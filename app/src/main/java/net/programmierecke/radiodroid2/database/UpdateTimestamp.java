package net.programmierecke.radiodroid2.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "update_timestamp")
public class UpdateTimestamp {
    @PrimaryKey
    public int id = 1;
    
    public long last_update_timestamp;
}