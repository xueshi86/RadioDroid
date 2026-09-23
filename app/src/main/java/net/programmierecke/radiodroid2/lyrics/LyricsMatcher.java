package net.programmierecke.radiodroid2.lyrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词匹配严格化工具：判定"元信息缺失/未知"，并校验候选歌词是否与请求的曲目、歌手一致。
 * 原则：宁可不显示歌词，也不显示张冠李戴的歌词。
 */
final class LyricsMatcher {

    /** 标准 LRC 头部署名行：[ar:歌手名]。 */
    private static final Pattern LRC_ARTIST_TAG = Pattern.compile("(?im)^[ \\t]*\\[ar:([^\\]]*)\\]");
    /** 标准 LRC 头部曲目行：[ti:曲目名]。 */
    private static final Pattern LRC_TITLE_TAG = Pattern.compile("(?im)^[ \\t]*\\[ti:([^\\]]*)\\]");

    /** 认定为"元信息缺失"的占位值（英文常量 + 中文占位；本地化占位值由调用方另行比对）。 */
    private static final String[] PLACEHOLDERS = {
            "unknown", "unknown artist", "unknown track", "unknown title",
            "n/a", "na", "-", "--", "未知", "未知歌手", "未知曲目", "未知歌曲"
    };

    private LyricsMatcher() {
    }

    /** 是否为缺失/未知的元信息占位值。 */
    static boolean isUnknown(@Nullable String value) {
        if (value == null) {
            return true;
        }
        String lower = value.trim().toLowerCase();
        if (lower.isEmpty()) {
            return true;
        }
        for (String placeholder : PLACEHOLDERS) {
            if (placeholder.equals(lower)) {
                return true;
            }
        }
        return false;
    }

    /** 归一化：转小写、去标点、压缩空白，保留字母（含中日韩文）与数字。 */
    @NonNull
    static String normalize(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** 歌手是否一致：完全相等，或在长度差异不大时互为包含（容忍 feat.、后缀等写法差异）。 */
    static boolean artistMatches(@Nullable String requestArtist, @Nullable String candidateArtist) {
        return similar(normalize(requestArtist), normalize(candidateArtist));
    }

    /** 曲目是否一致：规则同歌手，避免命中"同名不同曲"。 */
    static boolean titleMatches(@Nullable String requestTrack, @Nullable String candidateTrack) {
        return similar(normalize(requestTrack), normalize(candidateTrack));
    }

    /** 相似判定：完全相等；否则仅在较短者不短于较长者一半、且互为包含时才算一致。 */
    private static boolean similar(@NonNull String a, @NonNull String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        int min = Math.min(a.length(), b.length());
        int max = Math.max(a.length(), b.length());
        if (min < 3 || min * 2 < max) {
            return false;
        }
        return a.contains(b) || b.contains(a);
    }

    /** 提取 LRC 的 [ar:] 署名，缺失或为空返回 null。 */
    @Nullable
    static String extractArtistTag(@NonNull String lrc) {
        return extractTag(LRC_ARTIST_TAG, lrc);
    }

    /** 提取 LRC 的 [ti:] 曲目名，缺失或为空返回 null。 */
    @Nullable
    static String extractTitleTag(@NonNull String lrc) {
        return extractTag(LRC_TITLE_TAG, lrc);
    }

    @Nullable
    private static String extractTag(@NonNull Pattern pattern, @NonNull String lrc) {
        Matcher matcher = pattern.matcher(lrc);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).trim();
        return value.isEmpty() ? null : value;
    }
}
