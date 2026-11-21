# 投屏协议 / Cast protocol

> 结论：眼镜是 **USB 显示器**。手机渲染 640×400 RGBA8888 帧 → 加 16 字节头 → USB bulk 发送。
> 眼镜端只解析头、把 payload 当帧缓冲显示。

## 1. 帧格式

`com/llvision/glass3/sdk/lcd/LCDProfileUtils.java` → `createLCDData()`：

```
偏移  长度  字段
0     1     format      = 1            # 1 = 原始 RGBA8888（640*400*4）
1     1     format2     = 0
2     1     format3     = 0
3     1     format4     = 0
4     4     totalLength = 16 + len(payload)   # 小端 uint32
8     8     extended[8] = 0                  # 预留扩展，当前全 0
16    N     payload     = 宽*高*4 字节 RGBA
```

示例（640×400）：

```
00 01 00 00 00 00        <- format=1, format2..4=0
   18 88 ff 0f           <- 0x0fff8818 = 1,024,000 + 16 = 1,024,016  (LE)
   ... 8 字节 0 ...
   <1,024,000 字节 RGBA 像素>
```

## 2. 数据流

```
① 整屏镜像（sync）
   MediaProjection
     → VirtualDisplay("ScreenRecorder-display", 640, 400, 1, surface)
     → ImageReader(640,400, RGBA_8888, maxImages=2)
     → planes[0].getBuffer()            # 原始 RGBA
     → LCD.transferScreen(w,h,bytes)    # 加 16B 头
     → nativeLCDTransferStream()        # JNI
     → liblcd.so → libusb_bulk_transfer → 眼镜

② 浮层投屏（overlay）
   CaptureWidgetRecord：把指定 View 渲染到 VirtualDisplay，其余同上

③ 待机 / logo
   showLogo()：把 assets/logo.bin（1,024,000 B）当一帧发送
```

对应代码：

- `sdk/service/LCDService.java`：`startTransferScreen()` / `startCaptureScreen(0|1)` / `showLogo()`
- `sdk/lcd/CaptureRecord.java`：`MediaProjection → VirtualDisplay → ImageReader`
- `sdk/lcd/CaptureWidgetRecord.java`：浮层捕获
- `sdk/lcd/LCD.java`：`transferScreen()` → `nativeLCDTransferStream()`
- `sdk/lcd/LCDProfileUtils.java`：帧头封装

## 3. 控制命令（LCD 侧）

JNI 方法（`sdk/lcd/LCD.java`，通过 `RegisterNatives` 动态注册）：

| 方法 | 作用 | 备注 |
|---|---|---|
| `nativeLCDInit(vid,pid,fd,bus,addr,usbfs)` | 初始化/claim 接口 | 参数来自 Android USB Host fd |
| `nativeLCDSetLuminance(int)` | 亮度 | 取值被夹到 100–254 |
| `nativeLCDSetSwitch(boolean)` | 屏开/关 | 依据 `LCDInfo.bopen` 状态 |
| `nativeLCDSetRoll(int)` | 画面旋转 | |
| `nativeLCDSetDisplayMode(int)` | 0=logo/待机，1=浮层，2=全屏镜像 | |
| `nativeLCDGetInfo()` → `LCDInfo` | 读屏状态 | 含 `bopen` |
| `nativeLCDTransferStream(byte[])` | 送帧 | 16B 头 + RGBA |
| `nativeWriteCustomFields(byte[≤128], int)` | 写产品信息 | |

控制类操作在原生层通过 **USB control transfer** 实现（`liblcd.so` 引用 `libusb_control_transfer`）。
**参数已真机抓包确认**，详见 [usb-capture.md](usb-capture.md)：

| 功能 | bmRequestType | bRequest | wValue | wIndex | wLen |
|---|---|---|---|---|---|
| 查询状态 | 0xA1 | 0x04 | 0 | 0x0003 | 8 |
| 设置亮度 | 0xA1 | 0x01 | 亮度(100–254) | 0x0003 | 1 |
| 设置显示模式 | 0xA1 | 0x05 | 1=关 / 2=开 | 0x0003 | 1 |
| 自动亮度 | 0xA1 | 0x10 | 0/1 | 0x0002 | 1 |
| LED 指示灯 | 0xA1 | 0x19 | 0/1 | 0x0002 | 1 |
| HID 报告 | 0xA1 | 0x16 | 0 | 0x0002 | 28 |

## 4. 流量估算

- 每帧 = 16 + 1,024,000 = 1,024,016 B ≈ 1 MB
- USB 2.0 High-Speed 理论 ~40 MB/s ⇒ 理论上限约 **35–40 fps**；实际取决于眼镜固件消费速度
- 若需要更高帧率或更低带宽，可考虑：降分辨率、JPEG（`FRAME_FORMAT_MJPEG` 常量存在但 LCD 路径未使用）、
  差分帧（需固件配合，未知）

## 5. 与相机/USB 其它功能的关系

眼镜是复合设备：

- UVC 摄像头 → 手机预览/拍照（不走 LCD 通道）
- HID → 按键、触摸、传感器数据（`SensorService` / `KeyEventService`）
- LCD → 投屏/显示（本文档）

三者共用一条 USB 线、不同接口；自定义发送端需要选对 **vendor-specific 接口**的 bulk OUT 端点。
