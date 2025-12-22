package net.programmierecke.radiodroid2.database;

import androidx.room.Ignore;

public class TagCount {
    public String tag;
    public int stationCount;
    
    public TagCount() {
    }
    
    @Ignore
    public TagCount(String tag, int stationCount) {
        this.tag = tag;
        this.stationCount = stationCount;
    }
}