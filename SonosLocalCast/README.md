# ZONOZ local stream · 本地音乐推送

把手机里的本地音频，通过 **自建 HTTP 服务器 + 直连 Sonos 的 UPnP/SOAP 接口**，推送到两台 Play:1 上播放。
全程离线、纯局域网，不依赖 Sonos 官方 App，不经过任何云服务，不需要登录账号。

```
┌──────────────┐   SSDP 239.255.255.250:1900   ┌────────────────────┐
│  Android App │ ────────────────────────────► │ Play:1 (S2)        │
│              │                               │ 192.168.1.20:1400  │
│  NanoHTTPD   │ ◄──── GET /stream/xxx.mp3 ─── │ AVTransport        │
│  :8080       │       Range: bytes=0-         │ RenderingControl   │
└──────────────┘                               └────────────────────┘
        ▲                                              ▲
        │            SOAP POST SetAVTransportURI        │
        └──────────────────────────────────────────────┘
```

---

## 0. 已构建产物（可直接安装）

| 项 | 值 |
| --- | --- |
| APK 路径 | `SonosLocalCast/dist/ZONOZ本地推播-v2.0.4.apk` |
| 包名 | `com.sonoscast.local` |
| 版本 | 1.0 (versionCode 27) |
| minSdk / targetSdk | 26 / 35（Android 8.0 ~ Android 15） |
| ABI | arm64-v8a, armeabi-v7a, x86, x86_64 |
| 大小 | 约 11 MB |
| 签名 | `keystore/sonoscast.jks` · alias `sonoscast` · 口令 `sonoscast` |

已在本机完成一次完整构建验证：`BUILD SUCCESSFUL`（JDK 17.0.2 + Gradle 8.9 + AGP 8.7.3 + SDK 35）。

### 安装步骤（Android 手机）

1. 把 `Sonos本地推送-v1.0.0.apk` 传到手机（数据线 / 微信文件传输助手 / 网盘均可）。
2. 点击 APK，HyperOS 会拦一次 —— 选「**设置**」→ 允许「**安装未知应用**」→ 返回继续安装。
3. 若提示「应用未经安全检测」，选「**继续安装**」（这是自签名包，属正常提示）。
4. 首次打开会请求通知权限，**必须允许**（前台服务需要常驻通知）。
5. 进「设置 → 应用设置 → Sonos 本地推送 → 省电策略」改成「**无限制**」，
   否则 HyperOS 会在熄屏一段时间后杀掉后台服务，播放中断。

### 本机已部署的构建环境

| 组件 | 路径 |
| --- | --- |
| JDK 17.0.2 | `%LOCALAPPDATA%\AndroidToolchain\jdk-17` |
| Gradle 8.9 | `%LOCALAPPDATA%\AndroidToolchain\gradle-8.9` |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`（platform 35 / build-tools 35.0.0 / platform-tools） |

重新构建：

```powershell
$env:JAVA_HOME        = "$env:LOCALAPPDATA\AndroidToolchain\jdk-17"
$env:GRADLE_USER_HOME = "<工程所在盘符>\path\to\SONOS本地播放apk开发\.gradle-home"   # 见下方说明
& "$env:LOCALAPPDATA\AndroidToolchain\gradle-8.9\bin\gradle.bat" assembleRelease --no-daemon --offline
```

> `GRADLE_USER_HOME` 指向工作区内的 `.gradle-home/`：在受限/沙箱环境里 `%USERPROFILE%\.gradle`
> 不可写，Gradle 会直接报 `Failed to load native library 'native-platform.dll'`。
> 普通开发机可以省略这一行，用默认的 `~/.gradle` 即可。
> 另外 `gradle.properties` 里设了 `kotlin.compiler.execution.strategy=in-process`，
> 避免 Kotlin 编译守护进程在受限环境里连不上。

> 工程放在含中文的路径下，`gradle.properties` 里已设 `android.overridePathCheck=true` 放行。
> 如果换机器后构建报路径错误，把这个工程复制到纯英文路径即可。

---

## 1. 环境要求

| 项 | 版本 |
| --- | --- |
| Android Studio | Ladybug 2024.2.1 及以上（推荐 Meerkat 2024.3+） |
| JDK | 17（直接用 Android Studio 内置的 JBR 即可） |
| Gradle | 8.9（wrapper 会自动下载） |
| AGP | 8.7.3 |
| Kotlin | 2.0.21（Compose Compiler 随 Kotlin 版本内置） |
| Compose BOM | 2024.12.01（Material 3 + 动态取色） |
| compileSdk / targetSdk | 35 |
| minSdk | 26（Android 8.0） |

运行设备：Android 8.0 及以上，需与 Sonos 处于同一 Wi-Fi 子网。

---

## 2. 构建 APK

### 图形界面

1. Android Studio → `Open` → 选中 `SonosLocalCast` 目录（**不要**打开它的父目录）。
2. 首次打开会提示下载 Gradle 8.9 与 Android SDK Platform 35，全部同意。
3. `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`。
4. 产物：`app/build/outputs/apk/debug/app-debug.apk`，可直接 `adb install` 或传到手机安装。

> `gradle/wrapper/gradle-wrapper.jar` 未随仓库提供（本机无 Gradle 环境，无法生成二进制 jar）。
> Android Studio 打开项目时会按 `gradle-wrapper.properties` 自动补齐。
> 若命令行构建，先执行一次 `gradle wrapper --gradle-version 8.9`。

### 命令行

```bash
# 首次
gradle wrapper --gradle-version 8.9

# debug
./gradlew assembleDebug

# release（未配签名时为 unsigned，需自行 apksigner）
./gradlew assembleRelease
```

### 签名

```bash
keytool -genkeypair -v -keystore sonoscast.jks \
  -alias sonoscast -keyalg RSA -keysize 2048 -validity 10000
```

在 `app/build.gradle.kts` 里补：

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("../sonoscast.jks")
            storePassword = "你的密码"
            keyAlias = "sonoscast"
            keyPassword = "你的密码"
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("release") }
    }
}
```

或用 `Build` → `Generate Signed Bundle / APK…` 走向导，无需改脚本。
自己用的话，debug APK 已经够用（debug keystore 由 AS 自动生成，可直接安装）。

---

## 3. 使用

1. 手机连上家里的 Wi-Fi（**不要**用访客网络）。
2. 打开 App，允许通知权限 —— 前台服务需要常驻通知。
3. 启动后会自动 SSDP 扫描 3 秒。没扫到就在「音箱」卡片底部**手动输入 IP**（如 `192.168.1.20`）。
4. 勾选一台或两台 Play:1。
5. 「选择本地音频」→ 系统文件选择器挑一个 mp3 / flac / wav / m4a。
6. 「推送到选中音箱」。
7. 断流排查：用另一台手机或电脑浏览器打开状态卡里那个 `http://手机IP:8080/`，
   能打开说明手机侧入站连接正常；打不开就是路由器 AP 隔离或手机安全软件拦截。

---

## 4. 代码结构

```
app/src/main/java/com/sonoscast/local/
├── MainActivity.kt              单 Activity，Compose 宿主 + 通知权限申请
├── SonosCastApp.kt              Application，创建通知渠道
├── core/
│   ├── SsdpDiscovery.kt         SSDP M-SEARCH（ZonePlayer ST + ssdp:all 双轮）+ 设备描述解析
│   ├── LocalMediaServer.kt      NanoHTTPD 子类，Range/206/416 完整实现 + 诊断首页
│   ├── SonosSoapClient.kt       SetAVTransportURI / Play / Pause / Stop / Seek / Volume
│   ├── SoapXml.kt               SOAP 信封 + DIDL-Lite 元数据 + XML 转义
│   ├── UpnpErrors.kt            UPnP 错误码 → 人话 + 处置建议
│   ├── MediaRegistry.kt         fileId ↔ SAF Uri 映射，HTTP 只暴露 fileId
│   ├── MimeTypes.kt             MIME 归一化（audio/x-flac → audio/flac 等）
│   ├── NetUtils.kt              局域网 IPv4 获取（优先 wlan 网卡）、多播锁
│   ├── AppState.kt              全局状态（StateFlow），Service 写 / UI 读
│   ├── LogBus.kt                500 条环形日志总线
│   └── SonosDevice.kt           设备模型
├── service/
│   └── SonosCastService.kt      前台服务：持有 server + discovery + soap，处理 701 重试
└── ui/
    ├── MainScreen.kt            Material 3 主界面
    ├── MainViewModel.kt         状态转发 + 指令下发
    └── theme/Theme.kt           动态取色 + 自定义靛蓝配色
```

---

## 5. 关键实现说明

### 5.1 SSDP 发现

- 第一轮用 Sonos 专属 ST `urn:schemas-upnp-org:device:ZonePlayer:1`，回包干净；
  1.6 秒内无收获再用 `ssdp:all` 兜底一轮，总预算 3 秒。
- `DatagramSocket` 显式 `bind()` 到 Wi-Fi 网卡地址，避免开了 VPN / 蜂窝时从错误接口发出。
- 拿 `MulticastLock`，否则部分 ROM 会丢组播回包。
- 收到 `LOCATION` 后再 GET 一次 `device_description.xml`，解析 `roomName` / `UDN` / `controlURL`。
- 超时返回空列表 → 服务进入 `STANDBY`（纯本地待命），不崩溃、不退出。

### 5.2 HTTP 服务器（Range 是重点）

`LocalMediaServer` 完整处理：

| 请求 | 响应 |
| --- | --- |
| 无 Range | `200` + `Accept-Ranges: bytes` + `Content-Length: total` |
| `Range: bytes=0-` | `206` + `Content-Range: bytes 0-(total-1)/total` |
| `Range: bytes=1000-1999` | `206` + `Content-Length: 1000` |
| `Range: bytes=-500`（后缀） | `206`，最后 500 字节 |
| `Range: bytes=99999999-`（越界） | `416` + `Content-Range: bytes */total` |
| `HEAD` | 只回头，不发 body |

文件定位优先用 `ParcelFileDescriptor.AutoCloseInputStream` + `FileChannel.position()`，
对本地文件是 O(1)；provider 不支持 seek 时回退到 `skip()`。

`Content-Type` 与 DIDL-Lite 的 `protocolInfo` 使用同一个值（都来自 `MimeTypes.resolve`），
避免「HTTP 说是 flac、DIDL 说是 mp3」导致的 714。

服务器还提供一个 HTML 首页 `/`，显示当前音频与推流 URL —— 这是排查 701 最有效的工具。

### 5.3 SOAP 推送

`SetAVTransportURI` 的 `CurrentURIMetaData` 是一段**被转义过的** DIDL-Lite：

```xml
<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" ...>
  <item id="1" parentID="0" restricted="1">
    <dc:title>歌曲名</dc:title>
    <upnp:class>object.item.audioItem.musicTrack</upnp:class>
    <res protocolInfo="http-get:*:audio/mpeg:*">http://192.168.1.5:8080/stream/1a2b3c.mp3</res>
  </item>
</DIDL-Lite>
```

整段作为 SOAP 参数文本，必须 `&lt;` 转义 —— 少转一层就是 402 Invalid Args。

### 5.4 701 自动重试

1. `SetAVTransportURI` 返回 701 → 日志打印**实际发出的 URL**。
2. 重建 HTTP 服务器（换端口 8080 → 8081 → 8765 → 随机），重新取一次局域网 IP。
3. 用新 URL 对**所有**已选音箱重新推一遍（不能只重推失败那台，因为旧端口已关闭）。
4. 仍失败 → UI 展示错误码 + 分步排查建议（VPN / AP 隔离 / 安全软件）。

### 5.5 双音箱

勾选两台时，对每台独立发 `SetAVTransportURI` + `Play`，各自拉同一个 HTTP URL。
**不使用** `x-rincon` 手动组网 —— 那套在 S2 上行为不稳定，独立推送是最可靠的兜底。

### 5.6 后台存活

- 前台服务 `foregroundServiceType="dataSync|mediaPlayback"`，常驻通知显示端口与已连接台数。
- `dataSync` 在 Android 15 有 6 小时/24 小时累计限制，`mediaPlayback` 没有，两者同时声明以规避。
- `PARTIAL_WAKE_LOCK`（CPU）+ `WIFI_MODE_FULL_HIGH_PERF`（Wi-Fi），每次推送时续期 4 小时。

---

## 6. S2 排障速查

| 现象 | 最可能原因 | 处理 |
| --- | --- | --- |
| 扫描不到任何音箱 | 路由器 AP 隔离 / 访客网络 / 多播被拦 | 关闭 AP 隔离；手动输入 IP |
| 扫描不到，但手动 IP 能连 | 路由器屏蔽了 239.255.255.250 | 一直用手动 IP，不影响播放 |
| `701 Transition not available` | 音箱访问不到 `http://手机IP:端口/...` | 用别的设备浏览器打开该 URL 验证；关 VPN / 安全软件 |
| `714 Illegal MIME-Type` | Content-Type 与真实格式不符 | 看日志里的 MIME；转成 mp3 再试 |
| `716 Resource not found` | SAF 授权失效 / 文件被删 | 重新选一次文件 |
| 能播但拖不动进度条 | 服务器未返回 206 | 看日志是否有 `HTTP 206`；本工程已实现 |
| 播放几秒就断 | 熄屏后 Wi-Fi 休眠 | 检查是否拿到 WakeLock/WifiLock；关闭系统「电池优化」对本 App 的限制 |
| `800` / 连接超时 | IP 或端口不对，音箱离线 | 重新扫描；确认 1400 端口可达 |

关于「启用 UPnP」开关：Sonos 官方 App 在部分版本里没有这个开关，但企业/托管网络环境或
某些固件版本的确会关闭 UPnP 服务。判断方法：

```
浏览器打开 http://音箱IP:1400/xml/device_description.xml
```

- 能返回 XML → UPnP 服务正常，问题一定在手机 → 音箱的**反向**连通性（701 类）。
- 返回 403 / 超时 → 音箱侧 UPnP 被限制，需要在 Sonos App 的「设置 → 系统 → 网络」里检查，
  或重置音箱网络后重试。

---

## 7. 已知限制

- **手机 IP 变化后需要重新推送**：DHCP 续租换了地址，旧 URL 立即失效。重新点一次推送即可（会自动重建服务）。
- **不支持 Sonos 原生分组**：见 5.5。两台音箱是各播各的，可能存在几十毫秒的相位差。
- **不支持 DSD / APE / OGG 高码率**：Play:1 的硬解能力有限，714 时请转码为 MP3 或 FLAC。
- **单曲推送**：当前一次只推一个文件，没有做播放列表与自动下一首。
- **HTTP 明文**：`usesCleartextTraffic="true"`，仅限局域网使用，不要暴露到公网。
