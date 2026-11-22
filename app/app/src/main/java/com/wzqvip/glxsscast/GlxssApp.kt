package com.wzqvip.glxsscast

import android.app.Application
import android.util.Log
import com.wzqvip.glxsscast.diag.Oplog

/**
 * 进程入口：**最早**安装 Oplog 的崩溃处理器（比 Activity/Service 都早）。
 *
 * 背景：0.1.5 只在 `MainActivity.onCreate` / `UsbLink.get` 里调 `Oplog.init`。
 * 如果崩溃发生在 Application/Provider 初始化、或者 Activity 还没起来时的广播里，
 * `crash_last.txt` 就一行都留不下 —— 华为机上表现为「打开就闪退，没有现场」。
 *
 * 这里只做一件事：把 [Oplog] 装起来（幂等），失败也只打 logcat，绝不拖垮启动。
 */
class GlxssApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Oplog.init(this)
            Oplog.logf("BOOT", "Application onCreate (pid=%d)", android.os.Process.myPid())
        } catch (t: Throwable) {
            Log.e("GlxssApp", "Oplog.init failed", t)
        }
    }
}
