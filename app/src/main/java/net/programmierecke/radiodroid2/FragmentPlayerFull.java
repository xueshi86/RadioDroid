package net.programmierecke.radiodroid2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.paging.PagedList;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;
import com.mikepenz.iconics.IconicsColor;
import com.mikepenz.iconics.IconicsSize;
import com.mikepenz.iconics.IconicsDrawable;
import com.mikepenz.iconics.typeface.library.googlematerial.GoogleMaterial;
import com.squareup.picasso.Picasso;

import net.programmierecke.radiodroid2.history.TrackHistoryAdapter;
import net.programmierecke.radiodroid2.history.TrackHistoryEntry;
import net.programmierecke.radiodroid2.history.TrackHistoryInfoDialog;
import net.programmierecke.radiodroid2.history.TrackHistoryRepository;
import net.programmierecke.radiodroid2.history.TrackHistoryViewModel;
import net.programmierecke.radiodroid2.recording.DataRecording;
import net.programmierecke.radiodroid2.recording.Recordable;
import net.programmierecke.radiodroid2.recording.RecordingsAdapter;
import net.programmierecke.radiodroid2.recording.RecordingsManager;
import net.programmierecke.radiodroid2.recording.RunningRecordingInfo;
import net.programmierecke.radiodroid2.service.ConnectivityChecker;
import net.programmierecke.radiodroid2.service.PauseReason;
import net.programmierecke.radiodroid2.service.PlayerService;
import net.programmierecke.radiodroid2.service.PlayerServiceUtil;
import net.programmierecke.radiodroid2.station.DataRadioStation;
import net.programmierecke.radiodroid2.station.StationNoteDialog;
import net.programmierecke.radiodroid2.station.StationNoteManager;
import net.programmierecke.radiodroid2.ui.StationPlaceholderUtils;
import net.programmierecke.radiodroid2.station.StationActions;
import net.programmierecke.radiodroid2.station.live.ShoutcastInfo;
import net.programmierecke.radiodroid2.station.live.StreamLiveInfo;
import net.programmierecke.radiodroid2.station.live.metadata.TrackMetadata;
import net.programmierecke.radiodroid2.station.live.metadata.TrackMetadataCallback;
import net.programmierecke.radiodroid2.station.live.metadata.TrackMetadataSearcher;
import net.programmierecke.radiodroid2.utils.RefreshHandler;
import net.programmierecke.radiodroid2.views.RecyclerAwareNestedScrollView;
import net.programmierecke.radiodroid2.views.TagsView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Observable;

public class FragmentPlayerFull extends Fragment {
    private final String TAG = "FragmentPlayerFull";

    /**
     * Fragment may be a part of another view which could be dragged/scrolled
     * and certain hacks may require the fragment to request them to stop
     * intercepting touch events to not end up confused.
     */
    public interface TouchInterceptListener {
        void requestDisallowInterceptTouchEvent(boolean disallow);
    }

    private TouchInterceptListener touchInterceptListener;

    private BroadcastReceiver updateUIReceiver;

    private boolean initialized = false;

    private RefreshHandler refreshHandler = new RefreshHandler();
    private TimedUpdateTask timedUpdateTask = new TimedUpdateTask(this);
    private static final int TIMED_UPDATE_INTERVAL = 1000; // 1 second

    private PlayerTrackMetadataCallback trackMetadataCallback;
    private TrackMetadataCallback.FailureType trackMetadataLastFailureType = null;
    private StreamLiveInfo lastLiveInfoForTrackMetadata = null;

    private RecordingsManager recordingsManager;
    private java.util.Observer recordingsObserver;

    private FavouriteManager favouriteManager;
    private FavouritesObserver favouritesObserver = new FavouritesObserver();

    private TrackHistoryRepository trackHistoryRepository;
    private TrackHistoryAdapter trackHistoryAdapter;
    private TrackHistoryAdapter stationHistoryAdapter;
    private String lastStationUuid;

    private RecordingsAdapter recordingsAdapter;

    private ActivityResultLauncher<String> exportRecordingLauncher;
    private DataRecording pendingExportRecording;

    private RecyclerAwareNestedScrollView scrollViewContent;

    // 「电台信息」页视图（作为四页内容 pager 的第 0 页，由 HistoryAndRecordsPagerAdapter 管理）：
    // 字段从原 ArtAndInfoPagerAdapter 上移为 Fragment 字段
    private ViewGroup stationInfoPage;
    private ImageView imageViewArt;
    private TextView textViewStationDescription;
    private TagsView viewTags;
    private ViewGroup layoutStationNoteRow;
    private TextView textViewStationNote;
    // IconicsImageButton 继承自 IconicsImageView 而非 ImageButton，声明为 View 避免类型转换崩溃
    private View buttonEditStationNote;

    private TabLayout segmentTabs;

    private TextView textViewGeneralInfo;
    private TextView textViewTotalPlayTime;
    private TextView textViewCurrentPlayTime;
    private TextView textViewNetworkUsageInfo;
    private TextView textViewTimeCached;

    /** 播放器「电台信息」页大图标的目标尺寸（dp），与页内图标卡高度一致 */
    private static final int PLAYER_ART_ICON_SIZE_DP = 180;

    private ImageView imageViewConnectionTypeFull;

    // 录音状态块（分隔线 + 录音图标/大小/文件名）：仅「电台信息 / 录音」段且录音进行中时显示
    private ViewGroup blockRecording;
    /** 录音行是否有内容可显示（录音进行中），与「当前段是否属于录音行」共同决定录音块可见性 */
    private boolean recordingRowActive;
    private ImageView imgRecordingIcon;
    private TextView textViewRecordingSize;
    private TextView textViewRecordingName;

    // 内容 pager（电台信息 / 本台曲目 / 曲目历史 / 录音列表四页，支持横滑翻页）
    private ViewPager pagerContent;
    private HistoryAndRecordsPagerAdapter historyAndRecordsPagerAdapter;

    private TrackHistoryViewModel trackHistoryViewModel;

    private ImageButton btnPlay;
    private ImageButton btnPrev;
    private ImageButton btnNext;
    private ImageButton btnRecord;
    private ImageButton btnFavourite;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 在 onCreate 中注册，保证同一 Fragment 实例只注册一次
        exportRecordingLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("*/*"),
                uri -> {
                    DataRecording recording = pendingExportRecording;
                    pendingExportRecording = null;
                    if (recording != null && uri != null) {
                        recordingsManager.exportRecordingToUri(requireContext(), recording, uri);
                    }
                });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        RadioDroidApp radioDroidApp = (RadioDroidApp) requireActivity().getApplication();

        recordingsManager = radioDroidApp.getRecordingsManager();
        recordingsObserver = (observable, o) -> updateRecordings();

        favouriteManager = radioDroidApp.getFavouriteManager();

        trackHistoryAdapter = new TrackHistoryAdapter(requireActivity());
        trackHistoryAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            public void onItemRangeInserted(int positionStart, int itemCount) {
                final LinearLayoutManager lm = (LinearLayoutManager) historyAndRecordsPagerAdapter.recyclerViewSongHistory.getLayoutManager();
                if (lm.findFirstVisibleItemPosition() < 2) {
                    historyAndRecordsPagerAdapter.recyclerViewSongHistory.scrollToPosition(0);
                }
            }
        });

        stationHistoryAdapter = new TrackHistoryAdapter(requireActivity());
        stationHistoryAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            public void onItemRangeInserted(int positionStart, int itemCount) {
                final LinearLayoutManager lm = (LinearLayoutManager) historyAndRecordsPagerAdapter.recyclerViewCurrentStationHistory.getLayoutManager();
                if (lm != null && lm.findFirstVisibleItemPosition() < 2) {
                    historyAndRecordsPagerAdapter.recyclerViewCurrentStationHistory.scrollToPosition(0);
                }
            }
        });

        trackHistoryRepository = radioDroidApp.getTrackHistoryRepository();

        updateUIReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                switch (intent.getAction()) {
//                    case PlayerService.PLAYER_SERVICE_TIMER_UPDATE: {
//                        timerUpdate();
//                        break;
//                    }
                    case PlayerService.PLAYER_SERVICE_STATE_CHANGE: {
                        
                        fullUpdate();
                        
                        break;
                    }
                    case PlayerService.PLAYER_SERVICE_META_UPDATE: {
                        fullUpdate();
                        break;
                    }
                    case PlayerService.PLAYER_SERVICE_CONNECTION_TYPE_CHANGED: {
                        String connectionTypeName = intent.getStringExtra(PlayerService.PLAYER_SERVICE_CONNECTION_TYPE_EXTRA);
                        if (connectionTypeName != null) {
                            // 防御非法 enum 名：valueOf 遇到未知字符串会抛 IllegalArgumentException 导致主线程崩溃
                            ConnectivityChecker.ConnectionType type;
                            try {
                                type = ConnectivityChecker.ConnectionType.valueOf(connectionTypeName);
                            } catch (IllegalArgumentException e) {
                                type = ConnectivityChecker.ConnectionType.NONE;
                            }
                            updateConnectionTypeIcon(type);
                        }
                        break;
                    }
                }
            }
        };

        View view = inflater.inflate(R.layout.layout_player_full, container, false);

        scrollViewContent = view.findViewById(R.id.scrollViewContent);

        // 「电台信息」页：inflate 后作为内容 pager 的第 0 页（不预先 attach，由 adapter 管理），
        // 取代原先被合并的「专辑封面 / 信息」两个 ViewPager 页
        stationInfoPage = (ViewGroup) inflater.inflate(R.layout.page_player_station_info, (ViewGroup) view, false);
        imageViewArt = stationInfoPage.findViewById(R.id.imageViewArt);
        textViewStationDescription = stationInfoPage.findViewById(R.id.textViewStationDescription);
        viewTags = stationInfoPage.findViewById(R.id.viewTags);
        layoutStationNoteRow = stationInfoPage.findViewById(R.id.layoutStationNoteRow);
        textViewStationNote = stationInfoPage.findViewById(R.id.textViewStationNote);
        buttonEditStationNote = stationInfoPage.findViewById(R.id.buttonEditStationNote);

        // 播放状态块（本次/累计收听、缓冲、流量）位于信息页布局内；电台标题行在主布局
        textViewGeneralInfo = view.findViewById(R.id.textViewGeneralInfo);
        textViewTotalPlayTime = stationInfoPage.findViewById(R.id.textViewTotalPlayTime);
        textViewCurrentPlayTime = stationInfoPage.findViewById(R.id.textViewCurrentPlayTime);
        textViewNetworkUsageInfo = stationInfoPage.findViewById(R.id.textViewNetworkUsageInfo);
        textViewTimeCached = stationInfoPage.findViewById(R.id.textViewTimeCached);

        imageViewConnectionTypeFull = view.findViewById(R.id.imageViewConnectionTypeFull);

        blockRecording = view.findViewById(R.id.blockRecording);
        imgRecordingIcon = view.findViewById(R.id.imgRecordingIcon);
        textViewRecordingSize = view.findViewById(R.id.textViewRecordingSize);
        textViewRecordingName = view.findViewById(R.id.textViewRecordingName);

        pagerContent = view.findViewById(R.id.pagerContent);
        historyAndRecordsPagerAdapter = new HistoryAndRecordsPagerAdapter(requireContext(), pagerContent, stationInfoPage);
        pagerContent.setAdapter(historyAndRecordsPagerAdapter);

        // 单行四段分段控件（UI 审查 P1-2）：取代原先两行 PagerTabStrip，四个页面全部保留
        setupSegmentTabs(view);

        // "本台曲目"顶部的正在播放头也可点击，与历史列表条目一致弹出曲目详情（含歌词入口）
        historyAndRecordsPagerAdapter.layoutCurrentPlaying.setOnClickListener(v -> showCurrentTrackInfoDialog());

        btnPlay = view.findViewById(R.id.buttonPlay);
        btnPrev = view.findViewById(R.id.buttonPrev);
        btnNext = view.findViewById(R.id.buttonNext);
        btnRecord = view.findViewById(R.id.buttonRecord);
        btnFavourite = view.findViewById(R.id.buttonFavorite);

        historyAndRecordsPagerAdapter.recyclerViewCurrentStationHistory.setAdapter(stationHistoryAdapter);

        LinearLayoutManager llmCurrentStationHistory = new LinearLayoutManager(getContext());
        llmCurrentStationHistory.setOrientation(RecyclerView.VERTICAL);
        historyAndRecordsPagerAdapter.recyclerViewCurrentStationHistory.setLayoutManager(llmCurrentStationHistory);

        historyAndRecordsPagerAdapter.recyclerViewSongHistory.setAdapter(trackHistoryAdapter);

        LinearLayoutManager llmHistory = new LinearLayoutManager(getContext());
        llmHistory.setOrientation(RecyclerView.VERTICAL);
        historyAndRecordsPagerAdapter.recyclerViewSongHistory.setLayoutManager(llmHistory);

        // 曲目历史空态（UI 美化）：跟随适配器条目数切换居中空态
        trackHistoryAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateEmptyState(historyAndRecordsPagerAdapter.emptySongHistory, historyAndRecordsPagerAdapter.recyclerViewSongHistory);
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                updateEmptyState(historyAndRecordsPagerAdapter.emptySongHistory, historyAndRecordsPagerAdapter.recyclerViewSongHistory);
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                updateEmptyState(historyAndRecordsPagerAdapter.emptySongHistory, historyAndRecordsPagerAdapter.recyclerViewSongHistory);
            }
        });
        updateEmptyState(historyAndRecordsPagerAdapter.emptySongHistory, historyAndRecordsPagerAdapter.recyclerViewSongHistory);

        trackHistoryViewModel = ViewModelProviders.of(this).get(TrackHistoryViewModel.class);
        trackHistoryViewModel.getStationHistoryPaged().observe(getViewLifecycleOwner(), new Observer<PagedList<TrackHistoryEntry>>() {
            @Override
            public void onChanged(@Nullable PagedList<TrackHistoryEntry> songHistoryEntries) {
                stationHistoryAdapter.submitList(songHistoryEntries);
            }
        });
        trackHistoryViewModel.getAllHistoryPaged().observe(getViewLifecycleOwner(), new Observer<PagedList<TrackHistoryEntry>>() {
            @Override
            public void onChanged(@Nullable PagedList<TrackHistoryEntry> songHistoryEntries) {
                trackHistoryAdapter.submitList(songHistoryEntries);
            }
        });

        recordingsAdapter = new RecordingsAdapter(requireContext(), recordingsManager);
        recordingsAdapter.setOnExportRequestedListener(recording -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                pendingExportRecording = recording;
                // MIME 用 "*/*"：录音扩展名随流类型变化（mp3/aac/ogg/ts 等），
                // 固定 MIME 会导致部分文件被置灰不可选，文件类型由文件名保证。
                exportRecordingLauncher.launch(recording.Name);
            } else {
                recordingsManager.exportRecordingToPublicDir(requireContext(), recording);
            }
        });
        recordingsAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            public void onItemRangeInserted(int positionStart, int itemCount) {
                updateEmptyState(historyAndRecordsPagerAdapter.emptyRecordings, historyAndRecordsPagerAdapter.recyclerViewRecordings);
                final LinearLayoutManager lm = (LinearLayoutManager) historyAndRecordsPagerAdapter.recyclerViewRecordings.getLayoutManager();
                if (lm.findFirstVisibleItemPosition() < 2) {
                    historyAndRecordsPagerAdapter.recyclerViewRecordings.scrollToPosition(0);
                }
            }

            @Override
            public void onChanged() {
                updateEmptyState(historyAndRecordsPagerAdapter.emptyRecordings, historyAndRecordsPagerAdapter.recyclerViewRecordings);
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                updateEmptyState(historyAndRecordsPagerAdapter.emptyRecordings, historyAndRecordsPagerAdapter.recyclerViewRecordings);
            }
        });

        historyAndRecordsPagerAdapter.recyclerViewRecordings.setAdapter(recordingsAdapter);

        LinearLayoutManager llmRecordings = new LinearLayoutManager(getContext());
        llmRecordings.setOrientation(RecyclerView.VERTICAL);
        historyAndRecordsPagerAdapter.recyclerViewRecordings.setLayoutManager(llmRecordings);

        // 录音空态（UI 美化）：跟随适配器条目数切换居中空态
        updateEmptyState(historyAndRecordsPagerAdapter.emptyRecordings, historyAndRecordsPagerAdapter.recyclerViewRecordings);

        // 「电台信息」pane 与分页列表（本台曲目 / 曲目历史 / 录音）应占据其上方内容
        // 与录音状态块之外的剩余可视高度，只能在运行时确定。
        // 若剩余空间不足（如横屏），退化为与可视区等高，由外层滚动查看，保持原有行为。
        ViewTreeObserver viewTreeObserver = scrollViewContent.getViewTreeObserver();
        if (viewTreeObserver.isAlive()) {
            viewTreeObserver.addOnGlobalLayoutListener(this::applyContentHeights);
        }

        return view;
    }

    public void init() {
        if (!initialized) {
            fullUpdate();
        }
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                
                
                if (PlayerServiceUtil.isPlaying()) {
                    
                    if (PlayerServiceUtil.isRecording()) {
                        
                        PlayerServiceUtil.stopRecording();
                        updateRunningRecording();
                    }

                    PlayerServiceUtil.pause(PauseReason.USER);
                } else {
                    
                    playLastFromHistory();
                }
                
                // 添加延迟检查，看看状态是否改变
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        
                    }
                }, 500);
            }
        });

        btnPrev.setOnClickListener(view -> PlayerServiceUtil.skipToPrevious());
        btnNext.setOnClickListener(view -> PlayerServiceUtil.skipToNext());

        btnRecord.setOnClickListener(view -> {
            if (PlayerServiceUtil.isPlaying()) {
                if (PlayerServiceUtil.isRecording()) {
                    PlayerServiceUtil.stopRecording();
                } else {
                    // 录音写入应用专属外部目录，无需存储权限
                    PlayerServiceUtil.startRecording();
                }

                updateRunningRecording();

                // 录音入口（UI 审查 P1-2）：切到单行分段控件的「录音」段，
                // 由分段联动逻辑互斥显示历史 pager 并翻到录音页
                TabLayout.Tab recordingsTab = segmentTabs.getTabAt(3);
                if (recordingsTab != null) {
                    segmentTabs.selectTab(recordingsTab);
                }
            } else {
                Toast.makeText(getActivity(), getResources().getString(R.string.error_record_not_playing), Toast.LENGTH_SHORT).show();
            }
        });

        btnFavourite.setOnClickListener(v -> {
            DataRadioStation station = Utils.getCurrentOrLastStation(requireContext());

            if (station == null) {
                return;
            }

            if (favouriteManager.has(station.StationUuid)) {
                StationActions.removeFromFavourites(requireContext(), v, v, station);
            } else {
                StationActions.markAsFavourite(requireContext(), station);
            }
        });
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);

        if (hidden) {
            stopUpdating();
        } else {
            startUpdating();
        }

        if (touchInterceptListener != null) {
            touchInterceptListener.requestDisallowInterceptTouchEvent(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        startUpdating();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopUpdating();
    }

    public void setTouchInterceptListener(TouchInterceptListener touchInterceptListener) {
        this.touchInterceptListener = touchInterceptListener;
    }

    private void startUpdating() {
        
        // Temporarily remove visibility check to ensure broadcast receiver is registered
        // if (!isVisible()) {
        //     Log.d("FragmentPlayerFull", "Fragment not visible, returning early");
        //     return;
        // }

        fullUpdate();

        refreshHandler.executePeriodically(timedUpdateTask, TIMED_UPDATE_INTERVAL);

        IntentFilter filter = new IntentFilter();

        filter.addAction(PlayerService.PLAYER_SERVICE_TIMER_UPDATE);
        filter.addAction(PlayerService.PLAYER_SERVICE_STATE_CHANGE);
        filter.addAction(PlayerService.PLAYER_SERVICE_META_UPDATE);
        filter.addAction(PlayerService.PLAYER_SERVICE_CONNECTION_TYPE_CHANGED);

        
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(updateUIReceiver, filter);
        

        recordingsManager.getSavedRecordingsObservable().addObserver(recordingsObserver);

        favouriteManager.addObserver(favouritesObserver);
    }

    private void stopUpdating() {
        if (getView() == null) {
            return;
        }

        refreshHandler.cancel();

        if (trackMetadataCallback != null) {
            trackMetadataCallback.cancel();
        }

        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(updateUIReceiver);

        recordingsManager.getSavedRecordingsObservable().deleteObserver(recordingsObserver);

        favouriteManager.deleteObserver(favouritesObserver);
    }

    public void resetScroll() {
        scrollViewContent.scrollTo(0, 0);
        historyAndRecordsPagerAdapter.recyclerViewCurrentStationHistory.scrollToPosition(0);
        historyAndRecordsPagerAdapter.recyclerViewSongHistory.scrollToPosition(0);
        historyAndRecordsPagerAdapter.recyclerViewRecordings.scrollToPosition(0);
    }

    public boolean isScrolled() {
        return scrollViewContent.getScrollY() > 0;
    }

    private void playLastFromHistory() {
        RadioDroidApp radioDroidApp = (RadioDroidApp) requireActivity().getApplication();
        DataRadioStation station = PlayerServiceUtil.getCurrentStation();

        if (station == null) {
            HistoryManager historyManager = radioDroidApp.getHistoryManager();
            station = historyManager.getFirst();
        }

        if (station != null) {
            Utils.showPlaySelection(radioDroidApp, station, getActivity().getSupportFragmentManager());
        }
    }

    /**
     * 信息页备注行（Issue #54）：展示当前电台备注，点击整行或铅笔图标弹出编辑对话框，
     * 保存后经回调原地刷新，无需等待下一次 fullUpdate。
     */
    private void updateStationNoteRow(DataRadioStation station) {
        StationNoteManager noteManager = ((RadioDroidApp) requireActivity().getApplication()).getStationNoteManager();
        String note = noteManager.getNote(station.StationUuid);

        textViewStationNote.setCompoundDrawablesRelativeWithIntrinsicBounds(buildNoteIcon(), null, null, null);
        if (TextUtils.isEmpty(note)) {
            textViewStationNote.setText(R.string.note_add_hint);
            // 空备注占位提示用次要色弱化，不与正文备注争夺注意力
            textViewStationNote.setTextColor(Utils.getThemeColor(requireContext(), R.attr.colorTextSecondary));
        } else {
            textViewStationNote.setText(note);
            // 正文备注与 TextAppearance.RadioDroid.Note 及行首图标同用强调色
            textViewStationNote.setTextColor(Utils.getThemeColor(requireContext(), R.attr.presetAccentColor));
        }

        View.OnClickListener editListener = v -> StationNoteDialog.show(requireContext(), station,
                newNote -> updateStationNoteRow(station));
        layoutStationNoteRow.setOnClickListener(editListener);
        buttonEditStationNote.setOnClickListener(editListener);
    }

    /**
     * 备注行首小图标：跟随预设强调色，与列表备注行视觉一致。
     */
    private Drawable buildNoteIcon() {
        return new IconicsDrawable(requireContext(), GoogleMaterial.Icon.gmd_description)
                .size(IconicsSize.dp(12))
                .color(IconicsColor.colorInt(Utils.getThemeColor(requireContext(), R.attr.presetAccentColor)));
    }

    private void fullUpdate() {
        DataRadioStation station = Utils.getCurrentOrLastStation(requireContext());

        if (station != null) {
            if (!station.StationUuid.equals(lastStationUuid)) {
                lastStationUuid = station.StationUuid;
                trackHistoryViewModel.setStationUuid(station.StationUuid);
            }
        }

        updateCurrentPlayingHeader();

        if (station != null) {
            final ShoutcastInfo shoutcastInfo = PlayerServiceUtil.getShoutcastInfo();
            // TODO: add some of shoutcast info

            final StreamLiveInfo liveInfo = PlayerServiceUtil.getMetadataLive();
            String streamTitle = liveInfo.getTitle();
            
            // 使用解析后的艺术家和歌曲信息，而不是原始标题
            String displayText = "";
            String displayTextForAccessibility = "";
            
            // 获取本地化的艺术家和歌曲名
            String artistDisplay = liveInfo.getArtist();
            String trackDisplay = liveInfo.getTrack();
            
            // 如果是英文的Unknown值或中文的"未知"，替换为本地化的Unknown
            if ("Unknown Artist".equals(artistDisplay) || "未知".equals(artistDisplay)) {
                artistDisplay = getString(R.string.unknown_artist);
            }
            if ("Unknown Track".equals(trackDisplay) || "未知".equals(trackDisplay)) {
                trackDisplay = getString(R.string.unknown_track);
            }
            
            if (liveInfo.hasArtistAndTrack()) {
                displayText = artistDisplay + " - " + trackDisplay;
                // 为无障碍服务提供更详细的信息
                displayTextForAccessibility = getString(R.string.now_playing_accessibility, trackDisplay, artistDisplay);
                
                // 如果艺术家或歌曲名包含"未知"信息，提供更友好的提示
                if (("Unknown Artist".equals(liveInfo.getArtist()) || "未知".equals(liveInfo.getArtist())) && 
                    !("Unknown Track".equals(liveInfo.getTrack()) || "未知".equals(liveInfo.getTrack()))) {
                    displayTextForAccessibility = getString(R.string.now_playing_artist_unknown, trackDisplay);
                } else if (!("Unknown Artist".equals(liveInfo.getArtist()) || "未知".equals(liveInfo.getArtist())) && 
                           ("Unknown Track".equals(liveInfo.getTrack()) || "未知".equals(liveInfo.getTrack()))) {
                    displayTextForAccessibility = getString(R.string.now_playing_track_unknown, artistDisplay);
                } else if (("Unknown Artist".equals(liveInfo.getArtist()) || "未知".equals(liveInfo.getArtist())) && 
                           ("Unknown Track".equals(liveInfo.getTrack()) || "未知".equals(liveInfo.getTrack()))) {
                    displayTextForAccessibility = getString(R.string.now_playing_all_unknown);
                }
            } else if (!TextUtils.isEmpty(streamTitle)) {
                displayText = streamTitle;
                displayTextForAccessibility = getString(R.string.now_playing_stream, streamTitle);
            }

            if (!TextUtils.isEmpty(displayText)) {
                textViewGeneralInfo.setText(displayText);
                // 设置无障碍内容描述
                textViewGeneralInfo.setContentDescription(displayTextForAccessibility);
            } else {
                textViewGeneralInfo.setText(station.Name);
                // 设置无障碍内容描述
                textViewGeneralInfo.setContentDescription(getString(R.string.content_desc_listening_station, station.Name));
            }

            Drawable flag = CountryFlagsLoader.getInstance().getFlag(requireContext(), station.CountryCode);
            if (flag != null) {
                float k = flag.getMinimumWidth() / (float) flag.getMinimumHeight();
                float viewHeight = textViewStationDescription.getTextSize();
                flag.setBounds(0, 0, (int) (k * viewHeight), (int) viewHeight);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                textViewStationDescription.setCompoundDrawablesRelative(flag, null, null, null);
            } else {
                textViewStationDescription.setCompoundDrawables(flag, null, null, null);
            }

            // TODO: add votes/clicks/trend

            textViewStationDescription.setText(station.getLongDetails(requireContext()));

            String[] tags = station.TagsAll.split(",");
            viewTags.setTags(Arrays.asList(tags));
            //viewTags.setTagSelectionCallback(tagSelectionCallback);

            // 备注行（Issue #54）：展示当前电台备注并提供编辑入口
            updateStationNoteRow(station);
        }

        updateAlbumArt();
        updateRecordings();
        
        // 直接更新播放按钮，类似于小播放器的实现
        
        if (PlayerServiceUtil.isPlaying()) {
            
            btnPlay.setImageResource(R.drawable.ic_pause_circle);
            btnPlay.setContentDescription(getResources().getString(R.string.detail_pause));
        } else {
            
            btnPlay.setImageResource(R.drawable.ic_play_circle);
            btnPlay.setContentDescription(getResources().getString(R.string.detail_play));
        }
        
        updateRecordButton(PlayerServiceUtil.isPlaying(), PlayerServiceUtil.isRecording());
        updateFavouriteButton();

        updateConnectionTypeIcon();

        timedUpdateTask.run();

        initialized = true;
    }

    private void updateCurrentPlayingHeader() {
        if (historyAndRecordsPagerAdapter == null || historyAndRecordsPagerAdapter.textViewCurrentTrack == null) {
            return;
        }

        DataRadioStation station = PlayerServiceUtil.getCurrentStation();
        if (station == null) {
            station = Utils.getCurrentOrLastStation(requireContext());
        }

        final StreamLiveInfo liveInfo = PlayerServiceUtil.getMetadataLive();
        String trackDisplay = liveInfo.getTrack();
        String artistDisplay = liveInfo.getArtist();

        if ("Unknown Track".equals(trackDisplay) || "未知".equals(trackDisplay)) {
            trackDisplay = getString(R.string.unknown_track);
        }
        if ("Unknown Artist".equals(artistDisplay) || "未知".equals(artistDisplay)) {
            artistDisplay = getString(R.string.unknown_artist);
        }

        if (liveInfo.hasArtistAndTrack()) {
            historyAndRecordsPagerAdapter.textViewCurrentTrack.setText(trackDisplay);
            historyAndRecordsPagerAdapter.textViewCurrentArtist.setText(artistDisplay);
        } else {
            String streamTitle = liveInfo.getTitle();
            if (!TextUtils.isEmpty(streamTitle)) {
                historyAndRecordsPagerAdapter.textViewCurrentTrack.setText(streamTitle);
                historyAndRecordsPagerAdapter.textViewCurrentArtist.setText(station != null ? station.Name : "");
            } else {
                historyAndRecordsPagerAdapter.textViewCurrentTrack.setText(station != null ? station.Name : getString(R.string.unknown_track));
                historyAndRecordsPagerAdapter.textViewCurrentArtist.setText("");
            }
        }

        if (station != null) {
            loadStationIconWithFallback(historyAndRecordsPagerAdapter.imageViewCurrentPlayingIcon, station.IconUrl, station.HomePageUrl, station.StationUuid, station.Name);
        }
    }

    private void showCurrentTrackInfoDialog() {
        DataRadioStation station = PlayerServiceUtil.getCurrentStation();
        if (station == null) {
            station = Utils.getCurrentOrLastStation(requireContext());
        }
        if (station == null) {
            return;
        }

        final StreamLiveInfo liveInfo = PlayerServiceUtil.getMetadataLive();
        String artist = liveInfo.getArtist();
        String track = liveInfo.getTrack();
        String title = liveInfo.getTitle();

        // 与头部显示一致的兜底：元数据缺解析结果时，以原始标题作为曲目名
        if ("Unknown Track".equals(track) || track.isEmpty()) {
            if (TextUtils.isEmpty(title)) {
                return;
            }
            track = title;
        }
        if ("Unknown Artist".equals(artist) || artist.isEmpty()) {
            artist = "";
        }

        TrackHistoryEntry entry = new TrackHistoryEntry();
        entry.stationUuid = station.StationUuid;
        entry.stationIconUrl = station.IconUrl;
        entry.artist = artist;
        entry.track = track;
        entry.title = title;
        entry.startTime = new Date();
        // endTime 早于 startTime 表示曲目仍在播放，与曲目历史的"正在播放"语义一致
        entry.endTime = new Date(0);

        new TrackHistoryInfoDialog(entry).show(requireActivity().getSupportFragmentManager(), TrackHistoryInfoDialog.FRAGMENT_TAG);
    }

    private void updateConnectionTypeIcon() {
        updateConnectionTypeIcon(ConnectivityChecker.getCurrentConnectionType(requireContext()));
    }

    private void updateConnectionTypeIcon(ConnectivityChecker.ConnectionType connectionType) {
        if (imageViewConnectionTypeFull == null) {
            return;
        }

        boolean showIndicator = PreferenceManager.getDefaultSharedPreferences(requireContext().getApplicationContext())
                .getBoolean(PlayerService.METERED_CONNECTION_WARNING_KEY, false);

        if (!showIndicator || connectionType == null || connectionType == ConnectivityChecker.ConnectionType.NONE || !PlayerServiceUtil.isPlaying()) {
            imageViewConnectionTypeFull.setVisibility(View.GONE);
            return;
        }

        switch (connectionType) {
            case NOT_METERED:
                imageViewConnectionTypeFull.setImageResource(R.drawable.ic_network_wifi);
                imageViewConnectionTypeFull.setContentDescription(getString(R.string.content_desc_connection_type_wifi));
                break;
            case METERED:
                imageViewConnectionTypeFull.setImageResource(R.drawable.ic_network_mobile);
                imageViewConnectionTypeFull.setContentDescription(getString(R.string.content_desc_connection_type_mobile));
                break;
        }

        imageViewConnectionTypeFull.setVisibility(View.VISIBLE);
    }

    private void updatePlaybackButtons(boolean playing, boolean recording) {
        updatePlayButton(playing);
        updateRecordButton(playing, recording);
    }

    private void updatePlayButton(boolean playing) {
        
        if (playing) {
            
            btnPlay.setImageResource(R.drawable.ic_pause_circle);
            btnPlay.setContentDescription(getResources().getString(R.string.detail_pause));
            
        } else {
            
            btnPlay.setImageResource(R.drawable.ic_play_circle);
            btnPlay.setContentDescription(getResources().getString(R.string.detail_play));
            
        }
    }

    private void updateRecordButton(boolean playing, boolean recording) {
        btnRecord.setEnabled(true);

        if (recording) {
            btnRecord.setImageResource(R.drawable.ic_stop_recording);
            btnRecord.setContentDescription(getResources().getString(R.string.detail_stop));
            btnRecord.setAlpha(1.0f);
        } else {
            btnRecord.setImageResource(R.drawable.ic_start_recording);

            if (!playing) {
                btnRecord.setAlpha(0.5f);
                btnRecord.setContentDescription(getResources().getString(R.string.error_record_not_playing));
            } else {
                btnRecord.setAlpha(1.0f);
                btnRecord.setContentDescription(getResources().getString(R.string.image_button_record));
            }
        }
    }

    private void updateRecordings() {
        recordingsAdapter.setRecordings(recordingsManager.getSavedRecordings());
        updateRunningRecording();
    }

    private void updateRunningRecording() {
        recordingRowActive = PlayerServiceUtil.isRecording();
        if (recordingRowActive) {
            final Map<Recordable, RunningRecordingInfo> runningRecordings = recordingsManager.getRunningRecordings();
            final RunningRecordingInfo recordingInfo = runningRecordings.entrySet().iterator().next().getValue();

            imgRecordingIcon.startAnimation(AnimationUtils.loadAnimation(getContext(), R.anim.blink_recording));
            textViewRecordingSize.setText(Utils.getReadableBytes(recordingInfo.getBytesWritten()));
            textViewRecordingName.setText(recordingInfo.getFileName());
        } else {
            imgRecordingIcon.clearAnimation();
        }
        updateRecordingRowVisibility();
    }

    /**
     * 录音状态块显隐（UI 审查 P1-2 后续）：「录音状态栏只在电台信息和录音界面显示」——
     * 当前段为「电台信息」或「录音」且确有录音进行中时可见，其余情况隐藏。
     * 显隐变化会触发外层全局布局回调，内容 pager 高度随之自动重算。
     */
    private void updateRecordingRowVisibility() {
        if (segmentTabs == null || blockRecording == null) {
            return;
        }
        final int position = segmentTabs.getSelectedTabPosition();
        final boolean pageShowsRecording = position == 0 || position == 3;
        blockRecording.setVisibility(pageShowsRecording && recordingRowActive ? View.VISIBLE : View.GONE);
    }

    private void updateAlbumArt() {
        DataRadioStation station = PlayerServiceUtil.getCurrentStation();
        if (station == null) {
            // 未播放时回退到最后播放的电台（UI 审查 P0-2）：同屏标题、顶栏图标、曲目历史
            // 均取 getCurrentOrLastStation()，封面独用 getCurrentStation() 会在未播放时整页空白
            station = Utils.getCurrentOrLastStation(requireContext());
            if (station == null) {
                return;
            }
        }

        final StreamLiveInfo liveInfo = PlayerServiceUtil.getMetadataLive();

        if (lastLiveInfoForTrackMetadata != null &&
                TextUtils.equals(lastLiveInfoForTrackMetadata.getArtist(), liveInfo.getArtist()) &&
                TextUtils.equals(lastLiveInfoForTrackMetadata.getTrack(), liveInfo.getTrack()) &&
                !TrackMetadataCallback.FailureType.RECOVERABLE.equals(trackMetadataLastFailureType)) {
            return;
        }

        final RadioDroidApp radioDroidApp = (RadioDroidApp) requireActivity().getApplication();
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(radioDroidApp);
        String LastFMApiKey = sharedPref.getString("last_fm_api_key", "");

        if (TextUtils.isEmpty(liveInfo.getArtist()) || TextUtils.isEmpty(liveInfo.getTrack()) ||
                LastFMApiKey.isEmpty()) {
            if (station.hasIcon()) {
                loadStationIconWithFallback(imageViewArt, station.IconUrl, station.HomePageUrl, station.StationUuid, station.Name, PLAYER_ART_ICON_SIZE_DP);
            } else if (!TextUtils.isEmpty(station.HomePageUrl)) {
                loadStationIconWithFallback(imageViewArt, null, station.HomePageUrl, station.StationUuid, station.Name, PLAYER_ART_ICON_SIZE_DP);
            } else {
                // 同步 tag，避免图标缓存写入后按旧电台刷新到错误的图片
                imageViewArt.setTag(R.id.tag_station_uuid, station.StationUuid);
                imageViewArt.setImageDrawable(StationPlaceholderUtils.createPlaceholderDrawable(getContext(), station.Name, station.StationUuid));
            }
            return;
        }

        trackMetadataLastFailureType = null;
        lastLiveInfoForTrackMetadata = liveInfo;

        if (trackMetadataCallback != null) {
            trackMetadataCallback.cancel();
        }

        TrackMetadataSearcher trackMetadataSearcher = radioDroidApp.getTrackMetadataSearcher();

        final WeakReference<FragmentPlayerFull> fragmentWeakReference = new WeakReference<>(this);
        trackHistoryRepository.getLastInsertedHistoryItem((trackHistoryEntry, dao) -> {
            if (trackHistoryEntry == null) {
                Log.e(TAG, "trackHistoryEntry is null in updateAlbumArt which should not happen.");
                return;
            }

            if (!TextUtils.isEmpty(trackHistoryEntry.artUrl)) {
                return;
            }

            FragmentPlayerFull fragment = fragmentWeakReference.get();
            if (fragment != null) {
                fragment.requireActivity().runOnUiThread(() -> {
                    if (fragment.isResumed()) {
                        fragment.trackMetadataCallback = new PlayerTrackMetadataCallback(fragmentWeakReference, trackHistoryEntry);
                        trackMetadataSearcher.fetchTrackMetadata(LastFMApiKey, liveInfo.getArtist(), liveInfo.getTrack(), fragment.trackMetadataCallback);
                    }
                });
            }
        });
    }

    private void updateFavouriteButton() {
        DataRadioStation station = Utils.getCurrentOrLastStation(requireContext());

        if (station != null && favouriteManager.has(station.StationUuid)) {
            btnFavourite.setImageResource(R.drawable.ic_star_24dp);
            btnFavourite.setContentDescription(requireContext().getApplicationContext().getString(R.string.detail_unstar));
        } else {
            btnFavourite.setImageResource(R.drawable.ic_star_border_24dp);
            btnFavourite.setContentDescription(requireContext().getApplicationContext().getString(R.string.detail_star));
        }
    }

    private class FavouritesObserver implements java.util.Observer {

        @Override
        public void update(Observable o, Object arg) {
            updateFavouriteButton();
        }
    }

    private static class PlayerTrackMetadataCallback implements TrackMetadataCallback {
        private boolean canceled = false;
        private WeakReference<FragmentPlayerFull> fragmentWeakReference;
        private TrackHistoryEntry trackHistoryEntry;

        private PlayerTrackMetadataCallback(@NonNull WeakReference<FragmentPlayerFull> fragmentWeakReference, TrackHistoryEntry trackHistoryEntry) {
            this.fragmentWeakReference = fragmentWeakReference;
            this.trackHistoryEntry = trackHistoryEntry;
        }

        public void cancel() {
            canceled = true;
        }

        @Override
        public void onFailure(@NonNull FailureType failureType) {
            FragmentPlayerFull fragment = fragmentWeakReference.get();
            if (fragment != null) {
                fragment.requireActivity().runOnUiThread(() -> {
                    if (canceled) {
                        return;
                    }

                    fragment.trackMetadataLastFailureType = failureType;

                    DataRadioStation station = Utils.getCurrentOrLastStation(fragment.requireContext());

                    if (station != null && station.hasIcon()) {
                        fragment.loadStationIconWithFallback(fragment.imageViewArt, station.IconUrl, station.HomePageUrl, station.StationUuid, station.Name, PLAYER_ART_ICON_SIZE_DP);
                    } else if (station != null && !TextUtils.isEmpty(station.HomePageUrl)) {
                        fragment.loadStationIconWithFallback(fragment.imageViewArt, null, station.HomePageUrl, station.StationUuid, station.Name, PLAYER_ART_ICON_SIZE_DP);
                    } else if (station != null) {
                        fragment.imageViewArt.setTag(R.id.tag_station_uuid, station.StationUuid);
                        fragment.imageViewArt.setImageDrawable(StationPlaceholderUtils.createPlaceholderDrawable(fragment.getContext(), station.Name, station.StationUuid));
                    } else {
                        fragment.imageViewArt.setTag(R.id.tag_station_uuid, null);
                        fragment.imageViewArt.setImageResource(R.drawable.ic_launcher);
                    }

                    fragment.trackMetadataCallback = null;
                });
            }
        }

        @Override
        public void onSuccess(@NonNull final TrackMetadata trackMetadata) {
            FragmentPlayerFull fragment = fragmentWeakReference.get();
            if (fragment != null) {
                fragment.requireActivity().runOnUiThread(() -> {
                    if (canceled) {
                        return;
                    }

                    final List<TrackMetadata.AlbumArt> albumArts = trackMetadata.getAlbumArts();
                    if (!albumArts.isEmpty()) {
                        final String albumArtUrl = albumArts.get(0).url;

                        if (!TextUtils.isEmpty(albumArtUrl)) {
                            // 专辑封面与电台图标无关：清除 tag，避免电台图标缓存写入后覆盖专辑封面
                            fragment.imageViewArt.setTag(R.id.tag_station_uuid, null);
                            Picasso.get()
                                    .load(albumArtUrl)
                                    .into(fragment.imageViewArt);

                            if (!albumArtUrl.equals(trackHistoryEntry.stationIconUrl)) {
                                fragment.trackHistoryRepository.setTrackArtUrl(trackHistoryEntry.uid, albumArtUrl);
                            }

                            fragment.trackMetadataCallback = null;

                            return;
                        }
                    }

                    onFailure(FailureType.UNRECOVERABLE);
                });
            }
        }
    }

    /**
     * 录音 / 曲目历史空态（UI 美化）：适配器无条目时显示居中空态，否则隐藏。
     * 只切换空态自身可见性，不改动列表可见性与数据。
     */
    private void updateEmptyState(View emptyView, RecyclerView list) {
        if (emptyView == null || list == null || list.getAdapter() == null) {
            return;
        }
        emptyView.setVisibility(list.getAdapter().getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private class HistoryAndRecordsPagerAdapter extends PagerAdapter {
        private ViewGroup layoutStationInfo;
        private ViewGroup layoutCurrentStationHistory;
        private ViewGroup layoutSongHistory;
        private ViewGroup layoutRecordings;

        ViewGroup layoutCurrentPlaying;

        RecyclerView recyclerViewCurrentStationHistory;
        RecyclerView recyclerViewSongHistory;
        RecyclerView recyclerViewRecordings;

        View emptySongHistory;
        View emptyRecordings;

        ImageView imageViewCurrentPlayingIcon;
        TextView textViewCurrentTrack;
        TextView textViewCurrentArtist;

        HistoryAndRecordsPagerAdapter(@NonNull Context context, @NonNull ViewGroup parent, @NonNull ViewGroup stationInfoPage) {
            LayoutInflater inflater = LayoutInflater.from(context);

            // 第 0 页「电台信息」（大尺寸图标卡 + 可滚动信息区 + 播放状态块）由 Fragment 预先
            // inflate 后传入，使 Fragment 可直接持有其内部控件引用；其余三页在此 inflate
            layoutStationInfo = stationInfoPage;
            layoutCurrentStationHistory = (ViewGroup) inflater.inflate(R.layout.page_player_current_history, parent, false);
            layoutSongHistory = (ViewGroup) inflater.inflate(R.layout.page_player_history, parent, false);
            layoutRecordings = (ViewGroup) inflater.inflate(R.layout.page_player_recordings, parent, false);

            recyclerViewCurrentStationHistory = layoutCurrentStationHistory.findViewById(R.id.recyclerViewCurrentStationHistory);
            recyclerViewSongHistory = layoutSongHistory.findViewById(R.id.recyclerViewSongHistory);
            recyclerViewRecordings = layoutRecordings.findViewById(R.id.recyclerViewRecordings);

            emptySongHistory = layoutSongHistory.findViewById(R.id.emptySongHistory);
            emptyRecordings = layoutRecordings.findViewById(R.id.emptyRecordings);

            layoutCurrentPlaying = layoutCurrentStationHistory.findViewById(R.id.layoutCurrentPlaying);
            imageViewCurrentPlayingIcon = layoutCurrentStationHistory.findViewById(R.id.imageViewCurrentPlayingIcon);
            textViewCurrentTrack = layoutCurrentStationHistory.findViewById(R.id.textViewCurrentTrack);
            textViewCurrentArtist = layoutCurrentStationHistory.findViewById(R.id.textViewCurrentArtist);
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup collection, int position) {
            switch (position) {
                case 0:
                    collection.addView(layoutStationInfo);
                    return layoutStationInfo;
                case 1:
                    collection.addView(layoutCurrentStationHistory);
                    return layoutCurrentStationHistory;
                case 2:
                    collection.addView(layoutSongHistory);
                    return layoutSongHistory;
                default:
                    collection.addView(layoutRecordings);
                    return layoutRecordings;
            }
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object view) {
            container.removeView((View) view);
        }

        @Override
        public int getCount() {
            return 4;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }
    }

    private static class TimedUpdateTask extends RefreshHandler.ObjectBoundRunnable<FragmentPlayerFull> {
        TimedUpdateTask(FragmentPlayerFull obj) {
            super(obj);
        }

        @Override
        protected void run(FragmentPlayerFull fragmentPlayerFull) {
            final ShoutcastInfo shoutcastInfo = PlayerServiceUtil.getShoutcastInfo();

            if (PlayerServiceUtil.isPlaying()) {
                String networkUsageInfo = Utils.getReadableBytes(PlayerServiceUtil.getTransferredBytes());
                if (shoutcastInfo != null && shoutcastInfo.bitrate > 0) {
                    networkUsageInfo += " (" + shoutcastInfo.bitrate + " kbps)";
                }

                fragmentPlayerFull.textViewNetworkUsageInfo.setText(
                        fragmentPlayerFull.getString(R.string.player_network_usage, networkUsageInfo));

                final long now = System.currentTimeMillis();
                final long startTime = PlayerServiceUtil.getLastPlayStartTime();
                long deltaSeconds = startTime > 0 ? ((now - startTime) / 1000) : 0;
                deltaSeconds = Math.max(deltaSeconds, 0);
                fragmentPlayerFull.textViewCurrentPlayTime.setText(
                        fragmentPlayerFull.getString(R.string.player_session_play_time, DateUtils.formatElapsedTime(deltaSeconds)));

                long totalPlayTime = PlayerServiceUtil.getTotalPlayTime();
                fragmentPlayerFull.textViewTotalPlayTime.setText(
                        fragmentPlayerFull.getString(R.string.player_total_play_time, DateUtils.formatElapsedTime(totalPlayTime)));

                fragmentPlayerFull.textViewTimeCached.setText(
                        fragmentPlayerFull.getString(R.string.player_buffer_time, DateUtils.formatElapsedTime(PlayerServiceUtil.getBufferedSeconds())));

                fragmentPlayerFull.updateRunningRecording();
            }
        }
    }

    private void loadStationIconWithFallback(final ImageView target, final String iconUrl, final String homePageUrl) {
        loadStationIconWithFallback(target, iconUrl, homePageUrl, null, null);
    }

    private void loadStationIconWithFallback(final ImageView target, final String iconUrl, final String homePageUrl, final String stationUuid) {
        loadStationIconWithFallback(target, iconUrl, homePageUrl, stationUuid, null);
    }

    private void loadStationIconWithFallback(final ImageView target, final String iconUrl, final String homePageUrl, final String stationUuid, final String stationName) {
        loadStationIconWithFallback(target, iconUrl, homePageUrl, stationUuid, stationName, 70);
    }

    private void loadStationIconWithFallback(final ImageView target, final String iconUrl, final String homePageUrl, final String stationUuid, final String stationName, final int targetSizeDp) {
        PlayerServiceUtil.getStationIcon(target, iconUrl, homePageUrl, stationUuid, stationName, targetSizeDp);
    }

    /**
     * 单行四段分段控件（UI 审查 P1-2 及后续调整）：合并原先两行 PagerTabStrip，
     * 「电台信息 | 本台曲目 | 曲目历史 | 录音列表」四页全部承载于单个内容 pager
     * （「电台信息」页合并了原专辑封面与信息两页内容），点击分段翻页、横滑翻页
     * 同步分段高亮，双向联动；录音状态块仅随「电台信息 / 录音列表」显示。
     * 胶囊背景用当前主题强调色（配色方案联动），胶囊文字
     * 按 WCAG 亮度自动取黑/白（与项目既有「色块取字色」规则一致）；未选中段用次要文字色。
     */
    private void setupSegmentTabs(@NonNull View view) {
        segmentTabs = view.findViewById(R.id.segmentTabs);

        final int accent = Utils.getThemeColor(requireContext(), R.attr.presetAccentColor);
        final int onAccent = contrastColorFor(accent);
        final int secondary = Utils.getThemeColor(requireContext(), R.attr.colorTextSecondary);

        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(12));
        pill.setColor(accent);

        String[] titles = new String[]{
                getResources().getString(R.string.tab_player_station_info),
                getResources().getString(R.string.tab_player_current_history),
                getResources().getString(R.string.tab_player_history),
                getResources().getString(R.string.tab_player_recordings)};

        for (int i = 0; i < titles.length; i++) {
            TextView label = new TextView(requireContext());
            label.setText(titles[i]);
            label.setGravity(android.view.Gravity.CENTER);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            // 四段均分后每段较窄，4 字标题禁止换行（换行会把胶囊撑成两行高）
            label.setMaxLines(1);
            label.setPadding(dp(2), dp(8), dp(2), dp(8));
            label.setBackground(i == 0 ? pill : null);
            label.setTextColor(i == 0 ? onAccent : secondary);
            segmentTabs.addTab(segmentTabs.newTab().setCustomView(label));
        }

        segmentTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(@NonNull TabLayout.Tab tab) {
                updateSegmentStyles(tab.getPosition());
                // 点击分段 → 翻页（同位置时 setCurrentItem 为无操作）
                pagerContent.setCurrentItem(tab.getPosition(), false);
            }

            @Override
            public void onTabUnselected(@NonNull TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(@NonNull TabLayout.Tab tab) {
            }
        });

        // 内容 pager 横滑翻页 → 同步分段高亮与录音状态块显隐
        // （selectTab 已选中时为静默操作，onTabSelected 不再触发，不会形成循环）
        pagerContent.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                TabLayout.Tab tab = segmentTabs.getTabAt(position);
                if (tab != null && segmentTabs.getSelectedTabPosition() != position) {
                    segmentTabs.selectTab(tab);
                }
                updateRecordingRowVisibility();
            }
        });

        // 初始态：选中第一段「电台信息」（同位置 setCurrentItem 无回调，需手动同步录音状态块）
        TabLayout.Tab first = segmentTabs.getTabAt(0);
        if (first != null) {
            segmentTabs.selectTab(first);
        }
        updateRecordingRowVisibility();
    }

    /**
     * 按剩余可视空间设置内容 pager 的高度：
     * 可视高度 − pager 顶部偏移 −（可见时的）录音状态块高度。录音状态块显隐等布局
     * 变化都会触发全局布局回调，本方法幂等收敛。
     */
    private void applyContentHeights() {
        if (scrollViewContent == null || pagerContent == null) {
            return;
        }
        final int visibleHeight = scrollViewContent.getHeight();
        if (visibleHeight == 0) {
            return;
        }
        final int recordingExtra = blockRecording.getVisibility() == View.VISIBLE ? blockRecording.getHeight() : 0;
        final int available = visibleHeight - pagerContent.getTop() - recordingExtra;
        final int newHeight = available > 0 ? available : visibleHeight;
        ViewGroup.LayoutParams layoutParams = pagerContent.getLayoutParams();
        if (newHeight != layoutParams.height) {
            layoutParams.height = newHeight;
            pagerContent.setLayoutParams(layoutParams);
        }
    }

    /** 重绘四个分段的外观：选中段为强调色胶囊 + 反色文字，未选中段透明底 + 次要文字色。 */
    private void updateSegmentStyles(int selectedPosition) {
        final int accent = Utils.getThemeColor(requireContext(), R.attr.presetAccentColor);
        final int onAccent = contrastColorFor(accent);
        final int secondary = Utils.getThemeColor(requireContext(), R.attr.colorTextSecondary);
        for (int i = 0; i < segmentTabs.getTabCount(); i++) {
            TabLayout.Tab tab = segmentTabs.getTabAt(i);
            if (tab == null || tab.getCustomView() == null) {
                continue;
            }
            TextView label = (TextView) tab.getCustomView();
            boolean selected = i == selectedPosition;
            if (selected) {
                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(dp(12));
                pill.setColor(accent);
                label.setBackground(pill);
                label.setTextColor(onAccent);
            } else {
                label.setBackground(null);
                label.setTextColor(secondary);
            }
        }
    }

    /** 按 WCAG 相对亮度取黑或白（亮度大于 0.179 用黑），与项目既有「色块取字色」规则一致 */
    private static int contrastColorFor(int color) {
        double luminance = 0.2126 * linearize(Color.red(color) / 255.0)
                + 0.7152 * linearize(Color.green(color) / 255.0)
                + 0.0722 * linearize(Color.blue(color) / 255.0);
        return luminance > 0.179 ? Color.BLACK : Color.WHITE;
    }

    private static double linearize(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
