# -*- coding: utf-8 -*-
"""屏幕采集抽象与 640×400 映射。

原则：
  * 核心只依赖 numpy；真实屏幕采集用可选依赖 mss（Windows GDI / macOS Quartz /
    Linux X11，跨平台但 Wayland 需 XWayland）。
  * 提供 TestPatternSource，没有眼镜/没有桌面也能验证整条“采集 → 640×400 → RGBA”管线。
  * fit（留黑边）/ cover（裁切，CLI 也接受 crop）/ stretch（拉伸）三种映射。
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any

try:  # pragma: no cover - 取决于环境
    import numpy as np  # type: ignore

    NUMPY_AVAILABLE = True
except Exception:
    np = None  # type: ignore
    NUMPY_AVAILABLE = False

from .errors import GlxssError

LCD_W = 640
LCD_H = 400


def require_numpy() -> None:
    if not NUMPY_AVAILABLE:
        raise GlxssError("采集/映射需要 numpy：python -m pip install numpy")


class ImageSource(ABC):
    """采集源接口：每次 grab() 返回 H×W×3 uint8 RGB。"""

    name = "source"

    @abstractmethod
    def grab(self) -> Any:
        raise NotImplementedError

    def grab_rgba(self):
        """可选快路径：直接返回 640×400×4 RGBA uint8；默认 None=走 grab()+映射。

        UVC 监看源在解码 YV12 时顺手转成 RGBA，避免再缩放/拼 alpha；HUD 也可以
        直接用 Pillow 的 RGBA 结果。`CastApp` 会优先调用本方法。
        """
        return None

    def close(self) -> None:
        pass

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()
        return False


def _resize_nearest(image, width: int, height: int):
    require_numpy()
    src_h, src_w = image.shape[:2]
    if (src_w, src_h) == (width, height):
        return image.copy()
    ys = ((np.arange(height) + 0.5) * src_h / height).astype(np.intp)
    xs = ((np.arange(width) + 0.5) * src_w / width).astype(np.intp)
    ys = np.clip(ys, 0, src_h - 1)
    xs = np.clip(xs, 0, src_w - 1)
    return image[ys[:, None], xs[None, :]]


class TestPatternSource(ImageSource):
    """确定性测试图案，不访问桌面/USB；用于自检和 `cast --source test`。"""

    name = "test-pattern"

    def __init__(self, width: int = LCD_W, height: int = LCD_H,
                 pattern: str = "bars", animate: bool = True):
        require_numpy()
        if width <= 0 or height <= 0:
            raise GlxssError("测试图案宽高必须为正")
        self.width = width
        self.height = height
        self.pattern = pattern
        self.animate = animate
        self._tick = 0
        self._palette = np.array([
            [235, 235, 235], [235, 235, 16], [16, 235, 235], [16, 235, 16],
            [235, 16, 235], [235, 16, 16], [16, 16, 235], [16, 16, 16],
        ], dtype=np.uint8)

    def grab(self) -> Any:
        require_numpy()
        w, h = self.width, self.height
        if self.pattern == "gradient":
            x = np.linspace(0, 255, w, dtype=np.uint8)[None, :]
            y = np.linspace(0, 255, h, dtype=np.uint8)[:, None]
            image = np.dstack([
                np.broadcast_to(x, (h, w)),
                np.broadcast_to(y, (h, w)),
                np.broadcast_to((x.astype(np.uint16) + y) // 2, (h, w)).astype(np.uint8),
            ]).copy()
        else:
            columns = (np.arange(w) * 8 // max(1, w)).clip(0, 7)
            image = self._palette[columns][None, :, :].repeat(h, axis=0).copy()

        # 顶部灰阶 + 底部白框，方便判断旋转/偏移/通道顺序。
        bar_h = max(2, h // 20)
        image[0:bar_h, :, :] = 32
        step = max(1, w // 16)
        for i in range(0, w, step):
            image[0:bar_h, i:i + step // 2, :] = 220
        border = max(1, min(w, h) // 160)
        image[0:border, :, :] = 255
        image[-border:, :, :] = 255
        image[:, 0:border, :] = 255
        image[:, -border:, :] = 255

        if self.animate:
            self._tick += 1
            size = max(8, h // 20)
            x = int((self._tick * 7) % max(1, w - size))
            y = int((self._tick * 3) % max(1, h - size))
            image[y:y + size, x:x + size, :] = (255, 0, 255)
        return np.ascontiguousarray(image)


class ScreenSource(ImageSource):
    """用 mss 采集真实屏幕；mss 是可选依赖。"""

    name = "screen"

    def __init__(self, monitor: int = 1):
        require_numpy()
        try:
            import mss  # type: ignore
        except Exception as exc:
            raise GlxssError(
                "真实屏幕采集需要 mss：python -m pip install mss\n"
                "  Linux Wayland 需要 XWayland；纯 Wayland 会失败。\n"
                "  没有桌面时可用 `--source test`。") from exc
        self._mss_module = mss
        self._sct = mss.mss()
        monitors = self._sct.monitors
        if monitor < 1 or monitor >= len(monitors):
            self.close()
            raise GlxssError(
                "显示器编号 %d 不存在；mss 检测到 %d 个显示器（0=全部，1..N）" %
                (monitor, max(0, len(monitors) - 1)))
        self.monitor = monitor
        self._region = monitors[monitor]
        self.width = int(self._region["width"])
        self.height = int(self._region["height"])

    def grab(self) -> Any:
        require_numpy()
        try:
            shot = self._sct.grab(self._region)
        except Exception as exc:
            raise GlxssError(
                "屏幕采集失败：%s\n"
                "  Linux 请确认有 X11/XWayland；无桌面时用 `--source test`。" % exc) from exc
        # mss 返回 BGRA；去掉 alpha 后反转 BGR → RGB。
        bgra = np.asarray(shot)
        return np.ascontiguousarray(bgra[:, :, [2, 1, 0]])

    def close(self) -> None:
        try:
            self._sct.close()
        except Exception:
            pass


def load_rgb_image(path: str):
    """用 Pillow 读取任意图片并转 RGB numpy 数组。"""
    require_numpy()
    try:
        from PIL import Image  # type: ignore
    except Exception as exc:
        raise GlxssError("读取图片需要 Pillow：python -m pip install Pillow") from exc
    try:
        with Image.open(path) as image:
            return np.asarray(image.convert("RGB")).copy()
    except OSError as exc:
        raise GlxssError("无法读取图片 %s: %s" % (path, exc)) from exc


def map_to_rgba(image, width: int = LCD_W, height: int = LCD_H,
                mode: str = "fit"):
    """把任意 RGB 图像映射成 width×height RGBA uint8（alpha=255）。"""
    require_numpy()
    if image is None or getattr(image, "ndim", 0) != 3 or image.shape[2] < 3:
        raise GlxssError("源图像必须是 H×W×3 的 RGB 数组")
    if image.shape[2] != 3:
        image = image[:, :, :3]
    image = np.ascontiguousarray(image.astype(np.uint8))
    src_h, src_w = image.shape[:2]
    if src_w <= 0 or src_h <= 0:
        raise GlxssError("源图像尺寸为 0")
    mode = mode.lower()
    if mode == "crop":
        mode = "cover"
    if mode not in ("fit", "cover", "stretch"):
        raise GlxssError("映射模式必须是 fit / cover(crop) / stretch，收到 %r" % mode)

    canvas = np.zeros((height, width, 3), dtype=np.uint8)
    if mode == "stretch":
        rgb = _resize_nearest(image, width, height)
    elif mode == "cover":
        scale = max(width / src_w, height / src_h)
        crop_w = min(src_w, max(1, int(round(width / scale))))
        crop_h = min(src_h, max(1, int(round(height / scale))))
        left = max(0, (src_w - crop_w) // 2)
        top = max(0, (src_h - crop_h) // 2)
        rgb = _resize_nearest(image[top:top + crop_h, left:left + crop_w], width, height)
    else:  # fit
        scale = min(width / src_w, height / src_h)
        new_w = max(1, min(width, int(round(src_w * scale))))
        new_h = max(1, min(height, int(round(src_h * scale))))
        resized = _resize_nearest(image, new_w, new_h)
        left = (width - new_w) // 2
        top = (height - new_h) // 2
        canvas[top:top + new_h, left:left + new_w] = resized
        rgb = canvas

    alpha = np.full((height, width, 1), 255, dtype=np.uint8)
    return np.ascontiguousarray(np.concatenate([rgb, alpha], axis=2))


def rgba_bytes(rgba) -> bytes:
    """RGBA H×W×4 → 设备需要的连续字节（400×640×4 = 1,024,000）。"""
    require_numpy()
    if rgba.shape != (LCD_H, LCD_W, 4):
        raise GlxssError("RGBA 数组形状必须是 (400,640,4)，实际 %r" % (rgba.shape,))
    return np.ascontiguousarray(rgba, dtype=np.uint8).tobytes()


def rotate180_rgba(rgba):
    """RGBA 图像旋转 180°（同像素顺序反转，比 [::-1,::-1] 快很多）。

    眼镜装反时用 `--rotate 180`；这里把每个 4 B 像素当作一个整体反转，
    不改变通道顺序（R/G/B/A 仍属于同一像素）。
    """
    require_numpy()
    array = np.ascontiguousarray(rgba, dtype=np.uint8)
    if array.ndim != 3 or array.shape[2] != 4:
        raise GlxssError("rotate180_rgba 需要 H×W×4 数组，实际 %r" % (array.shape,))
    height, width = array.shape[:2]
    pixels = array.view(np.uint32).reshape(-1)
    rotated = np.ascontiguousarray(pixels[::-1])
    return rotated.view(np.uint8).reshape(height, width, 4)


def save_rgba_png(path: str, rgba) -> None:
    """把 RGBA 数组写成 PNG（用于离线核对打包结果）。"""
    require_numpy()
    try:
        from PIL import Image  # type: ignore
    except Exception as exc:
        raise GlxssError("保存 PNG 需要 Pillow：python -m pip install Pillow") from exc
    if rgba.ndim != 3 or rgba.shape[2] != 4:
        raise GlxssError("save_rgba_png 需要 H×W×4 数组")
    Image.fromarray(rgba.astype(np.uint8), "RGBA").save(path)


def create_source(name: str, monitor: int = 1,
                  test_pattern: str = "bars") -> ImageSource:
    key = name.lower()
    if key in ("test", "pattern", "bars"):
        return TestPatternSource(pattern="bars" if key == "bars" else test_pattern)
    if key in ("screen", "display", "desktop"):
        return ScreenSource(monitor=monitor)
    if key == "hud":
        # 延迟导入，避免 capture ↔ hud 的模块级循环依赖。
        from .hud import HudSource
        return HudSource()
    raise GlxssError("未知采集源 %r（可选 hud / test / screen）" % name)
