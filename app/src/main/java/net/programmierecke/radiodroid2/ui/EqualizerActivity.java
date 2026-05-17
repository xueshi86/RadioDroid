package net.programmierecke.radiodroid2.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.service.PlayerServiceUtil;

public class EqualizerActivity extends AppCompatActivity {

    private static final String PREF_EQ_ENABLED = "equalizer_enabled";
    private static final String PREF_EQ_PRESET = "equalizer_preset";
    private static final String PREF_BASS_BOOST_ENABLED = "bass_boost_enabled";
    private static final String PREF_BASS_BOOST_STRENGTH = "bass_boost_strength";
    private static final String PREF_BAND_LEVELS = "equalizer_band_levels";

    private static final String PREF_CACHE_NUM_BANDS = "eq_cache_num_bands";
    private static final String PREF_CACHE_CENTER_FREQS = "eq_cache_center_freqs";
    private static final String PREF_CACHE_MIN_LEVEL = "eq_cache_min_level";
    private static final String PREF_CACHE_MAX_LEVEL = "eq_cache_max_level";
    private static final String PREF_CACHE_NUM_PRESETS = "eq_cache_num_presets";
    private static final String PREF_CACHE_PRESET_NAMES = "eq_cache_preset_names";
    private static final String PREF_CACHE_PRESET_BAND_LEVELS = "eq_cache_preset_band_levels";

    private static final int PRESET_VOICE = -2;
    private static final int PRESET_MUSIC = -3;

    private static final short[] VOICE_BAND_LEVELS_MILLIDB = {
            -600, -200, 500, 700, 200
    };

    private static final short[] MUSIC_BAND_LEVELS_MILLIDB = {
            500, 200, 0, 350, 500
    };

    private static final short DEFAULT_NUM_BANDS = 5;
    private static final int[] DEFAULT_CENTER_FREQS_MILLIHZ = {60000, 230000, 910000, 3600000, 14000000};
    private static final short DEFAULT_MIN_LEVEL = -1500;
    private static final short DEFAULT_MAX_LEVEL = 1500;

    public static final String ACTION_EQ_ACTIVITY_OPENED = "net.programmierecke.radiodroid2.EQ_ACTIVITY_OPENED";
    public static final String ACTION_EQ_ACTIVITY_CLOSED = "net.programmierecke.radiodroid2.EQ_ACTIVITY_CLOSED";

    private Equalizer equalizer;
    private BassBoost bassBoost;
    private int audioSessionId = 0;
    private boolean hasLiveEqualizer = false;

    private short eqNumBands;
    private int[] eqCenterFreqs;
    private short eqMinLevel;
    private short eqMaxLevel;
    private short eqNumSystemPresets;
    private String[] eqSystemPresetNames;
    private short[][] eqSystemPresetBandLevels;

    private SwitchMaterial switchEnabled;
    private SwitchMaterial switchBassBoost;
    private SeekBar seekBarBassBoost;
    private TextView textBassBoostValue;
    private Spinner spinnerPreset;
    private LinearLayout layoutBands;
    private TextView textNotPlaying;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTheme(Utils.getThemeResId(this));

        setContentView(R.layout.activity_equalizer);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.settings_equalizer);
        toolbar.setNavigationOnClickListener(v -> finish());

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        switchEnabled = findViewById(R.id.switchEqualizerEnabled);
        switchBassBoost = findViewById(R.id.switchBassBoost);
        seekBarBassBoost = findViewById(R.id.seekBarBassBoost);
        textBassBoostValue = findViewById(R.id.textBassBoostValue);
        spinnerPreset = findViewById(R.id.spinnerPreset);
        layoutBands = findViewById(R.id.layoutBands);
        textNotPlaying = findViewById(R.id.textNotPlaying);

        audioSessionId = PlayerServiceUtil.getAudioSessionId();

        if (audioSessionId != 0) {
            try {
                equalizer = new Equalizer(0, audioSessionId);
                queryCapabilities(equalizer);
                cacheCapabilities();
                hasLiveEqualizer = true;

                try {
                    bassBoost = new BassBoost(0, audioSessionId);
                } catch (Exception e) {
                    bassBoost = null;
                }
            } catch (Exception e) {
                equalizer = null;
                bassBoost = null;
            }
        }

        if (!hasLiveEqualizer) {
            if (!queryCapabilitiesFromTempSession()) {
                loadCachedCapabilities();
            }
            textNotPlaying.setVisibility(View.VISIBLE);
            textNotPlaying.setText(R.string.equalizer_settings_saved_hint);
        }

        setupUI();

        sendBroadcast(new Intent(ACTION_EQ_ACTIVITY_OPENED));
    }

    private void queryCapabilities(Equalizer eq) {
        eqNumBands = eq.getNumberOfBands();
        eqCenterFreqs = new int[eqNumBands];
        for (short i = 0; i < eqNumBands; i++) {
            eqCenterFreqs[i] = eq.getCenterFreq(i);
        }
        short[] range = eq.getBandLevelRange();
        eqMinLevel = range[0];
        eqMaxLevel = range[1];

        eqNumSystemPresets = 0;
        try {
            eqNumSystemPresets = eq.getNumberOfPresets();
        } catch (Exception ignored) {
        }

        eqSystemPresetNames = new String[eqNumSystemPresets];
        eqSystemPresetBandLevels = new short[eqNumSystemPresets][];
        for (short i = 0; i < eqNumSystemPresets; i++) {
            try {
                eqSystemPresetNames[i] = eq.getPresetName(i);
                eq.usePreset(i);
                eqSystemPresetBandLevels[i] = new short[eqNumBands];
                for (short j = 0; j < eqNumBands; j++) {
                    eqSystemPresetBandLevels[i][j] = eq.getBandLevel(j);
                }
            } catch (Exception e) {
                eqSystemPresetNames[i] = "Preset " + (i + 1);
                eqSystemPresetBandLevels[i] = null;
            }
        }
    }

    private boolean queryCapabilitiesFromTempSession() {
        MediaPlayer tempPlayer = null;
        Equalizer tempEq = null;
        try {
            tempPlayer = new MediaPlayer();
            int tempSessionId = tempPlayer.getAudioSessionId();
            if (tempSessionId != 0) {
                tempEq = new Equalizer(0, tempSessionId);
                queryCapabilities(tempEq);
                cacheCapabilities();
                return true;
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (tempEq != null) {
                try { tempEq.release(); } catch (Exception ignored) {}
            }
            if (tempPlayer != null) {
                try { tempPlayer.release(); } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private void cacheCapabilities() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(PREF_CACHE_NUM_BANDS, eqNumBands);
        editor.putString(PREF_CACHE_CENTER_FREQS, joinInts(eqCenterFreqs));
        editor.putInt(PREF_CACHE_MIN_LEVEL, eqMinLevel);
        editor.putInt(PREF_CACHE_MAX_LEVEL, eqMaxLevel);
        editor.putInt(PREF_CACHE_NUM_PRESETS, eqNumSystemPresets);
        editor.putString(PREF_CACHE_PRESET_NAMES, joinStrings(eqSystemPresetNames));

        if (eqSystemPresetBandLevels != null && eqSystemPresetBandLevels.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < eqSystemPresetBandLevels.length; i++) {
                if (i > 0) sb.append("|");
                if (eqSystemPresetBandLevels[i] != null) {
                    for (int j = 0; j < eqSystemPresetBandLevels[i].length; j++) {
                        if (j > 0) sb.append(",");
                        sb.append(eqSystemPresetBandLevels[i][j]);
                    }
                }
            }
            editor.putString(PREF_CACHE_PRESET_BAND_LEVELS, sb.toString());
        }

        editor.apply();
    }

    private void loadCachedCapabilities() {
        eqNumBands = (short) prefs.getInt(PREF_CACHE_NUM_BANDS, DEFAULT_NUM_BANDS);

        String freqsStr = prefs.getString(PREF_CACHE_CENTER_FREQS, null);
        eqCenterFreqs = new int[eqNumBands];
        if (freqsStr != null) {
            String[] parts = freqsStr.split(",");
            for (int i = 0; i < eqNumBands && i < parts.length; i++) {
                try {
                    eqCenterFreqs[i] = Integer.parseInt(parts[i]);
                } catch (Exception e) {
                    eqCenterFreqs[i] = getDefaultCenterFreq(i);
                }
            }
        } else {
            for (int i = 0; i < eqNumBands; i++) {
                eqCenterFreqs[i] = getDefaultCenterFreq(i);
            }
        }

        eqMinLevel = (short) prefs.getInt(PREF_CACHE_MIN_LEVEL, DEFAULT_MIN_LEVEL);
        eqMaxLevel = (short) prefs.getInt(PREF_CACHE_MAX_LEVEL, DEFAULT_MAX_LEVEL);
        eqNumSystemPresets = (short) prefs.getInt(PREF_CACHE_NUM_PRESETS, 0);

        String namesStr = prefs.getString(PREF_CACHE_PRESET_NAMES, null);
        if (namesStr != null && !namesStr.isEmpty()) {
            eqSystemPresetNames = namesStr.split("\\|");
            if (eqSystemPresetNames.length > eqNumSystemPresets) {
                eqNumSystemPresets = (short) eqSystemPresetNames.length;
            }
        } else {
            eqSystemPresetNames = new String[0];
            eqNumSystemPresets = 0;
        }

        String bandLevelsStr = prefs.getString(PREF_CACHE_PRESET_BAND_LEVELS, null);
        eqSystemPresetBandLevels = new short[eqNumSystemPresets][];
        if (bandLevelsStr != null && !bandLevelsStr.isEmpty()) {
            String[] presetStrs = bandLevelsStr.split("\\|");
            for (int i = 0; i < eqNumSystemPresets && i < presetStrs.length; i++) {
                if (presetStrs[i].isEmpty()) continue;
                String[] levelStrs = presetStrs[i].split(",");
                eqSystemPresetBandLevels[i] = new short[eqNumBands];
                for (int j = 0; j < eqNumBands && j < levelStrs.length; j++) {
                    try {
                        eqSystemPresetBandLevels[i][j] = Short.parseShort(levelStrs[j]);
                    } catch (Exception e) {
                        eqSystemPresetBandLevels[i][j] = 0;
                    }
                }
            }
        }
    }

    private int getDefaultCenterFreq(int bandIndex) {
        if (bandIndex < DEFAULT_CENTER_FREQS_MILLIHZ.length) {
            return DEFAULT_CENTER_FREQS_MILLIHZ[bandIndex];
        }
        return DEFAULT_CENTER_FREQS_MILLIHZ[DEFAULT_CENTER_FREQS_MILLIHZ.length - 1];
    }

    private String joinInts(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private String joinStrings(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append("|");
            sb.append(values[i] != null ? values[i] : "");
        }
        return sb.toString();
    }

    private void setupUI() {
        boolean wasEnabled = prefs.getBoolean(PREF_EQ_ENABLED, false);
        switchEnabled.setChecked(wasEnabled);

        if (hasLiveEqualizer) {
            equalizer.setEnabled(wasEnabled);
        }

        switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(PREF_EQ_ENABLED, isChecked).apply();
            if (hasLiveEqualizer && equalizer != null) {
                equalizer.setEnabled(isChecked);
                if (bassBoost != null) {
                    bassBoost.setEnabled(isChecked && switchBassBoost.isChecked());
                }
            }
            updateControlsState(isChecked);
        });

        setupPresetSpinner();
        setupBandSliders();
        setupBassBoost();
        updateControlsState(wasEnabled);
    }

    private void setupPresetSpinner() {
        String[] presetNames = new String[eqNumSystemPresets + 3];
        presetNames[0] = getString(R.string.equalizer_custom);
        for (int i = 0; i < eqNumSystemPresets; i++) {
            presetNames[i + 1] = getLocalizedPresetName(eqSystemPresetNames[i], i);
        }
        presetNames[eqNumSystemPresets + 1] = getString(R.string.equalizer_preset_voice);
        presetNames[eqNumSystemPresets + 2] = getString(R.string.equalizer_preset_music);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.spinner_item_eq, presetNames);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_eq);
        spinnerPreset.setAdapter(adapter);

        int savedPreset = prefs.getInt(PREF_EQ_PRESET, -1);
        if (savedPreset == PRESET_VOICE) {
            spinnerPreset.setSelection(eqNumSystemPresets + 1);
        } else if (savedPreset == PRESET_MUSIC) {
            spinnerPreset.setSelection(eqNumSystemPresets + 2);
        } else if (savedPreset >= 0 && savedPreset < eqNumSystemPresets) {
            spinnerPreset.setSelection(savedPreset + 1);
            if (hasLiveEqualizer) {
                try {
                    equalizer.usePreset((short) savedPreset);
                } catch (Exception e) {
                    prefs.edit().putInt(PREF_EQ_PRESET, -1).apply();
                    spinnerPreset.setSelection(0);
                }
            }
        } else {
            if (savedPreset >= eqNumSystemPresets && savedPreset != PRESET_VOICE && savedPreset != PRESET_MUSIC) {
                prefs.edit().putInt(PREF_EQ_PRESET, -1).apply();
            }
            if (hasLiveEqualizer) {
                restoreBandLevels();
            }
        }

        final short finalNumSystemPresets = eqNumSystemPresets;
        spinnerPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    prefs.edit().putInt(PREF_EQ_PRESET, -1).apply();
                    return;
                }
                if (position == finalNumSystemPresets + 1) {
                    prefs.edit().putInt(PREF_EQ_PRESET, PRESET_VOICE).apply();
                    applyVoicePreset();
                    updateBandSliders();
                    saveBandLevels();
                    return;
                }
                if (position == finalNumSystemPresets + 2) {
                    prefs.edit().putInt(PREF_EQ_PRESET, PRESET_MUSIC).apply();
                    applyMusicPreset();
                    updateBandSliders();
                    saveBandLevels();
                    return;
                }
                short presetIndex = (short) (position - 1);
                prefs.edit().putInt(PREF_EQ_PRESET, presetIndex).apply();
                if (hasLiveEqualizer) {
                    try {
                        equalizer.usePreset(presetIndex);
                        updateBandSliders();
                        saveBandLevels();
                    } catch (Exception e) {
                        prefs.edit().putInt(PREF_EQ_PRESET, -1).apply();
                        spinnerPreset.setSelection(0);
                    }
                } else {
                    applyCachedSystemPreset(presetIndex);
                    updateBandSliders();
                    saveBandLevelsFromUI();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void applyCachedSystemPreset(int presetIndex) {
        if (presetIndex >= 0 && presetIndex < eqNumSystemPresets
                && eqSystemPresetBandLevels != null
                && eqSystemPresetBandLevels[presetIndex] != null) {
            short[] levels = eqSystemPresetBandLevels[presetIndex];
            for (int i = 0; i < eqNumBands && i < levels.length; i++) {
                View child = layoutBands.getChildAt(i);
                if (child != null) {
                    SeekBar seekBar = child.findViewById(R.id.seekBarBand);
                    TextView textLevel = child.findViewById(R.id.textBandLevel);
                    if (seekBar != null && textLevel != null) {
                        seekBar.setProgress(levels[i] - eqMinLevel);
                        textLevel.setText(formatLevel(levels[i]));
                    }
                }
            }
        }
    }

    private void setupBandSliders() {
        layoutBands.removeAllViews();

        short[] initialLevels = getInitialBandLevels();

        for (short i = 0; i < eqNumBands; i++) {
            View bandView = getLayoutInflater().inflate(R.layout.item_equalizer_band, layoutBands, false);

            TextView textFreq = bandView.findViewById(R.id.textBandFreq);
            TextView textLevel = bandView.findViewById(R.id.textBandLevel);
            SeekBar seekBar = bandView.findViewById(R.id.seekBarBand);

            int centerFreq = eqCenterFreqs[i];
            String freqText;
            if (centerFreq >= 1000000) {
                freqText = String.format("%.1f kHz", centerFreq / 1000000.0);
            } else {
                freqText = String.format("%.0f Hz", centerFreq / 1000.0);
            }
            textFreq.setText(freqText);

            seekBar.setMax(eqMaxLevel - eqMinLevel);
            short level = initialLevels[i];
            seekBar.setProgress(level - eqMinLevel);
            textLevel.setText(formatLevel(level));

            final short bandIndex = i;
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        short newLevel = (short) (progress + eqMinLevel);
                        if (hasLiveEqualizer && equalizer != null) {
                            try {
                                equalizer.setBandLevel(bandIndex, newLevel);
                            } catch (Exception ignored) {
                            }
                        }
                        textLevel.setText(formatLevel(newLevel));
                        spinnerPreset.setSelection(0);
                        prefs.edit().putInt(PREF_EQ_PRESET, -1).apply();
                        saveBandLevelsFromUI();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });

            layoutBands.addView(bandView);
        }
    }

    private short[] getInitialBandLevels() {
        int savedPreset = prefs.getInt(PREF_EQ_PRESET, -1);

        if (savedPreset == PRESET_VOICE) {
            return computeVoiceBandLevels();
        } else if (savedPreset == PRESET_MUSIC) {
            return computeMusicBandLevels();
        } else if (savedPreset >= 0 && savedPreset < eqNumSystemPresets
                && eqSystemPresetBandLevels != null
                && eqSystemPresetBandLevels[savedPreset] != null) {
            short[] levels = new short[eqNumBands];
            short[] cached = eqSystemPresetBandLevels[savedPreset];
            for (int i = 0; i < eqNumBands && i < cached.length; i++) {
                levels[i] = cached[i];
            }
            return levels;
        } else if (hasLiveEqualizer && equalizer != null) {
            short[] levels = new short[eqNumBands];
            for (short i = 0; i < eqNumBands; i++) {
                try {
                    levels[i] = equalizer.getBandLevel(i);
                } catch (Exception e) {
                    levels[i] = 0;
                }
            }
            return levels;
        } else {
            return loadSavedBandLevels();
        }
    }

    private short[] computeVoiceBandLevels() {
        short[] levels = new short[eqNumBands];
        for (int i = 0; i < eqNumBands; i++) {
            levels[i] = calculateVoiceBandLevel(eqCenterFreqs[i], i, eqNumBands);
        }
        return levels;
    }

    private short[] computeMusicBandLevels() {
        short[] levels = new short[eqNumBands];
        for (int i = 0; i < eqNumBands; i++) {
            levels[i] = calculateMusicBandLevel(eqCenterFreqs[i], i, eqNumBands);
        }
        return levels;
    }

    private short[] loadSavedBandLevels() {
        short[] levels = new short[eqNumBands];
        String levelsStr = prefs.getString(PREF_BAND_LEVELS, null);
        if (levelsStr != null) {
            String[] parts = levelsStr.split(",");
            for (int i = 0; i < eqNumBands && i < parts.length; i++) {
                try {
                    levels[i] = Short.parseShort(parts[i]);
                } catch (Exception e) {
                    levels[i] = 0;
                }
            }
        }
        return levels;
    }

    private void applyVoicePreset() {
        short[] levels = computeVoiceBandLevels();
        if (hasLiveEqualizer && equalizer != null) {
            for (short i = 0; i < eqNumBands; i++) {
                try {
                    equalizer.setBandLevel(i, levels[i]);
                } catch (Exception ignored) {
                }
            }
        }
        applyLevelsToSliders(levels);
    }

    private void applyLevelsToSliders(short[] levels) {
        for (int i = 0; i < eqNumBands && i < levels.length; i++) {
            View child = layoutBands.getChildAt(i);
            if (child != null) {
                SeekBar seekBar = child.findViewById(R.id.seekBarBand);
                TextView textLevel = child.findViewById(R.id.textBandLevel);
                if (seekBar != null && textLevel != null) {
                    seekBar.setProgress(levels[i] - eqMinLevel);
                    textLevel.setText(formatLevel(levels[i]));
                }
            }
        }
    }

    private short calculateVoiceBandLevel(int centerFreqMilliHz, int bandIndex, int totalBands) {
        if (bandIndex < VOICE_BAND_LEVELS_MILLIDB.length) {
            return VOICE_BAND_LEVELS_MILLIDB[bandIndex];
        }
        if (centerFreqMilliHz < 200000) {
            return -600;
        } else if (centerFreqMilliHz < 500000) {
            return -200;
        } else if (centerFreqMilliHz < 2000000) {
            return 500;
        } else if (centerFreqMilliHz < 5000000) {
            return 700;
        } else {
            return 200;
        }
    }

    private void applyMusicPreset() {
        short[] levels = computeMusicBandLevels();
        if (hasLiveEqualizer && equalizer != null) {
            for (short i = 0; i < eqNumBands; i++) {
                try {
                    equalizer.setBandLevel(i, levels[i]);
                } catch (Exception ignored) {
                }
            }
        }
        applyLevelsToSliders(levels);
    }

    private short calculateMusicBandLevel(int centerFreqMilliHz, int bandIndex, int totalBands) {
        if (bandIndex < MUSIC_BAND_LEVELS_MILLIDB.length) {
            return MUSIC_BAND_LEVELS_MILLIDB[bandIndex];
        }
        if (centerFreqMilliHz < 200000) {
            return 500;
        } else if (centerFreqMilliHz < 500000) {
            return 200;
        } else if (centerFreqMilliHz < 2000000) {
            return 0;
        } else if (centerFreqMilliHz < 5000000) {
            return 350;
        } else {
            return 500;
        }
    }

    private void restoreBandLevels() {
        if (equalizer == null) return;
        String levelsStr = prefs.getString(PREF_BAND_LEVELS, null);
        if (levelsStr == null) return;
        String[] parts = levelsStr.split(",");
        for (short i = 0; i < eqNumBands && i < parts.length; i++) {
            try {
                short level = Short.parseShort(parts[i]);
                equalizer.setBandLevel(i, level);
            } catch (Exception ignored) {
            }
        }
    }

    private void updateBandSliders() {
        for (int i = 0; i < eqNumBands; i++) {
            View child = layoutBands.getChildAt(i);
            if (child != null) {
                SeekBar seekBar = child.findViewById(R.id.seekBarBand);
                TextView textLevel = child.findViewById(R.id.textBandLevel);
                if (seekBar != null && textLevel != null) {
                    short level;
                    if (hasLiveEqualizer && equalizer != null) {
                        try {
                            level = equalizer.getBandLevel((short) i);
                        } catch (Exception e) {
                            level = 0;
                        }
                    } else {
                        level = (short) (seekBar.getProgress() + eqMinLevel);
                    }
                    seekBar.setProgress(level - eqMinLevel);
                    textLevel.setText(formatLevel(level));
                }
            }
        }
    }

    private void saveBandLevels() {
        if (hasLiveEqualizer && equalizer != null) {
            StringBuilder sb = new StringBuilder();
            for (short i = 0; i < eqNumBands; i++) {
                try {
                    if (i > 0) sb.append(",");
                    sb.append(equalizer.getBandLevel(i));
                } catch (Exception ignored) {
                }
            }
            prefs.edit().putString(PREF_BAND_LEVELS, sb.toString()).apply();
        } else {
            saveBandLevelsFromUI();
        }
    }

    private void saveBandLevelsFromUI() {
        StringBuilder sb = new StringBuilder();
        int childCount = layoutBands.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = layoutBands.getChildAt(i);
            SeekBar seekBar = child.findViewById(R.id.seekBarBand);
            if (seekBar != null) {
                if (i > 0) sb.append(",");
                short level = (short) (seekBar.getProgress() + eqMinLevel);
                sb.append(level);
            }
        }
        prefs.edit().putString(PREF_BAND_LEVELS, sb.toString()).apply();
    }

    private void setupBassBoost() {
        boolean wasBassBoostEnabled = prefs.getBoolean(PREF_BASS_BOOST_ENABLED, false);
        int savedStrength = prefs.getInt(PREF_BASS_BOOST_STRENGTH, 0);

        switchBassBoost.setChecked(wasBassBoostEnabled);

        if (hasLiveEqualizer && bassBoost != null) {
            bassBoost.setEnabled(wasBassBoostEnabled && switchEnabled.isChecked());
            if (savedStrength > 0 && wasBassBoostEnabled) {
                try {
                    bassBoost.setStrength((short) savedStrength);
                } catch (Exception ignored) {
                }
            }
        }

        seekBarBassBoost.setProgress(savedStrength);
        textBassBoostValue.setText(String.valueOf(savedStrength / 10));

        switchBassBoost.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(PREF_BASS_BOOST_ENABLED, isChecked).apply();
            if (hasLiveEqualizer && bassBoost != null) {
                bassBoost.setEnabled(isChecked && switchEnabled.isChecked());
            }
            seekBarBassBoost.setEnabled(isChecked && switchEnabled.isChecked());
        });

        seekBarBassBoost.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    if (hasLiveEqualizer && bassBoost != null) {
                        try {
                            bassBoost.setStrength((short) progress);
                        } catch (Exception ignored) {
                        }
                    }
                    textBassBoostValue.setText(String.valueOf(progress / 10));
                    prefs.edit().putInt(PREF_BASS_BOOST_STRENGTH, progress).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void updateControlsState(boolean enabled) {
        spinnerPreset.setEnabled(enabled);
        int childCount = layoutBands.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = layoutBands.getChildAt(i);
            SeekBar seekBar = child.findViewById(R.id.seekBarBand);
            if (seekBar != null) {
                seekBar.setEnabled(enabled);
            }
        }
        switchBassBoost.setEnabled(enabled);
        seekBarBassBoost.setEnabled(enabled && switchBassBoost.isChecked());
    }

    private String getLocalizedPresetName(String systemName, int index) {
        if (systemName == null || systemName.trim().isEmpty()) {
            return getString(R.string.eq_preset_unknown, index);
        }
        switch (systemName.toLowerCase().replace(" ", "_").replace("-", "_")) {
            case "normal":
                return getString(R.string.eq_preset_normal);
            case "classical":
                return getString(R.string.eq_preset_classical);
            case "dance":
                return getString(R.string.eq_preset_dance);
            case "flat":
                return getString(R.string.eq_preset_flat);
            case "folk":
            case "acoustic":
                return getString(R.string.eq_preset_folk);
            case "heavy_metal":
            case "heavymetal":
            case "metal":
                return getString(R.string.eq_preset_heavy_metal);
            case "hip_hop":
            case "hiphop":
            case "rap":
                return getString(R.string.eq_preset_hip_hop);
            case "jazz":
                return getString(R.string.eq_preset_jazz);
            case "pop":
                return getString(R.string.eq_preset_pop);
            case "rock":
                return getString(R.string.eq_preset_rock);
            case "latin":
                return getString(R.string.eq_preset_latin);
            case "blues":
                return getString(R.string.eq_preset_blues);
            case "electronic":
            case "electronic_music":
            case "edm":
                return getString(R.string.eq_preset_electronic);
            case "r&b":
            case "rnb":
            case "r_and_b":
                return getString(R.string.eq_preset_rnb);
            case "country":
                return getString(R.string.eq_preset_country);
            default:
                return systemName;
        }
    }

    private String formatLevel(short level) {
        float db = level / 100.0f;
        return String.format("%.1f dB", db);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveBandLevels();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveBandLevels();
        if (equalizer != null) {
            equalizer.release();
            equalizer = null;
        }
        if (bassBoost != null) {
            bassBoost.release();
            bassBoost = null;
        }
        sendBroadcast(new Intent(ACTION_EQ_ACTIVITY_CLOSED));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
