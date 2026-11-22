# -*- coding: utf-8 -*-
"""glxss 命令行入口（argparse）。

子命令：
  list / status / bright / screen / mode / roll / eeprom / version
  hid / uvc / frame / flash / cast

所有硬件命令都复用 glxss.usb / lcd / hid / uvc / firmware；离线命令
（--help、list 之外的 dry-run、frame 生成）不要求眼镜在线。
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import time
from typing import Optional, Tuple

from . import __version__, capture, firmware, hid, lcd, usb, uvc
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


def _add_fit_arg(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--fit", choices=("fit", "cover", "crop", "stretch"),
                        default="fit",
                        help="映射：fit=留黑边（默认）/ cover=裁切 / stretch=拉伸")


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
                   choices=("test", "bars", "black", "solid", "image", "png"),
                   help="test(默认)/bars/black/solid/image/png")
    p.add_argument("path", nargs="?", help="image/png 的路径；solid 时可直接给颜色")
    p.add_argument("--color", default=None, help="solid 颜色，例如 ff8000")
    _add_fit_arg(p)
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
    p = sub.add_parser("cast", help="实时投屏（屏幕/测试图案 → 眼镜）")
    p.add_argument("--source", choices=("screen", "test"), default="screen",
                   help="采集源：screen（mss）或 test（默认屏幕）")
    p.add_argument("--monitor", type=int, default=1, help="mss 显示器编号（0=全部）")
    p.add_argument("--fps", type=float, default=15.0, help="目标帧率（默认 15，0=不限速）")
    _add_fit_arg(p)
    p.add_argument("--duration", type=float, default=0.0, help="持续秒数（0=一直）")
    p.add_argument("--frames", type=int, default=0, help="发送帧数上限（0=不限）")
    p.add_argument("--dry-run", action="store_true", help="只采集/映射，不打开眼镜")
    p.add_argument("--test-pattern", choices=("bars", "gradient"), default="bars",
                   help="--source test 时的图案")
    p.add_argument("--no-init", action="store_true", help="不先发开屏/模式命令")
    _add_device_args(p, timeout=5000)
    p.set_defaults(func=cmd_cast)

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
    if kind in ("image", "png"):
        if not args.path:
            raise GlxssError("frame %s 需要一个图片路径" % kind)
        return capture.load_rgb_image(args.path), "图片 %s" % args.path
    raise GlxssError("未知帧类型 %r" % kind)


def _frame_packet(args):
    rgb, desc = _resolve_frame_source(args)
    rgba = capture.map_to_rgba(rgb, mode=args.fit)
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
    if args.fps < 0:
        raise GlxssError("--fps 不能为负")
    if args.duration < 0:
        raise GlxssError("--duration 不能为负")
    source = capture.create_source(args.source, monitor=args.monitor,
                                   test_pattern=args.test_pattern)
    session = None
    try:
        if args.dry_run:
            print("DRY-RUN：采集源=%s，不打开眼镜。" % source.name)
            app = CastApp(source, sender=None, fps=args.fps, fit=args.fit,
                          duration=args.duration, max_frames=args.frames,
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
        print("开始投屏：源=%s fit=%s fps=%s（Ctrl-C 停止）" % (
            source.name, args.fit, args.fps if args.fps > 0 else "不限"))
        app = CastApp(source, sender=link.send_packet, fps=args.fps, fit=args.fit,
                      duration=args.duration, max_frames=args.frames,
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
