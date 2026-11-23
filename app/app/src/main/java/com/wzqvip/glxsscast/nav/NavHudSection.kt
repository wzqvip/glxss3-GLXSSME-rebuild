package com.wzqvip.glxsscast.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wzqvip.glxsscast.settings.Prefs
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * “导航 HUD” 页面：通知使用权引导 + 调试信息 + 目标地址 + 模拟通知。
 *
 * 实时显示：
 *  - 通知使用权是否开启、NotificationListenerService 是否连上；
 *  - 最近一条 Google 地图通知的原文（title/text/subText）和解析结果；
 *  - 实际发出的 UDP JSON、发包计数、发送错误；
 *  - “模拟一条通知”：造一条假通知走同一套解析/发送链路（本机不开车也能自测）。
 */
@Composable
fun NavHudSection(prefs: Prefs) {
    val context = LocalContext.current
    var host by remember { mutableStateOf(prefs.navHost) }
    var portText by remember { mutableStateOf(prefs.navPort.toString()) }
    var sendEnabled by remember { mutableStateOf(prefs.navSend) }
    var statusLine by remember { mutableStateOf("") }

    // 每秒刷新一次授权/连接状态（系统设置页回来后也能立刻反映）。
    LaunchedEffect(Unit) {
        NavBus.ensureTicker(context)
        while (true) {
            NavBus.refreshPermission(context)
            delay(1000)
        }
    }

    val permission = NavBus.listenerEnabled.value
    val connected = NavBus.listenerConnected.value
    val frame = NavBus.last.value
    val sent = NavBus.sent.value
    val error = NavBus.error.value
    val lastJson = NavBus.lastJson.value
    val active = NavBus.active.value
    val lastSentAt = NavBus.lastSentAt.value

    HorizontalDivider()
    Text("导航 HUD（Google 地图 → Pi）", style = MaterialTheme.typography.titleSmall)
    Text(
        if (permission) "通知使用权：已开启" else "通知使用权：未开启（必须开启才能读地图导航）",
        style = MaterialTheme.typography.bodySmall,
        color = if (permission) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error
    )
    Text(
        "监听服务：" + when {
            connected -> "已连接（等待 Google 地图导航通知）"
            permission -> "已授权但服务未连上（点下方按钮重新打开授权页/重启 App）"
            else -> "未连接"
        },
        style = MaterialTheme.typography.bodySmall
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { NavListenerService.openSettings(context) },
            modifier = Modifier.weight(1f)
        ) { Text(if (permission) "通知使用权设置" else "开启通知使用权") }
        OutlinedButton(
            onClick = { NavBus.runIconSelfTest() },
            modifier = Modifier.weight(1f)
        ) { Text("图标相似度自检") }
    }
    if (NavBus.iconSelfTest.value.isNotEmpty()) {
        Text(
            "图标自检: " + NavBus.iconSelfTest.value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }

    Text("目标地址（Pi 的 Wi-Fi IP + UDP 端口）", style = MaterialTheme.typography.bodySmall)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            singleLine = true,
            label = { Text("IP") },
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
            singleLine = true,
            label = { Text("端口") },
            modifier = Modifier.width(96.dp)
        )
        Button(onClick = {
            prefs.navHost = host
            prefs.navPort = portText.toIntOrNull() ?: Prefs.DEFAULT_NAV_PORT
            host = prefs.navHost
            portText = prefs.navPort.toString()
            NavBus.resetSocket()
            statusLine = "已保存：%s:%d".format(prefs.navHost, prefs.navPort)
        }) { Text("保存") }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("发送 UDP（每秒 1 包）", modifier = Modifier.weight(1f))
        Switch(
            checked = sendEnabled,
            onCheckedChange = {
                sendEnabled = it
                prefs.navSend = it
                NavBus.resetSocket()
                statusLine = if (it) "开始向 Pi 发包" else "已暂停发包"
            }
        )
    }
    if (statusLine.isNotEmpty()) {
        Text(statusLine, style = MaterialTheme.typography.bodySmall)
    }

    Text(
        "状态：" + when {
            active -> "导航中 · 正在发包"
            frame != null -> "已停止（等待下一帧）"
            else -> "等待手机导航…"
        } + " · 已发 $sent 包" +
            if (lastSentAt > 0) " · 最近 " + timeText(lastSentAt) else "",
        style = MaterialTheme.typography.bodySmall
    )
    if (error.isNotEmpty()) {
        Text(
            "发送错误: $error",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    Text("最近一条地图通知（调试）", style = MaterialTheme.typography.bodySmall)
    if (frame == null) {
        Text(
            "（尚未收到）在 Google 地图里开始驾车/步行导航，通知栏出现转向提示即可。",
            style = MaterialTheme.typography.bodySmall
        )
    } else {
        Text(
            "title: " + frame.title + "\n" +
                "text: " + frame.text + "\n" +
                "subText: " + frame.subText +
                (if (frame.simulated) "  [模拟]" else ""),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
    Text(
        NavParser.summary(frame),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace
    )
    if (lastJson.isNotEmpty()) {
        Text(
            "实际发出: $lastJson",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = {
                NavBus.simulate(context, Maneuver.RIGHT, 500.0, 120, 2400.0)
                statusLine = "已模拟一条右转导航通知"
            },
            modifier = Modifier.weight(1f)
        ) { Text("模拟右转 500m") }
        Button(
            onClick = {
                NavBus.simulate(context, Maneuver.ARRIVE, 0.0, 0, 0.0)
                statusLine = "已模拟一条到达通知"
            },
            modifier = Modifier.weight(1f)
        ) { Text("模拟到达") }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = {
                NavBus.simulate(context, Maneuver.LEFT, 120.0, 300, 5200.0)
                statusLine = "已模拟一条左转导航通知"
            },
            modifier = Modifier.weight(1f)
        ) { Text("模拟左转 120m") }
        OutlinedButton(
            onClick = { NavBus.clearDebug() },
            modifier = Modifier.weight(1f)
        ) { Text("清空调试状态") }
    }
    Text(
        "手机和 Pi 要在同一个 Wi-Fi；Pi 端跑 `glxss hud`（或 stream 的 HUD 模式）" +
            "并在 9999/UDP 收包。眼镜没有磁力计，HUD 只画机动箭头，不画罗盘。",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 2.dp)
    )
}

private fun timeText(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))
