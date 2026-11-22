package com.wzqvip.glxsscast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.wzqvip.glxsscast.audio.AudioEnh
import com.wzqvip.glxsscast.diag.Oplog
import com.wzqvip.glxsscast.lcd.LcdControl
import com.wzqvip.glxsscast.lcd.LcdProtocol
import com.wzqvip.glxsscast.lcd.ProductInfo
import com.wzqvip.glxsscast.record.RecordSource
import com.wzqvip.glxsscast.record.VideoRecorder
import com.wzqvip.glxsscast.settings.HidAction
import com.wzqvip.glxsscast.settings.Prefs
import com.wzqvip.glxsscast.stress.CpuSampler
import com.wzqvip.glxsscast.usb.HidSensor
import com.wzqvip.glxsscast.usb.Peripherals
import com.wzqvip.glxsscast.usb.UsbLink
import com.wzqvip.glxsscast.uvc.UvcCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 单页控制台。功能分区：
 * 1. 连接（自动重连 / 手动连接 / 断开）
 * 2. UVC 摄像头（描述符档位下拉 / 拍照 / 原始抓包）
 * 3. 投屏（开始 / 停止 / 画面模式 / 画面调整）
 * 4. LCD 控制（读状态字段 / 读产品信息 / 开屏 / 亮度 / 自动亮度 / 测试图 / 固件版本）
 * 5. 外设（HID / 麦克风 / 扬声器）
 * 6. 固件恢复
 *
 * 设置持久化统一走 [Prefs]（SharedPreferences `prefs`），重启后恢复。
 */
class MainActivity : ComponentActivity() {

    private lateinit var usb: UsbLink
    private lateinit var prefs: Prefs

    // ---- 基础状态 ----
    private var status by mutableStateOf("未连接")
    private var statusFields by mutableStateOf("")
    private var frames by mutableStateOf(0)
    private var brightness by mutableStateOf(180)
    private var castInfo by mutableStateOf("未投屏")
    private var fillMode by mutableStateOf(com.wzqvip.glxsscast.cast.CastService.MODE_CROP)
    private var showUvc by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var rollIdx by mutableStateOf(0)
    private val ROTATE_VALUES = intArrayOf(0, 2, 4, 6, 8, 10, 12, 14)

    // ---- 画面调整 ----
    private var zoom by mutableStateOf(1f)
    private var offsetX by mutableStateOf(0f)
    private var offsetY by mutableStateOf(0f)
    private var adjustOpen by mutableStateOf(false)

    // ---- 开关（持久化） ----
    private var autoBrightness by mutableStateOf(false)
    private var hidOn by mutableStateOf(false)
    private var autoReconnect by mutableStateOf(true)

    // ---- HID 传感器 / Fn 按键 ----
    private var hidSensor: HidSensor? = null
    private var hidInfo by mutableStateOf("HID 未启动")
    private var hidHex by mutableStateOf("")
    private var hidClickAction by mutableStateOf(HidAction.SNAPSHOT)
    private var hidLongAction by mutableStateOf(HidAction.CAST_TOGGLE)
    private var hidMode by mutableStateOf(HidSensor.Mode.POLL)

    // ---- 传感器量程 / 姿态可视化（演示用） ----
    private data class Vec3(val x: Float, val y: Float, val z: Float) {
        companion object { val ZERO = Vec3(0f, 0f, 0f) }
    }

    /** 一帧姿态：acc/gyro 已换算；acc/pitch/roll 已减去「水平零点」 */
    private data class Attitude(
        val acc: Vec3 = Vec3.ZERO,
        val gyro: Vec3 = Vec3.ZERO,
        val accMagG: Float = 0f,
        val accRawMag: Float = 0f,
        val pitch: Float = 0f,
        val roll: Float = 0f,
        val bubbleX: Float = 0f,
        val bubbleY: Float = 0f
    )

    private var accFsr by mutableStateOf(1)
    private var gyroFsr by mutableStateOf(3)
    private var accFsrMenu by mutableStateOf(false)
    private var gyroFsrMenu by mutableStateOf(false)
    private var hwFsrLine by mutableStateOf("硬件 FSR：启动 HID 后自动读 0x06")
    private var fsrEstimate by mutableStateOf("量程估计：未运行（先启动 HID，再点「自动估计量程」）")
    private var fsrEstimating by mutableStateOf(false)
    private var attitude by mutableStateOf(Attitude())
    private var zeroRaw by mutableStateOf<IntArray?>(null)
    private var zeroPending = true
    /** 取零点前先凑 5 帧取中位数，避开 HID 启动时的第一帧瞬态/抖动 */
    private val zeroSamples = ArrayList<IntArray>()
    private var lastRawAcc: IntArray? = null

    // ---- 崩溃现场 ----
    private var crashInfo by mutableStateOf("")
    private var crashDialog by mutableStateOf(false)
    private var crashText by mutableStateOf("")

    /** 用户主动点「断开」后暂停自动重连（插拔/手动连接会恢复） */
    private var reconnectPaused = false

    // ---- EEPROM 产品信息 ----
    private var productInfo by mutableStateOf<ProductInfo?>(null)
    private var productDialog by mutableStateOf(false)

    // ---- UVC 档位 ----
    private var uvcOptions by mutableStateOf<List<UvcCamera.StreamOption>>(emptyList())
    private var uvcSelected by mutableStateOf<UvcCamera.StreamOption?>(null)
    private var uvcMenuOpen by mutableStateOf(false)
    private var uvcErr by mutableStateOf("")

    // ---- 录像 ----
    private val recorder by lazy { VideoRecorder(this) }
    private var recSource by mutableStateOf(RecordSource.CAST)
    private var recInfo by mutableStateOf("未录像")
    private var recPath by mutableStateOf("")

    // ---- 音频增强 ----
    private var micDevices by mutableStateOf<List<String>>(emptyList())
    private var audioInfo by mutableStateOf("音频：未测试")
    private var volume by mutableStateOf(AudioEnh.Volume(0, 0))

    // ---- 三路并发压力测试 ----
    private var stressRunning by mutableStateOf(false)
    private var stressInfo by mutableStateOf("")
    private var stressJob: Job? = null
    private var stressCancelled = false

    /** Compose 里 rememberUvcCamera 创建的实例，录像/压力测试需要直接拿它挂旁路 */
    private var activeUvc: UvcCamera? = null

    /** USB 打开/重连串行化，避免自动重连循环和手动操作打架 */
    private val usbMutex = Mutex()
    private var lastPermissionRequestAt = 0L

    private data class UvcOpenResult(
        val options: List<UvcCamera.StreamOption>,
        val selected: UvcCamera.StreamOption?,
        val status: String
    )

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

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbLink.ACTION_PERMISSION) return
            val dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Oplog.logf(
                "CONN", "USB permission result dev=%s granted=%b",
                dev?.deviceName ?: "null", granted
            )
            when {
                dev == null -> status = "USB 权限被拒绝"
                !granted -> status = "USB 权限被拒绝"
                // 引导模式设备：继续灌固件（不能当正常设备连接）
                dev.vendorId == com.wzqvip.glxsscast.usb.MyriadBootLoader.BOOT_VID -> autoFlashBoot(dev)
                else -> connect(dev)
            }
        }
    }

    /**
     * 拔插自动重连：
     * - ATTACHED：VID 0x2E09 的 GLXSS3（正常模式 2e09:0030）自动连接；
     * - DETACHED：清掉状态，后台重连循环会在设备回来后自动恢复。
     */
    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // 重新插上 = 用户想用设备了：解除「断开」暂停，交给自动重连/自动连接
                    reconnectPaused = false
                    val dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (dev != null && dev.vendorId == 0x03E7) {
                        status = "检测到引导模式设备"
                        // 掉回引导模式 = 固件崩溃复位；先把最后 200 条操作固化下来
                        Oplog.dumpCrash("boot_mode_03e7(attach)")
                        autoFlashBoot(dev)
                    }
                    if (dev != null && dev.vendorId == UsbLink.VID) {
                        status = "检测到眼镜（2e09:%04x），自动连接…".format(dev.productId)
                        connect(dev)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (dev == null || dev.vendorId == UsbLink.VID) {
                        Oplog.dumpCrash("usb_detached(productId=${dev?.productId})")
                        usb.close()
                        status = "设备已断开（等待自动重连…）"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = UsbLink.get(this)
        Oplog.init(this)
        prefs = Prefs.get(this)

        // ---- 恢复持久化设置 ----
        fillMode = prefs.fillMode.coerceIn(0, 2)
        com.wzqvip.glxsscast.cast.CastService.fillMode = fillMode
        zoom = prefs.zoom
        offsetX = prefs.offsetX
        offsetY = prefs.offsetY
        applyAdjust(zoom, offsetX, offsetY, persist = false)
        autoBrightness = prefs.autoBrightness
        autoReconnect = prefs.autoReconnect
        hidClickAction = HidAction.from(prefs.hidClickAction)
        hidLongAction = HidAction.from(prefs.hidLongAction)
        hidMode = if (prefs.hidMode == "interrupt") HidSensor.Mode.INTERRUPT else HidSensor.Mode.POLL
        accFsr = prefs.accFsr
        gyroFsr = prefs.gyroFsr
        hidOn = false
        volume = AudioEnh.volume(this)
        refreshAudioDevices()
        updateCrashInfo()

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
        // 投屏通知（含「停止投屏 / 画面模式」动作）需要通知权限才可见
        if (Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3)
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

        startAutoReconnectLoop()
        setContent { App() }
        // 实验/自动化脚本用：`am start ... --es cmd cast` 这类命令在 onCreate 后延迟执行
        window.decorView.postDelayed({ handleDebugIntent(intent) }, 800)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * 实验 harness 入口（`diag.DebugReceiver` / `adb am start --es cmd ...`）。
     *
     * 只服务长跑实验与取证：投屏需要系统授权对话框，所以这里只负责把
     * `createScreenCaptureIntent()` 弹出来，授权仍需点一下。
     */
    private fun handleDebugIntent(intent: Intent?) {
        val cmd = intent?.getStringExtra("cmd") ?: return
        Oplog.logf("DEBUG", "intent cmd=%s", cmd)
        when (cmd) {
            "cast" -> {
                if (com.wzqvip.glxsscast.cast.CastService.running) {
                    status = "投屏已在运行"
                } else if (!usb.isOpen) {
                    status = "投屏前先连接眼镜"
                } else {
                    status = "请求投屏授权…"
                    runCatching { mpLauncher.launch(projectionManager.createScreenCaptureIntent()) }
                        .onFailure { status = "投屏授权启动失败: ${it.message}" }
                }
            }
            "record_start" -> startRecording()
            "record_stop" -> stopRecording()
            "hid_start" -> startHidSensor()
            "hid_stop" -> stopHidSensor()
            "fsr_estimate" -> estimateAccRange()
            "level_calib" -> calibrateLevel()
            "sensor_attr" -> readImuAttributes()
            "oplog_dump" -> {
                Oplog.dumpCrash("manual")
                updateCrashInfo()
                status = "已写 crash_last.txt"
            }
            "status" -> readStatusFields()
            else -> status = "未知调试命令: $cmd"
        }
    }

    private fun updateCrashInfo() {
        crashInfo = buildString {
            append("oplog ${Oplog.entryCount()} 条 / ${Oplog.fileSize() / 1024} KB")
            val reason = Oplog.lastCrashReason
            if (reason != null) append(" · 上次现场: $reason")
            else append(" · 暂无 crash 现场")
        }
    }

    private fun openCrashSnapshot() {
        val f = java.io.File(filesDir, "crash_last.txt")
        crashText = if (!f.exists()) {
            "还没有 crash_last.txt（未发生掉线/崩溃，或还没手动 dump）。\n\n" +
                "oplog.txt 路径: ${java.io.File(filesDir, "oplog.txt").absolutePath}"
        } else {
            runCatching { f.readLines().takeLast(200).joinToString("\n") }
                .getOrElse { "读取失败: ${it.message}" }
        }
        updateCrashInfo()
        crashDialog = true
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        unregisterReceiver(attachReceiver)
        // 录像要在进程里收尾，否则 muxer 的 mp4 索引写不完
        try {
            if (com.wzqvip.glxsscast.cast.CastService.frameTap === recorder) {
                com.wzqvip.glxsscast.cast.CastService.frameTap = null
            }
            activeUvc?.let { if (it.frameTap === recorder) it.frameTap = null }
            if (recorder.isRecording) recorder.stop()
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "stop recorder on destroy failed", t)
        }
        // 连接是进程内共享的（CastService 可能还在用），这里不关闭
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // 每次打开/回到页面都按当前姿态重新取水平零点（符合「每次打开都按照水平」）
        zeroPending = true
        zeroSamples.clear()
        // 回到前台时如果还没连上，立刻试一次（不等 1s 轮询）
        if (!usb.isOpen && prefs.autoReconnect && !reconnectPaused) {
            lifecycleScope.launch {
                delay(300)
                if (!usb.isOpen) tryOpenFound()
            }
        }
    }

    // ==================================================================
    // 连接 / 重连
    // ==================================================================

    private fun autoFlashBoot(dev: UsbDevice) {
        Oplog.logf("CONN", "autoFlashBoot dev=%s hasPerm=%b", dev.deviceName, usb.hasPermission(dev))
        if (!usb.hasPermission(dev)) { status = "引导模式设备，请求权限…"; usb.requestPermission(dev); return }
        val base = getExternalFilesDir(null)?.absolutePath ?: "/sdcard/Android/data/com.wzqvip.glxsscast/files"
        val img = base + "/G25_MainFW_1.9.1.img"
        if (!java.io.File(img).exists()) { status = "检测到引导模式，但缺少固件文件"; return }
        status = "检测到引导模式，自动加载固件…"
        com.wzqvip.glxsscast.usb.MyriadBootLoader.flash(this, dev, img,
            { p, m -> runOnUiThread { status = "$p% $m" } },
            { ok, msg -> runOnUiThread { status = msg } })
    }

    /** 手动连接：先看权限，再在 IO 线程打开 */
    private fun connect(dev: UsbDevice) {
        reconnectPaused = false
        if (!usb.hasPermission(dev)) {
            status = "请求 USB 权限…"
            requestPermissionThrottled(dev)
            return
        }
        lifecycleScope.launch {
            val ok = usbMutex.withLock {
                withContext(Dispatchers.IO) { usb.open(dev) }
            }
            status = if (ok) "已连接: ${dev.productName ?: dev.deviceName}" else "打开失败（设备可能被占用）"
            if (ok) applyPersistedDeviceSettings()
        }
    }

    private fun requestPermissionThrottled(dev: UsbDevice) {
        val now = System.currentTimeMillis()
        if (now - lastPermissionRequestAt < 5000) return
        lastPermissionRequestAt = now
        usb.requestPermission(dev)
    }

    /** 设过了自动亮度 / HID 的话，连接成功后恢复 */
    private fun applyPersistedDeviceSettings() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val c = usb.connection ?: return@withContext
                usb.ensureHid()
                val ab = autoBrightness
                LcdControl.setAutoBrightness(c, ab)
            }
            if (prefs.hidEnabled && hidSensor?.running != true) startHidSensor()
        }
    }

    /** 后台重连：设备在但没打开时按指数退避重试（500ms → 15s 上限） */
    private fun startAutoReconnectLoop() {
        lifecycleScope.launch {
            var attempt = 0
            while (isActive) {
                delay(1000)
                if (!autoReconnect || !prefs.autoReconnect || reconnectPaused) {
                    attempt = 0
                    continue
                }
                if (usb.isOpen) {
                    // 系统没发 DETACH 广播（进程被冻/服务抢占）时，用 deviceList 自行校验
                    val d = usb.device
                    if (d != null && usb.findDevice() == null) {
                        usb.close()
                        status = "设备已断开（等待自动重连…）"
                    }
                    attempt = 0
                    continue
                }
                val dev = usb.findDevice()
                if (dev == null) {
                    attempt = 0
                    continue
                }
                if (!usb.hasPermission(dev)) {
                    status = "重连: 请求 USB 权限…"
                    requestPermissionThrottled(dev)
                    attempt++
                    delay(backoffMs(attempt))
                    continue
                }
                val ok = usbMutex.withLock {
                    withContext(Dispatchers.IO) { usb.open(dev) }
                }
                if (ok) {
                    attempt = 0
                    status = "已自动重连: %s".format(dev.productName ?: dev.deviceName)
                    applyPersistedDeviceSettings()
                } else {
                    attempt++
                    val wait = backoffMs(attempt)
                    status = "重连失败（第 $attempt 次），%.1fs 后重试".format(wait / 1000f)
                    delay(wait)
                }
            }
        }
    }

    /** 指数退避：500ms、1s、2s、4s、8s…，上限 15s */
    private fun backoffMs(attempt: Int): Long {
        val exp = (attempt - 1).coerceIn(0, 6)
        return (500L shl exp).coerceAtMost(15_000L)
    }

    private suspend fun tryOpenFound() {
        val dev = usb.findDevice() ?: return
        if (!usb.hasPermission(dev)) return
        val ok = usbMutex.withLock { withContext(Dispatchers.IO) { usb.open(dev) } }
        if (ok) {
            status = "已连接: ${dev.productName ?: dev.deviceName}"
            applyPersistedDeviceSettings()
        }
    }

    // ==================================================================
    // LCD / EEPROM
    // ==================================================================

    private fun readStatusFields() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val st = withContext(Dispatchers.IO) {
                val c = usb.connection ?: return@withContext null
                LcdControl.readStatus(c)
            }
            busy = false
            if (st != null) {
                statusFields = st.describe()
                status = "读状态成功"
                brightness = st.luminance.coerceIn(100, 254)
            } else {
                status = "读状态失败（未连接？）"
            }
        }
    }

    private fun readProductInfo() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val pi = withContext(Dispatchers.IO) {
                val c = usb.connection ?: return@withContext null
                LcdControl.readProductInfo(c)
            }
            busy = false
            if (pi == null) {
                status = "读产品信息失败（0xA1/0x07 无响应）"
            } else {
                productInfo = pi
                productDialog = true
                status = "产品信息: " + pi.summary()
            }
        }
    }

    private fun readFirmwareVersion() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val v = withContext(Dispatchers.IO) {
                val c = usb.connection ?: return@withContext null
                // HID 接口的 claim 是"按 fd"的：UVC 抢走后要重新 ensureHid()
                usb.ensureHid()
                LcdControl.getFirmwareVersion(c)
            }
            busy = false
            status = if (v.isNullOrBlank()) "读固件版本失败（HID 接口被占用？）" else "真实固件版本: $v"
        }
    }

    private fun openScreen() {
        lifecycleScope.launch {
            val (sw, md) = withContext(Dispatchers.IO) {
                val c = usb.connection ?: return@withContext false to false
                // 原厂 LCDService.c()：屏没开就先 setSwitch(true)，再切 displayMode
                val s = LcdControl.setScreenOn(c, true)
                val m = LcdControl.setDisplayMode(c, 2)
                s to m
            }
            status = "开屏: switch=$sw mode2=$md"
            readStatusFields()
        }
    }

    private fun changeBrightness(v: Int) {
        brightness = v.coerceIn(100, 254)
        val b = brightness
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val c = usb.connection ?: return@withContext false
                LcdControl.setBrightness(c, b)
            }
            status = "亮度 = $b" + if (ok) "" else "（未连接/未 ACK）"
        }
    }

    private fun setAutoBrightnessByUser(on: Boolean) {
        autoBrightness = on
        prefs.autoBrightness = on
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val c = usb.connection ?: return@withContext false
                LcdControl.setAutoBrightness(c, on)
            }
            status = "自动亮度 = " + (if (on) "开" else "关") + if (ok) "" else "（未连接/未 ACK）"
        }
    }

    // ==================================================================
    // 投屏 / 画面调整
    // ==================================================================

    private fun stopCastService() {
        startService(
            Intent(this, com.wzqvip.glxsscast.cast.CastService::class.java)
                .setAction(com.wzqvip.glxsscast.cast.CastService.ACTION_STOP)
        )
    }

    /**
     * 画面调整实时生效：写 CastService 静态字段（下一帧映射就用新值，不重启投屏），
     * 同时把数值持久化到 SharedPreferences（键 zoom/ox/oy）。
     */
    private fun applyAdjust(z: Float, ox: Float, oy: Float, persist: Boolean = true) {
        val zz = z.coerceIn(1f, 2f)
        val xx = ox.coerceIn(-1f, 1f)
        val yy = oy.coerceIn(-1f, 1f)
        zoom = zz
        offsetX = xx
        offsetY = yy
        val cs = com.wzqvip.glxsscast.cast.CastService
        cs.zoom = zz
        cs.offsetX = xx
        cs.offsetY = yy
        if (persist) {
            prefs.zoom = zz
            prefs.offsetX = xx
            prefs.offsetY = yy
        }
    }

    private fun resyncOrientation() {
        startService(
            Intent(this, com.wzqvip.glxsscast.cast.CastService::class.java)
                .setAction(com.wzqvip.glxsscast.cast.CastService.ACTION_RESYNC)
        )
        status = "已请求重新适配屏幕方向"
    }

    private fun cycleFillMode() {
        fillMode = (fillMode + 1) % 3
        com.wzqvip.glxsscast.cast.CastService.fillMode = fillMode
        prefs.fillMode = fillMode
        status = "画面模式: " + com.wzqvip.glxsscast.cast.CastService.modeName(fillMode)
    }

    private fun sendAsync(payload: ByteArray) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                // 发测试图前先停止投屏，避免画面被投屏覆盖
                if (com.wzqvip.glxsscast.cast.CastService.running) {
                    stopCastService()
                    Thread.sleep(400)
                }
                val c = usb.connection
                var screenOn = false
                var modeOk = false
                if (c != null) {
                    // 停止投屏会把显示模式切回 1（关屏）——先切回 2（投屏）
                    modeOk = LcdControl.setDisplayMode(c, 2)
                    // 原厂 App 停止投屏后会 0xA1/0x03 关屏；关屏状态下只发帧是看不到的，必须显式开屏
                    screenOn = LcdControl.setScreenOn(c, true)
                }
                val sent = usb.sendFrame(LcdProtocol.frame(payload))
                Triple(sent, screenOn, modeOk)
            }
            val sent = r.first
            val screenOn = r.second
            val modeOk = r.third
            busy = false
            if (sent) {
                frames++
                val warn = when {
                    !modeOk && !screenOn -> "（警告：开屏/显示模式都没 ACK）"
                    !screenOn -> "（警告：开屏命令没 ACK）"
                    !modeOk -> "（警告：显示模式没 ACK）"
                    else -> ""
                }
                status = "已发送 $frames 帧（已停止投屏）$warn"
            } else {
                status = if (usb.isOpen) "发送失败（bulk OUT 没有写完）"
                else "发送失败（未连接眼镜）"
            }
        }
    }

    // ==================================================================
    // UVC
    // ==================================================================

    /** 枚举设备描述符里的档位 → 按保存的偏好选档 → 开始取流 */
    private fun startUvc(cam: UvcCamera) {
        val dev = cam.findDevice()
        if (dev == null) {
            status = "未找到眼镜"
            return
        }
        if (!cam.hasPermission(dev)) {
            status = "请求设备权限…"
            requestPermissionThrottled(dev)
            return
        }
        status = "枚举 UVC 档位…"
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                // 单 owner：UVC 复用 UsbLink 的连接（不再 open(dev, null) 拿第二个 fd）
                if (!usb.isOpen) {
                    val opened = usbMutex.withLock { usb.open(dev) }
                    if (!opened) {
                        return@withContext UvcOpenResult(
                            emptyList(), null, "USB 打开失败（${cam.lastError() ?: "设备被占用"}）"
                        )
                    }
                }
                if (!cam.prepare(dev, usb.connection)) {
                    return@withContext UvcOpenResult(emptyList(), null, "打开失败: ${cam.lastError()}")
                }
                val opts = cam.options()
                val want = opts.firstOrNull {
                    prefs.hasUvcSelection && it.matches(prefs.uvcFormat, prefs.uvcWidth, prefs.uvcHeight)
                }
                if (!cam.select(want)) {
                    return@withContext UvcOpenResult(opts, null, "协商失败: ${cam.lastError()}")
                }
                cam.start()
                UvcOpenResult(opts, cam.currentOption(), "摄像头: ${cam.negotiatedInfo()}")
            }
            uvcOptions = res.options
            uvcSelected = res.selected
            res.selected?.let { prefs.saveUvc(it.formatName, it.width, it.height) }
            status = res.status
        }
    }

    /** 下拉换档位 → 重启 UVC 流并持久化 */
    private fun selectUvc(cam: UvcCamera, opt: UvcCamera.StreamOption) {
        uvcMenuOpen = false
        status = "切换档位: ${opt.label} …"
        lifecycleScope.launch {
            val res: Pair<UvcCamera.StreamOption?, String> = withContext(Dispatchers.IO) {
                if (cam.restartStream(opt)) {
                    cam.currentOption() to "摄像头: ${cam.negotiatedInfo()}"
                } else {
                    null to "切换失败: ${cam.lastError()}"
                }
            }
            val sel = res.first
            if (sel != null) {
                uvcSelected = sel
                prefs.saveUvc(sel.formatName, sel.width, sel.height)
            }
            status = res.second
        }
    }

    // ==================================================================
    // HID 传感器 / Fn 按键
    // ==================================================================

    private fun startHidSensor() {
        if (!usb.isOpen) {
            status = "先连接眼镜再启动 HID"
            return
        }
        val s = hidSensor ?: HidSensor(usb).also { sensor ->
            hidSensor = sensor
            sensor.onReport = { r ->
                runOnUiThread {
                    applySensorReport(r)
                    hidInfo = r.sensorLine(accFsr, gyroFsr)
                    hidHex = r.hex()
                }
            }
            sensor.onEvent = { e ->
                runOnUiThread { handleHidEvent(e) }
            }
        }
        s.accFsr = accFsr
        s.gyroFsr = gyroFsr
        if (s.start(hidMode)) {
            prefs.hidEnabled = true
            prefs.hidMode = if (s.mode == HidSensor.Mode.POLL) "poll" else "interrupt"
            hidOn = true
            // 启动即把当前姿态当作水平零点
            zeroPending = true
            zeroSamples.clear()
            status = "HID 传感器已启动（${s.mode.label}，300ms）"
            readImuAttributes()
        } else {
            status = "HID 启动失败: ${s.lastError}"
        }
    }

    private fun stopHidSensor() {
        hidSensor?.stop()
        prefs.hidEnabled = false
        hidOn = false
        status = "HID 已停止"
    }

    /** 按键事件（已在主线程）：按配置执行动作 */
    private fun handleHidEvent(e: HidSensor.Event) {
        val action = when (e) {
            is HidSensor.Event.Click -> hidClickAction
            is HidSensor.Event.LongPress -> hidLongAction
        }
        Oplog.logf("HID", "button %s → %s", e.javaClass.simpleName, action.id)
        when (action) {
            HidAction.NONE -> status = "Fn 按键（未绑定动作）"
            HidAction.SNAPSHOT -> lifecycleScope.launch {
                val f = withContext(Dispatchers.IO) { activeUvc?.saveSnapshot() }
                status = if (f != null) "Fn 单击：已拍照 ${f.absolutePath}"
                else "Fn 单击：拍照失败（UVC 没有帧 / 未打开摄像头）"
            }
            HidAction.CAST_TOGGLE -> {
                if (com.wzqvip.glxsscast.cast.CastService.running) {
                    stopCastService()
                    status = "Fn 长按：停止投屏"
                } else if (!usb.isOpen) {
                    status = "Fn 长按：未连接眼镜，无法投屏"
                } else {
                    status = "Fn 长按：请求投屏授权…"
                    runCatching { mpLauncher.launch(projectionManager.createScreenCaptureIntent()) }
                        .onFailure { status = "投屏授权启动失败: ${it.message}" }
                }
            }
            HidAction.RECORD_TOGGLE -> {
                if (recorder.isRecording) stopRecording() else startRecording()
            }
        }
    }

    private fun cycleHidClickAction() {
        hidClickAction = hidClickAction.next()
        prefs.hidClickAction = hidClickAction.id
        status = "Fn 单击 = ${hidClickAction.label}"
    }

    private fun cycleHidLongAction() {
        hidLongAction = hidLongAction.next()
        prefs.hidLongAction = hidLongAction.id
        status = "Fn 长按 = ${hidLongAction.label}"
    }

    private fun cycleHidMode() {
        val next = if (hidMode == HidSensor.Mode.POLL) HidSensor.Mode.INTERRUPT else HidSensor.Mode.POLL
        val wasRunning = hidSensor?.running == true
        if (wasRunning) stopHidSensor()
        hidMode = next
        prefs.hidMode = if (next == HidSensor.Mode.POLL) "poll" else "interrupt"
        if (wasRunning) startHidSensor()
        status = "HID 读法 = ${next.label}"
    }

    // ---------------- 量程 / 姿态可视化 ----------------

    /**
     * 把 28 B 报告换算成 UI 姿态：acc 减去「水平零点」，pitch/roll 用 docs 约定
     * （`pitch=atan2(x,hypot(y,z))`、`roll=atan2(z,y)`）算绝对角后减去零点角，
     * 气泡偏移 = 校准后 acc / |acc|（|acc| 用未校准的重力模长，静止时 ≈1g，避免零点附近抖动）。
     */
    private fun applySensorReport(r: HidSensor.Report) {
        val raw = r.acc
        lastRawAcc = raw.copyOf()
        if (zeroPending) {
            zeroSamples.add(raw.copyOf())
            if (zeroSamples.size >= 5) {
                zeroRaw = IntArray(3) { axis -> medianOf(zeroSamples.map { it[axis] }) }
                zeroSamples.clear()
                zeroPending = false
                val z = zeroRaw!!
                Oplog.logf("HID", "水平零点(5帧中位数)=raw(%d,%d,%d)", z[0], z[1], z[2])
            }
        }
        val zero = zeroRaw ?: return
        val aScale = HidSensor.accScale(accFsr)
        val gScale = HidSensor.gyroScale(gyroFsr)
        val dx = ((raw[0] - zero[0]) * aScale).toFloat()
        val dy = ((raw[1] - zero[1]) * aScale).toFloat()
        val dz = ((raw[2] - zero[2]) * aScale).toFloat()
        val rawMag = kotlin.math.sqrt(
            raw[0].toDouble() * raw[0] + raw[1].toDouble() * raw[1] + raw[2].toDouble() * raw[2]
        ).toFloat()
        val mag = (rawMag * aScale).toFloat()
        val denom = mag.coerceAtLeast(0.05f)
        // 绝对 pitch/roll 相减：零点处严格为 0，避免 atan2(0,0) 在噪声下乱跳
        val pitch = (pitchOf(raw) - pitchOf(zero)).toFloat()
        val roll = wrapDegrees(rollOf(raw) - rollOf(zero)).toFloat()
        attitude = Attitude(
            acc = Vec3(dx, dy, dz),
            gyro = Vec3(
                (r.gyro[0] * gScale).toFloat(),
                (r.gyro[1] * gScale).toFloat(),
                (r.gyro[2] * gScale).toFloat()
            ),
            accMagG = mag,
            accRawMag = rawMag,
            pitch = pitch,
            roll = roll,
            bubbleX = (dx / denom).coerceIn(-1f, 1f),
            bubbleY = (dy / denom).coerceIn(-1f, 1f)
        )
    }

    /** docs 约定：抬头为 +；pitch = atan2(x, hypot(y,z)) */
    private fun pitchOf(acc: IntArray): Double = Math.toDegrees(
        kotlin.math.atan2(acc[0].toDouble(), kotlin.math.hypot(acc[1].toDouble(), acc[2].toDouble()))
    )

    /** docs 约定：roll = atan2(z, y) */
    private fun rollOf(acc: IntArray): Double =
        Math.toDegrees(kotlin.math.atan2(acc[2].toDouble(), acc[1].toDouble()))

    private fun wrapDegrees(v: Double): Double = ((v + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    private fun medianOf(values: List<Int>): Int {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2]
        else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** 「水平校准」：以当前姿态为零点 */
    private fun calibrateLevel() {
        val raw = lastRawAcc
        if (raw == null) {
            status = "还没有 HID 数据（先启动 HID 传感器）"
            return
        }
        zeroRaw = raw.copyOf()
        zeroPending = false
        zeroSamples.clear()
        status = "水平校准：零点 raw=(%d, %d, %d)".format(raw[0], raw[1], raw[2])
        Oplog.logf("HID", "水平校准 raw=(%d,%d,%d)", raw[0], raw[1], raw[2])
        hidSensor?.report?.let { applySensorReport(it) }
    }

    /**
     * 「自动估计量程」：采样 2 s（300ms 轮询 → 约 6–7 个新报告），
     * 用 |acc| 中位数反推 FSR（静止时 |acc|=1g → raw≈32768/FSR），取最接近档位并套用。
     */
    private fun estimateAccRange() {
        if (hidSensor?.running != true) {
            status = "先启动 HID 传感器再估计量程"
            return
        }
        if (fsrEstimating) return
        fsrEstimating = true
        fsrEstimate = "量程估计：采样 2 秒…（请把眼镜平放静止）"
        lifecycleScope.launch {
            val mags = ArrayList<Double>()
            var lastPoll = -1L
            val t0 = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < 2000) {
                val s = hidSensor
                val rep = s?.report
                val pc = s?.pollCount ?: 0L
                if (rep != null && pc != lastPoll) {
                    lastPoll = pc
                    val a = rep.acc
                    val m = kotlin.math.sqrt(
                        a[0].toDouble() * a[0] + a[1].toDouble() * a[1] + a[2].toDouble() * a[2]
                    )
                    if (m > 0.0) mags.add(m)
                }
                delay(100)
            }
            fsrEstimating = false
            val est = HidSensor.estimateAccRange(mags)
            if (est == null) {
                fsrEstimate = "量程估计失败：样本不足或 acc 全 0（先点「使能传感器」）"
                status = "量程估计失败（acc 全 0？先使能传感器）"
                return@launch
            }
            selectAccFsr(est.nearestIndex, manual = false)
            fsrEstimate = String.format(
                Locale.US,
                "估计 FSR≈%.1fg（最接近 ±%dg）· 原始 |acc| 中位数≈%.0f counts · 样本 %d · 已套用",
                est.fsrG, est.nearestG, est.medianRaw, est.sampleCount
            )
            status = String.format(
                Locale.US, "量程估计：FSR≈%.1fg → 选 ±%dg（raw |acc|≈%.0f）",
                est.fsrG, est.nearestG, est.medianRaw
            )
            Oplog.logf(
                "HID", "estimate acc: fsr=%.2fg medianRaw=%.1f samples=%d -> ±%dg",
                est.fsrG, est.medianRaw, est.sampleCount, est.nearestG
            )
        }
    }

    private fun selectAccFsr(index: Int, manual: Boolean = true) {
        accFsr = index.coerceIn(0, 3)
        prefs.accFsr = accFsr
        prefs.fsrInitialized = true
        hidSensor?.accFsr = accFsr
        hidSensor?.report?.let { hidInfo = it.sensorLine(accFsr, gyroFsr) }
        if (manual) {
            fsrEstimate = "量程：acc ±${HidSensor.ACC_FSR_G[accFsr]}g（手动）"
            Oplog.logf("HID", "手动 acc FSR = ±%dg", HidSensor.ACC_FSR_G[accFsr])
        }
    }

    private fun selectGyroFsr(index: Int) {
        gyroFsr = index.coerceIn(0, 3)
        prefs.gyroFsr = gyroFsr
        prefs.fsrInitialized = true
        hidSensor?.gyroFsr = gyroFsr
        hidSensor?.report?.let { hidInfo = it.sensorLine(accFsr, gyroFsr) }
        Oplog.logf("HID", "手动 gyro FSR = ±%d°/s", HidSensor.GYRO_FSR_DPS[gyroFsr])
    }

    /** 读 `0x06` 的 IMU 属性：拿硬件真实 acc_fsr / gyro_fsr；首次连接用它初始化量程 */
    private fun readImuAttributes() {
        lifecycleScope.launch {
            val attr = withContext(Dispatchers.IO) {
                val c = usb.connection ?: return@withContext null
                LcdControl.readImuAttributes(c)
            }
            if (attr == null) {
                hwFsrLine = "硬件 FSR：读取失败（0x06；可手动选量程）"
                return@launch
            }
            hwFsrLine = if (attr.fsrValid) {
                "硬件 FSR：acc_fsr=${attr.accFsr}（±${HidSensor.ACC_FSR_G[attr.accFsr]}g）· " +
                    "gyro_fsr=${attr.gyroFsr}（±${HidSensor.GYRO_FSR_DPS[attr.gyroFsr]}°/s）· " +
                    "chip=0x%016x".format(Locale.US, attr.chipId)
            } else {
                "硬件 FSR：acc_fsr=${attr.accFsr} / gyro_fsr=${attr.gyroFsr} 超出 0..3，" +
                    "保留当前选择 · raw=${attr.hex()}"
            }
            Oplog.logf("HID", "0x06 %s", hwFsrLine)
            if (!prefs.fsrInitialized && attr.fsrValid) {
                accFsr = attr.accFsr
                gyroFsr = attr.gyroFsr
                prefs.accFsr = accFsr
                prefs.gyroFsr = gyroFsr
                prefs.fsrInitialized = true
                hidSensor?.accFsr = accFsr
                hidSensor?.gyroFsr = gyroFsr
                hidSensor?.report?.let { hidInfo = it.sensorLine(accFsr, gyroFsr) }
                status = "传感器量程按硬件 0x06 初始化：acc ±${HidSensor.ACC_FSR_G[accFsr]}g / " +
                    "gyro ±${HidSensor.GYRO_FSR_DPS[gyroFsr]}°/s"
                Oplog.logf(
                    "HID", "量程初始化自硬件: acc=±%dg gyro=±%d°/s",
                    HidSensor.ACC_FSR_G[accFsr], HidSensor.GYRO_FSR_DPS[gyroFsr]
                )
            }
        }
    }

    // ==================================================================
    // 录像
    // ==================================================================

    /** 录像分辨率：投屏固定 640×400（LCD 输出同一帧）；UVC 用当前出帧尺寸 */
    private fun recordSize(src: RecordSource): Pair<Int, Int> {
        if (src == RecordSource.CAST) {
            return LcdProtocol.WIDTH to LcdProtocol.HEIGHT
        }
        val sz = activeUvc?.currentSize() ?: (640 to 480)
        // H.264 要求偶数
        return (sz.first / 2 * 2).coerceAtLeast(16) to (sz.second / 2 * 2).coerceAtLeast(16)
    }

    private fun startRecording() {
        if (recorder.isRecording) return
        val src = recSource
        val (w, h) = recordSize(src)
        if (src == RecordSource.CAST && !com.wzqvip.glxsscast.cast.CastService.running) {
            status = "投屏未运行：录像会开始但没有帧（先「开始投屏」）"
        }
        if (src == RecordSource.UVC && activeUvc?.isRunning != true) {
            status = "UVC 未出流：录像会开始但没有帧（先「打开摄像头 / 枚举启动」）"
        }
        val err = recorder.start(src, w, h)
        if (err != null) {
            recInfo = "录像启动失败: $err"
            status = "录像启动失败: $err"
            return
        }
        if (src == RecordSource.CAST) {
            com.wzqvip.glxsscast.cast.CastService.frameTap = recorder
        } else {
            activeUvc?.frameTap = recorder
        }
        recPath = recorder.outputFile?.absolutePath ?: ""
        recInfo = "● REC 0.0s"
        status = "开始录像：${src.label} ${w}×$h"
    }

    private fun stopRecording() {
        if (com.wzqvip.glxsscast.cast.CastService.frameTap === recorder) {
            com.wzqvip.glxsscast.cast.CastService.frameTap = null
        }
        activeUvc?.let { if (it.frameTap === recorder) it.frameTap = null }
        recorder.stop()
        val f = recorder.outputFile
        val size = f?.length() ?: 0L
        recPath = f?.absolutePath ?: ""
        recInfo = if (size > 0) {
            String.format(
                Locale.US, "已停止: %d 帧 · %.1fs · %.1f fps · %.2f MB",
                recorder.framesWritten, recorder.videoDurationUs / 1e6,
                recorder.encodedFps, size / 1048576.0
            )
        } else {
            "未产生文件: " + recorder.lastError.ifEmpty { "没有帧" }
        }
        status = if (size > 0) "录像已保存：$recPath（${size / 1024} KB）" else recInfo
    }

    /** 录像状态栏文字（UI 每 300ms 轮询） */
    private fun recordStatusLine(): String {
        if (!recorder.isRecording) return recInfo
        return String.format(
            Locale.US, "● REC %.1fs · %.1f fps · %d kbps · %.2f MB · 丢 %d",
            recorder.elapsedMs / 1000.0, recorder.encodedFps, recorder.bitrateKbps,
            recorder.bytesWritten / 1048576.0, recorder.framesDropped
        )
    }

    // ==================================================================
    // 音频增强
    // ==================================================================

    private fun refreshAudioDevices() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                AudioEnh.inputDevices(this@MainActivity).map { it.line() }
            }
            micDevices = list
        }
    }

    private fun runAudioProbe() {
        status = "音频实测中（1 秒，强制 USB 麦克风）…"
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { AudioEnh.probe(this@MainActivity, 1.0) }
            audioInfo = p.line() + if (p.usbFound) "" else " · 没有 USB 输入设备"
            status = if (p.ok) "音频实测完成" else "音频实测失败"
            refreshAudioDevices()
        }
    }

    private fun runDuplexTest() {
        status = "双向通话测试中（2 秒：播放 440Hz + 录音）…"
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { AudioEnh.duplex(this@MainActivity, 2.0) }
            audioInfo = if (!p.ok) {
                "双向测试失败: ${p.error}"
            } else {
                String.format(
                    Locale.US,
                    "双向通话 OK：扬声器播放 440Hz %dms + 麦克风（%s）录到 RMS %d / 峰值 %.1f dBFS · 采样率 %d Hz",
                    p.tonePlayedMs, p.deviceName, p.rms, p.peakDbfs, p.sampleRate
                )
            }
            status = if (p.ok) "双向通话测试完成" else "双向通话测试失败"
            refreshAudioDevices()
        }
    }

    // ==================================================================
    // 三重并发压力测试（投屏 + UVC + 录像 30s）
    // ==================================================================

    private fun startStress() {
        if (stressRunning) {
            stressCancelled = true
            stressJob?.cancel()
            return
        }
        val castOn = com.wzqvip.glxsscast.cast.CastService.running
        val cam = activeUvc
        val uvcOn = cam?.isRunning == true
        val autoRec = !recorder.isRecording
        if (autoRec) {
            recSource = if (castOn) RecordSource.CAST else RecordSource.UVC
            startRecording()
            if (!recorder.isRecording) {
                stressInfo = "压力测试未启动：" + recorder.lastError.ifEmpty { "录像启动失败" }
                return
            }
        }
        stressCancelled = false
        stressRunning = true
        stressInfo = "压力测试中… 投屏${if (castOn) "运行" else "未运行"} · " +
            "UVC${if (uvcOn) "运行" else "无流"} · 录像中"
        val rec0 = recorder.framesWritten
        val uvc0 = cam?.frameCount() ?: 0L
        val cores = CpuSampler.cores()
        val cpu0 = CpuSampler.cpuMs()
        stressJob = lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            var castSum = 0.0
            var castN = 0
            var uvcSum = 0.0
            var uvcN = 0
            var lastRec = rec0
            val rows = StringBuilder()
            try {
                for (i in 1..30) {
                    delay(1000)
                    val now = System.currentTimeMillis()
                    val cf = com.wzqvip.glxsscast.cast.CastService.fps
                    val uf = cam?.currentFps() ?: 0f
                    castSum += cf
                    castN++
                    uvcSum += uf
                    uvcN++
                    val dWall = (now - t0).coerceAtLeast(1)
                    val cpuPct = (CpuSampler.cpuMs() - cpu0) * 100.0 / (dWall * cores)
                    val dRec = recorder.framesWritten - lastRec
                    lastRec = recorder.framesWritten
                    rows.append(
                        String.format(
                            Locale.US, "%02ds cast=%.1ffps uvc=%.1ffps rec=+%d cpu=%.0f%%\n",
                            i, cf, uf, dRec, cpuPct
                        )
                    )
                    stressInfo = String.format(
                        Locale.US,
                        "压力测试 %2ds/30s · 投屏 %.1f fps · UVC %.1f fps · 录像 %d 帧 · CPU %.0f%%",
                        i, cf, uf, recorder.framesWritten - rec0, cpuPct
                    )
                    android.util.Log.i("Stress", rows.lineSequence().last())
                    if (!isActive) break
                }
            } finally {
                if (autoRec) {
                    runCatching { stopRecording() }
                }
                if (stressCancelled) {
                    stressInfo = "压力测试已取消（已录帧数 ${recorder.framesWritten - rec0}）"
                } else {
                    val wall = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                    val cpuMs = CpuSampler.cpuMs() - cpu0
                    val cpuPct = cpuMs * 100.0 / (wall * cores)
                    val cpuTotalPct = cpuMs * 100.0 / wall
                    val recFrames = recorder.framesWritten - rec0
                    val size = recorder.outputFile?.length() ?: 0L
                    val durS = recorder.videoDurationUs / 1e6
                    val avgCast = if (castN > 0) castSum / castN else 0.0
                    val avgUvc = if (uvcN > 0) uvcSum / uvcN else 0.0
                    val uvcFrames = (cam?.frameCount() ?: 0L) - uvc0
                    val lines = String.format(
                        Locale.US,
                        "三重并发 %.1fs（%d 核）\n" +
                            "投屏: %.1f fps（%s）\n" +
                            "UVC: %.1f fps（%s，累计 +%d 帧）\n" +
                            "录像: %d 帧 / %.1fs / %.1f fps / %.2f MB\n" +
                            "CPU: %.0f%%（%d 核归一）· %.0f%% 累计",
                        wall / 1000.0, cores,
                        avgCast, if (castOn) "运行" else "未运行",
                        avgUvc, if (uvcOn) "运行" else "无流", uvcFrames,
                        recFrames, durS, recorder.encodedFps, size / 1048576.0,
                        cpuPct, cores, cpuTotalPct
                    ) + if (recorder.lastError.isNotEmpty()) "\n备注: " + recorder.lastError else ""
                    stressInfo = lines
                    android.util.Log.i("Stress", "=== triple concurrency 30s ===\n$rows$lines")
                    // 落盘一份，adb pull 取证用
                    runCatching {
                        val dir = getExternalFilesDir(null) ?: filesDir
                        java.io.File(dir, "stress_last.txt")
                            .writeText(rows.toString() + lines + "\n")
                    }
                }
                stressRunning = false
                stressJob = null
            }
        }
    }


    // ==================================================================
    // UI
    // ==================================================================

    /**
     * 水平仪气泡（演示用，无姿态融合）：
     * 圆框 + 十字 + 中间小球；小球偏移 = 校准后 acc / |acc|（已减「水平零点」）。
     * 另画一条橙色箭头表示 gyro x/y 方向的角速度（长度 ∝ |gyro| / 满量程）。
     */
    @Composable
    private fun LevelBubble(att: Attitude, gyroFullScaleDps: Int, modifier: Modifier = Modifier) {
        val bg = Color(0xFF211A35)
        val grid = Color(0xFF4A3F73)
        val ring = Color(0xFF8B78E6)
        val ball = Color(0xFF00E5A0)
        val gyroColor = Color(0xFFFFB74D)
        Canvas(modifier) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = minOf(size.width, size.height) / 2f - 4.dp.toPx()
            drawCircle(bg, radius = r, center = Offset(cx, cy))
            drawCircle(ring, radius = r, center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
            drawCircle(grid, radius = r * 0.66f, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
            drawCircle(grid, radius = r * 0.33f, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
            drawLine(grid, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = 1.dp.toPx())
            drawLine(grid, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = 1.dp.toPx())
            // 角速度指示：gyro x/y 平面，静止时几乎不显示（不做姿态融合）
            val gMag = kotlin.math.sqrt(att.gyro.x * att.gyro.x + att.gyro.y * att.gyro.y)
            if (gMag > 0.5f && gyroFullScaleDps > 0) {
                val k = (gMag / gyroFullScaleDps).coerceIn(0f, 1f)
                val ux = att.gyro.x / gMag
                val uy = att.gyro.y / gMag
                val ex = cx + ux * r * 0.9f * k
                val ey = cy + uy * r * 0.9f * k
                drawLine(gyroColor, Offset(cx, cy), Offset(ex, ey), strokeWidth = 2.dp.toPx())
                drawCircle(gyroColor, radius = 3.dp.toPx(), center = Offset(ex, ey))
            }
            // 气泡：偏移钳在 ±1，最大贴到外圈
            val bx = cx + att.bubbleX * r * 0.82f
            val by = cy + att.bubbleY * r * 0.82f
            drawCircle(ball.copy(alpha = 0.18f), radius = 15.dp.toPx(), center = Offset(bx, by))
            drawCircle(ball, radius = 9.dp.toPx(), center = Offset(bx, by))
            drawCircle(
                Color.White, radius = 9.dp.toPx(), center = Offset(bx, by),
                style = Stroke(1.dp.toPx())
            )
        }
    }

    @Composable
    fun App() {
        // 投屏时保持屏幕常亮（Activity 在前台时生效；停止投屏立刻清掉）
        androidx.compose.runtime.LaunchedEffect(Unit) {
            var keepOn = false
            while (true) {
                val on = com.wzqvip.glxsscast.cast.CastService.running
                if (on != keepOn) {
                    keepOn = on
                    if (on) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                delay(500)
            }
        }

        MaterialTheme {
            if (productDialog && productInfo != null) {
                val pi = productInfo!!
                AlertDialog(
                    onDismissRequest = { productDialog = false },
                    title = { Text("EEPROM 产品信息") },
                    text = {
                        Column(
                            Modifier
                                .heightIn(max = 460.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                pi.detail(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { productDialog = false }) { Text("关闭") }
                    }
                )
            }

            if (crashDialog) {
                AlertDialog(
                    onDismissRequest = { crashDialog = false },
                    title = { Text("崩溃现场 crash_last.txt") },
                    text = {
                        Column(
                            Modifier
                                .heightIn(max = 460.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                crashText,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { crashDialog = false }) { Text("关闭") }
                    }
                )
            }

            Scaffold(
                topBar = { TopAppBar(title = { Text("GLXSS3 Cast 0.1.4 Dev") }) }
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
                    if (statusFields.isNotEmpty()) {
                        Text(
                            statusFields,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    HorizontalDivider()
                    Text("连接", style = MaterialTheme.typography.titleSmall)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val dev = usb.findDevice()
                                if (dev == null) status = "未找到眼镜（VID 0x2E09）" else connect(dev)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("连接眼镜") }
                        Button(
                            onClick = {
                                usb.close()
                                reconnectPaused = true
                                status = "已断开（自动重连暂停；插拔或点「连接眼镜」恢复）"
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("断开") }


                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text("拔插自动重连", modifier = Modifier.weight(1f))
                        Switch(
                            checked = autoReconnect,
                            onCheckedChange = {
                                autoReconnect = it
                                prefs.autoReconnect = it
                                status = if (it) "自动重连已开启" else "自动重连已关闭"
                            }
                        )
                    }

                    HorizontalDivider()
                    Text("UVC 摄像头", style = MaterialTheme.typography.titleSmall)
                    Button(
                        onClick = {
                            showUvc = !showUvc
                            if (!showUvc) {
                                uvcOptions = emptyList()
                                uvcSelected = null
                                uvcErr = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (showUvc) "收起摄像头" else "打开摄像头（UVC）") }

                    if (showUvc) {
                        val cam = com.wzqvip.glxsscast.uvc.rememberUvcCamera()
                        // 录像 / 压力测试要直接挂到这台相机上（预览监听器不受影响）
                        DisposableEffect(cam) {
                            activeUvc = cam
                            onDispose { if (activeUvc === cam) activeUvc = null }
                        }
                        Column(Modifier.fillMaxWidth().padding(4.dp)) {
                            com.wzqvip.glxsscast.uvc.UvcPreview(
                                cam,
                                Modifier.fillMaxWidth().height(300.dp),
                                onError = { msg: String -> uvcErr = msg }
                            )
                            if (uvcErr.isNotEmpty()) {
                                Text(
                                    "⚠ " + uvcErr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                com.wzqvip.glxsscast.uvc.UvcCaptureButton(cam, Modifier.weight(1f)) { f ->
                                    status = "已保存: " + f.absolutePath
                                }
                                Button(
                                    onClick = { startUvc(cam) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("枚举/启动") }
                            }

                            // ---- 分辨率/格式下拉（来自设备描述符） ----
                            val selLabel = uvcSelected?.label
                                ?: if (uvcOptions.isEmpty()) "格式/分辨率：未枚举" else "格式/分辨率：自动"
                            Box {
                                OutlinedButton(
                                    onClick = { uvcMenuOpen = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(selLabel) }
                                DropdownMenu(
                                    expanded = uvcMenuOpen,
                                    onDismissRequest = { uvcMenuOpen = false }
                                ) {
                                    if (uvcOptions.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("先点「枚举/启动」读取设备档位") },
                                            onClick = { uvcMenuOpen = false },
                                            enabled = false
                                        )
                                    }
                                    uvcOptions.forEach { opt ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    opt.label +
                                                        if (opt == uvcSelected) "  ✓" else ""
                                                )
                                            },
                                            onClick = { selectUvc(cam, opt) }
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    val dir = java.io.File(
                                        getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "uvc"
                                    )
                                    val f = java.io.File(
                                        dir,
                                        "uvc_raw_%d.bin".format(System.currentTimeMillis())
                                    )
                                    status = if (cam.startRawDump(f, 30))
                                        "正在抓 30 个 URB → " + f.absolutePath
                                    else "抓包失败（先点「枚举/启动」）"
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("抓原始 UVC 字节（诊断）") }
                            Button(
                                onClick = {
                                    try { cam.stop(); cam.close() } catch (_: Exception) {}
                                    showUvc = false
                                    uvcOptions = emptyList()
                                    uvcSelected = null
                                    status = "摄像头已关闭"
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("关闭摄像头") }
                        }
                    }

                    Text(castInfo, style = MaterialTheme.typography.bodyMedium)

                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        while (true) {
                            castInfo = if (com.wzqvip.glxsscast.cast.CastService.running) {
                                "投屏中: %.1f fps · 抓取 %s · 累计 %d 帧".format(
                                    com.wzqvip.glxsscast.cast.CastService.fps,
                                    com.wzqvip.glxsscast.cast.CastService.captureDesc.ifEmpty { "?" },
                                    com.wzqvip.glxsscast.cast.CastService.frames
                                )
                            } else {
                                "未投屏"
                            }
                            hidOn = hidSensor?.running == true
                            updateCrashInfo()
                            // 通知栏「画面模式」改了服务侧模式后，回 App 时同步显示
                            val svcMode = com.wzqvip.glxsscast.cast.CastService.fillMode
                            if (svcMode != fillMode) fillMode = svcMode
                            delay(500)
                        }
                    }

                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        while (true) {
                            recInfo = recordStatusLine()
                            volume = AudioEnh.volume(this@MainActivity)
                            delay(300)
                        }
                    }

                    HorizontalDivider()
                    Text("投屏", style = MaterialTheme.typography.titleSmall)
                    Button(
                        onClick = { cycleFillMode() },
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

                    // ---- 画面调整（可折叠）：实时微调位置/缩放，值持久化 ----
                    Button(
                        onClick = { adjustOpen = !adjustOpen },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            (if (adjustOpen) "▾ " else "▸ ") +
                                "画面调整（缩放 %.2f×，偏移 %+.2f / %+.2f）"
                                    .format(zoom, offsetX, offsetY)
                        )
                    }

                    if (adjustOpen) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "缩放 %.2f×（1.0 = 原始裁切，2.0 = 放大一倍）".format(zoom),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = zoom,
                                onValueChange = { applyAdjust(it, offsetX, offsetY, persist = false) },
                                onValueChangeFinished = { applyAdjust(zoom, offsetX, offsetY) },
                                valueRange = 1f..2f,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "水平偏移 %+.2f（0 = 居中，向左/右微调）".format(offsetX),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = offsetX,
                                onValueChange = { applyAdjust(zoom, it, offsetY, persist = false) },
                                onValueChangeFinished = { applyAdjust(zoom, offsetX, offsetY) },
                                valueRange = -1f..1f,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "垂直偏移 %+.2f（0 = 居中，向上/下微调）".format(offsetY),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = offsetY,
                                onValueChange = { applyAdjust(zoom, offsetX, it, persist = false) },
                                onValueChangeFinished = { applyAdjust(zoom, offsetX, offsetY) },
                                valueRange = -1f..1f,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { applyAdjust(1f, 0f, 0f) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("重置") }
                                Button(
                                    onClick = { resyncOrientation() },
                                    modifier = Modifier.weight(1f)
                                ) { Text("重新适配方向") }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            stopCastService()
                            status = "已停止投屏"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("停止投屏") }

                    HorizontalDivider()
                    Text("LCD 控制", style = MaterialTheme.typography.titleSmall)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { readStatusFields() },
                            enabled = usb.isOpen && !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("读取状态") }


                        Button(
                            onClick = { readProductInfo() },
                            enabled = usb.isOpen && !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("读产品信息") }
                    }

                    Button(
                        onClick = { openScreen() },
                        enabled = usb.isOpen,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("开屏（switch=1 + 显示模式 2）") }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { changeBrightness(brightness - 20) },
                            enabled = usb.isOpen,
                            modifier = Modifier.weight(1f)
                        ) { Text("亮度 −") }
                        Button(
                            onClick = { changeBrightness(brightness + 20) },
                            enabled = usb.isOpen,
                            modifier = Modifier.weight(1f)
                        ) { Text("亮度 +") }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text("自动亮度", modifier = Modifier.weight(1f))
                        Switch(
                            checked = autoBrightness,
                            onCheckedChange = { setAutoBrightnessByUser(it) }
                        )
                    }

                    Button(
                        onClick = { readFirmwareVersion() },
                        enabled = usb.isOpen && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("读取真实固件版本") }

                    HorizontalDivider()
                    Text("测试图（会先停止投屏）", style = MaterialTheme.typography.titleSmall)
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

                    HorizontalDivider()
                    Text("外设 · HID 传感器 / Fn 按键（28B 报告）", style = MaterialTheme.typography.titleSmall)
                    Button(
                        onClick = { if (hidOn) stopHidSensor() else startHidSensor() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (hidOn)
                                "停止 HID（已读 ${hidSensor?.pollCount ?: 0} 次 / 失败 ${hidSensor?.failCount ?: 0}）"
                            else "启动 HID 传感器（0xA1/0x16 · 300ms）"
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { cycleHidMode() },
                            modifier = Modifier.weight(1f)
                        ) { Text("读法: ${hidMode.label}") }
                        OutlinedButton(
                            onClick = { cycleHidClickAction() },
                            modifier = Modifier.weight(1f)
                        ) { Text("单击: ${hidClickAction.label}") }
                    }
                    OutlinedButton(
                        onClick = { cycleHidLongAction() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("长按: ${hidLongAction.label}") }
                    OutlinedButton(
                        onClick = {
                            lifecycleScope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    val c = usb.connection ?: return@withContext false
                                    // 0x10 光感 / 0x11 罗盘 / 0x12 加速度 / 0x14 陀螺
                                    LcdControl.enableSensors(c)
                                }
                                status = if (ok) "传感器已使能（0x10/0x11/0x12/0x14）"
                                else "传感器使能失败（未连接/未 ACK）"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("使能传感器（让 acc / gyro 非零）") }

                    HorizontalDivider()
                    Text("传感器量程 / 姿态可视化（演示）", style = MaterialTheme.typography.titleSmall)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { accFsrMenu = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("acc ±${HidSensor.ACC_FSR_G[accFsr]}g") }
                            DropdownMenu(
                                expanded = accFsrMenu,
                                onDismissRequest = { accFsrMenu = false }
                            ) {
                                HidSensor.ACC_FSR_G.forEachIndexed { i, g ->
                                    DropdownMenuItem(
                                        text = { Text("±${g}g" + if (i == accFsr) " ✓" else "") },
                                        onClick = { selectAccFsr(i); accFsrMenu = false }
                                    )
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { gyroFsrMenu = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("gyro ±${HidSensor.GYRO_FSR_DPS[gyroFsr]}°/s") }
                            DropdownMenu(
                                expanded = gyroFsrMenu,
                                onDismissRequest = { gyroFsrMenu = false }
                            ) {
                                HidSensor.GYRO_FSR_DPS.forEachIndexed { i, d ->
                                    DropdownMenuItem(
                                        text = { Text("±${d}°/s" + if (i == gyroFsr) " ✓" else "") },
                                        onClick = { selectGyroFsr(i); gyroFsrMenu = false }
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        hwFsrLine,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { estimateAccRange() },
                            enabled = !fsrEstimating,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (fsrEstimating) "采样中…" else "自动估计量程") }
                        OutlinedButton(
                            onClick = { calibrateLevel() },
                            modifier = Modifier.weight(1f)
                        ) { Text("水平校准") }
                    }
                    Text(
                        fsrEstimate,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        LevelBubble(
                            attitude,
                            HidSensor.GYRO_FSR_DPS[gyroFsr],
                            Modifier.size(168.dp)
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                String.format(
                                    Locale.US, "pitch = %.1f°  roll = %.1f°",
                                    attitude.pitch, attitude.roll
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                String.format(
                                    Locale.US, "|acc| = %.2f g  (raw %.0f)",
                                    attitude.accMagG, attitude.accRawMag
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                String.format(
                                    Locale.US, "acc = (%.2f, %.2f, %.2f) g",
                                    attitude.acc.x, attitude.acc.y, attitude.acc.z
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                String.format(
                                    Locale.US, "gyro = (%.0f, %.0f, %.0f) °/s",
                                    attitude.gyro.x, attitude.gyro.y, attitude.gyro.z
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                "acc/角度已减水平零点（打开页面/点校准归零）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        hidInfo,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    if (hidHex.isNotEmpty()) {
                        Text(
                            "raw: $hidHex",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Button(
                        onClick = {
                            lifecycleScope.launch {
                                val r = withContext(Dispatchers.IO) { Peripherals.micRms() }
                                status = if (r < 0) "麦克风不可用" else "麦克风 RMS = " + r
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("麦克风电平测试") }

                    Button(
                        onClick = {
                            lifecycleScope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    Peripherals.playTone(440.0, 1.0)
                                }
                                status = if (ok) "已播放 440Hz（应走眼镜扬声器）" else "播放失败"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("播放测试音（眼镜扬声器）") }

                    HorizontalDivider()
                    Text("录像（H.264 → MP4）", style = MaterialTheme.typography.titleSmall)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { recSource = RecordSource.CAST },
                            enabled = !recorder.isRecording,
                            modifier = Modifier.weight(1f)
                        ) { Text((if (recSource == RecordSource.CAST) "✓ " else "") + "投屏画面") }
                        OutlinedButton(
                            onClick = {
                                recSource = RecordSource.UVC
                                if (activeUvc?.isRunning != true) {
                                    status = "UVC 未出流：先「打开摄像头」；录像会开始但可能没有帧"
                                }
                            },
                            enabled = !recorder.isRecording,
                            modifier = Modifier.weight(1f)
                        ) { Text((if (recSource == RecordSource.UVC) "✓ " else "") + "UVC 画面") }
                    }
                    Button(
                        onClick = { if (recorder.isRecording) stopRecording() else startRecording() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (recorder.isRecording) "停止录像" else "开始录像")
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        if (recorder.isRecording) {
                            Box(
                                Modifier
                                    .background(Color.Red, CircleShape)
                                    .size(10.dp)
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            recInfo,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (recPath.isNotEmpty()) {
                        Text(
                            recPath,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    HorizontalDivider()
                    Text("音频增强", style = MaterialTheme.typography.titleSmall)
                    Button(
                        onClick = { refreshAudioDevices() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("刷新输入设备（USB 麦克风优先）") }
                    if (micDevices.isNotEmpty()) {
                        Text(
                            micDevices.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            "未发现输入设备（或 RECORD_AUDIO 未授权）",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { volume = AudioEnh.stepVolume(this@MainActivity, -1) },
                            modifier = Modifier.weight(1f)
                        ) { Text("音量 −") }
                        Text(volume.line(), modifier = Modifier.weight(1f))
                        Button(
                            onClick = { volume = AudioEnh.stepVolume(this@MainActivity, +1) },
                            modifier = Modifier.weight(1f)
                        ) { Text("音量 +") }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { runAudioProbe() },
                            modifier = Modifier.weight(1f)
                        ) { Text("实测 1s（采样率/RMS）") }
                        Button(
                            onClick = { runDuplexTest() },
                            modifier = Modifier.weight(1f)
                        ) { Text("双向通话 2s") }
                    }
                    Text(
                        audioInfo,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )

                    HorizontalDivider()
                    Text(
                        "三重并发压力测试（投屏 + UVC + 录像 30s）",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Button(
                        onClick = { startStress() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (stressRunning) "中止压力测试" else "开始压力测试（30 秒）") }
                    if (stressInfo.isNotEmpty()) {
                        Text(
                            stressInfo,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    HorizontalDivider()
                    Text("崩溃现场 / 操作日志", style = MaterialTheme.typography.titleSmall)
                    Text(
                        crashInfo,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { openCrashSnapshot() },
                            modifier = Modifier.weight(1f)
                        ) { Text("查看 crash_last.txt") }
                        Button(
                            onClick = {
                                Oplog.dumpCrash("manual")
                                updateCrashInfo()
                                status = "已写入 crash_last.txt（最后 200 条）"
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("立即 dump") }
                    }

                    HorizontalDivider()
                    Text("固件", style = MaterialTheme.typography.titleSmall)
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
                                val base = getExternalFilesDir(null)?.absolutePath
                                    ?: "/sdcard/Android/data/com.wzqvip.glxsscast/files"
                                val img = base + "/G25_MainFW_1.9.1.img"
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

                    Text(
                        "固件卡死（UVC 无帧 / 投屏和色条都没画面，但 USB 还能查状态）时：\n" +
                            "① 拔掉眼镜 USB 再插上 → 设备会以 03E7:2150 出现；\n" +
                            "② App 会自动灌固件（约 2 秒），等它重新枚举成 2E09:0030；\n" +
                            "③ 再点「连接眼镜」，然后开摄像头 / 发色条。\n" +
                            "仓库与原理/证据: https://github.com/wzqvip/glxss3-GLXSSME-rebuild\n开发者 bilibili: 塔可Tacoin",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                        "协议：16B 头 + 640×400 RGBA8888\n" +
                            "投屏通知栏可直接「停止投屏」「画面模式」，不用回 App。",
                        style = MaterialTheme.typography.bodySmall
                    )

                    // 实验项：改 roll 会让画面翻转，只有拔插眼镜能恢复 —— 所以放在最底下
                    Text(
                        "实验项：改了 roll 会导致画面上下翻转，需拔插眼镜恢复",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(
                        onClick = {
                            usb.connection?.let { c ->
                                rollIdx = (rollIdx + 1) % ROTATE_VALUES.size
                                val r = ROTATE_VALUES[rollIdx]
                                status = if (LcdControl.setRoll(c, r)) "roll=$r 已发送（$rollIdx/${ROTATE_VALUES.size-1}）" else "roll 发送失败"
                            } ?: run { status = "未连接" }
                        },
                        enabled = usb.isOpen,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("显示翻转（roll=" + ROTATE_VALUES[rollIdx] + "）") }
                }
            }
        }
    }
}






