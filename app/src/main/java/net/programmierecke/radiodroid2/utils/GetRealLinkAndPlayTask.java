package net.programmierecke.radiodroid2.utils;

import android.content.Context;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.util.Log;

import net.programmierecke.radiodroid2.IPlayerService;
import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.lang.ref.WeakReference;

public class GetRealLinkAndPlayTask extends AsyncTask<Void, Void, Void> {
    private static final String TAG = "GetRealLinkAndPlayTask";
    
    private WeakReference<Context> contextRef;
    private DataRadioStation station;
    private WeakReference<IPlayerService> playerServiceRef;

    public GetRealLinkAndPlayTask(Context context, DataRadioStation station, IPlayerService playerService) {
        this.contextRef = new WeakReference<>(context);
        this.station = station;
        this.playerServiceRef = new WeakReference<>(playerService);
    }

    @Override
    protected Void doInBackground(Void... params) {
        // 不再进行网络查询，直接使用本地存储的电台URL
        return null;
    }

    @Override
    protected void onPostExecute(Void result) {
        IPlayerService playerService = playerServiceRef.get();
        if (playerService != null && !isCancelled()) {
            try {
                // 直接使用本地存储的电台URL，不再获取"真实"链接
                if (station.StreamUrl != null && !station.StreamUrl.isEmpty()) {
                    station.playableUrl = station.StreamUrl;
                    playerService.SetStation(station);
                    playerService.Play(false);
                } else {
                    Log.w(TAG, "Station StreamUrl is null or empty: " + station.StationUuid);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error playing station", e);
            }
        }
        super.onPostExecute(result);
    }
}
