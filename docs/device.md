# 设备画像 / Device profile

## 软件

| 项 | 值 |
|---|---|
| 包名 | `com.llvision.glxss3.camera` |
| versionName | `2.3.7` |
| 厂商代号 | glxss3 / G25 |
| SDK 命名空间 | `com.llvision.glass3.sdk` |
| 构建任务（原生库字符串） | `G25_paltform_sdk_DTR`，路径含 `glxss_face_dtr/platform_lib/src/main/jni/...` |
| 主要组件 | `LLVisionPlatformService`、`LCDService`、`UVCService`、`SensorService`、`LightService`、`KeyEventService`、`UsbHostDataTransferService`、`CaptureActivity` |

## USB

| 模式 | VID | PID | 说明 |
|---|---|---|---|
| 正常 | `0x2E09` | `0x0030` | 复合设备：UVC 摄像头 + 厂商自定义 LCD 接口 + HID |
| 固件下载 | `0x03E7` | `0x2150` | boot 模式（`LLVisionPlatformService.BOOT_VID/BOOT_PID`） |

证据：`res/xml/device_filter.xml`、`res/xml/device_filter_llvision.xml`、
`sdk/service/LLVisionPlatformService.java`。

App 通过 Android USB Host API 打开设备，把 **fd + busNum + devAddr + usbfs 路径** 传给原生库
（`sdk/usb/b.java` → `nativeLCDInit(vid,pid,fd,busNum,devAddr,usbfsPath)`），原生层再用 libusb 访问。

## 显示

- 分辨率：**640 × 400**，DPI = 1（`framework/lcd/utils/ConstantUtils.java`）
- 交叉验证：`assets/logo.bin` 大小 1,024,000 B = 640 × 400 × 4（RGBA8888），且该文件被原样当作一帧发送。

## 原生库

| 库 | 作用 |
|---|---|
| `liblcd.so` | LCD 控制与送帧（`lcd_transfer_stream`、亮度/开关/旋转/显示模式） |
| `libuvc.so` / `libUVCCamera.so` | UVC 摄像头采集 |
| `libhid.so` | HID 输入（按键/触摸/传感器） |
| `libboot.so` | boot 模式固件下载（`Boot.download()`） |
| `libcommon.so` | libusb 封装（`usb_init/open/close`，`endpoint_bulk` / `endpoint_interrupt`） |
| `libjpeg-turbo1500.so` | JPEG 编解码（相机侧） |
| `libyuvto.so` | YUV/RGB 转换（libyuv） |
| `libopenssl.so` | 加密（固件/通信相关） |

## 固件镜像

`assets/G25_MainFW_1.9.1.img`（1,525,136 B）：

```
MA2x 容器头
├─ ddrInitHeaderForMvcmd.elf     ← Marvell MVCMD DDR 初始化
└─ main.elf                       ← 主程序
BUILD DATE: Thu Jun  9 06:59:57 EEST 2016
GIT: ma2450 / temp_proposed_golden_driver_ma2450
```

→ 眼镜主控为 **Marvell 平台**（疑似 MA2450 系列），2016 年构建。

配套 `assets/llvision.enkey`（128 B，`FileUtils.SE_KEY_NAME`），用途未确认（可能为固件校验/解密密钥）。

升级流程：App 把 img 复制到本地 → `Boot.download()` → 设备切到 `0x03E7:0x2150` → libboot 通过 bulk 写入。
