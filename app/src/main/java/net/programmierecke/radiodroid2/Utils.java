package net.programmierecke.radiodroid2;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Icon;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.webkit.MimeTypeMap;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.mikepenz.iconics.IconicsColor;
import com.mikepenz.iconics.IconicsDrawable;
import com.mikepenz.iconics.IconicsSize;
import com.mikepenz.iconics.typeface.IIcon;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

import net.programmierecke.radiodroid2.players.PlayStationTask;
import net.programmierecke.radiodroid2.players.selector.PlayerSelectorDialog;
import net.programmierecke.radiodroid2.players.selector.PlayerType;
import net.programmierecke.radiodroid2.service.ConnectivityChecker;
import net.programmierecke.radiodroid2.service.MediaSessionCallback;
import net.programmierecke.radiodroid2.service.PlayerServiceUtil;
import net.programmierecke.radiodroid2.station.DataRadioStation;

import net.programmierecke.radiodroid2.proxy.ProxySettings;
import net.programmierecke.radiodroid2.utils.CompositeX509TrustManager;
import net.programmierecke.radiodroid2.utils.Tls12SocketFactory;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Authenticator;
import okhttp3.ConnectionSpec;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.TlsVersion;

public class Utils {
    private static int loadIcons = -1;

    public static int parseIntWithDefault(String number, int defaultVal) {
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    public static String getCacheFile(Context ctx, String theURI) {
        StringBuilder chaine = new StringBuilder("");
        try {
            String aFileName = theURI.toLowerCase().replace("http://", "");
            aFileName = aFileName.toLowerCase().replace("https://", "");
            aFileName = sanitizeName(aFileName);

            File file = new File(ctx.getCacheDir().getAbsolutePath() + "/" + aFileName);
            Date lastModDate = new Date(file.lastModified());

            Date now = new Date();
            long millis = now.getTime() - file.lastModified();
            long secs = millis / 1000;
            long mins = secs / 60;
            long hours = mins / 60;

            if (BuildConfig.DEBUG) {
                Log.d("UTIL", "File last modified : " + lastModDate.toString() + " secs=" + secs + "  mins=" + mins + " hours=" + hours);
            }

            if (hours < 1) {
                FileInputStream aStream = new FileInputStream(file);
                BufferedReader rd = new BufferedReader(new InputStreamReader(aStream));
                String line;
                while ((line = rd.readLine()) != null) {
                    chaine.append(line);
                }
                rd.close();
                if (BuildConfig.DEBUG) {
                    Log.d("UTIL", "used cache for:" + theURI);
                }
                return chaine.toString();
            }
            if (BuildConfig.DEBUG) {
                Log.d("UTIL", "do not use cache, because too old:" + theURI);
            }
            return null;
        } catch (Exception e) {
            Log.e("UTIL", "getCacheFile() " + e);
        }
        return null;
    }

    public static void writeFileCache(Context ctx, String theURI, String content) {
        try {
            String aFileName = theURI.toLowerCase().replace("http://", "");
            aFileName = aFileName.toLowerCase().replace("https://", "");
            aFileName = sanitizeName(aFileName);

            File f = new File(ctx.getCacheDir() + "/" + aFileName);
            FileOutputStream aStream = new FileOutputStream(f);
            aStream.write(content.getBytes("utf-8"));
            aStream.close();
        } catch (Exception e) {
            Log.e("UTIL", "writeFileCache() could not write to cache file for:" + theURI);
        }
    }

    private static String downloadFeed(OkHttpClient httpClient, Context ctx, String theURI, boolean forceUpdate, Map<String, String> dictParams) {
        Log.i("DOWN", "Url=" + theURI);
        if (!forceUpdate) {
            String cache = getCacheFile(ctx, theURI);
            if (cache != null) {
                return cache;
            }
        }
        Log.i("DOWN", "Url=" + theURI + " (not cached)");

        try {
            HttpUrl url = HttpUrl.parse(theURI);
            Request.Builder requestBuilder = new Request.Builder().url(url);

            if (dictParams != null) {
                MediaType jsonMediaType = MediaType.parse("application/json; charset=utf-8");

                Gson gson = new Gson();
                String json = gson.toJson(dictParams);

                okhttp3.RequestBody requestBody = RequestBody.create(jsonMediaType, json);

                requestBuilder.post(requestBody);
            } else {
                requestBuilder.get();
            }

            Request request = requestBuilder.build();
            okhttp3.Response response = httpClient.newCall(request).execute();

            String responseStr = response.body().string();

            if (!response.isSuccessful()) {
                Log.e("UTIL", "HTTP请求失败: URL=" + theURI + ", 状态码=" + response.code() + ", 消息=" + response.message() + ", 响应=" + responseStr);
                return null;
            }

            writeFileCache(ctx, theURI, responseStr);
            if (BuildConfig.DEBUG) {
                Log.d("UTIL", "wrote cache file for:" + theURI);
            }
            return responseStr;
        } catch (java.net.SocketTimeoutException e) {
            Log.e("UTIL", "网络请求超时: URL=" + theURI + ", 错误=" + e.getMessage());
        } catch (java.net.UnknownHostException e) {
            Log.e("UTIL", "DNS解析失败: URL=" + theURI + ", 错误=" + e.getMessage());
        } catch (java.net.ConnectException e) {
            Log.e("UTIL", "连接失败: URL=" + theURI + ", 错误=" + e.getMessage());
        } catch (java.io.IOException e) {
            Log.e("UTIL", "IO错误: URL=" + theURI + ", 错误=" + e.getMessage());
        } catch (Exception e) {
            Log.e("UTIL", "downloadFeed() 未知错误: URL=" + theURI + ", 错误类型=" + e.getClass().getSimpleName() + ", 错误=" + e.getMessage());
        }

        return null;
    }

    public static String downloadFeedRelative(OkHttpClient httpClient, Context ctx, String theRelativeUri, boolean forceUpdate, Map<String, String> dictParams) {
        // try current server for download（优先读持久化服务器）
        String currentServer = RadioBrowserServerManager.getCurrentServer(ctx);
        if (currentServer == null) {
            return null;
        }

        String endpoint = RadioBrowserServerManager.constructEndpoint(currentServer, theRelativeUri);
        String result = downloadFeed(httpClient, ctx, endpoint, forceUpdate, dictParams);
        if (result != null) {
            RadioBrowserServerManager.setCurrentServer(currentServer, ctx);
            return result;
        }

        // get a list of all servers（DNS 列表 + 官方静态兜底，去重）
        String[] serverList = RadioBrowserServerManager.getOrderedServerCandidates(ctx);

        // try all other servers for download
        for (String newServer : serverList) {
            if (newServer.equals(currentServer)) {
                continue;
            }

            endpoint = RadioBrowserServerManager.constructEndpoint(newServer, theRelativeUri);
            result = downloadFeed(httpClient, ctx, endpoint, forceUpdate, dictParams);
            if (result != null) {
                // set the working server as new current server and persist
                RadioBrowserServerManager.setCurrentServer(newServer, ctx);
                return result;
            }
        }

        return null;
    }
    
    /**
     * Download from a specific server with specified protocol
     */
    public static String downloadFeedFromServer(OkHttpClient httpClient, Context ctx, String server, String theRelativeUri, boolean useHttps, boolean forceUpdate, Map<String, String> dictParams) {
        String endpoint = RadioBrowserServerManager.constructEndpoint(server, theRelativeUri, useHttps);
        return downloadFeed(httpClient, ctx, endpoint, forceUpdate, dictParams);
    }

    /**
     * 防双计标志：getRealStationLink 成功调用 click 端点（json/url/<uuid>，服务端已计一次点击）后置位，
     * 播放成功回调（ClickReporter）消费后跳过网络上报，避免同一播放被计两次。
     */
    public static volatile boolean lastResolveUsedClickEndpoint = false;

    public static String getRealStationLink(OkHttpClient httpClient, Context ctx, String stationId) {
        Log.i("UTIL", "StationUUID:" + stationId);
        String result = Utils.downloadFeedRelative(httpClient, ctx, "json/url/" + stationId, true, null);
        if (result != null) {
            // 服务端 click 端点已计一次点击，置位防双计标志
            Utils.lastResolveUsedClickEndpoint = true;
            Log.i("UTIL", result);
            JSONObject jsonObj;
            try {
                jsonObj = new JSONObject(result);
                return jsonObj.getString("url");
            } catch (Exception e) {
                Log.e("UTIL", "getRealStationLink() " + e);
            }
        }
        return null;
    }

    @Deprecated
    public static DataRadioStation getStationById(OkHttpClient httpClient, Context ctx, String stationId) {
        Log.w("UTIL", "Search by id:" + stationId);
        String result = Utils.downloadFeed(httpClient, ctx, "json/stations/byid/" + stationId, true, null);
        if (result != null) {
            try {
                List<DataRadioStation> list = DataRadioStation.DecodeJson(result);
                if (list != null) {
                    if (list.size() == 1) {
                        return list.get(0);
                    }
                    Log.e("UTIL", "stations by id did have length:" + list.size());
                }
            } catch (Exception e) {
                Log.e("UTIL", "getStationByid() " + e);
            }
        }
        return null;
    }

    public static DataRadioStation getStationByUuid(OkHttpClient httpClient, Context ctx, String stationUuid) {
        Log.w("UTIL", "Search by uuid:" + stationUuid);
        String result = Utils.downloadFeedRelative(httpClient, ctx, "json/stations/byuuid/" + stationUuid, true, null);
        if (result != null) {
            try {
                List<DataRadioStation> list = DataRadioStation.DecodeJson(result);
                if (list != null) {
                    if (list.size() == 1) {
                        return list.get(0);
                    }
                    Log.e("UTIL", "stations by uuid did have length:" + list.size());
                }
            } catch (Exception e) {
                Log.e("UTIL", "getStationByUuid() " + e);
            }
        }
        return null;
    }

    public static List<DataRadioStation> getStationsByUuid(OkHttpClient httpClient, Context ctx, Iterable<String> listUUids) {
        String uuids = TextUtils.join(",", listUUids);
        Log.d("UTIL", "Search by uuid for items");
        HashMap<String, String> p = new HashMap<String, String>();
        p.put("uuids", uuids);
        String result = Utils.downloadFeedRelative(httpClient, ctx, "json/stations/byuuid", true, p);
        if (result != null) {
            try {
                List<DataRadioStation> list = DataRadioStation.DecodeJson(result);
                if (list != null) {
                    return list;
                }else{
                    Log.e("UTIL", "stations by uuid was null");
                }
            } catch (Exception e) {
                Log.e("UTIL", "getStationsByUuid() " + e);
            }
        }
        return null;
    }

    public static @Nullable
    DataRadioStation getCurrentOrLastStation(@NonNull Context ctx) {
        DataRadioStation station = PlayerServiceUtil.getCurrentStation();
        if (station == null) {
            RadioDroidApp radioDroidApp = (RadioDroidApp) ctx.getApplicationContext();
            HistoryManager historyManager = radioDroidApp.getHistoryManager();
            station = historyManager.getFirst();
        }

        return station;
    }

    public static void showMpdServersDialog(final RadioDroidApp radioDroidApp, final FragmentManager fragmentManager, @Nullable final DataRadioStation station) {
        Fragment oldFragment = fragmentManager.findFragmentByTag(PlayerSelectorDialog.FRAGMENT_TAG);
        if (oldFragment != null && oldFragment.isVisible()) {
            return;
        }

        PlayerSelectorDialog playerSelectorDialogFragment = new PlayerSelectorDialog(radioDroidApp.getMpdClient(), station);
        playerSelectorDialogFragment.show(fragmentManager, PlayerSelectorDialog.FRAGMENT_TAG);
    }

    public static void showPlaySelection(final RadioDroidApp radioDroidApp, final DataRadioStation station, final FragmentManager fragmentManager) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(radioDroidApp);
        if (prefs.getBoolean("play_external", false)) {
            showMpdServersDialog(radioDroidApp, fragmentManager, station);
        } else {
            play(radioDroidApp, station);
        }
    }

    public static boolean urlIndicatesHlsStream(String streamUrl) {
        if (streamUrl == null || streamUrl.isEmpty()) {
            return false;
        }
        // Match .m3u8 at the end of path (before query/fragment)
        final Pattern m3u8Pattern = Pattern.compile(".*\\.m3u8([#?\\s].*)?$", Pattern.CASE_INSENSITIVE);
        if (m3u8Pattern.matcher(streamUrl).matches()) {
            return true;
        }
        // Match /hls/ in the URL path
        if (streamUrl.toLowerCase().contains("/hls/")) {
            return true;
        }
        // Match .hls extension at the end of path
        final Pattern hlsExtPattern = Pattern.compile(".*\\.hls([#?\\s].*)?$", Pattern.CASE_INSENSITIVE);
        return hlsExtPattern.matcher(streamUrl).matches();
    }

    public static void play(final RadioDroidApp radioDroidApp, final DataRadioStation station) {
        PlayerServiceUtil.play(station);
    }

    public static boolean shouldLoadIcons(final Context context) {
        switch (loadIcons) {
            case -1:
                if (PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext()).getBoolean("load_icons", true)) {
                    loadIcons = 1;
                    return true;
                } else {
                    loadIcons = 0;
                    return false;
                }
            case 0:
                return false;
            case 1:
                return true;
        }
        return false;
    }

    public static String getTheme(final Context context) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPref.getString("theme_name", context.getResources().getString(R.string.theme_light));
    }

    public static int getThemeResId(final Context context) {
        String selectedTheme = getTheme(context);
        if (selectedTheme.equals(context.getResources().getString(R.string.theme_dark)))
            return R.style.MyMaterialTheme_Dark;
        if (selectedTheme.equals(context.getResources().getString(R.string.theme_auto)))
            return isSystemInDarkTheme(context) ? R.style.MyMaterialTheme_Dark : R.style.MyMaterialTheme;
        else
            return R.style.MyMaterialTheme;
    }

    private static boolean isSystemInDarkTheme(final Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int nightModeFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
        }
        return false;
    }

    public static boolean isDarkTheme(final Context context) {
        return getThemeResId(context) == R.style.MyMaterialTheme_Dark;
    }

    /**
     * 系统是否允许播放动画（UI 美化 Phase 7）。
     * 「动画时长缩放」被设为 0（开发者选项里的关闭动画 / 减少动画）时返回 false，
     * 调用方应跳过纯装饰性的过渡动画，避免出现僵硬或空白的过渡帧。
     */
    public static boolean areAnimationsEnabled(final Context context) {
        if (context == null) {
            return true;
        }
        try {
            float scale = Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale != 0f;
        } catch (Exception e) {
            return true;
        }
    }

    public static int getAlertDialogThemeResId(final Context context) {
        int theme;
        if (getThemeResId(context) == R.style.MyMaterialTheme_Dark)
            theme = R.style.AlertTheme_Dark;
        else
            theme = R.style.AlertTheme;
        return theme;
    }
    
    public static int getBottomSheetDialogThemeResId(final Context context) {
        int theme;
        if (getThemeResId(context) == R.style.MyMaterialTheme_Dark)
            theme = R.style.BottomSheetDialogTheme_Dark;
        else
            theme = R.style.BottomSheetDialogTheme;
        return theme;
    }

    // ================= 预设配色（Issue #50） =================

    public static final String PREF_THEME_PRESET = "theme_preset";
    /** 默认预设：湖光青（全新安装时的默认配色方案） */
    public static final String THEME_PRESET_DEFAULT = "lake_teal";

    /**
     * 读取当前配色预设。返回的是与语言无关的稳定标识（见 @array/theme_preset_values），
     * 因此切换应用语言不会影响已选预设。
     */
    public static String getThemePreset(final Context context) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        String preset = sharedPref.getString(PREF_THEME_PRESET, THEME_PRESET_DEFAULT);
        return preset == null ? THEME_PRESET_DEFAULT : preset;
    }

    /** 当前预设 + 当前亮/暗主题所对应的配色覆盖样式 */
    public static int getThemePresetOverlayResId(final Context context) {
        return getThemePresetOverlayResId(getThemePreset(context), isDarkTheme(context));
    }

    /** 指定预设 + 指定亮/暗主题所对应的配色覆盖样式（供色卡预览等场景按值查询） */
    public static int getThemePresetOverlayResId(final String preset, boolean dark) {
        switch (preset == null ? THEME_PRESET_DEFAULT : preset) {
            case "lake_teal":
                return dark ? R.style.ThemePreset_LakeTeal_Dark : R.style.ThemePreset_LakeTeal;
            case "forest_green":
                return dark ? R.style.ThemePreset_ForestGreen_Dark : R.style.ThemePreset_ForestGreen;
            case "violet":
                return dark ? R.style.ThemePreset_Violet_Dark : R.style.ThemePreset_Violet;
            case "rose":
                return dark ? R.style.ThemePreset_Rose_Dark : R.style.ThemePreset_Rose;
            case "sunset_orange":
                return dark ? R.style.ThemePreset_SunsetOrange_Dark : R.style.ThemePreset_SunsetOrange;
            case "graphite":
                return dark ? R.style.ThemePreset_Graphite_Dark : R.style.ThemePreset_Graphite;
            case "pure_black":
                return dark ? R.style.ThemePreset_PureBlack_Dark : R.style.ThemePreset_PureBlack;
            case "classic_blue":
            default:
                return dark ? R.style.ThemePreset_ClassicBlue_Dark : R.style.ThemePreset_ClassicBlue;
        }
    }

    /**
     * 读取某套预设「主色 / 强调色」的实际色值，用于设置页色卡缩略图。
     * 直接解析 ThemePreset 覆盖样式，因此与主题真正生效的颜色始终一致，
     * 无需在 Java 里再维护一份色值表。
     * <p>
     * 注意不能直接写成 {@code context.getTheme().obtainStyledAttributes(styleResId, attrs)}：
     * 该重载会把「当前主题里已生效的值」当作基准，未被目标样式覆盖到的属性会拿到当前预设的颜色
     * （实测 8 套预设的强调色点会全部变成当前预设的强调色）。因此这里新建一个干净的 Theme，
     * 只叠加目标预设的覆盖样式，让两个属性都从该样式解析，结果与切换后的真实主题一致。
     */
    public static int[] getThemePresetColors(final Context context, final String preset, boolean dark) {
        Resources.Theme theme = context.getResources().newTheme();
        theme.applyStyle(getThemePresetOverlayResId(preset, dark), true);
        TypedArray ta = theme.obtainStyledAttributes(
                new int[]{R.attr.presetPrimaryColor, R.attr.presetAccentColor});
        try {
            return new int[]{ta.getColor(0, 0), ta.getColor(1, 0)};
        } finally {
            ta.recycle();
        }
    }

    /**
     * 在 setTheme() 之后、setContentView() 之前调用，把配色预设叠加到当前主题。
     * 对话框 / 底部弹窗主题会继承 Activity 主题，故无需单独调用。
     */
    public static void applyThemePreset(final Context context) {
        context.getTheme().applyStyle(getThemePresetOverlayResId(context), true);
    }

    /**
     * 把当前预设的主色同步到系统栏（状态栏 / 导航栏）。
     * <p>
     * 系统栏颜色仅在主题里声明（android:statusBarColor=?attr/presetPrimaryColor）时，
     * Activity 重建（应用内切换预设、旋转屏幕）会复用已存在的窗口装饰，
     * 系统栏会回落成基础主题色，因此需要在 onCreate 里显式同步一次。
     */
    public static void applyThemePresetToSystemBars(final Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        int presetPrimaryColor = getThemeColor(activity, R.attr.presetPrimaryColor);
        activity.getWindow().setStatusBarColor(presetPrimaryColor);
        activity.getWindow().setNavigationBarColor(presetPrimaryColor);
    }

    /** 解析当前主题中某个属性的颜色值（例如 ?attr/presetAccentColor） */
    public static int getThemeColor(final Context context, final int attrResId) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attrResId, value, true)) {
            return 0;
        }
        if (value.resourceId != 0) {
            return ContextCompat.getColor(context, value.resourceId);
        }
        return value.data;
    }

    public static boolean useCircularIcons(final Context context) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPref.getBoolean("circular_icons", false);
    }

    private static final ViewOutlineProvider CIRCLE_OUTLINE_PROVIDER = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setOval(0, 0, view.getWidth(), view.getHeight());
        }
    };

    /**
     * 圆形图标：API 21+ 直接把图标裁剪成圆形（背景与位图一起被裁掉，不受所在容器底色影响）；
     * 低版本回退到「透明圆环遮罩」方案（遮罩颜色按所在容器底色着色，可能存在微小色差）。
     */
    public static void applyCircularIcon(final ImageView imageView, final ImageView maskView) {
        if (imageView == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (maskView != null) {
                maskView.setVisibility(View.GONE);
            }
            imageView.setOutlineProvider(CIRCLE_OUTLINE_PROVIDER);
            imageView.setClipToOutline(true);
        } else if (maskView != null) {
            maskView.setVisibility(View.VISIBLE);
        }
        imageView.invalidate();
    }

    /** 关闭圆形图标：取消圆形裁剪并隐藏遮罩（条目复用时必须调用，否则会残留上一个条目的圆形外观）。 */
    public static void clearCircularIcon(final ImageView imageView, final ImageView maskView) {
        if (maskView != null) {
            maskView.setVisibility(View.GONE);
        }
        if (imageView != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                imageView.setClipToOutline(false);
            }
            imageView.invalidate();
        }
    }

    // Storage Permissions
    private static String[] PERMISSIONS_STORAGE = {
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    public static boolean verifyStoragePermissions(Activity activity, int request_id) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return true;
        }

        int permission = ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    activity,
                    PERMISSIONS_STORAGE,
                    request_id
            );
            return false;
        }

        return true;
    }

    public static boolean verifyStoragePermissions(Fragment fragment, int request_id) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return true;
        }

        int permission = ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            fragment.requestPermissions(PERMISSIONS_STORAGE, request_id);
            return false;
        }

        return true;
    }

    public static String getReadableBytes(double bytes) {
        String[] str = new String[]{"B", "KB", "MB", "GB", "TB"};
        for (String aStr : str) {
            if (bytes < 1024) {
                return String.format(Locale.getDefault(), "%1$,.1f %2$s", bytes, aStr);
            }
            bytes = bytes / 1024;
        }
        return String.format(Locale.getDefault(), "%1$,.1f %2$s", bytes * 1024, str[str.length - 1]);
    }

    public static String sanitizeName(String str) {
        return str.replaceAll("\\W+", "_").replaceAll("^_+", "").replaceAll("_+$", "");
    }

    // ISO 639-1 码 → RadioBrowser language 字段的英文全名（含 Android 旧码 iw/in/ji）
    private static final Map<String, String> ISO_LANGUAGE_TO_RADIOBROWSER_NAME;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("en", "english");
        m.put("zh", "chinese");
        m.put("de", "german");
        m.put("fr", "french");
        m.put("es", "spanish");
        m.put("it", "italian");
        m.put("pt", "portuguese");
        m.put("ru", "russian");
        m.put("ja", "japanese");
        m.put("ko", "korean");
        m.put("hi", "hindi");
        m.put("ar", "arabic");
        m.put("nl", "dutch");
        m.put("pl", "polish");
        m.put("tr", "turkish");
        m.put("sv", "swedish");
        m.put("no", "norwegian");
        m.put("nb", "norwegian");
        m.put("nn", "norwegian");
        m.put("da", "danish");
        m.put("fi", "finnish");
        m.put("el", "greek");
        m.put("cs", "czech");
        m.put("sk", "slovak");
        m.put("hu", "hungarian");
        m.put("ro", "romanian");
        m.put("bg", "bulgarian");
        m.put("uk", "ukrainian");
        m.put("be", "belarusian");
        m.put("sr", "serbian");
        m.put("hr", "croatian");
        m.put("bs", "bosnian");
        m.put("sl", "slovenian");
        m.put("mk", "macedonian");
        m.put("sq", "albanian");
        m.put("et", "estonian");
        m.put("lv", "latvian");
        m.put("lt", "lithuanian");
        m.put("th", "thai");
        m.put("vi", "vietnamese");
        m.put("id", "indonesian");
        m.put("in", "indonesian");
        m.put("ms", "malay");
        m.put("tl", "tagalog");
        m.put("he", "hebrew");
        m.put("iw", "hebrew");
        m.put("ji", "yiddish");
        m.put("yi", "yiddish");
        m.put("fa", "persian");
        m.put("ur", "urdu");
        m.put("bn", "bengali");
        m.put("ta", "tamil");
        m.put("te", "telugu");
        m.put("ml", "malayalam");
        m.put("kn", "kannada");
        m.put("mr", "marathi");
        m.put("gu", "gujarati");
        m.put("pa", "punjabi");
        m.put("si", "sinhala");
        m.put("ne", "nepali");
        m.put("my", "burmese");
        m.put("km", "khmer");
        m.put("lo", "lao");
        m.put("ka", "georgian");
        m.put("hy", "armenian");
        m.put("az", "azerbaijani");
        m.put("kk", "kazakh");
        m.put("uz", "uzbek");
        m.put("ky", "kyrgyz");
        m.put("tg", "tajik");
        m.put("tk", "turkmen");
        m.put("mn", "mongolian");
        m.put("sw", "swahili");
        m.put("am", "amharic");
        m.put("so", "somali");
        m.put("ha", "hausa");
        m.put("yo", "yoruba");
        m.put("ig", "igbo");
        m.put("zu", "zulu");
        m.put("xh", "xhosa");
        m.put("af", "afrikaans");
        m.put("eu", "basque");
        m.put("ca", "catalan");
        m.put("gl", "galician");
        m.put("is", "icelandic");
        m.put("ga", "irish");
        m.put("gd", "scottish gaelic");
        m.put("cy", "welsh");
        m.put("fy", "frisian");
        m.put("lb", "luxembourgish");
        m.put("mt", "maltese");
        ISO_LANGUAGE_TO_RADIOBROWSER_NAME = m;
    }

    /**
     * 系统语言 ISO 码转 RadioBrowser 的语言全名（库内 language 存 "english,german" 这类
     * 逗号分隔英文全名，ISO 码直接 = 匹配恒为空）。未映射的码原样返回，保持旧行为。
     */
    public static String getRadioBrowserLanguageName(String isoLanguageCode) {
        if (isoLanguageCode == null || isoLanguageCode.isEmpty()) {
            return isoLanguageCode;
        }
        String name = ISO_LANGUAGE_TO_RADIOBROWSER_NAME.get(isoLanguageCode.toLowerCase(Locale.ROOT));
        return name != null ? name : isoLanguageCode;
    }

    private static boolean isValidCountryCode(String code) {
        return code != null && code.trim().matches("[A-Za-z]{2}");
    }

    /**
     * 获取用户所在国家代码（ISO 3166-1 alpha-2，大写）。
     * Locale 未设置国家时（getCountry() 返回空串）依次回退到 SIM 卡国家、网络注册国家，
     * 均不可用时返回空串（调用方应跳过国家匹配，走语言兜底链）。
     */
    public static String getSystemCountryCode(@Nullable Context context) {
        String country = Locale.getDefault().getCountry();
        if (isValidCountryCode(country)) {
            return country.toUpperCase(Locale.ROOT);
        }
        if (context != null) {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                country = tm.getSimCountryIso();
                if (isValidCountryCode(country)) {
                    return country.trim().toUpperCase(Locale.ROOT);
                }
                country = tm.getNetworkCountryIso();
                if (isValidCountryCode(country)) {
                    return country.trim().toUpperCase(Locale.ROOT);
                }
            }
        }
        return "";
    }

    public static boolean hasWifiConnection(Context context) {
        ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        return mWifi.isConnected();
    }

    public static boolean hasAnyConnection(Context context) {
        ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = connManager.getActiveNetworkInfo();
        //should check null because in airplane mode it will be null
        return (netInfo != null && netInfo.isConnected());
    }

    public static boolean bottomNavigationEnabled(Context context) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPref.getBoolean("bottom_navigation", true);
    }

    public static String formatStringWithNamedArgs(String format, Map<String, String> args) {
        StringBuilder builder = new StringBuilder(format);
        for (Map.Entry<String, String> entry : args.entrySet()) {
            final String key = "${" + entry.getKey() + "}";
            int startIdx = 0;
            while (true) {
                final int keyIdx = builder.indexOf(key, startIdx);

                if (keyIdx == -1) {
                    break;
                }

                builder.replace(keyIdx, keyIdx + key.length(), entry.getValue());
                startIdx = keyIdx + entry.getValue().length();
            }
        }

        return builder.toString();
    }

    public static int themeAttributeToColor(int themeAttributeId, Context context, int fallbackColorId) {
        TypedValue outValue = new TypedValue();
        Resources.Theme theme = context.getTheme();
        boolean wasResolved = theme.resolveAttribute(themeAttributeId, outValue, true);
        if (wasResolved) {
            return outValue.resourceId == 0 ? outValue.data : ContextCompat.getColor(context, outValue.resourceId);
        } else {
            return fallbackColorId;
        }
    }

    public static int getIconColor(Context context) {
        return themeAttributeToColor(R.attr.menuTextColorDefault, context, Color.LTGRAY);
    }

    public static int getAccentColor(Context context) {
        return themeAttributeToColor(android.R.attr.colorPrimary, context, Color.LTGRAY);
    }

    /**
     * Add proxy to an okhttp builder.
     *
     * @return true if successful, false otherwise
     */
    public static boolean setOkHttpProxy(@NonNull OkHttpClient.Builder builder, @NonNull final ProxySettings proxySettings) {
        if (proxySettings.type == Proxy.Type.DIRECT) {
            java.net.Authenticator.setDefault(null);
            return true;
        }
        if (TextUtils.isEmpty(proxySettings.host)) {
            java.net.Authenticator.setDefault(null);
            return false;
        }
        if (proxySettings.port < 1 || proxySettings.port > 65535) {
            java.net.Authenticator.setDefault(null);
            return false;
        }
        InetSocketAddress proxyAddress = InetSocketAddress.createUnresolved(proxySettings.host, proxySettings.port);
        Proxy proxy = new Proxy(proxySettings.type, proxyAddress);

        builder.proxy(proxy);

        if (!proxySettings.login.isEmpty()) {
            final String login = proxySettings.login;
            final String password = proxySettings.password;

            if (proxySettings.type == Proxy.Type.SOCKS) {
                java.net.Authenticator.setDefault(new java.net.Authenticator() {
                    @Override
                    protected java.net.PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() == RequestorType.PROXY) {
                            return new java.net.PasswordAuthentication(login, password.toCharArray());
                        }
                        return null;
                    }
                });
            } else {
                java.net.Authenticator.setDefault(null);
            }

            Authenticator proxyAuthenticator = new Authenticator() {
                @Override
                public Request authenticate(Route route, Response response) throws IOException {
                    if (response.code() != 407) {
                        return null;
                    }
                    if (response.request().header("Proxy-Authorization") != null) {
                        return null;
                    }
                    String credential = Credentials.basic(login, password);
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                }
            };

            builder.proxyAuthenticator(proxyAuthenticator);
        } else {
            java.net.Authenticator.setDefault(null);
        }

        return true;
    }

    public static Uri resourceToUri(Resources resources, int resID) {
        return Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" +
                resources.getResourcePackageName(resID) + '/' +
                resources.getResourceTypeName(resID) + '/' +
                resources.getResourceEntryName(resID));
    }

    public static IconicsDrawable IconicsIcon(Context context, IIcon icon) {
        return new IconicsDrawable(context, icon).size(IconicsSize.TOOLBAR_ICON_SIZE).padding(IconicsSize.TOOLBAR_ICON_PADDING).color(IconicsColor.colorInt(getIconColor(context)));
    }

    public static String getMimeType(String url, String defaultMimeType) {
        String type = defaultMimeType;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        return type;
    }

    public static OkHttpClient.Builder enableTls12OnPreLollipop(OkHttpClient.Builder client) {
        if (Build.VERSION.SDK_INT >= 16 && Build.VERSION.SDK_INT < 22) {
            try {
                TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init((KeyStore)null);
                TrustManager[] tmList = trustManagerFactory.getTrustManagers();
                Log.i("OkHttpTLSCompat", "Found trustmanagers:"+tmList.length);
                X509TrustManager tm = (X509TrustManager)tmList[0];

                SSLContext sc = SSLContext.getInstance("TLSv1.2");
                sc.init(null, null, null);
                client.sslSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()), tm);

                ConnectionSpec cs = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2)
                        .build();

                List<ConnectionSpec> specs = new ArrayList<>();
                specs.add(cs);
                specs.add(ConnectionSpec.COMPATIBLE_TLS);
                specs.add(ConnectionSpec.CLEARTEXT);

                client.connectionSpecs(specs);
            } catch (Exception exc) {
                Log.e("OkHttpTLSCompat", "Error while setting TLS 1.2", exc);
            }
        }

        return client;
    }

    /**
     * 为 OkHttpClient 添加 ISRG Root X1 (Let's Encrypt 根证书) 到信任链。
     * <p>
     * Android 5.1 及更早版本的系统 TrustStore 不包含 ISRG Root X1（2015年6月发布），
     * 导致使用 Let's Encrypt 证书的服务器 SSL 握手失败。
     * 此方法创建一个组合式 TrustManager，系统 CA 优先验证，失败后尝试 ISRG Root X1。
     * 在所有 Android 版本上安全使用：有 ISRG Root X1 的设备由系统直接验证通过，
     * 没有的设备由额外的 TrustManager 兜底。
     *
     * @param client  OkHttpClient.Builder
     * @param context 用于加载 res/raw/isrg_root_x1.pem
     * @return 传入的 builder（链式调用）
     */
    public static OkHttpClient.Builder addIsrgRootX1(OkHttpClient.Builder client, Context context) {
        try {
            // 加载 ISRG Root X1 证书
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate isrgCert;
            try (java.io.InputStream is = context.getResources().openRawResource(
                    context.getResources().getIdentifier("isrg_root_x1", "raw", context.getPackageName()))) {
                isrgCert = cf.generateCertificate(is);
            }

            // 创建只包含 ISRG Root X1 的 KeyStore
            KeyStore isrgKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            isrgKeyStore.load(null, null);
            isrgKeyStore.setCertificateEntry("isrg-root-x1", isrgCert);

            // 创建 ISRG TrustManager
            TrustManagerFactory isrgTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            isrgTmf.init(isrgKeyStore);
            X509TrustManager isrgTm = null;
            for (TrustManager tm : isrgTmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    isrgTm = (X509TrustManager) tm;
                    break;
                }
            }
            if (isrgTm == null) {
                Log.e("IsrgRootX1", "Failed to find X509TrustManager for ISRG Root X1");
                return client;
            }

            // 获取系统 TrustManager
            TrustManagerFactory systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            systemTmf.init((KeyStore) null);
            X509TrustManager systemTm = null;
            for (TrustManager tm : systemTmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    systemTm = (X509TrustManager) tm;
                    break;
                }
            }
            if (systemTm == null) {
                Log.e("IsrgRootX1", "Failed to find system X509TrustManager");
                return client;
            }

            // 创建组合式 TrustManager
            CompositeX509TrustManager compositeTm = new CompositeX509TrustManager(systemTm, isrgTm);

            // 设置 SSLContext
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{compositeTm}, null);

            client.sslSocketFactory(sslContext.getSocketFactory(), compositeTm);

            Log.i("IsrgRootX1", "Successfully added ISRG Root X1 to trust store");
        } catch (Exception e) {
            Log.e("IsrgRootX1", "Failed to add ISRG Root X1", e);
        }
        return client;
    }

    /**
     * 创建电台快捷方式
     * @param context 上下文
     * @param station 电台对象
     * @param id 快捷方式ID
     * @return ShortcutInfo对象
     */
    public static ShortcutInfo createShortcutForStation(Context context, DataRadioStation station, int id) {
        if (Build.VERSION.SDK_INT >= 25) {
            // 使用默认图标创建快捷方式
            Intent playByUUIDintent = new Intent(MediaSessionCallback.ACTION_PLAY_STATION_BY_UUID, null, context, ActivityMain.class)
                    .putExtra(MediaSessionCallback.EXTRA_STATION_UUID, station.StationUuid);
            
            ShortcutInfo.Builder builder = new ShortcutInfo.Builder(context.getApplicationContext(), 
                    context.getPackageName() + "/" + station.StationUuid + "/" + id)
                    .setShortLabel(station.Name)
                    .setIntent(playByUUIDintent);
            
            // 如果有图标，使用图标
            if (station.hasIcon()) {
                // 这里简化处理，使用默认图标
                // 在实际应用中，可以使用异步加载图标
                builder.setIcon(Icon.createWithResource(context, R.drawable.ic_launcher));
            } else {
                builder.setIcon(Icon.createWithResource(context, R.drawable.ic_launcher));
            }
            
            return builder.build();
        }
        return null;
    }
}
