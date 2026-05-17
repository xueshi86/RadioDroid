package net.programmierecke.radiodroid2.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.TypedValue;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.squareup.picasso.Callback;
import com.squareup.picasso.NetworkPolicy;
import com.squareup.picasso.Picasso;

import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.IPlayerService;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.players.PlayState;
import net.programmierecke.radiodroid2.players.selector.PlayerType;
import net.programmierecke.radiodroid2.station.DataRadioStation;
import net.programmierecke.radiodroid2.station.live.ShoutcastInfo;
import net.programmierecke.radiodroid2.station.live.StreamLiveInfo;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class PlayerServiceUtil {

    private static Context mainContext = null;
    private static boolean mBound;
    private static ServiceConnection serviceConnection;

    public static void startService(Context context) {
        if (mBound) return;

        Intent anIntent = new Intent(context, PlayerService.class);
        anIntent.putExtra(PlayerService.PLAYER_SERVICE_NO_NOTIFICATION_EXTRA, true);
        mainContext = context;
        serviceConnection = getServiceConnection();
        context.bindService(anIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        mBound = true;
    }

    public static void bindService(Context context) {
        if (mBound) return;

        mainContext = context;
        serviceConnection = getServiceConnection();
        Intent anIntent = new Intent(context, PlayerService.class);
        context.bindService(anIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        mBound = true;
    }

    private static void unBind(Context context) {
        try {
            context.unbindService(serviceConnection);
        } catch (Exception e) {
        }
        serviceConnection = null;
        mBound = false;
    }

    public static void shutdownService() {
        if (mainContext != null) {
            try {
                if (BuildConfig.DEBUG) {
                    Log.d("PlayerServiceUtil", "PlayerServiceUtil: shutdownService");
                }

                Intent anIntent = new Intent(mainContext, PlayerService.class);
                unBind(mainContext);
                mainContext.stopService(anIntent);
                itsPlayerService = null;
                serviceConnection = null;
            } catch (Exception e) {
                if (BuildConfig.DEBUG) {
                    Log.d("PlayerServiceUtil", "PlayerServiceUtil: shutdownService E001:" + e.getMessage());
                }
            }
        }
    }

    private static IPlayerService itsPlayerService;

    private static ServiceConnection getServiceConnection() {
        return new ServiceConnection() {
            public void onServiceConnected(ComponentName className, IBinder binder) {
                if (BuildConfig.DEBUG) {
                    Log.d("PLAYER", "Service came online");
                }
                itsPlayerService = IPlayerService.Stub.asInterface(binder);

                Intent local = new Intent();
                local.setAction(PlayerService.PLAYER_SERVICE_BOUND);
                LocalBroadcastManager.getInstance(mainContext).sendBroadcast(local);
            }

            public void onServiceDisconnected(ComponentName className) {
                if (BuildConfig.DEBUG) {
                    Log.d("PLAYER", "Service offline");
                }
                unBind(mainContext);
                itsPlayerService = null;
            }
        };
    }

    public static boolean isServiceBound() {
        return itsPlayerService != null;
    }

    public static boolean isPlaying() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.isPlaying();
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    public static PlayState getPlayerState() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getPlayerState();
            } catch (RemoteException e) {
            }
        }
        return PlayState.Idle;
    }

    public static void stop() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.Stop();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void play(DataRadioStation station) {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.SetStation(station);
                itsPlayerService.Play(false);
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void setStation(DataRadioStation station) {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.SetStation(station);
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void skipToNext() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.SkipToNext();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void skipToPrevious() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.SkipToPrevious();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void pause(PauseReason pauseReason) {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.Pause(pauseReason);
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void resume() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.Resume();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void clearTimer() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.clearTimer();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void addTimer(int secondsAdd) {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.addTimer(secondsAdd);
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static long getTimerSeconds() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getTimerSeconds();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return 0;
    }

    public static @NonNull
    StreamLiveInfo getMetadataLive() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getMetadataLive();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return new StreamLiveInfo(null);
    }

    public static String getStationId() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getCurrentStationID();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return null;
    }

    public static DataRadioStation getCurrentStation() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getCurrentStation();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return null;
    }

    public static void getStationIcon(final ImageView holder, final String fromUrl) {
        getStationIcon(holder, fromUrl, null);
    }

    public static void getStationIcon(final ImageView holder, final String iconUrl, final String homePageUrl) {
        Resources r = mainContext.getResources();
        final float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 70, r.getDisplayMetrics());
        final Drawable placeholder = AppCompatResources.getDrawable(holder.getContext(), R.mipmap.ic_launcher);

        List<String> urlsToTry = new ArrayList<>();

        if (iconUrl != null && !iconUrl.trim().isEmpty()) {
            urlsToTry.add(iconUrl);
        }

        if (homePageUrl != null && !homePageUrl.trim().isEmpty()) {
            urlsToTry.addAll(buildFallbackUrls(homePageUrl));
        }

        if (urlsToTry.isEmpty()) {
            holder.setImageDrawable(placeholder);
            return;
        }

        tryLoadIconFromList(holder, urlsToTry, 0, placeholder, (int) px);
    }

    private static final int MAX_RETRY_COUNT = 3;
    private static final long[] RETRY_DELAYS_MS = {1000, 3000, 5000};
    private static final Set<String> failedFallbackDomains = new HashSet<>();

    private static List<String> buildFallbackUrls(String homePageUrl) {
        List<String> fallbacks = new ArrayList<>();
        try {
            URI uri = new URI(homePageUrl);
            String domain = uri.getHost();
            if (domain == null || domain.isEmpty()) {
                return fallbacks;
            }
            String scheme = uri.getScheme() != null ? uri.getScheme() : "https";

            fallbacks.add(scheme + "://" + domain + "/favicon.ico");
            fallbacks.add(scheme + "://" + domain + "/apple-touch-icon.png");
            fallbacks.add("https://www.google.com/s2/favicons?domain=" + domain + "&sz=128");
        } catch (Exception e) {
            Log.w("PlayerServiceUtil", "Failed to build fallback URLs from: " + homePageUrl, e);
        }
        return fallbacks;
    }

    private static void tryLoadIconFromList(final ImageView holder, final List<String> urls,
                                             final int index, final Drawable placeholder,
                                             final int pxSize) {
        if (index >= urls.size()) {
            holder.setImageDrawable(placeholder);
            return;
        }

        final String url = urls.get(index);

        Picasso.get()
                .load(url)
                .placeholder(placeholder)
                .resize(pxSize, 0)
                .networkPolicy(index == 0 ? NetworkPolicy.OFFLINE : NetworkPolicy.NO_CACHE)
                .into(holder, new Callback() {
                    @Override
                    public void onSuccess() {
                    }

                    @Override
                    public void onError(Exception e) {
                        if (index >= 1) {
                            Log.d("PlayerServiceUtil", "Fallback URL " + index + " failed: " + url);
                        }
                        tryLoadIconFromList(holder, urls, index + 1, placeholder, pxSize);
                    }
                });
    }

    private static void loadIconWithRetry(final ImageView holder, final String fromUrl,
                                           final Drawable placeholder, final int pxSize,
                                           final int retryCount) {
        if (retryCount == 0) {
            Picasso.get()
                    .load(fromUrl)
                    .placeholder(placeholder)
                    .resize(pxSize, 0)
                    .networkPolicy(NetworkPolicy.OFFLINE)
                    .into(holder, new Callback() {
                        @Override
                        public void onSuccess() {
                        }

                        @Override
                        public void onError(Exception e) {
                            loadIconWithRetry(holder, fromUrl, placeholder, pxSize, 1);
                        }
                    });
        } else if (retryCount <= MAX_RETRY_COUNT) {
            long delay = RETRY_DELAYS_MS[Math.min(retryCount - 1, RETRY_DELAYS_MS.length - 1)];
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    Picasso.get()
                            .load(fromUrl)
                            .placeholder(placeholder)
                            .resize(pxSize, 0)
                            .networkPolicy(NetworkPolicy.NO_CACHE)
                            .into(holder, new Callback() {
                                @Override
                                public void onSuccess() {
                                }

                                @Override
                                public void onError(Exception e) {
                                    if (retryCount < MAX_RETRY_COUNT) {
                                        loadIconWithRetry(holder, fromUrl, placeholder, pxSize, retryCount + 1);
                                    } else {
                                        Drawable appIcon = AppCompatResources.getDrawable(holder.getContext(), R.mipmap.ic_launcher);
                                        holder.setImageDrawable(appIcon);
                                    }
                                }
                            });
                }
            }, delay);
        }
    }

    public static ShoutcastInfo getShoutcastInfo() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getShoutcastInfo();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return null;
    }

    public static void startRecording() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.startRecording();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void stopRecording() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.stopRecording();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static boolean isRecording() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.isRecording();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return false;
    }

    public static String getCurrentRecordFileName() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getCurrentRecordFileName();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return null;
    }

    public static boolean getIsHls() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getIsHls();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return false;
    }

    public static long getTransferredBytes() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getTransferredBytes();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return 0;
    }

    public static long getBufferedSeconds() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getBufferedSeconds();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return 0;
    }

    public static long getLastPlayStartTime() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getLastPlayStartTime();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return 0;
    }

    public static PauseReason getPauseReason() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getPauseReason();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return PauseReason.NONE;
    }

    public static void enableMPD(String hostname, int port) {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.enableMPD(hostname, port);
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public void disableMPD() {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.disableMPD();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static void warnAboutMeteredConnection(PlayerType playerType) {
        if (itsPlayerService != null) {
            try {
                itsPlayerService.warnAboutMeteredConnection(playerType);
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
    }

    public static boolean isNotificationActive() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.isNotificationActive();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return false;
    }

    public static int getAudioSessionId() {
        if (itsPlayerService != null) {
            try {
                return itsPlayerService.getAudioSessionId();
            } catch (RemoteException e) {
                Log.e("", "" + e);
            }
        }
        return 0;
    }
}
