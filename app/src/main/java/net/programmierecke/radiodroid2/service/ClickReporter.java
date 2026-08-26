package net.programmierecke.radiodroid2.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import net.programmierecke.radiodroid2.RadioBrowserServerManager;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 播放点击上报（radio-browser click 端点）。
 *
 * 规则：
 * - 播放成功（进入 Playing 首次分支）时触发；
 * - 同一电台 5 秒冷却，防止缓冲抖动/重复播放刷屏；
 * - 若本次播放已通过 getRealStationLink（json/url/&lt;uuid&gt;，服务端已计一次点击）
 *   解析过真实链接，则跳过网络上报（防双计）；
 * - 上报走异步 OkHttp enqueue，不阻塞播放线程；失败静默（仅 Debug 日志）；
 * - 上报成功后本地 clickcount+1（同步间隔内排序即时生效，下次同步以服务器值为准）；
 * - 隐私开关 report_click_counts 关闭时完全不发起网络请求。
 */
public class ClickReporter {

    private static final String TAG = "AMA-Click";
    private static final long COOLDOWN_MS = 5000L;

    private static final Map<String, Long> lastReportedAt = new ConcurrentHashMap<>();

    private ClickReporter() {
    }

    /**
     * 播放成功回调。线程安全，可安全从播放线程调用。
     */
    public static void report(Context context, DataRadioStation station) {
        if (station == null || station.StationUuid == null || station.StationUuid.isEmpty()) {
            return;
        }
        if (context == null) {
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        boolean enabled = prefs.getBoolean("report_click_counts", true);
        if (!enabled) {
            Log.d(TAG, "click reporting disabled by preference, skip: " + station.StationUuid);
            return;
        }

        // 防双计：本次播放已通过 click 端点解析过链接（服务端已计数）
        if (Utils.lastResolveUsedClickEndpoint) {
            Utils.lastResolveUsedClickEndpoint = false;
            Log.d(TAG, "click already counted via resolve endpoint, skip: " + station.StationUuid);
            return;
        }

        // 5 秒冷却
        long now = System.currentTimeMillis();
        Long last = lastReportedAt.get(station.StationUuid);
        if (last != null && now - last < COOLDOWN_MS) {
            Log.d(TAG, "click cooldown active, skip: " + station.StationUuid);
            return;
        }
        lastReportedAt.put(station.StationUuid, now);

        String currentServer = RadioBrowserServerManager.getCurrentServer(context);
        if (currentServer == null) {
            Log.d(TAG, "no server available, skip click report");
            return;
        }

        final String stationUuid = station.StationUuid;
        String url = RadioBrowserServerManager.constructEndpoint(currentServer, "json/url/" + stationUuid);
        Request request = new Request.Builder().url(url).get().build();

        try {
            RadioDroidApp app = (RadioDroidApp) context.getApplicationContext();
            app.getHttpClient().newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    // 失败静默：不重试，不打扰用户
                    Log.d(TAG, "click report failed (silent): " + stationUuid + " err=" + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful()) {
                            incrementLocalClick(context, stationUuid);
                            Log.d(TAG, "click reported OK: " + stationUuid);
                        } else {
                            Log.d(TAG, "click report HTTP " + response.code() + ": " + stationUuid);
                        }
                    } finally {
                        if (response != null) {
                            response.close();
                        }
                    }
                }
            });
        } catch (Exception e) {
            Log.d(TAG, "click report exception (silent): " + e.getMessage());
        }
    }

    private static void incrementLocalClick(Context context, String stationUuid) {
        RadioStationRepository repository = RadioStationRepository.getInstance(context);
        if (repository != null) {
            repository.incrementStationClickCount(stationUuid);
        }
    }
}
