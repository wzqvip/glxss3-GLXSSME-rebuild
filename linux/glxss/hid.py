# -*- coding: utf-8 -*-
"""HID / 传感器 / 机身 Fn 键。

协议出处 docs/ch-hid.md：
  * if2（class 0x03）每 300 ms 读 0xA1/0x16 wLength=28；
  * 28 B 布局：<BBH9hBBf>（keyStatus/keyCode/lux/罗盘/加速度/陀螺/AF/CAF/温度）；
  * 版本：0xA1/0x17 wLength=24（16 B ASCII 版本 + u64 设备 ID）；
  * 传感器开关：0xA1/0x10..0x14，wValue=0/1；
  * 不要读中断端点 0x83，原厂也只走 control 轮询。
"""

from __future__ import annotations

import struct
import time
from dataclasses import dataclass, asdict
from typing import Dict, Iterator, List, Optional

from . import usb
from .errors import GlxssError, ProtocolError
from .util import hexs

HID_IFACE = usb.IFACE_HID
HID_REPORT_LEN = 28
HID_PRODUCT_LEN = 24
SENSOR_POLL_INTERVAL = 0.3
KEY_CODE_FN = 0x71
# 长按判定：只看按住时长（默认 0.9 s），不要求按下包数。
# 100 ms 轮询时 1 s 的按压只有 ~10 个包；如果同时要求包数/时间，边缘抖动
# （某个按下包落在 0.89 s）会让“按满 1 秒”被误判成单击。包数仅用于日志。
FN_LONG_PRESS_SECONDS = 0.9
FN_LONG_PRESS_PACKETS = 4  # 兼容旧调用方；不再参与判定


@dataclass
class HidReport:
    key_status: int
    key_code: int
    lux: int
    compass_x: int
    compass_y: int
    compass_z: int
    acc_x: int
    acc_y: int
    acc_z: int
    gyro_x: int
    gyro_y: int
    gyro_z: int
    af_result: int
    caf_flag: int
    temperature: float

    @property
    def pressed(self) -> bool:
        return self.key_status == 1

    @property
    def is_fn_key(self) -> bool:
        return self.key_code == KEY_CODE_FN

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)

    def format(self) -> str:
        key = ("按下" if self.pressed else "松开") + "(0x%02x)" % self.key_code
        note = " Fn" if self.is_fn_key else ""
        return (
            "按键=%-10s lux=%-6d 罗盘=(%d,%d,%d) 加速度=(%d,%d,%d) "
            "陀螺=(%d,%d,%d) AF=%d CAF=%d 温度=%.2f°C" % (
                key + note, self.lux,
                self.compass_x, self.compass_y, self.compass_z,
                self.acc_x, self.acc_y, self.acc_z,
                self.gyro_x, self.gyro_y, self.gyro_z,
                self.af_result, self.caf_flag, self.temperature,
            )
        )


@dataclass
class FnEvent:
    """Fn 按键事件（由 `FnWatcher.feed()` 产生）。"""

    kind: str                  # press / release / click / long
    at: float                  # time.monotonic() 时间戳
    duration: float = 0.0      # 按下到当前/松开的时间
    packets: int = 0           # 连续“按下”报告数

    def format(self) -> str:
        if self.kind == "click":
            return "单击（%.2f s，%d 包）" % (self.duration, self.packets)
        if self.kind == "long":
            return "长按（%.2f s，%d 包）" % (self.duration, self.packets)
        if self.kind == "release":
            return "松开（%.2f s）" % (self.duration,)
        return "按下"


class FnWatcher:
    """把 28 B HID 报告流解析成 Fn 单击/长按事件（纯逻辑，便于离线单测）。

    设备在按住 Fn 时每个轮询包都是 ``keyStatus=1, keyCode=0x71``；松开后是
    ``keyStatus=0``。**长按只按时间判**：按住时长 ≥ ``long_seconds``（默认
    0.9 s）即触发，不再要求“连续 N 个按下包”。

    为什么去掉包数：100 ms 轮询时，1 s 的按压只有 ~10 个包；若某个按下包
    落在 0.89 s、下一个包之前就松开，旧逻辑（时间 AND 包数）会判成单击。
    现在只要松开时 ``duration >= long_seconds``，即使按住期间没有包落在
    阈值之后，也会补发 ``long`` 而不是 ``click``。

    ``long_packets`` 参数仅为兼容旧调用方保留，不参与判定；包数仍写进
    ``FnEvent`` 便于日志排查。
    """

    def __init__(self, long_seconds: float = FN_LONG_PRESS_SECONDS,
                 long_packets: int = FN_LONG_PRESS_PACKETS):
        self.long_seconds = max(0.2, float(long_seconds))
        self.long_packets = max(0, int(long_packets))  # 兼容字段，不参与判定
        self._down = False
        self._down_at = 0.0
        self._packets = 0
        self._long_fired = False

    @property
    def down(self) -> bool:
        return self._down

    def reset(self) -> None:
        self._down = False
        self._down_at = 0.0
        self._packets = 0
        self._long_fired = False

    def feed(self, report: HidReport, now: Optional[float] = None) -> List[FnEvent]:
        """喂一个报告，返回本次产生的事件（0..2 个）。"""
        at = time.monotonic() if now is None else float(now)
        down = bool(report.is_fn_key and report.pressed)
        events: List[FnEvent] = []
        if down:
            if not self._down:
                self._down = True
                self._down_at = at
                self._packets = 1
                self._long_fired = False
                events.append(FnEvent("press", at, 0.0, 1))
            else:
                self._packets += 1
                elapsed = at - self._down_at
                if not self._long_fired and elapsed >= self.long_seconds:
                    self._long_fired = True
                    events.append(FnEvent("long", at, elapsed, self._packets))
            return events
        if not self._down:
            return events
        duration = at - self._down_at
        packets = self._packets
        self._down = False
        self._down_at = 0.0
        self._packets = 0
        events.append(FnEvent("release", at, duration, packets))
        if not self._long_fired and duration >= self.long_seconds:
            # 松开前的最后一个按下包还没到阈值（轮询间隔边缘），但实际按住
            # 时间已经够了 —— 按长按处理，避免“按满 1 秒却只算单击”。
            self._long_fired = True
            events.append(FnEvent("long", at, duration, packets))
        elif not self._long_fired:
            events.append(FnEvent("click", at, duration, packets))
        self._long_fired = False
        return events


@dataclass
class HidVersion:
    raw: bytes
    version: str
    device_id: Optional[int]

    def format(self) -> str:
        lines = ["  原始 hex : %s" % hexs(self.raw),
                 "  版本串   : %r" % self.version]
        if self.device_id is None:
            lines.append("  设备 ID  : （数据不足 8 B）")
        else:
            lines.append("  设备 ID  : %d (0x%016x)" % (self.device_id, self.device_id))
        return "\n".join(lines)


def parse_report(data: bytes) -> HidReport:
    if len(data) != HID_REPORT_LEN:
        raise ProtocolError("HID 报告长度 %d，预期 %d" % (len(data), HID_REPORT_LEN))
    values = struct.unpack("<BBH9hBBf", bytes(data))
    return HidReport(
        key_status=values[0],
        key_code=values[1],
        lux=values[2],
        compass_x=values[3],
        compass_y=values[4],
        compass_z=values[5],
        acc_x=values[6],
        acc_y=values[7],
        acc_z=values[8],
        gyro_x=values[9],
        gyro_y=values[10],
        gyro_z=values[11],
        af_result=values[12],
        caf_flag=values[13],
        temperature=values[14],
    )


def read_report(dev, timeout: int = 5000) -> HidReport:
    data = usb.ctrl_in(dev, 0x16, 0, HID_IFACE, HID_REPORT_LEN, timeout)
    return parse_report(data)


def read_version(dev, timeout: int = 5000) -> HidVersion:
    data = usb.ctrl_in(dev, 0x17, 0, HID_IFACE, HID_PRODUCT_LEN, timeout)
    version = bytes(data[:16]).split(b"\x00", 1)[0].decode("ascii", "replace").strip("\r\n ")
    device_id = struct.unpack_from("<Q", data, 16)[0] if len(data) >= 24 else None
    return HidVersion(raw=bytes(data), version=version, device_id=device_id)


def enable_sensors(dev, timeout: int = 5000, verbose: bool = False) -> List[str]:
    """使能光感/罗盘/加速度/陀螺；返回每项的 ack 描述。"""
    results = []
    for request, name in ((0x10, "光感"), (0x11, "罗盘"),
                          (0x12, "加速度"), (0x14, "陀螺")):
        try:
            data = usb.ctrl_in(dev, request, 1, HID_IFACE, 1, timeout)
            ack = data[0] if data else 0
            results.append("%s(0x%02x) ack=0x%02x" % (name, request, ack))
            if verbose:
                print("    " + results[-1])
        except GlxssError as exc:
            results.append("%s(0x%02x) 失败: %s" % (name, request, exc))
            if verbose:
                print("    " + results[-1])
    return results


def set_sensor(dev, request: int, enabled: bool, timeout: int = 5000) -> int:
    data = usb.ctrl_in(dev, request, 1 if enabled else 0, HID_IFACE, 1, timeout)
    return data[0] if data else 0


def set_led(dev, on: bool, timeout: int = 5000) -> int:
    """0x19 指示灯；docs 里属于已解请求。"""
    data = usb.ctrl_in(dev, 0x19, 1 if on else 0, HID_IFACE, 1, timeout)
    return data[0] if data else 0


def poll_reports(dev, interval: float = SENSOR_POLL_INTERVAL,
                 count: int = 0, timeout: int = 5000) -> Iterator[HidReport]:
    """按 300 ms 轮询；count=0 表示直到调用方 break。"""
    sent = 0
    next_at = time.monotonic()
    while count <= 0 or sent < count:
        report = read_report(dev, timeout)
        sent += 1
        yield report
        next_at += max(0.0, interval)
        delay = next_at - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        else:
            next_at = time.monotonic()


def scales_from_attribute(attribute: bytes):
    """从 0x06 的 16 B IMU 属性里取 acc/gyro FSR 档位，返回物理量换算系数。"""
    if len(attribute) < 6:
        return None
    acc_fsr = min(attribute[2], 3)
    gyro_fsr = min(attribute[5], 3)
    acc_g = (2, 4, 8, 16)[acc_fsr] / 32768.0
    gyro_dps = (250, 500, 1000, 2000)[gyro_fsr] / 32768.0
    return {"acc_fsr": acc_fsr, "gyro_fsr": gyro_fsr,
            "acc_g_per_lsb": acc_g, "gyro_dps_per_lsb": gyro_dps}
