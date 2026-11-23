package com.wzqvip.glxsscast.nav

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.wzqvip.glxsscast.cast.CastService
import com.wzqvip.glxsscast.diag.Oplog
import com.wzqvip.glxsscast.lcd.LcdControl
import com.wzqvip.glxsscast.lcd.LcdProtocol
import com.wzqvip.glxsscast.settings.Prefs
import com.wzqvip.glxsscast.usb.UsbLink
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 「导航 HUD → 眼镜」**直推**（方案 A：不经过 Pi / UDP）。
 *
 * ```
 * NavListenerService → NavParser → NavBus.onFrame ─┬→ 1 Hz 定时器（这里）
 *                                                └→ 数据变化时立即补一帧
 *   → NavHudRenderer 渲染 640×400 RGBA
 *   → 16 B 头 + 1 MB bulk OUT（UsbLink.sendFrame，和投屏共用同一个串行闸门）
 * ```
 *
 * 约束（都是真机踩过的坑）：
 * - **单帧串行**：一个后台线程按 1 s 周期跑，数据变化只是往同一个队列里塞一帧，
 *   绝不会两个线程同时写 bulk OUT；[com.wzqvip.glxsscast.usb.UsbGate] 再对
 *   控制请求/帧做进程级互斥。
 * - **不抢投屏**：CastService 在跑时暂停（投屏也是 1 MB×30 fps，硬抢会把帧切碎）。
 * - **先开屏**：眼镜屏会被固件 30 s 自动关掉，只发帧不会点亮；每轮开始先
 *   `setScreenOn(true)` + `setDisplayMode(2)`（与 [com.wzqvip.glxsscast.MainActivity] 的
 *   「开屏」按钮同一组命令）。
 * - **连接状态**：`UsbLink` 单例；断线只暂停，不重连（重连由 Activity 的自动重连负责）。
 *
 * 没有眼镜时，[capturePng] 仍能把「解析 → 渲染」的结果存成 PNG
 * （`getExternalFilesDir/nav-hud/`），用来看版式/箭头是否正确。
 */
object NavHudPusher {
    /** 数据超过这个年龄就标「信号过期」（Maps 通知会随导航结束消失）。 */
    const val FRESH_MS = 30_000L
    private const val TICK_MS = 1000L

    // ---- 页面状态（Compose 读；只在主线程写） ----
    val running = mutableStateOf(false)
    val enabled = mutableStateOf(true)
    val pushes = mutableStateOf(0)
    val lastPushAt = mutableStateOf(0L)
    val lastPushMs = mutableStateOf(0L)
    val lastError = mutableStateOf("")
    val status = mutableStateOf("未启动")
    val lastPngPath = mutableStateOf("")
    val lastHudLabel = mutableStateOf("")

    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var usb: UsbLink? = null
    private var sched: ScheduledExecutorService? = null
    private var screenInit = false

    // 复用缓冲：每秒 1 MB payload + 1 MB 帧缓冲 + 1 MB Bitmap，不产生 GC 抖动
    private var rgba = ByteArray(LcdProtocol.PAYLOAD_SIZE)
    private var frameBuf = ByteArray(LcdProtocol.HEADER_SIZE + LcdProtocol.PAYLOAD_SIZE)
    private var bmp: Bitmap? = null

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------
    /** App 启动/页面进来时按持久化开关同步一次。 */
    fun syncFromPrefs(context: Context) {
        val on = Prefs.get(context).navDirect
        enabled.value = on
        if (on) start(context) else stop()
    }

    /** 页面上的「导航 HUD → 眼镜」开关。 */
    fun setEnabled(context: Context, on: Boolean) {
        Prefs.get(context).navDirect = on
        enabled.value = on
        if (on) start(context) else stop()
    }

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        ctx = app
        usb = UsbLink.get(app)
        NavBus.ensureTicker(app)
        enabled.value = Prefs.get(app).navDirect
        if (running.value) return
        running.value = true
        screenInit = false
        NavBus.frameListener = { frame -> onNewFrame(frame) }
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "nav-hud-push").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }
        sched = executor
        // 固定延迟而不是固定频率：万一一次 bulk OUT 超过 1 s，也不会堆帧
        executor.scheduleWithFixedDelay({ tick() }, 0L, TICK_MS, TimeUnit.MILLISECONDS)
        postStatus("导航 HUD 已启动（等待导航…）")
        Oplog.logf("NAV", "眼镜直推启动：1 Hz + 数据变化立即推")
    }

    @Synchronized
    fun stop() {
        running.value = false
        NavBus.frameListener = null
        sched?.shutdownNow()
        sched = null
        screenInit = false
        postStatus("导航 HUD 已停止")
        Oplog.logf("NAV", "眼镜直推停止")
    }

    /** 解析出新帧：立即补一帧（不等 1 Hz 节拍）。 */
    private fun onNewFrame(frame: NavFrame) {
        if (!running.value) return
        sched?.execute {
            if (running.value) {
                try {
                    push("新帧")
                } catch (t: Throwable) {
                    Oplog.logf("NAV", "直推异常 %s: %s", t.javaClass.simpleName, t.message)
                }
            }
        }
    }

    /** 调试入口：确保已启动并马上推一帧。 */
    fun pushOnce(context: Context) {
        if (!running.value) start(context)
        sched?.execute {
            if (running.value) {
                try {
                    push("手动")
                } catch (t: Throwable) {
                    Oplog.logf("NAV", "手动推送异常 %s: %s", t.javaClass.simpleName, t.message)
                }
            }
        }
    }

    private fun tick() {
        try {
            if (running.value) push("节拍")
        } catch (t: Throwable) {
            Oplog.logf("NAV", "直推节拍异常 %s: %s", t.javaClass.simpleName, t.message)
        }
    }

    // ------------------------------------------------------------------
    // 单帧：渲染 → 16 B 头 → bulk OUT
    // ------------------------------------------------------------------
    private fun push(reason: String) {
        val c = ctx ?: return
        val link = usb ?: UsbLink.get(c).also { usb = it }
        if (!Prefs.get(c).navDirect) {
            postStatus("直推已关闭")
            return
        }
        if (!link.isOpen) {
            screenInit = false
            postStatus("眼镜未连接（插上眼镜 → 点「连接眼镜」）")
            return
        }
        if (CastService.running) {
            postStatus("投屏中：导航 HUD 暂停（先停止投屏）")
            return
        }
        if (!screenInit) {
            try {
                val conn = link.connection
                if (conn != null) {
                    val switchOk = LcdControl.setScreenOn(conn, true)
                    val modeOk = LcdControl.setDisplayMode(conn, 2)
                    screenInit = switchOk || modeOk
                    Oplog.logf("NAV", "HUD 开屏 switch=%b displayMode2=%b", switchOk, modeOk)
                }
            } catch (t: Throwable) {
                Oplog.logf("NAV", "HUD 开屏异常 %s: %s", t.javaClass.simpleName, t.message)
            }
        }

        val frame = NavBus.last.value
        val stale = frame != null && !frame.isFresh(FRESH_MS)
        val snap = snapshot(c)

        val t0 = SystemClock.elapsedRealtime()
        val rendered = NavHudRenderer.render(snap, bmp)
        bmp = rendered
        rgba = NavHudRenderer.toRgba(rendered, rgba)
        val payload = rgba
        val frameBytes = frameBytes(payload)
        val ok = try {
            link.sendFrame(frameBytes)
        } catch (t: Throwable) {            Oplog.logf("NAV", "HUD sendFrame 异常 %s: %s", t.javaClass.simpleName, t.message)
            false
        }
        val dt = SystemClock.elapsedRealtime() - t0
        val label = frame?.label ?: "等待导航…"
        main.post {
            if (ok) pushes.value += 1
            lastPushAt.value = System.currentTimeMillis()
            lastPushMs.value = dt
            lastHudLabel.value = label
            if (ok) {
                lastError.value = ""
            } else {
                lastError.value = "bulk OUT 失败（见 oplog；眼镜可能掉线）"
            }
            status.value = statusText(frame, stale, ok, reason)
        }
        if (!ok) Oplog.logf("NAV", "HUD 推送失败 reason=%s", reason)
    }

    /** 复用 [frameBuf]：16 B 头 + payload（头格式见 [LcdProtocol]）。 */
    private fun frameBytes(payload: ByteArray): ByteArray {
        val fb = frameBuf
        if (fb.size != LcdProtocol.HEADER_SIZE + payload.size) {
            frameBuf = ByteArray(LcdProtocol.HEADER_SIZE + payload.size)
        }
        val buf = frameBuf
        for (i in 0 until LcdProtocol.HEADER_SIZE) buf[i] = 0
        buf[0] = 1 // format
        val total = buf.size
        buf[4] = (total and 0xFF).toByte()
        buf[5] = ((total ushr 8) and 0xFF).toByte()
        buf[6] = ((total ushr 16) and 0xFF).toByte()
        buf[7] = ((total ushr 24) and 0xFF).toByte()
        System.arraycopy(payload, 0, buf, LcdProtocol.HEADER_SIZE, payload.size)
        return buf
    }

    // ------------------------------------------------------------------
    // 调试：抓一帧 PNG（不需要眼镜）
    // ------------------------------------------------------------------
    /**
     * 把当前（或指定）导航帧渲染成 PNG，返回绝对路径。
     *
     * 位置：`getExternalFilesDir/nav-hud/hud-<时间戳>.png`，同时覆盖 `last.png`，
     * 并把解析字段写到 `hud-latest.json`。可在任意后台线程调用。
     */
    fun capturePng(
        context: Context,
        overrideFrame: NavFrame? = null,
        note: String = "",
        waiting: Boolean = false,
    ): String {
        // waiting=true：强制画「等待导航…」（不取 NavBus.last，也不看 overrideFrame）
        val snap = if (waiting) {
            NavHudRenderer.Snapshot(
                frame = null,
                connection = connectionText(null, stale = false),
                hint = hintText(context),
            )
        } else {
            snapshot(context, overrideFrame)
        }
        val frame = snap.frame
        val bitmap = NavHudRenderer.render(snap, null)
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "nav-hud")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val file = File(dir, "hud-$stamp.png")
        val ok = try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        } catch (t: Throwable) {
            Oplog.logf("NAV", "HUD PNG 写入失败 %s: %s", t.javaClass.simpleName, t.message)
            false
        } finally {
            bitmap.recycle()
        }
        if (!ok) return ""
        try {
            file.copyTo(File(dir, "last.png"), overwrite = true)
            File(dir, "hud-latest.json").writeText(
                buildJson(frame, snap, note, file.name), Charsets.UTF_8
            )
        } catch (t: Throwable) {
            Oplog.logf("NAV", "HUD 抓帧附带文件失败 %s: %s", t.javaClass.simpleName, t.message)
        }
        val path = file.absolutePath
        main.post {
            lastPngPath.value = path
            lastHudLabel.value = frame?.label ?: "等待导航…"
        }
        Oplog.logf(
            "NAV", "HUD PNG → %s（%d B, %s）",
            path, file.length(), note.ifEmpty { "-" }
        )
        return path
    }

    private fun buildJson(
        frame: NavFrame?,
        snapshot: NavHudRenderer.Snapshot,
        note: String,
        fileName: String,
    ): String = buildString {
        append("{\n")
        append("  \"png\": ").append('"').append(fileName).append('"').append(",\n")
        append("  \"note\": ").append('"').append(note.replace('"', '\'')).append('"').append(",\n")
        append("  \"connection\": ").append('"').append(snapshot.connection).append('"').append(",\n")
        append("  \"hint\": ").append('"').append(snapshot.hint.replace('"', '\'')).append('"').append(",\n")
        if (frame != null) {
            append("  \"label\": ").append('"').append(frame.label.replace('"', '\'')).append('"').append(",\n")
            append("  \"road\": ").append('"').append(frame.road.replace('"', '\'')).append('"').append(",\n")
            append("  \"maneuver\": ").append('"').append(frame.maneuver.key).append('"').append(",\n")
            append("  \"distance_m\": ").append(frame.distanceMeters ?: -1).append(",\n")
            append("  \"eta_s\": ").append(frame.etaSeconds ?: -1).append(",\n")
            append("  \"remain_m\": ").append(frame.remainMeters ?: -1).append(",\n")
            append("  \"title\": ").append('"').append(frame.title.replace('"', '\'')).append('"').append(",\n")
            append("  \"text\": ").append('"').append(frame.text.replace('"', '\'')).append('"').append(",\n")
            append("  \"sub_text\": ").append('"').append(frame.subText.replace('"', '\'')).append('"').append(",\n")
            append("  \"icon_guess\": ").append('"').append(frame.iconGuess.key).append('"').append(",\n")
            append("  \"icon_score\": ").append(frame.iconScore).append(",\n")
            append("  \"simulated\": ").append(frame.simulated).append(",\n")
            append("  \"age_ms\": ").append(frame.ageMs).append(",\n")
            append("  \"json\": ").append('"').append(frame.toJson().replace('"', '\'')).append('"').append(",\n")
        }
        append("  \"pushes\": ").append(pushes.value).append(",\n")
        append("  \"status\": ").append('"').append(status.value.replace('"', '\'')).append('"').append("\n")
        append("}\n")
    }

    // ------------------------------------------------------------------
    // 文本
    // ------------------------------------------------------------------
    /** 当前应该显示在眼镜上的那一帧（UI 预览与直推共用，保证像素一致）。 */
    fun snapshot(
        context: Context,
        overrideFrame: NavFrame? = null,
    ): NavHudRenderer.Snapshot {
        val frame = overrideFrame ?: NavBus.last.value
        val stale = frame != null && !frame.isFresh(FRESH_MS)
        return NavHudRenderer.Snapshot(
            frame = frame,
            connection = connectionText(frame, stale),
            hint = hintText(context),
        )
    }

    private fun connectionText(frame: NavFrame?, stale: Boolean): String {
        val link = usb
        return when {
            link == null || !link.isOpen -> "眼镜未连接"
            CastService.running -> "投屏占用"
            stale -> "信号过期"
            frame == null -> "等待导航"
            else -> "眼镜直连"
        }
    }

    private fun hintText(context: Context): String {
        val perm = NavAccess.isListenerEnabled(context)
        val open = usb?.isOpen == true
        return listOf(
            if (perm) "通知使用权已开启" else "通知使用权未开启",
            if (open) "眼镜已连接" else "眼镜未连接",
        ).joinToString(" · ")
    }

    private fun statusText(
        frame: NavFrame?,
        stale: Boolean,
        ok: Boolean,
        reason: String,
    ): String = buildString {
        if (!ok) append("推送失败 · ")
        when {
            frame == null -> append("等待导航…")
            stale -> append("信号过期（${(frame.ageMs / 1000).coerceAtLeast(1)} s 前）")
            else -> append("导航中 · ").append(frame.maneuver.zh.ifEmpty { "指示" })
                .append(' ').append(NavFrame.distanceText(frame.distanceMeters))
        }
        append(" · 已推 ").append(pushes.value).append(" 帧")
        if (reason == "新帧") append(" · 刚更新")
    }

    private fun postStatus(text: String) {
        main.post { status.value = text }
    }
}
