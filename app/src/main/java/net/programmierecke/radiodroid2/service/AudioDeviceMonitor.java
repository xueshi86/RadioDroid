package net.programmierecke.radiodroid2.service;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

import androidx.preference.PreferenceManager;

import net.programmierecke.radiodroid2.ActivityMain;
import net.programmierecke.radiodroid2.HistoryManager;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.players.selector.PlayerType;
import net.programmierecke.radiodroid2.station.DataRadioStation;

public class AudioDeviceMonitor {
    private static final String TAG = "AudioDeviceMonitor";

    private static final String PREF_BT_DISCONNECT_HANDLED = "bluetooth_disconnect_handled";
    private static final String PREF_HEADSET_WAS_CONNECTED = "headset_was_connected_before_disconnect";
    private static final String PREF_WIRED_DISCONNECT_HANDLED = "wired_disconnect_handled";

    private final Context context;
    private final AudioManager audioManager;
    private MediaRouter mediaRouter;
    private volatile long lastBluetoothDisconnectTime = 0;
    private static final long DEBOUNCE_MS = 2000;

    private boolean lastBtConnected = false;
    private boolean lastWiredConnected = false;

    private Object audioDeviceCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final MediaRouter.Callback mediaRouterCallback = new MediaRouter.Callback() {
        @Override
        public void onRouteSelected(MediaRouter router, MediaRouter.RouteInfo route) {
            Log.d(TAG, "Route SELECTED: name=" + route.getName() + " type=" + route.getDeviceType());
            checkDeviceChanges();
        }

        @Override
        public void onRouteUnselected(MediaRouter router, MediaRouter.RouteInfo route) {
            Log.d(TAG, "Route UNSELECTED: name=" + route.getName() + " type=" + route.getDeviceType());
            checkDeviceChanges();
        }

        @Override
        public void onRouteChanged(MediaRouter router, MediaRouter.RouteInfo route) {
            Log.d(TAG, "Route CHANGED: name=" + route.getName() + " enabled=" + route.isEnabled());
            checkDeviceChanges();
        }

        @Override
        public void onRouteAdded(MediaRouter router, MediaRouter.RouteInfo route) {
            Log.d(TAG, "Route ADDED: name=" + route.getName());
            checkDeviceChanges();
        }

        @Override
        public void onRouteRemoved(MediaRouter router, MediaRouter.RouteInfo route) {
            Log.d(TAG, "Route REMOVED: name=" + route.getName());
            checkDeviceChanges();
        }
    };

    public AudioDeviceMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void register() {
        snapshotCurrentState();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null) {
            audioDeviceCallback = new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    Log.d(TAG, "AudioDeviceCallback: " + addedDevices.length + " devices ADDED");
                    for (AudioDeviceInfo dev : addedDevices) {
                        Log.d(TAG, "  added: type=" + dev.getType() + "(" + audioDeviceTypeToString(dev.getType()) + ")");
                    }
                    handler.post(() -> checkDeviceChanges());
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    Log.d(TAG, "AudioDeviceCallback: " + removedDevices.length + " devices REMOVED");
                    for (AudioDeviceInfo dev : removedDevices) {
                        Log.d(TAG, "  removed: type=" + dev.getType() + "(" + audioDeviceTypeToString(dev.getType()) + ")");
                    }
                    handler.post(() -> checkDeviceChanges());
                }
            };
            audioManager.registerAudioDeviceCallback((AudioDeviceCallback) audioDeviceCallback, handler);
            Log.d(TAG, "Registered AudioDeviceCallback");
        }

        mediaRouter = MediaRouter.getInstance(context);
        mediaRouter.addCallback(MediaRouteSelector.EMPTY, mediaRouterCallback);

        Log.d(TAG, "Registered. Initial BT=" + lastBtConnected + ", Wired=" + lastWiredConnected);
    }

    public void unregister() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null && audioDeviceCallback != null) {
            audioManager.unregisterAudioDeviceCallback((AudioDeviceCallback) audioDeviceCallback);
            audioDeviceCallback = null;
            Log.d(TAG, "Unregistered AudioDeviceCallback");
        }
        if (mediaRouter != null) {
            mediaRouter.removeCallback(mediaRouterCallback);
            mediaRouter = null;
            Log.d(TAG, "Unregistered MediaRouter callback");
        }
    }

    private void snapshotCurrentState() {
        lastBtConnected = isBluetoothAudioConnected();
        lastWiredConnected = isWiredHeadsetConnected();
    }

    private void checkDeviceChanges() {
        boolean btNow = isBluetoothAudioConnected();
        boolean wiredNow = isWiredHeadsetConnected();

        Log.d(TAG, "checkDeviceChanges: BT " + lastBtConnected + "->" + btNow
                + ", Wired " + lastWiredConnected + "->" + wiredNow);

        if (lastBtConnected && !btNow) {
            Log.d(TAG, "Bluetooth DISCONNECTED detected");
            handleBluetoothDisconnect();
        } else if (!lastBtConnected && btNow) {
            Log.d(TAG, "Bluetooth CONNECTED detected");
            handleBluetoothConnect();
        }

        if (lastWiredConnected && !wiredNow) {
            Log.d(TAG, "Wired headset DISCONNECTED detected");
            handleWiredHeadsetDisconnect();
        } else if (!lastWiredConnected && wiredNow) {
            Log.d(TAG, "Wired headset CONNECTED detected");
            handleWiredHeadsetConnect();
        }

        lastBtConnected = btNow;
        lastWiredConnected = wiredNow;
    }

    private boolean isBluetoothAudioConnected() {
        if (audioManager == null) {
            Log.w(TAG, "isBluetoothAudioConnected: audioManager is null!");
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                boolean foundBtViaDeviceInfo = false;
                for (AudioDeviceInfo device : devices) {
                    int type = device.getType();
                    if (isBluetoothDeviceType(type)) {
                        Log.d(TAG, "isBluetoothAudioConnected: found BT device type=" + type + "(" + audioDeviceTypeToString(type) + ")");
                        foundBtViaDeviceInfo = true;
                        break;
                    }
                }

                if (foundBtViaDeviceInfo) {
                    return true;
                }

                boolean hasBtPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                        || context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;

                if (hasBtPermission) {
                    Log.d(TAG, "isBluetoothAudioConnected: no BT in AudioDeviceInfo (has permission), returning false");
                    return false;
                }

                boolean mediaRouterBt = isBluetoothViaMediaRouter();
                Log.d(TAG, "isBluetoothAudioConnected: no BT permission, MediaRouter fallback=" + mediaRouterBt);
                return mediaRouterBt;
            } else {
                boolean a2dp = audioManager.isBluetoothA2dpOn();
                boolean sco = audioManager.isBluetoothScoOn();
                Log.d(TAG, "isBluetoothAudioConnected (legacy): a2dp=" + a2dp + " sco=" + sco);
                return a2dp || sco;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking bluetooth audio", e);
            return false;
        }
    }

    private boolean isBluetoothDeviceType(int type) {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID) {
            return true;
        }
        return false;
    }

    private boolean isBluetoothViaMediaRouter() {
        if (mediaRouter == null) return false;
        try {
            MediaRouter.RouteInfo selectedRoute = mediaRouter.getSelectedRoute();
            if (selectedRoute != null) {
                int deviceType = selectedRoute.getDeviceType();
                if (deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH) {
                    return true;
                }
                String routeName = selectedRoute.getName() != null ? selectedRoute.getName().toString().toLowerCase() : "";
                if (routeName.contains("bluetooth") || routeName.contains("a2dp")) {
                    return true;
                }
            }

            java.util.List<MediaRouter.RouteInfo> routes = mediaRouter.getRoutes();
            for (MediaRouter.RouteInfo route : routes) {
                if (route != null && route.isEnabled()) {
                    if (route.getDeviceType() == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error in isBluetoothViaMediaRouter", e);
            return false;
        }
    }

    private String audioDeviceTypeToString(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "BUILTIN_SPEAKER";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "BUILTIN_EARPIECE";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "WIRED_HEADSET";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "WIRED_HEADPHONES";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "BLUETOOTH_A2DP";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "BLUETOOTH_SCO";
            default:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && type == AudioDeviceInfo.TYPE_BLE_HEADSET) return "BLE_HEADSET";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && type == AudioDeviceInfo.TYPE_USB_HEADSET) return "USB_HEADSET";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID) return "HEARING_AID";
                return "UNKNOWN(" + type + ")";
        }
    }

    private boolean isWiredHeadsetConnected() {
        if (audioManager == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                for (AudioDeviceInfo device : devices) {
                    int type = device.getType();
                    if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                            || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                        return true;
                    }
                }
                return false;
            } else {
                return audioManager.isWiredHeadsetOn();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking wired headset", e);
            return false;
        }
    }

    static boolean wasBluetoothDisconnectHandled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean handled = prefs.getBoolean(PREF_BT_DISCONNECT_HANDLED, false);
        if (handled) {
            prefs.edit().putBoolean(PREF_BT_DISCONNECT_HANDLED, false).apply();
        }
        return handled;
    }

    private void handleBluetoothDisconnect() {
        long now = System.currentTimeMillis();
        if (now - lastBluetoothDisconnectTime < DEBOUNCE_MS) {
            Log.d(TAG, "Bluetooth disconnect debounced");
            return;
        }
        lastBluetoothDisconnectTime = now;

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        boolean closeOnDisconnect = sharedPref.getBoolean("close_on_bluetooth_disconnect", false);
        boolean pauseOnDisconnect = sharedPref.getBoolean("pause_on_bluetooth_disconnect", false);
        boolean isPlaying = PlayerServiceUtil.isPlaying();

        Log.d(TAG, "handleBluetoothDisconnect: closeOnDisconnect=" + closeOnDisconnect
                + ", pauseOnDisconnect=" + pauseOnDisconnect
                + ", isPlaying=" + isPlaying);

        sharedPref.edit()
                .putBoolean(PREF_BT_DISCONNECT_HANDLED, true)
                .putBoolean(PREF_HEADSET_WAS_CONNECTED, true)
                .commit();

        if (closeOnDisconnect) {
            Log.d(TAG, "Bluetooth disconnect: closing app (close_on_bluetooth_disconnect=true)");
            if (isPlaying) {
                Log.d(TAG, "  pausing playback before close...");
                PlayerServiceUtil.pause(PauseReason.BECAME_NOISY);
            }
            closeApp();
            return;
        }

        if (pauseOnDisconnect) {
            Log.d(TAG, "Bluetooth disconnect: pausing (pause_on_bluetooth_disconnect=true)");
            if (isPlaying) {
                PlayerServiceUtil.pause(PauseReason.BECAME_NOISY);
            } else {
                Log.d(TAG, "  not playing, skip pause");
            }
        } else {
            Log.d(TAG, "Bluetooth disconnect: neither close nor pause enabled, doing nothing");
        }
    }

    private void handleBluetoothConnect() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        boolean autoResume = sharedPref.getBoolean("auto_resume_on_bluetooth_a2dp_connection", false);

        Log.d(TAG, "handleBluetoothConnect: autoResume=" + autoResume);

        if (!autoResume) {
            Log.d(TAG, "Bluetooth connect: resume disabled, skipping");
            return;
        }

        tryResumePlayback(sharedPref, true);
    }

    private void handleWiredHeadsetConnect() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);

        if (!sharedPref.getBoolean("auto_resume_on_wired_headset_connection", false)) {
            Log.d(TAG, "Wired headset connect: resume disabled");
            return;
        }

        tryResumePlayback(sharedPref, false);
    }

    private void tryResumePlayback(SharedPreferences sharedPref, boolean isBluetooth) {
        boolean wasConnectedBefore = sharedPref.getBoolean(PREF_HEADSET_WAS_CONNECTED, false);
        sharedPref.edit().putBoolean(PREF_HEADSET_WAS_CONNECTED, false).apply();

        boolean isPlaying = PlayerServiceUtil.isPlaying();
        PauseReason pauseReason = PlayerServiceUtil.getPauseReason();

        Log.d(TAG, "tryResumePlayback: isBluetooth=" + isBluetooth
                + ", wasConnectedBefore=" + wasConnectedBefore
                + ", isPlaying=" + isPlaying
                + ", pauseReason=" + pauseReason);

        if (!wasConnectedBefore) {
            Log.d(TAG, (isBluetooth ? "Bluetooth" : "Wired") + " connect: no prior disconnect recorded, skipping resume");
            return;
        }

        if (isPlaying) {
            Log.d(TAG, (isBluetooth ? "Bluetooth" : "Wired") + " connect: already playing");
            return;
        }

        if (pauseReason != PauseReason.BECAME_NOISY) {
            Log.d(TAG, (isBluetooth ? "Bluetooth" : "Wired") + " connect: pause reason is " + pauseReason + ", not BECAME_NOISY");
            return;
        }

        Log.d(TAG, (isBluetooth ? "Bluetooth" : "Wired") + " connect: resuming playback");
        RadioDroidApp radioDroidApp = (RadioDroidApp) context.getApplicationContext();
        HistoryManager historyManager = radioDroidApp.getHistoryManager();
        DataRadioStation lastStation = historyManager.getFirst();

        if (lastStation != null) {
            Log.d(TAG, "  resuming station: " + lastStation.Name);
            if (!radioDroidApp.getMpdClient().isMpdEnabled()) {
                Utils.playAndWarnIfMetered(radioDroidApp, lastStation, PlayerType.RADIODROID, () -> Utils.play(radioDroidApp, lastStation));
            }
        } else {
            Log.w(TAG, "  no last station found in history, cannot resume");
        }
    }

    private void handleWiredHeadsetDisconnect() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        sharedPref.edit().putBoolean(PREF_HEADSET_WAS_CONNECTED, true).apply();
        sharedPref.edit().putBoolean(PREF_WIRED_DISCONNECT_HANDLED, true).commit();

        boolean closeOnDisconnect = sharedPref.getBoolean("close_when_noisy", false);
        boolean pauseOnDisconnect = sharedPref.getBoolean("pause_when_noisy", true);
        boolean isPlaying = PlayerServiceUtil.isPlaying();

        Log.d(TAG, "handleWiredHeadsetDisconnect: closeOnDisconnect=" + closeOnDisconnect
                + ", pauseOnDisconnect=" + pauseOnDisconnect
                + ", isPlaying=" + isPlaying);

        if (closeOnDisconnect) {
            Log.d(TAG, "Wired headset disconnect: closing app");
            if (isPlaying) {
                PlayerServiceUtil.pause(PauseReason.BECAME_NOISY);
            }
            closeApp();
            return;
        }

        if (pauseOnDisconnect) {
            Log.d(TAG, "Wired headset disconnect: pausing");
            if (isPlaying) {
                PlayerServiceUtil.pause(PauseReason.BECAME_NOISY);
            }
        }
    }

    static boolean wasWiredDisconnectHandled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean handled = prefs.getBoolean(PREF_WIRED_DISCONNECT_HANDLED, false);
        if (handled) {
            prefs.edit().putBoolean(PREF_WIRED_DISCONNECT_HANDLED, false).apply();
        }
        return handled;
    }

    private void markHeadsetDisconnected() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        sharedPref.edit().putBoolean(PREF_HEADSET_WAS_CONNECTED, true).apply();
    }

    private void closeApp() {
        Log.d(TAG, "closeApp: stopping PlayerService and closing ActivityMain");

        PlayerServiceUtil.stop();

        Intent closeIntent = new Intent(context, ActivityMain.class);
        closeIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        closeIntent.putExtra("close_app", true);
        context.startActivity(closeIntent);

        Log.d(TAG, "closeApp: done");
    }
}
