package net.programmierecke.radiodroid2.station.live;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import net.programmierecke.radiodroid2.BuildConfig;

import java.util.Map;

public class StreamLiveInfo implements Parcelable {

    final private String TAG = "StreamLiveInfo";

    public StreamLiveInfo(Map<String, String> rawMetadata) {
        this.rawMetadata = rawMetadata;

        // 总是输出调试信息，以便诊断乱码问题
        Log.i(TAG, "=== StreamLiveInfo构造函数开始 ===");
        Log.i(TAG, "StreamLiveInfo构造函数 - 原始元数据: " + rawMetadata);

        if (rawMetadata != null && rawMetadata.containsKey("StreamTitle")) {
            title = rawMetadata.get("StreamTitle");

            // 空白归一：部分电台（如 ICECAST 服务器）返回仅含空格的 StreamTitle，
            // TextUtils.isEmpty 将其判为非空，导致「正在播放」行与曲目历史显示空白。
            // 将纯空白标题归一为空串，走统一的未知兜底。
            if (title != null && title.trim().isEmpty()) {
                Log.w(TAG, "StreamLiveInfo构造函数 - StreamTitle仅为空白('" + title + "')，归一为空串");
                title = "";
            }

            Log.i(TAG, "StreamLiveInfo构造函数 - StreamTitle: " + title);
            Log.i(TAG, "StreamLiveInfo构造函数 - StreamTitle长度: " + (title != null ? title.length() : 0));
            
            // 尝试检测和修复编码问题
            if (title != null && !title.isEmpty()) {
                title = fixEncodingIssues(title);
                Log.i(TAG, "StreamLiveInfo构造函数 - 修复编码后的StreamTitle: " + title);
            }
            
            // 打印标题中每个字符的Unicode值
            if (title != null && !title.isEmpty()) {
                StringBuilder charCodes = new StringBuilder();
                for (int i = 0; i < Math.min(title.length(), 100); i++) {
                    char c = title.charAt(i);
                    charCodes.append(String.format("'%c'(%04X) ", c, (int) c));
                }
                Log.i(TAG, "StreamLiveInfo构造函数 - StreamTitle的前100个字符的Unicode值: " + charCodes.toString());
            }
            
            // 打印标题的字节表示（用于调试编码问题）
            if (title != null && !title.isEmpty()) {
                try {
                    byte[] bytes = title.getBytes("UTF-8");
                    StringBuilder byteCodes = new StringBuilder();
                    for (int i = 0; i < Math.min(bytes.length, 100); i++) {
                        byteCodes.append(String.format("%02X ", bytes[i] & 0xFF));
                    }
                    Log.i(TAG, "StreamLiveInfo构造函数 - StreamTitle的前100个字节的UTF-8编码: " + byteCodes.toString());
                } catch (Exception e) {
                    Log.e(TAG, "StreamLiveInfo构造函数 - 获取字节编码时出错: " + e.getMessage());
                }
            }

            if (!TextUtils.isEmpty(title)) {
                // 尝试智能解析元数据
                Log.i(TAG, "StreamLiveInfo构造函数 - 开始解析元数据");
                parseMetadataIntelligently(title);

                Log.i(TAG, "StreamLiveInfo构造函数 - 解析完成");
                Log.i(TAG, "StreamLiveInfo构造函数 - 最终艺术家: '" + artist + "'");
                Log.i(TAG, "StreamLiveInfo构造函数 - 最终曲目: '" + track + "'");
                Log.i(TAG, "=== StreamLiveInfo构造函数结束 ===");
            } else if (title != null && title.isEmpty()) {
                // 处理空字符串情况（可能是检测到数据损坏）
                Log.w(TAG, "StreamLiveInfo构造函数 - 检测到空标题，可能是数据损坏");
                // 保持artist和track为空字符串，不进行解析
            }
        } else {
            Log.w(TAG, "StreamLiveInfo构造函数 - 原始元数据为空或不包含StreamTitle");
        }

        // 兜底：智能解析出的 artist/track 也可能为空白（如「歌手 - 」这类畸形标题），
        // 统一归一为空串，保证 hasArtistAndTrack() 与 UI 未知兜底判定一致
        if (artist.trim().isEmpty()) {
            artist = "";
        }
        if (track.trim().isEmpty()) {
            track = "";
        }
    }

    /**
     * 将字节数组转换为十六进制字符串（用于调试）
     * @param bytes 字节数组
     * @param maxBytes 最大显示字节数
     * @return 十六进制字符串
     */
    private String bytesToHex(byte[] bytes, int maxBytes) {
        if (bytes == null || bytes.length == 0) {
            return "[]";
        }
        
        StringBuilder hexString = new StringBuilder();
        int limit = Math.min(bytes.length, maxBytes);
        
        for (int i = 0; i < limit; i++) {
            hexString.append(String.format("%02X ", bytes[i] & 0xFF));
        }
        
        if (bytes.length > maxBytes) {
            hexString.append("...");
        }
        
        return hexString.toString();
    }

    /**
     * 尝试修复编码问题，使用多种编码组合检测和修复
     * @param input 可能存在编码问题的字符串
     * @return 修复后的字符串
     */
    private String fixEncodingIssues(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        // 如果字符串看起来已经是合理的，直接返回
        if (isReasonableString(input)) {
            return input;
        }
        
        try {
            // 编码修复尝试列表，按优先级排序
            String[][] encodingPairs = {
                {"ISO-8859-1", "UTF-8"},   // 最常见的情况：UTF-8被错误地用ISO-8859-1解码
                {"GBK", "UTF-8"},          // 中文常见情况：UTF-8被错误地用GBK解码
                {"ISO-8859-1", "GBK"},     // 中文情况：GBK被错误地用ISO-8859-1解码
                {"ISO-8859-1", "GB2312"},   // 中文旧编码情况
                {"ISO-8859-1", "Big5"},     // 繁体中文情况
                {"UTF-8", "ISO-8859-1"},   // 反向情况：ISO-8859-1被错误地用UTF-8解码
                {"GB2312", "UTF-8"},       // 中文情况：UTF-8被错误地用GB2312解码
                {"Big5", "UTF-8"}          // 繁体中文情况：UTF-8被错误地用Big5解码
            };
            
            for (String[] pair : encodingPairs) {
                try {
                    // 尝试将字符串按源编码转换为字节，然后按目标编码解码
                    byte[] bytes = input.getBytes(pair[0]);
                    String fixedString = new String(bytes, pair[1]);
                    
                    // 检查修复后的字符串是否合理
                    if (isReasonableString(fixedString) && !fixedString.equals(input)) {
                        Log.i(TAG, "fixEncodingIssues - 成功修复编码问题，使用组合: " + pair[0] + " -> " + pair[1]);
                        Log.i(TAG, "fixEncodingIssues - 原始字符串: " + input);
                        Log.i(TAG, "fixEncodingIssues - 修复后字符串: " + fixedString);
                        return fixedString;
                    }
                } catch (Exception e) {
                    // 忽略单个编码组合的异常，继续尝试下一个
                    Log.d(TAG, "fixEncodingIssues - 尝试编码组合 " + pair[0] + " -> " + pair[1] + " 失败: " + e.getMessage());
                }
            }
            
            // 尝试直接检测UTF-8编码
            if (isUtf8Encoded(input)) {
                try {
                    byte[] bytes = input.getBytes("ISO-8859-1");
                    String fixedString = new String(bytes, "UTF-8");
                    if (isReasonableString(fixedString)) {
                        Log.i(TAG, "fixEncodingIssues - 直接检测到UTF-8编码并修复");
                        return fixedString;
                    }
                } catch (Exception e) {
                    Log.d(TAG, "fixEncodingIssues - UTF-8直接检测失败: " + e.getMessage());
                }
            }
            
            Log.i(TAG, "fixEncodingIssues - 无法自动修复编码问题，返回原始字符串");
            return input;
            
        } catch (Exception e) {
            Log.e(TAG, "fixEncodingIssues - 修复编码时出错: " + e.getMessage());
            return input;
        }
    }
    
    /**
     * 检测字符串是否可能是UTF-8编码被错误解码
     * @param input 要检测的字符串
     * @return 如果可能是UTF-8编码返回true，否则返回false
     */
    private boolean isUtf8Encoded(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        
        int consecutiveInvalidChars = 0;
        int potentialUtf8Sequences = 0;
        
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            
            // 检测常见的UTF-8编码错误模式
            // 例如：Ã¥Â¦Â -> 如 (UTF-8: E5 A6 82)
            if ((c >= 0xC0 && c <= 0xDF) || // UTF-8 2字节前缀
                (c >= 0xE0 && c <= 0xEF) || // UTF-8 3字节前缀
                (c >= 0xF0 && c <= 0xF7)) { // UTF-8 4字节前缀
                potentialUtf8Sequences++;
                consecutiveInvalidChars = 0;
            } else if (c >= 0x80 && c <= 0xBF) { // UTF-8 后续字节
                if (i > 0) {
                    char prevChar = input.charAt(i - 1);
                    if ((prevChar >= 0xC0 && prevChar <= 0xF7)) {
                        // 这是一个有效的UTF-8后续字节
                        consecutiveInvalidChars = 0;
                    } else {
                        consecutiveInvalidChars++;
                    }
                } else {
                    consecutiveInvalidChars++;
                }
            } else if (c < 0x20 || c == 0x7F) {
                // 控制字符，可能表示编码问题
                consecutiveInvalidChars++;
            } else {
                // 普通ASCII字符
                consecutiveInvalidChars = 0;
            }
            
            // 如果连续出现太多无效字符，可能不是UTF-8
            if (consecutiveInvalidChars > 3) {
                return false;
            }
        }
        
        // 如果有足够多的潜在UTF-8序列，认为可能是UTF-8编码
        return potentialUtf8Sequences > input.length() * 0.1; // 至少10%的字符是潜在的UTF-8序列
    }
    
    /**
     * 检查字符串是否"合理"（即不太可能是乱码）
     * @param str 要检查的字符串
     * @return 如果字符串合理返回true，否则返回false
     */
    private boolean isReasonableString(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        
        // 检查是否包含中文字符
        int chineseCharCount = 0;
        int totalCharCount = str.length();
        
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            // 中文字符的Unicode范围
            if ((c >= 0x4E00 && c <= 0x9FFF) || 
                (c >= 0x3400 && c <= 0x4DBF) ||
                (c >= 0x20000 && c <= 0x2A6DF) ||
                (c >= 0x2A700 && c <= 0x2B73F) ||
                (c >= 0x2B740 && c <= 0x2B81F) ||
                (c >= 0x2B820 && c <= 0x2CEAF) ||
                (c >= 0xF900 && c <= 0xFAFF) ||
                (c >= 0x2F800 && c <= 0x2FA1F)) {
                chineseCharCount++;
            }
        }
        
        // 如果中文字符占比超过30%，认为是合理的中文文本
        double chineseRatio = (double) chineseCharCount / totalCharCount;
        if (chineseRatio > 0.3) {
            Log.i(TAG, "isReasonableString - 中文字符占比: " + chineseRatio + ", 认为是合理的中文文本");
            return true;
        }
        
        // 检查是否包含常见的英文单词字符
        int englishCharCount = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == ' ') {
                englishCharCount++;
            }
        }
        
        // 如果英文字符占比超过70%，认为是合理的英文文本
        double englishRatio = (double) englishCharCount / totalCharCount;
        if (englishRatio > 0.7) {
            Log.i(TAG, "isReasonableString - 英文字符占比: " + englishRatio + ", 认为是合理的英文文本");
            return true;
        }
        
        Log.i(TAG, "isReasonableString - 中文字符占比: " + chineseRatio + ", 英文字符占比: " + englishRatio + ", 认为不是合理的文本");
        return false;
    }

    public @NonNull
    String getTitle() {
        return title;
    }

    public
    boolean hasArtistAndTrack() {
        return ! (artist.isEmpty() || track.isEmpty());
    }

    public @NonNull
    String getArtist() {
        return artist;
    }

    public @NonNull
    String getTrack() {
        return track;
    }

    public Map<String, String> getRawMetadata() {
        return rawMetadata;
    }

    private String title = "";
    private String artist = "";
    private String track = "";
    private Map<String, String> rawMetadata;

    protected StreamLiveInfo(Parcel in) {
        title = in.readString();
        artist = in.readString();
        track = in.readString();
        in.readMap(rawMetadata, String.class.getClassLoader());
    }

    public static final Creator<StreamLiveInfo> CREATOR = new Creator<StreamLiveInfo>() {
        @Override
        public StreamLiveInfo createFromParcel(Parcel in) {
            return new StreamLiveInfo(in);
        }

        @Override
        public StreamLiveInfo[] newArray(int size) {
            return new StreamLiveInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * 智能解析元数据，处理不同格式的电台信息
     * @param title 原始标题字符串
     */
    /**
     * 智能元信息解析，支持多种格式和极端情况
     * 使用简化的解析策略，优先处理常见格式，然后处理特殊格式
     */
    private void parseMetadataIntelligently(String title) {
        Log.i(TAG, "=== parseMetadataIntelligently开始 ===");
        Log.i(TAG, "parseMetadataIntelligently - 原始标题: " + title);
        
        // 默认值
        artist = "";
        track = "";
        
        // 预处理标题
        String originalTitle = title;
        title = preprocessTitle(title);
        Log.i(TAG, "parseMetadataIntelligently - 预处理后标题: " + title);
        
        if (title.isEmpty()) {
            Log.w(TAG, "parseMetadataIntelligently - 预处理后标题为空，返回");
            return;
        }
        
        // 检查是否包含过多问号，如果是则忽略此元数据
        if (isMetadataCorrupted(title)) {
            Log.w(TAG, "parseMetadataIntelligently - 检测到元数据损坏，忽略此元数据");
            return;
        }
        
        boolean parseSuccess = false;
        
        // 简化的解析流程：一次遍历尝试所有格式
        try {
            // 1. 首先尝试字段格式解析（如title=, artist=, text=等）
            if (title.contains("=") && (title.contains("text=") || title.contains("title=") || title.contains("artist="))) {
                parseSuccess = parseFieldFormats(title);
            } 
            // 2. 如果不是字段格式，尝试标准格式解析
            if (!parseSuccess) {
                parseSuccess = parseStandardFormats(title);
            }
            // 3. 如果标准格式解析失败，尝试特殊格式解析
            if (!parseSuccess) {
                parseSuccess = parseSpecialFormats(title);
            }
            // 4. 作为最后手段，尝试智能分割
            if (!parseSuccess) {
                parseAsLastResort(title);
                parseSuccess = true; // 最后手段总是会产生结果
            }
        } catch (Exception e) {
            Log.e(TAG, "parseMetadataIntelligently - 解析过程中出错: " + e.getMessage(), e);
            // 出错时使用安全的默认值
            artist = "Unknown Artist";
            track = "Unknown Track";
        }
        
        // 清理结果
        cleanParsingResults();
        
        Log.i(TAG, "parseMetadataIntelligently - 最终解析结果 - 艺术家: '" + artist + "', 歌曲: '" + track + "'");
        Log.i(TAG, "=== parseMetadataIntelligently结束 ===");
    }
    
    /**
     * 从包含text字段的字符串中提取歌曲名
     * @param input 包含text字段的字符串
     * @return 提取的歌曲名
     */
    private String extractTextValue(String input) {
        // 查找text字段
        int textIndex = input.indexOf("text=");
        if (textIndex == -1) {
            return input;
        }
        
        // 跳过"text="
        int valueStart = textIndex + 5;
        
        // 检查是否有引号
        if (valueStart < input.length() && input.charAt(valueStart) == '"') {
            // 找到匹配的结束引号
            int valueEnd = input.indexOf('"', valueStart + 1);
            if (valueEnd != -1) {
                return input.substring(valueStart + 1, valueEnd);
            }
        }
        
        // 如果没有引号，查找下一个空格或字段结束
        int valueEnd = input.indexOf(' ', valueStart);
        if (valueEnd == -1) {
            valueEnd = input.length();
        }
        
        return input.substring(valueStart, valueEnd);
    }
    
    /**
     * 从包含指定字段的字符串中提取字段值
     * @param input 包含字段的字符串
     * @param fieldName 字段名（如"title"或"artist"）
     * @return 提取的字段值
     */
    private String extractFieldValue(String input, String fieldName) {
        Log.i(TAG, "=== extractFieldValue开始 ===");
        Log.i(TAG, "extractFieldValue - 输入字符串: " + input);
        Log.i(TAG, "extractFieldValue - 字段名: " + fieldName);
        
        // 构建字段模式，如title="
        String fieldPattern = fieldName + "=\"";
        Log.i(TAG, "extractFieldValue - 查找模式: " + fieldPattern);
        
        int fieldIndex = input.indexOf(fieldPattern);
        Log.i(TAG, "extractFieldValue - 字段位置: " + fieldIndex);
        
        if (fieldIndex == -1) {
            Log.i(TAG, "extractFieldValue - 未找到字段，返回空字符串");
            return "";
        }
        
        // 跳过字段名和等号和引号
        int valueStart = fieldIndex + fieldPattern.length();
        Log.i(TAG, "extractFieldValue - 值开始位置: " + valueStart);
        
        // 找到匹配的结束引号
        int valueEnd = input.indexOf('"', valueStart);
        Log.i(TAG, "extractFieldValue - 值结束位置: " + valueEnd);
        
        if (valueEnd != -1) {
            String extractedValue = input.substring(valueStart, valueEnd);
            Log.i(TAG, "extractFieldValue - 提取的值: '" + extractedValue + "'");
            return extractedValue;
        }
        
        // 如果没有找到结束引号，返回空字符串
        Log.i(TAG, "extractFieldValue - 未找到结束引号，返回空字符串");
        return "";
    }
    
    /**
     * 从包含text字段的字符串中提取歌曲名（改进版本）
     * @param input 包含text字段的字符串
     * @return 提取的歌曲名
     */
    private String extractTextValueImproved(String input) {
        // 查找text字段
        int textIndex = input.indexOf("text=");
        if (textIndex == -1) {
            return "";
        }
        
        // 跳过"text="
        int valueStart = textIndex + 5;
        
        // 检查是否有引号
        if (valueStart < input.length() && input.charAt(valueStart) == '"') {
            // 找到匹配的结束引号
            int valueEnd = input.indexOf('"', valueStart + 1);
            if (valueEnd != -1) {
                return input.substring(valueStart + 1, valueEnd);
            }
        }
        
        // 如果没有引号，查找下一个空格或字段结束
        int valueEnd = input.indexOf(' ', valueStart);
        if (valueEnd == -1) {
            valueEnd = input.length();
        }
        
        return input.substring(valueStart, valueEnd);
    }
    
    /**
     * 检查元数据是否损坏
     * @param title 要检查的标题
     * @return 如果元数据损坏返回true，否则返回false
     */
    private boolean isMetadataCorrupted(String title) {
        if (title == null || title.isEmpty()) {
            return false;
        }
        
        // 计算问号字符的数量
        int questionMarkCount = 0;
        for (int i = 0; i < title.length(); i++) {
            if (title.charAt(i) == '?') {
                questionMarkCount++;
            }
        }
        
        // 计算控制字符的数量
        int controlCharCount = 0;
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r') {
                controlCharCount++;
            }
        }
        
        // 计算问号和控制字符的比例
        double questionMarkRatio = (double) questionMarkCount / title.length();
        double controlCharRatio = (double) controlCharCount / title.length();
        
        // 放宽损坏检测标准：
        // 1. 问号比例超过50%才认为损坏（之前是30%）
        // 2. 控制字符比例超过20%才认为损坏（之前是10%）
        // 3. 只有在标题很短（小于10字符）且包含多个问号（大于等于2个）时才认为损坏
        boolean isCorrupted = (questionMarkRatio > 0.5) || 
                             (controlCharRatio > 0.2) || 
                             (questionMarkCount >= 2 && title.length() < 10);
        
        if (BuildConfig.DEBUG && isCorrupted) {
            Log.d(TAG, "isMetadataCorrupted - 检测到损坏的元数据: '" + title + 
                  "', 问号数量: " + questionMarkCount + 
                  ", 控制字符数量: " + controlCharCount +
                  ", 总长度: " + title.length() + 
                  ", 问号比例: " + String.format("%.2f", questionMarkRatio) +
                  ", 控制字符比例: " + String.format("%.2f", controlCharRatio));
        } else if (BuildConfig.DEBUG && questionMarkCount > 0) {
            Log.d(TAG, "isMetadataCorrupted - 元数据包含问号但未达到损坏阈值: '" + title + 
                  "', 问号数量: " + questionMarkCount + 
                  ", 总长度: " + title.length() + 
                  ", 问号比例: " + String.format("%.2f", questionMarkRatio));
        }
        
        return isCorrupted;
    }
    
    /**
     * 预处理标题，移除无效字符和标准化格式
     */
    private String preprocessTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return "";
        }
        
        // 移除控制字符（保留常见的空白字符）
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r' || !Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }
        
        String result = cleaned.toString().trim();
        
        // 处理一些常见的格式问题
        result = result.replaceAll("^\\s*[-=]+\\s*", ""); // 移除开头的-或=
        result = result.replaceAll("\\s*[-=]+\\s*$", ""); // 移除结尾的-或=
        result = result.replaceAll("\\s+", " "); // 标准化空白字符
        
        return result;
    }
    
    /**
     * 解析标准格式和常见变体
     */
    private boolean parseStandardFormats(String title) {
        // 如果包含字段格式，不使用标准格式解析
        if (title.contains("text=") || title.contains("title=") || title.contains("artist=")) {
            Log.i(TAG, "parseStandardFormats - 检测到字段格式，跳过标准格式解析");
            return false;
        }
        
        // 标准格式 "艺术家 - 歌曲名"
        if (title.contains(" - ")) {
            String[] parts = title.split(" - ", 2);
            artist = parts[0].trim();
            track = parts[1].trim();
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "parseStandardFormats - 使用标准格式解析");
            }
            return true;
        }
        
        // 包含"+"分隔符的格式（处理一些特殊格式）
        if (title.contains("+")) {
            return parsePlusSeparatedFormat(title);
        }
        
        return false;
    }
    
    /**
     * 解析包含+分隔符的格式
     */
    private boolean parsePlusSeparatedFormat(String title) {
        // 将所有"+"替换为空格
        String normalizedTitle = title.replace("+", " ");
        
        // 使用"-"作为艺术家和歌曲名的分隔符
        if (normalizedTitle.contains(" - ") || normalizedTitle.contains("-")) {
            String[] parts = normalizedTitle.split(" - ", 2);
            if (parts.length == 1) {
                // 如果用" - "分割失败，尝试用"-"分割
                parts = normalizedTitle.split("-", 2);
            }
            
            if (parts.length >= 2) {
                artist = parts[0].trim();
                track = parts[1].trim();
                
                // 检查并移除重复的艺术家名称
                if (track.startsWith(artist)) {
                    track = track.substring(artist.length()).trim();
                }
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "parsePlusSeparatedFormat - 使用+替换为空格，-作为分隔符解析");
                }
                return true;
            }
        }
        
        // 如果没有"-"分隔符，整个字符串作为歌曲名
        artist = "";
        track = normalizedTitle;
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "parsePlusSeparatedFormat - 使用+替换为空格，但没有-分隔符");
        }
        return true;
    }
    
    /**
     * 解析特殊格式和极端情况
     */
    private boolean parseSpecialFormats(String title) {
        // 处理空艺术家字段的情况：" - 歌曲名"
        if (title.startsWith(" - ")) {
            String remaining = title.substring(3).trim();
            if (!remaining.isEmpty()) {
                artist = "Unknown Artist";
                track = remaining;
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "parseSpecialFormats - 检测到空艺术家字段，艺术家设为: " + artist + ", 歌曲名: " + track);
                }
                return true;
            }
        }
        
        // 处理只有歌曲名的情况
        if (!title.contains(" - ") && !title.contains("=") && !title.contains("+")) {
            artist = "";
            track = title;
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "parseSpecialFormats - 检测到只有歌曲名的情况");
            }
            return true;
        }
        
        // 处理包含多个分隔符的情况
        if (title.split(" - ").length > 2) {
            String[] parts = title.split(" - ", 3);
            if (parts.length >= 3) {
                // 可能是 "艺术家 - 专辑 - 歌曲名" 格式
                artist = parts[0].trim();
                track = parts[2].trim(); // 取最后一部分作为歌曲名
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "parseSpecialFormats - 检测到多分隔符格式，可能是艺术家-专辑-歌曲名");
                }
                return true;
            }
        }
        
        // 处理包含HTML实体或特殊字符的情况
        if (title.contains("&amp;") || title.contains("&quot;") || title.contains("&lt;") || title.contains("&gt;")) {
            return parseWithHtmlEntities(title);
        }
        
        // 处理包含过多空白字符的情况
        if (title.matches(".*\\s{3,}.*")) {
            return parseWithExcessiveWhitespace(title);
        }
        
        // 处理包含URL或链接的情况
        if (title.matches(".*https?://.*") || title.matches(".*www\\..*")) {
            return parseWithUrl(title);
        }
        
        // 处理包含非标准字符的情况
        if (title.matches(".*[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F].*")) {
            return parseWithControlCharacters(title);
        }
        
        return false;
    }
    
    /**
     * 解析字段格式（如text=, title=等）
     */
    private boolean parseFieldFormats(String title) {
        Log.i(TAG, "=== parseFieldFormats开始 ===");
        Log.i(TAG, "parseFieldFormats - 输入标题: " + title);
        Log.i(TAG, "parseFieldFormats - 标题长度: " + title.length());
        
        try {
            // 包含text字段的格式
            if (title.contains("text=")) {
                Log.i(TAG, "parseFieldFormats - 检测到text字段，调用parseTextFieldFormat");
                return parseTextFieldFormat(title);
            }
            
            // 包含title和artist字段的格式
            if (title.contains("title=") && title.contains("artist=")) {
                Log.i(TAG, "parseFieldFormats - 检测到title和artist字段");
                
                String extractedArtist = extractFieldValue(title, "artist");
                String extractedTrack = extractFieldValue(title, "title");
                
                Log.i(TAG, "parseFieldFormats - 提取的artist原始值: '" + extractedArtist + "'");
                Log.i(TAG, "parseFieldFormats - 提取的track原始值: '" + extractedTrack + "'");
                
                artist = extractedArtist;
                track = extractedTrack;
                
                Log.i(TAG, "parseFieldFormats - 最终artist: '" + artist + "'");
                Log.i(TAG, "parseFieldFormats - 最终track: '" + track + "'");
                
                return true;
            }
            
            // 只包含title字段的格式
            if (title.contains("title=") && !title.contains("artist=")) {
                Log.i(TAG, "parseFieldFormats - 检测到只有title字段");
                track = extractFieldValue(title, "title");
                artist = "Unknown Artist";
                return true;
            }
            
            // 只包含artist字段的格式
            if (title.contains("artist=") && !title.contains("title=")) {
                Log.i(TAG, "parseFieldFormats - 检测到只有artist字段");
                artist = extractFieldValue(title, "artist");
                track = "Unknown Track";
                return true;
            }
            
            // 支持以冒号分隔的字段格式（如title:value）
            if (title.matches(".*\\w+:[^=].*")) {
                Log.i(TAG, "parseFieldFormats - 检测到冒号分隔的字段格式");
                return parseColonSeparatedFields(title);
            }
            
            Log.i(TAG, "parseFieldFormats - 未检测到已知的字段格式");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "parseFieldFormats - 解析字段格式时出错: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 解析冒号分隔的字段格式（如title:value）
     */
    private boolean parseColonSeparatedFields(String title) {
        try {
            // 尝试匹配title:value格式
            if (title.contains("title:") && title.contains("artist:")) {
                // 提取artist和title
                String extractedArtist = extractColonFieldValue(title, "artist");
                String extractedTrack = extractColonFieldValue(title, "title");
                
                artist = extractedArtist;
                track = extractedTrack;
                return true;
            } else if (title.contains("title:")) {
                // 只提取title
                track = extractColonFieldValue(title, "title");
                artist = "Unknown Artist";
                return true;
            } else if (title.contains("artist:")) {
                // 只提取artist
                artist = extractColonFieldValue(title, "artist");
                track = "Unknown Track";
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "parseColonSeparatedFields - 解析冒号分隔字段时出错: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 从冒号分隔的字符串中提取字段值
     */
    private String extractColonFieldValue(String input, String fieldName) {
        try {
            String pattern = fieldName + ":";
            int fieldIndex = input.indexOf(pattern);
            if (fieldIndex == -1) {
                return "";
            }
            
            int valueStart = fieldIndex + pattern.length();
            int valueEnd = input.indexOf(" ", valueStart);
            if (valueEnd == -1) {
                valueEnd = input.indexOf(";", valueStart);
            }
            if (valueEnd == -1) {
                valueEnd = input.length();
            }
            
            return input.substring(valueStart, valueEnd).trim();
        } catch (Exception e) {
            Log.e(TAG, "extractColonFieldValue - 提取冒号字段值时出错: " + e.getMessage(), e);
            return "";
        }
    }
    
    /**
     * 解析包含text字段的格式
     */
    private boolean parseTextFieldFormat(String title) {
        Log.i(TAG, "=== parseTextFieldFormat开始 ===");
        Log.i(TAG, "parseTextFieldFormat - 输入标题: " + title);
        
        try {
            // 尝试从text字段前提取艺术家
            int textIndex = title.indexOf("text=");
            Log.i(TAG, "parseTextFieldFormat - text=位置: " + textIndex);
            
            if (textIndex > 0) {
                // 查找text字段前的艺术家名称
                String beforeText = title.substring(0, textIndex).trim();
                Log.i(TAG, "parseTextFieldFormat - text字段前内容: '" + beforeText + "'");
                
                // 处理多种分隔符情况
                String[] separators = {" - ", "-", " | ", "|", ": ", ":"};
                boolean foundSeparator = false;
                
                for (String separator : separators) {
                    if (beforeText.contains(separator)) {
                        int separatorIndex = beforeText.lastIndexOf(separator);
                        artist = beforeText.substring(0, separatorIndex).trim();
                        Log.i(TAG, "parseTextFieldFormat - 使用分隔符 '" + separator + "' 提取艺术家: '" + artist + "'");
                        foundSeparator = true;
                        break;
                    }
                }
                
                if (!foundSeparator) {
                    // 如果没有找到分隔符，直接使用整个前缀作为艺术家
                    artist = beforeText;
                    Log.i(TAG, "parseTextFieldFormat - 直接使用text前内容作为艺术家: '" + artist + "'");
                }
            } else {
                Log.i(TAG, "parseTextFieldFormat - text字段位于开头，无法从前缀提取艺术家");
                artist = "";
            }
            
            // 提取text字段值
            String textValue = extractTextValueImproved(title);
            Log.i(TAG, "parseTextFieldFormat - 提取的text值: '" + textValue + "'");
            
            if (textValue.isEmpty()) {
                // 如果提取失败，尝试使用原始方法
                textValue = extractTextValue(title);
                Log.i(TAG, "parseTextFieldFormat - 原始方法提取的text值: '" + textValue + "'");
            }
            
            track = textValue;
            
            // 处理特殊情况：如果艺术家为空且track不为空
            if ((artist == null || artist.trim().isEmpty()) && !track.isEmpty()) {
                // 检查是否是特殊标识模式
                if (track.matches(".*\\b(ID|LEGAL|SPOT|PROMO|COMMERCIAL|ADVERTISEMENT|BLOCK|INTRO|OUTRO)\\b.*") || 
                    track.matches(".*\\b\\d{2,3}_.*") || // 匹配类似"04_"或"123_"的模式
                    track.matches(".*[A-Z]{3,4}\\s*-\\s*[A-Z]+.*") || // 匹配类似"WEGR - LEGAL"的模式
                    track.matches(".*\\bSPOT\\s+BLOCK\\b.*") || // 匹配"Spot Block"相关模式
                    track.matches(".*\\bBREAK\\b.*") || // 匹配广告插播
                    track.matches(".*\\bSTATION\\b.*") || // 匹配电台标识
                    track.matches(".*\\bBUMPER\\b.*")) { // 匹配过渡片段
                    // 如果是特殊标识模式，不进行分割
                    Log.i(TAG, "parseTextFieldFormat - 检测到特殊标识模式，不进行分割");
                    artist = "Unknown Artist";
                } 
                // 检查track是否包含多个单词
                else if (track.split("\\s+").length > 2) {
                    // 尝试智能分割
                    String[] words = track.split("\\s+", 3);
                    if (words.length >= 3) {
                        artist = words[0] + " " + words[1];
                        track = words[2];
                        Log.i(TAG, "parseTextFieldFormat - 检测到多词track，分割为艺术家: '" + artist + "', 曲目: '" + track + "'");
                    } else if (words.length == 2) {
                        artist = words[0];
                        track = words[1];
                        Log.i(TAG, "parseTextFieldFormat - 检测到两词track，分割为艺术家: '" + artist + "', 曲目: '" + track + "'");
                    }
                } else {
                    // 设置默认艺术家
                    Log.i(TAG, "parseTextFieldFormat - 艺术家为空但track不为空，设置为'Unknown Artist'");
                    artist = "Unknown Artist";
                }
            }
            
            Log.i(TAG, "parseTextFieldFormat - 最终artist: '" + artist + "'");
            Log.i(TAG, "parseTextFieldFormat - 最终track: '" + track + "'");
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "parseTextFieldFormat - 解析text字段格式时出错: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 作为最后手段的智能分割
     */
    private void parseAsLastResort(String title) {
        // 首先尝试使用各种分隔符进行智能分割
        String[] separators = {" - ", "-", " | ", "|", " / ", "/", ":"};
        
        for (String separator : separators) {
            if (title.contains(separator)) {
                String[] parts = title.split(separator, 2);
                if (parts.length >= 2) {
                    artist = parts[0].trim();
                    track = parts[1].trim();
                    
                    // 检查分割后的合理性
                    if (artist.length() > 0 && track.length() > 0 && 
                        artist.length() < 50 && track.length() < 100) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "parseAsLastResort - 使用分隔符 '" + separator + "' 成功分割");
                        }
                        return;
                    }
                }
            }
        }
        
        // 尝试基于大写字母的分割（假设艺术家和歌曲名都以大写字母开头）
        if (title.matches(".*[A-Z].*[A-Z].*")) {
            String[] words = title.split("\\s+");
            // 只有当单词数量足够多时才尝试分割（至少5个单词）
            if (words.length >= 5) {
                // 尝试在中间位置分割
                int midPoint = words.length / 2;
                artist = String.join(" ", java.util.Arrays.copyOfRange(words, 0, midPoint));
                track = String.join(" ", java.util.Arrays.copyOfRange(words, midPoint, words.length));
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "parseAsLastResort - 基于大写字母分割成功");
                }
                return;
            }
        }
        
        // 尝试基于引号的分割（如"Artist" "Song"）
        if (title.matches(".*\".*\".*\".*\".*")) {
            String[] quotedParts = title.split("\"");
            if (quotedParts.length >= 5) {
                artist = quotedParts[1].trim();
                track = quotedParts[3].trim();
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "parseAsLastResort - 基于引号分割成功");
                }
                return;
            }
        }
        
        // 如果所有分割都失败，检查标题长度
        if (title.length() > 60) {
            // 对于长标题，尝试在中间位置分割
            int midPoint = title.length() / 2;
            // 尝试在最近的空格处分割
            int splitPoint = title.lastIndexOf(' ', midPoint);
            if (splitPoint > 0) {
                artist = title.substring(0, splitPoint).trim();
                track = title.substring(splitPoint + 1).trim();
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "parseAsLastResort - 长标题在中间位置分割成功");
                }
                return;
            }
        }
        
        // 如果所有分割都失败，整个标题作为艺术家名
        artist = title;
        track = "";
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "parseAsLastResort - 所有分割失败，整个标题作为艺术家名");
        }
    }
    
    /**
     * 清理解析结果
     */
    private void cleanParsingResults() {
        // 清理艺术家名
        if (artist != null) {
            artist = artist.trim();
            
            // 移除各种引号和特殊字符
            artist = removeQuotes(artist);
            
            // 移除括号内容和元数据标记
            artist = artist.replaceAll("\\s*\\[.*?\\]\\s*", " "); // 移除方括号内容
            artist = artist.replaceAll("\\s*\\((Live|Remix|Edit|Version|Cover|Acoustic|Demo|Instrumental|Explicit|Official|Audio|Extended|Radio|Single|Album|Original|Remastered|Unplugged|Live Session|Studio Version|Alternate Version|Clean|Dirty)\\)\\s*", " "); // 移除常见元数据标记
            artist = artist.replaceAll("\\s*\\(\\d{4}\\)\\s*", " "); // 移除年份
            
            // 移除多余的空白和特殊字符
            artist = artist.replaceAll("\\s+", " ");
            artist = artist.replaceAll("[\\p{Cntrl}]", ""); // 移除控制字符
            
            // 移除常见的无用前缀和后缀
            artist = artist.replaceAll("^(Artist:|artist:|Artist |artist |Performer:|performer:|Performer |performer )", "");
            artist = artist.replaceAll("\\s*[-:–—]\\s*(Live|Remix|Edit|Version|Cover)\\s*$", "");
            artist = artist.replaceAll("[\\s\\r\\n]*[-:–—=+~][\\s\\r\\n]*$", ""); // 移除末尾的分隔符
            
            // 处理全大写或全小写的情况
            if (artist.equals(artist.toUpperCase()) && artist.length() > 3) {
                // 如果全大写且长度大于3，转换为标题格式
                artist = toTitleCase(artist);
            }
            
            // 处理feat.格式（多种变体）
            artist = handleFeatInArtist(artist);
        }
        
        // 清理歌曲名
        if (track != null) {
            track = track.trim();
            
            // 移除各种引号和特殊字符
            track = removeQuotes(track);
            
            // 移除括号内容和元数据标记
            track = track.replaceAll("\\s*\\[.*?\\]\\s*", " "); // 移除方括号内容
            track = track.replaceAll("\\s*\\((Live|Remix|Edit|Version|Cover|Acoustic|Demo|Instrumental|Explicit|Official|Audio|Extended|Radio|Single|Album|Original|Remastered|Unplugged|Live Session|Studio Version|Alternate Version|Clean|Dirty)\\)\\s*", " "); // 移除常见元数据标记
            track = track.replaceAll("\\s*\\(\\d{4}\\)\\s*", " "); // 移除年份
            
            // 移除多余的空白和特殊字符
            track = track.replaceAll("\\s+", " ");
            track = track.replaceAll("[\\p{Cntrl}]", ""); // 移除控制字符
            
            // 移除常见的无用前缀和后缀
            track = track.replaceAll("^(Title:|title:|Track:|track:|Song:|song:|Title |title |Track |track |Song |song |Music:|music:|Music |music )", "");
            track = track.replaceAll("\\s*[-:–—]\\s*(Live|Remix|Edit|Version|Cover|Official|Audio)\\s*$", "");
            track = track.replaceAll("[\\s\\r\\n]*[-:–—=+~][\\s\\r\\n]*$", ""); // 移除末尾的分隔符
            
            // 处理全大写或全小写的情况
            if (track.equals(track.toUpperCase()) && track.length() > 3) {
                // 如果全大写且长度大于3，转换为标题格式
                track = toTitleCase(track);
            }
        }
        
        // 最终修剪
        if (artist != null) {
            artist = artist.trim();
        }
        if (track != null) {
            track = track.trim();
        }
        
        // 处理空值情况
        if (artist == null || artist.isEmpty()) {
            artist = "Unknown Artist";
        }
        
        if (track == null || track.isEmpty()) {
            track = "Unknown Track";
        }
        
        // 处理解析结果过于相似的情况（可能是分割错误）
        if (artist != null && track != null && !artist.isEmpty() && !track.isEmpty()) {
            // 检查艺术家和歌曲名是否过于相似
            double similarity = calculateSimilarity(artist, track);
            if (similarity > 0.8 || artist.equals(track)) {
                // 如果相似度超过80%或完全相同，可能是分割错误
                track = artist;
                artist = "Unknown Artist";
                
                Log.d(TAG, "cleanParsingResults - 检测到艺术家和歌曲名过于相似，调整为Unknown Artist");
            }
        }
        
        // 处理歌曲名包含完整艺术家信息的情况
        if (artist != null && track != null && !artist.isEmpty() && !track.isEmpty() && 
            track.startsWith(artist) && track.length() > artist.length() + 5) {
            // 如果歌曲名以艺术家名开头且长度明显更长，可能是分割错误
            String remaining = track.substring(artist.length()).trim();
            if (remaining.startsWith("-") || remaining.startsWith(":")) {
                remaining = remaining.substring(1).trim();
            }
            if (!remaining.isEmpty()) {
                track = remaining;
                Log.d(TAG, "cleanParsingResults - 检测到歌曲名包含完整艺术家信息，调整歌曲名为: " + track);
            }
        }
    }
    
    /**
     * 移除字符串中的各种引号
     */
    private String removeQuotes(String input) {
        if (input == null) return input;
        
        // 移除双引号、单引号、反引号等
        input = input.replaceAll("^[\"'`]+|[\"'`]+$", "");
        input = input.replaceAll("^[\"'`]+|[\"'`]+$", ""); // 再次执行以处理嵌套引号
        
        return input;
    }
    
    /**
     * 将字符串转换为标题格式（首字母大写，其余小写）
     */
    private String toTitleCase(String input) {
        if (input == null || input.isEmpty()) return input;
        
        StringBuilder result = new StringBuilder();
        boolean nextTitleCase = true;
        
        for (char c : input.toCharArray()) {
            if (Character.isSpaceChar(c)) {
                nextTitleCase = true;
                result.append(c);
            } else if (nextTitleCase) {
                result.append(Character.toTitleCase(c));
                nextTitleCase = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        
        return result.toString();
    }
    
    /**
     * 处理艺术家名中的feat.格式（多种变体）
     */
    private String handleFeatInArtist(String artist) {
        if (artist == null || artist.isEmpty()) return artist;
        
        // 支持多种feat.格式：feat., ft., featuring, with
        String[] featPatterns = {"feat\\.", "ft\\.", "featuring", "with"};
        
        for (String pattern : featPatterns) {
            if (artist.contains(pattern)) {
                String[] parts = artist.split(pattern, 2);
                String mainArtist = parts[0].trim();
                
                if (parts.length > 1) {
                    String featArtists = parts[1].trim();
                    // 将feat.信息添加到歌曲名中
                    if (track != null && !track.isEmpty()) {
                        track += " (feat. " + featArtists + ")";
                    }
                }
                
                Log.d(TAG, "cleanParsingResults - 处理艺术家名包含'" + pattern + "'的情况");
                return mainArtist;
            }
        }
        
        return artist;
    }
    
    /**
     * 计算两个字符串的相似度（基于编辑距离）
     */
    private double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        if (s1.equals(s2)) return 1.0;
        
        int maxLength = Math.max(s1.length(), s2.length());
        if (maxLength == 0) return 1.0;
        
        // 使用简化的编辑距离计算
        int distance = levenshteinDistance(s1, s2);
        return 1.0 - ((double) distance / maxLength);
    }
    
    /**
     * 计算Levenshtein编辑距离
     */
    private int levenshteinDistance(String s1, String s2) {
        int[] costs = new int[s2.length() + 1];
        
        for (int i = 0; i <= s1.length(); i++) {
            int lastValue = i;
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    costs[j] = j;
                } else if (j > 0) {
                    int newValue = costs[j - 1];
                    if (s1.charAt(i - 1) != s2.charAt(j - 1)) {
                        newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1;
                    }
                    costs[j - 1] = lastValue;
                    lastValue = newValue;
                }
            }
            if (i > 0) {
                costs[s2.length()] = lastValue;
            }
        }
        
        return costs[s2.length()];
    }
    
    /**
     * 处理包含多个分隔符的情况
     */
    private boolean parseMultipleSeparators(String title) {
        // 尝试找到最合理的分割点
        String[] parts = title.split("\\s*-\\s*", 3);
        
        if (parts.length >= 3) {
            // 如果有三个部分，可能是"艺术家 - 专辑 - 歌曲"格式
            artist = parts[0].trim();
            track = parts[2].trim(); // 使用第三部分作为歌曲名
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "parseMultipleSeparators - 使用三段式分割: " + artist + " - " + track);
            }
            return true;
        } else if (parts.length >= 2) {
            // 如果只有两个部分，使用标准分割
            artist = parts[0].trim();
            track = parts[1].trim();
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "parseMultipleSeparators - 使用两段式分割: " + artist + " - " + track);
            }
            return true;
        }
        
        return false;
    }
    
    /**
     * 处理包含HTML实体或特殊字符的情况
     */
    private boolean parseWithHtmlEntities(String title) {
        // 解码HTML实体
        String decodedTitle = title
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&apos;", "'");
        
        // 尝试解析解码后的标题
        if (parseStandardFormats(decodedTitle)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "parseWithHtmlEntities - HTML实体解码后成功解析");
            }
            return true;
        }
        
        // 如果解码后仍然无法解析，使用解码后的标题作为歌曲名
        artist = "Unknown Artist";
        track = decodedTitle.trim();
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "parseWithHtmlEntities - HTML实体解码后使用整个标题作为歌曲名");
        }
        return true;
    }
    
    /**
     * 处理包含过多空白字符的情况
     */
    private boolean parseWithExcessiveWhitespace(String title) {
        // 标准化空白字符
        String normalizedTitle = title.replaceAll("\\s{3,}", " - ");
        
        // 尝试解析标准化后的标题
        if (parseStandardFormats(normalizedTitle)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "parseWithExcessiveWhitespace - 标准化空白字符后成功解析");
            }
            return true;
        }
        
        // 如果标准化后仍然无法解析，进一步处理
        normalizedTitle = normalizedTitle.replaceAll("\\s+", " ").trim();
        
        // 尝试再次解析
        if (parseStandardFormats(normalizedTitle)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "parseWithExcessiveWhitespace - 进一步标准化空白字符后成功解析");
            }
            return true;
        }
        
        // 如果仍然无法解析，使用标准化后的标题作为歌曲名
        artist = "Unknown Artist";
        track = normalizedTitle;
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "parseWithExcessiveWhitespace - 标准化空白字符后使用整个标题作为歌曲名");
        }
        return true;
    }
    
    /**
     * 处理包含URL或链接的情况
     */
    private boolean parseWithUrl(String title) {
        // 移除URL和链接
        String withoutUrl = title.replaceAll("https?://[^\\s]+", "");
        withoutUrl = withoutUrl.replaceAll("www\\.[^\\s]+", "");
        
        // 清理多余的空白字符
        withoutUrl = withoutUrl.replaceAll("\\s+", " ").trim();
        
        // 尝试解析移除URL后的标题
        if (parseStandardFormats(withoutUrl)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "parseWithUrl - 移除URL后成功解析");
            }
            return true;
        }
        
        // 如果移除URL后仍然无法解析，使用移除URL后的标题作为歌曲名
        artist = "Unknown Artist";
        track = withoutUrl;
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "parseWithUrl - 移除URL后使用整个标题作为歌曲名");
        }
        return true;
    }
    
    /**
     * 处理包含控制字符的情况
     */
    private boolean parseWithControlCharacters(String title) {
        // 移除控制字符
        String cleanTitle = title.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        
        // 尝试解析清理后的标题
        if (parseStandardFormats(cleanTitle)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "parseWithControlCharacters - 移除控制字符后成功解析");
            }
            return true;
        }
        
        // 如果清理后仍然无法解析，使用清理后的标题作为歌曲名
        artist = "Unknown Artist";
        track = cleanTitle;
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "parseWithControlCharacters - 移除控制字符后使用整个标题作为歌曲名");
        }
        return true;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(title);
        parcel.writeString(artist);
        parcel.writeString(track);
        parcel.writeMap(rawMetadata);
    }
}
