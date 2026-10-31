package com.sonoscast.local.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.sonoscast.local.MainActivity
import com.sonoscast.local.R
import com.sonoscast.local.SonosCastApp
import com.sonoscast.local.core.AppState
import com.sonoscast.local.core.AudioProbe
import com.sonoscast.local.core.CoverLoader
import com.sonoscast.local.core.DeviceStatus
import com.sonoscast.local.core.Hint
import com.sonoscast.local.core.I18n
import com.sonoscast.local.core.LocalMediaServer
import com.sonoscast.local.core.LogBus
import com.sonoscast.local.core.MediaItem
import com.sonoscast.local.core.MediaRegistry
import com.sonoscast.local.core.MimeTypes
import com.sonoscast.local.core.MusicLibrary
import com.sonoscast.local.core.NetUtils
import com.sonoscast.local.core.NotifArt
import com.sonoscast.local.core.RepeatMode
import com.sonoscast.local.core.ServicePhase
import com.sonoscast.local.core.SoapResult
import com.sonoscast.local.core.SoapXml
import com.sonoscast.local.core.SonosDevice
import com.sonoscast.local.core.SonosSoapClient
import com.sonoscast.local.core.SsdpDiscovery
import com.sonoscast.local.core.Str
import com.sonoscast.local.core.Track
import com.sonoscast.local.core.UpnpErrors
import com.sonoscast.local.core.ZoneTopology
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 常驻前台服务：持有 HTTP 服务器 + 发现器 + SOAP 客户端。
 *
 * 之所以把三件事都塞进一个 Service，是因为它们共享同一份生命周期：
 * 服务器一停，正在播放的音箱立刻断流；发现器要能随时重扫；SOAP 要能随时暂停。
 * 拆成多个 Service 只会让生命周期更难对齐。
 *
 * UI 通过 [AppState] 单向读取状态，通过 Intent action 下发指令。
 */
class SonosCastService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serverMutex = Mutex()

    private var server: LocalMediaServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var foregroundStarted = false
    private var progressJob: Job? = null
    private var mediaSession: MediaSessionCompat? = null
    private var pausedAtSec = 0
    private var soapFailStreak = 0
    private var lastUrlHealAt = 0L
    private var lastPollInterval = -1L
    private var lastSessionState = -1
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var lastTransportState: String? = null

    /** 本地续播计时器（进程活着时最可靠的第一道） */
    private val autoAdvanceHandler = Handler(Looper.getMainLooper())

    /**
     * 本轮续播的 token。
     *
     * 本地计时器和精确闹钟**同时**排着，谁先到谁执行；执行时比对 token，
     * 不匹配就直接丢弃 —— 这样不会出现"闹钟补一脚，连跳两首"。
     */
    private var autoAdvanceToken: String = ""

    private var autoAlarmArmed = false

    /** 上次切歌的时间戳，用来抑制"轮询看到旧的 STOPPED"造成的重复跳歌 */
    private var lastAdvanceAt = 0L

    /**
     * 最近一次**推送成功**的时刻与这一首的时长。
     *
     * 用来挡住"提前跳歌"：如果停止来得远早于这一首的时长，多半是网络抖动/音箱自己停了，
     * 不能当成"放完了"。1.0 之前就出现过「歌还没放完就跳下一首」。
     */
    private var castAtMs = 0L
    private var castDurationMs = 0L

    /** 提前停止后尝试续播的次数（每次推送清零），避免无限循环 */
    private var earlyStopRetries = 0

    /**
     * 音箱原生队列模式：把整条播放清单灌进音箱，之后由音箱自己切歌。
     *
     * 这样机身的上一首/下一首（含双击播放键）才有意义，随机/循环也交给音箱执行。
     * 任何一步失败都退回"逐首推 URL"的老路子，保证不会因为队列 API 不能用而放不了歌。
     */
    private var nativeQueueActive = false

    /** 音箱队列里当前装的是哪一份清单（数量 + 首尾 id 的指纹），变了才重建 */
    private var sonosQueueSignature = ""

    /** 上次成功推完队列的时刻（settle 用） */
    private var castSettledAtMs = 0L

    /** 上一次看到的队列序号（1 起）；机身按键切歌就是靠它发现 */
    private var lastQueueTrack = 0

    /**
     * 音箱队列已经和 App 清单对齐到哪个版本（对应 [AppState.queueRevision]）。
     *
     * 只要两者不等，说明有代码改了清单却没同步音箱 —— 由 `reconcileQueueIfNeeded`
     * 在下一个采样周期兜底重建。这一层是"漏了某个入口"的结构性保险。
     */
    private var syncedRevision = -1

    /** 自检重建的限流时刻，避免 A 与 B 不一致时反复重建 */
    private var lastAuditRebuildAtMs = 0L

    /** 对账用：上一次观察到的清单版本，以及它开始"未同步"的时刻 */
    private var lastSeenRevision = -1
    private var unsyncedSinceMs = 0L

    /** 手动拖动音量后的一小段时间内不接受回读，避免滑块来回跳 */
    private var lastVolumeSetAt = 0L

    /** 每 30 分钟续一次 WakeLock / WifiLock，避免 4 小时超时后熄屏断流 */
    private var lockRenewalJob: Job? = null

    private val discovery by lazy { SsdpDiscovery(applicationContext) }
    private val soap by lazy { SonosSoapClient() }

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        LogBus.i("服务已创建")
        initMediaSession()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须最先执行：Android 要求 startForegroundService 后 5 秒内进入前台
        ensureForeground()

        when (intent?.action) {
            ACTION_SCAN -> bootstrap(forceScan = true)
            ACTION_CAST -> {
                bootstrap()
                castCurrent()
            }
            ACTION_ADD_MANUAL -> {
                intent?.getStringExtra(EXTRA_IP)?.let { addManualDevice(it) }
            }
            ACTION_PAUSE -> pauseAll()
            ACTION_RESUME -> resumeAll()
            ACTION_STOP -> stopAll()
            ACTION_NEXT -> playRelative(1)
            ACTION_PREV -> playRelative(-1)
            ACTION_TOGGLE_SHUFFLE -> toggleShuffle()
            ACTION_CYCLE_REPEAT -> cycleRepeat()
            ACTION_QUEUE_CHANGED -> syncSpeakerQueue(
                intent?.getLongExtra(EXTRA_INSERTED_TRACK_ID, -1L) ?: -1L,
                rebuild = intent?.getBooleanExtra(EXTRA_FORCE_REBUILD, false) ?: false
            )
            AutoAdvanceReceiver.ACTION_AUTO_ADVANCE ->
                handleAutoAdvance(intent?.getStringExtra(AutoAdvanceReceiver.EXTRA_TOKEN))
            ACTION_SEEK -> seekTo(intent?.getIntExtra(EXTRA_POSITION, 0) ?: 0)
            ACTION_SET_VOLUME -> setVolume(
                intent?.getIntExtra(EXTRA_VOLUME, AppState.volume.value) ?: AppState.volume.value
            )
            ACTION_SHUTDOWN -> shutdownGracefully()
            ACTION_START, null -> bootstrap()
            else -> bootstrap()
        }

        // START_STICKY：被系统回收后自动重建。重建时 intent 为 null，走 bootstrap 恢复服务器。
        return START_STICKY
    }

    override fun onDestroy() {
        LogBus.i("服务已销毁")
        cancelAutoAdvance()
        scope.cancel()
        runCatching { mediaSession?.release() }
        mediaSession = null
        runCatching { server?.stop() }
        server = null
        releaseLocks()
        foregroundStarted = false
        AppState.resetRuntime()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 启动 / 扫描

    private fun bootstrap(forceScan: Boolean = false) {
        scope.launch {
            if (AppState.phase.value == ServicePhase.STOPPED || AppState.phase.value == ServicePhase.ERROR) {
                AppState.phase.value = ServicePhase.STARTING
            }
            if (!ensureServer()) return@launch
            updateNotification()
            if (forceScan || AppState.devices.value.isEmpty()) {
                scanInternal()
            }
            // 进程被杀过、但音箱还在播：把界面状态从音箱那边找回来
            if (AppState.devices.value.isNotEmpty()) {
                restorePlaybackState()
            }
        }
    }

    /**
     * 向音箱查询当前播放状态并恢复 UI。
     *
     * 为什么必须问音箱而不是读本地：`currentTrack` / `MediaRegistry` 都是进程内存态，
     * 用户从最近任务划掉 App 后全部丢失，而 Sonos 靠着已缓冲的数据还在继续播，
     * 两边状态就脱节了。音箱才是唯一真相来源。
     */
    private suspend fun restorePlaybackState() {
        if (AppState.nowPlayingTitle.value != null) return

        val device = AppState.coordinators().firstOrNull() ?: return
        val snapshot = runCatching { soap.getTransportSnapshot(device) }.getOrNull() ?: return

        if (!snapshot.hasMedia) {
            LogBus.d("音箱当前没有加载媒体，无需恢复")
            return
        }
        if (!snapshot.uri.contains("/stream/")) {
            LogBus.i("音箱当前播的是外部音源，不接管界面：${snapshot.uri.take(48)}")
            return
        }
        if (!snapshot.isPlaying && !snapshot.isPaused) {
            LogBus.d("音箱状态 ${snapshot.state}，不恢复播放界面")
            return
        }

        // registry 已经空了，用元数据里的标题回曲库找同一首（命中就能恢复封面）
        if (MediaRegistry.current.value == null && snapshot.title.isNotBlank()) {
            val matched = MusicLibrary.tracks.value.firstOrNull { it.title == snapshot.title }
            if (matched != null) {
                MediaRegistry.registerTrack(matched)
                LogBus.i("按标题在曲库找回：${matched.title}")
            }
        }

        // 进程被杀过的话播放清单也丢了，按曲库重建一条，保证上/下一首还能用
        if (AppState.queue.value.isEmpty()) {
            val current = MediaRegistry.current.value?.let { item ->
                MusicLibrary.tracks.value.firstOrNull { it.uri == item.uri }
            }
            if (current != null) {
                AppState.startQueue(MusicLibrary.tracks.value, current)
                LogBus.d("恢复播放状态时同步重建播放清单（${AppState.queue.value.size} 首）")
            }
        }

        AppState.nowPlayingTitle.value = snapshot.title.ifBlank {
            MediaRegistry.current.value?.displayName ?: ""
        }
        AppState.positionSec.value = snapshot.positionSec
        AppState.durationSec.value = snapshot.durationSec
        AppState.setStatus(
            device.key,
            if (snapshot.isPlaying) DeviceStatus.PLAYING else DeviceStatus.PAUSED
        )
        lastTransportState = snapshot.state
        updatePlaybackState(
            if (snapshot.isPlaying) PlaybackStateCompat.STATE_PLAYING
            else PlaybackStateCompat.STATE_PAUSED
        )
        LogBus.i(
            "已从音箱恢复播放状态：${snapshot.title.ifBlank { "未知曲目" }} · " +
                "${snapshot.state} · ${Track.formatDuration(snapshot.positionSec * 1000)}"
        )
        // 只有真的在播才排续播：暂停状态排了会"自己播起来"
        if (snapshot.isPlaying) scheduleAutoAdvance()
    }

    private suspend fun scanInternal() {
        if (AppState.scanning.value) return
        AppState.scanning.value = true
        LogBus.i("开始 SSDP 发现（超时 ${SCAN_TIMEOUT_MS}ms）…")
        try {
            val found = discovery.discover(SCAN_TIMEOUT_MS)
            if (found.isEmpty()) {
                if (AppState.devices.value.isEmpty()) {
                    AppState.phase.value = ServicePhase.STANDBY
                }
                AppState.hint.value = Hint(Str.NO_DEVICE)
                LogBus.w("未发现音箱 → 纯本地待命模式（HTTP 服务继续运行）")
            } else {
                AppState.upsertDevices(found)
                AppState.phase.value = ServicePhase.READY
                AppState.hint.value = null
                LogBus.i(
                    "发现 ${found.size} 台：" +
                        found.joinToString("、") { "${it.displayName}(${it.ip})" }
                )
                // 顺手把主/从关系也标出来，UI 立刻能看到"主 RF / 从 LF"
                refreshTopology(found)
            }
            updateNotification()
        } catch (t: Throwable) {
            LogBus.e("扫描失败：${t.javaClass.simpleName} ${t.message}")
        } finally {
            AppState.scanning.value = false
        }
    }

    private fun addManualDevice(ip: String) {
        scope.launch {
            LogBus.i("手动探测音箱：$ip")
            val device = discovery.probeByIp(ip)
            if (device == null) {
                AppState.hint.value = Hint(Str.ERR_OFFLINE)
                LogBus.e("手动添加失败：$ip")
                return@launch
            }
            AppState.upsertDevices(listOf(device))
            AppState.selectedKeys.value = AppState.selectedKeys.value + device.key
            AppState.phase.value = ServicePhase.READY
            LogBus.i("已添加：${device.displayName} · ${device.modelName} · ${device.ip}")

            // UPnP 是否可用的现场判定：描述文件读不到，说明 1400 端口可能通但 UPnP 服务被关，
            // 或者这台根本不是 Sonos。两种情况都值得明确告诉用户，而不是等到 701 才猜。
            AppState.hint.value = if (device.isSonos) null else Hint(Str.ERR_OFFLINE)
            updateNotification()
        }
    }

    // ---------------------------------------------------------------- 服务器

    private suspend fun ensureServer(force: Boolean = false): Boolean = serverMutex.withLock {
        withContext(Dispatchers.IO) {
            val ip = NetUtils.currentLanIp(this@SonosCastService)
            if (ip.isNullOrBlank()) {
                AppState.phase.value = ServicePhase.ERROR
                AppState.hint.value = Hint(Str.ERR_OFFLINE)
                LogBus.e("未获取到局域网 IP，无法启动 HTTP 服务")
                return@withContext false
            }

            if (!force && server != null && AppState.serverPort.value > 0 && AppState.localIp.value == ip) {
                return@withContext true
            }

            stopServerLocked()

            // 8080 优先，被占用则依次退让；最后一个 0 表示由系统分配
            val candidates = intArrayOf(LocalMediaServer.DEFAULT_PORT, 8081, 8765, 0)
            for (port in candidates) {
                val attempt = runCatching {
                    val candidate = LocalMediaServer(this@SonosCastService, ip, port)
                    try {
                        candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                        candidate
                    } catch (t: Throwable) {
                        // bind 失败时 NanoHTTPD 已经创建了 ServerSocket，必须显式关掉
                        runCatching { candidate.stop() }
                        throw t
                    }
                }
                val started = attempt.getOrNull()
                if (started != null) {
                    server = started
                    AppState.localIp.value = ip
                    AppState.serverPort.value = started.listeningPort
                    if (AppState.phase.value != ServicePhase.READY) {
                        AppState.phase.value = if (AppState.devices.value.isEmpty()) {
                            ServicePhase.STANDBY
                        } else {
                            ServicePhase.READY
                        }
                    }
                    LogBus.i("HTTP 媒体服务已启动：http://$ip:${started.listeningPort}/ （绑定 $ip）")
                    return@withContext true
                }
                LogBus.w("端口 $port 启动失败：${attempt.exceptionOrNull()?.message}")
            }

            // 最后兜底：绑定 0.0.0.0（URL 仍用 Wi-Fi 地址，只是监听范围放宽）
            val fallback = runCatching {
                val candidate = LocalMediaServer(this@SonosCastService, null, LocalMediaServer.DEFAULT_PORT)
                try {
                    candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    candidate
                } catch (t: Throwable) {
                    runCatching { candidate.stop() }
                    throw t
                }
            }
            val fallbackServer = fallback.getOrNull()
            if (fallbackServer != null) {
                server = fallbackServer
                AppState.localIp.value = ip
                AppState.serverPort.value = fallbackServer.listeningPort
                LogBus.w("已回退到 0.0.0.0 监听：http://$ip:${fallbackServer.listeningPort}/")
                return@withContext true
            }

            AppState.phase.value = ServicePhase.ERROR
            AppState.hint.value = Hint(Str.ERR_UNKNOWN)
            LogBus.e("HTTP 服务启动失败：${fallback.exceptionOrNull()?.message}")
            false
        }
    }

    /** 只能在持有 serverMutex 时调用 */
    private fun stopServerLocked() {
        runCatching { server?.stop() }
        server = null
        AppState.serverPort.value = 0
    }

    private suspend fun restartServerForRetry(): Boolean {
        serverMutex.withLock {
            withContext(Dispatchers.IO) { stopServerLocked() }
        }
        return ensureServer(force = true)
    }

    // ------------------------------------------------------------------ 推送

    private fun castCurrent(resumeAtSec: Int = 0) {
        val item = MediaRegistry.current.value
        if (item == null) {
            AppState.hint.value = Hint(Str.PICK_SONG)
            return
        }
        val targets = AppState.selectedDevices()
        if (targets.isEmpty()) {
            AppState.hint.value = Hint(Str.NO_DEVICE)
            return
        }
        if (AppState.busy.value) {
            LogBus.w("上一次推送尚未结束，忽略本次请求")
            return
        }
        scope.launch {
            // 点歌也要先试原生队列 —— 这里曾经只调 performCast，
            // 结果只有"上一首/下一首"会建队列，用户点歌时永远是单曲推送（1.0.2 的漏改）。
            // 序号必须取"用户点的那首"，不能直接用 queueIndex：
            // 轮询随时可能在 cast 之前把它改成音箱当前那首。
            if (castNativeQueue(targets, AppState.takePendingQueueIndex(), resumeAtSec = resumeAtSec)) {
                return@launch
            }
            nativeQueueActive = false
            performCast(item, targets)
            // 退回单曲推送时同样要补回播放位置，否则"从 Sonos App 收回来的歌"会从头播
            if (resumeAtSec > 0) {
                targets.forEach { device ->
                    runCatching { soap.seek(device, resumeAtSec.toLong()) }
                }
                LogBus.i("单曲推送后恢复到 ${resumeAtSec}s")
            }
        }
    }

    private suspend fun performCast(item: MediaItem, targets: List<SonosDevice>) {
        if (blockIfUnsupported(item.uri, item.displayName)) return
        AppState.busy.value = true
        cancelAutoAdvance()
        AppState.hint.value = null
        // 每次推送时续期一次锁，避免超过 4 小时后熄屏被系统掐断
        renewLocks()
        try {
            if (!ensureServer()) return

            val ip = AppState.localIp.value ?: return
            val port = AppState.serverPort.value
            var url = LocalMediaServer.buildStreamUrl(ip, port, item)
            AppState.streamUrl.value = url
            AppState.nowPlayingTitle.value = item.displayName
            AppState.positionSec.value = 0L
            AppState.durationSec.value = 0L

            LogBus.i("────────────────────────")
            LogBus.i("推送「${item.displayName}」→ ${targets.joinToString("、") { it.displayName }}")
            LogBus.i("CurrentURI = $url")
            if (!MimeTypes.isSonosSupported(item.mimeType)) {
                LogBus.w("${item.mimeType} 不在 Play:1 常见支持列表内，可能返回 714")
            }

            selfCheck(url)

            // 推之前先问一次拓扑：立体声配对里的从箱不能单独下 Transport 指令（会回 1023），
            // 提前识别出来就能直接跳过并标成"随主箱"，而不是报一个假的失败。
            refreshTopology(targets)

            // 用刷新后的设备信息重取一次（isSatellite/channelRole 已更新）
            val liveTargets = targets.map { old ->
                AppState.devices.value.firstOrNull { it.key == old.key } ?: old
            }

            // S1 / S2 混选提示：两代固件的家庭不能互相组队，我们都是独立推送，
            // 但用户看到"一台响一台不响"时需要一个解释。
            val hasS1 = liveTargets.any { it.isLegacyS1 }
            val hasS2 = liveTargets.any { it.swGen >= 2 }
            if (hasS1 && hasS2) {
                AppState.hint.value = Hint(Str.HINT_MIXED_GEN)
            }

            var results = liveTargets.map { pushOne(it, url, item) }

            if (results.any { it.errorCode == 701 }) {
                LogBus.w("检测到 701 —— 重建 HTTP 服务（换端口）后对所有音箱重试一次")
                if (restartServerForRetry()) {
                    val newIp = AppState.localIp.value
                    val newPort = AppState.serverPort.value
                    if (newIp != null && newPort > 0) {
                        url = LocalMediaServer.buildStreamUrl(newIp, newPort, item)
                        AppState.streamUrl.value = url
                        LogBus.i("重试 CurrentURI = $url")
                        results = liveTargets.map { pushOne(it, url, item) }
                    }
                } else {
                    LogBus.e("重建 HTTP 服务失败，放弃重试")
                }
            }

            val played = results.count { it.ok }
            val following = results.count { it.skipped }
            val failed = results.count { !it.ok && !it.skipped }

            if (failed == 0) {
                AppState.hint.value = null
                LogBus.i(
                    "完成：$played 台主箱播放" +
                        if (following > 0) "，$following 台从箱随主箱同步" else ""
                )
                lastTransportState = "PLAYING"
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                // 记下"这一首是什么时候推的、有多长"，供 STOPPED 判定用
                castAtMs = SystemClock.elapsedRealtime()
                earlyStopRetries = 0
                castDurationMs = currentTrack()?.durationMs ?: 0L
                // 推送成功 → 立刻排好"这一首放完怎么办"，不再依赖轮询能不能发出去
                scheduleAutoAdvance()
            } else {
                val firstFailure = results.firstOrNull { !it.ok && !it.skipped }
                AppState.hint.value = Hint(
                    UpnpErrors.key(firstFailure?.errorCode),
                    listOf(AppState.streamUrl.value.orEmpty(), item.mimeType)
                )
                LogBus.e(
                    "完成：$played 成功 / $following 从箱 / $failed 失败；首个失败 " +
                        "${firstFailure?.errorCode ?: "-"} ${firstFailure?.message ?: ""}"
                )
            }
        } catch (t: Throwable) {
            LogBus.e("推送异常：${t.javaClass.simpleName} ${t.message}")
            AppState.hint.value = Hint(Str.ERR_UNKNOWN)
        } finally {
            AppState.busy.value = false
            updateNotification()
        }
    }

    private data class PushResult(
        val device: SonosDevice,
        val ok: Boolean,
        val errorCode: Int?,
        val message: String?,
        /** 从设备被跳过的情形：既不算成功也不算失败 */
        val skipped: Boolean = false
    )

    /**
     * 刷新区域组拓扑，把参与本次推送的设备标好主/从角色。
     *
     * 只问一台即可 —— GetZoneGroupState 返回的是整个家庭的拓扑。
     * 拿不到就算了（网络抖动/固件差异），后面 pushOne 还有 1023 兜底。
     */
    private suspend fun refreshTopology(targets: List<SonosDevice>) {
        val probe = targets.firstOrNull() ?: return
        val body = soap.getZoneGroupState(probe) ?: run {
            LogBus.d("GetZoneGroupState 无响应，跳过拓扑识别")
            return
        }
        val nodes = ZoneTopology.parse(body)
        if (nodes.isEmpty()) {
            LogBus.d("拓扑解析为空，跳过（推送时用 1023 兜底）")
            return
        }
        AppState.applyTopology(nodes.associateBy { it.uuid })
        val summary = nodes.joinToString("、") { node ->
            val role = if (node.isSatellite()) "从${node.channel()}" else "主${node.channel()}"
            "${node.uuid.takeLast(8)}:$role"
        }
        LogBus.i("区域组拓扑：$summary")
    }

    /**
     * 单台推送：SetAVTransportURI → Play。
     * 两台音箱是各自独立拉流，不走 x-rincon 组播 —— S2 下手动组网行为不稳定。
     */
    private suspend fun pushOne(device: SonosDevice, url: String, item: MediaItem): PushResult {
        // 拓扑已判定是从设备：直接跳过，不发请求。它的声音由主箱带出来。
        if (device.isSatellite) {
            AppState.setStatus(device.key, DeviceStatus.FOLLOWING)
            LogBus.i(
                "${device.displayName}(${device.ip}) 是配对从设备" +
                    (if (device.channelRole.isNotBlank()) "［${device.channelRole}］" else "") +
                    "，随主箱同步，跳过独立推送"
            )
            return PushResult(device, false, null, "satellite", skipped = true)
        }

        AppState.setStatus(device.key, DeviceStatus.PUSHING)
        val metadata = SoapXml.didlLite(item.displayName, url, item.mimeType)

        // S1 兼容预埋：老固件走最小指令集（SetAVTransportURI + Play + RenderingControl），
        // 并对格式超规格的情况提前提醒，避免只看到一句 714。
        if (device.isLegacyS1) {
            LogBus.i(
                "${device.displayName} 是 S1 固件" +
                    (if (device.softwareVersion.isNotBlank()) "（${device.softwareVersion}）" else "") +
                    "，按最小指令集推送"
            )
            when (item.mimeType) {
                "audio/flac", "audio/wav", "audio/aiff" ->
                    LogBus.w(
                        "S1 对 ${item.mimeType} 的解码上限约为 16bit/48kHz，" +
                            "超规格文件会被音箱自己降采样（不是传输链路的问题）"
                    )
            }
        }

        when (val setResult = soap.setAvTransportUri(device, url, metadata)) {
            is SoapResult.Success -> Unit

            is SoapResult.Fault -> {
                // 拓扑没拿到时的兜底：1023 = Sonos 说"这是从设备"，不是故障
                if (setResult.errorCode == SONOS_ERROR_SATELLITE) {
                    AppState.setStatus(device.key, DeviceStatus.FOLLOWING)
                    LogBus.i("${device.displayName} 返回 1023 → 判定为配对从设备，随主箱同步")
                    return PushResult(device, false, null, "satellite", skipped = true)
                }
                AppState.setStatus(device.key, DeviceStatus.ERROR, UpnpErrors.describe(setResult.errorCode))
                LogBus.e(
                    "${device.displayName} SetAVTransportURI 失败：" +
                        "${setResult.errorCode} ${setResult.description}"
                )
                if (device.isLegacyS1 && setResult.errorCode in S1_UNSUPPORTED_ACTIONS) {
                    LogBus.w(
                        "该错误码在 S1 老固件上通常代表「此动作不被支持」。已按最小指令集重试；" +
                            "若持续失败，请把调试页日志反馈回来做针对性兼容。"
                    )
                }
                return PushResult(device, false, setResult.errorCode, setResult.description)
            }

            is SoapResult.TransportError -> {
                AppState.setStatus(device.key, DeviceStatus.ERROR, setResult.message)
                return PushResult(device, false, null, setResult.message)
            }
        }

        when (val playResult = soap.play(device)) {
            is SoapResult.Success -> {
                AppState.setStatus(device.key, DeviceStatus.PLAYING)
                LogBus.i("${device.displayName} 已开始播放")
                return PushResult(device, true, null, null)
            }

            is SoapResult.Fault -> {
                AppState.setStatus(device.key, DeviceStatus.ERROR, UpnpErrors.describe(playResult.errorCode))
                LogBus.e("${device.displayName} Play 失败：${playResult.errorCode} ${playResult.description}")
                return PushResult(device, false, playResult.errorCode, playResult.description)
            }

            is SoapResult.TransportError -> {
                AppState.setStatus(device.key, DeviceStatus.ERROR, playResult.message)
                return PushResult(device, false, null, playResult.message)
            }
        }
    }

    /**
     * 本机自检：确认自己的 HTTP 服务确实能响应 HEAD + Range。
     * 这一步只能证明"服务端逻辑没问题"，不能证明"音箱能连到手机"，
     * 后者要靠 UI 里给出的外网/局域网访问建议。
     */
    private fun selfCheck(url: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).head().build()
                probeClient.newCall(request).execute().use { response ->
                    LogBus.i(
                        "自检 HEAD → HTTP ${response.code} · " +
                            "Type=${response.header("Content-Type")} · " +
                            "Accept-Ranges=${response.header("Accept-Ranges")} · " +
                            "Length=${response.header("Content-Length")}"
                    )
                }
            }.onFailure { LogBus.w("自检失败（服务可能尚未就绪）：${it.message}") }
        }
    }

    // ------------------------------------------------------------ 播放控制

    /** 曲库里对应"当前正在推的那首歌" */
    private fun currentTrack(): com.sonoscast.local.core.Track? {
        val uri = MediaRegistry.current.value?.uri ?: return null
        return MusicLibrary.tracks.value.firstOrNull { it.uri == uri }
    }

    /**
     * 上一首 / 下一首 / 播完续播都走这里。
     *
     * 1.8.2 起"下一首"只认 [AppState.queue]（播放清单），不再直接逛曲库：
     * - 单曲循环：上/下一首都停在当前曲（用户明确要求"下一曲应仍是当前音乐"）
     * - 随机 / 列表循环：按清单前/后走，末尾自动回绕
     * 清单为空时（比如进程刚被拉起还没点过歌）先按曲库补一条，保证按钮不失效。
     */
    private fun playRelative(offset: Int, auto: Boolean = false) {
        // 任何切歌动作都先取消上一轮的续播计时，避免"刚切完旧闹钟又补一脚"
        cancelAutoAdvance()
        lastAdvanceAt = SystemClock.elapsedRealtime()

        // 原生队列模式：直接让音箱切 —— 和机身按键走的是同一条路径，
        // 这样"手机点下一首"和"音箱按下一首"行为完全一致。
        if (nativeQueueActive) {
            val targets = AppState.controlTargets().filterNot { it.isSatellite }
            if (targets.isNotEmpty()) {
                scope.launch {
                    targets.forEach { device ->
                        val result =
                            if (offset > 0) soap.nextTrack(device) else soap.previousTrack(device)
                        if (result !is SoapResult.Success) {
                            LogBus.w("音箱切歌失败：${describeSoapFailure(result)}")
                        }
                    }
                }
                // 序号交给轮询纠正，这里只先把界面推进一格，点击才有反馈
                AppState.step(offset)?.let { MediaRegistry.registerTrack(it) }
                return
            }
        }

        ensureQueueReady()

        val repeatOne = AppState.repeatMode.value == RepeatMode.ONE && !AppState.shuffle.value
        if (repeatOne) {
            val current = AppState.currentFromQueue() ?: currentTrack()
            if (current != null) {
                LogBus.i("单曲循环：保持在「${current.title}」")
                startTrack(current)
            } else {
                stopAll()
            }
            return
        }

        val next = AppState.step(offset)
        if (next == null) {
            LogBus.i("播放清单为空（${playModeLabel()}），停止")
            stopAll()
            return
        }
        LogBus.i("${if (offset > 0) "下一首" else "上一首"} →「${next.title}」（${playModeLabel()}）")
        startTrack(next)
    }

    // ------------------------------------------------------- 音箱原生队列

    /**
     * 把整条播放清单灌进音箱队列，并从第 [index] 首开始播。
     *
     * 相比"一首一首推 URL"：
     * 1. 机身的上一首/下一首（含双击播放键）终于有东西可切；
     * 2. 随机 / 循环由音箱自己执行，歌与歌之间没有空隙；
     * 3. 手机进程被杀也不影响这一首放完。
     *
     * @return false = 这条路走不通，调用方应退回单曲推送
     */

    /**
     * 推流前预检：超出音箱规格（>48 kHz，或 WAV/AIFF >16 bit）的文件直接拦下。
     * 否则音箱会拉一段数据后自己跳过，用户看到的是"没声音 + 自动跳下一首"的假 bug。
     */
    private fun blockIfUnsupported(uri: android.net.Uri, label: String): Boolean {
        val fmt = AudioProbe.probe(this, uri) ?: return false
        if (!AudioProbe.isUnsupported(fmt)) return false
        val desc = AudioProbe.describe(fmt)
        LogBus.w("跳过不支持的格式：" + label + " · " + desc + "（音箱上限 48 kHz，WAV/AIFF 16 bit）")
        AppState.hint.value = Hint(Str.UNSUPPORTED_FORMAT, listOf(desc))
        return true
    }

    private suspend fun castNativeQueue(
        targets: List<SonosDevice>,
        index: Int,
        attempt: Int = 0,
        resumeAtSec: Int = 0,
        autoPlay: Boolean = true
    ): Boolean {
        val queue = AppState.queue.value
        // 先看这首能不能放（超出音箱规格就别推，免得被静默跳过）
        queue.getOrNull(index)?.let { t ->
            if (blockIfUnsupported(t.uri, t.title)) return true
        }
        // 只有一首时没必要建队列（单曲循环还更简单），S1 老固件也不冒险
        if (queue.size < 2) {
            LogBus.d("播放清单只有 ${queue.size} 首，不建队列，走单曲推送")
            return false
        }

        val ip = AppState.localIp.value ?: run {
            LogBus.w("本机局域网 IP 未知，无法建队列，走单曲推送")
            return false
        }
        val port = AppState.serverPort.value
        if (port <= 0) {
            LogBus.w("HTTP 服务未就绪（port=$port），无法建队列，走单曲推送")
            return false
        }

        val speakers = targets.filterNot { it.isSatellite }.filterNot { it.isLegacyS1 }
        if (speakers.isEmpty()) {
            LogBus.w("选中的设备里没有可用主箱（从箱/S1 被排除），走单曲推送")
            return false
        }

        // 签名覆盖**整条清单的顺序**：只比首尾会让 insertNext 之类的中途重排漏掉，
        // 结果音箱还按旧顺序放、App 按新序号 seek → 播错歌。
        val signature = queueSignature(queue)
        val needRebuild = !nativeQueueActive || signature != sonosQueueSignature

        val safeIndex = index.coerceIn(0, queue.lastIndex)
        val targetTrack = queue[safeIndex]
        // 注意：用 Silently，不要动 MediaRegistry.current —— 否则批量建队列会把
        // "当前曲"改成清单最后一首，兜底单曲推送时就变成"点 A 播 C"。
        val targetFileId = MediaRegistry.registerTrackSilently(targetTrack).fileId
        LogBus.d("推队列：第 ${safeIndex + 1}/${queue.size} 首「${targetTrack.title}」（$targetFileId）")

        AppState.busy.value = true
        return try {
            for (speaker in speakers) {
                AppState.setStatus(speaker.key, DeviceStatus.PUSHING)

                if (needRebuild) {
                    soap.removeAllTracksFromQueue(speaker)
                    // 把整条清单准备成 (URL, DIDL) 再分批入队
                    val entries = queue.map { track ->
                        val item = MediaRegistry.registerTrackSilently(track)
                        val url = LocalMediaServer.buildStreamUrl(ip, port, item)
                        url to SoapXml.didlLite(track.title, url, item.mimeType, track.id.toString())
                    }
                    if (!enqueueAll(speaker, entries)) {
                        AppState.setStatus(speaker.key, DeviceStatus.IDLE)
                        return false
                    }
                    // 必须先切传输再看长度！
                    // GetMediaInfo 的 NrTracks 读的是**当前传输**：
                    // 切之前传输还停在上一首的单曲 URL 上，会报 1（实测证实），
                    // 于是自检必然误判 → 白白退回单曲推送（1.0.2–1.0.6 的假警报）。
                    val transport = soap.setQueueTransport(speaker)
                    if (transport !is SoapResult.Success) {
                        LogBus.w("切换队列传输失败：${describeSoapFailure(transport)}，退回逐首推送")
                        AppState.setStatus(speaker.key, DeviceStatus.IDLE)
                        return false
                    }
                    // 自检：队列长度必须和清单一致，否则"点 A 播 B"
                    val queueSize = soap.getQueueSize(speaker)
                    if (queueSize != null && queueSize != queue.size) {
                        LogBus.w(
                            "音箱队列长度不符（音箱 $queueSize / 清单 ${queue.size}），" +
                                "为避免播错歌，退回逐首推送"
                        )
                        AppState.setStatus(speaker.key, DeviceStatus.IDLE)
                        return false
                    }
                }

                soap.setPlayMode(speaker, nativePlayMode())
                soap.seekToTrack(speaker, safeIndex + 1)
                // 自检：定位到第 index+1 首，读回来确认一次，否则会"点 A 播 B"
                // 刚切传输时音箱可能还没把 <Track> 更新过来，读一次就下结论太武断，
                // 轮询几次（1.0.9 之前只读一次 → 误判 → 退回单曲推送 → 从头播）
                var positioned: Int? = null
                for (round in 0 until 4) {
                    positioned = soap.getQueueTrackNumber(speaker)
                    if (positioned == null || positioned == safeIndex + 1) break
                    if (round < 3) kotlinx.coroutines.delay(300)
                }
                if (positioned != null && positioned != safeIndex + 1) {
                    LogBus.w("队列定位不符（期望第 ${safeIndex + 1} 首，实际第 $positioned 首），重新定位")
                    soap.seekToTrack(speaker, safeIndex + 1)
                    var retry: Int? = null
                    for (round in 0 until 3) {
                        retry = soap.getQueueTrackNumber(speaker)
                        if (retry == null || retry == safeIndex + 1) break
                        if (round < 2) kotlinx.coroutines.delay(300)
                    }
                    if (retry != null && retry != safeIndex + 1) {
                        LogBus.w("队列定位仍不符（第 $retry 首），退回逐首推送")
                        AppState.setStatus(speaker.key, DeviceStatus.IDLE)
                        return false
                    }
                }
                // 重建队列会把播放位置归零。切随机/循环触发重建时，
                // 用户听到的会是"当前这首从头开始"——所以这里把位置补回去。
                if (resumeAtSec > 0) {
                    runCatching { soap.seek(speaker, resumeAtSec.toLong()) }
                    LogBus.d("重建后恢复到 ${resumeAtSec}s")
                }
                if (autoPlay) {
                    val play = soap.play(speaker)
                    if (play !is SoapResult.Success) {
                        LogBus.w("队列播放失败：${describeSoapFailure(play)}，退回逐首推送")
                        AppState.setStatus(speaker.key, DeviceStatus.IDLE)
                        return false
                    }
                } else {
                    LogBus.d("保持暂停：只重建队列并定位到 ${resumeAtSec}s，不发送 Play")
                }
                // 终极自检：真的在读目标那一首的流吗？
                // 序号对得上不代表歌对得上（顺序不一致时序号会正好落在别的歌上）。
                // 音箱切流有 300~800ms 延迟，所以要轮询几次再判定，不能读一次就下结论。
                var actualUri: String? = null
                var matched = false
                for (round in 0 until 4) {
                    actualUri = soap.getPlaybackTrackUri(speaker)
                    if (actualUri != null && actualUri.contains(targetFileId)) {
                        matched = true
                        break
                    }
                    if (round < 3) kotlinx.coroutines.delay(400)
                }
                if (!matched && actualUri != null) {
                    val playing = actualUri.substringAfterLast('/')
                    LogBus.w("声画不一致：期望 $targetFileId，音箱在放 $playing（第 ${attempt + 1} 次）")
                    if (attempt == 0) {
                        nativeQueueActive = false
                        sonosQueueSignature = ""
                        return castNativeQueue(targets, safeIndex, attempt + 1)
                    }
                    AppState.setStatus(speaker.key, DeviceStatus.IDLE)
                    return false
                }
                if (matched) LogBus.d("声画一致：$targetFileId ✓")
                AppState.setStatus(
                    speaker.key,
                    if (autoPlay) DeviceStatus.PLAYING else DeviceStatus.PAUSED
                )
            }

            sonosQueueSignature = signature
            nativeQueueActive = true
            lastQueueTrack = safeIndex + 1
            castSettledAtMs = SystemClock.elapsedRealtime()
            // 音箱队列已经和当前清单对齐，对账/自检都以这个版本为基准
            syncedRevision = AppState.queueRevision
            lastSeenRevision = syncedRevision
            unsyncedSinceMs = 0L
            lastTransportState = "PLAYING"
            earlyStopRetries = 0
            castAtMs = SystemClock.elapsedRealtime()
            castDurationMs = currentTrack()?.durationMs ?: 0L
            AppState.hint.value = null
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            // 音箱自己会续播，本地计时器/闹钟全部撤掉（否则会和音箱抢着切歌）
            cancelAutoAdvance()
            LogBus.i("已把 ${queue.size} 首灌进音箱队列（${if (needRebuild) "重建" else "复用"}），从第 ${safeIndex + 1} 首开始")
            true
        } catch (t: Throwable) {
            LogBus.w("原生队列异常：${t.javaClass.simpleName} ${t.message}，退回逐首推送")
            false
        } finally {
            AppState.busy.value = false
            updateNotification()
        }
    }

    /**
     * 分批把曲目塞进音箱队列。
     *
     * 实测（Play:1 / S2 86.10）：一次 `AddMultipleURIsToQueue` 最多 16 条，
     * 18 条就返回 402/500 —— 之前的 24 条一批必然失败，于是每次都退回单曲推送，
     * 机身的"下一首"也就永远无效。现在默认每批 12 条留余量。
     */
    private suspend fun enqueueAll(
        speaker: SonosDevice,
        entries: List<Pair<String, String>>
    ): Boolean {
        var index = 0
        while (index < entries.size) {
            val end = minOf(index + QUEUE_CHUNK_SIZE, entries.size)
            // 关键：每批要放在"已入队数量 + 1"的位置。
            // 1.0.4 把这里写死成 1，导致第二批开始都挤到队首，整个队列顺序被倒过来，
            // 于是"点 A 播 B"（1.0.5 修）。
            if (!enqueueChunk(speaker, entries.subList(index, end), index + 1)) return false
            index = end
        }
        return true
    }

    /** 一批失败就对半拆开重试，直到单条；单条仍失败才算这条路走不通 */
    private suspend fun enqueueChunk(
        speaker: SonosDevice,
        entries: List<Pair<String, String>>,
        firstTrackNumber: Int
    ): Boolean {
        if (entries.isEmpty()) return true
        val result = soap.addMultipleUrisToQueue(speaker, entries, firstTrackNumber)
        if (result is SoapResult.Success) return true

        if (entries.size == 1) {
            LogBus.w("单条入队失败：${describeSoapFailure(result)}")
            return false
        }
        LogBus.w("一次入队 ${entries.size} 首失败（${describeSoapFailure(result)}），对半拆分重试")
        val mid = entries.size / 2
        return enqueueChunk(speaker, entries.subList(0, mid), firstTrackNumber) &&
            enqueueChunk(speaker, entries.subList(mid, entries.size), firstTrackNumber + mid)
    }

    /** App 的播放模式 → 音箱的 PlayMode */
    private fun nativePlayMode(): String = when {
        // 随机开关优先：随机时绝不能让残留的 REPEAT_ONE 生效（会单曲无限循环）
        AppState.shuffle.value -> "REPEAT_ALL"
        AppState.repeatMode.value == RepeatMode.ONE -> "REPEAT_ONE"
        // 清单顺序由 App 独占（orderFor 已经打乱过），音箱只按顺序放。
        else -> "REPEAT_ALL"
    }

    private fun describeSoapFailure(result: SoapResult): String = when (result) {
        is SoapResult.Fault -> "UPnP ${result.errorCode ?: "-"} ${result.description}"
        is SoapResult.TransportError -> result.message
        is SoapResult.Success -> "OK"
    }

    /** 清单没建立时用曲库兜底，避免"上一首/下一首"点了没反应 */    private fun ensureQueueReady() {
        if (AppState.queue.value.isNotEmpty()) return
        val library = MusicLibrary.tracks.value
        if (library.isEmpty()) return
        val current = MediaRegistry.current.value?.let { item ->
            library.firstOrNull { it.uri == item.uri }
        } ?: library.first()
        AppState.startQueue(library, current)
        LogBus.d("播放清单缺失，已按曲库重建（${library.size} 首）")
    }

    private fun startTrack(track: com.sonoscast.local.core.Track) {
        val targets = AppState.selectedDevices()
        if (targets.isEmpty()) {
            AppState.hint.value = Hint(Str.NO_DEVICE)
            return
        }
        AppState.setQueueTrack(track)
        MediaRegistry.registerTrack(track)
        val index = AppState.queueIndex.value
        scope.launch {
            // 优先走"音箱原生队列"：机身按键、随机/循环、自动续播都交给音箱
            if (castNativeQueue(targets, index)) return@launch
            // 走不通（S1 / 清单只有一首 / 接口报错）→ 退回逐首推 URL
            nativeQueueActive = false
            performCast(MediaRegistry.current.value ?: return@launch, targets)
        }
    }

    /** 随机开关：只改顺序，循环按钮随之亮/灭（互斥由 AppState 保证） */
    private fun toggleShuffle() {
        AppState.setShuffle(!AppState.shuffle.value)
        LogBus.i("播放模式 → ${playModeLabel()}")
        updateNotification()
        recastIfQueueActive("随机开关")
    }

    /** 循环在「列表循环 ↔ 单曲循环」之间切换，并顺带退出随机 */
    private fun cycleRepeat() {
        AppState.setRepeatMode(AppState.repeatMode.value.next())
        LogBus.i("播放模式 → ${playModeLabel()}")
        updateNotification()
        applyPlayModeOnly("循环切换")
    }

    /**
     * 随机/循环变化会重排 `AppState.queue`，而音箱里的队列还是旧的顺序 ——
     * 不重建的话，`<Track>` 读到第 N 首、App 却按新顺序解释成另一首，
     * 表现为"界面显示 A、实际在放 B"。所以这里当场重推一次。
     */
    /** 现在是"应该用队列模式"吗？只看清单本身，不看历史失败留下的标志 */
    private fun shouldUseQueue(): Boolean = AppState.queue.value.size >= 2

    /** 队列签名：整条顺序 + 随机开关。变了就得重建音箱队列 */
    private fun queueSignature(queue: List<Track>): String =
        AppState.shuffle.value.toString() + "|" + queue.joinToString(",") { it.id.toString() }

    /**
     * 「下一首播放」之后把这次插入同步写进音箱队列。
     *
     * 1.1 之前只改 App 的清单，音箱队列没变：送别放完后音箱按自己的顺序走下一首，
     * 而 App 按自己的清单认为下一首是刚插进来的那首 —— UI 和声音从此永久错开一位。
     * 这里用 Sonos 的 `EnqueueAsNext=1` 原生插到"当前曲之后"，两边顺序就一致了。
     */
    private fun syncSpeakerQueue(insertedTrackId: Long, rebuild: Boolean = false) {
        if (!shouldUseQueue()) {
            LogBus.d("清单已改：清单不足 2 首，保持单曲推送")
            return
        }
        if (AppState.busy.value) {
            LogBus.d("清单已改：上一次推送还没结束，签名校验会兜底重建")
            return
        }
        // 移动已有曲目：音箱里那份旧副本没法原地删，只能整体重建
        if (rebuild) {
            LogBus.d("下一首播放（移动已有曲目）→ 重建队列以对齐")
            recastIfQueueActive("下一首播放")
            return
        }
        val queue = AppState.queue.value
        if (queue.isEmpty()) return
        val speakers = AppState.selectedDevices()
            .filterNot { it.isSatellite }
            .filterNot { it.isLegacyS1 }
        if (speakers.isEmpty()) return
        val ip = AppState.localIp.value ?: return
        val port = AppState.serverPort.value

        // 音箱当前若不在队列模式（上一次推送失败退回过），原生插队无处可插，
        // 直接整队重建一次更稳。
        if (!nativeQueueActive) {
            LogBus.d("清单已改：音箱尚不在队列模式 → 重建队列")
            recastIfQueueActive("下一首播放")
            return
        }

        val inserted = queue.firstOrNull { it.id == insertedTrackId }
            ?: queue.getOrNull((AppState.queueIndex.value + 1).coerceIn(0, queue.lastIndex))
            ?: return
        // 1 起的"当前曲之后"位置：当前是 N（1 起），插入后应排第 N+1
        val insertAt = (AppState.queueIndex.value + 2).coerceAtLeast(1)

        scope.launch {
            val item = MediaRegistry.registerTrackSilently(inserted)
            val url = LocalMediaServer.buildStreamUrl(ip, port, item)
            val didl = SoapXml.didlLite(inserted.title, url, item.mimeType, inserted.id.toString())
            var ok = true
            for (speaker in speakers) {
                val result = soap.addMultipleUrisToQueue(
                    speaker,
                    listOf(url to didl),
                    firstTrackNumber = insertAt,
                    enqueueAsNext = true
                )
                if (result !is SoapResult.Success) {
                    ok = false
                    LogBus.w("原生插队失败（${describeSoapFailure(result)}）")
                }
            }
            if (ok) {
                sonosQueueSignature = queueSignature(queue)
                syncedRevision = AppState.queueRevision
                lastSeenRevision = syncedRevision
                unsyncedSinceMs = 0L
                LogBus.i("已把「${inserted.title}」插进音箱队列第 $insertAt 位（原生插队）")
            } else {
                LogBus.w("原生插队不成功，改为重建整个队列")
                recastIfQueueActive("下一首播放")
            }
        }
    }

    /**
     * 清单版本对账（结构性保险）。
     *
     * 任何入口改了 `AppState.queue` 都会让 `queueRevision` 递增；正常路径（点歌 / 原生插队 /
     * 切随机）会自己把音箱同步好并回写 `syncedRevision`。这里负责抓"漏掉的入口"：
     * 版本号一直不相等超过一个宽限期，就整队重建，绝不让 UI 与声音长期错位。
     */
    private fun reconcileQueueIfNeeded(device: SonosDevice) {
        if (!shouldUseQueue()) return
        if (AppState.externalSource.value) return   // 音箱在放别人的内容，别抢
        if (AppState.busy.value) return
        val revision = AppState.queueRevision
        if (revision == syncedRevision) {
            lastSeenRevision = revision
            unsyncedSinceMs = 0L
            return
        }
        if (revision != lastSeenRevision) {
            lastSeenRevision = revision
            unsyncedSinceMs = SystemClock.elapsedRealtime()
            LogBus.d("清单版本变化 rev=$revision（音箱仍在 rev=$syncedRevision），等待主动同步")
            return
        }
        if (SystemClock.elapsedRealtime() - unsyncedSinceMs < RECONCILE_GRACE_MS) return

        LogBus.w("清单已变但音箱没跟上（rev=$revision / 已同步 $syncedRevision）→ 兜底重建")
        lastSeenRevision = revision
        unsyncedSinceMs = 0L
        recastIfQueueActive("清单对账兜底")
    }

    /**
     * 主动自检：音箱队列的曲目数必须等于 App 清单长度。
     *
     * 长度不一致是"两边顺序错开"的最早可观测信号（1.1.1 的 off-by-one 就属此类），
     * 与其等用户发现"界面和声音不一致"，不如这里直接重建并报警。
     */
    private suspend fun auditQueue(device: SonosDevice) {
        if (AppState.externalSource.value) return   // 同上
        if (!shouldUseQueue()) return
        if (AppState.busy.value) return
        val expected = AppState.queue.value.size
        if (expected < 2) return
        val actual = runCatching { soap.getQueueSize(device) }.getOrNull() ?: return
        if (actual == expected) return
        // 限流：同一次不一致不要在每轮自检里反复重建（避免把日志刷爆、把歌打断）
        val now = SystemClock.elapsedRealtime()
        if (now - lastAuditRebuildAtMs < AUDIT_REBUILD_COOLDOWN_MS) return
        lastAuditRebuildAtMs = now
        LogBus.w("队列自检失败：音箱 $actual 首 / 清单 $expected 首 → 重建队列")
        recastIfQueueActive("队列自检")
    }


    /**
     * 只切播放模式，不动队列。
     *
     * 循环（列表/单曲）的切换只影响 Sonos 的播放模式，重建队列纯属浪费：
     * RemoveAllTracksFromQueue + 重新入队会让声音中断 0.5-2 秒（用户实测"顿一下"）。
     */
    private fun applyPlayModeOnly(reason: String) {
        if (!shouldUseQueue()) {
            LogBus.d(reason + "：不是队列模式，只刷新界面状态")
            syncPlaybackStateToDevices()
            return
        }
        val speaker = AppState.coordinators().firstOrNull()
        if (speaker == null) {
            LogBus.w(reason + "：没有可用主箱")
            return
        }
        scope.launch {
            val mode = nativePlayMode()
            val r = runCatching { soap.setPlayMode(speaker, mode) }.getOrNull()
            if (r is SoapResult.Success) {
                LogBus.i(reason + " → 只切播放模式：" + mode + "（不重建队列）")
            } else {
                LogBus.w(reason + "：SetPlayMode 失败，退回重建队列")
                recastIfQueueActive(reason)
            }
            syncPlaybackStateToDevices()
        }
    }

    private fun recastIfQueueActive(reason: String) {
        // 唯一重建入口：先确认音箱上放的还是我们的 /stream/。
        // 否则"接管瞬间的那一次对账"仍会把 Sonos App 的播放顶掉（1.1.6 实测残留）。
        if (AppState.externalSource.value) {
            LogBus.d("recast（$reason）：音箱在放其他来源，跳过")
            return
        }
        // 注意：这里**不能**用 nativeQueueActive 当门禁。
        // 上一次推送失败会把该标志置 false，于是"切回列表循环"时永远不重建，
        // 音箱就一直停在 1 首的单曲队列里（1.1.2 回归脚本第 6 步抓到的 bug）。
        // 判断依据改成"清单本身够不够长"：够长就该是队列模式，失败就再试。
        if (!shouldUseQueue()) {
            LogBus.d("$reason：清单不足 2 首，保持单曲推送")
            return
        }
        if (AppState.busy.value) {
            LogBus.d("$reason：上一次推送还没结束，跳过重建")
            return
        }
        val item = MediaRegistry.current.value
        if (item == null) {
            LogBus.d("$reason：没有当前曲目，跳过重建")
            return
        }
        // 传输出命令只发主箱：从箱回 1023，且会让状态闪"失败"
        val targets = AppState.selectedDevices().filterNot { it.isSatellite }
        if (targets.isEmpty()) {
            LogBus.d("$reason：没有选中音箱，跳过重建")
            return
        }
        val index = AppState.queueIndex.value.coerceAtLeast(0)
        // 记下当前进度与播放状态：重建后要回到同一位置，暂停状态也要保持
        val atSec = AppState.positionSec.value.toInt().coerceAtLeast(0)
        val wasPlaying = targets.any {
            AppState.deviceStatus.value[it.key] == DeviceStatus.PLAYING
        }
        scope.launch {
            LogBus.d("$reason → 按新顺序重建队列（第 ${index + 1} 首，${atSec}s）")
            if (!castNativeQueue(targets, index, resumeAtSec = atSec, autoPlay = wasPlaying)) {
                nativeQueueActive = false
                LogBus.w("$reason：重建队列失败，退回单曲推送（并恢复到 ${atSec}s）")
                runCatching { performCast(item, targets) }
                // 单曲推送会把位置归零，这里补回去 —— 否则用户听到的就是"从头开始"
                if (atSec > 0) {
                    targets.forEach { runCatching { soap.seek(it, atSec.toLong()) } }
                    AppState.positionSec.value = atSec.toLong()
                }
                if (!wasPlaying) {
                    targets.forEach { runCatching { soap.pause(it) } }
                    targets.forEach { AppState.setStatus(it.key, DeviceStatus.PAUSED) }
                }
                return@launch
            }
            if (!wasPlaying) {
                // 原本是暂停的，重建后不要自己放起来
                targets.forEach { runCatching { soap.pause(it) } }
                targets.forEach { AppState.setStatus(it.key, DeviceStatus.PAUSED) }
            }
            // 重建会改写状态：最后统一按真实状态回写会话/通知，
            // 否则锁屏会停在"正在播放"（1.1.9 实测）
            syncPlaybackStateToDevices()
            updateNotification()
        }
    }

    /** 日志/通知里的一句话描述：随机优先显示，其次循环 */
    private fun playModeLabel(): String = when {
        AppState.shuffle.value -> "随机播放"
        AppState.repeatMode.value == RepeatMode.ONE -> "单曲循环"
        else -> "列表循环"
    }

    // ------------------------------------------------------------ 媒体会话


    /**
     * MIUI 的锁屏媒体卡片会**缓存自定义动作的图标**，同一个 action id 即使换了图标也不重绘。
     * 让 id 随状态变化（shuffle_on / shuffle_off、repeat_off/list/one），
     * 系统就会当成"新按钮"重新取图标 —— 这是实测唯一不重启会话也能刷新的办法。
     */
    private fun shuffleActionId(): String =
        if (AppState.shuffle.value) ACTION_TOGGLE_SHUFFLE + "_ON" else ACTION_TOGGLE_SHUFFLE + "_OFF"

    private fun repeatActionId(): String = when {
        AppState.shuffle.value -> ACTION_TOGGLE_REPEAT + "_OFF"
        AppState.repeatMode.value == RepeatMode.ONE -> ACTION_TOGGLE_REPEAT + "_ONE"
        else -> ACTION_TOGGLE_REPEAT + "_LIST"
    }

    private fun initMediaSession() {
        if (mediaSession != null) return
        mediaSession = MediaSessionCompat(this, "ZonozLocalStream").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumeAll()
                override fun onPause() = pauseAll()
                override fun onStop() = stopAll()
                override fun onSkipToNext() = playRelative(1)
                override fun onSkipToPrevious() = playRelative(-1)

                override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
                    // 动作 id 带状态后缀（见 shuffleActionId/repeatActionId），这里按前缀分发
                    if (action != null && action.startsWith(ACTION_TOGGLE_SHUFFLE)) {
                        val on = !AppState.shuffle.value
                        LogBus.i("媒体控件切换随机：${if (on) "开" else "关"}")
                        AppState.setShuffle(on)
                        recastIfQueueActive("媒体控件随机")
                        syncPlaybackStateToDevices()
                        updateNotification()
                        return
                    }
                    if (action != null && action.startsWith(ACTION_TOGGLE_REPEAT)) {
                        val one = AppState.repeatMode.value != RepeatMode.ONE
                        LogBus.i("媒体控件切换循环：${if (one) "单曲" else "列表"}")
                        AppState.setRepeatMode(if (one) RepeatMode.ONE else RepeatMode.ALL)
                        applyPlayModeOnly("媒体控件循环")
                        syncPlaybackStateToDevices()
                        updateNotification()
                        return
                    }
                    when (action) {
                        ACTION_TOGGLE_SHUFFLE -> {
                            val on = !AppState.shuffle.value
                            LogBus.i("媒体控件切换随机：${if (on) "开" else "关"}")
                            AppState.setShuffle(on)
                            recastIfQueueActive("媒体控件随机")
                            syncPlaybackStateToDevices()
                            updateNotification()
                        }
                        ACTION_TOGGLE_REPEAT -> {
                            val one = AppState.repeatMode.value != RepeatMode.ONE
                            LogBus.i("媒体控件切换循环：${if (one) "单曲" else "列表"}")
                            AppState.setRepeatMode(if (one) RepeatMode.ONE else RepeatMode.ALL)
                            applyPlayModeOnly("媒体控件循环")
                            syncPlaybackStateToDevices()
                            updateNotification()
                        }
                    }
                }

                // 通知栏 / 锁屏 / 耳机按键上的随机与循环（Android 13+ 会渲染这两个按钮）
                override fun onSetShuffleMode(shuffleMode: Int) {
                    val on = shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE
                    if (on != AppState.shuffle.value) {
                        LogBus.i("通知栏切换随机：${if (on) "开" else "关"}")
                        AppState.setShuffle(on)
                        recastIfQueueActive("通知栏随机")
                        syncPlaybackStateToDevices()
                        updateNotification()
                    }
                }

                override fun onSetRepeatMode(repeatMode: Int) {
                    val one = repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE
                    val next = if (one) RepeatMode.ONE else RepeatMode.ALL
                    if (next != AppState.repeatMode.value) {
                        LogBus.i("通知栏切换循环：${if (one) "单曲" else "列表"}")
                        AppState.setRepeatMode(next)
                        applyPlayModeOnly("通知栏循环")
                        syncPlaybackStateToDevices()
                        updateNotification()
                    }
                }
            })
            isActive = true
        }
        updatePlaybackState(PlaybackStateCompat.STATE_NONE)
    }

    /** 媒体控件的状态跟随音箱真实播放状态，否则按钮会显示成"暂停"、进度条停走 */
    private fun syncPlaybackStateToDevices() {
        val playing = AppState.deviceStatus.value.values.any { it == DeviceStatus.PLAYING }
        updatePlaybackState(
            if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        )
    }

    private fun updatePlaybackState(state: Int) {
        val session = mediaSession ?: return
        lastSessionState = state
        runCatching {
            val track = currentTrack()
            // 锁屏 / 通知媒体卡片显示的歌名、艺术家、时长都来自 MediaMetadata
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track?.title ?: "ZONOZ local stream")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track?.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track?.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track?.durationMs ?: 0L)
                    .build()
            )
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_STOP or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    // 通知栏/锁屏的两个按钮用「实心 = 开、空心 = 关」区分，
                    // 且遵循 App 内的互斥逻辑：随机开时循环必须是"关"的样子。
                    .addCustomAction(
                        PlaybackStateCompat.CustomAction.Builder(
                            shuffleActionId(),
                            I18n.t(Str.MODE_SHUFFLE),
                            if (AppState.shuffle.value) R.drawable.ic_shuffle
                            else R.drawable.ic_shuffle_dim
                        ).build()
                    )
                    .addCustomAction(
                        PlaybackStateCompat.CustomAction.Builder(
                            repeatActionId(),
                            I18n.t(
                                when {
                                    AppState.shuffle.value -> Str.MODE_REPEAT_ALL
                                    AppState.repeatMode.value == RepeatMode.ONE -> Str.MODE_REPEAT_ONE
                                    else -> Str.MODE_REPEAT_ALL
                                }
                            ),
                            when {
                                AppState.shuffle.value -> R.drawable.ic_repeat_dim
                                AppState.repeatMode.value == RepeatMode.ONE -> R.drawable.ic_repeat_one
                                else -> R.drawable.ic_repeat
                            }
                        ).build()
                    )
                    // 暂停/停止时速度必须为 0，否则系统会一直外推进度条
                    .setState(
                        state,
                        AppState.positionSec.value * 1000L,
                        if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f
                    )
                    .build()
            )
            // 系统 shuffle/repeat 模式不再回写：MIUI 会据此画自己的按钮且不跟随状态，
            // 统一由上面的自定义动作表达（实心=开、空心=关）。
            LogBus.d(
                "会话模式：shuffle=" + (if (AppState.shuffle.value) "ALL" else "NONE") +
                    " repeat=" + (when {
                        AppState.shuffle.value -> "NONE"
                        AppState.repeatMode.value == RepeatMode.ONE -> "ONE"
                        else -> "ALL"
                    })
            )
        }
    }

    private fun pauseAll() {
        scope.launch {
            // 断连恢复后旧 socket 可能已死：先确保媒体服务可用，再让音箱播
            if (!ensureServer()) {
                LogBus.w("媒体服务不可用，先不发送播放指令")
                AppState.hint.value = Hint(Str.CONNECTION_LOST)
                return@launch
            }
            // 从箱不接收传输命令（发过去只会回 1023），只对主箱发
            val targets = AppState.controlTargets().filterNot { it.isSatellite }
            if (targets.isEmpty()) return@launch
            targets.forEach { device ->
                when (val result = soap.pause(device)) {
                    is SoapResult.Success -> {
                        AppState.setStatus(device.key, DeviceStatus.PAUSED)
                        if (AppState.deviceStatus.value[device.key] == DeviceStatus.PAUSED) {
                            mirrorSatelliteStatus(DeviceStatus.PAUSED)
                        }
                        LogBus.i("${device.displayName} 已暂停")
                    }

                    is SoapResult.Fault -> {
                        AppState.setStatus(device.key, DeviceStatus.ERROR, UpnpErrors.describe(result.errorCode))
                        LogBus.e("${device.displayName} 暂停失败：${result.errorCode}")
                    }

                    is SoapResult.TransportError -> {
                        AppState.setStatus(device.key, DeviceStatus.ERROR, result.message)
                    }
                }
            }
            pausedAtSec = AppState.positionSec.value.toInt()
            syncPlaybackStateToDevices()
            updateNotification()
            cancelAutoAdvance()
        }
    }

    private fun resumeAll() {
        scope.launch {
            if (AppState.externalSource.value) {
                LogBus.i("音箱在放其他来源，点播放 → 交还给 ZONOZ（从暂停处继续）")
                AppState.externalSource.value = false
                // 收到哪儿就从哪儿接着放：位置是接管那一刻冻结的 AppState.positionSec
                castCurrent(resumeAtSec = if (pausedAtSec > 0) pausedAtSec else AppState.positionSec.value.toInt())
                return@launch
            }
            // 从箱不接收传输命令（发过去只会回 1023），只对主箱发
            val targets = AppState.controlTargets().filterNot { it.isSatellite }
            if (targets.isEmpty()) return@launch
            targets.forEach { device ->
                when (val result = soap.play(device)) {
                    is SoapResult.Success -> {
                        AppState.setStatus(device.key, DeviceStatus.PLAYING)
                        mirrorSatelliteStatus(DeviceStatus.PLAYING)
                        LogBus.i("${device.displayName} 已继续")
                    }

                    is SoapResult.Fault -> {
                        AppState.setStatus(device.key, DeviceStatus.ERROR, UpnpErrors.describe(result.errorCode))
                    }

                    is SoapResult.TransportError -> {
                        AppState.setStatus(device.key, DeviceStatus.ERROR, result.message)
                    }
                }
            }
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            updateNotification()
            scheduleAutoAdvance()
        }
    }

    private fun stopAll() {
        cancelAutoAdvance()
        scope.launch {
            // 从箱不接收传输命令（发过去只会回 1023），只对主箱发
            val targets = AppState.controlTargets().filterNot { it.isSatellite }
            targets.forEach { device ->
                runCatching { soap.stop(device) }
                AppState.setStatus(device.key, DeviceStatus.IDLE)
            }
            AppState.nowPlayingTitle.value = null
            AppState.positionSec.value = 0L
            AppState.durationSec.value = 0L
            lastTransportState = null
            LogBus.i("已向 ${targets.size} 台音箱发送 Stop")
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
            updateNotification()
        }
    }

    private fun setVolume(volume: Int) {
        val value = volume.coerceIn(0, 100)
        AppState.volume.value = value
        lastVolumeSetAt = SystemClock.elapsedRealtime()
        scope.launch {
            // 从箱不接收传输命令（发过去只会回 1023），只对主箱发
            val targets = AppState.controlTargets().filterNot { it.isSatellite }
            val results = targets.map { device -> device to soap.setVolume(device, value) }
            val failed = results.count { it.second !is SoapResult.Success }
            if (failed == 0) {
                LogBus.i("音量 → $value（${targets.size} 台）")
            } else {
                LogBus.e("音量设置失败 $failed/${targets.size} 台，目标值 $value")
            }
        }
    }

    /** 拖动进度条后跳转。先本地更新进度，避免松手瞬间视觉回跳。 */
    private fun seekTo(seconds: Int) {
        val target = seconds.coerceAtLeast(0)
        AppState.positionSec.value = target.toLong()
        scope.launch {
            // 从箱不接收传输命令（发过去只会回 1023），只对主箱发
            val targets = AppState.controlTargets().filterNot { it.isSatellite }
            if (targets.isEmpty()) {
                LogBus.w("没有正在播放的设备，Seek 无效")
                return@launch
            }
            targets.forEach { device ->
                when (val result = soap.seek(device, target.toLong())) {
                    is SoapResult.Success -> Unit
                    is SoapResult.Fault -> LogBus.e(
                        "Seek 失败 ${device.displayName}：${result.errorCode} ${result.description}"
                    )

                    is SoapResult.TransportError -> LogBus.e(
                        "Seek 网络异常 ${device.displayName}：${result.message}"
                    )
                }
            }
            LogBus.i("跳转到 ${Track.formatDuration(target * 1000L)}")
            // 拖完进度条要按新位置重排续播
            scheduleAutoAdvance()
        }
    }

    private fun shutdownGracefully() {
        cancelAutoAdvance()
        scope.launch {
            AppState.controlTargets().forEach { device ->
                runCatching { soap.stop(device) }
            }
            LogBus.i("用户请求停止服务")
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ------------------------------------------------------------ 前台 / 通知

    private fun ensureForeground() {
        if (foregroundStarted) return

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 只用 mediaPlayback：dataSync 在 Android 14+ 有 6 小时上限，
            // 超时抛 ForegroundServiceDidNotStopInTimeException 把进程杀掉
            // （10-31 12:48 的崩溃日志就是它）。
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }

        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            foregroundStarted = true
            renewLocks()
            startProgressPolling()
            startLockRenewal()
            LogBus.d("已进入前台服务模式（type=$type）")
        }.onFailure {
            LogBus.e("进入前台服务失败：${it.javaClass.simpleName} ${it.message}")
        }
    }

    /**
     * 播放进度轮询。
     *
     * 显示节奏是 **1 秒一次**，但真正向音箱发 SOAP 的节奏仍是 **2 秒一次**：
     * 中间那一拍用本地插值把秒数 +1。这样进度条读秒是常见的 1Hz，
     * 又不会把发往音箱的请求量翻倍（GetPositionInfo + GetTransportInfo 每条 2s 一轮）。
     * 每次真实采样都会覆盖插值结果，所以不会累积漂移。
     */
    private fun startProgressPolling() {
        ensureNetworkCallback()
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            var tick = 0
            while (isActive) {
                // 正在播或**被机身按键暂停**的设备都算活跃：暂停后也要继续读状态，
                // 否则机身再按一次播放，App 这边永远发现不了。
                val coordinators = AppState.coordinators()
                // 先找"有播放状态"的主箱；找不到就**兜底盯第一个主箱**。
                // 只按状态找的写法有个致命漏洞：设备重新发现后状态表可能是空的，
                // active 变 null → 轮询直接停 → 音箱还在放，锁屏却永远停在旧进度
                // （用户实测：App 0:50、锁屏 1:40）。
                val active = coordinators.firstOrNull { device ->
                    val status = AppState.deviceStatus.value[device.key]
                    status == DeviceStatus.PLAYING || status == DeviceStatus.PAUSED
                } ?: coordinators.firstOrNull()
                if (active != null) {
                    val sampled = tick % PROGRESS_SAMPLE_EVERY_TICKS == 0
                    if (sampled) {
                        // 0) 归属必须第一个判：音箱可能已经被 Sonos App 抢走。
                        //    抢走之后**不能**再读别人的进度，否则界面进度条会跟着别人的歌走。
                        refreshOwnership(active)
                        if (!AppState.externalSource.value) {
                            runCatching { soap.getPositionInfo(active) }.getOrNull()?.let { (position, duration) ->
                                AppState.positionSec.value = position
                                if (duration > 0L) AppState.durationSec.value = duration
                            }
                        }
                        // 机身按键切歌 / 暂停 / 继续，都只能靠轮询发现
                        syncQueueTrack(active)
                        syncTransportState(active)
                        // 从箱状态跟随主箱（它没有独立传输，不镜像就会停在旧的 PAUSED）
                        AppState.deviceStatus.value[active.key]?.let { mirrorSatelliteStatus(it) }
                        // 正在播时每拍刷新一次会话进度。
                        // 会话的 position 只在 setPlaybackState 时写入，系统随后按 speed 外推；
                        // 不刷新的话，App 里 0:50、锁屏却按一小时前的基准外推到 1:40（用户实测）。
                        // 官方文档也要求播放中至少每几秒更新一次。
                        if (!AppState.externalSource.value &&
                            AppState.deviceStatus.value[active.key] == DeviceStatus.PLAYING
                        ) {
                            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                        }
                        // ① 对账：清单版本变了但没人通知音箱 → 这里兜底重建
                        reconcileQueueIfNeeded(active)
                        // ② 主动自检：音箱队列长度与 App 清单不一致 → 立刻重建并报警
                        if (tick % (PROGRESS_SAMPLE_EVERY_TICKS * QUEUE_AUDIT_EVERY_SAMPLES) == 0) {
                            auditQueue(active)
                        }
                        if (tick % (PROGRESS_SAMPLE_EVERY_TICKS * VOLUME_POLL_EVERY_SAMPLES) == 0) {
                            syncVolume(active)
                        }
                    } else if (!AppState.externalSource.value &&
                        !AppState.connectionLost.value &&
                        AppState.deviceStatus.value[active.key] == DeviceStatus.PLAYING
                    ) {
                        // 两次采样之间的本地推进：**只在真的在播时**做。
                        // 暂停时继续插值就会变成 1:27↔1:28 反复横跳（1.0.1 的现象）。
                        val next = AppState.positionSec.value + 1
                        val total = AppState.durationSec.value
                        if (total <= 0L || next < total) {
                            AppState.positionSec.value = next
                        }
                    }
                }
                // ── 自适应节拍 ─────────────────────────────────────────────
                // 旧版固定 1 秒一拍、永不停：用户离开家 40 分钟后，服务仍在每 2 秒
                // 发 4 个 SOAP（全部超时），CPU 累计 1 小时 13 分、耗电 70%+。
                val playingNow = AppState.deviceStatus.value.values.any { it == DeviceStatus.PLAYING }
                val interval = when {
                    AppState.connectionLost.value -> DISCONNECTED_POLL_MS   // 断连：30 秒探一次
                    active == null -> IDLE_POLL_MS                          // 没有活跃设备：15 秒
                    playingNow -> PROGRESS_TICK_MS                          // 正在播：1 秒（进度要顺滑）
                    else -> PAUSED_POLL_MS                                  // 暂停等机身按键：5 秒
                }
                if (interval != lastPollInterval) {
                    lastPollInterval = interval
                    LogBus.d(
                        "轮询节拍 → ${interval}ms（lost=${AppState.connectionLost.value}" +
                            " active=${active != null} playing=$playingNow)"
                    )
                }
                // ── 会话看门狗 ───────────────────────────────────────────
                // 锁屏/通知的按钮与进度条只认 MediaSession。任何一条分支漏了
                // updatePlaybackState，锁屏就停在"正在播放、进度一路走到结尾"
                // （用户反复遇到的 bug）。这里每拍按真实状态强刷，最多一拍自愈。
                val wantSessionState =
                    if (playingNow && !AppState.connectionLost.value) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED
                if (wantSessionState != lastSessionState) {
                    lastSessionState = wantSessionState
                    updatePlaybackState(wantSessionState)
                }
                // 锁只在"真的在播"时持有；暂停 / 断连立即释放，避免整夜挂锁
                if (playingNow && !AppState.connectionLost.value) {
                    acquireWakeLock()
                    acquireWifiLock()
                } else {
                    releaseLocks()
                }
                tick++
                delay(interval)
            }
        }
    }

    // ------------------------------------------------------ 外部控制的同步

    /**
     * 把音箱的真实状态同步回 App。
     *
     * 机身按键（播放/暂停）和 Sonos 官方 App 都不会通知我们，只能靠轮询发现。
     * 早期版本只更新了 MediaSession、没更新 `AppState.deviceStatus`，
     * 于是"音箱暂停了，手机上的按钮还是播放态、进度条还在 1:27↔1:28 插值"。
     */
    /**
     * 断连：连续多次拿不到音箱状态（Wi-Fi 断开 / 换了网络 / 音箱离线）。
     *
     * 必须把界面与会话都降级成暂停，否则会出现"手机已经在蜂窝网络、
     * 界面还显示正在播放、进度慢慢爬"的假象（用户实测）。
     */
    private fun markDisconnected() {
        if (AppState.connectionLost.value) return
        LogBus.w("与音箱失去联系（Wi-Fi 断开或音箱离线），界面转为暂停")
        AppState.connectionLost.value = true
        cancelAutoAdvance()
        releaseLocks()
        // 监听 socket 绑在 wlan 地址上：Wi-Fi 一消失，NanoHTTPD 的 accept 就会持续失败
        // 并在循环里空转 —— 实测断网稳态 CPU 78 秒/分钟（多核跑满）、整机发烫。
        // 这里直接停掉服务端，等 Wi-Fi 回来由 ensureServer(force = true) 重建。
        runCatching { server?.stop() }
        server = null
        LogBus.d("已停止媒体服务器（等网络恢复后重建）")
        AppState.deviceStatus.value.keys.forEach { key ->
            AppState.setStatus(key, DeviceStatus.PAUSED)
        }
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        updateNotification()
    }

    private fun markReconnected() {
        if (!AppState.connectionLost.value) return
        LogBus.i("与音箱恢复联系")
        AppState.connectionLost.value = false
        AppState.hint.value = null
    }

    /** 注册默认网络回调：Wi-Fi 恢复时清断连态，并把媒体服务器标记为需重建 */
    private fun ensureNetworkCallback() {
        if (netCallback != null) return
        runCatching {
            val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) {
                        scope.launch {
                            LogBus.i("Wi-Fi 已恢复，重建媒体服务器")
                            ensureServer(force = true)
                            soapFailStreak = 0
                            markReconnected()
                        }
                    }
                }

                override fun onLost(network: android.net.Network) {
                    LogBus.w("默认网络断开")
                    scope.launch { markDisconnected() }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        }.onFailure { LogBus.w("网络回调注册失败：" + it.message) }
    }

    /**
     * 归属检查：音箱当前放的还是不是我们的 /stream/。
     *
     * Sonos App 抢走音箱后，TrackURI 会变成它自己的内容。这时必须**主动交还控制权**，
     * 否则下一轮对账 / 自检 / 续播会把音箱抢回来——用户看到的就是
     * "Sonos App 播一会儿，音乐突然又跳回 ZONOZ"（1.1.6 实测反馈）。
     */
    private suspend fun refreshOwnership(device: SonosDevice) {
        val uri = runCatching { soap.getPlaybackTrackUri(device) }.getOrNull()
        if (uri.isNullOrBlank()) return          // 切流中读不到，下一轮再说
        val ours = uri.contains("/stream/")
        if (!ours && !AppState.externalSource.value) {
            LogBus.i("音箱已被其他来源占用（Sonos App 等），ZONOZ 交还控制权")
            AppState.externalSource.value = true
            nativeQueueActive = false
            lastQueueTrack = 0
            cancelAutoAdvance()
            syncPlaybackStateToDevices()
            updateNotification()
        } else if (ours && AppState.externalSource.value) {
            LogBus.i("音箱已回到 ZONOZ 的串流")
            AppState.externalSource.value = false
        }
        if (ours) healStaleStreamHost(uri)
    }

    /**
     * 串流地址过期自救。
     *
     * 手机换了 IP（VPN 开关、Wi-Fi 重连、DHCP 续租都可能触发）之后，音箱队列里存的
     * 还是 `http://<旧IP>:8080/stream/...`，它取流会失败 —— 表现是"能选设备、点歌没声音、
     * 提示检查网络"，而 App 自己完全不知道。
     *
     * 这里每 2 秒采样时比对一次主机名，发现不是本机当前 IP 就按新地址重推整条队列。
     * 15 秒限流，避免重推风暴。
     */
    private fun healStaleStreamHost(uri: String) {
        val ip = AppState.localIp.value ?: return
        val host = uri.substringAfter("://", "").substringBefore("/").substringBefore(":")
        if (host.isEmpty() || host == ip) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastUrlHealAt < 15_000) return
        lastUrlHealAt = now
        LogBus.w("串流地址已过期：音箱里是 $host，本机现在是 $ip → 按新地址重推")
        castCurrent(resumeAtSec = AppState.positionSec.value.toInt())
    }


    /**
     * 立体声配对：从箱（isSatellite / Invisible）没有自己的传输状态，
     * 它的真实状态永远等于主箱。之前只给主箱写状态，从箱就停在某次 pause 写下的
     * PAUSED —— 于是"两个箱子都在响，输出设备列表里 LF 显示已暂停"（用户实测）。
     */
    private fun mirrorSatelliteStatus(status: DeviceStatus) {
        AppState.devices.value
            .filter { it.isSatellite }
            .forEach { sat ->
                if (AppState.deviceStatus.value[sat.key] != status) {
                    AppState.setStatus(sat.key, status)
                    LogBus.d("从箱 ${sat.displayName} 状态跟随主箱 → $status")
                }
            }
    }

    private suspend fun syncTransportState(device: SonosDevice) {
        if (AppState.externalSource.value) {
            // 音箱在放别人的内容：界面必须按"没在放我们的歌"渲染，
            // 否则播放键会显示成暂停图标、进度条继续走（1.1.6 用户实测）。
            if (AppState.deviceStatus.value[device.key] != DeviceStatus.PAUSED) {
                AppState.setStatus(device.key, DeviceStatus.PAUSED)
                syncPlaybackStateToDevices()
                updateNotification()
            }
            return
        }
        val state = runCatching { soap.getTransportState(device) }.getOrNull()
        if (state == null) {
            soapFailStreak++
            if (soapFailStreak >= 3) markDisconnected()
            return
        }
        if (soapFailStreak > 0) {
            soapFailStreak = 0
            markReconnected()
        }
        val current = AppState.deviceStatus.value[device.key]

        when (state) {
            "PAUSED_PLAYBACK" -> {
                if (current != DeviceStatus.PAUSED) {
                    LogBus.i("音箱已暂停（机身按键 / Sonos App），同步界面状态")
                    AppState.setStatus(device.key, DeviceStatus.PAUSED)
                    cancelAutoAdvance()
                    syncPlaybackStateToDevices()
                    updateNotification()
                }
                lastTransportState = state
            }

            "PLAYING" -> {
                if (current != DeviceStatus.PLAYING) {
                    LogBus.i("音箱已开始播放，同步界面状态")
                    AppState.setStatus(device.key, DeviceStatus.PLAYING)
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    updateNotification()
                }
                lastTransportState = state
            }

            "STOPPED" -> {
                if (lastTransportState == "PLAYING") {
                    if (nativeQueueActive) {
                        // 队列模式：切歌是音箱自己做的，出现 STOPPED 说明队列真的放完了
                        LogBus.i("音箱队列播放结束")
                        AppState.setStatus(device.key, DeviceStatus.IDLE)
                        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                        updateNotification()
                    } else {
                        handleEarlyStop(device)
                    }
                }
                lastTransportState = state
            }

            else -> lastTransportState = state
        }
    }

    /** 单曲推送模式下的 STOPPED 判定：区分"真的放完"与"抖动提前停止" */
    private fun handleEarlyStop(device: SonosDevice) {
        if (AppState.externalSource.value) return   // 是 Sonos App 换了源，不是我们抖动
        val sinceAdvance = SystemClock.elapsedRealtime() - lastAdvanceAt
        if (sinceAdvance < AUTO_ADVANCE_DEBOUNCE_MS) {
            LogBus.d("刚切过歌（${sinceAdvance}ms），忽略这次 STOPPED")
            return
        }
        val playedMs = if (castAtMs > 0L) {
            SystemClock.elapsedRealtime() - castAtMs
        } else {
            Long.MAX_VALUE
        }
        val expectedMs = if (castDurationMs > 0L) castDurationMs else AppState.durationSec.value * 1000L
        val tooEarly = expectedMs > 0L && playedMs < expectedMs - EARLY_STOP_TOLERANCE_MS

        if (tooEarly && earlyStopRetries < MAX_EARLY_STOP_RETRIES) {
            earlyStopRetries++
            LogBus.w(
                "音箱提前停止（已播 ${playedMs / 1000}s / 共 ${expectedMs / 1000}s），" +
                    "尝试继续播放而不是跳歌（第 $earlyStopRetries 次）"
            )
            scope.launch { runCatching { soap.play(device) } }
        } else {
            LogBus.i("播放结束 → 按「${playModeLabel()}」续播")
            playRelative(1, auto = true)
        }
    }

    /**
     * 跟随音箱队列的当前序号。
     *
     * 机身的"下一首"（双击播放键）按下后是**音箱自己**换了曲，我们收不到任何通知，
     * 只能靠 GetPositionInfo 里的 `<Track>` 变化发现，再回头更新界面与通知。
     */
    private suspend fun syncQueueTrack(device: SonosDevice) {
        // 不再用 nativeQueueActive 当门禁：单曲推送时也要按 TrackURI 认歌，
        // 否则媒体控件切模式降级后界面序号不再跟随（实测）。
        // 正在推歌时不要回写序号：会和用户刚点的那首抢指针
        if (AppState.busy.value) return
        // 刚推完的一小段时间内也别跟随：音箱切流有延迟，
        // 这时读到的还是上一首的序号，会把高亮抢到别的歌上
        if (SystemClock.elapsedRealtime() - castSettledAtMs < QUEUE_SETTLE_MS) return
        val trackNo = runCatching { soap.getQueueTrackNumber(device) }.getOrNull() ?: return
        if (trackNo <= 0) return

        val queue = AppState.queue.value
        if (queue.isEmpty()) return
        val indexByNo = (trackNo - 1).coerceIn(0, queue.lastIndex)

        // 关键：按**内容**（TrackURI 里的 fileId）反查，而不是按序号。
        // 清单一旦和音箱队列错位（例如"下一首播放"只改了 App 的清单），
        // 按序号映射会永久偏一位：UI 显示第 48 首、实际在放第 49 首（1.1.1 的实测 bug）。
        val playingUri = runCatching { soap.getPlaybackTrackUri(device) }.getOrNull()
        val playingId = playingUri
            ?.substringAfterLast("/stream/", "")
            ?.substringBefore('.')
            ?.takeIf { it.isNotEmpty() }
        val indexByContent = playingId?.let { fid ->
            queue.indexOfFirst { MediaRegistry.fileIdFor(it.uri) == fid }
        } ?: -1

        // 音箱放的不是我们的流（Sonos App 接管中）→ 既不改序号也不刷通知
        if (playingId == null) return

        val index = if (indexByContent >= 0) indexByContent else indexByNo
        val track = queue.getOrNull(index) ?: return
        if (trackNo == lastQueueTrack && index == AppState.queueIndex.value) return

        if (indexByContent < 0 && playingId != null) {
            LogBus.w("音箱放的不是清单里的这首（$playingId），按序号 $trackNo 兜底")
        } else if (indexByContent >= 0 && indexByContent != indexByNo) {
            LogBus.w("清单与音箱队列错位（音箱第 $trackNo 首 = 清单第 ${indexByContent + 1} 首），已按内容校正")
        }
        lastQueueTrack = trackNo
        LogBus.i("音箱切到了队列第 $trackNo 首 →「${track.title}」")
        AppState.queueIndex.value = index
        MediaRegistry.registerTrack(track)
        AppState.positionSec.value = 0L
        castAtMs = SystemClock.elapsedRealtime()
        castDurationMs = track.durationMs
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        updateNotification()
    }

    /** 音量回读：机身音量键 / Sonos App 调过之后，App 里的滑块要跟着动 */
    private suspend fun syncVolume(device: SonosDevice) {
        // 刚由 App 自己设过音量时先别读，避免和用户拖动打架
        if (SystemClock.elapsedRealtime() - lastVolumeSetAt < VOLUME_SYNC_GUARD_MS) return
        val volume = runCatching { soap.getVolume(device) }.getOrNull() ?: return
        if (volume != AppState.volume.value) {
            LogBus.d("音量已同步：$volume（来自音箱机身或 Sonos App）")
            AppState.volume.value = volume
        }
    }

    // ---------------------------------------------------------- 后台续播唤醒

    /**
     * 排一次"该放下一首"的唤醒。
     *
     * 为什么不只靠上面那段 2s 轮询：
     * 锁屏进入 Doze 后，系统会切断应用网络，`GetTransportInfo` 拿不到结果，
     * 就永远等不到 `PLAYING → STOPPED` 这一次跳变 —— 音箱播完只能干等，
     * 直到用户点亮屏幕、网络恢复，下一轮轮询才把"早就该切了"补上。
     *
     * 所以这里改用两个**不依赖网络**的手段，谁先到谁生效：
     *   ① 本地计时器：按已知时长 postDelayed，进程活着就一定准时；
     *   ② 精确闹钟：进程被冻结甚至回收，也能把服务叫醒（Doze 下仍会投递）。
     * 两者共用 [autoAdvanceToken]，保证只推进一次。
     */
    private fun scheduleAutoAdvance() {
        cancelAutoAdvance()

        // 队列模式下**必须**让音箱自己续播：本地计时器一旦抢跑，就会在音箱已经
        // 切到下一首之后又发一次 Next，把刚播了几秒的歌直接跳过
        // （10:02:55 开始播「奔跑」，10:03:02 被这条计时器推到「Boléro」——1.1.4 的真 bug）。
        if (nativeQueueActive) {
            LogBus.d("队列模式：由音箱自己续播，不排本地计时器")
            return
        }

        val track = currentTrack()

        // 时长优先用**曲目自己的**元数据：AppState.durationSec 是上一轮轮询回来的全局值，
        // 刚切歌时它还是上一首的时长 —— 拿它算剩余时间会导致"提前跳歌"（1.0 的真 bug）。
        val trackDurationMs = track?.durationMs?.takeIf { it > 0L }
        val polledDurationMs = AppState.durationSec.value.takeIf { it > 0L }?.times(1000L)
        val durationMs = trackDurationMs ?: polledDurationMs
        if (durationMs == null || durationMs <= 0L) {
            LogBus.d("曲目时长未知，续播只依赖 2s 轮询")
            return
        }

        // 同理，进度也只在"刚推过、还没开始播"时按 0 算，其余情况用轮询值并夹到 [0, duration]
        val positionMs = AppState.positionSec.value.coerceIn(0L, durationMs / 1000L) * 1000L
        val remainingMs = (durationMs - positionMs).coerceAtLeast(MIN_AUTO_ADVANCE_MS) + AUTO_ADVANCE_MARGIN_MS
        val token = "${track?.id ?: 0L}-${SystemClock.elapsedRealtime()}"
        autoAdvanceToken = token

        autoAdvanceHandler.postDelayed({
            if (autoAdvanceToken == token) scope.launch { verifyThenAdvance(token) }
        }, remainingMs)

        runCatching {
            val alarmManager = getSystemService(AlarmManager::class.java) ?: return@runCatching
            val pendingIntent = autoAdvancePendingIntent(token)
            val triggerAt = System.currentTimeMillis() + remainingMs
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                LogBus.w("没有精确闹钟权限，续播闹钟可能被系统延迟（建议在系统设置里允许「闹钟和提醒」）")
            }
            autoAlarmArmed = true
            LogBus.d("已排续播：${remainingMs / 1000}s 后（本地计时器 + 闹钟双保险）")
        }.onFailure { LogBus.w("排续播闹钟失败：${it.message}") }
    }

    /**
     * 本地计时器到点后**先核实**再决定是否推进。
     *
     * 计时是按曲目元数据算的，但元数据不一定准（VBR、编码器写错的时长），
     * 暂停/拖进度后 app 的时间轴也可能和音箱不同步。只要还能问到进度、
     * 且明显没放到结尾，就按真实剩余时间重排 —— 宁可晚切，绝不提前切。
     */
    private suspend fun verifyThenAdvance(token: String) {
        if (autoAdvanceToken != token) return
        if (nativeQueueActive) {
            LogBus.d("队列模式：本地续播计时器作废（由音箱自己续播）")
            cancelAutoAdvance()
            return
        }

        val playing = AppState.coordinators().firstOrNull { device ->
            AppState.deviceStatus.value[device.key] == DeviceStatus.PLAYING
        }
        val snapshot = playing?.let { runCatching { soap.getPositionInfo(it) }.getOrNull() }
        if (snapshot != null) {
            val position = snapshot.first
            val total = if (snapshot.second > 0L) snapshot.second else AppState.durationSec.value
            if (total > 0L && position < total - 3L) {
                LogBus.w("续播计时器提前到点（${position}s / ${total}s），按真实剩余时间重排")
                AppState.positionSec.value = position
                AppState.durationSec.value = total
                scheduleAutoAdvance()
                return
            }
        }

        if (autoAdvanceToken != token) return
        LogBus.i("续播计时器（本地）触发 → 推下一首")
        playRelative(1, auto = true)
    }

    /** 停播 / 暂停 / 重新推送 / 手动切歌时必须取消，否则会多跳一首 */
    private fun cancelAutoAdvance() {
        autoAdvanceHandler.removeCallbacksAndMessages(null)
        autoAdvanceToken = ""
        if (autoAlarmArmed) {
            runCatching {
                getSystemService(AlarmManager::class.java)?.cancel(autoAdvancePendingIntent(""))
            }
            autoAlarmArmed = false
        }
    }

    private fun autoAdvancePendingIntent(token: String): PendingIntent {
        val intent = Intent(this, AutoAdvanceReceiver::class.java)
            .setAction(AutoAdvanceReceiver.ACTION_AUTO_ADVANCE)
            .putExtra(AutoAdvanceReceiver.EXTRA_TOKEN, token)
        return PendingIntent.getBroadcast(
            this,
            AUTO_ADVANCE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 闹钟被投递回来：token 不匹配说明这一轮已经结束（本地计时器先跑了），直接丢弃 */
    private fun handleAutoAdvance(token: String?) {
        if (token.isNullOrBlank() || token != autoAdvanceToken) {
            LogBus.d("收到过期的续播闹钟，忽略（已切过歌）")
            return
        }
        // 队列模式下音箱自己会续播；这条闹钟只是历史遗留，收到就作废，
        // 否则会把正在播的歌强行推到下一首（1.1.4 的真 bug）。
        if (nativeQueueActive) {
            LogBus.d("队列模式：忽略续播闹钟（由音箱自己接下一首）")
            cancelAutoAdvance()
            return
        }
        LogBus.i("续播计时器（闹钟唤醒）触发 → 推下一首")
        cancelAutoAdvance()
        playRelative(1, auto = true)
    }

    /**
     * 每 30 分钟续一次 WakeLock / WifiLock。
     *
     * 原来的实现只在推送时续期，锁的 4 小时超时到了之后长播就会断流。
     * 顺带在续期时对一次账：如果还在播但计时器丢了（进程被冻结过），补排一次。
     */
    private fun startLockRenewal() {
        if (lockRenewalJob?.isActive == true) return
        lockRenewalJob = scope.launch {
            while (isActive) {
                delay(LOCK_RENEW_INTERVAL_MS)
                renewLocks()
                LogBus.d("已续期 WakeLock / WifiLock")
                val playing = AppState.deviceStatus.value.values.any { it == DeviceStatus.PLAYING }
                if (playing && AppState.nowPlayingTitle.value != null) {
                    scheduleAutoAdvance()
                }
            }
        }
    }

    /**
     * 媒体风格通知。
     *
     * 用 MediaStyle + MediaSession 才能让系统在通知栏/锁屏渲染出"播放器卡片"
     * （Android 13+ 的媒体控件、耳机线控、蓝牙按键也都走这条通路）。
     */
    private fun buildNotification(): Notification {
        val port = AppState.serverPort.value
        val selected = AppState.selectedDevices().size
        val track = currentTrack()
        val playing = AppState.devices.value.any {
            AppState.deviceStatus.value[it.key] == DeviceStatus.PLAYING
        }

        val title = track?.title ?: AppState.nowPlayingTitle.value ?: "ZONOZ local stream"
        val subtitle = track?.subtitle ?: buildString {
            append(
                if (port > 0) I18n.f(Str.PORT_N, port.toString())
                else I18n.t(Str.PHASE_STARTING)
            )
            append(" · ")
            append(I18n.f(Str.SPEAKERS_N, selected.toString()))
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val prevIntent = PendingIntent.getService(
            this, 1, Intent(this, SonosCastService::class.java).setAction(ACTION_PREV), flags
        )
        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, SonosCastService::class.java)
                .setAction(if (playing) ACTION_PAUSE else ACTION_RESUME),
            flags
        )
        val nextIntent = PendingIntent.getService(
            this, 3, Intent(this, SonosCastService::class.java).setAction(ACTION_NEXT), flags
        )
        val shuffleIntent = PendingIntent.getService(
            this, 5,
            Intent(this, SonosCastService::class.java).setAction(ACTION_TOGGLE_SHUFFLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val repeatIntent = PendingIntent.getService(
            this, 6,
            Intent(this, SonosCastService::class.java).setAction(ACTION_TOGGLE_REPEAT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exitIntent = PendingIntent.getService(
            this, 4, Intent(this, SonosCastService::class.java).setAction(ACTION_SHUTDOWN), flags
        )

        val builder = NotificationCompat.Builder(this, SonosCastApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_blank)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSubText(
                if (selected > 0) {
                    I18n.f(Str.SPEAKERS_N, selected.toString()) + " · " +
                        I18n.f(Str.PORT_N, port.toString())
                } else {
                    null
                }
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            // 锁屏/通知媒体卡片取的是**通知里的 action**，不是会话的 custom action，
            // 所以这里的图标也必须跟随状态，且遵循 App 内互斥：
            //   随机开 → 随机实心、循环空心
            //   随机关 → 随机空心、循环实心（列表或单曲）
            .addAction(
                if (AppState.shuffle.value) R.drawable.ic_shuffle else R.drawable.ic_shuffle_dim,
                I18n.t(Str.MODE_SHUFFLE),
                shuffleIntent
            )
            .addAction(R.drawable.ic_notif_prev, I18n.t(Str.PREV), prevIntent)
            .addAction(
                if (playing) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
                if (playing) I18n.t(Str.PAUSE) else I18n.t(Str.PLAY),
                toggleIntent
            )
            .addAction(R.drawable.ic_notif_next, I18n.t(Str.NEXT), nextIntent)
            .addAction(
                when {
                    AppState.shuffle.value -> R.drawable.ic_repeat_dim
                    AppState.repeatMode.value == RepeatMode.ONE -> R.drawable.ic_repeat_one
                    else -> R.drawable.ic_repeat
                },
                I18n.t(
                    if (AppState.repeatMode.value == RepeatMode.ONE) Str.MODE_REPEAT_ONE
                    else Str.MODE_REPEAT_ALL
                ),
                repeatIntent
            )
            .addAction(0, I18n.t(Str.EXIT), exitIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // 有封面就挂上去，媒体卡片会显示成专辑图；
        // 同时把 ZONOZ 角标合成进大图 —— 系统自带的 small icon 角标已被我们置空（见 ic_stat_blank.xml）
        val cover = CoverLoader.peek(track)
        if (cover != null) {
            builder.setLargeIcon(NotifArt.withBrandBadge(cover))
        } else if (track != null) {
            // 缓存里没有（比如进程刚重启、UI 还没加载过）→ 自己去解一张，解完刷新通知
            loadCoverForNotification(track)
        }

        return builder.build()
    }

    private var coverLoadPending = false

    /** 通知专用：异步补一张封面，成功后刷新通知（失败就算了，下次更新再试） */
    private fun loadCoverForNotification(track: com.sonoscast.local.core.Track) {
        if (coverLoadPending) return
        coverLoadPending = true
        scope.launch {
            val bitmap = runCatching { CoverLoader.load(this@SonosCastService, track) }.getOrNull()
            coverLoadPending = false
            if (bitmap != null) updateNotification()
        }
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    // -------------------------------------------------------------- WakeLock

    /**
     * 熄屏后要保持两把锁：
     * - PARTIAL_WAKE_LOCK 让 CPU 不休眠（否则 HTTP 线程被冻结，音箱立刻断流）
     * - WifiLock 让 Wi-Fi 保持高性能模式（很多 ROM 熄屏后会进省电模式，延迟飙升导致卡顿）
     *
     * 两者都带超时，[renewLocks] 会在每次推送时续期，避免长时间播放中途失效。
     */
    private fun renewLocks() {
        val playing = AppState.deviceStatus.value.values.any { it == DeviceStatus.PLAYING }
        if (!playing || AppState.connectionLost.value) {
            // 没在播就别守着 CPU：旧版每 30 分钟无条件续锁，导致熄屏后整夜被唤醒
            releaseLocks()
            return
        }
        releaseLocks()
        acquireWakeLock()
        acquireWifiLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SonosCast::mediaServer"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            LogBus.d("已获取 CPU WakeLock（${WAKE_LOCK_TIMEOUT_MS / 3600000} 小时）")
        }.onFailure { LogBus.w("获取 WakeLock 失败：${it.message}") }
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        runCatching {
            val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "SonosCast::wifi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            LogBus.d("已获取 Wi-Fi 高性能锁")
        }.onFailure { LogBus.w("获取 WifiLock 失败：${it.message}") }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val SCAN_TIMEOUT_MS = 3000L
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000

        /** Sonos 私有错误码：把指令发给立体声配对里的从设备时会返回它 */
        private const val SONOS_ERROR_SATELLITE = 1023

        /**
         * S1 老固件上常见的"该动作不支持"错误码。
         *
         * 401 Invalid Action / 402 Invalid Args / 501 Action Failed / 602 Optional Action Not Implemented。
         * 命中时只记日志引导反馈，不改变现有的最小指令集（本版对 S1 不发任何新动作）。
         */
        private val S1_UNSUPPORTED_ACTIONS = setOf(401, 402, 501, 602)

        /** 续播最短间隔：太短的曲子（或刚开播）也别立刻触发 */
        private const val MIN_AUTO_ADVANCE_MS = 5_000L

        /** 留一点余量，确保音箱那边真的播完了 */
        private const val AUTO_ADVANCE_MARGIN_MS = 1_500L

        private const val AUTO_ADVANCE_REQUEST_CODE = 2001

        /** WakeLock / WifiLock 续期周期 */
        private const val LOCK_RENEW_INTERVAL_MS = 30L * 60 * 1000

        /** 切歌后的静默期：这段时间内轮询到的 STOPPED 一律忽略，防止连跳两首 */
        private const val AUTO_ADVANCE_DEBOUNCE_MS = 10_000L

        /** 进度显示节拍：1 秒 */
        private const val PROGRESS_TICK_MS = 1_000L
    // 后台省电：不同状态下轮询间隔（1.2.1 起）
    private const val PAUSED_POLL_MS = 5_000L
    private const val IDLE_POLL_MS = 15_000L
    private const val DISCONNECTED_POLL_MS = 30_000L

        /** 提前停止的容差：实际播放时长比「时长 - 20s」还短，就不当成正常放完 */
        private const val EARLY_STOP_TOLERANCE_MS = 20_000L

        /** 提前停止最多尝试续播几次，超过就认命切歌 */
        private const val MAX_EARLY_STOP_RETRIES = 2

        /** 每多少轮采样读一次音量（4 = 约 8 秒一次） */
        private const val VOLUME_POLL_EVERY_SAMPLES = 4

        /** 刚由 App 设过音量后，这段时间内不接受回读 */
        private const val VOLUME_SYNC_GUARD_MS = 3_000L

        /** 刚推完队列后，等这么久再让轮询跟随音箱序号 */
        private const val QUEUE_SETTLE_MS = 1_500L

        /** 清单变了之后给"主动同步"留的宽限期；超过就由对账兜底重建 */
        private const val RECONCILE_GRACE_MS = 4_000L

        /** 每多少次采样做一次队列长度自检（2s 一次采样 × 5 ≈ 10 秒） */
        private const val QUEUE_AUDIT_EVERY_SAMPLES = 5

        /** 自检发现不一致后，最少间隔这么久才再重建一次 */
        private const val AUDIT_REBUILD_COOLDOWN_MS = 60_000L

        /** 灌队列时每次塞多少首（实测音箱上限 16，18 就 402/500，取 12 留余量） */
        private const val QUEUE_CHUNK_SIZE = 12

        /** 每 N 拍真正向音箱采样一次（当前 = 每 2 秒一次 SOAP，其余用本地插值） */
        private const val PROGRESS_SAMPLE_EVERY_TICKS = 2

        private const val EXTRA_VOLUME = "extra_volume"
        private const val EXTRA_IP = "extra_ip"
        private const val EXTRA_POSITION = "extra_position"
        private const val EXTRA_INSERTED_TRACK_ID = "extra_inserted_track_id"
        private const val EXTRA_FORCE_REBUILD = "extra_force_rebuild"

        private const val ACTION_START = "com.sonoscast.local.action.START"
        private const val ACTION_SCAN = "com.sonoscast.local.action.SCAN"
        private const val ACTION_CAST = "com.sonoscast.local.action.CAST"
        private const val ACTION_PAUSE = "com.sonoscast.local.action.PAUSE"
        private const val ACTION_RESUME = "com.sonoscast.local.action.RESUME"
        private const val ACTION_STOP = "com.sonoscast.local.action.STOP"
        private const val ACTION_NEXT = "com.sonoscast.local.action.NEXT"
        private const val ACTION_PREV = "com.sonoscast.local.action.PREV"
        private const val ACTION_TOGGLE_SHUFFLE = "com.sonoscast.local.action.TOGGLE_SHUFFLE"
        private const val ACTION_CYCLE_REPEAT = "com.sonoscast.local.action.CYCLE_REPEAT"
        private const val ACTION_QUEUE_CHANGED = "com.sonoscast.local.action.QUEUE_CHANGED"
        private const val ACTION_SEEK = "com.sonoscast.local.action.SEEK"
        private const val ACTION_SET_VOLUME = "com.sonoscast.local.action.SET_VOLUME"
    private const val ACTION_TOGGLE_REPEAT = "com.sonoscast.local.action.TOGGLE_REPEAT"
        private const val ACTION_ADD_MANUAL = "com.sonoscast.local.action.ADD_MANUAL"
        private const val ACTION_SHUTDOWN = "com.sonoscast.local.action.SHUTDOWN"


        /**
         * 是否已被排除在电池优化之外。
         *
         * 没被排除的 App 在锁屏进 Doze 后网络会被切断 —— 这正是"播完不续"的根因之一，
         * 所以调试页要把它显示成一个可操作的引导项，而不是藏在系统设置里。
         */
        fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName)
                ?: false
        }.getOrDefault(false)

        /** 跳到系统「电池优化」页，让用户把本应用设为"不限制" */
        fun requestIgnoreBatteryOptimizations(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { LogBus.w("打开电池优化设置失败：${it.message}") }
        }

        /**
         * 跳到「闹钟和提醒」权限页。
         *
         * Android 12+ 上 SCHEDULE_EXACT_ALARM 默认不授予（targetSdk 33+），
         * 没有它就只能用 setAndAllowWhileIdle，锁屏续播可能晚十几分钟。
         */
        fun requestExactAlarmPermission(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { LogBus.w("打开精确闹钟设置失败：${it.message}") }
        }

        fun start(context: Context) = send(context, ACTION_START)
        fun scan(context: Context) = send(context, ACTION_SCAN)
        fun cast(context: Context) = send(context, ACTION_CAST)
        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stopPlayback(context: Context) = send(context, ACTION_STOP)
        fun next(context: Context) = send(context, ACTION_NEXT)
        fun previous(context: Context) = send(context, ACTION_PREV)
        fun toggleShuffle(context: Context) = send(context, ACTION_TOGGLE_SHUFFLE)
        fun cycleRepeat(context: Context) = send(context, ACTION_CYCLE_REPEAT)

        /**
         * 播放清单被"下一首播放"改过之后调用：把这次插入**同步写进音箱的队列**。
         *
         * 1.1 之前只改了 App 的清单，音箱的队列没变 —— 送别放完后音箱按自己的顺序走了
         * 下一首（Serenade），而 App 按自己的清单认为下一首是 Under Pressure，
         * 于是 UI 和声音永久错开一位（1.1.1 修）。
         */
        fun queueChanged(context: Context, insertedTrackId: Long = -1L, rebuild: Boolean = false) =
            send(context, ACTION_QUEUE_CHANGED, insertedTrackId = insertedTrackId, forceRebuild = rebuild)
        fun seek(context: Context, seconds: Int) = send(context, ACTION_SEEK, position = seconds)
        fun setVolume(context: Context, volume: Int) = send(context, ACTION_SET_VOLUME, volume = volume)
        fun addManualDevice(context: Context, ip: String) = send(context, ACTION_ADD_MANUAL, ip = ip)
        fun shutdown(context: Context) = send(context, ACTION_SHUTDOWN)

        private fun send(
            context: Context,
            action: String,
            volume: Int? = null,
            ip: String? = null,
            position: Int? = null,
            insertedTrackId: Long? = null,
            forceRebuild: Boolean? = null
        ) {
            val intent = Intent(context, SonosCastService::class.java).setAction(action)
            volume?.let { intent.putExtra(EXTRA_VOLUME, it) }
            ip?.let { intent.putExtra(EXTRA_IP, it) }
            position?.let { intent.putExtra(EXTRA_POSITION, it) }
            insertedTrackId?.let { intent.putExtra(EXTRA_INSERTED_TRACK_ID, it) }
            forceRebuild?.let { intent.putExtra(EXTRA_FORCE_REBUILD, it) }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                LogBus.e("无法启动服务：${t.javaClass.simpleName} ${t.message}")
            }
        }
    }
}
