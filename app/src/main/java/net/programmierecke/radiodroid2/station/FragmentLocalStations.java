package net.programmierecke.radiodroid2.station;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import net.programmierecke.radiodroid2.ActivityMain;
import net.programmierecke.radiodroid2.FragmentBase;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.database.RadioStation;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.interfaces.IFragmentSearchable;
import net.programmierecke.radiodroid2.station.DataRadioStation;
import net.programmierecke.radiodroid2.station.ItemAdapterStation;
import net.programmierecke.radiodroid2.utils.DatabaseEmptyHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class FragmentLocalStations extends FragmentBase implements IFragmentSearchable {
    private static final String TAG = "FragmentLocalStations";

    public static final int SORT_NONE = 0;
    public static final int SORT_NAME = 1;
    public static final int SORT_CLICK_COUNT = 2;
    public static final int SORT_VOTES = 3;
    public static final int SORT_RECENT = 4;
    private static final String PREF_SORT_MODE = "local_stations_sort_mode";
    private static final String PREF_SORT_ASCENDING = "local_stations_sort_ascending";

    private RecyclerView rvStations;
    private SwipeRefreshLayout swipeRefreshLayout;
    private LinearLayout layoutError;
    private TextView textErrorMessage;
    private MaterialButton btnRetry;
    private FloatingActionButton fabScrollToTop;

    private ItemAdapterStation stationListAdapter;
    private RadioStationRepository repository;
    private StationsFilter.SearchStyle lastSearchStyle = StationsFilter.SearchStyle.ByName;
    private String lastSearchQuery = "";
    private int currentSortMode = SORT_CLICK_COUNT;
    private boolean sortAscending = false;
    private static final int MAX_DISPLAY_STATIONS = 1000;
    private List<DataRadioStation> allStations = new ArrayList<>();

    public int getCurrentSortMode() {
        return currentSortMode;
    }

    public boolean isSortAscending() {
        return sortAscending;
    }

    public void setSortMode(int mode) {
        if (currentSortMode == mode) {
            sortAscending = !sortAscending;
        } else {
            currentSortMode = mode;
            sortAscending = true;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        prefs.edit()
                .putInt(PREF_SORT_MODE, currentSortMode)
                .putBoolean(PREF_SORT_ASCENDING, sortAscending)
                .apply();
        applySortAndRefresh();
    }

    private void applySortAndRefresh() {
        if (allStations.isEmpty()) return;
        List<DataRadioStation> sorted = getSortedStations();
        stationListAdapter.updateList(null, sorted);
    }

    private List<DataRadioStation> getSortedStations() {
        List<DataRadioStation> sorted = new ArrayList<>(allStations);
        Comparator<DataRadioStation> comparator = null;
        switch (currentSortMode) {
            case SORT_NAME:
                comparator = Comparator.comparing(s -> s.Name.toLowerCase());
                break;
            case SORT_CLICK_COUNT:
                comparator = Comparator.comparingInt(s -> s.ClickCount);
                break;
            case SORT_VOTES:
                comparator = Comparator.comparingInt(s -> s.Votes);
                break;
            case SORT_RECENT:
                comparator = Comparator.comparing(s -> s.LastChangeTime, Comparator.nullsLast(Comparator.reverseOrder()));
                break;
        }
        if (comparator != null) {
            if (!sortAscending) {
                comparator = comparator.reversed();
            }
            Collections.sort(sorted, comparator);
        }
        if (sorted.size() > MAX_DISPLAY_STATIONS) {
            sorted = new ArrayList<>(sorted.subList(0, MAX_DISPLAY_STATIONS));
        }
        return sorted;
    }

    private void updateStationsList(List<DataRadioStation> stations) {
        allStations = new ArrayList<>(stations);
        List<DataRadioStation> displayList = getSortedStations();
        stationListAdapter.updateList(null, displayList);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_stations, container, false);

        rvStations = view.findViewById(R.id.recyclerViewStations);
        swipeRefreshLayout = view.findViewById(R.id.swiperefresh);
        layoutError = view.findViewById(R.id.layoutError);
        textErrorMessage = view.findViewById(R.id.textErrorMessage);
        btnRetry = view.findViewById(R.id.btnRetry);
        fabScrollToTop = view.findViewById(R.id.fabScrollToTop);

        // 初始化RecyclerView
        if (getContext() != null) {
            rvStations.setLayoutManager(new LinearLayoutManager(getContext()));
            rvStations.addItemDecoration(new DividerItemDecoration(getContext(), DividerItemDecoration.VERTICAL));
        }

        rvStations.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (fabScrollToTop != null) {
                    boolean canScrollUp = recyclerView.canScrollVertically(-1);
                    fabScrollToTop.setVisibility(canScrollUp ? View.VISIBLE : View.GONE);
                }
            }
        });

        if (fabScrollToTop != null) {
            fabScrollToTop.setOnClickListener(v -> {
                if (rvStations != null) {
                    rvStations.smoothScrollToPosition(0);
                }
            });
        }
        
        // Adapter将在onActivityCreated中初始化，确保Activity可用
        rvStations.setAdapter(null);

        // 初始化Repository - 延迟到onActivityCreated以确保Context可用
        // repository = RadioStationRepository.getInstance(getContext()); // 移到onActivityCreated中初始化

        // 设置下拉刷新
        swipeRefreshLayout.setEnabled(true);
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                // 下拉刷新时重新加载数据，始终按系统国家优先的逻辑
                loadDataWithSystemCountryPriority();
                // 刷新完成后停止刷新动画
                swipeRefreshLayout.setRefreshing(false);
            }
        });
        
        // 设置重试按钮
        btnRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadData();
            }
        });

        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        
        // 在这里初始化Repository和Adapter，确保Context可用
        if (getContext() != null) {
            repository = RadioStationRepository.getInstance(getContext());
        } else {
            Log.e(TAG, "Context is null in onActivityCreated");
            showError("应用上下文不可用，请重启应用");
            return;
        }
        
        // 初始化Adapter
        if (getActivity() != null) {
            stationListAdapter = new ItemAdapterStation(getActivity(), R.layout.list_item_station);
            stationListAdapter.setStationActionsListener(new ItemAdapterStation.StationActionsListener() {
                @Override
                public void onStationClick(DataRadioStation station, int pos) {
                    // 处理电台点击事件
                    FragmentLocalStations.this.onStationClick(station, pos);
                }

                @Override
                public void onStationSwiped(DataRadioStation station) {
                    // 处理电台滑动事件
                }

                @Override
                public void onStationMoved(int from, int to) {
                    // 处理电台移动事件
                }

                @Override
                public void onStationMoveFinished() {
                    // 处理电台移动完成事件
                }
            });
            rvStations.setAdapter(stationListAdapter);
        } else {
            Log.e(TAG, "Activity is null in onActivityCreated, cannot initialize adapter");
            showError("Activity不可用，请重启应用");
            return;
        }
        
        // 获取传递的参数，但不使用URL，因为这是本地数据库查询
        Bundle args = getArguments();
        if (args != null) {
            String url = args.getString("url", "");
            Log.d(TAG, "Received URL argument (ignored for local database): " + url);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        currentSortMode = prefs.getInt(PREF_SORT_MODE, SORT_CLICK_COUNT);
        sortAscending = prefs.getBoolean(PREF_SORT_ASCENDING, false);

        loadData();
    }

    private void loadData() {
        // 检查repository是否已初始化
        if (repository == null) {
            if (getContext() != null) {
                repository = RadioStationRepository.getInstance(getContext());
            } else {
                showError("数据仓库未初始化，请重启应用");
                return;
            }
        }
        
        // 使用统一的空数据库检查
        DatabaseEmptyHelper.checkAndShowEmptyDatabaseError(this, layoutError, rvStations, 
            new DatabaseEmptyHelper.DatabaseCheckCallback() {
                @Override
                public void onCheckCompleted(boolean isEmpty, int count) {
                    if (!isEmpty) {
                        // 数据库有数据，继续加载
                        if (getActivity() != null) {
                            // 获取系统国家和语言
                            java.util.Locale locale = java.util.Locale.getDefault();
                            String systemCountry = locale.getCountry();
                            String systemLanguage = locale.getLanguage();
                            
                            // 确保在主线程上加载数据
                            getActivity().runOnUiThread(() -> {
                                // 根据当前搜索模式加载数据
                                switch (lastSearchStyle) {
                                    case ByLanguageExact:
                                        // 如果已经是按语言搜索，直接加载
                                        loadAllStations();
                                        break;
                                    default:
                                        // 默认情况下，先尝试加载系统国家的电台
                                        loadStationsBySystemCountry(systemCountry, systemLanguage);
                                        break;
                                }
                            });
                        } else {
                            Log.e(TAG, "Activity is null, cannot load stations");
                        }
                    }
                }

                @Override
                public void onCheckError(String error) {
                    if (getActivity() != null) {
                        Log.e(TAG, "Error checking station count", null);
                        showError("检查本地数据库时出错：" + error);
                    } else {
                        Log.e(TAG, "Activity is null, cannot show error message: " + error);
                    }
                }
            });
    }

    private void loadStationsBySystemCountry(String systemCountry, String systemLanguage) {
        Log.d(TAG, "Loading stations for system country(" + systemCountry + ")");
        
        // 先尝试加载系统国家的电台
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                // 首先尝试加载系统国家的电台
                repository.getStationsByCountryCodeAll(systemCountry).observe(getViewLifecycleOwner(), new Observer<List<RadioStation>>() {
                    @Override
                    public void onChanged(List<RadioStation> radioStations) {
                        if (radioStations != null && !radioStations.isEmpty()) {
                            hideError();
                            // 转换为DataRadioStation，预分配容量以提高性能
                            List<DataRadioStation> dataStations = new ArrayList<>(radioStations.size());
                            for (RadioStation radioStation : radioStations) {
                                if (radioStation != null) {
                                    DataRadioStation dataStation = radioStation.toDataRadioStation();
                                    if (dataStation != null) {
                                        dataStations.add(dataStation);
                                    }
                                }
                            }
                            
                            if (!dataStations.isEmpty()) {
                                updateStationsList(dataStations);
                                Log.d(TAG, "Loaded " + dataStations.size() + " stations for country(" + systemCountry + ")");
                            } else {
                                // 如果系统国家没有电台，尝试加载系统语言的电台
                                loadStationsBySystemLanguage(systemLanguage);
                            }
                        } else {
                            // 如果系统国家没有电台，尝试加载系统语言的电台
                            loadStationsBySystemLanguage(systemLanguage);
                        }
                    }
                });
            });
        } else {
            Log.e(TAG, "Activity is null, cannot load stations by system country");
        }
    }
    
    private void loadStationsBySystemLanguage(String systemLanguage) {
        // 处理中文语言代码的特殊情况
        final String languageCode = "zh".equals(systemLanguage) ? "chinese" : systemLanguage;
        
        Log.d(TAG, "Loading stations for system language(" + languageCode + ")");
        
        // 获取系统国家代码
        final String systemCountry = java.util.Locale.getDefault().getCountry();
        Log.d(TAG, "System country code: " + systemCountry);
        
        // 先尝试加载系统语言和国家的电台
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                // 首先尝试加载系统语言和国家的电台
                repository.getStationsByLanguageAndCountry(languageCode, systemCountry).observe(getViewLifecycleOwner(), new Observer<List<RadioStation>>() {
                    @Override
                    public void onChanged(List<RadioStation> radioStations) {
                        if (radioStations != null && !radioStations.isEmpty()) {
                            hideError();
                            // 转换为DataRadioStation，预分配容量以提高性能
                            List<DataRadioStation> dataStations = new ArrayList<>(radioStations.size());
                            for (RadioStation radioStation : radioStations) {
                                if (radioStation != null) {
                                    DataRadioStation dataStation = radioStation.toDataRadioStation();
                                    if (dataStation != null) {
                                        dataStations.add(dataStation);
                                    }
                                }
                            }
                            
                            if (!dataStations.isEmpty()) {
                                updateStationsList(dataStations);
                                Log.d(TAG, "加载了 " + dataStations.size() + " 个系统语言(" + languageCode + ")和国家(" + systemCountry + ")的电台");
                            } else {
                                // 如果系统语言和国家没有电台，尝试只按语言加载
                                loadStationsByLanguageOnly(languageCode);
                            }
                        } else {
                            // 如果系统语言和国家没有电台，尝试只按语言加载
                            loadStationsByLanguageOnly(languageCode);
                        }
                    }
                });
            });
        } else {
            Log.e(TAG, "Activity is null, cannot load stations by system language and country");
        }
    }
    
    private void loadStationsByLanguageOnly(String languageCode) {
        Log.d(TAG, "Loading stations for language(" + languageCode + ") without country restriction");
        
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                // 尝试加载系统语言的电台（不限制国家）
                repository.getStationsByLanguageAll(languageCode).observe(getViewLifecycleOwner(), new Observer<List<RadioStation>>() {
                    @Override
                    public void onChanged(List<RadioStation> radioStations) {
                        if (radioStations != null && !radioStations.isEmpty()) {
                            hideError();
                            // 转换为DataRadioStation，预分配容量以提高性能
                            List<DataRadioStation> dataStations = new ArrayList<>(radioStations.size());
                            for (RadioStation radioStation : radioStations) {
                                if (radioStation != null) {
                                    DataRadioStation dataStation = radioStation.toDataRadioStation();
                                    if (dataStation != null) {
                                        dataStations.add(dataStation);
                                    }
                                }
                            }
                            
                            if (!dataStations.isEmpty()) {
                                updateStationsList(dataStations);
                                Log.d(TAG, "Loaded " + dataStations.size() + " stations for language(" + languageCode + ")");
                            } else {
                                // 如果系统语言没有电台，加载所有电台
                                showError("系统语言(" + languageCode + ")没有找到电台，正在尝试加载所有电台...");
                                new android.os.Handler().postDelayed(() -> loadAllStations(), 1500);
                            }
                        } else {
                            // 如果系统语言没有电台，加载所有电台
                            showError("系统语言(" + languageCode + ")没有找到电台，正在尝试加载所有电台...");
                            new android.os.Handler().postDelayed(() -> loadAllStations(), 1500);
                        }
                    }
                });
            });
        } else {
            Log.e(TAG, "Activity is null, cannot load stations by language only");
        }
    }

    private void loadAllStations() {
        Log.d(TAG, "Loading all local stations");
        
        // 检查repository是否已初始化
        if (repository == null) {
            showError("数据仓库未初始化，请重启应用");
            return;
        }
        
        repository.getAllStationsByClickCount().observe(getViewLifecycleOwner(), new Observer<List<RadioStation>>() {
            @Override
            public void onChanged(List<RadioStation> radioStations) {
                if (radioStations != null && !radioStations.isEmpty()) {
                    hideError();
                    // 转换RadioStation为DataRadioStation，预分配容量以提高性能
                    List<DataRadioStation> dataStations = new ArrayList<>(radioStations.size());
                    for (RadioStation radioStation : radioStations) {
                        if (radioStation != null) {
                            DataRadioStation dataStation = radioStation.toDataRadioStation();
                            if (dataStation != null) {
                                dataStations.add(dataStation);
                            }
                        }
                    }
                    
                    if (!dataStations.isEmpty()) {
                        updateStationsList(dataStations);
                        Log.d(TAG, "Loaded " + dataStations.size() + " stations");
                    } else {
                        // 如果所有电台查询也没有结果，显示友好提示信息
                        showError("本地数据库中没有找到任何有效的电台数据，请尝试更新本地数据库");
                    }
                } else {
                    // 如果所有电台查询也没有结果，显示友好提示信息
                    showError("本地数据库中没有找到任何电台，请尝试更新本地数据库");
                }
            }
        });
    }

    private void loadStationsByClickCount() {
        // 检查repository是否已初始化
        if (repository == null) {
            showError("数据仓库未初始化，请重启应用");
            return;
        }
        
        repository.getStationsByClickCount().observe(getViewLifecycleOwner(), new Observer<List<RadioStation>>() {
            @Override
            public void onChanged(List<RadioStation> radioStations) {
                if (radioStations != null && !radioStations.isEmpty()) {
                    hideError();
                    // 转换RadioStation为DataRadioStation，预分配容量以提高性能
                    List<DataRadioStation> dataStations = new ArrayList<>(radioStations.size());
                    for (RadioStation radioStation : radioStations) {
                        if (radioStation != null) {
                            DataRadioStation dataStation = radioStation.toDataRadioStation();
                            if (dataStation != null) {
                                dataStations.add(dataStation);
                            }
                        }
                    }
                    
                    if (!dataStations.isEmpty()) {
                        updateStationsList(dataStations);
                    } else {
                        showError("本地数据库中没有有效的电台数据");
                    }
                } else {
                    showError("本地数据库中没有电台数据");
                }
            }
        });
    }

    private void loadStationsByVotes() {
        // 检查repository是否已初始化
        if (repository == null) {
            showError("数据仓库未初始化，请重启应用");
            return;
        }
        
        repository.getStationsByVotes().observe(getViewLifecycleOwner(), new Observer<List<RadioStation>>() {
            @Override
            public void onChanged(List<RadioStation> radioStations) {
                if (radioStations != null && !radioStations.isEmpty()) {
                    hideError();
                    // 转换RadioStation为DataRadioStation，预分配容量以提高性能
                    List<DataRadioStation> dataStations = new ArrayList<>(radioStations.size());
                    for (RadioStation radioStation : radioStations) {
                        if (radioStation != null) {
                            DataRadioStation dataStation = radioStation.toDataRadioStation();
                            if (dataStation != null) {
                                dataStations.add(dataStation);
                            }
                        }
                    }
                    
                    if (!dataStations.isEmpty()) {
                        updateStationsList(dataStations);
                    } else {
                        showError("本地数据库中没有有效的电台数据");
                    }
                } else {
                    showError("本地数据库中没有电台数据");
                }
            }
        });
    }

    private void loadStationsByLastChangeTime() {
        // 检查repository是否已初始化
        if (repository == null) {
            showError("数据仓库未初始化，请重启应用");
            return;
        }
        
        repository.getStationsByLastChangeTime().observe(getViewLifecycleOwner(), new Observer<List<RadioStation>>() {
            @Override
            public void onChanged(List<RadioStation> radioStations) {
                if (radioStations != null && !radioStations.isEmpty()) {
                    hideError();
                    // 转换RadioStation为DataRadioStation
                    List<DataRadioStation> dataStations = new ArrayList<>();
                    for (RadioStation radioStation : radioStations) {
                        if (radioStation != null) {
                            DataRadioStation dataStation = radioStation.toDataRadioStation();
                            if (dataStation != null) {
                                dataStations.add(dataStation);
                            }
                        }
                    }
                    
                    if (!dataStations.isEmpty()) {
                        updateStationsList(dataStations);
                    } else {
                        showError("本地数据库中没有有效的电台数据");
                    }
                } else {
                    showError("本地数据库中没有电台数据");
                }
            }
        });
    }

    private void showError(String message) {
        if (layoutError != null && textErrorMessage != null && rvStations != null) {
            textErrorMessage.setText(message);
            layoutError.setVisibility(View.VISIBLE);
            rvStations.setVisibility(View.GONE);
        } else {
            // 如果视图未初始化，记录日志
            Log.e(TAG, "Error: " + message);
        }
    }

    private void hideError() {
        if (layoutError != null && rvStations != null) {
            layoutError.setVisibility(View.GONE);
            rvStations.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void search(StationsFilter.SearchStyle searchStyle, String query) {
        lastSearchStyle = searchStyle;
        lastSearchQuery = query;
        
        // 检查repository是否已初始化
        if (repository == null) {
            if (getContext() != null) {
                repository = RadioStationRepository.getInstance(getContext());
            } else {
                showError("数据仓库未初始化，请重启应用");
                return;
            }
        }
        
        if (query != null && !query.isEmpty()) {
            // 执行搜索
            repository.searchStations(query).observe(getViewLifecycleOwner(), new Observer<List<RadioStation>>() {
                @Override
                public void onChanged(List<RadioStation> radioStations) {
                    if (radioStations != null && !radioStations.isEmpty()) {
                        hideError();
                        // 转换RadioStation为DataRadioStation
                        List<DataRadioStation> dataStations = new ArrayList<>();
                        for (RadioStation radioStation : radioStations) {
                            if (radioStation != null) {
                                DataRadioStation dataStation = radioStation.toDataRadioStation();
                                if (dataStation != null) {
                                    dataStations.add(dataStation);
                                }
                            }
                        }
                        
                        if (!dataStations.isEmpty()) {
                            updateStationsList(dataStations);
                        } else {
                            showError("没有找到有效的电台");
                        }
                    } else {
                        showError("没有找到匹配的电台");
                    }
                }
            });
        } else {
            // 如果没有搜索查询，则加载所有数据
            loadData();
        }
    }
    
    private void onStationClick(DataRadioStation station, int pos) {
        if (getActivity() == null) {
            Log.e(TAG, "Activity is null, cannot play station");
            return;
        }
        
        RadioDroidApp radioDroidApp = (RadioDroidApp) getActivity().getApplication();
        if (radioDroidApp == null) {
            Log.e(TAG, "RadioDroidApp is null, cannot play station");
            return;
        }
        
        Utils.showPlaySelection(radioDroidApp, station, getActivity().getSupportFragmentManager());
    }
    
    private void loadStationsByCountry(String countryCode) {
        Log.d(TAG, "Loading stations for country(" + countryCode + ")");
        
        // 加载指定国家的电台
        repository.getStationsByCountryCodeAll(countryCode).observe(getViewLifecycleOwner(), new Observer<List<RadioStation>>() {
            @Override
            public void onChanged(List<RadioStation> radioStations) {
                if (radioStations != null && !radioStations.isEmpty()) {
                    hideError();
                    // 转换RadioStation为DataRadioStation，预分配容量以提高性能
                    List<DataRadioStation> dataStations = new ArrayList<>(radioStations.size());
                    for (RadioStation radioStation : radioStations) {
                        if (radioStation != null) {
                            DataRadioStation dataStation = radioStation.toDataRadioStation();
                            if (dataStation != null) {
                                dataStations.add(dataStation);
                            }
                        }
                    }
                    
                    if (!dataStations.isEmpty()) {
                        updateStationsList(dataStations);
                        Log.d(TAG, "Loaded " + dataStations.size() + " stations for country(" + countryCode + ")");
                    } else {
                        // 如果指定国家没有电台，加载所有电台
                        showError("国家(" + countryCode + ")没有找到电台，正在尝试加载所有电台...");
                        new android.os.Handler().postDelayed(() -> loadAllStations(), 1500);
                    }
                } else {
                    // 如果指定国家没有电台，加载所有电台
                    showError("国家(" + countryCode + ")没有找到电台，正在尝试加载所有电台...");
                    new android.os.Handler().postDelayed(() -> loadAllStations(), 1500);
                }
            }
        });
    }

    private void loadDataWithSystemCountryPriority() {
        if (repository == null) {
            if (getContext() != null) {
                repository = RadioStationRepository.getInstance(getContext());
            } else {
                showError("数据仓库未初始化，请重启应用");
                return;
            }
        }

        java.util.Locale locale = java.util.Locale.getDefault();
        String systemCountry = locale.getCountry();
        String systemLanguage = locale.getLanguage();

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                loadStationsBySystemCountry(systemCountry, systemLanguage);
            });
        } else {
            Log.e(TAG, "Activity is null, cannot load stations");
            showError("Activity不可用，请重启应用");
        }
    }
}