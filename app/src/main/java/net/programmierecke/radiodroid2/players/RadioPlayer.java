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
import java.net.HttpURLConnection;
import java.net.URL;

import okhttp3.OkHttpClient;

public class RadioPlayer implements PlayerWrapper.PlayListener, Recordable {

    // #region debug-point C:debug-logger
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
    private Context mainContext;

    private String streamName;
    private String currentStationUuid = "";

    private HandlerThread playerThread;
    private Handler playerThreadHandler;

    private PlayerListener playerListener;
    private PlayState playState = PlayState.Idle;

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

        final OkHttpClient customizedHttpClient = radioDroidApp.newHttpClient()
                .connectTimeout(connectTimeout, TimeUnit.SECONDS)
                .readTimeout(readTimeout, TimeUnit.SECONDS)
                .build();

        playerThreadHandler.post(() -> currentPlayer.playRemote(customizedHttpClient, stationURL, mainContext, isAlarm, stationUuid));
    }

    public final void play(final DataRadioStation station, final boolean isAlarm) {
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
     * 外部使用 0-100 范围，内部通过指数曲线映射为 ExoPlayer 增益。
     *
     * 指数曲线使人耳感知的音量变化更均匀：
     * - 低音量端（0-30）：增益变化缓慢，避免最小音量仍然太大
     * - 高音量端（70-100）：增益变化加快，允许超过 1.0 增益提升最大音量
     *
     * 映射公式：gain = maxGain * (volume/100)^2
     * - volume=0  → gain=0（静音）
     * - volume=50 → gain=maxGain*0.25
     * - volume=100→ gain=maxGain
     */
    public final void setVolume(float volume) {
        float ratio = Math.max(0f, Math.min(1f, volume / VOLUME_RANGE));
        // 指数曲线：ratio^2 使低音量端更精细
        float gain = maxGain * ratio * ratio;
        // #region debug-point C:set-volume
        dbg("C", "RadioPlayer:248", "RadioPlayer.setVolume", java.util.Map.of("inputVolume", volume, "ratio", ratio, "gain", gain, "maxGain", maxGain));
        // #endregion
        currentPlayer.setVolume(gain);
    }

    /**
     * 设置最大增益系数。
     * 由 PlayerService 根据系统音量动态调整：
     * - 低系统音量 → maxGain < 1.0（更安静）
     * - 中系统音量 → maxGain = 1.0（不变）
     * - 高系统音量 → maxGain > 1.0（更响）
     */
    public void setMaxGain(float gain) {
        maxGain = Math.max(0.1f, Math.min(4.0f, gain));
    }

    public float getMaxGain() {
        return maxGain;
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
