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
import android.util.Log

/**
 * GLXSS3 USB 连接与帧发送。
 *
 * 设备：VID 0x2E09 / PID 0x0030（正常模式，复合设备）
 * 选接口：优先 vendor-specific(0xFF) 且带 bulk OUT 的接口
 * 发送：1 MB 的帧分块写入（单次 ≤ 16 KB），失败重试
 */
class UsbLink private constructor(private val ctx: Context) {

    companion object {
        const val VID = 0x2E09
        const val PID = 0x0030
        const val ACTION_PERMISSION = "com.wzqvip.glxsscast.USB_PERMISSION"
        private const val TAG = "UsbLink"
        private const val CHUNK = 16 * 1024
        private const val TIMEOUT_MS = 2000

        // 进程内共享同一个连接（Activity 与 CastService 共用）
        @Volatile private var shared: UsbLink? = null
        fun get(context: Context): UsbLink =
            shared ?: synchronized(this) {
                shared ?: UsbLink(context.applicationContext).also { shared = it }
            }
    }

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    var device: UsbDevice? = null
        private set
    private var conn: UsbDeviceConnection? = null
    private var intf: UsbInterface? = null
    private var epOut: UsbEndpoint? = null

    val isOpen: Boolean get() = conn != null && epOut != null

    /** 供控制命令使用 */
    val connection: UsbDeviceConnection? get() = conn

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

    /** 打开设备并选好 bulk OUT 端点；需已获得权限 */
    fun open(dev: UsbDevice): Boolean {
        close()
        if (!usb.hasPermission(dev)) {
            Log.w(TAG, "no permission")
            return false
        }
        val c = usb.openDevice(dev) ?: run {
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
            Log.e(TAG, "no bulk OUT endpoint")
            c.close()
            return false
        }
        if (!c.claimInterface(itf, true)) {
            Log.e(TAG, "claimInterface failed")
            c.close()
            return false
        }
        conn = c
        intf = itf
        epOut = ep
        device = dev
        Log.i(TAG, "opened if=${itf.id} ep=0x${ep.address.toString(16)} mps=${ep.maxPacketSize}")
        return true
    }

    /** 发送一帧（已含 16 字节头）。原厂用单次 bulk 传输整帧（1,024,016 B，timeout 5000ms）；失败时退回分块。 */
    fun sendFrame(frame: ByteArray): Boolean {
        val c = conn ?: return false
        val ep = epOut ?: return false
        // 1) 整帧一次写（与原厂一致）
        var sent = c.bulkTransfer(ep, frame, 0, frame.size, 5000)
        if (sent == frame.size) return true
        if (sent > 0) {
            Log.w(TAG, "partial bulk write $sent/${frame.size}, falling back to chunks")
        } else {
            Log.w(TAG, "bulk write failed ($sent), falling back to chunks")
        }
        // 2) 分块重试
        var off = if (sent > 0) sent else 0
        while (off < frame.size) {
            val len = minOf(CHUNK, frame.size - off)
            val n = c.bulkTransfer(ep, frame, off, len, TIMEOUT_MS)
            if (n < 0) {
                Log.w(TAG, "chunk failed off=$off len=$len")
                return false
            }
            off += n
        }
        return true
    }

    fun close() {
        intf?.let { conn?.releaseInterface(it) }
        conn?.close()
        conn = null
        intf = null
        epOut = null
        device = null
    }
}

