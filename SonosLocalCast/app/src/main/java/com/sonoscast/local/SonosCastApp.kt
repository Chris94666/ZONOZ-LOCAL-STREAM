package com.sonoscast.local

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.sonoscast.local.core.Favorites
import com.sonoscast.local.core.I18n
import com.sonoscast.local.core.UiScale

class SonosCastApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 语言必须在任何界面组合之前确定：优先用户上次的选择，其次跟随系统 Locale
        I18n.init(this)
        // 文字大小也要在任何界面组合之前定下来
        UiScale.init(this)
        // 收藏夹也跟着恢复
        Favorites.init(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "sonoscast_service"
    }
}
