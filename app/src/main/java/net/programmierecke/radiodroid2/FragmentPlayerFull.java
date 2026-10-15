package net.programmierecke.radiodroid2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
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

import com.google.android.material.bottomsheet.BottomSheetBehavior;
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
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
    private ViewGroup layoutStationNoteRow;
    private TextView textViewStationNote;

    // 「电台信息」页 v4.1 整页 Hero：背景（logo 放大模糊 / 主题渐变兜底）铺满分段条到
    // 控制条之间；前景自上而下为方形电台图标展示卡 → 电台名 → 正在播放 →
    // 元数据胶囊 → 缓冲/流量状态胶囊（挨在一起居中）→ 备注行（贴底）
    private ImageView heroBgImage;
    /** Hero 流动层（播放时涌动）：与底层同源模糊位图，反向缓移 + 透明度渐变 */
    private ImageView heroBgFlow;
    private View heroLogoCard;
    /** 信息组上方弹性空隙：高度由代码计算，使信息组中心对齐 Hero 高度 55% */
    private View heroTopSpacer;
    private TextView heroStationName;
    private TextView heroNowPlaying;
    private TagsView heroMetaChips;
    private TextView statBufferChip;
    private TextView statTrafficChip;
    /** 缓冲/流量状态胶囊行（信息组下沿参照，用于 55% 定位计算） */
    private View statRow;

    /** 背景流动动画：播放时两层背景反向缓移（涌动），停止时冻结显示当前帧 */
    private AnimatorSet heroFlowAnimator;
    /** 流动层透明度渐起/渐落动画（独立于平移，模拟 HTML 参考稿的 rise/fall） */
    private ValueAnimator heroFlowAlphaAnimator;

    /** Hero 背景形态：LOGO = imageViewArt 位图放大模糊；GRADIENT = 主题色渐变兜底 */
    private enum HeroArtMode { LOGO, GRADIENT }

    private HeroArtMode heroArtMode = HeroArtMode.GRADIENT;
    /** 上次镜像到 Hero 背景的 imageViewArt 内容（同一 Drawable 不重复模糊） */
    private Drawable lastMirroredDrawable;
    /** 主题渐变兜底是否已应用到 Hero 背景（避免重复设置触发无效重绘） */
    private boolean heroGradientApplied = false;

    private TabLayout segmentTabs;

    /** 底部弹层行为：监听展开/收起以刷新顶栏联动；onDestroyView 反注册 */
    @Nullable
    private BottomSheetBehavior<View> sheetBehavior;

    private final BottomSheetBehavior.BottomSheetCallback bottomSheetCallback = new BottomSheetBehavior.BottomSheetCallback() {
        @Override
        public void onStateChanged(@NonNull View bottomSheet, int newState) {
            updateNowPlayingRowVisibility();
        }

        @Override
        public void onSlide(@NonNull View bottomSheet, float slideOffset) {
        }
    };

    @Override
    public void onDestroyView() {
        if (sheetBehavior != null) {
            sheetBehavior.removeBottomSheetCallback(bottomSheetCallback);
            sheetBehavior = null;
        }
        // Hero 背景流动动画随视图销毁终止，避免泄漏/空指针
        if (heroFlowAlphaAnimator != null) {
            heroFlowAlphaAnimator.cancel();
            heroFlowAlphaAnimator = null;
        }
        if (heroFlowAnimator != null) {
            heroFlowAnimator.cancel();
            heroFlowAnimator = null;
        }
        super.onDestroyView();
    }

    // 主布局通用标题行（正在播放 / 电台名 + 连接类型图标）：
    // 「电台信息」段由 Hero 承担身份信息，本行隐藏；其余三段恢复显示
    private ViewGroup rowNowPlaying;
    private TextView textViewGeneralInfo;

    /** 播放器「电台信息」页 logo 的解码目标尺寸（dp）：供 Hero 背景模糊取图保留清晰度 */
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
        heroBgImage = stationInfoPage.findViewById(R.id.heroBgImage);
        heroBgFlow = stationInfoPage.findViewById(R.id.heroBgFlow);
        heroLogoCard = stationInfoPage.findViewById(R.id.heroLogoCard);
        heroTopSpacer = stationInfoPage.findViewById(R.id.heroTopSpacer);
        heroStationName = stationInfoPage.findViewById(R.id.heroStationName);
        heroNowPlaying = stationInfoPage.findViewById(R.id.heroNowPlaying);
        heroMetaChips = stationInfoPage.findViewById(R.id.heroMetaChips);
        statBufferChip = stationInfoPage.findViewById(R.id.statBufferChip);
        statTrafficChip = stationInfoPage.findViewById(R.id.statTrafficChip);
        statRow = stationInfoPage.findViewById(R.id.statRow);
        layoutStationNoteRow = stationInfoPage.findViewById(R.id.layoutStationNoteRow);
        textViewStationNote = stationInfoPage.findViewById(R.id.textViewStationNote);

        // 背景双层预先放大，为播放时的「屏保级」平移/缩放留出足量余量（避免露出
        // 边缘）：底层 1.5 倍 → 480dp 宽屏每边余量 120dp（覆盖 X 向 ±100dp 位移），
        // 流动层 1.55 倍 → 余量 132dp（覆盖 ±120dp 位移）
        heroBgImage.setScaleX(1.5f);
        heroBgImage.setScaleY(1.5f);
        heroBgFlow.setScaleX(1.55f);
        heroBgFlow.setScaleY(1.55f);

        // 电台信息页布局定位（用户指定）：
        // ① 图标中心对齐 Hero 区（分段条↔控制条）高度的 25%；
        // ② 信息组（电台名→正在播放→元数据胶囊→状态胶囊）中心对齐 Hero 高度的 55%。
        // Hero 高度随设备/屏幕方向变化，XML 固定边距/等分权重均无法表达，改为布局
        // 回调里动态计算：图标设 topMargin、顶部弹性 Space 设精确高度（剩余空间由
        // 底部 weight=1 Space 吸收、备注行贴底）。topMargin/spacer 高度收敛后回调
        // 不再改值，监听自然空转，无循环布局风险。
        stationInfoPage.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                final int heroHeight = stationInfoPage.getHeight();
                if (heroLogoCard == null || heroHeight <= 0 || heroLogoCard.getHeight() <= 0) {
                    return;
                }

                // ① 图标中心 → Hero 高度 25% 处
                int iconTop = Math.round(heroHeight * 0.25f) - heroLogoCard.getHeight() / 2;
                LinearLayout.LayoutParams iconLp = (LinearLayout.LayoutParams) heroLogoCard.getLayoutParams();
                if (iconLp != null && iconLp.topMargin != iconTop) {
                    iconLp.topMargin = Math.max(iconTop, dp(8));
                    heroLogoCard.setLayoutParams(iconLp);
                    return; // 图标占位变化会触发下一轮回调，届时再算 spacer
                }

                // ② 信息组中心 → Hero 高度 55% 处
                //    spacer 高度 = 目标中心 - 信息组高一半 - 图标底
                //    组高下沿动态取：正在播放行可见时含它（组末行），隐藏时到状态胶囊
                if (heroTopSpacer == null || heroStationName.getTop() <= 0) {
                    return;
                }
                int groupBottom = heroNowPlaying != null
                        && heroNowPlaying.getVisibility() == View.VISIBLE
                        ? heroNowPlaying.getBottom() : statRow.getBottom();
                int infoGroupHeight = groupBottom - heroStationName.getTop();
                if (infoGroupHeight <= 0) {
                    return;
                }
                int spacerHeight = Math.round(heroHeight * 0.55f) - infoGroupHeight / 2 - heroLogoCard.getBottom();
                if (heroTopSpacer.getHeight() != spacerHeight && spacerHeight >= 0) {
                    heroTopSpacer.getLayoutParams().height = spacerHeight;
                    heroTopSpacer.setLayoutParams(heroTopSpacer.getLayoutParams());
                }
            }
        });

        // Hero 背景跟随 imageViewArt 内容：在其绘制前监听位图变化（初始加载 /
        // 图标缓存刷新 / 专辑封面替换 / 占位图都会触发绘制），统一镜像到背景层
        imageViewArt.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                updateHeroBackgroundFromArt();
                return true;
            }
        });

        // 播放状态胶囊（缓冲 / 流量）位于信息页布局内；电台标题行在主布局
        textViewGeneralInfo = view.findViewById(R.id.textViewGeneralInfo);
        // 顶栏电台名恒置选中态：长名超出单行宽度时走马灯循环滚动
        textViewGeneralInfo.setSelected(true);

        // 状态胶囊数值使用等宽（tabular）数字，每秒刷新时数字步进不跳动（API21+ 生效）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String tnum = "tnum";
            statBufferChip.setFontFeatureSettings(tnum);
            statTrafficChip.setFontFeatureSettings(tnum);
        }

        // 状态胶囊行首图标：时钟（缓冲）与上下箭头（流量），白色 85% 与胶囊文字同调
        statBufferChip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                new IconicsDrawable(requireContext(), GoogleMaterial.Icon.gmd_access_time)
                        .size(IconicsSize.dp(12))
                        .color(IconicsColor.colorInt(0xD9FFFFFF)), null, null, null);
        statTrafficChip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                new IconicsDrawable(requireContext(), GoogleMaterial.Icon.gmd_swap_vert)
                        .size(IconicsSize.dp(12))
                        .color(IconicsColor.colorInt(0xD9FFFFFF)), null, null, null);

        // 「正在播放」行首呼吸点（强调色亮变体，圆点尺寸与间距对齐设计稿 hero-now）
        GradientDrawable nowDot = new GradientDrawable();
        nowDot.setShape(GradientDrawable.OVAL);
        nowDot.setColor(Utils.getThemeColor(requireContext(), R.attr.presetAccentBrightColor));
        nowDot.setSize(dp(7), dp(7));
        heroNowPlaying.setCompoundDrawablesRelativeWithIntrinsicBounds(nowDot, null, null, null);
        heroNowPlaying.setCompoundDrawablePadding(dp(6));

        // 监听底部弹层状态：展开/收起会改变顶栏（FragmentPlayerSmall）的收起条件，
        // 但不触发分段切换，需在此手动刷新联动（见 updateNowPlayingRowVisibility）
        final View bottomSheet = requireActivity().findViewById(R.id.bottom_sheet);
        if (bottomSheet != null) {
            sheetBehavior = BottomSheetBehavior.from(bottomSheet);
            sheetBehavior.addBottomSheetCallback(bottomSheetCallback);
        }

        rowNowPlaying = view.findViewById(R.id.rowNowPlaying);
        imageViewConnectionTypeFull = view.findViewById(R.id.imageViewConnectionTypeFull);

        blockRecording = view.findViewById(R.id.blockRecording);
        imgRecordingIcon = view.findViewById(R.id.imgRecordingIcon);
        textViewRecordingSize = view.findViewById(R.id.textViewRecordingSize);
        textViewRecordingName = view.findViewById(R.id.textViewRecordingName);

        // 信息页主题派生样式（含录音块浮层底/描边）：需在 blockRecording 赋值后调用
        applyInfoPageThemeStyles();

        pagerContent = view.findViewById(R.id.pagerContent);
        historyAndRecordsPagerAdapter = new HistoryAndRecordsPagerAdapter(requireContext(), pagerContent, stationInfoPage);
        pagerContent.setAdapter(historyAndRecordsPagerAdapter);

        // 单行四段分段控件（UI 审查 P1-2）：取代原先两行 PagerTabStrip，四个页面全部保留
        setupSegmentTabs(view);

        // "本台曲目"顶部的正在播放头也可点击，与历史列表条目一致弹出曲目详情（含歌词入口）
        historyAndRecordsPagerAdapter.layoutCurrentPlaying.setOnClickListener(v -> showCurrentTrackInfoDialog());

        btnPlay = view.findViewById(R.id.buttonPlay);
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

        // 播放键按压缩放反馈（v3.1）：按下 scale(0.96) + 轻触感，抬起/取消回弹；
        // 返回 false 交回默认处理，点击与涟漪行为不变
        btnPlay.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(60).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    break;
            }
            return false;
        });

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
        // v4.1 信息页为固定布局（无滚动容器），无需重置其内部滚动位置
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
     * 信息页备注行（Issue #54，v4.1 贴底 Hero）：居中轻量文字行——空态白 62%「添加备注」
     * 提示（行首备注图标），有备注时强调色亮变体正文；无卡片底，避免破坏沉浸感。
     * 点击整行弹出编辑对话框，保存后原地刷新。
     */
    private void updateStationNoteRow(DataRadioStation station) {
        StationNoteManager noteManager = ((RadioDroidApp) requireActivity().getApplication()).getStationNoteManager();
        String note = noteManager.getNote(station.StationUuid);
        final boolean hasNote = !TextUtils.isEmpty(note);

        textViewStationNote.setCompoundDrawablesRelativeWithIntrinsicBounds(buildNoteIcon(), null, null, null);
        if (!hasNote) {
            textViewStationNote.setText(R.string.note_add_hint);
            // Hero 沉浸背景上的空态占位提示：白 62% 弱化，不与正文争夺注意力
            textViewStationNote.setTextColor(ContextCompat.getColor(requireContext(), R.color.player_hero_text_faint));
        } else {
            textViewStationNote.setText(note);
            // 正文备注与行首图标同用强调色亮变体（Hero 深色底上保持可读，随配色方案联动）
            textViewStationNote.setTextColor(Utils.getThemeColor(requireContext(), R.attr.presetAccentBrightColor));
        }

        layoutStationNoteRow.setOnClickListener(v -> StationNoteDialog.show(requireContext(), station,
                newNote -> updateStationNoteRow(station)));
    }

    /**
     * 备注行首小图标：跟随预设强调色亮变体，与备注文字形成统一视觉锚点。
     */
    private Drawable buildNoteIcon() {
        return new IconicsDrawable(requireContext(), GoogleMaterial.Icon.gmd_description)
                .size(IconicsSize.dp(12))
                .color(IconicsColor.colorInt(Utils.getThemeColor(requireContext(), R.attr.presetAccentBrightColor)));
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

            // 顶栏标题行恒显电台名（用户要求恢复；长名走马灯滚动）；曲目信息由
            // Hero「正在播放」行承担，顶栏不再重复
            textViewGeneralInfo.setText(station.Name);
            // 设置无障碍内容描述
            textViewGeneralInfo.setContentDescription(getString(R.string.content_desc_listening_station, station.Name));

            // 「正在播放」行置于状态胶囊下一行（信息组末尾）：播放中且有曲目时显示；
            // 停止/暂停后元数据仍是最后缓存曲目，继续显示会让用户误解电台仍在播出，
            // 隐藏时只影响该行自身（GONE），上方电台名与胶囊位置不动
            if (PlayerServiceUtil.isPlaying() && !TextUtils.isEmpty(displayText)) {
                heroNowPlaying.setText(getString(R.string.player_hero_now_playing, displayText));
                heroNowPlaying.setVisibility(View.VISIBLE);
            } else {
                heroNowPlaying.setVisibility(View.GONE);
            }

            // Hero 电台名：电台身份的唯一大字标识（顶栏另恒显单行走马灯电台名）
            heroStationName.setText(station.Name);

            // Hero 元数据胶囊行：比特率 · 编码 · 地区 · 语言（空值跳过）
            List<String> metaChips = new ArrayList<>();
            int liveBitrate = (shoutcastInfo != null && shoutcastInfo.bitrate > 0)
                    ? shoutcastInfo.bitrate : station.Bitrate;
            if (liveBitrate > 0) {
                metaChips.add(getString(R.string.station_detail_bitrate, liveBitrate));
            }
            if (!TextUtils.isEmpty(station.Codec)) {
                metaChips.add(station.Codec);
            }
            String regionChip = buildRegionChip(station);
            if (!TextUtils.isEmpty(regionChip)) {
                metaChips.add(regionChip);
            }
            if (!TextUtils.isEmpty(station.Language)) {
                metaChips.add(station.Language);
            }
            heroMetaChips.setTags(metaChips);

            // 电台信息页正文里不再重复比特率/编码/地区/语言（getLongDetails 已由胶囊行承担）
            // v4.1：风格标签已按设计移除，不再展示

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

        // 未播放时状态胶囊只显示标签名（「缓冲时长」「流量」），播放中由
        // TimedUpdateTask 每秒填充「标签 + 数值」，停止/暂停后恢复为纯标签
        if (!PlayerServiceUtil.isPlaying()) {
            statBufferChip.setText(R.string.player_stat_buffer);
            statTrafficChip.setText(R.string.player_stat_data);
        }

        // Hero 背景流动动画：播放时涌动、停止时渐落静止在当前帧
        updateHeroFlow(PlayerServiceUtil.isPlaying());

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
        updateNowPlayingRowVisibility();
    }

    /**
     * 主布局通用标题行（rowNowPlaying）恒隐藏（用户要求）：电台名只显示在展开态
     * 顶栏（FragmentPlayerSmall HEADER，电台图标 + 电台名 + 连接图标 + ⋮ 菜单），
     * 不再在分段条上方重复显示一行电台名。行保留在布局中（textViewGeneralInfo
     * 等引用仍在），仅恒 GONE 不占位。
     */
    private void updateNowPlayingRowVisibility() {
        if (rowNowPlaying == null) {
            return;
        }
        rowNowPlaying.setVisibility(View.GONE);

        // 迷你条 header（FragmentPlayerSmall）恒展开：展开态顶栏常显电台图标 +
        // 电台名 + 连接图标 + ⋮，不再按分段收起
        if (getActivity() != null) {
            androidx.fragment.app.Fragment headerFragment =
                    getActivity().getSupportFragmentManager().findFragmentById(R.id.fragment_player_small);
            if (headerFragment instanceof FragmentPlayerSmall) {
                ((FragmentPlayerSmall) headerFragment).setHeaderContentCollapsed(false);
            }
        }
    }

    /**
     * Hero 背景镜像（v3.1）：与 imageViewArt 共用同一位图——imageViewArt 每次内容
     * 变化都会触发绘制前回调，在此把当前位图缩小并轻度模糊后铺到背景层（60% 透明度）。
     * 占位态 / 无 logo 电台改用主题色渐变兜底，保证 Hero 任何时刻都有完整背景。
     */
    private void updateHeroBackgroundFromArt() {
        if (heroBgImage == null) {
            return;
        }
        if (heroArtMode != HeroArtMode.LOGO || imageViewArt == null) {
            applyHeroGradient();
            return;
        }

        Drawable current = imageViewArt.getDrawable();
        if (current == null || current == lastMirroredDrawable) {
            return;
        }
        if (!(current instanceof BitmapDrawable)) {
            // 占位图等非位图内容：不镜像，走主题渐变兜底
            applyHeroGradient();
            return;
        }

        heroGradientApplied = false;
        lastMirroredDrawable = current;
        Bitmap source = ((BitmapDrawable) current).getBitmap();
        Bitmap blurred = blurHeroBitmap(source);
        if (blurred == null) {
            applyHeroGradient();
            return;
        }
        heroBgImage.setAlpha(0.6f);
        heroBgImage.setImageBitmap(blurred);
        // 流动层与底层同源（播放时叠加反向缓移产生涌动）
        heroBgFlow.setImageBitmap(blurred);
    }

    /** Hero 无位图时的兜底背景：按预设强调色生成的对角渐变（亮/暗主题分别派生）。 */
    private void applyHeroGradient() {
        if (heroGradientApplied || heroBgImage == null) {
            return;
        }
        heroGradientApplied = true;
        lastMirroredDrawable = null;

        final int accent = Utils.getThemeColor(requireContext(), R.attr.presetAccentColor);
        final boolean dark = Utils.isDarkTheme(requireContext());
        // 亮色：800 阶主体 + 两端向黑/白微偏移制造纵深；暗色：整体压暗保持沉浸
        final int startColor = ColorUtils.blendARGB(accent, Color.BLACK, dark ? 0.45f : 0.22f);
        final int endColor = ColorUtils.blendARGB(accent, dark ? Color.BLACK : Color.WHITE, dark ? 0.12f : 0.22f);
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{startColor, accent, endColor});
        heroBgImage.setAlpha(1f);
        heroBgImage.setImageDrawable(gradient);
        // 渐变兜底同样镜像到流动层（mutate 避免两个 ImageView 共享 bounds 状态）
        heroBgFlow.setImageDrawable(gradient.mutate());
    }

    /**
     * Hero 背景流动动画（用户参考 HTML 设计稿的 feTurbulence 涌动效果）：
     * 播放时流动层透明度缓缓升起（模拟扭曲强度 rise），同时两层背景反向缓慢平移，
     * 叠加干涉产生「色块涌动」感；停止时透明度缓缓落回 0（fall）后冻结平移，
     * 底层静止显示当前背景帧。
     */
    private void updateHeroFlow(boolean playing) {
        if (heroBgFlow == null || !isAdded()) {
            return;
        }

        // 透明度渐起/渐落（rise 1.8s / fall 1.4s，与参考稿一致）
        float targetAlpha = playing ? 0.75f : 0f;
        if (heroFlowAlphaAnimator != null && heroFlowAlphaAnimator.isRunning()) {
            heroFlowAlphaAnimator.cancel();
        }
        heroFlowAlphaAnimator = ValueAnimator.ofFloat(heroBgFlow.getAlpha(), targetAlpha);
        heroFlowAlphaAnimator.setDuration(playing ? 1800 : 1400);
        heroFlowAlphaAnimator.addUpdateListener(animation ->
                heroBgFlow.setAlpha((float) animation.getAnimatedValue()));
        if (!playing) {
            // 落回 0 后冻结平移：背景完全静止在当前帧
            heroFlowAlphaAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(android.animation.Animator animation) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (!cancelled && !PlayerServiceUtil.isPlaying()) {
                        stopHeroFlowDrift();
                    }
                }
            });
        }
        heroFlowAlphaAnimator.start();

        if (playing) {
            if (heroFlowAnimator != null) {
                heroFlowAnimator.start(); // 复用已构建的平移动画，从冻结位置继续
                return;
            }
            // 「屏保级」漂移（用户要求明显流动，参考电脑屏保的大幅缓移）：
            // 位移幅度拉满放大余量（底层 ±100dp/±70dp，流动层 ∓120dp/∓80dp），
            // 周期 8~14s——8 秒内背景位移超百 dp，肉眼清晰可感
            ObjectAnimator driftBaseX = ObjectAnimator.ofFloat(heroBgImage, View.TRANSLATION_X, 0f, dp(100));
            driftBaseX.setDuration(14000);
            ObjectAnimator driftBaseY = ObjectAnimator.ofFloat(heroBgImage, View.TRANSLATION_Y, 0f, dp(70));
            driftBaseY.setDuration(11000);
            // 流动层反向缓漂（幅度更大、周期更短），与底层速度/方向错开产生明显干涉
            ObjectAnimator driftFlowX = ObjectAnimator.ofFloat(heroBgFlow, View.TRANSLATION_X, 0f, -dp(120));
            driftFlowX.setDuration(10000);
            ObjectAnimator driftFlowY = ObjectAnimator.ofFloat(heroBgFlow, View.TRANSLATION_Y, 0f, -dp(80));
            driftFlowY.setDuration(8000);
            // 缩放呼吸（屏保式缓慢胀缩）：只向放大方向呼吸，不缩小，保证位移永不露边
            ObjectAnimator breathBaseX = ObjectAnimator.ofFloat(heroBgImage, View.SCALE_X, 1.5f, 1.56f);
            breathBaseX.setDuration(18000);
            ObjectAnimator breathBaseY = ObjectAnimator.ofFloat(heroBgImage, View.SCALE_Y, 1.5f, 1.56f);
            breathBaseY.setDuration(18000);
            ObjectAnimator breathFlowX = ObjectAnimator.ofFloat(heroBgFlow, View.SCALE_X, 1.55f, 1.62f);
            breathFlowX.setDuration(14000);
            ObjectAnimator breathFlowY = ObjectAnimator.ofFloat(heroBgFlow, View.SCALE_Y, 1.55f, 1.62f);
            breathFlowY.setDuration(14000);

            heroFlowAnimator = new AnimatorSet();
            heroFlowAnimator.playTogether(driftBaseX, driftBaseY, driftFlowX, driftFlowY,
                    breathBaseX, breathBaseY, breathFlowX, breathFlowY);
            for (ObjectAnimator anim : new ObjectAnimator[]{driftBaseX, driftBaseY, driftFlowX, driftFlowY,
                    breathBaseX, breathBaseY, breathFlowX, breathFlowY}) {
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setRepeatMode(ValueAnimator.REVERSE);
                anim.setInterpolator(new LinearInterpolator());
            }
            heroFlowAnimator.start();
        }
    }

    /** 停止并冻结背景平移动画（保留当前 translation，画面静止在当前帧）。 */
    private void stopHeroFlowDrift() {
        if (heroFlowAnimator != null) {
            heroFlowAnimator.cancel();
        }
    }

    /**
     * Hero 背景模糊：先把源图缩到 ≤64px 宽，再做 3 次盒式模糊（近似高斯）。
     * 输入极小，UI 线程开销可忽略；盒式模糊用滑动窗口求和，无需额外依赖。
     */
    @Nullable
    private static Bitmap blurHeroBitmap(@Nullable Bitmap source) {
        if (source == null || source.isRecycled() || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return null;
        }
        final int targetWidth = 64;
        int targetHeight = Math.max(1, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
        Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
        } catch (IllegalArgumentException e) {
            return null;
        }

        int w = small.getWidth(), h = small.getHeight();
        int[] pixels = new int[w * h];
        small.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] temp = new int[pixels.length];
        for (int pass = 0; pass < 3; pass++) {
            boxBlurHorizontal(pixels, temp, w, h, 4);
            boxBlurVertical(temp, pixels, w, h, 4);
        }
        small.setPixels(pixels, 0, w, 0, 0, w, h);
        return small;
    }

    private static void boxBlurHorizontal(int[] src, int[] dst, int w, int h, int radius) {
        final int div = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            final int rowStart = y * w;
            int sr = 0, sg = 0, sb = 0, sa = 0;
            for (int i = -radius; i <= radius; i++) {
                int c = src[rowStart + Math.min(w - 1, Math.max(0, i))];
                sa += (c >>> 24);
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[rowStart + x] = ((sa / div) << 24)
                        | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int out = src[rowStart + Math.min(w - 1, Math.max(0, x - radius))];
                int in = src[rowStart + Math.min(w - 1, Math.max(0, x + radius + 1))];
                sa += (in >>> 24) - (out >>> 24);
                sr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static void boxBlurVertical(int[] src, int[] dst, int w, int h, int radius) {
        final int div = radius * 2 + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0, sa = 0;
            for (int i = -radius; i <= radius; i++) {
                int c = src[Math.min(h - 1, Math.max(0, i)) * w + x];
                sa += (c >>> 24);
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = ((sa / div) << 24)
                        | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int out = src[Math.min(h - 1, Math.max(0, y - radius)) * w + x];
                int in = src[Math.min(h - 1, Math.max(0, y + radius + 1)) * w + x];
                sa += (in >>> 24) - (out >>> 24);
                sr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    /**
     * 信息页主题派生样式（v4.1，全部从预设属性取值、随配色方案联动）：
     * 缓冲/流量状态胶囊 = 白 13% 底 + 强调色 45% 细描边；
     * 录音状态块 = 圆角半透明深底 + 强调色 28% 细描边（浮层样式）。
     */
    private void applyInfoPageThemeStyles() {
        final Context context = requireContext();
        final int accentBright = Utils.getThemeColor(context, R.attr.presetAccentBrightColor);

        // 缓冲 / 流量状态胶囊：白 13% 底 + 强调色 45% 细描边（与设计稿 stat-chip 一致）
        if (statBufferChip != null && statTrafficChip != null) {
            final int chipStroke = ColorUtils.setAlphaComponent(accentBright, 115);
            statBufferChip.setBackground(makeStatChipBackground(chipStroke));
            statTrafficChip.setBackground(makeStatChipBackground(chipStroke));
        }

        // 录音状态块浮层底：圆角半透明深底 + 强调色 28% 细描边（主题无关的深色覆盖层）
        if (blockRecording != null) {
            GradientDrawable recBackground = new GradientDrawable();
            recBackground.setCornerRadius(dp(10));
            recBackground.setColor(ContextCompat.getColor(context, R.color.player_hero_rec_bg));
            recBackground.setStroke(dp(1), ColorUtils.setAlphaComponent(accentBright, 72));
            blockRecording.setBackground(recBackground);
        }
    }

    /** 状态胶囊底形：全圆角 + 白 13% 底 + 强调色细描边 */
    private GradientDrawable makeStatChipBackground(int strokeColor) {
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(99));
        chip.setColor(ContextCompat.getColor(requireContext(), R.color.player_hero_chip_bg));
        chip.setStroke(dp(1), strokeColor);
        return chip;
    }

    /** 地区胶囊文案：国家显示名（· 州/省），数据缺失时回退原始码。 */
    private String buildRegionChip(@NonNull DataRadioStation station) {
        String country = "";
        if (!TextUtils.isEmpty(station.CountryCode)) {
            try {
                country = new Locale("", station.CountryCode).getDisplayCountry();
            } catch (Exception e) {
                country = station.CountryCode;
            }
            if (TextUtils.isEmpty(country)) {
                country = station.CountryCode;
            }
        }
        String state = station.State == null ? "" : station.State.trim();
        if (!TextUtils.isEmpty(state) && !state.equalsIgnoreCase(country)) {
            return TextUtils.isEmpty(country) ? state : country + " · " + state;
        }
        return country;
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
                heroArtMode = HeroArtMode.LOGO;
                loadStationIconWithFallback(imageViewArt, station.IconUrl, station.HomePageUrl, station.StationUuid, station.Name, PLAYER_ART_ICON_SIZE_DP);
            } else if (!TextUtils.isEmpty(station.HomePageUrl)) {
                heroArtMode = HeroArtMode.LOGO;
                loadStationIconWithFallback(imageViewArt, null, station.HomePageUrl, station.StationUuid, station.Name, PLAYER_ART_ICON_SIZE_DP);
            } else {
                // 同步 tag，避免图标缓存写入后按旧电台刷新到错误的图片
                heroArtMode = HeroArtMode.GRADIENT;
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
                        fragment.heroArtMode = HeroArtMode.LOGO;
                        fragment.loadStationIconWithFallback(fragment.imageViewArt, station.IconUrl, station.HomePageUrl, station.StationUuid, station.Name, PLAYER_ART_ICON_SIZE_DP);
                    } else if (station != null && !TextUtils.isEmpty(station.HomePageUrl)) {
                        fragment.heroArtMode = HeroArtMode.LOGO;
                        fragment.loadStationIconWithFallback(fragment.imageViewArt, null, station.HomePageUrl, station.StationUuid, station.Name, PLAYER_ART_ICON_SIZE_DP);
                    } else if (station != null) {
                        fragment.heroArtMode = HeroArtMode.GRADIENT;
                        fragment.imageViewArt.setTag(R.id.tag_station_uuid, station.StationUuid);
                        fragment.imageViewArt.setImageDrawable(StationPlaceholderUtils.createPlaceholderDrawable(fragment.getContext(), station.Name, station.StationUuid));
                    } else {
                        // 应用图标不属于电台视觉素材：Hero 背景仍用主题渐变兜底
                        fragment.heroArtMode = HeroArtMode.GRADIENT;
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
                            fragment.heroArtMode = HeroArtMode.LOGO;
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
        TimedUpdateTask(FragmentPlayerFull fragmentPlayerFull) {
            super(fragmentPlayerFull);
        }

        @Override
        protected void run(FragmentPlayerFull fragmentPlayerFull) {
            // v4.1 状态胶囊：缓冲 / 流量两枚挨在一起居中，文案 =「标签 + 数值」
            if (PlayerServiceUtil.isPlaying()) {
                fragmentPlayerFull.statBufferChip.setText(
                        fragmentPlayerFull.getString(R.string.player_stat_buffer) + " "
                                + DateUtils.formatElapsedTime(PlayerServiceUtil.getBufferedSeconds()));

                fragmentPlayerFull.statTrafficChip.setText(
                        fragmentPlayerFull.getString(R.string.player_stat_data) + " "
                                + Utils.getReadableBytes(PlayerServiceUtil.getTransferredBytes()));

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
        // v4.1 录音状态块为浮层样式（自带水平/底部外边距），高度需连同下边距一起扣除
        int recordingExtra = 0;
        if (blockRecording.getVisibility() == View.VISIBLE) {
            ViewGroup.MarginLayoutParams recParams = (ViewGroup.MarginLayoutParams) blockRecording.getLayoutParams();
            recordingExtra = blockRecording.getHeight() + recParams.bottomMargin;
        }
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
