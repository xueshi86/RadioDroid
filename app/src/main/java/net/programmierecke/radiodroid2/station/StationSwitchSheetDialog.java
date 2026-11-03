package net.programmierecke.radiodroid2.station;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import net.programmierecke.radiodroid2.ActivityMain;
import net.programmierecke.radiodroid2.HistoryManager;
import net.programmierecke.radiodroid2.FavouriteManager;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.service.PlayerServiceUtil;
import net.programmierecke.radiodroid2.ui.StationPlaceholderUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 换台底部弹窗（全屏播放器控制条「换台」按钮）：
 * 双页签列出「我的收藏」与「最近收听」（历史）电台，点击条目即换台播放；
 * 右上角「显示全部」跳转到应用对应的收藏 / 历史界面。
 * 主题通过 Utils.getBottomSheetDialogThemeResId 跟随应用明暗主题。
 */
public class StationSwitchSheetDialog extends BottomSheetDialogFragment {

    public static final String FRAGMENT_TAG = "station_switch_sheet_dialog";

    private TextView textTabFavorites;
    private TextView textTabHistory;
    private TextView textShowAll;
    private TextView textEmpty;
    private RecyclerView recyclerView;

    private StationSwitchAdapter adapter;
    private boolean showFavorites = true;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, Utils.getBottomSheetDialogThemeResId(requireContext()));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        setRetainInstance(true);

        View view = inflater.inflate(R.layout.dialog_station_switch_sheet, container, false);

        textTabFavorites = view.findViewById(R.id.textTabFavorites);
        textTabHistory = view.findViewById(R.id.textTabHistory);
        textShowAll = view.findViewById(R.id.textShowAll);
        textEmpty = view.findViewById(R.id.textEmptySwitch);
        recyclerView = view.findViewById(R.id.recyclerViewSwitchStations);

        adapter = new StationSwitchAdapter(requireContext(), this::playStation);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        textTabFavorites.setOnClickListener(v -> selectTab(true));
        textTabHistory.setOnClickListener(v -> selectTab(false));

        // 「显示全部」：收起弹窗并跳转到应用对应的收藏 / 历史界面（导航会同时收起播放器）
        textShowAll.setOnClickListener(v -> {
            if (getActivity() instanceof ActivityMain) {
                dismiss();
                ((ActivityMain) getActivity()).openStationList(
                        showFavorites ? R.id.nav_item_starred : R.id.nav_item_history);
            }
        });

        selectTab(true);

        return view;
    }

    private void selectTab(boolean favorites) {
        showFavorites = favorites;

        final Context ctx = requireContext();
        final int accentColor = Utils.getThemeColor(ctx, R.attr.presetAccentColor);
        final int secondaryColor = Utils.getThemeColor(ctx, R.attr.colorTextSecondary);

        textTabFavorites.setTextColor(favorites ? accentColor : secondaryColor);
        textTabHistory.setTextColor(!favorites ? accentColor : secondaryColor);

        final RadioDroidApp app = (RadioDroidApp) requireActivity().getApplication();
        List<DataRadioStation> stations = favorites
                ? app.getFavouriteManager().getList()
                : app.getHistoryManager().getList();

        adapter.submitList(stations == null ? new ArrayList<>() : stations);

        final boolean empty = stations == null || stations.isEmpty();
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        textEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void playStation(DataRadioStation station) {
        if (!isAdded()) {
            return;
        }

        final RadioDroidApp app = (RadioDroidApp) requireActivity().getApplication();

        // 显式绑定来源列表，保证播放服务中「上一曲/下一曲」在对应列表内切换（同 Issue #57 的处理）
        final FavouriteManager fm = app.getFavouriteManager();
        final HistoryManager hm = app.getHistoryManager();
        station.queue = showFavorites ? fm : hm;

        // 换台语义：点击条目直接播放，不弹播放方式确认框
        Utils.play(app, station);
        dismiss();
    }

    private static class StationSwitchAdapter extends RecyclerView.Adapter<StationSwitchAdapter.StationViewHolder> {

        interface OnStationClickListener {
            void onStationClick(DataRadioStation station);
        }

        private final Context context;
        private final LayoutInflater inflater;
        private final OnStationClickListener listener;
        private List<DataRadioStation> stations = new ArrayList<>();

        StationSwitchAdapter(Context context, OnStationClickListener listener) {
            this.context = context;
            this.inflater = LayoutInflater.from(context);
            this.listener = listener;
        }

        void submitList(List<DataRadioStation> list) {
            this.stations = list;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public StationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new StationViewHolder(inflater.inflate(R.layout.list_item_station_switch, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull StationViewHolder holder, int position) {
            final DataRadioStation station = stations.get(position);

            holder.textName.setText(station.Name);
            holder.textName.setSelected(true);

            final String tags = station.TagsAll == null ? "" : station.TagsAll.trim();
            if (tags.isEmpty()) {
                holder.textTags.setVisibility(View.GONE);
            } else {
                holder.textTags.setVisibility(View.VISIBLE);
                holder.textTags.setText(tags);
            }

            if (Utils.shouldLoadIcons(context) && station.hasIcon()) {
                PlayerServiceUtil.getStationIcon(holder.imgIcon, station.IconUrl, station.HomePageUrl, station.StationUuid, station.Name);
            } else {
                holder.imgIcon.setImageDrawable(StationPlaceholderUtils.createPlaceholderDrawable(context, station.Name, station.StationUuid));
            }

            holder.itemView.setOnClickListener(v -> listener.onStationClick(station));
        }

        @Override
        public int getItemCount() {
            return stations.size();
        }

        static class StationViewHolder extends RecyclerView.ViewHolder {
            final android.widget.ImageView imgIcon;
            final TextView textName;
            final TextView textTags;

            StationViewHolder(@NonNull View itemView) {
                super(itemView);
                imgIcon = itemView.findViewById(R.id.imageViewSwitchIcon);
                textName = itemView.findViewById(R.id.textViewSwitchName);
                textTags = itemView.findViewById(R.id.textViewSwitchTags);
            }
        }
    }
}
