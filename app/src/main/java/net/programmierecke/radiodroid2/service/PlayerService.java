package net.programmierecke.radiodroid2.service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.RemoteException;
import android.support.v4.media.MediaMetadataCompat;

import androidx.annotation.NonNull;
import androidx.core.app.JobIntentService;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.squareup.picasso.NetworkPolicy;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

import net.programmierecke.radiodroid2.ActivityMain;
import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.FavouriteManager;
import net.programmierecke.radiodroid2.HistoryManager;
import net.programmierecke.radiodroid2.IPlayerService;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.history.TrackHistoryEntry;
import net.programmierecke.radiodroid2.history.TrackHistoryRepository;
import net.programmierecke.radiodroid2.players.PlayState;
import net.programmierecke.radiodroid2.playlist.PlaylistParser;
import net.programmierecke.radiodroid2.players.selector.PlayerType;
import net.programmierecke.radiodroid2.station.DataRadioStation;
import net.programmierecke.radiodroid2.station.live.ShoutcastInfo;
import net.programmierecke.radiodroid2.station.live.StreamLiveInfo;
import net.programmierecke.radiodroid2.players.RadioPlayer;
import net.programmierecke.radiodroid2.recording.RecordingsManager;
import net.programmierecke.radiodroid2.recording.RunningRecordingInfo;
import net.programmierecke.radiodroid2.ui.EqualizerActivity;

import static android.content.Intent.ACTION_MEDIA_BUTTON;

public class PlayerService extends JobIntentService implements RadioPlayer.PlayerListener {

    // #region debug-point B:debug-logger
    private static void dbg(String hypothesisId, String location, String msg, java.util.Map<String, Object> data) {
        new Thread(() -> {
            try {
                URL url = new URL("http://127.0.0.1:7777/event");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(500);
                conn.setReadTimeout(500);
                String json = "{\"sessionId\":\"volume-pop\",\"runId\":\"pre\",\"hypothesisId\":\"" + hypothesisId + "\",\"location\":\"" + location + "\",\"msg\":\"[DEBUG] " + msg.replace("\"", "'") + "\",\"data\":" + (data != null ? new org.json.JSONObject(data).toString() : "{}") + ",\"ts\":" + System.currentTimeMillis() + "}";
                conn.getOutputStream().write(json.getBytes("UTF-8"));
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }
    // #endregion

    protected static final int NOTIFY_ID = 1;
    private static final String NOTIFICATION_CHANNEL_ID = "default";

    public static final String METERED_CONNECTION_WARNING_KEY = "warn_no_wifi";

    public static final String PLAYER_SERVICE_CONNECTION_TYPE_CHANGED = "net.programmierecke.radiodroid2.connection_type_changed";
    public static final String PLAYER_SERVICE_CONNECTION_TYPE_EXTRA = "connection_type";

    public static final String PLAYER_SERVICE_NO_NOTIFICATION_EXTRA = "no_notification";

    public static final String PLAYER_SERVICE_TIMER_UPDATE = "net.programmierecke.radiodroid2.timerupdate";
    public static final String PLAYER_SERVICE_TIMER_FINISHED = "net.programmierecke.radiodroid2.timerfinished";
    public static final String PLAYER_SERVICE_META_UPDATE = "net.programmierecke.radiodroid2.metaupdate";

    public static final String PLAYER_SERVICE_STATE_CHANGE = "net.programmierecke.radiodroid2.statechange";
    public static final String PLAYER_SERVICE_STATE_EXTRA_KEY = "state";

    public static final String PLAYER_SERVICE_BOUND = "net.programmierecke.radiodroid2.playerservicebound";

    private final String TAG = "PLAY";

    private final String ACTION_PAUSE = "pause";
    private final String ACTION_RESUME = "resume";
    private final String ACTION_SKIP_TO_NEXT = "next";
    private final String ACTION_SKIP_TO_PREVIOUS = "previous";
    private final String ACTION_STOP = "stop";

    private static final float FULL_VOLUME = 100f;
    private static final float DUCK_VOLUME = 40f;

    // 音量曲线补偿：监听系统音量变化，动态调整播放器最大增益
    // 低系统音量（<35%）→ maxGain = 0.5 ~ 1.0（降低 1 倍）
    // 中系统音量（35%-65%）→ maxGain = 1.0（不变）
    // 高系统音量（65%-100%）→ maxGain = 1.0 ~ 2.0（提升 1 倍）
    private BroadcastReceiver volumeChangeReceiver;

    private SharedPreferences sharedPref;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener;

    private TrackHistoryRepository trackHistoryRepository;

    private Context itsContext;
    private Handler handler;

    private DataRadioStation currentStation;

    // 闹钟播放时的音量渐增参数（直接控制系统音量）
    private boolean currentIsAlarm = false;
    private boolean alarmFadeApplied = false;
    private int alarmStartVolume = 0;
    private int alarmTargetVolume = 50;
    private int alarmFadeDurationMs = 30000;

    // 闹钟播放时保存/恢复系统音量
    private int savedSystemVolume = -1;
    private boolean alarmVolumeOverride = false;
    /**
     * 闹钟音量会话代次。每次 start/stop 递增。
     * 渐增 Runnable 捕获创建时的 session，执行时若 session 已过期则丢弃，
     * 避免 binder 线程 stop 与主线程 fade 竞态导致音量被再次抬高。
     */
    private volatile int alarmVolumeSession = 0;
    // 系统音量渐增用的定时任务
    private final List<Runnable> alarmFadeTasks = new java.util.ArrayList<>();

    private BitmapDrawable radioIcon;

    private RadioPlayer radioPlayer;

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private MediaSessionCompat mediaSession;
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private BecomingNoisyReceiver becomingNoisyReceiver = new BecomingNoisyReceiver();
    private AudioDeviceMonitor audioDeviceMonitor;
    private ConnectivityChecker connectivityChecker = new ConnectivityChecker();

    private android.media.audiofx.Equalizer serviceEqualizer;
    private android.media.audiofx.BassBoost serviceBassBoost;
    private boolean eqActivityOpen = false;

    private final BroadcastReceiver eqActivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (EqualizerActivity.ACTION_EQ_ACTIVITY_OPENED.equals(action)) {
                eqActivityOpen = true;
                releaseServiceEqualizer();
            } else if (EqualizerActivity.ACTION_EQ_ACTIVITY_CLOSED.equals(action)) {
                eqActivityOpen = false;
                if (radioPlayer != null) {
                    int sessionId = radioPlayer.getAudioSessionId();
                    if (sessionId != 0) {
                        applyEqualizerSettings(sessionId);
                    }
                }
            }
        }
    };

    private PauseReason pauseReason = PauseReason.NONE;

    private int lastErrorFromPlayer = -1;

    private ToneGenerator toneGenerator;
    private Runnable toneGeneratorStopRunnable;

    private CountDownTimer timer;
    private long seconds = 0;

    private StreamLiveInfo liveInfo = new StreamLiveInfo(null);
    private ShoutcastInfo streamInfo;

    private boolean isHls = false;

    private long lastPlayStartTime = 0;
    private long totalPlayTimeAccumulatedMillis = 0;
    private long currentPlayingSessionStart = 0;
    private boolean playStateIsPlaying = false;
    /**
     * 本次播放会话是否已完成“静音→均衡器附着→音量渐入”初始化。
     * ExoPlayer 对同一会话可能连续通知多次 Playing（STATE_READY 回调与
     * setPlayWhenReady 后的手动回调共两条路径）。用它避免重复初始化导致在
     * 播放过程中释放+重建 AudioEffect，触发 DSP 效果链重配置而产生爆音。
     */
    private boolean eqAndFadeInitialized = false;

    private boolean notificationIsActive = false;

    final int pendingIntentFlag =  Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;

    void sendBroadCast(String action) {
        Intent local = new Intent();
        local.setAction(action);
        LocalBroadcastManager.getInstance(itsContext).sendBroadcast(local);
    }

    private final IPlayerService.Stub itsBinder = new IPlayerService.Stub() {
        // This method exist because we need to set information about current radio station
        // and then use it in playerFragment when MPD player is working.
        public void SetStation(DataRadioStation station) {
            PlayerService.this.setStation(station);
        }

        public void SetAlarmFade(int startVolume, int targetVolume, int durationMs) throws RemoteException {
            PlayerService.this.setAlarmFade(startVolume, targetVolume, durationMs);
        }

        public void SkipToNext() throws RemoteException {
            PlayerService.this.next();
        }

        public void SkipToPrevious() throws RemoteException {
            PlayerService.this.previous();
        }

        public void Play(boolean isAlarm) throws RemoteException {
            PlayerService.this.playCurrentStation(isAlarm);
        }

        public void Pause(PauseReason pauseReason) throws RemoteException {
            PlayerService.this.pause(pauseReason);
        }

        public void Resume() throws RemoteException {
            PlayerService.this.resume();
        }

        public void Stop() throws RemoteException {
            PlayerService.this.stop();
        }

        @Override
        public void addTimer(int secondsAdd) throws RemoteException {
            PlayerService.this.addTimer(secondsAdd);
        }

        @Override
        public void clearTimer() throws RemoteException {
            PlayerService.this.clearTimer();
        }

        @Override
        public long getTimerSeconds() throws RemoteException {
            return PlayerService.this.getTimerSeconds();
        }

        @Override
        public String getCurrentStationID() throws RemoteException {
            return currentStation != null ? currentStation.StationUuid : null;
        }

        @Override
        public DataRadioStation getCurrentStation() throws RemoteException {
            return currentStation;
        }

        @Override
        public StreamLiveInfo getMetadataLive() throws RemoteException {
            return PlayerService.this.liveInfo;
        }

        @Override
        public ShoutcastInfo getShoutcastInfo() throws RemoteException {
            return streamInfo;
        }

        @Override
        public MediaSessionCompat.Token getMediaSessionToken() throws RemoteException {
            return PlayerService.this.mediaSession.getSessionToken();
        }

        @Override
        public boolean getIsHls() throws RemoteException {
            return isHls;
        }

        @Override
        public boolean isPlaying() throws RemoteException {
            return radioPlayer.isPlaying();
        }

        @Override
        public PlayState getPlayerState() throws RemoteException {
            return radioPlayer.getPlayState();
        }

        @Override
        public void startRecording() throws RemoteException {
            if (radioPlayer != null) {
                RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
                RecordingsManager recordingsManager = radioDroidApp.getRecordingsManager();

                recordingsManager.record(PlayerService.this, radioPlayer);

                sendBroadCast(PLAYER_SERVICE_META_UPDATE);
            }
        }

        @Override
        public void stopRecording() throws RemoteException {
            if (radioPlayer != null) {
                RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
                RecordingsManager recordingsManager = radioDroidApp.getRecordingsManager();

                recordingsManager.stopRecording(radioPlayer);

                sendBroadCast(PLAYER_SERVICE_META_UPDATE);
            }
        }

        @Override
        public boolean isRecording() throws RemoteException {
            return radioPlayer != null && radioPlayer.isRecording();
        }

        @Override
        public String getCurrentRecordFileName() throws RemoteException {
            if (radioPlayer != null) {
                RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
                RecordingsManager recordingsManager = radioDroidApp.getRecordingsManager();

                RunningRecordingInfo info = recordingsManager.getRecordingInfo(radioPlayer);
                if (info != null) {
                    return info.getFileName();
                }
            }
            return null;
        }

        @Override
        public long getTransferredBytes() throws RemoteException {
            if (radioPlayer != null) {
                return radioPlayer.getCurrentPlaybackTransferredBytes();
            }
            return 0;
        }

        @Override
        public long getBufferedSeconds() throws RemoteException {
            if (radioPlayer != null) {
                return radioPlayer.getBufferedSeconds();
            }
            return 0;
        }

        @Override
        public long getLastPlayStartTime() throws RemoteException {
            return lastPlayStartTime;
        }

        @Override
        public PauseReason getPauseReason() throws RemoteException {
            return PlayerService.this.pauseReason;
        }

        @Override
        public void enableMPD(String hostname, int port) throws RemoteException {
            if (radioPlayer != null) {
                // radioPlayer.enableMPDPlayer(hostname, port);
            }
        }

        @Override
        public void disableMPD() throws RemoteException {
            if (radioPlayer != null) {
                // radioPlayer.disableMPDPlayer();
            }
        }

        @Override
        public boolean isNotificationActive() throws RemoteException {
            return PlayerService.this.notificationIsActive;
        }

        @Override
        public int getAudioSessionId() throws RemoteException {
            if (radioPlayer != null) {
                return radioPlayer.getAudioSessionId();
            }
            return 0;
        }

        @Override
        public long getTotalPlayTime() throws RemoteException {
            long total = totalPlayTimeAccumulatedMillis;
            if (playStateIsPlaying && currentPlayingSessionStart > 0) {
                total += System.currentTimeMillis() - currentPlayingSessionStart;
            }
            return total / 1000;
        }
    };

    private MediaSessionCompat.Callback mediaSessionCallback = null;

    private AudioManager.OnAudioFocusChangeListener afChangeListener =
            new AudioManager.OnAudioFocusChangeListener() {
                public void onAudioFocusChange(int focusChange) {
                    // #region debug-point B:focus-change
                    dbg("B", "PlayerService:416", "onAudioFocusChange", java.util.Map.of("focusChange", focusChange, "isLocal", radioPlayer != null && radioPlayer.isLocal(), "isAlarm", currentIsAlarm, "alarmVolumeOverride", alarmVolumeOverride));
                    // #endregion
                    if (radioPlayer == null || !radioPlayer.isLocal()) {
                        return;
                    }

                    // 闹钟播放期间：保持满增益，不 duck、不因短暂失焦而静音。
                    // 系统媒体音量由 startSystemVolumeFade 线性控制，应用层音量必须始终为 FULL。
                    if (currentIsAlarm || alarmVolumeOverride) {
                        switch (focusChange) {
                            case AudioManager.AUDIOFOCUS_GAIN:
                                if (pauseReason == PauseReason.FOCUS_LOSS_TRANSIENT) {
                                    enableMediaSession();
                                    resume();
                                }
                                radioPlayer.setVolume(FULL_VOLUME);
                                break;
                            case AudioManager.AUDIOFOCUS_LOSS:
                                // 永久失焦仍暂停（例如用户切到其他强占媒体的应用）
                                if (radioPlayer.isPlaying()) {
                                    pause(PauseReason.FOCUS_LOSS);
                                }
                                break;
                            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                                // 闹钟不 duck、不暂停短暂失焦，避免“无声播放”
                                radioPlayer.setVolume(FULL_VOLUME);
                                break;
                        }
                        return;
                    }

                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_GAIN:

                            if (pauseReason == PauseReason.FOCUS_LOSS_TRANSIENT) {
                                enableMediaSession();
                                resume();
                            }

                            // 仅在播放已真正开始时做 DUCK→FULL 恢复渐入。
                            // 播放刚启动时（还在缓冲/初始化）请求焦点获得的 GAIN 回调
                            // 若立即把音量拉到 DUCK_VOLUME，会与 applyEqualizerAndFadeIn
                            // 的“静音→渐入”竞争，造成起始瞬间音量突增。
                            if (playStateIsPlaying) {
                                // 渐变恢复到满音量，避免瞬间音量突增
                                fadeInVolume(radioPlayer, DUCK_VOLUME, FULL_VOLUME, 200);
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS:

                            if (radioPlayer.isPlaying()) {
                                pause(PauseReason.FOCUS_LOSS);
                            }

                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:

                            if (radioPlayer.isPlaying()) {
                                pause(PauseReason.FOCUS_LOSS_TRANSIENT);
                            }

                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            if (BuildConfig.DEBUG)

                            // 渐变降低到duck音量
                            fadeInVolume(radioPlayer, FULL_VOLUME, DUCK_VOLUME, 100);
                            break;
                    }
                }
            };

    private ConnectivityChecker.ConnectivityCallback connectivityCallback = new ConnectivityChecker.ConnectivityCallback() {
        @Override
        public void onConnectivityChanged(boolean connected, ConnectivityChecker.ConnectionType connectionType) {
            sendConnectionTypeChangedBroadcast(connectionType);
        }
    };

    private long getTimerSeconds() {
        return seconds;
    }

    private void clearTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;

            seconds = 0;

            sendBroadCast(PLAYER_SERVICE_TIMER_UPDATE);
        }
    }

    private void addTimer(int secondsAdd) {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }

        seconds += secondsAdd;

        timer = new CountDownTimer(seconds * 1000, 1000) {
            public void onTick(long millisUntilFinished) {
                seconds = millisUntilFinished / 1000;

                sendBroadCast(PLAYER_SERVICE_TIMER_UPDATE);
            }

            public void onFinish() {
                stop();
                timer = null;
                
                // 发送定时器完成广播
                sendBroadCast(PLAYER_SERVICE_TIMER_FINISHED);
            }
        }.start();
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return itsBinder;
    }

    private HeadsetConnectionReceiver dynamicHeadsetReceiver;

    @Override
    public void onCreate() {
        super.onCreate();

        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);

        handler = new Handler(getMainLooper());

        itsContext = this;
        timer = null;
        powerManager = (PowerManager) itsContext.getSystemService(Context.POWER_SERVICE);
        audioManager = (AudioManager) itsContext.getSystemService(Context.AUDIO_SERVICE);
        radioIcon = ((BitmapDrawable) ResourcesCompat.getDrawable(getResources(), R.drawable.ic_launcher, null));

        radioPlayer = new RadioPlayer(PlayerService.this);
        radioPlayer.setPlayerListener(this);
        radioPlayer.setVolumeMappingEnabled(sharedPref.getBoolean("enable_volume_mapping", true));

        preferenceChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                if ("enable_volume_mapping".equals(key)) {
                    boolean enabled = sharedPreferences.getBoolean(key, true);
                    if (radioPlayer != null) {
                        radioPlayer.setVolumeMappingEnabled(enabled);
                        updateVolumeGain();
                        radioPlayer.refreshVolume();
                    }
                }
            }
        };
        sharedPref.registerOnSharedPreferenceChangeListener(preferenceChangeListener);

        mediaSessionCallback = new MediaSessionCallback(this, itsBinder);

        mediaSession = new MediaSessionCompat(getBaseContext(), getBaseContext().getPackageName());
        mediaSession.setCallback(mediaSessionCallback);

        Intent startActivityIntent = new Intent(itsContext.getApplicationContext(), ActivityMain.class);
        mediaSession.setSessionActivity(PendingIntent.getActivity(itsContext.getApplicationContext(), 0, startActivityIntent, PendingIntent.FLAG_UPDATE_CURRENT | pendingIntentFlag));

        setMediaPlaybackState(PlaybackStateCompat.STATE_NONE);

        RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
        trackHistoryRepository = radioDroidApp.getTrackHistoryRepository();

        final IntentFilter eqActivityFilter = new IntentFilter();
        eqActivityFilter.addAction(EqualizerActivity.ACTION_EQ_ACTIVITY_OPENED);
        eqActivityFilter.addAction(EqualizerActivity.ACTION_EQ_ACTIVITY_CLOSED);
        registerReceiver(eqActivityReceiver, eqActivityFilter);

        audioDeviceMonitor = new AudioDeviceMonitor(this);
        audioDeviceMonitor.register();

        // 注册系统音量变化监听，动态调整播放器增益
        registerVolumeChangeReceiver();
        updateVolumeGain();

        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "RadioDroid2 Player", NotificationManager.IMPORTANCE_LOW);

            // Configure the notification channel.
            notificationChannel.setDescription("Channel description");
            notificationChannel.enableLights(false);
            notificationChannel.enableVibration(false);
            notificationManager.createNotificationChannel(notificationChannel);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户划掉最近任务时，确保闹钟系统音量恢复（onDestroy 可能不被调用）
        currentIsAlarm = false;
        alarmFadeApplied = false;
        stopAlarmVolumeOverride();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        // 服务销毁时务必先恢复系统音量，避免闹钟渐增后的 100% 残留
        currentIsAlarm = false;
        alarmFadeApplied = false;
        stopAlarmVolumeOverride();

        stop();

        mediaSession.release();

        radioPlayer.destroy();

        unregisterReceiver(eqActivityReceiver);
        unregisterVolumeChangeReceiver();
        if (audioDeviceMonitor != null) {
            audioDeviceMonitor.unregister();
            audioDeviceMonitor = null;
        }

        if (sharedPref != null && preferenceChangeListener != null) {
            sharedPref.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }

        releaseServiceEqualizer();

        // Clean up handler to prevent memory leaks（须在音量恢复之后）
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Service could be started by external forces, e.g. when we had the last media session
        // and user presses play/pause media button.
        PlayerServiceUtil.bindService(itsContext.getApplicationContext());

        if (currentStation == null) {
            RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
            HistoryManager historyManager = radioDroidApp.getHistoryManager();
            currentStation = historyManager.getFirst();
        }

        if (currentStation == null) {
            RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
            FavouriteManager favouriteManager = radioDroidApp.getFavouriteManager();
            currentStation = favouriteManager.getFirst();
        }

        boolean showNotification = true;

        if (intent != null) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case ACTION_SKIP_TO_PREVIOUS:
                        previous();
                        break;
                    case ACTION_SKIP_TO_NEXT:
                        next();
                        break;
                    case ACTION_STOP:
                        stop();
                        return START_NOT_STICKY;
                    case ACTION_PAUSE:
                        pause(PauseReason.USER);
                        break;
                    case ACTION_RESUME:
                        resume();
                        break;
                    case ACTION_MEDIA_BUTTON:
                        KeyEvent key = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                        if (key.getAction() == KeyEvent.ACTION_UP) {
                            int keycode = key.getKeyCode();
                            switch (keycode) {
                                case KeyEvent.KEYCODE_MEDIA_PLAY:
                                    resume();
                                    break;
                                case KeyEvent.KEYCODE_MEDIA_NEXT:
                                    next();
                                    break;
                                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                                    previous();
                                    break;
                            }
                        }
                        break;
                }
            }

            MediaButtonReceiver.handleIntent(mediaSession, intent);

            showNotification = !intent.getBooleanExtra(PLAYER_SERVICE_NO_NOTIFICATION_EXTRA, false);
        }

        // It is an error for service started via Context.startForegroundService not to create
        // a notification since Android O. It must call startForeground within 5 seconds of being started.
        // Thus if we can show a notification - we always show it.
        if (showNotification && !notificationIsActive) {
            if (currentStation == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // On Android O+ we MUST show notification if started via startForegroundService

                    NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                            "Temporary", NotificationManager.IMPORTANCE_DEFAULT);
                    ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
                    Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                            .setContentTitle("")
                            .setContentText("").build();
                    startForeground(NOTIFY_ID, notification);
                    stopForeground(true);
                } else {
                    stopSelf();
                    return START_NOT_STICKY;
                }
            } else {
                updateNotification(PlayState.Paused);
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    private void playWithoutWarnings(DataRadioStation station) {
        setStation(station);
        playCurrentStation(false);
    }

    public void setStation(DataRadioStation station) {
        this.currentStation = station;
    }

    private void setAlarmFade(int startVolume, int targetVolume, int durationMs) {
        this.alarmStartVolume = Math.max(0, Math.min(100, startVolume));
        this.alarmTargetVolume = Math.max(0, Math.min(100, targetVolume));
        this.alarmFadeDurationMs = Math.max(0, durationMs);
    }

    public void playCurrentStation(final boolean isAlarm) {
        if (currentStation == null) {
            return;
        }

        currentIsAlarm = isAlarm;
        alarmFadeApplied = false;

        if (Utils.shouldLoadIcons(itsContext))
            downloadRadioIcon();

        int result = acquireAudioFocus();
        // #region debug-point B:audio-focus-result
        int streamMaxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int streamCurVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        dbg("B", "PlayerService:705", "acquireAudioFocus result", java.util.Map.of("result", result, "granted", result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED, "streamMaxVol", streamMaxVol, "streamCurVol", streamCurVol, "streamRatio", (float)streamCurVol / streamMaxVol));
        // #endregion
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Start playback.
            enableMediaSession();

            liveInfo = new StreamLiveInfo(null);
            streamInfo = null;

            acquireWakeLockAndWifiLock();

            final DataRadioStation stationToPlay = currentStation;
            if (stationToPlay.StreamUrl != null && PlaylistParser.isPlaylistUrl(stationToPlay.StreamUrl)) {
                new PlaylistResolveTask(stationToPlay, isAlarm).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } else {
                radioPlayer.play(currentStation, isAlarm);
            }
        }
    }

    public void pause(PauseReason pauseReason) {
        if (BuildConfig.DEBUG) {
        }

        this.pauseReason = pauseReason;

        releaseWakeLockAndWifiLock();

        // Pausing due to focus loss means that we can gain it again
        // so we should keep the focus and the wait for callback.
        if (pauseReason != PauseReason.FOCUS_LOSS_TRANSIENT) {
            releaseAudioFocus();
        }

        // 用户手动暂停/永久暂停闹钟时：立即恢复闹钟响铃前的系统媒体音量
        // 短暂失焦（FOCUS_LOSS_TRANSIENT）保留渐增状态，以便焦点回来后继续
        if (pauseReason != PauseReason.FOCUS_LOSS_TRANSIENT) {
            currentIsAlarm = false;
            alarmFadeApplied = false;
            stopAlarmVolumeOverride();
        }

        radioPlayer.pause();
    }

    public void next() {
        if (currentStation == null) {
            return;
        }

        setMediaPlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT);
        DataRadioStation station = currentStation.queue.getNextById(currentStation.StationUuid);

        if (station != null) {
            playWithoutWarnings(station);
        }
    }

    public void previous() {
        if (currentStation == null) {
            return;
        }

        DataRadioStation station = currentStation.queue.getPreviousById(currentStation.StationUuid);
        if (station != null) {
            playWithoutWarnings(station);
        }
    }

    public void resume() {

        this.pauseReason = PauseReason.NONE;

        if (!radioPlayer.isPlaying()) {
            RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
            DataRadioStation station = currentStation;

            if (currentStation == null) {
                HistoryManager historyManager = radioDroidApp.getHistoryManager();
                station = historyManager.getFirst();
            }

            if (station != null) {
                playWithoutWarnings(station);
            }
        }
    }

    public void stop() {

        this.pauseReason = PauseReason.NONE;
        this.notificationIsActive = false;

        liveInfo = new StreamLiveInfo(null);
        streamInfo = null;

        // 先恢复系统音量，再停播放器，避免停止过程中仍有渐增任务把音量改回目标值
        currentIsAlarm = false;
        alarmFadeApplied = false;
        stopAlarmVolumeOverride();

        releaseAudioFocus();
        disableMediaSession();
        radioPlayer.stop();
        releaseWakeLockAndWifiLock();
        clearTimer();
        totalPlayTimeAccumulatedMillis = 0;
        currentPlayingSessionStart = 0;

        stopForeground(true);

        stopConnectionTypeListener();

        //sendBroadCast(PLAYER_SERVICE_STATE_CHANGE);
    }

    private class PlaylistResolveTask extends AsyncTask<Void, Void, String> {
        private final DataRadioStation station;
        private final boolean isAlarm;

        PlaylistResolveTask(DataRadioStation station, boolean isAlarm) {
            this.station = station;
            this.isAlarm = isAlarm;
        }

        @Override
        protected String doInBackground(Void... params) {
            RadioDroidApp app = (RadioDroidApp) getApplication();
            return PlaylistParser.resolvePlaylistUrl(app.getHttpClient(), station.StreamUrl);
        }

        @Override
        protected void onPostExecute(String resolvedUrl) {
            if (resolvedUrl != null) {
                station.playableUrl = resolvedUrl;
                radioPlayer.play(station, isAlarm);
            } else {
                Toast.makeText(itsContext, R.string.error_station_load, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setMediaPlaybackState(int state) {
        if (mediaSession == null) {
            return;
        }

        long actions = PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_STOP
                | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                | PlaybackStateCompat.ACTION_PLAY_PAUSE;

        if (state == PlaybackStateCompat.STATE_BUFFERING || state == PlaybackStateCompat.STATE_PLAYING) {
            actions |= PlaybackStateCompat.ACTION_PAUSE;
        } else {
            actions |= PlaybackStateCompat.ACTION_PLAY;
        }

        PlaybackStateCompat.Builder playbackStateBuilder = new PlaybackStateCompat.Builder();
        playbackStateBuilder.setActions(actions);

        if (state == PlaybackStateCompat.STATE_ERROR) {
            String error = "";

            try {
                error = itsContext.getResources().getString(lastErrorFromPlayer);
            } catch (Resources.NotFoundException ex) {
                Log.e(TAG, String.format("Unknown play error: %d", lastErrorFromPlayer), ex);
            }

            playbackStateBuilder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_ACTION_ABORTED, error);
        }

        playbackStateBuilder.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f);
        mediaSession.setPlaybackState(playbackStateBuilder.build());
    }

    private void enableMediaSession() {
        if (!mediaSession.isActive()) {

            IntentFilter becomingNoisyFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            registerReceiver(becomingNoisyReceiver, becomingNoisyFilter);

            mediaSession.setActive(true);

            setMediaPlaybackState(PlaybackStateCompat.STATE_NONE);
        }
    }

    private void disableMediaSession() {

        if (mediaSession.isActive()) {

            mediaSession.setActive(false);

            // try-catch 保护：若 registerReceiver 抛异常（如 SecurityException），
            // mediaSession 已激活但 receiver 未注册，此处反注册会抛 IllegalArgumentException
            try {
                unregisterReceiver(becomingNoisyReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "becomingNoisyReceiver not registered");
            }
        }
    }

    private int acquireAudioFocus() {
        int result;
        // #region debug-point B:audio-focus-request
        dbg("B", "PlayerService:905", "requestAudioFocus called", java.util.Map.of("isAlarm", currentIsAlarm));
        // #endregion
        // 闹钟与普通播放统一使用 USAGE_MEDIA + STREAM_MUSIC，
        // 使系统路由到扬声器/有线/蓝牙时都走媒体音量（与闹钟音量渐增一致）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFocusRequest.Builder builder = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(afChangeListener)
                    .setAcceptsDelayedFocusGain(false);
            // 闹钟不因 duck 而暂停，音量由系统媒体音量线性控制
            if (currentIsAlarm) {
                builder.setWillPauseWhenDucked(false);
            }
            audioFocusRequest = builder.build();
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(afChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.e(TAG, "acquiring audio focus failed! result=" + result + " isAlarm=" + currentIsAlarm);
            // 闹钟场景：即便焦点未授予也继续播放，避免“完全无声”
            // （部分 ROM 在锁屏/勿扰下可能拒绝 AUDIOFOCUS_GAIN）
            if (currentIsAlarm) {
                Log.w(TAG, "Alarm playback continues without exclusive audio focus");
                return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
            toastOnUi(R.string.error_grant_audiofocus);
        }

        return result;
    }

    private void releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        } else {
            audioManager.abandonAudioFocus(afChangeListener);
        }
    }

    void acquireWakeLockAndWifiLock() {

        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PlayerService:");
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        } else {
        }

        WifiManager wm = (WifiManager) itsContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            if (wifiLock == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PlayerService");
                } else {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "PlayerService");
                }
            }
            if (!wifiLock.isHeld()) {
                wifiLock.acquire();
            } else {
            }
        } else {
            Log.e(TAG, "could not acquire wifi lock, WifiManager does not exist!");
        }
    }

    private void releaseWakeLockAndWifiLock() {

        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to release wake lock: " + e.getMessage());
            }
            wakeLock = null;
        }

        if (wifiLock != null) {
            try {
                if (wifiLock.isHeld()) {
                    wifiLock.release();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to release wifi lock: " + e.getMessage());
            }
            wifiLock = null;
        }
    }

    private void sendMessage(String theTitle, String theMessage, String theTicker) {
        Intent notificationIntent = new Intent(itsContext, ActivityMain.class);
        notificationIntent.putExtra("stationid", currentStation.StationUuid);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Intent stopIntent = new Intent(itsContext, PlayerService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent pendingIntentStop = PendingIntent.getService(itsContext, 0, stopIntent, pendingIntentFlag);


        Intent nextIntent = new Intent(itsContext, PlayerService.class);
        nextIntent.setAction(ACTION_SKIP_TO_NEXT);
        PendingIntent pendingIntentNext = PendingIntent.getService(itsContext, 0, nextIntent, pendingIntentFlag);

        Intent previousIntent = new Intent(itsContext, PlayerService.class);
        previousIntent.setAction(ACTION_SKIP_TO_PREVIOUS);
        PendingIntent pendingIntentPrevious = PendingIntent.getService(itsContext, 0, previousIntent, pendingIntentFlag);

        PlayState currentPlayerState = radioPlayer.getPlayState();

        if (lastErrorFromPlayer != -1) {
            try {
                theMessage = itsContext.getResources().getString(lastErrorFromPlayer);
            } catch (Resources.NotFoundException ex) {
                Log.e(TAG, String.format("Unknown play error: %d", lastErrorFromPlayer), ex);
            }
        }

        PendingIntent contentIntent = PendingIntent.getActivity(itsContext, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | pendingIntentFlag);
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(itsContext, NOTIFICATION_CHANNEL_ID)
                .setContentIntent(contentIntent)
                .setContentTitle(theTitle)
                .setContentText(theMessage)
                .setWhen(System.currentTimeMillis())
                .setTicker(theTicker)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSmallIcon(R.drawable.ic_play_arrow_white_24dp)
                .setLargeIcon(radioIcon.getBitmap())
                .addAction(R.drawable.ic_stop_white_24dp, getString(R.string.action_stop), pendingIntentStop)
                .addAction(R.drawable.ic_skip_previous_24dp, getString(R.string.action_skip_to_previous), pendingIntentPrevious);

        if (currentPlayerState == PlayState.Playing || currentPlayerState == PlayState.PrePlaying) {
            Intent pauseIntent = new Intent(itsContext, PlayerService.class);
            pauseIntent.setAction(ACTION_PAUSE);
            PendingIntent pendingIntentPause = PendingIntent.getService(itsContext, 0, pauseIntent, pendingIntentFlag);

            notificationBuilder.addAction(R.drawable.ic_pause_white_24dp, getString(R.string.action_pause), pendingIntentPause);
            notificationBuilder.setUsesChronometer(true)
                    .setOngoing(true);
        } else if (currentPlayerState == PlayState.Paused || currentPlayerState == PlayState.Idle) {
            Intent resumeIntent = new Intent(itsContext, PlayerService.class);
            resumeIntent.setAction(ACTION_RESUME);
            PendingIntent pendingIntentResume = PendingIntent.getService(itsContext, 0, resumeIntent, pendingIntentFlag);

            notificationBuilder.addAction(R.drawable.ic_play_arrow_white_24dp, getString(R.string.action_resume), pendingIntentResume);
            notificationBuilder.setUsesChronometer(false)
                    .setDeleteIntent(pendingIntentStop)
                    .setOngoing(false);
        }

        notificationBuilder.addAction(R.drawable.ic_skip_next_24dp, getString(R.string.action_skip_to_next), pendingIntentNext)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(1, 2, 3 /* previous, play/pause, next */)
                        .setCancelButtonIntent(pendingIntentStop)
                        .setShowCancelButton(true));
        Notification notification = notificationBuilder.build();

        startForeground(NOTIFY_ID, notification);
        notificationIsActive = true;

        if (currentPlayerState == PlayState.Paused || currentPlayerState == PlayState.Idle) {
            stopForeground(false); // necessary to make notification dismissible
        }
    }

    private void toastOnUi(final int messageId) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(itsContext, itsContext.getResources().getString(messageId), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 闹钟开始时：保存系统媒体音量，设为起始音量，直接控制 STREAM_MUSIC 线性渐增。
     * startVolume/targetVolume 是系统媒体音量的百分比（0-100）。
     * 统一使用 STREAM_MUSIC，覆盖扬声器 / 有线耳机 / 蓝牙 A2DP 的媒体音量路由。
     */
    private void startAlarmVolumeOverride() {
        if (alarmVolumeOverride) return;
        if (audioManager == null) return;

        // 仅在首次进入闹钟音量覆盖时保存响铃前系统音量；中途重入不得覆盖
        if (savedSystemVolume < 0) {
            savedSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        }
        alarmVolumeOverride = true;
        final int session = ++alarmVolumeSession;

        // 禁用指数音量映射，使用线性映射，使系统音量百分比 = 实际输出百分比
        if (radioPlayer != null) {
            radioPlayer.setVolumeMappingEnabled(false);
            radioPlayer.setMaxGain(1.0f);
            // 应用层固定满增益，实际响度完全由系统媒体音量控制
            radioPlayer.setVolume(FULL_VOLUME);
        }

        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (maxVol <= 0) {
            Log.e(TAG, "startAlarmVolumeOverride: stream max volume is 0");
            return;
        }

        // 设系统媒体音量为起始音量（0% 对应 0，100% 对应 maxVol）
        int startVol = Math.round(alarmStartVolume / 100f * maxVol);
        startVol = Math.max(0, Math.min(maxVol, startVol));
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, startVol, 0);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to set system media volume for alarm start", e);
        }

        Log.i(TAG, "Alarm volume override start: start%=" + alarmStartVolume
                + " target%=" + alarmTargetVolume
                + " fadeMs=" + alarmFadeDurationMs
                + " startVol=" + startVol + "/" + maxVol
                + " savedVol=" + savedSystemVolume
                + " session=" + session);

        // 如果有渐增时长且目标 > 起始，启动系统媒体音量线性渐增
        if (alarmFadeDurationMs > 0 && alarmTargetVolume > alarmStartVolume) {
            int targetVol = Math.round(alarmTargetVolume / 100f * maxVol);
            targetVol = Math.max(0, Math.min(maxVol, targetVol));
            startSystemVolumeFade(startVol, targetVol, alarmFadeDurationMs, session);
        } else {
            // 无渐增：直接跳到目标系统音量
            int targetVol = Math.round(alarmTargetVolume / 100f * maxVol);
            targetVol = Math.max(0, Math.min(maxVol, targetVol));
            try {
                if (alarmVolumeSession == session && alarmVolumeOverride) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0);
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Failed to set system media volume for alarm target", e);
            }
        }
    }

    /**
     * 通过定时器逐步增加系统媒体音量（STREAM_MUSIC），实现闹钟音量线性渐增。
     * 该流同时作用于内置扬声器、有线耳机与蓝牙媒体（A2DP）输出。
     *
     * @param session 创建本轮渐增时的 alarmVolumeSession；停止后 session 失效，残留任务自动作废
     */
    private void startSystemVolumeFade(int fromVol, int toVol, int durationMs, final int session) {
        cancelAlarmFade();
        if (audioManager == null) return;
        if (toVol <= fromVol || durationMs <= 0) {
            try {
                if (alarmVolumeSession == session && alarmVolumeOverride) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                            Math.max(0, Math.min(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), toVol)), 0);
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Failed to set alarm target volume", e);
            }
            return;
        }

        final int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        // 步数与系统可分辨档位对齐，最多 60 步，保证线性且不过密
        final int steps = Math.max(1, Math.min(toVol - fromVol, 60));
        final long stepInterval = Math.max(1L, durationMs / steps);
        final float volumeStep = (float) (toVol - fromVol) / steps;

        for (int i = 1; i <= steps; i++) {
            final float vol = fromVol + volumeStep * i;
            final int stepIndex = i;
            Runnable step = () -> {
                // session 过期或已停止：丢弃，绝不可再改系统音量
                if (!alarmVolumeOverride || alarmVolumeSession != session || audioManager == null) return;
                int sysVol = Math.round(vol);
                sysVol = Math.max(0, Math.min(maxVol, sysVol));
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sysVol, 0);
                } catch (SecurityException e) {
                    Log.e(TAG, "Failed to set alarm fade volume step=" + stepIndex, e);
                }
                // 每一步都确保播放器增益仍为满，防止焦点/重缓冲把应用音量压低
                if (radioPlayer != null && alarmVolumeOverride && alarmVolumeSession == session) {
                    radioPlayer.setVolumeMappingEnabled(false);
                    radioPlayer.setMaxGain(1.0f);
                    radioPlayer.setVolume(FULL_VOLUME);
                }
            };
            alarmFadeTasks.add(step);
            handler.postDelayed(step, stepInterval * i);
        }
    }

    /**
     * 取消系统音量渐增定时器。
     */
    private void cancelAlarmFade() {
        if (handler != null) {
            for (Runnable task : alarmFadeTasks) {
                handler.removeCallbacks(task);
            }
        }
        alarmFadeTasks.clear();
    }

    /**
     * 闹钟结束/用户手动停止时：
     * 1) 取消所有系统音量渐增任务
     * 2) 把 STREAM_MUSIC 恢复到闹钟响铃前保存的档位（不能停留在目标 100%）
     * 3) 恢复普通播放的音量映射
     *
     * 幂等：可重复调用；即使 alarmVolumeOverride 标志异常，只要 savedSystemVolume 有效也会恢复。
     * 注意：必须同步执行（不可 post 到 handler），否则 onDestroy 的
     * handler.removeCallbacksAndMessages(null) 可能把恢复任务清掉，导致音量卡在 100%。
     */
    private void stopAlarmVolumeOverride() {
        final boolean wasOverride = alarmVolumeOverride;
        final int volumeToRestore = savedSystemVolume;

        // 递增 session：所有在途渐增 Runnable 立即失效，即使已通过 alarmVolumeOverride 检查也不会写音量
        alarmVolumeSession++;
        alarmVolumeOverride = false;
        currentIsAlarm = false;
        alarmFadeApplied = false;
        cancelAlarmFade();

        // 恢复音量映射开关
        boolean mappingEnabled = sharedPref != null && sharedPref.getBoolean("enable_volume_mapping", true);
        if (radioPlayer != null) {
            radioPlayer.setVolumeMappingEnabled(mappingEnabled);
        }

        // 恢复闹钟响铃前的系统媒体音量（0 也是合法档位，必须用 >= 0 判断）
        // 场景：起始 0% → 目标 100%，用户停止后绝不能保持 100%，必须回到响铃前档位
        if (volumeToRestore >= 0 && audioManager != null) {
            try {
                int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int restoreVol = Math.max(0, Math.min(maxVol, volumeToRestore));
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVol, 0);
                Log.i(TAG, "Alarm stopped: restored system media volume to " + restoreVol
                        + "/" + maxVol + " (saved=" + volumeToRestore
                        + ", wasOverride=" + wasOverride
                        + ", session=" + alarmVolumeSession + ")");
            } catch (SecurityException e) {
                Log.e(TAG, "Failed to restore system media volume after alarm", e);
            }
            savedSystemVolume = -1;
        } else if (wasOverride) {
            Log.w(TAG, "Alarm stopped but no saved system volume to restore");
        }

        // 重新计算 maxGain（恢复正常的音量映射补偿）
        updateVolumeGain();
        if (radioPlayer != null) {
            radioPlayer.refreshVolume();
        }
    }

    /**
     * 应用均衡器设置并渐入音量。
     * <p>
     * 注意：必须先静音再应用均衡器，避免在 Android 5.1 等低版本上
     * Equalizer 效果附着到音频会话时产生爆音（音频 DSP 管线重配置瞬间的瞬态噪声）。
     *
     * @param useAlarmFade 是否使用闹钟专属音量渐增参数
     */
    private void applyEqualizerAndFadeIn(int audioSessionId, boolean useAlarmFade) {
        // 幂等保护：ExoPlayer 对同一播放会话可能发出多次 Playing 通知
        // （STATE_READY 在 buffering→ready 切换、seek 等场景下可能重复触发）。
        // 若每次都重新执行"静音→释放/重建均衡器→渐入"，会在播放过程中反复
        // 触发 AudioFlinger 效果链重配置，低版本 Android 上会产生爆音，
        // 且音量会从渐入中途被重置回 0，表现为"开始播放不到一秒突然很大声"。
        // 对闹钟模式同样生效：第一次已完成 startAlarmVolumeOverride + 满音量设置，
        // 重复执行只会释放并重建均衡器产生爆音。
        if (eqAndFadeInitialized) {
            // #region debug-point B:apply-equalizer
            dbg("B", "PlayerService:applyEqualizerAndFadeIn", "skip duplicate Playing notification", java.util.Map.of("audioSessionId", audioSessionId, "useAlarmFade", useAlarmFade));
            // #endregion
            return;
        }
        eqAndFadeInitialized = true;

        // #region debug-point B:apply-equalizer
        int streamMaxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int streamCurVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        dbg("B", "PlayerService:1083", "applyEqualizerAndFadeIn called", java.util.Map.of("audioSessionId", audioSessionId, "maxGain", radioPlayer != null ? radioPlayer.getMaxGain() : -1, "streamCurVol", streamCurVol, "streamMaxVol", streamMaxVol, "useAlarmFade", useAlarmFade, "alarmFadeApplied", alarmFadeApplied));
        // #endregion

        // 先静音，避免后续均衡器附着时产生爆音
        if (radioPlayer != null) {
            radioPlayer.setVolume(0f);
        }

        applyEqualizerSettings(audioSessionId);

        // 闹钟渐增已启动后，后续 Buffering→Playing 只重新确保应用层满增益，
        // 不重启系统音量渐增（避免音量被重置回起点）。
        if (useAlarmFade && alarmFadeApplied) {
            if (radioPlayer != null) {
                radioPlayer.setVolumeMappingEnabled(false);
                radioPlayer.setMaxGain(1.0f);
                radioPlayer.setVolume(FULL_VOLUME);
            }
            return;
        }

        cancelPendingFadeIn();
        final RadioPlayer player = radioPlayer;
        if (player != null) {
            if (useAlarmFade) {
                alarmFadeApplied = true;
                // 闹钟播放：系统媒体音量线性渐增 + 应用层固定满增益
                startAlarmVolumeOverride();
                player.setVolumeMappingEnabled(false);
                player.setMaxGain(1.0f);
                player.setVolume(FULL_VOLUME);
            } else {
                Runnable fadeInTask = () -> fadeInVolume(player, 0f, FULL_VOLUME, 300);
                pendingFadeInTasks.add(fadeInTask);
                handler.postDelayed(fadeInTask, 50);
            }
        }
    }

    private void applyEqualizerSettings(int audioSessionId) {
        releaseServiceEqualizer();

        if (eqActivityOpen) return;

        SharedPreferences eqPrefs = PreferenceManager.getDefaultSharedPreferences(itsContext);

        String stationUuid = currentStation != null ? currentStation.StationUuid : null;
        boolean hasStationEq = stationUuid != null && EqualizerActivity.hasStationEqualizer(itsContext, stationUuid);

        String prefEnabled, prefPreset, prefBandLevels, prefBassBoostEnabled, prefBassBoostStrength;
        if (hasStationEq) {
            prefEnabled = EqualizerActivity.getStationEqEnabledKey(stationUuid);
            prefPreset = EqualizerActivity.getStationEqPresetKey(stationUuid);
            prefBandLevels = EqualizerActivity.getStationBandLevelsKey(stationUuid);
            prefBassBoostEnabled = EqualizerActivity.getStationBassBoostEnabledKey(stationUuid);
            prefBassBoostStrength = EqualizerActivity.getStationBassBoostStrengthKey(stationUuid);
        } else {
            prefEnabled = "equalizer_enabled";
            prefPreset = "equalizer_preset";
            prefBandLevels = "equalizer_band_levels";
            prefBassBoostEnabled = "bass_boost_enabled";
            prefBassBoostStrength = "bass_boost_strength";
        }

        boolean eqEnabled = eqPrefs.getBoolean(prefEnabled, false);
        if (!eqEnabled || audioSessionId == 0) return;

        try {
            serviceEqualizer = new android.media.audiofx.Equalizer(0, audioSessionId);

            int savedPreset = eqPrefs.getInt(prefPreset, -1);
            short numPresets = serviceEqualizer.getNumberOfPresets();
            short numBands = serviceEqualizer.getNumberOfBands();

            if (savedPreset == -2) {
                short[] voiceLevels = {-600, -200, 500, 700, 200};
                for (short i = 0; i < numBands; i++) {
                    try {
                        int centerFreq = serviceEqualizer.getCenterFreq(i);
                        short level;
                        if (i < voiceLevels.length) {
                            level = voiceLevels[i];
                        } else if (centerFreq < 200000) {
                            level = -600;
                        } else if (centerFreq < 500000) {
                            level = -200;
                        } else if (centerFreq < 2000000) {
                            level = 500;
                        } else if (centerFreq < 5000000) {
                            level = 700;
                        } else {
                            level = 200;
                        }
                        serviceEqualizer.setBandLevel(i, level);
                    } catch (Exception ignored) {
                    }
                }
            } else if (savedPreset == -3) {
                short[] musicLevels = {500, 200, 0, 350, 500};
                for (short i = 0; i < numBands; i++) {
                    try {
                        int centerFreq = serviceEqualizer.getCenterFreq(i);
                        short level;
                        if (i < musicLevels.length) {
                            level = musicLevels[i];
                        } else if (centerFreq < 200000) {
                            level = 500;
                        } else if (centerFreq < 500000) {
                            level = 200;
                        } else if (centerFreq < 2000000) {
                            level = 0;
                        } else if (centerFreq < 5000000) {
                            level = 350;
                        } else {
                            level = 500;
                        }
                        serviceEqualizer.setBandLevel(i, level);
                    } catch (Exception ignored) {
                    }
                }
            } else if (savedPreset >= 0 && savedPreset < numPresets) {
                try {
                    serviceEqualizer.usePreset((short) savedPreset);
                } catch (Exception ignored) {
                }
            } else {
                String levelsStr = eqPrefs.getString(prefBandLevels, null);
                if (levelsStr != null) {
                    String[] parts = levelsStr.split(",");
                    for (short i = 0; i < numBands && i < parts.length; i++) {
                        try {
                            short level = Short.parseShort(parts[i]);
                            serviceEqualizer.setBandLevel(i, level);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            serviceEqualizer.setEnabled(true);

            boolean bassBoostEnabled = eqPrefs.getBoolean(prefBassBoostEnabled, false);
            if (bassBoostEnabled) {
                try {
                    serviceBassBoost = new android.media.audiofx.BassBoost(0, audioSessionId);
                    short strength = (short) eqPrefs.getInt(prefBassBoostStrength, 0);
                    if (strength > 0) {
                        serviceBassBoost.setStrength(strength);
                    }
                    serviceBassBoost.setEnabled(true);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    // 取消上一次渐入的所有待执行任务，避免切换电台时旧回调乱入造成爆音
    private final List<Runnable> pendingFadeInTasks = new java.util.ArrayList<>();

    /**
     * 音量渐变，避免音量突变产生爆音。
     * 每次调用会先取消上一次未完成的渐入任务。
     */
    private void fadeInVolume(final RadioPlayer player, final float fromVolume, final float toVolume, final int durationMs) {
        cancelPendingFadeIn();

        final int steps = 15;
        final long stepInterval = durationMs / steps;
        final float volumeStep = (toVolume - fromVolume) / steps;

        // #region debug-point B:fadein-start
        dbg("B", "PlayerService:1220", "fadeInVolume START", java.util.Map.of("from", fromVolume, "to", toVolume, "stepInterval", stepInterval));
        // #endregion
        player.setVolume(fromVolume);

        for (int i = 1; i <= steps; i++) {
            final float volume = fromVolume + volumeStep * i;
            final int stepIndex = i;
            Runnable task = () -> {
                // #region debug-point B:fadein-step
                dbg("B", "PlayerService:1228", "fadeInVolume step", java.util.Map.of("step", stepIndex, "volume", volume, "totalSteps", steps));
                // #endregion
                player.setVolume(volume);
            };
            pendingFadeInTasks.add(task);
            handler.postDelayed(task, stepInterval * i);
        }
    }

    private void cancelPendingFadeIn() {
        for (Runnable task : pendingFadeInTasks) {
            handler.removeCallbacks(task);
        }
        pendingFadeInTasks.clear();
    }

    private void releaseServiceEqualizer() {
        if (serviceEqualizer != null) {
            try {
                serviceEqualizer.release();
            } catch (Exception ignored) {
            }
            serviceEqualizer = null;
        }
        if (serviceBassBoost != null) {
            try {
                serviceBassBoost.release();
            } catch (Exception ignored) {
            }
            serviceBassBoost = null;
        }
    }

    private void updateNotification() {
        updateNotification(radioPlayer.getPlayState());
    }

    private void updateNotification(PlayState playState) {
        switch (playState) {
            case Idle:
                NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
                notificationManager.cancel(NOTIFY_ID);

                setMediaPlaybackState(PlaybackStateCompat.STATE_NONE);
                break;
            case PrePlaying:
                sendMessage(currentStation.Name, itsContext.getResources().getString(R.string.notify_pre_play), itsContext.getResources().getString(R.string.notify_pre_play));

                setMediaPlaybackState(PlaybackStateCompat.STATE_BUFFERING);
                break;
            case Playing:
                final String title = liveInfo.getTitle();
                if (!TextUtils.isEmpty(title)) {
                    sendMessage(currentStation.Name, title, title);
                } else {
                    sendMessage(currentStation.Name, itsContext.getResources().getString(R.string.notify_play), currentStation.Name);
                }

                if (mediaSession != null) {
                    final MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder();
                    builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1);
                    builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentStation.Name);
                    builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, liveInfo.getArtist());
                    builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, liveInfo.getTrack());
                    builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentStation.Name);
                    if (liveInfo.hasArtistAndTrack()) {
                        builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, liveInfo.getArtist());
                        builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, liveInfo.getTrack());
                    } else {
                        builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, liveInfo.getTitle());
                        builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentStation.Name); // needed for android-media-controller to show an icon
                    }
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, radioIcon.getBitmap());
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, radioIcon.getBitmap());
                    mediaSession.setMetadata(builder.build());
                }

                setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);

                break;
            case Paused:
                sendMessage(currentStation.Name, itsContext.getResources().getString(R.string.notify_paused), currentStation.Name);

                if (lastErrorFromPlayer != -1) {
                    setMediaPlaybackState(PlaybackStateCompat.STATE_ERROR);
                } else {
                    setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED);
                }

                break;
        }
    }

    private void downloadRadioIcon() {
        final float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 70, getResources().getDisplayMetrics());

        if (!currentStation.hasIcon() && TextUtils.isEmpty(currentStation.HomePageUrl)) {
            radioIcon = (BitmapDrawable) ResourcesCompat.getDrawable(getResources(), R.drawable.ic_launcher, null);
            updateNotification();
            return;
        }

        // 优先从缓存加载
        StationIconCache iconCache = StationIconCache.getInstance(itsContext);
        String cachedPath = iconCache.getIconPath(currentStation.StationUuid);
        if (cachedPath != null) {
            Picasso.get()
                    .load(Uri.fromFile(new java.io.File(cachedPath)))
                    .resize((int) px, 0)
                    .into(new Target() {
                        @Override
                        public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
                            final boolean useCircularIcons = Utils.useCircularIcons(itsContext);
                            if (!useCircularIcons)
                                radioIcon = new BitmapDrawable(getResources(), bitmap);
                            else {
                                RoundedBitmapDrawable rb = RoundedBitmapDrawableFactory.create(getResources(), bitmap);
                                rb.setCircular(true);
                                radioIcon = new BitmapDrawable(getResources(), rb.getBitmap());
                            }
                            updateNotification();
                        }

                        @Override
                        public void onBitmapFailed(Exception e, Drawable errorDrawable) {
                            radioIcon = (BitmapDrawable) ResourcesCompat.getDrawable(getResources(), R.drawable.ic_launcher, null);
                            updateNotification();
                        }

                        @Override
                        public void onPrepareLoad(Drawable placeHolderDrawable) {
                        }
                    });
            return;
        }

        // 缓存未命中，使用多渠道回退策略
        List<String> urlsToTry = new ArrayList<>();
        if (currentStation.hasIcon()) {
            urlsToTry.add(currentStation.IconUrl);
        }
        if (!TextUtils.isEmpty(currentStation.HomePageUrl)) {
            try {
                java.net.URI uri = new java.net.URI(currentStation.HomePageUrl);
                String domain = uri.getHost();
                if (domain != null && !domain.isEmpty()) {
                    String scheme = uri.getScheme() != null ? uri.getScheme() : "https";
                    urlsToTry.add(scheme + "://" + domain + "/apple-touch-icon.png");
                    urlsToTry.add(scheme + "://" + domain + "/apple-touch-icon-precomposed.png");
                    urlsToTry.add(scheme + "://" + domain + "/android-chrome-192x192.png");
                    urlsToTry.add(scheme + "://" + domain + "/favicon.ico");
                    urlsToTry.add("https://www.google.com/s2/favicons?domain=" + domain + "&sz=256");
                }
            } catch (Exception ignored) {}
        }

        tryLoadIconForNotification(urlsToTry, 0, px);
    }

    private void tryLoadIconForNotification(final List<String> urls, final int index, final float px) {
        if (index >= urls.size()) {
            radioIcon = (BitmapDrawable) ResourcesCompat.getDrawable(getResources(), R.drawable.ic_launcher, null);
            updateNotification();
            return;
        }

        Picasso.get()
                .load(urls.get(index))
                .resize((int) px, 0)
                .networkPolicy(index == 0 ? NetworkPolicy.OFFLINE : NetworkPolicy.NO_CACHE)
                .into(new Target() {
                    @Override
                    public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
                        final boolean useCircularIcons = Utils.useCircularIcons(itsContext);
                        if (!useCircularIcons)
                            radioIcon = new BitmapDrawable(getResources(), bitmap);
                        else {
                            RoundedBitmapDrawable rb = RoundedBitmapDrawableFactory.create(getResources(), bitmap);
                            rb.setCircular(true);
                            radioIcon = new BitmapDrawable(getResources(), rb.getBitmap());
                        }
                        // 保存到缓存
                        boolean isFavorite = isStationFavorited(currentStation.StationUuid);
                        StationIconCache.getInstance(itsContext).saveIcon(currentStation.StationUuid, bitmap, isFavorite);
                        updateNotification();
                    }

                    @Override
                    public void onBitmapFailed(Exception e, Drawable errorDrawable) {
                        tryLoadIconForNotification(urls, index + 1, px);
                    }

                    @Override
                    public void onPrepareLoad(Drawable placeHolderDrawable) {
                    }
                });
    }

    private boolean isStationFavorited(String stationUuid) {
        try {
            RadioDroidApp app = (RadioDroidApp) itsContext.getApplicationContext();
            FavouriteManager favMgr = app.getFavouriteManager();
            return favMgr != null && favMgr.has(stationUuid);
        } catch (Exception e) {
            return false;
        }
    }

    private void sendConnectionTypeChangedBroadcast(ConnectivityChecker.ConnectionType connectionType) {
        Intent broadcast = new Intent();
        broadcast.setAction(PLAYER_SERVICE_CONNECTION_TYPE_CHANGED);
        broadcast.putExtra(PLAYER_SERVICE_CONNECTION_TYPE_EXTRA, connectionType.name());
        LocalBroadcastManager.getInstance(itsContext).sendBroadcast(broadcast);
    }

    private void startConnectionTypeListener() {
        connectivityChecker.startListening(PlayerService.this, connectivityCallback);
        sendConnectionTypeChangedBroadcast(ConnectivityChecker.getCurrentConnectionType(PlayerService.this));
    }

    private void stopConnectionTypeListener() {
        connectivityChecker.stopListening(PlayerService.this);
    }

    @Override
    public void onStateChanged(final PlayState state, final int audioSessionId) {
        // State changed can be called from the player's thread.

        handler.post(new Runnable() {
            @Override
            public void run() {
                lastErrorFromPlayer = -1;

                switch (state) {
                    case Paused:
                        break;
                    case Playing: {
                        if (playStateIsPlaying) {
                            currentPlayingSessionStart = System.currentTimeMillis();
                            lastPlayStartTime = currentPlayingSessionStart;
                            applyEqualizerAndFadeIn(audioSessionId, currentIsAlarm);
                            break;
                        }

                        enableMediaSession();

                        if (BuildConfig.DEBUG) {
                        }

                        playStateIsPlaying = true;

                        Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
                        i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
                        i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
                        itsContext.sendBroadcast(i);

                        applyEqualizerAndFadeIn(audioSessionId, currentIsAlarm);
                        break;
                    }
                    default: {
                        // 离开 Playing 状态（缓冲/停止/出错/切换电台）后：
                        // 1) 先取消所有待执行的渐入任务，避免旧 fade-in 回调在缓冲期间继续上调音量
                        //    （Android 5.x 残留爆音根因：BUFFERING/切换电台抖动时，旧任务仍被 postDelayed
                        //    执行，用户会听到"少量初始声音"，READY 恢复时又被 setVolume(0) 截断）
                        // 2) 立即静音，确保缓冲期间不输出声音
                        cancelPendingFadeIn();
                        if (radioPlayer != null) {
                            radioPlayer.setVolume(0f);
                        }

                        releaseServiceEqualizer();
                        // 离开 Playing 状态（缓冲/停止/出错）后，下次 Playing 需重新初始化音量与均衡器
                        eqAndFadeInitialized = false;

                        if (state != PlayState.PrePlaying) {
                            disableMediaSession();
                        }

                        if (audioSessionId > 0) {
                            if (BuildConfig.DEBUG) {
                            }

                            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
                            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
                            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
                            itsContext.sendBroadcast(i);
                        }

                        if (state == PlayState.Idle) {
                            stop();
                        }

                        break;
                    }
                }

                if (state != PlayState.Playing && playStateIsPlaying && currentPlayingSessionStart > 0) {
                    totalPlayTimeAccumulatedMillis += System.currentTimeMillis() - currentPlayingSessionStart;
                    currentPlayingSessionStart = 0;
                }
                if (state != PlayState.Playing) {
                    playStateIsPlaying = false;
                }

                if (state == PlayState.Playing) {
                    startConnectionTypeListener();
                } else {
                    stopConnectionTypeListener();
                }

                updateNotification(state);

                final Intent intent = new Intent();
                intent.setAction(PLAYER_SERVICE_STATE_CHANGE);
                intent.putExtra(PLAYER_SERVICE_STATE_EXTRA_KEY, (Parcelable) state);
                LocalBroadcastManager.getInstance(itsContext).sendBroadcast(intent);
            }
        });
    }

    @Override
    public void onPlayerWarning(int messageId) {
        onPlayerError(messageId);
    }

    @Override
    public void onPlayerError(int messageId) {
        handler.post(() -> {
            PlayerService.this.lastErrorFromPlayer = messageId;

            toastOnUi(messageId);
            updateNotification();
        });
    }

    @Override
    public void onBufferedTimeUpdate(long bufferedMs) {

    }

    @Override
    public void foundShoutcastStream(ShoutcastInfo info, boolean isHls) {
        this.streamInfo = info;
        this.isHls = isHls;
        if (info != null) {
//            if (info.audioName != null) {
//                if (!info.audioName.trim().equals("")) {
//                    currentStationName = info.audioName.trim();
//                }
//            }

            if (BuildConfig.DEBUG) {
            }
        }
        sendBroadCast(PLAYER_SERVICE_META_UPDATE);
    }

    @Override
    public void foundLiveStreamInfo(final StreamLiveInfo liveInfo) {
        // 此回调由 ExoPlayer/IcyDataSource 在子线程触发，而 currentStation、liveInfo、
        // radioPlayer.getPlayState() 等字段均在主线程修改。直接在子线程访问会引发：
        // 1) oldLiveInfo.getTitle().equals(...) 在 getTitle() 返回 null 时 NPE；
        // 2) updateNotification() 读取 currentStation.StationUuid 时 currentStation 已被切到新电台或为 null；
        // 3) 通知显示错误电台、轨道历史写入错误 stationUuid。
        // 因此将整个方法体 post 到主 handler 串行化执行
        handler.post(() -> {
            StreamLiveInfo oldLiveInfo = this.liveInfo;
            this.liveInfo = liveInfo;

            // null 安全的 title 比较：oldLiveInfo 或其 title 为 null 时视为变化
            String oldTitle = oldLiveInfo == null ? null : oldLiveInfo.getTitle();
            String newTitle = liveInfo.getTitle();
            boolean titleChanged = oldTitle == null ? newTitle != null : !oldTitle.equals(newTitle);

            if (titleChanged) {
                sendBroadCast(PLAYER_SERVICE_META_UPDATE);
                updateNotification();

                // currentStation 可能在切台过程中被置 null，必须防御
                if (currentStation == null) {
                    return;
                }

                Calendar calendar = Calendar.getInstance();
                Date currentTime = calendar.getTime();

                trackHistoryRepository.getLastInsertedHistoryItem((trackHistoryEntry, dao) -> {
                    if (trackHistoryEntry != null && liveInfo.getTitle() != null
                            && liveInfo.getTitle().equals(trackHistoryEntry.title)) {
                        // Prevent from generating several same entries when rapidly doing pause and resume.
                        trackHistoryEntry.endTime = new Date(0);
                        dao.update(trackHistoryEntry);
                    } else {
                        dao.setCurrentPlayingTrackEndTime(currentTime);

                        TrackHistoryEntry newTrackHistoryEntry = new TrackHistoryEntry();
                        newTrackHistoryEntry.stationUuid = currentStation.StationUuid;
                        newTrackHistoryEntry.artist = liveInfo.getArtist();
                        newTrackHistoryEntry.title = liveInfo.getTitle();
                        newTrackHistoryEntry.track = liveInfo.getTrack();
                        newTrackHistoryEntry.stationIconUrl = currentStation.IconUrl;
                        newTrackHistoryEntry.startTime = currentTime;
                        newTrackHistoryEntry.endTime = new Date(0);

                        trackHistoryRepository.insert(newTrackHistoryEntry);
                    }
                });
            }
        });
    }

    @Override
    protected void onHandleWork(@NonNull Intent intent) {
    }

    // ============ 音量曲线补偿 ============
    // 监听系统音量变化，动态调整播放器最大增益
    // 解决：安静环境最小音量仍太大、嘈杂环境最大音量仍太小

    /**
     * 根据系统音量计算播放器最大增益。
     *
     * 补偿曲线（分段线性）：
     * - 系统音量 0%-35%：maxGain 从 0.5 线性升至 1.0（低音量降低 1 倍）
     * - 系统音量 35%-65%：maxGain = 1.0（不调整）
     * - 系统音量 65%-100%：maxGain 从 1.0 线性升至 2.0（嘈杂环境提升音量）
     *
     * 效果：
     * - 系统音量 0%：maxGain=0.5（降低 1 倍）
     * - 系统音量 35%：maxGain=1.0（正常）
     * - 系统音量 65%：maxGain=1.0（不变）
     * - 系统音量 100%：maxGain=2.0（提升 1 倍）
     */
    private void updateVolumeGain() {
        // 闹钟播放期间使用固定的 maxGain=1.0，音量由系统音量控制
        if (alarmVolumeOverride) {
            if (radioPlayer != null) {
                radioPlayer.setMaxGain(1.0f);
            }
            return;
        }

        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (maxVol <= 0) return;

        float maxGain;
        boolean volumeMappingEnabled = sharedPref != null && sharedPref.getBoolean("enable_volume_mapping", true);
        if (volumeMappingEnabled) {
            float ratio = (float) curVol / maxVol;  // 0.0 ~ 1.0
            if (ratio < 0.35f) {
                // 低音量端：降低 1 倍，从 0.5 线性升至 1.0
                maxGain = 0.5f + 0.5f * (ratio / 0.35f);
            } else if (ratio <= 0.65f) {
                // 中等音量：不调整
                maxGain = 1.0f;
            } else {
                // 高音量端：1.0 → 2.0 的线性映射
                maxGain = 1.0f + 1.0f * ((ratio - 0.65f) / 0.35f);
            }
        } else {
            // 关闭双层音量映射时使用原始线性控制，保持 maxGain 为 1.0
            maxGain = 1.0f;
        }

        if (radioPlayer != null) {
            radioPlayer.setMaxGain(maxGain);
        }
    }

    private void registerVolumeChangeReceiver() {
        if (volumeChangeReceiver != null) return;

        volumeChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateVolumeGain();
                checkHeadsetZeroVolumePause();
            }
        };

        IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        registerReceiver(volumeChangeReceiver, filter);
    }

    /**
     * 当系统音量调至 0 且对应音频设备连接时，根据设置自动暂停播放。
     * 优先级：有线耳机 > 蓝牙耳机 > 内置扬声器（无耳机连接时）
     */
    private void checkHeadsetZeroVolumePause() {
        // 闹钟音量渐增期间可能临时将系统音量设为 0，不应触发自动暂停
        if (alarmVolumeOverride) return;

        if (audioManager == null) return;

        int curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (curVol > 0) return;

        if (!PlayerServiceUtil.isPlaying()) return;

        boolean pauseOnWiredZero = sharedPref != null && sharedPref.getBoolean("pause_on_wired_headset_zero_volume", false);
        boolean pauseOnBluetoothZero = sharedPref != null && sharedPref.getBoolean("pause_on_bluetooth_headset_zero_volume", false);
        boolean pauseOnSpeakerZero = sharedPref != null && sharedPref.getBoolean("pause_on_speaker_zero_volume", false);

        if (!pauseOnWiredZero && !pauseOnBluetoothZero && !pauseOnSpeakerZero) return;

        if (pauseOnWiredZero && audioDeviceMonitor != null && audioDeviceMonitor.isWiredHeadsetConnected()) {
            Log.d(TAG, "Wired headset volume reached 0, pausing playback");
            PlayerServiceUtil.pause(PauseReason.USER);
            return;
        }

        if (pauseOnBluetoothZero && audioDeviceMonitor != null && audioDeviceMonitor.isBluetoothAudioConnected()) {
            Log.d(TAG, "Bluetooth headset volume reached 0, pausing playback");
            PlayerServiceUtil.pause(PauseReason.USER);
            return;
        }

        // 内置扬声器：仅在没有耳机连接时生效
        if (pauseOnSpeakerZero && audioDeviceMonitor != null
                && !audioDeviceMonitor.isWiredHeadsetConnected()
                && !audioDeviceMonitor.isBluetoothAudioConnected()) {
            Log.d(TAG, "Speaker volume reached 0 (no headset connected), pausing playback");
            PlayerServiceUtil.pause(PauseReason.USER);
        }
    }

    private void unregisterVolumeChangeReceiver() {
        if (volumeChangeReceiver != null) {
            try {
                unregisterReceiver(volumeChangeReceiver);
            } catch (Exception ignored) {}
            volumeChangeReceiver = null;
        }
    }
}
