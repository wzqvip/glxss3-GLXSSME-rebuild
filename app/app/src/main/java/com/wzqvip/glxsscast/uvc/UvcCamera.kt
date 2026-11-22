package com.wzqvip.glxsscast.uvc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import com.wzqvip.glxsscast.diag.Oplog
import com.wzqvip.glxsscast.lcd.LcdControl
import com.wzqvip.glxsscast.usb.UsbGate
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 录像等旁路消费者：每帧解码完成后额外回调一次。
 *
 * 在 UVC 收流线程上调用（Bitmap 是 A/B 双缓冲复用的，下一帧会被覆盖）
 * 实现必须快速返回，需要保留请自行拷贝像素。
 */
fun interface UvcFrameTap {
    fun onUvcFrame(bitmap: Bitmap, width: Int, height: Int, ptsUs: Long)
}

/**
 * 纯 Kotlin 的 UVC 摄像头（GLXSS G25）实现 —— **不依赖任何 native 库**。
 *
 * 数据通路：
 * ```
 *  INTF1 (VideoStreaming, alt=1, ep 0x81 BULK IN)
 *      └─ UVC payload(2~12B header + data) ─► 组装成整帧
 *           ├─ MJPEG  ─► BitmapFactory.decodeByteArray
 *           └─ 未压缩  ─► YV12/I420/NV12/YUY2 ─► ARGB IntArray ─► Bitmap
 * ```
 *
 * 关键点（真机 / 厂商日志 + Frida 抓包证据，详见 docs/uvc-camera-plan.md §8）：
 * - 设备自称 UVC 1.10，VS 接口用 **bulk** 端点（不是 isochronous），
 *   INTF1 只有 alt=0 就带 0x81（mps=512），所以 `setInterface(alt=0)` 即可；
 * - 原厂 libuvc 每次 `libusb_submit_transfer` 就是 `frameBufferSize=460822`
 *   = `dwMaxVideoFrameSize(460800) + 22`，设备侧一帧 = `460812 = 460800 + 12B header`；
 * - payload header 是标准 UVC 1.1 的 12 字节头（PTS + SCR 都在）：
 *   `0C 0F|0E <PTS:4> <SCR:6>`  → `bHeaderLength=12`、`bmHeaderInfo` bit1(EOF)=1、
 *   bit0(FID) 逐帧翻转、bit2/bit3(PTS/SCR)=1；后 460800 字节是 YV12
 *   （Y | V | U，实测与原厂画面一致）；**整帧只有帧首这一个 header**；
 * - **主机侧单次 bulk URB 上限 16384 B**（Android usbfs，与 libusb 的
 *   `MAX_BULK_BUFFER_LENGTH` 一致）：拿 460822 去 `bulkTransfer` 会立刻返回 -1
 *   （不是超时！）。原厂 libusb 是内部拆成 ~29 个 16 KiB URB。因此本实现按
 *   [BULK_CHUNK] 分块读，再在 [readLoop] 里按 `hdrLen + frameSize` 重组帧；
 * - **只认合法 header**：固件异常时设备会"整块灌满、首字节是亮度值"，
 *   以前那种 `rawPayloadMode` 兜底会把垃圾当图像画出来 → 彩色噪点；
 *   现在未同步超过 4 MB 会判定为无效流并报错。
 *
 * 与 [com.wzqvip.glxsscast.usb.UsbLink] 的关系（0.1.3 起**默认共用同一个连接**）：
 * - 原厂只 open 一个 fd，LCD/UVC/HID 都在上面 claim/控制；我们以前是 UVC 自己
 *   `openDevice` 拿第二个 fd，两个 fd 都会 force claim，HID 的 claim 会被互相抢走，
 *   同设备两个 fd 同时做 bulk（1 MB OUT + 460 KB IN）也是与「原厂不崩」的差异之一。
 *   现在 UI 调 [prepare] 时传 `UsbLink.connection`（`reuse`），[releaseInternal] 不再
 *   close 共享连接，也不 release HID（HID 归 UsbLink）；
 * - 所有控制传输（claim/release/setInterface/probe/commit/CLEAR_HALT）都走 [UsbGate]，
 *   不会插进 LCD 的 1 MB 帧中间；
 * - [stop] 的收尾顺序（0.1.3）：停读线程 → `setInterface(alt0)` → `releaseInterface(VS)`
 *   → 对 EP 0x81 发 `CLEAR_FEATURE(ENDPOINT_HALT)` → 共享连接时不 close。
 *   之前只做 `setInterface(alt0)` 就 close/复用，端点若被上一次取消的 URB 留在
 *   HALT/半配置状态，下一次 commit 会“成功但 0 帧”（半死）。
 */
class UvcCamera(private val ctx: Context) {

    companion object {
        const val VID = 0x2E09
        const val PID = 0x0030
        private const val TAG = "UvcCamera"

        /** 目标分辨率（厂商日志实测） */
        const val TARGET_W = 640
        const val TARGET_H = 480

        // ---- UVC class-specific 请求 ----
        private const val VS_PROBE = 0x0100      // VS_PROBE_CONTROL << 8
        private const val VS_COMMIT = 0x0200     // VS_COMMIT_CONTROL << 8
        private const val UVC_SET_CUR = 0x01
        private const val UVC_GET_CUR = 0x81
        private const val UVC_GET_MIN = 0x82
        private const val UVC_GET_MAX = 0x83
        private const val UVC_GET_DEF = 0x87
        private const val REQ_OUT = 0x21         // Host->Device | Class | Interface
        private const val REQ_IN = 0xA1          // Device->Host | Class | Interface

        private const val CTRL_TIMEOUT = 2000
        private const val BULK_TIMEOUT = 1000

        /** UVC payload header 最长 12 字节（2 + PTS 4 + SCR 6） */
        private const val MAX_HDR = 12

        /**
         * 原厂 libuvc 的 transfer 尺寸 = dwMaxVideoFrameSize + 22（日志 frameBufferSize=460822）。
         * 设备一帧 = 460800 + 12B header = 460812，用 22 留 10 字节余量。
         *
         * **注意**：Android usbfs 单次 URB 上限 16384 B，不能拿这个尺寸去 `bulkTransfer`
         * （实测立刻返回 -1）；实际读取用 [BULK_CHUNK]，本值只用于日志/统计。
         */
        private const val URB_SLACK = 22

        /**
         * 单次 `bulkTransfer` 的读取块大小 = Android usbfs 单 URB 上限（16 KiB）。
         * libusb（原厂）内部也是把 460822 拆成 16 KiB URB，只是它对 App 隐藏了拆分/重组。
         */
        private const val BULK_CHUNK = 16 * 1024

        /** 探测设备的视频格式/帧描述符（不 claim 接口，只读描述符），用于诊断 */
        fun describe(usb: UsbManager, device: UsbDevice): String {
            val sb = StringBuilder()
            sb.append("${device.deviceName} ${device.manufacturerName}/${device.productName}")
            sb.append(" bcdUVC? ifaces=${device.interfaceCount}\n")
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                sb.append(
                    "  INTF #${itf.id} alt=${itf.alternateSetting} " +
                        "class=0x%02x/0x%02x eps=%d".format(itf.interfaceClass, itf.interfaceSubclass, itf.endpointCount)
                )
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    sb.append(
                        " | ep=0x%02x type=%d mps=%d".format(
                            ep.address, ep.type, ep.maxPacketSize
                        )
                    )
                }
                sb.append('\n')
            }
            return sb.toString()
        }
    }

    /** 帧回调；每帧一个 Bitmap（已解码/转换好） */
    interface Listener {
        fun onFrame(bitmap: Bitmap, frameBytes: Int, fps: Float)
        fun onError(message: String)
    }

    /** UVC 未压缩格式的排布方式 */
    enum class PixelMapping { MJPEG, YV12, I420, NV12, NV21, YUY2, UYVY, UNKNOWN }

    data class FrameDesc(
        val index: Int,
        val width: Int,
        val height: Int,
        val maxFrameSize: Int,
        val defaultInterval: Int,
        val intervals: List<Int>
    ) {
        /** 100ns 单位 → fps */
        val fps: Float get() = if (defaultInterval > 0) 1e7f / defaultInterval else 0f
    }

    data class FormatDesc(
        val index: Int,
        val name: String,           // "MJPEG" / "YV12" / "YUY2" / ...
        val mapping: PixelMapping,
        val bitsPerPixel: Int,
        val frames: MutableList<FrameDesc> = mutableListOf()
    )

    /**
     * 设备描述符里的一个「格式 + 分辨率」档位，UI 下拉用。
     * [formatIndex] / [frameIndex] 是 UVC bFormatIndex / bFrameIndex（重新选择时要原样写回 probe）。
     */
    data class StreamOption(
        val formatName: String,
        val mapping: PixelMapping,
        val formatIndex: Int,
        val frameIndex: Int,
        val width: Int,
        val height: Int,
        val fps: Float,
        val maxFrameSize: Int,
        val interval: Int
    ) {
        val label: String
            get() = "$formatName  ${width}×${height} @${String.format(Locale.US, "%.0f", fps)}fps"

        fun matches(formatName: String, w: Int, h: Int): Boolean =
            this.formatName.equals(formatName, ignoreCase = true) && width == w && height == h
    }

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    // ---- 选中的流参数 ----
    @Volatile private var format: FormatDesc? = null
    @Volatile private var frame: FrameDesc? = null
    @Volatile private var frameWidth = TARGET_W
    @Volatile private var frameHeight = TARGET_H

    /** 最近一次 [prepare] 从描述符解析出的全部档位（UI 下拉选择） */
    @Volatile private var optionList: List<StreamOption> = emptyList()
    private var parsedFormats: List<FormatDesc> = emptyList()
    @Volatile private var selectedOption: StreamOption? = null

    /** 设备描述符里可选的档位（需先 [prepare] 成功） */
    fun options(): List<StreamOption> = optionList

    /** 当前实际在用的档位 */
    fun currentOption(): StreamOption? = selectedOption

    // ---- USB ----
    private var device: UsbDevice? = null
    private var conn: UsbDeviceConnection? = null
    private var vsAlt: UsbInterface? = null        // alt=1，带 bulk IN
    private var vsAltZero: UsbInterface? = null    // 同 id 的 alt=0，stop 时切回去
    private var vsEpIn: UsbEndpoint? = null
    private var ownsConnection = false
    /** 共用 UsbLink 的连接（true 时 [releaseInternal] 不 close、不 release HID） */
    private var sharedConn = false
    private var vcClaimed: UsbInterface? = null
    private var vsClaimed = false
    /**
     * 是否已把 VS 接口切到带 bulk IN 的 alt。
     * 不能每次 select() 都重复 setInterface(同一个 alt)：部分固件会因此把刚启动的流复位掉
     * （现象：commit 成功、只来一个坏 URB，然后就再也收不到数据）。
     */
    private var altSelected = false

    // ---- 线程 ----
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var listener: Listener? = null

    /** 录像旁路（与 [listener] 并存，预览不受影响）；null = 不录像 */
    @Volatile var frameTap: UvcFrameTap? = null

    // ---- 帧缓冲/统计 ----
    private var frameBuf = ByteArray(0)
    private var frameLen = 0
    @Volatile private var inSync = false
    @Volatile private var unalignedDrop = 0
    private var transferBuf = ByteArray(0)
    /** 字节流组帧缓冲：1 帧 + 1 个 chunk（header 可能跨 chunk） */
    private var rxBuf = ByteArray(0)
    /** 单帧净荷（不含 UVC header），640x480 YV12 = 460800 */
    private var frameSize = 0
    /** URB 尺寸 = frameSize + URB_SLACK（对齐原厂 460822） */
    private var urbSize = 0

    /** 最近一个有效 header 的长度/标志位，诊断用 */
    @Volatile private var lastHdrLen = -1
    @Volatile private var lastHdrInfo = -1
    /** 连续无效 URB 计数 / 累计 */
    @Volatile private var invalidStreak = 0
    @Volatile private var invalidTotal = 0L
    @Volatile private var invalidReported = false

    @Volatile private var lastBitmap: Bitmap? = null
    private var framesInWindow = 0
    private var windowStart = 0L
    @Volatile private var fps = 0f
    /** 累计出帧数（压力测试统计用） */
    @Volatile private var totalFrames = 0L
    /** fps 进 oplog 的节流计数（每 5 秒一条） */
    private var fpsLogTick = 0

    // 双缓冲：每帧不再新建 Bitmap（createBitmap 每帧 1.2MB 分配 + 拷贝）
    private var bmpA: Bitmap? = null
    private var bmpB: Bitmap? = null
    private var useA = true

    val isOpen: Boolean get() = conn != null
    val isRunning: Boolean get() = running
    /** 读线程是否还活着（设备断开/读循环退出后 running 可能滞后，实验 harness 用它判断僵死状态） */
    fun isReaderAlive(): Boolean = thread?.isAlive == true

    /** 最近一秒的出流帧率（压力测试 / 状态栏显示） */
    fun currentFps(): Float = fps

    /** 累计出帧数 */
    fun frameCount(): Long = totalFrames

    /** 当前画面尺寸：优先用最近一帧，其次协商结果 */
    fun currentSize(): Pair<Int, Int> {
        lastBitmap?.let { return it.width to it.height }
        selectedOption?.let { return it.width to it.height }
        return frameWidth to frameHeight
    }

    /** 诊断字符串：本次协商到的格式/帧/带宽 */
    fun negotiatedInfo(): String {
        val f = format ?: return "not negotiated"
        val fr = frame ?: return "format=$f"
        val hdr = if (lastHdrLen > 0) " hdr=${lastHdrLen}B/info=0x%02x".format(lastHdrInfo) else ""
        val bad = if (invalidTotal > 0) " 无效URB=$invalidTotal" else ""
        return "format=${f.name}(idx=${f.index}) ${fr.width}x${fr.height} " +
            "frameIdx=${fr.index} maxFrame=${fr.maxFrameSize}B " +
            "urb=${urbSize}B interval=${fr.defaultInterval}" +
            "(=${String.format(Locale.US, "%.1f", fr.fps)}fps)$hdr$bad"
    }

    /** 找眼镜（优先精确 VID/PID） */
    fun findDevice(): UsbDevice? {
        val list = usb.deviceList.values
        return list.firstOrNull { it.vendorId == VID && it.productId == PID }
            ?: list.firstOrNull { it.vendorId == VID }
    }

    fun hasPermission(dev: UsbDevice): Boolean = usb.hasPermission(dev)

    /**
     * 诊断（debug 广播 `uvc_bulk` 用）：对当前 VS bulk IN 端点按不同长度做同步
     * `bulkTransfer`，记录返回值和耗时。
     *
     * 判据：
     * - `n = -1` 且 `dt ≈ timeout` → 设备在线但**不送数据**（NAK，没出流）；
     * - `n = -1` 且 `dt ≈ 0`   → 端点错误（EPIPE/STALL/ENODEV，claim/alt 被抢或端点 halt）；
     * - `n > 0` → 真有数据（打印前 12 字节，`0c 0f ...` 就是 UVC header）。
     *
     * 在 [prepare] 之后、[select] 之前调用不会出流（预期全部超时），
     * 在 [select] 之后调用才可能拿到帧。
     */
    fun bulkProbe(
        lengths: IntArray = intArrayOf(512, 16384, 460822),
        timeoutMs: Int = 800
    ): String {
        val c = conn ?: return "bulkProbe: no connection"
        val ep = vsEpIn ?: return "bulkProbe: no endpoint"
        val sb = StringBuilder()
        sb.append("ep=0x%02x mps=%d claimed=%b alt=%b\n".format(ep.address, ep.maxPacketSize, vsClaimed, altSelected))
        for (len in lengths) {
            val buf = ByteArray(len)
            val t0 = SystemClock.elapsedRealtime()
            val n = try {
                c.bulkTransfer(ep, buf, len, timeoutMs)
            } catch (t: Throwable) {
                -2
            }
            val dt = SystemClock.elapsedRealtime() - t0
            val head = if (n > 0) (0 until minOf(12, n)).joinToString("") { "%02x".format(buf[it]) } else ""
            val line = "len=$len n=$n dt=${dt}ms $head"
            Log.i(TAG, "bulkProbe $line")
            Oplog.logf("UVC", "bulkProbe %s", line)
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    /** 诊断：清一次 EP 0x81 的 HALT（CLEAR_FEATURE(ENDPOINT_HALT)） */
    fun clearHaltForTest(): Boolean {
        val c = conn ?: return false
        val ep = vsEpIn ?: return false
        return LcdControl.clearEndpointHalt(c, ep.address)
    }

    // ==================================================================
    // 打开 + 协商
    // ==================================================================

    /**
     * 打开设备并完成 probe/commit（等价于 [prepare] + [select]）。
     *
     * @param reuse 复用已打开的连接（例如 UsbLink.connection）；为 null 时自己 openDevice + 自己 close
     * @return 成功与否；失败时 [lastError] 有原因
     */
    fun open(dev: UsbDevice, reuse: UsbDeviceConnection? = null): Boolean {
        if (!prepare(dev, reuse)) return false
        if (!select(null)) {
            releaseInternal()
            return false
        }
        return true
    }

    /**
     * 打开设备、claim 接口、解析 VS 描述符，但**不** probe/commit、不取流。
     * 成功后 [options] 就是设备支持的全部「格式 + 分辨率」档位，UI 可下拉选择。
     *
     * @param reuse 复用已打开的连接；为 null 时自己 openDevice + 自己 close
     */
    fun prepare(dev: UsbDevice, reuse: UsbDeviceConnection? = null): Boolean {
        close()
        lastError = null
        if (reuse == null && !usb.hasPermission(dev)) {
            lastError = "no USB permission for $dev"
            Log.w(TAG, lastError!!)
            return false
        }
        device = dev

        // 1) 找 VideoStreaming 接口里带 bulk IN 的 alt setting（alt=1）
        var foundAlt: UsbInterface? = null
        var foundEp: UsbEndpoint? = null
        var zeroAlt: UsbInterface? = null
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            if (itf.interfaceClass != 0x0e || itf.interfaceSubclass != 0x02) continue
            // 同 id 的 alt=0（dummy）留着，停止时切回去
            if (itf.alternateSetting == 0 && (zeroAlt == null || zeroAlt!!.id == itf.id)) {
                zeroAlt = itf
            }
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_IN
                ) {
                    foundAlt = itf
                    foundEp = ep
                }
            }
            if (foundAlt != null) break
        }
        if (foundAlt == null || foundEp == null) {
            lastError = "no UVC bulk IN endpoint (is the glasses in normal mode 2e09:0030?)"
            Log.e(TAG, lastError!!)
            return false
        }
        vsAlt = foundAlt
        vsAltZero = zeroAlt
        vsEpIn = foundEp
        Log.i(TAG, "VS iface=#${foundAlt.id} alt=${foundAlt.alternateSetting} ep=0x%02x mps=${foundEp.maxPacketSize}".format(foundEp.address))

        // 2) 打开连接（优先复用 UsbLink 的那个 fd —— 单 owner）
        val c = reuse ?: (usb.openDevice(dev) ?: run {
            lastError = "openDevice() failed"
            Log.e(TAG, lastError!!)
            return false
        })
        conn = c
        ownsConnection = (reuse == null)
        sharedConn = (reuse != null)
        Oplog.logf(
            "UVC", "prepare %s shared=%b", dev.deviceName, sharedConn
        )

        // 2.5) 也 claim VideoControl 接口（原厂 libuvc 会 claim；部分设备要求先有 VC 才出流）
        val vc = (0 until dev.interfaceCount).map { dev.getInterface(it) }
            .firstOrNull { it.interfaceClass == 0x0e && it.interfaceSubclass == 0x01 }
        if (vc != null) {
            val ok = UsbGate.io("claim:vc") { c.claimInterface(vc, true) }
            if (ok) vcClaimed = vc
            Oplog.logf("UVC", "claim VC if=%d -> %b", vc.id, ok)
            Log.i(TAG, "claim VC iface=#${vc.id} alt=${vc.alternateSetting} -> $ok")
        }

        // 2.6) 摘掉内核 hid-generic 对 HID 接口(2)的占用。
        // 共用一个 fd 时 HID 已经由 UsbLink claim 过了，这里不要再碰：
        // 重复 claim 没有意义，而 release 会把 UsbLink 的 claim 一起放掉。
        if (!sharedConn) {
            val hid = (0 until dev.interfaceCount).map { dev.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            if (hid != null) {
                val ok = UsbGate.io("claim:hid_uvc") { c.claimInterface(hid, true) }
                Oplog.logf("UVC", "claim HID if=%d -> %b", hid.id, ok)
                Log.i(TAG, "claim HID iface=#${hid.id} -> $ok")
            }
        }


        // 3) claim + 切 alt setting
        val vsOk = UsbGate.io("claim:vs") { c.claimInterface(foundAlt, true) }
        if (!vsOk) {
            lastError = "claimInterface(#${foundAlt.id} alt=${foundAlt.alternateSetting}) failed"
            Oplog.logf("UVC", "claim VS if=%d -> false", foundAlt.id)
            Log.e(TAG, lastError!!)
            releaseInternal()
            return false
        }
        vsClaimed = true
        val altOk = UsbGate.io("set_alt:vs") { c.setInterface(foundAlt) }
        Oplog.logf(
            "UVC", "claim VS if=%d alt=%d -> true; setInterface(alt%d)=%b",
            foundAlt.id, foundAlt.alternateSetting, foundAlt.alternateSetting, altOk
        )
        if (!altOk) {
            // 少数机型要求先 claim alt0 再切 alt1；失败时也继续试（有的设备 setInterface 返回 false 但其实生效）
            Log.w(TAG, "setInterface(alt=${foundAlt.alternateSetting}) returned false, continuing anyway")
        }
        altSelected = true

        // 4) 解析描述符 → 档位列表（不在这一步 probe/commit）
        parsedFormats = try {
            parseFormats(c.rawDescriptors)
        } catch (t: Throwable) {
            Log.w(TAG, "parseFormats failed", t)
            emptyList()
        }
        optionList = buildOptions(parsedFormats)
        Log.i(TAG, "VS formats: " + parsedFormats.joinToString { f ->
            "${f.name}#${f.index}" + f.frames.joinToString(prefix = "[", postfix = "]") { fr ->
                "${fr.width}x${fr.height}@${String.format(Locale.US, "%.1f", fr.fps)}"
            }
        })
        Log.i(TAG, "options (${optionList.size}): " + optionList.joinToString { it.label })
        return true
    }

    /** 从描述符解析结果构建 UI 档位列表：YV12 → MJPEG → 其它，分辨率从大到小 */
    private fun buildOptions(formats: List<FormatDesc>): List<StreamOption> {
        val out = ArrayList<StreamOption>()
        for (f in formats) {
            for (fr in f.frames) {
                out += StreamOption(
                    formatName = f.name,
                    mapping = f.mapping,
                    formatIndex = f.index,
                    frameIndex = fr.index,
                    width = fr.width,
                    height = fr.height,
                    fps = fr.fps,
                    maxFrameSize = fr.maxFrameSize,
                    interval = fr.defaultInterval
                )
            }
        }
        fun rank(m: PixelMapping) = when (m) {
            PixelMapping.YV12 -> 0
            PixelMapping.MJPEG -> 1
            PixelMapping.I420 -> 2
            PixelMapping.NV12 -> 3
            PixelMapping.NV21 -> 4
            PixelMapping.YUY2 -> 5
            PixelMapping.UYVY -> 6
            PixelMapping.UNKNOWN -> 7
        }
        out.sortWith(
            compareBy({ rank(it.mapping) }, { -(it.width.toLong() * it.height).toInt() }, { -it.fps })
        )
        return out
    }

    /**
     * 应用一个档位（null = 默认 640×480 优先）。
     * 会重新 probe/commit 并重建收发缓冲，但**不**启动读线程。
     */
    fun select(want: StreamOption? = null): Boolean {
        val c = conn ?: run {
            lastError = "not prepared"
            return false
        }
        val alt = vsAlt ?: run {
            lastError = "no VS interface"
            return false
        }
        lastError = null
        // stop() 会 release VS，这里按需重新 claim；已经切过 alt 就不重复（会复位流）
        if (!vsClaimed) {
            val ok = UsbGate.io("claim:vs") { c.claimInterface(alt, true) }
            vsClaimed = ok
            altSelected = false
            Oplog.logf("UVC", "re-claim VS if=%d -> %b", alt.id, ok)
            if (!ok) {
                lastError = "claimInterface(#${alt.id}) failed"
                return false
            }
        }
        if (!altSelected) {
            val ok = UsbGate.io("set_alt:vs") { c.setInterface(alt) }
            Oplog.logf("UVC", "setInterface(alt=%d) -> %b", alt.alternateSetting, ok)
            if (!ok) {
                Log.w(TAG, "setInterface(alt=${alt.alternateSetting}) returned false, continuing anyway")
            }
            altSelected = true
        }
        val (f, fr) = pick(want)
        format = f
        frame = fr
        frameWidth = fr.width
        frameHeight = fr.height
        Log.i(TAG, "selected: ${f.name} ${fr.width}x${fr.height} frameIdx=${fr.index}")

        // 5) 原厂 vendor 前置序列（显示模式 / LCD 状态读 + XU 0x0d0d 相机开关）
        //    必须在 probe 之前（Frida 抓包 re/liangliang/uvc40.txt 94–101 行）
        vendorPreProbe(c)

        // 6) probe / commit
        if (!probeAndCommit(c, alt.id, f, fr)) return false

        // 7) 原厂 commit 之后再写两次 XU 0x0606（抓包 130–131 行）
        vendorPostCommit(c)

        // 7.5) 清一次 EP 0x81 的 HALT。上一次被取消的 URB / 固件 STALL 会让端点停在
        //      halt 状态，表现是 bulkTransfer **立刻** 返回 -1（dt≈0），而不是 1000ms 超时。
        vsEpIn?.let { LcdControl.clearEndpointHalt(c, it.address) }

        // 8) 准备缓冲：一次 bulk 只能读 16 KiB（usbfs 上限），组帧缓冲按 1 帧 + 1 chunk
        frameSize = max(fr.maxFrameSize, estimateFrameBytes(f.mapping, fr.width, fr.height))
        urbSize = frameSize + URB_SLACK
        frameBuf = ByteArray(frameSize + 64)
        transferBuf = ByteArray(BULK_CHUNK)
        rxBuf = ByteArray(frameSize + MAX_HDR + BULK_CHUNK + 64)
        frameLen = 0
        invalidStreak = 0
        invalidTotal = 0
        invalidReported = false
        lastHdrLen = -1
        lastHdrInfo = -1
        bmpA = null
        bmpB = null
        lastBitmap = null
        selectedOption = StreamOption(
            formatName = f.name,
            mapping = f.mapping,
            formatIndex = f.index,
            frameIndex = fr.index,
            width = fr.width,
            height = fr.height,
            fps = fr.fps,
            maxFrameSize = fr.maxFrameSize,
            interval = fr.defaultInterval
        )
        Log.i(TAG, "buffers: frameSize=$frameSize urbSize=$urbSize")
        Log.i(TAG, "negotiated: ${negotiatedInfo()}")
        return true
    }

    /**
     * 换档位并重启取流（UI 下拉选择后调用）。
     * 复用当前 USB 连接：先停读线程 → 切回 alt0 → 重新 probe/commit 新档位 → 重新开始取流。
     */
    fun restartStream(want: StreamOption? = null): Boolean {
        val dev = device ?: run {
            lastError = "not prepared"
            return false
        }
        // stop() 不会 close 共享连接；记住它，重新 probe 失败时也复用它（不要新开第二个 fd）
        val reuse = conn
        stop()
        lastError = null
        // 先复用现有连接重新 probe；失败再彻底重开（部分固件在 alt 切换后需要新 fd）
        if (select(want)) {
            start()
            Log.i(TAG, "restarted: ${negotiatedInfo()}")
            return true
        }
        Log.w(TAG, "restartStream: re-probe failed (${lastError}), reopening device")
        if (!prepare(dev, reuse)) return false
        if (!select(want)) return false
        start()
        Log.i(TAG, "restarted (reopened): ${negotiatedInfo()}")
        return true
    }

    /** 选档位：优先精确 formatIndex+frameIndex，其次 格式名+宽高；都没有就用默认规则 */
    private fun pick(want: StreamOption?): Pair<FormatDesc, FrameDesc> {
        if (want != null) {
            for (f in parsedFormats) for (fr in f.frames) {
                if (f.index == want.formatIndex && fr.index == want.frameIndex) return f to fr
            }
            for (f in parsedFormats) for (fr in f.frames) {
                if (f.name.equals(want.formatName, true) &&
                    fr.width == want.width && fr.height == want.height
                ) return f to fr
            }
            Log.w(TAG, "wanted option ${want.label} not in descriptors, using default")
        }
        val def = pickFormat(parsedFormats)
        if (def != null) return def
        // 描述符解析失败：按厂商日志的经验值硬试 YV12 640x480
        Log.w(TAG, "no VS format descriptors parsed; falling back to guesses")
        return FormatDesc(1, "YV12", PixelMapping.YV12, 12) to
            FrameDesc(1, TARGET_W, TARGET_H, TARGET_W * TARGET_H * 3 / 2, 666666, listOf(666666))
    }

    private var lastError: String? = null
    fun lastError(): String? = lastError

    /**
     * 默认档位：640×480 里按 MJPEG > YV12 > I420 > ... 优先级挑；
     * 没有 640×480 就挑面积最大的。
     */
    private fun pickFormat(formats: List<FormatDesc>): Pair<FormatDesc, FrameDesc>? {
        // 优先级：MJPEG（带宽小、解码快）> YV12/I420（本设备极可能只有这个）> NV12 > YUY2
        val order = listOf(
            PixelMapping.MJPEG, PixelMapping.YV12, PixelMapping.I420,
            PixelMapping.NV12, PixelMapping.NV21, PixelMapping.YUY2, PixelMapping.UYVY, PixelMapping.UNKNOWN
        )
        var best: Pair<FormatDesc, FrameDesc>? = null
        var bestRank = Int.MAX_VALUE
        for (f in formats) {
            for (fr in f.frames) {
                if (fr.width != TARGET_W || fr.height != TARGET_H) continue
                val rank = order.indexOf(f.mapping).let { if (it < 0) 99 else it }
                if (rank < bestRank) {
                    bestRank = rank
                    best = f to fr
                }
            }
        }
        if (best == null) {
            // 没有 640x480？退而求其次：最大的那个
            for (f in formats) for (fr in f.frames) {
                if (best == null ||
                    fr.width.toLong() * fr.height > best!!.second.width.toLong() * best!!.second.height
                ) {
                    best = f to fr
                }
            }
        }
        return best
    }

    /**
     * UVC probe/commit —— 逐字节对齐原厂 Frida 抓包
     * （`re/liangliang/uvc40.txt` 105–131 行 / `vendor_uvc_full.log` 104–130 行）：
     *
     * ```
     * GET_CUR / GET_MIN / GET_MAX                      ×2 轮
     * SET_CUR(probe, frame=3) → GET_CUR ×2 GET_MIN GET_MAX ×2 轮
     * SET_CUR(probe, frame=4) → GET_CUR ×2 GET_MIN GET_MAX ×2 轮
     * SET_CUR(probe, frame=4) → GET_CUR
     * SET_CUR(commit, frame=4)   ← 34 B，内容 = 最后一次 GET_CUR 的回读
     * ```
     *
     * 与旧实现的关键差异：
     * - 原厂 SET_CUR 里的 `dwMaxVideoFrameSize=0x012c7f80 /
     *   dwMaxPayloadTransferSize=0x012c7f8c` 就是 **GET_MAX 的全局上限**（19,693,440 /
     *   19,693,452 B），旧实现自己填 460800/16384，字节对不上原厂；
     * - 先 probe frame=3（热身档）再落到 frame=4，并在 commit 前把目标档 SET 两次。
     *
     * 注意：URB 尺寸仍按**当前档位净荷**算（640×480 = 460822），
     * 不能拿这个全局上限当 URB（见 docs/ch-uvc.md §2.3）。
     *
     * probe 结构（UVC 1.1，34 字节；UVC 1.0 为 26 字节）：
     * ```
     *  0..1   bmHint                    (bit0 = dwFrameInterval 有效)
     *  2      bFormatIndex
     *  3      bFrameIndex
     *  4..7   dwFrameInterval           (100ns)
     *  8..9   wKeyFrameRate
     * 10..11  wPFrameRate
     * 12..13  wCompQuality
     * 14..15  wCompWindowSize
     * 16..17  wDelay
     * 18..21  dwMaxVideoFrameSize
     * 22..25  dwMaxPayloadTransferSize
     * 26..29  dwClockFrequency
     * 30      bmFramingInfo
     * 31..33  bPreferedVersion/bMinVersion/bMaxVersion
     * ```
     */
    private fun probeAndCommit(
        c: UsbDeviceConnection,
        ifaceNum: Int,
        f: FormatDesc,
        fr: FrameDesc
    ): Boolean {
        // probe/commit 是 class 控制传输：走 UsbGate（不与 1 MB LCD 帧交错）+ 进 oplog
        fun ioIn(tag: String, request: Int, value: Int, len: Int, buf: ByteArray): Int {
            val t0 = SystemClock.elapsedRealtime()
            val n = UsbGate.io("uvc:$tag") {
                c.controlTransfer(REQ_IN, request, value, ifaceNum, buf, 0, len, CTRL_TIMEOUT)
            }
            Oplog.logf(
                "UVC", "%s(IN) req=0x%02x wValue=0x%04x len=%d rc=%d ms=%d",
                tag, request, value, len, n, SystemClock.elapsedRealtime() - t0
            )
            return n
        }

        fun ioOut(tag: String, value: Int, data: ByteArray, len: Int): Int {
            val t0 = SystemClock.elapsedRealtime()
            val n = UsbGate.io("uvc:$tag") {
                c.controlTransfer(REQ_OUT, UVC_SET_CUR, value, ifaceNum, data, 0, len, CTRL_TIMEOUT)
            }
            Oplog.logf(
                "UVC", "%s(OUT) wValue=0x%04x len=%d rc=%d ms=%d",
                tag, value, len, n, SystemClock.elapsedRealtime() - t0
            )
            return n
        }

        fun putU32(buf: ByteArray, off: Int, v: Int) {
            buf[off] = (v and 0xff).toByte()
            buf[off + 1] = ((v shr 8) and 0xff).toByte()
            buf[off + 2] = ((v shr 16) and 0xff).toByte()
            buf[off + 3] = ((v shr 24) and 0xff).toByte()
        }

        // 基底：设备当前的 probe 值（有些设备校验未用字段必须为 0）
        val base = ByteArray(34)
        var len = ioIn("GET_CUR(probe)", UVC_GET_CUR, VS_PROBE, base.size, base)
        if (len <= 0) {
            len = ioIn("GET_DEF(probe)", UVC_GET_DEF, VS_PROBE, base.size, base)
        }
        if (len <= 0) {
            Log.w(TAG, "GET_CUR/GET_DEF(probe) failed, building probe from scratch")
            base.fill(0)
            len = 34
        }
        if (len < 26) {
            Log.w(TAG, "probe length $len < 26, padding")
            len = 34
        }
        var baseMaxFrame = u32(base, 18).toInt()
        var baseMaxPayload = u32(base, 22).toInt()

        // 原厂在 probe 前把 GET_CUR/GET_MIN/GET_MAX 来回读两轮；
        // 18..25 以 GET_MAX 的全局上限为准（= 原厂 SET_CUR 里的字节）。
        val minb = ByteArray(34)
        val maxb = ByteArray(34)
        val tmp = ByteArray(34)
        repeat(2) { round ->
            ioIn("GET_CUR(probe#${round + 2})", UVC_GET_CUR, VS_PROBE, 34, tmp)
            val nMin = ioIn("GET_MIN(probe#${round + 1})", UVC_GET_MIN, VS_PROBE, 34, minb)
            val nMax = ioIn("GET_MAX(probe#${round + 1})", UVC_GET_MAX, VS_PROBE, 34, maxb)
            if (round == 0) {
                Log.i(TAG, "probe MIN n=$nMin frame=${minb[3]} " +
                    "maxFrame=${u32(minb, 18)} maxPayload=${u32(minb, 22)}")
                Log.i(TAG, "probe MAX n=$nMax frame=${maxb[3]} " +
                    "maxFrame=${u32(maxb, 18)} maxPayload=${u32(maxb, 22)}")
            }
            if (nMax >= 26) {
                val mf = u32(maxb, 18).toInt()
                val mp = u32(maxb, 22).toInt()
                if (mf > 0 && mp > 0) {
                    baseMaxFrame = mf
                    baseMaxPayload = mp
                }
            }
        }
        putU32(base, 18, baseMaxFrame)
        putU32(base, 22, baseMaxPayload)

        fun buildProbe(frameIndex: Int): ByteArray {
            val b = base.copyOf()
            b[0] = 1                        // bmHint: 用 dwFrameInterval
            b[1] = 0
            b[2] = f.index.toByte()
            b[3] = frameIndex.toByte()
            putU32(b, 4, fr.defaultInterval)
            return b
        }

        /** 原厂每组 SET_CUR 之后：GET_CUR ×2 + GET_MIN + GET_MAX */
        fun readRound(tag: String) {
            ioIn("GET_CUR($tag)", UVC_GET_CUR, VS_PROBE, 34, tmp)
            ioIn("GET_CUR($tag,+1)", UVC_GET_CUR, VS_PROBE, 34, tmp)
            ioIn("GET_MIN($tag)", UVC_GET_MIN, VS_PROBE, 34, tmp)
            ioIn("GET_MAX($tag)", UVC_GET_MAX, VS_PROBE, 34, tmp)
        }

        fun setProbe(tag: String, frameIndex: Int): Boolean {
            val b = buildProbe(frameIndex)
            var ret = ioOut("SET_CUR($tag)", VS_PROBE, b, 34)
            if (ret < 0) {
                ret = ioOut("SET_CUR($tag,26)", VS_PROBE, b, 26)
            }
            Log.i(
                TAG,
                "SET_CUR(probe $tag) frame=$frameIndex fmt=${f.index} " +
                    "interval=${fr.defaultInterval} -> $ret"
            )
            if (ret < 0) {
                lastError = "SET_CUR(probe) failed: $ret"
                Log.e(TAG, lastError!!)
                return false
            }
            return true
        }

        // 原厂顺序：先 probe 热身帧档（抓包里是 frame=3），再落到目标档（frame=4）；
        // 目标档在 commit 前再 SET 一次。
        val indexes = f.frames.map { it.index }
        val warm = indexes.firstOrNull { it == 3 && it != fr.index }
            ?: indexes.firstOrNull { it != fr.index }
        if (warm != null) {
            if (!setProbe("warm#$warm", warm)) return false
            readRound("warm")
            readRound("warm+")
        }
        if (!setProbe("target#${fr.index}", fr.index)) return false
        readRound("target")
        readRound("target+")
        if (!setProbe("target#${fr.index}#final", fr.index)) return false

        // 读回 probe 看设备给了什么，并原样作为 commit 内容（与原厂一致）
        val back = ByteArray(34)
        val n = ioIn("GET_CUR(probe,back)", UVC_GET_CUR, VS_PROBE, back.size, back)
        if (n >= 4) {
            Log.i(TAG, "probe result: bFormatIndex=${back[2]} bFrameIndex=${back[3]} " +
                "maxVideoFrame=${u32(back, 18)} maxPayload=${u32(back, 22)}")
        }
        val commitSrc = if (n >= 26) back else buildProbe(fr.index)
        var ret = ioOut("SET_CUR(commit)", VS_COMMIT, commitSrc, len)
        Log.i(TAG, "SET_CUR(commit) frame=${commitSrc[3]} len=$len -> $ret")
        if (ret < 0 && len > 26) {
            ret = ioOut("SET_CUR(commit,26)", VS_COMMIT, commitSrc, 26)
            Log.i(TAG, "SET_CUR(commit, 26) -> $ret")
        }
        if (ret < 0) {
            lastError = "SET_CUR(commit) failed: $ret"
            Log.e(TAG, lastError!!)
            return false
        }
        return true
    }

    /**
     * 原厂打开相机**之前**的 vendor 序列（Frida 抓包 `re/liangliang/uvc40.txt` 94–101 行）：
     *
     * ```
     * 0xA1/0x05  wValue=1  wIndex=3  wLen=1   读显示模式（wValue=1 即"显示模式 1"）
     * 0xA1/0x04  wValue=0  wIndex=3  wLen=8   读 LCD 状态
     * 0xA1/0x05  wValue=1  wIndex=3  wLen=1   再读一次
     * 0x21/0x01  wValue=0x0d0d wIndex=0x0400 wLen=1 data=01   XU：相机开关（entity 4）
     * ```
     *
     * 最后这条 XU 写是原厂「相机开关」：旧实现不发也能出帧，但在「probe/commit 都成功、
     * bulk IN 却一个字节都不来」的场景里，这条是先试项（设备相机侧可能停在待机）。
     */
    private fun vendorPreProbe(c: UsbDeviceConnection) {
        val t0 = SystemClock.elapsedRealtime()
        // 0xA1/0x05 wValue=1 wIndex=3：与 LcdControl.setDisplayMode 是同一条控制传输
        // （bmRequestType=0xA1 是 IN，缓冲区就是读回值；原厂也是这么发的）
        val mode1 = LcdControl.setDisplayMode(c, 1)
        val status = LcdControl.readStatus(c)
        val mode1b = LcdControl.setDisplayMode(c, 1)
        val xu = LcdControl.vendorXuSwitch(c, true)
        Oplog.logf(
            "UVC", "pre-probe vendor: mode1=%b status=%s mode1#2=%b xu0x0d0d=%b ms=%d",
            mode1, status?.hex() ?: "-", mode1b, xu, SystemClock.elapsedRealtime() - t0
        )
        Log.i(
            TAG, "vendor pre-probe: displayMode=$mode1 status=${status?.hex()} " +
                "displayMode#2=$mode1b xu(0x0d0d)=$xu"
        )
    }

    /** 原厂 `SET_CUR(commit)` 之后连发两次 XU `0x0606`（抓包 130–131 行） */
    private fun vendorPostCommit(c: UsbDeviceConnection) {
        val ok = LcdControl.vendorXuStreamStart(c)
        Oplog.logf("UVC", "post-commit vendor: xu0x0606 x2 -> %b", ok)
        Log.i(TAG, "vendor post-commit: xu(0x0606) x2 -> $ok")
    }

    private fun u32(b: ByteArray, off: Int): Long {
        if (off + 3 >= b.size) return 0
        return ((b[off].toLong() and 0xff) or ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or ((b[off + 3].toLong() and 0xff) shl 24))
    }

    private fun estimateFrameBytes(m: PixelMapping, w: Int, h: Int): Int = when (m) {
        PixelMapping.MJPEG -> w * h / 2
        PixelMapping.YV12, PixelMapping.I420, PixelMapping.NV12, PixelMapping.NV21 -> w * h * 3 / 2
        PixelMapping.YUY2, PixelMapping.UYVY -> w * h * 2
        PixelMapping.UNKNOWN -> w * h * 2
    }

    // ==================================================================
    // 描述符解析（只挑 VS 接口 alt=0 下的 format/frame）
    // ==================================================================

    /**
     * 从 `UsbDeviceConnection.rawDescriptors` 里解析出 interfaceNumber → 支持的 formats。
     * 结构：CONFIG(0x02) → INTERFACE(0x04) → CS_INTERFACE(0x24) {VS_FORMAT_*(0x04/0x06), VS_FRAME_*(0x05/0x07)}
     */
    fun parseFormats(raw: ByteArray): List<FormatDesc> {
        val result = ArrayList<FormatDesc>()
        var i = 0
        // rawDescriptors 一般从 CONFIG 开始；保险起见先搜一把
        while (i + 1 < raw.size) {
            val l = raw[i].toInt() and 0xff
            if (l >= 9 && (raw[i + 1].toInt() and 0xff) == 0x02) break
            i++
        }
        var curClass = -1
        var curSub = -1
        var curAlt = -1
        var cur: FormatDesc? = null
        while (i + 2 < raw.size) {
            val len = raw[i].toInt() and 0xff
            if (len < 2) break
            val type = raw[i + 1].toInt() and 0xff
            when (type) {
                0x04 -> { // INTERFACE
                    if (i + 8 < raw.size) {
                        curAlt = raw[i + 3].toInt() and 0xff
                        curClass = raw[i + 5].toInt() and 0xff
                        curSub = raw[i + 6].toInt() and 0xff
                        if (!(curClass == 0x0e && curSub == 0x02 && curAlt == 0)) cur = null
                    }
                }
                0x24 -> { // CS_INTERFACE
                    if (curClass == 0x0e && curSub == 0x02 && curAlt == 0 && i + 2 < raw.size) {
                        when (raw[i + 2].toInt() and 0xff) {
                            0x04 -> { // VS_FORMAT_UNCOMPRESSED
                                if (i + 26 < raw.size) {
                                    val idx = raw[i + 3].toInt() and 0xff
                                    val guid = guidName(raw, i + 5)
                                    val bpp = raw[i + 21].toInt() and 0xff
                                    cur = FormatDesc(idx, guid, mappingOf(guid, bpp), bpp)
                                    result.add(cur)
                                }
                            }
                            0x06 -> { // VS_FORMAT_MJPEG
                                if (i + 5 < raw.size) {
                                    val idx = raw[i + 3].toInt() and 0xff
                                    cur = FormatDesc(idx, "MJPEG", PixelMapping.MJPEG, 0)
                                    result.add(cur)
                                }
                            }
                            0x05, 0x07 -> { // VS_FRAME_UNCOMPRESSED / VS_FRAME_MJPEG
                                val owner = cur
                                if (owner != null && i + 25 < raw.size) {
                                    val fi = raw[i + 3].toInt() and 0xff
                                    val w = (raw[i + 5].toInt() and 0xff) or ((raw[i + 6].toInt() and 0xff) shl 8)
                                    val h = (raw[i + 7].toInt() and 0xff) or ((raw[i + 8].toInt() and 0xff) shl 8)
                                    val maxFrame = u32(raw, i + 17).toInt()
                                    val defInt = u32(raw, i + 21).toInt()
                                    val type2 = raw[i + 25].toInt() and 0xff
                                    val intervals = ArrayList<Int>()
                                    if (type2 == 0) {
                                        // 连续区间：dwMinFrameInterval / dwMaxFrameInterval / Step
                                        if (i + 29 < raw.size) intervals.add(u32(raw, i + 26).toInt())
                                        if (i + 33 < raw.size) intervals.add(u32(raw, i + 30).toInt())
                                    } else {
                                        for (k in 0 until type2) {
                                            val off = i + 26 + 4 * k
                                            if (off + 3 < raw.size) intervals.add(u32(raw, off).toInt())
                                        }
                                    }
                                    owner.frames.add(
                                        FrameDesc(fi, w, h, maxFrame, defInt, intervals)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            i += len
        }
        return result
    }

    private fun guidName(raw: ByteArray, off: Int): String {
        if (off + 3 >= raw.size) return "????"
        val sb = StringBuilder(4)
        for (k in 0 until 4) {
            val ch = raw[off + k].toInt() and 0xff
            sb.append(if (ch in 0x20..0x7e) ch.toChar() else '?')
        }
        return sb.toString()
    }

    private fun mappingOf(guid: String, bpp: Int): PixelMapping = when (guid.uppercase(Locale.US)) {
        "YUY2", "YUYV" -> PixelMapping.YUY2
        "YV12" -> PixelMapping.YV12
        "I420", "IYUV" -> PixelMapping.I420
        "NV12" -> PixelMapping.NV12
        "NV21" -> PixelMapping.NV21
        "UYVY" -> PixelMapping.UYVY
        else -> if (bpp == 16) PixelMapping.UNKNOWN else PixelMapping.UNKNOWN
    }

    // ==================================================================
    // 收流
    // ==================================================================

    fun start() {
        val c = conn ?: return
        val ep = vsEpIn ?: return
        if (running) return
        running = true
        frameLen = 0
        inSync = false
        invalidStreak = 0
        invalidTotal = 0
        invalidReported = false
        unalignedDrop = 0
        framesInWindow = 0
        windowStart = System.currentTimeMillis()
        Oplog.logf(
            "UVC", "start reader ep=0x%02x chunk=%d frame=%d urb=%d",
            ep.address, transferBuf.size, frameSize, urbSize
        )
        thread = Thread({ readLoop(c, ep) }, "UvcReader").also {
            it.priority = Thread.NORM_PRIORITY + 1
            it.start()
        }
    }

    /**
     * 读帧循环（分块 URB + 字节流组帧）。
     *
     * **为什么不能一次 bulk 读一整帧**：Android usbfs 单次 `bulkTransfer` 的 URB
     * 上限是 16384 B（与 libusb 的 `MAX_BULK_BUFFER_LENGTH` 一致）。实测
     * `bulkTransfer(ep, buf, 460822, 1000)` 在 Pixel 上**立刻返回 -1**（dt≈0/59ms，
     * 不是 1000ms 超时），而 512 / 16384 都能拿到数据；原厂 libusb 之所以能用
     * `transferBufferSize=460822`，是 libusb 内部把它拆成 ~29 个 16 KB URB 再重组。
     * 所以这里改成每次读 [BULK_CHUNK]=16384，自己在字节流上组帧。
     *
     * 设备实际格式（本轮 15:45 实测 + 原厂抓包一致）：
     * - 每帧 = 12 B UVC header + `frameSize`（640×480 YV12 = 460800）净荷；
     * - **整帧只有一个 header**，且 `bmHeaderInfo` bit1(EOF)=1（`0c 0f|0e ...`）；
     * - header 之后是连续净荷，后续 16 KB 块里没有 header（首字节就是亮度值）。
     *
     * 组帧规则：
     * 1. 未同步时在字节流里找 header（`0x0C` + EOF + PTS/SCR 自洽），丢弃之前的半帧；
     * 2. 同步后每 `hdrLen + frameSize` 字节交一帧；
     * 3. 交帧后校验下一帧起点仍是合法 header，不合法就重新同步（防止永久错位）。
     */
    private fun readLoop(c: UsbDeviceConnection, ep: UsbEndpoint) {
        val buf = transferBuf
        val rx = rxBuf
        var rxLen = 0
        var synced = false
        var discarding = 0L
        var timeouts = 0
        var fastFails = 0
        var lastLog = 0L
        // 整个读循环再兜一层 Throwable：设备拔出后 bulkTransfer / append / dumpUrb 都可能抛，
        // 后台线程的未捕获异常在 Android 上会杀掉整个进程（= 闪退）。
        try {
        while (running) {
            val t0 = SystemClock.elapsedRealtime()
            val n = try {
                c.bulkTransfer(ep, buf, buf.size, BULK_TIMEOUT)
            } catch (t: Throwable) {
                Log.e(TAG, "bulkTransfer threw", t)
                break
            }
            val dt = SystemClock.elapsedRealtime() - t0
            if (n < 0) {
                // Android 的 bulkTransfer 对「超时」和「设备错误/已拔出」都返回 -1。
                // 用耗时区分，否则设备一拔就会变成 100% CPU 的热循环：
                //   dt ≈ BULK_TIMEOUT → 设备在线但没数据（固件没出流）
                //   dt ≈ 0            → URB 立即失败（设备已断开 / alt 被重置 / 连接被别的 App 抢走）
                val fast = dt < BULK_TIMEOUT / 2
                if (fast) fastFails++ else fastFails = 0
                if (fastFails >= 5) {
                    val msg = "USB 传输连续失败（设备已断开，或接口被其它 App 抢走）。请重新插拔眼镜后再试。"
                    Log.e(TAG, "$msg dt=${dt}ms")
                    Oplog.logf("UVC", "reader fast-fail x%d dt=%dms → stop", fastFails, dt)
                    Oplog.dumpCrash("uvc_usb_fail(dt=${dt}ms)")
                    listener?.onError(msg)
                    break
                }
                timeouts++
                if (timeouts == 3) {
                    Log.w(TAG, "no UVC data yet (${BULK_TIMEOUT}ms timeout) — device idle?")
                    Oplog.logf("UVC", "bulk-in timeout x3 (no data, chunk=%d)", buf.size)
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastLog >= 3000) {
                    Log.w(TAG, "bulk in timeout x$timeouts (no data) fastFails=$fastFails")
                    lastLog = now
                }
                if (fast) safeSleep(20)
                continue
            }
            if (n == 0) {
                safeSleep(10)
                continue
            }
            timeouts = 0
            fastFails = 0
            dumpUrb(buf, n)

            if (rxLen + n > rx.size) {
                // 理论不会发生（缓冲按 1 帧 + 1 chunk 分配）；真溢出就丢半帧重新同步
                Log.w(TAG, "rx buffer overflow (rxLen=$rxLen n=$n) → resync")
                rxLen = 0
                synced = false
            }
            System.arraycopy(buf, 0, rx, rxLen, n)
            rxLen += n

            var pos = 0
            while (true) {
                if (!synced) {
                    var i = pos
                    var found = -1
                    while (i + MAX_HDR <= rxLen) {
                        val l = rx[i].toInt() and 0xff
                        if (l in 2..MAX_HDR && headerOk(rx, i, l)) { found = i; break }
                        i++
                    }
                    if (found < 0) {
                        // 保留末尾 <MAX_HDR 字节（header 可能跨 chunk）
                        pos = max(pos, rxLen - (MAX_HDR - 1))
                        break
                    }
                    discarding += (found - pos)
                    unalignedDrop += (found - pos)
                    pos = found
                    synced = true
                    inSync = true
                }
                if (rxLen - pos < MAX_HDR) break
                val hdrLen = rx[pos].toInt() and 0xff
                if (!headerOk(rx, pos, hdrLen)) {
                    synced = false
                    discarding++
                    pos++
                    continue
                }
                val need = hdrLen + frameSize
                if (rxLen - pos < need) break
                // 一帧到齐：整块拷进 frameBuf（deliver() 会按 frameSize 解码）
                if (frameBuf.size < frameSize) frameBuf = ByteArray(frameSize + 64)
                System.arraycopy(rx, pos + hdrLen, frameBuf, 0, frameSize)
                frameLen = frameSize
                lastHdrLen = hdrLen
                lastHdrInfo = rx[pos + 1].toInt() and 0xff
                invalidStreak = 0
                discarding = 0
                deliverSafe()
                pos += need
            }
            if (pos > 0) {
                System.arraycopy(rx, pos, rx, 0, rxLen - pos)
                rxLen -= pos
            }

            if (discarding > 4L * 1024 * 1024 && !invalidReported) {
                invalidReported = true
                invalidTotal++
                val msg = "眼镜在发没有合法 UVC header 的数据流（已丢弃 ${discarding / 1024} KB）：" +
                    "固件媒体流水线可能已卡死。请拔插眼镜 USB —— App 会自动重灌固件" +
                    "（03E7:2150 → 2E09:0030）；若没自动重灌，等设备出现后用「加载固件」。"
                Log.e(TAG, msg)
                Oplog.logf("UVC", "unsynced 4MB, synced=false → 报错")
                Oplog.dumpCrash("uvc_unsynced_4mb")
                try {
                    listener?.onError(msg)
                } catch (t: Throwable) {
                    Log.w(TAG, "onError callback failed", t)
                }
            }
        }
        } catch (t: Throwable) {
            Oplog.logf("UVC", "reader 异常 %s: %s", t.javaClass.simpleName, t.message)
            Log.e(TAG, "readLoop threw", t)
        } finally {
            frameLen = 0
            // 读循环退出（设备断开/连续 fast-fail）时把 running 归位，否则上层会以为还在出流
            running = false
            Oplog.logf("UVC", "reader stopped (invalidUrb=%d)", invalidTotal)
            Log.i(TAG, "reader stopped (invalidUrb=$invalidTotal)")
        }
    }

    /**
     * 判定一段字节是否是一个合法 UVC payload header（本设备 12 B，整帧只有这一个）。
     *
     * 用 header 自身的冗余位做校验（误判概率 ~1e-10，远低于纯 `0x0C` 扫描）：
     * - `bHeaderLength` 在 2..12；
     * - `bmHeaderInfo` bit1(EOF)=1（本设备每帧都置位）、bit6(ERR)=0；
     * - 带 SCR（bit3）时，SCR 前 2 字节 = PTS 前 2 字节（实测 `0c 0f d9 75 00 00 d9 75…`）。
     */
    private fun headerOk(b: ByteArray, off: Int, hdrLen: Int): Boolean {
        if (hdrLen !in 2..MAX_HDR) return false
        if (off + hdrLen > b.size) return false
        val info = b[off + 1].toInt() and 0xff
        if ((info and 0x40) != 0) return false             // ERR bit
        if ((info and 0x02) == 0) return false             // EOF
        if (hdrLen >= 12 && (info and 0x08) != 0) {        // SCR 与 PTS 低 2 字节一致
            if (b[off + 6] != b[off + 2] || b[off + 7] != b[off + 3]) return false
        }
        return true
    }

    /**
     * [deliver] 的兜底：解码/`listener`/`frameTap` 回调里的任何异常都不允许逃出
     * `UvcReader` 线程 —— Android 上后台线程的未捕获异常会杀掉**整个进程**（表现为闪退）。
     */
    private fun deliverSafe() {
        try {
            deliver()
        } catch (t: Throwable) {
            Oplog.logf("UVC", "deliver threw %s: %s", t.javaClass.simpleName, t.message)
            Log.e(TAG, "deliver threw", t)
        }
    }

    /** 读线程里的 sleep 不允许把 InterruptedException 抛出去（同样会杀进程） */
    private fun safeSleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (ignored: InterruptedException) {
        }
    }

    // ---- 原始字节抓包（诊断用，和 Frida 抓原厂那套同样格式） ----

    @Volatile private var dumpOs: FileOutputStream? = null
    @Volatile private var dumpRemaining = 0

    /**
     * 把接下来 [urbs] 个 bulk 读请求的**原始字节**写到 [file]，格式：
     * 每个请求前置 4 字节小端长度，后接原始数据。
     *
     * 2026-11-22 起读请求是 16 KiB 分块（[BULK_CHUNK]），不再是"一帧一个 460812 B 的
     * URB"；合法流里**每帧第一块**以 `0C 0F/0E` 开头，其余块首字节是亮度值。
     * 拿回 PC 后按 `12 + frameSize` 组帧，可用 `re/liangliang/uvc_frame_decode.py` 解析。
     *
     * @return 成功打开文件返回 true（数据在 reader 线程里写）
     */
    fun startRawDump(file: File, urbs: Int = 30): Boolean {
        if (!running) {
            Log.w(TAG, "startRawDump: reader not running")
            return false
        }
        stopRawDump()
        return try {
            file.parentFile?.mkdirs()
            dumpOs = FileOutputStream(file)
            dumpRemaining = urbs
            rawDumpPath = file.absolutePath
            Log.i(TAG, "raw dump -> ${file.absolutePath} ($urbs URBs)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startRawDump failed", t)
            false
        }
    }

    /** 最近一次原始抓包的文件路径（未抓过为 null） */
    @Volatile var rawDumpPath: String? = null
        private set

    fun stopRawDump() {
        try { dumpOs?.close() } catch (ignored: Throwable) {}
        dumpOs = null
        dumpRemaining = 0
    }

    private fun dumpUrb(buf: ByteArray, n: Int) {
        if (dumpRemaining <= 0) return
        val os = dumpOs ?: return
        try {
            os.write(
                byteArrayOf(
                    (n and 0xff).toByte(), ((n shr 8) and 0xff).toByte(),
                    ((n shr 16) and 0xff).toByte(), ((n shr 24) and 0xff).toByte()
                )
            )
            os.write(buf, 0, n)
            if (--dumpRemaining == 0) {
                os.close()
                dumpOs = null
                Log.i(TAG, "raw dump finished: $rawDumpPath")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "dumpUrb failed", t)
            stopRawDump()
        }
    }

    /** 一帧收齐：解码/转换 → 回调 */
    private fun deliver() {
        val len = frameLen
        frameLen = 0
        if (len <= 0) return
        val f = format ?: return
        val w = frameWidth
        val h = frameHeight
        val bmp: Bitmap? = try {
            when (f.mapping) {
                PixelMapping.MJPEG -> BitmapFactory.decodeByteArray(frameBuf, 0, len)
                PixelMapping.YV12 -> yv12ToBitmap(frameBuf, len, w, h, uFirst = false)
                PixelMapping.I420 -> yv12ToBitmap(frameBuf, len, w, h, uFirst = true)
                PixelMapping.NV12 -> nvToBitmap(frameBuf, len, w, h, swap = false)
                PixelMapping.NV21 -> nvToBitmap(frameBuf, len, w, h, swap = true)
                PixelMapping.YUY2 -> yuy2ToBitmap(frameBuf, len, w, h)
                PixelMapping.UYVY -> yuy2ToBitmap(frameBuf, len, w, h, uyvy = true)
                PixelMapping.UNKNOWN -> null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed (${f.name} ${w}x$h len=$len)", t)
            null
        }
        if (bmp == null) {
            Log.w(TAG, "decode returned null (format=${f.name} len=$len expected~${estimateFrameBytes(f.mapping, w, h)})")
            return
        }
        framesInWindow++
        totalFrames++
        val now = System.currentTimeMillis()
        if (now - windowStart >= 1000) {
            fps = framesInWindow * 1000f / (now - windowStart)
            framesInWindow = 0
            windowStart = now
            Log.i(TAG, "fps=${String.format(Locale.US, "%.1f", fps)} (${f.name} ${w}x${h})")
            // 每 5 秒进一次 oplog，避免刷爆（crash 现场仍需能看到 UVC 是否在出流）
            fpsLogTick++
            if (fpsLogTick % 5 == 1) {
                Oplog.logf("UVC", "fps=%.1f %s %dx%d frames=%d", fps, f.name, w, h, totalFrames)
            }
        }
        lastBitmap = bmp
        try {
            frameTap?.onUvcFrame(bmp, w, h, System.nanoTime() / 1000)
        } catch (t: Throwable) {
            Log.w(TAG, "frameTap failed", t)
        }
        try {
            listener?.onFrame(bmp, len, fps)
        } catch (t: Throwable) {
            Log.w(TAG, "onFrame callback failed", t)
        }
    }

    // ---- YUV → Bitmap ----
    private var argb = IntArray(0)

    /**
     * 把 `argb` 交给一张**复用**的 Bitmap（A/B 双缓冲）。
     *
     * 以前每帧 `Bitmap.createBitmap(argb, ...)` 都要新分配 1.2 MB 再拷贝一次，
     * 20 fps 就是 ~24 MB/s 的分配压力。改成双缓冲后每帧零分配。
     *
     * 注意：帧率瓶颈不在解码——原厂 libuvc 的回调间隔实测也是 ~50 ms（≈20 fps），
     * 说明设备本身就按 20 fps 出流（probe 报的 30 fps 是上限），所以两条路都是 20 fps。
     */
    private fun bitmapOf(w: Int, h: Int): Bitmap {
        var b = if (useA) bmpA else bmpB
        if (b == null || b.width != w || b.height != h) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (useA) bmpA = b else bmpB = b
        }
        b.setPixels(argb, 0, w, 0, 0, w, h)
        useA = !useA
        return b
    }

    private fun yv12ToBitmap(src: ByteArray, len: Int, w: Int, h: Int, uFirst: Boolean): Bitmap? {
        val ySize = w * h
        val cSize = ySize / 4
        if (len < ySize + 2 * cSize) return null
        // YV12: Y | V | U      I420: Y | U | V（真机实测设备发的是 YV12，与原厂画面颜色一致）
        val vOff = if (uFirst) ySize + cSize else ySize
        val uOff = if (uFirst) ySize else ySize + cSize
        if (argb.size != ySize) argb = IntArray(ySize)
        Yuv.yuv420ToArgb(argb, src, uOff, vOff, w, h)
        return bitmapOf(w, h)
    }

    private fun nvToBitmap(src: ByteArray, len: Int, w: Int, h: Int, swap: Boolean): Bitmap? {
        val ySize = w * h
        if (len < ySize + ySize / 2) return null
        if (argb.size != ySize) argb = IntArray(ySize)
        Yuv.nv12ToArgb(argb, src, ySize, w, h, swap)
        return bitmapOf(w, h)
    }

    private fun yuy2ToBitmap(src: ByteArray, len: Int, w: Int, h: Int, uyvy: Boolean = false): Bitmap? {
        val ySize = w * h
        if (len < ySize * 2) return null
        if (argb.size != ySize) argb = IntArray(ySize)
        Yuv.yuy2ToArgb(argb, src, w, h, uyvy)
        return bitmapOf(w, h)
    }

    // ==================================================================
    // 拍照 / 关闭
    // ==================================================================

    /** 当前帧（预览用；可能为 null） */
    fun currentBitmap(): Bitmap? = lastBitmap

    /**
     * 保存当前帧为 PNG 到 app 专属目录（无需任何权限）：
     * `Android/data/<pkg>/files/Pictures/uvc/uvc_yyyyMMdd_HHmmss.png`
     */
    fun saveSnapshot(): File? {
        val bmp = lastBitmap ?: return null
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "uvc")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "cannot create $dir")
            return null
        }
        val name = "uvc_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val file = File(dir, name)
        return try {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "saved $file (${file.length()} bytes)")
            file
        } catch (t: Throwable) {
            Log.e(TAG, "saveSnapshot failed", t)
            null
        }
    }

    /**
     * 优雅停流（0.1.3）：
     * 1. 停读线程（join；读循环最多被 BULK_TIMEOUT 卡 1 s）；
     * 2. `setInterface(alt0)` —— 告诉固件 VS 停止；
     * 3. `releaseInterface(VS)`；
     * 4. 对 EP 0x81 发 `CLEAR_FEATURE(ENDPOINT_HALT)` —— 清掉上一次被取消的 URB
     *    可能留下的 halt/半配置状态（这正是“commit 成功但 0 帧”的半死成因之一）；
     * 5. 共享连接时**不** close（LCD/HID 还要用）。
     */
    fun stop() {
        try {
            stopInternal()
        } catch (t: Throwable) {
            Oplog.logf("UVC", "stop 异常 %s: %s", t.javaClass.simpleName, t.message)
            Log.w(TAG, "stop threw", t)
        } finally {
            // 无论 release/setInterface 成败，读线程都已要求退出
            running = false
        }
    }

    private fun stopInternal() {
        running = false
        val t = thread
        thread = null
        if (t != null && t.isAlive) {
            try {
                t.join(2000)
            } catch (ignored: InterruptedException) {
            }
        }
        val c = conn ?: return
        val itf = vsAlt ?: vsAltZero
        if (vsClaimed) {
            val alt0 = vsAltZero ?: vsAlt
            if (alt0 != null) {
                val ok = try {
                    UsbGate.io("set_alt:vs0") { c.setInterface(alt0) }
                } catch (e: Throwable) {
                    false
                }
                Oplog.logf("UVC", "stop: setInterface(alt=%d) -> %b", alt0.alternateSetting, ok)
            }
            val ep = vsEpIn
            if (ep != null) {
                try {
                    LcdControl.clearEndpointHalt(c, ep.address)
                } catch (ignored: Throwable) {
                }
            }
            if (itf != null) {
                val rel = try {
                    UsbGate.io("release:vs") { c.releaseInterface(itf) }
                } catch (e: Throwable) {
                    false
                }
                Oplog.logf("UVC", "stop: releaseInterface(if=%d) -> %b", itf.id, rel)
            }
            vsClaimed = false
            altSelected = false
        }
        Oplog.logf("UVC", "stop done (shared=%b)", sharedConn)
    }

    fun close() {
        try {
            stop()
            releaseInternal()
        } catch (t: Throwable) {
            Oplog.logf("UVC", "close 异常 %s: %s", t.javaClass.simpleName, t.message)
            Log.w(TAG, "close threw", t)
        }
    }

    private fun releaseInternal() {
        stopRawDump()
        val c = conn
        val vc = vcClaimed
        if (c != null && vc != null) {
            val rel = try {
                UsbGate.io("release:vc") { c.releaseInterface(vc) }
            } catch (e: Throwable) {
                false
            }
            Oplog.logf("UVC", "release VC if=%d -> %b", vc.id, rel)
        }
        if (c != null && ownsConnection) {
            try {
                c.close()
                Oplog.log("UVC", "close own fd")
            } catch (ignored: Throwable) {
            }
        }
        conn = null
        device = null
        vsAlt = null
        vsAltZero = null
        vsEpIn = null
        vcClaimed = null
        vsClaimed = false
        ownsConnection = false
        sharedConn = false
        altSelected = false
        lastBitmap = null
        bmpA = null
        bmpB = null
        running = false
        optionList = emptyList()
        parsedFormats = emptyList()
        selectedOption = null
    }
}






