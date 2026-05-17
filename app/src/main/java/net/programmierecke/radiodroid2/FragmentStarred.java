package net.programmierecke.radiodroid2;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import net.programmierecke.radiodroid2.station.ItemAdapterStation;
import net.programmierecke.radiodroid2.station.DataRadioStation;
import net.programmierecke.radiodroid2.station.ItemAdapterIconOnlyStation;
import net.programmierecke.radiodroid2.interfaces.IAdapterRefreshable;
import net.programmierecke.radiodroid2.station.StationActions;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Observable;
import java.util.Observer;

public class FragmentStarred extends Fragment implements IAdapterRefreshable, Observer {
    private static final String TAG = "FragmentStarred";

    private static final String PREF_SORT_MODE = "favorites_sort_mode";
    private static final String PREF_SORT_ASCENDING = "favorites_sort_ascending";
    static final int SORT_NONE = 0;
    static final int SORT_NAME = 1;
    static final int SORT_CLICK_COUNT = 2;
    static final int SORT_VOTES = 3;
    static final int SORT_RECENT = 4;

    private RecyclerView rvStations;
    private SwipeRefreshLayout swipeRefreshLayout;
    private FloatingActionButton fabScrollToTop;

    private FavouriteManager favouriteManager;
    private int currentSortMode = SORT_NONE;
    private boolean sortAscending = true;

    int getCurrentSortMode() {
        return currentSortMode;
    }

    boolean isSortAscending() {
        return sortAscending;
    }

    void setSortMode(int mode) {
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
        RefreshListGui();
    }

    void onStationClick(DataRadioStation theStation) {
        RadioDroidApp radioDroidApp = (RadioDroidApp) getActivity().getApplication();
        Utils.showPlaySelection(radioDroidApp, theStation, getActivity().getSupportFragmentManager());
    }

    public void RefreshListGui() {
        if (BuildConfig.DEBUG) Log.d(TAG, "refreshing the stations list.");

        ItemAdapterStation adapter = (ItemAdapterStation) rvStations.getAdapter();
        if (adapter == null) return;

        if (BuildConfig.DEBUG) Log.d(TAG, "stations count:" + favouriteManager.listStations.size());

        List<DataRadioStation> stations;
        if (currentSortMode != SORT_NONE) {
            stations = getSortedStations();
        } else {
            stations = favouriteManager.listStations;
        }

        adapter.updateList(this, stations);
    }

    private List<DataRadioStation> getSortedStations() {
        List<DataRadioStation> sorted = new java.util.ArrayList<>(favouriteManager.listStations);
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

        return sorted;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        RadioDroidApp radioDroidApp = null;
        if (getActivity() != null) {
            radioDroidApp = (RadioDroidApp) getActivity().getApplication();
        }

        if (radioDroidApp == null) {
            Log.e(TAG, "Cannot get RadioDroidApp, Activity is null");
            return new View(getContext());
        }

        favouriteManager = radioDroidApp.getFavouriteManager();
        favouriteManager.addObserver(this);

        View view = inflater.inflate(R.layout.fragment_stations, container, false);
        rvStations = (RecyclerView) view.findViewById(R.id.recyclerViewStations);
        fabScrollToTop = view.findViewById(R.id.fabScrollToTop);
        rvStations.setAdapter(null);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        currentSortMode = prefs.getInt(PREF_SORT_MODE, SORT_NONE);
        sortAscending = prefs.getBoolean(PREF_SORT_ASCENDING, true);

        swipeRefreshLayout = (SwipeRefreshLayout) view.findViewById(R.id.swiperefresh);
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> RefreshDownloadList());
        }

        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        if (getActivity() != null) {
            ItemAdapterStation adapter;
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getContext());
            if (sharedPref.getBoolean("load_icons", false) && sharedPref.getBoolean("icons_only_favorites_style", false)) {
                adapter = new ItemAdapterIconOnlyStation(getActivity(), R.layout.list_item_icon_only_station);
                Context ctx = getContext();
                DisplayMetrics displayMetrics = ctx.getResources().getDisplayMetrics();
                int itemWidth = (int) ctx.getResources().getDimension(R.dimen.regular_style_icon_container_width);
                int noOfColumns = displayMetrics.widthPixels / itemWidth;
                rvStations.setAdapter(adapter);
                rvStations.setLayoutManager(new GridLayoutManager(ctx, noOfColumns));
                ((ItemAdapterIconOnlyStation)adapter).enableItemMove(rvStations);
            } else {
                adapter = new ItemAdapterStation(getActivity(), R.layout.list_item_station);
                LinearLayoutManager llm = new LinearLayoutManager(getContext());
                llm.setOrientation(RecyclerView.VERTICAL);
                rvStations.setAdapter(adapter);
                rvStations.setLayoutManager(llm);
                rvStations.addItemDecoration(new DividerItemDecoration(rvStations.getContext(), llm.getOrientation()));
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
                    if (getView() != null) {
                        getView().post(() -> {
                            favouriteManager.Save();
                            favouriteManager.notifyObservers();
                        });
                    }
                }
            });

            rvStations.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    if (fabScrollToTop != null) {
                        fabScrollToTop.setVisibility(recyclerView.canScrollVertically(-1) ? View.VISIBLE : View.GONE);
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

            if (favouriteManager.listStations.size() > 0) {
                if (getActivity() instanceof ActivityMain) {
                    ((ActivityMain) getActivity()).invalidateOptionsMenu();
                }
            }
            RefreshListGui();
        } else {
            Log.e(TAG, "Activity is null in onActivityCreated, cannot initialize adapter");
        }
    }

    void RefreshDownloadList(){
        RefreshListGui();
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        rvStations.setAdapter(null);
        if (favouriteManager != null) {
            favouriteManager.deleteObserver(this);
        }
    }

    @Override
    public void update(Observable o, Object arg) {
        if (getActivity() instanceof ActivityMain) {
            ((ActivityMain) getActivity()).invalidateOptionsMenu();
        }
        RefreshListGui();
    }
}
