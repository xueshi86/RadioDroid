package net.programmierecke.radiodroid2.station;

import android.content.Context;
import android.content.Intent;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;

/**
 * 电台备注编辑对话框（Issue #54）。
 * 备注限宽不限字数：通过 {@link MaxWidthInputFilter} 以字体实测宽度截断输入，
 * 保证中英文/任意字号下输入的文本都能在列表备注行内单行放下，不会换行。
 */
public class StationNoteDialog {

    /** 保存/清除后由调用方刷新自身界面（列表行、播放器信息页等）。 */
    public interface OnNoteChangedListener {
        void onNoteChanged(@Nullable String newNote);
    }

    public static void show(@NonNull Context context, @NonNull DataRadioStation station,
                            @Nullable OnNoteChangedListener listener) {
        StationNoteManager noteManager = ((RadioDroidApp) context.getApplicationContext()).getStationNoteManager();
        String existing = noteManager.getNote(station.StationUuid);

        final EditText editText = new EditText(context);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setSingleLine(true);
        editText.setHint(R.string.note_input_hint);
        if (!TextUtils.isEmpty(existing)) {
            editText.setText(existing);
            editText.setSelection(existing.length());
        }
        int pad = (int) (context.getResources().getDisplayMetrics().density * 8);
        FrameLayout container = new FrameLayout(context);
        container.setPadding(pad * 3, pad, pad * 3, 0);
        container.addView(editText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(context, Utils.getAlertDialogThemeResId(context))
                .setTitle(R.string.note_edit_title)
                .setMessage(context.getString(R.string.note_edit_message, station.Name))
                .setView(container)
                .setPositiveButton(R.string.note_action_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.note_action_clear, (d, which) -> {
                    noteManager.setNote(station.StationUuid, null);
                    notifyLists(context, station);
                    if (listener != null) listener.onNoteChanged(null);
                })
                .create();

        // 布局完成后按实际可用宽度挂限宽过滤器；同时预截断可能已超宽的既有文本（如旧版本数据），
        // 避免用户无法继续输入
        final AlertDialog dialogRef = dialog;
        dialog.setOnShowListener(d -> editText.post(() -> {
            int usable = editText.getWidth() - editText.getPaddingLeft() - editText.getPaddingRight();
            if (usable <= 0) usable = context.getResources().getDisplayMetrics().widthPixels / 2;
            MaxWidthInputFilter widthFilter = new MaxWidthInputFilter(editText.getPaint(), usable);
            editText.setFilters(new InputFilter[]{widthFilter, new InputFilter.LengthFilter(64)});
            CharSequence current = editText.getText();
            CharSequence clamped = widthFilter.clamp(current);
            if (clamped != null) {
                editText.setText(clamped);
                editText.setSelection(clamped.length());
            }
        }));

        dialog.show();
        // 覆盖保存按钮：点击时不自动关闭，先校验（保留行为简单：空文本=清除备注）
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            noteManager.setNote(station.StationUuid, editText.getText().toString());
            String saved = noteManager.getNote(station.StationUuid);
            Toast.makeText(context.getApplicationContext(),
                    TextUtils.isEmpty(saved) ? R.string.note_cleared_toast : R.string.note_saved_toast,
                    Toast.LENGTH_SHORT).show();
            notifyLists(context, station);
            if (listener != null) listener.onNoteChanged(saved);
            dialogRef.dismiss();
        });
    }

    /** 列表页面通过本地广播刷新对应电台行（与收藏状态变化同一通道）。 */
    private static void notifyLists(Context context, DataRadioStation station) {
        Intent intent = new Intent(DataRadioStation.RADIO_STATION_LOCAL_INFO_CHAGED);
        intent.putExtra(DataRadioStation.RADIO_STATION_UUID, station.StationUuid);
        LocalBroadcastManager.getInstance(context.getApplicationContext()).sendBroadcast(intent);
    }

    /**
     * 限宽输入过滤器：逐字符累加并实测文本宽度，超出 {@code maxWidth} 即拒绝继续输入。
     * 以 Paint 实测宽度为准，中英文混排、任意字号都精确，输入结果保证单行放下。
     */
    private static class MaxWidthInputFilter implements InputFilter {
        private final TextPaint paint;
        private final int maxWidth;

        MaxWidthInputFilter(TextPaint paint, int maxWidthPx) {
            this.paint = paint;
            this.maxWidth = maxWidthPx;
        }

        @Override
        public CharSequence filter(CharSequence source, int start, int end,
                                   Spanned dest, int dstart, int dend) {
            if (start >= end) return null; // 删除操作，直接放行

            String prefix = dest.subSequence(0, dstart).toString();
            String suffix = dest.subSequence(dend, dest.length()).toString();

            StringBuilder accepted = new StringBuilder();
            for (int i = start; i < end; i++) {
                String candidate = prefix + accepted + source.charAt(i) + suffix;
                if (paint.measureText(candidate) > maxWidth) break;
                accepted.append(source.charAt(i));
            }

            int acceptedLength = accepted.length();
            int sourceLength = end - start;
            if (acceptedLength == sourceLength) return null; // 全部接受
            if (acceptedLength == 0) return "";               // 全部拒绝
            return source.subSequence(start, start + acceptedLength); // 部分接受
        }

        /** 返回截断到限宽内的文本；未超宽时返回 null（无需修改）。 */
        CharSequence clamp(CharSequence text) {
            if (text == null || paint.measureText(text.toString()) <= maxWidth) return null;
            int end = text.length();
            while (end > 0 && paint.measureText(text.subSequence(0, end).toString()) > maxWidth) {
                end--;
            }
            return text.subSequence(0, end);
        }
    }
}
