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

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
        final Drawable placeholder = AppCompatResources.getDrawable(holder.getContext(), R.mipmap.ic_launcher);

        // 标记当前 ImageView 对应的电台 UUID，后台图标升级时用于验证身份
        if (stationUuid != null && !stationUuid.isEmpty()) {
            holder.setTag(R.id.tag_station_uuid, stationUuid);
        }

        // 第1步：优先从文件缓存加载 —— 保证速度
        if (stationUuid != null && !stationUuid.isEmpty()) {
            StationIconCache iconCache = StationIconCache.getInstance(mainContext);
            String cachedPath = iconCache.getIconPath(stationUuid);
            if (cachedPath != null) {
                // 立即显示缓存图标（resize 填满 ImageView，小图放大、大图缩小）
                Picasso.get()
                        .load(Uri.fromFile(new java.io.File(cachedPath)))
                        .placeholder(placeholder)
                        .resize((int) px, 0)
                        .noFade()
                        .into(holder);

                Log.d(TAG, "File cache HIT: " + stationUuid + " path=" + cachedPath);

                // 缓存来自回退URL，且电台有主图标，且距上次重试超4小时 → 加入待重试队列
                if (iconCache.isFallbackCached(stationUuid)
                        && iconUrl != null && !iconUrl.trim().isEmpty()
                        && iconCache.shouldRetryIconUrl(stationUuid)) {
                    synchronized (pendingRetries) {
                        pendingRetries.add(new RetryRequest(iconUrl, stationUuid, holder, (int) px));
                    }
                    // 重置刷新定时器：最后一次 getStationIcon 后 500ms 统一执行后台重试
                    flushHandler.removeCallbacks(flushRunnable);
                    flushHandler.postDelayed(flushRunnable, FLUSH_DELAY_MS);
                }
                return;
            }
            Log.d(TAG, "File cache MISS: " + stationUuid);
        }

        // 第2步：文件缓存未命中，构建回退URL列表
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

        // 第3步：先尝试 Picasso 内存/磁盘缓存（可能秒出，无需网络）
        if (iconUrl != null && !iconUrl.trim().isEmpty()) {
            Picasso.get()
                    .load(iconUrl)
                    .placeholder(placeholder)
                    .resize((int) px, 0)
                    .networkPolicy(NetworkPolicy.OFFLINE)
                    .into(holder, new Callback() {
                        @Override
                        public void onSuccess() {
                            // Picasso 缓存命中，保存到文件缓存
                            Log.d(TAG, "Picasso cache HIT: " + stationUuid + " url=" + iconUrl);
                            if (stationUuid != null && !stationUuid.isEmpty()) {
                                saveIconToCacheFromView(holder, stationUuid);
                                StationIconCache.getInstance(mainContext).clearFallbackMark(stationUuid);
                            }
                        }

                        @Override
                        public void onError(Exception e) {
                            // Picasso 缓存未命中，联网加载（不延迟，快速失败）
                            Log.d(TAG, "Picasso cache MISS, loading from network: " + stationUuid);
                            loadIconFromNetwork(holder, urlsToTry, placeholder, (int) px, stationUuid);
                        }
                    });
        } else {
            // 没有 IconUrl，直接尝试回退URL
            tryFallbackUrls(holder, urlsToTry, 0, placeholder, (int) px, stationUuid);
        }
    }

    /**
     * 联网加载图标：先尝试 IconUrl，失败后立即尝试回退URL（不延迟等待）。
     */
    private static void loadIconFromNetwork(final ImageView holder, final List<String> urls,
                                             final Drawable placeholder, final int pxSize,
                                             final String stationUuid) {
        final String iconUrl = urls.get(0);
        Log.d(TAG, "Network load IconUrl: " + stationUuid + " url=" + iconUrl);

        Picasso.get()
                .load(iconUrl)
                .placeholder(placeholder)
                .resize(pxSize, 0)
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
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.d(TAG, "Network load FAILED: " + stationUuid + " url=" + iconUrl + " err=" + e.getMessage());
                        // 记录重试时间
                        if (stationUuid != null && !stationUuid.isEmpty()) {
                            StationIconCache.getInstance(mainContext).recordIconUrlRetryTime(stationUuid);
                        }
                        // 立即尝试回退URL，不等待
                        if (urls.size() > 1) {
                            tryFallbackUrls(holder, urls, 1, placeholder, pxSize, stationUuid);
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
                                         final int pxSize, final String stationUuid) {
        if (startIndex >= urls.size()) {
            holder.setImageDrawable(placeholder);
            return;
        }

        final String url = urls.get(startIndex);
        Log.d(TAG, "Fallback[" + startIndex + "]: " + stationUuid + " url=" + url);

        Picasso.get()
                .load(url)
                .placeholder(placeholder)
                .resize(pxSize, 0)
                .networkPolicy(NetworkPolicy.NO_CACHE)
                .into(holder, new Callback() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "Fallback[" + startIndex + "] SUCCESS: " + stationUuid + " url=" + url);
                        if (stationUuid != null && !stationUuid.isEmpty()) {
                            saveIconToCacheFromView(holder, stationUuid);
                            StationIconCache.getInstance(mainContext).markAsFallback(stationUuid);
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.d(TAG, "Fallback[" + startIndex + "] FAILED: " + stationUuid + " url=" + url);
                        tryFallbackUrls(holder, urls, startIndex + 1, placeholder, pxSize, stationUuid);
                    }
                });
    }

    /** 待重试请求 */
    private static class RetryRequest {
        final String iconUrl;
        final String stationUuid;
        final ImageView holder;
        final int pxSize;

        RetryRequest(String iconUrl, String stationUuid, ImageView holder, int pxSize) {
            this.iconUrl = iconUrl;
            this.stationUuid = stationUuid;
            this.holder = holder;
            this.pxSize = pxSize;
        }
    }

    // 待重试队列：缓存图标显示完毕后统一执行后台重试
    private static final List<RetryRequest> pendingRetries = new ArrayList<>();
    private static final android.os.Handler flushHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long FLUSH_DELAY_MS = 500; // 最后一次图标加载后500ms统一刷新
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
            retryIconUrlInBackground(req.iconUrl, req.stationUuid, req.holder, req.pxSize);
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

            fallbacks.add(scheme + "://" + domain + "/favicon.ico");
            fallbacks.add(scheme + "://" + domain + "/apple-touch-icon.png");
            fallbacks.add("https://www.google.com/s2/favicons?domain=" + domain + "&sz=128");
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
                                                  final ImageView holder, final int pxSize) {
        final Target target = new Target() {
            @Override
            public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
                if (bitmap != null) {
                    boolean isFavorite = isStationFavorited(stationUuid);
                    StationIconCache cache = StationIconCache.getInstance(mainContext);
                    cache.saveIcon(stationUuid, bitmap, isFavorite);
                    cache.clearFallbackMark(stationUuid);
                    cache.recordIconUrlRetryTime(stationUuid);
                    Log.d("PlayerServiceUtil", "Background IconUrl upgrade succeeded for: " + stationUuid);

                    // 刷新 ImageView 显示主图标
                    if (holder != null) {
                        holder.post(() -> {
                            // 验证 holder 仍显示同一电台（通过 tag 中的 uuid 比对）
                            Object tag = holder.getTag(R.id.tag_station_uuid);
                            if (tag != null && tag.equals(stationUuid)) {
                                holder.setImageBitmap(bitmap);
                            }
                        });
                    }
                }
                backgroundTargets.remove(this);
            }

            @Override
            public void onBitmapFailed(Exception e, Drawable errorDrawable) {
                // 记录重试时间，24小时内不再尝试
                StationIconCache.getInstance(mainContext).recordIconUrlRetryTime(stationUuid);
                Log.d("PlayerServiceUtil", "Background IconUrl upgrade failed for: "
                        + stationUuid + ", will retry tomorrow");
                backgroundTargets.remove(this);
            }

            @Override
            public void onPrepareLoad(Drawable placeHolderDrawable) {}
        };

        // 持有强引用防止 Picasso 的弱引用导致 Target 被 GC
        backgroundTargets.add(target);

        Picasso.get()
                .load(iconUrl)
                .resize(pxSize, 0)
                .networkPolicy(NetworkPolicy.NO_CACHE)
                .into(target);
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

    public static void warnAboutMeteredConnection(PlayerType playerType) {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.warnAboutMeteredConnection(playerType);
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
