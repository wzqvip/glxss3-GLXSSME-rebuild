package com.wzqvip.glxsscast.record

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import com.wzqvip.glxsscast.cast.CastFrameTap
import com.wzqvip.glxsscast.uvc.UvcFrameTap
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** 录像源 */
enum class RecordSource(val label: String, val prefix: String) {
    CAST("投屏画面", "cast"),
    UVC("UVC 画面", "uvc")
}

/**
 * H.264 录像：源帧 → 独立线程 → `MediaCodec`(avc, 输入 buffer/Image) → `MediaMuxer` → MP4。
 *
 * 设计要点：
 * - **不阻塞采集侧**：`onCastFrame` / `onUvcFrame` 只做一次拷贝（1MB~1.2MB）后入队，
 *   队列（3 帧）满了直接丢帧并计数；编码/合流全在 `recorder` 线程。
 * - 尺寸固定为开始录像时源的尺寸（投屏 640×400，UVC 640×480），编码器中途不重建；
 *   源尺寸变了就丢帧（`framesDropped`），避免 muxer/编码器崩溃。
 * - 编码器输入优先 `COLOR_FormatYUV420Flexible` + `getInputImage()`（由系统给出
 *   rowStride/pixelStride，最稳）；不支持时退到 `COLOR_FormatYUV420SemiPlanar`(NV12)
 *   或 `COLOR_FormatYUV420Planar`(I420)，按紧排写入。
 * - 只有真正写出过样本（muxer 已 start）才保留文件；源无流（0 帧）时删掉空文件并报错。
 * - 文件：`getExternalFilesDir(Movies)/rec/<cast|uvc>_yyyyMMdd_HHmmss.mp4`
 */
class VideoRecorder(private val ctx: Context) : CastFrameTap, UvcFrameTap {

    companion object {
        private const val TAG = "VideoRecorder"
        private const val MIME = "video/avc"
        private const val FRAME_RATE = 25
        private const val DESIRED_BITRATE = 3_000_000
        private const val I_FRAME_INTERVAL = 1
        /** 帧队列深度：编码跟不上就丢帧，不反压源 */
        private const val QUEUE_DEPTH = 3
        /** 池化 RGBA 帧缓冲个数（队列 + 正在编码的 + 备用） */
        private const val POOL_SIZE = 5
    }

    // ---- 对外状态（UI / 压力测试轮询）----
    @Volatile var isRecording = false; private set
    @Volatile var source = RecordSource.CAST; private set
    @Volatile var outputFile: File? = null; private set
    @Volatile var lastError = ""; private set
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    /** 真正写进 muxer 的帧数（= 编码输出样本数） */
    @Volatile var framesWritten = 0L; private set
    @Volatile var framesDropped = 0L; private set
    @Volatile var bytesWritten = 0L; private set
    /** 首帧~末帧的时长（来自编码器 PTS） */
    @Volatile var videoDurationUs = 0L; private set
    @Volatile var startedAtMs = 0L; private set
    @Volatile var stoppedAtMs = 0L; private set
    @Volatile var colorFormat = 0; private set
    @Volatile var codecName = ""; private set

    /** 录像时钟（从开始录制到现在的墙钟毫秒） */
    val elapsedMs: Long
        get() {
            val stop = stoppedAtMs
            val now = if (stop > 0) stop else System.currentTimeMillis()
            return if (startedAtMs <= 0) 0 else (now - startedAtMs).coerceAtLeast(0)
        }

    /** 编码输出实际帧率 */
    val encodedFps: Float
        get() {
            val sec = videoDurationUs / 1_000_000.0
            return if (sec > 0.001) (framesWritten / sec).toFloat() else 0f
        }

    val bitrateKbps: Int
        get() {
            val sec = elapsedMs / 1000.0
            return if (sec > 0.05) (bytesWritten * 8.0 / sec / 1000.0).toInt() else 0
        }

    // ---- 线程/队列 ----
    private val queue = ArrayBlockingQueue<Frame>(QUEUE_DEPTH)
    private val freeLock = Any()
    private val freeBuffers = ArrayList<ByteArray>()

    @Volatile private var running = false
    @Volatile private var ready = false
    @Volatile private var startError = ""
    @Volatile private var startLatch: CountDownLatch? = null
    private var thread: Thread? = null
    private var originNanos = 0L

    private var yuvBuf = ByteArray(0)
    private var inputPacketSize = 0
    private var kind = Kind.NONE

    private enum class Kind { NONE, IMAGE, NV12, I420 }

    private class Frame(val data: ByteArray, val ptsUs: Long)

    private class EncoderSetup(
        val codec: MediaCodec,
        val kind: Kind,
        val colorFormat: Int,
        val name: String
    )

    /** 一次录像的 muxer 状态 */
    private class MuxState {
        var muxer: MediaMuxer? = null
        var track = -1
        var started = false
        var firstPtsUs = -1L
        var lastPtsUs = -1L
    }

    // ==================================================================
    // 旁路回调（采集线程调用，必须立刻返回）
    // ==================================================================

    override fun onCastFrame(rgba: ByteArray, width: Int, height: Int, ptsUs: Long) {
        if (!isRecording || source != RecordSource.CAST) return
        offerRgba(rgba, width, height)
    }

    override fun onUvcFrame(bitmap: Bitmap, width: Int, height: Int, ptsUs: Long) {
        if (!isRecording || source != RecordSource.UVC) return
        if (width != this.width || height != this.height) {
            framesDropped++
            return
        }
        val buf = obtainBuffer(width * height * 4)
        if (buf == null) {
            framesDropped++
            return
        }
        try {
            bitmap.copyPixelsToBuffer(ByteBuffer.wrap(buf))
        } catch (t: Throwable) {
            Log.w(TAG, "copyPixelsToBuffer failed", t)
            recycle(buf)
            framesDropped++
            return
        }
        if (!queue.offer(Frame(buf, nowUs()))) {
            framesDropped++
            recycle(buf)
        }
    }

    private fun offerRgba(rgba: ByteArray, w: Int, h: Int) {
        if (w != width || h != height) {
            framesDropped++
            return
        }
        if (rgba.size < w * h * 4) {
            framesDropped++
            return
        }
        val buf = obtainBuffer(w * h * 4)
        if (buf == null) {
            framesDropped++
            return
        }
        System.arraycopy(rgba, 0, buf, 0, w * h * 4)
        if (!queue.offer(Frame(buf, nowUs()))) {
            framesDropped++
            recycle(buf)
        }
    }

    private fun nowUs(): Long = (System.nanoTime() - originNanos) / 1000

    private fun obtainBuffer(size: Int): ByteArray? {
        synchronized(freeLock) {
            while (freeBuffers.isNotEmpty()) {
                val b = freeBuffers.removeAt(freeBuffers.size - 1)
                if (b.size >= size) return b
            }
        }
        return null
    }

    private fun recycle(buf: ByteArray) {
        synchronized(freeLock) {
            if (freeBuffers.size < POOL_SIZE) freeBuffers.add(buf)
        }
    }

    // ==================================================================
    // 开始 / 停止
    // ==================================================================

    /**
     * 开始录像（会短暂阻塞，等编码器就绪，通常 < 300ms）。
     *
     * @return null = 成功；否则是失败原因
     */
    @Synchronized
    fun start(src: RecordSource, w: Int, h: Int): String? {
        if (isRecording || running) return "已在录像"
        if (w <= 0 || h <= 0) return "无效分辨率 ${w}x$h"
        if ((w and 1) != 0 || (h and 1) != 0) return "分辨率必须是偶数: ${w}x$h"
        if (w * h * 4 > 8 * 1024 * 1024) return "分辨率过大: ${w}x$h"

        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        val dir = File(base, "rec")
        if (!dir.exists() && !dir.mkdirs()) return "无法创建目录 $dir"
        val name = src.prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val file = File(dir, name)

        // ---- 重置状态 ----
        source = src
        width = w
        height = h
        outputFile = file
        lastError = ""
        startError = ""
        ready = false
        framesWritten = 0
        framesDropped = 0
        bytesWritten = 0
        videoDurationUs = 0
        colorFormat = 0
        codecName = ""
        startedAtMs = System.currentTimeMillis()
        stoppedAtMs = 0
        originNanos = System.nanoTime()
        yuvBuf = ByteArray(w * h * 3 / 2)
        inputPacketSize = w * h * 3 / 2
        kind = Kind.NONE
        queue.clear()
        synchronized(freeLock) {
            freeBuffers.clear()
            repeat(POOL_SIZE) { freeBuffers.add(ByteArray(w * h * 4)) }
        }

        val latch = CountDownLatch(1)
        startLatch = latch
        running = true
        val t = Thread({ recordLoop(file) }, "recorder")
        t.isDaemon = true
        thread = t
        t.start()

        val signalled = try {
            latch.await(3, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!ready || !signalled) {
            running = false
            try {
                t.join(800)
            } catch (_: InterruptedException) {
            }
            thread = null
            isRecording = false
            val err = lastError.ifEmpty { startError.ifEmpty { "编码器启动失败" } }
            return err
        }
        isRecording = true
        Log.i(TAG, "recording started -> ${file.absolutePath} ${w}x$h colorFormat=$colorFormat codec=$codecName")
        return null
    }

    /** 停止录像并收尾（等 muxer 写完，通常 < 1s） */
    @Synchronized
    fun stop() {
        if (!running && !isRecording) return
        running = false
        isRecording = false
        val t = thread
        thread = null
        if (t != null) {
            try {
                t.join(3000)
            } catch (_: InterruptedException) {
            }
        }
        stoppedAtMs = System.currentTimeMillis()
        queue.clear()
        synchronized(freeLock) { freeBuffers.clear() }
        kind = Kind.NONE
        Log.i(TAG, summary())
    }

    fun summary(): String {
        val f = outputFile
        val exist = f != null && f.exists()
        val size = if (exist) f!!.length() else 0L
        return "elapsed=${elapsedMs}ms video=${videoDurationUs / 1000}ms frames=$framesWritten " +
            "dropped=$framesDropped fps=%.1f bitrate=%dkbps size=%dB file=%s err=%s".format(
                Locale.US, encodedFps, bitrateKbps, size, f?.absolutePath ?: "-", lastError
            )
    }

    // ==================================================================
    // 编码线程
    // ==================================================================

    private fun recordLoop(outFile: File) {
        val st = MuxState()
        var codec: MediaCodec? = null
        var fatal = ""
        try {
            val setup = openEncoder() ?: throw IllegalStateException(
                startError.ifEmpty { "找不到可用的 H.264 编码器配置 ($width×$height)" }
            )
            codec = setup.codec
            st.muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            ready = true
            startLatch?.countDown()

            var lastFeedPts = 0L
            while (running) {
                drain(codec, st, 0)
                val frame = pollFrame(10)
                if (frame == null) continue
                lastFeedPts = feed(codec, frame, lastFeedPts)
            }

            // 停止时把队列里最多 3 帧编完，再送 EOS（丢帧可接受，这里只求收尾稳定）
            while (true) {
                val frame = pollFrame(0) ?: break
                lastFeedPts = feed(codec, frame, lastFeedPts)
            }
            pushEos(codec, lastFeedPts)
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline) {
                if (drain(codec, st, 10_000)) break
            }
        } catch (t: Throwable) {
            fatal = t.message ?: t.toString()
            Log.e(TAG, "record loop failed", t)
        } finally {
            try {
                codec?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "codec.stop failed", t)
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            if (st.started) {
                try {
                    st.muxer?.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "muxer.stop failed", t)
                }
            }
            try {
                st.muxer?.release()
            } catch (_: Throwable) {
            }
            if (st.started && framesWritten > 0) {
                videoDurationUs = (st.lastPtsUs - st.firstPtsUs).coerceAtLeast(0)
                if (fatal.isNotEmpty()) lastError = fatal
            } else {
                // 没有样本（源无流/刚 start 就被 stop）→ 删掉空壳 mp4，别留垃圾文件
                outFile.delete()
                if (fatal.isNotEmpty()) lastError = fatal
                else lastError = "录制期间没有帧（源无流），已删除空文件"
            }
            isRecording = false
            running = false
            queue.clear()
            synchronized(freeLock) { freeBuffers.clear() }
            startLatch?.countDown()
            Log.i(TAG, "recorder thread exit: $lastError")
        }
    }

    private fun pollFrame(timeoutMs: Long): Frame? {
        return try {
            if (timeoutMs <= 0) queue.poll() else queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            null
        }
    }

    /** 把一帧送进编码器；返回本帧实际使用的 PTS（保证单调递增） */
    private fun feed(codec: MediaCodec, frame: Frame, lastPts: Long): Long {
        var pts = frame.ptsUs
        if (pts <= lastPts) pts = lastPts + 1000
        val idx = codec.dequeueInputBuffer(10_000)
        if (idx < 0) {
            framesDropped++
            recycle(frame.data)
            return lastPts
        }
        var filled = false
        try {
            filled = fillInput(codec, idx, frame.data)
        } catch (t: Throwable) {
            Log.w(TAG, "fillInput failed", t)
        }
        try {
            if (filled) {
                codec.queueInputBuffer(idx, 0, inputPacketSize, pts, 0)
            } else {
                // 宁可丢一帧也不能把 input buffer 卡死（必须归还）
                codec.queueInputBuffer(idx, 0, 0, lastPts + 1, 0)
                framesDropped++
            }
        } catch (t: Throwable) {
            Log.w(TAG, "queueInputBuffer failed", t)
        }
        recycle(frame.data)
        return if (filled) pts else lastPts
    }

    private fun pushEos(codec: MediaCodec, lastPts: Long) {
        try {
            val idx = codec.dequeueInputBuffer(500_000)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, lastPts + 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "push EOS failed", t)
        }
    }

    /**
     * 拉取所有可用输出样本；有样本写进 muxer。
     * @return 是否收到 EOS
     */
    private fun drain(codec: MediaCodec, st: MuxState, timeoutUs: Long): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try {
                codec.dequeueOutputBuffer(info, timeoutUs)
            } catch (t: Throwable) {
                Log.w(TAG, "dequeueOutputBuffer failed", t)
                return true
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!st.started) {
                        val muxer = st.muxer ?: return false
                        st.track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        st.started = true
                        Log.i(TAG, "muxer started: track=${st.track} ${codec.outputFormat}")
                    }
                }
                idx >= 0 -> {
                    try {
                        val buf = codec.getOutputBuffer(idx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (buf != null && info.size > 0 && st.started) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            st.muxer?.writeSampleData(st.track, buf, info)
                            bytesWritten += info.size
                            framesWritten++
                            if (st.firstPtsUs < 0) st.firstPtsUs = info.presentationTimeUs
                            st.lastPtsUs = info.presentationTimeUs
                            // 实时更新，UI 里录制中也能看到真实 fps
                            videoDurationUs = (st.lastPtsUs - st.firstPtsUs).coerceAtLeast(0)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "writeSampleData failed", t)
                    } finally {
                        try {
                            codec.releaseOutputBuffer(idx, false)
                        } catch (_: Throwable) {
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
                else -> return false
            }
        }
    }

    // ==================================================================
    // 编码器
    // ==================================================================

    private fun openEncoder(): EncoderSetup? {
        val candidates = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        )
        for (cf in candidates) {
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createEncoderByType(MIME)
                val caps = codec.codecInfo.getCapabilitiesForType(MIME)
                if (!caps.colorFormats.contains(cf)) {
                    codec.release()
                    continue
                }
                val vc = caps.videoCapabilities
                if (!vc.isSizeSupported(width, height)) {
                    codec.release()
                    startError = "编码器不支持 ${width}×$height"
                    Log.w(TAG, startError)
                    return null
                }
                val bitrate = DESIRED_BITRATE
                    .coerceIn(vc.bitrateRange.lower, vc.bitrateRange.upper)
                val fmt = MediaFormat.createVideoFormat(MIME, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, cf)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                }
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val k = when (cf) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> Kind.IMAGE
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> Kind.NV12
                    else -> Kind.I420
                }
                kind = k
                colorFormat = cf
                codecName = codec.name
                Log.i(TAG, "encoder ${codec.name} colorFormat=0x${Integer.toHexString(cf)} kind=$k")
                return EncoderSetup(codec, k, cf, codec.name)
            } catch (t: Throwable) {
                Log.w(TAG, "encoder with colorFormat 0x${Integer.toHexString(cf)} failed", t)
                try {
                    codec?.release()
                } catch (_: Throwable) {
                }
            }
        }
        return null
    }

    /** 把 RGBA 帧写进 input buffer（Image 路径按系统给的 stride 写，最稳） */
    private fun fillInput(codec: MediaCodec, idx: Int, rgba: ByteArray): Boolean {
        Rgba2Yuv.i420(rgba, width, height, yuvBuf)
        when (kind) {
            Kind.IMAGE -> {
                val img: Image = codec.getInputImage(idx) ?: run {
                    // 理论上 Flexible 都支持 getInputImage；万一不支持就按 NV12 紧排写
                    Log.w(TAG, "getInputImage null, fall back to normal buffer")
                    kind = Kind.NV12
                    return fillManual(codec, idx)
                }
                try {
                    writeImage(img, yuvBuf)
                } finally {
                    try {
                        img.close()
                    } catch (_: Throwable) {
                    }
                }
                return true
            }
            Kind.NV12, Kind.I420 -> return fillManual(codec, idx)
            else -> return false
        }
    }

    private fun fillManual(codec: MediaCodec, idx: Int): Boolean {
        val ib = codec.getInputBuffer(idx) ?: return false
        ib.clear()
        if (ib.remaining() < inputPacketSize) return false
        val ySize = width * height
        val cSize = ySize / 4
        if (kind == Kind.NV12) {
            ib.put(yuvBuf, 0, ySize)
            for (i in 0 until cSize) {
                ib.put(yuvBuf[ySize + i])
                ib.put(yuvBuf[ySize + cSize + i])
            }
        } else {
            ib.put(yuvBuf, 0, inputPacketSize)
        }
        return true
    }

    /** I420 → Image 平面（尊重 rowStride / pixelStride / 交织 UV） */
    private fun writeImage(image: Image, i420: ByteArray) {
        val planes = image.planes
        val ySize = width * height
        val cSize = ySize / 4
        if (planes.isEmpty()) return
        copyPlane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride, i420, 0, width, height)
        if (planes.size >= 3) {
            copyPlane(
                planes[1].buffer, planes[1].rowStride, planes[1].pixelStride,
                i420, ySize, width / 2, height / 2
            )
            copyPlane(
                planes[2].buffer, planes[2].rowStride, planes[2].pixelStride,
                i420, ySize + cSize, width / 2, height / 2
            )
        } else if (planes.size == 2) {
            // 交织 UV：lineStride = rowStride，pixelStride 一般为 2
            val uv = planes[1]
            val buf = uv.buffer
            val rowStride = uv.rowStride
            val ps = max(1, uv.pixelStride)
            val limit = buf.limit()
            val cw = width / 2
            for (row in 0 until height / 2) {
                val uRow = row * cw
                val vRow = ySize + uRow
                val base = row * rowStride
                for (col in 0 until cw) {
                    val p = base + col * ps
                    if (p + 1 < limit) {
                        buf.put(p, i420[uRow + col])
                        buf.put(p + 1, i420[vRow + col])
                    }
                }
            }
        }
    }

    private fun copyPlane(
        dst: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        src: ByteArray,
        srcOff: Int,
        w: Int,
        h: Int
    ) {
        val limit = dst.limit()
        for (row in 0 until h) {
            val srcRow = srcOff + row * w
            if (pixelStride == 1) {
                val dp = row * rowStride
                val n = min(w, limit - dp)
                if (n > 0) {
                    dst.position(dp)
                    dst.put(src, srcRow, n)
                }
            } else {
                for (col in 0 until w) {
                    val p = row * rowStride + col * pixelStride
                    if (p < limit) dst.put(p, src[srcRow + col])
                }
            }
        }
    }
}
