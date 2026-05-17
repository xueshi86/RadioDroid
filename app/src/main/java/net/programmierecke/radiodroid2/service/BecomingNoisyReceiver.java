package net.programmierecke.radiodroid2.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;

import androidx.preference.PreferenceManager;

import net.programmierecke.radiodroid2.ActivityMain;

public class BecomingNoisyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()) && PlayerServiceUtil.isPlaying()) {
            if (AudioDeviceMonitor.wasBluetoothDisconnectHandled(context)) {
                return;
            }

            if (AudioDeviceMonitor.wasWiredDisconnectHandled(context)) {
                return;
            }

            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);

            if (sharedPref.getBoolean("close_when_noisy", false)) {
                PlayerServiceUtil.pause(PauseReason.BECAME_NOISY);

                context.stopService(new Intent(context, PlayerService.class));

                Intent closeIntent = new Intent(context, ActivityMain.class);
                closeIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                closeIntent.putExtra("close_app", true);
                context.startActivity(closeIntent);
                return;
            }

            if (sharedPref.getBoolean("pause_when_noisy", true)) {
                PlayerServiceUtil.pause(PauseReason.BECAME_NOISY);
            }
        }
    }
}
