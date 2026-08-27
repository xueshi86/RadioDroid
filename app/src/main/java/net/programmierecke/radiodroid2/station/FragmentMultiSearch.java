package net.programmierecke.radiodroid2.station;

import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import net.programmierecke.radiodroid2.FragmentBase;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.database.RadioStation;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.ui.SearchableListDialogFragment;
import net.programmierecke.radiodroid2.utils.DatabaseEmptyHelper;

import java.util.ArrayList;
import java.util.List;

public class FragmentMultiSearch extends FragmentBase {
    private static final String TAG = "FragmentMultiSearch";

    private static final int FILTER_TYPE_COUNTRY = 0;
    private static final int FILTER_TYPE_LANGUAGE = 1;
    private static final int FILTER_TYPE_TAG = 2;

    private RecyclerView recyclerViewStations;
    private EditText etSearchQuery;
    private MaterialButton spinnerCountry;
    private MaterialButton spinnerLanguage;
    private MaterialButton spinnerTag;
    private MaterialButton btnResetFilters;
    private MaterialButton btnToggleFilters;
    private MaterialButton btnExpandFilters;
    private ScrollView scrollViewFilters;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefreshLayout;
    private FloatingActionButton fabScrollToTop;

    private ItemAdapterStation stationListAdapter;
    private RadioStationRepository repository;

    // 筛选条件
    private String selectedCountry = "";
    private String selectedLanguage = "";
    private String selectedTag = "";
    private String searchQuery = "";

    // 选项列表（首项为"全部"）
    private List<SearchableListDialogFragment.FilterOption> countriesList = new ArrayList<>();
    private List<SearchableListDialogFragment.FilterOption> languagesList = new ArrayList<>();
    private List<SearchableListDialogFragment.FilterOption> tagsList = new ArrayList<>();
    private boolean tagsLoaded = false;

    // 防抖处理
    private Handler searchHandler = new Handler();
    private static final long DEBOUNCE_DELAY = 500; // 500ms
    private Runnable searchRunnable;

    private String allLabel = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_multi_search, container, false);

        recyclerViewStations = view.findViewById(R.id.recyclerViewStations);
        etSearchQuery = view.findViewById(R.id.etSearchQuery);
        spinnerCountry = view.findViewById(R.id.spinnerCountry);
        spinnerLanguage = view.findViewById(R.id.spinnerLanguage);
        spinnerTag = view.findViewById(R.id.spinnerTag);
        btnResetFilters = view.findViewById(R.id.btnResetFilters);
        btnToggleFilters = view.findViewById(R.id.btnToggleFilters);
        btnExpandFilters = view.findViewById(R.id.btnExpandFilters);
        scrollViewFilters = view.findViewById(R.id.scrollViewFilters);
        swipeRefreshLayout = view.findViewById(R.id.swiperefresh);
        fabScrollToTop = view.findViewById(R.id.fabScrollToTop);

        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // 初始化Repository和Adapter
        if (getContext() != null) {
            repository = RadioStationRepository.getInstance(getContext());

            // 初始化Adapter
            if (getActivity() != null) {
                stationListAdapter = new ItemAdapterStation(getActivity(), R.layout.list_item_station);
                stationListAdapter.setStationActionsListener(new ItemAdapterStation.StationActionsListener() {
                    @Override
                    public void onStationClick(DataRadioStation station, int pos) {
                        if (getActivity() == null) {
                            Log.e(TAG, "Activity is null, cannot play station");
                            return;
                        }

                        RadioDroidApp radioDroidApp = (RadioDroidApp) getActivity().getApplication();
                        if (radioDroidApp == null) {
                            Log.e(TAG, "RadioDroidApp is null, cannot play station");
                            return;
                        }

                        Utils.showPlaySelection(radioDroidApp, station, getActivity().getSupportFragmentManager());
                    }

                    @Override
                    public void onStationSwiped(DataRadioStation station) {
                        // 处理电台滑动事件
                    }

                    @Override
                    public void onStationMoved(int from, int to) {
                        // 处理电台移动事件
                    }

                    @Override
                    public void onStationMoveFinished() {
                        // 处理电台移动完成事件
                    }
                });
                recyclerViewStations.setAdapter(stationListAdapter);
                recyclerViewStations.setLayoutManager(new LinearLayoutManager(getActivity()));
                recyclerViewStations.addItemDecoration(new DividerItemDecoration(getActivity(), DividerItemDecoration.VERTICAL));

                recyclerViewStations.addOnScrollListener(new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                        if (fabScrollToTop != null) {
                            fabScrollToTop.setVisibility(recyclerView.canScrollVertically(-1) ? View.VISIBLE : View.GONE);
                        }
                    }
                });

                if (fabScrollToTop != null) {
                    fabScrollToTop.setOnClickListener(v -> {
                        if (recyclerViewStations != null) {
                            recyclerViewStations.smoothScrollToPosition(0);
                        }
                    });
                }
            }

            allLabel = getString(R.string.multi_search_all);

            // 加载所有国家、语言和标签
            loadFilterOptions();

            // 禁用下拉刷新功能
            swipeRefreshLayout.setEnabled(false);
            swipeRefreshLayout.setRefreshing(false);

            // 根据主题设置按钮文字颜色
            boolean isDarkTheme = Utils.isDarkTheme(getContext());
            if (isDarkTheme) {
                // 暗色主题下使用白色文字
                btnResetFilters.setTextColor(android.graphics.Color.WHITE);
                btnToggleFilters.setTextColor(android.graphics.Color.WHITE);
                btnExpandFilters.setTextColor(android.graphics.Color.WHITE);
            }

            // 设置事件监听器
            setupEventListeners();

            // 设置初始按钮文本和可见性
            btnToggleFilters.setText(getString(R.string.multi_search_collapse_filters));
            scrollViewFilters.setVisibility(View.VISIBLE);
            btnExpandFilters.setVisibility(View.GONE);

            updateAllFilterButtonTexts();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 重新加载筛选选项，确保下拉选择框内容不丢失
        if (countriesList.size() <= 1 || languagesList.size() <= 1 || tagsList.size() <= 1) {
            loadFilterOptions();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 清理防抖任务，防止视图销毁后触发 performMultiSearch
        if (searchHandler != null && searchRunnable != null) {
            searchHandler.removeCallbacks(searchRunnable);
        }
    }

    private void loadFilterOptions() {
        // 清空列表，重新加载数据
        countriesList.clear();
        languagesList.clear();
        tagsList.clear();

        // 加载国家列表（首项为"全部"）
        countriesList.add(new SearchableListDialogFragment.FilterOption(allLabel, 0));
        repository.getAllCountries().observe(getViewLifecycleOwner(), new Observer<List<String>>() {
            @Override
            public void onChanged(List<String> countries) {
                if (countries != null && !countries.isEmpty()) {
                    // 保留首项"全部"，追加新数据
                    if (countriesList.size() > 0 && allLabel.equals(countriesList.get(0).name)) {
                        countriesList.clear();
                        countriesList.add(new SearchableListDialogFragment.FilterOption(allLabel, 0));
                    }
                    for (String c : countries) {
                        countriesList.add(new SearchableListDialogFragment.FilterOption(c, 0));
                    }
                    updateAllFilterButtonTexts();
                }
            }
        });

        // 加载语言列表
        languagesList.add(new SearchableListDialogFragment.FilterOption(allLabel, 0));
        repository.getAllLanguages().observe(getViewLifecycleOwner(), new Observer<List<String>>() {
            @Override
            public void onChanged(List<String> languages) {
                if (languages != null && !languages.isEmpty()) {
                    if (languagesList.size() > 0 && allLabel.equals(languagesList.get(0).name)) {
                        languagesList.clear();
                        languagesList.add(new SearchableListDialogFragment.FilterOption(allLabel, 0));
                    }
                    for (String l : languages) {
                        languagesList.add(new SearchableListDialogFragment.FilterOption(l, 0));
                    }
                    updateAllFilterButtonTexts();
                }
            }
        });

        // 加载标签列表（后台拆分+计数，避免主线程全库扫描；F-2 修复：单标签而非逗号组合串）
        tagsList.add(new SearchableListDialogFragment.FilterOption(allLabel, 0));
        tagsLoaded = false;
        new Thread(() -> {
            try {
                final List<RadioStationRepository.SearchableListOption> options = repository.getTagOptionsSortedByCount();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (options != null) {
                            if (tagsList.size() > 0 && allLabel.equals(tagsList.get(0).name)) {
                                tagsList.clear();
                                tagsList.add(new SearchableListDialogFragment.FilterOption(allLabel, 0));
                            }
                            for (RadioStationRepository.SearchableListOption o : options) {
                                tagsList.add(new SearchableListDialogFragment.FilterOption(o.name, o.count));
                            }
                            tagsLoaded = true;
                            updateAllFilterButtonTexts();
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "加载标签列表失败", e);
            }
        }).start();
    }

    private void updateAllFilterButtonTexts() {
        if (getView() == null) return;
        String allText = allLabel;
        spinnerCountry.setText(selectedCountry.isEmpty() ? allText : selectedCountry);
        spinnerLanguage.setText(selectedLanguage.isEmpty() ? allText : selectedLanguage);
        spinnerTag.setText(selectedTag.isEmpty() ? allText : selectedTag);
    }

    private void showFilterDialog(final int filterType) {
        if (getContext() == null) return;

        String title;
        List<SearchableListDialogFragment.FilterOption> options;
        String selected;

        switch (filterType) {
            case FILTER_TYPE_COUNTRY:
                title = getString(R.string.multi_search_country);
                options = countriesList;
                selected = selectedCountry;
                break;
            case FILTER_TYPE_LANGUAGE:
                title = getString(R.string.multi_search_language);
                options = languagesList;
                selected = selectedLanguage;
                break;
            default:
                title = getString(R.string.multi_search_tag);
                options = tagsList;
                selected = selectedTag;
                break;
        }

        if (options == null || options.isEmpty()) {
            return;
        }

        SearchableListDialogFragment dialog = SearchableListDialogFragment.newInstance(title, options, selected);
        dialog.setOnOptionSelectedListener(name -> {
            if (name == null) return;
            String value = allLabel.equals(name) ? "" : name;
            switch (filterType) {
                case FILTER_TYPE_COUNTRY:
                    selectedCountry = value;
                    break;
                case FILTER_TYPE_LANGUAGE:
                    selectedLanguage = value;
                    break;
                default:
                    selectedTag = value;
                    break;
            }
            updateAllFilterButtonTexts();
            performMultiSearch();
        });
        dialog.show(getChildFragmentManager(), "searchable_list");
    }

    private void setupEventListeners() {
        // 国家选择
        spinnerCountry.setOnClickListener(v -> showFilterDialog(FILTER_TYPE_COUNTRY));
        // 语言选择
        spinnerLanguage.setOnClickListener(v -> showFilterDialog(FILTER_TYPE_LANGUAGE));
        // 标签选择
        spinnerTag.setOnClickListener(v -> showFilterDialog(FILTER_TYPE_TAG));

        // 搜索关键词监听器
        etSearchQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                searchQuery = s.toString();

                // 防抖处理
                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }

                searchRunnable = new Runnable() {
                    @Override
                    public void run() {
                        performMultiSearch();
                    }
                };

                searchHandler.postDelayed(searchRunnable, DEBOUNCE_DELAY);
            }
        });

        // 搜索框焦点监听器
        etSearchQuery.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                // 移除自动折叠筛选条件的逻辑，允许用户在筛选条件展开时输入关键词
            }
        });

        // 重置筛选条件按钮
        btnResetFilters.setOnClickListener(v -> resetFilters());

        // 筛选条件折叠/展开按钮
        btnToggleFilters.setOnClickListener(v -> toggleFilters());

        // 展开筛选条件按钮
        btnExpandFilters.setOnClickListener(v -> toggleFilters());
    }

    /**
     * 切换筛选条件区域的显示/隐藏
     */
    private void toggleFilters() {
        if (scrollViewFilters.getVisibility() == View.VISIBLE) {
            // 隐藏筛选条件区域
            scrollViewFilters.setVisibility(View.GONE);
            btnToggleFilters.setText(getString(R.string.multi_search_expand_filters));
            btnExpandFilters.setVisibility(View.VISIBLE);
        } else {
            // 显示筛选条件区域
            scrollViewFilters.setVisibility(View.VISIBLE);
            btnToggleFilters.setText(getString(R.string.multi_search_collapse_filters));
            btnExpandFilters.setVisibility(View.GONE);
        }
    }

    private void performMultiSearch() {
        if (getView() == null) {
            return; // 视图已销毁（防抖任务延迟触发窗口）
        }
        Log.d(TAG, "执行多条件搜索: 国家=" + selectedCountry + ", 语言=" + selectedLanguage + ", 标签=" + selectedTag + ", 关键词=" + searchQuery);

        // 使用统一的空数据库检查
        LinearLayout errorLayout = getView().findViewById(R.id.layoutError);
        DatabaseEmptyHelper.checkAndShowEmptyDatabaseError(this, errorLayout, recyclerViewStations,
            new DatabaseEmptyHelper.DatabaseCheckCallback() {
                @Override
                public void onCheckCompleted(boolean isEmpty, int count) {
                    if (!isEmpty) {
                        // 数据库有数据，执行多条件搜索
                        repository.searchStationsByMultiCriteria(selectedCountry, selectedLanguage, selectedTag, searchQuery)
                            .observe(getViewLifecycleOwner(), stations -> {
                                handleSearchResults(stations);
                            });
                    }
                }

                @Override
                public void onCheckError(String error) {
                    Log.e(TAG, "数据库检查错误: " + error);
                }
            });
    }

    private void handleSearchResults(List<RadioStation> radioStations) {
        if (getView() == null) {
            return; // 异步回调返回时视图可能已销毁
        }
        if (radioStations != null && !radioStations.isEmpty()) {
            // 无效电台过滤：用户可在设置中关闭"在列表中显示无效电台"
            boolean showBroken = PreferenceManager.getDefaultSharedPreferences(getContext())
                    .getBoolean("show_broken", false);
            // 转换为DataRadioStation
            List<DataRadioStation> dataStations = new ArrayList<>(radioStations.size());
            for (RadioStation radioStation : radioStations) {
                if (radioStation != null) {
                    DataRadioStation dataStation = radioStation.toDataRadioStation();
                    if (dataStation != null && (showBroken || dataStation.Working)) {
                        dataStations.add(dataStation);
                    }
                }
            }

            stationListAdapter.updateList(null, dataStations);
            recyclerViewStations.setVisibility(View.VISIBLE);
            getView().findViewById(R.id.layoutError).setVisibility(View.GONE);
        } else {
            // 没有搜索结果
            stationListAdapter.updateList(null, new ArrayList<>());
            recyclerViewStations.setVisibility(View.VISIBLE);
            getView().findViewById(R.id.layoutError).setVisibility(View.VISIBLE);
        }
    }

    private void resetFilters() {
        // 重置所有筛选条件
        selectedCountry = "";
        selectedLanguage = "";
        selectedTag = "";
        searchQuery = "";
        etSearchQuery.setText("");

        updateAllFilterButtonTexts();

        // 执行搜索
        performMultiSearch();
    }
}
