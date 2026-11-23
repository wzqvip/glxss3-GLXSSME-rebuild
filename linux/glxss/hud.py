# -*- coding: utf-8 -*-
"""HUD 渲染源：640×400 RGBA，把主机侧信息画成“眼镜主显示”的内容。

定位（详见 linux/pi/STREAM.md「导航 HUD」）：
  * 眼镜是主显示，LCD HAT 只做状态/控制面板；
  * 导航 HUD（`glxss stream` / `glxss hud` 的 HUD 模式）：
      - 顶部：剩余时间/剩余距离（有就显示）、手机连接状态、fps
      - 中心：大号机动箭头（按 label / maneuver 选：左转/右转/直行/环岛/掉头/到达）
      - 箭头下方：距离（500 m / 1.2 km）
      - 底部：转向指令文字（右转进入 xx 路）
      - 左侧：光感 lux + 温度
      - 右侧：加速度姿态（俯仰/横滚）
      - 没有导航数据时中心显示“等待手机导航…”
  * **不画罗盘**：眼镜没有磁力计，`bearing`/`heading` 可空，箭头只按机动类型选。
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

from . import __version__, hid, nav
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

# 旧“时钟/罗盘”版式常量已删除：眼镜没有磁力计，HUD 不再画罗盘。
# 导航 HUD 版式：中心大箭头 + 下方距离；箭头大小/位置见 _draw_nav_scene。
NAV_CX = 320
NAV_CY = 172
NAV_SCALE = 1.95


@dataclass
class NavInfo:
    """HUD 需要的导航信息（``glxss.nav.NavFrame`` 的渲染副本）。

    箭头不再按方位角旋转：``bearing``/``heading`` 只保留给调试与 state 输出，
    中心图标由 ``maneuver``（左转/右转/直行/环岛/掉头/到达）决定。
    """

    bearing: Optional[float] = None  # 目标方位（deg，可空；不参与箭头）
    distance: float = 0.0            # 到下一动作的距离（米）
    label: str = ""                  # 转向指令文字（右转进入 xx 路）
    heading: Optional[float] = None  # 手机朝向（可空；HUD 不显示罗盘）
    maneuver: str = ""                  # 机动类型（选箭头用；空=按 label 兜底）
    eta: Optional[float] = None      # 剩余时间（秒）
    remain: Optional[float] = None   # 剩余路线距离（米）
    source: str = ""                 # maps / ctl / …

    def maneuver_name(self) -> str:
        """机动类型；老数据没有 maneuver 时按 label 关键词兜底。"""
        return self.maneuver or nav.classify_maneuver(self.label)


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
    """离线演示数据：用于 `hud --dry-run` / HAT 预览，不接触硬件。

    导航演示按真实手机包的形状给：只有 label/distance/eta/remain，没有
    bearing/heading（眼镜没有磁力计）。
    """
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
        nav=NavInfo(distance=320.0, label="右转进入 人民路",
                    maneuver="right", eta=180.0, remain=2400.0,
                    source="demo"),
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

    def set_nav(self, bearing: Optional[float], distance: float,
                label: str = "", heading: Optional[float] = None, *,
                maneuver: str = "", eta: Optional[float] = None,
                remain: Optional[float] = None) -> None:
        """手动设置导航帧（兼容旧接口；bearing/heading 可传 None）。

        有 ``nav_source`` 时写进共享单槽（``ttl=0`` 持久，直到 nav-clear 或
        手机新包覆盖），避免下一次轮询把这里设的值冲掉；没有时直接写 state。
        """
        if self._nav_source is not None:
            self._nav_source.update(
                bearing, float(distance), str(label), heading,
                eta=eta, remain=remain, maneuver=maneuver,
                ttl=0.0, source="hud")
            self._poll_nav()
            return
        with self._lock:
            self.state.nav = NavInfo(
                None if bearing is None else float(bearing),
                float(distance), str(label), heading,
                nav.maneuver_of(maneuver, label), eta, remain, "hud")
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
            frame.label, frame.heading, frame.maneuver, frame.eta,
            frame.remain)
        with self._lock:
            if key == self._nav_key:
                return False
            self._nav_key = key
            if frame is None:
                self.state.nav = None
            else:
                self.state.nav = NavInfo(
                    frame.bearing, float(frame.distance),
                    str(frame.label or ""), frame.heading,
                    frame.maneuver or nav.classify_maneuver(frame.label),
                    frame.eta, frame.remain, frame.source)
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

        # 剩余时间 / 剩余距离：手机给了才显示，没有就 "--"（不画罗盘/heading）。
        nav = state.nav
        remain_text = _format_remaining(nav, self._has_cjk_font()) if nav else "--"
        draw.text((99, 13), "REMAINING", font=self._font(8, bold=True), fill=MUTED)
        font = self._text_font(remain_text, 17, bold=True)
        remain_text = self._fit_text(draw, remain_text, font, 146)
        draw.text((98, 25), remain_text, font=font,
                  fill=ACCENT if nav is not None else MUTED)
        draw.line((250, 18, 250, 50), fill=CARD_EDGE)

        link = nav is not None
        dot = OK if link else ACCENT_2
        draw.ellipse((264, 26, 276, 38), fill=dot)
        draw.text((284, 16), "PHONE LINK" if link else "WAITING PHONE",
                  font=self._font(12, bold=True), fill=dot)
        glasses = "GLASSES ONLINE" if state.connected else "GLASSES OFFLINE"
        draw.text((284, 37), "NAV UDP  " + glasses,
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

        # 只保留 IMU 有无数据（罗盘一栏已删除：眼镜没有磁力计）。
        draw.line((30, 228, 126, 228), fill=CARD_EDGE)
        draw.text((30, 236), "IMU", font=self._font(9, bold=True), fill=MUTED)
        draw.text((30, 248), "OK" if state.acc_valid else "NO DATA",
                  font=self._font(11, bold=True),
                  fill=OK if state.acc_valid else BAD)

        draw.line((30, 268, 126, 268), fill=CARD_EDGE)
        draw.text((30, 276), "HUD", font=self._font(9, bold=True), fill=MUTED)
        draw.text((30, 288), "%d" % state.brightness,
                  font=self._font(11, bold=True), fill=TEXT)

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
        """中心导航层：按机动类型画大箭头 + 箭头下方距离；无数据时显示等待。

        这里**没有罗盘刻度环/方位角旋转**：眼镜没有磁力计，箭头只表达“左转/
        右转/直行/环岛/掉头/到达”，不表达绝对方向。
        """
        cx, cy = NAV_CX, NAV_CY
        nav = state.nav
        if nav is None:
            self._draw_waiting(draw, cx, cy)
            self._center_text(draw, cx, 262, "--",
                              self._font(30, bold=True), MUTED)
            return

        maneuver = nav.maneuver_name()
        # 柔和背光：让箭头在明亮环境下也有对比，但不画成罗盘。
        # 用不透明深色（老版 Pillow 的 ImageDraw 不做 alpha 混合，半透明大色块
        # 在 PNG 里会变成灰片；LCD 端也只用 RGB）。
        draw.ellipse((cx - 108, cy - 104, cx + 108, cy + 104),
                     fill=(15, 25, 44, 255))
        self._draw_maneuver(draw, cx, cy, NAV_SCALE, maneuver)

        distance = _format_distance(nav.distance)
        if nav.distance <= 0:
            # 到达时距离通常为 0，用“到达/--”比“0 m”合理。
            distance = (_maneuver_word("arrive", self._has_cjk_font())
                        if maneuver == "arrive" else "--")
        font = self._font(40 if len(distance) <= 6 else 30, bold=True)
        width = self._text_width(draw, distance, font)
        draw.rounded_rectangle((cx - width // 2 - 16, 248,
                                cx + width // 2 + 16, 306),
                               radius=10, fill=(6, 10, 20, 222),
                               outline=CARD_EDGE, width=1)
        self._center_text(draw, cx, 254, distance, font, TEXT)

    def _draw_maneuver(self, draw, cx: int, cy: int, scale: float,
                       maneuver: str) -> None:
        """把单位坐标里的机动图标缩放/居中画到 (cx, cy)。"""
        shapes, points = _maneuver_shapes(maneuver)
        xs = [p[0] for p in points]
        ys = [p[1] for p in points]
        ox = (min(xs) + max(xs)) / 2.0
        oy = (min(ys) + max(ys)) / 2.0

        def place(point):
            return (cx + (point[0] - ox) * scale,
                    cy + (point[1] - oy) * scale)

        color = _maneuver_color(maneuver)
        for shape in shapes:
            kind = shape[0]
            if kind == "line":
                pts = [place(p) for p in shape[1]]
                width = max(2, int(round(shape[2] * scale)))
                draw.line(pts, fill=color, width=width, joint="curve")
                # 线端补圆，避免粗线在转折处露直角。
                for x, y in (pts[0], pts[-1]):
                    r = width / 2.0
                    draw.ellipse((x - r, y - r, x + r, y + r), fill=color)
            elif kind == "poly":
                pts = [place(p) for p in shape[1]]
                draw.polygon(pts, fill=color)
                draw.line(pts + [pts[0]], fill=TEXT, width=2, joint="curve")
            elif kind == "ellipse":
                ex, ey, rx, ry = shape[1]
                width = max(2, int(round(shape[2] * scale)))
                p0 = place((ex - rx, ey - ry))
                p1 = place((ex + rx, ey + ry))
                draw.ellipse((min(p0[0], p1[0]), min(p0[1], p1[1]),
                              max(p0[0], p1[0]), max(p0[1], p1[1])),
                             outline=color, width=width)

    def _draw_waiting(self, draw, cx: int, cy: int) -> None:
        if self._has_cjk_font():
            text = "等待手机导航…"
        else:
            text = "WAITING FOR PHONE…"
        font = self._text_font(text, 24, bold=True)
        # 暗色药丸压住网格，等待文字在眼镜上更清楚。
        draw.rounded_rectangle((cx - 148, cy - 38, cx + 148, cy + 40),
                               radius=10, fill=(4, 8, 16, 214),
                               outline=(40, 60, 86, 180), width=1)
        self._center_text(draw, cx, cy - 22, text, font, ACCENT_2)
        self._center_text(draw, cx, cy + 18, "Wi-Fi UDP JSON 1 Hz  :9999",
                          self._font(10), MUTED)

    def _draw_bottom(self, draw, state: HudState) -> None:
        """底部指令条：大号转向指令文字（右转进入 xx 路）+ 来源/亮度。"""
        draw.rounded_rectangle((16, 316, LCD_W - 16, 388), radius=10,
                               fill=(14, 24, 40, 224), outline=CARD_EDGE, width=1)
        nav = state.nav
        if nav is None:
            text = "等待手机导航…" if self._has_cjk_font() else "WAITING FOR PHONE…"
            font = self._text_font(text, 20, bold=True)
            self._center_text(draw, LCD_W // 2, 322, text, font, ACCENT_2)
            self._center_text(draw, LCD_W // 2, 356,
                              "NAV DATA · UDP :9999 · glxss ctl nav",
                              self._font(9), MUTED)
            return

        maneuver = nav.maneuver_name()
        label = (nav.label or "").strip()
        if not label:
            label = _maneuver_word(maneuver, self._has_cjk_font())
        if not self._has_cjk_font() and any(ord(ch) > 127 for ch in label):
            label = _maneuver_word(maneuver, False)
        font = self._text_font(label, 29, bold=True)
        label = self._fit_text(draw, label, font, LCD_W - 80)
        self._center_text(draw, LCD_W // 2, 320, label, font, ACCENT_2)

        draw.text((30, 366), "BRT %d" % state.brightness,
                  font=self._font(9), fill=MUTED)
        self._center_text(draw, LCD_W // 2, 366, "NEXT ACTION",
                          self._font(8, bold=True), MUTED)
        source = (nav.source or "").upper().split(":")[0] or "NAV"
        self._right_text(draw, LCD_W - 30, 366, source[:14],
                         self._font(9), MUTED)

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


def _format_eta(seconds: float, cjk: bool = True) -> str:
    """剩余秒数 → “2 分” / “1 小时 5 分” / “2 min” / “1 h 5 min”。"""
    seconds = max(0.0, float(seconds))
    if seconds < 60:
        return "<1 分" if cjk else "<1 min"
    minutes = int(round(seconds / 60.0))
    if minutes < 60:
        return ("%d 分" % minutes) if cjk else ("%d min" % minutes)
    hours, rest = divmod(minutes, 60)
    if cjk:
        return "%d 小时 %d 分" % (hours, rest) if rest else "%d 小时" % hours
    return "%d h %d min" % (hours, rest) if rest else "%d h" % hours


def _format_remaining(nav: "NavInfo", cjk: bool = True) -> str:
    """顶部“剩余时间 · 剩余距离”；都没有返回 --。"""
    parts = []
    if nav.eta is not None and nav.eta > 0:
        parts.append(_format_eta(nav.eta, cjk))
    if nav.remain is not None and nav.remain > 0:
        parts.append(_format_distance(nav.remain))
    return " · ".join(parts) if parts else "--"


_MANEUVER_WORDS = {
    "left": ("左转", "LEFT"),
    "right": ("右转", "RIGHT"),
    "slight-left": ("稍向左", "SLIGHT LEFT"),
    "slight-right": ("稍向右", "SLIGHT RIGHT"),
    "straight": ("直行", "STRAIGHT"),
    "roundabout": ("环岛", "ROUNDABOUT"),
    "uturn": ("掉头", "U TURN"),
    "arrive": ("到达", "ARRIVE"),
    "unknown": ("", ""),
}


def _maneuver_word(maneuver: str, cjk: bool = True) -> str:
    zh, en = _MANEUVER_WORDS.get(maneuver, ("", ""))
    if cjk:
        return zh or en or ""
    return en or zh or ""


def _maneuver_color(maneuver: str):
    if maneuver == "arrive":
        return OK
    if maneuver in ("roundabout", "uturn"):
        return ACCENT_2
    return ACCENT


def _arrow_head(tip, direction, length: float = 20.0,
                half: float = 16.0):
    """箭头的三角形（tip=尖端，direction=指向，length/half=长/半宽）。"""
    tx, ty = float(tip[0]), float(tip[1])
    dx, dy = float(direction[0]), float(direction[1])
    norm = math.hypot(dx, dy) or 1.0
    dx, dy = dx / norm, dy / norm
    bx, by = tx - dx * length, ty - dy * length
    px, py = -dy, dx
    return [(tx, ty), (bx + px * half, by + py * half),
            (bx - px * half, by - py * half)]


def _uturn_arc():
    """掉头图标的半圆弧（屏幕坐标，y 向下；从右端绕上方到左端）。"""
    points = []
    for step in range(0, 13):
        angle = math.radians(-180.0 * step / 12.0)
        points.append((14.0 * math.cos(angle),
                       -4.0 + 14.0 * math.sin(angle)))
    return points


def _roundabout_ring():
    """环岛圆弧：底部入口，逆时针绕到正上方缺口（末端接箭头）。"""
    points = []
    for step in range(0, 16):
        angle = math.radians(240.0 - 300.0 * step / 15.0)
        points.append((20.0 * math.cos(angle),
                       2.0 + 20.0 * math.sin(angle)))
    return points


def _maneuver_shapes(maneuver: str):
    """单位坐标（约 -50..50，y 向下）里的机动图标。

    返回 ``(shapes, bbox_points)``；shapes 里每条是
    ``("line", points, width)`` / ``("poly", points)`` /
    ``("ellipse", (cx, cy, rx, ry), width)``。不依赖任何图标资源，
    全部用几何图形画，肉眼可辨左转/右转/直行/环岛/掉头/到达。
    """
    if maneuver in ("left", "right"):
        right = maneuver == "right"
        sign = 1.0 if right else -1.0
        stem = [(0.0, 34.0), (0.0, -10.0), (20.0 * sign, -10.0)]
        head = _arrow_head((40.0 * sign, -10.0), (sign, 0.0), 20.0, 17.0)
        shapes = [("line", stem, 14.0), ("poly", head)]
        points = stem + head
    elif maneuver in ("slight-left", "slight-right"):
        right = maneuver == "slight-right"
        sign = 1.0 if right else -1.0
        stem = [(0.0, 34.0), (0.0, 10.0), (24.0 * sign, -14.0)]
        head = _arrow_head((30.0 * sign, -20.0), (sign, -1.0), 20.0, 16.0)
        shapes = [("line", stem, 14.0), ("poly", head)]
        points = stem + head
    elif maneuver == "uturn":
        stem = [(14.0, 34.0), (14.0, -4.0)]
        arc = _uturn_arc()
        tail = [(-14.0, -4.0), (-14.0, 14.0)]
        head = _arrow_head((-14.0, 32.0), (0.0, 1.0), 20.0, 16.0)
        shapes = [("line", stem, 14.0), ("line", arc, 14.0),
                  ("line", tail, 14.0), ("poly", head)]
        points = stem + arc + tail + head
    elif maneuver == "roundabout":
        # 环岛：底部入口 + 逆时针圆弧，缺口在正上方，箭头从缺口左侧绕出。
        ring = _roundabout_ring()
        stem = [(0.0, 44.0), (0.0, 22.0)]
        head = _arrow_head((-2.0, -23.0), (-1.0, -0.6), 17.0, 14.0)
        shapes = [("line", ring, 9.0), ("line", stem, 9.0),
                  ("poly", head)]
        points = [(-20.0, -18.0), (20.0, 22.0), (0.0, 44.0),
                  (-2.0, -23.0), (14.0, -28.0), (-16.0, -14.0)]
    elif maneuver == "arrive":
        pole = [(-14.0, 36.0), (-14.0, -36.0)]
        flag = [(-14.0, -36.0), (30.0, -24.0), (-14.0, -12.0)]
        ground = [(-30.0, 36.0), (-2.0, 36.0)]
        shapes = [("line", pole, 7.0), ("poly", flag), ("line", ground, 6.0)]
        points = pole + flag + ground
    else:  # 直行 / 未知
        arrow = [(-9.0, 34.0), (9.0, 34.0), (9.0, -6.0), (22.0, -6.0),
                 (0.0, -36.0), (-22.0, -6.0), (-9.0, -6.0)]
        shapes = [("poly", arrow)]
        points = arrow
    return shapes, points

