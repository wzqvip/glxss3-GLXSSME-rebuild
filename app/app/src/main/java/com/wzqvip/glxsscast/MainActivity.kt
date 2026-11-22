package com.wzqvip.glxsscast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.wzqvip.glxsscast.lcd.LcdControl
import com.wzqvip.glxsscast.lcd.LcdProtocol
import com.wzqvip.glxsscast.lcd.ProductInfo
import com.wzqvip.glxsscast.settings.Prefs
import com.wzqvip.glxsscast.usb.Peripherals
import com.wzqvip.glxsscast.usb.UsbLink
import com.wzqvip.glxsscast.uvc.UvcCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    // ---- 画面调整 ----
    private var zoom by mutableStateOf(1f)
    private var offsetX by mutableStateOf(0f)
    private var offsetY by mutableStateOf(0f)
    private var adjustOpen by mutableStateOf(false)

    // ---- 开关（持久化） ----
    private var autoBrightness by mutableStateOf(false)
    private var hidOn by mutableStateOf(false)
    private var autoReconnect by mutableStateOf(true)

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
        hidOn = prefs.hidEnabled && Peripherals.hidRunning

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
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        unregisterReceiver(attachReceiver)
        // 连接是进程内共享的（CastService 可能还在用），这里不关闭
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
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
                if (prefs.hidEnabled && !Peripherals.hidRunning) Peripherals.startHid(usb)
            }
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
                if (!cam.prepare(dev)) {
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

    private fun toggleHid() {
        if (Peripherals.hidRunning) {
            Peripherals.stopHid()
            prefs.hidEnabled = false
            status = "HID 已停止（共 ${Peripherals.hidPackets} 包）"
        } else if (usb.isOpen) {
            Peripherals.startHid(usb)
            prefs.hidEnabled = true
            status = "HID 监听中"
        } else {
            status = "未连接"
        }
        hidOn = Peripherals.hidRunning
    }

    // ==================================================================
    // UI
    // ==================================================================

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

            Scaffold(
                topBar = { TopAppBar(title = { Text("GLXSS3 Cast 0.1.1") }) }
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
                            hidOn = Peripherals.hidRunning
                            // 通知栏「画面模式」改了服务侧模式后，回 App 时同步显示
                            val svcMode = com.wzqvip.glxsscast.cast.CastService.fillMode
                            if (svcMode != fillMode) fillMode = svcMode
                            delay(500)
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
                    Text("外设", style = MaterialTheme.typography.titleSmall)
                    Button(
                        onClick = { toggleHid() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (hidOn)
                                "停止 HID（已收 ${Peripherals.hidPackets}）"
                            else "开始 HID 监听"
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
                            "原理与证据见 docs/device-recovery.md。",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                        "协议：16B 头 + 640×400 RGBA8888\n" +
                            "投屏通知栏可直接「停止投屏」「画面模式」，不用回 App。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
