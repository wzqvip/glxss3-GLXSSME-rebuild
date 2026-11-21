# 设备画像（已并入新文档）

> 设备画像、USB 拓扑、固件概况已经移到：
>
> - 硬件规格与接口表 → [README §2](../README.md#2-硬件规格)
> - USB 复合设备与连接 → [第 5 章 · USB](ch-usb.md)
> - 固件容器与加载 → [第 6 章 · 固件](ch-firmware.md)
> - SoC 细节 → [soc-myriad2.md](soc-myriad2.md)
>
> 下面保留**厂商原生库清单**（没有搬到别处，做方案 A / 逆向时有用）。

## 原生库

| 库 | 作用 |
|---|---|
| `liblcd.so` | LCD 控制与送帧（`lcd_transfer_stream`、亮度/开关/旋转/显示模式） |
| `libuvc.so` / `libUVCCamera.so` | UVC 摄像头采集（serenegiant fork） |
| `libhid.so` | HID 输入（按键/触摸/传感器/版本） |
| `libboot.so` | 引导模式固件下载（`Boot.download()`） |
| `libcommon.so` | libusb 封装（`usb_init/open/close`、`endpoint_bulk`/`endpoint_interrupt`） |
| `libjpeg-turbo1500.so` | JPEG 编解码 |
| `libyuvto.so` | YUV/RGB 转换（libyuv） |
| `libopenssl.so` | 加密（固件 enkey / AES） |
| `libfacedtr-libusb.so` | 2.3.7 新增：人脸检测私有通道（见 [ch-npu.md](ch-npu.md)） |

## 软件标识

| 项 | 值 |
|---|---|
| 包名 | `com.llvision.glxss3.camera` |
| versionName / Code | `2.3.7` / 15 |
| 厂商代号 | glxss3 / G25 |
| SDK 命名空间 | `com.llvision.glass3.sdk` |
| 构建任务（原生库字符串） | `G25_paltform_sdk_DTR`，`glxss_face_dtr/platform_lib/...` |
| 固件 | `G25_MainFW_1.9.1.img`，1,525,136 B（旧版 1.7.4：1,522,416 B） |

> 注意：仓库**不分发**这些 `.so` 与固件；`.gitignore` 排除了 `*.so` / `*.img` / `*.apk`。
