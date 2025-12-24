package net.programmierecke.radiodroid2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;
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
import net.programmierecke.radiodroid2.ui.DatabaseUpdateProgressDialog;

import static net.programmierecke.radiodroid2.ActivityMain.FRAGMENT_FROM_BACKSTACK;

import android.os.PowerManager;

public class FragmentSettings extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener, IApplicationSelected, PreferenceFragmentCompat.OnPreferenceStartScreenCallback  {
    
    private DatabaseUpdateProgressDialog updateDialog;
    private ActivityResultLauncher<String> filePickerLauncher;

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
        
        // 检查对话框内部状态，即使isUpdating返回false，如果对话框已存在，也应该尝试恢复
        boolean dialogExists = (updateDialog != null);
        Log.d("FragmentSettings", "updateDialog exists: " + dialogExists);
        
        // 只有在真正有更新进行或对话框已存在时才恢复显示
        if (isUpdating || dialogExists) {
            Log.d("FragmentSettings", "Restoring dialog - isUpdating: " + isUpdating + ", dialogExists: " + dialogExists);
            // 如果有正在进行的更新或对话框已存在，显示进度对话框
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
                                    // 注意：这里只检查对话框状态，不再检查isUpdating，避免重复启动更新
                                    Log.d("FragmentSettings", "In delayed runnable: updateDialog.isShowing=" + (updateDialog != null ? updateDialog.isShowing() : "null"));
                                    
                                    // 如果对话框存在且之前是显示状态，显示它
                                    if (updateDialog != null && dialogExists && 
                                        isAdded() && getActivity() != null && !getActivity().isFinishing() && !getActivity().isDestroyed()) {
                                        updateDialog.show();
                                        Log.d("FragmentSettings", "Dialog shown successfully");
                                    } else if (updateDialog != null && isUpdating && 
                                        isAdded() && getActivity() != null && !getActivity().isFinishing() && !getActivity().isDestroyed()) {
                                        // 只有在真正有更新进行时才显示对话框
                                        updateDialog.show();
                                        Log.d("FragmentSettings", "Dialog shown for active update");
                                    } else {
                                        Log.d("FragmentSettings", "Cannot show dialog: conditions not met - updateDialog=" + (updateDialog != null ? "not null" : "null") + 
                                                  ", isUpdating=" + isUpdating + 
                                                  ", dialogExists=" + dialogExists +
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

                    if (getContext().getPackageManager().resolveActivity(intent, 0) == null) {
                        Toast.makeText(getContext(), R.string.error_no_equalizer_found, Toast.LENGTH_SHORT).show();
                    } else {
                        intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
                        startActivityForResult(intent, ActivityMain.LAUNCH_EQUALIZER_REQUEST);
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
            findPreference("update_local_database").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    // 启动数据库更新
                    DatabaseUpdateManager.startUpdate(requireContext());
                    
                    // 显示进度对话框
                    if (updateDialog != null && updateDialog.isShowing()) {
                        // 如果对话框已经在显示，不做任何操作
                    } else {
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
                        androidx.appcompat.app.AlertDialog.Builder choiceBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
                        choiceBuilder.setTitle("网络连接检查");
                        
                        // Calculate when the test was performed
                        String timeString = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            .format(new java.util.Date(savedResultsData.timestamp));
                        
                        choiceBuilder.setMessage("已有保存的测试结果（" + timeString + "）。\n\n您想要查看保存的结果还是进行新的测试？");
                        
                        choiceBuilder.setPositiveButton("查看保存的结果", (dialog, which) -> {
                            showNetworkConnectionResults(savedResultsData.results, savedResultsData.timestamp);
                        });
                        
                        choiceBuilder.setNegativeButton("进行新的测试", (dialog, which) -> {
                            performNewNetworkTest();
                        });
                        
                        choiceBuilder.setNeutralButton("取消", null);
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
                    androidx.appcompat.app.AlertDialog.Builder confirmBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
                    confirmBuilder.setTitle("导出主数据库");
                    confirmBuilder.setMessage("是否将主数据库导出到外部存储？导出的文件将保存在Download文件夹中。");
                    
                    confirmBuilder.setPositiveButton("导出", (dialog, which) -> {
                        exportDatabase();
                    });
                    
                    confirmBuilder.setNegativeButton("取消", null);
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
                    androidx.appcompat.app.AlertDialog.Builder confirmBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
                    confirmBuilder.setTitle("导入主数据库");
                    confirmBuilder.setMessage("是否从外部存储导入主数据库？\n\n注意：导入完成后应用将自动重启以加载新数据库。\n\n这将覆盖当前的主数据库，请确保已备份重要数据。");
                    
                    confirmBuilder.setPositiveButton("选择文件", (dialog, which) -> {
                        // 打开文件选择器，用户可以导航到Download/RadioDroid文件夹选择.db文件
                        filePickerLauncher.launch("*/*");
                    });
                    
                    confirmBuilder.setNegativeButton("取消", null);
                    confirmBuilder.show();
                    
                    return false;
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

//            findPreference("show_about").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
//                @Override
//                public boolean onPreferenceClick(Preference preference) {
//                    ((ActivityMain) getActivity()).getToolbar().setTitle(R.string.settings_about);
//                    FragmentAbout f = new FragmentAbout();
//                    FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
//                    fragmentTransaction.replace(R.id.containerView, f).addToBackStack(String.valueOf(FRAGMENT_FROM_BACKSTACK)).commit();
//                    return false;
//                }
//            });
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
        
        // Title
        TextView titleView = new TextView(requireContext());
        titleView.setText("网络连接速度测试结果");
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(0, 0, 0, 30);
        linearLayout.addView(titleView);
        
        // Test time
        TextView timeView = new TextView(requireContext());
        if (timestamp > 0) {
            String timeString = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date(timestamp));
            timeView.setText("测试时间: " + timeString);
        } else {
            timeView.setText("测试时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date()));
        }
        timeView.setTextSize(14);
        timeView.setPadding(0, 0, 0, 20);
        timeView.setTextColor(Color.GRAY);
        linearLayout.addView(timeView);
        
        // Server 1
        TextView server1Title = new TextView(requireContext());
        server1Title.setText("服务器 1: fi1.api.radio-browser.info");
        server1Title.setTextSize(16);
        server1Title.setTypeface(null, Typeface.BOLD);
        server1Title.setPadding(0, 20, 0, 10);
        linearLayout.addView(server1Title);
        
        // Server 1 HTTP
        TextView server1Http = new TextView(requireContext());
        long httpTime1 = results.get("fi1.api.radio-browser.info_HTTP");
        server1Http.setText("HTTP: " + (httpTime1 == Long.MAX_VALUE ? "连接失败" : httpTime1 + " ms"));
        server1Http.setTextSize(14);
        server1Http.setPadding(30, 5, 0, 5);
        server1Http.setTextColor(httpTime1 == Long.MAX_VALUE ? Color.RED : Color.BLACK);
        linearLayout.addView(server1Http);
        
        // Server 1 HTTPS
        TextView server1Https = new TextView(requireContext());
        long httpsTime1 = results.get("fi1.api.radio-browser.info_HTTPS");
        server1Https.setText("HTTPS: " + (httpsTime1 == Long.MAX_VALUE ? "连接失败" : httpsTime1 + " ms"));
        server1Https.setTextSize(14);
        server1Https.setPadding(30, 5, 0, 5);
        server1Https.setTextColor(httpsTime1 == Long.MAX_VALUE ? Color.RED : Color.BLACK);
        linearLayout.addView(server1Https);
        
        // Server 2
        TextView server2Title = new TextView(requireContext());
        server2Title.setText("服务器 2: de2.api.radio-browser.info");
        server2Title.setTextSize(16);
        server2Title.setTypeface(null, Typeface.BOLD);
        server2Title.setPadding(0, 20, 0, 10);
        linearLayout.addView(server2Title);
        
        // Server 2 HTTP
        TextView server2Http = new TextView(requireContext());
        long httpTime2 = results.get("de2.api.radio-browser.info_HTTP");
        server2Http.setText("HTTP: " + (httpTime2 == Long.MAX_VALUE ? "连接失败" : httpTime2 + " ms"));
        server2Http.setTextSize(14);
        server2Http.setPadding(30, 5, 0, 5);
        server2Http.setTextColor(httpTime2 == Long.MAX_VALUE ? Color.RED : Color.BLACK);
        linearLayout.addView(server2Http);
        
        // Server 2 HTTPS
        TextView server2Https = new TextView(requireContext());
        long httpsTime2 = results.get("de2.api.radio-browser.info_HTTPS");
        server2Https.setText("HTTPS: " + (httpsTime2 == Long.MAX_VALUE ? "连接失败" : httpsTime2 + " ms"));
        server2Https.setTextSize(14);
        server2Https.setPadding(30, 5, 0, 5);
        server2Https.setTextColor(httpsTime2 == Long.MAX_VALUE ? Color.RED : Color.BLACK);
        linearLayout.addView(server2Https);
        
        // Fastest connection
        TextView fastestTitle = new TextView(requireContext());
        fastestTitle.setText("最快连接:");
        fastestTitle.setTextSize(16);
        fastestTitle.setTypeface(null, Typeface.BOLD);
        fastestTitle.setPadding(0, 20, 0, 10);
        linearLayout.addView(fastestTitle);
        
        // Find the fastest connection
        long minTime = Long.MAX_VALUE;
        String fastestConnection = "无";
        
        for (Map.Entry<String, Long> entry : results.entrySet()) {
            if (entry.getValue() < minTime) {
                minTime = entry.getValue();
                String[] parts = entry.getKey().split("_");
                fastestConnection = parts[0] + " (" + parts[1] + ")";
            }
        }
        
        TextView fastestResult = new TextView(requireContext());
        fastestResult.setText(fastestConnection + " - " + (minTime == Long.MAX_VALUE ? "无可用连接" : minTime + " ms"));
        fastestResult.setTextSize(14);
        fastestResult.setPadding(30, 5, 0, 5);
        fastestResult.setTextColor(minTime == Long.MAX_VALUE ? Color.RED : Color.parseColor("#008000"));
        linearLayout.addView(fastestResult);
        
        scrollView.addView(linearLayout);
        
        // Create and show the dialog
        androidx.appcompat.app.AlertDialog.Builder resultsBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
        resultsBuilder.setView(scrollView);
        resultsBuilder.setPositiveButton("确定", null);
        resultsBuilder.show();
    }
    
    // Method to perform a new network test
    private void performNewNetworkTest() {
        // Show progress dialog
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
        builder.setTitle(R.string.settings_check_network_connection);
        builder.setMessage("正在检查网络连接速度...");
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
                    androidx.appcompat.app.AlertDialog.Builder errorBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
                    errorBuilder.setTitle("网络检查失败");
                    errorBuilder.setMessage("无法连接到电台浏览器服务器: " + e.getMessage());
                    errorBuilder.setPositiveButton("确定", null);
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
        
        // 加载每个服务器和协议的结果
        results.put("fi1.api.radio-browser.info_HTTP", 
            sharedPref.getLong("fi1.api.radio-browser.info_HTTP", Long.MAX_VALUE));
        results.put("fi1.api.radio-browser.info_HTTPS", 
            sharedPref.getLong("fi1.api.radio-browser.info_HTTPS", Long.MAX_VALUE));
        results.put("de2.api.radio-browser.info_HTTP", 
            sharedPref.getLong("de2.api.radio-browser.info_HTTP", Long.MAX_VALUE));
        results.put("de2.api.radio-browser.info_HTTPS", 
            sharedPref.getLong("de2.api.radio-browser.info_HTTPS", Long.MAX_VALUE));
        
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
    public void onResume() {
        super.onResume();
        Log.d("FragmentSettings", "onResume called");
        
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);

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
        
        // Update database status when settings screen is loaded
        updateDatabaseStatusOnLoad();
        
        // 检查是否有正在进行的数据库更新，如果有则恢复显示进度对话框
        checkAndRestoreUpdateDialog();
        
        // 更新应用前台时间 - 移到checkAndRestoreUpdateDialog之后，避免取消正在进行的更新
        DatabaseUpdateWorker.updateAppForegroundTime(requireContext());
    }

    @Override
    public void onPause() {
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        
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
                // 获取本地数据库中的电台数量
                RadioStationRepository repository = RadioStationRepository.getInstance(getContext());
                
                // 如果需要使用数据库时间戳，先获取数据库更新时间
                if (useDatabaseTimestamp) {
                    long dbUpdateTime = repository.getDatabaseUpdateTime();
                    String timestamp;
                    if (dbUpdateTime > 0) {
                        timestamp = java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(dbUpdateTime));
                    } else {
                        // 如果没有数据库时间戳，使用当前时间
                        timestamp = java.text.DateFormat.getDateTimeInstance().format(new java.util.Date());
                    }
                    
                    repository.getStationCount(new RadioStationRepository.StationCountCallback() {
                        @Override
                        public void onStationCountReceived(int count) {
                            // 在UI线程更新状态显示
                            if (getActivity() != null && isAdded()) {
                                getActivity().runOnUiThread(() -> {
                                    // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                                    if (isAdded() && getContext() != null) {
                                        statusPref.setSummary(getString(R.string.settings_local_database_status_success, timestamp, count));
                                    }
                                });
                            }
                        }

                        @Override
                        public void onError(String error) {
                            // 如果获取电台数量失败，只显示时间
                            if (getActivity() != null && isAdded()) {
                                getActivity().runOnUiThread(() -> {
                                    // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                                    if (isAdded() && getContext() != null) {
                                        statusPref.setSummary(getString(R.string.settings_local_database_status_success, timestamp, 0));
                                    }
                                });
                            }
                        }
                    });
                    
                    // Save status to SharedPreferences
                    editor.putString("local_database_last_status", "success");
                    editor.putString("local_database_last_update", timestamp);
                } else {
                    // 使用当前时间戳（原有逻辑）
                    String timestamp = java.text.DateFormat.getDateTimeInstance().format(new java.util.Date());
                    
                    repository.getStationCount(new RadioStationRepository.StationCountCallback() {
                        @Override
                        public void onStationCountReceived(int count) {
                            // 在UI线程更新状态显示
                            if (getActivity() != null && isAdded()) {
                                getActivity().runOnUiThread(() -> {
                                    // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                                    if (isAdded() && getContext() != null) {
                                        statusPref.setSummary(getString(R.string.settings_local_database_status_success, timestamp, count));
                                    }
                                });
                            }
                        }

                        @Override
                        public void onError(String error) {
                            // 如果获取电台数量失败，只显示时间
                            if (getActivity() != null && isAdded()) {
                                getActivity().runOnUiThread(() -> {
                                    // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                                    if (isAdded() && getContext() != null) {
                                        statusPref.setSummary(getString(R.string.settings_local_database_status_success, timestamp, 0));
                                    }
                                });
                            }
                        }
                    });
                    
                    // Save status to SharedPreferences
                    editor.putString("local_database_last_status", "success");
                    editor.putString("local_database_last_update", timestamp);
                }
            } else {
                statusPref.setSummary(getString(R.string.settings_local_database_status_failed, error));
                
                // Save status to SharedPreferences
                editor.putString("local_database_last_status", "failed");
                editor.putString("local_database_last_update", error);
            }
            
            editor.apply();
        }
    }
    
    private void updateDatabaseStatusOnLoad() {
        Preference statusPref = findPreference("local_database_status");
        if (statusPref != null) {
            // Check if we have a stored update status
            SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
            String lastUpdateStatus = prefs.getString("local_database_last_status", null);
            String lastUpdateTime = prefs.getString("local_database_last_update", null);
            
            if (lastUpdateStatus != null && lastUpdateTime != null) {
                if ("success".equals(lastUpdateStatus)) {
                    // 获取本地数据库中的电台数量
                    RadioStationRepository repository = RadioStationRepository.getInstance(getContext());
                    repository.getStationCount(new RadioStationRepository.StationCountCallback() {
                        @Override
                        public void onStationCountReceived(int count) {
                            // 在UI线程更新状态显示
                            if (getActivity() != null && isAdded()) {
                                getActivity().runOnUiThread(() -> {
                                    // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                                    if (isAdded() && getContext() != null) {
                                        statusPref.setSummary(getString(R.string.settings_local_database_status_success, lastUpdateTime, count));
                                    }
                                });
                            }
                        }

                        @Override
                        public void onError(String error) {
                            // 如果获取电台数量失败，只显示时间
                            if (getActivity() != null && isAdded()) {
                                getActivity().runOnUiThread(() -> {
                                    // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                                    if (isAdded() && getContext() != null) {
                                        statusPref.setSummary(getString(R.string.settings_local_database_status_success, lastUpdateTime, 0));
                                    }
                                });
                            }
                        }
                    });
                } else {
                    if (isAdded() && getContext() != null) {
                        statusPref.setSummary(getString(R.string.settings_local_database_status_failed, lastUpdateTime));
                    }
                }
            } else {
                if (isAdded() && getContext() != null) {
                    statusPref.setSummary(getString(R.string.settings_local_database_status_default));
                }
            }
        }
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
        androidx.appcompat.app.AlertDialog.Builder progressBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
        progressBuilder.setTitle("导出数据库");
        progressBuilder.setMessage("正在导出主数据库，请稍候...");
        androidx.appcompat.app.AlertDialog progressDialog = progressBuilder.create();
        progressDialog.show();
        
        // 在后台线程执行导出操作
        new Thread(() -> {
            try {
                // 获取主数据库文件路径
                File mainDatabaseFile = requireContext().getDatabasePath("radio_droid_database");
                
                // 获取电台数量
                RadioStationRepository repository = RadioStationRepository.getInstance(requireContext());
                int stationCount = repository.getStationCountSync();
                
                // 创建导出目录
                File exportDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RadioDroid");
                if (!exportDir.exists()) {
                    exportDir.mkdirs();
                }
                
                // 创建导出文件名（包含时间戳和电台数量）
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
                String timestamp = sdf.format(new Date());
                File exportFile = new File(exportDir, "radio_droid_database_" + timestamp + "_" + stationCount + ".db");
                
                // 复制数据库文件
                FileChannel source = new FileInputStream(mainDatabaseFile).getChannel();
                FileChannel destination = new FileOutputStream(exportFile).getChannel();
                destination.transferFrom(source, 0, source.size());
                source.close();
                destination.close();
                
                // 在UI线程显示结果
                requireActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    androidx.appcompat.app.AlertDialog.Builder successBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
                    successBuilder.setTitle("导出成功");
                    successBuilder.setMessage("主数据库已成功导出到：\n" + exportFile.getAbsolutePath());
                    successBuilder.setPositiveButton("确定", null);
                    successBuilder.show();
                });
                
            } catch (IOException e) {
                e.printStackTrace();
                // 在UI线程显示错误
                requireActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    androidx.appcompat.app.AlertDialog.Builder errorBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
                    errorBuilder.setTitle("导出失败");
                    errorBuilder.setMessage("导出主数据库时发生错误：" + e.getMessage());
                    errorBuilder.setPositiveButton("确定", null);
                    errorBuilder.show();
                });
            }
        }).start();
    }
    
    // 从外部存储导入主数据库
    private void importDatabase(Uri uri) {
        // 显示进度对话框
        androidx.appcompat.app.AlertDialog.Builder progressBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
        progressBuilder.setTitle("导入数据库");
        progressBuilder.setMessage("正在导入主数据库，请稍候...");
        androidx.appcompat.app.AlertDialog progressDialog = progressBuilder.create();
        progressDialog.show();
        
        // 在后台线程执行导入操作
        new Thread(() -> {
            try {
                // 获取主数据库文件路径
                File mainDatabaseFile = requireContext().getDatabasePath("radio_droid_database");
                
                // 创建备份文件
                File backupFile = new File(mainDatabaseFile.getParent(), "radio_droid_database_backup_" + 
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".db");
                
                // 复制当前数据库到备份文件
                if (mainDatabaseFile.exists()) {
                    FileChannel source = new FileInputStream(mainDatabaseFile).getChannel();
                    FileChannel backup = new FileOutputStream(backupFile).getChannel();
                    backup.transferFrom(source, 0, source.size());
                    source.close();
                    backup.close();
                }
                
                // 关闭数据库连接
                RadioStationRepository repository = RadioStationRepository.getInstance(requireContext());
                repository.closeDatabase();
                
                // 从Uri复制文件到主数据库
                java.io.InputStream inputStream = requireContext().getContentResolver().openInputStream(uri);
                FileChannel destination = new FileOutputStream(mainDatabaseFile).getChannel();
                java.nio.channels.ReadableByteChannel sourceChannel = java.nio.channels.Channels.newChannel(inputStream);
                long transferred = 0;
                long size = 0;
                
                // 使用缓冲区复制文件
                byte[] buffer = new byte[8192];
                int bytesRead;
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                inputStream.close();
                
                // 将数据写入目标文件
                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(baos.toByteArray());
                java.nio.channels.ReadableByteChannel finalSourceChannel = java.nio.channels.Channels.newChannel(bais);
                destination.transferFrom(finalSourceChannel, 0, baos.size());
                finalSourceChannel.close();
                bais.close();
                destination.close();
                
                // 重新初始化数据库
                repository.reinitializeDatabase(requireContext());
                
                // 在UI线程显示结果
                requireActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    androidx.appcompat.app.AlertDialog.Builder successBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
                    successBuilder.setTitle("导入成功");
                    successBuilder.setMessage("主数据库已成功导入。\n原数据库已备份为：\n" + backupFile.getAbsolutePath() + "\n\n应用将自动重启以加载新数据库。");
                    successBuilder.setPositiveButton("确定", (dialog, which) -> {
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
                e.printStackTrace();
                // 在UI线程显示错误
                requireActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    androidx.appcompat.app.AlertDialog.Builder errorBuilder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
                    errorBuilder.setTitle("导入失败");
                    errorBuilder.setMessage("导入主数据库时发生错误：" + e.getMessage());
                    errorBuilder.setPositiveButton("确定", null);
                    errorBuilder.show();
                });
            }
        }).start();
    }
}
