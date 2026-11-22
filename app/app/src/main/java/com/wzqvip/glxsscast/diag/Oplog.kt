package com.wzqvip.glxsscast.diag

import android.content.Context
import android.util.Log
import com.wzqvip.glxsscast.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 环形操作日志 —— 为了在下一次固件崩溃/半死时留下「最后做了什么」。
 *
 * 设计要点：
 * - **内存环形**：最近 [CAP] 条（默认 600），读/写都在锁内完成，单条写入 ~µs，不拖 USB 线程；
 * - **定期落盘** `files/oplog.txt`：后台线程每 2 秒把新增行追加到文件，文件 >2 MB 自动轮转
 *   （旧的改名 `oplog.1.txt`，只保留一份），所以长跑也不会撑爆存储；
 * - **崩溃/断开快照**：设备掉线、掉回引导模式、LCD bulk 连续失败、UVC 无效流/断流时调用
 *   [dumpCrash]，把最后 ~200 条写进 `files/crash_last.txt`（带时间/原因/版本/pid 头）；
 * - **进程崩溃**：安装默认 UncaughtExceptionHandler，先 dump 再交给原 handler。
 *
 * 追加一条日志不会抛异常（任何 I/O 失败只打 logcat），所以可以放心从 USB 线程调用。
 *
 * 取证：
 * ```
 * adb shell run-as com.wzqvip.glxsscast cat files/crash_last.txt
 * adb shell run-as com.wzqvip.glxsscast cat files/oplog.txt | tail -200
 * ```
 */
object Oplog {

    private const val TAG = "Oplog"
    private const val CAP = 600
    private const val CRASH_TAIL = 200
    private const val OPLOG_MAX_BYTES = 2L * 1024 * 1024
    private const val FLUSH_INTERVAL_MS = 2000L
    private const val UNSAVED_INLINE_FLUSH = 400

    private val lock = Any()
    private val ring = ArrayDeque<String>(CAP)
    private val unsaved = ArrayList<String>(64)

    private val ts = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile private var filesDir: File? = null
    @Volatile private var version = BuildConfig.VERSION_NAME
    @Volatile private var installed = false
    @Volatile private var flusher: Thread? = null

    @Volatile var lastCrashPath: String? = null
        private set
    @Volatile var lastCrashReason: String? = null
        private set
    @Volatile var crashCount: Int = 0
        private set

    /** 幂等初始化；[appVersion] 只用于 crash 头，一般传 BuildConfig.VERSION_NAME */
    fun init(ctx: Context, appVersion: String = BuildConfig.VERSION_NAME) {
        if (installed) return
        synchronized(lock) {
            if (installed) return
            filesDir = ctx.applicationContext.filesDir
            version = appVersion
            installed = true
            installCrashHandler()
            flusher = Thread({
                while (true) {
                    try {
                        Thread.sleep(FLUSH_INTERVAL_MS)
                    } catch (ignored: InterruptedException) {
                        return@Thread
                    }
                    flush()
                }
            }, "OplogFlusher").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "oplog init dir=$filesDir version=$version")
        }
    }

    /** 记一条。category 用短标签（CTRL/LCD_TX/UVC/CONN/HID/CAST/EP/BOOT/DEBUG/…） */
    fun log(category: String, message: String) {
        val line = "${ts.get()!!.format(Date())} | ${category.padEnd(9)} | $message"
        synchronized(lock) {
            if (ring.size >= CAP) ring.removeFirst()
            ring.addLast(line)
            unsaved.add(line)
            if (unsaved.size >= UNSAVED_INLINE_FLUSH) {
                // 极端情况下（30fps × 2s 也只是 60 行）直接同步落盘，避免积压
                flushLocked()
            }
        }
        Log.i(TAG, line)
    }

    fun logf(category: String, fmt: String, vararg args: Any?) {
        log(category, if (args.isEmpty()) fmt else String.format(Locale.US, fmt, *args))
    }

    /** 最近 n 条（时间正序） */
    fun tail(n: Int = CRASH_TAIL): List<String> = synchronized(lock) {
        val from = (ring.size - n).coerceAtLeast(0)
        ring.toList().subList(from, ring.size)
    }

    fun entryCount(): Int = synchronized(lock) { ring.size }

    fun fileSize(): Long = filesDir?.let { File(it, "oplog.txt").length() } ?: 0L

    fun flush() {
        synchronized(lock) { flushLocked() }
    }

    private fun flushLocked() {
        val dir = filesDir ?: return
        if (unsaved.isEmpty()) return
        val batch = ArrayList(unsaved)
        unsaved.clear()
        try {
            dir.mkdirs()
            val f = File(dir, "oplog.txt")
            if (f.length() > OPLOG_MAX_BYTES) {
                val old = File(dir, "oplog.1.txt")
                old.delete()
                f.renameTo(old)
            }
            FileOutputStream(f, true).bufferedWriter().use { w ->
                for (l in batch) {
                    w.append(l)
                    w.newLine()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "flush failed", t)
        }
    }

    /**
     * 把最后 [CRASH_TAIL] 条写入 `files/crash_last.txt`，并 flush 全量 oplog。
     *
     * @param reason 短原因，例如 `usb_detached` / `boot_mode_03e7` / `uvc_invalid_stream` /
     *               `lcd_send_fail` / `app_crash:IOException`
     */
    fun dumpCrash(reason: String): File? {
        flush()
        val dir = filesDir ?: return null
        val lines = tail(CRASH_TAIL)
        val f = File(dir, "crash_last.txt")
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val header = buildString {
            appendLine("=== GLXSS3 Cast crash snapshot ===")
            appendLine("time   : $now")
            appendLine("reason : $reason")
            appendLine("version: $version (versionCode ${BuildConfig.VERSION_CODE})")
            appendLine("pid    : ${android.os.Process.myPid()}")
            appendLine("entries: ${lines.size}")
            appendLine("----------------------------------")
        }
        return try {
            dir.mkdirs()
            f.writeText(header + lines.joinToString("\n") + "\n")
            lastCrashPath = f.absolutePath
            lastCrashReason = reason
            crashCount++
            Log.e(TAG, "crash snapshot -> ${f.absolutePath} ($reason)")
            f
        } catch (t: Throwable) {
            Log.e(TAG, "dumpCrash failed", t)
            null
        }
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                dumpCrash("app_crash:${e.javaClass.simpleName}:${e.message?.take(80)}")
            } catch (ignored: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}
