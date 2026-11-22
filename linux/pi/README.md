# Raspberry Pi Zero 2 W + Waveshare 1.3inch LCD HAT

目标：Pi Zero 2 W 作为便携投屏/控制端，1.3 寸 240×240 屏显示眼镜连接状态、
FPS、亮度、模式，摇杆/按键直接控制眼镜。

> 引脚以 Waveshare 官方 wiki 为准：
> <https://www.waveshare.com/wiki/1.3inch_LCD_HAT>
> 控制器 ST7789VM，240×240 IPS，SPI，3.3 V。

---

## 1. 接线表（HAT 直接插在 40-pin 排针上）

### 1.1 LCD / ST7789

| 信号 | BCM GPIO | 物理引脚 | 说明 |
|---|---:|---:|---|
| SCLK | GPIO11 | 23 | SPI0 SCLK |
| MOSI | GPIO10 | 19 | SPI0 MOSI |
| CS | GPIO8 | 24 | SPI0 CE0（`/dev/spidev0.0`） |
| DC | GPIO25 | 22 | 命令/数据选择 |
| RST | GPIO27 | 13 | 复位 |
| BL | GPIO24 | 18 | 背光（代码里置高） |
| 3V3 | — | 1 或 17 | HAT 供电 |
| GND | — | 6/9/14/20/25/30/34/39 | 共地 |

> HAT 直接插上即可；如果使用杜邦线，确保 3.3 V，不要接 5 V 到 SPI 逻辑。
> 屏只使用 CE0；`--spi-device 1` 仅在你自行改接 CE1 时使用。

### 1.2 摇杆与按键

| HAT 丝印 | BCM GPIO | 物理引脚 | 本程序功能 |
|---|---:|---:|---|
| Joystick UP | GPIO6 | 31 | 亮度 +20 |
| Joystick DOWN | GPIO19 | 35 | 亮度 −20 |
| Joystick LEFT | GPIO5 | 29 | 显示模式 1（待机） |
| Joystick RIGHT | GPIO26 | 37 | 显示模式 2（投屏） |
| Joystick PRESS | GPIO13 | 33 | 开/关屏 |
| KEY1 | GPIO21 | 40 | 切换 fit / cover / stretch |
| KEY2 | GPIO20 | 38 | 切换 screen / test 采集源 |
| KEY3 | GPIO16 | 36 | 退出程序 |

全部按键**低电平有效**，使用内部上拉；按下时读到 0。

---

## 2. 打开 SPI 与上拉

```bash
sudo raspi-config
# Interface Options → SPI → Yes
# 或非交互：
sudo raspi-config nonint do_spi 0
```

检查：

```bash
ls -l /dev/spidev0.0
```

Raspberry Pi OS Bookworm 的配置文件是 `/boot/firmware/config.txt`，旧版是
`/boot/config.txt`。确保有：

```ini
dtparam=spi=on
```

Waveshare 官方示例还建议为按键启用内部上拉（尤其 Pi 4B / 新系统）：

```ini
gpio=6,19,5,26,13,21,20,16=pu
```

改完重启：

```bash
sudo reboot
```

> 如果系统已经加载了 fbtft/flexfb 驱动去占用 SPI，本程序会打不开
> `/dev/spidev0.0`；请按 Waveshare wiki 的说明注释掉 `flexfb`/`fbtft_device`，
> 或不要在同一个 SPI 上同时启用 framebuffer 驱动。

---

## 3. 依赖

```bash
sudo apt update
sudo apt install -y \
  python3-venv python3-pil python3-numpy python3-spidev \
  libusb-1.0-0 fonts-dejavu-core

# GPIO 后端三选一（程序会自动探测 RPi.GPIO → lgpio → gpiod）
sudo apt install -y python3-rpi.gpio
# 或：sudo apt install -y python3-lgpio
# 或：sudo apt install -y python3-libgpiod

# GPIO/SPI/USB 权限
sudo usermod -aG spi,gpio,video "$USER"
```

把仓库放到 `/opt/glxss`（路径可按需修改）：

```bash
sudo mkdir -p /opt/glxss
sudo chown "$USER":"$USER" /opt/glxss
# 从开发机复制仓库，或 git clone 后 checkout linux 分支
git clone <repo-url> /opt/glxss
cd /opt/glxss && git checkout linux
```

创建虚拟环境（`--system-site-packages` 让 venv 用上 apt 装的 Pillow/numpy/spidev）：

```bash
cd /opt/glxss/linux
python3 -m venv --system-site-packages /opt/glxss/venv
/opt/glxss/venv/bin/pip install -U pip
/opt/glxss/venv/bin/pip install -e .
```

装 udev 规则（让普通用户访问眼镜）：

```bash
sudo cp /opt/glxss/linux/udev/99-glxss.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules
sudo udevadm trigger
```

重新登录一次，让 `spi/gpio/video` 组生效，然后：

```bash
groups
/opt/glxss/venv/bin/python -c "import spidev, PIL, numpy; print('deps ok')"
```

---

## 4. 先跑状态页

第一次建议不投屏、只显示状态页，用来确认接线和面板 offset：

```bash
cd /opt/glxss/linux
/opt/glxss/venv/bin/python pi/lcdhat.py --no-cast --source test
```

* 默认 ST7789 窗口 offset 是 `x=0, y=80`（常见 240×240 面板）。
  如果画面整体偏移，试：

```bash
--x-offset 0 --y-offset 80
--x-offset 80 --y-offset 0
--rotation 1
```

* 如果黑屏：检查 BL（GPIO24）是否被拉高、RST/DC 是否接反、SPI 是否开启、
  是否被 fbtft 占用。
* 如果颜色反相/偏色：不同批次面板可能需要 `INVON` 开关或不同的初始化序列；
  当前使用常见 ST7789 240×240 序列，先在 `pi/lcdhat.py` 的 `_INIT` 里微调。
* 状态页是英文，避免 Pi 默认字体缺中文字形。

然后投屏：

```bash
# 没有桌面/X11：用测试图案（推荐先这样验证整条链路）
/opt/glxss/venv/bin/python pi/lcdhat.py --source test --fps 15

# 有桌面/X11（mss 采集）：屏幕投屏
/opt/glxss/venv/bin/python pi/lcdhat.py --source screen --fps 10
```

`--source screen` 在 Raspberry Pi OS Lite 上会因为找不到 X11 失败，程序会回退到
test 图案并在状态页显示错误。若要真正采集 Pi 桌面，需要安装桌面环境/启动 X11，
或改用带 XWayland 的 Wayland 会话。

---

## 5. 按键

| 操作 | 效果 |
|---|---|
| 摇杆 ← / → | 显示模式 1（待机）/ 2（投屏） |
| 摇杆按下 | 开/关屏；开屏时自动切回投屏模式 |
| 摇杆 ↑ / ↓ | 亮度 +20 / −20（夹在 100..254） |
| KEY1 | fit → cover → stretch |
| KEY2 | screen ↔ test 采集源 |
| KEY3 | 退出（systemd 下正常退出不会自动重启） |

每次按键都会刷新状态页的 `KEY` 行，并打印到 journal。

---

## 6. systemd 开机自启

示例 unit 在 [`service/glxss-cast.service`](service/glxss-cast.service)，
默认假设：

* 用户名 `pi`
* 仓库 `/opt/glxss`
* venv `/opt/glxss/venv`
* 采集源 `screen`

按实际情况改 `User`、`WorkingDirectory`、`ExecStart`。**Lite 系统**把
`--source screen` 改成 `--source test`。

```bash
sudo cp /opt/glxss/linux/pi/service/glxss-cast.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now glxss-cast
systemctl status glxss-cast
journalctl -u glxss-cast -f
```

停止/重启：

```bash
sudo systemctl stop glxss-cast
sudo systemctl restart glxss-cast
```

> unit 里 `SupplementaryGroups=spi gpio video` 让服务无需 root 就能访问
> SPI、GPIO 和可能的 video 设备。若你的系统组名不同，用 `ls -l /dev/spidev0.0`
> 和 `ls -l /dev/gpiomem` 查看实际组并修改。

---

## 7. 已知限制

* 状态页每次全屏刷新 240×240，SPI 40 MHz 下开销很小；但不要和 fbtft 同时用。
* 当前驱动针对 ST7789 240×240 的常见初始化；不同批次面板的 offset/反相可能不同。
* `mss` 采集需要 X11/XWayland；纯 Wayland/Lite 用 test 图案。
* 树莓派 Zero 2 W 的 USB 供电有限；眼镜 + HAT 建议用带供电的 USB Hub 或独立供电。
* 本项目不修改眼镜固件；首次使用仍需要主机向 `03e7:2150` 灌入合法固件
  （`glxss flash IMAGE --yes`）。
