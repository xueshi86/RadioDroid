package net.programmierecke.radiodroid2.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.TypedValue;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.squareup.picasso.Callback;
import com.squareup.picasso.NetworkPolicy;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.FavouriteManager;
import net.programmierecke.radiodroid2.IPlayerService;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.players.PlayState;
import net.programmierecke.radiodroid2.players.selector.PlayerType;
import net.programmierecke.radiodroid2.station.DataRadioStation;
import net.programmierecke.radiodroid2.station.live.ShoutcastInfo;
import net.programmierecke.radiodroid2.station.live.StreamLiveInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public class PlayerServiceUtil {

    private static final String TAG = "StationIcon";

    private static Context mainContext = null;
    private static boolean mBound;
    private static ServiceConnection serviceConnection;

    public static void startService(Context context) {
        if (mBound) return;

        Intent anIntent = new Intent(context, PlayerService.class);
        anIntent.putExtra(PlayerService.PLAYER_SERVICE_NO_NOTIFICATION_EXTRA, true);
        mainContext = context;
        serviceConnection = getServiceConnection();
        context.bindService(anIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        mBound = true;
    }

    public static void bindService(Context context) {
        if (mBound) return;

        mainContext = context;
        serviceConnection = getServiceConnection();
        Intent anIntent = new Intent(context, PlayerService.class);
        context.bindService(anIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        mBound = true;
    }

    private static void unBind(Context context) {
        try {
            context.unbindService(serviceConnection);
        } catch (Exception e) {
        }
        serviceConnection = null;
        mBound = false;
    }

    public static void shutdownService() {
        if (mainContext != null) {
            try {
                if (BuildConfig.DEBUG) {
                    Log.d("PlayerServiceUtil", "PlayerServiceUtil: shutdownService");
                }

                Intent anIntent = new Intent(mainContext, PlayerService.class);
                unBind(mainContext);
                mainContext.stopService(anIntent);
                itsPlayerService = null;
                serviceConnection = null;
            } catch (Exception e) {
                if (BuildConfig.DEBUG) {
                    Log.d("PlayerServiceUtil", "PlayerServiceUtil: shutdownService E001:" + e.getMessage());
                }
            }
        }
    }

    private static IPlayerService itsPlayerService;

    private static ServiceConnection getServiceConnection() {
        return new ServiceConnection() {
            public void onServiceConnected(ComponentName className, IBinder binder) {
                if (BuildConfig.DEBUG) {
                    Log.d("PLAYER", "Service came online");
                }
                itsPlayerService = IPlayerService.Stub.asInterface(binder);

                Intent local = new Intent();
                local.setAction(PlayerService.PLAYER_SERVICE_BOUND);
                LocalBroadcastManager.getInstance(mainContext).sendBroadcast(local);
            }

            public void onServiceDisconnected(ComponentName className) {
                if (BuildConfig.DEBUG) {
                    Log.d("PLAYER", "Service offline");
                }
                unBind(mainContext);
                itsPlayerService = null;
            }
        };
    }

    public static boolean isServiceBound() {
        return itsPlayerService != null;
    }

    public static boolean isPlaying() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.isPlaying();
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    public static PlayState getPlayerState() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getPlayerState();
            } catch (RemoteException e) {
            }
        }
        return PlayState.Idle;
    }

    public static void stop() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.Stop();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void play(DataRadioStation station) {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.SetStation(station);
                itsPlayerService.Play(false);
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void setStation(DataRadioStation station) {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.SetStation(station);
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void skipToNext() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.SkipToNext();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void skipToPrevious() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.SkipToPrevious();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void pause(PauseReason pauseReason) {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.Pause(pauseReason);
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void resume() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.Resume();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void clearTimer() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.clearTimer();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void addTimer(int secondsAdd) {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.addTimer(secondsAdd);
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static long getTimerSeconds() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getTimerSeconds();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return 0;
    }

    public static @NonNull
    StreamLiveInfo getMetadataLive() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getMetadataLive();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return new StreamLiveInfo(null);
    }

    public static String getStationId() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getCurrentStationID();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return null;
    }

    public static DataRadioStation getCurrentStation() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getCurrentStation();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return null;
    }

    public static void getStationIcon(final ImageView holder, final String fromUrl) {
        getStationIcon(holder, fromUrl, null, null);
    }

    public static void getStationIcon(final ImageView holder, final String iconUrl, final String homePageUrl) {
        getStationIcon(holder, iconUrl, homePageUrl, null);
    }

    /**
     * 统一的电台图标加载方法。
     *
     * 核心原则：尽快显示图标，尽量显示主图标。
     *
     * 流程：
     * 1. 有文件缓存 → 立即显示（不管来源，保证速度）
     *    - 若缓存来自回退URL且距上次重试超4小时，加入待重试队列
     * 2. 无文件缓存 → 先查 Picasso 内存/磁盘缓存（秒出）
     * 3. Picasso 缓存也没有 → 联网加载 IconUrl（不延迟重试，快速失败）
     *    - 成功 → 保存到文件缓存
     *    - 失败 → 立即尝试回退URL（不等待）
     * 4. 全部失败 → 后台延迟重试 IconUrl
     *
     * @param holder      目标ImageView
     * @param iconUrl     电台图标URL（主图标）
     * @param homePageUrl 电台主页URL（用于构建回退URL）
     * @param stationUuid 电台唯一ID，用于缓存key。为null时不使用缓存。
     */
    public static void getStationIcon(final ImageView holder, final String iconUrl, final String homePageUrl, final String stationUuid) {
        Resources r = mainContext.getResources();
        final float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 70, r.getDisplayMetrics());
        final int targetPxSize = (int) px;
        final int maxPxSize = Math.min(targetPxSize * 3, 512);
        final Drawable placeholder = AppCompatResources.getDrawable(holder.getContext(), R.mipmap.ic_launcher);

        holder.setScaleType(ImageView.ScaleType.FIT_CENTER);

        if (stationUuid != null && !stationUuid.isEmpty()) {
            holder.setTag(R.id.tag_station_uuid, stationUuid);
        }

        if (stationUuid != null && !stationUuid.isEmpty()) {
            StationIconCache iconCache = StationIconCache.getInstance(mainContext);
            String cachedPath = iconCache.getIconPath(stationUuid);
            if (cachedPath != null) {
                Picasso.get()
                        .load(Uri.fromFile(new File(cachedPath)))
                        .placeholder(placeholder)
                        .resize(maxPxSize, 0)
                        .onlyScaleDown()
                        .noFade()
                        .into(holder, new Callback() {
                            @Override
                            public void onSuccess() {
                                applySmartDisplayLogic(holder, targetPxSize, stationUuid);
                            }

                            @Override
                            public void onError(Exception e) {}
                        });

                Log.d(TAG, "File cache HIT: " + stationUuid + " path=" + cachedPath);

                if (iconCache.isFallbackCached(stationUuid)) {
                    if (iconUrl != null && !iconUrl.trim().isEmpty()
                            && iconCache.shouldRetryIconUrl(stationUuid)) {
                        synchronized (pendingRetries) {
                            pendingRetries.add(new RetryRequest(iconUrl, stationUuid, holder, targetPxSize, maxPxSize, homePageUrl));
                        }
                        flushHandler.removeCallbacks(flushRunnable);
                        flushHandler.postDelayed(flushRunnable, FLUSH_DELAY_MS);
                    } else if ((iconUrl == null || iconUrl.trim().isEmpty())
                            && homePageUrl != null && !homePageUrl.trim().isEmpty()
                            && !hdDiscoveryAttempted.contains(stationUuid)) {
                        synchronized (pendingHdDiscoveries) {
                            pendingHdDiscoveries.add(new HdDiscoveryRequest(homePageUrl, stationUuid, holder, targetPxSize, maxPxSize));
                        }
                        flushHandler.removeCallbacks(flushRunnable);
                        flushHandler.postDelayed(flushRunnable, FLUSH_DELAY_MS);
                    }
                }
                return;
            }
            Log.d(TAG, "File cache MISS: " + stationUuid);
        }

        final List<String> urlsToTry = new ArrayList<>();
        if (iconUrl != null && !iconUrl.trim().isEmpty()) {
            urlsToTry.add(iconUrl);
        }
        if (homePageUrl != null && !homePageUrl.trim().isEmpty()) {
            urlsToTry.addAll(buildFallbackUrls(homePageUrl));
        }
        if (urlsToTry.isEmpty()) {
            holder.setImageDrawable(placeholder);
            return;
        }

        if (iconUrl != null && !iconUrl.trim().isEmpty()) {
            Picasso.get()
                    .load(iconUrl)
                    .placeholder(placeholder)
                    .resize(maxPxSize, 0)
                    .onlyScaleDown()
                    .networkPolicy(NetworkPolicy.OFFLINE)
                    .into(holder, new Callback() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG, "Picasso cache HIT: " + stationUuid + " url=" + iconUrl);
                            if (stationUuid != null && !stationUuid.isEmpty()) {
                                saveIconToCacheFromView(holder, stationUuid);
                                StationIconCache.getInstance(mainContext).clearFallbackMark(stationUuid);
                            }
                            applySmartDisplayLogic(holder, targetPxSize, stationUuid);
                        }

                        @Override
                        public void onError(Exception e) {
                            Log.d(TAG, "Picasso cache MISS, loading from network: " + stationUuid);
                            loadIconFromNetwork(holder, urlsToTry, placeholder, targetPxSize, maxPxSize, homePageUrl, stationUuid);
                        }
                    });
        } else {
            tryFallbackUrls(holder, urlsToTry, 0, placeholder, targetPxSize, maxPxSize, homePageUrl, stationUuid);
        }
    }

    /**
     * 联网加载图标：先尝试 IconUrl，失败后立即尝试回退URL（不延迟等待）。
     */
    private static void loadIconFromNetwork(final ImageView holder, final List<String> urls,
                                             final Drawable placeholder, final int targetPxSize,
                                             final int maxPxSize, final String homePageUrl,
                                             final String stationUuid) {
        final String iconUrl = urls.get(0);
        Log.d(TAG, "Network load IconUrl: " + stationUuid + " url=" + iconUrl);

        Picasso.get()
                .load(iconUrl)
                .placeholder(placeholder)
                .resize(maxPxSize, 0)
                .onlyScaleDown()
                .networkPolicy(NetworkPolicy.NO_CACHE)
                .into(holder, new Callback() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "Network load SUCCESS: " + stationUuid + " url=" + iconUrl);
                        if (stationUuid != null && !stationUuid.isEmpty()) {
                            saveIconToCacheFromView(holder, stationUuid);
                            StationIconCache cache = StationIconCache.getInstance(mainContext);
                            cache.clearFallbackMark(stationUuid);
                            cache.recordIconUrlRetryTime(stationUuid);
                        }
                        applySmartDisplayLogic(holder, targetPxSize, stationUuid);
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.d(TAG, "Network load FAILED: " + stationUuid + " url=" + iconUrl + " err=" + e.getMessage());
                        if (stationUuid != null && !stationUuid.isEmpty()) {
                            StationIconCache.getInstance(mainContext).recordIconUrlRetryTime(stationUuid);
                        }
                        if (urls.size() > 1) {
                            tryFallbackUrls(holder, urls, 1, placeholder, targetPxSize, maxPxSize, homePageUrl, stationUuid);
                        } else {
                            holder.setImageDrawable(placeholder);
                        }
                    }
                });
    }

    /**
     * 尝试回退URL列表，失败后立即跳到下一个（不延迟）。
     */
    private static void tryFallbackUrls(final ImageView holder, final List<String> urls,
                                         final int startIndex, final Drawable placeholder,
                                         final int targetPxSize, final int maxPxSize,
                                         final String homePageUrl, final String stationUuid) {
        if (startIndex >= urls.size()) {
            holder.setImageDrawable(placeholder);
            return;
        }

        final String url = urls.get(startIndex);
        Log.d(TAG, "Fallback[" + startIndex + "]: " + stationUuid + " url=" + url);

        Picasso.get()
                .load(url)
                .placeholder(placeholder)
                .resize(maxPxSize, 0)
                .onlyScaleDown()
                .networkPolicy(NetworkPolicy.NO_CACHE)
                .into(holder, new Callback() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "Fallback[" + startIndex + "] SUCCESS: " + stationUuid + " url=" + url);
                        if (stationUuid != null && !stationUuid.isEmpty()) {
                            saveIconToCacheFromView(holder, stationUuid);
                            StationIconCache.getInstance(mainContext).markAsFallback(stationUuid);
                        }
                        applySmartDisplayLogic(holder, targetPxSize, stationUuid);

                        if (homePageUrl != null && !homePageUrl.trim().isEmpty()
                                && !hdDiscoveryAttempted.contains(stationUuid)) {
                            synchronized (pendingHdDiscoveries) {
                                pendingHdDiscoveries.add(new HdDiscoveryRequest(homePageUrl, stationUuid, holder, targetPxSize, maxPxSize));
                            }
                            flushHandler.removeCallbacks(flushRunnable);
                            flushHandler.postDelayed(flushRunnable, FLUSH_DELAY_MS);
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.d(TAG, "Fallback[" + startIndex + "] FAILED: " + stationUuid + " url=" + url);
                        tryFallbackUrls(holder, urls, startIndex + 1, placeholder, targetPxSize, maxPxSize, homePageUrl, stationUuid);
                    }
                });
    }

    /** 待重试请求 */
    private static class RetryRequest {
        final String iconUrl;
        final String stationUuid;
        final ImageView holder;
        final int targetPxSize;
        final int maxPxSize;
        final String homePageUrl;

        RetryRequest(String iconUrl, String stationUuid, ImageView holder,
                     int targetPxSize, int maxPxSize, String homePageUrl) {
            this.iconUrl = iconUrl;
            this.stationUuid = stationUuid;
            this.holder = holder;
            this.targetPxSize = targetPxSize;
            this.maxPxSize = maxPxSize;
            this.homePageUrl = homePageUrl;
        }
    }

    private static class HdDiscoveryRequest {
        final String homePageUrl;
        final String stationUuid;
        final ImageView holder;
        final int targetPxSize;
        final int maxPxSize;

        HdDiscoveryRequest(String homePageUrl, String stationUuid, ImageView holder,
                           int targetPxSize, int maxPxSize) {
            this.homePageUrl = homePageUrl;
            this.stationUuid = stationUuid;
            this.holder = holder;
            this.targetPxSize = targetPxSize;
            this.maxPxSize = maxPxSize;
        }
    }

    private static class DiscoveredIcon {
        final String url;
        final int size;

        DiscoveredIcon(String url, int size) {
            this.url = url;
            this.size = size;
        }
    }

    private static final List<RetryRequest> pendingRetries = new ArrayList<>();
    private static final List<HdDiscoveryRequest> pendingHdDiscoveries = new ArrayList<>();
    private static final Set<String> hdDiscoveryAttempted = Collections.synchronizedSet(new HashSet<>());
    private static final ExecutorService discoveryExecutor = Executors.newSingleThreadExecutor();
    private static final android.os.Handler flushHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long FLUSH_DELAY_MS = 500;
    private static final Runnable flushRunnable = () -> flushPendingRetries();

    /**
     * 执行所有待处理的后台 IconUrl 重试请求。
     * 由内部定时器自动触发：最后一次 getStationIcon 调用后 500ms 统一执行，
     * 确保所有缓存图标先显示完毕，再统一升级。
     */
    public static void flushPendingRetries() {
        List<RetryRequest> toProcess;
        synchronized (pendingRetries) {
            toProcess = new ArrayList<>(pendingRetries);
            pendingRetries.clear();
        }
        for (RetryRequest req : toProcess) {
            retryIconUrlInBackground(req.iconUrl, req.stationUuid, req.holder, req.targetPxSize, req.maxPxSize);
        }

        List<HdDiscoveryRequest> hdToProcess;
        synchronized (pendingHdDiscoveries) {
            hdToProcess = new ArrayList<>(pendingHdDiscoveries);
            pendingHdDiscoveries.clear();
        }
        for (HdDiscoveryRequest req : hdToProcess) {
            discoverHdIconInBackground(req.homePageUrl, req.stationUuid, req.holder, req.targetPxSize, req.maxPxSize);
        }
    }

    private static final Set<String> failedFallbackDomains = new HashSet<>();

    // 持有 Picasso Target 的强引用，防止被 GC
    private static final java.util.List<Target> backgroundTargets = new ArrayList<>();

    private static List<String> buildFallbackUrls(String homePageUrl) {
        List<String> fallbacks = new ArrayList<>();
        try {
            URI uri = new URI(homePageUrl);
            String domain = uri.getHost();
            if (domain == null || domain.isEmpty()) {
                return fallbacks;
            }
            String scheme = uri.getScheme() != null ? uri.getScheme() : "https";

            fallbacks.add(scheme + "://" + domain + "/apple-touch-icon.png");
            fallbacks.add(scheme + "://" + domain + "/apple-touch-icon-precomposed.png");
            fallbacks.add(scheme + "://" + domain + "/android-chrome-192x192.png");
            fallbacks.add(scheme + "://" + domain + "/favicon.ico");
            fallbacks.add("https://www.google.com/s2/favicons?domain=" + domain + "&sz=256");
        } catch (Exception e) {
            Log.w("PlayerServiceUtil", "Failed to build fallback URLs from: " + homePageUrl, e);
        }
        return fallbacks;
    }

    /**
     * 后台静默重试加载原始 IconUrl，用于覆盖已有的回退缓存。
     *
     * 成功：覆盖缓存 + 清除 fallback 标记 + 记录重试时间 + 刷新 ImageView
     * 失败：记录重试时间（24小时内不再重试），维持现有内容
     *
     * @param iconUrl     原始电台图标URL
     * @param stationUuid 电台UUID
     * @param holder      当前显示的ImageView，用于成功后刷新
     * @param pxSize      图标尺寸
     */
    private static void retryIconUrlInBackground(final String iconUrl, final String stationUuid,
                                                  final ImageView holder, final int targetPxSize,
                                                  final int maxPxSize) {
        final Target target = new Target() {
            @Override
            public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
                if (bitmap != null) {
                    if (isTruncatedBitmap(bitmap)) {
                        Log.w("PlayerServiceUtil", "Background IconUrl load truncated, skipping for: " + stationUuid);
                        backgroundTargets.remove(this);
                        return;
                    }
                    boolean isFavorite = isStationFavorited(stationUuid);
                    StationIconCache cache = StationIconCache.getInstance(mainContext);
                    cache.saveIcon(stationUuid, bitmap, isFavorite);
                    cache.clearFallbackMark(stationUuid);
                    cache.recordIconUrlRetryTime(stationUuid);
                    Log.d("PlayerServiceUtil", "Background IconUrl upgrade succeeded for: " + stationUuid);

                    if (holder != null) {
                        holder.post(() -> {
                            Object tag = holder.getTag(R.id.tag_station_uuid);
                            if (tag == null || !tag.equals(stationUuid)) return;

                            Drawable currentDrawable = holder.getDrawable();
                            int currentWidth = 0;
                            if (currentDrawable instanceof android.graphics.drawable.BitmapDrawable) {
                                currentWidth = ((android.graphics.drawable.BitmapDrawable) currentDrawable).getBitmap().getWidth();
                            }

                            // 只有新图标 >= 当前图标时才替换显示，避免用更小的主图覆盖清晰的回退图
                            if (bitmap.getWidth() >= currentWidth) {
                                holder.setImageBitmap(bitmap);
                                applySmartDisplayLogic(holder, targetPxSize, stationUuid);
                            } else {
                                Log.d("PlayerServiceUtil", "Background IconUrl smaller than current display (" +
                                        bitmap.getWidth() + " vs " + currentWidth + "), keeping current for: " + stationUuid);
                            }
                        });
                    }
                }
                backgroundTargets.remove(this);
            }

            @Override
            public void onBitmapFailed(Exception e, Drawable errorDrawable) {
                StationIconCache.getInstance(mainContext).recordIconUrlRetryTime(stationUuid);
                Log.d("PlayerServiceUtil", "Background IconUrl upgrade failed for: "
                        + stationUuid + ", will retry later");
                backgroundTargets.remove(this);
            }

            @Override
            public void onPrepareLoad(Drawable placeHolderDrawable) {}
        };

        backgroundTargets.add(target);

        Picasso.get()
                .load(iconUrl)
                .resize(maxPxSize, 0)
                .onlyScaleDown()
                .networkPolicy(NetworkPolicy.NO_CACHE)
                .into(target);
    }

    /**
     * 检测位图是否可能为截断下载导致的半截黑图。
     *
     * 判断逻辑：扫描位图底部 1/4 区域的像素，如果超过 80% 为纯黑色（0xFF000000），
     * 则认为该位图很可能是下载不完整导致的损坏图标。
     *
     * @param bitmap 待检测的位图
     * @return true 表示位图可能已损坏（半截黑图）
     */
    private static boolean isTruncatedBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return true;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        // 扫描底部 1/4 区域
        int startY = height * 3 / 4;
        int endY = height;
        int totalPixels = width * (endY - startY);
        int blackPixels = 0;
        int[] pixels = new int[width];
        for (int y = startY; y < endY; y++) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1);
            for (int px : pixels) {
                // 纯黑色：alpha=FF, R=G=B=0
                if (px == 0xFF000000) {
                    blackPixels++;
                }
            }
        }
        float blackRatio = (float) blackPixels / totalPixels;
        if (blackRatio > 0.8f) {
            Log.w(TAG, "Detected truncated bitmap: " + width + "x" + height
                    + " bottomQuarterBlackRatio=" + String.format("%.2f", blackRatio));
            return true;
        }
        return false;
    }

    /**
     * 从 ImageView 同步获取当前显示的 Bitmap 并保存到文件缓存。
     *
     * 在 Picasso Callback.onSuccess 中调用，此时 ImageView 的 Drawable 一定有效
     * （还没被 RecyclerView 回收），可以安全读取。
     *
     * @param holder      当前显示图标的 ImageView
     * @param stationUuid 电台UUID
     */
    private static void saveIconToCacheFromView(final ImageView holder, final String stationUuid) {
        try {
            Drawable drawable = holder.getDrawable();
            Log.d(TAG, "saveIconToCacheFromView: " + stationUuid + " drawable=" + (drawable != null ? drawable.getClass().getSimpleName() : "null"));
            Bitmap bitmap = null;
            if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                bitmap = ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
            } else if (drawable != null) {
                // Picasso 使用 PicassoDrawable 包装，需要从 Drawable 转换为 Bitmap
                bitmap = drawableToBitmap(drawable);
            }
            if (bitmap != null && stationUuid != null && !stationUuid.isEmpty()) {
                if (isTruncatedBitmap(bitmap)) {
                    Log.w(TAG, "saveIconToCacheFromView: skipping save, bitmap appears truncated for " + stationUuid);
                    return;
                }
                boolean isFavorite = isStationFavorited(stationUuid);
                StationIconCache.getInstance(mainContext).saveIcon(stationUuid, bitmap, isFavorite);
            } else {
                Log.w(TAG, "saveIconToCacheFromView: bitmap is null for " + stationUuid);
            }
        } catch (Exception e) {
            Log.w(TAG, "saveIconToCacheFromView failed: " + stationUuid, e);
        }
    }

    /**
     * 将任意 Drawable 转换为 Bitmap。
     * Picasso 使用 PicassoDrawable 包装 Bitmap，需要通过此方法提取。
     */
    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
            return ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
        }
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            // 单色或无内在尺寸的 Drawable
            width = 1;
            height = 1;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    /**
     * 智能图标显示逻辑。
     *
     * 核心原则：尽量显示高清图标。
     *
     * 如果图片宽度占显示区域宽度 50% 以上 → 原图显示（CENTER_INSIDE，清晰）
     * 如果图片宽度占显示区域宽度 50% 以下 → 放大显示（模糊但起标识作用）
     *
     * @param holder        目标ImageView
     * @param targetPxSize  目标显示区域宽度（像素）
     * @param stationUuid   电台UUID，用于验证ImageView身份
     */
    private static void applySmartDisplayLogic(final ImageView holder, final int targetPxSize,
                                                final String stationUuid) {
        if (holder == null) return;

        Object tag = holder.getTag(R.id.tag_station_uuid);
        if (stationUuid != null && !stationUuid.isEmpty() && tag != null && !tag.equals(stationUuid)) {
            return;
        }

        Drawable drawable = holder.getDrawable();
        if (drawable == null) return;

        Bitmap bitmap;
        if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
            bitmap = ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
        } else {
            bitmap = drawableToBitmap(drawable);
        }

        if (bitmap == null || bitmap.isRecycled()) return;

        // 强制 ImageView 保持正方形，防止因图片尺寸变化导致行高变形、叠加图标错位
        holder.getLayoutParams().height = holder.getLayoutParams().width;

        float widthRatio = (float) bitmap.getWidth() / targetPxSize;

        if (widthRatio >= 0.5f) {
            // 图片 ≥ 显示区域 50% → 原图显示，清晰不模糊
            holder.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        } else {
            // 图片 < 显示区域 50% → 放大显示，模糊但起标识作用
            int scaledWidth = targetPxSize;
            int scaledHeight = (int) ((float) bitmap.getHeight() * targetPxSize / bitmap.getWidth());
            if (scaledHeight <= 0) scaledHeight = targetPxSize;
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);
            holder.setImageBitmap(scaled);
            holder.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
    }

    private static void discoverHdIconInBackground(final String homePageUrl, final String stationUuid,
                                                    final ImageView holder, final int targetPxSize,
                                                    final int maxPxSize) {
        if (hdDiscoveryAttempted.contains(stationUuid)) return;
        hdDiscoveryAttempted.add(stationUuid);

        discoveryExecutor.execute(() -> {
            try {
                List<DiscoveredIcon> discoveredIcons = new ArrayList<>();

                String html = fetchUrlContent(homePageUrl);
                if (html != null) {
                    discoveredIcons.addAll(parseHtmlForIconUrls(html, homePageUrl));
                }

                try {
                    URI uri = new URI(homePageUrl);
                    String domain = uri.getHost();
                    String scheme = uri.getScheme() != null ? uri.getScheme() : "https";
                    String manifestUrl = scheme + "://" + domain + "/site.webmanifest";
                    String manifestJson = fetchUrlContent(manifestUrl);
                    if (manifestJson != null) {
                        discoveredIcons.addAll(parseWebManifestForIconUrls(manifestJson, manifestUrl));
                    }
                } catch (Exception ignored) {}

                if (discoveredIcons.isEmpty()) return;

                Collections.sort(discoveredIcons, (a, b) -> b.size - a.size);

                List<DiscoveredIcon> filtered = new ArrayList<>();
                for (DiscoveredIcon icon : discoveredIcons) {
                    if (icon.size >= targetPxSize) {
                        filtered.add(icon);
                    }
                }
                if (filtered.isEmpty()) {
                    filtered.add(discoveredIcons.get(0));
                }

                if (holder != null) {
                    holder.post(() -> tryDiscoveredIconUrls(filtered, 0, stationUuid, holder, targetPxSize, maxPxSize));
                }
            } catch (Exception e) {
                Log.w(TAG, "HD icon discovery failed for: " + stationUuid, e);
            }
        });
    }

    private static void tryDiscoveredIconUrls(final List<DiscoveredIcon> icons, final int startIndex,
                                               final String stationUuid, final ImageView holder,
                                               final int targetPxSize, final int maxPxSize) {
        if (startIndex >= icons.size()) return;

        final String url = icons.get(startIndex).url;
        Log.d(TAG, "HD discovery[" + startIndex + "]: " + stationUuid + " url=" + url + " size=" + icons.get(startIndex).size);

        final Target target = new Target() {
            @Override
            public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
                if (bitmap != null) {
                    if (isTruncatedBitmap(bitmap)) {
                        Log.w(TAG, "HD discovery truncated, skipping for: " + stationUuid);
                        tryDiscoveredIconUrls(icons, startIndex + 1, stationUuid, holder, targetPxSize, maxPxSize);
                        backgroundTargets.remove(this);
                        return;
                    }
                    boolean isFavorite = isStationFavorited(stationUuid);
                    StationIconCache cache = StationIconCache.getInstance(mainContext);

                    if (holder != null) {
                        Object tag = holder.getTag(R.id.tag_station_uuid);
                        if (tag == null || !tag.equals(stationUuid)) {
                            backgroundTargets.remove(this);
                            return;
                        }

                        Drawable currentDrawable = holder.getDrawable();
                        int currentWidth = 0;
                        if (currentDrawable instanceof android.graphics.drawable.BitmapDrawable) {
                            currentWidth = ((android.graphics.drawable.BitmapDrawable) currentDrawable).getBitmap().getWidth();
                        }

                        // 只有新图标 >= 当前图标时才替换显示
                        if (bitmap.getWidth() >= currentWidth) {
                            cache.saveIcon(stationUuid, bitmap, isFavorite);
                            cache.clearFallbackMark(stationUuid);
                            Log.d(TAG, "HD discovery succeeded for: " + stationUuid);
                            holder.setImageBitmap(bitmap);
                            applySmartDisplayLogic(holder, targetPxSize, stationUuid);
                        } else {
                            Log.d(TAG, "HD discovery icon smaller than current (" +
                                    bitmap.getWidth() + " vs " + currentWidth + "), skipping for: " + stationUuid);
                        }
                    }
                }
                backgroundTargets.remove(this);
            }

            @Override
            public void onBitmapFailed(Exception e, Drawable errorDrawable) {
                Log.d(TAG, "HD discovery[" + startIndex + "] FAILED: " + stationUuid + " url=" + url);
                tryDiscoveredIconUrls(icons, startIndex + 1, stationUuid, holder, targetPxSize, maxPxSize);
                backgroundTargets.remove(this);
            }

            @Override
            public void onPrepareLoad(Drawable placeHolderDrawable) {}
        };

        backgroundTargets.add(target);

        Picasso.get()
                .load(url)
                .resize(maxPxSize, 0)
                .onlyScaleDown()
                .networkPolicy(NetworkPolicy.NO_CACHE)
                .into(target);
    }

    private static String fetchUrlContent(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "RadioDroid");
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                conn.disconnect();
                return null;
            }
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                result.write(buffer, 0, bytesRead);
                if (result.size() > 1024 * 1024) break;
            }
            is.close();
            conn.disconnect();
            return result.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static List<DiscoveredIcon> parseHtmlForIconUrls(String html, String baseUrl) {
        List<DiscoveredIcon> icons = new ArrayList<>();
        try {
            Pattern linkPattern = Pattern.compile("<link[^>]+>", Pattern.CASE_INSENSITIVE);
            Matcher matcher = linkPattern.matcher(html);

            while (matcher.find()) {
                String linkTag = matcher.group();
                String rel = extractHtmlAttribute(linkTag, "rel");
                if (rel == null) continue;

                String relLower = rel.toLowerCase();
                if (!relLower.contains("icon") && !relLower.contains("apple-touch-icon")) continue;

                String href = extractHtmlAttribute(linkTag, "href");
                if (href == null || href.trim().isEmpty()) continue;

                String absoluteUrl = resolveUrl(baseUrl, href);
                if (absoluteUrl == null) continue;

                int size = parseSizeAttribute(extractHtmlAttribute(linkTag, "sizes"));
                icons.add(new DiscoveredIcon(absoluteUrl, size));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse HTML for icon URLs", e);
        }
        return icons;
    }

    private static List<DiscoveredIcon> parseWebManifestForIconUrls(String json, String manifestUrl) {
        List<DiscoveredIcon> icons = new ArrayList<>();
        try {
            JSONObject manifest = new JSONObject(json);
            JSONArray iconsArray = manifest.optJSONArray("icons");
            if (iconsArray == null) return icons;

            for (int i = 0; i < iconsArray.length(); i++) {
                JSONObject iconObj = iconsArray.getJSONObject(i);
                String src = iconObj.optString("src", "");
                if (src.isEmpty()) continue;

                String absoluteUrl = resolveUrl(manifestUrl, src);
                if (absoluteUrl == null) continue;

                String sizes = iconObj.optString("sizes", "");
                int size = parseSizeString(sizes);
                icons.add(new DiscoveredIcon(absoluteUrl, size));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse webmanifest", e);
        }
        return icons;
    }

    private static String extractHtmlAttribute(String tag, String attrName) {
        Pattern pattern = Pattern.compile(attrName + "\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(tag);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static int parseSizeAttribute(String sizesStr) {
        if (sizesStr == null || sizesStr.isEmpty()) return 0;
        String[] sizeParts = sizesStr.split("\\s+");
        int maxSize = 0;
        for (String part : sizeParts) {
            int size = parseSizeString(part);
            if (size > maxSize) maxSize = size;
        }
        return maxSize;
    }

    private static int parseSizeString(String sizeStr) {
        if (sizeStr == null || sizeStr.isEmpty()) return 0;
        try {
            String[] parts = sizeStr.toLowerCase().split("x");
            if (parts.length >= 1) {
                return Integer.parseInt(parts[0].trim());
            }
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private static String resolveUrl(String baseUrl, String relativeUrl) {
        try {
            URI base = new URI(baseUrl);
            URI resolved = base.resolve(relativeUrl);
            return resolved.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断电台是否在收藏列表中
     */
    private static boolean isStationFavorited(String stationUuid) {
        try {
            RadioDroidApp app = (RadioDroidApp) mainContext.getApplicationContext();
            FavouriteManager favMgr = app.getFavouriteManager();
            return favMgr != null && favMgr.has(stationUuid);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 强制刷新指定电台的图标。删除文件缓存后重新从网络加载。
     *
     * @param station 电台对象，包含 iconUrl、homePageUrl、stationUuid
     * @param holder  目标ImageView，刷新成功后更新显示
     */
    public static void forceRefreshStationIcon(DataRadioStation station, ImageView holder) {
        if (station == null || station.StationUuid == null) return;

        String stationUuid = station.StationUuid;
        Log.d(TAG, "Force refresh icon for: " + stationUuid);

        // 删除文件缓存
        StationIconCache.getInstance(mainContext).removeIcon(stationUuid);

        // 清除 Picasso 内存缓存中该图标的引用
        if (station.IconUrl != null && !station.IconUrl.trim().isEmpty()) {
            Picasso.get().invalidate(station.IconUrl);
        }

        // 重新加载图标
        getStationIcon(holder, station.IconUrl, station.HomePageUrl, stationUuid);
    }

    /**
     * 清除所有电台图标缓存（文件缓存 + Picasso 磁盘缓存）。
     *
     * @return 清除的文件数量
     */
    public static int clearAllIconCache() {
        StationIconCache cache = StationIconCache.getInstance(mainContext);
        int count = cache.clearAllCache();

        // 清除 Picasso 磁盘缓存
        try {
            File picassoCacheDir = new File(mainContext.getCacheDir(), "picasso-cache");
            if (picassoCacheDir.exists() && picassoCacheDir.isDirectory()) {
                File[] files = picassoCacheDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        f.delete();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to clear Picasso disk cache", e);
        }

        Log.d(TAG, "Cleared all icon cache, removed " + count + " files");
        return count;
    }

    public static ShoutcastInfo getShoutcastInfo() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getShoutcastInfo();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return null;
    }

    public static void startRecording() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.startRecording();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void stopRecording() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.stopRecording();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static boolean isRecording() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.isRecording();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return false;
    }

    public static String getCurrentRecordFileName() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getCurrentRecordFileName();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return null;
    }

    public static boolean getIsHls() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getIsHls();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return false;
    }

    public static long getTransferredBytes() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getTransferredBytes();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return 0;
    }

    public static long getBufferedSeconds() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getBufferedSeconds();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return 0;
    }

    public static long getLastPlayStartTime() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getLastPlayStartTime();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return 0;
    }

    public static long getTotalPlayTime() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getTotalPlayTime();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return 0;
    }

    public static PauseReason getPauseReason() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getPauseReason();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return PauseReason.NONE;
    }

    public static void enableMPD(String hostname, int port) {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.enableMPD(hostname, port);
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public void disableMPD() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.disableMPD();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static boolean isNotificationActive() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.isNotificationActive();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return false;
    }

    public static int getAudioSessionId() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getAudioSessionId();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return 0;
    }
}
