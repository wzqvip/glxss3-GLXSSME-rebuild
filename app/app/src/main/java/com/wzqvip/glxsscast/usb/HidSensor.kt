package com.wzqvip.glxsscast.usb

import android.os.SystemClock
import android.util.Log
import com.wzqvip.glxsscast.diag.Oplog
import com.wzqvip.glxsscast.lcd.LcdControl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * HID 传感器 / Fn 按键（28 B 报告，`0xA1/0x16` wIndex=2 wLen=28）。
 *
 * 布局（docs/ch-hid.md §5，`struct.unpack("<BBH9hBBf")`）：
 * ```
 * 0   u8   keyStatus   0=松开 1=按下
 * 1   u8   keyCode     0x71=113 = Fn 键
 * 2   u16  lux         环境光原始值
 * 4   i16  compass xyz（4/6/8）
 * 10  i16  acc xyz（10/12/14）
 * 16  i16  gyro xyz（16/18/20）
 * 22  u8   af_result
 * 23  u8   caf_flag
 * 24  f32  temperature（°C）
 * ```
 *
 * 读取方式：每 300 ms 发一次 `0xA1/0x16`（原厂 `libhid.so` 的节奏），**不需要**读 0x83
 * 中断端点；0x83 作为 [Mode.INTERRUPT] 备选保留。轮询走 [UsbGate]，不会插进 1 MB
 * LCD 帧中间（超时 800 ms，接口被抢/HID 未 claim 时最多拖慢一帧，不会 5 s 卡死）。
 *
 * 按键判定（原厂 KeyEventClient 语义）：
 * - 单击 = 按下之后的**第一个松开包**；
 * - 长按 = 连续**第 4 个按下包**（repeat==3，约 0.9–1.2 s）。
 */
class HidSensor(private val link: UsbLink) {

    enum class Mode(val label: String) { POLL("控制请求 0xA1/0x16"), INTERRUPT("中断端点 0x83") }

    sealed class Event {
        data class Click(val code: Int) : Event()
        data class LongPress(val code: Int) : Event()
    }

    data class Report(
        val keyStatus: Int,
        val keyCode: Int,
        val lux: Int,
        val compass: IntArray,
        val acc: IntArray,
        val gyro: IntArray,
        val afResult: Int,
        val cafFlag: Int,
        val temperatureC: Float,
        val raw: ByteArray
    ) {
        fun hex(): String = raw.joinToString("") { "%02x".format(it) }

        /** 传感器一行（UI 状态栏）；默认 ±4g / ±2000dps（实测硬件 0x06 的值见 MainActivity） */
        fun sensorLine(accFsr: Int = 1, gyroFsr: Int = 3): String {
            val accScale = HidSensor.accScale(accFsr)
            val gyroScale = HidSensor.gyroScale(gyroFsr)
            return String.format(
                Locale.US,
                "按键=%s(0x%02x) · lux=%d · 温度=%.1f°C · acc=(%.2f, %.2f, %.2f)g · gyro=(%.0f, %.0f, %.0f)°/s · af=%d caf=%d",
                if (keyStatus == 1) "按下" else "松开", keyCode, lux, temperatureC,
                acc[0] * accScale, acc[1] * accScale, acc[2] * accScale,
                gyro[0] * gyroScale, gyro[1] * gyroScale, gyro[2] * gyroScale,
                afResult, cafFlag
            )
        }

        fun buttonLine(): String =
            "key 0x%02x / %s".format(keyCode, if (keyStatus == 1) "按下" else "松开")
    }

    companion object {
        private const val TAG = "HidSensor"
        const val KEY_GLXSS_BUTTON = 0x71
        private const val POLL_INTERVAL_MS = 300L
        private const val CTRL_TIMEOUT_MS = 800
        private const val LONG_REPEAT = 3

        /** FSR 档位表，索引 0..3 与固件 `0x06` 的 acc_fsr / gyro_fsr 一致 */
        val ACC_FSR_G = intArrayOf(2, 4, 8, 16)
        val GYRO_FSR_DPS = intArrayOf(250, 500, 1000, 2000)

        /** acc 原始计数 → g（满量程 = 32768） */
        fun accScale(fsr: Int): Double = ACC_FSR_G[fsr.coerceIn(0, 3)] / 32768.0

        /** gyro 原始计数 → °/s（满量程 = 32768） */
        fun gyroScale(fsr: Int): Double = GYRO_FSR_DPS[fsr.coerceIn(0, 3)] / 32768.0

        data class AccRangeEstimate(
            val sampleCount: Int,
            val medianRaw: Double,
            val fsrG: Double,
            val nearestIndex: Int
        ) {
            val nearestG: Int get() = ACC_FSR_G[nearestIndex]
        }

        /**
         * 静止时 |acc| 应 = 1g，而 `raw = 32768 * |acc| / FSR`，所以
         * `FSR ≈ 32768 / median(|acc_raw|)`。取最接近的标准档位。
         *
         * @return null = 样本不足或传感器未使能（几乎全 0）
         */
        fun estimateAccRange(rawMagnitudes: List<Double>): AccRangeEstimate? {
            val v = rawMagnitudes.filter { it > 0.0 }.sorted()
            if (v.size < 3) return null
            val med = if (v.size % 2 == 1) v[v.size / 2]
            else (v[v.size / 2 - 1] + v[v.size / 2]) / 2.0
            if (med < 64.0) return null
            val fsr = 32768.0 / med
            val nearest = ACC_FSR_G.indices.minByOrNull {
                kotlin.math.abs(ACC_FSR_G[it] - fsr)
            } ?: 1
            return AccRangeEstimate(v.size, med, fsr, nearest)
        }

        /** 最近创建的实例（实验 harness / stats 读取用） */
        @Volatile var last: HidSensor? = null
            private set
    }

    init {
        last = this
    }

    @Volatile var running = false; private set
    @Volatile var mode: Mode = Mode.POLL; private set
    @Volatile var report: Report? = null; private set
    @Volatile var pollCount = 0L; private set
    @Volatile var failCount = 0L; private set
    @Volatile var lastError: String = ""; private set
    @Volatile var eventCount = 0L; private set

    /** 当前换算用的 FSR 档位（UI 选择 / 0x06 读到的硬件值），只影响显示与日志 */
    @Volatile var accFsr: Int = 1
    @Volatile var gyroFsr: Int = 3

    /** 报告回调（HID 线程；UI 用 runOnUiThread/状态更新） */
    @Volatile var onReport: ((Report) -> Unit)? = null

    /** 按键事件回调（HID 线程） */
    @Volatile var onEvent: ((Event) -> Unit)? = null

    private var thread: Thread? = null
    private var prevStatus = -1
    private var repeat = 0
    private var longFired = false

    fun start(want: Mode = mode): Boolean {
        if (running) return true
        if (!link.isOpen) {
            lastError = "USB 未连接"
            return false
        }
        // HID claim 是「按 fd」的；同 fd 下 ensureHid 是幂等的
        link.ensureHid()
        if (want == Mode.INTERRUPT && link.hidInterruptEndpoint() == null) {
            lastError = "没有 HID 中断端点 0x83，回退控制请求轮询"
            Oplog.log("HID", lastError)
            mode = Mode.POLL
        } else {
            mode = want
        }
        running = true
        prevStatus = -1
        repeat = 0
        longFired = false
        pollCount = 0
        failCount = 0
        lastError = ""
        Oplog.logf("HID", "start mode=%s", mode.name)
        thread = Thread({ if (mode == Mode.POLL) pollLoop() else interruptLoop() }, "HidSensor").also {
            it.isDaemon = true
            it.start()
        }
        return true
    }

    fun stop() {
        running = false
        val t = thread
        thread = null
        if (t != null && t.isAlive) {
            try {
                t.join(1200)
            } catch (ignored: InterruptedException) {
            }
        }
        Oplog.logf("HID", "stop polls=%d fails=%d events=%d", pollCount, failCount, eventCount)
    }

    // ---------------- 轮询 ----------------

    private fun pollLoop() {
        var failStreak = 0
        while (running) {
            val t0 = SystemClock.elapsedRealtime()
            val c = link.connection
            if (c == null) {
                lastError = "USB 连接已关闭"
                break
            }
            val raw = try {
                LcdControl.readHidReport(c, CTRL_TIMEOUT_MS)
            } catch (t: Throwable) {
                null
            }
            if (raw == null) {
                failCount++
                failStreak++
                lastError = "HID 报告读取失败（n != 28，HID 接口可能被抢/固件异常）"
                if (failStreak == 5) {
                    Oplog.logf("HID", "read failed x5 (fails=%d)", failCount)
                    Oplog.dumpCrash("hid_read_fail(streak=$failStreak)")
                }
                // 被 UVC/别的 fd 抢走后重新 claim 一次（同一 fd 时幂等）
                if (failStreak % 10 == 0) link.ensureHid()
            } else {
                failStreak = 0
                pollCount++
                val r = parse(raw)
                report = r
                handleButton(r)
                try {
                    onReport?.invoke(r)
                } catch (t: Throwable) {
                    Log.w(TAG, "onReport failed", t)
                }
                if (pollCount == 1L || pollCount % 20 == 0L) {
                    Oplog.logf("HID", "poll=%d %s", pollCount, r.sensorLine(accFsr, gyroFsr))
                }
            }
            val spent = SystemClock.elapsedRealtime() - t0
            val sleep = POLL_INTERVAL_MS - spent
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep)
                } catch (ignored: InterruptedException) {
                    break
                }
            }
        }
        Oplog.log("HID", "poll loop exit")
    }

    // ---------------- 备选：中断端点 0x83 ----------------

    private fun interruptLoop() {
        val ep = link.hidInterruptEndpoint()
        if (ep == null) {
            lastError = "没有 HID 中断端点"
            Oplog.log("HID", lastError)
            return
        }
        val buf = ByteArray(64)
        Oplog.logf("HID", "interrupt read ep=0x%02x", ep.address)
        while (running) {
            val n = try {
                link.readInterrupt(ep, buf, 500)
            } catch (t: Throwable) {
                -1
            }
            if (n >= 28) {
                pollCount++
                val r = parse(buf)
                report = r
                handleButton(r)
                try {
                    onReport?.invoke(r)
                } catch (ignored: Throwable) {
                }
            } else if (n < 0) {
                failCount++
                Thread.sleep(50)
            } else {
                Thread.sleep(50)
            }
        }
        Oplog.log("HID", "interrupt loop exit")
    }

    // ---------------- 解析 / 按键 ----------------

    private fun parse(b: ByteArray): Report {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val keyStatus = bb.get().toInt() and 0xFF
        val keyCode = bb.get().toInt() and 0xFF
        val lux = bb.short.toInt() and 0xFFFF
        val compass = IntArray(3) { bb.short.toInt() }
        val acc = IntArray(3) { bb.short.toInt() }
        val gyro = IntArray(3) { bb.short.toInt() }
        val af = bb.get().toInt() and 0xFF
        val caf = bb.get().toInt() and 0xFF
        val temp = bb.float
        return Report(keyStatus, keyCode, lux, compass, acc, gyro, af, caf, temp, b.copyOf(28))
    }

    /**
     * 原厂状态机：e=上次电平；`e!=status → count=0` 否则 `count++`；
     * 长按 = count==3（第 4 个按下包）；单击 = 松开且 count==0 且没触发过长按。
     */
    private fun handleButton(r: Report) {
        val s = r.keyStatus
        if (prevStatus == -1) {
            prevStatus = s
            repeat = 0
            return
        }
        repeat = if (s == prevStatus) repeat + 1 else 0
        val isLong = repeat == LONG_REPEAT
        val code = r.keyCode
        when (s) {
            0 -> {
                if (!longFired && repeat == 0) emit(Event.Click(code))
                longFired = false
            }
            1 -> {
                if (isLong && !longFired) {
                    longFired = true
                    emit(Event.LongPress(code))
                }
            }
        }
        prevStatus = s
    }

    private fun emit(e: Event) {
        eventCount++
        Oplog.logf("HID", "event=%s code=0x%02x", e.javaClass.simpleName, when (e) {
            is Event.Click -> e.code
            is Event.LongPress -> e.code
        })
        try {
            onEvent?.invoke(e)
        } catch (t: Throwable) {
            Log.w(TAG, "onEvent failed", t)
        }
    }
}


