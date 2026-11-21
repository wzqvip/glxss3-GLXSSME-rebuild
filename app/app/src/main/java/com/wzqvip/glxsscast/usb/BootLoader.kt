package com.wzqvip.glxsscast.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.llvision.glass3.sdk.boot.Boot
import com.llvision.glass3.sdk.boot.BootCallback

/** 引导模式(03e7:2150)检测与固件加载。 */
object BootLoader {
    const val BOOT_VID = 0x03E7
    const val BOOT_PID = 0x2150
    private const val TAG = "BootLoader"

    fun findBootDevice(usb: UsbManager): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == BOOT_VID && it.productId == BOOT_PID }

    /** 解析 /dev/bus/usb/001/002 → (bus=1, addr=2) */
    private fun parseBusAddr(name: String): Pair<Int, Int> {
        val parts = name.trim('/').split('/')
        val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1
        val addr = parts.getOrNull(parts.size - 1)?.toIntOrNull() ?: 1
        return bus to addr
    }

    fun flash(
        ctx: Context,
        dev: UsbDevice,
        imgPath: String,
        keyPath: String,
        onDone: (Boolean, String) -> Unit
    ) {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = usb.openDevice(dev)
        if (conn == null) {
            onDone(false, "无法打开引导模式设备")
            return
        }
        val fd = conn.fileDescriptor
        val (bus, addr) = parseBusAddr(dev.deviceName)
        val usbfs = dev.deviceName
        Thread {
            try {
                val r = Boot.bootInit(dev.vendorId, dev.productId, fd, bus, addr, usbfs)
                Log.i(TAG, "bootInit=$r fd=$fd bus=$bus addr=$addr usbfs=$usbfs")
                if (r != 0) {
                    onDone(false, "bootInit 失败: $r")
                    return@Thread
                }
                val r2 = Boot.bootDownload(imgPath, keyPath, object : BootCallback {
                    override fun onFail(code: Int, msg: String?) {
                        Log.e(TAG, "bootDownload fail $code $msg")
                        onDone(false, "刷写失败: $code $msg")
                    }

                    override fun onSuccess() {
                        Log.i(TAG, "bootDownload success")
                        onDone(true, "固件加载成功，等待设备重新枚举为 2e09:0030")
                    }
                })
                Log.i(TAG, "bootDownload ret=$r2")
                if (r2 != 0) onDone(false, "bootDownload 返回 $r2")
            } catch (e: Throwable) {
                Log.e(TAG, "flash exception", e)
                onDone(false, "异常: ${e.message}")
            } finally {
                try {
                    conn.close()
                } catch (_: Exception) {
                }
            }
        }.start()
    }
}
