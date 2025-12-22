package net.programmierecke.radiodroid2.station;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;

import net.programmierecke.radiodroid2.ActivityMain;
import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.FragmentBase;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.StationSaveManager;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.database.RadioStation;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.interfaces.IFragmentSearchable;
import net.programmierecke.radiodroid2.utils.CustomFilter;

import java.util.ArrayList;
import java.util.List;

public class FragmentStations extends FragmentBase implements IFragmentSearchable {
    private static final String TAG = "FragmentStations";

    public static final String KEY_SEARCH_ENABLED = "SEARCH_ENABLED";

    private RecyclerView rvStations;
    private ViewGroup layoutError;
    private MaterialButton btnRetry;
    private SwipeRefreshLayout swipeRefreshLayout;

    private SharedPreferences sharedPref;

    private boolean searchEnabled = false;

    private StationsFilter stationsFilter;
    private StationsFilter.SearchStyle lastSearchStyle = StationsFilter.SearchStyle.ByName;
    private String lastQuery = "";
    private StationSaveManager queue;
    
    // 本地数据库仓库
    private RadioStationRepository repository;

    void onStationClick(DataRadioStation theStation, int pos) {
        RadioDroidApp radioDroidApp = (RadioDroidApp) getActivity().getApplication();
        Utils.showPlaySelection(radioDroidApp, theStation, getActivity().getSupportFragmentManager());
    }

    @Override
    protected void RefreshListGui() {
        if (rvStations == null) {
            return;
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "refreshing the stations list.");

        Context ctx = getContext();
        if (sharedPref == null) {
            sharedPref = PreferenceManager.getDefaultSharedPreferences(ctx);
        }

        boolean show_broken = sharedPref.getBoolean("show_broken", false);
        
        // 初始化仓库实例
        if (repository == null) {
            repository = RadioStationRepository.getInstance(ctx);
        }

        // 从本地数据库获取电台数据
        LiveData<List<RadioStation>> liveDataStations;
        
        // 根据搜索状态决定数据源
        if (searchEnabled && !TextUtils.isEmpty(lastQuery)) {
            // 有搜索查询，使用FTS快速搜索结果
            liveDataStations = repository.searchStationsFast(lastQuery);
        } else {
            // 没有搜索查询，获取所有电台
            liveDataStations = repository.getAllStationsByName();
        }
        
        // 观察数据变化
        liveDataStations.observe(getViewLifecycleOwner(), new Observer<List<RadioStation>>() {
            @Override
            public void onChanged(List<RadioStation> radioStations) {
                if (radioStations != null) {
                    ArrayList<DataRadioStation> filteredStationsList = new ArrayList<>();
                    queue.clear();
                    
                    // 转换RadioStation到DataRadioStation
                    for (RadioStation station : radioStations) {
                        DataRadioStation dataStation = station.toDataRadioStation();
                        queue.add(dataStation);
                        
                        // 根据设置过滤损坏的电台
                        if (show_broken || dataStation.Working) {
                            filteredStationsList.add(dataStation);
                        }
                    }
                    
                    if (BuildConfig.DEBUG) Log.d(TAG, "station count:" + radioStations.size() + ", filtered count:" + filteredStationsList.size());
                    
                    // 更新UI
                    ItemAdapterStation adapter = (ItemAdapterStation) rvStations.getAdapter();
                    if (adapter != null) {
                        adapter.updateList(null, filteredStationsList);
                        if (searchEnabled) {
                            stationsFilter.filter("");
                        }
                    }
                    
                    // 隐藏加载状态
                    LocalBroadcastManager.getInstance(getContext()).sendBroadcast(new Intent(ActivityMain.ACTION_HIDE_LOADING));
                    if (swipeRefreshLayout != null) {
                        swipeRefreshLayout.setRefreshing(false);
                    }
                }
            }
        });
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Log.d("STATIONS","onCreateView()");
        queue = new StationSaveManager(getContext());
        Bundle bundle = getArguments();
        if (bundle != null) {
            searchEnabled = bundle.getBoolean(KEY_SEARCH_ENABLED, false);
        }

        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_stations_remote, container, false);
        rvStations = (RecyclerView) view.findViewById(R.id.recyclerViewStations);
        layoutError = view.findViewById(R.id.layoutError);
        btnRetry = view.findViewById(R.id.btnRefresh);

        // Adapter将在onActivityCreated中初始化，确保Activity可用
        rvStations.setAdapter(null);

        if (searchEnabled) {
            // stationsFilter将在onActivityCreated中初始化
        }

        LinearLayoutManager llm = new LinearLayoutManager(getContext());
        llm.setOrientation(LinearLayoutManager.VERTICAL);

        rvStations.setLayoutManager(llm);

        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(rvStations.getContext(),
                llm.getOrientation());
        rvStations.addItemDecoration(dividerItemDecoration);

        swipeRefreshLayout = (SwipeRefreshLayout) view.findViewById(R.id.swiperefresh);
        swipeRefreshLayout.setOnRefreshListener(
                () -> {
                    // 从本地数据库刷新数据
                    RefreshListGui();
                }
        );

        // RefreshListGui将在onActivityCreated中调用
        // RefreshListGui();

        // 搜索将在onActivityCreated中执行
        if (lastQuery != null) {
            Log.d("STATIONS", "Will do queued search for: "+lastQuery + " style="+lastSearchStyle);
        }

        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        
        // 在这里初始化Adapter，确保Activity可用
        if (getActivity() != null) {
            ItemAdapterStation adapter = new ItemAdapterStation(getActivity(), R.layout.list_item_station);
            adapter.setStationActionsListener(new ItemAdapterStation.StationActionsListener() {
                @Override
                public void onStationClick(DataRadioStation station, int pos) {
                    FragmentStations.this.onStationClick(station, pos);
                }

                @Override
                public void onStationSwiped(DataRadioStation station) {
                }

                @Override
                public void onStationMoved(int from, int to) {
                }

                @Override
                public void onStationMoveFinished() {
                }
            });

            if (searchEnabled) {
                stationsFilter = adapter.getFilter();

                stationsFilter.setDelayer(new CustomFilter.Delayer() {
                    private int previousLength = 0;

                    public long getPostingDelay(CharSequence constraint) {
                        if (constraint == null) {
                            return 0;
                        }

                        long delay = 0;
                        if (constraint.length() < previousLength) {
                            delay = 500;
                        }
                        previousLength = constraint.length();

                        return delay;
                    }
                });

                adapter.setFilterListener(searchStatus -> {
                    layoutError.setVisibility(searchStatus == StationsFilter.SearchStatus.ERROR ? View.VISIBLE : View.GONE);
                    LocalBroadcastManager.getInstance(getContext()).sendBroadcast(new Intent(ActivityMain.ACTION_HIDE_LOADING));
                    swipeRefreshLayout.setRefreshing(false);
                });

                btnRetry.setOnClickListener(v -> search(lastSearchStyle, lastQuery));
            }

            rvStations.setAdapter(adapter);
            
            // 刷新列表
            RefreshListGui();
            
            // 执行待处理的搜索
            if (lastQuery != null && stationsFilter != null){
                Log.d("STATIONS", "do queued search for: "+lastQuery + " style="+lastSearchStyle);
                stationsFilter.clearList();
                search(lastSearchStyle, lastQuery);
            }
        } else {
            Log.e(TAG, "Activity is null in onActivityCreated, cannot initialize adapter");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        rvStations.setAdapter(null);
    }

    @Override
    public void search(StationsFilter.SearchStyle searchStyle, String query) {
        Log.d("STATIONS", "query = "+query + " searchStyle="+searchStyle);
        lastQuery = query;
        lastSearchStyle = searchStyle;

        if (rvStations != null && searchEnabled) {
            Log.d("STATIONS", "query a = "+query);
            if (!TextUtils.isEmpty(query)) {
                LocalBroadcastManager.getInstance(getContext()).sendBroadcast(new Intent(ActivityMain.ACTION_SHOW_LOADING));
            }

            // 初始化仓库实例
            if (repository == null) {
                repository = RadioStationRepository.getInstance(getContext());
            }
            
            // 根据搜索风格选择不同的搜索方法
            LiveData<List<RadioStation>> searchResults;
            switch (searchStyle) {
                case ByName:
                    searchResults = repository.searchStationsByNameFast(query);
                    break;
                case ByTagExact:
                    searchResults = repository.searchStationsByTagsFast(query);
                    break;
                case ByCountryCodeExact:
                    searchResults = repository.searchStationsByCountryFast(query);
                    break;
                case ByLanguageExact:
                    searchResults = repository.searchStationsByLanguageFast(query);
                    break;
                default:
                    searchResults = repository.searchStationsFast(query);
                    break;
            }
            
            // 观察搜索结果
            searchResults.observe(getViewLifecycleOwner(), new Observer<List<RadioStation>>() {
                @Override
                public void onChanged(List<RadioStation> radioStations) {
                    if (radioStations != null) {
                        ArrayList<DataRadioStation> filteredStationsList = new ArrayList<>();
                        queue.clear();
                        
                        // 获取用户设置
                        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getContext());
                        boolean show_broken = sharedPref.getBoolean("show_broken", false);
                        
                        // 转换RadioStation到DataRadioStation并过滤
                        for (RadioStation station : radioStations) {
                            DataRadioStation dataStation = station.toDataRadioStation();
                            queue.add(dataStation);
                            
                            if (show_broken || dataStation.Working) {
                                filteredStationsList.add(dataStation);
                            }
                        }
                        
                        // 更新UI
                        ItemAdapterStation adapter = (ItemAdapterStation) rvStations.getAdapter();
                        if (adapter != null) {
                            adapter.updateList(null, filteredStationsList);
                        }
                        
                        // 隐藏加载状态
                        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(new Intent(ActivityMain.ACTION_HIDE_LOADING));
                        if (swipeRefreshLayout != null) {
                            swipeRefreshLayout.setRefreshing(false);
                        }
                    }
                }
            });
        } else {
            Log.d("STATIONS", "query b = "+query + " " + searchEnabled + " ");
        }
    }

    void RefreshDownloadList(){
        // 电台列表不需要从网络更新，只使用本地数据
        // 直接刷新UI显示
        RefreshListGui();
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
        }
    }
}
