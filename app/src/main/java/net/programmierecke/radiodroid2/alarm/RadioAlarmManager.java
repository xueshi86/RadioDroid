package net.programmierecke.radiodroid2.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;

import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Observable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class RadioAlarmManager {

    private static final int ONE_DAY_IN_MILLIS = 24 * 60 * 60 * 1000;
    private Context context;
    private List<DataRadioStationAlarm> list = new ArrayList<DataRadioStationAlarm>();
    final int pendingIntentFlag =  Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;


    private class AlarmsObservable extends Observable {
        @Override
        public synchronized boolean hasChanged() {
            return true;
        }
    }

    private Observable savedAlarmsObservable = new AlarmsObservable();

    public RadioAlarmManager(Context context){
        this.context = context;
        load();

//        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
//        SharedPreferences.OnSharedPreferenceChangeListener listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
//            public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
//                if (callback != null) {
//                    callback.onChanged();
//                }
//            }
//        };
//        sharedPref.registerOnSharedPreferenceChangeListener(listener);
    }

    public Observable getSavedAlarmsObservable() {
        return savedAlarmsObservable;
    }

    public void add(DataRadioStation station, int hour, int minute){
        if(BuildConfig.DEBUG) { Log.d("ALARM","added station:"+station.Name); }
        DataRadioStationAlarm alarm = new DataRadioStationAlarm();
        alarm.station = station;
        alarm.hour = hour;
        alarm.minute = minute;
        alarm.weekDays = new ArrayList<>();
        alarm.id = getFreeId();
        alarm.startVolume = 1;
        alarm.targetVolume = 50;
        alarm.fadeDurationSeconds = 30;
        list.add(alarm);

        save();

        setEnabled(alarm.id, true);
    }

    public DataRadioStationAlarm[] getList(){
        return list.toArray(new DataRadioStationAlarm[0]);
    }

    int getFreeId(){
        int i = 0;
        while (!checkIdFree(i)){
            i++;
        }
        if(BuildConfig.DEBUG) { Log.d("ALARM","new free id:"+i); }
        return i;
    }

    boolean checkIdFree(int id){
        for(DataRadioStationAlarm alarm: list){
            if (alarm.id == id){
                return false;
            }
        }
        return true;
    }

    void save(){
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPref.edit();

        String items = "";

        for (DataRadioStationAlarm alarm: list){
            if(BuildConfig.DEBUG) { Log.d("ALARM","save item:"+alarm.id+"/"+alarm.station.Name); }
            editor.putString("alarm."+alarm.id+".station",alarm.station.toJson().toString());
            editor.putInt("alarm."+alarm.id+".timeHour",alarm.hour);
            editor.putInt("alarm."+alarm.id+".timeMinutes",alarm.minute);
            editor.putBoolean("alarm."+alarm.id+".enabled",alarm.enabled);
            editor.putBoolean("alarm."+alarm.id+".repeating",alarm.repeating);

            Gson gson = new Gson();
            String weekdaysString = gson.toJson(alarm.weekDays);
            editor.putString("alarm."+alarm.id+".weekDays",weekdaysString);
            editor.putInt("alarm."+alarm.id+".startVolume",alarm.startVolume);
            editor.putInt("alarm."+alarm.id+".targetVolume",alarm.targetVolume);
            editor.putInt("alarm."+alarm.id+".fadeDurationSeconds",alarm.fadeDurationSeconds);
            editor.putLong("alarm."+alarm.id+".nextTriggerTime",alarm.nextTriggerTime);

            if (items.equals("")) {
                items = "" + alarm.id;
            }else{
                items = items + "," + alarm.id;
            }
        }

        editor.putString("alarm.ids",items);
        editor.apply();

        savedAlarmsObservable.notifyObservers();
    }

    public void load(){
        list.clear();
        if(BuildConfig.DEBUG) { Log.d("ALARM","load()"); }

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        String ids = sharedPref.getString("alarm.ids", "");
        if (!ids.equals("")) {
            String[] idsArr = ids.split(",");
            if(BuildConfig.DEBUG) { Log.d("ALARM", "load() - " + idsArr.length); }
            for (String id : idsArr) {
                DataRadioStationAlarm alarm = new DataRadioStationAlarm();

                alarm.station = DataRadioStation.DecodeJsonSingle(sharedPref.getString("alarm." + id + ".station", null));
                String weekDaysString  = sharedPref.getString("alarm." + id + ".weekDays", "[]");
                Gson gson = new Gson();
                alarm.weekDays = gson.fromJson(weekDaysString, new TypeToken<List<Integer>>(){}.getType());
                alarm.hour = sharedPref.getInt("alarm." + id + ".timeHour", 0);
                alarm.minute = sharedPref.getInt("alarm." + id + ".timeMinutes", 0);
                alarm.enabled = sharedPref.getBoolean("alarm." + id + ".enabled", false);
                alarm.repeating  = sharedPref.getBoolean("alarm." + id + ".repeating", false);
                // 旧版本可能保存了 0%，统一归一化到 [1,100]（app 永不将系统音量设为 0）
                alarm.startVolume = Math.max(1, Math.min(100, sharedPref.getInt("alarm." + id + ".startVolume", 1)));
                alarm.targetVolume = Math.max(1, Math.min(100, sharedPref.getInt("alarm." + id + ".targetVolume", 50)));
                alarm.fadeDurationSeconds = sharedPref.getInt("alarm." + id + ".fadeDurationSeconds", 30);
                // 旧版本数据没有该字段时默认 -1（不误判为错过）
                alarm.nextTriggerTime = sharedPref.getLong("alarm." + id + ".nextTriggerTime", -1);

                try {
                    alarm.id = Integer.parseInt(id);
                    if (alarm.station != null) {
                        list.add(alarm);
                    }
                } catch (Exception e) {
                    Log.e("ALARM", "could not decode:" + id);
                }
            }
        }else{
            Log.w("ALARM","empty load() string");
        }

        // 打开应用时：已错过的单次闹钟直接作废（enabled=false），
        // 避免界面显示"仍开启"，也防止后续 resetAllAlarms() 将其重新激活补响。
        // 重复闹钟或旧数据（nextTriggerTime=-1）不受影响。
        boolean disabledMissed = false;
        long now = System.currentTimeMillis();
        for (DataRadioStationAlarm alarm : list) {
            if (alarm.enabled && !alarm.repeating
                    && alarm.nextTriggerTime >= 0 && alarm.nextTriggerTime < now) {
                alarm.enabled = false;
                disabledMissed = true;
                if(BuildConfig.DEBUG) { Log.d("ALARM","missed one-shot alarm disabled on load:"+alarm.id); }
            }
        }
        if (disabledMissed) {
            save();
        }
    }

    public void setEnabled(int alarmId, boolean enabled) {
        DataRadioStationAlarm alarm = getById(alarmId);
        if (alarm != null) {
            if (enabled != alarm.enabled) {
                alarm.enabled = enabled;
                save();

                if (enabled){
                    start(alarmId);
                }else{
                    stop(alarmId);
                }
            }
        }
    }

    DataRadioStationAlarm getById(int id){
        for(DataRadioStationAlarm alarm: list){
            if (id == alarm.id){
                return alarm;
            }
        }
        return null;
    }

    void start(int alarmId) {
        DataRadioStationAlarm alarm = getById(alarmId);
        if (alarm != null) {
            stop(alarmId);

            Intent intent = new Intent(context, AlarmReceiver.class);
            intent.putExtra("id",alarmId);
            PendingIntent alarmIntent = PendingIntent.getBroadcast(context, alarmId, intent, PendingIntent.FLAG_UPDATE_CURRENT | pendingIntentFlag);
            AlarmManager alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(System.currentTimeMillis());
            calendar.set(Calendar.HOUR_OF_DAY, alarm.hour);
            calendar.set(Calendar.MINUTE, alarm.minute);
            calendar.set(Calendar.SECOND, 0);

            // if new calendar is in the past, move it 1 day ahead
            // add 1 min, to ignore already fired events
            if (calendar.getTimeInMillis() < System.currentTimeMillis() + 60){
                if(BuildConfig.DEBUG) { Log.d("ALARM","moved ahead one day"); }
                calendar.setTimeInMillis(calendar.getTimeInMillis() + ONE_DAY_IN_MILLIS);
            }

            if (alarm.repeating) {
                Integer currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
                Collections.sort(alarm.weekDays);
                Integer limiter = 6;
                while (!alarm.weekDays.contains(currentDayOfWeek - 1) && limiter > 0) {
                    calendar.setTimeInMillis(calendar.getTimeInMillis() + ONE_DAY_IN_MILLIS);
                    currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
                    limiter--;
                }
            }
            Log.d(
                    "ALARM","started:" +alarmId + " "
                    + calendar.get(Calendar.DAY_OF_WEEK) + " "
                    + calendar.get(Calendar.DAY_OF_MONTH)
                    + "." + calendar.get(Calendar.MONTH)
                    + " " + calendar.get(Calendar.HOUR_OF_DAY) + ":" + calendar.get(Calendar.MINUTE)
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if(BuildConfig.DEBUG) { Log.d("ALARM","START setAlarmClock"); }
                alarmMgr.setAlarmClock(new AlarmManager.AlarmClockInfo(calendar.getTimeInMillis(),alarmIntent),alarmIntent);
            }else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                if(BuildConfig.DEBUG) { Log.d("ALARM","START setExact"); }
                alarmMgr.setExact(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), alarmIntent);
            }else{
                if(BuildConfig.DEBUG) { Log.d("ALARM","START set"); }
                alarmMgr.set(AlarmManager.RTC_WAKEUP,calendar.getTimeInMillis(),alarmIntent);
            }

            // 记录实际注册的触发时间，供 resetAllAlarms() 判断单次闹钟是否已错过
            if (alarm.nextTriggerTime != calendar.getTimeInMillis()) {
                alarm.nextTriggerTime = calendar.getTimeInMillis();
                save();
            }
        }
    }

    void stop(int alarmId) {
        DataRadioStationAlarm alarm = getById(alarmId);
        if (alarm != null) {
            if(BuildConfig.DEBUG) { Log.d("ALARM","stopped:"+alarmId); }
            Intent intent = new Intent(context, AlarmReceiver.class);
            PendingIntent alarmIntent = PendingIntent.getBroadcast(context, alarmId, intent, pendingIntentFlag);
            AlarmManager alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            alarmMgr.cancel(alarmIntent);
        }
    }

    public void changeTime(int alarmId, int hourOfDay, int minute) {
        DataRadioStationAlarm alarm = getById(alarmId);
        if (alarm != null) {
            alarm.hour = hourOfDay;
            alarm.minute = minute;
            save();

            if (alarm.enabled){
                stop(alarmId);
                start(alarmId);
            }
        }
    }

    public void setAlarmFade(int alarmId, int startVolume, int targetVolume, int fadeDurationSeconds) {
        DataRadioStationAlarm alarm = getById(alarmId);
        if (alarm != null) {
            alarm.startVolume = Math.max(1, Math.min(100, startVolume));
            alarm.targetVolume = Math.max(1, Math.min(100, targetVolume));
            alarm.fadeDurationSeconds = Math.max(0, fadeDurationSeconds);
            save();
        }
    }

    public void changeWeekDays(int alarmId, int weekday) {
        DataRadioStationAlarm alarm = getById(alarmId);
        if (alarm != null) {
            if (alarm.weekDays == null) {
                alarm.weekDays = new ArrayList<>();
            }
            int position = alarm.weekDays.indexOf(weekday);
            if (position == -1) {
                alarm.weekDays.add(weekday);
            } else {
                alarm.weekDays.remove(position);
            }
            save();
            start(alarmId);
        }
    }

    public void remove(int id) {
        DataRadioStationAlarm alarm = getById(id);
        if (alarm != null) {
            stop(id);
            list.remove(alarm);
            save();
        }
    }

    public DataRadioStation getStation(int stationId) {
        DataRadioStationAlarm alarm = getById(stationId);
        if (alarm != null) {
            return alarm.station;
        }
        return null;
    }

    /**
     * 重新注册所有启用的闹钟（设备重启、或某个闹钟响后调用）。
     * 单次闹钟：若原定触发时间已过（例如被强行停止/冻结而错过，或设备长时间关机后开机），
     * 视为作废并自动禁用，不再跨天补响。重复闹钟不受影响，start() 内部会顺延到下一个匹配日。
     */
    public void resetAllAlarms() {
        boolean disabledMissed = false;
        for(DataRadioStationAlarm alarm: list){
            if (alarm.enabled){
                if (alarm.repeating) {
                    start(alarm.id);
                } else {
                    // -1 表示旧版本数据未记录触发时间，保持原行为（重新注册）
                    long now = System.currentTimeMillis();
                    if (alarm.nextTriggerTime >= 0 && alarm.nextTriggerTime < now) {
                        if(BuildConfig.DEBUG) { Log.d("ALARM","missed one-shot alarm disabled:"+alarm.id); }
                        alarm.enabled = false;
                        disabledMissed = true;
                    } else {
                        if(BuildConfig.DEBUG) { Log.d("ALARM","started alarm with id:"+alarm.id); }
                        start(alarm.id);
                    }
                }
            }
        }
        if (disabledMissed) {
            save();
        }
    }

    public void toggleRepeating(int id) {
        DataRadioStationAlarm alarm = getById(id);
        if (alarm != null) {
            alarm.repeating = !alarm.repeating;
            save();
            start(id);
        }
    }
}
