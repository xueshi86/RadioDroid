package net.programmierecke.radiodroid2.updater;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UpdateDownloader {
    private static final String TAG = "UpdateDownloader";
    /** 更新可用与下载完成通知使用高重要性渠道，确保用户能看到（Issue #55） */
    private static final String CHANNEL_ID = "app_update_v2";
    /** 下载进度使用低重要性渠道，避免反复打扰 */
    private static final String CHANNEL_ID_PROGRESS = "app_update_progress";
    /** 旧渠道（低重要性），升级后删除以免残留 */
    private static final String LEGACY_CHANNEL_ID = "app_update";
    private static final int NOTIFICATION_ID = 0x5EED;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile boolean isDownloading = false;
    private static volatile DownloadCallback downloadCallback;

    private UpdateDownloader() {
    }

    /** 供设置页在前台展示「立即安装」对话框，回调在主线程执行 */
    public interface DownloadCallback {
        void onDownloadFinished(File apkFile);

        void onDownloadFailed();
    }

    public static void setDownloadCallback(DownloadCallback callback) {
        downloadCallback = callback;
    }

    public static void startDownload(Context context, UpdateInfo info) {
        if (isDownloading || info == null || info.apkUrl == null) {
            return;
        }
        isDownloading = true;
        createChannels(context);
        Toast.makeText(context, R.string.update_download_started, Toast.LENGTH_SHORT).show();
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            File apkFile = null;
            try {
                File dir = new File(appContext.getCacheDir(), "update");
                deleteDirContents(dir);
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("Cannot create cache directory");
                }
                String fileName = (info.apkFileName != null && !info.apkFileName.isEmpty())
                        ? info.apkFileName : "RadioDroid-update.apk";
                apkFile = new File(dir, fileName);
                downloadApk(appContext, info, apkFile);
                showDownloadCompleteNotification(appContext, apkFile);
                notifyDownloadFinished(apkFile);
            } catch (Exception e) {
                Log.w(TAG, "Download failed: " + e.getMessage());
                if (apkFile != null) {
                    //noinspection ResultOfMethodCallIgnored
                    apkFile.delete();
                }
                showDownloadFailedNotification(appContext);
                notifyDownloadFailed(appContext);
            } finally {
                isDownloading = false;
            }
        }, "AppUpdateDownload").start();
    }

    private static void notifyDownloadFinished(final File apkFile) {
        final DownloadCallback callback = downloadCallback;
        if (callback != null) {
            MAIN_HANDLER.post(() -> callback.onDownloadFinished(apkFile));
        }
    }

    private static void notifyDownloadFailed(final Context context) {
        MAIN_HANDLER.post(() -> {
            Toast.makeText(context, R.string.update_download_failed, Toast.LENGTH_LONG).show();
            DownloadCallback callback = downloadCallback;
            if (callback != null) {
                callback.onDownloadFailed();
            }
        });
    }

    public static void openApkForInstall(Context context, File apkFile) {
        try {
            Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setClipData(android.content.ClipData.newRawUri("", apkUri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No installer found: " + e.getMessage());
            Toast.makeText(context, R.string.update_open_installer_failed, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 「立即安装」入口：Android 8.0+ 若未授予「安装未知应用」权限，先跳转到系统设置页，
     * 用户授权后再次点击即可安装，避免点击后毫无反应（Issue #55）。
     */
    public static void installApk(Context context, File apkFile) {
        if (apkFile == null || !apkFile.exists()) {
            Toast.makeText(context, R.string.update_apk_missing, Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Toast.makeText(context, R.string.update_allow_unknown_sources, Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "No unknown sources settings: " + e.getMessage());
                Toast.makeText(context, R.string.update_open_installer_failed, Toast.LENGTH_LONG).show();
            }
            return;
        }
        openApkForInstall(context, apkFile);
    }

    public static void showUpdateAvailableNotification(Context context, UpdateInfo info) {
        createChannels(context);
        Intent intent = new Intent(context, UpdateReceiver.class);
        intent.setAction(UpdateReceiver.ACTION_DOWNLOAD_UPDATE);
        intent.putExtra(UpdateReceiver.EXTRA_VERSION, info.latestVersion);
        intent.putExtra(UpdateReceiver.EXTRA_URL, info.apkUrl);
        intent.putExtra(UpdateReceiver.EXTRA_FILE_NAME, info.apkFileName);
        intent.putExtra(UpdateReceiver.EXTRA_SIZE, info.apkSize);
        intent.putExtra(UpdateReceiver.EXTRA_NOTES, info.releaseNotes);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.update_found_title, info.latestVersion))
                .setContentText(context.getString(R.string.update_download))
                .setSmallIcon(R.drawable.ic_refresh)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification);
    }

    static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_app_update_name),
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(context.getString(R.string.notification_channel_app_update_description));
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setShowBadge(true);
            manager.createNotificationChannel(channel);

            NotificationChannel progressChannel = new NotificationChannel(
                    CHANNEL_ID_PROGRESS,
                    context.getString(R.string.notification_channel_app_update_progress_name),
                    NotificationManager.IMPORTANCE_LOW);
            progressChannel.setDescription(context.getString(R.string.notification_channel_app_update_description));
            progressChannel.enableLights(false);
            progressChannel.enableVibration(false);
            progressChannel.setShowBadge(false);
            manager.createNotificationChannel(progressChannel);

            // 旧的低重要性渠道会导致「下载完成」通知不弹出，升级后删除
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID);
        }
    }

    private static void downloadApk(Context context, UpdateInfo info, File target) throws IOException {
        RadioDroidApp app = (RadioDroidApp) context.getApplicationContext();
        OkHttpClient client = app.getHttpClient().newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url(info.apkUrl).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            long total = response.body().contentLength();
            if (total <= 0) {
                total = info.apkSize;
            }
            try (InputStream input = response.body().byteStream();
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                long written = 0;
                int lastPercent = -1;
                long lastProgressUpdate = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    written += read;
                    int percent = total > 0 ? (int) (written * 100 / total) : -1;
                    // 限制进度通知频率：系统对同一应用有每秒约 5 条的通知限流，
                    // 刷新过快会导致「下载完成」通知被直接丢弃（Issue #55）
                    long now = SystemClock.elapsedRealtime();
                    if (percent >= 0 && percent != lastPercent && now - lastProgressUpdate >= 1000) {
                        lastPercent = percent;
                        lastProgressUpdate = now;
                        updateProgressNotification(context, percent);
                    }
                }
                output.flush();
            }
        }
    }

    private static void updateProgressNotification(Context context, int percent) {
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID_PROGRESS)
                .setContentTitle(context.getString(R.string.update_downloading))
                .setContentText(percent + "%")
                .setSmallIcon(R.drawable.ic_refresh)
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build();
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification);
    }

    private static void showDownloadCompleteNotification(Context context, File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        // 部分系统仅通过 ClipData 向安装器授予读取权限
        intent.setClipData(android.content.ClipData.newRawUri("", apkUri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.update_download_complete))
                .setContentText(context.getString(R.string.update_install_now))
                .setSmallIcon(R.drawable.ic_refresh)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build();
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification);
    }

    private static void showDownloadFailedNotification(Context context) {
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.update_download_failed))
                .setSmallIcon(R.drawable.ic_refresh)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification);
    }

    private static void deleteDirContents(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
