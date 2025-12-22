package net.programmierecke.radiodroid2.ui;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

import net.programmierecke.radiodroid2.ActivityMain;
import net.programmierecke.radiodroid2.FragmentSettings;
import net.programmierecke.radiodroid2.R;

public class EmptyDatabaseView extends LinearLayout {
    private TextView titleView;
    private TextView messageView;
    private MaterialButton updateButton;
    private OnUpdateClickListener onUpdateClickListener;

    public EmptyDatabaseView(Context context) {
        super(context);
        init(context);
    }

    public EmptyDatabaseView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public EmptyDatabaseView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.fragment_empty_database, this, true);
        
        titleView = findViewById(R.id.emptyDatabaseTitle);
        messageView = findViewById(R.id.emptyDatabaseMessage);
        updateButton = findViewById(R.id.btnUpdateDatabase);
        
        updateButton.setOnClickListener(v -> {
            if (onUpdateClickListener != null) {
                onUpdateClickListener.onUpdateClick();
            } else {
                // 默认行为：跳转到设置页面
                Intent intent = new Intent(context, ActivityMain.class);
                intent.putExtra("open_settings", true);
                context.startActivity(intent);
            }
        });
    }

    public void setTitle(String title) {
        if (titleView != null) {
            titleView.setText(title);
        }
    }

    public void setMessage(String message) {
        if (messageView != null) {
            messageView.setText(message);
        }
    }

    public void setButtonText(String text) {
        if (updateButton != null) {
            updateButton.setText(text);
        }
    }

    public void setOnUpdateClickListener(OnUpdateClickListener listener) {
        this.onUpdateClickListener = listener;
    }

    public interface OnUpdateClickListener {
        void onUpdateClick();
    }
}