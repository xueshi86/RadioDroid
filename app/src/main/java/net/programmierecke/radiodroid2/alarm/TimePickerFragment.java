package net.programmierecke.radiodroid2.alarm;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.NumberPicker;

import androidx.annotation.NonNull;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;

import java.util.Calendar;

/**
 * 时间选择器辅助类。
 * <p>
 * 使用两个可循环滚动的 {@link NumberPicker}（小时 0-23、分钟 0-59）选择时间，
 * 只能上下滚动选择、禁止键盘输入；数字、分割线与按钮颜色由主题属性
 * (timePickerValueColor / timePickerDividerColor / timePickerButtonColor) 控制，
 * 暗色主题下统一为白色，不会出现选中/未选中变灰或按钮变淡看不清的问题。
 */
public class TimePickerFragment {
    private TimePickerDialog.OnTimeSetListener callback;
    private int initialHour;
    private int initialMinute;

    public TimePickerFragment() {
        final Calendar c = Calendar.getInstance();
        this.initialHour = c.get(Calendar.HOUR_OF_DAY);
        this.initialMinute = c.get(Calendar.MINUTE);
    }

    public TimePickerFragment(int initialHour, int initialMinute) {
        this.initialHour = initialHour;
        this.initialMinute = initialMinute;
    }

    public void setCallback(TimePickerDialog.OnTimeSetListener callback) {
        this.callback = callback;
    }

    public void show(@NonNull Context context, androidx.fragment.app.FragmentManager fragmentManager, String tag) {
        boolean dark = Utils.getThemeResId(context) == R.style.MyMaterialTheme_Dark;
        int themeRes = dark ? R.style.DialogTheme_Dark : R.style.DialogTheme;

        ContextThemeWrapper themedContext = new ContextThemeWrapper(context, themeRes);
        View view = LayoutInflater.from(themedContext).inflate(R.layout.dialog_time_picker, null);

        NumberPicker npHour = view.findViewById(R.id.npHour);
        NumberPicker npMinute = view.findViewById(R.id.npMinute);

        // 小时 00-23、分钟 00-59，均可循环滚动，两位补零显示。
        // 使用 setDisplayedValues：当前值与滚轮项走同一绘制路径，数字字号必然生效
        npHour.setMinValue(0);
        npHour.setMaxValue(23);
        npHour.setValue(initialHour);
        npHour.setWrapSelectorWheel(true);
        npHour.setDisplayedValues(twoDigitValues(24));

        npMinute.setMinValue(0);
        npMinute.setMaxValue(59);
        npMinute.setValue(initialMinute);
        npMinute.setWrapSelectorWheel(true);
        npMinute.setDisplayedValues(twoDigitValues(60));

        // 禁止键盘输入，只能上下滚动选择
        npHour.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        npMinute.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        // 长按上下箭头快速连续滚动
        npHour.setOnLongPressUpdateInterval(60);
        npMinute.setOnLongPressUpdateInterval(60);

        Button btnCancel = view.findViewById(R.id.btnCancelTime);
        Button btnConfirm = view.findViewById(R.id.btnConfirmTime);

        AlertDialog dialog = new AlertDialog.Builder(themedContext)
                .setView(view)
                .create();

        // 移除 AlertDialog 内容面板的默认内边距，宽度改为 match_parent：
        // 对话框占满屏幕宽度（减去 margin），内容通过布局自身的居中属性实现对齐
        dialog.setOnShowListener(d -> {
            View custom = dialog.findViewById(android.R.id.custom);
            if (custom != null) {
                ViewGroup.LayoutParams lp = custom.getLayoutParams();
                if (lp != null) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    custom.setLayoutParams(lp);
                }
                custom.setPadding(0, 0, 0, 0);
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            if (callback != null) {
                callback.onTimeSet(null, npHour.getValue(), npMinute.getValue());
                callback = null;
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    /** 生成 00..N-1 的两位补零字符串数组（供 setDisplayedValues 使用） */
    private static String[] twoDigitValues(int count) {
        String[] values = new String[count];
        for (int i = 0; i < count; i++) {
            values[i] = String.format("%02d", i);
        }
        return values;
    }
}