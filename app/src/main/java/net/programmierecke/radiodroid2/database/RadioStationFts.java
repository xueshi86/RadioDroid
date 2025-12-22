package net.programmierecke.radiodroid2.database;

import androidx.room.Entity;
import androidx.room.Fts4;
import androidx.room.ColumnInfo;

/**
 * 用于全文搜索的FTS表
 */
@Fts4(contentEntity = RadioStation.class)
@Entity(tableName = "radio_stations_fts")
public class RadioStationFts {
    @ColumnInfo(name = "station_uuid")
    public String stationUuid;
    public String name;
    public String tags;
    public String country;
    public String language;
}