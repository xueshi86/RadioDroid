package net.programmierecke.radiodroid2;

import android.content.Intent;
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

        // 获取本地数据库统计信息
        repository.getStationCount(new RadioStationRepository.StationCountCallback() {
            @Override
            public void onStationCountReceived(int count) {
                // 创建本地统计数据
                ArrayList<DataStatistics> statistics = new ArrayList<>();
                
                // 总电台数
                DataStatistics totalStations = new DataStatistics();
                totalStations.Name = "stations_total";
                totalStations.Value = String.valueOf(count);
                statistics.add(totalStations);
                    
                // 工作正常的电台数
                DataStatistics workingStations = new DataStatistics();
                workingStations.Name = "stations_working";
                workingStations.Value = "N/A"; // 需要额外的查询
                statistics.add(workingStations);
                    
                // 损坏的电台数
                DataStatistics brokenStations = new DataStatistics();
                brokenStations.Name = "stations_broken";
                brokenStations.Value = "N/A"; // 需要额外的查询
                statistics.add(brokenStations);
                    
                // 更新UI
                itemAdapterStatistics.clear();
                for (DataStatistics item : statistics) {
                    itemAdapterStatistics.add(item);
                }
                    
                Log.d(TAG, "Loaded local statistics: " + count + " total stations");
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "Error loading station count: " + error);
            }
        });
    }

    @Override
    public void Refresh() {
        loadLocalStatistics();
    }
}
