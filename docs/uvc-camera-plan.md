# UVC 摄像头方案决策记录（历史）

> **这份文档现在只保留"为什么选纯 Kotlin"的决策过程和厂商 `.so` 的绑定细节。**
> 协议、代码、实测数据、踩坑已经移到 [第 2 章 · 摄像头](ch-uvc.md)，本文不再重复。
>
> 状态：方案 B（纯 Kotlin 自实现）已在真机跑通，20–25 fps；本文是选型留档，避免后来者重走弯路。

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| **推荐路径（主线）** | **方案 B：纯 Kotlin 自己实现 UVC**。零 native、零新依赖，已实测 |
| **最快验证** | 方案 D：加一行 `com.herohan:UVCAndroid:1.0.13` 当"探测器" |
| **保底回退** | 方案 A：拷厂商 6 个 `.so` + 153 个 native 声明；类名被 `.so` 写死，改动成本最高 |
| **排除** | 方案 C：external camera HAL——测试机型都不声明 `android.hardware.camera.external` |
| **设备真相** | 标准 UVC 1.10 + **bulk** 端点 + **未压缩 YV12 640×480**；早期"彩色噪点"是固件卡死，不是协议问题 |

协议细节见 [ch-uvc.md](ch-uvc.md)：
[描述符](ch-uvc.md#2-描述符与参数结构)、
[12 B header](ch-uvc.md#3-uvc-payload-header12-b)、
[YV12](ch-uvc.md#4-yv12-布局)、
[帧同步](ch-uvc.md#6-帧同步算法)。

---

## 1. 设备事实（已钉死，别再猜）

```
2e09:0030  bcdUSB 2.10  7 接口
INTF0  0x0e/0x01  VideoControl
INTF1  0x0e/0x02  VideoStreaming   ep 0x81 BULK IN 512   ← 只有这个跟摄像头有关
INTF2  0x03       HID
INTF3  0xff/0x01  LCD             ep 0x01 BULK OUT 512
INTF4-6 0x01      UAC
```

四条对实现有决定性影响的推论：

1. **是 bulk 不是 isochronous** → 不能照抄 iso 摄像头的 alt 时序；本设备 VS 只有 alt=0 就带端点。
2. **UVC 1.10（bcdUVC=0x0110）** → probe/commit 结构体按 **34 字节**发，失败再退 26 字节（UVC 1.0）。
3. **只有一个 bulk IN、maxpkt 512** → 一帧 640×480 未压缩（460800 B）会被拆成 ~900 个 512 B 包，
   但一次 `bulkTransfer` 拿到的就是一个完整 UVC payload（设备用末尾短包结束传输）。
4. **帧头 12 B，不是 2 B**；`actual = 460812 = 460800 + 12`。

---

## 2. 四个方案横向对比

| | **A. 复用厂商 .so** | **B. 纯 Kotlin（推荐）** | **C. external camera / Camera2** | **D. 现成库 UVCAndroid** |
|---|---|---|---|---|
| 需要什么 | 6 个 `.so` × 2~5 个 ABI + 153 个 native 声明 + 9 个回调/数据类 | 无（只用 `android.hardware.usb`） | 无 | 1 行 Gradle 依赖（AAR 内含 .so） |
| 新增二进制 | ~900 KB/ABI（arm64 + v7a ≈ 1.8 MB） | 0 | 0 | ~1 MB（Maven Central） |
| 新增代码量 | 绑定层 ~250 行 + 调用 ~120 行 | ~1200 行（`uvc/` 三个文件） | ~30 行 | ~100 行 |
| 首次跑通概率 | 高（厂商同一套代码在这台设备上跑过） | **高（已实测）** | **≈0** | 中（取决于是否支持 YV12 未压缩） |
| 主要坑 | **类名写死在 .so 里**、`libstdc++`、ABI、许可证 | probe 参数要调、CPU 转 YUV | 厂商没编 HAL | 上层 API 可能只认 MJPEG/YUYV |
| 预览方式 | `nativeSetPreviewDisplay(Surface)` 直接画，CPU 最低 | Bitmap → ImageView（当前） | Camera2 → Surface | SurfaceView/TextureView |
| 许可证 | Apache-2.0（serenegiant）+ 厂商改动，需保留 NOTICE | 无 | — | Apache-2.0 |
| 与 `UsbLink` 共存 | 要自己传 fd/busnum/devnum，不共用 connection | 可复用 `UsbLink.connection` | 不适用 | 自己 `openDevice`（多一个 fd） |
| 建议 | 保底 | **主线（已采用）** | 排除 | 验证用 |

### 2.1 为什么 C 直接排除

- `android.hardware.camera.external`（`PackageManager.FEATURE_CAMERA_EXTERNAL`，API 20 起）要求
  **OEM 把 external camera HAL 编进产品**（AOSP 的 `ExternalCameraProvider`）；
- 测试过的机型（LG G8 / Pixel）都没有声明这个 feature；
- 就算有，Camera2 的 ID 也不固定，还得全扫 + 看 `LENS_FACING_EXTERNAL`，比方案 B 麻烦。

一条命令自查：`packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_EXTERNAL)`。

### 2.2 为什么 A 不是首选（尽管"proven"）

`libUVCCamera.so` **没有导出任何 `Java_*` 符号**（`nm -D` 里 `.text` 段只有 `JNI_OnLoad`），
它在 `JNI_OnLoad` 里用 `RegisterNatives` 一次性注册 **153 个** native 方法到一个
**硬编码的类名**：

```
com/llvision/glass3/sdk/camera/UVCCamera
（配套还有 CameraAeData / CameraCapability / CameraExif / CameraFdData /
 CameraFaceLoc / IButtonCallback / IFrameCallback / IStatusCallback 等类型名）
```

后果：

1. 不能用 GitHub 上 `com.serenegiant.usb.UVCCamera` 的 Java 源码——包名不匹配，
   `FindClass` 直接失败 → `JNI_OnLoad` 返回 -1 → `UnsatisfiedLinkError`。
2. 必须建一个**同名同包**的类，且 153 个 native 声明**一个都不能少**
   （`RegisterNatives` 按数组整体注册，缺一个就整体失败）。
3. 该类原本依赖厂商一堆类（`usb.b`、`util.a`、`LogUtil`、`ResultCode`…），
   但只需要 native 声明 + 静态 `loadLibrary` 块，其余逻辑可以自己写。
4. 静态块里的加载顺序是厂商写死的：

```java
System.loadLibrary("jpeg-turbo1500");
System.loadLibrary("uvc");
System.loadLibrary("UVCCamera");
```

   `libusb100` / `libcommon` / `libyuvto` 没显式加载，靠 `DT_NEEDED` 带进来。
   稳妥做法是显式预加载（`usb100` → `common` → `jpeg-turbo1500` → `yuvto` → `uvc` → `UVCCamera`）。
5. `nativeConnect` 的真实参数顺序（与上游
   `UVCCamera::connect(int vid, int pid, int fd, int busnum, int devaddr, const char *usbfs)` 一致）：

```
nativeConnect(long ptr, int vid, int pid, int fd, int busnum, int devnum, String usbfsDir) : int
                       ↑2e09    ↑0030    ↑getFileDescriptor()  ↑deviceName 解析     ↑"/dev/bus/usb"
```

   最后一个参数是 **usbfs 目录**（`/dev/bus/usb`），不是设备全路径。

6. `libstdc++.so` 是平台库（不在 APK 里）。有反证表明测试机型上仍可解析，
   但换 ROM/换机型仍可能炸，方案 A 必须把这条列进风险。

### 2.3 方案 D 的具体形态

```kotlin
// app/app/build.gradle.kts
implementation("com.herohan:UVCAndroid:1.0.13")   // Maven Central
```

- 内部就是 `com.serenegiant.usb.*` + serenegiant 的 prebuilt `.so`，与方案 A 同血统；
- 适合当"这设备到底是不是标准 UVC"的探测器；
- 风险：它的上层 `CameraHelper` 默认按 MJPEG/YUYV 走，遇到只有 YV12 未压缩的设备
  可能在 `uvc_get_stream_ctrl_format_size` 就返回 `UVC_ERROR_NOT_SUPPORTED`；
- 备选：`com.github.jiangdongguo.AndroidUSBCamera`（走 JitPack，构建偶尔抽风）。

---

## 3. 回退链

```
B（纯 Kotlin）不通
   → 用 D 花 30 分钟确认设备是否标准（D 通了就直接用 D）
   → D 也不通
   → 方案 A（搬厂商 .so + 补 153 个声明）
```

当前 B 已通，回退链仅作记录。

---

## 4. 真机抓包与修复（2026-11-21，已完成）

> 本节只留结论；完整证据、代码 diff、logcat、截图见
> **[ch-uvc.md §7–§8](ch-uvc.md#7-实测数据)**。

- 设备的帧格式从头到尾都是**标准 UVC 1.10 bulk + 12 B header + YV12 640×480**；
- "彩色噪点"不是 header 偏移/U/V 顺序/stride 问题，而是**固件媒体流水线卡死、在灌无边界垃圾流**；
  同一时刻原厂 libuvc 也是 3×`TIMED_OUT`；
- 代码修复：URB 对齐原厂（`frameSize + 22`）、删除 raw 兜底、连续 10 个无效 URB 报错、
  增加原始字节抓包；
- 重灌固件后我们 App 与原厂 App 同时恢复出图（20–25 fps）。

---

## 附录 A：`libUVCCamera.so` 全量 native 速查（方案 A 用）

`nm -D libUVCCamera.so`：145 个动态符号，`.text` 段只导出 `JNI_OnLoad`，零个 `Java_*`
⇒ 全靠 `RegisterNatives` + 硬编码类名
`com/llvision/glass3/sdk/camera/UVCCamera`（`.so` 里还有
`serenegiant_usb_UVCCamera.cpp` 的编译路径，证明它是 serenegiant 那套改了包名/加了功能）。

常用签名（从 `.so` 的 JNI 签名字符串核对过）：

```
(JIIIIILjava/lang/String;)I          nativeConnect(id, vid, pid, fd, busnum, devnum, usbfsDir)
(JIIIIIF)I                           nativeSetPreviewSize(id, w, h, minFps, maxFps, format, bandwidth)
(JLcom/...IFrameCallback;I)I         nativeSetFrameCallback(id, cb, pixelFormat)
(JLandroid/view/Surface;)I           nativeSetPreviewDisplay / nativeSetCaptureDisplay
(J)Ljava/lang/String;                nativeGetSupportedSize
(J)J  (J)I  (JI)I  (JZ)I  (J)V       getCtrlSupports / nativeOpen / setXxx / destroy ...
(J)Lcom/...CameraAeData;             nativeGetAutoExposure
(J)Lcom/...CameraCapability;         nativeGetCameraCapability
(J)Lcom/...CameraExif;               nativeGetExif
(J)Lcom/...CameraFdData;             nativeGetReportFdResult
(ILcom/...CameraFaceLoc;×4)V         nativeStartFaceDetection 一类
(Ljava/nio/ByteBuffer;)V             IFrameCallback#onFrame
```

### A.1 生成绑定层（不想手抄 153 个）

```powershell
$src = 'jadx-out\sources\com\llvision\glass3\sdk\camera\UVCCamera.java'
$dst = 'app\app\src\main\java\com\llvision\glass3\sdk\camera\UVCCamera.java'
Select-String -Path $src -Pattern 'native ' |
  ForEach-Object {
    $l = $_.Line.Trim()
    $l = $l -replace '^private static final native', 'public static native'
    $l = $l -replace '^private final native',         'public native'
    $l = $l -replace '^private static native',        'public static native'
    $l
  } | Set-Content -Encoding UTF8 $dst
```

再补 `package` + 类壳 + 静态 `loadLibrary` 块，以及 9 个空壳类/接口
（`CameraAeData`、`CameraAfData`、`CameraCapability`、`CameraExif`、`CameraFdData`、
`CameraFaceLoc`、`IButtonCallback`、`IFrameCallback`、`IStatusCallback`）。
**包名一个字母都不能改**，因为 `.so` 是按字符串找的。

### A.2 许可证提醒

serenegiant/UVCCamera 是 **Apache-2.0**；厂商二进制是它的衍生版。
拷 `.so` 进自己 App 时要在 `NOTICE.md` 里保留 upstream 版权与 Apache-2.0 声明，
并注明"二进制来自原厂 App、含厂商修改"。

---

## 附录 B：一句话

> 这台眼镜是**标准 UVC 1.10 + bulk 端点 + 未压缩 YV12 640×480**。
> 最省事的不是搬厂商 native，而是自己发 5 个控制传输 + 一个 bulk 读循环——
> 这就是 [`uvc/UvcCamera.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/uvc/UvcCamera.kt)。
