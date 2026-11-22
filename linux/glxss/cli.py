# -*- coding: utf-8 -*-
"""glxss 命令行入口（argparse）。

子命令：
  list / status / bright / screen / mode / roll / eeprom / version
  hid / uvc / frame / flash / cast / hud / ctl

所有硬件命令都复用 glxss.usb / lcd / hid / uvc / firmware；离线命令
（--help、list 之外的 dry-run、frame/hud 生成）不要求眼镜在线。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import threading
import time
from typing import Optional, Tuple

from . import __version__, capture, control, firmware, hid, hud, lcd, monitor, usb, uvc
from .app import CastApp, stats_summary
from .errors import GlxssError
from .util import hexdump, hexs, now_str


# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------
def _add_device_args(parser: argparse.ArgumentParser, timeout: int = 2000) -> None:
    parser.add_argument("--detach", action="store_true",
                        help="Linux 临时 detach 内核驱动（uvcvideo/hid-generic）")
    parser.add_argument("--timeout", type=int, default=timeout,
                        help="USB 超时毫秒（默认 %d）" % timeout)
    parser.add_argument("-v", "--verbose", action="store_true", help="打印接口/claim 细节")


def _add_fit_arg(parser: argparse.ArgumentParser, default=None) -> None:
    parser.add_argument("--rotate", type=int, choices=(0, 180), default=0,
                        help="画面旋转：0=正常 / 180=上下左右翻转（眼镜装反时用）")
    parser.add_argument("--fit", choices=("fit", "cover", "crop", "stretch"),
                        default=default,
                        help="映射：fit=留黑边 / cover=裁切 / stretch=拉伸"
                             "（默认 hud=stretch、uvc=cover、其它=fit）")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="glxss",
        description="GLXSS3/G25 跨平台工具（Linux/Windows/macOS）：LCD 投屏、UVC、HID、固件。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="协议文档：docs/ch-display.md、ch-uvc.md、ch-hid.md、ch-firmware.md\n"
               "Linux 版安装/权限/树莓派部署：linux/README.md",
    )
    parser.add_argument("--version", action="version",
                        version="glxss %s" % __version__)
    sub = parser.add_subparsers(dest="command", metavar="命令")

    # list -----------------------------------------------------------------
    p = sub.add_parser("list", help="枚举 USB 设备（不需要眼镜）",
                       description="列出本机所有 USB 设备；GLXSS 会展开接口/端点。")
    p.add_argument("--brief", action="store_true", help="只打一行摘要")
    p.add_argument("--glxss-only", action="store_true", help="只显示 2e09:0030 / 03e7:2150")
    p.set_defaults(func=cmd_list)

    # status ---------------------------------------------------------------
    p = sub.add_parser("status", help="读 LCD 状态（8 B）")
    _add_device_args(p)
    p.set_defaults(func=cmd_status)

    # bright ---------------------------------------------------------------
    p = sub.add_parser("bright", help="设置亮度（0..255，实测 100..254）")
    p.add_argument("value", type=int, help="亮度值")
    _add_device_args(p)
    p.add_argument("--no-verify", action="store_true", help="写完不回读状态")
    p.set_defaults(func=cmd_bright)

    # screen ---------------------------------------------------------------
    p = sub.add_parser("screen", help="开/关屏")
    p.add_argument("state", choices=("on", "off"), help="on=开屏，off=关屏")
    _add_device_args(p)
    p.add_argument("--no-verify", action="store_true", help="写完不回读状态")
    p.set_defaults(func=cmd_screen)

    # mode -----------------------------------------------------------------
    p = sub.add_parser("mode", help="显示模式（1=待机/logo，2=投屏）")
    p.add_argument("value", type=int, choices=(0, 1, 2), help="0/1/2（0 未验证）")
    _add_device_args(p)
    p.add_argument("--no-verify", action="store_true", help="写完不回读状态")
    p.set_defaults(func=cmd_mode)

    # roll -----------------------------------------------------------------
    p = sub.add_parser("roll", help="设置 roll（0..65535）")
    p.add_argument("value", type=int, help="roll 值")
    _add_device_args(p)
    p.add_argument("--no-verify", action="store_true", help="写完不回读状态")
    p.set_defaults(func=cmd_roll)

    # eeprom ---------------------------------------------------------------
    p = sub.add_parser("eeprom", help="只读 EEPROM 产品信息（264 B）",
                       description="只读 0xA1/0x07；不实现危险的 EEPROM 写入。")
    _add_device_args(p)
    p.add_argument("--raw", action="store_true", help="附完整 hexdump")
    p.set_defaults(func=cmd_eeprom)

    # version --------------------------------------------------------------
    p = sub.add_parser("version", help="读 HID 固件版本与设备 ID")
    _add_device_args(p)
    p.set_defaults(func=cmd_version)

    # hid ------------------------------------------------------------------
    p = sub.add_parser("hid", help="轮询 28 B HID 报告（按键/lux/IMU/温度）")
    _add_device_args(p, timeout=5000)
    p.add_argument("--count", type=int, default=0, help="读取次数（0=一直读）")
    p.add_argument("--interval", type=float, default=0.3, help="轮询间隔秒（默认 0.3）")
    p.add_argument("--enable-sensors", action="store_true", help="先发 0x10/0x11/0x12/0x14 使能")
    p.add_argument("--raw", action="store_true", help="每行都附 28 B 原始 hex")
    p.set_defaults(func=cmd_hid)

    # uvc ------------------------------------------------------------------
    p = sub.add_parser("uvc", help="UVC probe/commit 并读帧（可存 PNG）")
    _add_device_args(p, timeout=5000)
    p.add_argument("--frames", type=int, default=3, help="读几帧（默认 3）")
    p.add_argument("--format-index", type=int, default=None, help="bFormatIndex（默认沿用设备）")
    p.add_argument("--frame-index", type=int, default=None, help="bFrameIndex（默认沿用设备）")
    p.add_argument("--fps", type=float, default=None, help="协商帧率（默认沿用设备）")
    p.add_argument("--width", type=int, default=640, help="帧宽（默认 640）")
    p.add_argument("--height", type=int, default=480, help="帧高（默认 480）")
    p.add_argument("--save", metavar="PNG", help="把第一帧转 RGB 存成 PNG")
    p.add_argument("--probe-only", action="store_true", help="只做 probe/回读，不 commit/读流")
    p.set_defaults(func=cmd_uvc)

    # frame ----------------------------------------------------------------
    p = sub.add_parser("frame", help="生成/发送单帧或测试图案",
                       description="离线 --dry-run 可验证 16 B 头与 RGBA 打包。")
    p.add_argument("kind", nargs="?", default="test",
                   choices=("test", "bars", "black", "solid", "image", "png", "hud"),
                   help="test(默认)/bars/black/solid/image/png/hud")
    p.add_argument("path", nargs="?", help="image/png 的路径；solid 时可直接给颜色")
    p.add_argument("--color", default=None, help="solid 颜色，例如 ff8000")
    _add_fit_arg(p, default="fit")
    p.add_argument("--dry-run", action="store_true", help="只生成帧并打印 SHA256，不打开设备")
    p.add_argument("--save-png", metavar="PNG", help="保存映射后的 640×400 RGBA PNG")
    p.add_argument("--fps", type=float, default=10.0, help="发送帧率（0=不限速）")
    p.add_argument("--seconds", type=float, default=1.0, help="发送时长秒（默认 1）")
    p.add_argument("--loop", action="store_true", help="一直发到 Ctrl-C")
    p.add_argument("--no-init", action="store_true", help="不先发开屏/模式命令")
    _add_device_args(p, timeout=5000)
    p.set_defaults(func=cmd_frame)

    # flash ----------------------------------------------------------------
    p = sub.add_parser("flash", help="向 03e7:2150 灌固件（默认 dry-run）",
                       description="默认只分析镜像；必须显式 --yes 才会写设备。")
    p.add_argument("image", help="G25_MainFW_*.img（不随仓库分发）")
    p.add_argument("--yes", action="store_true", help="真的写入（危险）")
    p.add_argument("--no-decrypt", action="store_true", help="镜像已是明文，跳过 AES 尾部解密")
    p.add_argument("--sha256", default=None, help="校验将发送数据的 SHA256")
    p.add_argument("--force", action="store_true", help="绕过 MA2x/版本串检查（危险）")
    _add_device_args(p, timeout=10000)
    p.set_defaults(func=cmd_flash)

    # cast -----------------------------------------------------------------
    p = sub.add_parser("cast", help="实时投屏（HUD/相机监看/屏幕/测试图案 → 眼镜）")
    p.add_argument("--source", choices=("hud", "uvc", "screen", "test"),
                   default="hud",
                   help="采集源：hud（默认 HUD）、uvc（眼镜相机监看）、screen 或 test")
    p.add_argument("--monitor", type=int, default=1, help="mss 显示器编号（0=全部）")
    p.add_argument("--fps", type=float, default=None,
                   help="目标帧率（HUD 默认 2，uvc 默认 10，screen/test 默认 15）")
    _add_fit_arg(p)
    p.add_argument("--duration", type=float, default=0.0, help="持续秒数（0=一直）")
    p.add_argument("--frames", type=int, default=0, help="发送帧数上限（0=不限）")
    p.add_argument("--dry-run", action="store_true",
                   help="只采集/映射，不打开眼镜（uvc 源需要真机）")
    p.add_argument("--test-pattern", choices=("bars", "gradient"), default="bars",
                   help="--source test 时的图案")
    p.add_argument("--brightness", type=int, default=180, help="HUD 源显示的初始亮度")
    p.add_argument("--demo", action="store_true", help="HUD/监看使用演示传感器数据")
    p.add_argument("--heading-offset", type=float, default=0.0,
                   help="罗盘 heading 校准偏移（度）")
    p.add_argument("--listen", action="store_true",
                   help="HUD 源启动 Unix socket 控制服务（供 LCD HAT 使用）")
    p.add_argument("--socket", default=None, help="控制 socket 路径")
    p.add_argument("--view", choices=("hud", "monitor"), default="hud",
                   help="HUD 主进程初始视图（可运行中用 ctl view 切换）")
    p.add_argument("--rec", action="store_true",
                   help="监看模式右上角显示录制红点/时间")
    p.add_argument("--camera-fps", type=float, default=15.0,
                   help="UVC 协商帧率（默认 15；发送帧率由 --fps 控制）")
    p.add_argument("--uvc-half", action="store_true",
                   help="UVC 先解 320×240 再放大（Pi Zero 2 W 上更省 CPU/延迟）")
    p.add_argument("--monitor-fit", choices=("cover", "stretch", "fit"),
                   default="cover", help="监看模式映射（默认 cover 裁切）")
    p.add_argument("--no-init", action="store_true", help="不先发开屏/模式命令")
    _add_device_args(p, timeout=5000)
    p.set_defaults(func=cmd_cast)

    # hud ------------------------------------------------------------------
    p = sub.add_parser("hud", help="HUD 渲染源：时钟/罗盘/姿态/光感 → 眼镜",
                       description="眼镜作为主显示，默认 2 fps 渲染 640×400 HUD；"
                                   "--view monitor 可切到相机监看；"
                                   "--dry-run 离线生成 PNG 便于核对图层。")
    p.add_argument("--fps", type=float, default=2.0,
                   help="HUD 发送帧率（默认 2，0=不限速；monitor 建议 10）")
    p.add_argument("--rotate", type=int, choices=(0, 180), default=0,
                   help="画面旋转：0=正常 / 180=上下左右翻转（眼镜装反时用）")
    p.add_argument("--brightness", type=int, default=180,
                   help="HUD 底栏/初始亮度（默认 180，实测有效 100..254）")
    p.add_argument("--duration", type=float, default=0.0, help="持续秒数（0=一直）")
    p.add_argument("--frames", type=int, default=0, help="发送帧数上限（0=不限）")
    p.add_argument("--dry-run", action="store_true",
                   help="离线渲染一张 HUD（演示数据），不打开眼镜")
    p.add_argument("--demo", action="store_true",
                   help="使用演示传感器数据（不读 HID）")
    p.add_argument("--heading-offset", type=float, default=0.0,
                   help="罗盘 heading 校准偏移（度）")
    p.add_argument("--save-png", metavar="PNG", help="保存一张 640×400 RGBA HUD PNG")
    p.add_argument("--listen", action="store_true",
                   help="启动 Unix socket 控制服务（LCD HAT 用）")
    p.add_argument("--socket", default=None, help="控制 socket 路径")
    p.add_argument("--view", choices=("hud", "monitor"), default="hud",
                   help="启动视图（默认 hud；monitor 需要眼镜 UVC，通常配合 --detach）")
    p.add_argument("--rec", action="store_true",
                   help="监看模式右上角显示录制红点/时间")
    p.add_argument("--camera-fps", type=float, default=15.0,
                   help="UVC 协商帧率（默认 15；发送帧率仍由 --fps 控制）")
    p.add_argument("--uvc-half", action="store_true",
                   help="UVC 先解 320×240 再放大（更省 CPU/延迟）")
    p.add_argument("--monitor-fit", choices=("cover", "stretch", "fit"),
                   default="cover", help="监看模式映射（默认 cover 裁切）")
    p.add_argument("--no-init", action="store_true", help="不先发开屏/模式命令")
    _add_device_args(p, timeout=5000)
    p.set_defaults(func=cmd_hud)

    # ctl ------------------------------------------------------------------
    p = sub.add_parser("ctl", help="通过 Unix socket 控制正在运行的 HUD 主进程")
    p.add_argument("action",
                   choices=("status", "ping", "bright", "screen", "mode",
                            "view", "rec", "hud", "test", "nav", "nav-clear",
                            "quit"),
                   help="要发送的命令（view/mode 可切 hud|monitor）")
    p.add_argument("value", nargs="?", help="命令参数（brightness/on/off/度数等）")
    p.add_argument("--distance", type=float, default=0.0, help="nav 的距离（米）")
    p.add_argument("--label", default="", help="nav 的文字标签")
    p.add_argument("--socket", default=None, help="控制 socket 路径")
    p.add_argument("--timeout", type=int, default=2000, help="socket 超时毫秒")
    p.add_argument("--json", action="store_true", help="原样输出 JSON")
    p.set_defaults(func=cmd_ctl)

    return parser


# ---------------------------------------------------------------------------
# 帮助函数
# ---------------------------------------------------------------------------
def _open_lcd(args):
    """返回 (dev, session, LcdLink, intf)；调用方负责 session.close()。"""
    dev = usb.find_normal()
    session = usb.DeviceSession(dev, detach=args.detach, verbose=args.verbose)
    intf, ep = usb.pick_bulk_out(dev)
    session.claim_interface(intf, label="LCD")
    link = lcd.LcdLink(dev, ep.bEndpointAddress, timeout=args.timeout)
    return dev, session, link, intf


def _verify(args, dev) -> None:
    if getattr(args, "no_verify", False):
        return
    try:
        status = lcd.read_status(dev, args.timeout)
        print("  回读: " + status.format())
    except GlxssError as exc:
        print("[警告] 回读状态失败（命令可能已生效）: %s" % exc, file=sys.stderr)


def _parse_color(text: Optional[str]) -> Tuple[int, int, int]:
    value = (text or "ff8000").strip().lstrip("#")
    if len(value) == 3:
        value = "".join(ch * 2 for ch in value)
    if len(value) != 6:
        raise GlxssError("颜色格式应为 rrggbb 或 #rrggbb，收到 %r" % text)
    try:
        return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16)
    except ValueError as exc:
        raise GlxssError("颜色格式错误: %r" % text) from exc


# ---------------------------------------------------------------------------
# list
# ---------------------------------------------------------------------------
def cmd_list(args) -> int:
    devices = usb.iter_devices()
    shown = 0
    print("USB 设备 %d 个（%s）" % (len(devices), usb.platform_note()))
    for dev in devices:
        known = usb.is_glxss(dev)
        if args.glxss_only and not known:
            continue
        shown += 1
        if args.brief:
            print(("  * " if known else "    ") + usb.device_label(dev))
        else:
            print()
            print((">>> " if known else "    ") + usb.describe_device(dev))
    if not shown:
        print("  没有匹配的设备。")
        print("  正常模式应出现 2e09:0030；引导模式应出现 03e7:2150。")
    return 0


# ---------------------------------------------------------------------------
# status / 控制
# ---------------------------------------------------------------------------
def cmd_status(args) -> int:
    dev, session, _link, _intf = _open_lcd(args)
    try:
        status = lcd.read_status(dev, args.timeout)
        print("设备: " + usb.device_label(dev))
        print("  raw   : " + hexs(status.raw))
        print("  分辨率: %d×%d" % (status.width, status.height))
        print("  屏    : %s" % ("开" if status.screen_open else "关"))
        print("  亮度  : %d (0x%02x)" % (status.luminance, status.luminance))
        print("  roll  : %d (0x%04x)" % (status.roll, status.roll))
        print("  byte7 : 0x%02x（UNKNOWN / roll 高字节）" % status.byte7)
    finally:
        session.close()
    return 0


def _run_control(args, request: int, value: int, label: str) -> int:
    dev, session, _link, _intf = _open_lcd(args)
    try:
        print("设备: " + usb.device_label(dev))
        # 直接用 lcd 的函数，ack 是设备返回的单字节。
        if request == 0x01:
            ack = lcd.set_brightness(dev, value, args.timeout)
        elif request == 0x03:
            ack = lcd.set_screen(dev, bool(value), args.timeout)
        elif request == 0x05:
            ack = lcd.set_mode(dev, value, args.timeout)
        elif request == 0x02:
            ack = lcd.set_roll(dev, value, args.timeout)
        else:
            ack = 0
        print("  %s: wValue=%d (0x%04x) -> ack=0x%02x" % (
            label, value, value, ack if ack is not None else 0))
        _verify(args, dev)
    finally:
        session.close()
    return 0


def cmd_bright(args) -> int:
    if not 0 <= args.value <= 255:
        raise GlxssError("亮度必须在 0..255（实测有效范围 100..254）")
    if args.value < 100 or args.value > 254:
        print("[警告] 亮度 %d 超出实测范围 100..254，仍照发。" % args.value,
              file=sys.stderr)
    return _run_control(args, 0x01, args.value, "设亮度")


def cmd_screen(args) -> int:
    return _run_control(args, 0x03, 1 if args.state == "on" else 0, "屏开关")


def cmd_mode(args) -> int:
    if args.value == 0:
        print("[警告] 模式 0 未在真机验证；1/2 是已验证值。", file=sys.stderr)
    return _run_control(args, 0x05, args.value, "显示模式")


def cmd_roll(args) -> int:
    if not 0 <= args.value <= 0xFFFF:
        raise GlxssError("roll 必须在 0..65535")
    return _run_control(args, 0x02, args.value, "设 roll")


# ---------------------------------------------------------------------------
# eeprom / version
# ---------------------------------------------------------------------------
def cmd_eeprom(args) -> int:
    dev, session, _link, _intf = _open_lcd(args)
    try:
        print("设备: " + usb.device_label(dev))
        raw = lcd.read_eeprom(dev, args.timeout)
        print("  返回 %d B（预期 %d）" % (len(raw), lcd.EEPROM_LEN))
        if len(raw) != lcd.EEPROM_LEN:
            print("[警告] 长度不是 264，仍按现有字节解析。", file=sys.stderr)
        for line in lcd.format_eeprom(raw):
            print(line)
        if args.raw:
            print(hexdump(raw, 32))
    finally:
        session.close()
    return 0


def cmd_version(args) -> int:
    dev = usb.find_normal()
    print("设备: " + usb.device_label(dev))
    with usb.DeviceSession(dev, detach=args.detach, verbose=args.verbose) as session:
        session.claim(usb.IFACE_HID, label="HID")
        version = hid.read_version(dev, args.timeout)
        print("  返回 %d B（预期 %d）" % (len(version.raw), hid.HID_PRODUCT_LEN))
        print(version.format())
        if not version.version:
            print("[警告] 版本串为空；确认 HID claim 成功（Linux 可试 --detach）。",
                  file=sys.stderr)
    return 0


# ---------------------------------------------------------------------------
# hid
# ---------------------------------------------------------------------------
def cmd_hid(args) -> int:
    if args.count < 0:
        raise GlxssError("--count 不能为负")
    if args.interval < 0:
        raise GlxssError("--interval 不能为负")
    dev = usb.find_normal()
    print("设备: " + usb.device_label(dev))
    seen = 0
    try:
        with usb.DeviceSession(dev, detach=args.detach, verbose=args.verbose) as session:
            session.claim(usb.IFACE_HID, label="HID")
            if args.enable_sensors:
                print("  使能传感器 ...")
                for line in hid.enable_sensors(dev, args.timeout, verbose=True):
                    print("    " + line)
            print("  每 %.0f ms 读 0xA1/0x16 wLength=28；Ctrl-C 退出。"
                  % (args.interval * 1000))
            for report in hid.poll_reports(dev, args.interval, args.count, args.timeout):
                seen += 1
                line = "[%s] %s" % (now_str(), report.format())
                if args.raw or seen == 1:
                    line += " raw=" + hexs(_last_raw_hint(report))
                print(line)
    except KeyboardInterrupt:
        print("\n已停止 HID 轮询（Ctrl-C），共 %d 次。" % seen)
        return 0
    print("HID 轮询结束，共 %d 次。" % seen)
    return 0


def _last_raw_hint(report) -> bytes:
    """报告对象不保留原始 hex；重新 pack 一次用于展示。"""
    import struct
    return struct.pack(
        "<BBH9hBBf",
        report.key_status, report.key_code, report.lux,
        report.compass_x, report.compass_y, report.compass_z,
        report.acc_x, report.acc_y, report.acc_z,
        report.gyro_x, report.gyro_y, report.gyro_z,
        report.af_result, report.caf_flag, report.temperature,
    )


# ---------------------------------------------------------------------------
# uvc
# ---------------------------------------------------------------------------
def cmd_uvc(args) -> int:
    if args.frames <= 0 and not args.probe_only:
        raise GlxssError("--frames 必须 > 0（或改用 --probe-only）")
    if args.width <= 0 or args.height <= 0:
        raise GlxssError("--width/--height 必须 > 0")
    dev = usb.find_normal()
    print("设备: " + usb.device_label(dev))
    with usb.DeviceSession(dev, detach=args.detach, verbose=args.verbose) as session:
        stream = uvc.UvcStream.open(dev, session, timeout=args.timeout,
                                    verbose=args.verbose)
        stream.set_dimensions(args.width, args.height)
        frame_size = args.width * args.height * 3 // 2
        probe = stream.negotiate(
            format_index=args.format_index,
            frame_index=args.frame_index,
            fps=args.fps,
            max_frame=frame_size,
        )
        print("  协商结果:")
        for line in probe.format().splitlines():
            print("    " + line.strip())
        if args.probe_only:
            print("--probe-only：停在 probe，未 commit、未读流。")
            return 0
        print("  读 %d 帧（%d×%d YV12，frameSize=%d）..." % (
            args.frames, args.width, args.height, frame_size))
        count = 0
        first = time.monotonic()
        for frame in stream.read_frames(args.frames, timeout=args.timeout):
            count += 1
            print("  帧 #%d %d B  %s" % (
                frame.index, frame.frame_size, frame.header.format()))
            if count == 1 and args.save:
                frame.save_png(args.save)
                print("  已保存首帧: " + args.save)
        elapsed = max(1e-6, time.monotonic() - first)
        print("UVC 读取结束：%d 帧，%.1f fps，%.2f MB/s" % (
            count, count / elapsed, count * frame_size / elapsed / 1e6))
    return 0


# ---------------------------------------------------------------------------
# frame
# ---------------------------------------------------------------------------
def _resolve_frame_source(args):
    """返回 (rgb, desc)；rgb 是 H×W×3 numpy 数组。"""
    kind = args.kind
    if kind == "test":
        source = capture.TestPatternSource(animate=False)
        return source.grab(), "640×400 测试图案"
    if kind == "bars":
        source = capture.TestPatternSource(animate=False)
        return source.grab(), "八色竖条"
    if kind == "black":
        import numpy as np
        return np.zeros((capture.LCD_H, capture.LCD_W, 3), dtype=np.uint8), "纯黑"
    if kind == "solid":
        import numpy as np
        color = _parse_color(args.path or args.color)
        rgb = np.zeros((capture.LCD_H, capture.LCD_W, 3), dtype=np.uint8)
        rgb[:, :] = color
        return rgb, "纯色 #%02x%02x%02x" % color
    if kind == "hud":
        import numpy as np
        image = hud.HudSource(demo=True).render()
        return np.asarray(image.convert("RGB"), dtype=np.uint8), "HUD 图层（演示数据）"
    if kind in ("image", "png"):
        if not args.path:
            raise GlxssError("frame %s 需要一个图片路径" % kind)
        return capture.load_rgb_image(args.path), "图片 %s" % args.path
    raise GlxssError("未知帧类型 %r" % kind)


def _frame_packet(args):
    rgb, desc = _resolve_frame_source(args)
    rgba = capture.map_to_rgba(rgb, mode=args.fit)
    if int(getattr(args, "rotate", 0) or 0) % 360 == 180:
        rgba = capture.rotate180_rgba(rgba)
    payload = capture.rgba_bytes(rgba)
    packet = lcd.build_frame(payload)
    return packet, desc, rgba


def _print_frame_header(packet: bytes, desc: str) -> None:
    print("帧源: " + desc)
    print("  帧头 16B : " + hexs(packet[:16]))
    print("  totalLen : %d (0x%06x) = 16 + %d" % (
        len(packet), len(packet), lcd.FRAME_PAYLOAD))
    print("  包大小   : %d B" % len(packet))
    print("  SHA256   : " + hashlib.sha256(packet).hexdigest())


def cmd_frame(args) -> int:
    if args.fps < 0:
        raise GlxssError("--fps 不能为负（0 表示不限速）")
    if not args.loop and args.seconds <= 0:
        raise GlxssError("--seconds 必须 > 0（一直发请用 --loop）")
    packet, desc, rgba = _frame_packet(args)
    _print_frame_header(packet, desc)
    if args.save_png:
        capture.save_rgba_png(args.save_png, rgba)
        print("  已保存 640×400 RGBA PNG: " + args.save_png)
    if args.dry_run:
        print("DRY-RUN：只生成帧，不打开设备、不发送。")
        return 0

    dev, session, link, intf = _open_lcd(args)
    try:
        print("设备: " + usb.device_label(dev))
        print("  端点: if=%d alt=%d class=0x%02x ep=0x%02x" % (
            intf.bInterfaceNumber, intf.bAlternateSetting,
            intf.bInterfaceClass, link.ep_addr))
        if not args.no_init:
            try:
                link.initialize(args.timeout)
                print("  已开屏并切到投屏模式（0x03=1, 0x05=2）")
            except GlxssError as exc:
                print("[警告] 初始化失败（继续发帧）: %s" % exc, file=sys.stderr)
        period = (1.0 / args.fps) if args.fps > 0 else 0.0
        start = time.monotonic()
        sent = 0
        last_report = start
        try:
            while True:
                if not args.loop and time.monotonic() - start >= args.seconds:
                    break
                frame_started = time.monotonic()
                link.send_packet(packet)
                sent += 1
                now = time.monotonic()
                if now - last_report >= 1.0:
                    elapsed = max(1e-6, now - start)
                    print("[%5.1fs] 帧=%d 平均=%.1f fps %.2f MB/s" % (
                        elapsed, sent, sent / elapsed, sent * len(packet) / elapsed / 1e6))
                    last_report = now
                if period:
                    spend = time.monotonic() - frame_started
                    if spend < period:
                        time.sleep(period - spend)
        except KeyboardInterrupt:
            print("\n已中断。")
        elapsed = max(1e-6, time.monotonic() - start)
        print("发送结束：成功 %d 帧，耗时 %.2f s，平均 %.1f fps，%.2f MB/s" % (
            sent, elapsed, sent / elapsed, sent * len(packet) / elapsed / 1e6))
    finally:
        session.close()
    return 0


# ---------------------------------------------------------------------------
# flash
# ---------------------------------------------------------------------------
def cmd_flash(args) -> int:
    image = firmware.prepare_image(args.image, no_decrypt=args.no_decrypt,
                                   force=args.force)
    print("== 镜像分析 ==")
    print(image.format())
    if args.sha256:
        expected = args.sha256.strip().lower().replace(":", "")
        if expected and expected != image.payload_sha256:
            raise GlxssError("SHA256 不匹配：传入 %s，实际将发送 %s。未写入任何数据。" % (
                expected, image.payload_sha256))
        print("  SHA256 校验: 通过")

    if not args.yes:
        # 只枚举，不打开/不写；枚举失败也不影响 dry-run 结论。
        try:
            boot = usb.find_boot(required=False)
            normal = usb.find_normal(required=False)
        except GlxssError as exc:
            boot = normal = None
            print("[信息] 无法枚举 USB（dry-run 继续）: %s" % exc)
        if boot is not None:
            print("检测到引导模式 03e7:2150；加 --yes 真写。")
        elif normal is not None:
            print("检测到正常模式 2e09:0030，但没有引导模式 03e7:2150。")
        else:
            print("未检测到 03e7:2150；让眼镜进入 Myriad ROM 引导模式后再加 --yes。")
        print("DRY-RUN：未打开设备、未写入任何数据。")
        return 0

    if not image.valid_magic and not args.force:
        raise GlxssError("镜像 magic 不是 MA2x，拒绝写入；确认文件后加 --force。")
    if image.version is None and not args.force:
        raise GlxssError("镜像未找到 G25_MainFW_ 版本串，拒绝写入；确认后加 --force。")

    dev = usb.find_boot()
    print("设备: " + usb.device_label(dev))
    intf, ep = usb.pick_bulk_out(dev)
    print("  端点: if=%d alt=%d class=0x%02x ep=0x%02x" % (
        intf.bInterfaceNumber, intf.bAlternateSetting,
        intf.bInterfaceClass, ep.bEndpointAddress))
    print("\n!!! 即将向 03e7:2150 写入固件 !!!")
    print("  SHA256: " + image.payload_sha256)
    print("  大小  : %d B，%d 包 × %d B；写完后设备会重启并重枚举。"
          % (image.size, (image.size + 1023) // 1024, firmware.BOOT_CHUNK))
    with usb.DeviceSession(dev, detach=args.detach, verbose=args.verbose) as session:
        session.claim_interface(intf, label="BOOT")
        result = firmware.flash(dev, image, dry_run=False, timeout=args.timeout,
                                force=args.force)
    print("写出结束：%d/%d B，耗时 %.2f s，平均 %.2f MB/s%s" % (
        result.sent, result.total, result.elapsed, result.rate_mbps,
        "（设备已断开）" if result.gone else ""))
    if not result.complete and not result.gone:
        print("[警告] 未写完。", file=sys.stderr)
        return 1
    print("等待重枚举为 2e09:0030（最多 8 s）...")
    if firmware.wait_for_normal(8.0):
        print("已检测到 2e09:0030；建议运行 `glxss version` 确认版本。")
    else:
        print("暂未检测到 2e09:0030；稍等几秒后运行 `glxss list` 确认。")
    return 0


# ---------------------------------------------------------------------------
# cast
# ---------------------------------------------------------------------------
def cmd_cast(args) -> int:
    if args.fps is None:
        args.fps = {"hud": 2.0, "uvc": 10.0}.get(args.source, 15.0)
    if args.fps < 0:
        raise GlxssError("--fps 不能为负")
    if args.duration < 0:
        raise GlxssError("--duration 不能为负")
    if args.source == "hud":
        _hud_defaults(args)
        return cmd_hud(args)
    if args.source == "uvc":
        if args.fit is None:
            args.fit = "cover"
        return _cast_uvc(args)
    if args.fit is None:
        args.fit = "fit"
    source = capture.create_source(args.source, monitor=args.monitor,
                                   test_pattern=args.test_pattern)
    session = None
    try:
        if args.dry_run:
            print("DRY-RUN：采集源=%s，不打开眼镜。" % source.name)
            app = CastApp(source, sender=None, fps=args.fps, fit=args.fit,
                          rotate=args.rotate, duration=args.duration,
                          max_frames=args.frames,
                          on_stats=lambda st: print("[%s] %s" % (
                              time.strftime("%H:%M:%S"), st.line())))
            stats = app.run()
            print(stats_summary(stats))
            return 0

        dev = usb.find_normal()
        print("设备: " + usb.device_label(dev))
        session = usb.DeviceSession(dev, detach=args.detach, verbose=args.verbose)
        intf, ep = usb.pick_bulk_out(dev)
        session.claim_interface(intf, label="LCD")
        link = lcd.LcdLink(dev, ep.bEndpointAddress, timeout=args.timeout)
        if not args.no_init:
            try:
                link.initialize(args.timeout)
                print("已开屏并切到投屏模式。")
            except GlxssError as exc:
                print("[警告] 初始化失败（继续投屏）: %s" % exc, file=sys.stderr)
        print("开始投屏：源=%s fit=%s fps=%s rot=%d（Ctrl-C 停止）" % (
            source.name, args.fit, args.fps if args.fps > 0 else "不限",
            args.rotate))
        app = CastApp(source, sender=link.send_packet, fps=args.fps, fit=args.fit,
                      rotate=args.rotate, duration=args.duration,
                      max_frames=args.frames,
                      on_stats=lambda st: print("[%s] %s" % (
                          time.strftime("%H:%M:%S"), st.line())))
        try:
            stats = app.run()
        except KeyboardInterrupt:
            print("\n已停止投屏（Ctrl-C）。")
            return 0
        print(stats_summary(stats))
    finally:
        source.close()
        if session is not None:
            session.close()
    return 0


def _cast_uvc(args) -> int:
    """独立相机监看：claim LCD+HID+UVC，UVC 读帧线程 + LCD 发送线程。"""
    if args.dry_run:
        raise GlxssError(
            "uvc 源需要眼镜在线，不能 dry-run。\n"
            "  离线预览 HUD 用 `glxss hud --dry-run --save-png x.png`；\n"
            "  验证投屏管线用 `glxss cast --source test --dry-run`。")
    if args.listen:
        print("[警告] --listen 只用于 HUD 源；独立 uvc 监看不启动控制 socket。",
              file=sys.stderr)
    render_interval = (1.0 / args.fps) if args.fps > 0 else 0.5
    hud_source = hud.HudSource(brightness=args.brightness, demo=args.demo,
                               heading_offset=args.heading_offset,
                               render_interval=max(0.1, render_interval))
    hud_source.set_fps(args.fps)
    gate = usb.UsbGate(verbose=args.verbose)
    session = None
    monitor_source = None

    def on_stats(stats) -> None:
        extra = ""
        if monitor_source is not None:
            monitor_stats = monitor_source.stats_dict()
            extra = " | uvc %.1f fps 读 %.0f ms 解码 %.0f ms 旧帧 %d 错 %d" % (
                monitor_stats.get("read_fps", 0.0),
                monitor_stats.get("read_ms", 0.0),
                monitor_stats.get("decode_ms", 0.0),
                monitor_stats.get("frames_stale", 0),
                int(monitor_stats.get("read_errors", 0)) +
                int(monitor_stats.get("decode_errors", 0)))
        print("[%s] %s%s" % (time.strftime("%H:%M:%S"), stats.line(), extra))

    try:
        dev = usb.find_normal()
        print("设备: " + usb.device_label(dev))
        session = usb.DeviceSession(dev, detach=args.detach, verbose=args.verbose)
        intf, ep = usb.pick_bulk_out(dev)
        session.claim_interface(intf, label="LCD")
        link = lcd.LcdLink(dev, ep.bEndpointAddress, timeout=args.timeout)
        if not args.no_init:
            try:
                link.initialize(args.timeout)
                print("已开屏并切到投屏模式。")
            except GlxssError as exc:
                print("[警告] 初始化失败（继续监看）: %s" % exc, file=sys.stderr)
        if not args.demo:
            try:
                with gate:
                    session.claim(usb.IFACE_HID, label="HID")
                    print("  使能传感器（加速度计做水平仪）...")
                    for line in hid.enable_sensors(dev, args.timeout, verbose=False):
                        print("    " + line)
                    version = hid.read_version(dev, args.timeout)
                    hud_source.set_firmware(version.version)
                    print("  固件: " + (version.version or "UNKNOWN"))
                hud_source.attach_hid(dev, interval=0.1,
                                      timeout=min(1500, max(200, args.timeout)),
                                      lock=gate)
            except GlxssError as exc:
                print("[警告] HID/传感器不可用，水平仪无数据: %s" % exc,
                      file=sys.stderr)
        hud_source.set_connected(True)
        print("打开 UVC（需要 --detach 才能在 Linux 上绕过 uvcvideo）...")
        monitor_source = monitor.MonitorSource.open(
            dev, session, gate=gate,
            timeout=min(500, max(200, args.timeout)),
            fit=args.fit, half=args.uvc_half, camera_fps=args.camera_fps,
            rec=args.rec, state_provider=hud_source.snapshot,
            verbose=args.verbose)
        print("开始相机监看：fps=%s fit=%s half=%s rec=%s（Ctrl-C 停止）" % (
            args.fps if args.fps > 0 else "不限", args.fit, args.uvc_half,
            args.rec))
        app = CastApp(monitor_source,
                      sender=lambda packet: _locked_send(gate, link, packet),
                      fps=args.fps, fit="stretch", rotate=args.rotate,
                      duration=args.duration, max_frames=args.frames,
                      on_stats=on_stats)
        try:
            stats = app.run()
        except KeyboardInterrupt:
            print("\n已停止相机监看（Ctrl-C）。")
            return 0
        print(stats_summary(stats))
        monitor_stats = monitor_source.stats_dict()
        print("UVC 统计：读帧 %d、读错误 %d、解码错误 %d、超时 %d" % (
            monitor_stats.get("frames_ready", 0),
            monitor_stats.get("read_errors", 0),
            monitor_stats.get("decode_errors", 0),
            monitor_stats.get("timeouts", 0)))
        if monitor_stats.get("last_error"):
            print("  最近错误: " + str(monitor_stats["last_error"]))
        if int(monitor_stats.get("frames_ready", 0)) == 0:
            print("[错误] UVC 没有读到任何完整帧；检查 --detach、摄像头占用和固件状态"
                  "（probe/commit 成功但 0 帧见 docs/device-recovery.md）。",
                  file=sys.stderr)
            return 1
    finally:
        if monitor_source is not None:
            monitor_source.close()
        hud_source.close()
        if session is not None:
            session.close()
    return 0


# ---------------------------------------------------------------------------
# hud
# ---------------------------------------------------------------------------
def _as_bool(value) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    return str(value).strip().lower() in ("1", "on", "true", "yes", "open")


def _hud_defaults(args) -> None:
    """让 `cast --source hud` 复用 `hud` 子命令的参数。"""
    for name, default in (("brightness", 180), ("demo", False),
                          ("heading_offset", 0.0), ("listen", False),
                          ("socket", None), ("save_png", None),
                          ("view", "hud"), ("rec", False),
                          ("camera_fps", 15.0), ("uvc_half", False),
                          ("monitor_fit", "cover"), ("rotate", 0)):
        if not hasattr(args, name):
            setattr(args, name, default)


def cmd_hud(args) -> int:
    _hud_defaults(args)
    if args.fps < 0:
        raise GlxssError("--fps 不能为负（0 表示不限速）")
    if not 0 <= args.brightness <= 255:
        raise GlxssError("亮度必须在 0..255（实测有效范围 100..254）")
    if args.duration < 0:
        raise GlxssError("--duration 不能为负")
    if args.dry_run:
        return _hud_dry_run(args)
    return _hud_live(args)


def _hud_dry_run(args) -> int:
    """离线渲染一张 HUD：覆盖图层、字体降级、RGBA 打包和 PNG 保存。"""
    import numpy as np

    source = hud.HudSource(brightness=args.brightness, demo=True,
                           heading_offset=args.heading_offset)
    source.set_fps(args.fps)
    image = source.render()
    if int(getattr(args, "rotate", 0) or 0) % 360 == 180:
        image = image.rotate(180)
    state = source.snapshot()
    packet = lcd.build_frame(capture.rgba_bytes(np.asarray(image, dtype=np.uint8)))
    _print_frame_header(packet, "HUD 640×400 RGBA（演示数据）")
    print("  图层    : 顶栏(时间/连接/fps) + 罗盘 + lux/传感器 + 姿态 + 底栏(亮度/固件)")
    print("  heading : %6.1f deg（罗盘 X/Y 原始值推算）" % state.heading)
    pitch, roll = state.pitch_roll
    print("  姿态    : pitch=%+.1f° roll=%+.1f°" % (pitch, roll))
    print("  传感器  : lux=%d 罗盘=%s" % (
        state.lux, "OK" if state.compass_valid else "NO DATA"))
    if state.nav is not None:
        print("  导航占位: bearing=%.0f° distance=%.0f m label=%r" % (
            state.nav.bearing, state.nav.distance, state.nav.label))
    if args.save_png:
        source.save_png(args.save_png, rotate=args.rotate)
        print("  已保存 640×400 RGBA PNG: " + args.save_png)
    print("DRY-RUN：只渲染 HUD，不打开设备、不发送。")
    return 0


def _hud_live(args) -> int:
    """HUD 主进程：claim LCD + HID，按 --fps 发帧；可随时切相机监看。"""
    render_interval = (1.0 / args.fps) if args.fps > 0 else 0.05
    hud_source = hud.HudSource(brightness=args.brightness, demo=args.demo,
                               heading_offset=args.heading_offset,
                               render_interval=max(0.05, render_interval))
    hud_source.set_fps(args.fps)
    session = None
    server = None
    app = None
    mode_source = None
    gate = usb.UsbGate(verbose=args.verbose)
    mode = {"value": 2}
    screen = {"value": True}
    sock_path = None

    def status_dict():
        state = hud_source.snapshot()
        data = {
            "connected": state.connected,
            "fps": round(state.fps, 1),
            "brightness": state.brightness,
            "mode": mode["value"],
            "screen": screen["value"],
            "hud": state.hud_enabled,
            "lux": state.lux,
            "firmware": state.firmware,
            "time": time.strftime("%H:%M:%S"),
            "source": mode_source.mode if mode_source is not None else "hud",
            "rotate": args.rotate,
            "socket": sock_path or "",
            "error": hud_source.hid_error,
        }
        if mode_source is not None:
            data.update(mode_source.status_dict())
        return data

    def on_stats(stats) -> None:
        hud_source.set_fps(stats.fps)
        extra = ""
        if mode_source is not None and mode_source.mode == "monitor":
            monitor = mode_source.monitor
            if monitor is not None:
                m = monitor.stats_dict()
                extra = " | uvc %.1f fps 解码 %.0f ms 旧帧 %d" % (
                    m.get("read_fps", 0.0), m.get("decode_ms", 0.0),
                    m.get("frames_stale", 0))
        print("[%s] %s%s" % (time.strftime("%H:%M:%S"), stats.line(), extra))

    def on_fn_key(report) -> None:
        if mode_source is None:
            return
        target = mode_source.request_toggle()
        print("[%s] Fn 键单击：视图 → %s" % (time.strftime("%H:%M:%S"), target))

    try:
        dev = usb.find_normal()
        print("设备: " + usb.device_label(dev))
        session = usb.DeviceSession(dev, detach=args.detach, verbose=args.verbose)
        intf, ep = usb.pick_bulk_out(dev)
        session.claim_interface(intf, label="LCD")
        link = lcd.LcdLink(dev, ep.bEndpointAddress, timeout=args.timeout)
        if not args.no_init:
            try:
                link.initialize(args.timeout)
                print("已开屏并切到投屏模式。")
            except GlxssError as exc:
                print("[警告] 初始化失败（继续发 HUD）: %s" % exc, file=sys.stderr)
        try:
            with gate:
                status = link.status(args.timeout)
            screen["value"] = status.screen_open
            hud_source.set_brightness(status.luminance)
            print("  回读: " + status.format())
        except GlxssError as exc:
            print("[警告] 回读 LCD 状态失败: %s" % exc, file=sys.stderr)

        def open_monitor():
            return monitor.MonitorSource.open(
                dev, session, gate=gate,
                timeout=min(500, max(200, args.timeout)),
                fit=args.monitor_fit, half=args.uvc_half,
                camera_fps=args.camera_fps, rec=args.rec,
                state_provider=hud_source.snapshot, verbose=args.verbose)

        mode_source = monitor.ModeSource(hud_source, open_monitor,
                                         initial=args.view)

        if not args.demo:
            try:
                with gate:
                    session.claim(usb.IFACE_HID, label="HID")
                    print("  使能传感器（光感/罗盘/加速度/陀螺）...")
                    for line in hid.enable_sensors(dev, args.timeout, verbose=False):
                        print("    " + line)
                    version = hid.read_version(dev, args.timeout)
                    hud_source.set_firmware(version.version)
                    print("  固件: " + (version.version or "UNKNOWN"))
                hud_source.attach_hid(dev, interval=0.1,
                                      timeout=min(1500, max(200, args.timeout)),
                                      lock=gate, on_key=on_fn_key)
            except GlxssError as exc:
                print("[警告] HID/传感器不可用，HUD 只显示时钟/连接状态: %s"
                      % exc, file=sys.stderr)
        hud_source.set_connected(True)

        if args.save_png:
            hud_source.save_png(args.save_png, rotate=args.rotate)
            print("  已保存首张 HUD PNG: " + args.save_png)

        def switch_view(value):
            try:
                mode_source.request_view(str(value))
            except GlxssError as exc:
                return {"ok": False, "error": str(exc)}
            return None

        def handle(request):
            cmd = str(request.get("cmd", "")).strip().lower()
            if cmd in ("", "status", "ping"):
                response = {"ok": True, "status": status_dict()}
                if cmd == "ping":
                    response["pong"] = True
                return response
            if cmd in ("bright", "brightness"):
                value = int(request.get("value", hud_source.snapshot().brightness))
                if not 0 <= value <= 255:
                    return {"ok": False, "error": "亮度必须在 0..255"}
                with gate:
                    ack = link.brightness(value, args.timeout)
                hud_source.set_brightness(value)
                return {"ok": True, "ack": ack, "status": status_dict()}
            if cmd == "screen":
                value = _as_bool(request.get("value", True))
                with gate:
                    ack = link.screen(value, args.timeout)
                    if value:
                        link.mode(2, args.timeout)
                screen["value"] = value
                mode["value"] = 2 if value else 1
                return {"ok": True, "ack": ack, "status": status_dict()}
            if cmd in ("view", "mode"):
                raw = request.get("value", "monitor" if cmd == "view" else 2)
                text = str(raw).strip().lower()
                if cmd == "mode" and text not in ("hud", "monitor", "uvc"):
                    try:
                        value = int(raw)
                    except (TypeError, ValueError):
                        return {"ok": False, "error": "mode 需要 0..2 或 hud/monitor"}
                    if not 0 <= value <= 2:
                        return {"ok": False, "error": "显示模式必须在 0..2"}
                    with gate:
                        ack = link.mode(value, args.timeout)
                    mode["value"] = value
                    return {"ok": True, "ack": ack, "status": status_dict()}
                error = switch_view(text)
                if error is not None:
                    return error
                return {"ok": True, "status": status_dict()}
            if cmd == "rec":
                mode_source.set_rec(_as_bool(request.get("value", True)))
                return {"ok": True, "status": status_dict()}
            if cmd == "hud":
                enabled = _as_bool(request.get("value", True))
                hud_source.set_hud_enabled(enabled)
                return {"ok": True, "status": status_dict()}
            if cmd == "test":
                seconds = float(request.get("seconds", 3.0))
                if mode_source.mode != "hud":
                    mode_source.request_view("hud")
                hud_source.flash_test(max(0.2, seconds))
                return {"ok": True, "status": status_dict()}
            if cmd == "nav":
                hud_source.set_nav(float(request.get("bearing", 0.0)),
                                   float(request.get("distance", 0.0)),
                                   str(request.get("label", "")))
                return {"ok": True, "status": status_dict()}
            if cmd == "nav-clear":
                hud_source.clear_nav()
                return {"ok": True, "status": status_dict()}
            if cmd == "quit":
                if app is not None:
                    app.stop()
                return {"ok": True, "message": "正在退出"}
            return {"ok": False, "error": "未知命令 %r" % cmd}

        app = CastApp(mode_source,
                      sender=lambda packet: _locked_send(gate, link, packet),
                      fps=args.fps, fit="stretch", rotate=args.rotate,
                      duration=args.duration, max_frames=args.frames,
                      close_source=False, on_stats=on_stats)
        if args.listen:
            sock_path = args.socket or control.default_socket_path()
            server = control.ControlServer(sock_path, handle)
            server.start()
            print("控制 socket: " + sock_path)
            print("  HAT: pi/lcdhat.py --socket %s" % sock_path)
        print("开始 %s：fps=%s fit=%s rot=%d，%s" % (
            "相机监看" if args.view == "monitor" else "HUD",
            args.fps if args.fps > 0 else "不限", args.monitor_fit,
            args.rotate,
            "按 Ctrl-C 停止" if not args.listen else "Ctrl-C 或 ctl quit 停止"))
        stats = app.run()
        print(stats_summary(stats))
        return 0
    finally:
        if server is not None:
            server.stop()
        if mode_source is not None:
            mode_source.close()
        else:
            hud_source.close()
        if session is not None:
            session.close()


def _locked_send(lock, link, packet: bytes) -> int:
    with lock:
        return link.send_packet(packet)


def _print_ctl_status(status) -> None:
    print("  眼镜    : %s" % ("ONLINE" if status.get("connected") else "OFFLINE"))
    print("  fps     : %.1f" % float(status.get("fps", 0.0)))
    print("  亮度    : %s" % status.get("brightness"))
    print("  视图    : %s" % (status.get("view") or "hud"))
    print("  显示模式: %s" % status.get("mode"))
    print("  屏      : %s" % ("开" if status.get("screen") else "关"))
    print("  HUD     : %s" % ("ON" if status.get("hud") else "PAUSED"))
    print("  录制红点: %s" % ("ON" if status.get("rec") else "OFF"))
    print("  光感    : %s lux" % status.get("lux"))
    print("  固件    : %s" % (status.get("firmware") or "UNKNOWN"))
    monitor = status.get("monitor")
    if isinstance(monitor, dict):
        print("  UVC     : %.1f fps 读 %.0f ms 解码 %.0f ms 旧帧 %d" % (
            float(monitor.get("read_fps", 0.0)),
            float(monitor.get("read_ms", 0.0)),
            float(monitor.get("decode_ms", 0.0)),
            int(monitor.get("frames_stale", 0))))
    print("  时间    : %s" % status.get("time"))
    if status.get("socket"):
        print("  socket  : %s" % status.get("socket"))
    if status.get("monitor_error"):
        print("  UVC 错误: %s" % status.get("monitor_error"))
    if status.get("error"):
        print("  错误    : %s" % status.get("error"))


def cmd_ctl(args) -> int:
    client = control.ControlClient(args.socket,
                                   timeout=max(0.2, args.timeout / 1000.0))
    action = args.action
    value = args.value
    payload = {}

    if action in ("status", "ping", "quit", "nav-clear"):
        if value:
            raise GlxssError("%s 不接受参数 %r" % (action, value))
    elif action == "bright":
        if value is None:
            raise GlxssError("bright 需要亮度值（100..254）")
        try:
            payload["value"] = int(value)
        except ValueError as exc:
            raise GlxssError("亮度必须是整数: %r" % value) from exc
    elif action == "screen":
        if value is None:
            raise GlxssError("screen 需要 on/off")
        payload["value"] = _as_bool(value)
    elif action == "mode":
        if value is None:
            raise GlxssError("mode 需要 0/1/2 或 hud/monitor")
        text = str(value).strip().lower()
        if text in ("hud", "monitor", "uvc"):
            payload["value"] = "monitor" if text == "uvc" else text
        else:
            try:
                payload["value"] = int(value)
            except ValueError as exc:
                raise GlxssError("mode 需要 0/1/2 或 hud/monitor: %r" % value) from exc
    elif action == "view":
        if value is None:
            raise GlxssError("view 需要 hud 或 monitor")
        text = str(value).strip().lower()
        if text not in ("hud", "monitor", "uvc"):
            raise GlxssError("view 需要 hud 或 monitor，收到 %r" % value)
        payload["value"] = "monitor" if text == "uvc" else text
    elif action == "rec":
        if value is None:
            raise GlxssError("rec 需要 on/off")
        payload["value"] = _as_bool(value)
    elif action == "hud":
        if value is None:
            raise GlxssError("hud 需要 on/off")
        payload["value"] = _as_bool(value)
    elif action == "test":
        try:
            payload["seconds"] = float(value) if value else 3.0
        except ValueError as exc:
            raise GlxssError("测试帧时长必须是秒数: %r" % value) from exc
    elif action == "nav":
        if value is None:
            raise GlxssError("nav 需要方位角（度）")
        try:
            payload["bearing"] = float(value)
        except ValueError as exc:
            raise GlxssError("方位角必须是数字: %r" % value) from exc
        payload["distance"] = float(args.distance)
        payload["label"] = args.label

    response = client.request(action, **payload)
    if args.json:
        print(json.dumps(response, ensure_ascii=False, indent=2))
        return 0
    if not response.get("ok"):
        raise GlxssError(str(response.get("error", "控制命令失败")))
    status = response.get("status")
    if action == "status" and isinstance(status, dict):
        _print_ctl_status(status)
    elif action == "ping":
        print("OK：控制服务在线")
    elif isinstance(status, dict):
        print("OK：%s" % action)
        _print_ctl_status(status)
    else:
        print("OK：%s" % action)
    return 0


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "command", None):
        parser.print_help()
        return 0
    try:
        return int(args.func(args) or 0)
    except KeyboardInterrupt:
        print("\n已中断。", file=sys.stderr)
        return 130
    except GlxssError as exc:
        print("[错误] " + str(exc), file=sys.stderr)
        return 1
    except BrokenPipeError:
        return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
