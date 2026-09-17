package net.programmierecke.radiodroid2.lyrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "lyrics_cache")
public class LyricsCacheEntry {

    @PrimaryKey
    @ColumnInfo(name = "cache_key")
    @NonNull
    public String cacheKey;

    @ColumnInfo(name = "artist")
    @NonNull
    public String artist;

    @ColumnInfo(name = "track")
    @NonNull
    public String track;

    @ColumnInfo(name = "album_name")
    @Nullable
    public String albumName;

    @ColumnInfo(name = "plain_lyrics")
    @Nullable
    public String plainLyrics;

    @ColumnInfo(name = "synced_lyrics")
    @Nullable
    public String syncedLyrics;

    @ColumnInfo(name = "instrumental")
    public boolean instrumental;

    @ColumnInfo(name = "source_id")
    @NonNull
    public String sourceId;

    @ColumnInfo(name = "fetched_at")
    public long fetchedAt;

    public LyricsCacheEntry(@NonNull String cacheKey, @NonNull String artist, @NonNull String track,
                            @Nullable String albumName, @Nullable String plainLyrics,
                            @Nullable String syncedLyrics, boolean instrumental,
                            @NonNull String sourceId, long fetchedAt) {
        this.cacheKey = cacheKey;
        this.artist = artist;
        this.track = track;
        this.albumName = albumName;
        this.plainLyrics = plainLyrics;
        this.syncedLyrics = syncedLyrics;
        this.instrumental = instrumental;
        this.sourceId = sourceId;
        this.fetchedAt = fetchedAt;
    }
}
