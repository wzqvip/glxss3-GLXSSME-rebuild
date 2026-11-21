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
 * 实时投屏：MediaProjection → VirtualDisplay(宽 640、高按手机屏比例) → ImageReader(RGBA)
 * → 行映射到 640×400 → 16B 头 + 1,024,000B → USB bulk。
 *
 * 画面模式：0=适应(黑边) 1=裁剪(填满) 2=拉伸(填满)；运行中切换会立即重建管线。
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

        fun modeName(m: Int) = when (m) {
            MODE_FIT -> "适应(黑边)"
            MODE_STRETCH -> "拉伸(填满)"
            else -> "裁剪(填满)"
        }
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
        usb = UsbLink.get(this)
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
                    // 立即生效：重建管线（复用当前 MediaProjection，无需重新授权）
                    try {
                        destroyPipeline()
                        createPipeline()
                        lastError = "画面模式已切换: ${modeName(m)}"
                    } catch (e: Exception) {
                        lastError = "切换模式失败: ${e.message}"
                    }
                } else {
                    lastError = "画面模式: ${modeName(m)}（下次投屏生效）"
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
        if (running) {
            // 已在投屏：只切模式
            fillMode = fillMode
            return
        }
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

        val ht = HandlerThread("cast").also { it.start() }
        thread = ht
        handler = Handler(ht.looper)

        rgbaBuf = ByteArray(LcdProtocol.PAYLOAD_SIZE)
        frameBuf = ByteArray(LcdProtocol.HEADER_SIZE + LcdProtocol.PAYLOAD_SIZE).also { fb ->
            fb[0] = 1
            val total = fb.size
            fb[4] = (total and 0xFF).toByte()
            fb[5] = ((total ushr 8) and 0xFF).toByte()
            fb[6] = ((total ushr 16) and 0xFF).toByte()
            fb[7] = ((total ushr 24) and 0xFF).toByte()
        }

        createPipeline()
        running = true
        countWindow = 0
        windowStart = System.currentTimeMillis()
        Log.i(TAG, "casting started mode=$fillMode")
    }

    /** 按当前 fillMode 创建 ImageReader + VirtualDisplay */
    private fun createPipeline() {
        val mp = projection ?: throw IllegalStateException("no projection")
        val hd = handler ?: throw IllegalStateException("no handler")
        val (sw, sh) = screenSize()
        captureW = LcdProtocol.WIDTH
        captureH = if (fillMode == MODE_FIT) LcdProtocol.HEIGHT
        else (LcdProtocol.WIDTH.toLong() * sh / sw).toInt().coerceIn(64, 4000)

        val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, hd)
        virtualDisplay = mp.createVirtualDisplay(
            "glxss-cast-$fillMode",
            captureW, captureH, 1,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, hd
        )
        Log.i(TAG, "pipeline ${captureW}x$captureH mode=$fillMode")
    }

    private fun destroyPipeline() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
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
                MODE_STRETCH -> for (y in 0 until dstH) copyRow(y * h / dstH, y)
                MODE_CROP -> {
                    val keep = minOf(dstH, h)
                    val startRow = ((h - keep) / 2).coerceAtLeast(0)
                    for (y in 0 until dstH) copyRow(startRow + (y.toLong() * keep / dstH).toInt(), y)
                }
                else -> {
                    java.util.Arrays.fill(dst, 0)
                    val contentH = (dstW.toLong() * h / w).toInt().coerceAtMost(dstH)
                    val top = (dstH - contentH) / 2
                    for (y in 0 until contentH) copyRow(y * h / contentH, top + y)
                }
            }

            val fb = frameBuf ?: return
            System.arraycopy(dst, 0, fb, LcdProtocol.HEADER_SIZE, dst.size)
            if (usb.sendFrame(fb)) {
                frames++
                countWindow++
                val now = System.currentTimeMillis()
                if (now - windowStart >= 1000) {
                    fps = countWindow * 1000f / (now - windowStart)
                    Log.i(TAG, "fps=$fps frames=$frames capture=${captureW}x${captureH} mode=$fillMode")
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
        destroyPipeline()
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
        // USB 连接是进程内共享的（Activity 还在用），这里不关闭
        super.onDestroy()
    }
}

