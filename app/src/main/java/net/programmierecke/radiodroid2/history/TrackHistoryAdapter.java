package net.programmierecke.radiodroid2.history;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.fragment.app.FragmentActivity;
import androidx.paging.PagedList;
import androidx.paging.PagedListAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.database.RadioDroidDatabase;
import net.programmierecke.radiodroid2.database.RadioStation;
import net.programmierecke.radiodroid2.service.PlayerServiceUtil;

import java.util.HashMap;
import java.util.Map;

public class TrackHistoryAdapter extends PagedListAdapter<TrackHistoryEntry, TrackHistoryAdapter.TrackHistoryItemViewHolder> {
    class TrackHistoryItemViewHolder extends RecyclerView.ViewHolder {
        final View rootview;

        final ImageView imageViewStationIcon;
        final TextView textViewTrackName;
        final TextView textViewTrackArtist;

        private TrackHistoryItemViewHolder(View itemView) {
            super(itemView);

            rootview = itemView;

            imageViewStationIcon = itemView.findViewById(R.id.imageViewStationIcon);
            textViewTrackName = itemView.findViewById(R.id.textViewTrackName);
            textViewTrackArtist = itemView.findViewById(R.id.textViewTrackArtist);
        }
    }

    private Context context;
    private FragmentActivity activity;
    private final LayoutInflater inflater;
    private boolean shouldLoadIcons;
    private Drawable stationImagePlaceholder;
    private RadioDroidDatabase database;
    private final Map<String, String> homePageUrlCache = new HashMap<>();

    public TrackHistoryAdapter(FragmentActivity activity) {
        super(DIFF_CALLBACK);
        this.activity = activity;
        this.context = activity;
        inflater = LayoutInflater.from(context);

        stationImagePlaceholder = AppCompatResources.getDrawable(context, R.mipmap.ic_launcher);
        database = RadioDroidDatabase.getDatabase(activity.getApplication());
    }

    @NonNull
    @Override
    public TrackHistoryItemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = inflater.inflate(R.layout.list_item_history_track_item, parent, false);
        return new TrackHistoryItemViewHolder(itemView);
    }


    @Override
    public void onBindViewHolder(@NonNull final TrackHistoryItemViewHolder holder, int position) {
        final TrackHistoryEntry historyEntry = getItem(position);

        if (historyEntry == null) {
            return;
        }

        if (shouldLoadIcons) {
            String iconUrl = historyEntry.stationIconUrl;
            String homePageUrl = homePageUrlCache.get(historyEntry.stationUuid);

            if (!TextUtils.isEmpty(iconUrl) || !TextUtils.isEmpty(homePageUrl)) {
                PlayerServiceUtil.getStationIcon(holder.imageViewStationIcon, iconUrl, homePageUrl);
            } else {
                holder.imageViewStationIcon.setImageDrawable(stationImagePlaceholder);
            }
        } else {
            holder.imageViewStationIcon.setVisibility(View.GONE);
        }

        String trackName = historyEntry.track;
        String artistName = historyEntry.artist;
        
        if ("Unknown Track".equals(trackName) || "未知".equals(trackName)) {
            trackName = context.getString(R.string.unknown_track);
        }
        if ("Unknown Artist".equals(artistName) || "未知".equals(artistName)) {
            artistName = context.getString(R.string.unknown_artist);
        }
        
        holder.textViewTrackName.setText(trackName);
        holder.textViewTrackArtist.setText(artistName);

        holder.textViewTrackName.setSelected(true);
        holder.textViewTrackArtist.setSelected(true);

        holder.rootview.setOnClickListener(view -> showTrackInfoDialog(historyEntry));
    }

    @Override
    public void submitList(PagedList<TrackHistoryEntry> pagedList) {
        shouldLoadIcons = Utils.shouldLoadIcons(context);
        super.submitList(pagedList);
        preloadHomePageUrls(pagedList);
    }

    private void preloadHomePageUrls(final PagedList<TrackHistoryEntry> pagedList) {
        if (pagedList == null || pagedList.size() == 0) {
            return;
        }
        database.getQueryExecutor().execute(() -> {
            boolean changed = false;
            for (int i = 0; i < pagedList.size(); i++) {
                TrackHistoryEntry entry = pagedList.get(i);
                if (entry != null && !TextUtils.isEmpty(entry.stationUuid) && !homePageUrlCache.containsKey(entry.stationUuid)) {
                    try {
                        RadioStation station = database.radioStationDao().getStationById(entry.stationUuid);
                        if (station != null && !TextUtils.isEmpty(station.homepage)) {
                            homePageUrlCache.put(entry.stationUuid, station.homepage);
                            changed = true;
                        } else {
                            homePageUrlCache.put(entry.stationUuid, "");
                        }
                    } catch (Exception e) {
                        homePageUrlCache.put(entry.stationUuid, "");
                    }
                }
            }
            if (changed && activity != null && !activity.isFinishing()) {
                activity.runOnUiThread(() -> notifyDataSetChanged());
            }
        });
    }

    private void showTrackInfoDialog(final TrackHistoryEntry historyEntry) {
        TrackHistoryInfoDialog trackHistoryInfoDialog = new TrackHistoryInfoDialog(historyEntry);
        trackHistoryInfoDialog.show(activity.getSupportFragmentManager(), TrackHistoryInfoDialog.FRAGMENT_TAG);
    }

    private static DiffUtil.ItemCallback<TrackHistoryEntry> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<TrackHistoryEntry>() {
                @Override
                public boolean areItemsTheSame(TrackHistoryEntry oldEntry, TrackHistoryEntry newEntry) {
                    return oldEntry.uid == newEntry.uid;
                }

                @Override
                public boolean areContentsTheSame(TrackHistoryEntry oldEntry,
                                                  @NonNull TrackHistoryEntry newEntry) {
                    return oldEntry.equals(newEntry);
                }
            };

}
