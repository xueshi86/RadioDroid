package net.programmierecke.radiodroid2.database;

import androidx.lifecycle.LiveData;
import androidx.paging.DataSource;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import java.util.List;

@Dao
public interface RadioStationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<RadioStation> stations);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(RadioStation station);

    @Update
    void update(RadioStation station);

    // 播放点击上报成功后本地计数 +1，并刷新 lastclicktime
    @Query("UPDATE radio_stations SET clickcount = clickcount + 1, lastclicktime = :now WHERE station_uuid = :uuid")
    void incrementClickCount(String uuid, String now);

    @Delete
    void delete(RadioStation station);

    @Query("DELETE FROM radio_stations")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM radio_stations")
    int getCount();

    @Query("SELECT * FROM radio_stations ORDER BY name ASC LIMIT 1000")
    LiveData<List<RadioStation>> getAllStationsByName();

    @Query("SELECT * FROM radio_stations ORDER BY clickcount DESC")
    LiveData<List<RadioStation>> getAllStationsByClickCount();

    @Query("SELECT * FROM radio_stations WHERE lastcheckok = 1 ORDER BY RANDOM() LIMIT 1")
    RadioStation getRandomStationSync();


    @Query("SELECT * FROM radio_stations ORDER BY clickcount DESC LIMIT 1000")
    LiveData<List<RadioStation>> getStationsByClickCount();

    @Query("SELECT * FROM radio_stations ORDER BY clickcount DESC LIMIT :limit")
    LiveData<List<RadioStation>> getTopClickStations(int limit);

    @Query("SELECT * FROM radio_stations ORDER BY clickcount DESC LIMIT 1000")
    LiveData<List<RadioStation>> getTopClickStationsAll();

    @Query("SELECT * FROM radio_stations ORDER BY votes DESC LIMIT 1000")
    LiveData<List<RadioStation>> getStationsByVotes();

    @Query("SELECT * FROM radio_stations ORDER BY votes DESC LIMIT :limit")
    LiveData<List<RadioStation>> getTopVoteStations(int limit);

    @Query("SELECT * FROM radio_stations ORDER BY votes DESC LIMIT 1000")
    LiveData<List<RadioStation>> getTopVoteStationsAll();

    @Query("SELECT * FROM radio_stations ORDER BY lastchangetime DESC LIMIT 1000")
    LiveData<List<RadioStation>> getStationsByLastChangeTime();

    @Query("SELECT * FROM radio_stations ORDER BY lastchangetime DESC LIMIT :limit")
    LiveData<List<RadioStation>> getRecentlyChangedStations(int limit);

    @Query("SELECT * FROM radio_stations ORDER BY lastchangetime DESC LIMIT 1000")
    LiveData<List<RadioStation>> getRecentlyChangedStationsAll();

    @Query("SELECT * FROM radio_stations WHERE lastcheckok = 1 ORDER BY lastchangetime DESC LIMIT :limit")
    LiveData<List<RadioStation>> getRecentlyChangedWorkingStations(int limit);

    @Query("SELECT * FROM radio_stations WHERE lastcheckok = 1 ORDER BY lastchangetime DESC LIMIT 1000")
    LiveData<List<RadioStation>> getRecentlyChangedWorkingStationsAll();

    @Query("SELECT * FROM radio_stations ORDER BY lastclicktime DESC LIMIT :limit")
    LiveData<List<RadioStation>> getRecentlyPlayedStations(int limit);

    @Query("SELECT DISTINCT country FROM radio_stations WHERE country != '' ORDER BY country ASC")
    LiveData<List<String>> getAllCountries();
    
    @Query("SELECT DISTINCT country FROM radio_stations WHERE country != '' ORDER BY country ASC")
    List<String> getAllCountriesSync();

    @Query("SELECT * FROM radio_stations WHERE country = :country ORDER BY clickcount DESC LIMIT 500")
    LiveData<List<RadioStation>> getStationsByCountry(String country);

    @Query("SELECT DISTINCT language FROM radio_stations WHERE language != '' ORDER BY language ASC")
    LiveData<List<String>> getAllLanguages();
    
    @Query("SELECT DISTINCT language FROM radio_stations WHERE language != '' ORDER BY language ASC")
    List<String> getAllLanguagesSync();

    @Query("SELECT * FROM radio_stations WHERE language = :language ORDER BY clickcount DESC LIMIT 500")
    LiveData<List<RadioStation>> getStationsByLanguage(String language);

    @Query("SELECT * FROM radio_stations WHERE language = :language ORDER BY clickcount DESC")
    LiveData<List<RadioStation>> getStationsByLanguageAll(String language);

    @Query("SELECT * FROM radio_stations WHERE language = :language ORDER BY clickcount DESC LIMIT :limit")
    LiveData<List<RadioStation>> getStationsByLanguageWithLimit(String language, int limit);
    
    @Query("SELECT * FROM radio_stations WHERE language = :language AND countrycode = :countryCode ORDER BY clickcount DESC LIMIT :limit")
    LiveData<List<RadioStation>> getStationsByLanguageAndCountry(String language, String countryCode, int limit);
    
    @Query("SELECT * FROM radio_stations WHERE language = :language AND countrycode = :countryCode ORDER BY clickcount DESC")
    LiveData<List<RadioStation>> getStationsByLanguageAndCountry(String language, String countryCode);
    
    @Query("SELECT * FROM radio_stations WHERE countrycode = :countryCode ORDER BY clickcount DESC LIMIT :limit")
    LiveData<List<RadioStation>> getStationsByCountryWithLimit(String countryCode, int limit);

    @Query("SELECT * FROM radio_stations WHERE countrycode = :countryCode ORDER BY clickcount DESC")
    LiveData<List<RadioStation>> getStationsByCountryCodeAll(String countryCode);

    // 以下三个同步查询与"本地电台"列表页的加载链路（国家→语言→全部，点击量降序）保持一致，
    // 供无播放历史时在后台线程取"列表中第一个可播放电台"（LIMIT 与列表页最大显示数一致）。
    @Query("SELECT * FROM radio_stations WHERE countrycode = :countryCode ORDER BY clickcount DESC LIMIT 1000")
    List<RadioStation> getStationsByCountryCodeAllSync(String countryCode);

    @Query("SELECT * FROM radio_stations WHERE language = :language ORDER BY clickcount DESC LIMIT 1000")
    List<RadioStation> getStationsByLanguageAllSync(String language);

    @Query("SELECT * FROM radio_stations ORDER BY clickcount DESC LIMIT 1000")
    List<RadioStation> getAllStationsByClickCountSync();

    @Query("SELECT DISTINCT tags FROM radio_stations WHERE tags != ''")
    LiveData<List<String>> getAllTags();
    
    @Query("SELECT * FROM radio_stations WHERE tags LIKE '%' || :tag || '%' ORDER BY clickcount DESC LIMIT 500")
    LiveData<List<RadioStation>> getStationsByTag(String tag);
    
    @Query("SELECT COUNT(*) FROM radio_stations")
    int getStationCount();
    
    @Query("SELECT COUNT(*) FROM radio_stations WHERE tags LIKE '%' || :tag || '%'")
    LiveData<Integer> getStationCountByTag(String tag);
    
    @Query("SELECT COUNT(*) FROM radio_stations WHERE tags LIKE '%' || :tag || ',%' OR tags LIKE :tag || ',%' OR tags LIKE '%,' || :tag OR tags = :tag")
    int getStationCountByTagSync(String tag);
    
    // 更精确的标签查询，处理特殊字符如#
    @Query("SELECT COUNT(*) FROM radio_stations WHERE tags LIKE ',' || :tag || ',' OR tags LIKE :tag || ',%' OR tags LIKE '%,' || :tag OR tags = :tag")
    int getStationCountByTagPreciseSync(String tag);
    
    @Query("SELECT COUNT(*) FROM radio_stations WHERE country = :country")
    LiveData<Integer> getStationCountByCountry(String country);
    
    @Query("SELECT COUNT(*) FROM radio_stations WHERE country = :country")
    int getStationCountByCountrySync(String country);
    
    @Query("SELECT COUNT(*) FROM radio_stations WHERE language = :language")
    LiveData<Integer> getStationCountByLanguage(String language);
    
    @Query("SELECT COUNT(*) FROM radio_stations WHERE language = :language")
    int getStationCountByLanguageSync(String language);
    
    @Query("SELECT COUNT(*) FROM radio_stations WHERE lastcheckok = 1")
    int getWorkingStationCount();
    
    @Query("SELECT COUNT(*) FROM radio_stations WHERE lastcheckok = 0")
    int getBrokenStationCount();

    
    // 优化的查询方法 - 一次性获取所有国家及其电台数量
    @Query("SELECT country, COUNT(*) as stationCount FROM radio_stations WHERE country != '' GROUP BY country HAVING COUNT(*) > 0 ORDER BY country ASC")
    List<CountryCount> getAllCountriesWithCountSync();
    
    // 优化的查询方法 - 一次性获取所有语言及其电台数量
    @Query("SELECT language, COUNT(*) as stationCount FROM radio_stations WHERE language != '' GROUP BY language HAVING COUNT(*) > 0 ORDER BY language ASC")
    List<LanguageCount> getAllLanguagesWithCountSync();
    
    // 获取所有标签字符串（原始格式，包含逗号分隔的多个标签）
    @Query("SELECT DISTINCT tags FROM radio_stations WHERE tags != '' AND tags != ','")
    List<String> getAllTagStringsSync();

    // 加权搜索：前缀匹配(0) > 词边界(1) > 包含(2) > 标签前缀(3) > 标签等值(4)；clickcount 次级排序
    @Query("SELECT * FROM radio_stations WHERE name LIKE :query || '%' ESCAPE '\\' OR name LIKE '%' || :query || '%' ESCAPE '\\' OR tags LIKE '%' || :query || ',%' ESCAPE '\\' OR tags LIKE :query || ',%' ESCAPE '\\' OR tags LIKE '%,' || :query ESCAPE '\\' OR tags = :query OR country LIKE :query || '%' ESCAPE '\\' OR country LIKE '%' || :query || '%' ESCAPE '\\' OR language LIKE :query || '%' ESCAPE '\\' OR language LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY CASE WHEN name LIKE :query || '%' ESCAPE '\\' THEN 0 WHEN name LIKE '% ' || :query || '%' ESCAPE '\\' OR name LIKE '%-' || :query || '%' ESCAPE '\\' OR name LIKE '%(' || :query || '%' ESCAPE '\\' OR name LIKE '%.' || :query || '%' ESCAPE '\\' THEN 1 WHEN name LIKE '%' || :query || '%' ESCAPE '\\' THEN 2 WHEN tags LIKE :query || ',%' ESCAPE '\\' THEN 3 WHEN tags = :query THEN 4 ELSE 5 END, clickcount DESC LIMIT 100")
    LiveData<List<RadioStation>> searchStations(String query);
    
    // 使用FTS进行快速搜索（前缀 token + 加权排序）
    @Query("SELECT rs.* FROM radio_stations rs JOIN radio_stations_fts fts ON rs.station_uuid = fts.station_uuid WHERE radio_stations_fts MATCH :query || '*' ORDER BY CASE WHEN rs.name LIKE :query || '%' ESCAPE '\\' THEN 0 WHEN rs.name LIKE '% ' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%-' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%(' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%.' || :query || '%' ESCAPE '\\' THEN 1 ELSE 2 END, rs.clickcount DESC LIMIT 100")
    LiveData<List<RadioStation>> searchStationsFast(String query);
    
    // 使用FTS按名称搜索
    @Query("SELECT rs.* FROM radio_stations rs JOIN radio_stations_fts fts ON rs.station_uuid = fts.station_uuid WHERE radio_stations_fts MATCH :query || '*' ORDER BY CASE WHEN rs.name LIKE :query || '%' ESCAPE '\\' THEN 0 WHEN rs.name LIKE '% ' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%-' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%(' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%.' || :query || '%' ESCAPE '\\' THEN 1 ELSE 2 END, rs.clickcount DESC LIMIT 100")
    LiveData<List<RadioStation>> searchStationsByNameFast(String query);
    
    // 使用FTS按标签搜索
    @Query("SELECT rs.* FROM radio_stations rs JOIN radio_stations_fts fts ON rs.station_uuid = fts.station_uuid WHERE radio_stations_fts MATCH :query || '*' ORDER BY CASE WHEN rs.name LIKE :query || '%' ESCAPE '\\' THEN 0 WHEN rs.name LIKE '% ' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%-' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%(' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%.' || :query || '%' ESCAPE '\\' THEN 1 ELSE 2 END, rs.clickcount DESC LIMIT 100")
    LiveData<List<RadioStation>> searchStationsByTagsFast(String query);
    
    // 使用FTS按国家搜索
    @Query("SELECT rs.* FROM radio_stations rs JOIN radio_stations_fts fts ON rs.station_uuid = fts.station_uuid WHERE radio_stations_fts MATCH :query || '*' ORDER BY CASE WHEN rs.name LIKE :query || '%' ESCAPE '\\' THEN 0 WHEN rs.name LIKE '% ' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%-' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%(' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%.' || :query || '%' ESCAPE '\\' THEN 1 ELSE 2 END, rs.clickcount DESC LIMIT 100")
    LiveData<List<RadioStation>> searchStationsByCountryFast(String query);
    
    // 使用FTS按语言搜索
    @Query("SELECT rs.* FROM radio_stations rs JOIN radio_stations_fts fts ON rs.station_uuid = fts.station_uuid WHERE radio_stations_fts MATCH :query || '*' ORDER BY CASE WHEN rs.name LIKE :query || '%' ESCAPE '\\' THEN 0 WHEN rs.name LIKE '% ' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%-' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%(' || :query || '%' ESCAPE '\\' OR rs.name LIKE '%.' || :query || '%' ESCAPE '\\' THEN 1 ELSE 2 END, rs.clickcount DESC LIMIT 100")
    LiveData<List<RadioStation>> searchStationsByLanguageFast(String query);

    @Query("SELECT * FROM radio_stations WHERE name LIKE :query || '%' ESCAPE '\\' OR name LIKE '%' || :query || '%' ESCAPE '\\' OR tags LIKE '%' || :query || ',%' ESCAPE '\\' OR tags LIKE :query || ',%' ESCAPE '\\' OR tags LIKE '%,' || :query ESCAPE '\\' OR tags = :query ORDER BY CASE WHEN name LIKE :query || '%' ESCAPE '\\' THEN 0 WHEN name LIKE '% ' || :query || '%' ESCAPE '\\' OR name LIKE '%-' || :query || '%' ESCAPE '\\' OR name LIKE '%(' || :query || '%' ESCAPE '\\' OR name LIKE '%.' || :query || '%' ESCAPE '\\' THEN 1 WHEN name LIKE '%' || :query || '%' ESCAPE '\\' THEN 2 WHEN tags LIKE :query || ',%' ESCAPE '\\' THEN 3 WHEN tags = :query THEN 4 ELSE 5 END, clickcount DESC LIMIT 100")
    LiveData<List<RadioStation>> searchStationsByName(String query);

    @Query("SELECT * FROM radio_stations WHERE tags LIKE '%' || :query || ',%' OR tags LIKE :query || ',%' OR tags LIKE '%,' || :query OR tags = :query ORDER BY clickcount DESC LIMIT 100")
    LiveData<List<RadioStation>> searchStationsByTags(String query);

    @Query("SELECT * FROM radio_stations WHERE country LIKE '%' || :query || '%' ORDER BY clickcount DESC LIMIT 100")
    LiveData<List<RadioStation>> searchStationsByCountry(String query);

    @Query("SELECT * FROM radio_stations WHERE language LIKE '%' || :query || '%' ORDER BY clickcount DESC LIMIT 100")
    LiveData<List<RadioStation>> searchStationsByLanguage(String query);

    @Query("SELECT * FROM radio_stations WHERE country = :countryCode ORDER BY clickcount DESC LIMIT 500")
    LiveData<List<RadioStation>> getStationsByCountryCode(String countryCode);

    @Query("SELECT * FROM radio_stations WHERE language = :language ORDER BY clickcount DESC LIMIT 500")
    LiveData<List<RadioStation>> getStationsByLanguageExact(String language);

    @Query("SELECT * FROM radio_stations WHERE tags LIKE '%' || :tag || '%' ORDER BY clickcount DESC LIMIT 500")
    LiveData<List<RadioStation>> getStationsByTagExact(String tag);

    // 用于分页查询的方法
    @Query("SELECT * FROM radio_stations ORDER BY name ASC")
    DataSource.Factory<Integer, RadioStation> getAllStationsPaged();
    
    // 获取所有电台数据的方法
    @Query("SELECT * FROM radio_stations")
    List<RadioStation> getAllStations();

    @Query("SELECT * FROM radio_stations ORDER BY clickcount DESC")
    DataSource.Factory<Integer, RadioStation> getStationsByClickCountPaged();

    @Query("SELECT * FROM radio_stations ORDER BY votes DESC")
    DataSource.Factory<Integer, RadioStation> getStationsByVotesPaged();

    @Query("SELECT * FROM radio_stations ORDER BY lastchangetime DESC")
    DataSource.Factory<Integer, RadioStation> getStationsByLastChangeTimePaged();

    @Query("SELECT * FROM radio_stations WHERE name LIKE '%' || :query || '%' ESCAPE '\\' OR tags LIKE '%' || :query || '%' ESCAPE '\\' OR country LIKE '%' || :query || '%' ESCAPE '\\' OR language LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY CASE WHEN name LIKE :query || '%' ESCAPE '\\' THEN 0 WHEN name LIKE '% ' || :query || '%' ESCAPE '\\' OR name LIKE '%-' || :query || '%' ESCAPE '\\' OR name LIKE '%(' || :query || '%' ESCAPE '\\' OR name LIKE '%.' || :query || '%' ESCAPE '\\' THEN 1 ELSE 2 END, clickcount DESC")
    DataSource.Factory<Integer, RadioStation> searchStationsPaged(String query);

    @Query("SELECT * FROM radio_stations WHERE station_uuid = :stationId")
    RadioStation getStationById(String stationId);

    @Query("SELECT station_uuid FROM radio_stations")
    List<String> getAllStationIds();
    
    @Query("SELECT DISTINCT url FROM radio_stations WHERE url IS NOT NULL AND url != ''")
    List<String> getAllStationUrls();

    @Query("DELETE FROM radio_stations WHERE station_uuid IN (:stationIds)")
    void deleteStationsByIds(List<String> stationIds);

    /**
     * FTS4 外部内容表索引重建（'rebuild' 命令）。
     * F-1 修复：全量/增量更新后必须调用，否则 FTS 索引与主表失步，新台 FTS 搜不到。
     * 5 万行实测数秒级，应在后台线程执行。
     */
    @Query("INSERT INTO radio_stations_fts(radio_stations_fts) VALUES('rebuild')")
    void rebuildFtsIndex();

    /**
     * 获取本地库中最大的 lastchangetime（增量更新时间水位锚点）。
     * 格式 YYYY-MM-DD HH:mm:ss，定宽字符串可直接比较。
     */
    @Query("SELECT lastchangetime FROM radio_stations ORDER BY lastchangetime DESC LIMIT 1")
    String getMaxLastChangeTime();
    
    /**
     * 多条件搜索电台
     * @param country 国家筛选条件，为空表示不筛选
     * @param language 语言筛选条件，为空表示不筛选
     * @param tag 标签筛选条件，为空表示不筛选
     * @param keyword 关键词搜索，为空表示不搜索
     * @return 符合条件的电台列表
     */
    @Query("SELECT * FROM radio_stations WHERE (:country = '' OR country = :country) AND (:language = '' OR language = :language) AND (:tag = '' OR tags LIKE ',' || :tag || ',' OR tags LIKE :tag || ',%' OR tags LIKE '%,' || :tag OR tags = :tag) AND (:keyword = '' OR name LIKE :keyword || '%' ESCAPE '\\' OR name LIKE '%' || :keyword || '%' ESCAPE '\\' OR country LIKE :keyword || '%' ESCAPE '\\' OR country LIKE '%' || :keyword || '%' ESCAPE '\\' OR language LIKE :keyword || '%' ESCAPE '\\' OR language LIKE '%' || :keyword || '%' ESCAPE '\\' OR tags LIKE '%' || :keyword || '%' ESCAPE '\\') ORDER BY CASE WHEN :keyword != '' AND name LIKE :keyword || '%' ESCAPE '\\' THEN 0 WHEN :keyword != '' AND (name LIKE '% ' || :keyword || '%' ESCAPE '\\' OR name LIKE '%-' || :keyword || '%' ESCAPE '\\' OR name LIKE '%(' || :keyword || '%' ESCAPE '\\' OR name LIKE '%.' || :keyword || '%' ESCAPE '\\') THEN 1 WHEN :keyword != '' AND name LIKE '%' || :keyword || '%' ESCAPE '\\' THEN 2 ELSE 3 END, clickcount DESC LIMIT 1000")
    LiveData<List<RadioStation>> searchStationsByMultiCriteria(String country, String language, String tag, String keyword);
    
    // 获取搜索建议
    @Query("SELECT DISTINCT name FROM radio_stations WHERE name LIKE :query || '%' ORDER BY name ASC LIMIT 5")
    LiveData<List<String>> getSearchSuggestions(String query);
    
    // 获取标签建议
    @Query("SELECT DISTINCT tags FROM radio_stations WHERE tags LIKE '%' || :query || '%' LIMIT 10")
    LiveData<List<String>> getTagSuggestions(String query);
}