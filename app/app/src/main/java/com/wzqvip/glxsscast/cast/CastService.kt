package com.wzqvip.glxsscast.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import com.wzqvip.glxsscast.lcd.LcdControl
import com.wzqvip.glxsscast.lcd.LcdProtocol
import com.wzqvip.glxsscast.usb.UsbLink

/**
 * 实时投屏：MediaProjection → VirtualDisplay(宽度 640、高度按手机屏幕比例)
 * → ImageReader(RGBA_8888) → 行映射到 640×400 → 16B 头 + 1,024,000B → USB bulk。
 *
 * 画面模式（fillMode）：
 *   0 = FIT     保持比例、上下留黑边
 *   1 = CROP    保持比例、居中裁剪填满（默认，无黑边不变形）
 *   2 = STRETCH 拉伸填满（可能有形变）
 */
class CastService : Service() {

    companion object {
        const val ACTION_START = "com.wzqvip.glxsscast.START"
        const val ACTION_STOP = "com.wzqvip.glxsscast.STOP"
        const val ACTION_SET_MODE = "com.wzqvip.glxsscast.SET_MODE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_MODE = "mode"

        const val MODE_FIT = 0
        const val MODE_CROP = 1
        const val MODE_STRETCH = 2

        private const val TAG = "CastService"
        private const val CHANNEL_ID = "cast"
        private const val NOTIF_ID = 1001

        @Volatile var running = false; private set
        @Volatile var frames = 0L; private set
        @Volatile var fps = 0f; private set
        @Volatile var lastError = ""; private set
        @Volatile var fillMode = MODE_CROP
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private lateinit var usb: UsbLink

    private var sending = false
    private var countWindow = 0L
    private var windowStart = 0L

    // 复用缓冲，避免每帧 1MB 分配
    private var rgbaBuf: ByteArray? = null
    private var frameBuf: ByteArray? = null
    private var captureW = LcdProtocol.WIDTH
    private var captureH = LcdProtocol.HEIGHT

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCasting()
        }
    }

    override fun onCreate() {
        super.onCreate()
        usb = UsbLink(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCasting()
                stopSelf()
            }
            ACTION_SET_MODE -> {
                val m = intent.getIntExtra(EXTRA_MODE, MODE_CROP)
                fillMode = m
                if (running) {
                    // 模式变化：重建投屏（需要重新授权，故仅记录，下次启动生效）
                    lastError = "画面模式已切换为 ${modeName(m)}（下次开始投屏生效）"
                }
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                fillMode = intent.getIntExtra(EXTRA_MODE, fillMode)
                startForegroundCompat()
                if (data != null) startCasting(code, data) else lastError = "缺少投影授权数据"
            }
        }
        return START_NOT_STICKY
    }

    private fun modeName(m: Int) = when (m) {
        MODE_FIT -> "适应(黑边)"
        MODE_STRETCH -> "拉伸"
        else -> "裁剪"
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "投屏", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GLXSS3 Cast")
            .setContentText("正在投屏到眼镜")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /** 手机屏幕宽高（用于计算与 640 同比例的抓取高度） */
    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val wm = getSystemService(WindowManager::class.java)
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun startCasting(resultCode: Int, data: Intent) {
        if (running) return
        if (!usb.isOpen) {
            val dev = usb.findDevice()
            if (dev == null || !usb.open(dev)) {
                lastError = "USB 未连接（找不到 0x2E09:0x0030）"
                return
            }
        }
        usb.connection?.let { LcdControl.setDisplayMode(it, 2) }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) {
            lastError = "MediaProjection 授权失败"
            return
        }
        mp.registerCallback(projectionCallback, Handler(mainLooper))
        projection = mp

        val (sw, sh) = screenSize()
        captureH = if (fillMode == MODE_FIT) LcdProtocol.HEIGHT
        else (LcdProtocol.WIDTH.toLong() * sh / sw).toInt().coerceIn(64, 4000)
        captureW = LcdProtocol.WIDTH

        rgbaBuf = ByteArray(LcdProtocol.PAYLOAD_SIZE)
        frameBuf = ByteArray(LcdProtocol.HEADER_SIZE + LcdProtocol.PAYLOAD_SIZE).also { fb ->
            fb[0] = 1
            val total = fb.size
            fb[4] = (total and 0xFF).toByte()
            fb[5] = ((total ushr 8) and 0xFF).toByte()
            fb[6] = ((total ushr 16) and 0xFF).toByte()
            fb[7] = ((total ushr 24) and 0xFF).toByte()
        }

        val ht = HandlerThread("cast").also { it.start() }
        thread = ht
        handler = Handler(ht.looper)

        val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, handler)

        virtualDisplay = mp.createVirtualDisplay(
            "glxss-cast",
            captureW, captureH, 1,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        running = true
        countWindow = 0
        windowStart = System.currentTimeMillis()
        Log.i(TAG, "casting started ${captureW}x$captureH mode=$fillMode")
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onFrame(r: ImageReader) {
        val img = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            if (sending) return
            sending = true
            val w = img.width
            val h = img.height
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowBytes = minOf(w * pixelStride, rowStride)
            val dst = rgbaBuf ?: return
            val dstW = LcdProtocol.WIDTH
            val dstH = LcdProtocol.HEIGHT
            val dstRow = dstW * 4

            fun copyRow(srcRow: Int, dstRowIdx: Int) {
                if (srcRow < 0 || srcRow >= h) return
                buf.position(srcRow * rowStride)
                val n = minOf(rowBytes, buf.remaining(), dst.size - dstRowIdx * dstRow)
                if (n > 0) buf.get(dst, dstRowIdx * dstRow, n)
            }

            when (fillMode) {
                MODE_STRETCH -> {
                    for (y in 0 until dstH) copyRow(y * h / dstH, y)
                }
                MODE_CROP -> {
                    // 保持比例：纵向居中裁剪出 400 行（抓取宽 640 与手机同比例）
                    val keep = minOf(dstH, h)
                    val startRow = ((h - keep) / 2).coerceAtLeast(0)
                    for (y in 0 until dstH) {
                        val src = startRow + (y.toLong() * keep / dstH).toInt()
                        copyRow(src, y)
                    }
                }
                else -> { // MODE_FIT：保持比例，上下黑边
                    java.util.Arrays.fill(dst, 0)
                    val contentH = (dstW.toLong() * h / w).toInt().coerceAtMost(dstH)
                    val top = (dstH - contentH) / 2
                    for (y in 0 until contentH) {
                        copyRow(y * h / contentH, top + y)
                    }
                }
            }

            val fb = frameBuf ?: return
            System.arraycopy(dst, 0, fb, LcdProtocol.HEADER_SIZE, dst.size)
            val ok = usb.sendFrame(fb)
            if (ok) {
                frames++
                countWindow++
                val now = System.currentTimeMillis()
                if (now - windowStart >= 1000) {
                    fps = countWindow * 1000f / (now - windowStart)
                    Log.i(TAG, "fps=${fps} frames=${frames} capture=${captureW}x${captureH} mode=$fillMode")
                    countWindow = 0
                    windowStart = now
                }
            } else {
                lastError = "发送失败"
            }
        } catch (e: Exception) {
            lastError = "帧处理异常: ${e.message}"
        } finally {
            sending = false
            img.close()
        }
    }

    private fun stopCasting() {
        running = false
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
        usb.connection?.let { LcdControl.setDisplayMode(it, 1) }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopCasting()
        usb.close()
        super.onDestroy()
    }
}
