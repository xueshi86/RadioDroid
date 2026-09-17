package net.programmierecke.radiodroid2.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class UpdateReceiver extends BroadcastReceiver {
    public static final String ACTION_DOWNLOAD_UPDATE = "net.programmierecke.radiodroid2.action.DOWNLOAD_UPDATE";
    public static final String EXTRA_VERSION = "version";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_SIZE = "size";
    public static final String EXTRA_NOTES = "notes";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_DOWNLOAD_UPDATE.equals(intent.getAction())) {
            return;
        }
        UpdateInfo info = new UpdateInfo(
                intent.getStringExtra(EXTRA_VERSION),
                intent.getStringExtra(EXTRA_URL),
                intent.getStringExtra(EXTRA_FILE_NAME),
                intent.getLongExtra(EXTRA_SIZE, 0L),
                intent.getStringExtra(EXTRA_NOTES),
                true);
        UpdateDownloader.startDownload(context, info);
    }
}
