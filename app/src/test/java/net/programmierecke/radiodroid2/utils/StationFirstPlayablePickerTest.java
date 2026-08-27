package net.programmierecke.radiodroid2.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.programmierecke.radiodroid2.database.RadioStation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 纯逻辑单测：无播放历史时从"本地电台"列表挑选第一个可播放电台（StationFirstPlayablePicker）。
 * 覆盖：null/空列表、null 元素、全不可用、混合列表顺序优先、单元素、不修改入参。
 */
class StationFirstPlayablePickerTest {

    private static RadioStation station(boolean working, String name) {
        RadioStation s = new RadioStation();
        s.name = name;
        s.lastcheckok = working;
        return s;
    }

    @Test
    void nullListReturnsNull() {
        assertNull(StationFirstPlayablePicker.firstPlayableOf(null));
    }

    @Test
    void emptyListReturnsNull() {
        assertNull(StationFirstPlayablePicker.firstPlayableOf(Collections.emptyList()));
    }

    @Test
    void allBrokenReturnsNull() {
        List<RadioStation> list = Arrays.asList(
                station(false, "A-broken"),
                station(false, "B-broken"));
        assertNull(StationFirstPlayablePicker.firstPlayableOf(list));
    }

    @Test
    void singleWorkingReturnsIt() {
        RadioStation only = station(true, "only");
        RadioStation pick = StationFirstPlayablePicker.firstPlayableOf(Collections.singletonList(only));
        assertSame(only, pick);
    }

    @Test
    void returnsFirstWorkingInListOrder() {
        List<RadioStation> list = Arrays.asList(
                station(false, "A-broken"),
                station(true, "B-working"),
                station(true, "C-working"));
        RadioStation pick = StationFirstPlayablePicker.firstPlayableOf(list);
        assertEquals("B-working", pick.name);
    }

    @Test
    void skipsBrokenAtListFront_andPreservesOrderAsPriority() {
        List<RadioStation> list = Arrays.asList(
                station(false, "first-broken"),
                station(true, "second-working"));
        RadioStation pick = StationFirstPlayablePicker.firstPlayableOf(list);
        assertEquals("second-working", pick.name);
    }

    @Test
    void skipsNullElements() {
        List<RadioStation> list = Arrays.asList(
                null,
                station(false, "broken"),
                station(true, "good"),
                null);
        RadioStation pick = StationFirstPlayablePicker.firstPlayableOf(list);
        assertNotNull(pick);
        assertEquals("good", pick.name);
    }

    @Test
    void allNullElementsReturnsNull() {
        List<RadioStation> list = Arrays.asList(null, null);
        assertNull(StationFirstPlayablePicker.firstPlayableOf(list));
    }

    @Test
    void listWithOnlyNullAndBrokenReturnsNull() {
        List<RadioStation> list = Arrays.asList(
                null,
                station(false, "broken"));
        assertNull(StationFirstPlayablePicker.firstPlayableOf(list));
    }

    @Test
    void doesNotMutateInputList() {
        List<RadioStation> original = new ArrayList<>(Arrays.asList(
                station(false, "a"),
                station(true, "b")));
        List<RadioStation> copy = new ArrayList<>(original);
        StationFirstPlayablePicker.firstPlayableOf(original);
        assertEquals(copy.size(), original.size());
        for (int i = 0; i < copy.size(); i++) {
            assertSame(copy.get(i), original.get(i));
        }
    }
}