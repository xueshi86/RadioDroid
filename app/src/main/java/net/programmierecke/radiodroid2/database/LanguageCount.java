package net.programmierecke.radiodroid2.database;

import androidx.room.Ignore;

public class LanguageCount {
    public String language;
    public int stationCount;
    
    public LanguageCount() {
    }
    
    @Ignore
    public LanguageCount(String language, int stationCount) {
        this.language = language;
        this.stationCount = stationCount;
    }
}