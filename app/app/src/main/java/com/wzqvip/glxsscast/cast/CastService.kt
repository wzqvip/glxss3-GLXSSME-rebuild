package com.wzqvip.glxsscast.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.Icon
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
import android.view.Display
import android.view.WindowManager
import com.wzqvip.glxsscast.lcd.LcdControl
import com.wzqvip.glxsscast.lcd.LcdProtocol
import com.wzqvip.glxsscast.settings.Prefs
import com.wzqvip.glxsscast.usb.UsbLink
import java.nio.ByteBuffer

/**
 * 实时投屏：MediaProjection → VirtualDisplay（宽高比 = 当前屏幕方向）→ ImageReader(RGBA)
 * → 映射到 640×400 → 16B 头 + 1,024,000B → USB bulk。
 *
 * 画面模式：0=适应(黑边) 1=裁剪(填满, cover) 2=拉伸(填满)。
 *
 * 横屏黑边修复：
 *  - VirtualDisplay 尺寸在创建/旋转时都按「当前屏幕」的宽高比算，避免系统在抓取图里 letterbox；
 *  - 屏幕方向变化时用 `VirtualDisplay.setSurface()` + `resize()` 换到新的 ImageReader
 *    （Android 14+ 不允许同一个 MediaProjection 建第二个 VirtualDisplay，所以不能重建）；
 *  - 裁剪模式用 cover：scale = max(640/w, 400/h)，再叠加「画面调整」的 zoom/offset，
 *    对任意抓取尺寸（包括 resize 失败后残留的 letterbox 图）都居中填满。
 */
class CastService : Service() {

    companion object {
        const val ACTION_START = "com.wzqvip.glxsscast.START"
        const val ACTION_STOP = "com.wzqvip.glxsscast.STOP"
        const val ACTION_SET_MODE = "com.wzqvip.glxsscast.SET_MODE"
        const val ACTION_RESYNC = "com.wzqvip.glxsscast.RESYNC"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_MODE = "mode"

        const val MODE_FIT = 0
        const val MODE_CROP = 1
        const val MODE_STRETCH = 2

        private const val TAG = "CastService"
        private const val CHANNEL_ID = "cast"
        private const val NOTIF_ID = 1001
        /** 通知动作用的 requestCode（每个 PendingIntent 必须不同） */
        private const val RC_STOP = 2001
        private const val RC_MODE = 2002
        private const val RC_OPEN = 2003

        @Volatile var running = false; private set
        @Volatile var frames = 0L; private set
        @Volatile var fps = 0f; private set
        @Volatile var lastError = ""; private set
        @Volatile var fillMode = MODE_CROP

        /** 当前抓取尺寸，UI 展示 / 排障用 */
        @Volatile var captureDesc = ""; private set

        // ---- 画面调整（静态字段，UI 直接改，下一帧映射生效，不用重启投屏）----
        /** 缩放 1.0–2.0（1.0 = cover 原始裁切） */
        @Volatile var zoom = 1f
        /** 水平偏移 -1..1（归一化；映射时 = offset * 源宽 / 2，再做 clamp） */
        @Volatile var offsetX = 0f
        /** 垂直偏移 -1..1（归一化；映射时 = offset * 源高 / 2，再做 clamp） */
        @Volatile var offsetY = 0f

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
    private var displayListener: DisplayManager.DisplayListener? = null
    private lateinit var usb: UsbLink

    private var sending = false
    private var countWindow = 0L
    private var windowStart = 0L

    private var rgbaBuf: ByteArray? = null
    private var frameBuf: ByteArray? = null
    private var rowScratch: ByteArray? = null
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
        // 服务可能先于 Activity 起来：先从 SharedPreferences 恢复画面调整/画面模式
        val prefs = Prefs.get(this)
        fillMode = prefs.fillMode.coerceIn(0, 2)
        zoom = prefs.zoom
        offsetX = prefs.offsetX
        offsetY = prefs.offsetY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCasting()
                stopSelf()
            }
            ACTION_SET_MODE -> {
                // 通知栏「画面模式」不传 EXTRA_MODE → 循环 适应 → 裁剪 → 拉伸 → 适应
                val m = if (intent.hasExtra(EXTRA_MODE)) {
                    intent.getIntExtra(EXTRA_MODE, MODE_CROP).coerceIn(0, 2)
                } else {
                    (fillMode + 1) % 3
                }
                // 注意：Android 14+ 的 MediaProjection 只能建一个 VirtualDisplay，
                // 不能重建管线；抓取尺寸固定，模式只在行映射时应用（下一帧生效）。
                fillMode = m
                Prefs.get(this).fillMode = m
                lastError = "画面模式: ${modeName(m)}"
                updateNotification()
                if (!running) stopSelf()
            }
            ACTION_RESYNC -> {
                // 手动重新按当前屏幕方向适配抓取尺寸（DisplayListener 漏掉时兜底）
                val hd = handler
                if (hd != null && running) hd.post { applyScreenSize() }
                else lastError = "未投屏，无法重新适配方向"
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

    /** 通知栏动作的 PendingIntent（FLAG_IMMUTABLE：Android 12+ 必须显式声明可变性） */
    private fun pending(action: String): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(
            this, if (action == ACTION_STOP) RC_STOP else RC_MODE,
            Intent(this, CastService::class.java).setAction(action), flags
        )
    }

    /**
     * 投屏前台通知：带「停止投屏」「画面模式」两个动作，
     * 点完不用回 App（PendingIntent → [ACTION_STOP] / [ACTION_SET_MODE]）。
     */
    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, RC_OPEN,
            Intent().setClassName(packageName, "com.wzqvip.glxsscast.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun icon(id: Int): Icon = Icon.createWithResource(this, id)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GLXSS3 Cast · 投屏中")
            .setContentText(
                "画面模式: ${modeName(fillMode)}" +
                    if (captureDesc.isNotEmpty()) " · 抓取 $captureDesc" else ""
            )
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    icon(android.R.drawable.ic_menu_close_clear_cancel), "停止投屏", pending(ACTION_STOP)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    icon(android.R.drawable.ic_menu_crop), "画面模式", pending(ACTION_SET_MODE)
                ).build()
            )
            .build()
    }

    /** 模式切换后刷新通知文案 */
    private fun updateNotification() {
        if (!running) return
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "投屏", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /** 当前屏幕（默认显示器）的真实像素尺寸，随旋转变化 */
    private fun screenSize(): Pair<Int, Int> {
        runCatching {
            val dm = getSystemService(DisplayManager::class.java)
            val d = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (d != null) {
                val p = Point()
                d.getRealSize(p)
                if (p.x > 0 && p.y > 0) return p.x to p.y
            }
        }
        return if (Build.VERSION.SDK_INT >= 30) {
            val wm = getSystemService(WindowManager::class.java)
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    /**
     * 抓取尺寸：保持与屏幕当前方向相同的宽高比，这样系统不会在抓取图里 letterbox。
     * 竖屏（h >= w）：宽固定 640，高按比例；横屏（w > h）：高固定 400（=目标高），宽按比例。
     */
    private fun captureSizeFor(sw: Int, sh: Int): Pair<Int, Int> {
        if (sw <= 0 || sh <= 0) return LcdProtocol.WIDTH to LcdProtocol.HEIGHT
        return if (sh >= sw) {
            val h = (LcdProtocol.WIDTH.toLong() * sh / sw).toInt().coerceIn(64, 4096)
            LcdProtocol.WIDTH to h
        } else {
            val w = (LcdProtocol.HEIGHT.toLong() * sw / sh).toInt().coerceIn(64, 4096)
            w to LcdProtocol.HEIGHT
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
        registerDisplayListener()
        running = true
        countWindow = 0
        windowStart = System.currentTimeMillis()
        updateNotification()
        Log.i(TAG, "casting started mode=$fillMode capture=${captureW}x$captureH")
    }

    /** 按当前屏幕方向创建 ImageReader + VirtualDisplay（只在开始投屏时调用一次） */
    private fun createPipeline() {
        val mp = projection ?: throw IllegalStateException("no projection")
        val hd = handler ?: throw IllegalStateException("no handler")
        val (sw, sh) = screenSize()
        val (w, h) = captureSizeFor(sw, sh)
        captureW = w
        captureH = h
        captureDesc = "${captureW}×${captureH}"

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

    /** 屏幕旋转 / 尺寸变化：换 ImageReader，并 resize 已有的 VirtualDisplay */
    private fun registerDisplayListener() {
        val hd = handler ?: return
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val h = handler ?: return
                // 等系统把旋转后的显示尺寸稳定下来再处理
                h.postDelayed({ applyScreenSize() }, 250)
            }
        }
        dm.registerDisplayListener(listener, hd)
        displayListener = listener
    }

    /**
     * 把抓取尺寸对齐到当前屏幕方向。必须在 cast 线程（handler）上执行，
     * 这样和 onFrame 的 surface 换手是串行的。
     */
    private fun applyScreenSize() {
        if (!running) return
        val vd = virtualDisplay ?: return
        val hd = handler ?: return
        val (sw, sh) = screenSize()
        val (nw, nh) = captureSizeFor(sw, sh)
        if (nw == captureW && nh == captureH) return

        val oldReader = imageReader
        val oldW = captureW
        val oldH = captureH
        val newReader = try {
            ImageReader.newInstance(nw, nh, PixelFormat.RGBA_8888, 2)
        } catch (e: Exception) {
            lastError = "旋转: 新建 ImageReader ${nw}×$nh 失败: ${e.message}"
            return
        }
        newReader.setOnImageAvailableListener({ r -> onFrame(r) }, hd)

        try {
            vd.setSurface(newReader.surface)
            vd.resize(nw, nh, 1)
        } catch (e: Exception) {
            // 退化：保持原抓取尺寸，用户可用「画面调整」的 zoom/offset 手动补偿
            try {
                if (oldReader != null) vd.setSurface(oldReader.surface)
                vd.resize(oldW, oldH, 1)
            } catch (_: Exception) {
            }
            try {
                newReader.setOnImageAvailableListener(null, null)
                newReader.close()
            } catch (_: Exception) {
            }
            lastError = "旋转: resize 到 ${nw}×$nh 失败，保持 ${oldW}×$oldH（可用画面调整补偿）"
            Log.w(TAG, "resize to ${nw}x$nh failed", e)
            return
        }

        imageReader = newReader
        captureW = nw
        captureH = nh
        captureDesc = "${nw}×$nh"
        try {
            oldReader?.setOnImageAvailableListener(null, null)
            oldReader?.close()
        } catch (_: Exception) {
        }
        lastError = "已适配屏幕方向: 抓取 ${nw}×$nh"
        Log.i(TAG, "capture resized ${nw}x$nh (screen ${sw}x$sh)")
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
            // 旋转换过 reader 后，旧 reader 的残留帧直接丢弃
            if (r !== imageReader) return
            if (sending) return
            sending = true
            val w = img.width
            val h = img.height
            val plane = img.planes[0]
            val buf = plane.buffer
            val dst = rgbaBuf ?: return
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            when (fillMode) {
                MODE_STRETCH -> mapStretch(buf, w, h, rowStride, pixelStride, dst)
                MODE_FIT -> mapFit(buf, w, h, rowStride, pixelStride, dst)
                else -> mapCover(buf, w, h, rowStride, pixelStride, dst)
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

    /** 拉伸：忽略比例，把整幅图铺满 640×400 */
    private fun mapStretch(
        buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int, dst: ByteArray
    ) {
        val dstW = LcdProtocol.WIDTH
        val dstH = LcdProtocol.HEIGHT
        val dstRow = dstW * 4
        val rowBytes = minOf(w * pixelStride, rowStride)
        for (y in 0 until dstH) {
            val srcRow = y * h / dstH
            buf.position(srcRow * rowStride)
            val n = minOf(rowBytes, buf.remaining(), dst.size - y * dstRow)
            if (n > 0) buf.get(dst, y * dstRow, n)
        }
    }

    /** 适应：等比缩放后上下留黑边（老行为） */
    private fun mapFit(
        buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int, dst: ByteArray
    ) {
        val dstW = LcdProtocol.WIDTH
        val dstH = LcdProtocol.HEIGHT
        val dstRow = dstW * 4
        val rowBytes = minOf(w * pixelStride, rowStride)
        java.util.Arrays.fill(dst, 0)
        val contentH = (dstW.toLong() * h / w).toInt().coerceAtMost(dstH)
        val top = (dstH - contentH) / 2
        for (y in 0 until contentH) {
            val srcRow = y * h / contentH
            buf.position(srcRow * rowStride)
            val n = minOf(rowBytes, buf.remaining(), dst.size - (top + y) * dstRow)
            if (n > 0) buf.get(dst, (top + y) * dstRow, n)
        }
    }

    /**
     * 裁剪(填满)：cover 变换 —— scale = max(dstW/w, dstH/h) * zoom，
     * 居中裁剪，再叠加归一化偏移（以源图像素为单位 = offset * 源宽/高 / 2），
     * 采样坐标 clamp 到图内，因此任何抓取尺寸都不会留黑边/越界。
     */
    private fun mapCover(
        buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int, dst: ByteArray
    ) {
        if (w <= 0 || h <= 0) return
        val dstW = LcdProtocol.WIDTH
        val dstH = LcdProtocol.HEIGHT
        val dstRow = dstW * 4

        val z = zoom.coerceIn(1f, 4f)
        val scale = maxOf(dstW.toFloat() / w, dstH.toFloat() / h) * z
        // cover 保证 srcW <= w、srcH <= h
        val srcW = (dstW / scale).coerceIn(1f, w.toFloat())
        val srcH = (dstH / scale).coerceIn(1f, h.toFloat())
        val maxLeft = (w - srcW).coerceAtLeast(0f)
        val maxTop = (h - srcH).coerceAtLeast(0f)

        val cx = w / 2f + offsetX.coerceIn(-1f, 1f) * w * 0.5f
        val cy = h / 2f + offsetY.coerceIn(-1f, 1f) * h * 0.5f
        val left = (cx - srcW / 2f).coerceIn(0f, maxLeft)
        val top = (cy - srcH / 2f).coerceIn(0f, maxTop)

        // 每列采样源 x（预计算，逐像素只做索引）
        val xs = IntArray(dstW) { x ->
            (left + (x + 0.5f) * srcW / dstW).toInt().coerceIn(0, w - 1)
        }
        val rowBytes = minOf(w * pixelStride, rowStride)
        var scratch = rowScratch
        if (scratch == null || scratch.size < rowBytes) {
            scratch = ByteArray(rowBytes)
            rowScratch = scratch
        }

        var lastSy = -1
        for (y in 0 until dstH) {
            val sy = (top + (y + 0.5f) * srcH / dstH).toInt().coerceIn(0, h - 1)
            if (sy != lastSy) {
                buf.position(sy * rowStride)
                val n = minOf(rowBytes, buf.remaining())
                buf.get(scratch, 0, n)
                lastSy = sy
            }
            val o = y * dstRow
            var si = o
            if (pixelStride == 4) {
                for (x in 0 until dstW) {
                    val s = xs[x] shl 2
                    dst[si] = scratch[s]
                    dst[si + 1] = scratch[s + 1]
                    dst[si + 2] = scratch[s + 2]
                    dst[si + 3] = scratch[s + 3]
                    si += 4
                }
            } else {
                for (x in 0 until dstW) {
                    val s = xs[x] * pixelStride
                    dst[si] = scratch[s]
                    dst[si + 1] = if (s + 1 < rowBytes) scratch[s + 1] else 0
                    dst[si + 2] = if (s + 2 < rowBytes) scratch[s + 2] else 0
                    dst[si + 3] = if (s + 3 < rowBytes) scratch[s + 3] else 255.toByte()
                    si += 4
                }
            }
        }
    }

    private fun stopCasting() {
        running = false
        try {
            val dl = displayListener
            if (dl != null) {
                getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(dl)
            }
        } catch (_: Exception) {
        }
        displayListener = null
        destroyPipeline()
        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        captureDesc = ""
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
