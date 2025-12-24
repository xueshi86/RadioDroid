package net.programmierecke.radiodroid2.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import net.programmierecke.radiodroid2.R;

public class DatabaseUpdateProgressDialog extends DialogFragment {
    private static final String ARG_TITLE = "title";
    private static final String ARG_MESSAGE = "message";
    
    private ProgressBar progressBar;
    private TextView textMessage;
    private TextView textProgress;
    private String title;
    private String initialMessage;
    
    public interface OnCancelListener {
        void onCancel();
    }
    
    private OnCancelListener cancelListener;
    
    public static DatabaseUpdateProgressDialog newInstance(String title, String message) {
        DatabaseUpdateProgressDialog dialog = new DatabaseUpdateProgressDialog();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        args.putString(ARG_MESSAGE, message);
        dialog.setArguments(args);
        return dialog;
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            title = getArguments().getString(ARG_TITLE);
            initialMessage = getArguments().getString(ARG_MESSAGE);
        }
        
        // 使对话框不可取消，除非明确设置了取消监听器
        setCancelable(cancelListener != null);
    }
    
    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View view = inflater.inflate(R.layout.dialog_database_update_progress, null);
        
        progressBar = view.findViewById(R.id.progress_bar);
        textMessage = view.findViewById(R.id.message_text);
        textProgress = view.findViewById(R.id.progress_text);
        
        textMessage.setText(initialMessage);
        textProgress.setText("");
        
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(view);
        
        if (cancelListener != null) {
            builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                // 显示确认对话框，防止用户误触
                androidx.appcompat.app.AlertDialog.Builder confirmBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
                confirmBuilder.setTitle("确认取消");
                confirmBuilder.setMessage("确定要取消数据库更新吗？已下载的数据将被保存。");
                confirmBuilder.setPositiveButton("确定取消", (confirmDialog, confirmWhich) -> {
                    if (cancelListener != null) {
                        cancelListener.onCancel();
                    }
                    dismiss();
                });
                confirmBuilder.setNegativeButton("继续更新", null);
                confirmBuilder.show();
            });
        }
        
        return builder.create();
    }
    
    public void updateProgress(String message, int current, int total) {
        if (textMessage != null && textProgress != null && total > 0) {
            int percentage = (int) ((current * 100) / total);
            // 格式化为"正在更新 - 5000/50170（9%）"的样式
            String formattedMessage = String.format("%s - %d/%d（%d%%）", message, current, total, percentage);
            textMessage.setText(formattedMessage);
            textProgress.setText(""); // 清空单独的进度文本，因为已合并到消息中
            
            if (progressBar != null) {
                progressBar.setMax(total);
                progressBar.setProgress(current);
            }
        } else if (textMessage != null) {
            // 如果没有总数，只显示消息
            textMessage.setText(message);
            if (textProgress != null) {
                textProgress.setText("");
            }
        }
    }
    
    public void updateMessage(String message) {
        if (textMessage != null) {
            textMessage.setText(message);
        }
    }
    
    public void setOnCancelListener(OnCancelListener listener) {
        this.cancelListener = listener;
        setCancelable(listener != null);
    }
}