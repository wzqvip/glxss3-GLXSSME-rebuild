# SoC：Intel Movidius Myriad 2（MA2450）—— 与 Intel 神经棒同款

## 结论

眼镜主控是 **Intel Movidius Myriad 2（型号 MA2450）**，与 **Intel Neural Compute Stick 1（NCS1）**
是同一颗芯片。移动端 App 的"boot 模式"USB 身份直接使用了 **NCS1 的 VID/PID**。

## 证据

### 1. 固件里的 DDR 初始化程序名

`G25_MainFW_1.9.1.img` 头部（偏移 0x08）：

```
MA2x | ddrInitHeaderForMvcmd.elf | .text ...
```

- **`Mvcmd` = Movidius Command**，是 Myriad 系列的启动/固件容器命名；
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
| 正常 | `0x2E09` | `0x0030` | 厂商自定义（复合设备：UVC + LCD + HID） |
| **Boot** | **`0x03E7`** | **`0x2150`** | **Intel NCS1（Myriad 2）的默认 USB ID** |

`0x03E7` 是 Intel 的 USB Vendor ID；`0x2150` 就是第一代神经棒（MA2450）在 boot 模式下的 PID。
（第二代 NCS2 / Myriad X 是 `0x03E7:0x2485`。）

App 的 `LLVisionPlatformService` 里硬编码了这两个常量（`BOOT_VID = 999`，`BOOT_PID = 8528`），
说明厂商直接把 NCS1 的 boot 流程拿来刷自家固件。

## 这颗芯片是什么

Myriad 2（MA2450）规格：

| 项 | 参数 |
|---|---|
| CPU | 2 × LEON4（SPARC V8，32 位） |
| 向量核 | 12 × SHAVE（128-bit SIMD VLIW） |
| 加速器 | SIPP（图像处理流水线）、可编程 DMA |
| 摄像头接口 | MIPI CSI-2 |
| 内存 | 片上 4 MB + 外挂 DDR |
| 接口 | USB 3.0 / 2.0、I2C、SPI、UART、GPIO、MIPI |
| 典型用途 | 视觉处理、深度神经网络推理（NCS1 就是它） |

这意味着眼镜的"投屏"实际是：**手机通过 USB 把帧传给 Myriad 2，Myriad 2 的 LEON 核驱动显示屏**
（I2C/SPI 控制 + 并行/DSI 数据），同时 Myriad 2 还负责 UVC 摄像头、HID、传感器。

## 对"自己做 AR 眼镜"的意义

### 路线 1：复用厂商固件，只写宿主端（最稳）

- 协议已在 [protocol.md](protocol.md) 写清楚（16B 头 + 640×400 RGBA）；
- 宿主（PC/手机）自己渲染、自己发帧；
- 优点：不用碰固件、不用 MDK；缺点：只能"当显示器用"。

### 路线 2：替换 Myriad 2 固件（潜力最大，难度也最大）

- Boot 模式就是标准 NCS1 的 ROM 引导，**开源工具 `myriad-usbboot`（movidius）可以用任意 MVCMD 文件
  启动 MA2450/MA2480**；
- 也就是说：**理论上你可以把自己的 MVCMD 固件刷进这副眼镜的 Myriad 2**，从而完全接管摄像头/显示；
- **主要障碍**：写出 MVCMD 需要 **Intel Movidius Development Kit（MDK）**（SHAVE 编译器 + LEON 工具链），
  该工具链已停止公开分发，获取困难；另外要逆向出这块板子的 LCD/摄像头硬件连接（面板型号、接口、时序）；
- 风险：刷写失败可能变砖（Myriad 的 ROM boot 通常可恢复，但不保证）。

### 路线 3：换芯片自研

- 参考 Myriad 2 的架构，选 ESP32-S3 / K230 / RV1106 等带 ISP 的 SoC 自行设计；
- 低成本、可控，但要自己做显示与摄像头驱动。

## 下一步验证

1. 两种模式下 `lsusb -v -d 03e7:2150` / `-d 2e09:0030`，记录端点与接口；
2. 用 `usbmon` 抓 App 刷固件时的 bulk 传输，确认是否就是标准 MVCMD 引导序列；
3. 查 `G25_MainFW_1.9.1.img` 的容器格式（`MA2x` 头 + `ddrInitHeaderForMvcmd.elf` + `main.elf`），
   与 NCSDK 的 `ma2x8x.mvcmd` 对比；
4. 评估 MDK 获取途径与风险，决定是否走路线 2。

## 参考

- Intel Neural Compute Stick 1 使用 Movidius Myriad 2（MA2450）
- MVCMD 启动格式与 `myriad-usbboot` 工具（Movidius 开源）
- Myriad 2 数据手册（Intel/Movidius，公开版本）
