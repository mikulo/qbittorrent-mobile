# qBittorrent Mobile (unofficial)

这是一个独立的 Android 客户端工程，界面参考 qBittorrent 桌面端和 WebUI，传输层使用
`libtorrent4j 2.1.0-39` 提供的 libtorrent 2.1 原生库。

> 本项目不是 qBittorrent 官方 Android 版本，也没有得到 qBittorrent 项目背书。应用名和界面仅用于说明兼容目标。

接手开发请先阅读 [项目交接文档](../HANDOFF.md)：包含源码导航、构建与测试命令、历史故障、日志机制、未解决风险及测试隐私约束。

## 目录关系

- `../upstream-qbittorrent/`：从 <https://github.com/qbittorrent/qBittorrent> 检出的官方源码，保持独立 Git 仓库。
- `../android-app/`：Android 工程与构建产物，不写入官方源码树。
- `artifacts/`：可直接安装的 APK。

## 已实现功能

- 原生手机布局：状态筛选、任务卡片、总上下行速度、空状态、深浅色主题。
- 添加 magnet 链接、HTTP/HTTPS `.torrent` 直链；也可通过 Android 系统文件选择器导入 `.torrent`。
- 新增任务先展示名称、总大小、文件数量、私有属性和保存位置；文件默认全选，可按文件取消下载并设置该任务的上下行限速。
- `.torrent` 使用完整 metainfo 加载路径，保留私有种子的 Tracker、Tracker 层级、名称和 Web Seed 等顶层字段。
- 前台下载服务，应用退到后台后继续传输。
- 下载、上传、做种、暂停/继续、删除任务/数据、强制校验、立即重新汇报 Tracker。
- 任务卡片固定显示删除按钮，不再使用左右滑删除；详情页可查看并修改单任务上下行限速。
- 任务详情：进度、速度、ETA、连接数、累计上传、保存路径、info-hash、文件进度、Tracker 列表。
- Tracker 详情显示正在汇报、返回 Peer 数、警告和真实错误原因。
- 可选 SOCKS5 代理，支持经代理解析域名，并覆盖 Tracker 与可选的 Peer 数据连接。
- 设置：全局上下行限速、监听端口、DHT、LSD、UPnP、NAT-PMP。
- 自动保存 magnet/种子来源和暂停状态；暂停任务会清除 auto-managed 标志，运行期间或进程重启后都不会自行恢复。
- 使用 libtorrent 原生 fast-resume 保存已验证 Piece、未完成区块、文件状态和任务参数；每 15 秒及暂停、继续、限速变化、任务完成和服务退出时创建检查点，重启时无需全量校验未变更的数据。
- 可通过系统目录选择器更改下载目录；已有任务会迁移到新目录，也可恢复应用专属默认目录。
- minSdk 26、compile/targetSdk 34，打包 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86`；这不代表所有 Android 版本和设备均已验证。

## 界面预览

本地 `docs/screenshots/` 为早期版本截图，未经公开隐私复核，不随源码提交。最新添加窗口、固定删除按钮和日志设置以当前构建为准。

## PT 与协议身份

应用按 qBittorrent 官方 `SessionImpl::initializeNativeSession()` 的做法配置 libtorrent，而不是实现另一套 BitTorrent 协议：

- peer fingerprint：`-qB5230-`
- HTTP User-Agent：`qBittorrent/5.2.3`
- anonymous mode：关闭，避免清空 peer-id/User-Agent
- µTP、TCP、DHT、LSD、PeX、智能封禁和元数据扩展由 libtorrent 提供
- 入站/出站协议加密保持“允许加密”的桌面默认行为

5.2.3 是本工程当前固定的兼容身份，不随上游发布自动更新，也不代表当前官方最新版本。PT 站仍可能校验 libtorrent 行为、扩展握手、平台策略或明确禁止非官方客户端；因此上述设置只能提高协议兼容性，不能承诺绕过每个站点的客户端白名单。使用前应确认站点规则。

## Android 存储策略

下载默认保存在：

```text
/storage/emulated/0/Android/data/org.qbittorrent.mobile/files/Download
```

这是 Android 推荐的应用专属外部目录，不需要额外存储权限。卸载应用时 Android 可能删除该目录；需要长期保留的数据请先备份。

用户也可以在设置中选择内部存储或实体存储卡里的自定义目录。由于 libtorrent 原生层只能使用真实文件路径、不能直接写入 SAF 的 `content://` 流，Android 11+ 会要求用户显式授予本应用“所有文件访问”权限；应用不会自行跳过系统授权页。云盘等仅提供虚拟文档的目录不受支持。

## 构建

需要 JDK 17、Android SDK 34 和 Build Tools 34：

```powershell
cd D:\codex\qbittorrent\android-app
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:ANDROID_HOME = 'C:\Android\Sdk'
.\gradlew.bat :app:assembleDebug
```

若 Java 在 Windows 环境报告 `Unable to establish loopback connection`，可创建短路径并增加 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Temp\JavaSockets`。代理配置不写入工程；需要时可在本机 Gradle 配置或命令环境中设置。

Debug APK 使用 Android 调试证书签名，适合直接安装测试。正式分发前应创建并妥善保管自己的 release keystore，然后在本机配置 release signing。

本地当前构建产物：`artifacts/qBittorrent-Mobile-0.3.8-universal-debug.apk`。公开仓库不包含历史 APK 或私有测试输入；请按上述步骤自行构建。

0.3.8（versionCode 13）按用户要求将列表、详情、通知、异步状态/统计请求和文件详情采样统一改为 1 秒，保留异步采集实现。本版本只构建 APK，不运行测试或 lint。

0.3.7 将高频同步原生查询改为异步状态/统计回调，每种请求最多一份在途；保留未发生变化的任务，删除时清除缓存。文件详情运行在独立执行器，恢复文件写盘也移出原生 alert 回调。总速度和任务速度按原生采样时间与累计字节差计算，不再额外采用 SessionStats 平滑速率；进度采用原生准确计数（包含部分区块），移除 payload 累加估计。文件详情的已下载量格式统一为 KiB 整数、MiB 一位、GiB 两位。

新日志字段：`stateReplyMs/statsReplyMs` 为请求到处理回调的耗时；`stateAgeMs/statsAgeMs` 为最近回调距今时间；`statePendingMs/statsPendingMs` 为未完成请求等待时间；`stateConvertMs` 为快照转换耗时；`detailsQueryMs` 为独立详情查询耗时；`stateFrames/statsFrames` 为累计回调数。它们替代旧版整轮 `queryMs`，用于区分原生响应慢、数据处理慢和详情查询慢。磁盘后端保留 POSIX 兼容模式，默认 mmap 的 Android FUSE 崩溃试验没有纳入成品。

0.3.6 将任务列表、详情、通知以及后台状态采样周期统一为 500ms；仍合并事件刷新并在离开页面时停止渲染。设置 → 运行诊断中可选择日志目录或恢复默认。默认目录是应用外部文件目录下的 `logs`，自定义目录下创建 `qBittorrent-Mobile-Logs` 子目录；更换目录不移动/删除原日志。

日志为 UTF-8 文本：`qbm-current.txt` 是当前文件，`qbm-1.txt` 至 `qbm-4.txt` 是轮换历史，每文件约 2 MiB，总计约 10 MiB。记录启动、前后台生命周期、服务、任务操作、Tracker 响应/错误、恢复检查点及异常堆栈，每 10 秒记录速度、连接数、Java/原生内存和采样耗时。写盘使用独立有界队列；队列溢出会计数，目录失效时回退默认目录并在设置中提示。链接、passkey/token 和鉴权字段会过滤。Java 未捕获异常尽力写入后交还系统处理；原生崩溃/系统回收的退出原因在下次启动读取，不替代完整系统 tombstone 或 ANR trace。

0.3.5 针对切回前台后卡顿/重启的报告，移除任务列表、通知和详情刷新中的主线程原生查询，改为后台每秒发布缓存；合并事件刷新，退出页面后停止刷新，关闭连续任务卡片更新动画。设置底部增加本机运行诊断，可读取 Android 记录的上次进程退出原因。单任务公开磁力在模拟器连续切换前后台后连接和 PID 保持；尚未在用户的 vivo / Android 14 上验证，不能将已确认的刷新缺陷等同于已确认的设备重启原因。

0.3.4 重做多文件新增下载窗口：采用全屏窗口、可滚动设置/文件区和固定底部确认区，文件数量和长文件名不会挤掉“开始下载”按钮。支持全选、全不选、已选数量/大小汇总；未选择文件时禁止提交，限速输入校验后再添加。模拟器使用 1,000 个模拟文件验证列表滚动、选择保留、限速传递和 300dp 高度下的按钮可见性。

0.3.2 修复了磁力新增界面长期停留在“正在读取种子信息”的问题：临时任务按 qBittorrent 桌面端方式主动运行于仅元数据模式，显式启动并引导 DHT，同时持久化 DHT 会话状态。等待框禁止点按外部误关闭；取消会立即清理临时任务，因此可以马上重新添加同一磁力。

0.3.3 取消任务卡片的左滑操作，暂停/继续和删除按钮均固定显示。实时已下载量不再依赖分片百分比变化才刷新；KiB 显示整数、MiB 显示一位小数、GiB/TiB 显示两位小数，并将进度条内部精度提升到十万分之一。

## Android HTTPS Tracker 说明

`libtorrent4j` 预编译 Android 原生库无法读取 Android Java/Conscrypt 的系统 CA 存储；启用 libtorrent 的证书验证会在 HTTPS announce 前报 `BIO routines` 初始化失败。按 libtorrent 对“系统没有可用证书存储”平台的兼容说明，本工程关闭 `validate_https_trackers`：HTTPS 仍加密，但不验证 Tracker 证书。请不要在不可信代理或公共网络中传输包含 passkey 的私有种子。

如果直连 Tracker 超时，可在设置中启用 SOCKS5。代理运行在手机本机时填写 `127.0.0.1`；代理运行在电脑时填写电脑的局域网 IP，而不是电脑的 `127.0.0.1`。

## 许可证

本 Android 工程以 GPL-3.0-or-later 发布，见 `COPYING`。qBittorrent 官方源码按其原始 GPL-2.0/GPL-3.0 许可保留在独立目录。libtorrent4j 使用 MIT 许可证，libtorrent 使用 BSD 许可证；对应依赖从 Maven Central 获取。
