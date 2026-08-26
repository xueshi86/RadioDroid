package net.programmierecke.radiodroid2;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by segler on 15.02.18.
 */

public class RadioBrowserServerManager {
    private static final String TAG = "RadioBrowserServerMgr";
    static String currentServer = null;
    static String[] serverList = null;

    // 持久化 key：记录最近成功服务器，重启后优先使用
    private static final String PREF_CURRENT_SERVER = "radio_browser_current_server";
    private static final String PREF_CURRENT_SERVER_SAVED_AT = "radio_browser_current_server_saved_at";
    private static final long SERVER_RETEST_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000; // 7 天自动重测

    // 官方镜像静态兜底列表（DNS 解析失败时使用；不硬编码第三方私有镜像）
    private static final String[] STATIC_FALLBACK_SERVERS = {
            "de1.api.radio-browser.info",
            "de2.api.radio-browser.info",
            "fi1.api.radio-browser.info",
            "at1.api.radio-browser.info"
    };

    /**
     * Blocking: do dns request do get a list of all available servers
     */
    private static String[] doDnsServerListing() {
        Log.d("DNS", "doDnsServerListing()");
        Vector<String> listResult = new Vector<String>();
        try {
            // add all round robin servers one by one to select them separately
            InetAddress[] list = InetAddress.getAllByName("all.api.radio-browser.info");
            for (InetAddress item : list) {
                // do not use original variable, it could fall back to "all.api.radio-browser.info"
                String currentHostAddress = item.getHostAddress();
                InetAddress new_item = InetAddress.getByName(currentHostAddress);
                Log.i("DNS", "Found: " + new_item.toString() + " -> " + new_item.getCanonicalHostName());
                String name = item.getCanonicalHostName();
                if (!name.equals("all.api.radio-browser.info") && !name.equals(currentHostAddress)) {
                    Log.i("DNS", "Added entry: '" + name+"'");
                    listResult.add(name);
                }
            }
        } catch (UnknownHostException e) {
            Log.e(TAG, "Failed to resolve radio browser server hosts", e);
        }
        if (listResult.size() == 0){
            // should we inform people that their internet provider is not able to do reverse lookups? (= is shit)
            Log.w("DNS", "Fallback to de1.api.radio-browser.info because dns call did not work.");
            listResult.add("de1.api.radio-browser.info");
        }
        Log.d("DNS", "doDnsServerListing() Found servers: " + listResult.size());
        return listResult.toArray(new String[0]);
    }

    /**
     * Blocking: return current cached server list. Generate list if still null.
     */
    public static String[] getServerList(boolean forceRefresh){
        if (serverList == null || serverList.length == 0 || forceRefresh){
            serverList = doDnsServerListing();
        }
        return serverList;
    }

    /**
     * 按优先级去重合并候选服务器：最近成功服务器优先 → DNS 列表 → 官方静态兜底列表。
     */
    public static String[] getOrderedServerCandidates(Context context) {
        Set<String> ordered = new LinkedHashSet<>();
        String current = getCurrentServer(context);
        if (current != null) {
            ordered.add(current);
        }
        String[] dnsList = getServerList(false);
        if (dnsList != null) {
            for (String s : dnsList) {
                ordered.add(s);
            }
        }
        for (String s : STATIC_FALLBACK_SERVERS) {
            ordered.add(s);
        }
        return ordered.toArray(new String[0]);
    }

    private static SharedPreferences getPrefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    /**
     * Blocking: return current selected server. Select one, if there is no current server.
     * 有 Context 时优先读取上次持久化的成功服务器，实现跨进程重启级联记忆。
     */
    public static String getCurrentServer() {
        return getCurrentServer(null);
    }

    public static String getCurrentServer(Context context) {
        if (currentServer == null && context != null) {
            currentServer = getPrefs(context).getString(PREF_CURRENT_SERVER, null);
            if (currentServer != null) {
                Log.d("SRV", "Restored persisted server: " + currentServer);
            }
        }
        if (currentServer == null){
            String[] serverList = getServerList(false);
            if (serverList.length > 0){
                Random rand = new Random();
                currentServer = serverList[rand.nextInt(serverList.length)];
                Log.d("SRV", "Selected new default server: " + currentServer);
            }else{
                Log.e("SRV", "no servers found");
            }
        }
        return currentServer;
    }

    /**
     * Set new server as current
     */
    public static void setCurrentServer(String newServer){
        currentServer = newServer;
    }

    /**
     * Set new server as current and persist（成功请求后调用，供下次启动复用）。
     */
    public static void setCurrentServer(String newServer, Context context){
        currentServer = newServer;
        if (context != null) {
            getPrefs(context).edit()
                    .putString(PREF_CURRENT_SERVER, newServer)
                    .putLong(PREF_CURRENT_SERVER_SAVED_AT, System.currentTimeMillis())
                    .apply();
            Log.d("SRV", "Persisted server: " + newServer);
        }
    }

    /**
     * 持久化服务器超过 7 天未重测时返回 true，触发自动重新测速。
     */
    public static boolean shouldRetestServer(Context context) {
        if (context == null) {
            return false;
        }
        long savedAt = getPrefs(context).getLong(PREF_CURRENT_SERVER_SAVED_AT, 0L);
        return savedAt == 0L || System.currentTimeMillis() - savedAt > SERVER_RETEST_INTERVAL_MS;
    }

    /**
     * 级联专用短超时客户端：避免多服务器级联链累计过长延迟。
     */
    public static OkHttpClient buildFailoverClient(Context context) {
        RadioDroidApp radioDroidApp = (RadioDroidApp) context.getApplicationContext();
        return radioDroidApp.getHttpClient().newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 级联下载：最近成功服务器 → DNS 列表 → 静态兜底列表，成功即回写并持久化当前服务器。
     * 全部失败返回 null（调用方静默处理）。
     */
    public static String downloadWithFailover(Context context, String path, boolean useHttps) {
        OkHttpClient client = buildFailoverClient(context);
        String[] candidates = getOrderedServerCandidates(context);

        for (String server : candidates) {
            String endpoint = constructEndpoint(server, path, useHttps);
            Response response = null;
            try {
                Request request = new Request.Builder().url(endpoint).get().build();
                response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    setCurrentServer(server, context);
                    Log.d(TAG, "downloadWithFailover SUCCESS: " + server + " path=" + path);
                    return body;
                }
                Log.w(TAG, "downloadWithFailover HTTP " + response.code() + " on " + server);
            } catch (IOException e) {
                Log.w(TAG, "downloadWithFailover FAILED: " + server + " err=" + e.getMessage());
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }
        Log.e(TAG, "downloadWithFailover ALL SERVERS FAILED: " + path);
        return null;
    }

    /**
     * Construct full url from server and path
     * 默认使用 HTTPS：radio-browser API 调用必须加密传输，
     * 否则中间人可篡改返回的流地址、搜索结果，窃听用户搜索内容
     */
    public static String constructEndpoint(String server, String path){
        return "https://" + server + "/" + path;
    }

    /**
     * Construct full url from server and path with protocol
     */
    public static String constructEndpoint(String server, String path, boolean useHttps){
        String protocol = useHttps ? "https://" : "http://";
        return protocol + server + "/" + path;
    }
    
    /**
     * Test connection speed for specific server and protocol
     */
    public static long testConnectionSpeed(Context context, String server, boolean useHttps) {
        RadioDroidApp radioDroidApp = (RadioDroidApp) context.getApplicationContext();
        OkHttpClient httpClient = radioDroidApp.getHttpClient();
        
        String endpoint = constructEndpoint(server, "json/stats", useHttps);
        
        try {
            long startTime = System.currentTimeMillis();
            Request request = new Request.Builder()
                    .url(endpoint)
                    .get()
                    .build();
            
            Response response = httpClient.newCall(request).execute();
            long endTime = System.currentTimeMillis();
            
            if (response.isSuccessful()) {
                return endTime - startTime;
            }
        } catch (IOException e) {
            Log.w("SRV", "Connection test failed for " + (useHttps ? "HTTPS" : "HTTP") + "://" + server, e);
        }
        
        return Long.MAX_VALUE; // Return a very large value to indicate failure
    }
    
    /**
     * Test connection speeds for all servers and both protocols
     */
    public static Map<String, Long> testAllConnectionSpeeds(Context context) {
        Map<String, Long> results = new HashMap<>();
        
        // Get all available servers from DNS
        String[] servers = getServerList(true);
        
        for (String server : servers) {
            // Test HTTP
            long httpTime = testConnectionSpeed(context, server, false);
            results.put(server + "_HTTP", httpTime);
            
            // Test HTTPS
            long httpsTime = testConnectionSpeed(context, server, true);
            results.put(server + "_HTTPS", httpsTime);
            
            Log.d("SRV", "Connection test - " + server + " HTTP: " + 
                  (httpTime == Long.MAX_VALUE ? "Failed" : httpTime + "ms") + 
                  ", HTTPS: " + (httpsTime == Long.MAX_VALUE ? "Failed" : httpsTime + "ms"));
        }
        
        return results;
    }
    
    /**
     * Get the fastest server and protocol based on connection tests
     */
    public static ServerInfo getFastestServer(Context context) {
        Map<String, Long> results = testAllConnectionSpeeds(context);
        
        String fastestKey = null;
        long fastestTime = Long.MAX_VALUE;
        
        for (Map.Entry<String, Long> entry : results.entrySet()) {
            if (entry.getValue() < fastestTime) {
                fastestTime = entry.getValue();
                fastestKey = entry.getKey();
            }
        }
        
        if (fastestKey != null && fastestTime < Long.MAX_VALUE) {
            String[] parts = fastestKey.split("_");
            String server = parts[0];
            boolean useHttps = parts[1].equals("HTTPS");
            
            Log.i("SRV", "Fastest connection: " + fastestKey + " with " + fastestTime + "ms");
            return new ServerInfo(server, useHttps);
        }
        
        // Fallback to default if all tests failed
        Log.w("SRV", "All connection tests failed, using default server");
        return new ServerInfo(getCurrentServer(), false);
    }
    
    /**
     * Server info class to hold server name and protocol preference
     */
    public static class ServerInfo {
        public String server;
        public boolean useHttps;
        
        public ServerInfo(String server, boolean useHttps) {
            this.server = server;
            this.useHttps = useHttps;
        }
    }
}
