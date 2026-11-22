#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""树莓派 Waveshare 1.3inch LCD HAT：240×240 状态页 + 按键控制 + 投屏。

硬件：
  * ST7789 SPI：SCLK=BCM11、MOSI=BCM10、CS=BCM8(CE0)、DC=BCM25、RST=BCM27、BL=BCM24
  * 摇杆/按键：UP=6、DOWN=19、LEFT=5、RIGHT=26、PRESS=13、
               KEY1=21、KEY2=20、KEY3=16（全部低电平有效，内部上拉）
  引脚来自 Waveshare 官方 wiki：
  https://www.waveshare.com/wiki/1.3inch_LCD_HAT

按键映射：
  * 摇杆 左/右 : 显示模式 1（待机）/ 2（投屏）
  * 摇杆 按下  : 开/关屏
  * 摇杆 上/下 : 亮度 ±20
  * KEY1       : 切换 fit / cover / stretch
  * KEY2       : 切换 screen / test 采集源
  * KEY3       : 退出

依赖：spidev、Pillow、numpy、pyusb，以及 RPi.GPIO / lgpio / gpiod 之一。
这是一个**没有真机时也能编译**的实现；真实 SPI/GPIO 操作只在树莓派上执行。
"""

from __future__ import annotations

import argparse
import os
import struct
import sys
import time
from typing import Dict, List, Tuple

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from glxss import capture, lcd, usb  # noqa: E402
from glxss.errors import GlxssError  # noqa: E402

try:  # 只在真正运行 Pi 界面时必需
    from PIL import Image, ImageDraw, ImageFont  # type: ignore
except Exception:  # pragma: no cover
    Image = None  # type: ignore
    ImageDraw = None  # type: ignore
    ImageFont = None  # type: ignore

try:
    import numpy as np  # type: ignore
except Exception:  # pragma: no cover
    np = None  # type: ignore

try:
    import spidev  # type: ignore
except Exception:  # pragma: no cover
    spidev = None  # type: ignore


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
            if pressed and not self._last[name] and now - self._last_at.get(name, 0) >= self.debounce:
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
        (0x21, b"", 0.0),       # INVON
        (0x13, b"", 0.0),       # NORON
        (0x29, b"", 0.08),      # DISPON
    ]

    _MADCTL = {0: 0x00, 1: 0x60, 2: 0xC0, 3: 0xA0}

    def __init__(self, gpio: Gpio, bus: int = 0, device: int = 0,
                 speed_hz: int = 40_000_000, dc: int = PIN_LCD_DC,
                 rst: int = PIN_LCD_RST, bl: int = PIN_LCD_BL,
                 x_offset: int = 0, y_offset: int = 80,
                 rotation: int = 0, verbose: bool = False):
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
            self._cmd(command, data)
            # SWRESET 会清掉 MADCTL；必须在 SLPOUT 之后再写。
            if command == 0x11:
                self._cmd(0x36, bytes([self._MADCTL[self.rotation]]))
            if delay:
                time.sleep(delay)
        if self.verbose:
            print("ST7789 初始化完成，rotation=%d offsets=(%d,%d)" % (
                self.rotation, self.x_offset, self.y_offset))

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
# 状态页
# ---------------------------------------------------------------------------
class StatusPage:
    def __init__(self):
        self._font_cache: Dict[int, object] = {}
        self.last_state = None

    def _font(self, size: int):
        if size not in self._font_cache:
            font = None
            for path in (
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
            ):
                try:
                    font = ImageFont.truetype(path, size)
                    break
                except Exception:
                    continue
            if font is None:
                font = ImageFont.load_default()
            self._font_cache[size] = font
        return self._font_cache[size]

    def render(self, state: dict):
        image = Image.new("RGB", (LCD_W, LCD_H), (12, 16, 24))
        draw = ImageDraw.Draw(image)
        title_font = self._font(20)
        font = self._font(15)
        small = self._font(12)

        draw.rectangle((0, 0, LCD_W, 34), fill=(30, 110, 190))
        draw.text((10, 7), "GLXSS CAST", font=title_font, fill=(255, 255, 255))
        draw.ellipse((204, 9, 224, 29), fill=(40, 220, 100) if state.get("connected") else (220, 60, 60))

        y = 46
        lines = [
            ("GLASSES", "ONLINE" if state.get("connected") else "OFFLINE",
             (120, 230, 150) if state.get("connected") else (255, 120, 120)),
            ("FPS", "%.1f  %.1f MB/s" % (state.get("fps", 0.0), state.get("mbps", 0.0)),
             (230, 230, 230)),
            ("BRIGHT", str(state.get("brightness", 0)), (230, 230, 230)),
            ("MODE", state.get("mode", "-"), (230, 230, 230)),
            ("SOURCE", state.get("source", "-"), (230, 230, 230)),
            ("FIT", state.get("fit", "-"), (230, 230, 230)),
            ("KEY", state.get("last_key", "-"), (250, 210, 120)),
        ]
        for label, value, color in lines:
            draw.text((12, y), label, font=small, fill=(140, 150, 165))
            draw.text((92, y - 2), str(value), font=font, fill=color)
            y += 23

        draw.line((10, 215, LCD_W - 10, 215), fill=(55, 65, 80), width=1)
        if state.get("error"):
            draw.text((10, 219), "ERR " + str(state["error"])[:30], font=small,
                      fill=(255, 120, 120))
        else:
            draw.text((10, 219), state.get("hint", "K1 fit K2 src K3 quit"),
                      font=small, fill=(130, 140, 155))
        self.last_state = state
        return image


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
        self.page = StatusPage()
        self.running = True
        self.source = None
        self.source_name = args.source
        self.fit = args.fit
        self.brightness = args.brightness
        self.mode = 2
        self.screen_on = True
        self.fps_target = args.fps
        self.dev = None
        self.session = None
        self.link = None
        self.error = ""
        self.frames = 0
        self.stats_started = time.monotonic()
        self.last_frame_at = 0.0
        self.last_status_at = 0.0
        self.last_connect_try = 0.0
        self.fps = 0.0
        self.mbps = 0.0
        self._open_source(args.source)

    # -- 采集源 ------------------------------------------------------------
    def _open_source(self, name: str) -> None:
        try:
            if self.source is not None:
                self.source.close()
            self.source = capture.create_source(
                name, monitor=self.args.monitor, test_pattern=self.args.test_pattern)
            self.source_name = name
            self.error = ""
        except GlxssError as exc:
            if name != "test":
                print("[警告] 屏幕采集失败，回退 test 图案: %s" % exc, file=sys.stderr)
                self._open_source("test")
                self.error = str(exc).splitlines()[0]
            else:
                self.error = str(exc).splitlines()[0]
                raise

    # -- USB ---------------------------------------------------------------
    def _connect(self) -> bool:
        now = time.monotonic()
        if self.link is not None:
            return True
        if now - self.last_connect_try < 2.0:
            return False
        self.last_connect_try = now
        session = None
        try:
            dev = usb.find_normal()
            session = usb.DeviceSession(dev, detach=self.args.detach,
                                        verbose=self.args.verbose)
            intf, ep = usb.pick_bulk_out(dev)
            session.claim_interface(intf, label="LCD")
            link = lcd.LcdLink(dev, ep.bEndpointAddress, timeout=self.args.timeout)
            link.initialize(self.args.timeout)
            self.dev, self.session, self.link = dev, session, link
            self.error = ""
            print("眼镜已连接: " + usb.device_label(dev))
            return True
        except GlxssError as exc:
            if session is not None:
                session.close()
            self.error = str(exc).splitlines()[0]
            return False

    def _disconnect(self, reason: str = "") -> None:
        self.error = reason or "USB disconnected"
        if self.session is not None:
            self.session.close()
        self.dev = self.session = self.link = None
        self.fps = self.mbps = 0.0
        if reason:
            print("[警告] " + reason, file=sys.stderr)

    # -- 控制 ---------------------------------------------------------------
    def _run_control(self, action: str, value=None) -> None:
        if self.link is None:
            self.error = "glasses offline"
            return
        try:
            if action == "brightness":
                self.brightness = int(value)
                self.link.brightness(self.brightness, self.args.timeout)
            elif action == "screen":
                self.screen_on = bool(value)
                self.link.screen(self.screen_on, self.args.timeout)
                if self.screen_on:
                    self.link.mode(2, self.args.timeout)
            elif action == "mode":
                self.mode = int(value)
                self.link.mode(self.mode, self.args.timeout)
            self.error = ""
        except GlxssError as exc:
            self._disconnect("控制命令失败: %s" % exc)

    def _handle_key(self, name: str) -> None:
        print("按键: " + name)
        if name == "left":
            self._run_control("mode", 1)
        elif name == "right":
            self._run_control("mode", 2)
        elif name == "press":
            self._run_control("screen", not self.screen_on)
        elif name == "up":
            self._run_control("brightness", min(254, self.brightness + 20))
        elif name == "down":
            self._run_control("brightness", max(100, self.brightness - 20))
        elif name == "key1":
            modes = ["fit", "cover", "stretch"]
            self.fit = modes[(modes.index(self.fit) + 1) % len(modes)] if self.fit in modes else "fit"
        elif name == "key2":
            self._open_source("test" if self.source_name == "screen" else "screen")
        elif name == "key3":
            self.running = False
        self._render_status(force=True)

    # -- 状态页 ------------------------------------------------------------
    def _status_state(self) -> dict:
        return {
            "connected": self.link is not None,
            "fps": self.fps,
            "mbps": self.mbps,
            "brightness": self.brightness,
            "mode": "CAST" if self.mode == 2 else "STANDBY",
            "source": self.source_name,
            "fit": self.fit,
            "last_key": self.buttons.last_event or "-",
            "error": self.error,
            "hint": "K1 fit  K2 src  K3 quit",
        }

    def _render_status(self, force: bool = False) -> None:
        now = time.monotonic()
        if not force and now - self.last_status_at < 1.0:
            return
        self.last_status_at = now
        try:
            self.panel.show(self.page.render(self._status_state()))
        except Exception as exc:
            print("[警告] 状态页刷新失败: %s" % exc, file=sys.stderr)

    # -- 主循环 ------------------------------------------------------------
    def run(self) -> int:
        print("LCD HAT 启动：SPI%d.%d %dx%d，GPIO 后端=%s" % (
            self.args.spi_bus, self.args.spi_device, LCD_W, LCD_H, self.gpio.backend))
        self._render_status(force=True)
        try:
            while self.running:
                for key in self.buttons.poll():
                    self._handle_key(key)
                if self.link is None:
                    self._connect()
                if self.args.no_cast:
                    time.sleep(0.05)
                    self._render_status()
                    continue
                if self.link is None:
                    time.sleep(0.05)
                    self._render_status()
                    continue
                now = time.monotonic()
                period = (1.0 / self.fps_target) if self.fps_target > 0 else 0.0
                if now - self.last_frame_at < period:
                    time.sleep(min(0.01, max(0.001, period - (now - self.last_frame_at))))
                    self._render_status()
                    continue
                try:
                    rgb = self.source.grab()
                    rgba = capture.map_to_rgba(rgb, mode=self.fit)
                    packet = lcd.build_frame(capture.rgba_bytes(rgba))
                    self.link.send_packet(packet)
                    self.frames += 1
                    elapsed = max(1e-6, time.monotonic() - self.stats_started)
                    self.fps = self.frames / elapsed
                    self.mbps = self.frames * len(packet) / elapsed / 1e6
                    self.error = ""
                except GlxssError as exc:
                    if "设备已断开" in str(exc) or "device" in str(exc).lower():
                        self._disconnect("投屏断开: %s" % str(exc).splitlines()[0])
                    else:
                        self.error = str(exc).splitlines()[0]
                        time.sleep(0.2)
                self.last_frame_at = time.monotonic()
                self._render_status()
        except KeyboardInterrupt:
            print("\nCtrl-C 退出。")
        finally:
            self.close()
        return 0

    def close(self) -> None:
        if self.session is not None:
            self.session.close()
        if self.source is not None:
            self.source.close()
        try:
            self.panel.close()
        except Exception:
            pass
        self.gpio.close()


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="树莓派 Waveshare 1.3inch LCD HAT 状态页 + GLXSS 投屏/控制")
    parser.add_argument("--fps", type=float, default=15.0, help="投屏目标帧率（默认 15）")
    parser.add_argument("--fit", choices=("fit", "cover", "stretch"), default="fit",
                        help="映射模式")
    parser.add_argument("--source", choices=("screen", "test"), default="screen",
                        help="初始采集源（默认 screen，失败回退 test）")
    parser.add_argument("--monitor", type=int, default=1, help="mss 显示器编号")
    parser.add_argument("--test-pattern", choices=("bars", "gradient"), default="bars")
    parser.add_argument("--no-cast", action="store_true", help="只显示状态页，不投屏")
    parser.add_argument("--brightness", type=int, default=180, help="初始亮度 100..254")
    parser.add_argument("--detach", action="store_true", help="Linux detach 内核驱动")
    parser.add_argument("--timeout", type=int, default=5000, help="USB 超时毫秒")
    parser.add_argument("--spi-bus", type=int, default=0)
    parser.add_argument("--spi-device", type=int, default=0, help="0=CE0，1=CE1")
    parser.add_argument("--spi-hz", type=int, default=40_000_000)
    parser.add_argument("--rotation", type=int, choices=(0, 1, 2, 3), default=0)
    parser.add_argument("--x-offset", type=int, default=0,
                        help="ST7789 窗口 x 偏移（默认 0）")
    parser.add_argument("--y-offset", type=int, default=80,
                        help="ST7789 窗口 y 偏移（默认 80；不同批次面板可能不同）")
    parser.add_argument("--dc", type=int, default=PIN_LCD_DC)
    parser.add_argument("--rst", type=int, default=PIN_LCD_RST)
    parser.add_argument("--bl", type=int, default=PIN_LCD_BL)
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    if not 0 <= args.brightness <= 255:
        print("[错误] 亮度必须在 0..255", file=sys.stderr)
        return 2
    try:
        return LcdHatApp(args).run()
    except GlxssError as exc:
        print("[错误] " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
