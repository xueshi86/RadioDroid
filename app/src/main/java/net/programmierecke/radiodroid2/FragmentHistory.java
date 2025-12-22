package net.programmierecke.radiodroid2;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;

import net.programmierecke.radiodroid2.station.ItemAdapterStation;
import net.programmierecke.radiodroid2.station.DataRadioStation;
import net.programmierecke.radiodroid2.interfaces.IAdapterRefreshable;
import net.programmierecke.radiodroid2.station.StationActions;
import net.programmierecke.radiodroid2.station.StationsFilter;

import java.util.ArrayList;
import java.util.List;

public class FragmentHistory extends Fragment implements IAdapterRefreshable {
    private static final String TAG = "FragmentHistory";

    private RecyclerView rvStations;
    private SwipeRefreshLayout swipeRefreshLayout;

    private HistoryManager historyManager;

    void onStationClick(DataRadioStation theStation) {
        if (getActivity() != null) {
            RadioDroidApp radioDroidApp = (RadioDroidApp) getActivity().getApplication();
            Utils.showPlaySelection(radioDroidApp, theStation, getActivity().getSupportFragmentManager());

            RefreshListGui();
            rvStations.smoothScrollToPosition(0);
        } else {
            Log.e(TAG, "Activity is null in onStationClick");
        }
    }

    @Override
    public void RefreshListGui() {
        if (BuildConfig.DEBUG) Log.d(TAG, "refreshing the stations list.");

        ItemAdapterStation adapter = (ItemAdapterStation) rvStations.getAdapter();

        if (BuildConfig.DEBUG) Log.d(TAG, "stations count:" + historyManager.listStations.size());

        if( adapter != null )
            adapter.updateList(null, historyManager.listStations);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // 使用安全的方式获取Application
        RadioDroidApp radioDroidApp = null;
        if (getActivity() != null) {
            radioDroidApp = (RadioDroidApp) getActivity().getApplication();
        }
        
        if (radioDroidApp == null) {
            Log.e(TAG, "Cannot get RadioDroidApp, Activity is null");
            // 返回一个空视图以防止崩溃
            return new View(getContext());
        }
        
        historyManager = radioDroidApp.getHistoryManager();

        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_stations, container, false);

        LinearLayoutManager llm = new LinearLayoutManager(getContext());
        llm.setOrientation(LinearLayoutManager.VERTICAL);

        rvStations = (RecyclerView) view.findViewById(R.id.recyclerViewStations);
        
        // Adapter将在onActivityCreated中初始化，确保Activity可用
        rvStations.setAdapter(null);
        rvStations.setLayoutManager(llm);
        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(rvStations.getContext(),
                llm.getOrientation());
        rvStations.addItemDecoration(dividerItemDecoration);

        swipeRefreshLayout = (SwipeRefreshLayout) view.findViewById(R.id.swiperefresh);
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(
                    new SwipeRefreshLayout.OnRefreshListener() {
                        @Override
                        public void onRefresh() {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "onRefresh called from SwipeRefreshLayout");
                            }
                            RefreshDownloadList();
                        }
                    }
            );
        }

        // RefreshListGui将在onActivityCreated中调用
        // RefreshListGui();

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
                    FragmentHistory.this.onStationClick(station);
                }

                @Override
                public void onStationSwiped(final DataRadioStation station) {
                    final int removedIdx = historyManager.remove(station.StationUuid);

                    RefreshListGui();

                    if (getView() != null) {
                        Snackbar snackbar = Snackbar
                                .make(rvStations, R.string.notify_station_removed_from_list, 6000);
                        snackbar.setAnchorView(getView().getRootView().findViewById(R.id.bottom_sheet));
                        snackbar.setAction(R.string.action_station_removed_from_list_undo, new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                historyManager.restore(station, removedIdx);
                                RefreshListGui();
                            }
                        });
                        snackbar.show();
                    }
                }

                @Override
                public void onStationMoved(int from, int to) {
                }

                @Override
                public void onStationMoveFinished() {
                }
            });
            
            rvStations.setAdapter(adapter);
            adapter.enableItemRemoval(rvStations);
            
            // 刷新列表
            RefreshListGui();
        } else {
            Log.e(TAG, "Activity is null in onActivityCreated, cannot initialize adapter");
        }
    }

    void RefreshDownloadList(){
        // 历史记录不需要从网络更新，只使用本地数据
        // 直接刷新UI显示
        RefreshListGui();
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        rvStations.setAdapter(null);
    }
}
