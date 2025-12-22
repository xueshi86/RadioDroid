package net.programmierecke.radiodroid2.station;

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
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;

import net.programmierecke.radiodroid2.FragmentBase;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.database.RadioStation;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.interfaces.IFragmentSearchable;
import net.programmierecke.radiodroid2.utils.DatabaseEmptyHelper;

import java.util.ArrayList;
import java.util.List;

public class FragmentSearchLocal extends FragmentBase implements IFragmentSearchable {
    private static final String TAG = "FragmentSearchLocal";

    private RecyclerView recyclerViewStations;
    private LinearLayout layoutError;
    private MaterialButton btnRetry;
    private SwipeRefreshLayout swiperefresh;

    private ItemAdapterStation stationListAdapter;
    private RadioStationRepository repository;
    private StationsFilter.SearchStyle lastSearchStyle = StationsFilter.SearchStyle.ByName;
    private String lastSearchQuery = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_stations, container, false);

        recyclerViewStations = view.findViewById(R.id.recyclerViewStations);
        layoutError = view.findViewById(R.id.layoutError);
        swiperefresh = view.findViewById(R.id.swiperefresh);

        // Adapter和LayoutManager将在onActivityCreated中初始化，确保Activity可用
        recyclerViewStations.setAdapter(null);

        // 初始化Repository - 延迟到onActivityCreated以确保Context可用
        // repository = RadioStationRepository.getInstance(getContext()); // 移到onActivityCreated中初始化

        // 设置下拉刷新
        swiperefresh.setEnabled(false); // 本地数据不需要下拉刷新

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
            return;
        }
        
        // 初始化Adapter
        if (getActivity() != null) {
            stationListAdapter = new ItemAdapterStation(getActivity(), R.layout.list_item_station);
            stationListAdapter.setStationActionsListener(new ItemAdapterStation.StationActionsListener() {
                @Override
                public void onStationClick(DataRadioStation station, int pos) {
                    FragmentSearchLocal.this.onStationClick(station, pos);
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
            recyclerViewStations.setAdapter(stationListAdapter);
            // 设置LayoutManager
            recyclerViewStations.setLayoutManager(new LinearLayoutManager(getActivity()));
        } else {
            Log.e(TAG, "Activity is null in onActivityCreated, cannot initialize adapter");
            return;
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

    @Override
    public void search(StationsFilter.SearchStyle searchStyle, String query) {
        Log.d(TAG, "Search called with style: " + searchStyle + ", query: " + query);
        
        lastSearchStyle = searchStyle;
        lastSearchQuery = query;
        
        if (query == null || query.isEmpty()) {
            Log.d(TAG, "Query is null or empty, showing error");
            if (stationListAdapter != null) {
                stationListAdapter.updateList(null, new ArrayList<>());
            }
            showError(true, "请输入搜索关键词");
            return;
        }
        
        // 检查repository是否已初始化
        if (repository == null) {
            Log.e(TAG, "Repository is null, cannot perform search");
            showError(true, "数据仓库未初始化，请重启应用");
            return;
        }
        
        // 检查Fragment是否已附加到Activity
        if (!isAdded() || getActivity() == null) {
            Log.e(TAG, "Fragment not attached to activity, cannot perform search");
            return;
        }
        
        // 检查stationListAdapter是否已初始化
        if (stationListAdapter == null) {
            Log.e(TAG, "StationListAdapter is null, cannot perform search");
            showError(true, "适配器未初始化，请重启应用");
            return;
        }
        
        // 使用统一的空数据库检查
        DatabaseEmptyHelper.checkAndShowEmptyDatabaseError(this, layoutError, recyclerViewStations, 
            new DatabaseEmptyHelper.DatabaseCheckCallback() {
                @Override
                public void onCheckCompleted(boolean isEmpty, int count) {
                    // 再次检查Fragment状态，防止在异步操作中Fragment已分离
                    if (!isAdded() || getActivity() == null) {
                        Log.e(TAG, "Fragment detached during database check");
                        return;
                    }
                    
                    if (!isEmpty) {
                        // 数据库有数据，继续搜索
                        Log.d(TAG, "Database is not empty, performing search with count: " + count);
                        performSearch(searchStyle, query);
                    } else {
                        Log.d(TAG, "Database is empty, not performing search");
                    }
                }

                @Override
                public void onCheckError(String error) {
                    // 再次检查Fragment状态，防止在异步操作中Fragment已分离
                    if (!isAdded() || getActivity() == null) {
                        Log.e(TAG, "Fragment detached during database check error");
                        return;
                    }
                    
                    Log.e(TAG, "Database check error: " + error);
                    showError(true, "检查本地数据库时出错：" + error);
                }
            });
    }
    
    private void performSearch(StationsFilter.SearchStyle searchStyle, String query) {
        Log.d(TAG, "Performing search with style: " + searchStyle + ", query: " + query);
        
        // 再次检查Fragment状态
        if (!isAdded() || getActivity() == null) {
            Log.e(TAG, "Fragment not attached to activity, cannot perform search");
            return;
        }
        
        // 检查repository是否已初始化
        if (repository == null) {
            Log.e(TAG, "Repository is null, cannot perform search");
            showError(true, "数据仓库未初始化，请重启应用");
            return;
        }
        
        // 检查stationListAdapter是否已初始化
        if (stationListAdapter == null) {
            Log.e(TAG, "StationListAdapter is null, cannot perform search");
            showError(true, "适配器未初始化，请重启应用");
            return;
        }
        
        showLoading(true);
        
        try {
            // 根据搜索类型执行不同的搜索
            switch (searchStyle) {
                case ByName:
                    Log.d(TAG, "Searching by name");
                    repository.searchStationsByName(query).observe(getViewLifecycleOwner(), stations -> {
                        handleSearchResults(stations);
                    });
                    break;
                case ByTagExact:
                    Log.d(TAG, "Searching by tags");
                    repository.searchStationsByTags(query).observe(getViewLifecycleOwner(), stations -> {
                        handleSearchResults(stations);
                    });
                    break;
                case ByCountryCodeExact:
                    Log.d(TAG, "Searching by country");
                    repository.searchStationsByCountry(query).observe(getViewLifecycleOwner(), stations -> {
                        handleSearchResults(stations);
                    });
                    break;
                case ByLanguageExact:
                    Log.d(TAG, "Searching by language");
                    repository.searchStationsByLanguage(query).observe(getViewLifecycleOwner(), stations -> {
                        handleSearchResults(stations);
                    });
                    break;
                default:
                    Log.d(TAG, "Searching with default method");
                    repository.searchStations(query).observe(getViewLifecycleOwner(), stations -> {
                        handleSearchResults(stations);
                    });
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during search", e);
            showError(true, "搜索时发生错误：" + e.getMessage());
        }
    }
    
    private void handleSearchResults(List<RadioStation> radioStations) {
        Log.d(TAG, "Handling search results, count: " + (radioStations != null ? radioStations.size() : "null"));
        
        // 再次检查Fragment状态
        if (!isAdded() || getActivity() == null) {
            Log.e(TAG, "Fragment not attached to activity, cannot handle search results");
            return;
        }
        
        // 检查stationListAdapter是否已初始化
        if (stationListAdapter == null) {
            Log.e(TAG, "StationListAdapter is null, cannot handle search results");
            showError(true, "适配器未初始化，请重启应用");
            return;
        }
        
        try {
            if (radioStations != null && !radioStations.isEmpty()) {
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
                    stationListAdapter.updateList(null, dataStations);
                    showContent(true);
                    Log.d(TAG, "搜索到 " + dataStations.size() + " 个电台");
                } else {
                    stationListAdapter.updateList(null, new ArrayList<>());
                    showContent(true);
                    Log.d(TAG, "没有搜索到有效的电台");
                }
            } else {
                stationListAdapter.updateList(null, new ArrayList<>());
                showContent(true);
                Log.d(TAG, "没有搜索到匹配的电台");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling search results", e);
            showError(true, "处理搜索结果时发生错误：" + e.getMessage());
        }
    }

    private void showLoading(boolean show) {
        if (show) {
            recyclerViewStations.setVisibility(View.GONE);
            layoutError.setVisibility(View.GONE);
            swiperefresh.setRefreshing(true);
        } else {
            swiperefresh.setRefreshing(false);
        }
    }

    private void showContent(boolean show) {
        showLoading(false);
        if (show) {
            recyclerViewStations.setVisibility(View.VISIBLE);
            layoutError.setVisibility(View.GONE);
        } else {
            recyclerViewStations.setVisibility(View.GONE);
            layoutError.setVisibility(View.VISIBLE);
        }
    }

    private void showError(boolean show, String errorMessage) {
        Log.d(TAG, "ShowError called: " + show + ", message: " + errorMessage);
        
        // 再次检查Fragment状态
        if (!isAdded() || getActivity() == null) {
            Log.e(TAG, "Fragment not attached to activity, cannot show error");
            return;
        }
        
        showLoading(false);
        recyclerViewStations.setVisibility(View.GONE);
        layoutError.setVisibility(View.VISIBLE);
        
        if (errorMessage != null) {
            // 可以在这里显示错误消息
            Log.e(TAG, "Error: " + errorMessage);
            
            // 尝试在错误布局中显示错误消息
            TextView textErrorMessage = layoutError.findViewById(R.id.textErrorMessage);
            if (textErrorMessage != null) {
                textErrorMessage.setText(errorMessage);
            }
        }
    }
}