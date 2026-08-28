# AMARadio 高价值借鉴 7 项落地实施方案

> 版本：v1.1（多专家评审修订版）
> 编写日期：2026-08-26
> 依据：`AMARadio_特性调研与借鉴建议.md` 第二节「高价值借鉴建议」第 1-7 项
> 评审方式：8 个专业角色对本方案进行全方位审核，评审发现已并入各节「评审修订要点」，跨项问题见第三节
> 范围说明：不含桌面 Widget（中价值项，另行评估）；本文档为实施方案，不含代码改动

---

## 目录

1. 总览与实施顺序
2. 七项落地实施方案（每项含：现状定位 → 方案设计 → 改动文件 → 实施步骤 → 验证方案 → 风险与回退 → 评审修订要点）
3. 跨项评审结论（横切问题与既有 Bug）
4. 全局测试与验收清单
5. 附录：评审专家矩阵

---

## 1. 总览与实施顺序

### 1.1 范围

| # | 建议 | 核心动作 | 预估工作量 | 依赖 |
|---|------|---------|-----------|------|
| 1 | 播放点击上报 | ClickReporter + 防双计 + 本地计数 | 1 天 | 依赖 6（级联复用） |
| 2 | Ogg Vorbis/Opus 元数据 | VorbisComment 接入元数据链 | 2-3 天 | 无（M0 探针先行） |
| 3 | 增量数据库更新 | lastchange 端点 + FTS 同步 | 4-5 天 | 依赖 6；含 Bug 修复 F-1 |
| 4 | 搜索加权排序 | 3 级权重 + FTS 前缀加权 | 1 天 | 无 |
| 5 | 过滤下拉可搜索 | SearchableListDialog + 标签拆分 | 1.5-2 天 | 无（与 4 同域可连做） |
| 6 | 镜像级联 failover | 服务器持久化 + 级联 + 短超时 | 1-1.5 天 | 无（建议先于 1、3） |
| 7 | 动态电台占位符 | 首字符 + 7 色占位统一工具 | 0.5-1 天 | 无 |

**合计约 11-14 人日**（含测试与回归；原估算 8-11 人日，评审后加入 FTS 修复、防双计、单测、8 语言文案等增量）。

### 1.2 实施顺序（PERT）

```
第一梯队（独立、见效快）     7 动态占位符 ──→ 4 搜索加权
第二梯队（打底 + 同域）      6 镜像级联 ──→ 1 点击上报
                                        └─→ 5 过滤下拉（与 4 同域）
第三梯队（需探针）           2 Ogg 元数据（M0 探针 → 实现）
第四梯队（最重，含 Bug 修复）3 增量数据库更新（含 F-1 FTS 修复）
```

- **6 必须先于 1、3**：点击上报与增量更新都要用级联后的服务器选择。
- **7、4 无依赖可并行**，各半天到一天，快速见效。
- **3 放最后**：改动面最大，且依赖 6 稳定服务器，避免同时引入两个大变更。

### 1.3 公共约定

- 分支：每项独立分支 `feature/ama-<n>-<name>`，独立 commit，可单独回滚。
- 日志 TAG：新增统一前缀 `AMA-`，如 `AMA-Click`、`AMA-Vorbis`、`AMA-IncUpdate`。
- 字符串：新增文案全部进 `strings.xml`，先落中文+英文，其余 6 语言留 TODO 占位（项目 8 语言同步是既定规范）。
- 偏好：新增设置项需在 `FragmentSettings` 与 `preferences.xml` 同时注册。
- minSdk 16 红线：不引入递归 CTE、不引入 API 21+ 独占调用（个别 API 需 `Build.VERSION` 守卫）。

---

## 2. 七项落地实施方案

### 建议 1：播放点击上报（Click Counting）

#### 现状与代码定位
- 播放成功回调：`PlayerService.onStateChanged` `case Playing:`（`service/PlayerService.java` L2000-2039，幂等守卫 `playStateIsPlaying` L2001，首次进入分支 L2013 之后）。
- 当前电台：`currentStation`（L134），含 `StationUuid`/`ClickCount`。
- 本地已有：`RadioStation.clickcount` 字段（`database/RadioStation.java` L57-58）+ 按点击排序查询，但无播放时上报、无 `clickcount+1` 的 DAO 方法（`RadioStationDao` 仅通用 `@Update`，且 Repository 中无调用）。
- click 端点先例：`Utils.getRealStationLink`（`Utils.java` L264-278）GET `json/url/<uuid>`——**该端点即计点击**；`PlayStationTask`（`players/PlayStationTask.java` L111-150）仅在本地 `StreamUrl` 为空时走它（L133）。
- HTTP：`RadioDroidApp.getHttpClient()`（10s 超时 + UA）；`Utils.downloadFeed` 有文件缓存副作用，**不可复用**。
- 冷却：已有 `utils/RateLimiter.java`（令牌桶）可参考。

#### 方案设计
1. **新增 `service/ClickReporter.java`**（单例，静态持有）：
   - `report(DataRadioStation station)`：校验 uuid 有效 → 防双计标志消费 → 5s 冷却（`ConcurrentHashMap<uuid, Long>`）→ OkHttp **异步 enqueue**（不新建线程池，回调中处理）。
   - 请求：GET `json/url/<uuid>`（响应体忽略），经 `RadioBrowserServerManager` 当前服务器构造，失败走级联（复用建议 6 的方法）。
   - 成功回调：`RadioStationDao` 新增 `@Query("UPDATE radio_stations SET clickcount = clickcount + 1, lastclicktime = :now WHERE station_uuid = :uuid")`，经 `RadioStationRepository` 单线程 executor（L52）执行；同步 `currentStation.ClickCount++`。
   - 失败：静默（Log.d 记录），不重试。
2. **防双计（评审重点）**：`Utils.getRealStationLink` 内、HTTP 调用前设置静态 `volatile boolean Utils.lastClickEndpointResolved = true`；`ClickReporter.report` 开头消费并清除该标志，为 true 则跳过网络上报（仅本地计数）。覆盖全部 4 个调用方：PlayStationTask L133、**AlarmReceiver L199（闹钟播放路径，评审新发现）**、StationActions L100/L193（后两者不触发 Playing 回调，安全）。
3. **隐私开关**：新增偏好 `report_click_counts`（默认开，`preferences.xml` + `FragmentSettings`）；关闭时仅本地计数不上报。
4. **服务器选择**：优先 `RadioBrowserServerManager.getCurrentServer()`（建议 6 落地后为持久化值），失败级联，全失败静默。

#### 改动文件
- 新增：`service/ClickReporter.java`
- 修改：`service/PlayerService.java`（Playing 分支调用）、`Utils.java`（防双计标志）、`database/RadioStationDao.java`（UPDATE 方法）、`database/RadioStationRepository.java`（转发）、`res/xml/preferences.xml` + `FragmentSettings.java` + `strings.xml`（开关项）

#### 实施步骤
1. DAO/Repository 增补 clickcount UPDATE 方法。
2. 实现 ClickReporter（冷却 + 异步请求 + 失败静默）。
3. PlayerService Playing 首次进入分支接入；接入防双计标志。
4. 设置项 + 文案。
5. 测试（见验证）。

#### 验证方案
- 单测（JVM）：冷却器 5s 内同 uuid 只报一次；防双计标志消费逻辑。
- 真机/模拟器 + MockWebServer（项目已有 androidTest 依赖）：
  - 播放本地 URL 台 → 观察收到 `json/url/<uuid>` 请求（Log + Mock 计数）→ 本地 clickcount +1；
  - 5s 内重复播放同台 → 仅 1 次请求；
  - 播放 StreamUrl 为空的台 → **无** 网络上报（防双计生效）但有本地 +1；
  - 闹钟触发播放 → 无网络上报；
  - 断网播放 → 无崩溃、无报错日志刷屏。

#### 风险与回退
- 双计风险：已用标志统一消除；若未来新增 click 端点调用方，需同步置标志（文档注明）。
- 本地 clickcount 会被下次全量/增量同步用服务器值覆盖（见 3.3 决策 B3）：上报成功则服务器已计，覆盖后数值仍正确；上报失败则本地增量仅存续到下次同步，属预期行为，文档明示。
- 回退：仅移除 ClickReporter 调用与开关默认值即可。

#### 评审修订要点
- 🟡 原方案"POST click 端点"表述有误——radio-browser 无独立 POST click 端点，官方语义即 GET `json/url/<uuid>` 计点击，统一为 GET。
- 🔴 新增：闹钟路径（AlarmReceiver L199）也会触发 Playing 回调造成双计，防双计范围扩大为全部 getRealStationLink 调用方。
- 🟡 新增：隐私开关（用户收听行为回传第三方服务，应有显式开关，参照现有"加载图标"开关先例 `shouldLoadIcons`）。
- 🟢 上报改用 OkHttp enqueue 异步，避免 AsyncTask/线程池生命周期问题（JobIntentService 进程随时可能被杀）。

---

### 建议 2：Ogg Vorbis / Opus 流元数据支持

#### 现状与代码定位
- 元数据主链路：`IcyDataSource`（`players/exoplayer/IcyDataSource.java`）解析 ICY → `onDataSourceStreamLiveInfo` → `ExoPlayerWrapper` L439 → `RadioPlayer` L425 → `PlayerService.foundLiveStreamInfo`（L2156）→ 广播 `PLAYER_SERVICE_META_UPDATE`（L2173）+ 通知 + 曲目历史（L2184-2204）。
- `ExoPlayerWrapper.onMetadata(Metadata)`（L357-396）：已处理 `IcyInfo`/`IcyHeaders`，`Id3Frame` 空实现（L391-393）；**缺 `VorbisComment` 分支**。
- 依赖：ExoPlayer 2.18.2 `exoplayer-core`（含 `metadata.vorbis.VorbisComment`）；`ProgressiveMediaSource` 默认 extractor 链含 OggExtractor。

#### 方案设计
1. **M0 探针（评审新增里程碑）**：先用 2-3 个真实 Ogg/Opus 流验证 ExoPlayer 2.18.2 的 OggExtractor 是否产出 `VorbisComment` metadata（临时在 `onMetadata` 打日志）。**探针结果决定实现路径**：
   - 路径 A（预期）：extractor 产出 → 仅加分支；
   - 路径 B（兜底）：在 DataSource 层仿 `IcyDataSource.processMetadataBlock`（L253-308）自解析 Ogg 页（`OggS` 捕获 + `\x01` 注释块 + 长度前缀），成本 +1-2 天。
2. **VorbisComment 分支**（路径 A）：`entry instanceof VorbisComment` → 取 `TITLE`/`ARTIST` 键值 → 构造 `StreamLiveInfo`（`station/live/StreamLiveInfo.java`，key 兼容 `StreamTitle`）→ 复用 IcyInfo 的转发路径，下游零改动。
3. **优先级与去重**：与 ICY 并存时按到达顺序覆盖；相同标题（去空白/大小写归一）不重复广播，防切歌刷屏。
4. **链式流（chained streams）**：列为**后续增强**，首版不承诺"换链不重载"——Ogg chaining 在 extractor 层支持有限，做透需 DataSource 层解析，避免范围膨胀。

#### 改动文件
- 修改：`players/exoplayer/ExoPlayerWrapper.java`（onMetadata 分支）
- 可能新增：`players/exoplayer/OggVorbisParser.java`（仅路径 B）

#### 实施步骤
1. M0 探针：临时日志 → 真机播放候选台 → 确认路径。
2. 按路径实现；去重/优先级逻辑。
3. 回归 ICY 台（确认无回归）。

#### 验证方案
- 候选测试台清单（实施时从 radio-browser 按 tag 检索）：tag=ogg、tag=opus 的真实台 5-10 个（欧洲小众台/播客流），逐一播放验证曲目名显示、曲目历史记录、通知曲目更新。
- 日志观察点：`AMA-Vorbis` TAG 出现 TITLE/ARTIST；`PLAYER_SERVICE_META_UPDATE` 广播触发。
- 回归：Shoutcast ICY 台（如 SomaFM 等）曲目显示不受影响。

#### 风险与回退
- 探针失败（extractor 不产出）→ 走路径 B，工作量上浮。
- Ogg 流在弱网下 metadata 更新频率低——属流本身特性，非 bug。
- 回退：移除 onMetadata 分支即可。

#### 评审修订要点
- 🔴 原方案"Ogg 链式流换链不重载"风险高，评审降级为后续增强，首版只做单流 VorbisComment 接入。
- 🟡 新增 M0 探针里程碑，避免双路径盲做。
- 🟢 新增"同值去重"，防曲目标题刷屏导致通知/历史高频写入。

---

### 建议 3：增量数据库更新（含 FTS 失步修复）

#### 现状与代码定位
- 全量更新链路：`RadioStationRepository.syncAllStationsFromNetworkInternal`（L138-578）：测速选最快（L613-660）→ 取总数（L166）→ temp 库（L223-234）→ 分页 100/页（L237-238）→ 多线程下载（L296-330，URL `json/stations?limit=100&offset=` L351）→ temp 批量 insertAll（L482-491）→ `replaceMainFromTemp`（L583-610：deleteAll + insertAll）。
- 触发：`FragmentSettings.startDatabaseUpdate`（L641-649）→ `DatabaseUpdateManager`（L34-96）→ `DatabaseUpdateWorker`（L131-284，ReentrantLock 单实例 L130 附近，30 分钟断点恢复）。
- 表结构：`RadioStation.station_uuid` 主键（天然适配 REPLACE）；`lastchangetime` ISO 字符串（L78-79）；`getAllStationIds`/`deleteStationsByIds` 已有（L234-241）。
- **🐛 F-1（评审发现既有 Bug）**：FTS4 外部内容表 `radio_stations_fts`（`RadioStationFts.java`，@Fts4 contentEntity）**无任何同步机制**（仅 MIGRATION_4_5 一次性填充）；`replaceMainFromTemp` 的 deleteAll+insertAll 后 FTS 索引不更新。`FragmentStations` L96/L323 在用 `searchStationsFast`（FTS 查询）→ **每次全量更新后，新入库电台 FTS 搜不到，FTS 搜索退化为旧数据子集**。

#### 方案设计
1. **端点与水位**：GET `json/stations/lastchange?lastchangeuuid=<uuid>&limit=1000&offset=0`，返回按 lastchangetime 升序的变更站列表；**水位 = 返回列表最后一条的 uuid**；空列表水位不变；分页中断后以"最后成功批次水位"续跑（天然断点续传）。双保险：水位 uuid + 对应 lastchangetime 字符串同时持久化（prefs：`incremental_lastchange_uuid` / `incremental_lastchange_time`）。
2. **同步方法**：新增 `RadioStationRepository.syncIncrementalStations(callback)`：
   - 循环拉取（复用 `Utils.downloadFeedFromServer` 级联，建议 6 落地后带 failover）→ `DataRadioStation.DecodeJson` → 主库直写 `insertAll`（REPLACE，批量 1000）→ 同步维护 FTS（见 3）→ 推进水位 → 批次 < limit 或出错停止。
   - 复用 `DatabaseUpdateWorker` 的前台通知/进度框架，新增 mode 参数（full/incremental）；与全量共用 ReentrantLock 防竞态。
3. **FTS 同步（评审核心）**：
   - 增量：仅对本次变更 uuid 逐行 `DELETE FROM radio_stations_fts WHERE station_uuid=:uuid` + `INSERT INTO radio_stations_fts(station_uuid,name,tags,country,language) VALUES(...)`（变更量通常数百，成本低）；DAO 新增两个方法。
   - 修复 F-1：全量 `replaceMainFromTemp` 完成后执行 `INSERT INTO radio_stations_fts(radio_stations_fts) VALUES('rebuild')`（FTS4 内置重建命令；5 万行实测 2-10s，放后台线程 + 进度提示）；异常时增量路径 fallback 到 rebuild。
4. **触发**：
   - 手动：设置页新增「增量更新」项（保留现有「更新电台数据库」全量）。
   - 自动：`ActivityMain`/`RadioDroidApp` 启动后检查——距上次同步（`local_database_last_update`）>24h 且网络可用 → WorkManager 静默增量（`NetworkType.CONNECTED` 约束；新增偏好「仅 Wi-Fi 下自动增量」，默认关）。
5. **已知局限（明示）**：增量不处理远端删除的电台；提供「每 30 天自动全量校准」偏好（默认开）或用户手动全量兜底。

#### 改动文件
- 修改：`RadioStationRepository.java`（新增同步方法 + 水位读写）、`RadioStationDao.java`（FTS 行级方法 + rebuild）、`DatabaseUpdateWorker.java`/`DatabaseUpdateManager.java`（mode 参数）、`FragmentSettings.java` + `preferences.xml` + `strings.xml`、`ActivityMain.java` 或 `RadioDroidApp.java`（启动检查）
- 新增：无（复用 Worker 框架）

#### 实施步骤
1. DAO 增补：FTS 行级 delete/insert、rebuild 语句。
2. Repository 实现 syncIncrementalStations + 水位持久化。
3. Worker mode 化 + 手动入口。
4. 启动自动检查逻辑。
5. **修复 F-1**（全量更新后 rebuild）。
6. 测试。

#### 验证方案
- 全量更新前后：`searchStationsFast` 能搜到新台（验证 F-1 修复）。
- 增量场景：构造两个全量库时间点（或 Mock 服务器返回 lastchange 数据）→ 增量后新台出现、变更台字段更新、FTS 可搜。
- 中断恢复：增量中途杀进程 → 重跑从水位继续，无重复/无遗漏。
- 竞态：增量与全量同时触发 → 单实例锁生效，第二个任务排队/合并。
- 性能：5 万行库上 rebuild 计时；增量批次（1000 条）端到端耗时。

#### 风险与回退
- lastchange 端点语义（水位锚点行为）需实施时以官方 API 文档核对——已列"实施时确认清单"。
- FTS rebuild 全库锁表期间搜索短暂不可用（秒级）——后台执行 + 失败可重试。
- 回退：关闭自动增量、保留手动全量；水位 key 冗余存储无副作用。

#### 评审修订要点
- 🔴 **F-1 为既有 Bug，评审强制要求并入本项**：否则增量同步后 FTS 搜索继续失步。
- 🟡 原方案未涉及 FTS 同步——外部内容表不会自动维护，是本项最大技术坑。
- 🟡 新增双保险水位（uuid + 时间），防水位损坏后无法恢复。
- 🟢 删除兜底策略（30 天全量校准）补齐增量局限。

---

### 建议 4：搜索加权评分（前缀优先 + 词边界）

#### 现状与代码定位
- 主搜索：`RadioStationDao.searchStationsByMultiCriteria`（L251）——LIKE 组合 + 2 级 CASE 权重（name 前缀 0 / 包含 1）+ clickcount DESC LIMIT 1000；调用方 `FragmentMultiSearch.performMultiSearch`（L400-423）。
- FTS 搜索：5 个方法（`searchStationsFast` 等，L171-188）SQL 相同、**无权重**；调用方 `FragmentStations`（L96/L323）。
- 内存过滤：`StationsFilter`（L74-135，Levenshtein + weight/4 + ClickCount 次级），经 adapter 可能再排。

#### 方案设计
1. **3 级权重 SQL**（两处统一）：`CASE WHEN name LIKE :kw || '%' THEN 0 WHEN name LIKE '% ' || :kw || '%' OR name LIKE '%-' || :kw || '%' OR name LIKE '%(' || :kw || '%' OR name LIKE '%.' || :kw || '%' THEN 1 ELSE 2 END`，ORDER BY 权重, clickcount DESC。词边界字符白名单：空格/连字符/左括号/点（覆盖 "Radio.FM"、"90s-FM" 等常见命名）；全部参数化防注入。
2. **FTS 查询加权**：`MATCH :kw || '*'`（FTS4 前缀 token）+ 同一 CASE 权重（对 name 列判断）；5 个方法统一 SQL 文本但**保留方法签名**（保守重构，不合并方法）。
3. **与内存过滤对齐（验证点）**：确认 `FragmentStations`/`FragmentLocalStations` 结果是否再经 `StationsFilter` 重排；若重排，需保证两侧权重语义一致（前缀 > 词边界 > 包含，clickcount 次级），否则 DAO 权重会被覆盖。
4. 大小写：SQLite LIKE 对 ASCII 大小写不敏感，符合预期；中文拼音匹配不在范围（现状如此）。

#### 改动文件
- 修改：`database/RadioStationDao.java`（L171-188 五个 FTS SQL + L251 主搜索 SQL）

#### 实施步骤
1. 修改 `searchStationsByMultiCriteria` SQL（3 级权重）。
2. 统一 5 个 FTS 查询 SQL（前缀 MATCH + 权重）。
3. 验证 `StationsFilter` 交互，必要时对齐。

#### 验证方案
- SQL 行为验证（JVM 或 sqlite3 手工构造数据）：搜 "jazz" → "Jazz FM" 在 "Ultra Jazz" 前；搜 "sch" → Sweden/Switzerland 在 Munich 前；词边界 "fm" → "ABC FM" 优先于 "FM Radio" 之外的后缀。
- 真机：FragmentMultiSearch 关键词搜索排序肉眼验证；FragmentStations FTS 搜索排序验证。
- 回归：空关键词/特殊字符（%、_、'）不崩溃（LIKE 通配符转义——`%`/`_` 需 ESCAPE，评审补充）。

#### 风险与回退
- LIKE 通配符注入：`%`/`_` 需 `ESCAPE '\'` 处理（评审补充点）。
- 5 个 FTS 方法统一 SQL 若引入回归，可逐个回退。
- 回退：整体还原 DAO SQL 即可（无结构性改动）。

#### 评审修订要点
- 🟡 词边界字符集从单一空格扩展为白名单（空格/连字符/括号/点）。
- 🟡 LIKE 通配符 `%`/`_` 转义（ESCAPE），防用户输入通配符导致结果失真。
- 🟢 明确 FTS 与内存过滤（StationsFilter）的两层排序关系为验证点，避免权重被覆盖。

---

### 建议 5：过滤下拉"包含"搜索 + 智能建议

#### 现状与代码定位
- `FragmentMultiSearch`：`CustomSpinner` ×3（country/language/tag）+ `ArrayAdapter`；选项来自 `repository.getAllCountries/getAllLanguages/getAllTags`（LiveData，DAO L84/L90/L117）。
- **🐛 F-2（评审发现）**：`getAllTags()` 为 `SELECT DISTINCT tags`——**返回原始逗号组合串**（如 "rock,pop"），标签下拉展示的是组合串而非单标签，上万组合无法检索。
- 防抖：`FragmentMultiSearch` L76-79 已有 500ms Handler 模式。
- `CustomSpinner`（`views/CustomSpinner.java`）为普通 AppCompatSpinner 无重写。

#### 方案设计
1. **交互形态（用户已确认）**：保留布局，点击下拉改为弹**可搜索对话框**：
   - 新增 `ui/SearchableListDialogFragment`：标题 + EditText（500ms 防抖）+ ListView（ArrayAdapter）+ 「全部」置顶 + 当前选中项高亮 + 命中计数。
   - 过滤：内存 `contains`（大小写不敏感）+ **前缀命中优先**排序（稳定）。
   - 实现方式：**推荐把三个 CustomSpinner 换为 MaterialButton（带下拉箭头 drawable）**，点击弹对话框——避免拦截 Spinner 点击（AppCompatSpinner 点击由内部 ListPopupWindow 处理，`setOnClickListener` 不可预期；OnTouchListener 拦截可行但脆）。
2. **标签数据源修复（F-2）**：
   - 复用 `getAllTagStringsSync`（DAO L165）→ Repository 新增 `getAllTagsSplitSync()`：**Java 内存拆分**（逗号 split + trim + 去重 + 计数），后台线程执行（5 万行 tags 字段扫描约百毫秒级，一次性）。
   - **不用 SQL 递归 CTE**（minSdk 16 设备 SQLite 版本不定，兼容红线）。
   - 排序：出现次数降序（热门标签优先）→ 前缀命中置顶。
3. **国家/语言**：沿用现有 DISTINCT 列表 + 对话框过滤（数据量小，无需拆分）。
4. **防抖复用**：抽公共防抖工具类（低优先级，先复制现有 Handler 模式）。

#### 改动文件
- 新增：`ui/SearchableListDialogFragment.java`
- 修改：`station/FragmentMultiSearch.java`、布局 `fragment_multi_search.xml`（Spinner → MaterialButton）、`RadioStationRepository.java`（getAllTagsSplitSync）、`strings.xml`

#### 实施步骤
1. Repository 拆分方法（含计数）。
2. SearchableListDialogFragment 实现。
3. FragmentMultiSearch 三个下拉接入对话框 + 标签走新数据源。
4. 文案。

#### 验证方案
- 真机：国家/语言/标签三个下拉——输入即过滤、前缀优先、选中回显、重置按钮联动。
- 标签下拉：显示单标签（非逗号串）；搜索 "rock" 出现含 rock 的标签并排前。
- 性能：5 万行库标签列表加载耗时（后台线程，UI 无卡顿）。
- 回归：现有按国家/语言/标签筛选逻辑不变（选中后 performMultiSearch 照常）。

#### 风险与回退
- 标签拆分会改变现有"组合串"筛选语义（原选择 "rock,pop" 是整串匹配）——**属修复而非回归**（组合串本就不该作为选项）；拆后按单标签匹配，结果更准确。
- 回退：保留原 Spinner + 原数据源可整体还原。

#### 评审修订要点
- 🔴 **F-2（标签原始逗号串）是本项隐藏前提**：不修数据源，对话框做出来也是错误的选项列表。
- 🟡 新增"计数 + 热门优先"排序，贴合 AMARadio 的"按相关性排序"。
- 🟡 明确禁止 SQL 侧拆分（递归 CTE 兼容性风险），强制 Java 内存拆分。
- 🟢 交互实现建议 MaterialButton 方案，规避 Spinner 拦截不确定性。

---

### 建议 6：镜像服务器级联容错（failover）

#### 现状与代码定位
- `RadioBrowserServerManager`：`currentServer`/`serverList` 为 **static 内存变量，不持久化**（L26-27）；DNS 发现 `all.api.radio-browser.info`（L32-59，失败 fallback de1）；`testConnectionSpeed`/`testAllConnectionSpeeds`（L115-165，串行）；`getFastestServer`（L170-195）。
- 现成级联模板：`Utils.downloadFeedRelative`（L222-254）——失败遍历 serverList + 成功 `setCurrentServer`；**但更新主流程不用它**（`RadioStationRepository` per-page 重试 L365-422 自行切换，成功不回写）。
- 测速结果存 prefs `NetworkCheckResults`（Repository L636-642）。

#### 方案设计
1. **持久化**：default prefs 新增 `radio_browser_current_server` / `radio_browser_server_list` / `radio_browser_server_saved_at`；`getCurrentServer()` 优先读持久值（无则现有逻辑）；`setCurrentServer()` 同步写 prefs（apply）。
2. **通用级联方法**：`RadioBrowserServerManager.downloadWithFailover(path, params)`（提炼 `downloadFeedRelative` 逻辑）：当前 → 列表逐台 → 全失败返回 null；**成功即回写并持久化 currentServer**。三个消费方：点击上报（建议 1）、增量更新（建议 3）、现有 downloadFeedRelative 调用点。
3. **级联短超时**：级联专用 OkHttp 客户端（connectTimeout 5s / readTimeout 10s，其余继承 UA/代理逻辑），避免 6 台级联链累计 60s。
4. **静态兜底镜像列表**：官方镜像 `de1/de2/fi1/at1.api.radio-browser.info` 常量 + DNS 失败 fallback；**不硬编码第三方镜像**（AMARadio 的 radiobrowser.ounben.com 是其私有镜像，本项目不依赖）；预留自定义镜像偏好项（`custom_mirror_server`，默认空，文档化 HTTPS/证书风险）。
5. **定期重测**：持久化服务器超过 7 天 → 自动重新 `testAllConnectionSpeeds`（防长期绑定劣化服务器）。
6. **更新主流程回写**：`RadioStationRepository` per-page 成功切换处（L365-422）调用 `setCurrentServer` 持久化。

#### 改动文件
- 修改：`RadioBrowserServerManager.java`（持久化 + 级联方法 + 短超时 client）、`Utils.java`（downloadFeedRelative 委托）、`RadioStationRepository.java`（回写）、`FragmentSettings.java` + `preferences.xml`（自定义镜像项，可选）

#### 实施步骤
1. 持久化 + getCurrentServer 读持久值。
2. 级联方法 + 短超时 client。
3. 三个消费方接入。
4. 定期重测逻辑。
5. 测试。

#### 验证方案
- 单测：级联顺序（当前 → 列表）、成功回写持久化、全失败返回 null。
- 真机：关闭主服务器（改 hosts/断网模拟）→ 请求自动切镜像成功；重启 App 后仍用上次成功服务器（日志 `AMA-Server` 验证）。
- 观察：更新/上报流程失败时级联耗时上限（5s/台）。

#### 风险与回退
- 持久化读写在多线程下的时序：SharedPreferences apply 异步，无强一致需求，OK。
- 自定义镜像安全：仅 HTTPS + 用户自担证书风险（文档明示）；默认不启用。
- 回退：删除 prefs key 即回退到随机选服务器。

#### 评审修订要点
- 🟡 新增级联短超时（原方案未提，6 台 × 10s 累计过长）。
- 🟡 新增 7 天自动重测（持久化后防止服务器劣化长期生效）。
- 🟢 明确不硬编码第三方镜像 + 预留自定义镜像配置点（合规与可运维性）。

---

### 建议 7：动态电台占位符（无图标智能兜底）

#### 现状与代码定位
- 占位现状：**灰色占位实为 `R.mipmap.ic_launcher`（应用图标）**——`ItemAdapterStation.java` L174、`TrackHistoryAdapter.java` L62、`PlayerServiceUtil.getStationIcon` L334（placeholder 定义）/L395/L480（兜底设置）。把应用图标当电台图标是误导（评审补充）。
- 图标链路：`PlayerServiceUtil.getStationIcon`（L329 起，**已有 stationUuid 参数**）→ `StationIconCache`（永久/半永久双层文件缓存，`service/StationIconCache.java`）。
- 通知大图标：`PlayerService.java` L1164 `setLargeIcon(radioIcon.getBitmap())`。
- 圆形图标开关：`Utils.useCircularIcons`；图标开关 `Utils.shouldLoadIcons`。

#### 方案设计
1. **统一工具类 `ui/StationPlaceholderUtils.java`**（唯一实现，杜绝三处漂移）：
   - `createDrawable(context, stationName, uuid)` / `createBitmap(context, stationName, uuid, size)`；
   - 首字符规则：跳过前导空白/符号，取首个字母或数字（toUpperCase）或中文首字；空名 fallback 收音机图标；
   - 颜色：7 色 Material 500 系色板（白字），`Math.abs(uuid.hashCode()) % 7` 稳定分配；
   - 内存 `LruCache<String, Bitmap>`（64 条）缓存生成结果，滚动零成本。
2. **接入点**：
   - `ItemAdapterStation` 无图标分支（L173-186 附近）与 `TrackHistoryAdapter` L62：`setImageDrawable(stationImagePlaceholder)` → 改为按站生成；
   - `PlayerServiceUtil.getStationIcon` 兜底（L395/L480）：placeholder 参数按 stationUuid 生成（签名已有 uuid）；
   - `PlayerService` 通知 L1164：StationIconCache MISS 时用 `StationPlaceholderUtils.createBitmap`（256×256 方形，适配 `setLargeIcon`）。
3. **主题兼容**：500 系色板双主题通用（白字）；与 `isDarkTheme` 联动仅影响外围底色（沿用现有 L186-189 逻辑）。

#### 改动文件
- 新增：`ui/StationPlaceholderUtils.java`
- 修改：`station/ItemAdapterStation.java`、`history/TrackHistoryAdapter.java`、`service/PlayerServiceUtil.java`、`service/PlayerService.java`（通知）

#### 实施步骤
1. 工具类（首字符 + 色板 + LruCache）。
2. 列表两处接入。
3. getStationIcon 兜底接入。
4. 通知大图标接入。

#### 验证方案
- 单测（JVM）：同 uuid 同色稳定；不同 uuid 分布到 7 色；首字符规则（"WDR 2"→"W"、"123 FM"→"1"、"中文台"→中文字符、空名 fallback）。
- 真机：无图标台列表/通知/锁屏三处占位一致（同台同色同字符）；滚动列表无卡顿（LruCache 生效）；深浅主题均可读。

#### 风险与回退
- 性能：生成位图成本毫秒级 + LruCache，无风险。
- 回退：仅还原三处调用即可。

#### 评审修订要点
- 🟡 修正占位语义：现用 ic_launcher（应用图标）当电台占位，本就误导——本项顺带修复。
- 🟡 三处接入必须共用同一工具类（含颜色 hash 逻辑），防实现漂移导致同台不同色。
- 🟢 新增 LruCache（原方案未提缓存，列表滚动高频 bind 场景必需）。

---

## 3. 跨项评审结论

### 3.1 评审发现的两个既有 Bug（均并入方案）

| ID | 严重度 | 位置 | 问题 | 处置 |
|----|--------|------|------|------|
| F-1 | 🔴 | `RadioStationFts` + `replaceMainFromTemp` | FTS4 外部内容表无同步机制，**每次全量更新后 FTS 搜索失步**（FragmentStations 在用 searchStationsFast） | 并入建议 3：全量 rebuild + 增量行级维护 |
| F-2 | 🔴 | `RadioStationDao.getAllTags` | 标签下拉返回**原始逗号组合串**（"rock,pop"），无法检索 | 并入建议 5：Java 内存拆分 + 计数 |

### 3.2 横切决策

| # | 决策 | 说明 |
|---|------|------|
| B1 | 点击双计统一治理 | `getRealStationLink` 4 个调用方（PlayStationTask/AlarmReceiver/StationActions×2）以静态标志统一防双计，任何新增 click 端点调用必须置标志 |
| B2 | clickcount 本地增量生命周期 | 本地 +1 会在下次同步被服务器值覆盖；上报成功则服务器已计（覆盖后数值正确）；上报失败则增量存续至下次同步——属预期，文档明示 |
| B3 | FTS 同步责任 | FTS 索引与主表一致性由「数据写入方」负责（全量/增量两个入口都要维护），不依赖 Room 自动机制 |
| B4 | 8 语言文案 | 新增字符串（点击上报开关、增量更新、对话框标题/占位、自定义镜像）统一走 strings.xml，中英先落，其余 6 语言占位 |
| B5 | minSdk 16 红线 | 禁止递归 CTE、API 21+ 独占调用（需守卫）；ExoPlayer/OkHttp 依赖版本不升 |
| B6 | 提交粒度 | 7 项独立分支独立 commit，每项含 CHANGELOG 条目；破坏性改动（F-1/F-2 修复）单独标注 |

### 3.3 评审否决/降级项

| 原方案内容 | 结论 | 理由 |
|-----------|------|------|
| POST click 端点 | 改为 GET `json/url/<uuid>` | radio-browser 无独立 POST click 端点 |
| Ogg 链式流"换链不重载"首版实现 | 降级为后续增强 | extractor 层支持有限，需 DataSource 层解析，范围膨胀 |
| 硬编码 AMARadio 私有镜像 | 不采用 | 第三方依赖不可控，仅预留自定义镜像配置 |
| SQL 递归 CTE 拆分标签 | 不采用 | 老设备 SQLite 版本兼容风险 |

---

## 4. 全局测试与验收清单

### 4.1 自动化（新增基建，低投入高保障）
- `app/build.gradle` 增加 `testImplementation 'junit:junit:4.13.2'`（仅 JVM 单测，不引入 Robolectric）。
- 单测覆盖：① ClickReporter 冷却与防双计；② 占位符首字符/7 色稳定性；③ 标签拆分去重计数；④ 搜索权重 SQL 的 pattern 生成（含通配符转义）；⑤ 级联顺序与回写。

### 4.2 回归清单（每项合并前必跑）
1. 全量更新 → FTS 搜索（searchStationsFast）能搜到新台（F-1 修复验证）。
2. 播放（本地 URL / 空 URL / 闹钟三条路径）→ 点击上报行为符合预期。
3. ICY 台曲目显示（Ogg 改动后回归）。
4. FragmentMultiSearch 三下拉 + 关键词组合筛选。
5. 无图标台列表/通知占位显示。
6. 深浅主题各过一遍。

### 4.3 实施时确认清单（不可跳过的技术核对）
- [ ] `json/stations/lastchange` 端点参数与水位语义（官方 API 文档为准）。
- [ ] ExoPlayer 2.18.2 OggExtractor 是否产出 VorbisComment（M0 探针）。
- [ ] 5 万行库 FTS rebuild 实测耗时（决定 rebuild 频率与提示）。
- [ ] `FragmentStations`/`FragmentLocalStations` 是否经 StationsFilter 重排（建议 4 对齐）。
- [ ] 官方镜像清单确认（de1/de2/fi1/at1 是否全部有效）。

---

## 5. 附录：评审专家矩阵

| 专家角色 | 关注点 | 主要发现（已并入方案） |
|---------|--------|----------------------|
| Android 架构师 | 生命周期、组件边界、minSdk 16 | 点击上报用 OkHttp enqueue 避免 JobIntentService 生命周期问题；禁止递归 CTE；统一占位符工具防三处漂移 |
| 流媒体/音频专家 | Ogg/Opus 解析、元数据链路 | M0 探针先行；链式流降级为后续增强；同值去重防刷屏；ICY 优先级定义 |
| 数据库/SQLite 专家 | FTS、REPLACE、性能 | **F-1 FTS 失步**；增量行级 FTS 维护 + 全量 rebuild；lastchange 双保险水位 |
| 安全与隐私专家 | 数据回传、第三方依赖 | 点击上报需显式开关；不硬编码第三方镜像；自定义镜像 HTTPS 风险文档化 |
| 产品/交互专家 | 交互形态、可发现性 | 下拉用可搜索对话框 + 热门优先；「全部」置顶与选中高亮；占位符语义修正（去 ic_launcher） |
| QA 测试专家 | 可测性、回归面 | 新增 JVM 单测基建；全路径回归清单；LIKE 通配符转义用例；断网/杀进程场景 |
| 性能专家 | 全库扫描、滚动流畅 | tags 拆分后台线程；FTS rebuild 计时与后台执行；占位符 LruCache；级联短超时 |
| 工程质量专家 | 提交粒度、可回退、文档 | 7 项独立分支独立 commit；每项含回退方案；8 语言文案流程；CHANGELOG 条目 |

---

*本文档为实施方案，不包含代码改动。批准后按 1.2 节顺序逐项实施，每项完成独立验证与提交。*
