package net.programmierecke.radiodroid2.updater;

import android.content.Context;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.RadioDroidApp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UpdateChecker {
    private static final String TAG = "UpdateChecker";
    private static final String RELEASES_LATEST_URL = "https://api.github.com/repos/xueshi86/RadioDroid/releases/latest";

    private UpdateChecker() {
    }

    public static UpdateInfo check(Context context) {
        try {
            RadioDroidApp app = (RadioDroidApp) context.getApplicationContext();
            OkHttpClient client = app.getHttpClient();
            Request request = new Request.Builder().url(RELEASES_LATEST_URL).build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.w(TAG, "GitHub API returned " + response.code());
                    return null;
                }
                String body = response.body().string();
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                String tagName = root.has("tag_name") ? root.get("tag_name").getAsString() : null;
                if (tagName == null) {
                    Log.w(TAG, "No tag_name in release response");
                    return null;
                }
                String notes = root.has("body") ? root.get("body").getAsString() : "";

                JsonArray assets = root.has("assets") ? root.getAsJsonArray("assets") : new JsonArray();
                List<String> assetNames = new ArrayList<>();
                Map<String, String> urls = new HashMap<>();
                Map<String, Long> sizes = new HashMap<>();
                for (JsonElement element : assets) {
                    JsonObject asset = element.getAsJsonObject();
                    if (!asset.has("name")) {
                        continue;
                    }
                    String name = asset.get("name").getAsString();
                    assetNames.add(name);
                    if (asset.has("browser_download_url")) {
                        urls.put(name, asset.get("browser_download_url").getAsString());
                    }
                    if (asset.has("size")) {
                        sizes.put(name, asset.get("size").getAsLong());
                    }
                }

                String apkName = findApkAsset(assetNames, BuildConfig.FLAVOR);
                if (apkName == null) {
                    Log.w(TAG, "No APK asset found for flavor " + BuildConfig.FLAVOR);
                    return null;
                }

                boolean hasUpdate = versionNewerThan(tagName, BuildConfig.VERSION_NAME);
                String apkUrl = urls.get(apkName);
                long apkSize = sizes.containsKey(apkName) ? sizes.get(apkName) : 0L;
                return new UpdateInfo(tagName, apkUrl, apkName, apkSize, notes, hasUpdate);
            }
        } catch (Exception e) {
            Log.w(TAG, "Check for update failed: " + e.getMessage());
            return null;
        }
    }

    static boolean versionNewerThan(String latestVersion, String currentVersion) {
        int[] latest = parseVersion(latestVersion);
        int[] current = parseVersion(currentVersion);
        int segments = Math.max(latest.length, current.length);
        for (int i = 0; i < segments; i++) {
            int l = i < latest.length ? latest[i] : 0;
            int c = i < current.length ? current[i] : 0;
            if (l > c) {
                return true;
            }
            if (l < c) {
                return false;
            }
        }
        return false;
    }

    static String findApkAsset(List<String> assetNames, String flavor) {
        for (String name : assetNames) {
            if (name.startsWith("RadioDroid-")
                    && name.contains("-" + flavor + "-")
                    && name.endsWith(".apk")) {
                return name;
            }
        }
        return null;
    }

    private static int[] parseVersion(String version) {
        if (version == null) {
            return new int[0];
        }
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        String[] parts = v.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            int end = 0;
            while (end < part.length() && Character.isDigit(part.charAt(end))) {
                end++;
            }
            try {
                result[i] = end > 0 ? Integer.parseInt(part.substring(0, end)) : 0;
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }
}
