# ZONOZ local stream · 开发交接说明（先读这个）

> **改任何播放链路代码前的强制流程（不得跳过）**
>
> 1. 先在 `SonosLocalCast/` 构建并装机：`.\build.ps1` → `.\install.ps1`
> 2. 再跑回归：`cd deploy; python regress_queue.py`，**七步全过**才允许继续改
> 3. 判据三条：`界面 fileId == 音箱 TrackURI fileId`、`NrTracks == 清单长度`、
>    第 3–6 步"当前曲的 fileId 前后必须一致"
> 4. 任何一步失败：先修到 7/7，再动新需求；日志证据贴进提交说明
>
> 原因：本项目最贵的教训是"界面与声音不一致"（点 A 播 B、进度错、错歌）。
> `regress_queue.py` 是唯一能在 3 分钟内证伪这条的脚本，跳过它等于盲改。
> 详见 §24.5。


工程本体在 `SonosLocalCast/`。本文只写「怎么跑、怎么改、哪些坑已经踩过」，协议细节见
`SonosLocalCast/README.md`，历史决策见 `.workbuddy/memory/`。

---

## 1. 这个 App 是什么

把手机里的本地音频，通过 **App 内嵌 HTTP 服务器 + 直连 Sonos 的 UPnP/SOAP** 推给音箱播放。
全程离线、纯局域网，不依赖 Sonos 官方 App、不过云、不需要账号。

```
App(NanoHTTPD :8080) ──SetAVTransportURI(URL)──► Sonos :1400 /AVTransport
        ▲                                              │
        └────────── GET /stream/<fileId>（Range）───────┘
```

包名 `com.sonoscast.local`，当前 **1.1.8 (versionCode 54)** —— 首个开源版本；应用名已从 SONOS LOCAL CAST 改为 ZONOZ local stream，minSdk 26 / targetSdk 35。

---

## 2. 本机工具链（已装好，别重装）

| 组件 | 路径 |
| --- | --- |
| JDK 17.0.2 | `%LOCALAPPDATA%\AndroidToolchain\jdk-17` |
| Gradle 8.9 | `%LOCALAPPDATA%\AndroidToolchain\gradle-8.9` |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`（platform 35 / build-tools 35.0.0 / platform-tools） |
| Gradle 缓存镜像 | 工作区 `.gradle-home/`（~2GB，从 `%USERPROFILE%\.gradle` 复制而来） |

本机没有 Android Studio，命令行构建即可。

---

## 3. 构建（**必须带这两个环境变量**）

```powershell
$env:JAVA_HOME       = "$env:LOCALAPPDATA\AndroidToolchain\jdk-17"
$env:GRADLE_USER_HOME = "C:\Users\C\Desktop\AI\SONOS本地播放apk开发\.gradle-home"

cd "C:\Users\C\Desktop\AI\SONOS本地播放apk开发\SonosLocalCast"
& "$env:LOCALAPPDATA\AndroidToolchain\gradle-8.9\bin\gradle.bat" assembleRelease --no-daemon --console=plain --offline
```

产物：`SonosLocalCast/app/build/outputs/apk/release/app-release.apk`
  （自签名 keystore；口令走 `local.properties` / 环境变量，不入库）

`assembleDebug` 同样可用（debug 也复用该 keystore）。

---

## 4. 沙箱环境的三个硬约束（踩过，别重复踩）

1. **`%USERPROFILE%\.gradle` 不可写** → Gradle 起不来，报
   `Failed to load native library 'native-platform.dll'`。
   解法：`GRADLE_USER_HOME` 指向工作区内 `.gradle-home`（已镜像好缓存），或用 `danger-full-access` 提权。
2. **Kotlin 编译守护进程连不上**（沙箱禁本地 socket）→ `Could not connect to Kotlin compile daemon`。
   解法：`gradle.properties` 已设 `kotlin.compiler.execution.strategy=in-process`。
3. **工作区之外只读** → APK 不能直接拷到 `C:\Users\C\Desktop\`（会被拒），交付物放
   `SonosLocalCast/dist/`。

另外：工程路径含中文，`gradle.properties` 里已设 `android.overridePathCheck=true` 放行（勿删）。

---

## 5. 真机调试

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices                                     # 手机需先开「USB 调试」或用无线调试配对
& $adb install -r "dist\ZONOZ-LOCAL-STREAM-v1.1.4.apk"
& $adb logcat -s SonosCast                          # App 的日志 TAG 就是 SonosCast

# 截图：adb pull 直接落到含中文的工作区路径会失败，先拉到 TEMP 再拷进来
& $adb shell screencap -p /sdcard/s1.png
& $adb pull /sdcard/s1.png "$env:TEMP\s1.png"
Copy-Item "$env:TEMP\s1.png" "deploy\shots\p1.png"
```

已知限制（HyperOS/小米）：
- `adb shell pm grant ... READ_MEDIA_AUDIO` **必定失败**（该权限带 USER_SENSITIVE_WHEN_GRANTED），只能用户在界面上手动授权。
- `adb shell input tap` 被拦（缺 INJECT_EVENTS），UI 自动化不可用，只能人工点。
- 截图偶尔全黑，等 1~2 秒重截即可。

---

## 6. 实测到的家庭环境

- 手机：Redmi K80 Pro = `24122RKC7C`（Android 16 / API 36），之前 IP `192.168.124.5`
- 电脑：`192.168.124.2`
- 音箱：**客厅一对 Play:1 立体声配对**（S2，固件 86.10-81100）
  - `.4` = `RINCON_347E5CCF347E01400` = **主箱 / RF / Coordinator**
  - `.3` = `RINCON_949F3EF0020601400` = **从箱 / LF / Invisible=1**
  - 对从箱发 `SetAVTransportURI` 返回 Sonos 私有码 **1023**，属正常，不是故障

验证音箱在线（电脑上直接发 SOAP，无需手机）：

```powershell
# 每台都应返回 200 + ZoneGroupState
curl.exe -s -X POST "http://192.168.124.4:1400/ZoneGroupTopology/Control" `
  -H "SOAPACTION: \"urn:schemas-upnp-org:service:ZoneGroupTopology:1#GetZoneGroupState\"" `
  -H "Content-Type: text/xml; charset=utf-8" `
  --data-binary '<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetZoneGroupState xmlns:u="urn:schemas-upnp-org:service:ZoneGroupTopology:1"/></s:Body></s:Envelope>'
```

---

## 7. 代码地图（8062 行 Kotlin / 29 个文件）

```
app/src/main/java/com/sonoscast/local/
├── MainActivity.kt            单 Activity，Compose 宿主；五页 AnimatedContent + 全局点击触感
├── SonosCastApp.kt            Application：通知渠道 + I18n.init
├── core/
│   ├── AppState.kt            全局运行时状态（object + StateFlow），Service 写 / UI 读
│   ├── SsdpDiscovery.kt       M-SEARCH 两轮（ZonePlayer → ssdp:all）+ 设备描述解析
│   ├── LocalMediaServer.kt    NanoHTTPD：Range/206/416/HEAD + 诊断首页 + 流定位
│   ├── SonosSoapClient.kt     AVTransport / RenderingControl / ZoneGroupTopology
│   ├── ZoneTopology.kt        解析 ZoneGroupState，判主从（Invisible / Coordinator）
│   ├── MusicLibrary.kt        MediaStore 扫描 + 排序 + A-Z 索引 + 文件夹过滤
│   ├── MediaRegistry.kt       fileId ↔ SAF Uri 映射（HTTP 只暴露 fileId）
│   ├── SoapXml.kt             SOAP 信封 + DIDL-Lite + 转义
│   ├── NetUtils.kt            局域网 IPv4（优先 wlan，防 VPN 抢路由）+ 多播锁
│   ├── I18n.kt / I18nTables.kt  8 语言（简中/繁中/英/日/韩/俄/西/法），127 键 × 8 表
│   ├── UiScale.kt             整体文字大小（乘在 fontScale 上，sp 全量生效）
│   ├── Favorites.kt           收藏（Track.id 集合 + SharedPreferences）
│   ├── NotifArt.kt            通知大图合成 ZONOZ 角标
│   ├── CoverLoader.kt         专辑封面 loadThumbnail + LruCache
│   ├── LogBus.kt              500 条环形日志（UI 调试页直接显示）
│   ├── UpnpErrors.kt          错误码 → 文案键（不向用户暴露数字码）
│   └── SonosDevice.kt         设备模型（含 isSatellite / channelRole）
├── service/
│   ├── SonosCastService.kt      前台服务：server+discovery+soap、701 重试、进度轮询、续播调度、媒体通知
│   └── AutoAdvanceReceiver.kt   续播闹钟落点（把被冻结的服务叫醒）
└── ui/
    ├── PlayerScreen.kt        播放页（大封面 + 模糊背景 + 传输控制 + 音量 + 设备 BottomSheet）
    ├── LibraryScreen.kt       曲库（搜索/排序/A-Z 索引条/长按队列/文件信息）
    ├── QueueScreen.kt         播放清单（真实播放顺序；开随机后即时重排；带封面）
    ├── TrackCover.kt          曲目封面（曲库与播放清单共用）
    ├── SettingsScreen.kt      设置首页（文件夹/语言/调试）+ 调试三级页（服务/设备/日志/手动 IP）
    ├── PlayerViewModel.kt     状态转发 + 指令下发
    ├── Strings.kt             str() / strf()，走 CompositionLocal
    └── theme/Theme.kt         纯黑白配色 + LocalStrings 下发
```

数据流单向：**UI → Intent → Service → 写 AppState → UI collect**。
`MediaRegistry` / `MusicLibrary` / `LogBus` / `I18n` 都是进程内单例。

---

## 8. 关键实现约定（改代码前必看）

- **DIDL-Lite 必须整段 XML 转义**后再塞进 SOAP 参数，少转一层 = 402。
- **HTTP `Content-Type` 必须与 DIDL `protocolInfo` 的 MIME 一致**，否则 714。两者都取 `MimeTypes.resolve()`。
- **服务描述里 `RenderingControl` 与 `GroupRenderingControl` 同时存在**，匹配必须精确到
  `:service:RenderingControl:`，否则 `SetVolume` 打到 Group 服务 → 401（历史真 bug）。
- **停止/暂停作用于 `activeDevices()`**（在播设备），不是 `selectedKeys`，否则取消勾选后停不掉。
- **进度只能轮询**（`GetPositionInfo`，2s 一次）：Sonos 是拉流播放，本地没有解码器。
- **`%s` 占位符**：i18n 文案统一 `%s`，调用方传字符串（`strf(key, ...)`）。
- Compose 里取文案 **必须**用 `str()` / `strf()`（不是 `I18n.t()`），否则切语言不重组。

### 8.1 后台续播为什么不能只靠轮询（1.8.0 的核心结论）

旧实现里"该放下一首了"完全依赖手机每 2s 发一次 `GetTransportInfo`。锁屏进入 Doze 后系统会
切断应用网络，请求全部失败 → 永远等不到 `PLAYING → STOPPED` 这个跳变 → 音箱播完就干等，
屏幕上点亮后网络恢复，下一轮才补推。**这是架构缺陷，不是参数问题。**

现在采用三层：

| 层 | 机制 | 依赖 | 作用 |
| --- | --- | --- | --- |
| ① | `Handler.postDelayed(剩余时长 + 1.5s)` | 进程活着 | 第一道，不走网络 |
| ② | `AlarmManager.setExactAndAllowWhileIdle` → `AutoAdvanceReceiver` → 拉起服务 | 精确闹钟权限 | 进程被冻结/Doze 也能叫醒 |
| ③ | 2s 轮询 | 网络 | 前台对账，纠正偏差 |

- ①② 共用 `autoAdvanceToken`，只有 token 匹配才执行，**保证只推进一次**。
- 切歌后有 10s 静默期（`AUTO_ADVANCE_DEBOUNCE_MS`），防止轮询读到旧的 STOPPED 再跳一首。
- `WakeLock/WifiLock` 每 30 分钟续期一次（原来只在推送时续，4 小时后会失效）。
- 调试页会提示"电池优化未排除 / 精确闹钟未授权"，这两个都会削弱锁屏续播。
- 后续若要彻底摆脱手机，走 Sonos 原生队列（`AddMultipleURIsToQueue` + `SetPlayMode` +
  `x-rincon-queue:`），随机/循环也交给音箱执行（见 1.8.0 计划里的方案 B）。

### 8.2 曲库扫描：为什么必须用 SAF（1.8.0 实测教训）

Android 11+ 的分区存储对 App 是**过滤视图**：没进 MediaStore 的文件，`File.listFiles()`
也列不出来（实测：新拷进来的 `黄昏.mp3`/`奔跑.mp3` 在 MediaStore 里没有，`listFiles()` 同样看不到）。

所以正确姿势是 `DocumentsProvider`：

1. `resolveFolderTree()` 拿 SAF tree URI（用户选目录时存下来；老安装按 `primary:Music` 规则反推）；
2. `DocumentsContract.buildChildDocumentsUriUsingTree` 递归枚举（系统侧权限，看得见真实文件）；
3. 与 `MediaStore.DATA` 求差集 → `MediaScannerConnection.scanFile` 补扫；
4. 仍不入库的 → 用 SAF document URI 直接建 `Track`（`content://` 可被 `ContentResolver` 打开，
   封面走 `MediaMetadataRetriever.embeddedPicture`）。

### 8.3 S1 兼容预埋（1.8.0）

- 判别：设备描述 `<swGen>1</swGen>` / 拓扑 `SWGen="1"` → `SonosDevice.swGen = 1`。
- 本版对 S1 **只发最小指令集**（`SetAVTransportURI` + `Play/Pause/Stop` + `RenderingControl.SetVolume`），
  不发任何 Sonos 扩展动作，避免踩未知坑。
- 401/402/501/602 在 S1 上视为"此动作不支持"，只记日志引导反馈。
- SSDP 现在**总是**补一轮 `ssdp:all`（S1 老固件常常只对它应答），两轮按 LOCATION 去重。
- 界面：设备行显示 `S1`/`S2`，调试页显示 `modelName · 代次 · softwareVersion`。
- 还没有真机验证（家里只有 S2 的一对 Play:1）。

---

## 9. 当前状态与未完成项

**已完成并真机验证**：SSDP 发现、HTTP Range 四类边界、双箱推送、主从识别、音量、通知栏媒体卡片、
曲库扫描、播放模式、下一首插播队列、进度拖动、文件夹过滤、进程死后从音箱恢复状态、自动续播。

**1.7.1**：把上一轮中断的 i18n 重构收尾（此前工作树**编译不过**）——
`Hint` 改为文案键、`LocalStrings` 真正下发、`I18n.init` 接入 Application、补 `ERR_FOLDER` 键。

**1.7.2**：i18n 收尾完成 —— 新增 17 个键（播放模式、复制/展开/收起、采样率、未知专辑、
未命名曲目、存储根目录、已选文件夹、无音频权限、关于正文、退出、端口/音箱数），
把 `LibraryScreen` / `DebugScreen` / `PlayerScreen` / `MusicLibrary` / 通知栏里残留的硬编码中文
全部接入语言表；修掉西语 `ERR_REACH` 里 `/n` 的笔误；删除从未被引用的 `AppState.describeDeviceStatus`。

**1.7.2 真机实测（Redmi K80 Pro / Android 16，2026-10-26）**：
- 启动即从主箱恢复播放状态（`VI. Waltz 2…`，PLAYING，3:15）—— 补上了 1.7.0「PLAYING 分支待实测」的缺口
- 一首放完后自动按模式续播（切到 Destiny 2 OST），通知卡片同步更新
- 通知 subText 正确渲染 `2 台音箱 · 端口 8080`（验证 `I18n.f` 的 `SPEAKERS_N` / `PORT_N`）
- PC 直连手机 `:8080` 验证：`/health` 200、`/` 200、`Range: bytes=0-99` → 206 + `Content-Range: bytes 0-99/5722808`、
  越界 → 416 + `bytes */5722808`、HEAD/GET → 200 + 正确 `Content-Length`
- 切 English 后播放页渲染 `Library / 69 tracks`，UI 进度与音箱 `GetPositionInfo` 一致 →
  `I18n.init` + `LocalStrings` + `str()/strf()` 整条链路证真

**1.8.0（2026-10-26，本轮六项）**：
1. 页面切换动画：`AnimatedContent` 加方向感知 `transitionSpec`（280ms 进 / 240ms 出 + 淡入，`SizeTransform(clip=false)`）
2. 后台续播：本地计时器 + 精确闹钟双保险 + 30min 锁续期 + 电池/闹钟引导 + 切歌静默期（详见 8.1）
3. S1 预埋：`swGen` 解析、代次徽标、最小指令集、SSDP 补扫、错误码识别（详见 8.3）
4. 触感：音量按 1% 刻度、进度按 1% 步进触发 `CLOCK_TICK`，松手 `CONFIRM`
5. 播放模式重构：`shuffle: Boolean` + `RepeatMode{OFF,ALL,ONE}`，UI 变为
   `[随机][上一曲][播放][下一曲][循环]`，两者互斥；移除停止键
6. 曲库自愈：SAF 枚举 → MediaScanner 补扫 → SAF 直读兜底（详见 8.2）

**1.8.0 已在真机验证**：
- 曲库 69 → **71 首**，`黄昏.mp3` / `奔跑.mp3` 出现在列表（日志：`SAF 枚举到 71 个音频文件`
  → `媒体库缺失 2 首，触发系统扫描：奔跑.mp3、黄昏.mp3` → `媒体扫描已完成` → `曲库已加载：71 首`）
- MediaStore 里已出现这两条记录（`content query` 确认，is_music=1，时长 195s / 344s）
- 播放页布局正确：左随机（灭）、右循环（灭），曲库入口显示 `71 首`
- 暂停状态下**不会**误排续播（日志无 `已排续播`，符合预期）
- 启动无崩溃、无 ANR，SSDP 两轮扫描正常（5 个响应 → 2 台 Sonos）

**1.8.0 仍待人工验证**（`adb input tap` 被 ROM 拦，无法自动化）：
- 锁屏续播：播一首短歌 → 锁屏 → 等它放完，观察音箱是否自动接下一首；
  `adb logcat -s SonosCast` 应出现 `续播计时器（本地）触发` 或 `续播计时器（闹钟唤醒）触发`
- 动画方向与手感（进出曲库/设置）
- 音量条、进度条拖动的震动
- 随机/循环按钮的互斥点亮状态
- 真机 S1 设备（当前没有）

**1.8.2（2026-10-26，六项体验问题）**：
1. 全局点击触感：在 `MainActivity` 根布局挂 `globalTapHaptics()`，用 `PointerEventPass.Initial`
   旁听指针事件（不消费），按下→抬起位移 < 28px 且 < 600ms 就发一次 `CLOCK_TICK`。
   新增界面自动具备反馈，不用逐个数按钮。
2. 设置收敛：`DebugScreen` 拆成 `SettingsScreen`（音乐文件夹 → 语言 → 调试）+ `DebugDetailScreen`
   （运行中服务 → 设备信息 → 运行日志 → 手动输入音箱 IP）。
3. 播放页底部一分为二：左「播放清单」、右「曲库」，各自显示数量。
4. 新增 `QueueScreen`：显示真实播放顺序，点任意一首跳过去播。
5. 播放模式严格互斥：`RepeatMode` 去掉 `OFF`（只剩 ALL/ONE），随机亮 → 循环灭，随机灭 → 循环必亮；
   循环键只切列表/单曲，点它同时退出随机。
6. 切歌只认播放清单：`AppState.queue + queueIndex` 成为唯一依据，
   `playRelative` 不再直接逛曲库；单曲循环下上/下一首都停在当前曲。

**1.8.2 已验证**：构建通过；启动无崩溃；`曲库 71 首`；恢复播放时自动重建清单（71 首）；
58 秒后自动续播并走队列（日志 `下一首 →「Russia (The Atomic Era)」（列表循环）`）；
播放页截图确认底部双入口与「随机灭 / 循环亮」的互斥状态。

**仍未完成**：
- 家里的音箱全是 S2，S1 只做了"识别 + 降级 + 诊断"，没有任何真机数据。
- 精确闹钟权限在 targetSdk 35 下默认不授予，需要用户在系统设置里允许（调试页有入口）。
- `adb shell input tap` 在本机被拦（缺 `INJECT_EVENTS`），UI 交互只能人工验证。
- 诊断用的 HTML 首页（`LocalMediaServer.handleIndex`）仍是中文；日志中文属有意保留。
- 没有播放列表/专辑连续播放（只有单曲 + 插播队列）；不支持 Sonos 原生分组（`x-rincon`）。
- 上架阻断项：`usesCleartextTraffic=true` 全局放行（技术上必须，音箱只支持 HTTP，见 §8）、
  没有隐私政策页面。
  （商标 2.0.0 已解决；keystore 口令硬编码与 `isMinifyEnabled=false` 已在 1.0 开源版修掉，见 §17）

## 10. 黑底一致性（1.8.1）

界面全黑，但系统层面还有几处会露出浅色，1.8.1 已处理：

| 位置 | 原值 | 现值 | 说明 |
| --- | --- | --- | --- |
| `values/themes.xml` · `windowBackground` | `#FBF8FF` | `#000000` | 页面滑动过渡时两页之间露出的就是它 → 一条白边 |
| `values/themes.xml` · `colorBackground` | 继承 Light 主题 | `#000000` | 某些场景走这个属性 |
| `values-night/themes.xml` | `#121318` | `#000000` | 夜色下也统一纯黑 |
| `values-v31/themes.xml` · `windowSplashScreenBackground` | 未设置（系统默认浅色） | `#000000` | Android 12+ 启动画面单独一套属性，**不认 windowBackground** |
| `MainActivity.setContent` | — | 外层加纯黑 `Box` | Compose 层兜底，任何"露底"瞬间都是黑 |

**仍然存在的一处白**：`mipmap-anydpi-v26/ic_launcher.xml` 的 adaptive icon 背景是
`@color/ic_launcher_background = #FFFFFF`。HyperOS 的「应用打开动画」会拿图标底色铺成一张
白色圆角卡片（录屏实测：冷启动 0.45s 处白像素占比 87%）。要彻底消掉必须把图标底色改成深色
并重画前景字形 —— 属于品牌改动，等确认后再做。

取证方式（可复现）：`adb shell screenrecord` 录屏 → `ffmpeg -vf "negate,blackframe=amount=0:threshold=24"`
统计每帧近白像素占比，白像素突增即为白闪。注意 HyperOS 需要屏幕处于亮屏解锁状态，
否则 screenrecord 会报 `INVALID_LAYER_STACK`。

## 11. 播放清单与播放模式（1.8.2）

### 11.1 唯一的播放真相：`AppState.queue`

```
queue: List<Track>        // 播放顺序（展示顺序 = 播放顺序）
queueIndex: Int           // 当前播到第几首
queueSource: List<Track>  // 未打乱的原始顺序（关随机时还原用）
```

- **点歌**：`startQueue(source, track)`，`source` 是用户眼前那个列表（曲库 / 搜索结果 / 排序后）。
  随机开启时 → 当前曲置顶 + 其余打乱。
- **下一首/上一首**：`step(offset)` 按清单循环走，**不再直接逛曲库**。
  1.8.2 之前 `playRelative` 用 `MusicLibrary.neighbourXxx`，所以关掉随机也照样跳下一首 —— 那是 bug。
- **单曲循环**：`playRelative` 直接重播当前曲，上/下一首都不离开当前曲。
- **切随机**：`setShuffle` 立刻 `reorderQueue()`，界面上的播放清单马上跟着变。
- **长按"下一首播放"**：`insertNext(track)` 插到当前曲之后（不再是独立的小队列）。
- 进程被杀后 `restorePlaybackState` 会按曲库重建一条清单，保证按钮不失效。

### 11.2 互斥规则

| 随机 | 循环 | 说明 |
| --- | --- | --- |
| 亮 | 灭 | 按打乱后的清单顺序走 |
| 灭 | 亮（列表 / 单曲） | 按曲库顺序走；单曲则停在当前 |

不存在"两个都灭"的状态：`RepeatMode` 只有 `ALL` 和 `ONE`，关随机就回到上次的循环方式。

### 11.3 页面层级

```
播放页 ──┬── 曲库
         ├── 播放清单
         └── 设置 ── 调试
```

`MainActivity.screenDepth()` 决定滑动方向，`BackHandler` 里调试页回设置、其余回播放页。


### 11.4 单曲循环下的清单收敛（1.8.3）

单曲循环时 `orderFor()` 直接返回 `listOf(current)`，所以：

- 播放清单里只会有**当前这一首**（之前显示 71 首、实际只播 1 首，属于"界面说谎"）
- 切回列表循环时用 `queueSource` 还原完整顺序
- 单曲循环下长按"下一首播放"会被 `insertNext()` 拒绝（插队没有意义）

其余两处 1.8.3 改动：
- 播放清单每行加了专辑封面（`TrackCover.kt`，与曲库共用同一份 LruCache）
- 设置页「音乐文件夹」一行只显示**完整系统路径**（`/storage/emulated/0/Music`），
  「选择 / 清除」移进点击后的二级 BottomSheet，三个栏目宽度因此一致

### 11.5 1.8.4 / 1.8.5（体验对齐与两个真 bug）

**1.8.4**
- 音乐文件夹的二级菜单改成和语言栏一样的 `DropdownMenu`（原来是 BottomSheet，两处观感不一致）；
  菜单里不再重复显示路径（上一级那一行已经有完整路径了）
- 曲库 / 播放清单改为**从下往上**滑入（抽屉感），设置仍是从右向左（导航层级感）。
  `MainActivity.transitionSpec` 里按 `targetState/initialState` 是否属于 LB/QU 来选竖向还是横向

**1.8.5（贴图里发现的真 bug）**
- 现象：标题显示成 `You&apos;re Beautiful`，而且播放清单是"空"
- 根因：`GetPositionInfo` 的 `TrackMetaData` 是**两层转义** ——
  `&lt;DIDL-Lite&gt;&lt;dc:title&gt;You&amp;apos;re Beautiful&lt;/dc:title&gt;`。
  旧代码只解了一层，正则取出的标题里仍残留 `&apos;`。
  连带后果：按标题回曲库找歌永远匹配不上 → `MediaRegistry.current` 为空 →
  `restorePlaybackState` 里"顺手重建播放清单"的分支被跳过 → 清单空。
- 修法：`SonosSoapClient.getTransportSnapshot` 里对取出的 title **再解一次**。
- 验证：重启后日志 `按标题在曲库找回：A Little Piece` + `重建播放清单（71 首）`

## 12. 文字大小与 1Hz 进度（1.8.6 / 1.8.7）

### 12.1 整体文字大小

`core/UiScale.kt` + `theme/Theme.kt`：

- 档位固定 4 个：`0.85 / 1.0 / 1.15 / 1.3`（小 / 标准 / 大 / 特大），存 SharedPreferences
- 应用方式：`CompositionLocalProvider(LocalDensity provides Density(density, fontScale * uiScale))`
- **只放大 fontScale，不动 density**：所有 `sp` 字号（含 Material 组件排版）整体缩放，
  而 `dp`（图标、间距、圆角）保持不变，放大后布局不会被撑变形
- 与系统字体缩放是乘法叠加（跟随系统再按 App 微调）
- 多语言：系数与语言无关，8 种语言套同一倍数；文案长短差异靠既有布局消化
  （列表行 `maxLines` + ellipsis、弹窗内容可滚动）
- 设置页顺序：音乐文件夹 → 语言 → 文字大小 → 调试

### 12.2 进度读秒改成 1Hz（不增加请求量）

`SonosCastService.startProgressPolling()` 原来是 `delay(2000)`，所以左侧读秒 2 秒才跳一次。

改法：循环节拍降到 **1 秒**，但每隔一拍才真正向音箱发 SOAP：

```kotlin
val sampled = tick % PROGRESS_SAMPLE_EVERY_TICKS == 0   // 每 2 拍采样一次
if (sampled) { GetPositionInfo(...) 覆盖 positionSec }
else { positionSec += 1 }                               // 中间那拍本地插值
```

- 显示 1Hz，网络请求量与原来完全一致（仍是 2 秒一轮 `GetPositionInfo` + `GetTransportInfo`）
- 每次真实采样都会覆盖插值结果，不累积漂移；超过总时长就不再涨
- 验证方式：1 秒间隔连拍 7 张，裁剪进度条区域做哈希，**相邻帧全部不同**（2Hz 时会出现"相同/不同"交替）

### 12.3 顺带修的文案

- `Str.SETTINGS` 七种语言全部从"设置与诊断 / Settings & diagnostics"改成纯"设置"，
  因为诊断已经挪到三级页
- 设置页「调试」条目的副标题改走新键 `Str.DEBUG_HINT`（"运行状态、设备与日志"），
  不再用 `SERVICE_ADDR · DEV_DIAGNOSTICS · NO_LOGS` 硬拼

## 13. 繁體中文与索引条触感（1.8.8）

### 13.1 新增繁體中文（台灣用語）

- `Lang` 枚举里 **紧跟简体中文** 插入 `ZH_TW("zh-TW", "繁體中文")`，
  语言菜单是按 `Lang.values()` 渲染的，所以它天然排在「简体中文」正下方
- 新增 `ZH_TW` 词表（120 条，全量对齐，不是机器转换）：
  - 用字按台湾规范：設定 / 資料夾 / 檔案 / 資訊 / 搜尋 / 音訊 / 位元率 / 取樣率 /
    韌體 / 記憶體 / 網路 / 複製 / 收合 / 偵錯
  - 硬件名词：喇叭（speaker）、主喇叭 / 衛星喇叭（立體聲配對的從設備）、埠（port）
- **系统语言判定**（`Lang.systemDefault()`）不再只看 `Locale.language`（简繁都是 `zh`）：
  先按完整 tag 匹配，再看 `script == Hant` 或 region ∈ {TW, HK, MO} → 繁體，其余中文 → 简体
- 英文/日文等回退逻辑不变：`fromTag(language) ?: EN`

### 13.2 A-Z 索引条加触感

`AlphaIndexBar` 是自己用 `awaitEachGesture` 处理指针的（不用 `clickable`），
所以**全局点击触感覆盖不到它**——这也正是"滑动没震动"的原因。

修法：按下时震一次，之后每跨过一个字母再震一次（`HapticFeedbackConstants.CLOCK_TICK`），
手感是"一格一格"的刻度，和音量/进度条一致。

## 14. 收藏夹与通知角标（1.8.9）

### 14.1 收藏（喜愛）

- `core/Favorites.kt`：只存 `Track.id` 的集合，SharedPreferences 持久化。
  MediaStore 的 `_ID` 与曲库重扫无关，SAF 兜底条目用路径哈希生成（也是稳定的），
  所以刷新曲库不会丢收藏。
- 交互：
  - 曲库长按菜单新增「喜爱 / 取消喜爱」（`FAVORITE` / `UNFAVORITE`）
  - 已收藏的曲目在**封面右下角**叠一个白色实心爱心，尺寸 9dp ≈ 封面的 1/6
  - 排序菜单（右上角漏斗）底部用分割线隔出「喜爱的音乐」筛选项，勾选后只显示收藏
  - 筛选与搜索是**与**关系：先过滤收藏，再过滤搜索词
- 文案：`FAVORITE` / `UNFAVORITE` / `FAVORITES`，8 种语言各一条

### 14.2 播放页底部入口居中

`EntryCard` 的 Column 加 `horizontalAlignment = CenterHorizontally`，
数量那行加 `textAlign = Center`。两个入口（播放清单 / 曲库）的标题与数字都居中。

### 14.3 通知专辑图上的 "SONOS LC" 放大

**先搞清楚它是什么**：Android 13+ 的媒体通知会把 App 的 **small icon** 以浅色
monochrome 叠加在专辑图右下角 —— 那个小字就是 `ic_stat_lc.png`，不是专辑图本身的内容。

- 旧图里 "SONOS / LC" 只占画布约 45%，叠上去后大约只有专辑图的 1/8
- 重写 `deploy/make_stat_icon.py`：文字块放大到画布 ~90%（左右留 4%），
  白色填充 + 黑色描边保证浅色封面上也可读
- 重新生成 5 个密度的 `drawable-*/ic_stat_lc.png`
- 生成脚本可复跑：`python deploy/make_stat_icon.py`（需要 Pillow）

> 注意：曲目**没有内嵌封面**时，系统会拿 App 图标当占位图（白底 SONOS/LC），
> 那是 `mipmap-*/ic_launcher.png`，与这里的 small icon 是两套资源。

## 15. 品牌更名 ZONOZ local stream（2.0.0 / 2.0.1）

### 15.1 改了什么

| 位置 | 旧 | 新 |
| --- | --- | --- |
| 应用名 `strings.xml/app_name` | SONOS本地推播 | **ZONOZ local stream** |
| 播放页顶栏 | SONOS + LOCAL CAST | ZONOZ + LOCAL STREAM |
| 关于弹窗标题 | SONOS LOCAL CAST | ZONOZ local stream |
| 通知/媒体会话兜底标题 | SONOS LOCAL CAST | ZONOZ local stream |
| 诊断网页 `<title>/<h1>` | Sonos 本地媒体服务 | ZONOZ local stream · 本地媒体服务 |
| 桌面图标 | 白底 SONOS + LC 两行 | 白底 **ZONOZ** 单行（`deploy/make_icons.py` 重写） |

- 包名 `com.sonoscast.local` **没动** —— 改了会导致已装用户无法覆盖升级，且与品牌无关。
- 日志 TAG 仍是 `SonosCast`（`LogBus.TAG`），方便沿用既有的 `adb logcat -s SonosCast` 习惯。

> 顺带把「商标风险」这条去掉了：应用名与图标都不再出现 SONOS 字样。
> 代码/文档里仍会提到 "Sonos" 的地方，都是在描述**音箱设备**（协议、机型），不是品牌。

### 15.2 通知专辑图上的角标（2.0.1）

上一版把 `ic_stat_lc` 的文字放大了，但位置/大小仍由系统决定。这一版改成**完全自己画**：

1. `res/drawable/ic_stat_blank.xml`：**完全透明**的 small icon。
   原因：Android 13+ 会把 small icon 叠在专辑图右下角，位置大小改不了；
   置空后系统那层就消失了。
2. `core/NotifArt.kt`：把封面居中裁成正方形，再在右下角合成品牌角标
   - 角标边长 = 封面 **31.25%**（2.0.1 是 25%，2.0.2 按"再大 25%"调整）
   - 距右下边缘 = 封面 **7%**（不贴边，留间距；这一项始终没动）
   - 白底圆角方块 + 黑色 ZONOZ，和桌面图标同一套视觉；字距同样取字号的 12%
3. `SonosCastService.buildNotification()` 用 `NotifArt.withBrandBadge(cover)` 作为 large icon。

**副作用**：状态栏不再有本应用的图标（small icon 是透明的）。
换来的是通知/锁屏卡片里只有一个我们设计好的角标。

另外补了个小修：封面缓存只在 UI 里填，Service 起通知时常常拿不到 →
新增 `loadCoverForNotification()`，Service 自己解一张封面再刷新通知
（否则重启进程后通知里长期没有专辑图）。

### 15.3 收藏爱心

22dp 太抢眼 → 2.0.2 改成 **13dp**，内边距 4dp → **2dp**。
（1.8.9 首次实现是 9dp/1dp，太小且贴边；22dp 又太大，13dp 是最终值。）

### 15.4 字距统一（2.0.2）

桌面图标与通知角标都改成「字距 = 字号 × 0.12」：
原来图标用 `0.035 × 画布`（约字号的 16%），缩近 25% 后取 12%。
`make_icons.py` 的 `SPACING_EM` 与 `NotifArt.SPACING_EM` 是同一个数值 —— 改一处要同步另一处。

## 16. 设置页的「后台保活」一行（2.0.4）

### 16.1 问题

1.8.2 把设置页收敛成「音乐文件夹 → 语言 → 调试」时，保活引导被留在**调试三级页**里，
而且是**条件渲染**：

```kotlin
if (!batteryExempt || !exactAlarmAllowed) { ...引导与按钮... }
```

于是出现了两种"消失"：

1. 位置深了两层（设置 → 调试 → 运行中服务），日常根本看不到；
2. 一旦用户真的去授权成功，`batteryExempt && exactAlarmAllowed` 都成立，
   整块引导**自动隐藏**——看起来像被删掉了。
   （本轮实测：`dumpsys deviceidle whitelist` 里有本应用，`appops SCHEDULE_EXACT_ALARM: allow`，
   所以那一段确实不显示。）

### 16.2 现在（1.0 起位于**调试页**）

**「后台保活」是调试页的第一张卡**（`设置 → 调试 → 后台保活`），常驻显示：

| 状态 | 副标题 | 点击行为 |
| --- | --- | --- |
| 两项都就绪 | `已排除电池优化 · 精确闹钟已授权`（灰字） | 不作动作 |
| 缺电池优化 | `未排除电池优化`（橙色） | 直接拉起系统「忽略电池优化」对话框 |
| 缺精确闹钟 | `精确闹钟未授权`（橙色） | 直接拉起系统「闹钟和提醒」设置 |
| 两者都缺 | 两句话用 ` · ` 连接 | 依次拉起两个对话框 |

> 2.0.4 曾放在设置首页，1.0 版按用户要求**移回调试目录**：
> 设置首页只留 音乐文件夹 / 语言 / 文字大小 / 调试 四项。

- 状态读取封装在 `readKeepAliveState()`，`rememberKeepAliveState()` 在
  **每次 ON_RESUME** 重读一次（用户从系统设置返回后不会显示过期状态）。
- `SettingsRow` 新增 `subtitleColor` / `iconTint` 两个可选参数，用于橙色告警态。
- 新文案键：`KEEPALIVE` / `KEEPALIVE_OK` / `KEEPALIVE_BATTERY` / `KEEPALIVE_ALARM`（8 语言，共 127 键）。

## 17. 开源发布（1.0）

### 17.1 版本与署名

- `versionName = "1.0"`、`versionCode = 27`（对外从 1.0 起算，versionCode 保持单调递增以便覆盖升级）
- 作者改为 **大气放电现象**（8 语言 `Str.AUTHOR`）
- 应用名 **ZONOZ local stream**，图标白底黑字 ZONOZ

### 17.2 仓库卫生（这几条是给所有公开仓库的模板）

| 项 | 处理 |
| --- | --- |
| keystore | **移出仓库**并写进 `.gitignore`；签名改为读 `local.properties` / 环境变量，缺省回退工程内文件 |
| 口令 | 不再硬编码，`signingProp()` 三级回退：local.properties → 环境变量 → 默认值 |
| R8 | `isMinifyEnabled = true` + `isShrinkResources = true`，APK **11.0 MB → 1.5 MB** |
| 无用资源 | 删掉 `ic_stat_lc.png`（5 个密度）、`ic_stat_sonos.xml`（品牌改造后已无引用） |
| 大文件 | `deploy/nocturne_via_http.mp3`(9.3MB)、历史截图 ~15MB 全部排除 |
| 个人数据 | AGENTS.md 里的内网 IP / RINCON ID / 手机序列号已脱敏；`.workbuddy/` 不进公开树 |
| 文档 | 新增根 `README.md`（面向使用者）、`LICENSE`(MIT)、`SonosLocalCast/local.properties.example` |
| CI | `.github/workflows/build.yml`：push 时自动 `assembleDebug` 并上传 artifact |
| 工具 | `deploy/make_icons.py` → `tools/make_icons.py`（路径改为相对仓库根，跨平台字体探测） |

### 17.3 R8 开启后的实测（重要）

担心反射踩坑，所以逐项验过：

- 启动无崩溃，前台服务正常，`HTTP 媒体服务已启动`
- 曲库扫描正常：`曲库已加载：71 首`
- SSDP 正常：`收到 5 个响应，其中疑似 Sonos 2 个`
- **NanoHTTPD 在 R8 下工作正常**（这是最担心的一块）：
  `GET /health` → 200 `OK`；`Range: bytes=0-99` → **206 + `bytes 0-99/5722808`**；
  `HEAD` → 200 + `Content-Length: 5722808` + `audio/mpeg`
- 因此 `proguard-rules.pro` 里对 `fi.iki.elonen.**` 整包保留是必要的

### 17.4 干净副本自检

把源码复制到一个空目录（不含 keystore / local.properties / build / dist），用系统 Gradle 编译
`assembleRelease` → **BUILD SUCCESSFUL**，说明公开仓库的内容是自洽的，不依赖本机私有文件。

> 注意：本机沙箱里 `assembleDebug` 会失败（`C:\Users\C\.android\debug.keystore.lock` 拒绝访问），
> 那是沙箱只读导致的，GitHub Actions 的干净环境没有这个问题。

### 17.5 交付物

`release/` 下分四份（工作区内的副本，最终拷到桌面）：

1. `1-源码（上传到 GitHub）` —— 78 个文件 / 0.6 MB，可直接作为仓库根内容
2. `2-APK（传到 Releases）` —— `ZONOZ-LOCAL-STREAM-v1.0.2.apk`（1.51 MB）+ `sha256.txt`
3. `3-上传教程.md` —— 保姆级 GitHub 上传步骤
4. `4-签名密钥（自行保管）` —— keystore 备份 + 说明，**绝不能上传**

## 18. 1.0.1 修掉的三个真 bug

### 18.1 「下一首播放」永远没反应

```
旧：if (list.any { it.id == track.id }) return false   // 已在清单就放弃
```

从曲库点歌时清单就是整个曲库，所以**每一首**都在清单里 → 这个菜单项等于摆设。

新逻辑（`AppState.insertNext`）：把目标曲从原位置**摘掉**，再插到 `queueIndex + 1`。
只有两种情况不动作：它正在播、或者它本来就在下一位。

### 18.2 A-Z 索引对中文无效

旧的 `sectionIndex` 取标题首字符判断 `A..Z`，汉字的码位在拉丁字母之后 → 全落 `#`，
排序又是 `String.CASE_INSENSITIVE_ORDER`（按 UTF-16 码位），于是中文全堆在 Z 之后。

新做法（不引入字典、不依赖 ICU）：
- **首字母**：把字转成 GBK 字节，利用 **GB2312 一级汉字区（0xB0A1–0xD7F9）按拼音编码**这一特性，
  用一张 23 个边界码表反推首字母（拼音无 I/U/V）。
- **排序**：`compareBy(letterRank(indexLetter(title)))` + `thenComparator { Collator(CHINA) }`，
  保证"列表顺序"和"索引条跳转"一致。
- 实测：`奔跑→B`、`大城小爱→D`、`故乡→G`、`花田错→H`；
  曲库加载时会把分组结果打进日志（`A-Z 分组：ABC…`），方便以后排查。

### 18.3 歌没放完就跳下一首（最严重）

两个原因叠加：

1. **排续播用错了时长**。推送成功后立刻 `scheduleAutoAdvance()`，那一刻
   `AppState.durationSec` 还是**上一首**的值（要等 2s 轮询才刷新），
   于是"还剩多久"按上一首算，定时器提前触发。
2. **音箱提前停止被当成播完**。网络抖动/音箱自己停 → `PLAYING → STOPPED`，
   旧逻辑无条件切歌。

三层修法：

| 层 | 内容 |
| --- | --- |
| ① 时长来源 | 优先 `Track.durationMs`（曲目元数据），全局轮询值只作兜底 |
| ② 到点核实 | `verifyThenAdvance()`：定时器触发后先问一次真实进度，没到结尾就按真实剩余重排 |
| ③ 提前停止 | `castAtMs` / `castDurationMs` 记下"何时推的、多长"，STOPPED 时差 20s 以上判为异常 → 先 `Play` 续上（最多 2 次），不切歌 |

日志里对应 `续播计时器提前到点（…）` 与 `音箱提前停止（已播 …s / 共 …s）`。

## 19. 1.0.2 修掉的三个"外部控制不同步"

### 19.1 机身按键暂停，App 不跟着变（进度 1:27↔1:28 抖动）

轮询一直在跑，但只更新了 MediaSession，**没更新 AppState.deviceStatus**：
- 播放按钮仍按 PLAYING 渲染；
- 2s 采样把秒数拉回 1:27，本地插值又推到 1:28 → 横跳。

修法：新增 `syncTransportState()`，把 PLAYING / PAUSED_PLAYBACK 双向同步回 AppState，
暂停时 `cancelAutoAdvance()`；本地插值只在"确实在播"时做（判断依据也换成状态而非设备发现）。

### 19.2 机身音量键，App 滑块不动

音量一直是只写不读。新增 `syncVolume()`，每约 8 秒读一次 `RenderingControl.GetVolume`；
App 自己刚设过音量的 3 秒内跳过（`VOLUME_SYNC_GUARD_MS`），避免和拖动打架。

### 19.3 机身"下一首"失效 → 改用 Sonos 原生队列

根因是架构：只推单条 URI 时音箱**没有队列**，机身按键无处可切。

`SonosSoapClient` 新增：`removeAllTracksFromQueue` / `addMultipleUrisToQueue` /
`setQueueTransport` / `setPlayMode` / `seekToTrack` / `nextTrack` / `previousTrack` /
`getQueueTrackNumber`。

`SonosCastService.castNativeQueue()`：
RemoveAll → AddMultiple（每批 12，失败对半拆）→ SetAVTransportURI(x-rincon-queue:UUID#0) →
SetPlayMode → Seek(TRACK_NR) → Play。

- 机身按键切歌靠 `GetPositionInfo` 的 `<Track>` 变化发现（`syncQueueTrack()`）
- `playRelative` 在队列模式下改发 Next/Previous
- 队列模式**关掉本地续播计时器**，由音箱自己续播（也就根除了"提前切歌"那一类问题）
- 单首清单 / S1 / 任何 SOAP 失败 → 自动退回逐首推 URL

真机验证脚本：`deploy/queue_probe.py`（push 队列 / next / pause / volume / status）。
### 19.4 1.0.3：双击播放键仍无效的原因（漏改第二条入口）

App 有**两条**播放入口：

| 入口 | 触发场景 | 1.0.2 状态 |
| --- | --- | --- |
| `startTrack()` | 上一首 / 下一首 / 自动续播 | 已走原生队列 |
| `castCurrent()` | **曲库点歌 / 清单点歌 / 通知栏播放** | 仍是单曲推送 ← 漏了 |

平时点歌走的正是 `castCurrent()`，所以音箱一直没有队列。
Sonos 文档对双击播放键的措辞是 "press twice to skip a track (when applicable)" ——
没有队列时就是 not applicable，表现就是"只会播放/暂停"。

修法：`castCurrent()` 同样先 `castNativeQueue()`，失败再 `performCast()`；
四个降级分支（清单 < 2 首 / IP 未知 / 端口未就绪 / 无可用主箱）都补了日志。
### 19.5 1.0.4：入队一次 24 首超限（音箱橙灯连闪的真正原因）

1.0.3 修好入口后，入队这一步自己失败了：`UPnP 402`。

真机实测的 `AddMultipleURIsToQueue` 上限（Play:1 / S2 86.10）：

| 数量 | 结果 |
| --- | --- |
| 8 / 12 / 16 | 200 |
| 18 / 20 / 22 / 24 | 402 / 500 |

修法：`QUEUE_CHUNK_SIZE` 24 → **12**，并加 `enqueueChunk()` 对半拆分重试（拆到单条为止）。

另外把「队列确实建成」的判据记下来，便于以后自检：
`GetMediaInfo` 返回 `NrTracks=N` 且 `CurrentURI=x-rincon-queue:RINCON_…#0`。

> 机身橙灯连闪 = 音箱收到了 Next 但执行失败（没有队列 / 曲目打不开），不是 App 崩了。
### 19.6 1.0.5：分批入队把顺序搞反 →「点 A 播 B」

1.0.4 把清单切成 12 首一批，但每批的 `DesiredFirstTrackNumberEnqueued` 都写死 **1**。
音箱的语义是"把这一批插到第 N 位"，于是后面每一批都顶到队首，队列变成按批倒序：

真机实测（不同 URL 入队后用 ContentDirectory.Browse 读回）：

| 写法 | 实际顺序 |
| --- | --- |
| 每批都插第 1 位 | probe05 → probe06 → probe03 → probe04 → probe01 → probe02 |
| 逐批递增 | probe01 → … → probe06（正确）|

而 seek 用的是 `AppState.queueIndex + 1`，序号和实际位置不符 → 播错歌。

修法：
1. `enqueueAll` 传 `index + 1` 作为本批插入位置；
2. 拆分重试时右半批用 `firstTrackNumber + mid`；
3. 两道自检：入队后比对 `NrTracks` 与清单长度；定位后比对 `<Track>` 与期望序号，
   任一不符就**退回逐首推送**（永不播错）。

> 教训：凡是"分批写入有序资源"的 API，插入位置必须显式递增，
> 且写入后一定要**读回来校验**。

### 19.7 版本号合并（对外统一 1.0.2）

按用户要求：**1.0.1 之后的所有迭代（1.0.2 / 1.0.3 / 1.0.4 / 1.0.5）合并成一次发布 1.0.2**。

- 对外 versionName = 1.0.2
- 内部 versionCode = 33，保持单调递增（已装的是 32），否则老用户无法覆盖安装
- 代码内容 = 1.0.5 的全部修复（原生队列 + 分批入队 + 顺序修正 + 双自检 + 状态/音量同步）
- release/更新日志.md 与仓库 CHANGELOG.md 改为中英双语，并把 1.0.2–1.0.5 的条目合并叙述

> 以后发版：只改 versionName（1.0.3 …），versionCode 继续 +1。

## 20. 构建 / 安装脚本（省掉每次重敲的四行）

`SonosLocalCast/` 下新增两个脚本，替代 §3 里那串手动命令：

```powershell
cd SonosLocalCast
.\build.ps1                  # release（默认离线）
.\build.ps1 -Mode Debug      # debug
.\build.ps1 -Clean           # 先 clean 再构建
.\build.ps1 -NoOffline       # 允许联网解析依赖

.\install.ps1                # 装 release 并回读 versionName / versionCode
.\install.ps1 -Mode Debug
.\install.ps1 -Log 15        # 装完跟 15 秒 SonosCast 日志
```

- 两个脚本自己拼 `JAVA_HOME` / `GRADLE_USER_HOME`；找不到 JDK 或 Gradle 会直接报错退出。
- `build.ps1` 结束打印 APK 路径、大小、**SHA-256**，可直接贴进更新日志。
- `install.ps1` 没设备时报"没有已连接的设备"，不会静默失败。
- 实测 `build.ps1 -Mode Release` 增量 12s，产物 SHA-256 与已发布的
  `FEA59E93…1149` 完全一致 —— **构建可复现**。

> 踩坑：`install.ps1` 最初把参数取名 `-Debug`，与 `[CmdletBinding()]` 自带的通用参数冲突
> （报"已为命令多次定义名为 Debug 的参数"），改成 `-Mode Debug` 才通过。
> 另外 PowerShell 里别给 `$args` 之类自动变量赋值，脚本里用的是 `$gradleArgs`。
>
> 第二个坑：`$out -notmatch 'Success'` 在 `$out` 是**数组**时返回的是"不匹配的元素数组"，
> 非空即为真 → 明明装成功却判失败。必须先 `$text = ($out | Out-String)` 再 `-match`。
> 实测通过：`已安装 : 1.0.2 (versionCode 33)`，退出码 0；`-Log 8` 能正常跟日志 8 秒。

## 21. 迭代手册（skill）

本项目全部经验沉淀在 **`skills/zonoz-local-stream/`**，新会话先读这一份：

```
skills/zonoz-local-stream/
├── SKILL.md                        入口：铁律、工程坐标、构建/验证/发版速查
└── references/
    ├── sonos-protocol.md           队列序列、实测上限、三道自检、状态同步
    ├── build-and-release.md        工具链、沙箱约束、发版清单、GitHub 推送
    └── pitfalls.md                 按痛苦程度排序的踩坑总账（A~E）
```

**为什么放在工程里而不是 harness 的 skill 目录**：本机 DSH 的 skill 来自 `app.asar`
（解包到 `E:\DSH\resources\runtime\office-skills`），该目录**只读**；
`%USERPROFILE%\.dsh\skills` 也不存在且在工作区外（沙箱拒写）。
所以采用"随项目走"的方式，靠本文件（AGENTS.md 每会话自动加载）做入口索引。

**五条最贵的教训**（详见 `pitfalls.md`）：

1. 文件内容不要用 shell 生成——反引号会把字母吞掉（`` `v ``→VT、`` `r ``→CR）
2. Sonos 的"200 成功"必须读回来验证（`NrTracks` / `<Track>`）
3. 分批入队：每批 12 条上限、插入位置必须逐批递增
4. 改播放链路时两条入口（`startTrack` / `castCurrent`）都要改
5. UI 不能自动化，交互验证交给用户并给出日志关键字

## 22. 1.1：终于修好"点 A 播 B"的四个根因

四个真凶叠在一起，每个都用真机数据证实过。**改这类代码前先读本节。**（1.0.3–1.0.10 是内部迭代，对外统一发布为 **1.1**。）

### 22.1 `NrTracks` 读的是"当前传输"，不是队列长度

`GetMediaInfo.NrTracks` 反映**当前 transport** 的曲目数。入队后、还没把传输切到
`x-rincon-queue` 时读它，拿到的是上一首单曲的 `1`。真机实测：

| 步骤 | `NrTracks` |
| --- | --- |
| transport = 单曲 URL | 1 |
| 入队 12 首、transport 未变 | **1** |
| 切到队列 transport 后 | **12** |

**自检顺序必须是：入队 → 切传输 → 再读 `NrTracks`。**
1.0.2–1.0.6 写反了，于是每次重建都被自己的自检否决，永远退回单曲推送。

### 22.2 fileId 必须由 Uri 确定性派生

旧实现把 `System.nanoTime()` 混进去，同一首歌每次注册都换 ID：自检比的是两个不同 ID
（必不相等）、重建后旧 URL 全 404、注册表无限增长。现在为 `md5(uri)` 前 12 位，
可用 `deploy/verify_playback.py` 独立复算对照。

### 22.3 批量注册不能动 `MediaRegistry.current`

`registerTrack()` 会写 `_current`。建 72 首队列时用它，建完 current 变成**最后一首**；
一旦退回单曲推送就推那一首 → 点 A 播 C。批量场景一律 `registerTrackSilently()`。

### 22.4 随机与单曲循环必须互斥

`orderFor()` 曾先判 `repeatMode == ONE` 再判随机 → 从单曲循环切到随机时清单仍是 1 首；
`nativePlayMode()` 同理会发 `REPEAT_ONE`（单曲死循环）。现在随机优先，开随机时把循环置回 ALL。

### 22.5 验证方法（以后必须这么做）

```powershell
cd deploy
python verify_playback.py <歌名>      # 四方比对，五项全过才算通过
python ntracks_probe.py               # 证实 NrTracks 跟着传输走
python seek_probe.py                  # 证实 Seek(TRACK_NR) 语义
```

判据：`NrTracks == 清单长度`、`Track == 日志序号`、`TrackURI` 含期望 fileId、
日志有 `声画一致：…✓`。**只在"日志看起来对"时不许下结论**——1.0.3 就是这么翻车的。
## 23. 1.1：切随机/循环不再让当前歌从头播

重建队列（`RemoveAllTracksFromQueue` + 重新入队）会把播放位置归零，
所以"切随机 → 这首从头开始"曾经是个稳定复现的现象。现在的处理：

1. `recastIfQueueActive()` 记下当前秒数，传给 `castNativeQueue(resumeAtSec = …)`；
2. 重建后 `Seek(TRACK_NR)` 定位曲目，再 `Seek(REL_TIME)` 回到原秒数，然后 `Play`；
3. 重建失败退回单曲推送时，同样 `Seek(REL_TIME)` 补回位置；
4. 原本是**暂停**状态，重建完仍然是暂停（不会自己放起来）。

实测（真机）：`pause → Seek(REL_TIME 120s) → play → 3 秒后 RelTime = 122s` ✓；
用户实测：Love Song 播到 1:10 时点随机，日志 `重建后恢复到 70s`，42 秒后读回 110s ✓。

另外两处配套修正：

- `<Track>` / `TrackURI` 的校验收成**轮询**（4 次 × 300ms），读一次就判会误杀；
  误杀的后果是退回单曲推送——那一步同样会把歌从头播。
- `recastIfQueueActive` 的四个提前返回都有日志
  （`当前不是队列模式` / `上一次推送还没结束` / `没有当前曲目` / `没有选中音箱`），
  便于区分"没触发"和"触发了但失败"。

## 24. 1.1.3：把"界面与声音不一致"这一类问题结构性堵住

这一节是本项目最容易反复的地方。**改播放链路前必读**，四条都是真机抓到的。

### 24.1 `insertNext` 的下标必须跟着移动修正

清单里被移动的歌如果排在**当前曲之前**，`removeAt` 会让当前曲前移一位：

```kotlin
if (existing >= 0) {
    list.removeAt(existing)
    if (existing < currentIdx) currentIdx--   // ← 漏了这句，queueIndex 会指向下一首
}
```

漏掉它的后果：紧接着的重建按错误序号 seek → "点了下一首播放，音乐立刻跳成另一首"。

### 24.2 进程重启后 fileId 必须能"反推"

fileId = `md5(uri)[:12]`，可复算但**不可逆**。重启后内存注册表是空的，
而音箱队列里还留着上一轮的 URL → 音箱取流拿到 **404 → 跳过整首**，
听感就是"音乐自己跳了 / 和界面对不上"。

现在 `handleStream` 找不到时调 `MediaRegistry.resolve(context, fileId)`：
遍历曲库重算 `md5(uri)` 命中后补注册。实测：未注册 id → 200 + 正确长度；伪造 id → 404。

### 24.3 队列模式下本地续播计时器必须彻底停用（三处闸门）

架构上早就写了"队列模式由音箱自己续播"，但只写在注释里，代码没拦：

| 位置 | 症状 |
| --- | --- |
| `scheduleAutoAdvance()`（拖进度条会调） | 重新武装计时器 |
| `verifyThenAdvance()`（本地计时器到点） | 到点发 Next |
| `handleAutoAdvance()`（闹钟唤醒） | 到点发 Next |

实测事故：`奔跑` 播了 7 秒后计时器到点发 Next → 直接跳到 `Boléro`，
用户感知是"放完 Amazing Day 直接到 Boléro"。

**三处都必须 `if (nativeQueueActive) { cancel + return }`。**

### 24.4 结构性措施（防止"漏一个入口"再次发生）

| 措施 | 实现 | 目的 |
| --- | --- | --- |
| 单一写入口 | `AppState.updateQueue(next, reason)`，内部 `queueRevision++` | 杜绝绕过 |
| 单一对账点 | 每 2s 采样比较 `queueRevision` 与 `syncedRevision`，差 >4s 就整队重建 | 漏了入口也能自愈 |
| 按内容认歌 | `syncQueueTrack` 用 `TrackURI` 的 fileId 反查清单 | 序号错位也能显示正确 |
| 主动自检 | 每约 10s 比对 `NrTracks` 与清单长度（60s 限流） | 提前发现漂移 |
| 回归脚本 | `deploy/regress_queue.py`，七条路径 + "不许切走当前曲"断言 | 每次改完先跑 |

### 24.5 `regress_queue.py` 的用法与局限

```powershell
cd deploy
python regress_queue.py      # 七步引导，脚本自动检测操作并断言
```

判据三条：`界面 fileId == 音箱 TrackURI fileId`、`NrTracks == 清单长度`、
第 3–6 步"当前曲的 fileId 前后必须一致"。

**局限**：它只能证明"界面与音箱一致"，证不了"用户意图"。
第 3 步因此特意要求"点一首排在当前曲**之前**的歌"，才会走到 24.1 那个分支。

### 24.6 以后再遇到"时好时坏"

先查三类状态量：`nativeQueueActive`（降级标志）、`queueRevision`（清单是否已同步）、
`autoAdvanceToken`（本地计时器是否还活着）。给每个提前返回加日志，
再让用户做一次最小复现——比读代码猜快一个数量级。

## 25. 1.1.6：与 Sonos App 抢控制权 / 通知栏随机循环 / 许可页

### 25.1 症状与真凶

用户实测：ZONOZ 暂停后，用 Sonos App 播一会儿，音乐会被 ZONOZ 抢回来；再在 Sonos App
点播放又切回去，而 ZONOZ 界面显示的是另一首歌。

真凶是 1.1.3 引入的"对账 + 自检"把门禁从 `nativeQueueActive` 改成了 `shouldUseQueue()`：
只要 App 清单 ≥2 首，即使音箱上放的是 Sonos App 的内容，也会被当成"我们没同步好"而整队重建。
于是 `RemoveAllTracksFromQueue` + 重新入队 → 直接顶掉别人的播放。

界面显示错歌，是因为 `syncQueueTrack()` 在 `TrackURI` 认不出来时退回"按序号映射"，
把清单第 N 首当成正在播的那首。

### 25.2 修法：先判归属，再谈同步

新增 `refreshOwnership(device)`，每 2 秒采样时第一步执行：

| TrackURI | 处理 |
| --- | --- |
| 含 `/stream/` | 我们的流，清 `externalSource`，一切照旧 |
| 其它 | 置 `AppState.externalSource = true`：`nativeQueueActive=false`、`cancelAutoAdvance()`、`lastQueueTrack=0`、状态改 PAUSED、刷新通知 |

四处闸门同时加 `if (AppState.externalSource.value) return`：`reconcileQueueIfNeeded`、
`auditQueue`、`handleEarlyStop`、以及 `syncQueueTrack` 里"认不出就按序号兜底"的分支。

用户主动点播放 = 交还：`resumeAll()` 发现 `externalSource` 为真时不再发 `Play`（那会继续放别人的歌），
而是 `castCurrent()` 重新推自己的串流。播放页会显示一条红色提示（`Str.EXTERNAL_SOURCE`）。

真机验证（PC 直接发 SOAP 模拟抢占，把音箱 `SetAVTransportURI` 指向一个非 `/stream/` 的 URL）：
- 日志出现一次 `音箱已被其他来源占用（Sonos App 等），ZONOZ 交还控制权`
- 之后 18 秒内 **零** 次 `重建队列 / castNativeQueue / 已排续播`（旧行为会立刻重建）
- 把 URI 设回原串流后，日志 `音箱已回到 ZONOZ 的串流`

### 25.3 通知栏 / 锁屏的随机与循环

`MediaSessionCompat` 支持 `setShuffleMode` / `setRepeatMode`，系统媒体控件在
`PlaybackStateCompat.actions` 含 `ACTION_SET_SHUFFLE_MODE` / `ACTION_SET_REPEAT_MODE` 时
才会渲染这两个按钮。

- `updatePlaybackState()` 补上这两个 action，并按 `AppState.shuffle / repeatMode` 回写模式
- `Callback.onSetShuffleMode` / `onSetRepeatMode` 落到 `AppState.setShuffle` / `setRepeatMode`，
  随后 `recastIfQueueActive(...)` 让音箱的播放模式跟着变
- 实测 `dumpsys media_session` 里本会话 `actions=2359863`，减去原有的
  `PLAY|PAUSE|PLAY_PAUSE|STOP|NEXT|PREV`(567) 正好等于 `2^21 + 2^18`，两个新 action 已生效

### 25.4 其余两处

- 播放页底部两个入口：标题 13sp Medium、数量 12sp，字距统一 0.2sp（原来 12/11 混用）
- 设置页新增「开源许可」（`ui/LicenseScreen.kt`）：MIT 全文 + NanoHTTPD(BSD-3) /
  OkHttp(Apache-2.0) / AndroidX-Compose(Apache-2.0) / Kotlin(Apache-2.0) 清单
