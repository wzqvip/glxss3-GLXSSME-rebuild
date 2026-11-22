package com.wzqvip.glxsscast.lcd

/**
 * GLXSS3 投屏帧协议。
 *
 * 帧 = 16 字节头 + 640x400 原始 RGBA8888（1,024,000 字节）
 *
 * 头布局（小端）：
 *   [0]      format  = 1
 *   [1..3]   = 0
 *   [4..7]   totalLen = 16 + payloadLen
 *   [8..15]  = 0（扩展）
 */
object LcdProtocol {
    const val WIDTH = 640
    const val HEIGHT = 400
    const val HEADER_SIZE = 16
    const val PAYLOAD_SIZE = WIDTH * HEIGHT * 4

    /**
     * 画面 180° 软件翻转总开关（@Volatile，投屏与测试图共用）。
     *
     * 单一状态放在这里，[com.wzqvip.glxsscast.cast.CastService.flip180] 只是它的别名，
     * 这样投屏帧和 [bars]/[solid] 生成的测试图不会出现一个翻一个不翻。
     * 由 [com.wzqvip.glxsscast.settings.Prefs.flip180] 持久化。
     */
    @Volatile
    var flip180 = false

    /**
     * 原地 180° 旋转 640×400 RGBA：目标 (x,y) ← 源 (W-1-x, H-1-y)。
     *
     * 等价于「扫描顺序反转」：第 y 行与第 H-1-y 行成对交换，且每行内像素逆序。
     * 只交换 4 字节像素，不改变 RGBA 内部字节序。约 128k 次 4B 交换/帧。
     */
    fun flip180InPlace(buf: ByteArray) {
        require(buf.size == PAYLOAD_SIZE) { "buffer must be $PAYLOAD_SIZE bytes, got ${buf.size}" }
        val rowBytes = WIDTH * 4
        for (y in 0 until HEIGHT / 2) {
            val top = y * rowBytes
            val bottom = (HEIGHT - 1 - y) * rowBytes
            for (x in 0 until WIDTH) {
                val i = top + x * 4
                val j = bottom + (WIDTH - 1 - x) * 4
                for (k in 0 until 4) {
                    val t = buf[i + k]
                    buf[i + k] = buf[j + k]
                    buf[j + k] = t
                }
            }
        }
    }

    fun frame(payload: ByteArray): ByteArray {
        require(payload.size == PAYLOAD_SIZE) {
            "payload must be $PAYLOAD_SIZE bytes, got ${payload.size}"
        }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = 1
        val total = out.size
        out[4] = (total and 0xFF).toByte()
        out[5] = ((total ushr 8) and 0xFF).toByte()
        out[6] = ((total ushr 16) and 0xFF).toByte()
        out[7] = ((total ushr 24) and 0xFF).toByte()
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }

    /** 纯色帧（RGBA，字节序待真机确认；先按 R,G,B,A 输出） */
    fun solid(r: Int, g: Int, b: Int, a: Int = 255): ByteArray {
        val out = ByteArray(PAYLOAD_SIZE)
        val rr = r.toByte(); val gg = g.toByte(); val bb = b.toByte(); val aa = a.toByte()
        var i = 0
        while (i < out.size) {
            out[i] = rr; out[i + 1] = gg; out[i + 2] = bb; out[i + 3] = aa
            i += 4
        }
        // 纯色旋转后仍是同一颜色；这里仍然执行，保证 solid/black 与 bars 走同一条翻转路径。
        if (flip180) flip180InPlace(out)
        return out
    }

    /** 竖色条 + 纵向渐变，用于确认分辨率/字节序/方向 */
    fun bars(): ByteArray {
        val colors = arrayOf(
            intArrayOf(255, 0, 0), intArrayOf(0, 255, 0), intArrayOf(0, 0, 255),
            intArrayOf(255, 255, 255), intArrayOf(0, 0, 0),
            intArrayOf(255, 255, 0), intArrayOf(0, 255, 255), intArrayOf(255, 0, 255)
        )
        val out = ByteArray(PAYLOAD_SIZE)
        val n = colors.size
        for (y in 0 until HEIGHT) {
            val shade = 255 - 200 * y / (HEIGHT - 1)
            for (x in 0 until WIDTH) {
                val c = colors[x * n / WIDTH]
                val o = (y * WIDTH + x) * 4
                out[o] = (c[0] * shade / 255).toByte()
                out[o + 1] = (c[1] * shade / 255).toByte()
                out[o + 2] = (c[2] * shade / 255).toByte()
                out[o + 3] = 255.toByte()
            }
        }
        // 180° 后色条左右顺序反转、纵向渐变上下反转，一眼能看出翻转是否生效
        if (flip180) flip180InPlace(out)
        return out
    }
}
