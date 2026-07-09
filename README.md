<p align="center">
  <img src="app/src/main/res/drawable-xxxhdpi/ic_launcher.png" alt="RadioDroid Logo" width="96"/>
</p>

<h1 align="center">RadioDroid</h1>

<p align="center">
  <b>全球电台收音机 · 离线数据库 · 魔改增强版</b><br>
  <i>Global Radio Browser · Offline Database · Enhanced Edition</i>
</p>

<p align="center">
  <a href="#中文">中文介绍</a> ·
  <a href="#english">English</a> ·
  <a href="#changelog">Changelog</a>
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
| 存储空间 | 本地数据库约 40MB |
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

应用采用智能多级缓存策略加载电台图标，确保两个核心体验：**尽快显示图标，尽量显示主图标**。

**图标获取渠道**（渐进回退）：

1. **主图标**：服务器提供的 `IconUrl`（最优先）
2. **渐进回退**：按顺序尝试以下 URL，失败立即跳到下一个：
   - `apple-touch-icon.png`
   - `apple-touch-icon-precomposed.png`
   - `android-chrome-192x192.png`
   - `favicon.ico`
   - Google Favicons 服务（兜底）
3. **HD 图标发现**（新增）：当仅有主页 URL 但无图标 URL 的回退图标加载成功后，后台解析主页 HTML 查找 Apple Touch Icon 等高分辨率 `<link>` 标签图标

**智能显示逻辑**：

图标加载后根据实际尺寸自动适配显示：
- 图标 ≥ 显示区域 50% → 原图 `CENTER_INSIDE` 显示，清晰不模糊
- 图标 < 显示区域 50% → 放大至显示区域尺寸，模糊但起标识作用
- ImageView 强制保持正方形，防止行高变形

**缓存策略**：

| 特性 | 说明 |
|------|------|
| 分层缓存 | 收藏电台图标存入永久缓存（不删除），其他电台图标存入半永久缓存（7天过期） |
| 快速显示 | 打开页面时，有缓存的图标立即显示（不管来源），保证用户第一眼看到图标 |
| 智能升级 | 缓存为回退图标的电台，后台每4小时静默尝试获取主图标；无主图标时尝试 HD 发现 |
| 尺寸保护 | 后台获取的新图标尺寸小于当前显示图标时不替换，避免用更小的图覆盖清晰的大图 |
| 来源标记 | 系统会标记每个缓存图标的来源（主图标/回退），主图标永不再重试，回退图标定时升级 |
| 用户控制 | 设置中可关闭电台图标显示，减少缓存占用，适合存储空间紧张的用户 |

**流程示意**：
```
打开页面 → 缓存图标立即显示（保证速度）
        → 缓存是回退图标的电台：
          ├─ 有 IconUrl → 4小时后后台静默重试主图标
          │  ├─ 成功且≥当前尺寸 → 替换缓存和显示
          │  └─ 失败或<当前尺寸 → 维持现有图标
          └─ 无 IconUrl → 后台解析主页 HTML 查找 HD 图标
             └─ 成功 → 自动替换缓存和显示

无缓存 → 联网加载 IconUrl → 成功 → 保存文件缓存 + 智能显示
                            → 失败 → 立即渐进回退（5级URL）
                                   → 任意成功 → 保存文件缓存 + 标记回退来源 + 智能显示
                                   → 全部失败 → 显示占位图标
```

####   播放器

内置播放器基于 ExoPlayer 和 Android MediaPlayer 双引擎：

- **ExoPlayer**：支持 HLS、ICY（Shoutcast）协议，对 Shoutcast 流解析元数据
- **MediaPlayer**：通过 StreamProxy 代理捕获元数据
- 播放时自动解析流内嵌的 ICY 元数据（歌曲名/艺术家），用于曲目历史记录
- 支持通过 LastFM API 补充曲目封面等元数据

同时支持：外部播放器调用、MPD（Music Player Daemon）协议、Chromecast 投屏（仅 Play 版）。

####   音量指数级控制

播放器提供「音量映射增强」开关（在「设置 → 播放器」中），用于在指数级音量控制和默认线性音量控制之间切换。

- **开启（默认）**：启用双层音量映射策略。先根据系统音量动态调整最大增益系数，再对应用内音量滑杆应用以 50% 为对称点的指数曲线，从而在安静环境获得足够低的最小音量、在嘈杂环境获得足够高的最大音量，同时让中间档位的音量变化更符合人耳听觉特性。
- **关闭**：恢复默认线性音量控制（增益 = maxGain × volume / 100），不做系统音量补偿和指数曲线处理。

**系统音量补偿**（开启时生效，分段线性）：

| 系统音量区间 | 增益系数范围 | 效果 |
|-------------|-------------|------|
| < 35% | 0.5× → 1.0× | 降低 1 倍，安静环境不吵 |
| 35% ~ 65% | 1.0× | 正常 |
| > 65% | 1.0× → 2.0× | 提升 1 倍，嘈杂环境更易听清 |

**应用内音量曲线**（开启时生效，以 50% 为对称点的指数曲线）：

| 应用音量 | 输出增益 |
|---------|---------|
| 0 | 静音 |
| 25% | maxGain × 0.5 |
| 50% | maxGain × 1.0 |
| 100% | maxGain × 2.0 |

低音量端增益低于线性（最低减半），高音量端增益高于线性（最高翻倍），全范围保持平滑过渡。

####   MPD 播放器支持

MPD（Music Player Daemon）是一款开源的音频播放服务端程序，通常运行在 Linux 服务器、NAS 或树莓派等设备上。RadioDroid 支持将电台流推送到远程 MPD 服务器进行播放，适用于以下场景：

- **家庭音响系统**：在家庭服务器上运行 MPD，通过 RadioDroid 将电台推送到家中音响播放
- **NAS/树莓派音乐中心**：利用闲置的 NAS 或树莓派作为音频输出终端
- **多房间同步播放**：通过 MPD 的多客户端特性实现多个房间同步收听同一电台
- **远程控制**：手机作为遥控器，通过 MPD 控制远端设备的播放

**使用方法**：

1. 确保已有一台运行中的 MPD 服务器（需与手机在同一局域网或可通过公网访问）
2. 进入应用「设置 → 外部播放器 → Music Player Daemon」
3. 开启「启用 MPD」开关
4. 点击「你的服务器」添加 MPD 服务器信息：
   - **名称**：自定义标识名（如"客厅音响"、"书房 NAS"）
   - **主机**：MPD 服务器的 IP 地址或域名
   - **端口**：MPD 监听端口（默认 `6600`）
   - **密码**：如果 MPD 配置了密码认证则填写，否则留空
5. 保存后点击对应服务器条目测试连接状态
6. 在播放器选择界面中选择 MPD 作为目标播放器

**技术说明**：

| 项目 | 说明 |
|------|------|
| 协议 | MPD 原生文本协议（非 HTTP），基于 TCP Socket 直连 |
| 认证 | 支持 MPD 密码认证（可选） |
| 连接方式 | 应用直接与 MPD 服务器建立 TCP 连接，不经过中间代理 |
| 支持操作 | 播放、暂停、恢复、停止、音量调节 |
| 多服务器 | 支持保存多个 MPD 服务器配置并自由切换 |
| 适用网络 | 局域网优先；公网访问需确保防火墙开放 MPD 端口 |

**注意事项**：
- MPD 功能为原版 RadioDroid 已有功能，本分支未对其进行修改测试，仅添加介绍
- 手机与 MPD 服务器之间需要网络连通性（同一 WiFi 或 VPN）
- 首次使用建议先确认 MPD 服务器可从手机正常访问（可用终端工具 `telnet <IP> 6600` 测试）
- 如连接失败，请检查：MPD 服务是否运行、端口是否正确、防火墙规则、密码是否匹配

####  代理支持

支持 HTTP 和 SOCKS5 代理，带认证用户名/密码。代理设置通过 Gson 序列化存储。每个 OkHttp 请求经 `proxyAuthenticator`（原版错误使用了 `authenticator`，已修正）处理认证。

####  ️ 多语言界面

设置中提供界面语言选择，支持：跟随系统、中文、英文、西班牙语、俄语。通过 `initAppLanguage()` 在 `ActivityMain.onCreate()` 中动态加载生效。针对所有新增和修改过的代码界面进行了多语言的全面适配，消除了原版代码中中英文混杂显示的问题。

####   暗色主题

支持亮色和暗色主题，可在设置中切换。修正了原版暗色模式下部分界面元素和字体颜色显示不正确的问题。常用界面元素（标题、标签、描述等）根据主题自动调整文字颜色。

####   均衡器

提供双套预设方案。一套调用 Android 系统原生均衡器预设，不同设备厂商的预设名称和调音效果可能存在差异；另一套为应用内置预设，包含「人声」（适合新闻、访谈、脱口秀等以人声为主的节目）和「音乐」（适合音乐类电台的通用调音方案）。

同时支持电台个性化均衡器设置：在电台详情中点击均衡器按钮，可为单个电台单独配置均衡器参数，实现不同电台自动切换不同音效的个性化体验。

####   电台缓存策略

电台详情中提供「缓存策略」配置按钮，允许为每个电台单独设置播放缓冲策略，实现个性化的播放体验：

| 策略 | 说明 | 适用场景 |
|------|------|------|
| 轻度缓冲 | 缓冲 2.5 秒后开始播放，内存占用小、延迟低 | 网络稳定、追求快速播放 |
| 增强缓冲 | 缓冲 10 秒后开始播放，有效吸收网络波动 | 网络偶尔不稳定 |
| 极限缓冲 | 缓冲 30 秒后开始播放，最大限度抵御网络中断 | 网络中度不稳定 |

**使用方式**：在电台详情界面点击「缓存策略」按钮，选择适合该电台的策略，配置自动保存并立即生效（若当前正在播放该电台则自动重启播放）。

**为什么需要按电台单独设置**：不同电台的流媒体服务器质量参差不齐——有些服务器稳定流畅，适合轻度缓冲快速响应；有些服务器波动频繁，需要更长缓冲时间来平滑播放中断。为每个电台单独设置策略，可在播放速度和稳定性之间取得最佳平衡。

**注意事项**：缓冲时间越长，曲目历史记录与当前实际播放的内容可能出现时间差。因为曲目历史显示的是电台流当前的歌曲信息，而你的播放器由于较长的缓冲，实际播放的内容还在"排队"中——你可能听到的还是上一首歌，但曲目历史已经显示了下一首歌的标题。缓冲时间越长，这个时间差就越大。轻度缓冲（2.5 秒）基本不会出现此问题。

####  ️ 其他功能

- **收藏电台**：支持添加/移除收藏，滑动删除，撤销操作（Snackbar），M3U 导入/导出
- **历史记录**：播放过的电台列表，支持 M3U 导出，一键清除
- **睡眠定时器**：SeekBar 设置分钟数，终点自动停止播放，保存默认值
- **闹钟**：支持设置指定时间自动播放指定电台。闹钟默认仅生效一次，如需每天重复请在闹钟编辑界面开启「重复」开关
- **录音功能**：录制当前播放的电台流为音频文件
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
|    Storage | Local database uses approximately 40MB |
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

The app uses a smart multi-level caching strategy to ensure two core experiences: **show icons as fast as possible, show primary icons whenever possible**.

**Icon Sources** (progressive fallback):

1. **Primary Icon**: Server-provided `IconUrl` (highest priority)
2. **Progressive Fallback**: Tries each URL in sequence, jumping to the next on failure:
   - `apple-touch-icon.png`
   - `apple-touch-icon-precomposed.png`
   - `android-chrome-192x192.png`
   - `favicon.ico`
   - Google Favicons service (last resort)
3. **HD Icon Discovery** (new): When a fallback icon loads for a station with homepage URL but no icon URL, the app parses the homepage HTML in the background to find high-resolution `<link>` tag icons

**Smart Display Logic**:

After loading, icons are automatically adapted based on actual size:
- Icon ≥ 50% of display area → `CENTER_INSIDE` original display, crisp and clear
- Icon < 50% of display area → Scaled up to display size, blurry but identifiable
- ImageView forced to square aspect ratio to prevent row height distortion

**Caching Strategy**:

| Feature | Description |
|---------|-------------|
| Layered Cache | Favorite station icons go into permanent cache (never deleted), others go into semi-permanent cache (7-day expiry) |
| Fast Display | When opening a page, cached icons display immediately regardless of source, ensuring users see icons at first glance |
| Smart Upgrade | Stations with fallback icons silently retry the primary icon every 4 hours; if no primary icon exists, attempt HD discovery |
| Size Protection | A newly fetched icon smaller than the currently displayed one will not replace it, preventing degradation |
| Source Tracking | Each cached icon is marked with its source (primary/fallback). Primary icons are never retried; fallback icons are periodically upgraded |
| User Control | Icons can be disabled in Settings to reduce cache size, ideal for users with limited storage |

**Flow Diagram**:
```
Open page → Cached icons display immediately (speed first)
         → Stations with fallback icons:
           ├─ Has IconUrl → Retry primary icon after 4h in background
           │  ├─ Success & ≥ current size → Replace cache and display
           │  └─ Failed or < current size → Keep current icon
           └─ No IconUrl → Parse homepage HTML for HD icons in background
              └─ Success → Auto-replace cache and display

No cache → Load IconUrl from network → Success → Save to file cache + smart display
                                       → Failed → Progressive fallback (5 URLs)
                                              → Any success → Save file cache + mark fallback source + smart display
                                              → All failed → Show placeholder icon
```

####   Player

Dual-engine playback:

- **ExoPlayer**: HLS, ICY (Shoutcast) supports stream metadata parsing
- **MediaPlayer**: Via StreamProxy for metadata capture
- Live ICY metadata extraction (song/artist) for track history
- LastFM API for supplementary track artwork

Also supports: external player, MPD protocol, Chromecast (Play variant only).

####   Exponential Volume Control

The player provides a **Volume Mapping Boost** toggle in **Settings → Player**, which switches between exponential volume control and default linear volume control.

- **On (default)**: Enables a two-stage volume mapping strategy. The maximum gain coefficient is first adjusted based on system volume, and then an exponential curve (symmetric around 50%) is applied to the in-app volume slider. This keeps the minimum volume low enough for quiet environments while making the maximum volume loud enough for noisy environments, with a natural perceived loudness curve across the whole range.
- **Off**: Restores the default linear volume control (gain = maxGain × volume / 100) without system volume compensation or exponential curve processing.

**System Volume Compensation** (active when toggled on, piecewise linear):

| System Volume Range | Gain Coefficient Range | Effect |
|--------------------|----------------------|--------|
| < 35% | 0.5× → 1.0× | Reduced by half, quiet enough for silent environments |
| 35% ~ 65% | 1.0× | Normal |
| > 65% | 1.0× → 2.0× | Doubled, easier to hear in noisy environments |

**In-App Volume Curve** (active when toggled on, symmetric exponential curve centered at 50%):

| App Volume | Output Gain |
|-----------|-------------|
| 0 | Mute |
| 25% | maxGain × 0.5 |
| 50% | maxGain × 1.0 |
| 100% | maxGain × 2.0 |

Lower volumes fall below the linear curve (minimum halved), while higher volumes rise above it (maximum doubled), maintaining smooth transitions throughout.

####   MPD (Music Player Daemon) Support

MPD (Music Player Daemon) is an open-source audio playback server program that typically runs on Linux servers, NAS devices, or Raspberry Pi. RadioDroid supports streaming radio stations to a remote MPD server for playback. This feature is useful for:

- **Home Audio System**: Run MPD on a home server and push radio streams to your home speakers via RadioDroid
- **NAS/Raspberry Pi Music Center**: Use idle NAS or Raspberry Pi as an audio output device
- **Multi-Room Sync**: Leverage MPD's multi-client capabilities to sync the same radio across multiple rooms
- **Remote Control**: Use your phone as a remote control for playback on distant devices via MPD

**How to Use**:

1. Ensure you have a running MPD server (must be accessible from your phone on the same LAN or via public network)
2. Go to **Settings → External Player → Music Player Daemon**
3. Enable the "Enable MPD" toggle
4. Tap **"Your servers"** to add an MPD server:
   - **Name**: Custom identifier (e.g., "Living Room", "Office NAS")
   - **Host**: IP address or domain name of the MPD server
   - **Port**: MPD listening port (default `6600`)
   - **Password**: Fill in if MPD has password authentication configured; leave blank otherwise
5. After saving, tap the server entry to test connection status
6. Select MPD as the target player in the player selector dialog

**Technical Details**:

| Item | Description |
|------|-------------|
| Protocol | Native MPD text protocol (not HTTP), direct TCP Socket connection |
| Authentication | Supports optional MPD password authentication |
| Connection | App establishes direct TCP connection to MPD server, no intermediate proxy |
| Supported Operations | Play, Pause, Resume, Stop, Volume Control |
| Multi-Server | Save and switch between multiple MPD server configurations |
| Network | LAN recommended; public access requires firewall rule for MPD port |

**Notes**:
- MPD is an existing feature from the original RadioDroid; this fork has not modified or tested it, documentation is provided for reference only
- Network connectivity between phone and MPD server is required (same WiFi or VPN)
- Before first use, verify MPD is reachable from your phone (test with `telnet <IP> 6600` in terminal)
- If connection fails, check: MPD service running?, correct port?, firewall rules?, password match?

####   Proxy Support

HTTP and SOCKS5 proxy with username/password authentication. Settings serialized via Gson. Uses `proxyAuthenticator` (fixed from the original's incorrect `authenticator`).

####   Equalizer

Two sets of presets available. One uses the Android system's built-in equalizer presets, whose names and sound profiles may vary across device manufacturers; the other is built into the app, featuring "Vocal" (optimized for news, talk shows, podcasts) and "Music" (general-purpose tuning for music stations).

Also supports per-station equalizer customization: tap the equalizer button in station details to configure equalizer parameters for individual stations, enabling automatic switching to different sound profiles when switching stations.

####   Per-Station Buffer Strategy

The station details page provides a **Buffer Strategy** configuration button, allowing you to set a specific playback buffering strategy for each individual station:

| Strategy | Description | Best For |
|----------|-------------|----------|
| Light Buffer | Plays after 2.5s buffering, low memory usage, low latency | Stable networks, fast playback |
| Enhanced Buffer | Plays after 10s buffering, absorbs network fluctuations | Occasionally unstable networks |
| Extreme Buffer | Plays after 30s buffering, maximum resilience against interruptions | Moderately unstable networks |

**How to Use**: Open station details, tap the "Buffer Strategy" button, and select the strategy that best fits the station. Settings are saved automatically and take effect immediately (if the station is currently playing, playback restarts with the new strategy).

**Why per-station?**: Different radio stations have vastly different stream server quality — some are rock-solid and benefit from light buffering for quick response, while others experience frequent interruptions and need longer buffers to play smoothly. Setting a strategy per station gives you the best balance between playback speed and stability.

**Note**: Longer buffer times may cause the track history to get out of sync with what you're actually hearing. This is because the track history shows the current song from the radio stream, while your player — due to the longer buffer — is still playing content that entered the queue earlier. For example, you might still be hearing Song A, but the track history already shows Song B's title. The longer the buffer, the larger this gap becomes. Light buffer (2.5s) essentially avoids this issue.

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
- **Station Detail Expansion**: Website visit, share, alarm, desktop shortcut creation
- **Trend Icons**: Click trend indicators (rising/falling/flat)
- **Country Flags**: Flag icons per station in list view
- **Android TV**: Auto-detect TV devices, channel management
- **Metered Connection Warning**: Alerts before playing on metered networks

---

## Changelog

> Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)

### v1.00
*2026-07-08*

**PLS 播放列表支持**
- **新增**：`PlaylistParser` 播放列表解析工具类，支持 PLS 与 M3U 格式识别与解析
- **新增**：播放流程（`PlayerService`、`PlayStationTask`）自动检测 `.pls/.m3u` 流地址，下载并解析为真实音频流 URL 后再播放，解决大量 PLS 格式电台无法播放的问题

**播放音量控制重构**
- **重构**：RadioPlayer 音量映射改为以 50% 为对称点的指数曲线，低音量端降低 1 倍、高音量端提升 1 倍，人耳听感更均匀
- **重构**：PlayerService 系统音量补偿区间调整为 <35% / 35%-65% / >65% 三段式，低音量更安静、高音量在嘈杂环境更易听清
- **新增**：设置 → 播放器 → 音量映射增强开关，默认开启；关闭后恢复原始线性音量控制

**收藏交互修复**
- **修复**：列表项展开状态下的收藏/撤销收藏按钮状态与点击逻辑，避免撤销收藏按钮失效或状态不刷新

**主题与显示**
- **新增**：设置 → 外观 → 主题增加「自动」模式，可跟随系统暗色/亮色状态自动切换
- **优化**：主题默认选项改为「自动」，新安装用户首次启动即可适配系统主题

**音频设备控制**
- **新增**：设置 → 播放器 → 有线耳机零音量暂停开关
- **新增**：设置 → 播放器 → 蓝牙耳机零音量暂停开关
- **优化**：AudioDeviceMonitor 耳机连接检测方法暴露给 PlayerService，音量变化监听中实现零音量自动暂停逻辑

**老版本兼容性修复**
- **修复**：数据库初始化流程增加用户设置备份/恢复机制，重建数据库时保留原有偏好设置，避免老版本升级后配置丢失

**版本更新**
- 版本号升级至 v1.00 (versionCode 109)

### v0.99
*2026-06-02*

**均衡器爆音修复**
- **修复**：均衡器频段参数在全部配置完成后再启用（`setEnabled(true)`），避免中间态频段配置导致 Android 音频管道产生脉冲爆音
- **修复**：BassBoost 增强器在设置强度值后再启用，与均衡器同理
- **修复**：淡入渐入任务改用 `pendingFadeInTasks` 队列管理，50ms 延迟执行；每次新渐入前取消上一次未完成的任务，防止任务重叠引发音量突变爆音

**播放电台高亮修复**
- **修复**：`highlightCurrentStation()` 遍历查找前将 `playingStationPosition` 重置为 -1，确保列表变更（拖拽排序、取消收藏）后播放电台高亮始终绑定电台 UUID 而非列表位置
- **修复**：`updateList()` 小变化分支在 `notifyDataSetChanged()` 前先调用 `highlightCurrentStation()`，确保高亮位置实时同步

**电台图标显示优化**
- **新增**：HD 图标发现机制 — 对仅有主页 URL 但无图标 URL 的电台，自动解析主页 HTML 查找 Apple Touch Icon 等高分辨率图标，成功后自动替换缓存和显示
- **新增**：`applySmartDisplayLogic()` 图标智能显示逻辑，根据图标实际尺寸优化 ImageView 适配
- **新增**：图标回退 URL 自动构建系统，根据主页域名自动构造 `favicon.ico` 和 `apple-touch-icon.png` 路径
- **优化**：后台重试机制增强，回退图标缓存 4 小时后静默重试主图标

**文档修复**
- **修复**：README.md 目录锚点链接跳转错误

### v0.98
*2026-05-30*

**播放爆音修复**
- **修复**：采用播放会话代际标记（Session ID）机制，防止旧播放器延迟回调污染新播放器，消除快速切换电台时的爆音
- **修复**：旧播放器停止前先静音再延迟释放（100ms），消除 AudioTrack 硬件瞬态脉冲导致的爆音
- **修复**：允许 Playing→Playing 重入通知，确保淡入正确触发

**播放时长修复**
- **修复**：暂停不再计入总播放时长，跨会话累计
- **修复**：播放起始时间在音频实际渲染后才开始计时，消除缓冲等待时间的虚增
- **修复**：大播放器总时长不再显示 00:00

**UI 界面优化**
- **优化**：回到顶部按钮位置调整 — 水平方向右移至电台详情箭头附近不重叠，垂直方向下移至与最后一行电台详情按钮平行
- **优化**：小播放器播放/暂停按钮放大 — Vector drawable 从 36dp 增至 48dp，使用 1:1 宽高比约束填满控制条高度
- **优化**：收藏页切换视图按钮不再折叠到溢出菜单，始终显示在工具栏
- **优化**：电台列表当前播放电台视觉标识（播放中指示覆盖层）
- **优化**：大播放器"时长-缓冲-数据"三栏布局，修复部分语言文字与数字重叠问题

**工具栏按钮自定义**
- **新增**：设置 → 外观 → 工具栏按钮，6 个开关项可分别控制搜索、排序、定时关闭、随机播放、切换视图、投屏按钮的显示/隐藏
- **新增**：多语言字符串支持（简体中文、English、Русский、Español）

**功能增强**
- **新增**：收藏夹网格/列表视图切换
- **新增**：播放缓冲策略设置界面（轻度/增强/极限缓冲）
- **删除**：移除电台详情/弹窗菜单中的"添加到桌面快捷方式"按钮及相关代码

**版本更新**
- 版本号升级至 v0.98 (versionCode 107)

### v0.97
*2026-05-23*

**播放爆音修复**
- **修复**：RadioPlayer 音量控制改用指数曲线映射（ratio²），低音量端变化更精细，避免爆音
- **新增**：`setMaxGain()` 动态增益控制 — PlayerService 根据系统音量动态调整播放增益系数（0.1~4.0），低系统音量更安静，高系统音量可超增益提升响度

**电台图标文件缓存系统**
- **重构**：PlayerServiceUtil 图标加载流程重构为三级缓存：文件缓存 → Picasso 内存/磁盘缓存 → 网络加载
- **新增**：`StationIconCache` 文件缓存层 — 收藏电台图标永久缓存，其他电台图标7天过期
- **优化**：打开页面时优先从文件缓存加载图标，保证第一眼看到图标
- **新增**：后台静默升级机制 — 缓存为回退图标的电台，500ms 延迟批量执行后台主图标重试，成功后自动替换显示
- **优化**：网络加载失败后立即尝试回退 URL，不再等待重试延迟

**电台个性化均衡器**
- **新增**：EqualizerActivity 支持电台级别均衡器设置 — 每个电台可单独配置开关、预设、频段级别、低音增强
- **新增**：`hasStationEqualizer()` / `getStationEqEnabledKey()` 等静态方法，支持 PlayerService 按电台切换均衡器参数
- **优化**：均衡器标题栏显示电台名称，区分全局和电台专属设置

**播放器界面优化**
- **优化**：大播放器信息栏统一样式 — 播放时长、数据用量、缓冲时间三栏布局，添加标签前缀（Duration/Data/Buffered），统一字号和颜色
- **优化**：缓冲时间显示位置调整，与播放时长和数据用量水平排列

**播放服务增强**
- **重构**：PlayerService 大幅增强（+254 行）— 均衡器与播放联动、音量控制优化、电台 UUID 传递
- **重构**：PlayerServiceUtil 图标缓存系统重构（+411 行）
- **新增**：`PlayerWrapper.playRemote()` 支持 stationUuid 参数传递
- **新增**：RadioPlayer 记录当前电台 UUID，支持按电台切换均衡器

**多语言更新**
- **新增**：缓冲策略、均衡器设置相关中英文字符串
- **优化**：电台图标设置描述更准确（"下载并显示电台图标" / "不下载图标，节省存储空间"）
- **更新**：西班牙语、俄语翻译同步

**代码质量与清理**
- **清理**：移除 ExoPlayerWrapper 中 80+ 处调试日志（Log.d/Log.i），保留必要的运行状态日志
- **清理**：移除 BufferSettingsDialog 中 6 处调试日志
- **清理**：移除 PlayerService 中 rawMetadata 遍历调试日志
- **修复**：12 处 `e.printStackTrace()` 替换为 `Log.e(TAG, message, e)`，统一异常日志输出
- **删除**：废弃文件 `dialogs/DatabaseUpdateProgressDialog.java`（与 `ui.DatabaseUpdateProgressDialog` 重复）
- **删除**：调试残留文档 `GRADLE_INSTALLATION_GUIDE.md`
- **优化**：ExoPlayerWrapper 从 2864 行精简至 2596 行，功能逻辑不变

### v0.96
*2025-05-18*

**电台图标增强**
- **新增**：电台图标多级回退获取机制 — 优先使用服务器提供的 `IconUrl`，失败后回退到网站通用图标（`favicon.ico` / `apple-touch-icon.png`），最终使用 Google Favicons 服务兜底
- **优化**：Picasso 图片库重试机制（最多 3 次，间隔 1s/3s/5s）
- **修复**：收藏电台列表（FragmentStarred）使用图标仅显示适配器（`ItemAdapterIconOnlyStation`），统一图标展示风格

**音频体验增强**
- **新增**：音频均衡器功能 — `EqualizerActivity` 提供完整均衡器控制界面
- **新增**：双套预设方案 — 系统原生预设（各厂商效果不同）+ 应用内置预设（「人声」适合新闻/访谈/脱口秀，「音乐」适合音乐类电台）
- **新增**：PlayerService 服务端均衡器实现，支持预设设置和频段级别控制

**闹钟系统现代化**
- **重构**：闹钟播放器选择器（`PlayerSelectorDialog`）现代化改造，优化应用和设备选择交互逻辑
- **优化**：`AlarmReceiver` 闹钟触发和播放逻辑
- **优化**：`ItemAdapterRadioAlarm` 闹钟列表适配器

**音频设备管理**
- **新增**：`AudioDeviceMonitor` 音频设备监控系统，自动检测耳机插拔、蓝牙 A2DP 连接/断开等音频输出设备变更
- **新增**：`HeadsetConnectionReceiver` 增强的音频设备连接状态管理 — 支持蓝牙耳机(A2DP/Headset 协议)和有线耳机的连接/断开检测，防抖处理（2秒窗口），`isAudioBluetoothDevice` 智能识别音频类蓝牙设备
- **新增**：`BecomingNoisyReceiver` 音频噪声事件处理 — 拔出耳机或蓝牙断开时自动暂停播放，支持「暂停」和「关闭应用」两种策略（设置中配置）
- **新增**：有线耳机重连后自动恢复播放（设置开关）
- **新增**：蓝牙断开可配置为暂停或关闭应用
- **新增**：蓝牙重连后自动恢复播放（设置开关）

**播放历史修复**
- **修复**：`TrackHistoryAdapter` 中图标 URL 处理逻辑，提升曲目历史图标显示成功率
- **修复**：`TrackHistoryInfoDialog` 对话框显示问题

**排序功能**
- **新增**：电台列表排序功能（`FragmentLocalStations`）— 支持按名称/点击量/投票数/最近变更四种模式排序
- **新增**：排序方向切换（升序/降序），点击相同模式切换方向，偏好持久化保存

**录音播放器**
- **新增**：录音功能完整实现 — `RecordingsManager` 管理录音列表和排序
- **新增**：`RecordingsAdapter` 录音列表显示、播放、详情和删除功能
- **新增**：`RadioPlayer` 录音管理接口，PlayerService 录音控制
- **新增**：大小播放器界面录音控制按钮（开始/停止录音）

**多语言和界面优化**
- **优化**：FragmentSettings 设置界面多语言同步和代码重构（546 行变更）
- **优化**：ApplicationSelectorDialog 应用选择器对话框现代化（162 行变更）
- **优化**：FragmentAbout 关于页面内容更新
- **优化**：FragmentHistory 历史记录界面优化
- **优化**：FragmentPlayerFull/FragmentPlayerSmall 大小播放器界面优化
- **优化**：FragmentStarred 收藏界面重构（171 行变更）
- **优化**：FragmentTabs 标签页逻辑优化
- **优化**：ActivityMain 主界面逻辑优化（250 行变更）
- **优化**：RadioDroidApp 应用初始化逻辑优化
- **优化**：Utils 工具类更新

**数据库和导出修复**
- **修复**：导出数据库报错信息截断问题 — `exportDatabase` 数据库查询操作移至后台线程，避免主线程访问导致 `IllegalStateException`
- **新增**：缺失的中文翻译（导出成功/失败提示、进度条文本等）
- **修复**：`e.getMessage()` 为 null 时的显示问题
- **新增**：`getDisplayPathFromUri` 方法，将 content URI 转换为友好的文件路径显示
- **修复**：英文 `strings.xml` 中 `warning_low_external_storage` 混入中文问题

**数据库结构更新**
- **更新**：`RadioDroidDatabase` 数据库结构
- **更新**：`RadioStationDao` 数据访问对象，新增查询方法
- **更新**：`RadioStationRepository` 数据仓库重构（207 行变更）
- **更新**：`IPlayerService.aidl` 接口定义

**其他修复**
- **优化**：ExoPlayerWrapper ExoPlayer 封装层更新（50 行变更）
- **优化**：IcyDataSource ICY 数据源简化（137 行变更，减少冗余代码）
- **优化**：RadioDataSourceFactory 数据源工厂
- **优化**：StreamProxy 流代理元数据解析增强
- **更新**：AndroidManifest.xml 权限和组件声明
- **更新**：.gitignore 忽略规则

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