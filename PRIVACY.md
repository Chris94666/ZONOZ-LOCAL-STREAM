# 隐私声明 / Privacy Policy

ZONOZ local stream（包名 `com.sonoscast.local`）

## 中文

本应用不收集、不上传任何个人数据。

1. **不联网**：应用没有服务器后端，也不含统计、广告、崩溃上报或推送 SDK。
   唯一的外部通信是在局域网内向你的 Sonos 音箱发送 UPnP/SOAP 指令，以及手机自己开启的
   HTTP 媒体服务（供音箱拉取音频，仅监听局域网，不暴露到公网）。
2. **权限与用途**
   - 音乐和音频：读取本地曲库，用于显示与播放
   - 本地网络：发现音箱、推流
   - 通知 / 前台服务：锁屏控制与后台续播
   - 忽略电池优化、精确闹钟（可选）：长时间播放不被系统打断
3. **数据存在哪**：曲库元数据、播放位置、收藏、语言与文字大小等设置，只写入本机的
   SharedPreferences 与 MediaStore 缓存，不上传、不备份到云端。
4. **删除**：卸载应用即清除全部本地数据；没有任何服务端副本（因为没有服务端）。
5. **第三方**：音频传输使用 UPnP/SOAP，仅与局域网内的音箱通信；不向任何第三方发送数据。
   开源组件清单见 App 内「设置 → 开源许可」。

## English

This app collects nothing and uploads nothing.

1. **No backend**: there is no server, no analytics, no ads, no crash reporting and no push SDK.
   The only network traffic is UPnP/SOAP to your Sonos speakers on the local network, plus a
   LAN-only HTTP media server on the phone that the speaker pulls audio from.
2. **Permissions**: audio read (library), local network (discovery/streaming),
   notifications & foreground service (lock-screen controls, background continuation),
   optional battery-optimisation exemption and exact alarms.
3. **Storage**: library metadata, playback position, favourites and UI preferences live in
   on-device SharedPreferences / MediaStore caches only.
4. **Deletion**: uninstalling removes all local data; there is no server-side copy.
5. **Third parties**: none. Open-source components are listed in Settings → Open-source licenses.

联系方式 / Contact: https://github.com/Chris94666/ZONOZ-LOCAL-STREAM/issues
