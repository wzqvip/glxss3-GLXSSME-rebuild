#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""树莓派 Waveshare 1.3inch LCD HAT：240×240 状态页 + 菜单 + 眼镜 HUD 控制。

定位（重要）：
  * LCD HAT 是**状态/控制面板**，不是眼镜画面的镜像；
  * 眼镜由 `glxss hud --listen` 主进程渲染 HUD 并发送；
  * 本程序通过 Unix socket（`glxss.control`）读状态、发命令，默认在 socket
    不存在时自动拉起一个 `python -m glxss hud --listen` 子进程。

硬件（Waveshare 官方 wiki）：
  * ST7789 SPI：SCLK=BCM11、MOSI=BCM10、CS=BCM8(CE0)、DC=BCM25、RST=BCM27、BL=BCM24
  * 摇杆/按键：UP=6、DOWN=19、LEFT=5、RIGHT=26、PRESS=13、
               KEY1=21、KEY2=20、KEY3=16（低电平有效，内部上拉）

操作：
  状态页：PRESS 进菜单；KEY1 HUD 开关；KEY2 发测试帧；KEY3 退出。
  菜单页：UP/DOWN 选项；LEFT/RIGHT 调值；PRESS 确认；KEY3 返回。

无树莓派硬件时可以用 `--preview` 生成状态页/菜单页 PNG：
    python pi/lcdhat.py --preview /tmp/glxss-hat-preview
"""

from __future__ import annotations

import argparse
import os
import struct
import subprocess
import sys
import time
from typing import Dict, List, Optional, Tuple

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from glxss.control import ControlClient, default_socket_path  # noqa: E402
from glxss.errors import GlxssError  # noqa: E402

try:  # 只在真正运行 Pi 界面时必需
    from PIL import Image, ImageDraw, ImageFont  # type: ignore
except Exception:  # pragma: no cover
    Image = None  # type: ignore
    ImageDraw = None  # type: ignore
    ImageFont = None  # type: ignore

try:
    import spidev  # type: ignore
except Exception:  # pragma: no cover
    spidev = None  # type: ignore

try:
    import numpy as np  # type: ignore
except Exception:  # pragma: no cover
    np = None  # type: ignore


LCD_W = 240
LCD_H = 240

# Waveshare 官方 wiki Pinout（BCM 编号）
PIN_LCD_SCLK = 11
PIN_LCD_MOSI = 10
PIN_LCD_CS = 8
PIN_LCD_DC = 25
PIN_LCD_RST = 27
PIN_LCD_BL = 24

BUTTON_PINS = {
    "up": 6,
    "down": 19,
    "left": 5,
    "right": 26,
    "press": 13,
    "key1": 21,
    "key2": 20,
    "key3": 16,
}


# ---------------------------------------------------------------------------
# GPIO 后端：RPi.GPIO → lgpio → gpiod
# ---------------------------------------------------------------------------
class Gpio:
    """最小 GPIO 封装；优先 RPi.GPIO，树莓派 Bookworm 可用 lgpio 兜底。"""

    def __init__(self):
        self.backend = ""
        self._gpio = None
        self._lgpio = None
        self._lgpio_handle = None
        self._gpiod = None
        self._lines: Dict[int, object] = {}
        try:
            import RPi.GPIO as GPIO  # type: ignore
            GPIO.setwarnings(False)
            GPIO.setmode(GPIO.BCM)
            self._gpio = GPIO
            self.backend = "RPi.GPIO"
            return
        except Exception:
            pass
        try:
            import lgpio  # type: ignore
            self._lgpio = lgpio
            self._lgpio_handle = lgpio.gpiochip_open(0)
            self.backend = "lgpio"
            return
        except Exception:
            pass
        try:
            import gpiod  # type: ignore
            if not hasattr(gpiod, "Chip"):
                raise RuntimeError("gpiod v2 不受支持，请用 RPi.GPIO 或 lgpio")
            self._gpiod = gpiod
            self._chip = gpiod.Chip("gpiochip0")
            self.backend = "gpiod"
            return
        except Exception as exc:
            raise GlxssError(
                "没有可用的 GPIO 后端（RPi.GPIO / lgpio / gpiod）。\n"
                "  sudo apt install python3-rpi.gpio   # 或 python3-libgpiod / python3-lgpio\n"
                "  并确认用户属于 gpio 组。原始错误: %s" % exc)

    def setup_output(self, pin: int, initial: int = 0) -> None:
        if self._gpio is not None:
            self._gpio.setup(pin, self._gpio.OUT, initial=initial)
        elif self._lgpio is not None:
            self._lgpio.gpio_claim_output(self._lgpio_handle, pin, initial)
        else:
            line = self._chip.get_line(pin)
            line.request(consumer="glxss-lcdhat", type=self._gpiod.LINE_REQ_DIR_OUT,
                         default_vals=[initial])
            self._lines[pin] = line

    def setup_input(self, pin: int, pull_up: bool = True) -> None:
        if self._gpio is not None:
            pull = self._gpio.PUD_UP if pull_up else self._gpio.PUD_DOWN
            self._gpio.setup(pin, self._gpio.IN, pull_up_down=pull)
        elif self._lgpio is not None:
            flags = self._lgpio.SET_PULL_UP if pull_up else self._lgpio.SET_PULL_DOWN
            self._lgpio.gpio_claim_input(self._lgpio_handle, pin, flags)
        else:
            flags = self._gpiod.LINE_REQ_FLAG_BIAS_PULL_UP if pull_up else 0
            line = self._chip.get_line(pin)
            line.request(consumer="glxss-lcdhat", type=self._gpiod.LINE_REQ_DIR_IN,
                         flags=flags)
            self._lines[pin] = line

    def output(self, pin: int, value: int) -> None:
        value = 1 if value else 0
        if self._gpio is not None:
            self._gpio.output(pin, value)
        elif self._lgpio is not None:
            self._lgpio.gpio_write(self._lgpio_handle, pin, value)
        else:
            self._lines[pin].set_value(value)

    def input(self, pin: int) -> int:
        if self._gpio is not None:
            return int(self._gpio.input(pin))
        if self._lgpio is not None:
            return int(self._lgpio.gpio_read(self._lgpio_handle, pin))
        return int(self._lines[pin].get_value())

    def close(self) -> None:
        try:
            if self._gpio is not None:
                self._gpio.cleanup()
            elif self._lgpio is not None and self._lgpio_handle is not None:
                self._lgpio.gpiochip_close(self._lgpio_handle)
            else:
                for line in self._lines.values():
                    line.release()
                if getattr(self, "_chip", None) is not None:
                    self._chip.close()
        except Exception:
            pass
        self._lines = {}


class Buttons:
    def __init__(self, gpio: Gpio, debounce: float = 0.2):
        self.gpio = gpio
        self.debounce = debounce
        self._last: Dict[str, bool] = {}
        self._last_at: Dict[str, float] = {}
        for pin in BUTTON_PINS.values():
            gpio.setup_input(pin, pull_up=True)
        for name, pin in BUTTON_PINS.items():
            self._last[name] = gpio.input(pin) == 0
        self.last_event = ""
        self.last_event_at = 0.0

    def poll(self) -> List[str]:
        """返回本次新按下的按键名（已消抖）。"""
        now = time.monotonic()
        events = []
        for name, pin in BUTTON_PINS.items():
            pressed = self.gpio.input(pin) == 0
            if (pressed and not self._last[name]
                    and now - self._last_at.get(name, 0) >= self.debounce):
                events.append(name)
                self._last_at[name] = now
                self.last_event = name
                self.last_event_at = now
            self._last[name] = pressed
        return events


# ---------------------------------------------------------------------------
# ST7789 240×240
# ---------------------------------------------------------------------------
class ST7789:
    # 常见 ST7789 240×240 初始化序列（RGB565，SPI mode 0）。
    _INIT = [
        (0x01, b"", 0.15),      # SWRESET
        (0x11, b"", 0.12),      # SLPOUT
        (0x3A, b"\x55", 0.0),   # COLMOD: 16-bit
        (0xB2, b"\x0c\x0c\x00\x33\x33", 0.0),
        (0xB7, b"\x35", 0.0),
        (0xBB, b"\x19", 0.0),
        (0xC0, b"\x2c", 0.0),
        (0xC2, b"\x01", 0.0),
        (0xC3, b"\x12", 0.0),
        (0xC4, b"\x20", 0.0),
        (0xC6, b"\x0f", 0.0),
        (0xD0, b"\xa4\xa1", 0.0),
        (0xE0, b"\xd0\x04\x11\x0b\x0b\x14\x2a\x30\x42\x4b\x56\x5b\x62\x6c", 0.0),
        (0xE1, b"\xd0\x04\x10\x0b\x0b\x13\x29\x31\x43\x4b\x55\x5b\x62\x6c", 0.0),
        # INVON/INVOFF 由 invert 参数在 _init_panel 里于 DISPON 前写入。
        (0x13, b"", 0.0),       # NORON
        (0x29, b"", 0.08),      # DISPON
    ]

    _MADCTL = {0: 0x00, 1: 0x60, 2: 0xC0, 3: 0xA0}

    def __init__(self, gpio: Gpio, bus: int = 0, device: int = 0,
                 speed_hz: int = 40_000_000, dc: int = PIN_LCD_DC,
                 rst: int = PIN_LCD_RST, bl: int = PIN_LCD_BL,
                 x_offset: int = 0, y_offset: int = 80,
                 rotation: int = 0, invert: bool = True,
                 verbose: bool = False):
        if spidev is None:
            raise GlxssError("缺少 spidev：sudo apt install python3-spidev")
        if Image is None:
            raise GlxssError("缺少 Pillow：sudo apt install python3-pil")
        if rotation not in (0, 1, 2, 3):
            raise GlxssError("--rotation 必须是 0..3")
        self.gpio = gpio
        self.dc = dc
        self.rst = rst
        self.bl = bl
        self.x_offset = x_offset
        self.y_offset = y_offset
        self.rotation = rotation
        self.invert = bool(invert)
        self.verbose = verbose
        if rotation in (1, 3):
            self.x_offset, self.y_offset = y_offset, x_offset
        self.spi = spidev.SpiDev()
        self.spi.open(bus, device)
        self.spi.max_speed_hz = speed_hz
        self.spi.mode = 0
        gpio.setup_output(dc, 0)
        gpio.setup_output(rst, 0)
        gpio.setup_output(bl, 0)
        self._hardware_reset()
        self._init_panel()
        gpio.output(bl, 1)
        self.fill((0, 0, 0))

    def _hardware_reset(self) -> None:
        self.gpio.output(self.rst, 0)
        time.sleep(0.02)
        self.gpio.output(self.rst, 1)
        time.sleep(0.12)

    def _write_bytes(self, data: bytes) -> None:
        if not data:
            return
        if hasattr(self.spi, "writebytes2"):
            self.spi.writebytes2(data)
        else:  # 旧 spidev
            self.spi.writebytes(list(data))

    def _cmd(self, command: int, data: bytes = b"") -> None:
        self.gpio.output(self.dc, 0)
        self._write_bytes(bytes([command]))
        if data:
            self.gpio.output(self.dc, 1)
            self._write_bytes(data)

    def _init_panel(self) -> None:
        for command, data, delay in self._INIT:
            # 打开显示前按面板批次决定是否反相：IPS 屏常需 INVON，否则红蓝互换。
            if command == 0x29:
                self._cmd(0x21 if self.invert else 0x20)
            self._cmd(command, data)
            # SWRESET 会清掉 MADCTL；必须在 SLPOUT 之后再写。
            if command == 0x11:
                self._cmd(0x36, bytes([self._MADCTL[self.rotation]]))
            if delay:
                time.sleep(delay)
        if self.verbose:
            print("ST7789 初始化完成，rotation=%d offsets=(%d,%d) invert=%s" % (
                self.rotation, self.x_offset, self.y_offset, self.invert))

    def _window(self) -> None:
        x0, y0 = self.x_offset, self.y_offset
        x1, y1 = x0 + LCD_W - 1, y0 + LCD_H - 1
        self._cmd(0x2A, struct.pack(">HH", x0, x1))
        self._cmd(0x2B, struct.pack(">HH", y0, y1))
        self._cmd(0x2C)

    @staticmethod
    def _rgb565(image) -> bytes:
        image = image.convert("RGB").resize((LCD_W, LCD_H), Image.BILINEAR)
        if np is not None:
            arr = np.asarray(image, dtype=np.uint16)
            packed = (((arr[:, :, 0] >> 3) << 11) |
                      ((arr[:, :, 1] >> 2) << 5) |
                      (arr[:, :, 2] >> 3)).astype(">u2")
            return packed.tobytes()
        raw = image.tobytes()
        out = bytearray(LCD_W * LCD_H * 2)
        pos = 0
        for i in range(0, len(raw), 3):
            r, g, b = raw[i], raw[i + 1], raw[i + 2]
            value = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)
            out[pos:pos + 2] = struct.pack(">H", value)
            pos += 2
        return bytes(out)

    def show(self, image) -> None:
        data = self._rgb565(image)
        self._window()
        self.gpio.output(self.dc, 1)
        self._write_bytes(data)

    def fill(self, color: Tuple[int, int, int]) -> None:
        self.show(Image.new("RGB", (LCD_W, LCD_H), color))

    def close(self) -> None:
        try:
            self.gpio.output(self.bl, 0)
        except Exception:
            pass
        try:
            self.spi.close()
        except Exception:
            pass


# ---------------------------------------------------------------------------
# 状态页 / 菜单页
# ---------------------------------------------------------------------------
MENU_ITEMS = (
    ("brightness", "BRIGHTNESS"),
    ("mode", "DISPLAY"),
    ("screen", "SCREEN"),
    ("hud", "HUD"),
    ("test", "TEST FRAME"),
    ("exit", "EXIT"),
)


class Pages:
    """240×240 英文界面；避免 Pi 默认字体缺中文字形。"""

    def __init__(self):
        self._font_cache: Dict[Tuple[int, bool], object] = {}
        self.last_state = {}

    def _font(self, size: int, bold: bool = False):
        key = (int(size), bool(bold))
        if key in self._font_cache:
            return self._font_cache[key]
        font = None
        candidates = [
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf" if bold else
            "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
            "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf" if bold else
            "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
        ]
        for path in candidates:
            if not path or not os.path.exists(path):
                continue
            try:
                font = ImageFont.truetype(path, int(size))
                break
            except Exception:
                continue
        if font is None:
            try:
                font = ImageFont.load_default(size=int(size))
            except TypeError:
                font = ImageFont.load_default()
        self._font_cache[key] = font
        return font

    def _text_width(self, draw, text: str, font) -> int:
        try:
            return int(draw.textlength(text, font=font))
        except Exception:
            box = draw.textbbox((0, 0), text, font=font)
            return box[2] - box[0]

    def _right(self, draw, right: int, y: int, text: str, font, fill) -> None:
        draw.text((right - self._text_width(draw, text, font), y),
                  text, font=font, fill=fill)

    def status(self, state: dict, error: str = "", notice: str = ""):
        image = Image.new("RGB", (LCD_W, LCD_H), (10, 15, 24))
        draw = ImageDraw.Draw(image)
        title = self._font(16, bold=True)
        font = self._font(13)
        small = self._font(10)
        tiny = self._font(8)

        draw.rectangle((0, 0, LCD_W, 32), fill=(24, 92, 156))
        draw.text((10, 6), "GLXSS HAT", font=title, fill=(255, 255, 255))
        dot = (48, 220, 120) if state.get("connected") else (235, 72, 84)
        draw.ellipse((208, 9, 224, 25), fill=dot)
        draw.text((10, 34), "GLASSES", font=small, fill=(130, 142, 158))
        draw.text((78, 32), "ONLINE" if state.get("connected") else "OFFLINE",
                  font=font, fill=dot)

        rows = [
            ("FPS", "%.1f" % float(state.get("fps", 0.0)), (225, 230, 238)),
            ("BRIGHT", str(state.get("brightness", "-")), (225, 230, 238)),
            ("HUD", "ON" if state.get("hud") else "PAUSED",
             (120, 230, 150) if state.get("hud") else (255, 190, 72)),
            ("LUX", "%s lx" % state.get("lux", "-"), (255, 208, 120)),
            ("TEMP", "%.1f C" % float(state.get("temperature", 0.0)),
             (255, 208, 120)),
            ("MODE", "CAST" if state.get("mode") == 2 else "STANDBY",
             (225, 230, 238)),
            ("TIME", state.get("time") or time.strftime("%H:%M:%S"),
             (190, 200, 214)),
        ]
        y = 54
        for label, value, color in rows:
            draw.text((10, y + 2), label, font=small, fill=(120, 132, 148))
            draw.text((78, y), str(value), font=font, fill=color)
            y += 21

        draw.line((8, 202, LCD_W - 8, 202), fill=(44, 62, 82), width=1)
        if error:
            draw.text((8, 207), "ERR " + error[:33], font=tiny, fill=(255, 110, 120))
            draw.text((8, 220), "K3 QUIT   PRESS MENU", font=tiny,
                      fill=(130, 140, 156))
        else:
            draw.text((8, 207), "PRESS MENU  K1 HUD", font=tiny,
                      fill=(130, 140, 156))
            draw.text((8, 220), notice or "K2 TEST  K3 QUIT", font=tiny,
                      fill=(190, 160, 90) if notice else (130, 140, 156))
        self.last_state = state
        return image

    def menu(self, state: dict, index: int, edit, notice: str = ""):
        image = Image.new("RGB", (LCD_W, LCD_H), (10, 15, 24))
        draw = ImageDraw.Draw(image)
        title = self._font(15, bold=True)
        font = self._font(12)
        small = self._font(9)

        draw.rectangle((0, 0, LCD_W, 30), fill=(24, 92, 156))
        draw.text((10, 5), "MENU", font=title, fill=(255, 255, 255))
        self._right(draw, LCD_W - 10, 8, "%d/%d" % (index + 1, len(MENU_ITEMS)),
                    small, (210, 228, 245))

        for i, (key, label) in enumerate(MENU_ITEMS):
            y = 38 + i * 26
            selected = i == index
            if selected:
                draw.rounded_rectangle((5, y - 3, LCD_W - 5, y + 20), radius=5,
                                       fill=(28, 62, 98))
            color = (255, 255, 255) if selected else (180, 192, 206)
            draw.text((12, y), label, font=font, fill=color)
            # edit 只属于当前选中项；其它项始终显示主进程的真实状态。
            value = self._menu_value(key, state, edit if selected else None)
            self._right(draw, LCD_W - 12, y, value, font,
                        (255, 208, 120) if selected else (200, 210, 222))

        draw.line((8, 198, LCD_W - 8, 198), fill=(44, 62, 82), width=1)
        if notice:
            draw.text((8, 202), notice[:36], font=small, fill=(255, 208, 120))
            draw.text((8, 218), "K3 BACK", font=small, fill=(130, 140, 156))
        else:
            draw.text((8, 202), "UD SEL   LR ADJ", font=small,
                      fill=(130, 140, 156))
            draw.text((8, 218), "PRESS OK   K3 BACK", font=small,
                      fill=(130, 140, 156))
        return image

    @staticmethod
    def _menu_value(key: str, state: dict, edit) -> str:
        if key == "brightness":
            return str(edit if edit is not None else state.get("brightness", "-"))
        if key == "mode":
            mode = edit if edit is not None else state.get("mode", 2)
            return "CAST" if mode == 2 else "STANDBY"
        if key == "screen":
            value = edit if edit is not None else state.get("screen", True)
            return "ON" if value else "OFF"
        if key == "hud":
            value = edit if edit is not None else state.get("hud", True)
            return "ON" if value else "PAUSED"
        if key == "test":
            return "SEND"
        return "QUIT"


# ---------------------------------------------------------------------------
# 主进程客户端（Unix socket）
# ---------------------------------------------------------------------------
class Daemon:
    """连接 `glxss hud --listen`；必要时拉起一个子进程。"""

    def __init__(self, path: str, spawn: bool, fps: float, brightness: int,
                 detach: bool, timeout: int, verbose: bool = False):
        self.path = os.path.abspath(path)
        self.client = ControlClient(self.path, timeout=1.2)
        self.spawn = spawn
        self.fps = fps
        self.brightness = brightness
        self.detach = detach
        self.timeout = timeout
        self.verbose = verbose
        self.child: Optional[subprocess.Popen] = None
        self.error = ""
        self._last_spawn = 0.0

    def ensure(self) -> bool:
        if self._ping():
            return True
        if not self.spawn:
            return False
        child = self.child
        if child is not None and child.poll() is None:
            # 子进程已在跑，只做短等待，别把 HAT 的按键循环卡住。
            return self._wait_ready(0.5)
        if time.monotonic() - self._last_spawn < 5.0:
            return False
        self._last_spawn = time.monotonic()
        self._spawn()
        return self._wait_ready(6.0)

    def _ping(self) -> bool:
        try:
            self.client.request("ping")
            self.error = ""
            return True
        except GlxssError as exc:
            self.error = str(exc).splitlines()[0]
            return False

    def _wait_ready(self, seconds: float) -> bool:
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if self._ping():
                return True
            child = self.child
            if child is not None and child.poll() is not None:
                self.error = "HUD 子进程退出（code=%s）" % child.returncode
                return False
            time.sleep(0.25)
        return False

    def _spawn(self) -> None:
        cmd = [sys.executable, "-m", "glxss", "hud", "--listen",
               "--socket", self.path,
               "--fps", str(self.fps),
               "--brightness", str(self.brightness)]
        if self.detach:
            cmd.append("--detach")
        if self.timeout:
            cmd += ["--timeout", str(self.timeout)]
        env = os.environ.copy()
        env["GLXSS_SOCK"] = self.path
        env["PYTHONUNBUFFERED"] = "1"
        env["PYTHONPATH"] = ROOT + os.pathsep + env.get("PYTHONPATH", "")
        print("启动 HUD 主进程: " + " ".join(cmd))
        try:
            self.child = subprocess.Popen(cmd, cwd=ROOT, env=env)
        except OSError as exc:
            self.error = "无法启动 HUD 主进程: %s" % exc
            self.child = None

    def request(self, cmd: str, **payload):
        try:
            response = self.client.request(cmd, **payload)
        except GlxssError as exc:
            self.error = str(exc).splitlines()[0]
            return None
        if not response.get("ok"):
            self.error = str(response.get("error", "命令失败"))
            return None
        self.error = ""
        return response

    def status(self) -> Optional[dict]:
        response = self.request("status")
        if response is None:
            return None
        status = response.get("status")
        return status if isinstance(status, dict) else None

    def close(self) -> None:
        child = self.child
        if child is None:
            return
        if child.poll() is not None:
            self.child = None
            return
        try:
            self.client.request("quit")
        except GlxssError:
            pass
        deadline = time.monotonic() + 3.0
        while time.monotonic() < deadline and child.poll() is None:
            time.sleep(0.1)
        if child.poll() is None:
            try:
                child.terminate()
                child.wait(timeout=2.0)
            except Exception:
                try:
                    child.kill()
                except Exception:
                    pass
        self.child = None


# ---------------------------------------------------------------------------
# 主控制器
# ---------------------------------------------------------------------------
class LcdHatApp:
    def __init__(self, args):
        self.args = args
        self.gpio = Gpio()
        self.buttons = Buttons(self.gpio)
        self.panel = ST7789(
            self.gpio,
            bus=args.spi_bus, device=args.spi_device, speed_hz=args.spi_hz,
            dc=args.dc, rst=args.rst, bl=args.bl,
            x_offset=args.x_offset, y_offset=args.y_offset,
            rotation=args.rotation, verbose=args.verbose)
        self.pages = Pages()
        self.daemon = Daemon(args.socket or default_socket_path(),
                             spawn=not args.no_spawn, fps=args.fps,
                             brightness=args.brightness, detach=args.detach,
                             timeout=args.timeout, verbose=args.verbose)
        self.running = True
        self.menu_open = False
        self.index = 0
        self.edit = None
        self.notice = ""
        self.notice_until = 0.0
        self.status: dict = {}
        self.last_status_at = 0.0
        self.last_poll_at = 0.0
        self.last_render_at = 0.0

    # -- 生命周期 ----------------------------------------------------------
    def run(self) -> int:
        print("LCD HAT 启动：SPI%d.%d，GPIO=%s，socket=%s" % (
            self.args.spi_bus, self.args.spi_device, self.gpio.backend,
            self.daemon.path))
        # 先画一帧，避免拉起 HUD 子进程的几秒里 HAT 全黑。
        self._render(force=True)
        if not self.daemon.ensure():
            print("[警告] HUD 主进程未就绪: %s" % self.daemon.error,
                  file=sys.stderr)
        self._refresh_status(force=True)
        self._render(force=True)
        try:
            while self.running:
                for key in self.buttons.poll():
                    self._handle_key(key)
                now = time.monotonic()
                if now - self.last_poll_at >= 1.0:
                    self.last_poll_at = now
                    self._refresh_status()
                self._render()
                time.sleep(0.02)
        except KeyboardInterrupt:
            print("\nCtrl-C 退出。")
        finally:
            self.close()
        return 0

    def close(self) -> None:
        self.daemon.close()
        try:
            self.panel.close()
        except Exception:
            pass
        self.gpio.close()

    # -- 状态 --------------------------------------------------------------
    def _refresh_status(self, force: bool = False) -> None:
        now = time.monotonic()
        if not force and now - self.last_status_at < 0.8:
            return
        self.last_status_at = now
        status = self.daemon.status()
        if status is None:
            # 服务掉线时状态页不要继续显示旧的 ONLINE/FPS。
            offline = dict(self.status)
            offline.update({"connected": False, "fps": 0.0})
            self.status = offline
            if self.daemon.child is None or self.daemon.child.poll() is not None:
                self.daemon.ensure()
            return
        self.status = status

    def _status_value(self, key: str):
        if key == "brightness":
            try:
                return max(100, min(254, int(self.status.get("brightness", 180))))
            except (TypeError, ValueError):
                return 180
        if key == "mode":
            return 2 if self.status.get("mode") == 2 else 1
        if key == "screen":
            return bool(self.status.get("screen", True))
        if key == "hud":
            return bool(self.status.get("hud", True))
        return None

    # -- 菜单 --------------------------------------------------------------
    def _enter_item(self, index: int) -> None:
        self.index = index % len(MENU_ITEMS)
        key = MENU_ITEMS[self.index][0]
        self.edit = self._status_value(key)

    def _adjust(self, direction: int) -> None:
        key = MENU_ITEMS[self.index][0]
        if key == "brightness":
            current = self.edit if isinstance(self.edit, int) else 180
            self.edit = max(100, min(254, current + 10 * direction))
        elif key == "mode":
            self.edit = 1 if self.edit == 2 else 2
        elif key in ("screen", "hud"):
            self.edit = not bool(self.edit)
        else:
            return
        self._set_notice("%s = %s" % (MENU_ITEMS[self.index][1],
                                      Pages._menu_value(key, self.status, self.edit)))

    def _apply(self) -> None:
        key = MENU_ITEMS[self.index][0]
        if key == "brightness":
            self._command("bright", value=self.edit)
        elif key == "mode":
            self._command("mode", value=self.edit)
        elif key == "screen":
            self._command("screen", value=bool(self.edit))
        elif key == "hud":
            self._command("hud", value=bool(self.edit))
        elif key == "test":
            self._command("test", seconds=3.0)
            self._set_notice("TEST FRAME SENT")
        elif key == "exit":
            self.running = False

    def _command(self, cmd: str, **payload) -> None:
        response = self.daemon.request(cmd, **payload)
        if response is None:
            self._set_notice("FAILED: " + self.daemon.error[:28])
            return
        status = response.get("status")
        if isinstance(status, dict):
            self.status = status
        self._set_notice("OK " + cmd.upper())

    def _set_notice(self, text: str) -> None:
        self.notice = text
        self.notice_until = time.monotonic() + 2.0
        self._render(force=True)

    def _handle_key(self, name: str) -> None:
        print("按键: " + name)
        if self.menu_open:
            if name == "up":
                self._enter_item(self.index - 1)
            elif name == "down":
                self._enter_item(self.index + 1)
            elif name == "left":
                self._adjust(-1)
            elif name == "right":
                self._adjust(1)
            elif name == "press":
                self._apply()
            elif name == "key3":
                self.menu_open = False
                self._set_notice("MENU CLOSED")
        else:
            if name == "press":
                self.menu_open = True
                self._enter_item(0)
            elif name == "key1":
                self._command("hud", value=not bool(self.status.get("hud", True)))
            elif name == "key2":
                self._command("test", seconds=3.0)
                self._set_notice("TEST FRAME SENT")
            elif name == "key3":
                self.running = False
        self._render(force=True)

    # -- 渲染 --------------------------------------------------------------
    def _render(self, force: bool = False) -> None:
        now = time.monotonic()
        if not force and now - self.last_render_at < 0.5:
            return
        self.last_render_at = now
        if self.notice and now >= self.notice_until:
            self.notice = ""
        error = self.daemon.error
        if self.menu_open:
            image = self.pages.menu(self.status, self.index, self.edit, self.notice)
        else:
            image = self.pages.status(self.status, error=error, notice=self.notice)
        try:
            self.panel.show(image)
        except Exception as exc:
            print("[警告] 屏幕刷新失败: %s" % exc, file=sys.stderr)


# ---------------------------------------------------------------------------
# 预览（没有 Pi 硬件时生成 PNG）
# ---------------------------------------------------------------------------
def preview(out_dir: str) -> int:
    if Image is None:
        raise GlxssError("预览需要 Pillow：python -m pip install Pillow")
    os.makedirs(out_dir, exist_ok=True)
    pages = Pages()
    state = {
        "connected": True, "fps": 2.0, "brightness": 185, "hud": True,
        "lux": 321, "temperature": 36.5, "mode": 2, "screen": True,
        "time": time.strftime("%H:%M:%S"),
    }
    status_path = os.path.join(out_dir, "hat-status.png")
    menu_path = os.path.join(out_dir, "hat-menu.png")
    paused_path = os.path.join(out_dir, "hat-status-offline.png")
    pages.status(state).save(status_path)
    pages.menu(state, 0, 185).save(menu_path)
    offline = dict(state, connected=False, hud=False)
    pages.status(offline, error="daemon offline: /run/glxss/glxssctl.sock").save(
        paused_path)
    print("已生成:")
    print("  " + status_path)
    print("  " + menu_path)
    print("  " + paused_path)
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="树莓派 Waveshare 1.3inch LCD HAT 状态/控制面板（通过 Unix socket 控制 glxss hud）")
    parser.add_argument("--socket", default=None,
                        help="控制 socket 路径（默认 $GLXSS_SOCK 或 /run/glxssctl.sock）")
    parser.add_argument("--no-spawn", action="store_true",
                        help="socket 不可用时不要自动拉起 glxss hud 子进程")
    parser.add_argument("--fps", type=float, default=2.0,
                        help="拉起的 HUD 主进程帧率（默认 2）")
    parser.add_argument("--brightness", type=int, default=180,
                        help="HUD 初始亮度（100..254）")
    parser.add_argument("--detach", action="store_true",
                        help="拉起的 HUD 主进程使用 --detach")
    parser.add_argument("--timeout", type=int, default=5000,
                        help="拉起的 HUD 主进程 USB 超时毫秒")
    parser.add_argument("--spi-bus", type=int, default=0)
    parser.add_argument("--spi-device", type=int, default=0, help="0=CE0，1=CE1")
    parser.add_argument("--spi-hz", type=int, default=40_000_000)
    parser.add_argument("--rotation", type=int, choices=(0, 1, 2, 3), default=0)
    parser.add_argument("--x-offset", type=int, default=0,
                        help="ST7789 窗口 x 偏移（默认 0）")
    parser.add_argument("--y-offset", type=int, default=80,
                        help="ST7789 窗口 y 偏移（默认 80；不同批次面板可能不同）")
    parser.add_argument("--invert", dest="invert", action="store_true",
                        default=True,
                        help="发 INVON 反相（默认开；红蓝互换时用 --no-invert）")
    parser.add_argument("--no-invert", dest="invert", action="store_false",
                        help="发 INVOFF，关闭反相")
    parser.add_argument("--dc", type=int, default=PIN_LCD_DC)
    parser.add_argument("--rst", type=int, default=PIN_LCD_RST)
    parser.add_argument("--bl", type=int, default=PIN_LCD_BL)
    parser.add_argument("--preview", metavar="DIR",
                        help="不开 SPI/GPIO，只生成状态页/菜单页 PNG")
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    if not 0 <= args.brightness <= 255:
        print("[错误] 亮度必须在 0..255", file=sys.stderr)
        return 2
    if args.preview:
        try:
            return preview(args.preview)
        except GlxssError as exc:
            print("[错误] " + str(exc), file=sys.stderr)
            return 1
    try:
        return LcdHatApp(args).run()
    except GlxssError as exc:
        print("[错误] " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
