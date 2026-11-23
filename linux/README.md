# GLXSS3 / G25 — Linux / 树莓派功能与使用说明（`linux` 分支）

这个目录把 GLXSS3（G25）眼镜当成三样东西用：一块 **640×400 的 USB 显示器**、
一个 **UVC 摄像头**、一个 **HID 传感器盒**。代码是 Python 3.9+，对外提供
`glxss` CLI；主战场是 Raspberry Pi Zero 2 W，显示部分在 Linux x86、Windows、
macOS 上也能跑。Android 版在仓库根目录的 `app/`，两边互不影响。

它已经在真机上跑通的东西：

```text
HUD       Pillow 640×400 RGBA ──USB bulk──> 眼镜
相机预览   眼镜 UVC 1280×720 YV12 ──抽样/叠加──> 640×400 ──> 眼镜
推流      眼镜 UVC ──H.264 硬编──> mediamtx :8554 ──RTSP──> OBS
导航      手机 ──UDP :9999 JSON──> Pi ──> HUD 箭头/距离/指令
控制      SSH / 脚本 ──Unix socket──> Pi 上的主进程
面板      Pi ──SPI/GPIO──> ST7789 240×240 状态页 + 菜单
```

> 协议依据：`docs/ch-display.md`、`docs/ch-uvc.md`、`docs/ch-hid.md`、
> `docs/ch-firmware.md`、`docs/firmware-boot-protocol.md`。
> 仓库不包含原厂固件、APK 或任何第三方二进制。

---

## 1. 概述

| 模块 | 入口 | 现状 |
|---|---|---|
| 显示 / 投屏 | `glxss hud`、`glxss cast`、`glxss frame` | 640×400 RGBA，16 B 帧头，2 fps HUD 默认 |
| 相机预览 | `pi/stream.py --mirror-fps`、`glxss cast --source uvc` | 720p → 640×400，三分线 + 水平仪，10 fps |
| 数字变焦 | Fn 短按、`glxss ctl zoom` | 1.0 / 1.5 / 2.0 / 3.0x，源画面中心裁切 |
| 画面翻转 | `--rotate 180`、`--mirror-rotate 180` | 面板/相机装反时用 |
| 直播推流 | `pi/stream.py` | UVC → `h264_v4l2m2m` → mediamtx RTSP → OBS |
| Fn 按键 | `pi/stream.py` 内 | 长按切 HUD/视频，短按变焦 |
| HID 传感器 | `glxss hid`、stream 内 100 ms 轮询 | 按键 / lux / 罗盘 / 加速度 / 陀螺 / 温度 |
| LCD HAT | `pi/lcdhat.py` | 240×240 状态页 + 7 项菜单，8 个按键 |
| 固件 | udev 自动、`glxss flash` | 03e7:2150 → 2e09:0030 |
| 导航 HUD | `glxss/nav.py`、UDP :9999 | 箭头、距离、指令；5 s 超时 |
| 控制接口 | `glxss ctl` | status / display / zoom / rtsp / nav / button |
| 打包 | `packaging/` | PyInstaller 三端脚本 |

明确没做的：音频（UAC）接进 RTSP、录像、NPU、写 EEPROM、MJPEG 以外的低延迟协议。
眼镜的 USB 声卡系统能识别，但本项目不碰；OBS 里要声音请单独加麦。

### 1.1 目录结构

```text
linux/
├─ glxss/
│  ├─ usb.py         # pyusb 设备发现 / claim / control / bulk
│  ├─ lcd.py         # 16 B 头 + 640×400 RGBA + 亮度/屏/模式
│  ├─ uvc.py         # probe/commit、YV12 组帧、YV12→RGB
│  ├─ uvc_async.py   # libusb1 异步 16×16 KiB URB（720p30）
│  ├─ hid.py         # 28 B 报告、Fn 单击/长按、水平仪
│  ├─ pipeline.py    # 一次采集多消费者、720p→640×400、变焦、叠加
│  ├─ stream.py      # 推流/预览/HUD/控制 socket/重连
│  ├─ hud.py         # 640×400 导航 HUD 渲染
│  ├─ nav.py         # 手机 UDP JSON → 线程安全单槽
│  ├─ capture.py     # 测试图案 / 屏幕采集 / 图片源
│  ├─ control.py     # JSON over Unix socket
│  ├─ firmware.py    # AES-128-ECB + 03e7:2150 灌写
│  └─ cli.py         # glxss 子命令
├─ pi/
│  ├─ stream.py      # 推流入口
│  ├─ lcdhat.py      # 1.3" ST7789 状态页/菜单
│  ├─ lcdhat_selftest.py
│  ├─ service/       # systemd 模板
│  ├─ SETUP.md       # 从零安装（刷机、SPI、接线、udev、排障）
│  └─ STREAM.md      # 推流/延迟/性能的详细记录
├─ packaging/        # PyInstaller
├─ udev/99-glxss.rules
├─ tests/
└─ pyproject.toml
```

---

## 2. 硬件要求

| 项 | 要求 |
|---|---|
| 眼镜 | GLXSS3 / G25。正常工作模式 `2e09:0030`，ROM 引导 `03e7:2150` |
| 主机 | Raspberry Pi Zero 2 W（推荐，4×A53 1 GHz / 416 MB）；Pi 3/4/5 或 Linux x86_64 也可 |
| 面板（可选） | Waveshare 1.3inch LCD HAT，ST7789 240×240，SPI0 CE0，5 向摇杆 + 3 键 |
| 供电 | 5 V / 2.5 A 起；眼镜用带供电 USB Hub 更稳 |
| 存储 | 8 GB+ SD 卡（Pi OS Lite 64-bit 足够） |
| 网络 | Pi 与 OBS 电脑同一局域网；Wi-Fi 或 USB 网卡 |
| 系统依赖 | `libusb-1.0`、Python 3.9+、`ffmpeg`（Pi 上要带 `h264_v4l2m2m`） |
| OBS | 任意近期版本，「媒体源」拉 RTSP |

几个硬限制先写在这里：

* Pi Zero 2 W 只有一个 USB OTG 口，眼镜插上后就没有空口了；HAT 走 SPI/GPIO，
  不占 USB，但同时接网卡/U 盘要加 Hub。
* 720p30 需要约 **42 MB/s**。同步单 URB 读只有 **26 MB/s** 左右，必须走
  `libusb1` 异步 16 URB；没有 `usb1` 会回退同步，720p 只有 18–19 fps。
* 眼镜每个上电周期都要主机灌一次固件，没有固件时它就以 `03e7:2150` 停着。
* 长时间硬编会让 Pi 到 60–70 °C，建议散热片；`vcgencmd get_throttled` 必须是 `0x0`。

Windows 只建议做显示验证：Zadig 把厂商 LCD 接口绑到 WinUSB，UVC/HID 在
Windows 上没做完整验证。macOS 的 UVC/IOHID 可能占着接口，必要时 `--detach`。
完整 Pi 从零安装看 [`pi/SETUP.md`](pi/SETUP.md)。

---

## 3. 安装

### 3.1 树莓派（推荐路径）

```bash
sudo apt update
sudo apt install -y python3-venv python3-pip libusb-1.0-0 ffmpeg \
  python3-numpy python3-pil python3-spidev python3-rpi.gpio \
  fonts-dejavu-core fonts-wqy-zenhei
```

`python3-rpi.gpio` 和 `python3-lgpio` 二选一，Bookworm 上两个都能用。
`fonts-wqy-zenhei` 是给 HUD 的中文指令用的，不装会退回英文。

异步 URB 需要 `usb1`。装在系统里（venv 用 `--system-site-packages`）或装进 venv：

```bash
sudo apt install -y python3-usb1          # 系统包
# 或
python3 -m pip install libusb1            # venv 内，模块名仍是 usb1
```

代码和 venv：

```bash
sudo mkdir -p /opt/glxss && sudo chown "$USER":"$USER" /opt/glxss
git clone -b linux <repo-url> /opt/glxss
python3 -m venv --system-site-packages /opt/glxss/venv
/opt/glxss/venv/bin/pip install -U pip
/opt/glxss/venv/bin/pip install -e /opt/glxss/linux
```

udev 权限（不要每次 sudo）：

```bash
sudo cp /opt/glxss/linux/udev/99-glxss.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules && sudo udevadm trigger
sudo usermod -aG spi,gpio,video,plugdev "$USER"
```

重新登录或重启后生效。HAT 还要开 SPI：

```bash
sudo raspi-config nonint do_spi 0
# /boot/firmware/config.txt 里确认：
#   dtparam=spi=on
#   gpio=6,19,5,26,13,21,20,16=pu
```

接线、面板 offset（常见 `0/80` 或 `80/0`）、反相、`--rotation` 见
[`pi/SETUP.md`](pi/SETUP.md) 和 [`pi/README.md`](pi/README.md)。

推流要的 mediamtx（只开 RTSP + 本地 API，适合 416 MB 的 Zero 2 W）：

```bash
cd /tmp
curl -fL -o mediamtx.tar.gz \
  https://github.com/bluenviron/mediamtx/releases/download/v1.21.4/mediamtx_v1.21.4_linux_arm64.tar.gz
sudo tar xzf mediamtx.tar.gz -C /usr/local/bin mediamtx
sudo mkdir -p /etc/mediamtx
sudo cp /opt/glxss/linux/pi/mediamtx.yml /etc/mediamtx/mediamtx.yml
sudo cp /opt/glxss/linux/pi/service/mediamtx.service /etc/systemd/system/
```

版本号/架构按 release 页换；完整安装和拓扑说明见 [`pi/STREAM.md`](pi/STREAM.md) §3。

### 3.2 其他 Linux / Windows / macOS

```bash
cd linux
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -U pip
python -m pip install -e ".[dev]"
```

* **Linux x86_64**：装 `libusb-1.0-0`、`ffmpeg`，其余同上。
* **Windows 10/11**：需要 `libusb-1.0.dll`（放 System32 或 PATH），再用
  [Zadig](https://zadig.akeo.ie/) 把眼镜的目标接口绑成 WinUSB。GLXSS 是复合设备，
  别把整个设备绑成 WinUSB，否则摄像头/HID/音频一起失效。只投屏时优先绑厂商
  LCD 接口（接口 3，EP `0x01`）。
* **macOS**：`brew install libusb`。系统 UVC/IOHID 可能占着相机/HID，LCD 厂商
  接口通常能直接访问；接口忙时先试 `--detach`。

装完确认：

```bash
glxss --help
glxss list
```

`glxss` 不在 PATH 就用 `python -m glxss ...`，或直接用 venv 里的解释器。

---

## 4. 快速开始

先不接眼镜也能跑离线命令（生成 HUD/HAT 的 PNG、算帧头）。

```bash
cd /opt/glxss/linux
export GLXSS_SOCK=/run/glxss/glxssctl.sock   # systemd 里就是这个；手动跑也可换 /tmp/glxssctl.sock
PY=/opt/glxss/venv/bin/python
```

### 4.1 枚举和自检

```bash
$PY -m glxss list
$PY pi/stream.py --check
```

`--check` 会打开 UVC、probe/commit、预热 2 帧，然后打印实测值。正常输出类似：

```text
[check] 实测 1280x720，30.7 fps，42.38 MB/s，frame_size=1382400
```

如果这里只有 18–19 fps / 26 MB/s，说明 `usb1` 没装或异步 reader 没起来；
先确认 `python3-usb1` / `libusb1`，再看线材和供电。

### 4.2 推流到 OBS

```bash
$PY pi/stream.py --fps 25 --bitrate 2M --async-urb 16
```

PC 上 OBS：**来源 → 媒体源 → 取消“本地文件” → 输入**：

```text
rtsp://<Pi-IP>:8554/glxss
```

「网络缓冲」调到 1 MB；不要用「VLC 视频源」拉 RTSP，缓存更大。

### 4.3 同时投眼镜预览

```bash
# 推流 + 眼镜 10 fps 相机预览，画面上下颠倒时加 --mirror-rotate 180
$PY pi/stream.py --fps 25 --bitrate 2M --async-urb 16 \
  --mirror-fps 10 --mirror-rotate 180
```

启动后长按眼镜 Fn ≥0.9 s 在 **相机预览 ↔ HUD** 之间切；短按在预览下循环变焦。
也可以不按键：

```bash
$PY -m glxss ctl display preview
$PY -m glxss ctl display hud
$PY -m glxss ctl zoom cycle
$PY -m glxss ctl rtsp off
```

### 4.4 只跑 HUD / HAT

```bash
# 只投 HUD（不推流）
$PY -m glxss hud --listen --detach --fps 2 --brightness 180

# 另一个终端：HAT 控制器（HUD 没跑时它会自动拉起一个）
$PY pi/lcdhat.py --socket "$GLXSS_SOCK"
```

离线预览：

```bash
$PY -m glxss hud --dry-run --save-png /tmp/hud.png
$PY pi/lcdhat.py --preview /tmp/hat
```

### 4.5 开机自启

```bash
sudo cp pi/service/glxss-stream.service /etc/systemd/system/   # 推流 + 眼镜预览
sudo cp pi/service/glxss-lcdhat.service /etc/systemd/system/   # HAT
sudo systemctl daemon-reload
sudo systemctl enable --now glxss-stream glxss-lcdhat
journalctl -u glxss-stream -f
```

模板里的 `User=`、`WorkingDirectory=`、`ExecStart=` 是按作者机器写的，换成你的
用户名和路径。**`glxss-stream.service` 和 `glxss-cast.service` 不要同时开**：
两个都会占眼镜屏，二选一。

---

## 5. 功能详解

### 5.1 显示与投屏

眼镜的显示协议是固定的，不能改：

```text
16 B 头：01 00 00 00 | totalLen(u32 LE = 1,024,016) | 8 B 0
payload：640×400×4 = 1,024,000 B 裸 RGBA8888
整帧    ：1,024,016 B，一次 bulk OUT（EP 0x01 / 接口 3）
```

`frame`、`cast`、`hud` 默认会先 `screen on` + `mode 2`，所以一般不用手动开屏。

**怎么用**

```bash
# 读状态、亮度、开关屏、显示模式
$PY -m glxss status
$PY -m glxss bright 180          # 实测有效范围 100..254
$PY -m glxss screen on
$PY -m glxss mode 2              # 1=待机/logo，2=投屏

# 测试图案 / 单帧 / 连续投屏
$PY -m glxss frame test --fps 10 --seconds 5
$PY -m glxss frame bars --fps 10 --seconds 5
$PY -m glxss cast --source test --fps 10 --duration 5
$PY -m glxss cast --source hud --fps 2

# 屏幕镜像（需要 X11；Pi OS Lite 没有桌面）
$PY -m glxss cast --source screen --fps 10 --fit cover
```

`--fit` 有 `fit`（等比留黑边，默认）、`cover` / `crop`（等比填满、居中裁切）、
`stretch`（直接拉满）。`--rotate 180` 在面板装反时用。

**实测**

* 整帧 1,024,016 B，一次 bulk OUT；10 fps 时约 10.2 MB/s。
* 1 MB 发送本身约 30–40 ms；HUD 2 fps 时 CPU 占用很低。
* 亮度 100..254 有效；低于 100 基本看不见。

**坑**

* 发帧成功但屏不亮：先确认 `glxss screen on` 和 `glxss mode 2`。有些固件状态
  会停在待机。
* 1 MB 帧发送期间不要插控制请求，否则帧会被截断/花屏。代码里用 `UsbGate`
  串行化，但同一个设备不要开第二个进程。
* 16 B 头里的 `totalLen` 和 payload 大小不能改；`--dry-run` 只算头和 SHA256，
  不碰设备。
* 屏幕采集走 `mss`/X11，Pi OS Lite 上没有桌面，用 `hud` 或 `test` 源。

### 5.2 HUD 模式

HUD 是 Pillow 画的 640×400 RGBA，默认 **2 fps**。布局：

| 区域 | 内容 |
|---|---|
| 顶栏左 | 本地时间、剩余时间/剩余距离（手机给了才显示） |
| 顶栏中 | 手机连接状态 `PHONE LINK` / `WAITING PHONE`，眼镜在线状态 |
| 顶栏右 | fps、HUD 开关、亮度 |
| 中心 | 按机动类型画的大箭头（左转 / 直行 / 右转 / 到达等） |
| 箭头下 | 距离，`320 m` / `1.2 km` |
| 底栏 | 下一动作文字，例如「右转进入 人民路」 |
| 左侧 | 光照 lux、温度、IMU/HUD 状态 |
| 右侧 | 姿态仪：pitch/roll 地平线 + 数值 + 陀螺 |

没有导航数据时中心与底栏显示「等待手机导航…」（旧文案「等待手机…」），顶栏
显示 `WAITING PHONE`；`--nav-stale` 默认 5 s，超时也回到这个状态。

**手机端（Android，0.1.7 起）**

App 首页「导航 HUD（Google 地图 → Pi）」一栏：开启「通知使用权」→ 填 Pi 的 IP
（默认 `192.168.2.128:9999`）→ 开始导航。`NavListenerService` 解析 Google 地图
的导航通知（标题=指令、正文=距离、副标题=`12 分钟 · 3.2 公里`、大图标=机动类型，
图标只作兜底不依赖资源 id），每秒发一包：

```json
{"bearing": null, "heading": null, "distance": 500,
 "label": "右转进入 人民路", "maneuver": "right",
 "eta": 120, "remain": 2400, "source": "maps"}
```

`maneuver` ∈ `left/slight-left/right/slight-right/straight/roundabout/uturn/arrive`；
没有该字段时 Pi 按 `label` 关键词兜底。页面里还有「模拟一条通知」按钮，不开车
也能验证解析→JSON→UDP 全链路。协议/HUD 版式/真机实测见
[`pi/STREAM.md` §6.5](pi/STREAM.md)。

**怎么用**

```bash
# 真机：2 fps，开控制 socket
$PY -m glxss hud --listen --detach --fps 2 --brightness 180

# 只发不监听
$PY -m glxss hud --fps 2

# 演示数据，不读 HID
$PY -m glxss hud --demo --fps 2

# 离线出图
$PY -m glxss hud --dry-run --save-png /tmp/hud.png
```

**实测**

* Pi Zero 2 W 上单帧 Pillow 渲染约 **110–120 ms**，发送约 30–40 ms，所以
  HUD 默认 2 fps；设到 5 fps 以上只是白烧 CPU/USB，信息不会更快。
* HID 100 ms 轮询，水平仪响应比 2 fps 重绘快；导航数据手机端 1 Hz 就够。

**坑**

* 眼镜屏同时只能有一个进程写。跑 `pi/stream.py --mirror-fps` 时 HUD 由 stream
  自己渲染（长按 Fn 切），不要再开 `glxss hud`。
* `glxss hud --listen` 默认不开 UVC；要切相机预览必须加 `--detach`，否则
  uvcvideo 占着 VS 接口。
* 中文指令需要 CJK 字体（`fonts-wqy-zenhei`）。没有字体时 HUD 自动退回英文
  （RIGHT/STRAIGHT/ARRIVE），不会显示一排方框。
* **罗盘 / heading（计划移除 → 0.1.7 已移除）**：眼镜没有磁力计。HUD 不再
  显示 heading 数值和罗盘刻度环（`NO MAG` 卡片整个删除），中心箭头按
  `maneuver`（左转/直行/右转/环岛/掉头/到达）或 `label` 关键词画，不再依赖
  `bearing - heading`。`bearing` / `heading` 字段仍接收（可空），留给调试和旧
  手机包。手动 `ctl nav --label 右转 --distance 500` 不需要 bearing。

### 5.3 相机预览、数字变焦、180° 翻转

戴着眼镜看相机画面（自拍/手持构图），叠加三分线、水平仪，可选 REC 标记。

**怎么用**

```bash
# 独立预览：眼镜 UVC → 眼镜显示
$PY -m glxss cast --source uvc --fps 10 --detach --rotate 180

# 推流进程内预览（推荐；同一帧共享给编码和 LCD）
$PY pi/stream.py --fps 25 --bitrate 2M --async-urb 16 \
  --mirror-fps 10 --mirror-rotate 180

# 长按 Fn 在预览/HUD 间切，或：
$PY -m glxss ctl display preview
$PY -m glxss ctl display hud
```

`--detach` 在 Linux 上必须加：内核 `uvcvideo` 会占着 UVC 的 VideoStreaming
接口，不摘掉就打不开。

**预览链路**

```text
UVC 1280×720 YV12（frame 3）
  → 按 fit/cover 取画面（cover：1280×720 居中裁 1152×720）
  → 4×4 整数抽样到 320×180 左右
  → 画三分线 + 水平仪（HID 加速度计 pitch/roll）
  → 摆到 320×200 画布（fit 时居中留黑边）
  → 2× 最近邻放大到 640×400 RGBA
  → 16 B 头 + 1 MB bulk OUT
```

Pi Zero 2 W 上这条链路的眼镜侧稳定 **10.0 fps**，单帧渲染 **17–28 ms**
（1.0x 的 CPU 中位 17.7 ms，双路时和其他线程抢 GIL 后墙钟到 22–28 ms），
1 MB 发送窗口 **40–56 ms**（同步；异步发送前暂停 UVC 后 32–39 ms）。

**数字变焦**

档位 1.0 / 1.5 / 2.0 / 3.0x，都是对 **720p 源画面做居中裁切**：

| 档位 | 源裁切窗口 | 说明 |
|---:|---|---|
| 1.0x | 1280×720 | 整幅 |
| 1.5x | 853×480 | 中心裁切后重新映射 |
| 2.0x | 640×360 | 中心裁切 |
| 3.0x | 427×240 | 中心裁切 |

入口：

* 预览下短按 Fn：`1.0 → 1.5 → 2.0 → 3.0 → 1.0` 循环；
* `glxss ctl zoom 1.5` 或 `glxss ctl zoom cycle`；
* 启动参数 `--zoom` 只影响启动档位，当前白名单是 1.0/1.5/2.0；3.0x 用 Fn
  或 `ctl zoom cycle` 切。

变焦只改眼镜预览的视野，**RTSP 推流始终是全画幅 1280×720**；切档位不重启
LCD 线程，也不重启 ffmpeg。

**180° 翻转**

* `glxss cast` / `glxss hud`：`--rotate 180`
* `pi/stream.py`：`--mirror-rotate 180`
* 180° 下面板/叠加层坐标整体翻了一次，水平仪的 pitch/roll 默认也取反；
  方向不对时用 `--level-invert-x`（roll 左右倾）/ `--level-invert-y`（pitch 上下）
  单独修。验收：头左倾 → 地平线右倾；头低下 → 地平线上移。

**坑**

* UVC 是独占的。`glxss uvc`、`glxss cast --source uvc`、`pi/stream.py` 只能同时
  跑一个；切换前先停另一个。
* probe/commit 后设备会重启数据面，第 2 帧约 **466 ms**；`--check` 预热 2 帧就是
  为了不把这一下算进 fps。
* 720p → 480p 在同一会话里切是设备怪癖：回读说 frame 4，数据面可能还在发
  720p，按 460,800 组帧会斜纹花屏。要 480p 就重新打开设备（必要时拔插），
  不要原地切。
* 预览分辨率不是越高越好：720p 全画幅在 Pi Zero 2 W 上已经接近 USB/GIL 上限，
  双路时采集只有 17–19 fps。

### 5.4 推流：UVC → H.264 → RTSP → OBS

```text
眼镜 UVC 1280×720 YV12
  → pyusb 读帧（16 KiB 分块组帧；异步 16 URB）
  → ffmpeg stdin（h264_v4l2m2m 硬编）
  → RTSP/TCP 127.0.0.1:8554/glxss（mediamtx）
  → PC OBS 媒体源 rtsp://<Pi-IP>:8554/glxss
```

单路默认推荐 **1280×720@25、2 Mbps、TCP**；需要满采集就 `--fps 30`。

**怎么用**

```bash
# 单路 25 fps
$PY pi/stream.py --fps 25 --bitrate 2M --async-urb 16

# 720p30（采集和编码都拉到 30，Pi 会热一点）
$PY pi/stream.py --fps 30 --bitrate 2M --async-urb 16

# 推流 + 眼镜 10 fps 预览
$PY pi/stream.py --fps 25 --bitrate 2M --async-urb 16 \
  --mirror-fps 10 --mirror-rotate 180

# 只验证，不推流
$PY pi/stream.py --check
```

`pi/service/glxss-stream.service` 模板里是 `--fps 30 --bitrate 2M --async-urb 16
--mirror-fps 10 --mirror-rotate 180`；想按 25 跑就改 `--fps 25`。

**验证**

```bash
ffprobe -v error -show_entries stream=codec_name,width,height,avg_frame_rate \
  -of default=nw=1 rtsp://127.0.0.1:8554/glxss
# codec_name=h264 width=1280 height=720 avg_frame_rate=25/1
```

**断线重连（实测）**

* 运行中 `pkill ffmpeg`：1 s 内打印编码器退出并重启，新进程起来后编码恢复，
  采集不断。
* `pkill mediamtx`：ffmpeg 退避重试，mediamtx 恢复后自动续推，状态行
  `重启=n/n` 递增。
* UVC 掉线：关设备、退避重开；这期间继续丢帧，避免 USB 缓冲溢出。

**坑**

* 先确认硬编存在：`ffmpeg -hide_banner -encoders | grep h264_v4l2m2m`。
  Bookworm 上 `h264_omx` 通常不可用，别选。
* `--fps` 高于实际采集帧率只会让时间戳更假；异步 16 URB 下 720p 实测
  29.7–30.7 fps，双路 10 fps 预览时只有 17–19 fps。
* 推流地址默认是 `rtsp://127.0.0.1:8554/glxss`；OBS 在 PC 上要换成
  `rtsp://<Pi-IP>:8554/glxss`，并放行 8554/tcp。
* 720p30 需要 42 MB/s；同步读只有 26 MB/s，必须装 `usb1` 或显式
  `--async-urb 16`。
* 不要在推流的同时再开一个进程读 UVC；UVC 接口独占。

### 5.5 Fn 按键

HID 报告里的 Fn 键码是 `0x71`。stream 进程默认每 100 ms 轮询一次 HID。

| 操作 | 条件 | 行为 |
|---|---|---|
| 长按 ≥0.9 s | 任意视图 | 相机预览 ↔ HUD |
| 短按 | 预览下 | 变焦循环 1.0 / 1.5 / 2.0 / 3.0 |
| 短按 | HUD 下 | 忽略（只打日志） |

长按**只看按住时间**，不再要求“连续 N 个按下包”。松开时如果实际按住
≥0.9 s 也会补发长按，避免边缘漏判。阈值可调：`--fn-long-press 0.9`；
`--no-fn` 完全禁用按键。

日志长这样：

```text
[hid] key 0x71 down  00:12:16
[hid] key 0x71 up    00:12:16（按住 0.10 s）
[stream] Fn 按键：单击（0.10 s，1 包） → zoom 1.5x（源中心 853x480；居中裁切后缩放）

[hid] key 0x71 up    00:04:26（按住 1.34 s）
[stream] Fn 按键：长按（1.34 s，13 包） → display toggle（当前 preview）
```

没有硬件也能走完整路径：

```bash
$PY -m glxss ctl button click      # 等价短按（HUD 下被忽略）
$PY -m glxss ctl button long       # 等价长按（切视图）
```

**实测**

* 真实短按 0.10–0.22 s 被判为单击；长按 1.34 s 被判为长按。
* 切 HUD/预览只改 `LcdSink` 模式，不 release/claim LCD 接口，不会冻结。
* 双路运行中切变焦，眼镜保持 9.8–10.0 fps，采集 17.7–19.0 fps，`重启=0/0`。

**坑**

* HID 起不来时 Fn 没反应；先看 `ctl status` 的 `HID` 行，或
  `glxss hid --enable-sensors --count 5` 单独验证。
* 同一时间只能有一个 HID 轮询者。`pi/stream.py` 已经开了轮询，就不要再跑
  `glxss hid` 或第二个 HUD。
* RTSP 开关已经不再绑 Fn，用 `ctl rtsp on|off`。

### 5.6 HID 传感器

`0xA1/0x16`，每次读 28 B，结构 `<BBH9hBBf>`：

| 字段 | 说明 |
|---|---|
| `keyStatus` / `keyCode` | 按键状态 / 键码（Fn=`0x71`） |
| `lux` | 光照 |
| `compass_x/y/z` | 磁罗盘原始值（未标定） |
| `acc_x/y/z` | 加速度，用于 pitch/roll |
| `gyro_x/y/z` | 陀螺原始值 |
| `af_result` / `caf_flag` | 对焦结果/连续对焦标志 |
| `temperature` | 板载温度（°C） |

Pi 端每 **100 ms** 轮询一次；设备本身约 **300 ms** 一包。HUD 的 lux/温度/
姿态、预览的水平仪、Fn 都来自这里。

**怎么用**

```bash
$PY -m glxss hid --count 10                 # 10 包
$PY -m glxss hid --enable-sensors --count 5 # 先使能传感器再读
$PY -m glxss hid --raw --count 3            # 附 28 B hex
```

**坑**

* 传感器默认可能是关的；读到的 lux/罗盘/IMU 全 0 时先 `--enable-sensors`。
* 磁罗盘只显示原始值，硬铁/软铁校准没做；heading 的坐标系也没在真机标定。
  罗盘刻度环/heading 已计划从 HUD 去掉（见 §5.2）。
* HID 接口可能被内核 `hid-generic` 或其他进程占着。和 UVC 一样，一个设备只跑
  一个轮询者；失败了先 kill 占用进程/重新插拔。
* 温度是板载传感器读数，不是体温。

### 5.7 LCD HAT（1.3" 240×240 ST7789）

HAT 的定位是**状态/控制面板**，不镜像眼镜画面。状态页每秒刷新，屏幕每
0.5 s 重绘一次。

**状态页**

```text
GLXSS HAT                    ●
GLASSES   ONLINE / OFFLINE
FPS       2.0
BRIGHT    185
VIEW      HUD / MONITOR
HUD       ON / PAUSED
LUX       321 lx
MODE      CAST / STANDBY
TIME      19:35:17
```

底部提示 `PRESS MENU  K1 HUD` / `K2 TEST  K3 QUIT`；服务掉线时显示
`ERR ...` 和 `K3 QUIT  PRESS MENU`。

温度不在这一页：240×240 放不下，旧版曾有一行 `TEMP`；现在温度只画在 HUD 左侧，
`ctl status` 也没有单列温度（要看原始报告用 `glxss hid`）。`VIEW` 是后来加的，
用来切 HUD/相机预览。

**菜单（7 项）**

| 项 | LEFT/RIGHT | PRESS |
|---|---|---|
| BRIGHTNESS | 100..254，步进 10 | 下发亮度 |
| VIEW | HUD / MONITOR | 切相机预览 |
| DISPLAY | 1=STANDBY / 2=CAST | 切显示模式 |
| SCREEN | ON/OFF | 开/关屏（开屏自动 CAST） |
| HUD | ON/PAUSED | HUD 开关 |
| TEST FRAME | — | 发 3 s 测试图案，自动切回 HUD |
| EXIT | — | 退出 HAT（自动拉起的 HUD 子进程一起退） |

**8 个按键**

| 按键 | 状态页 | 菜单页 |
|---|---|---|
| UP | — | 上移 |
| DOWN | — | 下移 |
| LEFT | — | 调小/切换 |
| RIGHT | — | 调大/切换 |
| PRESS | 进入菜单 | 确认 |
| KEY1 | HUD 开关 | — |
| KEY2 | 发 3 s 测试帧 | — |
| KEY3 | 退出 | 返回状态页 |

8 个都映射了；状态页只用 PRESS/KEY1/KEY2/KEY3，UP/DOWN/LEFT/RIGHT 留给菜单。

这些命令是按 `glxss hud --listen` 主进程的接口实现的。如果 socket 连的是
`pi/stream.py`，`VIEW` 能切，但亮度/HUD 开关/测试帧这些命令 stream 进程没有
对应实现；推流时用 `glxss ctl` 控制，或者只跑 HUD + HAT。

**运行 / 自启**

```bash
# 手动
$PY pi/lcdhat.py --socket "$GLXSS_SOCK"

# 没装 SPI/GPIO 也能看页面
$PY pi/lcdhat.py --preview /tmp/hat

# 硬件自检（先停掉 lcdhat 服务让出 GPIO）
sudo systemctl stop glxss-lcdhat
$PY pi/lcdhat_selftest.py colors
$PY pi/lcdhat_selftest.py text
$PY pi/lcdhat_selftest.py keys

# 自启
sudo cp pi/service/glxss-lcdhat.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now glxss-lcdhat
```

HAT 在 socket 不存在时会自动拉起 `glxss hud --listen` 子进程；systemd 模板用
`--no-spawn`，因为 HUD 由 `glxss-cast`/`glxss-stream` 负责。

**坑**

* 黑屏先看 SPI/接线/背光；`--preview` 能出 PNG 说明渲染没问题，问题在 SPI。
* 花屏/偏移调 `--x-offset/--y-offset`（常见 `0/80`、`80/0`）和 `--rotation`；
  颜色反相用 `--invert` / `--no-invert`。
* 用户要在 `spi`、`gpio` 组；`spidev` 被 fbtft 占用时先禁用 fbtft。
* 状态页的 `ONLINE` 只代表 **HUD/推流进程的 socket 可达**，不代表 USB 一定
  正常；USB 错误看 `ctl status` 的 `UVC 错误`/`HID` 行。
* `GLXSS_SOCK` 和 `--socket` 两边必须一致，否则 HAT 会去拉起一个新 HUD。

### 5.8 固件：03e7:2150 → 2e09:0030

固件不在眼镜里。每次上电它先以 `03e7:2150`（Myriad ROM 引导）出现，主机通过
EP1 灌 1.5 MB 镜像，约 **0.3–0.5 s** 后重枚举成 `2e09:0030` 正常工作模式。

**自动（Pi）**

如果 Pi 上已经配了自动灌写（udev 看到 `03e7:2150` 就调用一次
`glxss flash --yes`），插上/上电后约 0.3–0.5 s 自动变 `2e09:0030`。仓库里的
`udev/99-glxss.rules` 只有权限，不含自动灌写；从仓库新装的机器要先按下面的
手动命令灌一次，或者自己加一条 `RUN+=` 规则（镜像路径写死；udev 里不要跑长
任务，做成 systemd 服务更稳）。

**手动**

```bash
# 默认 dry-run：只读镜像、算 SHA256、打印计划，不打开设备
$PY -m glxss flash G25_MainFW_1.9.1.img

# 真写（危险）
$PY -m glxss flash G25_MainFW_1.9.1.img --yes
```

写之前会检查镜像的 `MA2x` 和 `G25_MainFW_` 版本串；除非 `--force`，不匹配不写。
写完等 **8 s** 让它重枚举，别拔线。`lsusb` 看到 `2e09:0030` 才算成功。

**坑**

* `linux/` 不提供固件文件；用你合法持有的原厂镜像。
* Pi Zero 2 W 供电/线材差时灌写容易断，恢复优先在 PC 上做。
* 设备有一种“半死”状态：USB 控制面还活着（状态/发帧都 ACK），但相机/显示
  流水线停摆，UVC 一帧都收不到。只能重灌固件恢复。
* 本工具不实现写 EEPROM（`0x21/0x06`），避免改 SN/BOM。

### 5.9 导航 HUD（手机 UDP）

Pi 监听 `0.0.0.0:9999`（UDP），手机每秒发一包 UTF-8 JSON：

```json
{"bearing": 45, "distance": 120, "label": "右转", "heading": 40}
```

| 字段 | 含义 |
|---|---|
| `bearing` | 目标绝对方位（deg，0=北，顺时针） |
| `distance` | 距离（米） |
| `label` | 下一动作文字（UTF-8，最长 48 字） |
| `heading` | 手机当前朝向（deg，可选） |

新版也接受 `maneuver`（`left` / `slight-left` / `right` / `slight-right` /
`straight` / `roundabout` / `uturn` / `arrive`）和 `eta`（秒）/`remain`（米）；
不传 `maneuver` 时 Pi 按 `label` 的中英文关键词猜，`eta`/`remain` 用于顶栏的
剩余时间/距离。

`bearing`/`heading` 允许省略或 `null`。**5 s** 没收到新包（`--nav-stale`）
HUD 回到「等待手机…」（新版文案「等待手机导航…」）。绑定 9999 不需要 root，
有防火墙时放行 UDP 9999。

**怎么用**

```bash
# 手动注入一帧（持久，直到 nav-clear 或手机新包覆盖）
export GLXSS_SOCK=/run/glxss/glxssctl.sock
$PY -m glxss ctl nav 45 --distance 120 --label 右转 --heading 40
# 新版也可以不给 bearing，直接按 maneuver/label 画箭头：
$PY -m glxss ctl nav --label 右转 --distance 500 --eta 120 --remain 2400
$PY -m glxss ctl status        # 看「导航 / 导航UDP」两行
$PY -m glxss ctl nav-clear

# PC 上直接模拟手机（IP 换成 Pi）
python3 -c 'import json,socket; socket.socket(socket.AF_INET,socket.SOCK_DGRAM).sendto(json.dumps({"bearing":45,"distance":120,"label":"右转","heading":40},ensure_ascii=False).encode(),("192.168.2.128",9999))'
```

手机 App（`app/`，0.1.7 起）首页有「导航 HUD（Google 地图 → Pi）」：开通知使用权，
填 Pi IP/UDP 端口，App 解析 Google 地图导航通知里的指令/距离/剩余时间，按每秒
一包发 `maneuver`/`distance`/`label`/`eta`/`remain`。页面里还有调试卡片和
「模拟一条通知」，不开车也能验证整条链路。

**坑**

* 端口被占时第二个监听者 bind 失败；同一时间只跑一个 HUD/stream 进程。
* JSON 里数字必须是数字，`"45"` 这种字符串只在部分路径容忍；中文 label 要 UTF-8。
* `ctl nav` 注入的帧 `ttl=0` 持久，不想要就 `nav-clear`。
* 罗盘刻度环/heading 计划移除（见 §5.2）：移除后 `bearing` 可省略，只发
  `label` + `distance` 也能画箭头。

### 5.10 实测性能（Pi Zero 2 W）

CPU 单位：**1 个 A53 核 = 100%**，数据取自 `/proc/<pid>/stat` 的
utime+stime 增量，20 s 以上稳态。

| 场景 | 采集 | 编码/发送 | 眼镜 | python | ffmpeg |
|---|---:|---:|---:|---:|---:|
| 720p30 异步 16 URB，只推流 | **29.7–30.7 fps / 40.6–42.4 MB/s** | 30.0 fps | — | ~128% | ~31% |
| 720p30 异步 + 眼镜 10 fps（默认暂停 UVC） | **17.5–18.4 fps** | 同采集 | **10.0 fps** | ~108% | ~23% |
| 同上，`--no-lcd-pause` | 19.9–21.9 fps | 同采集 | 10.0 fps | — | — |
| 720p 同步 16 KiB，`--fps 25` 只推流 | 16.9–17.2 fps | 16.9–17.2 fps | — | ~53–55% | ~6–9% |
| 同步 `--fps 25` + 眼镜 10 fps | 13.0–13.2 fps | 13.2 fps | 10.0 fps | ~73% | ~6%（合计 ~79%） |

读这张表的方式：

* **要 30 fps / 42 MB/s**：异步 16 URB，单路 CPU ~1.6 核；这是 Pi Zero 2 W
  的实际上限，`--check` 实测 30.7 fps / 42.38 MB/s。
* **要省 CPU**：同步 25 fps 单路 python 53–55% + ffmpeg 6–9%；代价是采集只有
  17 fps 左右。
* **双路**：眼镜始终 **9.9–10.0 fps**；采集 17.5–18.4 fps（默认暂停 UVC），
  加 `--no-lcd-pause` 可到 19.9–21.9 fps。合计 CPU 约 73–79%（同步 25 双路）
  或 ~130%（异步 30 双路，约 1.3 核）。
* **眼镜预览单帧**：720p 渲染 **17–28 ms**（1.0x CPU 中位 17.7 ms，双路墙钟
  22–28 ms）；1 MB bulk OUT 发送窗口 **40–56 ms**（同步），异步暂停后 32–39 ms。
* 双路 10 fps 时 4 个核没跑满，瓶颈是 **CPython GIL**：读线程、异步拷贝、编码、
  LCD 四个线程抢 GIL，所以采集是 17–19 而不是 30。要更顺的相机画面就把
  `--mirror-fps` 降到 5（采集约 23.7 fps）。
* 温度 45–52 °C、`throttled=0x0`；长时间硬编到 60–70 °C 正常，加散热片。

---

## 6. `glxss ctl` 命令参考

`glxss ctl` 通过 Unix socket 给正在跑的主进程发 JSON 命令。socket 路径由
`GLXSS_SOCK` 或 `--socket` 决定；systemd 服务固定在：

```text
/run/glxss/glxssctl.sock
```

从 SSH 控制时先 export，否则客户端会去连用户默认路径：

```bash
export GLXSS_SOCK=/run/glxss/glxssctl.sock
$PY -m glxss ctl status
# 等价：$PY -m glxss ctl status --socket /run/glxss/glxssctl.sock
```

| 命令 | 作用 |
|---|---|
| `ctl status` | 眼镜在线、fps、视图、变焦、水平仪、HID、导航、RTSP、屏/HUD、光感、固件 |
| `ctl display hud / preview / toggle` | 切 HUD / 相机预览 / 来回切 |
| `ctl zoom 1.0 / 1.5 / 2.0 / cycle` | 数字变焦；`cycle` 走 1.0→1.5→2.0→3.0→1.0 |
| `ctl rtsp on / off / toggle` | 开/关推流（不重启采集） |
| `ctl nav [bearing] --distance M --label 文本 [--heading DEG] [--eta S] [--remain M]` | 注入导航帧；`bearing` 可省略 |
| `ctl nav-clear` | 清掉导航帧 |
| `ctl button click / long` | 注入 Fn 单击/长按，无硬件测试用 |
| `ctl quit` | 让主进程退出 |

`display` 的 `preview` 也接受 `camera`/`uvc`/`monitor`/`on`，`hud` 也接受
`off`；`view hud|monitor` 是同义命令。

**`status` 输出示例**（数值随运行变化）

```text
  眼镜    : ONLINE
  fps     : 25.0
  视图    : preview
  显示    : camera preview（最近按键 long）
  变焦    : 1.5x，源中心裁切 853x480（短按 Fn 在 video 下循环；ctl zoom cycle）
  水平仪  : pitch=-2.2° roll=+0.7°（取反 x=True y=True）
  HID     : alive 轮询 100 ms，key=0x71/0，按键=up
  导航    : maneuver=right distance=120 m eta=-- remain=-- label='右转' age=0.4s fresh=True
  导航UDP : alive 0.0.0.0:9999，收 12 包，坏包 0
  RTSP    : ON（ctl rtsp on|off，已不绑 Fn）
  UVC 读   : 异步 16 URB，在途 16，队列 0，暂停 120 次/3600 ms
  屏      : 开
  HUD     : ON
  光感    : 321 lux
  固件    : G25_MainFW_1.9.1
  时间    : 19:35:17
  socket  : /run/glxss/glxssctl.sock
```

**支持范围**

* `pi/stream.py` 的主进程：`status`、`display`/`view`、`zoom`、`rtsp`、`button`、
  `nav`、`nav-clear`、`quit`。
* `glxss hud --listen` 的主进程：额外支持 `bright`、`screen`、`mode`、`hud`、
  `test`、`rec`；`zoom`/`rtsp` 只有 stream 进程有对应实现。

**坑**

* 两边 `GLXSS_SOCK`/`--socket` 必须一致，否则报「控制服务未启动」。
* 同一个 socket 只能有一个服务监听；先 `pgrep -af 'glxss|stream.py'` 看清楚
  连的是 HUD 还是推流进程。
* `ctl zoom` 只影响眼镜预览，不改变 RTSP 全画幅；`3.0x` 用 `cycle` 最稳。
* `ctl quit` 会真的退出主进程；systemd 管着的话会按 `Restart` 再拉起来。

---

## 7. 已知限制

* **USB 带宽**：Pi Zero 2 W 同步单 URB 约 26 MB/s（720p 18–19 fps），异步
  16 URB 约 42 MB/s（720p 29.7–30.7 fps）。这是 dwc_otg + usbfs 的现实上限，
  不是代码参数能无限提高的。
* **双路 GIL**：推流 + 眼镜 10 fps 预览时采集 17–19 fps，4 核没跑满但 CPython
  的 GIL 把 LCD 线程拖长；要接近 30 fps 只能降低预览帧率或把渲染移出 Python。
* **撕裂**：设备把 payload 直接当帧缓冲，没有 vsync/双缓冲。主机侧整帧一次
  bulk OUT、控制请求不插帧、发送时暂停 UVC，能把窗口压到 32–39 ms，但不能
  彻底消除。移动镜头时仍可能看到上/下半帧错位。
* **720p ↔ 480p 同会话切换**：设备回读和实际数据面可能不一致，按 460,800
  组帧会斜纹花屏。要 480p 就重新打开设备（必要时拔插）。
* **1080p 不可用**：净荷 3,110,400 B，异步实测约 43.64 MB/s / 14 fps；
  30 fps 需要 93 MB/s，USB2 达不到。
* **HUD 2 fps / Pillow**：单帧 110–120 ms，纯 Python 渲染；信息型 HUD 够用，
  不适合做高帧率视频。
* **罗盘/heading**：眼镜没有磁力计，HUD 的罗盘刻度环/heading 计划移除；
  磁罗盘原始值也未做硬铁/软铁校准。
* **音频/录像/NPU**：UAC 没接进 RTSP，OBS 要声音请单独加麦；录像、NPU、
  MJPEG 以外的低延迟协议不在范围。
* **Windows/macOS**：显示能跑，UVC/HID 未做完整真机验证；Windows 需要
  Zadig 绑 WinUSB，不要把整个复合设备绑掉。
* **供电/散热**：Pi Zero 2 W 硬编 30 fps 会到 60–70 °C；供电不足会掉设备、
  Wi-Fi 断、`throttled` 非 0。
* **写 EEPROM**：故意不实现（会改 SN/BOM）。
* **端到端延迟**：0.3–0.6 s 是估算，没做精确打点；主要来自编码和 OBS 缓冲。

---

## 8. 故障排查

| 现象 | 处理 |
|---|---|
| `No backend available` / 找不到 libusb | Linux 装 `libusb-1.0-0`；Windows 放 `libusb-1.0.dll`；macOS `brew install libusb` |
| 只有 `03e7:2150` | 眼镜在 ROM 引导模式，没固件；按 §5.8 灌写，成功后变 `2e09:0030` |
| `未找到 2e09:0030` | 先 `glxss list`；检查线材/供电/Hub；可能在引导模式或另一进程占着 |
| `权限不足` / `LIBUSB_ERROR_ACCESS` | 装 `udev/99-glxss.rules` 并 `udevadm trigger`；确认用户组；退出原厂 App/其他 glxss |
| `接口忙` / claim 失败 | 退出占用进程；Linux 加 `--detach`；同一设备不要重复 claim |
| 发帧成功但屏不亮 | `glxss screen on` + `glxss mode 2`；亮度调到 180+ |
| UVC 超时 / 0 帧 | 关掉其他摄像头程序；加 `--detach`；`--check` 看 probe 结果；固件假死需重灌 |
| 720p 只有 18–19 fps | 没装 `usb1`/`python3-usb1`，回退同步了；装好后用 `--async-urb 16` |
| 推流卡顿/重启多 | 看状态行 `采集/发送/丢弃/重启`；`--fps` 降到实测值；换有线/降预览帧率 |
| OBS 拉不到流 | 确认 mediamtx 在跑、URL 是 `rtsp://<Pi-IP>:8554/glxss`、8554/tcp 放行；用 `ffprobe` 先验 |
| `h264_v4l2m2m` 不存在 | `ffmpeg -encoders` 里找 `v4l2m2m`；装 Pi OS 的 ffmpeg；备选 `--codec libx264`（CPU 高） |
| `ctl` 连不上 | `GLXSS_SOCK`/`--socket` 两边一致；先确认主进程有 `--listen`/控制 socket；看 `/run/glxss` 权限 |
| `ctl view monitor` 后还是 HUD | 看 `ctl status` 的 `UVC 错误`；多数是没加 `--detach`，uvcvideo 占着 VS |
| Fn 键没反应 | `ctl status` 看 HID 是否 alive；`glxss hid --enable-sensors --count 5`；别同时开两个 HID 轮询 |
| HUD 显示 `NO IMU` / 传感器全 0 | `glxss hid --enable-sensors --count 5`；无罗盘时显示 `--` 正常 |
| 中文显示方框 | 装 `fonts-wqy-zenhei`；用 `fc-list :lang=zh` 确认；没有 CJK 会自动退英文 |
| HAT 黑屏/花屏/偏移 | 检查 SPI/接线/背光；调 `--x-offset/--y-offset`（常见 0/80、80/0）和 `--rotation`；颜色反相用 `--invert` |
| HAT 一直 OFFLINE | HUD/stream 没跑或 socket 不一致；`systemctl status glxss-*`；`ls -l /run/glxss/glxssctl.sock` |
| 画面上下颠倒 | `glxss cast/hud --rotate 180`；stream 用 `--mirror-rotate 180` |
| 画面左右/上下反 | 调 `--level-invert-x`（roll）/`--level-invert-y`（pitch），别改源码 |
| 移动镜头时画面撕裂 | 设备无 vsync，属已知限制；确认没加 `--no-lcd-pause`；降低预览帧率 |
| 切 480p 后斜纹 | 同会话切换怪癖；重开设备/拔插，不要原地 commit |
| 摄像头读不到、状态还能读 | 固件媒体流水线假死；重新灌固件（§5.8） |
| `throttled` 非 0 / 掉设备 | 换 5 V/2.5 A 电源和短粗线；用带供电 Hub；`vcgencmd get_throttled` 要 `0x0` |

---

## 9. 自检、打包与索引

### 9.1 无眼镜自检

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

`unittest` 里控制 socket 的用例在没有 `AF_UNIX` 的平台会 skip；HUD 用例需要
Pillow + numpy，缺失时也会 skip。

### 9.2 打包（PyInstaller）

```bash
# Linux/macOS
packaging/build.sh              # onefile
packaging/build.sh --onedir     # 启动更快

# Windows
powershell -ExecutionPolicy Bypass -File packaging\build.ps1
powershell -ExecutionPolicy Bypass -File packaging\build.ps1 -OneDir
```

要点：PyInstaller 不能跨平台；Windows 需要 `libusb-1.0.dll`；onefile 体积约
30–90 MB、每次启动解压 1–3 s；受限环境 onefile 建不了临时目录时用 `-OneDir`。
详见 [`packaging/README.md`](packaging/README.md)。

### 9.3 代码和协议出处

| 内容 | 位置 |
|---|---|
| 显示帧格式/亮度/模式 | [`docs/ch-display.md`](../docs/ch-display.md) |
| UVC probe/commit、YV12、16 KiB 组帧 | [`docs/ch-uvc.md`](../docs/ch-uvc.md) |
| HID 28 B 报告 | [`docs/ch-hid.md`](../docs/ch-hid.md) |
| 固件容器/灌写/恢复 | [`docs/ch-firmware.md`](../docs/ch-firmware.md) |
| 推流、延迟、性能记录 | [`pi/STREAM.md`](pi/STREAM.md) |
| Pi 从零安装、接线、排障 | [`pi/SETUP.md`](pi/SETUP.md) |
| HAT 接线/最短命令 | [`pi/README.md`](pi/README.md) |
| 打包 | [`packaging/README.md`](packaging/README.md) |

HUD 渲染在 `glxss/hud.py`，相机预览/变焦在 `glxss/pipeline.py`，推流/重连在
`glxss/stream.py`，UVC 异步读在 `glxss/uvc_async.py`，控制通道在
`glxss/control.py`。所有硬件命令复用 `glxss/usb.py` 的 pyusb 封装。

### 9.4 许可

本项目禁止商业使用，详见根目录 [LICENSE](../LICENSE)。
