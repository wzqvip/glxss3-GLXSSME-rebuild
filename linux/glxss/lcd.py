# -*- coding: utf-8 -*-
"""LCD：640×400 RGBA 帧 + 亮度/开关/模式/roll/状态/EEPROM。

协议出处 docs/ch-display.md：
  * 帧：16 B 头（format=1，totalLen 含头）+ 640×400×4 裸 RGBA，
    bulk OUT EP 0x01（接口 3 的 vendor-specific 接口）。
  * 控制：bmRequestType=0xA1，wIndex=3，参数放 wValue；写入也是 IN 方向 1 B 的
    “哑读”，设备用返回的 ack 确认。
  * 状态：0xA1/0x04 wLength=8；EEPROM：0xA1/0x07 wValue=0x0100 wLength=264。
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Dict, List

from . import usb
from .errors import GlxssError, ProtocolError
from .util import fmt_ascii, fmt_hex, hexs

FRAME_W = 640
FRAME_H = 400
FRAME_PAYLOAD = FRAME_W * FRAME_H * 4  # 1,024,000
FRAME_HEADER = 16
FRAME_TOTAL = FRAME_HEADER + FRAME_PAYLOAD  # 1,024,016

LCD_IFACE = usb.IFACE_LCD
EEPROM_LEN = 264
EEPROM_HEAD_LEN = 8


@dataclass
class LcdStatus:
    raw: bytes
    width: int
    height: int
    screen_open: bool
    luminance: int
    roll: int
    byte7: int

    @classmethod
    def parse(cls, data: bytes) -> "LcdStatus":
        if len(data) < 8:
            raise ProtocolError("LCD 状态只有 %d 字节（预期 8）" % len(data))
        return cls(
            raw=bytes(data[:8]),
            width=struct.unpack_from("<H", data, 0)[0],
            height=struct.unpack_from("<H", data, 2)[0],
            screen_open=data[4] != 0,
            luminance=data[5],
            roll=struct.unpack_from("<H", data, 6)[0],
            byte7=data[7],
        )

    def format(self) -> str:
        return ("%d×%d 屏=%s 亮度=%d roll=%d raw=%s" % (
            self.width, self.height, "开" if self.screen_open else "关",
            self.luminance, self.roll, hexs(self.raw)))


def build_frame(payload: bytes) -> bytes:
    """把 640×400 RGBA 像素打包成设备要的 1,024,016 B 帧。"""
    if len(payload) != FRAME_PAYLOAD:
        raise GlxssError("帧像素必须是 %d 字节，实际 %d" % (FRAME_PAYLOAD, len(payload)))
    # [0]=format=1, [1..3]=0, [4..7]=totalLen(LE，含 16 B 头), [8..15]=0
    return (bytes((1, 0, 0, 0)) +
            struct.pack("<I", FRAME_HEADER + len(payload)) +
            bytes(8) + payload)


def send_packet(dev, ep_addr: int, packet: bytes, timeout: int = 5000) -> int:
    if len(packet) != FRAME_TOTAL:
        raise GlxssError("帧包必须是 %d 字节，实际 %d" % (FRAME_TOTAL, len(packet)))
    return usb.bulk_write_all(dev, ep_addr, packet, timeout=timeout)


def send_payload(dev, ep_addr: int, payload: bytes, timeout: int = 5000) -> int:
    return send_packet(dev, ep_addr, build_frame(payload), timeout=timeout)


def read_status(dev, timeout: int = 2000) -> LcdStatus:
    data = usb.ctrl_in(dev, 0x04, 0, LCD_IFACE, 8, timeout)
    return LcdStatus.parse(data)


def _control_write(dev, request: int, value: int, label: str,
                   timeout: int = 2000) -> int:
    """控制命令实际是 1 B IN 哑读；返回 ack 字节，便于日志。"""
    data = usb.ctrl_in(dev, request, value, LCD_IFACE, 1, timeout)
    ack = data[0] if data else 0
    return ack


def set_brightness(dev, value: int, timeout: int = 2000) -> int:
    if not 0 <= value <= 255:
        raise GlxssError("亮度必须在 0..255（实测有效范围 100..254）")
    return _control_write(dev, 0x01, value, "亮度", timeout)


def set_screen(dev, on: bool, timeout: int = 2000) -> int:
    return _control_write(dev, 0x03, 1 if on else 0, "屏开关", timeout)


def set_mode(dev, value: int, timeout: int = 2000) -> int:
    if not 0 <= value <= 2:
        raise GlxssError("显示模式必须在 0..2（1=待机，2=投屏）")
    return _control_write(dev, 0x05, value, "显示模式", timeout)


def set_roll(dev, value: int, timeout: int = 2000) -> int:
    if not 0 <= value <= 0xFFFF:
        raise GlxssError("roll 必须在 0..65535")
    return _control_write(dev, 0x02, value, "roll", timeout)


def set_auto_brightness(dev, on: bool, timeout: int = 2000) -> int:
    """0xA1/0x10 wIndex=2（HID/AUX 接口），原厂自动亮度开关。"""
    data = usb.ctrl_in(dev, 0x10, 1 if on else 0, usb.IFACE_HID, 1, timeout)
    return data[0] if data else 0


def initialize(dev, timeout: int = 2000) -> None:
    """开屏 + 切投屏模式；只发帧不会点亮面板。"""
    set_screen(dev, True, timeout)
    set_mode(dev, 2, timeout)


def read_eeprom(dev, timeout: int = 2000) -> bytes:
    return usb.ctrl_in(dev, 0x07, 0x0100, LCD_IFACE, EEPROM_LEN, timeout)


def parse_eeprom(raw: bytes) -> Dict[str, object]:
    info = raw[EEPROM_HEAD_LEN:]
    blank = bool(info) and (all(b == 0xFF for b in info) or all(b == 0x00 for b in info))
    res_raw = info[60:64]
    if len(res_raw) == 4:
        width = (res_raw[0] << 8) | res_raw[1]
        height = (res_raw[2] << 8) | res_raw[3]
        res_guess = ("%d×%d（推测，未验证）" % (width, height)
                     if (width and height and not blank) else "UNKNOWN")
    else:
        res_guess = "（数据不足）"
    return {
        "info": info,
        "blank": blank,
        "head": raw[:EEPROM_HEAD_LEN],
        "platform": fmt_hex(info, 0, 1),
        "product": fmt_ascii(info, 1, 7),
        "firm": fmt_hex(info, 8, 2),
        "bsn": fmt_ascii(info, 10, 16),
        "psn": fmt_ascii(info, 26, 16),
        "bom": fmt_ascii(info, 42, 2),
        "isp": fmt_ascii(info, 44, 8),
        "firmware": fmt_ascii(info, 52, 8),
        "res_raw": fmt_hex(info, 60, 4),
        "res_guess": res_guess,
        "reserved": fmt_hex(info, 64, 64),
        "custom": fmt_ascii(info, 128, 128),
    }


def format_eeprom(raw: bytes) -> List[str]:
    info = parse_eeprom(raw)
    lines = ["  EEPROM 原始长度: %d B（8 B 头 + 256 B 产品信息）" % len(raw),
             "  设备头(8B)  : %s" % hexs(info["head"])]
    if info["blank"]:
        lines.append("  结论        : 产品信息区全 0xFF/0x00 —— 未写入工厂信息")
    fields = [
        ("platform", "platformID"), ("product", "productID"), ("firm", "firmID"),
        ("bsn", "bsnID"), ("psn", "psnID"), ("bom", "bomID"), ("isp", "ispID"),
        ("firmware", "firmwareID"), ("res_raw", "resolution"),
        ("custom", "customFields"),
    ]
    for key, label in fields:
        value = info[key]
        if key == "res_raw":
            value = "%s → %s" % (value or "（空）", info["res_guess"])
        lines.append("  %-12s: %s" % (label, value if value else "（空）"))
    lines.append("  [64..127]   : UNKNOWN %s" % (info["reserved"] or "（空）"))
    return lines


class LcdLink:
    """已经打开/claim 的 LCD 接口封装，供 app.py 和 Pi 控制循环复用。"""

    def __init__(self, dev, ep_addr: int = 0x01, timeout: int = 5000):
        self.dev = dev
        self.ep_addr = ep_addr
        self.timeout = timeout

    @classmethod
    def from_device(cls, dev, timeout: int = 5000) -> "LcdLink":
        _, ep = usb.pick_bulk_out(dev)
        return cls(dev, ep.bEndpointAddress, timeout)

    def send_packet(self, packet: bytes) -> int:
        return send_packet(self.dev, self.ep_addr, packet, self.timeout)

    def send_payload(self, payload: bytes) -> int:
        return send_payload(self.dev, self.ep_addr, payload, self.timeout)

    def status(self, timeout: int = 2000) -> LcdStatus:
        return read_status(self.dev, timeout)

    def initialize(self, timeout: int = 2000) -> None:
        initialize(self.dev, timeout)

    def brightness(self, value: int, timeout: int = 2000) -> int:
        return set_brightness(self.dev, value, timeout)

    def screen(self, on: bool, timeout: int = 2000) -> int:
        return set_screen(self.dev, on, timeout)

    def mode(self, value: int, timeout: int = 2000) -> int:
        return set_mode(self.dev, value, timeout)

    def roll(self, value: int, timeout: int = 2000) -> int:
        return set_roll(self.dev, value, timeout)
