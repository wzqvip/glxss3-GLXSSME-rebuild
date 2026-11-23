package com.wzqvip.glxsscast.nav

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.wzqvip.glxsscast.diag.Oplog
import com.wzqvip.glxsscast.settings.Prefs
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 导航 HUD 的运行时总线：解析结果 + 1 Hz UDP 发送 + 调试状态。
 *
 * 数据流：
 * ```
 * Google Maps 通知 --[NavListenerService]--> NavParser --> NavBus.last
 *                                                       └--> 每 1 s UDP JSON --> Pi:9999
 * ```
 * 「模拟一条通知」调试按钮直接调 [simulate]，走的是同一条解析/发送链路（只是
 * 通知对象是本地造的），所以不开车也能验证 “解析 → JSON → UDP” 全链路。
 */
object NavBus {
    /** 系统里“通知使用权”是否已授权（页面每次 onResume 刷新）。 */
    val listenerEnabled = mutableStateOf(false)
    /** NotificationListenerService 是否真的连上了（系统绑定成功）。 */
    val listenerConnected = mutableStateOf(false)
    /** 当前是否在发导航包（收到 Maps 导航通知为 true，通知被移除/超时为 false）。 */
    val active = mutableStateOf(false)
    val last = mutableStateOf<NavFrame?>(null)
    val lastJson = mutableStateOf("")
    val sent = mutableStateOf(0)
    val error = mutableStateOf("")
    val lastSentAt = mutableStateOf(0L)
    /** 图标相似度自检结果（调试按钮）。 */
    val iconSelfTest = mutableStateOf("")

    private const val FRESH_MS = 30_000L
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "glxss-nav-udp").apply { isDaemon = true }
    }
    private val tickerStarted = AtomicBoolean(false)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var appContext: Context? = null
    private var socket: DatagramSocket? = null
    private var lastTarget = ""

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (t: Throwable) {
                Oplog.logf("NAV", "tick 异常 %s: %s", t.javaClass.simpleName, t.message)
            } finally {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    fun ensureTicker(context: Context) {
        appContext = context.applicationContext
        if (tickerStarted.compareAndSet(false, true)) {
            handler.post(ticker)
            Oplog.logf("NAV", "1 Hz 发包定时器启动")
        }
    }

    /** 收到一条解析好的导航帧：立即发一包，之后由定时器每秒重发。 */
    fun onFrame(frame: NavFrame, context: Context? = null) {
        context?.let { ensureTicker(it) }
        last.value = frame
        active.value = true
        Oplog.logf(
            "NAV", "解析 %s maneuver=%s distance=%s eta=%s ← %s",
            frame.label, frame.maneuver.key,
            NavFrame.distanceText(frame.distanceMeters),
            NavFrame.etaText(frame.etaSeconds), frame.packageName
        )
        send(frame)
    }

    /** Maps 导航通知被移除（结束导航）→ 停止发包。 */
    fun onNavigationEnded() {
        if (active.value) Oplog.logf("NAV", "导航通知移除，停止发包")
        active.value = false
    }

    /** 每秒调用一次：只发 30 s 内的新鲜帧。 */
    fun tick() {
        if (!active.value) return
        val frame = last.value ?: return
        if (!frame.isFresh(FRESH_MS)) {
            active.value = false
            Oplog.logf("NAV", "导航包已过期（>%d s），停止发包", FRESH_MS / 1000)
            return
        }
        send(frame)
    }

    /** 立即发一包（不看 active；调试按钮用）。 */
    fun send(frame: NavFrame? = last.value) {
        val ctx = appContext ?: return
        if (frame == null) return
        val prefs = Prefs.get(ctx)
        if (!prefs.navSend) return
        val host = prefs.navHost.trim().ifEmpty { Prefs.DEFAULT_NAV_HOST }
        val port = prefs.navPort.coerceIn(1, 65535)
        val payload = frame.toJson().toByteArray(Charsets.UTF_8)
        executor.execute {
            try {
                val target = "$host:$port"
                if (socket == null || socket?.isClosed == true || target != lastTarget) {
                    socket?.close()
                    socket = DatagramSocket()
                    lastTarget = target
                }
                val address = InetAddress.getByName(host)
                socket?.send(DatagramPacket(payload, payload.size, address, port))
                val now = System.currentTimeMillis()
                handler.post {
                    sent.value += 1
                    lastSentAt.value = now
                    lastJson.value = String(payload, Charsets.UTF_8)
                    error.value = ""
                }
            } catch (t: Throwable) {
                socket?.close()
                socket = null
                lastTarget = ""
                val message = "${t.javaClass.simpleName}: ${t.message}"
                handler.post { error.value = message }
                Oplog.logf("NAV", "UDP 发送失败 → %s:%d %s", host, port, message)
            }
        }
    }

    /** 配置改了（IP/端口/开关）后丢弃旧 socket，下包重新解析地址。 */
    fun resetSocket() {
        executor.execute {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
            socket = null
            lastTarget = ""
        }
    }

    /** 调试：造一条假通知，走完整解析链路并立即发包。 */
    fun simulate(
        context: Context,
        maneuver: Maneuver = Maneuver.RIGHT,
        distanceMeters: Double? = 500.0,
        etaSeconds: Long? = 120,
        remainMeters: Double? = 2400.0,
    ): NavFrame {
        ensureTicker(context)
        val frame = NavParser.simulate(
            maneuver, distanceMeters = distanceMeters,
            etaSeconds = etaSeconds, remainMeters = remainMeters,
        )
        onFrame(frame, context)
        return frame
    }

    fun runIconSelfTest() {
        iconSelfTest.value = NavParser.IconMatcher.selfTest()
    }

    fun refreshPermission(context: Context) {
        listenerEnabled.value = NavAccess.isListenerEnabled(context)
    }

    /** 清空调试状态（不影响系统通知授权）。 */
    fun clearDebug() {
        last.value = null
        lastJson.value = ""
        sent.value = 0
        error.value = ""
        lastSentAt.value = 0L
        active.value = false
    }
}

/** 通知使用权工具：判断是否已授权、跳到系统设置页。 */
object NavAccess {
    private const val ENABLED_LISTENERS = "enabled_notification_listeners"

    fun isListenerEnabled(context: Context): Boolean {
        return try {
            androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName)
        } catch (t: Throwable) {
            // 旧 ROM 上兼容：直接读 Settings.Secure。
            try {
                val flat = android.provider.Settings.Secure.getString(
                    context.contentResolver, ENABLED_LISTENERS
                ) ?: return false
                flat.contains(context.packageName)
            } catch (_: Throwable) {
                false
            }
        }
    }
}
