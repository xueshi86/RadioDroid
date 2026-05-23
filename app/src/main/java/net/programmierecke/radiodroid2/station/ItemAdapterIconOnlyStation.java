package net.programmierecke.radiodroid2.station;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import android.widget.PopupMenu;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.service.PlayerServiceUtil;
import net.programmierecke.radiodroid2.utils.RecyclerItemMoveAndSwipeHelper;
import net.programmierecke.radiodroid2.utils.SwipeableViewHolder;

public class ItemAdapterIconOnlyStation extends ItemAdapaterContextMenuStation implements RecyclerItemMoveAndSwipeHelper.MoveAndSwipeCallback<ItemAdapterStation.StationViewHolder> {

    class StationViewHolder extends ItemAdapterStation.StationViewHolder implements View.OnClickListener, View.OnCreateContextMenuListener, SwipeableViewHolder {
        PopupMenu contextMenu = null;

        StationViewHolder(View itemView) {
            super(itemView);

            viewForeground = itemView.findViewById(R.id.station_icon_foreground);
            frameLayout = itemView.findViewById(R.id.stationIconFrameLayout);

            imageViewIcon = itemView.findViewById(R.id.iconImageViewIcon);
            transparentImageView = itemView.findViewById(R.id.iconTransparentCircle);
            itemView.setOnCreateContextMenuListener(this);
        }

        public void dismissContextMenu() {
            if (contextMenu != null) {
                contextMenu.dismiss();
                contextMenu = null;
            }
        }

        @Override
        public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
            if (contextMenu != null)
                return;
            int pos = getAdapterPosition();
            DataRadioStation station = filteredStationsList.get(pos);
            contextMenu = StationPopupMenu.INSTANCE.open(v, getContext(), activity, station, ItemAdapterIconOnlyStation.this);
        }
    }

    public ItemAdapterIconOnlyStation(FragmentActivity fragmentActivity, int resourceId) {
        super(fragmentActivity, resourceId);
    }

    @NonNull
    @Override
    public StationViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View v = inflater.inflate(resourceId, parent, false);

        return new StationViewHolder(v);
    }

    @Override
    public void onBindViewHolder(final ItemAdapterStation.StationViewHolder holder, int position) {
        final DataRadioStation station = filteredStationsList.get(position);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext().getApplicationContext());
        boolean useCircularIcons = Utils.useCircularIcons(getContext());

        if (station.hasIcon()) {
            setupIcon(useCircularIcons, holder.imageViewIcon, holder.transparentImageView);
            PlayerServiceUtil.getStationIcon(holder.imageViewIcon, station.IconUrl, station.HomePageUrl, station.StationUuid);
        } else if (!TextUtils.isEmpty(station.HomePageUrl)) {
            setupIcon(useCircularIcons, holder.imageViewIcon, holder.transparentImageView);
            PlayerServiceUtil.getStationIcon(holder.imageViewIcon, null, station.HomePageUrl, station.StationUuid);
        } else {
            holder.imageViewIcon.setImageDrawable(stationImagePlaceholder);
            if (Utils.isDarkTheme(getContext())) {
                holder.imageViewIcon.setBackgroundColor(getContext().getResources().getColor(R.color.windowBackgroundDark));
            } else {
                holder.imageViewIcon.setBackgroundColor(getContext().getResources().getColor(android.R.color.white));
            }
            if (useCircularIcons) {
                holder.transparentImageView.setVisibility(View.VISIBLE);
                holder.imageViewIcon.getLayoutParams().height = holder.imageViewIcon.getLayoutParams().width;
            }
        }

        if (playingStationPosition == position) {
            TypedValue tv = new TypedValue();
            getContext().getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true);
            GradientDrawable borderDrawable = new GradientDrawable();
            borderDrawable.setShape(GradientDrawable.RECTANGLE);
            borderDrawable.setCornerRadius(4 * getContext().getResources().getDisplayMetrics().density);
            borderDrawable.setStroke(3, tv.data);
            borderDrawable.setColor(Color.TRANSPARENT);
            holder.frameLayout.setBackground(borderDrawable);
            holder.transparentImageView.setVisibility(View.VISIBLE);
            holder.transparentImageView.setColorFilter(tv.data);
        } else {
            holder.frameLayout.setBackground(null);
            holder.transparentImageView.setVisibility(View.GONE);
        }
    }

    public void enableItemMove(RecyclerView recyclerView) {
        RecyclerItemMoveAndSwipeHelper swipeAndMoveHelper = new RecyclerItemMoveAndSwipeHelper<>(getContext(), ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0, this);
        new ItemTouchHelper(swipeAndMoveHelper).attachToRecyclerView(recyclerView);
    }
}

