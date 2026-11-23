# -*- coding: utf-8 -*-
"""HUD 渲染源：640×400 RGBA，把主机侧信息画成“眼镜主显示”的内容。

定位（详见 linux/pi/STREAM.md「导航 HUD」）：
  * 眼镜是主显示，LCD HAT 只做状态/控制面板；
  * 导航 HUD（`glxss stream` / `glxss hud` 的 HUD 模式）：
      - 顶部：当前 heading（手机 GPS 或眼镜罗盘）、手机连接状态、fps
      - 中心：大箭头（相对 bearing = bearing - heading）+ 罗盘刻度环
      - 箭头下方：距离（120 m / 1.2 km）
      - 底部：下一动作文字（右转 / 直行 / 到达）
      - 左侧：光感 lux + 温度、传感器/罗盘状态
      - 右侧：加速度姿态（俯仰/横滚）
      - 没有导航数据时中心显示“等待手机…”
  * 数据来自 `glxss.nav` 的线程安全单槽（Wi-Fi UDP JSON，默认 :9999）。
  * HID 300 ms 一包，HUD 默认 2 fps 重绘；导航数据手机 1 Hz 推送。

字体：优先系统 Noto/DejaVu/Arial/Consolas；动作文字（中文）优先找
Noto CJK / 文泉驿 / 微软雅黑 / 苹方，找不到才退回 ASCII 映射。仓库不自带
字体文件；Pi 上建议 `sudo apt install fonts-wqy-zenhei`（见 STREAM.md）。

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

# 罗盘中心/半径（旧的“时钟/罗盘”HUD 版式；保留给 _draw_compass / 测试）。
COMPASS_CX = 320
COMPASS_CY = 194
COMPASS_R = 100

# 导航 HUD 版式：中心刻度环 + 大箭头 + 下方距离。
NAV_CX = 320
NAV_CY = 176
NAV_R = 92


@dataclass
class NavInfo:
    """HUD 需要的导航信息（``glxss.nav.NavFrame`` 的渲染副本）。

    ``bearing`` 是目标绝对方位还是相对角，由 ``heading`` 决定：
    ``heading`` 有值时箭头角度 = ``bearing - heading``；没有时直接用
    ``bearing``（手动测试/``ctl nav`` 的语义）。
    """

    bearing: float = 0.0     # 目标方位（deg，0=北，顺时针）
    distance: float = 0.0    # 距离（米）
    label: str = ""          # 例如 "右转" / "Main St" / "到达"
    heading: Optional[float] = None  # 手机当前朝向（deg）；None=只用 bearing


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
                 heading_offset: float = 0.0,
                 horizon_invert_x: bool = False,
                 horizon_invert_y: bool = False,
                 nav_source=None):
        if Image is None:
            raise GlxssError("HUD 渲染需要 Pillow：python -m pip install Pillow")
        if np is None:
            raise GlxssError("HUD 渲染需要 numpy：python -m pip install numpy")
        self._lock = threading.RLock()
        self._font_cache: Dict[Tuple[int, bool], object] = {}
        self._cjk_font_cache: Dict[Tuple[int, bool], object] = {}
        self._cjk_font_checked = False
        self._background_cache = None
        self._render_interval = max(0.05, float(render_interval))
        self._last_render = 0.0
        self._cache = None
        self._rgba_cache = None
        self._rgba_last_render = 0.0
        self._test_until = 0.0
        self._test_rgb = None
        self._hid_stop = threading.Event()
        self._hid_thread: Optional[threading.Thread] = None
        self._hid_error = ""
        # 面板 180° 安装时，整张 HUD 在发送前会翻转 180°；姿态小地平线的
        # pitch/roll 需要取反才能在用户视角里符合航空惯例（数值文字不取反）。
        self._horizon_invert_x = bool(horizon_invert_x)
        self._horizon_invert_y = bool(horizon_invert_y)
        self.state = demo_state() if demo else HudState()
        self.state.brightness = int(brightness)
        if firmware:
            self.state.firmware = str(firmware)
        self.state.heading_offset = float(heading_offset)
        # 外部导航单槽（glxss.nav.LatestNav）：HUD 每次取帧前轮询，变化才重绘。
        self._nav_source = nav_source
        self._nav_key = object() if nav_source is not None else None
        if nav_source is not None:
            self._poll_nav()

    # -- 状态更新 ----------------------------------------------------------
    def snapshot(self) -> HudState:
        """取一致的状态副本；顺带把外部导航单槽同步进来（变化才重绘）。"""
        self._poll_nav()
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

    def set_nav(self, bearing: float, distance: float, label: str = "",
                heading: Optional[float] = None) -> None:
        """手动设置导航帧（兼容旧接口）。

        有 ``nav_source`` 时写进共享单槽（``ttl=0`` 持久，直到 nav-clear 或
        手机新包覆盖），避免下一次轮询把这里设的值冲掉；没有时直接写 state。
        """
        if self._nav_source is not None:
            self._nav_source.update(float(bearing), float(distance), str(label),
                                    heading, ttl=0.0, source="hud")
            self._poll_nav()
            return
        with self._lock:
            self.state.nav = NavInfo(float(bearing), float(distance),
                                     str(label), heading)
            self.state.updated_at = time.time()
        self._invalidate()

    def clear_nav(self) -> None:
        if self._nav_source is not None:
            self._nav_source.clear()
        with self._lock:
            self.state.nav = None
            self.state.updated_at = time.time()
        self._nav_key = None
        self._invalidate()

    def set_nav_source(self, source) -> None:
        """接上 `glxss.nav.LatestNav`（或任何有 ``snapshot()`` 的单槽）。"""
        with self._lock:
            self._nav_source = source
            self._nav_key = object()
        self._poll_nav()

    def _poll_nav(self) -> bool:
        """把共享单槽的最新帧同步到 ``state.nav``；变化时让帧缓存失效。

        返回 True 表示状态变了（grab/render 只需在变化时重绘）。放在取帧
        路径上而不是后台线程：HUD 的 2 fps 渲染本身就是节拍器，手机 1 Hz
        的包最迟 0.5 s 反映到画面上。
        """
        source = self._nav_source
        if source is None:
            return False
        try:
            frame = source.snapshot()
        except Exception:
            frame = None
        key = None if frame is None else (
            frame.received_at, frame.bearing, frame.distance,
            frame.label, frame.heading)
        with self._lock:
            if key == self._nav_key:
                return False
            self._nav_key = key
            if frame is None:
                self.state.nav = None
            else:
                self.state.nav = NavInfo(
                    float(frame.bearing), float(frame.distance),
                    str(frame.label or ""), frame.heading)
            self.state.updated_at = time.time()
        self._invalidate()
        return True

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
        self._rgba_last_render = 0.0

    # -- HID 轮询 ----------------------------------------------------------
    def attach_hid(self, dev, interval: float = hid.SENSOR_POLL_INTERVAL,
                   timeout: int = 1500, lock=None, on_key=None) -> None:
        """后台按 interval 读 28 B 报告；lock 用于和主线程的 bulk OUT 串行化。

        `on_key(report)` 在检测到 Fn 键“按下”边沿时回调；monitor/HUD 切换用。
        """
        if self._hid_thread is not None and self._hid_thread.is_alive():
            return
        self._hid_stop.clear()
        self._hid_thread = threading.Thread(
            target=self._hid_loop, args=(dev, interval, timeout, lock, on_key),
            name="glxss-hud-hid", daemon=True)
        self._hid_thread.start()

    def _hid_loop(self, dev, interval: float, timeout: int, lock, on_key=None) -> None:
        failures = 0
        key_was_down = False
        next_at = time.monotonic()
        while not self._hid_stop.is_set():
            try:
                if lock is not None:
                    with lock:
                        report = hid.read_report(dev, timeout)
                else:
                    report = hid.read_report(dev, timeout)
                self.update_report(report)
                if (on_key is not None and report.is_fn_key
                        and report.pressed and not key_was_down):
                    try:
                        on_key(report)
                    except Exception as exc:  # 回调失败不能弄死 HID 线程
                        print("[警告] HID 按键回调失败: %s" % exc, file=sys.stderr)
                key_was_down = report.pressed
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
        self._poll_nav()
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

    def grab_rgba(self):
        """快路径：直接返回 640×400 RGBA，省掉 RGB 转换和再映射。"""
        now = time.monotonic()
        if self._test_rgb is not None and now < self._test_until:
            rgb = self._test_rgb
            rgba = np.empty((LCD_H, LCD_W, 4), dtype=np.uint8)
            rgba[:, :, :3] = rgb
            rgba[:, :, 3] = 255
            return rgba
        self._poll_nav()
        with self._lock:
            cached = self._rgba_cache
            last = self._rgba_last_render
            state = replace(self.state)
        if cached is not None and (now - last) < self._render_interval:
            return cached
        image = self.render(state)
        rgba = np.asarray(image, dtype=np.uint8).copy()
        with self._lock:
            self._rgba_cache = rgba
            self._rgba_last_render = time.monotonic()
        return rgba

    def render(self, state: Optional[HudState] = None):
        """渲染一张 640×400 RGBA `PIL.Image`；不传 state 时取内部快照。"""
        if Image is None:  # pragma: no cover - 构造时已检查
            raise GlxssError("HUD 渲染需要 Pillow")
        self._poll_nav()
        state = state if state is not None else self.snapshot()
        image = self._background().copy()
        draw = ImageDraw.Draw(image, "RGBA")
        clock = time.strftime("%H:%M", time.localtime())

        if not state.hud_enabled:
            self._draw_paused(image, draw, state, clock)
            return image

        self._draw_top(draw, state, clock)
        self._draw_left(draw, state)
        self._draw_nav_scene(draw, state)
        self._draw_attitude(draw, state)
        self._draw_bottom(draw, state)
        return image

    def save_png(self, path: str, rotate: int = 0) -> None:
        """保存一张 RGBA PNG（离线核对图层/字体用；rotate=180 与投屏一致）。"""
        parent = os.path.dirname(os.path.abspath(path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        image = self.render()
        if int(rotate) % 360 == 180:
            image = image.rotate(180)
        image.save(path)

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

    def _cjk_font(self, size: int, bold: bool = False):
        """找一款有中文字形的字体；找不到返回 None（调用方再用 ASCII 兜底）。"""
        key = (int(size), bool(bold))
        if key in self._cjk_font_cache:
            return self._cjk_font_cache[key]
        font = None
        if ImageFont is not None:
            win_dir = os.environ.get("WINDIR", r"C:\Windows")
            candidates = []
            if os.name == "nt":
                names = (("msyhbd.ttc", "msyh.ttc", "simhei.ttf") if bold
                         else ("msyh.ttc", "msyhbd.ttc", "simhei.ttf"))
                candidates += [os.path.join(win_dir, "Fonts", name)
                               for name in names]
            if bold:
                candidates += [
                    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
                    "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Bold.otf",
                    "/System/Library/Fonts/PingFang.ttc",
                ]
            candidates += [
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf",
                "/System/Library/Fonts/PingFang.ttc",
                "/System/Library/Fonts/STHeiti Light.ttc",
            ]
            for path in candidates:
                if not path or not os.path.exists(path):
                    continue
                try:
                    font = ImageFont.truetype(path, int(size))
                    break
                except Exception:
                    continue
        self._cjk_font_cache[key] = font
        self._cjk_font_checked = True
        return font

    def _has_cjk_font(self) -> bool:
        return self._cjk_font(16) is not None

    def _text_font(self, text: str, size: int, bold: bool = False):
        """文字里有非 ASCII 时优先 CJK 字体，否则用常规字体。"""
        if any(ord(ch) > 127 for ch in str(text)):
            font = self._cjk_font(size, bold)
            if font is not None:
                return font
        return self._font(size, bold)

    def _text_width(self, draw, text: str, font) -> int:
        try:
            return int(draw.textlength(text, font=font))
        except Exception:
            box = draw.textbbox((0, 0), text, font=font)
            return box[2] - box[0]

    def _fit_text(self, draw, text: str, font, max_width: int) -> str:
        """按像素宽度截断（中文按字形宽度算），末尾加省略号。"""
        if font is None or max_width <= 0:
            return text
        if self._text_width(draw, text, font) <= max_width:
            return text
        ellipsis = "…" if any(ord(ch) > 127 for ch in text) else "..."
        body = text
        while body and self._text_width(draw, body + ellipsis, font) > max_width:
            body = body[:-1]
        return (body + ellipsis) if body else ellipsis

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
        draw.rounded_rectangle((12, 10, LCD_W - 12, 58), radius=10,
                               fill=(14, 24, 40, 224), outline=CARD_EDGE, width=1)
        draw.text((28, 16), clock, font=self._font(21, bold=True), fill=TEXT)
        draw.text((30, 40), "LOCAL", font=self._font(8), fill=MUTED)
        draw.line((86, 18, 86, 50), fill=CARD_EDGE)

        # 当前 heading：手机 GPS 优先，其次眼镜 HID 罗盘；都没有显示 --。
        nav = state.nav
        heading: Optional[float] = None
        if nav is not None and nav.heading is not None:
            heading = float(nav.heading)
        elif state.compass_valid or any(state.compass):
            heading = state.heading
        heading_text = ("%03.0f°" % heading) if heading is not None else "--°"
        draw.text((99, 13), "HEADING", font=self._font(8, bold=True), fill=MUTED)
        draw.text((98, 26), heading_text, font=self._font(24, bold=True),
                  fill=ACCENT if heading is not None else MUTED)
        draw.line((178, 18, 178, 50), fill=CARD_EDGE)

        link = nav is not None
        dot = OK if link else ACCENT_2
        draw.ellipse((192, 26, 204, 38), fill=dot)
        draw.text((212, 16), "PHONE LINK" if link else "WAITING PHONE",
                  font=self._font(12, bold=True), fill=dot)
        glasses = "GLASSES ONLINE" if state.connected else "GLASSES OFFLINE"
        draw.text((212, 37), "NAV UDP  " + glasses,
                  font=self._font(8), fill=MUTED)

        fps_text = "%.1f fps" % state.fps
        self._right_text(draw, LCD_W - 26, 14, fps_text,
                         self._font(18, bold=True), ACCENT)
        hint = "HUD ON  v%s" % __version__ if state.hud_enabled else "HUD PAUSED"
        self._right_text(draw, LCD_W - 26, 38, hint, self._font(8), MUTED)

    def _draw_left(self, draw, state: HudState) -> None:
        draw.rounded_rectangle((16, 86, 140, 306), radius=12,
                               fill=CARD, outline=CARD_EDGE, width=1)
        draw.text((30, 96), "LIGHT", font=self._font(10, bold=True), fill=MUTED)
        draw.text((30, 110), "%d" % state.lux, font=self._font(26, bold=True),
                  fill=ACCENT_2)
        draw.text((30, 140), "lux", font=self._font(10), fill=MUTED)

        # 光感条：sqrt 压缩，室内 0..1000 也能看出变化。
        bar_y = 156
        ratio = min(1.0, math.sqrt(max(0, state.lux)) / math.sqrt(1000.0))
        draw.rounded_rectangle((30, bar_y, 126, bar_y + 6), radius=3,
                               fill=(40, 54, 72, 220))
        draw.rounded_rectangle((30, bar_y, 30 + int(96 * ratio), bar_y + 6),
                               radius=3, fill=ACCENT_2)

        draw.line((30, 176, 126, 176), fill=CARD_EDGE)
        draw.text((30, 184), "TEMP", font=self._font(9, bold=True), fill=MUTED)
        draw.text((30, 197), "%.1f°C" % state.temperature,
                  font=self._font(19, bold=True), fill=TEXT)

        draw.line((30, 228, 126, 228), fill=CARD_EDGE)
        draw.text((30, 236), "SENSOR", font=self._font(9, bold=True), fill=MUTED)
        draw.text((30, 248), "OK" if state.sensors_valid else "NO DATA",
                  font=self._font(11, bold=True),
                  fill=OK if state.sensors_valid else BAD)
        draw.line((30, 268, 126, 268), fill=CARD_EDGE)
        draw.text((30, 276), "MAG", font=self._font(9, bold=True), fill=MUTED)
        draw.text((30, 288), "OK" if state.compass_valid else "N/A",
                  font=self._font(11, bold=True),
                  fill=OK if state.compass_valid else BAD)

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
        # 罗盘无数据时不要伪装成 000°：显示 -- + NO MAG。
        value_text = "%03.0f" % heading if valid else "--"
        self._center_text(draw, cx, cy - 20, value_text,
                          self._font(30 if not valid else 27, bold=True),
                          TEXT if valid else MUTED)
        if valid:
            self._center_text(draw, cx, cy + 9, "deg",
                              self._font(10, bold=True), MUTED)
        else:
            self._center_text(draw, cx, cy + 10, "NO MAG",
                              self._font(10, bold=True), BAD)

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
            # 面板 180° 时整张图会翻转，绘制用的角度先取反（数字保持真实值）。
            hp = -pitch if self._horizon_invert_y else pitch
            hr = -roll if self._horizon_invert_x else roll
            angle = math.radians(-hr)
            offset = max(-radius + 10, min(radius - 10, hp * 1.4))
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

    def _draw_nav_scene(self, draw, state: HudState) -> None:
        """中心导航层：罗盘刻度环 + 大箭头 + 距离；无数据时显示等待手机。"""
        cx, cy, r = NAV_CX, NAV_CY, NAV_R
        nav = state.nav
        # 刻度环按当前朝向旋转：N 始终指向真实北方。无 nav.heading 时按 0
        # 处理（ctl nav 手动测试），箭头就是 bearing 本身。
        ring_heading = 0.0
        if nav is not None and nav.heading is not None:
            ring_heading = float(nav.heading)

        draw.ellipse((cx - r - 7, cy - r - 7, cx + r + 7, cy + r + 7),
                     outline=(38, 58, 84, 120), width=1)
        draw.ellipse((cx - r, cy - r, cx + r, cy + r),
                     outline=(66, 112, 158, 230 if nav is not None else 150),
                     width=2)

        for deg in range(0, 360, 15):
            rad = math.radians(deg - ring_heading)
            major = deg % 90 == 0
            mid = deg % 45 == 0
            inner = r - (13 if major else 8 if mid else 4)
            x1 = cx + math.sin(rad) * inner
            y1 = cy - math.cos(rad) * inner
            x2 = cx + math.sin(rad) * r
            y2 = cy - math.cos(rad) * r
            if major:
                color = ACCENT
            elif mid:
                color = (150, 176, 202, 200)
            else:
                color = (90, 116, 142, 150)
            draw.line((x1, y1, x2, y2), fill=color, width=2 if major else 1)

        for text, deg in (("N", 0), ("E", 90), ("S", 180), ("W", 270)):
            rad = math.radians(deg - ring_heading)
            lx = cx + math.sin(rad) * (r - 20)
            ly = cy - math.cos(rad) * (r - 20)
            font = self._font(12 if text == "N" else 10,
                              bold=(text == "N"))
            color = ACCENT if text == "N" else (176, 196, 216, 220)
            self._center_text(draw, int(lx), int(ly - 7), text, font, color)

        # 正上方的小三角 = 当前朝向（环随 heading 旋转后，N 在真实方位上）。
        draw.polygon([(cx, cy - r - 3), (cx - 6, cy - r - 13),
                      (cx + 6, cy - r - 13)], fill=ACCENT)

        if nav is None:
            self._draw_waiting(draw, cx, cy)
            self._center_text(draw, cx, 268, "--",
                              self._font(30, bold=True), MUTED)
            return

        relative = ((nav.bearing - nav.heading) % 360.0
                    if nav.heading is not None else nav.bearing % 360.0)
        self._draw_nav_arrow(draw, cx, cy, relative)

        distance = _format_distance(nav.distance)
        font = self._font(36, bold=True)
        width = self._text_width(draw, distance, font)
        draw.rounded_rectangle((cx - width // 2 - 12, 258,
                                cx + width // 2 + 12, 310),
                               radius=9, fill=(6, 10, 20, 218))
        self._center_text(draw, cx, 264, distance, font, TEXT)

    def _draw_nav_arrow(self, draw, cx: int, cy: int, relative: float) -> None:
        """大箭头：0° 指向正上，顺时针旋转。带一层半透明辉光。"""
        rad = math.radians(relative)
        sin, cos = math.sin(rad), math.cos(rad)

        def point(forward: float, side: float):
            return (cx + sin * forward + cos * side,
                    cy - cos * forward + sin * side)

        glow = [point(74, 0), point(-28, 34), point(-8, 0), point(-28, -34)]
        arrow = [point(66, 0), point(-22, 28), point(-4, 0), point(-22, -28)]
        draw.polygon(glow, fill=ACCENT[:3] + (48,))
        draw.polygon(arrow, fill=ACCENT)
        draw.line(arrow + [arrow[0]], fill=TEXT, width=2, joint="curve")
        draw.ellipse((cx - 5, cy - 5, cx + 5, cy + 5), fill=ACCENT)

    def _draw_waiting(self, draw, cx: int, cy: int) -> None:
        if self._has_cjk_font():
            text = "等待手机…"
        else:
            text = "WAITING FOR PHONE…"
        font = self._text_font(text, 24, bold=True)
        # 暗色药丸压住刻度环/网格，等待文字在眼镜上更清楚。
        draw.rounded_rectangle((cx - 132, cy - 36, cx + 132, cy + 38),
                               radius=10, fill=(4, 8, 16, 212))
        self._center_text(draw, cx, cy - 20, text, font, ACCENT_2)
        self._center_text(draw, cx, cy + 18, "Wi-Fi UDP  JSON  1 Hz",
                          self._font(10), MUTED)

    def _draw_bottom(self, draw, state: HudState) -> None:
        """底部动作条：大号“下一动作”文字（右转/直行/到达）。"""
        draw.rounded_rectangle((16, 316, LCD_W - 16, 388), radius=10,
                               fill=(14, 24, 40, 224), outline=CARD_EDGE, width=1)
        nav = state.nav
        if nav is None:
            text = "等待手机…" if self._has_cjk_font() else "WAITING FOR PHONE…"
            font = self._text_font(text, 20, bold=True)
            self._center_text(draw, LCD_W // 2, 322, text, font, ACCENT_2)
            self._center_text(draw, LCD_W // 2, 356, "NAV DATA · UDP :9999",
                              self._font(9), MUTED)
            return

        label = (nav.label or "").strip()
        if not label:
            label = self._direction_word(nav)
        if not self._has_cjk_font() and any(ord(ch) > 127 for ch in label):
            label = self._fallback_action(label)
        font = self._text_font(label, 30, bold=True)
        label = self._fit_text(draw, label, font, LCD_W - 80)
        self._center_text(draw, LCD_W // 2, 318, label, font, ACCENT_2)

        draw.text((30, 368), "BRT %3d" % state.brightness,
                  font=self._font(9), fill=MUTED)
        self._center_text(draw, LCD_W // 2, 368, "NEXT ACTION",
                          self._font(8, bold=True), MUTED)
        self._right_text(draw, LCD_W - 30, 368, "BRG %03.0f°" % nav.bearing,
                         self._font(9), MUTED)

    def _direction_word(self, nav: NavInfo) -> str:
        """label 为空时按相对方位给一个动作词（中文字体缺失时用英文）。"""
        if nav.heading is None:
            relative = nav.bearing % 360.0
        else:
            relative = (nav.bearing - nav.heading) % 360.0
        if relative < 20 or relative >= 340:
            zh, en = "直行", "STRAIGHT"
        elif relative < 160:
            zh, en = "右转", "RIGHT"
        elif relative < 200:
            zh, en = "掉头", "U TURN"
        elif relative < 340:
            zh, en = "左转", "LEFT"
        else:
            zh, en = "直行", "STRAIGHT"
        return zh if self._has_cjk_font() else en

    @staticmethod
    def _fallback_action(label: str) -> str:
        """没有中文字体时，把常见动作词映射成短英文，避免显示方框。"""
        mapping = {
            "右转": "RIGHT", "左转": "LEFT", "直行": "STRAIGHT",
            "到达": "ARRIVE", "抵达": "ARRIVE", "掉头": "U TURN",
            "稍向右": "SLIGHT RIGHT", "稍向左": "SLIGHT LEFT",
            "靠右": "KEEP RIGHT", "靠左": "KEEP LEFT",
            "进入环岛": "ROUNDABOUT", "上坡": "UPHILL", "下坡": "DOWNHILL",
        }
        text = mapping.get(label.strip())
        if text:
            return text
        if all(ord(ch) < 128 for ch in label):
            return label
        return "NAV"

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
