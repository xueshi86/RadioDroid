package net.programmierecke.radiodroid2.lyrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * LRCLIB (https://lrclib.net) 开放歌词 API，无需鉴权。
 * 先 GET /api/get 精确匹配（artist+track+duration），未命中再 GET /api/search，
 * 但仅接受曲目与歌手均精确一致的候选，不做任何模糊兜底。base URL 可配置为自建实例/镜像。
 */
public class LrclibProvider implements LyricsProvider {

    public static final String ID = "lrclib";
    public static final String DEFAULT_BASE_URL = "https://lrclib.net";

    private static final Type LIST_TYPE = new TypeToken<List<LrcLyrics>>() {
    }.getType();

    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final Gson gson = new Gson();

    private static class LrcLyrics {
        String trackName;
        String artistName;
        String albumName;
        Long duration;
        Boolean instrumental;
        String plainLyrics;
        String syncedLyrics;
    }

    public LrclibProvider(@NonNull OkHttpClient httpClient, @Nullable String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = (baseUrl == null || baseUrl.trim().isEmpty()) ? DEFAULT_BASE_URL : baseUrl.trim();
    }

    @NonNull
    @Override
    public String getId() {
        return ID;
    }

    @Nullable
    @Override
    public LyricsResult fetch(@NonNull String artist, @NonNull String track, @Nullable Integer durationSeconds) throws IOException {
        LrcLyrics direct = requestSingle(apiGetUrl(artist, track, durationSeconds));
        if (direct != null && hasContent(direct) && matches(direct, artist, track)) {
            return toResult(direct, artist, track);
        }

        List<LrcLyrics> candidates = requestList(apiSearchUrl(artist, track));
        LrcLyrics best = pickExact(candidates, artist, track, durationSeconds);
        return best == null ? null : toResult(best, artist, track);
    }

    /** 候选是否与请求的歌手、曲目均精确一致。 */
    private boolean matches(@NonNull LrcLyrics lyrics, @NonNull String artist, @NonNull String track) {
        return LyricsMatcher.artistMatches(artist, lyrics.artistName)
                && LyricsMatcher.titleMatches(track, lyrics.trackName);
    }

    @Nullable
    private LrcLyrics requestSingle(@Nullable HttpUrl url) throws IOException {
        if (url == null) {
            return null;
        }
        Response response = null;
        try {
            response = executeRequest(url);
            if (response.code() == 404) {
                return null;
            }
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("LRCLIB get failed: HTTP " + response.code());
            }
            return gson.fromJson(response.body().charStream(), LrcLyrics.class);
        } catch (JsonSyntaxException e) {
            throw new IOException("LRCLIB invalid payload", e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    @Nullable
    private List<LrcLyrics> requestList(@Nullable HttpUrl url) throws IOException {
        if (url == null) {
            return null;
        }
        Response response = null;
        try {
            response = executeRequest(url);
            if (response.code() == 404) {
                return null;
            }
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("LRCLIB search failed: HTTP " + response.code());
            }
            return gson.fromJson(response.body().charStream(), LIST_TYPE);
        } catch (JsonSyntaxException e) {
            throw new IOException("LRCLIB invalid search payload", e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private Response executeRequest(@NonNull HttpUrl url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build();
        return httpClient.newCall(request).execute();
    }

    @Nullable
    private HttpUrl apiGetUrl(@NonNull String artist, @NonNull String track, @Nullable Integer durationSeconds) {
        HttpUrl base = baseHttpUrl();
        if (base == null) {
            return null;
        }
        HttpUrl.Builder builder = base.newBuilder()
                .addPathSegments("api/get")
                .addQueryParameter("artist_name", artist)
                .addQueryParameter("track_name", track);
        if (durationSeconds != null) {
            builder.addQueryParameter("duration", String.valueOf(durationSeconds));
        }
        return builder.build();
    }

    @Nullable
    private HttpUrl apiSearchUrl(@Nullable String artist, @NonNull String track) {
        HttpUrl base = baseHttpUrl();
        if (base == null) {
            return null;
        }
        HttpUrl.Builder builder = base.newBuilder()
                .addPathSegments("api/search")
                .addQueryParameter("track_name", track);
        if (artist != null && !artist.trim().isEmpty()) {
            builder.addQueryParameter("artist_name", artist);
        }
        return builder.build();
    }

    @Nullable
    private HttpUrl baseHttpUrl() {
        String normalized = baseUrl;
        if (!normalized.contains("://")) {
            normalized = "https://" + normalized;
        }
        return HttpUrl.parse(normalized);
    }

    private boolean hasContent(@Nullable LrcLyrics lyrics) {
        return lyrics != null && (Boolean.TRUE.equals(lyrics.instrumental)
                || !isEmpty(lyrics.plainLyrics)
                || !isEmpty(lyrics.syncedLyrics));
    }

    @Nullable
    private LrcLyrics pickExact(@Nullable List<LrcLyrics> candidates, @NonNull String artist,
                               @NonNull String track, @Nullable Integer durationSeconds) {
        if (candidates == null) {
            return null;
        }
        LrcLyrics best = null;
        long bestScore = Long.MIN_VALUE;
        for (LrcLyrics candidate : candidates) {
            if (candidate == null || !hasContent(candidate) || !matches(candidate, artist, track)) {
                continue; // 非精确匹配一律丢弃
            }
            long score = 0;
            if (!isEmpty(candidate.syncedLyrics)) {
                score += 100;
            }
            if (durationSeconds != null && candidate.duration != null) {
                long diff = Math.abs(candidate.duration - durationSeconds);
                score += Math.max(0, 50 - diff);
            }
            if (best == null || score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    @NonNull
    private LyricsResult toResult(@NonNull LrcLyrics lyrics, @NonNull String requestedArtist, @NonNull String requestedTrack) {
        return new LyricsResult(
                isEmpty(lyrics.artistName) ? requestedArtist : lyrics.artistName,
                isEmpty(lyrics.trackName) ? requestedTrack : lyrics.trackName,
                lyrics.albumName,
                lyrics.plainLyrics,
                lyrics.syncedLyrics,
                Boolean.TRUE.equals(lyrics.instrumental),
                ID);
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }
}
