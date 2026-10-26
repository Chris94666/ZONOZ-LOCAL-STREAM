# Changelog · 更新日志

ZONOZ LOCAL STREAM — Android app that streams local music from your phone to Sonos over your LAN.
ZONOZ LOCAL STREAM —— 把手机本地音乐通过局域网直接推给 Sonos 播放的 Android 应用。

> Version note: everything developed after 1.0.1 is released as a single **1.0.2**
> (`versionCode 33`, kept monotonic so existing installs can upgrade in place).
> 版本说明：1.0.1 之后的所有迭代合并为 **1.0.2** 一次发布（`versionCode 33`，保持单调递增以便覆盖安装）。

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
