package net.programmierecke.radiodroid2.station;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import net.programmierecke.radiodroid2.utils.CustomFilter;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import info.debatty.java.stringsimilarity.Levenshtein;

public class StationsFilter extends CustomFilter {
    public enum SearchStatus {
        SUCCESS,
        ERROR
    }

    public enum SearchStyle {
        ByName,
        ByLanguageExact,
        ByCountryCodeExact,
        ByTagExact,
    }

    public interface DataProvider {
        List<DataRadioStation> getOriginalStationList();

        void notifyFilteredStationsChanged(SearchStatus status, List<DataRadioStation> filteredStations);
    }

    private final String TAG = "StationsFilter";
    private final int FUZZY_SEARCH_THRESHOLD = 80;
    private final Levenshtein levenshtein = new Levenshtein();

    private Context context;
    private DataProvider dataProvider;

    private List<DataRadioStation> filteredStationsList;
    private SearchStatus lastRemoteSearchStatus = SearchStatus.SUCCESS;

    private SearchStyle searchStyle = SearchStyle.ByName;

    private class WeightedStation {
        DataRadioStation station;
        int weight;

        WeightedStation(DataRadioStation station, int weight) {
            this.station = station;
            this.weight = weight;
        }
    }

    public StationsFilter(@NonNull Context context, @NonNull DataProvider dataProvider) {
        this.context = context;
        this.dataProvider = dataProvider;
    }

    public void setSearchStyle(SearchStyle searchStyle){
        Log.d("FILTER","Changed search style:" + searchStyle);
        this.searchStyle = searchStyle;
    }

    public void clearList(){
        Log.d("FILTER", "forced refetch");
        // 本地搜索不需要缓存远程查询结果
    }

    @Override
    protected FilterResults performFiltering(CharSequence constraint) {
        final String query = constraint.toString().toLowerCase();
        Log.d("FILTER", "performFiltering() " + query);

        // 只使用本地搜索，不再进行网络查询
        List<DataRadioStation> stationsToFilter = dataProvider.getOriginalStationList();
        lastRemoteSearchStatus = SearchStatus.SUCCESS;
        
        if (query.isEmpty() || query.length() < 2) {
            Log.d("FILTER", "performFiltering() 2 " + query);
            filteredStationsList = stationsToFilter;
        } else {
            Log.d("FILTER", "performFiltering() 3 " + query);
            Log.d("FILTER", "performFiltering() 4a " + query);
            ArrayList<WeightedStation> filteredStations = new ArrayList<>();

            for (DataRadioStation station : stationsToFilter) {
                String stationName = station.Name.toLowerCase();
                boolean isMatch = false;
                
                // 首先检查是否包含查询字符串（精确匹配部分）
                if (stationName.contains(query)) {
                    isMatch = true;
                } else {
                    // 对于不包含查询字符串的结果，使用Levenshtein距离进行模糊匹配
                    double distance = levenshtein.distance(query, stationName);
                    int maxLength = Math.max(query.length(), stationName.length());
                    int weight = maxLength > 0 ? (int) ((1 - distance / maxLength) * 100) : 100;
                    
                    isMatch = weight > FUZZY_SEARCH_THRESHOLD;
                }
                
                if (isMatch) {
                    // 计算权重用于排序
                    double distance = levenshtein.distance(query, stationName);
                    int maxLength = Math.max(query.length(), stationName.length());
                    int weight = maxLength > 0 ? (int) ((1 - distance / maxLength) * 100) : 100;
                    
                    // We will sort stations with similar weight by other metric
                    int compressedWeight = weight / 4;
                    filteredStations.add(new WeightedStation(station, compressedWeight));
                }
            }

            Collections.sort(filteredStations, (x, y) -> {
                if (x.weight == y.weight) {
                    return -Integer.compare(x.station.ClickCount, y.station.ClickCount);
                }
                return -Integer.compare(x.weight, y.weight);
            });

            filteredStationsList = new ArrayList<>();
            for (WeightedStation weightedStation : filteredStations) {
                filteredStationsList.add(weightedStation.station);
            }
        }

        FilterResults filterResults = new FilterResults();
        filterResults.values = filteredStationsList;
        return filterResults;
    }

    @Override
    protected void publishResults(CharSequence constraint, FilterResults results) {
        dataProvider.notifyFilteredStationsChanged(lastRemoteSearchStatus, (List<DataRadioStation>) results.values);
    }
}
