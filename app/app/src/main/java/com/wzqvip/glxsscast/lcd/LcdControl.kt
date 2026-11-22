package com.wzqvip.glxsscast.lcd

import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock
import com.wzqvip.glxsscast.diag.Oplog
import com.wzqvip.glxsscast.usb.UsbGate

/**
 * LCD / HID 控制命令（真机抓包验证，见 docs/usb-capture.md、docs/ch-hid.md）
 *
 * 全部为 bmRequestType=0xA1 (IN|CLASS|INTERFACE)，参数放在 wValue；
 * 每条请求都走 [UsbGate]（与 1 MB 帧的 bulk OUT 互斥）并记进 [Oplog]
 * （opcode / wValue / wIndex / 返回码 / 耗时），崩溃后能从 oplog 里看到最后发了什么。
 */
object LcdControl {
    const val REQ_CTRL = 0xA1          // Device-to-host | Class | Interface
    const val IFACE_LCD = 3            // wIndex：LCD 厂商接口
    const val IFACE_AUX = 2            // wIndex：自动亮度 / LED / HID

    /** 小控制请求的超时：不要 5 s 把投屏帧堵死 */
    private const val TIMEOUT = 2000
    private const val TIMEOUT_EEPROM = 5000

    /**
     * 统一控制传输入口：串行化 + 全量日志。
     * @return controlTransfer 的返回值（成功 = 实际字节数；-1 = 失败/超时）
     */
    private fun xfer(
        conn: UsbDeviceConnection,
        request: Int,
        value: Int,
        index: Int,
        buf: ByteArray?,
        length: Int,
        timeout: Int = TIMEOUT
    ): Int {
        val t0 = SystemClock.elapsedRealtime()
        val n = UsbGate.io("ctrl:0x%02x".format(request)) {
            conn.controlTransfer(REQ_CTRL, request, value, index, buf, length, timeout)
        }
        val dt = SystemClock.elapsedRealtime() - t0
        val hex = if (n in 1..32 && buf != null) {
            " data=" + buf.copyOfRange(0, n).joinToString("") { "%02x".format(it) }
        } else ""
        Oplog.logf(
            "CTRL",
            "op=0x%02x wValue=0x%04x wIndex=%d len=%d rc=%d ms=%d%s",
            request, value, index, length, n, dt, hex
        )
        return n
    }

    /** 设置亮度（100..254） */
    fun setBrightness(conn: UsbDeviceConnection, luminance: Int): Boolean {
        val v = luminance.coerceIn(100, 254)
        return xfer(conn, 0x01, v, IFACE_LCD, ByteArray(1), 1) > 0
    }

    /** 设置显示模式：1=关闭(logo/待机)，2=投屏开启 */
    fun setDisplayMode(conn: UsbDeviceConnection, mode: Int): Boolean {
        val m = mode.coerceIn(0, 2)
        return xfer(conn, 0x05, m, IFACE_LCD, ByteArray(1), 1) > 0
    }

    /**
     * 屏幕开关（原厂 `LCD.setSwitch()` → `nativeLCDSetSwitch` → `lcd_power_switch`）：
     * `0xA1 / bRequest=0x02 / wValue=0|1 / wIndex=3 / wLength=1`。
     *
     * **重要**：原厂 LCDService 在停止投屏/浮层后会主动 `setSwitch(false)`，
     * 30 秒的 "Switch-LCD" 线程也会在「没有在投屏」时把屏关掉。
     * 屏 switch 关掉以后，只发帧/只切 displayMode **不会**重新点亮 —— 必须显式 setSwitch(true)。
     *
     * 注意：旧文档/旧代码把 0x02 当 roll、0x03 当开关，正好写反了；见 [setRoll]。
     */
    fun setScreenOn(conn: UsbDeviceConnection, on: Boolean): Boolean =
        xfer(conn, 0x02, if (on) 1 else 0, IFACE_LCD, ByteArray(1), 1) > 0

    /**
     * 屏幕旋转（原厂 `LCD.setRoll()` → `nativeLCDSetRoll` → `lcd_set_roll`）：
     * `0xA1 / bRequest=0x03 / wValue=roll&0xFF / wIndex=3 / wLength=1`，`ret==1` 才算 ACK。
     *
     * 这是对 `liblcd.so` 的 JNI 注册表逐项回溯得到的（armeabi-v7a/arm64-v8a 一致），
     * 不是按函数顺序猜的：`nativeLCDSetRoll` 的 wrapper 明确 `bl lcd_set_roll`，
     * 而 `lcd_set_roll` 里 `libusb_control_transfer` 的 bRequest 是 3。
     *
     * 只保留给诊断（DebugReceiver/ExpHarness）；普通 UI 的「画面翻转」走软件 180°。
     */
    fun setRoll(conn: UsbDeviceConnection, roll: Int): Boolean =
        xfer(conn, 0x03, roll and 0xFF, IFACE_LCD, ByteArray(1), 1) > 0

    /**
     * `0xA1/0x04` 的 8 B 状态。
     *
     * 实测样本 `80 02 90 01 01 b4 06 00`：
     * ```
     * [0..1]  width      LE u16  → 640
     * [2..3]  height     LE u16  → 400
     * [4]     bopen      0/1     → 屏是否点亮
     * [5]     luminance  100..254→ 当前亮度（设置后会同步变化，可作 ACK）
     * [6..7]  rollover   LE u16  → 旋转值
     * ```
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
        val n = xfer(
            conn, 0x07, 0x0100, IFACE_LCD, buf, ProductInfo.TOTAL_LEN, TIMEOUT_EEPROM
        )
        if (n <= 0) return null
        return ProductInfo.parse(buf)
    }

    /** 自动亮度开关 */
    fun setAutoBrightness(conn: UsbDeviceConnection, on: Boolean): Boolean {
        return xfer(conn, 0x10, if (on) 1 else 0, IFACE_AUX, ByteArray(1), 1) > 0
    }

    /** LED 指示灯开关 */
    fun setLed(conn: UsbDeviceConnection, on: Boolean): Boolean {
        return xfer(conn, 0x19, if (on) 1 else 0, IFACE_AUX, ByteArray(1), 1) > 0
    }

    /** 真实固件版本：0xA1/0x17，HID 接口(2)，24 字节 = 16B 版本串 + 8B ID */
    fun getFirmwareVersion(conn: UsbDeviceConnection): String? {
        val buf = ByteArray(24)
        val n = xfer(conn, 0x17, 0, IFACE_AUX, buf, 24)
        if (n <= 0) return null
        val s = String(buf, 0, minOf(16, n), Charsets.US_ASCII)
        return s.trim('\u0000', ' ', '\n')
    }

    /** 查询状态：返回 8 字节，byte[5]=当前亮度 */
    fun getStatus(conn: UsbDeviceConnection): ByteArray? = ctrl(conn, 0x04, 0, IFACE_LCD, 8)

    /**
     * HID 报告（28 字节，0xA1/0x16 wIndex=2）。**必须刚好 28 字节**才算成功：
     * 短读/多读说明 HID 接口被抢或固件异常，交给调用方计数/告警。
     */
    fun readHidReport(conn: UsbDeviceConnection, timeoutMs: Int = TIMEOUT): ByteArray? {
        val buf = ByteArray(28)
        val n = xfer(conn, 0x16, 0, IFACE_AUX, buf, 28, timeoutMs)
        return if (n == 28) buf else null
    }

    /** 旧的宽松版本（保留兼容）：任意正长度就返回 */
    fun getHidReport(conn: UsbDeviceConnection): ByteArray? = ctrl(conn, 0x16, 0, IFACE_AUX, 28)

    /**
     * `0xA1/0x06`：IMU 属性（16 B，wIndex=2）。
     *
     * 布局（docs/ch-hid.md §4/§5.3，真机验证）：
     * ```
     * 0  u8  acc_status     1  u8  acc_enabled   2  u8  acc_fsr (0..3)
     * 3  u8  gyro_status    4  u8  gyro_enabled  5  u8  gyro_fsr (0..3)
     * 8  u64 chip_id (LE)
     * ```
     * `*_fsr` 就是 28 B 报告里 acc/gyro 的换算档位（±2/4/8/16g、±250/500/1000/2000dps）。
     */
    data class ImuAttr(
        val accStatus: Int,
        val accEnabled: Boolean,
        val accFsr: Int,
        val gyroStatus: Int,
        val gyroEnabled: Boolean,
        val gyroFsr: Int,
        val chipId: Long,
        val raw: ByteArray
    ) {
        fun hex(): String = raw.joinToString("") { "%02x".format(it) }
        val fsrValid: Boolean get() = accFsr in 0..3 && gyroFsr in 0..3
    }

    fun readImuAttributes(conn: UsbDeviceConnection): ImuAttr? {
        val b = ctrl(conn, 0x06, 0, IFACE_AUX, 16) ?: return null
        if (b.size < 16) return null
        fun u(i: Int) = b[i].toInt() and 0xff
        var chip = 0L
        for (i in 0..7) chip = chip or ((b[8 + i].toLong() and 0xff) shl (8 * i))
        return ImuAttr(
            accStatus = u(0),
            accEnabled = u(1) != 0,
            accFsr = u(2),
            gyroStatus = u(3),
            gyroEnabled = u(4) != 0,
            gyroFsr = u(5),
            chipId = chip,
            raw = b.copyOf(16)
        )
    }

    /**
     * 使能传感器：`0xA1/0x10` 光感(自动亮度) / `0x11` 罗盘 / `0x12` 加速度 / `0x14` 陀螺。
     * 不使能时 28 B 报告里 IMU/罗盘全是 0。
     */
    fun enableSensors(conn: UsbDeviceConnection): Boolean {
        var ok = true
        for (op in intArrayOf(0x10, 0x11, 0x12, 0x14)) {
            ok = xfer(conn, op, 1, IFACE_AUX, ByteArray(1), 1) > 0 && ok
        }
        return ok
    }

    /**
     * `0xA1/0x18`：系统复位（原厂 `sensor_set_system_reset_after_500_ms`）。
     *
     * **危险**：会让眼镜固件重启、掉回引导模式 `03E7:2150`（之后必须重灌固件）。
     * 只用于实验/恢复路径，不放进普通 UI。
     */
    fun systemReset(conn: UsbDeviceConnection): Boolean =
        xfer(conn, 0x18, 1, IFACE_AUX, ByteArray(1), 1) > 0

    /**
     * 原厂 UVC 初始化时会发的一条 vendor XU 写：
     * `0x21/0x01 wValue=0x0d0d wIndex=0x0400 data=01`（entity 4，相机侧 1 字节开关）。
     * 抓包显示健康设备不发也出流，但「相机被挂起」时它可能是唤醒开关（待验证）。
     */
    fun vendorXuSwitch(conn: UsbDeviceConnection, on: Boolean): Boolean {
        val data = byteArrayOf(if (on) 1 else 0)
        val t0 = SystemClock.elapsedRealtime()
        val n = UsbGate.io("xu:0x0d0d") {
            conn.controlTransfer(0x21, 0x01, 0x0d0d, 0x0400, data, 1, TIMEOUT)
        }
        Oplog.logf(
            "CTRL", "op=0x01(XU) wValue=0x0d0d wIndex=0x0400 data=%02x rc=%d ms=%d",
            data[0], n, SystemClock.elapsedRealtime() - t0
        )
        return n >= 0
    }

    /**
     * 清端点 HALT：`bmRequestType=0x02`（Host→Device | Standard | Endpoint）、
     * `bRequest=0x01`（CLEAR_FEATURE）、`wValue=0`（ENDPOINT_HALT）、`wIndex=端点地址`。
     *
     * UVC 停止后对 EP 0x81 做一次，避免上一次 bulk IN 被取消后端点停在 HALT 状态、
     * 下一次 commit 后一个字节都不来（这是「半死」的一个可恢复成因）。
     */
    fun clearEndpointHalt(conn: UsbDeviceConnection, epAddress: Int): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        val n = UsbGate.io("clear_halt:0x%02x".format(epAddress)) {
            conn.controlTransfer(0x02, 0x01, 0x0000, epAddress, ByteArray(0), 0, TIMEOUT)
        }
        Oplog.logf(
            "EP",
            "CLEAR_FEATURE(ENDPOINT_HALT) ep=0x%02x rc=%d ms=%d",
            epAddress, n, SystemClock.elapsedRealtime() - t0
        )
        return n >= 0
    }

    private fun ctrl(
        conn: UsbDeviceConnection,
        request: Int,
        value: Int,
        index: Int,
        length: Int
    ): ByteArray? {
        val buf = ByteArray(length)
        val n = xfer(conn, request, value, index, buf, length)
        return if (n > 0) buf.copyOf(n) else null
    }
}
