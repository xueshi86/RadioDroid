package net.programmierecke.radiodroid2.players.exoplayer;


import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;

import net.programmierecke.radiodroid2.station.live.ShoutcastInfo;
import net.programmierecke.radiodroid2.station.live.StreamLiveInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static net.programmierecke.radiodroid2.Utils.getMimeType;
import static okhttp3.internal.Util.closeQuietly;

/**
 * An {@link HttpDataSource} that uses {@link OkHttpClient},
 * retrieves stream's {@link ShoutcastInfo} and {@link StreamLiveInfo} if any,
 * attempts to reconnect if connection is lost. These distinguishes it from {@link DefaultHttpDataSource}.
 * <p>
 * When connection is lost attempts to reconnect will made alongside with calling
 * {@link IcyDataSourceListener#onDataSourceConnectionLost()}.
 * After reconnecting time has passed
 * {@link IcyDataSourceListener#onDataSourceConnectionLostIrrecoverably()} will be called.
 **/
public class IcyDataSource implements HttpDataSource {

    public static final long DEFAULT_TIME_UNTIL_STOP_RECONNECTING = 2 * 60 * 1000; // 2 minutes

    public static final long DEFAULT_DELAY_BETWEEN_RECONNECTIONS = 0;

    public interface IcyDataSourceListener {
        /**
         * Called on first connection and after successful reconnection.
         */
        void onDataSourceConnected();

        /**
         * Called when connection is lost and reconnection attempts will be made.
         */
        void onDataSourceConnectionLost();

        /**
         * Called when data source gives up reconnecting.
         */
        void onDataSourceConnectionLostIrrecoverably();

        void onDataSourceShoutcastInfo(@Nullable ShoutcastInfo shoutcastInfo);

        void onDataSourceStreamLiveInfo(StreamLiveInfo streamLiveInfo);

        void onDataSourceBytesRead(byte[] buffer, int offset, int length);

        void onDataSourceContentType(String contentType);
    }

    private static final String TAG = "IcyDataSource";

    private DataSpec dataSpec;

    private final OkHttpClient httpClient;
    private final TransferListener transferListener;
    private final IcyDataSourceListener dataSourceListener;

    private Request request;

    private ResponseBody responseBody;
    private Map<String, List<String>> responseHeaders;

    int metadataBytesToSkip = 0;
    int remainingUntilMetadata = Integer.MAX_VALUE;
    private boolean opened;

    ShoutcastInfo shoutcastInfo;
    private StreamLiveInfo streamLiveInfo;

    public IcyDataSource(@NonNull OkHttpClient httpClient,
                         @NonNull TransferListener listener,
                         @NonNull IcyDataSourceListener dataSourceListener) {
        this.httpClient = httpClient;
        this.transferListener = listener;
        this.dataSourceListener = dataSourceListener;
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSourceException {
        close();

        this.dataSpec = dataSpec;

        final boolean allowGzip = (dataSpec.flags & DataSpec.FLAG_ALLOW_GZIP) != 0;

        HttpUrl url = HttpUrl.parse(dataSpec.uri.toString());
        Request.Builder builder = new Request.Builder().url(url)
                .addHeader("Icy-MetaData", "1");

        if (!allowGzip) {
            builder.addHeader("Accept-Encoding", "identity");
        }

        request = builder.build();

        return connect();
    }

    private long connect() throws HttpDataSourceException {
        Response response;
        try {
            response = httpClient.newCall(request).execute();
        } catch (IOException e) {
            throw new HttpDataSourceException("Unable to connect to " + dataSpec.uri.toString(), e,
                    dataSpec, HttpDataSourceException.TYPE_OPEN);
        }

        final int responseCode = response.code();

        if (!response.isSuccessful()) {
            final Map<String, List<String>> headers = request.headers().toMultimap();
            throw new InvalidResponseCodeException(responseCode, headers, dataSpec);
        }

        responseBody = response.body();
        assert responseBody != null;

        responseHeaders = response.headers().toMultimap();

        final MediaType contentType = responseBody.contentType();

        final String type = contentType == null ? getMimeType(dataSpec.uri.toString(), "audio/mpeg") : contentType.toString().toLowerCase();

        if (dataSourceListener != null) {
            dataSourceListener.onDataSourceContentType(type);
        }

        if (!REJECT_PAYWALL_TYPES.apply(type)) {
            close();
            throw new InvalidContentTypeException(type, dataSpec);
        }

        opened = true;

        dataSourceListener.onDataSourceConnected();
        transferListener.onTransferStart(this, dataSpec, true);

        if (type.equals("application/vnd.apple.mpegurl") || type.equals("application/x-mpegurl")) {
            return responseBody.contentLength();
        } else {
            // try to get shoutcast information from stream connection
            shoutcastInfo = ShoutcastInfo.Decode(response);
            dataSourceListener.onDataSourceShoutcastInfo(shoutcastInfo);

            metadataBytesToSkip = 0;
            if (shoutcastInfo != null) {
                remainingUntilMetadata = shoutcastInfo.metadataOffset;
            } else {
                remainingUntilMetadata = Integer.MAX_VALUE;
            }

            return responseBody.contentLength();
        }
    }

    @Override
    public void close() throws HttpDataSourceException {
        if (opened) {
            opened = false;
            transferListener.onTransferEnd(this, dataSpec, true);
        }

        if (responseBody != null) {
            closeQuietly(responseBody);
            responseBody = null;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws HttpDataSourceException {
        try {
            final int bytesTransferred = readInternal(buffer, offset, readLength);
            transferListener.onBytesTransferred(this, dataSpec, true, bytesTransferred);
            return bytesTransferred;
        } catch (HttpDataSourceException readError) {
            dataSourceListener.onDataSourceConnectionLost();
            throw readError;
        }
    }

    void sendToDataSourceListenersWithoutMetadata(byte[] buffer, int offset, int bytesAvailable) {
        int canSkip = Math.min(metadataBytesToSkip, bytesAvailable);
        offset += canSkip;
        bytesAvailable -= canSkip;
        remainingUntilMetadata -= canSkip;
        while (bytesAvailable > 0) {
            if (bytesAvailable > remainingUntilMetadata) {
                if (remainingUntilMetadata > 0) {
                    dataSourceListener.onDataSourceBytesRead(buffer, offset, remainingUntilMetadata);
                    offset += remainingUntilMetadata;
                    bytesAvailable -= remainingUntilMetadata;
                }
                
                int metadataSizeByte = buffer[offset] & 0xFF;
                metadataBytesToSkip = metadataSizeByte * 16 + 1;
                
                if (metadataSizeByte > 0) {
                    if (bytesAvailable >= metadataBytesToSkip) {
                        byte[] metadataBytes = new byte[metadataSizeByte * 16];
                        System.arraycopy(buffer, offset + 1, metadataBytes, 0, metadataSizeByte * 16);
                        processMetadataBlock(metadataBytes);
                    } else {
                        Log.w(TAG, "Incomplete metadata block, need " + metadataBytesToSkip + " bytes, but only " + bytesAvailable + " available");
                        if (bytesAvailable > 1) {
                            int availableDataSize = bytesAvailable - 1;
                            byte[] partialMetadataBytes = new byte[availableDataSize];
                            System.arraycopy(buffer, offset + 1, partialMetadataBytes, 0, availableDataSize);
                            processMetadataBlock(partialMetadataBytes);
                        }
                    }
                }
                
                remainingUntilMetadata = shoutcastInfo.metadataOffset;
            }

            int bytesLeft = Math.min(bytesAvailable, remainingUntilMetadata);
            int metadataInLeft = Math.min(bytesLeft, metadataBytesToSkip);
            int audioInLeft = bytesLeft - metadataInLeft;
            if (audioInLeft > 0) {
                dataSourceListener.onDataSourceBytesRead(buffer, offset + metadataInLeft, audioInLeft);
            }
            metadataBytesToSkip -= metadataInLeft;
            offset += bytesLeft;
            bytesAvailable -= bytesLeft;
            remainingUntilMetadata -= audioInLeft;
        }
    }

    private void processMetadataBlock(byte[] metadataBytes) {
        if (metadataBytes == null || metadataBytes.length == 0) {
            return;
        }

        int actualLength = metadataBytes.length;
        while (actualLength > 0 && metadataBytes[actualLength - 1] == 0) {
            actualLength--;
        }

        if (actualLength == 0) {
            return;
        }

        boolean isMetadataValid = false;
        for (int i = 0; i < actualLength - 10; i++) {
            if (metadataBytes[i] == 'S' && metadataBytes[i+1] == 't' && metadataBytes[i+2] == 'r' &&
                metadataBytes[i+3] == 'e' && metadataBytes[i+4] == 'a' && metadataBytes[i+5] == 'm' &&
                metadataBytes[i+6] == 'T' && metadataBytes[i+7] == 'i' && metadataBytes[i+8] == 't' &&
                metadataBytes[i+9] == 'l' && metadataBytes[i+10] == 'e') {
                isMetadataValid = true;
                break;
            }
        }

        if (!isMetadataValid) {
            return;
        }

        String metadataString = null;
        String[] encodings = {"ISO-8859-1", "UTF-8", "GBK", "GB2312", "Big5"};

        for (String encoding : encodings) {
            try {
                metadataString = new String(metadataBytes, 0, actualLength, encoding);
                if (metadataString.contains("StreamTitle") && metadataString.length() > 10) {
                    break;
                }
            } catch (Exception ignored) {
            }
        }

        if (metadataString == null) {
            return;
        }

        Map<String, String> metadataMap = parseMetadataString(metadataString);

        if (metadataMap.containsKey("StreamTitle")) {
            String streamTitle = metadataMap.get("StreamTitle");
            if (streamTitle != null && !streamTitle.trim().isEmpty()) {
                StreamLiveInfo streamLiveInfo = new StreamLiveInfo(metadataMap);
                dataSourceListener.onDataSourceStreamLiveInfo(streamLiveInfo);
            }
        }
    }
    
    private Map<String, String> parseMetadataString(String metadataString) {
        Map<String, String> metadataMap = new java.util.HashMap<>();

        if (metadataString == null || metadataString.isEmpty()) {
            return metadataMap;
        }

        String[] pairs = metadataString.split(";");

        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }

            int equalsIndex = pair.indexOf('=');
            if (equalsIndex == -1) {
                continue;
            }

            String key = pair.substring(0, equalsIndex).trim();
            String value = pair.substring(equalsIndex + 1).trim();

            if (value.length() >= 2 &&
                ((value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') ||
                 (value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'))) {
                value = value.substring(1, value.length() - 1);
            }

            metadataMap.put(key, value);
        }

        return metadataMap;
    }

    private int readInternal(byte[] buffer, int offset, int readLength) throws HttpDataSourceException {
        if (responseBody == null) {
            throw new HttpDataSourceException(dataSpec, HttpDataSourceException.TYPE_READ);
        }

        InputStream stream = responseBody.byteStream();

        int bytesRead = 0;
        try {
            bytesRead = stream.read(buffer, offset, readLength);
        } catch (IOException e) {
            throw new HttpDataSourceException(e, dataSpec, HttpDataSourceException.TYPE_READ);
        }

        sendToDataSourceListenersWithoutMetadata(buffer, offset, bytesRead);

        return bytesRead;
    }

    @Override
    public Uri getUri() {
        return dataSpec.uri;
    }

    @Override
    public void setRequestProperty(String name, String value) {
        // Ignored
    }

    @Override
    public void clearRequestProperty(String name) {
        // Ignored
    }

    @Override
    public void clearAllRequestProperties() {
        // Ignored
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public int getResponseCode() {
        return 0;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {

    }
}
