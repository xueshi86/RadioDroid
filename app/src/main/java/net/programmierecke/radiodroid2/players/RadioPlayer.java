package net.programmierecke.radiodroid2.players;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.station.DataRadioStation;
import net.programmierecke.radiodroid2.station.live.ShoutcastInfo;
import net.programmierecke.radiodroid2.station.live.StreamLiveInfo;
import net.programmierecke.radiodroid2.players.exoplayer.ExoPlayerWrapper;
import net.programmierecke.radiodroid2.players.mediaplayer.MediaPlayerWrapper;
import net.programmierecke.radiodroid2.recording.Recordable;
import net.programmierecke.radiodroid2.recording.RecordableListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class RadioPlayer implements PlayerWrapper.PlayListener, Recordable {

    final private String TAG = "RadioPlayer";

    public interface PlayerListener {
        void onStateChanged(final PlayState status, final int audioSessionId);

        void onPlayerWarning(final int messageId);

        void onPlayerError(final int messageId);

        void onBufferedTimeUpdate(final long bufferedMs);

        // We are not interested in this events here so they will be forwarded to whoever hold RadioPlayer
        void foundShoutcastStream(ShoutcastInfo bitrate, boolean isHls);

        void foundLiveStreamInfo(StreamLiveInfo liveInfo);
    }

    private PlayerWrapper currentPlayer;
    private float maxGain = 1.0f;  // 最大增益系数，由 PlayerService 根据系统音量动态调整
    private boolean volumeMappingEnabled = true; // 是否启用双层音量映射
    private float lastVolume = 100f; // 最后一次设置的音量，用于开关切换后刷新
    private Context mainContext;

    private String streamName;
    private String currentStationUuid = "";

    private HandlerThread playerThread;
    private Handler playerThreadHandler;

    private PlayerListener playerListener;
    // volatile：playState 在主线程与 ExoPlayer 回调线程之间共享，需保证可见性，
    // 否则 stop() 可能读到过期值导致 ExoPlayer 不释放
    private volatile PlayState playState = PlayState.Idle;

    private StreamLiveInfo lastLiveInfo;

    private PlayStationTask playStationTask;

    private Runnable bufferCheckRunnable = new Runnable() {
        @Override
        public void run() {
            final long bufferTimeMs = currentPlayer.getBufferedMs();

            playerListener.onBufferedTimeUpdate(bufferTimeMs);


            playerThreadHandler.postDelayed(this, 2000);
        }
    };

    public RadioPlayer(Context mainContext) {
        this.mainContext = mainContext;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            // ExoPlayer has its own thread for cpu intensive tasks
            playerThreadHandler = new Handler(Looper.getMainLooper());
            currentPlayer = new ExoPlayerWrapper();
        } else {
            playerThread = new HandlerThread("MediaPlayerThread");
            playerThread.start();

            // MediaPlayer requires to be run in non-ui thread.
            playerThreadHandler = new Handler(playerThread.getLooper());
            // use old MediaPlayer on API levels < 16
            // https://github.com/google/ExoPlayer/issues/711
            currentPlayer = new MediaPlayerWrapper(playerThreadHandler);
        }

        currentPlayer.setStateListener(this);
    }

    public final void play(final String stationURL, final String streamName, final boolean isAlarm) {
        play(stationURL, streamName, isAlarm, "");
    }

    public final void play(final String stationURL, final String streamName, final boolean isAlarm, final String stationUuid) {
        setState(PlayState.PrePlaying, -1);

        this.streamName = streamName;
        this.currentStationUuid = stationUuid;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mainContext.getApplicationContext());
        final int connectTimeout = prefs.getInt("stream_connect_timeout", 4);
        final int readTimeout = prefs.getInt("stream_read_timeout", 10);

        RadioDroidApp radioDroidApp = (RadioDroidApp) mainContext.getApplicationContext();

        // TODO: Should we not pass http client if currentPlayer is external?

        // Use getHttpClient().newBuilder() to preserve UserAgentInterceptor and other
        // interceptors from the main client. Using newHttpClient() creates a fresh builder
        // without interceptors, causing many stream servers to reject requests (no User-Agent).
        final OkHttpClient customizedHttpClient = radioDroidApp.getHttpClient().newBuilder()
                .connectTimeout(connectTimeout, TimeUnit.SECONDS)
                .readTimeout(readTimeout, TimeUnit.SECONDS)
                .build();

        playerThreadHandler.post(() -> currentPlayer.playRemote(customizedHttpClient, stationURL, mainContext, isAlarm, stationUuid));
    }

    public final void play(final DataRadioStation station, final boolean isAlarm) {
        // 取消旧的链接解析任务：避免旧任务回调污染新播放状态（清空新任务引用、对新电台报错暂停）
        cancelStationLinkRetrieval();

        setState(PlayState.PrePlaying, -1);

        playStationTask = new PlayStationTask(station, mainContext,
                (url) -> RadioPlayer.this.play(station.playableUrl, station.Name, isAlarm, station.StationUuid),
                (executionResult) -> {
                    RadioPlayer.this.playStationTask = null;

                    if (executionResult == PlayStationTask.ExecutionResult.FAILURE) {
                        RadioPlayer.this.onPlayerError(R.string.error_station_load);
                    }
                });

        playStationTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void cancelStationLinkRetrieval() {
        if (playStationTask != null) {
            playStationTask.cancel(true);
            playStationTask = null;
        }
    }

    public final void pause() {
        cancelStationLinkRetrieval();

        playerThreadHandler.post(() -> {
            if (playState == PlayState.Idle || playState == PlayState.Paused) {
                return;
            }

            final int audioSessionId = getAudioSessionId();
            currentPlayer.pause();

            if (BuildConfig.DEBUG) {
                playerThreadHandler.removeCallbacks(bufferCheckRunnable);
            }

            setState(PlayState.Paused, audioSessionId);
        });
    }

    public final void stop() {
        if (playState == PlayState.Idle) {
            return;
        }

        cancelStationLinkRetrieval();

        playerThreadHandler.post(() -> {
            final int audioSessionId = getAudioSessionId();

            currentPlayer.stop();

            if (BuildConfig.DEBUG) {
                playerThreadHandler.removeCallbacks(bufferCheckRunnable);
            }

            setState(PlayState.Idle, audioSessionId);
        });
    }

    public final void destroy() {
        stop();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            Looper looper = playerThread.getLooper();
            if (looper != null) {
                playerThreadHandler.post(() -> playerThread.quit());
            }
        }
    }

    public final boolean isPlaying() {
        // From user perspective PlayState.PrePlaying is playing and otherwise will lead to
        // inconsistencies in UI.
        return playState == PlayState.PrePlaying || playState == PlayState.Playing;
    }

    public final int getAudioSessionId() {
        return currentPlayer.getAudioSessionId();
    }

    private static final float VOLUME_RANGE = 100f;

    /**
     * 设置播放音量。
     * 外部使用 0-100 范围，内部通过对称指数曲线映射为 ExoPlayer 增益。
     *
     * 以 50% 为对称点的指数曲线，保持低音量区和高音量区曲度一致：
     * - 低音量端（0-50）：gain 低于线性，最低为 maxGain 的 0.5 倍（降低 1 倍）
     * - 高音量端（50-100）：gain 高于线性，最高可达 maxGain 的 2 倍（提升 1 倍）
     *
     * 映射公式：gain = maxGain * 2^((volume/100)*2 - 1)
     * - volume=0  → gain=0（静音）
     * - volume=25 → gain=maxGain*2^(-0.5)≈maxGain*0.707
     * - volume=50 → gain=maxGain（正常）
     * - volume=100→ gain=maxGain*2
     */
    public final void setVolume(float volume) {
        lastVolume = volume;
        float ratio = Math.max(0f, Math.min(1f, volume / VOLUME_RANGE));
        float gain;
        if (ratio <= 0f) {
            gain = 0f;
        } else if (volumeMappingEnabled) {
            // 对称指数曲线：以 50% 为中心，低音量降低 1 倍，高音量提升 1 倍
            float normalized = ratio * 2f - 1f; // -1 ~ 1
            gain = maxGain * (float) Math.pow(2.0, normalized);
        } else {
            // 关闭增强时：使用原始线性音量控制
            gain = maxGain * ratio;
        }
        currentPlayer.setVolume(gain);
    }

    /**
     * 设置最大增益系数。
     * 由 PlayerService 根据系统音量动态调整：
     * - 低系统音量（<35%）→ maxGain = 0.5 ~ 1.0（降低 1 倍）
     * - 中系统音量（35%-65%）→ maxGain = 1.0（不变）
     * - 高系统音量（65%-100%）→ maxGain = 1.0 ~ 2.0（提升 1 倍）
     */
    public void setMaxGain(float gain) {
        maxGain = Math.max(0.1f, Math.min(4.0f, gain));
    }

    public float getMaxGain() {
        return maxGain;
    }

    /**
     * 最近一次 setVolume 传入的应用层音量（0~100）。
     * PlayerService 在线性渐入被中断时用于反解当前实际增益。
     */
    public float getLastVolume() {
        return lastVolume;
    }

    /**
     * 启用或禁用双层音量映射。
     * 禁用后将使用原始线性音量控制（gain = maxGain * ratio）。
     */
    public void setVolumeMappingEnabled(boolean enabled) {
        volumeMappingEnabled = enabled;
    }

    /**
     * 使用最后一次记录的音量重新应用当前音量映射设置。
     * 在开关状态变化后调用，可立即生效。
     */
    public void refreshVolume() {
        setVolume(lastVolume);
    }

    @Override
    public boolean canRecord() {
        return currentPlayer.canRecord();
    }

    @Override
    public void startRecording(@NonNull RecordableListener recordableListener) {
        currentPlayer.startRecording(recordableListener);
    }

    @Override
    public void stopRecording() {
        currentPlayer.stopRecording();
    }

    @Override
    public boolean isRecording() {
        return currentPlayer.isRecording();
    }

    @Override
    public Map<String, String> getRecordNameFormattingArgs() {
        Map<String, String> args = new HashMap<>();
        args.put("station", Utils.sanitizeName(streamName));

        if (lastLiveInfo != null) {
            String artist = lastLiveInfo.getArtist();
            String track = lastLiveInfo.getTrack();
            if (isUnknownMetadata(artist)) {
                args.put("artist", "-");
            } else {
                args.put("artist", Utils.sanitizeName(artist));
            }
            if (isUnknownMetadata(track)) {
                args.put("track", "-");
            } else {
                args.put("track", Utils.sanitizeName(track));
            }
        } else {
            args.put("artist", "-");
            args.put("track", "-");
        }

        return args;
    }

    private boolean isUnknownMetadata(String value) {
        if (value == null || value.trim().isEmpty()) {
            return true;
        }
        String lower = value.trim().toLowerCase();
        return lower.equals("unknown artist") || lower.equals("unknown track") ||
               lower.equals("unknown") || lower.equals("-");
    }

    @Override
    public String getExtension() {
        return currentPlayer.getExtension();
    }

    public final void runInPlayerThread(Runnable runnable) {
        playerThreadHandler.post(runnable);
    }

    public final void setPlayerListener(PlayerListener listener) {
        playerListener = listener;
    }

    public PlayState getPlayState() {
        return playState;
    }

    private void setState(PlayState state, int audioSessionId) {

        if (playState == state) {
            if (state == PlayState.Playing) {
                playerListener.onStateChanged(state, audioSessionId);
            }
            return;
        }

        if (BuildConfig.DEBUG) {
            if (state == PlayState.Playing) {
                playerThreadHandler.removeCallbacks(bufferCheckRunnable);
                playerThreadHandler.post(bufferCheckRunnable);
            } else {
                playerThreadHandler.removeCallbacks(bufferCheckRunnable);
            }
        }

        playState = state;
        playerListener.onStateChanged(state, audioSessionId);
    }

    public long getTotalTransferredBytes() {
        return currentPlayer.getTotalTransferredBytes();
    }

    public long getCurrentPlaybackTransferredBytes() {
        return currentPlayer.getCurrentPlaybackTransferredBytes();
    }

    public long getBufferedSeconds() {
        return currentPlayer.getBufferedMs() / 1000;
    }

    public boolean isLocal() {
        return currentPlayer.isLocal();
    }

    @Override
    public void onStateChanged(PlayState state) {
        setState(state, getAudioSessionId());
    }

    @Override
    public void onPlayerWarning(int messageId) {
        playerThreadHandler.post(() -> playerListener.onPlayerWarning(messageId));
    }

    @Override
    public void onPlayerError(int messageId) {
        pause();
        playerThreadHandler.post(() -> playerListener.onPlayerError(messageId));
    }

    @Override
    public void onDataSourceShoutcastInfo(ShoutcastInfo shoutcastInfo, boolean isHls) {
        playerListener.foundShoutcastStream(shoutcastInfo, isHls);
    }

    @Override
    public void onDataSourceStreamLiveInfo(StreamLiveInfo liveInfo) {
        lastLiveInfo = liveInfo;
        playerListener.foundLiveStreamInfo(liveInfo);
    }
}
