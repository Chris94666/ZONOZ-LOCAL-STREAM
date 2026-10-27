package com.sonoscast.local.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 一条待展示的提示。
 *
 * 存的是文案键 + 参数而不是成品字符串 —— 用户切语言时提示会跟着变，
 * 而不是停留在生成那一刻的语言。
 */
data class Hint(val key: Str, val args: List<String> = emptyList())

enum class ServicePhase {
    /** 服务未启动 */
    STOPPED,

    /** 正在准备 HTTP 服务器 / 网络 */
    STARTING,

    /** 服务已就绪但没发现任何音箱（纯本地待命模式） */
    STANDBY,

    /** 服务就绪且至少发现一台音箱 */
    READY,

    /** 致命错误，需要用户干预 */
    ERROR
}

enum class DeviceStatus {
    IDLE, PUSHING, PLAYING, PAUSED, ERROR,

    /** 立体声配对里的从设备：不单独接收推送，随主箱同步发声 */
    FOLLOWING
}

/**
 * 循环方式（仅在关闭随机时生效）。
 *
 * 1.8.2 起取消"顺序播放到末尾就停"这一档：随机与循环永远有一个亮着，
 * 所以循环只剩「列表循环 / 单曲循环」两态，关掉随机就回到上一次的循环方式。
 */
enum class RepeatMode {
    /** 列表循环：播到清单末尾回到第一首 */
    ALL,

    /** 单曲循环：始终重播当前曲 */
    ONE;

    fun next(): RepeatMode = if (this == ALL) ONE else ALL
}

/**
 * 全局运行状态。
 *
 * 用 object 单例 + StateFlow，让 Compose 直接 collect，不必绑定 Service。
 * Service 是唯一的写入方，UI 只读 + 通过 Intent 发指令，单向数据流。
 */
object AppState {

    val phase = MutableStateFlow(ServicePhase.STOPPED)
    val localIp = MutableStateFlow<String?>(null)
    val serverPort = MutableStateFlow(0)
    val streamUrl = MutableStateFlow<String?>(null)

    val devices = MutableStateFlow<List<SonosDevice>>(emptyList())
    val selectedKeys = MutableStateFlow<Set<String>>(emptySet())

    val scanning = MutableStateFlow(false)
    val busy = MutableStateFlow(false)

    val nowPlayingTitle = MutableStateFlow<String?>(null)
    val volume = MutableStateFlow(30)

    /** 音箱回报的播放进度（秒），由 Service 定时轮询 GetPositionInfo 写入 */
    val positionSec = MutableStateFlow(0L)
    val durationSec = MutableStateFlow(0L)

    /** 随机播放开关（与循环互斥：随机亮 → 循环灭） */
    val shuffle = MutableStateFlow(false)

    /** 循环方式：列表循环 / 单曲循环（随机开启时界面熄灭，但保留用户上次选择） */
    val repeatMode = MutableStateFlow(RepeatMode.ALL)

    /**
     * 开/关随机。
     *
     * 只改顺序、不丢循环设置：关掉随机后循环按钮会重新亮起，沿用上次的列表/单曲。
     * 同时立即重排播放清单，界面上的"播放清单"会马上跟着变。
     */
    fun setShuffle(enabled: Boolean) {
        if (shuffle.value == enabled) return
        shuffle.value = enabled
        // 随机与单曲循环互斥：开随机时必须退出单曲循环，
        // 否则 orderFor 会按"单曲循环"把清单收敛成 1 首，
        // nativePlayMode 还会给音箱发 REPEAT_ONE（单曲无限循环）。
        if (enabled) repeatMode.value = RepeatMode.ALL
        reorderQueue()
    }

    /**
     * 切循环：点循环键本身就意味着退出随机（两者互斥）。
     *
     * 单曲循环会把播放清单收敛成"只有当前这一首"，切回列表循环再按原始列表还原 ——
     * 界面上的清单数量必须和实际播放行为一致，否则会出现"显示 71 首但只播 1 首"。
     */
    fun setRepeatMode(mode: RepeatMode) {
        repeatMode.value = mode
        shuffle.value = false
        reorderQueue()
    }

    // ---------------------------------------------------------- 播放清单

    /**
     * 当前播放清单。
     *
     * 这是"下一首/上一首"的唯一依据（1.8.2 之前直接逛曲库，导致关掉随机也会跳下一首）。
     * 顺序即播放顺序；开启随机后显示的就是打乱后的顺序。
     */
    val queue = MutableStateFlow<List<Track>>(emptyList())

    /** 清单里当前播放到第几首，-1 表示还没有 */
    val queueIndex = MutableStateFlow(-1)

    /**
     * 用户最近一次点歌的曲目 id。
     *
     * 点歌是"UI 改状态 → 发 Intent → Service 异步处理"，中间隔着一个轮询周期；
     * 轮询会把 [queueIndex] 改成音箱当前那首，于是 Service 拿到的是旧序号，
     * 结果 seek 到别的歌上（1.0.2/33 的"点 A 播 B"）。
     * 这里记下用户真正点的那首，推送时按 id 反查序号，杜绝这个竞态。
     */
    private var pendingTrackId: Long? = null

    fun markPendingTrack(track: Track) {
        pendingTrackId = track.id
    }

    /** 取"用户点的那首"在清单里的序号，并清除标记；没有标记时退回 [queueIndex] */
    fun takePendingQueueIndex(): Int {
        val id = pendingTrackId
        pendingTrackId = null
        if (id != null) {
            val i = queue.value.indexOfFirst { it.id == id }
            if (i >= 0) return i
        }
        return queueIndex.value
    }

    /** 未打乱的原始顺序（用户点歌时所在的列表），关随机时用来还原 */
    private var queueSource: List<Track> = emptyList()

    /**
     * 清单版本号：每次清单内容/顺序变化 +1。
     *
     * Service 侧靠它判断"音箱队列是不是已经落后于 App 清单"——
     * 这样就不需要每个改清单的入口自己记得去同步音箱（那正是 1.1/1.1.1
     * 反复出错的根源：漏一个入口，序号就永久偏一位）。
     */
    var queueRevision: Int = 0
        private set

    /**
     * 修改播放清单的**唯一入口**。
     *
     * 任何想改清单的代码都必须走这里，否则 `queueRevision` 不会递增，
     * 对账机制就失效。改动原因 [reason] 会写进日志，方便回溯是谁改的。
     */
    fun updateQueue(next: List<Track>, reason: String) {
        if (next === queue.value) return
        queue.value = next
        queueRevision++
        LogBus.d("清单已变更（$reason）：${next.size} 首 · rev=$queueRevision")
    }

    fun currentFromQueue(): Track? = queue.value.getOrNull(queueIndex.value)

    /**
     * 点歌时建立播放清单。
     *
     * [source] 是用户点歌时看到的那个列表（曲库 / 搜索结果）；
     * 随机开启时当前曲置顶、其余打乱，这样清单一打开就是真正会播的顺序。
     */
    fun startQueue(source: List<Track>, current: Track) {
        queueSource = source
        val ordered = orderFor(source, current)
        updateQueue(ordered, "点歌建立清单")
        val found = ordered.indexOfFirst { it.id == current.id }
        queueIndex.value = if (found >= 0) found else 0
        markPendingTrack(current)
    }

    /** 把清单指针移到指定曲目（不在清单里则忽略） */
    fun setQueueTrack(track: Track) {
        val idx = queue.value.indexOfFirst { it.id == track.id }
        if (idx >= 0) queueIndex.value = idx
        markPendingTrack(track)
    }

    /** 上/下一首：按清单循环走，返回目标曲目 */
    fun step(offset: Int): Track? {
        val list = queue.value
        if (list.isEmpty()) return null
        val size = list.size
        val target = ((queueIndex.value + offset) % size + size) % size
        queueIndex.value = target
        return list[target]
    }

    /**
     * 长按"下一首播放"：把这首歌**挪到**当前曲后面。
     *
     * 注意：不能"已经在清单里就拒绝"。从曲库点歌时，清单本身就是整个曲库，
     * 每一首都已经在里面了 —— 那样点"下一首播放"会永远没反应（1.0 的真 bug）。
     * 正确行为是调整顺序：先把自己从原位置摘掉，再插到当前曲后面。
     *
     * 单曲循环时清单里只有当前这一首，插队没有意义，直接拒绝。
     *
     * @return true = 顺序有变化；false = 本来就满足（或不允许）
     */
    /**
     * 上一次 [insertNext] 是"移动清单里已有的曲目"（true）还是"插入新曲目"（false）。
     *
     * 移动时音箱队列里那份旧副本还在原位，原生插队会造成两份，
     * 必须改用"重建整个队列"来对齐（见 `SonosCastService.syncSpeakerQueue`）。
     */
    var lastInsertWasMove: Boolean = false
        private set

    fun insertNext(track: Track): Boolean {
        if (repeatMode.value == RepeatMode.ONE && !shuffle.value) return false

        val list = queue.value.toMutableList()
        var currentIdx = queueIndex.value.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        val currentId = list.getOrNull(currentIdx)?.id

        // 就是当前正在播的这首，挪不动
        if (track.id == currentId) return false

        val existing = list.indexOfFirst { it.id == track.id }
        // 已经紧挨着当前曲，无需改动
        if (existing >= 0 && existing == currentIdx + 1) return false

        if (existing >= 0) {
            list.removeAt(existing)
            // 关键：如果摘掉的这条在当前曲**前面**，当前曲的位置要跟着前移一位。
            // 不修正的话 queueIndex 会指向"下一首"，随后重建队列就 seek 到别的歌，
            // 用户听到的是"点了下一首播放，音乐立刻跳到另一首"（1.1.3 的真 bug）。
            if (existing < currentIdx) currentIdx--
        }

        // 记下这次是"移动已有曲目"还是"插入新曲目"：
        //   移动 → 音箱里那份旧副本还在原位，只能用重建队列来一致；
        //   新增 → 可以用 Sonos 的 EnqueueAsNext 原生插队（不打断播放）。
        lastInsertWasMove = existing >= 0

        val insertAt = (currentIdx + 1).coerceIn(0, list.size)
        if (list.getOrNull(insertAt)?.id == track.id) return false
        list.add(insertAt, track)

        queueIndex.value = currentIdx.coerceIn(0, list.lastIndex)
        updateQueue(list, "下一首播放")
        return true
    }

    /** 清单里紧挨着的下一首是不是它（曲库角标用） */
    fun isNextInQueue(trackId: Long): Boolean {
        val list = queue.value
        if (list.isEmpty()) return false
        val next = (queueIndex.value + 1) % list.size
        return list[next].id == trackId
    }

    /** 当前曲之后还剩几首（副标题用） */
    fun upcomingCount(): Int {
        val list = queue.value
        if (list.isEmpty()) return 0
        return (list.size - queueIndex.value - 1).coerceAtLeast(0)
    }

    /**
     * 按当前模式算出清单内容。
     *
     * - 单曲循环：清单里只有当前这一首（播放清单界面就显示 1 首，和实际播放行为一致）
     * - 随机：当前曲置顶 + 其余打乱
     * - 其余：还原曲库顺序
     */
    private fun orderFor(source: List<Track>, current: Track?): List<Track> {
        if (current == null) return source
        // 随机优先于单曲循环：开了随机就该看到**整库打乱**的 72 首，
        // 不能被残留的"单曲循环"压成 1 首（用户实测：单曲循环→随机，清单只剩 1 首）
        if (shuffle.value) {
            val rest = source.filter { it.id != current.id }.shuffled()
            return listOf(current) + rest
        }
        if (repeatMode.value == RepeatMode.ONE) return listOf(current)
        return source
    }

    private fun reorderQueue() {
        val current = currentFromQueue() ?: return
        val source = queueSource.ifEmpty { queue.value }
        val ordered = orderFor(source, current)
        updateQueue(ordered, "重排（随机/循环）")
        queueIndex.value = ordered.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    }

    val deviceStatus = MutableStateFlow<Map<String, DeviceStatus>>(emptyMap())
    val deviceError = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 最近一次需要用户在 UI 上看到的提示 */
    val hint = MutableStateFlow<Hint?>(null)

    val logs: StateFlow<List<LogEntry>> get() = LogBus.entries

    fun upsertDevices(found: List<SonosDevice>) {
        devices.update { old ->
            val byKey = LinkedHashMap<String, SonosDevice>()
            old.forEach { byKey[it.key] = it }
            found.forEach { byKey[it.key] = it }
            byKey.values.toList()
        }
        // 首次发现时自动勾选，减少一次点击
        if (selectedKeys.value.isEmpty() && found.isNotEmpty()) {
            selectedKeys.value = found.take(2).map { it.key }.toSet()
        }
    }

    fun toggleSelection(key: String) {
        selectedKeys.update { if (key in it) it - key else it + key }
    }

    fun setSelection(keys: Set<String>) {
        selectedKeys.value = keys
    }

    fun removeDevice(key: String) {
        devices.update { list -> list.filterNot { it.key == key } }
        selectedKeys.update { it - key }
        deviceStatus.update { it - key }
    }

    fun setStatus(key: String, status: DeviceStatus, error: String? = null) {
        deviceStatus.update { it + (key to status) }
        deviceError.update { if (error == null) it - key else it + (key to error) }
    }

    /**
     * 用 ZoneGroupTopology 的结果刷新每台设备的角色。
     *
     * 主箱 → isSatellite=false；从箱 → isSatellite=true 并记录 LF/RF 声道。
     * 拓扑里没有的设备（比如手动添加但组里没它）保持原样。
     */
    fun applyTopology(nodes: Map<String, ZoneTopology.Node>) {
        if (nodes.isEmpty()) return
        devices.update { list ->
            list.map { device ->
                val node = nodes[device.uuid] ?: return@map device
                device.copy(
                    isSatellite = node.isSatellite(),
                    channelRole = node.channel(),
                    // 拓扑比设备描述更权威：这里的 SWGen 一定和设备当前固件一致
                    swGen = if (node.swGen > 0) node.swGen else device.swGen,
                    softwareVersion = node.softwareVersion.ifBlank { device.softwareVersion }
                )
            }
        }
    }

    /** 主箱（能接收 Transport 指令的设备） */
    fun coordinators(): List<SonosDevice> = devices.value.filterNot { it.isSatellite }

    fun resetRuntime() {
        phase.value = ServicePhase.STOPPED
        serverPort.value = 0
        localIp.value = null
        streamUrl.value = null
        devices.value = emptyList()
        selectedKeys.value = emptySet()
        scanning.value = false
        busy.value = false
        nowPlayingTitle.value = null
        positionSec.value = 0L
        durationSec.value = 0L
        updateQueue(emptyList(), "清空清单")
        queueIndex.value = -1
        queueSource = emptyList()
        deviceStatus.value = emptyMap()
        deviceError.value = emptyMap()
        hint.value = null
    }

    /** 当前选中且仍然存在的设备 */
    fun selectedDevices(): List<SonosDevice> {
        val keys = selectedKeys.value
        return devices.value.filter { it.key in keys }
    }

    /**
     * 当前"正在播放/已推送"的设备。
     *
     * 停止、暂停这类操作必须以它为准，而不是 [selectedKeys] ——
     * 否则用户推送完再取消勾选，就再也停不掉了。
     */
    fun activeDevices(): List<SonosDevice> {
        val statuses = deviceStatus.value
        return devices.value.filter { device ->
            when (statuses[device.key]) {
                DeviceStatus.PLAYING, DeviceStatus.PAUSED, DeviceStatus.PUSHING -> true
                else -> false
            }
        }
    }

    /** 控制指令的目标：优先作用于在播设备，没有则回落到选中设备 */
    fun controlTargets(): List<SonosDevice> =
        activeDevices().ifEmpty { selectedDevices() }
}
