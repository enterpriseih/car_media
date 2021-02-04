package com.faw.car.media.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.service.media.MediaBrowserService;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CarMediaService extends Service {

    private final String TAG = "CAR.MEDIA";
    private final IBinder mBinder = new CarMediaBinder();

    private static final String SOURCE_KEY = "media_source";
    private static final String PLAYBACK_STATE_KEY = "playback_state";
    private static final String SHARED_PREF = "com.android.car.media.car_media_service";
    private static final String PACKAGE_NAME_SEPARATOR = ",";

    private Context mContext;
    private MediaSessionManager mMediaSessionManager;
    private MediaSessionUpdater mMediaSessionUpdater;
    private String mPrimaryMediaPackage;
    private SharedPreferences mSharedPrefs;
    // MediaController for the current active user's active media session. This controller can be
    // null if playback has not been started yet.
    private MediaController mActiveUserMediaController;
    private SessionChangedListener mSessionsListener;
    private boolean mStartPlayback;

    private RemoteCallbackList<ICarMediaSourceListener> mMediaSourceListeners =
            new RemoteCallbackList();

    // Handler to receive PlaybackState callbacks from the active media controller.
    private Handler mHandler;
    private HandlerThread mHandlerThread;

    /** The package name of the last media source that was removed while being primary. */
    private String mRemovedMediaSourcePackage;

    /**
     * Listens to {@link Intent#ACTION_PACKAGE_REMOVED} and {@link Intent#ACTION_PACKAGE_REPLACED}
     * so we can reset the media source to null when its application is uninstalled, and restore it
     * when the application is reinstalled.
     */
    private BroadcastReceiver mPackageRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getData() == null) {
                return;
            }
            String intentPackage = intent.getData().getSchemeSpecificPart();
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                if (mPrimaryMediaPackage != null && mPrimaryMediaPackage.equals(intentPackage)) {
                    mRemovedMediaSourcePackage = intentPackage;
                    setPrimaryMediaSource(null);
                }
            } else if (Intent.ACTION_PACKAGE_REPLACED.equals(intent.getAction())
                    || Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                if (mRemovedMediaSourcePackage != null
                        && mRemovedMediaSourcePackage.equals(intentPackage)
                        && isMediaService(intentPackage)) {
                    setPrimaryMediaSource(mRemovedMediaSourcePackage);
                }
            }
        }
    };

    private BroadcastReceiver mUserSwitchReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateMediaSessionCallbackForCurrentUser();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        return mBinder;
    }

    public class CarMediaBinder extends ICarMediaManager.Stub {

        @Override
        public String getMediaSource() throws RemoteException {
            return CarMediaService.this.getMediaSource();
        }

        @Override
        public void setMediaSource(String mediaSource) throws RemoteException {
            CarMediaService.this.setMediaSource(mediaSource);
        }

        @Override
        public void registerMediaSourceListener(ICarMediaSourceListener callback) throws RemoteException {
            CarMediaService.this.registerMediaSourceListener(callback);
        }

        @Override
        public void unregisterMediaSourceListener(ICarMediaSourceListener callback) throws RemoteException {
            CarMediaService.this.unregisterMediaSourceListener(callback);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    public void constructService(Context context) {
        mContext = context;
        mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
        mMediaSessionUpdater = new MediaSessionUpdater();

        mHandlerThread = new HandlerThread("CAR.MEDIA");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mPackageRemovedReceiver, filter);

        IntentFilter userSwitchFilter = new IntentFilter();
        userSwitchFilter.addAction("android.intent.action.USER_SWITCHED");
        mContext.registerReceiver(mUserSwitchReceiver, userSwitchFilter);

        updateMediaSessionCallbackForCurrentUser();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        constructService(this);
        init();
    }

    public void init() {
        mHandler.post(() -> {
            mSharedPrefs = mContext.getSharedPreferences(SHARED_PREF, Context.MODE_PRIVATE);
            mPrimaryMediaPackage = getLastMediaPackage();
            mStartPlayback = mSharedPrefs.getInt(PLAYBACK_STATE_KEY, PlaybackState.STATE_NONE)
                    == PlaybackState.STATE_PLAYING;
            notifyListeners();
        });
//        CarLocalServices.getService(CarUserService.class).runOnUser0Unlock(() -> {
//            mSharedPrefs = mContext.getSharedPreferences(SHARED_PREF, Context.MODE_PRIVATE);
//            mPrimaryMediaPackage = getLastMediaPackage();
//            mStartPlayback = mSharedPrefs.getInt(PLAYBACK_STATE_KEY, PlaybackState.STATE_NONE)
//                    == PlaybackState.STATE_PLAYING;
//            notifyListeners();
//        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        release();
    }

    public void release() {
        mMediaSessionUpdater.unregisterCallbacks();
    }

    public void dump(PrintWriter writer) {
        writer.println("*CarMediaService*");
        writer.println("\tCurrent media package: " + mPrimaryMediaPackage);
        if (mActiveUserMediaController != null) {
            writer.println(
                    "\tCurrent media controller: " + mActiveUserMediaController.getPackageName());
        }
        writer.println("\tNumber of active media sessions: "
                + mMediaSessionManager.getActiveSessions(new ComponentName(this, NotificationListener.class)).size());
    }

    /**
     */
    public synchronized void setMediaSource(String packageName) {
//        ICarImpl.assertPermission(mContext, android.Manifest.permission.MEDIA_CONTENT_CONTROL);
        setPrimaryMediaSource(packageName);
    }

    /**
     */
    public synchronized String getMediaSource() {
        return mPrimaryMediaPackage;
    }

    /**
     */
    public synchronized void registerMediaSourceListener(ICarMediaSourceListener callback) {
        mMediaSourceListeners.register(callback);
    }

    /**
     */
    public synchronized void unregisterMediaSourceListener(ICarMediaSourceListener callback) {
        mMediaSourceListeners.unregister(callback);
    }

    private void updateMediaSessionCallbackForCurrentUser() {
        if (mSessionsListener != null) {
            mMediaSessionManager.removeOnActiveSessionsChangedListener(mSessionsListener);
        }
        mSessionsListener = new SessionChangedListener(0);
        mMediaSessionManager.addOnActiveSessionsChangedListener(mSessionsListener, null,
                /*ActivityManager.getCurrentUser()*/ null);
        mMediaSessionUpdater.registerCallbacks(mMediaSessionManager.getActiveSessions(
                new ComponentName(this, NotificationListener.class)/*, ActivityManager.getCurrentUser()*/));
    }

    /**
     * Attempts to play the current source using MediaController.TransportControls.play()
     */
    private void play() {
        if (mActiveUserMediaController != null) {
            MediaController.TransportControls controls = mActiveUserMediaController.getTransportControls();
            if (controls != null) {
                controls.play();
            }
        }
    }

    /**
     * Attempts to stop the current source using MediaController.TransportControls.stop()
     */
    private void stop() {
        if (mActiveUserMediaController != null) {
            MediaController.TransportControls controls = mActiveUserMediaController.getTransportControls();
            if (controls != null) {
                controls.stop();
            }
        }
    }

    private class SessionChangedListener implements MediaSessionManager.OnActiveSessionsChangedListener {
        private final int mCurrentUser;

        SessionChangedListener(int currentUser) {
            mCurrentUser = currentUser;
        }

        @Override
        public void onActiveSessionsChanged(List<MediaController> controllers) {
//            if (ActivityManager.getCurrentUser() != mCurrentUser) {
//                Log.e(TAG, "Active session callback for old user: " + mCurrentUser);
//                return;
//            }
            mMediaSessionUpdater.registerCallbacks(controllers);
        }
    }

    private class MediaControllerCallback extends MediaController.Callback {

        private final MediaController mMediaController;
        private int mPreviousPlaybackState;

        private MediaControllerCallback(MediaController mediaController) {
            mMediaController = mediaController;
            PlaybackState state = mediaController.getPlaybackState();
            mPreviousPlaybackState = (state == null) ? PlaybackState.STATE_NONE : state.getState();
        }

        private void register() {
            mMediaController.registerCallback(this);
        }

        private void unregister() {
            mMediaController.unregisterCallback(this);
        }

        @Override
        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
            if (state.getState() == PlaybackState.STATE_PLAYING
                    && state.getState() != mPreviousPlaybackState) {
                setPrimaryMediaSource(mMediaController.getPackageName());
            }
            mPreviousPlaybackState = state.getState();
        }
    }

    private class MediaSessionUpdater {
        private Map<MediaSession.Token, MediaControllerCallback> mCallbacks = new HashMap<>();

        /**
         * Register a {@link MediaControllerCallback} for each given controller. Note that if a
         * controller was already watched, we don't register a callback again. This prevents an
         * undesired revert of the primary media source. Callbacks for previously watched
         * controllers that are not present in the given list are unregistered.
         */
        private void registerCallbacks(List<MediaController> newControllers) {

            List<MediaController> additions = new ArrayList<>(newControllers.size());
            Map<MediaSession.Token, MediaControllerCallback> updatedCallbacks =
                    new HashMap<>(newControllers.size());

            for (MediaController controller : newControllers) {
                MediaSession.Token token = controller.getSessionToken();
                MediaControllerCallback callback = mCallbacks.get(token);
                if (callback == null) {
                    callback = new MediaControllerCallback(controller);
                    callback.register();
                    additions.add(controller);
                }
                updatedCallbacks.put(token, callback);
            }

            for (MediaSession.Token token : mCallbacks.keySet()) {
                if (!updatedCallbacks.containsKey(token)) {
                    mCallbacks.get(token).unregister();
                }
            }

            mCallbacks = updatedCallbacks;
            updatePrimaryMediaSourceWithCurrentlyPlaying(additions);
            // If there are no playing media sources, and we don't currently have the controller
            // for the active source, check the new active sessions for a matching controller.
            if (mActiveUserMediaController == null) {
                updateActiveMediaController(additions);
            }
        }

        /**
         * Unregister all MediaController callbacks
         */
        private void unregisterCallbacks() {
            for (Map.Entry<MediaSession.Token, MediaControllerCallback> entry : mCallbacks.entrySet()) {
                entry.getValue().unregister();
            }
        }
    }

    /**
     * Updates the primary media source, then notifies content observers of the change
     */
    private synchronized void setPrimaryMediaSource(@Nullable String packageName) {
        if (mPrimaryMediaPackage != null && mPrimaryMediaPackage.equals((packageName))) {
            return;
        }

        stop();

        mStartPlayback = false;
        mPrimaryMediaPackage = packageName;
        updateActiveMediaController(mMediaSessionManager
                .getActiveSessions(new ComponentName(this, NotificationListener.class)/*, ActivityManager.getCurrentUser()*/));

        if (mSharedPrefs != null) {
            if (!TextUtils.isEmpty(mPrimaryMediaPackage)) {
                saveLastMediaPackage(mPrimaryMediaPackage);
                mRemovedMediaSourcePackage = null;
            }
        } else {
            // Shouldn't reach this unless there is some other error in CarService
            Log.e(TAG, "Error trying to save last media source, prefs uninitialized");
        }
        notifyListeners();
    }

    private void notifyListeners() {
        int i = mMediaSourceListeners.beginBroadcast();
        while (i-- > 0) {
            try {
                ICarMediaSourceListener callback = mMediaSourceListeners.getBroadcastItem(i);
                callback.onMediaSourceChanged(mPrimaryMediaPackage);
            } catch (RemoteException e) {
                Log.e(TAG, "calling onMediaSourceChanged failed " + e);
            }
        }
        mMediaSourceListeners.finishBroadcast();
    }

    private MediaController.Callback mMediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            savePlaybackState(state);
            // Try to start playback if the new state allows the play action
            maybeRestartPlayback(state);
        }
    };

    /**
     * Finds the currently playing media source, then updates the active source if different
     */
    private synchronized void updatePrimaryMediaSourceWithCurrentlyPlaying(
            List<MediaController> controllers) {
        for (MediaController controller : controllers) {
            if (controller.getPlaybackState() != null
                    && controller.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) {
                if (mPrimaryMediaPackage == null || !mPrimaryMediaPackage.equals(
                        controller.getPackageName())) {
                    setPrimaryMediaSource(controller.getPackageName());
                }
                return;
            }
        }
    }

    private boolean isMediaService(String packageName) {
        return getBrowseServiceClassName(packageName) != null;
    }

    private String getBrowseServiceClassName(@NonNull String packageName) {
        PackageManager packageManager = mContext.getPackageManager();
        Intent mediaIntent = new Intent();
        mediaIntent.setPackage(packageName);
        mediaIntent.setAction(MediaBrowserService.SERVICE_INTERFACE);

        List<ResolveInfo> mediaServices = packageManager.queryIntentServices(mediaIntent,
                PackageManager.GET_RESOLVED_FILTER);

        if (mediaServices == null || mediaServices.isEmpty()) {
            return null;
        }
        return mediaServices.get(0).serviceInfo.name;
    }

    private void saveLastMediaPackage(@NonNull String packageName) {
        String serialized = mSharedPrefs.getString(SOURCE_KEY, null);
        if (serialized == null) {
            mSharedPrefs.edit().putString(SOURCE_KEY, packageName).apply();
        } else {
            Deque<String> packageNames = getPackageNameList(serialized);
            packageNames.remove(packageName);
            packageNames.addFirst(packageName);
            mSharedPrefs.edit().putString(SOURCE_KEY, serializePackageNameList(packageNames))
                    .apply();
        }
    }

    private String getLastMediaPackage() {
        String serialized = mSharedPrefs.getString(SOURCE_KEY, null);
        if (!TextUtils.isEmpty(serialized)) {
            for (String packageName : getPackageNameList(serialized)) {
                if (isMediaService(packageName)) {
                    return packageName;
                }
            }
        }

        String defaultSourcePackage = mContext.getString(R.string.default_media_application);
        if (isMediaService(defaultSourcePackage)) {
            return defaultSourcePackage;
        }
        return null;
    }

    private String serializePackageNameList(Deque<String> packageNames) {
        return packageNames.stream().collect(Collectors.joining(PACKAGE_NAME_SEPARATOR));
    }

    private Deque<String> getPackageNameList(String serialized) {
        String[] packageNames = serialized.split(PACKAGE_NAME_SEPARATOR);
        return new ArrayDeque(Arrays.asList(packageNames));
    }

    private void savePlaybackState(PlaybackState playbackState) {
        int state = playbackState != null ? playbackState.getState() : PlaybackState.STATE_NONE;
        if (state == PlaybackState.STATE_PLAYING) {
            // No longer need to request play if audio was resumed already via some other means,
            // e.g. Assistant starts playback, user uses hardware button, etc.
            mStartPlayback = false;
        }
        if (mSharedPrefs != null) {
            mSharedPrefs.edit().putInt(PLAYBACK_STATE_KEY, state).apply();
        }
    }

    private void maybeRestartPlayback(PlaybackState state) {
        if (mStartPlayback && state != null
                && (state.getActions() & PlaybackState.ACTION_PLAY) != 0) {
            play();
            mStartPlayback = false;
        }
    }

    /**
     * Updates active media controller from the list that has the same package name as the primary
     * media package. Clears callback and resets media controller to null if not found.
     */
    private void updateActiveMediaController(List<MediaController> mediaControllers) {
        if (mPrimaryMediaPackage == null) {
            return;
        }
        if (mActiveUserMediaController != null) {
            mActiveUserMediaController.unregisterCallback(mMediaControllerCallback);
            mActiveUserMediaController = null;
        }
        for (MediaController controller : mediaControllers) {
            if (mPrimaryMediaPackage.equals(controller.getPackageName())) {
                mActiveUserMediaController = controller;
                // Specify Handler to receive callbacks on, to avoid defaulting to the calling
                // thread; this method can be called from the MediaSessionManager callback.
                // Using the version of this method without passing a handler causes a
                // RuntimeException for failing to create a Handler.
                PlaybackState state = mActiveUserMediaController.getPlaybackState();
                savePlaybackState(state);
                mActiveUserMediaController.registerCallback(mMediaControllerCallback, mHandler);
                maybeRestartPlayback(state);
                return;
            }
        }
    }
}