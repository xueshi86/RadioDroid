# AMARadio 特性调研与 RadioDroid 借鉴建议

> 调研时间：2026-08-26
> 调研对象：[ounben/AMARadio](https://github.com/ounben/AMARadio)（活跃维护，最新 v1.26 / 2026-08-23）
> 对比基线：本项目 RadioDroid v1.06（fork segler-alex/RadioDroid v0.86 的深度魔改版）

---

## 一、两项目定位速览

| 维度 | AMARadio | 本项目 RadioDroid |
|------|----------|------------------|
| 技术栈 | 100% Kotlin + Jetpack Compose + Material 3 | Java 为主（少量 Kotlin）+ 传统 View |
| 媒体引擎 | Media3（ExoPlayer + CastPlayer） | ExoPlayer 2.18.2 + MediaPlayer 双引擎 |
| 架构 | MVVM + Activity 作用域 ViewModel | 传统 Activity/Fragment + Service |
| 数据库 | 双库分离：目录库 + 用户库（Room） | 单库 Room（含用户数据） |
| 图片加载 | Coil | Picasso |
| 版本区间 | minSdk 26 / targetSdk 37 | minSdk 16 / targetSdk 33 |
| 数据模式 | v1.10 起本地优先（70MB 预填充 + 每日同步） | 全量离线镜像（5 万+）+ 手动更新 |
| 特色功能 | **桌面 Widget**、Cast、Android Auto、无障碍 | 录音、闹钟、均衡器、代理、MPD、Android TV |
| 砍掉的功能 | 录音、闹钟、代理、LastFM 均被移除 | —（这些反而保留并做深了） |

**结论先行**：两者同源（都基于 radio-browser 生态），AMARadio 在**系统集成层**（Widget / Android Auto / Cast / 无障碍）和新格式支持（Ogg/Opus）上领先；本项目在**离线数据、音频打磨、功能完整度**上领先。AMARadio v1.10 之后也转向"本地优先数据库"，与本项目路线一致，方向上互相印证。

---

## 二、高价值借鉴建议（🟢 建议落地）

### 1. 播放点击上报（Click Counting）— 低成本，社区贡献

- **AMARadio 做法**：播放时异步上报点击到 radio-browser.info API（`click` 端点），异步不阻塞播放；**5 秒冷却期**防止重复上报；本地点击热度数据随之上涨，支持全球排名。
- **本项目现状**：本地库有 `ClickCount` 字段且支持按点击量排序（`getAllStationsByClickCount`），但**播放时不上报**——点击热度停留在数据库快照时点，无法回馈社区、本地排序数据也会逐渐失真。
- **改动建议**：在 `PlayerService`/`RadioPlayer` 播放成功回调处，异步 POST `https://de1.api.radio-browser.info/json/url/<uuid>`（或 click 端点），加 5s 冷却 + 失败静默；上报成功后本地 `ClickCount+1`。

### 2. Ogg Vorbis / Opus 流元数据支持 — 中成本，直接提升曲目显示

- **AMARadio 做法**（v1.26）：`MetadataHandler` 集中解析——动态提取 Vorbis 注释（`TITLE`/`ARTIST`）与 Media3 元数据；**支持 Ogg 链式流**（chain），连续播放时**无需重启流**即可更新曲目标题。
- **本项目现状**：曲目历史仅解析 ICY 元数据（Shoutcast）+ LastFM 兜底。大量使用 Ogg/Opus 的电台（欧洲小众台、播客流）曲目名/艺术家显示不出来。
- **改动建议**：在 `IcyDataSource`/`RadioDataSourceFactory` 旁新增 Ogg 注释解析器（Vorbis comment 结构简单，解析 `\x01` 长度前缀即可），挂到 `ExoPlayerWrapper` 的元数据回调链上；链式流先做"换链不重载"。

### 3. 增量数据库更新 — 中成本，解决全量更新痛点

- **AMARadio 做法**（v1.13）：改用 `json/stations/lastchange` 端点做**增量同步**（每次批量 1000 条），`StationUuid` 设为主键 + `REPLACE` 策略；启动 5 分钟后自动同步（距上次 >24h 才触发）。
- **本项目现状**：更新即全量重下 5 万+ 条（多线程并行虽快，但每次 1-20 分钟 + 流量消耗大）。
- **改动建议**：增加"增量更新"模式——记录本地库最大 `LastChangeTime`，只拉取该时间之后的变更（radio-browser 提供 `lastchange` 参数）；配合 `INSERT OR REPLACE` 与已有多线程分页框架，可复用现有下载管道。

### 4. 搜索加权评分（开头匹配 + 词边界优先）

- **AMARadio 做法**（v1.14）：过滤搜索用加权算法——**开头匹配 > 词边界匹配 > 模糊匹配**（如搜 "sch" 时 Sweden/Switzerland 优先于 Munich）。
- **本项目现状**：FTS4 + `LIKE %keyword%`，结果无序或按字母序，搜 "jazz" 可能先出 "Ultra Jazz" 而不是 "Jazz FM"。
- **改动建议**：查询结果上叠加排序权重——`name LIKE 'kw%'` 权重最高、词边界其次、包含匹配最低；FTS4 可改用 `prefix` 匹配加分。

### 5. 过滤下拉"包含"搜索 + 智能建议

- **AMARadio 做法**（v0.95/v1.15）：国家/语言/标签下拉框支持**输入即过滤**（"包含"逻辑）+ 按相关性排序；11,000+ 标签/语言元数据存本地 SQL，离线即时建议。
- **本项目现状**：高级搜索下拉是静态列表（从数据库提取全部项），上万标签直接列出难以查找。
- **改动建议**：下拉改为可输入过滤的 AutoCompleteTextView（本地 `LIKE` 查询，500ms 防抖已有可复用），命中项按前缀优先排序。

### 6. 镜像服务器级联容错（failover）

- **AMARadio 做法**（v0.99/v1.10）：请求失败自动级联 `官方 → 轮换 → 镜像（radiobrowser.ounben.com）`。
- **本项目现状**：更新时已有自动测速选最快服务器，但**失败重试机制**停留在单服务器级别。
- **改动建议**：将"测速选优"升级为"失败级联"——请求失败立即切换下一个候选（含 `all.api.radio-browser.info` DNS 轮换 + 自建镜像占位），并持久化最近成功服务器。

### 7. 动态电台占位符（无图标电台智能兜底）

- **AMARadio 做法**（v1.11）：无图标电台自动生成占位——从名称提取标识符（"WDR 2" → "2"）、按 UUID 哈希分配 7 种 Material 颜色，在手机 UI / 通知 / Android Auto 通用。
- **本项目现状**：无图标显示灰色占位符（Material 收音机图标），所有电台千篇一律。
- **改动建议**：复用现有图标缓存框架，在最终兜底前生成"首字符 + 颜色"占位（颜色按 UUID hash 稳定分配），实现成本极低，列表观感提升明显。

---

## 三、中价值借鉴建议（🟡 视精力评估）

### 8. 桌面 Widget（Jetpack Glance）— 最大功能差距

- **AMARadio 做法**（v1.19-1.23，多轮打磨）：
  - **4x1 紧凑型**（实时电台信息 + 播放控制）+ **4x3 播放器型**（含可滚动收藏/历史列表）
  - 推送模型同步：收藏/播放变更 <200ms 更新；Mutex 顺序更新防状态卡住
  - **防幽灵架构**：Widget 交互直接启动前台服务，规避 Android 15 Recents 黑屏幽灵任务（Oppo/Realme 高发）
  - 主题化预览（Widget 选择器显示真实深浅色预览）
- **本项目现状**：**完全没有桌面 Widget**（仅"创建桌面快捷方式"）。这是用户高频入口，也是两项目最大功能差距。
- **改动建议**：项目是 Java 传统 View 架构，Glance 是 Kotlin Compose API，但项目已含少量 Kotlin 文件（如 `StationPopupMenu.kt`），可混编引入。建议先做 **4x1 紧凑型**（电台名 + 曲目 + 播放/暂停/下一台），同步走现有 `PlayerService` 广播（`PLAYER_SERVICE_*` 事件已具备），暂不做 4x3 列表型以控制工作量。

### 9. Android Auto 稳定性细节

- **AMARadio 做法**（v1.17）：
  - **媒体锚点**：严格上报加载状态而非 IDLE，防止换台时车载会话断开
  - 媒体类型统一 `MUSIC(1)`（部分系统拒绝 `RADIO_STATION(21)`）
  - 编解码器交接（MP3↔AAC）加 250ms 宽限期防硬件错误
  - 车载历史智能限制 10 条（驾驶安全）
  - 元数据顺序：电台名第一行、曲目第二行
- **本项目现状**：有 `RadioDroidBrowserService` 支持 Android Auto 媒体浏览，但无上述稳定性处理。
- **改动建议**：优先做**媒体类型统一 + 元数据顺序调整**（几行代码）；媒体锚点需在 MediaSession 回调层加状态守卫。

### 10. 动态 UI 缩放（Compact → Extra Large）

- **AMARadio 做法**（v0.87）：设置中调整 UI 缩放，字体/按钮/图标随缩放联动，无障碍友好。
- **本项目现状**：无。
- **改动建议**：基于 `fontScale`/`density` 覆盖实现（Java 可实现，`updateConfiguration`），注意与现有 `FragmentSettings` 8 语言字符串同步。中低优先级。

### 11. 无障碍（TalkBack）优化

- **AMARadio 做法**（v0.99.6）：电台语义分组（名称/国家/标签分组朗读）、收藏与搜索状态"播报"图标、74 语言无障碍描述。
- **本项目现状**：基本未专门处理（自定义 View 多，TalkBack 读序混乱）。
- **改动建议**：低投入版——电台列表 item 加 `contentDescription` 组合（"电台名，国家，标签，收藏状态"），播放按钮状态变化加 `announceForAccessibility`。

### 12. 大缓冲 + 硬件资源持久化（防咔哒声）

- **AMARadio 做法**（v0.99）：缓冲放大到 50-100 秒 + `setPrioritizeTimeOverSizeThresholds`；临时缓冲/空闲期间**保持 MediaCodec/AudioTrack 活跃**，防"咔哒"声与静音间隙。
- **本项目现状**：按电台缓冲策略（2.5/10/30s）+ 大量爆音修复（会话代际、静音渐入等），思路侧重"切换瞬态"；AMARadio 侧重"流空闲期"。
- **改动建议**：可在"极限缓冲"档探索硬件保活；注意本项目 minSdk 16，Media3 硬件保持 API 需要版本适配，评估后再动。

### 13. 智能队列回退（通知/锁屏跳过按钮）

- **AMARadio 做法**（v1.24）：通过搜索/Widget 启动的电台，自动以历史/收藏列表作为**跳过（上一首/下一首）备用队列**。
- **本项目现状**：需确认播放器是否支持切换上一/下一台（若支持，当前队列来源单一）。
- **改动建议**：无队列来源时取历史记录逆序作为候选列表，实现简单、交互增值。

---

## 四、架构方向参考（🔴 长期演进，不建议短期动）

| 方向 | AMARadio 现状 | 本项目评估 |
|------|--------------|-----------|
| Media3 全面迁移（含 CastPlayer） | v0.97 完成，v1.25 上 Cast | 现 ExoPlayer 2.18.2 且低版本兼容大量定制，迁移风险高；**建议维持**，仅按需升级 ExoPlayer 补丁版 |
| 双数据库架构（目录库/用户库分离） | v1.18 | 本项目已有"数据库重建保留用户设置"机制（v1.00），问题已缓解；彻底分离是干净方案但重构量大 |
| Compose 全面迁移 | 100% Compose | minSdk 16 + Java 存量巨大，**不建议**；仅新功能模块可考虑 |
| 74 语言 | v0.99.5 起 | 本项目 8 语言已够用，翻译维护成本高，**不建议跟进** |
| In-App Review | v0.99.7 | Play 版可加（需 Play Services，Free 版跳过） |

---

## 五、本项目保持的优势（无需借鉴，守住即可）

- **离线全量数据库**：5 万+ 电台全量镜像 + FTS4 + 断点续传 + 预置数据库导入，比 AMARadio 的 70MB 预填充 + 每日增量更彻底（AMARadio 仍在追赶此路线）
- **功能完整度**：录音、闹钟（音量渐增等）、均衡器（含电台级）、代理、MPD、Android TV——这些 AMARadio 已**主动移除**
- **低版本兼容**：minSdk 16（Android 5.x）vs AMARadio minSdk 26；多年爆音/兼容修复积累是硬资产
- **音频打磨深度**：会话代际、指数音量映射、按台缓冲策略，均已超过 AMARadio 的通用处理

---

## 六、行动建议（按性价比排序）

| 优先级 | 事项 | 预估工作量 | 收益 |
|--------|------|-----------|------|
| 🟢 1 | 播放点击上报 + 5s 冷却 | 半天 | 回馈社区、热度数据保鲜 |
| 🟢 2 | 动态电台占位符（首字符+颜色） | 半天 | 列表/通知观感全面提升 |
| 🟢 3 | 搜索加权排序（前缀优先） | 1 天 | 搜索体验质变 |
| 🟢 4 | Ogg Vorbis/Opus 元数据 | 2-3 天 | 曲目历史覆盖更多电台 |
| 🟢 5 | 增量数据库更新（lastchange） | 3-5 天 | 更新耗时长痛解决 |
| 🟡 6 | 桌面 Widget 4x1（Glance） | 1-2 周 | 最大功能差距补齐 |
| 🟡 7 | 镜像级联 failover | 1 天 | 更新/上报更稳 |
| 🟡 8 | Android Auto 媒体类型+元数据顺序 | 半天 | 车载显示规范化 |

> 说明：工作量为相对估算（基于本项目代码风格与现有基础设施），实际以实施时为准。
