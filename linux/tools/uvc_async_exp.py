#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""UVC 720p 吞吐实验台：同步单读 vs 多线程同步读 vs libusb 异步多 URB。

用途：验证 Pi Zero 2 W 上 720p30（41.5 MB/s）是否受“单 URB 在途”限制。
不改主代码、不依赖 glxss 包；只在真机上跑，结束后停流/释放接口。

    python3 tools/uvc_async_exp.py --mode sync   --seconds 10
    python3 tools/uvc_async_exp.py --mode thread --threads 4 --seconds 10
    python3 tools/uvc_async_exp.py --mode async  --urb 8 --seconds 10
    python3 tools/uvc_async_exp.py --mode async  --urb 8 --framing --seconds 10

需要 `python3-usb1`（Debian：sudo apt install -y python3-usb1）。

注意：同一时间只能有一个进程 claim UVC；先停掉 glxss-stream / glxss uvc。
"""

from __future__ import annotations

import argparse
import struct
import sys
import threading
import time

import usb1

VID, PID = 0x2E09, 0x0030
EP_IN = 0x81
IF_VC, IF_VS, IF_HID, IF_LCD = 0, 1, 2, 3
UVC_SET_CUR = 0x01
UVC_GET_CUR = 0x81
VS_PROBE = 0x0100
VS_COMMIT = 0x0200

MAX_HEADER = 12


def log(msg: str) -> None:
    print(msg, flush=True)


def build_probe(cur: bytes, fmt: int, frame: int, interval: int,
                max_frame: int, max_payload: int) -> bytes:
    src = bytearray((cur or b"")[:34].ljust(34, b"\x00"))
    struct.pack_into("<H", src, 0, 0x0001)
    src[2] = fmt & 0xFF
    src[3] = frame & 0xFF
    struct.pack_into("<I", src, 4, interval & 0xFFFFFFFF)
    struct.pack_into("<I", src, 18, max_frame & 0xFFFFFFFF)
    struct.pack_into("<I", src, 22, max_payload & 0xFFFFFFFF)
    return bytes(src)


def probe_commit(handle, fmt: int, frame: int, interval: int,
                 max_frame: int, max_payload: int) -> bytes:
    try:
        cur = handle.controlRead(0xA1, UVC_GET_CUR, VS_PROBE, IF_VS, 34,
                                 timeout=5000)
    except usb1.USBError as exc:
        log("  GET_CUR(probe) 失败: %s" % exc)
        cur = b""
    log("  GET_CUR(probe) %d B: %s" % (len(cur), bytes(cur).hex()))
    target = build_probe(bytes(cur), fmt, frame, interval, max_frame, max_payload)
    log("  SET_CUR(probe) 34 B: %s" % target.hex())
    handle.controlWrite(0x21, UVC_SET_CUR, VS_PROBE, IF_VS, target,
                        timeout=5000)
    try:
        back = bytes(handle.controlRead(0xA1, UVC_GET_CUR, VS_PROBE, IF_VS, 34,
                                        timeout=5000))
        log("  GET_CUR(back) %d B: %s" % (len(back), back.hex()))
    except usb1.USBError as exc:
        log("  GET_CUR(back) 失败: %s" % exc)
        back = b""
    source = back if len(back) >= 26 else target
    source = source[:34].ljust(34, b"\x00")
    handle.controlWrite(0x21, UVC_SET_CUR, VS_COMMIT, IF_VS, source,
                        timeout=5000)
    log("  SET_CUR(commit) ok")
    return source


# ---------------------------------------------------------------------------
# 组帧（与 glxss.uvc.UvcStream._header_ok / _take_frame 一致）
# ---------------------------------------------------------------------------
def header_ok(buf: bytes, off: int, hdr_len: int) -> bool:
    if not 2 <= hdr_len <= MAX_HEADER:
        return False
    if off + hdr_len > len(buf):
        return False
    info = buf[off + 1]
    if info & 0x40 or not info & 0x02:
        return False
    if hdr_len >= MAX_HEADER and info & 0x08:
        if buf[off + 6] != buf[off + 2] or buf[off + 7] != buf[off + 3]:
            return False
    return True


def find_header(buf: bytes) -> int | None:
    last = len(buf) - MAX_HEADER
    for i in range(max(0, last + 1)):
        if header_ok(buf, i, buf[i]):
            return i
    return None


class Framer:
    """累加字节流并切片；多线程模式下 chunk 顺序不可靠，只用于异步模式。"""

    def __init__(self, frame_size: int):
        self.frame_size = frame_size
        self.buf = bytearray()
        self.synced = False
        self.frames = 0
        self.dropped = 0
        self.times: list[float] = []

    def feed(self, data: bytes) -> int:
        self.buf.extend(data)
        made = 0
        while True:
            if not self.synced:
                found = find_header(self.buf)
                if found is None:
                    keep = MAX_HEADER - 1
                    if len(self.buf) > keep:
                        self.dropped += len(self.buf) - keep
                        del self.buf[:len(self.buf) - keep]
                    return made
                if found:
                    self.dropped += found
                    del self.buf[:found]
                self.synced = True
            if len(self.buf) < 2:
                return made
            hdr_len = self.buf[0]
            if len(self.buf) < hdr_len:
                return made
            if not header_ok(bytes(self.buf[:MAX_HEADER]), 0, hdr_len):
                self.synced = False
                self.dropped += 1
                del self.buf[:1]
                continue
            need = hdr_len + self.frame_size
            if len(self.buf) < need:
                return made
            del self.buf[:need]
            self.frames += 1
            self.times.append(time.monotonic())
            made += 1


# ---------------------------------------------------------------------------
# 三种读法
# ---------------------------------------------------------------------------
def run_sync(handle, chunk: int, seconds: float, framer: Framer | None):
    total = 0
    reads = 0
    errors = 0
    t0 = time.monotonic()
    while time.monotonic() - t0 < seconds:
        try:
            data = bytes(handle.bulkRead(EP_IN, chunk, timeout=5000))
        except usb1.USBError as exc:
            errors += 1
            if errors <= 3:
                log("  读错误: %s" % exc)
            continue
        reads += 1
        total += len(data)
        if framer is not None:
            framer.feed(data)
    return total, reads, errors, time.monotonic() - t0


def run_thread(handle, chunk: int, seconds: float, threads: int):
    stop = threading.Event()
    lock = threading.Lock()
    counters = {"bytes": 0, "reads": 0, "errors": 0}

    def worker():
        local = {"bytes": 0, "reads": 0, "errors": 0}
        while not stop.is_set():
            try:
                data = handle.bulkRead(EP_IN, chunk, timeout=1000)
            except usb1.USBError:
                with lock:
                    counters["errors"] += 1
                continue
            local["reads"] += 1
            local["bytes"] += len(data)
            if local["reads"] % 64 == 0:
                with lock:
                    counters["bytes"] += local["bytes"]
                    counters["reads"] += local["reads"]
                    counters["errors"] += local["errors"]
                    local = {"bytes": 0, "reads": 0, "errors": 0}
        with lock:
            counters["bytes"] += local["bytes"]
            counters["reads"] += local["reads"]
            counters["errors"] += local["errors"]

    t0 = time.monotonic()
    pool = [threading.Thread(target=worker, daemon=True) for _ in range(threads)]
    for t in pool:
        t.start()
    time.sleep(seconds)
    stop.set()
    for t in pool:
        t.join(timeout=2.0)
    return counters["bytes"], counters["reads"], counters["errors"], time.monotonic() - t0


def run_async(handle, context, chunk: int, seconds: float, urb_count: int,
              framer: Framer | None, timeout_ms: int = 1000):
    lock = threading.Lock()
    counters = {"bytes": 0, "reads": 0, "errors": 0, "timeouts": 0}
    stop = threading.Event()
    transfers = []

    def callback(transfer):
        status = transfer.getStatus()
        if status == usb1.TRANSFER_COMPLETED:
            n = transfer.getActualLength()
            with lock:
                counters["bytes"] += n
                counters["reads"] += 1
                if framer is not None:
                    framer.feed(bytes(transfer.getBuffer()[:n]))
        elif status == usb1.TRANSFER_TIMED_OUT:
            with lock:
                counters["timeouts"] += 1
        elif status in (usb1.TRANSFER_CANCELLED, usb1.TRANSFER_NO_DEVICE):
            return False
        else:
            with lock:
                counters["errors"] += 1
        if stop.is_set():
            return False
        try:
            transfer.submit()
        except usb1.DoomedTransferError:
            return False
        return True

    for _ in range(urb_count):
        transfer = handle.getTransfer()
        transfer.setBulk(EP_IN, chunk, callback=callback, timeout=timeout_ms)
        transfer.submit()
        transfers.append(transfer)

    t0 = time.monotonic()
    while time.monotonic() - t0 < seconds:
        try:
            context.handleEventsTimeout(0.05)
        except usb1.USBError as exc:
            log("  handleEvents 错误: %s" % exc)
            break
    stop.set()
    for transfer in transfers:
        try:
            if transfer.isSubmitted():
                transfer.cancel()
        except Exception:
            pass
    deadline = time.monotonic() + 2.0
    while time.monotonic() < deadline:
        try:
            context.handleEventsTimeout(0.05)
        except usb1.USBError:
            break
        if not any(t.isSubmitted() for t in transfers):
            break
    for transfer in transfers:
        try:
            transfer.close()
        except Exception:
            pass
    return counters["bytes"], counters["reads"], counters["errors"] + counters["timeouts"], \
        time.monotonic() - t0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="UVC 720p 吞吐实验：sync / thread / async 多 URB")
    parser.add_argument("--mode", choices=("sync", "thread", "async"),
                        default="async")
    parser.add_argument("--chunk", type=int, default=16384,
                        help="单 URB 字节数（usbfs 上限 16384）")
    parser.add_argument("--urb", type=int, default=8, help="异步模式在途 URB 数")
    parser.add_argument("--threads", type=int, default=4, help="thread 模式线程数")
    parser.add_argument("--seconds", type=float, default=10.0)
    parser.add_argument("--warmup", type=float, default=2.0,
                        help="组帧模式下统计稳态 fps 时跳过的启动秒数")
    parser.add_argument("--frame", type=int, default=3)
    parser.add_argument("--format", type=int, default=1)
    parser.add_argument("--interval", type=int, default=333333)
    parser.add_argument("--max-frame", type=int, default=1382400)
    parser.add_argument("--max-payload", type=int, default=16384)
    parser.add_argument("--framing", action="store_true",
                        help="按 12B header + max-frame 组帧（thread 模式禁止）")
    parser.add_argument("--no-detach", action="store_true")
    parser.add_argument("--no-hid", action="store_true")
    parser.add_argument("--lcd", action="store_true", help="并发发 1MB LCD 帧（测双路）")
    parser.add_argument("--lcd-fps", type=float, default=10.0)
    parser.add_argument("--hid", action="store_true", help="并发 HID 轮询")
    parser.add_argument("--timeout", type=int, default=1000, help="异步 URB 超时 ms")
    args = parser.parse_args(argv)

    if args.mode == "thread" and args.framing:
        log("thread 模式的 chunk 完成顺序不保证，不能组帧；去掉 --framing")
        return 2

    context = usb1.USBContext()
    device = context.getByVendorIDAndProductID(VID, PID)
    if device is None:
        log("找不到 %04x:%04x" % (VID, PID))
        return 2
    log("设备 %04x:%04x bus=%d addr=%d" % (
        VID, PID, device.getBusNumber(), device.getDeviceAddress()))
    handle = device.open()
    if not args.no_detach:
        try:
            handle.setAutoDetachKernelDriver(True)
            log("  setAutoDetachKernelDriver(True)")
        except usb1.USBError as exc:
            log("  auto-detach 失败: %s（继续）" % exc)
    try:
        handle.setConfiguration(1)
    except usb1.USBError as exc:
        log("  setConfiguration: %s（继续）" % exc)

    # 与 Android 一致：VC → HID → VS
    for number, label in ((IF_VC, "VC"), (IF_HID, "HID"), (IF_VS, "VS")):
        if number == IF_HID and args.no_hid:
            continue
        try:
            handle.claimInterface(number)
            log("  claim %s if%d ok" % (label, number))
        except usb1.USBError as exc:
            log("  claim %s if%d 失败: %s" % (label, number, exc))
    if args.lcd:
        try:
            handle.claimInterface(IF_LCD)
            log("  claim LCD if%d ok" % IF_LCD)
        except usb1.USBError as exc:
            log("  claim LCD if%d 失败: %s" % (IF_LCD, exc))
    try:
        handle.setInterfaceAltSetting(IF_VS, 0)
        log("  SET_INTERFACE if1 alt=0 ok")
    except usb1.USBError as exc:
        log("  SET_INTERFACE if1 alt=0: %s（继续）" % exc)
    try:
        handle.clearHalt(EP_IN)
        log("  CLEAR_HALT 0x81 ok")
    except usb1.USBError as exc:
        log("  CLEAR_HALT 0x81: %s（继续）" % exc)

    probe_commit(handle, args.format, args.frame, args.interval,
                 args.max_frame, args.max_payload)

    stop = threading.Event()
    extras = []

    def hid_loop():
        n = 0
        while not stop.is_set():
            try:
                data = handle.controlRead(0xA1, 0x16, 0, IF_HID, 28,
                                          timeout=1000)
                n += 1
                if n <= 2:
                    log("  [HID] %d B: %s" % (len(data), bytes(data[:12]).hex()))
            except usb1.USBError:
                pass
            stop.wait(0.3)
        log("  [HID] 轮询 %d 次" % n)

    def lcd_loop():
        import numpy as np
        w, h = 640, 400
        row = (np.arange(w, dtype=np.uint32) % 256).astype(np.uint8)
        base = np.tile(row, (h, 1))
        frame = np.zeros((h, w, 4), dtype=np.uint8)
        frame[..., 3] = 255
        header = bytes((1, 0, 0, 0)) + struct.pack("<I", 16 + w * h * 4) + bytes(8)
        sent = 0
        t0 = time.monotonic()
        period = 1.0 / args.lcd_fps if args.lcd_fps > 0 else 0
        while not stop.is_set():
            frame[..., 0] = base
            frame[..., 1] = base
            frame[..., 2] = base
            packet = header + frame.tobytes()
            try:
                handle.bulkWrite(0x01, packet, timeout=5000)
                sent += 1
            except usb1.USBError as exc:
                log("  [LCD] 发送失败: %s" % exc)
                break
            stop.wait(period)
        log("  [LCD] 共发 %d 帧" % sent)

    if args.hid:
        t = threading.Thread(target=hid_loop, daemon=True)
        t.start()
        extras.append(t)
    if args.lcd:
        t = threading.Thread(target=lcd_loop, daemon=True)
        t.start()
        extras.append(t)

    framer = Framer(args.max_frame) if args.framing else None
    log("开始：mode=%s chunk=%d urb=%d threads=%d framing=%s" % (
        args.mode, args.chunk, args.urb, args.threads, bool(framer)))
    if args.mode == "sync":
        total, reads, errors, elapsed = run_sync(handle, args.chunk,
                                                 args.seconds, framer)
    elif args.mode == "thread":
        total, reads, errors, elapsed = run_thread(handle, args.chunk,
                                                   args.seconds, args.threads)
    else:
        total, reads, errors, elapsed = run_async(
            handle, context, args.chunk, args.seconds, args.urb, framer,
            timeout_ms=args.timeout)
    stop.set()
    for t in extras:
        t.join(timeout=2.0)

    mbps = total / max(1e-6, elapsed) / 1e6
    fps = total / max(1, args.max_frame) / max(1e-6, elapsed)
    log("结果：%.2f s  reads=%d  errors=%d  总字节=%.2f MB  %.2f MB/s  "
        "理论上限=%.1f fps" % (
            elapsed, reads, errors, total / 1e6, mbps, fps))
    if framer is not None:
        log("      组帧=%d 帧  丢弃=%d B  实测=%.1f fps" % (
            framer.frames, framer.dropped,
            framer.frames / max(1e-6, elapsed)))
        times = framer.times
        if len(times) > 3:
            origin = times[0]
            steady = [t for t in times if t - origin >= args.warmup]
            deltas = sorted(b - a for a, b in zip(times, times[1:]))
            if len(steady) > 1:
                log("      稳态（跳过 %.1fs）=%.2f fps  间隔中位=%.1f ms p95=%.1f ms" % (
                    args.warmup, (len(steady) - 1) / (steady[-1] - steady[0]),
                    deltas[len(deltas) // 2] * 1000.0,
                    deltas[min(len(deltas) - 1, int(len(deltas) * 0.95))] * 1000.0))

    try:
        handle.setInterfaceAltSetting(IF_VS, 0)
    except Exception:
        pass
    try:
        handle.close()
    except Exception:
        pass
    context.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
