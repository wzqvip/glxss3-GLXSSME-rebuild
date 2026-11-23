package com.wzqvip.glxsscast.nav

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.wzqvip.glxsscast.diag.Oplog

/**
 * 读 Google 地图的导航通知（`com.google.android.apps.maps`）。
 *
 * 只处理地图包；不是导航的通知（附近推荐、通勤提醒等）会被
 * [NavParser.parseNotification] 判掉。解析结果进 [NavBus]，由 1 Hz 定时器
 * 发 UDP 到 Pi。
 *
 * 需要用户在系统里授权「通知使用权」（见 App 的“导航 HUD”页面）。
 */
class NavListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NavBus.listenerConnected.value = true
        NavBus.listenerEnabled.value = true
        NavBus.ensureTicker(this)
        // 进程可能由系统直接拉起（没有 Activity）：按持久化开关恢复「直推眼镜」，
        // 这样插着眼镜、锁屏开车时也能持续刷新 HUD。
        NavHudPusher.syncFromPrefs(this)
        Oplog.logf("NAV", "NotificationListenerService 已连接（可读地图通知）")
        // App/服务刚重启时导航可能已经在跑：系统只推「之后」的通知，这里主动扫一遍
        // 当前通知栏，否则要等 Maps 下一次更新（停车等待时可能几十秒）才出 HUD。
        try {
            val existing = activeNotifications
            var restored = 0
            existing?.forEach { sbn ->
                if (sbn != null && handle(sbn)) restored++
            }
            if (restored > 0) {
                Oplog.logf("NAV", "启动时恢复 %d 条进行中的地图通知", restored)
            }
        } catch (t: Throwable) {
            Oplog.logf(
                "NAV", "activeNotifications 扫描失败 %s: %s",
                t.javaClass.simpleName, t.message
            )
        }
    }

    override fun onListenerDisconnected() {
        NavBus.listenerConnected.value = false
        Oplog.logf("NAV", "NotificationListenerService 断开（通知使用权可能被关）")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        handle(sbn)
    }

    /** 解析一条通知并交给 [NavBus]；返回是否是一条有效的导航通知。 */
    private fun handle(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != NavParser.MAPS_PACKAGE) return false
        val frame = try {
            NavParser.parseNotification(sbn.notification, sbn.packageName, this)
        } catch (t: Throwable) {
            Oplog.logf("NAV", "解析地图通知失败 %s: %s", t.javaClass.simpleName, t.message)
            null
        } ?: return false
        NavBus.onFrame(frame, this)
        return true
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        if (sbn == null || sbn.packageName != NavParser.MAPS_PACKAGE) return
        val wasNavigation = try {
            sbn.notification?.category == Notification.CATEGORY_NAVIGATION ||
                NavParser.parseNotification(sbn.notification, sbn.packageName, this) != null
        } catch (t: Throwable) {
            false
        }
        if (wasNavigation) NavBus.onNavigationEnded()
    }

    companion object {
        /** 跳到「通知使用权」设置页；旧系统回退到列表页。 */
        fun openSettings(context: Context) {
            val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, NavListenerService::class.java)
                            .flattenToString()
                    )
                }
            } else {
                null
            }
            val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            for (intent in listOfNotNull(detail, list)) {
                val ok = try {
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        true
                    } else {
                        false
                    }
                } catch (t: Throwable) {
                    false
                }
                if (ok) return
            }
            Oplog.logf("NAV", "打不开通知使用权设置页")
        }
    }
}
