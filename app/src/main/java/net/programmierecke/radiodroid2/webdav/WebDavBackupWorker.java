package net.programmierecke.radiodroid2.webdav;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class WebDavBackupWorker extends Worker {
    private static final String TAG = "WebDavBackupWorker";
    public static final String KEY_TYPE = "type";
    public static final String KEY_MODE = "mode";
    public static final String KEY_FAV_MODE = "fav_mode";
    public static final String MODE_OVERWRITE = "overwrite";
    public static final String MODE_MERGE = "merge";
    public static final String WORK_NAME = "webdav_backup_restore";
    public static final String RESULT_PREFS = "webdav_last_result";
    private static final int MAX_ATTEMPTS = 6;
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_PENDING = "pending";
    private static final String ERROR_PREFIX = "error_";
    private static final String ERROR_CONFIGURATION = "error_configuration";
    private static final String ERROR_UNKNOWN = "error_unknown";

    public WebDavBackupWorker(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }

    public static void enqueue(Context context, WebDavBackupType type, boolean restore, String favMode) {
        Data input = new Data.Builder().putString(KEY_TYPE, type.name()).putBoolean(KEY_MODE, restore).putString(KEY_FAV_MODE, favMode).build();
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(WebDavBackupWorker.class).setInputData(input).setConstraints(constraints).build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        WebDavSettings settings;
        String configurationError = null;
        try {
            settings = new WebDavSettingsStore(getApplicationContext()).load();
        } catch (WebDavException e) {
            settings = null;
            configurationError = errorCode(e);
        }
        if (settings == null) {
            persistResult(null, configurationError == null ? ERROR_CONFIGURATION : configurationError, getInputData().getBoolean(KEY_MODE, false));
            return Result.failure();
        }

        WebDavBackupManager manager = new WebDavBackupManager(getApplicationContext(), settings);
        WebDavBackupType type = WebDavBackupType.fromName(getInputData().getString(KEY_TYPE));
        boolean restore = getInputData().getBoolean(KEY_MODE, false);
        boolean favRequested = type == WebDavBackupType.FAVOURITES || type == WebDavBackupType.BOTH;
        boolean dbRequested = type == WebDavBackupType.DATABASE || type == WebDavBackupType.BOTH;

        String favStatus = null;
        String dbStatus = null;
        boolean retryable = false;

        if (favRequested) {
            try {
                if (restore) manager.restoreFavourites(MODE_MERGE.equals(getInputData().getString(KEY_FAV_MODE)));
                else manager.backupFavourites();
                favStatus = STATUS_SUCCESS;
            } catch (Exception e) {
                Log.e(TAG, "Favourites operation failed: restore=" + restore, e);
                favStatus = errorCode(e);
                retryable = retryable || isNetworkError(e);
            }
        }

        if (dbRequested) {
            try {
                if (restore) {
                    manager.prepareDatabaseRestore();
                    dbStatus = STATUS_PENDING;
                } else {
                    manager.backupDatabase();
                    dbStatus = STATUS_SUCCESS;
                }
            } catch (Exception e) {
                Log.e(TAG, "Database operation failed: restore=" + restore, e);
                dbStatus = errorCode(e);
                retryable = retryable || isNetworkError(e);
            }
        }

        if (retryable && getRunAttemptCount() < MAX_ATTEMPTS) return Result.retry();
        persistResult(favStatus, dbStatus, restore);
        if (isError(favStatus) || isError(dbStatus)) return Result.failure();
        return Result.success();
    }

    private String errorCode(Exception e) {
        if (e instanceof WebDavException) {
            switch (((WebDavException) e).getKind()) {
                case AUTHENTICATION: return "error_authentication";
                case PERMISSION: return "error_permission";
                case NOT_FOUND: return "error_not_found";
                case PROTOCOL: return "error_protocol";
                case NETWORK: return "error_network";
                case LOCAL_DATABASE: return "error_local_database";
                case STORAGE: return "error_storage";
                case CONFIGURATION: return ERROR_CONFIGURATION;
                case EMPTY_FILE: return "error_empty_favourites";
                default: return "error_invalid_data";
            }
        }
        return ERROR_UNKNOWN;
    }

    private boolean isError(String status) {
        return status != null && status.startsWith(ERROR_PREFIX);
    }

    private boolean isNetworkError(Exception e) {
        return e instanceof WebDavException && ((WebDavException) e).getKind() == WebDavException.Kind.NETWORK;
    }

    private void persistResult(String favStatus, String dbStatus, boolean restore) {
        if (favStatus == null && dbStatus == null) return;
        SharedPreferences.Editor editor = getApplicationContext().getSharedPreferences(RESULT_PREFS, Context.MODE_PRIVATE).edit().putBoolean("restore", restore);
        if (favStatus != null) editor.putString("fav", favStatus);
        if (dbStatus != null) editor.putString("db", dbStatus);
        editor.apply();
    }
}
