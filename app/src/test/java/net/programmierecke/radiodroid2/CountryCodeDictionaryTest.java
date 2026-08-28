package net.programmierecke.radiodroid2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

/**
 * 纯逻辑单测：国家代码 → 本地化国名（搜索筛选下拉多语言显示）。
 * 借鉴 AMARadio 方案：系统 Locale API 优先（CLDR 数据），解析失败返回 null 由调用方兜底。
 */
class CountryCodeDictionaryTest {

    private Locale originalLocale;

    @BeforeEach
    void saveLocale() {
        originalLocale = Locale.getDefault();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    @Test
    void chineseLocaleResolvesChineseNames() {
        Locale.setDefault(new Locale("zh"));
        assertEquals("中国", CountryCodeDictionary.getLocalizedCountryName("CN"));
        assertEquals("日本", CountryCodeDictionary.getLocalizedCountryName("JP"));
        assertEquals("德国", CountryCodeDictionary.getLocalizedCountryName("DE"));
    }

    @Test
    void englishLocaleResolvesEnglishNames() {
        Locale.setDefault(new Locale("en"));
        assertEquals("China", CountryCodeDictionary.getLocalizedCountryName("CN"));
        assertEquals("Japan", CountryCodeDictionary.getLocalizedCountryName("JP"));
    }

    @Test
    void otherLocalesResolveTheirNames() {
        Locale.setDefault(new Locale("ru"));
        assertEquals("Китай", CountryCodeDictionary.getLocalizedCountryName("CN"));
        Locale.setDefault(new Locale("de"));
        assertEquals("Japan", CountryCodeDictionary.getLocalizedCountryName("JP"));
    }

    @Test
    void invalidCodeNeverCrashes() {
        Locale.setDefault(new Locale("zh"));
        // 不存在的代码：桌面 CLDR 返回"未知地区"，Android ICU 返回原代码——
        // 平台行为不同但都不抛异常；调用方以"返回 null 时兜底显示代码"的契约使用
        String result = CountryCodeDictionary.getLocalizedCountryName("ZZ");
        if (result != null) {
            assertEquals(false, result.isEmpty());
        }
        assertNull(CountryCodeDictionary.getLocalizedCountryName(null));
        assertNull(CountryCodeDictionary.getLocalizedCountryName(""));
    }

    @Test
    void lowercaseCodeAccepted() {
        Locale.setDefault(new Locale("zh"));
        assertEquals("中国", CountryCodeDictionary.getLocalizedCountryName("cn"));
    }

    @Test
    void localizedResultDiffersFromRawCode() {
        // 防御回归：任何已知代码的显示结果都不应等于代码本身
        Locale.setDefault(new Locale("zh"));
        assertNotEquals("CN", CountryCodeDictionary.getLocalizedCountryName("CN"));
    }
}
