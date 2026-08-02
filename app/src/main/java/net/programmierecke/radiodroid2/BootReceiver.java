package net.programmierecke.radiodroid2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import net.programmierecke.radiodroid2.alarm.RadioAlarmManager;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(final Context context, Intent intent) {
        // 校验 action：避免任意 Intent 触发闹钟重置
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.w(TAG, "Ignored broadcast: action mismatch");
            return;
        }

        final RadioDroidApp radioDroidApp = (RadioDroidApp) context.getApplicationContext();
        // resetAllAlarms 内部涉及 SharedPreferences 读写和 AlarmManager 操作，
        // 移到 goAsync 后台执行避免阻塞主线程（onReceive 默认 10 秒超时）
        final PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                radioDroidApp.getAlarmManager().resetAllAlarms();
            } catch (Exception e) {
                Log.e(TAG, "resetAllAlarms failed", e);
            } finally {
                if (pendingResult != null) {
                    pendingResult.finish();
                }
            }
        }).start();
    }
}
