package net.programmierecke.radiodroid2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import net.programmierecke.radiodroid2.station.ItemAdapterStation;
import net.programmierecke.radiodroid2.station.DataRadioStation;
import net.programmierecke.radiodroid2.station.ItemAdapterIconOnlyStation;
import net.programmierecke.radiodroid2.interfaces.IAdapterRefreshable;
import net.programmierecke.radiodroid2.station.StationActions;
import net.programmierecke.radiodroid2.station.StationsFilter;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Observable;
import java.util.Observer;

public class FragmentStarred extends Fragment implements IAdapterRefreshable, Observer {
    private static final String TAG = "FragmentStarred";

    private RecyclerView rvStations;
    private SwipeRefreshLayout swipeRefreshLayout;

    private FavouriteManager favouriteManager;

    void onStationClick(DataRadioStation theStation) {
        RadioDroidApp radioDroidApp = (RadioDroidApp) getActivity().getApplication();
        Utils.showPlaySelection(radioDroidApp, theStation, getActivity().getSupportFragmentManager());
    }

    public void RefreshListGui() {
        if (BuildConfig.DEBUG) Log.d(TAG, "refreshing the stations list.");

        ItemAdapterStation adapter = (ItemAdapterStation) rvStations.getAdapter();

        if (BuildConfig.DEBUG) Log.d(TAG, "stations count:" + favouriteManager.listStations.size());

        adapter.updateList(this, favouriteManager.listStations);
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
        
        favouriteManager = radioDroidApp.getFavouriteManager();
        favouriteManager.addObserver(this);

        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_stations, container, false);
        rvStations = (RecyclerView) view.findViewById(R.id.recyclerViewStations);

        // Adapter将在onActivityCreated中初始化，确保Activity可用
        rvStations.setAdapter(null);

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
            ItemAdapterStation adapter;
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getContext());
            if (sharedPref.getBoolean("load_icons", false) && sharedPref.getBoolean("icons_only_favorites_style", false)) {
                adapter = new ItemAdapterIconOnlyStation(getActivity(), R.layout.list_item_icon_only_station);
                Context ctx = getContext();
                DisplayMetrics displayMetrics = ctx.getResources().getDisplayMetrics();
                int itemWidth = (int) ctx.getResources().getDimension(R.dimen.regular_style_icon_container_width);
                int noOfColumns = displayMetrics.widthPixels / itemWidth;
                GridLayoutManager glm = new GridLayoutManager(ctx, noOfColumns);
                rvStations.setAdapter(adapter);
                rvStations.setLayoutManager(glm);
                ((ItemAdapterIconOnlyStation)adapter).enableItemMove(rvStations);
            } else {
                adapter = new ItemAdapterStation(getActivity(), R.layout.list_item_station);
                LinearLayoutManager llm = new LinearLayoutManager(getContext());
                llm.setOrientation(RecyclerView.VERTICAL);

                rvStations.setAdapter(adapter);
                rvStations.setLayoutManager(llm);
                DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(rvStations.getContext(),
                        llm.getOrientation());
                rvStations.addItemDecoration(dividerItemDecoration);
                adapter.enableItemMoveAndRemoval(rvStations);
            }

            adapter.setStationActionsListener(new ItemAdapterStation.StationActionsListener() {
                @Override
                public void onStationClick(DataRadioStation station, int pos) {
                    FragmentStarred.this.onStationClick(station);
                }

                @Override
                public void onStationSwiped(final DataRadioStation station) {
                    if (getContext() != null && getView() != null) {
                        StationActions.removeFromFavourites(getContext(), getView(), station);
                    }
                }

                @Override
                public void onStationMoved(int from, int to) {
                    favouriteManager.moveWithoutNotify(from, to);
                }

                @Override
                public void onStationMoveFinished() {
                    // We don't want to update RecyclerView during its layout process
                    if (getView() != null) {
                        getView().post(() -> {
                            favouriteManager.Save();
                            favouriteManager.notifyObservers();
                        });
                    }
                }
            });
            
            // 刷新列表
            RefreshListGui();
        } else {
            Log.e(TAG, "Activity is null in onActivityCreated, cannot initialize adapter");
        }
    }

    void RefreshDownloadList(){
        // 收藏列表不需要从网络更新，只使用本地数据
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

        RadioDroidApp radioDroidApp = null;
        if (getActivity() != null) {
            radioDroidApp = (RadioDroidApp) getActivity().getApplication();
        }
        
        if (radioDroidApp != null) {
            favouriteManager = radioDroidApp.getFavouriteManager();
            favouriteManager.deleteObserver(this);
        }
    }

    @Override
    public void update(Observable o, Object arg) {
        RefreshListGui();
    }
}