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
    private static final String CHANNEL_ID = "app_update";
    private static final int NOTIFICATION_ID = 0x5EED;

    private static volatile boolean isDownloading = false;

    private UpdateDownloader() {
    }

    public static void startDownload(Context context, UpdateInfo info) {
        if (isDownloading || info == null || info.apkUrl == null) {
            return;
        }
        isDownloading = true;
        createChannel(context);
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
            } catch (Exception e) {
                Log.w(TAG, "Download failed: " + e.getMessage());
                if (apkFile != null) {
                    //noinspection ResultOfMethodCallIgnored
                    apkFile.delete();
                }
                showDownloadFailedNotification(appContext);
            } finally {
                isDownloading = false;
            }
        }, "AppUpdateDownload").start();
    }

    public static void openApkForInstall(Context context, File apkFile) {
        try {
            Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No installer found: " + e.getMessage());
            Toast.makeText(context, R.string.update_open_installer_failed, Toast.LENGTH_LONG).show();
        }
    }

    public static void showUpdateAvailableNotification(Context context, UpdateInfo info) {
        createChannel(context);
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

    static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_app_update_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(context.getString(R.string.notification_channel_app_update_description));
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
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
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    written += read;
                    int percent = total > 0 ? (int) (written * 100 / total) : -1;
                    if (percent >= 0 && percent != lastPercent) {
                        lastPercent = percent;
                        updateProgressNotification(context, percent);
                    }
                }
                output.flush();
            }
        }
    }

    private static void updateProgressNotification(Context context, int percent) {
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
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
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.update_download_complete))
                .setContentText(context.getString(R.string.update_download_complete))
                .setSmallIcon(R.drawable.ic_refresh)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
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
