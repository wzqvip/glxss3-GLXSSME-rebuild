package com.wzqvip.glxsscast.cast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Android Auto（OpenHeadUnit）投屏 + 息屏 面板。
 *
 * 纯展示/触发层：抓屏逻辑都在 [CastService]，黑遮罩在 [BlackoutActivity]，
 * 这里只负责给用户一条「怎么设才 1:1」的路径，和一个不用摸黑去点通知栏的「息屏」入口。
 */
@Composable
fun AaCastSection(onStatus: (String) -> Unit) {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(CastService.running) }
    var blackout by remember { mutableStateOf(CastService.blackout) }
    var capture by remember { mutableStateOf(CastService.captureDesc) }
    var fps by remember { mutableStateOf(CastService.fps) }
    var frames by remember { mutableStateOf(CastService.frames) }
    var warn by remember { mutableStateOf(CastService.blackoutWarning) }
    var dump by remember { mutableStateOf(CastService.lastDumpPath) }

    // 1 s 轮询服务状态（投屏可能从通知栏启动/停止，Compose 收不到回调）
    LaunchedEffect(Unit) {
        while (true) {
            running = CastService.running
            blackout = CastService.blackout
            capture = CastService.captureDesc
            fps = CastService.fps
            frames = CastService.frames
            warn = CastService.blackoutWarning
            dump = CastService.lastDumpPath
            delay(1000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Android Auto 投屏（单应用 + 息屏）", style = MaterialTheme.typography.titleSmall)
        Text(
            "1) OpenHeadUnit 设置里：分辨率 1280×800、画面适应 Fill with bars（16:10 上下留边）\n" +
                "2) 点「开始投屏」→ 系统对话框选「单个应用」→ OpenHeadUnit\n" +
                "3) 眼镜 640×400 正好按 2:1 缩放，无状态栏、无黑边",
            style = MaterialTheme.typography.bodySmall
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = {
                    if (!CastService.running) {
                        onStatus("先开始投屏，再进息屏")
                        return@Button
                    }
                    try {
                        ctx.startActivity(
                            android.content.Intent(ctx, BlackoutActivity::class.java)
                        )
                        onStatus("息屏中：手机黑屏 + 亮度 0，点屏幕任意处可退出")
                    } catch (t: Throwable) {
                        onStatus("息屏失败: ${t.javaClass.simpleName}: ${t.message}")
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (blackout) "息屏中（再点可叠加）" else "息屏（黑屏+亮度0）") }

            OutlinedButton(
                onClick = {
                    if (!CastService.running) {
                        onStatus("未投屏：先开始投屏")
                        return@OutlinedButton
                    }
                    try {
                        ctx.startService(
                            android.content.Intent(ctx, CastService::class.java)
                                .setAction(CastService.ACTION_DUMP_FRAME)
                        )
                        onStatus("已请求抓一帧（稍后看下面路径）")
                    } catch (t: Throwable) {
                        onStatus("抓帧请求失败: ${t.javaClass.simpleName}")
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("抓帧存 PNG") }
        }
        Text(
            if (!running) {
                "投屏未运行"
            } else {
                "抓取 $capture · %.1f fps · 共 %d 帧".format(fps, frames) +
                    if (blackout) " · 息屏中" else ""
            },
            style = MaterialTheme.typography.bodySmall
        )
        if (warn.isNotEmpty()) {
            Text(warn, style = MaterialTheme.typography.bodySmall)
        }
        if (dump.isNotEmpty()) {
            Text("最近抓帧: $dump", style = MaterialTheme.typography.bodySmall)
        }
    }
}
