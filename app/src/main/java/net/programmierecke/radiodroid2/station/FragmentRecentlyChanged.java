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

public class FragmentRecentlyChanged extends FragmentBase implements IFragmentSearchable {
    private static final String TAG = "FragmentRecentlyChanged";

    private RecyclerView recyclerViewStations;
    private SwipeRefreshLayout swiperefresh;
    private LinearLayout layoutError;
    private TextView textErrorMessage;
    private MaterialButton btnRetry;

    private ItemAdapterStation stationListAdapter;
    private RadioStationRepository repository;
    private StationsFilter.SearchStyle lastSearchStyle = StationsFilter.SearchStyle.ByName;
    private String lastSearchQuery = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_stations, container, false);

        recyclerViewStations = view.findViewById(R.id.recyclerViewStations);
        swiperefresh = view.findViewById(R.id.swiperefresh);
        layoutError = view.findViewById(R.id.layoutError);
        textErrorMessage = view.findViewById(R.id.textErrorMessage);
        btnRetry = view.findViewById(R.id.btnRetry);

        // Adapter和LayoutManager将在onActivityCreated中初始化，确保Activity可用
        recyclerViewStations.setAdapter(null);

        // 初始化Repository - 延迟到onActivityCreated以确保Context可用
        // repository = RadioStationRepository.getInstance(getContext()); // 移到onActivityCreated中初始化

        // 设置下拉刷新
        swiperefresh.setEnabled(false); // 本地数据不需要下拉刷新

        // 加载数据 - 移到onActivityCreated中
        // loadData();

        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Log.d(TAG, "onActivityCreated called");
        
        // 在这里初始化Repository和Adapter，确保Context可用
        if (getContext() != null) {
            Log.d(TAG, "Context is available, initializing repository");
            repository = RadioStationRepository.getInstance(getContext());
        } else {
            Log.e(TAG, "Context is null in onActivityCreated");
            showError(true, "应用上下文不可用，请重启应用");
            return;
        }
        
        // 初始化Adapter
        if (getActivity() != null && getContext() != null) {
            Log.d(TAG, "Activity and Context are available, initializing adapter");
            // 设置LayoutManager
            LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
            layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
            recyclerViewStations.setLayoutManager(layoutManager);
            recyclerViewStations.addItemDecoration(new DividerItemDecoration(getContext(), DividerItemDecoration.VERTICAL));
            
            stationListAdapter = new ItemAdapterStation(getActivity(), R.layout.list_item_station);
            stationListAdapter.setStationActionsListener(new ItemAdapterStation.StationActionsListener() {
                @Override
                public void onStationClick(DataRadioStation station, int pos) {
                    FragmentRecentlyChanged.this.onStationClick(station, pos);
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
        } else {
            Log.e(TAG, "Activity is null in onActivityCreated, cannot initialize adapter");
            return;
        }
        
        // 加载数据
        loadData();
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

    private void loadData() {
        Log.d(TAG, "loadData called");
        showLoading(true);
        
        // 检查repository是否已初始化
        if (repository == null) {
            Log.e(TAG, "Repository is null in loadData");
            showError(true, "数据仓库未初始化，请重启应用");
            return;
        }
        
        // 使用统一的空数据库检查
        DatabaseEmptyHelper.checkAndShowEmptyDatabaseError(this, (LinearLayout) layoutError, recyclerViewStations, 
            new DatabaseEmptyHelper.DatabaseCheckCallback() {
                @Override
                public void onCheckCompleted(boolean isEmpty, int count) {
                    if (!isEmpty) {
                        // 数据库有数据，继续加载最近更新的电台
                        loadRecentlyChangedStations();
                    }
                }

                @Override
                public void onCheckError(String error) {
                    showError(true, "检查本地数据库时出错：" + error);
                }
            });
    }
    
    private void loadRecentlyChangedStations() {
        Log.d(TAG, "Loading recently changed stations from database");
        // 确保在主线程上加载数据
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                // 从本地数据库获取最近更新的电台数据
                repository.getRecentlyChangedStations(100).observe(getViewLifecycleOwner(), new Observer<List<RadioStation>>() {
                    @Override
                    public void onChanged(List<RadioStation> radioStations) {
                        Log.d(TAG, "Recently changed stations received: " + (radioStations != null ? radioStations.size() : 0) + " stations");
                        showLoading(false);
                        
                        if (radioStations == null || radioStations.isEmpty()) {
                            // 显示最近更新的电台为空的消息
                            showNoResultsMessage();
                            return;
                        }
                        
                        // 数据加载成功，隐藏错误消息，显示列表
                        showError(false, null);
                        
                        // 更新适配器数据
                        if (stationListAdapter != null) {
                            // 转换为DataRadioStation
                            List<DataRadioStation> dataStations = new ArrayList<>();
                            for (RadioStation radioStation : radioStations) {
                                if (radioStation != null) {
                                    DataRadioStation dataStation = radioStation.toDataRadioStation();
                                    if (dataStation != null) {
                                        dataStations.add(dataStation);
                                    }
                                }
                            }
                            
                            stationListAdapter.updateList(null, dataStations);
                            Log.d(TAG, "Adapter updated with " + dataStations.size() + " recently changed stations");
                        } else {
                            Log.e(TAG, "Adapter is null, cannot update data");
                            showError(true, "显示数据时出错，请重试");
                        }
                    }
                });
            });
        } else {
            Log.e(TAG, "Activity is null, cannot load recently changed stations");
        }
    }

    private void showLoading(boolean show) {
        if (show) {
            recyclerViewStations.setVisibility(View.GONE);
            swiperefresh.setRefreshing(true);
        } else {
            swiperefresh.setRefreshing(false);
        }
    }

    private void showContent(boolean show) {
        showLoading(false);
        if (show) {
            recyclerViewStations.setVisibility(View.VISIBLE);
        } else {
            recyclerViewStations.setVisibility(View.GONE);
        }
    }

    private void showError(boolean show, String errorMessage) {
        showLoading(false);
        
        if (show) {
            recyclerViewStations.setVisibility(View.GONE);
            layoutError.setVisibility(View.VISIBLE);
            
            if (errorMessage != null && textErrorMessage != null) {
                textErrorMessage.setText(errorMessage);
                Log.e(TAG, "Error: " + errorMessage);
            }
            
            if (btnRetry != null) {
                btnRetry.setOnClickListener(v -> {
                    layoutError.setVisibility(View.GONE);
                    loadData();
                });
            }
        } else {
            layoutError.setVisibility(View.GONE);
        }
    }

    @Override
    public void search(StationsFilter.SearchStyle searchStyle, String query) {
        lastSearchStyle = searchStyle;
        lastSearchQuery = query;
        
        if (query == null || query.isEmpty()) {
            loadData();
            return;
        }
        
        showLoading(true);
        
        // 根据搜索类型执行不同的搜索
        switch (searchStyle) {
            case ByName:
                repository.searchStationsByName(query).observe(getViewLifecycleOwner(), stations -> {
                    handleSearchResults(stations);
                });
                break;
            case ByTagExact:
                repository.searchStationsByTags(query).observe(getViewLifecycleOwner(), stations -> {
                    handleSearchResults(stations);
                });
                break;
            case ByCountryCodeExact:
                repository.searchStationsByCountry(query).observe(getViewLifecycleOwner(), stations -> {
                    handleSearchResults(stations);
                });
                break;
            case ByLanguageExact:
                repository.searchStationsByLanguage(query).observe(getViewLifecycleOwner(), stations -> {
                    handleSearchResults(stations);
                });
                break;
            default:
                repository.searchStationsByName(query).observe(getViewLifecycleOwner(), stations -> {
                    handleSearchResults(stations);
                });
                break;
        }
    }
    
    private void handleSearchResults(List<RadioStation> radioStations) {
        if (radioStations != null && !radioStations.isEmpty()) {
            // 转换为DataRadioStation
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
    }
    
    private void showNoResultsMessage() {
        // 创建一个空的电台列表，只显示一条提示信息
        ArrayList<DataRadioStation> stationsList = new ArrayList<>();
        DataRadioStation emptyMessage = new DataRadioStation();
        emptyMessage.Name = "没有找到最近更新的电台";
        emptyMessage.HomePageUrl = "可能数据库更新不完整，请尝试在设置中更新本地数据库";
        stationsList.add(emptyMessage);

        // 更新UI
        if (stationListAdapter != null) {
            stationListAdapter.updateList(null, stationsList);
            showContent(true);
        } else {
            Log.e(TAG, "Adapter is null in showNoResultsMessage");
        }
    }
}