package com.wzqvip.glxsscast.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wzqvip.glxsscast.MainActivity
import com.wzqvip.glxsscast.nav.Maneuver
import com.wzqvip.glxsscast.nav.NavBus
import com.wzqvip.glxsscast.nav.NavHudPusher
import com.wzqvip.glxsscast.nav.NavParser
import com.wzqvip.glxsscast.settings.Prefs
import com.wzqvip.glxsscast.cast.CastService

/**
 * 实验/取证入口（仅 debug 构建；`exported=false`，用 root shell 的 `am broadcast` 调用）。
 *
 * ```
 * adb shell su -c "am broadcast -n com.wzqvip.glxsscast/.diag.DebugReceiver --es cmd oplog_dump"
 *   cmd = oplog_dump | stats | uvc_start | uvc_stop | uvc_close
 *       | uvc_cycle (--ei n 50 --ei hold 400)
 *       | ctrl_poll (--ei interval 100 --ei seconds 600) | ctrl_poll_stop
 *       | cast | record_start | record_stop | hid_start | hid_stop | status
 * ```
 * cast/record/hid/status 转发给 [MainActivity]（投屏授权仍需在系统对话框上点一下）。
 *
 * 所有命令都包了 try/catch：取证入口自己崩掉的话，正好会毁掉要取的那份现场。
 */
class DebugReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DebugReceiver"

        /** 调试线程统一兜底：后台线程未捕获异常 = 进程被杀（闪退） */
        private fun bg(name: String, block: () -> Unit) {
            Thread({
                try {
                    block()
                } catch (t: Throwable) {
                    Log.w(TAG, "$name 失败", t)
                    Oplog.logf("DEBUG", "$name 异常 %s: %s", t.javaClass.simpleName, t.message)
                }
            }, name).apply { isDaemon = true }.start()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: intent.action ?: return
        try {
            Oplog.init(context.applicationContext)
            Oplog.logf("DEBUG", "receiver cmd=%s", cmd)
            val app = context.applicationContext
            when (cmd) {
                "oplog_dump" -> Oplog.dumpCrash(intent.getStringExtra("reason") ?: "manual")
                "stats" -> ExpHarness.writeStats(app)
                "sensors" -> bg("ExpSensors") { Log.i(TAG, ExpHarness.enableSensors(app)) }
                "xu_on" -> bg("ExpXu") { Log.i(TAG, ExpHarness.xuSwitch(app, true)) }
                "xu_off" -> bg("ExpXu") { Log.i(TAG, ExpHarness.xuSwitch(app, false)) }
                "flash_boot" -> bg("ExpFlash") {
                    Log.i(TAG, ExpHarness.flashBoot(app, intent.getStringExtra("img")))
                }
                "system_reset" -> bg("ExpReset") { Log.i(TAG, ExpHarness.systemReset(app)) }
                "bright", "mode", "screen", "roll", "autobright", "led" -> {
                    val v = intent.getIntExtra("value", 0)
                    bg("ExpLcd") { Log.i(TAG, ExpHarness.lcdControl(app, cmd, v)) }
                }
                "lcd_test" -> bg("ExpLcdTest") { Log.i(TAG, ExpHarness.lcdTest(app)) }
                "rec_start" -> bg("ExpRec") {
                    Log.i(TAG, ExpHarness.recordStart(app, intent.getStringExtra("src") ?: "cast"))
                }
                "rec_stop" -> bg("ExpRec") { Log.i(TAG, ExpHarness.recordStop()) }
                "cast_stop" -> {
                    app.startService(
                        Intent(app, CastService::class.java).setAction(CastService.ACTION_STOP)
                    )
                }
                "uvc_start" -> bg("ExpUvcStart") {
                    ExpHarness.uvcStart(app)
                    ExpHarness.writeStats(app)
                }
                "uvc_stop" -> bg("ExpUvcStop") {
                    ExpHarness.uvcStop()
                    ExpHarness.writeStats(app)
                }
                "uvc_close" -> bg("ExpUvcClose") { ExpHarness.uvcClose() }
                "uvc_rawdump" -> Log.i(TAG, ExpHarness.uvcRawDump(app, intent.getIntExtra("n", 3)))
                "uvc_bulk" -> bg("ExpUvcBulk") { Log.i(TAG, ExpHarness.uvcBulk(app)) }
                "uvc_snapshot" -> bg("ExpUvcSnap") { Log.i(TAG, ExpHarness.uvcSnapshot()) }
                "uvc_cycle" -> {
                    val n = intent.getIntExtra("n", 50)
                    val hold = intent.getIntExtra("hold", 400).toLong()
                    bg("ExpUvcCycle") { ExpHarness.uvcCycle(app, n, hold) }
                }
                "ctrl_poll" -> ExpHarness.startCtrlPoll(
                    app,
                    intent.getIntExtra("interval", 100).toLong(),
                    intent.getIntExtra("seconds", 600).toLong() * 1000L
                )
                "ctrl_poll_stop" -> ExpHarness.stopCtrlPoll()
                // 导航 HUD：无 UI 的解析/发包自测（不需要解锁屏幕、不需要开车）
                "nav_sim" -> {
                    val maneuver = Maneuver.fromKey(
                        intent.getStringExtra("maneuver") ?: "right"
                    )
                    // 兼容 --ei / --ef / --el 三种写法（Number 统一转 double）。
                    fun numberExtra(key: String, fallback: Double): Double =
                        (intent.extras?.get(key) as? Number)?.toDouble() ?: fallback
                    val frame = NavBus.simulate(
                        app,
                        maneuver = maneuver,
                        distanceMeters = numberExtra("distance", 500.0),
                        etaSeconds = numberExtra("eta", 120.0).toLong(),
                        remainMeters = numberExtra("remain", 2400.0),
                    )
                    Log.i(TAG, "nav_sim → " + frame.toJson())
                    Oplog.logf("NAV", "nav_sim → %s", frame.toJson())
                }
                "nav_icon_test" -> {
                    NavBus.runIconSelfTest()
                    Log.i(TAG, "nav icon self-test: " + NavBus.iconSelfTest.value)
                    Oplog.logf("NAV", "icon self-test: %s", NavBus.iconSelfTest.value)
                }
                "nav_status" -> {
                    val line = "sent=${NavBus.sent.value} active=${NavBus.active.value} " +
                        "err=${NavBus.error.value} last=${NavBus.last.value?.toJson()}"
                    Log.i(TAG, "nav status: $line")
                    Oplog.logf("NAV", "status: %s", line)
                }
                // 方案 A：手机直推眼镜。无 UI 的完整自测：解析 → 640×400 渲染 → PNG
                //   adb shell am broadcast -n com.wzqvip.glxsscast/.diag.DebugReceiver \
                //     --es cmd nav_hud_png --es maneuver right --ef distance 500 \
                //     --ef eta 120 --ef remain 2400
                "nav_hud_png" -> {
                    val maneuver = Maneuver.fromKey(
                        intent.getStringExtra("maneuver") ?: "right"
                    )
                    fun number(key: String, fallback: Double): Double =
                        (intent.extras?.get(key) as? Number)?.toDouble() ?: fallback
                    val distance = number("distance", 500.0)
                    val eta = number("eta", 120.0).toLong()
                    val remain = number("remain", 2400.0)
                    val label = intent.getStringExtra("label")
                    bg("NavHudPng") {
                        // use_last=1：抓「最近一条真实 Maps 通知」而不是造一条
                        val frame = if (intent.getBooleanExtra("use_last", false)) {
                            null
                        } else {
                            NavParser.simulate(
                                maneuver = maneuver,
                                distanceMeters = distance,
                                etaSeconds = eta,
                                remainMeters = remain,
                                label = label,
                            )
                        }
                        val path = NavHudPusher.capturePng(
                            app, frame, note = "DebugReceiver",
                            waiting = intent.getBooleanExtra("waiting", false),
                        )
                        Log.i(TAG, "nav_hud_png → $path")
                        Oplog.logf("NAV", "nav_hud_png maneuver=%s → %s", maneuver.key, path)
                    }
                }
                "nav_hud_push" -> {
                    NavHudPusher.pushOnce(app)
                    Log.i(TAG, "nav_hud_push requested, running=${NavHudPusher.running.value}")
                    Oplog.logf("NAV", "nav_hud_push requested")
                }
                "nav_direct" -> {
                    val on = intent.getIntExtra("on", 1) != 0
                    Prefs.get(app).navDirect = on
                    if (on) NavHudPusher.start(app) else NavHudPusher.stop()
                    Log.i(TAG, "nav_direct=$on")
                    Oplog.logf("NAV", "nav_direct=%b", on)
                }
                "nav_hud_status" -> {
                    val line = "running=${NavHudPusher.running.value} " +
                        "pushes=${NavHudPusher.pushes.value} " +
                        "err=${NavHudPusher.lastError.value} " +
                        "png=${NavHudPusher.lastPngPath.value} " +
                        "status=${NavHudPusher.status.value}"
                    Log.i(TAG, "nav hud status: $line")
                    Oplog.logf("NAV", "hud status: %s", line)
                }
                // 需要 Activity 的命令（投屏授权 / 录像 / HID UI / 状态）
                "cast", "record_start", "record_stop", "hid_start", "hid_stop", "status",
                "fsr_estimate", "level_calib", "sensor_attr" -> {
                    val i = Intent(app, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("cmd", cmd)
                    }
                    app.startActivity(i)
                }
                else -> {
                    Log.w(TAG, "unknown cmd $cmd")
                    Oplog.logf("DEBUG", "unknown cmd=%s", cmd)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "DebugReceiver.onReceive 失败 cmd=$cmd", t)
            try {
                Oplog.logf(
                    "DEBUG", "receiver 异常 cmd=%s %s: %s",
                    cmd, t.javaClass.simpleName, t.message
                )
            } catch (ignored: Throwable) {
            }
        }
    }
}

