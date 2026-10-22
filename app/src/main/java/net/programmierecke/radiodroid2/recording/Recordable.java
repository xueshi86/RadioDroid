package net.programmierecke.radiodroid2.recording;

import androidx.annotation.NonNull;

import java.util.Map;

public interface Recordable {

    boolean canRecord();

    void startRecording(@NonNull RecordableListener recordableListener);

    void stopRecording();

    boolean isRecording();

    Map<String, String> getRecordNameFormattingArgs();
    String getExtension();

    /**
     * 当前录音所属电台的唯一 ID；用于录音列表显示该录音所属电台的图标。
     * 非电台播放器（如本地代理）没有电台身份时返回 null。
     */
    default String getStationUuid() {
        return null;
    }

    /**
     * 当前录音所属电台名称（未做文件名清理）；用于电台图标缺失时生成占位图标。
     */
    default String getStationName() {
        return null;
    }
}
