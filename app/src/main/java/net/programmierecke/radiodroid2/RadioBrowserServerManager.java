package net.programmierecke.radiodroid2;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Vector;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by segler on 15.02.18.
 */

public class RadioBrowserServerManager {
    static String currentServer = null;
    static String[] serverList = null;

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
            e.printStackTrace();
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
     * Blocking: return current selected server. Select one, if there is no current server.
     */
    public static String getCurrentServer() {
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
     * Construct full url from server and path
     */
    public static String constructEndpoint(String server, String path){
        return "http://" + server + "/" + path;
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
     * Test connection speeds for both servers and both protocols
     */
    public static Map<String, Long> testAllConnectionSpeeds(Context context) {
        Map<String, Long> results = new HashMap<>();
        
        // Test the two specified servers
        String[] servers = {"fi1.api.radio-browser.info", "de2.api.radio-browser.info"};
        
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
