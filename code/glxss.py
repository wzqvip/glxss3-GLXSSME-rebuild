#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
GLXSS G25（亮亮视野）PC 端调试工具 —— 单文件 CLI

覆盖当前仓库已逆向清楚的协议：
  * LCD 显示：16 B 头 + 640x400 RGBA 帧（EP 0x01 bulk OUT）
  * LCD 控制：亮度 / 屏开关 / 显示模式 / roll / 状态 / EEPROM 产品信息
  * HID：28 B 传感器报告、24 B 版本串 + 设备 ID
  * UVC：标准 UVC 1.10 probe/commit + bulk IN 读帧（只读测试）
  * Myriad ROM boot：03e7:2150、1024 B/包、EP 0x01、10 s 超时（默认 dry-run）
  * App raw UVC dump -> PNG（YV12 / I420）

协议来源见 docs/：
  docs/ch-display.md  docs/ch-hid.md  docs/ch-uvc.md
  docs/firmware-boot-protocol.md  docs/ch-firmware.md

依赖：Python 3.8+，仅硬件命令需要 pyusb（pip install pyusb）。
      Windows 还需要 libusb-1.0.dll（以及设备绑定 WinUSB），Linux 需要 udev 权限。

安全：本工具不会写 EEPROM（危险命令 0x21/0x06 未实现）。
      flash 默认 dry-run，必须显式 --yes 才会向 03e7:2150 写数据。
"""

from __future__ import annotations

import argparse
import hashlib
import os
import struct
import sys
import time
import zlib
from typing import NoReturn

# ---------------------------------------------------------------------------
# 可选依赖：pyusb。没有安装时 --help / decode-uvc / flash dry-run 仍然可用。
# ---------------------------------------------------------------------------
try:  # pragma: no cover - 环境相关
    import usb.core  # type: ignore
    import usb.util  # type: ignore
    PYUSB_AVAILABLE = True
except Exception:  # ImportError 或 libusb 后端加载失败
    usb = None  # type: ignore
    PYUSB_AVAILABLE = False

if PYUSB_AVAILABLE:
    USBError = usb.core.USBError  # type: ignore[union-attr]
    NoBackendError = usb.core.NoBackendError  # type: ignore[union-attr]
else:
    class USBError(Exception):  # type: ignore[no-redef]
        pass

    class NoBackendError(Exception):  # type: ignore[no-redef]
        pass

# 可选加速：有 pycryptodome 时用它解 AES；没有就用本文件里的纯 Python 实现。
try:  # pragma: no cover - 环境相关
    from Crypto.Cipher import AES as _CRYPTO_AES  # type: ignore
except Exception:  # pragma: no cover
    _CRYPTO_AES = None

TOOL_VERSION = "1.0"

# 设备 ID（docs/firmware-boot-protocol.md §2）
VID_NORMAL, PID_NORMAL = 0x2E09, 0x0030
VID_BOOT, PID_BOOT = 0x03E7, 0x2150

# 接口号（docs/ch-display.md §3、docs/ch-hid.md §2）
IFACE_LCD = 3
IFACE_HID = 2
IFACE_BOOT = 0

# LCD 帧（docs/ch-display.md §2）
FRAME_W, FRAME_H = 640, 400
FRAME_PAYLOAD = FRAME_W * FRAME_H * 4  # 1,024,000
FRAME_HEADER = 16
FRAME_TOTAL = FRAME_PAYLOAD + FRAME_HEADER  # 1,024,016

# HID（docs/ch-hid.md §5）
HID_REPORT_LEN = 28
HID_PRODUCT_LEN = 24

# EEPROM（docs/ch-display.md §6 / lcd/ProductInfo.kt）
EEPROM_LEN = 264
EEPROM_HEAD_LEN = 8

# 固件引导（docs/firmware-boot-protocol.md §2）
BOOT_CHUNK = 1024
BOOT_TIMEOUT_MS = 10000
FW_TAIL_MAX = 0x80000
FW_AES_KEY = b"b5Q8JBFRYYIRawl3"

# UVC（docs/ch-uvc.md §2）
UVC_IFACE_CLASS = 0x0E
UVC_VC_SUBCLASS = 0x01
UVC_VS_SUBCLASS = 0x02
UVC_GET_CUR = 0x81
UVC_GET_MIN = 0x82
UVC_GET_MAX = 0x83
UVC_GET_DEF = 0x87
UVC_SET_CUR = 0x01
UVC_VS_PROBE = 0x0100
UVC_VS_COMMIT = 0x0200

KNOWN_DEVICES = {
    (VID_NORMAL, PID_NORMAL): "GLXSS G25 正常模式（LCD/HID/UVC）",
    (VID_BOOT, PID_BOOT): "GLXSS G25 引导模式（Myriad ROM boot）",
}


# ---------------------------------------------------------------------------
# 错误与输出
# ---------------------------------------------------------------------------
class ToolError(Exception):
    """可直接展示给用户的错误。"""


class DeviceGone(ToolError):
    """设备断开/重枚举（刷写成功时也会出现）。"""


def die(msg: str) -> NoReturn:
    raise ToolError(msg)


def out(msg: str = "") -> None:
    print(msg)


def warn(msg: str) -> None:
    print("[警告] " + msg, file=sys.stderr)


def info(msg: str) -> None:
    print("[信息] " + msg, file=sys.stderr)


def hexs(data, sep: str = " ") -> str:
    return sep.join("%02x" % b for b in bytes(data))


def hexdump(data, width: int = 16, prefix: str = "  ") -> str:
    data = bytes(data)
    lines = []
    for off in range(0, len(data), width):
        chunk = data[off:off + width]
        text = "".join(chr(b) if 0x20 <= b <= 0x7E else "." for b in chunk)
        lines.append(prefix + ("%04x  " % off) + hexs(chunk).ljust(width * 3 - 1) + "  " + text)
    return "\n".join(lines)


def u16le(b, off: int = 0) -> int:
    return struct.unpack_from("<H", b, off)[0]


def u32le(b, off: int = 0) -> int:
    return struct.unpack_from("<I", b, off)[0]


def s16le(b, off: int = 0) -> int:
    return struct.unpack_from("<h", b, off)[0]


def u32be(b, off: int = 0) -> int:
    return struct.unpack_from(">I", b, off)[0]


def now_str() -> str:
    t = time.time()
    return time.strftime("%H:%M:%S", time.localtime(t)) + (".%03d" % int((t % 1) * 1000))


def ctrl_line(direction: str, request: int, value: int, index: int, length: int,
              extra: str = "") -> str:
    return ("CTRL %-3s bmRequestType=0x%02x bRequest=0x%02x wValue=0x%04x "
            "wIndex=%d wLength=%d%s" % (
                direction, 0xA1 if direction == "IN" else 0x21,
                request, value, index, length, (" " + extra) if extra else ""))


def print_ctrl(direction: str, request: int, value: int, index: int, length: int,
               extra: str = "") -> None:
    print("  " + ctrl_line(direction, request, value, index, length, extra))


# ---------------------------------------------------------------------------
# AES-128-ECB 解密（纯 Python 兜底；用于解密固件尾部 512 KiB）
# 参考 docs/ch-firmware.md §3：key = "b5Q8JBFRYYIRawl3"，加密封在文件末尾 0x80000 B。
# ---------------------------------------------------------------------------
_AES_SBOX = bytes([
    0x63, 0x7C, 0x77, 0x7B, 0xF2, 0x6B, 0x6F, 0xC5, 0x30, 0x01, 0x67, 0x2B, 0xFE, 0xD7, 0xAB, 0x76,
    0xCA, 0x82, 0xC9, 0x7D, 0xFA, 0x59, 0x47, 0xF0, 0xAD, 0xD4, 0xA2, 0xAF, 0x9C, 0xA4, 0x72, 0xC0,
    0xB7, 0xFD, 0x93, 0x26, 0x36, 0x3F, 0xF7, 0xCC, 0x34, 0xA5, 0xE5, 0xF1, 0x71, 0xD8, 0x31, 0x15,
    0x04, 0xC7, 0x23, 0xC3, 0x18, 0x96, 0x05, 0x9A, 0x07, 0x12, 0x80, 0xE2, 0xEB, 0x27, 0xB2, 0x75,
    0x09, 0x83, 0x2C, 0x1A, 0x1B, 0x6E, 0x5A, 0xA0, 0x52, 0x3B, 0xD6, 0xB3, 0x29, 0xE3, 0x2F, 0x84,
    0x53, 0xD1, 0x00, 0xED, 0x20, 0xFC, 0xB1, 0x5B, 0x6A, 0xCB, 0xBE, 0x39, 0x4A, 0x4C, 0x58, 0xCF,
    0xD0, 0xEF, 0xAA, 0xFB, 0x43, 0x4D, 0x33, 0x85, 0x45, 0xF9, 0x02, 0x7F, 0x50, 0x3C, 0x9F, 0xA8,
    0x51, 0xA3, 0x40, 0x8F, 0x92, 0x9D, 0x38, 0xF5, 0xBC, 0xB6, 0xDA, 0x21, 0x10, 0xFF, 0xF3, 0xD2,
    0xCD, 0x0C, 0x13, 0xEC, 0x5F, 0x97, 0x44, 0x17, 0xC4, 0xA7, 0x7E, 0x3D, 0x64, 0x5D, 0x19, 0x73,
    0x60, 0x81, 0x4F, 0xDC, 0x22, 0x2A, 0x90, 0x88, 0x46, 0xEE, 0xB8, 0x14, 0xDE, 0x5E, 0x0B, 0xDB,
    0xE0, 0x32, 0x3A, 0x0A, 0x49, 0x06, 0x24, 0x5C, 0xC2, 0xD3, 0xAC, 0x62, 0x91, 0x95, 0xE4, 0x79,
    0xE7, 0xC8, 0x37, 0x6D, 0x8D, 0xD5, 0x4E, 0xA9, 0x6C, 0x56, 0xF4, 0xEA, 0x65, 0x7A, 0xAE, 0x08,
    0xBA, 0x78, 0x25, 0x2E, 0x1C, 0xA6, 0xB4, 0xC6, 0xE8, 0xDD, 0x74, 0x1F, 0x4B, 0xBD, 0x8B, 0x8A,
    0x70, 0x3E, 0xB5, 0x66, 0x48, 0x03, 0xF6, 0x0E, 0x61, 0x35, 0x57, 0xB9, 0x86, 0xC1, 0x1D, 0x9E,
    0xE1, 0xF8, 0x98, 0x11, 0x69, 0xD9, 0x8E, 0x94, 0x9B, 0x1E, 0x87, 0xE9, 0xCE, 0x55, 0x28, 0xDF,
    0x8C, 0xA1, 0x89, 0x0D, 0xBF, 0xE6, 0x42, 0x68, 0x41, 0x99, 0x2D, 0x0F, 0xB0, 0x54, 0xBB, 0x16,
])
_AES_INV_SBOX = bytes([0] * 256)
_inv = bytearray(256)
for _i, _v in enumerate(_AES_SBOX):
    _inv[_v] = _i
_AES_INV_SBOX = bytes(_inv)


def _gmul(a: int, b: int) -> int:
    p = 0
    for _ in range(8):
        if b & 1:
            p ^= a
        hi = a & 0x80
        a = (a << 1) & 0xFF
        if hi:
            a ^= 0x1B
        b >>= 1
    return p


_MUL9 = bytes(_gmul(i, 9) for i in range(256))
_MUL11 = bytes(_gmul(i, 11) for i in range(256))
_MUL13 = bytes(_gmul(i, 13) for i in range(256))
_MUL14 = bytes(_gmul(i, 14) for i in range(256))


def _aes_expand_key(key: bytes):
    if len(key) != 16:
        die("AES-128 密钥必须是 16 字节")
    w = list(key)
    rcon = 1
    for i in range(4, 44):
        temp = w[(i - 1) * 4:i * 4]
        if i % 4 == 0:
            temp = temp[1:] + temp[:1]
            temp = [_AES_SBOX[b] for b in temp]
            temp[0] ^= rcon
            rcon = ((rcon << 1) ^ 0x1B) & 0xFF if rcon & 0x80 else (rcon << 1) & 0xFF
        w.extend(w[(i - 4) * 4 + j] ^ temp[j] for j in range(4))
    words = [w[i * 4:(i + 1) * 4] for i in range(44)]
    # 每轮 16 字节（4 个 word），便于按 AES state 顺序做 AddRoundKey
    return [bytes(words[4 * r + 0] + words[4 * r + 1] +
                  words[4 * r + 2] + words[4 * r + 3]) for r in range(11)]


def _aes_add_round_key(state, rk):
    return [state[i] ^ rk[i] for i in range(16)]


def _aes_inv_shift_rows(s):
    out = s[:]
    for r in range(1, 4):
        for c in range(4):
            out[r + 4 * c] = s[r + 4 * ((c - r) % 4)]
    return out


def _aes_inv_mix_columns(s):
    out = s[:]
    for c in range(4):
        a0, a1, a2, a3 = s[4 * c:4 * c + 4]
        out[4 * c + 0] = _MUL14[a0] ^ _MUL11[a1] ^ _MUL13[a2] ^ _MUL9[a3]
        out[4 * c + 1] = _MUL9[a0] ^ _MUL14[a1] ^ _MUL11[a2] ^ _MUL13[a3]
        out[4 * c + 2] = _MUL13[a0] ^ _MUL9[a1] ^ _MUL14[a2] ^ _MUL11[a3]
        out[4 * c + 3] = _MUL11[a0] ^ _MUL13[a1] ^ _MUL9[a2] ^ _MUL14[a3]
    return out


def _aes_decrypt_block(block: bytes, rk) -> bytes:
    s = list(block)
    s = _aes_add_round_key(s, rk[10])
    for r in range(9, 0, -1):
        s = _aes_inv_shift_rows(s)
        s = [_AES_INV_SBOX[b] for b in s]
        s = _aes_add_round_key(s, rk[r])
        s = _aes_inv_mix_columns(s)
    s = _aes_inv_shift_rows(s)
    s = [_AES_INV_SBOX[b] for b in s]
    s = _aes_add_round_key(s, rk[0])
    return bytes(s)


def aes128_ecb_decrypt(data: bytes, key: bytes = FW_AES_KEY) -> bytes:
    """AES-128-ECB 解密；data 长度必须是 16 的整数倍。"""
    if len(data) % 16:
        die("AES 解密长度必须是 16 的整数倍（当前 %d 字节）" % len(data))
    if _CRYPTO_AES is not None and not os.environ.get("GLXSS_PURE_AES"):
        try:
            return _CRYPTO_AES.new(key, _CRYPTO_AES.MODE_ECB).decrypt(data)
        except Exception:
            pass
    rk = _aes_expand_key(key)
    return b"".join(_aes_decrypt_block(data[i:i + 16], rk)
                    for i in range(0, len(data), 16))


# ---------------------------------------------------------------------------
# PNG 解码 / 编码（纯标准库，支持常见 8/16 位、调色板、非隔行 PNG）
# ---------------------------------------------------------------------------
PNG_SIG = b"\x89PNG\r\n\x1a\n"


class PNGImage:
    __slots__ = ("width", "height", "rgba")

    def __init__(self, width: int, height: int, rgba: bytes):
        self.width = width
        self.height = height
        self.rgba = rgba


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa = abs(p - a)
    pb = abs(p - b)
    pc = abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def _png_unfilter(raw: bytes, width: int, height: int, bits_per_pixel: int):
    row_bytes = (width * bits_per_pixel + 7) // 8
    bpp = max(1, bits_per_pixel // 8)
    prev = bytearray(row_bytes)
    rows = []
    pos = 0
    for _y in range(height):
        if pos >= len(raw):
            die("PNG 数据不足（IDAT 截断）")
        ft = raw[pos]
        pos += 1
        if pos + row_bytes > len(raw):
            die("PNG 数据不足（行 %d 截断）" % _y)
        line = bytearray(raw[pos:pos + row_bytes])
        pos += row_bytes
        if ft == 0:
            pass
        elif ft == 1:  # Sub
            for i in range(row_bytes):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + left) & 0xFF
        elif ft == 2:  # Up
            for i in range(row_bytes):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ft == 3:  # Average
            for i in range(row_bytes):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ft == 4:  # Paeth
            for i in range(row_bytes):
                left = line[i - bpp] if i >= bpp else 0
                up = prev[i]
                ul = prev[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + _paeth(left, up, ul)) & 0xFF
        else:
            die("PNG 行过滤类型不支持: %d" % ft)
        rows.append(line)
        prev = line
    return rows


def decode_png(path: str) -> PNGImage:
    """把 PNG 解成 RGBA8888。只支持非隔行；16 位取高 8 位。"""
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError as e:
        die("无法读取 PNG %s: %s" % (path, e))
    if not data.startswith(PNG_SIG):
        die("%s 不是 PNG 文件（签名不符）" % path)
    pos = 8
    width = height = bitdepth = colortype = interlace = 0
    palette = None
    trns = None
    idat = bytearray()
    while pos + 8 <= len(data):
        length = u32be(data, pos)
        typ = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + length]
        if pos + 12 + length > len(data):
            die("PNG chunk 越界: %s" % typ)
        pos += 12 + length
        if typ == b"IHDR":
            if length != 13:
                die("PNG IHDR 长度错误")
            width, height, bitdepth, colortype, comp, filt, interlace = struct.unpack(
                ">IIBBBBB", payload)
            if comp != 0 or filt != 0:
                die("PNG 压缩/过滤方法不支持")
        elif typ == b"PLTE":
            palette = payload
        elif typ == b"tRNS":
            trns = payload
        elif typ == b"IDAT":
            idat.extend(payload)
        elif typ == b"IEND":
            break
    if width <= 0 or height <= 0:
        die("PNG 缺少 IHDR 或尺寸非法")
    if interlace != 0:
        die("不支持隔行扫描（interlaced）PNG，请另存为非隔行")
    if colortype not in (0, 2, 3, 4, 6):
        die("PNG 颜色类型不支持: %d" % colortype)
    if bitdepth not in (1, 2, 4, 8, 16):
        die("PNG 位深不支持: %d" % bitdepth)
    if colortype == 3 and palette is None:
        die("调色板 PNG 缺少 PLTE")
    if colortype == 3 and bitdepth not in (1, 2, 4, 8):
        die("调色板 PNG 位深不支持: %d" % bitdepth)
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colortype]
    bits_per_pixel = channels * bitdepth
    try:
        raw = zlib.decompress(bytes(idat))
    except zlib.error as e:
        die("PNG IDAT 解压失败: %s" % e)
    rows = _png_unfilter(raw, width, height, bits_per_pixel)
    rgba = bytearray(width * height * 4)
    maxv = (1 << bitdepth) - 1
    for y, row in enumerate(rows):
        if bitdepth == 8:
            samples = list(row)
        elif bitdepth == 16:
            samples = [row[i] for i in range(0, len(row), 2)]
        else:
            per_byte = 8 // bitdepth
            mask = maxv
            samples = []
            for byte in row:
                for k in range(per_byte - 1, -1, -1):
                    samples.append((byte >> (k * bitdepth)) & mask)
                if len(samples) >= width * channels:
                    break
            samples = samples[:width * channels]
        if len(samples) < width * channels:
            die("PNG 行像素数据不足（行 %d）" % y)
        o = y * width * 4
        for x in range(width):
            i = x * channels
            a = 255
            if colortype == 3:
                idx = samples[i]
                p = idx * 3
                if p + 2 >= len(palette):
                    die("PNG 调色板索引越界: %d" % idx)
                r, g, b = palette[p], palette[p + 1], palette[p + 2]
                if trns is not None and idx < len(trns):
                    a = trns[idx]
            elif colortype == 0:
                v = samples[i]
                if bitdepth < 8:
                    v = v * 255 // maxv
                r = g = b = v
            elif colortype == 4:
                v = samples[i]
                if bitdepth < 8:
                    v = v * 255 // maxv
                r = g = b = v
                a = samples[i + 1]
            elif colortype == 2:
                r, g, b = samples[i], samples[i + 1], samples[i + 2]
            else:  # 6
                r, g, b, a = samples[i], samples[i + 1], samples[i + 2], samples[i + 3]
            q = o + x * 4
            rgba[q] = r
            rgba[q + 1] = g
            rgba[q + 2] = b
            rgba[q + 3] = a
    return PNGImage(width, height, bytes(rgba))


def write_png(path: str, width: int, height: int, rgb: bytes) -> None:
    """把 RGB888 写成最小 PNG（filter 0），用于 decode-uvc。"""
    raw = bytearray()
    stride = width * 3
    for y in range(height):
        raw.append(0)
        raw += rgb[y * stride:(y + 1) * stride]

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    out = PNG_SIG
    out += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    out += chunk(b"IDAT", zlib.compress(bytes(raw), 6))
    out += chunk(b"IEND", b"")
    parent = os.path.dirname(os.path.abspath(path))
    if parent and not os.path.isdir(parent):
        die("输出目录不存在: %s" % parent)
    try:
        with open(path, "wb") as f:
            f.write(out)
    except OSError as e:
        die("无法写入 PNG %s: %s" % (path, e))


# ---------------------------------------------------------------------------
# 640x400 帧生成 / PNG -> 帧映射
# ---------------------------------------------------------------------------
def pattern_bars() -> bytes:
    """八色竖条 + 自上而下渐暗，和 poc_send_frame.py 的 bars 一致。"""
    buf = bytearray(FRAME_PAYLOAD)
    colors = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 255),
              (0, 0, 0), (255, 255, 0), (0, 255, 255), (255, 0, 255)]
    bar_w = FRAME_W // len(colors)
    for y in range(FRAME_H):
        shade = 255 - (200 * y // max(1, FRAME_H - 1))
        row = bytearray()
        for r, g, b in colors:
            row += bytes((r * shade // 255, g * shade // 255, b * shade // 255, 255)) * bar_w
        buf[y * FRAME_W * 4:(y + 1) * FRAME_W * 4] = row
    return bytes(buf)


def parse_color(text: str) -> tuple[int, int, int]:
    s = text.strip().lstrip("#")
    if len(s) == 8:
        s = s[:6]
    if len(s) != 6:
        die("颜色格式应为 RRGGBB（或 #RRGGBB），收到: %r" % text)
    try:
        return int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16)
    except ValueError:
        die("颜色不是合法十六进制: %r" % text)


def pattern_solid(color: tuple[int, int, int]) -> bytes:
    return bytes((color[0], color[1], color[2], 255)) * (FRAME_W * FRAME_H)


def pattern_black() -> bytes:
    return bytes((0, 0, 0, 255)) * (FRAME_W * FRAME_H)


def map_png_to_frame(img: PNGImage, mode: str = "fit", verbose: bool = False) -> bytes:
    """把任意 PNG 缩放到 640x400；fit 留黑边，cover 裁剪，stretch 拉伸。"""
    W, H = FRAME_W, FRAME_H
    out = bytearray(bytes((0, 0, 0, 255)) * (W * H))
    src = img.rgba
    sw, sh = img.width, img.height

    def put(dx: int, dy: int, sx: int, sy: int) -> None:
        i = (sy * sw + sx) * 4
        a = src[i + 3]
        q = (dy * W + dx) * 4
        if a == 255:
            out[q] = src[i]
            out[q + 1] = src[i + 1]
            out[q + 2] = src[i + 2]
        elif a:
            # 透明像素合成到黑底（设备端面板不消费 alpha，统一写 255）
            out[q] = (src[i] * a) // 255
            out[q + 1] = (src[i + 1] * a) // 255
            out[q + 2] = (src[i + 2] * a) // 255
        out[q + 3] = 255

    if mode == "stretch":
        xmap = [min(sw - 1, (x * sw) // W) for x in range(W)]
        for y in range(H):
            sy = min(sh - 1, (y * sh) // H)
            base = sy * sw
            o = y * W * 4
            for x in range(W):
                sx = xmap[x]
                i = (base + sx) * 4
                a = src[i + 3]
                if a == 255:
                    out[o] = src[i]
                    out[o + 1] = src[i + 1]
                    out[o + 2] = src[i + 2]
                elif a:
                    out[o] = (src[i] * a) // 255
                    out[o + 1] = (src[i + 1] * a) // 255
                    out[o + 2] = (src[i + 2] * a) // 255
                out[o + 3] = 255
                o += 4
        if verbose:
            info("映射: stretch %dx%d -> 640x400（不保持比例）" % (sw, sh))
        return bytes(out)

    if mode == "cover":
        scale = max(W / sw, H / sh)
        src_w = W / scale
        src_h = H / scale
        left = max(0.0, (sw - src_w) / 2.0)
        top = max(0.0, (sh - src_h) / 2.0)
        for y in range(H):
            sy = min(sh - 1, int(top + (y + 0.5) * src_h / H))
            for x in range(W):
                sx = min(sw - 1, int(left + (x + 0.5) * src_w / W))
                put(x, y, sx, sy)
        if verbose:
            info("映射: cover %dx%d -> 640x400（居中裁剪，缩放 %.3f）" % (sw, sh, scale))
        return bytes(out)

    # fit（默认）
    scale = min(W / sw, H / sh)
    dw = max(1, min(W, int(round(sw * scale))))
    dh = max(1, min(H, int(round(sh * scale))))
    ox = (W - dw) // 2
    oy = (H - dh) // 2
    for y in range(dh):
        sy = min(sh - 1, y * sh // dh)
        for x in range(dw):
            sx = min(sw - 1, x * sw // dw)
            put(ox + x, oy + y, sx, sy)
    if verbose:
        info("映射: fit %dx%d -> %dx%d（黑边 %d,%d）" % (sw, sh, dw, dh, ox, oy))
    return bytes(out)


def frame_packet(payload: bytes) -> bytes:
    if len(payload) != FRAME_PAYLOAD:
        die("帧像素必须是 %d 字节，实际 %d" % (FRAME_PAYLOAD, len(payload)))
    # docs/ch-display.md §2.1: [0]=format=1, [4..7]=totalLen(LE, 含 16 B 头)
    return (bytes((1, 0, 0, 0)) + struct.pack("<I", FRAME_HEADER + len(payload)) +
            bytes(8) + payload)


# ---------------------------------------------------------------------------
# USB 基础设施
# ---------------------------------------------------------------------------
def require_pyusb() -> None:
    if not PYUSB_AVAILABLE:
        die("没有可用的 pyusb。请先安装：\n"
            "  python -m pip install pyusb\n"
            "Windows 还需要 libusb-1.0.dll，并把眼镜接口绑定到 WinUSB（Zadig）。\n"
            "Linux 还需要 udev 权限（见 code/README.md）。")


def _pyusb_error_text(e) -> str:
    return "%s" % e


def _errno_of(e) -> "int | None":
    n = getattr(e, "errno", None)
    if n is None:
        n = getattr(e, "backend_error_code", None)
    try:
        return int(n)
    except Exception:
        return None


def is_no_device(e) -> bool:
    n = _errno_of(e)
    s = _pyusb_error_text(e).lower()
    return (n in (-4, 19) or "no such device" in s or "device disconnected" in s or
            ("no device" in s and "backend" not in s))


def is_timeout(e) -> bool:
    n = _errno_of(e)
    s = _pyusb_error_text(e).lower()
    return n in (-7, 110) or "timeout" in s or "timed out" in s


def usb_error_hint(e, doing: str = "") -> str:
    n = _errno_of(e)
    s = _pyusb_error_text(e).lower()
    hints = []
    if is_timeout(e):
        hints.append("超时：设备没有在时限内响应/消费数据。检查是否被厂商 App 或其他程序占用，"
                     "投屏时也可试 --timeout 调大。")
    elif is_no_device(e):
        hints.append("设备已断开/重枚举：拔插后重试；刷写时这通常表示设备开始重启。")
    elif n in (-13, 13) or "access" in s or "permission" in s:
        hints.append("权限不足：Linux 加 udev 规则或用 sudo；Windows 用 Zadig 把接口绑到 WinUSB。")
    elif n in (-16, 16) or "busy" in s or "claimed" in s:
        hints.append("接口忙：关闭厂商 App / 其他使用该设备的程序；Linux 可加 --detach 摘掉内核驱动。")
    elif "not supported" in s or "not implemented" in s:
        hints.append("当前驱动/后端不支持该操作：Windows 需要 WinUSB/libusb 驱动；"
                     "Linux 可能需要 detach 内核驱动。")
    elif "no backend" in s or "libusb" in s:
        hints.append("找不到 libusb 后端：安装 libusb-1.0（Windows 放到 PATH 或 System32），"
                     "Linux 安装 libusb-1.0-0。")
    base = ("%s失败: %s" % (doing, e)) if doing else str(e)
    return base + (("\n提示：" + " ".join(hints)) if hints else "")


def find_device(vid: int, pid: int, required: bool = True):
    require_pyusb()
    try:
        dev = usb.core.find(idVendor=vid, idProduct=pid)
    except NoBackendError as e:  # type: ignore[attr-defined]
        raise ToolError(usb_error_hint(e, "查找 USB 设备"))
    except USBError as e:  # type: ignore[attr-defined]
        raise ToolError(usb_error_hint(e, "枚举 USB"))
    if dev is None and required:
        raise ToolError(
            "未找到 %04x:%04x。\n"
            "  * 正常模式应为 2e09:0030；引导模式应为 03e7:2150。\n"
            "  * 检查线材/供电/设备模式；先用 `python code/glxss.py list` 确认。\n"
            "  * Linux 检查 udev 权限；Windows 检查设备是否绑定了 WinUSB。" % (vid, pid))
    return dev


def find_normal(required: bool = True):
    return find_device(VID_NORMAL, PID_NORMAL, required)


def find_boot(required: bool = True):
    return find_device(VID_BOOT, PID_BOOT, required)


def device_line(dev) -> str:
    tag = KNOWN_DEVICES.get((dev.idVendor, dev.idProduct), "")
    return ("%04x:%04x bus=%s addr=%s%s" % (
        dev.idVendor, dev.idProduct, getattr(dev, "bus", "?"), getattr(dev, "address", "?"),
        ("  [" + tag + "]") if tag else ""))


def safe_string(dev, attr: str) -> str:
    try:
        v = getattr(dev, attr)
        return v if isinstance(v, str) else ""
    except Exception:
        return ""


def ep_type_name(attr: int) -> str:
    return {0: "control", 1: "iso", 2: "bulk", 3: "interrupt"}.get(attr & 0x03, "?")


def _iter_configs(dev):
    try:
        return list(dev)
    except USBError as e:  # type: ignore[attr-defined]
        raise ToolError(usb_error_hint(e, "读取配置描述符"))
    except Exception as e:
        raise ToolError("读取配置描述符失败: %s" % e)


def find_interface(dev, number: int, alt: "int | None" = None):
    for cfg in _iter_configs(dev):
        for intf in cfg:
            if intf.bInterfaceNumber == number and (alt is None or intf.bAlternateSetting == alt):
                return intf
    return None


def pick_bulk_out(dev, verbose: bool = False):
    """优先 vendor-specific(0xFF) + EP 0x01 + if3，和 UsbLink 的选法一致。"""
    cands = []
    for cfg in _iter_configs(dev):
        for intf in cfg:
            for ep in intf:
                if (ep.bmAttributes & 0x03) != 0x02 or (ep.bEndpointAddress & 0x80):
                    continue
                score = 0
                if intf.bInterfaceClass == 0xFF:
                    score += 100
                if ep.bEndpointAddress == 0x01:
                    score += 50
                if intf.bInterfaceNumber == IFACE_LCD:
                    score += 20
                cands.append((score, cfg.bConfigurationValue, intf, ep))
    if verbose:
        for score, cfgv, intf, ep in sorted(cands, key=lambda x: -x[0]):
            print("  候选 bulk OUT: cfg=%d if=%d alt=%d class=0x%02x ep=0x%02x mps=%d score=%d" % (
                cfgv, intf.bInterfaceNumber, intf.bAlternateSetting, intf.bInterfaceClass,
                ep.bEndpointAddress, ep.wMaxPacketSize, score))
    if not cands:
        raise ToolError("设备没有 bulk OUT 端点（描述符不符合预期）")
    cands.sort(key=lambda x: -x[0])
    _, _, intf, ep = cands[0]
    return intf, ep


def pick_uvc(dev):
    """返回 (VC interface, VS interface, bulk IN endpoint)。"""
    vcs, vss = [], []
    for cfg in _iter_configs(dev):
        for intf in cfg:
            if intf.bInterfaceClass != UVC_IFACE_CLASS:
                continue
            if intf.bInterfaceSubClass == UVC_VC_SUBCLASS:
                vcs.append(intf)
            elif intf.bInterfaceSubClass == UVC_VS_SUBCLASS:
                for ep in intf:
                    if (ep.bmAttributes & 0x03) == 0x02 and (ep.bEndpointAddress & 0x80):
                        vss.append((intf, ep))
                        break
    if not vss:
        raise ToolError("未找到 UVC VideoStreaming 接口（class 0x0e/subclass 0x02 + bulk IN）")
    # 优先 alt=0（本设备 alt0 就带 0x81）
    vss.sort(key=lambda x: (x[0].bAlternateSetting != 0, x[0].bInterfaceNumber))
    vs, ep = vss[0]
    vc = vcs[0] if vcs else None
    return vc, vs, ep


def ensure_configuration(dev, verbose: bool = False) -> None:
    """确保设备已有 active configuration；已配置时绝不重置。"""
    try:
        dev.get_active_configuration()
        return
    except Exception as e:
        if verbose:
            print("  get_active_configuration: %s" % e)
    try:
        dev.set_configuration()
        print("  已 set_configuration（设备原先没有 active configuration）")
    except Exception as e:
        if verbose:
            print("  set_configuration 失败（继续）: %s" % e)


class DeviceSession:
    """打开设备、claim 接口、退出时 release（必要时重新 attach 内核驱动）。"""

    def __init__(self, dev, detach: bool = False, verbose: bool = False):
        self.dev = dev
        self.detach = detach
        self.verbose = verbose
        self.claimed = []
        self.detached = []
        self._configured = False

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()
        return False

    def claim(self, number: int, alt: "int | None" = None, label: str = "") -> bool:
        if not self._configured:
            ensure_configuration(self.dev, self.verbose)
            self._configured = True
        intf = find_interface(self.dev, number, alt)
        if intf is None:
            warn("找不到 interface %d%s，跳过 claim" % (number, (" alt=%d" % alt) if alt is not None else ""))
            return False
        name = label or ("interface %d" % number)
        if self.detach:
            try:
                self.dev.detach_kernel_driver(number)
                self.detached.append(number)
                print("  已 detach 内核驱动: %s" % name)
            except NotImplementedError:
                if self.verbose:
                    print("  当前平台不支持 detach_kernel_driver（Windows 请用 Zadig 绑定 WinUSB）")
            except USBError as e:  # type: ignore[attr-defined]
                if self.verbose:
                    print("  detach 内核驱动 %s: %s（可能本来就没有驱动）" % (name, e))
            except Exception as e:
                if self.verbose:
                    print("  detach 内核驱动 %s: %s" % (name, e))
        try:
            usb.util.claim_interface(self.dev, intf)
            self.claimed.append(intf)
            print("  claim %s (if=%d alt=%d class=0x%02x) 成功" % (
                name, intf.bInterfaceNumber, intf.bAlternateSetting, intf.bInterfaceClass))
            return True
        except USBError as e:  # type: ignore[attr-defined]
            warn("claim %s 失败: %s\n"
                 "  控制请求仍会尝试；若返回 -1/超时，请关闭厂商 App、"
                 "Linux 加 --detach，Windows 用 Zadig 绑定 WinUSB。" % (name, e))
            return False
        except Exception as e:
            warn("claim %s 失败: %s" % (name, e))
            return False

    def close(self) -> None:
        for intf in reversed(self.claimed):
            try:
                usb.util.release_interface(self.dev, intf)
            except Exception:
                pass
        self.claimed = []
        for num in self.detached:
            try:
                self.dev.attach_kernel_driver(num)
            except Exception:
                pass
        self.detached = []


def ctrl_in(dev, request: int, value: int, index: int, length: int,
            timeout: int = 2000, quiet: bool = False) -> bytes:
    if not quiet:
        print_ctrl("IN", request, value, index, length)
    try:
        data = dev.ctrl_transfer(0xA1, request, value, index, length, timeout=timeout)
    except USBError as e:  # type: ignore[attr-defined]
        raise ToolError(usb_error_hint(e, "控制读 0xA1/0x%02x" % request))
    except ValueError as e:
        raise ToolError("控制读参数错误: %s" % e)
    return bytes(data)


def ctrl_out(dev, request: int, value: int, index: int, data: bytes,
             timeout: int = 2000, quiet: bool = False) -> int:
    if not quiet:
        print_ctrl("OUT", request, value, index, len(data), "data=%s" % hexs(data[:16]))
    try:
        n = dev.ctrl_transfer(0x21, request, value, index, data, timeout=timeout)
    except USBError as e:  # type: ignore[attr-defined]
        raise ToolError(usb_error_hint(e, "控制写 0x21/0x%02x" % request))
    return int(n)


def bulk_write_all(dev, ep_addr: int, data: bytes, timeout: int,
                   attempts: int = 3) -> int:
    """写完整块；短写/超时会只补写剩余字节（不重发已写部分，保持帧边界）。"""
    off = 0
    total = len(data)
    failures = 0
    while off < total:
        chunk = data if off == 0 else data[off:]
        try:
            n = dev.write(ep_addr, chunk, timeout)
        except USBError as e:  # type: ignore[attr-defined]
            hint = usb_error_hint(e, "bulk OUT 0x%02x（已写 %d/%d B）" % (ep_addr, off, total))
            if is_no_device(e):
                raise DeviceGone(hint)
            failures += 1
            if failures >= attempts:
                raise ToolError(hint)
            time.sleep(0.1)
            continue
        if not n:
            failures += 1
            if failures >= attempts:
                raise ToolError("bulk OUT 0x%02x 连续返回 0 字节（offset %d/%d）" % (
                    ep_addr, off, total))
            time.sleep(0.1)
            continue
        off += int(n)
        failures = 0
    return off


# ---------------------------------------------------------------------------
# LCD 控制 / 状态 / EEPROM
# ---------------------------------------------------------------------------
class LcdStatus:
    __slots__ = ("raw", "width", "height", "screen_open", "luminance", "roll", "reserved")

    def __init__(self, raw: bytes):
        if len(raw) < 8:
            raise ToolError("状态数据只有 %d 字节（预期 8）" % len(raw))
        self.raw = raw[:8]
        self.width = u16le(raw, 0)
        self.height = u16le(raw, 2)
        self.screen_open = raw[4] != 0
        self.luminance = raw[5]
        self.roll = u16le(raw, 6)
        self.reserved = raw[7]


def read_lcd_status(dev, timeout: int = 2000) -> LcdStatus:
    data = ctrl_in(dev, 0x04, 0, IFACE_LCD, 8, timeout)
    if len(data) < 8:
        raise ToolError("0xA1/0x04 只返回 %d 字节（预期 8）" % len(data))
    return LcdStatus(data)


def print_lcd_status(st: LcdStatus) -> None:
    print("  原始 hex: %s" % hexs(st.raw))
    print("  分辨率  : %d×%d" % (st.width, st.height))
    print("  屏      : %s (byte4=0x%02x)" % ("开" if st.screen_open else "关", st.raw[4]))
    print("  亮度    : %d (byte5=0x%02x)" % (st.luminance, st.luminance))
    print("  roll    : %d (byte6..7 LE=0x%04x)" % (st.roll, st.roll))
    print("  byte7   : 0x%02x（UNKNOWN / roll 高字节）" % st.reserved)


def write_lcd(dev, request: int, value: int, label: str, timeout: int = 2000) -> int:
    data = ctrl_in(dev, request, value, IFACE_LCD, 1, timeout)
    ack = data[0] if data else 0
    print("  %s: wValue=%d (0x%04x) -> ack=0x%02x" % (label, value, value, ack))
    return ack


def _fmt_ascii(info: bytes, start: int, length: int) -> str:
    chunk = info[start:start + length]
    s = "".join(chr(c) if 0x20 <= c <= 0x7E else "." for c in chunk)
    return s.strip(" .")


def _fmt_hex(info: bytes, start: int, length: int) -> str:
    return hexs(info[start:start + length], "")


def parse_eeprom(raw: bytes) -> dict:
    info = raw[EEPROM_HEAD_LEN:]
    blank = bool(info) and (all(b == 0xFF for b in info) or all(b == 0x00 for b in info))
    res_raw = info[60:64]
    if len(res_raw) == 4:
        w = (res_raw[0] << 8) | res_raw[1]
        h = (res_raw[2] << 8) | res_raw[3]
        res_guess = "%d×%d（推测，未验证）" % (w, h) if (w and h and not blank) else "UNKNOWN"
    else:
        res_guess = "（数据不足）"
    return {
        "info": info,
        "blank": blank,
        "head": raw[:EEPROM_HEAD_LEN],
        "platform": _fmt_hex(info, 0, 1),
        "product": _fmt_ascii(info, 1, 7),
        "firm": _fmt_hex(info, 8, 2),
        "bsn": _fmt_ascii(info, 10, 16),
        "psn": _fmt_ascii(info, 26, 16),
        "bom": _fmt_ascii(info, 42, 2),
        "isp": _fmt_ascii(info, 44, 8),
        "firmware": _fmt_ascii(info, 52, 8),
        "res_raw": _fmt_hex(info, 60, 4),
        "res_guess": res_guess,
        "reserved": _fmt_hex(info, 64, 64),
        "custom": _fmt_ascii(info, 128, 128),
    }


def print_eeprom(raw: bytes, show_raw: bool = False) -> None:
    pi = parse_eeprom(raw)
    print("  EEPROM 原始长度: %d B（8 B 头 + 256 B 产品信息）" % len(raw))
    print("  设备头(8B)  : %s" % hexs(pi["head"]))
    if pi["blank"]:
        print("  结论        : 产品信息区全 0xFF/0x00 —— 这颗 EEPROM 未写入工厂信息")
    for key, label in (
        ("platform", "platformID"), ("product", "productID"), ("firm", "firmID"),
        ("bsn", "bsnID"), ("psn", "psnID"), ("bom", "bomID"), ("isp", "ispID"),
        ("firmware", "firmwareID"), ("res_raw", "resolution"),
        ("custom", "customFields"),
    ):
        val = pi[key]
        if key == "res_raw":
            val = "%s → %s" % (val or "（空）", pi["res_guess"])
        print("  %-12s: %s" % (label, val if val else "（空）"))
    print("  [64..127]   : UNKNOWN %s" % (pi["reserved"] or "（空）"))
    if show_raw:
        print("  原始 %d B:" % len(raw))
        print(hexdump(raw, 32))


# ---------------------------------------------------------------------------
# 子命令：list / status / bright / screen / mode / roll / eeprom / version / hid
# ---------------------------------------------------------------------------
def cmd_list(args) -> int:
    require_pyusb()
    try:
        devices = list(usb.core.find(find_all=True))
    except NoBackendError as e:  # type: ignore[attr-defined]
        raise ToolError(usb_error_hint(e, "枚举 USB"))
    except USBError as e:  # type: ignore[attr-defined]
        raise ToolError(usb_error_hint(e, "枚举 USB"))
    print("共发现 %d 个 USB 设备（pyusb %s）" % (len(devices), getattr(usb, "__version__", "?")))
    found = 0
    for idx, dev in enumerate(devices, 1):
        tag = KNOWN_DEVICES.get((dev.idVendor, dev.idProduct), "")
        if tag:
            found += 1
        mfg = safe_string(dev, "manufacturer")
        prod = safe_string(dev, "product")
        extra = "  ".join(x for x in (mfg, prod) if x)
        line = "[%02d] %04x:%04x bus=%s addr=%s" % (
            idx, dev.idVendor, dev.idProduct, getattr(dev, "bus", "?"),
            getattr(dev, "address", "?"))
        if tag:
            line += "  <<< %s >>>" % tag
        if extra:
            line += "  " + extra
        print(line)
        if args.brief and not tag:
            continue
        try:
            configs = list(dev)
        except Exception as e:
            print("     （描述符读取失败: %s）" % e)
            continue
        for cfg in configs:
            print("     config %d: %d 个接口" % (cfg.bConfigurationValue, cfg.bNumInterfaces))
            for intf in cfg:
                ep_desc = []
                for ep in intf:
                    ep_desc.append("ep 0x%02x %s %s mps=%d%s" % (
                        ep.bEndpointAddress,
                        "IN" if ep.bEndpointAddress & 0x80 else "OUT",
                        ep_type_name(ep.bmAttributes), ep.wMaxPacketSize,
                        (" interval=%d" % ep.bInterval) if (ep.bmAttributes & 0x03) == 3 else ""))
                print("       if %d alt %d class=0x%02x sub=0x%02x proto=0x%02x  %s" % (
                    intf.bInterfaceNumber, intf.bAlternateSetting, intf.bInterfaceClass,
                    intf.bInterfaceSubClass, intf.bInterfaceProtocol,
                    "; ".join(ep_desc) if ep_desc else "（无端点）"))
    if not found:
        print("\n未发现 GLXSS 设备（2e09:0030 正常模式 / 03e7:2150 引导模式）。")
    else:
        print("\n发现 %d 个 GLXSS 设备。" % found)
    return 0


def cmd_status(args) -> int:
    dev = find_normal()
    print("设备: " + device_line(dev))
    with DeviceSession(dev, detach=args.detach, verbose=args.verbose) as s:
        s.claim(IFACE_LCD, label="LCD")
        st = read_lcd_status(dev, args.timeout)
        print_lcd_status(st)
    return 0


def _verify_after(dev, args) -> None:
    if getattr(args, "no_verify", False):
        return
    try:
        st = read_lcd_status(dev, args.timeout)
        print("  回读状态: %d×%d 屏=%s 亮度=%d roll=%d" % (
            st.width, st.height, "开" if st.screen_open else "关", st.luminance, st.roll))
    except ToolError as e:
        warn("回读状态失败（命令可能已生效）: %s" % e)


def cmd_bright(args) -> int:
    if not 0 <= args.value <= 255:
        die("亮度必须在 0..255（文档实测有效范围是 100..254）")
    if args.value < 100 or args.value > 254:
        warn("亮度 %d 超出实测范围 100..254；固件行为未验证，照发。" % args.value)
    dev = find_normal()
    print("设备: " + device_line(dev))
    with DeviceSession(dev, detach=args.detach, verbose=args.verbose) as s:
        s.claim(IFACE_LCD, label="LCD")
        write_lcd(dev, 0x01, args.value, "设亮度", args.timeout)
        _verify_after(dev, args)
    return 0


def cmd_screen(args) -> int:
    val = 1 if args.state == "on" else 0
    dev = find_normal()
    print("设备: " + device_line(dev))
    with DeviceSession(dev, detach=args.detach, verbose=args.verbose) as s:
        s.claim(IFACE_LCD, label="LCD")
        write_lcd(dev, 0x03, val, "屏开关", args.timeout)
        _verify_after(dev, args)
    return 0


def cmd_mode(args) -> int:
    if not 0 <= args.value <= 2:
        die("显示模式必须在 0..2（1=关/logo/待机，2=投屏开；0 未验证）")
    if args.value == 0:
        warn("显示模式 0 未在真机验证过；1/2 是已验证值。")
    dev = find_normal()
    print("设备: " + device_line(dev))
    with DeviceSession(dev, detach=args.detach, verbose=args.verbose) as s:
        s.claim(IFACE_LCD, label="LCD")
        write_lcd(dev, 0x05, args.value, "显示模式", args.timeout)
        _verify_after(dev, args)
    return 0


def cmd_roll(args) -> int:
    if not 0 <= args.value <= 0xFFFF:
        die("roll 必须在 0..65535（wValue 是 16 位）")
    dev = find_normal()
    print("设备: " + device_line(dev))
    with DeviceSession(dev, detach=args.detach, verbose=args.verbose) as s:
        s.claim(IFACE_LCD, label="LCD")
        write_lcd(dev, 0x02, args.value, "设 roll", args.timeout)
        _verify_after(dev, args)
    return 0


def cmd_eeprom(args) -> int:
    dev = find_normal()
    print("设备: " + device_line(dev))
    with DeviceSession(dev, detach=args.detach, verbose=args.verbose) as s:
        s.claim(IFACE_LCD, label="LCD")
        data = ctrl_in(dev, 0x07, 0x0100, IFACE_LCD, EEPROM_LEN, args.timeout)
        print("  返回 %d B（预期 %d）" % (len(data), EEPROM_LEN))
        if len(data) != EEPROM_LEN:
            warn("EEPROM 返回长度不是 264，仍按现有字节解析。")
        print_eeprom(data, args.raw)
    return 0


def cmd_version(args) -> int:
    dev = find_normal()
    print("设备: " + device_line(dev))
    with DeviceSession(dev, detach=args.detach, verbose=args.verbose) as s:
        s.claim(IFACE_HID, label="HID")
        data = ctrl_in(dev, 0x17, 0, IFACE_HID, HID_PRODUCT_LEN, args.timeout)
        print("  返回 %d B（预期 %d）" % (len(data), HID_PRODUCT_LEN))
        if len(data) < HID_PRODUCT_LEN:
            warn("版本数据不足 24 B，可能 HID 接口被占用/固件异常。")
        ver_raw = data[:16]
        version = bytes(ver_raw).split(b"\x00", 1)[0].decode("ascii", "replace").strip("\r\n ")
        print("  原始 hex : %s" % hexs(data))
        print("  版本串   : %r" % version)
        if len(data) >= 24:
            dev_id = struct.unpack_from("<Q", data, 16)[0]
            print("  设备 ID  : %d (0x%016x)" % (dev_id, dev_id))
        else:
            print("  设备 ID  : （数据不足 8 B）")
        if not version:
            warn("版本串为空；确认 HID 接口是否 claim 成功（Linux 可试 --detach）。")
    return 0


def parse_hid_report(data: bytes) -> dict:
    if len(data) != HID_REPORT_LEN:
        raise ToolError("HID 报告长度 %d，预期 %d" % (len(data), HID_REPORT_LEN))
    vals = struct.unpack("<BBH9hBBf", bytes(data))
    return {
        "key_status": vals[0], "key_code": vals[1], "lux": vals[2],
        "compass": vals[3:6], "acc": vals[6:9], "gyro": vals[9:12],
        "af": vals[12], "caf": vals[13], "temp": vals[14],
    }


def fmt_hid(r: dict) -> str:
    key = ("按下" if r["key_status"] == 1 else "松开") + "(0x%02x)" % r["key_code"]
    note = " Fn" if r["key_code"] == 0x71 else ""
    return ("按键=%-10s lux=%-6d 罗盘=(%d,%d,%d) 加速度=(%d,%d,%d) "
            "陀螺=(%d,%d,%d) AF=%d CAF=%d 温度=%.2f°C" % (
                key + note, r["lux"], *r["compass"], *r["acc"], *r["gyro"],
                r["af"], r["caf"], r["temp"]))


def cmd_hid(args) -> int:
    dev = find_normal()
    print("设备: " + device_line(dev))
    print("  HID 报告 0xA1/0x16 wIndex=2 wLength=28，解析 <BBH9hBBf>；Ctrl-C 退出。")
    count = 0
    fails = 0
    try:
        with DeviceSession(dev, detach=args.detach, verbose=args.verbose) as s:
            s.claim(IFACE_HID, label="HID")
            if args.enable_sensors:
                print("  使能传感器 0xA1/0x10,0x11,0x12,0x14 wValue=1 ...")
                for req, name in ((0x10, "光感"), (0x11, "罗盘"), (0x12, "加速度"), (0x14, "陀螺")):
                    try:
                        d = ctrl_in(dev, req, 1, IFACE_HID, 1, args.timeout)
                        print("    %s(0x%02x): ack=0x%02x" % (name, req, d[0] if d else 0))
                    except ToolError as e:
                        warn("%s使能失败: %s" % (name, e))
            print("  时间           按键          lux ...")
            while True:
                if args.count and count >= args.count:
                    break
                try:
                    data = ctrl_in(dev, 0x16, 0, IFACE_HID, HID_REPORT_LEN, args.timeout,
                                   quiet=(count > 0 and not args.verbose))
                except ToolError as e:
                    fails += 1
                    warn("读 HID 报告失败（连续 %d 次）: %s" % (fails, e))
                    if fails >= 5:
                        raise ToolError("连续 5 次读取 HID 报告失败，停止。\n"
                                        "  * HID 接口可能被内核 hid 驱动/其他 App 占用；Linux 可试 --detach。\n"
                                        "  * 也可能是固件异常，参考 docs/device-recovery.md。")
                    time.sleep(max(0.2, args.interval))
                    continue
                fails = 0
                count += 1
                r = parse_hid_report(data)
                line = "[%s] %s" % (now_str(), fmt_hid(r))
                if args.raw or count == 1:
                    line += "  raw=" + hexs(data)
                print(line)
                time.sleep(max(0.0, args.interval))
        print("HID 轮询结束，共 %d 次。" % count)
    except KeyboardInterrupt:
        print("\n已停止 HID 轮询（Ctrl-C），共 %d 次。" % count)
    return 0


# ---------------------------------------------------------------------------
# 子命令：frame
# ---------------------------------------------------------------------------
def resolve_frame_payload(args):
    """返回 (rgba_payload, 描述)。"""
    pattern = args.pattern
    if not pattern:
        die("缺少帧类型：请用 frame png FILE / frame bars / frame solid / frame black")
    low = pattern.lower()
    if low == "png" or low.endswith(".png"):
        if low == "png":
            path = args.path
            if not path:
                die("frame png 需要一个 PNG 文件路径，例如：frame png shot.png")
        else:
            path = pattern
            if args.path:
                die("frame %s 后面不应再有第二个文件参数（收到 %r）" % (pattern, args.path))
        img = decode_png(path)
        payload = map_png_to_frame(img, args.fit, args.verbose)
        desc = "PNG %s（%dx%d，fit=%s）" % (path, img.width, img.height, args.fit)
        return payload, desc
    if args.path and low not in ("solid",):
        die("frame %s 不接受额外参数（收到 %r）" % (pattern, args.path))
    if low == "bars":
        return pattern_bars(), "八色竖条渐变"
    if low == "black":
        return pattern_black(), "纯黑"
    if low == "solid":
        # 兼容 `frame solid ff8000` 与 `frame solid --color ff8000`
        color = parse_color(args.path or args.color)
        return pattern_solid(color), "纯色 #%02x%02x%02x" % color
    die("未知帧类型 %r；可选 png / bars / solid / black，或直接给一个 .png 路径" % pattern)


def cmd_frame(args) -> int:
    if args.fps < 0:
        die("--fps 不能为负数（0 表示不限速）")
    if not args.loop and args.seconds <= 0:
        die("--seconds 必须 > 0（想一直发请用 --loop）")
    payload, desc = resolve_frame_payload(args)
    pkt = frame_packet(payload)
    print("帧源: " + desc)
    print("  帧头 16B : %s" % hexs(pkt[:16]))
    print("  totalLen : %d (0x%06x) = 16 + %d" % (
        FRAME_HEADER + FRAME_PAYLOAD, FRAME_HEADER + FRAME_PAYLOAD, FRAME_PAYLOAD))
    print("  包大小   : %d B" % len(pkt))
    if args.dry_run:
        print("  SHA256   : %s" % hashlib.sha256(pkt).hexdigest())
        print("DRY-RUN：只生成帧，不打开设备、不发送。")
        return 0

    dev = find_normal()
    print("设备: " + device_line(dev))
    intf, ep = pick_bulk_out(dev, args.verbose)
    print("  端点: if=%d alt=%d class=0x%02x ep=0x%02x bulk OUT mps=%d" % (
        intf.bInterfaceNumber, intf.bAlternateSetting, intf.bInterfaceClass,
        ep.bEndpointAddress, ep.wMaxPacketSize))
    frames = 0
    failed = 0
    bytes_sent = 0
    start = time.perf_counter()
    last_report = start
    first = True
    interrupted = False
    try:
        with DeviceSession(dev, detach=args.detach, verbose=args.verbose) as s:
            s.claim(intf.bInterfaceNumber, alt=intf.bAlternateSetting, label="LCD")
            if not args.no_init:
                # docs/ch-display.md §3.3：屏没开就先开，再切显示模式；否则帧写进去但面板不亮
                try:
                    write_lcd(dev, 0x03, 1, "开屏", args.timeout)
                except ToolError as e:
                    warn("开屏失败（继续发帧，但屏可能不亮）: %s" % e)
                try:
                    write_lcd(dev, 0x05, 2, "显示模式=投屏", args.timeout)
                except ToolError as e:
                    warn("切显示模式失败（继续发帧）: %s" % e)
            period = 1.0 / args.fps if args.fps > 0 else 0.0
            while True:
                if not args.loop and time.perf_counter() - start >= args.seconds:
                    break
                t0 = time.perf_counter()
                try:
                    n = bulk_write_all(dev, ep.bEndpointAddress, pkt, args.timeout)
                except DeviceGone:
                    raise
                except ToolError as e:
                    failed += 1
                    warn("帧发送失败（连续 %d 次）: %s" % (failed, e))
                    if failed >= 3:
                        raise ToolError("连续 3 帧发送失败，停止。\n"
                                        "  * 关闭厂商 App / 其他占用 USB 的程序；\n"
                                        "  * 检查设备是否还在 2e09:0030；\n"
                                        "  * 固件半死时参考 docs/ch-firmware.md §7 重灌。")
                    # 不重发半帧：等下一帧从 16 B 头重新开始，设备会丢弃未完成的帧
                    time.sleep(0.2)
                    continue
                failed = 0
                frames += 1
                bytes_sent += n
                if first:
                    print("  首帧已写出（%d B），开始循环发送。" % n)
                    first = False
                now = time.perf_counter()
                if now - last_report >= 1.0:
                    dt = max(1e-6, now - start)
                    print("[%5.1fs] 帧=%d 失败=%d 平均=%.1f fps  %.1f MB/s" % (
                        dt, frames, failed, frames / dt, bytes_sent / dt / 1e6))
                    last_report = now
                if period:
                    spend = time.perf_counter() - t0
                    if spend < period:
                        time.sleep(period - spend)
    except KeyboardInterrupt:
        interrupted = True
    finally:
        dt = max(1e-6, time.perf_counter() - start)
        print("发送结束：成功 %d 帧，失败 %d 帧，耗时 %.2f s，平均 %.1f fps，%.1f MB/s%s" % (
            frames, failed, dt, frames / dt, bytes_sent / dt / 1e6,
            "（Ctrl-C 中断）" if interrupted else ""))
    return 0


# ---------------------------------------------------------------------------
# 子命令：uvc（probe/commit + 只读读帧）
# ---------------------------------------------------------------------------
def parse_probe(p: bytes) -> dict:
    d = {"_len": len(p)}
    d["bmHint"] = u16le(p, 0) if len(p) >= 2 else 0
    d["format"] = p[2] if len(p) >= 3 else 0
    d["frame"] = p[3] if len(p) >= 4 else 0
    d["interval"] = u32le(p, 4) if len(p) >= 8 else 0
    d["max_frame"] = u32le(p, 18) if len(p) >= 22 else 0
    d["max_payload"] = u32le(p, 22) if len(p) >= 26 else 0
    d["clock"] = u32le(p, 26) if len(p) >= 30 else 0
    d["framing"] = p[30] if len(p) >= 31 else 0
    d["versions"] = tuple(p[31:34]) if len(p) >= 34 else ()
    return d


def print_probe(p: bytes) -> None:
    d = parse_probe(p)
    fps = (1e7 / d["interval"]) if d["interval"] else 0.0
    print("  probe %d B: bmHint=0x%04x format=%d frame=%d interval=%d (%.2f fps)" % (
        d["_len"], d["bmHint"], d["format"], d["frame"], d["interval"], fps))
    print("            maxFrame=%d maxPayload=%d clock=%d framing=%d versions=%s" % (
        d["max_frame"], d["max_payload"], d["clock"], d["framing"], d["versions"]))
    if d["_len"] >= 34:
        print("  原始 34B : %s" % hexs(p[:34]))


def cmd_uvc(args) -> int:
    if args.frames <= 0:
        die("--frames 必须 > 0")
    dev = find_normal()
    print("设备: " + device_line(dev))
    vc, vs, ep = pick_uvc(dev)
    print("  VC interface: %s" % ("if=%d class=0x%02x sub=0x%02x" % (
        vc.bInterfaceNumber, vc.bInterfaceClass, vc.bInterfaceSubClass) if vc else "（未发现）"))
    print("  VS interface: if=%d alt=%d class=0x%02x sub=0x%02x  ep=0x%02x bulk IN mps=%d" % (
        vs.bInterfaceNumber, vs.bAlternateSetting, vs.bInterfaceClass,
        vs.bInterfaceSubClass, ep.bEndpointAddress, ep.wMaxPacketSize))
    with DeviceSession(dev, detach=args.detach, verbose=args.verbose) as s:
        if vc is not None:
            s.claim(vc.bInterfaceNumber, alt=vc.bAlternateSetting, label="UVC VC")
        s.claim(vs.bInterfaceNumber, alt=vs.bAlternateSetting, label="UVC VS")
        if vs.bAlternateSetting != 0:
            try:
                dev.set_interface(vs.bInterfaceNumber, vs.bAlternateSetting)
                print("  set_interface(%d, alt=%d) 成功" % (vs.bInterfaceNumber, vs.bAlternateSetting))
            except Exception as e:
                warn("set_interface 失败（继续）: %s" % e)

        # 1) GET_CUR(probe)：34 B（UVC 1.1），失败回退 26 B（UVC 1.0）
        probe = None
        last_err = None
        for length in (34, 26):
            try:
                data = ctrl_in(dev, UVC_GET_CUR, UVC_VS_PROBE, vs.bInterfaceNumber, length,
                               args.timeout)
                if len(data) >= 26:
                    probe = bytearray(data)
                    break
                last_err = "只返回 %d 字节" % len(data)
            except ToolError as e:
                last_err = str(e)
        if probe is None:
            raise ToolError("GET_CUR(probe) 失败：%s\n"
                            "  * 确认没有其他 App 占用摄像头；\n"
                            "  * Linux 上 uvcvideo 会占住 VS，需要 --detach 或先卸载 uvcvideo；\n"
                            "  * Windows 需要把接口绑定到 WinUSB（Zadig）。" % last_err)
        print("  GET_CUR(probe) 原始 %d B: %s" % (len(probe), hexs(probe)))
        print_probe(bytes(probe))

        cur = parse_probe(bytes(probe))
        fmt = args.format_index if args.format_index is not None else (cur["format"] or 1)
        frame = args.frame_index if args.frame_index is not None else (cur["frame"] or 4)
        interval = args.interval if args.interval is not None else (cur["interval"] or 333333)
        if args.fps is not None:
            if args.fps <= 0:
                die("--fps 必须 > 0")
            interval = max(1, int(round(10000000.0 / args.fps)))
        max_frame = args.max_frame if args.max_frame is not None else (cur["max_frame"] or 460800)
        max_payload = args.max_payload if args.max_payload is not None else (cur["max_payload"] or 16384)
        if max_payload < 1024:
            max_payload = 16384

        buf = bytearray(34)
        buf[0:2] = struct.pack("<H", 1)  # bmHint bit0 = dwFrameInterval 有效
        buf[2] = fmt & 0xFF
        buf[3] = frame & 0xFF
        struct.pack_into("<I", buf, 4, interval & 0xFFFFFFFF)
        struct.pack_into("<I", buf, 18, max_frame & 0xFFFFFFFF)
        struct.pack_into("<I", buf, 22, max_payload & 0xFFFFFFFF)
        print("  协商目标: format=%d frame=%d interval=%d (%.2f fps) maxFrame=%d maxPayload=%d" % (
            fmt, frame, interval, 1e7 / interval if interval else 0, max_frame, max_payload))

        # 2) SET_CUR(probe)
        send_len = 34 if len(probe) >= 34 else 26
        try:
            ctrl_out(dev, UVC_SET_CUR, UVC_VS_PROBE, vs.bInterfaceNumber,
                     bytes(buf[:send_len]), args.timeout)
        except ToolError as e:
            if send_len > 26:
                warn("SET_CUR(probe) 34 B 失败，回退 26 B: %s" % e)
                ctrl_out(dev, UVC_SET_CUR, UVC_VS_PROBE, vs.bInterfaceNumber,
                         bytes(buf[:26]), args.timeout)
                send_len = 26
            else:
                raise
        # 3) GET_CUR(probe) 看设备认可了什么
        committed = bytes(buf[:send_len])
        try:
            back = ctrl_in(dev, UVC_GET_CUR, UVC_VS_PROBE, vs.bInterfaceNumber, 34, args.timeout)
            if len(back) >= 26:
                print("  GET_CUR(probe) 回读:")
                print_probe(back)
                committed = back[:34]
        except ToolError as e:
            warn("GET_CUR(probe) 回读失败，用写入值 commit: %s" % e)

        if args.probe_only:
            print("--probe-only：已停在 probe，未发 commit、未读流。")
            return 0

        # 4) SET_CUR(commit)
        ctrl_out(dev, UVC_SET_CUR, UVC_VS_COMMIT, vs.bInterfaceNumber,
                 committed[:send_len], args.timeout)
        print("  commit 完成，开始读 %d 帧（只读）..." % args.frames)

        urb_size = max(64, max_frame + 22)
        print("  URB 大小: %d B（maxFrame %d + 22）" % (urb_size, max_frame))
        frames = 0
        urbs = 0
        timeouts = 0
        deadline = time.time() + max(5.0, args.timeout / 1000.0 * 3)
        while frames < args.frames and time.time() < deadline:
            try:
                data = dev.read(ep.bEndpointAddress, urb_size, timeout=args.timeout)
            except USBError as e:  # type: ignore[attr-defined]
                if is_timeout(e):
                    timeouts += 1
                    warn("读 URB 超时（%d/3）：设备没有出流。" % timeouts)
                    if timeouts >= 3:
                        raise ToolError(
                            "连续 3 次读 UVC 超时（0 帧）。\n"
                            "  * Linux：uvcvideo 可能占着 VS，请加 --detach 或卸载 uvcvideo；\n"
                            "  * 关闭厂商 App / 其他摄像头程序；\n"
                            "  * 控制面正常但 UVC 0 帧可能是固件媒体流水线卡死，参考 docs/ch-firmware.md §7。")
                    continue
                raise ToolError(usb_error_hint(e, "bulk IN 0x%02x" % ep.bEndpointAddress))
            if not data:
                warn("bulk IN 返回 0 字节")
                continue
            timeouts = 0
            buf2 = bytes(data)
            urbs += 1
            if len(buf2) < 2:
                warn("URB 只有 %d 字节，忽略" % len(buf2))
                continue
            hl = buf2[0]
            hi = buf2[1]
            hdr_ok = 2 <= hl <= 12 and hl <= len(buf2)
            flags = ("FID=%d EOF=%d PTS=%d SCR=%d ERR=%d STI=%d RES=%d EOH=%d" % (
                hi & 1, (hi >> 1) & 1, (hi >> 2) & 1, (hi >> 3) & 1,
                (hi >> 6) & 1, (hi >> 5) & 1, (hi >> 4) & 1, (hi >> 7) & 1))
            print("  URB#%d len=%d bHeaderLength=%d bmHeaderInfo=0x%02x (%s)" % (
                urbs, len(buf2), hl, hi, flags))
            print("    首 32B: %s" % hexs(buf2[:32]))
            if hdr_ok:
                if hi & 0x04 and len(buf2) >= 6:
                    print("    PTS=%d" % u32le(buf2, 2))
                if hi & 0x08 and len(buf2) >= 12:
                    print("    SCR=%s" % hexs(buf2[6:12]))
                if len(buf2) > hl:
                    print("    像素首 16B: %s" % hexs(buf2[hl:hl + 16]))
            else:
                warn("UVC header 无效（bHeaderLength=%d）" % hl)
            if (hi & 0x02) or (hdr_ok and len(buf2) - hl >= max_frame):
                frames += 1
                print("  -> 帧 %d 结束（EOF%s）" % (
                    frames, "" if hi & 0x02 else " 缺失，按长度兜底"))
        print("UVC 读取结束：%d 帧 / %d 个 URB。" % (frames, urbs))
    return 0


# ---------------------------------------------------------------------------
# 子命令：flash（默认 dry-run）
# ---------------------------------------------------------------------------
def find_fw_version(data: bytes):
    idx = data.find(b"G25_MainFW_")
    if idx < 0:
        return None, -1
    end = idx
    while end < len(data) and end < idx + 48 and 0x20 <= data[end] <= 0x7E:
        end += 1
    return data[idx:end].decode("ascii", "replace").strip(), idx


def analyze_firmware(path: str, args):
    try:
        with open(path, "rb") as f:
            raw = f.read()
    except OSError as e:
        die("无法读取固件 %s: %s" % (path, e))
    if not raw:
        die("固件文件为空: %s" % path)
    raw_sha = hashlib.sha256(raw).hexdigest()
    print("== 镜像分析 ==")
    print("  文件      : %s" % os.path.abspath(path))
    print("  原始大小  : %d B (0x%x)" % (len(raw), len(raw)))
    print("  SHA256    : %s" % raw_sha)
    magic = raw[:4]
    print("  magic     : %s (%s)" % (magic.decode("latin1", "replace"), hexs(magic)))
    if magic != b"MA2x":
        if args.yes and not args.force:
            die("镜像不以 MA2x 开头，可能不是 Myriad2/G25 固件。确认文件无误后可用 --force 强制发送。")
        warn("镜像不以 MA2x 开头；dry-run 继续分析（真写会被拒绝，除非加 --force）。")
    raw_ver, raw_off = find_fw_version(raw)
    print("  版本串    : %s" % (("%r @0x%x" % (raw_ver, raw_off)) if raw_ver else "（明文区未找到）"))

    decrypted = False
    if args.no_decrypt:
        payload = raw
        print("  解密      : 跳过（--no-decrypt），发送原文件")
    elif raw_ver is not None:
        payload = raw
        print("  解密      : 明文已含版本串，视为已解密，跳过")
    else:
        tail = min(len(raw), FW_TAIL_MAX)
        start = len(raw) - tail
        print("  尾部      : 0x%x..0x%x（%d B）" % (start, len(raw), tail))
        if tail % 16:
            if args.yes:
                die("尾部长度 %d B 不是 16 的整数倍，无法按 AES-ECB 解密；"
                    "若这是已解密镜像请加 --no-decrypt。" % tail)
            warn("尾部长度 %d B 不是 16 的整数倍；dry-run 用原文件算哈希，不尝试解密。" % tail)
            payload = raw
        else:
            print("  AES-128-ECB: 解密尾部（key=%r）..." % FW_AES_KEY.decode("ascii"))
            t0 = time.perf_counter()
            plain = aes128_ecb_decrypt(raw[start:], FW_AES_KEY)
            print("              完成，耗时 %.2f s" % (time.perf_counter() - t0))
            candidate = raw[:start] + plain
            ver, off = find_fw_version(candidate)
            if ver is None:
                if args.yes:
                    die("AES 解密后仍未找到 G25_MainFW_ 版本串；镜像可能不是 G25 固件/已损坏。\n"
                        "  若确认镜像是明文，可加 --no-decrypt 强制发送。")
                warn("AES 解密后未找到 G25_MainFW_ 版本串；dry-run 改用原文件算哈希（真写会被拒绝）。")
                payload = raw
            else:
                payload = candidate
                print("  版本串    : %r @0x%x（解密后）" % (ver, off))
                pad = plain[-1]
                pad_ok = 1 <= pad <= 16 and plain[-pad:] == bytes([pad]) * pad
                print("  PKCS#7    : 末字节=%d，填充%s（本工具保留完整长度发送，与 App 一致）" % (
                    pad, "有效" if pad_ok else "无效/可疑"))
                decrypted = True

    payload_sha = hashlib.sha256(payload).hexdigest()
    print("  将发送    : %d B (0x%x)" % (len(payload), len(payload)))
    print("  SHA256(发送): %s" % payload_sha)
    print("  分块      : %d B/包，共 %d 包，单包超时 %d ms" % (
        BOOT_CHUNK, (len(payload) + BOOT_CHUNK - 1) // BOOT_CHUNK, args.timeout))
    print("  目标协议  : 03e7:2150 interface 0，EP 0x01 bulk OUT，无 header/ACK/ZLP")
    return payload, raw_sha, payload_sha, decrypted


def cmd_flash(args) -> int:
    payload, raw_sha, payload_sha, decrypted = analyze_firmware(args.image, args)
    if args.sha256:
        expected = args.sha256.strip().lower().replace(":", "")
        if expected and expected != payload_sha:
            die("SHA256 不匹配：传入 %s，实际将发送 %s。未写入任何数据。" % (
                expected, payload_sha))
        print("  SHA256 校验: 通过（与 --sha256 一致）")

    try:
        boot = find_boot(required=False)
    except ToolError as e:
        if not args.yes:
            info("无法枚举 USB（dry-run 继续）: %s" % e)
            print("DRY-RUN：未打开设备、未写入任何数据。")
            return 0
        raise
    if boot is None:
        try:
            normal = find_normal(required=False)
        except ToolError:
            normal = None
        if normal is not None:
            hint = "检测到正常模式 2e09:0030，但没有引导模式 03e7:2150。"
        else:
            hint = "没有检测到 03e7:2150 引导设备。"
        if not args.yes:
            print(hint)
            print("DRY-RUN：未打开设备、未写入任何数据。")
            print("确认上面的 SHA256 后，让眼镜进入引导模式并加 --yes 真写：")
            print("  python code/glxss.py flash %s --yes" % args.image)
            return 0
        die(hint + "\n请让眼镜进入 Myriad ROM 引导模式（掉电重启/等待重枚举）后再试。")

    print("设备: " + device_line(boot))
    intf, ep = pick_bulk_out(boot, args.verbose)
    print("  端点: if=%d alt=%d class=0x%02x ep=0x%02x bulk OUT mps=%d" % (
        intf.bInterfaceNumber, intf.bAlternateSetting, intf.bInterfaceClass,
        ep.bEndpointAddress, ep.wMaxPacketSize))
    if not args.yes:
        print("DRY-RUN：检测到引导设备，但未打开、未写入任何数据。")
        print("镜像 SHA256 已打印；确认无误后加 --yes 真写。")
        return 0

    print("\n!!! 即将向 03e7:2150 写入固件 !!!")
    print("  SHA256 : %s" % payload_sha)
    print("  大小   : %d B，%d 包 × %d B" % (
        len(payload), (len(payload) + BOOT_CHUNK - 1) // BOOT_CHUNK, BOOT_CHUNK))
    print("  传输中不要拔线/断电；写完后设备会自行重启并重枚举为 2e09:0030。")
    with DeviceSession(boot, detach=args.detach, verbose=args.verbose) as s:
        s.claim(intf.bInterfaceNumber, alt=intf.bAlternateSetting, label="BOOT")
        total = len(payload)
        sent = 0
        t0 = time.perf_counter()
        last_report = t0
        next_pct = 0
        gone = False
        while sent < total:
            chunk = payload[sent:sent + BOOT_CHUNK]
            try:
                n = boot.write(ep.bEndpointAddress, chunk, args.timeout)
            except USBError as e:  # type: ignore[attr-defined]
                if is_no_device(e):
                    gone = True
                    print("\n  设备在 %d%% 处断开/重枚举（%s）" % (sent * 100 // total, e))
                    break
                raise ToolError(usb_error_hint(e, "刷写 bulk OUT（已写 %d/%d B）" % (sent, total)))
            if not n:
                raise ToolError("bulk OUT 返回 0 字节（offset %d/%d）" % (sent, total))
            sent += int(n)
            pct = sent * 100 // total
            now = time.perf_counter()
            if pct >= next_pct or now - last_report >= 1.0 or sent >= total:
                rate = sent / max(1e-6, now - t0) / 1e6
                print("  进度: %3d%%  %d/%d B  %.2f MB/s" % (pct, sent, total, rate))
                next_pct = (pct // 10 + 1) * 10
                last_report = now
        dt = max(1e-6, time.perf_counter() - t0)
        print("写出结束：%d/%d B，耗时 %.2f s，平均 %.2f MB/s%s" % (
            sent, total, dt, sent / dt / 1e6, "（设备已断开）" if gone else ""))
        if sent < total and not gone:
            warn("只写出了 %d/%d 字节，未完成。" % (sent, total))
            return 1
        print("等待重枚举为 2e09:0030（最多 8 s）...")
        deadline = time.time() + 8.0
        seen = False
        while time.time() < deadline:
            try:
                if find_normal(required=False) is not None:
                    seen = True
                    break
            except ToolError:
                pass
            time.sleep(0.5)
        if seen:
            print("已检测到 2e09:0030。建议运行：python code/glxss.py version")
        else:
            print("暂未检测到 2e09:0030；稍等几秒后运行 `python code/glxss.py list` 确认。")
    return 0


# ---------------------------------------------------------------------------
# 子命令：decode-uvc（App raw dump -> PNG）
# ---------------------------------------------------------------------------
def uvc_header_info(buf: bytes) -> dict:
    if len(buf) < 2:
        return {"ok": False}
    hl = buf[0]
    hi = buf[1]
    d = {
        "ok": 2 <= hl <= 12 and hl <= len(buf),
        "hl": hl,
        "info": hi,
        "fid": hi & 1,
        "eof": (hi >> 1) & 1,
        "pts_flag": (hi >> 2) & 1,
        "scr_flag": (hi >> 3) & 1,
        "err": (hi >> 6) & 1,
        "sti": (hi >> 5) & 1,
        "res": (hi >> 4) & 1,
        "eoh": (hi >> 7) & 1,
    }
    if d["ok"]:
        off = 2
        if d["pts_flag"] and len(buf) >= off + 4:
            d["pts"] = u32le(buf, off)
            off += 4
        if d["scr_flag"] and len(buf) >= off + 6:
            d["scr"] = buf[off:off + 6]
    return d


def fmt_uvc_header(d: dict) -> str:
    if not d.get("ok"):
        return "header 无效"
    s = "hl=%d info=0x%02x FID=%d EOF=%d PTS=%d SCR=%d ERR=%d STI=%d RES=%d EOH=%d" % (
        d["hl"], d["info"], d["fid"], d["eof"], d["pts_flag"], d["scr_flag"],
        d["err"], d["sti"], d["res"], d["eoh"])
    if "pts" in d:
        s += " PTS=%d" % d["pts"]
    if "scr" in d:
        s += " SCR=%s" % hexs(d["scr"])
    return s


def try_prefixed_urbs(blob: bytes):
    """识别 4 B 小端长度前缀 + URB 的 dump（re/liangliang 抓包格式）。"""
    pos = 0
    urbs = []
    n = len(blob)
    while pos + 8 <= n:
        length = u32le(blob, pos)
        if length < 2 or pos + 4 + length > n:
            return None
        body = blob[pos + 4:pos + 4 + length]
        if not (2 <= body[0] <= 12):
            return None
        urbs.append(body)
        pos += 4 + length
    return urbs if pos == n and urbs else None


def assemble_frames(urbs, frame_size: int, verbose: bool = False):
    frames = []
    cur = bytearray()
    in_frame = False
    bad = 0
    for i, urb in enumerate(urbs, 1):
        if len(urb) < 2:
            bad += 1
            continue
        d = uvc_header_info(urb)
        if i <= 3 or verbose:
            print("  URB#%-4d len=%-7d %s" % (i, len(urb), fmt_uvc_header(d)))
        if not d["ok"] or d["err"]:
            bad += 1
            if in_frame:
                cur += urb
            continue
        if not in_frame:
            cur = bytearray()
            in_frame = True
        cur += urb[d["hl"]:]
        if d["eof"] or len(cur) >= frame_size:
            frames.append(bytes(cur[:frame_size]))
            in_frame = False
            cur = bytearray()
    if cur and len(cur) >= frame_size:
        frames.append(bytes(cur[:frame_size]))
        in_frame = False
    return frames, bad


def parse_raw_uvc_stream(blob: bytes, frame_size: int, verbose: bool = False):
    """无长度前缀的连续 URB：本设备每个 URB 都带 EOF，且 header 后正好一帧。"""
    frames = []
    pos = 0
    n = len(blob)
    count = 0
    while pos + 2 <= n:
        hl = blob[pos]
        if not (2 <= hl <= 12) or pos + hl > n:
            warn("在偏移 0x%x 处未找到合法 UVC header（0x%02x），停止解析。" % (pos, hl))
            break
        d = uvc_header_info(blob[pos:pos + 12])
        count += 1
        if count <= 3 or verbose:
            print("  URB#%-4d @0x%-8x %s" % (count, pos, fmt_uvc_header(d)))
        start = pos + hl
        end = start + frame_size
        if end > n:
            warn("偏移 0x%x 处帧数据不足（需要 %d B，剩 %d B），忽略尾帧。" % (
                pos, frame_size, n - start))
            break
        frames.append(blob[start:end])
        # 找下一个 URB 头：正常应紧跟在帧尾；若 dump 的 URB 缓冲有 padding，允许 64 B 内搜索
        nxt = end
        if not (nxt + 2 <= n and 2 <= blob[nxt] <= 12):
            found = None
            for delta in range(0, 65):
                q = end + delta
                if q + 2 <= n and 2 <= blob[q] <= 12 and (blob[q + 1] & 0x02):
                    found = q
                    break
            if found is not None:
                nxt = found
        pos = nxt
    return frames


def yuv420_to_rgb(buf: bytes, w: int, h: int, order: str) -> bytes:
    ys = w * h
    cs = ys // 4
    cw = w // 2
    if len(buf) < ys + 2 * cs:
        die("YUV 数据不足：需要 %d B，实际 %d B" % (ys + 2 * cs, len(buf)))
    if order == "yv12":
        V = buf[ys:ys + cs]
        U = buf[ys + cs:ys + 2 * cs]
    else:
        U = buf[ys:ys + cs]
        V = buf[ys + cs:ys + 2 * cs]
    rgb = bytearray(w * h * 3)
    for y in range(h):
        yrow = y * w
        crow = (y >> 1) * cw
        o = yrow * 3
        for x in range(w):
            yy = buf[yrow + x] - 16
            if yy < 0:
                yy = 0
            c = crow + (x >> 1)
            u = U[c] - 128
            v = V[c] - 128
            r = (298 * yy + 409 * v + 128) >> 8
            g = (298 * yy - 100 * u - 208 * v + 128) >> 8
            b = (298 * yy + 516 * u + 128) >> 8
            rgb[o] = 0 if r < 0 else (255 if r > 255 else r)
            rgb[o + 1] = 0 if g < 0 else (255 if g > 255 else g)
            rgb[o + 2] = 0 if b < 0 else (255 if b > 255 else b)
            o += 3
    return bytes(rgb)


def plane_stats(name: str, b: bytes) -> str:
    if not b:
        return name + ": empty"
    n = len(b)
    return "%s: len=%d min=%d max=%d avg=%.1f" % (
        name, n, min(b), max(b), sum(b) / n)


def cmd_decode_uvc(args) -> int:
    if args.width <= 0 or args.height <= 0:
        die("--width/--height 必须为正数")
    try:
        with open(args.file, "rb") as f:
            blob = f.read()
    except OSError as e:
        die("无法读取 %s: %s" % (args.file, e))
    if not blob:
        die("输入文件为空")
    w, h = args.width, args.height
    frame_size = w * h * 3 // 2
    print("输入: %s（%d B），按 %dx%d YUV420（frame=%d B）解析" % (
        args.file, len(blob), w, h, frame_size))

    fmt = args.format
    frames = []
    urbs = None
    bad = 0
    if fmt == "auto":
        # 先试长度前缀：前缀文件的首字节（长度低字节）可能正好落在 2..12，
        # 所以不能先用 "首字节像 UVC header" 判断。
        urbs = try_prefixed_urbs(blob)
        if urbs:
            fmt = "prefixed"
            print("  识别为 4 B 长度前缀 + URB dump，共 %d 个 URB" % len(urbs))
        elif len(blob) >= 2 and 2 <= blob[0] <= 12:
            fmt = "stream"
        elif len(blob) == frame_size:
            fmt = "raw"
        else:
            die("无法识别 dump 格式：既不是合法 UVC header 开头，也不是长度前缀/裸 frame。\n"
                "  可用 --format frame|stream|prefixed|raw 显式指定。")
    if fmt == "prefixed":
        if urbs is None:
            urbs = try_prefixed_urbs(blob)
        if not urbs:
            die("不是合法的 4 B 长度前缀 + URB dump")
        print("  共 %d 个 URB" % len(urbs))
        frames, bad = assemble_frames(urbs, frame_size, args.verbose)
    elif fmt == "stream":
        frames = parse_raw_uvc_stream(blob, frame_size, args.verbose)
    elif fmt == "frame":
        d = uvc_header_info(blob)
        if not d["ok"]:
            die("文件开头不是合法 UVC payload header")
        print("  " + fmt_uvc_header(d))
        body = blob[d["hl"]:]
        if len(body) < frame_size:
            warn("帧数据不足（%d/%d B）" % (len(body), frame_size))
        frames = [body[:frame_size]] if body else []
    elif fmt == "raw":
        if len(blob) != frame_size:
            warn("裸 YUV 文件大小 %d B，与 %dx%d frame=%d B 不符；仍尝试解码。" % (
                len(blob), w, h, frame_size))
        frames = [blob]

    if not frames:
        die("没有解析出完整帧。可试 --format frame / --format raw，并确认 --width/--height。")
    if args.first:
        frames = frames[:1]
    if args.max_frames and args.max_frames > 0:
        frames = frames[:args.max_frames]
    if bad:
        warn("有 %d 个 URB header 无效/ERR，已跳过或按长度兜底。" % bad)
    print("解析出 %d 帧；YUV 顺序=%s，开始转 RGB..." % (len(frames), args.order.upper()))

    base = os.path.splitext(os.path.abspath(args.file))[0]
    if args.output:
        out_base = os.path.abspath(args.output)
        if out_base.lower().endswith(".png"):
            out_base = out_base[:-4]
    else:
        out_base = base
    out_dir = args.out_dir or os.path.dirname(out_base)
    if not os.path.isdir(out_dir):
        die("输出目录不存在: %s" % out_dir)
    stem = os.path.basename(out_base)
    paths = []
    for i, frame in enumerate(frames, 1):
        if len(frame) < frame_size:
            frame = frame + bytes(frame_size - len(frame))
        print("  帧 %d/%d: %s" % (i, len(frames), plane_stats("Y", frame[:w * h])))
        rgb = yuv420_to_rgb(frame, w, h, args.order)
        if len(frames) == 1:
            path = os.path.join(out_dir, stem + ".png")
        else:
            path = os.path.join(out_dir, "%s_f%04d.png" % (stem, i))
        write_png(path, w, h, rgb)
        paths.append(path)
    print("已写入 %d 个 PNG：" % len(paths))
    for p in paths:
        print("  " + p)
    return 0


# ---------------------------------------------------------------------------
# argparse / main
# ---------------------------------------------------------------------------
def add_timeout(p, default: int, text: str) -> None:
    p.add_argument("--timeout", type=int, default=default, metavar="MS", help=text)


class ChineseArgumentParser(argparse.ArgumentParser):
    """把 argparse 默认的 usage: 前缀换成中文。"""

    def format_usage(self):
        text = super().format_usage()
        return text.replace("usage:", "用法:", 1) if text.startswith("usage:") else text

    def format_help(self):
        text = super().format_help()
        return text.replace("usage:", "用法:", 1) if text.startswith("usage:") else text


def build_parser() -> argparse.ArgumentParser:
    parser = ChineseArgumentParser(
        prog="glxss.py",
        description=(
            "GLXSS G25（亮亮视野）PC 端调试工具。\n"
            "覆盖已逆向的 LCD 显示/控制、HID 传感器、UVC 只读探测、Myriad 引导刷写、raw UVC 解码。\n"
            "依赖：pyusb（仅硬件命令需要）。"),
        epilog=(
            "常用示例:\n"
            "  python code/glxss.py list\n"
            "  python code/glxss.py status\n"
            "  python code/glxss.py bright 180\n"
            "  python code/glxss.py screen on\n"
            "  python code/glxss.py mode 2\n"
            "  python code/glxss.py roll 10\n"
            "  python code/glxss.py eeprom --raw\n"
            "  python code/glxss.py version\n"
            "  python code/glxss.py hid --enable-sensors\n"
            "  python code/glxss.py frame bars --fps 10 --seconds 5\n"
            "  python code/glxss.py frame png shot.png --fit cover\n"
            "  python code/glxss.py uvc --frames 3\n"
            "  python code/glxss.py flash G25_MainFW_1.9.1.img          # 默认 dry-run\n"
            "  python code/glxss.py flash G25_MainFW_1.9.1.img --yes    # 真写\n"
            "  python code/glxss.py decode-uvc dump.bin\n"
            "\n"
            "安全：不实现写 EEPROM（0x21/0x06）；flash 必须显式 --yes；不建议在传输中拔线。\n"
            "详细说明见 code/README.md。"),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--version", action="version", version="glxss.py " + TOOL_VERSION)
    sub = parser.add_subparsers(dest="command", metavar="<命令>")

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--detach", action="store_true",
                        help="Linux：claim 前 detach 内核驱动（uvcvideo/hid-generic）；Windows 无效")
    common.add_argument("--verbose", "-v", action="store_true", help="打印更多调试信息")

    # list
    p = sub.add_parser(
        "list", parents=[common],
        description="列出本机 USB 设备，标记 2e09:0030 / 03e7:2150，并打印接口/端点。",
        help="列出 USB 设备并标记 GLXSS")
    p.add_argument("--brief", action="store_true", help="每台设备只打摘要（GLXSS 仍打接口/端点）")
    p.set_defaults(func=cmd_list)

    # status
    p = sub.add_parser(
        "status", parents=[common],
        description="读 LCD 状态：0xA1/0x04 wValue=0 wIndex=3 wLength=8。\n"
                    "解析宽高、屏开关、亮度、roll，并打印原始 hex。",
        help="读 LCD 状态（0xA1/0x04）")
    add_timeout(p, 2000, "控制传输超时 ms（默认 2000）")
    p.set_defaults(func=cmd_status)

    # bright
    p = sub.add_parser(
        "bright", parents=[common],
        description="设亮度：0xA1/0x01 wValue=亮度 wIndex=3 wLength=1。\n"
                    "实测有效范围 100..254；本工具接受 0..255 但越界会警告。",
        help="设亮度（0xA1/0x01）")
    p.add_argument("value", type=int, metavar="0-255", help="亮度值（实测 100..254）")
    p.add_argument("--no-verify", action="store_true", help="写完后不回读状态")
    add_timeout(p, 2000, "控制传输超时 ms（默认 2000）")
    p.set_defaults(func=cmd_bright)

    # screen
    p = sub.add_parser(
        "screen", parents=[common],
        description="屏开关：0xA1/0x03 wValue=1/0 wIndex=3 wLength=1。\n"
                    "屏被关后只发帧不会点亮，必须先 screen on。",
        help="屏开关（0xA1/0x03）")
    p.add_argument("state", choices=("on", "off"), metavar="on|off", help="on=开屏，off=关屏")
    p.add_argument("--no-verify", action="store_true", help="写完后不回读状态")
    add_timeout(p, 2000, "控制传输超时 ms（默认 2000）")
    p.set_defaults(func=cmd_screen)

    # mode
    p = sub.add_parser(
        "mode", parents=[common],
        description="显示模式：0xA1/0x05 wValue=模式 wIndex=3 wLength=1。\n"
                    "1=关/logo/待机，2=投屏开；0 未在真机验证。",
        help="显示模式（0xA1/0x05）")
    p.add_argument("value", type=int, choices=(0, 1, 2), metavar="0|1|2", help="显示模式")
    p.add_argument("--no-verify", action="store_true", help="写完后不回读状态")
    add_timeout(p, 2000, "控制传输超时 ms（默认 2000）")
    p.set_defaults(func=cmd_mode)

    # roll
    p = sub.add_parser(
        "roll", parents=[common],
        description="屏幕旋转：0xA1/0x02 wValue=roll wIndex=3 wLength=1。",
        help="设 roll（0xA1/0x02）")
    p.add_argument("value", type=int, metavar="0-65535", help="roll 值（wValue 16 位）")
    p.add_argument("--no-verify", action="store_true", help="写完后不回读状态")
    add_timeout(p, 2000, "控制传输超时 ms（默认 2000）")
    p.set_defaults(func=cmd_roll)

    # eeprom
    p = sub.add_parser(
        "eeprom", parents=[common],
        description="读 EEPROM 产品信息：0xA1/0x07 wValue=0x100 wIndex=3 wLength=264。\n"
                    "只读，安全；布局见 docs/ch-display.md §6 与 lcd/ProductInfo.kt。\n"
                    "本工具不会发 0x21/0x06（写 EEPROM，危险）。",
        help="读 EEPROM 产品信息（0xA1/0x07）")
    p.add_argument("--raw", action="store_true", help="额外打印 264 B 原始 hex")
    add_timeout(p, 5000, "控制传输超时 ms（默认 5000）")
    p.set_defaults(func=cmd_eeprom)

    # version
    p = sub.add_parser(
        "version", parents=[common],
        description="读运行固件版本：0xA1/0x17 wIndex=2 wLength=24。\n"
                    "前 16 B ASCII 版本串，后 8 B 设备 ID（u64 LE）。",
        help="读版本串 + 设备 ID（0xA1/0x17）")
    add_timeout(p, 5000, "控制传输超时 ms（默认 5000）")
    p.set_defaults(func=cmd_version)

    # hid
    p = sub.add_parser(
        "hid", parents=[common],
        description="循环读 28 B HID 报告：0xA1/0x16 wIndex=2 wLength=28。\n"
                    "按 struct.unpack('<BBH9hBBf') 解析：按键/lux/罗盘/加速度/陀螺/温度。\n"
                    "默认 300 ms 一次，Ctrl-C 退出。IMU 全 0 时可加 --enable-sensors。",
        help="实时轮询 HID 报告（0xA1/0x16）")
    p.add_argument("--interval", type=float, default=0.3, metavar="SEC",
                   help="轮询间隔秒（默认 0.3，和原厂一致）")
    p.add_argument("--count", type=int, default=0, metavar="N",
                   help="读取 N 次后退出（默认 0=一直读）")
    p.add_argument("--enable-sensors", action="store_true",
                   help="先发 0x10/0x11/0x12/0x14 wValue=1 使能光感/罗盘/加速度/陀螺")
    p.add_argument("--raw", action="store_true", help="每行都附 28 B 原始 hex")
    add_timeout(p, 2000, "单次控制传输超时 ms（默认 2000）")
    p.set_defaults(func=cmd_hid)

    # frame
    p = sub.add_parser(
        "frame", parents=[common],
        description="发送 640x400 RGBA 帧：16 B 头 + 1,024,000 B，EP 0x01 bulk OUT。\n"
                    "默认先发 0xA1/0x03=1 开屏、0xA1/0x05=2 切投屏（--no-init 可跳过）。\n"
                    "帧率/时长由 --fps / --seconds 控制，--loop 一直发到 Ctrl-C。",
        help="发送测试帧（16 B 头 + 640x400 RGBA）")
    p.add_argument("pattern", nargs="?", default=None,
                   metavar="<png|bars|solid|black|文件.png>",
                   help="帧源：png / bars / solid / black，或直接给 .png 路径")
    p.add_argument("path", nargs="?", metavar="[文件/颜色]",
                   help="pattern=png 时的 PNG 路径；pattern=solid 时可给 RRGGBB")
    p.add_argument("--png", dest="png_path", metavar="FILE",
                   help="等价于 frame png FILE（可省略 pattern）")
    p.add_argument("--fps", type=float, default=5.0, metavar="N",
                   help="目标帧率（默认 5；0=不限速）")
    p.add_argument("--seconds", type=float, default=5.0, metavar="SEC",
                   help="发送时长秒（默认 5）")
    p.add_argument("--loop", action="store_true", help="一直发送，直到 Ctrl-C（忽略 --seconds）")
    p.add_argument("--color", default="ff8000", metavar="RRGGBB",
                   help="solid 的颜色（默认 ff8000）")
    p.add_argument("--fit", choices=("fit", "cover", "stretch"), default="fit",
                   help="PNG 映射方式（默认 fit 留黑边；cover 裁剪；stretch 拉伸）")
    p.add_argument("--no-init", action="store_true", help="不发开屏/显示模式命令，只发帧")
    p.add_argument("--dry-run", action="store_true", help="只生成帧并打印头/哈希，不碰设备")
    add_timeout(p, 5000, "单帧 bulk 超时 ms（默认 5000）")
    p.set_defaults(func=cmd_frame)

    # uvc
    p = sub.add_parser(
        "uvc", parents=[common],
        description="UVC 只读探测：GET_CUR probe -> SET_CUR probe -> commit -> bulk IN 读几帧。\n"
                    "打印 12 B UVC payload header（FID/EOF/PTS/SCR/ERR）与首字节。\n"
                    "本命令不修改设备持久状态；失败时会给出占用/驱动提示。",
        help="UVC probe/commit + 读几帧（只读测试）")
    p.add_argument("--frames", type=int, default=3, metavar="N", help="读到多少帧后退出（默认 3）")
    p.add_argument("--probe-only", action="store_true", help="只做 GET_CUR/SET_CUR probe，不 commit、不读流")
    p.add_argument("--format-index", type=int, default=None, metavar="N", help="bFormatIndex（默认用回读值/1=YV12）")
    p.add_argument("--frame-index", type=int, default=None, metavar="N", help="bFrameIndex（默认用回读值/4=640x480）")
    p.add_argument("--interval", type=int, default=None, metavar="100NS",
                   help="dwFrameInterval（100 ns 单位，默认用回读值/333333）")
    p.add_argument("--fps", type=float, default=None, metavar="N",
                   help="按帧率换算 dwFrameInterval（覆盖 --interval）")
    p.add_argument("--max-frame", type=int, default=None, metavar="BYTES",
                   help="dwMaxVideoFrameSize（默认用回读值/460800）")
    p.add_argument("--max-payload", type=int, default=None, metavar="BYTES",
                   help="dwMaxPayloadTransferSize（默认用回读值/16384）")
    add_timeout(p, 2000, "控制/读流超时 ms（默认 2000）")
    p.set_defaults(func=cmd_uvc)

    # flash
    p = sub.add_parser(
        "flash", parents=[common],
        description="向 03e7:2150 引导模式写固件：1024 B/包，EP 0x01 bulk OUT，10 s 超时。\n"
                    "自动识别并解密 G25 .img 尾部 512 KiB（AES-128-ECB），也可 --no-decrypt。\n"
                    "默认 dry-run：只分析镜像并打印 SHA256；必须加 --yes 才真正写设备。",
        help="Myriad 引导刷写（默认 dry-run）")
    p.add_argument("image", metavar="<img>", help="G25 固件镜像，例如 G25_MainFW_1.9.1.img")
    p.add_argument("--yes", action="store_true", help="确认写入；不加只做 dry-run")
    p.add_argument("--no-decrypt", action="store_true", help="不自动解密尾部，直接发送原文件")
    p.add_argument("--force", action="store_true", help="跳过 MA2x magic 检查")
    p.add_argument("--sha256", default=None, metavar="HEX",
                   help="期望的发送镜像 SHA256；不匹配则拒绝写入")
    add_timeout(p, BOOT_TIMEOUT_MS, "单包 bulk 超时 ms（默认 10000）")
    p.set_defaults(func=cmd_flash)

    # decode-uvc
    p = sub.add_parser(
        "decode-uvc", parents=[common],
        description="把 App 抓到的 raw UVC dump 解成 PNG（YV12 默认，可选 I420）。\n"
                    "自动识别：单个/连续 URB、4 B 长度前缀 dump、裸 YUV frame。\n"
                    "思路同 re/liangliang/uvc_frame_decode.py，但已内置到本工具。",
        help="raw UVC dump -> PNG")
    p.add_argument("file", metavar="<file.bin>", help="raw UVC dump 文件")
    p.add_argument("-o", "--output", metavar="PNG", help="输出 PNG 路径（多帧时作为前缀）")
    p.add_argument("--out-dir", metavar="DIR", help="输出目录（默认与输入同目录）")
    p.add_argument("-W", "--width", type=int, default=640, help="图像宽度（默认 640）")
    p.add_argument("-H", "--height", type=int, default=480, help="图像高度（默认 480）")
    p.add_argument("--order", choices=("yv12", "i420"), default="yv12",
                   help="YUV 平面顺序（默认 yv12=Y|V|U；i420=Y|U|V）")
    p.add_argument("--format", choices=("auto", "frame", "stream", "prefixed", "raw"),
                   default="auto", help="dump 格式（默认 auto）")
    p.add_argument("--max-frames", type=int, default=0, metavar="N", help="最多解码 N 帧（默认 0=全部）")
    p.add_argument("--first", action="store_true", help="只解码第一帧")
    p.set_defaults(func=cmd_decode_uvc)

    _localize_parser(parser)
    return parser


def _localize_parser(parser: argparse.ArgumentParser) -> None:
    parser._positionals.title = "位置参数"
    parser._optionals.title = "选项"
    for action in parser._actions:
        if isinstance(action, argparse._HelpAction):
            action.help = "显示帮助并退出"
        elif isinstance(action, argparse._VersionAction):
            action.help = "显示版本并退出"
        if isinstance(action, argparse._SubParsersAction):
            for child in action.choices.values():
                _localize_parser(child)


def main(argv=None) -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
        except Exception:
            pass
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "command", None):
        parser.print_help()
        return 0
    # --png 备用写法
    if getattr(args, "command", None) == "frame" and getattr(args, "png_path", None):
        if args.pattern and args.pattern != "png" and not args.pattern.lower().endswith(".png"):
            parser.error("--png 不能与 frame %s 同时使用" % args.pattern)
        args.pattern = "png"
        args.path = args.png_path
    try:
        return int(args.func(args) or 0)
    except DeviceGone as e:
        print("错误: " + str(e), file=sys.stderr)
        return 1
    except ToolError as e:
        print("错误: " + str(e), file=sys.stderr)
        return 1
    except USBError as e:  # type: ignore[attr-defined]
        print("USB 错误: " + usb_error_hint(e), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n已中断（Ctrl-C）。", file=sys.stderr)
        return 130
    except BrokenPipeError:
        return 1


if __name__ == "__main__":
    sys.exit(main())
