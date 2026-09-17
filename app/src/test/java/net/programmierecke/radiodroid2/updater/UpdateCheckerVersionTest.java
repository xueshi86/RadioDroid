package net.programmierecke.radiodroid2.updater;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerVersionTest {

    @Test
    @DisplayName("普通递增版本号判定为更新")
    void testSimpleNewer() {
        assertTrue(UpdateChecker.versionNewerThan("1.09", "1.08"));
        assertTrue(UpdateChecker.versionNewerThan("1.08", "1.07"));
    }

    @Test
    @DisplayName("数值逐段比较：1.10 大于 1.9")
    void testMultiDigitSegment() {
        assertTrue(UpdateChecker.versionNewerThan("1.10", "1.9"));
        assertTrue(UpdateChecker.versionNewerThan("10.0", "2.0"));
    }

    @Test
    @DisplayName("容忍 v 前缀")
    void testAcceptVPrefix() {
        assertTrue(UpdateChecker.versionNewerThan("v1.09", "1.08"));
        assertTrue(UpdateChecker.versionNewerThan("1.09", "v1.08"));
    }

    @Test
    @DisplayName("三段版本号比较")
    void testThreeSegments() {
        assertTrue(UpdateChecker.versionNewerThan("1.0.2", "1.0.1"));
        assertTrue(UpdateChecker.versionNewerThan("1.1.0", "1.0.9"));
    }

    @Test
    @DisplayName("相等版本不算更新")
    void testEqualNotNewer() {
        assertFalse(UpdateChecker.versionNewerThan("1.08", "1.08"));
        assertFalse(UpdateChecker.versionNewerThan("1.10", "1.10"));
        assertFalse(UpdateChecker.versionNewerThan("1.0.1", "1.0.1"));
    }

    @Test
    @DisplayName("旧版本小于当前版本")
    void testOldVersion() {
        assertFalse(UpdateChecker.versionNewerThan("1.07", "1.08"));
    }
}