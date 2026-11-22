package com.wzqvip.glxsscast.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.wzqvip.glxsscast.diag.Oplog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 纯自研的 Myriad 固件引导（协议见 docs/firmware-boot-protocol.md）：
 *  - 设备：03E7:2150（Myriad ROM boot）
 *  - EP1 OUT (0x01)，每包 ≤1024 B，超时 10s，纯 bulk 单向写
 *  - img 尾部 0x80000 字节是 AES-128-ECB 加密，key = "b5Q8JBFRYYIRawl3"
 *
 * 镜像来源：0.1.5 起固件随 APK 分发（`assets/firmware/G25_MainFW_1.9.1.img`），
 * 首次运行由 [Firmware] 解包到 `filesDir/firmware/`，
 * 这里只负责「校验入参 + 灌写」，**不碰任何外部存储路径**。
 *
 * 健壮性约定：本方法不会把异常抛给调用方 —— 参数错误/设备被拔/USB 打不开/写入失败
 * 都走 [done] 回调（且**只回调一次**），由上层在状态栏提示。
 */
object MyriadBootLoader {
    const val BOOT_VID = 0x03E7
    const val BOOT_PID = 0x2150
    private const val TAG = "MyriadBoot"
    private const val CHUNK = 1024
    private const val TIMEOUT = 10000
    private const val ENC_START = 0x0F4590
    private const val ENC_LEN = 0x80000

    /** 镜像合理区间：1.9.1 = 1,525,136 B；给 1.7.4（1,500,000 左右）留余量 */
    private const val MIN_IMG = 1_000_000L
    private const val MAX_IMG = 16L * 1024 * 1024

    /** MA2x 容器魔数（`G25_MainFW_*.img` 前 4 字节） */
    private val MAGIC = byteArrayOf(0x4D, 0x41, 0x32, 0x78) // "MA2x"

    private val AES_KEY = "b5Q8JBFRYYIRawl3".toByteArray(Charsets.US_ASCII)

    fun findBootDevice(usb: UsbManager): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == BOOT_VID && it.productId == BOOT_PID }

    /**
     * 入参校验（大小 + `MA2x` 魔数）。返回 null = 通过，否则是可显示的错误文案。
     *
     * 之所以在灌写前查：路径不存在时旧的实现会在线程里 `readBytes()` 抛异常，
     * 表现为「点了没反应 / 状态乱跳」，而不是一条明确的提示。
     */
    fun validate(img: File): String? {
        if (!img.isFile) return "固件文件不存在: ${img.absolutePath}"
        val len = img.length()
        if (len < MIN_IMG || len > MAX_IMG) {
            return "固件大小异常: $len B（期望约 1.5 MB）"
        }
        val magic = try {
            img.inputStream().use { input ->
                val b = ByteArray(4)
                if (input.read(b) != 4) return "固件头读取失败（文件被截断？）"
                b
            }
        } catch (t: Throwable) {
            return "固件读取失败: ${t.javaClass.simpleName}: ${t.message}"
        }
        if (!magic.contentEquals(MAGIC)) {
            return "不是 G25 固件（魔数 ${magic.joinToString("") { "%02x".format(it) }} != 4d413278）"
        }
        return null
    }

    /** 解密尾部 512 KiB；返回要发送的完整镜像（长度不变，尾部不足补 0） */
    fun prepareImage(img: ByteArray): ByteArray {
        val out = img.copyOf()
        // 加密尾 = 文件末尾 0x80000 字节（1.9.1 是 0x0F4590，1.7.4 是 0x0F3AF0 —— 用 size 推算，别写死）
        val start = maxOf(0, out.size - ENC_LEN)
        val len = minOf(ENC_LEN, out.size - start)
        if (len <= 0) return out
        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
            val plain = cipher.doFinal(out, start, len)
            System.arraycopy(plain, 0, out, start, plain.size)
            // 保留完整 0x80000 字节（含 PKCS#7 填充字节），总长 = 0x174590
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
        imgFile: File,
        progress: (Int, String) -> Unit,
        done: (Boolean, String) -> Unit
    ) {
        // done 只回调一次：提前返回/异常/传输失败都可能落到回调上
        val finished = AtomicBoolean(false)
        val finish: (Boolean, String) -> Unit = { ok, msg ->
            if (finished.compareAndSet(false, true)) {
                try {
                    done(ok, msg)
                } catch (t: Throwable) {
                    Log.w(TAG, "done callback threw", t)
                }
            }
        }
        // 1) 入参校验：不建线程、不碰 USB
        validate(imgFile)?.let {
            finish(false, it)
            return
        }
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        Oplog.init(ctx)
        // 2) 后台线程灌写
        Thread({
            var conn: UsbDeviceConnection? = null
            try {
                val raw = imgFile.readBytes()
                Oplog.logf(
                    "BOOT", "flash start dev=%s img=%s bytes=%d",
                    dev.deviceName, imgFile.name, raw.size
                )
                safeProgress(progress, 0, "读取固件 ${raw.size} 字节")
                val img = prepareImage(raw)

                conn = try {
                    usb.openDevice(dev)
                } catch (t: Throwable) {
                    // 权限被系统收回 / 设备已拔：openDevice 会抛 SecurityException / IllegalArgumentException
                    Oplog.logf("BOOT", "openDevice threw %s: %s", t.javaClass.simpleName, t.message)
                    null
                }
                if (conn == null) {
                    Oplog.log("BOOT", "openDevice(boot) failed")
                    finish(false, "无法打开引导设备（可能已拔出或没有 USB 权限）")
                    return@Thread
                }
                // 找 bulk OUT 端点（EP1 OUT = 0x01）
                var intf: android.hardware.usb.UsbInterface? = null
                var ep: android.hardware.usb.UsbEndpoint? = null
                for (i in 0 until dev.interfaceCount) {
                    val itf = dev.getInterface(i) ?: continue
                    for (e in 0 until itf.endpointCount) {
                        val x = itf.getEndpoint(e)
                        if (x != null && x.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            x.direction == UsbConstants.USB_DIR_OUT
                        ) {
                            intf = itf
                            ep = x
                        }
                    }
                }
                if (intf == null || ep == null) {
                    Oplog.log("BOOT", "no bulk OUT endpoint on boot device")
                    finish(false, "没有 bulk OUT 端点（设备不在引导模式？）")
                    return@Thread
                }
                val claimed = try {
                    conn.claimInterface(intf, true)
                } catch (t: Throwable) {
                    Oplog.logf("BOOT", "claimInterface threw %s: %s", t.javaClass.simpleName, t.message)
                    false
                }
                if (!claimed) {
                    Oplog.log("BOOT", "claimInterface(boot) failed")
                    finish(false, "claimInterface 失败")
                    return@Thread
                }
                Oplog.logf("BOOT", "boot iface=%d ep=0x%02x writing %d bytes", intf.id, ep.address, img.size)

                var off = 0
                var retry = 0
                while (off < img.size) {
                    val n = minOf(CHUNK, img.size - off)
                    val sent = try {
                        conn.bulkTransfer(ep, img, off, n, TIMEOUT)
                    } catch (t: Throwable) {
                        Oplog.logf("BOOT", "bulkTransfer threw %s", t.javaClass.simpleName)
                        -1
                    }
                    if (sent < 0) {
                        retry++
                        Log.w(TAG, "bulk fail off=$off retry=$retry")
                        Oplog.logf("BOOT", "bulk fail off=%d retry=%d", off, retry)
                        if (retry > 3) {
                            Oplog.logf("BOOT", "transfer failed off=%d ret=%d", off, sent)
                            finish(false, "传输失败 off=$off ret=$sent")
                            return@Thread
                        }
                        Thread.sleep(50)
                        continue
                    }
                    off += sent
                    if (off % (CHUNK * 64) == 0) {
                        Oplog.logf("BOOT", "written %d/%d", off, img.size)
                        safeProgress(progress, off * 100 / img.size, "写入 $off/${img.size}")
                    }
                }
                Oplog.logf("BOOT", "flash sent %d bytes OK, waiting re-enum", img.size)
                safeProgress(progress, 100, "传输完成，等待设备重启…")
                finish(true, "固件已发送（${img.size} 字节），等待重枚举为 2E09:0030")
            } catch (e: Throwable) {
                Log.e(TAG, "flash failed", e)
                Oplog.logf("BOOT", "flash exception %s: %s", e.javaClass.simpleName, e.message)
                finish(false, "异常: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try {
                    conn?.close()
                } catch (_: Throwable) {
                }
            }
        }, "MyriadFlash").start()
    }

    private fun safeProgress(progress: (Int, String) -> Unit, p: Int, msg: String) {
        try {
            progress(p, msg)
        } catch (t: Throwable) {
            Log.w(TAG, "progress callback threw", t)
        }
    }
}
