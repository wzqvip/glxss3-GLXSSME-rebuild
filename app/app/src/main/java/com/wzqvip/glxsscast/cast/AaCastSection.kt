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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android Auto（OpenHeadUnit）投屏 + 息屏 面板。
 *
 * 纯展示/触发层：抓屏逻辑都在 [CastService]，黑遮罩在 [BlackoutActivity]，
 * 这里只负责给用户一条「怎么设才 1:1」的路径，和一个不用摸黑去点通知栏的「息屏」入口。
 */
@Composable
fun AaCastSection(onStatus: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
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
            "1) OpenHeadUnit：Resolution = 720p（480p 最接近 16:10）、Video fit = Fit with bars\n" +
                "2) 投屏对话框选「单个应用」→ OpenHeadUnit（状态栏不会进画面）\n" +
                "3) 眼镜 640×400：AA 完整 → 640×360（上下各 20 px 黑边）；切「裁剪」可满屏但左右各切 ~13%",
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
                        onStatus("息屏中：屏幕最暗但 AA 继续渲染，点屏幕任意处退出")
                    } catch (t: Throwable) {
                        onStatus("息屏失败: ${t.javaClass.simpleName}: ${t.message}")
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (blackout) "息屏中（点此再进）" else "息屏（亮度 0）") }

            OutlinedButton(
                onClick = {
                    if (!CastService.running) {
                        onStatus("未投屏：先开始投屏")
                        return@OutlinedButton
                    }
                    // 深度息屏要 root：先探一次（Magisk 弹窗此时屏幕还亮着，能看见）
                    scope.launch {
                        onStatus("正在确认 root…")
                        val rooted = withContext(Dispatchers.IO) { BacklightIo.hasRoot() }
                        if (!rooted) {
                            onStatus("没有 root 授权：改用「息屏（亮度 0）」即可")
                            return@launch
                        }
                        try {
                            ctx.startActivity(
                                android.content.Intent(ctx, BlackoutActivity::class.java)
                                    .putExtra(BlackoutActivity.EXTRA_DEEP, true)
                            )
                            onStatus("深度息屏中：面板背光已关，点屏幕任意处退出并恢复")
                        } catch (t: Throwable) {
                            onStatus("深度息屏失败: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("深度息屏(root)") }
        }
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
            modifier = Modifier.fillMaxWidth()
        ) { Text("抓帧存 PNG（证明息屏期间还在出帧）") }
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
