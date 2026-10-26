package com.sonoscast.local.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sonoscast.local.core.LogBus

/**
 * 续播闹钟的落点。
 *
 * 为什么需要一个 BroadcastReceiver 而不是直接 PendingIntent.getService：
 * 锁屏 + Doze 下进程可能被冻结甚至回收，只有 `setExactAndAllowWhileIdle` 的闹钟能把
 * 系统叫醒并投递广播。收到后我们再显式拉起前台服务，由它去推下一首。
 *
 * 这里不做任何业务判断（token 校验在 Service 里做），只负责"把人叫醒"。
 */
class AutoAdvanceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AUTO_ADVANCE) return
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        LogBus.i("续播闹钟唤醒服务（token=$token）")

        val forward = Intent(context, SonosCastService::class.java)
            .setAction(ACTION_AUTO_ADVANCE)
            .putExtra(EXTRA_TOKEN, token)
        runCatching {
            ContextCompat.startForegroundService(context, forward)
        }.onFailure {
            LogBus.e("续播闹钟拉起服务失败：${it.javaClass.simpleName} ${it.message}")
        }
    }

    companion object {
        const val ACTION_AUTO_ADVANCE = "com.sonoscast.local.action.AUTO_ADVANCE"
        const val EXTRA_TOKEN = "extra_auto_token"
    }
}
