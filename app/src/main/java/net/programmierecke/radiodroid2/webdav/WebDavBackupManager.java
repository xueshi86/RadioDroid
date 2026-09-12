package net.programmierecke.radiodroid2.webdav;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import net.programmierecke.radiodroid2.FavouriteManager;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.List;

public final class WebDavBackupManager {
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
                favourites.clear();
            } else if (!favourites.addMultiple(stations)) {
                throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Favourite restore failed");
            }
        } finally { file.delete(); }
    }

    public void backupDatabase() throws Exception {
        File source = context.getDatabasePath("radio_droid_database");
        if (!source.isFile()) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Database unavailable");
        File file = File.createTempFile("database_", ".db", temporaryDirectory());
        RadioStationRepository repository = RadioStationRepository.getInstance(context);
        try {
            repository.closeDatabase();
            copy(source, file);
        } finally {
            repository.reinitializeDatabase(context);
        }
        try {
            validateDatabase(file);
            client.upload(DATABASE_FILE, file);
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
            database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            cursor = database.rawQuery("PRAGMA integrity_check", null);
            if (!cursor.moveToFirst() || !"ok".equalsIgnoreCase(cursor.getString(0))) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Database integrity check failed");
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
