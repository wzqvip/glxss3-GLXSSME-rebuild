# 眼镜现状调研（已并入新文档）

> 这份早期笔记的内容已经拆分到新的 README 与章节，**请优先阅读**：
>
> - 硬件/能力/状态矩阵 → [README](../README.md)
> - 为什么做替代 App、原厂 App 的限制 → [README §1](../README.md#1-这是什么--为什么做)
> - 不写固件能不能用 NPU → [第 8 章 · NPU](ch-npu.md)
> - 原厂 App 的功能拆分 → [第 7 章 · App](ch-app.md)
>
> 下面只保留几条**没有搬到别处**的历史信息。

## 产品与厂商

| 项 | 内容 |
|---|---|
| 产品 | LLVision（亮亮视野）GLXSS3 / G25 AR 眼镜 |
| 厂商现状 | 已停止支持（社区反馈公司停运） |
| 原厂 App | `com.llvision.glxss3.camera` v2.3.7（构建于 2016–2017 年代） |
| 社区 | 有付费的"二合一"改版（本仓库分析样本之一） |

## 原厂 App 的组件

`LLVisionPlatformService`（USB 连接/状态/固件升级）、`UVCService`（libuvc 预览/拍照）、
`LCDService`（投屏/亮度/开关/旋转/显示模式）、`SensorService` / `KeyEventService` / `LightService`
（HID/GPIO）、`appstore`（账户/云，已失效，不依赖）。

## 原厂 App 的限制（新 App 的机会）

- 只支持 640×400 一种分辨率、单一投屏模式，无缩放/裁剪；
- 没有断电/断开自动重连、没有重试与超时提示；
- 没有帧率/带宽控制，画面卡顿无处理；
- 没有"只投某个 App"、没有横竖屏/镜像翻转、没有亮度快捷调节；
- 依赖已失效的 appstore/账户体系；
- 代码质量差（jadx 可见大量复制粘贴、异常吞掉）。

这些限制对应的解决方案见 [第 7 章 · App](ch-app.md) 的"已知问题 / 下一步"。
