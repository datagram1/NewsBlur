package com.newsblur.service;

import android.app.Service;
import android.content.ComponentCallbacks2;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.newsblur.R;
import com.newsblur.activity.NbActivity;
import com.newsblur.database.BlurDatabaseHelper;
import static com.newsblur.database.BlurDatabaseHelper.closeQuietly;
import com.newsblur.database.DatabaseConstants;
import com.newsblur.domain.SocialFeed;
import com.newsblur.domain.Story;
import com.newsblur.network.APIConstants;
import com.newsblur.network.APIManager;
import com.newsblur.network.domain.FeedFolderResponse;
import com.newsblur.network.domain.NewsBlurResponse;
import com.newsblur.network.domain.StoriesResponse;
import com.newsblur.network.domain.UnreadCountResponse;
import com.newsblur.network.domain.UnreadStoryHashesResponse;
import com.newsblur.util.AppConstants;
import com.newsblur.util.FeedSet;
import com.newsblur.util.FileCache;
import com.newsblur.util.NetworkUtils;
import com.newsblur.util.PrefsUtils;
import com.newsblur.util.ReadingAction;
import com.newsblur.util.ReadFilter;
import com.newsblur.util.StoryOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A background service to handle synchronisation with the NB servers.
 *
 * It is the design goal of this service to handle all communication with the API.
 * Activities and fragments should enqueue actions in the DB or use the methods
 * provided herein to request an action and let the service handle things.
 *
 * Per the contract of the Service class, at most one instance shall be created. It
 * will be preserved and re-used where possible.  Additionally, regularly scheduled
 * invocations are requested via the Main activity and BootReceiver.
 *
 * The service will notify all running activities of an update before, during, and
 * after sync operations are performed.  Activities can then refresh views and
 * query this class to see if progress indicators should be active.
 */
public class NBSyncService extends Service {

    /**
     * Mode switch for which newly received stories are suitable for display so
     * that they don't disrupt actively visible pager and list offsets.
     */
    public enum ActivationMode { ALL, OLDER, NEWER };

    private static final Object WAKELOCK_MUTEX = new Object();
    private static final Object PENDING_FEED_MUTEX = new Object();

    private volatile static boolean ActionsRunning = false;
    private volatile static boolean CleanupRunning = false;
    private volatile static boolean FFSyncRunning = false;
    private volatile static boolean StorySyncRunning = false;
    private volatile static boolean HousekeepingRunning = false;
    private volatile static boolean RecountsRunning = false;

    private volatile static boolean DoFeedsFolders = false;
    private volatile static boolean DoUnreads = false;
    private volatile static boolean HaltNow = false;
    private volatile static ActivationMode ActMode = ActivationMode.ALL;
    private volatile static long ModeCutoff = 0L;

    public volatile static Boolean isPremium = null;
    public volatile static Boolean isStaff = null;

    private volatile static boolean isMemoryLow = false;
    private static long lastFeedCount = 0L;
    private static long lastFFReadMillis = 0L;
    private static long lastFFWriteMillis = 0L;

    /** Feed set that we need to sync immediately for the UI. */
    private static FeedSet PendingFeed;
    private static Integer PendingFeedTarget = 0;

    /** Feed sets that the API has said to have no more pages left. */
    private static Set<FeedSet> ExhaustedFeeds;
    static { ExhaustedFeeds = new HashSet<FeedSet>(); }
    /** The number of pages we have collected for the given feed set. */
    private static Map<FeedSet,Integer> FeedPagesSeen;
    static { FeedPagesSeen = new HashMap<FeedSet,Integer>(); }
    /** The number of stories we have collected for the given feed set. */
    private static Map<FeedSet,Integer> FeedStoriesSeen;
    static { FeedStoriesSeen = new HashMap<FeedSet,Integer>(); }

    /** Actions that may need to be double-checked locally due to overlapping API calls. */
    private static List<ReadingAction> FollowupActions;
    static { FollowupActions = new ArrayList<ReadingAction>(); }

    /** Feed IDs (API stype) that have been acted upon and need a double-check for counts. */
    private static Set<FeedSet> RecountCandidates;
    static { RecountCandidates = new HashSet<FeedSet>(); }
    private volatile static boolean FlushRecounts = false;

    Set<String> orphanFeedIds;

    private ExecutorService primaryExecutor;
    OriginalTextService originalTextService;
    UnreadsService unreadsService;
    ImagePrefetchService imagePrefetchService;

    PowerManager.WakeLock wl = null;
	APIManager apiManager;
    BlurDatabaseHelper dbHelper;
    private int lastStartIdCompleted = -1;

	@Override
	public void onCreate() {
		super.onCreate();
        if (AppConstants.VERBOSE_LOG) Log.d(this.getClass().getName(), "onCreate");
        HaltNow = false;
        PowerManager pm = (PowerManager) getApplicationContext().getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.getClass().getSimpleName());
        wl.setReferenceCounted(true);

        primaryExecutor = Executors.newFixedThreadPool(1);
	}

    /**
     * Services can be constructed synchrnously by the Main thread, so don't do expensive
     * parts of construction in onCreate, but save them for when we are in our own thread.
     */
    private void finishConstruction() {
        if (apiManager == null) {
            apiManager = new APIManager(this);
            dbHelper = new BlurDatabaseHelper(this);
            originalTextService = new OriginalTextService(this);
            unreadsService = new UnreadsService(this);
            imagePrefetchService = new ImagePrefetchService(this);
        }
    }

    /**
     * Called serially, once per "start" of the service.  This serves as a wakeup call
     * that the service should check for outstanding work.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, final int startId) {
        // only perform a sync if the app is actually running or background syncs are enabled
        if (PrefsUtils.isOfflineEnabled(this) || (NbActivity.getActiveActivityCount() > 0)) {
            // Services actually get invoked on the main system thread, and are not
            // allowed to do tangible work.  We spawn a thread to do so.
            Runnable r = new Runnable() {
                public void run() {
                    doSync(startId);
                }
            };
            primaryExecutor.execute(r);
        } else {
            Log.d(this.getClass().getName(), "Skipping sync: app not active and background sync not enabled.");
            stopSelf(startId);
        } 

        // indicate to the system that the service should be alive when started, but
        // needn't necessarily persist under memory pressure
        return Service.START_NOT_STICKY;
    }

    /**
     * Do the actual work of syncing.
     */
    private synchronized void doSync(final int startId) {
        try {
            if (HaltNow) return;

            incrementRunningChild();
            finishConstruction();

            if (AppConstants.VERBOSE_LOG) Log.d(this.getClass().getName(), "starting primary sync");

            if (NbActivity.getActiveActivityCount() < 1) {
                // if the UI isn't running, politely run at background priority
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } else {
                // if the UI is running, run just one step below normal priority so we don't step on async tasks that are updating the UI
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE);
            }

            // do this even if background syncs aren't enabled, because it absolutely must happen
            // on all devices
            housekeeping();

            // check to see if we are on an allowable network only after ensuring we have CPU
            if (!(PrefsUtils.isBackgroundNetworkAllowed(this) || (NbActivity.getActiveActivityCount() > 0))) {
                Log.d(this.getClass().getName(), "Abandoning sync: app not active and network type not appropriate for background sync.");
                return;
            }

            originalTextService.start(startId);

            // first: catch up
            syncActions();
            
            // these requests are expressly enqueued by the UI/user, do them next
            syncPendingFeedStories();

            syncMetadata(startId);

            checkRecounts();

            unreadsService.start(startId);

            imagePrefetchService.start(startId);

            finishActions();

            if (AppConstants.VERBOSE_LOG) Log.d(this.getClass().getName(), "finishing primary sync");

        } catch (Exception e) {
            Log.e(this.getClass().getName(), "Sync error.", e);
        } finally {
            decrementRunningChild(startId);
        }
    }

    /**
     * Check for upgrades and wipe the DB if necessary, and do DB maintenance
     */
    private void housekeeping() {
        try {
            boolean upgraded = PrefsUtils.checkForUpgrade(this);
            if (upgraded) {
                HousekeepingRunning = true;
                NbActivity.updateAllActivities(false);
                // wipe the local DB
                dbHelper.dropAndRecreateTables();
                NbActivity.updateAllActivities(true);
                // in case this is the first time we have run since moving the cache to the new location,
                // blow away the old version entirely. This line can be removed some time well after
                // v61+ is widely deployed
                FileCache.cleanUpOldCache(this);
                PrefsUtils.updateVersion(this);
            }

            boolean autoVac = PrefsUtils.isTimeToVacuum(this);
            // this will lock up the DB for a few seconds, only do it if the UI is hidden
            if (NbActivity.getActiveActivityCount() > 0) autoVac = false;
            
            if (upgraded || autoVac) {
                HousekeepingRunning = true;
                NbActivity.updateAllActivities(false);
                PrefsUtils.updateLastVacuumTime(this);
                Log.i(this.getClass().getName(), "rebuilding DB . . .");
                dbHelper.vacuum();
                Log.i(this.getClass().getName(), ". . . . done rebuilding DB");
            }
        } finally {
            if (HousekeepingRunning) {
                HousekeepingRunning = false;
                NbActivity.updateAllActivities(true);
            }
        }
    }

    /**
     * Perform any reading actions the user has done before we do anything else.
     */
    private void syncActions() {
        if (stopSync()) return;

        Cursor c = null;
        try {
            c = dbHelper.getActions(false);
            if (c.getCount() < 1) return;

            ActionsRunning = true;
            NbActivity.updateAllActivities(false);

            actionsloop : while (c.moveToNext()) {
                String id = c.getString(c.getColumnIndexOrThrow(DatabaseConstants.ACTION_ID));
                ReadingAction ra;
                try {
                    ra = ReadingAction.fromCursor(c);
                } catch (IllegalArgumentException e) {
                    Log.e(this.getClass().getName(), "error unfreezing ReadingAction", e);
                    dbHelper.clearAction(id);
                    continue actionsloop;
                }
                    
                NewsBlurResponse response = ra.doRemote(apiManager);

                // if we attempted a call and it failed, do not mark the action as done
                if (response != null) {
                    if (response.isError()) {
                        if (response.isUserError()) {
                            Log.d(this.getClass().getName(), "Discarding reading action with user error.");
                        } else {
                            continue actionsloop;
                        }
                    }
                }

                dbHelper.clearAction(id);
                FollowupActions.add(ra);
            }
        } finally {
            closeQuietly(c);
            if (ActionsRunning) {
                ActionsRunning = false;
                NbActivity.updateAllActivities(false);
            }
        }
    }

    /**
     * Some actions have a final, local step after being done remotely to ensure in-flight
     * API actions didn't race-overwrite them.  Do these, and then clean up the DB.
     */
    private void finishActions() {
        if (HaltNow) return;
        if (FollowupActions.size() < 1) return;

        for (ReadingAction ra : FollowupActions) {
            ra.doLocal(dbHelper);
        }
        FollowupActions.clear();
        NbActivity.updateAllActivities(false);
    }

    /**
     * The very first step of a sync - get the feed/folder list, unread counts, and
     * unread hashes. Doing this resets pagination on the server!
     */
    private void syncMetadata(int startId) {
        if (stopSync()) return;
        if (ActMode != ActivationMode.ALL) return;

        if (DoFeedsFolders || PrefsUtils.isTimeToAutoSync(this)) {
            PrefsUtils.updateLastSyncTime(this);
            DoFeedsFolders = false;
        } else {
            return;
        }

        // cleanup is expensive, so do it as part of the metadata sync
        CleanupRunning = true;
        NbActivity.updateAllActivities(false);
        dbHelper.cleanupStories(PrefsUtils.isKeepOldStories(this));
        dbHelper.cleanupStoryText();
        imagePrefetchService.imageCache.cleanup(dbHelper.getAllStoryImages());
        CleanupRunning = false;
        NbActivity.updateAllActivities(false);

        // cleanup may have taken a while, so re-check our running status
        if (stopSync()) return;
        if (ActMode != ActivationMode.ALL) return;

        FFSyncRunning = true;
        NbActivity.updateAllActivities(false);

        // there is a rare issue with feeds that have no folder.  capture them for workarounds.
        Set<String> debugFeedIds = new HashSet<String>();
        orphanFeedIds = new HashSet<String>();

        try {
            // a metadata sync invalidates pagination and feed status
            ExhaustedFeeds.clear();
            FeedPagesSeen.clear();
            FeedStoriesSeen.clear();
            UnreadsService.clearHashes();
            RecountCandidates.clear();

            FeedFolderResponse feedResponse = apiManager.getFolderFeedMapping(true);

            if (feedResponse == null) {
                return;
            }

            // if the response says we aren't logged in, clear the DB and prompt for login. We test this
            // here, since this the first sync call we make on launch if we believe we are cookied.
            if (! feedResponse.isAuthenticated) {
                PrefsUtils.logout(this);
                return;
            }

            lastFFReadMillis = feedResponse.readTime;
            long startTime = System.currentTimeMillis();

            isPremium = feedResponse.isPremium;
            isStaff = feedResponse.isStaff;

            // clean out the feed / folder tables
            dbHelper.cleanupFeedsFolders();

            // data for the folder and folder-feed-mapping tables
            List<ContentValues> folderValues = new ArrayList<ContentValues>();
            List<ContentValues> ffmValues = new ArrayList<ContentValues>();
            for (Entry<String, List<Long>> entry : feedResponse.folders.entrySet()) {
                if (!TextUtils.isEmpty(entry.getKey())) {
                    String folderName = entry.getKey().trim();
                    if (!TextUtils.isEmpty(folderName)) {
                        ContentValues values = new ContentValues();
                        values.put(DatabaseConstants.FOLDER_NAME, folderName);
                        folderValues.add(values);
                    }

                    for (Long feedId : entry.getValue()) {
                        ContentValues values = new ContentValues(); 
                        values.put(DatabaseConstants.FEED_FOLDER_FEED_ID, feedId);
                        values.put(DatabaseConstants.FEED_FOLDER_FOLDER_NAME, folderName);
                        ffmValues.add(values);
                        // note all feeds that belong to some folder
                        debugFeedIds.add(Long.toString(feedId));
                    }
                }
            }

            // data for the feeds table
            List<ContentValues> feedValues = new ArrayList<ContentValues>();
            feedaddloop: for (String feedId : feedResponse.feeds.keySet()) {
                // sanity-check that the returned feeds actually exist in a folder or at the root
                // if they do not, they should neither display nor count towards unread numbers
                if (! debugFeedIds.contains(feedId)) {
                    Log.w(this.getClass().getName(), "Found and ignoring un-foldered feed: " + feedId );
                    orphanFeedIds.add(feedId);
                    continue feedaddloop;
                }
                if (! feedResponse.feeds.get(feedId).active) {
                    // the feed is disabled/hidden, pretend it doesn't exist
                    continue feedaddloop;
                }
                feedValues.add(feedResponse.feeds.get(feedId).getValues());
            }
            
            // data for the the social feeds table
            List<ContentValues> socialFeedValues = new ArrayList<ContentValues>();
            for (SocialFeed feed : feedResponse.socialFeeds) {
                socialFeedValues.add(feed.getValues());
            }
            
            dbHelper.insertFeedsFolders(feedValues, folderValues, ffmValues, socialFeedValues);

            // populate the starred stories count table
            dbHelper.updateStarredStoriesCount(feedResponse.starredCount);

            lastFFWriteMillis = System.currentTimeMillis() - startTime;
            lastFeedCount = feedValues.size();

            unreadsService.start(startId);
            UnreadsService.doMetadata();

        } finally {
            FFSyncRunning = false;
            NbActivity.updateAllActivities(true);
        }

    }

    /**
     * See if any feeds have been touched in a way that require us to double-check unread counts;
     */
    private void checkRecounts() {
        if (stopSync()) return;
        if (!FlushRecounts) return;

        try {
            if (RecountCandidates.size() < 1) return;

            RecountsRunning = true;
            NbActivity.updateAllActivities(false);

            // if we are offline, the best we can do is perform a local unread recount and
            // save the true one for when we go back online.
            if (!NetworkUtils.isOnline(this)) {
                for (FeedSet fs : RecountCandidates) {
                    dbHelper.updateLocalFeedCounts(fs);
                }
            } else {
                Set<String> apiIds = new HashSet<String>();
                for (FeedSet fs : RecountCandidates) {
                    apiIds.addAll(fs.getFlatFeedIds());
                }
                Log.d(this.getClass().getName(), "IDs to check: " + apiIds);
                UnreadCountResponse apiResponse = apiManager.getFeedUnreadCounts(apiIds);
                if ((apiResponse == null) || (apiResponse.isError())) {
                    Log.w(this.getClass().getName(), "Bad response to feed_unread_count");
                    return;
                }
                if (apiResponse.feeds != null ) {
                    for (Map.Entry<String,UnreadCountResponse.UnreadMD> entry : apiResponse.feeds.entrySet()) {
                        dbHelper.updateFeedCounts(entry.getKey(), entry.getValue().getValues());
                    }
                }
                if (apiResponse.socialFeeds != null ) {
                    for (Map.Entry<String,UnreadCountResponse.UnreadMD> entry : apiResponse.socialFeeds.entrySet()) {
                        String feedId = entry.getKey().replaceAll(APIConstants.VALUE_PREFIX_SOCIAL, "");
                        dbHelper.updateSocialFeedCounts(feedId, entry.getValue().getValues());
                    }
                }
                RecountCandidates.clear();
            }
        } finally {
            if (RecountsRunning) {
                RecountsRunning = false;
                NbActivity.updateAllActivities(false);
            }
            FlushRecounts = false;
        }
    }

    /**
     * Fetch stories needed because the user is actively viewing a feed or folder.
     */
    private void syncPendingFeedStories() {
        FeedSet fs = PendingFeed;
        boolean finished = false;
        if (fs == null) {
            return;
        }
        try {
            if (ExhaustedFeeds.contains(fs)) {
                Log.i(this.getClass().getName(), "No more stories for feed set: " + fs);
                finished = true;
                return;
            }
            
            if (!FeedPagesSeen.containsKey(fs)) {
                FeedPagesSeen.put(fs, 0);
                FeedStoriesSeen.put(fs, 0);
            }
            int pageNumber = FeedPagesSeen.get(fs);
            int totalStoriesSeen = FeedStoriesSeen.get(fs);

            StoryOrder order = PrefsUtils.getStoryOrder(this, fs);
            ReadFilter filter = PrefsUtils.getReadFilter(this, fs);
            
            while (totalStoriesSeen < PendingFeedTarget) {
                if (stopSync()) return;

                if (!fs.equals(PendingFeed)) {
                    // the active view has changed
                    if (fs == null) finished = true;
                    return; 
                }

                StorySyncRunning = true;
                NbActivity.updateAllActivities(false);

                pageNumber++;
                StoriesResponse apiResponse = apiManager.getStories(fs, pageNumber, order, filter);
            
                if (! isStoryResponseGood(apiResponse)) return;

                // if any reading activities happened during the API call, the result is now stale.
                // discard it and start again
                if (dbHelper.getActions(false).getCount() > 0) return;

                FeedPagesSeen.put(fs, pageNumber);
                totalStoriesSeen += apiResponse.stories.length;
                FeedStoriesSeen.put(fs, totalStoriesSeen);

                insertStories(apiResponse);
                NbActivity.updateAllActivities(true);
            
                if (apiResponse.stories.length == 0) {
                    ExhaustedFeeds.add(fs);
                    finished = true;
                    return;
                }
            }
            finished = true;

        } finally {
            if (StorySyncRunning) {
                StorySyncRunning = false;
                NbActivity.updateAllActivities(false);
            }
            synchronized (PENDING_FEED_MUTEX) {
                if (finished && fs.equals(PendingFeed)) PendingFeed = null;
            }
        }
    }

    private boolean isStoryResponseGood(StoriesResponse response) {
        if (response == null) {
            Log.e(this.getClass().getName(), "Null response received while loading stories.");
            return false;
        }
        if (response.stories == null) {
            Log.e(this.getClass().getName(), "Null stories member received while loading stories.");
            return false;
        }
        return true;
    }

    void insertStories(StoriesResponse apiResponse) {
        dbHelper.insertStories(apiResponse, ActMode, ModeCutoff);
    }

    void incrementRunningChild() {
        synchronized (WAKELOCK_MUTEX) {
            wl.acquire();
        }
    }

    void decrementRunningChild(int startId) {
        synchronized (WAKELOCK_MUTEX) {
            if (wl == null) return;
            if (wl.isHeld()) {
                wl.release();
            }
            // our wakelock reference counts.  only stop the service if it is in the background and if
            // we are the last thread to release the lock.
            if (!wl.isHeld()) {
                if (NbActivity.getActiveActivityCount() < 1) {
                    stopSelf(startId);
                }
                lastStartIdCompleted = startId;
            }
        }
    }

    static boolean stopSync(Context context) {
        if (HaltNow) {
            if (AppConstants.VERBOSE_LOG) Log.d(NBSyncService.class.getName(), "stopping sync, soft interrupt set.");
            return true;
        }
        if (context == null) return false;
        if (!NetworkUtils.isOnline(context)) return true;
        return false;
    }

    boolean stopSync() {
        return stopSync(this);
    }

    public void onTrimMemory (int level) {
        if (level > ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            isMemoryLow = true;
        }

        // this is also called when the UI is hidden, so double check if we need to
        // stop
        if ( (lastStartIdCompleted != -1) && (NbActivity.getActiveActivityCount() < 1)) {
            stopSelf(lastStartIdCompleted);
        }
    }

    /**
     * Is the main feed/folder list sync running?
     */
    public static boolean isFeedFolderSyncRunning() {
        return (HousekeepingRunning || ActionsRunning || FFSyncRunning || CleanupRunning || UnreadsService.running() || StorySyncRunning || OriginalTextService.running() || ImagePrefetchService.running());
    }

    /**
     * Is there a sync for a given FeedSet running?
     */
    public static boolean isFeedSetSyncing(FeedSet fs, Context context) {
        return (fs.equals(PendingFeed) && (!stopSync(context)));
    }

    public static String getSyncStatusMessage(Context context) {
        if (HousekeepingRunning) return context.getResources().getString(R.string.sync_status_housekeeping);
        if (ActionsRunning) return context.getResources().getString(R.string.sync_status_actions);
        if (FFSyncRunning) return context.getResources().getString(R.string.sync_status_ffsync);
        if (CleanupRunning) return context.getResources().getString(R.string.sync_status_cleanup);
        if (StorySyncRunning) return context.getResources().getString(R.string.sync_status_stories);
        if (UnreadsService.running()) return String.format(context.getResources().getString(R.string.sync_status_unreads), UnreadsService.getPendingCount());
        if (OriginalTextService.running()) return String.format(context.getResources().getString(R.string.sync_status_text), OriginalTextService.getPendingCount());
        if (ImagePrefetchService.running()) return String.format(context.getResources().getString(R.string.sync_status_images), ImagePrefetchService.getPendingCount());
        return null;
    }

    /**
     * Force a refresh of feed/folder data on the next sync, even if enough time
     * hasn't passed for an autosync.
     */
    public static void forceFeedsFolders() {
        DoFeedsFolders = true;
    }

    public static void flushRecounts() {
        FlushRecounts = true;
    }

    /**
     * Tell the service which stories can be activated if received. See ActivationMode.
     */
    public static void setActivationMode(ActivationMode actMode) {
        ActMode = actMode;
    }

    public static void setActivationMode(ActivationMode actMode, long modeCutoff) {
        ActMode = actMode;
        ModeCutoff = modeCutoff;
    }

    /**
     * Requests that the service fetch additional stories for the specified feed/folder. Returns
     * true if more will be fetched as a result of this request.
     *
     * @param desiredStoryCount the minimum number of stories to fetch.
     * @param totalSeen the number of stories the caller thinks they have seen for the FeedSet
     *        or a negative number if the caller trusts us to track for them
     */
    public static boolean requestMoreForFeed(FeedSet fs, int desiredStoryCount, int callerSeen) {
        if (ExhaustedFeeds.contains(fs)) {
            if (AppConstants.VERBOSE_LOG) Log.i(NBSyncService.class.getName(), "rejecting request for feedset that is exhaused");
            return false;
        }

        synchronized (PENDING_FEED_MUTEX) {
            Integer alreadyPending = 0;
            if (fs.equals(PendingFeed)) alreadyPending = PendingFeedTarget;
            Integer alreadySeen = FeedStoriesSeen.get(fs);
            if (alreadySeen == null) alreadySeen = 0;
            if (callerSeen < alreadySeen) {
                // the caller is probably filtering and thinks they have fewer than we do, so
                // update our count to agree with them, and force-allow another requet
                alreadySeen = callerSeen;
                FeedStoriesSeen.put(fs, callerSeen);
                alreadyPending = 0;
            }

            if (AppConstants.VERBOSE_LOG) Log.d(NBSyncService.class.getName(), "have:" + alreadySeen + "  want:" + desiredStoryCount + " pending:" + alreadyPending);
            if (desiredStoryCount <= alreadySeen) {
                return false;
            }
            if (desiredStoryCount <= alreadyPending) {
                return false;
            }
            
            PendingFeed = fs;
            PendingFeedTarget = desiredStoryCount;
        }
        return true;
    }

    public static void clearPendingStoryRequest() {
        synchronized (PENDING_FEED_MUTEX) {
            PendingFeed = null;
        }
    }

    public static void resetFeeds() {
        ExhaustedFeeds.clear();
        FeedPagesSeen.clear();
        FeedStoriesSeen.clear();
    }

    public static void getOriginalText(String hash) {
        OriginalTextService.addHash(hash);
    }

    public static void addRecountCandidates(Set<FeedSet> fs) {
        RecountCandidates.addAll(fs);
    }

    public static void softInterrupt() {
        if (AppConstants.VERBOSE_LOG) Log.d(NBSyncService.class.getName(), "soft stop");
        HaltNow = true;
    }

    public static void resumeFromInterrupt() {
        HaltNow = false;
    }

    @Override
    public void onDestroy() {
        try {
            if (AppConstants.VERBOSE_LOG) Log.d(this.getClass().getName(), "onDestroy - stopping execution");
            HaltNow = true;
            if (unreadsService != null) unreadsService.shutdown();
            if (originalTextService != null) originalTextService.shutdown();
            if (imagePrefetchService != null) imagePrefetchService.shutdown();
            if (primaryExecutor != null) {
                primaryExecutor.shutdown();
                try {
                    primaryExecutor.awaitTermination(AppConstants.SHUTDOWN_SLACK_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    primaryExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            if (dbHelper != null) dbHelper.close();
            if (AppConstants.VERBOSE_LOG) Log.d(this.getClass().getName(), "onDestroy - execution halted");
            super.onDestroy();
        } catch (Exception ex) {
            Log.e(this.getClass().getName(), "unclean shutdown", ex);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static boolean isMemoryLow() {
        return isMemoryLow;
    }

    public static String getSpeedInfo() {
        StringBuilder s = new StringBuilder();
        s.append(lastFeedCount).append(" in ").append(lastFFReadMillis).append(" and ").append(lastFFWriteMillis);
        return s.toString();
    }

}
