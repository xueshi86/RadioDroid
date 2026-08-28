package net.programmierecke.radiodroid2.ui;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 电台名关键词提取（纯 Java、无 Android 依赖，可 JVM 单测）。
 *
 * 目的：占位图显示单个首字母辨别性太差（大量电台同以 A 开头），
 * 改为显示"关键词或首词"——完整的词，配合 UUID 稳定 7 色底图提高辨识度。
 *
 * 三级降级：
 *  1) 关键词：跳过噪声词（fm/am/radio/the/live 等通用词）与频率数字后的首个有效词；
 *     中文词先剥离尾部通用后缀（广播/电台/频率/台…），再取前 {@link #MAX_CJK_CHARS} 字
 *     （保留"济南新闻广播"→"济南新闻" vs "济南交通广播"→"济南交通" 的区分度）；
 *     数字与中文直接相连且 ≤3 位无小数点时视为数字品牌（"500首华语经典电台"→"500首"，
 *     "80后音悦台"→"80后"），避免拦腰截断产出"首华语经"这类不可读切片
 *  2) 首词：全部是噪声时退回原文第一个词
 *  3) 失败：空名/纯符号（如 "★★★"）→ 返回 null，由调用方显示 ♪
 *
 * 词元切分规则：字母/数字连续段为一个词（中日韩与拉丁文按文字类型分界）；
 * '.' 与 ',' 前后都是字母或数字时视为词内连接符（"89.5"、"Radio.co"、"A.I." 保持完整）。
 */
public final class StationWordExtractor {

    /** 中文关键词最大保留字数 */
    public static final int MAX_CJK_CHARS = 4;

    /** 拉丁噪声词（小写比较）：电台名中大量出现、无辨别性的通用词 */
    private static final Set<String> NOISE_WORDS = new HashSet<>(Arrays.asList(
            "fm", "am", "radio", "the", "live", "online", "web", "internet",
            "station", "mhz", "khz", "hd", "dab", "webradio", "radios", "stream", "stereo"));

    /** 中文通用后缀（长词在前）：从尾部反复剥离，剩余至少保留 2 字 */
    private static final String[] CJK_TAIL_SUFFIXES = {
            "人民广播电台", "广播电台", "电台", "广播", "频率", "台"};

    private static final int TYPE_NUMBER = 0;
    private static final int TYPE_LATIN = 1;
    private static final int TYPE_CJK = 2;

    private StationWordExtractor() {
    }

    /**
     * 提取占位图显示词。返回 null 表示无可提取内容（调用方回退 ♪）。
     */
    @Nullable
    public static String extractDisplayWord(@Nullable String name) {
        if (name == null) {
            return null;
        }
        String s = name.trim();
        if (s.isEmpty()) {
            return null;
        }

        List<Token> tokens = tokenize(s);
        if (tokens.isEmpty()) {
            return null;
        }

        // 第一级：跳过噪声词与数字，取首个有效词
        String keyword = pickKeyword(tokens);
        if (keyword != null) {
            return keyword;
        }

        // 第二级：全部是噪声时退回首词（可能是 "FM"、"89.5" 这类）
        return pickFirstWord(tokens);
    }

    // ==================== 关键词/首词选择 ====================

    @Nullable
    private static String pickKeyword(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.type == TYPE_LATIN) {
                String lower = t.text.toLowerCase(Locale.ROOT);
                if (!NOISE_WORDS.contains(lower)) {
                    return t.text;
                }
            } else if (t.type == TYPE_CJK) {
                String stripped = stripCjkTail(t.text);
                if (!stripped.isEmpty()) {
                    // 数字品牌前缀：数字与中文直接相连（无空格分隔）且 ≤3 位、无小数点时，
                    // 是品牌的一部分而非频率（"500首华语经典电台"→"500首"，"80后音悦台"→"80后"）；
                    // 带小数点（89.5）或 ≥4 位整数（1026）仍按频率噪声跳过，
                    // 避免拦腰截断产出"首华语经"这类不可读切片
                    if (t.adjacentToPrev && i > 0) {
                        Token prev = tokens.get(i - 1);
                        if (prev.type == TYPE_NUMBER && isBrandNumber(prev.text)) {
                            return prev.text + stripped.substring(0, Math.min(1, stripped.length()));
                        }
                    }
                    return stripped.substring(0, Math.min(MAX_CJK_CHARS, stripped.length()));
                }
            }
            // 频率数字（带小数点/长整数/独立出现）在关键词级跳过
        }
        return null;
    }

    private static boolean isBrandNumber(String number) {
        return number.length() <= 3 && number.indexOf('.') < 0 && number.indexOf(',') < 0;
    }

    @Nullable
    private static String pickFirstWord(List<Token> tokens) {
        for (Token t : tokens) {
            if (t.type == TYPE_CJK) {
                String stripped = stripCjkTail(t.text);
                if (!stripped.isEmpty()) {
                    return stripped.substring(0, Math.min(MAX_CJK_CHARS, stripped.length()));
                }
            }
            return t.text; // 拉丁词或频率数字原样返回
        }
        return null;
    }

    // ==================== 中文后缀剥离 ====================

    private static String stripCjkTail(String s) {
        boolean changed = true;
        while (changed && s.length() > 2) {
            changed = false;
            for (String suffix : CJK_TAIL_SUFFIXES) {
                if (s.length() - suffix.length() >= 2 && s.endsWith(suffix)) {
                    s = s.substring(0, s.length() - suffix.length());
                    changed = true;
                    break;
                }
            }
        }
        return s;
    }

    // ==================== 词元切分 ====================

    private static final class Token {
        final String text;
        final int type;
        /** 与前一词元是否直接相连（中间无分隔符；中日韩↔拉丁/数字的文字分界也算相连） */
        final boolean adjacentToPrev;

        Token(String text, int type, boolean adjacentToPrev) {
            this.text = text;
            this.type = type;
            this.adjacentToPrev = adjacentToPrev;
        }
    }

    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int n = s.length();
        int i = 0;

        StringBuilder cur = new StringBuilder();
        boolean curIsCjk = false;
        boolean hasCjk = false;
        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean curAdjacent = false; // 当前正在构建的词元与前一词元是否直接相连

        while (i < n) {
            int cp = s.codePointAt(i);
            int width = Character.charCount(cp);

            if (isWordCp(cp)) {
                boolean cjk = isCjkCp(cp);
                if (cur.length() > 0 && cjk != curIsCjk) {
                    // 中日韩与拉丁文分界：断词，且新词元与前词元直接相连
                    out.add(buildToken(cur, hasCjk, hasLetter, hasDigit, curAdjacent));
                    cur.setLength(0);
                    hasCjk = false;
                    hasLetter = false;
                    hasDigit = false;
                    curAdjacent = true;
                }
                if (cur.length() == 0) {
                    curIsCjk = cjk;
                }
                cur.appendCodePoint(cp);
                hasCjk |= cjk;
                if (Character.isLetter(cp)) {
                    hasLetter = true;
                } else {
                    hasDigit = true;
                }
                i += width;
            } else if (isGlueCp(cp) && cur.length() > 0 && nextIsWordOfSameClass(s, i + width, curIsCjk)) {
                // '.' / ',' 前后都是同类字母数字时保持词完整（89.5 / Radio.co / A.I.）
                cur.appendCodePoint(cp);
                i += width;
            } else {
                if (cur.length() > 0) {
                    out.add(buildToken(cur, hasCjk, hasLetter, hasDigit, curAdjacent));
                    cur.setLength(0);
                    hasCjk = false;
                    hasLetter = false;
                    hasDigit = false;
                }
                // 分隔符打断相连关系
                curAdjacent = false;
                i += width;
            }
        }
        if (cur.length() > 0) {
            out.add(buildToken(cur, hasCjk, hasLetter, hasDigit, curAdjacent));
        }
        return out;
    }

    private static Token buildToken(StringBuilder cur, boolean hasCjk, boolean hasLetter, boolean hasDigit, boolean adjacentToPrev) {
        int type;
        if (hasCjk) {
            type = TYPE_CJK;
        } else if (hasLetter) {
            type = TYPE_LATIN;
        } else {
            type = TYPE_NUMBER;
        }
        return new Token(cur.toString(), type, adjacentToPrev);
    }

    private static boolean isWordCp(int cp) {
        return Character.isLetterOrDigit(cp);
    }

    private static boolean isGlueCp(int cp) {
        return cp == '.' || cp == ',';
    }

    private static boolean nextIsWordOfSameClass(String s, int from, boolean curIsCjk) {
        if (from >= s.length()) {
            return false;
        }
        int cp = s.codePointAt(from);
        return isWordCp(cp) && isCjkCp(cp) == curIsCjk;
    }

    private static boolean isCjkCp(int cp) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        if (block == null) {
            return false;
        }
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }
}
