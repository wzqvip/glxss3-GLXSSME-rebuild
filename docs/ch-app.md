# 第 7 章 · App 架构与开发指南

> 本章讲清楚：这个替代 App 是怎么分层的、每一层负责什么、怎么构建/调试、
> 现在有哪些已知问题、下一步该做什么。
>
> 代码根目录：[`app/app/src/main/java/com/wzqvip/glxsscast/`](../app/app/src/main/java/com/wzqvip/glxsscast/)
> 构建说明：[app/BUILDING.md](../app/BUILDING.md)

---

## 1. 设计原则

| 原则 | 落地 |
|---|---|
| **零 native（默认路径）** | 只依赖 `android.hardware.usb`，不打包任何厂商 `.so`；`UVC`/`LCD`/`引导` 全部纯 Kotlin。`BootLoader.kt` 是可选 fallback，需自备 `.so` |
| **零厂商 SDK** | 不引用 `com.llvision.*`；`BootLoader.kt` 是可选 fallback，需要自备 `.so` |
| **进程内单连接** | `UsbLink` 单例 + 一个 `UsbDeviceConnection`，Activity/Service 共用 |
| **失败要可解释** | 状态栏直接给"为什么失败 + 下一步做什么"（例如固件卡死 → 拔插重灌） |
| **缓冲区复用** | 投屏 1 MB/帧、UVC 460 KB/帧，全部复用，避免 GC 抖动 |
| **控制与数据分离** | `lcd/LcdControl` 只发 control，`usb/UsbLink` 只发 bulk |

---

## 2. 模块划分

```
com.wzqvip.glxsscast
├─ MainActivity.kt        Compose UI、权限/热插拔、测试入口、画面调整
├─ usb/
│   ├─ UsbLink.kt         单例连接、接口/端点选择、HID claim、发帧
│   ├─ MyriadBootLoader.kt 纯 Kotlin 固件加载（默认路径）
│   ├─ BootLoader.kt      厂商 libboot.so 的 JNI 绑定（可选、需自备 .so）
│   └─ Peripherals.kt     HID 中断读取、扬声器测试音、麦克风 RMS
├─ lcd/
│   ├─ LcdProtocol.kt     16 B 帧头、纯色/色条测试图
│   └─ LcdControl.kt      亮度/开关屏/roll/模式/状态/版本/EEPROM/自动亮度/LED
├─ cast/
│   └─ CastService.kt     MediaProjection → VirtualDisplay → ImageReader → 映射 → USB
└─ uvc/
    ├─ UvcCamera.kt       描述符解析、probe/commit、收流组帧、YUV 解码、拍照
    ├─ Yuv.kt             YV12/I420/NV12/NV21/YUY2/UYVY → ARGB（整数运算）
    └─ UvcPreview.kt      Compose 预览 + 拍照按钮 + 生命周期
```

| 模块 | 行数（约） | 关键职责 | 依赖 |
|---|---:|---|---|
| `MainActivity.kt` | 652 | UI、权限、广播、测试按钮、画面调整 | 全部 |
| `cast/CastService.kt` | 545 | 投屏前台服务、旋转适配、三种映射 | `UsbLink`、`LcdControl` |
| `uvc/UvcCamera.kt` | 1022 | UVC 全流程 | `Yuv`、`UsbManager` |
| `usb/UsbLink.kt` | 205 | 连接与 bulk | — |
| `usb/MyriadBootLoader.kt` | 109 | 固件解密 + 灌写 | `UsbManager` |
| `usb/BootLoader.kt` | 76 | 厂商 JNI 路径 | `libboot.so`（可选） |
| `usb/Peripherals.kt` | 96 | HID/音频测试 | `UsbLink` |
| `lcd/LcdControl.kt` | 109 | 控制请求封装 | `UsbDeviceConnection` |
| `lcd/LcdProtocol.kt` | 69 | 帧格式 | — |
| `uvc/Yuv.kt` | 122 | 颜色转换 | — |
| `uvc/UvcPreview.kt` | 120 | Compose 预览 | `UvcCamera` |

**技术栈**：Kotlin 2.0.21、AGP 8.7.3、Compose BOM 2024.10.01、`minSdk 26 / targetSdk 35`、JDK 17。
无第三方运行时依赖（只有 AndroidX）。

---

## 3. 数据流

### 3.1 投屏（前台 Service）

```
MainActivity: MediaProjectionManager.createScreenCaptureIntent()
   └─ onActivityResult → startForegroundService(CastService.ACTION_START, resultData)
        └─ CastService.startCasting()
             ├─ 确保 UsbLink 已连接
             ├─ LcdControl.setDisplayMode(2)
             ├─ ImageReader(RGBA_8888, 2) + VirtualDisplay(屏幕比例)
             └─ onFrame():
                  acquireLatestImage → 映射到 640×400 → 拷进 frameBuf[16..]
                  → UsbLink.sendFrame() → 统计 fps
```

### 3.2 摄像头（Activity 内）

```
UvcPreview(camera)
   ├─ camera.open(dev, reuse = null)      // claim VC + VS + HID, probe/commit
   ├─ camera.start()                       // UvcReader 线程
   └─ Listener.onFrame(bitmap, bytes, fps)
        └─ ImageView.setImageBitmap()      // 不走 Compose 重组
```

### 3.3 控制 / 固件 / 音频

```
MainActivity 按钮 → LcdControl.xxx(usb.connection)      // 亮度/开关/模式/状态/版本
MainActivity.autoFlashBoot() → MyriadBootLoader.flash()  // 03E7 出现时自动
Peripherals.playTone()/micRms()                          // 音频测试
```

---

## 4. 构建、安装与运行

完整环境见 [app/BUILDING.md](../app/BUILDING.md)。最短路径：

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.10.7-hotspot"
cd app
C:\gradle\gradle-8.9\bin\gradle.bat assembleDebug --no-daemon --console=plain
adb install -r app\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.wzqvip.glxsscast/.MainActivity
```

运行前检查：

| 项 | 要求 |
|---|---|
| USB 权限 | 首次插上会弹窗；勾选"默认打开"可免重复 |
| 相机权限 | Manifest 已声明，启动时申请；UVC 需要它才给 USB 权限 |
| 录音权限 | 麦克风测试需要 |
| 通知权限 | Android 13+ 前台服务通知需要（`POST_NOTIFICATIONS`） |
| 固件文件 | 自动恢复需要 `/sdcard/Android/data/com.wzqvip.glxsscast/files/G25_MainFW_1.9.1.img` |

---

## 5. 调试指南

### 5.1 logcat

```powershell
adb logcat -s UsbLink CastService UvcCamera MyriadBoot Peripherals BootLoader
```

| TAG | 关键行 | 含义 |
|---|---|---|
| `UsbLink` | `opened if=3 ep=0x1 mps=512` | 连接成功、选对端点 |
| `UsbLink` | `claim HID if=2 -> true` | HID 已拿到（否则 `0xA1/0x17` 会 -1） |
| `CastService` | `pipeline 640×… mode=…` / `fps=25.0` | 投屏正常 |
| `UvcCamera` | `VS formats: …` | 描述符解析结果 |
| `UvcCamera` | `selected: format=YV12(idx=1) …` | 协商结果 |
| `UvcCamera` | `buffers: frameSize=460800 urbSize=460822` | URB 对齐原厂 |
| `UvcCamera` | `fps=25.0 (YV12 640x480)` | 出流正常 |
| `UvcCamera` | `invalid UVC payload … n=461824` | **固件异常**（旧版缓冲区大小），去重灌 |
| `MyriadBoot` | `decrypted tail: cipher=524288` / 无 `bulk fail` | 固件灌写正常 |

### 5.2 现场自证：抓原始 UVC 字节

UI：「打开摄像头」→「抓原始 UVC 字节（诊断）」，会写 30 个 URB 到
`Android/data/com.wzqvip.glxsscast/files/Pictures/uvc/uvc_raw_*.bin`。
格式：每个 URB 前 4 B 小端长度 + 原始字节。

```powershell
adb pull /sdcard/Android/data/com.wzqvip.glxsscast/files/Pictures/uvc/
```

健康数据：每个 URB 以 `0C 0F/0E` 开头、长度 460812；
异常数据：`n == urbSize`、首字节是亮度值（没有帧边界）。

### 5.3 设备侧状态

```powershell
adb shell "su -c 'lsusb'"
adb shell "su -c 'dmesg | grep -E \"idVendor|USB disconnect\" | tail -20'"
adb shell dumpsys usb | Select-String -Pattern "2e09|03e7"
```

App 内「读取状态」返回 8 B，格式见 [ch-display.md §3.2](ch-display.md#32-状态-8-b-布局)；
「读取真实固件版本」走 `0xA1/0x17`。

### 5.4 常见症状 → 章节

| 症状 | 先看 |
|---|---|
| 发了色条/投屏，屏幕不亮 | [ch-display.md §7 坑 3](ch-display.md#7-坑与注意事项)（屏 switch 被关） |
| UVC 一直 timeout | [device-recovery.md §1](device-recovery.md#1-三态判定表30-秒分清)（三态判定） |
| UVC 彩色噪点 | [ch-uvc.md §8 坑 1–3](ch-uvc.md#8-坑与注意事项) |
| 读固件版本失败 | [ch-hid.md §2](ch-hid.md#2-接口与-claim-语义最重要的一节)（claim） |
| 画面被压扁/裁错 | [ch-display.md §4](ch-display.md#4-手机端管线从-mediaprojection-到-640400)（映射模式） |
| 刷固件 -206 | [ch-firmware.md §4.3](ch-firmware.md#43-为什么原厂-app-会--206历史坑已修) |

---

## 6. 已知问题

| # | 问题 | 影响 | 现状/绕过 |
|---|---|---|---|
| 1 | HID 28 B 报告语义未解 | 无法做传感器类功能 | 只显示 hex；见 [ch-hid.md §4.2](ch-hid.md#42-怎么继续解建议) |
| 2 | 拔线后投屏 Service 不自动停 | 后台继续跑、每帧失败 | 手动「停止投屏」；待改成 DETACHED 自动停 |
| 3 | 旋转时 `resize()` 可能失败 | 抓取尺寸不匹配，画面裁错 | 状态栏提示；用「画面调整」zoom/offset 补偿 |
| 4 | 亮度不持久化 | 每次启动从 180 开始 | 设备端亮度其实是持久的；可加 SharedPreferences |
| 5 | 无帧率上限 | 源有多少帧就发多少 | USB 上限约 25–30 fps，实际由设备消费速度决定 |
| 6 | 拉伸模式不清屏 | 源宽 < 640 时右侧可能残留 | 用适应/裁剪；或给 `mapStretch` 加 `Arrays.fill` |
| 7 | UVC 没有前台 Service | App 退到后台可能被回收 | 投屏有前台 Service；UVC 目前只在 Activity |
| 8 | HID 中断端点读取是实验性的 | UI 开关可能收不到包 | 可靠路径是 `0xA1/0x16` 轮询 |
| 9 | 拍照只存 app 专属目录 | 不进系统相册 | 零权限；需要时接 MediaStore |
| 10 | 没有录像 | 缺功能 | 原厂用 MediaCodec + EasyMuxer，可参考 |
| 11 | 固件文件需手动放置 | 自动恢复不可用 | 提示明确；可加"从文件选择器导入" |
| 12 | 单设备 | 多眼镜需重启 App | 未做多设备管理 |

---

## 7. 下一步

按"价值 / 成本"排序：

| 优先级 | 事项 | 说明 |
|---|---|---|
| P0 | 拔线自动停投屏 + 重连恢复 | 体验硬伤；DETACHED 里停 Service，ATTACHED 里自动重连 |
| P0 | 亮度/模式持久化 | SharedPreferences，几行代码 |
| P1 | HID 报告解码 | 静止/旋转/遮光三组实验，见 [ch-hid.md §4.2](ch-hid.md#42-怎么继续解建议) |
| P1 | 帧率上限 / 带宽自适应 | 10/15/20/25 档；投屏 + UVC 同开时自动降 |
| P2 | 录像（MediaCodec + MediaMuxer） | 投屏/UVC 都能录；参考原厂 `EasyMuxer` |
| P2 | UVC 前台 Service | 后台预览/录制 |
| P2 | 1080p / MJPEG 实验 | 先测带宽，见 [ch-uvc.md §9](ch-uvc.md#9-待验证) |
| P3 | 人脸检测 / DTR 通道 | 协议未逆向，属于研究项，见 [ch-npu.md](ch-npu.md) |
| P3 | 多设备 / 国际化 / 主题 | 打磨 |

---

## 8. 扩展指南

### 8.1 加一条控制命令

1. 在 `lcd/LcdControl.kt` 里加一个 `controlTransfer(REQ_CTRL, bRequest, wValue, wIndex, buf, len, TIMEOUT)`；
2. `wIndex`：LCD=3，HID/AUX=2；
3. 先在 PC 上用 pyusb 验证，再进 App；
4. 把请求写进 [ch-display.md §3.1](ch-display.md#31-请求总表) 的表里。

### 8.2 加一种画面映射

1. 在 `CastService` 加 `MODE_XXX`；
2. 实现 `mapXxx(buf, w, h, rowStride, pixelStride, dst)`，**必须填满 dst**；
3. 在 `onFrame()` 的 `when(fillMode)` 里分派；
4. UI 的模式循环改成 `% 4`。

### 8.3 接一个新 UVC 格式

1. 在 `PixelMapping` 加枚举；
2. `estimateFrameBytes()` 加净荷估算；
3. `deliver()` 加解码分支；
4. `mappingOf(guid)` 加 GUID 映射。

### 8.4 写新 App 的最小骨架

只需要三样东西就能投屏：

```kotlin
// 1) 找设备 + 权限
val dev = usbManager.deviceList.values.first { it.vendorId == 0x2E09 && it.productId == 0x0030 }

// 2) 打开 + claim 厂商接口 + 找 bulk OUT
val conn = usbManager.openDevice(dev)!!
val itf = (0 until dev.interfaceCount).map { dev.getInterface(it) }
    .first { it.interfaceClass == 0xFF }
conn.claimInterface(itf, true)
val ep = (0 until itf.endpointCount).map { itf.getEndpoint(it) }
    .first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }

// 3) 组帧 + 发送（16 B 头见 ch-display.md）
conn.controlTransfer(0xA1, 0x03, 1, 3, ByteArray(1), 1, 5000)  // 开屏
conn.controlTransfer(0xA1, 0x05, 2, 3, ByteArray(1), 1, 5000)  // 显示模式 2
conn.bulkTransfer(ep, frame16PlusRgba, frame16PlusRgba.size, 5000)
```

更完整的 Python 版本见 [code/poc_send_frame.py](../code/poc_send_frame.py)。

---

## 9. 许可与合规

- 本项目**禁止商业使用**，见 [LICENSE](../LICENSE)；
- 仓库不包含 APK/固件/厂商 `.so`；
- 若你自行把厂商 `.so` 放进 `jniLibs/` 使用 `BootLoader` 路径，请自行确认许可与合规，
  并在分发时保留上游（serenegiant / Apache-2.0）声明；
- 与 LLVision 无关联，见 [NOTICE.md](../NOTICE.md)。
