package com.wzqvip.glxsscast.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.wzqvip.glxsscast.diag.Oplog
import java.util.concurrent.atomic.AtomicInteger

/**
 * GLXSS3 USB 连接与帧发送。
 *
 * 设备：VID 0x2E09 / PID 0x0030（正常模式，复合设备）
 * 选接口：优先 vendor-specific(0xFF) 且带 bulk OUT 的接口（if3）
 * 发送：1 MB 的帧**单次** bulk OUT（与原厂一致），失败/短写时只补齐**同一帧**的剩余字节，
 *       不重发已发内容；连续失败达 [FAIL_LIMIT] 次就收敛（交给上层停流 + 留 crash 现场）。
 *
 * **单 owner**（0.1.3 起）：LCD/UVC/HID 共用本对象的 [connection]。
 * UVC 的 `open(dev, null)` 已改成 `open(dev, usb.connection)`，同一设备不再有两个 fd
 * 各自 claim/操作（原厂也是单 fd）。claim 是「按 fd」的，单 fd 也消除了 UVC 抢走 HID
 * claim、关流后 0x17/0x16 变 -1 的问题。
 */
class UsbLink private constructor(private val ctx: Context) {

    companion object {
        const val VID = 0x2E09
        const val PID = 0x0030
        const val ACTION_PERMISSION = "com.wzqvip.glxsscast.USB_PERMISSION"
        private const val TAG = "UsbLink"
        private const val CHUNK = 16 * 1024
        private const val TIMEOUT_MS = 2000
        private const val FRAME_TIMEOUT_MS = 5000

        /** 连续多少帧发送失败就认定链路/固件异常（上层停流 + crash 快照） */
        const val FAIL_LIMIT = 3

        // 进程内共享同一个连接（Activity 与 CastService 共用）
        @Volatile private var shared: UsbLink? = null
        fun get(context: Context): UsbLink =
            shared ?: synchronized(this) {
                shared ?: UsbLink(context.applicationContext).also {
                    shared = it
                    Oplog.init(context.applicationContext)
                }
            }
    }

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile var device: UsbDevice? = null
        private set
    @Volatile private var conn: UsbDeviceConnection? = null
    private var intf: UsbInterface? = null
    private var hid: UsbInterface? = null
    private var epOut: UsbEndpoint? = null

    val isOpen: Boolean get() = conn != null && epOut != null

    /** 供控制命令 / UVC 复用 */
    val connection: UsbDeviceConnection? get() = conn

    // ---- 统计（UI / 实验脚本 / crash 现场用） ----
    private val frameSeq = AtomicInteger(0)
    @Volatile var framesSent: Long = 0; private set
    @Volatile var bytesSent: Long = 0; private set
    @Volatile var lastSendMs: Int = 0; private set
    @Volatile var failStreak: Int = 0; private set
    @Volatile var lastSendOk: Boolean = true; private set
    @Volatile var failTotal: Long = 0; private set

    /** 找 GLXSS3（优先精确匹配 VID/PID） */
    fun findDevice(): UsbDevice? {
        val list = usb.deviceList.values
        return list.firstOrNull { it.vendorId == VID && it.productId == PID }
            ?: list.firstOrNull { it.vendorId == VID }
    }

    fun hasPermission(dev: UsbDevice): Boolean = usb.hasPermission(dev)

    fun requestPermission(dev: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ACTION_PERMISSION).setPackage(ctx.packageName),
            flags
        )
        usb.requestPermission(dev, pi)
    }

    /** 打开设备并选好 bulk OUT 端点；需已获得权限。已打开同一设备时直接复用，不重开。 */
    fun open(dev: UsbDevice): Boolean {
        val cur = conn
        if (cur != null && device?.deviceId == dev.deviceId && epOut != null) {
            Oplog.logf("CONN", "open: already open ${dev.deviceName}, reuse fd")
            ensureHid()
            return true
        }
        close()
        if (!usb.hasPermission(dev)) {
            Oplog.logf("CONN", "open: no permission ${dev.deviceName}")
            Log.w(TAG, "no permission")
            return false
        }
        val c = usb.openDevice(dev) ?: run {
            Oplog.logf("CONN", "openDevice failed ${dev.deviceName}")
            Log.e(TAG, "openDevice failed")
            return false
        }
        // 1) 找 vendor-specific 接口的 bulk OUT
        var chosen: Triple<UsbInterface, UsbEndpoint, Int>? = null
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                val bulkOut = ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_OUT
                if (!bulkOut) continue
                val score = if (itf.interfaceClass == 0xFF) 2 else 1
                if (chosen == null || score > chosen!!.third) {
                    chosen = Triple(itf, ep, score)
                }
            }
        }
        val (itf, ep, _) = chosen ?: run {
            Oplog.log("CONN", "open: no bulk OUT endpoint")
            Log.e(TAG, "no bulk OUT endpoint")
            c.close()
            return false
        }
        val claimed = UsbGate.io("claim:if${itf.id}") { c.claimInterface(itf, true) }
        Oplog.logf("CONN", "claim LCD if=%d alt=%d -> %b", itf.id, itf.alternateSetting, claimed)
        if (!claimed) {
            Log.e(TAG, "claimInterface failed")
            c.close()
            return false
        }
        // 2) 顺带摘掉内核 hid-generic 对 HID 接口(2) 的占用（原厂启动时同样 claim iface2）。
        claimHid(c, dev)
        conn = c
        intf = itf
        epOut = ep
        device = dev
        Oplog.logf(
            "CONN", "opened %s if=%d ep=0x%02x mps=%d", dev.deviceName, itf.id,
            ep.address, ep.maxPacketSize
        )
        Log.i(TAG, "opened if=${itf.id} ep=0x${ep.address.toString(16)} mps=${ep.maxPacketSize}")
        return true
    }

    private fun claimHid(c: UsbDeviceConnection, dev: UsbDevice): Boolean {
        for (i in 0 until dev.interfaceCount) {
            val h = dev.getInterface(i)
            if (h.interfaceClass == UsbConstants.USB_CLASS_HID) {
                val ok = UsbGate.io("claim:hid") { c.claimInterface(h, true) }
                if (ok) hid = h
                Oplog.logf("CONN", "claim HID if=%d -> %b", h.id, ok)
                Log.i(TAG, "claim HID if=${h.id} -> $ok")
                return ok
            }
        }
        return false
    }

    /**
     * 重新拿回 HID 接口(2) 的 claim（单 fd 之后一般只在旧行为/异常时用得上）。
     * HID 的 class 控制请求（0xA1/0x16、0xA1/0x17）只有在「本 fd 拥有该接口」时才成功。
     */
    fun ensureHid(): Boolean {
        val c = conn ?: return false
        val d = device ?: return false
        return claimHid(c, d)
    }

    /**
     * 发送一帧（已含 16 字节头）。
     *
     * 策略（0.1.3）：
     * 1. 与原厂一致，**单次** bulkTransfer 整帧；
     * 2. 短写（>0 但 <size）只把**同一帧**剩下的字节分块补完（保持帧边界，不重发已发部分）；
     * 3. 补不齐 / 直接失败 → 记 failStreak，连续 [FAIL_LIMIT] 次 dump 一次 crash 现场，
     *    不再无限重试（上层看到连续失败会停流，避免把固件继续打崩）。
     */
    fun sendFrame(frame: ByteArray): Boolean {
        val c = conn ?: return false
        val ep = epOut ?: return false
        val seq = frameSeq.incrementAndGet()
        var total = 0
        val t0 = SystemClock.elapsedRealtime()
        var n: Int
        try {
            n = UsbGate.io("lcd_tx#$seq") {
                c.bulkTransfer(ep, frame, 0, frame.size, FRAME_TIMEOUT_MS)
            }
        } catch (t: Throwable) {
            Oplog.logf("LCD_TX", "seq=%d size=%d THREW %s", seq, frame.size, t.javaClass.simpleName)
            n = -1
        }
        if (n > 0) total = n
        if (n != frame.size && n > 0) {
            // 同一帧补齐（不重发已发内容，保证固件端帧边界不被破坏）
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (total < frame.size && SystemClock.elapsedRealtime() < deadline) {
                val len = minOf(CHUNK, frame.size - total)
                val m = try {
                    UsbGate.io("lcd_tx#$seq+") {
                        c.bulkTransfer(ep, frame, total, len, TIMEOUT_MS)
                    }
                } catch (t: Throwable) {
                    -1
                }
                if (m <= 0) break
                total += m
            }
        }
        val dt = (SystemClock.elapsedRealtime() - t0).toInt()
        val ok = total == frame.size
        lastSendMs = dt
        lastSendOk = ok
        if (ok) {
            framesSent++
            bytesSent += total
            failStreak = 0
        } else {
            failTotal++
            failStreak++
        }
        Oplog.logf(
            "LCD_TX", "seq=%d size=%d sent=%d rc=%s ms=%d streak=%d",
            seq, frame.size, total, if (ok) "OK" else "FAIL", dt, failStreak
        )
        if (!ok && failStreak == FAIL_LIMIT) {
            Oplog.dumpCrash("lcd_send_fail(consecutive=$failStreak,seq=$seq,rc_=${n})")
        }
        return ok
    }

    // ---- HID / 中断端点 ----
    fun openHidEndpoint(): UsbEndpoint? {
        val c = conn ?: return null
        val dev = device ?: return null
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN) {
                        val ok = UsbGate.io("claim:hid_ep") { c.claimInterface(itf, true) }
                        if (ok) {
                            hid = itf
                            Oplog.logf("HID", "interrupt endpoint 0x%02x claimed=%b", ep.address, ok)
                            return ep
                        }
                    }
                }
            }
        }
        return null
    }

    /** 拿 HID IN 中断端点（不 claim，用于已 claim 的场景） */
    fun hidInterruptEndpoint(): UsbEndpoint? {
        val dev = device ?: return null
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        ep.direction == UsbConstants.USB_DIR_IN
                    ) return ep
                }
            }
        }
        return null
    }

    fun readInterrupt(ep: UsbEndpoint, buf: ByteArray, timeoutMs: Int): Int =
        conn?.bulkTransfer(ep, buf, buf.size, timeoutMs) ?: -1

    fun close() {
        val c = conn
        if (c != null) {
            intf?.let {
                UsbGate.io("release:if${it.id}") { c.releaseInterface(it) }
            }
            hid?.let {
                UsbGate.io("release:hid") { c.releaseInterface(it) }
            }
            Oplog.log("CONN", "close fd (released iface ${intf?.id}, hid ${hid?.id})")
            try {
                c.close()
            } catch (ignored: Throwable) {
            }
        }
        conn = null
        intf = null
        hid = null
        epOut = null
        device = null
    }
}
