<p align="center">
  <img src="app/src/main/res/drawable-xxxhdpi/ic_launcher.png" alt="RadioDroid Logo" width="96"/>
</p>

<h1 align="center">RadioDroid</h1>

<p align="center">
  <b>全球电台收音机 · 离线数据库 · 魔改增强版</b><br>
  <i>Global Radio Browser · Offline Database · Enhanced Edition</i>
</p>

<p align="center">
  <a href="#-中文">中文介绍</a> ·
  <a href="#-english">English</a> ·
  <a href="#-changelog">Changelog</a>
</p>

---

##   中文

### 项目由来

**RadioDroid** 是一款基于 Android 平台的全球电台收音机应用，电台数据来源于 [radio-browser.info](https://www.radio-browser.info/) 社区数据库，收录了全球数万个在线电台。

本项目 Fork 自 [segler-alex/RadioDroid](https://github.com/segler-alex/RadioDroid)（v0.86 版）。原版自 2023 年以来停止维护，且存在多个影响日常使用的 Bug（如中文搜索失效、部分英文搜索无结果等），因此在该版本基础之上进行了深度「魔改」——持续修复问题、优化体验并添加实用功能，形成了当前版本。

### 构建版本说明

本应用提供两种构建变体（Build Flavor）：

| 版本 | 说明 |
|------|------|
| **Free** | 无 Google Play Services 依赖，不支持 Chromecast 投屏，不集成 SafetyNet。纯开源构建，适合 F-Droid 或自行构建 |
| **Play** | 集成 Google Play Services，支持 Chromecast 投屏和 SafetyNet 完整性检查，适用于 Google Play 商店分发 |

两个版本的核心功能（收音机播放、离线数据库、搜索等）完全一致，区别仅在于是否包含 Google 专有服务。

### 与官方原版的主要区别

####  核心架构变更：离线数据库模式

**原版方式**：每次浏览电台列表、搜索、按分类查看等操作，均实时向远在欧洲的 [radio-browser.info](https://www.radio-browser.info/) API 服务器发起网络请求。受服务器地理距离影响，延迟高且连接不稳定，体验较差。

**本版改为本地数据库模式**：

- 首次使用时，用户手动触发全量数据同步，将服务器上的电台数据（约 5 万+ 条目）下载存储到本地 SQLite 数据库（基于 Room 框架）
- 下载采用**多线程并行策略**：根据设备 CPU 核心数、网络延迟动态调整线程数（2-10 线程），多线程分页请求，自动测速后选择最快的 API 服务器
- 之后所有电台浏览、搜索、分类筛选、排序等操作均直接查询本地数据库，**无需网络连接**
- 数据库更新过程中支持**后台执行**（通过 WorkManager 前台服务），可切换到其他 App 继续操作
- 支持**断点续传**：中断的下载任务可在下次恢复继续，无需重新下载
- 更新前自动检查网络连通性、电量（<5% 拒绝更新，<20% 警告提示）、存储空间（需 ≥50MB 内部空间）
- 提供**数据库导入/导出**功能，换机或重装时可迁移数据，避免重复下载

**优点**：

| 方面 | 说明 |
|------|------|
|   响应速度 | 所有列表浏览、搜索、筛选均本地 SQLite 查询，毫秒级响应 |
|  离线可用 | 无网络环境下正常浏览电台信息，播放时仅需网络传输音频流 |
|  稳定性 | 不依赖远程 API 可用性，不受服务器故障或网络波动影响 |
|  ️ 数据一致 | 搜索结果可复现，列表顺序稳定，不受服务器侧数据变更影响 |
| ⚡ 交互流畅 | 电台切换、列表滚动、实时搜索反馈均流畅无卡顿 |

**缺点**：

| 方面 | 说明 |
|------|------|
|   首次初始化 | 全量下载 5 万+ 电台，耗时约 1-20 分钟（取决于网络质量） |
|   数据时效性 | 电台数据为更新时的快照，新增/变更需手动触发更新 |
|   存储空间 | 本地数据库约 30MB |
| ️ 更新方式 | 非自动实时同步，需用户主动触发更新 |

####  预置数据库文件

自 **v0.96** 起，每个版本的 Release 附件中将提供一份预下载的完整电台数据库文件。用户可直接下载并导入至应用中，无需经历耗时的首次全量同步过程，特别适合以下场景：

- 首次使用者希望开箱即用
- 网络条件有限或服务器连接不稳定的用户
- 希望快速恢复使用环境的换机用户

**用法**：在应用的「设置 → 本地数据库 → 导入数据库」中选择下载的数据库文件即可完成导入。

####   本地电台智能显示

应用会根据用户手机的系统设置，智能优先展示与用户相关的电台：

1. **优先显示系统国家电台**：根据手机系统国家代码（`Locale.getDefault().getCountry()`），从数据库筛选该国电台
2. **回退到系统语言电台**：若国家无电台，尝试查询系统语言+国家的电台组合
3. **进一步回退**：仍无结果则查询仅按语言筛选
4. **兜底显示全部电台**：所有条件均不满足时显示全部电台列表

刷新列表时始终遵循此优先级逻辑，确保用户首先看到最可能感兴趣的电台。

####   搜索功能

系统提供两种搜索入口：

**1. 快速搜索（电台 Tab 内）**

电台主列表中直接输入关键词搜索，基于本地 SQLite 数据库的 `LIKE` 查询（`%keyword%`），实时返回匹配结果。

**2. 高级多条件搜索**

独立的高级搜索页面，支持同时设置四个维度的筛选条件：

- **国家**：下拉选择，从数据库提取所有国家列表
- **语言**：下拉选择，从数据库提取所有语言列表
- **标签**：下拉选择，从数据库提取所有标签列表
- **关键词**：文本输入，500ms 防抖延迟，避免过度查询

四个条件可任意组合（均为可选），任意条件变更时自动触发联合查询 (`searchStationsByMultiCriteria`)。支持一键重置所有筛选条件。筛选条件区域可折叠/展开，节省屏幕空间。

#### SQLite FTS 全文搜索引擎

数据库内置 `radio_stations_fts` 表（SQLite FTS4），对电台名称、标签、国家、语言建立全文索引，支持按键词前缀检索。`RadioStationDao` 提供按名称、标签、国家、语言的独立 FTS 快速搜索通道。

#### 电台列表排序

支持四种排序方式，点击 Toolbar 上的排序按钮弹出选择对话框：

| 排序方式 | 说明 |
|----------|------|
| 按名称 | 字母序排列 |
| 按点击量 | 按 radio-browser.info 全球用户点击热度排序 |
| 按投票数 | 按社区投票数排列 |
| 按最近变更 | 按电台信息最后更新时间排列 |

当前排序模式高亮显示 ↑（升序）/ ↓（降序）指示，点击相同模式可切换排序方向。排序偏好自动持久化保存。

####   随机播放

电台列表页 Toolbar 上提供随机播放按钮。点击后从本地数据库随机选取一个电台，最多尝试 10 次寻找有效播放源的电台，每次等待 10 秒验证连通性。找到有效电台则自动开始播放。

####   回到顶部

电台列表和高级搜索页面均提供浮动按钮（FAB），列表滚动离开顶部后自动浮现，点击平滑滚回顶部。

####   曲目历史

原版 RadioDroid 已有曲目历史功能。本应用针对流媒体 ICY 元数据中的曲目名称和艺术家信息段，优化了截取和解析逻辑，提升正确匹配和显示当前播放曲目名与艺术家的概率。同时支持通过 LastFM API 获取曲目附加元数据。

####   电台图标

电台列表和播放界面支持显示电台 Logo。图标获取采用多渠道三步回退策略：

1. **优先使用服务器提供的图标 URL**：若电台有 `IconUrl` 字段，尝试加载
2. **回退到网站通用图标**：从电台主页域名构造 `favicon.ico` 和 `apple-touch-icon.png` 地址尝试加载
3. **使用 Google Favicons 服务兜底**：通过 `google.com/s2/favicons` 服务获取站点图标

每步失败后自动回退到下一步，全部失败则显示默认图标。使用 Picasso 图片库缓存图标，内置重试机制（最多 3 次，间隔 1s / 3s / 5s）。

####   播放器

内置播放器基于 ExoPlayer 和 Android MediaPlayer 双引擎：

- **ExoPlayer**：支持 HLS、ICY（Shoutcast）协议，对 Shoutcast 流解析元数据
- **MediaPlayer**：通过 StreamProxy 代理捕获元数据
- 播放时自动解析流内嵌的 ICY 元数据（歌曲名/艺术家），用于曲目历史记录
- 支持通过 LastFM API 补充曲目封面等元数据

同时支持：外部播放器调用、MPD（Music Player Daemon）协议、Chromecast 投屏（仅 Play 版）。

####  代理支持

支持 HTTP 和 SOCKS5 代理，带认证用户名/密码。代理设置通过 Gson 序列化存储。每个 OkHttp 请求经 `proxyAuthenticator`（原版错误使用了 `authenticator`，已修正）处理认证。

####  ️ 多语言界面

设置中提供界面语言选择，支持：跟随系统、中文、英文、西班牙语、俄语。通过 `initAppLanguage()` 在 `ActivityMain.onCreate()` 中动态加载生效。针对所有新增和修改过的代码界面进行了多语言的全面适配，消除了原版代码中中英文混杂显示的问题。

####   暗色主题

支持亮色和暗色主题，可在设置中切换。修正了原版暗色模式下部分界面元素和字体颜色显示不正确的问题。常用界面元素（标题、标签、描述等）根据主题自动调整文字颜色。

####  ️ 其他功能

- **收藏电台**：支持添加/移除收藏，滑动删除，撤销操作（Snackbar），M3U 导入/导出
- **历史记录**：播放过的电台列表，支持 M3U 导出，一键清除
- **睡眠定时器**：SeekBar 设置分钟数，终点自动停止播放，保存默认值
- **闹钟**：支持设置指定时间自动播放指定电台。闹钟默认仅生效一次，如需每天重复请在闹钟编辑界面开启「重复」开关
- **录音功能**：录制当前播放的电台流为音频文件
- **均衡器**：提供双套预设方案。一套调用 Android 系统原生均衡器预设，不同设备厂商的预设名称和调音效果可能存在差异；另一套为应用内置预设，包含「人声」（适合新闻、访谈、脱口秀等以人声为主的节目）和「音乐」（适合音乐类电台的通用调音方案）
- **电台详情展开**：点击展开按钮显示网站访问、分享、添加闹钟、创建桌面快捷方式等操作
- **趋势图标**：电台列表显示点击量趋势（上升/下降/持平）
- **国家图标**：电台列表显示所属国家的国旗图标
- **Android TV 支持**：检测 TV 设备自动启用频道管理
- **流量提醒**：使用计量网络时弹出提醒，防止意外消耗流量

---

##   English

### Introduction

**RadioDroid** is an Android global radio browser app powered by the [radio-browser.info](https://www.radio-browser.info/) community database, which hosts tens of thousands of online radio stations worldwide.

This project is a heavily customized fork of [segler-alex/RadioDroid](https://github.com/segler-alex/RadioDroid) (v0.86). The original has been unmaintained since 2023 and had several bugs affecting daily usage. This fork introduces deep architectural changes, bug fixes, and practical features.

### Build Variants

| Variant | Description |
|---------|-------------|
| **Free** | No Google Play Services dependency, no Chromecast casting, no SafetyNet. Pure open-source build, suitable for F-Droid or self-building |
| **Play** | Includes Google Play Services, supports Chromecast casting and SafetyNet integrity checks, for Google Play Store distribution |

Core functionality is identical across both variants. The difference is the availability of Google proprietary services.

### Key Differences from Official RadioDroid

####   Core Architecture: Offline Database Mode

**Original approach**: Every list browsing, search, or category operation made real-time API requests to [radio-browser.info](https://www.radio-browser.info/) servers in Europe, causing high latency and poor UX.

**This version uses a local database approach**:

- On first use, manually trigger a full data sync that downloads all ~50,000+ stations into a local SQLite database (Room framework)
- Multi-threaded parallel downloading: dynamically adjusts thread count (2-10) based on CPU cores and network latency, with automatic server selection by speed testing
- All subsequent browsing, searching, filtering, and sorting operate directly on the local database — **no network required**
- Database updates run in the background via WorkManager foreground service, allowing other app usage
- Resumable downloads: interrupted syncs can continue from where they left off
- Pre-sync checks: network connectivity, battery level (<5% blocks update, <20% warns), storage space (≥50MB internal)
- Database export/import for migration across devices or after reinstall

**Pros**:

| Aspect | Description |
|--------|-------------|
|    Speed | All operations are local SQLite queries with millisecond response times |
|   Offline | Browse and search stations without internet; only audio streaming needs network |
|   Stability | Independent of remote API availability; unaffected by server outages |
| ️  Consistency | Search results and list ordering are stable and reproducible |
| ⚡ UX | Smooth station switching, scrolling, and real-time search feedback |

**Cons**:

| Aspect | Description |
|--------|-------------|
|    Initial Setup | Full download of 50K+ stations takes 1-20 minutes (network-dependent) |
|    Data Freshness | Station data is a snapshot; new/modified stations require manual refresh |
|    Storage | Local database uses approximately 30MB |
|  ️  Updates | Not real-time; manual user trigger required |

####    Pre-built Database Files

Starting from **v0.96**, a pre-downloaded full radio database file will be attached to each release. Users can import it directly into the app, bypassing the time-consuming initial full sync. This is especially useful for:

- First-time users who want out-of-the-box experience
- Users with limited network connectivity or unstable server access
- Users switching devices who want to quickly restore their environment

**Usage**: Import the downloaded database file via **Settings → Local Database → Import Database** in the app.

####    Smart Local Station Display

The app intelligently prioritizes stations based on the user's device locale:

1. **System country first**: Filters stations by device country code (`Locale.getDefault().getCountry()`)
2. **Fallback to language + country**: If no stations match the country
3. **Language-only fallback**: If still no results
4. **Show all stations**: As final fallback

Refreshing always follows this priority logic.

####    Search

Two search modes:

**1. Quick Search (Stations Tab)**

Real-time keyword search using local SQLite `LIKE` queries with pattern matching.

**2. Advanced Multi-Criteria Search**

Dedicated search page with four filter dimensions:

- **Country**: Dropdown from all countries in the database
- **Language**: Dropdown from all languages
- **Tag**: Dropdown from all tags
- **Keyword**: Text input with 500ms debounce

All four criteria are optional and combinable. Any change triggers an automatic multi-condition query. One-tap reset for all filters. Filter area is collapsible.

#### SQLite FTS Full-Text Search

`radio_stations_fts` table (SQLite FTS4) indexes station name, tags, country, and language. Dedicated FTS search channels provided per field via `RadioStationDao`.

#### Station List Sorting

Four sorting modes via toolbar button dialog:

| Mode | Description |
|------|-------------|
| Name | Alphabetical order |
| Click Count | By global click popularity from radio-browser.info |
| Votes | By community vote count |
| Recent Change | By last modification timestamp |

Current sort mode displayed with ↑ (ascending) / ↓ (descending). Tapping the same mode toggles direction. Preferences are persisted.

####   Shuffle Play

Random play button on the station list toolbar. Picks a random station from the local database, retrying up to 10 times (10-second timeout each) to find a working station.

####   Scroll to Top

Floating action button appears when list is scrolled down. Tapping smoothly scrolls back to the top.

####   Track History

Original RadioDroid already had track history. This version optimizes the parsing logic for stream ICY metadata (track name and artist), improving matching and display accuracy. Also fetches supplementary metadata via LastFM API.

####   Station Icons

Multi-channel three-step fallback icon loading:

1. Server-provided `IconUrl`
2. Domain-derived `favicon.ico` and `apple-touch-icon.png`
3. Google Favicons service (`google.com/s2/favicons`)

With retry mechanism (3 retries with 1s/3s/5s delays) and Picasso image caching.

####   Player

Dual-engine playback:

- **ExoPlayer**: HLS, ICY (Shoutcast) supports stream metadata parsing
- **MediaPlayer**: Via StreamProxy for metadata capture
- Live ICY metadata extraction (song/artist) for track history
- LastFM API for supplementary track artwork

Also supports: external player, MPD protocol, Chromecast (Play variant only).

####   Proxy Support

HTTP and SOCKS5 proxy with username/password authentication. Settings serialized via Gson. Uses `proxyAuthenticator` (fixed from the original's incorrect `authenticator`).

####  ️ Multi-Language UI

Language selector in settings: System, Chinese, English, Spanish, Russian. Loaded dynamically in `ActivityMain.onCreate()` via `initAppLanguage()`. All new and modified UI code has full multi-language support.

####   Dark Theme

Light/dark theme toggle in settings. Fixed incorrect colors on certain UI elements in dark mode. Text colors automatically adjust per theme.

####  ️ Other Features

- **Favorites**: Add/remove with undo snackbar, swipe-to-delete, M3U import/export
- **History**: Played station list with M3U export, one-tap clear
- **Sleep Timer**: SeekBar dialog, auto-stops playback, saves default
- **Alarm**: Schedule a station to play at a specified time. Alarms are one-time by default; enable the "repeat" toggle in the alarm editor for daily recurrence
- **Recording**: Record live radio streams to audio files
- **Equalizer**: Two sets of presets available. One uses the Android system's built-in equalizer presets, whose names and sound profiles may vary across device manufacturers; the other is built into the app, featuring "Vocal" (optimized for news, talk shows, podcasts) and "Music" (general-purpose tuning for music stations)
- **Station Detail Expansion**: Website visit, share, alarm, desktop shortcut creation
- **Trend Icons**: Click trend indicators (rising/falling/flat)
- **Country Flags**: Flag icons per station in list view
- **Android TV**: Auto-detect TV devices, channel management
- **Metered Connection Warning**: Alerts before playing on metered networks

---

##  ️ Changelog

> Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)

### v0.95
*2025-05*

- **修复**：Android 13+/15/16 上文件管理器无法打开 — `OpenDocument` 替换 `GetContent`，`CreateDocument` 替换直接文件写入，移除过时存储权限检查
- **修复**：数据库导入数据丢失风险 — 先复制到临时文件验证后再替换正式文件
- **修复**：`replaceMainFromTemp` 数据丢失风险 — 先读取临时数据库验证后再删除主库
- **修复**：`cancelUpdate` 中危险的 `SharedPreferences` 直接文件操作
- **修复**：数据库导入中无意义的 `Thread.sleep(1100ms)` 延迟
- **优化**：数据库更新进度写入 — `commit` 改为 `apply`，减少磁盘 I/O
- **优化**：批量插入大小从 1000 提升至 2000

### v0.94
*2025-05*

- **修复**：HTTP/SOCKS 代理认证失败 — 修正 OkHttp 认证器调用错误（`authenticator` → `proxyAuthenticator`）
- **新增**：SOCKS5 代理认证支持和无限重试保护
- **修复**：`StreamProxy` 元数据解析缺少 EOF 检查导致的流结束崩溃
- **修复**：`StationSaveManager` 导出 M3U 时 `BufferedWriter` 资源泄漏
- **修复**：`ActivityMain` 广播接收器重复注册导致的内存泄漏
- **修复**：历史记录列表 `subList` 视图引发的并发修改异常
- **新增**：临时数据库文件（`.db`/`-wal`/`-shm`/`-journal`）自动清理
- **修复**：`WakeLock`/`WifiLock` 释放时缺少异常保护导致的潜在崩溃
- **修复**：`FragmentSettings` 对话框显示时缺少 Fragment 生命周期检查
- **优化**：数据库更新失败时的资源回收逻辑

### v0.93
*2025-04*

- **新增**：随机播放功能 — 电台界面 Toolbar 随机播放按钮，从本地数据库随机选取电台
- **优化**：搜索算法 — 支持部分匹配和近似模糊匹配，支持标签组合搜索
- **修复**：界面硬编码 — 基本消除中英文混杂的界面显示
- **新增**：俄语语言支持
- **修复**：暗色主题下部分界面和字体颜色错误
- **修复**：均衡器和统计页面显示问题

### v0.92
*2025-04*

- **优化**：电台播放逻辑 — 优先使用本地电台地址，降低远程服务器依赖
- **降级**：Kotlin 版本以解决兼容性问题
- **更新**：一批过时 API，适配新版 Android

### v0.91
*2025-03*

- **国际化**：所有新增代码中中文硬编码改为中英文双语显示
- **优化**：本地电台显示逻辑 — 手机系统国家电台 > 手机系统语言电台 > 全部电台
- **修复**：服务器数据库地址硬编码 — 改为 DNS 获取，解决服务器变更引发崩溃
- **新增**：设置 → 外观目录下界面语言选项
- **更新**：关于页面内容
- **修复**：关键代码中的数组越界、空指针等问题

### v0.90
*2025-03*

- **重构**：整合国家、语言、标签、搜索界面，全新设计高级搜索
- **优化**：本地数据库更新逻辑 — 多线程并行下载，耗时缩短 50%+
- 搭配欧洲代理时更新时间可缩短至 **1 分钟以内**
- **修复**：若干小 Bug

### v0.89
*2025-02*

- **修复**：数据库更新、导入、导出各类 Bug
- **修复**：数据库状态显示错误
- **修复**：睡眠定时器失效
- **修复**：收藏导入导出异常
- **修复**：大播放器按钮图标不切换
- **修复**：曲目历史中文乱码和英文字段截取错误

### v0.88
*2025-02*

- **新增**：更新本地数据库时可切换后台运行
- **优化**：更新逻辑和用户提示
- **调整**：数据库导出导入功能

### v0.87
*2025-01*

> **里程碑版本** — 架构级重构

- **核心重构**：引入本地离线数据库模式，所有电台操作基于 SQLite 本地数据库
- **新增**：服务器连接测试 — 网络不佳时提示不宜更新
- **新增**：本地数据库导入/导出 — 换机或重装免除再次下载
- ⚠️ 首次全量下载 5 万+ 电台约需 10-60 分钟（视网络）
- 功能初步跑通，后续持续完善

### v0.86 修改版
*2025-01*

- **修复**：App 无法中文搜索节目
- **修复**：部分英文搜索结果不显示
- RadioDroid v0.86 原版有多个影响使用的 Bug，自 2023 年无人维护，开启本分支

---

<p align="center">
  <sub>Built with ❤️ based on <a href="https://github.com/segler-alex/RadioDroid">segler-alex/RadioDroid</a></sub>
</p>