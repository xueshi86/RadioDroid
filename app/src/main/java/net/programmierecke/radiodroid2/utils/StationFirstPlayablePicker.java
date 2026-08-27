package net.programmierecke.radiodroid2.utils;

import net.programmierecke.radiodroid2.database.RadioStation;

import java.util.List;

/**
 * 选台纯逻辑工具：从电台列表中挑选第一个可播放电台。
 * 与"本地电台"列表页展示顺序一致（由调用方传入已排序列表，列表顺序即优先顺序），
 * 供无播放历史时新建闹钟兜底选台使用；抽成独立静态方法便于 JVM 单元测试。
 */
public final class StationFirstPlayablePicker {

    private StationFirstPlayablePicker() {
    }

    /**
     * 从（已排序的）电台列表中返回第一个可播放电台（lastcheckok=true），无则返回 null。
     *
     * @param stations 已按"本地电台"列表页展示顺序排好的电台列表，可为 null
     */
    public static RadioStation firstPlayableOf(List<RadioStation> stations) {
        if (stations == null) {
            return null;
        }
        for (RadioStation station : stations) {
            if (station != null && station.lastcheckok) {
                return station;
            }
        }
        return null;
    }
}