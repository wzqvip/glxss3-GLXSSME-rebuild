package com.wzqvip.glxsscast.cast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wzqvip.glxsscast.settings.Prefs
import kotlinx.coroutines.delay

/**
 * 「AA 画面 → 眼镜」的显示设置：内容区识别 + HUD 叠加。
 *
 * 与 [AaCastSection]（怎么设 OpenHeadUnit、息屏、抓帧取证）互补：
 * 这里只管**画面怎么映射**、**HUD 叠不叠**，两者都写 [CastService] / [CastHud] 的
 * 静态字段 —— 下一帧就生效，不需要重启投屏。
 */
@Composable
fun CastHudSection(onStatus: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }

    var hudOn by remember { mutableStateOf(prefs.hudOn) }
    var hudPos by remember { mutableStateOf(prefs.hudPos) }
    var hudAlpha by remember { mutableStateOf(prefs.hudAlpha) }
    var hudNavOnly by remember { mutableStateOf(prefs.hudNavOnly) }
    var contentDetect by remember { mutableStateOf(prefs.aaContentDetect) }
    var contentDesc by remember { mutableStateOf(CastService.contentDesc) }
    var running by remember { mutableStateOf(CastService.running) }
    var fps by remember { mutableStateOf(CastService.fps) }
    var mode by remember { mutableStateOf(CastService.fillMode) }

    // 首帧把 UI 状态同步到运行时（服务没起来时也保持一致）
    LaunchedEffect(Unit) {
        CastHud.enabled = hudOn
        CastHud.position = hudPos
        CastHud.alphaPct = hudAlpha
        CastHud.navOnly = hudNavOnly
        CastService.aaContent = contentDetect
        while (true) {
            contentDesc = CastService.contentDesc
            running = CastService.running
            fps = CastService.fps
            mode = CastService.fillMode
            delay(1000)
        }
    }

    fun applyHud() {
        CastHud.enabled = hudOn
        CastHud.position = hudPos
        CastHud.alphaPct = hudAlpha
        CastHud.navOnly = hudNavOnly
        CastHud.reset()
        prefs.hudOn = hudOn
        prefs.hudPos = hudPos
        prefs.hudAlpha = hudAlpha
        prefs.hudNavOnly = hudNavOnly
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("AA 画面 → 眼镜：映射与 HUD", style = MaterialTheme.typography.titleSmall)

        Text(
            if (!running) {
                "投屏未运行 · 内容区识别: " + (if (contentDetect) "开" else "关")
            } else {
                "画面模式 ${CastService.modeName(mode)} · 抓取 ${CastService.captureDesc.ifEmpty { "?" }}" +
                    " · AA 内容区 ${contentDesc.ifEmpty { "全屏" }} · %.1f fps".format(fps)
            },
            style = MaterialTheme.typography.bodySmall
        )

        // ---- 内容区识别 ----
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("识别 AA 内容区（剥黑边）", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "默认开。OpenHeadUnit 全屏时 AA 是 16:9、左右各 210 px 黑边；" +
                        "剥掉后「适应」→ 640×360（上下各 20 px），「裁剪」只切 5%（旧版切 13%）。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = contentDetect,
                onCheckedChange = {
                    contentDetect = it
                    CastService.aaContent = it
                    prefs.aaContentDetect = it
                    onStatus(if (it) "AA 内容区识别: 开" else "AA 内容区识别: 关（旧版整幅映射）")
                }
            )
        }

        // ---- HUD 开关 ----
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("HUD 叠加到 AA 画面", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "时间 / lux / 水平仪气泡 / 导航箭头画在 AA 帧之上，可单独关掉。" +
                        "通知栏也有「HUD 开/关」动作（息屏时不用回 App）。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = hudOn,
                onCheckedChange = {
                    hudOn = it
                    applyHud()
                    onStatus(if (it) "HUD 叠加: 开（${CastHud.layoutName}）" else "HUD 叠加: 关")
                }
            )
        }

        if (hudOn) {
            Text("版式（顶部条在「适应」模式下正好落在黑边里，完全不挡 AA）", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { hudPos = CastHud.POS_CORNERS; applyHud() },
                    modifier = Modifier.weight(1f)
                ) { Text(if (hudPos == CastHud.POS_CORNERS) "✓ 四角" else "四角") }
                OutlinedButton(
                    onClick = { hudPos = CastHud.POS_TOP; applyHud() },
                    modifier = Modifier.weight(1f)
                ) { Text(if (hudPos == CastHud.POS_TOP) "✓ 顶部条" else "顶部条") }
                OutlinedButton(
                    onClick = { hudPos = CastHud.POS_BOTTOM; applyHud() },
                    modifier = Modifier.weight(1f)
                ) { Text(if (hudPos == CastHud.POS_BOTTOM) "✓ 底部条" else "底部条") }
            }

            Text("不透明度 $hudAlpha%", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = hudAlpha.toFloat(),
                onValueChange = { hudAlpha = it.toInt(); CastHud.alphaPct = hudAlpha },
                onValueChangeFinished = { applyHud() },
                valueRange = 20f..100f,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("极简：只画导航箭头", modifier = Modifier.weight(1f))
                Switch(
                    checked = hudNavOnly,
                    onCheckedChange = { hudNavOnly = it; applyHud() }
                )
            }
        }
    }
}
