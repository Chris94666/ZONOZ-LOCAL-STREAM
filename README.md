# ZONOZ LOCAL STREAM

Stream the music stored on your phone straight to Sonos speakers on your local network —
no cloud, no account, no Sonos app required.

把手机里的本地音乐，通过 **App 自建的 HTTP 服务 + 直连音箱的 UPnP/SOAP**，推给局域网里的 Sonos 播放。
全程离线、纯局域网，不依赖 Sonos 官方 App。

**[English](#english) · [简体中文](#简体中文) · [Download v1.0.2](https://github.com/Chris94666/ZONOZ-LOCAL-STREAM/releases/latest)**

```
手机 App / Phone app（NanoHTTPD :8080） ──SetAVTransportURI(URL)──► 音箱 / Speaker :1400 /AVTransport
        ▲                                                                     │
        └────────────────  GET /stream/<id>（Range 206）  ─────────────────────┘
```

> This is an unofficial tool, not affiliated with Sonos, Inc. "Sonos" is used only to describe compatible hardware.
> 本项目是非官方工具，与 Sonos, Inc. 无任何关联。"Sonos" 仅用于说明兼容的设备。

---

## Screenshots / 应用截图

| Player | Library | Settings | About |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/player.jpg" width="220" /> | <img src="docs/screenshots/library.jpg" width="220" /> | <img src="docs/screenshots/setting.jpg" width="220" /> | <img src="docs/screenshots/about.jpg" width="220" /> |

---

## English

### What it is

ZONOZ LOCAL STREAM turns your Android phone into a media server for your Sonos speakers.
The app scans the music stored on the phone, serves it over an embedded HTTP server with full
`Range` support (so seeking works), and drives the speaker over UPnP/SOAP — playlist, play/pause,
volume, repeat and shuffle all happen on the speaker itself.

Everything stays on your Wi-Fi: no cloud, no account, no telemetry.

### Features

- **Local library** — scans your music folder. On Android 11+ it uses the Storage Access
  Framework, so files that were never indexed by MediaStore still show up. Search, A–Z index
  (pinyin initials for Chinese titles), sort by title / date / size / duration.
- **Streaming playback** — the phone acts as the media server and the speaker pulls the audio;
  full HTTP `Range` support means scrubbing the progress bar works.
- **Native Sonos queue** — the whole play queue is loaded into the speaker
  (`AddMultipleURIsToQueue` + `x-rincon-queue`), so tracks run back to back with no gap and the
  speaker's own buttons (play/pause, skip) drive the same queue the app shows.
- **Play queue** — the list shows the real playback order; tap any row to jump there.
  Shuffle / repeat-all / repeat-one are mutually exclusive.
- **Favourites** — long-press a track to add it, then filter the library to favourites only.
- **Screen-off playback** — a local timer plus an exact alarm keep the next track queued even
  when the phone is locked or dozing.
- **Notification & lock screen** — a standard media card with the album art and a small brand badge.
- **8 languages** — English, 简体中文, 繁體中文, 日本語, 한국어, Русский, Español, Français.
  Plus four UI text sizes and haptic feedback on taps and drags.
- **Device discovery** — finds speakers on the LAN automatically, tells the main speaker from
  satellites, and detects S1 / S2 firmware.

### Install

The app is not on Google Play; sideload the APK from [Releases](https://github.com/Chris94666/ZONOZ-LOCAL-STREAM/releases/latest).

1. Copy the APK to the phone and install it (allow "install unknown apps" when asked).
2. Open the app and grant the **Music and audio** permission.
3. `Settings → Music folder` — pick the folder that holds your music (defaults to `Music`).
4. Back on the player screen, tap the speaker icon in the top-right corner and wait for
   discovery; if nothing shows up, enter the speaker IP manually.
5. Open the library and tap a track — the speaker starts playing.

To keep playing with the screen off: `Settings → Diagnostics → Keep alive`, then follow the
prompts to exclude the app from battery optimisation and allow exact alarms.

### Known limitations

- **LAN only** — phone and speaker must be on the same Wi-Fi. Router "AP isolation" will break it.
- **Cleartext HTTP** — Sonos only speaks HTTP, so the app declares `usesCleartextTraffic`.
  All traffic stays inside your local network.
- **Speaker-side skip depends on the model** — Sonos documents "press play/pause twice to skip"
  as *when applicable*; older players may not send that gesture. The in-app buttons always work.
- **No Sonos grouping** (`x-rincon`) support.
- **S1 firmware** — detected and downgraded to a minimal command set, but never tested on real S1 hardware.
- **Local files only** — no streaming services.
- The first cast loads the whole queue into the speaker, which takes a moment for large libraries.

### Privacy

- No account, no analytics, no crash reporting. The app makes no requests to the internet.
- The only network activity: SSDP discovery on the LAN, SOAP control of the speakers, and serving
  audio to the speaker over HTTP.
- Library data lives only in memory and `SharedPreferences` on the device.

---

## 简体中文

### 这是什么

把手机变成 Sonos 音箱的媒体服务器：App 扫描手机里的音乐，用自建 HTTP 服务对外推流
（完整支持 `Range`，所以能拖进度），并通过 UPnP/SOAP 控制音箱——播放清单、播放/暂停、音量、
循环与随机都在音箱上执行。

全程离线、纯局域网：不经过任何云端，不需要账号，也不依赖 Sonos 官方 App。
适合家里有 Sonos 音箱、但音乐都存在手机本地的场景。

### 功能

- **本地曲库**：扫描手机音乐目录（Android 11+ 走 SAF，未入库的文件也能读到），支持搜索、
  A-Z 索引（中文按拼音首字母）、按标题/时间/大小/时长排序
- **推流播放**：手机当媒体服务器，音箱拉流播放；完整支持 HTTP Range，可拖动进度
- **Sonos 原生队列**：整条播放清单会灌进音箱（`AddMultipleURIsToQueue` + `x-rincon-queue`），
  歌与歌之间由音箱自己衔接没有空隙，机身的播放/暂停、下一首按键操作的就是 App 显示的那条队列
- **播放清单**：真实播放顺序，点哪首播哪首；随机 / 列表循环 / 单曲循环三者严格互斥
- **收藏**：长按曲目加入喜爱，可筛选只看收藏
- **锁屏续播**：本地计时器 + 精确闹钟双保险，锁屏/息屏也能自动接下一首
- **通知与锁屏**：系统媒体卡片，专辑图右下角带品牌角标
- **多语言**：简体中文 / 繁體中文 / English / 日本語 / 한국어 / Русский / Español / Français（8 套）；
  整体文字大小四档，点击与拖动有触感反馈
- **设备**：自动发现局域网音箱，识别主箱/卫星箱，支持 S1 / S2 固件

### 安装

应用没有上架 Google Play，直接从 [Releases](https://github.com/Chris94666/ZONOZ-LOCAL-STREAM/releases/latest) 下载 APK 安装。

1. 把 APK 传到手机安装（提示时允许「安装未知应用」）
2. 打开 App，允许「音乐和音频」权限
3. `设置 → 音乐文件夹` 选一个目录（默认 `Music`）
4. 回到播放页，点右上角音箱图标，等自动发现；没发现就手动输入音箱 IP
5. 进曲库点一首歌 → 音箱开始播放

想锁屏也能连续播放：`设置 → 调试 → 后台保活`，按提示关掉电池优化、允许精确闹钟。

### 已知限制

- **仅限局域网**：手机和音箱必须在同一个 Wi-Fi；路由器开了「AP 隔离」会失败
- **明文 HTTP**：音箱只支持 HTTP，所以 App 声明了 `usesCleartextTraffic`，全部流量留在局域网内
- **机身按键切歌看型号**：Sonos 文档写的是「双击播放键切歌（when applicable）」，
  老型号可能不上报这个手势；App 里的上一首/下一首始终可用
- **不支持音箱分组**（`x-rincon`）
- **S1 老固件**只做了识别与降级，没有真机验证过
- 只处理本地音频文件，不接流媒体服务
- 第一次点歌需要把整条清单灌进音箱，曲库很大时会有一小段等待

### 隐私

- 没有账号、没有统计、没有崩溃上报，App 不发任何外部网络请求
- 唯一的网络行为：局域网内 SSDP 发现 + 对音箱的 SOAP 控制 + 作为 HTTP 服务端把音频发给音箱
- 曲库信息只在本机内存与 SharedPreferences 里

---

## Build from source / 从源码构建

```bash
git clone https://github.com/Chris94666/ZONOZ-LOCAL-STREAM.git
cd ZONOZ-LOCAL-STREAM/SonosLocalCast

# tell Gradle where the SDK is (or just open the project in Android Studio)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed. Put a keystore in `SonosLocalCast/keystore/` and add to `local.properties`:

```properties
signing.storeFile=../keystore/your.jks
signing.storePassword=***
signing.keyAlias=***
signing.keyPassword=***
```

Without a keystore, `assembleRelease` still produces an **unsigned** APK — it just cannot be
installed. To create your own keystore:

```bash
keytool -genkeypair -v -keystore your.jks -alias yourkey \
        -keyalg RSA -keysize 2048 -validity 10000
```

| Item / 项 | Version / 版本 |
| --- | --- |
| Android | 8.0 (API 26) and above |
| compileSdk | 35 |
| JDK | 17 |
| Gradle | 8.9 (wrapper included / 仓库自带 wrapper) |

## Project layout / 项目结构

```
SonosLocalCast/                 Android project (package com.sonoscast.local, kept for upgrade compatibility)
├── app/src/main/java/com/sonoscast/local/
│   ├── core/                   HTTP server, SOAP, SSDP, library, i18n, ...
│   ├── service/                foreground service + auto-advance alarm
│   └── ui/                     Jetpack Compose screens
└── app/src/main/res/
tools/make_icons.py             icon generator (black ZONOZ on white)
.github/                        build CI
```

## Credits & license / 致谢与许可

MIT License — see [LICENSE](LICENSE).
Third-party: AndroidX / Jetpack Compose / Kotlin Coroutines / [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) / [OkHttp](https://square.github.io/okhttp/).

作者 / Author: **大气放电现象** ([@Chris94666](https://github.com/Chris94666))
