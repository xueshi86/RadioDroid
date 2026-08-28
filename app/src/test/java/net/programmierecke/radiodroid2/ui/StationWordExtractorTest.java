package net.programmierecke.radiodroid2.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * 纯逻辑单测：无图标电台占位图的关键词提取规则。
 * 三级降级链：关键词（跳过噪声词/频率数字）→ 首词 → null（调用方显示 ♪）。
 */
class StationWordExtractorTest {

    // ===== 第一级：关键词（跳过噪声词与频率数字） =====

    @Test
    void latinNoiseWordSkipped() {
        assertEquals("BBC", StationWordExtractor.extractDisplayWord("BBC Radio 1"));
        assertEquals("Hit", StationWordExtractor.extractDisplayWord("Hit FM"));
        assertEquals("Swiss", StationWordExtractor.extractDisplayWord("Radio Swiss Jazz"));
        assertEquals("Sound", StationWordExtractor.extractDisplayWord("100.3 The Sound"));
        assertEquals("CNR", StationWordExtractor.extractDisplayWord("CNR 新闻综合广播"));
        assertEquals("MBC", StationWordExtractor.extractDisplayWord("MBC FM4U"));
        assertEquals("Antenne", StationWordExtractor.extractDisplayWord("Antenne Bayern"));
        assertEquals("record", StationWordExtractor.extractDisplayWord("radio record"));
    }

    @Test
    void frequencyNumberSkippedInKeywordPass() {
        assertEquals("济南新闻", StationWordExtractor.extractDisplayWord("89.5 FM 济南新闻广播"));
        assertEquals("Sound", StationWordExtractor.extractDisplayWord("98.7 Sound Radio"));
    }

    @Test
    void brandNumberAdjacentToCjkIsKept() {
        // 真实回归案例：数字品牌前缀曾被当作频率噪声跳过，导致"首华语经""后音悦"式拦腰截断
        assertEquals("500首", StationWordExtractor.extractDisplayWord("500首华语经典电台"));
        assertEquals("80后", StationWordExtractor.extractDisplayWord("80后音悦台"));
    }

    @Test
    void firstValidTokenWinsWhenNumberComesLater() {
        // 数字不在首位时，首个有效词优先（"重返"本身已具辨别性）
        assertEquals("重返", StationWordExtractor.extractDisplayWord("重返20岁电台"));
    }

    @Test
    void frequencyNumbersStillSkipped() {
        // 带小数点或 ≥4 位整数 → 频率，不并入品牌
        assertEquals("音乐", StationWordExtractor.extractDisplayWord("102.5 音乐广播"));
        assertEquals("北京交通", StationWordExtractor.extractDisplayWord("1026北京交通广播"));
        // 空格隔开的数字与中文不相连
        assertEquals("济南新闻", StationWordExtractor.extractDisplayWord("500 济南新闻广播"));
    }

    @Test
    void cjkStripsTailSuffixes() {
        assertEquals("山东", StationWordExtractor.extractDisplayWord("山东人民广播电台"));
        assertEquals("上海", StationWordExtractor.extractDisplayWord("上海广播电台"));
        assertEquals("北京新闻", StationWordExtractor.extractDisplayWord("北京新闻广播"));
        assertEquals("古典音乐", StationWordExtractor.extractDisplayWord("古典音乐台"));
        assertEquals("音乐之声", StationWordExtractor.extractDisplayWord("音乐之声"));
    }

    @Test
    void cjkKeepsUpToFourCharsForDistinctiveness() {
        // 前缀相同（济南/湖南）的电台靠前 4 字区分
        assertEquals("济南新闻", StationWordExtractor.extractDisplayWord("济南新闻广播"));
        assertEquals("济南交通", StationWordExtractor.extractDisplayWord("济南交通广播"));
    }

    @Test
    void glueKeepsCompoundTokensComplete() {
        assertEquals("89.5", StationWordExtractor.extractDisplayWord("89.5"));
        assertEquals("Radio.co", StationWordExtractor.extractDisplayWord("Radio.co"));
        assertEquals("A.I", StationWordExtractor.extractDisplayWord("A.I. Radio"));
    }

    // ===== 第二级：全噪声时退回首词 =====

    @Test
    void allNoiseFallsBackToFirstWord() {
        assertEquals("Radio", StationWordExtractor.extractDisplayWord("Radio"));
        assertEquals("FM", StationWordExtractor.extractDisplayWord("FM"));
        assertEquals("fm", StationWordExtractor.extractDisplayWord("fm am"));
        assertEquals("89.5", StationWordExtractor.extractDisplayWord("89.5 FM"));
    }

    // ===== 第三级：提取失败 =====

    @Test
    void emptyOrNullReturnsNull() {
        assertNull(StationWordExtractor.extractDisplayWord(null));
        assertNull(StationWordExtractor.extractDisplayWord(""));
        assertNull(StationWordExtractor.extractDisplayWord("   "));
    }

    @Test
    void symbolOnlyReturnsNull() {
        assertNull(StationWordExtractor.extractDisplayWord("★★★"));
        assertNull(StationWordExtractor.extractDisplayWord("· · ·"));
        assertNull(StationWordExtractor.extractDisplayWord("---===---"));
    }

    // ===== 边界 =====

    @Test
    void singleLetterNameKeptAsIs() {
        // 电台名本身就是单字母时没有更优选择
        assertEquals("A", StationWordExtractor.extractDisplayWord("A RADIO"));
        assertEquals("X", StationWordExtractor.extractDisplayWord("X"));
    }

    @Test
    void whitespaceAndDecorationsTolerated() {
        assertEquals("Jazz", StationWordExtractor.extractDisplayWord("  ★ Jazz Radio ★ "));
        assertEquals("KEXP", StationWordExtractor.extractDisplayWord("KEXP — 90.3 Seattle"));
    }

    @Test
    void accentedLatinTreatedAsRealWord() {
        // 带变音符的词不在英文噪声表内，按原词保留
        assertEquals("Rádio", StationWordExtractor.extractDisplayWord("Rádio Dnešek"));
    }
}
