package net.programmierecke.radiodroid2.recording;

import java.util.Date;

public class DataRecording {
    public String Name = "";
    public Date Time;

    // 该录音所属电台的信息（用于列表行首电台图标）。
    // 新录音在录制时记录电台身份；旧录音由 RecordingsManager 按曲目历史反查，
    // 均无法确定时留空，列表显示通用占位图标。
    public String StationUuid;
    public String StationName;
    public String StationIconUrl;
    public String StationHomePageUrl;
}
