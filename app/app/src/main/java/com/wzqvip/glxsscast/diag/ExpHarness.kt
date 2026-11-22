package com.wzqvip.glxsscast.diag

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.wzqvip.glxsscast.cast.CastService
import com.wzqvip.glxsscast.lcd.LcdControl
import com.wzqvip.glxsscast.lcd.LcdProtocol
import com.wzqvip.glxsscast.record.RecordSource
import com.wzqvip.glxsscast.record.VideoRecorder
import com.wzqvip.glxsscast.usb.Firmware
import com.wzqvip.glxsscast.usb.HidSensor
import com.wzqvip.glxsscast.usb.MyriadBootLoader
import com.wzqvip.glxsscast.usb.UsbGate
import com.wzqvip.glxsscast.usb.UsbLink
import com.wzqvip.glxsscast.uvc.UvcCamera
import java.io.File
import java.util.Locale

/**
 * 长跑实验 harness（只被 [DebugReceiver] 调用，普通用户走 UI）。
 *
 * 目的：任务 2 的触发实验需要可靠的「按命令开始/停止 UVC、10 Hz 控制请求、统计快照」，
 * 用 adb 广播驱动比点 UI 稳定、可重复。命令见 [DebugReceiver]。
 *
 * 注意：投屏启动仍然需要系统录屏授权（Activity 的 mpLauncher），harness 只负责发命令让
 * Activity 去弹授权框；UVC/控制请求/LCD 控制不需要用户交互。
 */
object ExpHarness {

    private val lock = Any()
    private var uvc: UvcCamera? = null
    private var recorder: VideoRecorder? = null

    @Volatile private var ctrlThread: Thread? = null
    @Volatile var ctrlRequests: Long = 0L; private set

    /** 拿 UsbLink 并确保连接（设备在线时） */
    fun ensureLink(ctx: Context): UsbLink {
        val link = UsbLink.get(ctx)
        if (!link.isOpen) {
            val dev = link.findDevice()
            if (dev != null) link.open(dev)
        }
        return link
    }

    fun uvcStart(ctx: Context): String = synchronized(lock) {
        val link = ensureLink(ctx)
        val dev = link.device ?: link.findDevice()
            ?: return "uvc start: no device".also { Oplog.log("EXP", it) }
        if (!link.isOpen && !link.open(dev)) {
            return "uvc start: usb open failed".also { Oplog.log("EXP", it) }
        }
        val cam = uvc ?: UvcCamera(ctx.applicationContext).also { uvc = it }
        // 设备断线重连后实例可能留着 running=true 但线程已死；这种僵死状态先 close 再重开
        if (cam.isRunning && !cam.isReaderAlive()) {
            Oplog.log("EXP", "uvc start: stale running state → close")
            cam.close()
        }
        if (cam.isRunning) return "uvc start: already running"
        if (!cam.prepare(dev, link.connection)) {
            return "uvc start: prepare failed ${cam.lastError()}".also { Oplog.log("EXP", it) }
        }
        if (!cam.select(null)) {
            return "uvc start: select failed ${cam.lastError()}".also { Oplog.log("EXP", it) }
        }
        cam.start()
        val msg = "uvc start: ${cam.negotiatedInfo()}"
        Oplog.log("EXP", msg)
        msg
    }

    fun uvcStop(): String = synchronized(lock) {
        val cam = uvc ?: return "uvc stop: not opened"
        cam.stop()
        val msg = "uvc stop: frames=${cam.frameCount()}"
        Oplog.log("EXP", msg)
        msg
    }

    fun uvcClose(): String = synchronized(lock) {
        val cam = uvc ?: return "uvc close: not opened"
        cam.close()
        uvc = null
        Oplog.log("EXP", "uvc close")
        "uvc close: done"
    }

    /** 把当前 UVC 帧存成 PNG（验证解码画面用） */
    fun uvcSnapshot(): String {
        val cam = synchronized(lock) { uvc } ?: return "snapshot: no uvc instance"
        val f = cam.saveSnapshot() ?: return "snapshot: no frame (frames=${cam.frameCount()})"
        return "snapshot: ${f.absolutePath} (${f.length()} B, frames=${cam.frameCount()}, fps=${cam.currentFps()})"
    }

    /** 诊断 bulk IN：prepare 后 / clear halt 后 / commit 后 三种状态下的 bulkTransfer 实测 */
    fun uvcBulk(ctx: Context): String {
        val link = ensureLink(ctx)
        val dev = link.device ?: link.findDevice() ?: return "uvc bulk: no device"
        if (!link.isOpen && !link.open(dev)) return "uvc bulk: usb open failed"
        val cam = UvcCamera(ctx.applicationContext)
        val sb = StringBuilder()
        try {
            if (!cam.prepare(dev, link.connection)) {
                return "uvc bulk: prepare failed ${cam.lastError()}"
            }
            sb.append("== before probe ==\n").append(cam.bulkProbe())
            cam.clearHaltForTest()
            sb.append("== after clear halt ==\n").append(cam.bulkProbe())
            if (!cam.select(null)) {
                sb.append("select failed: ${cam.lastError()}")
            } else {
                sb.append("== after commit ==\n").append(cam.bulkProbe())
                cam.start()
                Thread.sleep(1500)
                sb.append("running=${cam.isRunning} frames=${cam.frameCount()} fps=${cam.currentFps()}")
                cam.stop()
            }
        } finally {
            cam.close()
        }
        val s = sb.toString()
        for (line in s.split('\n')) Log.i("ExpHarness", "BULK $line")
        return s
    }

    /** 使能光感/罗盘/加速度/陀螺（0x10/0x11/0x12/0x14），让 28B 报告有非零 IMU */
    fun enableSensors(ctx: Context): String {
        val link = ensureLink(ctx)
        val c = link.connection ?: return "sensors: no usb connection"
        val ok = LcdControl.enableSensors(c)
        Oplog.logf("EXP", "enableSensors -> %b", ok)
        return "sensors enable -> $ok"
    }

    /**
     * 手动给引导模式设备（03E7:2150）灌固件 —— 自动 attach 流程没触发/没权限时用。
     * 固件取 APK 内置的那份（`assets/firmware/` → `filesDir/firmware/`），不再读外部目录。
     */
    fun flashBoot(ctx: Context, imgPath: String? = null): String {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = MyriadBootLoader.findBootDevice(usb) ?: return "flash boot: no 03E7:2150 device"
        if (!usb.hasPermission(dev)) {
            Oplog.log("EXP", "flash boot: no USB permission, request")
            UsbLink.get(ctx).requestPermission(dev)
            return "flash boot: requesting USB permission, retry in 2s"
        }
        val path = imgPath ?: run {
            val r = Firmware.ensure(ctx)
            if (!r.ok || r.file == null) return "flash boot: ${r.message}"
            r.file.absolutePath
        }
        val missing = MyriadBootLoader.validate(File(path))
        if (missing != null) return "flash boot: $missing"
        Oplog.logf("EXP", "flash boot start %s", path)
        MyriadBootLoader.flash(
            ctx, dev, File(path),
            { p, m ->
                if (p % 25 == 0 || p == 100) Oplog.logf("BOOT", "progress %d%% %s", p, m)
            },
            { ok, msg ->
                Oplog.logf("BOOT", "flash done ok=%b: %s", ok, msg)
            }
        )
        return "flash boot started: $path"
    }

    /** 把接下来 n 个 UVC bulk URB 的原始字节写到 files/uvc_raw.bin（4B 长度前缀 + 数据） */
    fun uvcRawDump(ctx: Context, urbs: Int = 3): String {
        val cam = synchronized(lock) { uvc } ?: return "rawdump: no uvc instance"
        val f = File(ctx.filesDir, "uvc_raw.bin")
        val ok = cam.startRawDump(f, urbs)
        return "rawdump -> ${if (ok) f.absolutePath else "failed (uvc not running)"}"
    }

    /** 原厂 XU 开关（0x21/0x01 0x0d0d） */
    fun xuSwitch(ctx: Context, on: Boolean): String {
        val link = ensureLink(ctx)
        val c = link.connection ?: return "xu: no usb connection"
        val ok = LcdControl.vendorXuSwitch(c, on)
        return "xu switch($on) -> $ok"
    }

    /** LCD 控制命令（实验用，避免每次点 UI） */
    fun lcdControl(ctx: Context, op: String, value: Int): String {
        val link = ensureLink(ctx)
        val c = link.connection ?: return "lcd $op: no usb connection"
        val ok = when (op) {
            "bright" -> LcdControl.setBrightness(c, value)
            "mode" -> LcdControl.setDisplayMode(c, value)
            "screen" -> LcdControl.setScreenOn(c, value != 0)
            "roll" -> LcdControl.setRoll(c, value)
            "autobright" -> LcdControl.setAutoBrightness(c, value != 0)
            "led" -> LcdControl.setLed(c, value != 0)
            else -> false
        }
        return "lcd $op=$value -> $ok"
    }

    /** 发一帧纯色测试图（不经投屏，用于确认 LCD bulk 通路） */
    fun lcdTest(ctx: Context): String {
        val link = ensureLink(ctx)
        val c = link.connection ?: return "lcd test: no usb connection"
        LcdControl.setDisplayMode(c, 2)
        LcdControl.setScreenOn(c, true)
        val ok = link.sendFrame(LcdProtocol.frame(LcdProtocol.bars()))
        return "lcd test frame -> $ok (frames=${link.framesSent}, last=${link.lastSendMs}ms)"
    }

    // ==================================================================
    // 录像 harness（后台广播控制；不依赖 Activity 在前台）
    // ==================================================================

    /** 开始录像（录像源固定投屏帧；投屏须已在跑，否则 encode 0 帧） */
    fun recordStart(ctx: Context, source: String): String = synchronized(lock) {
        val src = if (source.equals("uvc", true)) RecordSource.UVC else RecordSource.CAST
        if (recorder?.isRecording == true) return "record: already recording"
        val r = VideoRecorder(ctx.applicationContext)
        val (w, h) = if (src == RecordSource.CAST) 640 to 400 else 640 to 480
        val err = r.start(src, w, h)
        if (err != null) return "record start failed: $err".also { Oplog.log("EXP", it) }
        if (src == RecordSource.CAST) CastService.frameTap = r else uvc?.frameTap = r
        recorder = r
        Oplog.logf("EXP", "record start src=%s %dx%d file=%s", src.name, w, h, r.outputFile?.absolutePath)
        "record started: ${r.outputFile?.absolutePath}"
    }

    fun recordStop(): String = synchronized(lock) {
        val r = recorder ?: return "record: not recording"
        if (CastService.frameTap === r) CastService.frameTap = null
        if (uvc?.frameTap === r) uvc?.frameTap = null
        r.stop()
        val msg = "record stop: frames=${r.framesWritten} dropped=${r.framesDropped} " +
            "dur=${r.videoDurationUs / 1000}ms err=${r.lastError}"
        recorder = null
        Oplog.log("EXP", msg)
        msg
    }

    /**
     * 发 `0xA1/0x18` 复位固件。仅用于从「半死」恢复：设备会重枚举为 03E7:2150，
     * App 的 attach 流程会自动灌 G25_MainFW_1.9.1.img。
     */
    fun systemReset(ctx: Context): String {
        val link = ensureLink(ctx)
        val c = link.connection ?: return "system reset: no usb connection"
        Oplog.log("EXP", "system reset (0xA1/0x18) requested")
        stopCtrlPoll()
        val ok = LcdControl.systemReset(c)
        return "system reset -> $ok (expect 03E7:2150 in ~1s)"
    }

    /** 快速 start/stop 若干次；每轮间隔 [holdMs] */
    fun uvcCycle(ctx: Context, cycles: Int, holdMs: Long): String {
        Oplog.logf("EXP", "uvc cycle start cycles=%d hold=%dms", cycles, holdMs)
        var ok = 0
        var fail = 0
        val t0 = System.currentTimeMillis()
        for (i in 1..cycles) {
            uvcStart(ctx)
            if (uvc?.isRunning == true) ok++ else fail++
            Thread.sleep(holdMs)
            uvcStop()
            Thread.sleep(150)
            if (i % 10 == 0) Oplog.logf("EXP", "uvc cycle %d/%d ok=%d fail=%d", i, cycles, ok, fail)
        }
        uvcClose()
        val msg = String.format(
            Locale.US, "uvc cycle done: %d cycles in %.1fs (ok≈%d fail=%d)",
            cycles, (System.currentTimeMillis() - t0) / 1000.0, ok, fail
        )
        Oplog.log("EXP", msg)
        return msg
    }

    /**
     * 每 [intervalMs] 发一次 `0xA1/0x04` 状态查询（8B），持续 [durationMs]。
     * 用来做「控制请求与 1 MB 帧高频交错」，验证串行闸门是否稳。
     */
    fun startCtrlPoll(ctx: Context, intervalMs: Long, durationMs: Long) {
        stopCtrlPoll()
        val t = Thread({
            try {
            val link = UsbLink.get(ctx)
            val end = System.currentTimeMillis() + durationMs
            var n = 0L
            var fail = 0L
            Oplog.logf("EXP", "ctrl poll start interval=%dms duration=%dms", intervalMs, durationMs)
            while (System.currentTimeMillis() < end) {
                val c = link.connection
                if (c == null) {
                    fail++
                    Thread.sleep(200)
                    continue
                }
                val st = try {
                    LcdControl.readStatus(c)
                } catch (t: Throwable) {
                    null
                }
                n++
                if (st == null) fail++
                if (n == 1L || n % 100 == 0L) {
                    Oplog.logf(
                        "EXP", "ctrl poll n=%d fail=%d status=%s",
                        n, fail, st?.hex() ?: "null"
                    )
                }
                Thread.sleep(intervalMs.coerceAtLeast(10))
            }
            ctrlRequests += n
            Oplog.logf("EXP", "ctrl poll done n=%d fail=%d", n, fail)
            } catch (t: Throwable) {
                // 调试线程也不允许未捕获异常（Android 会杀进程）；stopCtrlPoll 的 interrupt 也走这里
                Oplog.logf("EXP", "ctrl poll 异常 %s: %s", t.javaClass.simpleName, t.message)
            }
        }, "CtrlPoll")
        t.isDaemon = true
        ctrlThread = t
        t.start()
    }

    fun stopCtrlPoll() {
        val t = ctrlThread
        ctrlThread = null
        if (t != null && t.isAlive) {
            t.interrupt()
            Oplog.log("EXP", "ctrl poll stop requested")
        }
    }

    /** 把当前状态写 files/exp_stats.txt（adb pull / run-as cat 取证） */
    fun writeStats(ctx: Context) {
        val link = UsbLink.get(ctx)
        val cam = synchronized(lock) { uvc }
        val text = buildString {
            appendLine("=== GLXSS3 Cast exp stats ===")
            appendLine("time    : ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(java.util.Date())}")
            appendLine("pid     : ${android.os.Process.myPid()}")
            appendLine("usbOpen : ${link.isOpen} dev=${link.device?.deviceName}")
            appendLine("lcd     : frames=${link.framesSent} fails=${link.failTotal} streak=${link.failStreak} lastMs=${link.lastSendMs}")
            appendLine("gate    : owner=${UsbGate.lastOwner} lastMs=${UsbGate.lastHoldMs} maxMs=${UsbGate.maxHoldMs}")
            appendLine("cast    : running=${CastService.running} fps=${CastService.fps} frames=${CastService.frames}")
            appendLine("uvc     : running=${cam?.isRunning} fps=${cam?.currentFps()} frames=${cam?.frameCount()}")
            val hid = HidSensor.last
            appendLine(
                "hid     : running=${hid?.running} mode=${hid?.mode} polls=${hid?.pollCount} " +
                    "fails=${hid?.failCount} report=${hid?.report?.sensorLine() ?: "-"}"
            )
            val rec = synchronized(lock) { recorder }
            appendLine(
                "record  : active=${rec?.isRecording} frames=${rec?.framesWritten} " +
                    "dropped=${rec?.framesDropped} size=${rec?.outputFile?.length()} tap=${CastService.frameTap != null}"
            )
            appendLine("ctrlPoll: requests=${ctrlRequests} active=${ctrlThread?.isAlive}")
            appendLine("oplog   : entries=${Oplog.entryCount()} bytes=${Oplog.fileSize()} crash=${Oplog.lastCrashReason}")
        }
        try {
            File(ctx.applicationContext.filesDir, "exp_stats.txt").writeText(text)
            Oplog.log("EXP", "stats written")
        } catch (t: Throwable) {
            Oplog.logf("EXP", "stats write failed: %s", t.message)
        }
    }
}
