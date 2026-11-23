# 方案 B 可行性：CarPlay 转换盒 + Android 接收端 → GLXSS G25（640×400）

> 调研日期：2026-11-22。范围：只做研究与文档，不改 App 代码。
> 结论先行：**技术上可行，但用户给的两个参考项目都不是“CarPlay 转换盒接收端”**——
> `open-headunit` 是 **Android Auto** 接收端；`DiPlay` 是 **不经过盒子的原生 CarPlay** 接收端。
> 真正对应“盒子 + Android 主机”的开源实现是 `lvalen91/Carlink`（Unlicense）及其协议资料。
>
> 如果只想要“能用的导航 HUD”，方案 A 仍然是更稳的选择；方案 B 适合“必须在眼镜里看到完整
> CarPlay UI”的实验，推荐先花半天做 B0 验证，再决定是否投入 2–4 周做 B1。

---

## 0. 结论速览

| 问题 | 结论 |
|---|---|
| 两个项目怎么拿 CarPlay 画面？ | **open-headunit 拿不到 CarPlay**，它收的是 Android Auto（H.264/H.265，USB AOA 或 Wi-Fi TCP+TLS）。**DiPlay 能拿 CarPlay**，但它是原生 AirPlay/iAP2 接收端，不需要盒子；视频是 H.264/H.265 over TCP，解码到 `Surface`。 |
| 用哪款盒子？ | 两个项目都不用 CarPlay 盒子。`open-headunit` 可选 AA 无线盒子（AAWireless 等）；`DiPlay` 明确“no dongle”。CarPlay 盒子路线应选 **Carlinkit CPC200-CCPA**（VID:PID `1314:1520/1521`）。 |
| 能不能接到眼镜？ | 能。接入点在**解码器的输出 Surface**：把 `SurfaceView` 换成 `ImageReader`/`SurfaceTexture`，拿 RGBA 后走现有 `LcdProtocol` + `bulkTransfer(EP 0x01)`。 |
| 640×400 / 帧率 / 延迟 | 协议层可请求 640×400；iOS 是否接受未验证。眼镜链路 1,024,016 B/帧，25 fps ≈ 25.6 MB/s，**上限约 25–30 fps**。端到端估计 **100–200 ms**（不含触摸回程）。 |
| 输入 | CarPlay 支持触摸和旋钮。DiPlay 有完整的触摸/旋钮 HID；`open-headunit` 有触摸转发。**单 Fn 键不够**，需要手机屏幕当触摸板，或 BLE 遥控。 |
| 许可 | `open-headunit` AGPL-3.0；`DiPlay` GPL-3.0（UI 部分 AGPL-3.0）；`Carlink` Unlicense；`node-CarPlay` MIT；CPC200 文档 MIT。 |
| 法律 | 买来的盒子内部有 MFi 认证芯片/身份，iPhone 认它；**自己实现接收端**会碰到 MFi 证书/密钥、Apple NDA、CarPlay 商标和 DMCA 技术措施。DiPlay 的 APK 内置的是从 Carlinkit 固件提取的身份，**代码是 GPL，身份数据不是**。 |
| 工作量 | B0（盒子 + 现成 App + 录屏投眼镜）：**0.5–1 天**；B1（复用 Carlink 做 640×400 接收端）：**2–4 周**；B2（移植 DiPlay 原生栈）：**数月**，且有身份/法律硬伤。 |
| 建议 | 方案 A 继续当主力；B 先做 B0，通过再做 B1。**不建议把 B2 产品化**。 |

---

## 1. 先纠正两个前提

### 1.1 `open-headunit` 不是 CarPlay

README 第一句就写的是 “turn your Android tablet or phone into an **Android Auto** receiver”
（[README L12](https://github.com/andreknieriem/open-headunit/blob/main/README.md#L12)）。
它的代码包叫 `aap`（Android Auto Protocol），没有 AirPlay/iAP2/CarPlay 实现：

- 视频：H.264 / H.265，`MediaCodec` 解码到 `Surface`
  （[VideoDecoder.kt L24-L27](https://github.com/andreknieriem/open-headunit/blob/main/app/src/main/java/com/andrerinas/openheadunit/decoder/video/VideoDecoder.kt#L24-L27)）。
- 分辨率：代码注释明确 AA 投影范围 **800×480 – 3840×2160**
  （[VideoDecoder.kt L121-L127](https://github.com/andreknieriem/open-headunit/blob/main/app/src/main/java/com/andrerinas/openheadunit/decoder/video/VideoDecoder.kt#L121-L127)）。
- 它支持的“USB wireless dongle”是 **Android Auto** 无线盒子（AAWireless、Carlinkit AA 等），
  不是 CarPlay 盒子（[README L44-L46](https://github.com/andreknieriem/open-headunit/blob/main/README.md#L44-L46)；
  [UsbAttachPolicy.kt L13-L23](https://github.com/andreknieriem/open-headunit/blob/main/app/src/main/java/com/andrerinas/openheadunit/connection/usb/UsbAttachPolicy.kt#L13-L23)）。

所以：**想用 open-headunit 接 CarPlay 是走不通的**。它对本文的价值是“Android 上如何把
H.264 解码到 Surface、如何转发触摸”的参考实现。

### 1.2 `DiPlay` 是原生 CarPlay，不用盒子

DiPlay 的 README 写得很直接：

- “Wired and wireless” CarPlay；“No jailbreak, **dongle**, Mac, account or authentication server
  is required”（[README L15](https://github.com/shihabal3amri/DiPlay/blob/main/README.md#L15)）。
- 它不是 Apple 认证产品；APK 里带的是“从公开的 Carlinkit C2Air Allwinner V821 固件中恢复出来的
  配件身份”（[README L25](https://github.com/shihabal3amri/DiPlay/blob/main/README.md#L25)；
  见 [THIRD_PARTY_NOTICES.md “Experimental authentication data”](https://github.com/shihabal3amri/DiPlay/blob/main/docs/THIRD_PARTY_NOTICES.md)）。

也就是说：DiPlay 自己**扮演**了“盒子”的角色——iPhone 直接连它，AirPlay/iAP2/MFi 全在 App 里。
它不需要 CPC200，但也因此需要一份 MFi 身份；这正是后面最大的法律/工程门槛。

### 1.3 那“盒子 + Android 接收端”谁在做？

对应的是 **Carlinkit CPC200-CCPA** 这类“Android 车机用的无线 CarPlay 盒子”：

- 名字先分清：**Autokit 是 Carlinkit 的 Android 主机 App（闭源）**，不是盒子型号；
  **CPC200-CCPA 是盒子硬件型号**；**Carlink 是它的开源 Android 主机替代 App**。
- 盒子对 iPhone：自己建 5 GHz SoftAP，跑 AirPlay/RTSP + MFi 认证
  （[wireless_carplay.md](https://github.com/lvalen91/CPC200-CCPA_resources/blob/main/documentation/02_Protocol_Reference/wireless_carplay.md)）。
- 盒子对 Android 主机：USB 2.0 vendor-specific 接口，16 B 头 `0x55AA55AA`，视频类型 `0x06`，
  H.264 Annex B（[usb_protocol.md](https://github.com/lvalen91/CPC200-CCPA_resources/blob/main/documentation/02_Protocol_Reference/usb_protocol.md)、
  [video_protocol.md](https://github.com/lvalen91/CPC200-CCPA_resources/blob/main/documentation/02_Protocol_Reference/video_protocol.md)）。
- Android 侧已有开源实现：`lvalen91/Carlink`（Unlicense，Kotlin，27.6k 行）
  （[README L12-L13](https://github.com/lvalen91/Carlink/blob/main/README.md#L12-L13)）。

**这是方案 B 最短的技术路径**：盒子负责 CarPlay，Android 只负责“盒子 USB 协议 + 解码 + 显示”。

---

## 2. 硬件清单与成本

### 2.1 必需/可选

| 项 | 规格/型号 | 是否必需 | 参考价（2026-11） | 说明 |
|---|---|---|---|---|
| iPhone | 支持 CarPlay 的 iPhone（建议 iPhone 8 及以上，iOS 12+） | **必需** | 已有则 0；二手 ¥600–1500 | CarPlay 是 iPhone 功能；没有 iPhone 就没有 CarPlay。无线 CarPlay 还需要 5 GHz Wi-Fi。 |
| CarPlay 盒子 | **Carlinkit CPC200-CCPA**（或同协议 CCPA 克隆） | B1 必需 | $51–67（¥370–490） | 必须是“Android 车机用”的 CCPA，不是“给原厂有线 CarPlay 加无线”的 U2W。VID:PID 常见 `1314:1520/1521`。 |
| USB-C 扩展坞 | 2 个以上 USB-A、支持 PD 充电、USB 2.0 兼容 | B1 必需 | ¥80–200 | 手机只有一个 USB-C 口，要同时接盒子和眼镜。优先带 PD 的 hub，避免手机供电不足。 |
| USB-C OTG/数据线 | USB-C 转 USB-A 母/公，短粗线 | B1 必需 | ¥20–60 | 盒子通常 USB-A 公头；眼镜线按现有即可。 |
| PD 电源/充电宝 | 30 W PD，车充或充电宝 | 强烈建议 | ¥100–200 | 眼镜 bulk OUT ≈ 25–30 MB/s，盒子 + 眼镜同时吃电；不接电源手机会掉电/掉设备。 |
| GLXSS G25 + 现有线 | 已有 | 已有 | 0 | 复合设备 `2e09:0030`，640×400，EP 0x01 bulk OUT。 |
| BLE 遥控/小键盘 | AB Shutter3 一类，或带方向键的 BLE 遥控 | 可选 | ¥20–60 | 手机放口袋/支架够不着时，单 Fn 键不够用。 |
| 手机支架 | 可调角度 | 可选 | ¥30–80 | 手机屏幕当触摸板时需要。 |
| CH341A + MFi 协处理器 | 仅 B2 原生路线 | 不推荐 | ¥30 + 灰色渠道 $30–100 | 正规 MFi 芯片只卖给 MFi 会员；灰色渠道有法律/供应风险。 |

### 2.2 成本小结

- **已有 iPhone**：B1 增量 **¥600–950**（盒子 + hub + 线 + 电源）。
- **没有 iPhone**：再加 **¥600–1500**（二手 iPhone），总 **¥1200–2400**。
- **B2（DiPlay 原生）**：硬件几乎为 0，但需要 MFi 身份；合法获取身份需要加入 MFi 计划（公司资质、
  NDA、认证费用），**不是“买个芯片”能解决的**。用提取的身份则法律风险高。

> 价格会波动；盒子建议买**新机、已激活/可激活、固件 2025.10 左右**的版本。二手盒子可能被
> Carlinkit 的激活机制锁住（ludwig-v 的逆向仓库明确提到 activation 问题：
> [README “Carlinkit controls devices activation”](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering#rooting-the-device-via-software)）。

### 2.3 连接拓扑（B1）

```
iPhone ──(Wi-Fi 5GHz + BT 配对)──► CPC200-CCPA ──(USB 2.0 bulk)──► Android 手机（Pixel 10 Pro Fold）
                                                                        │
                                                                        ├─ 解码 H.264 → 640×400 RGBA
                                                                        │
                                                                        └─(USB 2.0 bulk EP 0x01)──► GLXSS G25
```

盒子自己建 Wi-Fi，**不需要 Android 手机开热点**；手机只负责 USB host + 解码 + 眼镜。
这是 B1 比 B2 简单很多的关键：不碰 Android 的 Wi-Fi Direct/Hotspot/NCM。

设备侧：

- **Pixel 10 Pro Fold（Android 16，root）**：首选。USB-C host + hub 是主要验证点；root 对 USB 权限/
  自启有帮助，但不是必需。
- **LG G8（LineageOS 14，root）**：如果这里的 “14” 指 LineageOS 14.1（Android 7.1），
  Carlink 的 `minSdk = 29` 装不上，需要回移代码或改用 DiPlay（minSdk 24）；工作量会明显增加。
  如果它实际是 Android 14 的 ROM，则和 Pixel 一样可用。**先以 Pixel 为准做验证**。
  （Carlink `minSdk` 证据：[app/build.gradle.kts](https://github.com/lvalen91/Carlink/blob/main/app/build.gradle.kts)）

---

## 3. 问题 1：两个项目怎么拿到 CarPlay 画面？

### 3.1 `open-headunit`：Android Auto，不是 CarPlay

| 维度 | 实际情况 | 证据 |
|---|---|---|
| 协议 | Android Auto Protocol（AAP） | README L12；代码包 `aap/` |
| 传输 | USB AOA（手机作为 accessory）或 Wi-Fi TCP+TLS（Headunit Server 5277 / 本机 5288） | README“Wired USB / Wireless”；`AutomationCommandPolicy.kt` L57（5277）、`WirelessServer.kt` L23（5288） |
| 视频编码 | H.264 Baseline（默认）/ H.265（可选） | `VideoCodecNegotiationPolicy.kt` L19-L34 |
| 分辨率 | 800×480 – 3840×2160（AA 枚举） | `VideoDecoder.kt` L121-L127 |
| 帧率 | 30/60 fps（AA 配置） | `VideoCodecNegotiationPolicy.kt`；AA 本身协商 |
| 关键类 | `VideoDecoder`（MediaCodec→Surface）、`AapProjectionActivity`（Surface/SurfaceView + 触摸）、`TouchCoordinateMapper` | 见下 |
| 盒子 | 只支持 AA 无线盒子；**不支持 CarPlay 盒子** | README L44-L46；`UsbAttachPolicy.kt` L13-L23 |

关键代码：

- 解码入口：`VideoDecoder` 用 `MediaCodec` 配 `COLOR_FormatSurface`，`configure(format, mSurface, ...)` 到
  `Surface`（[VideoDecoder.kt L1683](https://github.com/andreknieriem/open-headunit/blob/main/app/src/main/java/com/andrerinas/openheadunit/decoder/video/VideoDecoder.kt#L1683)、
  [L1782](https://github.com/andreknieriem/open-headunit/blob/main/app/src/main/java/com/andrerinas/openheadunit/decoder/video/VideoDecoder.kt#L1782)）。
- `setSurface()` 可以换输出面（[L747-L760](https://github.com/andreknieriem/open-headunit/blob/main/app/src/main/java/com/andrerinas/openheadunit/decoder/video/VideoDecoder.kt#L747-L760)）。
- 触摸：`AapProjectionActivity.dispatchTouchEvent()`（L2297）→ `sendTouchEvent()`（L2792），
  坐标由 `TouchCoordinateMapper.map()` 映射到协商分辨率（[TouchCoordinateMapper.kt L11-L73](https://github.com/andreknieriem/open-headunit/blob/main/app/src/main/java/com/andrerinas/openheadunit/input/TouchCoordinateMapper.kt#L11-L73)）。
- 触摸手势策略：`RemoteTouchPolicy`（`input/RemoteTouchPolicy.kt`），可复用的“手机屏幕当触摸板”逻辑。

**结论**：open-headunit 不能拿 CarPlay；它的视频/触摸管线可以作为“Android 接收端如何接眼镜”的参考。

### 3.2 `DiPlay`：原生 CarPlay（AirPlay/iAP2），不用盒子

| 维度 | 实际情况 | 证据 |
|---|---|---|
| 协议 | CarPlay 的 AirPlay/RTSP + iAP2 + MFi | `AirPlaySession` 文档注释：RTSP framing、pairing/auth、HID、SETUP/TEARDOWN（[AirPlaySession.kt L66-L76](https://github.com/shihabal3amri/DiPlay/blob/main/shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt#L66-L76)） |
| 无线 | iPhone ↔ 手机同一 Wi-Fi / 热点 / Wi-Fi Direct；AirPlay TCP 屏幕流 | README L15；`docs/CONNECTION_SETUP.md` |
| 有线 | USB NCM + iAP2（手机作为 USB host，用户态跑 NCM 以太网） | `transport/NcmUsbBridge.kt` L9-L16；`Iap2UsbMuxHost.kt` |
| 视频编码 | **H.264 / H.265**（`avcC`/`hvcC`） | `ScreenStream.kt` L13、L203-L212 |
| 封装 | 128 B AirPlay screen header + ChaCha20-Poly1305 密文；NAL 长度前缀转 Annex B | `ScreenStream.kt` L17-L20、L100-L123、L167-L201 |
| 分辨率 | 由主机窗口尺寸决定（`DisplaySize` → AirPlay config），不是固定档位 | `CarPlayHostActivity.kt` L5881-L5896、L7106-L7110 |
| 帧率 | 30–60 fps，默认 60（可设） | `AirPlayDisplaySettings.kt` L15-L18 |
| 解码 | MediaCodec → `Surface` | `AndroidMediaSink.kt` L176-L182、L1207-L1208、L1358 |
| 关键类 | `AirPlaySession`、`CarPlayMediaEngine`、`ScreenStream`、`AndroidMediaSink.VideoDecoder` | 见下 |
| 盒子 | **不用**；它自己就是接收端 | README L15 |

关键代码：

- `CarPlayMediaEngine` 定义 `MediaSink` 接口（`onVideoCodec/onVideoConfig/onVideoFrame`），
  并把 `ScreenStream` 的帧转给 sink（[CarPlayMediaEngine.kt L22-L31、L139](https://github.com/shihabal3amri/DiPlay/blob/main/shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt#L22-L31)）。
- `ScreenStream` 收 TCP 流，按 128 B 头解析，解密后把长度前缀 NAL 转 Annex B
  （[ScreenStream.kt L100-L123、L176-L201](https://github.com/shihabal3amri/DiPlay/blob/main/shared/src/main/java/com/shilapi/xcertplay/airplay/ScreenStream.kt#L100-L123)）。
- `AndroidMediaSink.VideoDecoder` 是串行 MediaCodec 解码器：mime 选 `video/avc` 或 `video/hevc`，
  `codec.configure(format, surface, null, 0)` 直接渲染到传入的 `Surface`
  （[AndroidMediaSink.kt L1207-L1208、L1358、L1433](https://github.com/shihabal3amri/DiPlay/blob/main/shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt#L1207-L1208)）。
- 显示尺寸：`scheduleDisplaySize(width,height)` 把当前 View 尺寸发给 iPhone
  （[CarPlayHostActivity.kt L5881-L5896](https://github.com/shihabal3amri/DiPlay/blob/main/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L5881-L5896)）；
  测试里 1920×990 是合法画布（[CarPlayHostResolutionTest.kt L99-L104](https://github.com/shihabal3amri/DiPlay/blob/main/common/src/test/java/com/shilapi/xcertplay/CarPlayHostResolutionTest.kt#L99-L104)）。
- 触摸：`CarPlayTouchMapper` 把 `MotionEvent` 归一化成 0–1 接触点
  （[CarPlayTouchMapper.kt L14-L32](https://github.com/shihabal3amri/DiPlay/blob/main/shared/src/main/java/com/shilapi/xcertplay/media/CarPlayTouchMapper.kt#L14-L32)），
  再由 `AirPlayHid.touchReport()` 打包（[AirPlayHid.kt L46-L56](https://github.com/shihabal3amri/DiPlay/blob/main/shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayHid.kt#L46-L56)）。
- 旋钮：`AirPlayHid.knobReport()`（L59-L70）和 `CarPlayController.sendKnob()`（L665-L681）实现了
  CarPlay 的 rotary/knob HID。

**结论**：DiPlay 能拿 CarPlay，但它是“把盒子的事自己做了”。要复用它，等于移植一整套
AirPlay/iAP2/MFi/NCM，不是“接个盒子”。

### 3.3 盒子路线的事实标准：CPC200-CCPA + Carlink

`lvalen91/Carlink` 是 Android 上的 CPC200-CCPA 主机实现，和本文目标最接近：

- 支持 CarPlay（iPhone）和 Android Auto，`README L12-L13` 要求 **Android 10+ / AAOS** 和
  **CPC200-CCPA 固件 2025.10**。
- USB 过滤器：`1314:1520`、`1314:1521`，以及备用 `2276:448`
  （[usb_device_filter.xml](https://github.com/lvalen91/Carlink/blob/main/app/src/main/res/xml/usb_device_filter.xml)）。
- `H264Renderer` 明确写“Decodes video from the CPC200-CCPA adapter and renders to a Surface”
  （[H264Renderer.java L22-L34](https://github.com/lvalen91/Carlink/blob/main/app/src/main/java/com/carlink/video/H264Renderer.java#L22-L34)）。
- 协议层 `AdapterDriver` 负责 2 s 心跳、初始化、读写线程、消息分发
  （[AdapterDriver.kt L1-L30](https://github.com/lvalen91/Carlink/blob/main/app/src/main/kotlin/com/carlink/protocol/AdapterDriver.kt#L1-L30)）。
- `serializeOpen()` 的 28 B payload 就是 `width/height/fps/format/packetMax/iBoxVersion/phoneWorkMode`
  （[MessageSerializer.kt L352-L367](https://github.com/lvalen91/Carlink/blob/main/app/src/main/kotlin/com/carlink/protocol/MessageSerializer.kt#L352-L367)）。
- 触摸：CarPlay 用 `0x17` 多触点 float 0–1（[MessageSerializer.kt L112-L150](https://github.com/lvalen91/Carlink/blob/main/app/src/main/kotlin/com/carlink/protocol/MessageSerializer.kt#L112-L150)）。

盒子协议的关键事实（来自 25+ 次抓包/固件分析）：

- USB：interface 0，vendor-specific；bulk IN `0x83` / bulk OUT `0x02`（不同批次可能不同，枚举时取）。
- 消息头 16 B：magic `0x55AA55AA`、length、type、`type ^ 0xFFFFFFFF`。
- 视频：type `0x06`，总头 36 B（16 B USB + 20 B 视频），payload 是 **H.264 Annex B**，
  无 B 帧；分辨率/帧率由主机 `Open(0x01)` 的 width/height/fps 决定。
- 盒子对 iPhone：自己建 5 GHz SoftAP（192.168.43.1），BT 做 iAP2/MFi，然后 AirPlay/RTSP；
  视频在盒子内部被重新封装成 USB `0x06`，**不转码**。

证据：[usb_protocol.md](https://github.com/lvalen91/CPC200-CCPA_resources/blob/main/documentation/02_Protocol_Reference/usb_protocol.md)、
[video_protocol.md](https://github.com/lvalen91/CPC200-CCPA_resources/blob/main/documentation/02_Protocol_Reference/video_protocol.md)、
[wireless_carplay.md](https://github.com/lvalen91/CPC200-CCPA_resources/blob/main/documentation/02_Protocol_Reference/wireless_carplay.md)、
[node-CarPlay DongleDriver.ts](https://github.com/rhysmorgan134/node-CarPlay/blob/main/src/modules/DongleDriver.ts)。

### 3.4 三者对比

| 维度 | open-headunit | DiPlay | Carlink（盒子路线） |
|---|---|---|---|
| 拿的是 | Android Auto | 原生 CarPlay | CarPlay（经 CPC200 盒子） |
| 需要 iPhone | 否（需要 Android 手机） | 是 | 是 |
| 需要 CarPlay 盒子 | 否（可选用 AA 盒子） | 否 | **是**（CPC200-CCPA） |
| 需要 MFi 身份 | 否 | **是**（本地身份/CH341） | 否（盒子自带） |
| 视频格式 | H.264/H.265 | H.264/H.265 | H.264 |
| 传输 | USB AOA / Wi-Fi TCP+TLS | Wi-Fi AirPlay / USB NCM | USB bulk 私有头 |
| 输出 | Surface | Surface | Surface |
| 许可 | AGPL-3.0 | GPL-3.0（UI AGPL） | Unlicense |
| 对本文 | 参考“解码到 Surface + 触摸” | 参考“CarPlay 输入/显示尺寸”；**不建议移植** | **推荐复用** |

---

## 4. 问题 2：能不能把画面接到眼镜显示管线？

### 4.1 可以，接入点在“解码后的 Surface”

三条路线都最终把 H.264/H.265 解码到一个 `Surface`：

- DiPlay：`AndroidMediaSink` 构造时可传 `surface`，运行中 `setSurface()`；
  `CarPlayHostActivity` 把视频 View 的 Surface 交给它（L6605）。
- open-headunit：`VideoDecoder.setSurface()`。
- Carlink：`H264Renderer(width, height, Surface, ...)` 构造器（L210），`MediaCodec.configure(format, surface, ...)`（L571）。

我们要做的不是“改解码器”，而是**换一个 Surface 消费者**：

```
MediaCodec ──► Surface ──► ImageReader / SurfaceTexture ──► RGBA ──► 640×400 映射
                                                                   └─► LcdProtocol.frame()
                                                                        └─► bulkTransfer(EP 0x01, 1,024,016 B)
```

现有眼镜管线（本仓库）：

- 帧格式：16 B 头 + 640×400 RGBA8888，单帧 1,024,016 B
  （[ch-display.md L46-L93](ch-display.md#2-帧格式16-b-头--rgba)）。
- 发送：`UsbLink.sendFrame()` 单次 `bulkTransfer`，短写按 16 KB 分块重试
  （`app/app/src/main/java/com/wzqvip/glxsscast/usb/UsbLink.kt` L260-L293）。
- 映射：`mapFit` / `mapCover` / `mapStretch`
  （`app/app/src/main/java/com/wzqvip/glxsscast/cast/CastService.kt` L754/L770/L793）。
- Pi 侧：`linux/glxss/pipeline.py` 的 `LcdRenderer` 已有 YV12→640×400 的 cover/fit 逻辑，
  可加一个 RGBA 入口复用。

### 4.2 三种取帧方式

| 方式 | 延迟（估算） | CPU/GPU | 代码量 | 适用 |
|---|---|---|---|---|
| **ImageReader（YUV_420_888）**：解码到 `ImageReader.surface`，`acquireLatestImage()` 拿 YUV，CPU 转 RGBA | 低（~3–8 ms） | CPU 转色 | 中 | **推荐**；最稳，和现有 Pi 管线同构 |
| **SurfaceTexture + GLES**：解码到 `SurfaceTexture`，外部纹理渲染到 FBO，`glReadPixels` 拿 RGBA | 低（~2–5 ms） | GPU 回读 | 中高 | 想要 RGBA 直出、可接受 GL 代码 |
| **SurfaceView + PixelCopy**：保留现有显示，定时 `PixelCopy` 截一帧 | 高（+1–2 帧） | 拷贝 | 低 | **B0 验证/原型**；30 fps 可能抖动 |

说明：

- MediaCodec 的 `COLOR_FormatSurface` 输出通常是 YUV；`ImageReader` 用 `YUV_420_888` 最稳，
  不要假设 `RGBA_8888` 一定能被硬件解码器直接写。
- 如果非要用 `RGBA_8888`，先做一次 10 行小测试：`MediaCodec` → `ImageReader(RGBA_8888)`，
  失败就退回 YUV。
- Carlink 的 `SurfaceSnapshot` 已经示范了 `PixelCopy`（L45-L60），但它是给设置页背景用的，
  不是 30 fps 管线；原型可以借用，正式版建议 ImageReader/GL。
- `ImageReader` 的尺寸必须等于**解码流尺寸**：请求 640×400 就建 640×400；若实际流是
  1280×720，就建 1280×720 再缩放，不要建死 640×400 却送 720p（会直接失败或花屏）。
- 解码器只认一个输出 Surface。若同时想在手机屏幕上看，别用两个 Surface 抢；要么手机屏只做
  触摸板，要么用 `ImageReader` 拿帧后自己画到两个目标。

### 4.3 640×400 与 CarPlay 分辨率协商

**盒子路线**：`Open(0x01)` 直接带 width/height/fps；盒子把它写进 `HU_VIEWAREA_INFO`，
iPhone 按这个尺寸渲染（[video_protocol.md “How the Head-Unit Resolution Reaches the iPhone”](https://github.com/lvalen91/CPC200-CCPA_resources/blob/main/documentation/02_Protocol_Reference/video_protocol.md)）。
协议层没有 800×480 的硬下限，抓包里出现过 1200×480、1280×720、2400×960 等。
**但 iOS 是否接受 640×400、CarPlay UI 在 640×400 下是否可用，没有现成证据**，必须实测。

**DiPlay 路线**：它把当前 View 尺寸直接作为画布（`scheduleDisplaySize`），
所以把 View 做成 640×400 就会请求 640×400；同样受 iOS 侧限制。

建议顺序：

1. 先请求 **640×400@30**，看 iPhone 是否出流、SPS 里的 width/height 是否 640×400。
2. 若被拒绝/黑边/UI 不可读，退 **800×480@30**（CarPlay 常见最小档），再 `mapCover` 到 640×400。
3. 再退 **1280×720@30**，下采样到 640×400；文字更细，但缩放成本略高。
4. 不要请求 60 fps：眼镜链路扛不住，且手机解码/发热更大。

CarPlay 的控件大小是按“物理宽度”缩放的（DiPlay 的 `CarPlaySize` 用 250/300/350 mm，
`AirPlayDisplaySettings` 允许 100–400 mm）。如果 640×400 下字太小，优先**调大物理尺寸**
（让 iOS 把控件画大），而不是一味提高分辨率。

### 4.4 帧率与 USB 带宽

眼镜链路是硬瓶颈：

```
payload = 640×400×4 = 1,024,000 B
frame   = 16 + payload = 1,024,016 B
25 fps  ≈ 25.6 MB/s
单帧传输 ≈ 30 ms（≈34–35 MB/s，USB 2.0 HS 上限附近）
```

（[ch-display.md L86-L93](ch-display.md#23-尺寸速算)）

结论：

- **目标 25 fps，上限 30 fps**；CarPlay 60 fps 输入必须“只保留最新帧”，不能排队。
- 现有 App 实测 25 fps（README L55），说明链路可行；30 fps 需要测试是否稳定。
- 盒子 + 眼镜挂在同一个 USB 2.0 hub 上时，两者共享 480 Mbps 总线；眼镜占 25–30 MB/s，
  盒子视频只有 ~1–2 Mbps，余量足够；但要确认 hub 不是只走 USB 1.1 或劣质芯片。

### 4.5 延迟预算（估算，不是实测）

| 阶段 | 估计 | 依据 |
|---|---|---|
| iPhone 编码 + AirPlay Wi-Fi | 40–80 ms | 盒子日志 “Video Latency 75 ms”（video_protocol.md） |
| 盒子 USB → Android 读 | 5–15 ms | USB bulk + 36 B 头重组 |
| H.264 解码（720p，硬解） | 8–25 ms | 手机硬解一帧的量级 |
| YUV→RGBA + 缩放 | 3–8 ms | 640×400 小图 |
| 眼镜 bulk OUT | 25–35 ms | 1,024,016 B @ ~35 MB/s |
| 面板扫描/刷新 | ~16 ms | 60 Hz |
| **合计** | **~100–180 ms**（p95 可能 200–250 ms） | 不含触摸回程 |

触摸回程（触摸 → 盒子 → iPhone → 渲染 → 眼镜）再加 **80–150 ms**，总交互延迟可能 **200–350 ms**。
对导航/选歌可接受；对打字/精细操作明显滞后。

---

## 5. 问题 3：输入怎么处理？

### 5.1 CarPlay 的输入模型

CarPlay 不是“只发视频”就行，iPhone 需要输入设备：

- **Touchscreen HID**：多点触控，归一化坐标；适合手机屏幕/触摸板。
- **Knob/rotary HID**：select/home/back + 相对 x/y + wheel；适合车机旋钮。
- **Media/telephony HID**：播放/暂停/下一首/接挂电话。

DiPlay 同时实现了 touch 和 knob：

- touch：`AirPlayHid.touchReport()`（2 指，16 B 报告；L46-L56）
- knob：`AirPlayHid.knobReport()`（4 B；L59-L70）
- 发送：`CarPlayController.sendTouch()` / `sendKnob()`（L665-L681）

`open-headunit` 则把 Android View 的触摸映射成 AA 的 `TouchEvent` 发给手机
（`AapProjectionActivity.sendTouchEvent()` L2792；`RemoteTouchPolicy` 处理手势/长按）。

Carlink 用盒子协议 `0x17`：每个触点 16 B `float x, float y, int action, int pointerId`
（[MessageSerializer.kt L112-L150](https://github.com/lvalen91/Carlink/blob/main/app/src/main/kotlin/com/carlink/protocol/MessageSerializer.kt#L112-L150)）。

### 5.2 “手机屏幕当触摸板/虚拟按键”有没有现成实现？

**有管线，没有现成 UI。**

- DiPlay/Carlink 本来就是“视频 View 接收触摸”，所以**手机屏幕显示 CarPlay 画面时，它天然就是
  一块触摸板**：触摸坐标 → 归一化 → HID。你不需要另写“触摸板控件”，只需要让这个 View 可见/
  可触摸。
- 如果手机要放口袋、只戴眼镜，则需要一个**不显示视频的触摸板覆盖层**：一个全屏透明 View，
  把 `MotionEvent` 交给 DiPlay 的 `CarPlayTouchMapper` / Carlink 的 `serializeMultiTouch`。
  这部分代码量很小（~100–200 行），但没有现成 App 可直接抄 UI。
- `open-headunit` 的 `RemoteTouchPolicy` 已经把“单指/双指手势、长按、拖动”抽象好了，
  但它输出的是 AA TouchEvent，不是 CarPlay；思路可借鉴，代码不能直接用于 CarPlay。

### 5.3 单 Fn 键能做什么、不能做什么

眼镜只有 1 个 Fn 键（现有固件通过 HID 上报，App 已用于短按/长按切显示/变焦，见 README）。
在 CarPlay 里：

- **能做**：Home / Select（knob 的 `home`/`select`）、显示/隐藏切换、Siri（长按）。
- **不能做**：方向导航、滚动列表、地图拖动、文本输入。
- CarPlay 的 knob 模式需要 x/y/wheel 才能移动焦点；单键无法完成“选 App/选目的地”。

所以：

1. **首选**：手机屏幕当触摸板（手机放在支架/腿上，屏幕可触）。
2. **次选**：BLE 小遥控（带方向键/确认/返回），映射到 CarPlay knob HID。
3. **Fn**：只做 Home/Select/显示切换，不做唯一输入。
4. 如果手机必须放口袋：加一个“眼镜端 3 轴/头动”方案不现实（眼镜 HID 只有 28 B，语义未全解），
   还是用 BLE 遥控。

---

## 6. 问题 4：许可与法律风险

### 6.1 代码许可

| 项目 | 许可 | 你能做什么 | 分发时的义务 |
|---|---|---|---|
| `open-headunit` | **AGPL-3.0**（[LICENSE L1](https://github.com/andreknieriem/open-headunit/blob/main/LICENSE#L1)） | 修改、商用 | 衍生作品需 AGPL；**网络交互用户也要能拿到源码**（AGPL §13） |
| `DiPlay` | **GPL-3.0**（代码）；`DiPlayActivity.kt` 为 **AGPL-3.0-only** | 修改、商用 | 衍生作品需 GPL-3.0；UI 文件 AGPL；分发 APK 需提供对应源码 |
| `Carlink` | **Unlicense**（公共领域，[LICENSE.txt L1](https://github.com/lvalen91/Carlink/blob/main/LICENSE.txt#L1)） | 几乎无限制 | 无 copyleft 义务；但仓库里的第三方二进制不在此列 |
| `node-CarPlay` | **MIT** | 修改、商用 | 保留版权/许可声明 |
| `CPC200-CCPA_resources` | **MIT** | 参考/复用文档 | 保留声明 |
| `ludwig-v/...dongle-reverse-engineering` | 仓库未给统一代码许可（以文件为准） | 参考 | 不要直接搬固件/二进制 |

注意：

- GPL/AGPL 是**代码**许可，不覆盖 MFi 证书/密钥、Apple 固件、CarPlay 商标。
- `Carlink` 的 `assets/ARMiPhoneIAP2.patched` 是给盒子用的**补丁二进制**，不是 Unlicense 能授权的
  东西；本文的“只取视频”原型不需要它，**不要分发**。
- 如果你把 DiPlay 的代码并进自己的 App，保守假设你的 App 要按 GPL-3.0 开源；闭源产品不要走这条。

### 6.2 MFi / CarPlay 认证

- Apple 的 **MFi Program** 是配件厂商计划：签约、NDA、使用认证芯片/流程、送测。
  CarPlay 是 Apple 的商标和规范，未认证产品不能自称 CarPlay，也可能被 iOS 拒绝/限制。
  （参考：[Apple MFi Program](https://mfi.apple.com/)、[CarPlay](https://www.apple.com/ios/carplay/)）
- **买来的 CPC200-CCPA 内部有 MFi 认证芯片/身份**：固件分析显示它通过 `/dev/i2c-1` 驱动
  MFi coprocessor 完成 `0xAA00/0xAA02` 认证，iPhone 认它
  （[wireless_carplay.md](https://github.com/lvalen91/CPC200-CCPA_resources/blob/main/documentation/02_Protocol_Reference/wireless_carplay.md)）。
  你的 Android App 只是它的 USB 主机，**不需要你拥有 MFi 身份**。这是 B1 相对 B2 的最大法律优势。
- 但这不等于“盒子厂商允许你把它接到任意 Android 主机”；可能超出预期用途/EULA。个人实验风险低，
  产品化/分发要重新评估。

### 6.3 DiPlay 身份数据的法律问题

DiPlay 的 APK 内置了“从公开 Carlinkit C2Air 固件恢复的配件证书/私钥”
（README L25；THIRD_PARTY_NOTICES “Experimental authentication data”）。这意味着：

- **代码是 GPL，身份数据不是 GPL**；THIRD_PARTY_NOTICES 明确说这些数据“not relicensed as
  project source code”。
- 使用他人的 MFi 私钥/证书，可能涉及 Apple 的合同/许可、商业秘密、DMCA 反规避、商标/不正当竞争。
- 即使个人实验能用，**不能作为产品分发**；Apple 也可能在 iOS 更新后封禁。
- DiPlay 自己都说“acceptance after future iOS updates ... unresolved”。

结论：**B2 不适合产品化**；如果只是个人研究，接受“随时可能失效 + 法律风险自担”。

### 6.4 逆向盒子 USB 协议

- 为互操作性逆向公开协议，在不少法域有抗辩空间；但 **DMCA §1201 的技术措施**是另一层
  （[17 U.S.C. §1201](https://www.law.cornell.edu/uscode/text/17/1201)）。
- CPC200 的视频/音频类型默认是**明文** H.264（`0x55AA55AA`），`CMD_ENABLE_CRYPT (0xF0)` 是可选的；
  本文路线不需要开启加密/绕过加密，法律风险相对低。
- 不要分发盒子固件、不要绕过激活、不要修改 MFi 芯片；这些风险高。

### 6.5 商标/宣传

- 不要在 product/页面/图标上使用 “CarPlay”“Apple”“iPhone” 作为产品名或暗示 Apple 认证；
  可以写“兼容 CarPlay 的接收端（实验）”，并注明非 Apple 认证。
- DiPlay 的 CarPlay 图标来自 Apple 开发者站点，不属于 GPL；不要直接复用。

---

## 7. 问题 5：结论

### 7.1 工作量（要改哪些层）

#### B0：盒子 + 现成 App + 现有投屏（验证用，0.5–1 天）

1. Pixel 装 **AutoKit**（Carlinkit 官方 App，闭源）或 Carlink APK。
2. iPhone 配对盒子，确认 CarPlay 出现在手机屏幕。
3. 用现有 `glxsscast` 的 MediaProjection 投屏，把手机屏幕（裁掉状态栏）投到眼镜。
4. 用手机触摸屏操作 CarPlay。

这能一次性验证：盒子/iPhone/供电/USB hub/眼镜 640×400/输入是否“能用”。
**如果这一步的延迟/文字可读性就不能接受，后面的开发没有意义。**

#### B1：复用 Carlink，做真正的 640×400 接收端（2–4 周）

| 层 | 工作 | 估计 |
|---|---|---|
| USB host/权限 | 双设备（盒子 + 眼镜）经 hub 枚举；`UsbManager` 权限；断线重连 | 2–3 天 |
| 盒子协议 | 复用 Carlink 的 `AdapterDriver`/`MessageSerializer`；Open 640×400@30；心跳；0x06 解析 | 3–5 天（复用为主） |
| 解码 | `H264Renderer` → `ImageReader`/`SurfaceTexture`；关键帧丢失恢复（cmd 12） | 3–5 天 |
| 显示 | YUV/GL → RGBA；`mapCover/fit`；`LcdProtocol` + `UsbLink.sendFrame`；丢旧帧 | 3–5 天 |
| 输入 | 手机触摸板 View → 0x17；Fn → knob home/select；BLE 遥控可选 | 2–3 天 |
| 音频 | 盒子 PCM/USB Audio → AudioTrack/扬声器/BT；避免被眼镜 UAC 抢走 | 2–3 天（可后置） |
| 生命周期 | 前台服务、开机自启、锁屏/息屏、热管理 | 2–3 天 |
| 测试 | 30 分钟 soak、USB 复位、分辨率回退、延迟测量 | 3–5 天 |
| **合计** | 1 人，熟悉 Android USB/MediaCodec | **2–4 周** |

代码量估计：新增/改 **1,500–3,000 行**（大部分协议/解码可复用 Carlink）。
**不需要移植整个 dongle 协议栈**：CarPlay/AirPlay/MFi 都在盒子里，主机只实现 USB 协议
（约 10 个消息类型：Open/Heartbeat/Video/Touch/Command/Audio/BoxSettings/Plugged/Phase/Unplugged）。

#### B1-HUD：只取 CarPlay 导航元数据（1–2 周，折中）

盒子还会发 `0x2A` 的 NaviJSON（`NaviManeuverType`、`NaviRemainDistance`、`NaviRoadName` 等，
见 usb_protocol.md 的 MediaData 子类型 200）。如果目标只是“导航箭头”，可以：

- 不解码视频，只解析 NaviJSON；
- 复用方案 A 的 `linux/glxss/hud.py` / `nav.py` 画箭头；
- 省掉 640×400 视频链路、带宽和大部分延迟。

代价：没有完整 CarPlay UI，只有导航；仍然需要 iPhone + 盒子。

#### B2：移植 DiPlay 原生 CarPlay（数月，不推荐产品化）

| 层 | 工作 | 估计 |
|---|---|---|
| AirPlay/RTSP、pairing（SRP/Curve25519）、ChaCha | 复用/理解 DiPlay `airplay/` | 数周 |
| MFi 认证 | 本地身份或 CH341 + MFi 芯片；合法身份是硬门槛 | 数周–不可行 |
| iAP2 / USB NCM（有线） | `transport/` 的 NCM/IAP2 栈 | 数周 |
| 视频/音频 | `ScreenStream` + `AndroidMediaSink` | 数周 |
| 输入/显示 | touch/knob + 640×400 | 1–2 周 |
| 测试/iOS 兼容 | 每次 iOS 更新都可能坏 | 持续 |
| **合计** | 熟悉音视频协议的 1 人 | **2–4 个月起** |

DiPlay 的 `shared/src/main` 约 **428 个文件 / 80,280 行**（本次快照统计），完整 App 约 111k 行；
这不是“接一个库”，而是“接管一个接收端”。而且身份/许可问题无法用工程解决。

### 7.2 与方案 A 对比

方案 A（Maps 通知 → 自绘 HUD）在本仓库已经落地：
手机每秒一包 UDP JSON（默认 9999），Pi/手机画大号箭头、距离、路名
（`linux/glxss/nav.py` L2-L4；`linux/glxss/hud.py` L8）。

| 维度 | 方案 A：通知 → 自绘 HUD | 方案 B1：盒子 + 640×400 | 方案 B2：DiPlay 原生 |
|---|---|---|---|
| 额外硬件 | 无 | 盒子 + hub + 电源（¥600–950） | 无盒子，但需 MFi 身份 |
| 需要 iPhone | **否** | 是 | 是 |
| 画面 | 自绘箭头/文字 | 完整 CarPlay UI | 完整 CarPlay UI |
| 延迟（估算） | ~50–100 ms | ~100–200 ms | ~80–150 ms |
| 帧率 | 按 HUD 刷新 | 25–30 fps | 30–60 fps |
| 输入 | Fn + 手机 | 手机触摸板 / BLE / Fn | 同左 |
| 法律 | 干净 | 盒子是 MFi 配件；逆向/EULA 需评估 | **高风险**（身份数据） |
| 工作量 | 已完成 | 2–4 周 | 数月 / 不可产品化 |
| 功耗/发热 | 低 | 高（Wi-Fi + 解码 + USB） | 高 |
| 适合 | 日常导航 | 想要完整 CarPlay | 个人研究 |

**建议**：

1. **方案 A 继续当主力**，不要为了 B 放弃 A。
2. 如果用户有 iPhone 且确实需要完整 CarPlay：先做 **B0**（半天）；通过后做 **B1**（2–4 周）。
3. 如果只想要更好的导航 HUD：做 **B1-HUD**（只取 NaviJSON），比全屏视频划算。
4. **不要产品化 B2**；DiPlay 的身份数据是结构性风险。
5. 如果没有 iPhone：B 直接放弃，继续优化 A。

### 7.3 风险清单

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 640×400 被 iOS 拒绝/UI 不可读 | 中高 | 高 | 先实测；回退 800×480/1280×720 + 下采样；调物理尺寸/DPI |
| 盒子固件/激活不兼容 | 中 | 高 | 买新机、确认固件 2025.10+、保留原厂固件备份；先跑 AutoKit |
| 双 USB 设备供电/枚举不稳 | 中 | 高 | 带 PD 的 hub；外接电源；测 30 分钟 soak |
| 眼镜链路 30 fps 不稳 | 中 | 中 | 锁 25 fps；只发最新帧；短写重试 |
| 触摸延迟大 | 高 | 中 | 手机触摸板优先；BLE 遥控；不要用单 Fn 做导航 |
| 音频被眼镜 UAC 抢走 | 中 | 中 | 明确 AudioTrack/USB 路由；必要时禁用眼镜音频 |
| iOS 更新导致盒子失效 | 低（盒子厂商跟进） | 中 | 买有固件更新的品牌；保留旧 iOS 设备 |
| GPL/AGPL 污染 | 中 | 中 | B1 用 Unlicense 的 Carlink；不要抄 DiPlay/open-headunit 代码 |
| MFi/身份法律风险 | B1 低 / B2 高 | 高 | B1 用认证盒子；B2 不产品化 |

---

## 8. 如果做，第一步验证什么？

按顺序做，任何一步失败就先停，不要投入后面：

### Step 0：确认前提（0.5 天）

- [ ] 有可用 iPhone？型号/ iOS 版本？支持无线 CarPlay？
- [ ] 能否接受“手机当触摸板/必须放在看得到的地方”？
- [ ] 是否只是要导航？如果是，直接做 B1-HUD 或继续 A。

### Step 1：盒子 + 官方 App（0.5 天）

- [ ] 买 CPC200-CCPA（确认 `1314:1520/1521`，固件 2025.10+，已激活）。
- [ ] Pixel 装 AutoKit，USB 接盒子，iPhone 配对，确认 CarPlay 出现在手机屏幕。
- [ ] 记录：从插上到出画面多少秒？触摸是否跟手？发热/掉电？

**通过标准**：能稳定显示/操作 CarPlay 30 分钟。

### Step 2：双设备 + 眼镜（0.5 天）

- [ ] 用带 PD 的 USB-C hub 同时接盒子和眼镜。
- [ ] `adb shell dumpsys usb` 确认两个设备都在；眼镜仍是 `2e09:0030`。
- [ ] 现有 App 投屏到眼镜，同时盒子保持 CarPlay；观察 25 fps 是否掉、USB 是否复位。
- [ ] 用手机屏幕当触摸板，操作 CarPlay；主观评估延迟/文字。

**通过标准**：眼镜 640×400 画面稳定 ≥20 fps；触摸可用；30 分钟无掉设备。

### Step 3：拿到解码帧（1–3 天）

- [ ] 用 Carlink 或 200 行 USB 探针：claim interface 0，发 `Open(640,400,30,...)`。
- [ ] 抓第一个 `0x06` 包；解析 36 B 头，确认 width/height 与 SPS 一致。
- [ ] 保存一帧 H.264 到文件，`ffprobe`/`h264_analyze` 确认 640×400、H.264 High、无 B 帧。
- [ ] 若 640×400 被拒，改 800×480/1280×720 重试。

**通过标准**：能持续收到 0x06 帧 ≥20 fps；SPS 分辨率符合请求。

### Step 4：解码 → RGBA → 眼镜（3–5 天）

- [ ] `MediaCodec` 解码到 `ImageReader`/`SurfaceTexture`。
- [ ] 拿一帧 RGBA，`mapCover/fit` 到 640×400，`LcdProtocol.frame()`，`bulkTransfer`。
- [ ] 在眼镜上看到 CarPlay 画面；记录 fps、单帧耗时、端到端延迟。
- [ ] 用 1 kHz 计时器叠加在画面上，手机 240 fps 慢动作拍眼镜，测端到端。

**通过标准**：眼镜上 ≥20 fps；端到端 ≤250 ms；文字可读。

### Step 5：输入（2–3 天）

- [ ] 手机触摸板 → `0x17` 多点触控；确认点击/滑动/双指。
- [ ] Fn 短按 → Home/Select；长按 → 显示切换/Siri。
- [ ] 可选：BLE 遥控 → knob HID（x/y/wheel/select/home/back）。

**通过标准**：能完成“打开地图 → 搜索 → 选目的地 → 开始导航”全流程。

### Step 6：定型（1 周）

- [ ] 25 fps 锁定、丢旧帧、关键帧恢复（cmd 12）。
- [ ] 音频路由（手机扬声器/BT/眼镜 UAC 三选一）。
- [ ] 前台服务、开机自启、锁屏、热管理。
- [ ] 30 分钟 soak + 拔插恢复 + 分辨率回退。

---

## 9. 证据索引

| 结论 | 证据 |
|---|---|
| open-headunit 是 AA 接收端 | README L12；`aap/` 包；`VideoDecoder.kt` L24-L27 |
| open-headunit 支持 AA 无线盒子 | README L44-L46；`UsbAttachPolicy.kt` L13-L23 |
| open-headunit 视频 H.264/H.265、800×480–3840×2160 | `VideoDecoder.kt` L24-L27、L121-L127；`VideoCodecNegotiationPolicy.kt` L19-L34 |
| open-headunit 触摸转发 | `AapProjectionActivity.kt` L2297/L2792；`TouchCoordinateMapper.kt` L11-L73 |
| DiPlay 不用盒子 | README L15 |
| DiPlay 身份来自 Carlinkit 固件 | README L25；THIRD_PARTY_NOTICES “Experimental authentication data” |
| DiPlay 视频 H.264/H.265 + ChaCha | `ScreenStream.kt` L13、L17-L20、L100-L123、L167-L201 |
| DiPlay 解码到 Surface | `AndroidMediaSink.kt` L176-L182、L1207-L1208、L1358 |
| DiPlay 分辨率=主机窗口 | `CarPlayHostActivity.kt` L5881-L5896、L7106-L7110 |
| DiPlay 触摸/旋钮 HID | `AirPlayHid.kt` L46-L70；`CarPlayController.kt` L665-L681 |
| DiPlay 有线 NCM | `NcmUsbBridge.kt` L9-L16 |
| CPC200 USB 头/视频格式 | `usb_protocol.md`、`video_protocol.md` |
| CPC200 的 iPhone 侧 AirPlay/MFi | `wireless_carplay.md` |
| CPC200 分辨率由 Open 决定 | `video_protocol.md` “How the Head-Unit Resolution Reaches the iPhone” |
| Carlink 支持 CPC200 | README L12-L13；`usb_device_filter.xml`；`H264Renderer.java` L22-L34 |
| Carlink 的 Open 28 B | `MessageSerializer.kt` L352-L367 |
| Carlink 触摸 0x17 | `MessageSerializer.kt` L112-L150 |
| 眼镜 1,024,016 B/帧、25 fps | 本仓库 `docs/ch-display.md` L46-L93 |
| 眼镜发送 API | `UsbLink.kt` L260-L293；`CastService.kt` L754/L770/L793 |
| 方案 A 已实现 | `linux/glxss/nav.py` L2-L4；`linux/glxss/hud.py` L8 |
| 许可 | 各仓库 LICENSE；GPL/AGPL 官方文本 |

---

## 10. 参考链接

- [open-headunit](https://github.com/andreknieriem/open-headunit)
- [DiPlay](https://github.com/shihabal3amri/DiPlay)
- [Carlink（CPC200 Android 主机）](https://github.com/lvalen91/Carlink)
- [CPC200-CCPA 协议资料](https://github.com/lvalen91/CPC200-CCPA_resources)
- [node-CarPlay（CPC200 的另一份实现）](https://github.com/rhysmorgan134/node-CarPlay)
- [Carlinkit 无线 CarPlay 盒子逆向](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering)
- [Carlinkit CPC200-CCPA（Amazon 参考价）](https://www.amazon.com/dp/B09ZQJWNJT)
- [Apple MFi Program](https://mfi.apple.com/)
- [Apple CarPlay](https://www.apple.com/ios/carplay/)
- [GNU GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html)
- [GNU AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html)
- [17 U.S.C. §1201（DMCA 反规避）](https://www.law.cornell.edu/uscode/text/17/1201)

---

## 附：一句话回答每个问题

1. **怎么拿 CarPlay？** open-headunit 拿不到（它是 AA）；DiPlay 是原生 AirPlay/iAP2 接收端，不用盒子；
   盒子路线是 CPC200-CCPA，视频是 H.264 over USB bulk。
2. **能接眼镜吗？** 能，换掉解码器的输出 Surface，拿 RGBA 走现有 640×400 管线；25–30 fps，
   端到端约 100–200 ms。
3. **输入？** 手机屏幕当触摸板最实际；Fn 只能做 Home/Select；单键不够导航。
4. **许可/法律？** AGPL/GPL/Unlicense 各不相同；B1 用认证盒子风险最低；B2 的 MFi 身份是硬伤。
5. **结论？** A 继续用；B 先 B0，再 B1；B2 不产品化。
