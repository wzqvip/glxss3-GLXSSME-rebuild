package com.wzqvip.glxsscast.cast

import com.wzqvip.glxsscast.lcd.LcdProtocol
import java.nio.ByteBuffer

/**
 * 抓屏帧 → 640×400 LCD payload 的几何映射（含「AA 内容区」自动识别）。
 *
 * ## 为什么需要它
 *
 * 眼镜 640×400 = **1.60**，手机屏 2340×1080 = **2.167**，而 Android Auto 的画布是
 * **16:9**。OpenHeadUnit 把 1280×720 的 AA 视频按「保持比例 + 上下/左右留边」画在手机屏上
 * （实测：内容区 x∈[210,2130]、y∈[0,1080]，左右各 210 px 纯黑边），于是三层比例套在一起：
 *
 * ```
 * 手机屏 2340×1080 ──┐  黑边 210 │  AA 内容 1920×1080 (16:9)  │ 黑边 210
 *                    └─ 抓屏 866×400 ── map ──▶ 640×400 ──▶ 眼镜
 * ```
 *
 * - 旧 **cover**：scale = max(640/866, 400/400) = 1.0 → 只取中间 640 列 = 屏幕 x∈[305,2033]，
 *   正好把 AA 内容左右各切掉 ~95 px（**10% 画面丢失**）——用户说的「没显示全」就是它；
 * - 旧 **fit**：整幅 866×400 塞进 640×400 → 内容只有 640×295，上下**各 52 px 黑边**（26% 浪费）；
 * - 旧 **stretch**：整幅拉伸 → 内容全在，但横向被压 ~35%（比例严重失真）。
 *
 * ## 现在的做法
 *
 * 先识别出**真正有内容的那块矩形**（把 letterbox 黑边去掉），再在三种模式下映射：
 *
 * | 模式 | 结果 | 内容丢失 | 变形 | 边 |
 * |---|---|---|---|---|
 * | 适应 [FIT] | 640×360 居中 | 0 | 0 | 上下各 20 px（5%） |
 * | 裁剪 [COVER] | 640×400 居中 | 左右各 5% | 0 | 0 |
 * | 拉伸 [STRETCH] | 640×400 满铺 | 0 | 横向 +11% | 0 |
 *
 * 识别不到黑边（例如整屏就是内容）时自动退化为「整幅」，行为与旧版一致。
 *
 * 性能：每像素只做一次 4 B 拷贝（最近邻），源行按需 bulk 拷进 scratch，
 * 30 fps × 256k 像素 ≈ 7.7 M px/s，和旧 mapCover 同一量级。
 */
object CastGeom {

    /** 与 [CastService.MODE_*] 对应 */
    const val FIT = 0
    const val COVER = 1
    const val STRETCH = 2

    /** letterbox 判定：纯黑边三通道 ≈ 0；AA 深色界面实测 luma ≥ 16 */
    private const val DARK_MAX = 12

    // 检测网格：96×54 ≈ 5k 个采样点/次，够用且几乎不占 CPU
    private const val GRID_COLS = 96
    private const val GRID_ROWS = 54

    /** 内容区至少占整幅的 20%（更小说明是误检：比如 AA 启动闪屏全黑） */
    private const val MIN_CONTENT_FRAC = 0.20f
    /** 可信的宽高比范围：竖屏 0.45（9:16）～ 宽屏 2.2（21:9） */
    private const val MIN_ASPECT = 0.45f
    private const val MAX_ASPECT = 2.20f

    /**
     * 内容区跟踪器（**只在 cast 线程用**，无需加锁）。
     *
     * - 每 [PROBE_EVERY] 帧探测一次（30 fps 下 ≈ 每 0.33 s），结果做迟滞：
     *   变化小于 1.5% 直接接受，变化大要连续 2 次一致才切换，避免 AA 闪一帧
     *   深色画面时画面「跳一下」。
     * - 尺寸变化（旋转/resize）时清空，重新探测。
     */
    class ContentTracker {
        /** [l, t, r, b]（抓取坐标，含端点）；null = 用整幅 */
        var rect: IntArray? = null
            private set

        /** 是否启用识别（关掉就退回旧行为：整幅映射） */
        @Volatile
        var enabled = true

        /** 给 UI/日志看的一句话 */
        @Volatile
        var desc = "全屏"

        private var probedW = -1
        private var probedH = -1
        private var sinceProbe = Int.MAX_VALUE
        private var candidate: IntArray? = null
        private var candidateHits = 0

        fun reset() {
            rect = null
            desc = "全屏"
            probedW = -1
            probedH = -1
            sinceProbe = Int.MAX_VALUE
            candidate = null
            candidateHits = 0
        }

        /** 每帧调用一次；内部节流探测。 */
        fun tick(buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int) {
            if (w != probedW || h != probedH) {
                probedW = w
                probedH = h
                rect = null
                candidate = null
                candidateHits = 0
                desc = "全屏"
                sinceProbe = Int.MAX_VALUE
            }
            if (!enabled) {
                if (rect != null) {
                    rect = null
                    desc = "全屏"
                }
                candidate = null
                candidateHits = 0
                return
            }
            sinceProbe++
            if (sinceProbe < PROBE_EVERY) return
            sinceProbe = 0
            val found = detect(buf, w, h, rowStride, pixelStride) ?: run {
                // 探测失败（全黑闪屏 / 抓取还没稳定）→ 保持上一次结果
                return
            }
            adopt(found, w, h)
        }

        private fun adopt(found: IntArray, w: Int, h: Int) {
            val cur = rect
            if (cur == null) {
                rect = found
                desc = describe(found, w, h)
                return
            }
            val tolX = maxOf(1, (w * 0.015f).toInt())
            val tolY = maxOf(1, (h * 0.015f).toInt())
            val small = kotlin.math.abs(found[0] - cur[0]) <= tolX &&
                kotlin.math.abs(found[2] - cur[2]) <= tolX &&
                kotlin.math.abs(found[1] - cur[1]) <= tolY &&
                kotlin.math.abs(found[3] - cur[3]) <= tolY
            if (small) {
                rect = found
                candidate = null
                candidateHits = 0
                desc = describe(found, w, h)
                return
            }
            val cand = candidate
            val same = cand != null &&
                kotlin.math.abs(found[0] - cand[0]) <= tolX &&
                kotlin.math.abs(found[2] - cand[2]) <= tolX &&
                kotlin.math.abs(found[1] - cand[1]) <= tolY &&
                kotlin.math.abs(found[3] - cand[3]) <= tolY
            candidate = found
            candidateHits = if (same) candidateHits + 1 else 1
            if (candidateHits >= 2) {
                rect = found
                candidate = null
                candidateHits = 0
                desc = describe(found, w, h)
            }
        }

        private fun describe(r: IntArray, w: Int, h: Int): String {
            val cw = r[2] - r[0]
            val ch = r[3] - r[1]
            return if (cw >= w - 1 && ch >= h - 1) "全屏" else "$cw×$ch"
        }

        companion object {
            /** 每多少帧探测一次 */
            const val PROBE_EVERY = 10
        }
    }

    /**
     * 在抓取帧里找「有内容的矩形」：从四边往里剥掉纯黑/近黑的行列。
     *
     * @return `[l, t, r, b]`（右/下为**开区间**），识别不到可信内容区时返回 null。
     */
    fun detect(
        buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int
    ): IntArray? {
        if (w < 32 || h < 32) return null
        val limit = buf.limit()
        if (limit < 4) return null

        val cols = minOf(GRID_COLS, w)
        val rows = minOf(GRID_ROWS, h)
        val colMax = IntArray(cols)
        val rowMax = IntArray(rows)

        // 一次网格扫描：同时算每列/每行的最大亮度
        for (j in 0 until rows) {
            val sy = (j.toLong() * h / rows).toInt().coerceIn(0, h - 1)
            val rowOff = sy * rowStride
            var rMax = 0
            for (i in 0 until cols) {
                val sx = (i.toLong() * w / cols).toInt().coerceIn(0, w - 1)
                val o = rowOff + sx * pixelStride
                if (o < 0 || o + 2 >= limit) continue
                val r = buf.get(o).toInt() and 0xFF
                val g = buf.get(o + 1).toInt() and 0xFF
                val b = buf.get(o + 2).toInt() and 0xFF
                // max 通道比加权 luma 更适合「黑边 vs 深色 UI」：AA 的深蓝 > 0
                val v = maxOf(r, g, b)
                if (v > colMax[i]) colMax[i] = v
                if (v > rMax) rMax = v
            }
            rowMax[j] = rMax
        }

        var left = 0
        while (left < cols && colMax[left] <= DARK_MAX) left++
        var right = cols
        while (right > left && colMax[right - 1] <= DARK_MAX) right--
        var top = 0
        while (top < rows && rowMax[top] <= DARK_MAX) top++
        var bottom = rows
        while (bottom > top && rowMax[bottom - 1] <= DARK_MAX) bottom--
        if (right <= left || bottom <= top) return null

        // 网格下标 → 像素坐标（用「单元格边界」，宁大勿小）
        val l = (left.toLong() * w / cols).toInt()
        val r = (right.toLong() * w / cols).toInt()
        val t = (top.toLong() * h / rows).toInt()
        val b = (bottom.toLong() * h / rows).toInt()

        val cw = r - l
        val ch = b - t
        if (cw <= 0 || ch <= 0) return null
        if (cw.toFloat() * ch < w.toFloat() * h * MIN_CONTENT_FRAC) return null
        val aspect = cw.toFloat() / ch
        if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) return null
        return intArrayOf(l, t, r, b)
    }

    // ---- 复用缓冲（只在 cast 线程用；避免每帧 30 次分配 → GC 抖动） ----
    private var colIndex = IntArray(LcdProtocol.WIDTH)
    private var rowScratch = ByteArray(0)

    /**
     * 把抓取帧映射成 640×400 RGBA（[dst] 必须正好是 payload 大小）。
     *
     * @param rect [ContentTracker.rect]；null = 整幅
     */
    fun map(
        buf: ByteBuffer,
        w: Int,
        h: Int,
        rowStride: Int,
        pixelStride: Int,
        dst: ByteArray,
        mode: Int,
        rect: IntArray?,
        zoom: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        val dw = LcdProtocol.WIDTH
        val dh = LcdProtocol.HEIGHT
        if (dw * dh * 4 != dst.size) return
        if (w <= 0 || h <= 0) return

        val sl = (rect?.get(0) ?: 0).coerceIn(0, w - 1)
        val st = (rect?.get(1) ?: 0).coerceIn(0, h - 1)
        val sr = (rect?.get(2) ?: w).coerceIn(sl + 1, w)
        val sb = (rect?.get(3) ?: h).coerceIn(st + 1, h)
        val sw = (sr - sl).toFloat()
        val sh = (sb - st).toFloat()

        when (mode) {
            STRETCH -> {
                // 内容区（或整幅）非等比铺满：信息不丢，横向会变形
                blit(
                    buf, w, h, rowStride, pixelStride, dst,
                    sl.toFloat(), st.toFloat(), sw, sh,
                    0, 0, dw, dh
                )
            }
            COVER -> {
                // 等比放大填满，居中裁剪；zoom/offset 叠加在内容区上
                val z = zoom.coerceIn(1f, 4f)
                val scale = maxOf(dw / sw, dh / sh) * z
                val cutW = (dw / scale).coerceIn(1f, sw)
                val cutH = (dh / scale).coerceIn(1f, sh)
                val maxLeft = (sw - cutW).coerceAtLeast(0f)
                val maxTop = (sh - cutH).coerceAtLeast(0f)
                val cx = sw / 2f + offsetX.coerceIn(-1f, 1f) * sw * 0.5f
                val cy = sh / 2f + offsetY.coerceIn(-1f, 1f) * sh * 0.5f
                val left = (cx - cutW / 2f).coerceIn(0f, maxLeft)
                val top = (cy - cutH / 2f).coerceIn(0f, maxTop)
                blit(
                    buf, w, h, rowStride, pixelStride, dst,
                    sl + left, st + top, cutW, cutH,
                    0, 0, dw, dh
                )
            }
            else -> {
                // 适应：等比缩放居中，上下（或左右）用黑边补 —— 内容一个像素都不丢
                val scale = minOf(dw / sw, dh / sh)
                val fitW = (sw * scale).toInt().coerceIn(1, dw)
                val fitH = (sh * scale).toInt().coerceIn(1, dh)
                val dx0 = (dw - fitW) / 2
                val dy0 = (dh - fitH) / 2
                fillBars(dst, dw, dh, dx0, dy0, fitW, fitH)
                blit(
                    buf, w, h, rowStride, pixelStride, dst,
                    sl.toFloat(), st.toFloat(), sw, sh,
                    dx0, dy0, fitW, fitH
                )
            }
        }
    }

    /** 适应模式的留边：只刷边条，不整屏 memset */
    private fun fillBars(
        dst: ByteArray, dw: Int, dh: Int, dx0: Int, dy0: Int, fitW: Int, fitH: Int
    ) {
        val rowBytes = dw * 4
        val topBytes = dy0 * rowBytes
        if (topBytes > 0) java.util.Arrays.fill(dst, 0, topBytes.coerceAtMost(dst.size), 0)
        val bottomStart = (dy0 + fitH) * rowBytes
        if (bottomStart < dst.size) java.util.Arrays.fill(dst, bottomStart, dst.size, 0)
        val sideBytes = (dw - fitW) * 4
        if (sideBytes > 0) {
            val leftBytes = dx0 * 4
            for (y in dy0 until (dy0 + fitH)) {
                val o = y * rowBytes
                java.util.Arrays.fill(dst, o, o + leftBytes, 0)
                val rs = o + (dx0 + fitW) * 4
                java.util.Arrays.fill(dst, rs, rs + (dw - dx0 - fitW) * 4, 0)
            }
        }
    }

    /**
     * 把源窗口 `(sx0,sy0,sw,sh)` 等比/非等比映射到目标窗口 `(dx0,dy0,dw,dh)`（最近邻）。
     *
     * 源行按需 bulk 拷进 scratch（同一源行被多个目标行复用时只拷一次），
     * 目标列索引预计算（每帧一次 640 次除法展开成查表）。
     */
    private fun blit(
        buf: ByteBuffer,
        w: Int,
        h: Int,
        rowStride: Int,
        pixelStride: Int,
        dst: ByteArray,
        sx0: Float,
        sy0: Float,
        sw: Float,
        sh: Float,
        dx0: Int,
        dy0: Int,
        dw: Int,
        dh: Int,
    ) {
        if (sw <= 0f || sh <= 0f || dw <= 0 || dh <= 0) return
        val dstRowBytes = LcdProtocol.WIDTH * 4
        val limit = buf.limit()
        if (limit < 4 || rowStride <= 0) return
        // 浮点取整后 sy 可能越过 limit（stride 与抓取尺寸不一致的残帧），先算上限
        val maxSy = ((limit - 1) / rowStride).coerceIn(0, h - 1)

        if (colIndex.size < dw) colIndex = IntArray(dw)
        val xs = colIndex
        for (i in 0 until dw) {
            xs[i] = (sx0 + (i + 0.5f) * sw / dw).toInt().coerceIn(0, w - 1)
        }

        val rowBytes = minOf(w * pixelStride, rowStride).coerceAtLeast(4)
        var scratch = rowScratch
        if (scratch.size < rowBytes) {
            scratch = ByteArray(rowBytes)
            rowScratch = scratch
        }
        var lastSy = -1
        for (y in 0 until dh) {
            val sy = (sy0 + (y + 0.5f) * sh / dh).toInt().coerceIn(0, maxSy)
            if (sy != lastSy) {
                buf.position(sy * rowStride)
                val n = minOf(rowBytes, buf.remaining())
                if (n > 0) buf.get(scratch, 0, n)
                lastSy = sy
            }
            val o = (dy0 + y) * dstRowBytes + dx0 * 4
            var si = o
            if (pixelStride == 4) {
                for (i in 0 until dw) {
                    val s = xs[i] shl 2
                    dst[si] = scratch[s]
                    dst[si + 1] = scratch[s + 1]
                    dst[si + 2] = scratch[s + 2]
                    dst[si + 3] = scratch[s + 3]
                    si += 4
                }
            } else {
                for (i in 0 until dw) {
                    val s = xs[i] * pixelStride
                    dst[si] = scratch[s]
                    dst[si + 1] = if (s + 1 < rowBytes) scratch[s + 1] else 0
                    dst[si + 2] = if (s + 2 < rowBytes) scratch[s + 2] else 0
                    dst[si + 3] = 255.toByte()
                    si += 4
                }
            }
        }
    }
}
