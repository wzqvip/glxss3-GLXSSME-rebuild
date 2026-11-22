# 亮亮视野 Glxss Me GLXSS3 投屏协议研究 / GLXSS3 Cast Protocol Research

> 对亮亮视野 LLVision GLXSS3（G25）AR 眼镜（Glxss Me）App（`com.llvision.glxss3.camera` v2.3.7）的互操作性研究，以及基于研究结果的重新实现：它怎么把手机画面投到眼镜、
> 摄像头/音频/HID 怎么驱动，以及用我们自己的 App 重新实现。
>
> A study of the LLVision GLXSS3 (G25) AR glasses, for interoperability and personal hardware
> experimentation.

**源码仓库不含第三方专有文件**（仅含分析笔记、我们自己编写的代码与协议描述）。
例外：`dist/` 里的自研 APK **内含原厂固件镜像** `G25_MainFW_1.9.1.img`（版权归亮亮视野所有，
仅用于设备互操作性恢复，禁止商用，详见 [NOTICE.md](NOTICE.md)）。
**本项目禁止商业使用** —— 见 [LICENSE](LICENSE)。

注： 目前有民间高价转卖的开发资料等，目前我并未购买（如果有人可以分享可以联系我）。 本项目结果均为官方app进行协议分析后的重新实现，不包含原始代码。暂无开发眼镜固件的计划。

---

## 眼镜是什么

一句话：一块戴在头上的 **USB 显示器 + USB 摄像头 + USB 声卡 + HID**，SoC 是 **Intel Movidius Myriad 2**
（MA2450，和 Intel NCS1 神经棒同款）。所有 AR 叠加都在手机端做，眼镜只负责显示。

固件**不在眼镜里**：每次上电，它先以 `03e7:2150`（Myriad ROM 引导）出现，主机把 1.5MB 固件灌进 DDR，
它再重枚举成 `2e09:0030` 正常工作模式。没有主机灌固件，它什么都干不了。


| 项 | 值 |
|---|---|
| SoC | Intel Movidius Myriad 2（MA2450），12 个 SHAVE VLIW 核 + 2×LEON，RTEMS；USB 栈为 MCCI（`vsc2.uvc_streamin` 等） |
| 相机 | Sony IMX214（13MP，最高 4128×3104）；UVC 1.10 bulk 传输，格式 YV12 / MJPEG |
| 相机档位 | 4128×3104、3264×2448、1920×1080、1280×960、1280×720、640×480；实测稳定出流为 **640×480 YV12，约 20–25fps**（更高档位 probe/commit 能过，但设备不出流） |
| 变焦 | 宣传的「3x 无损放大」是**数码裁切**（4128/1080≈3.8），无光学变焦；UVC `CT_ZOOM_ABSOLUTE` 待真机验证 |
| 显示 | 640×400 RGBA8888，16B 帧头，单帧 1,024,016B；bulk OUT EP `0x01`，实测 30ms/帧（≈35MB/s） |
| 显示控制 | 亮度 `0x01`、屏开关 `0x02`、roll `0x03`、状态 `0x04`、显示模式 `0x05`、EEPROM `0x07`、自动亮度 `0x10`、版本 `0x17`（详见 docs/ch-display.md） |
| IMU / 传感器 | HID 28B 报告：光照 lux、罗盘 xyz、加速度 xyz（±4g，静止 \|a\|≈8117 counts）、陀螺 xyz（±2000dps）、af/caf、温度 f32 |
| 按键 | **1 个 Fn 键**（keyCode `0x71`，单击/长按），无触摸；ME 宣传的「手势」靠相机识别 |
| 音频 | 标准 USB Audio：扬声器 + 麦克风，实测 48kHz，Android 识别为 usb_headset |
| 正常模式 USB | VID `0x2E09` / PID `0x0030`（复合设备，7 个接口） |
| 引导模式 USB | VID `0x03E7` / PID `0x2150`（"Movidius MA2X5X"，只有 bulk IN） |
| 固件 | `G25_MainFW_1.9.1.img`（1,525,136B，MA2x 容器，2016-06-09）；另有旧版 1.7.4（同源，差 2,712B） |
| 固件加密 | 尾部 512KiB 为 AES-128-ECB，key 由 `llvision.enkey` 经内置 RSA-1024 私钥解出 |
| 重量/形态 | 公开资料：GLXSS ME（2018）33.4g，USB-C 有线连接手机 |

## 驱动情况

手机端（Android）目前的进度：

| 子系统 | 状态 | 说明 |
|---|---|---|
| 显示投屏 | ✅ | MediaProjection 实时投屏 25fps；裁剪/拉伸/适应 + 缩放/偏移微调；横屏 cover；软件 180° 翻转 |
| LCD 控制 | ✅ | 亮度、屏开关、显示模式、roll、状态查询、EEPROM 读取、真实固件版本（`0xA1/0x17`，原厂是硬编码） |
| UVC 摄像头 | ✅ | 纯 Kotlin 实现，YV12/MJPEG，640×480 @25fps，内嵌预览 + 拍照 |
| **摄像头变焦** | **🚧 进行中** | 「3x 无损」为数码裁切（非光学）；UVC `CT_ZOOM_ABSOLUTE` 真机验证中 |
| 录像 | ✅ | H.264/MP4，投屏或 UVC 源；实测 30s / 899 帧 / 0 丢帧 |
| 音频 | ✅ | 48kHz；强制 USB 麦克风、音量控制、RMS/采样率实测、双向通话 |
| HID / IMU | ✅ | 28B 全解：Fn 按键（单击/长按）、lux、罗盘、加速度、陀螺、af/caf、温度；300ms 轮询 |
| 固件加载/恢复 | ✅ | 固件随 APK 分发（assets），首次运行解包校验；460ms 灌完 1.5MB；引导模式自动检测 + 拔插自动恢复 |
| USB 生命周期 | ✅ | 单例连接；Android 12（华为）闪退修复；拔插自动重连；崩溃取证（crash_last.txt） |
| 人脸检测 | ❌ | 固件里有模块（`libfacedtr`），手机端未实现 |
| NPU | ❌ | Myriad 2 的 SHAVE/CNN 未使用 |
| 无线 | ❌ | 眼镜无 Wi-Fi/蓝牙，只有 USB 有线 |

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

# 固件：0.1.5 起已随 APK 分发（assets/firmware/），首次启动会解包到
# filesDir/firmware/ 并做 sha256 校验，不需要再 adb push
```

打开 App：连接眼镜 → 开始投屏（选"分享整个屏幕"）→ 摄像头/亮度/测试图都在同一页。

> 固件版权归亮亮视野（LLVision）所有：APK 内含镜像仅用于把自家处于 `03E7:2150`
> 引导模式的眼镜恢复成 `2E09:0030`，**禁止商用**。详见 [NOTICE.md](NOTICE.md)。

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



