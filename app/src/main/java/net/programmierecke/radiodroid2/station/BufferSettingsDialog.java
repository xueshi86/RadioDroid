package net.programmierecke.radiodroid2.station;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.service.PlayerServiceUtil;

public class BufferSettingsDialog extends BottomSheetDialogFragment {

    private static final String TAG = "BufferSettingsDialog";

    private static final String ARG_STATION_UUID = "station_uuid";
    private static final String ARG_STATION_NAME = "station_name";

    // Legacy preference key prefix (for backward compatibility)
    private static final String PREFS_BUFFER_PREFIX_OLD = "station_buffer_ms_";
    // New preference key prefix
    public static final String PREFS_STRATEGY_PREFIX = "station_buffer_strategy_";

    private String stationUuid;
    private String stationName;

    private RadioGroup strategyRadioGroup;
    private RadioButton radioLight;
    private RadioButton radioEnhanced;
    private RadioButton radioExtreme;

    public static BufferSettingsDialog newInstance(@NonNull String stationUuid, @NonNull String stationName) {
        BufferSettingsDialog dialog = new BufferSettingsDialog();
        Bundle args = new Bundle();
        args.putString(ARG_STATION_UUID, stationUuid);
        args.putString(ARG_STATION_NAME, stationName);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public int getTheme() {
        return Utils.getBottomSheetDialogThemeResId(requireContext());
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            stationUuid = getArguments().getString(ARG_STATION_UUID);
            stationName = getArguments().getString(ARG_STATION_NAME);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_buffer_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Set dialog title with station name
        TextView titleText = view.findViewById(R.id.bufferDialogTitle);
        if (stationName != null) {
            titleText.setText(getString(R.string.buffer_strategy_title) + " - " + stationName);
        }

        strategyRadioGroup = view.findViewById(R.id.strategyRadioGroup);
        radioLight = view.findViewById(R.id.radioLight);
        radioEnhanced = view.findViewById(R.id.radioEnhanced);
        radioExtreme = view.findViewById(R.id.radioExtreme);

        // Load current strategy for this station
        BufferStrategy currentStrategy = getStationStrategy(requireContext(), stationUuid);
        selectStrategyRadio(currentStrategy);

        // Buttons
        view.findViewById(R.id.bufferBtnCancel).setOnClickListener(v -> dismiss());

        view.findViewById(R.id.bufferBtnApply).setOnClickListener(v -> {
            BufferStrategy selectedStrategy = getSelectedStrategy();

            saveStationStrategy(requireContext(), stationUuid, selectedStrategy);

            // Restart current playback if this station is currently playing
            restartCurrentPlaybackIfNeeded();

            dismiss();
        });
    }

    private void selectStrategyRadio(BufferStrategy strategy) {
        switch (strategy) {
            case LIGHT:
                radioLight.setChecked(true);
                break;
            case ENHANCED:
                radioEnhanced.setChecked(true);
                break;
            case EXTREME:
                radioExtreme.setChecked(true);
                break;
        }
    }

    private BufferStrategy getSelectedStrategy() {
        int checkedId = strategyRadioGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.radioEnhanced) {
            return BufferStrategy.ENHANCED;
        } else if (checkedId == R.id.radioExtreme) {
            return BufferStrategy.EXTREME;
        }
        return BufferStrategy.LIGHT;
    }

    private void restartCurrentPlaybackIfNeeded() {
        DataRadioStation currentStation = PlayerServiceUtil.getCurrentStation();
        // Only restart if the currently playing station is the one being modified
        if (currentStation != null && PlayerServiceUtil.isPlaying()
                && currentStation.StationUuid != null && currentStation.StationUuid.equals(stationUuid)) {
            PlayerServiceUtil.play(currentStation);
        }
    }

    // ==================== Static helper methods ====================

    /**
     * Get the buffer strategy for a specific station.
     * Falls back to LIGHT if no custom strategy is set.
     */
    public static BufferStrategy getStationStrategy(@NonNull Context context, @NonNull String stationUuid) {
        if (stationUuid == null || stationUuid.isEmpty()) {
            return BufferStrategy.LIGHT;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());

        // Check for new strategy preference
        String strategyKey = prefs.getString(PREFS_STRATEGY_PREFIX + stationUuid, null);
        if (strategyKey != null) {
            return BufferStrategy.fromKey(strategyKey);
        }

        // Check for legacy buffer_ms preference and migrate
        if (prefs.contains(PREFS_BUFFER_PREFIX_OLD + stationUuid)) {
            int oldBufferMs = prefs.getInt(PREFS_BUFFER_PREFIX_OLD + stationUuid, 2500);
            BufferStrategy migrated = migrateFromBufferMs(oldBufferMs);
            // Migrate to new format
            prefs.edit()
                    .putString(PREFS_STRATEGY_PREFIX + stationUuid, migrated.key)
                    .remove(PREFS_BUFFER_PREFIX_OLD + stationUuid)
                    .apply();
            return migrated;
        }

        return BufferStrategy.LIGHT;
    }

    /**
     * Save the buffer strategy for a specific station.
     */
    public static void saveStationStrategy(@NonNull Context context, @NonNull String stationUuid, @NonNull BufferStrategy strategy) {
        if (stationUuid == null || stationUuid.isEmpty()) {
            Log.w(TAG, "Cannot save strategy: stationUuid is null or empty");
            return;
        }
        String key = PREFS_STRATEGY_PREFIX + stationUuid;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        prefs.edit()
                .putString(key, strategy.key)
                .remove(PREFS_BUFFER_PREFIX_OLD + stationUuid) // Clean up legacy
                .commit(); // Use commit() to ensure immediate write
    }

    /**
     * Migrate from old buffer_ms value to a BufferStrategy.
     */
    private static BufferStrategy migrateFromBufferMs(int bufferMs) {
        if (bufferMs >= 8000) {
            return BufferStrategy.EXTREME;
        } else if (bufferMs >= 4000) {
            return BufferStrategy.ENHANCED;
        }
        return BufferStrategy.LIGHT;
    }

    // Keep backward compatibility: getStationBufferMs is still used by ExoPlayerWrapper
    // but now it derives from the strategy
    public static final int DEFAULT_BUFFER_MS = 2500;

    /**
     * @deprecated Use {@link #getStationStrategy(Context, String)} instead.
     * This method is kept for backward compatibility and returns the bufferForPlaybackMs
     * from the station's strategy.
     */
    @Deprecated
    public static int getStationBufferMs(@NonNull Context context, @NonNull String stationUuid) {
        BufferStrategy strategy = getStationStrategy(context, stationUuid);
        return strategy.bufferForPlaybackMs;
    }
}
