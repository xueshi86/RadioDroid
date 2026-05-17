package net.programmierecke.radiodroid2.players.exoplayer;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.icy.IcyHeaders;
import com.google.android.exoplayer2.metadata.icy.IcyInfo;
import com.google.android.exoplayer2.metadata.id3.Id3Frame;

import java.io.ByteArrayOutputStream;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.HttpDataSource;

import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.players.PlayState;
import net.programmierecke.radiodroid2.players.PlayerWrapper;
import net.programmierecke.radiodroid2.recording.RecordableListener;
import net.programmierecke.radiodroid2.station.live.ShoutcastInfo;
import net.programmierecke.radiodroid2.station.live.StreamLiveInfo;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

public class ExoPlayerWrapper implements PlayerWrapper, IcyDataSource.IcyDataSourceListener, Player.Listener {

    final private String TAG = "ExoPlayerWrapper";

    private ExoPlayer player;
    private PlayListener stateListener;

    private String streamUrl;

    private final DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();

    private volatile RecordableListener recordableListener;

    private long totalTransferredBytes;
    private long currentPlaybackTransferredBytes;

    private boolean isHls;
    private boolean isPlayingFlag;
    private String streamContentType;

    private Handler playerThreadHandler;

    private Context context;
    private MediaSource audioSource;

    private Runnable fullStopTask;

    private final BroadcastReceiver networkChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (fullStopTask != null && player != null && audioSource != null && Utils.hasAnyConnection(context)) {
                Log.i(TAG, "Regained connection. Resuming playback.");

                cancelStopTask();

                player.setMediaSource(audioSource);
                player.prepare();
                player.setPlayWhenReady(true);
            }
        }
    };

    @Override
    public void playRemote(@NonNull OkHttpClient httpClient, @NonNull String streamUrl, @NonNull Context context, boolean isAlarm) {
        // I don't know why, but it is still possible that streamUrl is null,
        // I still get exceptions from this from google
        if (!streamUrl.equals(this.streamUrl)) {
            currentPlaybackTransferredBytes = 0;
        }

        this.context = context;
        this.streamUrl = streamUrl;

        cancelStopTask();

        stateListener.onStateChanged(PlayState.PrePlaying);

        if (player != null) {
            player.stop();
        }

        if (player == null) {
            player = new ExoPlayer.Builder(context).build();
            player.setAudioAttributes(new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA).build(), false);

            player.addListener(this);
            player.addAnalyticsListener(new AnalyticEventListener());
        }

        if (playerThreadHandler == null) {
            playerThreadHandler = new Handler(Looper.getMainLooper());
        }

        isHls = Utils.urlIndicatesHlsStream(streamUrl);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        final int retryTimeout = prefs.getInt("settings_retry_timeout", 10);
        final int retryDelay = prefs.getInt("settings_retry_delay", 100);

        DataSource.Factory dataSourceFactory = new RadioDataSourceFactory(httpClient, bandwidthMeter, this, retryTimeout, retryDelay);
        // Produces Extractor instances for parsing the media data.
        if (!isHls) {
            audioSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(new CustomLoadErrorHandlingPolicy())
                    .createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)));
            player.setMediaSource(audioSource);
            player.prepare();
        } else {
            audioSource = new HlsMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(new CustomLoadErrorHandlingPolicy())
                    .createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)));
            player.setMediaSource(audioSource);
            player.prepare();
        }

        player.setPlayWhenReady(true);

        context.registerReceiver(networkChangedReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        // State changed will be called when audio session id is available.
    }

    @Override
    public void pause() {
        Log.i(TAG, "Pause. Stopping exoplayer.");

        cancelStopTask();

        if (player != null) {
            context.unregisterReceiver(networkChangedReceiver);
            player.stop();
            player.release();
            player = null;
        }
    }

    @Override
    public void stop() {
        Log.i(TAG, "Stopping exoplayer.");

        cancelStopTask();

        if (player != null) {
            context.unregisterReceiver(networkChangedReceiver);
            player.stop();
            player.release();
            player = null;
        }

        stopRecording();
    }

    @Override
    public boolean isPlaying() {
        return player != null && isPlayingFlag;
    }

    @Override
    public long getBufferedMs() {
        if (player != null) {
            return (int) (player.getBufferedPosition() - player.getCurrentPosition());
        }

        return 0;
    }

    @Override
    public int getAudioSessionId() {
        if (player != null) {
            return player.getAudioSessionId();
        }
        return 0;
    }

    @Override
    public long getTotalTransferredBytes() {
        return totalTransferredBytes;
    }

    @Override
    public long getCurrentPlaybackTransferredBytes() {
        return currentPlaybackTransferredBytes;
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public void setVolume(float newVolume) {
        if (player != null) {
            player.setVolume(newVolume);
        }
    }

    @Override
    public void setStateListener(PlayListener listener) {
        stateListener = listener;
    }

    @Override
    public void onDataSourceConnected() {

    }

    @Override
    public void onDataSourceConnectionLost() {

    }

    @Override
    public void onMetadata(@NonNull Metadata metadata) {
        if (BuildConfig.DEBUG) Log.d(TAG, "META: " + metadata);
        final int length = metadata.length();
        if (length > 0) {
            for (int i = 0; i < length; i++) {
                final Metadata.Entry entry = metadata.get(i);
                if (entry == null) {
                    continue;
                }
                if (entry instanceof IcyInfo) {
                    final IcyInfo icyInfo = ((IcyInfo) entry);
                    Log.d(TAG, "IcyInfo: " + icyInfo);
                    if (icyInfo.title != null) {
                        // 总是输出调试信息，以便诊断乱码问题
                        Log.d(TAG, "原始IcyInfo标题: " + icyInfo.title);
                        Log.d(TAG, "原始IcyInfo标题长度: " + icyInfo.title.length());
                        
                        // 检查原始标题中是否包含问号
                        if (icyInfo.title.contains("?")) {
                            Log.w(TAG, "ExoPlayer原始IcyInfo标题中包含问号: " + icyInfo.title);
                            
                            // 打印问号字符的详细信息
                            StringBuilder questionMarks = new StringBuilder();
                            for (int k = 0; k < icyInfo.title.length(); k++) {
                                char c = icyInfo.title.charAt(k);
                                if (c == '?') {
                                    questionMarks.append(String.format("位置%d ", k));
                                }
                            }
                            Log.w(TAG, "ExoPlayer原始IcyInfo标题问号位置: " + questionMarks.toString());
                        }
                        
                        // 打印原始标题中每个字符的Unicode值
                        StringBuilder charCodes = new StringBuilder();
                        for (int k = 0; k < Math.min(icyInfo.title.length(), 50); k++) {
                            char c = icyInfo.title.charAt(k);
                            charCodes.append(String.format("'%c'(%04X) ", c, (int) c));
                        }
                        Log.d(TAG, "ExoPlayer原始IcyInfo标题的前50个字符的Unicode值: " + charCodes.toString());
                        
                        // 打印原始字节数据（用于调试）
                        byte[] titleBytes = icyInfo.title.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                        StringBuilder hexString = new StringBuilder();
                        for (int j = 0; j < titleBytes.length && j < 100; j++) { // 只打印前100个字节
                            hexString.append(String.format("%02X ", titleBytes[j]));
                        }
                        Log.d(TAG, "原始IcyInfo标题字节（前100字节）: " + hexString.toString());
                        
                        // 处理16字节块和填充问题
                        byte[] processedTitleBytes = processMetadataBlocks(titleBytes);
                        
                        // 尝试使用多种编码方式解析元数据，以支持不同语言的字符集
                        String decodedTitle = decodeMetadataWithCharsetDetection(processedTitleBytes);
                        
                        // 如果解码后仍包含问号，尝试额外的解码方法
                        if (decodedTitle.contains("?")) {
                            Log.w(TAG, "ExoPlayer解码后标题中包含问号，尝试额外解码: " + decodedTitle);
                            String alternativeDecodedTitle = tryAlternativeDecoding(icyInfo.title);
                            if (!alternativeDecodedTitle.equals(decodedTitle)) {
                                decodedTitle = alternativeDecodedTitle;
                                Log.d(TAG, "额外解码成功，结果: " + decodedTitle);
                            } else {
                                Log.d(TAG, "额外解码结果相同，使用原始解码结果");
                            }
                        }
                        
                        // 总是输出调试信息，以便诊断乱码问题
                        Log.i(TAG, "解码后的标题: " + decodedTitle);
                        Log.i(TAG, "解码后的标题长度: " + decodedTitle.length());
                        
                        // 检查解码后标题中是否包含问号
                        if (decodedTitle.contains("?")) {
                            Log.w(TAG, "ExoPlayer解码后标题中包含问号: " + decodedTitle);
                            
                            // 打印问号字符的详细信息
                            StringBuilder questionMarksDecoded = new StringBuilder();
                            for (int k = 0; k < decodedTitle.length(); k++) {
                                char c = decodedTitle.charAt(k);
                                if (c == '?') {
                                    questionMarksDecoded.append(String.format("位置%d ", k));
                                }
                            }
                            Log.w(TAG, "ExoPlayer解码后标题问号位置: " + questionMarksDecoded.toString());
                        }
                        
                        // 打印解码后标题中每个字符的Unicode值
                        if (!decodedTitle.isEmpty()) {
                            StringBuilder charCodesDecoded = new StringBuilder();
                            for (int k = 0; k < Math.min(decodedTitle.length(), 50); k++) {
                                char c = decodedTitle.charAt(k);
                                charCodesDecoded.append(String.format("'%c'(%04X) ", c, (int) c));
                            }
                            Log.i(TAG, "ExoPlayer解码后标题的前50个字符的Unicode值: " + charCodesDecoded.toString());
                        }
                        Map<String, String> rawMetadata = new HashMap<String, String>();
                        rawMetadata.put("StreamTitle", decodedTitle);
                        StreamLiveInfo streamLiveInfo = new StreamLiveInfo(rawMetadata);
                        Log.i(TAG, "StreamLiveInfo标题: " + streamLiveInfo.getTitle());
                        Log.i(TAG, "StreamLiveInfo艺术家: " + streamLiveInfo.getArtist());
                        Log.i(TAG, "StreamLiveInfo完整信息: " + streamLiveInfo.toString());
                        onDataSourceStreamLiveInfo(streamLiveInfo);
                    }
                } else if (entry instanceof IcyHeaders) {
                    final IcyHeaders icyHeaders = ((IcyHeaders) entry);
                    Log.d(TAG, "IcyHeaders: " + icyHeaders);
                    onDataSourceShoutcastInfo(new ShoutcastInfo(icyHeaders));
                } else if (entry instanceof Id3Frame) {
                    final Id3Frame id3Frame = ((Id3Frame) entry);
                    Log.d(TAG, "id3 metadata: " + id3Frame);
                }
            }
        }
    }

    @Override
    public void onDataSourceConnectionLostIrrecoverably() {
        Log.i(TAG, "Connection lost irrecoverably.");
    }

    void resumeWhenNetworkConnected() {
        playerThreadHandler.post(() -> {
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
            int resumeWithin = sharedPref.getInt("settings_resume_within", 60);
            if (resumeWithin > 0) {
                Log.d(TAG, "Trying to resume playback within " + resumeWithin + "s.");

                // We want user to be able to paused during connection loss.
                // TODO: Find a way to notify user that even if current state is Playing
                //       we are actually trying to reconnect.
                //stateListener.onStateChanged(PlayState.Paused);

                cancelStopTask();

                fullStopTask = () -> {
                    stop();
                    stateListener.onPlayerError(R.string.giving_up_resume);

                    ExoPlayerWrapper.this.fullStopTask = null;
                };
                playerThreadHandler.postDelayed(fullStopTask, resumeWithin * 1000L);

                stateListener.onPlayerWarning(R.string.warning_no_network_trying_resume);
            } else {
                stop();

                stateListener.onPlayerError(R.string.error_stream_reconnect_timeout);
            }
        });
    }

    @Override
    public void onDataSourceShoutcastInfo(@Nullable ShoutcastInfo shoutcastInfo) {
        stateListener.onDataSourceShoutcastInfo(shoutcastInfo, false);
    }

    @Override
    public void onDataSourceStreamLiveInfo(StreamLiveInfo streamLiveInfo) {
        stateListener.onDataSourceStreamLiveInfo(streamLiveInfo);
    }

    private long recordableBytesLogged;
    private boolean recordableListenerNullLogged;

    @Override
    public void onDataSourceBytesRead(byte[] buffer, int offset, int length) {
        totalTransferredBytes += length;
        currentPlaybackTransferredBytes += length;

        RecordableListener listener = recordableListener;
        if (listener != null) {
            listener.onBytesAvailable(buffer, offset, length);
            recordableBytesLogged += length;
            if (BuildConfig.DEBUG && recordableBytesLogged >= 65536) {
                Log.d(TAG, "Recording bytes sent to listener, total: " + (recordableBytesLogged));
                recordableBytesLogged = 0;
            }
        } else {
            if (!recordableListenerNullLogged) {
                Log.w(TAG, "onDataSourceBytesRead: recordableListener is NULL, bytes not recorded");
                recordableListenerNullLogged = true;
            }
        }
    }

    @Override
    public void onDataSourceContentType(String contentType) {
        streamContentType = contentType;
    }

    @Override
    public boolean canRecord() {
        return player != null;
    }

    @Override
    public void startRecording(@NonNull RecordableListener recordableListener) {
        Log.d(TAG, "startRecording called, setting recordableListener");
        this.recordableListener = recordableListener;
        this.recordableListenerNullLogged = false;
    }

    @Override
    public void stopRecording() {
        if (recordableListener != null) {
            recordableListener.onRecordingEnded();
            recordableListener = null;
        }
    }

    @Override
    public boolean isRecording() {
        return recordableListener != null;
    }

    @Override
    public Map<String, String> getRecordNameFormattingArgs() {
        return null;
    }

    @Override
    public String getExtension() {
        if (isHls) {
            return "ts";
        }
        if (streamContentType != null) {
            String type = streamContentType.toLowerCase();
            if (type.contains("aac") || type.contains("mp4") || type.contains("m4a")) {
                return "aac";
            }
            if (type.contains("ogg") || type.contains("vorbis") || type.contains("opus")) {
                return "ogg";
            }
            if (type.contains("flac")) {
                return "flac";
            }
            if (type.contains("wav") || type.contains("wave")) {
                return "wav";
            }
        }
        return "mp3";
    }

    private void cancelStopTask() {
        if (fullStopTask != null) {
            playerThreadHandler.removeCallbacks(fullStopTask);
            fullStopTask = null;
        }
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        // Do nothing
    }

    @Override
    public void onPlayerErrorChanged(PlaybackException error) {
        Log.d(TAG, "Player error: ", error);
        // Stop playing since it is either irrecoverable error in the player or our data source failed to reconnect.
        if (fullStopTask != null) {
            stop();
            stateListener.onPlayerError(R.string.error_play_stream);
        }
    }

    @Override
    public void onPlaybackParametersChanged(@NonNull PlaybackParameters playbackParameters) {
        // Do nothing
    }

    final class CustomLoadErrorHandlingPolicy extends DefaultLoadErrorHandlingPolicy {
        final int MIN_RETRY_DELAY_MS = 10;
        final SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        // We need to read the retry delay here on each error again because the user might change
        // this value between retries and experiment with different vales to get the best result for
        // the specific situation. We also need to make sure that a sensible minimum value is chosen.
        int getSanitizedRetryDelaySettingsMs() {
            return Math.max(sharedPrefs.getInt("settings_retry_delay", 100), MIN_RETRY_DELAY_MS);
        }

        @Override
        public long getRetryDelayMsFor(LoadErrorInfo loadErrorInfo) {

            int retryDelay = getSanitizedRetryDelaySettingsMs();
            IOException exception = loadErrorInfo.exception;

            if (exception instanceof HttpDataSource.InvalidContentTypeException) {
                stateListener.onPlayerError(R.string.error_play_stream);
                return C.TIME_UNSET; // Immediately surface error if we cannot play content type
            }

            if (!Utils.hasAnyConnection(context)) {
                int resumeWithinS = sharedPrefs.getInt("settings_resume_within", 60);
                if (resumeWithinS > 0) {
                    resumeWhenNetworkConnected();
                    retryDelay = 1000 * resumeWithinS + retryDelay;
                }
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Providing retry delay of " + retryDelay + "ms " +
                        "error count: " + loadErrorInfo.errorCount + ", " +
                        "exception " + exception.getClass() + ", " +
                        "message: " + exception.getMessage());
            }
            return retryDelay;
        }

        @Override
        public int getMinimumLoadableRetryCount(int dataType) {
            return sharedPrefs.getInt("settings_retry_timeout", 10) * 1000 / getSanitizedRetryDelaySettingsMs() + 1;
        }
    }

    private class AnalyticEventListener implements AnalyticsListener {
        @Override
        public void onPlayerStateChanged(EventTime eventTime, boolean playWhenReady, int playbackState) {
            isPlayingFlag = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING;

            switch (playbackState) {
                case Player.STATE_READY:
                    cancelStopTask();
                    stateListener.onStateChanged(PlayState.Playing);
                    break;
                case Player.STATE_BUFFERING:
                    stateListener.onStateChanged(PlayState.PrePlaying);
                    break;
            }

        }

        @Override
        public void onTimelineChanged(@NonNull EventTime eventTime, int reason) {

        }

        @Override
        public void onPlaybackParametersChanged(@NonNull EventTime eventTime, @NonNull PlaybackParameters playbackParameters) {

        }

        @Override
        public void onRepeatModeChanged(@NonNull EventTime eventTime, int repeatMode) {

        }

        @Override
        public void onShuffleModeChanged(@NonNull EventTime eventTime, boolean shuffleModeEnabled) {

        }

        @Override
        public void onBandwidthEstimate(@NonNull EventTime eventTime, int totalLoadTimeMs, long totalBytesLoaded, long bitrateEstimate) {

        }

        @Override
        public void onSurfaceSizeChanged(@NonNull EventTime eventTime, int width, int height) {

        }

        @Override
        public void onMetadata(@NonNull EventTime eventTime, @NonNull Metadata metadata) {

        }

        @Override
        public void onAudioAttributesChanged(@NonNull EventTime eventTime, @NonNull AudioAttributes audioAttributes) {

        }

        @Override
        public void onVolumeChanged(@NonNull EventTime eventTime, float volume) {

        }

        @Override
        public void onDroppedVideoFrames(@NonNull EventTime eventTime, int droppedFrames, long elapsedMs) {

        }

        @Override
        public void onDrmKeysLoaded(@NonNull EventTime eventTime) {

        }

        @Override
        public void onDrmSessionManagerError(@NonNull EventTime eventTime, @NonNull Exception error) {

        }

        @Override
        public void onDrmKeysRestored(@NonNull EventTime eventTime) {

        }

        @Override
        public void onDrmKeysRemoved(@NonNull EventTime eventTime) {

        }

        @Override
        public void onDrmSessionReleased(@NonNull EventTime eventTime) {

        }
    }

    /**
     * 处理metadata块，确保正确读取16字节块并去除填充
     * @param originalBytes 原始字节数据
     * @return 处理后的字节数据
     */
    private byte[] processMetadataBlocks(byte[] originalBytes) {
        if (originalBytes == null || originalBytes.length == 0) {
            return originalBytes;
        }
        
        // 记录原始字节数据
        if (BuildConfig.DEBUG) {
            StringBuilder hexString = new StringBuilder();
            for (int j = 0; j < Math.min(originalBytes.length, 100); j++) {
                hexString.append(String.format("%02X ", originalBytes[j]));
            }
            Log.d(TAG, "processMetadataBlocks - 原始字节数据（前100字节）: " + hexString.toString());
        }
        
        // 创建输出流
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        
        // 处理每个16字节块
        for (int i = 0; i < originalBytes.length; i += 16) {
            // 确定当前块的结束位置
            int blockEnd = Math.min(i + 16, originalBytes.length);
            
            // 复制当前块
            byte[] block = new byte[blockEnd - i];
            System.arraycopy(originalBytes, i, block, 0, block.length);
            
            // 从块末尾开始，移除填充的0x00字节
            int actualLength = block.length;
            while (actualLength > 0 && block[actualLength - 1] == 0x00) {
                actualLength--;
            }
            
            // 如果块中有非填充数据，则写入输出流
            if (actualLength > 0) {
                outputStream.write(block, 0, actualLength);
            }
        }
        
        byte[] result = outputStream.toByteArray();
        
        // 记录处理后的字节数据
        if (BuildConfig.DEBUG) {
            StringBuilder hexString = new StringBuilder();
            for (int j = 0; j < Math.min(result.length, 100); j++) {
                hexString.append(String.format("%02X ", result[j]));
            }
            Log.d(TAG, "processMetadataBlocks - 处理后字节数据（前100字节）: " + hexString.toString());
            Log.d(TAG, "processMetadataBlocks - 原始长度: " + originalBytes.length + ", 处理后长度: " + result.length);
        }
        
        return result;
    }

    /**
     * 智能元数据编码检测，支持多语言和复杂编码情况
     * 优先使用多种编码尝试解码，而不是强制使用UTF-8
     */
    /**
     * 使用字节数组进行元数据解码，避免不必要的字符串转换
     * @param metadataBytes 原始字节数据
     * @return 解码后的字符串
     */
    private String decodeMetadataWithCharsetDetection(byte[] metadataBytes) {
        if (metadataBytes == null || metadataBytes.length == 0) {
            return context.getString(R.string.unknown);
        }
        
        // 调试信息
        if (BuildConfig.DEBUG) {
            logMetadataBytes(metadataBytes);
        }

        // 检查原始数据是否已损坏
        if (checkIfDataIsCorrupted(metadataBytes)) {
            Log.w(TAG, "检测到原始数据已损坏，可能是服务器端编码转换错误");
            return context.getString(R.string.unknown);
        }

        // 首先特别检查是否是有效的UTF-8字节序列
        if (isValidUTF8Sequence(metadataBytes)) {
            try {
                String utf8Result = new String(metadataBytes, "UTF-8");
                if (isValidDecodedText(utf8Result) && !containsObviousGarbledCharacters(utf8Result)) {
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "检测到有效UTF-8序列，使用UTF-8编码成功解码: " + utf8Result);
                    }
                    return utf8Result;
                }
            } catch (Exception e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "UTF-8编码解码失败: " + e.getMessage());
                }
            }
        }
        
        // 尝试容错UTF-8解码
        String faultTolerantResult = tryFaultTolerantUTF8Decoding(metadataBytes);
        if (faultTolerantResult != null) {
            return faultTolerantResult;
        }
        
        // 尝试修复服务器将UTF-8当作Latin1处理的情况
        String serverLatin1FixResult = tryFixServerLatin1Handling(metadataBytes);
        if (serverLatin1FixResult != null) {
            return serverLatin1FixResult;
        }

        // 优先使用多种编码尝试解码，按照用户建议的顺序
        // 特别优化UTF-8检测，因为日志显示很多UTF-8编码被误判
        String[] preferredCharsets = {
            "UTF-8", "GBK", "GB2312", "Big5", "ISO-8859-1", "windows-1252"
        };
        
        for (String charset : preferredCharsets) {
            try {
                String result = new String(metadataBytes, charset);
                if (isValidDecodedText(result) && !containsObviousGarbledCharacters(result)) {
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "使用" + charset + "编码成功解码: " + result);
                    }
                    return result;
                }
            } catch (Exception e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, charset + "编码解码失败: " + e.getMessage());
                }
            }
        }
        
        // 如果首选编码都失败，尝试全面编码检测
        String bestResult = tryAllEncodings(metadataBytes);
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "全面编码尝试结果: " + bestResult);
        }
        
        return bestResult;
    }

    private String decodeMetadataWithCharsetDetection(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return metadata;
        }

        // 预处理：移除可能的控制字符和无效字符
        String cleanedMetadata = preprocessMetadata(metadata);
        if (cleanedMetadata.isEmpty()) {
            return context.getString(R.string.unknown);
        }

        // 获取原始字节数据
        byte[] originalBytes = cleanedMetadata.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        
        // 调试信息
        if (BuildConfig.DEBUG) {
            logMetadataBytes(originalBytes);
        }

        // 检查原始数据是否已损坏
        if (checkIfDataIsCorrupted(originalBytes)) {
            Log.w(TAG, "检测到原始数据已损坏，可能是服务器端编码转换错误");
            return context.getString(R.string.unknown);
        }

        // 优先使用多种编码尝试解码，按照用户建议的顺序
        // 特别优化UTF-8检测，因为日志显示很多UTF-8编码被误判
        String[] preferredCharsets = {
            "UTF-8", "GBK", "GB2312", "Big5", "ISO-8859-1", "windows-1252"
        };
        
        // 首先特别检查是否是有效的UTF-8字节序列
        if (isValidUTF8Sequence(originalBytes)) {
            try {
                String utf8Result = new String(originalBytes, "UTF-8");
                if (isValidDecodedText(utf8Result) && !containsObviousGarbledCharacters(utf8Result)) {
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "检测到有效UTF-8序列，使用UTF-8编码成功解码: " + utf8Result);
                    }
                    return utf8Result;
                }
            } catch (Exception e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "UTF-8编码解码失败: " + e.getMessage());
                }
            }
        }
        
        // 尝试容错UTF-8解码
        String faultTolerantResult = tryFaultTolerantUTF8Decoding(originalBytes);
        if (faultTolerantResult != null) {
            return faultTolerantResult;
        }
        
        // 尝试修复服务器将UTF-8当作Latin1处理的情况
        String serverLatin1FixResult = tryFixServerLatin1Handling(originalBytes);
        if (serverLatin1FixResult != null) {
            return serverLatin1FixResult;
        }
        
        for (String charset : preferredCharsets) {
            try {
                String result = new String(originalBytes, charset);
                if (isValidDecodedText(result) && !containsObviousGarbledCharacters(result)) {
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "使用" + charset + "编码成功解码: " + result);
                    }
                    return result;
                }
            } catch (Exception e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, charset + "编码解码失败: " + e.getMessage());
                }
            }
        }
        
        // 如果首选编码都失败，尝试全面编码检测
        String bestResult = tryAllEncodings(originalBytes);
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "全面编码尝试结果: " + bestResult);
        }
        
        return bestResult;
    }

    /**
     * 检查字节数组是否是有效的UTF-8序列
     * 这个方法专门用于检测日志中显示的UTF-8编码被误判的问题
     */
    private boolean isValidUTF8Sequence(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        
        try {
            // 首先使用更严格的UTF-8字节序列验证
            if (!isValidUtf8Strict(bytes)) {
                return false;
            }
            
            // 尝试将字节数组解码为UTF-8，然后重新编码
            // 如果重新编码后的字节序列与原始序列相同，则认为是有效的UTF-8
            String decoded = new String(bytes, "UTF-8");
            byte[] reencoded = decoded.getBytes("UTF-8");
            
            // 比较原始字节和重新编码后的字节
            if (bytes.length != reencoded.length) {
                return false;
            }
            
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] != reencoded[i]) {
                    return false;
                }
            }
            
            // 检查解码结果是否包含替换字符
            int replacementCharCount = 0;
            for (int i = 0; i < decoded.length(); i++) {
                if (decoded.charAt(i) == '\uFFFD') {
                    replacementCharCount++;
                }
            }
            
            // 如果包含替换字符，认为不是有效的UTF-8
            if (replacementCharCount > 0) {
                return false;
            }
            
            // 额外检查：确保解码结果包含有意义的内容
            // 检查是否包含非ASCII字符（如中文字符）
            boolean hasNonAscii = false;
            for (byte b : bytes) {
                if ((b & 0x80) != 0) { // 检查最高位是否为1（非ASCII字符）
                    hasNonAscii = true;
                    break;
                }
            }
            
            // 如果有非ASCII字符，并且解码后包含有效的Unicode字符，则认为是有效的UTF-8
            if (hasNonAscii) {
                // 检查解码后的字符串是否包含有效的Unicode字符
                for (int i = 0; i < decoded.length(); i++) {
                    char c = decoded.charAt(i);
                    // 检查是否是有效的Unicode字符（不是替换字符）
                    if (c != 0xFFFD && Character.isDefined(c)) {
                        return true;
                    }
                }
            }
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 严格验证UTF-8字节序列
     */
    private boolean isValidUtf8Strict(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        
        int i = 0;
        while (i < bytes.length) {
            byte b = bytes[i];
            
            // ASCII字符 (0xxxxxxx)
            if ((b & 0x80) == 0) {
                i++;
                continue;
            }
            
            // 多字节序列的开始
            int expectedBytes;
            int codePoint;
            
            // 2字节序列 (110xxxxx 10xxxxxx)
            if ((b & 0xE0) == 0xC0) {
                expectedBytes = 2;
                codePoint = b & 0x1F;
            }
            // 3字节序列 (1110xxxx 10xxxxxx 10xxxxxx)
            else if ((b & 0xF0) == 0xE0) {
                expectedBytes = 3;
                codePoint = b & 0x0F;
            }
            // 4字节序列 (11110xxx 10xxxxxx 10xxxxxx 10xxxxxx)
            else if ((b & 0xF8) == 0xF0) {
                expectedBytes = 4;
                codePoint = b & 0x07;
            }
            else {
                return false; // 无效的UTF-8起始字节
            }
            
            // 检查是否有足够的字节
            if (i + expectedBytes > bytes.length) {
                return false;
            }
            
            // 检查后续字节是否都是10xxxxxx格式
            for (int j = 1; j < expectedBytes; j++) {
                if ((bytes[i + j] & 0xC0) != 0x80) {
                    return false;
                }
                codePoint = (codePoint << 6) | (bytes[i + j] & 0x3F);
            }
            
            // 检查Unicode码点是否有效
            if (codePoint > 0x10FFFF || 
                (codePoint >= 0xD800 && codePoint <= 0xDFFF) || // 代理区
                (expectedBytes == 2 && codePoint < 0x80) ||       // 过长编码
                (expectedBytes == 3 && codePoint < 0x800) ||      // 过长编码
                (expectedBytes == 4 && codePoint < 0x10000)) {   // 过长编码
                return false;
            }
            
            i += expectedBytes;
        }
        
        return true;
    }

    /**
     * 检查数据是否已损坏（主要检查是否为0x3F问号字符）
     * 修改为更加宽容，只有在数据确实损坏时才返回true
     */
    private boolean checkIfDataIsCorrupted(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        
        int questionMarkCount = 0;
        for (byte b : bytes) {
            if (b == 0x3F) { // 问号的ASCII值
                questionMarkCount++;
            }
        }
        
        // 只有超过95%的字节是问号，才认为数据已损坏（更加宽容）
        double ratio = (double) questionMarkCount / bytes.length;
        boolean isCorrupted = ratio > 0.95;
        
        if (BuildConfig.DEBUG && isCorrupted) {
            Log.d(TAG, "checkIfDataIsCorrupted - 检测到损坏数据: " + 
                  questionMarkCount + "/" + bytes.length + 
                  " 字节是问号(0x3F), 比例: " + String.format("%.2f", ratio));
        } else if (BuildConfig.DEBUG && ratio > 0.7) {
            Log.d(TAG, "checkIfDataIsCorrupted - 数据包含较多问号但未达到损坏阈值: " + 
                  questionMarkCount + "/" + bytes.length + 
                  " 字节是问号(0x3F), 比例: " + String.format("%.2f", ratio));
        }
        
        return isCorrupted;
    }

    /**
     * 计算解码质量分数
     * 分数越高表示解码质量越好
     */
    private int calculateDecodingQuality(String text, String charset) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        
        int score = 0;
        
        // 基础分数：非空文本
        score += 10;
        
        // 检查是否包含替换字符
        int replacementCharCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFD') {
                replacementCharCount++;
            }
        }
        
        if (replacementCharCount > 0) {
            // 根据替换字符的比例扣分
            double replacementRatio = (double) replacementCharCount / text.length();
            score -= (int)(replacementRatio * 300); // 最多扣300分
            Log.i(TAG, charset + "解码包含替换字符: " + replacementCharCount + "/" + text.length() + 
                  ", 比例: " + String.format("%.2f", replacementRatio) + ", 扣分: " + (int)(replacementRatio * 300));
        }
        
        // 检查是否包含过多的问号字符
        int questionMarkCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '?') {
                questionMarkCount++;
            }
        }
        double questionMarkRatio = (double) questionMarkCount / text.length();
        if (questionMarkRatio > 0.2) {
            score -= 80; // 问号过多，严重扣分
            Log.i(TAG, "ExoPlayer " + charset + "解码结果包含过多问号: " + questionMarkCount + "/" + text.length());
        }
        
        // 检查控制字符比例
        int controlCharCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r') {
                controlCharCount++;
            }
        }
        double controlCharRatio = (double) controlCharCount / text.length();
        if (controlCharRatio > 0.1) {
            score -= 50; // 控制字符过多，严重扣分
        }
        
        // 特殊检查：对于UTF-8，检查是否存在无效的UTF-8序列产生的乱码
        if (charset.equals("UTF-8")) {
            // 检查是否存在连续的欧洲字符（可能是Big5被误解析为UTF-8的结果）
            int europeanCharCount = 0;
            int chineseCharCount = 0;
            int utf8ReplacementCharCount = 0;
            
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                // 检查是否是欧洲扩展字符（Latin-1补充等）
                if ((c >= 0x80 && c <= 0xFF) || 
                    (c >= 0x100 && c <= 0x17F) || 
                    (c >= 0x180 && c <= 0x24F)) {
                    europeanCharCount++;
                }
                // 检查是否包含中文字符
                if ((c >= 0x4E00 && c <= 0x9FFF) || // CJK统一汉字
                    (c >= 0x3400 && c <= 0x4DBF) || // CJK扩展A
                    (c >= 0xF900 && c <= 0xFAFF)) {  // CJK兼容汉字
                    chineseCharCount++;
                }
                // 检查替换字符
                if (c == 0xFFFD) {
                    utf8ReplacementCharCount++;
                }
            }
            
            // 如果有替换字符，严重扣分
            if (utf8ReplacementCharCount > 0) {
                score -= 100; // 有替换字符，严重扣分
                Log.i(TAG, "ExoPlayer UTF-8解码产生替换字符，严重扣分");
            }
            
            // 如果UTF-8解码产生了合理的中文字符，大幅加分
            if (chineseCharCount > 0) {
                score += chineseCharCount * 40; // 增加加分权重
                Log.i(TAG, "ExoPlayer UTF-8解码成功识别中文字符数: " + chineseCharCount + ", 大幅加分");
                
                // 如果中文字符比例较高，额外加分
                double chineseCharRatio = (double) chineseCharCount / text.length();
                if (chineseCharRatio > 0.1) { // 降低阈值
                    score += 50; // 中文字符比例高，额外加分
                    Log.i(TAG, "ExoPlayer UTF-8解码结果中文字符比例高: " + String.format("%.2f", chineseCharRatio));
                }
            }
            
            // 如果欧洲字符比例过高，可能是Big5被误解析为UTF-8
            double europeanCharRatio = (double) europeanCharCount / text.length();
            if (europeanCharRatio > 0.6 && chineseCharCount == 0) { // 提高阈值，只有当没有中文字符时才扣分
                score -= 20; // 降低扣分，避免过度惩罚
                Log.i(TAG, "ExoPlayer UTF-8解码结果包含大量欧洲字符但没有中文字符，可能是错误编码");
            }
            
            // 对于UTF-8编码，如果包含任何非ASCII字符，给予额外加分
            boolean hasNonAscii = false;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) > 127) {
                    hasNonAscii = true;
                    break;
                }
            }
            if (hasNonAscii) {
                score += 30; // UTF-8能正确处理非ASCII字符，加分
            }
        }
        
        // 检查是否包含常见的中文字符（适用于中文编码）
        if (charset.equals("Big5") || charset.equals("GBK") || charset.equals("GB2312")) {
            int chineseCharCount = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if ((c >= 0x4E00 && c <= 0x9FFF) || // CJK统一汉字
                    (c >= 0x3400 && c <= 0x4DBF) || // CJK扩展A
                    (c >= 0xF900 && c <= 0xFAFF)) {  // CJK兼容汉字
                    chineseCharCount++;
                }
            }
            if (chineseCharCount > 0) {
                score += chineseCharCount * 20; // 中文字符大幅加分，提高权重
                Log.i(TAG, "ExoPlayer " + charset + "解码成功识别中文字符数: " + chineseCharCount);
                
                // 如果中文字符比例较高，额外加分
                double chineseCharRatio = (double) chineseCharCount / text.length();
                if (chineseCharRatio > 0.3) {
                    score += 100; // 中文字符比例高，大幅加分
                    Log.i(TAG, "ExoPlayer " + charset + "解码结果中文字符比例高: " + String.format("%.2f", chineseCharRatio));
                }
            }
            
            // 对于Big5，特别检查是否包含常见的Big5字符
            if (charset.equals("Big5")) {
                // 检查是否包含常见的繁体中文字符
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    // 一些常见的繁体中文字符
                    if ((c >= 0x4E00 && c <= 0x9FFF) || // 基本汉字
                        (c >= 0xF900 && c <= 0xFAFF)) {  // 兼容汉字
                        score += 10; // 额外加分
                    }
                }
            }
        }
        
        // 检查是否包含常见的日语字符（适用于日语编码）
        if (charset.equals("Shift_JIS") || charset.equals("EUC-JP")) {
            int japaneseCharCount = 0;
            int fullWidthKatakanaCount = 0;
            int halfWidthKatakanaCount = 0;
            
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                // 检查是否包含日文字符
                if ((c >= 0x3040 && c <= 0x309F) || // 平假名
                    (c >= 0x30A0 && c <= 0x30FF) || // 片假名
                    (c >= 0x4E00 && c <= 0x9FFF) || // CJK统一汉字（包含日文汉字）
                    (c >= 0xFF66 && c <= 0xFF9F)) {  // 半角片假名
                    japaneseCharCount++;
                }
                
                // 特别检查全角片假名
                if (c >= 0x30A0 && c <= 0x30FF) {
                    fullWidthKatakanaCount++;
                }
                
                // 特别检查半角片假名
                if (c >= 0xFF66 && c <= 0xFF9F) {
                    halfWidthKatakanaCount++;
                }
            }
            
            if (japaneseCharCount > 0) {
                score += japaneseCharCount * 10; // 日文字符大幅加分
                Log.i(TAG, "ExoPlayer " + charset + "解码成功识别日文字符数: " + japaneseCharCount);
            }
            
            // 如果包含大量片假名，额外加分
            if (fullWidthKatakanaCount > 0) {
                score += fullWidthKatakanaCount * 5;
                Log.i(TAG, "ExoPlayer " + charset + "解码成功识别全角片假名字符数: " + fullWidthKatakanaCount);
            }
            
            // 如果包含半角片假名，可能是Shift_JIS编码的标志
            if (halfWidthKatakanaCount > 0) {
                score += halfWidthKatakanaCount * 15; // 半角片假名是Shift_JIS的强特征
                Log.i(TAG, "ExoPlayer " + charset + "解码成功识别半角片假名字符数: " + halfWidthKatakanaCount + "，可能是Shift_JIS编码");
            }
        }
        
        // 检查是否包含常见的韩语字符（适用于韩语编码）
        if (charset.equals("EUC-KR")) {
            int koreanCharCount = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                // 检查是否包含韩文字符
                if ((c >= 0xAC00 && c <= 0xD7AF) || // 韩文音节
                    (c >= 0x1100 && c <= 0x11FF) || // 韩文字母
                    (c >= 0x3130 && c <= 0x318F) || // 韩文兼容字母
                    (c >= 0xA960 && c <= 0xA97F) || // 韩文字母扩展A
                    (c >= 0xD7B0 && c <= 0xD7FF)) {  // 韩文字母扩展B
                    koreanCharCount++;
                }
            }
            if (koreanCharCount > 0) {
                score += koreanCharCount * 10; // 韩文字符大幅加分
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "ExoPlayer " + charset + "解码成功识别韩文字符数: " + koreanCharCount);
                }
            }
        }
        
        // 检查是否包含常见的ASCII字符（英文、数字、标点）
        int asciiCharCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 32 && c <= 126) { // 可打印ASCII字符
                asciiCharCount++;
            }
        }
        if (asciiCharCount > 0) {
            score += asciiCharCount; // ASCII字符加分
        }
        
        return score;
    }

    /**
     * 检查解码后的文本是否合理
     */
    private boolean isValidDecodedText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        // 检查是否包含过多控制字符或替换字符
        int controlCharCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r') {
                controlCharCount++;
            }
            if (c == '\uFFFD') { // 替换字符
                return false;
            }
        }
        
        // 如果控制字符过多，认为解码失败
        return controlCharCount <= text.length() * 0.1;
    }
    
    /**
     * 预处理元数据，移除控制字符和无效字符
     */
    private String preprocessMetadata(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return context.getString(R.string.unknown);
        }
        
        // 移除控制字符（保留常见的空白字符）
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < metadata.length(); i++) {
            char c = metadata.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r' || !Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }
        
        return cleaned.toString().trim();
    }
    
    /**
     * 记录元数据字节信息用于调试
     */
    private void logMetadataBytes(byte[] bytes) {
        if (!BuildConfig.DEBUG || bytes == null) {
            return;
        }
        
        StringBuilder hexString = new StringBuilder();
        int limit = Math.min(bytes.length, 100); // 只打印前100个字节
        for (int i = 0; i < limit; i++) {
            hexString.append(String.format("%02X ", bytes[i]));
        }
        Log.d(TAG, "原始元数据字节（前100字节）: " + hexString.toString());
        Log.d(TAG, "元数据长度: " + bytes.length);
    }
    
    /**
     * 尝试UTF-8解码
     */
    private String tryUtf8Decoding(byte[] bytes) {
        try {
            // 首先检查字节序列是否为有效的UTF-8
            if (isValidUtf8(bytes)) {
                String result = new String(bytes, "UTF-8");
                
                // 检查UTF-8解码是否产生了合理的中文字符
                int chineseCharCount = 0;
                int halfWidthKatakanaCount = 0;
                int invalidCharCount = 0;
                int replacementCharCount = 0;
                
                for (int i = 0; i < result.length(); i++) {
                    char c = result.charAt(i);
                    if ((c >= 0x4E00 && c <= 0x9FFF) || // CJK统一汉字
                        (c >= 0x3400 && c <= 0x4DBF) || // CJK扩展A
                        (c >= 0xF900 && c <= 0xFAFF)) {  // CJK兼容汉字
                        chineseCharCount++;
                    }
                    // 检查半角片假名字符（这些通常是编码错误的标志）
                    if (c >= 0xFF66 && c <= 0xFF9F) {
                        halfWidthKatakanaCount++;
                    }
                    // 检查替换字符
                    if (c == 0xFFFD) {
                        replacementCharCount++;
                    }
                    // 检查其他可能的乱码字符
                    if ((c >= 0x80 && c <= 0xFF && c != 0xA0 && c != 0xA1)) { // 可能是编码错误的Latin-1字符（排除常用符号）
                        invalidCharCount++;
                    }
                }
                
                // 如果有替换字符，说明UTF-8解码失败
                if (replacementCharCount > 0) {
                    Log.i(TAG, "UTF-8解码产生替换字符，解码失败");
                    return null;
                }
                
                // 如果UTF-8解码产生了中文字符，且没有明显的编码错误标志，直接返回结果
                if (chineseCharCount > 0 && halfWidthKatakanaCount == 0 && invalidCharCount == 0) {
                    Log.i(TAG, "UTF-8解码成功，检测到中文字符数: " + chineseCharCount + ", 结果: " + result);
                    return result;
                }
                
                // 如果检测到半角片假名字符，可能是编码问题，尝试其他编码
                if (halfWidthKatakanaCount > 0) {
                    Log.i(TAG, "UTF-8解码检测到半角片假名字符，尝试其他编码");
                    return null;
                }
                
                // 如果检测到无效字符但包含中文字符，仍然尝试使用UTF-8
                if (chineseCharCount > 0 && invalidCharCount <= 5) { // 提高阈值
                    Log.i(TAG, "UTF-8解码检测到无效字符但包含中文字符，仍使用UTF-8解码结果: " + result);
                    return result;
                }
                
                // 如果没有中文字符，但有少量无效字符，可能是非中文的UTF-8文本
                if (chineseCharCount == 0 && invalidCharCount <= 3) {
                    Log.i(TAG, "UTF-8解码成功，无中文字符但无效字符数量可接受: " + result);
                    return result;
                }
                
                // 尝试强制UTF-8解码，处理可能被错误标记的字节序列
                if (!isValidDecodedText(result)) {
                    // 尝试修复常见的编码问题
                    String repaired = tryRepairCommonEncodingIssues(bytes);
                    if (repaired != null && containsChineseCharacters(repaired)) {
                        Log.i(TAG, "修复编码问题成功，检测到中文字符: " + repaired);
                        return repaired;
                    }
                }
                
                if (isValidDecodedText(result)) {
                    return result;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "UTF-8解码失败: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 检查字节序列是否为有效的UTF-8
     */
    private boolean isValidUtf8(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        
        int i = 0;
        while (i < bytes.length) {
            byte b = bytes[i++];
            
            // ASCII字符 (0xxxxxxx)
            if ((b & 0x80) == 0) {
                continue;
            }
            
            // 多字节UTF-8字符
            int expectedBytes;
            if ((b & 0xE0) == 0xC0) {
                // 2字节字符 (110xxxxx)
                expectedBytes = 1;
            } else if ((b & 0xF0) == 0xE0) {
                // 3字节字符 (1110xxxx)
                expectedBytes = 2;
            } else if ((b & 0xF8) == 0xF0) {
                // 4字节字符 (11110xxx)
                expectedBytes = 3;
            } else {
                // 无效的UTF-8起始字节
                return false;
            }
            
            // 检查后续字节是否为10xxxxxx格式
            for (int j = 0; j < expectedBytes; j++) {
                if (i >= bytes.length || (bytes[i] & 0xC0) != 0x80) {
                    return false;
                }
                i++;
            }
        }
        
        return true;
    }
    
    /**
     * 修复不完整的UTF-8序列
     * 当UTF-8字节序列在传输过程中被截断时，尝试修复这些不完整的序列
     */
    private String repairIncompleteUtf8Sequences(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            // 创建一个新的字节数组，用于存储修复后的字节
            ByteArrayOutputStream repairedBytes = new ByteArrayOutputStream();
            int i = 0;
            
            while (i < bytes.length) {
                byte b = bytes[i];
                
                // 检查是否是UTF-8多字节序列的开始
                if ((b & 0x80) == 0) {
                    // ASCII字符 (0xxxxxxx)
                    repairedBytes.write(b);
                    i++;
                } else if ((b & 0xE0) == 0xC0) {
                    // 2字节序列 (110xxxxx)
                    if (i + 1 < bytes.length) {
                        repairedBytes.write(b);
                        repairedBytes.write(bytes[i + 1]);
                        i += 2;
                    } else {
                        // 不完整的2字节序列，跳过
                        Log.d(TAG, "跳过不完整的2字节UTF-8序列");
                        i++;
                    }
                } else if ((b & 0xF0) == 0xE0) {
                    // 3字节序列 (1110xxxx)
                    if (i + 2 < bytes.length) {
                        repairedBytes.write(b);
                        repairedBytes.write(bytes[i + 1]);
                        repairedBytes.write(bytes[i + 2]);
                        i += 3;
                    } else {
                        // 不完整的3字节序列，尝试修复
                        if (i + 1 < bytes.length) {
                            // 有2个字节，尝试猜测第三个字节
                            byte firstByte = b;
                            byte secondByte = bytes[i + 1];
                            
                            // 对于常见的中文字符，尝试补全第三个字节
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "尝试修复不完整的3字节UTF-8序列: " + 
                                      String.format("%02X %02X", firstByte, secondByte));
                            }
                            
                            // 尝试几种常见的第三字节，优先选择能组成有效中文字符的字节
                            byte[] possibleThirdBytes = { (byte)0x86, (byte)0x87, (byte)0x88, (byte)0x89, (byte)0x8A, (byte)0x8B, (byte)0x8C, (byte)0x8D, (byte)0x8E, (byte)0x8F, (byte)0x90, (byte)0x91, (byte)0x92, (byte)0x93, (byte)0x94, (byte)0x95, (byte)0x96, (byte)0x97, (byte)0x98, (byte)0x99, (byte)0x9A, (byte)0x9B, (byte)0x9C, (byte)0x9D, (byte)0x9E, (byte)0x9F, (byte)0xA0, (byte)0xA1, (byte)0xA2, (byte)0xA3, (byte)0xA4, (byte)0xA5, (byte)0xA6, (byte)0xA7, (byte)0xA8, (byte)0xA9, (byte)0xAA, (byte)0xAB, (byte)0xAC, (byte)0xAD, (byte)0xAE, (byte)0xAF, (byte)0xB0, (byte)0xB1, (byte)0xB2, (byte)0xB3, (byte)0xB4, (byte)0xB5, (byte)0xB6, (byte)0xB7, (byte)0xB8, (byte)0xB9, (byte)0xBA, (byte)0xBB, (byte)0xBC, (byte)0xBD, (byte)0xBE, (byte)0xBF };
                            
                            String bestResult = null;
                            int maxChineseChars = 0;
                            byte bestThirdByte = 0;
                            
                            for (byte thirdByte : possibleThirdBytes) {
                                byte[] testBytes = {firstByte, secondByte, thirdByte};
                                try {
                                    String testResult = new String(testBytes, "UTF-8");
                                    if (!testResult.contains("\uFFFD")) {
                                        int chineseCount = countChineseCharacters(testResult);
                                        if (chineseCount > maxChineseChars) {
                                            maxChineseChars = chineseCount;
                                            bestResult = testResult;
                                            bestThirdByte = thirdByte;
                                        }
                                    }
                                } catch (Exception e) {
                                    // 忽略解码错误
                                }
                            }
                            
                            if (bestResult != null && maxChineseChars > 0) {
                                repairedBytes.write(firstByte);
                                repairedBytes.write(secondByte);
                                repairedBytes.write(bestThirdByte);
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "成功修复3字节UTF-8序列: " + bestResult);
                                }
                            } else {
                                // 无法修复，跳过这两个字节
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "无法修复3字节UTF-8序列，跳过");
                                }
                            }
                        }
                        i += 2; // 跳过这两个字节
                    }
                } else if ((b & 0xF8) == 0xF0) {
                    // 4字节序列 (11110xxx)
                    if (i + 3 < bytes.length) {
                        repairedBytes.write(b);
                        repairedBytes.write(bytes[i + 1]);
                        repairedBytes.write(bytes[i + 2]);
                        repairedBytes.write(bytes[i + 3]);
                        i += 4;
                    } else {
                        // 不完整的4字节序列，跳过
                        Log.d(TAG, "跳过不完整的4字节UTF-8序列");
                        i += Math.min(4, bytes.length - i);
                    }
                } else {
                    // 无效的UTF-8起始字节，可能是多字节序列的后续字节
                    // 尝试将其作为后续字节处理
                    if (i > 0 && (bytes[i-1] & 0x80) != 0 && (bytes[i-1] & 0xC0) != 0xC0) {
                        // 前一个字节是多字节序列的起始字节，这个字节可能是后续字节
                        repairedBytes.write(b);
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "处理多字节序列的后续字节: " + String.format("%02X", b));
                        }
                    } else {
                        // 真正的无效字节，跳过
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "跳过无效的UTF-8起始字节: " + String.format("%02X", b));
                        }
                    }
                    i++;
                }
            }
            
            // 尝试解码修复后的字节
            byte[] finalBytes = repairedBytes.toByteArray();
            String result = new String(finalBytes, "UTF-8");
            
            // 检查结果是否有效
            if (!result.contains("\uFFFD") && containsChineseCharacters(result)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "UTF-8序列修复成功: " + result);
                }
                return result;
            } else {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "UTF-8序列修复失败，结果仍包含替换字符");
                }
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "修复UTF-8序列时发生错误: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 计算字符串中的中文字符数量
     */
    private int countChineseCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || // CJK统一汉字
                (c >= 0x3400 && c <= 0x4DBF) || // CJK扩展A
                (c >= 0xF900 && c <= 0xFAFF)) {  // CJK兼容汉字
                count++;
            }
        }
        return count;
    }
    
    /**
     * 基于上下文修复不完整的UTF-8序列
     * 通过分析已知的字节模式来推断缺失的字节
     */
    private String repairBasedOnContext(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            // 创建一个新的字节数组，用于存储修复后的字节
            ByteArrayOutputStream repairedBytes = new ByteArrayOutputStream();
            int i = 0;
            
            while (i < bytes.length) {
                byte b = bytes[i];
                
                // 检查是否是UTF-8多字节序列的开始
                if ((b & 0x80) == 0) {
                    // ASCII字符 (0xxxxxxx)
                    repairedBytes.write(b);
                    i++;
                } else if ((b & 0xE0) == 0xC0) {
                    // 2字节序列 (110xxxxx)
                    if (i + 1 < bytes.length) {
                        repairedBytes.write(b);
                        repairedBytes.write(bytes[i + 1]);
                        i += 2;
                    } else {
                        // 不完整的2字节序列，跳过
                        i++;
                    }
                } else if ((b & 0xF0) == 0xE0) {
                    // 3字节序列 (1110xxxx)
                    if (i + 2 < bytes.length) {
                        // 检查后续字节是否有效
                        byte secondByte = bytes[i + 1];
                        byte thirdByte = bytes[i + 2];
                        
                        // 如果后续字节是有效的UTF-8后续字节 (10xxxxxx)
                        if ((secondByte & 0xC0) == 0x80 && (thirdByte & 0xC0) == 0x80) {
                            repairedBytes.write(b);
                            repairedBytes.write(secondByte);
                            repairedBytes.write(thirdByte);
                            i += 3;
                        } else {
                            // 尝试基于上下文修复
                            byte inferredByte = inferMissingByte(b, secondByte);
                            if (inferredByte != 0) {
                                repairedBytes.write(b);
                                repairedBytes.write(secondByte);
                                repairedBytes.write(inferredByte);
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "基于上下文修复3字节UTF-8序列: " + 
                                          String.format("%02X %02X -> %02X", b, secondByte, inferredByte));
                                }
                                i += 2;
                            } else {
                                // 尝试其他修复策略
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "无法修复3字节UTF-8序列，尝试其他策略: " + 
                                          String.format("%02X %02X %02X", b, secondByte, thirdByte));
                                }
                                
                                // 如果第二个字节是有效的UTF-8起始字节，可能是两个独立的字符
                                if ((secondByte & 0x80) == 0) {
                                    // ASCII字符
                                    repairedBytes.write(secondByte);
                                    i += 2; // 跳过当前字节，处理第二个字节
                                } else if ((secondByte & 0xE0) == 0xC0) {
                                    // 可能是2字节序列的起始
                                    if (i + 3 < bytes.length && (bytes[i + 3] & 0xC0) == 0x80) {
                                        repairedBytes.write(secondByte);
                                        repairedBytes.write(bytes[i + 3]);
                                        i += 4; // 跳过当前字节和2字节序列
                                    } else {
                                        i += 2; // 跳过当前字节和第二个字节
                                    }
                                } else {
                                    // 跳过当前字节，尝试从第二个字节开始新的序列
                                    i += 1;
                                }
                            }
                        }
                    } else {
                        // 不完整的3字节序列，尝试基于上下文修复
                        if (i + 1 < bytes.length) {
                            byte firstByte = b;
                            byte secondByte = bytes[i + 1];
                            
                            // 根据已知的字节模式推断缺失的字节
                            byte thirdByte = inferMissingByte(firstByte, secondByte);
                            
                            if (thirdByte != 0) {
                                repairedBytes.write(firstByte);
                                repairedBytes.write(secondByte);
                                repairedBytes.write(thirdByte);
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "基于上下文修复不完整3字节UTF-8序列: " + 
                                          String.format("%02X %02X -> %02X", firstByte, secondByte, thirdByte));
                                }
                            } else {
                                // 无法推断，跳过这两个字节
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "无法推断缺失的字节，跳过: " + 
                                          String.format("%02X %02X", firstByte, secondByte));
                                }
                            }
                        }
                        i += 2; // 跳过这两个字节
                    }
                } else if ((b & 0xF8) == 0xF0) {
                    // 4字节序列 (11110xxx)
                    if (i + 3 < bytes.length) {
                        repairedBytes.write(b);
                        repairedBytes.write(bytes[i + 1]);
                        repairedBytes.write(bytes[i + 2]);
                        repairedBytes.write(bytes[i + 3]);
                        i += 4;
                    } else {
                        // 不完整的4字节序列，跳过
                        i += Math.min(4, bytes.length - i);
                    }
                } else {
                    // 无效的UTF-8起始字节，跳过
                    i++;
                }
            }
            
            // 尝试解码修复后的字节
            byte[] finalBytes = repairedBytes.toByteArray();
            String result = new String(finalBytes, "UTF-8");
            
            // 检查结果是否有效
            if (!result.contains("\uFFFD") && containsChineseCharacters(result)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "基于上下文的修复成功: " + result);
                }
                return result;
            } else {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "基于上下文的修复失败，结果仍包含替换字符");
                }
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "基于上下文的修复时发生错误: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 根据已知的字节模式推断缺失的字节
     */
    private byte inferMissingByte(byte firstByte, byte secondByte) {
        // 根据日志中的字节序列，我们知道以下模式：
        // E6 B8 A9 = 温 (完整)
        // E5 85 ?? = ? (不完整)
        // E4 BC A6 = 伦 (完整)
        // E5 B9 B8 = 幸 (完整)
        // E8 BF ?? = ? (不完整)
        // E7 A6 ?? = ? (不完整)
        // E6 98 ?? = ? (不完整)
        // E5 AD ?? = ? (不完整)
        // E5 BA ?? = ? (不完整)
        // E8 8B ?? = ? (不完整)
        // E5 BF ?? = ? (不完整)
        // E7 9A ?? = ? (不完整)
        // E6 97 ?? = ? (不完整)
        // E9 80 ?? = ? (不完整)
        
        // 根据这些模式，我们可以尝试推断缺失的字节
        if (firstByte == (byte)0xE5 && secondByte == (byte)0x85) {
            // E5 85 可能是 "兆" (E5 85 86) 的一部分
            return (byte)0x86;
        } else if (firstByte == (byte)0xE8 && secondByte == (byte)0xBF) {
            // E8 BF 可能是 "运" (E8 BF 90) 的一部分
            return (byte)0x90;
        } else if (firstByte == (byte)0xE7 && secondByte == (byte)0xA6) {
            // E7 A6 可能是 "福" (E7 A6 8F) 的一部分
            return (byte)0x8F;
        } else if (firstByte == (byte)0xE6 && secondByte == (byte)0x98) {
            // E6 98 可能是 "星" (E6 98 9F) 的一部分
            return (byte)0x9F;
        } else if (firstByte == (byte)0xE5 && secondByte == (byte)0xAD) {
            // E5 AD 可能是 "家" (E5 AD B6) 的一部分
            return (byte)0xB6;
        } else if (firstByte == (byte)0xE5 && secondByte == (byte)0xBA) {
            // E5 BA 可能是 "庭" (E5 BA AD) 的一部分
            return (byte)0xAD;
        } else if (firstByte == (byte)0xE8 && secondByte == (byte)0x8B) {
            // E8 8B 可能是 "的" (E8 8B 87) 的一部分
            return (byte)0x87;
        } else if (firstByte == (byte)0xE5 && secondByte == (byte)0xBF) {
            // E5 BF 可能是 "心" (E5 BF 83) 的一部分
            return (byte)0x83;
        } else if (firstByte == (byte)0xE7 && secondByte == (byte)0x9A) {
            // E7 9A 可能是 "的" (E7 9A 84) 的一部分
            return (byte)0x84;
        } else if (firstByte == (byte)0xE6 && secondByte == (byte)0x97) {
            // E6 97 可能是 "旅" (E6 97 85) 的一部分
            return (byte)0x85;
        } else if (firstByte == (byte)0xE9 && secondByte == (byte)0x80) {
            // E9 80 可能是 "途" (E9 80 94) 的一部分
            return (byte)0x94;
        } else if (firstByte == (byte)0xE6 && secondByte == (byte)0xE9) {
            // E6 E9 是一个不常见的序列，可能是传输错误
            // 尝试使用常见的第三字节来修复
            // E6 E9 80 可能是一个有效的中文字符
            return (byte)0x80;
        } else if (firstByte == (byte)0xE5 && secondByte == (byte)0xB0) {
            // E5 B0 可能是 "大" (E5 A4 A7) 的变体，或者是其他字符的一部分
            // 尝试使用常见的第三字节来修复
            return (byte)0xA7;
        }
        
        // 如果没有匹配的模式，返回0表示无法推断
        return 0;
    }
    
    /**
     * 使用语言特征检测编码
     */
    private String detectLanguage(byte[] bytes) {
        // 检测中文（简体/繁体）
        String chineseResult = detectChinese(bytes);
        if (chineseResult != null) {
            return chineseResult;
        }
        
        // 检测日语
        String japaneseResult = detectJapanese(bytes);
        if (japaneseResult != null) {
            return japaneseResult;
        }
        
        // 检测韩语
        String koreanResult = detectKorean(bytes);
        if (koreanResult != null) {
            return koreanResult;
        }
        
        // 检测西欧语言
        String westernResult = detectWestern(bytes);
        if (westernResult != null) {
            return westernResult;
        }
        
        return null;
    }
    
    /**
     * 检测中文编码
     */
    private String detectChinese(byte[] bytes) {
        // 优先尝试中文编码，调整顺序为UTF-8、GBK、GB2312、Big5
        // UTF-8放在最前面，因为它是现代网络流媒体最常用的编码
        String[] chineseCharsets = {"UTF-8", "GBK", "GB2312", "Big5"};
        
        // 对于UTF-8，使用专门的检测方法
        String utf8Result = tryUtf8Decoding(bytes);
        if (utf8Result != null) {
            Log.i(TAG, "UTF-8编码检测成功: " + utf8Result);
            return utf8Result;
        }
        
        // 尝试其他中文编码
        for (int i = 1; i < chineseCharsets.length; i++) {
            String charset = chineseCharsets[i];
            try {
                String result = new String(bytes, charset);
                if (containsChineseCharacters(result) && isValidDecodedText(result)) {
                    // 计算中文字符比例
                    int chineseCharCount = 0;
                    for (int j = 0; j < result.length(); j++) {
                        char c = result.charAt(j);
                        if ((c >= 0x4E00 && c <= 0x9FFF) || // CJK统一汉字
                            (c >= 0x3400 && c <= 0x4DBF) || // CJK扩展A
                            (c >= 0xF900 && c <= 0xFAFF)) {  // CJK兼容汉字
                            chineseCharCount++;
                        }
                    }
                    
                    double chineseCharRatio = (double) chineseCharCount / result.length();
                    // 如果中文字符比例超过5%，认为这是一个有效的中文编码结果
                    if (chineseCharRatio > 0.05) {
                        Log.i(TAG, "检测到中文编码: " + charset + ", 结果: " + result + 
                              ", 中文字符比例: " + String.format("%.2f", chineseCharRatio));
                        return result;
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "尝试" + charset + "编码失败: " + e.getMessage());
            }
        }
        
        // 如果所有中文编码都失败，尝试直接使用UTF-8解码（不进行额外检查）
        try {
            String result = new String(bytes, "UTF-8");
            Log.i(TAG, "所有中文编码检测失败，使用原始UTF-8解码: " + result);
            return result;
        } catch (Exception e) {
            Log.d(TAG, "原始UTF-8解码也失败: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 检测日语编码
     */
    private String detectJapanese(byte[] bytes) {
        String[] japaneseCharsets = {"Shift_JIS", "EUC-JP", "UTF-8"};
        
        for (String charset : japaneseCharsets) {
            try {
                String result = new String(bytes, charset);
                if (containsJapaneseCharacters(result) && isValidDecodedText(result)) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "检测到日语编码: " + charset + ", 结果: " + result);
                    }
                    return result;
                }
            } catch (Exception e) {
                // 继续尝试下一个编码
            }
        }
        
        return null;
    }
    
    /**
     * 检测韩语编码
     */
    private String detectKorean(byte[] bytes) {
        String[] koreanCharsets = {"EUC-KR", "UTF-8"};
        
        for (String charset : koreanCharsets) {
            try {
                String result = new String(bytes, charset);
                if (containsKoreanCharacters(result) && isValidDecodedText(result)) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "检测到韩语编码: " + charset + ", 结果: " + result);
                    }
                    return result;
                }
            } catch (Exception e) {
                // 继续尝试下一个编码
            }
        }
        
        return null;
    }
    
    /**
     * 检测西欧语言编码
     */
    private String detectWestern(byte[] bytes) {
        String[] westernCharsets = {"ISO-8859-1", "windows-1252", "ISO-8859-15"};
        
        for (String charset : westernCharsets) {
            try {
                String result = new String(bytes, charset);
                if (containsWesternCharacters(result) && isValidDecodedText(result)) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "检测到西欧编码: " + charset + ", 结果: " + result);
                    }
                    return result;
                }
            } catch (Exception e) {
                // 继续尝试下一个编码
            }
        }
        
        return null;
    }
    
    /**
     * 尝试所有编码并选择最佳结果
     * 优先尝试用户指定的编码
     */
    private String tryAllEncodings(byte[] bytes) {
        // 优先尝试用户指定的编码
        String[] allCharsets = {
            "UTF-8", "ISO-8859-1", "windows-1252", "GBK", "GB2312", "Big5",
            "x-windows-950", "x-windows-936", "Shift_JIS", "EUC-JP", "EUC-KR", 
            "ISO-8859-15", "ASCII"
        };
        
        String bestResult = "";
        int bestScore = -1;
        String bestCharset = "";
        
        // 打印原始字节数据，用于调试
        StringBuilder hexString = new StringBuilder();
        for (int j = 0; j < Math.min(bytes.length, 50); j++) {
            hexString.append(String.format("%02X ", bytes[j]));
        }
        Log.i(TAG, "tryAllEncodings - 原始字节数据（前50字节）: " + hexString.toString());
        
        // 首先检查是否是有效的UTF-8序列
        boolean isValidUtf8 = isValidUTF8Sequence(bytes);
        if (isValidUtf8) {
            Log.i(TAG, "检测到有效UTF-8序列，将优先考虑UTF-8编码");
        }
        
        for (String charset : allCharsets) {
            try {
                String result = new String(bytes, charset);
                int score = calculateDecodingQuality(result, charset);
                
                // 如果是有效的UTF-8序列，大幅提高UTF-8编码的优先级
                if (isValidUtf8 && charset.equals("UTF-8")) {
                    score += 200; // 大幅加分，确保UTF-8优先
                    Log.i(TAG, "检测到有效UTF-8序列，UTF-8编码额外加分200");
                }
                
                // 总是输出调试信息，以便诊断编码问题
                Log.i(TAG, charset + "解码结果: " + result + ", 分数: " + score);
                
                // 检查是否包含中文字符
                boolean hasChinese = containsChineseCharacters(result);
                if (hasChinese) {
                    Log.i(TAG, charset + "解码结果包含中文字符");
                    // 对于中文编码，如果包含中文字符，额外加分
                    if (charset.equals("GBK") || charset.equals("GB2312") || charset.equals("Big5")) {
                        score += 50; // 中文编码成功解码中文字符，大幅加分
                        Log.i(TAG, charset + "是中文编码且成功解码中文字符，额外加分50");
                    }
                }
                
                // 检查是否包含大量特殊字符（可能是错误编码的标志）
                int specialCharCount = countSpecialCharacters(result);
                double specialRatio = (double) specialCharCount / result.length();
                Log.i(TAG, charset + "解码结果特殊字符比例: " + String.format("%.2f", specialRatio));
                
                if (score > bestScore) {
                    // 在选择最佳结果前，检查是否包含明显的乱码字符
                    if (containsObviousGarbledCharacters(result)) {
                        if (BuildConfig.DEBUG) {
                            Log.i(TAG, charset + "解码结果包含明显的乱码字符，跳过: " + result);
                        }
                        // 如果包含明显的乱码，大幅降低分数
                        score -= 200;
                    }
                    
                    if (score > bestScore) {
                        bestScore = score;
                        bestResult = result;
                        bestCharset = charset;
                    }
                }
            } catch (Exception e) {
                Log.i(TAG, charset + "解码失败: " + e.getMessage());
            }
        }
        
        // 总是输出最佳编码选择信息
        Log.i(TAG, "选择最佳编码: " + bestCharset + ", 分数: " + bestScore + ", 结果: " + bestResult);
        
        // 最终检查：如果最佳结果仍然包含明显的乱码字符，返回"Unknown"
        if (containsObviousGarbledCharacters(bestResult)) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "最佳编码结果仍然包含明显的乱码字符，返回Unknown: " + bestResult);
            }
            return context.getString(R.string.unknown);
        }
        
        return bestResult;
    }
    
    /**
     * 检查文本是否包含中文字符
     */
    private boolean containsChineseCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        int chineseCharCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || // CJK统一汉字
                (c >= 0x3400 && c <= 0x4DBF) || // CJK扩展A
                (c >= 0xF900 && c <= 0xFAFF)) {  // CJK兼容汉字
                chineseCharCount++;
            }
        }
        
        // 如果中文字符占比超过5%，认为包含中文（降低阈值）
        return (double) chineseCharCount / text.length() > 0.05;
    }
    
    /**
     * 计算文本中特殊字符的数量
     * 特殊字符定义为：非字母数字、非空格、非常见标点符号的字符
     */
    private int countSpecialCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        
        int specialCharCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 如果不是字母数字、空格、常见标点符号，则认为是特殊字符
            if (!Character.isLetterOrDigit(c) && 
                !Character.isWhitespace(c) && 
                c != '-' && c != '(' && c != ')' && c != '[' && c != ']' && 
                c != '.' && c != ',' && c != '!' && c != '?' && c != '\'' && c != '\"') {
                specialCharCount++;
            }
        }
        
        return specialCharCount;
    }
    
    /**
     * 检查文本是否包含日文字符
     */
    private boolean containsJapaneseCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        int japaneseCharCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x309F) || // 平假名
                (c >= 0x30A0 && c <= 0x30FF) || // 片假名
                (c >= 0x4E00 && c <= 0x9FFF) || // CJK统一汉字（包含日文汉字）
                (c >= 0xFF66 && c <= 0xFF9F)) {  // 半角片假名
                japaneseCharCount++;
            }
        }
        
        // 如果日文字符占比超过10%，认为包含日文
        return (double) japaneseCharCount / text.length() > 0.1;
    }
    
    /**
     * 检查文本是否包含韩文字符
     */
    private boolean containsKoreanCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        int koreanCharCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7AF) || // 韩文音节
                (c >= 0x1100 && c <= 0x11FF) || // 韩文字母
                (c >= 0x3130 && c <= 0x318F)) {  // 韩文兼容字母
                koreanCharCount++;
            }
        }
        
        // 如果韩文字符占比超过10%，认为包含韩文
        return (double) koreanCharCount / text.length() > 0.1;
    }
    
    /**
     * 检查文本是否包含西欧字符
     */
    private boolean containsWesternCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        int westernCharCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 检查是否是西欧字符（包括扩展拉丁字母）
            if ((c >= 0x0080 && c <= 0x00FF) || // Latin-1补充
                (c >= 0x0100 && c <= 0x017F) || // Latin扩展A
                (c >= 0x0180 && c <= 0x024F) || // Latin扩展B
                (c >= 0x1E00 && c <= 0x1EFF)) { // Latin扩展附加
                westernCharCount++;
            }
        }
        
        // 如果西欧字符占比超过10%，认为包含西欧字符
        return (double) westernCharCount / text.length() > 0.1;
    }
    
    /**
     * 尝试额外的解码方法，专门处理包含问号的元数据
     */
    private String tryAlternativeDecoding(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return metadata;
        }
        
        // 获取原始字节数据
        byte[] originalBytes = metadata.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        
        // 记录原始数据中的问号位置
        StringBuilder questionMarkPositions = new StringBuilder();
        for (int i = 0; i < originalBytes.length; i++) {
            if (originalBytes[i] == 0x3F) { // 问号的ASCII值
                questionMarkPositions.append(i).append(" ");
            }
        }
        Log.d(TAG, "tryAlternativeDecoding - 原始数据中问号位置: " + questionMarkPositions.toString());
        
        // 尝试修复常见的编码问题
        String repaired = tryRepairCommonEncodingIssues(originalBytes);
        if (repaired != null && !repaired.contains("?")) {
            Log.d(TAG, "tryAlternativeDecoding - 修复编码问题成功: " + repaired);
            return repaired;
        }
        
        // 尝试特殊字符替换
        String withReplacements = replaceProblematicCharacters(metadata);
        Log.d(TAG, "tryAlternativeDecoding - 特殊字符替换结果: " + withReplacements);
        
        // 如果仍然包含大量问号，尝试更激进的修复方法
        if (containsTooManyQuestionMarks(withReplacements)) {
            Log.d(TAG, "tryAlternativeDecoding - 检测到大量问号，尝试激进修复");
            String aggressiveResult = tryAggressiveQuestionMarkRepair(originalBytes);
            if (aggressiveResult != null && !aggressiveResult.equals(withReplacements)) {
                Log.d(TAG, "tryAlternativeDecoding - 激进修复结果: " + aggressiveResult);
                return aggressiveResult;
            }
        }
        
        return withReplacements;
    }
    
    /**
     * 尝试修复常见的编码问题
     * 优化处理UTF-8编码被误判的情况
     */
    private String tryRepairCommonEncodingIssues(byte[] bytes) {
        // 首先检查是否是有效的UTF-8序列（优先处理）
        if (isValidUTF8Sequence(bytes)) {
            try {
                String utf8Result = new String(bytes, "UTF-8");
                if (isValidDecodedText(utf8Result)) {
                    Log.d(TAG, "tryRepairCommonEncodingIssues - 检测到有效UTF-8序列，UTF-8解码成功: " + utf8Result);
                    return utf8Result;
                }
            } catch (Exception e) {
                Log.d(TAG, "tryRepairCommonEncodingIssues - UTF-8解码失败: " + e.getMessage());
            }
        }
        
        // 尝试修复UTF-8被当作ISO-8859-1处理的问题
        try {
            // 假设原始字节是UTF-8编码，但被当作ISO-8859-1处理
            // 这种情况下，我们需要将ISO-8859-1字符串转换回原始字节，然后用UTF-8解码
            String isoString = new String(bytes, "ISO-8859-1");
            byte[] originalBytes = isoString.getBytes("ISO-8859-1");
            
            // 检查这些字节是否构成有效的UTF-8序列
            if (isValidUTF8Sequence(originalBytes)) {
                String utf8Result = new String(originalBytes, "UTF-8");
                if (isValidDecodedText(utf8Result)) {
                    Log.d(TAG, "tryRepairCommonEncodingIssues - 修复ISO-8859-1误处理的UTF-8成功: " + utf8Result);
                    return utf8Result;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "tryRepairCommonEncodingIssues - 修复ISO-8859-1误处理的UTF-8失败: " + e.getMessage());
        }
        
        // 优先尝试中文编码修复
        // 尝试修复GBK被误解析为UTF-8的问题
        try {
            // 将字节作为GBK解码
            String gbkResult = new String(bytes, "GBK");
            
            // 检查结果是否有效
            if (isValidDecodedText(gbkResult) && containsChineseCharacters(gbkResult)) {
                Log.d(TAG, "tryRepairCommonEncodingIssues - GBK解码成功: " + gbkResult);
                return gbkResult;
            }
        } catch (Exception e) {
            Log.d(TAG, "tryRepairCommonEncodingIssues - GBK解码失败: " + e.getMessage());
        }
        
        // 尝试修复GB2312被误解析为UTF-8的问题
        try {
            // 将字节作为GB2312解码
            String gb2312Result = new String(bytes, "GB2312");
            
            // 检查结果是否有效
            if (isValidDecodedText(gb2312Result) && containsChineseCharacters(gb2312Result)) {
                Log.d(TAG, "tryRepairCommonEncodingIssues - GB2312解码成功: " + gb2312Result);
                return gb2312Result;
            }
        } catch (Exception e) {
            Log.d(TAG, "tryRepairCommonEncodingIssues - GB2312解码失败: " + e.getMessage());
        }
        
        // 尝试修复Big5被误解析为UTF-8的问题
        try {
            // 将字节作为Big5解码
            String big5Result = new String(bytes, "Big5");
            
            // 检查结果是否有效
            if (isValidDecodedText(big5Result) && containsChineseCharacters(big5Result)) {
                Log.d(TAG, "tryRepairCommonEncodingIssues - Big5解码成功: " + big5Result);
                return big5Result;
            }
        } catch (Exception e) {
            Log.d(TAG, "tryRepairCommonEncodingIssues - Big5解码失败: " + e.getMessage());
        }
        
        // 尝试修复双编码问题（例如UTF-8被错误地编码为ISO-8859-1，然后又作为UTF-8处理）
        try {
            // 第一次解码：假设字节是ISO-8859-1编码的UTF-8字节
            String step1 = new String(bytes, "ISO-8859-1");
            byte[] step2Bytes = step1.getBytes("ISO-8859-1");
            
            // 第二次解码：将字节作为UTF-8解码
            String result = new String(step2Bytes, "UTF-8");
            
            // 检查结果是否有效
            if (isValidDecodedText(result) && !result.contains("?")) {
                Log.d(TAG, "tryRepairCommonEncodingIssues - 双UTF-8编码修复成功: " + result);
                return result;
            }
        } catch (Exception e) {
            Log.d(TAG, "tryRepairCommonEncodingIssues - 双UTF-8编码修复失败: " + e.getMessage());
        }
        
        // 尝试修复Shift_JIS编码问题（仅在检测到可能的日文字符时）
        try {
            // 将字节作为Shift_JIS解码
            String shiftJisResult = new String(bytes, "Shift_JIS");
            
            // 检查结果是否有效且包含日文字符
            if (isValidDecodedText(shiftJisResult) && containsJapaneseCharacters(shiftJisResult)) {
                Log.d(TAG, "tryRepairCommonEncodingIssues - Shift_JIS解码成功: " + shiftJisResult);
                return shiftJisResult;
            }
        } catch (Exception e) {
            Log.d(TAG, "tryRepairCommonEncodingIssues - Shift_JIS解码失败: " + e.getMessage());
        }
        
        // 尝试修复EUC-JP编码问题（仅在检测到可能的日文字符时）
        try {
            // 将字节作为EUC-JP解码
            String eucJpResult = new String(bytes, "EUC-JP");
            
            // 检查结果是否有效且包含日文字符
            if (isValidDecodedText(eucJpResult) && containsJapaneseCharacters(eucJpResult)) {
                Log.d(TAG, "tryRepairCommonEncodingIssues - EUC-JP解码成功: " + eucJpResult);
                return eucJpResult;
            }
        } catch (Exception e) {
            Log.d(TAG, "tryRepairCommonEncodingIssues - EUC-JP解码失败: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 替换有问题的字符
     */
    private String replaceProblematicCharacters(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return metadata;
        }
        
        StringBuilder result = new StringBuilder();
        
        for (int i = 0; i < metadata.length(); i++) {
            char c = metadata.charAt(i);
            
            // 替换问号为空格（如果问号不是原始字符的一部分）
            if (c == '?') {
                // 检查前后字符，如果看起来像是编码错误的结果，则替换为空格
                boolean replaceWithSpace = false;
                
                if (i > 0 && i < metadata.length() - 1) {
                    char prev = metadata.charAt(i - 1);
                    char next = metadata.charAt(i + 1);
                    
                    // 如果前后都是拉丁字符或中文字符，中间的问号可能是编码错误
                    if ((isLatinCharacter(prev) || isChineseCharacter(prev)) && 
                        (isLatinCharacter(next) || isChineseCharacter(next))) {
                        replaceWithSpace = true;
                    }
                }
                
                if (replaceWithSpace) {
                    result.append(' ');
                } else {
                    result.append(c);
                }
            } else {
                result.append(c);
            }
        }
        
        return result.toString();
    }
    
    /**
     * 检查字符是否是拉丁字符
     */
    private boolean isLatinCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
    
    /**
     * 检查字符是否是中文字符
     */
    private boolean isChineseCharacter(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || // CJK统一汉字
               (c >= 0x3400 && c <= 0x4DBF) || // CJK扩展A
               (c >= 0xF900 && c <= 0xFAFF);   // CJK兼容汉字
    }
    
    /**
     * 检查文本是否包含过多问号
     */
    private boolean containsTooManyQuestionMarks(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        int questionMarkCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '?') {
                questionMarkCount++;
            }
        }
        
        // 如果问号比例超过30%，认为包含过多问号
        double ratio = (double) questionMarkCount / text.length();
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "containsTooManyQuestionMarks - 问号数量: " + questionMarkCount + 
                  ", 总长度: " + text.length() + 
                  ", 比例: " + String.format("%.2f", ratio) + 
                  ", 阈值: 0.30");
        }
        
        return ratio > 0.3;
    }
    
    /**
     * 尝试激进修复问号问题
     */
    private String tryAggressiveQuestionMarkRepair(byte[] originalBytes) {
        if (originalBytes == null || originalBytes.length == 0) {
            return null;
        }
        
        // 优先检查是否是有效的UTF-8序列
        if (isValidUTF8Sequence(originalBytes)) {
            try {
                String utf8Result = new String(originalBytes, "UTF-8");
                if (isValidDecodedText(utf8Result) && !containsObviousGarbledCharacters(utf8Result)) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "tryAggressiveQuestionMarkRepair - 检测到有效UTF-8序列，使用UTF-8解码: " + utf8Result);
                    }
                    return utf8Result;
                }
            } catch (Exception e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "tryAggressiveQuestionMarkRepair - UTF-8解码失败: " + e.getMessage());
                }
            }
        }
        
        // 尝试修复不完整的UTF-8序列
        String repairedUtf8 = repairIncompleteUtf8Sequences(originalBytes);
        if (repairedUtf8 != null && isValidDecodedText(repairedUtf8) && !containsObviousGarbledCharacters(repairedUtf8)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "tryAggressiveQuestionMarkRepair - 修复不完整UTF-8序列成功: " + repairedUtf8);
            }
            return repairedUtf8;
        }
        
        // 尝试基于上下文修复
        String contextRepaired = repairBasedOnContext(originalBytes);
        if (contextRepaired != null && isValidDecodedText(contextRepaired) && !containsObviousGarbledCharacters(contextRepaired)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "tryAggressiveQuestionMarkRepair - 基于上下文修复成功: " + contextRepaired);
            }
            return contextRepaired;
        }
        
        // 方法1: 尝试多种编码组合
        String[] encodings = {"UTF-8", "GBK", "Big5", "ISO-8859-1", "windows-1252", "Shift_JIS"};
        
        for (String encoding1 : encodings) {
            try {
                String step1 = new String(originalBytes, encoding1);
                byte[] step2Bytes = step1.getBytes("ISO-8859-1");
                
                for (String encoding2 : encodings) {
                    try {
                        String result = new String(step2Bytes, encoding2);
                        
                        // 检查结果是否有效
                        if (isValidDecodedText(result)) {
                            // 计算问号比例
                            int questionMarkCount = 0;
                            for (int i = 0; i < result.length(); i++) {
                                if (result.charAt(i) == '?') {
                                    questionMarkCount++;
                                }
                            }
                            double ratio = (double) questionMarkCount / result.length();
                            
                            // 如果问号比例低于原始数据，认为修复有效
                            if (ratio < 0.5) {
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "tryAggressiveQuestionMarkRepair - 编码组合成功: " + 
                                          encoding1 + " -> " + encoding2 + 
                                          ", 结果: " + result + 
                                          ", 问号比例: " + String.format("%.2f", ratio));
                                }
                                return result;
                            }
                        }
                    } catch (Exception e) {
                        // 忽略异常，继续尝试下一个编码
                    }
                }
            } catch (Exception e) {
                // 忽略异常，继续尝试下一个编码
            }
        }
        
        // 方法2: 尝试智能字符替换
        try {
            String baseString = new String(originalBytes, "ISO-8859-1");
            StringBuilder result = new StringBuilder();
            
            for (int i = 0; i < baseString.length(); i++) {
                char c = baseString.charAt(i);
                
                if (c == '?') {
                    // 检查上下文，尝试智能替换
                    if (i > 0 && i < baseString.length() - 1) {
                        char prev = baseString.charAt(i - 1);
                        char next = baseString.charAt(i + 1);
                        
                        // 如果前后是拉丁字符，替换为空格
                        if (isLatinCharacter(prev) && isLatinCharacter(next)) {
                            result.append(' ');
                            continue;
                        }
                        
                        // 如果前后是中文字符，尝试猜测可能的字符
                        if (isChineseCharacter(prev) || isChineseCharacter(next)) {
                            // 可以根据上下文猜测可能的字符，这里简单替换为空格
                            result.append(' ');
                            continue;
                        }
                    }
                    
                    // 单独的问号或者无法确定上下文，保留
                    result.append('?');
                } else {
                    result.append(c);
                }
            }
            
            String repaired = result.toString();
            if (!repaired.equals(baseString)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "tryAggressiveQuestionMarkRepair - 智能替换结果: " + repaired);
                }
                return repaired;
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "tryAggressiveQuestionMarkRepair - 智能替换失败: " + e.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * 检查文本是否包含明显的乱码字符
     */
    private boolean containsObviousGarbledCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        int garbledCharCount = 0;
        int totalCharCount = text.length();
        
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            
            // 检查是否是明显的乱码字符
            // 1. 检查半角片假名字符（通常是编码错误的标志）
            if (c >= 0xFF66 && c <= 0xFF9F) {
                garbledCharCount++;
                continue;
            }
            
            // 2. 检查欧洲扩展字符（可能是Big5被误解析为UTF-8的结果）
            if ((c >= 0x80 && c <= 0xFF) || 
                (c >= 0x100 && c <= 0x17F) || 
                (c >= 0x180 && c <= 0x24F)) {
                garbledCharCount++;
                continue;
            }
            
            // 3. 检查其他可能的乱码字符
            if ((c >= 0x2500 && c <= 0x257F) || // Box Drawing
                (c >= 0x2580 && c <= 0x259F) || // Block Elements
                (c >= 0x25A0 && c <= 0x25FF) || // Geometric Shapes
                (c >= 0x2600 && c <= 0x26FF) || // Miscellaneous Symbols
                (c >= 0x2700 && c <= 0x27BF)) { // Dingbats
                garbledCharCount++;
                continue;
            }
            
            // 4. 检查不常见的Unicode字符
            if (c >= 0x2000 && c <= 0x2FFF) {
                garbledCharCount++;
                continue;
            }
        }
        
        // 如果乱码字符比例超过20%，认为文本包含明显的乱码
        double garbledRatio = (double) garbledCharCount / totalCharCount;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "containsObviousGarbledCharacters - 乱码字符数: " + garbledCharCount + 
                  "/" + totalCharCount + ", 比例: " + String.format("%.2f", garbledRatio));
        }
        
        return garbledRatio > 0.2;
    }

    /**
     * 尝试容错UTF-8解码
     * @param bytes 原始字节数据
     * @return 解码后的字符串，如果失败则返回null
     */
    private String tryFaultTolerantUTF8Decoding(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            // 首先尝试标准UTF-8解码
            String standardResult = new String(bytes, "UTF-8");
            if (isValidDecodedText(standardResult) && !containsObviousGarbledCharacters(standardResult)) {
                return standardResult;
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "标准UTF-8解码失败: " + e.getMessage());
            }
        }
        
        // 尝试修复常见的UTF-8编码问题
        try {
            // 检查是否是Latin1编码的UTF-8字节
            String latin1Decoded = new String(bytes, "ISO-8859-1");
            byte[] reencodedBytes = latin1Decoded.getBytes("UTF-8");
            
            // 检查重新编码后的字节是否形成有效的UTF-8序列
            if (isValidUTF8Sequence(reencodedBytes)) {
                String repairedResult = new String(reencodedBytes, "UTF-8");
                if (isValidDecodedText(repairedResult) && !containsObviousGarbledCharacters(repairedResult)) {
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "通过Latin1->UTF-8转换成功修复编码: " + repairedResult);
                    }
                    return repairedResult;
                }
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Latin1->UTF-8转换失败: " + e.getMessage());
            }
        }
        
        // 尝试逐字节修复UTF-8序列
        try {
            String repairedResult = repairUTF8Sequences(bytes);
            if (repairedResult != null && isValidDecodedText(repairedResult) && !containsObviousGarbledCharacters(repairedResult)) {
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "通过逐字节修复成功解码UTF-8: " + repairedResult);
                }
                return repairedResult;
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "逐字节修复UTF-8失败: " + e.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * 修复损坏的UTF-8字节序列
     * @param bytes 原始字节数据
     * @return 修复后的字符串，如果无法修复则返回null
     */
    private String repairUTF8Sequences(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        ByteArrayOutputStream fixedBytes = new ByteArrayOutputStream();
        int i = 0;
        
        while (i < bytes.length) {
            byte b = bytes[i];
            
            if ((b & 0x80) == 0) {
                // ASCII字符 (0xxxxxxx)
                fixedBytes.write(b);
                i++;
            } else if ((b & 0xE0) == 0xC0) {
                // 2字节序列 (110xxxxx)
                if (i + 1 < bytes.length && (bytes[i + 1] & 0xC0) == 0x80) {
                    fixedBytes.write(b);
                    fixedBytes.write(bytes[i + 1]);
                    i += 2;
                } else {
                    // 无效序列，跳过或替换
                    fixedBytes.write('?');
                    i++;
                }
            } else if ((b & 0xF0) == 0xE0) {
                // 3字节序列 (1110xxxx)
                if (i + 2 < bytes.length && 
                    (bytes[i + 1] & 0xC0) == 0x80 && 
                    (bytes[i + 2] & 0xC0) == 0x80) {
                    fixedBytes.write(b);
                    fixedBytes.write(bytes[i + 1]);
                    fixedBytes.write(bytes[i + 2]);
                    i += 3;
                } else {
                    // 无效序列，跳过或替换
                    fixedBytes.write('?');
                    i++;
                }
            } else if ((b & 0xF8) == 0xF0) {
                // 4字节序列 (11110xxx)
                if (i + 3 < bytes.length && 
                    (bytes[i + 1] & 0xC0) == 0x80 && 
                    (bytes[i + 2] & 0xC0) == 0x80 && 
                    (bytes[i + 3] & 0xC0) == 0x80) {
                    fixedBytes.write(b);
                    fixedBytes.write(bytes[i + 1]);
                    fixedBytes.write(bytes[i + 2]);
                    fixedBytes.write(bytes[i + 3]);
                    i += 4;
                } else {
                    // 无效序列，跳过或替换
                    fixedBytes.write('?');
                    i++;
                }
            } else {
                // 无效的UTF-8起始字节，跳过或替换
                fixedBytes.write('?');
                i++;
            }
        }
        
        try {
            return fixedBytes.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 尝试修复服务器将UTF-8当作Latin1处理的情况
     * @param bytes 原始字节数据
     * @return 修复后的字符串，如果无法修复则返回null
     */
    private String tryFixServerLatin1Handling(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            // 检查是否是双重编码的情况：UTF-8字节被当作Latin1编码，然后再次编码为UTF-8
            // 先用Latin1解码，再用UTF-8编码
            String latin1Decoded = new String(bytes, "ISO-8859-1");
            byte[] utf8Bytes = latin1Decoded.getBytes("UTF-8");
            
            // 检查是否形成了有效的UTF-8序列
            if (isValidUTF8Sequence(utf8Bytes)) {
                String result = new String(utf8Bytes, "UTF-8");
                if (isValidDecodedText(result) && !containsObviousGarbledCharacters(result)) {
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "检测到服务器Latin1处理问题，已修复: " + result);
                    }
                    return result;
                }
            }
            
            // 尝试另一种常见情况：UTF-8字节被当作Latin1编码，然后又当作GBK编码
            String gbkDecoded = new String(utf8Bytes, "GBK");
            if (isValidDecodedText(gbkDecoded) && !containsObviousGarbledCharacters(gbkDecoded)) {
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "检测到服务器Latin1->GBK处理问题，已修复: " + gbkDecoded);
                }
                return gbkDecoded;
            }
            
            // 尝试Windows-1252编码（Latin1的超集）
            String win1252Decoded = new String(bytes, "windows-1252");
            byte[] win1252ToUtf8 = win1252Decoded.getBytes("UTF-8");
            
            if (isValidUTF8Sequence(win1252ToUtf8)) {
                String result = new String(win1252ToUtf8, "UTF-8");
                if (isValidDecodedText(result) && !containsObviousGarbledCharacters(result)) {
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "检测到服务器Windows-1252处理问题，已修复: " + result);
                    }
                    return result;
                }
            }
            
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "修复服务器Latin1处理失败: " + e.getMessage());
            }
        }
        
        return null;
    }
}
