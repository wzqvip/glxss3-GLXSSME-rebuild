# 第 3 章 · HID

> 本章讲清楚：眼镜的 HID 接口里到底有什么、28 B 报告解到哪一步、24 B 版本串怎么读，
> 以及**为什么必须先 claim 接口**（不 claim 就一律返回 -1）。
>
> 对应代码：[`usb/UsbLink.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/usb/UsbLink.kt)、
> [`usb/Peripherals.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/usb/Peripherals.kt)、
> [`lcd/LcdControl.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/lcd/LcdControl.kt)
>
> 原始数据：[usb-capture.md §3](usb-capture.md#3-控制传输全部实测)；
> 请求全枚举：[firmware-extraction.md §3.2](firmware-extraction.md#32-全部-vendor-control-请求枚举没有读-flash内存)

---

## 1. 原理：HID 接口承担三件事

| 职责 | 通道 | 状态 |
|---|---|---|
| 按键 / 触摸 / 传感器上报 | 28 B class 控制请求 `0xA1/0x16`（另有一个中断 IN 端点 `0x83`） | ⚠️ 数据能拿，语义未全解 |
| 真实固件版本 + 设备 ID | 24 B `0xA1/0x17` | ✅ 可用 |
| LED / 自动亮度 / 传感器配置 | `0xA1/0x10`、`0x18`、`0x19`、`0x11`–`0x15` | 已知命令，未全部实测 |

HID 接口本身是标准 `class 0x03`，带一个中断 IN 端点；但厂商**没有**让 App 去读中断端点，
而是用 **class control 请求**轮询（原厂约 300 ms 一次，即 ~3–5 次/秒）。
中断端点 `0x83` 仍然存在，可以用来做事件式读取（本仓库 `Peripherals.startHid()` 走的就是它）。

```
if2  class 0x03  ep 0x83 INT IN 64 (interval 8)
  │
  ├─ control IN 0xA1/0x16  wIndex=2  len=28   ← 原厂轮询，稳定拿到报告
  ├─ control IN 0xA1/0x17  wIndex=2  len=24   ← 版本 + 设备 ID
  └─ bulk/interrupt IN ep 0x83                ← 本仓库可选路径（待验证）
```

---

## 2. 接口与 claim 语义（最重要的一节）

### 2.1 不 claim 就 -1

在 Android 上，`hid-generic` 内核驱动会先占用 HID 接口。此时：

- 枚举正常；
- 但 `0xA1/0x16`、`0xA1/0x17` **全部返回 -1**（`controlTransfer` 失败）；
- dmesg 里持续出现 `input irq status -75`（EOVERFLOW）。

一旦 `claimInterface(hid, force = true)`：

```
修复前：读取真实固件版本 → "读固件版本失败"   (0xA1/0x17 ret=-1)
修复后：读取真实固件版本 → "G25_MainFW_1.9.1" (ret=24)
```

原厂 App 冷启动时也是先 `CLAIM iface=2` 再发 `0xA1/0x16`（见
[device-recovery.md §4](device-recovery.md#4-原厂-app-的完整初始化序列健康设备hook-libusb100so)），完全一致。

### 2.2 claim 是"按 fd"的，不是全局锁

这是本项目踩过的最隐蔽的坑：

1. `UsbLink` 用自己的连接 claim 了 HID → `0x17` 正常；
2. `UvcCamera` 用**另一个 fd** 打开设备，也 `claimInterface(hid, force=true)`；
   usbfs 会把接口从 `UsbLink` 的连接上**抢走**；
3. 关掉 UVC 后，`UsbLink` 的 `0x17` 又变回 -1。

解决办法：**每次读版本/轮询前重新 claim**：

```kotlin
// usb/UsbLink.kt
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

### 2.3 dmesg 里的 `-75` 是正常的

每次枚举后 `hid-generic` 会打 4–6 条 `input irq status -75`，**健康设备也有**。
它不是固件挂掉的证据（早期文档曾误判，已在
[device-recovery.md §2.4](device-recovery.md#24-dmesg-里的-input-irq-status--75-是正常现象别误判) 更正）。

---

## 3. 请求总表

全部是 `bmRequestType = 0xA1`（Device-to-Host | Class | Interface），`wIndex = 2`。
长度和用途来自 `libhid.so` 的静态枚举 + 抓包交叉核对。

| 请求 | wValue | wLength | 用途 | 状态 |
|---|---|---|---|---|
| `0xA1/0x04` | 0 | 12 | 传感器数据 | 未解 |
| `0xA1/0x05` | 0 | 12 | 传感器数据 | 未解 |
| `0xA1/0x06` | 0 | 16 | 原厂用于 HID 描述符 / 传感器数据（语义未定） | 未解 |
| `0xA1/0x10` | 0/1 | 1 | 自动亮度开关 | 已用（`LcdControl.setAutoBrightness`） |
| `0xA1/0x11` | 0 | 1 | 传感器开关 | 未用 |
| `0xA1/0x12` | 0 | 1 | 传感器开关 | 未用 |
| `0xA1/0x13` | 0 | 1 | 传感器量程 | 未用 |
| `0xA1/0x14` | 0 | 1 | 传感器量程 | 未用 |
| `0xA1/0x15` | 0 | 1 | 传感器配置 | 未用 |
| `0xA1/0x16` | 0 | **28** | HID 传感器报告 | ⚠️ 部分解出 |
| `0xA1/0x17` | 0 | **24** | **版本串 + 设备 ID** | ✅ 可用 |
| `0xA1/0x18` | 0 | 1 | 配置 | 未用 |
| `0xA1/0x19` | 0/1 | 1 | LED 指示灯 | 已用（`LcdControl.setLed`） |
| `0xA1/0x1A` | 0 | 2 | 状态 | 未解 |

> 表里没有"读 flash / 读内存"类请求；HID 侧最大读取就是 28 B 报告和 264 B EEPROM（LCD 侧）。
> 这也是"固件无法从设备回读"的证据之一，见 [firmware-extraction.md](firmware-extraction.md)。

---

## 4. 28 B 报告：解到哪一步

实测样本（`0xA1/0x16`）：

```
00 71 50 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 41 1b 72 42
    ▲  ▲                                                                 ▲────────▲
    │  └ byte[2] 递增计数（0x50 → 0x65 …）                              最后 4 B 疑似时间戳
    └ byte[1] = 0x71（固定？）
```

| 偏移 | 长度 | 当前结论 | 置信 |
|---|---|---|---|
| 0 | 1 | 固定 `0x00`（report ID？） | 【验】样本一致 |
| 1 | 1 | `0x71`，含义未知（可能是报告类型/长度/标志） | 【未】 |
| 2 | 1 | **递增计数器**（每包 +1，溢出回绕） | 【验】 |
| 3–23 | 21 | 疑似若干传感器样本，**未解** | 【未】 |
| 24–27 | 4 | 疑似时间戳（`41 1b 72 42`），与系统 uptime 的关系未验证 | 【推】 |

### 4.1 为什么只解出这么点

- 厂商 App 对这些字段的用法是**不透明**的：`SensorService` 直接把 byte[] 交给上层，
  Java 代码里没有逐字段解析（jadx 里也找不到 struct 定义）；
- `0xA1/0x04`、`0x05`、`0x06` 还各有一份 12/12/16 B 的数据，说明传感器信息被拆成了多条请求，
  28 B 报告可能只是其中一部分；
- 固件 strings 里有加速度/陀螺/光感相关的驱动名，但没有报告布局。

### 4.2 怎么继续解（建议）

1. 静止平放 10 秒，记录 200 个包，看哪些字段**恒定**（零偏/量程）；
2. 绕三个轴分别旋转，看哪两组 int16 呈正弦变化 → 陀螺；
3. 快速平移/倾斜，看另一组 → 加速度；
4. 遮住光感/改变环境光，看哪个字节跳变；
5. 按 `int16 LE` 切成若干通道，与 Android `SensorManager` 同姿态数据对齐。
   （本项目**没有**做这一步，28 B 的语义仍是开放的。）

---

## 5. 24 B 版本查询（✅ 可用）

`0xA1/0x17`，`wIndex=2`，`wLength=24`：

```kotlin
// lcd/LcdControl.kt
fun getFirmwareVersion(conn: UsbDeviceConnection): String? {
    val buf = ByteArray(24)
    val n = conn.controlTransfer(REQ_CTRL, 0x17, 0, IFACE_AUX, buf, 24, TIMEOUT)
    if (n <= 0) return null
    val s = String(buf, 0, minOf(16, n), Charsets.US_ASCII)
    return s.trim('\u0000', ' ', '\n')
}
```

返回布局（`libhid.so` 的 `sensor_get_product_info` 反汇编 + 实测）：

| 偏移 | 长度 | 内容 |
|---|---|---|
| 0–15 | 16 | ASCII 版本串，实跑为 `G25_MainFW_1.9.1`（正好 16 字符） |
| 16–23 | 8 | 设备 ID（两个 u32，拼成 64 位 `glxssId`） |

> 这是**唯一可靠的"设备当前在跑哪版固件"来源**。
> 原厂主界面那个 "Firmware: G25_MainFW_1.9.1" 是硬编码 asset 文件名，不是从设备读的；
> LCD 侧的 `firmwareID`（EEPROM 偏移 52–59）是 8 字符工厂 ID，也不等于语义版本。

---

## 6. 驱动步骤

```
1) 拿到 UsbDeviceConnection（可复用 UsbLink.connection），确认设备是 2E09:0030
2) claimInterface(if2, force = true)        ← 必须；否则下面全部返回 -1
3) （可选）0xA1/0x17 wIndex=2 wLen=24 读版本串 + 设备 ID
4) 轮询 0xA1/0x16 wIndex=2 wLen=28           ← 原厂约 300 ms 一次
   或尝试读中断端点 0x83（实验性，见 §7）
5) 解析：byte[2] 计数器；其余字段待解（见 §4）
6) 任何请求返回 -1 → 先 ensureHid() 重新 claim，再重试
7) 关闭 UVC 之后也要 ensureHid()（它会抢走 HID）
```

## 7. 中断端点读取（可选路径）

`Peripherals.startHid()` 用 `openHidEndpoint()` 找到 `0x83`，然后循环读：

```kotlin
// usb/Peripherals.kt
val ep = link.openHidEndpoint()
if (ep == null) { lastHidHex = "no HID endpoint"; return }
hidRunning = true
val t = Thread {
    val buf = ByteArray(64)
    while (hidRunning) {
        val n = try { link.readInterrupt(ep, buf, 500) } catch (e: Exception) { -1 }
        if (n > 0) {
            hidPackets++
            val sb = StringBuilder()
            for (i in 0 until n) sb.append(String.format("%02x", buf[i]))
            lastHidHex = sb.toString()
        } else Thread.sleep(50)
    }
}
```

`readInterrupt()` 实际调用的是 `UsbDeviceConnection.bulkTransfer(ep, ...)`：

```kotlin
fun readInterrupt(ep: UsbEndpoint, buf: ByteArray, timeoutMs: Int): Int =
    conn?.bulkTransfer(ep, buf, buf.size, timeoutMs) ?: -1
```

> **待验证**：Android 没有独立的 `interruptTransfer` API，社区常用 `bulkTransfer` 或
> `UsbRequest.queue()` 读中断端点。前者在本设备上是否稳定收到包，本项目**没有留下实测记录**；
> 更稳的做法是用 `UsbRequest`（`UsbRequest().initialize(conn, ep)` + `queue()/requestWait()`）。
> 已知可靠的是 control 轮询 `0xA1/0x16`，UI 的「开始 HID 监听」属于实验功能。

---

## 8. 实测数据

| 项 | 值 |
|---|---|
| `0xA1/0x17` 未 claim | `-1` |
| `0xA1/0x17` 已 claim | `ret=24`，版本 `G25_MainFW_1.9.1` |
| `0xA1/0x16` 频率 | 原厂约 300 ms 一次（~3.3 次/秒）；抓包描述约 5 次/秒 |
| `0xA1/0x16` 计数器 | `byte[2]` 逐包 +1（样本 `0x50` → `0x65`） |
| `0xA1/0x16` 尾部 | 最后 4 B 每包变化，疑似时间戳 |
| 枚举后 dmesg | `input irq status -75` ×4–6，**正常** |

---

## 9. 坑与注意事项

1. **必须先 claim HID 接口**，否则所有 HID class 请求返回 -1。
2. **claim 按 fd 生效**：UVC 用另一个连接 force claim 会抢走；关掉 UVC 后要 `ensureHid()`。
3. **不要和 Linux 的 `hid-generic`/`hidraw` 抢**：在 PC 上调试时设备可能被内核接管，
   需要 detach 或用 libusb 的 auto-detach（仅主机侧）。
4. **`input irq status -75` 不是故障**，别据此判断固件挂了。
5. **28 B 不是"传感器完整快照"**：还有 0x04/0x05/0x06 三条请求，别只抓一条就下结论。
6. **`0xA1/0x17` 的 16 B 版本串不带 NUL**：正好 16 字符，读出来要自己 trim。
7. **HID 报告里的计数器会回绕**（u8），丢包判断不能只看计数器差。
8. **别把 `0xA1/0x19`（LED）当状态查询**：它是写命令，`wValue=0/1`。

---

## 10. 待验证

- [ ] 28 B 报告中 byte[3..23] 的字段语义（先用静止/旋转/遮光三组实验）。
- [ ] `0xA1/0x04` / `0x05` / `0x06` 返回的 12/12/16 B 分别是什么。
- [ ] `0xA1/0x11`–`0x15` 传感器开关/量程的实际效果。
- [ ] 中断端点 `0x83` 是否能稳定收到事件（用 `UsbRequest` 重写后再测）。
- [ ] 按键事件是否真的走 HID，还是走别的接口（至今没抓到明确的按键包）。
