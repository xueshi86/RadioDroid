package net.programmierecke.radiodroid2.station;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.annotation.SuppressLint;
import android.text.TextUtils;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.fragment.app.FragmentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ItemTouchHelper;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.*;

import com.google.android.material.card.MaterialCardView;
import com.mikepenz.iconics.IconicsDrawable;
import com.mikepenz.iconics.IconicsSize;
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial;
import com.mikepenz.iconics.typeface.library.googlematerial.GoogleMaterial;
import com.mikepenz.iconics.view.IconicsImageButton;

import net.programmierecke.radiodroid2.*;
import net.programmierecke.radiodroid2.interfaces.IAdapterRefreshable;
import net.programmierecke.radiodroid2.players.PlayStationTask;
import net.programmierecke.radiodroid2.players.selector.PlayerType;
import net.programmierecke.radiodroid2.utils.RecyclerItemMoveAndSwipeHelper;
import net.programmierecke.radiodroid2.service.PlayerService;
import net.programmierecke.radiodroid2.service.PlayerServiceUtil;
import net.programmierecke.radiodroid2.ui.EqualizerActivity;
import net.programmierecke.radiodroid2.utils.RecyclerItemSwipeHelper;
import net.programmierecke.radiodroid2.utils.SwipeableViewHolder;
import net.programmierecke.radiodroid2.ui.StationPlaceholderUtils;
import net.programmierecke.radiodroid2.views.TagsView;

public class ItemAdapterStation
        extends RecyclerView.Adapter<ItemAdapterStation.StationViewHolder>
        implements RecyclerItemMoveAndSwipeHelper.MoveAndSwipeCallback<ItemAdapterStation.StationViewHolder> {

    public interface StationActionsListener {
        void onStationClick(DataRadioStation station, int pos);

        void onStationMoved(int from, int to);

        void onStationSwiped(DataRadioStation station);

        void onStationMoveFinished();
    }

    public interface FilterListener {
        void onSearchCompleted(StationsFilter.SearchStatus searchStatus);
    }

    private final String TAG = "AdapterStations";

    List<DataRadioStation> stationsList;
    List<DataRadioStation> filteredStationsList = new ArrayList<>();

    int resourceId;

    StationActionsListener stationActionsListener;
    private FilterListener filterListener;
    private boolean supportsStationRemoval = false;
    protected RecyclerItemMoveAndSwipeHelper<StationViewHolder> moveAndSwipeHelper;

    private boolean shouldLoadIcons;

    private IAdapterRefreshable refreshable;
    FragmentActivity activity;

    private BroadcastReceiver updateUIReceiver;

    private int expandedPosition = -1;
    public int playingStationPosition = -1;

    private FavouriteManager favouriteManager;

    private RecyclerView snackbarRecyclerView;

    private StationsFilter filter;

    private TagsView.TagSelectionCallback tagSelectionCallback = new TagsView.TagSelectionCallback() {
        @Override
        public void onTagSelected(String tag) {
            Intent i = new Intent(getContext(), ActivityMain.class);
            i.putExtra(ActivityMain.EXTRA_SEARCH_TAG, tag);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        }
    };

    class StationViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener, SwipeableViewHolder {
        View viewForeground;
        LinearLayout layoutMain;
        FrameLayout frameLayout;

        ImageView imageViewIcon;
        ImageView transparentImageView;
        ImageView starredStatusIcon;
        ImageView playingOverlay;
        TextView textViewTitle;
        TextView textViewShortDescription;
        TextView textViewTags;
        ImageButton buttonMore;

        View viewDetails;
        ViewStub stubDetails;
        IconicsImageButton buttonVisitWebsite;
        ImageButton buttonBookmark;
        ImageButton buttonShare;
        ImageView imageTrend;
        ImageButton buttonAddAlarm;
        TagsView viewTags;
        ImageButton buttonBufferSettings;
        ImageButton buttonEqualizerSettings;
        ImageButton buttonPlayInternalOrExternal;
        ImageButton buttonRefreshIcon;

        StationViewHolder(View itemView) {
            super(itemView);

            viewForeground = itemView.findViewById(R.id.station_foreground);
            layoutMain = itemView.findViewById(R.id.layoutMain);
            frameLayout = itemView.findViewById(R.id.frameLayout);

            imageViewIcon = itemView.findViewById(R.id.imageViewIcon);
            imageTrend = itemView.findViewById(R.id.trendStatusIcon);
            transparentImageView = itemView.findViewById(R.id.transparentCircle);
            starredStatusIcon = itemView.findViewById(R.id.starredStatusIcon);
            playingOverlay = itemView.findViewById(R.id.playingOverlay);
            textViewTitle = itemView.findViewById(R.id.textViewTitle);
            textViewShortDescription = itemView.findViewById(R.id.textViewShortDescription);
            textViewTags = itemView.findViewById(R.id.textViewTags);
            buttonMore = itemView.findViewById(R.id.buttonMore);
            stubDetails = itemView.findViewById(R.id.stubDetails);

            itemView.setOnClickListener(this);
        }

        @Override
        public void onClick(View view) {
            if (stationActionsListener != null) {
                int pos = getAdapterPosition();
                stationActionsListener.onStationClick(filteredStationsList.get(pos), pos);
            }
        }

        @Override
        public View getForegroundView() {
            return viewForeground;
        }
    }

    public ItemAdapterStation(FragmentActivity fragmentActivity, int resourceId) {
        this.activity = fragmentActivity;
        this.resourceId = resourceId;

        RadioDroidApp radioDroidApp = (RadioDroidApp) fragmentActivity.getApplication();
        favouriteManager = radioDroidApp.getFavouriteManager();
        IntentFilter filter = new IntentFilter();
        filter.addAction(PlayerService.PLAYER_SERVICE_META_UPDATE);
        filter.addAction(DataRadioStation.RADIO_STATION_LOCAL_INFO_CHAGED);

        this.updateUIReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }

                switch (intent.getAction()) {
                    case PlayerService.PLAYER_SERVICE_META_UPDATE:
                        highlightCurrentStation();
                        break;
                    case DataRadioStation.RADIO_STATION_LOCAL_INFO_CHAGED:
                        String uuid = intent.getStringExtra(DataRadioStation.RADIO_STATION_UUID);
                        notifyChangedByStationUuid(uuid);
                        break;
                }

            }
        };

        LocalBroadcastManager.getInstance(getContext()).registerReceiver(this.updateUIReceiver, filter);
    }

    public void setStationActionsListener(StationActionsListener stationActionsListener) {
        this.stationActionsListener = stationActionsListener;
    }

    public void setFilterListener(FilterListener filterListener) {
        this.filterListener = filterListener;
    }

    public void enableItemRemoval(RecyclerView recyclerView) {
        if (!supportsStationRemoval) {
            supportsStationRemoval = true;
            this.snackbarRecyclerView = recyclerView;

            RecyclerItemSwipeHelper<StationViewHolder> swipeHelper = new RecyclerItemSwipeHelper<>(getContext(), 0, ItemTouchHelper.LEFT + ItemTouchHelper.RIGHT, this);
            new ItemTouchHelper(swipeHelper).attachToRecyclerView(recyclerView);
        }
    }

    public void enableItemMoveAndRemoval(RecyclerView recyclerView) {
        if (!supportsStationRemoval) {
            supportsStationRemoval = true;
            this.snackbarRecyclerView = recyclerView;

            moveAndSwipeHelper = new RecyclerItemMoveAndSwipeHelper<>(getContext(), ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, this);
            new ItemTouchHelper(moveAndSwipeHelper).attachToRecyclerView(recyclerView);
        }
    }

    /**
     * 返回当前正在显示的电台列表（可能是排序副本），供拖拽时固化显示顺序。
     */
    public List<DataRadioStation> getDisplayedStations() {
        return filteredStationsList;
    }

    public void updateList(FragmentStarred refreshableList, List<DataRadioStation> stationsList) {
        this.refreshable = refreshableList;
        
        // 使用DiffUtil来优化列表更新，减少不必要的刷新
        if (this.stationsList == null) {
            this.stationsList = stationsList;
            this.filteredStationsList = stationsList;
            notifyStationsChanged();
        } else {
            // 如果列表大小差异很大，直接更新
            if (Math.abs(this.stationsList.size() - stationsList.size()) > 50) {
                this.stationsList = stationsList;
                this.filteredStationsList = stationsList;
                notifyStationsChanged();
            } else {
                // 对于小的变化，使用更高效的更新方式
                this.stationsList = stationsList;
                this.filteredStationsList = stationsList;
                highlightCurrentStation();
                notifyDataSetChanged();
            }
        }
    }

    private void notifyStationsChanged() {
        expandedPosition = -1;
        playingStationPosition = -1;

        shouldLoadIcons = Utils.shouldLoadIcons(getContext());

        highlightCurrentStation();

        notifyDataSetChanged();
    }

    @Override
    public StationViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View v = inflater.inflate(resourceId, parent, false);

        return new StationViewHolder(v);
    }

    @SuppressLint("NewApi")
    private boolean isActivityUsable() {
        return activity != null && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN
                || !activity.isDestroyed());
    }

    @Override
    public void onBindViewHolder(final StationViewHolder holder, int position) {
        if (filteredStationsList == null || position < 0 || position >= filteredStationsList.size()) {
            return;
        }
        final DataRadioStation station = filteredStationsList.get(position);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext().getApplicationContext());
        boolean useCircularIcons = Utils.useCircularIcons(getContext());
        // 列表项根节点已是卡片：直接对 itemView 设背景色会抹掉圆角与卡片面色，改为设置卡片背景色。
        // 失效/已删除的标记底色统一走主题属性：亮色主题浅底深字，暗色主题深底浅字，避免文字与底色撞色。
        if (holder.itemView instanceof MaterialCardView) {
            MaterialCardView card = (MaterialCardView) holder.itemView;
            if (station.DeletedOnServer) {
                // 已被服务器删除
                card.setCardBackgroundColor(Utils.getThemeColor(getContext(), R.attr.colorStationDeleted));
            } else if (!station.Working) {
                // 当前不可用
                card.setCardBackgroundColor(Utils.getThemeColor(getContext(), R.attr.colorStationUnavailable));
            } else {
                card.setCardBackgroundColor(Utils.getThemeColor(getContext(), R.attr.colorSurfaceCard));
            }
        } else if (station.DeletedOnServer) {
            holder.itemView.setBackgroundColor(Utils.getThemeColor(getContext(), R.attr.colorStationDeleted));
        } else if (!station.Working) {
            holder.itemView.setBackgroundColor(Utils.getThemeColor(getContext(), R.attr.colorStationUnavailable));
        } else {
            holder.itemView.setBackgroundColor(0x00000000);
        }

        if (!shouldLoadIcons) {
            holder.imageViewIcon.setVisibility(View.GONE);
        } else {
            // 视图复用时可能残留上一轮的隐藏状态，需显式恢复显示
            holder.imageViewIcon.setVisibility(View.VISIBLE);
            if (station.hasIcon()) {
                setupIcon(useCircularIcons, holder.imageViewIcon, holder.transparentImageView);
                PlayerServiceUtil.getStationIcon(holder.imageViewIcon, station.IconUrl, station.HomePageUrl, station.StationUuid, station.Name);
            } else if (!TextUtils.isEmpty(station.HomePageUrl)) {
                setupIcon(useCircularIcons, holder.imageViewIcon, holder.transparentImageView);
                PlayerServiceUtil.getStationIcon(holder.imageViewIcon, null, station.HomePageUrl, station.StationUuid, station.Name);
            } else {
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

            // 两种模式下的行高、图标尺寸与详情行可见性都必须显式设定：
            // 它们会被写进 ViewHolder 的 LayoutParams，仅设置紧凑模式的话，
            // 复用到的（或模式切换前已绑定的）条目会残留紧凑尺寸，外观与大图标模式几乎无差别
            if (prefs.getBoolean("compact_style", false))
                setupCompactStyle(holder);
            else
                setupStandardStyle(holder);

            if (prefs.getBoolean("icon_click_toggles_favorite", true)) {

                final boolean isInFavorites = favouriteManager.has(station.StationUuid);
                holder.imageViewIcon.setContentDescription(getContext().getApplicationContext().getString(isInFavorites ? R.string.detail_unstar : R.string.detail_star));
                holder.imageViewIcon.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (favouriteManager.has(station.StationUuid)) {
                            StationActions.removeFromFavourites(getContext(), view, snackbarRecyclerView, station);
                        } else {
                            StationActions.markAsFavourite(getContext(), station);
                        }

                        int position = holder.getAdapterPosition();
                        notifyItemChanged(position);
                    }
                });
            }
        }

        final boolean isExpanded = position == expandedPosition;
        holder.textViewTags.setVisibility(isExpanded ? View.GONE : View.VISIBLE);

        holder.buttonMore.setImageResource(isExpanded ? R.drawable.ic_expand_less_black_24dp : R.drawable.ic_expand_more_black_24dp);
        holder.buttonMore.setContentDescription(getContext().getApplicationContext().getString(isExpanded ? R.string.image_button_less : R.string.image_button_more));
        holder.buttonMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // Notify prev item change
                if (expandedPosition != -1) {
                    notifyItemChanged(expandedPosition);
                }

                int position = holder.getAdapterPosition();
                expandedPosition = isExpanded ? -1 : position;

                // Notify current item changed
                if (expandedPosition != -1) {
                    notifyItemChanged(expandedPosition);
                }
            }
        });

        // 确保电台名称可见
        holder.textViewTitle.setText(station.Name != null ? station.Name : activity.getString(R.string.unknown_station));
        
        // 文字颜色统一走主题角色，不再按亮/暗主题硬编码
        // “正在播放”高亮色跟随预设配色（强调色）
        int highlightColor = Utils.getThemeColor(getContext(), R.attr.presetAccentColor);

        if (playingStationPosition == position) {
            holder.textViewTitle.setTextColor(highlightColor);
            holder.textViewTitle.setTypeface(null, Typeface.BOLD);
            holder.frameLayout.setBackground(buildPlayingBorderDrawable(highlightColor));
            int overlayColor = Color.argb(50, Color.red(highlightColor), Color.green(highlightColor), Color.blue(highlightColor));
            holder.playingOverlay.setBackgroundColor(overlayColor);
            holder.playingOverlay.setVisibility(View.VISIBLE);
        } else {
            // 还原主题主文字色，避免 ViewHolder 复用残留上一首的强调色
            holder.textViewTitle.setTextColor(Utils.getThemeColor(getContext(), R.attr.colorTextPrimary));
            holder.textViewTitle.setTypeface(null, Typeface.NORMAL);
            holder.frameLayout.setBackground(null);
            holder.playingOverlay.setVisibility(View.GONE);
        }

        holder.textViewShortDescription.setText(station.getShortDetails(getContext()));
        holder.textViewTags.setText(station.TagsAll != null ? station.TagsAll.replace(",", ", ") : "");


        boolean inFavourites = favouriteManager.has(station.StationUuid);
        holder.starredStatusIcon.setVisibility(inFavourites ? View.VISIBLE : View.GONE);
        holder.starredStatusIcon.setContentDescription(inFavourites ? getContext().getString(R.string.action_favorite) : "");

        if (prefs.getBoolean("click_trend_icon_visible", true)) {
            if (station.ClickTrend < 0) {
                holder.imageTrend.setImageResource(R.drawable.ic_trending_down_black_24dp);
                holder.imageTrend.setContentDescription(getContext().getString(R.string.icon_click_trend_decreasing));
            } else if (station.ClickTrend > 0) {
                holder.imageTrend.setImageResource(R.drawable.ic_trending_up_black_24dp);
                holder.imageTrend.setContentDescription(getContext().getString(R.string.icon_click_trend_increasing));
            } else {
                holder.imageTrend.setImageResource(R.drawable.ic_trending_flat_black_24dp);
                holder.imageTrend.setContentDescription(getContext().getString(R.string.icon_click_trend_stable));
            }
        } else {
            holder.imageTrend.setVisibility(View.GONE);
        }

        Drawable flag = CountryFlagsLoader.getInstance().getFlag(activity, station.CountryCode);

        if (flag != null && flag.getMinimumHeight() > 0) {
            float k = flag.getMinimumWidth() / (float) flag.getMinimumHeight();
            float viewHeight = holder.textViewShortDescription.getTextSize();
            flag.setBounds(0, 0, (int) (k * viewHeight), (int) viewHeight);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            holder.textViewShortDescription.setCompoundDrawablesRelative(flag, null, null, null);
        } else {
            holder.textViewShortDescription.setCompoundDrawables(flag, null, null, null);
        }

        if (isExpanded) {
            holder.viewDetails = holder.stubDetails == null ? holder.viewDetails : holder.stubDetails.inflate();
            holder.stubDetails = null;
            holder.viewTags = (TagsView) holder.viewDetails.findViewById(R.id.viewTags);
            holder.buttonVisitWebsite = holder.viewDetails.findViewById(R.id.buttonVisitWebsite);
            holder.buttonShare = holder.viewDetails.findViewById(R.id.buttonShare);
            holder.buttonBookmark = holder.viewDetails.findViewById(R.id.buttonBookmark);
            holder.buttonAddAlarm = holder.viewDetails.findViewById(R.id.buttonAddAlarm);
            holder.buttonBufferSettings = holder.viewDetails.findViewById(R.id.buttonBufferSettings);
            holder.buttonEqualizerSettings = holder.viewDetails.findViewById(R.id.buttonEqualizerSettings);
            holder.buttonPlayInternalOrExternal = holder.viewDetails.findViewById(R.id.buttonPlayInRadioDroid);
            holder.buttonRefreshIcon = holder.viewDetails.findViewById(R.id.buttonRefreshIcon);

            holder.buttonVisitWebsite.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (isActivityUsable()) {
                        StationActions.openStationHomeUrl(activity, station);
                    }
                }
            });

            holder.buttonShare.setOnClickListener(view -> {
                if (isActivityUsable()) {
                    StationActions.share(activity, station);
                }
            });

            if (favouriteManager.has(station.StationUuid)) {
                // favorite stations should only be removed in the favorites view
                holder.buttonBookmark.setVisibility(View.GONE);
            } else {
                // 视图复用时可能残留上一轮的隐藏状态，需显式恢复显示
                holder.buttonBookmark.setVisibility(View.VISIBLE);
                holder.buttonBookmark.setOnClickListener(view -> {
                    StationActions.markAsFavourite(getContext(), station);
                    int position1 = holder.getAdapterPosition();
                    notifyItemChanged(position1);
                });
            }

            holder.buttonBufferSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (isActivityUsable()) {
                        BufferSettingsDialog dialog = BufferSettingsDialog.newInstance(station.StationUuid, station.Name);
                        dialog.show(activity.getSupportFragmentManager(), "buffer_settings_dialog");
                    }
                }
            });

            holder.buttonEqualizerSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (isActivityUsable()) {
                        Intent intent = new Intent(activity, net.programmierecke.radiodroid2.ui.EqualizerActivity.class);
                        intent.putExtra(EqualizerActivity.EXTRA_STATION_UUID, station.StationUuid);
                        intent.putExtra(EqualizerActivity.EXTRA_STATION_NAME, station.Name);
                        activity.startActivity(intent);
                    }
                }
            });
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                holder.buttonEqualizerSettings.setVisibility(View.GONE);
            }

            holder.buttonAddAlarm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (isActivityUsable()) {
                        StationActions.setAsAlarm(activity, station);
                    }
                }
            });

            if (prefs.getBoolean("play_external", false)) {
                holder.buttonPlayInternalOrExternal.setOnClickListener(v -> {
                    StationActions.playInRadioDroid(getContext(), station);
                });
            } else {
                Context context = getContext();
                holder.buttonPlayInternalOrExternal.setContentDescription(getContext().getString(R.string.detail_play_in_external_player));
                holder.buttonPlayInternalOrExternal.setImageDrawable(new IconicsDrawable(getContext(), CommunityMaterial.Icon2.cmd_play_box_outline).size(IconicsSize.dp(24)));
                holder.buttonPlayInternalOrExternal.setOnClickListener(v -> PlayStationTask.playExternal(station, context).execute());
            }

            holder.buttonRefreshIcon.setOnClickListener(v -> {
                PlayerServiceUtil.forceRefreshStationIcon(station, holder.imageViewIcon);
            });
            String[] tags = station.TagsAll == null || station.TagsAll.isEmpty() ? new String[0] : station.TagsAll.split(",");
            holder.viewTags.setTags(Arrays.asList(tags));
            holder.viewTags.setTagSelectionCallback(tagSelectionCallback);
        }
        if (holder.viewDetails != null)
            holder.viewDetails.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
    }

    @Override
    public int getItemCount() {
        if (filteredStationsList != null) {
            return filteredStationsList.size();
        }
        return 0;
    }

    @Override
    public void onSwiped(StationViewHolder viewHolder, int direction) {
        stationActionsListener.onStationSwiped(filteredStationsList.get(viewHolder.getAdapterPosition()));
    }

    @Override
    public void onDragged(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, double dX, double dY) {

    }

    @Override
    public void onMoved(StationViewHolder viewHolder, int from, int to) {
        stationActionsListener.onStationMoved(from, to);
        notifyItemMoved(from, to);
    }

    @Override
    public void onMoveEnded(StationViewHolder viewHolder) {
        stationActionsListener.onStationMoveFinished();
    }

    @Override
    public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
        LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(updateUIReceiver);
    }

    public StationsFilter getFilter() {
        if (filter == null) {
            filter = new StationsFilter(getContext(), new StationsFilter.DataProvider() {
                @Override
                public List<DataRadioStation> getOriginalStationList() {
                    return stationsList;
                }

                @Override
                public void notifyFilteredStationsChanged(StationsFilter.SearchStatus status, List<DataRadioStation> filteredStations) {
                    filteredStationsList = filteredStations;

                    notifyStationsChanged();

                    if (filterListener != null) {
                        filterListener.onSearchCompleted(status);
                    }
                }
            });
        }

        return filter;
    }

    Context getContext() {
        return activity;
    }

    void setupIcon(boolean useCircularIcons, ImageView imageView, ImageView transparentImageView) {
        if (useCircularIcons) {
            transparentImageView.setVisibility(View.VISIBLE);
            imageView.getLayoutParams().height = imageView.getLayoutParams().height = imageView.getLayoutParams().width;
            if (Utils.isDarkTheme(getContext())) {
                imageView.setBackgroundColor(getContext().getResources().getColor(R.color.windowBackgroundDark));
            } else {
                imageView.setBackgroundColor(getContext().getResources().getColor(android.R.color.white));
            }
        } else {
            // 非圆形图标模式下必须隐藏圆形遮罩，否则视图复用时可能残留上一个圆形绑定的遮罩
            transparentImageView.setVisibility(View.GONE);
            if (Utils.isDarkTheme(getContext())) {
                imageView.setBackgroundColor(getContext().getResources().getColor(R.color.windowBackgroundDark));
            } else {
                imageView.setBackgroundColor(getContext().getResources().getColor(android.R.color.white));
            }
        }
    }

    /**
     * 构建“正在播放”高亮方框。描边宽度用 dp 换算（此前直接传 px，导致高密度屏幕上描边过细）。
     */
    GradientDrawable buildPlayingBorderDrawable(int color) {
        float density = getContext().getResources().getDisplayMetrics().density;
        GradientDrawable borderDrawable = new GradientDrawable();
        borderDrawable.setShape(GradientDrawable.RECTANGLE);
        borderDrawable.setCornerRadius(8 * density);
        borderDrawable.setStroke(Math.max(1, Math.round(3 * density)), color);
        borderDrawable.setColor(Color.TRANSPARENT);
        return borderDrawable;
    }

    private void setupCompactStyle(final StationViewHolder holder) {
        int containerSize = (int) getContext().getResources().getDimension(R.dimen.compact_style_icon_container_width);
        int iconSize = (int) getContext().getResources().getDimension(R.dimen.compact_style_icon_width);

        holder.layoutMain.setMinimumHeight((int) getContext().getResources().getDimension(R.dimen.compact_style_item_minimum_height));
        // 图标容器必须宽高相等，否则“正在播放”高亮方框会出现缺边
        holder.frameLayout.getLayoutParams().width = containerSize;
        holder.frameLayout.getLayoutParams().height = containerSize;
        holder.imageViewIcon.getLayoutParams().width = iconSize;
        holder.imageViewIcon.getLayoutParams().height = iconSize;
        // 高亮遮罩与圆形遮罩需与图标同尺寸，保证方框紧贴图标
        holder.playingOverlay.getLayoutParams().width = iconSize;
        holder.playingOverlay.getLayoutParams().height = iconSize;
        holder.transparentImageView.getLayoutParams().width = (int) getContext().getResources().getDimension(R.dimen.compact_style_icon_width);
        holder.transparentImageView.getLayoutParams().height = (int) getContext().getResources().getDimension(R.dimen.compact_style_icon_height);

        holder.textViewShortDescription.setVisibility(View.GONE);
    }

    /**
     * 大图标模式（未勾选「紧凑模式」）：大图标 + 行高按布局默认值，并显示电台详情行。
     * 与 {@link #setupCompactStyle} 成对，保证两种模式相互切换、条目复用时外观一致。
     */
    private void setupStandardStyle(final StationViewHolder holder) {
        int iconSize = (int) getContext().getResources().getDimension(R.dimen.icon_station_list);

        holder.layoutMain.setMinimumHeight((int) getContext().getResources().getDimension(R.dimen.item_height_standard));
        holder.frameLayout.getLayoutParams().width = iconSize;
        holder.frameLayout.getLayoutParams().height = iconSize;
        holder.imageViewIcon.getLayoutParams().width = iconSize;
        holder.imageViewIcon.getLayoutParams().height = iconSize;
        holder.playingOverlay.getLayoutParams().width = iconSize;
        holder.playingOverlay.getLayoutParams().height = iconSize;
        holder.transparentImageView.getLayoutParams().width = iconSize;
        holder.transparentImageView.getLayoutParams().height = iconSize;

        holder.textViewShortDescription.setVisibility(View.VISIBLE);
    }

    private void highlightCurrentStation() {
        if (!PlayerServiceUtil.isPlaying()) {
            if (playingStationPosition != -1) {
                int oldPosition = playingStationPosition;
                playingStationPosition = -1;
                if (oldPosition > -1)
                    notifyItemChanged(oldPosition);
            }
            return;
        }
        if (filteredStationsList == null) return;

        int oldPlayingStationPosition = playingStationPosition;
        playingStationPosition = -1;

        String currentStationUuid = PlayerServiceUtil.getStationId();
        for (int i = 0; i < filteredStationsList.size(); i++) {
            if (filteredStationsList.get(i).StationUuid.equals(currentStationUuid)) {
                playingStationPosition = i;
                break;
            }
        }
        if (playingStationPosition != oldPlayingStationPosition) {
            if (oldPlayingStationPosition > -1)
                notifyItemChanged(oldPlayingStationPosition);
            if (playingStationPosition > -1)
                notifyItemChanged(playingStationPosition);
        }
    }

    private void notifyChangedByStationUuid(String uuid) {
        // TODO: Iterate through view holders instead of whole collection
        for (int i = 0; i < filteredStationsList.size(); i++) {
            if (filteredStationsList.get(i).StationUuid.equals(uuid)) {
                notifyItemChanged(i);
                break;
            }
        }
    }
}
