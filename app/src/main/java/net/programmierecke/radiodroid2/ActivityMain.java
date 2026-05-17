package net.programmierecke.radiodroid2;


import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.GravityCompat;
import androidx.core.view.MenuItemCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;


import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.navigation.NavigationView;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.DialogFragment;

import com.bytehamster.lib.preferencesearch.SearchPreferenceResult;
import com.bytehamster.lib.preferencesearch.SearchPreferenceResultListener;
import com.google.android.material.tabs.TabLayout;
import com.mikepenz.iconics.Iconics;
import com.rustamg.filedialogs.FileDialog;
import com.rustamg.filedialogs.OpenFileDialog;
import com.rustamg.filedialogs.SaveFileDialog;

import net.programmierecke.radiodroid2.alarm.FragmentAlarm;
import net.programmierecke.radiodroid2.alarm.TimePickerFragment;
import net.programmierecke.radiodroid2.station.FragmentLocalStations;
import net.programmierecke.radiodroid2.FragmentStarred;
import net.programmierecke.radiodroid2.FragmentTabs;
import net.programmierecke.radiodroid2.cast.CastAwareActivity;
import net.programmierecke.radiodroid2.database.RadioStation;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.interfaces.IFragmentSearchable;
import net.programmierecke.radiodroid2.players.PlayState;
import net.programmierecke.radiodroid2.players.PlayStationTask;
import net.programmierecke.radiodroid2.players.mpd.MPDClient;
import net.programmierecke.radiodroid2.players.mpd.MPDServersRepository;
import net.programmierecke.radiodroid2.players.selector.PlayerType;
import net.programmierecke.radiodroid2.service.MediaSessionCallback;
import net.programmierecke.radiodroid2.service.PlayerService;
import net.programmierecke.radiodroid2.service.PlayerServiceUtil;
import net.programmierecke.radiodroid2.station.DataRadioStation;
import net.programmierecke.radiodroid2.station.StationsFilter;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;



import static net.programmierecke.radiodroid2.service.MediaSessionCallback.EXTRA_STATION_UUID;

public class ActivityMain extends AppCompatActivity implements SearchView.OnQueryTextListener,
        NavigationView.OnNavigationItemSelectedListener,
        BottomNavigationView.OnNavigationItemSelectedListener,
        FileDialog.OnFileSelectedListener,
        TimePickerDialog.OnTimeSetListener,
        SearchPreferenceResultListener,
        CastAwareActivity {

    public static final String EXTRA_SEARCH_TAG = "search_tag";

    public static final int LAUNCH_EQUALIZER_REQUEST = 1;

    public final static int MAX_DYNAMIC_LAUNCHER_SHORTCUTS = 4;

    public static final int FRAGMENT_FROM_BACKSTACK = 777;

    public static final String ACTION_SHOW_LOADING = "net.programmierecke.radiodroid2.show_loading";
    public static final String ACTION_HIDE_LOADING = "net.programmierecke.radiodroid2.hide_loading";

    private static final String TAG = "RadioDroid";

    private final String TAG_SEARCH_URL = "json/stations/bytagexact";
    private final String SAVE_LAST_MENU_ITEM = "LAST_MENU_ITEM";

    public static final int PERM_REQ_STORAGE_FAV_SAVE = 1;
    public static final int PERM_REQ_STORAGE_FAV_LOAD = 2;

    // Request code for creating a PDF document.
    private static final int ACTION_SAVE_FILE = 1;
    private static final int ACTION_LOAD_FILE = 2;

    private SearchView mSearchView;

    private AppBarLayout appBarLayout;
    private TabLayout tabsView;

    DrawerLayout mDrawerLayout;
    NavigationView mNavigationView;
    BottomNavigationView mBottomNavigationView;
    FragmentManager mFragmentManager;

    private BottomSheetBehavior playerBottomSheet;

    private FragmentPlayerSmall smallPlayerFragment;
    private FragmentPlayerFull fullPlayerFragment;

    BroadcastReceiver broadcastReceiver;

    MenuItem menuItemSearch;
    MenuItem menuItemDelete;
    MenuItem menuItemSleepTimer;
    MenuItem menuItemSave;
    MenuItem menuItemLoad;
    MenuItem menuItemIconsView;
    MenuItem menuItemListView;
    MenuItem menuItemAddAlarm;
    MenuItem menuItemMpd;
    MenuItem menuItemRandomPlay;
    MenuItem menuItemSort;

    private SharedPreferences sharedPref;

    private int selectedMenuItem;

    private boolean instanceStateWasSaved;

    private Date lastExitTry;

    private AlertDialog meteredConnectionAlertDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Iconics.init(this);

        if (getIntent() != null && getIntent().getBooleanExtra("close_app", false)) {
            super.onCreate(savedInstanceState);
            finishAffinity();
            System.exit(0);
            return;
        }

        initAppLanguage();

        super.onCreate(savedInstanceState);

        if (sharedPref == null) {
            PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
            sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        }
        setTheme(Utils.getThemeResId(this));
        setContentView(R.layout.layout_main);

        Log.d(TAG, "FilesDir: "+getFilesDir().getAbsolutePath());
        Log.d(TAG, "CacheDir: "+getCacheDir().getAbsolutePath());
        try {
            File dir = new File(getFilesDir().getAbsolutePath());
            if (dir.isDirectory()) {

                String[] children = dir.list();
                for (String aChildren : children) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "delete file:" + aChildren);
                    }
                    try {
                        new File(dir, aChildren).delete();
                    } catch (Exception e) {
                    }
                }
            }
        } catch (Exception e) {
        }

        final Toolbar myToolbar = findViewById(R.id.my_awesome_toolbar);
        setSupportActionBar(myToolbar);

        PlayerServiceUtil.startService(getApplicationContext());

        selectedMenuItem = sharedPref.getInt("last_selectedMenuItem", -1);
        instanceStateWasSaved = savedInstanceState != null;
        mFragmentManager = getSupportFragmentManager();

        appBarLayout = findViewById(R.id.app_bar_layout);
        tabsView = findViewById(R.id.tabs);
        mDrawerLayout = findViewById(R.id.drawerLayout);
        mNavigationView = findViewById(R.id.my_navigation_view);
        mBottomNavigationView = findViewById(R.id.bottom_navigation);

        if (Utils.bottomNavigationEnabled(this)) {
            mBottomNavigationView.setOnNavigationItemSelectedListener(this);
            mNavigationView.setVisibility(View.GONE);
            mNavigationView.getLayoutParams().width = 0;
        } else {
            mNavigationView.setNavigationItemSelectedListener(this);
            mBottomNavigationView.setVisibility(View.GONE);

            ActionBarDrawerToggle mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout, R.string.app_name, R.string.app_name);
            mDrawerLayout.addDrawerListener(mDrawerToggle);
            mDrawerToggle.syncState();

            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
        }

        smallPlayerFragment = (FragmentPlayerSmall) mFragmentManager.findFragmentById(R.id.fragment_player_small);
        fullPlayerFragment = (FragmentPlayerFull) mFragmentManager.findFragmentById(R.id.fragment_player_full);

        if (smallPlayerFragment == null || fullPlayerFragment == null) {
            smallPlayerFragment = new FragmentPlayerSmall();
            fullPlayerFragment = new FragmentPlayerFull();

            FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
            // Hide it at start to make .onHiddenChanged be called on first show
            fragmentTransaction.hide(fullPlayerFragment);
            fragmentTransaction.replace(R.id.fragment_player_small, smallPlayerFragment);
            fragmentTransaction.replace(R.id.fragment_player_full, fullPlayerFragment);
            fragmentTransaction.commit();
        }

        smallPlayerFragment.setCallback(new FragmentPlayerSmall.Callback() {
            @Override
            public void onToggle() {
                toggleBottomSheetState();
            }
        });
        fullPlayerFragment.setTouchInterceptListener(new FragmentPlayerFull.TouchInterceptListener() {
            @Override
            public void requestDisallowInterceptTouchEvent(boolean disallow) {
                findViewById(R.id.bottom_sheet).getParent().requestDisallowInterceptTouchEvent(disallow);
            }
        });

        // Disable ability of ToolBar to follow bottom sheet because it doesn't work well with
        // our custom RecyclerAwareNestedScrollView
        CoordinatorLayout.LayoutParams coordinatorLayoutParams = (CoordinatorLayout.LayoutParams) appBarLayout.getLayoutParams();
        AppBarLayout.Behavior appBarLayoutBehavior = new AppBarLayout.Behavior() {
            @Override
            public boolean onStartNestedScroll(CoordinatorLayout parent, AppBarLayout child, View directTargetChild, View target, int nestedScrollAxes, int type) {
                return playerBottomSheet.getState() == BottomSheetBehavior.STATE_COLLAPSED;
            }
        };

        coordinatorLayoutParams.setBehavior(appBarLayoutBehavior);

        playerBottomSheet = BottomSheetBehavior.from(findViewById(R.id.bottom_sheet));
        playerBottomSheet.setBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            private int oldState = BottomSheetBehavior.STATE_COLLAPSED;

            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                // Prevent bottom sheet from minimizing if its content isn't scrolled to the top
                // Essentially this is a cheap hack to prevent bottom sheet from being dragged by non-scrolling elements.
                if (newState == BottomSheetBehavior.STATE_DRAGGING && oldState == BottomSheetBehavior.STATE_EXPANDED) {
                    if (fullPlayerFragment.isScrolled()) {
                        playerBottomSheet.setState(BottomSheetBehavior.STATE_EXPANDED);
                        return;
                    }
                }

                // Small player should serve as header if full screen player is expanded.
                // Hide full screen player's fragment if it is not visible to reduce resource usage.

                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    if (smallPlayerFragment.getContext() == null)
                        return;

                    appBarLayout.setExpanded(false);
                    smallPlayerFragment.setRole(FragmentPlayerSmall.Role.HEADER);

                    FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
                    fragmentTransaction.hide(mFragmentManager.findFragmentById(R.id.containerView));
                    fragmentTransaction.commit();
                } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    appBarLayout.setExpanded(true);
                    smallPlayerFragment.setRole(FragmentPlayerSmall.Role.PLAYER);
                    fullPlayerFragment.resetScroll();

                    FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
                    fragmentTransaction.hide(fullPlayerFragment);
                    fragmentTransaction.commit();
                }

                if (oldState == BottomSheetBehavior.STATE_EXPANDED && newState != BottomSheetBehavior.STATE_EXPANDED) {
                    FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
                    fragmentTransaction.show(mFragmentManager.findFragmentById(R.id.containerView));
                    fragmentTransaction.commit();
                }

                if (oldState == BottomSheetBehavior.STATE_COLLAPSED && newState != oldState) {
                    fullPlayerFragment.init();

                    FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
                    fragmentTransaction.show(fullPlayerFragment);
                    fragmentTransaction.commit();
                }

                oldState = newState;
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {

            }
        });

        ((RadioDroidApp) getApplication()).getCastHandler().onCreate(this);

        // 处理启动时的Intent
        Intent intent = getIntent();
        if (intent != null) {
            handleIntent(intent);
            // 不在这里setIntent(null)，让onResume也能处理
        }

        setupStartUpFragment();
    }



    @Override
    public boolean onNavigationItemSelected(MenuItem menuItem) {
        // If menuItem == null method was executed manually
        if (menuItem != null)
            selectedMenuItem = menuItem.getItemId();

        if (playerBottomSheet.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            playerBottomSheet.setState(BottomSheetBehavior.STATE_COLLAPSED);
        }

        if (mSearchView != null) {
            mSearchView.clearFocus();
        }

        mDrawerLayout.closeDrawers();
        Fragment f = null;
        String backStackTag = String.valueOf(selectedMenuItem);

        if (selectedMenuItem == R.id.nav_item_stations) {
            f = new FragmentTabs();
            // 如果是从搜索按钮点击进入的，需要自动切换到搜索标签页
            if (menuItem == null && selectedMenuItem == R.id.nav_item_stations) {
                // 延迟执行，确保FragmentTabs已经完全初始化
                new android.os.Handler().postDelayed(() -> {
                    Fragment currentFragment = mFragmentManager.findFragmentById(R.id.containerView);
                    if (currentFragment instanceof FragmentTabs) {
                        ((FragmentTabs) currentFragment).search(StationsFilter.SearchStyle.ByName, "");
                    }
                }, 100);
            }
        } else if (selectedMenuItem == R.id.nav_item_multi_search) {
            f = new net.programmierecke.radiodroid2.station.FragmentMultiSearch();
        } else if (selectedMenuItem == R.id.nav_item_starred) {
            f = new FragmentStarred();
        } else if (selectedMenuItem == R.id.nav_item_history) {
            f = new FragmentHistory();
        } else if (selectedMenuItem == R.id.nav_item_alarm) {
            f = new FragmentAlarm();
        } else if (selectedMenuItem == R.id.nav_item_settings) {
            f = new FragmentSettings();
        }

        // Without "Immediate", "Settings" fragment may become forever stuck in limbo receiving onResume.
        // I'm not sure why.
        mFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
        if (Utils.bottomNavigationEnabled(this))
            fragmentTransaction.replace(R.id.containerView, f).commit();
        else
            fragmentTransaction.replace(R.id.containerView, f).addToBackStack(backStackTag).commit();

        // User selected a menuItem. Let's hide progressBar
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ActivityMain.ACTION_HIDE_LOADING));
        invalidateOptionsMenu();
        checkMenuItems();

        appBarLayout.setExpanded(true);

        return false;
    }

    @Override
    public void onBackPressed() {
        if (playerBottomSheet.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            playerBottomSheet.setState(BottomSheetBehavior.STATE_COLLAPSED);
            return;
        }

        int backStackCount = mFragmentManager.getBackStackEntryCount();
        FragmentManager.BackStackEntry backStackEntry;

        if (backStackCount > 0) {
            // FRAGMENT_FROM_BACKSTACK value added as a backstack name for non-root fragments like Recordings, About, etc
            backStackEntry = mFragmentManager.getBackStackEntryAt(mFragmentManager.getBackStackEntryCount() - 1);
            if (backStackEntry.getName().equals("SearchPreferenceFragment")) {
                super.onBackPressed();
                return;
            }
            int parsedId = Integer.parseInt(backStackEntry.getName());
            if (parsedId == FRAGMENT_FROM_BACKSTACK) {
                super.onBackPressed();
                invalidateOptionsMenu();
                return;
            }
        }

        // Don't support backstack with BottomNavigationView
        if (Utils.bottomNavigationEnabled(this)) {
            // I'm giving 3 seconds on making a choice
            if (lastExitTry != null && new Date().getTime() < lastExitTry.getTime() + 3 * 1000) {
                PlayerServiceUtil.shutdownService();
                finish();
            } else {
                Toast.makeText(this, R.string.alert_press_back_to_exit, Toast.LENGTH_SHORT).show();
                lastExitTry = new Date();
                return;
            }
        }

        if (backStackCount > 1) {
            backStackEntry = mFragmentManager.getBackStackEntryAt(mFragmentManager.getBackStackEntryCount() - 2);

            selectedMenuItem = Integer.parseInt(backStackEntry.getName());

            if (!Utils.bottomNavigationEnabled(this)) {
                mNavigationView.setCheckedItem(selectedMenuItem);
            }
            invalidateOptionsMenu();

        } else {
            finish();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("close_app", false)) {
            finishAffinity();
            System.exit(0);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "on request permissions result:" + requestCode);
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            case PERM_REQ_STORAGE_FAV_LOAD: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    LoadFavourites();
                } else {
                    LoadFavourites();
                }
                return;
            }
            case PERM_REQ_STORAGE_FAV_SAVE: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    SaveFavourites();
                } else {
                    SaveFavourites();
                }
                return;
            }
        }
    }
    
    /**
     * 导航到设置页面
     */
    private void navigateToSettings() {
        Fragment f = new FragmentSettings();
        
        // 清除回退栈并添加设置片段
        mFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
        if (Utils.bottomNavigationEnabled(this))
            fragmentTransaction.replace(R.id.containerView, f).commit();
        else
            fragmentTransaction.replace(R.id.containerView, f).addToBackStack(null).commit();
            
        // 隐藏加载进度条
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ActivityMain.ACTION_HIDE_LOADING));
        invalidateOptionsMenu();
    }

    /**
     * 导航到设置页面的数据库更新部分
     */
    private void navigateToDatabaseUpdate() {
        // 直接打开数据库更新子屏幕
        FragmentSettings fragmentSettings = new FragmentSettings();
        
        // 创建Bundle并设置要打开的子屏幕
        Bundle args = new Bundle();
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, "pref_category_local_database_update");
        fragmentSettings.setArguments(args);
        
        // 清除回退栈并添加设置片段
        mFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
        if (Utils.bottomNavigationEnabled(this))
            fragmentTransaction.replace(R.id.containerView, fragmentSettings).commit();
        else
            fragmentTransaction.replace(R.id.containerView, fragmentSettings).addToBackStack(null).commit();
            
        // 隐藏加载进度条
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ActivityMain.ACTION_HIDE_LOADING));
        invalidateOptionsMenu();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (!PlayerServiceUtil.isNotificationActive()) {
            /* If at this point if for whatever reason we have the service without a notification,
             * we must shut it down because user doesn't have a way to interact with it.
             * This is a safeguard since such service should have been destroyed in onPause()
             */
            PlayerServiceUtil.shutdownService();
        }
    }

    @Override
    protected void onPause() {
        SharedPreferences.Editor ed = sharedPref.edit();
        ed.putInt("last_selectedMenuItem", selectedMenuItem);
        ed.apply();

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "PAUSED");
        }

        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);

        super.onPause();

        if (PlayerServiceUtil.getPlayerState() == PlayState.Idle) {
            PlayerServiceUtil.shutdownService();
        }

        CastHandler castHandler = ((RadioDroidApp) getApplication()).getCastHandler();
        castHandler.onPause();
        castHandler.setActivity(null);
    }

    private void handleIntent(@NonNull Intent intent) {
        String action = intent.getAction();
        final Bundle extras = intent.getExtras();
        if (extras == null) {
            return;
        }
        
        // 检查是否需要打开设置页面
        if (extras.getBoolean("open_settings", false)) {
            if (extras.getBoolean("open_database_update", false)) {
                navigateToDatabaseUpdate();
            } else {
                navigateToSettings();
            }
            return;
        }

        if (MediaSessionCallback.ACTION_PLAY_STATION_BY_UUID.equals(action)) {
            final Context context = getApplicationContext();
            final String stationUUID = extras.getString(EXTRA_STATION_UUID);
            if (TextUtils.isEmpty(stationUUID))
                return;
            intent.removeExtra(EXTRA_STATION_UUID); // mark intent as consumed
            RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
            // 使用本地数据库获取电台信息，不再进行网络查询
            new AsyncTask<Void, Void, DataRadioStation>() {
                @Override
                protected DataRadioStation doInBackground(Void... params) {
                    // 从本地数据库获取电台信息
                    RadioStationRepository repository = RadioStationRepository.getInstance(context);
                    RadioStation station = repository.getStationByUuid(stationUUID);
                    return station != null ? station.toDataRadioStation() : null;
                }

                @Override
                protected void onPostExecute(DataRadioStation station) {
                    if (!isFinishing()) {
                        if (station != null) {
                            Utils.showPlaySelection(radioDroidApp, station, getSupportFragmentManager());

                            Fragment currentFragment = mFragmentManager.getFragments().get(mFragmentManager.getFragments().size() - 1);
                            if (currentFragment instanceof FragmentHistory) {
                                ((FragmentHistory) currentFragment).RefreshListGui();
                            }
                        }
                    }
                }
            }.execute();
        } else {
            final String searchTag = extras.getString(EXTRA_SEARCH_TAG);
            Log.d("MAIN","received search request for tag 1: "+searchTag);
            if (searchTag != null) {
                Log.d("MAIN","received search request for tag 2: "+searchTag);
                search(StationsFilter.SearchStyle.ByTagExact, searchTag);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "RESUMED");
        }

        setupBroadcastReceiver();

        PlayerServiceUtil.startService(getApplicationContext());
        CastHandler castHandler = ((RadioDroidApp) getApplication()).getCastHandler();
        castHandler.onResume();
        castHandler.setActivity(this);

        if (playerBottomSheet.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            appBarLayout.setExpanded(false);
        }

        Intent intent = getIntent();
        if (intent != null) {
            handleIntent(intent);
            setIntent(null);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);

        final Toolbar myToolbar = (Toolbar) findViewById(R.id.my_awesome_toolbar);
        menuItemSleepTimer = menu.findItem(R.id.action_set_sleep_timer);
        menuItemSearch = menu.findItem(R.id.action_search);
        menuItemDelete = menu.findItem(R.id.action_delete);
        menuItemSave = menu.findItem(R.id.action_save);
        menuItemLoad = menu.findItem(R.id.action_load);
        menuItemListView = menu.findItem(R.id.action_list_view);
        menuItemIconsView = menu.findItem(R.id.action_icons_view);
        menuItemAddAlarm = menu.findItem(R.id.action_add_alarm);
        menuItemMpd = menu.findItem(R.id.action_mpd);
        menuItemRandomPlay = menu.findItem(R.id.action_random_play);
        menuItemSort = menu.findItem(R.id.action_sort);
        // 移除SearchView，直接使用onOptionsItemSelected处理点击事件跳转到多条件搜索界面
        MenuItemCompat.setActionView(menuItemSearch, null);

        menuItemSleepTimer.setVisible(false);
        menuItemSearch.setVisible(false);
        menuItemDelete.setVisible(false);
        menuItemSave.setVisible(false);
        menuItemLoad.setVisible(false);
        menuItemListView.setVisible(false);
        menuItemIconsView.setVisible(false);
        menuItemAddAlarm.setVisible(false);
        menuItemRandomPlay.setVisible(false);
        menuItemSort.setVisible(false);

        boolean mpd_is_visible = false;
        RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
        if (radioDroidApp != null) {
            MPDClient mpdClient = radioDroidApp.getMpdClient();
            if (mpdClient != null) {
                MPDServersRepository repository = mpdClient.getMpdServersRepository();
                mpd_is_visible = !repository.isEmpty();
            }
        }
        menuItemMpd.setVisible(mpd_is_visible);

        if (selectedMenuItem == R.id.nav_item_stations) {
            menuItemSleepTimer.setVisible(true);
            menuItemSearch.setVisible(true);
            menuItemRandomPlay.setVisible(true);
            boolean shouldShowSort = true;
            Fragment topFragment = mFragmentManager.findFragmentById(R.id.containerView);
            if (topFragment instanceof FragmentTabs) {
                Fragment currentTab = ((FragmentTabs) topFragment).getCurrentVisibleFragment();
                if (currentTab != null && !(currentTab instanceof FragmentLocalStations)) {
                    shouldShowSort = false;
                }
            }
            menuItemSort.setVisible(shouldShowSort);
            myToolbar.setTitle(R.string.nav_item_stations);
        } else if (selectedMenuItem == R.id.nav_item_starred) {
            menuItemSleepTimer.setVisible(true);
            menuItemSort.setVisible(true);
            //menuItemSearch.setVisible(true);
            menuItemSave.setVisible(true);
            menuItemLoad.setVisible(true);
            menuItemSave.setTitle(R.string.nav_item_save_playlist);

            if (sharedPref.getBoolean("icons_only_favorites_style", false)) {
                menuItemListView.setVisible(true);
            } else if (sharedPref.getBoolean("load_icons", false)) {
                menuItemIconsView.setVisible(true);
            }
            if (radioDroidApp.getFavouriteManager().isEmpty()) {
                menuItemDelete.setVisible(false);
            } else {
                menuItemDelete.setVisible(true).setTitle(R.string.action_delete_favorites);
            }
            myToolbar.setTitle(R.string.nav_item_starred);
        } else if (selectedMenuItem == R.id.nav_item_history) {
            menuItemSleepTimer.setVisible(true);
            menuItemSort.setVisible(false);
            //menuItemSearch.setVisible(true);
            menuItemSave.setVisible(true);
            menuItemSave.setTitle(R.string.nav_item_save_history_playlist);

            if (!radioDroidApp.getHistoryManager().isEmpty()) {
                menuItemDelete.setVisible(true).setTitle(R.string.action_delete_history);
            }
            myToolbar.setTitle(R.string.nav_item_history);
        } else if (selectedMenuItem == R.id.nav_item_alarm) {
            menuItemSort.setVisible(false);
            menuItemAddAlarm.setVisible(true);
            myToolbar.setTitle(R.string.nav_item_alarm);
        }
 /* settings fragment sets the toolbar title depending on the current preference screen
        else if (selectedMenuItem == R.id.nav_item_settings) {
            myToolbar.setTitle(R.string.nav_item_settings);
        }
 */

        ((RadioDroidApp) getApplication()).getCastHandler().getRouteItem(getApplicationContext(), menu);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (menuItemRandomPlay == null) {
            menuItemRandomPlay = menu.findItem(R.id.action_random_play);
        }

        menuItemRandomPlay.setVisible(selectedMenuItem == R.id.nav_item_stations);

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        super.onActivityResult(requestCode, resultCode, resultData);

        if (requestCode == ACTION_SAVE_FILE && resultCode == RESULT_OK) {
            Uri uri = null;
            if (resultData != null) {
                final Uri finalUri = resultData.getData();
                Log.d(TAG, "Choosen save path: " + finalUri);
                
                // 立即返回应用，在后台执行导出操作
                new AsyncTask<Void, Void, Boolean>() {
                    private Exception exception;
                    private String fileName;
                    
                    @Override
                    protected Boolean doInBackground(Void... params) {
                        try {
                            // 在后台线程中获取文件名
                            fileName = getFileNameFromUri(finalUri);
                            
                            OutputStream outputStream = getContentResolver().openOutputStream(finalUri);
                            if (outputStream != null) {
                                RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
                                FavouriteManager favouriteManager = radioDroidApp.getFavouriteManager();
                                HistoryManager historyManager = radioDroidApp.getHistoryManager();
                                
                                boolean success;
                                if (selectedMenuItem == R.id.nav_item_starred) {
                                    // 直接写入到输出流
                                    success = favouriteManager.SaveM3UToStream(outputStream);
                                } else if (selectedMenuItem == R.id.nav_item_history) {
                                    // 直接写入到输出流
                                    success = historyManager.SaveM3UToStream(outputStream);
                                } else {
                                    success = false;
                                }
                                
                                outputStream.close();
                                return success;
                            }
                        } catch (Exception e) {
                            exception = e;
                            Log.e(TAG, "Unable to write to file " + e);
                        }
                        return false;
                    }

                    @Override
                    protected void onPostExecute(Boolean result) {
                        if (exception != null) {
                            Toast.makeText(ActivityMain.this, getResources().getString(R.string.error_save_file_failed, exception.getMessage()), Toast.LENGTH_LONG).show();
                        } else if (result.booleanValue()) {
                            Toast.makeText(ActivityMain.this, getResources().getString(R.string.notify_save_playlist_ok, getString(R.string.export_type_file), fileName), Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(ActivityMain.this, getResources().getString(R.string.notify_save_playlist_nok, getString(R.string.export_type_file), fileName), Toast.LENGTH_LONG).show();
                        }
                    }
                }.execute();
            }
        }
        if (requestCode == ACTION_LOAD_FILE && resultCode == RESULT_OK) {
            Uri uri = null;
            if (resultData != null) {
                final Uri finalUri = resultData.getData();
                Log.d(TAG, "Choosen load path: " + finalUri);
                
                // 立即返回应用，在后台执行导入操作
                new AsyncTask<Void, Void, Void>() {
                    private String fileName;
                    private Exception exception;
                    private int importedCount = 0;
                    private List<DataRadioStation> importedStations;
                    
                    @Override
                    protected Void doInBackground(Void... params) {
                        try {
                            // 在后台线程中获取文件名
                            fileName = getFileNameFromUri(finalUri);
                            if (fileName == null || fileName.isEmpty()) {
                                fileName = "playlist.m3u";
                            }
                            
                            // 检查文件是否为M3U格式
                            if (!fileName.toLowerCase().endsWith(".m3u")) {
                                exception = new Exception(getResources().getString(R.string.error_invalid_file_format));
                                return null;
                            }
                            
                            RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
                            FavouriteManager favouriteManager = radioDroidApp.getFavouriteManager();
                            
                            InputStream is = getContentResolver().openInputStream(finalUri);
                            InputStreamReader reader = new InputStreamReader(is);
                            
                            // 直接调用LoadM3UReader获取结果
                            importedStations = favouriteManager.LoadM3UReader(reader);
                            if (importedStations != null) {
                                importedCount = importedStations.size();
                                // 不在这里调用addMultiple，只保存数据
                            } else {
                                exception = new Exception(getResources().getString(R.string.error_import_failed_parse));
                            }
                            
                            reader.close();
                            is.close();
                        } catch (Exception e) {
                            exception = e;
                            Log.e(TAG, "Unable to load file " + e);
                        }
                        return null;
                    }

                    @Override
                    protected void onPostExecute(Void result) {
                        if (exception != null) {
                            if (exception.getMessage() != null && exception.getMessage().contains("M3U")) {
                                Toast.makeText(ActivityMain.this, exception.getMessage(), Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(ActivityMain.this, getResources().getString(R.string.error_import_failed, exception.getMessage()), Toast.LENGTH_SHORT).show();
                            }
                        } else if (importedStations != null) {
                            // 在主线程中添加电台到收藏列表
                            RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
                            FavouriteManager favouriteManager = radioDroidApp.getFavouriteManager();
                            favouriteManager.addMultiple(importedStations);
                            Toast.makeText(ActivityMain.this, getResources().getString(R.string.success_imported_stations, importedCount, fileName), Toast.LENGTH_LONG).show();
                        }
                    }
                }.execute();
            }
        }
    }

    @Override
    public void onFileSelected(FileDialog dialog, File file) {
        try {
            Log.i("MAIN", "save to " + file.getParent() + "/" + file.getName());
            RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
            FavouriteManager favouriteManager = radioDroidApp.getFavouriteManager();
            HistoryManager historyManager = radioDroidApp.getHistoryManager();

            if (dialog instanceof SaveFileDialog) {
                if (selectedMenuItem == R.id.nav_item_starred) {
                    favouriteManager.SaveM3U(file.getParent(), file.getName());
                }else if (selectedMenuItem == R.id.nav_item_history) {
                    historyManager.SaveM3U(file.getParent(), file.getName());
                }
            } else if (dialog instanceof OpenFileDialog) {
                favouriteManager.LoadM3U(file.getParent(), file.getName());
            }
        } catch (Exception e) {
            Log.e("MAIN", e.toString());
        }
    }

    void SaveFavourites() {
        // 创建导出文件名：导出时间和电台数量
        FavouriteManager favouriteManager = new FavouriteManager(this);
        int favouriteCount = favouriteManager.getList().size();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String defaultFileName = "RadioDroid_Favorites_" + timestamp + "_" + favouriteCount + "stations.m3u";
        
        // 使用系统文件选择器
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/x-mpegurl");
        intent.putExtra(Intent.EXTRA_TITLE, defaultFileName);
        startActivityForResult(intent, ACTION_SAVE_FILE);
    }

    void LoadFavourites() {
        // 使用系统文件选择器
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/x-mpegurl");
        intent.putExtra(Intent.EXTRA_TITLE, "playlist.m3u");
        startActivityForResult(intent, ACTION_LOAD_FILE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId == android.R.id.home) {
            mDrawerLayout.openDrawer(GravityCompat.START);  // OPEN DRAWER
            return true;
        } else if (itemId == R.id.action_search) {
                // 点击搜索按钮，导航到电台界面的搜索标签页
                selectedMenuItem = R.id.nav_item_stations;
                onNavigationItemSelected(null);
                return true;
        } else if (itemId == R.id.action_save) {
            try {
                SaveFavourites();
            } catch (Exception e) {
                Log.e("MAIN", e.toString());
            }

            return true;
        } else if (itemId == R.id.action_load) {
                try {
                    LoadFavourites();
                } catch (Exception e) {
                    Log.e("MAIN", e.toString());
                }
                return true;
        } else if (itemId == R.id.action_set_sleep_timer) {
            changeTimer();
            return true;
        } else if (itemId == R.id.action_random_play) {
            playRandomStation();
            return true;
        } else if (itemId == R.id.action_mpd) {
            selectMPDServer();
            return true;
        } else if (itemId == R.id.action_delete) {
                if (selectedMenuItem == R.id.nav_item_history) {
                    new AlertDialog.Builder(this, Utils.getAlertDialogThemeResId(this))
                            .setMessage(this.getString(R.string.alert_delete_history))
                            .setCancelable(true)
                            .setPositiveButton(this.getString(R.string.yes), new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int id) {
                                    RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
                                    HistoryManager historyManager = radioDroidApp.getHistoryManager();

                                    historyManager.clear();

                                    Toast toast = Toast.makeText(getApplicationContext(), getString(R.string.notify_deleted_history), Toast.LENGTH_SHORT);
                                    toast.show();
                                    recreate();
                                }
                            })
                            .setNegativeButton(this.getString(R.string.no), null)
                            .show();
                } else if (selectedMenuItem == R.id.nav_item_starred) {
                    new AlertDialog.Builder(this, Utils.getAlertDialogThemeResId(this))
                            .setMessage(this.getString(R.string.alert_delete_favorites))
                            .setCancelable(true)
                            .setPositiveButton(this.getString(R.string.yes), new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int id) {
                                    RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
                                    FavouriteManager favouriteManager = radioDroidApp.getFavouriteManager();

                                    favouriteManager.clear();

                                    Toast toast = Toast.makeText(getApplicationContext(), getString(R.string.notify_deleted_favorites), Toast.LENGTH_SHORT);
                                    toast.show();
                                    recreate();
                                }
                            })
                            .setNegativeButton(this.getString(R.string.no), null)
                            .show();
                }
                return true;
        } else if (itemId == R.id.action_list_view) {
            sharedPref.edit().putBoolean("icons_only_favorites_style", false).apply();
            recreate();
            return true;
        } else if (itemId == R.id.action_icons_view) {
            sharedPref.edit().putBoolean("icons_only_favorites_style", true).apply();
            recreate();
            return true;
        } else if (itemId == R.id.action_sort) {
            showSortDialog();
            return true;
        } else if (itemId == R.id.action_add_alarm) {
            TimePickerFragment newFragment = new TimePickerFragment();
            newFragment.setCallback(this);
            newFragment.show(getSupportFragmentManager(), "timePicker");
            return true;
        } else {
            return super.onOptionsItemSelected(menuItem);
        }
    }

    public void toggleBottomSheetState() {
        if (playerBottomSheet.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            playerBottomSheet.setState(BottomSheetBehavior.STATE_COLLAPSED);
        } else {
            playerBottomSheet.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    private void showSortDialog() {
        Fragment topFragment = mFragmentManager.findFragmentById(R.id.containerView);

        Fragment currentFragment = null;

        if (topFragment instanceof FragmentStarred) {
            currentFragment = topFragment;
        } else if (topFragment instanceof FragmentTabs) {
            currentFragment = ((FragmentTabs) topFragment).getCurrentVisibleFragment();
        }

        if (currentFragment == null) {
            return;
        }

        String[] sortOptions = {
                getString(R.string.sort_by_name),
                getString(R.string.sort_by_click_count),
                getString(R.string.sort_by_votes),
                getString(R.string.sort_by_recent)
        };

        int currentMode;
        boolean isAscending;
        final int[] sortModes;

        if (currentFragment instanceof FragmentStarred) {
            final FragmentStarred starredFragment = (FragmentStarred) currentFragment;
            sortModes = new int[]{
                    FragmentStarred.SORT_NAME,
                    FragmentStarred.SORT_CLICK_COUNT,
                    FragmentStarred.SORT_VOTES,
                    FragmentStarred.SORT_RECENT
            };
            currentMode = starredFragment.getCurrentSortMode();
            isAscending = starredFragment.isSortAscending();

            String[] displayOptions = new String[sortOptions.length];
            for (int i = 0; i < sortOptions.length; i++) {
                String indicator = "";
                if (sortModes[i] == currentMode) {
                    indicator = isAscending ? " ↑" : " ↓";
                }
                displayOptions[i] = sortOptions[i] + indicator;
            }

            int checkedItem = -1;
            for (int i = 0; i < sortModes.length; i++) {
                if (sortModes[i] == currentMode) {
                    checkedItem = i;
                    break;
                }
            }

            new AlertDialog.Builder(this, Utils.getAlertDialogThemeResId(this))
                    .setTitle(R.string.action_sort)
                    .setSingleChoiceItems(displayOptions, checkedItem, (dialog, which) -> {
                        starredFragment.setSortMode(sortModes[which]);
                        dialog.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else if (currentFragment instanceof FragmentLocalStations) {
            final FragmentLocalStations localFragment = (FragmentLocalStations) currentFragment;
            sortModes = new int[]{
                    FragmentLocalStations.SORT_NAME,
                    FragmentLocalStations.SORT_CLICK_COUNT,
                    FragmentLocalStations.SORT_VOTES,
                    FragmentLocalStations.SORT_RECENT
            };
            currentMode = localFragment.getCurrentSortMode();
            isAscending = localFragment.isSortAscending();

            String[] displayOptions = new String[sortOptions.length];
            for (int i = 0; i < sortOptions.length; i++) {
                String indicator = "";
                if (sortModes[i] == currentMode) {
                    indicator = isAscending ? " ↑" : " ↓";
                }
                displayOptions[i] = sortOptions[i] + indicator;
            }

            int checkedItem = -1;
            for (int i = 0; i < sortModes.length; i++) {
                if (sortModes[i] == currentMode) {
                    checkedItem = i;
                    break;
                }
            }

            new AlertDialog.Builder(this, Utils.getAlertDialogThemeResId(this))
                    .setTitle(R.string.action_sort)
                    .setSingleChoiceItems(displayOptions, checkedItem, (dialog, which) -> {
                        localFragment.setSortMode(sortModes[which]);
                        dialog.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    @Override
    public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
        RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
        HistoryManager historyManager = radioDroidApp.getHistoryManager();
        Fragment currentFragment = mFragmentManager.getFragments().get(mFragmentManager.getFragments().size() - 2);
        if (historyManager.size() > 0 && currentFragment instanceof FragmentAlarm) {
            DataRadioStation station = historyManager.getList().get(0);
            ((FragmentAlarm) currentFragment).getRam().add(station, hourOfDay, minute);
        }
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        return true;
    }

    @Override
    public void onSearchResultClicked(SearchPreferenceResult result) {
        result.closeSearchPage(this);
        getSupportFragmentManager().popBackStack();
        FragmentSettings f = FragmentSettings.openNewSettingsSubFragment(this, result.getScreen());
        result.highlight(f);
    }

    private void setupStartUpFragment() {
        // This will restore fragment that was shown before activity was recreated
        if (instanceStateWasSaved) {
            invalidateOptionsMenu();
            checkMenuItems();
            return;
        }

        RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
        HistoryManager hm = radioDroidApp.getHistoryManager();
        FavouriteManager fm = radioDroidApp.getFavouriteManager();

        final String startupAction = sharedPref.getString("startup_action", getResources().getString(R.string.startup_show_history));

        if (startupAction.equals(getResources().getString(R.string.startup_show_player))) {
            selectMenuItem(R.id.nav_item_stations);
            new android.os.Handler().postDelayed(() -> playerBottomSheet.setState(BottomSheetBehavior.STATE_EXPANDED), 300);
            return;
        }

        if (startupAction.equals(getResources().getString(R.string.startup_show_history)) && hm.isEmpty()) {
            selectMenuItem(R.id.nav_item_stations);
            return;
        }

        if (startupAction.equals(getResources().getString(R.string.startup_show_favorites)) && fm.isEmpty()) {
            selectMenuItem(R.id.nav_item_stations);
            return;
        }

        if (startupAction.equals(getResources().getString(R.string.startup_show_history))) {
            selectMenuItem(R.id.nav_item_history);
        } else if (startupAction.equals(getResources().getString(R.string.startup_show_favorites))) {
            selectMenuItem(R.id.nav_item_starred);
        } else if (startupAction.equals(getResources().getString(R.string.startup_show_all_stations)) || selectedMenuItem < 0) {
            selectMenuItem(R.id.nav_item_stations);
        } else {
            selectMenuItem(selectedMenuItem);
        }
    }

    private void selectMenuItem(int itemId) {
        MenuItem item;
        if (Utils.bottomNavigationEnabled(this))
            item = mBottomNavigationView.getMenu().findItem(itemId);
        else
            item = mNavigationView.getMenu().findItem(itemId);

        if (item != null) {
            onNavigationItemSelected(item);
        } else {
            selectedMenuItem = R.id.nav_item_stations;
            onNavigationItemSelected(null);
        }
    }

    private void checkMenuItems() {
        if (mBottomNavigationView.getMenu().findItem(selectedMenuItem) != null)
            mBottomNavigationView.getMenu().findItem(selectedMenuItem).setChecked(true);

        if (mNavigationView.getMenu().findItem(selectedMenuItem) != null)
            mNavigationView.getMenu().findItem(selectedMenuItem).setChecked(true);
    }

    public void search(StationsFilter.SearchStyle searchStyle, String query) {
        Log.d("MAIN", "Search() searchstyle=" + searchStyle + " query=" + query);
        Fragment currentFragment = mFragmentManager.getFragments().get(mFragmentManager.getFragments().size() - 1);
        if (currentFragment instanceof FragmentTabs) {
            ((FragmentTabs) currentFragment).search(searchStyle, query);
        } else {
            String backStackTag = String.valueOf(R.id.nav_item_stations);
            FragmentTabs f = new FragmentTabs();
            FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
            if (Utils.bottomNavigationEnabled(this)) {
                fragmentTransaction.replace(R.id.containerView, f).commit();
                mBottomNavigationView.getMenu().findItem(R.id.nav_item_stations).setChecked(true);
            } else {
                fragmentTransaction.replace(R.id.containerView, f).addToBackStack(backStackTag).commit();
                mNavigationView.getMenu().findItem(R.id.nav_item_stations).setChecked(true);
            }

            f.search(searchStyle, query);
            selectedMenuItem = R.id.nav_item_stations;
            invalidateOptionsMenu();
        }

    }

    public void SearchStations(@NonNull String query) {
        Log.d("MAIN", "SearchStations() " + query);
        
        // 检查FragmentManager是否为空
        if (mFragmentManager == null) {
            Log.e("MAIN", "FragmentManager is null, cannot search");
            return;
        }
        
        // 检查Fragment列表是否为空
        if (mFragmentManager.getFragments() == null || mFragmentManager.getFragments().isEmpty()) {
            Log.e("MAIN", "No fragments available, cannot search");
            return;
        }
        
        try {
            Fragment currentFragment = mFragmentManager.getFragments().get(mFragmentManager.getFragments().size() - 1);
            if (currentFragment instanceof IFragmentSearchable) {
                ((IFragmentSearchable) currentFragment).search(StationsFilter.SearchStyle.ByName, query);
            } else {
                Log.w("MAIN", "Current fragment does not implement IFragmentSearchable: " + 
                     (currentFragment != null ? currentFragment.getClass().getSimpleName() : "null"));
            }
        } catch (Exception e) {
            Log.e("MAIN", "Error during search", e);
        }
    }



    private void showMeteredConnectionDialog(@NonNull Runnable playFunc) {
        Resources res = this.getResources();
        String title = res.getString(R.string.alert_metered_connection_title);
        String text = res.getString(R.string.alert_metered_connection_message);
        meteredConnectionAlertDialog = new AlertDialog.Builder(this, Utils.getAlertDialogThemeResId(this))
                .setTitle(title)
                .setMessage(text)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> playFunc.run())
                .setOnDismissListener(dialog -> meteredConnectionAlertDialog = null)
                .create();

        meteredConnectionAlertDialog.show();
    }

    private void setupBroadcastReceiver() {
        if (broadcastReceiver != null) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Receiver was not registered: " + e.getMessage());
            }
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_HIDE_LOADING);
        filter.addAction(ACTION_SHOW_LOADING);
        filter.addAction(PlayerService.PLAYER_SERVICE_STATE_CHANGE);
        filter.addAction(PlayerService.PLAYER_SERVICE_METERED_CONNECTION);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(ACTION_HIDE_LOADING)) {
                    hideLoadingIcon();
                } else if (intent.getAction().equals(ACTION_SHOW_LOADING)) {
                    showLoadingIcon();
                } else if (intent.getAction().equals(PlayerService.PLAYER_SERVICE_METERED_CONNECTION)) {
                    if (meteredConnectionAlertDialog != null) {
                        meteredConnectionAlertDialog.cancel();
                        meteredConnectionAlertDialog = null;
                    }

                    PlayerType playerType = intent.getParcelableExtra(PlayerService.PLAYER_SERVICE_METERED_CONNECTION_PLAYER_TYPE);

                    switch (playerType) {
                        case RADIODROID:
                            showMeteredConnectionDialog(() -> Utils.play((RadioDroidApp) getApplication(), PlayerServiceUtil.getCurrentStation()));
                            break;
                        case EXTERNAL:
                            DataRadioStation currentStation = PlayerServiceUtil.getCurrentStation();
                            if (currentStation != null) {
                                showMeteredConnectionDialog(() -> PlayStationTask.playExternal(currentStation, ActivityMain.this).execute());
                            }
                            break;
                        default:
                            Log.e(TAG, String.format("broadcastReceiver unexpected PlayerType '%s'", playerType.toString()));
                    }
                } else if (intent.getAction().equals(PlayerService.PLAYER_SERVICE_STATE_CHANGE)) {
                    if (PlayerServiceUtil.isPlaying()) {
                        if (meteredConnectionAlertDialog != null) {
                            meteredConnectionAlertDialog.cancel();
                            meteredConnectionAlertDialog = null;
                        }
                    }
                }
            }
        };

        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, filter);
    }

    // Loading listener
    private void showLoadingIcon() {
        findViewById(R.id.progressBarLoading).setVisibility(View.VISIBLE);
    }

    private void hideLoadingIcon() {
        findViewById(R.id.progressBarLoading).setVisibility(View.GONE);
    }
    
    // 从URI获取文件路径
    private String getPathFromUri(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            if (docId.startsWith(":")) {
                // 如果是相对路径，返回空字符串，让SaveM3UInternal方法处理
                return "";
            }
        }
        
        // 尝试从URI中提取路径
        String path = uri.getPath();
        if (path != null) {
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash > 0) {
                return path.substring(0, lastSlash);
            }
        }
        
        return "";
    }
    
    // 从URI获取文件名
    private String getFileNameFromUri(Uri uri) {
        String displayName = null;
        
        // 尝试从URI中获取显示名称
        try {
            String[] projection = {android.provider.OpenableColumns.DISPLAY_NAME};
            try (android.database.Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int columnIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (columnIndex >= 0) {
                        displayName = cursor.getString(columnIndex);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting file name from URI: " + e.toString());
        }
        
        // 如果无法获取显示名称，返回空字符串，让SaveM3UInternal方法使用默认文件名
        if (displayName == null || displayName.isEmpty()) {
            return "";
        }
        
        return displayName;
    }

    private void changeTimer() {
        final AlertDialog.Builder seekDialog = new AlertDialog.Builder(this, Utils.getAlertDialogThemeResId(this));
        View seekView = View.inflate(this, R.layout.layout_timer_chooser, null);

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
        seekDialog.setPositiveButton(R.string.sleep_timer_apply, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                PlayerServiceUtil.clearTimer();
                PlayerServiceUtil.addTimer(seekBar.getProgress() * 60);
                sharedPref.edit().putInt("sleep_timer_default_minutes", seekBar.getProgress()).apply();
            }
        });

        seekDialog.setNegativeButton(R.string.sleep_timer_clear, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                PlayerServiceUtil.clearTimer();
            }
        });

        seekDialog.create();
        seekDialog.show();
    }

    private void playRandomStation() {
        Toast.makeText(this, R.string.action_random_play, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            RadioStationRepository repository;
            try {
                repository = RadioStationRepository.getInstance(ActivityMain.this);
            } catch (Exception e) {
                Log.e(TAG, "Failed to get repository", e);
                showToastOnUiThread(R.string.error_station_load);
                return;
            }

            if (repository == null) {
                showToastOnUiThread(R.string.error_station_load);
                return;
            }

            int stationCount;
            try {
                stationCount = repository.getStationCountSync();
            } catch (Exception e) {
                Log.e(TAG, "Failed to get station count", e);
                showToastOnUiThread(R.string.error_station_load);
                return;
            }

            if (stationCount == 0) {
                showToastOnUiThread(R.string.error_station_load);
                return;
            }

            final int MAX_ATTEMPTS = 10;
            int attempts = 0;
            final boolean[] foundWorkingStation = {false};

            while (attempts < MAX_ATTEMPTS && !foundWorkingStation[0]) {
                attempts++;
                Log.d(TAG, "Attempt " + attempts + " to find working station");

                RadioStation randomStation;
                try {
                    randomStation = repository.getRandomStationSync();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to get random station", e);
                    continue;
                }

                if (randomStation == null) {
                    continue;
                }

                DataRadioStation dataStation;
                try {
                    dataStation = randomStation.toDataRadioStation();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to convert station", e);
                    continue;
                }

                if (dataStation == null) {
                    continue;
                }

                final DataRadioStation finalStation = dataStation;
                final boolean[] stationPlayed = {false};
                final Object lock = new Object();

                runOnUiThread(() -> {
                    new PlayStationTask(finalStation, ActivityMain.this,
                            url -> {
                                finalStation.playableUrl = url;
                                PlayerServiceUtil.play(finalStation);
                                synchronized (lock) {
                                    stationPlayed[0] = true;
                                    foundWorkingStation[0] = true;
                                    lock.notify();
                                }
                            },
                            result -> {
                                synchronized (lock) {
                                    if (result == PlayStationTask.ExecutionResult.FAILURE) {
                                        Log.d(TAG, "Station failed to play, trying next");
                                    } else {
                                        stationPlayed[0] = true;
                                        foundWorkingStation[0] = true;
                                    }
                                    lock.notify();
                                }
                            }).execute();
                });

                synchronized (lock) {
                    try {
                        lock.wait(10000); // Wait up to 10 seconds for result
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                if (foundWorkingStation[0]) {
                    break;
                }
            }

            if (!foundWorkingStation[0]) {
                showToastOnUiThread(R.string.error_station_load);
                Log.e(TAG, "Failed to find working station after " + MAX_ATTEMPTS + " attempts");
            }
        }).start();
    }

    private void showToastOnUiThread(int resId) {
        runOnUiThread(() -> Toast.makeText(ActivityMain.this, resId, Toast.LENGTH_SHORT).show());
    }

    private void selectMPDServer() {
        RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
        Utils.showMpdServersDialog(radioDroidApp, getSupportFragmentManager(), null);
    }

    public final Toolbar getToolbar() {
        return (Toolbar) findViewById(R.id.my_awesome_toolbar);
    }

    @Override
    public void invalidateOptionsMenuForCast() {
        invalidateOptionsMenu();
    }
    
    private void initAppLanguage() {
        if (sharedPref == null) {
            sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        }
        String language = sharedPref.getString("app_language", "system");
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale);
        } else {
            config.locale = locale;
        }
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());
    }
}
