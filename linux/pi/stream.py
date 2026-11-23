#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GLXSS G25 直播一键启动（Pi Zero 2 W）。

链路：眼镜 UVC → pyusb 读 YV12 → ffmpeg(h264_v4l2m2m) → RTSP → PC OBS。

例子（Pi 上跑，mediamtx 也在 Pi 上）：

    python3 pi/stream.py                          # 1280×720@15fps 2Mbps → 本机 mediamtx
    python3 pi/stream.py --check                  # 只检查 UVC 能不能读、实测 fps
    python3 pi/stream.py --fps 25 --bitrate 3M    # 720p 编码 25fps（采集受 USB 带宽限制）
    python3 pi/stream.py --mjpeg-port 8080        # 顺带开 MJPEG，OBS 用 VLC 源
    python3 pi/stream.py --mirror-fps 3           # 同时把画面投到眼镜上预览

不写 --rtsp 时默认推 `rtsp://127.0.0.1:8554/glxss`；PC 上 OBS「媒体源」填
脚本启动时打印的 `rtsp://<Pi-IP>:8554/glxss`。

注意：同一时间只有一个进程能占用 UVC 接口。运行本脚本前请停掉
`glxss uvc` / 监看模式和 `glxss-stream.service` 以外的取流程序。
"""

from __future__ import annotations

import argparse
import os
import shutil
import signal
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if os.path.isdir(os.path.join(ROOT, "glxss")) and ROOT not in sys.path:
    sys.path.insert(0, ROOT)

try:
    from glxss.errors import GlxssError
    from glxss.stream import (
        DEFAULT_RTSP, StreamConfig, TestPatternSource, UvcSource, local_ip,
        obs_url, run_stream,
    )
except ImportError as exc:  # pragma: no cover - 部署问题
    sys.stderr.write(
        "无法导入 glxss（%s）。\n"
        "请确认在仓库 linux/ 目录下运行，或设置 PYTHONPATH=/home/taco/glxss3-linux。\n" % exc)
    raise SystemExit(2)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="pi/stream.py",
        description="GLXSS G25 UVC 直播：RTSP（主）/ MJPEG（备选）/ 眼镜预览",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
        epilog="完整说明见 linux/pi/STREAM.md。",
    )
    p.add_argument("--rtsp", default=DEFAULT_RTSP,
                   help="RTSP 推流地址（mediamtx 的 path）")
    p.add_argument("--no-rtsp", action="store_true", help="不推 RTSP（只做 MJPEG/预览）")
    p.add_argument("--size", default="1280x720", help="采集分辨率，宽x高（YUV420 需偶数）")
    p.add_argument("--format-index", type=int, default=None,
                   help="UVC bFormatIndex（默认 1=YV12，Android 实测值）")
    p.add_argument("--frame-index", type=int, default=None,
                   help="UVC bFrameIndex（默认按描述符自动选 3=1280x720@30）")
    p.add_argument("--fps", type=float, default=15.0,
                   help="目标帧率；同时作为 UVC 协商 interval。"
                        "720p 在 Pi Zero 2 W 上实测约 14-15fps（USB 带宽上限）")
    p.add_argument("--bitrate", default="1500k", help="H.264 码率（如 1500k、2M）")
    p.add_argument("--maxrate", default="", help="可选：最大码率（CBR 时和 --bitrate 相同）")
    p.add_argument("--bufsize", default="", help="可选：码率缓冲（如 3000k）")
    p.add_argument("--codec", default="h264_v4l2m2m",
                   choices=["h264_v4l2m2m", "libx264", "h264_omx"],
                   help="h264_v4l2m2m 是 Pi 硬件编码（/dev/video11）")
    p.add_argument("--transport", default="tcp", choices=["tcp", "udp"],
                   help="RTSP 传输；局域网用 tcp 更稳")
    p.add_argument("--gop", type=int, default=0, help="关键帧间隔（默认 fps×2）")
    p.add_argument("--detach", dest="detach", action="store_true", default=True,
                   help="临时摘掉内核 uvcvideo（默认开）")
    p.add_argument("--no-detach", dest="detach", action="store_false",
                   help="不摘 uvcvideo（此时多半抢不到接口）")
    p.add_argument("--mjpeg-port", type=int, default=0,
                   help="同时提供 MJPEG HTTP 的端口（0=关闭）")
    p.add_argument("--mjpeg-fps", type=float, default=10.0, help="MJPEG 帧率上限")
    p.add_argument("--mjpeg-quality", type=int, default=70, help="MJPEG 质量 10-95")
    p.add_argument("--async-urb", type=int, default=16,
                   help="异步 URB 数（0=同步 16 KiB 读；16 可在 Pi 上稳定 720p 30fps）")
    p.add_argument("--async-timeout", type=int, default=1000,
                   help="单个异步 URB 超时毫秒（默认 1000）")
    p.add_argument("--hid-poll", type=float, default=0.1,
                   help="HID/Fn 轮询间隔秒（默认 0.1；设备报告周期 300ms）")
    p.add_argument("--no-fn", dest="fn_keys", action="store_false", default=True,
                   help="禁用 Fn 按键（长按切 video/HUD，短按循环变焦；RTSP 用 ctl）")
    p.add_argument("--fn-long-press", type=float, default=0.9,
                   help="Fn 长按阈值秒（默认 0.9；只看按住时间，不要求包数）")
    p.add_argument("--fn-long-packets", type=int, default=4,
                   help="已废弃：长按不再要求按下包数（保留只为兼容旧脚本）")
    p.add_argument("--ctl-socket", default=None,
                   help="控制 socket 路径（默认 glxss 的默认路径；--no-ctl 关闭）")
    p.add_argument("--no-ctl", action="store_true",
                   help="不启动控制 socket（只靠状态行/日志）")
    p.add_argument("--mirror-fps", type=float, default=0.0,
                   help="启动即投眼镜屏的帧率（0=不投，长按 Fn 打开）")
    p.add_argument("--preview-fps", type=float, default=0.0,
                   help="长按 Fn 打开眼镜预览时用的帧率（0=mirror-fps 或 10）")
    p.add_argument("--zoom", type=float, default=1.0, choices=(1.0, 1.5, 2.0),
                   help="启动时的数字变焦档位（只影响眼镜预览；运行中短按 Fn "
                        "或 ctl zoom 切换；重启回到这里，不落盘）")
    p.add_argument("--mirror-rotate", type=int, choices=(0, 180), default=0,
                   help="眼镜预览旋转：0=正常 / 180=画面上下颠倒时用")
    p.add_argument("--hud-fps", type=float, default=2.0,
                   help="长按 Fn 切到 HUD 后的重绘/发送帧率（默认 2）")
    p.add_argument("--nav-port", type=int, default=9999,
                   help="手机导航 UDP 监听端口（默认 9999；0=关闭）")
    p.add_argument("--nav-bind", default="0.0.0.0",
                   help="导航 UDP 绑定地址（默认 0.0.0.0，手机走 Wi-Fi）")
    p.add_argument("--nav-stale", type=float, default=5.0,
                   help="多少秒没收到导航包就显示“等待手机导航…”（默认 5）")
    p.add_argument("--no-nav-udp", action="store_true",
                   help="不监听手机导航 UDP（仍可用 glxss ctl nav）")
    p.add_argument("--level-invert-x", dest="level_invert_x",
                   action="store_const", const=True, default=None,
                   help="强制取反水平仪 roll（左右倾）；默认 180 旋转下取反")
    p.add_argument("--no-level-invert-x", dest="level_invert_x",
                   action="store_const", const=False,
                   help="强制不取反水平仪 roll（0 旋转默认）")
    p.add_argument("--level-invert-y", dest="level_invert_y",
                   action="store_const", const=True, default=None,
                   help="强制取反水平仪 pitch（上下）；默认 180 旋转下取反")
    p.add_argument("--no-level-invert-y", dest="level_invert_y",
                   action="store_const", const=False,
                   help="强制不取反水平仪 pitch（0 旋转默认）")
    p.add_argument("--mirror-fit", default="fit",
                   choices=["fit", "cover", "crop", "stretch"], help="眼镜预览的映射方式")
    p.add_argument("--mirror-level", dest="mirror_level", action="store_true",
                   default=True, help="眼镜预览叠水平仪（读 HID 加速度计，默认开）")
    p.add_argument("--no-mirror-level", dest="mirror_level", action="store_false",
                   help="不读 HID，只画三分线 + 中心准星")
    p.add_argument("--no-lcd-pause", action="store_true",
                   help="LCD 发送时不暂停 UVC 异步读（对照用；会加大撕裂窗口）")
    p.add_argument("--api-url", default="auto",
                   help="mediamtx API（用于实测码率）；auto=本机推流时用 :9997，否则关闭")
    p.add_argument("--stats-interval", type=float, default=2.0, help="统计打印间隔（秒）")
    p.add_argument("--duration", type=float, default=0.0, help="运行多少秒后退出（0=一直跑）")
    p.add_argument("--frames", type=int, default=0, help="最多采集多少帧（0=不限）")
    p.add_argument("--timeout", type=int, default=5000, help="USB 超时（毫秒）")
    p.add_argument("--ffmpeg", default="ffmpeg", help="ffmpeg 路径")
    p.add_argument("--test-source", action="store_true",
                   help="用合成测试图案代替眼镜（没有硬件时验证链路）")
    p.add_argument("--check", action="store_true",
                   help="只自检：打开 UVC、probe、读 10 帧测 fps，然后退出")
    p.add_argument("-v", "--verbose", action="store_true", help="详细日志")
    return p


def run_check(config: StreamConfig) -> int:
    """--check：确认 UVC 能打开、probe 正常、实测帧率，不启动 ffmpeg。"""
    print("[check] ffmpeg: %s" % (shutil.which(config.ffmpeg) or "未安装！"))
    source = TestPatternSource(config) if config.test_source else UvcSource(config)
    print("[check] 打开采集源（%s）..." % source.name)
    try:
        probe = source.open()
        if probe is not None:
            print("[check] probe:\n" + probe.format())
        # commit 后第一帧可能是上一会话残留/设备重启数据面，实测会慢 ~0.4-0.5 s；
        # 预热 2 帧再计时，否则 10 帧均值会被这一下拖到 ~13 fps（见 docs/ch-uvc.md §7.2）。
        print("[check] 预热 2 帧 ...")
        for _ in range(2):
            source.read()
        count = 10
        print("[check] 读 %d 帧测速 ..." % count)
        started = time.monotonic()
        for _ in range(count):
            frame = source.read()
        elapsed = max(1e-6, time.monotonic() - started)
        print("[check] 实测 %dx%d，%.1f fps，%.2f MB/s，frame_size=%d" % (
            frame.width, frame.height, count / elapsed,
            count * frame.frame_size / elapsed / 1e6, frame.frame_size))
        stream = getattr(source, "stream", None)
        if stream is not None and getattr(stream, "negotiated_fallback", False):
            print("[check] 警告：1280×720 协商失败，已回退 %dx%d（画面仍是裁切视角）"
                  % (frame.width, frame.height))
        async_stats = getattr(source, "async_stats", None)
        if callable(async_stats):
            info = async_stats() or {}
            if info:
                print("[check] 异步 URB：%d 个在途/%d B，已读 %.1f MB，错误 %d，暂停 %d 次"
                      % (info.get("urbs", 0), info.get("chunk", 0),
                         info.get("bytes", 0) / 1e6, info.get("errors", 0),
                         info.get("pauses", 0)))
    finally:
        source.close()
    print("[check] OK")
    return 0


def _install_signal_handlers() -> None:
    """systemd stop 发 SIGTERM；转成 KeyboardInterrupt 走 finally 清理 ffmpeg。"""
    def _handler(signum, frame):  # noqa: ARG001
        raise KeyboardInterrupt

    for name in ("SIGTERM", "SIGHUP"):
        sig = getattr(signal, name, None)
        if sig is not None:
            try:
                signal.signal(sig, _handler)
            except Exception:
                pass


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    # systemd/journal 下也能立刻看到日志（重定向到文件时 Python 默认块缓冲）
    try:
        sys.stdout.reconfigure(line_buffering=True)
        sys.stderr.reconfigure(line_buffering=True)
    except Exception:
        pass
    _install_signal_handlers()
    if args.no_rtsp:
        args.rtsp = ""
    try:
        config = StreamConfig.from_args(args)
    except GlxssError as exc:
        print("[错误] %s" % exc, file=sys.stderr)
        return 2

    if config.api_url == "auto":
        host = (config.rtsp_url or "").split("://", 1)[-1].split(":", 1)[0]
        config.api_url = "http://127.0.0.1:9997" if host in (
            "127.0.0.1", "localhost", "") else ""

    try:
        if args.check:
            return run_check(config)
        if config.rtsp_url:
            print("[stream] 提示：OBS「媒体源」→ %s（网络缓存 0.1-0.3 MB 延迟更低）"
                  % obs_url(config.rtsp_url), flush=True)
        if config.mjpeg_port:
            print("[stream] 提示：OBS「VLC 视频源」→ http://%s:%d/stream"
                  % (local_ip(), config.mjpeg_port), flush=True)
        stats = run_stream(config)
    except GlxssError as exc:
        print("[错误] %s" % exc, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n[stream] 已停止")
        return 0
    print("[stream] 结束：%s" % stats.line())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
