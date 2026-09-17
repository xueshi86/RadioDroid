package net.programmierecke.radiodroid2.history;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.squareup.picasso.Picasso;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.lyrics.LyricsRepository;
import net.programmierecke.radiodroid2.lyrics.LyricsSheetDialog;

import java.text.DateFormat;
import java.util.Objects;

public class TrackHistoryInfoDialog extends BottomSheetDialogFragment {

    public static final String FRAGMENT_TAG = "tracks_history_info_dialog_fragment";

    // QuickLyric 及其兼容分支在 Manifest 中注册的歌词查询协议，
    // 任何兼容 fork 均可响应；仅当用户在设置中选择“外部歌词应用”时使用
    private static final String ACTION_GET_LYRICS = "com.geecko.QuickLyric.getLyrics";

    private final TrackHistoryEntry historyEntry;

    public TrackHistoryInfoDialog(TrackHistoryEntry historyEntry) {
        this.historyEntry = historyEntry;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, Utils.getBottomSheetDialogThemeResId(requireContext()));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        setRetainInstance(true);

        View view = inflater.inflate(R.layout.dialog_track_history_details, container, false);

        AppCompatImageView imageViewTrackArt = view.findViewById(R.id.imageViewTrackArt);
        TextView textViewDate = view.findViewById(R.id.textViewDate);
        TextView textViewDuration = view.findViewById(R.id.textViewDuration);
        AppCompatButton btnLyrics = view.findViewById(R.id.btnViewLyrics);
        AppCompatButton btnCopyInfo = view.findViewById(R.id.btnCopyTrackInfo);

        Resources resource = requireContext().getResources();
        final float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200, resource.getDisplayMetrics());
        Picasso.get()
                .load(historyEntry.artUrl)
                .placeholder(AppCompatResources.getDrawable(getContext(), R.mipmap.ic_launcher))
                .resize((int) px, 0)
                .into(imageViewTrackArt);

        // TODO: Icons for date and duration

        textViewDate.setText(DateFormat.getDateInstance().format(historyEntry.startTime));

        if (historyEntry.endTime.after(historyEntry.startTime)) {
            String elapsedTime = DateUtils.formatElapsedTime((historyEntry.endTime.getTime() - historyEntry.startTime.getTime()) / 1000);
            textViewDuration.setText(elapsedTime);
        } else {
            textViewDuration.setText("");
        }

        // 获取本地化的艺术家和曲名
        final String artistName;
        final String trackName;
        if ("Unknown Artist".equals(historyEntry.artist)) {
            artistName = getString(R.string.unknown_artist);
        } else {
            artistName = historyEntry.artist;
        }
        if ("Unknown Track".equals(historyEntry.track)) {
            trackName = getString(R.string.unknown_track);
        } else {
            trackName = historyEntry.track;
        }

        btnLyrics.setOnClickListener(v -> {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            String mode = prefs.getString(LyricsRepository.PREF_LYRICS_SOURCE_MODE, LyricsRepository.LYRICS_SOURCE_MODE_INTERNAL);

            if (LyricsRepository.LYRICS_SOURCE_MODE_EXTERNAL.equals(mode)) {
                if (hasLyricsAppHandler()) {
                    // 传递原始数据给歌词应用
                    try {
                        getContext().startActivity(new Intent(ACTION_GET_LYRICS)
                                .putExtra("TAGS", new String[]{historyEntry.artist, historyEntry.track}));
                    } catch (ActivityNotFoundException ignored) {
                    }
                } else {
                    Toast.makeText(getContext(), R.string.lyrics_no_external_app, Toast.LENGTH_LONG).show();
                }
                return;
            }

            Integer durationSeconds = null;
            if (historyEntry.endTime.after(historyEntry.startTime)) {
                durationSeconds = (int) ((historyEntry.endTime.getTime() - historyEntry.startTime.getTime()) / 1000);
            }
            LyricsSheetDialog.newInstance(historyEntry.artist, historyEntry.track, durationSeconds)
                    .show(getParentFragmentManager(), LyricsSheetDialog.FRAGMENT_TAG);
        });

        btnCopyInfo.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("Track info", String.format("%s %s", artistName, trackName));
                clipboard.setPrimaryClip(clip);

                CharSequence toastText = getContext().getResources().getText(R.string.notify_track_info_copied);
                Toast.makeText(getContext().getApplicationContext(), toastText, Toast.LENGTH_SHORT).show();
            } else {
                //Log.e(TAG, "Clipboard is NULL!");
                // TODO: toast general error
            }
        });

        return view;
    }

    private boolean hasLyricsAppHandler() {
        PackageManager pm = requireContext().getPackageManager();
        return !pm.queryIntentActivities(new Intent(ACTION_GET_LYRICS), PackageManager.MATCH_DEFAULT_ONLY).isEmpty();
    }
}
