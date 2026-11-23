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
 * 抓取；OpenHeadUnit 全屏放 AA 时 AA 的 1920×1080（16:9）居中，左右各 210 px 纯黑边
 * → 抓取帧里左右各 210*400/1080 ≈ **78 px** 黑边。
 *
 * 内容区像素编码（方便从目标像素反推「采样自源图哪一列」）：
 * - R = (x - 78) / 4     → 0..177，左边缘 0、右边缘 ≈ 177
 * - G = y & 0xFF
 * - B = 0x80（恒非 0 → 判断「是不是黑边」不会误伤）
 */
class CastGeomTest {

    private val w = 866
    private val h = 400
    private val bar = 78

    private fun frame(barPx: Int = bar): ByteBuffer {
        val stride = w * 4
        val bytes = ByteArray(stride * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val o = y * stride + x * 4
                val inside = x >= barPx && x < w - barPx
                bytes[o] = if (inside) ((x - barPx) / 4).toByte() else 0
                bytes[o + 1] = if (inside) (y and 0xFF).toByte() else 0
                bytes[o + 2] = if (inside) 0x80.toByte() else 0
                bytes[o + 3] = 0xFF.toByte()
            }
        }
        return ByteBuffer.wrap(bytes)
    }

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

    private fun detect(): IntArray? = CastGeom.detect(frame(), w, h, w * 4, 4)

    private fun map(mode: Int, rect: IntArray? = null, zoom: Float = 1f): ByteArray {
        val out = ByteArray(LcdProtocol.PAYLOAD_SIZE)
        CastGeom.map(frame(), w, h, w * 4, 4, out, mode, rect, zoom, 0f, 0f)
        return out
    }

    /** 内容区最右一列对应的 R（= (866-78-1-78)/4 ≈ 177） */
    private val rightR = (w - 2 * bar - 1) / 4

    // ------------------------------------------------------------------
    // 内容区识别
    // ------------------------------------------------------------------

    @Test
    fun detect_findsContentRectExactly() {
        val r = detect()
        assertNotNull("应该识别出内容区", r)
        r!!
        assertEquals("左边黑边 78 px", bar, r[0])
        assertEquals("右边黑边 78 px", w - bar, r[2])
        assertEquals("上边不该裁", 0, r[1])
        assertEquals("下边不该裁", h, r[3])
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
        assertEquals(0, r[1])
        assertEquals(h, r[3])
    }

    // ------------------------------------------------------------------
    // 三种模式（内容区已识别）
    // ------------------------------------------------------------------

    /**
     * 适应 + 内容区：内容 640×360 居中，上下各 20 px 留边，**内容一个像素都不丢**。
     *
     * 旧版 fit（不识别内容区）是 640×295 + 上下各 52 px 黑边 —— 内容被缩小 18%。
     */
    @Test
    fun fit_withContent_keepsWholeFrameWithThinBars() {
        val rect = detect()!!
        val out = map(CastGeom.FIT, rect)

        var top = 0
        while (top < LcdProtocol.HEIGHT && isBlack(out, 320, top)) top++
        var bottom = 0
        while (bottom < LcdProtocol.HEIGHT && isBlack(out, 320, LcdProtocol.HEIGHT - 1 - bottom)) bottom++
        assertTrue("上边留边应约 20 px，实际 $top", top in 15..26)
        assertTrue("下边留边应约 20 px，实际 $bottom", bottom in 15..26)
        assertEquals("内容应铺满 640 宽（左边缘不裁）", 0, pixel(out, 0, 200)[0])
        assertTrue(
            "右边缘应采到内容区终点（R≈$rightR），实际 ${pixel(out, LcdProtocol.WIDTH - 1, 200)[0]}",
            Math.abs(pixel(out, LcdProtocol.WIDTH - 1, 200)[0] - rightR) <= 3
        )
        // 垂直方向也不丢：目标顶部内容行 = 源第一行
        assertEquals(0, pixel(out, 320, top)[1])
    }

    /** 裁剪 + 内容区：铺满 640×400，但只裁掉内容左右各 ~5%（旧版是整屏裁 13%）。 */
    @Test
    fun cover_withContent_cropsAboutFivePercentPerSide() {
        val rect = detect()!!
        val out = map(CastGeom.COVER, rect)
        assertTrue("铺满：左上角不该是黑的", !isBlack(out, 0, 0))

        val contentW = w - 2 * bar                       // 710
        val expectedLeftR = ((contentW - 640) / 2) / 4   // ≈ 8
        val got = pixel(out, 0, 200)[0]
        assertTrue(
            "左边缘应只裁掉约 ${(contentW - 640) / 2} px（占内容 ${(contentW - 640) * 50 / contentW}%）," +
                "实际采样 R=$got（期望 ≈$expectedLeftR）",
            Math.abs(got - expectedLeftR) <= 4
        )
        // 右边缘对称
        assertTrue(
            "右边缘应对称裁剪，实际 R=${pixel(out, 639, 200)[0]}",
            Math.abs(pixel(out, 639, 200)[0] - (rightR - expectedLeftR)) <= 4
        )
    }

    /** 拉伸 + 内容区：铺满且不丢内容（16:9 → 16:10，横向约 11% 变形）。 */
    @Test
    fun stretch_withContent_fillsEverything() {
        val rect = detect()!!
        val out = map(CastGeom.STRETCH, rect)
        assertEquals("左边缘 = 内容区左边缘", 0, pixel(out, 0, 200)[0])
        assertTrue(
            "右下角应是内容（R≈$rightR），实际 ${pixel(out, 639, 399)[0]}",
            Math.abs(pixel(out, 639, 399)[0] - rightR) <= 3
        )
        assertTrue("右上角应是内容", !isBlack(out, LcdProtocol.WIDTH - 1, 0))
    }

    /** 不给 rect（= 关掉识别）：整幅映射，cover 会把两侧裁掉更多（旧行为，用于对比）。 */
    @Test
    fun cover_withoutContent_keepsLegacyBehavior() {
        val out = map(CastGeom.COVER, rect = null)
        // 866×400 → cover 1.0 取中间 640 列：起点 = (866-640)/2 = 113，落在内容区里 → R=(113-78)/4≈8
        // 旧版是 113 → 采样到内容区靠左的位置，右边同样丢掉 113 px（AA 内容被切）。
        val r = pixel(out, 0, 200)[0]
        assertEquals((113 - bar) / 4, r)
    }

    /** zoom 仍在内容区上生效（1.2× 时采样窗口变小、仍然居中）。 */
    @Test
    fun cover_zoomShrinksSourceWindow() {
        val rect = detect()!!
        val noZoom = map(CastGeom.COVER, rect)
        val zoomed = map(CastGeom.COVER, rect, zoom = 1.2f)
        val a = pixel(noZoom, 0, 200)[0]
        val b = pixel(zoomed, 0, 200)[0]
        assertTrue("放大后左边缘应更靠内容中间（裁剪更多）：$a → $b", b > a)
    }
}
