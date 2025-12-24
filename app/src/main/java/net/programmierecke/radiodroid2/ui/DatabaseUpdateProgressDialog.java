package net.programmierecke.radiodroid2.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.Date;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.service.DatabaseUpdateManager;
import net.programmierecke.radiodroid2.service.DatabaseUpdateWorker;

public class DatabaseUpdateProgressDialog {
    private static final String TAG = "DatabaseUpdateProgressDialog";
    private static final int UPDATE_INTERVAL = 500; // 更新间隔(毫秒)
    
    private Context context;
    private Dialog dialog;
    private ProgressBar progressBar;
    private TextView messageText;
    private TextView progressText;
    private Button cancelButton;
    private Handler handler;
    private Runnable updateRunnable;
    private boolean isShowing = false;
    
    // 用于检测进度卡死
    private int lastProgressValue = 0;
    private long lastProgressTime = 0;
    
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
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.dialog_database_update_progress, null);
        
        progressBar = view.findViewById(R.id.progress_bar);
        messageText = view.findViewById(R.id.message_text);
        progressText = view.findViewById(R.id.progress_text);
        cancelButton = view.findViewById(R.id.cancel_button);
        
        // 设置取消按钮点击事件
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCancelConfirmation();
            }
        });
        
        builder.setView(view)
            .setTitle("更新电台数据库")
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
        
        // 如果message为空，设置默认值
        if (progressMessage.isEmpty()) {
            progressMessage = "正在下载电台数据";
            Log.w(TAG, "Message为空，使用默认值: " + progressMessage);
        }
        
        // 检查是否是错误消息
        boolean isError = progressMessage.contains("更新失败") || progressMessage.contains("错误");
        
        boolean workManagerUpdating = DatabaseUpdateManager.isUpdating(context);
        
        // 检查WorkManager实际状态，如果WorkManager显示正在更新但SharedPreferences显示未更新，说明任务被系统暂停
        if (workManagerUpdating && !isUpdating) {
            Log.w(TAG, "检测到系统暂停：WorkManager显示更新中，但SharedPreferences显示未更新");
            // 这种情况下，任务可能被系统暂停，但WorkManager仍认为在运行
            isUpdating = true;
            progressMessage = "更新被系统暂停，等待恢复...";
            
            // 强制更新SharedPreferences状态，确保下次检测一致
            SharedPreferences prefs = context.getSharedPreferences("database_update_prefs", Context.MODE_PRIVATE);
            prefs.edit()
                .putBoolean(KEY_IS_UPDATING, true)
                .putString(KEY_PROGRESS_MESSAGE, progressMessage)
                .commit();
        }
        
        // 检查进度是否长时间没有变化（超过10秒），可能是卡死
        long currentTime = System.currentTimeMillis();
        if (isUpdating && currentProgress > 0 && totalProgress > 0) {
            // 如果进度长时间没有变化，可能是网络问题或系统暂停
            if (currentProgress == lastProgressValue && currentTime - lastProgressTime > 10000) {
                Log.w(TAG, "检测到进度长时间未变化，可能卡死，当前进度: " + currentProgress);
                progressMessage = "更新进度停滞，正在尝试恢复...";
                
                // 尝试从临时数据库获取实际进度
                try {
                    RadioStationRepository repository = RadioStationRepository.getInstance(context);
                    int tempDatabaseCount = repository.getTempDatabaseCount();
                    if (tempDatabaseCount > currentProgress) {
                        Log.w(TAG, "发现临时数据库中有更多数据: " + tempDatabaseCount + " > " + currentProgress);
                        currentProgress = tempDatabaseCount;
                        progressMessage = "正在下载电台数据";
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
                messageText.setText(progress.message + " (可能被系统暂停)");
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
            } else {
                progressText.setVisibility(View.VISIBLE);
                progressText.setText("准备中...");
            }
        }
        
        // 如果是错误消息，延迟关闭对话框
        if (isError) {
            Log.d(TAG, "Error detected, scheduling dialog dismissal");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "Dismissing dialog after error");
                    dismiss();
                }
            }, 5000); // 5秒后关闭对话框
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
                        Log.d(TAG, "Update confirmed finished, dismissing dialog");
                        dismiss();
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
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("取消更新")
            .setMessage("确定要取消数据库更新吗？")
            .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // 取消更新
                    DatabaseUpdateManager.cancelUpdate(context);
                    hide();
                }
            })
            .setNegativeButton("取消", null)
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