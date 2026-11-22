package com.wzqvip.glxsscast.lcd

import java.util.Locale

/**
 * `0xA1/0x07` 读回的 264 B EEPROM 数据解析。
 *
 * 布局（由 `liblcd.so` 的 `nativeProductGetInfo` / `ProductInfo.java` 反汇编得到，见
 * docs/firmware-extraction.md §4.3）：
 *
 * ```
 * [0..7]     8 B  设备返回头（不是产品信息）
 * [8..263]  256 B 产品信息：
 *     +0       1   platformID          （hex）
 *     +1..7    7   productID           （ASCII）
 *     +8..9    2   firmID              （hex）
 *     +10..25  16  bsnID               （ASCII，板级 SN）
 *     +26..41  16  psnID               （ASCII，产品 SN）
 *     +42..43  2   bomID               （ASCII）
 *     +44..51  8   ispID               （ASCII）
 *     +52..59  8   firmwareID          （ASCII，工厂 ID，不是 1.9.1 这种语义版本）
 *     +60..63  4   resolution          （厂商字段，语义未确认 → 原样 hex + 推测）
 *     +64..127 ?   ？？（本实现标 UNKNOWN）
 *     +128..255 128 customFields       （ASCII）
 * ```
 *
 * 解析策略：能确定的字段按表解析；不能确定的区域一律保留 hex 并在 UI 里标 `UNKNOWN`，
 * 不编造语义。
 */
data class ProductInfo(
    /** 设备返回的 264 B 原始数据 */
    val raw: ByteArray,
    /** raw[0..7]，原厂固定跳过 */
    val head: ByteArray,
    /** raw[8..263] 共 256 B */
    val info: ByteArray
) {

    companion object {
        const val TOTAL_LEN = 264
        const val HEAD_LEN = 8
        const val INFO_LEN = 256

        /** 从 264 B 控制传输结果解析；长度不足时返回 null */
        fun parse(raw: ByteArray): ProductInfo? {
            if (raw.size < HEAD_LEN + INFO_LEN) return null
            return ProductInfo(
                raw = raw.copyOf(HEAD_LEN + INFO_LEN),
                head = raw.copyOfRange(0, HEAD_LEN),
                info = raw.copyOfRange(HEAD_LEN, HEAD_LEN + INFO_LEN)
            )
        }

        private fun ascii(b: ByteArray, from: Int, len: Int): String {
            val end = minOf(b.size, from + len)
            if (from >= end) return ""
            val sb = StringBuilder(end - from)
            for (i in from until end) {
                val c = b[i].toInt() and 0xff
                sb.append(if (c in 0x20..0x7e) c.toChar() else '.')
            }
            return sb.toString().trim(' ', '.')
        }

        private fun hex(b: ByteArray, from: Int, len: Int): String {
            val end = minOf(b.size, from + len)
            if (from >= end) return ""
            val sb = StringBuilder((end - from) * 2)
            for (i in from until end) sb.append("%02x".format(b[i]))
            return sb.toString()
        }
    }

    private fun u(i: Int): Int = info[i].toInt() and 0xff

    /**
     * 256 B 产品信息是全 0xFF 或全 0x00 —— 说明这颗 EEPROM 从未写入过工厂信息。
     * 实测某些 G25 样机就是这种"空白 EEPROM"（0xA1/0x07 能正常返回 264 B，但内容全 0xFF）。
     */
    val isBlank: Boolean
        get() = info.all { it == 0xFF.toByte() } || info.all { it == 0x00.toByte() }

    /** 平台 ID（1 B hex） */
    val platformId: String get() = "%02x".format(u(0))

    /** 产品 ID（7 B ASCII） */
    val productId: String get() = ascii(info, 1, 7)

    /** firmID（2 B hex） */
    val firmId: String get() = hex(info, 8, 2)

    /** 板级序列号（16 B ASCII） */
    val bsnId: String get() = ascii(info, 10, 16)

    /** 产品序列号（16 B ASCII） */
    val psnId: String get() = ascii(info, 26, 16)

    /** BOM ID（2 B ASCII） */
    val bomId: String get() = ascii(info, 42, 2)

    /** ISP ID（8 B ASCII） */
    val ispId: String get() = ascii(info, 44, 8)

    /** 工厂固件 ID（8 B ASCII；不等于 0xA1/0x17 读到的运行版本） */
    val firmwareId: String get() = ascii(info, 52, 8)

    /** 厂商 resolution 字段原样（4 B hex）。语义未确认，不当作 640×400 的直接证据。 */
    val resolutionRaw: String get() = hex(info, 60, 4)

    /** resolution 的推测值：若 info[60]/[61] 可解释为 u16，标注为推测；空白 EEPROM 直接 UNKNOWN */
    val resolutionGuess: String
        get() {
            if (isBlank) return "UNKNOWN（EEPROM 未写入）"
            val a = u(60)
            val b = u(61)
            val c = u(62)
            val d = u(63)
            val w = (a shl 8) or b
            val h = (c shl 8) or d
            return if (w > 0 && h > 0) "$w×$h（推测，未验证）" else "UNKNOWN"
        }

    /** 64..127 未解析区域 */
    val reservedRaw: String get() = hex(info, 64, 64)

    /** 128..255 自定义字段（ASCII） */
    val customFields: String get() = ascii(info, 128, 128).replace('.', ' ').trim()

    /** 8 B 头（hex），用于判断头是否有含义 */
    val headHex: String get() = hex(head, 0, HEAD_LEN)

    /** 264 B 完整 hex（对话框「原始数据」用） */
    fun rawHex(): String = hex(raw, 0, raw.size)

    /** 一行摘要，状态栏用 */
    fun summary(): String {
        if (isBlank) return "EEPROM 为空（256 B 全 0xFF/0x00，未写入产品信息）"
        val parts = ArrayList<String>(6)
        if (productId.isNotEmpty()) parts += "产品=$productId"
        if (psnId.isNotEmpty()) parts += "SN=$psnId"
        if (bsnId.isNotEmpty() && bsnId != psnId) parts += "板SN=$bsnId"
        if (firmwareId.isNotEmpty()) parts += "工厂固件ID=$firmwareId"
        if (bomId.isNotEmpty()) parts += "BOM=$bomId"
        if (ispId.isNotEmpty()) parts += "ISP=$ispId"
        if (parts.isEmpty()) parts += "无可打印字段"
        return parts.joinToString(" · ")
    }

    /** 对话框里的多行详情 */
    fun detail(): String {
        fun line(k: String, v: String) = "%-12s %s".format(Locale.US, k, if (v.isEmpty()) "（空）" else v)
        return buildString {
            appendLine("EEPROM 产品信息（0xA1/0x07，264 B）")
            if (isBlank) {
                appendLine("结论：产品信息区全 0xFF/0x00 → 这颗 EEPROM 未写入工厂信息。")
                appendLine("（读命令本身成功返回 264 B，只是内容为空。）")
                appendLine()
            }
            appendLine("设备头(8B)  $headHex")
            appendLine(line("platformID", platformId))
            appendLine(line("productID", productId))
            appendLine(line("firmID", firmId))
            appendLine(line("bsnID", bsnId))
            appendLine(line("psnID", psnId))
            appendLine(line("bomID", bomId))
            appendLine(line("ispID", ispId))
            appendLine(line("firmwareID", firmwareId))
            appendLine(line("resolution", "${resolutionRaw} → ${resolutionGuess}"))
            appendLine(line("customFields", customFields))
            appendLine(line("[64..127]", "UNKNOWN  ${reservedRaw}"))
            append("原始 264B: ").append(rawHex())
        }
    }
}
