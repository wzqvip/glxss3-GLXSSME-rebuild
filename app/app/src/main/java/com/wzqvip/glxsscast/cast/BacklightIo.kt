package com.wzqvip.glxsscast.cast

import android.content.Context
import android.util.Log
import com.wzqvip.glxsscast.diag.Oplog
import java.io.File

/**
 * 用 root 直接写面板背光 —— 「深度息屏」用。
 *
 * 为什么不用 `settings put system screen_brightness 0`：
 *  - 那条命令要 WRITE_SETTINGS，而且改的是**用户系统设置**，忘了恢复就把手机永久调黑；
 *  - 框架层的 0 在部分 ROM 上只是"最暗档"，夜里仍看得见画面。
 *
 * 直接写 sysfs 才是真的把面板关掉，而且可以做到"存原值 → 写 0 → 退出还原"。
 *
 * 安全设计（这是唯一会让用户手机变黑的路径，必须能自愈）：
 * 1. 写 0 之前，把原值存进 SharedPreferences（`pending_orig`）；
 * 2. 退出息屏时还原并清掉标记；
 * 3. [GlxssApp.onCreate] 每次进程启动都会调 [restorePending] —— 即使 App 被杀/崩溃，
 *    下次打开 App 也会先把背光还回去。
 *
 * 实测设备（LG G8X / Android 14 / Magisk）：`su -c id` 可用，路径
 * `/sys/class/backlight/panel0-backlight/brightness`，量程 0–365。
 */
object BacklightIo {

    private const val TAG = "BacklightIo"

    /** 优先用这条；没有就退回 /sys/class/leds/lcd-backlight */
    private val CANDIDATES = listOf(
        "/sys/class/backlight/panel0-backlight/brightness",
        "/sys/class/leds/lcd-backlight/brightness",
    )

    private const val PREFS = "blackout-root"
    private const val KEY_PENDING = "pending_orig"
    private const val KEY_PATH = "pending_path"

    private fun exec(cmd: String, timeoutMs: Long = 4000): Pair<Int, String>? {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
                } catch (ignored: Throwable) {
                }
            }.apply { isDaemon = true; start() }
            val done = waitFor(p, timeoutMs)
            if (!done) {
                try {
                    p.destroy()
                } catch (ignored: Throwable) {
                }
                return null
            }
            reader.join(300)
            p.exitValue() to out.toString().trim()
        } catch (t: Throwable) {
            Log.w(TAG, "exec failed: $cmd", t)
            null
        }
    }

    private fun waitFor(p: Process, ms: Long): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue()
                return true
            } catch (e: IllegalThreadStateException) {
                try {
                    Thread.sleep(30)
                } catch (ignored: InterruptedException) {
                }
            }
        }
        return false
    }

    /** root 是否可用（会触发 Magisk 授权弹窗，所以只在用户主动点「深度息屏」时调） */
    fun hasRoot(): Boolean {
        val r = exec("id") ?: return false
        return r.first == 0 && r.second.contains("uid=0")
    }

    /** 读当前背光值；null = 没有 root / 找不到节点 */
    fun readRaw(): Int? {
        for (path in CANDIDATES) {
            val r = exec("cat $path") ?: continue
            if (r.first != 0) continue
            val v = r.second.trim().toIntOrNull()
            if (v != null && v >= 0) return v
        }
        return null
    }

    /** 写背光；返回是否成功 */
    fun writeRaw(v: Int): Boolean {
        val prefs = currentPrefs
        // 优先写上次用过的那个节点
        val saved = prefs?.let { p ->
            val path = p.getString(KEY_PATH, null)
            if (path != null && File(path).exists()) listOf(path) + CANDIDATES else CANDIDATES
        } ?: CANDIDATES
        for (path in saved.distinct()) {
            val r = exec("echo $v > $path") ?: continue
            if (r.first == 0) {
                // 确认写进去了（有的节点会静默忽略）
                val check = exec("cat $path")
                if (check != null && check.first == 0) {
                    val got = check.second.trim().toIntOrNull()
                    if (got != null && got == v) return true
                }
            }
        }
        return false
    }

    /** 写 0 之前记下原值（含节点路径），供退出/崩溃恢复 */
    fun savePending(ctx: Context, orig: Int) {
        try {
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            currentPrefs = p
            p.edit().putInt(KEY_PENDING, orig).apply()
            // 记下实际用的节点：读一次看哪个能读到
            for (path in CANDIDATES) {
                val r = exec("cat $path") ?: continue
                if (r.first == 0 && r.second.trim().toIntOrNull() != null) {
                    p.edit().putString(KEY_PATH, path).apply()
                    break
                }
            }
            Oplog.logf("BLACKOUT", "背光原值 %d 已记录（崩溃后下次启动会自动还原）", orig)
        } catch (t: Throwable) {
            Log.w(TAG, "savePending failed", t)
        }
    }

    /**
     * 如果上次把背光写成 0 却没还原（崩溃/强杀/断电前），把原值写回去。
     *
     * 幂等：没有 pending 标记就什么都不做。
     */
    fun restorePending(ctx: Context) {
        try {
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            currentPrefs = p
            val orig = p.getInt(KEY_PENDING, -1)
            if (orig < 0) return
            val ok = writeRaw(orig)
            if (ok) {
                p.edit().remove(KEY_PENDING).remove(KEY_PATH).apply()
                Oplog.logf("BLACKOUT", "背光已还原为 %d（上次息屏未正常退出）", orig)
            } else {
                Oplog.log("BLACKOUT", "背光还原失败（root 不可用？）/sys 只读？数值保留在 prefs，下次再试")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "restorePending failed", t)
        }
    }

    @Volatile
    private var currentPrefs: android.content.SharedPreferences? = null
}
