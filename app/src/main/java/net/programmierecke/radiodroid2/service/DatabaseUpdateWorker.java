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
    private static final String KEY_PROGRESS_CURRENT = "progress_current";
    private static final String KEY_PROGRESS_TOTAL = "progress_total";
    private static final String KEY_UPDATE_ID = "update_id";
    private static final String KEY_UPDATE_START_TIME = "update_start_time";
    private static final String KEY_APP_LAST_FOREGROUND_TIME = "app_last_foreground_time";
    
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
                "数据库更新",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("显示数据库更新进度");
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
            .setContentTitle("正在更新电台数据库")
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
            .setContentTitle("正在更新电台数据库")
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
            long existingUpdateId = prefs.getLong(KEY_UPDATE_ID, 0);
            long updateStartTime = prefs.getLong(KEY_UPDATE_START_TIME, 0);
            long currentTime = System.currentTimeMillis();
            
            // 如果已有更新在进行中且开始时间在30分钟内，继续该更新
            if (isAlreadyUpdating && existingUpdateId > 0 && updateStartTime > 0 && 
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
                    .putString(KEY_PROGRESS_MESSAGE, "正在准备更新...")
                    .putInt(KEY_PROGRESS_CURRENT, 0)
                    .putInt(KEY_PROGRESS_TOTAL, 0)
                    .apply();
                
                Log.d(TAG, "Starting new update with ID: " + updateId);
            }
            
            // 设置为前台服务，确保应用在后台时也能继续更新
            ForegroundInfo foregroundInfo = createForegroundInfo(
                "正在准备更新...",
                0,
                0
            );
            setForegroundAsync(foregroundInfo);
            Log.d(TAG, "Set foreground service to ensure background updates continue");
            
            try {
                // 检查是否是恢复的更新任务
                boolean isResuming = isAlreadyUpdating && existingUpdateId > 0;
                
                if (isResuming) {
                    Log.d(TAG, "Resuming existing update with ID: " + existingUpdateId);
                    updateId = existingUpdateId;
                    
                    // 恢复进度信息
                    String lastProgressMessage = prefs.getString(KEY_PROGRESS_MESSAGE, "正在恢复更新...");
                    int lastProgressCurrent = prefs.getInt(KEY_PROGRESS_CURRENT, 0);
                    int lastProgressTotal = prefs.getInt(KEY_PROGRESS_TOTAL, 0);
                    
                    // 通知进度恢复
                    onProgress(lastProgressMessage, lastProgressCurrent, lastProgressTotal);
                } else {
                    Log.d(TAG, "Starting new update with ID: " + updateId);
                }
                
                // 执行数据库更新，根据是否是恢复模式传递相应参数
                if (isResuming) {
                    repository.syncAllStationsFromNetworkInternal(getApplicationContext(), this, true);
                } else {
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
                
                // 保存错误信息到SharedPreferences
                String errorMessage = "更新失败: " + e.getMessage();
                prefs.edit()
                    .putString(KEY_PROGRESS_MESSAGE, errorMessage)
                    .commit();
                
                // 清除更新状态
                prefs.edit()
                    .putBoolean(KEY_IS_UPDATING, false)
                    .commit();
                
                return Result.failure();
            } finally {
                sLock.unlock();
            }
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in doWork", e);
            
            // 保存错误信息到SharedPreferences
            String errorMessage = "更新失败: " + e.getMessage();
            prefs.edit()
                .putString(KEY_PROGRESS_MESSAGE, errorMessage)
                .commit();
            
            return Result.failure();
        }
    }

    @Override
    public void onProgress(String message) {
        Log.d(TAG, "Progress: " + message);
        
        // 更新进度信息 - 使用commit()确保同步更新，立即持久化
        prefs.edit()
            .putString(KEY_PROGRESS_MESSAGE, message)
            .commit();
    }
    
    @Override
    public void onProgress(String message, int progress, int total) {
        Log.d(TAG, "Progress: " + message + " (" + progress + "/" + total + ")");
        
        // 不检查isStopped()，确保进度始终被写入，即使Worker被停止
        // 这样可以避免多个Worker实例互相干扰导致进度不更新
        
        prefs.edit()
            .putString(KEY_PROGRESS_MESSAGE, message)
            .putInt(KEY_PROGRESS_CURRENT, progress)
            .putInt(KEY_PROGRESS_TOTAL, total)
            .commit();
        
        prefs.getAll();
        
        // 更新前台服务通知
        updateForegroundNotification(message, progress, total);
        
        Log.d(TAG, "准备调用forceWriteProgressToDisk()，progress=" + progress);
        try {
            forceWriteProgressToDisk(message, progress, total);
            Log.d(TAG, "forceWriteProgressToDisk()调用完成");
        } catch (Exception e) {
            Log.e(TAG, "forceWriteProgressToDisk()调用失败: " + e.getMessage(), e);
        }
    }
    
    private void forceWriteProgressToDisk(String message, int progress, int total) {
        try {
            String prefsPath = getApplicationContext().getApplicationInfo().dataDir + "/shared_prefs/database_update_prefs.xml";
            File prefsFile = new File(prefsPath);
            Log.d(TAG, "尝试写入SharedPreferences文件: " + prefsPath + ", exists: " + prefsFile.exists());
            
            if (prefsFile.exists()) {
                prefsFile.setLastModified(System.currentTimeMillis());
                Log.d(TAG, "强制刷新SharedPreferences文件时间戳");
                
                String xmlContent = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                    "<map>\n" +
                    "    <boolean name=\"" + KEY_IS_UPDATING + "\" value=\"true\" />\n" +
                    "    <string name=\"" + KEY_PROGRESS_MESSAGE + "\">" + escapeXml(message) + "</string>\n" +
                    "    <int name=\"" + KEY_PROGRESS_CURRENT + "\" value=\"" + progress + "\" />\n" +
                    "    <int name=\"" + KEY_PROGRESS_TOTAL + "\" value=\"" + total + "\" />\n" +
                    "    <long name=\"" + KEY_UPDATE_START_TIME + "\" value=\"" + prefs.getLong(KEY_UPDATE_START_TIME, 0) + "\" />\n" +
                    "    <long name=\"" + KEY_UPDATE_ID + "\" value=\"" + prefs.getLong(KEY_UPDATE_ID, 0) + "\" />\n" +
                    "</map>";
                
                FileOutputStream fos = new FileOutputStream(prefsFile);
                fos.write(xmlContent.getBytes("UTF-8"));
                fos.close();
                Log.d(TAG, "直接写入SharedPreferences文件成功: progress=" + progress);
            } else {
                Log.w(TAG, "SharedPreferences文件不存在，跳过直接写入: " + prefsPath);
            }
        } catch (IOException e) {
            Log.w(TAG, "直接写入SharedPreferences文件失败: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            Log.w(TAG, "强制刷新文件时间戳失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
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
            .putString(KEY_PROGRESS_MESSAGE, "更新失败: " + error)
            .commit();
    }
    
    /**
     * 检查是否有正在进行的更新
     */
    public static boolean isUpdating(Context context) {
        // 首先检查SharedPreferences中的状态，不使用同步块避免主线程阻塞
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean isUpdatingInPrefs = prefs.getBoolean(KEY_IS_UPDATING, false);
        Log.d(TAG, "SharedPreferences isUpdating: " + isUpdatingInPrefs);
        
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
                            prefs.edit().putBoolean(KEY_IS_UPDATING, false).apply();
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
                prefs.edit().putBoolean(KEY_IS_UPDATING, false).apply();
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
                        prefs.edit().putBoolean(KEY_IS_UPDATING, true).apply();
                        
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
            // 清除更新状态
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                .putBoolean(KEY_IS_UPDATING, false)
                .apply();
        
            // 取消WorkManager任务
            androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("database_update");
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