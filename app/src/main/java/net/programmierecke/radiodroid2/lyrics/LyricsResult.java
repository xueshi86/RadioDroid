package net.programmierecke.radiodroid2.lyrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class LyricsResult {

    @NonNull
    public final String artist;
    @NonNull
    public final String track;
    @Nullable
    public final String albumName;
    @Nullable
    public final String plainLyrics;
    @Nullable
    public final String syncedLyrics;
    public final boolean instrumental;
    @NonNull
    public final String sourceId;

    public LyricsResult(@NonNull String artist, @NonNull String track, @Nullable String albumName,
                        @Nullable String plainLyrics, @Nullable String syncedLyrics,
                        boolean instrumental, @NonNull String sourceId) {
        this.artist = artist;
        this.track = track;
        this.albumName = albumName;
        this.plainLyrics = plainLyrics;
        this.syncedLyrics = syncedLyrics;
        this.instrumental = instrumental;
        this.sourceId = sourceId;
    }

    public boolean hasContent() {
        return instrumental || !isEmpty(plainLyrics) || !isEmpty(syncedLyrics);
    }

    public boolean hasSyncedLyrics() {
        return !isEmpty(syncedLyrics);
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }
}
