package com.wzqvip.glxsscast.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wzqvip.glxsscast.MainActivity
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
 */
class DebugReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DebugReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: intent.action ?: return
        Oplog.init(context.applicationContext)
        Oplog.logf("DEBUG", "receiver cmd=%s", cmd)
        val app = context.applicationContext
        when (cmd) {
            "oplog_dump" -> Oplog.dumpCrash(intent.getStringExtra("reason") ?: "manual")
            "stats" -> ExpHarness.writeStats(app)
            "sensors" -> Thread({ Log.i(TAG, ExpHarness.enableSensors(app)) }, "ExpSensors").start()
            "xu_on" -> Thread({ Log.i(TAG, ExpHarness.xuSwitch(app, true)) }, "ExpXu").start()
            "xu_off" -> Thread({ Log.i(TAG, ExpHarness.xuSwitch(app, false)) }, "ExpXu").start()
            "flash_boot" -> Thread({
                Log.i(TAG, ExpHarness.flashBoot(app, intent.getStringExtra("img")))
            }, "ExpFlash").start()
            "system_reset" -> Thread({ Log.i(TAG, ExpHarness.systemReset(app)) }, "ExpReset").start()
            "bright", "mode", "screen", "roll", "autobright", "led" -> {
                val v = intent.getIntExtra("value", 0)
                Thread({ Log.i(TAG, ExpHarness.lcdControl(app, cmd, v)) }, "ExpLcd").start()
            }
            "lcd_test" -> Thread({ Log.i(TAG, ExpHarness.lcdTest(app)) }, "ExpLcdTest").start()
            "rec_start" -> Thread({
                Log.i(TAG, ExpHarness.recordStart(app, intent.getStringExtra("src") ?: "cast"))
            }, "ExpRec").start()
            "rec_stop" -> Thread({ Log.i(TAG, ExpHarness.recordStop()) }, "ExpRec").start()
            "cast_stop" -> {
                app.startService(
                    Intent(app, CastService::class.java).setAction(CastService.ACTION_STOP)
                )
            }
            "uvc_start" -> Thread({
                ExpHarness.uvcStart(app)
                ExpHarness.writeStats(app)
            }, "ExpUvcStart").start()
            "uvc_stop" -> Thread({
                ExpHarness.uvcStop()
                ExpHarness.writeStats(app)
            }, "ExpUvcStop").start()
            "uvc_close" -> Thread({ ExpHarness.uvcClose() }, "ExpUvcClose").start()
            "uvc_rawdump" -> Log.i(TAG, ExpHarness.uvcRawDump(app, intent.getIntExtra("n", 3)))
            "uvc_cycle" -> {
                val n = intent.getIntExtra("n", 50)
                val hold = intent.getIntExtra("hold", 400).toLong()
                Thread({ ExpHarness.uvcCycle(app, n, hold) }, "ExpUvcCycle").start()
            }
            "ctrl_poll" -> ExpHarness.startCtrlPoll(
                app,
                intent.getIntExtra("interval", 100).toLong(),
                intent.getIntExtra("seconds", 600).toLong() * 1000L
            )
            "ctrl_poll_stop" -> ExpHarness.stopCtrlPoll()
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
    }
}
