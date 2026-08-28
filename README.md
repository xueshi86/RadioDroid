<p align="center">  
  <img src="app/src/main/res/drawable-xxxhdpi/ic_launcher.png" alt="RadioDroid Logo" width="96"/>  
</p>

<h1 align="center">RadioDroid</h1>

<div align="center">
  <b>全球电台收音机 · 离线数据库 · 魔改增强版</b><br/>
  <i>Global Radio Browser · Offline Database · Enhanced Edition</i><br/>
</div>

<div align="center">
  <a href="#中文">中文介绍</a> ·
  <a href="#english">English</a> ·
  <a href="CHANGELOG.md">Changelog</a>
</div>

---

## 中文

> **更新日志**：[CHANGELOG.md](CHANGELOG.md) — 查看完整版本变更记录

### 项目由来

**RadioDroid** 是一款基于 Android 平台的全球电台收音机应用，电台数据来源于 [radio-browser.info](https://www.radio-browser.info/) 社区数据库，收录了全球数万个在线电台。

本项目 Fork 自 [segler-alex/RadioDroid](https://github.com/segler-alex/RadioDroid)（v0.86 版）。原版自 2023 年以来停止维护，且存在多个影响日常使用的 Bug（如中文搜索失效、部分英文搜索无结果等），因此在该版本基础之上进行了深度「魔改」——持续修复问题、优化体验并添加实用功能，形成了当前版本。

### 构建版本说明

本应用提供两种构建变体（Build Flavor）：

| 版本       | 说明                                                                               |
| -------- | -------------------------------------------------------------------------------- |
| **Free** | 无 Google Play Services 依赖，不支持 Chromecast 投屏，不集成 SafetyNet。纯开源构建，适合 F-Droid 或自行构建 |
| **Play** | 集成 Google Play Services，支持 Chromecast 投屏和 SafetyNet 完整性检查，适用于 Google Play 商店分发   |

两个版本的核心功能（收音机播放、离线数据库、搜索等）完全一致，区别仅在于是否包含 Google 专有服务。

> **系统版本支持说明**：本应用优先支持 **Android 9.0+**（API 28+），在该版本区间获得最佳体验。Android 5.x~8.x 等低版本设备已做兼容适配，但部分特性（如均衡器实时调节、缓冲表现）可能存在小幅瑕疵，属预期行为。

### 与官方原版的主要区别

#### 核心架构变更：离线数据库模式

**原版方式**：每次浏览电台列表、搜索、按分类查看等操作，均实时向远在欧洲的 [radio-browser.info](https://www.radio-browser.info/) API 服务器发起网络请求。受服务器地理距离影响，延迟高且连接不稳定，体验较差。

**本版改为本地数据库模式**：

- 首次使用时，用户手动触发全量数据同步，将服务器上的电台数据（约 5 万+ 条目）下载存储到本地 SQLite 数据库（基于 Room 框架）
- 下载采用**多线程并行策略**：根据设备 CPU 核心数、网络延迟动态调整线程数（2-10 线程），多线程分页请求，自动测速后选择最快的 API 服务器
- 之后所有电台浏览、搜索、分类筛选、排序等操作均直接查询本地数据库，**无需网络连接**
- 数据库更新过程中支持**后台执行**（通过 WorkManager 前台服务），可切换到其他 App 继续操作
- 支持**断点续传**：中断的下载任务可在下次恢复继续，无需重新下载
- 提供**增量更新**：基于 `json/stations/lastchange` 端点仅拉取自上次同步以来变更的电台（每次批量 1000 条，uuid 水位 + REPLACE 主库直写），秒级完成、流量消耗极小；可在设置中手动触发，或开启「自动增量更新」（距上次更新 24 小时后静默执行，可限制仅 Wi-Fi）
- 更新前自动检查网络连通性、电量（<5% 拒绝更新，<20% 警告提示）、存储空间（需 ≥50MB 内部空间）
- 提供**数据库导入/导出**功能，换机或重装时可迁移数据，避免重复下载
- **镜像服务器级联容错**：最近成功的 API 服务器会被持久化，下次启动优先复用；请求失败自动级联 DNS 镜像列表与官方静态兜底服务器（de1/de2/fi1/at1），成功即回写；级联使用短超时（5s 连接），持久化服务器超过 7 天自动重新测速

**优点**：

| 方面     | 说明                              |
| ------ | ------------------------------- |
| 响应速度   | 所有列表浏览、搜索、筛选均本地 SQLite 查询，毫秒级响应 |
| 离线可用   | 无网络环境下正常浏览电台信息，播放时仅需网络传输音频流     |
| 稳定性    | 不依赖远程 API 可用性，不受服务器故障或网络波动影响    |
| ️ 数据一致 | 搜索结果可复现，列表顺序稳定，不受服务器侧数据变更影响     |
| ⚡ 交互流畅 | 电台切换、列表滚动、实时搜索反馈均流畅无卡顿          |

**缺点**：

| 方面     | 说明                                |
| ------ | --------------------------------- |
| 首次初始化  | 全量下载 5 万+ 电台，耗时约 1-20 分钟（取决于网络质量） |
| 数据时效性  | 增量更新大幅缓解：24h 后自动同步变更，手动增量秒级完成     |
| 存储空间   | 本地数据库约 40MB                       |
| ️ 更新方式 | 支持手动全量 / 手动增量 / 自动增量（可仅 Wi-Fi）    |

#### 预置数据库文件

自 **v0.96** 起，每个版本的 Release 附件中将提供一份预下载的完整电台数据库文件。用户可直接下载并导入至应用中，无需经历耗时的首次全量同步过程，特别适合以下场景：



- 首次使用者希望开箱即用
- 网络条件有限或服务器连接不稳定的用户
- 希望快速恢复使用环境的换机用户

**用法**：在应用的「设置 → 本地数据库 → 导入数据库」中选择下载的数据库文件即可完成导入。

#### 本地电台智能显示

应用会根据用户手机的系统设置，智能优先展示与用户相关的电台：

1. **优先显示系统国家电台**：根据手机系统国家代码（`Locale.getDefault().getCountry()`），从数据库筛选该国电台
2. **回退到系统语言电台**：若国家无电台，尝试查询系统语言+国家的电台组合
3. **进一步回退**：仍无结果则查询仅按语言筛选
4. **兜底显示全部电台**：所有条件均不满足时显示全部电台列表

刷新列表时始终遵循此优先级逻辑，确保用户首先看到最可能感兴趣的电台。

#### 搜索功能

系统提供两种搜索入口：

**1. 快速搜索（电台 Tab 内）**

电台主列表中直接输入关键词搜索，基于本地 SQLite 数据库的 `LIKE` 查询，实时返回匹配结果。搜索结果采用**加权排序**：前缀匹配 > 词边界匹配（空格/连字符/括号/点后起始）> 包含匹配，同等权重下按点击热度排序——搜 "jazz" 时 "Jazz FM" 优先于 "Ultra Jazz"。关键词中的 `%`/`_` 通配符会被转义，不会干扰匹配。

**2. 高级多条件搜索**

独立的高级搜索页面，支持同时设置四个维度的筛选条件：

- **国家**：下拉选择，可输入过滤（前缀优先排序）
- **语言**：下拉选择，可输入过滤（前缀优先排序）
- **标签**：下拉选择，标签已拆分为单标签并按出现次数排序（热门优先），可输入过滤；修正了原版直接列出 "rock,pop" 组合串的问题
- **关键词**：文本输入，500ms 防抖延迟，避免过度查询

四个条件可任意组合（均为可选），任意条件变更时自动触发联合查询 (`searchStationsByMultiCriteria`)。支持一键重置所有筛选条件。筛选条件区域可折叠/展开，节省屏幕空间。

#### SQLite FTS 全文搜索引擎

数据库内置 `radio_stations_fts` 表（SQLite FTS4），对电台名称、标签、国家、语言建立全文索引，支持按键词前缀检索（`MATCH 'kw*'`），并按名称前缀/词边界加权排序。`RadioStationDao` 提供按名称、标签、国家、语言的独立 FTS 快速搜索通道。全量/增量数据库更新后会自动重建 FTS 索引，保证新入库电台可被即时搜索到（修复了更新后 FTS 索引与主库失步的问题）。

#### 电台列表排序

支持四种排序方式，点击 Toolbar 上的排序按钮弹出选择对话框：

| 排序方式  | 说明                              |
| ----- | ------------------------------- |
| 按名称   | 字母序排列                           |
| 按点击量  | 按 radio-browser.info 全球用户点击热度排序 |
| 按投票数  | 按社区投票数排列                        |
| 按最近变更 | 按电台信息最后更新时间排列                   |

当前排序模式高亮显示 ↑（升序）/ ↓（降序）指示，点击相同模式可切换排序方向。排序偏好自动持久化保存。

#### 随机播放

电台列表页 Toolbar 上提供随机播放按钮。点击后从本地数据库随机选取一个电台，最多尝试 10 次寻找有效播放源的电台，每次等待 10 秒验证连通性。找到有效电台则自动开始播放。

#### 回到顶部

电台列表和高级搜索页面均提供浮动按钮（FAB），列表滚动离开顶部后自动浮现，点击平滑滚回顶部。

#### 曲目历史

原版 RadioDroid 已有曲目历史功能。本应用针对流媒体 ICY 元数据中的曲目名称和艺术家信息段，优化了截取和解析逻辑，提升正确匹配和显示当前播放曲目名与艺术家的概率。同时支持通过 LastFM API 获取曲目附加元数据。

**Ogg Vorbis / Opus 流元数据支持**（新增）：大量使用 Ogg/Opus 编码的电台（欧洲小众台、播客流）曲目信息此前无法显示，现已接入 ExoPlayer 元数据链路的 `VorbisComment` 解析（TITLE/ARTIST），曲目名、艺术家、通知与曲目历史均可正常展示；相同曲目自动去重，避免通知/历史高频刷新。

#### 电台图标

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

| 特性   | 说明                                       |
| ---- | ---------------------------------------- |
| 分层缓存 | 收藏电台图标存入永久缓存（不删除），其他电台图标存入半永久缓存（7天过期）    |
| 快速显示 | 打开页面时，有缓存的图标立即显示（不管来源），保证用户第一眼看到图标       |
| 智能升级 | 缓存为回退图标的电台，后台每4小时静默尝试获取主图标；无主图标时尝试 HD 发现 |
| 尺寸保护 | 后台获取的新图标尺寸小于当前显示图标时不替换，避免用更小的图覆盖清晰的大图    |
| 来源标记 | 系统会标记每个缓存图标的来源（主图标/回退），主图标永不再重试，回退图标定时升级 |
| 用户控制 | 设置中可关闭电台图标显示，减少缓存占用，适合存储空间紧张的用户          |

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
                                   → 全部失败 → 显示动态占位图标
```

**动态电台占位符**（新增）：无图标电台不再显示统一的应用图标占位，而是自动生成「首字符 + 颜色」占位图——颜色按电台 UUID 哈希稳定分配 7 色 Material 色板（同一电台永远同色），字符取电台名称首个字母/数字或中文首字。列表、图标兜底、通知大图标三处显示完全一致；生成结果内存缓存，列表滚动零开销。

#### 播放点击上报（社区回馈）

播放成功时异步上报到 radio-browser.info 社区（`json/url/<uuid>` 端点），保持点击热度排名新鲜、回馈社区数据库：

- **防双计**：经链接解析端点播放的电台不再重复上报（闹钟、手动解析等路径全覆盖）
- **5 秒冷却**：同一电台冷却期内不重复上报，防缓冲抖动刷屏
- **失败静默**：上报失败不影响播放，无任何弹窗或重试
- **本地计数**：上报成功后本地 clickcount+1，点击热度排序即时生效
- **隐私开关**：可在「设置 → 互动」中关闭上报（关闭后仅本地计数，不发任何网络请求）

#### 播放器

内置播放器基于 ExoPlayer 和 Android MediaPlayer 双引擎：

- **ExoPlayer**：支持 HLS、ICY（Shoutcast）协议，对 Shoutcast 流解析元数据
- **MediaPlayer**：通过 StreamProxy 代理捕获元数据
- 播放时自动解析流内嵌的 ICY 元数据（歌曲名/艺术家），用于曲目历史记录
- 支持通过 LastFM API 补充曲目封面等元数据

同时支持：外部播放器调用、MPD（Music Player Daemon）协议、Chromecast 投屏（仅 Play 版）。

#### 音量指数级控制

播放器提供「音量映射增强」开关（在「设置 → 播放器」中），用于在指数级音量控制和默认线性音量控制之间切换。

- **开启（默认）**：启用双层音量映射策略。先根据系统音量动态调整最大增益系数，再对应用内音量滑杆应用以 50% 为对称点的指数曲线，从而在安静环境获得足够低的最小音量、在嘈杂环境获得足够高的最大音量，同时让中间档位的音量变化更符合人耳听觉特性。
- **关闭**：恢复默认线性音量控制（增益 = maxGain × volume / 100），不做系统音量补偿和指数曲线处理。

**系统音量补偿**（开启时生效，分段线性）：

| 系统音量区间    | 增益系数范围      | 效果              |
| --------- | ----------- | --------------- |
| < 35%     | 0.5× → 1.0× | 降低 1 倍，安静环境不吵   |
| 35% ~ 65% | 1.0×        | 正常              |
| > 65%     | 1.0× → 2.0× | 提升 1 倍，嘈杂环境更易听清 |

**应用内音量曲线**（开启时生效，以 50% 为对称点的指数曲线）：

| 应用音量 | 输出增益          |
| ---- | ------------- |
| 0    | 静音            |
| 25%  | maxGain × 0.5 |
| 50%  | maxGain × 1.0 |
| 100% | maxGain × 2.0 |

低音量端增益低于线性（最低减半），高音量端增益高于线性（最高翻倍），全范围保持平滑过渡。

#### MPD 播放器支持

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

| 项目   | 说明                                  |
| ---- | ----------------------------------- |
| 协议   | MPD 原生文本协议（非 HTTP），基于 TCP Socket 直连 |
| 认证   | 支持 MPD 密码认证（可选）                     |
| 连接方式 | 应用直接与 MPD 服务器建立 TCP 连接，不经过中间代理      |
| 支持操作 | 播放、暂停、恢复、停止、音量调节                    |
| 多服务器 | 支持保存多个 MPD 服务器配置并自由切换               |
| 适用网络 | 局域网优先；公网访问需确保防火墙开放 MPD 端口           |

**注意事项**：

- MPD 功能为原版 RadioDroid 已有功能，本分支未对其进行修改测试，仅添加介绍
- 手机与 MPD 服务器之间需要网络连通性（同一 WiFi 或 VPN）
- 首次使用建议先确认 MPD 服务器可从手机正常访问（可用终端工具 `telnet <IP> 6600` 测试）
- 如连接失败，请检查：MPD 服务是否运行、端口是否正确、防火墙规则、密码是否匹配

#### 代理支持

支持 HTTP 和 SOCKS5 代理，带认证用户名/密码。代理设置通过 Gson 序列化存储。每个 OkHttp 请求经 `proxyAuthenticator`（原版错误使用了 `authenticator`，已修正）处理认证。

#### ️ 多语言界面

设置中提供界面语言选择，支持：跟随系统、中文、英文、俄语、西班牙语、德语、法语、意大利语、希腊语（共 8 种语言）。通过 `initAppLanguage()` 在 `ActivityMain.onCreate()` 中动态加载生效。针对所有新增和修改过的代码界面进行了多语言的全面适配，消除了原版代码中中英文混杂显示的问题。

#### 暗色主题

支持亮色和暗色主题，可在设置中切换。修正了原版暗色模式下部分界面元素和字体颜色显示不正确的问题。常用界面元素（标题、标签、描述等）根据主题自动调整文字颜色。

#### 均衡器

提供双套预设方案。一套调用 Android 系统原生均衡器预设，不同设备厂商的预设名称和调音效果可能存在差异；另一套为应用内置预设，包含「人声」（适合新闻、访谈、脱口秀等以人声为主的节目）和「音乐」（适合音乐类电台的通用调音方案）。

同时支持电台个性化均衡器设置：在电台详情中点击均衡器按钮，可为单个电台单独配置均衡器参数，实现不同电台自动切换不同音效的个性化体验。

#### 电台缓存策略

电台详情中提供「缓存策略」配置按钮，允许为每个电台单独设置播放缓冲策略，实现个性化的播放体验：

| 策略   | 说明                      | 适用场景        |
| ---- | ----------------------- | ----------- |
| 轻度缓冲 | 缓冲 2.5 秒后开始播放，内存占用小、延迟低 | 网络稳定、追求快速播放 |
| 增强缓冲 | 缓冲 10 秒后开始播放，有效吸收网络波动   | 网络偶尔不稳定     |
| 极限缓冲 | 缓冲 30 秒后开始播放，最大限度抵御网络中断 | 网络中度不稳定     |

**使用方式**：在电台详情界面点击「缓存策略」按钮，选择适合该电台的策略，配置自动保存并立即生效（若当前正在播放该电台则自动重启播放）。

**为什么需要按电台单独设置**：不同电台的流媒体服务器质量参差不齐——有些服务器稳定流畅，适合轻度缓冲快速响应；有些服务器波动频繁，需要更长缓冲时间来平滑播放中断。为每个电台单独设置策略，可在播放速度和稳定性之间取得最佳平衡。

**注意事项**：缓冲时间越长，曲目历史记录与当前实际播放的内容可能出现时间差。因为曲目历史显示的是电台流当前的歌曲信息，而你的播放器由于较长的缓冲，实际播放的内容还在"排队"中——你可能听到的还是上一首歌，但曲目历史已经显示了下一首歌的标题。缓冲时间越长，这个时间差就越大。轻度缓冲（2.5 秒）基本不会出现此问题。

#### ️ 其他功能

- **收藏电台**：支持添加/移除收藏，滑动删除，撤销操作（Snackbar），M3U 导入/导出
- **历史记录**：播放过的电台列表，支持 M3U 导出，一键清除
- **睡眠定时器**：SeekBar 设置分钟数，终点自动停止播放，保存默认值
- **闹钟**：支持设置指定时间自动播放指定电台。闹钟默认仅生效一次，如需每天重复请在闹钟编辑界面开启「重复」开关
- **录音功能**：录制当前播放的电台流为音频文件
- **电台详情展开**：点击展开按钮显示网站访问、分享、添加闹钟、创建桌面快捷方式等操作
- **趋势图标**：电台列表显示点击量趋势（上升/下降/持平）
- **国家图标**：电台列表显示所属国家的国旗图标
- **Android TV 支持**：检测 TV 设备自动启用频道管理
- **网络类型指示器**：播放时显示当前使用的 Wi-Fi 或移动数据图标，直观了解网络类型

---

## English

> **Changelog**: [CHANGELOG.md](CHANGELOG.md) — view the full version history

### Introduction

**RadioDroid** is an Android global radio browser app powered by the [radio-browser.info](https://www.radio-browser.info/) community database, which hosts tens of thousands of online radio stations worldwide.

This project is a heavily customized fork of [segler-alex/RadioDroid](https://github.com/segler-alex/RadioDroid) (v0.86). The original has been unmaintained since 2023 and had several bugs affecting daily usage. This fork introduces deep architectural changes, bug fixes, and practical features.

### Build Variants

| Variant  | Description                                                                                                                            |
| -------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| **Free** | No Google Play Services dependency, no Chromecast casting, no SafetyNet. Pure open-source build, suitable for F-Droid or self-building |
| **Play** | Includes Google Play Services, supports Chromecast casting and SafetyNet integrity checks, for Google Play Store distribution          |

Core functionality is identical across both variants. The difference is the availability of Google proprietary services.

> **System Version Note**: This app is optimized for **Android 9.0+** (API 28+), where it delivers the best experience. Older versions (Android 5.x-8.x) are supported with compatibility adaptations, but some features (e.g., real-time equalizer adjustments, buffering behavior) may have minor quirks, which is expected.

### Key Differences from Official RadioDroid

#### Core Architecture: Offline Database Mode

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

| Aspect         | Description                                                                     |
| -------------- | ------------------------------------------------------------------------------- |
| Speed          | All operations are local SQLite queries with millisecond response times         |
| Offline        | Browse and search stations without internet; only audio streaming needs network |
| Stability      | Independent of remote API availability; unaffected by server outages            |
| ️  Consistency | Search results and list ordering are stable and reproducible                    |
| ⚡ UX           | Smooth station switching, scrolling, and real-time search feedback              |

**Cons**:

| Aspect         | Description                                                              |
| -------------- | ------------------------------------------------------------------------ |
| Initial Setup  | Full download of 50K+ stations takes 1-20 minutes (network-dependent)    |
| Data Freshness | Station data is a snapshot; new/modified stations require manual refresh |
| Storage        | Local database uses approximately 40MB                                   |
| ️  Updates     | Not real-time; manual user trigger required                              |

#### Pre-built Database Files

Starting from **v0.96**, a pre-downloaded full radio database file will be attached to each release. Users can import it directly into the app, bypassing the time-consuming initial full sync. This is especially useful for:

- First-time users who want out-of-the-box experience
- Users with limited network connectivity or unstable server access
- Users switching devices who want to quickly restore their environment

**Usage**: Import the downloaded database file via **Settings → Local Database → Import Database** in the app.

#### Smart Local Station Display

The app intelligently prioritizes stations based on the user's device locale:

1. **System country first**: Filters stations by device country code (`Locale.getDefault().getCountry()`)
2. **Fallback to language + country**: If no stations match the country
3. **Language-only fallback**: If still no results
4. **Show all stations**: As final fallback

Refreshing always follows this priority logic.

#### Search

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

| Mode          | Description                                        |
| ------------- | -------------------------------------------------- |
| Name          | Alphabetical order                                 |
| Click Count   | By global click popularity from radio-browser.info |
| Votes         | By community vote count                            |
| Recent Change | By last modification timestamp                     |

Current sort mode displayed with ↑ (ascending) / ↓ (descending). Tapping the same mode toggles direction. Preferences are persisted.

#### Shuffle Play

Random play button on the station list toolbar. Picks a random station from the local database, retrying up to 10 times (10-second timeout each) to find a working station.

#### Scroll to Top

Floating action button appears when list is scrolled down. Tapping smoothly scrolls back to the top.

#### Track History

Original RadioDroid already had track history. This version optimizes the parsing logic for stream ICY metadata (track name and artist), improving matching and display accuracy. Also fetches supplementary metadata via LastFM API.

#### Station Icons

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

| Feature         | Description                                                                                                                              |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| Layered Cache   | Favorite station icons go into permanent cache (never deleted), others go into semi-permanent cache (7-day expiry)                       |
| Fast Display    | When opening a page, cached icons display immediately regardless of source, ensuring users see icons at first glance                     |
| Smart Upgrade   | Stations with fallback icons silently retry the primary icon every 4 hours; if no primary icon exists, attempt HD discovery              |
| Size Protection | A newly fetched icon smaller than the currently displayed one will not replace it, preventing degradation                                |
| Source Tracking | Each cached icon is marked with its source (primary/fallback). Primary icons are never retried; fallback icons are periodically upgraded |
| User Control    | Icons can be disabled in Settings to reduce cache size, ideal for users with limited storage                                             |

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

#### Player

Dual-engine playback:

- **ExoPlayer**: HLS, ICY (Shoutcast) supports stream metadata parsing
- **MediaPlayer**: Via StreamProxy for metadata capture
- Live ICY metadata extraction (song/artist) for track history
- LastFM API for supplementary track artwork

Also supports: external player, MPD protocol, Chromecast (Play variant only).

#### Exponential Volume Control

The player provides a **Volume Mapping Boost** toggle in **Settings → Player**, which switches between exponential volume control and default linear volume control.

- **On (default)**: Enables a two-stage volume mapping strategy. The maximum gain coefficient is first adjusted based on system volume, and then an exponential curve (symmetric around 50%) is applied to the in-app volume slider. This keeps the minimum volume low enough for quiet environments while making the maximum volume loud enough for noisy environments, with a natural perceived loudness curve across the whole range.
- **Off**: Restores the default linear volume control (gain = maxGain × volume / 100) without system volume compensation or exponential curve processing.

**System Volume Compensation** (active when toggled on, piecewise linear):

| System Volume Range | Gain Coefficient Range | Effect                                                |
| ------------------- | ---------------------- | ----------------------------------------------------- |
| < 35%               | 0.5× → 1.0×            | Reduced by half, quiet enough for silent environments |
| 35% ~ 65%           | 1.0×                   | Normal                                                |
| > 65%               | 1.0× → 2.0×            | Doubled, easier to hear in noisy environments         |

**In-App Volume Curve** (active when toggled on, symmetric exponential curve centered at 50%):

| App Volume | Output Gain   |
| ---------- | ------------- |
| 0          | Mute          |
| 25%        | maxGain × 0.5 |
| 50%        | maxGain × 1.0 |
| 100%       | maxGain × 2.0 |

Lower volumes fall below the linear curve (minimum halved), while higher volumes rise above it (maximum doubled), maintaining smooth transitions throughout.

#### MPD (Music Player Daemon) Support

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

| Item                 | Description                                                                |
| -------------------- | -------------------------------------------------------------------------- |
| Protocol             | Native MPD text protocol (not HTTP), direct TCP Socket connection          |
| Authentication       | Supports optional MPD password authentication                              |
| Connection           | App establishes direct TCP connection to MPD server, no intermediate proxy |
| Supported Operations | Play, Pause, Resume, Stop, Volume Control                                  |
| Multi-Server         | Save and switch between multiple MPD server configurations                 |
| Network              | LAN recommended; public access requires firewall rule for MPD port         |

**Notes**:

- MPD is an existing feature from the original RadioDroid; this fork has not modified or tested it, documentation is provided for reference only
- Network connectivity between phone and MPD server is required (same WiFi or VPN)
- Before first use, verify MPD is reachable from your phone (test with `telnet <IP> 6600` in terminal)
- If connection fails, check: MPD service running?, correct port?, firewall rules?, password match?

#### Proxy Support

HTTP and SOCKS5 proxy with username/password authentication. Settings serialized via Gson. Uses `proxyAuthenticator` (fixed from the original's incorrect `authenticator`).

#### Equalizer

Two sets of presets available. One uses the Android system's built-in equalizer presets, whose names and sound profiles may vary across device manufacturers; the other is built into the app, featuring "Vocal" (optimized for news, talk shows, podcasts) and "Music" (general-purpose tuning for music stations).

Also supports per-station equalizer customization: tap the equalizer button in station details to configure equalizer parameters for individual stations, enabling automatic switching to different sound profiles when switching stations.

#### Per-Station Buffer Strategy

The station details page provides a **Buffer Strategy** configuration button, allowing you to set a specific playback buffering strategy for each individual station:

| Strategy        | Description                                                         | Best For                       |
| --------------- | ------------------------------------------------------------------- | ------------------------------ |
| Light Buffer    | Plays after 2.5s buffering, low memory usage, low latency           | Stable networks, fast playback |
| Enhanced Buffer | Plays after 10s buffering, absorbs network fluctuations             | Occasionally unstable networks |
| Extreme Buffer  | Plays after 30s buffering, maximum resilience against interruptions | Moderately unstable networks   |

**How to Use**: Open station details, tap the "Buffer Strategy" button, and select the strategy that best fits the station. Settings are saved automatically and take effect immediately (if the station is currently playing, playback restarts with the new strategy).

**Why per-station?**: Different radio stations have vastly different stream server quality — some are rock-solid and benefit from light buffering for quick response, while others experience frequent interruptions and need longer buffers to play smoothly. Setting a strategy per station gives you the best balance between playback speed and stability.

**Note**: Longer buffer times may cause the track history to get out of sync with what you're actually hearing. This is because the track history shows the current song from the radio stream, while your player — due to the longer buffer — is still playing content that entered the queue earlier. For example, you might still be hearing Song A, but the track history already shows Song B's title. The longer the buffer, the larger this gap becomes. Light buffer (2.5s) essentially avoids this issue.

#### ️ Multi-Language UI

Language selector in settings: System, Chinese, English, Russian, Spanish, German, French, Italian, Greek (8 languages in total). Loaded dynamically in `ActivityMain.onCreate()` via `initAppLanguage()`. All new and modified UI code has full multi-language support.

#### Dark Theme

Light/dark theme toggle in settings. Fixed incorrect colors on certain UI elements in dark mode. Text colors automatically adjust per theme.

#### ️ Other Features

- **Favorites**: Add/remove with undo snackbar, swipe-to-delete, M3U import/export
- **History**: Played station list with M3U export, one-tap clear
- **Sleep Timer**: SeekBar dialog, auto-stops playback, saves default
- **Alarm**: Schedule a station to play at a specified time. Alarms are one-time by default; enable the "repeat" toggle in the alarm editor for daily recurrence
- **Recording**: Record live radio streams to audio files
- **Station Detail Expansion**: Website visit, share, alarm, desktop shortcut creation
- **Trend Icons**: Click trend indicators (rising/falling/flat)
- **Country Flags**: Flag icons per station in list view
- **Android TV**: Auto-detect TV devices, channel management
- **Network Type Indicator**: Shows Wi-Fi or mobile data icon during playback for at-a-glance network awareness

---

## Changelog

> 完整的历史记录已迁移至 [CHANGELOG.md](CHANGELOG.md)。

---

<p align="center">  
  <sub>Built with ❤️ based on <a href="https://github.com/segler-alex/RadioDroid">segler-alex/RadioDroid</a></sub>  
</p>
