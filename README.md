# 亮亮视野 Glxss Me GLXSS3 投屏协议研究 / GLXSS3 Cast Protocol Research

> 对亮亮视野 LLVision GLXSS3（G25）AR 眼镜（Glxss Me）App（`com.llvision.glxss3.camera` v2.3.7）的互操作性研究，以及基于研究结果的重新实现：它怎么把手机画面投到眼镜、
> 摄像头/音频/HID 怎么驱动，以及用我们自己的 App 重新实现。
>
> A study of the LLVision GLXSS3 (G25) AR glasses, for interoperability and personal hardware
> experimentation.

**本仓库不包含 APK、固件或任何第三方专有文件**（仅含分析笔记、我们自己编写的代码与协议描述）。
**本项目禁止商业使用** —— 见 [LICENSE](LICENSE)。

注： 目前有民间高价转卖的开发资料等，目前我并未购买（如果有人可以分享可以联系我）。 本项目结果均为官方app进行协议分析后的重新实现，不包含原始代码。暂无开发眼镜固件的计划。

---

## 眼镜是什么

一句话：一块戴在头上的 **USB 显示器 + USB 摄像头 + USB 声卡 + HID**，SoC 是 **Intel Movidius Myriad 2**
（MA2450，和 Intel NCS1 神经棒同款）。所有 AR 叠加都在手机端做，眼镜只负责显示。

固件**不在眼镜里**：每次上电，它先以 `03e7:2150`（Myriad ROM 引导）出现，主机把 1.5MB 固件灌进 DDR，
它再重枚举成 `2e09:0030` 正常工作模式。没有主机灌固件，它什么都干不了。

## 硬件

| 项 | 值 |
|---|---|
| SoC | Intel Movidius Myriad 2（MA2450），12 个 SHAVE 核 + 2×LEON，RTEMS |
| 相机 | Sony IMX214，UVC 输出 YV12 640×480@30（实测 20–25fps） |
| 显示 | 640×400，RGBA8888，16B 帧头，USB bulk 单帧 1,024,016B |
| 传感器 | 板上 HID 设备（28B 报告），具体字段未完全解出 |
| 音频 | 标准 USB Audio：扬声器 + 麦克风，Android 自动识别为 usb_headset |
| 正常模式 USB | VID `0x2E09` / PID `0x0030`（复合设备） |
| 引导模式 USB | VID `0x03E7` / PID `0x2150`（"Movidius MA2X5X"） |
| 固件 | `G25_MainFW_1.9.1.img`（1,525,136B，MA2x 容器，2016-06-09） |

USB 接口分布（正常模式）：

| 接口 | 用途 | 端点 |
|---|---|---|
| 0 | UVC VideoControl | — |
| 1 | UVC VideoStreaming | `0x81` bulk IN, mps 512 |
| 2 | HID | `0x83` interrupt IN, 64 |
| 3 | 厂商 LCD | `0x01` bulk OUT, 512 |
| 4 / 5 / 6 | USB Audio | 扬声器 iso `0x02` 256 / 麦克风 iso `0x82` 220 |

## 驱动情况

手机端（Android）目前的状态：

| 子系统 | 状态 | 说明 |
|---|---|---|
| 显示投屏 | ✅ | 自研 LCD 驱动，MediaProjection 实时投屏，25fps，裁剪/拉伸/适应 + 画面微调 |
| UVC 摄像头 | ✅ | 纯 Kotlin 实现，YV12 解码，25fps，内嵌预览 + 拍照 |
| 音频 | ✅ | 系统 UAC 路由（扬声器/麦克风），App 里可测试 |
| USB 复合设备 | ✅ | 单例连接，LCD/UVC/HID 各接口独立 claim |
| 固件加载/恢复 | ✅ | 460ms 灌完 1.5MB，拔插自动恢复 |
| HID | ⚠️ | 能收 28B 报告，字段只解出一部分 |
| 人脸检测 | ❌ | 固件里有模块，手机端未实现 |
| NPU | ❌ | Myriad 2 的 SHAVE/CNN 未使用 |
| 录像 | ❌ | 未实现 |

眼镜端固件：显示、相机、音频、USB 都是厂商固件自带的，我们不修改固件。

## 初始化流程

```
上电
 └─ 03e7:2150   Myriad ROM 引导（只有一个 bulk IN 端点）
     └─ 主机灌固件：EP1 OUT，每包 1024B，无头无 ACK，10s 超时（约 460ms）
         └─ 设备重启
             └─ 2e09:0030  正常工作模式
                 ├─ 显示：claim if3，写 1MB 帧
                 ├─ 相机：claim if0/if1，UVC probe+commit
                 ├─ 音频：Android 自动接管 if4/5/6
                 └─ HID：claim if2，读 28B 报告
```

固件还有一种"半死"状态：USB 控制面还活着（控制请求都 ACK、1MB 帧照收），但相机和显示的媒体流水线停摆，
UVC 一帧都收不到，原厂 App 同样收不到。只有重新灌固件能恢复，所以 App 里做了检测 + 自动恢复。

## 快速开始

```bash
# 构建
export JAVA_HOME=/path/to/jdk17
cd app && gradle assembleDebug

# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 固件文件（自动恢复需要，不随仓库分发）
adb push G25_MainFW_1.9.1.img /sdcard/Android/data/com.wzqvip.glxsscast/files/
```

打开 App：连接眼镜 → 开始投屏（选"分享整个屏幕"）→ 摄像头/亮度/测试图都在同一页。

## 仓库结构

```
app/          我们自己的 Android App（Kotlin + Compose）
  usb/        USB 连接、固件加载、HID
  lcd/        LCD 协议与亮度/开关控制
  cast/       投屏服务（MediaProjection → USB）
  uvc/        UVC 摄像头（纯 Kotlin）
code/         PC 端协议验证脚本
docs/         分析笔记与各子系统章节
dist/         构建好的 APK
```

## Linux / 跨平台版本（`linux` 分支）

本仓库另有一个 **Linux / 跨平台 Python 版本**，代码在 [`linux/`](linux/) 目录，
位于 **`linux` 分支**（`git checkout linux`），不影响 `main` 分支的 Android App。
它提供 `glxss` CLI（list/status/bright/screen/mode/roll/eeprom/version/hid/uvc/frame/flash/cast）、
可复用的 pyusb/numpy 核心库，以及 Raspberry Pi Zero 2 W + Waveshare 1.3inch LCD HAT
的状态页/按键投屏示例。安装、Windows（Zadig/WinUSB）、macOS（libusb）和 Pi 部署说明见
[`linux/README.md`](linux/README.md) 与 [`linux/pi/README.md`](linux/pi/README.md)。

## 文档

| 章节 | 内容 |
|---|---|
| [docs/ch-display.md](docs/ch-display.md) | 显示：帧格式、RGBA 布局、投屏与裁剪、控制命令 |
| [docs/ch-uvc.md](docs/ch-uvc.md) | 摄像头：UVC 描述符、probe/commit、YV12、帧同步 |
| [docs/ch-hid.md](docs/ch-hid.md) | HID：28B 报告与接口 claim |
| [docs/ch-audio.md](docs/ch-audio.md) | 音频：UAC 接口与路由 |
| [docs/ch-usb.md](docs/ch-usb.md) | USB 复合设备与连接管理 |
| [docs/ch-firmware.md](docs/ch-firmware.md) | 固件：容器、加密、灌写协议、恢复 |
| [docs/ch-app.md](docs/ch-app.md) | App 架构与开发指南 |
| [docs/ch-npu.md](docs/ch-npu.md) | NPU 现状与研究路线 |

原始抓包与逆向记录在 `docs/` 下其他文件里（usb-capture、firmware-boot-protocol、vendor-flash-capture 等）。

## 许可

禁止商业使用，详见 [LICENSE](LICENSE)。分析结论来自对官方 App 的协议分析，不含原始代码。

