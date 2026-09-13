# qBittorrent Mobile · 非官方 Android 客户端

面向手机的 BitTorrent 下载客户端：原生 Android 界面参考 qBittorrent 桌面端和 WebUI，传输层使用 **libtorrent4j / libtorrent**，支持磁力、种子文件、文件选择、限速及后台下载。

**当前源码版本：0.3.8 · Android API 26+ · Java 17 · GPL-3.0-or-later**

> 本项目不是 qBittorrent 官方 Android 版本，未获得官方背书，也不是把官方 Qt/C++ 客户端整体编译到 Android。官方源码作为独立子模块供行为对照，APK 的应用层是本仓库的 Java 实现。当前处于实验性测试阶段。

## 使用前必读

- **HTTPS Tracker 当前不验证服务器证书。** 这是原生 Android 库证书适配尚未解决的安全债务：连接加密不等于能防止中间人攻击，私有 Tracker 的 passkey 可能面临风险。不要把当前版本用于敏感 PT 账户或不可信网络，直到证书验证问题解决。
- 客户端设置了 qBittorrent 风格的 User-Agent 和 peer-id，但这**不代表官方身份或所有 PT 站兼容**。请遵守站点客户端白名单和使用规则，不要以修改身份来绕过限制。
- 个别设备切回前台后的卡顿/进程重启仍在排查。已加入缓存刷新和日志诊断，尚不能保证所有设备长期后台稳定。
- 默认下载到应用专属目录，卸载可能同时移除下载数据。正式使用前请备份重要文件和任务信息。

## 功能

| 类别 | 当前实现 |
| --- | --- |
| 新增下载 | magnet、系统选择 `.torrent` 文件、HTTP/HTTPS 种子直链 |
| 添加窗口 | 等待元数据、显示名称/大小/文件、默认全选、全选/全不选、按文件选择、单任务上下行限速 |
| 任务管理 | 暂停/继续、固定删除按钮、可选择删除下载数据、强制校验、立即重新汇报 Tracker |
| 列表与详情 | 状态筛选、进度、上下行速度、ETA、seeds/peers、文件进度、Tracker 响应和错误 |
| 恢复 | 持久化手动暂停状态、原生 fast-resume 检查点、DHT 会话状态 |
| 存储 | 应用专属默认目录、实体共享存储目录选择；变更下载目录会迁移现有任务 |
| 网络 | 全局与单任务限速、监听端口、DHT/LSD/UPnP/NAT-PMP、可选 SOCKS5 |
| 后台 | Android dataSync 前台服务与常驻通知 |
| 刷新 | 每 1 秒请求异步状态/统计，列表、详情和通知同步采用 1 秒周期；按原生采样时间计算速度；文件详情独立查询；事件合并和离页停止渲染 |
| 日志 | 可选日志目录、UTF-8 文本、轮换、常见敏感字段过滤、进程退出诊断 |

这是桌面功能的移动端子集，不是 WebUI 的完整替代。暂无完整 RSS、自动化、标签/分类及桌面队列规则等功能等价承诺。

## 获取源码

```sh
git clone --recurse-submodules https://github.com/mikulo/qbittorrent-mobile.git
cd qbittorrent-mobile
```

若已经普通克隆，可补充：

```sh
git submodule update --init upstream-qbittorrent
```

只构建 Android App 不依赖上游子模块中的 C++ 代码，因此也可以只克隆主仓库后直接构建 `android-app`。GitHub 的源码 ZIP 不包含子模块内容。

```text
qbittorrent-mobile/
├── README.md                  项目说明
├── HANDOFF.md                 详细开发/AI 交接文档
├── LICENSE                    Android 工程 GPLv3 许可文本
├── upstream-qbittorrent/       官方参考源码（固定提交的 Git 子模块）
└── android-app/
    ├── app/src/main/          Java 应用与 Android 资源
    ├── app/src/androidTest/   界面、刷新与日志回归测试
    ├── gradle/                Gradle Wrapper
    ├── docs/QA.md             历史验证记录与测试边界
    ├── README.md              Android 工程细节
    └── COPYING                Android 工程许可文本
```

官方参考提交固定为 `55ded55c696cd149e31faafa6ac65f4811321e0b`。本仓库不重复导入官方 Git 历史，也不修改其许可证。

私有种子、磁力输入文件、认证直链、运行日志、签名密钥、本机配置、缓存和历史 APK **不随公开源码提交**。本次公开的是源码，未附预编译 APK；请自行构建，勿从不明来源安装声称属于本项目的安装包。

## 构建

需要 JDK 17、Android SDK Platform 34、Build Tools 34.0.0；使用仓库自带 Gradle Wrapper（8.9）和 Android Gradle Plugin（8.7.3）。

### Windows / PowerShell

先把 `JAVA_HOME` 配置为本机 JDK 17 路径，把 `ANDROID_HOME` 配置为 Android SDK 路径，再从仓库根目录执行：

```powershell
Set-Location android-app
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

### Linux / macOS

配置相同版本的 JDK/SDK 后：

```sh
cd android-app
./gradlew :app:assembleDebug :app:lintDebug
```

当前已有构建验证来自 Windows，其它宿主系统的命令是标准 Wrapper 使用方式，尚未完成独立回归。

输出：`android-app/app/build/outputs/apk/debug/app-debug.apk`。

- 包名：`org.qbittorrent.mobile`。
- 版本：`0.3.8`，versionCode `13`。
- ABI：`arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86`。
- minSdk 26，compileSdk/targetSdk 34；不代表全部 API/厂商设备已经测试。
- 使用 Maven 预编译 `libtorrent4j:2.1.0-39` 及对应四个 ABI 库，无需构建官方 Qt 工程。
- Debug 包使用本机调试证书。不同机器构建的签名可能不同，无法直接覆盖安装；不要为了更换签名而贸然卸载有数据的应用。
- Release 签名尚未配置。公开分发正式包前需保管签名密钥、核对第三方许可和完成安全回归。

Windows 如遇 `Unable to establish loopback connection`，可为 JDK 配置短的 `jdk.net.unixdomain.tmpdir`，详见 [交接文档的构建章节](HANDOFF.md#4-开发环境与可复现操作)。不要把下载代理、真实密码或个人 SDK 路径提交到仓库。

## 下载目录与权限

默认位置通常是：

```text
/storage/emulated/0/Android/data/org.qbittorrent.mobile/files/Download
```

默认应用专属目录不需要广泛存储权限。自定义目录必须能解析为真实文件路径；原生 libtorrent 不能直接写入任意 `content://` 文档流，云盘/虚拟文档目录不支持。

Android 11+ 自定义共享目录需要按应用引导授予“所有文件访问”权限。**更改下载目录会移动已有任务的数据，不仅影响新增任务。** 移动前检查剩余空间并备份重要文件。

通知权限、系统前台服务和厂商电量策略都会影响使用体验；允许后台运行不等于系统永远不会回收进程。fast-resume 可减少不必要的全量校验，但异常退出或文件变更后仍可能需要一致性检查。

## PT、Tracker 与网络身份

当前兼容身份固定为：

```text
User-Agent: qBittorrent/5.2.3
peer-id prefix: -qB5230-
```

这不是 APK 版本，也不是当前官方最新版本声明。TCP/uTP、元数据扩展等由 libtorrent 处理；Android 任务管理逻辑是独立实现。

- `.torrent` 使用完整 metainfo 加载，保留 Tracker 与层级等顶层字段。
- 私有任务不能通过移除 `private` 标记或添加公共 Tracker 来修复，应检查真实 Tracker 错误。
- 如果 Tracker 返回 `port 6881 is blacklisted`，请按站点规则在设置中选择允许的监听端口；当前代码默认仍是 6881。
- SOCKS5 的 `127.0.0.1` 指运行 App 的设备自己。电脑上的代理不能在真机上直接用电脑的环回地址访问。
- HTTPS Tracker 证书验证的已知问题见本文开头，不因下载成功而视为已解决。

## 日志与故障反馈

在 **设置 → 运行诊断** 中可选择日志目录或恢复默认。

- 默认是应用外部文件目录下的 `logs`；自定义目录下新建 `qBittorrent-Mobile-Logs`。
- `qbm-current.txt` 为当前日志；`qbm-1.txt` 至 `qbm-4.txt` 为历史轮换，每文件约 2 MiB，当前目录约 10 MiB。
- 更换目录不会搬迁或删除旧目录中的日志。
- 记录启动/生命周期、服务、任务操作、Tracker 响应、检查点、异常；每约 10 秒记录连接数、速度、内存及状态采样耗时。
- 日志采用独立有界队列，不在每次刷新时同步写盘。
- 常见链接、鉴权字段和路径会过滤，但脱敏不是绝对保证；上传前仍需人工检查。
- Android API 30+ 可读取历史退出原因；Java 日志不能替代 native tombstone 或完整 ANR trace。

报告问题时请提供版本、设备/Android 版本、复现步骤、发生时间、任务数量和经过脱敏的相关片段。**不要在公开 Issue 上传私有种子、原始磁力、Tracker passkey、Cookie、认证直链或未经检查的完整日志。**

## 测试与已知限制

0.3.8 按用户要求将列表、详情、通知、原生状态/统计请求及文件详情采样统一调整为 1 秒，保留 0.3.7 的异步采集修复。本版本仅生成 APK，未运行测试或 lint；历史测试结果不代表 0.3.8 已通过回归。

0.3.7 修复“UI 设置为 500ms，但真实数据数秒才更新”的采集问题：取消高频逐字段同步查询，改为原生状态/统计回调；合并任务增量更新，每种请求最多一份在途；快照不等待文件详情查询或恢复文件写盘。速度按实际字节增量和原生时间戳计算，进度使用包含部分区块的原生计数，不再累加 payload 估计完成量。

磁盘后端仍保留兼容的 POSIX 实现。本次试验中的默认 mmap 后端在 Android 14/17 模拟器共享存储触发 MediaProvider/FUSE 崩溃，已撤回该变更，不能据此宣称 mmap 已适配。实际刷新仍受原生引擎、磁盘、设备负载和显示精度影响。

原有 instrumentation 的 5 个测试方法分别覆盖：

- 1,000 文件的添加窗口、固定确认区、选择保留和限速传递。
- 事件洪峰合并、生命周期停止、缓存读取及一秒刷新容差（0.3.8 调整测试预期，未执行）。
- 日志目录、UTF-8、合成秘密脱敏、轮换和无关文件保护。

另新增 `TransferPipelineTest`，共计 6 个 instrumentation 测试方法：使用本机回环 BT 会话传输合成数据，并故意阻塞文件详情执行器，检查真实进度/统计更新、任务卡片文字变化、实际上传、暂停和删除。它需要专用模拟器，不使用用户私有输入。JVM `TransferRateTest` 的 4 项测试覆盖半秒字节增量、延迟/重复时间戳、计数器重置和在途请求上限。

```sh
cd android-app
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

这条命令只构建/静态检查，不自动执行设备测试。安装和测试需明确选择专用模拟器，完整命令见 [HANDOFF.md](HANDOFF.md#43-模拟器和自动化测试)。不要在保存真实任务的设备上运行会改动应用状态的测试。

历史结果见 [QA.md](android-app/docs/QA.md)。文中的历史私有测试输入没有公开提供，不要求贡献者重复联系私有 Tracker。

尚待完善：

- HTTPS Tracker 证书信任接入。
- 特定 vivo 设备回前台后卡顿/重启的日志闭环及长期资源测试。
- 已下载量包含 native 提供的部分区块，不等于所有数据均已通过 Piece 哈希验证；完成状态仍以原生引擎为准。GiB 两位小数显示的最小跳动约 10.24 MiB，数字不变不必然代表没有新数据。
- 添加任务异步提交结果、取消竞争、旋转/进程重建、存储失效等边界仍需更多回归。
- 真实键盘、大字体、超多文件详情与各厂商后台行为覆盖不完整。

## 参与开发

接手前请读 [详细交接文档](HANDOFF.md)，再阅读 [Android 工程说明](android-app/README.md) 和 [历史测试记录](android-app/docs/QA.md)。交接文档包含本地维护环境记录，路径和设备编号需要按你的专用测试环境调整。

修改时优先保持：用户暂停意图、下载数据安全、完整 metainfo、主线程不做原生全量查询、添加窗口确认按钮常驻、取消后可重加、日志有界及隐私过滤。提交应描述测试结果和未测范围，不要把模拟器通过当作所有设备已修复。

## 许可与致谢

Android 工程按 **GPL-3.0-or-later** 提供，许可文本见 [LICENSE](LICENSE) / [android-app/COPYING](android-app/COPYING)。官方 qBittorrent 子模块和各项依赖继续遵循它们各自的许可；根许可证不改变第三方代码的许可。

- [qBittorrent](https://github.com/qbittorrent/qBittorrent)：界面与行为参考。
- [libtorrent4j](https://github.com/aldenml/libtorrent4j)：Java 绑定及 Android 原生依赖。
- [libtorrent](https://github.com/arvidn/libtorrent)：BitTorrent 传输引擎。

本项目与上述项目不存在官方发行或背书关系。
