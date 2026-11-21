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
import com.wzqvip.glxsscast.lcd.LcdControl
import com.wzqvip.glxsscast.lcd.LcdProtocol
import com.wzqvip.glxsscast.usb.UsbLink

/**
 * 实时投屏：MediaProjection → VirtualDisplay(640x400) → ImageReader(RGBA_8888)
 * → 16B 头 + 1,024,000B 整帧 → USB bulk (ep 0x01)。
 */
class CastService : Service() {

    companion object {
        const val ACTION_START = "com.wzqvip.glxsscast.START"
        const val ACTION_STOP = "com.wzqvip.glxsscast.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val TAG = "CastService"
        private const val CHANNEL_ID = "cast"
        private const val NOTIF_ID = 1001

        @Volatile var running = false; private set
        @Volatile var frames = 0L; private set
        @Volatile var fps = 0f; private set
        @Volatile var lastError = ""; private set
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
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
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

        val ht = HandlerThread("cast").also { it.start() }
        thread = ht
        handler = Handler(ht.looper)

        val reader = ImageReader.newInstance(
            LcdProtocol.WIDTH, LcdProtocol.HEIGHT, PixelFormat.RGBA_8888, 2
        )
        imageReader = reader
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, handler)

        virtualDisplay = mp.createVirtualDisplay(
            "glxss-cast",
            LcdProtocol.WIDTH, LcdProtocol.HEIGHT, 1,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        running = true
        countWindow = 0
        windowStart = System.currentTimeMillis()
        Log.i(TAG, "casting started")
    }

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
            val rgba: ByteArray
            if (rowStride == w * pixelStride) {
                rgba = ByteArray(buf.remaining())
                buf.get(rgba)
            } else {
                // 处理行对齐 padding
                rgba = ByteArray(w * h * 4)
                val row = ByteArray(w * pixelStride)
                for (y in 0 until h) {
                    buf.position(y * rowStride)
                    buf.get(row, 0, minOf(row.size, buf.remaining()))
                    System.arraycopy(row, 0, rgba, y * w * 4, minOf(row.size, w * 4))
                }
            }
            if (rgba.size == LcdProtocol.PAYLOAD_SIZE) {
                val ok = usb.sendFrame(LcdProtocol.frame(rgba))
                if (ok) {
                    frames++
                    countWindow++
                    val now = System.currentTimeMillis()
                    if (now - windowStart >= 1000) {
                        fps = countWindow * 1000f / (now - windowStart)
                        countWindow = 0
                        windowStart = now
                    }
                } else {
                    lastError = "发送失败"
                }
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
