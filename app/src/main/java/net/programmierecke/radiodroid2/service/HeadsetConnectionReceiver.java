package net.programmierecke.radiodroid2.service;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.util.Log;

import androidx.preference.PreferenceManager;

import net.programmierecke.radiodroid2.ActivityMain;
import net.programmierecke.radiodroid2.HistoryManager;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.players.selector.PlayerType;
import net.programmierecke.radiodroid2.station.DataRadioStation;

public class HeadsetConnectionReceiver extends BroadcastReceiver {

    private static final String TAG = "HeadsetConnection";

    private static volatile long lastBluetoothDisconnectTime = 0;
    private static final long DEBOUNCE_MS = 2000;
    private static final String PREF_BLUETOOTH_DISCONNECT_HANDLED = "bluetooth_disconnect_handled";
    private static final String PREF_HEADSET_WAS_CONNECTED = "headset_was_connected_before_disconnect";

    static boolean wasBluetoothDisconnectHandled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean handled = prefs.getBoolean(PREF_BLUETOOTH_DISCONNECT_HANDLED, false);
        if (handled) {
            prefs.edit().putBoolean(PREF_BLUETOOTH_DISCONNECT_HANDLED, false).apply();
        }
        return handled;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        Log.d(TAG, "onReceive: action=" + action);

        if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action) ||
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
            Log.d(TAG, "Bluetooth profile state changed: " + state);
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                handleBluetoothDisconnect(context);
                return;
            } else if (state == BluetoothProfile.STATE_CONNECTED) {
                handleHeadsetConnect(context, true);
                return;
            }
        }

        if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null && isAudioBluetoothDevice(device)) {
                Log.d(TAG, "Bluetooth ACL disconnected: " + device.getName());
                handleBluetoothDisconnect(context);
                return;
            }
        }

        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null && isAudioBluetoothDevice(device)) {
                Log.d(TAG, "Bluetooth ACL connected: " + device.getName());
                handleHeadsetConnect(context, true);
                return;
            }
        }

        if (AudioManager.ACTION_HEADSET_PLUG.equals(action)) {
            final int state = intent.getIntExtra("state", -1);
            Log.d(TAG, "Headset plug: state=" + state);
            if (state == 1) {
                handleHeadsetConnect(context, false);
            } else if (state == 0) {
                markHeadsetDisconnected(context);
            }
        }
    }

    private void handleBluetoothDisconnect(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastBluetoothDisconnectTime < DEBOUNCE_MS) {
            Log.d(TAG, "Bluetooth disconnect debounced");
            return;
        }
        lastBluetoothDisconnectTime = now;

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        sharedPref.edit()
                .putBoolean(PREF_BLUETOOTH_DISCONNECT_HANDLED, true)
                .putBoolean(PREF_HEADSET_WAS_CONNECTED, true)
                .apply();

        if (sharedPref.getBoolean("close_on_bluetooth_disconnect", false)) {
            Log.d(TAG, "Bluetooth disconnect: closing app");
            if (PlayerServiceUtil.isPlaying()) {
                PlayerServiceUtil.pause(PauseReason.BECAME_NOISY);
            }
            closeApp(context);
            return;
        }

        if (sharedPref.getBoolean("pause_on_bluetooth_disconnect", false)) {
            Log.d(TAG, "Bluetooth disconnect: pausing");
            if (PlayerServiceUtil.isPlaying()) {
                PlayerServiceUtil.pause(PauseReason.BECAME_NOISY);
            }
        }
    }

    private void handleHeadsetConnect(Context context, boolean isBluetooth) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);

        boolean wasConnectedBefore = sharedPref.getBoolean(PREF_HEADSET_WAS_CONNECTED, false);
        sharedPref.edit().putBoolean(PREF_HEADSET_WAS_CONNECTED, false).apply();

        boolean shouldResume;
        if (isBluetooth) {
            shouldResume = sharedPref.getBoolean("auto_resume_on_bluetooth_a2dp_connection", false);
        } else {
            shouldResume = sharedPref.getBoolean("auto_resume_on_wired_headset_connection", false);
        }

        if (!shouldResume) {
            Log.d(TAG, "Headset connect: resume disabled for " + (isBluetooth ? "bluetooth" : "wired"));
            return;
        }

        if (!wasConnectedBefore) {
            Log.d(TAG, "Headset connect: no prior disconnect recorded, skipping resume");
            return;
        }

        if (PlayerServiceUtil.isPlaying()) {
            Log.d(TAG, "Headset connect: already playing");
            return;
        }

        PauseReason pauseReason = PlayerServiceUtil.getPauseReason();
        if (pauseReason != PauseReason.BECAME_NOISY) {
            Log.d(TAG, "Headset connect: pause reason is " + pauseReason + ", not BECAME_NOISY");
            return;
        }

        Log.d(TAG, "Headset connect: resuming playback");
        RadioDroidApp radioDroidApp = (RadioDroidApp) context.getApplicationContext();
        HistoryManager historyManager = radioDroidApp.getHistoryManager();
        DataRadioStation lastStation = historyManager.getFirst();

        if (lastStation != null) {
            if (!radioDroidApp.getMpdClient().isMpdEnabled()) {
                Utils.playAndWarnIfMetered(radioDroidApp, lastStation, PlayerType.RADIODROID, () -> Utils.play(radioDroidApp, lastStation));
            }
        }
    }

    private void markHeadsetDisconnected(Context context) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        sharedPref.edit().putBoolean(PREF_HEADSET_WAS_CONNECTED, true).apply();
    }

    private void closeApp(Context context) {
        context.stopService(new Intent(context, PlayerService.class));

        Intent closeIntent = new Intent(context, ActivityMain.class);
        closeIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        closeIntent.putExtra("close_app", true);
        context.startActivity(closeIntent);
    }

    private boolean isAudioBluetoothDevice(BluetoothDevice device) {
        try {
            BluetoothClass btClass = device.getBluetoothClass();
            if (btClass == null) return true;
            int deviceClass = btClass.getDeviceClass();
            if (btClass.hasService(BluetoothClass.Service.AUDIO)) return true;
            if (deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
                    deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
                    deviceClass == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER ||
                    deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                    deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO) {
                return true;
            }
            return false;
        } catch (SecurityException e) {
            return true;
        }
    }
}
