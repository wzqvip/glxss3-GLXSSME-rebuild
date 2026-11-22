# -*- coding: utf-8 -*-
"""UVC 摄像头：probe/commit + bulk IN 读帧 + YV12→RGB。

协议出处 docs/ch-uvc.md：
  * VC if0（class 0x0e/0x01）、VS if1（0x0e/0x02），VS 的 alt=0 就带 bulk IN 0x81；
  * probe 34 B（UVC 1.10），wIndex=VS 接口号，wValue=0x0100(probe)/0x0200(commit)；
  * 设备侧一帧 = 12 B UVC payload header（0c 0f ...）+ 460800 B YV12，整帧一个 header；
  * **主机侧单次 bulk 读最多 16384 B**（usbfs 单 URB 上限 / libusb
    MAX_BULK_BUFFER_LENGTH，与 Android 一致）。读线程按 16 KiB 分块，再在字节流上
    按 `hdrLen + frameSize` 组帧（`_take_frame`），不能拿 460822 一次读；
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

try:  # monitor 快路径需要 numpy；缺失时 uvc --help / 协议命令仍可用
    import numpy as np  # type: ignore
except Exception:  # pragma: no cover - 取决于环境
    np = None  # type: ignore

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
# 与 Android UvcCamera 实测一致：bFormatIndex=1（YV12）、bFrameIndex=4（640×480@30）。
# 不能沿用 GET_CUR 的当前帧号：设备状态在会话之间残留，半坏状态（frame=1 1080p +
# maxPayload=GET_MAX 全局上限）下 probe/commit 都会成功但 bulk IN 一个字节都不来。
DEFAULT_FORMAT_INDEX = 1
DEFAULT_FRAME_INDEX = 4
# 主机侧单次 bulk 读取上限：usbfs 每个 URB 最大 16384 B（与 libusb
# MAX_BULK_BUFFER_LENGTH 一致，Android 实测 460822 会立刻返回 -1）。
# Linux 的 libusb 会在内部把大请求拆成 16 KiB URB，但那属于后端实现细节：
# 显式按 16 KiB 读，主机行为才与 Android 逐字节一致，也不依赖 libusb 版本。
BULK_CHUNK = 16384
# UVC payload header 最长 12 B（2 + PTS 4 + SCR 6）。
MAX_HEADER = 12
# 原厂 libuvc：frameBufferSize = dwMaxVideoFrameSize + 22 = 460822。
# 这是**逻辑帧缓冲**（仅用于日志/统计），不是单次 USB 读尺寸；实际读取用
# BULK_CHUNK=16384，再按 `hdrLen + frameSize = 460812` 组帧。
URB_SLACK = 22
# 原厂 UVC 打开序列里的两条 vendor XU 写（entity 4，wIndex=0x0400）：
#   probe 之前 0x0d0d=01（相机侧开关），commit 之后 0x0606=00 ×2（送流开关）。
# Android 实测它们不是出流的唯一开关（设备在 probe 前就在发数据），照抄只为与
# 原厂逐字节一致、排除变量；失败只记 verbose，不影响取流。
VENDOR_XU_CAMERA = 0x0D0D
VENDOR_XU_STREAM = 0x0606
VENDOR_XU_INDEX = 0x0400


def _require_numpy() -> None:
    if np is None:
        raise GlxssError("YV12→RGBA 需要 numpy：python -m pip install numpy")


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
        self.session = None
        self.vc = None
        self.hid = None
        self.hid_owned = False
        # 逻辑帧缓冲（frameSize+22，只用于日志/统计）；单次 USB 读是 chunk_size。
        self.urb_size = DEFAULT_FRAME_SIZE + URB_SLACK
        self.chunk_size = BULK_CHUNK
        # 字节流组帧状态：_rx 里保存“已收到但还没凑成一帧”的字节。
        self._rx = bytearray()
        self._synced = False
        self.dropped_bytes = 0
        self.synced_frames = 0

    @classmethod
    def open(cls, dev, session, timeout: int = 5000, verbose: bool = False) -> "UvcStream":
        """claim 接口并切 alt，顺序逐行对齐 Android `UvcCamera.prepare()`：

        1. VC(if0) force claim；
        2. **HID(if2) force claim**（原厂/Android 都做；不 claim 时部分固件不出流）；
        3. VS(if1) force claim；
        4. `SET_INTERFACE(VS alt)` —— **即使 alt=0、即使返回失败也发**；
        5. 对 0x81 发 CLEAR_FEATURE(HALT)，清掉上一次残留的 halt。
        """
        vc, vs, ep = usb.pick_uvc(dev)
        hid = usb.find_interface(dev, usb.IFACE_HID)
        if vc is not None:
            session.claim_interface(vc, label="UVC VC")
        # HID 通常已经被 HUD 主循环 claim（同一条连接）；只记录“是不是我们 claim 的”，
        # close 时不要把上层还要用的 HID 一起 release（Android 共用连接时也不 release）。
        claimed_numbers = {int(i.bInterfaceNumber) for i in session.claimed}
        hid_owned = hid is not None and int(hid.bInterfaceNumber) not in claimed_numbers
        if hid is not None:
            session.claim_interface(hid, label="HID")
        session.claim_interface(vs, label="UVC VS")
        # pyusb 没有 Device.set_interface；走 set_interface_altsetting（见 usb.set_alt）。
        usb.set_alt(dev, vs, vs.bAlternateSetting, verbose=verbose)
        stream = cls(dev, vs, ep, timeout=timeout, verbose=verbose)
        stream.session = session
        stream.vc = vc
        stream.hid = hid
        stream.hid_owned = hid_owned
        usb.clear_halt(dev, ep.bEndpointAddress, verbose=verbose)
        if verbose:
            print("  VC: %s" % ("if=%d" % vc.bInterfaceNumber if vc is not None else "未发现"))
            print("  HID: %s" % ("if=%d" % hid.bInterfaceNumber if hid is not None else "未发现"))
            print("  VS: if=%d alt=%d ep=0x%02x" % (
                vs.bInterfaceNumber, vs.bAlternateSetting, ep.bEndpointAddress))
        return stream

    def close(self) -> None:
        """停流（alt=0）+ 清 halt，再释放 VS/VC/HID；可重复调用。

        顺序照 Android `UvcCamera.stop()`：停读 → setInterface(alt0) →
        CLEAR_FEATURE(ENDPOINT_HALT) → releaseInterface。
        """
        try:
            usb.set_alt(self.dev, self.vs, 0, verbose=self.verbose)
        except Exception:
            pass
        try:
            usb.clear_halt(self.dev, self.ep.bEndpointAddress, verbose=self.verbose)
        except Exception:
            pass
        session = self.session
        if session is not None:
            release = [self.vs, self.vc]
            if self.hid_owned:
                release.append(self.hid)
            for intf in release:
                if intf is not None:
                    session.release_interface(intf)
        self.session = None

    def _ctrl_in(self, request: int, value: int, length: int, timeout: int) -> bytes:
        return usb.ctrl_in(self.dev, request, value, self.vs.bInterfaceNumber,
                           length, timeout)

    def _ctrl_out(self, request: int, value: int, data: bytes, timeout: int) -> int:
        return usb.ctrl_out(self.dev, request, value, self.vs.bInterfaceNumber,
                            data, timeout)

    def get_probe(self, timeout: int = 5000) -> Probe:
        """GET_CUR(probe)；失败再试 GET_DEF，与 Android 的兜底顺序一致。"""
        last_error = None
        for request in (UVC_GET_CUR, UVC_GET_DEF):
            for length in (34, 26):
                try:
                    data = self._ctrl_in(request, UVC_VS_PROBE, length, timeout)
                except GlxssError as exc:
                    last_error = exc
                    continue
                if len(data) >= 26:
                    return Probe.parse(data)
                last_error = "只返回 %d 字节" % len(data)
        raise GlxssError(
            "GET_CUR/GET_DEF(probe) 失败：%s\n"
            "  * 确认没有其他 App 占用摄像头；\n"
            "  * Linux 上 uvcvideo 会占住 VS，需要 --detach；\n"
            "  * Windows 需要将 UVC 接口绑定到 WinUSB（Zadig）。" % (last_error,))

    def _xu_write(self, value: int, data: bytes, timeout: int) -> bool:
        """vendor XU 写：0x21/0x01 wValue=value wIndex=0x0400。失败不算致命。"""
        try:
            usb.ctrl_out(self.dev, 0x01, value, VENDOR_XU_INDEX, data, timeout)
            return True
        except GlxssError as exc:
            if self.verbose:
                print("  [UVC] vendor XU 0x%04x 失败: %s" % (
                    value, str(exc).splitlines()[0]))
            return False

    def vendor_pre_probe(self, timeout: int = 2000) -> bool:
        """probe 之前的 XU `0x0d0d=01`（原厂相机侧开关）。

        注意：Linux/Pi 上**不重放**原厂序列的 `0xA1/0x05 显示模式=1` 和
        `0xA1/0x04 状态读`——这两条属于 LCD 显示面，由 `lcd.initialize()`
        （开屏 + 模式 2）管理；在这里发模式 1 会把屏切回待机，监看模式就黑了。
        """
        ok = self._xu_write(VENDOR_XU_CAMERA, b"\x01", timeout)
        if self.verbose:
            print("  [UVC] vendor pre-probe xu(0x%04x)=01 -> %s" % (
                VENDOR_XU_CAMERA, ok))
        return ok

    def vendor_post_commit(self, timeout: int = 2000) -> bool:
        """commit 之后 XU `0x0606=00` ×2（原厂送流开关）。"""
        ok = True
        for index in range(2):
            ok = self._xu_write(VENDOR_XU_STREAM, b"\x00", timeout) and ok
            if self.verbose:
                print("  [UVC] vendor post-commit xu(0x%04x)=00 #%d -> %s" % (
                    VENDOR_XU_STREAM, index + 1, ok))
        return ok

    def negotiate(self, format_index: Optional[int] = None,
                  frame_index: Optional[int] = None, fps: Optional[float] = None,
                  max_frame: Optional[int] = None,
                  max_payload: Optional[int] = None,
                  timeout: Optional[int] = None) -> Probe:
        timeout_ms = timeout or self.timeout
        # Android `UvcCamera.prepare()`：claim + setInterface 之后、probe 之前
        # 先发 vendor XU 0x0d0d=01。
        self.vendor_pre_probe(timeout_ms)
        try:
            current = self.get_probe(timeout_ms)
        except GlxssError as exc:
            # Android 同样：GET_CUR/GET_DEF 都失败时用全 0 基底继续 probe。
            # 设备半死/被占用时，SET_CUR 有时仍然能成功并让流恢复。
            current = Probe.parse(bytes(34))
            if self.verbose:
                print("  GET_CUR/GET_DEF(probe) 失败，改用全 0 基底: %s" %
                      (str(exc).splitlines()[0],))
        if self.verbose:
            print("  GET_CUR(probe):")
            print("    " + current.format().replace("\n", "\n    "))
        interval = DEFAULT_INTERVAL
        if fps is not None:
            if fps <= 0:
                raise GlxssError("--fps 必须 > 0")
            interval = max(1, int(round(1e7 / fps)))
        # 默认档位写死 Android 实测值（1=YV12 / 4=640×480 / 16384 payload），
        # 不再继承 GET_CUR 里的 bFormatIndex/bFrameIndex/dwMaxPayloadTransferSize。
        # 设备会把上一次 commit 的档位留到下一次上电；半坏状态下 GET_CUR 会回
        # frame=1（1080p）+ maxPayload=0x012c7f8c（GET_MAX 全局上限 19693452），
        # 这时无论 URB 多大都收不到数据 —— 必须显式覆盖回去。
        target_format = DEFAULT_FORMAT_INDEX if format_index is None else int(format_index)
        frame_bytes = int(max_frame or DEFAULT_FRAME_SIZE)
        if frame_index is None:
            # 640×480 默认档位固定用 4；换成别的分辨率（--size）时仍尊重设备当前帧号，
            # 避免把 720p/1080p 强行写成 4。
            if frame_bytes == DEFAULT_FRAME_SIZE:
                target_frame = DEFAULT_FRAME_INDEX
            else:
                target_frame = int(current.frame_index or DEFAULT_FRAME_INDEX)
        else:
            target_frame = int(frame_index)
        payload = int(max_payload or DEFAULT_MAX_PAYLOAD)
        descriptor = current.descriptor(
            format_index=target_format,
            frame_index=target_frame,
            interval=interval,
            max_frame=frame_bytes,
            max_payload=payload,
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
        # Android commit 之后：XU 0x0606=00 ×2 → CLEAR_FEATURE(HALT, 0x81)。
        self.vendor_post_commit(timeout_ms)
        # 上一次被取消的 URB / 固件 STALL 会让端点停在 halt，表现为“commit 成功但
        # 一个字节都不来”；pyusb 的 clear_halt 失败会退回标准控制请求。
        usb.clear_halt(self.dev, self.ep.bEndpointAddress, verbose=self.verbose)
        probe = Probe.parse(committed)
        self.probe = probe
        if not self.width or not self.height:
            self.width, self.height = DEFAULT_WIDTH, DEFAULT_HEIGHT
        self.frame_size = frame_bytes
        # urb_size 只是逻辑帧缓冲（frameSize + 22 = 460822，原厂 frameBufferSize）；
        # 单次 USB 读固定 16384，不能用它当 read size：usbfs 单 URB 上限就是 16 KiB，
        # Android 上拿 460822 去 bulkTransfer 会立刻 -1（不是超时）。
        self.urb_size = int(self.frame_size) + URB_SLACK
        self.chunk_size = min(BULK_CHUNK, max(64, self.urb_size))
        # 新会话：清空上一次的字节流组帧状态，避免旧半帧污染新档位。
        self._rx = bytearray()
        self._synced = False
        self.dropped_bytes = 0
        self.synced_frames = 0
        if probe.frame_index != target_frame and self.verbose:
            print("  [UVC] 警告：设备回读 bFrameIndex=%d（期望 %d）" % (
                probe.frame_index, target_frame))
        return probe

    def set_dimensions(self, width: int, height: int) -> None:
        if width <= 0 or height <= 0 or width % 2 or height % 2:
            raise GlxssError("UVC 宽高必须是正偶数（当前 %dx%d）" % (width, height))
        self.width = width
        self.height = height
        self.frame_size = width * height * 3 // 2
        self.urb_size = int(self.frame_size) + URB_SLACK
        self.chunk_size = min(BULK_CHUNK, max(64, self.urb_size))
        self._rx = bytearray()
        self._synced = False

    def read_chunk(self, timeout: Optional[int] = None) -> bytes:
        """读一个 16 KiB 块（usbfs 单 URB 上限，绝不超过 BULK_CHUNK）。"""
        return usb.bulk_read(self.dev, self.ep.bEndpointAddress,
                             max(2, int(self.chunk_size)),
                             timeout or self.timeout)

    def read_urb(self, timeout: Optional[int] = None) -> bytes:
        """兼容旧名字：等价于 read_chunk（一次只读 ≤16 KiB，不是一整帧）。"""
        return self.read_chunk(timeout=timeout)

    # -- 字节流组帧 --------------------------------------------------------
    @staticmethod
    def _header_ok(buf: bytes, off: int, hdr_len: int) -> bool:
        """判定 `buf[off]` 开始的是否是合法 UVC payload header（本设备 12 B）。

        与 Android `UvcCamera.headerOk()` 逐行一致，用 header 自身的冗余位做校验
        （误判概率 ~1e-10，远低于“扫 0x0C”：460 KB 里平均会误判十几次）：
          * `bHeaderLength ∈ 2..12`；
          * `bmHeaderInfo` bit1(EOF)=1、bit6(ERR)=0；
          * 带 SCR（bit3）时 SCR 前 2 字节 = PTS 前 2 字节（实测
            `0c 0f d9 75 00 00 d9 75 …`）。
        """
        if not 2 <= hdr_len <= MAX_HEADER:
            return False
        if off + hdr_len > len(buf):
            return False
        info = buf[off + 1]
        if info & 0x40:          # ERR
            return False
        if not info & 0x02:      # EOF（本设备每帧都置位）
            return False
        if hdr_len >= MAX_HEADER and info & 0x08:
            # SCR[0..1] == PTS[0..1]（都是小端，取原始字节比较）
            if buf[off + 6] != buf[off + 2] or buf[off + 7] != buf[off + 3]:
                return False
        return True

    def _find_header(self, buf: bytes) -> Optional[int]:
        """在 buf 里找第一个合法 header 的偏移；没有返回 None。

        只扫描到 `len(buf) - MAX_HEADER`：header 可能跨块，末尾不足 12 B 的
        候选留给下一块再判断（调用方会保留末尾 MAX_HEADER-1 字节）。
        """
        last = len(buf) - MAX_HEADER
        if last < 0:
            return None
        for i in range(last + 1):
            hdr_len = buf[i]
            if 2 <= hdr_len <= MAX_HEADER and self._header_ok(buf, i, hdr_len):
                return i
        return None

    def _take_frame(self) -> Optional[UvcFrame]:
        """从 `_rx` 里切一帧出来；不够一帧返回 None（不阻塞、不读 USB）。

        规则（对应 Android readLoop 的组帧段）：
          1. 未同步时在字节流里找合法 header，丢弃之前的半帧（流从帧中间开始
             是常态，追加就会永久错位）；
          2. 同步后攒够 `hdrLen + frameSize` 就交一帧；
          3. 帧起点 header 不合法 → 丢掉 1 字节重新同步。
        """
        buf = self._rx
        while True:
            if not self._synced:
                found = self._find_header(buf)
                if found is None:
                    # 保留末尾 MAX_HEADER-1 字节（header 可能跨块）
                    keep = MAX_HEADER - 1
                    if len(buf) > keep:
                        dropped = len(buf) - keep
                        self.dropped_bytes += dropped
                        del buf[:dropped]
                    return None
                if found:
                    self.dropped_bytes += found
                    del buf[:found]
                self._synced = True
            if len(buf) < 2:
                return None
            hdr_len = buf[0]
            if len(buf) < hdr_len:
                return None          # header 跨块
            if not self._header_ok(buf, 0, hdr_len):
                self._synced = False
                self.dropped_bytes += 1
                del buf[:1]
                continue
            need = hdr_len + self.frame_size
            if len(buf) < need:
                return None          # 半帧，等下一块
            header = parse_header(bytes(buf[:MAX_HEADER]))
            data = bytes(buf[hdr_len:need])
            del buf[:need]
            self._frame_index += 1
            self.synced_frames += 1
            return UvcFrame(
                index=self._frame_index,
                data=data,
                width=self.width,
                height=self.height,
                header=header,
                timestamp=time.time(),
            )

    def _append_chunk(self, chunk: bytes) -> None:
        """把一块追加进组帧缓冲；异常积压时丢半帧重新同步。"""
        self._rx.extend(chunk)
        # 正常最多积压“一帧 + 一个 header + 一块”；超过说明上层读得比设备慢
        # 或者流里没有边界，丢掉旧数据重新找 header。
        limit = self.frame_size + MAX_HEADER + self.chunk_size + 64
        if len(self._rx) > limit:
            drop = len(self._rx) - (self.frame_size + MAX_HEADER)
            self.dropped_bytes += drop
            del self._rx[:drop]
            self._synced = False
            if self.verbose:
                print("  [UVC] 组帧缓冲积压 %d B，丢弃前 %d B 重新同步" % (
                    len(self._rx) + drop, drop))

    def read_frame(self, timeout: Optional[int] = None) -> UvcFrame:
        """读一帧：每次 bulk 读 ≤16 KiB，按字节流组帧。

        为什么不能一次 bulk 读 460822：usbfs 单 URB 上限 16384 B（与 libusb
        MAX_BULK_BUFFER_LENGTH 一致）。Android 上 `bulkTransfer(…, 460822)` 会
        立刻返回 -1（dt≈0，不是超时）；原厂 libusb 的 460822 是它**内部**拆成
        ~29 个 16 KiB URB 再重组，不是一次传完。这里显式分块，在主机侧重组成帧。
        """
        timeout_ms = timeout or self.timeout
        deadline = time.monotonic() + timeout_ms / 1000.0
        while True:
            frame = self._take_frame()
            if frame is not None:
                return frame
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise GlxssError(
                    "UVC 读帧超时（%.1f s 内没有完整帧；已同步 %d 帧，丢弃 %d B，"
                    "缓冲 %d B）" % (
                        timeout_ms / 1000.0, self.synced_frames,
                        self.dropped_bytes, len(self._rx)))
            # 本次最多等剩余时间，避免半帧跨过整体 deadline。
            wait_ms = max(1, min(timeout_ms, int(remaining * 1000)))
            chunk = self.read_chunk(wait_ms)
            if not chunk:
                continue
            self._append_chunk(chunk)

    def read_frames(self, count: int = 1, timeout: Optional[int] = None) -> Iterator[UvcFrame]:
        sent = 0
        while count <= 0 or sent < count:
            yield self.read_frame(timeout=timeout)
            sent += 1


# ---------------------------------------------------------------------------
# YV12 → RGBA 快路径（monitor 用；numpy）
# ---------------------------------------------------------------------------
def _upsample2_i16(plane):
    """半分辨率平面 → 2×（最近邻）；手写 4 象限赋值比 np.repeat 快一个数量级。"""
    height, width = plane.shape
    out = np.empty((height * 2, width * 2), dtype=np.int16)
    out[::2, ::2] = plane
    out[1::2, ::2] = plane
    out[::2, 1::2] = plane
    out[1::2, 1::2] = plane
    return out


def _upsample2_rgba(rgba):
    """RGBA 320×200 → 640×400 最近邻 2×（按 4 B 像素整体复制）。"""
    height, width = rgba.shape[:2]
    pixels = np.ascontiguousarray(rgba).view(np.uint32).reshape(height, width)
    out = np.empty((height * 2, width * 2), dtype=np.uint32)
    out[::2, ::2] = pixels
    out[1::2, ::2] = pixels
    out[::2, 1::2] = pixels
    out[1::2, 1::2] = pixels
    return out.view(np.uint8).reshape(height * 2, width * 2, 4)


def _resize_rgba_nearest(rgba, out_width: int, out_height: int):
    """任意尺寸最近邻缩放（只给 stretch/fit 等非默认模式兜底）。"""
    height, width = rgba.shape[:2]
    if (width, height) == (out_width, out_height):
        return rgba
    ys = ((np.arange(out_height) + 0.5) * height / out_height).astype(np.intp)
    xs = ((np.arange(out_width) + 0.5) * width / out_width).astype(np.intp)
    np.clip(ys, 0, height - 1, out=ys)
    np.clip(xs, 0, width - 1, out=xs)
    pixels = np.ascontiguousarray(rgba).view(np.uint32).reshape(height, width)
    return pixels[ys[:, None], xs[None, :]].view(np.uint8).reshape(
        out_height, out_width, 4)


def _fit_rgba(rgba, out_width: int, out_height: int):
    """等比缩放 + 居中黑边（fit）。"""
    height, width = rgba.shape[:2]
    scale = min(out_width / width, out_height / height)
    new_w = max(1, min(out_width, int(round(width * scale))))
    new_h = max(1, min(out_height, int(round(height * scale))))
    canvas = np.zeros((out_height, out_width, 4), dtype=np.uint8)
    canvas[..., 3] = 255
    left = (out_width - new_w) // 2
    top = (out_height - new_h) // 2
    canvas[top:top + new_h, left:left + new_w] = _resize_rgba_nearest(
        rgba, new_w, new_h)
    return canvas


def _pack_rgba_from_i16(r, g, b):
    """int16 R/G/B → 640×400 RGBA uint8（uint32 打包，避免 4 通道跨步赋值）。"""
    for plane in (r, g, b):
        np.maximum(plane, 0, out=plane)
        np.minimum(plane, 255, out=plane)
    # 小端内存布局：uint32 = 0xAABBGGRR → 字节 R,G,B,A。
    r32 = r.astype(np.uint32)
    g32 = g.astype(np.uint32)
    g32 <<= 8
    b32 = b.astype(np.uint32)
    b32 <<= 16
    r32 |= g32
    r32 |= b32
    r32 |= np.uint32(0xFF000000)
    height, width = r.shape
    return r32.view(np.uint8).reshape(height, width, 4)


def _convert_yuv(y, u, v, upsample: bool):
    """Y/U/V 平面 → RGBA；u/v 与 y 同尺寸或半尺寸（upsample=True）。"""
    y16 = np.maximum(y.astype(np.int16) - 16, 0).astype(np.uint16)
    # 298*y = 256*y + 42*y，先右移再相加，等价且不会让 298*255 溢出
    # uint16：42*255+128=10838，yterm 最大 278，再 clip 到 0..255。
    yterm = (y16 + ((y16 * 42 + 128) >> 8)).astype(np.int16)
    u32 = u.astype(np.int32) - 128
    v32 = v.astype(np.int32) - 128
    rv = ((409 * v32) >> 8).astype(np.int16)
    bu = ((516 * u32) >> 8).astype(np.int16)
    guv = ((-100 * u32 - 208 * v32) >> 8).astype(np.int16)
    if upsample:
        rv = _upsample2_i16(rv)
        bu = _upsample2_i16(bu)
        guv = _upsample2_i16(guv)
    return _pack_rgba_from_i16(yterm + rv, yterm + guv, yterm + bu)


def decode_yv12_rgba(data: bytes, width: int = DEFAULT_WIDTH,
                     height: int = DEFAULT_HEIGHT,
                     out_width: int = 640, out_height: int = 400,
                     fit: str = "cover", half: bool = False):
    """YV12 → out_width×out_height RGBA uint8，给 monitor 的 10 fps 快路径。

    * fit="cover"（默认）：等比填满、居中裁切；640×480 → 640×400 只裁上下，
      不缩放，是最快的路径；
    * fit="stretch"：直接缩放到目标尺寸；
    * fit="fit"：等比缩放 + 黑边；
    * half=True：Y 隔行隔列取样（320×240），色度用原半分辨率平面，再放大：
      解码量约 1/4，适合 Pi Zero 2 W 上保帧率/降延迟。
    """
    _require_numpy()
    if width <= 0 or height <= 0 or width % 2 or height % 2:
        raise GlxssError("YUV420 宽高必须是正偶数（当前 %dx%d）" % (width, height))
    if out_width <= 0 or out_height <= 0:
        raise GlxssError("输出宽高必须为正")
    fit = (fit or "cover").lower()
    if fit == "crop":
        fit = "cover"
    if fit not in ("cover", "stretch", "fit"):
        raise GlxssError("映射模式必须是 cover / stretch / fit，收到 %r" % fit)
    y_size = width * height
    c_size = y_size // 4
    expected = y_size + 2 * c_size
    if len(data) < expected:
        raise ProtocolError("YUV 数据不足：需要 %d B，实际 %d B" % (expected, len(data)))
    raw = np.frombuffer(data, dtype=np.uint8, count=expected)
    y_plane = raw[:y_size].reshape(height, width)
    v_plane = raw[y_size:y_size + c_size].reshape(height // 2, width // 2)
    u_plane = raw[y_size + c_size:y_size + 2 * c_size].reshape(
        height // 2, width // 2)

    if fit == "cover":
        scale = max(out_width / width, out_height / height)
        crop_w = min(width, max(2, int(round(out_width / scale))))
        crop_h = min(height, max(2, int(round(out_height / scale))))
        # 4:2:0 要求裁切宽高和起点都是偶数，否则色度会错位一行。
        crop_w = max(2, crop_w - crop_w % 2)
        crop_h = max(2, crop_h - crop_h % 2)
        left = max(0, ((width - crop_w) // 2) // 2 * 2)
        top = max(0, ((height - crop_h) // 2) // 2 * 2)
        crop_w = min(crop_w, (width - left) - (width - left) % 2)
        crop_h = min(crop_h, (height - top) - (height - top) % 2)
        if half:
            y_crop = y_plane[top:top + crop_h:2, left:left + crop_w:2]
            u_crop = u_plane[top // 2:(top + crop_h) // 2,
                             left // 2:(left + crop_w) // 2]
            v_crop = v_plane[top // 2:(top + crop_h) // 2,
                             left // 2:(left + crop_w) // 2]
            rgba = _convert_yuv(y_crop, u_crop, v_crop, upsample=False)
            if (rgba.shape[1] * 2, rgba.shape[0] * 2) == (out_width, out_height):
                return _upsample2_rgba(rgba)
            return _resize_rgba_nearest(rgba, out_width, out_height)
        y_crop = y_plane[top:top + crop_h, left:left + crop_w]
        u_crop = u_plane[top // 2:(top + crop_h) // 2,
                         left // 2:(left + crop_w) // 2]
        v_crop = v_plane[top // 2:(top + crop_h) // 2,
                         left // 2:(left + crop_w) // 2]
        rgba = _convert_yuv(y_crop, u_crop, v_crop, upsample=True)
        if (rgba.shape[1], rgba.shape[0]) == (out_width, out_height):
            return rgba
        return _resize_rgba_nearest(rgba, out_width, out_height)

    # stretch / fit：先按整幅解码（可选半分辨率），再缩放。
    if half:
        y_crop = y_plane[::2, ::2]
        u_crop = u_plane
        v_crop = v_plane
        rgba = _convert_yuv(y_crop, u_crop, v_crop, upsample=False)
    else:
        rgba = _convert_yuv(y_plane, u_plane, v_plane, upsample=True)
    if fit == "fit":
        return _fit_rgba(rgba, out_width, out_height)
    return _resize_rgba_nearest(rgba, out_width, out_height)


# ---------------------------------------------------------------------------
# YV12 → RGB（通用路径；uvc --save / 测试用）
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
