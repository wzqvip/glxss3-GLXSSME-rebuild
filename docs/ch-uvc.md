# 第 2 章 · 摄像头（UVC）

> 本章讲清楚：怎么**不依赖任何 native 库**，用纯 Kotlin / Python 把眼镜摄像头跑起来。
> 关键结论：它是**标准 UVC 1.10 + bulk 端点 + 未压缩 YV12**；设备侧一帧 =
> `12 B header + 460800 B`，但**主机侧单次 bulk 读最多 16 KiB**（usbfs 单 URB 上限），
> 必须分块读、自己在字节流上组帧（2026-11-22 Pixel 真机定位，见 §5.3 / §7.2）。
> 之前所有"彩色噪点/花屏"都不是协议问题，而是**固件卡死时在灌无边界垃圾流**。
>
> 对应代码：[`uvc/UvcCamera.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/uvc/UvcCamera.kt)、
> [`uvc/Yuv.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/uvc/Yuv.kt)、
> [`uvc/UvcPreview.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/uvc/UvcPreview.kt)；
> Linux/Pi：[`linux/glxss/uvc.py`](../linux/glxss/uvc.py)、
> [`linux/tools/uvc_pi_exp.py`](../linux/tools/uvc_pi_exp.py)
>
> 原始抓包与排障记录：[uvc-camera-plan.md §4](uvc-camera-plan.md#4-真机抓包与修复2026-11-21已完成)；
> 固件卡死：[device-recovery.md](device-recovery.md)；选型对比：[uvc-camera-plan.md §2](uvc-camera-plan.md#2-四个方案横向对比)

---

## 1. 原理：一个"不标准但很规矩"的 UVC 摄像头

UVC 摄像头通常分两部分：

- **VC（VideoControl，if0）**：能力描述、probe/commit 协商；
- **VS（VideoStreaming，if1）**：真正出图像数据的接口 + 端点。

普通 USB 摄像头多用 **isochronous** 端点（等时传输，保证带宽、允许丢包）；这副眼镜用的是
**bulk IN（`0x81`，512 B）**——不保证带宽，但**不丢数据**，而且带宽可以吃满。

```
手机 / Pi                                               眼镜
────────                                                ────
claim if0 (VC) + if1 (VS)
   │
   ├─ GET_CUR(probe, 34B) ─────────────────────────────► 返回当前/默认参数
   ├─ SET_CUR(probe, 34B) ─────────────────────────────► 试参数
   ├─ GET_CUR(probe, 34B) ◄───────────────────────────── 看设备认可了什么
   ├─ SET_CUR(commit, 34B) ────────────────────────────► 正式生效
   │
   └─ bulkTransfer(0x81, **16384**) × N ◄────────────── 设备侧一帧 = 12B header
                                                          + 460800B YV12，主机侧
                                                          16 KiB 一块读、自己组帧
```

### 1.1 设备事实（实测，别再猜）

| 项 | 值 |
|---|---|
| 接口 | if0 `0x0e/0x01`（VC，无端点）；if1 `0x0e/0x02`（VS） |
| VS 端点 | `0x81` BULK IN，`wMaxPacketSize=512` |
| **alt setting** | **本设备 VS 只有一个 alt（alt=0）就带 `0x81`**，不需要切 alt=1 |
| UVC 版本 | `bcdUVC = 0x0110`（1.10）→ probe 结构 34 B（UVC 1.0 是 26 B） |
| 格式 | `bFormatIndex=1` → **YV12 未压缩**（`idx2` 是 MJPEG） |
| 帧档位 | 1920×1080、1280×720、640×480；实测选中 `bFrameIndex=4` → 640×480@30 |
| 实际帧率 | **29.7–30.4 fps**（2026-11-22 Pixel 实测；描述符上限 30） |
| 一帧 | `460800 = 640×480×1.5`，**无 stride padding** |
| 一帧总长 | `460812 = 460800 + 12`（12 B UVC payload header，整帧只有这 1 个） |
| 原厂 transfer | `460822 = frameSize + 22`（libusb `frameBufferSize` 日志；libusb 内部拆成 ~29 个 16 KiB URB） |
| **主机侧单次 bulk** | **16384 B 上限**（usbfs 单 URB = libusb `MAX_BULK_BUFFER_LENGTH`；460822 一次读在 Android 上立刻 -1） |

> ⚠️ 早期文档按"常见摄像头"假设了 `alt=1`、`2 B header`、`iso 端点`，全部被真机抓包推翻。
> 本文以抓包为准：[uvc-camera-plan.md §4](uvc-camera-plan.md#4-真机抓包与修复2026-11-21已完成)。

### 1.2 为什么是 YV12 而不是 MJPEG

厂商日志里有 `frameSize=(640,480)@YV12`、`frameBufferSize=460822`，而且：

- `460822 = 640×480×1.5 + 22`，只有未压缩 YUV420 才符合这个量级；YUY2 应是 614400，MJPEG 完全不该是 1.5×w×h；
- 把同一帧按 `Y|V|U`（YV12）和 `Y|U|V`（I420）各解一张，`Y|V|U` 的暖色木纹与白色纸盒与原厂预览一致，
  `Y|U|V` 整幅偏冷偏蓝（见 §7 实拍）；
- probe 回填 `bFormatIndex=1`，与 VS 描述符里的 YV12 对应。

所以：**主线按 YV12 做，MJPEG 只当备选分支**。代码运行时解析描述符，不写死。

---

## 2. 描述符与参数结构

### 2.1 接口/端点

| 接口 | Class/Subclass | 端点 | 说明 |
|---|---|---|---|
| if0 | `0x0e/0x01` | — | VideoControl；probe/commit 走这里（`wIndex` = VS 接口号 1） |
| if1 | `0x0e/0x02` | `0x81` BULK IN 512 | VideoStreaming；alt=0 即带端点 |

> `wIndex` 注意：控制请求本身是 **class interface 请求**，`wIndex` 填的是 **VS 接口号（1）**，
> 不是 VC 的 0。填错会返回 `-1`。

### 2.2 probe/commit 请求表

| 用途 | bmRequestType | bRequest | wValue | wIndex | wLength |
|---|---|---|---|---|---|
| GET_CUR probe | `0xA1` | `0x81` | `0x0100` | 1 | 26 / 34 |
| GET_MIN probe | `0xA1` | `0x82` | `0x0100` | 1 | 34 |
| GET_MAX probe | `0xA1` | `0x83` | `0x0100` | 1 | 34 |
| GET_DEF probe | `0xA1` | `0x87` | `0x0100` | 1 | 34 |
| **SET_CUR probe** | `0x21` | `0x01` | `0x0100` | 1 | **34** |
| GET_CUR commit | `0xA1` | `0x81` | `0x0200` | 1 | 34 |
| **SET_CUR commit** | `0x21` | `0x01` | `0x0200` | 1 | **34** |

### 2.3 34 B probe 结构

| 偏移 | 长度 | 字段 | 实测/建议值 |
|---|---|---|---|
| 0–1 | 2 | `bmHint` | `0x0001`（bit0 = `dwFrameInterval` 有效） |
| 2 | 1 | `bFormatIndex` | 1（YV12） |
| 3 | 1 | `bFrameIndex` | 4（640×480@30） |
| 4–7 | 4 | `dwFrameInterval` | `333333`（100 ns 单位 → 30 fps） |
| 8–9 | 2 | `wKeyFrameRate` | 0 |
| 10–11 | 2 | `wPFrameRate` | 0 |
| 12–13 | 2 | `wCompQuality` | 0 |
| 14–15 | 2 | `wCompWindowSize` | 0 |
| 16–17 | 2 | `wDelay` | 0 |
| 18–21 | 4 | `dwMaxVideoFrameSize` | `460800`（本档净荷） |
| 22–25 | 4 | `dwMaxPayloadTransferSize` | `16384`（原厂值） |
| 26–29 | 4 | `dwClockFrequency` | 0（UVC 1.0 结构到此为止，共 26 B） |
| 30 | 1 | `bmFramingInfo` | 0 |
| 31–33 | 3 | `bPreferedVersion/bMinVersion/bMaxVersion` | 0 |

实测 GET_CUR 回填（34 B，节选）：

```
01 00 | 01 | 04 | 15 16 05 00 | ... | 00 08 07 00 | 00 40 00 00
bmHint fmt  frm   333333              460800        16384
```

> `GET_MAX` 会回 `dwMaxVideoFrameSize=0x012c7f80 (19,693,440)`、`maxPayload=0x012c7f8c`
> ——这是设备/格式的**全局上限**，不是当前档位，**不能拿来当读缓冲尺寸**。
> 读缓冲按"当前档位净荷 + 12"组帧，单次 USB 读则固定 16 KiB（见 §5.3）。

### 2.4 原厂额外发的 vendor XU（不是必需）

健康设备初始化时原厂还会发（2026-11-22 Frida 抓包，Android `cc310be` 起照抄）：

```
probe 之前：  0x21/0x01  wValue=0x0d0d  wIndex=0x0400  wLength=1  data=01
commit 之后： 0x21/0x01  wValue=0x0606  wIndex=0x0400  wLength=1  data=00  ×2
```

这是相机侧 vendor 开关（entity 4）。**实测不是出流的开关**：设备在 probe/commit
**之前**就已经在吐 `0c 0f …` 数据；发不发这两条只影响"是否与原厂逐字节一致"。
Android 与 Linux（`UvcStream.vendor_pre_probe()` / `vendor_post_commit()`）都照发，
失败只记 verbose、不影响取流。

> Linux 侧**不重放**原厂序列里的 `0xA1/0x05 显示模式=1` ×2 + `0xA1/0x04 状态读`：
> 这两条属于 LCD 显示面，由 `lcd.initialize()`（开屏 + 模式 2）统一管理；在 UVC
> 打开时发模式 1 会把屏切回待机，监看模式直接黑屏。

---

## 3. UVC payload header（12 B）

每个 bulk transfer 的开头是标准 UVC 1.1 payload header：

```
0c 0f | b4 02 00 00 | b4 02 00 00 b4 02 | <460800 B YV12>
│  │    │             └ SCR（6 B，bmHeaderInfo bit3 置位时存在）
│  │    └ PTS（4 B LE，bmHeaderInfo bit2 置位时存在）
│  └ bmHeaderInfo
└ bHeaderLength = 12（2 + PTS 4 + SCR 6）
```

| 字段 | 值 | 含义 |
|---|---|---|
| `bHeaderLength` | 12 | 本 header 的字节数，合法范围 2–12 |
| `bmHeaderInfo` bit0 `FID` | 0/1 | 帧内包序号翻转；本设备每帧翻一次 |
| `bmHeaderInfo` bit1 `EOF` | 1 | **一帧结束**（本设备每帧都置位） |
| `bmHeaderInfo` bit2 `PTS` | 1 | 后面有 4 B 时间戳 |
| `bmHeaderInfo` bit3 `SCR` | 1 | 后面有 6 B 源时钟 |
| `bmHeaderInfo` bit6 `ERR` | 0 | 置位表示本帧作废 |
| PTS | `0x000002b4 = 692` | 设备时钟；下一帧 693 |
| SCR | `b4 02 00 00 b4 02` | 6 B 源时钟参考 |

抓包实录：

```
XCB#1 ep=0x81 actual=460812  head=0c 0f b4 02 00 00 b4 02 00 00 b4 02 9f a1 a4 a5 ...
XCB#2 ep=0x81 actual=460812  head=0c 0e b5 02 00 00 b5 02 00 00 b5 02 a2 a3 a3 a5 ...
                                     ↑ FID 翻转（0x0F → 0x0E），PTS 692 → 693
```

> **最容易踩的坑**：以为 header 只有 2 B（`bHeaderLength` + `bmHeaderInfo`），
> 从 byte[2] 开始取像素。实际有 12 B，错 10 个字节 → 整帧斜向错位、颜色像彩虹噪点。
> 判据很简单：`buf[0] == 0x0C` 且 `buf[1] & 0x02 != 0`。

---

## 4. YV12 布局

```
偏移 0                    307200              384000            460800
     ┌───────────────────┬──────────────────┬──────────────────┐
     │ Y  平面 640×480    │ V 平面 320×240    │ U 平面 320×240    │
     │ 1 B / 像素         │ 1 B / 2×2 像素    │ 1 B / 2×2 像素    │
     └───────────────────┴──────────────────┴──────────────────┘
      = Y | V | U（YV12）        ← 不是 I420（I420 是 Y | U | V）
```

| 平面 | 偏移 | 长度 | 采样 |
|---|---|---|---|
| Y | 0 | `640×480 = 307,200` | 每像素 1 B |
| V (Cr) | 307,200 | `320×240 = 76,800` | 2×2 共享 |
| U (Cb) | 384,000 | `320×240 = 76,800` | 2×2 共享 |
| 合计 | — | **460,800** | 无 padding、无 stride |

转换公式（BT.601 limited range，整数运算，见
[`Yuv.yuv420ToArgb()`](../app/app/src/main/java/com/wzqvip/glxsscast/uvc/Yuv.kt#L23-L47)）：

```kotlin
val y = (buf[yBase + i].toInt() and 0xff) - 16
val u = (buf[uOff + cBase + (i / 2)].toInt() and 0xff) - 128
val v = (buf[vOff + cBase + (i / 2)].toInt() and 0xff) - 128
val c = if (y < 0) 0 else y
val r = (298 * c + 409 * v + 128) shr 8
val g = (298 * c - 100 * u - 208 * v + 128) shr 8
val b = (298 * c + 516 * u + 128) shr 8
```

`uOff` / `vOff` 由格式决定：

```kotlin
// UvcCamera.yv12ToBitmap()
val ySize = w * h
val cSize = ySize / 4
val vOff = if (uFirst) ySize + cSize else ySize   // YV12: V 在前；I420: U 在前
val uOff = if (uFirst) ySize else ySize + cSize
Yuv.yuv420ToArgb(argb, src, uOff, vOff, w, h)
```

---

## 5. 驱动步骤（与代码一一对应）

```
1. 找设备 2E09:0030，拿 USB 权限
2. openDevice（可复用 UsbLink 的连接）
3. claim if0 (VC)            ← 与原厂 libuvc 对齐；部分设备要求先有 VC
4. claim if2 (HID)           ← 必须；不 claim 时部分固件不出流（Android 也做）
5. claim if1 (VS alt=0)
6. setInterface(VS alt=0)    ← 返回 false 也可能已生效；**alt=0 也要发**
7. 解析 rawDescriptors → VS_FORMAT_* / VS_FRAME_*
8. GET_CUR(probe) 拿基底 → 填 fmt/frame/interval/maxFrame（`18..21`）
9. SET_CUR(probe) → GET_CUR(probe) 读回 → SET_CUR(commit)
10. 组帧缓冲 = frameSize + 12 + 一个 chunk；**单次 bulk 读 = 16384 B**（§5.3）
11. 线程循环 bulkTransfer(0x81, 16384) → 按 §6 组帧
12. 停止：setInterface(alt0) → CLEAR_FEATURE(ENDPOINT_HALT, 0x81) → releaseInterface → close
```

### 5.1 选接口与端点

```kotlin
// uvc/UvcCamera.kt —— 找 VS 里带 bulk IN 的 alt setting
for (i in 0 until dev.interfaceCount) {
    val itf = dev.getInterface(i)
    if (itf.interfaceClass != 0x0e || itf.interfaceSubclass != 0x02) continue
    if (itf.alternateSetting == 0 && (zeroAlt == null || zeroAlt!!.id == itf.id)) {
        zeroAlt = itf
    }
    for (e in 0 until itf.endpointCount) {
        val ep = itf.getEndpoint(e)
        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
            ep.direction == UsbConstants.USB_DIR_IN
        ) { foundAlt = itf; foundEp = ep }
    }
    if (foundAlt != null) break
}
```

> 本设备 `foundAlt.alternateSetting == 0`，所以代码不强行 `alt=1`；
> 有些机型要求"先 claim alt0 再切 alt1"，所以 `setInterface` 失败时只记日志、继续跑。

### 5.2 probe/commit（仓库真实代码，节选）

```kotlin
// uvc/UvcCamera.kt
var probe = ByteArray(34)
var len = c.controlTransfer(REQ_IN, UVC_GET_CUR, VS_PROBE, ifaceNum, probe, 0, probe.size, CTRL_TIMEOUT)
if (len <= 0) len = c.controlTransfer(REQ_IN, UVC_GET_DEF, VS_PROBE, ifaceNum, probe, 0, probe.size, CTRL_TIMEOUT)
if (len <= 0) { probe = ByteArray(34); len = 34 }
if (len < 26) len = 34

probe[0] = 1                       // bmHint = 1
probe[1] = 0
probe[2] = f.index.toByte()        // bFormatIndex
probe[3] = fr.index.toByte()       // bFrameIndex
putU32(probe, 4, fr.defaultInterval)
putU32(probe, 18, max(fr.maxFrameSize, estimateFrameBytes(f.mapping, fr.width, fr.height)))
val payload = if (vsEpIn != null) max(16384, vsEpIn!!.maxPacketSize * 32) else 16384
putU32(probe, 22, payload)         // dwMaxPayloadTransferSize

var ret = c.controlTransfer(REQ_OUT, UVC_SET_CUR, VS_PROBE, ifaceNum, probe, 0, len, CTRL_TIMEOUT)
if (ret < 0 && len > 26) {         // 回退 UVC 1.0 的 26 B
    ret = c.controlTransfer(REQ_OUT, UVC_SET_CUR, VS_PROBE, ifaceNum, probe, 0, 26, CTRL_TIMEOUT)
}

val back = ByteArray(34)
val n = c.controlTransfer(REQ_IN, UVC_GET_CUR, VS_PROBE, ifaceNum, back, 0, back.size, CTRL_TIMEOUT)
val commitSrc = if (n > 0) back else probe
ret = c.controlTransfer(REQ_OUT, UVC_SET_CUR, VS_COMMIT, ifaceNum, commitSrc, 0, len, CTRL_TIMEOUT)
```

> 最强诊断行是 **`probe result:`**——它告诉你设备最终认可了哪个 format/frame/maxFrame。
> 失败时按这个顺序降级：34 B → 26 B → `bmHint=0` → 改 `dwMaxPayloadTransferSize`。

### 5.3 收流：单次 bulk 读上限 16 KiB（本轮真正的坑）

**现象**（Android，2026-11-22）：probe/commit 全部成功（`rc=34`），但读线程里
`bulkTransfer(ep, buf, 460822, 1000)` **立刻返回 -1**（dt≈0/几十 ms，不是 1000 ms
超时）；同一个端点换成 512 / 16384 都能拿到数据，首字节就是 `0c 0f`。

**原因**：Android/Linux 的 usbfs 单次 bulk URB 上限是 **16384 B**（与 libusb 的
`MAX_BULK_BUFFER_LENGTH` 一致）。原厂 libusb 之所以能写 `frameBufferSize=460822`，
是因为 **libusb 内部把它拆成 ~29 个 16 KiB URB 再重组**，对 App 隐藏了拆分；
`UsbDeviceConnection.bulkTransfer` / 一次 `dev.read(…, 460822)` 不拆，直接交给内核
→ `-EINVAL`（Android 表现为 -1）或设备侧一直 NAK。

**修法**：读循环每次只读 **16384**，用"一帧 + 一个 chunk"的 `rx` 缓冲做字节流组帧
（§6）。改完后 Pixel 上稳定 **29.7–30.4 fps、4900 帧 0 无效 URB**；Pi 侧同样改成
`BULK_CHUNK=16384`（`linux/glxss/uvc.py`），不再依赖 libusb 拆 URB 的后端行为。

> 与"URB 尺寸 = frameSize+22"不矛盾：460822 是**协议层面的逻辑帧长**
> （原厂 `frameBufferSize`），16 KiB 是**主机侧单次 USB 请求**的限制。
> 一个用于组帧/日志，一个用于读。

---

## 6. 帧同步算法（16 KiB 分块 + 字节流组帧）

设备侧行为（Pixel 实测 + 原厂抓包一致）：

- 一帧 = **12 B header + 460800 B 净荷**，**整帧只有 1 个 header**，
  `bmHeaderInfo` bit1(EOF)=1（样本 `0c 0f d9 75 00 00 d9 75 00 00 d9 75`）；
- header 之后是连续净荷，后续 16 KiB 块里没有 header（首字节直接是亮度值）；
- 帧与帧首尾相接，没有 gap。

读循环（Android [`readLoop()`](../app/app/src/main/java/com/wzqvip/glxsscast/uvc/UvcCamera.kt)，
Linux [`UvcStream.read_frame()`](../linux/glxss/uvc.py)）：

```
循环:
  chunk = bulkTransfer(ep, 16384, timeout=1000)
  if chunk < 0:
      若耗时很短（< 500 ms）→ fastFails++；连续 5 次 → 报错退出（设备拔了/接口被抢）
      否则是"设备在线但没数据" → 限流日志，继续
  rx += chunk
  解析 rx:
      未同步:
          逐字节找 header（0x0C/长度 2..12 + EOF=1 + ERR=0 + SCR/PTS 自洽）
          找到前的字节全部丢弃（流可能从帧中间开始）
      已同步:
          若已收满 hdrLen + frameSize → 整块交帧；否则等下一块
      帧起点 header 不合法 → 丢 1 字节重新同步
```

三条设计原则：

1. **只认合法 header**，且用 header 自身的冗余位做校验：
   `bHeaderLength ∈ 2..12`、EOF=1、ERR=0、带 SCR 时 `SCR[0..1] == PTS[0..1]`
   （实测 `0c 0f d9 75 00 00 d9 75…`）。误判概率 ~1e-10，远低于"扫 0x0C"
   （后者在 460 KB 里平均会误判十几次）。
2. **未同步时丢弃，绝不追加**。流可能从帧中间开始（打开摄像头时设备已经在发），
   第一个合法 header 之前的数据必须丢，否则整帧永远错位。
3. **异常流要有上限**：连续丢弃超过 4 MB 还没有合法 header，就报"固件媒体流水线
   卡死，拔插重灌"，而不是让 UI 一直显示 "device idle"（Android 侧）；
   Linux 侧 `read_frame()` 超时/积压时会带上"已同步帧数 / 丢弃字节 / 缓冲字节"。

> 当前实现**没有用 FID 做连续性校验**。如果将来遇到"每帧固定偏移几字节"的设备，
> 可以加：同一帧内 FID 应保持不变，变了但没见 EOF → 丢弃当前帧重新同步。

> 当前实现**没有用 FID 做连续性校验**。如果将来遇到"每帧固定偏移几字节"的设备，
> 可以加：同一帧内 FID 应保持不变，变了但没见 EOF → 丢弃当前帧重新同步。

---

## 7. 实测数据

| 项 | 值 |
|---|---|
| 协商结果 | `format=YV12(idx=1) 640x480 frameIdx=4 maxFrame=460800B interval=333333(=30.0fps)` |
| 缓冲 | `frameSize=460800 chunk=16384 rxBuf≈477 KB urbSize=460822（仅日志）` |
| 单次 bulk | `n = 16384`（分块上限），一帧由 ~29 块组成（`460812 = 12 + 460800`） |
| 帧率（Pixel） | **29.7–30.4 fps**，首秒 17.8（组帧/同步）；4900 帧 0 无效 URB |
| 帧率（Pi） | 见 §7.2（16 KiB 分块后待真机复测） |
| 解码 | YV12→ARGB 纯 Kotlin 约 8–15 ms/帧；Bitmap A/B 双缓冲，每帧零分配 |
| 并发 | 投屏 25 fps 同时 UVC 24.6 fps（实测不互斥） |
| 原始抓包 | 30 个 URB × (460812 + 4 B 长度前缀) = 13,824,480 B，与设备文件大小一致 |


### 7.1 Linux / Pi 实测（2026-11-22，Raspberry Pi Zero 2 W + pyusb 1.2.1）

Android 侧的打开方式在 Pi 上**必须显式写回**，否则会踩到下面几个坑（均已改入
`linux/glxss/uvc.py` / `linux/glxss/usb.py`）：

| 坑 | 实测现象 | 修法 |
|---|---|---|
| GET_CUR 残留档位 | `pi/stream.py --check --fps 30` 回读 `bFrameIndex=1`（1080p）、`dwMaxPayloadTransferSize=19693452`（GET_MAX 全局上限）；bulk IN 直接 `[Errno 110]` | probe 固定写 `format=1 / frame=4 / interval=333333 / maxFrame=460800 / maxPayload=16384`，不再继承 GET_CUR |
| 读尺寸 | 旧代码把读缓冲算成 65536（`min(64 KiB, frameSize+22)`），不是原厂的 460822；而且一次读整帧 | `frameSize+22=460822` 只作逻辑帧长/日志；实际每次读 **16384** 再组帧（§7.2，与 Android `cc310be` 一致） |
| SET_INTERFACE 从未发出 | pyusb 1.2 **没有** `Device.set_interface`：旧代码调用抛 AttributeError 被吞成“失败（继续）”，等于没发；而 Android 每次都发（alt=0 也发） | `usb.set_alt()` 用 `Device.set_interface_altsetting()`，失败再退回 `0x01/0x0B` 标准控制请求 |
| 缺 HID claim | Pi 只 claim 了 VC/VS | `UvcStream.open()` 按 **VC(if0) → HID(if2) → VS(if1)** claim（与 `UvcCamera.prepare()` 逐行一致），stop 时对 0x81 补 CLEAR_FEATURE(HALT) |

Pi 上已验证可用的并发面（同一个 handle，控制面走 `usb.UsbGate` 串行）：

- HID 轮询 `0xA1/0x16`（28 B）正常，实测返回 `00 71 …`（Fn 键未按）；
- LCD 初始化（`0xA1/0x03` 开屏 + `0xA1/0x05` 模式 2）与 1 MB bulk OUT 正常，
  实测约 22–24 MB/s；
- 两者可与 UVC bulk IN 同时跑（对应 Android 的「投屏 + HID 轮询 + 取流」）。

**实测结论（2026-11-22）：主机侧已对齐，但设备侧仍 0 帧。**

重新插拔（眼镜冷启动 → `03E7:2150` → udev 自动灌 `G25_MainFW_1.9.1.img`，
md5 `9cea02e4…` 与 APK 内置一致 → `2E09:0030`）之后，新的打开流程实测为：

```
claim if0 / if2 / if1 全 ok
SET_INTERFACE if1 alt=0 ok          ← 旧代码从未真正发出
CLEAR_FEATURE(HALT) 0x81 ok
GET_CUR(probe) = 01 00 | 01 | 04 | 333333 | … | 460800 | 16384   ← 与 Android 完全一致
SET_CUR(probe)=34  GET_CUR=34  SET_CUR(commit)=34
```

但 bulk IN 依旧一个字节都不来（`Errno 110`）。usbmon 证实**不是主机没发**：

```
S Bi:1:003:1 -115 16384 <        ← usbfs 把 460822 B 请求拆成 16 KiB URB 提交
C Bi:1:003:1 -2 0                ← 设备一直 NAK，最后被 cancel（不是主机错误）
```

逐项排查都无效（全部 0 帧）：

| 尝试 | 结果 |
|---|---|
| HID `0xA1/0x16` 300 ms + LCD 1 MB 帧并发（复刻 Android） | 0 帧 |
| `--detach` / 不 detach、`dev.reset()`、CLEAR_HALT | 0 帧 |
| 厂商 XU `0x21/0x01 0x0d0d 0x0400 = 01` | ACK=1，仍 0 帧 |
| `0xA1/0x18` 系统复位 → 03E7 → 重灌固件（两次，均写满 1,525,136 B） | 0 帧 |
| 逐档 probe（frame 1…5）+ commit 4（原厂 libuvc 也逐轮 probe） | 0 帧 |
| MJPEG（format=2）各帧档 | probe/commit 成功，0 帧 |
| 原厂顺序：开屏 → 显示模式 **1** → 黑帧 → XU → VC → VS → probe/commit | 0 帧 |

控制面全部健康（版本 `G25_MainFW_1.9.1`、0x04/0x05/0x06 传感器属性、LCD 8 B 状态、
1 MB bulk OUT 正常），只有**媒体数据面没有数据**——与
[device-recovery.md §1](device-recovery.md#1-三态判定表30-秒分清) 的 **状态 B
（固件媒体流水线卡死）** 完全一致；按该文档记录，这种状态下原厂 App 同样 0 帧。

⇒ 结论：Pi 侧打开顺序/参数已不再是阻塞点，剩下的是**设备侧 camera 流水线
（IMX214）没起来**。还需要一次设备侧确认：同一副眼镜在 Android 上复测
（若 Android 也 0 帧，就是设备/相机硬件或固件状态，需要更彻底的断电冷却或返修）。

> **2026-11-22 更新**：同一副眼镜在 Pixel 上复测时发现 Android 侧真正的坑是
> **usbfs 单次 bulk 读上限 16 KiB**（§5.3）。Pi 的 usbmon 里 `S Bi:… 16384`
> 说明 Linux libusb 已经替我们拆了 URB，所以 Pi 上"460822 一次读"不是被拒，
> 而是设备侧没有数据（状态 B）。两件事叠加：Android 修好分块后设备恢复出流，
> Pi 侧再把读路径显式改成 16 KiB（§7.2），主机行为就与 Android 完全一致。

逐项对照用 `linux/tools/uvc_pi_exp.py`（默认就是 16 KiB 分块 + 组帧）：

```bash
python3 tools/uvc_pi_exp.py --chunk 16384 --frame 4 --interval 333333 --hid --lcd --seconds 10
python3 tools/uvc_pi_exp.py --chunk 460822       # 复现“一次读整帧”（usbfs 拒绝 / 设备 NAK）
python3 tools/uvc_pi_exp.py --chunk 65536        # 复现旧实现的 64 KiB 读缓冲
```

实拍对比（同一时刻）：

| 我们 App（修复后） | 原厂 App |
|---|---|
| ![ours](img/uvc-ours-preview.jpg) | ![vendor](img/uvc-vendor-preview.jpg) |

同一帧按两种 U/V 顺序解码——只有左边与原厂画面一致：

| YV12（Y\|V\|U，正确） | I420（Y\|U\|V，整体偏冷） |
|---|---|
| ![yv12](img/uvc-frame-yv12.jpg) | ![i420](img/uvc-frame-i420.jpg) |

恢复后 UVC 25 fps + 投屏并发：

![recovered](img/uvc-ours-25fps.jpg)

### 7.2 Linux / Pi 移植：16 KiB 分块 + 字节流组帧（2026-11-22）

把 Android `cc310be` 的修复原样搬到 `linux/glxss/uvc.py`：

| 改动 | 内容 |
|---|---|
| 读尺寸 | `BULK_CHUNK = 16384`；`UvcStream.read_chunk()` 每次只读一个块，**绝不超过 16 KiB**。`urb_size = frameSize + 22 = 460822` 只保留为逻辑帧缓冲/日志 |
| 组帧 | `read_frame()` 把块追加进 `_rx`，`_take_frame()` 按 `hdrLen + frameSize` 切帧；`_header_ok()` 校验 `bHeaderLength 2..12 + EOF=1 + ERR=0 + SCR[0..1]==PTS[0..1]`；未同步时丢到下一个合法 header |
| XU | `negotiate()` 补原厂 vendor 序列：pre-probe `0x0d0d=01`，commit 后 `0x0606=00 ×2` + CLEAR_HALT(0x81)；失败不影响取流 |
| 实验台 | `linux/tools/uvc_pi_exp.py` 默认 `--chunk 16384`，同样组帧统计（帧数/fps/丢弃字节） |
| 测试 | 新增 `UvcChunkFramingTests`：460800 B 生产帧由 29 个 ≤16384 块拼出、header 跨块、假 header 重同步、超时诊断 |

离线单测（Windows / Pi 均可跑）：

```bash
cd linux && python -m unittest discover -s tests -v
# 45 tests OK（含 640×480 生产尺寸组帧用例）
```

Pi Zero 2 W 上的离线组帧基准（假设备按 16 KiB 返回 6 帧连续数据，跑 3 帧）：
**5.1 ms/帧、~196 fps 组帧吞吐、0 丢弃字节**，远高于 30 fps 目标——组帧不是瓶颈。

Pi 真机验证（眼镜插回 Pi 后；当前设备不在 Pi 上，尚未复测）：

```bash
sudo python3 pi/stream.py --check --fps 30 --detach
python3 -m glxss cast --source uvc --fps 10 --duration 10 --detach --rotate 180
sudo python3 pi/stream.py --rtsp rtsp://127.0.0.1:8554/glxss --fps 20   # 另开 ffprobe 验证
```

> Linux 的 libusb 本来就会把 460822 拆成 16 KiB URB（usbmon 可见 `S Bi:… 16384`），
> 所以这一步在 Pi 上**不会改变 USB 线上看到的 URB 大小**；它的价值是：不再依赖
> libusb 后端的拆分行为、与 Android 共用同一套组帧/重同步逻辑、并且在任何
> 只支持单 URB ≤16 KiB 的 usbfs 上都成立（Android 就是被这个上限挡住）。

---

## 8. 坑与注意事项

1. **header 是 12 B 不是 2 B**。按 2 B 取像素 = 整帧斜移 10 B，看起来像"彩虹噪点"。
2. **单次 bulk 读必须 ≤ 16384 B**（usbfs 单 URB 上限 = libusb
   `MAX_BULK_BUFFER_LENGTH`）。超过在 Android 上不会超时，而是**立刻 -1**（`dt≈0`），
   很容易误判成"设备没出流 / 接口被抢"；原厂 libusb 的 `460822` 是它内部拆 URB，
   不能直接照抄到一次 `bulkTransfer` / `dev.read`。读 16 KiB、按
   `hdrLen + frameSize = 460812` 组帧。
3. **不要在未同步时追加数据**。流从帧中间开始时，追加 = 永久错位。
   同步时用 header 的 `SCR[0..1]==PTS[0..1]` 做校验，别只扫 `0x0C`。
4. **U/V 顺序**：YV12 是 `Y|V|U`；I420 是 `Y|U|V`。偏色先换这个，不要先怀疑白平衡。
5. **alt setting 别照抄教程**：本设备 alt=0 就带端点；强行 `setInterface(alt=1)` 可能直接失败。
   但 **alt=0 的 SET_INTERFACE 也要发**（Android 每次都发，用于复位端点状态）。
6. **claim 顺序**：VC(if0) → **HID(if2)** → VS(if1)，全部 force claim（Linux 用 `--detach`）。
   HID 不 claim 时部分固件不出流，且后面读版本会 -1（见 [ch-hid.md](ch-hid.md)）。
7. **`setInterface` 返回 false 不等于没生效**：日志记录后继续跑，真没数据再排查。
   Python/pyusb 侧注意：没有 `Device.set_interface`，要用
   `Device.set_interface_altsetting(iface, alt)`（见 §7.1）。
8. **Android 的 `bulkTransfer` 用 -1 表示"超时"和"设备错误"两种事**：用耗时区分，
   否则拔线后会变成 100% CPU 热循环（实测 3 秒 1500 万次日志）。
9. **不要同时开两个 App 抢 UVC**：原厂 App 和我们 App 同时 claim 会互相踢掉 alt，表现为 0 帧。
10. **固件半死时 UVC 一定 0 帧**，而控制面/状态查询都正常。别改代码，先按
    [ch-firmware.md §7](ch-firmware.md#7-半死现象与恢复-sop) 重灌。
11. **1080p 档位**：净荷 3,110,400 B；20 fps 就是 62 MB/s，已经超过 USB 2.0 HS 的实际上限，
    还要和投屏抢带宽。当前代码支持改 `TARGET_W/H`，但没有实测，标【未】。
12. **厂商宣称的 800 万像素拍照 / 720p 录像不是这条 UVC 流**（见 [apk-versions.md §6](apk-versions.md#6-代码功能差异184--237)），
    很可能走另一条 vendor 通道，未逆向。

---

## 9. 待验证

- [ ] 1920×1080 / 1280×720 档位的实际帧率与 URB 尺寸（当前只跑 640×480）。
- [ ] MJPEG（`bFormatIndex=2`）是否真的可用，以及它的 `dwMaxVideoFrameSize`。
- [ ] `bmFramingInfo` / FID 在长帧（>1 个 URB）时是否需要参与组帧。
- [ ] 原厂 XU `0x21/0x01 wValue=0x0d0d / 0x0606` 的真实作用（Android 与 Linux 已照抄发送；
      对照实验发/不发都能出流，见 §2.4）。
- [ ] 相机自动对焦 / 曝光控制（2.3.7 有 `nativeGetmAutoFocus`，UVC PU 控制未试）。
- [ ] 8 MP 静像与 720p 录像的数据通道。
