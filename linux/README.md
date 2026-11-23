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
│  ├─ hud.py       # 导航 HUD 渲染源：箭头/距离/动作 + heading/姿态/lux/温度
│  ├─ nav.py       # 手机 → Pi Wi-Fi UDP JSON → LatestNav 线程安全单槽
│  ├─ monitor.py   # 相机监看源：UVC 读帧 → 640×400 → 九宫格/水平仪/REC → 眼镜
│  ├─ pipeline.py  # 一次采集多消费者：最新帧槽 / YV12→yuv420p 零拷贝 / LCD 渲染线程
│  ├─ control.py   # 与 LCD HAT 的 JSON over Unix socket 控制通道
│  ├─ app.py       # 采集→640×400→发送主循环，带 fps/MB/s 统计
│  └─ cli.py       # glxss 子命令（含 hud / ctl）
├─ pi/
│  ├─ lcdhat.py    # Waveshare 1.3inch LCD HAT：状态页 + 菜单 + socket 控制
│  ├─ SETUP.md     # 从零安装（SD 卡、SPI、接线、udev、systemd、排障）
│  ├─ service/glxss-cast.service      # HUD 主进程 + 控制 socket
│  ├─ service/glxss-lcdhat.service    # HAT 控制器
│  └─ README.md    # 接线/菜单/最短命令
├─ packaging/      # PyInstaller：build.sh / build.ps1 / glxss.spec / README
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
| HUD 渲染源（默认） | ✅ 已实现：Pillow 640×400 RGBA，2 fps 级别重绘 |
| 相机监看（monitor） | ✅ `cast --source uvc` / `hud --view monitor`；九宫格 + 水平仪 + 可选 REC；合成帧 10 fps 实测 |
| 直播 + 眼镜预览（共享帧） | ✅ `pi/stream.py --mirror-fps 10`：一次采集分发两路，真机 采集 30 / 编码 25 / 眼镜 10 fps，总 CPU 65–69% |
| 直播中的 Fn 键 | ✅ 长按 ≥0.9 s = preview ↔ HUD（stream 进程内渲染，不 release LCD）/ 短按（preview）= 循环变焦 1.0/1.5/2.0x（HUD 下忽略）；补 `[hid] key 0x71 down/up` 日志、`ctl button click/long` 注入、`ctl zoom` |
| Pi LCD HAT 状态页/菜单 | ✅ 已实现：状态页 + 7 项菜单（含 VIEW）+ Unix socket |
| PyInstaller 三端打包 | ✅ 脚本/spec 完成；Windows onedir 实测可运行 |
| 屏幕采集 | ✅ `mss`（可选依赖）；无桌面用 HUD/test |
| 音频/UAC、NPU、录像、MJPEG | ❌ 未实现（不在本次范围） |
| 写 EEPROM | ❌ 故意不实现（危险，会改 SN/BOM） |

> 用户定位：**LCD HAT = 状态/控制面板，眼镜 = 主显示，内容用 HUD 渲染**，
> 不是完整桌面镜像。`pi/stream.py` 运行时 HUD 由它自己渲染（长按 Fn 切换）；
> 不跑直播时用 `glxss hud --listen` 作独立 HUD 主进程。两者不要同时占眼镜屏。

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
python -m pip install libusb1         # 可选：720p30 异步多 URB（模块名 usb1）
```

> 不用 venv、直接用系统 Python 时：`sudo apt install -y python3-usb1`。
> `usb1` 缺失或 claim 失败会自动回退同步 16 KiB 读（720p 18–19 fps），
> 不影响推流；`--async-urb 0` 可强制同步。

没有桌面、只投 HUD/测试图案时，最少只需要 `pyusb` + `numpy` + `Pillow`：

```bash
python -m pip install -e ".[image]"
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

Windows 下 `mss` 用 GDI 采集，`frame image`/`--save-png`/HUD 需要 Pillow。

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

# 2) 离线验证 HUD（不需要眼镜；生成 640×400 RGBA PNG）
glxss hud --dry-run --save-png /tmp/hud.png

# 3) 跑 HUD（默认 2 fps；眼镜是主显示）
glxss hud --listen --fps 2 --brightness 180
# 另一个终端：控制主进程
glxss ctl status
glxss ctl bright 200
glxss ctl hud off
glxss ctl test 5

# 4) 读 LCD 状态，控制亮度/屏/模式
glxss status
glxss bright 180
glxss screen on
glxss mode 2

# 5) 离线验证帧格式（不碰硬件）
glxss frame test --dry-run
glxss frame bars --dry-run
glxss frame hud --dry-run
glxss frame solid --color ff8000 --dry-run

# 6) 发帧/投屏（HUD 是默认源）
glxss cast                          # = hud，2 fps
glxss cast --source screen --fps 10
glxss cast --source test --fps 15 --duration 10
glxss frame bars --fps 10 --seconds 5

# 6.1) 相机监看：眼镜 UVC → 眼镜显示（自拍/手持构图）
glxss cast --source uvc --fps 10 --detach --rotate 180 --rec
# 或让常驻 HUD 主进程切换视图（HAT 菜单 VIEW 同效）
glxss hud --listen --detach --rotate 180
glxss ctl view monitor
glxss ctl view hud
glxss ctl rec on

# 7) 摄像头 / 传感器 / 版本
glxss uvc --frames 3 --save first-frame.png
glxss hid --enable-sensors --count 10
glxss version
glxss eeprom --raw

# 8) 固件（默认只分析，不加 --yes 不写）
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
| `frame` | 测试图案/图片/HUD，按 16B 头发送；`--dry-run` 不碰设备 |
| `flash` | 03e7:2150 灌写；默认 dry-run，`--yes` 真写 |
| `cast` | 连续投屏；`--source hud`（默认）/`uvc`（相机监看）/`screen`/`test` |
| `hud` | HUD 主进程；`--dry-run` 离线出 PNG，`--listen` 开控制 socket，`--view monitor` 直接进监看 |
| `ctl` | 通过 socket 控制 HUD/直播进程（status/bright/screen/view/mode/display/zoom/rtsp/button/rec/hud/test/nav/quit） |

`--fit` 支持：

* `fit`：等比缩放，上下/左右留黑边（默认）
* `cover` / `crop`：等比放大填满，居中裁切
* `stretch`：直接拉到 640×400

`--detach` 只在 Linux 有意义；Windows 用 Zadig，macOS 视驱动情况尝试。

---

## 5. HUD 渲染源

`glxss/hud.py` 用 Pillow 画 **640×400 RGBA**，已经从「时钟/罗盘页」改成
**导航 HUD**；数据来自手机（Wi-Fi UDP，见 §5.3）：

```text
┌────────────────────────────────────────────────────────────┐
│ 01:18 LOCAL  HEADING 040°  ● PHONE LINK        2.0 fps    │
├──────────┬───────────────────────────────┬─────────────────┤
│ LIGHT    │             N                 │ ATTITUDE        │
│  321 lux │          ╱  ▲  ╲             │   ────────      │
│ TEMP     │        W │  ARROW  │ E        │  PITCH  -2.2    │
│  36.5°C  │          ╲       ╱             │  ROLL   +0.7    │
│ SENSOR OK│             120 m              │  GYRO  +2 -1 0  │
├──────────┴───────────────────────────────┴─────────────────┤
│                     右转（NEXT ACTION）                    │
└────────────────────────────────────────────────────────────┘
```

* 顶部：当前 heading（手机 GPS，缺省时用眼镜 HID 罗盘）、手机连接状态、fps；
* 中心：大箭头按 `bearing - heading` 旋转（**手机没给 heading 时直接用
  bearing**，方便 `ctl nav` 手动测试）；刻度环随 heading 旋转，N 始终指真北；
* 箭头下方：距离（`120 m` / `1.2 km`）；
* 底部：下一动作文字（`右转` / `直行` / `到达`，手机 UTF-8 label）；
* 左侧：光感 lux + 温度（用户要求保留）、传感器/罗盘状态；
* 右侧：加速度姿态仪（pitch/roll）、陀螺数值（保留）；
* **没有导航数据时**：中心显示「等待手机…」，顶部 `WAITING PHONE`，
  5 s（`--nav-stale`）没收到新包也回到这个状态。

> 中文动作文字需要 CJK 字体；Pi 上建议安装
> `sudo apt install fonts-wqy-zenhei`（约 15 MB）或 `fonts-noto-cjk`。
> 没有 CJK 字体时 HUD 自动退回英文映射（RIGHT/STRAIGHT/ARRIVE），不会显示方框。

### 5.1 使用方式

```bash
# 离线预览（演示数据；不碰硬件）
glxss hud --dry-run --save-png /tmp/hud.png
glxss frame hud --dry-run

# 真机：2 fps 发送；同时监听控制 socket
glxss hud --listen --fps 2 --brightness 180

# 只发送、不监听
glxss hud --fps 2

# 使用演示数据（不读 HID）
glxss hud --demo --fps 2
```

### 5.2 刷新/发送频率

* HID 固件 300 ms 一包；HUD 主进程按 100 ms 轮询（`attach_hid(interval=0.1)`），
  水平仪响应更快；
* HUD 重绘间隔跟 `--fps` 走（`render_interval=1/fps`）；Pi Zero 2 W 上单次
  Pillow 渲染约 65 ms，所以 HUD 实际很难超过 ~8 fps；
* 发送频率由 `--fps` 控制，默认 2 fps；HUD 是静态信息，不需要 25 fps；
* `--fps 0` 表示不限速（只建议调试）。

### 5.3 导航 HUD：手机 Wi-Fi UDP

Pi 端 `glxss/nav.py` 在 `0.0.0.0:9999`（UDP）收手机 JSON，写进线程安全的
`LatestNav` 单槽（和 `LatestFrame` 同思路：旧包直接覆盖，不排队）。`stream`
进程和 `hud` 进程都会启动接收线程，HUD 每次取帧前轮询单槽。

协议（手机每秒一包，UTF-8 JSON）：

```json
{"bearing": 45, "distance": 120, "label": "右转", "heading": 40}
```

| 字段 | 含义 |
|---|---|
| `bearing` | 目标绝对方位（deg，0=北，顺时针） |
| `heading` | 手机当前朝向（deg，可选；省略时箭头角直接用 bearing） |
| `distance` | 距离（米） |
| `label` | 下一动作文字（UTF-8，最长 48 字） |

* 绑定 `0.0.0.0:9999` 不需要 root（>1024）；有防火墙时放行 UDP 9999；
* 手机 1 Hz 发包；**5 s 没收到新包**（`--nav-stale`）HUD 回到「等待手机…」；
* `ctl nav` 注入的帧默认持久（`ttl=0`），直到 `ctl nav-clear` 或手机新包覆盖，
  方便无手机时做桌面测试。

命令行测试（不依赖手机）：

```bash
# Pi 上：注入一帧（bearing=45，距离 120 m，动作“右转”，手机 heading=40）
glxss ctl nav 45 --distance 120 --label 右转 --heading 40
glxss ctl status            # 看 导航 / 导航UDP 两行
glxss ctl nav-clear

# PC 上：直接模拟手机 UDP（把 IP 换成 Pi）
python3 -c 'import json,socket; socket.socket(socket.AF_INET,socket.SOCK_DGRAM).sendto(json.dumps({"bearing":45,"distance":120,"label":"右转","heading":40},ensure_ascii=False).encode(),("192.168.2.128",9999))'

# Pi 上确认端口和收包：
ss -lunp | grep 9999
journalctl -u glxss-stream -f | grep -E '\[nav\]|导航'
```

stream 的启动参数（systemd 里可加）：

```bash
--nav-port 9999        # 0=不监听
--nav-bind 0.0.0.0     # 只想本机测试可改 127.0.0.1
--nav-stale 5.0        # 无数据超时（秒）
--no-nav-udp           # 完全关闭 UDP（ctl nav 仍可用）
```

`glxss hud` 子命令也有同样的 `--nav-port/--nav-bind/--nav-stale/--no-nav-udp`。

Android 端入口：App 首页「导航 HUD（Wi-Fi UDP → Pi）」——
开关、Pi IP/端口（默认 `192.168.2.128:9999`）、bearing/距离滑块、
左转/直行/右转/到达按钮、手动 heading 开关；heading 默认取
`LocationManager` 的 `Location.getBearing()`。

代码里也可以直接操作单槽：

```python
from glxss import hud, nav
state = nav.LatestNav(stale=5.0)
source = hud.HudSource(nav_source=state)      # HUD 自动轮询
state.update(bearing=45, distance=120, label="右转", heading=40)
```

### 5.4 字体与校准

* 英文/数字优先系统 Noto/DejaVu/Arial/Consolas；**中文动作文字**优先
  Noto CJK / 文泉驿 / 微软雅黑 / 苹方。Pi 上装一款即可：
  `sudo apt install fonts-wqy-zenhei`，用 `fc-list :lang=zh | head` 验证。
  没有 CJK 字体时 HUD 自动把常见动作映射成英文，不会显示方框。
* heading 约定 +X=北、+Y=东，`--heading-offset` 可整体旋转校准。
* 磁罗盘硬铁/软铁校准**未实现**，只显示原始值；姿态公式按
  `docs/ch-hid.md §5.3`，未在真机标定。

### 5.5 相机监看（monitor）

戴着眼镜时看相机画面（自拍/手持构图），叠加三分线、水平仪和可选 REC 标记。

两种入口：

```bash
# 1) 独立监看（不跑 HUD 主进程）
glxss cast --source uvc --fps 10 --detach --rotate 180
# 叠加 REC 红点/时间；CPU 紧张时先解 320×240 再放大
glxss cast --source uvc --fps 10 --detach --rotate 180 --rec --uvc-half

# 2) 常驻 HUD 主进程里切换（推荐；LCD HAT 菜单 VIEW 同效）
glxss hud --listen --detach --rotate 180
glxss ctl view monitor      # 或 glxss ctl mode monitor
glxss ctl view hud
glxss ctl rec on
```

也可以启动即进监看：`glxss hud --listen --view monitor --detach --rotate 180`。

* 叠加层：三分线（九宫格）、水平仪（HID 加速度计 pitch/roll，地平线 + 准星）、
  `rec` 时右上角红点 + `REC mm:ss`；
* 映射：默认 `cover`——1280×720 居中裁掉左右各 64 列得到 1152×720（16:10），
  再 4×4 抽样到 288×180、填满 320×200；**居中裁切**，不会再像旧 640×480
  裁切窗口那样偏左。`--monitor-fit stretch|fit` 会重采样，Pi Zero 2 W 上更慢；
* `--camera-fps` 只控制 UVC 协商帧率（默认 15），发送帧率仍由 `--fps` 控制；
  720p 在 Pi Zero 2 W 上实测约 17–19 fps（USB 同步读上限）；
* UVC 读帧 + YV12→RGBA 在后台线程，主循环永远取“最新完成的一帧”发送；
  解码慢只丢旧帧，不拖慢发送；
* **UVC 每次 bulk 读 16384 B（16 KiB），按字节流组帧**：usbfs 单 URB 上限就是
  16384（= libusb `MAX_BULK_BUFFER_LENGTH`）。默认一帧是 `12 B header +
  1,382,400 B YV12 = 1,382,412`（1280×720），由 `UvcStream.read_frame()` 从
  ~85 个 16 KiB 块拼出来；640×480 回退时是 `12 + 460,800 = 460,812`（~29 块）。
  1,382,422 / 460,822 只是原厂 `frameBufferSize`（libusb 内部拆 URB），
  不能拿来当单次读尺寸。
  header 校验用 `bHeaderLength=12 + EOF=1 + ERR=0 + SCR[0..1]==PTS[0..1]`，
  未同步时丢弃到下一个合法 header（流从帧中间开始是常态）；
* 同一 `UsbDeviceConnection` 上 UVC bulk IN 与 LCD bulk OUT 并发；HID/亮度/模式
  等控制请求和 LCD 帧走 `usb.UsbGate` 串行，避免控制请求插进 1 MB 帧中间；
* 切回 HUD 时停流（alt=0）并释放 UVC 接口，下次切监看重开；
* HUD 主进程默认 2 fps，**切到 monitor 时自动把发送帧率提到 max(--fps, 10)**，
  切回 HUD 再恢复，不需要重启进程；
* Linux 必须加 `--detach`（uvcvideo 占着 UVC 接口）；和 `glxss uvc` 一样。

Pi Zero 2 W 实测（合成 YV12 640×480，CPU 1 GHz，performance governor；
720p 的整帧解码约 2.25×，直播的眼镜预览已改走 4×4 抽样，不走这张表）：

| 路径 | 单帧耗时 |
|---|---:|
| YV12→RGBA，全分辨率 cover（默认） | ~44 ms |
| YV12→RGBA，320×240 半分辨率（`--uvc-half`） | ~17 ms |
| YV12→RGBA，stretch/fit（需要重采样） | ~79 ms |
| 旋转 180°（uint32 像素整体反转） | ~1 ms |
| RGBA 打包 + 16 B 帧头 | ~2 ms |
| LCD 整帧 bulk OUT（10 fps 发送） | 实测 30–32 ms/帧（10.2 MB/s） |

> 目标 ≥10 fps：默认 cover 的解码与发送在不同线程，互不阻塞；若设备
> 实测低于 10 fps，优先用 `--uvc-half`（~17 ms/帧），再看 USB 供电/线材。
> 合成 YV12 帧 + 真实 LCD 发送的 Pi Zero 2 W 实测：稳定 10.0 fps，后台解码
> ~20 fps。真机 `glxss cast --source uvc --fps 10 --duration 10 --detach` 的
> LCD 发送侧同样稳定 **10.0 fps（10.2 MB/s）**；如果 UVC probe/commit 成功但
> 读不到帧，是固件媒体流水线停摆，见 `docs/device-recovery.md`（需重灌固件，
> 不在本工具范围内）。

真机 UVC（2026-11-22，`2e09:0030` + 16 KiB 分块组帧）。**默认 1280×720
（bFrameIndex=3，全画幅）**；640×480（frame 4）保留为回退：

| 项 | 实测（720p，默认） |
|---|---|
| `pi/stream.py --check --fps 30`（异步 16 URB） | **1280×720，30.7 fps / 42.38 MB/s**（预热 2 帧后） |
| 同步回退 `--async-urb 0` | 18.6–19.0 fps / 25.7–26.2 MB/s |
| 稳态读帧（异步） | **29.7–30.7 fps**，0–5 帧丢弃/45 s；`/tmp/full720_steady.png` 1280×720 |
| RTSP `--fps 30 --async-urb 16` | 采集 30.0 fps、编码 30.0 fps、0 重启；ffprobe `h264 1280x720 30/1`；CPU python ~128% + ffmpeg ~31% |
| RTSP + 投屏（`--fps 30 --async-urb 16 --mirror-fps 10`） | 采集 17.5–18.4 fps、编码同采集、眼镜 **10.0 fps**（渲染 22–28 ms + 发送 **29–39 ms**）；CPU python ~108% + ffmpeg ~23% |

640×480 回退档（历史，2026-11-22 早些时候）：

| 项 | 实测 |
|---|---|
| `--check --fps 30` | **29.9 fps / 13.80 MB/s** |
| 稳态读帧 | **30.0 fps**，33.3 ms/帧，0 丢弃字节 |
| RTSP `--fps 20` | 采集 30.0 fps、发送 20.0 fps、0 重启；ffprobe `h264 640x480 20/1` |
| RTSP `--fps 25` | 采集 30.0 fps、编码 25.1 fps，CPU（python+ffmpeg）**41.0%** |

### 5.6 直播 + 眼镜预览：一次采集、多消费者分发

`pi/stream.py --mirror-fps N` 不再是「再读一次 UVC、再解一次码」，而是：

```text
UVC 读线程（异步 16 URB / 16 KiB 分块组帧；单路 29.7–30.7 fps）
  → LatestFrame.publish()                  # 一个槽，旧的直接丢，不排队
       ├─ EncoderSink 线程 → ffmpeg stdin（YV12→yuv420p 只换写入顺序，零拷贝）
       └─ LcdSink 线程（同一 LCD 链路两种模式）
            preview：按 zoom 居中裁切（1.0x 整幅 / 1.5x 853×480 / 2.0x 640×360）
                     → 4×4～2×2 抽样 → YUV→RGB(C) → 640×400
                     → 三分线/水平仪 → 暂停 UVC → 1 MB bulk OUT → 恢复
            hud    ：HudSource.grab_rgba() → 640×400 导航 HUD
                     （箭头/距离/动作 + heading/连接/fps + lux/温度 + 姿态）
                     → 同一个 1 MB packet → 2 fps bulk OUT

手机 Wi-Fi UDP JSON :9999 → nav.NavListener → nav.LatestNav（单槽）
  → HudSource._poll_nav()（变化才重绘）→ 上面的 hud 分支
```

* 读线程只「读 + 发布」，**任何消费者慢都不会拖慢采集**；
* 异步 reader（`uvc_async.AsyncBulkReader`）用独立 libusb handle claim VS，
  保持 16 个 16 KiB URB 在途；`usb1` 缺失/claim 失败自动回退同步读；
* LCD 线程按 `--mirror-fps` 睡到截止时间再取当时最新帧，叠加三分线 + 水平仪；
* **Fn 长按 ≥0.9 s = 眼镜 camera preview ↔ HUD**：只调用 `LcdSink.set_mode()`，
  同一个 1 MB packet、**不 release/claim LCD 接口**。旧实现切 HUD 时只停预览并
  释放接口，等外部 `glxss hud` 进程接管；Pi 上没有该进程，面板停在最后一帧
  （用户实测的“冻结”）。现在 HUD 由 stream 进程内渲染（`--hud-fps`，默认
  2.0），实测单帧渲染 ~110–120 ms + 发送 ~30–40 ms；
* **Fn 短按（preview 下）= 循环数字变焦 1.0x → 1.5x → 2.0x**：
  `LcdRenderer` 先在 720p 源图上**居中裁切**（1.5x → 853×480，2.0x →
  640×360），再按 `--mirror-fit` 映射到 640×400；三分线/水平仪在裁切后绘制，
  始终正立。抽样步长跟着裁切窗口重算（720p 1.0x 是 4×4，1.5x/2.0x 变 2×2），
  1.5x 单帧渲染比 1.0x 多约 3 ms。**HUD 下短按忽略**；RTSP 开关改由
  `glxss ctl rtsp on|off`（不再绑键），变焦也有
  `glxss ctl zoom 1.0|1.5|2.0|cycle`，`ctl status` 显示当前档位与源裁切窗口；
* **长按判定只看时间**（0.9 s），不再要求“连续 4 个按下包”（100 ms 轮询下，
  1 s 按压只有 ~10 包，包数条件会造成边缘误判）；松开时若实际按住 ≥0.9 s 也
  补发长按。动作交给独立 worker，**UVC 读线程只取事件、不执行切换**；每次
  `keyStatus` 变化打 `[hid] key 0x71 down/up（按住 x.xx s）`，动作打
  `[stream] Fn 按键：…`，`glxss ctl status` 增加 `view/lcd_mode/zoom/level/hid`；
* **水平仪方向**：`--mirror-rotate 180` 下面板/叠加层坐标整体翻 180°，preview
  默认把 pitch/roll 都取反（`--level-invert-x` = roll 左右倾，`--level-invert-y`
  = pitch 上下）。HUD 的整张图翻转 180° 后由面板再翻一次，用户看到的是未旋转
  HUD 图，所以 HUD 小地平线只补偿 roll，pitch 保持真实方向。验收：头左倾 →
  地平线右倾；头低下 → 地平线上移。`--mirror-rotate 0` 默认不取反；
* 眼镜这一路的额外成本：preview 渲染 22–28 ms 墙钟 + 1 MB bulk OUT 29–39 ms
  （发送期间 UVC 暂停，10 fps 时总暂停约 0.3 s/s）；HUD 模式 2 fps，CPU 占用
  更低但单帧 Pillow 渲染 ~110 ms（约 0.22 核）；
* **不按键的等价控制**（服务里 `GLXSS_SOCK=/run/glxss/glxssctl.sock`）：
  `glxss ctl display preview|hud|toggle`、`glxss ctl zoom 1.5`（或 `cycle`）、
  `glxss ctl rtsp off|on`、`glxss ctl button click|long`（把事件注入同一队列，
  无硬件也能走完整路径）。变焦重启回 `--zoom`（默认 1.0），不落盘。

Pi Zero 2 W 真机（同一台设备，20 s 以上稳态）：

| 指标 | 720p 同步（旧） | **720p30 异步，单路** | **720p30 异步 + 眼镜 10 fps** |
|---|---|---|---|
| 采集 / 编码 / 眼镜 fps | 13.0 / 13.2 / 10.0（同步双路） | **29.7–30.7 / 30.0 / —** | **17.5–18.4 / 17.5–18.4 / 10.0** |
| 1 MB 发送窗口 | 40–56 ms | — | **32–39 ms**（暂停 UVC；无暂停 37–43 ms） |
| CPU（python + ffmpeg） | ~79% | ~128% + ~31% | ~108% + ~23% |

> 720p30 的瓶颈已从 USB（单 URB）转为 **CPython GIL**：双路 10 fps 时 4 个
> A53 核没跑满，但读线程/异步拷贝/编码/LCD 四个线程抢 GIL，所以采集 17–18
> 而不是 30。单路 30 fps 已达成；双路想要更顺的相机画面用 `--mirror-fps 5`
> （采集 23.7 fps）。

> 撕裂：主机侧已确认整帧一次 bulk OUT、`UsbGate` 不让控制请求插帧、LcdSink
> 发送完才取下一帧；剩余是设备端把 payload 直接当帧缓冲、无 vsync 的固有限制。
> 发送时暂停 UVC 把窗口从 40–56 ms 压到 32–39 ms（无暂停 37–43 ms），
> 已接近原厂 App 的 ~30 ms。
> 详见 [`docs/ch-uvc.md` §7.6](../docs/ch-uvc.md#76-眼镜预览撕裂排查与修法2026-11-22-晚)。

> 为什么不用 `-vf shuffleplanes`、为什么半分辨率 + PIL `NEAREST`、怎么把渲染
> 压到 ~10 ms CPU，见 [`pi/STREAM.md` §8.5](pi/STREAM.md#85-一次采集多消费者分发2026-11-22-优化)。

---

## 6. 代码里复用

```python
from glxss import capture, hud, lcd, usb

source = hud.HudSource(brightness=180)        # 或 capture.TestPatternSource()
source.set_connected(True)
source.set_firmware("G25_MainFW_1.9.1")
rgba = capture.map_to_rgba(source.grab(), mode="stretch")
packet = lcd.build_frame(capture.rgba_bytes(rgba))

dev = usb.find_normal()
with usb.DeviceSession(dev, detach=False) as session:
    intf, ep = usb.pick_bulk_out(dev)
    session.claim_interface(intf, label="LCD")
    link = lcd.LcdLink(dev, ep.bEndpointAddress)
    link.initialize()
    link.send_packet(packet)
```

`HudSource` 实现 `capture.ImageSource`，可直接注入 `CastApp`；`attach_hid(dev)`
会在后台按 300 ms 轮询 HID 并更新图层。`glxss.app.CastApp` 是带 fps 统计的
主循环，可注入任意 `sender`。

`capture.ImageSource` 是采集抽象；`TestPatternSource` 无依赖桌面，`ScreenSource`
使用 `mss`（Linux 上经 X11/XShm）。当前没有实现直接读 `/dev/fb0`/DRM 的源，
需要无 X11 的 framebuffer 采集时可以实现自己的 `ImageSource` 注入 `CastApp`。

---

## 7. Raspberry Pi + LCD HAT

完整从零安装（刷 Pi OS Lite 64-bit、SPI、`config.txt`、接线、udev、systemd、
offset 微调、供电/排障）见 **[`pi/SETUP.md`](pi/SETUP.md)**。

架构：

```text
glxss hud --listen  ──USB──►  眼镜 640×400 HUD / 相机监看
       ▲   UVC bulk IN ◄──┐  （monitor 模式：眼镜相机 → 眼镜显示）
       │                  │
       │ /run/glxss/glxssctl.sock（JSON）
       │
pi/lcdhat.py  ──SPI/GPIO──►  ST7789 240×240 + 摇杆/按键
```

最短流程（仓库 `/opt/glxss`，venv `/opt/glxss/venv`）：

```bash
cd /opt/glxss/linux
/opt/glxss/venv/bin/python -m glxss hud --listen --detach --fps 2
# 另一个终端或 systemd：HAT 控制器
/opt/glxss/venv/bin/python pi/lcdhat.py --socket /run/glxss/glxssctl.sock
```

HAT 默认会在 socket 不存在时自动拉起 `glxss hud --listen` 子进程
（`--no-spawn` 可禁用）。状态页显示眼镜连接、fps、亮度、VIEW（HUD/MONITOR）、
HUD 状态、lux、显示模式、时间；菜单 7 项：亮度、**视图（HUD/MONITOR）**、显示
模式、开关屏、HUD 开关、发测试帧、退出：

| 按键 | 状态页 | 菜单页 |
|---|---|---|
| UP/DOWN | — | 选择菜单项 |
| LEFT/RIGHT | — | 调值/切换 |
| PRESS | 进入菜单 | 确认 |
| KEY1 | HUD 开关 | — |
| KEY2 | 发测试帧 | — |
| KEY3 | 退出 | 返回状态页 |

眼镜上的 **Fn 键单击**也会在 HUD / MONITOR 之间切换（HID 报告里的按键边沿）。
`glxss-cast.service` 模板已带 `--detach`，monitor 模式才能打开 UVC；画面上下
颠倒时给 ExecStart 加 `--rotate 180`。

面板 offset 默认 `x=0, y=80`；不同批次试 `0/80`、`80/0`、`--rotation 1`。
颜色反相时改 `pi/lcdhat.py` 的 `_INIT`（`0x21` INVON ↔ `0x20` INVOFF）。

服务：

```bash
sudo cp pi/service/glxss-cast.service /etc/systemd/system/
sudo cp pi/service/glxss-lcdhat.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now glxss-cast glxss-lcdhat
journalctl -u glxss-cast -f
```

> Raspberry Pi OS Lite 没有 X11：HUD 是默认源，不需要桌面；只有
> `--source screen` 才需要桌面/XWayland。

---

## 8. PyInstaller 打包

`packaging/` 提供三端脚本：

```bash
# Linux/macOS
packaging/build.sh              # onefile
packaging/build.sh --onedir     # 启动更快

# Windows
powershell -ExecutionPolicy Bypass -File packaging\build.ps1
powershell -ExecutionPolicy Bypass -File packaging\build.ps1 -OneDir
```

要点：

* PyInstaller **不能跨平台**：Windows 包在 Windows 上打，Linux/macOS 同理；
* Windows 需要 `libusb-1.0.dll`，可用 `-LibusbDll` 打进包；否则放 System32/PATH；
* macOS 用 `brew install libusb`，必要时设 `DYLD_LIBRARY_PATH`；
* `--onefile` 体积约 30–90 MB、每次启动解压 1–3 秒；`--onedir` 启动快但文件多；
* 受限 Windows 环境 onefile 可能无法创建临时目录，改用 `-OneDir`；

详情见 [`packaging/README.md`](packaging/README.md)。

---

## 9. 平台差异与排障

| 现象 | 处理 |
|---|---|
| `No backend available` / 找不到 libusb | Linux 装 `libusb-1.0-0`；Windows 放 `libusb-1.0.dll`；macOS `brew install libusb` |
| `未找到 2e09:0030` | 设备可能还在 `03e7:2150`（需先灌固件）；或线材/供电；先 `glxss list` |
| `权限不足` / `Access denied` | Linux 装 udev 规则；Windows 用 Zadig 绑 WinUSB；macOS 检查 libusb |
| `接口忙` / claim 失败 | 退出厂商 App；Linux 加 `--detach`；同一进程不要重复 claim |
| 发帧成功但屏不亮 | 先 `glxss screen on` + `glxss mode 2`（`frame/cast/hud` 默认会做） |
| UVC 超时 / 0 帧 | 关掉其他摄像头程序；Linux `--detach` 摘掉 uvcvideo；固件假死需重灌 |
| `ctl view monitor` 后仍是 HUD | 看 `ctl status` 的 `UVC 错误`；多数是没加 `--detach`（uvcvideo 占用 VS 接口） |
| monitor 帧率低/延迟大 | 加 `--uvc-half`；确认 USB 供电足够（Pi Zero 2 W 建议带供电 Hub）；`--fps 10` 不要设太高 |
| 眼镜 Fn 键不能切 HUD/monitor | HID 不可用（`ctl status` 有 HID 错误）；先 `glxss hid --enable-sensors --count 5` 验证 |
| HUD 显示 NO MAG / NO IMU | 传感器未使能/全 0；`glxss hid --enable-sensors --count 5` 验证；无罗盘时 HUD 显示 `--` |
| `glxss ctl` 连不上 | 两个进程 `GLXSS_SOCK`/`--socket` 必须一致；先 `glxss hud --listen` |
| 屏幕采集全黑 | Linux Wayland 用 XWayland，或改 `--source hud`/`test` |
| LCD HAT 画面偏移 | 调 `pi/lcdhat.py --x-offset/--y-offset`（默认 `0/80`），或换 `--rotation` |
| 刷写中设备断开 | 正常：写完会重枚举为 `2e09:0030`；不要拔线，等 8 s |

### 固件与安全

* `flash` 默认 **dry-run**：只读文件、算 SHA256、打印计划，不打开设备。
* `--yes` 才会写；写之前会检查 `MA2x` 和 `G25_MainFW_` 版本串，除非 `--force`。
* 只解密镜像最后 `min(size, 512 KiB)`，AES-128-ECB，key `b5Q8JBFRYYIRawl3`。
* 仓库不提供固件文件；请使用你合法持有的原厂镜像。
* 本工具不实现写 EEPROM（`0x21/0x06`）。

---

## 10. 自检

无眼镜、无桌面也能跑：

```bash
cd linux
python -m compileall -q glxss pi tests tools packaging
python -m unittest discover -s tests -v
python -m glxss --help
for c in list status bright screen mode roll eeprom version hid uvc frame flash cast hud ctl; do
  python -m glxss "$c" --help >/dev/null || exit 1
done
python -m glxss hud --dry-run --save-png /tmp/glxss-hud.png
python -m glxss frame hud --dry-run
python -m glxss frame test --dry-run
python pi/lcdhat.py --preview /tmp/glxss-hat
python tools/make_test_png.py --out /tmp/glxss-test-pattern.png
```

Windows PowerShell 对应：

```powershell
python -m compileall -q glxss pi tests tools packaging
python -m unittest discover -s tests -v
python -m glxss hud --dry-run --save-png "$env:TEMP\glxss-hud.png"
python pi/lcdhat.py --preview "$env:TEMP\glxss-hat"
```

> `unittest` 里控制 socket 的用例在没有 `AF_UNIX` 的平台会 skip；
> HUD 用例需要 Pillow + numpy，缺失时也会 skip。

---

## 11. 协议出处

* 显示：`docs/ch-display.md` §2–§3
* UVC：`docs/ch-uvc.md` §1–§5
* HID：`docs/ch-hid.md` §4–§6
* 固件：`docs/ch-firmware.md` §3–§4
* 引导：`docs/firmware-boot-protocol.md`

本项目禁止商业使用，详见根目录 [LICENSE](../LICENSE)。
