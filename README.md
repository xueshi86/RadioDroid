# RadioDroid

## 项目介绍

### 中文
RadioDroid是一款基于Android平台的收音机应用，使用[www.radio-browser.info](https://www.radio-browser.info)提供的API获取全球电台数据。

**项目由来**：本项目是从segler-alex/RadioDroid fork而来的自用魔改版。原版RadioDroid v0.86版存在几个影响使用的bug，且自2023年以来没有更新，因此进行了自主修改和优化，形成了当前版本。

**主要功能**：
- 全球电台浏览和搜索
- 本地数据库存储，提高应用响应速度
- 电台收藏和管理
- 睡眠定时器功能
- 均衡器设置
- 多语言支持（中文、英文、俄语等）
- 随机播放功能
- 暗色主题支持

### English
RadioDroid is an Android radio browser app that uses the [www.radio-browser.info](https://www.radio-browser.info) API to access global radio station data.

**Project Origin**：This project is a self-modified fork of segler-alex/RadioDroid. The original RadioDroid v0.86 had several bugs affecting usage, and it hasn't been updated since 2023. Therefore, we made independent modifications and optimizations to create the current version.

**Main Features**：
- Global radio station browsing and searching
- Local database storage for improved app response speed
- Radio station favorites and management
- Sleep timer functionality
- Equalizer settings
- Multi-language support (Chinese, English, Russian, etc.)
- Random play feature
- Dark theme support

## Changelog

v0.94版本：修复HTTP/SOCKS代理认证失败问题，修正OkHttp认证器调用错误(authenticator改为proxyAuthenticator)，新增SOCKS5代理认证支持和无限重试保护；修复StreamProxy元数据解析EOF检查缺失导致的流结束崩溃问题；修复StationSaveManager导出M3U时BufferedWriter资源泄漏问题；修复ActivityMain广播接收器重复注册导致的内存泄漏问题；修复历史记录列表subList视图引发的并发修改异常；新增临时数据库文件(.db/-wal/-shm/-journal)自动清理机制，防止磁盘空间浪费；修复WakeLock/WifiLock释放时缺少异常保护导致的潜在崩溃；修复FragmentSettings对话框显示时缺少Fragment生命周期检查的问题；优化数据库更新失败时的资源回收逻辑。

v0.93版本：增加随机播放功能，在电台界面右上角增加随机播放按钮，会从本地数据库随机选取一个电台播放，增加发现电台渠道和趣味性；优化搜索界面和搜索功能实现，优化了现有的搜索算法，支持部分匹配和近似匹配，和标签组合搜索，以便更快找到电台；修改了部分界面遗留的硬编码问题，目前基本没有中英文混杂的界面显示问题了；添加了俄语支持；修正了暗色主题下某些界面和字体颜色错误的问题；修正了均衡器和统计页面的显示问题。

v0.92版本：修改电台播放代码逻辑，优先使用本地电台地址，避免远程服务器依赖；降级了Kotlin版本，避免版本不兼容问题；更新一些过时的API，避免安卓版本升级后产生不兼容问题。

v0.91版本：应可能是唯一英文用户要求，将0.86版本以来所有新增代码部分中文硬编码改为中英文双语显示；修改本地电台显示逻辑， 手机系统国家电台>手机系统语言电台>全部电台；以为服务器数据库一直不变，结果它老换，修改服务器数据库地址硬编码
，改为DNS获取，解决由此引发的应用崩溃问题；在设置-外观目录下增加了界面语言选项；修改了应用关于页面内容；检查了关键代码中的数组越界、空指针等问题，减少应用崩溃可能。

v0.90版本：整合了国家，语言，标签，搜索界面，新设计了搜索功能；优化了更新本地库代码逻辑，并添加了多线程更新功能，现在下载时间大概为3-22分钟，缩短时间一半以上,搭配合适欧洲代理更新时间甚至小于1分钟；还修复了其他几个小bug。 

v0.89版本：修复了更新导入导出数据库的bug，修复了数据库状态显示bug，修复睡眠定时器失效bug，修复收藏导入导出bug，修复大播放器按钮图标不切换bug，修复曲目历史中文乱码和英文字段截取错误bug等常用功能的发现的问题。

v0.88版本：更新本地数据库时可以切换到后台玩别的了；优化了更新逻辑和提示；调整了数据库导出导入功能。

v0.87版本：  对程序进行了大改。首先因为服务器在欧洲，网络经常很慢或无法连接，很多操作都需要查询服务器导致app体验很差，于是添加了本地数据库功能，首次使用手动将所有服务器电台数据读取到本地，所有的电台查询显示播放都只从本地数据库里操作，速度提升很多；配套增加了服务器连接测试功能，网络无法连接或很慢时就不要更新本地数据库了；增加了本地数据库导入导出功能，换机或重装免除再次更新数据库；缺点：由于服务器API限制，5万多电台都更新到本地服务器速度很慢，大概需要10到60分钟(根据不同网络状况而定），不过只要更新后很长时间都不需要再更新，大家选择网络好又有空的时候再更新吧；整个功能只是初步跑通，或许存在很多小bug，本地数据库更新的逻辑应该还有优化的空间，后续再完善吧。

v0.86修改版：RadioDroid v0.86版有几个bug影响使用，23年以来没人更新了，只能自己改。修改了app不能中文搜索节目问题，修改部分英文搜索结果不显示问题。自用魔改版，上传在这里吧。
