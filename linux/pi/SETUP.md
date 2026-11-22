# 树莓派 Zero 2 W + Waveshare 1.3inch LCD HAT：从零安装

本文从一张空白 SD 卡开始，装到：

* 眼镜显示 **HUD**（时间/罗盘/光感/姿态/亮度/固件）或 **相机监看**；
* 树莓派上的 **LCD HAT 作为状态/控制面板**（不镜像眼镜画面）；
* 摇杆菜单可以调亮度、切 HUD/MONITOR、切显示模式、开关屏、开关 HUD、发测试帧、退出；
* 两个 systemd 服务开机自启。

> 硬件操作前先读 §4 接线和 §12 供电。烧录/改 `config.txt` 前确认路径是
> Bookworm 的 `/boot/firmware/config.txt`（Bullseye 是 `/boot/config.txt`）。
>
> 本文命令默认用户 `pi`、仓库在 `/opt/glxss`、venv 在 `/opt/glxss/venv`。
> 换用户名/路径时同步改两个 service 文件。

---

## 0. 最终形态

```text
┌─────────────────────────┐        USB 2.0         ┌──────────────────┐
│ Raspberry Pi Zero 2 W   │───────────────────────►│ GLXSS3 / G25 眼镜 │
│  ├─ glxss hud --listen  │  bulk OUT 1,024,016 B  │  640×400 面板     │
│  │   （HUD 主进程）      │  16 B 头 + RGBA        │  HID 28 B 传感器   │
│  └─ pi/lcdhat.py        │◄── Unix socket ───┐    └──────────────────┘
│      （HAT 状态/菜单）   │  /run/glxss/…    │              ▲
└───────────┬─────────────┘                  │              │
            │ SPI + GPIO                     │              │
     ┌──────▼──────────┐              ┌──────┴───────┐      │
     │ Waveshare 1.3"  │              │ 同一个进程读  │      │
     │ ST7789 240×240  │              │ HID + 写 LCD │──────┘
     │ 摇杆 + 3 按键    │              └──────────────┘
     └─────────────────┘
```

* **眼镜是主显示**：HUD 由 Pi 在主机侧用 Pillow 画成 640×400 RGBA，再按帧发送。
* **HAT 是控制面板**：只显示状态和菜单，不复制眼镜画面。
* 两个进程通过 Unix socket 通信；HAT 默认会自动拉起 HUD 主进程。

---

## 1. 物料

| 物料 | 说明 |
|---|---|
| Raspberry Pi Zero 2 W | 64-bit SoC；也可以用 Pi 3/4/5，引脚兼容 |
| microSD 卡 | 8 GB+，Class 10/A1 以上 |
| 5 V 电源 | **2.5 A 以上**，Micro-USB（Zero 2 W）或 USB-C（Pi 4/5） |
| Waveshare 1.3inch LCD HAT | ST7789 240×240，直接插 40-pin |
| USB 数据线 | Pi Zero 2 W 的 OTG 口是 **PWR IN** 旁边的 **USB** 口，不是电源口 |
| 可选：带供电 USB Hub | 眼镜 + Pi 一起工作时更稳，见 §12 |
| 可选：HDMI + 键鼠 | 首次无 SSH 时方便，纯 Lite + SSH 不需要 |

> Pi Zero 2 W 有两个 Micro-USB：丝印 `PWR IN` 只供电，`USB` 才是数据 OTG。
> 眼镜必须插在 `USB`（或通过 Hub），插到 `PWR IN` 不会枚举。

---

## 2. 刷 Raspberry Pi OS Lite 64-bit

1. 下载 [Raspberry Pi Imager](https://www.raspberrypi.com/software/)。
2. `Choose OS → Raspberry Pi OS (other) → Raspberry Pi OS Lite (64-bit)`。
3. `Choose Storage` 选 SD 卡。
4. 点齿轮/`Edit Settings`（Imager 会预写入）：
   * hostname：`glxss-pi`
   * 用户名/密码：例如 `pi`
   * 配置 Wi-Fi（国家/SSID/密码）
   * `Services → Enable SSH → Use password authentication`
   * 时区建议 `Asia/Shanghai`
5. 写入，插卡，上电，等 1–2 分钟。
6. 从开发机 SSH：

```bash
ssh pi@glxss-pi.local
# 如果 .local 不通，用路由器里查到的 IP：
ssh pi@192.168.x.x
```

首次登录后：

```bash
sudo apt update
sudo apt full-upgrade -y
sudo reboot
```

> 没有 Imager 预配置时：把 SD 卡 boot 分区里放 `ssh` 空文件、`wpa_supplicant.conf`，
> 或接 HDMI/键鼠走 `raspi-config`。这里不展开。

---

## 3. 打开 SPI（I2C 可选）与 GPIO 上拉

### 3.1 raspi-config

```bash
sudo raspi-config
# Interface Options → SPI → Yes
# Interface Options → I2C → Yes        （可选，本 HAT 不需要）
# System Options → Hostname / Boot / Auto-login 按需
```

非交互：

```bash
sudo raspi-config nonint do_spi 0     # 0 = enable
sudo raspi-config nonint do_i2c 0     # 可选
```

### 3.2 改 config.txt

Bookworm：

```bash
sudo nano /boot/firmware/config.txt
```

Bullseye 及更早：

```bash
sudo nano /boot/config.txt
```

确认/加入：

```ini
# ST7789 用 SPI0（CE0）
dtparam=spi=on

# 可选：I2C（当前 HAT 不用）
# dtparam=i2c_arm=on

# Waveshare 官方建议：按键启用内部上拉
gpio=6,19,5,26,13,21,20,16=pu
```

**不要**同时启用 `fbtft`/`flexfb` 之类的 SPI framebuffer 驱动去抢同一块屏；
如果 `config.txt` 里有 `dtoverlay=flexfb`、`fbtft_device`、`waveshare35a` 等，
先注释掉。否则 `spidev0.0` 会被占用或画面被内核驱动接管。

重启并检查：

```bash
sudo reboot
ls -l /dev/spidev0.0
# 预期 crw-rw---- 1 root spi ...
groups | tr ' ' '\n' | grep -E 'spi|gpio'
```

> `spidev0.0` 属于 `spi` 组；GPIO 属于 `gpio` 组。后面会把用户加进去。

---

## 4. 接线

### 4.1 HAT 直接插

Waveshare 1.3inch LCD HAT 的 40-pin 母座直接套在 Pi 的 40-pin 排针上，
**对准 3V3/GND 方向，不要错位一列**。上电前检查：

* HAT 的 3V3 对应 Pi 的 3V3（物理 1/17），不是 5V；
* 不要有引脚弯折/短路；
* 用杜邦线时长度尽量短（SPI 40 MHz 下过长会花屏）。

```text
        Pi 40-pin 排针（俯视，USB 口朝下）
        ┌───────────────────────────────────┐
   3V3  │ 1  2 │ 5V
  GPIO2 │ 3  4 │ 5V
  GPIO3 │ 5  6 │ GND
  GPIO4 │ 7  8 │ GPIO14
    GND │ 9 10 │ GPIO15
 GPIO17 │11 12 │ GPIO18
 GPIO27 │13 14 │ GND          ← RST
 GPIO22 │15 16 │ GPIO23
   3V3  │17 18 │ GPIO24       ← BL
 GPIO10 │19 20 │ GND          ← MOSI
  GPIO9 │21 22 │ GPIO25       ← DC
 GPIO11 │23 24 │ GPIO8        ← SCLK / CS(CE0)
    GND │25 26 │ GPIO7
 GPIO0  │27 28 │ GPIO1
  GPIO5 │29 30 │ GND          ← LEFT
  GPIO6 │31 32 │ GPIO12       ← UP
 GPIO13 │33 34 │ GND          ← PRESS
 GPIO19 │35 36 │ GPIO16       ← DOWN / KEY3
 GPIO26 │37 38 │ GPIO20       ← RIGHT / KEY2
    GND │39 40 │ GPIO21       ← KEY1
        └───────────────────────────────────┘
```

### 4.2 LCD / ST7789

| 信号 | BCM GPIO | 物理引脚 | 说明 |
|---|---:|---:|---|
| SCLK | GPIO11 | 23 | SPI0 SCLK |
| MOSI | GPIO10 | 19 | SPI0 MOSI |
| CS | GPIO8 | 24 | SPI0 CE0 → `/dev/spidev0.0` |
| DC | GPIO25 | 22 | 命令/数据选择 |
| RST | GPIO27 | 13 | 复位 |
| BL | GPIO24 | 18 | 背光，代码里置高 |
| 3V3 | — | 1 或 17 | HAT 供电（**3.3 V**） |
| GND | — | 6/9/14/20/25/30/34/39 | 共地 |

### 4.3 摇杆与按键

全部**低电平有效**，使用内部上拉；按下读到 `0`。

| HAT 丝印 | BCM GPIO | 物理引脚 | 本程序功能 |
|---|---:|---:|---|
| Joystick UP | GPIO6 | 31 | 菜单上移 |
| Joystick DOWN | GPIO19 | 35 | 菜单下移 |
| Joystick LEFT | GPIO5 | 29 | 调小/切换 |
| Joystick RIGHT | GPIO26 | 37 | 调大/切换 |
| Joystick PRESS | GPIO13 | 33 | 进入菜单 / 确认 |
| KEY1 | GPIO21 | 40 | 状态页：HUD 开关 |
| KEY2 | GPIO20 | 38 | 状态页：发测试帧 |
| KEY3 | GPIO16 | 36 | 状态页：退出；菜单：返回 |

### 4.4 眼镜 USB

* 眼镜插 Pi Zero 2 W 的 **USB** 数据口（不是 `PWR IN`），用 **数据线**，不是纯充电线。
* 正常模式枚举为 `2e09:0030`；刚上电/无固件时是 `03e7:2150`（见 §12）。
* 不要和原厂手机 App 同时占用设备。

---

## 5. 安装依赖与仓库

### 5.1 apt 依赖

```bash
sudo apt update
sudo apt install -y \
  python3-venv python3-pil python3-numpy python3-spidev \
  libusb-1.0-0 fonts-dejavu-core git
```

GPIO 后端三选一（程序自动探测 RPi.GPIO → lgpio → gpiod）：

```bash
sudo apt install -y python3-rpi.gpio
# 或 sudo apt install -y python3-lgpio
# 或 sudo apt install -y python3-libgpiod
```

### 5.2 放仓库

```bash
sudo mkdir -p /opt/glxss
sudo chown "$USER":"$USER" /opt/glxss
git clone -b linux <你的仓库地址> /opt/glxss
```

没有公网/不想用 git 时，在开发机：

```bash
scp -r glxss3-touying linux/ pi@glxss-pi.local:/opt/glxss/
```

### 5.3 venv

用 `--system-site-packages` 复用 apt 装的 Pillow/numpy/spidev/RPi.GPIO，
避免在 Pi Zero 上重新编译 OpenCV 之外的轮子（numpy 在 Pi 上编译很慢）。

```bash
python3 -m venv --system-site-packages /opt/glxss/venv
/opt/glxss/venv/bin/pip install -U pip
cd /opt/glxss/linux
/opt/glxss/venv/bin/pip install -e .
```

检查：

```bash
/opt/glxss/venv/bin/python -c "import spidev, PIL, numpy, usb; print('deps ok')"
/opt/glxss/venv/bin/python -m glxss --version
```

### 5.4 udev 权限

```bash
sudo cp /opt/glxss/linux/udev/99-glxss.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules
sudo udevadm trigger
```

### 5.5 用户组

```bash
sudo usermod -aG spi,gpio,video,plugdev "$USER"
sudo reboot
```

重新登录后确认：

```bash
groups
ls -l /dev/spidev0.0
```

> 只加组不重启/不重新登录时，当前 shell 仍没有新组。

### 5.6 udev 规则做了什么

`99-glxss.rules` 给正常模式 `2e09:0030` 和引导模式 `03e7:2150` 设 `MODE=0666`
（并带 `uaccess`）。没有规则时通常报 `Access denied` / `LIBUSB_ERROR_ACCESS`。

---

## 6. 先做离线自检（不接眼镜）

```bash
cd /opt/glxss/linux

# 1) 编译 + 单元测试
/opt/glxss/venv/bin/python -m compileall -q glxss pi tests tools
/opt/glxss/venv/bin/python -m unittest discover -s tests -v

# 2) 命令行帮助
/opt/glxss/venv/bin/python -m glxss --help
/opt/glxss/venv/bin/python -m glxss hud --help

# 3) 离线生成一张 HUD PNG（不需要眼镜）
mkdir -p /tmp/glxss
/opt/glxss/venv/bin/python -m glxss hud --dry-run \
  --save-png /tmp/glxss/hud.png
ls -l /tmp/glxss/hud.png

# 4) 离线生成 HAT 状态页/菜单页 PNG（不需要 SPI/GPIO）
/opt/glxss/venv/bin/python pi/lcdhat.py --preview /tmp/glxss/hat
ls -l /tmp/glxss/hat
```

把 PNG 拷到开发机看：

```bash
# 在开发机执行
scp pi@glxss-pi.local:/tmp/glxss/hud.png .
scp -r pi@glxss-pi.local:/tmp/glxss/hat .
```

HUD 应包含：顶部时间/连接/fps、中心罗盘、左侧 lux/传感器状态、右侧姿态、
底部亮度/固件、导航占位箭头（不显示温度）。HAT 预览应是 240×240 的深色
状态页（含 VIEW 行）和 7 项菜单页。

---

## 7. 跑 HUD 主进程

先手动跑，确认眼镜能亮：

```bash
cd /opt/glxss/linux
/opt/glxss/venv/bin/python -m glxss hud --listen --detach --fps 2 --brightness 180
```

`--detach` 用于让运行中的 VIEW → MONITOR 能打开眼镜 UVC（uvcvideo 占着
VS 接口）；只在 HUD 用时也可以不加。

预期输出：

```text
设备: ... 2e09:0030 ...
已开屏并切到投屏模式。
  回读: 640×400 屏=开 亮度=...
  使能传感器（光感/罗盘/加速度/陀螺）...
  固件: G25_MainFW_1.9.1
控制 socket: /run/glxss/glxssctl.sock
开始 HUD：fps=2 ...
```

如果 `/run` 不可写（普通用户直接运行），程序会退回 `/tmp/glxssctl-<uid>.sock`。
两边必须用同一个路径；可以显式：

```bash
export GLXSS_SOCK=/tmp/glxssctl.sock
/opt/glxss/venv/bin/python -m glxss hud --listen --socket "$GLXSS_SOCK"
```

另开一个 SSH 窗口：

```bash
export GLXSS_SOCK=/tmp/glxssctl.sock   # 与上面一致
/opt/glxss/venv/bin/python -m glxss ctl status
/opt/glxss/venv/bin/python -m glxss ctl bright 200
/opt/glxss/venv/bin/python -m glxss ctl hud off
/opt/glxss/venv/bin/python -m glxss ctl test 5
/opt/glxss/venv/bin/python -m glxss ctl nav 72 --distance 320 --label "Main St"
/opt/glxss/venv/bin/python -m glxss ctl quit
```

### 7.1 HUD 图层

| 位置 | 内容 |
|---|---|
| 顶部 | 本地时间 HH:MM、`GLASSES ONLINE/OFFLINE`、fps、HUD ON/PAUSED |
| 中心 | 罗盘刻度环（N/E/S/W + 15° 刻度）、heading 指针、`036 deg` |
| 左侧 | 光感 lux、传感器 OK/NO DATA、罗盘 OK/NO DATA（无温度） |
| 右侧 | 姿态仪（地平线按 pitch/roll）、pitch/roll/gyro 数值 |
| 底部 | 亮度条、固件串、HUD 状态、连接状态 |
| 罗盘下方 | 可选导航占位：相对方位箭头 + 距离 + 标签 |

`HudSource.set_nav(bearing, distance, label)` 只是接口占位，**没有接任何真实
导航协议**；`glxss ctl nav` 是手动测试入口。

### 7.2 刷新/发送频率

* HID 固件 300 ms 一包；主进程按 100 ms 轮询，水平仪响应更快；
* HUD 重绘间隔跟 `--fps` 走（`1/fps`）；Pi Zero 2 W 上单次 Pillow 渲染约 65 ms；
* 实际发包频率由 `--fps` 控制；HUD 是静态信息，不要用 25 fps 白烧 USB/CPU。

### 7.2.1 相机监看（VIEW → MONITOR）

```bash
/opt/glxss/venv/bin/python -m glxss ctl view monitor
/opt/glxss/venv/bin/python -m glxss ctl view hud
```

眼镜 UVC **1280×720** YV12（全画幅，frame 3）→ 640×400 RGBA，叠加三分线/水平仪/可选 REC 后发回眼镜。
`glxss-cast.service` 必须带 `--detach`，否则 UVC VS 接口被 uvcvideo 占用。
CPU 紧张时给主进程加 `--uvc-half`（先解 320×240 再放大）。独立运行：
`glxss cast --source uvc --fps 10 --detach --rotate 180 --rec`。

### 7.3 罗盘/姿态约定（未真机标定）

* heading：`atan2(compass_y, compass_x)`，0°=+X、顺时针，`--heading-offset`
  可以整体旋转校准；
* pitch：`atan2(acc_x, hypot(acc_y, acc_z))`，抬头为正；
* roll：`atan2(acc_z, acc_y)`，与 `docs/ch-hid.md §5.3` 一致；
* 磁罗盘需要硬铁/软铁校准，本项目只做原始值显示，**没有做校准流程**。

---

## 8. 跑 HAT 控制器

HUD 主进程已经在跑时：

```bash
cd /opt/glxss/linux
/opt/glxss/venv/bin/python pi/lcdhat.py --socket /run/glxss/glxssctl.sock
```

没有手动跑 HUD 时，lcdhat 默认会**自动拉起**一个
`python -m glxss hud --listen` 子进程（`--no-spawn` 可禁用）：

```bash
/opt/glxss/venv/bin/python pi/lcdhat.py
```

如果面板黑屏/偏移，先只调显示：

```bash
# 常见 240×240 ST7789 面板
--x-offset 0 --y-offset 80

# 另一批面板可能是
--x-offset 80 --y-offset 0

# 画面旋转
--rotation 1     # 或 2/3
```

调好后把参数写进 `glxss-lcdhat.service` 的 `ExecStart`。

### 8.1 菜单操作

**状态页**：

| 按键 | 动作 |
|---|---|
| 摇杆 PRESS | 进入菜单 |
| KEY1 | HUD 开/关 |
| KEY2 | 发 3 秒测试帧 |
| KEY3 | 退出 |

**菜单页**（7 项）：

| 菜单项 | LEFT/RIGHT 调整 | PRESS 确认 |
|---|---|---|
| BRIGHTNESS | 100..254，步进 10 | 下发亮度 |
| VIEW | HUD / MONITOR | 切 HUD 或眼镜相机监看 |
| DISPLAY | 1=STANDBY / 2=CAST | 切显示模式 |
| SCREEN | ON/OFF | 开关屏（开屏自动切 CAST） |
| HUD | ON/PAUSED | 主进程 HUD 开关 |
| TEST FRAME | — | 显示 3 秒测试图案（自动切回 HUD） |
| EXIT | — | 退出 lcdhat（自动拉起的 HUD 子进程也会退出） |

* UP/DOWN 选择，LEFT/RIGHT 调值，PRESS 确认；
* KEY3 返回状态页（不退出）；
* 眼镜 Fn 键单击也能在 HUD / MONITOR 之间切换。

> HAT 界面是英文，避免 Pi 默认字体缺中文字形；日志/journal 是中文。

### 8.2 面板 offset 微调

ST7789 的 240×240 面板在 240×320 显存里有一个可见窗口偏移，不同批次可能不同。
现象是画面整体偏上/偏下/偏左/偏右、边缘有黑边/花边。按顺序试：

```bash
--x-offset 0  --y-offset 80
--x-offset 80 --y-offset 0
--x-offset 0  --y-offset 0
--rotation 1
```

如果颜色反相，改 `pi/lcdhat.py` 的 `_INIT`：把 `(0x21, ...)` 改成
`(0x20, ...)`（INVOFF）或反过来。不同批次可能需要不同的初始化序列。

---

## 9. systemd 开机自启

仓库提供两个 unit：

* `glxss-cast.service`：HUD 主进程 + 控制 socket；
* `glxss-lcdhat.service`：HAT 状态/菜单（`--no-spawn`，只连 socket）。

```bash
sudo cp /opt/glxss/linux/pi/service/glxss-cast.service /etc/systemd/system/
sudo cp /opt/glxss/linux/pi/service/glxss-lcdhat.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now glxss-cast.service glxss-lcdhat.service
```

检查：

```bash
systemctl status glxss-cast.service
systemctl status glxss-lcdhat.service
journalctl -u glxss-cast.service -f
journalctl -u glxss-lcdhat.service -f
```

停止/重启：

```bash
sudo systemctl stop glxss-lcdhat.service glxss-cast.service
sudo systemctl restart glxss-cast.service
sudo systemctl restart glxss-lcdhat.service
```

### 9.1 按实际环境修改

两个 unit 里默认：

```ini
User=pi
Group=pi
WorkingDirectory=/opt/glxss/linux
ExecStart=/opt/glxss/venv/bin/python ...
```

用户名/路径不同就要改。`glxss-cast.service` 里：

```ini
Environment=GLXSS_SOCK=/run/glxss/glxssctl.sock
RuntimeDirectory=glxss
RuntimeDirectoryMode=0750
```

systemd 会创建 `/run/glxss`，属主是 `User`，HAT 用同一路径。

### 9.2 只跑 HUD、不接 HAT

```bash
sudo systemctl disable --now glxss-lcdhat.service
```

### 9.3 只跑 HAT、手动跑 HUD

把 `glxss-lcdhat.service` 的 `--no-spawn` 去掉，HAT 会自己拉起 HUD；
或者保留 `--no-spawn`，手动在 SSH 里跑 HUD。

---

## 10. 更新

```bash
cd /opt/glxss
git fetch origin
git checkout linux
git pull --ff-only
/opt/glxss/venv/bin/pip install -e linux
sudo systemctl restart glxss-cast.service glxss-lcdhat.service
```

改了 `udev`/`config.txt` 时别忘了重新安装/重启。

---

## 11. 验收清单

- [ ] `ls -l /dev/spidev0.0` 存在，当前用户在 `spi`/`gpio` 组
- [ ] `glxss hud --dry-run --save-png` 生成的 PNG 是 640×400、图层完整
- [ ] `pi/lcdhat.py --preview` 的状态页/菜单页是 240×240
- [ ] 接眼镜后 `glxss list` 看到 `2e09:0030`
- [ ] `glxss hud --listen` 能开屏并显示 HUD
- [ ] `glxss ctl status` 返回 connected=true、亮度/固件/lux、view=hud
- [ ] HAT 状态页 1 秒内刷新，摇杆菜单各项能生效（VIEW 可切 HUD/MONITOR）
- [ ] `glxss ctl view monitor` 后眼镜显示相机画面 + 三分线/水平仪
- [ ] `glxss ctl view hud` 能切回 HUD
- [ ] 两个 systemd 服务都是 `active (running)`
- [ ] `vcgencmd get_throttled` 输出 `throttled=0x0`（无欠压）

---

## 12. 常见问题

### 12.1 SPI 不显示 / 黑屏

1. `ls -l /dev/spidev0.0` 不存在 → `dtparam=spi=on` 没生效或没重启。
2. `Permission denied` → 用户不在 `spi`/`gpio` 组，重新登录/重启。
3. 背光不亮 → BL（GPIO24）没置高、HAT 没插好、3V3 接错。
4. 有背光但全黑 → DC/RST 接反、SPI 频率太高、`fbtft` 抢占。
5. `--preview` 的 PNG 正常但面板黑 → 问题在 SPI/接线，不在渲染。
6. `spidev` 被占用 → 检查 `dmesg | grep -i -E 'fb|spi'`，注释掉 fbtft/flexfb。
7. 花屏/偏移 → 调 `--x-offset/--y-offset/--rotation`，见 §8.2。
8. 颜色反相 → `INVON`/`INVOFF`，见 §8.2。

### 12.2 USB 权限 / 枚举

```bash
lsusb | grep -i -E '2e09|03e7'
dmesg | tail -40
```

* 只有 `03e7:2150` → 眼镜在 **Myriad ROM 引导模式**，没有固件。这不是 HUD 的
  问题。需要主机先灌合法固件：

  ```bash
  # 在 PC/Linux 上（固件不随仓库分发）
  glxss flash G25_MainFW_1.9.1.img --yes
  ```

  灌完会重枚举为 `2e09:0030`。Pi 上也能灌，但 Zero 2 W 供电/线材更容易出问题，
  建议先在 PC 上恢复。
* `Access denied` / `LIBUSB_ERROR_ACCESS` → udev 规则没装/没 trigger；或设备被
  原厂 App、另一个 `glxss` 进程占用。
* HID 全 0 / `0xA1/0x16` 失败 → 内核 `hid-generic` 占着接口；kill 占用进程，
  加 `--detach`，或重新插拔。`glxss hid --enable-sensors --count 5` 可单独验证。
* 每次只跑一个 `glxss hud`/`glxss hid`；两个进程抢同一接口会互相打断。

### 12.3 眼镜进引导模式 / 假死

* 上电就是 `03e7:2150` 是正常的 ROM 引导，等主机灌固件；不是 Pi 配置问题。
* 正常模式但相机/显示流水线假死：控制面 ACK、帧照收，但画面不动；只能重新灌
  固件恢复。HUD 发帧失败会打印 `[警告] 帧失败`，连续失败 3 次退出。
* 不要在正常设备上随手发 `0x18` 系统复位。

### 12.4 Pi 供电不足

现象：Wi-Fi 掉线、USB 设备反复枚举、`dmesg` 出现 `Under-voltage detected`、
`vcgencmd get_throttled` 非 0。

处理：

```bash
vcgencmd get_throttled          # 0x0 才正常
vcgencmd measure_volts
```

* 用 5 V/2.5 A+ 电源，换短而粗的线；
* 眼镜不要从 Pi 的 5V 排针硬取大电流；
* 用**带供电的 USB Hub**，或给眼镜独立供电（共地）；
* 去掉不必要的 USB 外设/HDMI。

### 12.5 控制 socket 连不上

```bash
ls -l /run/glxss/glxssctl.sock
journalctl -u glxss-cast.service -n 50
/opt/glxss/venv/bin/python -m glxss ctl ping --socket /run/glxss/glxssctl.sock
```

* 两个进程的 `GLXSS_SOCK`/`--socket` 必须一致；
* 普通用户手动跑 HUD 时 `/run` 不可写 → 用 `/tmp/glxssctl-$UID.sock`；
* 残留 socket 会在启动时自动清理（只在确认连不上时）；
* `glxss-lcdhat.service` 用 `--no-spawn` 时，`glxss-cast.service` 必须已启动。

### 12.6 HUD 显示 NO MAG / NO IMU

* 传感器默认可能关闭；`glxss hud` 启动时会发 `0x10/0x11/0x12/0x14` 使能；
* 磁罗盘/IMU 需要真实数据；静止时 IMU 只有重力，heading 需要转动；
* 如果 `glxss hid --enable-sensors --count 5` 仍全 0，检查 HID claim/固件状态；
* heading 坐标系未标定，用 `--heading-offset` 或改代码里的
  `HudState.heading_offset`。

### 12.7 字体 / 中文

* HUD/HAT 界面用英文 + 系统 TrueType 字体，找不到会退回 Pillow 内置字体
  （会变小/变丑但不会崩）；
* 安装 `fonts-dejavu-core`（本文已装）即可；
* 日志是中文，串口/SSH 需要 UTF-8；Windows 终端乱码是代码页问题，不影响 PNG。

### 12.8 屏幕采集（mss）在 Lite 上失败

HUD 是默认源，不需要桌面。只有 `--source screen` 才需要 X11/XWayland；
Raspberry Pi OS Lite 没有桌面，直接用 HUD 或 `--source test`。

### 12.9 服务反复重启

```bash
journalctl -u glxss-cast.service -n 80
```

常见原因：找不到眼镜（`未找到 2e09:0030`）、udev/权限、`Pillow`/`numpy`
没装到 venv、`/run/glxss` 权限不对、旧进程占着 USB。修好后
`sudo systemctl restart glxss-cast.service`。

---

## 13. 卸载

```bash
sudo systemctl disable --now glxss-lcdhat.service glxss-cast.service
sudo rm -f /etc/systemd/system/glxss-lcdhat.service /etc/systemd/system/glxss-cast.service
sudo systemctl daemon-reload
sudo rm -f /etc/udev/rules.d/99-glxss.rules
sudo udevadm control --reload-rules
# 可选：删除代码/venv
sudo rm -rf /opt/glxss
```

`config.txt` 里的 `dtparam=spi=on` / `gpio=...=pu` 可以保留，不影响其他用途。

---

## 14. 参考

* 显示协议：`docs/ch-display.md`
* HID/传感器：`docs/ch-hid.md`
* UVC：`docs/ch-uvc.md`
* 固件灌写：`docs/ch-firmware.md`、`docs/firmware-boot-protocol.md`
* LCD HAT 引脚：<https://www.waveshare.com/wiki/1.3inch_LCD_HAT>
* 仓库 Linux 说明：`linux/README.md`
* HAT 简版说明：`linux/pi/README.md`
