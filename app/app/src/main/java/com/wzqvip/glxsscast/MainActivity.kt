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
    private var castInfo by mutableStateOf("未投屏")
    private var fillMode by mutableStateOf(com.wzqvip.glxsscast.cast.CastService.MODE_CROP)
    private var ledOn by mutableStateOf(false)

    private val projectionManager by lazy {
        getSystemService(android.media.projection.MediaProjectionManager::class.java)
    }

    private val mpLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            val i = Intent(this, com.wzqvip.glxsscast.cast.CastService::class.java).apply {
                action = com.wzqvip.glxsscast.cast.CastService.ACTION_START
                putExtra(com.wzqvip.glxsscast.cast.CastService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(com.wzqvip.glxsscast.cast.CastService.EXTRA_RESULT_DATA, data)
                putExtra(com.wzqvip.glxsscast.cast.CastService.EXTRA_MODE, fillMode)
            }
            androidx.core.content.ContextCompat.startForegroundService(this, i)
            status = "投屏已启动"
        } else {
            status = "投屏授权被取消"
        }
    }
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
        usb = UsbLink.get(this)
        fillMode = getSharedPreferences("prefs", MODE_PRIVATE).getInt("mode", fillMode)
        com.wzqvip.glxsscast.cast.CastService.fillMode = fillMode

        // UVC 设备需要 CAMERA 权限才会授予 USB 访问
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.CAMERA
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1)
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2)
        }

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
        // 连接是进程内共享的（CastService 可能还在用），这里不关闭
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

    private fun stopCastService() {
        startService(
            Intent(this, com.wzqvip.glxsscast.cast.CastService::class.java)
                .setAction(com.wzqvip.glxsscast.cast.CastService.ACTION_STOP)
        )
    }

    private fun sendAsync(payload: ByteArray) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                // 发测试图前先停止投屏，避免画面被投屏覆盖
                if (com.wzqvip.glxsscast.cast.CastService.running) {
                    stopCastService()
                    Thread.sleep(400)
                }
                // 停止投屏会把显示模式切回 1（关屏），发测试图前先切回 2（开屏）
                usb.connection?.let { LcdControl.setDisplayMode(it, 2) }
                usb.sendFrame(LcdProtocol.frame(payload))
            }
            busy = false
            if (ok) {
                frames++
                status = "已发送 $frames 帧（已停止投屏）"
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
                    Text(castInfo, style = MaterialTheme.typography.bodyMedium)

                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        while (true) {
                            castInfo = if (com.wzqvip.glxsscast.cast.CastService.running) {
                                "投屏中: %.1f fps, 累计 %d 帧".format(
                                    com.wzqvip.glxsscast.cast.CastService.fps,
                                    com.wzqvip.glxsscast.cast.CastService.frames
                                )
                            } else {
                                "未投屏"
                            }
                            kotlinx.coroutines.delay(500)
                        }
                    }

                    Button(
                        onClick = {
                            fillMode = (fillMode + 1) % 3
                            com.wzqvip.glxsscast.cast.CastService.fillMode = fillMode
                            getSharedPreferences("prefs", MODE_PRIVATE).edit()
                                .putInt("mode", fillMode).apply()

                            status = "画面模式: " + com.wzqvip.glxsscast.cast.CastService.modeName(fillMode)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "画面模式: " + com.wzqvip.glxsscast.cast.CastService.modeName(fillMode) +
                                if (com.wzqvip.glxsscast.cast.CastService.running) "（立即生效）" else ""
                        )
                    }

                    Button(
                        onClick = {
                            mpLauncher.launch(projectionManager.createScreenCaptureIntent())
                        },
                        enabled = usb.isOpen,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("开始投屏（实时镜像）") }

                    Button(
                        onClick = {
                            startService(
                                Intent(this@MainActivity, com.wzqvip.glxsscast.cast.CastService::class.java)
                                    .setAction(com.wzqvip.glxsscast.cast.CastService.ACTION_STOP)
                            )
                            status = "已停止投屏"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("停止投屏") }

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
                                if (LcdControl.setDisplayMode(c, 2)) status = "显示模式 2（投屏/开屏）" else status = "显示模式设置失败"
                            } ?: run { status = "未连接" }
                        },
                        enabled = usb.isOpen,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("开屏（显示模式 2）") }



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
                        onClick = {
                            if (com.wzqvip.glxsscast.usb.Peripherals.hidRunning) {
                                com.wzqvip.glxsscast.usb.Peripherals.stopHid()
                                status = "HID 已停止（共 " + com.wzqvip.glxsscast.usb.Peripherals.hidPackets + " 包）"
                            } else if (usb.isOpen) {
                                com.wzqvip.glxsscast.usb.Peripherals.startHid(usb)
                                status = "HID 监听中"
                            } else status = "未连接"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (com.wzqvip.glxsscast.usb.Peripherals.hidRunning)
                                "停止 HID（已收 " + com.wzqvip.glxsscast.usb.Peripherals.hidPackets + "）"
                            else "开始 HID 监听"
                        )
                    }

                    Button(
                        onClick = {
                            lifecycleScope.launch {
                                val r = withContext(Dispatchers.IO) { com.wzqvip.glxsscast.usb.Peripherals.micRms() }
                                status = if (r < 0) "麦克风不可用" else "麦克风 RMS = " + r
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("麦克风电平测试") }

                    Button(
                        onClick = {
                            lifecycleScope.launch {
                                val ok = withContext(Dispatchers.IO) { com.wzqvip.glxsscast.usb.Peripherals.playTone(440.0, 1.0) }
                                status = if (ok) "已播放 440Hz（应走眼镜扬声器）" else "播放失败"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("播放测试音（眼镜扬声器）") }



                    Button(
                        onClick = {
                            val usbMgr = getSystemService(android.content.Context.USB_SERVICE) as UsbManager
                            val bootDev = com.wzqvip.glxsscast.usb.BootLoader.findBootDevice(usbMgr)
                            if (bootDev == null) {
                                status = "未检测到引导模式设备(03E7:2150)"
                            } else if (!usb.hasPermission(bootDev)) {
                                status = "请求引导设备权限…"
                                usb.requestPermission(bootDev)
                            } else {
                                val base = getExternalFilesDir(null)?.absolutePath ?: "/sdcard/Android/data/com.wzqvip.glxsscast/files"
                                val img = base + "/G25_MainFW_1.9.1.img"
                                val key = base + "/llvision.enkey"
                                if (!java.io.File(img).exists()) {
                                    status = "缺少固件文件: " + img
                                } else {
                                    status = "正在加载固件…"
                                    com.wzqvip.glxsscast.usb.MyriadBootLoader.flash(
                                        this@MainActivity, bootDev, img,
                                        { p, m -> runOnUiThread { status = "$p% $m" } },
                                        { ok, msg -> runOnUiThread { status = msg } }
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("加载固件（引导模式 03E7:2150）") }

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







