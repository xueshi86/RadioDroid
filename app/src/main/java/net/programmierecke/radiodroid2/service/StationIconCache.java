package net.programmierecke.radiodroid2.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 电台图标双层缓存：永久缓存 + 半永久缓存。
 *
 * 永久缓存：收藏电台的图标，保存到应用卸载为止。取消收藏时图标移入半永久缓存。
 * 半永久缓存：非收藏电台的图标，7天TTL。每次读取时重置TTL为7天。
 *   - 经常使用的电台图标会因TTL重置而持续保留
 *   - 不常用的电台图标会在7天后自动过期
 *
 * 所有需要加载电台图标的地方统一使用此缓存：
 * 1. 先查永久缓存 -> 再查半永久缓存 -> 最后执行多渠道回退策略
 * 2. 回退成功后，收藏电台存入永久缓存，其他存入半永久缓存
 *
 * Fallback 标记机制：
 * - 当图标通过回退URL（非原始IconUrl）加载时，标记为 fallback 来源
 * - 后续缓存命中时若为 fallback 来源，可触发后台重试原始 IconUrl
 * - 原始 IconUrl 加载成功后清除 fallback 标记并覆盖缓存
 */
public class StationIconCache {

    private static final String TAG = "StationIconCache";
    private static final String PERMANENT_DIR_NAME = "station_icon_permanent";
    private static final String SEMI_PERMANENT_DIR_NAME = "station_icon_semipermanent";
    private static final String FALLBACK_SUFFIX = ".fallback";
    private static final String ICONURL_RETRY_SUFFIX = ".iconurl_retry";
    private static final long SEMI_PERMANENT_TTL_MS = 7L * 24 * 60 * 60 * 1000; // 7天
    private static final long ICONURL_RETRY_INTERVAL_MS = 4L * 60 * 60 * 1000; // 4小时

    private static StationIconCache instance;

    private final File permanentDir;
    private final File semiPermanentDir;
    private final Context appContext;

    private StationIconCache(Context context) {
        appContext = context.getApplicationContext();
        permanentDir = new File(appContext.getFilesDir(), PERMANENT_DIR_NAME);
        semiPermanentDir = new File(appContext.getFilesDir(), SEMI_PERMANENT_DIR_NAME);
        if (!permanentDir.exists()) {
            permanentDir.mkdirs();
        }
        if (!semiPermanentDir.exists()) {
            semiPermanentDir.mkdirs();
        }
        // 迁移旧版缓存目录（station_icon_cache）中的文件到永久缓存
        migrateOldCache();
    }

    /**
     * 迁移旧版 station_icon_cache 目录中的图标到永久缓存
     */
    private void migrateOldCache() {
        File oldCacheDir = new File(appContext.getFilesDir(), "station_icon_cache");
        if (!oldCacheDir.exists() || !oldCacheDir.isDirectory()) {
            return;
        }
        File[] oldFiles = oldCacheDir.listFiles();
        if (oldFiles == null) return;
        for (File oldFile : oldFiles) {
            if (oldFile.isFile() && oldFile.getName().endsWith(".png")) {
                File newFile = new File(permanentDir, oldFile.getName());
                if (!newFile.exists()) {
                    oldFile.renameTo(newFile);
                } else {
                    oldFile.delete();
                }
            }
        }
        // 删除旧目录（如果为空）
        oldFiles = oldCacheDir.listFiles();
        if (oldFiles == null || oldFiles.length == 0) {
            oldCacheDir.delete();
        }
    }

    public static synchronized StationIconCache getInstance(Context context) {
        if (instance == null) {
            instance = new StationIconCache(context);
        }
        return instance;
    }

    /**
     * 生成缓存文件名，使用 stationUuid 的 MD5 哈希避免文件名中的特殊字符问题
     */
    private String getCacheFileName(String stationUuid) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(stationUuid.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString() + ".png";
        } catch (NoSuchAlgorithmException e) {
            return stationUuid.replaceAll("[^a-zA-Z0-9_-]", "_") + ".png";
        }
    }

    /**
     * 获取电台图标的缓存路径。优先查永久缓存，再查半永久缓存。
     * 从半永久缓存命中时，自动重置TTL为7天。
     * 如果检测到缓存文件为截断的损坏位图，自动删除并返回null。
     *
     * @return 缓存文件路径，无缓存返回null
     */
    public String getIconPath(String stationUuid) {
        if (stationUuid == null || stationUuid.isEmpty()) {
            return null;
        }
        String fileName = getCacheFileName(stationUuid);

        // 优先查永久缓存
        File permanentFile = new File(permanentDir, fileName);
        if (permanentFile.exists() && permanentFile.length() > 0) {
            if (isCachedIconTruncated(permanentFile)) {
                Log.w(TAG, "getIconPath: truncated permanent cache detected, deleting: " + stationUuid);
                permanentFile.delete();
                clearFallbackMark(stationUuid);
                clearIconUrlRetryTime(stationUuid);
            } else {
                Log.d(TAG, "getIconPath HIT permanent: " + stationUuid + " size=" + permanentFile.length() + "B");
                return permanentFile.getAbsolutePath();
            }
        }

        // 查半永久缓存
        File semiFile = new File(semiPermanentDir, fileName);
        if (semiFile.exists() && semiFile.length() > 0) {
            // 检查是否过期
            long now = System.currentTimeMillis();
            long lastModified = semiFile.lastModified();
            if (now - lastModified > SEMI_PERMANENT_TTL_MS) {
                // 已过期，删除
                Log.d(TAG, "getIconPath EXPIRED: " + stationUuid);
                semiFile.delete();
                return null;
            }
            if (isCachedIconTruncated(semiFile)) {
                Log.w(TAG, "getIconPath: truncated semipermanent cache detected, deleting: " + stationUuid);
                semiFile.delete();
                clearFallbackMark(stationUuid);
                clearIconUrlRetryTime(stationUuid);
                return null;
            }
            // 命中半永久缓存，重置TTL
            semiFile.setLastModified(now);
            Log.d(TAG, "getIconPath HIT semipermanent: " + stationUuid + " size=" + semiFile.length() + "B");
            return semiFile.getAbsolutePath();
        }

        Log.d(TAG, "getIconPath MISS: " + stationUuid);
        return null;
    }

    /**
     * 检测缓存文件中的位图是否为截断下载导致的半截黑图。
     * 扫描位图底部 1/4 区域，如果超过 80% 为纯黑色像素则判定为截断。
     */
    private boolean isCachedIconTruncated(File file) {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            Bitmap bitmap = BitmapFactory.decodeStream(fis);
            if (bitmap == null) return true;

            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width <= 0 || height <= 0) {
                bitmap.recycle();
                return true;
            }

            int startY = height * 3 / 4;
            int totalPixels = width * (height - startY);
            int blackPixels = 0;
            int[] pixels = new int[width];
            for (int y = startY; y < height; y++) {
                bitmap.getPixels(pixels, 0, width, 0, y, width, 1);
                for (int px : pixels) {
                    if (px == 0xFF000000) {
                        blackPixels++;
                    }
                }
            }
            bitmap.recycle();
            float blackRatio = (float) blackPixels / totalPixels;
            return blackRatio > 0.8f;
        } catch (Exception e) {
            return true;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 获取缓存的图标 Bitmap。优先查永久缓存，再查半永久缓存。
     * 从半永久缓存命中时，自动重置TTL。
     */
    public Bitmap getIconBitmap(String stationUuid) {
        String path = getIconPath(stationUuid);
        if (path == null) {
            return null;
        }
        File file = new File(path);
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            Bitmap bitmap = BitmapFactory.decodeStream(fis);
            if (bitmap == null) {
                file.delete();
                return null;
            }
            return bitmap;
        } catch (IOException e) {
            Log.w(TAG, "Failed to read cached icon for: " + stationUuid, e);
            return null;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 保存图标到缓存。收藏电台存入永久缓存，其他存入半永久缓存。
     *
     * @param stationUuid 电台UUID
     * @param bitmap      图标Bitmap
     * @param isFavorite  是否是收藏电台
     */
    public void saveIcon(String stationUuid, Bitmap bitmap, boolean isFavorite) {
        if (stationUuid == null || stationUuid.isEmpty() || bitmap == null) {
            return;
        }
        String fileName = getCacheFileName(stationUuid);
        File targetDir = isFavorite ? permanentDir : semiPermanentDir;
        File file = new File(targetDir, fileName);

        // 如果在另一个缓存中已存在，先删除旧缓存
        File otherDir = isFavorite ? semiPermanentDir : permanentDir;
        File otherFile = new File(otherDir, fileName);
        if (otherFile.exists()) {
            otherFile.delete();
        }

        FileOutputStream fos = null;
        try {
            // 确保目标目录存在
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
            fos = null;
            // 验证文件确实写入
            boolean exists = file.exists();
            long length = file.length();
            Log.d(TAG, "saveIcon: " + stationUuid + " " + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " fav=" + isFavorite + " exists=" + exists + " len=" + length + "B"
                    + " path=" + file.getAbsolutePath());
        } catch (IOException e) {
            Log.w(TAG, "saveIcon FAIL: " + stationUuid + " path=" + file.getAbsolutePath(), e);
            if (file.exists()) {
                file.delete();
            }
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 电台被收藏时调用。将半永久缓存中的图标移入永久缓存。
     */
    public void onStationFavorited(String stationUuid) {
        if (stationUuid == null || stationUuid.isEmpty()) {
            return;
        }
        String fileName = getCacheFileName(stationUuid);
        File semiFile = new File(semiPermanentDir, fileName);
        File permanentFile = new File(permanentDir, fileName);

        // 永久缓存已有则无需操作
        if (permanentFile.exists() && permanentFile.length() > 0) {
            return;
        }

        // 半永久缓存有则移动
        if (semiFile.exists() && semiFile.length() > 0) {
            semiFile.renameTo(permanentFile);
        }
    }

    /**
     * 电台取消收藏时调用。将永久缓存中的图标移入半永久缓存（重置TTL）。
     */
    public void onStationUnfavorited(String stationUuid) {
        if (stationUuid == null || stationUuid.isEmpty()) {
            return;
        }
        String fileName = getCacheFileName(stationUuid);
        File permanentFile = new File(permanentDir, fileName);
        File semiFile = new File(semiPermanentDir, fileName);

        if (permanentFile.exists() && permanentFile.length() > 0) {
            // 移入半永久缓存，重置TTL
            if (permanentFile.renameTo(semiFile)) {
                semiFile.setLastModified(System.currentTimeMillis());
            }
        }
    }

    /**
     * 清理过期的半永久缓存文件。应在应用启动时调用。
     */
    public void cleanExpiredSemiPermanentCache() {
        long now = System.currentTimeMillis();
        File[] files = semiPermanentDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isFile() && now - file.lastModified() > SEMI_PERMANENT_TTL_MS) {
                file.delete();
            }
        }
    }

    /**
     * 删除指定电台的所有缓存图标
     */
    public void removeIcon(String stationUuid) {
        if (stationUuid == null || stationUuid.isEmpty()) {
            return;
        }
        String fileName = getCacheFileName(stationUuid);
        new File(permanentDir, fileName).delete();
        new File(semiPermanentDir, fileName).delete();
        clearFallbackMark(stationUuid);
        clearIconUrlRetryTime(stationUuid);
    }

    /**
     * 清除所有图标缓存（永久 + 半永久），包括 fallback 标记和重试时间戳。
     *
     * @return 删除的文件数量
     */
    public int clearAllCache() {
        int count = 0;
        count += deleteAllFilesInDir(permanentDir);
        count += deleteAllFilesInDir(semiPermanentDir);
        return count;
    }

    private int deleteAllFilesInDir(File dir) {
        int count = 0;
        if (dir == null || !dir.exists()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.isFile() && file.delete()) {
                count++;
            }
        }
        return count;
    }

    // ==================== Fallback 标记管理 ====================

    /**
     * 获取 fallback 标记文件的路径（在每个缓存目录下以 .fallback 后缀存在）
     */
    private String getFallbackMarkerFileName(String stationUuid) {
        return getCacheFileName(stationUuid) + FALLBACK_SUFFIX;
    }

    /**
     * 标记指定电台的缓存图标来源于回退URL（非原始IconUrl）。
     * 在回退URL加载成功时调用。
     */
    public void markAsFallback(String stationUuid) {
        if (stationUuid == null || stationUuid.isEmpty()) {
            return;
        }
        // 标记写在与 PNG 同目录，随缓存文件生命周期
        String markerName = getFallbackMarkerFileName(stationUuid);
        // 哪个目录有 PNG 就在哪个目录写 marker
        String iconFileName = getCacheFileName(stationUuid);
        File permPng = new File(permanentDir, iconFileName);
        File semiPng = new File(semiPermanentDir, iconFileName);
        if (permPng.exists()) {
            try { new File(permanentDir, markerName).createNewFile(); } catch (IOException ignored) {}
        } else if (semiPng.exists()) {
            try { new File(semiPermanentDir, markerName).createNewFile(); } catch (IOException ignored) {}
        }
    }

    /**
     * 判断指定电台的缓存图标是否来源于回退URL。
     *
     * @return true 表示当前缓存的图标是 fallback 来源，可以尝试用 IconUrl 覆盖
     */
    public boolean isFallbackCached(String stationUuid) {
        if (stationUuid == null || stationUuid.isEmpty()) {
            return false;
        }
        String markerName = getFallbackMarkerFileName(stationUuid);
        return new File(permanentDir, markerName).exists()
                || new File(semiPermanentDir, markerName).exists();
    }

    /**
     * 清除 fallback 标记。在原始 IconUrl 加载成功并覆盖缓存后调用。
     */
    public void clearFallbackMark(String stationUuid) {
        if (stationUuid == null || stationUuid.isEmpty()) {
            return;
        }
        String markerName = getFallbackMarkerFileName(stationUuid);
        new File(permanentDir, markerName).delete();
        new File(semiPermanentDir, markerName).delete();
    }

    // ==================== IconUrl 每日重试机制 ====================

    /**
     * 获取 IconUrl 重试时间戳文件名
     */
    private String getIconUrlRetryFileName(String stationUuid) {
        return getCacheFileName(stationUuid) + ICONURL_RETRY_SUFFIX;
    }

    /**
     * 记录 IconUrl 重试时间戳。无论成功或失败，每次尝试后都记录，
     * 用于控制每日最多重试一次。
     */
    public void recordIconUrlRetryTime(String stationUuid) {
        if (stationUuid == null || stationUuid.isEmpty()) {
            return;
        }
        String retryFileName = getIconUrlRetryFileName(stationUuid);
        // 写在与 PNG 同目录
        String iconFileName = getCacheFileName(stationUuid);
        File permPng = new File(permanentDir, iconFileName);
        File semiPng = new File(semiPermanentDir, iconFileName);
        if (permPng.exists()) {
            try { new File(permanentDir, retryFileName).createNewFile(); } catch (IOException ignored) {}
        } else if (semiPng.exists()) {
            try { new File(semiPermanentDir, retryFileName).createNewFile(); } catch (IOException ignored) {}
        } else {
            // PNG 尚未缓存（首次加载失败），默认写在半永久目录
            try { new File(semiPermanentDir, retryFileName).createNewFile(); } catch (IOException ignored) {}
        }
    }

    /**
     * 判断是否应该重试 IconUrl。距上次重试超过24小时返回 true。
     */
    public boolean shouldRetryIconUrl(String stationUuid) {
        if (stationUuid == null || stationUuid.isEmpty()) {
            return true; // 无UUID时不限制
        }
        String retryFileName = getIconUrlRetryFileName(stationUuid);
        File permRetry = new File(permanentDir, retryFileName);
        File semiRetry = new File(semiPermanentDir, retryFileName);

        // 找到重试时间戳文件
        File retryFile = null;
        if (permRetry.exists()) {
            retryFile = permRetry;
        } else if (semiRetry.exists()) {
            retryFile = semiRetry;
        }

        if (retryFile == null) {
            // 从未重试过，允许重试
            return true;
        }

        long lastRetry = retryFile.lastModified();
        long elapsed = System.currentTimeMillis() - lastRetry;
        return elapsed >= ICONURL_RETRY_INTERVAL_MS;
    }

    /**
     * 清除 IconUrl 重试时间戳。在电台缓存被删除时调用。
     */
    public void clearIconUrlRetryTime(String stationUuid) {
        if (stationUuid == null || stationUuid.isEmpty()) {
            return;
        }
        String retryFileName = getIconUrlRetryFileName(stationUuid);
        new File(permanentDir, retryFileName).delete();
        new File(semiPermanentDir, retryFileName).delete();
    }
}
