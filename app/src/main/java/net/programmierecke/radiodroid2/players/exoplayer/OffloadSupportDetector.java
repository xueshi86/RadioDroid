package net.programmierecke.radiodroid2.players.exoplayer;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

/**
 * 音频卸载（Audio Offload）支持检测。
 *
 * <p>卸载把解码工作交给设备 DSP 完成，可显著降低 CPU 占用与耗电，但需要 Android 10 及以上
 * 且设备硬件/音频策略支持。检测结果在进程内缓存一次。
 *
 * <p>检测失败的方向是安全的：任何异常都保守判定为“不支持”，此时设置页开关显示为禁用，
 * 不会影响默认（关闭）行为。
 */
public final class OffloadSupportDetector {

    /** 进程内缓存，null 表示尚未检测。 */
    private static Boolean cached;

    private OffloadSupportDetector() {
    }

    public static synchronized boolean isSupported(Context context) {
        if (cached != null) {
            return cached;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            cached = false;
            return false;
        }
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            // 电台主流编码为 AAC-LC / MP3。硬件卸载对 MP3 支持面很窄，这里以 AAC-LC 作为
            // 探测样本：与 ExoPlayer 实际卸载时的判定口径一致（不支持则自动走常规渲染）。
            AudioFormat aacFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_AAC_LC)
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cached = AudioManager.isOffloadedPlaybackSupported(aacFormat, attributes);
            } else {
                cached = probeWithAudioTrack(attributes, aacFormat);
            }
        } catch (Throwable t) {
            // 个别机型不识别该 encoding、或 AudioTrack 构建被拒，一律保守视为不支持
            cached = false;
        }
        return cached;
    }

    /** API 29-32 无官方查询接口，直接构建一条 offload 轨道进行真实探测。 */
    @TargetApi(Build.VERSION_CODES.Q)
    private static boolean probeWithAudioTrack(AudioAttributes attributes, AudioFormat format) {
        AudioTrack probe = null;
        try {
            probe = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setOffloadedPlayback(true)
                    .build();
            return probe.isOffloadedPlayback();
        } catch (Throwable t) {
            return false;
        } finally {
            if (probe != null) {
                try {
                    probe.release();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }
}