package net.programmierecke.radiodroid2.lyrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 网易云音乐实验性歌词来源（非官方公开接口，中文曲库覆盖好）。
 * 默认不启用，须由用户在设置中显式开启后才会进入回退链。
 * 流程：/api/search/get/web 搜索歌曲 id → /api/song/lyric 拉取 LRC。
 */
public class NetEaseProvider implements LyricsProvider {

    public static final String ID = "netease";

    private static final Pattern TIME_TAG = Pattern.compile("\\[\\d{1,2}:\\d{1,2}(\\.\\d{1,3})?]");
    private static final String REFERER = "https://music.163.com/";

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    private static class SearchResponse {
        Result result;
        Integer code;

        static class Result {
            List<Song> songs;
        }

        static class Song {
            Long id;
            String name;
            Long duration;
            List<Artist> artists;
        }

        static class Artist {
            String name;
        }
    }

    private static class LyricResponse {
        Lrc lrc;
        Integer code;

        static class Lrc {
            String lyric;
        }
    }

    public NetEaseProvider(@NonNull OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @NonNull
    @Override
    public String getId() {
        return ID;
    }

    @Nullable
    @Override
    public LyricsResult fetch(@NonNull String artist, @NonNull String track, @Nullable Integer durationSeconds) throws IOException {
        SearchResponse.Song song = searchSong(artist, track, durationSeconds);
        if (song == null || song.id == null) {
            return null;
        }

        String lyric = fetchLyric(song.id);
        if (lyric == null) {
            return null;
        }

        boolean synced = TIME_TAG.matcher(lyric).find();
        return new LyricsResult(
                artist,
                song.name != null ? song.name : track,
                null,
                synced ? null : lyric,
                synced ? lyric : null,
                false,
                ID);
    }

    @Nullable
    private SearchResponse.Song searchSong(@NonNull String artist, @NonNull String track, @Nullable Integer durationSeconds) throws IOException {
        String keyword = (artist.trim().isEmpty() ? "" : artist.trim() + " ") + track.trim();

        HttpUrl url = HttpUrl.parse("https://music.163.com/api/search/get/web").newBuilder()
                .addQueryParameter("s", keyword)
                .addQueryParameter("type", "1")
                .addQueryParameter("limit", "10")
                .addQueryParameter("offset", "0")
                .build();

        SearchResponse response = execute(url, SearchResponse.class);
        if (response == null || response.code == null || response.code != 200
                || response.result == null || response.result.songs == null) {
            return null;
        }
        return pickBestSong(response.result.songs, artist, track, durationSeconds);
    }

    @Nullable
    private String fetchLyric(long songId) throws IOException {
        HttpUrl url = HttpUrl.parse("https://music.163.com/api/song/lyric").newBuilder()
                .addQueryParameter("id", String.valueOf(songId))
                .addQueryParameter("lv", "1")
                .addQueryParameter("kv", "1")
                .addQueryParameter("tv", "-1")
                .build();

        LyricResponse response = execute(url, LyricResponse.class);
        if (response == null || response.code == null || response.code != 200 || response.lrc == null) {
            return null;
        }
        String lyric = response.lrc.lyric;
        return (lyric == null || lyric.trim().isEmpty()) ? null : lyric;
    }

    @Nullable
    private <T> T execute(@NonNull HttpUrl url, @NonNull Class<T> type) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("Accept", "application/json")
                .build();

        Response response = null;
        try {
            response = httpClient.newCall(request).execute();
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("NetEase request failed: HTTP " + response.code());
            }
            return gson.fromJson(response.body().charStream(), type);
        } catch (JsonSyntaxException e) {
            throw new IOException("NetEase invalid payload", e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    @Nullable
    private SearchResponse.Song pickBestSong(@Nullable List<SearchResponse.Song> songs, @NonNull String artist,
                                             @NonNull String track, @Nullable Integer durationSeconds) {
        if (songs == null) {
            return null;
        }
        SearchResponse.Song best = null;
        long bestScore = Long.MIN_VALUE;
        for (SearchResponse.Song song : songs) {
            if (song == null || song.id == null) {
                continue;
            }
            long score = 0;
            if (song.name != null && song.name.toLowerCase().contains(track.toLowerCase())) {
                score += 20;
            }
            if (!artist.trim().isEmpty() && song.artists != null) {
                for (SearchResponse.Artist a : song.artists) {
                    if (a != null && a.name != null && a.name.toLowerCase().contains(artist.toLowerCase())) {
                        score += 50;
                        break;
                    }
                }
            }
            if (durationSeconds != null && song.duration != null) {
                long diff = Math.abs(song.duration / 1000 - durationSeconds);
                score += Math.max(0, 30 - diff);
            }
            if (best == null || score > bestScore) {
                bestScore = score;
                best = song;
            }
        }
        return best;
    }
}
