# -*- coding: utf-8 -*-
"""HUD 渲染源：640×400 RGBA，把主机侧信息画成“眼镜主显示”的内容。

定位（详见 linux/pi/SETUP.md）：
  * 眼镜是主显示，LCD HAT 只做状态/控制面板；
  * 不是完整桌面镜像，只组合需要的图层：
      - 顶部：时间、连接状态、fps
      - 中心：磁罗盘（N/E/S/W 刻度环 + 指针 + heading）
      - 左侧：光感 lux、温度
      - 右侧：加速度姿态（俯仰/横滚）
      - 底部：亮度、固件版本、HUD 开关状态
      - 可选导航占位：相对方位箭头 + 距离 + 标签（不实现真实导航协议）
  * HID 300 ms 一包，HUD 默认 2 fps 重绘就够；真正发包频率由 `--fps` 控制。

字体：优先系统 Noto/DejaVu/Arial/Consolas，找不到才退回 Pillow 内置字体；
仓库不自带字体文件。界面文字保持 ASCII，避免 Pi 上缺中文字形。

用法（代码里）：
    source = HudSource(brightness=180)
    source.set_firmware("G25_MainFW_1.9.1")
    source.set_connected(True)
    source.attach_hid(dev)
    rgba = capture.map_to_rgba(source.grab(), mode="stretch")
"""

from __future__ import annotations

import math
import os
import sys
import threading
import time
from dataclasses import dataclass, field, replace
from typing import Dict, Optional, Tuple

try:  # 核心依赖；缺失时给出明确错误而不是 import 失败
    import numpy as np  # type: ignore
except Exception:  # pragma: no cover - 取决于环境
    np = None  # type: ignore

try:
    from PIL import Image, ImageDraw, ImageFont  # type: ignore
except Exception:  # pragma: no cover - 取决于环境
    Image = ImageDraw = ImageFont = None  # type: ignore

from . import __version__, hid
from .capture import LCD_H, LCD_W, ImageSource, TestPatternSource
from .errors import GlxssError

# 色板：深色底 + 青/琥珀强调色，适配眼镜的 AR 面板。
BG_TOP = (8, 13, 24, 255)
BG_BOTTOM = (3, 6, 12, 255)
CARD = (16, 26, 42, 196)
CARD_EDGE = (52, 82, 118, 190)
TEXT = (226, 236, 248, 255)
MUTED = (132, 150, 172, 255)
ACCENT = (64, 210, 255, 255)
ACCENT_2 = (255, 190, 72, 255)
OK = (80, 226, 142, 255)
BAD = (255, 96, 108, 255)
GRID = (24, 38, 58, 90)
DIM = (90, 108, 130, 255)

# 罗盘中心/半径（给底部导航箭头留空间）。
COMPASS_CX = 320
COMPASS_CY = 194
COMPASS_R = 100


@dataclass
class NavInfo:
    """导航占位：只描述“相对方位 + 距离 + 文字标签”，不绑定任何导航协议。"""

    bearing: float = 0.0     # 目标方位（deg，0=北，顺时针）
    distance: float = 0.0    # 距离（米）
    label: str = ""          # 例如 "Main St" / "目的地"


@dataclass
class HudState:
    """渲染所需的全部状态；由 HID 报告、LCD 状态和主循环共同更新。"""

    connected: bool = False
    fps: float = 0.0
    brightness: int = 180
    firmware: str = ""
    lux: int = 0
    temperature: float = 0.0
    compass: Tuple[int, int, int] = (0, 0, 0)
    acc: Tuple[int, int, int] = (0, 0, 0)
    gyro: Tuple[int, int, int] = (0, 0, 0)
    af: int = 0
    caf: int = 0
    sensors_valid: bool = False
    compass_valid: bool = False
    acc_valid: bool = False
    hud_enabled: bool = True
    nav: Optional[NavInfo] = None
    heading_offset: float = 0.0
    updated_at: float = field(default_factory=time.time)

    @property
    def heading(self) -> float:
        return heading_from_compass(self.compass[0], self.compass[1],
                                    self.heading_offset)

    @property
    def pitch_roll(self) -> Tuple[float, float]:
        return pitch_roll_from_acc(*self.acc)


def heading_from_compass(x: int, y: int, offset: float = 0.0) -> float:
    """由磁罗盘 X/Y 原始值算 heading（0..360，0=北，顺时针）。

    约定：+X=北、+Y=东。不同批次/佩戴方向的坐标可能旋转，用 offset 校准，
    或在调用方调整 `HudState.heading_offset`；坐标系本身未在真机标定。
    """
    if x == 0 and y == 0:
        return 0.0
    return (math.degrees(math.atan2(y, x)) + offset) % 360.0


def pitch_roll_from_acc(x: int, y: int, z: int) -> Tuple[float, float]:
    """按 docs/ch-hid.md §5.3 的约定算俯仰/横滚（度，抬头为正）。

    pitch = atan2(x, hypot(y, z))，roll = atan2(z, y)。
    原始 LSB 的物理量程不影响角度（三轴同比例），所以这里直接用 raw。
    """
    if x == 0 and y == 0 and z == 0:
        return 0.0, 0.0
    pitch = math.degrees(math.atan2(x, math.hypot(y, z)))
    roll = math.degrees(math.atan2(z, y))
    return pitch, roll


def demo_state() -> HudState:
    """离线演示数据：用于 `hud --dry-run` / HAT 预览，不接触硬件。"""
    return HudState(
        connected=True,
        fps=2.0,
        brightness=185,
        firmware="G25_MainFW_1.9.1",
        lux=321,
        temperature=36.5,
        compass=(2150, 1580, -420),
        acc=(-350, 9200, 120),
        gyro=(2, -1, 0),
        sensors_valid=True,
        compass_valid=True,
        acc_valid=True,
        nav=NavInfo(bearing=72.0, distance=320.0, label="Main St"),
    )


class HudSource(ImageSource):
    """把 HUD 画成 RGB numpy 数组，供 `CastApp` / `capture.map_to_rgba` 使用。"""

    name = "hud"

    def __init__(self, brightness: int = 180, firmware: str = "",
                 demo: bool = False, render_interval: float = 0.5,
                 heading_offset: float = 0.0):
        if Image is None:
            raise GlxssError("HUD 渲染需要 Pillow：python -m pip install Pillow")
        if np is None:
            raise GlxssError("HUD 渲染需要 numpy：python -m pip install numpy")
        self._lock = threading.RLock()
        self._font_cache: Dict[Tuple[int, bool], object] = {}
        self._background_cache = None
        self._render_interval = max(0.05, float(render_interval))
        self._last_render = 0.0
        self._cache = None
        self._test_until = 0.0
        self._test_rgb = None
        self._hid_stop = threading.Event()
        self._hid_thread: Optional[threading.Thread] = None
        self._hid_error = ""
        self.state = demo_state() if demo else HudState()
        self.state.brightness = int(brightness)
        if firmware:
            self.state.firmware = str(firmware)
        self.state.heading_offset = float(heading_offset)

    # -- 状态更新 ----------------------------------------------------------
    def snapshot(self) -> HudState:
        with self._lock:
            return replace(self.state)

    def set_connected(self, value: bool) -> None:
        with self._lock:
            self.state.connected = bool(value)
            self.state.updated_at = time.time()

    def set_fps(self, value: float) -> None:
        with self._lock:
            self.state.fps = max(0.0, float(value))
            self.state.updated_at = time.time()

    def set_brightness(self, value: int) -> None:
        with self._lock:
            self.state.brightness = int(value)
            self.state.updated_at = time.time()

    def set_firmware(self, value: str) -> None:
        with self._lock:
            self.state.firmware = str(value or "")
            self.state.updated_at = time.time()

    def set_hud_enabled(self, value: bool) -> None:
        with self._lock:
            self.state.hud_enabled = bool(value)
            self.state.updated_at = time.time()
        self._invalidate()

    def set_heading_offset(self, value: float) -> None:
        with self._lock:
            self.state.heading_offset = float(value)
            self.state.updated_at = time.time()
        self._invalidate()

    def set_nav(self, bearing: float, distance: float, label: str = "") -> None:
        """导航占位接口：设置目标方位/距离/标签；传 None 清除见 clear_nav。"""
        with self._lock:
            self.state.nav = NavInfo(float(bearing), float(distance), str(label))
            self.state.updated_at = time.time()
        self._invalidate()

    def clear_nav(self) -> None:
        with self._lock:
            self.state.nav = None
            self.state.updated_at = time.time()
        self._invalidate()

    def update_report(self, report) -> None:
        """接收一份 `hid.HidReport`；线程安全，供 HID 轮询线程调用。"""
        with self._lock:
            self.state.lux = int(report.lux)
            self.state.temperature = float(report.temperature)
            self.state.compass = (report.compass_x, report.compass_y,
                                  report.compass_z)
            self.state.acc = (report.acc_x, report.acc_y, report.acc_z)
            self.state.gyro = (report.gyro_x, report.gyro_y, report.gyro_z)
            self.state.af = int(report.af_result)
            self.state.caf = int(report.caf_flag)
            self.state.compass_valid = any(self.state.compass)
            self.state.acc_valid = any(self.state.acc)
            self.state.sensors_valid = (self.state.compass_valid or
                                        self.state.acc_valid)
            self.state.updated_at = time.time()
        self._invalidate()

    def flash_test(self, seconds: float = 3.0) -> None:
        """让眼镜显示测试图案若干秒，之后自动回到 HUD。"""
        if self._test_rgb is None:
            self._test_rgb = np.ascontiguousarray(
                TestPatternSource(animate=False).grab())
        self._test_until = time.monotonic() + max(0.2, float(seconds))

    def _invalidate(self) -> None:
        self._last_render = 0.0

    # -- HID 轮询 ----------------------------------------------------------
    def attach_hid(self, dev, interval: float = hid.SENSOR_POLL_INTERVAL,
                   timeout: int = 1500, lock=None) -> None:
        """后台按 300 ms 读 28 B 报告；lock 用于和主线程的 bulk OUT 串行化。"""
        if self._hid_thread is not None and self._hid_thread.is_alive():
            return
        self._hid_stop.clear()
        self._hid_thread = threading.Thread(
            target=self._hid_loop, args=(dev, interval, timeout, lock),
            name="glxss-hud-hid", daemon=True)
        self._hid_thread.start()

    def _hid_loop(self, dev, interval: float, timeout: int, lock) -> None:
        failures = 0
        next_at = time.monotonic()
        while not self._hid_stop.is_set():
            try:
                if lock is not None:
                    with lock:
                        report = hid.read_report(dev, timeout)
                else:
                    report = hid.read_report(dev, timeout)
                self.update_report(report)
                self._hid_error = ""
                failures = 0
            except GlxssError as exc:
                failures += 1
                self._hid_error = str(exc).splitlines()[0]
                if failures >= 3:
                    print("[警告] HUD 的 HID 轮询停止: %s" % self._hid_error,
                          file=sys.stderr)
                    return
            next_at += max(0.05, float(interval))
            delay = next_at - time.monotonic()
            if delay > 0:
                self._hid_stop.wait(delay)
            else:
                next_at = time.monotonic()

    @property
    def hid_error(self) -> str:
        return self._hid_error

    def close(self) -> None:
        self._hid_stop.set()
        thread = self._hid_thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=2.0)
        self._hid_thread = None

    # -- 帧输出 ------------------------------------------------------------
    def grab(self):
        """返回 H×W×3 uint8 RGB；测试图案优先，其余按 render_interval 缓存。"""
        now = time.monotonic()
        if self._test_rgb is not None and now < self._test_until:
            return self._test_rgb
        with self._lock:
            cached = self._cache
            last = self._last_render
            state = replace(self.state)
        if cached is not None and (now - last) < self._render_interval:
            return cached
        image = self.render(state)
        rgb = np.asarray(image.convert("RGB"), dtype=np.uint8).copy()
        with self._lock:
            self._cache = rgb
            self._last_render = time.monotonic()
        return rgb

    def render(self, state: Optional[HudState] = None):
        """渲染一张 640×400 RGBA `PIL.Image`；不传 state 时取内部快照。"""
        if Image is None:  # pragma: no cover - 构造时已检查
            raise GlxssError("HUD 渲染需要 Pillow")
        state = state if state is not None else self.snapshot()
        image = self._background().copy()
        draw = ImageDraw.Draw(image, "RGBA")
        clock = time.strftime("%H:%M", time.localtime())

        if not state.hud_enabled:
            self._draw_paused(image, draw, state, clock)
            return image

        self._draw_top(draw, state, clock)
        self._draw_left(draw, state)
        self._draw_compass(draw, state)
        self._draw_attitude(draw, state)
        self._draw_bottom(draw, state)
        if state.nav is not None:
            self._draw_nav(draw, state)
        return image

    def save_png(self, path: str) -> None:
        """保存一张 RGBA PNG（离线核对图层/字体用）。"""
        parent = os.path.dirname(os.path.abspath(path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        self.render().save(path)

    # ------------------------------------------------------------------
    # 绘制辅助
    # ------------------------------------------------------------------
    def _font(self, size: int, bold: bool = False):
        key = (int(size), bool(bold))
        if key in self._font_cache:
            return self._font_cache[key]
        font = None
        if ImageFont is not None:
            candidates = []
            win_dir = os.environ.get("WINDIR", r"C:\Windows")
            if os.name == "nt":
                candidates += [
                    os.path.join(win_dir, "Fonts", "consola.ttf" if not bold else "consolab.ttf"),
                    os.path.join(win_dir, "Fonts", "arial.ttf" if not bold else "arialbd.ttf"),
                ]
            if bold:
                candidates += [
                    "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf",
                    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                    "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf",
                    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
                ]
            candidates += [
                "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
                "/System/Library/Fonts/Supplemental/Arial.ttf",
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
                # Pillow 10+ 的 load_default 支持 size；9.x 只能退回固定点阵。
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

    def _center_text(self, draw, cx: int, y: int, text: str, font, fill) -> None:
        width = self._text_width(draw, text, font)
        draw.text((cx - width / 2, y), text, font=font, fill=fill)

    def _right_text(self, draw, right: int, y: int, text: str, font, fill) -> None:
        width = self._text_width(draw, text, font)
        draw.text((right - width, y), text, font=font, fill=fill)

    def _background(self):
        if self._background_cache is not None:
            return self._background_cache
        image = Image.new("RGBA", (LCD_W, LCD_H), BG_BOTTOM)
        draw = ImageDraw.Draw(image, "RGBA")
        for y in range(LCD_H):
            t = y / max(1, LCD_H - 1)
            color = tuple(int(BG_TOP[i] * (1 - t) + BG_BOTTOM[i] * t) for i in range(3))
            draw.line((0, y, LCD_W, y), fill=color + (255,))
        for x in range(40, LCD_W, 40):
            draw.line((x, 0, x, LCD_H), fill=GRID)
        for y in range(40, LCD_H, 40):
            draw.line((0, y, LCD_W, y), fill=GRID)
        # 左右两侧轻微暗角，把视觉重心压到中央罗盘。
        for i in range(80):
            alpha = int(70 * (1 - i / 80))
            draw.line((i, 0, i, LCD_H), fill=(0, 0, 0, alpha))
            draw.line((LCD_W - 1 - i, 0, LCD_W - 1 - i, LCD_H), fill=(0, 0, 0, alpha))
        self._background_cache = image
        return image

    def _draw_top(self, draw, state: HudState, clock: str) -> None:
        draw.rounded_rectangle((16, 12, LCD_W - 16, 54), radius=10,
                               fill=(14, 24, 40, 214), outline=CARD_EDGE, width=1)
        draw.text((30, 20), clock, font=self._font(21, bold=True), fill=TEXT)
        draw.text((98, 24), "LOCAL", font=self._font(11), fill=MUTED)

        dot = OK if state.connected else BAD
        draw.ellipse((246, 27, 258, 39), fill=dot)
        status = "GLASSES ONLINE" if state.connected else "GLASSES OFFLINE"
        draw.text((266, 26), status, font=self._font(12, bold=True), fill=dot)
        draw.text((266, 40), "GLXSS HUD  v%s" % __version__,
                  font=self._font(9), fill=DIM)

        fps_text = "%.1f fps" % state.fps
        self._right_text(draw, LCD_W - 30, 21, fps_text,
                         self._font(13, bold=True), ACCENT)
        hint = "HUD ON" if state.hud_enabled else "HUD PAUSED"
        self._right_text(draw, LCD_W - 30, 38, hint, self._font(10), MUTED)

    def _draw_left(self, draw, state: HudState) -> None:
        draw.rounded_rectangle((16, 86, 140, 306), radius=12,
                               fill=CARD, outline=CARD_EDGE, width=1)
        draw.text((30, 98), "LIGHT", font=self._font(11, bold=True), fill=MUTED)
        draw.text((30, 114), "%d" % state.lux, font=self._font(30, bold=True),
                  fill=ACCENT_2)
        draw.text((30, 150), "lux", font=self._font(11), fill=MUTED)

        # 光感条：sqrt 压缩，室内 0..1000 也能看出变化。
        bar_y = 170
        ratio = min(1.0, math.sqrt(max(0, state.lux)) / math.sqrt(1000.0))
        draw.rounded_rectangle((30, bar_y, 126, bar_y + 6), radius=3,
                               fill=(40, 54, 72, 220))
        draw.rounded_rectangle((30, bar_y, 30 + int(96 * ratio), bar_y + 6),
                               radius=3, fill=ACCENT_2)

        draw.line((30, 194, 126, 194), fill=CARD_EDGE)
        draw.text((30, 206), "TEMP", font=self._font(11, bold=True), fill=MUTED)
        draw.text((30, 222), "%.1f" % state.temperature,
                  font=self._font(27, bold=True), fill=TEXT)
        draw.text((100, 236), "C", font=self._font(12, bold=True), fill=MUTED)

        draw.line((30, 262, 126, 262), fill=CARD_EDGE)
        draw.text((30, 272), "SENSOR", font=self._font(10, bold=True), fill=MUTED)
        draw.text((30, 286), "OK" if state.sensors_valid else "NO DATA",
                  font=self._font(11, bold=True),
                  fill=OK if state.sensors_valid else BAD)

    def _draw_compass(self, draw, state: HudState) -> None:
        cx, cy, r = COMPASS_CX, COMPASS_CY, COMPASS_R
        draw.ellipse((cx - r - 6, cy - r - 6, cx + r + 6, cy + r + 6),
                     outline=(40, 62, 88, 150), width=1)
        draw.ellipse((cx - r, cy - r, cx + r, cy + r),
                     outline=(66, 112, 158, 210), width=2)

        for deg in range(0, 360, 15):
            rad = math.radians(deg)
            major = deg % 90 == 0
            mid = deg % 45 == 0
            inner = r - (15 if major else 9 if mid else 5)
            x1 = cx + math.sin(rad) * inner
            y1 = cy - math.cos(rad) * inner
            x2 = cx + math.sin(rad) * r
            y2 = cy - math.cos(rad) * r
            color = ACCENT if major else (150, 176, 202, 210) if mid else (90, 116, 142, 170)
            draw.line((x1, y1, x2, y2), fill=color, width=2 if major else 1)

        labels = (("N", 0), ("E", 90), ("S", 180), ("W", 270))
        for text, deg in labels:
            rad = math.radians(deg)
            lx = cx + math.sin(rad) * (r - 26)
            ly = cy - math.cos(rad) * (r - 26)
            font = self._font(15 if text == "N" else 13, bold=True)
            color = ACCENT if text == "N" else TEXT
            self._center_text(draw, int(lx), int(ly - 8), text, font, color)

        heading = state.heading
        valid = state.compass_valid or any(state.compass)
        # 指针：主针指向 heading，尾针反向；中心圆盘留给数值。
        self._needle(draw, cx, cy, heading, r - 34, 52, ACCENT, ACCENT_2,
                     alpha=255 if valid else 80)
        draw.ellipse((cx - 43, cy - 43, cx + 43, cy + 43),
                     fill=(10, 18, 30, 236), outline=(54, 82, 112, 220), width=1)
        self._center_text(draw, cx, cy - 20, "%03.0f" % heading,
                          self._font(27, bold=True), TEXT if valid else MUTED)
        self._center_text(draw, cx, cy + 9, "deg",
                          self._font(10, bold=True), MUTED)
        if not valid:
            self._center_text(draw, cx, cy + 24, "NO MAG",
                              self._font(9, bold=True), BAD)

    def _needle(self, draw, cx: int, cy: int, heading: float, outer: int,
                inner: int, color_front, color_back, alpha: int = 255) -> None:
        rad = math.radians(heading)
        sin, cos = math.sin(rad), math.cos(rad)
        # 垂直方向（用于三角形底边）。
        px, py = cos, -sin

        def point(radius: float, side: float = 0.0):
            return (cx + sin * radius + px * side,
                    cy - cos * radius + py * side)

        front = color_front[:3] + (alpha,)
        back = color_back[:3] + (max(30, alpha // 2),)
        # 先画尾针，再画主针，避免暗色尾针盖住主针根部。
        draw.polygon([point(inner + 4), point(-inner + 6, 7),
                      point(-inner + 6, -7)], fill=back)
        draw.polygon([point(outer), point(inner, 9), point(inner, -9)], fill=front)

    def _draw_attitude(self, draw, state: HudState) -> None:
        draw.rounded_rectangle((500, 86, LCD_W - 16, 306), radius=12,
                               fill=CARD, outline=CARD_EDGE, width=1)
        draw.text((514, 98), "ATTITUDE", font=self._font(11, bold=True), fill=MUTED)
        box = (516, 118, 608, 210)
        cx, cy = (box[0] + box[2]) // 2, (box[1] + box[3]) // 2
        radius = (box[2] - box[0]) // 2
        draw.ellipse(box, outline=(58, 94, 128, 220), width=1)
        draw.ellipse((cx - radius + 8, cy - radius + 8, cx + radius - 8, cy + radius - 8),
                     outline=(34, 54, 76, 180), width=1)

        valid = state.acc_valid or any(state.acc)
        pitch, roll = state.pitch_roll
        if not valid:
            self._center_text(draw, cx, cy - 7, "NO IMU",
                              self._font(11, bold=True), BAD)
        else:
            # 地平线：按 roll 旋转，按 pitch 平移；限制在仪表框内。
            angle = math.radians(-roll)
            offset = max(-radius + 10, min(radius - 10, pitch * 1.4))
            dx = math.cos(angle) * radius
            dy = math.sin(angle) * radius
            hx = -math.sin(angle) * offset
            hy = math.cos(angle) * offset
            draw.line((cx - dx + hx, cy - dy + hy, cx + dx + hx, cy + dy + hy),
                      fill=ACCENT, width=2)
            # 固定机翼标记。
            draw.line((cx - 22, cy, cx - 6, cy), fill=TEXT, width=2)
            draw.line((cx + 6, cy, cx + 22, cy), fill=TEXT, width=2)
            draw.line((cx, cy - 4, cx, cy + 4), fill=TEXT, width=2)
            draw.ellipse((cx - 3, cy - 3, cx + 3, cy + 3), fill=TEXT)

        draw.line((514, 226, LCD_W - 30, 226), fill=CARD_EDGE)
        draw.text((514, 236), "PITCH", font=self._font(9, bold=True), fill=MUTED)
        self._right_text(draw, LCD_W - 30, 234, "%+05.1f" % pitch,
                         self._font(13, bold=True), TEXT if valid else MUTED)
        draw.text((514, 258), "ROLL", font=self._font(9, bold=True), fill=MUTED)
        self._right_text(draw, LCD_W - 30, 256, "%+05.1f" % roll,
                         self._font(13, bold=True), TEXT if valid else MUTED)
        draw.text((514, 282), "GYRO", font=self._font(9, bold=True), fill=MUTED)
        gyro = "%+d %+d %+d" % state.gyro
        self._right_text(draw, LCD_W - 30, 280, gyro,
                         self._font(9), MUTED if not valid else TEXT)

    def _draw_bottom(self, draw, state: HudState) -> None:
        draw.rounded_rectangle((16, 330, LCD_W - 16, 386), radius=10,
                               fill=(14, 24, 40, 214), outline=CARD_EDGE, width=1)
        draw.text((30, 342), "BRIGHT", font=self._font(10, bold=True), fill=MUTED)
        draw.text((30, 356), "%3d" % state.brightness,
                  font=self._font(16, bold=True), fill=TEXT)
        ratio = max(0.0, min(1.0, (state.brightness - 100) / 154.0))
        draw.rounded_rectangle((86, 362, 216, 370), radius=4, fill=(38, 52, 70, 220))
        draw.rounded_rectangle((86, 362, 86 + int(130 * ratio), 370), radius=4,
                               fill=ACCENT)

        draw.text((250, 342), "FIRMWARE", font=self._font(10, bold=True), fill=MUTED)
        firmware = (state.firmware or "UNKNOWN").strip()
        draw.text((250, 356), firmware[:28], font=self._font(12), fill=TEXT)

        draw.text((472, 342), "HUD", font=self._font(10, bold=True), fill=MUTED)
        draw.text((472, 356), "ON" if state.hud_enabled else "PAUSED",
                  font=self._font(13, bold=True),
                  fill=OK if state.hud_enabled else ACCENT_2)
        draw.text((536, 342), "MODE", font=self._font(10, bold=True), fill=MUTED)
        draw.text((536, 356), "LIVE" if state.connected else "IDLE",
                  font=self._font(13, bold=True),
                  fill=OK if state.connected else BAD)

    def _draw_nav(self, draw, state: HudState) -> None:
        nav = state.nav
        if nav is None:
            return
        cx, cy = COMPASS_CX, 314
        relative = (nav.bearing - state.heading) % 360.0
        rad = math.radians(relative)
        sin, cos = math.sin(rad), math.cos(rad)
        tip = (cx + sin * 17, cy - cos * 17)
        left = (cx + sin * -5 + cos * 8, cy + cos * 5 - sin * 8)
        right = (cx + sin * -5 - cos * 8, cy + cos * 5 + sin * 8)
        draw.polygon([tip, left, right], fill=ACCENT_2)
        draw.ellipse((cx - 2, cy - 2, cx + 2, cy + 2), fill=ACCENT_2)

        distance = _format_distance(nav.distance)
        label = (nav.label or "NAV").strip()
        text = "%s  %.0f deg  %s" % (distance, nav.bearing, label)
        draw.text((cx + 26, cy - 7), text[:34], font=self._font(11, bold=True),
                  fill=ACCENT_2)

    def _draw_paused(self, image, draw, state: HudState, clock: str) -> None:
        draw.rounded_rectangle((60, 52, LCD_W - 60, 348), radius=16,
                               fill=(12, 20, 34, 230), outline=CARD_EDGE, width=1)
        self._center_text(draw, LCD_W // 2, 84, clock,
                          self._font(46, bold=True), TEXT)
        self._center_text(draw, LCD_W // 2, 146, "HUD PAUSED",
                          self._font(26, bold=True), ACCENT_2)
        self._center_text(draw, LCD_W // 2, 186, "Glasses are still connected",
                          self._font(12), MUTED)
        self._center_text(draw, LCD_W // 2, 208,
                          "Resume from the LCD HAT menu (HUD -> ON)",
                          self._font(11), MUTED)
        dot = OK if state.connected else BAD
        draw.ellipse((LCD_W // 2 - 7, 246, LCD_W // 2 + 7, 260), fill=dot)
        self._center_text(draw, LCD_W // 2, 268,
                          "GLASSES ONLINE" if state.connected else "GLASSES OFFLINE",
                          self._font(11, bold=True), dot)
        self._center_text(draw, LCD_W // 2, 304,
                          "fps %.1f   bright %d" % (state.fps, state.brightness),
                          self._font(11), MUTED)


def _format_distance(meters: float) -> str:
    if meters >= 1000:
        return "%.1f km" % (meters / 1000.0)
    return "%.0f m" % max(0.0, meters)
