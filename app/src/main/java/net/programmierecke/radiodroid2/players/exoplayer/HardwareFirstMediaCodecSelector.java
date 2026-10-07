package net.programmierecke.radiodroid2.players.exoplayer;

import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 硬件解码器优先的 MediaCodecSelector。
 *
 * <p>不改变 ExoPlayer 默认的能力过滤（不支持的 profile 依旧会被剔除），仅调整候选解码器的
 * 优先顺序：硬件解码器排前，软件解码器排后，其余（无法判定的）保持原有相对顺序。
 * 配合 {@code DefaultRenderersFactory.setEnableDecoderFallback(true)}，硬件解码器初始化失败时
 * ExoPlayer 会在同一条流内立即切换到下一个候选解码器，因此不会破坏播放。
 */
public class HardwareFirstMediaCodecSelector implements MediaCodecSelector {

    /** 排序权重：硬件 0 < 未知 1 < 纯软件 2，权重相同者保持默认顺序（稳定排序）。 */
    private static final Comparator<MediaCodecInfo> HARDWARE_FIRST =
            Comparator.comparingInt((MediaCodecInfo info) -> {
                if (info.hardwareAccelerated) {
                    return 0;
                }
                return info.softwareOnly ? 2 : 1;
            });

    @Override
    public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder,
                                                boolean requiresTunnelingDecoder) throws MediaCodecUtil.DecoderQueryException {
        List<MediaCodecInfo> infos = MediaCodecSelector.DEFAULT
                .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
        List<MediaCodecInfo> sorted = new ArrayList<>(infos);
        sorted.sort(HARDWARE_FIRST);
        return sorted;
    }
}