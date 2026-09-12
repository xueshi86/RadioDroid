package net.programmierecke.radiodroid2.webdav;

public enum WebDavBackupType {
    FAVOURITES,
    DATABASE,
    BOTH;

    public static WebDavBackupType fromName(String value) {
        try { return value == null ? FAVOURITES : valueOf(value); }
        catch (IllegalArgumentException e) { return FAVOURITES; }
    }
}
