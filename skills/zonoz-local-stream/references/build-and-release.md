# 构建 · 发版 · 推送

## 1. 工具链（本机已装好，别重装）

| 组件 | 路径 |
| --- | --- |
| JDK 17 | `%LOCALAPPDATA%\AndroidToolchain\jdk-17` |
| Gradle 8.9 | `%LOCALAPPDATA%\AndroidToolchain\gradle-8.9` |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`（platform 35 / build-tools 35.0.0） |
| adb | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` |
| Gradle 缓存 | 工作区 `.gradle-home`（沙箱必须，`%USERPROFILE%\.gradle` 不可写） |

## 2. 沙箱的三个硬约束

1. `%USERPROFILE%\.gradle` 不可写 → `Failed to load native library 'native-platform.dll'`
   → 用 `GRADLE_USER_HOME` 指向 `.gradle-home`（脚本已处理）。
2. Kotlin 编译守护进程连不上（禁本地 socket）→ `gradle.properties` 已设
   `kotlin.compiler.execution.strategy=in-process`。
3. 工作区外只读 → 写桌面需要 `danger-full-access` 授权；`adb pull` 到中文路径会失败，
   先拉到 `$env:TEMP` 再 `Copy-Item`。

另外：工程路径含中文 → `gradle.properties` 里的 `android.overridePathCheck=true` 不能删。

## 3. 手工构建（脚本不可用时）

```powershell
$env:JAVA_HOME        = "$env:LOCALAPPDATA\AndroidToolchain\jdk-17"
$env:GRADLE_USER_HOME = "C:\Users\C\Desktop\AI\SONOS本地播放apk开发\.gradle-home"
cd "C:\Users\C\Desktop\AI\SONOS本地播放apk开发\SonosLocalCast"
& "$env:LOCALAPPDATA\AndroidToolchain\gradle-8.9\bin\gradle.bat" assembleRelease --no-daemon --console=plain --offline
```

产物：`app\build\outputs\apk\release\app-release.apk`（自签名 keystore，口令走 `local.properties`，
不入库）。R8 已开启，APK 约 1.5 MB。

`assembleDebug` 在沙箱里可能因 `C:\Users\C\.android\debug.keystore.lock` 被拒而失败——
那是沙箱限制，不是工程问题。

## 4. 发版清单

| # | 动作 | 产物 |
| --- | --- | --- |
| 1 | `versionCode + 1`，`versionName` 改新号 | `app/build.gradle.kts` |
| 2 | `.\build.ps1` | APK + SHA-256 |
| 3 | 写 `release/更新日志.md`（中英双语） | 供 Release 正文 |
| 4 | 同步 `release/1-源码（上传到 GitHub）/` 改动文件 + `CHANGELOG.md` | 上传树 |
| 5 | `release/2-APK（传到 Releases）/` 放新 APK + `sha256.txt` | 发布附件 |
| 6 | 拷 `release/` → `C:\Users\C\Desktop\ZONOZ 开源发布包\` | 桌面交付 |
| 7 | 更新 `AGENTS.md`（版本行 + 本轮踩坑） | 交接文档 |

### 更新日志格式

每个版本一个 `## vX.Y.Z — 日期`，中英各一节（`### 中文` / `### English`），
写**根因**和**验证方式**，不要只写"修复了 XX"。

## 5. GitHub 推送

沙箱里 schannel 后端会失败（`SEC_E_NO_CREDENTIALS`），**必须换 OpenSSL 后端**：

```powershell
git -c http.sslBackend=openssl ls-remote <repo>     # 读操作
```

推送要 PAT（fine-grained，`Contents: Read and write`）。用一次性 header，
**不写进 git config**：

```powershell
$b64  = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("x-access-token:$token"))
$hdr  = "AUTHORIZATION: basic $b64"
git -c http.sslBackend=openssl -c "http.extraheader=$hdr" push origin main
git -c http.sslBackend=openssl -c "http.extraheader=$hdr" push origin vX.Y.Z
```

**不要**把整个开发仓库推上去：它的历史里有签名密钥文件。
公开内容只用 `release/1-源码（上传到 GitHub）/` 这一棵干净树。

推送后必须回读确认：

```powershell
git -c http.sslBackend=openssl ls-remote --heads --tags <repo>
```

## 6. Release 附件（API）

`deploy/publish_release.py`（仅标准库）：

```powershell
$env:GH_TOKEN = "<pat>"; python deploy\publish_release.py v1.0.2
```

会建 Release（正文取 `release/更新日志.md` 里对应版本段），并上传 APK + `sha256.txt`。
仓库是 public 时可用无认证 API 回读校验（`/releases/latest`）。

## 7. 推送前的安全检查

```powershell
# 公开树里不能有这些东西
git -C release\1-源码（上传到 GitHub）status --short
Select-String -Path <files> -Pattern "192\.168\.|RINCON_|[A-Z0-9]{10,}"  # 内网 IP / 设备 ID / 序列号
Get-ChildItem -Recurse -Include *.jks,local.properties,*.apk          # 密钥/配置/产物
```
