package net.programmierecke.radiodroid2.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;


import java.io.File;
import java.util.Date;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.service.DatabaseUpdateManager;
import net.programmierecke.radiodroid2.service.DatabaseUpdateWorker;


public class DatabaseUpdateProgressDialog {
    private static final String TAG = "DBUpdateProgressDialog";
    private static final int UPDATE_INTERVAL = 500; // 更新间隔(毫秒)
    
    private Context context;
    private Dialog dialog;
    
    // 资源ID映射表，用于根据消息内容查找对应的资源ID
    private static final java.util.HashMap<String, Integer> MESSAGE_TO_RES_ID = new java.util.HashMap<>();
    static {
        // 中文key
        MESSAGE_TO_RES_ID.put("正在检查网络连接", R.string.progress_checking_network);
        MESSAGE_TO_RES_ID.put("正在检查网络连接速度", R.string.progress_checking_network_speed);
        MESSAGE_TO_RES_ID.put("正在获取电台总数", R.string.progress_getting_station_count);
        MESSAGE_TO_RES_ID.put("查询到网络数据库现存", R.string.progress_found_stations);
        MESSAGE_TO_RES_ID.put("开始更新临时数据库", R.string.progress_starting_temp_db_update);
        MESSAGE_TO_RES_ID.put("恢复下载进度", R.string.progress_resuming_download);
        MESSAGE_TO_RES_ID.put("正在处理下载的数据", R.string.progress_processing_data);
        MESSAGE_TO_RES_ID.put("正在读取临时数据库", R.string.progress_reading_temp_db);
        MESSAGE_TO_RES_ID.put("正在验证数据完整性", R.string.progress_validating_data);
        MESSAGE_TO_RES_ID.put("正在写入主数据库", R.string.progress_writing_main_db);
        MESSAGE_TO_RES_ID.put("正在下载电台数据", R.string.progress_downloading_stations);
        MESSAGE_TO_RES_ID.put("正在切换到新数据库", R.string.progress_switching_db);
        MESSAGE_TO_RES_ID.put("更新完成", R.string.update_completed);
        MESSAGE_TO_RES_ID.put("准备中", R.string.progress_preparing_update);
        MESSAGE_TO_RES_ID.put("更新已取消", R.string.update_cancelled);
        MESSAGE_TO_RES_ID.put("更新被系统暂停", R.string.update_system_paused);
        MESSAGE_TO_RES_ID.put("更新失败", R.string.update_failed);
        
        // 英文key
        MESSAGE_TO_RES_ID.put("Checking network connection", R.string.progress_checking_network);
        MESSAGE_TO_RES_ID.put("Checking network connection speed", R.string.progress_checking_network_speed);
        MESSAGE_TO_RES_ID.put("Getting station count", R.string.progress_getting_station_count);
        MESSAGE_TO_RES_ID.put("Found", R.string.progress_found_stations);
        MESSAGE_TO_RES_ID.put("Starting temporary database update", R.string.progress_starting_temp_db_update);
        MESSAGE_TO_RES_ID.put("Resuming download progress", R.string.progress_resuming_download);
        MESSAGE_TO_RES_ID.put("Processing downloaded data", R.string.progress_processing_data);
        MESSAGE_TO_RES_ID.put("Reading temporary database", R.string.progress_reading_temp_db);
        MESSAGE_TO_RES_ID.put("Validating data integrity", R.string.progress_validating_data);
        MESSAGE_TO_RES_ID.put("Writing to main database", R.string.progress_writing_main_db);
        MESSAGE_TO_RES_ID.put("Downloading station data", R.string.progress_downloading_stations);
        MESSAGE_TO_RES_ID.put("Switching to new database", R.string.progress_switching_db);
        MESSAGE_TO_RES_ID.put("Update completed", R.string.update_completed);
        MESSAGE_TO_RES_ID.put("Preparing", R.string.progress_preparing_update);
        MESSAGE_TO_RES_ID.put("preparing update", R.string.progress_preparing_update);

        MESSAGE_TO_RES_ID.put("Update cancelled", R.string.update_cancelled);
        MESSAGE_TO_RES_ID.put("may be paused by system", R.string.update_system_paused);
        MESSAGE_TO_RES_ID.put("Update failed", R.string.update_failed);
        
        // 俄文key
        MESSAGE_TO_RES_ID.put("Проверка подключения к сети", R.string.progress_checking_network);
        MESSAGE_TO_RES_ID.put("Проверка скорости подключения к сети", R.string.progress_checking_network_speed);
        MESSAGE_TO_RES_ID.put("Получение количества станций", R.string.progress_getting_station_count);
        MESSAGE_TO_RES_ID.put("Найдено", R.string.progress_found_stations);
        MESSAGE_TO_RES_ID.put("Начинаем обновление временной базы данных", R.string.progress_starting_temp_db_update);
        MESSAGE_TO_RES_ID.put("Возобновление загрузки", R.string.progress_resuming_download);
        MESSAGE_TO_RES_ID.put("Обработка загруженных данных", R.string.progress_processing_data);
        MESSAGE_TO_RES_ID.put("Чтение временной базы данных", R.string.progress_reading_temp_db);
        MESSAGE_TO_RES_ID.put("Проверка целостности данных", R.string.progress_validating_data);
        MESSAGE_TO_RES_ID.put("Запись в основную базу данных", R.string.progress_writing_main_db);
        MESSAGE_TO_RES_ID.put("Загрузка данных станций", R.string.progress_downloading_stations);
        MESSAGE_TO_RES_ID.put("Переключение на новую базу данных", R.string.progress_switching_db);
        MESSAGE_TO_RES_ID.put("Обновление завершено", R.string.update_completed);
        MESSAGE_TO_RES_ID.put("Подготовка", R.string.progress_preparing_update);
        MESSAGE_TO_RES_ID.put("Обновление отменено", R.string.update_cancelled);
        MESSAGE_TO_RES_ID.put("может быть приостановлено системой", R.string.update_system_paused);
        MESSAGE_TO_RES_ID.put("Обновление не удалось", R.string.update_failed);
    }
    
    // 使用Activity Context重新获取本地化字符串
    private String getLocalizedMessage(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        
        // 检查消息是否包含映射表中的某个key
        for (java.util.Map.Entry<String, Integer> entry : MESSAGE_TO_RES_ID.entrySet()) {
            String key = entry.getKey();
            if (message.contains(key)) {
                // 获取资源ID
                int resId = entry.getValue();
                try {
                    // 使用Activity Context获取本地化字符串
                    String localizedTemplate = context.getString(resId);
                    
                    // 检查原始消息是否包含数值参数（从key之后的部分提取）
                    String afterKey = message.substring(message.indexOf(key) + key.length()).trim();
                    
                    // 如果key之后还有内容，尝试提取参数
                    if (!afterKey.isEmpty()) {
                        // 尝试匹配数值参数
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("-?\\d+");
                        java.util.regex.Matcher matcher = pattern.matcher(afterKey);
                        java.util.ArrayList<String> args = new java.util.ArrayList<>();
                        while (matcher.find()) {
                            args.add(matcher.group());
                        }
                        
                        if (!args.isEmpty()) {
                            // 将参数转换为Object数组并重新格式化
                            Object[] formatArgs = new Object[args.size()];
                            for (int i = 0; i < args.size(); i++) {
                                try {
                                    formatArgs[i] = Integer.parseInt(args.get(i));
                                } catch (NumberFormatException e) {
                                    formatArgs[i] = args.get(i);
                                }
                            }
                            return String.format(localizedTemplate, formatArgs);
                        }
                    }
                    
                    return localizedTemplate;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to get localized string for: " + message, e);
                    return message;
                }
            }
        }
        
        return message;
    }
    private ProgressBar progressBar;
    private TextView messageText;
    private TextView progressText;
    private Button cancelButton;
    private Handler handler;
    private Runnable updateRunnable;
    private boolean isShowing = false;
    private boolean errorToastShown = false;

    // 用于检测进度卡死
    private int lastProgressValue = 0;
    private long lastProgressTime = 0;
    private int mProgressPercentage = 0;
    
    public DatabaseUpdateProgressDialog(Context context) {
        this.context = context;
        handler = new Handler(Looper.getMainLooper());
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateProgress();
                if (isShowing) {
                    handler.postDelayed(this, UPDATE_INTERVAL);
                }
            }
        };
    }
    
    /**
     * 显示进度对话框
     */
    public void show() {
        Log.d(TAG, "show() called, isShowing=" + isShowing + ", dialog=" + (dialog != null ? "not null" : "null") +
                  ", dialog.isShowing=" + (dialog != null ? dialog.isShowing() : "N/A"));

        errorToastShown = false;

        if (dialog != null && dialog.isShowing()) {
            Log.d(TAG, "Dialog already showing, returning");
            return;
        }
        
        // 如果对话框已经存在但被隐藏，直接显示
        if (dialog != null) {
            dialog.show();
            // 设置 isShowing 为 true 并开始更新进度
            isShowing = true;
            // 清除之前的所有回调
            handler.removeCallbacks(updateRunnable);
            // 立即更新一次进度，确保显示最新的值
            updateProgress();
            // 然后开始定时更新
            handler.post(updateRunnable);
            Log.d(TAG, "Existing dialog shown successfully, isShowing=" + isShowing);
            return;
        }
        
        // 创建对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(context, Utils.getAlertDialogThemeResId(context));
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.dialog_database_update_progress, null);
        
        progressBar = view.findViewById(R.id.progress_bar);
        messageText = view.findViewById(R.id.message_text);
        progressText = view.findViewById(R.id.progress_text);
        cancelButton = view.findViewById(R.id.cancel_button);
        
        // 根据主题设置文本颜色和背景
        boolean isDarkTheme = Utils.isDarkTheme(context);
        if (isDarkTheme) {
            messageText.setTextColor(Color.WHITE);
            progressText.setTextColor(Color.WHITE);
            cancelButton.setTextColor(Color.WHITE);
            view.setBackgroundColor(Color.parseColor("#2c2c2e")); // 使用暗色主题的背景颜色
        }


        
        // 设置取消按钮点击事件
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCancelConfirmation();
            }
        });
        
        builder.setView(view)
            .setTitle(R.string.update_dialog_title)
            .setCancelable(false);
        
        dialog = builder.create();
        dialog.show();
        isShowing = true;
        Log.d(TAG, "Dialog created and shown successfully, isShowing=" + isShowing);
        
        // 立即更新一次进度，确保显示最新的值
        updateProgress();
        // 然后开始定时更新
        handler.post(updateRunnable);
    }
    
    /**
     * 隐藏进度对话框
     */
    public void hide() {
        Log.d(TAG, "hide() called, isShowing=" + isShowing + ", dialog=" + (dialog != null ? "not null" : "null") + 
                  ", dialog.isShowing=" + (dialog != null ? dialog.isShowing() : "N/A"));
        
        if (!isShowing && (dialog == null || !dialog.isShowing())) {
            Log.d(TAG, "Dialog not showing, returning");
            return;
        }
        
        // 停止更新进度，但保持内部状态
        handler.removeCallbacks(updateRunnable);
        
        if (dialog != null && dialog.isShowing()) {
            dialog.hide(); // 使用hide()而不是dismiss()，以便稍后可以重新显示
            Log.d(TAG, "Dialog hidden successfully, isShowing=" + isShowing);
        } else {
            Log.d(TAG, "Dialog was null or not showing");
        }
        
        // 不设置isShowing为false，保持内部状态以便在show()中正确恢复
        Log.d(TAG, "Dialog hidden but internal state preserved, isShowing=" + isShowing);
    }
    
    /**
     * 完全销毁进度对话框
     */
    public void dismiss() {
        Log.d(TAG, "dismiss() called, isShowing=" + isShowing);
        
        isShowing = false;
        handler.removeCallbacks(updateRunnable);
        
        if (dialog != null) {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
            dialog = null;
            Log.d(TAG, "Dialog dismissed and nullified successfully");
        }
    }
    
    /**
     * 更新进度
     */
    private void updateProgress() {
        // 确保在主线程更新UI
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    updateProgress();
                }
            });
            return;
        }
        
        // 使用与DatabaseUpdateWorker相同的键名常量
        String KEY_PROGRESS_CURRENT = "progress_current";
        String KEY_PROGRESS_TOTAL = "progress_total";
        String KEY_PROGRESS_MESSAGE = "progress_message";
        String KEY_IS_UPDATING = "is_updating";
        String KEY_UPDATE_ID = "update_id";
        String KEY_UPDATE_START_TIME = "update_start_time";
        
        // 使用更彻底的SharedPreferences刷新机制
        int currentProgress = 0;
        int totalProgress = 0;
        String progressMessage = "";
        boolean isUpdating = false;
        
        // 方法1：尝试直接读取文件内容（绕过SharedPreferences缓存）
        try {
            File prefsFile = new File(context.getApplicationInfo().dataDir + "/shared_prefs/database_update_prefs.xml");
            if (prefsFile.exists()) {
                long lastModified = prefsFile.lastModified();
                Log.d(TAG, "SharedPreferences文件最后修改时间: " + new Date(lastModified));
                
                // 尝试直接解析XML文件获取最新值
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(prefsFile));
                String line;
                while ((line = reader.readLine()) != null) {
                    // 使用更精确的XML解析方法
                    if (line.contains("name=\"" + KEY_PROGRESS_CURRENT + "\"")) {
                        // 解析格式: <int name="progress_current" value="36400" />
                        String[] parts = line.split("value=\"");
                        if (parts.length >= 2) {
                            String valuePart = parts[1];
                            String[] valueEnd = valuePart.split("\"");
                            if (valueEnd.length >= 1) {
                                try {
                                    currentProgress = Integer.parseInt(valueEnd[0].trim());
                                } catch (NumberFormatException e) {
                                    Log.w(TAG, "无法解析进度值: " + valueEnd[0]);
                                }
                            }
                        }
                    } else if (line.contains("name=\"" + KEY_PROGRESS_TOTAL + "\"")) {
                        String[] parts = line.split("value=\"");
                        if (parts.length >= 2) {
                            String valuePart = parts[1];
                            String[] valueEnd = valuePart.split("\"");
                            if (valueEnd.length >= 1) {
                                try {
                                    totalProgress = Integer.parseInt(valueEnd[0].trim());
                                } catch (NumberFormatException e) {
                                    Log.w(TAG, "无法解析总进度值: " + valueEnd[0]);
                                }
                            }
                        }
                    } else if (line.contains("name=\"" + KEY_PROGRESS_MESSAGE + "\"")) {
                        // 字符串类型的XML格式可能是: <string name="progress_message">正在下载电台数据</string>
                        if (line.contains(">")) {
                            String[] parts = line.split(">");
                            if (parts.length >= 2) {
                                String valuePart = parts[1];
                                String[] valueEnd = valuePart.split("<");
                                if (valueEnd.length >= 1) {
                                    progressMessage = valueEnd[0].trim();
                                }
                            }
                        } else {
                            // 尝试value="格式
                            String[] parts = line.split("value=\"");
                            if (parts.length >= 2) {
                                String valuePart = parts[1];
                                String[] valueEnd = valuePart.split("\"");
                                if (valueEnd.length >= 1) {
                                    progressMessage = valueEnd[0].trim();
                                }
                            }
                        }
                    } else if (line.contains("name=\"" + KEY_IS_UPDATING + "\"")) {
                        String[] parts = line.split("value=\"");
                        if (parts.length >= 2) {
                            String valuePart = parts[1];
                            String[] valueEnd = valuePart.split("\"");
                            if (valueEnd.length >= 1) {
                                isUpdating = Boolean.parseBoolean(valueEnd[0].trim());
                            }
                        }
                    }
                }
                reader.close();
                
                Log.d(TAG, "直接文件读取结果: current=" + currentProgress + ", total=" + totalProgress + 
                          ", message=" + progressMessage + ", isUpdating=" + isUpdating);
            }
        } catch (Exception e) {
            Log.w(TAG, "直接文件读取失败，回退到SharedPreferences: " + e.getMessage());
            
            // 方法2：回退到SharedPreferences读取
            SharedPreferences prefs = context.getSharedPreferences("database_update_prefs", Context.MODE_PRIVATE);
            
            // 强制刷新：重新获取SharedPreferences实例，确保读取最新值
            prefs = context.getSharedPreferences("database_update_prefs", Context.MODE_PRIVATE);
            
            // 强制提交任何待处理的编辑
            prefs.edit().commit();
            
            // 重新获取SharedPreferences实例，确保完全刷新
            prefs = context.getSharedPreferences("database_update_prefs", Context.MODE_PRIVATE);
            
            currentProgress = prefs.getInt(KEY_PROGRESS_CURRENT, 0);
            totalProgress = prefs.getInt(KEY_PROGRESS_TOTAL, 0);
            progressMessage = prefs.getString(KEY_PROGRESS_MESSAGE, "");
            isUpdating = prefs.getBoolean(KEY_IS_UPDATING, false);
        }
        
        // 使用Activity Context重新获取本地化字符串
        progressMessage = getLocalizedMessage(progressMessage);
        
        // 如果message为空，设置默认值
        if (progressMessage.isEmpty()) {
            progressMessage = context.getString(R.string.progress_downloading_data);
            Log.w(TAG, "Message为空，使用默认值: " + progressMessage);
        }
        
        // 检查是否是错误消息
        boolean isError = progressMessage.toLowerCase().contains(context.getString(R.string.update_failed).toLowerCase()) || 
                          progressMessage.contains(context.getString(R.string.error_list_update)) ||
                          progressMessage.contains(context.getString(R.string.update_cancelled));
        
        // 检查取消标志，如果设置了取消标志，不显示"更新被系统暂停"消息
        SharedPreferences prefsCheck = context.getSharedPreferences("database_update_prefs", Context.MODE_PRIVATE);
        boolean isCancelled = prefsCheck.getBoolean("update_cancelled", false);
        long updateId = prefsCheck.getLong("update_id", 0);
        long cancelTimestamp = prefsCheck.getLong("cancel_timestamp", 0);
        long currentTime = System.currentTimeMillis();
        
        // 如果设置了取消标志，或者更新ID为0，或者取消时间戳在最近10分钟内，都认为是已取消状态
        boolean recentlyCancelled = isCancelled && (cancelTimestamp > 0) && (currentTime - cancelTimestamp < 10 * 60 * 1000);
        
        // 获取WorkManager状态，但在有取消标志时不使用它
        boolean workManagerUpdating = DatabaseUpdateManager.isUpdating(context);
        
        // 获取当前更新ID和开始时间，用于验证是否是新的更新
        long currentUpdateId = prefsCheck.getLong(KEY_UPDATE_ID, 0);
        long currentUpdateTime = prefsCheck.getLong(KEY_UPDATE_START_TIME, 0);
        
        // 检查是否是刚刚开始的新更新（开始时间在5秒内）
        boolean isNewUpdate = (currentUpdateTime > 0) && (currentTime - currentUpdateTime < 5000);
        
        if (isCancelled || updateId == 0 || recentlyCancelled) {
            Log.d(TAG, "Update was cancelled or no update ID, not showing system paused message. isCancelled=" + isCancelled + 
                      ", updateId=" + updateId + ", recentlyCancelled=" + recentlyCancelled);
            isUpdating = false;
            progressMessage = context.getString(R.string.update_cancelled);
            
            // 不清除取消标志，让DatabaseUpdateWorker在真正开始新更新时清除
            // 这样可以确保UI能够正确显示取消状态
            Log.d(TAG, "Detected cancelled update, showing cancelled status");
        } else if (isNewUpdate) {
            // 如果是刚刚开始的新更新，不显示系统暂停消息
            Log.d(TAG, "Detected new update started recently, not showing system paused message. updateId=" + currentUpdateId + 
                      ", timeSinceStart=" + (currentTime - currentUpdateTime) + "ms");
            // 保持当前状态，不修改消息
        } else {
            // 检查WorkManager实际状态，如果WorkManager显示正在更新但SharedPreferences显示未更新，说明任务被系统暂停
            if (workManagerUpdating && !isUpdating) {
                // 额外检查：如果进度为0/0且消息不是初始消息，可能是取消后的残留状态
                if (currentProgress == 0 && totalProgress == 0 && 
                    !progressMessage.contains(context.getString(R.string.update_preparing)) && !progressMessage.contains(context.getString(R.string.update_cancelled))) {
                    Log.w(TAG, "检测到可能的残留状态，进度为0/0，重置状态");
                    isUpdating = false;
                    progressMessage = context.getString(R.string.update_cancelled);
                    prefsCheck.edit()
                        .putBoolean(KEY_IS_UPDATING, false)
                        .putString(KEY_PROGRESS_MESSAGE, progressMessage)
                        .putBoolean("update_cancelled", true)  // 设置取消标志，防止恢复
                        .putLong("cancel_timestamp", System.currentTimeMillis())  // 设置取消时间戳
                        .commit();
                } else {
                    // 再次检查取消标志，确保不会覆盖取消状态
                    if (isCancelled || recentlyCancelled) {
                        Log.w(TAG, "检测到取消标志，不显示系统暂停消息");
                        isUpdating = false;
                        progressMessage = context.getString(R.string.update_cancelled);
                        
                        // 确保SharedPreferences中的取消状态被正确设置
                        SharedPreferences prefsUpdate = context.getSharedPreferences("database_update_prefs", Context.MODE_PRIVATE);
                        prefsUpdate.edit()
                            .putBoolean(KEY_IS_UPDATING, false)
                            .putString(KEY_PROGRESS_MESSAGE, progressMessage)
                            .putBoolean("update_cancelled", true)
                            .putLong("cancel_timestamp", System.currentTimeMillis())
                            .commit();
                    } else {
                        Log.w(TAG, "检测到系统暂停：WorkManager显示更新中，但SharedPreferences显示未更新");
                        // 这种情况下，任务可能被系统暂停，但WorkManager仍认为在运行
                        isUpdating = true;
                        progressMessage = context.getString(R.string.update_system_paused);
                        
                        // 强制更新SharedPreferences状态，确保下次检测一致
                        SharedPreferences prefsUpdate = context.getSharedPreferences("database_update_prefs", Context.MODE_PRIVATE);
                        prefsUpdate.edit()
                            .putBoolean(KEY_IS_UPDATING, true)
                            .putString(KEY_PROGRESS_MESSAGE, progressMessage)
                            .commit();
                    }
                }
            }
        }
        
        // 检查进度是否长时间没有变化（超过10秒），可能是卡死
        if (isUpdating && currentProgress > 0 && totalProgress > 0) {
            // 如果进度长时间没有变化，可能是网络问题或系统暂停
            if (currentProgress == lastProgressValue && currentTime - lastProgressTime > 10000) {
                Log.w(TAG, "检测到进度长时间未变化，可能卡死，当前进度: " + currentProgress);
                progressMessage = context.getString(R.string.progress_stalled);
                
                // 尝试从临时数据库获取实际进度
                try {
                    RadioStationRepository repository = RadioStationRepository.getInstance(context);
                    int tempDatabaseCount = repository.getTempDatabaseCount();
                    if (tempDatabaseCount > currentProgress) {
                        Log.w(TAG, "发现临时数据库中有更多数据: " + tempDatabaseCount + " > " + currentProgress);
                        currentProgress = tempDatabaseCount;
                        progressMessage = context.getString(R.string.progress_downloading_data);
                        // 更新SharedPreferences，确保下次读取到正确的进度
                        SharedPreferences prefs = context.getSharedPreferences("database_update_prefs", Context.MODE_PRIVATE);
                        prefs.edit()
                            .putInt(KEY_PROGRESS_CURRENT, currentProgress)
                            .commit();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "获取临时数据库进度失败: " + e.getMessage());
                }
            }
            
            // 更新最后检测到的进度值和时间
            lastProgressValue = currentProgress;
            lastProgressTime = currentTime;
        }
        
        // 直接创建新的UpdateProgress对象，使用从SharedPreferences读取的值
        DatabaseUpdateWorker.UpdateProgress progress = new DatabaseUpdateWorker.UpdateProgress(
            isUpdating,
            progressMessage,
            currentProgress,
            totalProgress
        );
        
        Log.d(TAG, "updateProgress called: 强制刷新后 isUpdating=" + progress.isUpdating + ", current=" + progress.current + ", total=" + progress.total + 
                  ", isShowing=" + isShowing + ", dialog.isShowing=" + (dialog != null ? dialog.isShowing() : "null") + ", workManagerUpdating=" + workManagerUpdating);
        
        // 确保进度信息是最新的
        if (progress.current >= progress.total && progress.total > 0) {
            // 进度已完成，但WorkManager仍在运行，可能是进度更新延迟
            if (workManagerUpdating) {
                // 强制从WorkManager获取最新状态
                Log.d(TAG, "Progress shows completed but WorkManager still running, refreshing state");
            }
        }
        
        // 强制更新所有UI组件，确保显示最新的进度信息
        if (messageText != null) {
            // 如果是错误消息，直接显示错误信息
            if (isError) {
                messageText.setText(progressMessage);
                Log.d(TAG, "Displaying error message: " + progressMessage);
            } else if (progress.isUpdating && !workManagerUpdating) {
                // SharedPreferences显示正在更新，但WorkManager没有运行的任务，说明可能被系统暂停
                messageText.setText(progress.message + context.getString(R.string.progress_system_paused_suffix));
                Log.d(TAG, "Detected possible system pause: SharedPreferences shows updating but WorkManager has no running task");
            } else {
                messageText.setText(progress.message);
            }
        }
        
        if (progressBar != null) {
            // 如果是错误消息，隐藏进度条
            if (isError) {
                progressBar.setVisibility(View.GONE);
                Log.d(TAG, "Hiding progress bar due to error");
            } else if (progress.total > 0) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setMax(progress.total);
                // 强制设置进度，确保UI更新
                progressBar.setProgress(0);
                progressBar.setProgress(progress.current);
                
                // 如果检测到可能被系统暂停，将进度条设置为不确定状态
                if (progress.isUpdating && !workManagerUpdating) {
                    progressBar.setIndeterminate(true);
                    Log.d(TAG, "Setting progress bar to indeterminate due to possible system pause");
                } else {
                    progressBar.setIndeterminate(false);
                }
            } else {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setIndeterminate(true);
            }
        }
        
        if (progressText != null) {
            // 如果是错误消息，隐藏进度文本
            if (isError) {
                progressText.setVisibility(View.GONE);
                Log.d(TAG, "Hiding progress text due to error");
            } else if (progress.total > 0) {
                progressText.setVisibility(View.VISIBLE);
                progressText.setText(String.format("%d/%d (%d%%)", 
                    progress.current, progress.total, progress.getPercentage()));
                mProgressPercentage = progress.getPercentage();
            } else {
                progressText.setVisibility(View.VISIBLE);
                progressText.setText(context.getString(R.string.update_preparing));
            }
        }
        
        if (isError) {
            if (!errorToastShown) {
                Toast.makeText(context, progressMessage, Toast.LENGTH_LONG).show();
                errorToastShown = true;
            }
            dismiss();
            return;
        }
        
        // 如果不是正在更新状态，延迟一段时间再检查，避免立即关闭对话框
        if (!progress.isUpdating && !workManagerUpdating) {
            Log.d(TAG, "Update not in progress, scheduling delayed check");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // 延迟后再次检查
                    DatabaseUpdateWorker.UpdateProgress delayedProgress = DatabaseUpdateManager.getProgress(context);
                    boolean dialogActuallyShowing = dialog != null && dialog.isShowing();
                    boolean finalWorkManagerUpdating = DatabaseUpdateManager.isUpdating(context);
                    Log.d(TAG, "Delayed check: isUpdating=" + delayedProgress.isUpdating + ", isShowing=" + isShowing + 
                              ", dialogActuallyShowing=" + dialogActuallyShowing + ", workManagerUpdating=" + finalWorkManagerUpdating);
                    
                    // 只有在对话框确实显示且确认不是更新状态时才关闭
                    if (!delayedProgress.isUpdating && dialogActuallyShowing && !finalWorkManagerUpdating) {
                        // 确认确实不是在更新状态，才关闭对话框
                        Log.d(TAG, "Update confirmed finished, dismissing dialog, progress=" + mProgressPercentage + "%");
                        dismiss();
                        
                        // 如果不是正常完成（进度不到99%），通知用户下载中断
                        if (mProgressPercentage < 99) {
                            Log.d(TAG, "Update was interrupted, showing toast notification");
                            Toast.makeText(context, R.string.update_interrupted_message, Toast.LENGTH_LONG).show();
                        }
                    } else if (isShowing && !dialogActuallyShowing && finalWorkManagerUpdating) {
                        // 内部状态显示应该显示，但对话框实际不可见，且WorkManager确认有任务在运行，尝试重新显示
                        Log.d(TAG, "Internal state shows should be showing but dialog not visible, trying to show again");
                        try {
                            show();
                        } catch (Exception e) {
                            Log.e(TAG, "Error trying to show dialog again", e);
                        }
                    } else {
                        Log.d(TAG, "Keeping dialog open: delayedProgress.isUpdating=" + delayedProgress.isUpdating + 
                                  ", dialogActuallyShowing=" + dialogActuallyShowing + ", workManagerUpdating=" + finalWorkManagerUpdating);
                    }
                }
            }, 3000); // 延迟3秒再次检查，给应用更多时间恢复状态
        }
    }
    
    /**
     * 显示取消确认对话框
     */
    private void showCancelConfirmation() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, Utils.getAlertDialogThemeResId(context));
        builder.setTitle(R.string.update_confirm_cancel_title)
            .setMessage(R.string.update_confirm_cancel_message)
            .setPositiveButton(R.string.update_confirm_cancel_positive, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // 立即关闭进度对话框，不等待取消操作完成
                    dismiss();
                    
                    // 在后台线程执行取消操作，避免阻塞UI
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            // 取消更新
                            DatabaseUpdateManager.cancelUpdate(context);
                        }
                    }).start();
                }
            })
            .setNegativeButton(context.getString(android.R.string.cancel), null)
            .show();
    }
    
    /**
     * 检查是否正在显示
     */
    public boolean isShowing() {
        // 直接返回对话框的实际显示状态，确保与隐藏状态同步
        boolean result = dialog != null && dialog.isShowing();
        Log.d(TAG, "isShowing() called, returning " + result + " (dialog=" + (dialog != null ? "not null" : "null") + 
                  ", dialog.isShowing=" + (dialog != null ? dialog.isShowing() : "N/A") + ", internal isShowing=" + isShowing + ")");
        return result;
    }
}