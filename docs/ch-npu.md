# 第 8 章 · NPU 现状与研究空间

> 一句话：**这颗芯片有神经网络加速能力，但这副眼镜的固件没有把它开放给主机。**
> 本章解释为什么"NCS1 同款芯片"不等于"插上就能跑模型"，以及如果真想榨干它，有哪几条路、代价是什么。
>
> 相关：[README §2 硬件规格](../README.md#2-硬件规格)、[ch-firmware.md](ch-firmware.md)、
> [apk-versions.md §6](apk-versions.md#6-代码功能差异184--237)（厂商的视觉通道线索）、
> [open-questions.md](open-questions.md)

---

## 1. 这颗芯片有什么

| 项 | Myriad 2（MA2450） |
|---|---|
| CPU | 2 × LEON4（SPARC V8，32 位），跑 RTEMS |
| 向量核 | **12 × SHAVE**，128-bit SIMD VLIW |
| 图像流水线 | SIPP（可编程图像处理）、CIF、MIPI CSI-2 |
| 内存 | 片上 4 MB + 外挂 DDR |
| 接口 | USB 3.0/2.0、I2C、SPI、UART、GPIO、MIPI |
| 同款产品 | **Intel Neural Compute Stick 1（NCS1）** |
| 在本眼镜里干什么 | 相机 ISP/采集、LCD 驱动、USB 复合栈（MCCI）、HID、音频——**没有对外推理接口** |

NCS1 之所以能跑 CNN，是因为 Intel 的 NCS 固件把 SHAVE 组织成了推理运行时，并**通过 USB 暴露了一套
"加载图 + 传张量 + 取结果"的协议**。这套协议在 NCS 固件里，不在这颗芯片的 ROM 里。

---

## 2. 现状：❌ 未驱动

| 层 | 状态 | 说明 |
|---|:---:|---|
| 手机端调用 NPU | ❌ | 没有任何推理 API；本仓库也没有实现 |
| 固件暴露推理接口 | ❌ | 正常模式只有 UVC / LCD / HID / UAC 四类功能 |
| NCSDK / OpenVINO 直接使用 | ❌ | 它要的固件与厂商固件互斥（见 §3） |
| 厂商自己的视觉能力 | ⚠️ | 固件内部可能用 SHAVE/SIPP 做人脸检测，但只有私有通道（见 §4） |

### 2.1 为什么不能"直接当 NCS1 用"

> 本章没有"可运行代码"——这正是 ❌ 的含义：**没有接口可调**。

```
NCSDK 的用法：
  PC/手机 ── usb_boot ──► 03E7:2150（ROM）
                └─ 灌 NCS 自己的 MVCMD（含推理运行时）
                      └─ 设备重枚举成 NCS 的 USB 接口
                            └─ 主机发图 → 设备推理 → 回结果

眼镜的现状：
  主机 ── usb_boot ──► 03E7:2150（同一颗 ROM，同一个 ID）
            └─ 灌 G25_MainFW_1.9.1.img（含 LCD/相机/USB 厂商固件）
                  └─ 设备重枚举成 2E09:0030（UVC/LCD/HID/UAC）
                        └─ 没有任何"发图/取结果"端点
```

**关键点：USB 引导层是同一套**（这就是为什么本项目的 flasher 和 NCSDK 的 `usb_boot` 几乎一样），
**区别只在灌进去的镜像**。一旦灌 NCS 固件，设备就"变成 NCS1"——厂商固件里的 LCD/相机/UAC 全部消失。

### 2.2 还有三道现实门槛

| 门槛 | 说明 |
|---|---|
| **工具链** | 写自己的 MVCMD 需要 Intel Movidius Development Kit（MDK，SHAVE 编译器 + LEON 工具链），已停止公开分发 |
| **板级支持** | 厂商固件里有 `Board Mv0212`、`imx214M10MO`、`LcdGeneric/leon/Lcd.c` 等板级代码；NCS 固件不认识这块板子的面板/相机 |
| **镜像来源** | Intel 已归档 `myriad-usbboot`，NCSDK 也不再维护；NCS1 固件不是本仓库的一部分，需要自行合法获取 |

> 还有一个容易忽略的点：据 NCSDK 的 `usb_boot` 流程，**NCS1 正常工作时也以 `03E7:2150` 出现**
> （同一套 ROM 引导）。所以"看到 03E7:2150"不能区分"眼镜的 ROM"和"NCS 的 ROM"，
> 只能靠灌进去的镜像和随后枚举出的接口来判断。

---

## 3. 厂商固件里其实有"视觉"

这是最有趣、也最容易被忽略的线索：

| 版本 | 证据 | 含义 |
|---|---|---|
| 1.8.4（应用商店版） | `libUVCCamera.nativeGetCameraReportData()` 返回 `CameraReportData{afResult, cafFlag, cafStatus, CameraFdData{face_num, faceLoc0..3}}` | 固件会做人脸检测，并把**人脸坐标 + 对焦状态**回传给主机 |
| 2.3.7（原厂相机版） | 新增 `libfacedtr-libusb.so`（5 个 ABI）、`sdk/facedtr/FaceDtr`（`nativeSendStream` / `nativeGetStream`）、`sdk/memory/*`（ashmem 共享内存）、`UsbHostDataTransferService`（`IHostAIDL`），以及通用 `nativeControlTransfer(...)` | 改成"主机送帧 → 设备（或 native 库）出结果"的双向通道 |

【推】结论：**固件里存在一条厂商私有的"视觉数据"通道**，而且它至少能：
1. 从主机接收图像/视频流；
2. 回传结构化结果（人脸框、对焦状态）；
3. 通过 AIDL/共享内存与手机侧协作。

这条通道没有被逆向出协议，所以本项目**没有使用**它，也没有把它算进"NPU 已驱动"。
它是目前唯一已知的"让 Myriad 2 干活并把结果拿回来"的路径，值得单独研究。

---

## 4. 四条可能路线

| 路线 | 做法 | 收益 | 代价/风险 | 状态 |
|---|---|---|---|---|
| **A. 主机侧推理** | 手机/PC 跑模型，眼镜只当显示器/摄像头 | 立刻可用、零风险 | 浪费 Myriad 2；手机耗电/发热 | ✅ 现在就能做 |
| **B. 换成 NCS 固件** | 通过 `03E7:2150` 灌 NCS1 的 MVCMD | 变成标准 NCS1，可用 NCSDK/OpenVINO | **失去显示/相机/音频**；需要合法镜像；板级不匹配可能起不来；可回刷但麻烦 | ❌ 未验证（高风险） |
| **C. 自己写 MVCMD** | 用 MDK 写同时驱动 LCD/相机 + 推理的固件 | 理论最优：显示 + 视觉一体 | MDK 难获取；要逆向面板/相机接线与时序；工作量大 | ❌ 未开始 |
| **D. 逆向厂商 DTR 通道** | 复用 `FaceDtr` / `CameraReportData` 的私有协议 | 保留全部现有功能，还能白嫖固件的视觉能力 | 协议未公开；涉及 `libfacedtr-libusb.so` 逆向；可能被固件版本限制 | ⚠️ 研究项 |

### 4.1 路线 B 的具体风险

1. **镜像不匹配**：NCS 固件的 DDR 初始化/时钟/板级代码是按 Intel 参考板写的，不是 MV0212；
   最坏情况是"灌进去起不来"，但 ROM 仍在，通常还能重新灌回 1.9.1。
2. **外设全丢**：没有 `LcdGeneric` / `CamGeneric` / `imx214` 驱动，屏幕和摄像头不会工作。
3. **不可逆的部分**：如果新固件改写了 EEPROM/SPI flash（正常 NCS 固件不会），恢复会更麻烦。
4. **合规**：NCS 固件、MDK 都是 Intel 的版权物，本仓库不提供，也不能随项目分发。

### 4.2 路线 D 的研究步骤（只读优先）

1. 静态看 `libfacedtr-libusb.so` 的导出符号、字符串、`nativeControlTransfer` 的参数形态
   （类似当年逆向 `liblcd.so` 的做法）；
2. 在**不刷机**的前提下，用 Frida hook 原厂 App 的 `FaceDtr` 调用，记录 control/bulk 的
   端点、方向、长度、频率；
3. 用一段预录的 YV12 帧重放，观察返回结构（人脸框坐标？置信度？）；
4. 只有确认协议无副作用后，才考虑在自己的 App 里复现。

> 这一路线的价值不只是人脸检测：如果它真是"主机送帧 → Myriad 出结果"的通用通道，
> 理论上可以塞任何轻量模型进去——但那需要先知道固件认什么格式。

---

## 5. 如果目标只是"跑模型"

按性价比排序：

1. **手机/PC 侧推理**（路线 A）：最稳，和本项目的投屏/摄像头功能完全兼容；
2. **单独买一个 NCS1/NCS2**：二手很便宜，把眼镜只当显示/采集端，两个设备各干各的；
3. **路线 D**：如果你愿意做逆向，且能接受"只能用人脸检测"或"固件认的模型格式"；
4. **路线 B/C**：除非你的目标就是"用这副眼镜的芯片做研究"，否则不建议。

> 一句话：**"神经棒"能力和"显示/摄像头"能力，在现有固件下是互斥的**。
> 想要前者，就得放弃后者；想两者兼得，只能自己写固件（路线 C）。

---

## 6. 实测/证据索引

| 结论 | 证据 |
|---|---|
| SoC = Myriad 2 MA2450 | [README §2](../README.md#2-硬件规格)、[soc-myriad2.md](soc-myriad2.md) |
| 正常模式无推理接口 | 7 接口全枚举 + 全部 vendor control 请求枚举，见 [firmware-extraction.md §3](firmware-extraction.md#3-结论三不能从工作中的眼镜-usb-读回整片固件) |
| 引导层与 NCSDK 同源 | [firmware-boot-protocol.md §7](firmware-boot-protocol.md#7-与开源-myriad-usbboot--ncsdk-的对照) |
| 固件里有相机/LCD/USB/RTEMS | 固件 strings，见 [ch-firmware.md §2.2](ch-firmware.md#22-解密后的关键位置) |
| 厂商有视觉回传 | [apk-versions.md §6](apk-versions.md#6-代码功能差异184--237)（F7） |
| NCS1 正常模式也是 03E7:2150 | [soc-myriad2.md](soc-myriad2.md) 的 USB ID 表 + NCSDK 文档 |

---

## 7. 待验证

- [ ] `libfacedtr-libusb.so` 的完整协议（端点、消息头、结果结构）。
- [ ] 固件内部是否真的跑 CNN（而不是传统 CV 人脸检测）。
- [ ] NCS1 的 MVCMD 是否能在这块板子上启动（高风险，需要可恢复的测试环境）。
- [ ] 板子的 LCD/相机接线与时序（路线 C 的前置条件，需要拆机/示波器）。
- [ ] EEPROM/SPI flash 是否会被错误固件改写。
- [ ] MDK 是否还有合法获取途径（目前公开渠道已下架）。

---

## 8. 风险提示

| 风险 | 后果 | 缓解 |
|---|---|---|
| 灌入不匹配的固件 | 设备不启动，可能停留在引导模式 | 先确认能枚举 `03E7:2150`；保留原始 1.9.1 镜像 |
| 刷写中断电 | 一级引导写坏，极端情况需硬件恢复 | 保证供电，不要在传输中拔线 |
| 拆机找 UART/JTAG | 失去保修/损坏外壳 | 非必要不做 |
| 使用他人固件/工具链 | 版权/许可问题 | 只用自己合法持有的材料；本仓库不附带 |

> 本项目**没有**做任何 NPU 相关实验，也没有推荐你去刷 NCS 固件。
> 本章的目的是把"为什么不能"讲清楚，把"如果要试，代价是什么"摆出来。
