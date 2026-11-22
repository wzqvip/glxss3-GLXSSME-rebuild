# Raspberry Pi Zero 2 W + Waveshare 1.3inch LCD HAT

从零安装（刷系统、SPI、接线、udev、systemd、排障）请看 **[SETUP.md](SETUP.md)**。
本页只放最短路径和日常操作。

目标：

* **眼镜 = 主显示**：`glxss hud --listen` 在 Pi 上渲染 640×400 HUD 并按帧发送；
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

---

## 4. 运行

### 4.1 HUD 主进程（眼镜）

```bash
cd /opt/glxss/linux
export GLXSS_SOCK=/tmp/glxssctl.sock
/opt/glxss/venv/bin/python -m glxss hud --listen --fps 2 --brightness 180
```

默认 2 fps、每 0.5 s 重绘一次；HID 300 ms 一包。HUD 内容：
时间/连接/fps、罗盘、lux/温度、姿态、亮度/固件、可选导航占位。

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

颜色反相：改 `pi/lcdhat.py` 的 `_INIT`，`0x21`(INVON) ↔ `0x20`(INVOFF)。

---

## 5. 菜单

**状态页**：PRESS 进菜单；KEY1 HUD 开关；KEY2 发 3 秒测试帧；KEY3 退出。

**菜单页**：

| 项 | LEFT/RIGHT | PRESS |
|---|---|---|
| BRIGHTNESS | 100..254 步进 10 | 下发亮度 |
| DISPLAY | 1=STANDBY / 2=CAST | 切显示模式 |
| SCREEN | ON/OFF | 开关屏（开屏自动 CAST） |
| HUD | ON/PAUSED | HUD 开关 |
| TEST FRAME | — | 发测试图案 3 秒 |
| EXIT | — | 退出（自动拉起的 HUD 子进程一起退出） |

UP/DOWN 选择，LEFT/RIGHT 调值，PRESS 确认，KEY3 返回。

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
* Pi Zero 2 W USB 供电有限；眼镜 + HAT 建议带供电 Hub 或独立供电。
* 本项目不修改眼镜固件；首次使用仍需主机向 `03e7:2150` 灌合法固件
  （`glxss flash IMAGE --yes`），详见 SETUP.md §12。
