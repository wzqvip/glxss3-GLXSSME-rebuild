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
        Oplog.logf("NAV", "NotificationListenerService 已连接（可读地图通知）")
    }

    override fun onListenerDisconnected() {
        NavBus.listenerConnected.value = false
        Oplog.logf("NAV", "NotificationListenerService 断开（通知使用权可能被关）")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName != NavParser.MAPS_PACKAGE) return
        val frame = try {
            NavParser.parseNotification(sbn.notification, sbn.packageName, this)
        } catch (t: Throwable) {
            Oplog.logf("NAV", "解析地图通知失败 %s: %s", t.javaClass.simpleName, t.message)
            null
        } ?: return
        NavBus.onFrame(frame, this)
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
