# -*- coding: utf-8 -*-
"""UVC 摄像头 → H.264 → RTSP 直播（Pi Zero 2 W / 通用 Linux）。

链路（**一次采集、一次解码、多消费者分发**，对齐原厂 `:llvision_G25`）：

    GLXSS G25（UVC bulk IN，YV12 1280×720）
      → pyusb 读帧线程（和 `glxss uvc` 同一条路，需要 --detach 摘掉 uvcvideo）
      → 发布到「最新帧槽」`pipeline.LatestFrame`（旧的直接丢，不排队）
           ├─ 编码线程 → ffmpeg stdin（YV12→yuv420p 只换写入顺序，零像素拷贝）
           │            → h264_v4l2m2m（Pi 硬编码 /dev/video11）→ RTSP
           └─ LCD 线程 → 4×4 抽样 320×180/320×200 → YUV→RGB → 640×400 → 眼镜
                        （--mirror-fps 限速，丢旧帧）

备选输出：

    * MJPEG HTTP（:8080/stream）：OBS 用「VLC 视频源」拉；不需要 mediamtx，
      但 720p JPEG 在 Zero 2 W 上更慢，只作调试用。
    * 眼镜预览（--mirror-fps）：把同一路画面低帧率投到眼镜 640×400 屏上，
      方便对准；和 HUD 主进程抢 LCD 接口，二选一。

设计要点：

    * 读线程只做「读 + 发布」，**绝不做解码/编码/发送**，任何消费者慢了都不会
      拖慢采集（这是 `--mirror-fps 10` 时采集掉到 4.6 fps 的根因修复）；
    * 编码线程按 --fps 用令牌桶节流，取最新帧，跟不上就丢旧帧，延迟不累积；
    * LCD 线程按 --mirror-fps 限速，渲染 + 发送都在自己线程里；
    * 断线自动重连：UVC 掉线重开设备，ffmpeg 退出自动重启；
    * 统计：采集/编码/眼镜三路 fps、丢弃、YUV 入带宽、mediamtx API 实测码率、观众数。

同一时间只有一个进程能占用 UVC 接口：`glxss uvc` / 监看模式 / 本模块 三者
互斥；HUD 只占 LCD 接口，可以和直播共存（除非开了 --mirror-fps）。
"""

from __future__ import annotations

import json
import shutil
import socket
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from collections import deque
from dataclasses import dataclass, field, replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable, List, Optional, Tuple

from . import lcd, pipeline, usb, uvc
from .errors import GlxssError

DEFAULT_RTSP = "rtsp://127.0.0.1:8554/glxss"
LCD_W, LCD_H = lcd.FRAME_W, lcd.FRAME_H  # 640×400（眼镜屏）


# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
@dataclass
class StreamConfig:
    """一路直播的全部参数（`pi/stream.py` 负责把命令行映射到这里）。"""

    rtsp_url: str = DEFAULT_RTSP
    width: int = 1280
    height: int = 720
    fps: float = 15.0
    # UVC 描述符里的格式/帧序号；None=negotiate() 按分辨率自动选（720p=frame 3）。
    # 设备 YV12 还有 1920×1080 / 1280×960 / 640×480 等档位（见 uvc.KNOWN_MODES）。
    format_index: Optional[int] = None
    frame_index: Optional[int] = None
    bitrate: str = "1500k"
    codec: str = "h264_v4l2m2m"
    transport: str = "tcp"
    gop: int = 40
    maxrate: str = ""
    bufsize: str = ""
    detach: bool = True
    timeout_ms: int = 5000
    ffmpeg: str = "ffmpeg"
    extra_ffmpeg: Tuple[str, ...] = ()
    # 旧版「编码器队列深度」；现在改成最新帧槽（深度 1，旧的直接丢），保留字段
    # 只为兼容旧调用方，不参与运行。
    backlog: int = 2
    # 备选输出
    mjpeg_port: int = 0
    mjpeg_fps: float = 10.0
    mjpeg_quality: int = 70
    mirror_fps: float = 0.0
    mirror_fit: str = "fit"
    mirror_rotate: int = 0
    mirror_level: bool = True
    # 观测
    api_url: str = ""
    stats_interval: float = 2.0
    # 运行控制
    duration: float = 0.0
    max_frames: int = 0
    verbose: bool = False
    test_source: bool = False

    @property
    def frame_size(self) -> int:
        return self.width * self.height * 3 // 2

    @property
    def period(self) -> float:
        return (1.0 / self.fps) if self.fps > 0 else 0.0

    def validate(self) -> None:
        if self.width <= 0 or self.height <= 0:
            raise GlxssError("分辨率必须为正（当前 %dx%d）" % (self.width, self.height))
        if self.width % 2 or self.height % 2:
            raise GlxssError("YUV420 宽高必须是偶数（当前 %dx%d）" % (self.width, self.height))
        if self.fps < 0:
            raise GlxssError("--fps 不能为负")
        if self.fps > 60:
            raise GlxssError("--fps 最大 60（Pi Zero 2 W 上建议 20）")
        if not 0 <= self.mjpeg_port <= 65535:
            raise GlxssError("--mjpeg-port 必须在 0..65535")
        if self.mirror_fps < 0:
            raise GlxssError("--mirror-fps 不能为负")
        if self.mirror_fit not in ("fit", "cover", "crop", "stretch"):
            raise GlxssError("--mirror-fit 只能是 fit/cover/stretch")
        if self.mirror_rotate not in (0, 180):
            raise GlxssError("--mirror-rotate 只能是 0 或 180")
        if self.frame_index is not None and not 1 <= self.frame_index <= 255:
            raise GlxssError("--frame-index 必须在 1..255")
        if self.format_index is not None and not 1 <= self.format_index <= 255:
            raise GlxssError("--format-index 必须在 1..255")
        if not self.rtsp_url and self.mjpeg_port == 0:
            raise GlxssError("至少要有一种输出：--rtsp 或 --mjpeg-port")

    @classmethod
    def from_args(cls, args) -> "StreamConfig":
        """把 argparse.Namespace 转成配置；尺寸支持 `--size 1280x720`。"""
        width, height = 1280, 720
        size = getattr(args, "size", None)
        if size:
            try:
                width, height = (int(part) for part in str(size).lower().split("x", 1))
            except Exception as exc:
                raise GlxssError("--size 格式应为 1280x720，收到 %r" % (size,)) from exc
        config = cls(
            rtsp_url=getattr(args, "rtsp", DEFAULT_RTSP) or "",
            width=width,
            height=height,
            fps=float(getattr(args, "fps", 15.0)),
            format_index=getattr(args, "format_index", None),
            frame_index=getattr(args, "frame_index", None),
            bitrate=str(getattr(args, "bitrate", "1500k")),
            codec=str(getattr(args, "codec", "h264_v4l2m2m")),
            transport=str(getattr(args, "transport", "tcp")),
            gop=int(getattr(args, "gop", 0) or 0),
            maxrate=str(getattr(args, "maxrate", "") or ""),
            bufsize=str(getattr(args, "bufsize", "") or ""),
            detach=bool(getattr(args, "detach", True)),
            timeout_ms=int(getattr(args, "timeout", 5000)),
            ffmpeg=str(getattr(args, "ffmpeg", "ffmpeg")),
            mjpeg_port=int(getattr(args, "mjpeg_port", 0) or 0),
            mjpeg_fps=float(getattr(args, "mjpeg_fps", 10.0)),
            mjpeg_quality=int(getattr(args, "mjpeg_quality", 70)),
            mirror_fps=float(getattr(args, "mirror_fps", 0.0) or 0.0),
            mirror_fit=str(getattr(args, "mirror_fit", "fit")),
            mirror_rotate=int(getattr(args, "mirror_rotate", 0) or 0),
            mirror_level=bool(getattr(args, "mirror_level", True)),
            api_url=str(getattr(args, "api_url", "") or ""),
            stats_interval=float(getattr(args, "stats_interval", 2.0)),
            duration=float(getattr(args, "duration", 0.0) or 0.0),
            max_frames=int(getattr(args, "frames", 0) or 0),
            verbose=bool(getattr(args, "verbose", False)),
            test_source=bool(getattr(args, "test_source", False)),
        )
        if config.gop <= 0:
            config.gop = max(1, int(round(config.fps * 2)))
        config.validate()
        return config


# ---------------------------------------------------------------------------
# 帧 / 像素格式
# ---------------------------------------------------------------------------
@dataclass
class StreamFrame:
    """统一后的帧：data 始终是 YV12（Y | V | U），与设备一致。"""

    index: int
    data: bytes
    width: int
    height: int
    timestamp: float = field(default_factory=time.time)

    @property
    def frame_size(self) -> int:
        return len(self.data)


def yv12_to_i420(data: bytes, width: int, height: int) -> bytes:
    """YV12（Y|V|U）→ I420/yuv420p（Y|U|V）。

    Debian 12 的 ffmpeg 5.1 已移除 `yv12` 像素格式（`-pix_fmt yv12` 报
    "No such pixel format"），所以这里交换两个色度平面再喂给 ffmpeg。
    1280×720 下是一次 1.38 MB 的整帧切片，实际编码走
    `pipeline.write_yv12_as_i420()`（三次 memoryview 写入，零像素拷贝）。
    """
    y_size = width * height
    c_size = y_size // 4
    need = y_size + 2 * c_size
    if len(data) < need:
        raise GlxssError("YUV 数据不足：需要 %d B，实际 %d B" % (need, len(data)))
    if len(data) == need:
        return data[:y_size] + data[y_size + c_size:need] + data[y_size:y_size + c_size]
    return data[:y_size] + data[y_size + c_size:need] + data[y_size:y_size + c_size]


def local_ip() -> str:
    """猜本机局域网 IP（只为打印 OBS 该填的地址，失败就返回 127.0.0.1）。"""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(0.2)
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
        sock.close()
        return ip
    except Exception:
        return "127.0.0.1"


def obs_url(rtsp_url: str) -> str:
    """把推流地址里的 127.0.0.1/0.0.0.0 换成局域网 IP，方便直接填 OBS。"""
    parsed = urllib.parse.urlparse(rtsp_url)
    host = parsed.hostname or ""
    if host in ("127.0.0.1", "localhost", "0.0.0.0", "::1", ""):
        port = parsed.port or 8554
        path = parsed.path or "/glxss"
        return "rtsp://%s:%d%s" % (local_ip(), port, path)
    return rtsp_url


# ---------------------------------------------------------------------------
# 编码器
# ---------------------------------------------------------------------------
class FfmpegEncoder:
    """一个 ffmpeg 子进程：stdin 吃 rawvideo(I420)，输出 RTSP。"""

    def __init__(self, config: StreamConfig):
        self.config = config
        self.proc: Optional[subprocess.Popen] = None
        self._stderr: deque = deque(maxlen=16)
        self._stderr_lock = threading.Lock()
        self._stderr_thread: Optional[threading.Thread] = None

    # -- 命令行 ---------------------------------------------------------
    def build_command(self) -> List[str]:
        cfg = self.config
        level = "info" if cfg.verbose else "warning"
        cmd = [
            cfg.ffmpeg, "-hide_banner", "-loglevel", level, "-nostdin",
            # 输入尽量不缓存
            "-fflags", "nobuffer", "-flags", "low_delay",
            "-f", "rawvideo", "-pix_fmt", "yuv420p",
            "-s", "%dx%d" % (cfg.width, cfg.height),
            "-r", _num(cfg.fps),
            "-i", "pipe:0",
            "-an", "-c:v", cfg.codec, "-b:v", cfg.bitrate,
        ]
        if cfg.codec.startswith("h264_v4l2m2m") or cfg.codec.startswith("hevc_v4l2m2m"):
            # Pi 硬编码：V4L2 mem2mem，B 帧不支持
            cmd += ["-g", str(cfg.gop), "-bf", "0"]
            if cfg.maxrate:
                cmd += ["-maxrate", cfg.maxrate]
            if cfg.bufsize:
                cmd += ["-bufsize", cfg.bufsize]
        elif cfg.codec.startswith("libx264"):
            cmd += ["-preset", "ultrafast", "-tune", "zerolatency",
                    "-g", str(cfg.gop), "-bf", "0"]
            if cfg.maxrate:
                cmd += ["-maxrate", cfg.maxrate]
            if cfg.bufsize:
                cmd += ["-bufsize", cfg.bufsize]
        cmd += ["-pix_fmt", "yuv420p"]
        if cfg.rtsp_url:
            cmd += ["-f", "rtsp", "-rtsp_transport", cfg.transport]
            # 降低 mux 延迟（默认 0.7 s 级）
            cmd += ["-muxdelay", "0"]
            cmd += [cfg.rtsp_url]
        else:
            # 没有 RTSP 时仍生成一条丢弃流，方便 --mjpeg-port 单独用
            cmd += ["-f", "null", "-"]
        if cfg.extra_ffmpeg:
            cmd += list(cfg.extra_ffmpeg)
        return cmd

    # -- 生命周期 -------------------------------------------------------
    def start(self) -> None:
        if not shutil.which(self.config.ffmpeg):
            raise GlxssError(
                "找不到 ffmpeg（%s）。Pi 上安装：sudo apt install -y ffmpeg"
                % self.config.ffmpeg)
        cmd = self.build_command()
        if self.config.verbose:
            print("[stream] ffmpeg: " + " ".join(cmd))
        try:
            self.proc = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
            )
        except OSError as exc:
            raise GlxssError("启动 ffmpeg 失败：%s" % exc) from exc
        self._stderr_thread = threading.Thread(
            target=self._drain_stderr, name="ffmpeg-stderr", daemon=True)
        self._stderr_thread.start()

    def _drain_stderr(self) -> None:
        proc = self.proc
        if proc is None or proc.stderr is None:
            return
        for raw in iter(proc.stderr.readline, b""):
            line = raw.decode("utf-8", "replace").rstrip()
            if not line:
                continue
            with self._stderr_lock:
                self._stderr.append(line)
            if self.config.verbose:
                print("[ffmpeg] " + line)

    def write(self, data: bytes) -> bool:
        proc = self.proc
        if proc is None or proc.stdin is None:
            return False
        try:
            proc.stdin.write(data)
            return True
        except (BrokenPipeError, ValueError, OSError):
            return False

    def write_frame(self, frame: StreamFrame) -> int:
        """把一帧 YV12 按 yuv420p 顺序写进 stdin，返回字节数；管道断了返回 0。

        YV12 = Y|V|U、yuv420p = Y|U|V，所以只要按 Y→U→V 的顺序分三次写，
        不用在 Python 里拼 1.38 MB 的新 buffer（见 pipeline.yv12_plane_views）。
        数据长度不对会抛 GlxssError，调用方只丢这一帧。
        """
        proc = self.proc
        if proc is None or proc.stdin is None:
            return 0
        try:
            return pipeline.write_yv12_as_i420(
                proc.stdin, frame.data, frame.width, frame.height)
        except (BrokenPipeError, ValueError, OSError):
            return 0

    def poll(self) -> Optional[int]:
        if self.proc is None:
            return -1
        return self.proc.poll()

    def error_tail(self, limit: int = 3) -> str:
        with self._stderr_lock:
            lines = list(self._stderr)[-limit:]
        return " | ".join(lines) if lines else "（无输出）"

    def stop(self, timeout: float = 3.0) -> None:
        """停 ffmpeg。

        先 SIGTERM 再关 stdin：硬编码器卡死时 `stdin.close()` 的 flush 会永久
        阻塞（管道满、子进程不再读），直播场景也不需要优雅 flush。
        """
        proc = self.proc
        self.proc = None
        if proc is None:
            return
        try:
            if proc.poll() is None:
                proc.terminate()
        except Exception:
            pass
        try:
            proc.wait(timeout=max(1.0, timeout))
        except subprocess.TimeoutExpired:
            try:
                proc.kill()
                proc.wait(timeout=1.5)
            except Exception:
                pass
        except Exception:
            pass
        # 子进程已退出，这里通常立即返回；放到后台线程兜底，避免极端情况卡住
        stdin = proc.stdin
        if stdin is not None:
            threading.Thread(target=_close_quietly, args=(stdin,), daemon=True).start()


def _close_quietly(stream) -> None:
    try:
        stream.close()
    except Exception:
        pass


class EncoderSink:
    """编码线程：从「最新帧槽」取帧 → ffmpeg stdin。

    * YV12→yuv420p 只交换 U/V 的**写入顺序**（`pipeline.write_yv12_as_i420`），
      Python 侧零像素拷贝、零色彩转换；
    * 按 ``--fps`` 用令牌桶节流：相机 30 fps、目标 25 fps 时丢掉多余的帧，
      送进编码器的永远是最新画面，延迟不累积；
    * 写不进（管道满 / ffmpeg 死了）就置 ``fatal`` 让主循环重启编码器，
      **绝不让读线程等**。
    """

    def __init__(self, config: StreamConfig, hub: "pipeline.LatestFrame"):
        self.config = config
        self.hub = hub
        self.encoder = FfmpegEncoder(config)
        self.fps = max(0.0, float(config.fps))
        self.frames = 0
        self.bytes_written = 0
        self.dropped = 0
        self.fatal: str = ""
        self._thread: Optional[threading.Thread] = None
        self._running = False

    def start(self) -> None:
        self.encoder.start()
        self._running = True
        self._thread = threading.Thread(
            target=self._loop, name="encoder-sink", daemon=True)
        self._thread.start()

    def _loop(self) -> None:
        seq = 0
        tokens = 1.0
        last_tick = time.monotonic()
        while self._running:
            frame, seq = self.hub.take(seq, timeout=0.25)
            if frame is None:
                if not self._running or self.hub.closed:
                    return
                continue
            now = time.monotonic()
            if self.fps:
                # 允许攒 2 帧的抖动余量，避免调度抖动导致成对丢帧。
                tokens = min(2.0, tokens + max(0.0, now - last_tick) * self.fps)
                last_tick = now
                if tokens < 1.0:
                    self.dropped += 1
                    continue
                tokens -= 1.0
            try:
                written = self.encoder.write_frame(frame)
            except GlxssError:
                self.dropped += 1
                continue
            if not written:
                self.fatal = "ffmpeg 写入失败：" + self.encoder.error_tail()
                self._running = False
                return
            self.frames += 1
            self.bytes_written += written

    def alive(self) -> bool:
        return self._running and self.encoder.poll() is None

    def stop(self) -> None:
        self._running = False
        self.encoder.stop()
        if self._thread is not None:
            self._thread.join(timeout=1.0)
            self._thread = None


# 兼容旧名字（内部/脚本可能还在 import）。
EncoderPipeline = EncoderSink


def _num(value: float) -> str:
    return ("%d" % value) if float(value).is_integer() else ("%.3f" % value)


# ---------------------------------------------------------------------------
# 采集源
# ---------------------------------------------------------------------------
class UvcSource:
    """pyusb 读 GLXSS UVC；需要 detach uvcvideo（Linux）。"""

    name = "uvc"

    def __init__(self, config: StreamConfig):
        self.config = config
        self.dev = None
        self.session: Optional[usb.DeviceSession] = None
        self.stream: Optional[uvc.UvcStream] = None
        self.probe = None
        self.gate = usb.UsbGate(verbose=config.verbose)

    def open(self):
        self.dev = usb.find_normal()
        self.session = usb.DeviceSession(
            self.dev, detach=self.config.detach, verbose=self.config.verbose)
        self.stream = uvc.UvcStream.open(
            self.dev, self.session, timeout=self.config.timeout_ms,
            verbose=self.config.verbose)
        self.stream.set_dimensions(self.config.width, self.config.height)
        self.probe = self.stream.negotiate(
            format_index=self.config.format_index,
            frame_index=self.config.frame_index,
            fps=self.config.fps or None,
            max_frame=self.config.frame_size,
        )
        return self.probe

    def read(self) -> StreamFrame:
        if self.stream is None:
            raise GlxssError("UVC 未打开")
        frame = self.stream.read_frame(timeout=self.config.timeout_ms)
        return StreamFrame(frame.index, frame.data, frame.width, frame.height,
                           frame.timestamp)

    def close(self) -> None:
        if self.session is not None:
            try:
                self.session.close()
            except Exception:
                pass
        self.session = None
        self.stream = None
        self.dev = None

    # 眼镜低帧率预览 -----------------------------------------------------
    def create_lcd_sink(self, config: StreamConfig,
                        hub: "pipeline.LatestFrame") -> Optional["pipeline.LcdSink"]:
        """claim LCD 接口 + 建 LCD 线程（共享同一份帧，不再单独读 UVC）。"""
        if config.mirror_fps <= 0:
            return None
        if self.dev is None or self.session is None:
            raise GlxssError("设备未打开，无法投眼镜")
        return pipeline.open_lcd_sink(self.dev, self.session, config, hub,
                                      gate=self.gate)


class TestPatternSource:
    """无眼镜时的合成 YV12 源：验证编码/推流/OBS 全链路。"""

    name = "test-pattern"

    def __init__(self, config: StreamConfig):
        self.config = config
        self._index = 0
        self._next = 0.0
        self._numpy = None

    def open(self):
        try:
            import numpy as np  # type: ignore
        except Exception as exc:
            raise GlxssError("测试图案需要 numpy：python3 -m pip install numpy") from exc
        self._numpy = np
        return None

    def read(self) -> StreamFrame:
        import numpy as np  # type: ignore

        cfg = self.config
        period = cfg.period
        now = time.monotonic()
        if self._next <= 0:
            self._next = now
        if now < self._next:
            time.sleep(self._next - now)
            now = time.monotonic()
        self._next = max(now, self._next + period) if period else now
        index = self._index
        self._index += 1
        w, h = cfg.width, cfg.height
        # 滚动的灰度渐变 + 两块彩色区域，肉眼能看出卡顿/花屏/偏色
        row = ((np.arange(w, dtype=np.uint32) + index * 6) % 256).astype(np.uint8)
        y = np.tile(row, (h, 1))
        v = np.full((h // 2, w // 2), 128, dtype=np.uint8)
        u = np.full((h // 2, w // 2), 128, dtype=np.uint8)
        u[:, : w // 4] = 90
        v[:, : w // 4] = 180
        u[:, w // 2: 3 * w // 4] = 180
        v[:, w // 2: 3 * w // 4] = 90
        data = y.tobytes() + v.tobytes() + u.tobytes()  # YV12
        return StreamFrame(index, data, w, h)

    def close(self) -> None:
        pass

    def create_lcd_sink(self, config: StreamConfig, hub) -> None:
        return None


def open_source(config: StreamConfig):
    return TestPatternSource(config) if config.test_source else UvcSource(config)


# ---------------------------------------------------------------------------
# MJPEG HTTP（备选）
# ---------------------------------------------------------------------------
class _MjpegHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "glxss-mjpeg/1.0"

    def log_message(self, fmt, *args):  # 静默
        if getattr(self.server, "verbose", False):
            print("[mjpeg] " + (fmt % args))

    def do_GET(self):  # noqa: N802
        mjpeg: "MjpegOutput" = self.server.mjpeg  # type: ignore[attr-defined]
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        if parsed.path in ("/", "/index.html") and "action=stream" not in self.path:
            body = (
                "<html><head><title>GLXSS G25 MJPEG</title></head><body>"
                "<h3>GLXSS G25 live</h3>"
                "<p><img src=\"/stream\" style=\"max-width:100%\"></p>"
                "<p><a href=\"/snapshot.jpg\">snapshot.jpg</a></p></body></html>"
            ).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if parsed.path == "/stream" or query.get("action") == ["stream"]:
            mjpeg.stream_response(self)
            return
        if parsed.path in ("/snapshot.jpg", "/snapshot"):
            jpeg = mjpeg.snapshot()
            if not jpeg:
                mjpeg.encode_now()
                jpeg = mjpeg.snapshot()
            if not jpeg:
                self.send_error(503, "no frame yet")
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(jpeg)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(jpeg)
            return
        self.send_error(404, "not found")


class MjpegOutput:
    """把最新一帧编码成 JPEG，用 multipart/x-mixed-replace 推给 VLC/OBS。"""

    def __init__(self, config: StreamConfig):
        self.config = config
        self.fps = max(0.5, config.mjpeg_fps)
        self.quality = max(10, min(95, config.mjpeg_quality))
        self._lock = threading.Lock()
        self._cond = threading.Condition(self._lock)
        self._pending: Optional[StreamFrame] = None
        self._last_frame: Optional[StreamFrame] = None
        self._jpeg: Optional[bytes] = None
        self._seq = 0
        self._clients = 0
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self.bytes_sent = 0
        self.frames_sent = 0
        self.httpd: Optional[ThreadingHTTPServer] = None

    # -- 生命周期 -------------------------------------------------------
    def start(self) -> None:
        try:
            from PIL import Image  # noqa: F401  # type: ignore
        except Exception as exc:
            raise GlxssError(
                "MJPEG 输出需要 Pillow：sudo apt install -y python3-pil") from exc
        self._running = True
        self._thread = threading.Thread(
            target=self._encode_loop, name="mjpeg-encoder", daemon=True)
        self._thread.start()
        try:
            httpd = ThreadingHTTPServer(("0.0.0.0", self.config.mjpeg_port), _MjpegHandler)
        except OSError as exc:
            raise GlxssError("MJPEG 端口 %d 绑定失败：%s" % (
                self.config.mjpeg_port, exc)) from exc
        httpd.daemon_threads = True
        httpd.mjpeg = self  # type: ignore[attr-defined]
        httpd.verbose = self.config.verbose  # type: ignore[attr-defined]
        self.httpd = httpd
        threading.Thread(target=httpd.serve_forever, name="mjpeg-http",
                         daemon=True).start()

    def submit(self, frame: StreamFrame) -> None:
        with self._cond:
            self._pending = frame
            self._last_frame = frame
            self._cond.notify_all()

    def _encode_loop(self) -> None:
        last = 0.0
        while self._running:
            with self._cond:
                while self._running and self._pending is None:
                    self._cond.wait(0.5)
                frame = self._pending
                self._pending = None
                clients = self._clients
            if frame is None:
                continue
            now = time.monotonic()
            if now - last < 1.0 / self.fps:
                continue
            if clients <= 0:
                continue
            last = now
            try:
                jpeg = self._encode(frame)
            except Exception as exc:
                print("[stream] MJPEG 编码失败：%s" % exc)
                continue
            with self._cond:
                self._jpeg = jpeg
                self._seq += 1
                self._cond.notify_all()

    def _encode(self, frame: StreamFrame) -> bytes:
        import io

        from PIL import Image  # type: ignore

        rgb = uvc.yv12_to_rgb(frame.data, frame.width, frame.height, order="yv12")
        buffer = io.BytesIO()
        Image.fromarray(rgb, "RGB").save(buffer, "JPEG", quality=self.quality)
        return buffer.getvalue()

    def encode_now(self) -> None:
        """给 /snapshot.jpg 用：没有缓存时立刻编一帧。"""
        with self._cond:
            frame = self._last_frame
        if frame is None:
            return
        try:
            jpeg = self._encode(frame)
        except Exception:
            return
        with self._cond:
            self._jpeg = jpeg
            self._seq += 1
            self._cond.notify_all()

    # -- HTTP -----------------------------------------------------------
    def snapshot(self) -> Optional[bytes]:
        with self._cond:
            return self._jpeg

    def stream_response(self, handler: BaseHTTPRequestHandler) -> None:
        boundary = b"glxssframe"
        handler.send_response(200)
        handler.send_header(
            "Content-Type", "multipart/x-mixed-replace; boundary=%s" % boundary.decode())
        handler.send_header("Cache-Control", "no-store")
        handler.send_header("Pragma", "no-cache")
        handler.end_headers()
        with self._cond:
            self._clients += 1
        last_seq = -1
        try:
            while self._running:
                with self._cond:
                    if self._seq == last_seq:
                        self._cond.wait(1.0)
                    jpeg, seq = self._jpeg, self._seq
                if jpeg is None or seq == last_seq:
                    continue
                last_seq = seq
                chunk = (b"--" + boundary + b"\r\n"
                         b"Content-Type: image/jpeg\r\n"
                         b"Content-Length: " + str(len(jpeg)).encode() + b"\r\n\r\n"
                         + jpeg + b"\r\n")
                handler.wfile.write(chunk)
                handler.wfile.flush()
                self.bytes_sent += len(chunk)
                self.frames_sent += 1
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            with self._cond:
                self._clients = max(0, self._clients - 1)

    @property
    def clients(self) -> int:
        with self._cond:
            return self._clients

    def stop(self) -> None:
        self._running = False
        with self._cond:
            self._cond.notify_all()
        if self.httpd is not None:
            self.httpd.shutdown()
            self.httpd.server_close()
            self.httpd = None
        if self._thread is not None:
            self._thread.join(timeout=1.0)
            self._thread = None


# ---------------------------------------------------------------------------
# mediamtx 实测码率（可选）
# ---------------------------------------------------------------------------
class MediamtxStats:
    """轮询 mediamtx API，读 bytesReceived → 实际编码码率。"""

    def __init__(self, api_url: str, path: str, interval: float = 2.0,
                 verbose: bool = False):
        api_url = api_url.rstrip("/")
        self.url = "%s/v3/paths/get/%s" % (api_url, urllib.parse.quote(path))
        self.interval = max(0.5, interval)
        self.verbose = verbose
        self.kbps = 0.0
        self.viewers = 0
        self.online = False
        self._bytes = 0
        self._time = 0.0
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._loop, name="mtx-stats", daemon=True)
        self._thread.start()

    def _loop(self) -> None:
        while not self._stop.wait(self.interval):
            self.poll_once()

    def poll_once(self) -> None:
        try:
            with urllib.request.urlopen(self.url, timeout=1.5) as resp:
                data = json.loads(resp.read().decode("utf-8"))
        except Exception as exc:
            self.online = False
            if self.verbose:
                print("[stream] mediamtx API 读取失败：%s" % exc)
            return
        now = time.monotonic()
        received = int(data.get("bytesReceived") or 0)
        if self._time and received >= self._bytes:
            dt = max(0.001, now - self._time)
            self.kbps = (received - self._bytes) * 8.0 / dt / 1000.0
        self._bytes = received
        self._time = now
        readers = data.get("readers") or []
        self.viewers = len(readers)
        self.online = True

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=1.0)
            self._thread = None


# ---------------------------------------------------------------------------
# 统计
# ---------------------------------------------------------------------------
@dataclass
class StreamStats:
    started: float = 0.0
    captured: int = 0
    sent: int = 0
    dropped: int = 0
    bytes_in: int = 0
    encoder_restarts: int = 0
    source_restarts: int = 0
    capture_fps: float = 0.0
    send_fps: float = 0.0
    bitrate_kbps: float = 0.0
    viewers: int = 0
    mjpeg_fps: float = 0.0
    # 眼镜预览（LCD 线程）
    lcd: bool = False
    lcd_fps: float = 0.0
    lcd_ms: float = 0.0
    lcd_frames: int = 0
    lcd_dropped: int = 0
    last_error: str = ""

    @property
    def elapsed(self) -> float:
        return max(1e-6, time.monotonic() - self.started) if self.started else 0.0

    def line(self) -> str:
        mjpeg = (" MJPEG=%.1ffps" % self.mjpeg_fps) if self.mjpeg_fps else ""
        lcd = (" 眼镜=%.1ffps/%.1fms" % (self.lcd_fps, self.lcd_ms)
               if self.lcd else "")
        return (
            "帧=%d 编码=%d 丢弃=%d 采集=%.1ffps 编码=%.1ffps YUV=%.2fMB/s "
            "实测=%dkbps 观众=%d 重启=%d/%d%s%s 运行=%s" % (
                self.captured, self.sent, self.dropped, self.capture_fps,
                self.send_fps, self.bytes_in / self.elapsed / 1e6,
                int(self.bitrate_kbps), self.viewers,
                self.encoder_restarts, self.source_restarts, mjpeg, lcd,
                _hms(self.elapsed)))


def _hms(seconds: float) -> str:
    seconds = int(seconds)
    return "%02d:%02d:%02d" % (seconds // 3600, seconds % 3600 // 60, seconds % 60)


# ---------------------------------------------------------------------------
# 主循环
# ---------------------------------------------------------------------------
StatsCallback = Optional[Callable[[StreamStats], None]]


class Streamer:
    """把 UVC 源、编码线程、LCD 线程、MJPEG 输出和统计串起来。

    主线程就是**读线程**：``source.read()`` → ``hub.publish()``，然后立刻回去
    读下一帧。解码/编码/USB 发送全在各自的消费者线程里，谁慢谁丢自己的旧帧。
    """

    def __init__(self, config: StreamConfig, on_stats: StatsCallback = None):
        self.config = config
        self.on_stats = on_stats
        self.stats = StreamStats(started=time.monotonic())
        self._stop = threading.Event()
        self._source = None
        self.hub = pipeline.LatestFrame()
        self._pipe: Optional[EncoderSink] = None
        self._mjpeg: Optional[MjpegOutput] = None
        self._lcd: Optional[pipeline.LcdSink] = None
        self._api: Optional[MediamtxStats] = None
        # 编码器线程的累计计数（pipe 重启后归零，靠这里换算成总量）
        self._pipe_frames = 0
        self._pipe_dropped = 0

    def stop(self) -> None:
        self._stop.set()

    # -- 打印 -----------------------------------------------------------
    def _print(self, message: str) -> None:
        print("[stream] " + message, flush=True)

    def _print_banner(self) -> None:
        cfg = self.config
        if cfg.rtsp_url:
            self._print("源=%s %dx%d @%.1ffps  编码=%s %s  传输=%s" % (
                "测试图案" if cfg.test_source else "GLXSS UVC",
                cfg.width, cfg.height, cfg.fps, cfg.codec, cfg.bitrate, cfg.transport))
            self._print("推流 → %s" % cfg.rtsp_url)
            self._print("OBS 媒体源请填 → %s" % obs_url(cfg.rtsp_url))
        else:
            self._print("源=%s %dx%d @%.1ffps  仅 MJPEG 输出（不启动 ffmpeg）" % (
                "测试图案" if cfg.test_source else "GLXSS UVC",
                cfg.width, cfg.height, cfg.fps))
        if cfg.mjpeg_port:
            self._print("MJPEG → http://%s:%d/stream" % (
                local_ip(), cfg.mjpeg_port))
        if cfg.mirror_fps:
            self._print("眼镜预览 %.1f fps（会占用 LCD 接口，和 HUD 冲突）" % cfg.mirror_fps)

    # -- 主循环 ---------------------------------------------------------
    def run(self) -> StreamStats:
        cfg = self.config
        self._print_banner()
        started = self.stats.started
        last_report = time.monotonic()
        last_captured = 0
        last_sent = 0
        source_backoff = 1.0
        encoder_backoff = 1.0
        encoder_retry_at = 0.0
        try:
            if cfg.mjpeg_port:
                self._mjpeg = MjpegOutput(cfg)
                self._mjpeg.start()
                self._print("MJPEG 服务已启动（:%d）" % cfg.mjpeg_port)
            if cfg.api_url and cfg.rtsp_url:
                path = (urllib.parse.urlparse(cfg.rtsp_url).path or "/glxss").strip("/")
                self._api = MediamtxStats(cfg.api_url, path or "glxss",
                                          interval=cfg.stats_interval,
                                          verbose=cfg.verbose)
                self._api.start()

            slow_warned = False
            while not self._stop.is_set():
                now = time.monotonic()
                if cfg.duration and now - started >= cfg.duration:
                    break
                if cfg.max_frames and self.stats.captured >= cfg.max_frames:
                    break

                # 1) 采集源：没有就（重）打开
                if self._source is None:
                    try:
                        source = open_source(cfg)
                        probe = source.open()
                        self._source = source
                        source_backoff = 1.0
                        if probe is not None:
                            self._print("UVC 已打开：%s" % probe.format().replace(
                                "\n", " "))
                        if cfg.mirror_fps and hasattr(source, "create_lcd_sink"):
                            try:
                                self._lcd = source.create_lcd_sink(cfg, self.hub)
                                if self._lcd is not None:
                                    self._lcd.start()
                                    self.stats.lcd = True
                            except Exception as exc:
                                self._lcd = None
                                self.stats.lcd = False
                                self._print("眼镜预览启动失败（跳过）：%s" % exc)
                    except GlxssError as exc:
                        self.stats.source_restarts += 1
                        self.stats.last_error = str(exc)
                        self._print("打开采集源失败：%s（%.0f s 后重试）" % (
                            str(exc).splitlines()[0], source_backoff))
                        self._stop.wait(source_backoff)
                        source_backoff = min(source_backoff * 2, 15.0)
                        continue

                # 2) 编码器：没有就启动；死了就按退避时间重启。
                #    等待期间继续读 UVC（帧直接丢），避免 USB 端缓冲溢出。
                if cfg.rtsp_url and self._pipe is None and now >= encoder_retry_at:
                    try:
                        self._pipe = EncoderSink(cfg, self.hub)
                        self._pipe.start()
                        self._pipe_frames = 0
                        self._pipe_dropped = 0
                        encoder_backoff = 1.0
                        encoder_retry_at = 0.0
                        self._print("ffmpeg 已启动（pid=%d）" % (
                            self._pipe.encoder.proc.pid if self._pipe.encoder.proc else -1))
                    except GlxssError:
                        self._close_source()
                        raise
                if cfg.rtsp_url and self._pipe is not None and not self._pipe.alive():
                    tail = (self._pipe.fatal or self._pipe.encoder.error_tail())
                    self.stats.encoder_restarts += 1
                    self.stats.last_error = tail
                    self._print("编码器退出（%s），%.0f s 后重启" % (tail, encoder_backoff))
                    self._pipe.stop()
                    self._pipe = None
                    encoder_retry_at = time.monotonic() + encoder_backoff
                    encoder_backoff = min(encoder_backoff * 2, 15.0)
                    continue

                # 3) 读一帧 —— 读线程唯一的活，读完立刻发布最新帧。
                try:
                    frame = self._source.read()
                except GlxssError as exc:
                    self.stats.last_error = str(exc)
                    self._print("UVC 读帧失败：%s（重连）" % str(exc).splitlines()[0])
                    self._close_source()
                    if self._pipe is not None:
                        self._pipe.stop()
                        self._pipe = None
                    continue

                self.stats.captured += 1
                self.stats.bytes_in += len(frame.data)

                # 4) 发布：不做任何转换/IO，编码线程和 LCD 线程各取最新帧。
                self.hub.publish(frame)
                if self._mjpeg is not None:
                    self._mjpeg.submit(frame)

                # 5) 统计
                now = time.monotonic()
                if now - last_report >= cfg.stats_interval:
                    dt = max(1e-6, now - last_report)
                    self.stats.capture_fps = (self.stats.captured - last_captured) / dt
                    # 编码线程的计数在 pipe 重启后归零，这里按增量累加。
                    if self._pipe is not None:
                        self.stats.sent += self._pipe.frames - self._pipe_frames
                        self.stats.dropped += self._pipe.dropped - self._pipe_dropped
                        self._pipe_frames = self._pipe.frames
                        self._pipe_dropped = self._pipe.dropped
                    self.stats.send_fps = (self.stats.sent - last_sent) / dt
                    if self._api is not None:
                        self.stats.bitrate_kbps = self._api.kbps
                        self.stats.viewers = self._api.viewers
                    if self._mjpeg is not None:
                        self.stats.mjpeg_fps = self._mjpeg.frames_sent / self.stats.elapsed
                    if self._lcd is not None:
                        lcd_stats = self._lcd.stats_dict()
                        self.stats.lcd = True
                        self.stats.lcd_fps = lcd_stats["fps"]
                        self.stats.lcd_ms = lcd_stats["render_ms"] + lcd_stats["send_ms"]
                        self.stats.lcd_frames = lcd_stats["frames"]
                        self.stats.lcd_dropped = lcd_stats["dropped"]
                        if lcd_stats["last_error"]:
                            self.stats.last_error = lcd_stats["last_error"]
                    line = self.stats.line()
                    if cfg.rtsp_url and not (self._api and self._api.online):
                        line += "（目标 %s）" % cfg.bitrate
                    self._print(line)
                    if self.on_stats is not None:
                        self.on_stats(replace(self.stats))
                    last_report = now
                    last_captured = self.stats.captured
                    last_sent = self.stats.sent
                    # 相机比目标慢时 ffmpeg 仍按 --fps 打时间戳，画面会略慢；
                    # 提醒一次，建议把 --fps 降到实测值。
                    if (not slow_warned and cfg.fps and cfg.rtsp_url
                            and self.stats.elapsed > 10.0
                            and 0 < self.stats.capture_fps < cfg.fps * 0.9):
                        slow_warned = True
                        self._print(
                            "采集只有 %.1f fps（目标 %.0f），画面会略慢；"
                            "可把 --fps 改成 %d" % (
                                self.stats.capture_fps, cfg.fps,
                                max(5, int(self.stats.capture_fps))))
        except KeyboardInterrupt:
            self._print("收到 Ctrl-C，停止直播")
        finally:
            self._close_all()
        return self.stats

    # -- 清理 -----------------------------------------------------------
    def _close_source(self) -> None:
        if self._lcd is not None:
            self._lcd.stop()
            self._lcd = None
            self.stats.lcd = False
        if self._source is not None:
            try:
                self._source.close()
            except Exception:
                pass
        self._source = None

    def _close_all(self) -> None:
        self.hub.close()
        if self._pipe is not None:
            self._pipe.stop()
            self._pipe = None
        self._close_source()
        if self._mjpeg is not None:
            self._mjpeg.stop()
            self._mjpeg = None
        if self._api is not None:
            self._api.stop()
            self._api = None


def run_stream(config: StreamConfig, on_stats: StatsCallback = None) -> StreamStats:
    """便捷入口：跑一路直播直到 Ctrl-C / duration / frames。"""
    return Streamer(config, on_stats=on_stats).run()


def describe(config: StreamConfig) -> str:
    return "%dx%d@%.1ffps %s %s → %s" % (
        config.width, config.height, config.fps, config.codec, config.bitrate,
        config.rtsp_url or ("MJPEG :%d" % config.mjpeg_port))
