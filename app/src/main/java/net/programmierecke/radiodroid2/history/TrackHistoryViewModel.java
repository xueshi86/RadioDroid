package net.programmierecke.radiodroid2.history;

import android.app.Application;

import androidx.annotation.Nullable;
import androidx.arch.core.util.Function;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.paging.PagedList;

import net.programmierecke.radiodroid2.RadioDroidApp;

public class TrackHistoryViewModel extends AndroidViewModel {
    private final TrackHistoryRepository repository;

    private final MutableLiveData<String> stationUuidLiveData = new MutableLiveData<>();
    private final LiveData<PagedList<TrackHistoryEntry>> stationHistoryPaged =
            Transformations.switchMap(stationUuidLiveData,
                    new Function<String, LiveData<PagedList<TrackHistoryEntry>>>() {
                        @Override
                        public LiveData<PagedList<TrackHistoryEntry>> apply(@Nullable String stationUuid) {
                            return repository.getStationHistoryPaged(stationUuid);
                        }
                    });

    public TrackHistoryViewModel(Application application) {
        super(application);

        RadioDroidApp radioDroidApp = getApplication();
        repository = radioDroidApp.getTrackHistoryRepository();
    }

    public LiveData<PagedList<TrackHistoryEntry>> getAllHistoryPaged() {
        return repository.getAllHistoryPaged();
    }

    public LiveData<PagedList<TrackHistoryEntry>> getStationHistoryPaged() {
        return stationHistoryPaged;
    }

    public void setStationUuid(String stationUuid) {
        stationUuidLiveData.setValue(stationUuid);
    }
}
