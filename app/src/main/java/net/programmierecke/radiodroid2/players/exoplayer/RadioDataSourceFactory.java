package net.programmierecke.radiodroid2.players.exoplayer;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.TransferListener;

import net.programmierecke.radiodroid2.BuildConfig;

import okhttp3.OkHttpClient;

public class RadioDataSourceFactory implements DataSource.Factory {

    private static final String TAG = "RadioDataSourceFactory";

    private OkHttpClient httpClient;
    private final TransferListener transferListener;
    private IcyDataSource.IcyDataSourceListener dataSourceListener;
    private long retryTimeout;
    private long retryDelay;
    private int createCount;

    public RadioDataSourceFactory(@NonNull OkHttpClient httpClient,
                                  @NonNull TransferListener transferListener,
                                  @NonNull IcyDataSource.IcyDataSourceListener dataSourceListener,
                                  long retryTimeout,
                                  long retryDelay) {
        this.httpClient = httpClient;
        this.transferListener = transferListener;
        this.dataSourceListener = dataSourceListener;
        this.retryTimeout = retryTimeout;
        this.retryDelay = retryDelay;
    }

    @Override
    public DataSource createDataSource() {
        createCount++;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "createDataSource() called, count=" + createCount);
        }
        return new IcyDataSource(httpClient, transferListener, dataSourceListener);
    }
}
