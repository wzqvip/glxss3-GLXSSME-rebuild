# GLXSS3 / G25 — Linux 与跨平台版本（`linux` 分支）

这个目录把仓库里已经逆向清楚的协议重构成一个 **Python 3.9+ 跨平台库 + `glxss` CLI**，
目标平台是 Linux（含 Raspberry Pi Zero 2 W）、Windows、macOS。它与 Android 版
（根目录 `app/`）完全独立，不改动 Android 代码。

> 协议依据：`docs/ch-display.md`、`docs/ch-uvc.md`、`docs/ch-hid.md`、
> `docs/ch-firmware.md`、`docs/firmware-boot-protocol.md`。
> 仓库不包含原厂固件、APK 或任何第三方二进制。

---

## 1. 目录结构

```text
linux/
├─ glxss/
│  ├─ usb.py       # pyusb 设备发现/打开/claim/control/bulk 封装
│  ├─ lcd.py       # 16B 头 + 640×400 RGBA，亮度/开关/模式/roll/状态/EEPROM
│  ├─ uvc.py       # probe/commit、12B header、YV12→RGB（numpy）
│  ├─ hid.py       # 300ms 轮询 28B 报告、版本串、传感器使能
│  ├─ firmware.py  # AES-128-ECB 尾部解密 + 03e7:2150 灌写（默认 dry-run）
│  ├─ capture.py   # ImageSource / TestPatternSource / mss 屏幕采集 / 三种映射
│  ├─ app.py       # 采集→640×400→发送主循环，带 fps/MB/s 统计
│  └─ cli.py       # glxss 子命令
├─ pi/
│  ├─ lcdhat.py    # Waveshare 1.3inch LCD HAT：ST7789 状态页 + 按键 + 投屏
│  ├─ service/glxss-cast.service
│  └─ README.md    # 接线、SPI、依赖、systemd
├─ udev/99-glxss.rules
├─ tests/test_core.py
├─ tools/make_test_png.py
├─ pyproject.toml
└─ requirements.txt
```

当前状态：

| 功能 | 状态 |
|---|---|
| LCD 帧/控制/状态/EEPROM/版本 | ✅ 已实现，复用已验证协议；尚未在真机跑本版本 |
| UVC probe/commit + 读帧 + PNG | ✅ 已实现；YV12→RGB 用 numpy |
| HID 轮询/传感器使能 | ✅ 已实现 |
| 固件 AES 尾部解密 + 灌写 | ✅ 已实现，默认 dry-run，必须 `--yes` |
| 屏幕采集 | ✅ `mss`（可选依赖）；无桌面用 `--source test` |
| Pi LCD HAT 状态页/按键 | ✅ 代码完成；需按实际面板微调 offset |
| 音频/UAC、NPU、录像、MJPEG | ❌ 未实现（不在本次范围） |
| 写 EEPROM | ❌ 故意不实现（危险，会改 SN/BOM） |

---

## 2. 安装

### 2.1 Linux（x86_64 / ARM）

```bash
sudo apt update
sudo apt install -y python3-venv libusb-1.0-0

cd glxss3-touying/linux
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -U pip
python -m pip install -e ".[dev]"     # pyusb + numpy + mss + Pillow
```

没有桌面、只投测试图案时，最少只需要 `pyusb` + `numpy`：

```bash
python -m pip install -e .
```

推荐 udev 权限（不要每次 sudo）：

```bash
sudo cp udev/99-glxss.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules
sudo udevadm trigger
```

### 2.2 Windows 10/11

```powershell
py -3 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -U pip
python -m pip install -e ".[dev]"
```

还需要两步：

1. **libusb-1.0.dll**：从 libusb 官方 release 下载，放到 `C:\Windows\System32`
   （管理员）或放在 PATH 里；也可以在 Python 旁边放一份。
2. **Zadig 绑定 WinUSB**：下载 [Zadig](https://zadig.akeo.ie/)，管理员运行，
   `Options → List All Devices`，选中眼镜的**目标接口**，把驱动替换成 **WinUSB**。

> 重要：GLXSS 是复合设备（UVC + HID + LCD + UAC）。Zadig 如果只能选整个复合设备，
> 把整设备绑成 WinUSB 会让 Windows 的摄像头/HID/音频驱动一起失效。
> 只投屏时优先只绑定厂商 LCD 接口（接口 3，EP `0x01`）；若 Zadig 不显示单独接口，
> 建议在 Linux/树莓派上做完整功能，Windows 只做验证。
> `glxss list` 在绑定前也能枚举设备，但 `status/frame` 会因没有 WinUSB 而打不开。

Windows 下 `mss` 用 GDI 采集，`frame image`/`--save-png` 需要 Pillow。

### 2.3 macOS

```bash
brew install libusb
cd glxss3-touying/linux
python3 -m venv .venv && . .venv/bin/activate
python -m pip install -e ".[dev]"
```

macOS 的系统 UVC/IOHID 驱动可能占着相机/HID 接口；LCD 厂商接口通常可直接访问。
接口 busy 时先试 `--detach`。UVC/HID 在 macOS 上未做真机验证，必要时用 Linux/Pi 做完整测试。

安装后：

```bash
glxss --help
glxss list
```

如果 `glxss` 不在 PATH，改用 `python -m glxss ...`。

---

## 3. 快速开始

```bash
# 1) 枚举（不需要眼镜；有眼镜时会出现 2e09:0030 或 03e7:2150）
glxss list

# 2) 读 LCD 状态，控制亮度/屏/模式
glxss status
glxss bright 180
glxss screen on
glxss mode 2

# 3) 离线验证帧格式（不碰硬件）
glxss frame test --dry-run
glxss frame bars --dry-run
glxss frame solid --color ff8000 --dry-run

# 4) 发帧/投屏
glxss frame bars --fps 10 --seconds 5
glxss frame image screenshot.png --fit cover --seconds 5
glxss cast --source screen --fps 10
glxss cast --source test --fps 15 --duration 10

# 5) 摄像头 / 传感器 / 版本
glxss uvc --frames 3 --save first-frame.png
glxss hid --enable-sensors --count 10
glxss version
glxss eeprom --raw

# 6) 固件（默认只分析，不加 --yes 不写）
glxss flash /path/to/G25_MainFW_1.9.1.img
glxss flash /path/to/G25_MainFW_1.9.1.img --yes
```

### 帧格式（不可改）

```text
16 B 头：01 00 00 00 | totalLen(u32 LE, 含头=1,024,016) | 8 B 0
payload：640×400×4 = 1,024,000 B 裸 RGBA8888
整帧    ：1,024,016 B，一次 bulk OUT（EP 0x01 / 接口 3）
```

`frame --dry-run` 会打印帧头、总长度和整帧 SHA256；`--save-png` 可以把映射后的
640×400 RGBA 保存下来核对。`tools/make_test_png.py` 进一步用 Pillow/numpy 生成
测试图，并做“写入 PNG → 回读 → 与 RGBA 数组逐字节比较”。

---

## 4. CLI 子命令

| 命令 | 说明 |
|---|---|
| `list` | 枚举 USB，标出 `2e09:0030` / `03e7:2150`；`--brief`/`--glxss-only` |
| `status` | `0xA1/0x04` 读 8 B 状态 |
| `bright N` | `0xA1/0x01`，实测有效 100..254 |
| `screen on\|off` | `0xA1/0x03` |
| `mode N` | `0xA1/0x05`，1=待机/logo，2=投屏 |
| `roll N` | `0xA1/0x02`，0..65535 |
| `eeprom` | `0xA1/0x07` 只读 264 B 产品信息 |
| `version` | `0xA1/0x17` 24 B：版本串 + 设备 ID |
| `hid` | `0xA1/0x16` 28 B，默认 300 ms 轮询 |
| `uvc` | GET/SET probe + commit，bulk IN 读 YV12；`--save` 存首帧 PNG |
| `frame` | 测试图案/图片，按 16B 头发送；`--dry-run` 不碰设备 |
| `flash` | 03e7:2150 灌写；默认 dry-run，`--yes` 真写 |
| `cast` | 采集屏幕/测试图案，连续投屏；`--fps`、`--fit` |

`--fit` 支持：

* `fit`：等比缩放，上下/左右留黑边（默认）
* `cover` / `crop`：等比放大填满，居中裁切
* `stretch`：直接拉到 640×400

`--detach` 只在 Linux 有意义；Windows 用 Zadig，macOS 视驱动情况尝试。

---

## 5. 代码里复用

```python
from glxss import capture, lcd, usb

source = capture.TestPatternSource()          # 或 capture.ScreenSource()
rgba = capture.map_to_rgba(source.grab(), mode="fit")
packet = lcd.build_frame(capture.rgba_bytes(rgba))

dev = usb.find_normal()
with usb.DeviceSession(dev, detach=False) as session:
    intf, ep = usb.pick_bulk_out(dev)
    session.claim_interface(intf, label="LCD")
    link = lcd.LcdLink(dev, ep.bEndpointAddress)
    link.initialize()
    link.send_packet(packet)
```

`capture.ImageSource` 是采集抽象；`TestPatternSource` 无依赖桌面，`ScreenSource`
使用 `mss`（Linux 上经 X11/XShm）。当前没有实现直接读 `/dev/fb0`/DRM 的源，
需要无 X11 的 framebuffer 采集时可以实现自己的 `ImageSource` 注入 `CastApp`。
`glxss.app.CastApp` 是带 fps 统计的主循环，可注入任意 `sender`。

---

## 6. Raspberry Pi + LCD HAT

完整接线（按 Waveshare 官方 wiki）、SPI 打开、依赖、systemd 安装见
[`pi/README.md`](pi/README.md)。一句话流程：

```bash
# 在 Pi 上，仓库放在 /opt/glxss，虚拟环境在 /opt/glxss/venv
cd /opt/glxss/linux
.venv/bin/python pi/lcdhat.py --source test --fps 15
```

状态页显示眼镜连接、FPS、亮度、模式、采集源和最近按键；摇杆/按键直接下发
亮度/模式/开关屏命令。服务示例：

```bash
sudo cp pi/service/glxss-cast.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now glxss-cast
journalctl -u glxss-cast -f
```

注意：`mss` 在树莓派上需要 X11/XWayland；Raspberry Pi OS Lite 没有桌面时，
把 service 的 `--source screen` 改为 `--source test`，或接 HDMI 并启动桌面。

---

## 7. 平台差异与排障

| 现象 | 处理 |
|---|---|
| `No backend available` / 找不到 libusb | Linux 装 `libusb-1.0-0`；Windows 放 `libusb-1.0.dll`；macOS `brew install libusb` |
| `未找到 2e09:0030` | 设备可能还在 `03e7:2150`（需先灌固件）；或线材/供电；先 `glxss list` |
| `权限不足` / `Access denied` | Linux 装 udev 规则；Windows 用 Zadig 绑 WinUSB；macOS 检查 libusb |
| `接口忙` / claim 失败 | 退出厂商 App；Linux 加 `--detach`；同一进程不要重复 claim |
| 发帧成功但屏不亮 | 先 `glxss screen on` + `glxss mode 2`（`frame/cast` 默认会做） |
| UVC 超时 / 0 帧 | 关掉其他摄像头程序；Linux `--detach` 摘掉 uvcvideo；固件假死需重灌 |
| 屏幕采集全黑 | Linux Wayland 用 XWayland，或改 `--source test` |
| LCD HAT 画面偏移 | 调 `pi/lcdhat.py --x-offset/--y-offset`（默认 `0/80`），或换 `--rotation` |
| 刷写中设备断开 | 正常：写完会重枚举为 `2e09:0030`；不要拔线，等 8 s |

### 固件与安全

* `flash` 默认 **dry-run**：只读文件、算 SHA256、打印计划，不打开设备。
* `--yes` 才会写；写之前会检查 `MA2x` 和 `G25_MainFW_` 版本串，除非 `--force`。
* 只解密镜像最后 `min(size, 512 KiB)`，AES-128-ECB，key `b5Q8JBFRYYIRawl3`。
* 仓库不提供固件文件；请使用你合法持有的原厂镜像。
* 本工具不实现写 EEPROM（`0x21/0x06`）。

---

## 8. 自检

无眼镜、无桌面也能跑：

```bash
cd linux
python -m compileall -q glxss pi tests tools
python -m unittest discover -s tests -v
python -m glxss --help
for c in list status bright screen mode roll eeprom version hid uvc frame flash cast; do
  python -m glxss "$c" --help >/dev/null || exit 1
done
python -m glxss frame test --dry-run
python tools/make_test_png.py --out /tmp/glxss-test-pattern.png
```

Windows PowerShell 对应：

```powershell
python -m compileall -q glxss pi tests tools
python -m unittest discover -s tests -v
python -m glxss frame test --dry-run
python tools/make_test_png.py --out "$env:TEMP\glxss-test-pattern.png"
```

---

## 9. 协议出处

* 显示：`docs/ch-display.md` §2–§3
* UVC：`docs/ch-uvc.md` §1–§5
* HID：`docs/ch-hid.md` §4–§6
* 固件：`docs/ch-firmware.md` §3–§4
* 引导：`docs/firmware-boot-protocol.md`

本项目禁止商业使用，详见根目录 [LICENSE](../LICENSE)。
