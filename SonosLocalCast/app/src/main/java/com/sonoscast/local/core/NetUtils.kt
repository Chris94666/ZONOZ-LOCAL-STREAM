package com.sonoscast.local.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    /**
     * 取手机在局域网中的 IPv4 地址。
     *
     * 顺序很重要：
     * 1. 优先枚举 `wlan*` 网卡 —— 用户开了 VPN 时 `ConnectivityManager.activeNetwork`
     *    往往是 tun0（10.x / 172.x），把那个地址交给 Sonos 必然 701。
     * 2. 再退到 activeNetwork 的 LinkProperties。
     * 3. 最后兜底任意非回环 IPv4。
     */
    fun currentLanIp(context: Context): String? {
        byInterface { name -> name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth") }
            ?.let { return it }

        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching
            val network = cm.activeNetwork ?: return@runCatching
            val lp = cm.getLinkProperties(network) ?: return@runCatching
            lp.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
                ?.let { return it }
        }

        return byInterface { true }
    }

    private fun byInterface(predicate: (String) -> Boolean): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        interfaces
            .asSequence()
            .filter { nif -> runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false) }
            .filter { nif -> nif.name?.let(predicate) == true }
            .firstNotNullOfOrNull { nif ->
                nif.inetAddresses
                    .toList()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress
            }
    }.getOrNull()

    fun isWifiConnected(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    /**
     * SSDP 需要多播锁才能稳定收到 239.255.255.250 的回包。
     * M-SEARCH 的响应虽然是单播，但部分路由器/固件会以组播方式回，拿锁更保险。
     * 调用方必须负责 release()。
     */
    fun acquireMulticastLock(context: Context, tag: String): WifiManager.MulticastLock? = runCatching {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        wm.createMulticastLock(tag).apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()
}
