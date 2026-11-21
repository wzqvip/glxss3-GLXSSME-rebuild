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
 * 关键点（真机 / 厂商日志证据）：
 * - 设备自称 UVC 1.10，VS 接口用 **bulk** 端点（不是 isochronous），所以必须
 *   `setInterface(alt=1)` 才有 0x81；
 * - 厂商日志 `frameSize=(640,480)@YV12` + `frameBufferSize=460822`
 *   （640*480*1.5 = 460800，+22 字节头部余量）说明设备最可能只给
 *   **未压缩 YV12 @640x480**，不一定是 MJPEG。所以这里运行时**解析描述符**
 *   来决定格式，MJPEG / 各 YUV 都支持；
 * - probe/commit 走 INTF1 的 class 控制传输（SET_CUR 0x01 / GET_CUR 0x81，
 *   wValue=0x0100 探针、0x0200 提交）。
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
    private var transferBuf = ByteArray(0)
    private var rawPayloadMode = false            // 设备不带 UVC payload header 时的兜底
    @Volatile private var lastBitmap: Bitmap? = null
    private var framesInWindow = 0
    private var windowStart = 0L
    @Volatile private var fps = 0f

    val isOpen: Boolean get() = conn != null
    val isRunning: Boolean get() = running

    /** 诊断字符串：本次协商到的格式/帧/带宽 */
    fun negotiatedInfo(): String {
        val f = format ?: return "not negotiated"
        val fr = frame ?: return "format=$f"
        return "format=${f.name}(idx=${f.index}) ${fr.width}x${fr.height} " +
            "frameIdx=${fr.index} maxFrame=${fr.maxFrameSize}B interval=${fr.defaultInterval}(=${String.format(Locale.US, "%.1f", fr.fps)}fps)"
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

        // 6) 准备缓冲
        val need = max(fr.maxFrameSize, estimateFrameBytes(f.mapping, fr.width, fr.height))
        frameBuf = ByteArray(need + 1024)
        transferBuf = ByteArray(min(max(need, 64 * 1024) + 1024, 1024 * 1024))
        frameLen = 0
        rawPayloadMode = false
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
     * 读帧循环。每次 bulk 传输 = 一个 UVC payload（header + body），
     * EOF 位置 1 表示一帧结束。
     */
    private fun readLoop(c: UsbDeviceConnection, ep: UsbEndpoint) {
        val buf = transferBuf
        var timeouts = 0
        var badHeaders = 0
        while (running) {
            val n = try {
                c.bulkTransfer(ep, buf, buf.size, BULK_TIMEOUT)
            } catch (t: Throwable) {
                Log.e(TAG, "bulkTransfer threw", t)
                break
            }
            if (n < 0) {
                timeouts++
                if (timeouts % 20 == 0) Log.w(TAG, "bulk in timeout x$timeouts (no data)")
                continue
            }
            if (n == 0) continue
            timeouts = 0

            var off = 0
            val headerLen = buf[0].toInt() and 0xff
            if (!rawPayloadMode && (headerLen < 2 || headerLen > 12 || headerLen > n)) {
                badHeaders++
                if (badHeaders <= 3) Log.w(TAG, "bad UVC header len=$headerLen (n=$n), switching to raw payload mode")
                rawPayloadMode = true
            }
            if (rawPayloadMode) {
                append(buf, 0, n)
            } else {
                val info = buf[1].toInt() and 0xff
                val err = (info and 0x40) != 0
                val eof = (info and 0x02) != 0
                val fidToggle = (info and 0x01) != 0
                if (err) {
                    Log.w(TAG, "payload error flag set, dropping frame (fid=$fidToggle)")
                    frameLen = 0
                    continue
                }
                off = headerLen
                append(buf, off, n - off)
                if (eof) {
                    deliver()
                }
            }
            // raw 模式下没有 EOF 标记，靠攒够整帧来判断
            if (rawPayloadMode) {
                val need = estimateFrameBytes(
                    format?.mapping ?: PixelMapping.UNKNOWN, frameWidth, frameHeight
                )
                if (frameLen >= need) deliver()
            }
        }
        frameLen = 0
        Log.i(TAG, "reader stopped")
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

    private fun yv12ToBitmap(src: ByteArray, len: Int, w: Int, h: Int, uFirst: Boolean): Bitmap? {
        val ySize = w * h
        val cSize = ySize / 4
        if (len < ySize + 2 * cSize) return null
        // YV12: Y | V | U      I420: Y | U | V
        val vOff = if (uFirst) ySize + cSize else ySize
        val uOff = if (uFirst) ySize else ySize + cSize
        if (argb.size != ySize) argb = IntArray(ySize)
        Yuv.yuv420ToArgb(argb, src, uOff, vOff, w, h)
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun nvToBitmap(src: ByteArray, len: Int, w: Int, h: Int, swap: Boolean): Bitmap? {
        val ySize = w * h
        if (len < ySize + ySize / 2) return null
        if (argb.size != ySize) argb = IntArray(ySize)
        Yuv.nv12ToArgb(argb, src, ySize, w, h, swap)
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun yuy2ToBitmap(src: ByteArray, len: Int, w: Int, h: Int, uyvy: Boolean = false): Bitmap? {
        val ySize = w * h
        if (len < ySize * 2) return null
        if (argb.size != ySize) argb = IntArray(ySize)
        Yuv.yuy2ToArgb(argb, src, w, h, uyvy)
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
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
        running = false
    }
}
