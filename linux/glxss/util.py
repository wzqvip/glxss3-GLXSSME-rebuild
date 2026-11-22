# -*- coding: utf-8 -*-
"""小工具：hex 显示、ASCII 字段、时间戳。"""

from __future__ import annotations

import time


def hexs(data, sep: str = " ") -> str:
    return sep.join("%02x" % b for b in bytes(data))


def hexdump(data, width: int = 16, prefix: str = "  ") -> str:
    data = bytes(data)
    lines = []
    for offset in range(0, len(data), width):
        chunk = data[offset:offset + width]
        text = "".join(chr(b) if 0x20 <= b <= 0x7E else "." for b in chunk)
        lines.append(prefix + ("%04x  " % offset) + hexs(chunk).ljust(width * 3 - 1) + "  " + text)
    return "\n".join(lines)


def fmt_ascii(data: bytes, start: int, length: int) -> str:
    chunk = data[start:start + length]
    text = "".join(chr(b) if 0x20 <= b <= 0x7E else "." for b in chunk)
    return text.strip(" .")


def fmt_hex(data: bytes, start: int, length: int) -> str:
    return hexs(data[start:start + length], "")


def now_str() -> str:
    t = time.time()
    return time.strftime("%H:%M:%S", time.localtime(t)) + (".%03d" % int((t % 1) * 1000))


def human_rate(bytes_per_sec: float) -> str:
    if bytes_per_sec >= 1e6:
        return "%.2f MB/s" % (bytes_per_sec / 1e6)
    if bytes_per_sec >= 1e3:
        return "%.1f kB/s" % (bytes_per_sec / 1e3)
    return "%.0f B/s" % bytes_per_sec
