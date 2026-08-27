package net.programmierecke.radiodroid2.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.FragmentSettings;

public class DatabaseUpdateManager {
    private static final String TAG = "DatabaseUpdateManager";
    private static final String CHANNEL_ID = "DatabaseUpdateChannel";
    private static final String WORK_NAME = "database_update_work";
    private static final int NOTIFICATION_ID = 1002;
    
    /**
     * 启动数据库更新
     */
    public static void startUpdate(Context context) {
        // 检查是否已经有更新在进行中
        if (DatabaseUpdateWorker.isUpdating(context)) {
            Log.d(TAG, "Update already in progress, not starting new update");
            return;
        }
        
        // 彻底清除所有状态，确保新更新可以正常进行
        SharedPreferences prefs = context.getSharedPreferences("database_update_prefs", Context.MODE_PRIVATE);
        
        // 检查是否有取消标志或取消时间戳
        boolean isCancelled = prefs.getBoolean("update_cancelled", false);
        long cancelTimestamp = prefs.getLong("cancel_timestamp", 0);
        long currentTime = System.currentTimeMillis();
        
        // 如果是最近取消的更新（10分钟内），清除所有取消标志
        if (isCancelled || (cancelTimestamp > 0 && (currentTime - cancelTimestamp) < 10 * 60 * 1000)) {
            Log.d(TAG, "Clearing recent cancel state before starting new update");
            prefs.edit()
                .putBoolean("update_cancelled", false)
                .putLong("cancel_timestamp", 0)
                .commit();
        }
        
        // 设置新的更新状态
        prefs.edit()
            .putBoolean("is_updating", false)  // 先设为false，让Worker来设置为true
            .putLong("update_id", System.currentTimeMillis())  // 设置新的更新ID
            .putLong("update_start_time", System.currentTimeMillis())
            .putString("progress_message", context.getString(R.string.update_preparing))
            .putInt("progress_current", 0)
            .putInt("progress_total", 0)
            .commit();
        
        // 创建WorkManager任务
        Constraints.Builder constraintsBuilder = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresCharging(false);
        
        // 只在API级别23及以上时设置setRequiresDeviceIdle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            constraintsBuilder.setRequiresDeviceIdle(false);
        }
        
        Constraints constraints = constraintsBuilder.build();
        
        OneTimeWorkRequest updateRequest = new OneTimeWorkRequest.Builder(DatabaseUpdateWorker.class)
            .setConstraints(constraints)
            // 设置为长运行任务，确保在应用后台也能继续执行
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, java.util.concurrent.TimeUnit.SECONDS)
            .addTag("database_update")
            .build();
        
        // 使用REPLACE策略确保只有一个更新任务在运行
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            updateRequest
        );
        
        Log.d(TAG, "Started new database update work");
        // 不再显示初始通知，让应用内进度对话框处理用户界面
    }

    /**
     * 启动增量数据库更新（lastchange 端点，轻量）。
     * 与全量更新使用同一 Worker 的静态锁互斥；独立 work name 避免互相 REPLACE。
     */
    public static void startIncrementalUpdate(Context context) {
        if (DatabaseUpdateWorker.isUpdating(context)) {
            Log.d(TAG, "Update already in progress, not starting incremental update");
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        Data inputData = new Data.Builder().putString("mode", "incremental").build();

        // 重置增量更新的状态，确保 UI（进度对话框）能正确感知任务开始，
        // 避免沿用上次更新的 update_id/update_start_time 造成"系统暂停"误判
        SharedPreferences prefs = context.getSharedPreferences("database_update_prefs", Context.MODE_PRIVATE);

        // 清除历史取消标记：DatabaseUpdateWorker.cancelUpdate() 会写入 update_cancelled/cancel_timestamp，
        // 若不清除，isUpdating() 会因取消标记恒返回 false（界面与后台任务脱节），
        // 且 Worker.onProgress() 的二次取消检查会把运行中的增量同步误判为"已取消"而中断。
        prefs.edit()
                .putBoolean("update_cancelled", false)
                .putLong("cancel_timestamp", 0)
                .putBoolean("is_updating", false)  // 由 Worker 在运行时置为 true
                .putLong("update_id", System.currentTimeMillis())
                .putLong("update_start_time", System.currentTimeMillis())
                .putString("progress_message", context.getString(R.string.update_preparing))
                .putInt("progress_current", 0)
                .putInt("progress_total", 0)
                .commit();

        OneTimeWorkRequest updateRequest = new OneTimeWorkRequest.Builder(DatabaseUpdateWorker.class)
                .setInputData(inputData)
                .setConstraints(constraints)
                .addTag("database_update")
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                "database_incremental_update_work",
                ExistingWorkPolicy.REPLACE,
                updateRequest
        );

        Log.d(TAG, "Started incremental database update work");
    }
    
    /**
     * 取消数据库更新
     */
    public static void cancelUpdate(Context context) {
        // 取消WorkManager任务 - 使用多种方式确保彻底取消
        WorkManager workManager = WorkManager.getInstance(context);
        
        // 方法1：取消唯一工作
        workManager.cancelUniqueWork(WORK_NAME);
        
        // 方法2：通过标签取消所有相关工作
        workManager.cancelAllWorkByTag("database_update");
        
        // 清除Worker中的状态
        DatabaseUpdateWorker.cancelUpdate(context);
        
        // 取消通知
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_ID);
    }
    
    /**
     * 检查是否有数据库更新正在进行
     */
    public static boolean isUpdating(Context context) {
        return DatabaseUpdateWorker.isUpdating(context);
    }
    
    /**
     * 获取当前更新进度
     */
    public static DatabaseUpdateWorker.UpdateProgress getProgress(Context context) {
        return DatabaseUpdateWorker.getProgress(context);
    }
    
    /**
     * 显示简单的通知
     */
    private static void showSimpleNotification(Context context) {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        
        // 创建通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(context.getString(R.string.notification_channel_description));
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
        
        // 创建点击通知的Intent
        Intent notificationIntent = new Intent(context, FragmentSettings.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        // 创建通知
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build();
        
        // 显示通知
        notificationManager.notify(NOTIFICATION_ID, notification);
    }
    
    /**
     * 更新通知内容
     */
    public static void updateNotification(Context context, String message, int progress, int total) {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        
        // 创建点击通知的Intent
        Intent notificationIntent = new Intent(context, FragmentSettings.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        // 创建通知
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS);
        
        // 添加进度条
        if (total > 0) {
            builder.setProgress(total, progress, false);
            builder.setContentText(message + " (" + progress + "/" + total + ")");
        } else {
            builder.setProgress(100, 0, true);
        }
        
        // 更新通知
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }
    
    /**
     * 清除通知
     */
    public static void clearNotification(Context context) {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_ID);
    }
}