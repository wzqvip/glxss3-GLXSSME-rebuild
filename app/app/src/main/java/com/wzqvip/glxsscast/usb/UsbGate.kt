package com.wzqvip.glxsscast.usb

import com.wzqvip.glxsscast.diag.Oplog
import java.util.concurrent.locks.ReentrantLock

/**
 * 全进程 USB I/O 串行闸门。
 *
 * 背景：投屏每帧是**一次 1,024,016 B 的 bulk OUT**（单次调用，最长 5 s），而控制请求
 * （0xA1/0x04 状态、0xA1/0x16 HID、亮度/模式、UVC probe/commit/setInterface…）会同时在
 * 其它线程发出。两者在 usbfs 层是不同 URB，但**固件的 USB 任务/媒体任务是共享状态的**：
 * 1 MB 帧写到一半插一条 class 控制请求，正是我们与「原厂 App 不崩」之间最难排除的差异。
 *
 * 因此：
 * - 所有 **control transfer**（LCD/HID/UVC class 请求）+ **setInterface/claim/release**
 *   + **LCD 帧的 bulk OUT** 都走本闸门；
 * - UVC 的 bulk IN 读流**不走**闸门（那是长驻读、且原厂也是 LCD+UVC 并发；闸门只保证
 *   控制面不插进帧中间）；
 * - 使用 [ReentrantLock] 而不是 synchronized：允许同一线程内嵌套（例如 stop() 里
 *   setInterface + clear-halt），跨线程仍然互斥。
 *
 * 每个持有者都带名字，超长持有会在 logcat/Oplog 里留 `GATE` 警告，便于定位「谁卡住了 USB」。
 */
object UsbGate {

    private val lock = ReentrantLock()
    private const val SLOW_HOLD_MS = 1500L
    private const val SLOW_WAIT_MS = 200L

    @Volatile var lastOwner: String = ""
        private set
    @Volatile var lastHoldMs: Long = 0
        private set
    @Volatile var maxHoldMs: Long = 0
        private set

    /** 拿锁 → 执行 → 放锁。owner 只用于日志。 */
    fun <T> io(owner: String, block: () -> T): T {
        val waitStart = System.currentTimeMillis()
        lock.lock()
        val waited = System.currentTimeMillis() - waitStart
        if (waited >= SLOW_WAIT_MS) {
            Oplog.logf("GATE", "%s 等锁 %.0f ms（持锁者 %s）", owner, waited.toFloat(), lastOwner)
        }
        lastOwner = owner
        val t0 = System.currentTimeMillis()
        try {
            return block()
        } finally {
            val dt = System.currentTimeMillis() - t0
            lastHoldMs = dt
            if (dt > maxHoldMs) maxHoldMs = dt
            if (dt >= SLOW_HOLD_MS) {
                Oplog.logf("GATE", "%s 持锁 %.0f ms（历史最长 %.0f ms）", owner, dt.toFloat(), maxHoldMs.toFloat())
            }
            lock.unlock()
        }
    }
}
