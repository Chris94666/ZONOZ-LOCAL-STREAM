---
name: zonoz-local-stream
description: Iterate the ZONOZ local stream Android app (Sonos local streaming, package com.sonoscast.local). Use for any change, build, device test, release, or protocol work on this project.
---

# ZONOZ local stream · 迭代手册

把手机本地音乐通过 App 内嵌 HTTP 服务 + UPnP/SOAP 推给 Sonos 的 Android 应用。
本 skill 是本项目 1.0.x 全过程的经验沉淀：**先读这一页，再按需读 references/**。

## 0. 铁律（违反必踩坑）

1. **文件内容永远不要用 shell 生成**。改代码/文档/配置一律用 write/edit 工具。
   PowerShell 双引号里反引号是转义符：`` `versionName `` → VT + `ersionName`（本项目真踩过）。
2. **每次改完必须真机构建 + 安装 + 看日志**，不能只看编译通过。
3. **Sonos 侧任何"看起来 200 成功"的调用都要读回来验证**（`GetMediaInfo` / `GetPositionInfo`）。
4. **UI 无法自动化**：`adb shell input tap` 被 ROM 拦，交互验证必须交给用户，并给出可复现步骤。
5. **发版号只增不减**：`versionCode` 每次都 +1，否则老用户无法覆盖安装。

## 1. 工程坐标

| 项 | 值 |
| --- | --- |
| 工作区 | `C:\Users\C\Desktop\AI\SONOS本地播放apk开发` |
| 工程 | `SonosLocalCast/`（包名 `com.sonoscast.local`，日志 TAG `SonosCast`） |
| 当前版本 | 1.1 / versionCode 42（`app/build.gradle.kts`） |
| 公开仓库 | https://github.com/Chris94666/ZONOZ-LOCAL-STREAM |
| 桌面包 | `C:\Users\C\Desktop\ZONOZ 开源发布包\`（源码 / APK / 教程 / 密钥备份） |

## 2. 构建与安装（别手敲四行命令）

```powershell
cd SonosLocalCast
.\build.ps1                 # release，结束打印 APK + 大小 + SHA256
.\build.ps1 -Mode Debug
.\install.ps1               # 装 release + 回读 versionName/versionCode
.\install.ps1 -Log 15       # 装完跟 15 秒 SonosCast 日志
```

脚本自己拼 `JAVA_HOME` / `GRADLE_USER_HOME`。手工构建见
`references/build-and-release.md`（含沙箱三个硬约束）。

## 3. 协议层要点（改 Sonos 代码前必读）

- 控制路径是 `http://<ip>:1400/MediaRenderer/AVTransport/Control`，
  少了 `MediaRenderer/` 直接 **405**。
- `RenderingControl` 与 `GroupRenderingControl` 同时存在，匹配必须精确到
  `:service:RenderingControl:`，否则 `SetVolume` 打到 Group → **401**。
- DIDL-Lite 必须整段 XML 转义后再塞进 SOAP 参数，少转一层 = **402**；
  HTTP `Content-Type` 必须与 DIDL `protocolInfo` 一致，否则 **714**。
- 播放走 **Sonos 原生队列**（`x-rincon-queue`）。分批入队的实测限制、
  插入位置递增、三道自检，全在 `references/sonos-protocol.md`。
- 进度只能轮询（`GetPositionInfo`，1 秒节拍、2 秒一次真实采样）；
  音箱的机身按键不会通知 App，状态同步靠轮询 + `<Track>` 变化发现。

## 4. 设备与验证

```
音箱：Play:1 立体声配对（S2 固件 86.10）
      .4 = 主箱/Coordinator     .3 = 从箱（发 SetAVTransportURI 返 1023 属正常）
手机：Redmi K80 Pro，Android 16
```

电脑端可独立验证（不需要手机）：

```powershell
python deploy\verify_playback.py 歌名   # 四方比对，五项全过才算通过
python deploy\queue_probe.py status      # 读 Track/TrackURI/State
python deploy\queue_probe.py queuebatch 72 5   # 72 首按 12 一批入队并跳第 5 首
python deploy\queue_probe.py browse      # 读队列真实顺序
python deploy\queue_probe.py pause / volume 17 / next
```

判据：`GetMediaInfo` 的 `NrTracks=N` + `CurrentURI=x-rincon-queue:RINCON_…#0`。

## 5. 发版流程（一次到位）

1. `app/build.gradle.kts`：`versionCode + 1`、`versionName` 改新号
2. `.\build.ps1` 构建，记下 SHA-256
3. 更新 `release/更新日志.md`（**中英双语**）→ 同步 `CHANGELOG.md`
4. 同步 `release/1-源码（上传到 GitHub）/` 中改过的文件（含 `README.md`/`AGENTS.md`）
5. `release/2-APK（传到 Releases）/` 放新 APK + `sha256.txt`
6. 拷到桌面 `ZONOZ 开源发布包\`（需要 `danger-full-access`）
7. 提交并推送；建 Release 用 `deploy/publish_release.py`（token 走 `GH_TOKEN` 环境变量）

详细步骤、GitHub 推送的 TLS/凭据处理，见 `references/build-and-release.md`。

## 6. 踩坑清单（按痛苦程度排序）

见 `references/pitfalls.md`。速记：

| 坑 | 症状 | 对策 |
| --- | --- | --- |
| 反引号转义 | 文档里字母莫名消失 | 内容用 write/edit 工具写 |
| 数组 `-notmatch` | 成功被判失败 | 先 `Out-String` 再 `-match` |
| `-Debug` 参数 | 与 CmdletBinding 冲突 | 用 `-Mode Debug` |
| 入队 24 首 | 402/500，退回单曲推送 | 每批 12，失败对半拆 |
| 插入位置写死 1 | 队列按批倒序 → 点 A 播 B | 位置 = 已入队数 + 1 |
| 只推单 URI | 机身"下一首"失效、橙灯闪 | 用原生队列 |
| 本地插值不停 | 暂停时进度 1:27↔1:28 抖 | 只在 PLAYING 时插值 |
| 无队列点歌 | 旧版 `castCurrent()` 绕过队列 | 两条入口都要接队列 |

## 7. 交付习惯

- 每轮改动：**改代码 → 构建 → 安装 → 真机/脚本验证 → 更新 AGENTS.md → 更新日志 → 提交**
- 无法自动验证的部分，明确写出"请你在手机上确认 XXX"，并给出日志关键字
- 任何"我猜"的结论都要用一条可复现的命令证伪或证实，再写进文档
