package net.programmierecke.radiodroid2.database;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;
import java.io.File;

import androidx.lifecycle.LiveData;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.paging.DataSource;
import androidx.room.Room;
import androidx.sqlite.db.SupportSQLiteDatabase;

import androidx.preference.PreferenceManager;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioBrowserServerManager;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.service.DatabaseUpdateWorker;
import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

public class RadioStationRepository {
    private static final String TAG = "RadioStationRepository";
    
    private RadioStationDao radioStationDao;
    private RadioStationDao tempRadioStationDao; // 临时数据库的DAO
    private UpdateTimestampDao updateTimestampDao;
    private Context context;
    private Executor executor = Executors.newSingleThreadExecutor();
    
    // 静态锁对象，确保同步方法不会被多个线程同时调用
    private static final Object sSyncLock = new Object();
    
    // 单例模式
    private static volatile RadioStationRepository INSTANCE;
    
    public static RadioStationRepository getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (RadioStationRepository.class) {
                if (INSTANCE == null) {
                    RadioDroidDatabase db = RadioDroidDatabase.getDatabase(context);
                    INSTANCE = new RadioStationRepository(db.radioStationDao(), context);
                }
            }
        }
        return INSTANCE;
    }
    
    private RadioStationRepository(RadioStationDao radioStationDao, Context context) {
        this.radioStationDao = radioStationDao;
        // 创建临时数据库的实例
        RadioDroidDatabase tempDatabase = Room.databaseBuilder(context.getApplicationContext(),
                RadioDroidDatabase.class, "radio_droid_database_temp")
                .addMigrations(RadioDroidDatabase.MIGRATION_3_4, RadioDroidDatabase.MIGRATION_4_5, RadioDroidDatabase.MIGRATION_5_6, RadioDroidDatabase.MIGRATION_5_14, RadioDroidDatabase.MIGRATION_6_14)
                .fallbackToDestructiveMigration()
                .build();
        this.tempRadioStationDao = tempDatabase.radioStationDao();
        // 获取UpdateTimestampDao
        RadioDroidDatabase db = RadioDroidDatabase.getDatabase(context);
        this.updateTimestampDao = db.updateTimestampDao();
        this.context = context;
    }
    
    // 从网络获取并存储所有电台数据
    public void syncAllStationsFromNetwork(Context context, SyncCallback callback) {
        executor.execute(() -> {
            syncAllStationsFromNetworkInternal(context, callback);
        });
    }
    
    /**
     * 清空临时数据库
     * 用于取消更新时清除临时数据，防止恢复时从上次进度继续
     */
    public void clearTempDatabase() {
        synchronized (sSyncLock) {
            try {
                if (tempRadioStationDao != null) {
                    tempRadioStationDao.deleteAll();
                    Log.d(TAG, "Successfully cleared temporary database");
                } else {
                    Log.w(TAG, "tempRadioStationDao is null, cannot clear temporary database");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error clearing temporary database", e);
            }
        }
    }
    
    /**
     * 获取临时数据库中的电台数量
     */
    public int getTempDatabaseCount() {
        synchronized (sSyncLock) {
            try {
                if (tempRadioStationDao != null) {
                    return tempRadioStationDao.getCount();
                } else {
                    Log.w(TAG, "tempRadioStationDao is null, returning 0");
                    return 0;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting temp database count", e);
                return 0;
            }
        }
    }
    
    // 内部同步方法，不使用Executor，直接在当前线程执行
    public void syncAllStationsFromNetworkInternal(Context context, SyncCallback callback) {
        syncAllStationsFromNetworkInternal(context, callback, false);
    }
    
    // 内部同步方法，支持恢复模式
    public void syncAllStationsFromNetworkInternal(Context context, SyncCallback callback, boolean resumeMode) {
        // 添加线程信息日志
        Log.d(TAG, "Starting syncAllStationsFromNetworkInternal on thread: " + Thread.currentThread().getId() + ", name: " + Thread.currentThread().getName());
        
        // 使用同步块确保只有一个同步操作在运行
        synchronized (sSyncLock) {
            Log.d(TAG, "Acquired sync lock, starting station synchronization");
            try {
                Log.d(TAG, "开始同步电台数据");
            callback.onProgress(context.getString(R.string.progress_checking_network), 0, 100);
        
        // 检查网络检查结果
        RadioBrowserServerManager.ServerInfo fastestServer = checkNetworkAndGetFastestServer(context, callback);
        if (fastestServer == null) {
            Log.e(TAG, "网络检查失败，无法获取服务器信息");
            callback.onError(context.getString(R.string.error_list_update));
            throw new RuntimeException("网络检查失败，无法获取服务器信息");
        }
        
        RadioDroidApp radioDroidApp = (RadioDroidApp) context.getApplicationContext();
        OkHttpClient httpClient = radioDroidApp.getHttpClient();
        
        // 设置当前使用的服务器
        RadioBrowserServerManager.setCurrentServer(fastestServer.server);
        
        callback.onProgress(context.getString(R.string.progress_getting_station_count), 0, 100);
    // 首先获取电台总数
    Log.d(TAG, "Attempting to download stats from: " + fastestServer.server + "/json/stats");
    String statsResult = Utils.downloadFeedFromServer(httpClient, radioDroidApp, fastestServer.server, "json/stats", fastestServer.useHttps, true, null);
    Log.d(TAG, "Download completed, result is " + (statsResult == null ? "null" : "non-null"));
    if (statsResult == null) {
        Log.e(TAG, "获取服务器统计信息失败，服务器返回空结果");
        callback.onError(context.getString(R.string.error_server_stats_failed));
        // 不重置更新状态，让错误处理流程自然进行
        throw new RuntimeException(context.getString(R.string.error_server_stats_failed));
    }
        
        // 解析统计信息获取电台总数、工作站点数和损坏站点数
        final int totalStations;
        Integer workingStations = null;
        Integer brokenStations = null;
        try {
            Log.d(TAG, "服务器返回的原始统计信息: " + statsResult);
            org.json.JSONObject stats = new org.json.JSONObject(statsResult);
            totalStations = stats.getInt("stations");
            
            // 尝试获取工作站点数和损坏站点数
            if (stats.has("stations_working")) {
                workingStations = stats.getInt("stations_working");
                Log.d(TAG, "解析得到的工作站点数: " + workingStations);
            }
            if (stats.has("stations_broken")) {
                brokenStations = stats.getInt("stations_broken");
                Log.d(TAG, "解析得到的损坏站点数: " + brokenStations);
            }
            
            Log.d(TAG, "解析得到的电台总数: " + totalStations);
            callback.onProgress(String.format(context.getString(R.string.progress_found_stations), totalStations), 0, totalStations);
        } catch (Exception e) {
            Log.e(TAG, "解析统计信息失败", e);
            callback.onError(context.getString(R.string.error_stats_parse_failed) + ": " + e.getMessage());
            // 不重置更新状态，让错误处理流程自然进行
            throw new RuntimeException(context.getString(R.string.error_stats_parse_failed) + ": " + e.getMessage());
        }
        
        // 保存服务器统计信息到SharedPreferences
        if (workingStations != null && brokenStations != null) {
            SharedPreferences sharedPref = context.getSharedPreferences("ServerStatistics", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putInt("stations_total", totalStations);
            editor.putInt("stations_working", workingStations);
            editor.putInt("stations_broken", brokenStations);
            editor.putLong("last_updated", System.currentTimeMillis());
            editor.apply();
            Log.d(TAG, "已保存服务器统计信息到SharedPreferences");
        }

        
        // 获取主数据库中的电台数量
        int mainDatabaseCount = radioStationDao.getCount();
        Log.d(TAG, "主数据库中的电台数量: " + mainDatabaseCount);
        
        callback.onProgress(context.getString(R.string.progress_starting_temp_db_update), 0, totalStations);
        
        // 只有在非恢复模式下才清空临时数据库
        if (!resumeMode) {
            // 清空临时数据库
            tempRadioStationDao.deleteAll();
            Log.d(TAG, "已清空临时数据库");
        } else {
            // 恢复模式下，检查临时数据库中已有的数据
            int existingTempCount = tempRadioStationDao.getCount();
            Log.d(TAG, "恢复模式：临时数据库中已有 " + existingTempCount + " 个电台");
            if (existingTempCount > 0) {
                callback.onProgress(context.getString(R.string.progress_resuming_download), existingTempCount, totalStations);
            }
        }
            
            // 使用分页获取所有电台数据，增加每页数量到100，减少请求次数
            final int pageSize = 100; // 每页100个电台，增加数量减少请求次数
            int totalPages = (int) Math.ceil((double) totalStations / pageSize);
            int totalDownloaded = 0;
            int batchSize = 20; // 增加批量处理大小，减少数据库插入次数
            
            // 在恢复模式下，检查临时数据库中已有的电台数量，并从相应的页面开始下载
            int startPage = 0;
            if (resumeMode) {
                int existingTempCount = tempRadioStationDao.getCount();
                if (existingTempCount > 0) {
                    // 计算应该从哪一页开始下载
                    startPage = existingTempCount / pageSize;
                    totalDownloaded = existingTempCount;
                    Log.d(TAG, "恢复模式：从第 " + (startPage + 1) + " 页开始下载，已有 " + totalDownloaded + " 个电台");
                }
            }
            
            // 获取SharedPreferences，用于获取服务器响应时间
            SharedPreferences sharedPref = context.getSharedPreferences("NetworkCheckResults", Context.MODE_PRIVATE);
            
            // 获取所有可用服务器列表并根据响应时间排序
            String[] allServers = RadioBrowserServerManager.getServerList(true);
            List<String[]> serverProtocolList = new ArrayList<>();
            Map<String, Long> serverResponseTimes = new HashMap<>();
            
            // 为每个服务器添加 HTTP 和 HTTPS 协议选项
            for (String server : allServers) {
                // 为每个服务器添加 HTTP 和 HTTPS 协议选项
                String httpKey = server + "_HTTP";
                String httpsKey = server + "_HTTPS";
                
                // 从之前的网络检查结果中获取响应时间
                long httpTime = sharedPref.getLong(httpKey, Long.MAX_VALUE);
                long httpsTime = sharedPref.getLong(httpsKey, Long.MAX_VALUE);
                
                if (httpTime < Long.MAX_VALUE) {
                    serverProtocolList.add(new String[]{server, "http"});
                    serverResponseTimes.put(server + "_http", httpTime);
                }
                
                if (httpsTime < Long.MAX_VALUE) {
                    serverProtocolList.add(new String[]{server, "https"});
                    serverResponseTimes.put(server + "_https", httpsTime);
                }
            }
            
            // 根据响应时间排序服务器列表，响应时间短的优先
            Collections.sort(serverProtocolList, (a, b) -> {
                String keyA = a[0] + "_" + a[1];
                String keyB = b[0] + "_" + b[1];
                long timeA = serverResponseTimes.getOrDefault(keyA, Long.MAX_VALUE);
                long timeB = serverResponseTimes.getOrDefault(keyB, Long.MAX_VALUE);
                return Long.compare(timeA, timeB);
            });
            
            Log.d(TAG, "服务器列表已按响应时间排序，共 " + serverProtocolList.size() + " 个服务器协议组合");
            
            // 计算最佳线程数量
            // 考虑设备CPU核心数量、网络状况、API限制和任务数量
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            
            // 根据最快服务器的响应时间调整线程数
            // 响应时间越快，可使用的线程数越多
            long fastestServerResponseTime = mFastestServerResponseTime;
            int networkFactor = 1;
            if (fastestServerResponseTime < 100) {
                // 响应时间 < 100ms，网络很快，可使用更多线程
                networkFactor = 3;
            } else if (fastestServerResponseTime < 500) {
                // 响应时间 100-500ms，网络较快
                networkFactor = 2;
            } else if (fastestServerResponseTime < 1000) {
                // 响应时间 500-1000ms，网络一般
                networkFactor = 1;
            } else {
                // 响应时间 > 1000ms，网络较慢，减少线程数
                networkFactor = 1;
            }
            
            // 计算基础线程数
            int baseThreadCount = availableProcessors * networkFactor;
            
            // 根据总页数调整线程数，避免创建过多不必要的线程
            int pageCount = totalPages - startPage;
            int pageFactor = Math.min(pageCount, 8); // 最多不超过8个线程用于分页下载
            
            // 设置合理的上下限（2-10），考虑API限制
            // API限制：避免对服务器造成过大压力，最多使用10个线程
            int optimalThreadCount = Math.max(2, Math.min(10, Math.min(baseThreadCount, pageFactor)));
            
            Log.d(TAG, "设备CPU核心数: " + availableProcessors + ", 最快服务器响应时间: " + fastestServerResponseTime + "ms, 总页数: " + pageCount + ", 最优线程数: " + optimalThreadCount);
            
            // 创建线程池，自动调整线程数量
            ExecutorService downloadExecutor = Executors.newFixedThreadPool(optimalThreadCount);
            
            // 用于线程安全的数据收集
            List<List<RadioStation>> downloadedStationsPerPage = new ArrayList<>();
            for (int i = 0; i < totalPages - startPage; i++) {
                downloadedStationsPerPage.add(new ArrayList<>());
            }
            
            // 用于线程安全的进度更新
            final AtomicInteger processedPages = new AtomicInteger(0);
            final AtomicInteger totalDownloadedAtomic = new AtomicInteger(totalDownloaded);
            
            // 创建下载任务列表
            List<Callable<Void>> downloadTasks = new ArrayList<>();
            
            for (int page = startPage; page < totalPages; page++) {
                final int currentPage = page;
                final int pageIndex = page - startPage;
                
                downloadTasks.add(() -> {
                    int skip = currentPage * pageSize;
                    String urlWithParams = "json/stations?limit=" + pageSize + "&offset=" + skip;

                    
                    String resultString = null;
                    int retryCount = 0;
                    final int maxRetries = 3;
                    final int maxServerSwitches = serverProtocolList.size();
                    int serverSwitchCount = 0;
                    
                    // 当前使用的服务器索引，每个线程独立
                    int threadServerIndex = new Random().nextInt(serverProtocolList.size());
                    String threadCurrentServer = fastestServer.server;
                    boolean threadCurrentUseHttps = fastestServer.useHttps;
                    
                    while (retryCount < maxRetries && resultString == null && serverSwitchCount < maxServerSwitches) {
                        if (retryCount > 0) {
                        Log.w(TAG, "第 " + (currentPage + 1) + " 页第 " + retryCount + " 次重试");
                        try {
                            // 动态调整重试间隔，根据服务器响应时间
                            // 响应时间越长，重试间隔越大，反之亦然
                            long baseDelay = 500; // 基础延迟500ms
                            long dynamicDelay;
                            
                            // 获取当前服务器的响应时间
                            long serverResponseTime = Long.MAX_VALUE;
                            if (serverResponseTimes.containsKey(threadCurrentServer + "_" + (threadCurrentUseHttps ? "https" : "http"))) {
                                serverResponseTime = serverResponseTimes.get(threadCurrentServer + "_" + (threadCurrentUseHttps ? "https" : "http"));
                            }
                            
                            if (serverResponseTime < 100) {
                                // 响应时间 < 100ms，网络很快，重试间隔短
                                dynamicDelay = baseDelay * retryCount;
                            } else if (serverResponseTime < 500) {
                                // 响应时间 100-500ms，网络较快
                                dynamicDelay = baseDelay * 2 * retryCount;
                            } else if (serverResponseTime < 1000) {
                                // 响应时间 500-1000ms，网络一般
                                dynamicDelay = baseDelay * 3 * retryCount;
                            } else {
                                // 响应时间 > 1000ms，网络较慢，重试间隔长
                                dynamicDelay = baseDelay * 4 * retryCount;
                            }
                            
                            // 设置最大重试间隔，避免等待时间过长
                            long maxDelay = 5000; // 最大5秒
                            dynamicDelay = Math.min(dynamicDelay, maxDelay);
                            
                            Log.d(TAG, "线程 " + Thread.currentThread().getId() + " 重试间隔: " + dynamicDelay + "ms (基于服务器响应时间: " + serverResponseTime + "ms)");
                            Thread.sleep(dynamicDelay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            Log.w(TAG, "重试等待被中断");
                            break;
                        }
                    }
                        
                        resultString = Utils.downloadFeedFromServer(httpClient, radioDroidApp, threadCurrentServer, urlWithParams, threadCurrentUseHttps, true, null);
                        
                        if (resultString == null) {
                            // 如果当前服务器请求失败，切换到下一个服务器
                            serverSwitchCount++;
                            if (serverSwitchCount < maxServerSwitches) {
                                String[] nextServer = serverProtocolList.get(threadServerIndex);
                                threadCurrentServer = nextServer[0];
                                threadCurrentUseHttps = "https".equals(nextServer[1]);
                                threadServerIndex = (threadServerIndex + 1) % serverProtocolList.size();
                                Log.w(TAG, "线程 " + Thread.currentThread().getId() + " 切换到备用服务器: " + (threadCurrentUseHttps ? "HTTPS" : "HTTP") + "://" + threadCurrentServer);
                            }
                        }
                        
                        retryCount++;
                    }
                    
                    if (resultString != null) {
                        List<DataRadioStation> dataStations = DataRadioStation.DecodeJson(resultString);
                        
                        if (dataStations != null && !dataStations.isEmpty()) {
                            List<RadioStation> radioStations = new ArrayList<>();
                            for (DataRadioStation dataStation : dataStations) {
                                RadioStation radioStation = RadioStation.fromDataRadioStation(dataStation);
                                radioStations.add(radioStation);
                            }
                            
                            downloadedStationsPerPage.set(pageIndex, radioStations);
                            int pageDownloadedCount = radioStations.size();
                            int currentTotal = totalDownloadedAtomic.addAndGet(pageDownloadedCount);
                            
                            Log.d(TAG, "线程 " + Thread.currentThread().getId() + " 已下载第 " + (currentPage + 1) + " 页，共 " + pageDownloadedCount + " 个电台，累计下载 " + currentTotal + "/" + totalStations + " 个电台");
                            
                            // 更新进度
                            int processed = processedPages.incrementAndGet();
                            callback.onProgress(context.getString(R.string.progress_downloading_stations), currentTotal, totalStations);
                        } else {
                            Log.w(TAG, "线程 " + Thread.currentThread().getId() + " 第 " + (currentPage + 1) + " 页数据为空");
                            processedPages.incrementAndGet();
                        }
                    } else {
                        Log.e(TAG, "线程 " + Thread.currentThread().getId() + " 第 " + (currentPage + 1) + " 页下载失败，已重试 " + maxRetries + " 次，跳过该页");
                        processedPages.incrementAndGet();
                    }
                    
                    return null;
                });
            }
            
            // 执行所有下载任务
            Log.d(TAG, "开始执行 " + downloadTasks.size() + " 个下载任务，使用 " + optimalThreadCount + " 个线程");
            try {
                // 执行所有任务并等待完成
                downloadExecutor.invokeAll(downloadTasks);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "下载任务执行被中断", e);
                downloadExecutor.shutdownNow();
                throw e;
            }
            
            // 关闭线程池
            downloadExecutor.shutdown();
            
            // 收集所有下载的数据
            List<RadioStation> allDownloadedStations = new ArrayList<>();
            for (List<RadioStation> pageStations : downloadedStationsPerPage) {
                allDownloadedStations.addAll(pageStations);
            }
            
            // 更新总下载数
            totalDownloaded = totalDownloadedAtomic.get();
            Log.d(TAG, "所有下载任务完成，共下载 " + totalDownloaded + " 个电台");
            
            // 批量插入所有数据到临时数据库
            if (!allDownloadedStations.isEmpty()) {
                // 分批插入，减少内存占用
                final int insertBatchSize = 1000;
                for (int i = 0; i < allDownloadedStations.size(); i += insertBatchSize) {
                    int endIndex = Math.min(i + insertBatchSize, allDownloadedStations.size());
                    List<RadioStation> batchToInsert = allDownloadedStations.subList(i, endIndex);
                    tempRadioStationDao.insertAll(batchToInsert);
                    Log.d(TAG, "批量插入了 " + batchToInsert.size() + " 个电台到临时数据库，进度: " + endIndex + "/" + allDownloadedStations.size());
                }
            }
            
            if (totalDownloaded > 0) {
                Log.d(TAG, "数据下载完成，共同步 " + totalDownloaded + " 个电台");
                
                int tempDatabaseCount = tempRadioStationDao.getCount();
                Log.d(TAG, "临时数据库中的电台数量: " + tempDatabaseCount);
                
                if (tempDatabaseCount >= mainDatabaseCount) {
                    Log.d(TAG, "临时数据库的电台数量(" + tempDatabaseCount + ")大于或等于主数据库(" + mainDatabaseCount + ")，将使用临时数据库");
                    
                    callback.onProgress(context.getString(R.string.progress_processing_data), totalDownloaded, totalDownloaded);
                    
                    replaceMainFromTemp();
                    
                    tempRadioStationDao.deleteAll();
                    Log.d(TAG, "已清空临时数据库");
                    
                    updateDatabaseTimestamp(context);
                    Log.d(TAG, "已更新数据库时间戳");
                    
                    callback.onProgress(context.getString(R.string.progress_switching_db), totalDownloaded, totalDownloaded);
                    
                    Intent databaseUpdatedIntent = new Intent("net.programmierecke.radiodroid2.DATABASE_UPDATED");
                    LocalBroadcastManager.getInstance(context).sendBroadcast(databaseUpdatedIntent);
                    Log.d(TAG, "已发送数据库更新完成广播");
                    
                    String completionMessage = String.format(context.getString(R.string.update_completed), totalDownloaded);
                    callback.onSuccess(completionMessage);
                } else {
                    Log.d(TAG, "临时数据库的电台数量(" + tempDatabaseCount + ")小于主数据库(" + mainDatabaseCount + ")，将询问用户是否替换");
                    
                    boolean shouldReplace = callback.onConfirmReplace(
                        String.format(context.getString(R.string.update_confirm_replace_message), tempDatabaseCount, mainDatabaseCount), 
                        tempDatabaseCount, 
                        mainDatabaseCount
                    );
                    
                    if (shouldReplace) {
                        Log.d(TAG, "用户确认替换，将使用临时数据库");
                        
                        callback.onProgress(context.getString(R.string.progress_processing_data), totalDownloaded, totalDownloaded);
                        
                        replaceMainFromTemp();
                        
                        updateDatabaseTimestamp(context);
                        Log.d(TAG, "已更新数据库时间戳");
                        
                        callback.onProgress(context.getString(R.string.progress_switching_db), totalDownloaded, totalDownloaded);
                        
                        Intent databaseUpdatedIntent = new Intent("net.programmierecke.radiodroid2.DATABASE_UPDATED");
                        LocalBroadcastManager.getInstance(context).sendBroadcast(databaseUpdatedIntent);
                        Log.d(TAG, "已发送数据库更新完成广播");
                        
                        String completionMessage = String.format(context.getString(R.string.update_completed), totalDownloaded);
                        callback.onSuccess(completionMessage);
                    } else {
                        Log.d(TAG, "用户取消替换，将继续使用主数据库");
                        
                        String completionMessage = context.getString(R.string.update_completed_keep_existing);
                        
                        Intent databaseUpdatedIntent = new Intent("net.programmierecke.radiodroid2.DATABASE_UPDATED");
                        LocalBroadcastManager.getInstance(context).sendBroadcast(databaseUpdatedIntent);
                        Log.d(TAG, "已发送数据库更新完成广播");
                        
                        callback.onSuccess(completionMessage);
                    }
                    
                    tempRadioStationDao.deleteAll();
                    Log.d(TAG, "已清空临时数据库");
                }
            } else {
                Log.e(TAG, "没有获取到任何电台数据");
                callback.onError(context.getString(R.string.error_no_stations_found));
                // 不重置更新状态，让错误处理流程自然进行
                throw new RuntimeException(context.getString(R.string.error_no_stations_found));
            }
        } catch (RuntimeException e) {
            // 重新抛出RuntimeException
            throw e;
        } catch (Exception e) {
                Log.e(TAG, "同步电台数据时出错", e);
                callback.onError(context.getString(R.string.error_sync_failed) + ": " + e.getMessage());
                // 不重置更新状态，让错误处理流程自然进行
                throw new RuntimeException(context.getString(R.string.error_sync_failed) + ": " + e.getMessage());
            }
        } // 结束synchronized块
    }
    
    // 保存最快服务器的响应时间
    private long mFastestServerResponseTime = Long.MAX_VALUE;
    
    private void replaceMainFromTemp() {
        synchronized (sSyncLock) {
            try {
                List<RadioStation> allStationsFromTemp = tempRadioStationDao.getAllStations();
                Log.d(TAG, "从临时数据库获取到 " + allStationsFromTemp.size() + " 个电台");
                
                if (allStationsFromTemp.isEmpty()) {
                    Log.w(TAG, "临时数据库为空，跳过替换");
                    return;
                }
                
                radioStationDao.deleteAll();
                
                final int insertBatchSize = 2000;
                for (int i = 0; i < allStationsFromTemp.size(); i += insertBatchSize) {
                    int endIndex = Math.min(i + insertBatchSize, allStationsFromTemp.size());
                    List<RadioStation> batch = new ArrayList<>(allStationsFromTemp.subList(i, endIndex));
                    radioStationDao.insertAll(batch);
                }
                
                int finalCount = radioStationDao.getCount();
                Log.d(TAG, "主数据库最终数量: " + finalCount);
            } catch (Exception e) {
                Log.e(TAG, "替换主数据库失败", e);
                throw e;
            }
        }
    }
    
    // 检查网络并获取最快的服务器
    private RadioBrowserServerManager.ServerInfo checkNetworkAndGetFastestServer(Context context, SyncCallback callback) {
        try {
            // 检查网络连接状态
            if (!isNetworkAvailable(context)) {
                Log.e(TAG, "网络连接不可用");
                callback.onError(context.getString(R.string.error_network_unavailable));
                throw new RuntimeException(context.getString(R.string.error_network_unavailable));
            }
            
            // 检查电量状态
            checkBatteryLevel(context, callback);
            
            // 检查存储空间
            checkStorageSpace(context, callback);
            
            // 不再使用缓存结果，每次都执行新的网络检查
            Log.d(TAG, "执行新的网络检查");
            callback.onProgress(context.getString(R.string.progress_checking_network_speed), 0, 100);
            
            // 测试所有连接速度
            Map<String, Long> results = RadioBrowserServerManager.testAllConnectionSpeeds(context);
            
            // 保存结果供参考
            SharedPreferences sharedPref = context.getSharedPreferences("NetworkCheckResults", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPref.edit();
            for (Map.Entry<String, Long> entry : results.entrySet()) {
                editor.putLong(entry.getKey(), entry.getValue());
            }
            editor.putLong("timestamp", System.currentTimeMillis());
            editor.apply();
            
            // 查找最快的服务器并保存响应时间
            RadioBrowserServerManager.ServerInfo fastestServer = findFastestServerFromResults(results);
            if (fastestServer != null) {
                // 获取最快服务器的响应时间
                String key = fastestServer.server + "_" + (fastestServer.useHttps ? "HTTPS" : "HTTP");
                mFastestServerResponseTime = results.get(key);
                Log.d(TAG, "最快服务器: " + fastestServer.server + "，响应时间: " + mFastestServerResponseTime + "ms");
            }
            
            return fastestServer;
        } catch (Exception e) {
            Log.e(TAG, "检查网络连接时出错", e);
            callback.onError(context.getString(R.string.error_network_check_failed) + ": " + e.getMessage());
            // 不重置更新状态，让错误处理流程自然进行
            throw new RuntimeException(context.getString(R.string.error_network_check_failed) + ": " + e.getMessage());
        }
    }
    
    // 从测试结果中找到最快的服务器
    private RadioBrowserServerManager.ServerInfo findFastestServerFromResults(Map<String, Long> results) {
        long minTime = Long.MAX_VALUE;
        String fastestServer = null;
        boolean useHttps = false;
        
        for (Map.Entry<String, Long> entry : results.entrySet()) {
            if (entry.getValue() < minTime) {
                minTime = entry.getValue();
                String[] parts = entry.getKey().split("_");
                fastestServer = parts[0];
                useHttps = "HTTPS".equals(parts[1]);
            }
        }
        
        if (fastestServer != null) {
            Log.d(TAG, "找到最快的服务器: " + fastestServer + " (HTTPS: " + useHttps + ")");
            return new RadioBrowserServerManager.ServerInfo(fastestServer, useHttps);
        }
        
        Log.e(TAG, "没有找到可用的服务器");
        return null;
    }
    
    // 获取本地数据库中的电台数量
    public void getStationCount(StationCountCallback callback) {
        executor.execute(() -> {
            try {
                int count = radioStationDao.getCount();
                if (callback != null) {
                    callback.onStationCountReceived(count);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting station count", e);
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }
    
    // 同步获取电台数量（仅在后台线程中使用）
    public int getStationCountSync() {
        return radioStationDao.getStationCount();
    }
    
    public int getWorkingStationCountSync() {
        return radioStationDao.getWorkingStationCount();
    }
    
    public int getBrokenStationCountSync() {
        return radioStationDao.getBrokenStationCount();
    }
    
    public interface StationCountCallback {
        void onStationCountReceived(int count);
        void onError(String error);
    }
    
    // 按名称获取所有电台
    public LiveData<List<RadioStation>> getAllStationsByName() {
        return radioStationDao.getAllStationsByName();
    }

    public LiveData<List<RadioStation>> getAllStationsByClickCount() {
        return radioStationDao.getAllStationsByClickCount();
    }

    // 随机获取一个电台
    public RadioStation getRandomStationSync() {
        return radioStationDao.getRandomStationSync();
    }

    // 按点击次数获取电台
    public LiveData<List<RadioStation>> getStationsByClickCount() {
        return radioStationDao.getStationsByClickCount();
    }
    
    // 获取点击排行前N个电台
    public LiveData<List<RadioStation>> getTopClickStations(int limit) {
        return radioStationDao.getTopClickStations(limit);
    }
    
    public LiveData<List<RadioStation>> getTopClickStationsAll() {
        return radioStationDao.getTopClickStationsAll();
    }
    
    // 按投票数获取电台
    public LiveData<List<RadioStation>> getStationsByVotes() {
        return radioStationDao.getStationsByVotes();
    }
    
    // 获取投票排行前N个电台
    public LiveData<List<RadioStation>> getTopVoteStations(int limit) {
        return radioStationDao.getTopVoteStations(limit);
    }
    
    public LiveData<List<RadioStation>> getTopVoteStationsAll() {
        return radioStationDao.getTopVoteStationsAll();
    }
    
    // 按最后更改时间获取电台
    public LiveData<List<RadioStation>> getStationsByLastChangeTime() {
        return radioStationDao.getStationsByLastChangeTime();
    }
    
    // 获取最近更新的电台（限制数量）
    public LiveData<List<RadioStation>> getRecentlyChangedStations(int limit) {
        return radioStationDao.getRecentlyChangedStations(limit);
    }

    public LiveData<List<RadioStation>> getRecentlyChangedWorkingStations(int limit) {
        return radioStationDao.getRecentlyChangedWorkingStations(limit);
    }
    
    public LiveData<List<RadioStation>> getRecentlyChangedStationsAll() {
        return radioStationDao.getRecentlyChangedStationsAll();
    }

    public LiveData<List<RadioStation>> getRecentlyChangedWorkingStationsAll() {
        return radioStationDao.getRecentlyChangedWorkingStationsAll();
    }
    
    // 获取最近点击的电台（限制数量）
    public LiveData<List<RadioStation>> getRecentlyPlayedStations(int limit) {
        return radioStationDao.getRecentlyPlayedStations(limit);
    }
    
    // 获取所有国家
    public LiveData<List<String>> getAllCountries() {
        return radioStationDao.getAllCountries();
    }
    
    // 获取所有国家（同步版本）
    public List<String> getAllCountriesSync() {
        return radioStationDao.getAllCountriesSync();
    }
    
    // 按国家获取电台
    public LiveData<List<RadioStation>> getStationsByCountry(String country) {
        return radioStationDao.getStationsByCountry(country);
    }
    
    // 获取所有语言
    public LiveData<List<String>> getAllLanguages() {
        return radioStationDao.getAllLanguages();
    }
    
    // 获取所有语言（同步版本）
    public List<String> getAllLanguagesSync() {
        return radioStationDao.getAllLanguagesSync();
    }
    
    // 按语言获取电台
    public LiveData<List<RadioStation>> getStationsByLanguage(String language) {
        return radioStationDao.getStationsByLanguage(language);
    }

    public LiveData<List<RadioStation>> getStationsByLanguageAll(String language) {
        return radioStationDao.getStationsByLanguageAll(language);
    }
    
    // 按语言获取电台（限制数量）
    public LiveData<List<RadioStation>> getStationsByLanguageWithLimit(String language, int limit) {
        return radioStationDao.getStationsByLanguageWithLimit(language, limit);
    }
    
    // 按语言和国家获取电台（限制数量）
    public LiveData<List<RadioStation>> getStationsByLanguageAndCountry(String language, String countryCode, int limit) {
        return radioStationDao.getStationsByLanguageAndCountry(language, countryCode, limit);
    }
    
    // 按语言和国家获取电台（不限制数量）
    public LiveData<List<RadioStation>> getStationsByLanguageAndCountry(String language, String countryCode) {
        return radioStationDao.getStationsByLanguageAndCountry(language, countryCode);
    }
    
    // 按国家获取电台（限制数量）
    public LiveData<List<RadioStation>> getStationsByCountryWithLimit(String countryCode, int limit) {
        return radioStationDao.getStationsByCountryWithLimit(countryCode, limit);
    }

    public LiveData<List<RadioStation>> getStationsByCountryCodeAll(String countryCode) {
        return radioStationDao.getStationsByCountryCodeAll(countryCode);
    }
    
    // 获取所有标签
    public LiveData<List<String>> getAllTags() {
        return radioStationDao.getAllTags();
    }
    
    // 按标签获取电台
    public LiveData<List<RadioStation>> getStationsByTag(String tag) {
        return radioStationDao.getStationsByTag(tag);
    }
    
    // 获取标签对应的电台数量
    public LiveData<Integer> getStationCountByTag(String tag) {
        return radioStationDao.getStationCountByTag(tag);
    }
    
    // 获取标签对应的电台数量（同步版本）
    public int getStationCountByTagSync(String tag) {
        return radioStationDao.getStationCountByTagSync(tag);
    }
    
    // 更精确的标签查询，处理特殊字符如#
    public int getStationCountByTagPreciseSync(String tag) {
        return radioStationDao.getStationCountByTagPreciseSync(tag);
    }
    
    // 获取国家对应的电台数量
    public LiveData<Integer> getStationCountByCountry(String country) {
        return radioStationDao.getStationCountByCountry(country);
    }
    
    // 获取国家对应的电台数量（同步版本）
    public int getStationCountByCountrySync(String country) {
        return radioStationDao.getStationCountByCountrySync(country);
    }
    
    // 优化的查询方法 - 一次性获取所有国家及其电台数量
    public List<CountryCount> getAllCountriesWithCountSync() {
        return radioStationDao.getAllCountriesWithCountSync();
    }
    
    // 优化的查询方法 - 一次性获取所有语言及其电台数量
    public List<LanguageCount> getAllLanguagesWithCountSync() {
        return radioStationDao.getAllLanguagesWithCountSync();
    }
    
    // 获取所有标签字符串（原始格式，包含逗号分隔的多个标签）
    public List<String> getAllTagStringsSync() {
        return radioStationDao.getAllTagStringsSync();
    }
    
    // 获取语言对应的电台数量
    public LiveData<Integer> getStationCountByLanguage(String language) {
        return radioStationDao.getStationCountByLanguage(language);
    }
    
    // 获取语言对应的电台数量（同步版本）
    public int getStationCountByLanguageSync(String language) {
        return radioStationDao.getStationCountByLanguageSync(language);
    }
    
    // 搜索电台
    public LiveData<List<RadioStation>> searchStations(String query) {
        return radioStationDao.searchStations(query);
    }
    
    // 使用FTS快速搜索电台
    public LiveData<List<RadioStation>> searchStationsFast(String query) {
        return radioStationDao.searchStationsFast(query);
    }
    
    // 使用FTS按名称快速搜索电台
    public LiveData<List<RadioStation>> searchStationsByNameFast(String query) {
        return radioStationDao.searchStationsByNameFast(query);
    }
    
    // 使用FTS按标签快速搜索电台
    public LiveData<List<RadioStation>> searchStationsByTagsFast(String query) {
        return radioStationDao.searchStationsByTagsFast(query);
    }
    
    // 使用FTS按国家快速搜索电台
    public LiveData<List<RadioStation>> searchStationsByCountryFast(String query) {
        return radioStationDao.searchStationsByCountryFast(query);
    }
    
    // 使用FTS按语言快速搜索电台
    public LiveData<List<RadioStation>> searchStationsByLanguageFast(String query) {
        return radioStationDao.searchStationsByLanguageFast(query);
    }
    
    // 按名称搜索电台
    public LiveData<List<RadioStation>> searchStationsByName(String query) {
        return radioStationDao.searchStationsByName(query);
    }
    
    // 按标签搜索电台
    public LiveData<List<RadioStation>> searchStationsByTags(String query) {
        return radioStationDao.searchStationsByTags(query);
    }
    
    // 按国家搜索电台
    public LiveData<List<RadioStation>> searchStationsByCountry(String query) {
        return radioStationDao.searchStationsByCountry(query);
    }
    
    // 按语言搜索电台
    public LiveData<List<RadioStation>> searchStationsByLanguage(String query) {
        return radioStationDao.searchStationsByLanguage(query);
    }
    
    // 按国家代码获取电台
    public LiveData<List<RadioStation>> getStationsByCountryCode(String countryCode) {
        return radioStationDao.getStationsByCountryCode(countryCode);
    }
    
    // 按精确语言获取电台
    public LiveData<List<RadioStation>> getStationsByLanguageExact(String language) {
        return radioStationDao.getStationsByLanguageExact(language);
    }
    
    // 按精确标签获取电台
    public LiveData<List<RadioStation>> getStationsByTagExact(String tag) {
        return radioStationDao.getStationsByTagExact(tag);
    }
    
    // 分页查询方法
    public DataSource.Factory<Integer, RadioStation> getAllStationsPaged() {
        return radioStationDao.getAllStationsPaged();
    }
    
    public DataSource.Factory<Integer, RadioStation> getStationsByClickCountPaged() {
        return radioStationDao.getStationsByClickCountPaged();
    }
    
    public DataSource.Factory<Integer, RadioStation> getStationsByVotesPaged() {
        return radioStationDao.getStationsByVotesPaged();
    }
    
    public DataSource.Factory<Integer, RadioStation> getStationsByLastChangeTimePaged() {
        return radioStationDao.getStationsByLastChangeTimePaged();
    }
    
    public DataSource.Factory<Integer, RadioStation> searchStationsPaged(String query) {
        return radioStationDao.searchStationsPaged(query);
    }
    
    // 根据UUID获取电台
    public RadioStation getStationByUuid(String uuid) {
        return radioStationDao.getStationById(uuid);
    }
    
    /**
     * 多条件搜索电台
     * @param country 国家筛选条件，为空表示不筛选
     * @param language 语言筛选条件，为空表示不筛选
     * @param tag 标签筛选条件，为空表示不筛选
     * @param keyword 关键词搜索，为空表示不搜索
     * @return 符合条件的电台列表
     */
    public LiveData<List<RadioStation>> searchStationsByMultiCriteria(String country, String language, String tag, String keyword) {
        return radioStationDao.searchStationsByMultiCriteria(country, language, tag, keyword);
    }
    
    // 获取数据库更新时间戳
    public long getDatabaseUpdateTime() {
        UpdateTimestamp timestamp = updateTimestampDao.getTimestamp();
        if (timestamp != null) {
            return timestamp.last_update_timestamp;
        }
        return 0;
    }
    
    // 更新数据库时间戳
    public void updateDatabaseTimestamp() {
        updateTimestampDao.updateTimestamp(System.currentTimeMillis());
    }
    
    // 更新数据库时间戳（使用指定时间戳）
    public void updateDatabaseTimestamp(long timestamp) {
        updateTimestampDao.updateTimestamp(timestamp);
    }
    

    
    // 获取搜索建议
    public LiveData<List<String>> getSearchSuggestions(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        return radioStationDao.getSearchSuggestions(query);
    }
    
    // 获取标签建议
    public LiveData<List<String>> getTagSuggestions(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        return radioStationDao.getTagSuggestions(query);
    }
    
    // 更新数据库时间戳并保存到SharedPreferences
    public void updateDatabaseTimestamp(Context context) {
        long currentTime = System.currentTimeMillis();
        updateTimestampDao.updateTimestamp(currentTime);
        
        // 获取电台数量
        int stationCount = radioStationDao.getCount();
        
        // 同时更新SharedPreferences中的本地数据库最后更新时间和电台数量
        java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
        String formattedTime = dateFormat.format(new java.util.Date(currentTime));
        
        SharedPreferences prefs = context.getSharedPreferences("net.programmierecke.radiodroid2_preferences", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("local_database_last_update", formattedTime);
        editor.putInt("local_database_station_count", stationCount);
        editor.apply();
        
        Log.d(TAG, "Updated database timestamp and station count to SharedPreferences: " + formattedTime + ", " + stationCount);
    }
    
    // 确保update_timestamp表存在并有有效数据
    public void ensureUpdateTimestampTable() {
        try {
            SupportSQLiteDatabase db = RadioDroidDatabase.getDatabase(context).getOpenHelper().getWritableDatabase();
            
            // 检查update_timestamp表是否存在
            boolean tableExists = false;
            android.database.Cursor cursor = null;
            try {
                cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='update_timestamp'");
                tableExists = cursor != null && cursor.getCount() > 0;
            } catch (Exception e) {
                Log.w(TAG, "Error checking if update_timestamp table exists", e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            
            if (!tableExists) {
                Log.d(TAG, "update_timestamp table does not exist, creating it");
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `update_timestamp` (`id` INTEGER NOT NULL, `last_update_timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                );
                db.execSQL(
                    "INSERT OR REPLACE INTO `update_timestamp` (`id`, `last_update_timestamp`) VALUES (1, 0)"
                );
                Log.d(TAG, "Created update_timestamp table with default timestamp 0");
            } else {
                Log.d(TAG, "update_timestamp table already exists");
                
                // 检查表中是否有数据
                UpdateTimestamp timestamp = updateTimestampDao.getTimestamp();
                if (timestamp == null) {
                    Log.d(TAG, "update_timestamp table is empty, inserting default row");
                    db.execSQL(
                        "INSERT OR REPLACE INTO `update_timestamp` (`id`, `last_update_timestamp`) VALUES (1, 0)"
                    );
                } else {
                    Log.d(TAG, "update_timestamp table has data, timestamp: " + timestamp.last_update_timestamp);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error ensuring update_timestamp table", e);
        }
    }
    
    // 同步回调接口
    public interface SyncCallback {
        void onProgress(String message);
        void onProgress(String message, int current, int total);
        void onSuccess(String message);
        void onError(String error);
        boolean onConfirmReplace(String message, int tempCount, int mainCount);
    }
    
    // 进度回调接口
    public interface IProgressCallback {
        void onProgress(String message, int current, int total);
        void onSuccess(String message);
        void onFailure(String error);
    }
    
    // 关闭数据库连接
    public void closeDatabase() {
        try {
            // 清理临时数据库文件
            cleanupTempDatabaseFiles(context);
            
            // 直接关闭RadioDroidDatabase的静态实例，避免创建新实例
            RadioDroidDatabase.closeInstance();
            
            // 重置Repository实例
            INSTANCE = null;
            Log.d(TAG, "Database connection closed successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error closing database connection", e);
        }
    }
    
    /**
     * 清理临时数据库文件
     * Room数据库会创建多个文件（.db, -wal, -shm, -journal），需要全部清理
     */
    public static void cleanupTempDatabaseFiles(Context context) {
        String[] tempDbNames = {
            "radio_droid_database_temp",
            "radio_droid_database_temp-wal",
            "radio_droid_database_temp-shm",
            "radio_droid_database_temp-journal"
        };
        
        File databasesDir = context.getDatabasePath("radio_droid_database_temp").getParentFile();
        if (databasesDir != null && databasesDir.exists()) {
            for (String dbName : tempDbNames) {
                File dbFile = new File(databasesDir, dbName);
                if (dbFile.exists()) {
                    if (dbFile.delete()) {
                        Log.d(TAG, "Deleted temp database file: " + dbName);
                    } else {
                        Log.w(TAG, "Failed to delete temp database file: " + dbName);
                    }
                }
            }
        }
    }
    
    // 重新初始化数据库
    public void reinitializeDatabase(Context context) {
        try {
            SharedPreferences backupPrefs = context.getSharedPreferences("user_settings_backup", Context.MODE_PRIVATE);
            SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context);
            
            Map<String, ?> allSettings = defaultPrefs.getAll();
            SharedPreferences.Editor backupEditor = backupPrefs.edit();
            for (Map.Entry<String, ?> entry : allSettings.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof String) {
                    backupEditor.putString(key, (String) value);
                } else if (value instanceof Integer) {
                    backupEditor.putInt(key, (Integer) value);
                } else if (value instanceof Boolean) {
                    backupEditor.putBoolean(key, (Boolean) value);
                } else if (value instanceof Long) {
                    backupEditor.putLong(key, (Long) value);
                } else if (value instanceof Float) {
                    backupEditor.putFloat(key, (Float) value);
                } else if (value instanceof Set) {
                    backupEditor.putStringSet(key, (Set<String>) value);
                }
            }
            backupEditor.commit();
            Log.d(TAG, "Backed up " + allSettings.size() + " user settings");
            
            closeDatabase();
            
            RadioDroidDatabase newDb = RadioDroidDatabase.forceRecreateDatabase(context);
            
            this.radioStationDao = newDb.radioStationDao();
            this.updateTimestampDao = newDb.updateTimestampDao();
            
            RadioDroidDatabase tempDatabase = Room.databaseBuilder(context.getApplicationContext(),
                    RadioDroidDatabase.class, "radio_droid_database_temp")
                    .addMigrations(RadioDroidDatabase.MIGRATION_3_4, RadioDroidDatabase.MIGRATION_4_5, RadioDroidDatabase.MIGRATION_5_6, RadioDroidDatabase.MIGRATION_5_14, RadioDroidDatabase.MIGRATION_6_14)
                    .fallbackToDestructiveMigration()
                    .build();
            this.tempRadioStationDao = tempDatabase.radioStationDao();
            
            SharedPreferences.Editor restoreEditor = defaultPrefs.edit();
            Map<String, ?> backupSettings = backupPrefs.getAll();
            for (Map.Entry<String, ?> entry : backupSettings.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof String) {
                    restoreEditor.putString(key, (String) value);
                } else if (value instanceof Integer) {
                    restoreEditor.putInt(key, (Integer) value);
                } else if (value instanceof Boolean) {
                    restoreEditor.putBoolean(key, (Boolean) value);
                } else if (value instanceof Long) {
                    restoreEditor.putLong(key, (Long) value);
                } else if (value instanceof Float) {
                    restoreEditor.putFloat(key, (Float) value);
                } else if (value instanceof Set) {
                    restoreEditor.putStringSet(key, (Set<String>) value);
                }
            }
            restoreEditor.commit();
            Log.d(TAG, "Restored " + backupSettings.size() + " user settings");
            
            backupPrefs.edit().clear().commit();
            
            Log.d(TAG, "Database reinitialized successfully with user settings preserved");
        } catch (Exception e) {
            Log.e(TAG, "Error reinitializing database", e);
            throw new RuntimeException("Failed to reinitialize database: " + e.getMessage(), e);
        }
    }
    
    // 检查网络连接是否可用
    private boolean isNetworkAvailable(Context context) {
        try {
            ConnectivityManager connectivityManager = 
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            
            if (connectivityManager == null) {
                Log.e(TAG, "ConnectivityManager is null");
                return false;
            }
            
            NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
            boolean isConnected = activeNetworkInfo != null && activeNetworkInfo.isConnected();
            
            Log.d(TAG, "网络连接状态: " + (isConnected ? "已连接" : "未连接"));
            return isConnected;
        } catch (Exception e) {
            Log.e(TAG, "检查网络连接时出错", e);
            return false;
        }
    }
    
    // 检查电池电量
    private void checkBatteryLevel(Context context, SyncCallback callback) {
        try {
            BatteryManager batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            
            if (batteryManager == null) {
                Log.w(TAG, "BatteryManager is null, 无法检查电池电量");
                return;
            }
            
            int batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            
            if (batteryLevel == Integer.MIN_VALUE) {
                // 如果无法通过BatteryManager获取电量，尝试使用Intent方式
                Intent batteryStatus = context.registerReceiver(null, 
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                
                if (batteryStatus != null) {
                    int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    
                    if (level > 0 && scale > 0) {
                        batteryLevel = (level * 100) / scale;
                    }
                }
            }
            
            Log.d(TAG, "当前电池电量: " + batteryLevel + "%");
            
            // 如果电量低于20%，显示警告
            if (batteryLevel < 20 && batteryLevel > 0) {
                String warning = context.getString(R.string.warning_low_battery, batteryLevel);
                Log.w(TAG, warning);
                callback.onProgress(warning);
            }
            
            // 如果电量低于5%，阻止更新
            if (batteryLevel < 5 && batteryLevel > 0) {
                String error = context.getString(R.string.error_low_battery, batteryLevel);
                Log.e(TAG, error);
                callback.onError(error);
                throw new RuntimeException(error);
            }
        } catch (Exception e) {
            Log.e(TAG, "检查电池电量时出错", e);
            // 电池检查失败不应该阻止更新，只记录错误
            callback.onProgress(context.getString(R.string.progress_cannot_check_battery));
        }
    }
    
    // 检查存储空间
    private void checkStorageSpace(Context context, SyncCallback callback) {
        try {
            // 检查内部存储空间
            File internalDir = context.getFilesDir();
            StatFs internalStat = new StatFs(internalDir.getPath());
            long internalAvailable = internalStat.getAvailableBytes();
            long internalRequired = 50 * 1024 * 1024; // 至少需要50MB内部存储空间
            
            Log.d(TAG, "内部存储可用空间: " + (internalAvailable / (1024 * 1024)) + "MB");
            
            if (internalAvailable < internalRequired) {
                String error = context.getString(R.string.error_insufficient_storage);
                Log.e(TAG, error);
                callback.onError(error);
                throw new RuntimeException(error);
            }
            
            // 检查外部存储空间（如果可用）
            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                File externalDir = Environment.getExternalStorageDirectory();
                StatFs externalStat = new StatFs(externalDir.getPath());
                long externalAvailable = externalStat.getAvailableBytes();
                long externalRequired = 100 * 1024 * 1024; // 至少需要100MB外部存储空间
                
                Log.d(TAG, "外部存储可用空间: " + (externalAvailable / (1024 * 1024)) + "MB");
                
                if (externalAvailable < externalRequired) {
                    String warning = context.getString(R.string.warning_low_external_storage);
                Log.w(TAG, warning);
                callback.onProgress(warning);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "检查存储空间时出错", e);
            // 存储检查失败不应该阻止更新，只记录错误
            callback.onProgress(context.getString(R.string.progress_cannot_check_storage));
        }
    }
}