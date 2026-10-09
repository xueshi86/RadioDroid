package net.programmierecke.radiodroid2.station;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.utils.CustomFilter;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

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
        // 名称命中（包含/模糊）整体优先于备注命中；开关关闭时恒为 true，排序与旧版完全一致
        boolean nameMatch;

        WeightedStation(DataRadioStation station, int weight, boolean nameMatch) {
            this.station = station;
            this.weight = weight;
            this.nameMatch = nameMatch;
        }
    }

    public StationsFilter(@NonNull Context context, @NonNull DataProvider dataProvider) {
        this.context = context;
        this.dataProvider = dataProvider;
    }

    /** 备注命中电台补全回调：appended 为主线程回调，调用方需自行判空视图并刷新适配器。 */
    public interface NoteMatchCallback {
        void onNoteMatchesAppended(List<DataRadioStation> appended);
    }

    /**
     * 备注搜索补全（Issue #54）：本地数据库查询对备注无感知（备注存于 SharedPreferences），
     * 开关「在电台卡片显示备注」打开时，把备注命中但不在当前结果中的电台追加到结果尾部，
     * 与 StationsFilter 排序哲学一致（备注命中排在名称命中之后）。
     * 同步 Room 查询在后台线程执行，命中结果回主线程回调；无命中时不回调。
     */
    public static void appendNoteMatches(@NonNull Context context, @NonNull String query,
                                         @NonNull List<DataRadioStation> currentResults,
                                         @NonNull NoteMatchCallback callback) {
        boolean notesSearchEnabled = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .getBoolean("station_notes_visible", false);
        String trimmed = query.trim();
        if (!notesSearchEnabled || trimmed.length() < 2) {
            return;
        }
        StationNoteManager noteManager = ((RadioDroidApp) context.getApplicationContext()).getStationNoteManager();
        List<String> uuids = noteManager.findUuidsByNoteQuery(trimmed);
        if (uuids.isEmpty()) {
            return;
        }
        Set<String> present = new HashSet<>();
        for (DataRadioStation station : currentResults) {
            present.add(station.StationUuid);
        }
        final List<String> missing = new ArrayList<>();
        for (String uuid : uuids) {
            if (!present.contains(uuid)) {
                missing.add(uuid);
            }
        }
        if (missing.isEmpty()) {
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                RadioStationRepository repository = RadioStationRepository.getInstance(context.getApplicationContext());
                boolean showBroken = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                        .getBoolean("show_broken", false);
                List<DataRadioStation> appended = new ArrayList<>();
                for (String uuid : missing) {
                    net.programmierecke.radiodroid2.database.RadioStation rs = repository.getStationByUuid(uuid);
                    if (rs == null) continue;
                    DataRadioStation ds = rs.toDataRadioStation();
                    if (ds != null && (showBroken || ds.Working)) {
                        appended.add(ds);
                    }
                }
                if (!appended.isEmpty()) {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onNoteMatchesAppended(appended));
                }
            } catch (Exception e) {
                Log.w("StationsFilter", "appendNoteMatches failed", e);
            }
        });
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

        // 备注搜索跟随「在电台卡片显示备注」开关：关闭时不匹配备注（Issue #54）
        boolean notesSearchEnabled = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .getBoolean("station_notes_visible", false);
        StationNoteManager noteManager = notesSearchEnabled
                ? ((RadioDroidApp) context.getApplicationContext()).getStationNoteManager()
                : null;

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
                boolean nameMatch = true;
                int weight;

                if (stationName.contains(query)) {
                    // 名称包含查询字符串：按名称相似度计权重
                    isMatch = true;
                    double distance = levenshtein.distance(query, stationName);
                    int maxLength = Math.max(query.length(), stationName.length());
                    weight = maxLength > 0 ? (int) ((1 - distance / maxLength) * 100) : 100;
                } else {
                    // 名称不包含时使用 Levenshtein 距离模糊匹配
                    double distance = levenshtein.distance(query, stationName);
                    int maxLength = Math.max(query.length(), stationName.length());
                    weight = maxLength > 0 ? (int) ((1 - distance / maxLength) * 100) : 100;

                    isMatch = weight > FUZZY_SEARCH_THRESHOLD;
                }

                if (!isMatch && noteManager != null) {
                    // 备注命中给中等权重，且排在所有名称命中之后：避免压过真正名称包含查询词的电台
                    String note = noteManager.getNote(station.StationUuid);
                    if (note != null && note.toLowerCase().contains(query)) {
                        isMatch = true;
                        weight = 60;
                        nameMatch = false;
                    }
                }

                if (isMatch) {
                    // We will sort stations with similar weight by other metric
                    int compressedWeight = weight / 4;
                    filteredStations.add(new WeightedStation(station, compressedWeight, nameMatch));
                }
            }

            Collections.sort(filteredStations, (x, y) -> {
                if (x.nameMatch != y.nameMatch) {
                    return x.nameMatch ? -1 : 1;
                }
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
