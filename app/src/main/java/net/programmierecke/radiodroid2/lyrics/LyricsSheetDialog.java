package net.programmierecke.radiodroid2.lyrics;

import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;

import java.util.regex.Pattern;

public class LyricsSheetDialog extends BottomSheetDialogFragment {

    public static final String FRAGMENT_TAG = "lyrics_sheet_dialog_fragment";

    private static final String ARG_ARTIST = "arg_artist";
    private static final String ARG_TRACK = "arg_track";
    private static final String ARG_DURATION = "arg_duration";
    private static final String STATE_SEARCH_FINISHED = "state_search_finished";
    private static final String STATE_LYRICS_TEXT = "state_lyrics_text";
    private static final String STATE_SOURCE_TEXT = "state_source_text";

    private static final Pattern LRC_META_TAG = Pattern.compile("\\[(ar|ti|al|by|offset|re|ve|au):[^\\]]*]");
    private static final Pattern LRC_TIME_TAG = Pattern.compile("\\[\\d{1,2}:\\d{1,2}(\\.\\d{1,3})?]");

    private TextView textViewTitle;
    private TextView textViewLyrics;
    private TextView textViewSource;
    private TextView textViewStatus;
    private ProgressBar progressBar;

    private boolean searchFinished;
    private String lyricsText;
    private String sourceText;

    public static LyricsSheetDialog newInstance(@Nullable String artist, @NonNull String track, @Nullable Integer durationSeconds) {
        Bundle args = new Bundle();
        args.putString(ARG_ARTIST, artist == null ? "" : artist);
        args.putString(ARG_TRACK, track);
        if (durationSeconds != null) {
            args.putInt(ARG_DURATION, durationSeconds);
        }

        LyricsSheetDialog dialog = new LyricsSheetDialog();
        dialog.setArguments(args);
        return dialog;
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

        View view = inflater.inflate(R.layout.dialog_lyrics_sheet, container, false);

        textViewTitle = view.findViewById(R.id.textViewLyricsTitle);
        textViewLyrics = view.findViewById(R.id.textViewLyrics);
        textViewSource = view.findViewById(R.id.textViewLyricsSource);
        textViewStatus = view.findViewById(R.id.textViewLyricsStatus);
        progressBar = view.findViewById(R.id.progressBarLyrics);

        textViewLyrics.setMovementMethod(ScrollingMovementMethod.getInstance());

        final String artist = requireArguments().getString(ARG_ARTIST, "");
        final String track = requireArguments().getString(ARG_TRACK, "");
        final Integer duration = requireArguments().containsKey(ARG_DURATION)
                ? requireArguments().getInt(ARG_DURATION)
                : null;

        textViewTitle.setText(formatTitle(artist, track));

        if (savedInstanceState != null) {
            searchFinished = savedInstanceState.getBoolean(STATE_SEARCH_FINISHED);
            lyricsText = savedInstanceState.getString(STATE_LYRICS_TEXT);
            sourceText = savedInstanceState.getString(STATE_SOURCE_TEXT);
        }

        applyState();

        if (!searchFinished) {
            LyricsRepository repository = ((RadioDroidApp) requireActivity().getApplication()).getLyricsRepository();
            repository.fetchLyrics(artist, track, duration, new LyricsRepository.Callback() {
                @Override
                public void onLyricsFound(@NonNull LyricsResult result) {
                    if (!isAdded()) {
                        return;
                    }
                    searchFinished = true;
                    if (result.instrumental) {
                        lyricsText = getString(R.string.lyrics_instrumental);
                        sourceText = formatSource(result.sourceId);
                    } else {
                        lyricsText = extractReadableLyrics(result);
                        sourceText = TextUtils.isEmpty(lyricsText) ? null : formatSource(result.sourceId);
                    }
                    textViewTitle.setText(formatTitle(result.artist, result.track));
                    applyState();
                }

                @Override
                public void onLyricsNotFound() {
                    if (!isAdded()) {
                        return;
                    }
                    searchFinished = true;
                    lyricsText = null;
                    sourceText = null;
                    applyState();
                }
            });
        }

        return view;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_SEARCH_FINISHED, searchFinished);
        outState.putString(STATE_LYRICS_TEXT, lyricsText);
        outState.putString(STATE_SOURCE_TEXT, sourceText);
    }

    private void applyState() {
        if (!searchFinished) {
            progressBar.setVisibility(View.VISIBLE);
            textViewLyrics.setVisibility(View.GONE);
            textViewSource.setVisibility(View.GONE);
            textViewStatus.setVisibility(View.GONE);
            return;
        }

        progressBar.setVisibility(View.GONE);
        if (lyricsText != null) {
            textViewLyrics.setVisibility(View.VISIBLE);
            textViewLyrics.setText(lyricsText);
            if (sourceText != null) {
                textViewSource.setVisibility(View.VISIBLE);
                textViewSource.setText(sourceText);
            } else {
                textViewSource.setVisibility(View.GONE);
            }
            textViewStatus.setVisibility(View.GONE);
        } else {
            textViewLyrics.setVisibility(View.GONE);
            textViewSource.setVisibility(View.GONE);
            textViewStatus.setVisibility(View.VISIBLE);
            textViewStatus.setText(R.string.lyrics_not_found);
        }
    }

    @NonNull
    private String formatTitle(@Nullable String artist, @NonNull String track) {
        if (TextUtils.isEmpty(artist)) {
            return track;
        }
        return artist + " - " + track;
    }

    @Nullable
    private String extractReadableLyrics(@NonNull LyricsResult result) {
        String text = result.plainLyrics;
        if (TextUtils.isEmpty(text) && !TextUtils.isEmpty(result.syncedLyrics)) {
            text = stripLrcTimeTags(result.syncedLyrics);
        }
        return TextUtils.isEmpty(text) ? null : text.trim();
    }

    @NonNull
    private String stripLrcTimeTags(@NonNull String lrc) {
        String stripped = LRC_META_TAG.matcher(lrc).replaceAll("");
        stripped = LRC_TIME_TAG.matcher(stripped).replaceAll("");
        return stripped.replaceAll("\\n{3,}", "\n\n").trim();
    }

    @NonNull
    private String formatSource(@NonNull String sourceId) {
        String name;
        if (NetEaseProvider.ID.equals(sourceId)) {
            name = getString(R.string.lyrics_source_netease);
        } else if (LrclibProvider.ID.equals(sourceId)) {
            name = "LRCLIB";
        } else {
            name = sourceId;
        }
        return getString(R.string.lyrics_source_format, name);
    }
}
