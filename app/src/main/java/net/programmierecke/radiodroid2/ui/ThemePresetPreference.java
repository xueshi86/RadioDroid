package net.programmierecke.radiodroid2.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;

/**
 * 配色方案选择项（Issue #50）。
 * <p>
 * 在标准单选列表的基础上，为每套预设绘制「顶栏主色块 + 强调色点」的实时色卡缩略图，
 * 色值直接解析各 ThemePreset 覆盖样式，因此与主题真正生效的颜色始终一致，
 * 无需在 Java 里再维护一份色值表；亮暗主题下分别预览对应主题的那一组色值。
 */
public class ThemePresetPreference extends ListPreference {

    private static final int SWATCH_CORNER_DP = 6;

    public ThemePresetPreference(@NonNull Context context) {
        super(context);
    }

    public ThemePresetPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ThemePresetPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ThemePresetPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onClick() {
        final Context context = getContext();
        final CharSequence[] entries = getEntries();
        final CharSequence[] entryValues = getEntryValues();
        if (context == null || entries == null || entryValues == null) {
            super.onClick();
            return;
        }

        final float density = context.getResources().getDisplayMetrics().density;
        final boolean dark = Utils.isDarkTheme(context);
        final String currentValue = Utils.getThemePreset(context);

        final LayoutInflater inflater = LayoutInflater.from(context);
        final View content = inflater.inflate(R.layout.dialog_theme_preset, null);
        final LinearLayout list = content.findViewById(R.id.layoutThemePresetList);

        final AlertDialog dialog = new AlertDialog.Builder(context, Utils.getAlertDialogThemeResId(context))
                .setTitle(getTitle())
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        final int count = Math.min(entries.length, entryValues.length);
        for (int i = 0; i < count; i++) {
            final String value = entryValues[i].toString();
            final boolean selected = value.equals(currentValue);

            final View row = inflater.inflate(R.layout.list_item_theme_preset, list, false);

            final int[] presetColors = Utils.getThemePresetColors(context, value, dark);
            bindSwatch(row.findViewById(R.id.viewSwatchPrimary), presetColors[0],
                    SWATCH_CORNER_DP * density, density);
            bindAccentDot(row.findViewById(R.id.viewSwatchAccent), presetColors[1], presetColors[0], density);

            final TextView name = row.findViewById(R.id.textViewThemePresetName);
            name.setText(entries[i]);
            if (selected) {
                name.setTextColor(Utils.getThemeColor(context, R.attr.presetAccentColor));
            }

            final RadioButton radio = row.findViewById(R.id.radioThemePresetSelected);
            radio.setChecked(selected);

            row.setOnClickListener(v -> {
                // setValue 持久化到默认 SharedPreferences，进而触发设置页的 recreate() 整体换肤
                setValue(value);
                dialog.dismiss();
            });

            list.addView(row);
        }

        dialog.show();
    }

    private static void bindSwatch(View view, int primaryColor, float cornerRadius, float density) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(cornerRadius);
        drawable.setColor(primaryColor);
        // 细描边：纯白 / 纯黑等与对话框底色接近的主色也能看出色块边界，避免色卡"消失"
        drawable.setStroke(Math.max(1, (int) (0.5f * density)), Color.argb(40, 128, 128, 128));
        view.setBackground(drawable);
    }

    private static void bindAccentDot(View view, int accentColor, int primaryColor, float density) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(accentColor);
        // 色点落在主色块上，按主色亮度自动取黑/白描边，保证任何预设下都能分辨
        drawable.setStroke(Math.max(1, (int) (1.5f * density)), contrastColorFor(primaryColor));
        view.setBackground(drawable);
    }

    /** 按 WCAG 相对亮度取黑或白（亮度大于 0.179 用黑），与项目既有「色块取字色」规则一致 */
    private static int contrastColorFor(int color) {
        double luminance = 0.2126 * linearize(Color.red(color) / 255.0)
                + 0.7152 * linearize(Color.green(color) / 255.0)
                + 0.0722 * linearize(Color.blue(color) / 255.0);
        return luminance > 0.179 ? Color.BLACK : Color.WHITE;
    }

    private static double linearize(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
}