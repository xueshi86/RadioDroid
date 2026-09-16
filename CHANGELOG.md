# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

> 以下为 fork 后本项目发布的版本历史（原维护于 README 的 Changelog 章节，2026-08-26 整合迁入，README 现仅保留入口链接）。

## v1.08

*2026-09-16*

**电台搜索与多语言兼容**

- **修复**：综合搜索、分页搜索和多条件搜索加入 `state` 地域字段，解决搜索中文名或相关电台时漏检的问题
- **修复**：非 ASCII 查询改用转义后的 `LIKE` 子串搜索，避免 Android SQLite FTS4 对中文、西里尔字母、希腊字母及带重音拉丁字母分词不可靠导致无结果；纯 ASCII 字母、数字和空格查询继续使用 FTS，保留前缀检索性能
- **优化**：ASCII 综合快速搜索合并 FTS 结果与 `state LIKE` 结果，兼顾快速检索和地域字段召回；使用 `UNION` 避免旧版 SQLite 中 FTS `MATCH` 与普通字段条件混用的兼容问题
- **优化**：为搜索查询增加 LRU 结果缓存（容量 32，按访问序淘汰），同一查询复用同一 LiveData，数据库更新时由 Room 自动失效，避免逐键输入或重复请求时反复扫描数据库；确认逐键搜索入口（多条件搜索、搜索对话框）已具备输入防抖
- **优化**：国家、语言搜索改为前缀优先、包含兜底的排序方式，与名称搜索的加权排序保持一致
- **健壮性**：对 `LIKE` 查询中的 `%`、`_` 和反斜杠进行转义，防止用户输入被误解为 SQL 通配符
- **测试**：增加中文、英语、俄语、西班牙语、德语、法语、意大利语和希腊语代表性查询的搜索策略测试，并覆盖 FTS 清洗与 `LIKE` 转义

**简体中文资源整合**

- **变更**：合并 `values-zh` 与 `values-zh-rCN` 的简体中文资源，保留双方独有及较新的翻译、品牌文案、数组和格式转义，取消简体中文资源双轨

**构建与兼容性**

- **修复**：缩短 Chromecast 不可用状态的日志标签，解决 Play Debug 的 4 条 `LongLogTag` Lint 错误，兼容 Android 7.1 及以下的日志标签长度限制

**本地电台智能显示（修复随缘多国混合）**

- **修复**：本地分类的国家匹配增加**回退链**——优先取系统 `Locale` 国家代码，未设置国家（返回空串）时依次回退到 **SIM 卡国家**、**运营商网络注册国家**（`getSimCountryIso`/`getNetworkCountryIso`，无需权限），并新增两字母国名校验；国家代码确实缺失时跳过 `WHERE countrycode = ''` 的空查询、直接走语言降级，避免「Locale 无国家 → 国家查询恒空 → 静默降级到全球电台」导致的随机多国混合
- **修复**：系统语言降级改为**先映射再匹配**——将系统语言 ISO 码（如 `en`/`zh`）转换为 radio-browser 的 `language` 英文全名（`english`/`chinese`…，新增约 90 个 ISO 639-1 映射，含 Android 旧码 `iw`/`in`/`ji`），并按逗号分词做包含匹配（`LIKE` 命中 `english`/`german,english` 等任一写法），替代原「ISO 码 = 英文全名」精确等值查询；此前 `en` = `english,german` 恒不匹配导致语言降级失效而跳至全球电台
- **修复**：闹钟「无播放历史自动选台」的兜底链路（按国家→按语言→全部）改用与本地列表页一致的 `getSystemCountryCode`/`getRadioBrowserLanguageName` 与逗号分词 `LIKE` 查询，保证各入口选台口径一致
- **健壮性**：SQL 语言查询由精确等值改为分词包含匹配，其模式与 tags 既有分词查询保持一致，同时支持单语言 `english` 与多语言 `english,german` 两种库内格式

**版本更新**

- 版本号升级至 v1.08 (versionCode 117)

## v1.07

*2026-09-09*

**WebDAV 备份和恢复（新增）**

- **新增**：设置中新增「备份和恢复」子页，包含 WebDAV 配置、备份、恢复三个入口；配置页提供服务器地址、服务器目录、账号、密码四行输入与删除/保存按钮，服务器地址填写服务器根地址（如 `https://host/dav/`），服务器目录填写远端存放子目录（可留空表示根目录，如 `radiodroid`），备份文件即存放于 `服务器地址/目录/` 下，目录不存在时上传自动逐级创建（MKCOL）；已有配置密码留空表示沿用原密码
- **新增**：备份/恢复均支持三种模式 — 收藏电台（M3U）、离线数据库（SQLite）、两者；远端使用固定文件名 `favourites.m3u` 与 `radio_droid_database.db`，备份覆盖同名远端文件；收藏恢复为覆盖式（与 M3U 导入一致）；`两者` 模式下两项独立执行并分项报告结果
- **新增**：连通性状态灯 — 保存配置后与每次打开备份恢复页时自动检测（PROPFIND）；未配置或检测中显示灰色（检测中灰色/绿色交替闪烁），检测成功绿色，失败红色；删除配置恢复未配置状态，旧检测结果不会覆盖新配置状态
- **新增**：任务状态反馈 — 任务进行中时备份/恢复条目显示进行中提示，完成后弹窗展示收藏/数据库分项结果（成功/失败/等待确认）
- **新增**：上传时远端目录不存在会逐级自动创建（MKCOL）后重试；配置 URL 使用 HTTP 时在配置页显示明文传输风险提示
- **安全**：WebDAV 密码使用 Android Keystore RSA 加密存储，Keystore 不可用时拒绝保存明文；仅支持 Basic 认证，禁止自动跟随重定向；URL 校验拒绝非 HTTP/HTTPS、userinfo、query/fragment、路径穿越、控制字符与非法语法
- **安全**：数据库恢复采用「下载 → 校验（SQLite 文件头、PRAGMA integrity_check、必要表结构）→ 持久化待确认状态 → 前台确认 → 关闭 Room → 备份原库 → 替换 → 失败回滚」流程，后台任务不会绕过前台确认直接替换数据库；用户取消或界面暂不可用时保留待确认状态，稍后可继续处理
- **健壮性**：M3U 解析失败不修改本地收藏；数据库备份先关闭 Room 复制一致快照再上传；下载响应大小上限 64MB；网络错误自动重试（WorkManager，最多 5 次），认证/权限/数据错误直接失败；重复点击通过 WorkManager 唯一任务去重；`两者` 模式下收藏与数据库独立执行、独立反馈，一项失败不回滚另一项
- **修复**：恢复收藏时若服务器上的 `favourites.m3u` 为空（备份时本地收藏为空），此前会静默执行清空本地收藏并提示「恢复成功」，造成「恢复成功但列表为空」的误导；现改为明确报错「服务器上的收藏文件为空」，本地收藏保持不变，并新增该提示的 8 种语言文案
- **修复**：数据库备份在真机上始终失败并提示「本地数据库不可用」— 排查定位两个根因并修复：① `PRAGMA wal_checkpoint(TRUNCATE)` 返回结果集，原先用 `execSQL` 执行会抛 `SQLiteException`（Queries can be performed using query or rawQuery methods only），改用 `rawQuery` 执行并消费游标；② 备份前完整性校验（`PRAGMA integrity_check`）以只读模式打开临时库时，FTS4 虚表校验倒排索引需要写入临时校验页，报 `attempt to write a readonly database`，改用 `OPEN_READWRITE` 打开临时文件校验。修复后数据库备份/恢复全流程（备份、收藏备份/恢复、数据库恢复）验证通过
- **修复**：恢复本地数据库时弹窗顺序不对 — 此前工作完成后先弹「分项结果弹窗」（数据库显示「等待确认」），关闭后才弹「是否替换本地数据库」确认弹窗，再以 Toast 提示恢复成功；现改为需要前台确认时**先**弹「是否用备份文件替换本地数据库」确认弹窗，确认完成替换后**再**弹出分项结果弹窗（不再先弹「等待确认」结果弹窗、不再用 Toast 报告结果）
- **变更**：数据库替换确认后的分项结果如实反馈四种情形 — 用户点「确定」且替换成功显示「恢复成功」；替换过程中校验/数据类异常显示具体「恢复错误」；本地替换失败已回滚显示「恢复失败，已保留原数据库」（`webdav_restore_failed`）；用户点「取消」或返回时显示「已取消」（新增 `webdav_result_canceled` 文案，保留待确认状态以便稍后重新确认）。覆盖 8 种语言
- **变更**：WebDAV 数据库恢复的确认弹窗按钮由「恢复」改为「替换」（新增 `webdav_database_replace`），确认文案由「要用下载的数据库替换本地数据库吗？」改为「要用 WebDAV 服务器保存的数据库替换本地数据库吗？」（`webdav_database_restore_confirm`），更准确表述数据来源；并同步 zh-rCN / 英文两个附加语言文件保持一致
- **新增**：WebDAV 数据库恢复在后台替换数据库期间用 Toast 提示「数据库替换中，请稍等…」（新增 `webdav_database_restoring`），避免用户等待结果弹窗时误以为无响应
- **变更**：WebDAV 备份/恢复完成后的分项结果成功文案按操作区分 — 备份显示「备份成功」、恢复显示「恢复成功」，替换原先统一的「成功」文案（`webdav_result_success` 拆分为 `webdav_result_backup_success`/`webdav_result_restore_success`）；同步全 8 种语言并与全文一致
- **多语言**：全部新增界面、状态与提示文案支持中文、英文、俄语、西班牙语、德语、法语、意大利语、希腊语 8 种语言

**收藏夹与数据库导入导出健壮性修复**

- **修复**：非法、空、损坏或不兼容的收藏夹文件导入不再导致应用闪退；导入失败时显示错误提示，并保留已有收藏
- **修复**：收藏夹导入数据缺少名称、标签或 UUID 时的异常；无效条目会被安全跳过，重复电台按 UUID 去重
- **修复**：收藏夹保存失败或导入监听器异常时的状态不一致，避免误提示导入成功
- **修复**：非法、空、损坏或非 SQLite 数据库文件导入时的错误处理；导入前检查文件大小、SQLite 文件头、完整性和必要数据表
- **修复**：数据库导入替换失败可能导致主数据库被删除或截断的问题；改为临时文件验证、备份、替换和失败恢复，保留原数据库
- **修复**：导出主数据库时报「导出失败 / 导入的数据库无法正常访问，可能文件已损坏」— 根因为 Room 使用 WAL 模式，校验时 `SQLiteDatabase.openDatabase(OPEN_READONLY)` 后执行 `PRAGMA integrity_check`，FTS4 虚表校验需写入临时页，只读打开报「attempt to write a readonly database」被误判为损坏；现改用它为先 `PRAGMA wal_checkpoint(TRUNCATE)` 将 WAL 数据合并回主库文件（保证导出的 `.db` 完整自包含），校验改用 `OPEN_READWRITE`（同 WebDAV 备份恢复逻辑）
- **修复**：导出主数据库失败时的错误文案误用「导入…」措辞 — `validateDatabaseFile` 校验源库与导入文件共用，现按流程区分：导出用新增 `export_database_corrupted`（「主数据库无法正常访问，可能文件已损坏」）与 `export_database_empty`（「主数据库文件为空，无法导出」），导入仍用原有「导入…」文案；新增文案覆盖 8 种语言（中文/英语/俄语/西班牙语/德语/法语/意大利语/希腊语）
- **修复**：数据库导入导出过程中 WAL、SHM、journal 旁文件处理不完整，以及后台任务与界面生命周期变化导致的异常
- **修复**：更新任务取消时未持有锁仍释放锁并清理共享数据的问题
- **优化**：修复 Android 低版本 API、VectorDrawableCompat、RTL 属性和 MediaRouter RestrictedApi 相关 Lint 错误

**Issue #39 收藏夹电台下拉拖拽排序修复**

- **修复**：收藏夹中电台无法向下拖拽排序 — 根因为 `ItemTouchHelper` 回调未按规范实现：`onMove()` 返回 `true` 却未真正移动数据，且自定义覆盖了 `onMoved()` 而未调用父类默认实现，导致 `LinearLayoutManager.prepareForDrop()` 不执行，首项锚定（SO 27992427）使列表在 `notifyItemMoved` 后回弹，表现为「能向上拖、不能向下拖」
- **变更**：改为在 `onMove()` 中直接执行数据移动并返回 `true`，删除自定义 `onMoved()` 覆盖，让默认实现触发 `prepareForDrop()`，向上/向下拖拽排序均正常
- **测试**：启用收藏夹拖拽排序回归测试并新增「向下拖拽」用例，覆盖上拖、下拖、左滑、右滑等操作，校验列表项顺序、删除结果与数据源一致
- **修复**：上述修复后拖拽排序在两种场景下仍然失效，重新排查确认真实根因并修复：
  - **排序模式覆盖拖拽**：按名称/热度/投票/最近排序时，列表展示的是排序副本，拖拽只移动了底层数据，松手后刷新即被重新排序覆盖，视觉与数据「双双弹回」；且排序对话框缺少「无排序」出口，一旦选择排序便永远无法回到手动顺序。现任意排序状态下长按拖拽均可用：拖拽发生时自动将当前显示顺序固化为底层收藏数据并切换为「自定义顺序」，拖拽即时生效、结果持久保留，其余电台保持原排序相对顺序；排序对话框新增「自定义顺序（Custom Order）」选项用于主动切回手动顺序，「自定义顺序」不显示升降序箭头、重复点击不切换方向
  - **仅图标收藏夹模式长按冲突**：网格模式下长按会同时触发上下文菜单（PopupMenu）与拖拽，菜单接管触摸事件导致拖拽立即终止；现拖拽开始时抑制长按菜单并在拖拽结束后恢复，两种交互不再互相打断
  - **拖拽中数据与视图同步**：排序状态下固化显示顺序时改用引用赋值（非副本），使拖拽途中的每次移动同步作用于适配器显示列表；此前副本方案在长距离拖拽触发列表滚动时，滚动回拖过的区域会出现短暂顺序错位，松手全量刷新后才纠正；向上与向下拖拽共用同一回调路径（`onMove` 不区分方向，`Collections.rotate` 对两方向数学对称），修复后两方向拖拽全程显示一致
  - **下拉刷新与向下拖拽手势冲突（修复方案二次更正）**：收藏夹列表原被 `SwipeRefreshLayout` 包裹，长按电台后向下拖动时刷新容器先拦截手势触发列表刷新、拖拽被取消（向上拖拽正常）；此前尝试在拖拽开始时对 RecyclerView 调用 `requestDisallowInterceptTouchEvent(true)` 阻止父级拦截，但经源码验证该方案错误：`RecyclerView.requestDisallowInterceptTouchEvent(true)` 会遍历自身 `OnItemTouchListener` 并回调 `ItemTouchHelper.onRequestDisallowInterceptTouchEvent(true)`，而 ItemTouchHelper 收到该回调会立即执行 `select(null, ACTION_STATE_IDLE)` **取消当前拖拽**，导致拖拽刚开始即被自身取消，上拉、下拉全部失效。现改为从根上移除冲突源：收藏夹为本地数据无需下拉刷新，改用无 `SwipeRefreshLayout` 容器的专用布局 `fragment_stations_starred.xml`（仅含 RecyclerView 与回顶按钮），并移除上述两处 `requestDisallowInterceptTouchEvent` 调用，长按后上拉、下拉拖拽排序均恢复正常
  - **测试**：新增「排序状态下拖拽自动固化自定义顺序」仪器测试用例，校验排序生效、拖拽后列表与数据源顺序一致、其余电台保持排序相对顺序、排序模式自动切换为自定义顺序、自定义顺序下继续拖拽正常；同一用例覆盖排序状态向下拖拽、自定义顺序向下拖拽（0→2）与向上拖拽（2→0 跨两项），两方向行为一致

**录音错误提示多语言适配**

- **变更**：录音开始失败、写入失败、当前音频流不支持录音等错误提示，完整支持中文、英文、俄语、西班牙语、德语、法语、意大利语、希腊语 8 种语言

**Issue #35 反馈修复（自动增量更新默认值与文案、Android 5.x 均衡器）**

- **修复**：自动增量更新被意外自动开启 — 代码内默认值为开启（true），与设置界面声明的「默认关闭」（false）不一致，导致从未手动设置该开关的用户升级后仍会自动执行增量同步；现统一为默认关闭，行为与设置界面声明一致
- **修复**：恢复 Android 5.x 上对应用内均衡器的系统限制。实测实验性开关会在切换电台时造成短暂出声后静音或播放中断，因此移除该开关并继续禁用 Android 5.x 均衡器入口
- **变更**：重写「自动增量更新」开/关状态说明文案（8 种语言）— 原文案「距上次更新 24 小时后自动同步」未说明触发时机，易被误解为存在后台定时任务；现按实际代码逻辑改为「启动应用时，若距最后一次更新超过 24 小时，自动同步变更」，关闭态改为「不自动更新；点击上方“增量更新”手动同步」，明确指向设置页中的手动更新入口

**版本更新**

- 版本号升级至 v1.07 (versionCode 116)

## v1.06

*2026-08-25*

- **新增**：闹钟无播放历史时自动选台 — 首次安装未播放任何电台时，新建闹钟不再静默失败；自动从「本地电台」列表（尊重用户当前排序与无效电台过滤）选择第一个可播放电台作为闹钟电台，并提示用户自行更换；闹钟可正常保存与按时播放，提示文案支持全部 8 种语言
- **新增**：播放点击上报 radio-browser.info 社区（`json/url/<uuid>`）— 播放成功时异步上报，保持点击热度排名新鲜；5 秒冷却防刷、防双计（覆盖本地链接解析/闹钟等全部路径）、失败静默、本地 clickcount 即时 +1；「设置 → 互动」提供隐私开关（默认开，关闭后不发任何网络请求）
- **新增**：镜像服务器级联容错 — 最近成功服务器持久化并跨启动复用；请求失败自动级联 DNS 镜像与官方静态兜底（de1/de2/fi1/at1）；级联短超时 5s；持久化服务器 7 天自动重测
- **新增**：国家选项本地化与电台计数— 国家选项标注本地电台数量（如「中国 (3821)」），按 ISO 代码分组统计、同名变体自动合并；国名按系统 Locale 本地化显示（中文界面「中国/日本」而非 "China/Japan"），8 种语言自动支持，countries.json 英文表兜底
- **变更**：国家筛选按 `countrycode` 精确匹配（选项值与显示值解耦），对话框搜索框输「中国」或 "China" 均可命中；列表按本地化名排序（Collator 中文拼音序）

**Android 5.x 实验性均衡器开关**

- **新增**：设置中新增「Android 5 实验性均衡器」开关 — 默认关闭（保持 v1.05 的无爆音播放），手动开启后恢复 Android 5.x 内置均衡器（Equalizer/BassBoost）能力，兼顾无爆音与均衡器可用
- **修复**：开启实验开关后，均衡器仍保留音频会话复用、静音后附着、幂等保护机制，避免效果链挂载/卸载引发瞬态爆音
- **适配**：开关仅在 Android 5.x 设备显示；切换开关即时联动「使用内置均衡器」入口的置灰与说明文案
- **翻译**：开关标题与说明完整支持全部 8 种语言（中文、英文、俄语、西班牙语、德语、法语、意大利语、希腊语）

**数据库更新（增量端点重写 + 边界加固）**

- **新增**：增量数据库更新 — 基于 `json/stations/lastchange` 端点仅拉取变更电台（uuid 水位 + REPLACE 主库直写 + 断点续传）；设置页新增「增量更新」入口 +「自动增量更新」（默认关闭；开启后距上次更新 24h 静默执行，可限制仅 Wi-Fi）
- **修复**：90 秒看门狗导致"积压大+网络慢"每轮超时、永不收敛 — 放宽为 10 分钟兜底 + 600 页上限 + offset 未前进检测；水位仅整轮成功后推进，中断后幂等重放不丢数据
- **修复**：取消更新主线程 ANR 风险 — `cancelUpdate` 改为有界 tryLock(3s)
- **修复**：增量失败原因被覆盖丢失 — 现显示具体错误（网络不可用/超时等）；取消显示"已取消"而非"更新失败"
- **修复**：全量更新运行中触发增量抢锁失败，误清全量更新运行状态导致 UI 脱节
- **修复**：库空但增量水位残留的"假成功" — 清除水位并要求先执行全量更新
- **修复**：FTS 索引失步 — 全量/增量更新后自动重建 `radio_stations_fts`，新入库电台 FTS 搜索不到的问题
- **优化**：离线点击增量更新立即报错（原先无限期排队停在"准备中"）；进度对话框临时库探测移至后台线程避免锁阻塞；无变更时跳过 FTS 重建

**播放与搜索体验**

- **新增**：Ogg Vorbis/Opus 流元数据支持 — 接入 ExoPlayer `VorbisComment`（TITLE/ARTIST）解析，曲目名/艺术家/通知/曲目历史全面覆盖，同值去重防刷屏
- **新增**：搜索加权排序 — 前缀匹配 > 词边界匹配 > 包含匹配（LIKE 与 FTS 两通道统一），clickcount 次级排序；关键词 `%`/`_` 通配符转义
- **新增**：过滤下拉可搜索 — 国家/语言/标签下拉改为可输入过滤对话框（500ms 防抖 + 前缀优先 + 选中高亮）；标签拆分为单标签并按出现次数排序（热门优先）
- **新增**：过滤下拉「重置筛选」按钮 — 国家/语言/标签下拉选项单底部新增重置按钮，一键恢复「全部」选项，免去长列表手动滚动回顶部
- **修复**：标签下拉数据源错误 — 原返回 "rock,pop" 逗号组合串，改为拆分后的单标签列表

**无图标电台占位图（动态生成 + 关键词化）**

- **新增**：动态电台占位符 — 无图标电台显示「首字符 + UUID 稳定 7 色」占位图，列表/图标兜底/通知大图标三处一致，内存缓存零开销
- **修复**：无图标电台占位误导 — 原用应用图标 ic_launcher 充当电台占位，改为按电台生成的动态占位符
- **变更**：占位图文字由「首字符」升级为「关键词/首词」，改为提取名称关键词：跳过 FM/Radio/The 等噪声词与频率数字（"BBC Radio 1"→"BBC"、"100.3 The Sound"→"Sound"），中文剥离「广播/电台/频率/台」等通用后缀后取前 4 字保留区分度（"济南新闻广播"→"济南新闻" vs "济南交通广播"→"济南交通"）；三级降级链：关键词 → 首词 → ♪
- **优化**：字号按词长自适应收缩（可读下限内截断兜底），任何长度关键词都能放进列表图标与通知大图标；提取器 `StationWordExtractor` 为纯 Java 实现，附 13 个 JVM 单测
- **修复**：README 导航栏与标语块居中 — 修复 CRLF 伪空行截断 HTML 块导致的左对齐渲染
- **变更**：移除 README 中桌面快捷方式功能介绍（功能已移除）；同步占位图与国家下拉框新行为介绍

**版本更新**

- 版本号升级至 v1.06 (versionCode 115)

## v1.05

*2026-08-23*

**继续修复安卓 5.x 播放爆音问题**

- **修复**：Android 5.x 设备彻底禁用均衡器效果 — Equalizer/BassBoost 不再附着到播放会话，从根源消除效果链挂载/卸载引发的瞬间爆音
- **修复**：缓冲抖动期间不再反复收发音频效果会话广播 — 避免部分机型（如三星 SoundAlive）的系统音效随广播反复挂载/卸载产生全幅瞬态噪声；换台时正确补发旧会话的关闭广播
- **修复**：播放中缓冲抖动不再将音量硬性截断为 0 — 消除数字音频阶跃产生的"咔哒"声，仅在真正停止播放时静音
- **优化**：启动音量渐入由指数曲线改为线性增益渐入 — 消除起始瞬间的增益跳变，出声更平滑自然

**QuickLyric not found error 修复**

- **修复**：点击"查看歌词"时若未安装 QuickLyric（或其兼容应用）不再报错 — 改为弹窗询问，可选择前往 F-Droid 下载页安装

**版本更新**

- 版本号升级至 v1.05 (versionCode 114)

## v1.04

*2026-08-15*

**低版本 Android（5.x）播放兼容性修复**

- **修复**：多个电台"无法播放"（缓冲完成却静音不发声）— ExoPlayer 的 STATE_READY 回调在旧设备上可能延迟/丢失，新增轮询兜底在真正 READY 后补发 Playing 通知（不会提前渐入、不引入爆音）
- **优化**：低版本启动缓冲延迟缩短（2500ms → 1000ms），点击播放后更快出声

**低版本爆音修复（均衡器场景）**

- **修复**：Android 5.x 打开均衡器或播放瞬间爆音 — 低版本（API < 23）不再在正在播放的 audio session 上 attach Equalizer/BassBoost（改用临时 session 探测能力），消除 AudioFlinger 效果链瞬态爆音；设置保存后下次播放生效
- **修复**：BUFFERING 抖动时均衡器反复 attach/detach 引发的爆音 — 均衡器按 session 复用，仅停止（Idle）时释放

**收藏夹导入修复**

- **修复**：收藏夹跨版本导入失败 — M3U 导入改为纯本地流程（本地数据库查询 → M3U 内 URL 兜底），不再依赖联网查询 UUID，断网也能导入
- **修复**：M3U 导入显式指定 UTF-8 编码，兼容 Android 5.1.1 等旧设备导出的文件

**时间选择器界面重做**

- **重做**：时间选择器由系统 TimePickerDialog 改为自定义双滚轮对话框 — 小时/分钟两个可循环滚轮（00-23 / 00-59、两位补零、禁止键盘输入、长按连续滚动）
- **新增**：`CompactNumberPicker` 完全自绘滚轮 — 选中行放大加粗（44sp）、相邻行缩小半透明（26sp），突出当前选中值；支持拖动跟手、松手惯性吸附
- **美化**：对话框新增标题、圆角卡片容器、居中按钮区，布局与闹钟编辑页风格统一
- **适配**：滚轮数字、分割线、卡片背景、按钮文字颜色全部由主题属性控制，亮色/暗色主题下均显示正常

**闹钟功能增强**

- **修复**：闹钟起始音量允许 0% 导致渐增无效 — 起始音量下限改为 1%（应用永不将系统音量设为 0），旧数据加载时自动归一化到 [1,100]
- **修复**：一次性（非重复）闹钟错过触发后仍显示"已开启" — 记录实际触发时间，应用启动/重注册时检测到已错过则自动禁用，避免跨天补响
- **新增**：渐增参数实时校验 — 渐增开启时起始音量必须低于目标音量，否则禁用保存按钮并显示提示
- **新增**：系统媒体音量为 0 时保存闹钟弹窗警告（闹钟可能无声）
- **适配**：闹钟编辑对话框暗色主题下滑块、时间按钮颜色统一为白色，不再与深色背景混为一体

**其他修复**

- **修复**：录音播放完毕后再次点击播放无响应 — STATE_ENDED 状态下先 seek 回开头再播放
- **优化**：Chromecast 投屏按钮始终显示在工具栏（无投屏设备时置灰），受"显示投屏按钮"设置控制，避免按钮消失误导用户
- **优化**：电台列表 图标/列表 切换按钮改为"有空间才显示"，避免窄屏工具栏溢出

**版本更新**

- 版本号升级至 v1.04 (versionCode 113)

## v1.03

*2026-08-09*

- **新增**：录音按钮提示，帮助用户识别录音操作
- **修复**：低版本 Android 播放时的爆音问题
- **新增**：德语、法语、意大利语、希腊语界面语言支持

**闹钟播放时"扬声器零音量暂停"失效修复**

- **修复**：闹钟触发的播放中，将系统音量调至 0 不再暂停播放（v1.01 引入闹钟音量渐增时，`alarmVolumeOverride` 期间整个跳过了零音量暂停检查，渐增完成后仍不生效）
- **方案**：闹钟期间 app 永不将系统音量设为 0（起始音量强制 ≥ 1），因此任何 volume=0 必定是用户手动操作，零音量暂停在闹钟播放的任何阶段（含长渐增期间）均即时生效；暂停后系统音量自动恢复至闹钟响铃前水平

**闹钟音量渐增跳变修复**

- **修复**：渐增过程中音量按大步长突变而非平滑过渡 — 原步数上限仅 60 且步进间隔无上限（大音量范围设备每步跳 2-3 档，小音量范围设备每 2 秒才跳一步）
- **方案**：步数上限提升至 200（每步不超过 1 个系统档位），步进间隔限制在 200ms 以内，实现平滑线性渐增

**闹钟时间设置暗色主题显示修复**

- **修复**：暗色主题下闹钟时间设置对话框时间数字为深灰色几乎不可见，部分区域残留白色背景
- **方案**：`DialogTheme.Dark` 补全文字颜色（白色）与深色背景，与 `AlertTheme.Dark` 保持一致

## v1.02

*2026-07-31*

**首次安装默认设置调整**

- **优化**：外观 → 主题默认选项为「自动」，新安装用户首次启动即适配系统主题
- **优化**：启动行为 → 启动时默认「显示所有电台」
- **优化**：互动页「点击收藏」「自动收藏」「无效电台」「热度图标」默认不选中
- **优化**：播放器页「显示网络类型指示器」「扬声器零音量暂停」「手机耳机断开暂停」「音量映射增强」默认选中
- **说明**：以上均为首次安装时的默认值；用户若已修改设置，覆盖/升级安装不会覆盖用户设置

**Android 5.1 播放兼容性修复（Let's Encrypt 证书）**

- **新增**：`CompositeX509TrustManager` 组合式信任管理器 — 系统 CA 优先验证，失败后回退到内置的 ISRG Root X1
- **新增**：打包 ISRG Root X1（Let's Encrypt 根证书）到 `res/raw`，解决 Android 5.1 手机系统 TrustStore 不含该证书导致使用 Let's Encrypt 证书的电台（如 rautemusik、radiohost、zeno.fm 等）SSL 握手失败、静音无声音的问题
- **优化**：`RadioDroidApp.newHttpClient()` / `newHttpClientWithoutProxy()` 统一注入 ISRG Root X1；在有该证书的设备（Android 6+）上由系统直接验证，不影响原有行为

**播放器与均衡器爆音修复**

- **修复**：应用均衡器附着到音频会话前先静音应用层音量，消除 Android 5.1 等低版本上 DSP 管线重配置瞬间的爆音

**播放错误提示**

- **修复**：`ExoPlayerWrapper.onPlayerErrorChanged` 无条件停止并报告错误，不再因服务器返回错误码或 TLS 握手失败而静默停留在错误状态、无提示无声音

**语言资源补全**

- **新增**：清除图标缓存设置项的西班牙语、俄语翻译（此前显示英文）

**收藏导入 M3U 文件选择修复**

- **修复**：从播放列表导入收藏时，文件管理器中 `.m3u` 播放列表文件变灰不可选 — 原 `intent.setType("audio/x-mpegurl")` 按单一 MIME 过滤过窄，Android 各版本/各厂商文件管理器对 `.m3u` 的 MIME 映射不统一（`audio/x-mpegurl` / `application/octet-stream` / `application/vnd.apple.mpegurl` 等），导致文件被置灰无法选择
- **修复方案**：改为 `intent.setType("*/*")` 且不设置 `EXTRA_MIME_TYPES`，确保所有文件可选；文件有效性由 `LoadM3U` 解析阶段保证（无效行自动跳过）

**代码审查 — Critical（崩溃/数据丢失）**

- **修复**：`StationSaveManager.addMultiple` 空/Null 列表前置防御 — 误导入空 M3U 不再清空已有数据
- **修复**：`StationSaveManager.addMultiple` 导入电台未设置 `queue` 字段，播放切换时补 `station_new.queue`，避免 NPE
- **修复**：`PlayState` Parcelable 反序列化序号越界 — `ordinal < 0` 或越界时返回 `Idle`，避免损坏 Parcel 崩溃
- **修复**：`MediaPlayerWrapper` proxy 为 null 时 NPE — 增加 `proxy != null` 防御，`getExtension()` 返回 `"mp3"` 默认值
- **修复**：`PlaylistM3U.getBasePath` 无分隔符路径 `substring(0, -1)` 抛 `StringIndexOutOfBoundsException` — 分隔符为 -1 时返回空串

**Major（功能异常）**

- **修复**：`PlayerService.foundLiveStreamInfo` 子线程竞态 — 回调统一 `handler.post()` 主线程串行化，null 安全比较标题变化
- **修复**：`RadioPlayer.playState` 无 `volatile` 可见性 + `play()` 旧链接解析任务污染 — 加 `volatile` 并在 `play()` 开头 `cancelStationLinkRetrieval()`
- **修复**：`StreamProxy.isStopped` 无 `volatile` ，JIT 缓存导致代理线程退出延迟 — 改 `volatile`，`stop()` 后及时释放 socket 与带宽
- **修复**：`DatabaseUpdateWorker.cancelUpdate` 锁机制错误 — 改 `sLock.lock()` + try/finally unlock，取消失效问题
- **修复**：`RadioBrowserServerManager.constructEndpoint` HTTP 明文传输 — 改为 `https://` 强制加密
- **修复**：`PlayerService` 重复调用 `setMediaPlaybackState()` 及 `unregisterReceiver` 未注册抛异常 — 删除重复行、加 try-catch 保护
- **修复**：`ConnectivityChecker` 未实现 `onLost()/onUnavailable()` — 网络断开后 UI 网络类型图标卡旧状态
- **修复**：`NetworkCallback` 网络丢失未通知 UI

**Minor（健壮性/体验）**

- **修复**：`FragmentPlayerSmall.onDestroy` 用 `requireActivity()` — 改为 `getActivity()` + null 检查，避免 detach 后崩溃
- **修复**：`AlarmReceiver` `WifiLock.acquire()` 带超时参数编译错误 — 去除超时重载
- **修复**：values-zh-rCN `update_confirm_replace_message` 多占位符非位置格式 — 改为 `%1$d/%2$d`
- **修复**：`PlaylistM3U` 单行解析失败中断整个列表 — 增加 `catch (RuntimeException)` 跳过异常行
- **修复**：`ActivityMain` 广播接收器注册/反注册配对，避免泄漏
- **新增**：`FragmentSettings` 图标缓存清除功能及 es/ru/zh 翻译
- **修复**：`RadioDroidApp` 初始化异常保护与日志记录
- **修复**：`BootReceiver` 主线程耗时处理 — 改异步线程避免 ANR
- **修复**：`Utils` 缓存文件处理增强 — try-with-resources 与详细错误日志

**版本更新**

- 版本号升级至 v1.02 (versionCode 111)

## v1.01

*2026-07-27*

**网络类型指示器（替代 Wi-Fi 警告弹窗）**

- **新增**：迷你播放栏和完整播放界面显示 Wi-Fi 或移动数据图标，直观了解当前网络类型
- **新增**：`ic_network_wifi` / `ic_network_mobile` 矢量图标资源
- **重构**：移除计量连接警告对话框、提示音、自动暂停逻辑，改为非打断式信息展示
- **重构**：设置项"未使用 Wi-Fi 时警告"更名为"显示网络类型指示器"，语义从警告变为指示
- **新增**：`PLAYER_SERVICE_CONNECTION_TYPE_CHANGED` 广播，实时通知网络类型变化
- **新增**：`ConnectivityChecker.ConnectionType.NONE` 枚举值，标识无网络状态
- **修复**：`PauseReason` 反序列化增加序号越界保护，防止旧版本跨进程数据导致 `ArrayIndexOutOfBoundsException`

**闹钟音量渐增**

- **新增**：闹钟响铃时系统媒体音量从起始音量线性渐增至目标音量（默认 0%→50%，30 秒），不再突然全音量播放
- **新增**：闹钟编辑对话框 — 点击闹钟列表项可编辑时间、起始音量、目标音量、渐增时长
- **新增**：闹钟列表项显示渐增参数摘要（如"0% → 50% / 30 s"）
- **新增**：`PlayerService.setAlarmFade()` / `startAlarmVolumeOverride()` / `startSystemVolumeFade()` / `stopAlarmVolumeOverride()` 闹钟音量控制系统
- **新增**：闹钟结束后自动恢复响铃前的系统媒体音量
- **优化**：闹钟播放期间跳过应用层音量映射和零音量自动暂停，避免干扰渐增
- **优化**：闹钟播放期间不受短暂音频焦点丢失影响（不 duck、不暂停）
- **优化**：ExoPlayer / MediaPlayer 音频流统一使用 `STREAM_MUSIC`，覆盖扬声器/有线/蓝牙所有输出
- **优化**：`ExoPlayerWrapper` 新增 `volumeHandedOff` 标志，防止播放器内部逻辑重置 PlayerService 设定的音量

**睡眠定时器重定位**

- **重构**：睡眠定时器从"闹钟"设置页移至"播放器"设置页，不再依赖外部闹钟应用开关
- **重构**：字符串 key 从 `settings_alarm_sleep_timer` 重命名为 `settings_sleep_timer`
- **优化**：描述从"Stop playing after"改为"Stop current playback after"，更准确

**扬声器零音量暂停**

- **新增**：设置 → 播放器 → 扬声器零音量暂停开关 — 未连接耳机时，扬声器音量调至 0 自动暂停

**导入/导出兼容性修复（Android 4.x）**

- **修复**：Android 4.x (API 16-18) 设备上数据库导入/导出无法选择文件 — 低于 API 19 时回退到传统文件对话框（`OpenFileDialog` / `SaveFileDialog`）+ 运行时权限申请
- **新增**：权限被拒绝时显示提示

**收藏导入去重**

- **修复**：从 M3U 文件导入收藏时，M3U 内部相同 UUID 的电台不再产生重复条目

**播放器 Bug 修复**

- **修复**：`RadioPlayer` HTTP 客户端丢失 User-Agent 拦截器，导致部分流媒体服务器拒绝请求 — 改用 `getHttpClient().newBuilder()` 保留全局拦截器
- **修复**：HLS 流检测增强 — 增加 null 检查、`/hls/` 路径和 `.hls` 扩展名匹配、大小写不敏感
- **修复**：取消收藏后 Snackbar 被底部播放面板遮挡 — 锚定到播放面板上方
- **修复**：电台列表向右滑动背景边界错误 — 修正 `RecyclerItemSwipeHelper` bounds 计算
- **修复**：电台列表滑动方向运算符错误 — `LEFT + RIGHT`（算术加=8）改为 `LEFT | RIGHT`（位或=12）
- **修复**：全屏播放器取消收藏时 Snackbar 无锚点 — 传入实际 View 而非 null
- **修复**：`RadioDroidBrowserService` 内存泄漏 — `onDestroy()` 注销广播接收器
- **修复**：`AndroidManifest.xml` 恢复 `RadioDroidBrowserService` 声明（支持 Android Auto / 媒体浏览器）

**翻译与清理**

- **新增**：清除图标缓存功能的中英德繁翻译
- **新增**：权限被拒绝提示的多语言字符串
- **清理**：删除所有语言中不再使用的 `notify_metered_connection` 字符串资源
- **清理**：删除 `Utils.playAndWarnIfMetered()` / `MeteredWarningCallback` 及所有调用点

**版本更新**

- 版本号升级至 v1.01 (versionCode 110)

## v1.00

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

## v0.99

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

## v0.98

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

## v0.97

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

## v0.96

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

## v0.95

*2025-05*

- **修复**：Android 13+/15/16 上文件管理器无法打开 — `OpenDocument` 替换 `GetContent`，`CreateDocument` 替换直接文件写入，移除过时存储权限检查
- **修复**：数据库导入数据丢失风险 — 先复制到临时文件验证后再替换正式文件
- **修复**：`replaceMainFromTemp` 数据丢失风险 — 先读取临时数据库验证后再删除主库
- **修复**：`cancelUpdate` 中危险的 `SharedPreferences` 直接文件操作
- **修复**：数据库导入中无意义的 `Thread.sleep(1100ms)` 延迟
- **优化**：数据库更新进度写入 — `commit` 改为 `apply`，减少磁盘 I/O
- **优化**：批量插入大小从 1000 提升至 2000

## v0.94

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

## v0.93

*2025-04*

- **新增**：随机播放功能 — 电台界面 Toolbar 随机播放按钮，从本地数据库随机选取电台
- **优化**：搜索算法 — 支持部分匹配和近似模糊匹配，支持标签组合搜索
- **修复**：界面硬编码 — 基本消除中英文混杂的界面显示
- **新增**：俄语语言支持
- **修复**：暗色主题下部分界面和字体颜色错误
- **修复**：均衡器和统计页面显示问题

## v0.92

*2025-04*

- **优化**：电台播放逻辑 — 优先使用本地电台地址，降低远程服务器依赖
- **降级**：Kotlin 版本以解决兼容性问题
- **更新**：一批过时 API，适配新版 Android

## v0.91

*2025-03*

- **国际化**：所有新增代码中中文硬编码改为中英文双语显示
- **优化**：本地电台显示逻辑 — 手机系统国家电台 > 手机系统语言电台 > 全部电台
- **修复**：服务器数据库地址硬编码 — 改为 DNS 获取，解决服务器变更引发崩溃
- **新增**：设置 → 外观目录下界面语言选项
- **更新**：关于页面内容
- **修复**：关键代码中的数组越界、空指针等问题

## v0.90

*2025-03*

- **重构**：整合国家、语言、标签、搜索界面，全新设计高级搜索
- **优化**：本地数据库更新逻辑 — 多线程并行下载，耗时缩短 50%+
- 搭配欧洲代理时更新时间可缩短至 **1 分钟以内**
- **修复**：若干小 Bug

## v0.89

*2025-02*

- **修复**：数据库更新、导入、导出各类 Bug
- **修复**：数据库状态显示错误
- **修复**：睡眠定时器失效
- **修复**：收藏导入导出异常
- **修复**：大播放器按钮图标不切换
- **修复**：曲目历史中文乱码和英文字段截取错误

## v0.88

*2025-02*

- **新增**：更新本地数据库时可切换后台运行
- **优化**：更新逻辑和用户提示
- **调整**：数据库导出导入功能

## v0.87

*2025-01*

> **里程碑版本** — 架构级重构

- **核心重构**：引入本地离线数据库模式，所有电台操作基于 SQLite 本地数据库
- **新增**：服务器连接测试 — 网络不佳时提示不宜更新
- **新增**：本地数据库导入/导出 — 换机或重装免除再次下载
- ⚠️ 首次全量下载 5 万+ 电台约需 10-60 分钟（视网络）
- 功能初步跑通，后续持续完善

## v0.86 修改版

*2025-01*

- **修复**：App 无法中文搜索节目
- **修复**：部分英文搜索结果不显示
- RadioDroid v0.86 原版有多个影响使用的 Bug，自 2023 年无人维护，开启本分支


## [0.86] - 2023-09-28
### Added
- Auto stop support for auto start-play

### Changed
- Enabled android tv again
- Distribute package as AAB on play store from now on
- Sorting of entries from loaded files is now the same as the file

## [0.85] - 2023-09-27
### Fixed
- Building works again
- File dialog on android 13 uses system dialog and works now

### Added
- Translations: norwegian(nb), basque(eu)

### Changed
- Server fallback should work now even when the server return 502

## [0.84] - 2020-12-28
### Added
- Refreshable favorites and history lists
- Mark removed stations red, and broken stations yellow
- Translation updates
- Adaptive launcher icon
- Testing framework
- Stop button to MPD
- Very basic android TV support
- LastFM Api key changeable by user in settings menu

### Fixed
- Recording in android 10
- Correctly display audio players in list of external play
- Play audio warnings as music and not as alarm
- False negatives in hls stream detection

## [0.83] - 2020-04-15
### Changed
- "Remove from favorites" usability
- Track history with icons disabled (#774)

### Fixed
- Added fallback if dns resolve does not return anything
- Fix state updating of record button (#785)
- Show previously picked time when editing alarm's time (#784)
- Start recording after storage permissions are granted (#783)

## [0.82] - 2020-03-07
### Fixed
- Audio focus on pause
- Sudden stop of playback after it beeing resumed after connection loss

### Changed
- Swap station name and track name in full screen player

## [0.81] - 2020-03-03
### Added
- Export history to m3u

### Fixed
- Make sure all.api.radio-browser.info is not used directly
- Play time in fullscreen player
- Some crashes
- Stop notification relaunch after stop
- External player interactions
- Autostart of notification

### Changed
- Library: material 1.2.0-alpha05
- Library: gson 2.8.6
- Library: cast 18.1.0
- Library: lifecycle 2.2.0
- Library: searchpreference 2.0.0

## [0.80] - 2020-02-10
### Added
- Fullscreen player
- Password support for MPD
- Show warning for use of metered connections
- Flag symbols in countries tab
- History of the played tracks
- Stations search now shows results as you type
- Option to resume on wired or bluetooth device reconnection

### Fixed
- Connection issues with android 4 for most people

### Changed
- Library: OKhttp 3.12.8
- Library: Cast 18.0.0
- Use countrycode field from API instead of country field
- Reworked user interface for MPD which now allows explicit management of several servers
- Improved user interface of recordings

### Removed
- Server selection from settings. There is an automatic fallback now.
- Old main server is not used anymore (www.radio-browser.info/webservice)

