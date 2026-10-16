package net.programmierecke.radiodroid2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.animation.AccelerateDecelerateInterpolator;
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
import net.programmierecke.radiodroid2.players.PlayState;
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
    /** 色斑涌动层容器（播放时屏保级动画，未播放透明显示底层模糊图标） */
    private View heroBgBlobs;
    /** 基底：取色占比最大的主色铺满（60~75% 透明度），色斑叠于其上 */
    private View heroBlobBase;
    /** 色斑视图（4~5 枚大型径向渐变色斑，颜色取自电台图标主色） */
    private ImageView[] heroBlobViews;
    private View heroLogoCard;
    /** 信息组上方弹性空隙：高度由代码计算，使信息组中心对齐 Hero 高度 55% */
    private View heroTopSpacer;
    private TextView heroStationName;
    private TextView heroNowPlaying;
    /** 「正在录音」行（信息组末行）：录音中显示，与录音列表页录音块互斥呈现 */
    private TextView heroNowRecording;
    private TagsView heroMetaChips;
    private TextView statBufferChip;
    private TextView statTrafficChip;
    /** 缓冲/流量状态胶囊行（信息组下沿参照，用于 55% 定位计算） */
    private View statRow;

    /** 色斑数量（与布局 heroBlob1~5 对应） */
    private static final int BLOB_COUNT = 5;

    /** 内容 pager 真实页数（电台信息/本台曲目/曲目历史/录音列表） */
    private static final int PAGER_REAL_COUNT = 4;
    /** 循环副本份数：虚拟页数 = 真实页数 × 副本数，实现首尾循环拖动 */
    private static final int PAGER_COPIES = 7;
    /** 中心副本区起点（初始位置与边缘归一化回跳位置） */
    private static final int PAGER_CENTER_BASE = PAGER_REAL_COUNT * (PAGER_COPIES / 2);
    /** 程序化同步分段高亮中（onPageSelected → selectTab），onTabSelected 不翻页 */
    private boolean suppressTabJump;

    /**
     * 各色斑明度分层（0=最深→中心小轨道；1=次深→中环围绕；2=最浅→四角边缘大轨道），
     * applyBlobPaletteImmediate 按颜色明度排序写入；默认 2（色板未就绪时走边缘大轨道）。
     */
    private final int[] blobDepth = new int[]{2, 2, 2, 2, 2};
    /** 各色斑的分层锚点（applyBlobPaletteImmediate 写入，buildBlobAnimations 读作轨道圆心） */
    private final float[][] blobAnchors = new float[BLOB_COUNT][];
    /** 文字护盾层：中央椭圆软渐变，保证信息组区域背景亮度恒定（白字可读） */
    private ImageView heroBlobShield;
    /** 切台涟漪动画（色板过渡时整场轻微放大回落） */
    private AnimatorSet blobRippleAnimator;

    /**
     * 取色结果（spec：主导色 + 面积占比，保持图标原色，按占比排序）。
     * base = 占比最大色（铺满基底）；colors = 其余色斑色（2~4 个）；
     * weights = 与 colors 一一对应的图标面积占比（最大者归一为 1），决定色斑面积。
     */
    private static class BlobPalette {
        final int base;
        final int[] colors;
        final float[] weights;

        BlobPalette(int base, int[] colors, float[] weights) {
            this.base = base;
            this.colors = colors;
            this.weights = weights;
        }
    }

    /** 当前色板（null = 尚未取色） */
    private BlobPalette blobPalette;
    /** 色板过渡动画（切台时旧色板 → 新色板 2 秒交叉淡化） */
    private ValueAnimator blobPaletteTransition;
    /** 取色缓存（key = 电台 UUID + 图标位图 generationId，同一电台二次播放不重复计算） */
    private final java.util.HashMap<String, BlobPalette> blobPaletteCache = new java.util.HashMap<>();
    /** 当前电台缓存 key（fullUpdate 时更新） */
    private String blobCacheKey = "";

    /** 背景流动动画：播放时各色斑独立游走（涌动），停止时冻结显示当前帧 */
    private AnimatorSet heroFlowAnimator;
    /** 色斑层透明度渐起/渐落动画（独立于游走，模拟参考稿的 rise/fall） */
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
                    case PlayerService.PLAYER_SERVICE_BOUND: {
                        // 服务（重）绑定完成：改语言/主题等 Activity recreate 后
                        // PlayerServiceUtil 异步重绑，此前的 fullUpdate 查询播放状态
                        // 得到 Idle（服务未连）导致 Hero 游走动画不建且按钮/行状态
                        // 失真；绑定完成即全量刷新补齐
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
        heroBgBlobs = stationInfoPage.findViewById(R.id.heroBgBlobs);
        heroBlobBase = stationInfoPage.findViewById(R.id.heroBlobBase);
        heroBlobViews = new ImageView[]{
                stationInfoPage.findViewById(R.id.heroBlob1),
                stationInfoPage.findViewById(R.id.heroBlob2),
                stationInfoPage.findViewById(R.id.heroBlob3),
                stationInfoPage.findViewById(R.id.heroBlob4),
                stationInfoPage.findViewById(R.id.heroBlob5),
        };
        // 整层毛玻璃（RenderEffect）已移除：它会限制在容器尺寸的离屏 layer 里，
        // 色斑越出容器的部分在 layer 边界被硬截——四边出现横/竖截断线（用户实测
        // 截图踩坑，且斑越大越明显）。柔焦改由色斑自身的长尾径向渐隐叠加近似
        // （无 blur 版观感已在低版本设备长期验证）。作为补偿，把从色斑容器到窗口
        // 的整条祖先链裁切全部放开：色斑可一路画出容器/信息页/pager，唯一裁切
        // 发生在窗口边界 = 屏幕物理边界（屏外，不可见）——可视区内再无截断线
        releaseClipUpToWindow(heroBgBlobs);
        // 游走动画自愈兜底：fullUpdate 几乎总在首次布局前运行（onCreateView→init），
        // 当时 heroBgBlobs 宽高为 0，updateHeroFlow 会提前 return「下次 fullUpdate 再建」；
        // 而布局完成后若播放状态无变化（无 STATE/META 广播），就永远等不到那个
        // 「下次 fullUpdate」——改语言/改主题等导致 Activity recreate 后动画永久
        // 停止正是这个时序。此处在每次布局完成时按当前播放状态补齐渲染与游走。
        heroBgBlobs.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    // 左右外扩（消灭左右竖条）：整场漂移（±2.5% 视口）会把容器
                    // 左右边平进屏幕，露出一侧「容器外」的透明区——被截出随漂移
                    // 交替涨落的竖条（左条最大时右条消失、反之亦然）。容器左右各
                    // 外扩一个漂移幅度+余量后，平移极限时边缘恒在屏外。上下无需
                    // 外扩：基底垂直渐隐与色斑垂直约束已保证上下边缘 alpha 归零。
                    final View visParent = (View) heroBgBlobs.getParent();
                    if (visParent != null && visParent.getWidth() > 0
                            && heroBgBlobs.getLayoutParams()
                                    instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams mlp =
                                (ViewGroup.MarginLayoutParams) heroBgBlobs.getLayoutParams();
                        final int want = Math.round(visParent.getWidth() * 0.025f) + 8;
                        if (mlp.leftMargin != -want || mlp.rightMargin != -want) {
                            mlp.leftMargin = -want;
                            mlp.rightMargin = -want;
                            heroBgBlobs.setLayoutParams(mlp); // 触发 relayout 后再进本回调即收敛
                            return;
                        }
                    }
                    if (heroBgBlobs.getWidth() <= 0 || heroBgBlobs.getHeight() <= 0
                            || PlayerServiceUtil.getPlayerState() != PlayState.Playing) {
                        return;
                    }
                    boolean reduceMotion = Settings.Global.getFloat(
                            requireContext().getContentResolver(),
                            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
                    if (reduceMotion) {
                        return;
                    }
                    if (blobPalette != null && heroBlobViews[0].getLayoutParams().width <= 0) {
                        // 色板就绪但布局前未能渲染：按实际视口补一次
                        applyBlobPaletteImmediate(blobPalette);
                    }
                    applyHeroShield(); // 布局就绪后按实际视口校正护盾渐变半径
                    if (heroFlowAnimator != null) {
                        if (!heroFlowAnimator.isRunning()) {
                            heroFlowAnimator.start();
                        }
                        return;
                    }
                    buildBlobAnimations();
                    if (heroFlowAnimator != null) {
                        // alpha 交给已在运行的淡入动画（fullUpdate 的 rise），不在此干扰
                        heroFlowAnimator.start();
                    }
                });
        heroLogoCard = stationInfoPage.findViewById(R.id.heroLogoCard);
        heroTopSpacer = stationInfoPage.findViewById(R.id.heroTopSpacer);
        heroStationName = stationInfoPage.findViewById(R.id.heroStationName);
        heroNowPlaying = stationInfoPage.findViewById(R.id.heroNowPlaying);
        heroNowRecording = stationInfoPage.findViewById(R.id.heroNowRecording);
        heroBlobShield = stationInfoPage.findViewById(R.id.heroBlobShield);
        applyHeroShield();
        heroMetaChips = stationInfoPage.findViewById(R.id.heroMetaChips);
        statBufferChip = stationInfoPage.findViewById(R.id.statBufferChip);
        statTrafficChip = stationInfoPage.findViewById(R.id.statTrafficChip);
        statRow = stationInfoPage.findViewById(R.id.statRow);
        layoutStationNoteRow = stationInfoPage.findViewById(R.id.layoutStationNoteRow);
        textViewStationNote = stationInfoPage.findViewById(R.id.textViewStationNote);

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
                int groupBottom = statRow.getBottom();
                if (heroNowPlaying != null && heroNowPlaying.getVisibility() == View.VISIBLE) {
                    groupBottom = heroNowPlaying.getBottom();
                }
                if (heroNowRecording != null && heroNowRecording.getVisibility() == View.VISIBLE) {
                    groupBottom = heroNowRecording.getBottom();
                }
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

        // 状态胶囊与元数据胶囊（TagsView）样式统一：同底色、同圆角、同字号、
        // 同 20sp 高、无描边；保留行首图标（时钟/流量，白色与文字同调）帮助辨识
        // 数值使用等宽（tabular）数字，每秒刷新时数字步进不跳动（API21+ 生效）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String tnum = "tnum";
            statBufferChip.setFontFeatureSettings(tnum);
            statTrafficChip.setFontFeatureSettings(tnum);
        }

        // 行首图标：时钟（缓冲）与上下箭头（流量），白色 85% 与胶囊文字同调
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

        // 「正在录音」行首红色圆点（录音警示色，尺寸/间距与呼吸点一致）
        GradientDrawable recDot = new GradientDrawable();
        recDot.setShape(GradientDrawable.OVAL);
        recDot.setColor(0xFFE53935);
        recDot.setSize(dp(7), dp(7));
        heroNowRecording.setCompoundDrawablesRelativeWithIntrinsicBounds(recDot, null, null, null);
        heroNowRecording.setCompoundDrawablePadding(dp(6));

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
        // 无限循环：初始落在中心副本区的「电台信息」页，两侧各留 3 份完整副本，
        // 「电台信息」继续右滑到「录音列表」、「录音列表」继续左滑到「电台信息」
        pagerContent.setCurrentItem(PAGER_CENTER_BASE, false);

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

                // 用户要求：点击录音不再跳转录音列表，停留当前页——信息页通过
                // 「正在录音」行（正在播放行下方）呈现录音状态，录音列表页仍有录音块
                updateRunningRecording();
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
        filter.addAction(PlayerService.PLAYER_SERVICE_BOUND);

        
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
            // 色斑取色缓存 key（spec：电台 ID + 图标 URL；此处用 UUID + 后续位图
            // generationId 组成稳定 key，同一电台图标不变时不重复计算）
            blobCacheKey = station.StationUuid;
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

        // Hero 背景动态层：仅真正播放（Playing）时显示涌动，暂停（Paused）与
        // 停止（Idle）都淡出恢复静态模糊图标背景（用户要求暂停也回静态）；
        // 「正在播放」行与状态胶囊仍按 isPlaying() 处理（暂停即隐藏/回退标签名）
        PlayState state = PlayerServiceUtil.getPlayerState();
        updateHeroFlow(state == PlayState.Playing);

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
            // 「正在录音」行与录音块共用同一录音状态，文件名同源同命名规则
            if (heroNowRecording != null) {
                heroNowRecording.setText(getString(
                        R.string.player_hero_now_recording, recordingInfo.getFileName()));
            }
        } else {
            imgRecordingIcon.clearAnimation();
        }
        updateRecordingRowVisibility();
    }

    /**
     * 录音状态呈现（用户要求）：录音行与录音块是同一录音状态的两种呈现——
     * 同显同隐、按分段互斥：信息页显示「正在录音」行（正在播放行下方），
     * 录音列表页显示录音块，其余页隐藏；切换标签即可看到不同呈现。
     * 显隐变化会触发外层全局布局回调，内容 pager 高度随之自动重算。
     */
    private void updateRecordingRowVisibility() {
        if (segmentTabs == null || blockRecording == null) {
            return;
        }
        final int position = segmentTabs.getSelectedTabPosition();
        blockRecording.setVisibility(
                position == 3 && recordingRowActive ? View.VISIBLE : View.GONE);
        if (heroNowRecording != null) {
            heroNowRecording.setVisibility(
                    position == 0 && recordingRowActive ? View.VISIBLE : View.GONE);
        }
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
        // 从原始图标（清晰原图，非模糊缓存）提取色板；同 key 缓存不重复计算（spec）
        final String cacheKey = blobCacheKey + "|" + source.getGenerationId();
        BlobPalette palette = blobPaletteCache.get(cacheKey);
        if (palette == null) {
            palette = extractBlobPalette(source);
            if (blobPaletteCache.size() > 8) {
                blobPaletteCache.clear(); // 简单上限防膨胀
            }
            blobPaletteCache.put(cacheKey, palette);
        }
        applyBlobPalette(palette);
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
        // 无图标电台：色板退化为强调色系衍生（基底 = 强调色，色斑 = 邻近色变体）
        applyBlobPalette(extractPaletteFromAccent(accent));
    }

    /**
     * 取色：清晰原图 64×64 降采样 → 27 桶量化聚类（彩色 = 色相 8 × 明度 3，
     * 中性 = 黑/灰/白 3 桶单列并以绝对色度判别，防暗部噪声串色）→ 均值色 +
     * 面积占比（保持图标原色，不提亮不增饱和）→ 按占比降序做「可辨性」筛选
     * 取最多 5 个主导色（防同系深浅占满名额），占比最大者为基底铺满色，
     * 其余（最多 4 个）为色斑色且色斑面积按各自占比分配。
     */
    private BlobPalette extractBlobPalette(Bitmap source) {
        final int accent = Utils.getThemeColor(requireContext(), R.attr.presetAccentColor);
        if (source == null || source.isRecycled() || source.getWidth() <= 0) {
            return extractPaletteFromAccent(accent);
        }

        Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(source, 64, 64, true);
        } catch (IllegalArgumentException e) {
            return extractPaletteFromAccent(accent);
        }

        // 27 桶 = 彩色（色相 8 × 明度 3）+ 中性（暗黑/中灰/亮白 3 桶）。
        // 中性必须单列：白/灰/黑的 HSV 色相无意义（恒为 0），混进彩色桶会被
        // 平均进红橙色——此前图标里的白/黄/黑全部丢失、只剩暖橙棕正是这个原因。
        final int[] count = new int[27];
        final int[] sumR = new int[27], sumG = new int[27], sumB = new int[27];
        int[] pixels = new int[64 * 64];
        small.getPixels(pixels, 0, 64, 0, 0, 64, 64);
        for (int px : pixels) {
            if (Color.alpha(px) < 64) {
                continue;
            }
            float[] hsv = new float[3];
            Color.colorToHSV(px, hsv);
            int valBucket = hsv[2] < 0.35f ? 0 : (hsv[2] < 0.7f ? 1 : 2);
            int bucket;
            // 中性判定用绝对色度 S×V：暗部像素 V 极低时 S（相对值）虚高且色相
            // 全是噪声（如 RGB(30,35,55) S=0.45 实为深灰蓝黑），若按 S 判色会
            // 被提亮增饱和放大成图标里不存在的蓝紫品红
            if (hsv[1] < 0.22f || hsv[1] * hsv[2] < 0.16f || (hsv[2] > 0.93f && hsv[1] < 0.45f)) {
                bucket = 24 + valBucket; // 中性：黑 / 灰 / 白（含暖白高光、暗部噪声）
            } else {
                int hueBucket = Math.min(7, (int) (hsv[0] / 45f));
                bucket = hueBucket * 3 + valBucket;
            }
            sumR[bucket] += Color.red(px);
            sumG[bucket] += Color.green(px);
            sumB[bucket] += Color.blue(px);
            count[bucket]++;
        }

        // 桶按像素数（面积占比）降序
        Integer[] order = new Integer[27];
        for (int i = 0; i < 27; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> count[b] - count[a]);

        // 第一轮：按占比取最多 5 个主导色（1 基底 + 最多 4 色斑色），要求与
        // 已选色「可辨」（色相或明度拉开距离），避免名额被同系深浅暖棕挤占；
        // 同时记录各色桶像素数，供色斑按占比分配面积
        java.util.ArrayList<Integer> colors = new java.util.ArrayList<>();
        java.util.ArrayList<Long> pickedCounts = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> skipped = new java.util.ArrayList<>();
        java.util.ArrayList<Long> skippedCounts = new java.util.ArrayList<>();
        for (int i = 0; i < 27 && colors.size() < 5; i++) {
            int b = order[i];
            if (count[b] == 0) {
                break;
            }
            int c = Color.rgb(sumR[b] / count[b], sumG[b] / count[b], sumB[b] / count[b]);
            boolean distinct = true;
            for (int picked : colors) {
                if (!isDistinctColor(c, picked)) {
                    distinct = false;
                    break;
                }
            }
            if (distinct) {
                colors.add(c);
                pickedCounts.add((long) count[b]);
            } else {
                skipped.add(c);
                skippedCounts.add((long) count[b]);
            }
        }
        // 第二轮：可辨色不足时放宽，用被跳过的高占比桶补齐（保证至少基底+3 色斑）
        for (int si = 0; si < skipped.size() && colors.size() < 4; si++) {
            colors.add(skipped.get(si));
            pickedCounts.add(skippedCounts.get(si));
        }

        // 仍不足（基底 + 3 色斑）时用已有色的明度/饱和度变体补齐（禁止引入图标外色相），
        // 变体沿用源色的最小占比
        long minCount = 1L;
        for (long pc : pickedCounts) {
            minCount = Math.min(minCount, pc);
        }
        while (colors.size() < 4) {
            int src = colors.isEmpty() ? accent : colors.get(colors.size() - 1);
            float[] hsv = new float[3];
            Color.colorToHSV(src, hsv);
            hsv[1] = Math.min(1f, hsv[1] + 0.12f);
            hsv[2] = (colors.size() % 2 == 0)
                    ? Math.max(0.35f, hsv[2] - 0.15f)
                    : Math.min(0.95f, hsv[2] + 0.15f);
            colors.add(Color.HSVToColor(hsv));
            pickedCounts.add(minCount);
        }

        // 色斑面积按占比分配：以最大占比归一（最大 = 1），占比小的色斑明显更小
        long wMax = 1L;
        for (int i = 1; i < colors.size(); i++) {
            wMax = Math.max(wMax, pickedCounts.get(i));
        }
        float[] weights = new float[colors.size() - 1];
        for (int i = 1; i < colors.size(); i++) {
            weights[i - 1] = pickedCounts.get(i) / (float) wMax;
        }

        return new BlobPalette(colors.get(0), toArray(colors, 1), weights);
    }

    /**
     * 两色是否「可辨」（主导色挑选用）：中性 vs 彩色必可辨；同为中性比明度差；
     * 同为彩色比环形色相距离或明度差——防止橙/棕/深橙这类同系色占满名额。
     */
    private static boolean isDistinctColor(int c1, int c2) {
        float[] a = new float[3], b = new float[3];
        Color.colorToHSV(c1, a);
        Color.colorToHSV(c2, b);
        final boolean na = a[1] < 0.22f || a[1] * a[2] < 0.16f;
        final boolean nb = b[1] < 0.22f || b[1] * b[2] < 0.16f;
        if (na != nb) {
            return true;
        }
        if (na) {
            return Math.abs(a[2] - b[2]) >= 0.30f;
        }
        float d = Math.abs(a[0] - b[0]) % 360f;
        if (d > 180f) {
            d = 360f - d;
        }
        return d >= 18f || Math.abs(a[2] - b[2]) >= 0.32f;
    }

    /** 无图标电台的色板：基底 = 强调色，色斑 = 邻近明度/色相变体（保证可辨差异）。 */
    private BlobPalette extractPaletteFromAccent(int accent) {
        float[] hsv = new float[3];
        Color.colorToHSV(accent, hsv);
        int c1 = ColorUtils.blendARGB(accent, Color.WHITE, 0.25f);
        float[] h2 = {hsv[0] + 28f, hsv[1], Math.max(0.4f, hsv[2] - 0.1f)};
        int c2 = Color.HSVToColor(h2);
        float[] h3 = {hsv[0] - 28f, hsv[1], Math.min(0.95f, hsv[2] + 0.1f)};
        int c3 = Color.HSVToColor(h3);
        return new BlobPalette(accent,
                new int[]{c1, c2, c3}, new float[]{0.5f, 0.35f, 0.22f});
    }

    private int[] toArray(java.util.ArrayList<Integer> list, int from) {
        int[] arr = new int[list.size() - from];
        for (int i = from; i < list.size(); i++) {
            arr[i - from] = list.get(i);
        }
        return arr;
    }

    /**
     * 应用色板到动态层（spec：基底 = 占比最大色铺满 60~75% 透明度；色斑 4~6 个，
     * 尺寸 = 视口短边 60%~140%、错落四角与中部）。
     * 换色板（切台/图标刷新）时若动态层可见，做 2 秒 ArgbEvaluator 交叉淡化，
     * 不闪断、不重排布局。
     */
    private void applyBlobPalette(BlobPalette newPalette) {
        if (heroBgBlobs == null) {
            return;
        }
        if (heroBgBlobs.getWidth() <= 0 || heroBgBlobs.getHeight() <= 0) {
            // 尚未布局：记下色板，updateHeroFlow 首次启动时按已布局尺寸重新应用
            blobPalette = newPalette;
            return;
        }

        final BlobPalette old = blobPalette;
        blobPalette = newPalette;
        if (old != null && heroBgBlobs.getAlpha() > 0.05f && blobPaletteTransition == null) {
            // 不闪断、不重排布局；过渡开始时整场做一次极轻微放大回落
            // 的「涟漪」，把「歌换了」变成一次视觉呼吸（一首歌至多一次）
            startHeroBlobRipple();
            blobPaletteTransition = ValueAnimator.ofFloat(0f, 1f);
            blobPaletteTransition.setDuration(2000);
            blobPaletteTransition.setInterpolator(new LinearInterpolator());
            blobPaletteTransition.addUpdateListener(animation ->
                    renderBlobPalette(mixPalette(old, newPalette, (float) animation.getAnimatedValue())));
            blobPaletteTransition.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    blobPaletteTransition = null;
                }
            });
            blobPaletteTransition.start();
        } else {
            applyBlobPaletteImmediate(newPalette);
        }
    }

    /** 两套色板按 t 混合（ArgbEvaluator 逐色插值，占比同步插值）。 */
    private BlobPalette mixPalette(BlobPalette a, BlobPalette b, float t) {
        ArgbEvaluator ev = new ArgbEvaluator();
        int base = (int) ev.evaluate(t, a.base, b.base);
        int n = Math.max(a.colors.length, b.colors.length);
        int[] mixed = new int[n];
        float[] mw = new float[n];
        for (int i = 0; i < n; i++) {
            int ca = a.colors[Math.min(i, a.colors.length - 1)];
            int cb = b.colors[Math.min(i, b.colors.length - 1)];
            mixed[i] = (int) ev.evaluate(t, ca, cb);
            float wa = a.weights[Math.min(i, a.weights.length - 1)];
            float wb = b.weights[Math.min(i, b.weights.length - 1)];
            mw[i] = wa + (wb - wa) * t;
        }
        return new BlobPalette(base, mixed, mw);
    }

    /** 立即把色板渲染到基底与色斑（含按视口短边分配尺寸、错落分布）。 */
    private void applyBlobPaletteImmediate(BlobPalette palette) {
        if (heroBlobBase == null || heroBlobViews == null
                || heroBgBlobs.getWidth() <= 0 || heroBgBlobs.getHeight() <= 0) {
            return;
        }
        // 基底：主色铺满，低透明度（~43%）——压低后色斑之间的对比更强烈，
        // 均匀底色感更弱、涌动感更强
        // 基底垂直渐隐（上下各 ~14% 渐变到全透明）：纯色矩形会在信息页上下边缘
        // 被截出恒定横线，渐隐后边缘 alpha 归零、与相邻区域无缝延续
        heroBlobBase.setBackground(makeBaseGradient(darkenBase(palette.base)));

        final int shortSide = Math.min(heroBgBlobs.getWidth(), heroBgBlobs.getHeight());
        final int W = heroBgBlobs.getWidth();
        final int H = heroBgBlobs.getHeight();

        // 错落锚点（相对容器比例）：四角内收 + 中部。y 取 0.36/0.64（水平仍贴角）：
        // 色斑可见圆不得越信息页上下边（0.42×size 约束），锚点越靠边可用尺寸越小，
        // 内移换取边缘斑保持较大面积
        final float[][] corners = {
                {0.26f, 0.36f}, {0.74f, 0.33f}, {0.72f, 0.64f}, {0.28f, 0.67f}
        };

        // 亮暗分层定位（用户要求）：按颜色明度从暗到浅排三层——最深的 1 枚在屏幕
        // 最中间附近游动（小轨道），次深的 2 枚围绕最深的在中环游动（四角锚点向
        // 中心收缩 55%），最浅的 2 枚占四角边缘锚点大轨道扫掠边缘；明度对角分布，
        // 避免亮斑顶托中央白色前景字
        int colorCount = palette.colors.length;
        // 排序依据用 Rec601 感知亮度而非 HSV V：同样 V 的蓝色看起来比黄色暗得多，
        // 按 V 分层会让蓝紫系图标的「最深色」实际观感发亮——感知亮度才符合人眼
        final float[] blobV = new float[BLOB_COUNT];
        Integer[] byV = new Integer[BLOB_COUNT];
        for (int i = 0; i < BLOB_COUNT; i++) {
            byV[i] = i;
            final int c = palette.colors[i % colorCount];
            blobV[i] = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
        }
        java.util.Arrays.sort(byV, (a, b) -> Float.compare(blobV[a], blobV[b]));
        // 槽位：rank0 最深→中心；rank1/2 次深→对角两角的内环收缩位；rank3/4 最浅→另两对角边缘
        final int[][] cornerSlot = {{0, 2}, {1, 3}}; // {中环用角}, {边缘用角}
        int midIdx = 0, edgeIdx = 0;
        for (int rank = 0; rank < BLOB_COUNT; rank++) {
            final int blobIdx = byV[rank];
            final float[] slot;
            if (rank == 0) {
                slot = new float[]{0.50f, 0.50f};
                blobDepth[blobIdx] = 0;
            } else if (rank <= 2) {
                float[] c = corners[cornerSlot[0][midIdx++ % 2]];
                slot = new float[]{0.5f + (c[0] - 0.5f) * 0.55f, 0.5f + (c[1] - 0.5f) * 0.55f};
                blobDepth[blobIdx] = 1;
            } else {
                slot = corners[cornerSlot[1][edgeIdx++ % 2]];
                blobDepth[blobIdx] = 2;
            }
            blobAnchors[blobIdx] = slot;
        }

        for (int i = 0; i < BLOB_COUNT; i++) {
            final ImageView blob = heroBlobViews[i];
            // 色斑颜色循环取用（5 斑 / 3~4 色，相邻斑错开）
            final int ci = i % colorCount;
            final int color = palette.colors[ci];
            // 面积按占比分配 + 按分层收紧上限（用户校准）：中央小而深、边缘大而浅，
            // 中带自然留出「安静区」，与边缘涌动形成动静对比
            float wn = palette.weights.length > 0
                    ? palette.weights[Math.min(ci, palette.weights.length - 1)] : 1f;
            final float lo, hi;
            if (blobDepth[i] == 0) {
                lo = 0.52f; hi = 1.04f;
            } else if (blobDepth[i] == 1) {
                lo = 0.58f; hi = 1.27f;
            } else {
                lo = 0.63f; hi = 1.61f;
            }
            // 垂直自适应（消灭上下边横线的核心）：色斑可见圆（含渐变长尾，半径
            // ≈0.42×size）不得越过信息页上/下边——哪怕 α 很低的长尾越出，也会被
            // 顶栏/控制条的分界截出随涌动涨落的微横线（用户实测）。size 按锚点到
            // 上/下边的垂直余量收紧；水平方向不约束：信息页左右边 = 屏幕边，屏外
            // 裁切不可见。
            float ay = Math.max(0f, Math.min(H, blobAnchors[i][1] * H));
            float vLimit = Math.min(ay, H - ay) / 0.42f;
            int size = Math.round(Math.min(shortSide * (lo + (hi - lo) * wn), vLimit));
            float rCore = 0.42f * size;
            ay = Math.max(rCore, Math.min(H - rCore, ay));
            ViewGroup.LayoutParams lp = blob.getLayoutParams();
            lp.width = size;
            lp.height = size;
            blob.setLayoutParams(lp);
            // 初始位置 = 分层锚点（layout_gravity=center 基础上平移）；y 用安全区内推值
            blob.setTranslationX((blobAnchors[i][0] - 0.5f) * W);
            blob.setTranslationY(ay - 0.5f * H);
            blobAnchors[i][1] = ay / H; // 回写内推后的锚点，buildBlobAnimations 轨道同源
            blob.setImageDrawable(makeBlobDrawable(color, size));
        }
    }

    /** 渲染混合色板（过渡动画每帧调用；只更新颜色不重排布局）。 */
    private void renderBlobPalette(BlobPalette palette) {
        if (heroBlobBase == null || heroBlobViews == null || heroBgBlobs.getWidth() <= 0) {
            return;
        }
        // 基底垂直渐隐（上下各 ~14% 渐变到全透明）：纯色矩形会在信息页上下边缘
        // 被截出恒定横线，渐隐后边缘 alpha 归零、与相邻区域无缝延续
        heroBlobBase.setBackground(makeBaseGradient(darkenBase(palette.base)));
        int colorCount = palette.colors.length;
        for (int i = 0; i < BLOB_COUNT; i++) {
            int color = palette.colors[i % colorCount];
            int size = heroBlobViews[i].getLayoutParams().width;
            heroBlobViews[i].setImageDrawable(makeBlobDrawable(color, size));
        }
    }

    /**
     * 单枚色斑 drawable（用户要求：像衣服染色的不规则斑块，透过毛玻璃是模糊一片）：
     * 两枚错位径向渐变圆叠加（LayerDrawable）+ 三段长尾渐隐，边缘无锐利切线。
     */
    private android.graphics.drawable.Drawable makeBlobDrawable(int color, int sizePx) {
        // 每层渐变半径必须 ≤ 圆心到 view 四边的最小距离（错位圆心离哪边近就用哪边）：
        // 保证 view 边缘处 alpha 严格归零——色斑 view 越出容器边界的部分全是透明
        // 像素，被容器边界裁切也不留横/竖直线痕迹（用户实测截图踩坑：半径 0.60
        // 超出半宽 0.50，边缘 α≈30 被裁出明显直线）
        GradientDrawable mainBlob = makeBlobGradient(color, 0.42f, 0.42f, sizePx, 0.42f);
        GradientDrawable subBlob = makeBlobGradient(
                ColorUtils.blendARGB(color, Color.BLACK, 0.14f), 0.64f, 0.63f, sizePx, 0.35f);
        return new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{subBlob, mainBlob});
    }

    /**
     * 单层径向渐变：四段长尾渐隐（中心近实色 → 40% 半透 → 75% 微痕 → 边缘全透明）。
     * 核心透明度拉高到 235，重叠处色彩浓艳（对齐视频），长尾保证无锐利边界。
     */
    private GradientDrawable makeBlobGradient(
            int color, float centerX, float centerY, int sizePx, float radiusRatio) {
        GradientDrawable blob = new GradientDrawable();
        blob.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        blob.setGradientCenter(centerX, centerY);
        blob.setColors(new int[]{
                ColorUtils.setAlphaComponent(color, 235),
                ColorUtils.setAlphaComponent(color, 135),
                ColorUtils.setAlphaComponent(color, 45),
                Color.TRANSPARENT
        }, new float[]{0f, 0.40f, 0.75f, 1f});
        // 半径取圆心到最近边的距离以内（radiusRatio），view 边缘 alpha 严格归零
        blob.setGradientRadius(sizePx * radiusRatio);
        return blob;
    }

    /**
     * Hero 背景动态化：仅真正播放（Playing）时动态色斑层在静态模糊图标上
     * 淡入（约 1.5s）并持续轨道涌动；暂停（Paused）与停止（Idle）都淡出
     * （1.4s）后冻结，回到静态模糊图标背景。
     * 系统开启「减少动态效果」（动画时长缩放 = 0）时退化为静态渐变（不启动动画）。
     */
    private void updateHeroFlow(boolean playing) {
        if (heroBgBlobs == null || !isAdded()) {
            return;
        }
        // spec 加分项：系统「减少动态效果」→ 静态渐变（色斑层静止显示，不启动游走）
        boolean reduceMotion = Settings.Global.getFloat(requireContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;

        // 透明度渐起/渐落（rise 1.5s / fall 1.4s，spec）
        float targetAlpha = playing ? 1f : 0f;
        if (heroFlowAlphaAnimator != null && heroFlowAlphaAnimator.isRunning()) {
            heroFlowAlphaAnimator.cancel();
        }
        heroFlowAlphaAnimator = ValueAnimator.ofFloat(heroBgBlobs.getAlpha(), targetAlpha);
        heroFlowAlphaAnimator.setDuration(playing ? 1500 : 1400);
        heroFlowAlphaAnimator.addUpdateListener(animation ->
                heroBgBlobs.setAlpha((float) animation.getAnimatedValue()));
        if (!playing) {
            // 落回 0 后冻结游走：背景完全静止显示底层模糊电台图标
            heroFlowAlphaAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(android.animation.Animator animation) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (!cancelled) {
                        stopHeroFlowDrift();
                    }
                }
            });
        }
        heroFlowAlphaAnimator.start();

        if (playing && !reduceMotion) {
            if (heroBgBlobs.getWidth() <= 0) {
                return; // 尚未布局，下次 fullUpdate 再建
            }
            if (blobPalette != null && heroBlobViews[0].getLayoutParams().width <= 0) {
                // 色板在布局完成前就已取出（updateHeroBackgroundFromArt 早于布局）：
                // 此处补一次按已布局尺寸的渲染（锚点/尺寸依赖视口大小）
                applyBlobPaletteImmediate(blobPalette);
            }
            if (heroFlowAnimator != null) {
                if (!heroFlowAnimator.isRunning()) {
                    heroFlowAnimator.start(); // 停止冻结后重新起播，动画随淡入重启
                }
                return; // 已在游走则不重启，避免每次 fullUpdate 都跳回轨道起点
            }
            buildBlobAnimations();
            if (heroFlowAnimator != null) {
                heroFlowAnimator.start();
            }
        }
    }

    /**
     * 构建色斑轨道游走动画（色斑沿大椭圆轨道匀速连续扫动，永不停顿减速）：
     * 平移 = 椭圆轨道 Path 属性动画（GPU 合成层、线性匀速、无限循环无缝衔接），
     * 各斑轨道半径/周期/方向/起始相位全部错开（周期 13~21 秒，速度经用户校准）；
     * 缩放 0.78~1.32 呼吸（10~16s ease-in-out 往复），尺寸变化 + 相向轨道使
     * 色彩互相赶超、穿插覆盖；blur 只创建一次不动它。
     */
    private void buildBlobAnimations() {
        final int heroW = heroBgBlobs.getWidth();
        final int heroH = heroBgBlobs.getHeight();
        if (heroW <= 0 || heroH <= 0) {
            return; // 尚未布局，下次 fullUpdate 再建
        }

        // 各斑轨道参数：{半径X(视口宽比例), 半径Y(视口高比例), 轨道周期ms,
        //                起始角deg, 方向(+1顺时针/-1逆时针), 缩放min, 缩放max, 缩放周期ms}
        // 按明度分层三档轨道池（用户要求）：最深档 = 中心小轨道游动；次深档 =
        // 中环围绕；最浅档 = 四角大轨道扫掠边缘。每条轨道都带旋转角（各斑错开、
        // 均非 0°/90° 轴对齐）——轴对齐椭圆会在拱点处呈现水平/垂直直线扫过感
        // （用户反馈的「明显横竖线条」），旋转后轨迹斜向错落。字段：
        // {半径X, 半径Y, 轨道周期ms, 起始角deg, 方向(±1), 缩放min, 缩放max, 缩放周期ms, 轨道旋转deg}
        final float[][] darkSpecs = {
                {0.10f, 0.13f, 18000,  45f,  1f, 0.82f, 1.24f, 16000,  30f},
                {0.12f, 0.15f, 21000, 225f, -1f, 0.80f, 1.28f, 14000, -45f},
        };
        final float[][] midSpecs = {
                {0.16f, 0.13f, 16600, 150f, -1f, 0.85f, 1.25f, 12000,  70f},
                {0.14f, 0.17f, 19000, 330f,  1f, 0.84f, 1.26f, 15000, -25f},
        };
        final float[][] brightSpecs = {
                {0.28f, 0.22f, 14000,   0f,  1f, 0.80f, 1.30f, 13000,  15f},
                {0.24f, 0.28f, 13000, 270f, -1f, 0.85f, 1.28f, 14000, -60f},
        };
        int[] poolIdx = new int[3];
        TimeInterpolator easeInOut = new AccelerateDecelerateInterpolator();
        java.util.List<Animator> anims = new java.util.ArrayList<>();
        for (int i = 0; i < BLOB_COUNT; i++) {
            final View blob = heroBlobViews[i];
            final int depth = Math.max(0, Math.min(2, blobDepth[i]));
            final float[][] pool = depth == 0 ? darkSpecs : depth == 1 ? midSpecs : brightSpecs;
            final float[] s = pool[poolIdx[depth]++ % pool.length];
            // 轨道椭圆：圆心 = 分层锚点（applyBlobPaletteImmediate 已按明度写入）
            final float[] anchor = blobAnchors[i] != null
                    ? blobAnchors[i] : new float[]{0.5f, 0.5f};
            final float cx = (anchor[0] - 0.5f) * heroW;
            final float cy = (anchor[1] - 0.5f) * heroH;
            final float rx = s[0] * heroW;
            // 垂直轨道半径同步安全化：圆心全程 ±ry 不得让可见圆（≈0.42×size，含
            // 长尾）越过信息页上/下边——否则游走中仍会周期性截出微横线
            final float rCore = 0.42f * blob.getLayoutParams().width;
            final float cyAbs = 0.5f * heroH + cy;
            final float ry = Math.max(0.03f * heroH, Math.min(s[1] * heroH,
                    Math.min(cyAbs - rCore, heroH - cyAbs - rCore)));
            Path orbit = makeOrbitPath(cx, cy, rx, ry, s[3], s[4] > 0f, s[8]);

            ObjectAnimator travel = ObjectAnimator.ofFloat(blob,
                    View.TRANSLATION_X, View.TRANSLATION_Y, orbit);
            travel.setDuration((long) s[2]);
            travel.setInterpolator(new LinearInterpolator()); // 匀速绕轨，循环点无缝
            travel.setRepeatCount(ValueAnimator.INFINITE);
            travel.setRepeatMode(ValueAnimator.RESTART);

            ObjectAnimator sx = ObjectAnimator.ofFloat(blob, View.SCALE_X, s[5], s[6]);
            sx.setDuration((long) s[7]);
            ObjectAnimator sy = ObjectAnimator.ofFloat(blob, View.SCALE_Y, s[6], s[5]); // 与 X 反相位
            sy.setDuration((long) s[7]);
            sx.setInterpolator(easeInOut);
            sy.setInterpolator(easeInOut);
            sx.setRepeatCount(ValueAnimator.INFINITE);
            sx.setRepeatMode(ValueAnimator.REVERSE);
            sy.setRepeatCount(ValueAnimator.INFINITE);
            sy.setRepeatMode(ValueAnimator.REVERSE);

            anims.add(travel);
            anims.add(sx);
            anims.add(sy);
        }

        // 整场极慢漂移（准周期化，耐看性关键）：所有色斑作为一个场以质数周期
        // 两轴缓慢游移 ±2~2.5% 视口，与各斑轨道周期叠加后整体重复周期达分钟级，
        // 画面「永不重样」但依然平滑（translation 与各斑轨道分属不同 View，无冲突）
        ObjectAnimator driftX = ObjectAnimator.ofFloat(heroBgBlobs, View.TRANSLATION_X,
                -0.025f * heroW, 0.025f * heroW);
        driftX.setDuration(53000L);
        driftX.setRepeatCount(ValueAnimator.INFINITE);
        driftX.setRepeatMode(ValueAnimator.REVERSE);
        driftX.setInterpolator(easeInOut);
        ObjectAnimator driftY = ObjectAnimator.ofFloat(heroBgBlobs, View.TRANSLATION_Y,
                -0.02f * heroH, 0.02f * heroH);
        driftY.setDuration(41000L);
        driftY.setRepeatCount(ValueAnimator.INFINITE);
        driftY.setRepeatMode(ValueAnimator.REVERSE);
        driftY.setInterpolator(easeInOut);
        anims.add(driftX);
        anims.add(driftY);

        // 各斑 alpha 涨落（存在感呼吸）：质数周期错开 + 初值差异化（兼作入场错峰），
        // 色斑有「出墨/收墨」的节奏，画面密度不再恒定
        final float[][] alphaSpecs = {
                {0.86f, 1.00f, 23000},
                {0.90f, 1.00f, 31000},
                {0.84f, 0.98f, 43000},
                {0.88f, 1.00f, 29000},
                {0.86f, 0.99f, 37000},
        };
        for (int i = 0; i < BLOB_COUNT; i++) {
            heroBlobViews[i].setAlpha(alphaSpecs[i][0]); // 初值差异化 = 入场错峰
            ObjectAnimator fade = ObjectAnimator.ofFloat(heroBlobViews[i], View.ALPHA,
                    alphaSpecs[i][0], alphaSpecs[i][1]);
            fade.setDuration((long) (float) alphaSpecs[i][2]);
            fade.setRepeatCount(ValueAnimator.INFINITE);
            fade.setRepeatMode(ValueAnimator.REVERSE);
            fade.setInterpolator(easeInOut);
            anims.add(fade);
        }

        heroFlowAnimator = new AnimatorSet();
        heroFlowAnimator.playTogether(anims);
    }

    /**
     * 构建椭圆轨道 Path（四段三次贝塞尔近似，kappa 常数）：圆心 (cx,cy)、
     * 半轴 (rx,ry)、起始角 startDeg、cw 为顺时针（屏幕 y 向下，角度递增即顺时针）、
     * rotateDeg 为整条轨道绕圆心的旋转角（非 0°/90° 时轨迹斜向，消除轴对齐的
     * 水平/垂直扫过感）。路径首尾闭合，配合 RESTART 无限循环无缝衔接。
     */
    private static Path makeOrbitPath(float cx, float cy, float rx, float ry,
                                      float startDeg, boolean cw, float rotateDeg) {
        final double kappa = 0.5522847498307933;
        final double dir = cw ? 1.0 : -1.0;
        Path p = new Path();
        final double a0 = Math.toRadians(startDeg);
        p.moveTo(cx + rx * (float) Math.cos(a0), cy + ry * (float) Math.sin(a0));
        final double step = Math.toRadians(90.0) * dir;
        for (int q = 0; q < 4; q++) {
            final double ta = a0 + step * q;
            final double tb = ta + step;
            final float pax = cx + rx * (float) Math.cos(ta);
            final float pay = cy + ry * (float) Math.sin(ta);
            final float pbx = cx + rx * (float) Math.cos(tb);
            final float pby = cy + ry * (float) Math.sin(tb);
            // 端点切向：d/dθ (rx·cosθ, ry·sinθ) = (-rx·sinθ, ry·cosθ)，乘方向系数
            final float tax = -rx * (float) Math.sin(ta) * (float) dir;
            final float tay = ry * (float) Math.cos(ta) * (float) dir;
            final float tbx = -rx * (float) Math.sin(tb) * (float) dir;
            final float tby = ry * (float) Math.cos(tb) * (float) dir;
            p.cubicTo(
                    pax + (float) (kappa * tax), pay + (float) (kappa * tay),
                    pbx - (float) (kappa * tbx), pby - (float) (kappa * tby),
                    pbx, pby);
        }
        if (rotateDeg != 0f) {
            // 整条轨道绕圆心旋转：打破轴对齐，消除拱点处的水平/垂直直线扫过感
            android.graphics.Matrix rot = new android.graphics.Matrix();
            rot.postRotate(rotateDeg, cx, cy);
            p.transform(rot);
        }
        return p;
    }

    /** 停止并冻结背景平移动画（保留当前 translation，画面静止在当前帧）。 */
    private void stopHeroFlowDrift() {
        if (heroFlowAnimator != null) {
            heroFlowAnimator.cancel();
        }
    }

    /**
     * 切台涟漪：色板过渡开始时整场 1.0→1.045→1.0 缓慢放大回落（4s）——把
     * 「歌换了」变成一次克制的视觉呼吸；scale 与场漂移 translation 分属不同
     * 属性，无冲突。
     */
    private void startHeroBlobRipple() {
        if (heroBgBlobs == null) {
            return;
        }
        if (blobRippleAnimator != null) {
            blobRippleAnimator.cancel();
        }
        ObjectAnimator sx = ObjectAnimator.ofFloat(heroBgBlobs, View.SCALE_X, 1f, 1.045f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(heroBgBlobs, View.SCALE_Y, 1f, 1.045f, 1f);
        final long dur = 4000L;
        sx.setDuration(dur);
        sy.setDuration(dur);
        TimeInterpolator ease = new AccelerateDecelerateInterpolator();
        sx.setInterpolator(ease);
        sy.setInterpolator(ease);
        blobRippleAnimator = new AnimatorSet();
        blobRippleAnimator.playTogether(sx, sy);
        blobRippleAnimator.start();
    }

    /**
     * 基底色向深偏移（亮度压到 ≤0.32）并加实（α130）：暗基底让色斑「发光」而非
     * 「漂浮」，是高级深色 UI 的通行做法，白字对比同时受益。
     */
    private static int darkenBase(int base) {
        float[] hsv = new float[3];
        Color.colorToHSV(base, hsv);
        hsv[2] = Math.min(hsv[2], 0.32f);
        return ColorUtils.setAlphaComponent(Color.HSVToColor(hsv), 130);
    }

    /**
     * 全屏基底：垂直渐隐（上下各 ~14% 渐变到全透明）——纯色矩形会在信息页上下
     * 边缘被截出恒定横线，渐隐后边缘 alpha 归零、与相邻区域无缝延续。
     */
    private static android.graphics.drawable.Drawable makeBaseGradient(int baseColor) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        Color.TRANSPARENT,
                        baseColor,
                        baseColor,
                        Color.TRANSPARENT
                });
        base.setColors(new int[]{
                Color.TRANSPARENT,
                baseColor,
                baseColor,
                Color.TRANSPARENT
        }, new float[]{0f, 0.14f, 0.86f, 1f});
        return base;
    }

    /**
     * 从 startView 沿祖先链到窗口根，逐级关闭子 view 裁切（clipChildren=false）：
     * 色斑越出容器/信息页/pager 的部分得以一路画到窗口边界，唯一裁切发生在
     * 屏幕物理边界（屏外不可见）——这是消灭可视区内横/竖截断线的关键。
     * ViewPager 放开后横滑时相邻页内容会短暂越界绘制，视觉影响可忽略。
     */
    private static void releaseClipUpToWindow(View startView) {
        ViewGroup v = startView instanceof ViewGroup ? (ViewGroup) startView : null;
        while (v != null && v.getParent() instanceof ViewGroup) {
            v.setClipChildren(false);
            v = (ViewGroup) v.getParent();
        }
    }

    /**
     * 文字护盾层（可读性根治）：中央（55% 高度处，即信息组中心）最暗的椭圆径向
     * 软渐变、向外羽化至全透明——无论色斑如何涌动，中段信息组的背景亮度恒定。
     */
    private void applyHeroShield() {
        if (heroBlobShield == null) {
            return;
        }
        GradientDrawable shield = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x4D000000, 0x1A000000, 0x00000000});
        shield.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        shield.setGradientCenter(0.5f, 0.55f);
        final int w = heroBgBlobs.getWidth();
        final int h = heroBgBlobs.getHeight();
        shield.setGradientRadius(w > 0 && h > 0 ? Math.max(w, h) * 0.52f : 1f);
        heroBlobShield.setImageDrawable(shield);
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
     * 缓冲/流量状态胶囊 = 半透明白底纯胶囊（与元数据胶囊 TagsView 样式统一）；
     * 录音状态块 = 圆角半透明深底 + 强调色 28% 细描边（浮层样式）。
     */
    private void applyInfoPageThemeStyles() {
        final Context context = requireContext();
        final int accentBright = Utils.getThemeColor(context, R.attr.presetAccentBrightColor);

        // 缓冲 / 流量状态胶囊：半透明白底、无描边（与元数据胶囊样式完全统一）
        if (statBufferChip != null && statTrafficChip != null) {
            statBufferChip.setBackground(makeStatChipBackground());
            statTrafficChip.setBackground(makeStatChipBackground());
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

    /** 状态胶囊底形：全圆角 + 半透明白底（与元数据胶囊 TagsView 样式统一，无描边） */
    private GradientDrawable makeStatChipBackground() {
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(99));
        chip.setColor(ContextCompat.getColor(requireContext(), R.color.player_hero_chip_bg));
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

        /** 各真实页最近一次 instantiate 的虚拟 position（destroy 时效守卫，见 destroyItem） */
        private final int[] lastInstantiatedAt = new int[PAGER_REAL_COUNT];

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
            // 无限循环：虚拟 position 对真实页数取模；四个页面均为单实例 view 复用，
            // 先从容器摘除已在挂载中的副本页（ViewPager 先 instantiate 新页后 destroy
            // 旧页，同实例直接重复 add 会抛「already has a parent」）
            final View page;
            switch (position % PAGER_REAL_COUNT) {
                case 0:
                    page = layoutStationInfo;
                    break;
                case 1:
                    page = layoutCurrentStationHistory;
                    break;
                case 2:
                    page = layoutSongHistory;
                    break;
                default:
                    page = layoutRecordings;
                    break;
            }
            if (page.getParent() == collection) {
                collection.removeView(page);
            }
            collection.addView(page);
            lastInstantiatedAt[position % PAGER_REAL_COUNT] = position;
            return page;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object view) {
            // 时效守卫：同一真实页的 view 单实例复用，若该页已被更近一次 instantiate
            // 挂到新副本 position（回跳归一化时可能发生），旧副本的 destroy 不能把
            // view 从新副本位置摘走——否则新页当场空白
            if (lastInstantiatedAt[position % PAGER_REAL_COUNT] != position) {
                return;
            }
            container.removeView((View) view);
        }

        @Override
        public int getCount() {
            return PAGER_REAL_COUNT * PAGER_COPIES;
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
     * 同步分段高亮，双向联动；四页支持**无限循环拖动**（副本取模 + 边缘归一化：
     * 「电台信息」继续右滑到「录音列表」、「录音列表」继续左滑到「电台信息」）；
     * 录音状态块仅随「电台信息 / 录音列表」显示。
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
                // 用户点击分段 → 翻页（同位置时 setCurrentItem 为无操作）；post 到
                // 下一拍执行，避免 onPageSelected → selectTab 触发本回调时在 populate
                // 过程中重入 setCurrentItem；程序化同步（suppressTabJump）不翻页
                if (!suppressTabJump) {
                    final int target = PAGER_CENTER_BASE + tab.getPosition();
                    pagerContent.post(() -> pagerContent.setCurrentItem(target, false));
                }
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
                final int real = position % PAGER_REAL_COUNT;
                // 静默同步分段高亮（程序化选择，不触发 onTabSelected 的翻页路径）
                suppressTabJump = true;
                try {
                    TabLayout.Tab tab = segmentTabs.getTabAt(real);
                    if (tab != null && segmentTabs.getSelectedTabPosition() != real) {
                        segmentTabs.selectTab(tab);
                    }
                } finally {
                    suppressTabJump = false;
                }
                updateRecordingRowVisibility();
                // 无限循环：拖进边缘副本区（首尾各 1 份之外）→ 无动画跳回中心副本区
                // 的同一真实页。观感上即「电台信息」继续右滑出「录音列表」、
                // 「录音列表」继续左滑出「电台信息」，画面不变、无回卷闪烁。
                // post 到下一拍执行：onPageSelected 在 populate 内触发，此处同步
                // setCurrentItem 会重入 populate 导致单实例页 view 摘挂错乱（白页）
                pagerContent.post(() -> {
                    final int cur = pagerContent.getCurrentItem();
                    if (cur < PAGER_REAL_COUNT
                            || cur >= PAGER_REAL_COUNT * (PAGER_COPIES - 1)) {
                        pagerContent.setCurrentItem(
                                PAGER_CENTER_BASE + (cur % PAGER_REAL_COUNT), false);
                    }
                });
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
