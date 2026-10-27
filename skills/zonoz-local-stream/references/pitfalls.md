# 踩坑总账（按痛苦程度）

每条都写明：症状 → 根因 → 已采用的写法。**新坑往里加，别另开文档。**

## A. Shell / PowerShell

### A1 反引号吃字母（最贵的一次）

- 症状：`versionName` 写成 `ersionName`、`release\…` 写成 `elease\…`，肉眼极难发现。
- 根因：双引号字符串里反引号是转义符：`` `v ``→VT、`` `r ``→CR、`` `n ``→换行。
- 对策：**文件内容一律用 write/edit 工具写**；必须用 shell 时用单引号 here-string `@'…'@`。
- 兜底：写完后 `[regex]::Matches($raw,'[\x00-\x08\x0B\x0C\x0E-\x1F]')` 查控制字符。

### A2 数组上用 `-notmatch` 不等于布尔

- 症状：`adb install` 打印 `Success`，脚本却报"安装失败"。
- 根因：`$array -notmatch 'x'` 返回**不匹配的元素数组**（非空即真）。
- 对策：`$text = ($out | Out-String)` 再 `-match`。

### A3 `-Debug` 与 `[CmdletBinding()]` 冲突

- 症状：`已为命令多次定义名为"Debug"的参数`。
- 对策：改用 `-Mode Debug`；别给 `$args` 等自动变量赋值（用 `$gradleArgs`）。

### A4 中文输出乱码

- 症状：控制台出现 `����`；`chcp 936` 与 `OutputEncoding=utf-8` 不一致。
- 对策：`$env:PYTHONIOENCODING='utf-8'` 给 Python；需要时 `chcp 65001`。

### A5 Git Bash / MSYS 工具起不来

- 症状：`fatal error - couldn't create signal pipe, Win32 error 5`。
- 根因：沙箱禁命名管道，MSYS2/Cygwin 程序（bash/sed/grep/awk）无法启动；`git.exe` 正常。
- 对策：不要换 bash；文本处理用 harness 的 grep/glob/read/edit 工具。

### A6 git 的 TLS

- 症状：`schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS`。
- 对策：`git -c http.sslBackend=openssl …`（`libcurl-openssl-4.dll` 就在 Git 安装目录里）。

## B. Android / adb（HyperOS / 小米）

| 现象 | 真相 | 对策 |
| --- | --- | --- |
| `pm grant READ_MEDIA_AUDIO` 必失败 | 该权限带 USER_SENSITIVE_WHEN_GRANTED | 让用户在界面授权 |
| `input tap` 被拦 | 缺 INJECT_EVENTS | UI 只能人工验证，给出明确步骤 |
| 截图偶尔全黑 | 截屏时机 | 等 1~2 秒重截 |
| `adb pull` 到中文路径失败 | 路径编码 | 先拉 `$env:TEMP` 再 `Copy-Item` |
| `adb devices` 突然为空 | USB/无线调试掉线 | `install.ps1` 会明确报"没有已连接的设备" |

## C. Sonos 协议

详见 `sonos-protocol.md`。速查：

| 症状 | 根因 |
| --- | --- |
| HTTP 405 | 控制路径漏了 `MediaRenderer/` 或用了 `/MediaServer/` 的路径 |
| HTTP 402 | DIDL 没转义 / 单次入队超过 16 条 |
| HTTP 401 | `SetVolume` 打到 `GroupRenderingControl` |
| HTTP 714 | HTTP `Content-Type` 与 DIDL `protocolInfo` 不一致 |
| 机身"下一首"无效 + 橙灯闪 | 音箱没有队列（只推了单 URI） |
| 点 A 播 B | 分批入队时插入位置写死 1，队列按批倒序 |
| 音箱里只有 1 首（NrTracks=1） | 自检在切传输**之前**读了 NrTracks，读到的是旧单曲传输 |
| 点 A 播 C（兜底推错歌） | 批量建队列时 `registerTrack` 把 `current` 改成了最后一首 |
| 自检永远判定失败 | fileId 混了 `nanoTime`，同一首歌每次注册都不同 |
| 单曲循环→随机后清单只剩 1 首 | `orderFor` 先判 repeat-ONE 再判 shuffle |
| 切随机/循环时当前歌从头播 | 重建队列会把位置归零，要 `Seek(REL_TIME)` 补回（且兜底路径也一样） |
| 放着突然跳到别的歌 | 队列模式下本地续播计时器到点发了 `Next`（拖一次进度条就会重新武装） |
| 某首没播就被跳过 | 进程重启后该 fileId 未注册 → 音箱取流 404 → 自动跳过整首 |
| 暂停后进度抖动 | 暂停时仍在做本地插值 |
| 歌没放完就跳下一首 | ①排续播用了上一首时长 ②提前 STOPPED 被当成播完 |

## D. 工程结构类（App 内部）

- **两条播放入口**：`startTrack()`（上一首/下一首/续播）与 `castCurrent()`（点歌/通知栏）。
  改播放链路时**两个都要改**——1.0.2 只改了前者，导致双击播放键修不好。
- **状态真相只有一份**：`AppState.queue + queueIndex`；轮询读回的 `<Track>` 要写回这里。
- **UI 文案必须走 `str()/strf()`**（Compose 里用 `I18n.t()` 不重组）。
- **i18n 是 127 键 × 8 语言**，加文案要 8 张表都加。
- **降级标志会悄悄改变行为**：`nativeQueueActive` 一旦因兜底被置 false，
  之后"切随机 → 重建队列"就整段被跳过。凡是"用户操作触发重建 + 有降级标志"的功能，
  四个提前返回（`!active` / `busy` / `current==null` / `targets.isEmpty()`）**都要打日志**，
  否则现场只剩"什么都没发生"，无法区分"没触发"和"触发但失败"。
- **重建类操作要保留播放位置**：`RemoveAllTracksFromQueue` + 重新入队 = 位置归零。
  正确顺序是 `Seek(TRACK_NR)` 定位 → `Seek(REL_TIME)` 回原秒 → `Play`；
  原本暂停的，重建完要继续暂停。
- **校验要轮询，不要读一次就判**：音箱切传输有几百毫秒延迟，
  单次读 `<Track>` / `NrTracks` 很容易读到旧值 → 误判 → 走兜底 → 用户听到"重头播"。
- **改清单时下标必须跟着移动修正**：从清单里 `removeAt(index)`，若 `index` 在
  `queueIndex` 之前，当前曲会前移一位，`queueIndex` 必须 `--`；否则后续按序号 seek 会跳歌。
- **fileId 要在重启后仍能解析**：`md5(uri)` 不可逆，内存表一空，音箱队列里的旧 URL 就 404，
  表现为"跳过整首"。HTTP 层必须能回曲库反推（`MediaRegistry.resolve`）。
- **"由音箱自己续播"这类约定必须落成代码闸门**：只写注释不算，
  要在排计时器、计时器到点、闹钟唤醒三处都判断队列模式。
- **单一写入口 + 对账**：清单只允许经 `AppState.updateQueue()` 修改（带版本号），
  Service 每 2s 比较版本，落后就重建；再配一个每 ~10s 的长度自检。
  这样"漏了某个入口"最多造成几秒不一致，不会永久错位。

## E. 判断力教训

1. **"我以为对"最危险**：1.0.2→1.0.5 连续三次翻车，都是因为没读回来验证。
2. **一条可复现的实测胜过三条推理**：24 首上限、队列倒序，都是脚本实测出来的。
3. **用户的现象描述是重要线索**："橙灯连闪"直接指向"音箱收到了指令但执行失败"。
4. **失败要诚实说明**：写"我引入了这个回归"比含糊过去更快收敛。
5. **没验证就不许说"修好了"**：1.0.3 只看了日志（序号对）就宣布完成，实际那次校验
   永远失败、兜底推错歌。现在的规矩：先用 `deploy/verify_playback.py` 四方比对
   （曲库 md5 / App 日志 fileId / 音箱 `<Track>` / 音箱 `TrackURI`），全绿才算通过。
6. **动手前先把协议语义测出来**：`deploy/ntracks_probe.py`（NrTracks 跟着传输走）、
   `deploy/seek_probe.py`（seek 语义 + SHUFFLE 下是否仍准）都是十分钟能跑完的实验，
   比读代码猜快得多。
7. **用户说"时好时坏 / 点几次就正常"，先怀疑状态守卫，不要怀疑玄学。**
   这次的现象是"切随机时当前歌从头播，连点几次后又正常，换首歌又重复"，
   对应的正是三个状态量：

   | 现象 | 背后的状态 |
   | --- | --- |
   | 第一次点有效、后面无效 | `nativeQueueActive` 被兜底置 false / `busy` 挡住 |
   | 刚推完歌时行为异常 | `castSettledAtMs` 的 settle 窗口、音箱切流延迟 |
   | 换了输入源（换歌/换列表）又复现 | 计数器/标志在新一轮推送时被重置 |

   排查手段：**给每个提前返回加一行日志**，然后让用户做一次最小复现。
   日志一出来，是"没触发""触发但被 guard 挡""触发但失败"立刻分得清，
   比反复猜"是不是音箱的问题"快一个数量级。
8. **修完一处要顺着状态机走一遍所有分支**：这次重建队列有两条路
   （成功重建 / 失败退回单曲推送），第一版只在成功路径补了位置恢复，
   用户听到的仍是"从头播"——因为走了兜底。**降级路径和主路径必须同等对待。**
