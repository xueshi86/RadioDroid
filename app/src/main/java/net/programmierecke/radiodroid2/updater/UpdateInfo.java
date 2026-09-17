package net.programmierecke.radiodroid2.updater;

public class UpdateInfo {
    public final String latestVersion;
    public final String apkUrl;
    public final String apkFileName;
    public final long apkSize;
    public final String releaseNotes;
    public final boolean hasUpdate;

    public UpdateInfo(String latestVersion, String apkUrl, String apkFileName, long apkSize, String releaseNotes, boolean hasUpdate) {
        this.latestVersion = latestVersion;
        this.apkUrl = apkUrl;
        this.apkFileName = apkFileName;
        this.apkSize = apkSize;
        this.releaseNotes = releaseNotes;
        this.hasUpdate = hasUpdate;
    }
}
