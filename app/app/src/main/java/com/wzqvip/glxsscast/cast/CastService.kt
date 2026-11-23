package com.wzqvip.glxsscast.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.wzqvip.glxsscast.diag.Oplog
import com.wzqvip.glxsscast.lcd.LcdControl
import com.wzqvip.glxsscast.lcd.LcdProtocol
import com.wzqvip.glxsscast.settings.Prefs
import com.wzqvip.glxsscast.usb.UsbLink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 录像等旁路消费者：拿到投屏**映射之后、USB 组包之前**的 640×400 RGBA 帧。
 *
 * 调用发生在 cast 线程上，实现必须**立刻返回**（1MB/帧，不能阻塞 USB 发送）；
 * `rgba` 缓冲下一帧就会被复用，需要保留请自行拷贝。
 */
fun interface CastFrameTap {
    fun onCastFrame(rgba: ByteArray, width: Int, height: Int, ptsUs: Long)
}

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
        /** 抓屏线程抓一帧存成 PNG（证据/排障：证明"息屏"后仍有新帧） */
        const val ACTION_DUMP_FRAME = "com.wzqvip.glxsscast.DUMP_FRAME"
        /** 通知栏「HUD」：HUD 叠加开关（不用回 App，也不用退出息屏） */
        const val ACTION_TOGGLE_HUD = "com.wzqvip.glxsscast.TOGGLE_HUD"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_MODE = "mode"

        const val MODE_FIT = 0
        const val MODE_CROP = 1
        const val MODE_STRETCH = 2

        private const val TAG = "CastService"
        private const val CHANNEL_ID = "cast"
        private const val NOTIF_ID = 1001

        /**
         * 帧率上限 30 fps：动画时屏幕更新 25–30 fps，静止时 ~2 fps。
         * 限制 + 「上一帧 bulk OUT 完整返回后才发下一帧」既保证不把 USB 写队列堆满，
         * 也让帧与帧之间留出控制请求的窗口（控制请求另有 [com.wzqvip.glxsscast.usb.UsbGate] 串行化）。
         */
        private const val MAX_FPS = 30
        private const val MIN_FRAME_INTERVAL_MS = 1000L / MAX_FPS

        /** 连续多少帧 bulk OUT 失败就停流（不无限重试把固件继续打崩） */
        private const val SEND_FAIL_LIMIT = 3
        /** 通知动作用的 requestCode（每个 PendingIntent 必须不同） */
        private const val RC_STOP = 2001
        private const val RC_MODE = 2002
        private const val RC_OPEN = 2003
        private const val RC_BLACKOUT = 2004
        private const val RC_HUD = 2005

        @Volatile var running = false; private set
        @Volatile var frames = 0L; private set
        @Volatile var fps = 0f; private set
        @Volatile var lastError = ""; private set
        @Volatile var fillMode = MODE_CROP

        /** 「息屏」遮罩是否正在显示（[BlackoutActivity] 维护；通知文案/告警用） */
        @Volatile var blackout = false; private set

        /**
         * 息屏期间抓到**连续全黑帧**时给出的提示（空 = 正常）。
         *
         * 全黑通常意味着用户在选择抓取范围时点的是「整个屏幕」——那样我们的黑遮罩也会被
         * 抓进去，眼镜上就只剩黑屏。提示用户改选「单个应用 → OpenHeadUnit」。
         */
        @Volatile var blackoutWarning = ""; private set

        /** 最近一次 [ACTION_DUMP_FRAME] 落盘的路径（证据） */
        @Volatile var lastDumpPath = ""; private set
        /** 最近一帧的时间（System.currentTimeMillis），外部判断"是否还在出帧" */
        @Volatile var lastFrameAtWall = 0L; private set

        /** 当前抓取尺寸，UI 展示 / 排障用 */
        @Volatile var captureDesc = ""; private set

        /**
         * AA 内容区（剥掉 letterbox 之后的真实画面）尺寸，空 = 全屏。
         *
         * OpenHeadUnit 全屏时是 `1920×1080`（左右各 210 px 黑边）—— 有这个数字才说明
         * 「AA 画面被完整取到」而不是被 cover 切掉两边。
         */
        @Volatile var contentDesc = ""; private set

        /**
         * AA 内容区识别开关（UI 直接改，下一帧生效）。
         *
         * 开：先从抓取帧里剥掉纯黑 letterbox，再按模式映射 —— 三种模式都不会再切掉 AA 两边。
         * 关：退回整幅映射（旧行为，便于对比/排障）。
         */
        @Volatile var aaContent = true

        /** 录像旁路（Activity 直接挂到服务上，不用 bind；null = 不录像） */
        @Volatile var frameTap: CastFrameTap? = null

        // ---- 画面调整（静态字段，UI 直接改，下一帧映射生效，不用重启投屏）----
        /** 缩放 1.0–2.0（1.0 = cover 原始裁切） */
        @Volatile var zoom = 1f
        /** 水平偏移 -1..1（归一化；映射时 = offset * 源宽 / 2，再做 clamp） */
        @Volatile var offsetX = 0f
        /** 垂直偏移 -1..1（归一化；映射时 = offset * 源高 / 2，再做 clamp） */
        @Volatile var offsetY = 0f

        /**
         * 画面 180° 软件翻转（实时生效）。
         *
         * 真正状态在 [LcdProtocol.flip180]，这样测试图（bars/solid）和投屏帧共用同一个开关；
         * 这里只是别名，写它等价于写 LcdProtocol.flip180。由 [Prefs.flip180] 持久化。
         */
        var flip180: Boolean
            get() = LcdProtocol.flip180
            set(v) { LcdProtocol.flip180 = v }

        fun modeName(m: Int) = when (m) {
            MODE_FIT -> "适应(黑边)"
            MODE_STRETCH -> "拉伸(填满)"
            else -> "裁剪(填满)"
        }

        /** 一键「AA 投屏」用的模式：适应 + 内容区识别 → AA 16:9 完整显示成 640×360。 */
        const val MODE_AA = MODE_FIT

        /** 一键「AA 投屏」：模式 + 内容区识别都设成推荐值（持久化），再启动投屏。 */
        fun applyAaLanding() {
            fillMode = MODE_AA
            aaContent = true
            zoom = 1f
            offsetX = 0f
            offsetY = 0f
        }

        /** 活着的服务实例（[BlackoutActivity]/通知用；null = 没在投屏） */
        @Volatile private var instance: CastService? = null

        /** 由 [BlackoutActivity] 调用：同步"息屏遮罩可见"状态并刷新通知 */
        fun setBlackout(on: Boolean) {
            blackout = on
            if (!on) {
                blackoutWarning = ""
                blackFrameStreak = 0
            }
            try {
                instance?.updateNotification()
            } catch (ignored: Throwable) {
            }
        }

        /** 息屏时连续全黑帧计数（cast 线程写） */
        @Volatile private var blackFrameStreak = 0
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
    private var captureW = LcdProtocol.WIDTH
    private var captureH = LcdProtocol.HEIGHT

    /** AA 内容区跟踪（剥 letterbox 黑边；只在 cast 线程用） */
    private val content = CastGeom.ContentTracker()

    /** [ACTION_DUMP_FRAME]：下一帧落盘 PNG（主线程置位，cast 线程消费） */
    @Volatile private var dumpRequested = false

    /** 上一帧 USB 发出的时刻（elapsedRealtime），用于 30 fps 上限 */
    private var lastFrameAt = 0L
    private var frameSeq = 0L
    private var sendFailStreak = 0
    @Volatile private var stopScheduled = false

    /** USB DETACHED 清理只跑一次（Activity 的 receiver 也会通知停止，避免重复收尾） */
    private val detachCleanup = AtomicBoolean(false)
    private var detachReceiver: BroadcastReceiver? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            try {
                stopCasting()
            } catch (t: Throwable) {
                Oplog.logf("CAST", "projectionCallback 异常 %s", t.javaClass.simpleName)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Oplog.init(this)
        instance = this
        usb = UsbLink.get(this)
        // 服务可能先于 Activity 起来：先从 SharedPreferences 恢复画面调整/画面模式
        val prefs = Prefs.get(this)
        fillMode = prefs.fillMode.coerceIn(0, 2)
        zoom = prefs.zoom
        offsetX = prefs.offsetX
        offsetY = prefs.offsetY
        flip180 = prefs.flip180
        // AA 内容区识别 + HUD 叠加（服务可能先于 Activity 起来）
        aaContent = prefs.aaContentDetect
        content.enabled = aaContent
        CastHud.enabled = prefs.hudOn
        CastHud.alphaPct = prefs.hudAlpha
        CastHud.position = prefs.hudPos
        CastHud.navOnly = prefs.hudNavOnly
        registerDetachReceiver()
    }

    /**
     * 拔线 / 设备重枚举时**自己**停投屏。
     *
     * 之前 CastService 完全不知道设备已经消失，投屏线程会继续对失效 fd 写 1 MB 帧
     * （返 -1 → 连续 3 帧失败才收敛），既污染固件，也让 detach 后的收尾顺序变得不可控。
     */
    private fun registerDetachReceiver() {
        if (detachReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                try {
                    // API 33 才有 getParcelableExtra(name, Class)。华为 PAL-AL00 是 Android 12
                    // (API 31)，直接调两参重载会抛 NoSuchMethodError —— 0.1.5 就是它让
                    // USB 广播（attach/detach）在华为机上变成闪退。
                    @Suppress("DEPRECATION")
                    val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    // null = 系统没带设备信息；只看眼镜（2E09）的 detach
                    if (dev != null && dev.vendorId != UsbLink.VID) return
                    Oplog.logf("CAST", "USB detach(dev=%s) → stop casting", dev?.deviceName ?: "null")
                    if (detachCleanup.compareAndSet(false, true)) {
                        stopCasting()
                        try {
                            stopSelf()
                        } catch (ignored: Throwable) {
                        }
                    }
                } catch (t: Throwable) {
                    Oplog.logf(
                        "CAST", "detach receiver 异常 %s: %s",
                        t.javaClass.simpleName, t.message
                    )
                }
            }
        }
        detachReceiver = r
        try {
            ContextCompat.registerReceiver(
                this, r, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (t: Throwable) {
            Oplog.logf("CAST", "注册 detach receiver 失败 %s: %s", t.javaClass.simpleName, t.message)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Service 生命周期跑在主线程：这里任何未捕获异常 = App 闪退（用户看到「投屏就崩」）
        try {
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
                    if (hd != null && running) hd.post { applyScreenSizeSafe() }
                    else lastError = "未投屏，无法重新适配方向"
                }
                ACTION_DUMP_FRAME -> {
                    // 证据：把"下一帧"原样落成 PNG（投屏目标尺寸 640×400），
                    // 用来证明息屏/遮罩期间抓取管线仍然有帧、而且抓到的是 AA 画面。
                    val hd = handler
                    if (hd != null && running) {
                        dumpRequested = true
                        lastError = "已请求抓一帧 PNG…"
                    } else {
                        lastError = "未投屏，无法抓帧"
                    }
                }
                ACTION_TOGGLE_HUD -> {
                    // 通知栏一键开关叠加层：开着 AA + 息屏时不用退出黑屏就能切
                    CastHud.enabled = !CastHud.enabled
                    CastHud.reset()
                    Prefs.get(this).hudOn = CastHud.enabled
                    lastError = if (CastHud.enabled) {
                        "HUD 叠加: 开（${CastHud.layoutName}）"
                    } else {
                        "HUD 叠加: 关"
                    }
                    updateNotification()
                    if (!running) stopSelf()
                }
                ACTION_START -> {
                    val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                    @Suppress("DEPRECATION")
                    val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                    fillMode = intent.getIntExtra(EXTRA_MODE, fillMode)
                    if (!startForegroundCompat()) {
                        // Android 14 前台服务类型/通知权限问题：不崩，直接退出并留提示
                        lastError = "投屏服务启动失败（前台服务/通知权限）"
                        try {
                            stopCasting()
                        } catch (ignored: Throwable) {
                        }
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    if (data != null) startCasting(code, data) else lastError = "缺少投影授权数据"
                }
            }
        } catch (t: Throwable) {
            Oplog.logf(
                "CAST", "onStartCommand 异常 action=%s %s: %s",
                intent?.action, t.javaClass.simpleName, t.message
            )
            Log.e(TAG, "onStartCommand threw", t)
            lastError = "投屏服务异常: ${t.javaClass.simpleName}: ${t.message}"
            try {
                stopCasting()
            } catch (ignored: Throwable) {
            }
            try {
                stopSelf()
            } catch (ignored: Throwable) {
            }
        }
        return START_NOT_STICKY
    }

    /** 通知栏动作的 PendingIntent（FLAG_IMMUTABLE：Android 12+ 必须显式声明可变性） */
    private fun pending(action: String): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val rc = when (action) {
            ACTION_STOP -> RC_STOP
            ACTION_TOGGLE_HUD -> RC_HUD
            else -> RC_MODE
        }
        return PendingIntent.getService(
            this, rc,
            Intent(this, CastService::class.java).setAction(action), flags
        )
    }

    /**
     * 投屏前台通知：带「停止投屏」「画面模式」「息屏」三个动作，
     * 点完不用回 App（PendingIntent → [ACTION_STOP] / [ACTION_SET_MODE] / [BlackoutActivity]）。
     *
     * 「息屏」用 `getActivity` 直达 [BlackoutActivity]：这条路径是**用户点通知**触发的，
     * 不受 Android 10+ 的后台启动 Activity 限制，也就不需要在 Service 里偷偷 startActivity。
     */
    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, RC_OPEN,
            Intent().setClassName(packageName, "com.wzqvip.glxsscast.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun icon(id: Int): Icon = Icon.createWithResource(this, id)
        val blackoutPi = PendingIntent.getActivity(
            this, RC_BLACKOUT,
            Intent(this, BlackoutActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (blackoutWarning.isNotEmpty()) {
            blackoutWarning
        } else {
            buildString {
                append("画面模式: ").append(modeName(fillMode))
                if (!blackout) {
                    if (captureDesc.isNotEmpty()) append(" · 抓取 ").append(captureDesc)
                } else {
                    append(" · 息屏中")
                }
                if (contentDesc.isNotEmpty()) append(" · AA 内容 ").append(contentDesc)
                if (CastHud.enabled) append(" · HUD ").append(CastHud.layoutName)
            }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(
                if (blackout) "GLXSS3 Cast · 息屏投屏中" else "GLXSS3 Cast · 投屏中"
            )
            .setContentText(text)
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
            .addAction(
                Notification.Action.Builder(
                    icon(android.R.drawable.ic_menu_view),
                    if (blackout) "退出息屏" else "息屏",
                    blackoutPi
                ).build()
            )
            .addAction(
                // HUD 叠加开关：息屏/后台时不用回 App 也能切（第 4 个动作，展开通知可见）
                Notification.Action.Builder(
                    icon(android.R.drawable.ic_menu_info_details),
                    if (CastHud.enabled) "HUD 关" else "HUD 开",
                    pending(ACTION_TOGGLE_HUD)
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

    private fun startForegroundCompat(): Boolean {
        return try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "投屏", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val notif: Notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
            true
        } catch (t: Throwable) {
            // 华为/Android 14：缺 POST_NOTIFICATIONS 或前台服务类型不被允许时会抛
            Oplog.logf(
                "CAST", "startForeground 失败 %s: %s", t.javaClass.simpleName, t.message
            )
            Log.e(TAG, "startForeground failed", t)
            false
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
        // 新一轮投屏允许 detach 收尾再次触发
        detachCleanup.set(false)
        if (!usb.isOpen) {
            // 设备可能刚重枚举（03E7→2E09）或已被拔：openDevice/claim 在部分 ROM 上会抛
            val dev = try {
                usb.findDevice()
            } catch (t: Throwable) {
                Oplog.logf("CAST", "findDevice 异常 %s: %s", t.javaClass.simpleName, t.message)
                null
            }
            val opened = if (dev == null) {
                false
            } else {
                try {
                    usb.open(dev)
                } catch (t: Throwable) {
                    Oplog.logf("CAST", "usb.open 异常 %s: %s", t.javaClass.simpleName, t.message)
                    false
                }
            }
            if (!opened) {
                lastError = "USB 未连接（找不到 0x2E09:0x0030 或打开失败）"
                Oplog.log("CAST", "start aborted: USB not open / device not found")
                return
            }
        }
        Oplog.logf("CAST", "start requested mode=%d usb=%b", fillMode, usb.isOpen)
        try {
            usb.connection?.let { LcdControl.setDisplayMode(it, 2) }
        } catch (t: Throwable) {
            Oplog.logf("CAST", "setDisplayMode(2) 异常 %s: %s", t.javaClass.simpleName, t.message)
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Oplog.logf("CAST", "getMediaProjection 异常 %s: %s", t.javaClass.simpleName, t.message)
            null
        }
        if (mp == null) {
            lastError = "MediaProjection 授权失败"
            return
        }
        try {
            mp.registerCallback(projectionCallback, Handler(mainLooper))
        } catch (t: Throwable) {
            Oplog.logf("CAST", "registerCallback 异常 %s", t.javaClass.simpleName)
            lastError = "投屏授权回调注册失败: ${t.javaClass.simpleName}"
            return
        }
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

        try {
            createPipeline()
            registerDisplayListener()
        } catch (t: Throwable) {
            Oplog.logf(
                "CAST", "创建抓屏管线失败 %s: %s", t.javaClass.simpleName, t.message
            )
            Log.e(TAG, "createPipeline failed", t)
            lastError = "创建抓屏管线失败: ${t.javaClass.simpleName}: ${t.message}"
            stopCasting()
            return
        }
        running = true
        stopScheduled = false
        sendFailStreak = 0
        frameSeq = 0
        lastFrameAt = 0
        countWindow = 0
        windowStart = System.currentTimeMillis()
        content.reset()
        contentDesc = ""
        CastHud.reset()
        updateNotification()
        Oplog.logf("CAST", "started capture=${captureW}x$captureH mode=%d", fillMode)
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
                h.postDelayed({ applyScreenSizeSafe() }, 250)
            }
        }
        dm.registerDisplayListener(listener, hd)
        displayListener = listener
    }

    /**
     * cast Handler 线程入口：ImageReader/VirtualDisplay 在拔线/旋转竞态下会抛，
     * 不能让它打爆 "cast" HandlerThread（那会让投屏静默停摆）。
     */
    private fun applyScreenSizeSafe() {
        try {
            applyScreenSize()
        } catch (t: Throwable) {
            Oplog.logf(
                "CAST", "applyScreenSize 异常 %s: %s", t.javaClass.simpleName, t.message
            )
            Log.w(TAG, "applyScreenSize threw", t)
        }
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
        } catch (e: Throwable) {
            null
        } ?: return
        try {
            if (!running) return
            // 旋转换过 reader 后，旧 reader 的残留帧直接丢弃
            if (r !== imageReader) return
            if (sending) return
            sending = true
            // 设备已被拔（detach 清理可能还在后台线程排队）：别再组帧/写 USB，
            // 直接走一次停止流程，避免继续往消失的 fd 发 1 MB。
            if (!usb.isOpen) {
                if (!stopScheduled) {
                    stopScheduled = true
                    Oplog.log("CAST", "onFrame: USB 已关闭 → 停止投屏")
                    Handler(mainLooper).post {
                        lastError = "投屏已停止：USB 设备已断开"
                        stopCasting()
                    }
                }
                return
            }
            // 帧率上限 30fps：超出的帧直接丢掉（不排队、不补发），保持 USB 写节奏平滑
            val tNow = SystemClock.elapsedRealtime()
            if (tNow - lastFrameAt < MIN_FRAME_INTERVAL_MS) return
            lastFrameAt = tNow
            val seq = ++frameSeq
            val w = img.width
            val h = img.height
            val plane = img.planes[0]
            val buf = plane.buffer
            val dst = rgbaBuf ?: return
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            // 内容区识别（每 10 帧探测一次，内部带迟滞）：OpenHeadUnit 全屏时 AA 画面
            // 左右各有 ~210 px 黑边（实测 x∈[210,2130]），先剥掉黑边再映射，
            // 三种模式都不会再把 AA 左右各切掉 13%。（见 CastGeom 的说明）
            content.enabled = aaContent
            content.tick(buf, w, h, rowStride, pixelStride)
            val cd = if (content.rect == null) "" else content.desc
            if (cd != contentDesc) contentDesc = cd
            CastGeom.map(
                buf, w, h, rowStride, pixelStride, dst,
                fillMode, content.rect, zoom, offsetX, offsetY
            )

            // HUD 叠加：映射完之后、180° 翻转之前画（叠加层与 AA 同方向）。
            CastHud.compose(dst)

            // 180° 软件翻转：映射后再做一次扫描顺序反转（目标行/列都反），
            // 对三种画面模式和任意抓取尺寸都等价，且不需要重启投屏。
            if (flip180) LcdProtocol.flip180InPlace(dst)

            lastFrameAtWall = System.currentTimeMillis()

            // 息屏时的自检：整屏全黑说明抓到的是我们自己的黑遮罩（= 用户选的是"整个屏幕"
            // 而不是"单个应用"），眼镜上就什么都看不到 —— 这种情况下必须明确告警。
            if (blackout) checkBlackoutFrame(dst)

            // 证据落盘（异步，不占 cast 线程）：把这一帧原样写成 PNG
            if (dumpRequested) {
                dumpRequested = false
                dumpFrameAsync(dst.copyOf())
            }

            // 录像旁路：在组装 USB 帧（16B 头 + payload）之前把这一帧 640×400 RGBA 交出去。
            // 消费者只做一次拷贝就返回（满了就丢帧），不会拖慢 USB 发送。
            frameTap?.let { tap ->
                try {
                    tap.onCastFrame(dst, LcdProtocol.WIDTH, LcdProtocol.HEIGHT, System.nanoTime() / 1000)
                } catch (e: Throwable) {
                    Log.w(TAG, "frameTap error", e)
                }
            }

            val fb = frameBuf ?: return
            System.arraycopy(dst, 0, fb, LcdProtocol.HEADER_SIZE, dst.size)
            val t0 = SystemClock.elapsedRealtime()
            val ok = try {
                usb.sendFrame(fb)
            } catch (t: Throwable) {
                Oplog.logf("CAST", "sendFrame 异常 %s: %s", t.javaClass.simpleName, t.message)
                false
            }
            val dt = SystemClock.elapsedRealtime() - t0
            Oplog.logf(
                "LCD_FRAME", "seq=%d size=%d ms=%d ok=%b streak=%d",
                seq, fb.size, dt, ok, if (ok) 0 else sendFailStreak + 1
            )
            if (ok) {
                sendFailStreak = 0
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
                sendFailStreak++
                lastError = "发送失败（连续 $sendFailStreak/${SEND_FAIL_LIMIT}）"
                if (sendFailStreak >= SEND_FAIL_LIMIT && !stopScheduled) {
                    // 收敛：连续失败就停流，留 crash 现场，别再往一个可能已经挂掉的固件灌 1 MB
                    stopScheduled = true
                    Oplog.logf("CAST", "LCD 连续 %d 帧发送失败 → 停止投屏", sendFailStreak)
                    Oplog.dumpCrash("cast_lcd_send_fail(streak=$sendFailStreak)")
                    Handler(mainLooper).post {
                        lastError = "投屏已停止：连续 $sendFailStreak 帧 USB 发送失败"
                        stopCasting()
                    }
                }
            }
        } catch (e: Throwable) {
            // ImageReader/ByteBuffer 在设备拔出的竞态里会抛 IllegalStateException；
            // Catch Throwable 保证 cast 线程不会因为一帧异常就静默死掉。
            lastError = "帧处理异常: ${e.javaClass.simpleName}: ${e.message}"
            Oplog.logf("CAST", "onFrame 异常 %s: %s", e.javaClass.simpleName, e.message)
        } finally {
            sending = false
            try {
                img.close()
            } catch (t: Throwable) {
                Log.w(TAG, "img.close failed", t)
            }
        }
    }

    /**
     * 息屏自检：连续约 1.5 秒抓到纯黑帧 ⇒ 很可能抓的是"整个屏幕"（遮罩被一起抓走）。
     *
     * 采样（每 97 字节取一个）成本可忽略，只在息屏期间跑。判定成功后写
     * [blackoutWarning] 并刷一次通知，让用户在眼镜黑掉的时候知道该改哪个设置。
     */
    private fun checkBlackoutFrame(dst: ByteArray) {
        var any = false
        var i = 0
        val step = 97
        while (i < dst.size) {
            // 只看 RGB（alpha 恒为 255，不能作为"有内容"的依据）
            if (dst[i].toInt() != 0) {
                any = true
                break
            }
            i += step
        }
        if (any) {
            if (blackFrameStreak > 0) {
                blackFrameStreak = 0
                if (blackoutWarning.isNotEmpty()) {
                    blackoutWarning = ""
                    Handler(mainLooper).post { updateNotification() }
                }
            }
            return
        }
        blackFrameStreak++
        if (blackFrameStreak == 45 && blackoutWarning.isEmpty()) {
            blackoutWarning =
                "息屏中抓到全黑画面：投屏授权请选「单个应用 → OpenHeadUnit」"
            Oplog.logf("CAST", "blackout: %d 帧全黑 → 提示用户改选「单个应用」", blackFrameStreak)
            Handler(mainLooper).post { updateNotification() }
        }
    }

    /**
     * 把一帧 640×400 RGBA 存成 PNG（`files/cast-frame.png`），异步写盘。
     *
     * 用途：在没有外部采集设备时，直接证明"投屏链路这一刻收到的画面是什么"。
     * 例如：进入息屏后 dump 一帧，能同时证明 ①仍有新帧 ②抓到的是 AA 画面而不是黑遮罩。
     */
    private fun dumpFrameAsync(rgba: ByteArray) {
        val w = LcdProtocol.WIDTH
        val h = LcdProtocol.HEIGHT
        Thread({
            var path = ""
            try {
                val px = IntArray(w * h)
                var o = 0
                for (i in px.indices) {
                    px[i] = (0xFF shl 24) or
                        ((rgba[o].toInt() and 0xFF) shl 16) or
                        ((rgba[o + 1].toInt() and 0xFF) shl 8) or
                        (rgba[o + 2].toInt() and 0xFF)
                    o += 4
                }
                val bmp = android.graphics.Bitmap.createBitmap(
                    px, w, h, android.graphics.Bitmap.Config.ARGB_8888
                )
                val f = java.io.File(filesDir, "cast-frame.png")
                java.io.FileOutputStream(f).use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                bmp.recycle()
                path = f.absolutePath
            } catch (t: Throwable) {
                Oplog.logf("CAST", "dumpFrame 失败 %s: %s", t.javaClass.simpleName, t.message)
            }
            if (path.isNotEmpty()) {
                lastDumpPath = path
                lastError = "已保存抓帧: $path"
                Oplog.logf(
                    "CAST", "dump frame → %s (frames=%d blackout=%b 全黑帧连续=%d)",
                    path, frames, blackout, blackFrameStreak
                )
            }
        }, "CastDumpFrame").apply { isDaemon = true }.start()
    }
    /**
     * 停止投屏（主线程/投影回调都会走到）。
     *
     * 顺序：先停抓屏管线（不碰 USB），再让后台线程发一次「关屏」命令 ——
     * `controlTransfer` 最长阻塞 2s，且设备可能已经拔掉会抛异常；
     * 以前这段直接在主线程跑，detach/停止投屏时就可能闪退或 ANR。
     */
    private fun stopCasting() {
        try {
            val wasRunning = running
            running = false
            Oplog.logf("CAST", "stop (was=%b frames=%d)", wasRunning, frames)
            try {
                val dl = displayListener
                if (dl != null) {
                    getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(dl)
                }
            } catch (_: Throwable) {
            }
            displayListener = null
            destroyPipeline()
            try {
                projection?.unregisterCallback(projectionCallback)
                projection?.stop()
            } catch (_: Throwable) {
            }
            projection = null
            captureDesc = ""
            content.reset()
            contentDesc = ""
            CastHud.reset()
            // 投屏结束：清掉息屏状态（BlackoutActivity 的看门狗会在 ~2s 内自己退出并恢复亮度）
            blackoutWarning = ""
            blackout = false
            dumpRequested = false
            lastFrameAtWall = 0L
            try {
                thread?.quitSafely()
            } catch (_: Throwable) {
            }
            thread = null
            handler = null
            val c = try {
                usb.connection
            } catch (_: Throwable) {
                null
            }
            if (c != null) {
                Thread({
                    try {
                        LcdControl.setDisplayMode(c, 1)
                    } catch (t: Throwable) {
                        Oplog.logf(
                            "CAST", "关屏命令异常 %s: %s", t.javaClass.simpleName, t.message
                        )
                    }
                }, "CastLcdOff").apply { isDaemon = true }.start()
            }
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            Oplog.logf("CAST", "stopCasting 异常 %s: %s", t.javaClass.simpleName, t.message)
            Log.w(TAG, "stopCasting threw", t)
        }
    }

    override fun onDestroy() {
        try {
            detachReceiver?.let { r ->
                try {
                    unregisterReceiver(r)
                } catch (t: Throwable) {
                    Oplog.logf("CAST", "unregister detach receiver 异常 %s", t.javaClass.simpleName)
                }
            }
            detachReceiver = null
        } catch (ignored: Throwable) {
        }
        stopCasting()
        // USB 连接是进程内共享的（Activity 还在用），这里不关闭
        if (instance === this) instance = null
        super.onDestroy()
    }
}
