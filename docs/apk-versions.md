# 三个 APK 版本对比（GLXSS ME 1.8.4 / 原厂 2.3.7 / 社区 2.3.7）

> 范围：静态分析（jadx + 原生库字符串 + 固件解密复算）。**未刷写设备、未动手机、未修改仓库其它文件。**
> 结论分级沿用仓库惯例：**【验】已复算/有原始输出**、**【推】由代码与数据强推断**、**【未】未确认**。

## 0. 一句话结论

1. 新给的两个"较早版本"里，`GLXSS ME.apk` **不是相机 App**，而是 **应用商店 App**：包名 `com.llvision.glass3.appstore`、`versionName=1.8.4`、`versionCode=21`，界面自称 **GLXSS ME**（`res/values/strings.xml: device_name`）。它是**目前唯一能拿到 `G25_MainFW_1.7.4` 固件的来源（1.7.4 是全新发现的第二份固件）**。**【验】**
2. `1_亮亮眼镜拍照.apk` **就是原厂 2.3.7 本体**（`com.llvision.glxss3.camera`，vc15），它的 3 个 asset 与社区二合一版**逐字节相同**，所有 ABI 的 `.so` 也与社区版**逐字节相同**——社区版只改了 dex（3.81 MB → 4.05 MB），没动 native、没动固件。**【验】**
3. 三份固件共用**同一把 AES key**（`b5Q8JBFRYYIRawl3`，由 `libboot.so` 内置 RSA 私钥解 `llvision.enkey` 得到），`enkey` 三者 sha256 完全相同 → **厂商从未轮换固件密钥**。**【验】**
4. 1.7.4 与 1.9.1 **同源同构建日**：`BUILD DATE` 与 `GIT VERSION` 字符串完全一致（`Thu Jun  9 06:59:57 EEST 2016` / commit `6d821e59`），镜像只差 **2,712 字节**代码和版本号。所以 1.7.4 是"同一代的旧 build"，不是"另一代功能固件"。**【验】**
5. **投屏协议、HID/LCD 命令、固件刷写流程在 1.8.4 与 2.3.7 之间没有任何变化**：LCD 的 10 个 JNI 方法名集合、16 字节帧头常量、`bootInit/bootDownload(path, keyPath, cb)` 签名、USB 设备过滤表全部相同 → 我们的替代 App **一套协议、全版本通用**。**【验】**

---

## 1. 样本与哈希

| # | 文件 | 大小 (B) | sha256 |
|---|---|---|---|
| A | `GLXSS ME.apk`（应用商店 1.8.4） | 7,284,050 | `c13c4000f3135fae8cdb39109439df0534ddb69acff302e07c49c88f498951e2` |
| B | `1_亮亮眼镜拍照.apk`（原厂 2.3.7） | 9,431,735 | `2929431537cd0fdb9e7672309e561c0f8d416c7689288643cb81d5e2629d5569` |
| C | `re/liangliang/app.apk`（社区二合一 2.3.7） | — | `56e9bccd621ed9c31a50a7d1856e5b0d71cda35832f87a75e6aa8e165502d5df` |

工作副本（**在仓库外**，可随时删除）：`re/old-apks/{glxss-me.apk, liangliang-photo.apk, me-x/, photo-x/, me-jadx/, photo-jadx/, fw-1.7.4.dec.bin, fw-1.9.1.dec.bin}`。

## 2. 三份 APK 的基本信息【验】

| 项 | A：GLXSS ME（应用商店） | B：原厂 2.3.7（拍照） | C：社区二合一 2.3.7 |
|---|---|---|---|
| package | `com.llvision.glass3.appstore` | `com.llvision.glxss3.camera` | `com.llvision.glxss3.camera` |
| versionName / Code | **1.8.4 / 21** | **2.3.7 / 15** | 2.3.7 / 15 |
| minSdk / targetSdk | 21 / 25 | 22 / 25 | 22 / 25 |
| platformBuild | Android 7.1.1 (code 25) | 同 | 同 |
| ABI 目录 | **3**：armeabi, armeabi-v7a, x86 | **5**：+ arm64-v8a, x86_64 | 5（与 B 相同） |
| classes.dex | 3,808,804 B | 3,810,724 B | 4,046,892 B |
| UI 文案 | `app_name=GLXSS`、`device_name=**GLXSS ME**`、`app_store=应用商店` | `app_name=**亮亮拍**`、`llvision_sdk_app_name=GLXSS Suite Pro`、`llvision_sdk_device_name=**GLXSS ME**`（同一套 SDK 资源） | 同 B |
| 主 Activity | `SplashActivity` → `MainActivity`（**设备控制面板**：亮度 / 同步屏幕 / 常亮 / 关于 / 连接状态；连点图标 5 次切 debug）+ `SettingsActivity`、`AboutActivity`、`ChipInfoActivity`、`TestActivity` | `MyApplication` + `RemoteCameraActivity`（预览/拍照/录像）+ `SettingsActivity`（画质偏好） | 同 B（多 `BaseActivity`） |
| 与眼镜相关服务 | Platform/Sensor/Light/KeyEvent/LCD/**UVC**；**无** `UsbHostDataTransferService` | 上述全部 **+** `UsbHostDataTransferService`（`com.llvision.usb.host.IHostAIDL`） | 同 B |
| 权限要点 | WRITE/READ_EXTERNAL_STORAGE、RECORD_AUDIO、INTERNET、USB host、MOUNT_UNMOUNT_FILESYSTEMS、SYSTEM_ALERT_WINDOW、WAKE_LOCK | INTERNET、**WRITE_SETTINGS**、**READ_LOGS**、WRITE_EXTERNAL_STORAGE、RECORD_AUDIO、SYSTEM_ALERT_WINDOW | 同 B |
| 自身 OTA 端点 | `https://update.llvision.com/check/app_storeG25?tested=1`（App Store 自更新）+ `https://update.llvision.com/check/app`（应用商店内容） | `https://update.llvision.com/check/...`（App 自更新） | 同 B |

> 说明：A 的 `MainActivity:200-205` 把"设备版本"文本框直接写成 **asset 文件名**（`G25_MainFW_1.7.4`），不是从眼镜读的——与 2.3.7 里同样的硬编码做法一致（见 [firmware-extraction.md](firmware-extraction.md) §4.5）。**【验】**

## 3. assets（固件）对比

| asset | A：1.8.4 | B：原厂 2.3.7 | C：社区 2.3.7 |
|---|---|---|---|
| 固件 | **`G25_MainFW_1.7.4.img`** 1,522,416 B<br>sha256 `234083755b3bc67f32a7267063d0abf703b2c4355efa5a7cd61297614e6f668e` | `G25_MainFW_1.9.1.img` 1,525,136 B<br>sha256 `5b4246ba8a738f74be8938e0d174107e058a6303aa69f5538734f081dde68bca` | **与 B 逐字节相同** |
| `llvision.enkey` | 128 B `04063d14d1bdcbf34df093ae21d0ee12570145369b3ea2e71edbc32ba973676c` | 同 | 同 |
| `logo.bin` | 1,024,000 B `6692c79b7bc29ef7ae0c0d0d73f9b54ba5796da8213c0badcbf849e764bb0f59` | 同 | 同 |

### 3.1 1.7.4 已成功解密（沿用仓库方法）【验】

`docs/firmware-extraction.md` §1.5 的流程对本样本同样成立；`libboot.so` 里的 **RSA 私钥 PEM 完全相同**（886 B，1.8.4 与 2.3.7 逐字节一致），AES key 同为 `b5Q8JBFRYYIRawl3`，尾部 `0x80000` 用 AES-ECB 解密后**明文连续性校验为 True**：

```
1.7.4: rawlen=1,522,416 (0x173af0)  plain[-1]=4  -> canonical len=0x173adc (1,522,396)
       canonical sha256 = 5bf7eaea3999b7df3972584371ef28171a0eeff5e5b9e33a60a23a36593e139d
1.9.1: rawlen=1,525,136 (0x174590)  plain[-1]=12 -> canonical len=0x174574 (1,525,108)
       canonical sha256 = beaed6c7a49569544dc816ac97d0a2bb82b213593f45beb2ee8629c2992b9d60  ← 与既有文档一致
```

两条镜像的 `MA2x` 容器头完全一致（`4d413278` + 0x1ba8 + `ddrInitHeaderForMvcmd.elf` + `.text`），即**一级引导/DDR 初始化段相同**，只有 RTEMS 主固件段不同。

### 3.2 1.7.4 vs 1.9.1：同源、同构建日、差 2,712 B【验】

| 证据 | 1.7.4 | 1.9.1 |
|---|---|---|
| `BUILD DATE` | `Thu Jun  9 06:59:57 EEST 2016`（偏移 0x4990） | **完全相同** |
| `GIT VERSION` | `CLEAN 2016-06-09 ... refs/heads/temp_proposed_golden_driver_ma2450` + `6d821e59...` | **完全相同**（同一 commit） |
| 版本串 | `G25_MainFW_1.7.4` @0x101474 | `G25_MainFW_1.9.1` @0x101474 |
| canonical 长度 | 1,522,396 | 1,525,108（+2,712） |
| 首个差异字节 | — | **0x101481**，即版本串里的 `7`→`9` |
| 差异区间 | 除上面 2 B 外，集中在 `0x11b29a–0x11e49d`（9 小段，65–1309 B）与 `0x11e7a8–EOF`（整体错位重编译） | — |
| 新增字符串 | — | `HglCpr.c`（LEON **时钟/电源/复位** 驱动） |

**结论**：1.9.1 是同一源码基线的**修复 build**（新增/启用 `HglCpr` CPR 驱动，代码 +2.7 KB），1.7.4 是它的前一个 build。**不存在"1.7.4 是另一个功能代、支持更多/更少硬件"的情况**。**【验】**

## 4. 协议与控制命令对比

| 项 | A：1.8.4 | B/C：2.3.7 | 差异 |
|---|---|---|---|
| 帧头（`LCDProfileUtils`） | `format=1`、4 个 format 字节、`totalLength` LE、8 字节 extended | 同 | **无** |
| `LCD` JNI 方法集 | 10 个：`nativeLCDInit/SetLuminance/SetSwitch/SetRoll/SetDisplayMode/GetInfo/TransferStream/WriteCustomFields/Destory + nativeProductGetInfo` | 同（名字、签名全同） | **无** |
| 导出符号 | — | — | `libhid`: `sensor_get_product_info`、`sensor_get_light`、`sensor_set_indicator_light`、`hid_get_movi_last_state`… **两版相同**；`liblcd`: `lcd_get_info`、`lcd_set_luminance`、`lcd_set_display_mode`、`lcd_set_roll` **两版相同**【验】 |
| 刷写流程 | `Boot.bootInit(fd,bus,addr,usbfs)` → `bootDownload(fwPath, keyPath, callback)`；`BOOT_VID=999 (0x03E7)`、`BOOT_PID=8528 (0x2150)`；超时/回调状态机 `BOOT_START/SUCCESS/FAIL` | 同 | **无** |
| USB 过滤 | `0x2e09:0x0030`（正常）+ `0x3e7:0x2150`（引导），`res/xml/device_filter.xml` | 同（B 另有只含 `0x2e09:0x30` 的 `device_filter_llvision.xml`） | 实质无差异 |
| 版本查询 `0xA1/0x17` | 由 `nativeProductGetInfo`（`libhid` 的 `sensor_get_product_info`）实现，1.8.4 **已存在** | 同；2.3.7 额外在 Java 侧加了 `LCD.getNativeProductInfo()` | 仅 Java 包装层差异 |

**对我们最有用的协议结论**：`0xA1/0x17` 版本查询、`0xA1/0x07` EEPROM 产品信息、亮度/模式/翻转控制、16 B 帧头 —— 这套接口**从 1.8.4 起就冻结了，三份 App 通用**。我们的替代 App 不需要做"版本适配分支"。**【验】**

## 5. native 库对比（armeabi-v7a；B 与 C 逐字节相同）【验】

| so | A 1.8.4 (B / sha256 前缀) | B 2.3.7 (B / 前缀) | 说明 |
|---|---|---|---|
| `libboot.so` | 30,292 `400487b78e` | 30,292 `2bb478a8f5` | 仅调试路径字符串不同；RSA 私钥 PEM **完全相同** |
| `libcommon.so` | 17,872 `ca57c2489d` | 17,872 `777e7e563b` | B **新增** `usb_get_config_descriptor` / `libusb_get_config_descriptor` / `endpoint_bulk|interrupt` 字符串 |
| `libhid.so` | 30,412 `5b98ffc596` | 30,412 `96b4fcc0df` | 导出符号相同 |
| `liblcd.so` | 26,232 `5e9b243e0e` | 26,232 `052bd891f2` | 导出符号相同 |
| `libuvc.so` | 120,644 `3226610a07` | **116,548** `74adcfa859` | B 新增 `I420ToARGB`、`I420ToNV21`、`uvc_YV12ToYUY2`、`uvc_stream_stop` 日志、`gettimeofday` |
| `libUVCCamera.so` | 118,060 `5301f15f5b` | **122,156** `714743cbde` | A 有 `nativeGetCameraReportData` + `CameraReportData`/`CameraFdData`；B **删除**该 API，**新增** `nativeGetmAutoFocus`、`do_preview`、`capture_thread`/`preview_thread`、`PIXEL_FORMAT_YUY2` 日志 |
| `libyuvto.so` | 165,500 `5b61a1660d` | 165,500 `881e467e7c` | 仅 1 个字符串差异（同一 libyuv 构建） |
| `libusb100.so` | 104,016 | 104,016 | 仅构建路径 |
| `libjpeg-turbo1500.so` | 288,468 | 288,468 | 仅构建路径 |
| `libopenssl.so` | 1,649,504 | 1,649,504 | 仅构建路径 |
| `libfacedtr-libusb.so` | **不存在** | 17,992 `02e669bffd`（5 ABI 全有） | B 新增人脸检测专用库 |

**构建来源（字符串证据）**：
- A（1.8.4）：`/Users/haijianming/AndroidStudioProjects/project/ASWorkSpaceTest/G25_platform_sdk/platform_lib/...` → **开发者本机 build**。
- B/C（2.3.7）：`/media/user-disk/share/hudson/jobs/G25_paltform_sdk_DTR/workspace/glxss_face_dtr/platform_lib/...` → **Jenkins(Hudson) CI 的 `glxss_face_dtr` 分支**（DTR = detect/recognize）。

即：2.3.7 的 SDK 来自**人脸识别分支的产物**，1.8.4 是更早的手工构建。**【验】**

## 6. 代码/功能差异（1.8.4 → 2.3.7）

| 能力 | 1.8.4（A） | 2.3.7（B/C） | 对我们的意义 |
|---|---|---|---|
| 人脸检测 | **走摄像头 report**：`libUVCCamera.nativeGetCameraReportData()` 返回 `CameraReportData{afResult,cafFlag,cafStatus,CameraFdData{face_num, faceLoc0..3}}` | 改为**独立通道**：`libfacedtr-libusb.so` + `sdk/facedtr/FaceDtr`，用 `nativeSendStream/nativeGetStream` 把帧送进 Myriad、取回结果；配套 `sdk/memory/*`（`SharedMemoryController`）+ `UsbHostDataTransferService` | 说明眼镜里有一条**双向 vendor 数据通道**（不止 bulk 投屏）；`FaceDtr` 还导出了通用 `nativeControlTransfer(int,int,int,int,byte[],int,int)`。这是固件→主机回传数据的唯一已知路径，值得单独逆向（目前只用 bulk OUT + control IN，没有 IN 端点） |
| UVC 预览尺寸 | 只有常量 `DEFAULT_PREVIEW_WIDTH=1280 / HEIGHT=720`、`FRAME_FORMAT_YUYV=0 / MJPEG=1` | 新增显式尺寸表 `DEFAULT_PREVIEW_SIZE_LIST`：**(1920×1080, idx0)**、**(1280×720, idx2/idx6)**、**(640×480, idx3)**；`OLD_DEFAULT_PREVIEW_SIZE_LIST` 保留旧的 720p/480p | 眼镜 UVC 摄像头**支持 1080p**；我们的 `uvc-camera-plan.md` 可直接按 idx 0/2/3/6 去枚举，不用猜 |
| 自动对焦 | 无（AF 结果只在 report 里） | 新增 `IUVCServicesAutoFocusCallback`、`IAutoFocusMoveCallback`、`nativeGetmAutoFocus`、UVC PU 控制 | 相机可以手动/AF 控制 |
| 拍照/录像规格 | 无偏好设置界面 | `SettingsActivity`/`preferences.xml`：照片 **(4:3) 800 万像素**、录像 **720p@30fps**（高质量/标准两档）、含录音 | 8 MP 静态拍照不是 UVC 1080p 流能给的，应有**另一条取全分辨率静像的路径**（待查：很可能走 vendor 接口） |
| 录像实现 | `CameraServer.handleStartRecording` → `encoder2.EasyMuxer` + `mVideo/mAudio` | **同一套** `EasyMuxer`（`CameraClient.handleStartRecording`） | **旧版没有"更高级的录制功能"可抄**；差别只是 A 多打包了上游 serenegiant demo 库（`com.serenegiant.media.*`、`mediaeffect.*` 滤镜、`MediaAudioEncoder` 等），llvision 自己的录制路径没用到它们 |
| 共享内存 | 无 | `sdk/memory/NewMemoryFile`、`SharedMemoryController`、`SharedMemoryDescriptor` | 2.3.7 用 ashmem 把帧零拷贝给原生 DTR |
| 设备数据传输 | 无 | `com.llvision.usb.host.*`（`IHostAIDL`、`CommandCode`、`ResLoadResult`、`ProxyUtils`）+ `UsbHostDataTransferService` | 眼镜**反向向手机请求资源**的 AIDL 协议，可参考结构 |

## 7. 对我们有价值的结论

- **F1（固件 fallback）**：`G25_MainFW_1.7.4` 是本项目**第二份真实固件**，可作恢复备选。它是 1.9.1 的前一个 build（同 commit、同构建日、差 2.7 KB 的 CPR 驱动）。用途：若怀疑 1.9.1 的时钟/复位相关行为异常，可用 1.7.4 对照验证。**【验】**
- **F2（刷写规则不变）**：两份镜像只有 canonical 长度不同（0x173adc vs 0x174574），刷写流程、`MA2x` 容器头、`0x03E7:0x2150` 引导协议**完全一致** → 我们自己的 flasher 只需按 `size-16-plain[-1]` 算长度，对两版固件通用。**【验】**
- **F3（密钥未轮换）**：`enkey` 与 `libboot` 内 RSA 私钥在三份 APK 里完全相同 → **解密算法/密钥是一次性写死的**，可以放心把"解 `enkey` → AES-ECB 解 `0x80000` 尾巴"实现成通用工具。**【验】**
- **F4（协议稳定）**：16 B 帧头、亮度/开关/翻转/显示模式、`0xA1/0x17` 版本查询从 1.8.4 起未变 → 替代 App **不需要按 App 版本做兼容分支**；`0xA1/0x17` 可以大胆用于"连接后显示固件版本"。**【验】**
- **F5（UVC 尺寸表，可直接落地）**：2.3.7 里硬编码的 UVC 帧描述符表给出了眼镜摄像头的真实能力：1080p（idx0）、720p（idx2/6）、480p（idx3），格式 `YUYV=0` / `MJPEG=1`。写 UVC 客户端时按这三档枚举即可，`uvc-camera-plan.md` 里的 1080p 假设得到证实。**【验】**
- **F6（录像）**：没有"旧版独有录像功能"。两版都用 `EasyMuxer`（MediaCodec + MediaMuxer）出 mp4；1.8.4 多的只是上游 serenegiant demo 代码。若我们要做录制，参考 `CameraServer.handleStartRecording/handleStopRecording` 即可，无需逆向 1.8.4 的 mediaeffect。**【验】**
- **F7（人脸/视觉通道）**：1.8.4 的 `CameraReportData`（face_num + 4 个 faceLoc + AF 状态）证明**固件本身会做人脸检测并把坐标回传**；2.3.7 改成主机侧送帧（`libfacedtr-libusb`，18 KB，5 个 ABI 都有）。两条路径都是"厂商私有协议"，是我们目前唯一见到的**眼镜→主机数据回传**线索（`FaceDtr` 里还有通用 `nativeControlTransfer`）。**【推】**
- **F8（ABI 取舍）**：1.8.4 只有 armeabi/armeabi-v7a/x86；2.3.7 全 ABI（含 arm64-v8a、x86_64）。若要在 arm64-only 设备上跑旧 App，只有 2.3.7 可用 → **不要为了拿 1.7.4 而在 arm64 手机上装 1.8.4**（装不上）。**【验】**
- **F9（App Store 自更新端点）**：`https://update.llvision.com/check/app_storeG25?tested=1` + `X-API-Key: A72E805D24DDCDE9C2A0663D98F841A4`，返回 `human_version/url/changelog`，直接下 APK。意味着**厂商可能还有比 1.8.4 更旧的 App Store 版本/更多固件在服务器上**；这是唯一已知的、按版本取 APK 的自动化入口（只读 GET，可用来找 1.6.x/1.7.x 的固件）。**【推】**（未联网验证）
- **F10（社区版定位）**：社区二合一版 = 原厂 2.3.7 dex 的改动版，native/固件零改动 → **任何基于 native 行为的结论在两者间通用**，社区版多出来的 dex 内容（4.05 MB vs 3.81 MB）才是它的差异点（未在本次范围内展开）。**【验】**

## 8. 风险

- **R1（最危险）**：**任何**一份原厂 App（含 1.8.4）在检测到 `0x03E7:0x2150` 引导模式时都会**无条件**用自己 assets 里的固件刷写，**没有版本比较**（`LLVisionPlatformService.onStartDevice` → `bootInit` → `bootDownload`）。所以：**把 1.8.4 装到同一台手机上，插上眼镜就会把 1.9.1 降级成 1.7.4**。测试 1.8.4 时务必用独立设备/离线手机，或先备份并确认能刷回 1.9.1（素材都在，风险可控但会中断使用）。**【验】**
- **R2**：1.7.4 的 `canonical len` 推导依赖"最后一个字节 = 尾部长度"这一规律（在 1.9.1 上复算出与既有文档完全一致的 sha256）。1.7.4 上该规律**未在真机验证**；如果刷写工具把 1,522,396 写成 1,522,416（整文件），眼镜可能加载失败。建议：先用 1.9.1 验证我们的 flasher，再试 1.7.4。**【推】**
- **R3**：App Store 1.8.4 的 `TestActivity` 里有 `Security.decryptRSA(enkey)` 测试按钮（直接读 assets 里的 enkey 做 RSA 解密）。若误触会打日志但**不会写设备**；仍建议不要在生产机上乱点。**【验】**
- **R4**：1.8.4 的 `ChipInfoActivity` 只是半成品（与既有文档 §4.6 一致），不要指望它读芯片信息。**【验】**
- **R5**：**不要**把 APK/固件/解密镜像放进仓库（`.gitignore` 已排除？本仓库 `docs/` 只放文档）。本次所有二进制产物都在 `re/old-apks/`，属仓库外目录。**【验】**
- **R6**：`update.llvision.com` 的接口未验证（本次未联网）。若要用 F9 去找旧固件，注意这是厂商服务器，仅做只读 GET、控制频率。**【未】**

## 9. 复现命令

```powershell
# 解包（7-Zip）
& "C:\Program Files\7-Zip\7z.exe" x GLXSS\ ME.apk -o me-x -y

# 反编译（本机 jadx）
java -cp "C:\Users\WANGZ\Desktop\sshtest\re\liangliang\tools\jadx\lib\*" `
     jadx.cli.JadxCLI -d me-jadx --deobf --show-bad-code "GLXSS ME.apk.1.1"
```

```python
# 固件解密（两版通用；依赖 pycryptodome）
from Crypto.PublicKey import RSA
from Crypto.Cipher import AES, PKCS1_v1_5
import hashlib
raw = open('G25_MainFW_1.7.4.img', 'rb').read()
enc = open('llvision.enkey', 'rb').read()
so  = open(r'me-x\lib\armeabi-v7a\libboot.so', 'rb').read()
i = so.find(b'-----BEGIN RSA PRIVATE KEY-----')
j = so.find(b'-----END RSA PRIVATE KEY-----', i)
rsa = RSA.import_key(so[i:j + len(b'-----END RSA PRIVATE KEY-----')])
key = PKCS1_v1_5.new(rsa).decrypt(enc, b'')[:16]          # b'b5Q8JBFRYYIRawl3'
start = len(raw) - 0x80000
plain = raw[:start] + AES.new(key, AES.MODE_ECB).decrypt(raw[start:])
out_len = len(raw) - 16 - plain[-1]                        # 1.7.4 -> 0x173adc
print(hex(out_len), hashlib.sha256(plain[:out_len]).hexdigest())
```

## 10. 证据索引

| 结论 | 证据位置 |
|---|---|
| A 是应用商店 App / 版本 1.8.4 | `me-jadx/resources/AndroidManifest.xml:2-6`；`res/values/strings.xml`（`device_name=GLXSS ME`、`app_store=应用商店`） |
| A 的固件名硬编码 | `me-jadx/sources/.../appstore/util/FileUtils.java:19`；`.../activity/MainActivity.java:200-205` |
| 刷写无版本比较 | `.../sdk/service/LLVisionPlatformService.java:153-160, 299-320, 334`；`.../sdk/boot/Boot.java:38-67` |
| B 的固件名硬编码 | `photo-jadx/sources/.../appstore/activity/MainActivity.java:251-254`；`.../appstore/p001a/C0287a.java:21,64` |
| 1.7.4/1.9.1 同构建日 | 解密镜像 0x4990 / 0x10518a 处字符串（脚本 `re/old-apks/fwdiff.py`） |
| `HglCpr.c` 仅 1.9.1 有 | 同上（`fwdiff.py` 输出 only-in-1.9.1） |
| JNI/导出符号未变 | `libhid.so`/`liblcd.so` 字符串集合（`re/old-apks/sodiff.py`） |
| UVC 尺寸表 | `photo-jadx/sources/.../sdk/camera/IUVCCameraService.java:112-115` |
| 人脸通道迁移 | `me-jadx/.../sdk/camera/CameraReportData.java`；`photo-jadx/.../sdk/facedtr/FaceDtr.java`；`libUVCCamera.so` 字符串差异 |
| App Store 自更新 | `me-jadx/.../appstore/util/UpdateManager.java:70, 229` |

**复算脚本**（均在仓库外）：`re/old-apks/{an.py, dec.py, fw2.py, fw3.py, fwdiff.py, sodiff.py}`。
