package com.wzqvip.glxsscast.nav

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.wzqvip.glxsscast.lcd.LcdProtocol
import java.nio.ByteBuffer

/**
 * 导航 HUD 的**手机端渲染**：640×400 RGBA，直接就是 LCD 帧的 payload。
 *
 * 版式（眼镜屏幕是 640×400 的深色 micro-LCD，没有磁力计，所以**不画罗盘/heading**）：
 * ```
 * ┌──────────────────────────────────────────────┐
 * │ 剩余 2 分 · 2.4 km                  ● 眼镜直连 │  ← 顶栏 58 px
 * ├──────────────────────────────────────────────┤
 * │                    ▲                         │
 * │                 大箭头（按机动类型）              │  ← 中心区
 * │                                              │
 * │                 500 m                        │  ← 距离（金色 68 px）
 * ├──────────────────────────────────────────────┤
 * │              右转进入 人民路（完整指令）           │  ← 指令条
 * │                 人民路（路名放大高亮）             │
 * └──────────────────────────────────────────────┘
 * ```
 * 无导航数据时画「等待导航…」。所有绘制都用 Canvas（非 Compose），可以在后台
 * 线程每秒跑一次，输出字节序与 [LcdProtocol] / 投屏一致：R,G,B,A。
 *
 * [LcdProtocol.flip180] 与投屏共用：翻转在 [renderRgba] 里做（PNG 抓图保持逻辑方向）。
 */
object NavHudRenderer {
    const val WIDTH = LcdProtocol.WIDTH
    const val HEIGHT = LcdProtocol.HEIGHT

    /** 一次渲染的输入。 */
    data class Snapshot(
        /** null = 还没有导航数据（画「等待导航…」）。 */
        val frame: NavFrame?,
        /** 顶栏右侧的连接状态，例如「眼镜直连」「眼镜未连接」「投屏占用」。 */
        val connection: String = "",
        /** 无导航数据时底栏的提示（例如「通知使用权已开启 · 插上眼镜」）。 */
        val hint: String = "",
        val now: Long = System.currentTimeMillis(),
    )

    // ---- 调色板（深色底 + 白/黄高对比） ----
    private val BG_TOP = Color.rgb(11, 17, 26)
    private val BG_BOTTOM = Color.rgb(4, 6, 10)
    private val BAR_TOP = Color.rgb(17, 24, 34)
    private val BAR_BOTTOM = Color.rgb(20, 28, 39)
    private val LINE = Color.rgb(38, 52, 68)
    private val WHITE = Color.rgb(245, 249, 255)
    private val GOLD = Color.rgb(255, 197, 61)
    private val MUTED = Color.rgb(139, 158, 184)
    private val DIM = Color.rgb(96, 112, 133)
    private val GREEN = Color.rgb(76, 217, 100)
    private val RED = Color.rgb(255, 92, 95)

    private const val TOP_BAR_H = 58f
    private const val BOTTOM_BAR_Y = 306f
    private const val ARROW_CX = 320f
    private const val ARROW_CY = 148f
    private const val ARROW_HALF = 62f
    private const val DISTANCE_BASELINE = 286f

    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    /**
     * 渲染到 [reuse]（尺寸必须是 640×400；传 null 就新建）。
     * 返回的 Bitmap 不做 180° 翻转，是「人眼正常方向」，抓 PNG 用这个。
     */
    fun render(snapshot: Snapshot, reuse: Bitmap? = null): Bitmap {
        val bmp = if (reuse != null && !reuse.isRecycled &&
            reuse.width == WIDTH && reuse.height == HEIGHT
        ) {
            reuse
        } else {
            Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(bmp)
        paintBackground(canvas)

        val frame = snapshot.frame
        drawTopBar(canvas, frame, snapshot)
        if (frame == null) {
            drawWaiting(canvas, snapshot)
        } else {
            drawNavigation(canvas, frame)
        }
        return bmp
    }

    /**
     * 渲染并转成 LCD payload（1,024,000 B，R,G,B,A）。
     *
     * @param out 复用缓冲（必须 [LcdProtocol.PAYLOAD_SIZE]）；不足时新建返回。
     * @param reuseBitmap 复用 Bitmap，避免每秒分配 1 MB。
     */
    fun renderRgba(snapshot: Snapshot, reuseBitmap: Bitmap?, out: ByteArray?): ByteArray =
        toRgba(render(snapshot, reuseBitmap), out)

    /**
     * Bitmap → LCD payload（复用 [out]；应用 [LcdProtocol.flip180]）。
     *
     * `copyPixelsToBuffer` 对 `ARGB_8888` 拷贝的是**内存原始字节**，在 Android 上
     * 就是 R,G,B,A —— 与投屏帧（ImageReader RGBA_8888 直拷）一致。
     */
    fun toRgba(bitmap: Bitmap, out: ByteArray? = null): ByteArray {
        val buf = if (out != null && out.size == LcdProtocol.PAYLOAD_SIZE) {
            out
        } else {
            ByteArray(LcdProtocol.PAYLOAD_SIZE)
        }
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(buf))
        if (LcdProtocol.flip180) LcdProtocol.flip180InPlace(buf)
        return buf
    }

    // ------------------------------------------------------------------
    // 背景 / 顶栏 / 底栏
    // ------------------------------------------------------------------
    private fun paintBackground(canvas: Canvas) {
        val paint = Paint()
        paint.shader = LinearGradient(
            0f, 0f, 0f, HEIGHT.toFloat(), BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        // 顶栏 / 底栏薄底，让文字区更稳
        paint.shader = null
        paint.color = BAR_TOP
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), TOP_BAR_H, paint)
        paint.color = BAR_BOTTOM
        canvas.drawRect(0f, BOTTOM_BAR_Y, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        val linePaint = Paint().apply {
            color = LINE
            strokeWidth = 2f
        }
        canvas.drawLine(0f, TOP_BAR_H, WIDTH.toFloat(), TOP_BAR_H, linePaint)
        canvas.drawLine(0f, BOTTOM_BAR_Y, WIDTH.toFloat(), BOTTOM_BAR_Y, linePaint)
    }

    private fun drawTopBar(canvas: Canvas, frame: NavFrame?, snapshot: Snapshot) {
        // 左：剩余时间 · 剩余距离
        val left = if (frame == null) {
            "Google 地图导航"
        } else {
            val remaining = NavFrame.remainingText(frame)
            if (remaining == "--") "路线信息 --" else "剩余 $remaining"
        }
        drawText(canvas, left, 20f, 38f, 24f, if (frame == null) DIM else WHITE, Paint.Align.LEFT, medium)

        // 右：连接 / 数据新鲜度
        val stale = frame != null && !frame.isFresh(30_000L)
        val right = buildString {
            append(snapshot.connection)
            if (stale) {
                val sec = (frame!!.ageMs / 1000).coerceAtLeast(1)
                if (isNotEmpty()) append(" · ")
                append("${sec}s 前")
            }
        }
        if (right.isNotEmpty()) {
            val color = when {
                stale -> GOLD
                snapshot.connection.contains("未连接") -> RED
                else -> DIM
            }
            drawText(canvas, right, WIDTH - 20f, 38f, 20f, color, Paint.Align.RIGHT, regular)
            // 小圆点：连接状态指示（不占文字宽度，画在文字左侧）
            val textWidth = textWidth(right, 20f, regular)
            val dotX = WIDTH - 20f - textWidth - 14f
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = when {
                    stale -> GOLD
                    snapshot.connection.contains("未连接") -> RED
                    else -> GREEN
                }
            }
            canvas.drawCircle(dotX, 30f, 5f, dot)
        }
    }

    // ------------------------------------------------------------------
    // 有导航数据
    // ------------------------------------------------------------------
    private fun drawNavigation(canvas: Canvas, frame: NavFrame) {
        // 中央大箭头
        val arrowColor = when (frame.maneuver) {
            Maneuver.ARRIVE -> GREEN
            Maneuver.UNKNOWN -> MUTED
            else -> WHITE
        }
        val stale = !frame.isFresh(30_000L)
        val arrive = frame.maneuver == Maneuver.ARRIVE
        drawManeuver(
            canvas, frame.maneuver, ARROW_CX,
            if (arrive) ARROW_CY - 10f else ARROW_CY, ARROW_HALF,
            if (stale) dim(arrowColor) else arrowColor
        )

        // 距离（到达时改成「已到达」；到点转弯时用「现在」，比 "0 m" 好读）
        val distance = when {
            arrive -> "已到达"
            frame.distanceMeters != null && frame.distanceMeters < 15.0 -> "现在"
            else -> NavFrame.distanceText(frame.distanceMeters)
        }
        drawText(
            canvas, distance, WIDTH / 2f,
            if (arrive) 298f else DISTANCE_BASELINE, if (arrive) 58f else 68f,
            if (arrive) GREEN else GOLD, Paint.Align.CENTER, bold
        )

        // 底部指令条：有路名时两行 —— 第一行完整指令（小），第二行路名（大、金色高亮）；
        // 没有路名时整句最多两行，和以前一样。
        val label = frame.label.ifEmpty { frame.title }
            .ifEmpty { frame.maneuver.zh }
            .ifEmpty { "继续行驶" }
        val road = frame.road
        if (road.isNotEmpty()) {
            drawMultiline(canvas, label, BOTTOM_BAR_Y + 8f, 600f, 22f, WHITE, 1)
            drawMultiline(canvas, road, BOTTOM_BAR_Y + 38f, 600f, 34f, GOLD, 1, bold)
        } else {
            drawMultiline(canvas, label, BOTTOM_BAR_Y + 14f, 600f, 28f, WHITE, 2)
        }
    }

    // ------------------------------------------------------------------
    // 无导航数据
    // ------------------------------------------------------------------
    private fun drawWaiting(canvas: Canvas, snapshot: Snapshot) {
        // 暗色圆环占位（不是罗盘，只是 loading 视觉锚点）
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.rgb(42, 56, 73)
        }
        canvas.drawCircle(ARROW_CX, 158f, 54f, ring)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 90, 114) }
        repeat(3) { i ->
            canvas.drawCircle(ARROW_CX - 20f + i * 20f, 158f, 5f, dot)
        }
        drawText(canvas, "等待导航…", WIDTH / 2f, 264f, 44f, WHITE, Paint.Align.CENTER, medium)
        val hint = snapshot.hint.ifEmpty { "在 Google 地图中开始导航，通知栏出现转向提示即可" }
        drawMultiline(canvas, hint, BOTTOM_BAR_Y + 16f, 600f, 24f, MUTED, 2)
    }

    // ------------------------------------------------------------------
    // 机动箭头（单位坐标 [-1,1]，由 Canvas 缩放；无任何 GPS/heading 输入）
    // ------------------------------------------------------------------
    fun drawManeuver(
        canvas: Canvas,
        maneuver: Maneuver,
        cx: Float,
        cy: Float,
        half: Float,
        color: Int,
    ) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(half, half)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 0.155f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

        fun line(vararg xy: Float) {
            if (xy.size < 4) return
            val path = Path()
            path.moveTo(xy[0], xy[1])
            var i = 2
            while (i + 1 < xy.size) {
                path.lineTo(xy[i], xy[i + 1])
                i += 2
            }
            canvas.drawPath(path, stroke)
        }

        /** (dx,dy) = 行进方向；head 的底边垂直于它。 */
        fun head(tipX: Float, tipY: Float, dx: Float, dy: Float, len: Float, width: Float) {
            val n = kotlin.math.hypot(dx, dy).takeIf { it > 0.0001f } ?: 1f
            val ux = dx / n
            val uy = dy / n
            val bx = tipX - ux * len
            val by = tipY - uy * len
            val px = -uy * width
            val py = ux * width
            val path = Path()
            path.moveTo(tipX, tipY)
            path.lineTo(bx + px, by + py)
            path.lineTo(bx - px, by - py)
            path.close()
            canvas.drawPath(path, fill)
        }

        fun triangle(a: FloatArray, b: FloatArray, c: FloatArray) {
            val path = Path()
            path.moveTo(a[0], a[1])
            path.lineTo(b[0], b[1])
            path.lineTo(c[0], c[1])
            path.close()
            canvas.drawPath(path, fill)
        }

        when (maneuver) {
            Maneuver.LEFT -> {
                line(0f, 0.95f, 0f, 0.08f, -0.62f, 0.08f)
                head(-1.0f, 0.08f, -1f, 0f, 0.46f, 0.30f)
            }
            Maneuver.RIGHT -> {
                line(0f, 0.95f, 0f, 0.08f, 0.62f, 0.08f)
                head(1.0f, 0.08f, 1f, 0f, 0.46f, 0.30f)
            }
            Maneuver.SLIGHT_LEFT -> {
                line(0f, 0.95f, 0f, 0.35f, -0.58f, -0.42f)
                head(-0.80f, -0.74f, -0.58f, -0.77f, 0.34f, 0.24f)
            }
            Maneuver.SLIGHT_RIGHT -> {
                line(0f, 0.95f, 0f, 0.35f, 0.58f, -0.42f)
                head(0.80f, -0.74f, 0.58f, -0.77f, 0.34f, 0.24f)
            }
            Maneuver.UTURN -> {
                line(0.36f, 0.95f, 0.36f, 0.02f)
                canvas.drawArc(
                    RectF(-0.36f, -0.34f, 0.36f, 0.38f), 180f, 180f, false, stroke
                )
                line(-0.36f, 0.02f, -0.36f, 0.52f)
                head(-0.36f, 0.90f, 0f, 1f, 0.34f, 0.26f)
            }
            Maneuver.ROUNDABOUT -> {
                // 环 + 底部入口 + 环顶右侧的出口箭头（缺口就在出口处，顺时针）
                canvas.drawArc(
                    RectF(-0.46f, -0.44f, 0.46f, 0.48f), 330f, 300f, false, stroke
                )
                line(0f, 0.98f, 0f, 0.46f)
                head(0.58f, -0.48f, 1f, -0.5f, 0.30f, 0.18f)
            }
            Maneuver.ARRIVE -> {
                line(-0.30f, 0.96f, -0.30f, -0.86f)
                triangle(
                    floatArrayOf(-0.30f, -0.86f),
                    floatArrayOf(0.74f, -0.54f),
                    floatArrayOf(-0.30f, -0.16f)
                )
                line(-0.80f, 0.96f, 0.12f, 0.96f)
            }
            else -> { // STRAIGHT / UNKNOWN
                line(0f, 0.95f, 0f, 0.02f)
                triangle(
                    floatArrayOf(0f, -0.96f),
                    floatArrayOf(0.32f, -0.28f),
                    floatArrayOf(-0.32f, -0.28f)
                )
            }
        }
        canvas.restore()
    }

    // ------------------------------------------------------------------
    // 文字
    // ------------------------------------------------------------------
    private fun paintFor(size: Float, color: Int, tf: Typeface): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = tf
        }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        align: Paint.Align,
        tf: Typeface,
    ) {
        val paint = paintFor(size, color, tf)
        paint.textAlign = align
        canvas.drawText(text, x, baseline, paint)
    }

    private fun textWidth(text: String, size: Float, tf: Typeface): Float {
        val paint = paintFor(size, WHITE, tf)
        return paint.measureText(text)
    }

    /** 多行居中文字（最多 [maxLines] 行，超出省略号）。 */
    private fun drawMultiline(
        canvas: Canvas,
        text: String,
        top: Float,
        width: Float,
        size: Float,
        color: Int,
        maxLines: Int,
        tf: Typeface = medium,
    ) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = tf
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, tp, width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate((WIDTH - width) / 2f, top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun dim(color: Int): Int = Color.argb(
        (Color.alpha(color) * 0.45f).toInt().coerceIn(0, 255),
        Color.red(color), Color.green(color), Color.blue(color)
    )
}
