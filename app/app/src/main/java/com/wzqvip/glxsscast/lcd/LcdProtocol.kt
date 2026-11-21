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
        return out
    }
}
