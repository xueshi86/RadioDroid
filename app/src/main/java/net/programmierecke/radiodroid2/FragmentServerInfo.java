package net.programmierecke.radiodroid2;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;


import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.programmierecke.radiodroid2.adapters.ItemAdapterStatistics;
import net.programmierecke.radiodroid2.data.DataStatistics;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.interfaces.IFragmentRefreshable;

import java.util.ArrayList;
import java.util.List;

public class FragmentServerInfo extends Fragment implements IFragmentRefreshable {
    private static final String TAG = "FragmentServerInfo";
    private ItemAdapterStatistics itemAdapterStatistics;
    private RadioStationRepository repository;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_statistics,null);

        if (itemAdapterStatistics == null) {
            itemAdapterStatistics = new ItemAdapterStatistics(getActivity(), R.layout.list_item_statistic);
        }

        ListView lv = (ListView)view.findViewById(R.id.listViewStatistics);
        lv.setAdapter(itemAdapterStatistics);

        // 初始化仓库
        if (getContext() != null) {
            repository = RadioStationRepository.getInstance(getContext());
            loadLocalStatistics();
        }

        return view;
    }

    private void loadLocalStatistics() {
        if (repository == null) {
            Log.e(TAG, "Repository is null, cannot load statistics");
            return;
        }

        // 统计页面统一显示本地数据库的三个口径数字：全部（含损坏）、未损坏、损坏。
        // 线上 /json/stats 不返回 stations_working 字段，无法提供一致的三数字，
        // 故不使用 ServerStatistics，直接读取本地库（本地库为全量镜像，三数字即实际状态）。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 获取所有统计数据
                    final int totalCount = repository.getStationCountSync();
                    final int workingCount = repository.getWorkingStationCountSync();
                    final int brokenCount = repository.getBrokenStationCountSync();

                    // 在主线程更新UI
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateStatisticsUI(totalCount, workingCount, brokenCount);
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error loading statistics", e);
                }
            }
        }).start();
    }
    
    private void updateStatisticsUI(int totalCount, int workingCount, int brokenCount) {
        // 创建统计数据
        ArrayList<DataStatistics> statistics = new ArrayList<>();
        
        // 全部电台数（含损坏）
        DataStatistics totalStations = new DataStatistics();
        totalStations.Name = getString(R.string.statistics_total_stations);
        totalStations.Value = String.valueOf(totalCount);
        statistics.add(totalStations);
            
        // 未损坏电台数
        DataStatistics workingStations = new DataStatistics();
        workingStations.Name = getString(R.string.statistics_working_stations);
        workingStations.Value = String.valueOf(workingCount);
        statistics.add(workingStations);
            
        // 损坏电台数
        DataStatistics brokenStations = new DataStatistics();
        brokenStations.Name = getString(R.string.statistics_broken_stations);
        brokenStations.Value = String.valueOf(brokenCount);
        statistics.add(brokenStations);
            
        // 更新UI
        itemAdapterStatistics.clear();
        for (DataStatistics item : statistics) {
            itemAdapterStatistics.add(item);
        }
            
        Log.d(TAG, "Loaded statistics: " + totalCount + " total stations, " + workingCount + " working, " + brokenCount + " broken");
    }



    @Override
    public void Refresh() {
        loadLocalStatistics();
    }
}
