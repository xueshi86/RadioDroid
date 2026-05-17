package net.programmierecke.radiodroid2.database;

import android.content.Context;
import android.util.Log;

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

@Database(entities = {TrackHistoryEntry.class, RadioStation.class, UpdateTimestamp.class, RadioStationFts.class}, version = 14)
@TypeConverters({Converters.class})
public abstract class RadioDroidDatabase extends RoomDatabase {
    public abstract TrackHistoryDao songHistoryDao();
    
    public abstract RadioStationDao radioStationDao();
    
    public abstract UpdateTimestampDao updateTimestampDao();

    private static volatile RadioDroidDatabase INSTANCE;

    private static Executor queryExecutor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "RadioDroidDatabase Executor"));

    private static volatile boolean isClosing = false;

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
                "content=`radio_stations`)");
            
            // Populate FTS table with existing data, ignoring duplicates
            database.execSQL(
                "INSERT OR IGNORE INTO `radio_stations_fts` (`station_uuid`, `name`, `tags`, `country`, `language`) " +
                "SELECT `station_uuid`, `name`, `tags`, `country`, `language` FROM `radio_stations`");
        }
    };
    
    // Migration from version 5 to version 6 - Add radio_stations_fts table
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // No changes needed for this migration
        }
    };
    
    // Migration from version 5 to version 14 - Empty migration for version jumps
    static final Migration MIGRATION_5_14 = new Migration(5, 14) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // No changes needed for this migration
            // This migration handles jumps from version 5 to version 14
        }
    };
    
    // Migration from version 6 to version 14 - Empty migration for version jumps
    static final Migration MIGRATION_6_14 = new Migration(6, 14) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // No changes needed for this migration
            // This migration handles jumps from version 6 to version 14
        }
    };

    public static RadioDroidDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (RadioDroidDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            RadioDroidDatabase.class, "radio_droid_database")
                            .addCallback(CALLBACK)
                            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_5_14, MIGRATION_6_14)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    // 关闭数据库实例
    public static void closeInstance() {
        synchronized (RadioDroidDatabase.class) {
            // 设置关闭标志，阻止 onOpen 中的任务执行
            isClosing = true;
            
            // 关闭现有实例
            if (INSTANCE != null) {
                INSTANCE.close();
                INSTANCE = null;
                
                // 添加短暂延迟，确保数据库完全关闭并释放文件句柄
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // 强制重新创建数据库实例
    public static RadioDroidDatabase forceRecreateDatabase(final Context context) {
        synchronized (RadioDroidDatabase.class) {
            // 设置关闭标志，阻止 onOpen 中的任务执行
            isClosing = true;
            
            // 关闭现有实例
            if (INSTANCE != null) {
                INSTANCE.close();
                INSTANCE = null;
                
                // 添加短暂延迟，确保数据库完全关闭并释放文件句柄
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            // 创建新实例
            INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                    RadioDroidDatabase.class, "radio_droid_database")
                    .addCallback(CALLBACK)
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_5_14, MIGRATION_6_14)
                    .fallbackToDestructiveMigration()
                    .build();
            
            // 重置关闭标志
            isClosing = false;
            
            return INSTANCE;
        }
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

            queryExecutor.execute(() -> {
                // 检查数据库是否正在关闭，如果是则跳过操作
                if (isClosing) {
                    return;
                }
                
                // App may have been terminated without notice so we should set last track history entry's
                // end time to something reasonable.
                try {
                    // 增加更多检查，确保数据库完全可用
                    if (INSTANCE == null || !INSTANCE.isOpen() || isClosing) {
                        return;
                    }
                    
                    // 检查数据库连接是否可用
                    SupportSQLiteDatabase database = INSTANCE.getOpenHelper().getWritableDatabase();
                    if (database == null) {
                        return;
                    }
                    
                    // 检查表是否存在
                    android.database.Cursor cursor = null;
                    try {
                        cursor = database.query("SELECT name FROM sqlite_master WHERE type='table' AND name='song_history'");
                        if (cursor == null || cursor.getCount() == 0) {
                            // 表不存在，跳过操作
                            return;
                        }
                    } catch (Exception e) {
                        // 查询失败，跳过操作
                        return;
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                    
                    // 执行实际操作
                    INSTANCE.songHistoryDao().setLastHistoryItemEndTimeRelative(MAX_UNKNOWN_TRACK_DURATION);
                } catch (Exception e) {
                    // 忽略所有异常，避免崩溃
                    Log.w("RadioDroidDatabase", "Error setting last history item end time", e);
                }
            });
        }
    };

}
