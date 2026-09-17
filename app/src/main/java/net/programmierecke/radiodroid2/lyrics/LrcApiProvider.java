package net.programmierecke.radiodroid2.lyrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * LrcAPI (https://github.com/HisAtri/LrcApi，公共实例 https://api.lrc.cx) 聚合歌词接口，
 * 作为 LRCLIB 与网易云之间的中间降级层。GET /lyrics?title=&artist= 直接返回纯文本 LRC，
 * 未命中返回 404；自建实例可在设置中配置 base URL。
 */
public class LrcApiProvider implements LyricsProvider {

    public static final String ID = "lrcapi";
    public static final String DEFAULT_BASE_URL = "https://api.lrc.cx";

    private static final Pattern TIME_TAG = Pattern.compile("\\[\\d{1,2}:\\d{1,2}([.:]\\d{1,3})?\\]");

    private final OkHttpClient httpClient;
    private final String baseUrl;

    public LrcApiProvider(@NonNull OkHttpClient httpClient, @Nullable String baseUrl) {
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
    public LyricsResult fetch(@NonNull String artist, @NonNull String track, @Nullable Integer durationSeconds) throws Exception {
        HttpUrl url = lyricsUrl(artist, track);
        if (url == null) {
            return null;
        }

        Response response = null;
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "text/plain")
                    .build();
            response = httpClient.newCall(request).execute();
            if (response.code() == 404) {
                return null;
            }
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("LrcAPI failed: HTTP " + response.code());
            }
            String body = response.body().string();
            if (body.trim().isEmpty()) {
                return null;
            }
            return toResult(body, artist, track);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    @Nullable
    private HttpUrl lyricsUrl(@NonNull String artist, @NonNull String track) {
        String base = baseUrl;
        if (!base.contains("://")) {
            base = "https://" + base;
        }
        HttpUrl baseHttpUrl = HttpUrl.parse(base);
        if (baseHttpUrl == null) {
            return null;
        }
        HttpUrl.Builder builder = baseHttpUrl.newBuilder()
                .addPathSegments("lyrics")
                .addQueryParameter("title", track);
        if (!artist.trim().isEmpty()) {
            builder.addQueryParameter("artist", artist);
        }
        return builder.build();
    }

    @NonNull
    private LyricsResult toResult(@NonNull String body, @NonNull String artist, @NonNull String track) {
        Matcher matcher = TIME_TAG.matcher(body);
        if (matcher.find()) {
            return new LyricsResult(artist, track, null, null, body, false, ID);
        }
        return new LyricsResult(artist, track, null, body, null, false, ID);
    }
}
