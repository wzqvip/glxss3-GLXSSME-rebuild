package com.wzqvip.glxsscast.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 纯自研的 Myriad 固件引导（协议见 docs/firmware-boot-protocol.md）：
 *  - 设备：03E7:2150（Myriad ROM boot）
 *  - EP1 OUT (0x01)，每包 ≤1024 B，超时 10s，纯 bulk 单向写
 *  - img 尾部 0x80000 字节是 AES-128-ECB 加密，key = "b5Q8JBFRYYIRawl3"
 */
object MyriadBootLoader {
    const val BOOT_VID = 0x03E7
    const val BOOT_PID = 0x2150
    private const val TAG = "MyriadBoot"
    private const val CHUNK = 1024
    private const val TIMEOUT = 10000
    private const val ENC_START = 0x0F4590
    private const val ENC_LEN = 0x80000
    private val AES_KEY = "b5Q8JBFRYYIRawl3".toByteArray(Charsets.US_ASCII)

    fun findBootDevice(usb: UsbManager): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == BOOT_VID && it.productId == BOOT_PID }

    /** 解密尾部 512 KiB；返回要发送的完整镜像（长度不变，尾部不足补 0） */
    fun prepareImage(img: ByteArray): ByteArray {
        val out = img.copyOf()
        val start = minOf(ENC_START, out.size)
        val len = minOf(ENC_LEN, out.size - start)
        if (len <= 0) return out
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
            val plain = cipher.doFinal(out, start, len)
            System.arraycopy(plain, 0, out, start, plain.size)
            // 去掉填充后剩余部分清零（设备按容器长度忽略尾部）
            for (i in start + plain.size until out.size) out[i] = 0
            Log.i(TAG, "decrypted tail: cipher=$len plain=${plain.size}")
            out
        } catch (e: Exception) {
            Log.e(TAG, "decrypt failed", e)
            out
        }
    }

    fun flash(
        ctx: Context,
        dev: UsbDevice,
        imgPath: String,
        progress: (Int, String) -> Unit,
        done: (Boolean, String) -> Unit
    ) {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        Thread {
            var conn: android.hardware.usb.UsbDeviceConnection? = null
            try {
                val raw = File(imgPath).readBytes()
                progress(0, "读取固件 ${raw.size} 字节")
                val img = prepareImage(raw)

                conn = usb.openDevice(dev)
                if (conn == null) { done(false, "无法打开引导设备"); return@Thread }
                // 找 bulk OUT 端点（EP1 OUT = 0x01）
                var intf: android.hardware.usb.UsbInterface? = null
                var ep: android.hardware.usb.UsbEndpoint? = null
                for (i in 0 until dev.interfaceCount) {
                    val itf = dev.getInterface(i)
                    for (e in 0 until itf.endpointCount) {
                        val x = itf.getEndpoint(e)
                        if (x.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            x.direction == UsbConstants.USB_DIR_OUT
                        ) { intf = itf; ep = x }
                    }
                }
                if (intf == null || ep == null) { done(false, "没有 bulk OUT 端点"); return@Thread }
                if (!conn.claimInterface(intf, true)) { done(false, "claimInterface 失败"); return@Thread }

                var off = 0
                var retry = 0
                while (off < img.size) {
                    val n = minOf(CHUNK, img.size - off)
                    val sent = conn.bulkTransfer(ep, img, off, n, TIMEOUT)
                    if (sent < 0) {
                        retry++
                        Log.w(TAG, "bulk fail off=$off retry=$retry")
                        if (retry > 3) { done(false, "传输失败 off=$off ret=$sent"); return@Thread }
                        Thread.sleep(50)
                        continue
                    }
                    off += sent
                    if (off % (CHUNK * 64) == 0) progress(off * 100 / img.size, "写入 $off/${img.size}")
                }
                progress(100, "传输完成，等待设备重启…")
                done(true, "固件已发送（${img.size} 字节），等待重枚举为 2E09:0030")
            } catch (e: Throwable) {
                Log.e(TAG, "flash failed", e)
                done(false, "异常: ${e.message}")
            } finally {
                try { conn?.close() } catch (_: Exception) {}
            }
        }.start()
    }
}
