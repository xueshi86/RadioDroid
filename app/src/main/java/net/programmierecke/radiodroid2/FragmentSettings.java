package net.programmierecke.radiodroid2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.Manifest;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.Preference.OnPreferenceClickListener;
import androidx.preference.PreferenceScreen;

import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial;
import com.mikepenz.iconics.typeface.library.googlematerial.GoogleMaterial;
import com.bytehamster.lib.preferencesearch.SearchConfiguration;
import com.bytehamster.lib.preferencesearch.SearchPreference;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.programmierecke.radiodroid2.interfaces.IApplicationSelected;
import net.programmierecke.radiodroid2.proxy.ProxySettingsDialog;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.service.DatabaseUpdateManager;
import net.programmierecke.radiodroid2.service.DatabaseUpdateWorker;
import net.programmierecke.radiodroid2.service.PlayerServiceUtil;
import net.programmierecke.radiodroid2.ui.DatabaseUpdateProgressDialog;

import static net.programmierecke.radiodroid2.ActivityMain.FRAGMENT_FROM_BACKSTACK;
import static net.programmierecke.radiodroid2.service.PlayerService.PLAYER_SERVICE_TIMER_FINISHED;

import android.os.PowerManager;

public class FragmentSettings extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener, IApplicationSelected, PreferenceFragmentCompat.OnPreferenceStartScreenCallback  {
    
    private DatabaseUpdateProgressDialog updateDialog;
    private ActivityResultLauncher<String[]> filePickerLauncher;
    private ActivityResultLauncher<Intent> exportFileLauncher;
    private BroadcastReceiver timerFinishedReceiver;
    private BroadcastReceiver databaseUpdatedReceiver;
    private ActivityResultLauncher<String> bluetoothPermissionLauncher;
    private boolean dialogOpenedFromCheckbox = false;

    public static FragmentSettings openNewSettingsSubFragment(ActivityMain activity, String key) {
        FragmentSettings f = new FragmentSettings();
        Bundle args = new Bundle();
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, key);
        f.setArguments(args);
        FragmentTransaction fragmentTransaction = activity.getSupportFragmentManager().beginTransaction();
        fragmentTransaction.replace(R.id.containerView, f).addToBackStack(String.valueOf(FRAGMENT_FROM_BACKSTACK)).commit();
        return f;
    }
    
    /**
     * 检查是否有正在进行的数据库更新，如果有则恢复显示进度对话框
     */
    private void checkAndRestoreUpdateDialog() {
        Log.d("FragmentSettings", "checkAndRestoreUpdateDialog called");
        
        // 检查是否有正在进行的更新
        boolean isUpdating = DatabaseUpdateManager.isUpdating(requireContext());
        Log.d("FragmentSettings", "DatabaseUpdateManager.isUpdating() returned: " + isUpdating);
        
        // 只有在真正有更新进行时才恢复显示对话框
        if (isUpdating) {
            Log.d("FragmentSettings", "Restoring dialog - isUpdating: " + isUpdating);
            // 如果有正在进行的更新，显示进度对话框
            try {
                // 先检查是否已有对话框实例
                if (updateDialog == null) {
                    Log.d("FragmentSettings", "Creating new DatabaseUpdateProgressDialog");
                    updateDialog = new DatabaseUpdateProgressDialog(requireContext());
                } else {
                    Log.d("FragmentSettings", "Reusing existing DatabaseUpdateProgressDialog, isShowing=" + updateDialog.isShowing());
                }
                
                // 如果对话框未显示或已隐藏，重新显示
                if (!updateDialog.isShowing()) {
                    Log.d("FragmentSettings", "Dialog is not showing, calling show()");
                    // 确保在主线程中显示对话框
                    if (isAdded() && getActivity() != null && !getActivity().isFinishing() && !getActivity().isDestroyed()) {
                        // 添加一个小延迟，确保UI准备好
                        new Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    // 再次检查所有条件，确保状态没有改变
                                    Log.d("FragmentSettings", "In delayed runnable: updateDialog.isShowing=" + (updateDialog != null ? updateDialog.isShowing() : "null"));
                                    
                                    // 只有在真正有更新进行时才显示对话框
                                    if (updateDialog != null && DatabaseUpdateManager.isUpdating(requireContext()) && 
                                        isAdded() && getActivity() != null && !getActivity().isFinishing() && !getActivity().isDestroyed()) {
                                        updateDialog.show();
                                        Log.d("FragmentSettings", "Dialog shown successfully");
                                    } else {
                                        Log.d("FragmentSettings", "Cannot show dialog: conditions not met - updateDialog=" + (updateDialog != null ? "not null" : "null") + 
                                                  ", isUpdating=" + DatabaseUpdateManager.isUpdating(requireContext()) + 
                                                  ", isAdded=" + isAdded() + 
                                                  ", activity=" + (getActivity() != null ? "not null" : "null"));
                                    }
                                } catch (Exception e) {
                                    Log.e("FragmentSettings", "Error showing dialog in delayed runnable", e);
                                }
                            }
                        }, 500); // 增加延迟到500毫秒，确保UI完全准备好
                    } else {
                        Log.d("FragmentSettings", "Cannot show dialog: fragment not added or activity not available - isAdded=" + isAdded() + 
                                  ", activity=" + (getActivity() != null ? "not null" : "null"));
                    }
                } else {
                    Log.d("FragmentSettings", "Dialog is already showing");
                }
            } catch (Exception e) {
                Log.e("FragmentSettings", "Error showing dialog", e);
            }
        } else {
            Log.d("FragmentSettings", "No database update in progress");
            // 如果没有更新在进行，清理对话框
            if (updateDialog != null) {
                if (updateDialog.isShowing()) {
                    updateDialog.dismiss();
                }
                updateDialog = null;
            }
        }
    }

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat preferenceFragmentCompat,
                                           PreferenceScreen preferenceScreen) {
        openNewSettingsSubFragment((ActivityMain) getActivity(), preferenceScreen.getKey());
        return true;
    }

    private boolean isToplevel() {
        return getPreferenceScreen() == null || getPreferenceScreen().getKey().equals("pref_toplevel");
    }

    private void refreshToplevelIcons() {
        findPreference("shareapp_package").setSummary(getAppDisplayName(getPreferenceManager().getSharedPreferences().getString("shareapp_package", "")));
        findPreference("pref_category_ui").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon2.cmd_monitor));
        findPreference("pref_category_startup").setIcon(Utils.IconicsIcon(getContext(), GoogleMaterial.Icon.gmd_flight_takeoff));
        findPreference("pref_category_interaction").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon.cmd_gesture_tap));
        findPreference("pref_category_player").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon2.cmd_play));
        findPreference("pref_category_alarm").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon.cmd_clock_outline));
        findPreference("pref_category_connectivity").setIcon(Utils.IconicsIcon(getContext(), GoogleMaterial.Icon.gmd_import_export));
        findPreference("pref_category_recordings").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon2.cmd_record_rec));
        findPreference("pref_category_mpd").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon2.cmd_speaker_wireless));
        findPreference("pref_category_local_database_update").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon2.cmd_refresh));
        findPreference("pref_category_other").setIcon(Utils.IconicsIcon(getContext(), CommunityMaterial.Icon2.cmd_information_outline));
    }

    private void refreshToolbar() {
        ActivityMain activity = (ActivityMain) getActivity();
        final Toolbar myToolbar = activity.getToolbar(); //findViewById(R.id.my_awesome_toolbar);

        if (myToolbar == null || getPreferenceScreen() == null)
            return;

        myToolbar.setTitle(getPreferenceScreen().getTitle());

        if (Utils.bottomNavigationEnabled(activity)) {
            if (isToplevel()) {
                activity.getSupportActionBar().setDisplayHomeAsUpEnabled(false);
                activity.getSupportActionBar().setDisplayShowHomeEnabled(false);
                myToolbar.setNavigationOnClickListener(v -> activity.onBackPressed());
            } else {
                activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                activity.getSupportActionBar().setDisplayShowHomeEnabled(true);
                myToolbar.setNavigationOnClickListener(v -> activity.onBackPressed());
            }
        }
    }

    @Override
    public void onCreatePreferences(Bundle bundle, String s) {
        setPreferencesFromResource(R.xml.preferences, s);
        
        // 初始化文件选择器（使用OpenDocument替代GetContent，兼容Android 13+）
        filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    importDatabase(uri);
                }
            }
        );
        
        // 初始化导出文件选择器（使用CreateDocument，兼容Android 10+ Scoped Storage）
        exportFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        exportDatabaseToUri(uri);
                    }
                }
            }
        );

        bluetoothPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                updateBluetoothPermissionState();
                if (!granted) {
                    SharedPreferences.Editor editor = getPreferenceManager().getSharedPreferences().edit();
                    editor.putBoolean("pause_on_bluetooth_disconnect", false);
                    editor.putBoolean("close_on_bluetooth_disconnect", false);
                    editor.putBoolean("auto_resume_on_bluetooth_a2dp_connection", false);
                    editor.apply();
                }
            }
        );
        
        refreshToolbar();
        if (s == null) {
            refreshToplevelIcons();
            SearchPreference searchPreference = (SearchPreference) findPreference("searchPreference");
            SearchConfiguration config = searchPreference.getSearchConfiguration();
            config.setActivity((AppCompatActivity) getActivity());
            config.index(R.xml.preferences);
        } else if (s.equals("pref_category_player")) {
            findPreference("equalizer").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Intent intent = new Intent(getContext(), net.programmierecke.radiodroid2.ui.EqualizerActivity.class);
                    startActivity(intent);
                    return true;
                }
            });

            setupBluetoothPermissionPreference();


        } else if (s.equals("pref_category_connectivity")) {
            //final ListPreference servers = (ListPreference) findPreference("radiobrowser_server");
            //updateDnsList(servers);

            findPreference("settings_proxy").setOnPreferenceClickListener(new OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    ProxySettingsDialog proxySettingsDialog = new ProxySettingsDialog();
                    proxySettingsDialog.setCancelable(true);
                    proxySettingsDialog.show(getFragmentManager(), "");
                    return false;
                }
            });

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
                findPreference("settings_retry_timeout").setVisible(false);
                findPreference("settings_retry_delay").setVisible(false);
            }
        } else if (s.equals("pref_category_mpd")) {
            findPreference("mpd_servers_viewer").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    RadioDroidApp radioDroidApp = (RadioDroidApp) requireActivity().getApplication();
                    Utils.showMpdServersDialog(radioDroidApp, requireActivity().getSupportFragmentManager(), null);
                    return false;
                }
            });
        } else if (s.equals("pref_category_local_database_update")) {
            // 不在这里调用updateDatabaseStatusOnLoad()，而是在onResume中调用
            // updateDatabaseStatusOnLoad();
            
            findPreference("update_local_database").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    startDatabaseUpdate();
                    return false;
                }
            });

            findPreference("check_network_connection").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    // First check if there are saved results
                    NetworkCheckResults savedResultsData = loadNetworkCheckResults();
                    
                    if (savedResultsData != null) {
                        // There are saved results, ask user if they want to view them or run a new test
                        androidx.appcompat.app.AlertDialog.Builder choiceBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
                        choiceBuilder.setTitle(R.string.network_check_title);
                        
                        // Calculate when the test was performed
                        String timeString = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            .format(new java.util.Date(savedResultsData.timestamp));
                        
                        choiceBuilder.setMessage(getString(R.string.network_check_saved_results, timeString));
                        
                        choiceBuilder.setPositiveButton(R.string.network_check_view_saved, (dialog, which) -> {
                            showNetworkConnectionResults(savedResultsData.results, savedResultsData.timestamp);
                        });
                        
                        choiceBuilder.setNegativeButton(R.string.network_check_new_test, (dialog, which) -> {
                            performNewNetworkTest();
                        });
                        
                        choiceBuilder.setNeutralButton(R.string.network_check_cancel, null);
                        choiceBuilder.show();
                    } else {
                        // No saved results, perform a new test directly
                        performNewNetworkTest();
                    }
                    
                    return false;
                }
            });
            
            // 导出数据库按钮处理程序
            findPreference("export_database").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    // 显示确认对话框
                    androidx.appcompat.app.AlertDialog.Builder confirmBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
                    confirmBuilder.setTitle(getString(R.string.export_database_title));
                    confirmBuilder.setMessage(getString(R.string.export_database_message));
                    
                    confirmBuilder.setPositiveButton(getString(R.string.export_database_button), (dialog, which) -> {
                        exportDatabase();
                    });
                    
                    confirmBuilder.setNegativeButton(getString(android.R.string.cancel), null);
                    confirmBuilder.show();
                    
                    return false;
                }
            });
            
            // 导入数据库按钮处理程序
            findPreference("import_database").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    // 显示确认对话框
                    androidx.appcompat.app.AlertDialog.Builder confirmBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
                    confirmBuilder.setTitle(getString(R.string.import_database_title));
                    confirmBuilder.setMessage(getString(R.string.import_database_message));
                    
                    confirmBuilder.setPositiveButton(getString(R.string.import_database_button), (dialog, which) -> {
                        // 使用OpenDocument打开文件选择器，兼容Android 13+
                        filePickerLauncher.launch(new String[]{"*/*"});
                    });
                    
                    confirmBuilder.setNegativeButton(getString(android.R.string.cancel), null);
                    confirmBuilder.show();
                    
                    return false;
                }
            });
        } else if (s.equals("pref_category_alarm")) {
            Preference shareappPref = findPreference("shareapp_package");
            if (shareappPref != null) {
                shareappPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        ApplicationSelectorDialog newFragment = new ApplicationSelectorDialog();
                        newFragment.setCallback(FragmentSettings.this);
                        newFragment.show(getActivity().getSupportFragmentManager(), "appPicker");
                        return true;
                    }
                });
            }

            // 初始化睡眠定时器摘要文本
            Preference alarmTimeoutPref = findPreference("alarm_timeout");
            if (alarmTimeoutPref != null) {
                long currenTimerSeconds = PlayerServiceUtil.getTimerSeconds();
                if (currenTimerSeconds > 0) {
                    int minutes = (int) (currenTimerSeconds < 60 ? 1 : currenTimerSeconds / 60);
                    alarmTimeoutPref.setSummary(getString(R.string.settings_alarm_sleep_timer_desc).replace("%1$s", String.valueOf(minutes)));
                } else {
                    alarmTimeoutPref.setSummary(getString(R.string.settings_alarm_sleep_timer_desc_not_set));
                }
            }
            
            findPreference("alarm_timeout").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    // 使用与工具栏睡眠定时器相同的对话框
                    showSleepTimerDialog();
                    return true;
                }
            });
        } else if (s.equals("pref_category_other")) {
            findPreference("show_statistics").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    ((ActivityMain) getActivity()).getToolbar().setTitle(R.string.settings_statistics);
                    FragmentServerInfo f = new FragmentServerInfo();
                    FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
                    fragmentTransaction.replace(R.id.containerView, f).addToBackStack(String.valueOf(FRAGMENT_FROM_BACKSTACK)).commit();
                    return false;
                }
            });

            findPreference("show_about").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    ((ActivityMain) getActivity()).getToolbar().setTitle(R.string.settings_about);
                    FragmentAbout f = new FragmentAbout();
                    FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
                    fragmentTransaction.replace(R.id.containerView, f).addToBackStack(String.valueOf(FRAGMENT_FROM_BACKSTACK)).commit();
                    return false;
                }
            });
        }

        Preference batPref = getPreferenceScreen().findPreference(getString(R.string.key_ignore_battery_optimization));
        if (batPref != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                updateBatteryPrefDescription(batPref);
                batPref.setOnPreferenceClickListener(preference -> {
                    Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(intent);
                    updateBatteryPrefDescription(batPref);
                    return true;
                });
            } else {
                batPref.getParent().removePreference(batPref);
            }
        }
    }

    // Method to show network connection results
    private void showNetworkConnectionResults(Map<String, Long> results, long timestamp) {
        // Create a custom view for the results
        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout linearLayout = new LinearLayout(requireContext());
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setPadding(60, 40, 60, 40);
        
        // Check if dark theme is active
        boolean isDarkTheme = Utils.isDarkTheme(requireContext());
        int textColor = isDarkTheme ? Color.WHITE : Color.BLACK;
        int grayColor = isDarkTheme ? Color.GRAY : Color.DKGRAY;
        int successColor = isDarkTheme ? Color.GREEN : Color.parseColor("#008000");
        
        // Title
        TextView titleView = new TextView(requireContext());
        titleView.setText(R.string.network_check_results_title);
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(0, 0, 0, 30);
        titleView.setTextColor(textColor);
        linearLayout.addView(titleView);
        
        // Test time
        TextView timeView = new TextView(requireContext());
        if (timestamp > 0) {
            String timeString = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date(timestamp));
            timeView.setText(getString(R.string.network_check_time, timeString));
        } else {
            timeView.setText(getString(R.string.network_check_time, new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date())));
        }
        timeView.setTextSize(14);
        timeView.setPadding(0, 0, 0, 20);
        timeView.setTextColor(grayColor);
        linearLayout.addView(timeView);
        
        // 动态显示DNS返回的服务器的测试结果
        Map<String, Map<String, Long>> serverResults = new HashMap<>();
        
        // 按服务器分组结果
        for (Map.Entry<String, Long> entry : results.entrySet()) {
            String[] parts = entry.getKey().split("_");
            if (parts.length == 2) {
                String server = parts[0];
                String protocol = parts[1];
                
                if (!serverResults.containsKey(server)) {
                    serverResults.put(server, new HashMap<>());
                }
                serverResults.get(server).put(protocol, entry.getValue());
            }
        }
        
        // 显示每个服务器的结果
        int serverIndex = 1;
        for (Map.Entry<String, Map<String, Long>> serverEntry : serverResults.entrySet()) {
            String server = serverEntry.getKey();
            Map<String, Long> protocolResults = serverEntry.getValue();
            
            // Server title
            TextView serverTitle = new TextView(requireContext());
            serverTitle.setText(String.format(requireContext().getString(R.string.network_check_server_label), serverIndex) + ": " + server);
            serverTitle.setTextSize(16);
            serverTitle.setTypeface(null, Typeface.BOLD);
            serverTitle.setPadding(0, 20, 0, 10);
            serverTitle.setTextColor(textColor);
            linearLayout.addView(serverTitle);
            
            // HTTP result
            long httpTime = protocolResults.getOrDefault("HTTP", Long.MAX_VALUE);
            TextView httpView = new TextView(requireContext());
            httpView.setText("HTTP: " + (httpTime == Long.MAX_VALUE ? requireContext().getString(R.string.network_check_connection_failed) : httpTime + " ms"));
            httpView.setTextSize(14);
            httpView.setPadding(30, 5, 0, 5);
            httpView.setTextColor(httpTime == Long.MAX_VALUE ? Color.RED : textColor);
            linearLayout.addView(httpView);
            
            // HTTPS result
            long httpsTime = protocolResults.getOrDefault("HTTPS", Long.MAX_VALUE);
            TextView httpsView = new TextView(requireContext());
            httpsView.setText("HTTPS: " + (httpsTime == Long.MAX_VALUE ? requireContext().getString(R.string.network_check_connection_failed) : httpsTime + " ms"));
            httpsView.setTextSize(14);
            httpsView.setPadding(30, 5, 0, 5);
            httpsView.setTextColor(httpsTime == Long.MAX_VALUE ? Color.RED : textColor);
            linearLayout.addView(httpsView);
            
            serverIndex++;
        }
        
        // 如果没有服务器结果，显示提示
        if (serverResults.isEmpty()) {
            TextView noResultsView = new TextView(requireContext());
            noResultsView.setText(requireContext().getString(R.string.network_check_no_available));
            noResultsView.setTextSize(14);
            noResultsView.setPadding(30, 5, 0, 5);
            noResultsView.setTextColor(Color.RED);
            linearLayout.addView(noResultsView);
        }
        
        // Fastest connection
        TextView fastestTitle = new TextView(requireContext());
        fastestTitle.setText(requireContext().getString(R.string.network_check_fastest_label) + ":");
        fastestTitle.setTextSize(16);
        fastestTitle.setTypeface(null, Typeface.BOLD);
        fastestTitle.setPadding(0, 20, 0, 10);
        fastestTitle.setTextColor(textColor);
        linearLayout.addView(fastestTitle);
        
        // Find the fastest connection
        long minTime = Long.MAX_VALUE;
        String fastestConnection = requireContext().getString(R.string.network_check_no_connection);
        
        for (Map.Entry<String, Long> entry : results.entrySet()) {
            if (entry.getValue() < minTime) {
                minTime = entry.getValue();
                String[] parts = entry.getKey().split("_");
                if (parts.length >= 2) {
                    fastestConnection = parts[0] + " (" + parts[1] + ")";
                } else {
                    fastestConnection = entry.getKey();
                }
            }
        }
        
        TextView fastestResult = new TextView(requireContext());
        fastestResult.setText(fastestConnection + " - " + (minTime == Long.MAX_VALUE ? requireContext().getString(R.string.network_check_no_available) : minTime + " ms"));
        fastestResult.setTextSize(14);
        fastestResult.setPadding(30, 5, 0, 5);
        fastestResult.setTextColor(minTime == Long.MAX_VALUE ? Color.RED : successColor);
        linearLayout.addView(fastestResult);
        
        scrollView.addView(linearLayout);

        
        // Create and show the dialog
        androidx.appcompat.app.AlertDialog.Builder resultsBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
        resultsBuilder.setView(scrollView);
        resultsBuilder.setPositiveButton(requireContext().getString(R.string.action_ok), null);
        resultsBuilder.show();
    }
    
    private void startDatabaseUpdate() {
        DatabaseUpdateManager.startUpdate(requireContext());
        if (updateDialog != null && updateDialog.isShowing()) {
            // 对话框已在显示
        } else if (isAdded() && getActivity() != null && !getActivity().isFinishing() && !getActivity().isDestroyed()) {
            updateDialog = new DatabaseUpdateProgressDialog(requireContext());
            updateDialog.show();
        }
    }

    // Method to perform a new network test
    private void performNewNetworkTest() {
        // Show progress dialog
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
        builder.setTitle(R.string.settings_check_network_connection);
        builder.setMessage(R.string.network_check_testing);
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();
        
        // Check network connection speeds in background thread
        new Thread(() -> {
            try {
                // Test all connection speeds
                Map<String, Long> results = RadioBrowserServerManager.testAllConnectionSpeeds(requireContext());
                
                // Save the results
                long currentTime = System.currentTimeMillis();
                saveNetworkCheckResults(results, currentTime);
                
                requireActivity().runOnUiThread(() -> {
                    dialog.dismiss();
                    showNetworkConnectionResults(results, currentTime);
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    dialog.dismiss();
                    androidx.appcompat.app.AlertDialog.Builder errorBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
                    errorBuilder.setTitle(R.string.network_check_failed);
                    errorBuilder.setMessage(getString(R.string.network_check_error, e.getMessage()));
                    errorBuilder.setPositiveButton(R.string.action_ok, null);
                    errorBuilder.show();
                });
            }
        }).start();
    }
    
    // 内部类，用于保存网络检查结果和时间戳
    private static class NetworkCheckResults {
        public Map<String, Long> results;
        public long timestamp;
        
        public NetworkCheckResults(Map<String, Long> results, long timestamp) {
            this.results = results;
            this.timestamp = timestamp;
        }
    }
    
    // 使用SharedPreferences保存网络检查结果
    private void saveNetworkCheckResults(Map<String, Long> results, long timestamp) {
        SharedPreferences sharedPref = requireContext().getSharedPreferences(
            "NetworkCheckResults", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        
        // 清除之前的所有结果，确保只保存最新的测试结果
        editor.clear();
        
        // 保存每个服务器和协议的结果
        for (Map.Entry<String, Long> entry : results.entrySet()) {
            editor.putLong(entry.getKey(), entry.getValue());
        }
        
        // 保存结果的时间戳
        editor.putLong("timestamp", timestamp);
        editor.apply();
    }
    
    // 加载保存的网络检查结果
    private NetworkCheckResults loadNetworkCheckResults() {
        SharedPreferences sharedPref = requireContext().getSharedPreferences(
            "NetworkCheckResults", Context.MODE_PRIVATE);
        
        // 检查是否有保存的结果
        long timestamp = sharedPref.getLong("timestamp", 0);
        if (timestamp == 0) {
            return null; // 没有保存的结果
        }
        
        Map<String, Long> results = new HashMap<>();
        
        // 加载所有保存的服务器和协议的结果
        Map<String, ?> allEntries = sharedPref.getAll();
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            String key = entry.getKey();
            // 跳过timestamp键，只加载服务器测试结果
            if (!key.equals("timestamp") && entry.getValue() instanceof Long) {
                results.put(key, (Long) entry.getValue());
            }
        }
        
        return new NetworkCheckResults(results, timestamp);
    }

    /*
    private void setServersData(String[] list, ListPreference servers) {
        servers.setEntries(list);
        if (list.length > 0){
            servers.setDefaultValue(list[0]);
        }
        servers.setEntryValues(list);
    }

    void updateDnsList(final ListPreference lp){
        final AsyncTask<Void, Void, String[]> xxx = new AsyncTask<Void, Void, String[]>() {
            @Override
            protected String[] doInBackground(Void... params) {
                return RadioBrowserServerManager.getServerList(false);
            }

            @Override
            protected void onPostExecute(String[] result) {
                setServersData(result, lp);
                super.onPostExecute(result);
            }
        }.execute();
    }
    */

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 检查是否需要滚动到特定的偏好设置
        Bundle args = getArguments();
        if (args != null && args.containsKey("scroll_to_preference")) {
            String preferenceKey = args.getString("scroll_to_preference");
            if (preferenceKey != null) {
                // 延迟滚动，确保UI已经完全加载
                new Handler().postDelayed(() -> {
                    scrollToPreference(preferenceKey);
                }, 300);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d("FragmentSettings", "onResume called");
        
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);

        // 注册定时器完成广播接收器
        timerFinishedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (PLAYER_SERVICE_TIMER_FINISHED.equals(intent.getAction())) {
                    // 更新睡眠定时器摘要文本
                    Preference alarmTimeoutPref = findPreference("alarm_timeout");
                    if (alarmTimeoutPref != null) {
                        alarmTimeoutPref.setSummary(getString(R.string.settings_alarm_sleep_timer_desc_not_set));
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(PLAYER_SERVICE_TIMER_FINISHED);
        requireContext().registerReceiver(timerFinishedReceiver, filter);
        
        // 初始化并注册数据库更新广播接收器
        databaseUpdatedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("net.programmierecke.radiodroid2.DATABASE_UPDATED".equals(intent.getAction())) {
                    Log.d("FragmentSettings", "Received database updated broadcast");
                    // 数据库更新完成，更新状态显示
                    updateDatabaseStatusOnLoad();
                }
            }
        };
        IntentFilter databaseUpdateFilter = new IntentFilter("net.programmierecke.radiodroid2.DATABASE_UPDATED");
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(databaseUpdatedReceiver, databaseUpdateFilter);

        refreshToolbar();

        if(isToplevel())
            refreshToplevelIcons();

        if(findPreference("shareapp_package") != null)
            findPreference("shareapp_package").setSummary(getAppDisplayName(getPreferenceManager().getSharedPreferences().getString("shareapp_package", "")));

        // 恢复数据库更新进度对话框
        checkAndRestoreUpdateDialog();

        Preference batPref = getPreferenceScreen().findPreference(getString(R.string.key_ignore_battery_optimization));
        if (batPref != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            updateBatteryPrefDescription(batPref);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updateBluetoothPermissionState();
        }
        
        // 每次打开设置界面都更新数据库状态，确保状态信息是最新的
        updateDatabaseStatusOnLoad();
        
        // 更新应用前台时间 - 移到checkAndRestoreUpdateDialog之后，避免取消正在进行的更新
        DatabaseUpdateWorker.updateAppForegroundTime(requireContext());
    }

    @Override
    public void onPause() {
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        
        // 注销广播接收器
        if (timerFinishedReceiver != null) {
            requireContext().unregisterReceiver(timerFinishedReceiver);
        }
        
        if (databaseUpdatedReceiver != null) {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(databaseUpdatedReceiver);
        }
        
        // 隐藏进度对话框但不取消更新
        if (updateDialog != null) {
            if (updateDialog.isShowing()) {
                updateDialog.hide();
                Log.d("FragmentSettings", "Dialog hidden in onPause");
            }
            // 不设置为null，以便在onResume时可以重新显示
        }
        
        super.onPause();
    }

    @RequiresApi(23)
    private void updateBatteryPrefDescription(Preference batPref) {
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getContext().getPackageName())) {
            batPref.setSummary(R.string.settings_ignore_battery_optimization_summary_on);
        } else {
            batPref.setSummary(R.string.settings_ignore_battery_optimization_summary_off);
        }
    }

    private void setupBluetoothPermissionPreference() {
        androidx.preference.SwitchPreferenceCompat btPermPref = findPreference("bluetooth_connect_permission");
        if (btPermPref == null) return;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            btPermPref.setVisible(false);
            return;
        }

        btPermPref.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean isChecked = (Boolean) newValue;
            if (isChecked) {
                if (!hasBluetoothConnectPermission()) {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
                }
                return false;
            } else {
                if (hasBluetoothConnectPermission()) {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
                    startActivity(intent);
                }
                return false;
            }
        });

        updateBluetoothPermissionState();
    }

    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return requireContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void updateBluetoothPermissionState() {
        androidx.preference.SwitchPreferenceCompat btPermPref = findPreference("bluetooth_connect_permission");
        if (btPermPref == null) return;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            btPermPref.setVisible(false);
            return;
        }

        boolean hasPermission = hasBluetoothConnectPermission();

        btPermPref.setChecked(hasPermission);

        if (hasPermission) {
            btPermPref.setSummary(R.string.settings_bluetooth_connect_permission_summary);
        } else {
            btPermPref.setSummary(R.string.settings_bluetooth_connect_permission_denied);
        }

        androidx.preference.CheckBoxPreference pauseBtPref = findPreference("pause_on_bluetooth_disconnect");
        androidx.preference.CheckBoxPreference closeBtPref = findPreference("close_on_bluetooth_disconnect");
        androidx.preference.CheckBoxPreference resumeBtPref = findPreference("auto_resume_on_bluetooth_a2dp_connection");

        if (pauseBtPref != null) pauseBtPref.setEnabled(hasPermission);
        if (closeBtPref != null) closeBtPref.setEnabled(hasPermission);
        if (resumeBtPref != null) resumeBtPref.setEnabled(hasPermission);

        if (!hasPermission) {
            SharedPreferences.Editor editor = getPreferenceManager().getSharedPreferences().edit();
            editor.putBoolean("pause_on_bluetooth_disconnect", false);
            editor.putBoolean("close_on_bluetooth_disconnect", false);
            editor.putBoolean("auto_resume_on_bluetooth_a2dp_connection", false);
            editor.apply();
            if (pauseBtPref != null) pauseBtPref.setChecked(false);
            if (closeBtPref != null) closeBtPref.setChecked(false);
            if (resumeBtPref != null) resumeBtPref.setChecked(false);
        }
    }
    
    private void updateDatabaseStatus(boolean success, String error) {
        updateDatabaseStatus(success, error, false);
    }
    
    private void updateDatabaseStatus(boolean success, String error, boolean useDatabaseTimestamp) {
        Preference statusPref = findPreference("local_database_status");
        if (statusPref != null) {
            SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
            SharedPreferences.Editor editor = prefs.edit();
            
            if (success) {
                // 获取本地数据库中的电台数量和更新时间
                RadioStationRepository repository = RadioStationRepository.getInstance(getContext());
                
                // 在后台线程获取数据库状态信息
                new AsyncTask<Void, Void, DatabaseStatusInfo>() {
                    @Override
                    protected DatabaseStatusInfo doInBackground(Void... voids) {
                        // 确保update_timestamp表存在
                        repository.ensureUpdateTimestampTable();
                        
                        // 从数据库获取更新时间戳
                        long timestamp;
                        if (useDatabaseTimestamp) {
                            timestamp = repository.getDatabaseUpdateTime();
                        } else {
                            // 使用当前时间更新数据库时间戳
                            timestamp = System.currentTimeMillis();
                            repository.updateDatabaseTimestamp(timestamp);
                        }
                        
                        // 从数据库获取电台数量
                        int stationCount = repository.getStationCountSync();
                        
                        return new DatabaseStatusInfo(timestamp, stationCount);
                    }
                    
                    @Override
                    protected void onPostExecute(DatabaseStatusInfo statusInfo) {
                        // 在UI线程更新状态显示
                        if (getActivity() != null && isAdded()) {
                            getActivity().runOnUiThread(() -> {
                                // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                                if (isAdded() && getContext() != null) {
                                    String updateTime;
                                    if (statusInfo.timestamp > 0) {
                                        java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
                                        updateTime = dateFormat.format(new java.util.Date(statusInfo.timestamp));
                                    } else {
                                        // 如果数据库中没有时间戳，显示"数据库尚未更新"
                                        updateTime = getString(R.string.database_not_updated);
                                    }
                                    
                                    // 更新状态显示
                                    statusPref.setSummary(getString(R.string.settings_local_database_status_success, updateTime, statusInfo.stationCount));
                                    
                                    // 更新数据库状态摘要信息
                                    String statusSummary = getString(R.string.db_status_summary, updateTime, statusInfo.stationCount);
                                    SharedPreferences.Editor editor = getPreferenceManager().getSharedPreferences().edit();
                                    editor.putString("database_status_summary", statusSummary);
                                    editor.apply();
                                    
                                    Log.d("FragmentSettings", "Database status updated from database fields: " + statusSummary);
                                }
                            });
                        }
                    }
                }.execute();
                
                // Save status to SharedPreferences
                editor.putString("local_database_last_status", "success");
                editor.apply();
            } else {
                // 显示错误状态
                statusPref.setSummary(getString(R.string.settings_local_database_status_failed, error));
                editor.putString("local_database_last_status", "failed");
                editor.putString("local_database_last_error", error);
                editor.apply();
            }
        }
    }
    
    private void updateDatabaseStatusOnLoad() {
        Preference statusPref = findPreference("local_database_status");
        if (statusPref != null) {
            // 每次打开本地数据库更新目录时，都重新从数据库获取最新的状态信息
            RadioStationRepository repository = RadioStationRepository.getInstance(getContext());
            
            // 使用AsyncTask在后台线程获取数据库更新时间和电台数量
            new AsyncTask<Void, Void, DatabaseStatusInfo>() {
                @Override
                protected DatabaseStatusInfo doInBackground(Void... voids) {
                    // 确保update_timestamp表存在
                    repository.ensureUpdateTimestampTable();
                    
                    // 从数据库获取更新时间戳
                    long timestamp = repository.getDatabaseUpdateTime();
                    
                    // 从数据库获取电台数量
                    int stationCount = repository.getStationCountSync();
                    
                    return new DatabaseStatusInfo(timestamp, stationCount);
                }
                
                @Override
                protected void onPostExecute(DatabaseStatusInfo statusInfo) {
                    // 在UI线程更新状态显示
                    if (getActivity() != null && isAdded()) {
                        getActivity().runOnUiThread(() -> {
                            // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                            if (isAdded() && getContext() != null) {
                                String updateTime;
                                if (statusInfo.timestamp > 0) {
                                    java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
                                    updateTime = dateFormat.format(new java.util.Date(statusInfo.timestamp));
                                } else {
                                    // 如果数据库中没有时间戳，显示"数据库尚未更新"
                                    updateTime = getString(R.string.database_not_updated);
                                }
                                
                                // 更新状态显示
                                statusPref.setSummary(getString(R.string.settings_local_database_status_success, updateTime, statusInfo.stationCount));
                                
                                // 更新数据库状态摘要信息
                                String statusSummary = getString(R.string.db_status_summary, updateTime, statusInfo.stationCount);
                                SharedPreferences.Editor editor = getPreferenceManager().getSharedPreferences().edit();
                                editor.putString("database_status_summary", statusSummary);
                                editor.apply();
                                
                                Log.d("FragmentSettings", "Database status updated from database fields: " + statusSummary);
                            }
                        });
                    }
                }
            }.execute();
        }
    }
    
    // 用于存储数据库状态信息的内部类
    private static class DatabaseStatusInfo {
        public long timestamp;
        public int stationCount;
        
        public DatabaseStatusInfo(long timestamp, int stationCount) {
            this.timestamp = timestamp;
            this.stationCount = stationCount;
        }
    }

    private void getStationCountAndUpdateStatus(String lastUpdateTime, RadioStationRepository repository, Preference statusPref) {
        // 获取电台数量
        repository.getStationCount(new RadioStationRepository.StationCountCallback() {
            @Override
            public void onStationCountReceived(int count) {
                // 在UI线程更新状态显示
                if (getActivity() != null && isAdded()) {
                    getActivity().runOnUiThread(() -> {
                        // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                        if (isAdded() && getContext() != null) {
                            // 更新状态显示
                            statusPref.setSummary(getString(R.string.settings_local_database_status_success, lastUpdateTime, count));
                            
                            // 更新数据库状态摘要信息
                            String statusSummary = getString(R.string.db_status_summary, lastUpdateTime, count);
                            SharedPreferences.Editor editor = getPreferenceManager().getSharedPreferences().edit();
                            editor.putString("database_status_summary", statusSummary);
                            editor.apply();
                            
                            Log.d("FragmentSettings", "Database status updated: " + statusSummary);
                        }
                    });
                }
            }

            @Override
            public void onError(String error) {
                // 如果获取电台数量失败，只显示错误信息
                if (getActivity() != null && isAdded()) {
                    getActivity().runOnUiThread(() -> {
                        // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                        if (isAdded() && getContext() != null) {
                            statusPref.setSummary(getString(R.string.settings_local_database_status_failed, error));
                            
                            // 更新数据库状态摘要信息
                            String statusSummary = getString(R.string.db_status_error, error);
                            SharedPreferences.Editor editor = getPreferenceManager().getSharedPreferences().edit();
                            editor.putString("database_status_summary", statusSummary);
                            editor.apply();
                            
                            Log.d("FragmentSettings", "Database status update failed: " + error);
                        }
                    });
                }
            }
        });
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          String key) {
        if (BuildConfig.DEBUG) {
            Log.d("AAA", "changed key:" + key);
        }
        if (key.equals("alarm_external")) {
            boolean active = sharedPreferences.getBoolean(key, false);
            if (active) {
                dialogOpenedFromCheckbox = true;
                ApplicationSelectorDialog newFragment = new ApplicationSelectorDialog();
                newFragment.setCallback(this);
                newFragment.show(getActivity().getSupportFragmentManager(), "appPicker");
            }
        }
        if (key.equals("theme_name") || key.equals("circular_icons") || key.equals("bottom_navigation")) {
            if (key.equals("circular_icons"))
                ((RadioDroidApp) getActivity().getApplication()).getFavouriteManager().updateShortcuts();
            getActivity().recreate();
        }
        if (key.equals("app_language")) {
            String language = sharedPreferences.getString(key, "system");
            updateAppLanguage(language);
            getActivity().recreate();
        }
    }
    
    private void updateAppLanguage(String language) {
        Locale locale;
        if (language.equals("system")) {
            locale = Locale.getDefault();
        } else if (language.equals("en")) {
            locale = new Locale("en");
        } else if (language.equals("zh")) {
            locale = new Locale("zh");
        } else if (language.equals("es")) {
            locale = new Locale("es");
        } else if (language.equals("ru")) {
            locale = new Locale("ru");
        } else {
            locale = Locale.getDefault();
        }
        
        Locale.setDefault(locale);
        android.content.res.Configuration config = new android.content.res.Configuration();
        config.setLocale(locale);
        requireContext().getResources().updateConfiguration(config, requireContext().getResources().getDisplayMetrics());
    }

    @Override
    public void onAppSelected(String packageName, String activityName) {
        if (BuildConfig.DEBUG) {
            Log.d("SEL", "selected:" + packageName + "/" + activityName);
        }
        dialogOpenedFromCheckbox = false;
        SharedPreferences.Editor ed = getPreferenceManager().getSharedPreferences().edit();
        ed.putString("shareapp_package", packageName);
        ed.putString("shareapp_activity", activityName);
        ed.commit();

        findPreference("shareapp_package").setSummary(getAppDisplayName(packageName));
    }

    @Override
    public void onAppSelectionCancelled() {
        if (dialogOpenedFromCheckbox) {
            dialogOpenedFromCheckbox = false;
            String currentPackage = getPreferenceManager().getSharedPreferences().getString("shareapp_package", "");
            if (currentPackage.isEmpty()) {
                androidx.preference.CheckBoxPreference alarmExtPref = findPreference("alarm_external");
                if (alarmExtPref != null) {
                    alarmExtPref.setChecked(false);
                }
            }
        }
    }

    private String getAppDisplayName(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return getString(R.string.settings_alarm_audio_player_not_selected);
        }
        try {
            PackageManager pm = requireContext().getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(appInfo).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }
    
    // 导出主数据库（使用SAF让用户选择保存位置）
    private void exportDatabase() {
        new Thread(() -> {
            try {
                RadioStationRepository repository = RadioStationRepository.getInstance(requireContext());
                repository.ensureUpdateTimestampTable();
                int stationCount = repository.getStationCountSync();
                long timestamp = repository.getDatabaseUpdateTime();
                String updateTime;
                if (timestamp > 0) {
                    java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault());
                    updateTime = dateFormat.format(new java.util.Date(timestamp));
                } else {
                    updateTime = "unknown";
                }
                String defaultFileName = "radio_droid_database_" + updateTime + "_" + stationCount + ".db";
                
                requireActivity().runOnUiThread(() -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/octet-stream");
                        intent.putExtra(Intent.EXTRA_TITLE, defaultFileName);
                        exportFileLauncher.launch(intent);
                    } catch (Exception e) {
                        Log.e("FragmentSettings", "Error launching export dialog", e);
                        Toast.makeText(requireContext(), getString(R.string.export_failed_message, e.getMessage()), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e("FragmentSettings", "Error preparing export", e);
                requireActivity().runOnUiThread(() -> {
                    String errorMsg = e.getMessage() != null ? e.getMessage() : getString(R.string.export_failed_title);
                    Toast.makeText(requireContext(), getString(R.string.export_failed_message, errorMsg), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    // 导出主数据库到用户选择的URI
    private void exportDatabaseToUri(Uri uri) {
        androidx.appcompat.app.AlertDialog.Builder progressBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
        progressBuilder.setTitle(R.string.export_database_title);
        progressBuilder.setMessage(getString(R.string.progress_exporting_database));
        androidx.appcompat.app.AlertDialog progressDialog = progressBuilder.create();
        progressDialog.show();
        
        new Thread(() -> {
            try {
                File mainDatabaseFile = requireContext().getDatabasePath("radio_droid_database");
                
                try (java.io.InputStream inputStream = new java.io.FileInputStream(mainDatabaseFile);
                     java.io.OutputStream outputStream = requireContext().getContentResolver().openOutputStream(uri)) {
                    if (outputStream == null) {
                        throw new IOException("Unable to open output stream for URI: " + uri);
                    }
                    byte[] buffer = new byte[8192];
                    int length;
                    while ((length = inputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, length);
                    }
                }
                
                requireActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    String displayPath = getDisplayPathFromUri(uri);
                    androidx.appcompat.app.AlertDialog.Builder successBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
                    successBuilder.setTitle(R.string.export_success_title);
                    successBuilder.setMessage(getString(R.string.export_success_message, displayPath));
                    successBuilder.setPositiveButton(R.string.action_ok, null);
                    successBuilder.show();
                });
                
            } catch (IOException e) {
                e.printStackTrace();
                requireActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    androidx.appcompat.app.AlertDialog.Builder errorBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
                    errorBuilder.setTitle(R.string.export_failed_title);
                    errorBuilder.setMessage(getString(R.string.export_failed_message, errorMsg));
                    errorBuilder.setPositiveButton(R.string.action_ok, null);
                    errorBuilder.show();
                });
            }
        }).start();
    }
    
    // 将URI转换为友好的文件路径显示
    private String getDisplayPathFromUri(Uri uri) {
        if (uri == null) {
            return "";
        }
        
        String uriString = uri.toString();
        
        // 如果是文件URI（file://），直接返回路径
        if ("file".equals(uri.getScheme())) {
            return uri.getPath();
        }
        
        // 如果是content URI，尝试解析为友好路径
        if ("content".equals(uri.getScheme())) {
            // 尝试通过DocumentsContract解析
            if (uriString.contains("com.android.externalstorage.documents")) {
                String path = uri.getLastPathSegment();
                if (path != null) {
                    // 将 "primary:Download/filename.db" 转换为 "/storage/emulated/0/Download/filename.db"
                    if (path.startsWith("primary:")) {
                        path = "/storage/emulated/0/" + path.substring("primary:".length());
                    }
                    return path;
                }
            }
            
            // 尝试通过ContentResolver查询显示名称
            try {
                android.database.Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null);
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            int displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                            if (displayNameIndex >= 0) {
                                String displayName = cursor.getString(displayNameIndex);
                                if (displayName != null && !displayName.isEmpty()) {
                                    // 推断文件夹路径
                                    if (uriString.contains("Download")) {
                                        return "/storage/emulated/0/Download/" + displayName;
                                    } else if (uriString.contains("Documents")) {
                                        return "/storage/emulated/0/Documents/" + displayName;
                                    } else {
                                        return displayName;
                                    }
                                }
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }
            } catch (Exception e) {
                Log.w("FragmentSettings", "Failed to resolve display path from URI", e);
            }
        }
        
        // 无法解析时返回原始URI字符串
        return uriString;
    }
    
    // 从外部存储导入主数据库
    private void importDatabase(Uri uri) {
        androidx.appcompat.app.AlertDialog.Builder progressBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
        progressBuilder.setTitle(R.string.import_database_title);
        progressBuilder.setMessage(getString(R.string.progress_importing_database));
        androidx.appcompat.app.AlertDialog progressDialog = progressBuilder.create();
        progressDialog.show();
        
        new Thread(() -> {
            try {
                File mainDatabaseFile = requireContext().getDatabasePath("radio_droid_database");
                Log.d("FragmentSettings", "主数据库文件路径: " + mainDatabaseFile.getAbsolutePath());
                
                RadioStationRepository repository = RadioStationRepository.getInstance(requireContext());
                repository.closeDatabase();
                Log.d("FragmentSettings", "数据库连接已关闭");
                
                File databaseDir = mainDatabaseFile.getParentFile();
                if (!databaseDir.exists()) {
                    databaseDir.mkdirs();
                    Log.d("FragmentSettings", "创建数据库目录: " + databaseDir.getAbsolutePath());
                }
                
                // 先复制到临时文件，验证成功后再替换，防止数据丢失
                File tempImportFile = new File(databaseDir, "radio_droid_database_import_temp.db");
                if (tempImportFile.exists()) {
                    tempImportFile.delete();
                }
                
                try (java.io.InputStream inputStream = requireContext().getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream outputStream = new java.io.FileOutputStream(tempImportFile)) {
                    if (inputStream == null) {
                        tempImportFile.delete();
                        throw new Exception(getString(R.string.import_failed_invalid));
                    }
                    
                    byte[] buffer = new byte[8192];
                    int length;
                    long totalCopied = 0;
                    while ((length = inputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, length);
                        totalCopied += length;
                    }
                    outputStream.flush();
                    
                    Log.d("FragmentSettings", "已复制 " + totalCopied + " 字节到临时文件");
                    
                    if (totalCopied == 0) {
                        tempImportFile.delete();
                        throw new Exception(getString(R.string.import_failed_empty));
                    }
                }
                
                if (!tempImportFile.exists()) {
                    throw new Exception(getString(R.string.error_target_file_not_exist));
                }
                
                long targetFileSize = tempImportFile.length();
                Log.d("FragmentSettings", "临时文件大小: " + targetFileSize + " 字节");
                
                if (targetFileSize == 0) {
                    tempImportFile.delete();
                    throw new Exception(getString(R.string.import_failed_empty));
                }
                
                // 验证成功后再替换旧数据库
                if (mainDatabaseFile.exists()) {
                    boolean deleted = mainDatabaseFile.delete();
                    Log.d("FragmentSettings", "删除旧数据库文件: " + deleted);
                    if (!deleted) {
                        tempImportFile.delete();
                        throw new Exception(getString(R.string.error_cannot_delete_old_db));
                    }
                }
                
                boolean renamed = tempImportFile.renameTo(mainDatabaseFile);
                if (!renamed) {
                    // 如果重命名失败，尝试复制
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(tempImportFile);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(mainDatabaseFile)) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = fis.read(buffer)) > 0) {
                            fos.write(buffer, 0, length);
                        }
                        fos.flush();
                    }
                    tempImportFile.delete();
                }
                
                if (!mainDatabaseFile.exists()) {
                    throw new Exception(getString(R.string.error_target_file_not_exist));
                }
                
                repository.reinitializeDatabase(requireContext());
                
                repository.ensureUpdateTimestampTable();
                Log.d("FragmentSettings", "已确保update_timestamp表存在");
                
                int stationCount = repository.getStationCountSync();
                if (stationCount < 0) {
                    throw new Exception(getString(R.string.error_database_corrupted));
                }
                
                Log.d("FragmentSettings", "数据库导入成功，电台数量: " + stationCount);
                
                final long dbUpdateTime = repository.getDatabaseUpdateTime();
                Log.d("FragmentSettings", "从数据库读取的更新时间戳: " + dbUpdateTime);
                final int finalStationCount = stationCount;
                
                long finalUpdateTime = dbUpdateTime;
                if (finalUpdateTime <= 0) {
                    try {
                        String fileName = null;
                        try (android.database.Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null)) {
                            if (cursor != null && cursor.moveToFirst()) {
                                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                                if (nameIndex >= 0) {
                                    fileName = cursor.getString(nameIndex);
                                }
                            }
                        }
                        
                        Log.d("FragmentSettings", "导入的文件名: " + fileName);
                        
                        if (fileName != null) {
                            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("radio_droid_database_(\\d{8})_(\\d{6})_\\d+\\.db");
                            java.util.regex.Matcher matcher = pattern.matcher(fileName);
                            
                            if (matcher.find()) {
                                String dateStr = matcher.group(1);
                                String timeStr = matcher.group(2);
                                String dateTimeStr = dateStr + timeStr;
                                
                                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.getDefault());
                                java.util.Date date = format.parse(dateTimeStr);
                                finalUpdateTime = date.getTime();
                                
                                Log.d("FragmentSettings", "从文件名提取的时间戳: " + finalUpdateTime);
                                
                                repository.updateDatabaseTimestamp(finalUpdateTime);
                                Log.d("FragmentSettings", "已更新数据库时间戳: " + finalUpdateTime);
                            } else {
                                Log.d("FragmentSettings", "无法从文件名提取时间戳");
                            }
                        }
                    } catch (Exception e) {
                        Log.e("FragmentSettings", "从文件名提取时间戳失败", e);
                    }
                }
                
                final long finalUpdateTimeForLambda = finalUpdateTime;
                
                requireActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    
                    String updateTime;
                    if (finalUpdateTimeForLambda > 0) {
                        java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
                        updateTime = dateFormat.format(new java.util.Date(finalUpdateTimeForLambda));
                        Log.d("FragmentSettings", "格式化的更新时间: " + updateTime);
                    } else {
                        updateTime = getString(R.string.database_not_updated);
                        Log.d("FragmentSettings", "数据库中没有时间戳且无法从文件名提取，显示数据库尚未更新");
                    }
                    
                    SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putString("local_database_last_status", "success");
                    editor.putString("local_database_last_update", updateTime);
                    editor.putString("database_status_summary", getString(R.string.last_update, updateTime, finalStationCount));
                    editor.apply();
                    
                    Log.d("FragmentSettings", "已保存数据库状态到SharedPreferences: " + updateTime);
                    
                    androidx.appcompat.app.AlertDialog.Builder successBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
                    successBuilder.setTitle(R.string.import_success_title);
                    successBuilder.setMessage(getString(R.string.import_success_message, finalStationCount));
                    successBuilder.setPositiveButton(R.string.action_ok, (dialog, which) -> {
                        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().commit();
                        
                        Intent intent = new Intent(requireContext(), ActivityMain.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        requireActivity().finish();
                        System.exit(0);
                    });
                    successBuilder.setCancelable(false);
                    successBuilder.show();
                });
                
            } catch (Exception e) {
                Log.e("FragmentSettings", "导入数据库失败", e);
                // 在UI线程显示错误
                requireActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    androidx.appcompat.app.AlertDialog.Builder errorBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
                    errorBuilder.setTitle(R.string.import_failed_title);
                    errorBuilder.setMessage(getString(R.string.import_failed_message, e.getMessage()));
                    errorBuilder.setPositiveButton(R.string.action_ok, null);
                    errorBuilder.show();
                });
            }
        }).start();
    }
    
    private void showSleepTimerDialog() {
        final androidx.appcompat.app.AlertDialog.Builder seekDialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
        View seekView = View.inflate(requireContext(), R.layout.layout_timer_chooser, null);

        seekDialog.setTitle(R.string.sleep_timer_title);
        seekDialog.setView(seekView);

        final TextView seekTextView = (TextView) seekView.findViewById(R.id.timerTextView);
        final SeekBar seekBar = (SeekBar) seekView.findViewById(R.id.timerSeekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                seekTextView.setText(String.valueOf(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        SharedPreferences sharedPref = android.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
        long currenTimerSeconds = PlayerServiceUtil.getTimerSeconds();
        long currentTimer;
        if (currenTimerSeconds <= 0) {
            currentTimer = sharedPref.getInt("sleep_timer_default_minutes", 10);
        } else if (currenTimerSeconds < 60) {
            currentTimer = 1;
        } else {
            currentTimer = currenTimerSeconds / 60;
        }
        seekBar.setProgress((int) currentTimer);
        
        // 根据当前定时器状态更新摘要文本
        Preference alarmTimeoutPref = findPreference("alarm_timeout");
        if (alarmTimeoutPref != null) {
            if (currenTimerSeconds > 0) {
                int minutes = (int) (currenTimerSeconds < 60 ? 1 : currenTimerSeconds / 60);
                alarmTimeoutPref.setSummary(getString(R.string.settings_alarm_sleep_timer_desc).replace("%1$s", String.valueOf(minutes)));
            } else {
                alarmTimeoutPref.setSummary(getString(R.string.settings_alarm_sleep_timer_desc_not_set));
            }
        }
        
        seekDialog.setPositiveButton(R.string.sleep_timer_apply, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                PlayerServiceUtil.clearTimer();
                PlayerServiceUtil.addTimer(seekBar.getProgress() * 60);
                sharedPref.edit().putInt("sleep_timer_default_minutes", seekBar.getProgress()).apply();
                
                // 更新摘要文本
                Preference alarmTimeoutPref = findPreference("alarm_timeout");
                if (alarmTimeoutPref != null) {
                    alarmTimeoutPref.setSummary(getString(R.string.settings_alarm_sleep_timer_desc).replace("%1$s", String.valueOf(seekBar.getProgress())));
                }
            }
        });

        seekDialog.setNegativeButton(R.string.sleep_timer_clear, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                PlayerServiceUtil.clearTimer();
                
                // 重置摘要文本
                Preference alarmTimeoutPref = findPreference("alarm_timeout");
                if (alarmTimeoutPref != null) {
                    alarmTimeoutPref.setSummary(getString(R.string.settings_alarm_sleep_timer_desc_not_set));
                }
            }
        });

        seekDialog.create();
        seekDialog.show();
    }
}
