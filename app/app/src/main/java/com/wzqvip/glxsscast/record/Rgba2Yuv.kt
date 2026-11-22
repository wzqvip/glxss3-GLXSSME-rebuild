package com.wzqvip.glxsscast.record

/**
 * RGBA8888（内存字节序 R,G,B,A，ImageReader / Bitmap.copyPixelsToBuffer 都是这个序）
 * → I420（`Y` 紧排 width、`U`、`V` 各自紧排 width/2，BT.601 limited range，整数运算）。
 *
 * 只支持偶数宽高（H.264 本来就要求偶数），按 2×2 块一次算完 4 个 Y + 1 个 U + 1 个 V，
 * 640×400 一帧约 3~5 ms（ART JIT 后），在录像线程上跑，不占 UI / USB 线程。
 */
internal object Rgba2Yuv {

    /**
     * @param out 目标缓冲，长度必须 >= `w*h*3/2`，布局：Y[w*h] + U[w*h/4] + V[w*h/4]
     */
    fun i420(rgba: ByteArray, w: Int, h: Int, out: ByteArray) {
        require(w > 0 && h > 0 && (w and 1) == 0 && (h and 1) == 0) { "bad size ${w}x$h" }
        val ySize = w * h
        val cSize = ySize / 4
        require(out.size >= ySize + 2 * cSize) { "out too small: ${out.size} < ${ySize + 2 * cSize}" }
        require(rgba.size >= ySize * 4) { "rgba too small: ${rgba.size} < ${ySize * 4}" }

        val cw = w / 2
        val rowBytes = w * 4
        for (y in 0 until h step 2) {
            val out0 = y * w          // Y 行 y
            val out1 = out0 + w       // Y 行 y+1
            val src0 = y * rowBytes
            val src1 = src0 + rowBytes
            val cRow = (y / 2) * cw
            for (x in 0 until w step 2) {
                val p00 = src0 + x * 4
                val p01 = p00 + 4
                val p10 = src1 + x * 4
                val p11 = p10 + 4

                val r00 = rgba[p00].toInt() and 0xff
                val g00 = rgba[p00 + 1].toInt() and 0xff
                val b00 = rgba[p00 + 2].toInt() and 0xff
                val r01 = rgba[p01].toInt() and 0xff
                val g01 = rgba[p01 + 1].toInt() and 0xff
                val b01 = rgba[p01 + 2].toInt() and 0xff
                val r10 = rgba[p10].toInt() and 0xff
                val g10 = rgba[p10 + 1].toInt() and 0xff
                val b10 = rgba[p10 + 2].toInt() and 0xff
                val r11 = rgba[p11].toInt() and 0xff
                val g11 = rgba[p11 + 1].toInt() and 0xff
                val b11 = rgba[p11 + 2].toInt() and 0xff

                val xi = out0 + x
                out[xi] = yOf(r00, g00, b00).toByte()
                out[xi + 1] = yOf(r01, g01, b01).toByte()
                out[xi + w] = yOf(r10, g10, b10).toByte()
                out[xi + w + 1] = yOf(r11, g11, b11).toByte()

                val r = (r00 + r01 + r10 + r11) shr 2
                val g = (g00 + g01 + g10 + g11) shr 2
                val b = (b00 + b01 + b10 + b11) shr 2
                val ci = cRow + (x shr 1)
                // BT.601：U = -0.169R -0.331G +0.500B +128，V = +0.500R -0.419G -0.081B +128
                out[ySize + ci] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                out[ySize + cSize + ci] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
            }
        }
    }

    private fun yOf(r: Int, g: Int, b: Int): Int {
        val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
        return if (y < 16) 16 else if (y > 235) 235 else y
    }
}
