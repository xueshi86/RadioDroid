package net.programmierecke.radiodroid2;

import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class FragmentAbout extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_about,null);

        TextView aTextVersion = (TextView) view.findViewById(R.id.about_version);
        if (aTextVersion != null) {
            // 同时标明安装的是哪个变体，便于用户反馈问题时区分 Free / Play（Issue #56）
            aTextVersion.setText(getString(R.string.about_version, RadioDroidApp.getVersionLabel()));
        }

        TextView aTextFork = (TextView) view.findViewById(R.id.about_fork);
        if (aTextFork != null) {
            aTextFork.setText(getString(R.string.about_fork_description));
        }

        TextView aTextGithub = (TextView) view.findViewById(R.id.about_github);
        if (aTextGithub != null) {
            aTextGithub.setText(getString(R.string.about_github));
        }

        return view;
    }
}
