package net.programmierecke.radiodroid2.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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
        
        // 创建WorkManager任务
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            // 确保任务可以在应用后台运行
            .setRequiresDeviceIdle(false)
            .setRequiresCharging(false)
            .build();
        
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
        
        // 不再显示初始通知，让应用内进度对话框处理用户界面
    }
    
    /**
     * 取消数据库更新
     */
    public static void cancelUpdate(Context context) {
        // 取消WorkManager任务
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        
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
                "数据库更新",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("电台数据库更新通知");
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
            .setContentTitle("电台数据库更新")
            .setContentText("正在后台更新电台数据库，您可以继续使用其他功能")
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
            .setContentTitle("电台数据库更新")
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