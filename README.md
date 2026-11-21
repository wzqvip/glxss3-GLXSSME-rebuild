# GLXSS3 投屏协议研究 / GLXSS3 Cast Protocol Research

> 对 **亮亮视野 LLVision GLXSS3（G25）AR 眼镜** Android App（`com.llvision.glxss3.camera` v2.3.7）
> 的**互操作性**研究：它如何把手机画面"投屏"到眼镜，以及协议细节。
>
> A study of the screen-casting ("投屏") protocol used by the LLVision GLXSS3 (G25) AR glasses
> Android app, for interoperability and personal hardware experimentation.

**本仓库不包含 APK、固件或任何第三方专有文件**（仅含分析笔记、我们自己编写的代码与协议描述）。
**本项目禁止商业使用** —— 见 [LICENSE](LICENSE)。

---

## 一句话结论

眼镜是一个 **USB 显示器**：手机把画面渲染成 **640×400 RGBA8888**（1,024,000 字节），
加上 **16 字节头**，通过 **USB bulk** 发送给眼镜；AR 叠加逻辑全部在手机端，眼镜固件只负责显示。

> The glasses are a *dumb USB display*: the phone renders a 640×400 RGBA frame, prepends a
> 16-byte header, and pushes it over USB bulk. All "AR" logic lives in the phone app.

## 设备画像 / Device profile

| 项 | 值 |
|---|---|
| App 包名 | `com.llvision.glxss3.camera`（v2.3.7） |
| 厂商代号 | glxss3 / G25（构建任务 `G25_paltform_sdk_DTR`，SDK `com.llvision.glass3.sdk`） |
| 正常模式 USB | **VID `0x2E09` / PID `0x0030`**（复合设备：UVC 摄像头 + LCD + HID） |
| 固件下载模式 USB | **VID `0x03E7` / PID `0x2150`** |
| 显示分辨率 | **640 × 400**，DPI = 1 |
| 帧格式 | `16B 头 + 裸 RGBA8888` |
| 固件 | `G25_MainFW_1.9.1.img`：`MA2x` 容器 + Marvell MVCMD DDR init + `main.elf`（2016-06-09） |

## 帧格式 / Frame format

```
offset  size  field
0       1     format   = 1                 # 1 = raw RGBA8888
1       1     format2  = 0
2       1     format3  = 0
3       1     format4  = 0
4       4     totalLen = 16 + payloadLen   # little-endian uint32
8       8     extended[8] = 0
16      N     payload  = w*h*4  (640x400 => 1,024,000 bytes, RGBA)
```

## 快速开始 / Quick start

```bash
pip install pyusb
sudo python3 code/poc_send_frame.py --list            # 查看接口与端点
sudo python3 code/poc_send_frame.py --pattern bars --fps 5   # 发送色条测试帧
```

> 发送前请先确认眼镜处于正常模式（`0x2e09:0x0030`），并选对 vendor-specific 接口的 bulk OUT 端点。
> 控制命令（亮度/开关/旋转/显示模式）的参数仍需在真机上抓包确认，见 [docs/open-questions.md](docs/open-questions.md)。

## 仓库结构 / Layout

```
docs/                 研究文档
  device.md           设备画像、USB、固件
  protocol.md         投屏协议（帧格式、数据流、控制命令）
  open-questions.md   待验证项与验证方法
code/
  poc_send_frame.py   发送端 PoC（Linux + pyusb）
  tools/elf_disasm.py 小工具：解析 ELF、反汇编导出函数（capstone）
LICENSE               非商业使用许可（中英双语）
NOTICE.md             免责声明与合规说明
```

## 研究状态 / Status

- ✅ 协议层：静态逆向完成（jadx 反编译 + 原生库分析）
- ⚠️ 硬件层：端点号、control-request 字节、流控、实际帧率 **待真机验证**
- ❌ 未包含：APK、固件、任何厂商二进制

## 免责声明 / Disclaimer

本项目与 LLVision（亮亮视野）无任何关联，未获其授权。相关商标归其权利人所有。
所有分析仅用于**互操作性**与**个人研究**；请勿用于侵犯他人权利、破解付费服务或攻击设备。
详见 [NOTICE.md](NOTICE.md)。

## 许可 / License

**非商业使用**（Non-Commercial）。允许个人学习、研究、教学与非商业项目使用、修改与再分发
（须保留许可与署名）；**禁止任何商业使用**，商业授权需事先书面许可。
完整条款见 [LICENSE](LICENSE)。
