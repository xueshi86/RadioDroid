package net.programmierecke.radiodroid2.alarm;

import android.app.TimePickerDialog;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextThemeWrapper;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.TimePicker;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;

import java.util.Locale;
import java.util.Observer;

public class FragmentAlarm extends Fragment implements TimePickerDialog.OnTimeSetListener {
    private RadioAlarmManager ram;
    private ItemAdapterRadioAlarm adapterRadioAlarm;
    private ListView lvAlarms;
    private Observer alarmsObserver;

    public FragmentAlarm() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        RadioDroidApp radioDroidApp = (RadioDroidApp)getActivity().getApplication();
        ram = radioDroidApp.getAlarmManager();

        View view = inflater.inflate(R.layout.layout_alarms, container, false);

        adapterRadioAlarm = new ItemAdapterRadioAlarm(getActivity());
        lvAlarms = view.findViewById(R.id.listViewAlarms);
        lvAlarms.setAdapter(adapterRadioAlarm);
        lvAlarms.setClickable(true);
        lvAlarms.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object anObject = parent.getItemAtPosition(position);
                if (anObject instanceof DataRadioStationAlarm) {
                    ClickOnItem((DataRadioStationAlarm) anObject);
                }
            }
        });

        alarmsObserver = (o, arg) -> RefreshListAndView();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        RefreshListAndView();

        ram.getSavedAlarmsObservable().addObserver(alarmsObserver);
    }

    @Override
    public void onPause() {
        super.onPause();

        ram.getSavedAlarmsObservable().deleteObserver(alarmsObserver);
    }

    private void RefreshListAndView() {
        adapterRadioAlarm.clear();
        adapterRadioAlarm.addAll(ram.getList());
    }

    DataRadioStationAlarm clickedAlarm = null;
    private void ClickOnItem(DataRadioStationAlarm anObject) {
        clickedAlarm = anObject;
        showAlarmEditorDialog(anObject);
    }

    private DataRadioStationAlarm editingAlarm = null;
    private int editorHour;
    private int editorMinute;
    private MaterialButton editorTimeButton = null;

    private void showAlarmEditorDialog(final DataRadioStationAlarm alarm) {
        editingAlarm = alarm;
        editorHour = alarm.hour;
        editorMinute = alarm.minute;

        // 必须使用对话框主题 inflate，否则 ?attr/colorPrimary 等属性会按 Activity 主题解析，
        // 暗色主题下按钮时间与滑块颜色与背景混在一起
        ContextThemeWrapper themedContext = new ContextThemeWrapper(requireContext(),
                Utils.getAlertDialogThemeResId(requireContext()));
        View dialogView = LayoutInflater.from(themedContext).inflate(R.layout.dialog_alarm_editor, null);

        TextView tvStation = dialogView.findViewById(R.id.textViewEditorStation);
        editorTimeButton = dialogView.findViewById(R.id.buttonEditorTime);
        TextView tvStartVolumeLabel = dialogView.findViewById(R.id.textViewStartVolumeLabel);
        Slider sliderStartVolume = dialogView.findViewById(R.id.sliderStartVolume);
        TextView tvTargetVolumeLabel = dialogView.findViewById(R.id.textViewTargetVolumeLabel);
        Slider sliderTargetVolume = dialogView.findViewById(R.id.sliderTargetVolume);
        TextView tvFadeDurationLabel = dialogView.findViewById(R.id.textViewFadeDurationLabel);
        Slider sliderFadeDuration = dialogView.findViewById(R.id.sliderFadeDuration);
        TextView tvFadeWarning = dialogView.findViewById(R.id.textViewFadeWarning);

        // 对话框创建前匿名类无法捕获 dialog 变量本身，用数组间接引用
        final AlertDialog[] dialogHolder = {null};

        tvStation.setText(alarm.station.Name);
        editorTimeButton.setText(String.format(Locale.getDefault(), "%02d:%02d", editorHour, editorMinute));
        editorTimeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TimePickerFragment newFragment = new TimePickerFragment(editorHour, editorMinute);
                newFragment.setCallback(FragmentAlarm.this);
                newFragment.show(requireContext(), getActivity().getSupportFragmentManager(), "timePicker");
            }
        });

        // 旧版本可能保存了 0%，但滑块最小值为 1（app 永不将系统音量设为 0），
        // 直接 setValue 会因越界抛 IllegalArgumentException，需先限制到 [1,100]
        int startVolume = Math.max(1, Math.min(100, alarm.startVolume));
        int targetVolume = Math.max(1, Math.min(100, alarm.targetVolume));

        sliderStartVolume.setValue(startVolume);
        sliderTargetVolume.setValue(targetVolume);
        sliderFadeDuration.setValue(alarm.fadeDurationSeconds);

        tvStartVolumeLabel.setText(String.format(Locale.getDefault(), "%s: %s",
                getString(R.string.alarm_start_volume),
                String.format(Locale.getDefault(), getString(R.string.alarm_volume_percent), startVolume)));
        tvTargetVolumeLabel.setText(String.format(Locale.getDefault(), "%s: %s",
                getString(R.string.alarm_target_volume),
                String.format(Locale.getDefault(), getString(R.string.alarm_volume_percent), targetVolume)));
        tvFadeDurationLabel.setText(String.format(Locale.getDefault(), "%s: %s",
                getString(R.string.alarm_fade_duration),
                String.format(Locale.getDefault(), getString(R.string.alarm_fade_seconds), alarm.fadeDurationSeconds)));

        sliderStartVolume.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(Slider slider, float value, boolean fromUser) {
                tvStartVolumeLabel.setText(String.format(Locale.getDefault(), "%s: %s",
                        getString(R.string.alarm_start_volume),
                        String.format(Locale.getDefault(), getString(R.string.alarm_volume_percent), (int) value)));
                updateAlarmSaveState(dialogHolder[0], sliderStartVolume, sliderTargetVolume, sliderFadeDuration, tvFadeWarning);
            }
        });
        sliderTargetVolume.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(Slider slider, float value, boolean fromUser) {
                tvTargetVolumeLabel.setText(String.format(Locale.getDefault(), "%s: %s",
                        getString(R.string.alarm_target_volume),
                        String.format(Locale.getDefault(), getString(R.string.alarm_volume_percent), (int) value)));
                updateAlarmSaveState(dialogHolder[0], sliderStartVolume, sliderTargetVolume, sliderFadeDuration, tvFadeWarning);
            }
        });
        sliderFadeDuration.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(Slider slider, float value, boolean fromUser) {
                tvFadeDurationLabel.setText(String.format(Locale.getDefault(), "%s: %s",
                        getString(R.string.alarm_fade_duration),
                        String.format(Locale.getDefault(), getString(R.string.alarm_fade_seconds), (int) value)));
                updateAlarmSaveState(dialogHolder[0], sliderStartVolume, sliderTargetVolume, sliderFadeDuration, tvFadeWarning);
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()))
                .setView(dialogView)
                .setPositiveButton(R.string.alarm_save, null)
                .setNegativeButton(R.string.alarm_cancel, null)
                .create();
        dialogHolder[0] = dialog;

        dialog.setOnShowListener(dialogInterface -> {
            // 首次显示时按当前滑块值刷新保存按钮可用状态
            updateAlarmSaveState(dialog, sliderStartVolume, sliderTargetVolume, sliderFadeDuration, tvFadeWarning);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                // 注意：不能与外层方法体中的 startVolume/targetVolume 同名（Java 禁止遮蔽）
                int sliderStartVol = (int) sliderStartVolume.getValue();
                int sliderTargetVol = (int) sliderTargetVolume.getValue();
                int sliderFadeDur = (int) sliderFadeDuration.getValue();

                // 起始音量 ≥ 目标音量且渐增开启时不允许保存：否则闹钟执行时
                // 不会渐进到目标音量，而是直接从目标音量开始播放
                if (sliderFadeDur > 0 && sliderStartVol >= sliderTargetVol) {
                    return;
                }

                // 系统媒体音量为 0 时提示用户：闹钟可能无声
                AudioManager am = (AudioManager) requireContext().getSystemService(Context.AUDIO_SERVICE);
                if (am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                    new AlertDialog.Builder(requireContext())
                            .setMessage(R.string.alarm_volume_zero_warning)
                            .setPositiveButton(R.string.alarm_save, (d, w) -> {
                                saveAndDismiss(alarm, sliderStartVol, sliderTargetVol, sliderFadeDur, dialog);
                            })
                            .setNegativeButton(R.string.alarm_cancel, null)
                            .show();
                    return;
                }

                saveAndDismiss(alarm, sliderStartVol, sliderTargetVol, sliderFadeDur, dialog);
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                editingAlarm = null;
                editorTimeButton = null;
                dialog.dismiss();
            });
        });

        dialog.setOnDismissListener(dialogInterface -> {
            editingAlarm = null;
            editorTimeButton = null;
        });

        dialog.show();
    }

    private void saveAndDismiss(DataRadioStationAlarm alarm, int startVolume, int targetVolume, int fadeDuration, AlertDialog dialog) {
        ram.changeTime(alarm.id, editorHour, editorMinute);
        ram.setAlarmFade(alarm.id, startVolume, targetVolume, fadeDuration);
        ram.setEnabled(alarm.id, true);

        editingAlarm = null;
        editorTimeButton = null;
        dialog.dismiss();
    }

    /**
     * 实时校验渐增参数：渐增开启（时长>0）时，起始音量必须小于目标音量，
     * 否则禁用保存按钮并显示警告——避免闹钟执行时直接从目标音量开始播放。
     */
    private void updateAlarmSaveState(AlertDialog dialog, Slider sliderStartVolume, Slider sliderTargetVolume,
                                      Slider sliderFadeDuration, TextView tvFadeWarning) {
        if (dialog == null) return;
        Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (saveButton == null) return;

        boolean invalid = sliderFadeDuration.getValue() > 0
                && sliderStartVolume.getValue() >= sliderTargetVolume.getValue();
        saveButton.setEnabled(!invalid);
        if (tvFadeWarning != null) {
            tvFadeWarning.setVisibility(invalid ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
        if (editorTimeButton != null && editingAlarm != null) {
            editorHour = hourOfDay;
            editorMinute = minute;
            editorTimeButton.setText(String.format(Locale.getDefault(), "%02d:%02d", editorHour, editorMinute));
        }
    }

    public RadioAlarmManager getRam() {
        return ram;
    }
}
