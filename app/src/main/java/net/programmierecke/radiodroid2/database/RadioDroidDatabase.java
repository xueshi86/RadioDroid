package net.programmierecke.radiodroid2.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import net.programmierecke.radiodroid2.history.TrackHistoryDao;
import net.programmierecke.radiodroid2.history.TrackHistoryEntry;
import net.programmierecke.radiodroid2.database.RadioStation;
import net.programmierecke.radiodroid2.database.RadioStationDao;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static net.programmierecke.radiodroid2.history.TrackHistoryEntry.MAX_UNKNOWN_TRACK_DURATION;

@Database(entities = {TrackHistoryEntry.class, RadioStation.class, UpdateTimestamp.class, RadioStationFts.class}, version = 5)
@TypeConverters({Converters.class})
public abstract class RadioDroidDatabase extends RoomDatabase {
    public abstract TrackHistoryDao songHistoryDao();
    
    public abstract RadioStationDao radioStationDao();
    
    public abstract UpdateTimestampDao updateTimestampDao();

    private static volatile RadioDroidDatabase INSTANCE;

    private Executor queryExecutor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "RadioDroidDatabase Executor"));

    // Migration from version 3 to version 4 - Add UpdateTimestamp table
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // Create UpdateTimestamp table
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `update_timestamp` (`id` INTEGER NOT NULL, `last_update_timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            );
            
            // Insert default row
            database.execSQL(
                "INSERT OR REPLACE INTO `update_timestamp` (`id`, `last_update_timestamp`) VALUES (1, 0)"
            );
        }
    };
    
    // Migration from version 4 to version 5 - Add FTS table for faster search
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // Create FTS table
            database.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `radio_stations_fts` USING FTS4(" +
                "`station_uuid`, `name`, `tags`, `country`, `language`, " +
                "content=`radio_stations`)"
            );
            
            // Populate FTS table with existing data, ignoring duplicates
            database.execSQL(
                "INSERT OR IGNORE INTO `radio_stations_fts` (`station_uuid`, `name`, `tags`, `country`, `language`) " +
                "SELECT `station_uuid`, `name`, `tags`, `country`, `language` FROM `radio_stations`"
            );
        }
    };

    public static RadioDroidDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (RadioDroidDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            RadioDroidDatabase.class, "radio_droid_database")
                            .addCallback(CALLBACK)
                            .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    public Executor getQueryExecutor() {
        return queryExecutor;
    }

    private static RoomDatabase.Callback CALLBACK = new RoomDatabase.Callback() {
        @Override
        public void onCreate(@NonNull SupportSQLiteDatabase db) {
            super.onCreate(db);
        }

        @Override
        public void onOpen(@NonNull SupportSQLiteDatabase db) {
            super.onOpen(db);

            INSTANCE.queryExecutor.execute(() -> {
                // App may have been terminated without notice so we should set last track history entry's
                // end time to something reasonable.
                INSTANCE.songHistoryDao().setLastHistoryItemEndTimeRelative(MAX_UNKNOWN_TRACK_DURATION);
            });
        }
    };

}
