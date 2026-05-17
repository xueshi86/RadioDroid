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

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.WorkManager;
import androidx.work.WorkInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.ActivityMain;

public class DatabaseUpdateWorker extends Worker implements RadioStationRepository.SyncCallback {
    private static final String TAG = "DatabaseUpdateWorker";
    private static final String PREFS_NAME = "database_update_prefs";
    private static final String KEY_IS_UPDATING = "is_updating";
    private static final String KEY_PROGRESS_MESSAGE = "progress_message";
    private static final String KEY_PROGRESS_MESSAGE_RES_ID = "progress_message_res_id";
    private static final String KEY_PROGRESS_CURRENT = "progress_current";
    private static final String KEY_PROGRESS_TOTAL = "progress_total";
    private static final String KEY_UPDATE_ID = "update_id";
    private static final String KEY_UPDATE_START_TIME = "update_start_time";
    private static final String KEY_APP_LAST_FOREGROUND_TIME = "app_last_foreground_time";
    private static final String KEY_UPDATE_CANCELLED = "update_cancelled";
    
    private static final String CHANNEL_ID = "database_update_channel";
    private static final int NOTIFICATION_ID = 1001;
    
    // 静态锁对象，确保只有一个DatabaseUpdateWorker实例在运行
    private static final java.util.concurrent.locks.ReentrantLock sLock = new java.util.concurrent.locks.ReentrantLock();
    
    private SharedPreferences prefs;
    private RadioStationRepository repository;
    private long updateId;
    private volatile boolean shouldStop = false;
    private NotificationManager notificationManager;
    
    public DatabaseUpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        repository = RadioStationRepository.getInstance(context);
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getApplicationContext().getString(R.string.update_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getApplicationContext().getString(R.string.update_notification_channel_description));
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    private ForegroundInfo createForegroundInfo(String message, int progress, int total) {
        Intent intent = new Intent(getApplicationContext(), ActivityMain.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            getApplicationContext(),
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        Notification notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
            .setContentTitle(getApplicationContext().getString(R.string.update_dialog_title))
            .setContentText(message + " (" + progress + "/" + total + ")")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setProgress(total, progress, false)
            .build();
        
        return new ForegroundInfo(NOTIFICATION_ID, notification);
    }
    
    private void updateForegroundNotification(String message, int progress, int total) {
        Intent intent = new Intent(getApplicationContext(), ActivityMain.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            getApplicationContext(),
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        Notification notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
            .setContentTitle(getApplicationContext().getString(R.string.update_dialog_title))
            .setContentText(message + " (" + progress + "/" + total + ")")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setProgress(total, progress, false)
            .build();
        
        notificationManager.notify(NOTIFICATION_ID, notification);
    }
    
    @Override
    public void onStopped() {
        super.onStopped();
        shouldStop = true;
        Log.d(TAG, "Worker stopped - NOT clearing update state to allow resume");
        Log.d(TAG, "Current update ID: " + updateId + ", isUpdating: " + prefs.getBoolean(KEY_IS_UPDATING, false));
        // 不清除更新状态，允许恢复
        // 如果用户明确取消更新，会通过cancelUpdate()方法清除状态
    }
    
    @NonNull
    @Override
    public Result doWork() {
        // 添加线程信息日志
        Log.d(TAG, "Starting database update work on thread: " + Thread.currentThread().getId() + ", name: " + Thread.currentThread().getName());
        
        // 使用锁确保只有一个DatabaseUpdateWorker实例在运行
        sLock.lock();
        try {
            Log.d(TAG, "Acquired lock, starting database update work");
            
            // 检查是否已有更新在进行中
            boolean isAlreadyUpdating = prefs.getBoolean(KEY_IS_UPDATING, false);
            boolean isCancelled = prefs.getBoolean(KEY_UPDATE_CANCELLED, false);
            long existingUpdateId = prefs.getLong(KEY_UPDATE_ID, 0);
            long updateStartTime = prefs.getLong(KEY_UPDATE_START_TIME, 0);
            long cancelTimestamp = prefs.getLong("cancel_timestamp", 0);
            long currentTime = System.currentTimeMillis();
            
            // 如果设置了取消标志，或者取消时间戳在最近10分钟内，不恢复更新
            if (isCancelled || (cancelTimestamp > 0 && (currentTime - cancelTimestamp) < 10 * 60 * 1000)) {
                Log.d(TAG, "Previous update was cancelled, not resuming. isCancelled=" + isCancelled + 
                          ", cancelTimestamp=" + cancelTimestamp + ", currentTime=" + currentTime);
                
                // 生成新的更新ID并重置状态
                updateId = System.currentTimeMillis();
                updateStartTime = System.currentTimeMillis();
                
                // 设置更新状态，并清除取消标志，因为我们现在开始新的更新
                prefs.edit()
                    .putBoolean(KEY_IS_UPDATING, true)
                    .putLong(KEY_UPDATE_ID, updateId)
                    .putLong(KEY_UPDATE_START_TIME, updateStartTime)
                    .putString(KEY_PROGRESS_MESSAGE, getApplicationContext().getString(R.string.update_preparing))
                    .putInt(KEY_PROGRESS_CURRENT, 0)
                    .putInt(KEY_PROGRESS_TOTAL, 0)
                    .putBoolean(KEY_UPDATE_CANCELLED, false)  // 清除取消标志
                    .putLong("cancel_timestamp", 0)  // 清除取消时间戳
                    .commit();
                
                Log.d(TAG, "Starting new update after cancelled one with ID: " + updateId + ", cleared cancel flags");
            }
            // 如果已有更新在进行中且开始时间在30分钟内，继续该更新
            else if (isAlreadyUpdating && existingUpdateId > 0 && updateStartTime > 0 && 
                (currentTime - updateStartTime) < 30 * 60 * 1000) {
                Log.d(TAG, "Resuming existing update with ID: " + existingUpdateId);
                updateId = existingUpdateId;
                
                // 不重置进度状态，继续使用现有的进度
            } else {
                // 生成新的更新ID并重置状态
                updateId = System.currentTimeMillis();
                updateStartTime = System.currentTimeMillis();
                
                // 设置更新状态
                prefs.edit()
                    .putBoolean(KEY_IS_UPDATING, true)
                    .putLong(KEY_UPDATE_ID, updateId)
                    .putLong(KEY_UPDATE_START_TIME, updateStartTime)
                    .putString(KEY_PROGRESS_MESSAGE, getApplicationContext().getString(R.string.progress_preparing_update))
                    .putInt(KEY_PROGRESS_CURRENT, 0)
                    .putInt(KEY_PROGRESS_TOTAL, 0)
                    .commit();
                
                Log.d(TAG, "Starting new update with ID: " + updateId);
            }
            
            // 设置为前台服务，确保应用在后台时也能继续更新
            ForegroundInfo foregroundInfo = createForegroundInfo(
                getApplicationContext().getString(R.string.progress_preparing_update),
                0,
                0
            );
            setForegroundAsync(foregroundInfo);
            Log.d(TAG, "Set foreground service to ensure background updates continue");
            
            try {
                // 检查是否是恢复的更新任务
                boolean isResuming = isAlreadyUpdating && existingUpdateId > 0;
                
                // 如果是最近取消的更新（10分钟内），不使用恢复模式
                if (isCancelled || (cancelTimestamp > 0 && (currentTime - cancelTimestamp) < 10 * 60 * 1000)) {
                    Log.d(TAG, "Detected recent cancellation, forcing new update instead of resume");
                    isResuming = false;
                    
                    // 不在这里清除取消标志，等到真正开始新更新时再清除
                }
                
                if (isResuming) {
                    Log.d(TAG, "Resuming existing update with ID: " + existingUpdateId);
                    updateId = existingUpdateId;
                    
                    // 恢复进度信息
                    String lastProgressMessage = prefs.getString(KEY_PROGRESS_MESSAGE, getApplicationContext().getString(R.string.progress_resuming_update));
                    int lastProgressCurrent = prefs.getInt(KEY_PROGRESS_CURRENT, 0);
                    int lastProgressTotal = prefs.getInt(KEY_PROGRESS_TOTAL, 0);
                    
                    // 通知进度恢复
                    onProgress(lastProgressMessage, lastProgressCurrent, lastProgressTotal);
                } else {
                    Log.d(TAG, "Starting new update with ID: " + updateId);
                    
                    // 在真正开始新更新时，清除取消标志
                    if (isCancelled || (cancelTimestamp > 0 && (currentTime - cancelTimestamp) < 10 * 60 * 1000)) {
                        prefs.edit()
                            .putBoolean(KEY_UPDATE_CANCELLED, false)
                            .putLong("cancel_timestamp", 0)
                            .commit();
                        Log.d(TAG, "Cleared cancellation flags for new update");
                    }
                }
                
                // 执行数据库更新，根据是否是恢复模式传递相应参数
                if (isResuming) {
                    repository.syncAllStationsFromNetworkInternal(getApplicationContext(), this, true);
                } else {
                    // 清空临时数据库，确保全新开始
                    repository.clearTempDatabase();
                    Log.d(TAG, "Cleared temporary database for new update");
                    repository.syncAllStationsFromNetworkInternal(getApplicationContext(), this, false);
                }
                Log.d(TAG, "Database update completed successfully");
                
                // 清除更新状态
                prefs.edit()
                    .putBoolean(KEY_IS_UPDATING, false)
                    .apply();
                
                return Result.success();
            } catch (Exception e) {
                Log.e(TAG, "Database update failed", e);
                
                String userFriendlyMessage = getUserFriendlyErrorMessage(e);
                prefs.edit()
                    .putString(KEY_PROGRESS_MESSAGE, userFriendlyMessage)
                    .commit();
                
                prefs.edit()
                    .putBoolean(KEY_IS_UPDATING, false)
                    .commit();
                
                return Result.failure();
            } finally {
                sLock.unlock();
            }
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in doWork", e);
            
            String userFriendlyMessage = getUserFriendlyErrorMessage(e);
            prefs.edit()
                .putString(KEY_PROGRESS_MESSAGE, userFriendlyMessage)
                .commit();
            
            return Result.failure();
        }
    }

    @Override
    public void onProgress(String message) {
        Log.d(TAG, "Progress: " + message);
        
        prefs.edit()
            .putString(KEY_PROGRESS_MESSAGE, message)
            .putInt(KEY_PROGRESS_MESSAGE_RES_ID, 0)
            .apply();
    }
    
    @Override
    public void onProgress(String message, int progress, int total) {
        Log.d(TAG, "Progress: " + message + " (" + progress + "/" + total + ")");
        
        if (isStopped()) {
            throw new RuntimeException(getApplicationContext().getString(R.string.update_cancelled));
        }
        
        boolean isCancelled = prefs.getBoolean(KEY_UPDATE_CANCELLED, false);
        if (isCancelled) {
            throw new RuntimeException(getApplicationContext().getString(R.string.update_cancelled));
        }
        
        prefs.edit()
            .putString(KEY_PROGRESS_MESSAGE, message)
            .putInt(KEY_PROGRESS_MESSAGE_RES_ID, 0)
            .putInt(KEY_PROGRESS_CURRENT, progress)
            .putInt(KEY_PROGRESS_TOTAL, total)
            .apply();
        
        updateForegroundNotification(message, progress, total);
    }
    
    @Override
    public void onSuccess(String message) {
        Log.d(TAG, "Database update success: " + message);
        // 更新完成状态 - 使用commit()确保同步更新，立即持久化
        prefs.edit()
            .putString(KEY_PROGRESS_MESSAGE, message)
            .commit();
    }
    
    @Override
    public boolean onConfirmReplace(String message, int tempCount, int mainCount) {
        // 在WorkManager中自动确认替换，不需要用户交互
        Log.d(TAG, "Auto-confirming database replace: " + message);
        return true;
    }
    
    @Override
    public void onError(String error) {
        Log.e(TAG, "Database update error: " + error);
        // 更新错误状态 - 使用commit()确保同步更新，立即持久化
        prefs.edit()
            .putString(KEY_PROGRESS_MESSAGE, getApplicationContext().getString(R.string.update_failed) + ": " + error)
            .commit();
    }
    
    private String getUserFriendlyErrorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            message = e.getClass().getSimpleName();
        }
        
        if (message.contains("migration") || message.contains("Migration")) {
            return getApplicationContext().getString(R.string.update_failed_db_migration);
        } else if (message.contains("network") || message.contains("Network") || 
                   message.contains("connect") || message.contains("Connect") ||
                   message.contains("timeout") || message.contains("Timeout") ||
                   message.contains("UnknownHost") || message.contains("SocketException")) {
            return getApplicationContext().getString(R.string.update_failed_network);
        } else if (message.contains("disk") || message.contains("Disk") || 
                   message.contains("space") || message.contains("Space") ||
                   message.contains("SQLiteFull")) {
            return getApplicationContext().getString(R.string.update_failed_storage);
        } else {
            return getApplicationContext().getString(R.string.update_failed_message, message);
        }
    }

    /**
     * 检查是否有正在进行的更新
     */
    public static boolean isUpdating(Context context) {
        // 首先检查取消标志，如果设置了取消标志，直接返回false
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean isCancelled = prefs.getBoolean(KEY_UPDATE_CANCELLED, false);
        if (isCancelled) {
            Log.d(TAG, "Update was cancelled, returning false");
            return false;
        }
        
        // 检查更新ID，如果为0表示没有更新
        long updateId = prefs.getLong(KEY_UPDATE_ID, 0);
        if (updateId == 0) {
            Log.d(TAG, "Update ID is 0, no update in progress");
            return false;
        }
        
        // 首先检查SharedPreferences中的状态，不使用同步块避免主线程阻塞
        boolean isUpdatingInPrefs = prefs.getBoolean(KEY_IS_UPDATING, false);
        Log.d(TAG, "SharedPreferences isUpdating: " + isUpdatingInPrefs + ", updateId: " + updateId);
        
        // 如果SharedPreferences中显示正在更新，检查更新开始时间
        if (isUpdatingInPrefs) {
            long updateStartTime = prefs.getLong(KEY_UPDATE_START_TIME, 0);
            long currentTime = System.currentTimeMillis();
            Log.d(TAG, "SharedPreferences shows updating, checking start time: " + updateStartTime + ", current: " + currentTime);
            
            // 如果更新开始时间在最近60分钟内，认为更新可能仍在进行中
            // 增加时间窗口，给应用切换更多时间
            if (updateStartTime > 0 && (currentTime - updateStartTime) < 60 * 60 * 1000) {
                Log.d(TAG, "Update started recently, assuming still in progress");
                
                // 尝试获取锁，但不阻塞主线程
                if (sLock.tryLock()) {
                    try {
                        Log.d(TAG, "Acquired lock in isUpdating without blocking");
                        
                        // 额外检查WorkManager状态，确保任务确实存在
                        try {
                            WorkManager workManager = WorkManager.getInstance(context);
                            List<WorkInfo> workInfos = workManager.getWorkInfosByTag("database_update").get();
                            Log.d(TAG, "WorkManager workInfos: " + (workInfos != null ? workInfos.size() + " items" : "null"));
                            
                            if (workInfos != null) {
                                for (WorkInfo workInfo : workInfos) {
                                    Log.d(TAG, "WorkInfo state: " + workInfo.getState() + ", id: " + workInfo.getId());
                                    if (workInfo.getState() == WorkInfo.State.RUNNING || 
                                        workInfo.getState() == WorkInfo.State.ENQUEUED) {
                                        Log.d(TAG, "Confirmed: WorkManager has running/enqueued task, update is in progress");
                                        return true;
                                    }
                                }
                            }
                            
                            // 如果SharedPreferences显示正在更新但WorkManager中没有任务，可能是状态不一致
                            Log.d(TAG, "SharedPreferences shows updating but no WorkManager task found, checking if recently in foreground");
                            long appLastForegroundTime = prefs.getLong(KEY_APP_LAST_FOREGROUND_TIME, 0);
                            if (appLastForegroundTime > 0 && (currentTime - appLastForegroundTime) < 5 * 60 * 1000) {
                                Log.d(TAG, "App was recently in foreground, assuming update is still in progress");
                                return true;
                            }
                            
                            Log.d(TAG, "No evidence of active update found, resetting state");
                            prefs.edit().putBoolean(KEY_IS_UPDATING, false).commit();
                            return false;
                        } catch (Exception e) {
                            Log.e(TAG, "Error checking WorkManager status", e);
                            // 如果检查WorkManager失败，但SharedPreferences显示正在更新且开始时间不久，保持更新状态
                            return true;
                        }
                    } finally {
                        sLock.unlock();
                    }
                } else {
                    // 无法获取锁，可能doWork方法正在执行，检查WorkManager的实际状态
                    Log.d(TAG, "Could not acquire lock in isUpdating, checking WorkManager state");
                    
                    try {
                        WorkManager workManager = WorkManager.getInstance(context);
                        List<WorkInfo> workInfos = workManager.getWorkInfosByTag("database_update").get();
                        Log.d(TAG, "WorkManager workInfos when lock unavailable: " + (workInfos != null ? workInfos.size() + " items" : "null"));
                        
                        if (workInfos != null) {
                            for (WorkInfo workInfo : workInfos) {
                                Log.d(TAG, "WorkInfo state when lock unavailable: " + workInfo.getState() + ", id: " + workInfo.getId());
                                if (workInfo.getState() == WorkInfo.State.RUNNING || 
                                    workInfo.getState() == WorkInfo.State.ENQUEUED) {
                                    Log.d(TAG, "WorkManager confirms task is running/enqueued when lock unavailable");
                                    return true;
                                }
                            }
                        }
                        
                        // 如果WorkManager中没有运行的任务，但SharedPreferences显示正在更新，说明任务可能被系统暂停了
                        Log.d(TAG, "WorkManager shows no running task, but SharedPreferences says updating - task may be paused by system");
                        
                        // 在这种情况下，返回false，让UI知道更新可能被暂停
                        return false;
                    } catch (Exception e) {
                        Log.e(TAG, "Error checking WorkManager state when lock unavailable", e);
                        // 如果检查WorkManager失败，返回SharedPreferences状态作为后备
                        return isUpdatingInPrefs;
                    }
                }
            } else {
                Log.d(TAG, "Update started too long ago, resetting state");
                prefs.edit().putBoolean(KEY_IS_UPDATING, false).commit();
                return false;
            }
        }
        
        // 如果SharedPreferences显示没有在更新，也检查WorkManager以防状态不同步
        try {
            WorkManager workManager = WorkManager.getInstance(context);
            // 使用同步方法而不是LiveData
            List<WorkInfo> workInfos = workManager.getWorkInfosByTag("database_update").get();
            Log.d(TAG, "WorkManager workInfos: " + (workInfos != null ? workInfos.size() + " items" : "null"));
            
            if (workInfos != null) {
                for (WorkInfo workInfo : workInfos) {
                    Log.d(TAG, "WorkInfo state: " + workInfo.getState() + ", id: " + workInfo.getId());
                    if (workInfo.getState() == WorkInfo.State.RUNNING || 
                        workInfo.getState() == WorkInfo.State.ENQUEUED) {
                        
                        Log.d(TAG, "Found running or enqueued work, updating SharedPreferences");
                        
                        // 更新SharedPreferences状态
                        prefs.edit().putBoolean(KEY_IS_UPDATING, true).commit();
                        
                        Log.d(TAG, "Returning true - update is in progress");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // 如果检查WorkManager失败，只依赖SharedPreferences
            Log.e(TAG, "Error checking WorkManager status", e);
        }
        
        Log.d(TAG, "No update in progress, returning false");
        return false;
    }
    
    /**
     * 重置更新状态
     */
    public static void resetUpdateState(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_IS_UPDATING, false).apply();
        Log.d(TAG, "Update state reset");
    }
    
    /**
     * 更新应用前台时间
     */
    public static void updateAppForegroundTime(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_APP_LAST_FOREGROUND_TIME, System.currentTimeMillis()).apply();
        Log.d(TAG, "App foreground time updated");
    }
    
    /**
     * 获取当前更新进度
     */
    public static UpdateProgress getProgress(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return new UpdateProgress(
            prefs.getBoolean(KEY_IS_UPDATING, false),
            prefs.getString(KEY_PROGRESS_MESSAGE, ""),
            prefs.getInt(KEY_PROGRESS_CURRENT, 0),
            prefs.getInt(KEY_PROGRESS_TOTAL, 0)
        );
    }
    
    /**
     * 取消更新
     */
    public static void cancelUpdate(Context context) {
        // 使用同步块确保与doWork和isUpdating方法保持一致性
        synchronized (sLock) {
            Log.d(TAG, "Starting cancelUpdate process");
            
            // 清除更新状态，包括所有相关字段，确保下次启动新更新而不是恢复
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            
            // 设置取消标志，防止恢复逻辑误判
            prefs.edit()
                .putBoolean(KEY_IS_UPDATING, false)
                .putLong(KEY_UPDATE_ID, 0)
                .putLong(KEY_UPDATE_START_TIME, 0)
                .putLong(KEY_APP_LAST_FOREGROUND_TIME, 0)
                .putString(KEY_PROGRESS_MESSAGE, context.getString(R.string.update_cancelled))
                .putInt(KEY_PROGRESS_CURRENT, 0)
                .putInt(KEY_PROGRESS_TOTAL, 0)
                .putBoolean(KEY_UPDATE_CANCELLED, true)
                .putLong("cancel_timestamp", System.currentTimeMillis())
                .apply();
            
            // 取消WorkManager任务 - 使用多种方式确保彻底取消
            androidx.work.WorkManager workManager = androidx.work.WorkManager.getInstance(context);
            
            // 方法1：取消唯一工作
            workManager.cancelUniqueWork("database_update_work");
            Log.d(TAG, "Cancelled unique work: database_update_work");
            
            // 方法2：通过标签取消所有相关工作
            workManager.cancelAllWorkByTag("database_update");
            Log.d(TAG, "Cancelled all work by tag: database_update");
            
            // 方法3：尝试获取所有工作并取消
            try {
                java.util.List<androidx.work.WorkInfo> workInfos = workManager.getWorkInfosByTag("database_update").get();
                if (workInfos != null) {
                    for (androidx.work.WorkInfo workInfo : workInfos) {
                        Log.d(TAG, "Cancelling work: " + workInfo.getId() + ", state: " + workInfo.getState());
                        workManager.cancelWorkById(workInfo.getId());
                        
                        // 额外措施：如果是正在运行的任务，尝试立即终止
                        if (workInfo.getState() == androidx.work.WorkInfo.State.RUNNING) {
                            try {
                                // 尝试强制停止工作
                                workManager.pruneWork();
                                Log.d(TAG, "Pruned work to force termination: " + workInfo.getId());
                            } catch (Exception e) {
                                Log.e(TAG, "Error pruning work", e);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error cancelling work by ID", e);
            }
            
            // 方法4：尝试清除所有已完成的工作，确保状态干净
            try {
                workManager.pruneWork();
                Log.d(TAG, "Pruned all completed work");
            } catch (Exception e) {
                Log.e(TAG, "Error pruning work", e);
            }
            
            // 取消通知
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.cancel(NOTIFICATION_ID);
            
            // 额外措施：清空临时数据库，确保取消后不会从上次进度恢复
            try {
                RadioStationRepository repository = RadioStationRepository.getInstance(context);
                repository.clearTempDatabase();
                Log.d(TAG, "Successfully cleared temporary database to prevent resume after cancellation");
            } catch (Exception e) {
                Log.e(TAG, "Error clearing temporary database", e);
            }
            
            Log.d(TAG, "CancelUpdate process completed");
        } // 结束synchronized块
    }
    
    /**
     * 更新进度信息类
     */
    public static class UpdateProgress {
        public final boolean isUpdating;
        public final String message;
        public final int current;
        public final int total;
        
        public UpdateProgress(boolean isUpdating, String message, int current, int total) {
            this.isUpdating = isUpdating;
            this.message = message;
            this.current = current;
            this.total = total;
        }
        
        public int getPercentage() {
            if (total <= 0) return 0;
            return (int) (current * 100.0 / total);
        }
    }
}