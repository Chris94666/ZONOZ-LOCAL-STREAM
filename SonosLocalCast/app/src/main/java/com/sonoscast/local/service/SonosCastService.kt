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

    private fun castCurrent() {
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
        scope.launch { performCast(item, targets) }
    }

    private suspend fun performCast(item: MediaItem, targets: List<SonosDevice>) {
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

    /** 清单没建立时用曲库兜底，避免"上一首/下一首"点了没反应 */
    private fun ensureQueueReady() {
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
        scope.launch { performCast(MediaRegistry.current.value ?: return@launch, targets) }
    }

    /** 随机开关：只改顺序，循环按钮随之亮/灭（互斥由 AppState 保证） */
    private fun toggleShuffle() {
        AppState.setShuffle(!AppState.shuffle.value)
        LogBus.i("播放模式 → ${playModeLabel()}")
        updateNotification()
    }

    /** 循环在「列表循环 ↔ 单曲循环」之间切换，并顺带退出随机 */
    private fun cycleRepeat() {
        AppState.setRepeatMode(AppState.repeatMode.value.next())
        LogBus.i("播放模式 → ${playModeLabel()}")
        updateNotification()
    }

    /** 日志/通知里的一句话描述：随机优先显示，其次循环 */
    private fun playModeLabel(): String = when {
        AppState.shuffle.value -> "随机播放"
        AppState.repeatMode.value == RepeatMode.ONE -> "单曲循环"
        else -> "列表循环"
    }

    // ------------------------------------------------------------ 媒体会话

    private fun initMediaSession() {
        if (mediaSession != null) return
        mediaSession = MediaSessionCompat(this, "ZonozLocalStream").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumeAll()
                override fun onPause() = pauseAll()
                override fun onStop() = stopAll()
                override fun onSkipToNext() = playRelative(1)
                override fun onSkipToPrevious() = playRelative(-1)
            })
            isActive = true
        }
        updatePlaybackState(PlaybackStateCompat.STATE_NONE)
    }

    private fun updatePlaybackState(state: Int) {
        val session = mediaSession ?: return
        runCatching {
            val track = currentTrack()
            // 锁屏 / 通知媒体卡片显示的歌名、艺术家、时长都来自 MediaMetadata
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track?.title ?: "ZONOZ LOCAL STREAM")
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
                    .setState(state, AppState.positionSec.value * 1000L, 1f)
                    .build()
            )
        }
    }

    private fun pauseAll() {
        scope.launch {
            val targets = AppState.controlTargets()
            if (targets.isEmpty()) return@launch
            targets.forEach { device ->
                when (val result = soap.pause(device)) {
                    is SoapResult.Success -> {
                        AppState.setStatus(device.key, DeviceStatus.PAUSED)
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
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            updateNotification()
            cancelAutoAdvance()
        }
    }

    private fun resumeAll() {
        scope.launch {
            val targets = AppState.controlTargets()
            if (targets.isEmpty()) return@launch
            targets.forEach { device ->
                when (val result = soap.play(device)) {
                    is SoapResult.Success -> {
                        AppState.setStatus(device.key, DeviceStatus.PLAYING)
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
            val targets = AppState.controlTargets()
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
        scope.launch {
            val targets = AppState.controlTargets()
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
            val targets = AppState.controlTargets()
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
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
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
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            var tick = 0
            while (isActive) {
                val playing = AppState.coordinators().firstOrNull { device ->
                    AppState.deviceStatus.value[device.key] == DeviceStatus.PLAYING
                }
                if (playing != null) {
                    val sampled = tick % PROGRESS_SAMPLE_EVERY_TICKS == 0
                    if (sampled) {
                        runCatching { soap.getPositionInfo(playing) }.getOrNull()?.let { (position, duration) ->
                            AppState.positionSec.value = position
                            if (duration > 0L) AppState.durationSec.value = duration
                        }
                    } else {
                        // 两次采样之间的本地推进：只加秒数，不超过总时长
                        val next = AppState.positionSec.value + 1
                        val total = AppState.durationSec.value
                        if (total <= 0L || next < total) {
                            AppState.positionSec.value = next
                        }
                    }

                    // 检测"一首放完"：状态从 PLAYING 掉到 STOPPED 就是它自己播完了
                    val state = if (sampled) runCatching { soap.getTransportState(playing) }.getOrNull() else null
                    if (state != null) {
                        if (lastTransportState == "PLAYING" && state == "STOPPED") {
                            // 计时器/闹钟刚切过歌时，音箱可能还在报上一首的 STOPPED。
                            // 这里做一次静默期，避免"连跳两首"。
                            val sinceAdvance = SystemClock.elapsedRealtime() - lastAdvanceAt
                            if (sinceAdvance < AUTO_ADVANCE_DEBOUNCE_MS) {
                                LogBus.d("刚切过歌（${sinceAdvance}ms），忽略这次 STOPPED")
                            } else {
                                // 再核一道：停止来得远早于这一首的时长 → 多半是网络抖动/音箱自己停了，
                                // 不能当成"放完了"，否则会把没放完的歌切掉（1.0 的真 bug）。
                                val playedMs = if (castAtMs > 0L) {
                                    SystemClock.elapsedRealtime() - castAtMs
                                } else {
                                    Long.MAX_VALUE
                                }
                                val expectedMs = if (castDurationMs > 0L) {
                                    castDurationMs
                                } else {
                                    AppState.durationSec.value * 1000L
                                }
                                val tooEarly = expectedMs > 0L && playedMs < expectedMs - EARLY_STOP_TOLERANCE_MS

                                if (tooEarly && earlyStopRetries < MAX_EARLY_STOP_RETRIES) {
                                    earlyStopRetries++
                                    LogBus.w(
                                        "音箱提前停止（已播 ${playedMs / 1000}s / 共 ${expectedMs / 1000}s），" +
                                            "尝试继续播放而不是跳歌（第 $earlyStopRetries 次）"
                                    )
                                    runCatching { soap.play(playing) }
                                } else {
                                    LogBus.i("播放结束 → 按「${playModeLabel()}」续播")
                                    playRelative(1, auto = true)
                                }
                            }
                        }
                        if (state != lastTransportState) {
                            updatePlaybackState(
                                when (state) {
                                    "PLAYING" -> PlaybackStateCompat.STATE_PLAYING
                                    "PAUSED_PLAYBACK" -> PlaybackStateCompat.STATE_PAUSED
                                    "STOPPED" -> PlaybackStateCompat.STATE_STOPPED
                                    else -> PlaybackStateCompat.STATE_NONE
                                }
                            )
                        }
                        lastTransportState = state
                    }
                }
                tick++
                delay(PROGRESS_TICK_MS)
            }
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

        val title = track?.title ?: AppState.nowPlayingTitle.value ?: "ZONOZ LOCAL STREAM"
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
            .addAction(R.drawable.ic_notif_prev, I18n.t(Str.PREV), prevIntent)
            .addAction(
                if (playing) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
                if (playing) I18n.t(Str.PAUSE) else I18n.t(Str.PLAY),
                toggleIntent
            )
            .addAction(R.drawable.ic_notif_next, I18n.t(Str.NEXT), nextIntent)
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

        /** 提前停止的容差：实际播放时长比「时长 - 20s」还短，就不当成正常放完 */
        private const val EARLY_STOP_TOLERANCE_MS = 20_000L

        /** 提前停止最多尝试续播几次，超过就认命切歌 */
        private const val MAX_EARLY_STOP_RETRIES = 2

        /** 每 N 拍真正向音箱采样一次（当前 = 每 2 秒一次 SOAP，其余用本地插值） */
        private const val PROGRESS_SAMPLE_EVERY_TICKS = 2

        private const val EXTRA_VOLUME = "extra_volume"
        private const val EXTRA_IP = "extra_ip"
        private const val EXTRA_POSITION = "extra_position"

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
        private const val ACTION_SEEK = "com.sonoscast.local.action.SEEK"
        private const val ACTION_SET_VOLUME = "com.sonoscast.local.action.SET_VOLUME"
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
        fun seek(context: Context, seconds: Int) = send(context, ACTION_SEEK, position = seconds)
        fun setVolume(context: Context, volume: Int) = send(context, ACTION_SET_VOLUME, volume = volume)
        fun addManualDevice(context: Context, ip: String) = send(context, ACTION_ADD_MANUAL, ip = ip)
        fun shutdown(context: Context) = send(context, ACTION_SHUTDOWN)

        private fun send(
            context: Context,
            action: String,
            volume: Int? = null,
            ip: String? = null,
            position: Int? = null
        ) {
            val intent = Intent(context, SonosCastService::class.java).setAction(action)
            volume?.let { intent.putExtra(EXTRA_VOLUME, it) }
            ip?.let { intent.putExtra(EXTRA_IP, it) }
            position?.let { intent.putExtra(EXTRA_POSITION, it) }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                LogBus.e("无法启动服务：${t.javaClass.simpleName} ${t.message}")
            }
        }
    }
}
