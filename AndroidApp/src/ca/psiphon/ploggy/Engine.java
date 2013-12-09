/*
 * Copyright (c) 2013, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package ca.psiphon.ploggy;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Pair;

import ca.psiphon.ploggy.widgets.TimePickerPreference;

import com.squareup.otto.Subscribe;

/**
 * Coordinator for background Ploggy work.
 * 
 * The Engine:
 * - schedules friend status push/pulls
 * - schedules friend resource downloads
 * - maintains a worker thread pool for background tasks (pushing/pulling
 *   friends and handling friend requests
 * - runs the local location monitor
 * - (re)-starts and stops the local web server and Tor Hidden Service to
 *   handle requests from friends
 *   
 * An Engine instance is intended to be run via an Android Service set to
 * foreground mode (i.e., long running).
 */
public class Engine implements OnSharedPreferenceChangeListener, WebServer.RequestHandler {
    
    private static final String LOG_TAG = "Engine";
    
    private Context mContext;
    private Handler mHandler;
    private Runnable mRestartTask;
    private SharedPreferences mSharedPreferences;
    private ScheduledExecutorService mTaskThreadPool;
    private ExecutorService mPeerRequestThreadPool;
    private HashMap<String, ScheduledFuture<?>> mFriendPullTasks;
    private HashMap<String, ScheduledFuture<?>> mFriendDownloadTasks;
    
    private LocationMonitor mLocationMonitor;
    private WebServer mWebServer;
    private TorWrapper mTorWrapper;
    
    private static final int THREAD_POOL_SIZE = 30;

    public Engine(Context context) {
        Utils.initSecureRandom();
        mContext = context;
        mHandler = new Handler();
        // TODO: distinct instance of preferences for each persona
        // e.g., getSharedPreferencesName("persona1");
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    public synchronized void start() throws Utils.ApplicationError {
        Log.addEntry(LOG_TAG, "starting...");
        Events.register(this);
        mTaskThreadPool = Executors.newScheduledThreadPool(THREAD_POOL_SIZE);
        // Using a distinct worker thread pool and queue to manage peer
        // requests, so local tasks are not blocked by peer actions. Currently,
        // maximum number of simultaneous peer requests is expected to be
        // 2 * #friends as each friend could be performing a push/pull and download.
        mPeerRequestThreadPool = Executors.newFixedThreadPool(
                Math.max(THREAD_POOL_SIZE, Data.getInstance().getFriends().size()*2));
        mFriendPullTasks = new HashMap<String, ScheduledFuture<?>>();
        mFriendDownloadTasks = new HashMap<String, ScheduledFuture<?>>();
        mLocationMonitor = new LocationMonitor(this);
        mLocationMonitor.start();
        startHiddenService();
        schedulePullFromFriends();
        scheduleDownloadFromFriends();
        mSharedPreferences.registerOnSharedPreferenceChangeListener(this);
        Log.addEntry(LOG_TAG, "started");
    }

    public synchronized void stop() {
        Log.addEntry(LOG_TAG, "stopping...");
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        Events.unregister(this);
        stopHiddenService();
        if (mLocationMonitor != null) {
            mLocationMonitor.stop();
            mLocationMonitor = null;
        }
        if (mTaskThreadPool != null) {
            Utils.shutdownExecutorService(mTaskThreadPool);
            mTaskThreadPool = null;
            mFriendPullTasks = null;
            mFriendDownloadTasks = null;
        }
        if (mPeerRequestThreadPool != null) {
            Utils.shutdownExecutorService(mPeerRequestThreadPool);
            mPeerRequestThreadPool = null;
        }
        Log.addEntry(LOG_TAG, "stopped");
    }

    @Override
    public synchronized void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // Restart engine to apply changed preferences. Delay restart until user inputs are idle.
        // (This idle delay is important due to how SeekBarPreferences trigger onSharedPreferenceChanged
        // continuously as the user slides the seek bar). Delayed restart runs on main thread.
        if (mRestartTask == null) {
            mRestartTask = new Runnable() {
                @Override
                public void run() {
                    try {
                        stop();
                        start();
                    } catch (Utils.ApplicationError e) {
                        Log.addEntry(LOG_TAG, "failed restart engine after preference change");
                    }
                }
            };
        } else {
            mHandler.removeCallbacks(mRestartTask);
        }
        mHandler.postDelayed(mRestartTask, 5000);
    }

    public synchronized void submitTask(Runnable task) {
        mTaskThreadPool.submit(task);
    }
    
    @Override
    public synchronized void submitWebRequestTask(Runnable task) {
        mPeerRequestThreadPool.submit(task);
    }
    
    private void startHiddenService() throws Utils.ApplicationError {
        try {
            stopHiddenService();

            Data.Self self = Data.getInstance().getSelf();
            List<String> friendCertificates = new ArrayList<String>();
            for (Data.Friend friend : Data.getInstance().getFriends()) {
                friendCertificates.add(friend.mPublicIdentity.mX509Certificate);
            }
            mWebServer = new WebServer(
                    this,
                    new X509.KeyMaterial(self.mPublicIdentity.mX509Certificate, self.mPrivateIdentity.mX509PrivateKey),
                    friendCertificates);
            mWebServer.start();

            List<TorWrapper.HiddenServiceAuth> hiddenServiceAuths = new ArrayList<TorWrapper.HiddenServiceAuth>();
            for (Data.Friend friend : Data.getInstance().getFriends()) {
                hiddenServiceAuths.add(
                        new TorWrapper.HiddenServiceAuth(
                                friend.mPublicIdentity.mHiddenServiceHostname,
                                friend.mPublicIdentity.mHiddenServiceAuthCookie));
            }
            mTorWrapper = new TorWrapper(
                    TorWrapper.Mode.MODE_RUN_SERVICES,
                    hiddenServiceAuths,
                    new HiddenService.KeyMaterial(
                            self.mPublicIdentity.mHiddenServiceHostname,
                            self.mPublicIdentity.mHiddenServiceAuthCookie,
                            self.mPrivateIdentity.mHiddenServicePrivateKey),
                    mWebServer.getListeningPort());
            
            // TODO: in a background thread, monitor mTorWrapper.awaitStarted() to check for errors and retry... 
            mTorWrapper.start();
        } catch (IOException e) {
            throw new Utils.ApplicationError(LOG_TAG, e);
        }
    }
    
    private void stopHiddenService() {
        if (mTorWrapper != null) {
            mTorWrapper.stop();
        }
        if (mWebServer != null) {
            mWebServer.stop();
        }
    }
    
    public synchronized int getTorSocksProxyPort() throws Utils.ApplicationError {
        if (mTorWrapper != null) {
            return mTorWrapper.getSocksProxyPort();
        }
        throw new Utils.ApplicationError(LOG_TAG, "no Tor socks proxy");
    }
    
    @Subscribe
    public synchronized void onUpdatedSelf(Events.UpdatedSelf updatedSelf) {
        // Apply new transport and hidden service credentials
        try {
            startHiddenService();
        } catch (Utils.ApplicationError e) {
            Log.addEntry(LOG_TAG, "failed restart sharing service after self updated");
        }                        
    }

    @Subscribe
    public synchronized void onNewSelfLocation(Events.NewSelfLocation newSelfLocation) {
        // TODO: location fix timestamp vs. status update timestamp?
        // TODO: apply precision factor to long/lat/address
        // TODO: factor Location.getAccuracy() into precision?
        try {
            StringBuilder address = new StringBuilder();
            if (newSelfLocation.mAddress != null) {
                for (int i = 0; i < newSelfLocation.mAddress.getMaxAddressLineIndex(); i++) {
                    // TODO: internationalization
                    if (i > 0) address.append(", ");
                    address.append(newSelfLocation.mAddress.getAddressLine(i));
                }
            }
            Data.getInstance().updateSelfStatusLocation(
                    new Data.Location(
                            new Date(),
                            newSelfLocation.mLocation.getLatitude(),
                            newSelfLocation.mLocation.getLongitude(),
                            getIntPreference(R.string.preferenceLocationPrecisionInMeters),
                            address.toString()),
                    currentlySharingLocation());
        } catch (Utils.ApplicationError e) {
            Log.addEntry(LOG_TAG, "failed to update self status with new location");
        }
    }
    
    @Subscribe
    public synchronized void onUpdatedSelfStatus(Events.UpdatedSelfStatus updatedSelfStatus) {
        try {
            // Immediately push new status to all friends. If this fails for any reason,
            // implicitly fall back to friends pulling status.
            pushToFriends();
        } catch (Utils.ApplicationError e) {
            Log.addEntry(LOG_TAG, "failed push to friends after self status updated");
        }
    }
    
    @Subscribe
    public synchronized void onAddedFriend(Events.AddedFriend addedFriend) {
        // Apply new set of friends to web server and pull schedule
        // TODO: don't need to restart Tor, just web server
        //       (now need to restart Tor due to Hidden Service auth; but could use control interface instead?)
        try {
            startHiddenService();
            schedulePullFromFriends();
            scheduleDownloadFromFriends();
        } catch (Utils.ApplicationError e) {
            Log.addEntry(LOG_TAG, "failed restart sharing service after added friend");
        }
    }
    
    @Subscribe
    public synchronized void onRemovedFriend(Events.RemovedFriend removedFriend) {
        // Apply new set of friends to web server and pull scheduke
        // TODO: don't need to restart Tor, just web server
        try {
            startHiddenService();
            schedulePullFromFriends();
            scheduleDownloadFromFriends();
        } catch (Utils.ApplicationError e) {
            Log.addEntry(LOG_TAG, "failed restart sharing service after removed friend");
        }
    }

    @Subscribe
    public synchronized void onDisplayedMessages(Events.DisplayedMessages displayedMessages) {
        try {
            Data.getInstance().resetNewMessages();
        } catch (Utils.ApplicationError e) {
            Log.addEntry(LOG_TAG, "failed to reset new messages");
        }
    }

    @Subscribe
    public synchronized void onAddedDownload(Events.AddedDownload addedDownload) {
        // Schedule immediate download, if not already downloading from friend
        try {
            scheduleDownloadFromFriend(addedDownload.mFriendId);
        } catch (Utils.ApplicationError e) {
            Log.addEntry(LOG_TAG, "failed to schedule download from friend after added download");
        }
    }

    private void schedulePullFromFriends() throws Utils.ApplicationError {
        for (Data.Friend friend : Data.getInstance().getFriends()) {
            schedulePullFromFriend(friend.mId, true);
        }
    }
    
    private void schedulePullFromFriend(String friendId, boolean immediateInitialPull) throws Utils.ApplicationError {
        final String finalFriendId = friendId;
        Runnable task = new Runnable() {
            public void run() {
                Data data = Data.getInstance();
                try {
                    if (!mTorWrapper.isCircuitEstablished()) {
                        // TODO: TorWrapper could signal circuit established, triggering a pull, instead of waiting up to a full period for retry
                        return;
                    }
                    Data.Self self = data.getSelf();
                    Data.Friend friend = data.getFriendById(finalFriendId);
                    Log.addEntry(LOG_TAG, "pull status from: " + friend.mPublicIdentity.mNickname);
                    String response = WebClient.makeGetRequest(
                            new X509.KeyMaterial(self.mPublicIdentity.mX509Certificate, self.mPrivateIdentity.mX509PrivateKey),
                            friend.mPublicIdentity.mX509Certificate,
                            getTorSocksProxyPort(),
                            friend.mPublicIdentity.mHiddenServiceHostname,
                            Protocol.WEB_SERVER_VIRTUAL_PORT,
                            Protocol.PULL_STATUS_REQUEST_PATH);
                    Data.Status friendStatus = Json.fromJson(response, Data.Status.class);
                    data.updateFriendStatus(finalFriendId, friendStatus);
                    data.updateFriendLastReceivedStatusTimestamp(finalFriendId);
                } catch (Data.DataNotFoundError e) {
                    // Friend was deleted while pull was enqueued. Ignore error.
                    // RemovedFriend should eventually cancel schedule.
                } catch (Utils.ApplicationError e) {
                    try {
                        Log.addEntry(LOG_TAG, "failed to pull status from: " + data.getFriendById(finalFriendId).mPublicIdentity.mNickname);
                    } catch (Utils.ApplicationError e2) {
                        Log.addEntry(LOG_TAG, "failed to pull status");
                    }
                }
            }
        };

        // Cancel any existing pull schedule for this friend
        if (mFriendPullTasks.containsKey(friendId)) {
            mFriendPullTasks.get(friendId).cancel(false);
        }

        // TODO: scheduleAtFixedRate has backlog issue        
        int delay = getIntPreference(R.string.preferenceLocationPullFrequencyInMinutes)*60*1000;
        ScheduledFuture<?> future = mTaskThreadPool.scheduleWithFixedDelay(
                task, immediateInitialPull ? 0 : delay, delay, TimeUnit.MILLISECONDS);
        mFriendPullTasks.put(friendId, future);
    }

    private void pushToFriends() throws Utils.ApplicationError {
        // TODO: check for existing pushes in worker thread queue
        if (!mTorWrapper.isCircuitEstablished()) {
            // TODO: schedule another push in the future?
            return;
        }
        for (Data.Friend friend : Data.getInstance().getFriends()) {
            final String finalFriendId = friend.mId;
            Runnable task = new Runnable() {
                public void run() {
                    Data data = Data.getInstance();
                    try {
                        Data.Self self = data.getSelf();
                        Data.Status selfStatus = data.getSelfStatus();
                        Data.Friend friend = data.getFriendById(finalFriendId);
                        Log.addEntry(LOG_TAG, "push status to: " + friend.mPublicIdentity.mNickname);
                        WebClient.makeJsonPostRequest(
                                new X509.KeyMaterial(self.mPublicIdentity.mX509Certificate, self.mPrivateIdentity.mX509PrivateKey),
                                friend.mPublicIdentity.mX509Certificate,
                                getTorSocksProxyPort(),
                                friend.mPublicIdentity.mHiddenServiceHostname,
                                Protocol.WEB_SERVER_VIRTUAL_PORT,
                                Protocol.PUSH_STATUS_REQUEST_PATH,
                                Json.toJson(selfStatus));
                        data.updateFriendLastSentStatusTimestamp(finalFriendId);
                    } catch (Data.DataNotFoundError e) {
                        // Friend was deleted while push was enqueued. Ignore error.
                    } catch (Utils.ApplicationError e) {
                        try {
                            Log.addEntry(LOG_TAG, "failed to push status to: " + data.getFriendById(finalFriendId).mPublicIdentity.mNickname);
                        } catch (Utils.ApplicationError e2) {
                            Log.addEntry(LOG_TAG, "failed to push status");
                        }
                    }
                }
            };
            submitTask(task);
        }
    }

    private void scheduleDownloadFromFriends() throws Utils.ApplicationError {
        for (Data.Friend friend : Data.getInstance().getFriends()) {
            scheduleDownloadFromFriend(friend.mId);
        }
    }
    
    private void scheduleDownloadFromFriend(String friendId) throws Utils.ApplicationError {
        // Schedules one download (getNextInProgressDownload) per friend at a time.
        // Reuses pull frequency as a retry frequency for downloads in case of failure
        // TODO: use a different frequency? don't do polling retries (i.e., don't always wake every period)?

        final String finalFriendId = friendId;
        Runnable task = new Runnable() {
            public void run() {
                Data data = Data.getInstance();
                try {
                    if (!mTorWrapper.isCircuitEstablished()) {
                        // TODO: TorWrapper could signal circuit established, triggering a pull, instead of waiting up to a full period for retry
                        return;
                    }
                    Data.Self self = data.getSelf();
                    Data.Friend friend = data.getFriendById(finalFriendId);
                    while (true) {
                        Data.Download download = null;
                        try {
                            download = data.getNextInProgressDownload(finalFriendId);
                        } catch (Data.DataNotFoundError e) {
                            break;
                        }
                        // TODO: there's a potential race condition between getDownloadedSize and
                        // openDownloadResourceForAppending; we may want to lock the file first.
                        // However: currently only one thread downloads files for a given friend.
                        long downloadedSize = Downloads.getDownloadedSize(download);
                        if (downloadedSize == download.mSize) {
                            // Already downloaded complete file, but may have failed to commit
                            // the COMPLETED state change. Skip the download.
                        } else {
                            Log.addEntry(LOG_TAG, "download from: " + friend.mPublicIdentity.mNickname);
                            Pair<Long, Long> range = new Pair<Long, Long>(downloadedSize, (long)-1);
                            WebClient.makeGetRequest(
                                    new X509.KeyMaterial(self.mPublicIdentity.mX509Certificate, self.mPrivateIdentity.mX509PrivateKey),
                                    friend.mPublicIdentity.mX509Certificate,
                                    getTorSocksProxyPort(),
                                    friend.mPublicIdentity.mHiddenServiceHostname,
                                    Protocol.WEB_SERVER_VIRTUAL_PORT,
                                    Protocol.DOWNLOAD_REQUEST_PATH,
                                    Arrays.asList(new Pair<String, String>(Protocol.DOWNLOAD_REQUEST_RESOURCE_ID_PARAMETER, download.mResourceId)),
                                    range,
                                    Downloads.openDownloadResourceForAppending(download));
                        }
                        data.updateDownloadState(friend.mId, download.mResourceId, Data.Download.State.COMPLETE);
                        // TODO: WebClient post to event bus for download progress (replacing timer-based refreshes...)
                        // TODO: 404/403: denied by peer? -- change Download state to reflect this and don't retry (e.g., new state: CANCELLED)
                        // TODO: update some last received timestamp?
                    }
                } catch (Data.DataNotFoundError e) {
                    // Friend was deleted while pull was enqueued. Ignore error.
                    // RemovedFriend should eventually cancel schedule.
                } catch (Utils.ApplicationError e) {
                    try {
                        Log.addEntry(LOG_TAG, "failed to download from: " + data.getFriendById(finalFriendId).mPublicIdentity.mNickname);
                    } catch (Utils.ApplicationError e2) {
                        Log.addEntry(LOG_TAG, "failed to download status");
                    }
                }
            }
        };

        // Only schedule if there's no existing task
        if (mFriendDownloadTasks.containsKey(friendId) &&
                !mFriendDownloadTasks.get(friendId).isDone()) {
            return;
        }

        // TODO: scheduleAtFixedRate has backlog issue
        int delay = getIntPreference(R.string.preferenceLocationPullFrequencyInMinutes)*60*1000;
        ScheduledFuture<?> future = mTaskThreadPool.scheduleWithFixedDelay(task, 0, delay, TimeUnit.MILLISECONDS);
        mFriendDownloadTasks.put(friendId, future);
    }
    
    public synchronized Data.Status handlePullStatusRequest(String friendCertificate) throws Utils.ApplicationError {
        // Friend is requesting (pulling) self status
        // TODO: cancel any pending push to this friend?
        try {
            Data data = Data.getInstance();
            Data.Friend friend = data.getFriendByCertificate(friendCertificate);
            Data.Status status = data.getSelfStatus();
            // TODO: we don't yet know the friend really received the response bytes
            data.updateFriendLastSentStatusTimestamp(friend.mId);
            Log.addEntry(LOG_TAG, "served pull status request for " + friend.mPublicIdentity.mNickname);
            return status;
        } catch (Data.DataNotFoundError e) {
            throw new Utils.ApplicationError(LOG_TAG, "failed to handle pull status request: friend not found");
        }
    }
    
    public synchronized void handlePushStatusRequest(String friendCertificate, Data.Status status) throws Utils.ApplicationError  {
        // Friend is pushing their own status
        try {
            Data data = Data.getInstance();
            Data.Friend friend = data.getFriendByCertificate(friendCertificate);
            data.updateFriendStatus(friend.mId, status);
            // TODO: we don't yet know the friend really received the response bytes
            data.updateFriendLastReceivedStatusTimestamp(friend.mId);
            // Reschedule (delay) any outstanding pull from this friend
            schedulePullFromFriend(friend.mId, false);
            // Immediately start any pending downloads, since we know friend is online
            scheduleDownloadFromFriend(friend.mId);
            Log.addEntry(LOG_TAG, "served push status request for " + friend.mPublicIdentity.mNickname);
        } catch (Data.DataNotFoundError e) {
            throw new Utils.ApplicationError(LOG_TAG, "failed to handle push status request: friend not found");
        }
    }
    
    public synchronized WebServer.RequestHandler.DownloadResponse handleDownloadRequest(
            String friendCertificate, String resourceId, Pair<Long, Long> range) throws Utils.ApplicationError  {
        try {
            Data data = Data.getInstance();
            Data.Friend friend = data.getFriendByCertificate(friendCertificate);
            Data.LocalResource localResource = data.getLocalResource(resourceId);
            InputStream inputStream = Resources.openLocalResourceForReading(localResource, range);
            // TODO: update last some last sent timestamp?
            Log.addEntry(LOG_TAG, "served download request for " + friend.mPublicIdentity.mNickname);
            return new DownloadResponse(localResource.mMimeType, inputStream);
        } catch (Data.DataNotFoundError e) {
            throw new Utils.ApplicationError(LOG_TAG, "failed to handle download request: friend or resource not found");
        }
    }
    
    public synchronized Context getContext() {
        return mContext;
    }
    
    public synchronized boolean getBooleanPreference(int keyResID) throws Utils.ApplicationError {
        String key = mContext.getString(keyResID);
        // Defaults which are "false" are not present in the preferences file
        // if (!mSharedPreferences.contains(key)) {...}
        // TODO: this is ambiguous: there's now no test for failure to initialize defaults
        return mSharedPreferences.getBoolean(key, false);        
    }
    
    public synchronized int getIntPreference(int keyResID) throws Utils.ApplicationError {
        String key = mContext.getString(keyResID);
        if (!mSharedPreferences.contains(key)) {
            throw new Utils.ApplicationError(LOG_TAG, "missing preference default: " + key);
        }
        return mSharedPreferences.getInt(key, 0);        
    }

    public synchronized boolean currentlySharingLocation() throws Utils.ApplicationError {
        if (!getBooleanPreference(R.string.preferenceAutomaticLocationSharing)) {
            return false;
        }
        
        Calendar now = Calendar.getInstance();
        
        if (getBooleanPreference(R.string.preferenceLimitLocationSharingTime)) {
            int currentHour = now.get(Calendar.HOUR_OF_DAY);
            int currentMinute = now.get(Calendar.MINUTE);
            
            String sharingTimeNotBefore = mSharedPreferences.getString(
                    mContext.getString(R.string.preferenceLimitLocationSharingTimeNotBefore), "");
            int notBeforeHour = TimePickerPreference.getHour(sharingTimeNotBefore);
            int notBeforeMinute = TimePickerPreference.getMinute(sharingTimeNotBefore);
            String sharingTimeNotAfter = mSharedPreferences.getString(
                    mContext.getString(R.string.preferenceLimitLocationSharingTimeNotAfter), "");
            int notAfterHour = TimePickerPreference.getHour(sharingTimeNotAfter);
            int notAfterMinute = TimePickerPreference.getMinute(sharingTimeNotAfter);

            if ((currentHour < notBeforeHour) ||
                (currentHour == notBeforeHour && currentMinute < notBeforeMinute) ||
                (currentHour > notAfterHour) ||
                (currentHour == notAfterHour && currentMinute > notAfterMinute)) {
                return false;
            }
        }

        // Map current Calendar.DAY_OF_WEEK (1..7) to preference's SUNDAY..SATURDAY symbols
        assert(Calendar.SUNDAY == 1 && Calendar.SATURDAY == 7);
        String[] weekdays = mContext.getResources().getStringArray(R.array.weekdays);
        String currentWeekday = weekdays[now.get(Calendar.DAY_OF_WEEK) - 1];

        Set<String> sharingDays = mSharedPreferences.getStringSet(
                mContext.getString(R.string.preferenceLimitLocationSharingDay),
                new HashSet<String>());
    
        if (!sharingDays.contains(currentWeekday)) {
            return false;
        }

        return true;
    }
}
