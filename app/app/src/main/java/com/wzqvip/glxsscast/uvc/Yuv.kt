package com.wzqvip.glxsscast.uvc

/**
 * 把 UVC 的未压缩 YUV 帧转成 ARGB_8888 的 `IntArray`（BT.601 limited range，整数运算）。
 *
 * 只做"能跑起来"所需的最小转换：每帧 640x480 = 307200 像素，纯 Kotlin 约 8~15 ms
 * （ART JIT 后），15 fps 下 CPU 占用可以接受。要更快可以把这三段换成 RenderEffect /
 * libyuv（将来接 native 时）。
 */
object Yuv {

    private fun clamp(x: Int): Int = if (x < 0) 0 else if (x > 255) 255 else x

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xff shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)

    /**
     * YUV420 planar：`Y` 平面在 `buf[0, w*h)`，U/V 平面各自 `w*h/4`。
     *
     * @param uOff U(Cb) 平面起始偏移。YV12 → `w*h + w*h/4`；I420 → `w*h`
     * @param vOff V(Cr) 平面起始偏移。YV12 → `w*h`；       I420 → `w*h + w*h/4`
     */
    fun yuv420ToArgb(
        out: IntArray,
        buf: ByteArray,
        uOff: Int,
        vOff: Int,
        w: Int,
        h: Int
    ) {
        val cw = w / 2
        var o = 0
        for (j in 0 until h) {
            val yBase = j * w
            val cBase = (j / 2) * cw
            for (i in 0 until w) {
                val y = (buf[yBase + i].toInt() and 0xff) - 16
                val u = (buf[uOff + cBase + (i / 2)].toInt() and 0xff) - 128
                val v = (buf[vOff + cBase + (i / 2)].toInt() and 0xff) - 128
                val c = if (y < 0) 0 else y
                val r = (298 * c + 409 * v + 128) shr 8
                val g = (298 * c - 100 * u - 208 * v + 128) shr 8
                val b = (298 * c + 516 * u + 128) shr 8
                out[o++] = argb(r, g, b)
            }
        }
    }

    /**
     * NV12 / NV21：`Y` 平面 + 交织 UV 平面。
     * NV12 = U,V,U,V…；NV21 = V,U,V,U…（`swapUv = true`）
     */
    fun nv12ToArgb(
        out: IntArray,
        buf: ByteArray,
        uvOff: Int,
        w: Int,
        h: Int,
        swapUv: Boolean
    ) {
        var o = 0
        for (j in 0 until h) {
            val yBase = j * w
            val cBase = uvOff + (j / 2) * w
            for (i in 0 until w) {
                val y = (buf[yBase + i].toInt() and 0xff) - 16
                val c0 = buf[cBase + (i / 2) * 2].toInt() and 0xff
                val c1 = buf[cBase + (i / 2) * 2 + 1].toInt() and 0xff
                val u = (if (swapUv) c1 else c0) - 128
                val v = (if (swapUv) c0 else c1) - 128
                val c = if (y < 0) 0 else y
                val r = (298 * c + 409 * v + 128) shr 8
                val g = (298 * c - 100 * u - 208 * v + 128) shr 8
                val b = (298 * c + 516 * u + 128) shr 8
                out[o++] = argb(r, g, b)
            }
        }
    }

    /**
     * 打包格式。YUY2 = `Y0 U Y1 V`；UYVY = `U Y0 V Y1`（`uyvy = true`）。
     */
    fun yuy2ToArgb(
        out: IntArray,
        buf: ByteArray,
        w: Int,
        h: Int,
        uyvy: Boolean
    ) {
        var p = 0
        var o = 0
        val rowPairs = w / 2
        for (j in 0 until h) {
            for (i in 0 until rowPairs) {
                val b0 = buf[p].toInt() and 0xff
                val b1 = buf[p + 1].toInt() and 0xff
                val b2 = buf[p + 2].toInt() and 0xff
                val b3 = buf[p + 3].toInt() and 0xff
                p += 4
                val y0: Int
                val y1: Int
                val u: Int
                val v: Int
                if (uyvy) {
                    u = b0 - 128; y0 = b1; v = b2 - 128; y1 = b3
                } else {
                    y0 = b0; u = b1 - 128; y1 = b2; v = b3 - 128
                }
                out[o++] = yuv(y0, u, v)
                out[o++] = yuv(y1, u, v)
            }
        }
    }

    private fun yuv(y0: Int, u: Int, v: Int): Int {
        val c = if (y0 - 16 < 0) 0 else y0 - 16
        val r = (298 * c + 409 * v + 128) shr 8
        val g = (298 * c - 100 * u - 208 * v + 128) shr 8
        val b = (298 * c + 516 * u + 128) shr 8
        return argb(r, g, b)
    }
}
