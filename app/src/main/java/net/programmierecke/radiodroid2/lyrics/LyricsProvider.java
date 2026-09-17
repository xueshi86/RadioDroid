package net.programmierecke.radiodroid2.lyrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 单一歌词来源。fetch 由 LyricsRepository 在后台线程调用，
 * 同步返回结果；无结果返回 null；来源不可用或响应异常抛 Exception（由回退链捕获后继续下一来源）。
 */
public interface LyricsProvider {

    @NonNull
    String getId();

    @Nullable
    LyricsResult fetch(@NonNull String artist, @NonNull String track, @Nullable Integer durationSeconds) throws Exception;
}
