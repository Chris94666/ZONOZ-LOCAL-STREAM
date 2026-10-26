package com.sonoscast.local.core

/**
 * 把 UPnP / Sonos 的错误码翻译成「人话」。
 *
 * 设计原则：
 * - **不向用户暴露数字码**，701/714/1023 这些只留在日志里给开发者看
 * - 每条都给「发生了什么 + 该怎么办」，而不是一句"操作失败"
 * - 返回的是 [Str] 键而不是字符串，这样切换语言时提示会跟着变
 */
object UpnpErrors {

    /** 错误码 -> 文案键 */
    fun key(code: Int?): Str = when (code) {
        701, 702, 716 -> Str.ERR_REACH          // 拿不到媒体流（716 是路径 404，对用户而言同样是"够不到"）
        714 -> Str.ERR_FORMAT                    // 格式不被接受
        402 -> Str.ERR_BADREQ                    // 参数/元数据有问题
        800, null -> Str.ERR_OFFLINE             // 根本连不上
        1023 -> Str.ERR_SUB                      // Sonos 私有：从设备
        else -> Str.ERR_UNKNOWN
    }

    /** 只在日志里用，给开发者看的原始描述 */
    fun describe(code: Int?): String = when (code) {
        401 -> "Invalid Action"
        402 -> "Invalid Args"
        501 -> "Action Failed"
        701 -> "Transition not available"
        702 -> "No contents"
        704 -> "Invalid connection reference"
        705 -> "Transport is locked"
        710 -> "Seek mode not supported"
        711 -> "Illegal seek target"
        712 -> "Play speed not supported"
        714 -> "Illegal MIME-Type"
        715 -> "Content busy"
        716 -> "Resource not found"
        718 -> "Invalid InstanceID"
        800 -> "Unable to connect"
        1023 -> "Satellite unit (Sonos private)"
        null -> "no UPnP error code"
        else -> "UPnP $code"
    }
}
