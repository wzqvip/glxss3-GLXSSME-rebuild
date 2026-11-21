# 第 1 章 · 显示子系统

> 本章讲清楚：为什么这副眼镜"插上就能当显示器"、一帧数据到底长什么样、手机端怎么把任意屏幕
> 变成 640×400、以及亮度/开关/roll 这些控制命令怎么发。
>
> 对应代码：[`lcd/LcdProtocol.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/lcd/LcdProtocol.kt)、
> [`lcd/LcdControl.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/lcd/LcdControl.kt)、
> [`usb/UsbLink.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/usb/UsbLink.kt)、
> [`cast/CastService.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/cast/CastService.kt)
>
> 原始抓包：[usb-capture.md](usb-capture.md) §2–§3；恢复相关：[device-recovery.md](device-recovery.md)

---

## 1. 原理：眼镜端只做"往面板上贴图"

```
手机                                                  眼镜（Myriad 2 / RTEMS）
────                                                  ────────────────────────
渲染一帧 640×400 RGBA
  │
  ├─ 加 16 B 头
  │
  └─ bulk OUT EP 0x01 ──────────────► LEON 核收数据 → 校验头 → 写帧缓冲 → 面板刷新
                                          ▲
     0xA1/0x01 亮度  ────────────────────┤
     0xA1/0x03 开关屏 ───────────────────┤ 控制面（class 请求，wIndex=3）
     0xA1/0x02 roll   ───────────────────┤
     0xA1/0x05 显示模式 ─────────────────┘
```

三个关键事实：

1. **没有"画图命令"**。固件不会解析任何图形指令，它只认一种东西：`16 B 头 + 定长像素`。
   所以"AR 叠加""UI 布局""字体渲染"全部发生在手机端——眼镜只是最后一级 framebuffer。
2. **一帧就是一次 bulk 传输**。原厂和本仓库都是把 1,024,016 B 一次性写下去（`bulkTransfer` 内部按 512 B 分包），
   设备在 30 ms 内收完，等效 ~35 MB/s。
3. **控制与数据分离**。亮度/开关/模式走 USB **class control transfer**（不是 bulk），
   参数放在 `wValue`，`wIndex` 固定为 LCD 接口号 3。

> 这也解释了为什么"发色条能看见、但原厂 App 退出后屏幕黑"：原厂退出时会 `0xA1/0x03` 把屏
> **switch 关掉**，而 `0xA1/0x05` 切显示模式并不会重新点亮面板。必须显式 `setScreenOn(true)`。

---

## 2. 帧格式（16 B 头 + RGBA）

### 2.1 头布局

| 偏移 | 长度 | 字段 | 值 | 说明 |
|---|---|---|---|---|
| 0 | 1 | `format` | `0x01` | 1 = 原始 RGBA8888；其它值未验证 |
| 1 | 1 | `format2` | `0x00` | 保留 |
| 2 | 1 | `format3` | `0x00` | 保留 |
| 3 | 1 | `format4` | `0x00` | 保留 |
| 4 | 4 | `totalLen` | `1,024,016`（LE u32） | **16 + 像素字节数**，不是像素字节数 |
| 8 | 8 | `extended[8]` | 全 0 | 预留扩展，当前未用 |
| 16 | N | `payload` | 640×400×4 = 1,024,000 | 裸 RGBA8888，逐行从左到右、从上到下 |

640×400 时的真实字节（前 16 B）：

```
01 00 00 00  10 A0 0F 00  00 00 00 00  00 00 00 00
│            │            └───────────┴─ extended[8] = 0
│            └─ totalLen = 0x000FA010 = 1,024,016（小端）
└─ format = 1（RGBA8888）
```

> ⚠️ 常见错误：把 `totalLen` 写成 `1,024,000`（漏掉 16 B 头），或者把格式字节写到 `[1]`。
> 早期文档里 `0x0fff8818` 那个示例是笔误，**以代码为准**：
> [`LcdProtocol.frame()`](../app/app/src/main/java/com/wzqvip/glxsscast/lcd/LcdProtocol.kt#L20-L33) 写的是 `out[0]=1`、`out[4..7]=out.size`。

### 2.2 像素布局

| 项 | 值 |
|---|---|
| 像素格式 | RGBA8888，每像素 4 B：`R, G, B, A` |
| 行宽（payload） | `640 × 4 = 2,560 B` |
| **行填充（padding）** | **无**。payload 是紧密排列的 `400 × 2560 = 1,024,000 B` |
| 原点 | 左上角；不做垂直翻转 |
| Alpha | 实测原厂黑帧是 `00 00 00 FF`、近黑帧 `01 01 01 FF`；A 通常写 `0xFF` |

设备端把 payload 直接当帧缓冲用，所以**不存在 rowStride ≠ width×4 的情况**；
反过来说，手机端抓图时 `ImageReader` 的 `rowStride` 可能大于 `width×4`，**必须在映射阶段消掉**（见 §4）。

### 2.3 尺寸速算

```
payload   = 640 × 400 × 4          = 1,024,000 B
frame     = 16 + payload           = 1,024,016 B
25 fps    ≈ 25.6 MB/s 平均
单帧传输   ≈ 30 ms     ≈ 34–35 MB/s（USB 2.0 High-Speed 上限附近）
```

原厂的待机图 `assets/logo.bin` 正好是 **1,024,000 B**，被直接当一帧发出去——这也是"640×400×4"最早的交叉证据。

---

## 3. 控制面（全部实测）

### 3.1 请求总表

所有控制请求都是 `bmRequestType = 0xA1`（Device-to-Host | Class | Interface），
`wIndex` 是接口号（LCD=3，HID/AUX=2），大多数请求的 `wLength=1` 只是一次"哑读"。

| 功能 | bmReq | bRequest | wValue | wIndex | wLength | 返回/说明 |
|---|---|---|---|---|---|---|
| 设亮度 | `0xA1` | `0x01` | 100–254 | 3 | 1 | 实测 `0x64`–`0xFE`；App 侧夹到 100–254，越界值的固件行为未测 |
| 设 roll（旋转） | `0xA1` | `0x02` | roll 值 | 3 | 1 | 与状态里的 `roll` 字段对应 |
| 屏开关 | `0xA1` | `0x03` | 0/1 | 3 | 1 | **0=关，1=开**；只发帧不会点亮 |
| 读状态 | `0xA1` | `0x04` | 0 | 3 | **8** | 8 B，见 §3.2 |
| 显示模式 | `0xA1` | `0x05` | 1/2 | 3 | 1 | **1=关/logo/待机，2=投屏开**；0 未实测 |
| 读 EEPROM 产品信息 | `0xA1` | `0x07` | `0x0100` | 3 | **264** | 8 B 头 + 256 B 产品信息（见 §6） |
| 写 EEPROM | `0x21` | `0x06` | 0 | 3 | 264 | **危险，不要调用**（会改 SN/BOM） |
| 自动亮度 | `0xA1` | `0x10` | 0/1 | 2 | 1 | HID/AUX 接口 |
| LED 指示灯 | `0xA1` | `0x19` | 0/1 | 2 | 1 | HID/AUX 接口 |

> 表里的 `0x07` / `0x21/0x06` 来自 `liblcd.so` 静态枚举 + 抓包，不是猜的；
> 完整枚举见 [firmware-extraction.md §3.2](firmware-extraction.md#32-全部-vendor-control-请求枚举没有读-flash内存)。

### 3.2 状态 8 B 布局

实测样本：`80 02 90 01 01 b9 0a 00`

| 字节 | 字段 | 样本 | 解析 |
|---|---|---|---|
| 0–1 | `width` (LE u16) | `80 02` | 640 |
| 2–3 | `height` (LE u16) | `90 01` | 400 |
| 4 | `bopen` | `01` | 屏是否开（1=开） |
| 5 | `luminance` | `b9` | 当前亮度 185 |
| 6–7 | `roll` (LE u16) | `0a 00` | roll = 10 |

> 设置亮度后回读 `byte[5]` 会同步变化（设 `0xEF` 后读到 `0xEF`），所以这个字段可以当"亮度是否生效"的确认。
> 状态里**没有**固件版本信息——版本走 HID 的 `0xA1/0x17`，见 [ch-hid.md](ch-hid.md)。

### 3.3 推荐的开屏顺序

原厂 `LCDService` 的行为是：**屏没开就先开，再切显示模式**。我们照抄：

```
1) 0xA1/0x03 wValue=1   （setScreenOn true）
2) 0xA1/0x05 wValue=2   （displayMode = 投屏）
3) 每帧 bulk OUT 0x01
```

停止投屏时反过来：`0xA1/0x05 wValue=1`（或 `0xA1/0x03=0`）。

---

## 4. 手机端管线：从 MediaProjection 到 640×400

### 4.1 驱动步骤（从零到一帧）

```
1) 打开 2E09:0030，claim 厂商接口（if3，class 0xFF），找到 bulk OUT 0x01
2) （可选）0xA1/0x04 读 8 B 状态，记下 bopen / luminance
3) 0xA1/0x03 wValue=1      开屏（屏被关时只发帧不会亮）
4) 0xA1/0x05 wValue=2      切到投屏显示模式
5) 组帧：16 B 头（format=1, totalLen=1,024,016）+ 640×400 RGBA
6) bulkTransfer(EP 0x01, 1,024,016 B, timeout=5000)；短写/失败则 16 KB 分块重试
7) 循环 5–6；停止时发 0xA1/0x05 wValue=1（或 0xA1/0x03 wValue=0）
8) 亮度/roll 可随时发：0xA1/0x01 / 0xA1/0x02
```

### 4.2 数据流

```
MediaProjection (系统录屏授权)
   └─ createVirtualDisplay(抓取宽高按屏幕比例)
        └─ ImageReader(RGBA_8888, maxImages=2)
             └─ acquireLatestImage()
                  └─ planes[0].buffer / rowStride / pixelStride
                       └─ 映射到 640×400（适应 / 裁剪 / 拉伸）
                            └─ 16 B 头 + 1,024,000 B
                                 └─ bulkTransfer(EP 0x01, 1,024,016 B)
```

两个反直觉的点：

* **VirtualDisplay 的尺寸不是 640×400**。如果直接建 640×400，系统会按手机屏幕比例在你的抓图里
  再套一层黑边（letterbox），映射时怎么裁都不对。正确做法是让抓取尺寸**保持手机当前屏幕的宽高比**：
  竖屏固定宽 640、高按比例；横屏固定高 400、宽按比例。见
  [`CastService.captureSizeFor()`](../app/app/src/main/java/com/wzqvip/glxsscast/cast/CastService.kt#L194-L203)。
* **Android 14+ 不能为同一个 MediaProjection 建第二个 VirtualDisplay**。屏幕旋转时不能重建管线，
  只能 `VirtualDisplay.setSurface(newReader.surface)` + `resize()`，见
  [`applyScreenSize()`](../app/app/src/main/java/com/wzqvip/glxsscast/cast/CastService.kt#L295-L345)。

### 4.3 三种映射模式

| 模式 | 语义 | 代码 | 黑边 |
|---|---|---|---|
| 适应（fit） | 等比缩放到 640 宽，上下留黑边 | `mapFit` | 有，先 `Arrays.fill(dst, 0)` |
| 裁剪（cover） | 等比放大到填满 640×400，居中裁掉多余部分，可叠加 zoom/offset | `mapCover` | 无 |
| 拉伸（stretch） | 直接把源行按比例抽样成 640×400，不保持比例 | `mapStretch` | 无（但见 §7 坑 6） |
`mapCover` 的关键实现（节选，完整见
[`CastService.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/cast/CastService.kt#L450-L513)）：

```kotlin
val scale = maxOf(dstW.toFloat() / w, dstH.toFloat() / h) * z   // cover × zoom
val srcW = (dstW / scale).coerceIn(1f, w.toFloat())
val srcH = (dstH / scale).coerceIn(1f, h.toFloat())
val left = (cx - srcW / 2f).coerceIn(0f, maxLeft)
val top  = (cy - srcH / 2f).coerceIn(0f, maxTop)

// 每列采样源 x 预计算，逐像素只做索引；像素步长不是 4 时也能处理
val xs = IntArray(dstW) { x ->
    (left + (x + 0.5f) * srcW / dstW).toInt().coerceIn(0, w - 1)
}
```

`pixelStride == 4` 走快速路径（一次拷 4 字节），否则按 `pixelStride` 逐通道取，
最后一行/最后一列越界时用 clamp 兜住——这样旋转、分屏、挖孔屏等任意抓取尺寸都不会崩。

---

## 5. 可运行代码

### 5.1 组帧（仓库真实代码）

```kotlin
// lcd/LcdProtocol.kt
object LcdProtocol {
    const val WIDTH = 640
    const val HEIGHT = 400
    const val HEADER_SIZE = 16
    const val PAYLOAD_SIZE = WIDTH * HEIGHT * 4

    fun frame(payload: ByteArray): ByteArray {
        require(payload.size == PAYLOAD_SIZE) {
            "payload must be $PAYLOAD_SIZE bytes, got ${payload.size}"
        }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = 1
        val total = out.size
        out[4] = (total and 0xFF).toByte()
        out[5] = ((total ushr 8) and 0xFF).toByte()
        out[6] = ((total ushr 16) and 0xFF).toByte()
        out[7] = ((total ushr 24) and 0xFF).toByte()
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }
}
```

### 5.2 发帧（先整帧，失败再分块）

```kotlin
// usb/UsbLink.kt —— 整帧一次写与原厂一致；只有失败才退回 16 KB 分块
fun sendFrame(frame: ByteArray): Boolean {
    val c = conn ?: return false
    val ep = epOut ?: return false
    var sent = c.bulkTransfer(ep, frame, 0, frame.size, 5000)
    if (sent == frame.size) return true

    var off = if (sent > 0) sent else 0
    while (off < frame.size) {
        val len = minOf(CHUNK, frame.size - off)          // CHUNK = 16 * 1024
        val n = c.bulkTransfer(ep, frame, off, len, TIMEOUT_MS)
        if (n < 0) return false
        off += n
    }
    return true
}
```

> 帧本身**没有 ACK**。`bulkTransfer` 返回写入字节数就是唯一反馈；
> 设备消费不过来时会表现为 `LIBUSB_ERROR_TIMEOUT` / 短写，此时分块重试即可。

### 5.3 读状态

```kotlin
// lcd/LcdControl.kt
fun readStatus(conn: UsbDeviceConnection): Status? {
    val b = ctrl(conn, 0x04, 0, IFACE_LCD, 8) ?: return null
    if (b.size < 8) return null
    fun u(i: Int) = b[i].toInt() and 0xff
    return Status(
        width = u(0) or (u(1) shl 8),
        height = u(2) or (u(3) shl 8),
        screenOpen = u(4) != 0,
        luminance = u(5),
        roll = u(6) or (u(7) shl 8),
        raw = b
    )
}
```

### 5.4 投屏主循环（节选）

```kotlin
// cast/CastService.kt —— 帧头预填，之后每帧只拷像素
frameBuf = ByteArray(LcdProtocol.HEADER_SIZE + LcdProtocol.PAYLOAD_SIZE).also { fb ->
    fb[0] = 1
    val total = fb.size
    fb[4] = (total and 0xFF).toByte()
    fb[5] = ((total ushr 8) and 0xFF).toByte()
    fb[6] = ((total ushr 16) and 0xFF).toByte()
    fb[7] = ((total ushr 24) and 0xFF).toByte()
}

// onFrame(): 映射 → 拷到 header 之后 → 发
System.arraycopy(dst, 0, fb, LcdProtocol.HEADER_SIZE, dst.size)
if (usb.sendFrame(fb)) frames++
```

---

## 6. 实测数据

| 项 | 值 | 来源 |
|---|---|---|
| 帧大小 | 1,024,016 B（16 + 1,024,000） | 抓包 `libusb_bulk_transfer(ep=0x01, len=1024016)` |
| 单帧耗时 | **约 30 ms**，≈35 MB/s | `SEND_FRAME ret=1024016 dt=30ms` |
| 投屏帧率 | **25 fps**（24.6–25.6） | App 日志 `fps=` |
| 并发 | UVC 24.6 fps + 投屏 25 fps | 恢复后实测 |
| 屏状态回读 | `80 02 90 01 01 b9 0a 00` → 640×400、屏开、亮度 185、roll 10 | 抓包 |
| EEPROM 产品信息 | 264 B（8 B 头 + 256 B 信息），如 `firmwareID` 在 info[52..59] | 反汇编 + 抓包 |

EEPROM 256 B 产品信息（只读，安全）：

| 偏移 | 长度 | 字段 |
|---|---|---|
| 0 | 1 | platformID |
| 1–7 | 7 | productID（ASCII） |
| 8–9 | 2 | firmID |
| 10–25 | 16 | bsnID |
| 26–41 | 16 | psnID |
| 42–43 | 2 | bomID |
| 44–51 | 8 | ispID |
| 52–59 | 8 | firmwareID（**工厂 ID，不是 1.9.1 这种版本号**） |
| 60–63 | 4 | resolution（宽/高各 1 B） |
| 128–255 | 128 | customFields |

> 真正运行的固件版本要用 `0xA1/0x17`（HID，24 B）读，见 [ch-hid.md](ch-hid.md)。
> 界面上的 "Firmware: G25_MainFW_1.9.1" 在原厂 App 里是**硬编码文件名**，不能当证据。

---

## 7. 坑与注意事项

1. **头里的长度字段**：必须是 `16 + 像素长度`，小端 u32。写错会导致整帧被丢弃或错位。
2. **格式字节**：必须是 `1`。其它值（如 JPEG）在原厂 LCD 路径里**从未使用**，不要假设。
3. **屏 switch 状态是持久的**：原厂停止投屏会关屏，30 秒线程也会在"没在投屏"时关屏。
   发帧前先 `0xA1/0x03=1`，否则帧写进去了但面板不亮——这是"发了色条没反应"的头号原因。
4. **显示模式 ≠ 开关屏**：`0x05=2` 只是切显示模式，不负责点亮。
5. **抓图行宽有 padding**：`ImageReader` 的 `rowStride` 可能 > `width×4`。
   直接按 `w*4` 拷会得到斜纹/错位；本实现用 `plane.rowStride` + `pixelStride` 消掉。
6. **拉伸模式不清屏**：`mapStretch` 只覆写每行前 `rowBytes` 字节；当源宽 < 640 时右侧可能残留上一帧。
   正常抓取尺寸（宽=640 或高=400）下每行都是满的，不会触发；要用任意尺寸请选"适应/裁剪"。
7. **每帧 1 MB 别反复分配**：60 fps × 1 MB = 60 MB/s 的 GC 压力。本实现复用 `frameBuf`/`rgbaBuf`/行缓冲。
8. **单次 bulk 大包在部分手机上会超时**：先整帧（超时 5 s），失败自动退回 16 KB 分块。
9. **投屏与测试图互斥**：发测试图会先停投屏（否则画面立刻被下一帧覆盖）。
10. **颜色异常先查字节序**：若整屏红蓝互换，说明该机型抓图是 BGRA；换 R/B 即可（本项目未遇到，标【未】）。
11. **别碰 `0x21/0x06`**：那是写 EEPROM 产品信息，可能改掉 SN/BOM 且不可逆。

---

## 8. 待验证

- [ ] 显示模式 `0` 的确切语义（代码允许 0..2，抓包只见过 1/2）。
- [ ] `format` 字节是否存在其它合法值（JPEG/低色深）——原厂常量里有 `FRAME_FORMAT_MJPEG`，但 LCD 路径未使用。
- [ ] 亮度是否真的线性（100–254 的实际亮度曲线）。
- [ ] 不同手机屏幕上 RGBA 字节序是否一致。
- [ ] 更高帧率的可行性：单帧 30 ms 已经接近 USB 2.0 HS 上限，除非降分辨率或做差分帧（需固件配合）。
