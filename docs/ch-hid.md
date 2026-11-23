# 第 3 章 · HID、传感器与机身按键

> 这一章回答三件事：**HID 接口里到底有什么**、**28 B 报告每个字节是什么意思**、
> **眼镜上那一个按键是怎么上报的**。
>
> 结论来自两条独立证据链，能对上：
> 1. 原厂 `libhid.so` 的反汇编（`hid_start_report_info` 里 `0xA1/0x16` 28 B 的解析，
>    以及 `sensor_get_light/compass/acc_gyro/product_info` 的返回布局）；
> 2. 社区开源客户端 [`happyme531/glxss-client`](https://github.com/happyme531/glxss-client)
>    对同一协议的独立实现（`decode_sensor()` = `struct.unpack("<BBH9hBBf", ...)`）。
>
> 联网调研的出处与可信度见 **[hid-research.md](hid-research.md)**。
>
> 对应代码：[`usb/UsbLink.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/usb/UsbLink.kt)、
> [`usb/Peripherals.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/usb/Peripherals.kt)、
> [`lcd/LcdControl.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/lcd/LcdControl.kt)
>
> 原始抓包：[usb-capture.md §3](usb-capture.md#3-控制传输全部实测)；
> 原厂初始化序列：[device-recovery.md §4](device-recovery.md#4-原厂-app-的完整初始化序列健康设备hook-libusb100so)

---

## 1. 一句话结论

| 问题 | 答案 | 置信 |
|---|---|---|
| 眼镜上有几个可编程按键？ | **1 个**（SDK 里叫 Fn 键），HID 码固定 `0x71 = 113` | 【验】 |
| 28 B 报告解出多少？ | **28/28 B 全解**：15 个字段（按键 2 + 光感 1 + 磁罗盘 3 + 加速度 3 + 陀螺 3 + AF/CAF 2 + 温度 1） | 【验】 |
| 按键怎么上报？ | 28 B 报告的 `byte[0]=keyStatus`（0=松开，1=按下）、`byte[1]=keyCode=0x71`；原厂 300 ms 轮询一次 `0xA1/0x16` | 【验】 |
| 中断端点 `0x83` 用不用？ | 原厂 `libhid.so` **完全不用**（只 import 了 `libusb_control_transfer`，没有任何 bulk/interrupt 读）；`0x83` 是描述符里声明了但厂商没走的通道 | 【验】 |
| 触摸/手势？ | HID 报告里**没有**触摸字段；GLXSS ME 的"手势"是摄像头识别，不是触摸板 | 【推】 |
| 以前为什么只解出"计数器 + 时间戳"？ | `byte[2]` 其实是 **lux 低字节**，最后 4 B 是 **float 温度**；传感器没使能时 IMU 全 0，看起来像填充 | 【验】字段 /【推】零值原因 |

---

## 2. 接口与描述符

`if2` 是标准 HID 接口：`class 0x03 / subclass 0 / protocol 0`，一个中断 IN 端点 `0x83`
（`wMaxPacketSize=64`，`bInterval=8`）。配置描述符里 HID 类描述符为：

```
offset 0x20b:  09 04 02 00 01 03 00 00     ; if2, 1 ep, class 3, sub 0, proto 0
offset 0x213:  06 09 21 11 01 00 01 22 1f 00
               ; bcdHID=0x0111, bNumDescriptors=1,
               ; type=0x22 (report descriptor), wLength=0x001f (31 B)
offset 0x21d:  07 05 83 03 40 00 08        ; ep 0x83 INT IN, 64 B, interval 8
```

> 31 B 的报告描述符内容**没有抓到**（要 `GET_DESCRIPTOR(0x22)` 才有）。
> 从长度看，一个"厂商自定义 usage page + report ID 1 + 224 bit 输入"的描述符正好在这个量级，
> 但**未验证**。见 §11。

厂商实际只走 **class control 请求轮询**，不读 `0x83`：

```
if2  class 0x03  ep 0x83 INT IN 64 (interval 8)
  │
  ├─ control IN 0xA1/0x16  wIndex=2  wLen=28   ← 原厂每 300 ms 一次，28 B 报告
  ├─ control IN 0xA1/0x17  wIndex=2  wLen=24   ← 版本串 + 设备 ID
  ├─ control IN 0xA1/0x04/05/06                ← 光感/罗盘/IMU 的属性（不是数据）
  └─ control OUT/IN 0xA1/0x10..0x1A            ← 传感器开关、FSR、LED、复位
```

---

## 3. claim 语义（不 claim 就全是 -1）

### 3.1 内核 `hid-generic` 会先占住接口

Android/Linux 的 `hid-generic` 驱动会绑定 if2。此时枚举一切正常，但
`0xA1/0x16`、`0xA1/0x17` **全部返回 -1**，dmesg 持续 `input irq status -75`。

```
修复前：读取真实固件版本 → "读固件版本失败"   (0xA1/0x17 ret=-1)
修复后：读取真实固件版本 → "G25_MainFW_1.9.1" (ret=24)
```

原厂 App 冷启动也是先 `CLAIM iface=2` 再发 `0x16`（[device-recovery.md §4](device-recovery.md#4-原厂-app-的完整初始化序列健康设备hook-libusb100so)），
与我们的做法一致。

### 3.2 claim 是按 fd 的，会被抢

1. `UsbLink` 用自己的连接 claim 了 HID → `0x17` 正常；
2. `UvcCamera` 用**另一个 fd** 打开设备，也 `claimInterface(hid, force=true)`；
   usbfs 把接口从 `UsbLink` 的连接上抢走；
3. 关掉 UVC 后，`UsbLink` 的 `0x17` 又变回 -1。

解决办法：**每次读之前重新 claim**：

```kotlin
fun ensureHid(): Boolean {
    val c = conn ?: return false
    val d = device ?: return false
    return claimHid(c, d)
}

private fun claimHid(c: UsbDeviceConnection, dev: UsbDevice): Boolean {
    for (i in 0 until dev.interfaceCount) {
        val h = dev.getInterface(i)
        if (h.interfaceClass == UsbConstants.USB_CLASS_HID) {
            val ok = c.claimInterface(h, true)
            if (ok) hid = h
            Log.i(TAG, "claim HID if=${h.id} -> $ok")
            return ok
        }
    }
    return false
}
```

`UsbLink.open()` 里也会顺手 claim 一次；`UvcCamera.open()` 同样会 claim（保持兼容）。
MainActivity 的「读取真实固件版本」按钮在调用前显式 `usb.ensureHid()`。

### 3.3 `-75` 是正常的

每次枚举后 `hid-generic` 会打 4–6 条 `input irq status -75`，**健康设备也有**，
不是固件挂掉的证据（[device-recovery.md §2.4](device-recovery.md#24-dmesg-里的-input-irq-status--75-是正常现象别误判)）。

---

## 4. 请求总表（已按 `libhid.so` 反汇编修正）

全部 `bmRequestType = 0xA1`，`wIndex = 2`（HID 接口），`wValue` 见备注，超时 5000 ms。

| 请求 | wLen | 方向 | 实际语义 | 状态 |
|---|---|---|---|---|
| `0x04` | 12 | IN | 光感属性：`u8 status, u8 enabled, u16 rsvd, u64 chipId@4` | 已解【验】 |
| `0x05` | 12 | IN | 罗盘属性：同 `0x04` 布局 | 已解【验】 |
| `0x06` | 16 | IN | IMU 属性：`acc_status@0, acc_en@1, acc_fsr@2, gyro_status@3, gyro_en@4, gyro_fsr@5, u64 chipId@8` | 已解【验】 |
| `0x10` | 1 | IN/OUT | 光感（自动亮度）开关，`wValue=0/1` | 已解【验】 |
| `0x11` | 1 | IN/OUT | 罗盘开关，`wValue=0/1` | 已解【验】 |
| `0x12` | 1 | IN/OUT | 加速度计开关，`wValue=0/1` | 已解【验】 |
| `0x13` | 1 | IN/OUT | 加速度量程（FSR） | 【推】 |
| `0x14` | 1 | IN/OUT | 陀螺仪开关，`wValue=0/1` | 已解【验】 |
| `0x15` | 1 | IN/OUT | 陀螺量程（FSR）；字符串存在，**本库未见调用** | 【未】 |
| `0x16` | **28** | IN | **HID 报告**（按键 + 光感 + 罗盘 + IMU + AF/CAF + 温度） | 已解【验】 |
| `0x17` | **24** | IN | 产品信息：16 B 版本串 + 8 B 设备 ID | 已解【验】 |
| `0x18` | 1 | IN/OUT | 系统复位（`sensor_set_system_reset_after_500_ms`） | 【推】 |
| `0x19` | 1 | IN/OUT | 指示灯 LED，`wValue=0/1` | 已解【验】 |
| `0x1A` | 2 | IN | MOVI（Myriad）last state，原厂只打日志 | 【未】 |

补充事实：

- **旧文档把 `0x04/0x05/0x06` 写成"传感器数据"是错的。** 它们返回的是
  `LightAttribute` / `CompassAttribute` / `AcceleratorGyroAttribute`，
  原生代码解析后调 Java 构造器 `(IZJ)V` / `(IZJ)V` / `(IZIIZIJ)V`，
  字段与长度严格对应（见 §9）。
- 13 个控制请求在 `libhid.so` 里全部是 `libusb_control_transfer` 调用；
  **没有** `libusb_bulk_transfer` / 中断读 —— 这是"原厂不读 `0x83`"的直接证据。
- `0x18` 在库里没有 500 ms 的 sleep，延迟复位是固件侧行为；**别随手试**。

---

## 5. 28 B 报告：字段全解

原厂 `hid_start_report_info` 的线程循环（libhid.so Thumb，函数 `0x1b2c`）：

```
每 300 ms:
  lock; n = control(0xA1,0x16,wValue=0,wIndex=2,buf,28,5000); unlock
  if (n != 28) { log("hid get info fail"); 停止线程 }
  解析 buf → HIDInfo → onHidInfoCallback(HIDInfo)
  usleep(300000)
```

解析代码在 `0x1d38–0x1d4e`（取字段）和 `0x1b56–0x1c28`（按 `HIDInfo` 构造器顺序压栈），
可以直接对出下面这张表。

### 5.1 布局

```
偏移  类型        字段           说明
0     u8          keyStatus      0=松开/抬起，1=按下（见 §6）
1     u8          keyCode        固定 0x71 = 113 = KEY_CODE_GLXSS_BUTTON
2     u16 LE      lux            环境光，原始值（样本 0x0050=80）
4     i16 LE      compass_x      磁罗盘原始值
6     i16 LE      compass_y
8     i16 LE      compass_z
10    i16 LE      acc_x          加速度原始值
12    i16 LE      acc_y
14    i16 LE      acc_z
16    i16 LE      gyro_x         陀螺原始值
18    i16 LE      gyro_y
20    i16 LE      gyro_z
22    u8          af_result      自动对焦结果（相机侧状态，语义未细究）
23    u8          caf_flag       连续自动对焦标志
24    f32 LE      temperature    温度，摄氏度（样本 60.527）
```

等价的 C/Python 解包：`struct.unpack("<BBH9hBBf", data)`（**验证过**，见社区实现）。

### 5.2 实测样本逐字节

```
00 71 50 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 41 1b 72 42
│  │  └──┬──┘ └────────────────────────── 全 0 ──────────────────────────┘ └──┬───┘
│  │     │                                                                     │
│  │     └ byte[2..3] = 0x0050 = 80 lux                                        └ f32 = 60.527 °C
│  └ byte[1] = 0x71 = 113（Fn 键码，松开时也保留）
└ byte[0] = 0（松开）
```

> **对旧文档的更正**：`byte[2]` 不是"递增计数器"，是 lux 低字节（样本 80，
> 之前记录 0x50→0x65 是室内光照从 80 变到 101）；最后 4 B 不是时间戳，是温度。
> IMU/罗盘全 0 **很可能是**传感器没被使能（`0x10–0x14` 没发，或固件默认关闭）——
> 社区客户端也是先 `enable_sensors()` 再读，使能后才有非零数据。【推】

### 5.3 单位换算

FSR 由 `0x06` 的 `acc_fsr` / `gyro_fsr`（0–3）决定：

| fsr | 加速度量程 | 陀螺量程 |
|---|---|---|
| 0 | ±2 g | ±250 °/s |
| 1 | ±4 g | ±500 °/s |
| 2 | ±8 g | ±1000 °/s |
| 3 | ±16 g | ±2000 °/s |

```
acc_g    = raw * (2,4,8,16)[acc_fsr] / 32768
gyro_dps = raw * (250,500,1000,2000)[gyro_fsr] / 32768
```

坐标系与姿态约定（社区在真机上确认）：**抬头为 +**；
`pitch = atan2(x, hypot(y,z))`，`roll = atan2(z, y)`。
磁罗盘需要做硬铁/软铁校准（`glxss-client` 有 60 样本、3 轴跨度 ≥40 的校准流程）。

### 5.4 新旧 SDK 的差异

- 旧 SDK（`re/old-apks/me-jadx`，应用商店 1.8.4）的 `HIDInfo` 只有 **12 个字段**，
  到 `gyro_z` 为止 —— 即 `byte[0..21]`，没有 `af/caf/temperature`。
- 新 SDK（2.3.7，`re/liangliang`）的 `HIDInfo` 是 **15 个字段**，
  构造器签名 `(IIIIIIIIIIIIIIF)V`，多了 `af_result`、`caf_flag`、`temperature`。
- 结论：28 B 是**后加的 6 字节**（`byte[22..27]`）；老固件/老 App 只解析前 22 B 也能工作。
  升级 App 时按 28 B 解析即可，向后兼容（老设备尾部可能是 0）。

---

## 6. 按键：一个 Fn 键，码 0x71

### 6.1 码表

| 常量 | 值 | 出处 |
|---|---|---|
| `KEY_CODE_GLXSS_BUTTON` | **113 = 0x71** | `IKeyEventClient.java`（新旧 SDK 相同） |
| `KEY_STATUS_UP` | 0 | 同上 |
| `KEY_STATUS_DOWN` | 1 | 同上 |

SDK 里**只定义了这一个键码**。`libhid.so` 里没有任何 key 码表/字符串 —— 原生层只是把
`byte[0]/byte[1]` 原样塞进 `HIDInfo`，语义完全在 Java 层。
（Linux evdev 的 113 是 `KEY_MUTE`，但这里只是厂商自选值，别按 evdev 解释。）

### 6.2 原厂怎么把"电平"变成"点击/长按"

原厂不是收事件，而是**每 300 ms 采一次电平**，然后在 `KeyEventClient` 里数重复次数：

```
e = 上次 keyStatus（-1 表示第一包）
if (e != status) count = 0 else count++
isLong = (count == 3)          // 新版 2.3.7
switch (status):
  case 0 (UP):
      if (!first && !longFired && count == 0) -> onClick(keyCode)
      longFired = false
  case 1 (DOWN):
      if (!first && isLong) { longFired = true; onLongClick(keyCode) }
e = status
```

- 单击 = 按下后**第一个松开包**（`count==0`）；
- 长按 = **连续第 4 个按下包**（`count==3`），即 3 个轮询间隔 ≈ **0.9 s**（含抖动约 1.2 s）；
- 旧 SDK（1.8.4）阈值是 `repeatCount == 15`（约 16 包），说明老版本轮询更快或防抖更保守；
- 社区客户端用 0.8 s 时间阈值，效果等价。

> 推论：如果一次按下-松开短于 300 ms 且固件不锁存，可能整段被漏掉。
> 是否锁存**未验证**（§11）。

### 6.3 原厂 App 把按键绑到了什么

全仓库只有一处消费按键：`com/llvision/app/CameraFragment.setGlxssKey()`。

```java
mKeyEventClient.setOnGlxssFnClickListener(keyCode -> {
    if (recordMode) {                  // 只在录像中生效
        mFov++; if (mFov > 2) mFov = 0;
        // 切 FOV 档位：图标换成 btn_fov_10x2x / btn_fov_15x2x / btn_fov_20x2x
        mHandler.sendEmptyMessageDelayed(93, 500);   // 500ms 后再下发
    }
});
```

- **单击**：录像中循环切换 FOV 三档（图标 `btn_fov_10x2x` / `15x2x` / `20x2x`）；不在录像时**什么都不做**。
- **长按**：原厂 App **没有**注册 `setOnGlxssFnLongClickListener`（SDK 提供，App 未用）。
- **没有**"拍照 / 返回 / 菜单"的按键绑定。拍照/录像是屏幕上的按钮（另一路 UVC/LCD 命令）。

### 6.4 没有触摸板

- 28 B 报告没有触摸坐标/手势字段，SDK 也没有 `Touch` 相关类；
- GLXSS ME 宣传里的"手势翻页"是**摄像头 RGB 识别**（青亭网 2018 发布会实测），不是触摸；
- 初代 Glxss（2015，一体机）才有"电源键 + 拍照键 + 四手势触摸板"，**不是 G25**。

---

## 7. 读取与解析（Kotlin 可直接抄）

### 7.1 claim + 读一包

```kotlin
private const val REQ_TYPE_IN   = 0xA1          // 0x80 | 0x20 | 0x01
private const val IFACE_HID     = 2
private const val REQ_HID_INFO  = 0x17
private const val REQ_HID_REPORT= 0x16
private const val TIMEOUT_MS    = 1500

private var hidItf: UsbInterface? = null

fun claimHid(conn: UsbDeviceConnection, dev: UsbDevice): Boolean {
    for (i in 0 until dev.interfaceCount) {
        val itf = dev.getInterface(i)
        if (itf.interfaceClass == UsbConstants.USB_CLASS_HID) {
            val ok = conn.claimInterface(itf, true)
            if (ok) hidItf = itf
            return ok
        }
    }
    return false
}

fun readReport(conn: UsbDeviceConnection): ByteArray? {
    if (hidItf == null) return null
    val buf = ByteArray(28)
    val n = conn.controlTransfer(REQ_TYPE_IN, REQ_HID_REPORT, 0, IFACE_HID, buf, 28, TIMEOUT_MS)
    return if (n == 28) buf else null   // -1 = 没 claim 住 / 被 UVC 抢走
}
```

### 7.2 解析

```kotlin
data class HidReport(
    val keyStatus: Int, val keyCode: Int,
    val lux: Int,
    val compass: IntArray, val acc: IntArray, val gyro: IntArray,
    val afResult: Int, val cafFlag: Int,
    val temperatureC: Float,
)

fun parseReport(b: ByteArray): HidReport {
    require(b.size == 28)
    val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    val keyStatus = bb.get().toInt() and 0xFF
    val keyCode   = bb.get().toInt() and 0xFF
    val lux       = bb.short.toInt() and 0xFFFF
    val compass   = IntArray(3) { bb.short.toInt() }
    val acc       = IntArray(3) { bb.short.toInt() }
    val gyro      = IntArray(3) { bb.short.toInt() }
    val af        = bb.get().toInt() and 0xFF
    val caf       = bb.get().toInt() and 0xFF
    val temp      = bb.float
    return HidReport(keyStatus, keyCode, lux, compass, acc, gyro, af, caf, temp)
}
```

### 7.3 按键状态机（单击 / 长按）

长按**只按时间判**（建议 0.9 s）；不要同时要求“连续 N 个按下包”——100 ms
轮询时某个按下包可能落在 0.89 s，下一次轮询前就松开了，按满 1 秒会被误判成
单击。松开时若 `duration >= longMs` 且长按还没触发，也补发 `LongPress`：

```kotlin
class FnButton(private val longMs: Long = 900) {
    private var pressedAt = 0L
    private var longFired = false

    sealed class Event { data class Click(val code: Int) : Event()
                          data class LongPress(val code: Int) : Event() }

    fun feed(r: HidReport, now: Long = SystemClock.elapsedRealtime()): Event? {
        if (r.keyCode != 113) return null
        when (r.keyStatus) {
            1 -> {
                if (pressedAt == 0L) { pressedAt = now; longFired = false }
                if (!longFired && now - pressedAt >= longMs) {
                    longFired = true
                    return Event.LongPress(r.keyCode)
                }
            }
            0 -> {
                val t = pressedAt
                val duration = if (t != 0L) now - t else 0L
                pressedAt = 0
                if (t != 0L && !longFired) {
                    longFired = false
                    // 阈值边缘：最后一个按下包没到 0.9 s，但实际按住够了
                    return if (duration >= longMs) Event.LongPress(r.keyCode)
                           else Event.Click(r.keyCode)
                }
                longFired = false
            }
        }
        return null
    }
}
```

> 注意：`readReport()` 每 300 ms 一次即可（与固件节奏一致）。用
> `lifecycleScope.launch(Dispatchers.IO) { while (active) { ...; delay(300) } }`，
> 别在主线程轮询。

### 7.4 本仓库 Python 实现（2026-11-22 实机修正）

`linux/glxss/hid.py` 的 `FnWatcher` 把上面的状态机做成纯逻辑。**长按只看
按住时间**（默认 0.9 s），不再要求“连续第 4 个按下包”：

```python
from glxss import hid

watcher = hid.FnWatcher(long_seconds=0.9)   # long_packets 仅为兼容保留
for event in watcher.feed(report):          # 每个 28 B 报告喂一次
    if event.kind == "click":
        print("单击", event.duration, event.packets)
    elif event.kind == "long":
        print("长按", event.duration, event.packets)
```

原因（用户实机反馈“长按 1 秒没反应”）：100 ms 轮询时 1 s 按压只有 ~10 个
包；若某个按下包落在 0.89 s、下一次轮询前就松开，旧逻辑（时间 AND 包数）
会判成单击。现在：
* 按住期间任一次轮询 `elapsed >= 0.9 s` → 立即发 `long`；
* 松开时才攒够时间（`duration >= 0.9 s`）→ 发 `long` 而不是 `click`；
* 每次 `keyStatus` 变化都打一行 `[hid] key 0x71 down/up（按住 x.xx s）`，
  方便真实按键排查。

`pipeline.LevelPoller` 在独立线程里按 `--hid-poll`（默认 100 ms）读报告、
更新水平仪、写按键日志，并把事件放进队列；`stream.Streamer` 主循环只
`drain_events()`，**绝不在 UVC 读线程里发 USB 控制请求**。控制请求和 LCD
bulk OUT 共用 `usb.UsbGate`，所以 HID 轮询既不会插进 1 MB LCD 帧，也不会
阻塞 UVC 读流。1 MB 帧发送窗口实测 29–39 ms，100 ms 轮询最多被推迟这么久，
不会吞掉长达 1 s 的按压。

`pi/stream.py` 的绑定（2026-11-22 晚改版，**RTSP 不再绑键**）：

* **长按 ≥0.9 s** = 眼镜 camera preview ↔ HUD（stream 进程内渲染，不 release
  LCD；旧版的长按开关 RTSP 已移除）；
* **短按（preview 下）** = 循环数字变焦 `1.0x → 1.5x → 2.0x → 1.0x`；
  `LcdRenderer` 先对 720p 源画面做**居中裁切**（1.5x → 853×480，2.0x →
  640×360），再按 `--mirror-fit` 映射到 640×400；三分线/水平仪在裁切之后绘制，
  始终正立；
* **短按（HUD 视图下）忽略**（只打一行日志），避免在 HUD 上误触放大；要回视频
  请长按；
* RTSP 开关改用 `glxss ctl rtsp on|off`；变焦也可
  `glxss ctl zoom 1.0|1.5|2.0|cycle`；
* 事件写 `[stream] Fn 按键：…` 一行一个动作，`glxss ctl status` 可看
  `view/lcd_mode/zoom/last_button/level/hid`。没有硬件时用
  `glxss ctl button click|long` 注入同一事件队列做端到端测试。

变焦只作用在**眼镜预览**这一路；RTSP 推流始终是全画幅（对端观众看到的是
完整 1280×720 视野）。档位不落盘：进程重启回到 `--zoom`（默认 1.0，安全默认，
避免上次放大后误以为镜头/对焦坏了）。

### 7.5 先使能传感器，否则 IMU 全 0

```kotlin
fun enableSensors(conn: UsbDeviceConnection) {
    // 0x10 light / 0x11 compass / 0x12 acc / 0x14 gyro
    for (req in intArrayOf(0x10, 0x11, 0x12, 0x14)) {
        val ack = ByteArray(1)
        val n = conn.controlTransfer(0xA1, req, 1, IFACE_HID, ack, 1, TIMEOUT_MS)
        Log.i(TAG, "enable 0x%02x -> n=%d ack=%d".format(req, n, ack[0].toInt()))
    }
}

fun readLightAttr(conn: UsbDeviceConnection): ByteArray {   // 12 B
    val b = ByteArray(12)
    conn.controlTransfer(0xA1, 0x04, 0, IFACE_HID, b, 12, TIMEOUT_MS); return b
}
// 0x05 同构；IMU 用 0x06 + 16 B
```

返回布局（原生 `0x2046` / `0x21fe` 处逐字节拷贝，已核对）：

```
0x04/0x05 (12B): status@0  enabled@1  chipId(u64 LE)@4
0x06      (16B): acc_status@0 acc_en@1 acc_fsr@2 gyro_status@3 gyro_en@4 gyro_fsr@5
                 chipId(u64 LE)@8
```

`acc_fsr` / `gyro_fsr` 就是 §5.3 的 0–3。

---

## 8. 24 B 产品信息（`0xA1/0x17`）

```kotlin
fun readProduct(conn: UsbDeviceConnection): Pair<String, Long>? {
    val b = ByteArray(24)
    val n = conn.controlTransfer(0xA1, 0x17, 0, IFACE_HID, b, 24, TIMEOUT_MS)
    if (n != 24) return null
    val version = String(b, 0, 16, Charsets.US_ASCII).trimEnd('\u0000', ' ')
    val id = ByteBuffer.wrap(b, 16, 8).order(ByteOrder.LITTLE_ENDIAN).long
    return version to id
}
```

| 偏移 | 长度 | 内容 |
|---|---|---|
| 0–15 | 16 | ASCII 版本串，实跑 `G25_MainFW_1.9.1`（正好 16 字符，无 NUL） |
| 16–23 | 8 | 设备 ID（u64 LE，Java 侧 `ProductInfo.getGlxssId()`） |

原生 `sensor_get_product_info`（函数 `0x22b0`）先 `memcpy(16)` 到输出，
再写一个 NUL，然后把 `buf+16..23` 的 8 字节拼成 `long`，调用
`ProductInfo(String, long)` 构造器（签名 `(Ljava/lang/String;J)V`）。【验】

> 注意：`ProductInfo(byte[])` 那个 256 B 的解析器走的是 **LCD/EEPROM**，不是这条 24 B
> 请求。两个 `ProductInfo` 同名但来源不同，别混。

---

## 9. `0x1A` 与其余细节

- `0x1A`（2 B）：`hid_get_movi_last_state`，日志格式
  `hid_get_movi_last_state:ret=%d,DATA=0x%x`；ret==2 时返回 2 B 值，否则返回 ret。
  猜测是 Myriad 的启动/运行状态（`[未]`），本项目没用到。
- 所有请求的 `wIndex` 都是 **2**；`0x04/0x05/0x06/0x16/0x17` 的 `wValue` 都是 0。
- `0x10/0x11/0x12/0x13/0x14/0x15/0x18/0x19` 是"写 1 字节"：`wValue` 是参数，
  `data` 是 1 B 返回缓冲（原厂也读 1 B 回来做 ack）。

---

## 10. 实测数据与复现

| 项 | 值 |
|---|---|
| `0xA1/0x17` 未 claim | `-1` |
| `0xA1/0x17` 已 claim | `ret=24`，`G25_MainFW_1.9.1` |
| `0xA1/0x16` 频率 | 原厂 300 ms（`usleep(300000)`，反汇编 `0x1c90`） |
| `0xA1/0x16` 长度 | 固定 28；≠28 时原厂打日志并**停线程** |
| 中断端点 | 原厂零调用；本仓库 `bulkTransfer` 路径未验证 |

复现反汇编（不碰设备）：

```powershell
# 本仓库外的分析脚本：re/liangliang/tools/dis4.py
python dis4.py native/libhid.so .text thumb 1b2c 1e64   # 28B 轮询 + 解析
python dis4.py native/libhid.so .text thumb 22b0 23dc   # 0x17 产品信息
python dis4.py native/libhid.so .text thumb 203e 2060   # 0x04 属性
```

关键地址：`0x1d02` 发 `0x16` 请求，`0x1d38–0x1d4e` 取字段，
`0x1b56–0x1c28` 按 `HIDInfo` 15 参构造器压栈；`0x1c90` 的 `usleep(0x493e0)` = 300 ms。

---

## 11. 待验证

- [ ] `GET_DESCRIPTOR(0x22)` 拿 31 B 报告描述符：确认 28 B 报告是否有 report ID、
      是否是标准 input report（决定 `0x83` 能不能当事件流用）。
- [ ] `0x83` 中断端点用 `UsbRequest.queue()` 读是否稳定；有没有"按键事件"包。
- [ ] 按下-松开的**锁存行为**：<300 ms 的点击会不会丢。
- [ ] `keyStatus/keyCode` 之外的按键位：长按/双击是否还有别的码（目前只有 0x71）。
- [ ] 传感器默认状态：不使能时 IMU 全 0 是否属实；使能后 `0x16` 是否立刻有数据。
- [ ] `lux` 的量程/单位（原始值 vs lux 标定）、`temperature` 的准确性（样本 60.5 °C 偏高）。
- [ ] `af_result` / `caf_flag` 的取值含义（相机 AF 状态？谁在写？）。
- [ ] `0x13` / `0x15`（FSR 写）的取值范围与实际效果；`0x15` 为何在库里没有调用点。
- [ ] `0x18` 复位的实际行为与恢复时间（**危险，别在正常设备上试**）。
- [ ] `0x1A` 两个字节的定义。

以上除 `0x18` 外都不需要拆机，用 `0x16` 轮询 + 手动按键/遮光/转动即可。

---

## 12. 参考

- 本地证据：`re/liangliang/native/libhid.so`（ARM32 Thumb-2，符号已 strip）；
  `re/liangliang/jadx-out/sources/com/llvision/glass3/{sdk,framework}/hid/**`；
  `re/liangliang/usb-descriptors.bin`（配置描述符 752 B）。
- 原厂 App：`com.llvision.glass3.sdk.service.KeyEventService`、
  `com.llvision.glass3.framework.hid.KeyEventClient`、`com.llvision.app.CameraFragment`。
- 社区独立实现（同协议）：[happyme531/glxss-client](https://github.com/happyme531/glxss-client)
  —— `glxss.py: decode_sensor()`、`interaction.py: ButtonTracker`。
- 联网调研与型号/功能资料：[hid-research.md](hid-research.md)。
