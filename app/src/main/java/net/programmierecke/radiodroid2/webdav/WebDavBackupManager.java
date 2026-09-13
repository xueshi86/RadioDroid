package net.programmierecke.radiodroid2.webdav;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import net.programmierecke.radiodroid2.FavouriteManager;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.List;

public final class WebDavBackupManager {
    private static final String TAG = "WebDavBackupManager";
    public static final String FAVOURITES_FILE = "favourites.m3u";
    public static final String DATABASE_FILE = "radio_droid_database.db";
    private static final String PENDING_PREFS = "webdav_pending_restore";
    private final Context context;
    private final WebDavClient client;

    public WebDavBackupManager(Context context, WebDavSettings settings) {
        this.context = context.getApplicationContext();
        client = new WebDavClient(settings);
    }

    public void backupFavourites() throws Exception {
        File dir = temporaryDirectory();
        File file = File.createTempFile("favourites_", ".m3u", dir);
        try {
            FavouriteManager favourites = ((RadioDroidApp) context).getFavouriteManager();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), Charset.forName("UTF-8")));
            try { if (!favourites.SaveM3UWriter(writer)) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Favourite export failed"); }
            finally { writer.close(); }
            client.upload(FAVOURITES_FILE, file);
        } finally { file.delete(); }
    }

    public void restoreFavourites() throws Exception {
        File file = client.download(FAVOURITES_FILE, temporaryDirectory());
        try {
            FavouriteManager favourites = ((RadioDroidApp) context).getFavouriteManager();
            InputStreamReader reader = new InputStreamReader(new FileInputStream(file), Charset.forName("UTF-8"));
            List<DataRadioStation> stations;
            try { stations = favourites.LoadM3UReader(reader); }
            finally { reader.close(); }
            if (stations == null) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Favourite restore failed");
            if (stations.isEmpty()) {
                // 服务器上的收藏文件为空（或解析不出任何电台）：不能静默清空本地收藏，
                // 否则会造成"恢复成功但列表为空"的误导。明确报错，本地数据保持不变。
                throw new WebDavException(WebDavException.Kind.EMPTY_FILE, "Remote favourites file is empty");
            }
            if (!favourites.addMultiple(stations)) {
                throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Favourite restore failed");
            }
        } finally { file.delete(); }
    }

    public void backupDatabase() throws Exception {
        File source = context.getDatabasePath("radio_droid_database");
        Log.d(TAG, "backupDatabase: source=" + source.getAbsolutePath() + " exists=" + source.isFile() + " size=" + source.length());
        if (!source.isFile()) throw new WebDavException(WebDavException.Kind.LOCAL_DATABASE, "Database unavailable");
        File file;
        try {
            file = File.createTempFile("database_", ".db", temporaryDirectory());
        } catch (IOException e) {
            throw new WebDavException(WebDavException.Kind.STORAGE, "Temporary database file unavailable", e);
        }
        RadioStationRepository repository = RadioStationRepository.getInstance(context);
        Exception databaseFailure = null;
        try {
            repository.closeDatabase();
            Log.d(TAG, "backupDatabase: closeDatabase done, opening for checkpoint: " + source.getAbsolutePath());
            SQLiteDatabase database = SQLiteDatabase.openDatabase(source.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            try {
                // PRAGMA wal_checkpoint 会返回结果集，Android execSQL 不支持执行返回结果集的语句，
                // 必须用 rawQuery 执行并消费游标，否则抛 SQLiteException 导致备份失败。
                try (Cursor checkpointCursor = database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
                    checkpointCursor.moveToFirst();
                }
                Log.d(TAG, "backupDatabase: wal_checkpoint done");
            } finally {
                database.close();
            }
            Log.d(TAG, "backupDatabase: copying db to temp, sourceSize=" + source.length());
            copy(source, file);
            Log.d(TAG, "backupDatabase: copy done, tempSize=" + file.length());
        } catch (IOException e) {
            Log.e(TAG, "backupDatabase: copy failed", e);
            databaseFailure = new WebDavException(WebDavException.Kind.LOCAL_DATABASE, "Database copy failed", e);
        } catch (SQLiteException e) {
            Log.e(TAG, "backupDatabase: sqlite prepare failed", e);
            databaseFailure = new WebDavException(WebDavException.Kind.LOCAL_DATABASE, "Database cannot be prepared", e);
        } catch (RuntimeException e) {
            Log.e(TAG, "backupDatabase: runtime failure", e);
            databaseFailure = new WebDavException(WebDavException.Kind.LOCAL_DATABASE, "Database could not be closed", e);
        } finally {
            try {
                repository.reinitializeDatabase(context);
                Log.d(TAG, "backupDatabase: reinitializeDatabase done");
            } catch (RuntimeException e) {
                if (databaseFailure == null) {
                    databaseFailure = new WebDavException(WebDavException.Kind.LOCAL_DATABASE, "Database could not be reopened", e);
                } else {
                    databaseFailure.addSuppressed(e);
                }
            }
        }
        if (databaseFailure != null) {
            throw databaseFailure;
        }
        try {
            validateDatabase(file);
            Log.d(TAG, "backupDatabase: validateDatabase done, uploading file=" + file.length() + " bytes");
            try {
                client.upload(DATABASE_FILE, file);
                Log.d(TAG, "backupDatabase: upload done");
            } catch (WebDavException uploadEx) {
                Log.e(TAG, "backupDatabase: upload failed kind=" + uploadEx.getKind() + " msg=" + uploadEx.getMessage(), uploadEx);
                throw uploadEx;
            }
        } finally { file.delete(); }
    }

    public void prepareDatabaseRestore() throws Exception {
        File downloaded = client.download(DATABASE_FILE, temporaryDirectory());
        try {
            validateDatabase(downloaded);
            context.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE).edit().putString("database", downloaded.getAbsolutePath()).apply();
        } catch (Exception e) {
            downloaded.delete();
            throw e;
        }
    }

    public static void validateDatabase(File file) throws Exception {
        if (file == null || !file.isFile() || file.length() < 16 || file.length() > 512L * 1024L * 1024L) {
            throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Invalid database file");
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] header = new byte[16];
            if (input.read(header) != 16 || !java.util.Arrays.equals(header, new byte[] {'S','Q','L','i','t','e',' ','f','o','r','m','a','t',' ','3',0})) {
                throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Invalid database header");
            }
        }
        SQLiteDatabase database = null;
        Cursor cursor = null;
        try {
            // 数据库含 FTS4 虚表，PRAGMA integrity_check 校验 FTS 倒排索引时需要写入临时校验页，
            // 用 OPEN_READONLY 会报 "attempt to write a readonly database" 导致校验失败，因此用 OPEN_READWRITE。
            database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            // 打印 integrity_check 完整输出，便于定位失败原因
            cursor = database.rawQuery("PRAGMA integrity_check", null);
            StringBuilder integ = new StringBuilder();
            while (cursor.moveToNext()) {
                if (integ.length() > 0) integ.append(" | ");
                integ.append(cursor.getString(0));
            }
            Log.d(TAG, "validateDatabase: integrity_check result=[" + integ + "]");
            cursor.close();
            cursor = database.rawQuery("PRAGMA integrity_check", null);
            if (!cursor.moveToFirst() || !"ok".equalsIgnoreCase(cursor.getString(0))) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Database integrity check failed: " + integ);
            cursor.close();
            cursor = database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN (?, ?, ?, ?)", new String[] {"radio_stations", "song_history", "update_timestamp", "radio_stations_fts"});
            if (cursor.getCount() < 3) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Database schema is incompatible");
        } catch (SQLiteException e) {
            throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Database cannot be opened", e);
        } finally {
            if (cursor != null) cursor.close();
            if (database != null) database.close();
        }
    }

    private File temporaryDirectory() throws WebDavException {
        File dir = new File(context.getCacheDir(), "webdav");
        if (!dir.exists() && !dir.mkdirs()) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Temporary directory unavailable");
        return dir;
    }

    private void copy(File source, File target) throws Exception {
        FileInputStream input = new FileInputStream(source);
        FileOutputStream output = new FileOutputStream(target);
        try { byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count); output.flush(); }
        finally { input.close(); output.close(); }
    }
}
