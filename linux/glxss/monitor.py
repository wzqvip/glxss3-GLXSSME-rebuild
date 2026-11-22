# -*- coding: utf-8 -*-
"""相机监看源（monitor）：眼镜 UVC 读帧 → 640×400 → 叠加层 → 发回眼镜。

为什么单独一个源：
  * 眼镜同时是“摄像头”和“显示器”，LCD bulk OUT 与 UVC bulk IN 必须在同一
    `UsbDeviceConnection` 上并发；控制请求 + LCD 帧走 `usb.UsbGate` 串行，
    UVC 读流（bulk IN）不占闸门。
  * UVC 读取/解码放在后台线程，主循环永远取“最新完成的一帧”发送：
    解码慢时自动丢旧帧（10 fps 发送仍是 10 fps，只是画面延迟略高）。
  * YV12→RGBA 走 `uvc.decode_yv12_rgba` 的 uint32 打包快路径；默认全分辨率
    cover 裁切（640×480 → 640×400，不缩放），`half=True` 时 320×240 再 2×
    放大，给 Pi Zero 2 W 留余量。

叠加层：
  * 三分线（九宫格）；
  * 水平仪：HID 加速度计的 pitch/roll，地平线 + 中心准星；
  * `rec=True` 时右上角红点 + REC mm:ss（每整秒重绘，不占每帧开销）。
"""

from __future__ import annotations

import math
import threading
import time
from contextlib import nullcontext
from dataclasses import dataclass, field
from typing import Callable, Optional

try:
    import numpy as np  # type: ignore
except Exception:  # pragma: no cover - 取决于环境
    np = None  # type: ignore

try:
    from PIL import Image, ImageDraw, ImageFont  # type: ignore
except Exception:  # pragma: no cover - 取决于环境
    Image = ImageDraw = ImageFont = None  # type: ignore

from . import capture, uvc
from .capture import LCD_H, LCD_W, ImageSource
from .errors import GlxssError

# 叠加层颜色（RGB）；AR 面板上青色最清楚。
GRID_COLOR = (235, 245, 255)
GRID_ALPHA = 70
LEVEL_COLOR = (64, 210, 255)
LEVEL_ALPHA = 190
RETICLE_COLOR = (255, 255, 255)
RETICLE_ALPHA = 150
REC_COLOR = (255, 58, 58)
MUTED = (150, 168, 190)

StateProvider = Optional[Callable[[], object]]


@dataclass
class MonitorStats:
    """后台 UVC 线程的统计；status 命令原样返回给 HAT/CLI。"""

    frames_ready: int = 0
    frames_stale: int = 0
    read_errors: int = 0
    decode_errors: int = 0
    timeouts: int = 0
    read_ms: float = 0.0
    decode_ms: float = 0.0
    read_fps: float = 0.0
    last_error: str = ""
    started_at: float = field(default_factory=time.time)

    def as_dict(self) -> dict:
        return {
            "frames_ready": self.frames_ready,
            "frames_stale": self.frames_stale,
            "read_errors": self.read_errors,
            "decode_errors": self.decode_errors,
            "timeouts": self.timeouts,
            "read_ms": round(self.read_ms, 2),
            "decode_ms": round(self.decode_ms, 2),
            "read_fps": round(self.read_fps, 1),
            "last_error": self.last_error,
        }


class MonitorSource(ImageSource):
    """UVC 相机源；grab_rgba() 直接给出带叠加层的 640×400 RGBA。"""

    name = "uvc"

    def __init__(self, stream, session=None, *, gate=None, timeout: int = 500,
                 fit: str = "cover", half: bool = False, rec: bool = False,
                 state_provider: StateProvider = None, verbose: bool = False):
        if np is None:
            raise GlxssError("monitor 需要 numpy：python -m pip install numpy")
        self.stream = stream
        self.session = session
        self.gate = gate
        self.timeout = max(50, int(timeout))
        self.fit = fit
        self.half = bool(half)
        self.verbose = verbose
        self._state_provider = state_provider
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._pending = None      # 最新解码完成、尚未叠加的 RGBA
        self._composed = None     # 最近一次叠加后的 RGBA（发送慢时复用）
        self._placeholder_rgba = None
        self._stats = MonitorStats()
        self._rec = bool(rec)
        self._rec_started = time.monotonic()
        self._rec_key = ""
        self._rec_overlay = None
        self._font_cache = {}
        self._last_count_at = time.monotonic()
        self._last_count_frames = 0
        self._thread = threading.Thread(target=self._reader_loop,
                                        name="glxss-uvc-monitor", daemon=True)
        self._thread.start()

    # -- 打开/关闭 ---------------------------------------------------------
    @classmethod
    def open(cls, dev, session, *, gate=None, timeout: int = 500,
             fit: str = "cover", half: bool = False, camera_fps: Optional[float] = 30.0,
             rec: bool = False, state_provider: StateProvider = None,
             verbose: bool = False) -> "MonitorSource":
        """claim UVC VC/VS + probe/commit + 启动读帧线程。"""
        ctx = gate if gate is not None else nullcontext()
        stream = None
        try:
            with ctx:
                stream = uvc.UvcStream.open(dev, session,
                                            timeout=max(1000, timeout * 4),
                                            verbose=verbose)
                stream.set_dimensions(uvc.DEFAULT_WIDTH, uvc.DEFAULT_HEIGHT)
                frame_size = uvc.DEFAULT_WIDTH * uvc.DEFAULT_HEIGHT * 3 // 2
                try:
                    stream.negotiate(fps=camera_fps, max_frame=frame_size)
                except GlxssError as exc:
                    if camera_fps is None:
                        raise
                    if verbose:
                        print("  UVC %.1f fps 协商失败（%s），改用设备默认帧率" % (
                            camera_fps, str(exc).splitlines()[0]))
                    stream.negotiate(max_frame=frame_size)
        except Exception:
            if stream is not None:
                try:
                    with ctx:
                        stream.close()
                except Exception:
                    pass
            raise
        return cls(stream, session=session, gate=gate, timeout=timeout,
                   fit=fit, half=half, rec=rec,
                   state_provider=state_provider, verbose=verbose)

    def close(self) -> None:
        self._stop.set()
        thread = self._thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=2.0)
        self._thread = None
        stream = self.stream
        if stream is not None:
            try:
                ctx = self.gate if self.gate is not None else nullcontext()
                with ctx:
                    stream.close()
            except Exception:
                pass
            self.stream = None

    # -- 后台读帧/解码 -----------------------------------------------------
    def _reader_loop(self) -> None:
        errors_in_a_row = 0
        while not self._stop.is_set():
            stream = self.stream
            if stream is None:
                break
            started = time.monotonic()
            try:
                frame = stream.read_frame(timeout=self.timeout)
            except GlxssError as exc:
                message = str(exc).splitlines()[0] if str(exc) else "UVC 读帧失败"
                with self._lock:
                    self._stats.read_errors += 1
                    if "超时" in message or "timeout" in message.lower():
                        self._stats.timeouts += 1
                    self._stats.last_error = message
                errors_in_a_row += 1
                if errors_in_a_row >= 5 and not self._stop.is_set():
                    if self.verbose:
                        print("[警告] monitor UVC 连续 %d 次读帧失败: %s" % (
                            errors_in_a_row, message))
                continue
            read_ms = (time.monotonic() - started) * 1000.0
            errors_in_a_row = 0
            decode_started = time.monotonic()
            try:
                rgba = uvc.decode_yv12_rgba(
                    frame.data, frame.width, frame.height,
                    LCD_W, LCD_H, fit=self.fit, half=self.half)
            except Exception as exc:  # 数据坏/尺寸不符都只丢这一帧
                with self._lock:
                    self._stats.decode_errors += 1
                    self._stats.last_error = "解码失败: %s" % str(exc).splitlines()[0]
                continue
            decode_ms = (time.monotonic() - decode_started) * 1000.0
            now = time.monotonic()
            with self._lock:
                self._stats.frames_ready += 1
                self._stats.read_ms = read_ms
                self._stats.decode_ms = decode_ms
                self._stats.last_error = ""
                self._pending = rgba
                if now - self._last_count_at >= 1.0:
                    self._stats.read_fps = (
                        (self._stats.frames_ready - self._last_count_frames) /
                        max(1e-6, now - self._last_count_at))
                    self._last_count_at = now
                    self._last_count_frames = self._stats.frames_ready

    # -- 叠加层 ------------------------------------------------------------
    @staticmethod
    def _blend_pixels(rgba, ys, xs, color, alpha: int) -> None:
        ys = np.atleast_1d(np.asarray(ys, dtype=np.intp))
        xs = np.atleast_1d(np.asarray(xs, dtype=np.intp))
        if ys.size == 0:
            return
        valid = (ys >= 0) & (ys < LCD_H) & (xs >= 0) & (xs < LCD_W)
        if not valid.all():
            ys, xs = ys[valid], xs[valid]
        if ys.size == 0:
            return
        keep = 255 - int(alpha)
        rgb = rgba[ys, xs, :3].astype(np.uint16)
        blend = np.asarray(color, dtype=np.uint16)
        rgba[ys, xs, :3] = ((rgb * keep + blend * int(alpha)) // 255).astype(np.uint8)
        rgba[ys, xs, 3] = 255

    def _draw_grid(self, rgba) -> None:
        x1, x2 = LCD_W // 3, (LCD_W * 2) // 3
        y1, y2 = LCD_H // 3, (LCD_H * 2) // 3
        for x in (x1, x2):
            self._blend_pixels(rgba, np.arange(LCD_H), np.full(LCD_H, x),
                               GRID_COLOR, GRID_ALPHA)
        for y in (y1, y2):
            self._blend_pixels(rgba, np.full(LCD_W, y), np.arange(LCD_W),
                               GRID_COLOR, GRID_ALPHA)

    def _draw_reticle(self, rgba, y: int = LCD_H // 2) -> None:
        cx = LCD_W // 2
        xs = np.concatenate([
            np.arange(cx - 24, cx - 8), np.arange(cx + 8, cx + 24)])
        self._blend_pixels(rgba, np.full(xs.size, y), xs,
                           RETICLE_COLOR, RETICLE_ALPHA)
        ys = np.concatenate([
            np.arange(y - 16, y - 5), np.arange(y + 5, y + 16)])
        self._blend_pixels(rgba, ys, np.full(ys.size, cx),
                           RETICLE_COLOR, RETICLE_ALPHA)

    def _draw_level(self, rgba) -> None:
        state = None
        if self._state_provider is not None:
            try:
                state = self._state_provider()
            except Exception:
                state = None
        valid = bool(state is not None and getattr(state, "acc_valid", False))
        if valid:
            pitch, roll = state.pitch_roll
            cy = int(LCD_H / 2 + max(-150.0, min(150.0, float(pitch) * 2.0)))
            slope = math.tan(math.radians(-float(roll)))
            xs = np.arange(LCD_W, dtype=np.intp)
            ys = (cy + slope * (xs - LCD_W // 2)).astype(np.intp)
            keep = (np.abs(xs - LCD_W // 2) > 28) & (ys >= 1) & (ys < LCD_H - 1)
            self._blend_pixels(rgba, ys[keep], xs[keep], LEVEL_COLOR, LEVEL_ALPHA)
            self._blend_pixels(rgba, ys[keep] + 1, xs[keep], LEVEL_COLOR,
                               max(40, LEVEL_ALPHA // 3))
            center_y = cy
        else:
            center_y = LCD_H // 2
        self._draw_reticle(rgba, max(6, min(LCD_H - 6, center_y)))

    def _rec_font(self, size: int = 14):
        key = int(size)
        if key in self._font_cache:
            return self._font_cache[key]
        font = None
        if ImageFont is not None:
            for path in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                         "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf",
                         "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf"):
                try:
                    font = ImageFont.truetype(path, key)
                    break
                except Exception:
                    continue
            if font is None:
                try:
                    font = ImageFont.load_default(size=key)
                except TypeError:
                    font = ImageFont.load_default()
        self._font_cache[key] = font
        return font

    def _rec_image(self, elapsed: int):
        if Image is None:
            return None
        text = "REC %02d:%02d" % (elapsed // 60, elapsed % 60)
        if text == self._rec_key and self._rec_overlay is not None:
            return self._rec_overlay
        image = Image.new("RGBA", (150, 30), (0, 0, 0, 0))
        draw = ImageDraw.Draw(image, "RGBA")
        draw.rounded_rectangle((44, 3, 148, 27), radius=5, fill=(8, 10, 14, 150))
        draw.ellipse((8, 9, 24, 25), fill=(255, 58, 58, 255))
        draw.text((52, 7), text, font=self._rec_font(14),
                  fill=(255, 236, 236, 255))
        self._rec_overlay = np.asarray(image, dtype=np.uint8).copy()
        self._rec_key = text
        return self._rec_overlay

    def _draw_rec(self, rgba) -> None:
        with self._lock:
            rec = self._rec
            started = self._rec_started
        if not rec:
            return
        image = self._rec_image(int(max(0.0, time.monotonic() - started)))
        if image is None:
            # 没有 Pillow：退化成一个红点。
            self._blend_pixels(rgba, np.arange(10, 26), np.full(16, LCD_W - 26),
                               (255, 58, 58), 255)
            return
        height, width = image.shape[:2]
        left = max(0, LCD_W - width - 4)
        top = 4
        # 只对 150×30 的小块做 alpha 合成，不拖慢 10 fps。
        region = rgba[top:top + height, left:left + width].astype(np.uint16)
        alpha = image[:, :, 3].astype(np.uint16)[..., None]
        blended = (region[:, :, :3] * (255 - alpha) +
                   image[:, :, :3].astype(np.uint16) * alpha) // 255
        rgba[top:top + height, left:left + width, :3] = blended.astype(np.uint8)
        rgba[top:top + height, left:left + width, 3] = np.maximum(
            rgba[top:top + height, left:left + width, 3], image[:, :, 3])

    def _compose(self, rgba):
        self._draw_grid(rgba)
        self._draw_level(rgba)
        self._draw_rec(rgba)
        return rgba

    def _placeholder(self):
        if self._placeholder_rgba is not None:
            return self._placeholder_rgba
        rgba = np.zeros((LCD_H, LCD_W, 4), dtype=np.uint8)
        rgba[..., 3] = 255
        rgba[..., 0] = 8
        rgba[..., 1] = 12
        rgba[..., 2] = 20
        if Image is not None:
            image = Image.new("RGBA", (LCD_W, LCD_H), (8, 12, 20, 255))
            draw = ImageDraw.Draw(image, "RGBA")
            font = self._rec_font(22)
            text = "UVC  NO SIGNAL"
            try:
                width = int(draw.textlength(text, font=font))
            except Exception:
                width = 200
            draw.text(((LCD_W - width) // 2, LCD_H // 2 - 14), text,
                      font=font, fill=(150, 168, 190, 255))
            rgba = np.asarray(image, dtype=np.uint8).copy()
        self._placeholder_rgba = rgba
        return rgba

    # -- ImageSource 接口 --------------------------------------------------
    def grab_rgba(self):
        with self._lock:
            raw = self._pending
            self._pending = None
            composed = self._composed
            if raw is None:
                self._stats.frames_stale += 1
        if raw is not None:
            composed = self._compose(raw)
            with self._lock:
                self._composed = composed
            return composed
        if composed is not None:
            return composed
        return self._placeholder()

    def grab(self):
        rgba = self.grab_rgba()
        return np.ascontiguousarray(rgba[:, :, :3])

    # -- 状态 --------------------------------------------------------------
    def set_rec(self, enabled: bool) -> None:
        with self._lock:
            enabled = bool(enabled)
            if enabled and not self._rec:
                self._rec_started = time.monotonic()
                self._rec_key = ""
            self._rec = enabled

    @property
    def rec(self) -> bool:
        with self._lock:
            return self._rec

    def stats_dict(self) -> dict:
        with self._lock:
            data = self._stats.as_dict()
        data.update({"fit": self.fit, "half": self.half, "rec": self.rec})
        return data


class ModeSource(ImageSource):
    """HUD / monitor 双模式源；模式切换在主线程的 grab 边界执行。"""

    name = "mode"

    def __init__(self, hud_source: ImageSource, monitor_opener: Callable[[], MonitorSource],
                 initial: str = "hud"):
        if np is None:
            raise GlxssError("mode 源需要 numpy")
        self.hud = hud_source
        self._opener = monitor_opener
        self._monitor: Optional[MonitorSource] = None
        self._active = "hud"
        self._desired = "hud"
        self._last_error = ""
        self._lock = threading.Lock()
        self._rec = False
        self.request_view(initial)

    @property
    def mode(self) -> str:
        with self._lock:
            return self._active

    @property
    def desired(self) -> str:
        with self._lock:
            return self._desired

    @property
    def monitor(self) -> Optional[MonitorSource]:
        return self._monitor

    @property
    def error(self) -> str:
        with self._lock:
            return self._last_error

    def request_view(self, view: str) -> None:
        value = _normalize_view(view)
        with self._lock:
            self._desired = value

    def request_toggle(self) -> str:
        with self._lock:
            target = "monitor" if self._desired == "hud" else "hud"
            self._desired = target
        return target

    def set_rec(self, enabled: bool) -> None:
        with self._lock:
            self._rec = bool(enabled)
            monitor = self._monitor
        if monitor is not None:
            monitor.set_rec(enabled)

    @property
    def rec(self) -> bool:
        with self._lock:
            return self._rec

    def _sync(self) -> None:
        with self._lock:
            desired = self._desired
            active = self._active
            monitor = self._monitor
        if desired == active:
            if monitor is not None:
                monitor.set_rec(self.rec)
            return
        if desired == "monitor":
            if monitor is None:
                try:
                    monitor = self._opener()
                except GlxssError as exc:
                    message = str(exc).splitlines()[0] if str(exc) else "UVC 打开失败"
                    with self._lock:
                        self._last_error = message
                        self._desired = "hud"
                    print("[警告] 无法进入 monitor 模式: %s" % message)
                    return
                with self._lock:
                    self._monitor = monitor
                    self._last_error = ""
            monitor.set_rec(self.rec)
            with self._lock:
                self._active = "monitor"
        else:
            if monitor is not None:
                monitor.close()
                with self._lock:
                    self._monitor = None
            with self._lock:
                self._active = "hud"

    def grab_rgba(self):
        self._sync()
        with self._lock:
            active = self._active
            monitor = self._monitor
        if active == "monitor" and monitor is not None:
            return monitor.grab_rgba()
        rgba = self.hud.grab_rgba()
        if rgba is None:
            rgba = capture.map_to_rgba(self.hud.grab(), mode="stretch")
        return rgba

    def grab(self):
        self._sync()
        with self._lock:
            active = self._active
            monitor = self._monitor
        if active == "monitor" and monitor is not None:
            return monitor.grab()
        return self.hud.grab()

    def close(self) -> None:
        with self._lock:
            monitor = self._monitor
            self._monitor = None
        if monitor is not None:
            monitor.close()
        self.hud.close()

    def status_dict(self) -> dict:
        with self._lock:
            data = {
                "view": self._active,
                "pending_view": self._desired,
                "monitor_error": self._last_error,
                "rec": self._rec,
            }
            monitor = self._monitor
        if monitor is not None:
            data["monitor"] = monitor.stats_dict()
        return data


def _normalize_view(view: str) -> str:
    value = str(view or "").strip().lower()
    if value in ("monitor", "uvc", "camera", "cam", "1", "on", "true"):
        return "monitor"
    if value in ("hud", "status", "home", "0", "off", "false"):
        return "hud"
    raise GlxssError("视图必须是 hud 或 monitor，收到 %r" % view)
