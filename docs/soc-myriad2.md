# SoC：Intel Movidius Myriad 2（MA2450）

> 本文是 SoC 的**证据留档**。它在项目里的意义、能不能拿来跑 NN、有哪几条路线，
> 见 **[第 8 章 · NPU](ch-npu.md)**；硬件规格速查见 [README §2](../README.md#2-硬件规格)。

## 结论

眼镜主控是 **Intel Movidius Myriad 2（型号 MA2450）**，与 **Intel Neural Compute Stick 1（NCS1）**
是同一颗芯片。移动端 App 的"boot 模式"USB 身份直接使用了 **NCS1 的 VID/PID**。

## 证据

### 1. 固件里的 DDR 初始化程序名

`G25_MainFW_1.9.1.img` 头部（偏移 `0x08`）：

```
MA2x | ddrInitHeaderForMvcmd.elf | .text ...
```

- **`Mvcmd` = Movidius Command**，Myriad 系列的启动/固件容器命名；
- `ddrInitHeaderFor*` 是 Myriad 启动时先加载的 DDR 初始化程序。

### 2. 固件内嵌构建信息

```
GIT VERSION: CLEAN 2016-06-09 06:56:58 +0300
  ref: refs/heads/temp_proposed_golden_driver_ma2450
```

**`ma2450`** 即 Myriad 2 的型号（MA2450/MA2455）。

### 3. USB 设备 ID

| 模式 | VID | PID | 说明 |
|---|---|---|---|
| 正常 | `0x2E09` | `0x0030` | 厂商自定义（复合设备：UVC + LCD + HID + UAC） |
| **Boot** | **`0x03E7`** | **`0x2150`** | **Intel NCS1（Myriad 2）的默认 USB ID** |

`0x03E7` 是 Intel 的 USB Vendor ID；`0x2150` 是第一代神经棒（MA2450）的 ID。
（第二代 NCS2 / Myriad X 是 `0x03E7:0x2485`。）

App 的 `LLVisionPlatformService` 里硬编码了这两个常量（`BOOT_VID = 999`，`BOOT_PID = 8528`），
说明厂商直接把 NCS1 的 boot 流程拿来刷自家固件。

> **命名澄清**：NCS1（Myriad 2）对应 **MA2x5x** 系列；`ma2x8x` 是 **Myriad X（NCS2）**，
> 两者固件不通用。早期笔记里把 NCS1 固件写成 `ma2x8x.mvcmd` 是笔误，已在此更正。
> 具体文件名随 NCSDK 版本变化，本仓库不附带任何 NCS 固件。

## 规格

| 项 | 参数 |
|---|---|
| CPU | 2 × LEON4（SPARC V8，32 位） |
| 向量核 | 12 × SHAVE（128-bit SIMD VLIW） |
| 加速器 | SIPP（图像处理流水线）、可编程 DMA |
| 摄像头接口 | MIPI CSI-2 |
| 内存 | 片上 4 MB + 外挂 DDR |
| 接口 | USB 3.0 / 2.0、I2C、SPI、UART、GPIO、MIPI |
| 典型用途 | 视觉处理、深度神经网络推理（NCS1 就是它） |

**在本眼镜里**：手机通过 USB 把帧传给 Myriad 2，LEON 核驱动显示屏，同时负责 UVC 摄像头、
HID、音频、传感器。固件是 RTEMS，USB 设备栈来自 MCCI。

## 下一步验证

1. ~~两种模式下 `lsusb -v` 记录端点与接口~~ ✅ 已完成，见 [usb-capture.md](usb-capture.md)；
2. ~~用 `usbmon`/Frida 抓刷机序列，确认是否就是标准 MVCMD 引导~~ ✅ 已完成，见
   [firmware-boot-protocol.md](firmware-boot-protocol.md) / [vendor-flash-capture.md](vendor-flash-capture.md)；
3. ~~查容器的 `MA2x` 头/TLV/ELF 结构~~ ✅ 已完成，见 [ch-firmware.md §2](ch-firmware.md#2-容器格式ma2x--mvcmd)；
4. 评估 MDK 获取途径与"自写 MVCMD"的可行性 → 见 [ch-npu.md §4](ch-npu.md#4-四条可能路线)（未开始）。

## 参考

- Intel NCS1 使用 Movidius Myriad 2（MA2450）；
- MVCMD 启动格式与 `myriad-usbboot`（Movidius 开源，已归档）；
- NCSDK `api/src/usb_boot.c`（Myriad 2 ROM 引导的主机侧实现）；
- Myriad 2 数据手册（Intel/Movidius，公开版本）。
