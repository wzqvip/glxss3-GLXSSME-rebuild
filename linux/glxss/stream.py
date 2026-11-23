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
      方便对准。**长按 Fn ≥0.9 s** 在同一 LCD 链路上切「相机预览 ↔ HUD」
      （时间/罗盘/水平仪），不再依赖外部 `glxss hud` 进程，也不会释放 LCD 接口；
      **短按 Fn**（预览下）循环数字变焦 1.0x/1.5x/2.0x（源画面居中裁切），
      HUD 下短按忽略。RTSP 开关改用 `glxss ctl rtsp on|off`，不再绑按键。

设计要点：

    * 读线程只做「读 + 发布」，**绝不做解码/编码/发送**，任何消费者慢了都不会
      拖慢采集（这是 `--mirror-fps 10` 时采集掉到 4.6 fps 的根因修复）；
    * 编码线程按 --fps 用令牌桶节流，取最新帧，跟不上就丢旧帧，延迟不累积；
    * LCD 线程按 --mirror-fps 限速，渲染 + 发送都在自己线程里；
    * 断线自动重连：UVC 掉线重开设备，ffmpeg 退出自动重启；
    * 统计：采集/编码/眼镜三路 fps、丢弃、YUV 入带宽、mediamtx API 实测码率、观众数。

同一时间只有一个进程能占用 UVC 接口：`glxss uvc` / 监看模式 / 本模块 三者
互斥。眼镜屏由本模块的 LCD 线程负责：`preview` 放相机画面，`hud` 放板载
HUD（不再需要同时跑 `glxss hud` / `glxss-cast.service`）。
"""

from __future__ import annotations

import json
import math
import queue
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

from . import control, hid, hud, lcd, nav, pipeline, usb, uvc
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
    # 异步 URB：0=同步 16 KiB 读（旧行为）；>0=用 libusb1 保持 N 个 URB 在途。
    # Pi Zero 2 W 实测：纯读 2 个就够 40 MB/s；但和 HID/ffmpeg 并发时 8 个
    # 只有 ~28fps，16 个稳定 29.9–30.7fps（见 uvc_async.py）。
    async_urb: int = 16
    async_timeout: int = 1000
    # HID / Fn 按键
    fn_keys: bool = True
    hid_poll: float = 0.1
    fn_long_press: float = 0.9
    fn_long_packets: int = 4
    # 控制 socket（""=不启动；glxss ctl 用默认路径连）
    ctl_socket: str = ""
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
    # Fn 长按打开预览时用的帧率；0=mirror_fps 或 10
    preview_fps: float = 0.0
    # HUD 模式（长按 Fn 切到眼镜 HUD）的重绘/发送帧率
    hud_fps: float = 2.0
    # 导航 HUD：手机 Wi-Fi UDP JSON → nav.LatestNav（HUD 读该单槽）。
    # 默认绑定 0.0.0.0:9999；--no-nav-udp 可关，`ctl nav` 仍可用。
    nav_udp: bool = True
    nav_bind: str = "0.0.0.0"
    nav_port: int = 9999
    nav_stale: float = 5.0
    mirror_fit: str = "fit"
    mirror_rotate: int = 0
    mirror_level: bool = True
    # 数字变焦（1.0/1.5/2.0）：只裁**眼镜预览**的源画面，RTSP 仍推全画幅。
    # 运行中短按 Fn（preview 下）或 `glxss ctl zoom` 切换；进程内不落盘，
    # 重启回到这里给的 --zoom（默认 1.0）——避免上次放大后忘了，误以为
    # 镜头/对焦坏了。要固定就写进 systemd 的 --zoom 参数。
    zoom: float = 1.0
    # 水平仪方向覆盖：None=跟随 rotate（180 下默认两者都取反）；
    # True/False 强制取反/不取反。x=roll（左右倾），y=pitch（上下）。
    level_invert_x: Optional[bool] = None
    level_invert_y: Optional[bool] = None
    # 发送 LCD 整帧时暂停 UVC 异步读；关掉可用于复现“抢带宽 → 撕裂窗口变大”
    lcd_pause: bool = True
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
        if self.preview_fps < 0:
            raise GlxssError("--preview-fps 不能为负")
        if self.hud_fps <= 0:
            raise GlxssError("--hud-fps 必须 > 0")
        if not 0 <= int(self.nav_port) <= 65535:
            raise GlxssError("--nav-port 必须在 0..65535（0=不监听）")
        if self.nav_stale <= 0:
            raise GlxssError("--nav-stale 必须 > 0 秒")
        if self.mirror_fit not in ("fit", "cover", "crop", "stretch"):
            raise GlxssError("--mirror-fit 只能是 fit/cover/stretch")
        if self.mirror_rotate not in (0, 180):
            raise GlxssError("--mirror-rotate 只能是 0 或 180")
        if self.zoom not in pipeline.ZOOM_LEVELS:
            raise GlxssError("--zoom 只能是 1.0 / 1.5 / 2.0")
        if self.frame_index is not None and not 1 <= self.frame_index <= 255:
            raise GlxssError("--frame-index 必须在 1..255")
        if self.format_index is not None and not 1 <= self.format_index <= 255:
            raise GlxssError("--format-index 必须在 1..255")
        if not 0 <= self.async_urb <= 32:
            raise GlxssError("--async-urb 必须在 0..32（0=同步读，建议 8）")
        if self.hid_poll <= 0:
            raise GlxssError("--hid-poll 必须 > 0")
        if self.fn_long_press < 0.3:
            raise GlxssError("--fn-long-press 不能小于 0.3 秒")
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
        ctl_socket = ""
        if not getattr(args, "no_ctl", False):
            ctl_socket = (getattr(args, "ctl_socket", None)
                          or control.default_socket_path())
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
            async_urb=int(getattr(args, "async_urb", 16)),
            async_timeout=int(getattr(args, "async_timeout", 1000)),
            fn_keys=bool(getattr(args, "fn_keys", True)),
            hid_poll=float(getattr(args, "hid_poll", 0.1)),
            fn_long_press=float(getattr(args, "fn_long_press", 0.9)),
            fn_long_packets=int(getattr(args, "fn_long_packets", 4)),
            ctl_socket=ctl_socket,
            ffmpeg=str(getattr(args, "ffmpeg", "ffmpeg")),
            mjpeg_port=int(getattr(args, "mjpeg_port", 0) or 0),
            mjpeg_fps=float(getattr(args, "mjpeg_fps", 10.0)),
            mjpeg_quality=int(getattr(args, "mjpeg_quality", 70)),
            mirror_fps=float(getattr(args, "mirror_fps", 0.0) or 0.0),
            preview_fps=float(getattr(args, "preview_fps", 0.0) or 0.0),
            hud_fps=float(getattr(args, "hud_fps", 2.0) or 2.0),
            nav_udp=not bool(getattr(args, "no_nav_udp", False)),
            nav_bind=str(getattr(args, "nav_bind", "0.0.0.0") or "0.0.0.0"),
            nav_port=int(getattr(args, "nav_port", 9999) or 0),
            nav_stale=float(getattr(args, "nav_stale", 5.0) or 5.0),
            mirror_fit=str(getattr(args, "mirror_fit", "fit")),
            mirror_rotate=int(getattr(args, "mirror_rotate", 0) or 0),
            mirror_level=bool(getattr(args, "mirror_level", True)),
            zoom=float(getattr(args, "zoom", 1.0) or 1.0),
            level_invert_x=getattr(args, "level_invert_x", None),
            level_invert_y=getattr(args, "level_invert_y", None),
            lcd_pause=not bool(getattr(args, "no_lcd_pause", False)),
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
    """pyusb 读 GLXSS UVC（可选异步多 URB）；需要 detach uvcvideo（Linux）。

    异步读用独立的 libusb handle（`uvc_async.AsyncBulkReader`）claim VS，
    pyusb handle 继续 claim VC/HID（以及按需 LCD）。probe/commit 仍走 pyusb，
    所以协商/组帧逻辑与同步模式完全一致；异步不可用时自动回退。
    """

    name = "uvc"

    def __init__(self, config: StreamConfig):
        self.config = config
        self.dev = None
        self.session: Optional[usb.DeviceSession] = None
        self.stream: Optional[uvc.UvcStream] = None
        self.async_reader = None
        self.probe = None
        self.gate = usb.UsbGate(verbose=config.verbose)

    def open(self):
        self.dev = usb.find_normal()
        self.session = usb.DeviceSession(
            self.dev, detach=self.config.detach, verbose=self.config.verbose)
        reader = None
        if self.config.async_urb > 0:
            try:
                from .uvc_async import AsyncBulkReader
                # 固件要求“先 claim HID 再 claim VS”：异步 reader 只 claim VS，
                # 所以这里先用 pyusb claim HID。
                self.session.claim(usb.IFACE_HID, label="HID")
                reader = AsyncBulkReader(
                    urbs=self.config.async_urb,
                    timeout=self.config.async_timeout,
                    verbose=self.config.verbose)
                reader.open()
                self.async_reader = reader
                if self.config.verbose:
                    print("[stream] 异步 URB 已 claim VS：%d × 16 KiB（probe/commit 后再 start）"
                          % self.config.async_urb)
            except Exception as exc:
                if reader is not None:
                    try:
                        reader.close()
                    except Exception:
                        pass
                reader = None
                self.async_reader = None
                print("[stream] 异步 URB 不可用（%s），回退同步 16 KiB 读"
                      % (str(exc).splitlines()[0] if str(exc) else exc))
        try:
            self.stream = uvc.UvcStream.open(
                self.dev, self.session, timeout=self.config.timeout_ms,
                verbose=self.config.verbose, async_reader=reader)
            self.stream.set_dimensions(self.config.width, self.config.height)
            self.probe = self.stream.negotiate(
                format_index=self.config.format_index,
                frame_index=self.config.frame_index,
                fps=self.config.fps or None,
                max_frame=self.config.frame_size,
            )
            if reader is not None:
                # commit 之后才开异步事件线程：probe/commit 的 VS 控制请求
                # 与 libusb 事件循环同 handle 并发没有好处，串行更稳。
                reader.start()
                if self.config.verbose:
                    print("[stream] 异步 URB 已启动：%d × 16 KiB"
                          % self.config.async_urb)
        except Exception:
            self.close()
            raise
        return self.probe

    def read(self) -> StreamFrame:
        if self.stream is None:
            raise GlxssError("UVC 未打开")
        frame = self.stream.read_frame(timeout=self.config.timeout_ms)
        return StreamFrame(frame.index, frame.data, frame.width, frame.height,
                           frame.timestamp)

    def close(self) -> None:
        if self.stream is not None:
            try:
                self.stream.close()
            except Exception:
                pass
        self.stream = None
        reader, self.async_reader = self.async_reader, None
        if reader is not None:
            try:
                reader.close()
            except Exception:
                pass
        if self.session is not None:
            try:
                self.session.close()
            except Exception:
                pass
        self.session = None
        self.dev = None

    # 异步读暂停/恢复（LCD 整帧发送用） ----------------------------------
    def pause_reading(self) -> None:
        if self.async_reader is not None:
            self.async_reader.pause()

    def resume_reading(self) -> None:
        if self.async_reader is not None:
            self.async_reader.resume()

    def async_stats(self) -> dict:
        return self.async_reader.stats_dict() if self.async_reader else {}

    # 眼镜低帧率预览 -----------------------------------------------------
    def create_lcd_sink(self, config: StreamConfig, hub: "pipeline.LatestFrame",
                        level_poller=None, hud_source=None,
                        hud_fps: float = 2.0) -> Optional["pipeline.LcdSink"]:
        """claim LCD 接口 + 建 LCD 线程（共享同一份帧，不再单独读 UVC）。"""
        if config.mirror_fps <= 0:
            return None
        if self.dev is None or self.session is None:
            raise GlxssError("设备未打开，无法投眼镜")
        pause = config.lcd_pause
        return pipeline.open_lcd_sink(
            self.dev, self.session, config, hub, gate=self.gate,
            level_poller=level_poller, hud_source=hud_source,
            hud_fps=hud_fps,
            pause_reader=self.pause_reading if pause else None,
            resume_reader=self.resume_reading if pause else None)


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

    def create_lcd_sink(self, config: StreamConfig, hub, **_kwargs) -> None:
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
    lcd_render_ms: float = 0.0
    lcd_send_ms: float = 0.0
    lcd_frames: int = 0
    lcd_dropped: int = 0
    # Fn 按键 / 显示模式 / 推流开关 / 异步 URB
    display: str = "hud"          # hud=不占 LCD；preview=眼镜预览
    zoom: float = 1.0             # 数字变焦档位（只影响眼镜预览）
    rtsp: bool = False
    async_urbs: int = 0
    async_pauses: int = 0
    buttons: int = 0
    last_button: str = ""
    last_error: str = ""

    @property
    def elapsed(self) -> float:
        return max(1e-6, time.monotonic() - self.started) if self.started else 0.0

    def line(self) -> str:
        mjpeg = (" MJPEG=%.1ffps" % self.mjpeg_fps) if self.mjpeg_fps else ""
        lcd = (" 眼镜=%.1ffps/渲染%.1f+发送%.1fms" % (
            self.lcd_fps, self.lcd_render_ms, self.lcd_send_ms)
            if self.lcd else "")
        async_text = (" 异步=%dURB/暂停%d" % (self.async_urbs, self.async_pauses)
                      if self.async_urbs else "")
        key_text = (" 按键=%s" % self.last_button) if self.last_button else ""
        return (
            "帧=%d 编码=%d 丢弃=%d 采集=%.1ffps 编码=%.1ffps YUV=%.2fMB/s "
            "实测=%dkbps 观众=%d 重启=%d/%d%s%s 显示=%s 变焦=%.1fx%s 推流=%s%s 运行=%s" % (
                self.captured, self.sent, self.dropped, self.capture_fps,
                self.send_fps, self.bytes_in / self.elapsed / 1e6,
                int(self.bitrate_kbps), self.viewers,
                self.encoder_restarts, self.source_restarts, mjpeg, lcd,
                self.display, self.zoom, async_text,
                "ON" if self.rtsp else "OFF", key_text,
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
        self._hid: Optional[pipeline.LevelPoller] = None
        self._hud: Optional[hud.HudSource] = None
        # 导航数据单槽：手机 UDP 收包线程写，HUD/ctl/status 读。
        self._nav = nav.LatestNav(stale=config.nav_stale)
        self._nav_listener: Optional[nav.NavListener] = None
        self._ctl: Optional[control.ControlServer] = None
        self._booted = False
        # 显示模式：hud（不占 LCD，留给 HUD 进程）/ preview（本进程投相机画面）
        self._display_mode = "hud"
        # 数字变焦：只影响 LcdRenderer（眼镜预览），RTSP 编码始终是全画幅。
        self._zoom = float(config.zoom)
        self.stats.zoom = self._zoom
        self._preview_fps = max(1.0, float(
            config.preview_fps or config.mirror_fps or 10.0))
        self._rtsp_enabled = bool(config.rtsp_url)
        self._mode_lock = threading.Lock()
        self._button_lock = threading.Lock()
        self._last_button_kind = ""
        # Fn 长按（切显示模式）可能阻塞 1–2 s，绝不能放在 UVC 读线程；
        # 主循环只把动作塞进队列，由独立 worker 执行。短按 zoom 只改一个
        # float，原地执行。
        self._actions: "queue.Queue[str]" = queue.Queue()
        self._action_thread: Optional[threading.Thread] = None
        # 编码器线程的累计计数（pipe 重启后归零，靠这里换算成总量）
        self._pipe_frames = 0
        self._pipe_dropped = 0

    def stop(self) -> None:
        self._stop.set()

    # -- 打印 -----------------------------------------------------------
    def _print(self, message: str) -> None:
        print("[stream] " + message, flush=True)

    # -- HID / Fn 按键 --------------------------------------------------
    def _ensure_hud_source(self) -> Optional[hud.HudSource]:
        """建 HUD 渲染源（时间/罗盘/姿态/lux）；长按 Fn 切 HUD 时用它。

        HUD 不再依赖外部 `glxss hud` 进程：stream 自己占着 LCD 接口，切模式
        只是换 `LcdSink` 的填充源，不会出现“释放接口后没人画 → 画面冻结”。
        """
        if self._hud is not None:
            return self._hud
        cfg = self.config
        rotate180 = (cfg.mirror_rotate == 180)
        # HUD 的整张图在 render_hud() 里翻转 180°，面板再翻 180° → 用户看到的
        # 是**未旋转**的 HUD 图。所以姿态小地平线只需补偿 roll（左右倾）；
        # pitch（上下）在这个坐标系里本来就是对的，默认不取反。
        hud_invert_x = (cfg.level_invert_x if cfg.level_invert_x is not None
                        else rotate180)
        hud_invert_y = (cfg.level_invert_y if cfg.level_invert_y is not None
                        else False)
        try:
            source = hud.HudSource(
                brightness=180,
                render_interval=max(0.1, 1.0 / max(0.5, float(cfg.hud_fps))),
                horizon_invert_x=bool(hud_invert_x),
                horizon_invert_y=bool(hud_invert_y),
                nav_source=self._nav)
        except Exception as exc:
            self._print("HUD 渲染源不可用（Fn 只能切预览/黑屏）：%s" % (
                str(exc).splitlines()[0] if str(exc) else exc))
            return None
        source.set_connected(True)
        source.set_fps(cfg.hud_fps)
        self._hud = source
        # 固件版本只读一次，给 HUD 底栏用；失败不影响运行。
        dev = getattr(self._source, "dev", None)
        if dev is not None:
            try:
                timeout = min(1500, max(200, cfg.timeout_ms))
                ctx = getattr(self._source, "gate", None)
                if ctx is not None:
                    with ctx:
                        version = hid.read_version(dev, timeout)
                else:
                    version = hid.read_version(dev, timeout)
                source.set_firmware(version.version)
            except Exception as exc:
                if cfg.verbose:
                    self._print("读取固件版本失败（HUD 底栏留空）：%s" % (
                        str(exc).splitlines()[0] if str(exc) else exc))
        return source

    def _start_hid_poller(self) -> bool:
        if self._hid is not None:
            return True
        source = self._source
        if source is None or getattr(source, "dev", None) is None:
            return False
        cfg = self.config
        if not cfg.fn_keys and not cfg.mirror_level:
            return False
        self._ensure_hud_source()
        watcher = None
        if cfg.fn_keys:
            watcher = hid.FnWatcher(long_seconds=cfg.fn_long_press,
                                    long_packets=cfg.fn_long_packets)
        poller = pipeline.LevelPoller(
            source.dev, interval=cfg.hid_poll,
            timeout=min(1500, max(200, cfg.timeout_ms)),
            gate=getattr(source, "gate", None), verbose=cfg.verbose,
            watcher=watcher, enable_accel=bool(cfg.mirror_level),
            on_report=(self._hud.update_report if self._hud is not None else None))
        poller.start()
        self._hid = poller
        self._print("HID 轮询已启动：%.0f ms，Fn %s，长按 ≥%.1f s（只看时间）" % (
            cfg.hid_poll * 1000.0, "ON" if cfg.fn_keys else "OFF",
            cfg.fn_long_press))
        return True

    def _stop_hid_poller(self) -> None:
        poller, self._hid = self._hid, None
        if poller is not None:
            poller.stop()

    # -- 数字变焦（只动眼镜预览，不动 RTSP） ------------------------------
    def _view_name(self) -> str:
        """当前实际投屏视图：LCD 线程的 mode；没占 LCD 时视为 hud。"""
        lcd_sink = self._lcd
        return lcd_sink.mode if lcd_sink is not None else "hud"

    def _zoom_crop(self) -> str:
        """当前 zoom 在**源画面**上的居中裁切尺寸，例如 ``853x480``。"""
        crop_w, crop_h = pipeline.zoom_box(
            self.config.width, self.config.height, self._zoom)
        return "%dx%d" % (crop_w, crop_h)

    def _set_zoom(self, value) -> float:
        """设置/循环数字变焦，返回新档位；不改 LCD 线程、不重启采集。

        ``value`` 可以是 1.0/1.5/2.0，也可以是 ``cycle``（1.0→1.5→2.0→1.0）。
        状态同时写进 ``stats.zoom`` 和 ``config.zoom``，这样 LCD 线程重建时
        （USB 重连）仍从当前档位开始，不会闪回 1.0x。
        """
        text = str(value).strip().lower()
        if text in ("cycle", "next"):
            try:
                index = pipeline.ZOOM_LEVELS.index(float(self._zoom))
            except ValueError:
                index = 0
            zoom = pipeline.ZOOM_LEVELS[
                (index + 1) % len(pipeline.ZOOM_LEVELS)]
        else:
            try:
                zoom = float(text)
            except ValueError as exc:
                raise GlxssError(
                    "zoom 需要 1.0 / 1.5 / 2.0 / cycle，收到 %r" % (value,)) from exc
            if zoom not in pipeline.ZOOM_LEVELS:
                raise GlxssError(
                    "zoom 需要 1.0 / 1.5 / 2.0 / cycle，收到 %r" % (value,))
        self._zoom = zoom
        self.stats.zoom = zoom
        self.config.zoom = zoom
        lcd_sink = self._lcd
        if lcd_sink is not None:
            try:
                lcd_sink.set_zoom(zoom)
            except AttributeError:
                pass
        return zoom

    def _drain_buttons(self) -> None:
        """Fn 动作映射（只在主循环里取事件，绝不在读线程里碰 USB）：

        * 单击（preview）→ 循环 zoom 1.0 → 1.5 → 2.0 → 1.0；
        * 单击（HUD）    → 忽略（切回视频要长按，避免 HUD 下误触放大）；
        * 长按 ≥0.9 s    → display toggle（视频 ↔ HUD）。
        RTSP 开关不再绑按键，用 `glxss ctl rtsp on|off`。
        """
        poller = self._hid
        if poller is None:
            return
        for event in poller.drain_events():
            with self._button_lock:
                self.stats.buttons += 1
                self.stats.last_button = event.kind
                self._last_button_kind = event.kind
            if event.kind == "click":
                if self._view_name() == "preview":
                    zoom = self._set_zoom("cycle")
                    self._print(
                        "Fn 按键：%s → zoom %.1fx（源中心 %s；居中裁切后缩放）"
                        % (event.format(), zoom, self._zoom_crop()))
                else:
                    self._print(
                        "Fn 按键：%s → 忽略（当前 HUD 视图，短按不变焦；"
                        "长按 Fn 切回视频）" % event.format())
            elif event.kind == "long":
                self._print("Fn 按键：%s → display toggle（当前 %s）"
                            % (event.format(), self._view_name()))
                self._actions.put("display")

    def _start_action_worker(self) -> None:
        if self._action_thread is not None:
            return
        self._action_thread = threading.Thread(
            target=self._action_loop, name="fn-action", daemon=True)
        self._action_thread.start()

    def _action_loop(self) -> None:
        while not self._stop.is_set():
            try:
                action = self._actions.get(timeout=0.2)
            except queue.Empty:
                continue
            try:
                if action == "display":
                    self._toggle_display()
            except Exception as exc:
                self._print("Fn 动作 %s 失败：%s" % (
                    action, str(exc).splitlines()[0] if str(exc) else exc))

    def _stop_action_worker(self) -> None:
        thread, self._action_thread = self._action_thread, None
        if thread is not None and thread.is_alive():
            thread.join(timeout=1.0)

    # -- 显示模式（HUD ↔ 相机预览） -------------------------------------
    def _start_preview(self) -> bool:
        with self._mode_lock:
            lcd_sink = self._lcd
            if lcd_sink is not None and not lcd_sink.alive:
                self._print("LCD 线程已退出，重建后再切 preview")
                try:
                    lcd_sink.release()
                except Exception:
                    pass
                self._lcd = None
                lcd_sink = None
            if lcd_sink is not None:
                try:
                    lcd_sink.set_mode("preview")
                except Exception as exc:
                    self._print("切回 camera preview 失败：%s" % (
                        str(exc).splitlines()[0] if str(exc) else exc))
                    return False
                self.stats.lcd = True
                self.stats.display = "preview"
                self._display_mode = "preview"
                self._print("显示模式 -> camera preview（眼镜预览 %.1f fps）"
                            % self._preview_fps)
                return True
            source = self._source
            if source is None or not hasattr(source, "create_lcd_sink"):
                self._print("眼镜预览启动失败：采集源未打开")
                return False
            self._ensure_hud_source()
            self._start_hid_poller()   # 水平仪 / Fn 轮询在预览打开前就绪
            cfg = self.config
            old_fps = cfg.mirror_fps
            cfg.mirror_fps = self._preview_fps
            try:
                lcd_sink = source.create_lcd_sink(
                    cfg, self.hub, level_poller=self._hid,
                    hud_source=self._hud, hud_fps=cfg.hud_fps)
                if lcd_sink is None:
                    self._print("眼镜预览启动失败：当前源不支持投屏")
                    return False
                try:
                    # 重建 sink（USB 重连）时沿用当前档位；config.zoom 也一直
                    # 同步着，所以 LcdRenderer 初始化就已经是正确档位。
                    lcd_sink.set_zoom(self._zoom)
                except AttributeError:
                    pass
                lcd_sink.set_mode("preview")
                lcd_sink.start()
                self._lcd = lcd_sink
                self.stats.lcd = True
                self.stats.display = "preview"
                self._display_mode = "preview"
                self._print("显示模式 -> camera preview（眼镜预览 %.1f fps，"
                            "变焦 %.1fx；长按 Fn 切 HUD，短按循环变焦）"
                            % (self._preview_fps, self._zoom))
                return True
            except Exception as exc:
                self._lcd = None
                self.stats.lcd = False
                self.stats.display = "hud"
                self._display_mode = "hud"
                self._print("眼镜预览启动失败：%s" % (
                    str(exc).splitlines()[0] if str(exc) else exc))
                return False
            finally:
                cfg.mirror_fps = old_fps

    def _stop_preview(self) -> bool:
        """切到 HUD：只换 LcdSink 模式，**不 release LCD 接口**。

        旧实现停线程 + release 接口，等一个并不存在的 HUD 进程接管；面板上
        一直留着最后一帧（用户看到的“冻结”）。现在同一个 LcdSink 用
        ``hud_source`` 直接画 HUD，再长按 Fn 切回 preview。
        """
        with self._mode_lock:
            lcd_sink = self._lcd
            if lcd_sink is None:
                self.stats.lcd = False
                self.stats.display = "hud"
                self._display_mode = "hud"
                self._print("显示模式 -> HUD（当前没有占用 LCD）")
                return True
            try:
                lcd_sink.set_mode("hud")
            except Exception as exc:
                self._print("切 HUD 失败：%s" % (
                    str(exc).splitlines()[0] if str(exc) else exc))
                return False
            self.stats.lcd = True       # 仍以 2 fps 往眼镜发 HUD 帧
            self.stats.display = "hud"
            self._display_mode = "hud"
            self._print("显示模式 -> HUD（时间/罗盘/水平仪；LCD 保持占用；"
                        "长按 Fn 回视频，HUD 下短按 Fn 忽略）")
            return True

    def _release_preview(self) -> None:
        """停 LCD 线程并释放接口（只在关采集源/退出时用）。

        保留 ``_display_mode``（用户上次选的 preview/hud），重连后据此恢复。
        """
        with self._mode_lock:
            lcd_sink, self._lcd = self._lcd, None
            if lcd_sink is not None:
                try:
                    lcd_sink.release()
                except Exception as exc:
                    self._print("释放 LCD 接口失败：%s" % (
                        str(exc).splitlines()[0] if str(exc) else exc))
            self.stats.lcd = False
            self.stats.display = "hud"

    def _set_display(self, preview: bool) -> bool:
        return self._start_preview() if preview else self._stop_preview()

    def _toggle_display(self) -> bool:
        # 以 _lcd 是否存在 + 当前模式为准；有 sink 时只切模式，绝不 release。
        if self._lcd is not None:
            return self._set_display(self._lcd.mode != "preview")
        return self._start_preview()

    # -- RTSP 推流开关（glxss ctl rtsp on|off，不再绑 Fn） ----------------
    def _set_rtsp(self, enabled: bool) -> bool:
        with self._mode_lock:
            if not self.config.rtsp_url:
                self._print("RTSP 忽略：当前没有配置推流地址")
                return False
            enabled = bool(enabled)
            if enabled == self._rtsp_enabled:
                return True
            self._rtsp_enabled = enabled
            self.stats.rtsp = enabled
            if not enabled:
                # 真正的 ffmpeg.stop() 可能阻塞 3 s，不能放在控制 socket 线程里；
                # 主循环看到 _rtsp_enabled=False 会停止它。
                self._print("RTSP 推流停止请求（主循环即将停 ffmpeg；"
                            "`glxss ctl rtsp on` 恢复）")
            else:
                self._print("RTSP 推流已恢复（下一轮启动 ffmpeg）")
            return True

    def _toggle_rtsp(self) -> bool:
        return self._set_rtsp(not self._rtsp_enabled)

    # -- 控制 socket -----------------------------------------------------
    def _status_dict(self) -> dict:
        stats = self.stats
        source = self._source
        async_info = {}
        if source is not None and hasattr(source, "async_stats"):
            try:
                async_info = source.async_stats() or {}
            except Exception:
                async_info = {}
        invert_x = self.config.level_invert_x
        if invert_x is None:
            invert_x = (self.config.mirror_rotate == 180)
        invert_y = self.config.level_invert_y
        if invert_y is None:
            invert_y = (self.config.mirror_rotate == 180)
        level = None
        hid_info = {"alive": False, "error": "", "poll_ms": 0.0,
                    "key_down": False, "key_code": None, "key_status": None}
        if self._hid is not None:
            angles = self._hid.level_snapshot()
            if angles is not None:
                level = {"pitch": round(float(angles[0]), 1),
                         "roll": round(float(angles[1]), 1)}
            report = self._hid.last_report
            hid_info = {
                "alive": bool(self._hid.alive),
                "error": self._hid.error,
                "poll_ms": round(self.config.hid_poll * 1000.0, 1),
                "key_down": bool(self._hid.key_down),
                "key_code": (int(report.key_code) if report is not None else None),
                "key_status": (int(report.key_status) if report is not None else None),
            }
        return {
            "connected": source is not None,
            "fps": round(stats.capture_fps, 1),
            "encoder_fps": round(stats.send_fps, 1),
            "brightness": None,
            "view": stats.display,
            "display": stats.display,
            "zoom": self._zoom,
            "zoom_levels": list(pipeline.ZOOM_LEVELS),
            "zoom_crop": list(pipeline.zoom_box(
                self.config.width, self.config.height, self._zoom)),
            "lcd_mode": (self._lcd.mode if self._lcd is not None else "none"),
            "mode": 2 if stats.display == "preview" else 1,
            "screen": True,
            "hud": stats.display != "preview",
            "rec": False,
            "rtsp": bool(stats.rtsp and self.config.rtsp_url),
            "stream": bool(stats.rtsp and self.config.rtsp_url),
            "mirror_fps": self._preview_fps if stats.display == "preview" else 0.0,
            "hud_fps": self.config.hud_fps,
            "buttons": stats.buttons,
            "last_button": stats.last_button,
            "level": level,
            "level_invert": {"x": bool(invert_x), "y": bool(invert_y)},
            "hid": hid_info,
            "async": async_info,
            "monitor": {
                "read_fps": round(stats.capture_fps, 1),
                "read_ms": 0.0,
                "decode_ms": round(stats.lcd_ms, 1),
                "frames_stale": stats.lcd_dropped,
            },
            "time": time.strftime("%H:%M:%S"),
            "socket": self.config.ctl_socket,
            "nav": self._nav.status(),
            "nav_udp": (self._nav_listener.status()
                        if self._nav_listener is not None else None),
            "error": stats.last_error,
        }

    def _handle_ctl(self, request: dict) -> dict:
        cmd = str(request.get("cmd", "")).strip().lower()
        if cmd in ("", "status", "ping"):
            response = {"ok": True, "status": self._status_dict()}
            if cmd == "ping":
                response["pong"] = True
            return response
        if cmd in ("display", "view"):
            raw = str(request.get("value", "toggle")).strip().lower()
            if raw in ("", "toggle"):
                ok = self._toggle_display()
            elif raw in ("hud", "off", "0", "screen"):
                ok = self._set_display(False)
            elif raw in ("preview", "camera", "uvc", "on", "1", "monitor"):
                ok = self._set_display(True)
            else:
                return {"ok": False, "error": "display 需要 preview/hud/toggle"}
            return {"ok": ok, "status": self._status_dict()}
        if cmd in ("rtsp", "stream"):
            raw = str(request.get("value", "toggle")).strip().lower()
            if raw in ("", "toggle"):
                ok = self._toggle_rtsp()
            elif raw in ("on", "start", "1", "true"):
                ok = self._set_rtsp(True)
            elif raw in ("off", "stop", "0", "false"):
                ok = self._set_rtsp(False)
            else:
                return {"ok": False, "error": "rtsp 需要 on/off/toggle"}
            return {"ok": ok, "status": self._status_dict()}
        if cmd in ("button", "fn"):
            # 调试/自动化用：不碰硬件，直接把事件注入主循环队列。
            kind = str(request.get("value", "click")).strip().lower()
            if kind not in ("click", "long"):
                return {"ok": False, "error": "button 需要 click/long"}
            if self._hid is None:
                return {"ok": False, "error": "HID 轮询未启动"}
            self._hid.inject_event(hid.FnEvent(kind, time.monotonic(), 0.0, 0))
            return {"ok": True, "status": self._status_dict()}
        if cmd == "zoom":
            # 1.0 / 1.5 / 2.0 / cycle；只影响眼镜预览，RTSP 始终全画幅。
            raw = str(request.get("value", "cycle")).strip().lower()
            try:
                zoom = self._set_zoom(raw)
            except GlxssError as exc:
                return {"ok": False, "error": str(exc)}
            self._print("ctl zoom → %.1fx（源中心 %s）" % (zoom, self._zoom_crop()))
            return {"ok": True, "zoom": zoom, "status": self._status_dict()}
        if cmd == "nav":
            # 手动测试：写进与手机 UDP 相同的单槽，ttl=0 持久，直到
            # nav-clear 或下一个 UDP 包覆盖。方便无手机时验证 HUD。
            try:
                bearing = float(request.get("bearing", 0.0))
                distance = float(request.get("distance", 0.0))
                raw_heading = request.get("heading")
                heading = (None if raw_heading in (None, "")
                           else float(raw_heading))
            except (TypeError, ValueError):
                return {"ok": False,
                        "error": "nav 需要数字 bearing/distance/heading"}
            values = [bearing, distance] + (
                [] if heading is None else [heading])
            if not all(math.isfinite(value) for value in values):
                return {"ok": False,
                        "error": "nav 的 bearing/distance/heading 必须是有限数字"}
            frame = self._nav.update(
                bearing, distance, str(request.get("label", "")),
                heading, ttl=0.0, source="ctl")
            self._ensure_hud_source()
            self._print("ctl nav → bearing=%.0f distance=%.0f heading=%s label=%r"
                        % (frame.bearing, frame.distance,
                           "--" if frame.heading is None else "%.0f" % frame.heading,
                           frame.label))
            return {"ok": True, "nav": frame.as_dict(),
                    "status": self._status_dict()}
        if cmd == "nav-clear":
            self._nav.clear()
            return {"ok": True, "status": self._status_dict()}
        if cmd == "quit":
            self.stop()
            return {"ok": True, "message": "正在退出"}
        return {"ok": False,
                "error": "未知命令 %r（支持 status/display/zoom/rtsp/button/"
                         "nav/nav-clear/quit）" % cmd}

    def _start_ctl(self) -> None:
        path = (self.config.ctl_socket or "").strip()
        if not path:
            return
        try:
            server = control.ControlServer(path, self._handle_ctl)
            server.start()
            self._ctl = server
            self._print("控制 socket: %s（glxss ctl status）" % path)
        except Exception as exc:
            self._ctl = None
            self._print("控制 socket 启动失败（继续运行）：%s" % (
                str(exc).splitlines()[0] if str(exc) else exc))

    def _start_nav_udp(self) -> None:
        """启动手机导航 UDP 接收（失败只告警，HUD 仍可用 ctl nav）。"""
        cfg = self.config
        if not cfg.nav_udp or int(cfg.nav_port) <= 0:
            if cfg.verbose:
                self._print("导航 UDP 未启用（--no-nav-udp 或 --nav-port 0）")
            return
        if self._nav_listener is not None and self._nav_listener.is_alive():
            return
        listener = nav.NavListener(
            self._nav, host=cfg.nav_bind, port=int(cfg.nav_port),
            stale=cfg.nav_stale, verbose=cfg.verbose)
        listener.start()
        listener.wait_ready(1.0)
        self._nav_listener = listener
        if listener.error:
            self._print("导航 UDP 监听失败（ctl nav 仍可用）：%s" % listener.error)
        else:
            self._print("导航 HUD：UDP %s:%d 已监听（手机每秒 JSON；"
                        "手动测试用 glxss ctl nav）" % (
                            cfg.nav_bind, listener.bound_port or cfg.nav_port))

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
        if cfg.async_urb:
            self._print("UVC 读：异步 %d URB × 16 KiB（不可用时自动回退同步）"
                        % cfg.async_urb)
        else:
            self._print("UVC 读：同步 16 KiB（--async-urb 0）")
        if cfg.fn_keys:
            self._print("Fn 按键：长按 ≥%.1f s（只看时间）= 眼镜 video ↔ HUD；"
                        "短按（video 下）= 循环变焦 1.0/1.5/2.0x，HUD 下忽略"
                        % cfg.fn_long_press)
            self._print("RTSP 开关不再绑 Fn：用 `glxss ctl rtsp on|off`（变焦用 "
                        "`glxss ctl zoom 1.0|1.5|2.0|cycle`）")
        if cfg.nav_udp and int(cfg.nav_port) > 0:
            self._print("导航 HUD：监听 UDP %s:%d（手机每秒 JSON；无数据时 "
                        "HUD 显示“等待手机…”；手动测试 `glxss ctl nav 45 "
                        "--distance 120 --label 右转`）" % (
                            cfg.nav_bind, cfg.nav_port))
        if cfg.mirror_fps:
            self._print("眼镜预览 %.1f fps（HUD %.1f fps；同一 LCD 线程切模式，"
                        "不 release 接口）" % (cfg.mirror_fps, cfg.hud_fps))
            self._print("数字变焦 %.1fx：源中心裁切 %s → 按 --mirror-fit %s 映射"
                        % (self._zoom, self._zoom_crop(), cfg.mirror_fit))

    # -- 主循环 ---------------------------------------------------------
    def run(self) -> StreamStats:
        cfg = self.config
        self.stats.display = "hud"
        self.stats.rtsp = bool(cfg.rtsp_url)
        self._print_banner()
        self._start_ctl()
        self._start_nav_udp()
        self._start_action_worker()
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
                # 0) Fn 按键：只从队列取事件，绝不在读线程里发 USB 请求。
                self._drain_buttons()
                if (cfg.fn_keys and self._hid is not None
                        and not self._hid.alive and self._source is not None):
                    self._print("HID 轮询线程已退出，重启")
                    self._stop_hid_poller()
                    self._start_hid_poller()
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
                        self._start_hid_poller()
                        # 启动时按 --mirror-fps 进 preview；重连时恢复用户上次
                        # 用 Fn/ctl 选过的模式（preview 才重新占 LCD）。
                        if not self._booted:
                            self._booted = True
                            if cfg.mirror_fps:
                                self._start_preview()
                        elif self._display_mode == "preview":
                            self._start_preview()
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
                if self._pipe is not None and not self._rtsp_enabled:
                    pipe, self._pipe = self._pipe, None
                    self._print("RTSP 推流已停止（ffmpeg 退出）")
                    try:
                        pipe.stop()
                    except Exception:
                        pass
                if (cfg.rtsp_url and self._rtsp_enabled
                        and self._pipe is None and now >= encoder_retry_at):
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
                        self.stats.lcd_render_ms = lcd_stats["render_ms"]
                        self.stats.lcd_send_ms = lcd_stats["send_ms"]
                        self.stats.lcd_ms = (lcd_stats["render_ms"]
                                             + lcd_stats["send_ms"])
                        self.stats.lcd_frames = lcd_stats["frames"]
                        self.stats.lcd_dropped = lcd_stats["dropped"]
                        if lcd_stats["last_error"]:
                            self.stats.last_error = lcd_stats["last_error"]
                    source = self._source
                    if source is not None and hasattr(source, "async_stats"):
                        try:
                            async_info = source.async_stats() or {}
                        except Exception:
                            async_info = {}
                        self.stats.async_urbs = int(async_info.get("urbs", 0) or 0)
                        self.stats.async_pauses = int(
                            async_info.get("pauses", 0) or 0)
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
        self._release_preview()
        self._stop_hid_poller()
        if self._source is not None:
            try:
                self._source.close()
            except Exception:
                pass
        self._source = None

    def _close_all(self) -> None:
        self._stop.set()
        if self._ctl is not None:
            try:
                self._ctl.stop()
            except Exception:
                pass
            self._ctl = None
        self._stop_action_worker()
        if self._nav_listener is not None:
            try:
                self._nav_listener.stop()
            except Exception:
                pass
            self._nav_listener = None
        self.hub.close()
        if self._pipe is not None:
            self._pipe.stop()
            self._pipe = None
        self._close_source()
        if self._hud is not None:
            try:
                self._hud.close()
            except Exception:
                pass
            self._hud = None
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
