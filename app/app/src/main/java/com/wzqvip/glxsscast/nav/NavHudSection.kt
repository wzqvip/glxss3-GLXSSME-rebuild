package com.wzqvip.glxsscast.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wzqvip.glxsscast.settings.Prefs
import com.wzqvip.glxsscast.usb.UsbLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「导航 HUD」页：Google 地图导航通知 → **手机直接推眼镜**（方案 A），UDP → Pi 作为可选。
 *
 * 页面内容：
 *  - 通知使用权 / NotificationListenerService 的授权与连接状态；
 *  - 「导航 HUD → 眼镜」开关（1 Hz 直推 + 数据变化立即推）；
 *  - **实时预览**：与直推同一套 640×400 渲染，不插眼镜也能看版式；
 *  - 「抓一帧 PNG」：存到 `getExternalFilesDir/nav-hud/`，附带解析字段 json；
 *  - 「模拟右转/左转/到达」：不开车也能走完 解析 → 渲染 → 推送；
 *  - 最近一条地图通知原文 + 解析结果（title/text/subText/maneuver/distance/eta）；
 *  - 折叠的「UDP 发到 Pi」旧链路（默认关闭）。
 */
@Composable
fun NavHudSection(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var direct by remember { mutableStateOf(prefs.navDirect) }
    var udpOpen by remember { mutableStateOf(prefs.navSend) }
    var udpSend by remember { mutableStateOf(prefs.navSend) }
    var host by remember { mutableStateOf(prefs.navHost) }
    var portText by remember { mutableStateOf(prefs.navPort.toString()) }
    var statusLine by remember { mutableStateOf("") }
    var usbOpen by remember { mutableStateOf(UsbLink.get(context).isOpen) }
    var pngBusy by remember { mutableStateOf(false) }
    var pngPath by remember { mutableStateOf(NavHudPusher.lastPngPath.value) }
    // 预览复用同一个 Bitmap（1 Hz 重画）。不要 recycle：Compose 可能还在画上一帧。
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // 1 Hz：刷新授权/USB 状态 + 重画预览（和直推走同一套 snapshot/render）
    LaunchedEffect(Unit) {
        NavHudPusher.syncFromPrefs(context)
        while (true) {
            try {
                NavBus.refreshPermission(context)
                usbOpen = UsbLink.get(context).isOpen
                direct = prefs.navDirect
                preview = NavHudRenderer.render(NavHudPusher.snapshot(context), preview)
            } catch (t: Throwable) {
                statusLine = "预览刷新失败: ${t.javaClass.simpleName}: ${t.message}"
            }
            delay(1000)
        }
    }

    val permission = NavBus.listenerEnabled.value
    val connected = NavBus.listenerConnected.value
    val frame = NavBus.last.value
    val lastJson = NavBus.lastJson.value
    val sent = NavBus.sent.value

    HorizontalDivider()
    Text("导航 HUD（Google 地图 → 眼镜直推）", style = MaterialTheme.typography.titleSmall)
    Text(
        if (permission) "通知使用权：已开启" else "通知使用权：未开启（必须开启才能读地图导航）",
        style = MaterialTheme.typography.bodySmall,
        color = if (permission) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error
    )
    Text(
        "监听服务：" + when {
            connected -> "已连接（等待 Google 地图导航通知）"
            permission -> "已授权但服务未连上（点下方按钮重新授权 / 重启 App）"
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

    // ---- 方案 A：直推眼镜 ----
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "导航 HUD → 眼镜（USB 直推，每秒一帧）",
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = direct,
            onCheckedChange = {
                direct = it
                NavHudPusher.setEnabled(context, it)
                statusLine = if (it) "已开启直推（插上眼镜并连接后即可显示）" else "已关闭直推"
            }
        )
    }
    Text(
        if (!usbOpen) "眼镜：未连接 —— 把眼镜插到手机上，点上方「连接眼镜」。"
        else "眼镜：已连接（" + (if (direct) "直推中" else "直推已关") + "）",
        style = MaterialTheme.typography.bodySmall,
        color = if (usbOpen) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error
    )
    Text(
        NavHudPusher.status.value +
            if (NavHudPusher.lastPushAt.value > 0) {
                " · 最近 " + timeText(NavHudPusher.lastPushAt.value) +
                    " · ${NavHudPusher.lastPushMs.value} ms/帧"
            } else "",
        style = MaterialTheme.typography.bodySmall
    )
    if (NavHudPusher.lastError.value.isNotEmpty()) {
        Text(
            "推送错误: " + NavHudPusher.lastError.value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    // 实时预览：640×400 等比缩小
    preview?.let { bmp ->
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .padding(vertical = 2.dp)
        ) {
            drawImage(
                image = bmp.asImageBitmap(),
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = {
                pngBusy = true
                scope.launch {
                    val path = withContext(Dispatchers.IO) {
                        NavHudPusher.capturePng(context, note = "UI 按钮")
                    }
                    pngBusy = false
                    pngPath = path
                    statusLine = if (path.isEmpty()) "抓帧失败（见 oplog）" else "已保存 PNG"
                }
            },
            enabled = !pngBusy,
            modifier = Modifier.weight(1f)
        ) { Text(if (pngBusy) "抓帧中…" else "抓一帧 PNG") }
        OutlinedButton(
            onClick = {
                NavHudPusher.pushOnce(context)
                statusLine = "已请求立即推一帧"
            },
            modifier = Modifier.weight(1f)
        ) { Text("立即推一帧") }
    }
    if (pngPath.isNotEmpty()) {
        Text(
            "PNG: $pngPath",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
    if (statusLine.isNotEmpty()) {
        Text(statusLine, style = MaterialTheme.typography.bodySmall)
    }

    // ---- 调试：最近一条地图通知 ----
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
            onClick = {
                NavHudPusher.capturePng(
                    context,
                    overrideFrame = NavParser.simulate(
                        Maneuver.ROUNDABOUT, 80.0, 45, 900.0
                    ),
                    note = "环岛样张",
                )
                statusLine = "已保存环岛样张（不依赖 Maps）"
            },
            modifier = Modifier.weight(1f)
        ) { Text("环岛样张 PNG") }
    }
    OutlinedButton(
        onClick = { NavBus.clearDebug() },
        modifier = Modifier.fillMaxWidth()
    ) { Text("清空调试状态") }

    // ---- 方案 B（可选）：UDP → Pi ----
    OutlinedButton(
        onClick = { udpOpen = !udpOpen },
        modifier = Modifier.fillMaxWidth()
    ) { Text((if (udpOpen) "▾ " else "▸ ") + "UDP 发到 Pi（可选，默认关闭）") }
    if (udpOpen) {
        Text(
            "旧方案：把导航 JSON（每秒 1 包）发给 Pi，由 Pi 端 `glxss hud` 渲染。" +
                "手机直推眼镜（上方开关）才是默认路径，两者可同时开。",
            style = MaterialTheme.typography.bodySmall
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                singleLine = true,
                label = { Text("Pi IP") },
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
                checked = udpSend,
                onCheckedChange = {
                    udpSend = it
                    prefs.navSend = it
                    NavBus.resetSocket()
                    statusLine = if (it) "开始向 Pi 发包" else "已暂停 UDP 发包"
                }
            )
        }
        Text(
            "UDP 状态：" + (if (NavBus.active.value) "导航中" else "空闲") +
                " · 已发 $sent 包",
            style = MaterialTheme.typography.bodySmall
        )
        if (NavBus.error.value.isNotEmpty()) {
            Text(
                "UDP 错误: " + NavBus.error.value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (lastJson.isNotEmpty()) {
            Text(
                "最近发出: $lastJson",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
    Text(
        "主路径不需要 Pi：Maps 导航通知 → 手机渲染 640×400 → USB bulk 直推眼镜。" +
            "眼镜没有磁力计，HUD 只画机动箭头 + 距离 + 指令，不画罗盘。",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 2.dp)
    )
}

private fun timeText(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))
