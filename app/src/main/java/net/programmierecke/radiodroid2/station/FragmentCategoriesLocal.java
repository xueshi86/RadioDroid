package net.programmierecke.radiodroid2.station;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import net.programmierecke.radiodroid2.ActivityMain;
import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.CountryCodeDictionary;
import net.programmierecke.radiodroid2.CountryFlagsLoader;
import net.programmierecke.radiodroid2.FragmentBase;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.adapters.ItemAdapterCategory;
import net.programmierecke.radiodroid2.data.DataCategory;
import net.programmierecke.radiodroid2.database.CountryCount;
import net.programmierecke.radiodroid2.database.LanguageCount;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.station.StationsFilter;
import net.programmierecke.radiodroid2.utils.DatabaseEmptyHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class FragmentCategoriesLocal extends FragmentBase {
    private static final String TAG = "FragmentCategoriesLocal";

    private RecyclerView rvCategories;
    private androidx.constraintlayout.widget.ConstraintLayout layoutError;
    private ItemAdapterCategory adapterCategory;
    private StationsFilter.SearchStyle searchStyle = StationsFilter.SearchStyle.ByName;
    private SwipeRefreshLayout swipeRefreshLayout;
    private boolean singleUseFilter = false;
    private SharedPreferences sharedPref;
    private RadioStationRepository repository;
    private Executor executor = Executors.newSingleThreadExecutor();
    
    // 缓存变量，用于存储查询结果
    private ArrayList<DataCategory> cachedTags = null;
    private ArrayList<DataCategory> cachedCountries = null;
    private ArrayList<DataCategory> cachedLanguages = null;
    private boolean databaseUpdated = true; // 标记数据库是否已更新，初始为true表示首次加载
    
    // 数据库更新广播接收器
    private BroadcastReceiver databaseUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("net.programmierecke.radiodroid2.DATABASE_UPDATED".equals(intent.getAction())) {
                Log.d(TAG, "接收到数据库更新完成广播，清除缓存并标记数据库已更新");
                
                // 清除所有缓存
                cachedTags = null;
                cachedCountries = null;
                cachedLanguages = null;
                
                // 标记数据库已更新
                databaseUpdated = true;
                
                // 如果当前正在显示分类，则重新加载
                if (isAdded() && adapterCategory != null) {
                    loadCategories();
                }
            }
        }
    };

    public FragmentCategoriesLocal() {
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 注册数据库更新广播接收器
        IntentFilter filter = new IntentFilter("net.programmierecke.radiodroid2.DATABASE_UPDATED");
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(databaseUpdateReceiver, filter);
        Log.d(TAG, "已注册数据库更新广播接收器");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // 注销数据库更新广播接收器
        if (databaseUpdateReceiver != null) {
            LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(databaseUpdateReceiver);
            Log.d(TAG, "已注销数据库更新广播接收器");
        }
    }

    public void SetBaseSearchLink(StationsFilter.SearchStyle searchStyle) {
        this.searchStyle = searchStyle;
    }

    void ClickOnItem(DataCategory Data) {
        if (Data == null) {
            Log.e(TAG, "Category data is null");
            return;
        }
        
        if (Data.Name == null || Data.Name.isEmpty()) {
            Log.e(TAG, "Category name is null or empty");
            return;
        }
        
        // 检查Fragment是否已附加到Activity
        if (!isAdded() || getActivity() == null) {
            Log.e(TAG, "Fragment not attached to activity, cannot search");
            return;
        }
        
        try {
            ActivityMain m = (ActivityMain) getActivity();
            if (m == null) {
                Log.e(TAG, "Activity is null, cannot search");
                return;
            }
            
            Log.d(TAG, "Searching for category: " + Data.Name + " with style: " + searchStyle);
            m.search(this.searchStyle, Data.Name);
        } catch (Exception e) {
            Log.e(TAG, "Error when searching for category: " + Data.Name, e);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_stations_remote, container, false);

        rvCategories = view.findViewById(R.id.recyclerViewStations);
        layoutError = view.findViewById(R.id.layoutError);
        swipeRefreshLayout = view.findViewById(R.id.swiperefresh);
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setEnabled(false); // 本地数据不需要下拉刷新
        }

        // Adapter和LayoutManager将在onActivityCreated中初始化，确保Context可用
        rvCategories.setAdapter(null);

        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        
        // 在这里初始化Repository和Adapter，确保Context可用
        if (getContext() != null) {
            repository = RadioStationRepository.getInstance(getContext());
            
            // 初始化Adapter
            adapterCategory = new ItemAdapterCategory(R.layout.list_item_category);
            adapterCategory.setCategoryClickListener(new ItemAdapterCategory.CategoryClickListener() {
                @Override
                public void onCategoryClick(DataCategory category) {
                    ClickOnItem(category);
                }
            });
            
            // 设置RecyclerView
            LinearLayoutManager llm = new LinearLayoutManager(getContext());
            llm.setOrientation(LinearLayoutManager.VERTICAL);
            rvCategories.setLayoutManager(llm);
            rvCategories.setAdapter(adapterCategory);
        } else {
            Log.e(TAG, "Context is null in onActivityCreated");
            return;
        }
        
        // 加载数据
        loadCategories();
    }

    private void loadCategories() {
        if (BuildConfig.DEBUG) Log.d(TAG, "loading categories from local database.");

        // 检查缓存是否存在且数据库未更新
        if (!databaseUpdated) {
            Log.d(TAG, "使用缓存的分类数据");
            
            ArrayList<DataCategory> cachedData = null;
            switch (searchStyle) {
                case ByTagExact:
                    cachedData = cachedTags;
                    break;
                case ByCountryCodeExact:
                    cachedData = cachedCountries;
                    break;
                case ByLanguageExact:
                    cachedData = cachedLanguages;
                    break;
            }
            
            if (cachedData != null) {
                // 创建final副本用于lambda表达式
                final ArrayList<DataCategory> finalCachedData = cachedData;
                // 在主线程中更新UI
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (adapterCategory != null) {
                            adapterCategory.updateList(finalCachedData);
                        }
                    });
                }
                return;
            }
        }

        // 使用统一的空数据库检查
        DatabaseEmptyHelper.checkAndShowEmptyDatabaseError(this, layoutError, rvCategories, 
            new DatabaseEmptyHelper.DatabaseCheckCallback() {
                @Override
                public void onCheckCompleted(boolean isEmpty, int count) {
                    if (!isEmpty) {
                        // 数据库有数据，继续加载分类
                        Context ctx = getContext();
                        if (sharedPref == null) {
                            sharedPref = PreferenceManager.getDefaultSharedPreferences(ctx);
                        }

                        boolean show_single_use_tags = sharedPref.getBoolean("single_use_tags", false);

                        // 根据搜索类型加载不同的分类数据
                        switch (searchStyle) {
                            case ByTagExact:
                                loadTags(show_single_use_tags);
                                break;
                            case ByCountryCodeExact:
                                loadCountries(show_single_use_tags);
                                break;
                            case ByLanguageExact:
                                loadLanguages(show_single_use_tags);
                                break;
                            default:
                                loadTags(show_single_use_tags);
                                break;
                        }
                    }
                }

                @Override
                public void onCheckError(String error) {
                    Log.e(TAG, "Error checking station count: " + error);
                }
            });
    }

    private void loadTags(boolean show_single_use_tags) {
        // 检查repository是否已初始化
        if (repository == null) {
            Log.e(TAG, "Repository is null in loadTags");
            showEmptyDatabaseMessage();
            return;
        }
        
        Log.d(TAG, "加载标签分类");
        
        // 检查缓存是否存在且数据库未更新
        if (cachedTags != null && !databaseUpdated) {
            Log.d(TAG, "使用缓存的标签数据，数量: " + cachedTags.size());
            
            // 在主线程中更新UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (adapterCategory != null) {
                        adapterCategory.updateList(cachedTags);
                    } else {
                        Log.e(TAG, "Adapter is null in loadTags");
                    }
                });
            }
            return;
        }
        
        // 使用后台线程执行数据库查询
        executor.execute(() -> {
            List<String> tagStrings = repository.getAllTagStringsSync();
            
            if (tagStrings != null && !tagStrings.isEmpty()) {
                // 处理标签数据，分割复合标签并统计数量
                // 预估HashMap大小，减少扩容操作
                Map<String, Integer> tagCountMap = new HashMap<>(tagStrings.size() * 2);
                for (String tagStr : tagStrings) {
                    if (tagStr != null && !tagStr.isEmpty()) {
                        // 分割复合标签（如 "rock,pop,classic"）
                        String[] tagArray = tagStr.split(",");
                        for (String tag : tagArray) {
                            tag = tag.trim();
                            if (!tag.isEmpty()) {
                                // 增加标签计数
                                tagCountMap.put(tag, tagCountMap.getOrDefault(tag, 0) + 1);
                            }
                        }
                    }
                }

                // 转换为DataCategory列表，过滤掉电台数为0的标签
                // 预估ArrayList大小，减少扩容操作
                ArrayList<DataCategory> categoriesList = new ArrayList<>(tagCountMap.size());
                for (Map.Entry<String, Integer> entry : tagCountMap.entrySet()) {
                    String tag = entry.getKey();
                    int count = entry.getValue();
                    
                    if (count > 0) {
                        DataCategory category = new DataCategory();
                        category.Name = tag;
                        category.Label = tag;
                        category.UsedCount = count;
                        categoriesList.add(category);
                    }
                }

                // 排序
                Collections.sort(categoriesList);
                
                // 缓存结果
                cachedTags = new ArrayList<>(categoriesList);
                databaseUpdated = false;
                Log.d(TAG, "标签数据已缓存，数量: " + cachedTags.size());

                // 在主线程中更新UI
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (adapterCategory != null) {
                            if (categoriesList.isEmpty()) {
                                Log.d(TAG, "没有找到标签分类");
                                showNoResultsMessage("标签");
                            } else {
                                Log.d(TAG, "加载了 " + categoriesList.size() + " 个标签分类");
                                adapterCategory.updateList(categoriesList);
                            }
                        } else {
                            Log.e(TAG, "Adapter is null in loadTags");
                        }
                    });
                }
            } else {
                Log.d(TAG, "没有找到标签分类");
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> showNoResultsMessage("标签"));
                }
            }
        });
    }

    private void loadCountries(boolean show_single_use_tags) {
        // 检查repository是否已初始化
        if (repository == null) {
            Log.e(TAG, "Repository is null in loadCountries");
            showEmptyDatabaseMessage();
            return;
        }
        
        Log.d(TAG, "加载国家分类");
        
        // 检查缓存是否存在且数据库未更新
        if (cachedCountries != null && !databaseUpdated) {
            Log.d(TAG, "使用缓存的国家数据，数量: " + cachedCountries.size());
            
            // 在主线程中更新UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (adapterCategory != null) {
                        adapterCategory.updateList(cachedCountries);
                    } else {
                        Log.e(TAG, "Adapter is null in loadCountries");
                    }
                });
            }
            return;
        }
        
        // 使用后台线程执行数据库查询
        executor.execute(() -> {
            List<CountryCount> countryCounts = repository.getAllCountriesWithCountSync();
            
            if (countryCounts != null && !countryCounts.isEmpty()) {
                // 预估ArrayList大小，减少扩容操作
                ArrayList<DataCategory> categoriesList = new ArrayList<>(countryCounts.size());
                CountryCodeDictionary countryDict = CountryCodeDictionary.getInstance();
                CountryFlagsLoader flagsDict = CountryFlagsLoader.getInstance();

                for (CountryCount countryCount : countryCounts) {
                    if (countryCount != null && countryCount.country != null && !countryCount.country.isEmpty()) {
                        DataCategory category = new DataCategory();
                        category.Name = countryCount.country;
                        category.Label = countryDict.getCountryByCode(countryCount.country);
                        category.UsedCount = countryCount.stationCount;
                        
                        // 在后台线程中加载图标，避免阻塞UI
                        if (getContext() != null) {
                            category.Icon = flagsDict.getFlag(getContext(), countryCount.country);
                        } else {
                            Log.e(TAG, "Context is null in loadCountries");
                        }
                        
                        categoriesList.add(category);
                    }
                }

                // 排序
                Collections.sort(categoriesList);
                
                // 缓存结果
                cachedCountries = new ArrayList<>(categoriesList);
                databaseUpdated = false;
                Log.d(TAG, "国家数据已缓存，数量: " + cachedCountries.size());

                // 在主线程中更新UI
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (adapterCategory != null) {
                            Log.d(TAG, "加载了 " + categoriesList.size() + " 个国家分类");
                            adapterCategory.updateList(categoriesList);
                        } else {
                            Log.e(TAG, "Adapter is null in loadCountries");
                        }
                    });
                }
            } else {
                Log.d(TAG, "没有找到国家分类");
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> showNoResultsMessage("国家"));
                }
            }
        });
    }

    private void loadLanguages(boolean show_single_use_tags) {
        // 检查repository是否已初始化
        if (repository == null) {
            Log.e(TAG, "Repository is null in loadLanguages");
            showEmptyDatabaseMessage();
            return;
        }
        
        Log.d(TAG, "加载语言分类");
        
        // 检查缓存是否存在且数据库未更新
        if (cachedLanguages != null && !databaseUpdated) {
            Log.d(TAG, "使用缓存的语言数据，数量: " + cachedLanguages.size());
            
            // 在主线程中更新UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (adapterCategory != null) {
                        adapterCategory.updateList(cachedLanguages);
                    } else {
                        Log.e(TAG, "Adapter is null in loadLanguages");
                    }
                });
            }
            return;
        }
        
        // 使用后台线程执行数据库查询
        executor.execute(() -> {
            List<LanguageCount> languageCounts = repository.getAllLanguagesWithCountSync();
            
            if (languageCounts != null && !languageCounts.isEmpty()) {
                // 预估ArrayList大小，减少扩容操作
                ArrayList<DataCategory> categoriesList = new ArrayList<>(languageCounts.size());

                for (LanguageCount languageCount : languageCounts) {
                    if (languageCount != null && languageCount.language != null && !languageCount.language.isEmpty()) {
                        DataCategory category = new DataCategory();
                        category.Name = languageCount.language;
                        category.Label = languageCount.language;
                        category.UsedCount = languageCount.stationCount;
                        categoriesList.add(category);
                    }
                }

                // 排序
                Collections.sort(categoriesList);
                
                // 缓存结果
                cachedLanguages = new ArrayList<>(categoriesList);
                databaseUpdated = false;
                Log.d(TAG, "语言数据已缓存，数量: " + cachedLanguages.size());

                // 在主线程中更新UI
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (adapterCategory != null) {
                            Log.d(TAG, "加载了 " + categoriesList.size() + " 个语言分类");
                            adapterCategory.updateList(categoriesList);
                        } else {
                            Log.e(TAG, "Adapter is null in loadLanguages");
                        }
                    });
                }
            } else {
                Log.d(TAG, "没有找到语言分类");
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> showNoResultsMessage("语言"));
                }
            }
        });
    }

    public void EnableSingleUseFilter(boolean b) {
        this.singleUseFilter = b;
    }

    private void showEmptyDatabaseMessage() {
        // Create an empty category list with a message
        ArrayList<DataCategory> categoriesList = new ArrayList<>();
        DataCategory emptyMessage = new DataCategory();
        emptyMessage.Name = "";
        emptyMessage.Label = getString(R.string.empty_database_message);
        categoriesList.add(emptyMessage);

        // Update UI
        if (adapterCategory != null) {
            adapterCategory.updateList(categoriesList);
        } else {
            Log.e(TAG, "Adapter is null in showEmptyDatabaseMessage");
        }
    }
    
    private void showNoResultsMessage(String categoryType) {
        // Create an empty category list with a message
        ArrayList<DataCategory> categoriesList = new ArrayList<>();
        DataCategory emptyMessage = new DataCategory();
        emptyMessage.Name = "";
        emptyMessage.Label = getString(R.string.no_results_message, categoryType);
        categoriesList.add(emptyMessage);

        // Update UI
        if (adapterCategory != null) {
            adapterCategory.updateList(categoriesList);
        } else {
            Log.e(TAG, "Adapter is null in showNoResultsMessage");
        }
    }
}