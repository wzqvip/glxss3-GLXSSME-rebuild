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
import kotlin.math.min

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
 *   = `dwMaxVideoFrameSize(460800) + 22`，实测**一次 transfer 正好一帧**：
 *   `actual_length = 460812 = 460800 + 12B UVC payload header`；
 * - payload header 是标准 UVC 1.1 的 12 字节头（PTS + SCR 都在）：
 *   `0C 0F|0E <PTS:4> <SCR:6>`  → `bHeaderLength=12`、`bmHeaderInfo` bit1(EOF)=1、
 *   bit0(FID) 逐帧翻转、bit2/bit3(PTS/SCR)=1；后 460800 字节是 YV12
 *   （Y | V | U，实测与原厂画面一致）；
 * - 因此本实现的 URB 尺寸对齐原厂（`frameSize + 22`），并且**只认合法 header**：
 *   固件异常时设备会"整块灌满缓冲区"（`n == urbSize`、首字节是亮度值），
 *   以前那种 `rawPayloadMode` 兜底会把垃圾当图像画出来 → 彩色噪点；
 *   现在直接判定为无效数据并报错，不再刷噪点。
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
         * 原厂 libuvc 的 URB 尺寸 = dwMaxVideoFrameSize + 22（日志 frameBufferSize=460822）。
         * 实测设备一帧 = 460800 + 12B header = 460812，用 22 留 10 字节余量，
         * 既不会截断、也不会让一个 URB 横跨两帧。
         */
        private const val URB_SLACK = 22

        /** 连续多少个无效 URB 就认定"设备在发无效数据"并上报一次 */
        private const val INVALID_LIMIT = 10

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

        // 5) probe / commit
        if (!probeAndCommit(c, alt.id, f, fr)) return false

        // 6) 准备缓冲：URB 对齐原厂 = 单帧净荷 + 22
        frameSize = max(fr.maxFrameSize, estimateFrameBytes(f.mapping, fr.width, fr.height))
        urbSize = frameSize + URB_SLACK
        frameBuf = ByteArray(frameSize + 64)
        transferBuf = ByteArray(urbSize)
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
     * UVC probe/commit。
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

        // 用 GET_CUR 得到的当前值做基底最稳（有些设备校验未用字段必须为 0）
        var probe = ByteArray(34)
        var len = ioIn("GET_CUR(probe)", UVC_GET_CUR, VS_PROBE, probe.size, probe)
        if (len <= 0) {
            len = ioIn("GET_DEF(probe)", UVC_GET_DEF, VS_PROBE, probe.size, probe)
        }
        if (len <= 0) {
            Log.w(TAG, "GET_CUR/GET_DEF(probe) failed, building probe from scratch")
            probe = ByteArray(34)
            len = 34
        }
        if (len < 26) {
            Log.w(TAG, "probe length $len < 26, padding")
            len = 34
        }

        fun putU32(buf: ByteArray, off: Int, v: Int) {
            buf[off] = (v and 0xff).toByte()
            buf[off + 1] = ((v shr 8) and 0xff).toByte()
            buf[off + 2] = ((v shr 16) and 0xff).toByte()
            buf[off + 3] = ((v shr 24) and 0xff).toByte()
        }

        // bmHint = 1 → 用 dwFrameInterval
        probe[0] = 1
        probe[1] = 0
        probe[2] = f.index.toByte()
        probe[3] = fr.index.toByte()
        putU32(probe, 4, fr.defaultInterval)
        val frameBytes = max(fr.maxFrameSize, estimateFrameBytes(f.mapping, fr.width, fr.height))
        putU32(probe, 18, frameBytes)
        // 640×480 保持原厂验证过的 16 KB（不改已知可用路径）；
        // 720p/1080p 的单帧远超 16 KB，probe 里把 maxPayload 提到整帧大小，
        // 否则部分固件会按小的 payload 切帧。
        val payload = if (frameBytes > 1024 * 1024) {
            min(frameBytes, 8 * 1024 * 1024)
        } else if (vsEpIn != null) {
            max(16384, vsEpIn!!.maxPacketSize * 32)
        } else {
            16384
        }
        putU32(probe, 22, payload)

        var ret = ioOut("SET_CUR(probe)", VS_PROBE, probe, len)
        Log.i(TAG, "SET_CUR(probe) -> $ret (len=$len fmt=${f.index} frame=${fr.index} interval=${fr.defaultInterval})")
        if (ret < 0) {
            // 有些设备只接受 26 字节
            if (len > 26) {
                ret = ioOut("SET_CUR(probe,26)", VS_PROBE, probe, 26)
                Log.i(TAG, "SET_CUR(probe, 26) -> $ret")
                if (ret >= 0) len = 26
            }
        }
        if (ret < 0) {
            lastError = "SET_CUR(probe) failed: $ret"
            Log.e(TAG, lastError!!)
            return false
        }

        // 读回 probe 看设备给了什么（失败不致命）
        val back = ByteArray(34)
        val n = ioIn("GET_CUR(probe,back)", UVC_GET_CUR, VS_PROBE, back.size, back)
        if (n >= 4) {
            Log.i(TAG, "probe result: bFormatIndex=${back[2]} bFrameIndex=${back[3]} " +
                "maxVideoFrame=${u32(back, 18)} maxPayload=${u32(back, 22)}")
        }

        // commit：把 probe 内容原样提交
        val commitSrc = if (n > 0) back else probe
        ret = ioOut("SET_CUR(commit)", VS_COMMIT, commitSrc, len)
        Log.i(TAG, "SET_CUR(commit) -> $ret")
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
        Oplog.logf("UVC", "start reader ep=0x%02x urb=%d", ep.address, transferBuf.size)
        thread = Thread({ readLoop(c, ep) }, "UvcReader").also {
            it.priority = Thread.NORM_PRIORITY + 1
            it.start()
        }
    }

    /**
     * 读帧循环。
     *
     * 真机行为（Frida 抓原厂 libusb，见 docs §8）：URB = frameSize+22，
     * **一次 bulk 传输就返回一整帧 payload**：`n = 460812`、
     * `buf[0]=0x0C`（12B header）、`buf[1]` 的 bit1(EOF)=1。
     *
     * 判定规则因此很简单也很严格：
     * - `bHeaderLength` 不在 2..12、或 ERR 位置 1 → 这个 URB 是无效数据，
     *   **不拼入帧、不解码、不刷屏**（以前的 rawPayloadMode 兜底会把垃圾当图像画 → 彩色噪点）；
     * - header 合法 → 跳过 header 拼 payload，EOF 置位就交一帧。
     *
     * 固件异常时设备的典型症状是"整块灌满 URB"（`n == urbSize`，首字节是亮度值），
     * 会命中无效分支；连续 [INVALID_LIMIT] 次后回调一次 [Listener.onError]，
     * 提示眼镜固件状态异常、需要重新上电或重新加载固件。
     */
    private fun readLoop(c: UsbDeviceConnection, ep: UsbEndpoint) {
        val buf = transferBuf
        var timeouts = 0
        var fastFails = 0
        var lastLog = 0L
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
                    Oplog.logf("UVC", "bulk-in timeout x3 (no data, urb=%d)", urbSize)
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastLog >= 3000) {
                    Log.w(TAG, "bulk in timeout x$timeouts (no data) fastFails=$fastFails")
                    lastLog = now
                }
                if (fast) Thread.sleep(20)
                continue
            }
            if (n == 0) {
                Thread.sleep(10)
                continue
            }
            timeouts = 0
            fastFails = 0
            dumpUrb(buf, n)

            val hdrLen = buf[0].toInt() and 0xff
            val hdrInfo = if (n >= 2) buf[1].toInt() and 0xff else 0
            val errBit = (hdrInfo and 0x40) != 0
            val hdrOk = hdrLen in 2..MAX_HDR && hdrLen <= n
            if (!hdrOk || errBit) {
                // 没有合法 header：
                //  - 已经同步到某帧内（续传）→ 追加
                //  - 未同步（流从帧中间开始）→ 丢弃，等下一个 0x0C 开头的新帧
                // 之前无条件追加会让整帧永久错位 → 彩色噪点。
                if (inSync) {
                    append(buf, 0, n)
                    if (frameLen >= frameSize) { deliver(); inSync = false }
                } else {
                    unalignedDrop++
                    onInvalidUrb(n, hdrLen, hdrInfo,
                        if (!hdrOk) "bHeaderLength=$hdrLen" else "ERR bit set")
                }
                continue
            }
            invalidStreak = 0
            lastHdrLen = hdrLen
            lastHdrInfo = hdrInfo
            val eof = (hdrInfo and 0x02) != 0
            if (!inSync) { frameLen = 0; inSync = true }
            append(buf, hdrLen, n - hdrLen)
            if (eof) {
                deliver()
                inSync = false
            } else if (frameLen >= frameSize) {
                // 设备没按 UVC 语义打 EOF：按长度兜底交帧（真机不会走到这里）
                deliver()
            }
        }
        frameLen = 0
        // 读循环退出（设备断开/连续 fast-fail）时把 running 归位，否则上层会以为还在出流
        running = false
        Oplog.logf("UVC", "reader stopped (invalidUrb=%d)", invalidTotal)
        Log.i(TAG, "reader stopped (invalidUrb=$invalidTotal)")
    }

    /** 无效 URB：丢弃 + 计数；连续太多次就上报一次（绝不再把垃圾画成噪点） */
    private fun onInvalidUrb(n: Int, hdrLen: Int, hdrInfo: Int, why: String) {
        invalidStreak++
        invalidTotal++
        frameLen = 0
        if (invalidStreak <= 2) {
            Log.w(
                TAG,
                "invalid UVC payload ($why): n=$n hdrLen=$hdrLen hdrInfo=0x%02x urbSize=$urbSize"
                    .format(hdrInfo)
            )
            // 前 16 字节进 oplog：区分「整块灌满（首字节是亮度值）」还是「header 错位」
            Oplog.logf(
                "UVC", "invalid URB n=%d hdr=0x%02x info=0x%02x first16=%s",
                n, hdrLen, hdrInfo,
                (0 until minOf(16, n)).joinToString("") { "%02x".format(transferBuf[it]) }
            )
            // 第一个坏 URB 原样落盘，便于离线分析（是「整块灌满」还是「header 错位」）
            if (invalidStreak == 1 && n in 1..transferBuf.size) {
                try {
                    File(ctx.filesDir, "uvc_bad.bin").writeBytes(transferBuf.copyOf(n))
                } catch (ignored: Throwable) {
                }
            }
        }
        if (invalidStreak == INVALID_LIMIT && !invalidReported) {
            invalidReported = true
            val msg = "眼镜在发无效数据流（连续 $invalidStreak 个 URB 没有合法 UVC header，n=$n）：" +
                "固件媒体流水线已卡死。请拔插眼镜 USB —— App 会自动重灌固件（03E7:2150 → 2E09:0030）；" +
                "若没自动重灌，等设备出现后用「加载固件（引导模式 03E7:2150）」。"
            Log.e(TAG, msg)
            Oplog.logf("UVC", "invalid streak=%d (%s) n=%d hdr=0x%02x", invalidStreak, why, n, hdrInfo)
            Oplog.dumpCrash("uvc_invalid_stream(streak=$invalidStreak,n=$n)")
            listener?.onError(msg)
        }
    }

    // ---- 原始字节抓包（诊断用，和 Frida 抓原厂那套同样格式） ----

    @Volatile private var dumpOs: FileOutputStream? = null
    @Volatile private var dumpRemaining = 0

    /**
     * 把接下来 [urbs] 个 bulk 传输的**原始字节**写到 [file]，格式：
     * 每个 URB 前置 4 字节小端长度，后接原始数据。
     *
     * 这是排查"固件状态异常"最快的手段：合法数据里每个 URB 应以
     * `0C 0F/0E` 开头、长度 460812；异常时会是 `n == urbSize` 且首字节是亮度值。
     * 拿回 PC 上可以用 `re/liangliang/uvc_frame_decode.py` 直接解析。
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

    private fun append(src: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (frameLen + len > frameBuf.size) {
            // 帧比预期大：扩容（首帧可能比 dwMaxVideoFrameBufferSize 大）
            val newSize = max(frameBuf.size * 2, frameLen + len + 1024)
            Log.w(TAG, "frame buffer grown ${frameBuf.size} -> $newSize")
            frameBuf = frameBuf.copyOf(newSize)
        }
        System.arraycopy(src, off, frameBuf, frameLen, len)
        frameLen += len
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
        listener?.onFrame(bmp, len, fps)
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
        stop()
        releaseInternal()
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






