package net.programmierecke.radiodroid2.players;

import android.os.Parcel;
import android.os.Parcelable;

public enum PlayState implements Parcelable {
    Idle,
    PrePlaying,
    Playing,
    Paused;

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(ordinal());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<PlayState> CREATOR = new Creator<PlayState>() {
        @Override
        public PlayState createFromParcel(Parcel in) {
            int ordinal = in.readInt();
            PlayState[] values = PlayState.values();
            // 越界保护：跨进程脏数据或版本升级后枚举顺序变化时回退到 Idle，避免崩溃
            if (ordinal < 0 || ordinal >= values.length) {
                return Idle;
            }
            return values[ordinal];
        }

        @Override
        public PlayState[] newArray(int size) {
            return new PlayState[size];
        }
    };
}
