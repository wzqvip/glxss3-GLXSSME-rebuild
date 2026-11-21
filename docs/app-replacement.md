# 替代 App 设计 / Replacement phone app

目标：不依赖原厂 App，用 **Android USB Host API** 直接驱动眼镜，做一个更好用的投屏客户端。

## 1. 总体架构

```
┌─────────────────────────── Android App ───────────────────────────┐
│  UI (Compose)                                                      │
│   ├─ 连接状态 / 设备信息 / 亮度 / 开关 / 旋转 / 显示模式            │
│   └─ 投屏参数（缩放、帧率、镜像翻转）                               │
│                                                                    │
│  FrameSource             FrameEncoder?            UsbLink          │
│  MediaProjection    →    (可选：缩放/裁切)    →    bulk 分块发送    │
│  VirtualDisplay           RGBA 640×400             16B 头 + 载荷     │
│  ImageReader                                                       │
│                                                                    │
│  Control      → USB control transfer（亮度/开关/旋转/模式）         │
│  UVC (可选)   → 摄像头预览/拍照                                     │
└────────────────────────────────────────────────────────────────────┘
```

- 不依赖任何厂商 native 库（原厂用 libusb + JNI；Android 有原生 `UsbDeviceConnection` 即可）。
- 协议按 [protocol.md](protocol.md)：`16B 头 + 640×400×4 RGBA`。

## 2. 关键实现点

### USB

```kotlin
val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
// 过滤 0x2E09:0x0030（device_filter.xml），USB_DEVICE_ATTACHED 广播触发
val conn = usbManager.openDevice(device)
conn.claimInterface(intf, true)
val ep = intf.getEndpoint(0)            // 运行时遍历找 bulk OUT
```

- **bulk 分块**：单次 `bulkTransfer` 建议 ≤ 16 KB（Android 内部按 wMaxPacketSize 拆包，
  1 MB 整包一次发容易超时/失败）；写入循环 + 失败重试 + `LIBUSB` 风格的超时处理。
- **背压**：`ImageReader.acquireNextImage()` 拿到的 buffer 发不完就丢弃旧帧（保持低延迟）。
- **帧率控制**：目标 15–30 fps；可用 `Choreographer` 或简单的时间片。

### 帧管线

```kotlin
val vd = displayManager.createVirtualDisplay("cast", 640, 400, 1, surface, FLAG_PUBLIC)
val reader = ImageReader.newInstance(640, 400, PixelFormat.RGBA_8888, 2)
// onImageAvailable → planes[0].buffer → 发送
```

- **缩放/裁切**：屏幕比例一般不是 16:10，需要 letterbox（黑边）或 crop；
  原厂是直接拉伸（会变形）——新 App 提供 fit/fill/stretch 三种模式。
- **旋转/镜像**：`LCD.setRoll()` 只能整屏转，镜像需要自己做像素翻转（或渲染时翻转 Canvas）。
- **颜色**：先用纯色帧确认 RGBA 字节序（可能是 BGRA 或 ARGB），提供"颜色顺序"调试开关。

### 控制命令（待补齐）

亮度/开关/旋转/显示模式走 USB control transfer，但**具体 `bmRequestType/bRequest/wValue/wIndex` 未知**，
需要抓包（见 [open-questions.md](open-questions.md)）。计划：

1. 在 root 设备或 Linux 主机上抓原厂 App 的 usbmon；
2. 或对 `liblcd.so` 做静态反汇编（函数被 strip，需按字符串引用定位）；
3. 抓不到时，先用"只发帧"的 MVP（屏幕亮着就能用），控制命令后补。

## 3. 功能清单（"好用"的定义）

### 已实现（M0/M1，真机验证）
- [x] USB 自动发现/连接，测试图（色条/纯色/黑屏）
- [x] 亮度 +/−、开屏/关屏（显示模式 1/2）、读状态、断开
- [x] **实时投屏**：MediaProjection → VirtualDisplay → 16B 头 + 640×400 RGBA → USB
- [x] **画面模式**：适应（黑边）/ 裁剪（填满、不变形）/ 拉伸（填满）
- [x] 实测帧率：静态 ~3 fps，动画 **32–39 fps**（≈39 MB/s，USB2.0 上限附近）
- [x] 缓冲区复用（无每帧 1MB 分配）

> 注意：测试色条图案自带纵向渐变（越往下越暗），是图案本身的设计，不是显示问题。

### MVP（M0）
- [x] USB 自动发现/连接（拔插重连）
- [x] 全屏镜像投屏（640×400）
- [x] 断线重连 + 错误提示（部分）
- [ ] 基本设置持久化

### M1 体验
- [ ] 亮度滑块（命令待抓）/ 屏幕开关
- [ ] 缩放模式：适应/填充/拉伸；横竖屏适配
- [ ] 帧率上限（10/15/20/30）
- [ ] 画质选项（后期可试 JPEG/降低分辨率，需固件支持）
- [ ] 常亮、自动启动（连上即投）

### M2 高级
- [ ] 悬浮按钮/通知栏快捷开关
- [ ] 只投某个 App（Android 14 局部投屏 / 或自绘 HUD）
- [ ] 摄像头预览/拍照（UVC）
- [ ] 性能 HUD（fps、带宽、丢帧）

### M3 打磨
- [ ] 宽色域/伽马调整（若设备支持）
- [ ] 多设备管理
- [ ] 国际化

## 4. 里程碑

| 阶段 | 内容 | 产出 |
|---|---|---|
| M0 | USB 连接 + 发测试图（纯色/色条） | 能在眼镜上看到画面 |
| M1 | MediaProjection 实时投屏 | 可用替代品 |
| M2 | 控制命令 + 体验优化 | 好用版本 |
| M3 | 摄像头/高级功能 | 完整版 |

## 5. 技术选型

- 语言：Kotlin；UI：Jetpack Compose
- 最低 API：26（USB Host + MediaProjection 已足够；ImageReader RGBA 需要 21+）
- 构建：Gradle 8 + AGP 8，`minSdk 26 / targetSdk 35`
- 无第三方二进制依赖（UVC 可选 libuvc，或 Android 12+ 的 external camera HAL）

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| 控制命令参数未知 | 先做 MVP（不需要控制命令）；抓包后补 |
| 1 MB/帧带宽瓶颈 | 限帧率；试 JPEG 格式（format 字节可能支持 2=JPEG，需实测） |
| 不同手机 USB 稳定性差异 | 分块发送 + 重试 + 断线重连 |
| 设备固件差异（版本） | 启动时读版本/产品信息（`nativeProductGetInfo` 对应协议待逆向） |

## 7. 仓库规划

```
app/                 Android 工程（Kotlin）
  app/src/main/java/.../usb/UsbLink.kt
  app/src/main/java/.../lcd/LcdProtocol.kt
  app/src/main/java/.../cast/ScreenCastService.kt
  app/src/main/java/.../ui/
docs/                协议与调研（已有）
code/poc_send_frame.py  桌面端验证脚本（已可跑）
```
