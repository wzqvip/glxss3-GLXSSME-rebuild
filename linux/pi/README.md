# Raspberry Pi Zero 2 W + Waveshare 1.3inch LCD HAT

从零安装（刷系统、SPI、接线、udev、systemd、排障）请看 **[SETUP.md](SETUP.md)**。
本页只放最短路径和日常操作。

目标：

* **眼镜 = 主显示**：`glxss hud --listen` 在 Pi 上渲染 640×400 HUD 并按帧发送；
  菜单 VIEW 可切到 **MONITOR 相机监看**（眼镜 UVC → 眼镜显示）；
* **LCD HAT = 状态/控制面板**：240×240 状态页 + 菜单，不镜像眼镜画面；
* 两者通过 Unix socket（`glxss.control`）通信；HAT 默认会自动拉起 HUD 主进程。

```text
Pi: glxss hud --listen  ──USB──►  眼镜 640×400 HUD
        ▲
        │ /run/glxss/glxssctl.sock
        │
Pi: pi/lcdhat.py  ──SPI/GPIO──►  ST7789 240×240 + 摇杆/按键
```

---

## 1. 接线

HAT 直接插在 40-pin 排针上；杜邦线时注意 3.3 V，不要接 5 V 到 SPI 逻辑。

### LCD / ST7789

| 信号 | BCM GPIO | 物理引脚 | 说明 |
|---|---:|---:|---|
| SCLK | GPIO11 | 23 | SPI0 SCLK |
| MOSI | GPIO10 | 19 | SPI0 MOSI |
| CS | GPIO8 | 24 | SPI0 CE0（`/dev/spidev0.0`） |
| DC | GPIO25 | 22 | 命令/数据选择 |
| RST | GPIO27 | 13 | 复位 |
| BL | GPIO24 | 18 | 背光（代码置高） |
| 3V3 | — | 1 或 17 | HAT 供电 |
| GND | — | 6/9/14/20/25/30/34/39 | 共地 |

### 摇杆与按键（低电平有效，内部上拉）

| HAT 丝印 | BCM GPIO | 物理引脚 | 状态页功能 | 菜单页功能 |
|---|---:|---:|---|---|
| UP | GPIO6 | 31 | — | 上移 |
| DOWN | GPIO19 | 35 | — | 下移 |
| LEFT | GPIO5 | 29 | — | 调小/切换 |
| RIGHT | GPIO26 | 37 | — | 调大/切换 |
| PRESS | GPIO13 | 33 | 进入菜单 | 确认 |
| KEY1 | GPIO21 | 40 | HUD 开关 | — |
| KEY2 | GPIO20 | 38 | 发测试帧 | — |
| KEY3 | GPIO16 | 36 | 退出 | 返回状态页 |

---

## 2. 最短安装

```bash
sudo apt update
sudo apt install -y python3-venv python3-pil python3-numpy python3-spidev \
  libusb-1.0-0 fonts-dejavu-core git
sudo apt install -y python3-rpi.gpio      # 或 python3-lgpio / python3-libgpiod

sudo raspi-config nonint do_spi 0
# /boot/firmware/config.txt:
#   dtparam=spi=on
#   gpio=6,19,5,26,13,21,20,16=pu
sudo reboot

sudo mkdir -p /opt/glxss && sudo chown "$USER":"$USER" /opt/glxss
git clone -b linux <repo-url> /opt/glxss
python3 -m venv --system-site-packages /opt/glxss/venv
/opt/glxss/venv/bin/pip install -U pip
/opt/glxss/venv/bin/pip install -e /opt/glxss/linux

sudo cp /opt/glxss/linux/udev/99-glxss.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules && sudo udevadm trigger
sudo usermod -aG spi,gpio,video,plugdev "$USER"
```

完整步骤（含 SD 卡烧录、Imager 设置、udev、systemd、供电/排障）见
**[SETUP.md](SETUP.md)**。

---

## 3. 先离线验证

```bash
cd /opt/glxss/linux

# HUD 640×400 PNG（不需要眼镜）
/opt/glxss/venv/bin/python -m glxss hud --dry-run --save-png /tmp/hud.png

# HAT 240×240 状态页/菜单页（不需要 SPI/GPIO）
/opt/glxss/venv/bin/python pi/lcdhat.py --preview /tmp/hat
```

把 PNG `scp` 回开发机核对图层/字体，再上硬件。

上了硬件先跑自检（不连眼镜、不开 HUD，只验证屏幕和按键）：

```bash
sudo systemctl stop glxss-lcdhat.service   # 让出自检要用的 GPIO
/opt/glxss/venv/bin/python pi/lcdhat_selftest.py colors   # 红/绿/蓝/白/黑
/opt/glxss/venv/bin/python pi/lcdhat_selftest.py text     # 识别页，停留 25 s
/opt/glxss/venv/bin/python pi/lcdhat_selftest.py keys     # 打印按键事件
```

---

## 4. 运行

### 4.1 HUD 主进程（眼镜）

```bash
cd /opt/glxss/linux
export GLXSS_SOCK=/tmp/glxssctl.sock
/opt/glxss/venv/bin/python -m glxss hud --listen --detach --fps 2 --brightness 180
```

默认 2 fps；HID 100 ms 轮询。HUD 内容：时间/连接/fps、罗盘、lux/传感器状态、
姿态、亮度/固件、可选导航占位（**不再显示温度**；罗盘无数据时显示 `--`）。

`--detach` 是为了让运行中的 VIEW → MONITOR 能打开眼镜 UVC（uvcvideo 会占着
VS 接口）。相机监看：

```bash
# 在 HUD 主进程里切换（推荐；HAT 菜单 VIEW 同效）
/opt/glxss/venv/bin/python -m glxss ctl view monitor
/opt/glxss/venv/bin/python -m glxss ctl view hud
/opt/glxss/venv/bin/python -m glxss ctl rec on

# 或者独立跑，不启动 HUD 主进程
/opt/glxss/venv/bin/python -m glxss cast --source uvc --fps 10 \
    --detach --rotate 180 --rec
```

监看叠加三分线、水平仪（HID 加速度计）和可选 REC 红点/时间；默认 cover
裁切（640×480 → 640×400，不缩放）。Pi Zero 2 W 上若帧率/延迟不理想，
加 `--uvc-half`（先解 320×240 再放大）。

### 4.2 HAT 控制器

```bash
# HUD 已在跑：只连 socket
/opt/glxss/venv/bin/python pi/lcdhat.py --socket "$GLXSS_SOCK"

# HUD 没跑：默认自动拉起子进程（--no-spawn 可禁用）
/opt/glxss/venv/bin/python pi/lcdhat.py --socket "$GLXSS_SOCK"
```

面板偏移：

```bash
--x-offset 0 --y-offset 80     # 常见
--x-offset 80 --y-offset 0     # 另一批面板
--rotation 1                   # 旋转
```

颜色反相：`--invert`（默认，发 INVON）或 `--no-invert`（发 INVOFF）。
红蓝互换说明面板批次不同，不用改代码。

实测过的整组参数见 [§8 实测参数](#8-实参数实测)。

---

## 5. 菜单

**状态页**：PRESS 进菜单；KEY1 HUD 开关；KEY2 发 3 秒测试帧；KEY3 退出。
状态页新增 **VIEW** 行（HUD / MONITOR），不再显示 TEMP。

**菜单页**（7 项）：

| 项 | LEFT/RIGHT | PRESS |
|---|---|---|
| BRIGHTNESS | 100..254 步进 10 | 下发亮度 |
| VIEW | HUD / MONITOR | 切相机监看（眼镜 UVC → 眼镜显示） |
| DISPLAY | 1=STANDBY / 2=CAST | 切显示模式 |
| SCREEN | ON/OFF | 开关屏（开屏自动 CAST） |
| HUD | ON/PAUSED | HUD 开关 |
| TEST FRAME | — | 发测试图案 3 秒（自动切回 HUD） |
| EXIT | — | 退出（自动拉起的 HUD 子进程一起退出） |

UP/DOWN 选择，LEFT/RIGHT 调值，PRESS 确认，KEY3 返回。
**眼镜 Fn 键单击**也可在 HUD / MONITOR 之间切换，不占用 HAT 按键。

`glxss ctl` 也能直接控制主进程：

```bash
export GLXSS_SOCK=/tmp/glxssctl.sock
/opt/glxss/venv/bin/python -m glxss ctl status
/opt/glxss/venv/bin/python -m glxss ctl bright 200
/opt/glxss/venv/bin/python -m glxss ctl hud off
/opt/glxss/venv/bin/python -m glxss ctl test 5
/opt/glxss/venv/bin/python -m glxss ctl nav 72 --distance 320 --label "Main St"
/opt/glxss/venv/bin/python -m glxss ctl quit
```

---

## 6. systemd

两个 unit：

* `service/glxss-cast.service`：HUD 主进程 + `/run/glxss/glxssctl.sock`；
  模板已带 `--detach`（monitor 模式需要），画面颠倒时加 `--rotate 180`；
* `service/glxss-lcdhat.service`：HAT，`--no-spawn` 只连 socket。

```bash
sudo cp /opt/glxss/linux/pi/service/glxss-cast.service /etc/systemd/system/
sudo cp /opt/glxss/linux/pi/service/glxss-lcdhat.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now glxss-cast.service glxss-lcdhat.service
systemctl status glxss-cast.service
journalctl -u glxss-cast.service -f
```

按实际用户名/路径改 `User`、`WorkingDirectory`、`ExecStart`。

---

## 7. 已知限制

* HUD 的罗盘 heading/姿态公式按 `docs/ch-hid.md` 约定实现，**未做磁罗盘
  硬铁/软铁校准**，真机方向可能需要 `--heading-offset` 微调。
* ST7789 240×240 的 offset/反相随批次不同，需要按 §4 调。
* `--source screen` 需要 X11/XWayland；Raspberry Pi OS Lite 用 HUD/test。
* monitor（UVC）在 Linux 需要 `--detach` 摘掉 uvcvideo；同一时间只能有一个
  进程占用眼镜摄像头（HUD 主进程切监看，或独立 `cast --source uvc`）。
* 相机监看的发送帧率受 LCD bulk OUT 吞吐限制；默认 cover 解码约 44 ms/帧
  （Pi Zero 2 W @1 GHz），帧率不够时用 `--uvc-half`（约 17 ms/帧）。
  本机实测 `cast --source uvc --fps 10 --duration 10 --detach --rotate 180`
  的 LCD 发送稳定 10.0 fps（10.2 MB/s、30–32 ms/帧）。
* 若 probe/commit 成功但 UVC 0 帧（读错误 `Errno 110`），是固件媒体流水线
  停摆，先按 `docs/device-recovery.md` 拔插+重灌；不是 monitor 代码问题。
* Pi Zero 2 W USB 供电有限；眼镜 + HAT 建议带供电 Hub 或独立供电。
* 本项目不修改眼镜固件；首次使用仍需主机向 `03e7:2150` 灌合法固件
  （`glxss flash IMAGE --yes`），详见 SETUP.md §12。

---

## 8. 实测参数

在 `rpi0w2`（Pi Zero 2 W Rev 1.0）上一次真机点亮的环境与参数，供同批次 HAT 直接抄：

| 项 | 实测值 | 备注 |
|---|---|---|
| OS / kernel | Debian 12 bookworm / 6.12.109+rpt-rpi-v8 aarch64 | |
| Python | 3.11.2 | |
| 驱动库 | spidev 3.5、Pillow 9.4.0、numpy 1.24.2 | apt 包，非 pip |
| GPIO 后端 | `RPi.GPIO`（RPi.GPIO 0.7.1） | lgpio / gpiod 为兜底 |
| SPI 设备 | `/dev/spidev0.0` | `/boot/firmware/config.txt` 里 `dtparam=spi=on` |
| SPI 频率 | **40 MHz** | 满屏 115200 B ≈ 23 ms，无花屏 |
| x offset | **0** | |
| y offset | **80** | 240×240 面板在 240×320 GRAM 里的起始行 |
| rotation | **0** | 0..3 对应 MADCTL 0x00/0x60/0xC0/0xA0；旋转 1/3 会自动交换 x/y offset |
| invert | **INVON（0x21）** | 默认值；若红蓝互换改 `--no-invert` |
| DC / RST / BL | 25 / 27 / 24 | 背光高电平有效 |
| 按键 | 内部上拉、低有效 | UP=6 DOWN=19 LEFT=5 RIGHT=26 PRESS=13 KEY1=21 KEY2=20 KEY3=16 |

**按键实测**：UP、DOWN、LEFT、RIGHT、PRESS、KEY1、KEY2、KEY3 八个键全部在运行中的
`glxss-lcdhat.service` 里捕获到（`journalctl -u glxss-lcdhat.service` 里的
`按键: <name>` 行），无需额外消抖参数，`RPi.GPIO` 的 BCM 上拉即可直接读。

配置 `config.txt` 前已备份为
`/boot/firmware/config.txt.bak.YYYYmmdd-HHMMSS`；本次未改其它系统配置。

实际部署（用户名 `taco`，目录 `/home/taco/glxss3-linux`，装的是改过路径的
unit 副本，`pi/service/glxss-lcdhat.service` 本身仍是 `/opt/glxss` 模板）：

```ini
[Service]
User=taco
SupplementaryGroups=spi gpio video
WorkingDirectory=/home/taco/glxss3-linux
Environment=PYTHONPATH=/home/taco/glxss3-linux
Environment=GLXSS_SOCK=/run/glxss/glxssctl.sock
ExecStart=/usr/bin/python3 /home/taco/glxss3-linux/pi/lcdhat.py \
    --socket /run/glxss/glxssctl.sock --no-spawn \
    --rotation 0 --x-offset 0 --y-offset 80 --spi-hz 40000000
```

`--detach` 加在 `glxss-cast.service` 的 ExecStart 里（monitor 需要），
`--no-spawn` 是刻意的：眼镜不在线时 `glxss hud --listen` 会直接退出，
HAT 只连 socket、显示 `GLASSES OFFLINE`，所以**没眼镜也能正常当状态面板用**。
`glxss-cast.service` 也**没有**在本机启用（无眼镜时会反复重启），插上眼镜后再
`systemctl enable --now glxss-cast.service` 即可让状态页变成 ONLINE。
