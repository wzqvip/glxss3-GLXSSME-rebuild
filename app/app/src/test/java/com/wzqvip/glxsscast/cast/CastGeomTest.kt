package com.wzqvip.glxsscast.cast

import com.wzqvip.glxsscast.lcd.LcdProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * [CastGeom] 的纯 JVM 测试：不需要手机/眼镜。
 *
 * 用「合成抓取帧」复现实测几何关系：2340×1080 手机屏按 400/1080 缩到 **866×400**
 * 抓取，OpenHeadUnit 全屏放 AA 时 AA 的 1920×1080（16:9）居中，左右各 210 px 纯黑边
 * → 抓取帧里左右各 77.8 px 黑边。
 *
 * 断言的是**语义**（内容不丢 / 裁剪比例 / 留边位置），不是具体像素算法，
 * 这样以后改重采样策略也不会误报。
 */
class CastGeomTest {

    private val w = 866
    private val h = 400
    /** 2340×1080 上 1920×1080 的 AA 内容 → 左右各 210 px 黑边 → 抓取里 210*400/1080 ≈ 78 px */
    private val bar = 78

    /**
     * 合成一帧：左右 [bar] 列纯黑，中间内容区每个像素颜色 = (x 低 8 位, y 低 8 位, 0x80)，
     * 这样能从目标像素反推「采样自源图的哪一列/哪一行」。
     */
    private fun frame(barPx: Int = bar, fw: Int = this.w, fh: Int = this.h): ByteBuffer {
        val stride = fw * 4
        val bytes = ByteArray(stride * fh)
        for (y in 0 until fh) {
            for (x in 0 until fw) {
                val o = y * stride + x * 4
                val inside = x >= barPx && x < fw - barPx
                bytes[o] = if (inside) (x and 0xFF).toByte() else 0
                bytes[o + 1] = if (inside) (y and 0xFF).toByte() else 0
                bytes[o + 2] = if (inside) 0x80.toByte() else 0
                bytes[o + 3] = 0xFF.toByte()
            }
        }
        return ByteBuffer.wrap(bytes)
    }

    private fun dst(): ByteArray = ByteArray(LcdProtocol.PAYLOAD_SIZE)

    private fun pixel(buf: ByteArray, x: Int, y: Int): IntArray {
        val o = (y * LcdProtocol.WIDTH + x) * 4
        return intArrayOf(
            buf[o].toInt() and 0xFF,
            buf[o + 1].toInt() and 0xFF,
            buf[o + 2].toInt() and 0xFF,
            buf[o + 3].toInt() and 0xFF
        )
    }

    private fun isBlack(buf: ByteArray, x: Int, y: Int): Boolean =
        pixel(buf, x, y).let { it[0] == 0 && it[1] == 0 && it[2] == 0 }

    private fun map(mode: Int, rect: IntArray? = null, zoom: Float = 1f): ByteArray {
        val out = dst()
        println("[dbg] mode=$mode rect=${rect?.toList()}")
        CastGeom.map(
            frame(), w, h, w * 4, 4, out, mode, rect, zoom, 0f, 0f
        )
        return out
    }

    // ------------------------------------------------------------------
    // 内容区识别
    // ------------------------------------------------------------------

    @Test
    fun detect_findsContentRectAcrossLetterbox() {
        val r = CastGeom.detect(frame(), w, h, w * 4, 4)
        assertNotNull("应该识别出内容区", r)
        r!!
        assertEquals("上边不应该裁", 0, r[1])
        assertEquals("下边不应该裁", h, r[3])
        // 网格是 96 列，误差 ≤ 866/96 ≈ 10 px
        assertTrue("左边 ≈ $bar，实际 ${r[0]}", Math.abs(r[0] - bar) <= 12)
        assertTrue("右边 ≈ ${w - bar}，实际 ${r[2]}", Math.abs(r[2] - (w - bar)) <= 12)
    }

    @Test
    fun detect_returnsNullWhenFrameIsAllDark() {
        // AA 启动闪屏：整帧近黑 —— 不能把「没内容」当成「裁到 0」
        val dark = ByteBuffer.wrap(ByteArray(w * 4 * h))
        assertNull(CastGeom.detect(dark, w, h, w * 4, 4))
    }

    @Test
    fun detect_noBarsMeansFullFrame() {
        val r = CastGeom.detect(frame(barPx = 0), w, h, w * 4, 4)
        assertNotNull(r)
        r!!
        assertEquals(0, r[0])
        assertEquals(w, r[2])
    }

    // ------------------------------------------------------------------
    // 三种模式（内容区已识别）
    // ------------------------------------------------------------------

    /**
     * 适应 + 内容区：内容 640×360 居中，上下各 20 px 黑边，**左右一个像素都不丢**。
     *
     * 旧版 fit（不识别内容区）是 640×295 + 上下 52 px 黑边 —— 内容是缩小的。
     */
    @Test
    fun fit_withContent_keepsWholeFrameWithThinBars() {
        val rect = CastGeom.detect(frame(), w, h, w * 4, 4)!!
        val out = map(CastGeom.FIT, rect)

        // 上下留边约 20 px（(400 - 640*400/711)/2，允许 ±3 px 取整误差）
        var top = 0
        while (top < LcdProtocol.HEIGHT && isBlack(out, 320, top)) top++
        val bottom = LcdProtocol.HEIGHT - top
        assertTrue("上边留边应约 20 px，实际 $top", top in 15..26)
        assertTrue("下边留边应约 20 px，实际 ${LcdProtocol.HEIGHT - bottom}", (LcdProtocol.HEIGHT - bottom) in 15..26)

        // 内容区最左列 → 目标最左列（AA 左侧内容没被裁掉）
        val left = pixel(out, 0, 200)
        assertTrue("左边缘应采到内容区起点附近，实际 R=${left[0]}", Math.abs(left[0] - bar) <= 4)
        // 内容区最右列 → 目标最右列
        val right = pixel(out, LcdProtocol.WIDTH - 1, 200)
        assertTrue(
            "右边缘应采到内容区终点附近，实际 R=${right[0]}",
            Math.abs(right[0] - (w - bar - 1)) <= 6
        )
    }

    /** 裁剪 + 内容区：铺满 640×400，但只裁掉内容左右各 ~5%（旧版是整屏裁 13%）。 */
    @Test
    fun cover_withContent_cropsAboutFivePercentPerSide() {
        val rect = CastGeom.detect(frame(), w, h, w * 4, 4)!!
        val out = map(CastGeom.COVER, rect)
        assertTrue("铺满：左上角不该是黑的", !isBlack(out, 0, 0))

        val contentW = w - 2 * bar            // 711
        val scale = 640f / contentW           // cover 撑满用的是高度？640/711 < 1 → 用高度
        // 实际 scale = max(640/711, 400/400) = 1.0 → 取内容 640 / 711 宽，左右各裁 ~35 px
        val expectedLeft = (contentW - 640) / 2f
        val got = pixel(out, 0, 200)[0].toFloat()
        assertTrue(
            "左边缘应裁掉约 $expectedLeft px（内容是 0..711），实际采样 R=$got（scale=$scale）",
            Math.abs(got - (bar + expectedLeft)) <= 6
        )
    }

    /** 拉伸 + 内容区：铺满且不丢内容（横向会有 16:9 → 16:10 的 ~11% 变形）。 */
    @Test
    fun stretch_withContent_fillsEverything() {
        val rect = CastGeom.detect(frame(), w, h, w * 4, 4)!!
        val out = map(CastGeom.STRETCH, rect)
        println("[dbg] stretch corner=${pixel(out, 639, 399).toList()} tl=${pixel(out, 0, 0).toList()} mid=${pixel(out, 639, 200).toList()} left=${pixel(out, 0, 200).toList()}")
        assertTrue("左上角应是内容", !isBlack(out, 0, 0))
        assertTrue("右下角应是内容", !isBlack(out, LcdProtocol.WIDTH - 1, LcdProtocol.HEIGHT - 1))
        // 左右不丢：目标最左 = 内容区最左
        assertEquals(bar, pixel(out, 0, 200)[0])
    }

    /** 不给 rect（= 历史行为）：整幅映射，cover 会把两侧裁掉更多。 */
    @Test
    fun cover_withoutContent_keepsLegacyBehavior() {
        val out = map(CastGeom.COVER, rect = null)
        // 866×400 → cover 1.0 取中间 640 列：起点 = (866-640)/2 = 113
        assertEquals(113 + bar, pixel(out, 0, 200)[0])
    }

    /** zoom 仍在内容区上生效（1.2× 时采样窗口变小、仍然居中）。 */
    @Test
    fun cover_zoomShrinksSourceWindow() {
        val rect = CastGeom.detect(frame(), w, h, w * 4, 4)!!
        val noZoom = map(CastGeom.COVER, rect)
        val zoomed = map(CastGeom.COVER, rect, zoom = 1.2f)
        val a = pixel(noZoom, 0, 200)[0]
        val b = pixel(zoomed, 0, 200)[0]
        assertTrue("放大后左边缘应更靠内容中间（裁剪更多）：$a → $b", b > a)
    }
}
