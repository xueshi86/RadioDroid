package net.programmierecke.radiodroid2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.audiofx.AudioEffect;
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
    private ActivityResultLauncher<String> filePickerLauncher;
    private BroadcastReceiver timerFinishedReceiver;
    private BroadcastReceiver databaseUpdatedReceiver;

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
        findPreference("shareapp_package").setSummary(getPreferenceManager().getSharedPreferences().getString("shareapp_package", ""));
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
        
        // 初始化文件选择器
        filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    // 用户选择了文件，执行导入
                    importDatabase(uri);
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
                    Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
                    
                    // 添加更多参数以确保均衡器正确启动
                    intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getContext().getPackageName());
                    intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
                    intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0); // 0表示使用默认音频会话

                    if (getContext().getPackageManager().resolveActivity(intent, 0) == null) {
                        Toast.makeText(getContext(), R.string.error_no_equalizer_found, Toast.LENGTH_SHORT).show();
                    } else {
                        // 使用getActivity()确保在Fragment中正确启动
                        startActivity(intent);
                    }

                    return false;
                }
            });


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
                    // 启动数据库更新
                    DatabaseUpdateManager.startUpdate(requireContext());
                    
                    // 显示进度对话框
                    if (updateDialog != null && updateDialog.isShowing()) {
                        // 如果对话框已经在显示，不做任何操作
                    } else if (isAdded() && getActivity() != null && !getActivity().isFinishing() && !getActivity().isDestroyed()) {
                        updateDialog = new DatabaseUpdateProgressDialog(requireContext());
                        updateDialog.show();
                    }
                    
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
                    // 检查是否有外部存储权限
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (requireContext().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 
                                1001);
                            return false;
                        }
                    }
                    
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
                    // 检查是否有外部存储权限
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (requireContext().checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) 
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 
                                1002);
                            return false;
                        }
                    }
                    
                    // 显示确认对话框
                    androidx.appcompat.app.AlertDialog.Builder confirmBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
                    confirmBuilder.setTitle(getString(R.string.import_database_title));
                    confirmBuilder.setMessage(getString(R.string.import_database_message));
                    
                    confirmBuilder.setPositiveButton(getString(R.string.import_database_button), (dialog, which) -> {
                        // 打开文件选择器，用户可以导航到Download/RadioDroid文件夹选择.db文件
                        filePickerLauncher.launch("*/*");
                    });
                    
                    confirmBuilder.setNegativeButton(getString(android.R.string.cancel), null);
                    confirmBuilder.show();
                    
                    return false;
                }
            });
        } else if (s.equals("pref_category_alarm")) {
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
            findPreference("shareapp_package").setSummary(getPreferenceManager().getSharedPreferences().getString("shareapp_package", ""));

        // 恢复数据库更新进度对话框
        checkAndRestoreUpdateDialog();

        Preference batPref = getPreferenceScreen().findPreference(getString(R.string.key_ignore_battery_optimization));
        if (batPref != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // the second condition should already follow from the first
            updateBatteryPrefDescription(batPref);
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
                                    String statusSummary = "上次更新: " + updateTime + ", 电台数量: " + statusInfo.stationCount;
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
                                String statusSummary = "上次更新: " + updateTime + ", 电台数量: " + statusInfo.stationCount;
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
                            String statusSummary = "上次更新: " + lastUpdateTime + ", 电台数量: " + count;
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
                            String statusSummary = "获取状态失败: " + error;
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
        SharedPreferences.Editor ed = getPreferenceManager().getSharedPreferences().edit();
        ed.putString("shareapp_package", packageName);
        ed.putString("shareapp_activity", activityName);
        ed.commit();

        findPreference("shareapp_package").setSummary(packageName);
    }
    
    // 导出主数据库到外部存储
    private void exportDatabase() {
        // 显示进度对话框
        androidx.appcompat.app.AlertDialog.Builder progressBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
        progressBuilder.setTitle(R.string.export_database_title);
        progressBuilder.setMessage(getString(R.string.progress_exporting_database));
        androidx.appcompat.app.AlertDialog progressDialog = progressBuilder.create();
        progressDialog.show();
        
        // 在后台线程执行导出操作
        new Thread(() -> {
            try {
                // 获取主数据库文件路径
                File mainDatabaseFile = requireContext().getDatabasePath("radio_droid_database");
                
                // 获取电台数量和更新时间
                RadioStationRepository repository = RadioStationRepository.getInstance(requireContext());
                
                // 确保update_timestamp表存在
                repository.ensureUpdateTimestampTable();
                
                // 从数据库获取电台数量
                int stationCount = repository.getStationCountSync();
                
                // 从数据库获取时间戳
                long timestamp = repository.getDatabaseUpdateTime();
                String updateTime;
                if (timestamp > 0) {
                    java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault());
                    updateTime = dateFormat.format(new java.util.Date(timestamp));
                } else {
                    updateTime = "unknown";
                }
                
                // 创建导出目录
                File exportDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RadioDroid");
                if (!exportDir.exists()) {
                    exportDir.mkdirs();
                }
                
                // 创建导出文件名（包含更新时间和电台数量）
                File exportFile = new File(exportDir, "radio_droid_database_" + updateTime + "_" + stationCount + ".db");
                
                // 复制数据库文件
                FileChannel source = new FileInputStream(mainDatabaseFile).getChannel();
                FileChannel destination = new FileOutputStream(exportFile).getChannel();
                destination.transferFrom(source, 0, source.size());
                source.close();
                destination.close();
                
                // 在UI线程显示结果
                requireActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    androidx.appcompat.app.AlertDialog.Builder successBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
                    successBuilder.setTitle(R.string.export_success_title);
                    successBuilder.setMessage(getString(R.string.export_success_message, exportFile.getAbsolutePath()));
                    successBuilder.setPositiveButton(R.string.action_ok, null);
                    successBuilder.show();
                });
                
            } catch (IOException e) {
                e.printStackTrace();
                // 在UI线程显示错误
                requireActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    androidx.appcompat.app.AlertDialog.Builder errorBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
                    errorBuilder.setTitle(R.string.export_failed_title);
                    errorBuilder.setMessage(getString(R.string.export_failed_message, e.getMessage()));
                    errorBuilder.setPositiveButton(R.string.action_ok, null);
                    errorBuilder.show();
                });
            }
        }).start();
    }
    
    // 从外部存储导入主数据库
    private void importDatabase(Uri uri) {
        // 显示进度对话框
        androidx.appcompat.app.AlertDialog.Builder progressBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext(), Utils.getAlertDialogThemeResId(requireContext()));
        progressBuilder.setTitle(R.string.import_database_title);
        progressBuilder.setMessage(getString(R.string.progress_importing_database));
        androidx.appcompat.app.AlertDialog progressDialog = progressBuilder.create();
        progressDialog.show();
        
        // 在后台线程执行导入操作
        new Thread(() -> {
            try {
                // 获取主数据库文件路径
                File mainDatabaseFile = requireContext().getDatabasePath("radio_droid_database");
                Log.d("FragmentSettings", "主数据库文件路径: " + mainDatabaseFile.getAbsolutePath());
                
                // 关闭数据库连接
                RadioStationRepository repository = RadioStationRepository.getInstance(requireContext());
                repository.closeDatabase();
                Log.d("FragmentSettings", "数据库连接已关闭");
                
                // 添加延迟，确保数据库完全关闭并释放文件句柄
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // 确保目标目录存在
                File databaseDir = mainDatabaseFile.getParentFile();
                if (!databaseDir.exists()) {
                    databaseDir.mkdirs();
                    Log.d("FragmentSettings", "创建数据库目录: " + databaseDir.getAbsolutePath());
                }
                
                // 如果目标文件已存在，先删除
                if (mainDatabaseFile.exists()) {
                    boolean deleted = mainDatabaseFile.delete();
                    Log.d("FragmentSettings", "删除旧数据库文件: " + deleted);
                    if (!deleted) {
                        throw new Exception(getString(R.string.error_cannot_delete_old_db));
                    }
                    
                    // 添加延迟，确保文件删除完成
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                // 获取源文件信息
                long sourceFileSize = 0;
                try (java.io.InputStream inputStream = requireContext().getContentResolver().openInputStream(uri)) {
                    sourceFileSize = inputStream.available();
                    Log.d("FragmentSettings", "源文件大小: " + sourceFileSize + " 字节");
                    
                    if (sourceFileSize == 0) {
                        throw new Exception(getString(R.string.import_failed_invalid));
                    }
                    
                    // 使用FileChannel进行更可靠的文件复制
                    java.io.FileOutputStream outputStream = new java.io.FileOutputStream(mainDatabaseFile);
                    java.nio.channels.ReadableByteChannel sourceChannel = null;
                    java.nio.channels.FileChannel destChannel = null;
                    
                    try {
                        sourceChannel = java.nio.channels.Channels.newChannel(inputStream);
                        destChannel = outputStream.getChannel();
                        
                        long transferred = destChannel.transferFrom(sourceChannel, 0, sourceFileSize);
                        Log.d("FragmentSettings", "已复制 " + transferred + " 字节到目标文件");
                        
                        if (transferred != sourceFileSize) {
                            Log.w("FragmentSettings", "复制的字节数与源文件大小不一致: " + transferred + " vs " + sourceFileSize);
                        }
                    } finally {
                        if (sourceChannel != null) {
                            sourceChannel.close();
                        }
                        if (destChannel != null) {
                            destChannel.close();
                        }
                        outputStream.close();
                    }
                }
                
                // 验证文件是否成功复制
                if (!mainDatabaseFile.exists()) {
                    throw new Exception(getString(R.string.error_target_file_not_exist));
                }
                
                long targetFileSize = mainDatabaseFile.length();
                Log.d("FragmentSettings", "目标文件大小: " + targetFileSize + " 字节");
                
                if (targetFileSize == 0) {
                    throw new Exception(getString(R.string.import_failed_empty));
                }
                
                if (targetFileSize != sourceFileSize) {
                    Log.w("FragmentSettings", "源文件和目标文件大小不一致: " + sourceFileSize + " vs " + targetFileSize);
                }
                
                // 添加延迟，确保文件写入完成
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // 重新初始化数据库
                repository.reinitializeDatabase(requireContext());
                
                // 添加延迟，确保数据库初始化完成
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // 确保update_timestamp表存在并有有效数据
                repository.ensureUpdateTimestampTable();
                Log.d("FragmentSettings", "已确保update_timestamp表存在");
                
                // 添加延迟，确保表创建完成
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // 验证数据库是否可以正常访问
                int stationCount = repository.getStationCountSync();
                if (stationCount < 0) {
                    throw new Exception(getString(R.string.error_database_corrupted));
                }
                
                Log.d("FragmentSettings", "数据库导入成功，电台数量: " + stationCount);
                
                // 获取数据库中的更新时间戳
                final long dbUpdateTime = repository.getDatabaseUpdateTime();
                Log.d("FragmentSettings", "从数据库读取的更新时间戳: " + dbUpdateTime);
                final int finalStationCount = stationCount;
                
                // 如果数据库中没有时间戳，尝试从文件名中提取
                long finalUpdateTime = dbUpdateTime;
                if (finalUpdateTime <= 0) {
                    try {
                        // 获取文件名
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
                            // 尝试从文件名中提取时间戳 (格式: radio_droid_database_yyyyMMdd_HHmmss_count.db)
                            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("radio_droid_database_(\\d{8})_(\\d{6})_\\d+\\.db");
                            java.util.regex.Matcher matcher = pattern.matcher(fileName);
                            
                            if (matcher.find()) {
                                String dateStr = matcher.group(1); // yyyyMMdd
                                String timeStr = matcher.group(2); // HHmmss
                                String dateTimeStr = dateStr + timeStr; // yyyyMMddHHmmss
                                
                                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.getDefault());
                                java.util.Date date = format.parse(dateTimeStr);
                                finalUpdateTime = date.getTime();
                                
                                Log.d("FragmentSettings", "从文件名提取的时间戳: " + finalUpdateTime);
                                
                                // 更新数据库中的时间戳
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
                
                // 将finalUpdateTime标记为final以便在lambda中使用
                final long finalUpdateTimeForLambda = finalUpdateTime;
                
                // 在UI线程显示结果并更新SharedPreferences
                requireActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    
                    // 计算更新时间
                    String updateTime;
                    if (finalUpdateTimeForLambda > 0) {
                        java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
                        updateTime = dateFormat.format(new java.util.Date(finalUpdateTimeForLambda));
                        Log.d("FragmentSettings", "格式化的更新时间: " + updateTime);
                    } else {
                        // 如果数据库中没有时间戳且无法从文件名提取，显示"数据库尚未更新"
                        updateTime = getString(R.string.database_not_updated);
                        Log.d("FragmentSettings", "数据库中没有时间戳且无法从文件名提取，显示数据库尚未更新");
                    }
                    
                    // 保存更新时间和状态到SharedPreferences
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
                        // 重启应用
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
