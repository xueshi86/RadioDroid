package net.programmierecke.radiodroid2.alarm;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.TimePicker;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;

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

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_alarm_editor, null);

        TextView tvStation = dialogView.findViewById(R.id.textViewEditorStation);
        editorTimeButton = dialogView.findViewById(R.id.buttonEditorTime);
        TextView tvStartVolumeLabel = dialogView.findViewById(R.id.textViewStartVolumeLabel);
        Slider sliderStartVolume = dialogView.findViewById(R.id.sliderStartVolume);
        TextView tvTargetVolumeLabel = dialogView.findViewById(R.id.textViewTargetVolumeLabel);
        Slider sliderTargetVolume = dialogView.findViewById(R.id.sliderTargetVolume);
        TextView tvFadeDurationLabel = dialogView.findViewById(R.id.textViewFadeDurationLabel);
        Slider sliderFadeDuration = dialogView.findViewById(R.id.sliderFadeDuration);

        tvStation.setText(alarm.station.Name);
        editorTimeButton.setText(String.format(Locale.getDefault(), "%02d:%02d", editorHour, editorMinute));
        editorTimeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TimePickerFragment newFragment = new TimePickerFragment(editorHour, editorMinute);
                newFragment.setCallback(FragmentAlarm.this);
                newFragment.show(getActivity().getSupportFragmentManager(), "timePicker");
            }
        });

        sliderStartVolume.setValue(alarm.startVolume);
        sliderTargetVolume.setValue(alarm.targetVolume);
        sliderFadeDuration.setValue(alarm.fadeDurationSeconds);

        tvStartVolumeLabel.setText(String.format(Locale.getDefault(), "%s: %s",
                getString(R.string.alarm_start_volume),
                String.format(Locale.getDefault(), getString(R.string.alarm_volume_percent), alarm.startVolume)));
        tvTargetVolumeLabel.setText(String.format(Locale.getDefault(), "%s: %s",
                getString(R.string.alarm_target_volume),
                String.format(Locale.getDefault(), getString(R.string.alarm_volume_percent), alarm.targetVolume)));
        tvFadeDurationLabel.setText(String.format(Locale.getDefault(), "%s: %s",
                getString(R.string.alarm_fade_duration),
                String.format(Locale.getDefault(), getString(R.string.alarm_fade_seconds), alarm.fadeDurationSeconds)));

        sliderStartVolume.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(Slider slider, float value, boolean fromUser) {
                tvStartVolumeLabel.setText(String.format(Locale.getDefault(), "%s: %s",
                        getString(R.string.alarm_start_volume),
                        String.format(Locale.getDefault(), getString(R.string.alarm_volume_percent), (int) value)));
            }
        });
        sliderTargetVolume.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(Slider slider, float value, boolean fromUser) {
                tvTargetVolumeLabel.setText(String.format(Locale.getDefault(), "%s: %s",
                        getString(R.string.alarm_target_volume),
                        String.format(Locale.getDefault(), getString(R.string.alarm_volume_percent), (int) value)));
            }
        });
        sliderFadeDuration.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(Slider slider, float value, boolean fromUser) {
                tvFadeDurationLabel.setText(String.format(Locale.getDefault(), "%s: %s",
                        getString(R.string.alarm_fade_duration),
                        String.format(Locale.getDefault(), getString(R.string.alarm_fade_seconds), (int) value)));
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setPositiveButton(R.string.alarm_save, null)
                .setNegativeButton(R.string.alarm_cancel, null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                int startVolume = (int) sliderStartVolume.getValue();
                int targetVolume = (int) sliderTargetVolume.getValue();
                int fadeDuration = (int) sliderFadeDuration.getValue();

                ram.changeTime(alarm.id, editorHour, editorMinute);
                ram.setAlarmFade(alarm.id, startVolume, targetVolume, fadeDuration);
                // 保存后自动打开闹钟开关
                ram.setEnabled(alarm.id, true);

                editingAlarm = null;
                editorTimeButton = null;
                dialog.dismiss();
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
