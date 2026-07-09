package net.programmierecke.radiodroid2.playlist;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Utility class to download and parse PLS and M3U playlist files,
 * returning the first playable stream URL found.
 */
public class PlaylistParser {

    private static final String TAG = "PlaylistParser";

    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 15;

    private static final Pattern PLS_FILE_PATTERN = Pattern.compile(".*\\.pls([#?\\s].*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern M3U_FILE_PATTERN = Pattern.compile(".*\\.m3u([#?\\s].*)?$", Pattern.CASE_INSENSITIVE);

    private PlaylistParser() {
        // utility class
    }

    /**
     * Returns true if the given URL points to a playlist file (PLS or M3U).
     */
    public static boolean isPlaylistUrl(@Nullable String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        return PLS_FILE_PATTERN.matcher(url).matches()
                || M3U_FILE_PATTERN.matcher(url).matches();
    }

    /**
     * Downloads and parses a playlist file, returning the first audio stream URL.
     * Returns the original URL if parsing fails or no stream URL is found.
     */
    @NonNull
    public static String resolvePlaylistUrl(@NonNull OkHttpClient httpClient, @NonNull String playlistUrl) {
        String content = downloadPlaylist(httpClient, playlistUrl);
        if (content == null || content.trim().isEmpty()) {
            Log.w(TAG, "Could not download playlist, returning original URL: " + playlistUrl);
            return playlistUrl;
        }

        List<String> entries;
        if (PLS_FILE_PATTERN.matcher(playlistUrl).matches()) {
            entries = parsePls(content);
        } else {
            entries = parseM3u(content);
        }

        if (entries.isEmpty()) {
            Log.w(TAG, "No stream entries found in playlist, returning original URL: " + playlistUrl);
            return playlistUrl;
        }

        String resolved = entries.get(0);
        Log.i(TAG, "Resolved playlist " + playlistUrl + " to " + resolved);
        return resolved;
    }

    /**
     * Parse a PLS file and return a list of FileN entries in order.
     */
    @NonNull
    public static List<String> parsePls(@NonNull String content) {
        List<String> result = new ArrayList<>();
        Pattern filePattern = Pattern.compile("^File\\d+\\s*=\\s*(.+)$", Pattern.CASE_INSENSITIVE);

        String[] lines = content.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("[")) {
                continue;
            }
            Matcher matcher = filePattern.matcher(line);
            if (matcher.find()) {
                String url = matcher.group(1).trim();
                if (!url.isEmpty()) {
                    result.add(url);
                }
            }
        }
        return result;
    }

    /**
     * Parse an M3U file and return a list of stream URLs in order.
     */
    @NonNull
    public static List<String> parseM3u(@NonNull String content) {
        List<String> result = new ArrayList<>();

        String[] lines = content.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.toLowerCase().startsWith("http://") || line.toLowerCase().startsWith("https://")) {
                result.add(line);
            }
        }
        return result;
    }

    @Nullable
    private static String downloadPlaylist(@NonNull OkHttpClient httpClient, @NonNull String url) {
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "RadioDroid/2.0")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "Playlist download failed: " + response.code() + " for " + url);
                return null;
            }

            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }

            // Try to determine charset from Content-Type
            okhttp3.MediaType mediaType = body.contentType();
            Charset charset = Charset.defaultCharset();
            if (mediaType != null) {
                Charset cs = mediaType.charset();
                if (cs != null) {
                    charset = cs;
                }
            }

            return body.source().readString(charset);
        } catch (IOException e) {
            Log.w(TAG, "Playlist download IOException for " + url + ": " + e.getMessage());
            return null;
        }
    }
}
