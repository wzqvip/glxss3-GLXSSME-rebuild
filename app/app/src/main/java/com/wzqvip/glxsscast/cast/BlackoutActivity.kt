package com.wzqvip.glxsscast.cast

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.wzqvip.glxsscast.diag.Oplog

/**
 * 「息屏」模式：把手机屏幕压到最暗，但**不遮挡**被投的那个 App。
 *
 * ## 为什么不能盖一块纯黑
 *
 * 实测（LG G8X / Android 14，2026-11-22）：Android 14 的「单个应用」抓取本质上还是抓
 * **合成后的显示内容**。一旦我们自己的不透明黑窗口盖住 OpenHeadUnit，系统就把被盖住的
 * 那个窗口从合成里摘掉 —— 抓到的每一帧都是全黑（`cast-frame.png` = 1956 B 纯黑），
 * 眼镜黑屏。整屏抓取更直接：黑遮罩本身就会进画面。
 *
 * 所以这里用**全透明窗口**：
 *  - `windowIsTranslucent` + 透明背景 → 被投 App 仍处于"可见"状态，照常合成、照常被抓；
 *  - 但本窗口是**最顶层焦点窗口**，于是 `screenBrightness = 0f` 生效：框架把背光压到最暗；
 *  - 窗口仍然吃触摸：点屏幕任意位置 = 退出息屏（不用摸黑找按钮）；返回键也退。
 *
 * ## 亮度 0 的两种实现
 *
 * 1. **窗口亮度**（默认，无需 root）：`WindowManager.LayoutParams.screenBrightness = 0f`。
 *    只在本窗口可见期间生效，Activity 一退出系统自动还原 —— 不存在"把用户手机永久变黑"的风险。
 *    代价：有些 ROM 的 0 只是"最暗档"，夜里还看得出 AA 画面。
 * 2. **root 关背光**（可选，`deep=true`）：直接把
 *    `/sys/class/backlight/panel0-backlight/brightness` 写 0，面板真正熄灭，AA 继续渲染。
 *    原值写盘前先存进 SharedPreferences，退出/崩溃后下次启动都会还原（见 [BacklightIo]）。
 *
 * ## 与抓取模式的关系
 *
 * - 单个应用抓取：状态栏/通知不进画面，手机屏幕只剩"极暗的 AA"，眼镜上是最干净的 AA；
 * - 整屏抓取：透明窗口不挡画面，所以也能用（此时状态栏会进画面，建议 OpenHeadUnit
 *   Screen mode = Immersive 把系统栏藏掉）。
 */
class BlackoutActivity : Activity() {

    companion object {
        private const val TAG = "Blackout"

        /** true = 顺便用 root 把面板背光关掉（真正熄屏） */
        const val EXTRA_DEEP = "deep"
    }

    private val ui = Handler(Looper.getMainLooper())

    /** 上一次观察到的帧号/时刻，用于判断"被抓的 App 是不是还在出帧" */
    private var lastFrames = 0L
    private var lastFramesAt = 0L
    private var stallLogged = false
    private var deep = false

    /**
     * 看门狗：
     *  - 投屏已经停了（用户点停止 / 拔线 / USB 连续失败）→ 立刻退出，把屏幕还给用户；
     *  - 30 秒没有新帧 → 只在 oplog 里留证据（不自动退出：AA 静止画面本来就可能不出新帧）。
     */
    private val watchdog = object : Runnable {
        override fun run() {
            var keep = true
            try {
                if (!CastService.running) {
                    Oplog.log("BLACKOUT", "投屏已停止 → 自动退出息屏并恢复亮度")
                    keep = false
                } else {
                    val f = CastService.frames
                    val now = System.currentTimeMillis()
                    if (f != lastFrames) {
                        lastFrames = f
                        lastFramesAt = now
                        stallLogged = false
                    } else if (!stallLogged && now - lastFramesAt > 30_000) {
                        stallLogged = true
                        Oplog.logf(
                            "BLACKOUT", "30s 无新帧（frames=%d, 抓取 %s）—— 被投 App 可能已停止渲染",
                            f, CastService.captureDesc
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "watchdog threw", t)
            }
            if (!keep) {
                try {
                    finish()
                } catch (ignored: Throwable) {
                }
                return
            }
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            deep = intent?.getBooleanExtra(EXTRA_DEEP, false) == true
            // 透明窗口：不遮挡下面的 App（否则 MediaProjection 抓到的会是全黑）
            window.setBackgroundDrawable(ColorDrawable(0x00000000))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 亮度 0 = 框架允许的最暗；只在本窗口可见期间生效，退出即恢复
            window.attributes = window.attributes.apply {
                screenBrightness = 0f
                dimAmount = 0f
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(false)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }

            // 一个完全透明、但可点击的视图：点任意位置退出
            val root = View(this).apply {
                setBackgroundColor(0x00000000)
                isClickable = true
                isFocusable = true
                setOnClickListener { finish() }
            }
            setContentView(root)

            CastService.setBlackout(true)
            lastFrames = CastService.frames
            lastFramesAt = System.currentTimeMillis()
            ui.postDelayed(watchdog, 2000)

            if (deep) {
                Thread({
                    val orig = BacklightIo.readRaw()
                    if (orig == null) {
                        Oplog.log("BLACKOUT", "深度息屏失败：读不到背光（未授权 root？）→ 仅用窗口亮度 0")
                        return@Thread
                    }
                    BacklightIo.savePending(this, orig)
                    val ok = BacklightIo.writeRaw(0)
                    Oplog.logf("BLACKOUT", "深度息屏: 背光 %d → 0 ok=%b", orig, ok)
                }, "BlackoutBacklightOff").apply { isDaemon = true }.start()
            }

            Oplog.logf(
                "BLACKOUT", "进入息屏(透明): brightness=0 deep=%b keepScreenOn=true 抓取=%s frames=%d",
                deep, CastService.captureDesc.ifEmpty { "?" }, CastService.frames
            )
        } catch (t: Throwable) {
            Oplog.logf("BLACKOUT", "进入息屏失败 %s: %s", t.javaClass.simpleName, t.message)
            Log.e(TAG, "onCreate threw", t)
            finish()
        }
    }

    /** 通知栏「退出息屏」再次点进来（singleTop）→ 直接结束 */
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        try {
            finish()
        } catch (ignored: Throwable) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 个别 ROM 会在失焦/回焦后重置窗口亮度，重新压到 0
        if (hasFocus) {
            runCatching {
                window.attributes = window.attributes.apply { screenBrightness = 0f }
            }
        }
    }

    override fun onDestroy() {
        try {
            ui.removeCallbacksAndMessages(null)
        } catch (ignored: Throwable) {
        }
        try {
            // 亮度随窗口消失自动恢复；这里显式写回 -1（跟随系统）双保险
            window.attributes = window.attributes.apply { screenBrightness = -1f }
        } catch (ignored: Throwable) {
        }
        if (deep) {
            Thread({ BacklightIo.restorePending(this) }, "BlackoutBacklightOn")
                .apply { isDaemon = true }.start()
        }
        CastService.setBlackout(false)
        Oplog.logf("BLACKOUT", "退出息屏 frames=%d", CastService.frames)
        super.onDestroy()
    }
}
