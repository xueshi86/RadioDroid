package net.programmierecke.radiodroid2;

import android.content.Context;
import android.os.Bundle;

import androidx.fragment.app.Fragment;

public class FragmentBase extends Fragment {
    private static final String TAG = "FragmentBase";

    private boolean isCreated = false;

    public FragmentBase() {
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        isCreated = true;
    }

    protected void RefreshListGui() {
    }
}