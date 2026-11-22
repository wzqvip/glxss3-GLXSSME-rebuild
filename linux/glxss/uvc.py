# -*- coding: utf-8 -*-
"""UVC 摄像头：probe/commit + bulk IN 读帧 + YV12→RGB。

协议出处 docs/ch-uvc.md：
  * VC if0（class 0x0e/0x01）、VS if1（0x0e/0x02），VS 的 alt=0 就带 bulk IN 0x81；
  * probe 34 B（UVC 1.10），wIndex=VS 接口号，wValue=0x0100(probe)/0x0200(commit)；
  * 每个 URB 开头是 12 B UVC payload header（0c 0f ...），后面 460800 B YV12；
  * YV12 = Y | V | U，640×480，无 stride padding；转 RGB 使用 BT.601 limited range。

numpy 只在真正解码时导入，保证没装 numpy 时 `glxss --help` / `list` 仍可用。
"""

from __future__ import annotations

import struct
import time
from dataclasses import dataclass
from typing import Iterator, Optional, Tuple

from . import usb
from .errors import GlxssError, ProtocolError
from .util import hexs

UVC_GET_CUR = 0x81
UVC_GET_MIN = 0x82
UVC_GET_MAX = 0x83
UVC_GET_DEF = 0x87
UVC_SET_CUR = 0x01
UVC_VS_PROBE = 0x0100
UVC_VS_COMMIT = 0x0200

DEFAULT_WIDTH = 640
DEFAULT_HEIGHT = 480
DEFAULT_FRAME_SIZE = DEFAULT_WIDTH * DEFAULT_HEIGHT * 3 // 2  # 460800
DEFAULT_INTERVAL = 333333  # 100 ns 单位 → 30 fps
DEFAULT_MAX_PAYLOAD = 16384


# ---------------------------------------------------------------------------
# UVC payload header
# ---------------------------------------------------------------------------
@dataclass
class UvcHeader:
    header_len: int
    info: int
    pts: Optional[int] = None
    scr: Optional[bytes] = None
    raw: bytes = b""

    @property
    def valid(self) -> bool:
        return 2 <= self.header_len <= 12 and self.header_len <= len(self.raw)

    @property
    def fid(self) -> int:
        return self.info & 0x01

    @property
    def eof(self) -> bool:
        return bool(self.info & 0x02)

    @property
    def err(self) -> bool:
        return bool(self.info & 0x40)

    def format(self) -> str:
        if not self.valid:
            return "header 无效（bHeaderLength=%d）" % self.header_len
        text = "hl=%d info=0x%02x FID=%d EOF=%d ERR=%d" % (
            self.header_len, self.info, self.fid, self.eof, self.err)
        if self.pts is not None:
            text += " PTS=%d" % self.pts
        if self.scr is not None:
            text += " SCR=%s" % hexs(self.scr)
        return text


def parse_header(data: bytes) -> UvcHeader:
    if len(data) < 2:
        return UvcHeader(0, 0, raw=bytes(data))
    header_len = data[0]
    info = data[1]
    pts = None
    scr = None
    offset = 2
    if info & 0x04 and len(data) >= offset + 4:
        pts = struct.unpack_from("<I", data, offset)[0]
        offset += 4
    if info & 0x08 and len(data) >= offset + 6:
        scr = bytes(data[offset:offset + 6])
    return UvcHeader(header_len, info, pts, scr, bytes(data[:12]))


# ---------------------------------------------------------------------------
# probe/commit
# ---------------------------------------------------------------------------
@dataclass
class Probe:
    raw: bytes
    bm_hint: int
    format_index: int
    frame_index: int
    interval: int
    max_frame: int
    max_payload: int
    clock: int
    framing: int
    versions: Tuple[int, ...]

    @classmethod
    def parse(cls, data: bytes) -> "Probe":
        if len(data) < 26:
            raise ProtocolError("probe 只有 %d 字节（UVC 1.0 至少 26）" % len(data))
        return cls(
            raw=bytes(data),
            bm_hint=struct.unpack_from("<H", data, 0)[0],
            format_index=data[2],
            frame_index=data[3],
            interval=struct.unpack_from("<I", data, 4)[0],
            max_frame=struct.unpack_from("<I", data, 18)[0],
            max_payload=struct.unpack_from("<I", data, 22)[0],
            clock=struct.unpack_from("<I", data, 26)[0] if len(data) >= 30 else 0,
            framing=data[30] if len(data) >= 31 else 0,
            versions=tuple(data[31:34]) if len(data) >= 34 else (),
        )

    @property
    def fps(self) -> float:
        return (1e7 / self.interval) if self.interval else 0.0

    def descriptor(self, format_index: Optional[int] = None,
                   frame_index: Optional[int] = None,
                   interval: Optional[int] = None,
                   max_frame: Optional[int] = None,
                   max_payload: Optional[int] = None) -> bytes:
        """按现有 probe 基底生成 34 B（或 26 B）SET_CUR 结构。"""
        length = 34 if len(self.raw) >= 34 else 26
        buffer = bytearray(self.raw[:length].ljust(length, b"\x00"))
        struct.pack_into("<H", buffer, 0, 0x0001)  # dwFrameInterval 有效
        buffer[2] = (self.format_index if format_index is None else format_index) & 0xFF
        buffer[3] = (self.frame_index if frame_index is None else frame_index) & 0xFF
        struct.pack_into("<I", buffer, 4,
                         (self.interval if interval is None else interval) & 0xFFFFFFFF)
        struct.pack_into("<I", buffer, 18,
                         (self.max_frame if max_frame is None else max_frame) & 0xFFFFFFFF)
        struct.pack_into("<I", buffer, 22,
                         (self.max_payload if max_payload is None else max_payload) & 0xFFFFFFFF)
        return bytes(buffer)

    def format(self) -> str:
        return (
            "probe %d B: bmHint=0x%04x format=%d frame=%d interval=%d (%.2f fps)\n"
            "            maxFrame=%d maxPayload=%d clock=%d framing=%d versions=%s" % (
                len(self.raw), self.bm_hint, self.format_index, self.frame_index,
                self.interval, self.fps, self.max_frame, self.max_payload,
                self.clock, self.framing, self.versions))


# ---------------------------------------------------------------------------
# 读流 / 组帧
# ---------------------------------------------------------------------------
@dataclass
class UvcFrame:
    index: int
    data: bytes
    width: int
    height: int
    header: UvcHeader
    timestamp: float

    @property
    def frame_size(self) -> int:
        return len(self.data)

    def rgb(self, order: str = "yv12"):
        return yv12_to_rgb(self.data, self.width, self.height, order=order)

    def rgba(self, order: str = "yv12"):
        rgb = self.rgb(order=order)
        try:
            import numpy as np  # type: ignore
        except Exception as exc:
            raise GlxssError("UVC→RGBA 需要 numpy：python -m pip install numpy") from exc
        alpha = np.full((self.height, self.width, 1), 255, dtype=np.uint8)
        return np.concatenate([rgb, alpha], axis=2)

    def save_png(self, path: str, order: str = "yv12") -> None:
        try:
            from PIL import Image  # type: ignore
        except Exception as exc:
            raise GlxssError("保存 PNG 需要 Pillow：python -m pip install Pillow") from exc
        Image.fromarray(self.rgb(order=order), "RGB").save(path)


class UvcStream:
    """已 claim 的 UVC VS 接口封装。"""

    def __init__(self, dev, vs_interface, endpoint, timeout: int = 5000,
                 verbose: bool = False):
        self.dev = dev
        self.vs = vs_interface
        self.ep = endpoint
        self.timeout = timeout
        self.verbose = verbose
        self.probe: Optional[Probe] = None
        self.frame_size = DEFAULT_FRAME_SIZE
        self.width = DEFAULT_WIDTH
        self.height = DEFAULT_HEIGHT
        self._frame_index = 0

    @classmethod
    def open(cls, dev, session, timeout: int = 5000, verbose: bool = False) -> "UvcStream":
        vc, vs, ep = usb.pick_uvc(dev)
        if vc is not None:
            session.claim_interface(vc, label="UVC VC")
        session.claim_interface(vs, label="UVC VS")
        if vs.bAlternateSetting != 0:
            try:
                dev.set_interface(vs.bInterfaceNumber, vs.bAlternateSetting)
            except Exception as exc:
                if verbose:
                    print("  set_interface(%d, alt=%d) 失败（继续）: %s" % (
                        vs.bInterfaceNumber, vs.bAlternateSetting, exc))
        stream = cls(dev, vs, ep, timeout=timeout, verbose=verbose)
        if verbose:
            print("  VC: %s" % ("if=%d" % vc.bInterfaceNumber if vc is not None else "未发现"))
            print("  VS: if=%d alt=%d ep=0x%02x" % (
                vs.bInterfaceNumber, vs.bAlternateSetting, ep.bEndpointAddress))
        return stream

    def _ctrl_in(self, request: int, value: int, length: int, timeout: int) -> bytes:
        return usb.ctrl_in(self.dev, request, value, self.vs.bInterfaceNumber,
                           length, timeout)

    def _ctrl_out(self, request: int, value: int, data: bytes, timeout: int) -> int:
        return usb.ctrl_out(self.dev, request, value, self.vs.bInterfaceNumber,
                            data, timeout)

    def get_probe(self, timeout: int = 5000) -> Probe:
        last_error = None
        for length in (34, 26):
            try:
                data = self._ctrl_in(UVC_GET_CUR, UVC_VS_PROBE, length, timeout)
            except GlxssError as exc:
                last_error = exc
                continue
            if len(data) >= 26:
                return Probe.parse(data)
            last_error = "只返回 %d 字节" % len(data)
        raise GlxssError(
            "GET_CUR(probe) 失败：%s\n"
            "  * 确认没有其他 App 占用摄像头；\n"
            "  * Linux 上 uvcvideo 会占住 VS，需要 --detach；\n"
            "  * Windows 需要将 UVC 接口绑定到 WinUSB（Zadig）。" % (last_error,))

    def negotiate(self, format_index: Optional[int] = None,
                  frame_index: Optional[int] = None, fps: Optional[float] = None,
                  max_frame: Optional[int] = None,
                  max_payload: Optional[int] = None,
                  timeout: Optional[int] = None) -> Probe:
        timeout_ms = timeout or self.timeout
        current = self.get_probe(timeout_ms)
        if self.verbose:
            print("  GET_CUR(probe):")
            print("    " + current.format().replace("\n", "\n    "))
        interval = current.interval or DEFAULT_INTERVAL
        if fps is not None:
            if fps <= 0:
                raise GlxssError("--fps 必须 > 0")
            interval = max(1, int(round(1e7 / fps)))
        target_frame = current.max_frame or (self.width * self.height * 3 // 2)
        descriptor = current.descriptor(
            format_index=format_index,
            frame_index=frame_index,
            interval=interval,
            max_frame=max_frame or target_frame,
            max_payload=max_payload or current.max_payload or DEFAULT_MAX_PAYLOAD,
        )
        send_len = 34 if len(descriptor) >= 34 else 26
        try:
            self._ctrl_out(UVC_SET_CUR, UVC_VS_PROBE, descriptor[:send_len], timeout_ms)
        except GlxssError:
            if send_len == 34:
                self._ctrl_out(UVC_SET_CUR, UVC_VS_PROBE, descriptor[:26], timeout_ms)
                send_len = 26
            else:
                raise
        committed = descriptor[:send_len]
        try:
            back = self._ctrl_in(UVC_GET_CUR, UVC_VS_PROBE, 34, timeout_ms)
            if len(back) >= 26:
                committed = back[:34]
                if self.verbose:
                    print("  GET_CUR(probe) 回读:")
                    print("    " + Probe.parse(back).format().replace("\n", "\n    "))
        except GlxssError:
            pass
        self._ctrl_out(UVC_SET_CUR, UVC_VS_COMMIT, committed[:send_len], timeout_ms)
        probe = Probe.parse(committed)
        self.probe = probe
        if not self.width or not self.height:
            self.width, self.height = DEFAULT_WIDTH, DEFAULT_HEIGHT
        # 以当前档位为准；CLI 可显式传 max_frame 覆盖。
        self.frame_size = max_frame or probe.max_frame or (self.width * self.height * 3 // 2)
        return probe

    def set_dimensions(self, width: int, height: int) -> None:
        if width <= 0 or height <= 0 or width % 2 or height % 2:
            raise GlxssError("UVC 宽高必须是正偶数（当前 %dx%d）" % (width, height))
        self.width = width
        self.height = height
        self.frame_size = width * height * 3 // 2

    def read_urb(self, timeout: Optional[int] = None) -> bytes:
        size = max(64, self.frame_size + 22)
        return usb.bulk_read(self.dev, self.ep.bEndpointAddress, size,
                             timeout or self.timeout)

    def read_frame(self, timeout: Optional[int] = None) -> UvcFrame:
        timeout_ms = timeout or self.timeout
        deadline = time.monotonic() + timeout_ms / 1000.0
        buffer = bytearray()
        in_frame = False
        while time.monotonic() < deadline:
            urb = self.read_urb(timeout_ms)
            if len(urb) < 2:
                continue
            header = parse_header(urb)
            if not header.valid or header.err:
                # header 损坏时丢掉当前半帧，等下一个 FID/EOF 重新同步。
                in_frame = False
                buffer = bytearray()
                if self.verbose:
                    print("  [UVC] " + header.format())
                continue
            if not in_frame:
                buffer = bytearray()
                in_frame = True
            buffer.extend(urb[header.header_len:])
            if header.eof or len(buffer) >= self.frame_size:
                if len(buffer) < self.frame_size:
                    # EOF 提前到达：整帧不完整，丢弃。
                    in_frame = False
                    continue
                self._frame_index += 1
                return UvcFrame(
                    index=self._frame_index,
                    data=bytes(buffer[:self.frame_size]),
                    width=self.width,
                    height=self.height,
                    header=header,
                    timestamp=time.time(),
                )
        raise GlxssError("UVC 读帧超时（%.1f s 内没有完整帧）" % (timeout_ms / 1000.0))

    def read_frames(self, count: int = 1, timeout: Optional[int] = None) -> Iterator[UvcFrame]:
        sent = 0
        while count <= 0 or sent < count:
            yield self.read_frame(timeout=timeout)
            sent += 1


# ---------------------------------------------------------------------------
# YV12 → RGB（numpy）
# ---------------------------------------------------------------------------
def yv12_to_rgb(data: bytes, width: int, height: int, order: str = "yv12"):
    """把 YUV420 平面数据转换成 HxWx3 uint8 RGB。

    order="yv12"：Y | V | U（本设备）
    order="i420"：Y | U | V
    """
    try:
        import numpy as np  # type: ignore
    except Exception as exc:
        raise GlxssError("YV12→RGB 需要 numpy：python -m pip install numpy") from exc
    if width <= 0 or height <= 0 or width % 2 or height % 2:
        raise GlxssError("YUV420 宽高必须是正偶数（当前 %dx%d）" % (width, height))
    y_size = width * height
    c_size = y_size // 4
    expected = y_size + 2 * c_size
    if len(data) < expected:
        raise ProtocolError("YUV 数据不足：需要 %d B，实际 %d B" % (expected, len(data)))
    raw = np.frombuffer(data, dtype=np.uint8, count=expected)
    y = raw[:y_size].reshape(height, width).astype(np.int32) - 16
    np.maximum(y, 0, out=y)
    cw, ch = width // 2, height // 2
    if order.lower() == "yv12":
        v_plane = raw[y_size:y_size + c_size].reshape(ch, cw).astype(np.int32) - 128
        u_plane = raw[y_size + c_size:y_size + 2 * c_size].reshape(ch, cw).astype(np.int32) - 128
    else:
        u_plane = raw[y_size:y_size + c_size].reshape(ch, cw).astype(np.int32) - 128
        v_plane = raw[y_size + c_size:y_size + 2 * c_size].reshape(ch, cw).astype(np.int32) - 128
    u = np.repeat(np.repeat(u_plane, 2, axis=0), 2, axis=1)[:height, :width]
    v = np.repeat(np.repeat(v_plane, 2, axis=0), 2, axis=1)[:height, :width]
    r = (298 * y + 409 * v + 128) >> 8
    g = (298 * y - 100 * u - 208 * v + 128) >> 8
    b = (298 * y + 516 * u + 128) >> 8
    rgb = np.stack([r, g, b], axis=2)
    return np.clip(rgb, 0, 255).astype(np.uint8)


def rgb_to_rgba(rgb):
    """RGB uint8 → RGBA（alpha=255），供 LCD 帧使用。"""
    try:
        import numpy as np  # type: ignore
    except Exception as exc:
        raise GlxssError("打包 RGBA 需要 numpy：python -m pip install numpy") from exc
    if rgb.ndim != 3 or rgb.shape[2] != 3:
        raise GlxssError("RGB 数组形状应为 HxWx3，实际 %r" % (rgb.shape,))
    alpha = np.full(rgb.shape[:2] + (1,), 255, dtype=np.uint8)
    return np.ascontiguousarray(np.concatenate([rgb.astype(np.uint8), alpha], axis=2))
