package net.programmierecke.radiodroid2.lyrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.database.RadioDroidDatabase;

import okhttp3.OkHttpClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 歌词获取编排器：Room 缓存 → LRCLIB（官方/自定义实例）→ LrcAPI（公共/自建实例）→ 网易云（默认开启）。
 * fetchLyrics 须在主线程调用，回调统一切回主线程。
 */
public class LyricsRepository {

    public interface Callback {
        void onLyricsFound(@NonNull LyricsResult result);

        void onLyricsNotFound();
    }

    public static final String PREF_LYRICS_SOURCE_MODE = "lyrics_source_mode";
    public static final String LYRICS_SOURCE_MODE_INTERNAL = "internal";
    public static final String LYRICS_SOURCE_MODE_EXTERNAL = "external";
    public static final String PREF_LYRICS_LRCLIB_BASE_URL = "lyrics_lrclib_base_url";
    public static final String PREF_LYRICS_LRCPI_BASE_URL = "lyrics_lrcapi_base_url";
    public static final String PREF_LYRICS_NETEASE_ENABLED = "lyrics_netease_enabled";

    private static final long CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000;
    /** 缓存键版本：匹配策略收紧后，旧键下可能存有张冠李戴的歌词，通过版本前缀使其失效。 */
    private static final String CACHE_KEY_VERSION = "v2|";

    private final Context context;
    private final Executor executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "LyricsFetch"));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public LyricsRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public void fetchLyrics(@Nullable String artist, @NonNull String track,
                            @Nullable Integer durationSeconds, @NonNull Callback callback) {
        final String[] resolved = resolveMetadata(artist, track);
        if (resolved == null) {
            // 电台未提供可用的"歌手 + 曲目"元信息：不做任何搜索，直接显示无歌词
            postNotFound(callback);
            return;
        }
        final String rawArtist = resolved[0];
        final String rawTrack = resolved[1];

        executor.execute(() -> {
            LyricsResult cached = getFromCache(rawArtist, rawTrack);
            if (cached != null) {
                postFound(callback, cached);
                return;
            }

            LyricsResult result = fetchFromProviders(rawArtist, rawTrack, durationSeconds);
            if (result != null && result.hasContent()) {
                putToCache(result, rawArtist, rawTrack);
                postFound(callback, result);
            } else {
                postNotFound(callback);
            }
        });
    }

    @Nullable
    private LyricsResult fetchFromProviders(@NonNull String artist, @NonNull String track, @Nullable Integer durationSeconds) {
        List<String[]> queries = buildQueries(artist, track);
        if (queries.isEmpty()) {
            return null;
        }
        for (LyricsProvider provider : buildProviders()) {
            // 各来源内部均已按"精确匹配"校验（歌手与曲目必须一致），
            // 非精准结果会被来源自身丢弃，因此此处不再做模糊兜底。
            for (String[] query : queries) {
                try {
                    LyricsResult result = provider.fetch(query[0], query[1], durationSeconds);
                    if (result != null && result.hasContent()) {
                        return result;
                    }
                } catch (Exception e) {
                    // 该来源本次不可用，继续尝试
                }
            }
        }
        return null;
    }

    /**
     * 解析并校验元信息：占位值（Unknown Artist / 未知 / - 等）视为缺失；
     * 若歌手缺失而曲目形如 "Artist - Title"，则尝试从中拆分补全。
     * 无法得到"歌手 + 曲目"这一对可用元信息时返回 null，调用方据此直接显示无歌词、不做任何搜索。
     */
    @Nullable
    private String[] resolveMetadata(@Nullable String artist, @NonNull String track) {
        String resolvedArtist = isPlaceholder(artist) ? "" : artist.trim();
        String resolvedTrack = track.trim();
        if (isPlaceholder(resolvedTrack)) {
            return null;
        }
        if (resolvedArtist.isEmpty()) {
            int idx = resolvedTrack.indexOf(" - ");
            if (idx > 0) {
                String left = resolvedTrack.substring(0, idx).trim();
                String right = resolvedTrack.substring(idx + 3).trim();
                if (!isPlaceholder(left) && !isPlaceholder(right)) {
                    resolvedArtist = left;
                    resolvedTrack = right;
                }
            }
        }
        if (isPlaceholder(resolvedArtist) || isPlaceholder(resolvedTrack)) {
            return null;
        }
        return new String[]{resolvedArtist, resolvedTrack};
    }

    /** 元信息是否为缺失/未知占位值（同时比对本地化的 Unknown 文案）。 */
    private boolean isPlaceholder(@Nullable String value) {
        if (LyricsMatcher.isUnknown(value)) {
            return true;
        }
        String trimmed = value.trim();
        return trimmed.equalsIgnoreCase(context.getString(R.string.unknown))
                || trimmed.equalsIgnoreCase(context.getString(R.string.unknown_artist))
                || trimmed.equalsIgnoreCase(context.getString(R.string.unknown_track));
    }

    /**
     * 电台元数据常有 "Track [Station]" "(Live)" 等污染，或 artist 与 track 同值，
     * 依次生成多个归一化查询组合提升命中率。
     */
    @NonNull
    private List<String[]> buildQueries(@NonNull String artist, @NonNull String track) {
        List<String[]> queries = new ArrayList<>();
        String cleanedTrack = removeBrackets(track);
        String cleanedArtist = removeBrackets(artist);

        if (!isPlaceholder(cleanedArtist) && !isPlaceholder(cleanedTrack)
                && !cleanedArtist.equalsIgnoreCase(cleanedTrack)) {
            queries.add(new String[]{cleanedArtist, cleanedTrack});
        }

        // track 常为 "Artist - Title" 形式，尝试拆分
        String splitArtist = cleanedArtist;
        String splitTrack = cleanedTrack;
        if (cleanedArtist.isEmpty() || cleanedArtist.equalsIgnoreCase(cleanedTrack)) {
            int idx = cleanedTrack.indexOf(" - ");
            if (idx > 0) {
                splitArtist = cleanedTrack.substring(0, idx).trim();
                splitTrack = cleanedTrack.substring(idx + 3).trim();
            }
        }
        String[] splitQuery = new String[]{splitArtist, splitTrack};
        if (!isPlaceholder(splitArtist) && !isPlaceholder(splitTrack)) {
            for (String[] q : queries) {
                if (q[0].equalsIgnoreCase(splitQuery[0]) && q[1].equalsIgnoreCase(splitQuery[1])) {
                    return queries;
                }
            }
            queries.add(splitQuery);
        }
        return queries;
    }

    @NonNull
    private String removeBrackets(@NonNull String input) {
        return input
                .replaceAll("\\(.*?\\)", "")
                .replaceAll("\\[.*?\\]", "")
                .replaceAll("\\{.*?\\}", "")
                .trim();
    }

    @NonNull
    private List<LyricsProvider> buildProviders() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        OkHttpClient client = ((RadioDroidApp) context).getHttpClient();

        List<LyricsProvider> providers = new ArrayList<>();
        providers.add(new LrclibProvider(client, prefs.getString(PREF_LYRICS_LRCLIB_BASE_URL, "")));
        providers.add(new LrcApiProvider(client, prefs.getString(PREF_LYRICS_LRCPI_BASE_URL, "")));
        if (prefs.getBoolean(PREF_LYRICS_NETEASE_ENABLED, true)) {
            providers.add(new NetEaseProvider(client));
        }
        return providers;
    }

    @Nullable
    private LyricsResult getFromCache(@NonNull String artist, @NonNull String track) {
        try {
            RadioDroidDatabase db = RadioDroidDatabase.getDatabase(context);
            LyricsCacheEntry entry = db.lyricsCacheDao().getByKey(cacheKey(artist, track));
            if (entry == null) {
                return null;
            }
            if (System.currentTimeMillis() - entry.fetchedAt > CACHE_TTL_MS) {
                return null;
            }
            return new LyricsResult(entry.artist, entry.track, entry.albumName,
                    entry.plainLyrics, entry.syncedLyrics, entry.instrumental, entry.sourceId);
        } catch (Exception e) {
            return null;
        }
    }

    private void putToCache(@NonNull LyricsResult result, @NonNull String artist, @NonNull String track) {
        try {
            RadioDroidDatabase db = RadioDroidDatabase.getDatabase(context);
            db.lyricsCacheDao().insert(new LyricsCacheEntry(
                    cacheKey(artist, track),
                    result.artist, result.track, result.albumName,
                    result.plainLyrics, result.syncedLyrics,
                    result.instrumental, result.sourceId,
                    System.currentTimeMillis()));
        } catch (Exception e) {
            // 缓存失败不影响结果返回
        }
    }

    @NonNull
    private String cacheKey(@NonNull String artist, @NonNull String track) {
        return CACHE_KEY_VERSION + artist.trim().toLowerCase() + "|" + track.trim().toLowerCase();
    }

    private void postFound(@NonNull Callback callback, @NonNull LyricsResult result) {
        mainHandler.post(() -> callback.onLyricsFound(result));
    }

    private void postNotFound(@NonNull Callback callback) {
        mainHandler.post(callback::onLyricsNotFound);
    }
}
