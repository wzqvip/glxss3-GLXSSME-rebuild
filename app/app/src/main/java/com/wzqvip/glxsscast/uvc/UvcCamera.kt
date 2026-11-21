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
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

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
 * 与现有 [com.wzqvip.glxsscast.usb.UsbLink] 共存：
 * - 二者各自对同一个 [UsbDevice] 调 `openDevice` 是允许的（都拿独立 fd），
 *   UVC 只 claim INTF1，UsbLink 只 claim INTF3（厂商 LCD），互不冲突；
 * - 如果想让二者共用一个 [UsbDeviceConnection]（更省 fd），把
 *   `UsbLink.connection` 传进 [open] 的 `reuse` 参数即可；此时 [stop] 不会关闭它。
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

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    // ---- 选中的流参数 ----
    @Volatile private var format: FormatDesc? = null
    @Volatile private var frame: FrameDesc? = null
    @Volatile private var frameWidth = TARGET_W
    @Volatile private var frameHeight = TARGET_H

    // ---- USB ----
    private var device: UsbDevice? = null
    private var conn: UsbDeviceConnection? = null
    private var vsAlt: UsbInterface? = null        // alt=1，带 bulk IN
    private var vsAltZero: UsbInterface? = null    // 同 id 的 alt=0，stop 时切回去
    private var vsEpIn: UsbEndpoint? = null
    private var ownsConnection = false

    // ---- 线程 ----
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var listener: Listener? = null

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

    // 双缓冲：每帧不再新建 Bitmap（createBitmap 每帧 1.2MB 分配 + 拷贝）
    private var bmpA: Bitmap? = null
    private var bmpB: Bitmap? = null
    private var useA = true

    val isOpen: Boolean get() = conn != null
    val isRunning: Boolean get() = running

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
     * 打开设备并完成 probe/commit。
     *
     * @param reuse 复用已打开的连接（例如 UsbLink.connection）；为 null 时自己 openDevice + 自己 close
     * @return 成功与否；失败时 [lastError] 有原因
     */
    fun open(dev: UsbDevice, reuse: UsbDeviceConnection? = null): Boolean {
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

        // 2) 打开连接
        val c = reuse ?: (usb.openDevice(dev) ?: run {
            lastError = "openDevice() failed"
            Log.e(TAG, lastError!!)
            return false
        })
        conn = c
        ownsConnection = (reuse == null)

        // 2.5) 也 claim VideoControl 接口（原厂 libuvc 会 claim；部分设备要求先有 VC 才出流）
        val vc = (0 until dev.interfaceCount).map { dev.getInterface(it) }
            .firstOrNull { it.interfaceClass == 0x0e && it.interfaceSubclass == 0x01 }
        if (vc != null) {
            val ok = c.claimInterface(vc, true)
            Log.i(TAG, "claim VC iface=#${vc.id} alt=${vc.alternateSetting} -> $ok")
        }

        // 3) claim + 切 alt setting
        if (!c.claimInterface(foundAlt, true)) {
            lastError = "claimInterface(#${foundAlt.id} alt=${foundAlt.alternateSetting}) failed"
            Log.e(TAG, lastError!!)
            releaseInternal()
            return false
        }
        if (!c.setInterface(foundAlt)) {
            // 少数机型要求先 claim alt0 再切 alt1；失败时也继续试（有的设备 setInterface 返回 false 但其实生效）
            Log.w(TAG, "setInterface(alt=${foundAlt.alternateSetting}) returned false, continuing anyway")
        }

        // 4) 解析描述符 → 选格式/帧
        val formats = try {
            parseFormats(c.rawDescriptors)
        } catch (t: Throwable) {
            Log.w(TAG, "parseFormats failed", t)
            emptyList<FormatDesc>()
        }
        Log.i(TAG, "VS formats: " + formats.joinToString { f ->
            "${f.name}#${f.index}" + f.frames.joinToString(prefix = "[", postfix = "]") { fr ->
                "${fr.width}x${fr.height}@${String.format(Locale.US, "%.1f", fr.fps)}"
            }
        })
        if (formats.isNotEmpty()) {
            pickFormat(formats)
        } else {
            // 描述符解析失败：按厂商日志的经验值硬试 YV12 640x480，再退回 MJPEG
            Log.w(TAG, "no VS format descriptors parsed; falling back to guesses")
            format = FormatDesc(1, "YV12", PixelMapping.YV12, 12)
            frame = FrameDesc(1, TARGET_W, TARGET_H, TARGET_W * TARGET_H * 3 / 2, 666666, listOf(666666))
        }

        val f = format ?: run {
            lastError = "no usable video format at ${TARGET_W}x$TARGET_H"
            Log.e(TAG, lastError!!)
            releaseInternal()
            return false
        }
        val fr = frame!!
        frameWidth = fr.width
        frameHeight = fr.height
        Log.i(TAG, "selected: ${negotiatedInfo()}")

        // 5) probe / commit
        if (!probeAndCommit(c, foundAlt.id, f, fr)) {
            releaseInternal()
            return false
        }

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
        Log.i(TAG, "buffers: frameSize=$frameSize urbSize=$urbSize")
        return true
    }

    private var lastError: String? = null
    fun lastError(): String? = lastError

    /** 在描述符解析结果里挑 640x480 的最佳组合 */
    private fun pickFormat(formats: List<FormatDesc>) {
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
                if (best == null || fr.width * fr.height > best!!.second.width * best!!.second.height) {
                    best = f to fr
                }
            }
        }
        if (best != null) {
            format = best.first
            frame = best.second
            // 把同格式里第一个 640x480 的帧也记下来（便于日志）
        }
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
        // 用 GET_CUR 得到的当前值做基底最稳（有些设备校验未用字段必须为 0）
        var probe = ByteArray(34)
        var len = c.controlTransfer(REQ_IN, UVC_GET_CUR, VS_PROBE, ifaceNum, probe, 0, probe.size, CTRL_TIMEOUT)
        if (len <= 0) {
            len = c.controlTransfer(REQ_IN, UVC_GET_DEF, VS_PROBE, ifaceNum, probe, 0, probe.size, CTRL_TIMEOUT)
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
        putU32(probe, 18, max(fr.maxFrameSize, estimateFrameBytes(f.mapping, fr.width, fr.height)))
        val payload = if (vsEpIn != null) max(16384, vsEpIn!!.maxPacketSize * 32) else 16384
        putU32(probe, 22, payload)

        var ret = c.controlTransfer(REQ_OUT, UVC_SET_CUR, VS_PROBE, ifaceNum, probe, 0, len, CTRL_TIMEOUT)
        Log.i(TAG, "SET_CUR(probe) -> $ret (len=$len fmt=${f.index} frame=${fr.index} interval=${fr.defaultInterval})")
        if (ret < 0) {
            // 有些设备只接受 26 字节
            if (len > 26) {
                ret = c.controlTransfer(REQ_OUT, UVC_SET_CUR, VS_PROBE, ifaceNum, probe, 0, 26, CTRL_TIMEOUT)
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
        val n = c.controlTransfer(REQ_IN, UVC_GET_CUR, VS_PROBE, ifaceNum, back, 0, back.size, CTRL_TIMEOUT)
        if (n >= 4) {
            Log.i(TAG, "probe result: bFormatIndex=${back[2]} bFrameIndex=${back[3]} " +
                "maxVideoFrame=${u32(back, 18)} maxPayload=${u32(back, 22)}")
        }

        // commit：把 probe 内容原样提交
        val commitSrc = if (n > 0) back else probe
        ret = c.controlTransfer(REQ_OUT, UVC_SET_CUR, VS_COMMIT, ifaceNum, commitSrc, 0, len, CTRL_TIMEOUT)
        Log.i(TAG, "SET_CUR(commit) -> $ret")
        if (ret < 0 && len > 26) {
            ret = c.controlTransfer(REQ_OUT, UVC_SET_CUR, VS_COMMIT, ifaceNum, commitSrc, 0, 26, CTRL_TIMEOUT)
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
        framesInWindow = 0
        windowStart = System.currentTimeMillis()
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
        while (running) {
            val n = try {
                c.bulkTransfer(ep, buf, buf.size, BULK_TIMEOUT)
            } catch (t: Throwable) {
                Log.e(TAG, "bulkTransfer threw", t)
                break
            }
            if (n < 0) {
                timeouts++
                if (timeouts == 3) Log.w(TAG, "no UVC data yet (1000ms timeout) — device idle?")
                if (timeouts % 100 == 0) Log.w(TAG, "bulk in timeout x$timeouts (no data)")
                continue
            }
            if (n == 0) continue
            timeouts = 0
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
                    if (unalignedDrop % 50 == 1) {
                        Log.w(TAG, "skip unaligned URB n=$n first=0x%02x".format(buf[0].toInt() and 0xff))
                    }
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
        }
        if (invalidStreak == INVALID_LIMIT && !invalidReported) {
            invalidReported = true
            val msg = "UVC 数据没有合法 header（连续 $invalidStreak 个 URB，n=$n）：眼镜在发无效数据流，" +
                "通常是固件状态异常。请给眼镜重新上电，或用「加载固件（引导模式 03E7:2150）」重建一次。"
            Log.e(TAG, msg)
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
        val now = System.currentTimeMillis()
        if (now - windowStart >= 1000) {
            fps = framesInWindow * 1000f / (now - windowStart)
            framesInWindow = 0
            windowStart = now
            Log.i(TAG, "fps=${String.format(Locale.US, "%.1f", fps)} (${f.name} ${w}x${h})")
        }
        lastBitmap = bmp
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

    fun stop() {
        running = false
        val t = thread
        thread = null
        if (t != null && t.isAlive) {
            try {
                t.join(1500)
            } catch (ignored: InterruptedException) {
            }
        }
        // 回到 alt=0（停流），避免下次 open 时设备还停在 alt1
        val c = conn
        val itf = vsAltZero ?: vsAlt
        if (c != null && itf != null) {
            try {
                c.setInterface(itf)
            } catch (ignored: Throwable) {
            }
        }
    }

    fun close() {
        stop()
        releaseInternal()
    }

    private fun releaseInternal() {
        stopRawDump()
        val c = conn
        val itf = vsAlt
        if (c != null && itf != null) {
            try {
                c.releaseInterface(itf)
            } catch (ignored: Throwable) {
            }
        }
        if (c != null && ownsConnection) {
            try {
                c.close()
            } catch (ignored: Throwable) {
            }
        }
        conn = null
        device = null
        vsAlt = null
        vsAltZero = null
        vsEpIn = null
        ownsConnection = false
        lastBitmap = null
        bmpA = null
        bmpB = null
        running = false
    }
}



