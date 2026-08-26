package net.programmierecke.radiodroid2.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 纯逻辑单测：搜索关键词清洗工具。
 * 覆盖建议4（搜索加权+转义）新增的 escapeLike / sanitizeFtsQuery。
 */
class SearchHelpersTest {

    // ===== RadioStationRepository.escapeLike =====

    @Test
    void escapeLike_nullReturnsEmpty() {
        assertEquals("", RadioStationRepository.escapeLike(null));
    }

    @Test
    void escapeLike_emptyStaysEmpty() {
        assertEquals("", RadioStationRepository.escapeLike(""));
    }

    @Test
    void escapeLike_plainTextUnchanged() {
        assertEquals("jazz radio", RadioStationRepository.escapeLike("jazz radio"));
    }

    @Test
    void escapeLike_percentEscaped() {
        assertEquals("100\\%", RadioStationRepository.escapeLike("100%"));
    }

    @Test
    void escapeLike_underscoreEscaped() {
        assertEquals("a\\_b", RadioStationRepository.escapeLike("a_b"));
    }

    @Test
    void escapeLike_backslashEscapedBeforeWildcards() {
        // 反斜杠自身必须最先转义，否则后续 %/_ 的转义序列会被叠加污染
        assertEquals("a\\\\b\\%c\\_d", RadioStationRepository.escapeLike("a\\b%c_d"));
    }

    @Test
    void escapeLike_repeatedWildcards() {
        assertEquals("50\\%\\%\\_", RadioStationRepository.escapeLike("50%%_"));
    }

    @Test
    void escapeLike_nonAsciiUntouched() {
        assertEquals("北京 交通广播", RadioStationRepository.escapeLike("北京 交通广播"));
    }

    // ===== RadioStationRepository.sanitizeFtsQuery =====

    @Test
    void sanitizeFtsQuery_nullReturnsNoMatch() {
        assertEquals("__no_match__", RadioStationRepository.sanitizeFtsQuery(null));
    }

    @Test
    void sanitizeFtsQuery_emptyReturnsNoMatch() {
        assertEquals("__no_match__", RadioStationRepository.sanitizeFtsQuery(""));
    }

    @Test
    void sanitizeFtsQuery_whitespaceReturnsNoMatch() {
        assertEquals("__no_match__", RadioStationRepository.sanitizeFtsQuery("   \t "));
    }

    @Test
    void sanitizeFtsQuery_quotesRemoved() {
        // MATCH 语法中双引号会破坏查询，应被清洗为普通空格
        assertEquals("abc def", RadioStationRepository.sanitizeFtsQuery("abc\"def"));
    }

    @Test
    void sanitizeFtsQuery_starRemoved() {
        assertEquals("rock", RadioStationRepository.sanitizeFtsQuery("rock*"));
    }

    @Test
    void sanitizeFtsQuery_trimsEdges() {
        assertEquals("jazz", RadioStationRepository.sanitizeFtsQuery("  jazz  "));
    }

    @Test
    void sanitizeFtsQuery_plainPhraseKept() {
        assertEquals("classical music", RadioStationRepository.sanitizeFtsQuery("classical music"));
    }
}