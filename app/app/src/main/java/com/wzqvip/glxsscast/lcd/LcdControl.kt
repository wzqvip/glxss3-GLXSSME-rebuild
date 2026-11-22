package com.wzqvip.glxsscast.lcd

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface

/**
 * LCD 控制命令（真机抓包验证，见 docs/usb-capture.md）
 *
 * 全部为 bmRequestType=0xA1 (IN|CLASS|INTERFACE)，参数放在 wValue，
 * wLength=1 的哑读用于完成一次控制传输。
 */
object LcdControl {
    const val REQ_CTRL = 0xA1          // Device-to-host | Class | Interface
    const val IFACE_LCD = 3            // wIndex：LCD 厂商接口
    const val IFACE_AUX = 2            // wIndex：自动亮度 / LED / HID

    private const val TIMEOUT = 5000

    private fun ctrl(
        conn: UsbDeviceConnection,
        request: Int,
        value: Int,
        index: Int,
        length: Int
    ): ByteArray? {
        val buf = ByteArray(length)
        val n = conn.controlTransfer(REQ_CTRL, request, value, index, buf, length, TIMEOUT)
        return if (n > 0) buf else null
    }

    /** 设置亮度（100..254） */
    fun setBrightness(conn: UsbDeviceConnection, luminance: Int): Boolean {
        val v = luminance.coerceIn(100, 254)
        return conn.controlTransfer(REQ_CTRL, 0x01, v, IFACE_LCD, ByteArray(1), 1, TIMEOUT) > 0
    }

    /** 设置显示模式：1=关闭(logo/待机)，2=投屏开启 */
    fun setDisplayMode(conn: UsbDeviceConnection, mode: Int): Boolean {
        val m = mode.coerceIn(0, 2)
        return conn.controlTransfer(REQ_CTRL, 0x05, m, IFACE_LCD, ByteArray(1), 1, TIMEOUT) > 0
    }

    /**
     * 屏幕开关（原厂 `LCD.setSwitch()` → `liblcd.so` 0xA1/0x03 wIndex=3）。
     *
     * **重要**：原厂 LCDService 在停止投屏/浮层后会主动 `setSwitch(false)`，
     * 30 秒的 "Switch-LCD" 线程也会在「没有在投屏」时把屏关掉。
     * 屏 switch 关掉以后，只发帧/只切 displayMode **不会**重新点亮 —— 必须显式 setSwitch(true)。
     */
    fun setScreenOn(conn: UsbDeviceConnection, on: Boolean): Boolean =
        conn.controlTransfer(REQ_CTRL, 0x03, if (on) 1 else 0, IFACE_LCD, ByteArray(1), 1, TIMEOUT) > 0

    /** 屏幕旋转（原厂 `LCD.setRoll()` → 0xA1/0x02，参数放 wValue） */
    fun setRoll(conn: UsbDeviceConnection, roll: Int): Boolean =
        conn.controlTransfer(REQ_CTRL, 0x02, roll, IFACE_LCD, ByteArray(1), 1, TIMEOUT) > 0

    /**
     * `0xA1/0x04` 的 8 B 状态。
     *
     * 实测样本 `80 02 90 01 01 b4 06 00`（任务给的）与 `80 02 90 01 01 b9 0a 00`（抓包）：
     * ```
     * [0..1]  width      LE u16  → 640
     * [2..3]  height     LE u16  → 400
     * [4]     bopen      0/1     → 屏是否点亮
     * [5]     luminance  100..254→ 当前亮度（设置后会同步变化，可作 ACK）
     * [6..7]  rollover   LE u16  → 旋转值（样本 0x000a=10 / 0x0006=6）
     * ```
     * 其余（byte 7 的高字节部分 / 未来固件新增位）一律标 UNKNOWN，不猜语义。
     */
    data class Status(
        val width: Int,
        val height: Int,
        val screenOpen: Boolean,
        val luminance: Int,
        val roll: Int,
        /** raw[7]，roll 高字节或保留位；UI 标 UNKNOWN */
        val reserved: Int,
        val raw: ByteArray
    ) {
        fun hex(): String = raw.joinToString("") { "%02x".format(it) }

        /** 一行完整解析，状态栏直接显示 */
        fun describe(): String = buildString {
            append("状态 ${hex()} → 分辨率 ${width}×${height}")
            append(" · 屏=").append(if (screenOpen) "开" else "关")
            append(" · 亮度=$luminance")
            append(" · roll=$roll(byte6..7 LE)")
            append(" · byte7=0x%02x UNKNOWN".format(reserved))
        }
    }

    fun readStatus(conn: UsbDeviceConnection): Status? {
        val b = ctrl(conn, 0x04, 0, IFACE_LCD, 8) ?: return null
        if (b.size < 8) return null
        fun u(i: Int) = b[i].toInt() and 0xff
        return Status(
            width = u(0) or (u(1) shl 8),
            height = u(2) or (u(3) shl 8),
            screenOpen = u(4) != 0,
            luminance = u(5),
            roll = u(6) or (u(7) shl 8),
            reserved = u(7),
            raw = b
        )
    }

    /**
     * 读 EEPROM 产品信息：`0xA1/0x07`，wValue=0x0100，wIndex=3（LCD），264 B。
     *
     * 只读、安全。**不要**发 `0x21/0x06`（写 EEPROM，会改 SN/BOM）。
     * 返回数据布局见 [ProductInfo]。
     */
    fun readProductInfo(conn: UsbDeviceConnection): ProductInfo? {
        val buf = ByteArray(ProductInfo.TOTAL_LEN)
        val n = conn.controlTransfer(
            REQ_CTRL, 0x07, 0x0100, IFACE_LCD, buf, ProductInfo.TOTAL_LEN, TIMEOUT
        )
        if (n <= 0) return null
        return ProductInfo.parse(buf)
    }

    /** 自动亮度开关 */
    fun setAutoBrightness(conn: UsbDeviceConnection, on: Boolean): Boolean {
        return conn.controlTransfer(REQ_CTRL, 0x10, if (on) 1 else 0, IFACE_AUX, ByteArray(1), 1, TIMEOUT) > 0
    }

    /** LED 指示灯开关 */
    fun setLed(conn: UsbDeviceConnection, on: Boolean): Boolean {
        return conn.controlTransfer(REQ_CTRL, 0x19, if (on) 1 else 0, IFACE_AUX, ByteArray(1), 1, TIMEOUT) > 0
    }

    /** 真实固件版本：0xA1/0x17，HID 接口(2)，24 字节 = 16B 版本串 + 8B ID
     *  （HID 接口未被 claim 时该请求会返回 -1；UsbLink.open / UvcCamera.open 都会 claim 一次） */
    fun getFirmwareVersion(conn: UsbDeviceConnection): String? {
        val buf = ByteArray(24)
        val n = conn.controlTransfer(REQ_CTRL, 0x17, 0, IFACE_AUX, buf, 24, TIMEOUT)
        if (n <= 0) return null
        val s = String(buf, 0, minOf(16, n), Charsets.US_ASCII)
        return s.trim('\u0000', ' ', '\n')
    }

    /** 查询状态：返回 8 字节，byte[5]=当前亮度 */
    fun getStatus(conn: UsbDeviceConnection): ByteArray? = ctrl(conn, 0x04, 0, IFACE_LCD, 8)

    /** HID 报告（28 字节） */
    fun getHidReport(conn: UsbDeviceConnection): ByteArray? = ctrl(conn, 0x16, 0, IFACE_AUX, 28)
}

