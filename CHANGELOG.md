# Changelog · 更新日志

ZONOZ local stream — Android app that streams local music from your phone to Sonos over your LAN.
ZONOZ local stream —— 把手机本地音乐通过局域网直接推给 Sonos 播放的 Android 应用。

> Current release: **1.1.5** (`versionCode 51`). Version codes stay monotonic so existing
> installs upgrade in place; 1.0.3–1.0.10 and 1.1.1–1.1.3 were internal iterations folded into
> the next public release.
> 当前发布：**1.1.5**（`versionCode 51`）。versionCode 保持单调递增以便覆盖安装；
> 1.0.3–1.0.10、1.1.1–1.1.3 均为内部迭代，随后并入公开发布。

---

## v1.2.1 — 2026-10-31

### 中文
- **耗电修复（重点）**：断网时媒体服务器监听 socket 会在 `accept` 上空转，
  实测稳态 CPU **78 秒/分钟**、整机发烫。现在一断连就停服务器，Wi-Fi 回来再重建，
  同场景降到 **0.01 秒/分钟**。
- **自适应轮询**：正在播 1 秒 / 暂停 5 秒 / 无活跃设备 15 秒 / 断连 30 秒
  （旧版固定 1 秒且永不停，是耗电第二元凶）。
- **WakeLock 只在真的在播时持有**：暂停、断连、闲置立即释放；
  30 分钟续期前也先判断是否在播（旧版永久持锁并一直续期）。
- **前台服务类型改为 mediaPlayback**：原 `dataSync` 在 Android 14+ 有 6 小时上限，
  超时会抛 `ForegroundServiceDidNotStopInTimeException` 把进程杀掉。
- **锁屏/通知与 App 状态彻底同步**：新增会话看门狗（每拍按真实状态强刷）、
  播放中每拍刷新会话进度、轮询找不到活跃设备时兜底盯第一个主箱。
  修复"音箱暂停了，锁屏却还在走进度直到歌放完"。
- **通知栏随机/循环按钮**：改为「点亮=纯白、熄灭=42% 灰」的统一表达，
  并让动作 ID 随状态变化，绕过 MIUI 对锁屏按钮图标的缓存。
- **状态栏可读性**：系统栏图标强制白色；播放页顶栏叠一层从封面主色到透明的渐变。

### English
- Battery: the media server's listening socket spun on `accept` once Wi-Fi disappeared
  (measured 78 s CPU per minute, phone ran hot). The server is now stopped on disconnect and
  rebuilt when the network returns — same scenario drops to 0.01 s per minute.
- Adaptive polling: 1 s playing / 5 s paused / 15 s idle / 30 s disconnected.
- WakeLock is held only while actually playing; released on pause, disconnect and idle.
- Foreground service type changed to `mediaPlayback` (the old `dataSync` hits Android 14's
  six-hour limit and throws `ForegroundServiceDidNotStopInTimeException`).
- Lock-screen/notification state now converges within one poll: a session watchdog rewrites
  the real state every tick, the session position is refreshed while playing, and polling falls
  back to the first coordinator when no device carries a playback status.
- Notification shuffle/repeat buttons use one brightness rule (lit = white, off = 42% grey) and
  state-dependent action ids to defeat MIUI's icon caching.
- Status bar: forced light system-bar icons; the player screen tints the top bar with the cover's
  dominant colour.

---

## v1.2 — 2026-10-29

### 中文
- **VPN / 代理兼容**：SSDP 组播改用 MulticastSocket 并钉在 Wi-Fi 网卡上，SOAP 出站 socket
  绑定 wlan 源地址 —— 即使 VPN 抢走默认路由，局域网发现与推流仍走真实网卡。
  扫不到设备且检测到 VPN 时给出明确提示（8 语言）。
- **串流地址过期自愈**：手机换 IP（VPN 开关 / Wi-Fi 重连 / DHCP 续租）后，音箱里那条
  `http://<旧IP>:8080/...` 会失效。现在每 2 秒比对主机名，不一致就按新地址重推（15s 限流）。
- **单曲循环不再打乱清单**：切循环只改播放模式，不再重建/收缩内部清单
  （旧行为会让服务端按错误序号重建，当场跳歌）。
- **立体声配对修正**：从箱（LF）状态跟随主箱，不再显示"已暂停/失败"；暂停、继续、停止、
  拖动、音量只对主箱发命令，消除 1023 错误闪烁。
- **暂停状态重建队列不再出声**：锁屏切随机/循环时只重建并定位，不发 Play；重建结束统一
  回写媒体会话，锁屏与 App 状态不再分叉。
- **播放前格式预检**：解析 FLAC/WAV/AIFF 文件头，超过 48 kHz（或 24-bit WAV/AIFF）直接
  拦截并提示，不再出现"没声音几秒后自动跳下一首"的假故障。
- 回归脚本修复：扩展名不再写死 `.mp3`（`.ogg` 曾导致误判）。

### English
- VPN/proxy compatibility: SSDP multicast pinned to the Wi-Fi interface, SOAP sockets bound to
  the WLAN source address, so discovery and streaming survive a VPN taking the default route;
  plus an explicit hint when nothing is found while a VPN is active.
- Stale stream-URL self-healing: after the phone's IP changes, the speaker's old
  `http://<old-ip>:8080/...` URLs are detected and the queue is re-pushed with the new address.
- Switching repeat mode no longer reshuffles/shrinks the internal queue (that used to trigger a
  rebuild with a stale index and skip the current track).
- Stereo-pair fixes: the satellite now mirrors the coordinator's state, and transport commands are
  sent only to the coordinator, so LF no longer flashes "paused/failed".
- Rebuilding while paused no longer sends Play, and the media session is refreshed when it finishes.
- Format pre-check: FLAC/WAV/AIFF headers are parsed; anything above 48 kHz (or 24-bit WAV/AIFF)
  is blocked with a clear message instead of being silently skipped by the speaker.
- Regression script: extension is no longer hard-coded to `.mp3`.

---

## v1.1.9 — 2026-10-29

### 中文
- **断连感知**：连续 3 次（约 6 秒）拿不到音箱状态 → 判定"与音箱失去联系"，
  界面与锁屏同时转为暂停、进度冻结、播放页弹红字提示；Wi-Fi 恢复后自动重建媒体服务器
  并清除提示（不自动播放，等你点）。
- **媒体会话速度修复**：暂停时 `speed=0`，锁屏/通知栏的进度条不再"假前进"。
- 点播放前先自检媒体服务器：服务不可用就不发播放指令，避免"界面在走、音箱没声"。
- **播放清单支持长按「下一首播放」**：实时队列，被插的歌立刻出现在当前曲下方。
- **新增隐私声明**（设置 → 隐私声明，中英双语）+ 仓库根 `PRIVACY.md`。
- 修复回归脚本把扩展名写死为 `.mp3` 导致的 `.ogg` 误判。

### English
- Connection-loss detection: three consecutive failed state reads put the UI and the
  lock-screen session into pause, freeze the progress and show a red banner; when Wi-Fi
  returns the media server is rebuilt automatically and the banner clears.
- MediaSession now reports `speed = 0` while paused (the lock-screen progress no longer drifts).
- Play requests verify the media server first, so the UI never "plays" silently.
- Queue screen gained a long-press "play next" action; the inserted track appears right
  below the current one.
- New privacy statement in Settings (CN/EN) plus `PRIVACY.md` in the repo.
- Fixed the regression script's hard-coded `.mp3` extension that mis-parsed `.ogg` URIs.

---

## v1.1.8 — 2026-10-28

### 中文
- **循环切换不再打断播放**：列表/单曲循环只发一次 `SetPlayMode`，不再清空重建队列
  （以前每按一次会中断 0.5–2 秒）。
- **媒体控件状态跟随真实播放**：切随机/循环后，通知栏进度继续走、播放键不再误显示为"暂停"。
- **媒体控件图标反馈**：随机开启时图标变为"方形框内的随机箭头"；循环在"列表/单曲"两套图标与文案间切换。
- **单曲循环的清单收敛**：清单页只显示当前 1 首，入口数字同步为 `1 首`；
  内部队列仍保留全部曲目，切随机/重建不受影响。
- 修复单曲推送（降级模式）下界面序号不再跟随音箱的问题：改按 `TrackURI` 的 fileId 认歌。

### English
- Repeat toggling no longer interrupts playback: it only sends `SetPlayMode` instead of
  clearing and rebuilding the queue.
- Media-control state follows the real transport state again (progress keeps moving, no false "paused").
- Media-control icons now show state: shuffle uses a framed-arrow icon when enabled; repeat
  switches between list/one icons and labels.
- In single-repeat, the queue screen shows just the current track and the entry count matches (1);
  the internal queue keeps every track so shuffle/rebuild still work.
- Fixed the UI losing track of the current song in single-push fallback mode (map by `TrackURI` fileId).

---

## v1.1.7 — 2026-10-28

### 中文
- **媒体控件：随机 / 循环按钮改用 custom action**（和 Sonos App 同一种实现）。
  只声明 `ACTION_SET_SHUFFLE_MODE` 时澎湃 OS 的媒体卡片不渲染，改成
  `PlaybackStateCompat.CustomAction` 后，通知栏与锁屏展开即可看到「随机播放 / 列表循环」。
- **收回控制权时从暂停处继续**：`pauseAll()` 记下 `pausedAtSec`，ZONOZ 被 Sonos App
  接管后再点播放，不再从头开始。
- `recastIfQueueActive()` 增加归属守卫：接管瞬间不再有"对账重建"把别人的播放顶掉。
- 桌面图标名称改为 **Zonoz**（界面内仍是 ZONOZ local stream）。

### English
- Media controls now expose shuffle/repeat as `PlaybackStateCompat.CustomAction`
  (the same approach the Sonos app uses); plain shuffle/repeat action bits are not
  rendered by HyperOS' media card.
- Taking the speaker back resumes from the paused position (`pausedAtSec`).
- `recastIfQueueActive()` is guarded by source ownership, so a takeover no longer
  triggers a queue rebuild.
- Launcher label is now **Zonoz** (in-app brand stays ZONOZ local stream).

---

## v1.1.6 — 2026-10-28

### 中文
- **修复：ZONOZ 与 Sonos App 抢音箱控制权。** 暂停后由 Sonos App 接管播放时，
  ZONOZ 的"队列对账"会把音箱抢回来（重建队列）；现在每次采样先判断 TrackURI 是否还是自己的
  串流，不是就主动交还控制权，对账 / 自检 / 续播 / 提前停止重试全部让路。
- 修复：被接管期间界面会显示成清单里的另一首歌（按序号兜底）→ 现在不做任何映射。
- 播放页：音源被接管时显示提示条；点播放 = 把音箱交还给 ZONOZ。
- 通知栏 / 锁屏媒体控件新增**随机**与**列表/单曲循环**按钮（Android 13+）。
- 设置页新增「开源许可」：MIT 全文 + 第三方组件许可清单。
- 播放页底部两个入口的标题与数量字号统一（13sp / 12sp）。

### English
- **Fixed: ZONOZ no longer fights the Sonos app for the speaker.** While the Sonos app owns
  playback, ZONOZ's queue reconciliation used to rebuild the queue and steal it back; it now
  checks whether `TrackURI` is still our stream and hands control over otherwise.
- Fixed the UI showing a different track while another source owned the speaker.
- The player shows a hint when the source is taken over; pressing play hands it back.
- Media controls gained shuffle and repeat buttons (Android 13+).
- New "Open-source licenses" entry in Settings (MIT text + third-party licences).
- Unified the type scale of the two entry cards at the bottom of the player.

---

## v1.1.5 — 2026-10-27

### 中文

**安全修复：公开仓库里不再出现签名口令。**

`app/build.gradle.kts` 原本把开发用 keystore 的口令/别名写成默认回退值，`SonosLocalCast/README.md`
也把口令写在了签名说明表格里，两者都会随开源代码公开。

- 签名相关的三个值改为**必须**来自 `local.properties`（已被 `.gitignore` 忽略）或环境变量
  `ZONOZ_STORE_PASSWORD` / `ZONOZ_KEY_ALIAS` / `ZONOZ_KEY_PASSWORD`；
- 缺任意一项时构建**直接报错**并提示配置方法，不再静默使用某个默认口令；
- README 的签名章节改为引导用户生成自己的 keystore，并把口令写进 `local.properties`。

> 说明：密钥文件（`.jks`）从未进入公开仓库，`local.properties` 也没有；
> 因此泄露的仅是口令字面量，单独并不可用于签名。仍建议在意的用户轮换密钥。

### English

**Security fix: the public repository no longer contains a signing password.**

`app/build.gradle.kts` used the dev keystore's password and alias as hard-coded fallbacks, and
`SonosLocalCast/README.md` listed the password in its signing table — both shipped with the source.

- the three signing values must now come from `local.properties` (gitignored) or the
  `ZONOZ_STORE_PASSWORD` / `ZONOZ_KEY_ALIAS` / `ZONOZ_KEY_PASSWORD` environment variables;
- a missing value fails the build with an explicit message instead of silently signing with a default;
- the README's signing section now walks through generating your own keystore.

> The keystore file itself never entered the public repo (nor did `local.properties`), so the leak
> was the password literal only, which is useless on its own. Rotating the key is still advisable.

---
## v1.1.4 — 2026-10-27

### 中文

**品牌写法统一为 `ZONOZ local stream`**（原来是全大写 `ZONOZ LOCAL STREAM`）。

| 位置 | 现在 |
| --- | --- |
| 安装后的应用名 | `ZONOZ local stream` |
| 播放页顶栏 | `ZONOZ  local stream`（副标改为小写，字距从 1.4 收到 0.4） |
| 设置 → 关于 标题 | `ZONOZ local stream` |
| 通知 / 媒体会话兜底标题 | `ZONOZ local stream` |
| 诊断网页 `<title>` / `<h1>` | `ZONOZ local stream · 本地媒体服务` |
| README / CHANGELOG / AGENTS 文档 | `ZONOZ local stream` |

保持不变：包名 `com.sonoscast.local`、媒体会话 tag、通知封面右下角的 `ZONOZ` 五字母角标、
桌面图标字样 —— 它们都不是"ZONOZ LOCAL STREAM"这个短语的显示位置。

### English

**Brand casing unified to `ZONOZ local stream`** (was all-caps `ZONOZ LOCAL STREAM`).

| where | now |
| --- | --- |
| installed app label | `ZONOZ local stream` |
| player screen top bar | `ZONOZ  local stream` (sub-label lowercased, tracking 1.4 → 0.4) |
| Settings → About title | `ZONOZ local stream` |
| notification / media-session fallback title | `ZONOZ local stream` |
| diagnostic page `<title>` / `<h1>` | `ZONOZ local stream · local media service` |
| README / CHANGELOG / AGENTS | `ZONOZ local stream` |

Unchanged: package name, media-session tag, the five-letter `ZONOZ` badge on notification
artwork, and the launcher icon lettering — none of them render the phrase itself.

---

## v1.1.3 — 2026-10-27

### 中文

这一版把「界面与声音不一致」这一整类问题**从结构上**堵住，而不是逐条打补丁。

**1. 「下一首播放」后音乐立刻跳走、播的不是点的那首**

`insertNext()` 把歌从清单里摘出来时，如果它排在当前曲**之前**，当前曲的位置会前移一位，
但 `queueIndex` 没有跟着减 —— 于是后续重建队列按错误的序号 seek，跳到别的歌上。

修法：摘除时若 `existing < currentIdx` 就 `currentIdx--`，插入后把 `queueIndex` 写回修正值。

**2. 音箱取流 404，导致它跳过整首**

fileId 是 `md5(uri)` 前 12 位，可复算但**不可逆**。进程重启后内存注册表是空的，
而音箱队列里还留着上一轮的 URL —— 音箱来取流时拿到 404，于是**跳过该曲**直接放下一首，
听感就是"音乐自己跳了 / 与界面不一致"。

修法：`handleStream` 找不到时回曲库按 `md5(uri)` 反推并补注册。
真机实测：未注册的 fileId 返回 200 + 正确长度；伪造 id 仍返回 404。

**3. 队列模式下本地续播计时器抢跑（最隐蔽的一条）**

架构规定"队列模式由音箱自己续播，关掉本地计时器"，但这条约束只写在注释里：
拖动进度条会重新排计时器，到点就发 `Next`，把刚播了几秒的歌推走。
实测：`奔跑` 播了 7 秒后被推到 `Boléro`，用户感知是"放完 Amazing Day 直接到 Boléro"。

修法：三处闸门全部按"是否队列模式"判断 —— 排计时器时、本地计时器到点时、闹钟唤醒时。

**4. 结构性改造（防止"漏一个入口"再次发生）**

| 措施 | 实现 |
| --- | --- |
| 单一写入口 | `AppState.updateQueue(next, reason)` 是唯一改清单的函数，内部 `queueRevision++` |
| 单一对账点 | 每 2 秒采样时比较版本号，清单变了但音箱没跟上超过 4 秒 → 整队重建 |
| 按内容认歌 | 用 `TrackURI` 的 fileId 反查清单，序号只做兜底并记日志 |
| 主动自检 | 每约 10 秒比对 `NrTracks` 与清单长度，不一致就重建并报警（60 秒限流） |
| 回归脚本 | `deploy/regress_queue.py`：七条路径，断言"界面 fileId == 音箱 fileId"且"该步骤不应切走当前曲" |

**5. 「下一首播放」不再整队重建**

清单里**没有**的新曲用 Sonos 原生 `EnqueueAsNext=1` 插到当前曲之后（实测落在当前曲 +1 位，
不打断播放）；只有"移动已有曲目"才重建（音箱里那份旧副本无法原地删除，该固件
`RemoveTrackFromQueue` 返回 500）。

**回归结果：7/7 通过**

```
1 曲库点歌   2 清单点歌   3 下一首播放（点在前的歌）  4 下一首播放（清单靠后的歌）
5 切随机     6 切循环     7 机身下一首
```

### English

This release closes the whole "UI and sound disagree" class of bugs **structurally**
instead of patching one entry point at a time.

**1. "Play next" made the music jump immediately and played the wrong song**

When `insertNext()` removed a track that sat **before** the current one, the current
index shifted down by one but `queueIndex` was not decremented, so the following queue
rebuild seeked to the wrong track.

Fix: decrement the current index when the removed item was before it, and write the
corrected index back after inserting.

**2. The speaker got a 404 when streaming, so it skipped the track**

fileId is `md5(uri)[:12]` — recomputable but not invertible. After a process restart the
in-memory registry is empty while the speaker's queue still holds the previous URLs, so
the speaker received 404 and **skipped that track**, which sounds like "the music jumped
by itself / does not match the UI".

Fix: when `handleStream` cannot find the id, resolve it from the library by recomputing
`md5(uri)`. Measured on device: unregistered ids now return 200 with the right length,
bogus ids still return 404.

**3. The local auto-advance timer fired while in queue mode (the most hidden one)**

The architecture says "in queue mode the speaker advances by itself, disable the local
timer", but that rule only lived in a comment: dragging the progress bar re-armed the
timer, and when it fired it sent `Next`, pushing away a song that had just started.
Measured: `奔跑` played 7 s and was pushed to `Boléro`, which the user perceived as
"after Amazing Day it jumped straight to Boléro".

Fix: three guards, all keyed on queue mode — when scheduling, when the local timer fires,
and when the alarm wakes the service.

**4. Structural changes**

| measure | implementation |
| --- | --- |
| single writer | `AppState.updateQueue(next, reason)`, bumps `queueRevision` |
| single reconciler | every 2 s poll compares revisions; unsynced > 4 s ⇒ rebuild |
| content-based mapping | resolve the playing track from `TrackURI`'s fileId, index only as fallback |
| active self-check | every ~10 s compare `NrTracks` with the queue length, rebuild + warn (60 s throttle) |
| regression script | `deploy/regress_queue.py`, seven paths, asserts UI fileId == speaker fileId and "this step must not switch the current track" |

**5. "Play next" no longer rebuilds the whole queue**

A track that is not in the queue is inserted natively with `EnqueueAsNext=1` (measured:
lands at current+1, playback uninterrupted). Only *moving* an existing track rebuilds,
because the duplicate cannot be removed in place on this firmware
(`RemoveTrackFromQueue` returns 500).

**Regression: 7/7 passed.**

---

## v1.1 — 2026-10-27

### 中文

修掉「点 A 播 B」——这次找到了全部四个真凶，每一条都有真机数据佐证。

**1. `NrTracks` 读的是"当前传输"，不是"队列长度"**

入队后、还没把传输切到队列时读 `GetMediaInfo`，它报的是**上一首单曲**的 `NrTracks=1`，
于是队列长度自检必然误判 → 每次都退回单曲推送 → 音箱里根本没有队列。

真机实测（同一台音箱，逐步读回）：

| 步骤 | `NrTracks` |
| --- | --- |
| 传输是单曲 URL | 1 |
| 入队 12 首、未切传输 | **1** ← 读到的是旧传输 |
| 切到队列传输后 | **12** ← 队列真实长度 |

修法：**先 `SetAVTransportURI(x-rincon-queue)` 再读 `NrTracks`**。

**2. fileId 每次注册都变**

旧实现把 `System.nanoTime()` 混进 fileId，同一首歌每次注册都得到不同 ID：

- 自检拿"期望 ID"和"实际入队 ID"对比 → 永远不相等 → 永远判定失败
- 重建队列后旧 URL 全部 404
- 注册表无限增长

修法：改为 `md5(uri)` 取前 12 位，同一首歌永远同一个 ID。

**3. 批量建队列会把"当前曲"改成最后一首**

`registerTrack()` 会顺带写 `MediaRegistry.current`。建 72 首队列时调它，
建完后 current 变成清单最后一首；一旦退回单曲推送，推出去的就是那一首 → **点 A 播 C**。

修法：新增 `registerTrackSilently()`，批量场景只登记不改 current。

**4. 随机与单曲循环的状态互相矛盾**

`orderFor()` 先判"单曲循环"再判"随机"，于是从单曲循环切到随机时清单仍是 **1 首**；
`nativePlayMode()` 同理会给音箱发 `REPEAT_ONE`（单曲无限循环）。

修法：随机优先；开随机时同时把循环置回列表循环。

**附带修复**：点歌序号按"用户点的曲目 id"反查（`pendingTrackId`），不再用会被轮询覆盖的
`queueIndex`；推歌前后 1.5 秒内不跟随音箱序号，避免高亮被抢走。

**验证方式**（脚本 `deploy/verify_playback.py`，四方比对）：

```
曲库 : 期望 fileId=2acd962429bb
日志 : 第 1/72 首「奔跑」          fileId=2acd962429bb
音箱 : NrTracks=72  Track=1       URI=…/stream/2acd962429bb.mp3
  ✓ 点的是这首  ✓ fileId 稳定  ✓ 序号一致  ✓ 真的在放这首  ✓ 无声画不一致
```

回归：机身「下一首」→ App 跟随（`音箱切到了队列第 2 首 →「故乡」`）；机身暂停/恢复 → App 同步。
**5. 切随机/循环不再让当前歌曲从头开始**

重建队列会把播放位置归零，所以切模式后听到的是"这首从头播"。
现在重建前后保留位置：Seek(REL_TIME) 回到原秒数，实测
pause → seek 120s → play → 3 秒后 RelTime=122s，说明这条指令可靠。
重建失败退回单曲推送的那条路也会 seek 回原位置；原本是暂停状态则保持暂停。

**6. 校验不再"读一次就下结论"**

<Track> 与 TrackURI 都改成轮询若干次再判断（音箱切流有延迟）。
之前读得太早会误判成"定位不符"，进而退回单曲推送——那一步会把歌从头播。

**7. 单曲循环 → 随机，播放清单恢复 72 首**

orderFor() 的判定顺序改为"随机优先"，并且开随机时把循环置回列表循环；

ativePlayMode() 也先看随机，避免给音箱发 REPEAT_ONE（单曲死循环）。

> 版本说明：1.0.8 / 1.0.9 / 1.0.10 是内部迭代，统一并入 **1.1** 发布。

### English

Fixed "tap A, plays B" — this time with all four root causes found, each backed by
measurements from the real speaker.

**1. `NrTracks` reports the *current transport*, not the queue length**

Reading `GetMediaInfo` after enqueueing but *before* switching the transport returns the
previous single-URI transport's `NrTracks=1`, so the queue-length self-check always failed
and the app silently fell back to single-URI pushes — the speaker never had a queue.

Measured on the real speaker:

| step | `NrTracks` |
| --- | --- |
| transport = single URL | 1 |
| 12 tracks enqueued, transport unchanged | **1** |
| transport switched to the queue | **12** |

Fix: switch to `x-rincon-queue` **first**, then read `NrTracks`.

**2. fileId changed on every registration**

The old generator mixed `System.nanoTime()` into the id, so the same track got a new id on
every call: the verification compared two different ids (always mismatched), old URLs 404'd
after a rebuild, and the registry grew without bound.

Fix: `md5(uri)` prefix (12 hex chars) — stable per track.

**3. Bulk queue building overwrote "current track"**

`registerTrack()` also writes `MediaRegistry.current`. Enqueuing 72 tracks left `current`
pointing at the **last** track, so the single-URI fallback played that one — tap A, hear C.

Fix: `registerTrackSilently()` for bulk paths.

**4. Shuffle and repeat-one contradicted each other**

`orderFor()` checked repeat-one before shuffle, so switching from single-repeat to shuffle
left the queue at **1 track**; `nativePlayMode()` likewise sent `REPEAT_ONE` to the speaker.

Fix: shuffle wins; enabling shuffle resets repeat to all.

**Also**: the tap index is resolved from the track id the user tapped (`pendingTrackId`)
instead of the poll-driven `queueIndex`, and index syncing is paused for 1.5 s around a cast.

**Verification** (`deploy/verify_playback.py`, four-way comparison):

```
library : expected fileId=2acd962429bb
log     : #1/72 "奔跑"           fileId=2acd962429bb
speaker : NrTracks=72  Track=1   URI=…/stream/2acd962429bb.mp3
  ✓ right track  ✓ stable id  ✓ index matches  ✓ actually playing it
```

Regression: hardware "next" makes the app follow (`queue track 2 →「故乡」`);
hardware pause/resume sync both ways.

---

## v1.0.2 — 2026-10-26

### 中文

这一版解决的是**「音箱上的操作，手机不知道；手机上的操作，音箱不认」**这一类同步问题，
并顺手把播放架构从"逐首推 URL"换成了 **Sonos 原生队列**。

**1. 机身按键暂停/继续，现在会同步到 App**

以前在音箱上按暂停，App 里的按钮还是"播放中"，进度条还会在 `1:27 ↔ 1:28` 之间来回跳。
原因是轮询只更新了系统媒体卡片，没更新 App 内部的播放状态；而暂停后本地仍在"每秒补 1 秒"。

现在：`PAUSED_PLAYBACK` / `PLAYING` 双向同步回 App；暂停时**取消续播计时**；
本地秒数插值只在**确实在播**时进行。

**2. 用音箱音量键，App 里的滑块跟着动**

音量过去是"只写不读"。现在轮询顺带约每 8 秒读一次 `RenderingControl.GetVolume`；
自己刚拖过音量后 3 秒内不读，避免和手指打架。

**3. 改用 Sonos 原生队列 —— 机身的"下一首"终于能用**

旧实现是一首一首推单个 URL，音箱**自己根本没有队列**，机身的上一首/下一首无处可切，
双击播放键也就只能退化成播放/暂停（机身还会橙灯连闪，因为动作失败）。

现在把整条播放清单灌进音箱队列：

```
RemoveAllTracksFromQueue
  → AddMultipleURIsToQueue（整条清单，分批）
  → SetAVTransportURI(x-rincon-queue:<UUID>#0)
  → SetPlayMode（REPEAT_ALL / REPEAT_ONE / SHUFFLE）
  → Seek(TRACK_NR) → Play
```

连带好处：机身按键与手机按键走同一条路、歌与歌之间由音箱自己衔接**没有空隙**、
手机进程被杀这一首也能放完、随机/循环交给音箱执行。

机身按键换了曲，App 靠轮询 `GetPositionInfo` 里的 `<Track>` 序号变化发现，
再同步界面、封面和通知。

**4. 分批入队（每批 12 首，失败自动对半拆分）**

实测这台 Play:1 一次 `AddMultipleURIsToQueue` 最多接受 16 条，18 条就返回 402/500。
所以按 12 首一批，某批失败就对半拆分重试，拆到单条仍失败才退回单曲推送。

**5. 修掉一个我自己引入的回归：「点 A 播 B」**

分批时每批的插入位置一度写死成"第 1 位"，于是第二批开始都挤到队首，
音箱队列变成按批倒序，而 seek 用的是 App 的序号 —— 就播错歌了。
现在插入位置 = 已入队数量 + 1，并加了两道自检：

- 入队后比对 `GetMediaInfo.NrTracks` 与清单长度；
- 定位后比对 `GetPositionInfo.Track` 与期望序号；
- 任一不符就**退回逐首推送**——最坏情况只是回到旧方式，绝不播错歌。

**安全兜底**：清单只有一首、音箱是 S1 老固件、或任何一步 SOAP 失败，都会自动退回单曲推送。

### English

This release fixes the **"speaker does something, the app doesn't know"** class of bugs and
replaces the play pipeline with a **native Sonos queue**.

**1. Play/pause on the speaker now syncs to the app**

Pausing on the speaker used to leave the app showing "playing", and the position kept
oscillating between `1:27 ↔ 1:28` because polling only updated the media session while the
local 1-second interpolation kept running on a paused track.

Now `PAUSED_PLAYBACK` / `PLAYING` are synced both ways, auto-advance is cancelled while
paused, and local interpolation only happens while actually playing.

**2. Speaker volume buttons move the in-app slider**

Volume used to be write-only. Polling now reads `RenderingControl.GetVolume` roughly every
8 seconds, with a 3-second guard after a local change so it doesn't fight your finger.

**3. Native Sonos queue — the speaker's "next" finally works**

The old design pushed one URI at a time, so the speaker had **no queue of its own**; its
previous/next buttons had nothing to switch to, and double-pressing play/pause degraded to a
plain play/pause toggle (with the status LED flashing orange, because the action failed).

The whole play queue is now loaded into the speaker:

```
RemoveAllTracksFromQueue
  → AddMultipleURIsToQueue (whole queue, in batches)
  → SetAVTransportURI(x-rincon-queue:<UUID>#0)
  → SetPlayMode (REPEAT_ALL / REPEAT_ONE / SHUFFLE)
  → Seek(TRACK_NR) → Play
```

Side benefits: hardware and in-app buttons take the same path, tracks butt up against each
other with no gap, playback survives the app being killed, and shuffle/repeat run on the speaker.

When the hardware skips a track, the app notices the `<Track>` index change in
`GetPositionInfo` and updates the UI, artwork and notification.

**4. Batched enqueue (12 per call, split on failure)**

Measured on this Play:1: `AddMultipleURIsToQueue` accepts up to 16 URIs; 18 returns 402/500.
So we enqueue 12 at a time and halve a failing batch recursively, only falling back to
single-URI push if a single item also fails.

**5. Fixed a regression I introduced: "tap A, plays B"**

While batching, every batch hard-coded the insert position to "track 1", so later batches were
pushed to the head and the speaker queue ended up reversed per batch, while seeks used the app's
index — hence the wrong track. Insert positions now increment, plus two guards:

- after enqueueing, compare `GetMediaInfo.NrTracks` with the queue length;
- after seeking, compare `GetPositionInfo.Track` with the expected index;
- on any mismatch, **fall back to single-URI push** — worst case is the old behaviour, never a wrong track.

**Safety net**: single-track queues, S1 firmware, or any failing SOAP step all fall back to
single-URI push automatically.

---

## v1.0.1 — 2026-10-26

### 中文

- **曲库长按「下一首播放」没反应**：清单本来就包含整个曲库，旧逻辑"已在清单就放弃"。
  改成把目标曲挪到当前曲后面。
- **A-Z 索引对中文无效**：汉字全落到 `#`。改用 GB2312 一级汉字区的拼音编码反推首字母，
  排序改为「首字母分组 + ICU 中文排序」，列表与索引条永远一致。
- **歌没放完就跳下一首**：① 排续播用了上一首的时长；② 音箱提前停止被当成播完。
  修法：用曲目自身时长、到点先核实真实进度、提前停止先尝试续播。

### English

- **Long-press "Play next" did nothing**: the queue already contains the whole library, and the
  old code bailed out when the track was already in it. It now moves the track right after the
  current one.
- **A-Z index ignored Chinese**: every Han title collapsed into `#`. The index now derives the
  pinyin initial from the GB2312 level-1 block, and sorting uses "initial group + ICU
  collation for Chinese", so the list and the index bar always agree.
- **Tracks were skipped before they ended**: (1) auto-advance used the *previous* track's
  duration; (2) an early `STOPPED` was treated as "finished". Now it uses the track's own
  duration, verifies the real position before advancing, and tries to resume on an early stop.

---

## v1.0 — 2026-10-26

### 中文

首个公开版本。

- 本地曲库扫描（Android 11+ SAF），搜索 / 排序 / A-Z 索引
- 自建 HTTP 服务推流，支持 Range（可拖动进度）
- 播放清单：随机 / 列表循环 / 单曲循环，三者互斥
- 收藏（长按加入，可筛选只看收藏）
- 8 种语言：简体中文 / 繁體中文 / English / 日本語 / 한국어 / Русский / Español / Français
- 整体字号四档、点击与拖动触感
- 锁屏自动续播（本地计时器 + 精确闹钟双保险）
- 通知与锁屏媒体卡片，专辑图右下角带 ZONOZ 角标
- 自动发现局域网音箱，识别主箱 / 卫星箱，兼容 S1 / S2 固件

### English

First public release.

- Local library scanning (SAF on Android 11+), search / sort / A-Z index
- Built-in HTTP streaming server with Range support (seekable)
- Play queue: shuffle / repeat-all / repeat-one, strictly mutually exclusive
- Favourites (long-press to add, filter to show only favourites)
- 8 languages: 简体中文 / 繁體中文 / English / 日本語 / 한국어 / Русский / Español / Français
- Four UI text-size levels, tap and drag haptics
- Auto-advance with the screen off (local timer + exact alarm)
- Notification / lock-screen media card with a ZONOZ badge on the artwork
- Speaker discovery, main/satellite detection, S1 / S2 firmware aware
