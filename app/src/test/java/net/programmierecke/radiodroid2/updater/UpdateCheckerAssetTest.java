package net.programmierecke.radiodroid2.updater;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpdateCheckerAssetTest {

    private List<String> sampleAssets() {
        return new ArrayList(Arrays.asList(
                "radio_droid_db.bravo.enc.7z",
                "RadioDroid-free-release-1.09-abc1234.apk",
                "RadioDroid-play-release-1.09-abc1234.apk",
                "radio_droid_db.enc"
        ));
    }

    @Test
    @DisplayName("free flavor 选出 free 的 APK")
    void testFreeFlavor() {
        assertEquals("RadioDroid-free-release-1.09-abc1234.apk",
                UpdateChecker.findApkAsset(sampleAssets(), "free"));
    }

    @Test
    @DisplayName("play flavor 选出 play 的 APK")
    void testPlayFlavor() {
        assertEquals("RadioDroid-play-release-1.09-abc1234.apk",
                UpdateChecker.findApkAsset(sampleAssets(), "play"));
    }

    @Test
    @DisplayName("无匹配 asset 返回 null")
    void testNoMatch() {
        assertNull(UpdateChecker.findApkAsset(new ArrayList<>(), "free"));
        assertNull(UpdateChecker.findApkAsset(
                new ArrayList(Arrays.asList("radio_droid_db.enc")), "play"));
    }

    @Test
    @DisplayName("忽略大小写之外不误匹配其他 flavor")
    void testDistinctFlavor() {
        // 只有 play 的 APK 时，free 不应选中 play
        assertNull(UpdateChecker.findApkAsset(
                new ArrayList(Arrays.asList("RadioDroid-play-release-1.09-abc.apk")), "free"));
    }
}