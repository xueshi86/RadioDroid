package net.programmierecke.radiodroid2.station;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;

import android.text.TextUtils;
import android.util.Log;
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
import android.widget.TextView;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.service.PlayerServiceUtil;
import net.programmierecke.radiodroid2.ui.StationPlaceholderUtils;
import net.programmierecke.radiodroid2.utils.RecyclerItemMoveAndSwipeHelper;
import net.programmierecke.radiodroid2.utils.SwipeableViewHolder;

public class ItemAdapterIconOnlyStation extends ItemAdapaterContextMenuStation implements RecyclerItemMoveAndSwipeHelper.MoveAndSwipeCallback<ItemAdapterStation.StationViewHolder> {

    class StationViewHolder extends ItemAdapterStation.StationViewHolder implements View.OnClickListener, View.OnCreateContextMenuListener, SwipeableViewHolder {
        PopupMenu contextMenu = null;
        boolean suppressContextMenu = false;
        // 记录布局中声明的默认标题颜色，退出“正在播放”状态时还原（否则列表复用会残留强调色）
        final ColorStateList defaultTitleColors;

        StationViewHolder(View itemView) {
            super(itemView);

            viewForeground = itemView.findViewById(R.id.station_icon_foreground);
            frameLayout = itemView.findViewById(R.id.stationIconFrameLayout);

            imageViewIcon = itemView.findViewById(R.id.iconImageViewIcon);
            transparentImageView = itemView.findViewById(R.id.iconTransparentCircle);
            playingOverlay = itemView.findViewById(R.id.playingOverlay);
            textViewTitle = itemView.findViewById(R.id.textViewStationName);
            defaultTitleColors = textViewTitle.getTextColors();
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
            // 拖拽排序进行中不弹上下文菜单（长按同时触发拖拽与菜单，菜单会打断拖拽）
            if (suppressContextMenu || contextMenu != null)
                return;
            int pos = getAdapterPosition();
            DataRadioStation station = filteredStationsList.get(pos);
            contextMenu = StationPopupMenu.INSTANCE.open(v, getContext(), activity, station);
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
            PlayerServiceUtil.getStationIcon(holder.imageViewIcon, station.IconUrl, station.HomePageUrl, station.StationUuid, station.Name);
        } else if (!TextUtils.isEmpty(station.HomePageUrl)) {
            setupIcon(useCircularIcons, holder.imageViewIcon, holder.transparentImageView);
            PlayerServiceUtil.getStationIcon(holder.imageViewIcon, null, station.HomePageUrl, station.StationUuid, station.Name);
        } else {
            // 视图复用：同步 tag，避免图标缓存写入后按上一轮的电台刷新到错误的图片
            holder.imageViewIcon.setTag(R.id.tag_station_uuid, station.StationUuid);
            holder.imageViewIcon.setImageDrawable(StationPlaceholderUtils.createPlaceholderDrawable(getContext(), station.Name, station.StationUuid));
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
            // “正在播放”高亮色跟随预设配色（强调色）
            int highlightColor = Utils.getThemeColor(getContext(), R.attr.presetAccentColor);
            holder.frameLayout.setBackground(buildPlayingBorderDrawable(highlightColor));
            int overlayColor = Color.argb(50, Color.red(highlightColor), Color.green(highlightColor), Color.blue(highlightColor));
            holder.playingOverlay.setBackgroundColor(overlayColor);
            holder.playingOverlay.setVisibility(View.VISIBLE);
            holder.textViewTitle.setTextColor(highlightColor);
            holder.textViewTitle.setTypeface(null, Typeface.BOLD);
        } else {
            holder.frameLayout.setBackground(null);
            holder.playingOverlay.setVisibility(View.GONE);
            // 还原默认标题颜色，避免 ViewHolder 复用后残留上一首的强调色
            holder.textViewTitle.setTextColor(((StationViewHolder) holder).defaultTitleColors);
            holder.textViewTitle.setTypeface(null, Typeface.NORMAL);
        }

        holder.textViewTitle.setText(station.Name);
    }

    public void enableItemMove(RecyclerView recyclerView) {
        moveAndSwipeHelper = new RecyclerItemMoveAndSwipeHelper<>(getContext(), ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0, this);
        new ItemTouchHelper(moveAndSwipeHelper).attachToRecyclerView(recyclerView);
    }

    @Override
    public void onDragStarted(ItemAdapterStation.StationViewHolder viewHolder) {
        // 拖拽开始时抑制长按上下文菜单（PopupMenu 会接管触摸事件并终止拖拽）
        if (viewHolder instanceof StationViewHolder) {
            StationViewHolder holder = (StationViewHolder) viewHolder;
            holder.suppressContextMenu = true;
            holder.dismissContextMenu();
        }
    }

    @Override
    public void onMoveEnded(ItemAdapterStation.StationViewHolder viewHolder) {
        super.onMoveEnded(viewHolder);
        if (viewHolder instanceof StationViewHolder) {
            ((StationViewHolder) viewHolder).suppressContextMenu = false;
        }
    }
}

