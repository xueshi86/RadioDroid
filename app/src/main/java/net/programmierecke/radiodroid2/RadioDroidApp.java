package net.programmierecke.radiodroid2;

import android.app.UiModeManager;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.multidex.MultiDexApplication;
import androidx.preference.PreferenceManager;

import com.squareup.picasso.OkHttp3Downloader;
import com.squareup.picasso.Picasso;

import net.programmierecke.radiodroid2.alarm.RadioAlarmManager;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.history.TrackHistoryRepository;
import net.programmierecke.radiodroid2.players.mpd.MPDClient;
import net.programmierecke.radiodroid2.service.ConnectivityChecker;
import net.programmierecke.radiodroid2.service.DatabaseUpdateManager;
import net.programmierecke.radiodroid2.service.DatabaseUpdateWorker;
import net.programmierecke.radiodroid2.service.StationIconCache;
import net.programmierecke.radiodroid2.station.live.metadata.TrackMetadataSearcher;
import net.programmierecke.radiodroid2.proxy.ProxySettings;
import net.programmierecke.radiodroid2.recording.RecordingsManager;
import net.programmierecke.radiodroid2.utils.TvChannelManager;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class RadioDroidApp extends MultiDexApplication {

    private HistoryManager historyManager;
    private FavouriteManager favouriteManager;
    private RecordingsManager recordingsManager;
    private FallbackStationsManager fallbackStationsManager;
    private RadioAlarmManager alarmManager;
    private TvChannelManager tvChannelManager;

    private TrackHistoryRepository trackHistoryRepository;

    private MPDClient mpdClient;

    private CastHandler castHandler;

    private TrackMetadataSearcher trackMetadataSearcher;

    private ConnectionPool connectionPool;
    private OkHttpClient httpClient;

    private Interceptor testsInterceptor;

    public class UserAgentInterceptor implements Interceptor {

        private final String userAgent;

        public UserAgentInterceptor(String userAgent) {
            this.userAgent = userAgent;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request originalRequest = chain.request();
            Request requestWithUserAgent = originalRequest.newBuilder()
                    .header("User-Agent", userAgent)
                    .build();
            return chain.proceed(requestWithUserAgent);
        }
    }

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        GoogleProviderHelper.use(getBaseContext());

        connectionPool = new ConnectionPool();

        rebuildHttpClient();

        Picasso.Builder builder = new Picasso.Builder(this);
        builder.downloader(new OkHttp3Downloader(newHttpClientForPicasso()));
        Picasso picassoInstance = builder.build();
        Picasso.setSingletonInstance(picassoInstance);

        CountryCodeDictionary.getInstance().load(this);
        CountryFlagsLoader.getInstance();

        historyManager = new HistoryManager(this);
        favouriteManager = new FavouriteManager(this);
        fallbackStationsManager = new FallbackStationsManager(this);
        recordingsManager = new RecordingsManager();
        alarmManager = new RadioAlarmManager(this);

        UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        if (uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            tvChannelManager = new TvChannelManager(this);
            favouriteManager.addStationUpdateListener(tvChannelManager);
        }

        trackHistoryRepository = new TrackHistoryRepository(this);

        // 清理过期的半永久图标缓存
        StationIconCache.getInstance(this).cleanExpiredSemiPermanentCache();

        mpdClient = new MPDClient(this);

        castHandler = new CastHandler();

        trackMetadataSearcher = new TrackMetadataSearcher(httpClient);

        recordingsManager.updateRecordingsList();

        maybeAutoIncrementalUpdate();
    }

    /**
     * 启动自动增量同步：距上次更新超过 24h 且满足网络条件时静默触发。
     * 无增量水位（从未全量更新）或已有更新任务时不触发。
     */
    private void maybeAutoIncrementalUpdate() {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            if (!prefs.getBoolean("auto_incremental_update", true)) {
                return;
            }
            if (prefs.getBoolean("auto_incremental_wifi_only", false)) {
                if (ConnectivityChecker.getCurrentConnectionType(this) != ConnectivityChecker.ConnectionType.NOT_METERED) {
                    return;
                }
            }
            // DB 检查（getDatabaseUpdateTime/hasIncrementalWatermark 为同步 Room 查询）须在后台线程，
            // 主线程调用会被 Room 禁令抛异常，导致自动增量永远不触发
            new Thread(() -> {
                try {
                    RadioStationRepository repository = RadioStationRepository.getInstance(this);
                    long lastUpdate = repository.getDatabaseUpdateTime();
                    long elapsed = System.currentTimeMillis() - lastUpdate;
                    if (lastUpdate > 0 && elapsed < 24L * 60 * 60 * 1000) {
                        return; // 24h 内已同步
                    }
                    if (!repository.hasIncrementalWatermark(this)) {
                        return; // 无水位：需先全量更新
                    }
                    if (DatabaseUpdateWorker.isUpdating(this)) {
                        return;
                    }
                    DatabaseUpdateManager.startIncrementalUpdate(this);
                    Log.d("RadioDroidApp", "Auto incremental update scheduled");
                } catch (Exception e) {
                    Log.w("RadioDroidApp", "Auto incremental update skipped: " + e.getMessage());
                }
            }, "AutoIncrementalUpdate").start();
        } catch (Exception e) {
            Log.w("RadioDroidApp", "Auto incremental update skipped: " + e.getMessage());
        }
    }

    public void setTestsInterceptor(Interceptor testsInterceptor) {
        this.testsInterceptor = testsInterceptor;
    }

    public void rebuildHttpClient() {
        OkHttpClient.Builder builder = newHttpClient()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(new UserAgentInterceptor("RadioDroid2/" + BuildConfig.VERSION_NAME));

        httpClient = builder.build();
    }

    public FallbackStationsManager getFallbackStationsManager() {
        return fallbackStationsManager;
    }
   
    public HistoryManager getHistoryManager() {
        return historyManager;
    }

    public FavouriteManager getFavouriteManager() {
        return favouriteManager;
    }

    public RecordingsManager getRecordingsManager() {
        return recordingsManager;
    }

    public RadioAlarmManager getAlarmManager() {
        return alarmManager;
    }

    public TrackHistoryRepository getTrackHistoryRepository() {
        return trackHistoryRepository;
    }

    public MPDClient getMpdClient() {
        return mpdClient;
    }

    public CastHandler getCastHandler() {
        return castHandler;
    }

    public TrackMetadataSearcher getTrackMetadataSearcher() {
        return trackMetadataSearcher;
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    public OkHttpClient.Builder newHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder().connectionPool(connectionPool);

        if (testsInterceptor != null) {
            builder.addInterceptor(testsInterceptor);
        }

        if (!setCurrentOkHttpProxy(builder)) {
            Toast toast = Toast.makeText(this, getResources().getString(R.string.ignore_proxy_settings_invalid), Toast.LENGTH_SHORT);
            toast.show();
        }
        builder = Utils.enableTls12OnPreLollipop(builder);
        // 添加 ISRG Root X1 (Let's Encrypt 根证书)，解决 Android 5.1 等旧版本
        // 系统 TrustStore 不包含该证书导致 SSL 握手失败的问题
        builder = Utils.addIsrgRootX1(builder, this);
        return builder;
    }

    public OkHttpClient.Builder newHttpClientWithoutProxy() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder().connectionPool(connectionPool);

        if (testsInterceptor != null) {
            builder.addInterceptor(testsInterceptor);
        }

        builder = Utils.enableTls12OnPreLollipop(builder);
        builder = Utils.addIsrgRootX1(builder, this);
        return builder;
    }

    public boolean setCurrentOkHttpProxy(@NonNull OkHttpClient.Builder builder) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        ProxySettings proxySettings = ProxySettings.fromPreferences(sharedPref);
        if (proxySettings != null) {
            if (!Utils.setOkHttpProxy(builder, proxySettings)) {
                // proxy settings are not valid
                return false;
            }
        }
        return true;
    }

    private OkHttpClient newHttpClientForPicasso() {
        File cache = new File(getCacheDir(), "picasso-cache");
        if (!cache.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cache.mkdirs();
        }

        int CACHE_MAX_SIZE = 250 * 1024 * 1024;

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .addInterceptor(new UserAgentInterceptor("RadioDroid2/" + BuildConfig.VERSION_NAME))
                .addNetworkInterceptor(new okhttp3.Interceptor() {
                    @NonNull
                    @Override
                    public okhttp3.Response intercept(@NonNull Chain chain) throws IOException {
                        okhttp3.Response response = chain.proceed(chain.request());
                        okhttp3.Headers.Builder headersBuilder = response.headers().newBuilder();
                        String cacheControl = response.header("Cache-Control");
                        if (cacheControl == null || cacheControl.contains("max-age=0") || cacheControl.contains("no-cache") || cacheControl.contains("no-store")) {
                            headersBuilder.set("Cache-Control", "public, max-age=604800");
                        }
                        return response.newBuilder().headers(headersBuilder.build()).build();
                    }
                })
                .cache(new Cache(cache, CACHE_MAX_SIZE));

        if (testsInterceptor != null) {
            builder.addInterceptor(testsInterceptor);
        }

        setCurrentOkHttpProxy(builder);

        return builder.build();
    }
}
