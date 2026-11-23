package com.wzqvip.glxsscast.cast

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.wzqvip.glxsscast.diag.Oplog

/**
 * 「息屏」遮罩：全屏纯黑 + 窗口亮度 0 + 保持常亮。
 *
 * 为什么需要它：MediaProjection 抓的是**虚拟显示**的内容，手机物理屏幕亮着就会漏光、
 * 耗电、误触。这里的做法是给手机盖一层纯黑的不透明窗口，并把**本窗口**的屏幕亮度设为 0，
 * 让手机看起来已经灭屏 —— 而 OpenHeadUnit（被「单个应用」抓取的那个 App）照常渲染，
 * 系统仍然把它的窗口内容送到虚拟显示里，于是眼镜上的 AA 画面不受影响。
 *
 * 为什么用「窗口亮度」而不是 `settings put system screen_brightness 0`：
 *  - `WindowManager.LayoutParams.screenBrightness` 只影响本窗口可见期间，
 *    Activity 一退出/被移除，系统自动回到原亮度，**不存在忘记恢复把手机变瞎的风险**；
 *  - 不需要 WRITE_SETTINGS，也不需要 root；也不会改掉用户系统里的亮度设置。
 *  （如果面板最低亮度仍然漏光，见 docs/ch-app.md：可选 root 兜底把背光写到 0。）
 *
 * 退出方式：点屏幕任意位置（整屏都是按钮）、返回键，或者通知栏里的「退出息屏」。
 * 投屏被停掉（拔线/连续发帧失败/用户点停止）时，本 Activity 会自己退出并恢复亮度。
 */
class BlackoutActivity : Activity() {

    companion object {
        private const val TAG = "Blackout"
    }

    private val ui = Handler(Looper.getMainLooper())

    /** 上一次观察到的帧号/时刻，用于判断「被抓的 App 是不是还在出帧」 */
    private var lastFrames = 0L
    private var lastFramesAt = 0L
    private var stallLogged = false

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
            // 窗口本身也画黑（不依赖 ContentView 的绘制时序，避免闪一下白/灰）
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            // 亮度 0 = 框架允许的最暗；只在本窗口可见期间生效，退出即恢复
            window.attributes = window.attributes.apply { screenBrightness = 0f }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(false)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }

            val root = View(this).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true
                setOnClickListener { finish() }
            }
            setContentView(root)
            hideSystemBars()

            CastService.setBlackout(true)
            lastFrames = CastService.frames
            lastFramesAt = System.currentTimeMillis()
            ui.postDelayed(watchdog, 2000)
            Oplog.logf(
                "BLACKOUT", "进入息屏: brightness=0 keepScreenOn=true 抓取=%s frames=%d",
                CastService.captureDesc.ifEmpty { "?" }, CastService.frames
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
        // 通知栏/输入法弹出后系统会恢复系统栏，重新藏起来
        if (hasFocus) {
            hideSystemBars()
            // 个别 ROM 会在失焦后重置窗口亮度，重新压到 0
            runCatching {
                window.attributes = window.attributes.apply { screenBrightness = 0f }
            }
        }
    }

    private fun hideSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { c ->
                    c.hide(WindowInsets.Type.systemBars())
                    c.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hideSystemBars failed", t)
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
        CastService.setBlackout(false)
        Oplog.logf("BLACKOUT", "退出息屏 frames=%d", CastService.frames)
        super.onDestroy()
    }
}
