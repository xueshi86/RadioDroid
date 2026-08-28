package net.programmierecke.radiodroid2;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Created by segler on 21.02.18.
 */

public class CountryCodeDictionary {
    private static final CountryCodeDictionary ourInstance = new CountryCodeDictionary();

    public static CountryCodeDictionary getInstance() {
        return ourInstance;
    }

    private CountryCodeDictionary() {
    }

    private class Country {
        private String name;
        private String code;

        public String getName() {
            return name;
        }

        public String getCode() {
            return code;
        }
    }

    private Map<String, String> codeToCountry = new HashMap<>();

    public void load(Context context) {
        Resources resources = context.getResources();
        final InputStream inputStream = resources.openRawResource(R.raw.countries);
        final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

        Gson gson = new Gson();
        Type collectionType = new TypeToken<Collection<Country>>() {
        }.getType();
        Collection<Country> countries = gson.fromJson(reader, collectionType);

        for (CountryCodeDictionary.Country country : countries) {
            codeToCountry.put(country.getCode().toLowerCase(Locale.ENGLISH), country.getName());
        }
    }

    /**
     * ISO 代码 → 本地化国名（借鉴 AMARadio：系统 Locale API 优先，countries.json 英文名兜底）。
     * 显示语言跟随 Locale.getDefault()，应用内切换语言时 ActivityMain.initAppLanguage 已同步该值。
     */
    public String getCountryByCode(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        String localized = getLocalizedCountryName(code);
        if (localized != null) {
            return localized;
        }
        return codeToCountry.get(code.toLowerCase(Locale.ENGLISH));
    }

    /**
     * 纯系统级本地化：ISO 3166-1 代码 → 当前默认语言的国名（"CN"+中文 → "中国"）。
     * 系统无法解析（代码非法/未注册，显示结果为空或等于原代码）时返回 null，由调用方兜底。
     * 纯 Java 逻辑，可 JVM 单测。
     */
    public static String getLocalizedCountryName(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        try {
            // new Locale("", code) 全 API 级别可用（Locale.Builder 需 API 21，项目 minSdk 16）
            String display = new Locale("", code).getDisplayCountry(Locale.getDefault());
            if (display != null && !display.isEmpty() && !display.equalsIgnoreCase(code)) {
                return display;
            }
        } catch (Exception ignored) {
            // 极端异常时走英文表兜底
        }
        return null;
    }
}
