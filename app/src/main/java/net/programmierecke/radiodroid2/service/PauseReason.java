package net.programmierecke.radiodroid2.service;

import android.os.Parcel;
import android.os.Parcelable;

public enum PauseReason implements Parcelable {

    NONE,
    BECAME_NOISY,
    FOCUS_LOSS,
    FOCUS_LOSS_TRANSIENT,
    USER;

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(ordinal());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<PauseReason> CREATOR = new Parcelable.Creator<PauseReason>() {
        @Override
        public PauseReason createFromParcel(Parcel in) {
            int ordinal = in.readInt();
            PauseReason[] values = PauseReason.values();
            if (ordinal < 0 || ordinal >= values.length) {
                return NONE;
            }
            return values[ordinal];
        }

        @Override
        public PauseReason[] newArray(int size) {
            return new PauseReason[size];
        }
    };
}
