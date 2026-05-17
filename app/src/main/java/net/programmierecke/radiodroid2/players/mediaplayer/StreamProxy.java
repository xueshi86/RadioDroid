package net.programmierecke.radiodroid2.players.mediaplayer;

import android.util.Log;

import androidx.annotation.NonNull;

import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.station.live.ShoutcastInfo;
import net.programmierecke.radiodroid2.station.live.StreamLiveInfo;
import net.programmierecke.radiodroid2.recording.Recordable;
import net.programmierecke.radiodroid2.recording.RecordableListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class StreamProxy implements Recordable {
    private static final String TAG = "PROXY";

    private static final int MAX_RETRIES = 100;

    private OkHttpClient httpClient;
    private StreamProxyListener callback;
    private RecordableListener recordableListener;
    private String uri;
    private byte readBuffer[] = new byte[256 * 16];
    private volatile String localAddress = null;
    private boolean isStopped = false;
    private volatile String streamContentType = null;

    public StreamProxy(OkHttpClient httpClient, String uri, StreamProxyListener callback) {
        this.httpClient = httpClient;
        this.uri = uri;
        this.callback = callback;

        createProxy();
    }

    private void createProxy() {
        if (BuildConfig.DEBUG) Log.d(TAG, "thread started");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    connectToStream();
                    if (BuildConfig.DEBUG) Log.d(TAG, "createProxy() ended");
                } catch (Exception e) {
                    Log.e(TAG, "", e);
                }
            }
        }, "StreamProxy").start();
    }

    private void proxyDefaultStream(ShoutcastInfo info, ResponseBody responseBody, OutputStream outStream) throws Exception {
        int bytesUntilMetaData = 0;
        boolean streamHasMetaData = false;

        if (info != null) {
            callback.onFoundShoutcastStream(info, false);
            bytesUntilMetaData = info.metadataOffset;
            streamHasMetaData = true;
        }

        InputStream inputStream = responseBody.byteStream();

        while (!isStopped) {
            if (!streamHasMetaData || (bytesUntilMetaData > 0)) {
                int bytesToRead = Math.min(readBuffer.length, inputStream.available());
                if (streamHasMetaData) {
                    bytesToRead = Math.min(bytesUntilMetaData, bytesToRead);
                }

                int readBytes = inputStream.read(readBuffer, 0, bytesToRead);
                if (readBytes == 0) {
                    continue;
                }
                if (readBytes < 0) {
                    break;
                }

                if (streamHasMetaData) {
                    bytesUntilMetaData -= readBytes;
                }

                outStream.write(readBuffer, 0, readBytes);

                if (recordableListener != null) {
                    recordableListener.onBytesAvailable(readBuffer, 0, readBytes);
                }

                callback.onBytesRead(readBuffer, 0, readBytes);
            } else {
                readMetaData(inputStream);
                bytesUntilMetaData = info.metadataOffset;
            }
        }

        stopRecording();
    }

    private int readMetaData(InputStream inputStream) throws IOException {
        int metadataSizeByte = inputStream.read();
        if (metadataSizeByte < 0) {
            return 0;
        }
        int metadataBytes = metadataSizeByte * 16;
        int metadataBytesToRead = metadataBytes;
        int readBytesBufferMetadata = 0;
        int readBytes;

        if (BuildConfig.DEBUG) Log.d(TAG, "元数据大小:" + metadataBytes);
        if (metadataBytes > 0) {
            Arrays.fill(readBuffer, (byte) 0);
            while (true) {
                readBytes = inputStream.read(readBuffer, readBytesBufferMetadata, metadataBytesToRead);
                if (readBytes == 0) {
                    continue;
                }
                if (readBytes < 0) {
                    break;
                }
                metadataBytesToRead -= readBytes;
                readBytesBufferMetadata += readBytes;
                if (metadataBytesToRead <= 0) {
                    // 打印原始字节数据（用于调试）
                    if (BuildConfig.DEBUG) {
                        StringBuilder hexString = new StringBuilder();
                        for (int i = 0; i < metadataBytes && i < 100; i++) { // 只打印前100个字节
                            hexString.append(String.format("%02X ", readBuffer[i]));
                        }
                        Log.d(TAG, "StreamProxy原始元数据字节（前100字节）: " + hexString.toString());
                        Log.d(TAG, "StreamProxy元数据长度: " + metadataBytes);
                    }
                    
                    // 尝试使用多种编码方式解析元数据，以支持不同语言的字符集
                    String s = decodeMetadataWithCharsetDetection(readBuffer, metadataBytes);
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "StreamProxy原始元数据字符串: " + s);
                        Log.d(TAG, "StreamProxy元数据字符串长度: " + s.length());
                    }
                    Map<String, String> rawMetadata = decodeShoutcastMetadata(s);
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "StreamProxy解析后的原始元数据: " + rawMetadata.toString());
                        for (Map.Entry<String, String> entry : rawMetadata.entrySet()) {
                            Log.d(TAG, "StreamProxy元数据键值对 - " + entry.getKey() + ": " + entry.getValue());
                        }
                    }
                    StreamLiveInfo streamLiveInfo = new StreamLiveInfo(rawMetadata);
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "StreamLiveInfo标题: " + streamLiveInfo.getTitle());
                        Log.d(TAG, "StreamLiveInfo艺术家: " + streamLiveInfo.getArtist());
                        Log.d(TAG, "StreamLiveInfo完整信息: " + streamLiveInfo.toString());
                    }
                    callback.onFoundLiveStreamInfo(streamLiveInfo);
                    break;
                }
            }
        }
        return readBytesBufferMetadata + 1;
    }

    private void connectToStream() {
        isStopped = false;

        int retry = MAX_RETRIES;

        Socket socketProxy = null;
        OutputStream outputStream = null;
        ServerSocket proxyServer = null;

        try {
            if (BuildConfig.DEBUG) Log.d(TAG, "creating local proxy");

            // Create proxy stream which media player will connect to.

            try {
                proxyServer = new ServerSocket(0, 1, InetAddress.getLocalHost());
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            final int port = proxyServer.getLocalPort();
            localAddress = String.format(Locale.US, "http://localhost:%d", port);

            final Request request = new Request.Builder().url(uri)
                    .addHeader("Icy-MetaData", "1")
                    .build();

            while (!isStopped && retry > 0) {
                ResponseBody responseBody = null;

                try {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "connecting to stream (try=" + retry + "):" + uri);
                    }

                    Response response = httpClient.newCall(request).execute();
                    responseBody = response.body();
                    assert responseBody != null;

                    final MediaType contentType = responseBody.contentType();

                    if (BuildConfig.DEBUG) Log.d(TAG, "waiting...");

                    if (isStopped) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "stopped from the outside");
                        break;
                    }

                    if (socketProxy != null) {
                        socketProxy.close();
                        socketProxy = null;
                    }

                    if (outputStream != null) {
                        outputStream.close();
                        outputStream = null;
                    }

                    callback.onStreamCreated(localAddress);
                    proxyServer.setSoTimeout(2000);
                    socketProxy = proxyServer.accept();

                    // send ok message to local mediaplayer
                    if (BuildConfig.DEBUG) Log.d(TAG, "sending OK to the local media player");
                    outputStream = socketProxy.getOutputStream();
                    outputStream.write(("HTTP/1.0 200 OK\r\n" +
                            "Pragma: no-cache\r\n" +
                            "Content-Type: " + contentType +
                            "\r\n\r\n").getBytes("utf-8"));

                    final String type = contentType.toString().toLowerCase();

                    if (BuildConfig.DEBUG) Log.d(TAG, "Content Type: " + type);

                    streamContentType = type;

                    if (type.equals("application/vnd.apple.mpegurl") || type.equals("application/x-mpegurl")) {
                        Log.e(TAG, "Cannot play HLS streams through proxy!");
                    } else {
                        // try to get shoutcast information from stream connection
                        final ShoutcastInfo info = ShoutcastInfo.Decode(response);

                        proxyDefaultStream(info, responseBody, outputStream);
                    }
                    // reset retry count, if connection was ok
                    retry = MAX_RETRIES;
                } catch (ProtocolException protocolException) {
                    Log.e(TAG, "connecting to stream failed due to protocol exception, will NOT retry.", protocolException);
                    break;
                } catch (SocketTimeoutException ignored) {
                } catch (Exception e) {
                    Log.e(TAG, "exception occurred inside the connection loop, retry.", e);
                } finally {
                    if (responseBody != null) {
                        responseBody.close();
                    }
                }

                if (isStopped) {
                    break;
                }

                retry--;
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted ex Proxy() ", e);
        } finally {
            try {
                if (proxyServer != null) {
                    proxyServer.close();
                }

                if (socketProxy != null) {
                    socketProxy.close();
                }
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "exception occurred while closing resources.", e);
            }
        }

        // inform outside if stream stopped, only if outside did not initiate stop
        if (!isStopped) {
            callback.onStreamStopped();
        }

        stop();
    }

    private Map<String, String> decodeShoutcastMetadata(String metadataStr) {
        Map<String, String> metadata = new HashMap<>();

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "decodeShoutcastMetadata - 输入字符串: " + metadataStr);
            Log.d(TAG, "decodeShoutcastMetadata - 输入字符串长度: " + metadataStr.length());
            
            // 打印每个字符的Unicode值
            StringBuilder charCodes = new StringBuilder();
            for (int i = 0; i < Math.min(metadataStr.length(), 50); i++) {
                char c = metadataStr.charAt(i);
                charCodes.append(String.format("'%c'(%04X) ", c, (int) c));
            }
            Log.d(TAG, "decodeShoutcastMetadata - 前50个字符的Unicode值: " + charCodes.toString());
        }

        String[] kvs = metadataStr.split(";");

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "decodeShoutcastMetadata - 分割后的键值对数量: " + kvs.length);
        }

        for (String kv : kvs) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "decodeShoutcastMetadata - 处理键值对: " + kv);
            }
            
            final int n = kv.indexOf('=');
            if (n < 1) continue;

            final boolean isString = n + 1 < kv.length()
                    && kv.charAt(kv.length() - 1) == '\''
                    && kv.charAt(n + 1) == '\'';

            final String key = kv.substring(0, n);
            final String val = isString ?
                    kv.substring(n + 2, kv.length() - 1) :
                    n + 1 < kv.length() ?
                            kv.substring(n + 1) : "";

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "decodeShoutcastMetadata - 解析键: " + key);
                Log.d(TAG, "decodeShoutcastMetadata - 解析值: " + val);
                Log.d(TAG, "decodeShoutcastMetadata - 值的长度: " + val.length());
                
                // 检查值中是否包含问号
                if (val.contains("?")) {
                    Log.w(TAG, "decodeShoutcastMetadata - 值中包含问号: " + val);
                    
                    // 打印问号字符的详细信息
                    StringBuilder questionMarks = new StringBuilder();
                    for (int i = 0; i < val.length(); i++) {
                        char c = val.charAt(i);
                        if (c == '?') {
                            questionMarks.append(String.format("位置%d ", i));
                        }
                    }
                    Log.w(TAG, "decodeShoutcastMetadata - 问号位置: " + questionMarks.toString());
                }
                
                // 打印值中每个字符的Unicode值
                if (!val.isEmpty()) {
                    StringBuilder valCharCodes = new StringBuilder();
                    for (int i = 0; i < Math.min(val.length(), 30); i++) {
                        char c = val.charAt(i);
                        valCharCodes.append(String.format("'%c'(%04X) ", c, (int) c));
                    }
                    Log.d(TAG, "decodeShoutcastMetadata - 值的前30个字符的Unicode值: " + valCharCodes.toString());
                }
            }

            metadata.put(key, val);
        }

        return metadata;
    }

    /**
     * 尝试使用多种编码方式解析元数据，以支持不同语言的字符集
     * 首先尝试UTF-8，如果失败则尝试其他常见编码
     */
    private String decodeMetadataWithCharsetDetection(byte[] buffer, int length) {
        // 常见的字符编码列表，按优先级排序
        String[] charsets = {
            "UTF-8",        // Unicode，最常用
            "Big5",         // 繁体中文
            "GBK",          // 简体中文
            "GB2312",       // 简体中文
            "ISO-8859-1",   // 西欧
            "windows-1252", // Windows西欧
            "Shift_JIS",    // 日文
            "EUC-JP",       // 日文
            "EUC-KR",       // 韩文
            "ISO-8859-15",  // 西欧，包含欧元符号
            "x-windows-950", // Windows Big5
            "x-windows-936", // Windows GBK
            "ASCII"         // ASCII，作为最后备选
        };

        // 打印原始字节数据（用于调试）
        if (BuildConfig.DEBUG) {
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < length && i < 100; i++) { // 只打印前100个字节
                hexString.append(String.format("%02X ", buffer[i]));
            }
            Log.d(TAG, "原始元数据字节（前100字节）: " + hexString.toString());
            Log.d(TAG, "元数据长度: " + length);
        }

        // 尝试所有编码，并评估解码质量
        String bestResult = new String(buffer, 0, length);
        int bestScore = -1;
        String bestCharset = "ISO-8859-1";
        
        for (String charset : charsets) {
            try {
                String result = new String(buffer, 0, length, charset);
                int score = calculateDecodingQuality(result, charset);
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, charset + "解码结果: " + result);
                    Log.d(TAG, charset + "解码质量分数: " + score);
                }
                
                // 选择质量最高的解码结果
                if (score > bestScore) {
                    bestScore = score;
                    bestResult = result;
                    bestCharset = charset;
                }
            } catch (Exception e) {
                if (BuildConfig.DEBUG) Log.d(TAG, charset + "解码失败: " + e.getMessage());
            }
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "选择最佳编码: " + bestCharset + ", 分数: " + bestScore);
            Log.d(TAG, "最佳解码结果: " + bestResult);
        }
        
        return bestResult;
    }

    /**
     * 计算解码质量分数
     * 分数越高表示解码质量越好
     */
    private int calculateDecodingQuality(String text, String charset) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        
        int score = 0;
        
        // 基础分数：非空文本
        score += 10;
        
        // 检查是否包含替换字符
        if (text.contains("�")) {
            score -= 100; // 严重扣分，几乎可以肯定是错误编码
        }
        
        // 检查是否包含过多的问号字符
        int questionMarkCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '?') {
                questionMarkCount++;
            }
        }
        double questionMarkRatio = (double) questionMarkCount / text.length();
        if (questionMarkRatio > 0.2) {
            score -= 80; // 问号过多，严重扣分
            if (BuildConfig.DEBUG) {
                Log.d(TAG, charset + "解码结果包含过多问号: " + questionMarkCount + "/" + text.length());
            }
        }
        
        // 检查控制字符比例
        int controlCharCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r') {
                controlCharCount++;
            }
        }
        double controlCharRatio = (double) controlCharCount / text.length();
        if (controlCharRatio > 0.1) {
            score -= 50; // 控制字符过多，严重扣分
        }
        
        // 特殊检查：对于UTF-8，检查是否存在无效的UTF-8序列产生的乱码
        if (charset.equals("UTF-8")) {
            // 检查是否存在连续的欧洲字符（可能是Big5被误解析为UTF-8的结果）
            int europeanCharCount = 0;
            int chineseCharCount = 0;
            
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                // 检查是否是欧洲扩展字符（Latin-1补充等）
                if ((c >= 0x80 && c <= 0xFF) || 
                    (c >= 0x100 && c <= 0x17F) || 
                    (c >= 0x180 && c <= 0x24F)) {
                    europeanCharCount++;
                }
                // 检查是否包含中文字符
                if ((c >= 0x4E00 && c <= 0x9FFF) || // CJK统一汉字
                    (c >= 0x3400 && c <= 0x4DBF) || // CJK扩展A
                    (c >= 0xF900 && c <= 0xFAFF)) {  // CJK兼容汉字
                    chineseCharCount++;
                }
            }
            
            // 如果欧洲字符比例过高，可能是Big5被误解析为UTF-8
            double europeanCharRatio = (double) europeanCharCount / text.length();
            if (europeanCharRatio > 0.3) {
                score -= 40; // 很可能是Big5被误解析为UTF-8
            }
            
            // 如果UTF-8解码产生了合理的中文字符，大幅加分
            if (chineseCharCount > 0) {
                score += chineseCharCount * 15; // UTF-8正确解码中文，大幅加分
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "UTF-8解码成功识别中文字符数: " + chineseCharCount);
                }
            }
        }
        
        // 检查是否包含常见的中文字符（适用于中文编码）
        if (charset.equals("Big5") || charset.equals("GBK") || charset.equals("GB2312")) {
            int chineseCharCount = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if ((c >= 0x4E00 && c <= 0x9FFF) || // CJK统一汉字
                    (c >= 0x3400 && c <= 0x4DBF) || // CJK扩展A
                    (c >= 0xF900 && c <= 0xFAFF)) {  // CJK兼容汉字
                    chineseCharCount++;
                }
            }
            if (chineseCharCount > 0) {
                score += chineseCharCount * 10; // 中文字符大幅加分
            }
            
            // 对于Big5，特别检查是否包含常见的Big5字符
            if (charset.equals("Big5")) {
                // 检查是否包含常见的繁体中文字符
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    // 一些常见的繁体中文字符
                    if ((c >= 0x4E00 && c <= 0x9FFF) || // 基本汉字
                        (c >= 0xF900 && c <= 0xFAFF)) {  // 兼容汉字
                        score += 5; // 额外加分
                    }
                }
            }
        }
        
        // 检查是否包含常见的ASCII字符（英文、数字、标点）
        int asciiCharCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 32 && c <= 126) { // 可打印ASCII字符
                asciiCharCount++;
            }
        }
        if (asciiCharCount > 0) {
            score += asciiCharCount; // ASCII字符加分
        }
        
        return score;
    }

    /**
     * 检查解码后的文本是否合理
     * 简单的启发式检查：确保文本中不包含过多的控制字符
     */
    private boolean isValidDecodedText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        // 计算控制字符的比例
        int controlChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.getType(c) == Character.CONTROL) {
                controlChars++;
            }
        }

        // 如果控制字符比例过高，认为解码结果不合理
        return (double) controlChars / text.length() < 0.2;
    }

    public String getLocalAddress() {
        return localAddress;
    }

    public void stop() {
        if (BuildConfig.DEBUG) Log.d(TAG, "stopping proxy.");

        isStopped = true;

        stopRecording();
    }

    @Override
    public boolean canRecord() {
        return true;
    }

    @Override
    public void startRecording(@NonNull RecordableListener recordableListener) {
        this.recordableListener = recordableListener;
    }

    @Override
    public void stopRecording() {
        if (recordableListener != null) {
            recordableListener.onRecordingEnded();
            recordableListener = null;
        }
    }

    @Override
    public boolean isRecording() {
        return recordableListener != null;
    }

    @Override
    public Map<String, String> getRecordNameFormattingArgs() {
        return null;
    }

    @Override
    public String getExtension() {
        if (streamContentType != null) {
            if (streamContentType.contains("aac") || streamContentType.contains("mp4") || streamContentType.contains("m4a")) {
                return "aac";
            }
            if (streamContentType.contains("ogg") || streamContentType.contains("vorbis") || streamContentType.contains("opus")) {
                return "ogg";
            }
            if (streamContentType.contains("mpegurl") || streamContentType.contains("hls")) {
                return "ts";
            }
            if (streamContentType.contains("flac")) {
                return "flac";
            }
            if (streamContentType.contains("wav") || streamContentType.contains("wave")) {
                return "wav";
            }
        }
        return "mp3";
    }
}
