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
