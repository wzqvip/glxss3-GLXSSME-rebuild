package com.wzqvip.glxsscast.cast

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.wzqvip.glxsscast.lcd.LcdProtocol
import com.wzqvip.glxsscast.nav.Maneuver
import com.wzqvip.glxsscast.nav.NavBus
import com.wzqvip.glxsscast.nav.NavFrame
import com.wzqvip.glxsscast.nav.NavHudRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **HUD 叠加层**：画在 AA 投屏帧之上（而不是与 AA 二选一）。
 *
 * ```
 *  抓屏帧 ──CastGeom.map──▶ 640×400 RGBA ──CastHud.compose──▶ USB 帧
 *                                          └ 时间 / lux / 水平仪气泡 / 导航箭头+距离
 * ```
 *
 * ## 为什么用「叠加 Bitmap + 局部混合」而不是每帧整屏重绘
 *
 * 30 fps × 256k 像素的整屏合成会白白吃掉两三个 CPU 核。这里的做法：
 * 1. HUD 只在**数据变化时**才用 Canvas 重画到一张透明 Bitmap（时间 1 Hz、姿态 ~10 Hz、
 *    导航变化时），静态画面下几乎零成本；
 * 2. 每个 HUD 元素记录自己的矩形（[rects]），每帧**只混合这几块小区域**
 *    （约 10k 像素/帧，占整屏的 4%）。
 *
 * ## 遮挡
 *
 * 三种版式（[posLeft]/[posRight]/[posCenter]）默认都把元素贴在**画面边缘**；
 * 在「适应」模式下上下本来就有 20 px 留边，[position] = [POS_CORNERS] 时水平仪画在
 * 左上角、时间画在右上角，导航条贴底 —— 中间的地图/路口基本不被挡。透明度和版式可调，
 * 也可以一键关掉（避免 HUD 遮住 AA 的关键信息）。
 *
 * ## 数据来源
 *
 * | 字段 | 来源 | 后台/息屏时是否还有 |
 * |---|---|---|
 * | 时间 | 本机时钟（服务内自己算） | ✅ |
 * | 导航箭头/距离/路名 | [NavBus.last]（通知监听服务，前台常驻） | ✅ |
 * | 水平仪 pitch/roll/气泡 | [MainActivity] 的 HID 姿态回调 push 进来 | ⚠️ App 进程活着就有（Activity 退到后台不会停 HID） |
 * | lux | 同上（HID 28 B 报告里的 u16） | ⚠️ 同上 |
 *
 * 即：**息屏/后台时导航和时间一定在，水平仪/lux 只要 App 进程还在就也在**；
 * 数据超过 [STALE_MS] 就画成灰色（不会假装有数据）。
 */
object CastHud {

    // ---------------- 版式 ----------------
    /** 四角：水平仪左上、时间+lux 右上、导航贴底居中 */
    const val POS_CORNERS = 0
    /** 顶部条：全部塞进顶部 24 px（「适应」模式下正好落在黑边里，完全不挡 AA） */
    const val POS_TOP = 1
    /** 底部条：全部塞进底部 26 px */
    const val POS_BOTTOM = 2

    private const val STALE_MS = 4000L

    // ---------------- 配置（UI 直接改，下一帧生效） ----------------
    @Volatile
    var enabled = false

    /** 不透明度 20–100（%） */
    @Volatile
    var alphaPct = 88

    @Volatile
    var position = POS_CORNERS

    /** 只显示导航（不画水平仪/lux），偏好设置「极简」时用 */
    @Volatile
    var navOnly = false

    // ---------------- 数据 ----------------
    @Volatile
    private var lux = -1

    @Volatile
    private var pitchDeg = Float.NaN

    @Volatile
    private var rollDeg = 0f

    @Volatile
    private var bubbleX = 0f

    @Volatile
    private var bubbleY = 0f

    @Volatile
    private var poseAtWall = 0L

    /** [MainActivity] 的 HID 姿态回调调用（线程不定，只写字段） */
    fun pushPose(pitch: Float, roll: Float, bx: Float, by: Float, luxValue: Int) {
        pitchDeg = pitch
        rollDeg = roll
        bubbleX = bx.coerceIn(-1f, 1f)
        bubbleY = by.coerceIn(-1f, 1f)
        lux = luxValue
        poseAtWall = System.currentTimeMillis()
    }

    fun clearPose() {
        pitchDeg = Float.NaN
        poseAtWall = 0L
        lux = -1
    }

    // ---------------- 渲染状态（只在 cast 线程读写） ----------------
    private var bmp: Bitmap? = null
    private var canvas: Canvas? = null
    private var scratch = IntArray(0)
    private val rects = ArrayList<Rect>()
    private var drawnKey = ""

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockFmtSec = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // 调色板
    private val WHITE = Color.rgb(245, 249, 255)
    private val GOLD = Color.rgb(255, 197, 61)
    private val MUTED = Color.rgb(150, 168, 190)
    private val GREEN = Color.rgb(76, 217, 100)
    private val PANEL = Color.rgb(8, 12, 18)

    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    /** 给 UI 展示的一句话（HUD 是否开启 + 版本） */
    val layoutName: String
        get() = when (position) {
            POS_TOP -> "顶部条"
            POS_BOTTOM -> "底部条"
            else -> "四角"
        }

    fun reset() {
        drawnKey = ""
        rects.clear()
    }

    /**
     * 把 HUD 混合进 640×400 RGBA（cast 线程每帧调用）。
     */
    fun compose(dst: ByteArray) {
        if (!enabled) return
        if (dst.size != LcdProtocol.PAYLOAD_SIZE) return
        val a = alphaPct.coerceIn(20, 100) / 100f
        val key = key()
        if (key != drawnKey) {
            val ok = try {
                redraw()
                true
            } catch (t: Throwable) {
                false
            }
            drawnKey = if (ok) key else ""
            if (!ok) return
        }
        val src = bmp ?: return
        val w = src.width
        val h = src.height
        if (scratch.size < w * h) scratch = IntArray(w * h)
        val px = scratch
        val rowBytes = w * 4

        for (i in rects.indices) {
            val r = rects[i]
            val rw = r.width()
            val rh = r.height()
            if (rw <= 0 || rh <= 0) continue
            // 只取这一块（getPixels 的 stride 必须是 w，所以传 w 让它在整行 offset 下工作）
            src.getPixels(px, 0, w, r.left, r.top, rw, rh)
            for (yy in 0 until rh) {
                val dRow = (r.top + yy) * rowBytes + r.left * 4
                val sRow = yy * w
                for (xx in 0 until rw) {
                    val argb = px[sRow + xx]
                    val sa = (argb ushr 24) and 0xFF
                    if (sa == 0) continue
                    val o = dRow + xx * 4
                    val k = sa * a / 255f
                    val ik = 1f - k
                    dst[o] = ((argb ushr 16 and 0xFF) * k + (dst[o].toInt() and 0xFF) * ik).toInt().toByte()
                    dst[o + 1] = ((argb ushr 8 and 0xFF) * k + (dst[o + 1].toInt() and 0xFF) * ik).toInt().toByte()
                    dst[o + 2] = ((argb and 0xFF) * k + (dst[o + 2].toInt() and 0xFF) * ik).toInt().toByte()
                    dst[o + 3] = 255.toByte()
                }
            }
        }
    }

    /** 缓存键：任何会影响画面内容的东西都在里面；不变就不重画 Bitmap。 */
    private fun key(): String {
        val frame = NavBus.last.value
        val nav = if (frame == null) "-" else buildString {
            append(frame.maneuver.key)
            append('|').append(frame.distanceMeters)
            append('|').append(frame.road)
            append('|').append(frame.label)
            append('|').append(frame.isFresh(30_000L))
        }
        val now = System.currentTimeMillis()
        val stale = pitchDeg.isNaN() || now - poseAtWall > STALE_MS
        return buildString {
            // 时钟带秒：1 Hz 重画一次 Bitmap，静态画面下成本可忽略
            append(clockFmtSec.format(Date(now)))
            append('|').append(position).append('|').append(navOnly)
            append('|').append(if (stale) "s" else "${(bubbleX * 20).toInt()},${(bubbleY * 20).toInt()}")
            append('|').append(lux)
            append('|').append(nav)
        }
    }

    // ------------------------------------------------------------------
    // 重画（只在数据变化时）
    // ------------------------------------------------------------------
    private fun redraw() {
        val b = bmp ?: Bitmap.createBitmap(LcdProtocol.WIDTH, LcdProtocol.HEIGHT, Bitmap.Config.ARGB_8888)
            .also { bmp = it }
        val c = canvas ?: Canvas(b).also { canvas = it }
        b.eraseColor(Color.TRANSPARENT)
        rects.clear()

        val now = System.currentTimeMillis()
        val poseOk = !pitchDeg.isNaN() && now - poseAtWall <= STALE_MS
        when (position) {
            POS_TOP -> drawStrip(c, 0, 24f, poseOk, navOnly)
            POS_BOTTOM -> drawStrip(c, 1, 26f, poseOk, navOnly)
            else -> drawCorners(c, poseOk, navOnly)
        }
    }

    /** 四角版式：水平仪左上、时间/lux 右上、导航贴底居中 */
    private fun drawCorners(c: Canvas, poseOk: Boolean, navOnly: Boolean) {
        if (!navOnly) {
            // ---- 水平仪 ----
            val lv = Rect(8, 8, 8 + 92, 8 + 92)
            panel(c, lv)
            rects.add(lv)
            drawLevel(c, lv, poseOk)

            // ---- 时间 + lux ----
            val tr = Rect(LcdProtocol.WIDTH - 8 - 132, 8, LcdProtocol.WIDTH - 8, 8 + 56)
            panel(c, tr)
            rects.add(tr)
            drawClock(c, tr, 22f, 30f)
            val luxText = if (lux >= 0) "$lux lx" else "-- lx"
            text(c, luxText, tr.left + 60f, tr.top + 50f, 16f, MUTED, Paint.Align.LEFT, regular)
        }

        // ---- 导航 ----
        val frame = NavBus.last.value
        if (frame != null && frame.isFresh(30_000L)) {
            val nr = Rect(8, LcdProtocol.HEIGHT - 8 - 46, LcdProtocol.WIDTH - 8, LcdProtocol.HEIGHT - 8)
            panel(c, nr)
            rects.add(nr)
            drawNavBar(c, nr, frame)
        }
    }

    /**
     * 条带版式：顶部（[atTop] = true, y=0..h）或底部（y=400-h..400）。
     * 「适应」模式下 640×360 的画面上下各有 20 px 黑边，顶部条正好落在黑边里 → 零遮挡。
     */
    private fun drawStrip(c: Canvas, which: Int, h: Float, poseOk: Boolean, navOnly: Boolean) {
        val top = if (which == 0) 0f else LcdProtocol.HEIGHT - h
        val r = Rect(0, top.toInt(), LcdProtocol.WIDTH, (top + h).toInt())
        panel(c, r)
        rects.add(r)

        var x = 10f
        val base = top + h * 0.70f

        // 时间
        val clock = clockFmt.format(Date(System.currentTimeMillis()))
        text(c, clock, x, base, 17f, WHITE, Paint.Align.LEFT, medium)
        x += measure(clock, 17f, medium) + 14f

        if (!navOnly) {
            // lux
            if (lux >= 0) {
                val s = "$lux lx"
                text(c, s, x, base, 14f, MUTED, Paint.Align.LEFT, regular)
                x += measure(s, 14f, regular) + 14f
            }
            // 水平仪：小圆 + 气泡
            val cx = x + 12f
            val cy = top + h / 2f
            drawLevelMini(c, cx, cy, 11f, poseOk)
            x += 30f
        }

        // 导航：右侧
        val frame = NavBus.last.value
        if (frame != null && frame.isFresh(30_000L)) {
            val dist = when {
                frame.maneuver == Maneuver.ARRIVE -> "已到达"
                frame.distanceMeters != null && frame.distanceMeters < 15.0 -> "现在"
                else -> NavFrame.distanceText(frame.distanceMeters)
            }
            val right = LcdProtocol.WIDTH - 10f
            val tw = measure(dist, 18f, bold)
            val arrowCx = right - tw - 18f
            text(c, dist, right, base, 18f, GOLD, Paint.Align.RIGHT, bold)
            val arrowCy = top + h / 2f
            NavHudRenderer.drawManeuver(
                c, frame.maneuver, arrowCx, arrowCy, 9f,
                if (frame.maneuver == Maneuver.ARRIVE) GREEN else WHITE
            )
        }
    }

    /** 水平仪：外圈 + 十字 + 气泡（复用 MainActivity 的视觉语义：气泡偏向高的一侧） */
    private fun drawLevel(c: Canvas, r: Rect, poseOk: Boolean) {
        val cx = r.centerX().toFloat()
        val cy = r.centerY().toFloat()
        val rad = minOf(r.width(), r.height()) / 2f - 8f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = Color.rgb(70, 90, 114)
        c.drawCircle(cx, cy, rad, p)
        p.strokeWidth = 1f
        p.color = Color.rgb(46, 60, 78)
        c.drawLine(cx - rad, cy, cx + rad, cy, p)
        c.drawLine(cx, cy - rad, cx, cy + rad, p)

        val color = if (poseOk) GOLD else MUTED
        val bx = cx + (if (poseOk) bubbleX else 0f) * rad * 0.62f
        val by = cy + (if (poseOk) bubbleY else 0f) * rad * 0.62f
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = color
        c.drawCircle(bx, by, 8f, dot)
        if (poseOk) {
            val ang = String.format(Locale.US, "%+.1f°", pitchDeg)
            text(c, ang, cx, r.bottom - 6f, 13f, color, Paint.Align.CENTER, medium)
            text(
                c, String.format(Locale.US, "%+.1f°", rollDeg),
                cx, r.top + 18f, 13f, MUTED, Paint.Align.CENTER, regular
            )
        }
    }

    private fun drawLevelMini(c: Canvas, cx: Float, cy: Float, rad: Float, poseOk: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        p.color = Color.rgb(70, 90, 114)
        c.drawCircle(cx, cy, rad, p)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = if (poseOk) GOLD else MUTED
        val bx = cx + (if (poseOk) bubbleX else 0f) * rad * 0.6f
        val by = cy + (if (poseOk) bubbleY else 0f) * rad * 0.6f
        c.drawCircle(bx, by, 3.5f, dot)
    }

    private fun drawClock(c: Canvas, r: Rect, size: Float, baseline: Float) {
        text(c, clockFmtSec.format(Date(System.currentTimeMillis())),
            r.left + 12f, r.top + baseline, size, WHITE, Paint.Align.LEFT, medium)
    }

    /** 底部导航条：箭头 + 距离 + 路名 */
    private fun drawNavBar(c: Canvas, r: Rect, frame: NavFrame) {
        val cy = r.centerY().toFloat()
        NavHudRenderer.drawManeuver(
            c, frame.maneuver, r.left + 26f, cy, 17f,
            if (frame.maneuver == Maneuver.ARRIVE) GREEN else WHITE
        )
        val dist = when {
            frame.maneuver == Maneuver.ARRIVE -> "已到达"
            frame.distanceMeters != null && frame.distanceMeters < 15.0 -> "现在"
            else -> NavFrame.distanceText(frame.distanceMeters)
        }
        text(c, dist, r.left + 52f, cy + 8f, 22f, GOLD, Paint.Align.LEFT, bold)
        val label = frame.road.ifEmpty { frame.label }.ifEmpty { frame.maneuver.zh }
        if (label.isNotEmpty()) {
            text(c, label, r.left + 130f, cy + 7f, 17f, WHITE, Paint.Align.LEFT, regular)
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------
    private fun panel(c: Canvas, r: Rect) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = PANEL
        p.alpha = 150
        val rf = RectF(r)
        c.drawRoundRect(rf, 8f, 8f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.alpha = 70
        p.color = Color.rgb(120, 140, 165)
        c.drawRoundRect(rf, 8f, 8f, p)
    }

    private fun text(
        c: Canvas, s: String, x: Float, baseline: Float, size: Float,
        color: Int, align: Paint.Align, tf: Typeface,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = size
        p.color = color
        p.typeface = tf
        p.textAlign = align
        // 描边：AA 画面可能是浅色地图，白字加一圈黑边才读得清
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = Color.argb(150, 0, 0, 0)
        c.drawText(s, x, baseline, p)
        p.style = Paint.Style.FILL
        p.color = color
        c.drawText(s, x, baseline, p)
    }

    private fun measure(s: String, size: Float, tf: Typeface): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = size
        p.typeface = tf
        return p.measureText(s)
    }
}
