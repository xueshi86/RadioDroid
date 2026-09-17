package net.programmierecke.radiodroid2.lyrics;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface LyricsCacheDao {

    @Query("SELECT * FROM lyrics_cache WHERE cache_key = :cacheKey LIMIT 1")
    LyricsCacheEntry getByKey(String cacheKey);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(LyricsCacheEntry entry);

    @Query("DELETE FROM lyrics_cache WHERE fetched_at < :threshold")
    int deleteOlderThan(long threshold);

    @Query("DELETE FROM lyrics_cache")
    void clear();
}
