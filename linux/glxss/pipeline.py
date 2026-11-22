# -*- coding: utf-8 -*-
"""一次采集、一次解码、多消费者分发（对齐原厂 `:llvision_G25` 的架构）。

链路：

    UVC 读线程（`stream.Streamer.run` 主循环，16 KiB 分块组帧）
      └─ 读到一整帧 YV12（`bytes`，不可变，460800 B）
           └─ LatestFrame.publish()            # 只覆盖一个槽，旧的直接丢
                ├─ EncoderSink 线程 → ffmpeg stdin（YV12→yuv420p **只换写入顺序**）
                └─ LcdSink  线程 → 320×200 降采样 → YUV→RGB → 640×400 → 眼镜

为什么不是「推流一个进程 + 监看一个进程」：UVC 只有一条流，谁多读一次谁就多
一次 460 KB 拷贝、一次 YUV→RGBA 解码；Pi Zero 2 W 只有 4 个 1 GHz A53 核，
两个进程抢 CPU 的结果就是采集掉到 4.6 fps（见 `pi/STREAM.md` §8.5 优化前记录）。
原厂的做法是单线程读、共享一帧、各消费者各取最新，这里照做。

三条不变量：

1. **发布者永不阻塞**：`publish()` 只加锁写一个引用，不做任何转换/IO；
2. **消费者各自丢帧**：LCD 10 fps 时自然丢掉 3 帧里的 2 帧，不影响编码；
3. **不排队**：槽里只有最新一帧，慢消费者拿到的永远是最新画面，延迟不累积。
"""

from __future__ import annotations

import array
import math
import struct
import threading
import time
from contextlib import nullcontext
from dataclasses import dataclass
from typing import Callable, Optional, Tuple

from . import hid, lcd, usb
from .errors import GlxssError

try:  # 核心依赖；缺失时给出明确错误而不是 import 失败
    import numpy as np  # type: ignore
except Exception:  # pragma: no cover - 取决于环境
    np = None  # type: ignore

try:
    from PIL import Image, ImageDraw  # type: ignore
except Exception:  # pragma: no cover - 取决于环境
    Image = ImageDraw = None  # type: ignore

LCD_W, LCD_H = lcd.FRAME_W, lcd.FRAME_H          # 640×400
LCD_HALF_W, LCD_HALF_H = LCD_W // 2, LCD_H // 2  # 320×200

# 叠加层颜色（RGB + alpha）；AR 面板上青色最清楚，和 monitor.py 保持一致。
GRID_COLOR = (235, 245, 255, 70)
LEVEL_COLOR = (64, 210, 255, 190)
RETICLE_COLOR = (255, 255, 255, 150)
BACKDROP = (8, 12, 20, 255)


# ---------------------------------------------------------------------------
# 最新帧槽
# ---------------------------------------------------------------------------
class LatestFrame:
    """只有一个槽的「最新帧」发布点：谁慢谁丢自己的帧，永不排队。

    多消费者用法（每个消费者自己记 ``seq``）::

        seq = 0
        while running:
            frame, seq = hub.take(seq)
            if frame is None:      # 超时/已关闭，回到循环顶再判断
                continue
            consume(frame)

    ``take()`` 只在**有新帧**时返回；被覆盖掉的那些帧不会以任何形式补发。
    帧本身是 ``bytes``（不可变），多个线程共享同一个对象不需要拷贝。
    """

    def __init__(self) -> None:
        self._cv = threading.Condition()
        self._frame = None
        self._seq = 0
        self._closed = False
        self.published = 0

    # -- 发布（读线程） ----------------------------------------------------
    def publish(self, frame) -> int:
        with self._cv:
            self._frame = frame
            self._seq += 1
            self.published += 1
            self._cv.notify_all()
            return self._seq

    # -- 消费（编码线程 / LCD 线程） ---------------------------------------
    def take(self, last_seq: int, timeout: float = 0.25):
        """等一帧新的；返回 ``(frame, seq)``，超时或已关闭返回 ``(None, last_seq)``。"""
        with self._cv:
            if self._seq == last_seq and not self._closed:
                self._cv.wait(timeout)
            if self._closed or self._seq == last_seq:
                return None, last_seq
            return self._frame, self._seq

    def latest(self):
        """不等：拿当前最新帧（可能为 None）。"""
        with self._cv:
            return self._frame

    def latest_with_seq(self):
        """不等：拿 ``(frame, seq)``，两个值在同一把锁里取，保证配对。"""
        with self._cv:
            return self._frame, self._seq

    @property
    def seq(self) -> int:
        with self._cv:
            return self._seq

    @property
    def closed(self) -> bool:
        with self._cv:
            return self._closed

    def close(self) -> None:
        with self._cv:
            self._closed = True
            self._cv.notify_all()


# ---------------------------------------------------------------------------
# YV12 → yuv420p：只换平面写入顺序，零像素拷贝
# ---------------------------------------------------------------------------
def yv12_plane_views(data, width: int, height: int):
    """YV12（Y|V|U）→ ``(Y, U, V)`` 三个 ``memoryview``，**不复制像素**。

    这是喂 ffmpeg 的「零转换」关键：yuv420p = Y|U|V，YV12 = Y|V|U，两者只是
    后两段顺序不同。rawvideo 解复用器按字节流读一整帧（``avio_read`` 会循环读满
    ``frame_size``），所以分三次 ``write()`` 与拼 460 KB 大 buffer 再写一次**完全
    等价**，但省掉了 Python 侧 460 KB 的 memcpy。

    顺带查证过 ffmpeg 的 ``-vf shuffleplanes=0:2:1``：**它并不零转换**。5.1 的
    shuffleplanes 只接受带 alpha 的 yuva420p，会在前后各自动插入一个
    ``auto_scale``（yuv420p ↔ yuva420p），等于多做一次 swscale + 一个 alpha 平面，
    比这里的三次写慢得多（实测见 `pi/STREAM.md` §8.5）。
    """
    y_size = width * height
    c_size = y_size // 4
    need = y_size + 2 * c_size
    if len(data) < need:
        raise GlxssError("YUV 数据不足：需要 %d B，实际 %d B" % (need, len(data)))
    view = memoryview(data)
    return (view[:y_size],                     # Y
            view[y_size + c_size:need],        # U —— YV12 里在第三段
            view[y_size:y_size + c_size])      # V —— YV12 里在第二段


def write_yv12_as_i420(sink, data, width: int, height: int) -> int:
    """把一帧 YV12 按 yuv420p 顺序写进 ffmpeg stdin（3 次写，零拷贝）。"""
    y_view, u_view, v_view = yv12_plane_views(data, width, height)
    sink.write(y_view)
    sink.write(u_view)
    sink.write(v_view)
    return len(y_view) + len(u_view) + len(v_view)


# ---------------------------------------------------------------------------
# YV12 → RGBA（LCD 小图）
# ---------------------------------------------------------------------------
def yv12_planes(data, width: int, height: int):
    """YV12 → ``(Y, U, V)`` numpy 视图（只读，零拷贝；U/V 已按 yuv420p 顺序）。"""
    if np is None:
        raise GlxssError("LCD 渲染需要 numpy：sudo apt install -y python3-numpy")
    y_size = width * height
    c_size = y_size // 4
    need = y_size + 2 * c_size
    if len(data) < need:
        raise GlxssError("YUV 数据不足：需要 %d B，实际 %d B" % (need, len(data)))
    raw = np.frombuffer(data, dtype=np.uint8, count=need)
    y = raw[:y_size].reshape(height, width)
    v = raw[y_size:y_size + c_size].reshape(height // 2, width // 2)
    u = raw[y_size + c_size:need].reshape(height // 2, width // 2)
    return y, u, v


def yuv_to_rgba(y, u, v):
    """Y/U/V（同尺寸）→ HxWx4 RGBA uint8，整数定点 BT.601 有限范围。

    **参考实现**：给单测当基准用（`LcdRenderer` 实际走 PIL 的 C 转换，见
    `yuv_to_lcd_rgb`，在 Pi Zero 2 W 上快约 3 倍）。公式与
    `docs/ch-uvc.md §4` 的 Kotlin 版本逐项一致。
    """
    height, width = y.shape
    y16 = y.astype(np.int16)
    y16 -= 16
    np.maximum(y16, 0, out=y16)
    # 298*y ≈ y + (42*y >> 8)；42*255+128 = 10838，int16 不会溢出。
    yterm = y16 + ((y16 * 42 + 128) >> 8)
    u32 = u.astype(np.int32) - 128
    v32 = v.astype(np.int32) - 128
    r = yterm + ((409 * v32) >> 8).astype(np.int16)
    g = yterm + ((-100 * u32 - 208 * v32) >> 8).astype(np.int16)
    b = yterm + ((516 * u32) >> 8).astype(np.int16)
    np.clip(r, 0, 255, out=r)
    np.clip(g, 0, 255, out=g)
    np.clip(b, 0, 255, out=b)
    rgba = np.empty((height, width, 4), dtype=np.uint8)
    rgba[..., 0] = r
    rgba[..., 1] = g
    rgba[..., 2] = b
    rgba[..., 3] = 255
    return rgba


def cover_box(width: int, height: int) -> Tuple[int, int]:
    """cover：填满 640×400 的等比裁切尺寸（保证是偶数，4:2:0 不错位）。"""
    scale = max(LCD_W / width, LCD_H / height)
    crop_w = min(width, max(2, int(round(LCD_W / scale))))
    crop_h = min(height, max(2, int(round(LCD_H / scale))))
    crop_w -= crop_w % 2
    crop_h -= crop_h % 2
    return max(2, crop_w), max(2, crop_h)


def pitch_roll_from_acc(x: int, y: int, z: int) -> Tuple[float, float]:
    """按 docs/ch-hid.md §5.3 算俯仰/横滚（度，抬头为正）；与 hud.py 同式。"""
    if x == 0 and y == 0 and z == 0:
        return 0.0, 0.0
    pitch = math.degrees(math.atan2(x, math.hypot(y, z)))
    roll = math.degrees(math.atan2(z, y))
    return pitch, roll


# ---------------------------------------------------------------------------
# LCD 渲染（纯函数，可离线单测）
# ---------------------------------------------------------------------------
def _clamp_u8(value: float) -> int:
    if value <= 0:
        return 0
    if value >= 255:
        return 255
    return int(round(value))


# BT.601 有限范围（16..235 / 16..240）→ PIL 的 ``YCbCr`` 模式（JPEG 全范围）。
#
# PIL 的 YCbCr→RGB 用的是**全范围** BT.601：R = Y + 1.402(Cr-128)；
# 相机给的是**有限范围**：R = 1.164(Y-16) + 1.596(V-128)。所以先把 Y 拉满
# 到 0..255、把色度乘 1.1386（= 1.596/1.402 ≈ 2.018/1.772），再交给 PIL 的 C
# 代码转换，结果和 numpy 定点公式的差 < 1 LSB（实测 mean 0.14 / p99 1）。
_Y_LUT = bytes(_clamp_u8((i - 16) * 255.0 / 219.0) for i in range(256))
_C_LUT = bytes(_clamp_u8(128 + (i - 128) * 1.1386) for i in range(256))


def yuv_to_lcd_rgb(y_small, u_small, v_small):
    """(Y,Cb,Cr) 三张半分辨率 L 图 → RGB 图（PIL C 代码做矩阵转换）。

    比 numpy 逐通道定点公式快约 3 倍（Pi Zero 2 W：30 ms → 10 ms 级）。
    """
    y_img = y_small.point(_Y_LUT)
    u_img = u_small.point(_C_LUT)
    v_img = v_small.point(_C_LUT)
    return Image.merge("YCbCr", (y_img, u_img, v_img)).convert("RGB")


def _plane_image(plane, width: int, height: int, left: int = 0, top: int = 0):
    """numpy 平面切片 → L 模式 PIL 图；连续切片走 ``frombuffer``（零拷贝）。"""
    view = plane[top:top + height, left:left + width]
    if view.flags.c_contiguous:
        return Image.frombuffer("L", (width, height), view, "raw", "L", 0, 1)
    return Image.frombytes("L", (width, height),
                           np.ascontiguousarray(view).tobytes())


class LcdRenderer:
    """一帧 YV12 → 640×400 RGBA 包（三分线 + 水平仪），复用同一个输出 packet。

    全部转换都在**半分辨率**（320×240，cover 裁切成 320×200）上做——像素数
    只有 1/4：

      1. Y/U/V 平面按 2×2 抽样成半分辨率（Y 走 PIL 的 NEAREST 缩小，U/V 本来
         就是半分辨率）；``Image.frombuffer`` 让平面**零拷贝**变成 PIL 图；
      2. 有限范围 → 全范围的 256 项 LUT（``Image.point``，C 实现）；
      3. ``Image.merge("YCbCr") + convert("RGB")``：色彩矩阵用 PIL 的 C 代码
         （numpy 定点公式在 Pi Zero 2 W 上要 14 ms，这里是 4 ms 级）；
      4. 半分辨率上画三分线/水平仪（0.4 ms），``convert("RGBA")`` 时 alpha
         天然是 255，不用再 putalpha；
      5. 2× 最近邻放大**直接写进复用的 packet**（numpy 四象限 uint32 赋值，
         3.8 ms）。不用 ``resize()+tobytes()``：那会每帧新分配 2 MB，光缺页
         就要 11 ms（Pi Zero 2 W 实测）。

    ``fit`` 语义与 ``--mirror-fit`` 一致：cover/stretch/fit/crop。
    """

    def __init__(self, fit: str = "fit", level: Optional[Callable[[], object]] = None):
        if np is None:
            raise GlxssError("LCD 渲染需要 numpy：sudo apt install -y python3-numpy")
        if Image is None:
            raise GlxssError("LCD 渲染需要 Pillow：sudo apt install -y python3-pil")
        fit = (fit or "fit").lower()
        if fit == "crop":
            fit = "cover"
        if fit not in ("cover", "stretch", "fit"):
            raise GlxssError("映射模式必须是 cover / stretch / fit，收到 %r" % (fit,))
        self.fit = fit
        self.level = level
        self.packet = array.array("B", bytes(lcd.FRAME_TOTAL))
        self._packet_view = memoryview(self.packet)
        # 16 B 帧头固定（format=1、totalLen、8 B 保留），只写一次。
        self._packet_view[0] = 1
        struct.pack_into("<I", self.packet, 4, lcd.FRAME_TOTAL)
        # 像素区按 uint32 看：一个像素一次赋值，2× 放大就是四次象限复制。
        self._pixels = np.frombuffer(
            self.packet, dtype=np.uint8, offset=lcd.FRAME_HEADER,
            count=lcd.FRAME_PAYLOAD).reshape(LCD_H, LCD_W, 4).view(
                np.uint32).reshape(LCD_H, LCD_W)

    # -- 采样 --------------------------------------------------------------
    def _ycbcr(self, frame):
        """YV12 → 半分辨率 YCbCr 图（U/V 与缩小后的 Y 1:1 配对）。"""
        width, height = frame.width, frame.height
        if width <= 0 or height <= 0 or width % 2 or height % 2:
            raise GlxssError("YUV420 宽高必须是正偶数（当前 %dx%d）" % (width, height))
        y_plane, u_plane, v_plane = yv12_planes(frame.data, width, height)
        if self.fit == "cover":
            crop_w, crop_h = cover_box(width, height)
            left = ((width - crop_w) // 2) // 2 * 2
            top = ((height - crop_h) // 2) // 2 * 2
        else:
            crop_w, crop_h, left, top = width, height, 0, 0
        half_w, half_h = crop_w // 2, crop_h // 2
        y_big = _plane_image(y_plane, crop_w, crop_h, left, top)
        y_small = y_big.resize((half_w, half_h), Image.NEAREST)
        u_small = _plane_image(u_plane, half_w, half_h, left // 2, top // 2)
        v_small = _plane_image(v_plane, half_w, half_h, left // 2, top // 2)
        return yuv_to_lcd_rgb(y_small, u_small, v_small)

    def _to_lcd_canvas(self, rgb):
        """把半分辨率 RGB 摆到 320×200 的最终画布上（cover/stretch/fit）。"""
        if rgb.size == (LCD_HALF_W, LCD_HALF_H):
            return rgb
        if self.fit == "stretch":
            return rgb.resize((LCD_HALF_W, LCD_HALF_H), Image.NEAREST)
        src_w, src_h = rgb.size
        scale = min(LCD_HALF_W / src_w, LCD_HALF_H / src_h)
        new_w = max(1, min(LCD_HALF_W, int(round(src_w * scale))))
        new_h = max(1, min(LCD_HALF_H, int(round(src_h * scale))))
        canvas = Image.new("RGB", (LCD_HALF_W, LCD_HALF_H), BACKDROP[:3])
        canvas.paste(rgb.resize((new_w, new_h), Image.NEAREST),
                     ((LCD_HALF_W - new_w) // 2, (LCD_HALF_H - new_h) // 2))
        return canvas

    # -- 叠加层（都在 320×200 上画；放大后线宽正好 2 px） --------------------
    @staticmethod
    def _draw_grid(draw) -> None:
        for x in (LCD_HALF_W // 3, LCD_HALF_W * 2 // 3):
            draw.line([(x, 0), (x, LCD_HALF_H - 1)], fill=GRID_COLOR)
        for y in (LCD_HALF_H // 3, LCD_HALF_H * 2 // 3):
            draw.line([(0, y), (LCD_HALF_W - 1, y)], fill=GRID_COLOR)

    def _draw_level(self, draw) -> None:
        pitch = roll = None
        if self.level is not None:
            try:
                angles = self.level()
            except Exception:
                angles = None
            if angles is not None:
                pitch, roll = angles
        center = LCD_HALF_H // 2
        if pitch is not None:
            # 半分辨率下 pitch 每度 1 px（放大后 = full 的 2 px/度，和
            # monitor.py 一致），±75 px。
            center = int(LCD_HALF_H / 2 + max(-75.0, min(75.0, float(pitch))))
            center = max(10, min(LCD_HALF_H - 10, center))
            slope = math.tan(math.radians(-float(roll)))
            y_left = center + slope * (0 - LCD_HALF_W // 2)
            y_right = center + slope * (LCD_HALF_W - 1 - LCD_HALF_W // 2)
            draw.line([(0, y_left), (LCD_HALF_W - 1, y_right)], fill=LEVEL_COLOR)
        cx = LCD_HALF_W // 2
        draw.line([(cx - 12, center), (cx - 4, center)], fill=RETICLE_COLOR)
        draw.line([(cx + 4, center), (cx + 12, center)], fill=RETICLE_COLOR)
        draw.line([(cx, center - 8), (cx, center - 3)], fill=RETICLE_COLOR)
        draw.line([(cx, center + 3), (cx, center + 8)], fill=RETICLE_COLOR)

    # -- 对外 --------------------------------------------------------------
    def render(self, frame):
        """渲染一帧，返回**复用的** ``array('B')`` 帧包（长度 1,024,016）。"""
        image = self._to_lcd_canvas(self._ycbcr(frame))
        draw = ImageDraw.Draw(image, "RGBA")  # RGB 图 + "RGBA" 才有 alpha 混合
        self._draw_grid(draw)
        self._draw_level(draw)
        # RGB→RGBA：alpha 直接填 255，不需要再 putalpha。
        rgba = image.convert("RGBA")
        if rgba.size != (LCD_HALF_W, LCD_HALF_H):
            raise GlxssError("画布尺寸必须是 %dx%d" % (LCD_HALF_W, LCD_HALF_H))
        # 2× 最近邻 = 四象限复制（PIL NEAREST 放大也是这个映射），直接写进
        # packet，省掉 resize()/tobytes() 每帧 2 MB 的新分配。
        src = np.asarray(rgba).view(np.uint32).reshape(LCD_HALF_H, LCD_HALF_W)
        dst = self._pixels
        dst[::2, ::2] = src
        dst[1::2, ::2] = src
        dst[::2, 1::2] = src
        dst[1::2, 1::2] = src
        return self.packet


# ---------------------------------------------------------------------------
# 水平仪数据源
# ---------------------------------------------------------------------------
class LevelPoller:
    """HID 加速度计 → ``(pitch, roll)``；拿不到就返回 None（只画准星）。

    只发一条 ``0xA1/0x12 = 1`` 使能加速度，之后每 ``interval`` 秒读一次 28 B
    报告。控制请求和 LCD 帧走同一个 ``UsbGate``，不会插进 1 MB 帧中间。
    """

    def __init__(self, dev, *, interval: float = 0.15, timeout: int = 1500,
                 gate=None, verbose: bool = False):
        self.dev = dev
        self.interval = max(0.05, float(interval))
        self.timeout = max(100, int(timeout))
        self.gate = gate
        self.verbose = verbose
        self.error = ""
        self._angles: Optional[Tuple[float, float]] = None
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def start(self) -> bool:
        try:
            ctx = self.gate if self.gate is not None else nullcontext()
            with ctx:
                hid.set_sensor(self.dev, 0x12, True, self.timeout)
        except GlxssError as exc:
            self.error = str(exc).splitlines()[0] if str(exc) else "加速度计不可用"
            if self.verbose:
                print("[stream] 水平仪不可用（只画准星）：%s" % self.error)
            return False
        self._thread = threading.Thread(
            target=self._loop, name="lcd-level", daemon=True)
        self._thread.start()
        return True

    def _loop(self) -> None:
        failures = 0
        while not self._stop.is_set():
            try:
                ctx = self.gate if self.gate is not None else nullcontext()
                with ctx:
                    report = hid.read_report(self.dev, self.timeout)
                if any(report.acc):
                    self._angles = pitch_roll_from_acc(*report.acc)
                failures = 0
            except GlxssError as exc:
                failures += 1
                self.error = str(exc).splitlines()[0] if str(exc) else "HID 读取失败"
                if failures >= 3:
                    return
            except Exception:
                return
            self._stop.wait(self.interval)

    def __call__(self):
        return self._angles

    def stop(self) -> None:
        self._stop.set()
        thread = self._thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=1.0)
        self._thread = None


# ---------------------------------------------------------------------------
# 消费者 1：LCD 线程
# ---------------------------------------------------------------------------
@dataclass
class LcdStats:
    frames: int = 0
    dropped: int = 0
    stale: int = 0
    errors: int = 0
    fps: float = 0.0
    render_ms: float = 0.0
    send_ms: float = 0.0
    last_error: str = ""

    def as_dict(self) -> dict:
        return {
            "frames": self.frames, "dropped": self.dropped,
            "stale": self.stale, "errors": self.errors,
            "fps": round(self.fps, 1),
            "render_ms": round(self.render_ms, 2),
            "send_ms": round(self.send_ms, 2),
            "last_error": self.last_error,
        }


class LcdSink:
    """LCD 线程：从最新帧槽取帧 → 渲染 → bulk OUT（按 fps 限速，丢旧帧）。"""

    def __init__(self, dev, ep_addr: int, config, hub: LatestFrame, *,
                 link=None, level=None, level_poller=None, gate=None):
        self.dev = dev
        self.hub = hub
        self.gate = gate
        self.fps = max(0.2, float(config.mirror_fps))
        self.timeout = max(1000, int(getattr(config, "timeout_ms", 5000)))
        self.link = link if link is not None else lcd.LcdLink(
            dev, ep_addr, timeout=self.timeout)
        self.renderer = LcdRenderer(fit=getattr(config, "mirror_fit", "fit"),
                                    level=level)
        self._level_poller = level_poller
        self.stats = LcdStats()
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._count_at = time.monotonic()
        self._count_frames = 0

    # -- 生命周期 ----------------------------------------------------------
    def start(self) -> None:
        try:
            ctx = self.gate if self.gate is not None else nullcontext()
            with ctx:
                self.link.initialize(self.timeout)
        except GlxssError as exc:
            print("[stream] 眼镜初始化失败（继续投屏）：%s" % exc)
        self._thread = threading.Thread(
            target=self._loop, name="lcd-sink", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        thread = self._thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=2.0)
        self._thread = None
        poller = self._level_poller
        if poller is not None:
            poller.stop()
            self._level_poller = None

    @property
    def alive(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    # -- 主循环 ------------------------------------------------------------
    def _loop(self) -> None:
        next_at = 0.0
        period = 1.0 / self.fps
        last_seq = -1
        while not self._stop.is_set():
            now = time.monotonic()
            if now < next_at:
                # 精确睡到下一帧时刻，再取**当时最新**的一帧。不能用
                # ``hub.take()`` 在这里等下一帧：相机的下一帧可能比发送时刻晚
                # 十几毫秒到，周期会被拖长（实测把 10 fps 拖成 8.7 fps）。
                self._stop.wait(min(next_at - now, 0.2))
                continue
            frame, seq = self.hub.latest_with_seq()
            if frame is None:
                if self._stop.is_set() or self.hub.closed:
                    return
                self._stop.wait(0.02)
                continue
            next_at = max(next_at, now) + period
            with self._lock:
                if seq == last_seq:
                    self.stats.stale += 1     # 相机比眼镜慢，重发上一帧
                else:
                    self.stats.dropped += max(0, seq - last_seq - 1)
            last_seq = seq
            started = time.monotonic()
            try:
                packet = self.renderer.render(frame)
            except Exception as exc:  # 坏帧只丢这一帧
                with self._lock:
                    self.stats.errors += 1
                    self.stats.last_error = "渲染失败: %s" % (
                        str(exc).splitlines()[0] if str(exc) else exc)
                continue
            rendered = time.monotonic()
            try:
                # LCD 的 1 MB bulk OUT 和 HID/亮度等控制请求走同一个闸门：
                # 控制请求插进一帧中间最容易触发固件异常（见 usb.UsbGate）。
                ctx = self.gate if self.gate is not None else nullcontext()
                with ctx:
                    self.link.send_packet(packet)
            except Exception as exc:
                message = "发送失败: %s" % (
                    str(exc).splitlines()[0] if str(exc) else exc)
                with self._lock:
                    self.stats.errors += 1
                    self.stats.last_error = message
                    errors = self.stats.errors
                # 设备断了就别继续烧 CPU；主循环会通过 alive 看到线程结束。
                if not self._stop.is_set() and errors >= 5:
                    print("[stream] 眼镜投屏连续失败，LCD 线程退出：%s" % message)
                    return
                continue
            sent = time.monotonic()
            with self._lock:
                self.stats.frames += 1
                self.stats.render_ms = (rendered - started) * 1000.0
                self.stats.send_ms = (sent - rendered) * 1000.0
                if sent - self._count_at >= 1.0:
                    self.stats.fps = (
                        (self.stats.frames - self._count_frames) /
                        max(1e-6, sent - self._count_at))
                    self._count_at = sent
                    self._count_frames = self.stats.frames

    # -- 状态 --------------------------------------------------------------
    def stats_dict(self) -> dict:
        with self._lock:
            return self.stats.as_dict()


# ---------------------------------------------------------------------------
# 便捷构造
# ---------------------------------------------------------------------------
def open_lcd_sink(dev, session, config, hub: LatestFrame, *, gate=None,
                  claim: bool = True) -> LcdSink:
    """claim LCD 接口（必要时）+ 建 LcdSink；水平仪按需启动。"""
    intf, ep = usb.pick_bulk_out(dev)
    if claim:
        session.claim_interface(intf, label="LCD(眼镜预览)")
    poller = None
    if getattr(config, "mirror_level", True):
        poller = LevelPoller(dev, interval=0.15,
                             timeout=min(1500, max(200, config.timeout_ms)),
                             gate=gate, verbose=config.verbose)
        if not poller.start():
            poller = None
    return LcdSink(dev, ep.bEndpointAddress, config, hub,
                   level=poller, level_poller=poller, gate=gate)
