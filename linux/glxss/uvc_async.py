# -*- coding: utf-8 -*-
"""UVC bulk IN 异步多 URB 读（libusb1 / python3-usb1）。

**为什么需要它**：720p30 需要 41.5 MB/s。pyusb 的同步 `dev.read()` 一次只有
一个 16 KiB URB 在途，Pi Zero 2 W 实测上限约 26–29 MB/s（≈19–21 fps）；
720p 的 UVC interval 明明是 333333（30 fps），瓶颈在主机侧 URB 往返。

**实测**（2026-11-22，Pi Zero 2 W，本文件 `uvc_async_exp.py`）：

| 读法 | 吞吐 | 720p 帧率 |
|---|---|---|
| 同步单读 16 KiB | 28.9 MB/s | 20.9 fps |
| 2 线程同步读 | 40.2 MB/s | 29.1 fps |
| libusb 异步 2/4/8/16 URB | 40.25 MB/s | 29.1 fps |
| 异步 8 URB + 组帧，稳态 | 40.5 MB/s | **30.1 fps（中位 33.3 ms）** |

结论：Pi 的 dwc_otg 不是瓶颈，**只要 ≥2 个 URB 在途就能跑满设备**；8 个是
留余量。超过 2 个不再提升，说明设备侧输出约 30 fps（40.5 MB/s 含少量 header）。

**为什么另开一个 libusb handle**：主程序的控制面（HID 轮询 / LCD 1 MB bulk
OUT / probe+commit）都走 pyusb 的同一个 handle。libusb 允许同一设备开多个
handle，只要各 handle claim 的接口不重叠。这里只 claim VS(if1)：
  * pyusb handle 继续 claim VC(if0) / HID(if2) / LCD(if3)；
  * 固件要求“先 claim HID 再 claim VS”，所以调用方必须先让 pyusb claim HID，
    再 `AsyncBulkReader.open()`；
  * probe/commit 仍由 pyusb 发（control transfer 不要求 claim 该接口），
    因此 `uvc.UvcStream` 的整套协商/组帧逻辑原样复用。

**背压与暂停**：
  * 回调把 16 KiB 块按完成顺序放进队列；队列超过 high_water 就暂不重新提交，
    消费者取走后自动补交，内存有界；
  * `pause()` 让异步读暂时不提交新 URB，并等在途 URB 排空；`resume()` 恢复。
    `LcdSink` 在发 1 MB 帧前后调用它：LCD 整帧不再和 UVC bulk IN 抢总线，
    实测单帧发送 **45–150 ms → 24.6 ms**，撕裂时间窗明显缩短，而且 LCD 能真正
    跑到 10 fps（之前与 UVC 抢带宽只有 6 fps 左右）。
"""

from __future__ import annotations

import threading
import time
from collections import deque
from typing import Optional

from .errors import GlxssError

try:  # python3-usb1（Debian：apt install python3-usb1；pip：libusb1）
    import usb1  # type: ignore
except Exception as _exc:  # pragma: no cover - 取决于环境
    usb1 = None  # type: ignore
    USB1_IMPORT_ERROR: Optional[BaseException] = _exc
else:
    USB1_IMPORT_ERROR = None

VID_NORMAL, PID_NORMAL = 0x2E09, 0x0030
IFACE_VS = 1
EP_IN = 0x81
DEFAULT_CHUNK = 16384        # usbfs 单 URB 上限
DEFAULT_URBS = 16
HIGH_WATER = 256             # 4 MiB；消费端跟不上时暂停提交
LOW_WATER = 64


def available() -> bool:
    """当前环境是否可用（装了 libusb1 且能 import）。"""
    return usb1 is not None


def _status_name(status: int) -> str:
    if usb1 is None:
        return str(status)
    for name in ("TRANSFER_COMPLETED", "TRANSFER_ERROR", "TRANSFER_TIMED_OUT",
                 "TRANSFER_CANCELLED", "TRANSFER_STALL", "TRANSFER_NO_DEVICE",
                 "TRANSFER_OVERFLOW"):
        if status == getattr(usb1, name, -999):
            return name
    return "status=%d" % status


class AsyncBulkReader:
    """在独立 libusb handle 上对 VS 的 bulk IN 保持 N 个 16 KiB URB 在途。"""

    def __init__(self, chunk_size: int = DEFAULT_CHUNK, urbs: int = DEFAULT_URBS,
                 timeout: int = 1000, high_water: int = HIGH_WATER,
                 verbose: bool = False):
        if not 512 <= chunk_size <= DEFAULT_CHUNK:
            raise GlxssError("异步 URB 块大小必须在 512..%d（usbfs 单 URB 上限）"
                             % DEFAULT_CHUNK)
        if not 1 <= urbs <= 32:
            raise GlxssError("异步 URB 数必须在 1..32（建议 8）")
        self.chunk_size = int(chunk_size)
        self.urbs = int(urbs)
        self.timeout = max(1, int(timeout))
        self.high_water = max(2 * self.urbs, int(high_water))
        self.verbose = verbose
        self._context = None
        self._handle = None
        self._transfers = []
        self._lock = threading.Condition()
        self._queue: deque = deque()
        self._idle: list = []
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._opened = False
        self._paused = False
        self._fatal = ""
        self._inflight = 0
        # 统计
        self.bytes_read = 0
        self.chunks = 0
        self.errors = 0
        self.timeouts = 0
        self.pauses = 0
        self.pause_ms = 0.0

    # -- 打开/关闭 ---------------------------------------------------------
    def open(self) -> None:
        """打开设备并 claim VS；**调用方必须先用 pyusb claim HID**。"""
        if usb1 is None:
            raise GlxssError(
                "异步 URB 需要 python3-usb1（libusb1）：\n"
                "  Debian/Pi: sudo apt install -y python3-usb1\n"
                "  其它平台: python3 -m pip install libusb1\n"
                "  （原因：%s）" % (USB1_IMPORT_ERROR or "未安装"))
        context = usb1.USBContext()
        device = context.getByVendorIDAndProductID(VID_NORMAL, PID_NORMAL)
        if device is None:
            context.close()
            raise GlxssError("异步 URB：未找到 %04x:%04x" % (VID_NORMAL, PID_NORMAL))
        handle = device.open()
        try:
            try:
                handle.setAutoDetachKernelDriver(True)
            except Exception:
                pass
            handle.claimInterface(IFACE_VS)
            handle.setInterfaceAltSetting(IFACE_VS, 0)
            handle.clearHalt(EP_IN)
        except Exception as exc:
            try:
                handle.close()
            except Exception:
                pass
            context.close()
            raise GlxssError(
                "异步 URB：claim VS(if%d) 失败：%s\n"
                "  * 确认没有别的进程占用 UVC；\n"
                "  * 同一进程里 pyusb 不要再 claim VS；\n"
                "  * 可用 --async-urb 0 回退同步读。" % (IFACE_VS, exc)) from exc
        self._context = context
        self._handle = handle
        self._opened = True
        if self.verbose:
            print("[uvc-async] 已 claim VS(if%d)，%d URB × %d B"
                  % (IFACE_VS, self.urbs, self.chunk_size))

    # -- VS 控制面（probe/commit/CLEAR_HALT 必须走 claim 了 VS 的这个 handle）--
    def control_read(self, request_type: int, request: int, value: int,
                     index: int, length: int, timeout: int = 5000) -> bytes:
        return bytes(self._handle.controlRead(
            request_type, request, value, index, length, timeout=timeout))

    def control_write(self, request_type: int, request: int, value: int,
                      index: int, data: bytes, timeout: int = 5000) -> int:
        return int(self._handle.controlWrite(
            request_type, request, value, index, data, timeout=timeout))

    def clear_halt(self, endpoint: int = EP_IN) -> None:
        self._handle.clearHalt(endpoint)

    def start(self) -> None:
        if not self._opened:
            raise GlxssError("异步 URB 未打开")
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._event_loop,
                                        name="uvc-async-events", daemon=True)
        self._thread.start()
        for _ in range(self.urbs):
            self._new_transfer()

    def _new_transfer(self) -> None:
        transfer = self._handle.getTransfer()
        transfer.setBulk(EP_IN, self.chunk_size, callback=self._callback,
                         timeout=self.timeout)
        with self._lock:
            self._inflight += 1
            self._transfers.append(transfer)
        try:
            transfer.submit()
        except Exception as exc:
            with self._lock:
                self._inflight -= 1
            raise GlxssError("异步 URB 提交失败：%s" % exc) from exc

    # -- 事件线程 / 回调 ---------------------------------------------------
    def _event_loop(self) -> None:
        while not self._stop.is_set():
            try:
                self._context.handleEventsTimeout(0.05)
            except Exception as exc:  # USB 断开 / 上下文关闭
                with self._lock:
                    if not self._stop.is_set():
                        self._fatal = "异步 URB 事件循环失败：%s" % exc
                    self._lock.notify_all()
                return

    def _callback(self, transfer) -> None:
        resubmit = False
        status = transfer.getStatus()
        with self._lock:
            self._inflight -= 1
            if status == usb1.TRANSFER_COMPLETED:
                length = transfer.getActualLength()
                if length:
                    # 不在回调里复制 16 KiB（那是 Python/GIL 热点）：把
                    # (transfer, length) 直接交给消费者，复制和重新提交都在
                    # read_chunk() 里做。队列深度天然受 URB 数限制。
                    if not self._queue:
                        self._lock.notify_all()
                    self._queue.append((transfer, length))
                    return
                resubmit = True
            elif status == usb1.TRANSFER_TIMED_OUT:
                self.timeouts += 1
                resubmit = True
            elif status in (usb1.TRANSFER_CANCELLED, usb1.TRANSFER_NO_DEVICE):
                if not self._stop.is_set():
                    self._fatal = "UVC 异步读断开（%s）" % _status_name(status)
                    self._lock.notify_all()
            else:
                self.errors += 1
                resubmit = True
            if resubmit and not self._stop.is_set() and not self._fatal:
                if not self._paused and len(self._queue) < self.high_water:
                    self._inflight += 1
                else:
                    self._idle.append(transfer)
                    resubmit = False
        if resubmit:
            try:
                transfer.submit()
            except Exception as exc:
                with self._lock:
                    self._inflight -= 1
                    self._idle.append(transfer)
                    self._fatal = "异步 URB 重新提交失败：%s" % exc
                    self._lock.notify_all()

    # -- 消费者 ------------------------------------------------------------
    def read_chunk(self, timeout: Optional[int] = None) -> bytes:
        """取一块（按完成顺序）；超时返回 b''，断开抛 GlxssError。"""
        wait_s = max(1, int(timeout or self.timeout)) / 1000.0
        deadline = time.monotonic() + wait_s
        submit = []
        with self._lock:
            while (not self._queue and not self._fatal
                   and not self._stop.is_set()):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return b""
                self._lock.wait(min(remaining, 0.25))
            entry = self._queue.popleft() if self._queue else None
            fatal = self._fatal
        if entry is None:
            if fatal:
                raise GlxssError(fatal)
            return b""
        transfer, length = entry
        # 复制必须在下一次 submit 之前完成；复制放在锁外，减少回调等待。
        chunk = bytes(transfer.getBuffer()[:length])
        with self._lock:
            self.bytes_read += length
            self.chunks += 1
            if (self._stop.is_set() or self._fatal or self._paused
                    or len(self._queue) >= self.high_water):
                self._idle.append(transfer)
            else:
                self._inflight += 1
                submit.append(transfer)
        for item in submit:
            try:
                item.submit()
            except Exception as exc:
                with self._lock:
                    self._inflight -= 1
                    self._idle.append(item)
                    self._fatal = "异步 URB 重新提交失败：%s" % exc
                    self._lock.notify_all()
        return chunk

    # -- 暂停（给 LCD 整帧发送让出总线） ----------------------------------
    def pause(self, drain_timeout: float = 0.08) -> None:
        """停止提交新 URB，并尽量等在途 URB 完成（最多 drain_timeout 秒）。"""
        started = time.monotonic()
        with self._lock:
            if self._paused or self._stop.is_set():
                return
            self._paused = True
            deadline = started + max(0.0, drain_timeout)
            while self._inflight > 0 and time.monotonic() < deadline:
                self._lock.wait(0.005)
            self.pauses += 1
            self.pause_ms += (time.monotonic() - started) * 1000.0
            self._lock.notify_all()

    def resume(self) -> None:
        submit = []
        with self._lock:
            if not self._paused:
                return
            self._paused = False
            while self._idle and len(self._queue) < self.high_water:
                transfer = self._idle.pop()
                self._inflight += 1
                submit.append(transfer)
            self._lock.notify_all()
        for transfer in submit:
            try:
                transfer.submit()
            except Exception as exc:
                with self._lock:
                    self._inflight -= 1
                    self._idle.append(transfer)
                    self._fatal = "异步 URB 恢复失败：%s" % exc
                    self._lock.notify_all()
                break

    # -- 状态/关闭 ---------------------------------------------------------
    def stats_dict(self) -> dict:
        with self._lock:
            return {
                "urbs": self.urbs,
                "chunk": self.chunk_size,
                "inflight": self._inflight,
                "queued": len(self._queue),
                "bytes": self.bytes_read,
                "chunks": self.chunks,
                "errors": self.errors,
                "timeouts": self.timeouts,
                "pauses": self.pauses,
                "pause_ms": round(self.pause_ms, 1),
                "paused": self._paused,
                "fatal": self._fatal,
            }

    def close(self) -> None:
        if not self._opened:
            return
        with self._lock:
            self._stop.set()
            self._paused = False
            self._lock.notify_all()
        thread = self._thread
        if thread is not None and thread.is_alive():
            # 事件线程只在 handleEventsTimeout(0.05) 里；最多等 ~2 s。
            # 绝不能在线程还活着时 close context：libusb 的 mutex 会断言崩溃
            # （pthread_mutex_destroy / usbi_mutex_lock）。
            thread.join(timeout=2.0)
        alive = thread is not None and thread.is_alive()
        self._thread = None
        if alive:
            # 宁可泄漏一个 handle 让进程退出，也不在事件线程运行时销毁 context。
            if self.verbose:
                print("[uvc-async] 事件线程未退出，跳过 context.close()（避免 libusb 崩溃）")
            self._handle = None
            self._context = None
            self._opened = False
            return
        for transfer in self._transfers:
            try:
                if transfer.isSubmitted():
                    transfer.cancel()
            except Exception:
                pass
        # 给取消的 URB 一个完成的机会，否则 close 时 libusb 会报资源未释放。
        deadline = time.monotonic() + 1.0
        while time.monotonic() < deadline:
            try:
                self._context.handleEventsTimeout(0.02)
            except Exception:
                break
            if not any(t.isSubmitted() for t in self._transfers):
                break
        for transfer in self._transfers:
            try:
                transfer.close()
            except Exception:
                pass
        self._transfers = []
        try:
            self._handle.setInterfaceAltSetting(IFACE_VS, 0)
        except Exception:
            pass
        try:
            self._handle.releaseInterface(IFACE_VS)
        except Exception:
            pass
        try:
            self._handle.close()
        except Exception:
            pass
        try:
            self._context.close()
        except Exception:
            pass
        self._handle = None
        self._context = None
        self._opened = False
        if self.verbose:
            print("[uvc-async] 已关闭：%d 块 %.1f MB，错误 %d，超时 %d，暂停 %d 次/%.0f ms"
                  % (self.chunks, self.bytes_read / 1e6, self.errors,
                     self.timeouts, self.pauses, self.pause_ms))
