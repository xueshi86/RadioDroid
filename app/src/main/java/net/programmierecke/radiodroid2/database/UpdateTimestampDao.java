package net.programmierecke.radiodroid2.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

@Dao
public interface UpdateTimestampDao {
    @Query("SELECT * FROM update_timestamp WHERE id = 1")
    UpdateTimestamp getTimestamp();
    
    @Query("SELECT last_update_timestamp FROM update_timestamp WHERE id = 1")
    long getLastUpdateTime();
    
    @Insert
    void insertTimestamp(UpdateTimestamp timestamp);
    
    @Update
    void updateTimestamp(UpdateTimestamp timestamp);
    
    @Query("UPDATE update_timestamp SET last_update_timestamp = :timestamp WHERE id = 1")
    void updateTimestamp(long timestamp);
}