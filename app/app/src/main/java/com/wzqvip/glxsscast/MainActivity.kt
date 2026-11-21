package com.wzqvip.glxsscast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.wzqvip.glxsscast.lcd.LcdControl
import com.wzqvip.glxsscast.lcd.LcdProtocol
import com.wzqvip.glxsscast.usb.UsbLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var usb: UsbLink
    private var status by mutableStateOf("未连接")
    private var frames by mutableStateOf(0)
    private var brightness by mutableStateOf(180)
    private var busy by mutableStateOf(false)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbLink.ACTION_PERMISSION) return
            val dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (dev != null && granted) connect(dev) else status = "USB 权限被拒绝"
        }
    }

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev = intent.getParcelableExtra(
                        UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java
                    )
                    if (dev != null && dev.vendorId == UsbLink.VID) connect(dev)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    usb.close()
                    status = "设备已断开"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = UsbLink(this)

        androidx.core.content.ContextCompat.registerReceiver(
            this, permissionReceiver, IntentFilter(UsbLink.ACTION_PERMISSION),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, attachReceiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )

        setContent { App() }
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        unregisterReceiver(attachReceiver)
        usb.close()
        super.onDestroy()
    }

    private fun connect(dev: android.hardware.usb.UsbDevice) {
        if (!usb.hasPermission(dev)) {
            status = "请求 USB 权限…"
            usb.requestPermission(dev)
            return
        }
        status = if (usb.open(dev)) "已连接: ${dev.productName ?: dev.deviceName}" else "打开失败"
    }

    private fun sendAsync(payload: ByteArray) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                usb.sendFrame(LcdProtocol.frame(payload))
            }
            busy = false
            if (ok) {
                frames++
                status = "已发送 $frames 帧"
            } else {
                status = "发送失败（检查端点/权限）"
            }
        }
    }

    @Composable
    fun App() {
        MaterialTheme {
            Scaffold(
                topBar = { TopAppBar(title = { Text("GLXSS3 Cast") }) }
            ) { pad ->
                Column(
                    Modifier
                        .padding(pad)
                        .padding(16.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(status, style = MaterialTheme.typography.bodyLarge)
                    Button(
                        onClick = {
                            val dev = usb.findDevice()
                            if (dev == null) status = "未找到眼镜（VID 0x2E09）" else connect(dev)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("连接眼镜") }

                    Button(
                        onClick = { sendAsync(LcdProtocol.bars()) },
                        enabled = usb.isOpen && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("发送测试色条") }

                    Button(
                        onClick = { sendAsync(LcdProtocol.solid(0x18, 0x88, 0xFF)) },
                        enabled = usb.isOpen && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("发送纯色 (蓝)") }

                    Button(
                        onClick = { sendAsync(LcdProtocol.solid(0, 0, 0)) },
                        enabled = usb.isOpen && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("发送黑屏") }

                    Button(
                        onClick = {
                            usb.connection?.let { c ->
                                if (LcdControl.setDisplayMode(c, 2)) status = "显示模式 2（投屏）" else status = "显示模式设置失败"
                            }
                        },
                        enabled = usb.isOpen,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("开屏（显示模式 2）") }

                    Button(
                        onClick = {
                            usb.connection?.let { c ->
                                if (LcdControl.setDisplayMode(c, 1)) status = "显示模式 1（关闭/待机）" else status = "显示模式设置失败"
                            }
                        },
                        enabled = usb.isOpen,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("关屏（显示模式 1）") }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                brightness = (brightness - 20).coerceIn(100, 254)
                                usb.connection?.let { LcdControl.setBrightness(it, brightness) }
                                status = "亮度 = $brightness"
                            },
                            enabled = usb.isOpen,
                            modifier = Modifier.weight(1f)
                        ) { Text("亮度 −") }
                        Button(
                            onClick = {
                                brightness = (brightness + 20).coerceIn(100, 254)
                                usb.connection?.let { LcdControl.setBrightness(it, brightness) }
                                status = "亮度 = $brightness"
                            },
                            enabled = usb.isOpen,
                            modifier = Modifier.weight(1f)
                        ) { Text("亮度 +") }
                    }

                    Button(
                        onClick = {
                            usb.connection?.let { c ->
                                val st = LcdControl.getStatus(c)
                                status = if (st != null) "状态: ${st.joinToString("") { "%02x".format(it) }}  亮度=${st[5].toInt() and 0xff}" else "读状态失败"
                            }
                        },
                        enabled = usb.isOpen,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("读取状态") }

                    Button(
                        onClick = { usb.close(); status = "已断开" },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("断开") }

                    Text(
                        "协议：16B 头 + 640×400 RGBA8888\n" +
                        "下一步：MediaProjection 实时投屏、亮度/开关控制",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
