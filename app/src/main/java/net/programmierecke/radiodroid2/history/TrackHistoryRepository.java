package net.programmierecke.radiodroid2.history;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.paging.LivePagedListBuilder;
import androidx.paging.PagedList;

import net.programmierecke.radiodroid2.database.RadioDroidDatabase;

import java.util.Date;
import java.util.concurrent.Executor;

import static net.programmierecke.radiodroid2.history.TrackHistoryEntry.MAX_HISTORY_ITEMS_IN_TABLE;

public class TrackHistoryRepository {
    private final static int HISTORY_PAGE_SIZE = 15;

    public interface GetItemCallback {
        /**
         * It will be ran in the DB thread
         *
         * @param trackHistoryEntry fetched entry
         * @param dao               could be used to immediately do changes in the DB
         */
        void onItemFetched(@Nullable TrackHistoryEntry trackHistoryEntry, @NonNull TrackHistoryDao dao);
    }

    private final Context appContext;
    private final Executor queryExecutor;
    private final LiveData<PagedList<TrackHistoryEntry>> allHistoryPaged;
    private final PagedList.Config pagedConfig;

    // For performance reasons we don't want to enforce history limit on every insert
    private final static int TRUNCATE_FREQUENCY = 20;
    private int insertsToTruncateLeft = 0;

    public TrackHistoryRepository(Application application) {
        appContext = application.getApplicationContext();
        RadioDroidDatabase db = RadioDroidDatabase.getDatabase(application);

        queryExecutor = db.getQueryExecutor();

        pagedConfig = new PagedList.Config.Builder()
                .setPageSize(HISTORY_PAGE_SIZE)
                .setEnablePlaceholders(true)
                .build();

        allHistoryPaged = new LivePagedListBuilder<>(
                db.songHistoryDao().getAllHistoryPositional(),
                pagedConfig)
                .build();
    }

    /**
     * 每次操作时动态获取 DAO：设置页恢复数据库/WebDAV 恢复等流程会关闭并重建共享
     * Room 单例，若缓存旧实例的 DAO，之后播放服务写历史将因连接池已关闭而崩溃。
     */
    private TrackHistoryDao dao() {
        return RadioDroidDatabase.getDatabase(appContext).songHistoryDao();
    }

    public LiveData<PagedList<TrackHistoryEntry>> getAllHistoryPaged() {
        return allHistoryPaged;
    }

    public LiveData<PagedList<TrackHistoryEntry>> getStationHistoryPaged(String stationUuid) {
        return new LivePagedListBuilder<>(
                dao().getStationHistoryPositional(stationUuid),
                pagedConfig)
                .build();
    }

    public void insert(final TrackHistoryEntry historyEntry) {
        queryExecutor.execute(() -> {
            dao().insert(historyEntry);
            if (insertsToTruncateLeft == 0) {
                insertsToTruncateLeft = TRUNCATE_FREQUENCY;
                dao().truncateHistory(MAX_HISTORY_ITEMS_IN_TABLE);
            } else {
                insertsToTruncateLeft--;
            }
        });
    }

    public void setCurrentPlayingTrackEndTime(final Date time) {
        queryExecutor.execute(() -> dao().setCurrentPlayingTrackEndTime(time));
    }

    public void setLastHistoryItemEndTimeRelative(final int deltaSeconds) {
        queryExecutor.execute(() -> dao().setLastHistoryItemEndTimeRelative(deltaSeconds));
    }

    public void setTrackArtUrl(int id, @NonNull final String artUrl) {
        queryExecutor.execute(() -> dao().setTrackArtUrl(id, artUrl));
    }

    public void getLastInsertedHistoryItem(@NonNull final GetItemCallback callback) {
        queryExecutor.execute(() -> {
            TrackHistoryDao currentDao = dao();
            TrackHistoryEntry item = currentDao.getLastInsertedHistoryItem();
            callback.onItemFetched(item, currentDao);
        });
    }

    public void deleteHistory() {
        queryExecutor.execute(() -> {
            dao().deleteHistory();
        });
    }
}
