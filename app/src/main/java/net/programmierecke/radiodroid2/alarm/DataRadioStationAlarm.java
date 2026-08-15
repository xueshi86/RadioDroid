package net.programmierecke.radiodroid2.alarm;

import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.util.ArrayList;

public class DataRadioStationAlarm {
    public DataRadioStation station;
    public int id;
    public int hour;
    public int minute;
    public boolean repeating;
    public ArrayList<Integer> weekDays;
    public boolean enabled;

    // 闹钟音量渐增参数（1-100）：最低 1%，因为 app 永远不将系统音量设为 0
    public int startVolume = 1;
    public int targetVolume = 50;
    public int fadeDurationSeconds = 30;

    // 本闹钟实际注册的系统触发时间（epoch ms）。
    // start() 每次注册时更新；resetAllAlarms() 据此判断单次闹钟是否已错过（错过则作废）。
    // -1 表示旧版本数据未记录该字段，按未错过处理（保持原行为）。
    public long nextTriggerTime = -1;

}
