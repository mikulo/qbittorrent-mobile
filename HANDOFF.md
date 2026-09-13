# qBittorrent Mobile 项目交接文档

> 面向下一位接手的 AI / 开发者。核对日期：2026-09-13；当前 Android 版本：0.3.8（versionCode 13）。最新增量见下文，历史章节不代表当前刷新周期。

## 0.3.8 最新补记

- 用户确认 0.3.7 可以正常使用，要求将刷新周期改为 1 秒，直接生成 APK、不测试并更新 GitHub。
- 已将 MainActivity、TorrentDetailActivity、TorrentService 的 UiRefresh，TorrentEngine 的状态与文件详情定时器，以及 StateRequestGate 的请求间隔全部改为 1000ms。保留异步状态/统计、原生时间戳速率、独立详情读取和检查点写入，不退回旧同步查询。
- 本轮仅执行 assembleDebug；未运行单元测试、instrumentation 或 lint。测试代码中与周期绑定的预期同步调整，但未执行。
- 当前包为 `android-app/artifacts/qBittorrent-Mobile-0.3.8-universal-debug.apk`。GitHub 更新包含之前尚未推送的 0.3.7 修复与本次 1 秒调整；不提交 APK、私有输入、原始日志或凭据。
>
> 本文依据当前本地源码、构建文件、已有 QA 记录和用户反馈整理。**“源码已实现”“历史测试通过”“用户真机已确认”是不同证据等级，不可混用。** 本次工作只整理文档，没有重新运行全部构建、网络下载或真机测试，也没有修改应用逻辑。

> 公开发布补记：后续应以根目录 Git 仓库管理 Android 源码与文档，官方目录使用固定提交的子模块。私有输入、日志、缓存、旧截图和 APK 不进入公开 Git 历史。下文机器路径、历史包和测试输入属于原维护环境记录，不保证在新克隆中存在；请同时阅读根目录 [README](README.md)。

## 0. 0.3.7 接手补记（优先于下文 0.3.6 历史基线）

- 当前修复版本是 **0.3.7 / versionCode 12**；第 3 节的 0.3.6 包大小和哈希属于历史产物，勿用于核对新包。
- 用户日志确认：有 1 个下载任务时，12 条整轮查询记录耗时 1,816–10,396ms，平均 5,753.8ms；删除任务后约 0–4ms。只改 UI 计时器不能解决采集串行等待。
- 高频路径现为：500ms 异步 `post_torrent_updates` / `post_session_stats` → alert 线程读取数据包中的值 → 发布 Java 快照。不能重新加入 get_torrents/getName/savePath/status 等逐字段同步查询。
- `StateRequestGate` 同时约束应用定时器和 SessionManager 自带请求，每种最多一份在途；未收到回调时不重复堆积请求。日志的 pending age 可用于识别无回调/卡住。
- 状态 alert 是增量，空更新不表示删除全部任务。`activeHashes` 只注册正式任务；`states` 保留未变化行；删除清除状态和速率基线；元数据临时任务不进正式列表。
- `TransferRate` 按原生 alert 时间戳与累计字节差计算：会话为总传输，任务为 payload；处理初次采样、暂停计数器归零、重复/倒退时间戳。没有再套用 libtorrent4j 的五点平滑。
- 请求 `QUERY_ACCURATE_DOWNLOAD_COUNTERS/QUERY_NAME/QUERY_SAVE_PATH`，不请求大 Piece 位图。只在回调期间读 native 数据，不跨回调保留 borrowed 指针。
- `LiveProgress` 已移除，不能继续将 payload 增量当作有效完成量。部分区块进度来自 native 准确计数，是否完成由 native 状态决定。
- `detailPoller` 独立查询文件/Tracker/限速，不阻塞状态缓存发布；详情进度格式改用 downloadedBytes。`checkpointWriter` 独立写恢复文件，alert 内只序列化/复制数据；恢复请求按 hash 合并直到写盘结束。
- **磁盘仍为 POSIX**。曾试用默认 mmap，在 Android 14 和 Android 17 模拟器共享存储复现系统 MediaProvider/FUSE abort，导致应用被终止；已撤回，不得将其描述为已完成的磁盘后端优化。
- 新诊断字段见 Android README：响应耗时、回调年龄、在途请求年龄、转换耗时、详情耗时、累计回调数；旧 `queryMs` 不能与这些字段直接混为一项。
- 新建专用 `QBMobile_API34` AVD，使用现有 `system-images;android-34;google_apis;x86_64` 镜像，仍固定 `emulator-5554`。不要操作 `DSH_Pixel34` 或真机；`Pixel_10_Pro` 是 API 37.1/Android 17 预览镜像，而不是 Android 14。
- 新增 4 项 JVM 速率/请求队列测试，以及本机 BT 实传 instrumentation `TransferPipelineTest`。具体通过/失败结果以 QA 最新记录为准，不能把旧计时器回调测试当作真实数据刷新证明。
- 最终完整回归：4 项 JVM + 6 项 instrumentation 通过，含实际上传、任务卡片文字变化、暂停及删除。10 秒采样收到 20 次统计，最大状态/统计采样年龄 497/496ms；限速传输字节变化最大间隔仍为 2,031ms，不能将“每半秒采样”等同于“每半秒必然下载新字节”。详细失败尝试和测试修正见 `android-app/docs/QA.md`。
- 0.3.7 本地 APK：`android-app/artifacts/qBittorrent-Mobile-0.3.7-universal-debug.apk`，32,318,866 字节，与 0.3.6 签名相同；本轮修改未自动提交或推送 GitHub。
- 本次修复不改变客户端网络身份、监听端口、Tracker TLS 设置、用户目录或私有种子内容；FTP 原始日志未写入仓库。本地完成修复不等于已经推送 GitHub。

## 目录

1. [接手先读：现状与边界](#1-接手先读现状与边界)
2. [用户需求与协作约束](#2-用户需求与协作约束)
3. [目录、版本与交付物](#3-目录版本与交付物)
4. [开发环境与可复现操作](#4-开发环境与可复现操作)
5. [源码导航与运行架构](#5-源码导航与运行架构)
6. [新增任务与磁力元数据流程](#6-新增任务与磁力元数据流程)
7. [暂停、恢复和进度持久化](#7-暂停恢复和进度持久化)
8. [Android 存储与权限](#8-android-存储与权限)
9. [协议、Tracker 与代理](#9-协议tracker-与代理)
10. [界面、刷新与后台运行](#10-界面刷新与后台运行)
11. [日志和进程退出诊断](#11-日志和进程退出诊断)
12. [历史问题与修复时间线](#12-历史问题与修复时间线)
13. [测试证据与回归清单](#13-测试证据与回归清单)
14. [已知风险和待办优先级](#14-已知风险和待办优先级)
15. [常见问题排查手册](#15-常见问题排查手册)
16. [修改与交付规范](#16-修改与交付规范)
17. [下一位 AI 的接手清单](#17-下一位-ai-的接手清单)

## 1. 接手先读：现状与边界

### 1.1 这到底是什么工程

这是独立的 **Java 原生 Android 客户端 + libtorrent4j / 原生 libtorrent** 工程。界面参考 qBittorrent 桌面端和 WebUI，行为实现参考官方源码。

**它不是将官方 Qt/C++ qBittorrent 整体交叉编译到 Android，也不是官方 Android 发行版。** 当前 Gradle 构建没有将 `upstream-qbittorrent` 的 C++ 业务代码链接进 APK；原生传输库来自 Maven 的预编译依赖。用户最初期望“基于官方源码移植”，后续接手必须如实说明当前实现与这个目标的差距。

底层 BitTorrent 通信不是手写协议翻译，但 qBittorrent 桌面应用的任务管理、配置、恢复策略和全部 WebUI 功能也不会因为引入 libtorrent 自动具备。Android 应用自己实现了其中一部分。

### 1.2 当前最重要的状态

- 当前可安装测试包：`android-app/artifacts/qBittorrent-Mobile-0.3.6-universal-debug.apk`。
- 已有：文件/磁力/种子直链导入、添加前文件选择、单任务限速、暂停恢复、下载目录设置、fast-resume、固定删除按钮、500ms 状态刷新、文件日志及日志目录设置。
- 用户此前已确认私有种子能下载；不能据此承诺所有 PT 站认可这个客户端。
- 用户报告的 vivo 切回前台后卡顿、重启问题：已修复若干确定的刷新缺陷，并增加诊断；**没有收到该手机的新日志和完整回归结果，根因及最终修复效果仍未确认**。
- 最新日志功能和 500ms 刷新有模拟器回归记录；不能描述为已在用户手机验收。
- HTTPS Tracker 当前关闭证书验证，是必须显式保留在交接中的安全债务。

### 1.3 事实来源优先级

1. 当前源码/构建配置：判断“现在代码做了什么”。
2. [QA 记录](android-app/docs/QA.md)：判断“某个历史版本曾测过什么”。
3. 用户反馈：判断实际设备表现；不要用模拟器结果否定用户现象。
4. README：快速介绍，部分截图与历史说明不是当前 UI 的严格快照。
5. 本文中的“风险/建议”：基于实现审阅的判断，不等于已复现缺陷。

## 2. 用户需求与协作约束

### 2.1 用户明确要求过的功能

- UI 接近 qBittorrent，但必须是手机友好布局，而不是照搬桌面窗口。
- 能下载 PT 私有种子；关注真实 Tracker、客户端身份和协议行为，不能只展示伪造的状态。
- 官方 GitHub 源码独立子目录；Android 源码和 APK 在另一个子目录。
- 暂停后不允许自动恢复；重启必须尊重暂停意图。
- 可自定义下载目录，适配 Android 权限。
- 支持 HTTP/HTTPS `.torrent` 直链。
- 新增任务先弹出设置/文件选择窗口，默认全选，可设单任务上下行限速；磁力元数据应异步读取，不能误触消失后留下不可重加的任务。
- 任务详情也能修改单任务限速。
- **最新要求是取消所有左右滑删除，卡片固定显示删除按钮。** 早期滑动需求已经被替代。
- 已下载量：KiB 整数、MiB 一位小数、GiB 两位小数；进度不要等一个完整 Piece 才更新显示。
- 多文件选择界面必须始终能看到确认按钮。
- 切后台下载、返回前台不能引起卡顿或重新启动下载进程。
- 实时速度刷新改为 0.5 秒；日志写文本文件，设置中可选日志目录，详细程度由开发者决定。

### 2.2 测试素材与隐私

| 本地输入 | 性质 / 使用约束 |
| --- | --- |
| `android-app/magnet.txt` | 用户给的五个磁力，每行一个；优先用这些公开测试输入做网络回归，不在交接中复制原链接 |
| `android-app/test.torrent` | 用户明确说明是私有 PT 种子；非必要不要使用，更不要自动触发真实 announce |
| `android-app/test1.torrent` | 按敏感种子材料处理，未经核实不要当成公开样例 |
| `android-app/dl-link.txt` | 直链回归输入，可能包含账户凭据/passkey；不要打印、提交或写入报告 |

私有 Tracker 的 URL、passkey、Cookie、磁力完整内容、来源记录和日志都可能敏感。需要测试时优先构造本地合成 metainfo，真实公网测试优先公开磁力。不要把测试输入复制到公开仓库、Issue、日志样例或交接文档。

### 2.3 操作边界

- 当前工作目录：`D:\codex\qbittorrent`，使用 PowerShell。
- 后续自动化 Android 测试沿用明确目标 **`emulator-5554`**。不要因 `adb devices` 出现真机就安装、强停、清数据或执行测试；操作真机需用户明确授权。
- 不要对用户设备运行 `pm clear`，不要卸载现有应用来解决签名问题；卸载可能删除应用目录中的下载与恢复数据。
- 不要删除用户种子、下载文件、旧 APK、签名文件或已有改动。
- 境外依赖下载可使用用户提供的电脑代理 `socks5://127.0.0.1:7891`，不代表必须把这个地址硬编码进 Android App。
- 修改前检查当前目录和 Git 状态。交接初始核对时只有上游目录是独立 Git 仓库；公开发布后根目录管理 Android 源码和文档，上游以子模块固定提交。不要覆盖已有改动或把两者的历史混淆。
- 本文是交接说明，不授权后续 AI 自动扩大任务、重写架构、更新全部依赖或删除数据。

## 3. 目录、版本与交付物

### 3.1 目录地图

```text
D:\codex\qbittorrent\
├─ HANDOFF.md                        本交接文档
├─ upstream-qbittorrent\              官方参考源码，独立 Git 仓库
│  └─ AGENTS.md                       修改上游范围前先阅读该目录指引
├─ android-app\                      实际 Android 产品工程
│  ├─ app\src\main\java\org\qbittorrent\mobile\
│  ├─ app\src\main\res\              布局、主题、字符串、图标
│  ├─ app\src\main\AndroidManifest.xml
│  ├─ app\src\androidTest\java\org\qbittorrent\mobile\
│  ├─ app\build\                     生成文件，勿当源码修改
│  ├─ artifacts\                     按版本保留的可安装 APK
│  ├─ docs\QA.md                     历史构建/测试记录
│  ├─ docs\screenshots\              早期界面截图，非 0.3.6 全量截图
│  ├─ README.md / COPYING
│  ├─ build.gradle.kts / settings.gradle.kts / gradle.properties
│  ├─ gradlew / gradlew.bat / gradle\wrapper\
│  └─ magnet.txt / test.torrent / test1.torrent / dl-link.txt
└─ .java-temp\                       历史 Java 临时目录，不是应用源代码
```

### 3.2 官方参考源码基线

- Remote：`https://github.com/qbittorrent/qBittorrent.git`。
- 当前 HEAD：`55ded55c696cd149e31faafa6ac65f4811321e0b`，短版本 `55ded55`。
- 本次核对 `git status --short` 无输出，代表核对时上游工作树干净；未来接手时必须重新检查。
- 不要推断这个提交等于 APK 的“官方版本”，也不要未经确认切换上游分支/标签。
- 参考检索入口：`SessionImpl::initializeNativeSession`、`addnewtorrentdialog.ui`、`UPLOAD_MODE`、`STOP_WHEN_READY`、`save_resume_data`。路径随上游版本变化，用搜索确认。

### 3.3 当前 APK 身份

| 项目 | 值 |
| --- | --- |
| Application ID / namespace | `org.qbittorrent.mobile` |
| versionName / versionCode | `0.3.6` / `11` |
| 安装包 | `android-app/artifacts/qBittorrent-Mobile-0.3.6-universal-debug.apk` |
| 文件大小 | 32,311,478 字节 |
| SHA-256 | `D782257B8465FF561989DBF8529D3ACBE6D42BAF78E4CD645230146541282795` |
| 签名 | Debug 签名；历史记录验证 APK Signature Scheme v2 |
| ABI | `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` |

SHA-256 和大小在初始文档核对中重新检查。历史 APK 从 0.1.x 到 0.3.5 仍在本地 artifacts，不随公开仓库提交；不要为整理目录擅自删除。旧产物没有独立源码提交号可追溯，新产物应记录根仓库提交号和构建清单。

## 4. 开发环境与可复现操作

### 4.1 当前构建配置

| 组件 | 当前值 |
| --- | --- |
| Java 源码/目标版本 | 17 |
| JDK 已用路径 | `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot` |
| Android SDK 已用路径 | `C:\Android\Sdk` |
| minSdk / compileSdk / targetSdk | 26 / 34 / 34 |
| Android Gradle Plugin | 8.7.3 |
| Gradle Wrapper | 8.9 |
| AppCompat / Activity / RecyclerView | 1.7.0 / 1.9.3 / 1.3.2 |
| Material | 1.12.0 |
| libtorrent4j Java / 四个 Android ABI 包 | 统一 `2.1.0-39` |
| 测试 runner | `android.test.InstrumentationTestRunner` |

`gradle.properties` 当前 JVM 堆上限 3072m、UTF-8、AndroidX、non-transitive R。`jniLibs.useLegacyPackaging = true`。Release 未配置签名，混淆关闭。当前不是自编 NDK/libtorrent 的流程。

“minSdk 26、targetSdk 34”只是构建及运行门槛/目标配置，**不代表所有 Android 8–14 设备已测试，也不代表更高版本已适配**。提高 targetSdk 前需重新核对存储、通知和前台服务约束。

### 4.2 构建命令（PowerShell）

先检查这些路径仍存在，再执行：

```powershell
Set-Location 'D:\codex\qbittorrent\android-app'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:ANDROID_HOME = 'C:\Android\Sdk'
New-Item -ItemType Directory -Force -Path 'C:\Temp\JavaSockets' | Out-Null
# 已有 JAVA_TOOL_OPTIONS 时先检查，合并需要的选项，不要覆盖用户其它配置。
$env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS + ' -Djdk.net.unixdomain.tmpdir=C:\Temp\JavaSockets').Trim())
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

当前没有 `android-app/local.properties`，已用 `ANDROID_HOME` 指向 SDK。迁移环境时可配置本机 SDK，但不要把个人绝对路径当成可移植工程配置发布。

默认输出：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- `app/build/reports/lint-results-debug.html` / 对应 XML 报告

已遇到的问题：

- Windows Java 报 `Unable to establish loopback connection`：短的 Unix-domain socket 临时目录选项曾解决此问题，先检查环境，不要误判为 BitTorrent 网络故障。
- SDK XML 版本 3/4 提示：历史构建有警告但成功，不能一看到就重装 SDK。
- native library strip 提示：预编译库可能不能剥离符号，历史上不是打包失败。
- lint 通过是“无阻断错误”，不代表没有警告。
- `testDebugUnitTest` 无 JVM 测试源，不能把 `NO-SOURCE` 写成完整单元测试通过。
- 构建代理由下载工具/Gradle 的实际代理支持决定；不要把 SOCKS URL 当作所有工具通用的 HTTP 代理参数。

### 4.3 模拟器和自动化测试

已用 AVD 名称 `Pixel_10_Pro`、串号 `emulator-5554`、x86_64。AVD 名称不能用来推断 Android 系统版本；用 `getprop ro.build.version.sdk` 核对。

优先复用已启动的指定模拟器。若确需启动，用已存在的 AVD，后台进程隐藏启动：

```powershell
Start-Process -FilePath 'C:\Android\Sdk\emulator\emulator.exe' -WindowStyle Hidden -ArgumentList @('-avd', 'Pixel_10_Pro', '-port', '5554', '-no-snapshot-save')
```

所有 adb 操作都带串号；不要依赖 PATH 上可能存在的另一份 adb：

```powershell
Set-Location 'D:\codex\qbittorrent\android-app'
$qbmAdb = 'C:\Android\Sdk\platform-tools\adb.exe'
& $qbmAdb -s emulator-5554 shell getprop sys.boot_completed
& $qbmAdb -s emulator-5554 shell getprop ro.build.version.sdk
& $qbmAdb -s emulator-5554 install -r '.\app\build\outputs\apk\debug\app-debug.apk'
& $qbmAdb -s emulator-5554 install -r '.\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
# API 33+ 测试环境使用；低版本没有这个权限。
& $qbmAdb -s emulator-5554 shell pm grant org.qbittorrent.mobile android.permission.POST_NOTIFICATIONS
& $qbmAdb -s emulator-5554 shell am instrument -w -e class 'org.qbittorrent.mobile.AppLogTest,org.qbittorrent.mobile.RefreshLifecycleTest,org.qbittorrent.mobile.TorrentOptionsDialogTest' org.qbittorrent.mobile.test/android.test.InstrumentationTestRunner
```

等待系统完成启动再跑测试。曾有开机期间无过滤 instrumentation 被 `LOW_MEMORY` 杀死，显式类列表在启动完成后通过；遇到此情况要记录失败和重跑条件，不能隐瞒或直接算通过。

### 4.4 校验和归档

```powershell
& 'C:\Android\Sdk\build-tools\34.0.0\apksigner.bat' verify --verbose --print-certs '.\app\build\outputs\apk\debug\app-debug.apk'
& 'C:\Android\Sdk\build-tools\34.0.0\aapt.exe' dump badging '.\app\build\outputs\apk\debug\app-debug.apk'
Get-FileHash '.\app\build\outputs\apk\debug\app-debug.apk' -Algorithm SHA256
```

归档时用**新的版本文件名**复制到 artifacts，检查目标不存在；不要覆盖历史包。不应因本次只改文档而无意义地增加 APK 版本或重新发布同名包。

## 5. 源码导航与运行架构

主 Java 包：[app/src/main/java/org/qbittorrent/mobile](android-app/app/src/main/java/org/qbittorrent/mobile)。

| 文件 | 职责 / 接手重点 |
| --- | --- |
| `QBittorrentApp.java` | 初始化日志、引擎及退出诊断；注册 Activity 生命周期和内存警告日志 |
| `TorrentEngine.java` | 核心单例；原生会话、导入、草稿、任务操作、Tracker 消息、后台缓存、恢复和偏好；改动风险最大 |
| `MainActivity.java` | 列表、筛选、导入入口、添加窗口、服务启动、生命周期刷新 |
| `TorrentOptionsDialog.java` | 全屏新增任务窗口、文件选择、限速校验、固定确认区、提交/取消回调 |
| `TorrentDraft.java` | 添加前的名称、hash、大小、私有属性和文件集合 |
| `TorrentSnapshot.java` | UI 读取的任务快照，而非直接暴露 native 状态对象 |
| `TorrentAdapter.java` | 任务卡片 DiffUtil、固定暂停/继续/删除按钮、进度和数字绑定 |
| `TorrentDetailActivity.java` | 单任务详情、Tracker、文件进度和限速；按可见性订阅详情缓存 |
| `SettingsActivity.java` | 全局设置、下载/日志目录、权限引导、协议身份和运行诊断 |
| `TorrentService.java` | dataSync 前台服务、常驻通知、START_STICKY、退出/划走时尽力保存恢复数据 |
| `UiRefresh.java` | 限制每个消费者仅一个排队刷新，合并事件并随生命周期停止 |
| `Formatters.java` | 速度、大小、已下载量、ETA 等格式 |
| `AppLog.java` | 异步、有界、轮换、脱敏文本日志和目录切换 |
| `ProcessDiagnostics.java` | Android 历史进程退出原因与当前设备/PID 信息 |

布局入口：`activity_main.xml`、`item_torrent.xml`、`activity_detail.xml`、`activity_settings.xml`、`dialog_add.xml`、`dialog_torrent_options.xml`、`dialog_torrent_selection.xml`。后两者不是重复文件：options 是滚动区域中的设置头，selection 是包含固定底部操作区的完整窗口。

### 5.1 线程和频率

| 执行位置 | 主要工作 | 约束 |
| --- | --- | --- |
| 主线程 | Activity、RecyclerView、对话框、缓存渲染 | 不重新加入 native torrent/status/trackers 全量查询或磁盘等待 |
| `io` 单线程执行器 | 导入、启动/恢复、任务变更 | 同步网络导入可能延后队列中的其它操作，后续优化要保持顺序语义 |
| `metadataPoller` 单线程调度器 | 元数据轮询、恢复检查点定时任务、session state 保存 | 不是每种任务独立线程；耗时任务可能相互延误 |
| `statePoller` / `torrent-state` | 每 500ms 固定延迟采样 native 状态，发布缓存 | 实际周期是查询耗时 + 500ms，不是硬实时采样 |
| libtorrent alert 回调 | Tracker 状态、恢复数据返回等 | 当前部分恢复文件写盘仍在回调路径，存在后续优化空间 |
| `AppLog` / `app-log` | 日志追加、轮换、目录探测 | 有界队列，避免主线程同步写盘 |

状态流：native session → 后台状态快照 → volatile 缓存列表/详情 → 主页面、详情、通知各自的 `UiRefresh`。详情只为 `detailWatches` 中正在观察的任务采样。主线程读缓存不应触发隐式 native 调用。

源码中的重要入口：`startAsync/startInternal`、`refreshState/readSnapshots`、`snapshots/snapshot/files`、`watchDetails/unwatchDetails`、`prepare*`、`commitDraft/discardDraft`、`restoreSources`、`saveResumeDataAsync`。

SWIG 原生对象有独立生命周期。当前采样路径把 status/vector 转换为 Java 数据后在 `finally` 中释放。新增查询时核对 binding 的所有权；不要漏释放，也不要对借用的句柄机械调用 `delete()`，否则可能导致 native 崩溃。

## 6. 新增任务与磁力元数据流程

### 6.1 两阶段添加

```text
输入磁力 / 选择文件 / 输入直链
  → prepareMagnet / prepareTorrent / prepareTorrentUrl：生成 draft ID，后台准备
  → 展示等待状态，元数据就绪后展示文件和限速
  → 用户确认：commitDraft，应用选择与限速，再持久化正式任务
  → 用户取消：discardDraft，移除临时 native 任务和待处理状态
```

关键集合：`pendingTorrents`、`pendingHashes`、`cancelledDrafts`。暂存任务不应作为正式下载显示在主列表，也不应在用户确认前全量下载文件。

引擎仍保留旧的 `addMagnet/addTorrent/addTorrentUrl` 直接添加入口。后续修改导入 Intent 或 UI 时，不要误用旧入口绕过文件选择和限速窗口。

### 6.2 `.torrent` 与直链

- `.torrent` 通过完整 metainfo 的 `libtorrent.load_torrent_file` 构造 `AddTorrentParams`。
- 不能退回只构造 `TorrentInfo` 再 add：此前该 binding 路径丢失了顶层 Tracker 信息，造成“能看到文件却无 Tracker”。
- 保留 announce/tier、Web Seed 等顶层字段；私有属性本身不能替代 Tracker 地址。
- 导入定义保存到应用内部 `files/torrent_defs`；系统 Uri 的输入要复制，不能假设临时授权永远存在。
- HTTP/HTTPS 直链读取实现有连接超时 20 秒、读取超时 45 秒、最多 5 次重定向、16 MiB metainfo 大小上限；声明长度和实际流长度都有限制。
- 支持 HTTP/HTTPS URL 识别不代表每个 HTTP 服务器、重定向、认证方式及 Android 明文网络策略组合都验证过。真实网络回归需单独覆盖。
- 页面返回 HTML、登录过期、过大文件或无效 bencode，应呈现错误而不是当成下载内容开始 BT 任务。

### 6.3 磁力元数据关键细节

- 创建仅元数据任务前清除 `PAUSED` 和 `AUTO_MANAGED`。
- 设置 `UPLOAD_MODE` 与 `STOP_WHEN_READY`，并显式 `resume()`。
- 元数据临时保存位置为 cache 下 `magnet_metadata`。
- 显式配置 DHT bootstrap，并在开启 DHT 时启动：`dht.libtorrent.org:25401`、`router.bittorrent.com:6881`、`router.utorrent.com:6881`、`dht.transmissionbt.com:6881`。
- 大约每秒轮询元数据，最多约 120 次；不是无限等待。实际总耗时还受队列和网络影响。
- 元数据到达后移除临时 handle，解除只取元数据标志，生成可提交草稿。
- 支持取消并清理临时任务，避免同一 hash 再次添加被误判为重复。
- 不能承诺任何磁力都能“几秒出信息”；无可达元数据提供者、无 Tracker、DHT 不通时仍可能超时。

### 6.4 添加窗口约束与待审计点

- 全屏 Dialog；标题和底部按钮固定在可滚动 RecyclerView 外，文件数量不得挤掉确认区。
- 默认全选，有全选/全不选及已选数量/大小；一个文件都未选时禁止提交。
- 上下行输入单位 KiB/s，0 表示不限速；合法范围 0–2,097,151，转换为 B/s 防止 int 溢出。
- 外部点按不应关闭等待/选择流程；明确取消/返回应释放草稿。
- 当前 Activity 销毁会 dismiss 窗口并取消，不是保留跨旋转草稿的 ViewModel 方案；旋转、进程重建、多次 Intent 仍应专项测试。
- 当前 UI 的“已添加”提示与窗口关闭没有等待异步 commit 成功回执。失败时体验及草稿清理仍需审计，不能假定已经完整事务化。
- 不同 prepare 路径对 pending hash 的预留/释放以及取消与完成的跨线程竞争，也需要有针对性的回归，而非只测正常添加。

## 7. 暂停恢复和进度持久化

### 7.1 应用持久化数据

SharedPreferences `torrent_engine`：

- `sources`：JSON 数组，记录 `hash`、`type`、`source`、`paused`、`filePriorities`、`downloadLimit`、`uploadLimit`。
- `download_path`：当前全局保存路径。
- 监听端口、DHT/LSD/UPnP/NAT-PMP、全局速率和代理等配置。
- `source` 可能是磁力或本地定义路径，必须视为敏感数据。限速存储/恢复时核对调用位置的单位，不要把 KiB/s 与 B/s 混用。

应用文件目录：

| 位置（相对 app files/cache） | 内容 |
| --- | --- |
| `files/torrent_defs/` | 导入的种子定义 |
| `files/resume_data/<hash>.fastresume` | libtorrent 原生恢复数据 |
| `files/session.state` | 原生 session / DHT 状态 |
| `cache/magnet_metadata/` | 磁力元数据阶段临时数据 |

日志另有 SharedPreferences `app_logging`，`directory` 保存规范化后的实际日志目录。不要把下载目录和日志目录的状态混在一起。

### 7.2 暂停是用户意图，不只是瞬时速度为零

- 用户暂停时先持久化 `paused=true`，并清除 `AUTO_MANAGED`、设置 `PAUSED`。
- 恢复时相应更新用户意图并解除暂停。
- 启动恢复任务时以持久化用户意图覆盖恢复数据中的相关标志，不能让 native 队列自动管理把手动暂停任务重新启动。
- 暂停/恢复等操作会请求检查点。
- 速度暂时为零不是暂停；“有任务恢复记录”也不意味着应该主动继续。

### 7.3 Fast-resume 机制

- 使用原生 resume data 保存 Piece 校验状态、部分区块、文件状态和任务参数，而非只保存磁力后重加。
- 每 15 秒请求恢复数据；暂停、恢复、限速变化、完成、服务退出等也触发保存。
- 恢复记录采用临时文件、fsync、替换写入，尽量避免半文件。
- 原生 save-resume alert 返回后序列化落盘；用请求集合/等待机制跟踪保存状态。
- 重启优先 `read_resume_data_ex` 加载检查点；损坏记录会被放弃并回退来源恢复，这可能触发重新校验。
- `session.state` 每约 60 秒原子保存，启动时恢复 DHT 路由等状态，再应用当前设置，避免旧 session 配置覆盖用户最新设置。
- 服务退出保存是尽力而为；系统直接杀进程、native 崩溃、强行停止不能保证退出回调和最后一轮落盘完成。

**不能承诺任意强杀后 0 校验或精确 1 秒恢复。** 磁盘状态变化、缺失/过期检查点、移动文件、强制校验请求都可能合法触发校验。修复目标是避免无必要的全量校验，不是欺骗 native 内核跳过数据一致性验证。

### 7.4 删除与迁移

删除操作需区分仅移除任务和同时删除下载数据；保持确认步骤。移除来源记录和 fast-resume 后不能在重启时把任务恢复回来。

不要对整个 `files`、外部 Download 或日志根目录做递归清理。导入的 torrent_defs 是否全部随删除清理、迁移失败是否正确反馈属于后续可审计项。

## 8. Android 存储与权限

默认下载目录：

```text
/storage/emulated/0/Android/data/org.qbittorrent.mobile/files/Download
```

外部应用目录不可用时有内部 `files/downloads` 回退。应用专属目录与卸载生命周期关联；不要建议用户通过卸载修复问题而不先说明数据风险。

自定义目录流程：系统目录选择器 → 解析实体外部存储目录 → 校验写入 → 保存路径/执行任务移动。

- 原生 libtorrent 使用真实文件路径，不能直接把 `content://` Uri 作为保存路径。
- 当前 resolver 面向实体 ExternalStorageProvider，不是通用 SAF 文件系统；云盘、虚拟文档源不支持。
- Android 11+ 自定义共享存储使用“所有文件访问”授权引导；较旧系统使用相应读写权限。
- **更改下载目录会对现有任务调用 moveStorage，不是只修改未来任务默认路径。** 设置文案、用户操作提示和测试必须体现这一点。
- 当前没有完整的单任务独立保存目录管理界面；添加窗口展示路径不等于能逐任务选择目录。
- 撤销权限、SD 卡拔出、同名文件冲突、空间不足、移动中进程死亡等尚需专项验证。
- 目录选择的日志/下载用途用 `selectingLogDirectory` 区分，并保存到实例状态；修改此处注意返回选择器时不要误用另一个功能。

Manifest 已声明网络、前台 dataSync 服务、通知、相关存储权限和 WAKE_LOCK。**声明 WAKE_LOCK 不等于代码已经实际持有 WakeLock。** 不要用权限列表来保证息屏持续传输。

当前 `allowBackup=true`，来源信息/代理配置等没有应用层加密；正式分发前应审阅备份边界和凭据保护。广泛存储权限及新版 Android 前台服务限制也需发布前单独核验。

## 9. 协议、Tracker 与代理

### 9.1 客户端身份

`TorrentEngine` 当前固定：

```text
QBT_VERSION      = 5.2.3
USER_AGENT       = qBittorrent/5.2.3
PEER_FINGERPRINT = -qB5230-
```

这是工程的兼容身份配置，不是 APK 版本，也不是“截至今天官方最新稳定版”的结论。实际 APK 版本是 0.3.6。

历史本地 Tracker 捕获验证了实际 announce 含上述 UA/peer-id 前缀，不只是设置页显示。peer-id 后面的随机后缀正常。anonymous mode 关闭；TCP/uTP、加密、元数据扩展等由 libtorrent 提供。

PT 站可能检查底层实现、扩展握手、版本白名单和客户端政策。**改 UA 不等于官方客户端，不能保证符合站点规则或绕过白名单。** 升级 libtorrent4j 或改身份时应重新做真实请求捕获及允许范围内的兼容测试。

### 9.2 私有种子与 Tracker

私有种子不应靠打开 DHT 来替代丢失的私有 Tracker。保留 metainfo 私有标志和 announce 数据，尊重 native 私有种子机制；不要通过修改 private 标记或添加公共 Tracker 来“修复”PT 下载。

Tracker reply/error/warning 已从 alert 更新到详情和日志，能区分正在汇报、返回 Peer 数与真实错误。

用户曾遇到 `port 6881 is blacklisted`：这是 Tracker 对 announce 中监听端口的策略拒绝，不是文件坏了，也不是表示所有网络都封了此端口。应用允许改监听端口，但**当前源码默认仍为 6881**；历史用户成功不能视为默认端口已统一迁移。不要把 DHT bootstrap 的端口和本机监听端口混淆。

### 9.3 HTTPS Tracker 安全债务

当前设置 `validate_https_trackers=false`。历史原因是预编译 Android 原生库启用验证时出现 OpenSSL `BIO routines` 初始化失败，无法按当时方式使用 Android CA 存储。

结果是 HTTPS 有加密，**但不校验 Tracker 证书，不能可靠认证对端，存在中间人风险**；私有 announce 中的 passkey 尤其敏感。不是“已经完全适配官方安全协议”的状态。

正确后续方向是验证 native 库的 CA 加载方式、接入可信证书或构建正确的原生依赖，再启用验证并回归。不能只把 boolean 改 true 就宣称修复，也不能把当前关闭验证说成长期安全方案。此处影响 native Tracker 路径，不应泛化成 Java 直链下载也自动使用相同 TLS 设置。

### 9.4 代理地址边界

- 电脑下载依赖：用户允许 `socks5://127.0.0.1:7891`。
- 手机应用代理：`127.0.0.1` 指手机自己；若代理在电脑上，需要手机可达的电脑地址和代理监听配置。
- 标准 Android 模拟器访问宿主机常用 `10.0.2.2`，仍须确认实际网络环境，不能把它写成真机地址。
- 应用可配置 SOCKS5、代理解析域名、Tracker 及可选 Peer 连接代理。直链下载走 Java 网络路径，和 libtorrent Tracker 路径应分别验证。
- 不要在仓库持久化真实代理密码，不要默认关闭所有安全验证来排查连接。

## 10. 界面刷新与后台运行

### 10.1 0.3.6 的 500ms 含义

- 任务列表、详情页、常驻通知的 `UiRefresh` 周期都是 500ms。
- 后台共享 native 状态采样也是固定延迟 500ms，另加查询耗时。
- 设置诊断页仍约 2500ms，不属于下载速度主界面。
- native 速率计数可能内部平滑/以其它节奏更新；500ms 重绘不保证每次速度数字必然不同。
- 0.3.6 没有为凑刷新率伪造速度插值。

`UiRefresh` 合并事件：每个消费者最多一个待执行刷新，运行期间也维持 queued 状态，stop 移除回调。它解决排队失控，不会自动让耗时渲染变便宜。

### 10.2 避免旧性能问题复发

- 禁止 render 中直接循环 native status / tracker / file 查询。
- 主页面、详情、通知共用缓存，不能各建一套高频 native 轮询。
- 页面离开后停止自己的 UI 刷新和详情观察；停止页面刷新不等于停止下载引擎。
- 不要让每个 Tracker alert 无限制 post 全量刷新。
- 任务卡片连续更新动画已关闭，勿为视觉效果无条件恢复。
- 详情字符串用变化检测减少 TextView 重设；但文件列表仍是较大的文本显示，超多文件性能不代表彻底解决。
- 通知 500ms 更新成本、耗电、内存和 vendor 行为应在真机测量，不要只看模拟器五次回调测试。

### 10.3 下载进度显示不是磁盘真值

`Formatters.downloadedBytes`：KiB 整数、MiB 一位、GiB/TiB 两位；进度条内部最大值 100,000。Adapter 的 diff 覆盖 completed/total/uploaded/ETA/state 等字段，避免仅 Piece 百分比变化才刷新。

`LiveProgress` 目前将已验证的 `totalWantedDone` 与刷新间隔内 payload 增量组合，并限制不超过 wanted size，用来显示 Piece 内增量。**这是视觉估计，不是精确落盘/校验字节数。** 重传、重复区块等可能造成高估；存在先显示接近/达到 100% 而 native 尚未完成的风险。不要把这个值用于真正的完成判定、分享率记账或恢复校验跳过。后续应评估使用 native 部分区块完成信息或明确区分估计与验证进度。

### 10.4 后台服务与用户设备故障

`TorrentService` 是 `dataSync` 前台服务、低重要性常驻通知、`START_STICKY`；`onDestroy/onTaskRemoved` 请求异步恢复保存，没有在 Activity 返回前台时主动重启引擎的设计。

用户报告：vivo X200 Pro mini，用户称 Android 14；仅一个任务，主列表，已锁定后台应用、允许后台/自启动、取消电量限制。后台能下载，但回前台 30 秒内卡顿然后应用重启，seeds/peers/速度归零，再恢复，下载进度未丢。

已确认的旧源码缺陷是主线程 native 查询、无界事件渲染和生命周期后遗留回调；已做优化。**真实设备是 Java 异常、native 崩溃、ANR、低内存、系统策略还是其它原因，仍需退出记录/日志证明。** 不要没有证据就归咎 vivo 或反复让用户打开已经开过的权限。

## 11. 日志和进程退出诊断

### 11.1 目录与轮换

- 默认：`getExternalFilesDir(null)/logs`，通常为 `/storage/emulated/0/Android/data/org.qbittorrent.mobile/files/logs`；无外部目录则回退内部目录。
- 自定义：用户选择的实体根目录下新建 `qBittorrent-Mobile-Logs` 子目录。
- 设置 → 运行诊断：选择日志目录、恢复默认，展示实际路径和异常回退信息。
- 日志独立于下载目录；切换日志目录不会搬迁/删除旧日志，切换下载目录也不是切换日志目录。
- UTF-8 文本：`qbm-current.txt`，历史 `qbm-1.txt` 至 `qbm-4.txt`。
- 每文件约 2 MiB，当前受管理的一组约 10 MiB；仅轮换自身文件名，不删除其它文件。
- 不同历史目录中的遗留日志不计入当前目录的 10 MiB 上限，需要用户自行管理。

### 11.2 日志内容

| 类别 | 当前记录 |
| --- | --- |
| 启动/环境 | 应用版本、设备/系统、PID 等 |
| 生命周期 | Activity 前后台事件、服务事件、内存紧张回调 |
| 引擎/任务 | 启动、准备/提交、暂停/恢复、删除、检查点等关键操作 |
| Tracker | 响应、错误、警告，敏感字符串过滤后写入 |
| 异常 | 错误和异常堆栈；未捕获 Java 异常尽力保存 |
| 每约 10 秒健康采样 | 任务数、连接 peers/seeds、上下行 B/s、Java/native 内存、查询耗时 |

健康采样中的 Java/native 内存不等于完整 PSS/RSS；历史退出诊断中的 RSS 来自 Android 退出记录。不要用不同指标直接做内存泄漏结论。

通常行格式有时间/时区、级别、PID、线程名、消息；切换目录写入的 `INFO log_directory_selected` 首行不含完整时间前缀，解析器需容忍。应用启动日志的 `version=0.3.6` 目前硬编码在 AppLog，发新版时要和 Gradle 一起更新，未来可改成统一版本来源。

### 11.3 性能、可靠性和脱敏边界

- 单独 writer 线程，队列容量 256，满队列丢弃计数并在之后写入时报告，不无限堆积内存。
- 单条原始字符串先限长，再脱敏并限制最终长度；不能假定每条极长堆栈都完整保存。
- 主线程不等待普通日志写盘；每 500ms 刷新不会每次都写一条健康日志。
- 目录配置异步做写入探测和规范化，成功后持久化并通知 UI。
- 写失败会回退默认目录并提供警告；当前保存的原自定义目录未必清空，下次进程启动可能再次尝试，应在故障排查中注意。
- 未捕获 Java 异常最多等待约 750ms 日志排空，再交给原异常处理器；不是保证成功，更不能捕获所有 native signal 或系统杀进程。
- 脱敏覆盖常见链接 scheme、passkey/token/password/Authorization/Cookie/secret、长十六进制 ID 和常见 Android 绝对路径。
- 正则脱敏不是隐私的绝对保证；特殊编码、多行格式、非典型秘密仍可能漏过。旧日志不回溯清洗，Android 原始 logcat 也不自动经过 AppLog 过滤。
- 对外分享前仍需人工检查；不要用真实 PT 凭据构造脱敏测试。

### 11.4 退出诊断

`ProcessDiagnostics` 在 Android API 30+ 读取最近的历史进程退出记录，展示原因、状态码、时间、RSS，以及当前设备和 PID；启动时写日志。

它不是完整 tombstone/ANR trace 导出工具。系统可能没有保留足够记录；“没有 Java 异常”也不能排除 native 崩溃。应以退出时间匹配用户发生故障的时间，并区分升级、测试 force-stop 与真正异常退出。

## 12. 历史问题与修复时间线

| 版本/阶段 | 问题与变更 | 证据边界 |
| --- | --- | --- |
| 0.1.x | 初始 Android UI、PT Tracker 导入、真实 Tracker 错误展示、客户端身份；完整 metainfo 加载修复无 Tracker | 有历史私有种子和本地 Tracker 记录，不代表所有站点通用 |
| 0.1.x 网络排查 | HTTPS OpenSSL 初始化问题；当前关闭 Tracker 证书验证；遇到监听端口黑名单 | 可下载不等于安全债务解决，默认端口仍 6881 |
| 0.2.0 | 持久化手动暂停、清除 auto-managed；自定义下载目录和 torrent 直链 | QA 有暂停/重启、目录和直链测试 |
| 0.3.0 | 新增前选文件和限速；详情限速；当时实现滑动删除 | 滑动删除现已被用户要求取消，不应回退 |
| 0.3.1 | 原生 fast-resume，避免只按源重加引起全量检查 | 合成任务保存/重启回归，不保证所有强杀场景免检 |
| 0.3.2 | 磁力仅元数据 flags、DHT 引导、取消清理、session state | 公开磁力约 10–20 秒获取元数据的历史记录 |
| 0.3.3 | 固定删除按钮，下载量显示精度、细粒度进度 | LiveProgress 是估计，另有正确性风险待审计 |
| 0.3.4 | 多文件窗口重构，底部确认固定，1,000 文件回归 | 缩小窗口高度测试不等于真实键盘/旋转全覆盖 |
| 0.3.5 | 背景缓存、刷新合并、生命周期停止、取消更新动画、进程诊断 | 模拟器前后台 PID 稳定；vivo 根因未确认 |
| 0.3.6 | 主下载页面/通知/采样 500ms；可选目录日志、轮换/脱敏/健康记录 | 模拟器 5 项 instrumentation 通过记录，未收到 vivo 最新验收 |

详细历史操作和测量保留在 [docs/QA.md](android-app/docs/QA.md)，不要把老版本测试日期写成刚刚重新验证。

## 13. 测试证据与回归清单

### 13.1 当前自动化测试

测试路径：[app/src/androidTest/java/org/qbittorrent/mobile](android-app/app/src/androidTest/java/org/qbittorrent/mobile)。当前共 3 个类、5 个测试方法。

| 类 | 覆盖范围 | 未覆盖 |
| --- | --- | --- |
| `TorrentOptionsDialogTest`（1） | 1,000 个合成文件；按钮完整可见；全选/全不选；滚动后选项保留；128 KiB/s 传递；窗口缩小到 300dp | 不实际下载；缩小视口不等于真实键盘；不保证所有旋转/字体/屏幕 |
| `RefreshLifecycleTest`（3） | 10,000 请求合并；停止后无刷新；重复 start；缓存读；5 次刷新约 450–800ms 容差 | 不是长期 CPU/内存/耗电测试，不是所有 native 生命周期竞争测试 |
| `AppLogTest`（1） | 自定义缓存目录持久化；中文 UTF-8；合成秘密脱敏；保留异常类型；多轮轮换限额；不删除无关文件 | 不覆盖所有秘密编码；不覆盖所有 SD 卡/权限失效和系统杀进程 |

日志测试使用缓存目录、结束清理自己的临时文件并恢复默认；即便如此也会改变应用设置，**不要直接在用户生产应用数据上运行测试套件**。

历史上日志测试第一次因 `/data/user/0` 与 `/data/data` 路径别名断言失败，改用 canonical file 比较后通过。不要再把路径字符串不同直接当作目录选错。

### 13.2 历史手工/网络验证摘要

- 0.3.5 在 `emulator-5554` 使用 `magnet.txt` 第 3 行，两次切回前台均观察超过 30 秒；PID 8665 保持，已下载 12.9 → 21.5 MiB，seeds/peers 20/112 → 34/157，PSS 81,360 → 84,159 KiB，无新 crash-buffer 记录。
- 这些数字是历史测量，不是当前进程/实时状态，也不足以证明无长期泄漏。
- 0.3.2 公开磁力元数据约 10–20 秒；DHT 冷启动观察到节点/连接增长。
- 0.3.1 合成公开无 Tracker 双文件种子生成非空 fast-resume，强停后恢复，无检查状态；暂停后重复恢复保持暂停。
- 0.2.0 直链输入曾返回有效 metainfo，自定义实体目录保存及写入曾通过。
- 更早私有 Tracker 曾回复 307 peers，连接约 3 seeds/33 peers，约 3.6 MiB/s 后暂停；该记录不授权以后每次重跑私有测试。
- 网络 QA 任务及其下载数据曾通过应用删除确认清理；不要推断此后目录一定没有新数据。

### 13.3 下一次发布至少覆盖

1. 构建 APK、测试 APK、lint；核对真实版本、包名、ABI、签名和 SHA。
2. 上述 5 项 instrumentation；记录首次失败和重跑原因。
3. 公开磁力添加：等待时触外部、取消、同链接重加、失败重试、元数据成功、确认前不下载正式 payload。
4. 多文件：全部/部分/零选择；长文件名；限速 0、合法值、负数、溢出、非数字；底部按钮；真实键盘、旋转、大字体。
5. 暂停后等待超过定时器周期，再重启；保持暂停；恢复操作后才继续。
6. 下载中正常退后台、返回、模拟器强停再打开；检查恢复文件、PID、检查状态和进度，不要求跳过必要校验。
7. 固定删除按钮、二次确认、取消删除、仅删任务/删数据，重启后不复活。
8. 下载目录更改/恢复默认，现有任务迁移反馈，拒绝权限、权限撤销、空间不足。
9. 日志目录选择/默认恢复/重启记忆/不可写回退/旧日志保留；合成敏感值脱敏。
10. 前后台往返及详情进入退出的长期内存/CPU/耗电观察；用户设备测试在明确授权后进行。

## 14. 已知风险和待办优先级

以下是交接建议，不是本次已经修复，也不是授权自动全部实施。

### 优先级高：稳定性、正确性与安全

- **vivo 前台重启未闭环**：收集 0.3.6 日志、设置中的退出原因和发生时间；按证据定位 Java/native/ANR/内存/系统策略，再改对应路径。
- **Tracker TLS 校验证书关闭**：研究并验证原生 CA 信任接入，建立 HTTPS 受信/不受信证书的回归，而不是永久依赖关闭验证。
- **LiveProgress 的估计正确性**：审计重传、失败校验、重检查、选文件、任务完成边界，不允许视觉估计成为核心完成真值。
- **添加流程事务与竞态**：异步 commit 回执、失败可重试、文件/磁力重复 hash、取消与 ready 同时发生、Activity 销毁后的回调与资源释放。
- **500ms 长期资源开销**：单任务与多任务 native 分配、alert 回调写盘、通知更新、详情大文本；避免为了流畅把进程稳定性再次破坏。

### 优先级中：存储、恢复、产品健壮性

- 下载目录变更当前影响所有任务，增加明确的迁移状态/错误反馈前先确认用户期望；不要默默改成不同语义。
- 权限失效、SD 卡移除、磁盘满、文件变更导致恢复降级、移动中崩溃等尚需系统测试。
- 默认端口仍 6881：后续可评估新安装默认值与已有用户迁移策略，不要无提示改现有监听端口。
- 原生 status/vector 所有权和其它 SWIG 临时对象需持续审计；不能以 Java 堆稳定就断定 native 无泄漏。
- 未捕获错误日志尽力保存，缺完整 native tombstone/ANR trace；可评估受控导出诊断包，但需要隐私审查和用户触发。
- 日志 fallback 后坏的自定义目录可能下次又被尝试；设置应清楚展示实际写入位置。
- 详情文件展示、大字号/横屏/键盘、配置变更保留草稿仍有改善空间。

### 发布前事项

- 正式签名和可升级策略：保留 keystore，不能丢失签名身份后靠卸载解决。
- 源码版本管理、可追溯构建、依赖锁定/完整依赖清单、第三方 NOTICE 与许可证整理。
- 备份/敏感来源和代理凭据保护；`allowBackup=true` 与广泛文件权限需专门审阅。
- 项目不是官方 Android 版；名称、图标、身份展示和发布说明不得暗示官方背书。
- README 声明 Android 工程 GPL-3.0-or-later，依赖及官方源码保留各自许可证；分发前按实际打包内容核对义务，不把本交接当法律意见。
- targetSdk 升级、后台服务限制、商店权限政策及新系统兼容，需要以届时官方文档核验，当前 API 34 构建不能代替。
- 暂无完整桌面/WebUI 功能等价：不能承诺标签、分类、RSS、自动化、完整队列/分享率规则等均已实现。

## 15. 常见问题排查手册

### 15.1 回前台卡顿后重启

1. 先记录发生时间、界面、任务数和当前 APK 版本；用户设备信息已有记录，不要每次重新问同样问题。
2. 匹配日志前后 PID、`app_start`、Activity/service 事件和设置退出记录。PID 改变才是进程重建的重要证据；UI 重建不必然等于 native 会话重启。
3. 查退出前 health 的 Java/native 内存及 queryMs 趋势、trimMemory、Java 堆栈。没有这些记录不能反推绝无崩溃。
4. 若 native/ANR/低内存，按对应系统诊断路径继续；测试 force-stop、安装更新造成的退出要排除。
5. 再审阅主线程缓存读、重复监听、详情观察集合、原生对象释放和日志写盘阻塞，不直接取消后台下载。

### 15.2 seeds/peers 都是 0

1. 看有没有真实 Tracker，是否仍在元数据阶段、是否手动暂停。
2. 看具体 Tracker error/reply；区分端口黑名单、鉴权、TLS、超时、无 Peer。
3. 检查完整 metainfo 路径、代理地址是否指向手机本机而非电脑、域名解析和 Peer 代理选项。
4. 使用公开磁力或本地 Tracker 先分离 UI/native/网络问题；只有必要时才请求私有样本测试。
5. 不通过清除 private 标志、关闭更多安全功能或伪造 Peer 数掩盖问题。

### 15.3 磁力一直等待或取消后不能重加

检查 pending ID/hash 生命周期、仅元数据 flags、DHT 启动和 session state、临时 handle 是否存在、timeout 是否触发清理，以及取消/ready 回调顺序。先分离“网络无元数据”和“草稿状态卡死”，不要简单延长超时无限等待。

### 15.4 暂停后自动继续

分别验证 preferences 的暂停意图、native 的 PAUSED/AUTO_MANAGED，以及 restoreSources 如何覆盖恢复参数。检查是否有其它操作重新 add/resume，同步查看日志；不能只检查 UI 按钮文字。

### 15.5 重启后全量校验

核对对应 fast-resume 是否存在、更新时间/大小、是否加载成功、是否 fallback 到源重新创建；检查保存 alert 是否返回、写盘错误、文件移动/修改和用户强制校验操作。不要删检查点来“修复”慢恢复，也不要跳过真正必要的校验。

### 15.6 日志找不到

以设置显示的实际目录为准，确认自定义根目录下面有 `qBittorrent-Mobile-Logs` 子目录；检查回退警告、旧目录保留、权限、外部存储状态及是否同一个安装包数据。原生崩溃未留下 Java 栈是能力边界，不代表没有崩溃。

需要 adb 补充诊断时只指定授权目标。原始 logcat、preferences 和 fast-resume 可能含敏感内容；先在本地筛选/脱敏，不要直接整段贴入对话。链接含 `&` 时还涉及 Android shell 转义，PowerShell/JSON 引号不等于远端 shell 已正确转义。

## 16. 修改与交付规范

- 优先围绕用户当前请求做有限修改；诊断请求先诊断，文档请求只写文档。
- 先阅读适用的 AGENTS 指引；官方源码树默认仅参考，不把 Android 产物写进去。
- Android 源码由公开发布时创建的根仓库管理；修改前检查根仓库状态，保留已有工作，不要 reset/重建目录。上游子模块是另一份历史。
- 代码改动以可审阅的小补丁完成；不要编辑 app/build 下生成的 Java/class/dex 来代替修改 src。
- 影响线程/原生生命周期/恢复时，必须同时说明所有权、排序和取消边界。
- 不把所有异常吞掉后显示成功；不要以 UI 更顺畅为由隐去 Tracker 原始错误类别。
- 新功能增加针对性回归，记录设备/API、版本、输入是否公开、操作、结果及未测范围。
- 新 APK 增加 versionCode/versionName，同步启动日志版本、README 当前包、QA 和必要交接状态；归档新文件名并核对 hash。
- 构建通过、模拟器通过、用户手机通过分别写，不夸大测试覆盖。无法访问用户手机时明确说明。
- 输出给用户优先 APK/文档的可点击路径，简要说明改了什么、如何验证、有哪些未解决限制。
- 不在文档或提交中泄漏输入文件、Tracker passkey、代理密码、设备私有数据。

## 17. 下一位 AI 的接手清单

1. 读本文件第 1、2、14 节，确认项目真实架构、用户最新需求和测试边界。
2. 阅读 [README](android-app/README.md) 与 [QA](android-app/docs/QA.md)，区分历史结果和当前状态。
3. 检查实际版本和工作树；本文记录的是 2026-09-13 基线，后续源码可能已变。
4. 优先读 `TorrentEngine`、`UiRefresh`、`MainActivity`、`TorrentService`；处理日志时再读 `AppLog/ProcessDiagnostics/SettingsActivity`。
5. 有新崩溃反馈先读对应时间日志和退出原因，不重复猜测已排除的设置问题。
6. 制定最小改动与回归范围，不默认重写全部工程或升级内核。
7. 只在指定模拟器运行自动化；不用私有种子做常规冒烟，不碰真机生产数据。
8. 完成后更新 QA 的真实证据和本文状态，保留尚未确认的问题；交付版本化产物。

接手时最重要的一句话：**保住用户的任务与数据，沿用已经解决的交互和恢复约束，用真实日志定位下一处问题，不要把兼容身份、模拟器成功或 UI 显示当作完整正确性的证明。**
