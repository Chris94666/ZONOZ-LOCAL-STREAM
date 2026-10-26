# Sonos 协议层实测记录

本文件只记**在本项目真机上验证过**的事实，不是通用文档。

## 1. 服务与路径

| 服务 | 路径 | 请求方式 |
| --- | --- | --- |
| AVTransport | `/MediaRenderer/AVTransport/Control` | POST + `SOAPACTION: "urn:schemas-upnp-org:service:AVTransport:1#<Action>"` |
| RenderingControl | `/MediaRenderer/RenderingControl/Control` | 同上（`:service:RenderingControl:`） |
| ZoneGroupTopology | `/ZoneGroupTopology/Control` | 同上 |
| ContentDirectory | `/MediaServer/ContentDirectory/Control` | `Browse`，用于读队列 |

踩过的两次路径错误都返回 **HTTP 405 Method Not Allowed**（不是 404），
说明服务存在但路径不对——别误判成"音箱不支持"。

## 2. 原生队列：完整序列

```text
RemoveAllTracksFromQueue
  → AddMultipleURIsToQueue × N（每批 12）
  → SetAVTransportURI(x-rincon-queue:RINCON_xxx#0, 容器 DIDL)
  → SetPlayMode(REPEAT_ALL | REPEAT_ONE | SHUFFLE)
  → Seek(TRACK_NR, n)
  → Play
```

容器 DIDL（`CurrentURIMetaData`）**不能传空**：传空时 `SetAVTransportURI`
返回 200 但传输层不会切换（`GetMediaInfo` 的 `CurrentURI` 仍是旧值）。

```xml
<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/"
           xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
           xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
  <item id="-1" parentID="-1" restricted="true">
    <upnp:class>object.container.playlistContainer</upnp:class>
    <desc id="cdudn" nameSpace="urn:schemas-rinconnet:1/service/DeviceProperties">
      x-rincon-queue:RINCON_xxx#0
    </desc>
  </item>
</DIDL-Lite>
```

## 3. 实测到的两个硬限制

### 3.1 单次入队条数上限（Play:1 / S2 86.10）

| 一次入队 | 结果 |
| --- | --- |
| 8 / 12 / 16 | 200 |
| 18 / 20 / 22 / 24 | 402 / 500 |

→ 每批取 **12**（真实曲目标题比测试串长，留余量）；某一批失败就对半拆分重试，
拆到单条仍失败才放弃整条队列路径。

### 3.2 插入位置必须逐批递增

`AddMultipleURIsToQueue` 的 `DesiredFirstTrackNumberEnqueued` 语义是
"这一批插到第 N 位"。每批都写 1 时，后来的批次会**顶到队首**：

| 写法 | Browse 读回的真实顺序 |
| --- | --- |
| 每批都插第 1 位 | probe05 → probe06 → probe03 → probe04 → probe01 → probe02 |
| 逐批递增 | probe01 → … → probe06 |

后果：`Seek(TRACK_NR = App 序号 + 1)` 落在别的歌上 → 用户看到"点 A 播 B"。

## 4. 三道自检（缺一不可）

1. **入队后**：`GetMediaInfo` 的 `NrTracks` 必须等于清单长度，否则退回单曲推送。
2. **定位后**：`GetPositionInfo` 的 `<Track>` 必须等于期望序号，不符就重定位一次。
3. **播放后**：`GetTransportInfo` 为 PLAYING 才认为成功。

任何一项不符 → **退回逐首推 URL**（旧路径永远是对的，只是没有队列能力）。

## 5. 状态同步（机身按键）

- 音箱按键不会推送任何事件，只能轮询。
- 轮询节拍 1 秒，`GetPositionInfo` + `GetTransportInfo` 每 2 拍采样一次，中间本地插值。
- **只在 PLAYING 时插值**，否则暂停时进度会 1:27↔1:28 抖动。
- 机身切歌靠 `<Track>` 序号变化发现（`syncQueueTrack()`）。
- 音量每约 8 秒读一次 `RenderingControl.GetVolume`；本地设过音量后 3 秒内不读。

## 6. 判定"真的在放完 vs 提前停止"

单曲推送模式下 `PLAYING → STOPPED` 有两种含义，必须区分：

- 记录 `castAtMs` / `castDurationMs`（何时推的、这首多长）；
- 已播时长 < 总时长 − 20s 判为**异常停止** → 先 `Play` 续上（最多 2 次），不要切歌；
- 续播仍失败才按播放模式切下一首。

## 7. 队列模式下的续播归属

进入原生队列后，**关掉本地的续播计时器**：切歌由音箱自己完成，
本地计时器只会造成"提前切歌"。轮询只负责把状态读回 UI。

## 8. 已知正常现象

- 对从箱发 `SetAVTransportURI` 返回 Sonos 私有码 **1023**（Invisible=1 的从箱不接管传输）。
- 机身橙灯连闪 = 音箱收到 Next 但执行失败（没有队列 / 曲目 URL 打不开），不是 App 崩溃。
- 用过期 fileId 的 URL 入队后播放会直接 STOPPED——换当前会话有效的 fileId 即可。

## 9. `NrTracks` 的语义（1.0.7 实测，最容易搞错的一条）

`GetMediaInfo.NrTracks` 报的是**当前 transport** 的曲目数，不是"队列里有多少首"。

| 步骤 | `NrTracks` |
| --- | --- |
| transport = 单曲 URL | 1 |
| 入队 12 首、transport 未变 | **1** |
| 切到 `x-rincon-queue` 后 | **12** |

所以自检顺序只能是：入队 → **切传输** → 读 `NrTracks` → 比对。
顺序写反会导致"每次重建都被自己否决"，表现就是永远退回单曲推送、音箱里没有队列。

## 10. fileId 的确定性

`MediaRegistry.newFileId(uri)` 必须只由 Uri 决定（现为 md5 前 12 位）。
历史上混入过 `System.nanoTime()`，后果：

- 自检拿两个不同 ID 比对 → 永远判定"不一致"
- 重建队列后旧 URL 全部 404
- 注册表无限增长

独立复算：`md5("content://media/external/audio/media/<id>")[:12]`，
可与 `GetPositionInfo` 的 `TrackURI` 直接对照。