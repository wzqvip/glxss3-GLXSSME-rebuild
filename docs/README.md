# GLXSS G25 文档索引

> 这是整套文档的入口。建议先读 [README](../README.md) 的硬件与状态矩阵，
> 再按兴趣选章节。所有章节都遵循同一结构：**原理 → 数据结构/协议 → 驱动步骤 → 可运行代码 → 实测数据 → 坑**。

## 1. 分章教程

| # | 章节 | 一句话 | 关键结论 | 实测状态 |
|---|---|---|---|---|
| 1 | [显示子系统](ch-display.md) | 眼镜是一块 640×400 的 USB 显示器 | 16 B 头 + 1,024,000 B RGBA，单帧 30 ms | ✅ 25 fps |
| 2 | [摄像头 UVC](ch-uvc.md) | 标准 UVC 1.10，bulk，未压缩 YV12 | 一次 bulk = 一帧 = 460812 B（460800 + 12 B header） | ✅ 20–25 fps |
| 3 | [HID](ch-hid.md) | 按键/传感器/版本上报 | 28 B 报告只解出计数与时间戳；24 B 版本可读 | ⚠️ 语义未全解 |
| 4 | [音频](ch-audio.md) | 标准 UAC1 声卡 | 不需要私有协议，系统自动路由 | ✅ 路由可用 |
| 5 | [USB 复合与连接](ch-usb.md) | 一条线 7 个接口 | claim 是按 fd 的；HID 会被 UVC 抢走 | ✅ |
| 6 | [固件与引导](ch-firmware.md) | 固件不在眼镜里，每次上电由主机灌 | MA2x + AES-128-ECB 尾 512 KiB；裸 bulk 1024 B/包 | ✅ 460 ms |
| 7 | [App 架构](ch-app.md) | 模块、构建、调试、下一步 | 默认路径纯 Kotlin，无 native 依赖 | ✅ 可构建 |
| 8 | [NPU 现状](ch-npu.md) | Myriad 2 有 12×SHAVE，但用不上 | NCS1 固件与显示/相机互斥 | ❌ |
| 9 | [导航 HUD](nav-hud.md) | 手机读 Google 地图导航，自己渲染 640×400 直推眼镜 | Maps 通知 → Canvas → 16 B 头 + 1 MB bulk，1 Hz/25 ms；不需要 Pi | ✅ LG 真机（直推 ACK） |

## 2. 证据与专题文档

这些是**原始记录**，章节会引用它们，不再重复抄写。

| 文档 | 内容 | 为什么保留 |
|---|---|---|
| [soc-myriad2.md](soc-myriad2.md) | Myriad 2 规格、SoC 证据、与 NCS1 的关系 | 硬件层的证据留档 |
| [usb-capture.md](usb-capture.md) | 正常模式完整描述符、控制请求实测表、HID 报告样本 | 全部控制命令的第一手抓包 |
| [firmware-boot-protocol.md](firmware-boot-protocol.md) | `libboot.so` 反汇编、端点/超时/错误码、TLV/ELF 细节 | 引导协议的二进制级证据 |
| [firmware-extraction.md](firmware-extraction.md) | 固件是否可从设备回读、enkey/AES 复算、版本确认 | "为什么没有固件可 dump"的完整论证 |
| [vendor-flash-capture.md](vendor-flash-capture.md) | 原厂刷机 1490 包序列、payload 精确组成、-206 根因 | 刷写协议与生命周期坑的证据 |
| [device-recovery.md](device-recovery.md) | 三态判定、卡死态证据、恢复 SOP、代码修复 | 运维手册 + 故障反证 |
| [uvc-camera-plan.md](uvc-camera-plan.md) | 摄像头四种方案（A/B/C/D）对比、厂商 .so 绑定细节 | 技术选型记录，避免重走弯路 |
| [nav-hud.md](nav-hud.md) | 导航 HUD：Maps 通知解析、640×400 渲染、USB 直推、PNG 抓帧、真机实测 | 方案 A 的完整说明与样张 |
| [apk-versions.md](apk-versions.md) | 1.8.4 / 2.3.7 / 社区版对比、1.7.4 固件、UVC 档位表 | 版本考古与第二份固件 |
| [open-questions.md](open-questions.md) | 仍未验证的项 + 验证方法 | 给后来者的待办清单 |

早期笔记（已并入 README 与章节，原文件仅保留跳转）：

| 旧文档 | 现在读哪里 |
|---|---|
| [glasses-overview.md](glasses-overview.md) | [README §1](../README.md#1-这是什么--为什么做)、[ch-npu.md](ch-npu.md) |
| [device.md](device.md) | [README §2](../README.md#2-硬件规格)、[ch-usb.md](ch-usb.md) |
| [protocol.md](protocol.md) | [ch-display.md](ch-display.md) |
| [app-replacement.md](app-replacement.md) | [ch-app.md](ch-app.md) |

## 3. 工具

PC 端调试统一入口：[`code/glxss.py`](../code/glxss.py)（单文件 CLI，覆盖
`list/status/bright/screen/mode/roll/eeprom/version/hid/frame/uvc/flash/decode-uvc`；
安装、权限、示例见 [`code/README.md`](../code/README.md)）。

## 4. 建议阅读路径

**我只想用起来**
→ [README §5 快速开始](../README.md#5-快速开始) → [ch-firmware.md §7 恢复 SOP](ch-firmware.md#7-半死现象与恢复-sop)

**我要写自己的投屏客户端**
→ [ch-display.md](ch-display.md) → [ch-usb.md](ch-usb.md) → [code/poc_send_frame.py](../code/poc_send_frame.py)

**我想在 PC 上调试眼镜**
→ [code/README.md](../code/README.md) → [code/glxss.py](../code/glxss.py)（`list` / `status` / `frame` / `hid` / `uvc`）

**我要接摄像头**
→ [ch-uvc.md](ch-uvc.md) → [uvc-camera-plan.md](uvc-camera-plan.md)（选型与坑）

**我要碰固件**
→ [ch-firmware.md](ch-firmware.md) → [firmware-boot-protocol.md](firmware-boot-protocol.md) → [firmware-extraction.md](firmware-extraction.md) → [vendor-flash-capture.md](vendor-flash-capture.md)

**我想榨干这颗芯片（NPU）**
→ [ch-npu.md](ch-npu.md) → [soc-myriad2.md](soc-myriad2.md) → [open-questions.md](open-questions.md)

**设备不出图了**
→ [device-recovery.md §1 三态判定](device-recovery.md#1-三态判定表30-秒分清) → [ch-firmware.md §7](ch-firmware.md#7-半死现象与恢复-sop)

## 5. 证据分级约定

文档里每个关键结论都带标记：

| 标记 | 含义 | 例子 |
|---|---|---|
| **【验】** | 有二进制证据、抓包或真机实测输出 | "单帧 1,024,016 B，30 ms" |
| **【推】** | 由代码/数据结构强推断，未单独验证 | "0x9A 记录是 (寄存器地址, 值)" |
| **【未】** | 未确认，文中标"待验证" | "1080p 档位的实际帧率" |

## 6. 术语表

| 术语 | 解释 |
|---|---|
| **MVCMD / MA2x** | Movidius 的启动镜像容器格式；本仓库固件以 `MA2x` 魔数开头 |
| **ROM boot / 引导模式** | Myriad 2 芯片内固化的 USB 引导，ID `03E7:2150`，等待主机灌镜像 |
| **UVC** | USB Video Class；本设备是 1.10，VideoStreaming 用 bulk 端点 |
| **VS / VC** | UVC 的 VideoStreaming / VideoControl 接口 |
| **probe / commit** | UVC 协商流参数的两次 class 控制请求 |
| **YV12 / I420** | 两种 YUV420 平面顺序；本设备是 YV12（Y / V / U） |
| **UAC** | USB Audio Class；本设备是 UAC1，含扬声器与麦克风 |
| **IAD** | Interface Association Descriptor；复合设备用来把多个接口绑成一个功能 |
| **SIPP / SHAVE** | Myriad 2 的图像流水线加速器 / 128-bit SIMD VLIW 向量核 |
| **RTEMS** | 设备上运行的实时操作系统 |
| **claim（接口）** | `UsbDeviceConnection.claimInterface()`；**按 fd 生效**，不是全局锁 |
| **半死状态** | USB 控制面正常、媒体流水线停摆的固件异常态，只能重灌恢复 |
