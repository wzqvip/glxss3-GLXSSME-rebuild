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

    /** 自动亮度开关 */
    fun setAutoBrightness(conn: UsbDeviceConnection, on: Boolean): Boolean {
        return conn.controlTransfer(REQ_CTRL, 0x10, if (on) 1 else 0, IFACE_AUX, ByteArray(1), 1, TIMEOUT) > 0
    }

    /** LED 指示灯开关 */
    fun setLed(conn: UsbDeviceConnection, on: Boolean): Boolean {
        return conn.controlTransfer(REQ_CTRL, 0x19, if (on) 1 else 0, IFACE_AUX, ByteArray(1), 1, TIMEOUT) > 0
    }

    /** 真实固件版本：0xA1/0x17，HID 接口(2)，24 字节 = 16B 版本串 + 8B ID */
    fun getFirmwareVersion(conn: UsbDeviceConnection): String? {
        val n = conn.controlTransfer(REQ_CTRL, 0x17, 0, IFACE_AUX, ByteArray(24), 24, TIMEOUT)
        if (n <= 0) return null
        val buf = ByteArray(24)
        val r = conn.controlTransfer(REQ_CTRL, 0x17, 0, IFACE_AUX, buf, 24, TIMEOUT)
        if (r <= 0) return null
        val s = String(buf, 0, minOf(16, r), Charsets.US_ASCII)
        return s.trim('\u0000', ' ', '\n')
    }

    /** 查询状态：返回 8 字节，byte[5]=当前亮度 */
    fun getStatus(conn: UsbDeviceConnection): ByteArray? = ctrl(conn, 0x04, 0, IFACE_LCD, 8)

    /** HID 报告（28 字节） */
    fun getHidReport(conn: UsbDeviceConnection): ByteArray? = ctrl(conn, 0x16, 0, IFACE_AUX, 28)
}

