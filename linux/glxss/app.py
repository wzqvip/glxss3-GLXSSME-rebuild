# -*- coding: utf-8 -*-
"""投屏主循环：采集 → 640×400 RGBA → 16 B 头 → bulk OUT，带 fps 统计。"""

from __future__ import annotations

import time
from dataclasses import dataclass, replace
from typing import Callable, Optional

from .capture import (ImageSource, map_to_rgba, rgba_bytes, rotate180_rgba)
from .errors import GlxssError
from .lcd import FRAME_TOTAL, build_frame
from .util import human_rate

Sender = Optional[Callable[[bytes], int]]


@dataclass
class CastStats:
    frames: int = 0
    failures: int = 0
    bytes_sent: int = 0
    elapsed: float = 0.0
    last_frame_ms: float = 0.0
    source: str = ""
    fit: str = "fit"
    target_fps: float = 0.0

    @property
    def fps(self) -> float:
        return self.frames / self.elapsed if self.elapsed > 0 else 0.0

    @property
    def mbps(self) -> float:
        return (self.bytes_sent / self.elapsed / 1e6) if self.elapsed > 0 else 0.0

    def line(self) -> str:
        return ("帧=%d 失败=%d 平均=%.1f fps  %.2f MB/s 单帧=%.1f ms 源=%s fit=%s" % (
            self.frames, self.failures, self.fps, self.mbps,
            self.last_frame_ms, self.source, self.fit))


StatsCallback = Optional[Callable[[CastStats], None]]


class CastApp:
    """可注入 sender 的主循环；sender=None 即 dry-run（仍生成完整帧）。"""

    def __init__(self, source: ImageSource, sender: Sender = None,
                 fps: float = 15.0, fit: str = "fit", rotate: int = 0,
                 duration: float = 0.0, max_frames: int = 0,
                 timeout: int = 5000, on_stats: StatsCallback = None,
                 close_source: bool = False, report_interval: float = 1.0):
        self.source = source
        self.sender = sender
        self.fps = max(0.0, fps)
        self.fit = fit
        self.rotate = 180 if int(rotate) % 360 == 180 else 0
        self.duration = max(0.0, duration)
        self.max_frames = max(0, max_frames)
        self.timeout = timeout
        self.on_stats = on_stats
        self.close_source = close_source
        self.report_interval = max(0.1, report_interval)
        self._stop = False

    def stop(self) -> None:
        self._stop = True

    def run(self) -> CastStats:
        stats = CastStats(source=self.source.name, fit=self.fit, target_fps=self.fps)
        started = time.monotonic()
        last_report = started
        period = (1.0 / self.fps) if self.fps > 0 else 0.0
        failures_in_a_row = 0
        try:
            while not self._stop:
                now = time.monotonic()
                if self.duration and now - started >= self.duration:
                    break
                if self.max_frames and stats.frames >= self.max_frames:
                    break
                frame_started = time.monotonic()
                try:
                    # 采集源可以直接给出 640×400 RGBA（monitor/UVC 快路径），
                    # 省掉 map_to_rgba 的缩放、通道拼接和一次 1 MB 拷贝。
                    grab_rgba = getattr(self.source, "grab_rgba", None)
                    rgba = grab_rgba() if callable(grab_rgba) else None
                    if rgba is None:
                        rgb = self.source.grab()
                        rgba = map_to_rgba(rgb, mode=self.fit)
                    if self.rotate == 180:
                        rgba = rotate180_rgba(rgba)
                    packet = build_frame(rgba_bytes(rgba))
                    if len(packet) != FRAME_TOTAL:
                        raise GlxssError("内部错误：帧包长度 %d != %d" % (
                            len(packet), FRAME_TOTAL))
                    if self.sender is not None:
                        written = self.sender(packet)
                        if written != len(packet):
                            raise GlxssError("帧发送不完整：%d/%d" % (written, len(packet)))
                        stats.bytes_sent += written
                    failures_in_a_row = 0
                except GlxssError as exc:
                    stats.failures += 1
                    failures_in_a_row += 1
                    if failures_in_a_row >= 3:
                        raise GlxssError(
                            "连续 3 帧失败：%s\n"
                            "  * 关闭厂商 App/其他占用 USB 的程序；\n"
                            "  * 检查设备是否还在 2e09:0030；\n"
                            "  * 设备假死时可参考 firmware.py 重新灌固件。" % exc) from exc
                    print("[警告] 帧失败（连续 %d 次）: %s" % (failures_in_a_row, exc))
                    time.sleep(0.2)
                    continue
                stats.frames += 1
                stats.last_frame_ms = (time.monotonic() - frame_started) * 1000.0
                stats.elapsed = max(1e-6, time.monotonic() - started)

                now = time.monotonic()
                if now - last_report >= self.report_interval:
                    if self.on_stats:
                        self.on_stats(replace(stats))
                    last_report = now
                if period:
                    spend = time.monotonic() - frame_started
                    if spend < period:
                        time.sleep(period - spend)
        finally:
            stats.elapsed = max(1e-6, time.monotonic() - started)
            if self.close_source:
                self.source.close()
            if self.on_stats:
                self.on_stats(replace(stats))
        return stats


def run_cast(source: ImageSource, sender: Sender, fps: float = 15.0,
             fit: str = "fit", duration: float = 0.0,
             max_frames: int = 0, verbose: bool = True) -> CastStats:
    """便捷入口：每秒打印一行统计。"""
    def _print(stats: CastStats) -> None:
        if verbose:
            print("[%s] %s" % (time.strftime("%H:%M:%S"), stats.line()))

    app = CastApp(source, sender=sender, fps=fps, fit=fit,
                  duration=duration, max_frames=max_frames, on_stats=_print)
    return app.run()


def stats_summary(stats: CastStats) -> str:
    return ("发送结束：成功 %d 帧，失败 %d 帧，耗时 %.2f s，平均 %.1f fps，%s" % (
        stats.frames, stats.failures, stats.elapsed, stats.fps,
        human_rate(stats.bytes_sent / stats.elapsed if stats.elapsed else 0)))
