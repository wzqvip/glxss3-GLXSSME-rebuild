# USB 控制传输实测（真机抓包）

> **这是原始证据页。** 想按子系统读协议与代码，请先看：
> [显示](ch-display.md) · [UVC](ch-uvc.md) · [HID](ch-hid.md) · [音频](ch-audio.md) · [USB](ch-usb.md)。
>
> 方法：Pixel 10 Pro Fold（Android 16，KernelSU root）+ 眼镜（`2e09:0030`），
> 用 Frida 在 App 的 `:llvision_G25` 进程 hook `libusb_control_transfer` / `libusb_bulk_transfer`
> 抓取全部控制传输；同时用 `uiautomator` 驱动 App 界面触发各功能。
>
> **这是全部"待验证项"的答案**，可直接用于重新实现的 App 实现。

## 1. 设备描述符（实测）

```
VID:PID = 2e09:0030   ("LLVISION G25 Firmware", bcdUSB 2.10, 复合设备 7 接口)
INTF 0  class 0x0e/0x01   UVC Video Control
INTF 1  class 0x0e/0x02   UVC Video Streaming   ep 0x81 BULK IN  512
INTF 2  class 0x03        HID                   ep 0x83 INT  IN   64 (interval 8)
INTF 3  class 0xff/0x01   ★ LCD 厂商接口        ep 0x01 BULK OUT  512
INTF 4  class 0x01/0x01   USB Audio Control
INTF 5  class 0x01/0x02   Audio Streaming OUT   alt1 ep 0x02 ISOC OUT 256
INTF 6  class 0x01/0x02   Audio Streaming IN    alt1 ep 0x82 ISOC IN  220
```

> 附：**眼镜有麦克风和扬声器**（UAC1），可直接用 Android 音频路由使用，不需要私有协议。

## 2. 投屏帧（实测）

```
libusb_bulk_transfer(ep=0x01, len=1,024,016, timeout=5000)
```
- 每帧 **1,024,016 字节 = 16 字节头 + 640×400×4 RGBA**；
- **一次性 bulk 传输**（不是分块），超时 5000 ms；
- 连续发送，实测约 1~2 帧/秒（**这是早期原厂 App 抓包时的发送节奏**，受当时手机端渲染/发送线程限制；
  本项目优化后投屏可达 25 fps，见 [ch-display.md §6](ch-display.md#6-实测数据)）。

## 3. 控制传输（全部实测）

所有控制传输均为 **bmRequestType = 0xA1**（Device-to-Host | Class | Interface），
参数放在 `wValue`，`wLength=1`（哑读，用于完成一次控制传输）。

| 功能 | bmReq | bReq | wValue | wIndex | wLen | 说明 |
|---|---|---|---|---|---|---|
| **查询状态** | 0xA1 | **0x04** | 0 | **0x0003** | 8 | 返回 8 字节状态 |
| **设置亮度** | 0xA1 | **0x01** | **亮度值** | **0x0003** | 1 | 实测 0x64–0xFE（100–254） |
| **设置显示模式** | 0xA1 | **0x05** | **模式** | **0x0003** | 1 | 关闭=1，开启=2 |
| **自动亮度** | 0xA1 | **0x10** | 1 | **0x0002** | 1 | |
| **LED 指示灯** | 0xA1 | **0x19** | 0/1 | **0x0002** | 1 | |
| **HID 报告轮询** | 0xA1 | **0x16** | 0 | **0x0002** | 28 | 约 5 次/秒，返回传感器数据 |

### 状态返回（bReq=0x04，8 字节）示例

```
80 02 90 01 01 b9 0a 00
              ^^
              byte[5] = 当前亮度（0xb9 = 185）
```
实测：设置亮度 0xEF 后，状态 byte[5] 变为 0xEF；设置 0xB9 后变 0xB9。
`byte[4]` 可能为显示模式/开关状态（0x01）。

### HID 报告（bReq=0x16，28 字节）示例

```
00 71 50 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 41 1b 72 42
   ^^                                                          ^^^^^^^^^^^^^^^
   byte[2] 递增计数（0x50→0x65…）                             最后 4 字节疑似时间戳
```

## 4. 触发方式（复现步骤）

1. App 设置页：Brightness 滑条 → OK ⇒ 亮度命令；
2. Auto 开关 ⇒ 自动亮度命令（随后自动调亮度）；
3. Sync display 开关 ⇒ 显示模式 2 / 1；
4. Advance settings → LED indicator ⇒ LED 命令。

## 5. 给重新实现的 App 的结论

- 帧发送：单次 bulk 写 `16B 头 + RGBA`（Android 可用 `bulkTransfer`，大缓冲会自动分包）；
- 亮度：`controlTransfer(0xA1, 0x01, value, 0x0003, buf, 1, 5000)`；
- 显示模式：`controlTransfer(0xA1, 0x05, mode, 0x0003, buf, 1, 5000)`；
- 状态查询：`controlTransfer(0xA1, 0x04, 0, 0x0003, statusBuf, 8, 5000)`；
- 自动亮度/LED：`0xA1, 0x10/0x19, 0/1, 0x0002`；
- 音频：标准 UAC，无需逆向。

