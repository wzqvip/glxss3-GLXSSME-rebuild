#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""UVC 出流实验台（Pi 侧，对照 Android UvcCamera.kt 的打开顺序）。

用途：在不改主代码的前提下逐项对照 Android 的成功参数，找出 Pi 上
“probe/commit 成功但 bulk IN 超时”的差异。

    python3 tools/uvc_pi_exp.py --chunk 16384 --frame 3 --max-frame 1382400 --interval 333333
    python3 tools/uvc_pi_exp.py --chunk 460822        # 复现“一次读整帧”（usbfs 拒绝）
    python3 tools/uvc_pi_exp.py --chunk 65536         # 复现旧实现的 64 KiB 读缓冲
    python3 tools/uvc_pi_exp.py --hid --lcd --seconds 10

关键点（与 Android 一致）：
  * claim 顺序 VC(if0) → HID(if2) → VS(if1 alt) 全部 force；
  * setInterface(VS alt) 即使返回 false 也继续；
  * probe 默认 bmHint=1, format=1, frame=3（1280×720）, interval=333333,
    maxFrame=1382400, maxPayload=16384；commit 原样提交 GET_CUR 回读。
    测 640×480 回退档用 `--frame 4 --max-frame 460800`；
  * 单次 bulk 读默认 16384 B（usbfs 单 URB 上限）；读到的字节流按
    `hdrLen + maxFrame` 组帧（与 glxss.uvc 相同的 header 校验/重同步）；
  * header 无效只计一次、继续读（不中止）。
"""

from __future__ import annotations

import argparse
import struct
import sys
import threading
import time

import usb.core
import usb.util

VID, PID = 0x2E09, 0x0030
EP_IN = 0x81
IF_VC, IF_VS, IF_HID, IF_LCD = 0, 1, 2, 3

UVC_SET_CUR = 0x01
UVC_GET_CUR = 0x81
VS_PROBE = 0x0100
VS_COMMIT = 0x0200
REQ_IN = 0xA1
REQ_OUT = 0x21


class Gate:
    """控制面串行闸门（同一 handle 上并发 bulk IN 不受影响）。"""

    def __init__(self):
        self.lock = threading.RLock()

    def __enter__(self):
        self.lock.acquire()
        return self

    def __exit__(self, *exc):
        self.lock.release()
        return False


def find_interfaces(dev):
    found = {}
    for cfg in dev:
        for itf in cfg:
            found.setdefault((itf.bInterfaceNumber, itf.bAlternateSetting), itf)
    return found


def describe(dev):
    print("设备 %04x:%04x bus=%s addr=%s bcdUSB=0x%04x" % (
        dev.idVendor, dev.idProduct, dev.bus, dev.address, dev.bcdUSB))
    for cfg in dev:
        for itf in cfg:
            print("  if=%d alt=%d class=0x%02x/0x%02x/0x%02x eps=%d" % (
                itf.bInterfaceNumber, itf.bAlternateSetting,
                itf.bInterfaceClass, itf.bInterfaceSubClass,
                itf.bInterfaceProtocol, itf.bNumEndpoints))
            for ep in itf:
                print("      ep=0x%02x attr=0x%02x mps=%d" % (
                    ep.bEndpointAddress, ep.bmAttributes, ep.wMaxPacketSize))


def claim(dev, itf, detach, label):
    num = itf.bInterfaceNumber
    if detach:
        try:
            dev.detach_kernel_driver(num)
            print("  detach 内核驱动 if%d (%s) ok" % (num, label))
        except Exception as exc:
            print("  detach if%d (%s): %s" % (num, label, exc))
    try:
        usb.util.claim_interface(dev, itf)
        print("  claim %s if%d alt%d ok" % (label, num, itf.bAlternateSetting))
        return True
    except Exception as exc:
        print("  claim %s if%d alt%d 失败: %s" % (
            label, num, itf.bAlternateSetting, exc))
        return False


def ctrl_in(dev, req, value, index, length, timeout=2000):
    return bytes(dev.ctrl_transfer(REQ_IN, req, value, index, length, timeout=timeout))


def ctrl_out(dev, req, value, index, data, timeout=2000):
    return dev.ctrl_transfer(REQ_OUT, req, value, index, data, timeout=timeout)


def probe_commit(dev, gate, iface, fmt, frame, interval, max_frame, max_payload):
    with gate:
        try:
            cur = ctrl_in(dev, UVC_GET_CUR, VS_PROBE, iface, 34)
        except Exception as exc:
            print("  GET_CUR(probe) 失败: %s" % exc)
            cur = bytes(34)
        print("  GET_CUR(probe) %d B: %s" % (len(cur), cur.hex()))
        if len(cur) >= 26:
            hint, f0, fr0 = struct.unpack_from("<HBB", cur, 0)
            print("    原值 bmHint=0x%04x fmt=%d frame=%d interval=%d maxFrame=%d maxPayload=%d" % (
                hint, f0, fr0, struct.unpack_from("<I", cur, 4)[0],
                struct.unpack_from("<I", cur, 18)[0],
                struct.unpack_from("<I", cur, 22)[0]))
        buf = bytearray(cur[:34].ljust(34, b"\x00"))
        struct.pack_into("<H", buf, 0, 0x0001)
        buf[2] = fmt & 0xFF
        buf[3] = frame & 0xFF
        struct.pack_into("<I", buf, 4, interval & 0xFFFFFFFF)
        struct.pack_into("<I", buf, 18, max_frame & 0xFFFFFFFF)
        struct.pack_into("<I", buf, 22, max_payload & 0xFFFFFFFF)
        print("  SET_CUR(probe) 34 B: %s" % bytes(buf).hex())
        rc = ctrl_out(dev, UVC_SET_CUR, VS_PROBE, iface, bytes(buf))
        print("  SET_CUR(probe) -> %d" % rc)
        back = b""
        try:
            back = ctrl_in(dev, UVC_GET_CUR, VS_PROBE, iface, 34)
            print("  GET_CUR(probe,back) %d B: %s" % (len(back), back.hex()))
        except Exception as exc:
            print("  GET_CUR(probe,back) 失败: %s" % exc)
        src = back if len(back) >= 26 else bytes(buf)
        if len(src) < 34:
            src = bytes(src).ljust(34, b"\x00")
        rc = ctrl_out(dev, UVC_SET_CUR, VS_COMMIT, iface, src[:34])
        print("  SET_CUR(commit) -> %d" % rc)
        return src


def clear_halt(dev, ep=EP_IN):
    """CLEAR_FEATURE(ENDPOINT_HALT) wValue=0 wIndex=ep。"""
    try:
        dev.ctrl_transfer(0x02, 0x01, 0, ep, 0, timeout=1000)
        print("  CLEAR_FEATURE(HALT) ep=0x%02x ok" % ep)
    except Exception as exc:
        print("  CLEAR_FEATURE(HALT) ep=0x%02x: %s" % (ep, exc))


def hid_thread(dev, gate, stop, verbose=True):
    n = 0
    fails = 0
    while not stop.is_set():
        try:
            with gate:
                data = ctrl_in(dev, 0x16, 0, IF_HID, 28, timeout=1000)
            n += 1
            if verbose and n <= 3:
                print("  [HID] %d B: %s" % (len(data), data[:12].hex()))
        except Exception as exc:
            fails += 1
            if verbose and fails <= 3:
                print("  [HID] 失败: %s" % exc)
        stop.wait(0.3)
    print("  [HID] 轮询 %d 次，失败 %d" % (n, fails))


def lcd_thread(dev, gate, stop, fps=10.0, verbose=True):
    """初始化 LCD + 持续发 1MB 帧（对应 Android 的投屏并发）。"""
    import numpy as np

    with gate:
        try:
            st = ctrl_in(dev, 0x04, 0, IF_LCD, 8, timeout=2000)
            print("  [LCD] 状态 %s" % st.hex())
            for req, val, name in ((0x03, 1, "屏开关"), (0x05, 2, "显示模式")):
                ack = ctrl_in(dev, req, val, IF_LCD, 1, timeout=2000)
                print("  [LCD] %s -> %s" % (name, ack.hex()))
        except Exception as exc:
            print("  [LCD] 初始化失败: %s" % exc)
    w, h = 640, 400
    x = (np.arange(w, dtype=np.uint32) % 256).astype(np.uint8)
    base = np.tile(x, (h, 1))
    frame = np.zeros((h, w, 4), dtype=np.uint8)
    frame[..., 3] = 255
    header = bytes((1, 0, 0, 0)) + struct.pack("<I", 16 + w * h * 4) + bytes(8)
    sent = 0
    t0 = time.monotonic()
    period = 1.0 / fps if fps > 0 else 0
    while not stop.is_set():
        frame[..., 0] = base
        frame[..., 1] = base
        frame[..., 2] = base
        packet = header + frame.tobytes()
        try:
            with gate:
                dev.write(0x01, packet, timeout=5000)
            sent += 1
            if verbose and sent <= 2:
                print("  [LCD] 已发 %d 帧（%.2f MB/s）" % (
                    sent, sent * len(packet) / max(1e-6, time.monotonic() - t0) / 1e6))
        except Exception as exc:
            print("  [LCD] 发送失败: %s" % exc)
            break
        stop.wait(period)
    print("  [LCD] 共发 %d 帧" % sent)


def header_ok(buf, off, hdr_len):
    """与 glxss.uvc.UvcStream._header_ok / Android headerOk() 一致。"""
    if not 2 <= hdr_len <= 12:
        return False
    if off + hdr_len > len(buf):
        return False
    info = buf[off + 1]
    if info & 0x40:                      # ERR
        return False
    if not info & 0x02:                  # EOF
        return False
    if hdr_len >= 12 and info & 0x08:    # SCR[0..1] == PTS[0..1]
        if buf[off + 6] != buf[off + 2] or buf[off + 7] != buf[off + 3]:
            return False
    return True


def find_header(buf):
    last = len(buf) - 12
    if last < 0:
        return None
    for i in range(last + 1):
        if header_ok(buf, i, buf[i]):
            return i
    return None


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("--chunk", "--urb", dest="chunk", type=int, default=16384,
                   help="单次 bulk 读字节数（usbfs 单 URB 上限 16384）")
    p.add_argument("--frame", type=int, default=3)
    p.add_argument("--format", type=int, default=1)
    p.add_argument("--interval", type=int, default=333333)
    p.add_argument("--max-frame", type=int, default=1382400)
    p.add_argument("--max-payload", type=int, default=16384)
    p.add_argument("--timeout", type=int, default=5000)
    p.add_argument("--seconds", type=float, default=10.0)
    p.add_argument("--reads", type=int, default=0, help="最多读多少次 URB（0=按时间）")
    p.add_argument("--no-detach", action="store_true")
    p.add_argument("--no-hid", action="store_true")
    p.add_argument("--hid", action="store_true")
    p.add_argument("--lcd", action="store_true")
    p.add_argument("--lcd-fps", type=float, default=10.0)
    p.add_argument("--reset", action="store_true", help="打开前 dev.reset()")
    p.add_argument("--clear-halt", action="store_true")
    p.add_argument("--no-set-alt", action="store_true")
    p.add_argument("--dump", default="", help="把前 N 个 URB 原始数据写到文件")
    p.add_argument("--dump-count", type=int, default=30)
    args = p.parse_args(argv)

    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        print("找不到 %04x:%04x" % (VID, PID))
        return 2
    describe(dev)
    if args.reset:
        try:
            dev.reset()
            time.sleep(1.0)
            print("  dev.reset() ok")
        except Exception as exc:
            print("  dev.reset() 失败: %s" % exc)
    try:
        dev.set_configuration()
    except Exception as exc:
        print("  set_configuration: %s（继续）" % exc)

    itfs = find_interfaces(dev)
    vc = itfs.get((IF_VC, 0))
    vs = itfs.get((IF_VS, 0))
    hid = itfs.get((IF_HID, 0))
    lcd = itfs.get((IF_LCD, 0))
    if vs is None:
        print("没有 VS 接口")
        return 2
    gate = Gate()

    # 与 Android 一致的 claim 顺序：VC → HID → VS
    if vc is not None:
        claim(dev, vc, not args.no_detach, "VC")
    if hid is not None and not args.no_hid:
        claim(dev, hid, not args.no_detach, "HID")
    if not claim(dev, vs, not args.no_detach, "VS"):
        print("VS claim 失败")
        return 1
    # LCD 接口：Android 的 UsbLink 一直 claim 着；这里照做（控制面走 gate）。
    if lcd is not None and args.lcd:
        claim(dev, lcd, not args.no_detach, "LCD")
    if not args.no_set_alt:
        try:
            # pyusb 1.2 没有 Device.set_interface；这才是 SET_INTERFACE。
            dev.set_interface_altsetting(IF_VS, vs.bAlternateSetting)
            print("  set_interface_altsetting(%d, alt=%d) ok（false 也继续）" % (
                IF_VS, vs.bAlternateSetting))
        except AttributeError:
            try:
                dev.ctrl_transfer(0x01, 0x0B, vs.bAlternateSetting, IF_VS, 0,
                                  timeout=2000)
                print("  SET_INTERFACE(ctrl) ok")
            except Exception as exc:
                print("  SET_INTERFACE(ctrl) 失败: %s（继续）" % exc)
        except Exception as exc:
            print("  set_interface_altsetting 失败: %s（继续）" % exc)
    if args.clear_halt:
        with gate:
            clear_halt(dev)

    probe_commit(dev, gate, IF_VS, args.format, args.frame, args.interval,
                 args.max_frame, args.max_payload)

    stop = threading.Event()
    threads = []
    if args.hid:
        t = threading.Thread(target=hid_thread, args=(dev, gate, stop), daemon=True)
        t.start()
        threads.append(t)
    if args.lcd:
        t = threading.Thread(target=lcd_thread, args=(dev, gate, stop, args.lcd_fps),
                             daemon=True)
        t.start()
        threads.append(t)

    print("开始 bulk IN：ep=0x%02x chunk=%d timeout=%dms" % (
        EP_IN, args.chunk, args.timeout))
    t0 = time.monotonic()
    reads = timeouts = errors = frames = 0
    invalid = dropped = 0
    rx = bytearray()
    synced = False
    first = True
    dump_fh = open(args.dump, "wb") if args.dump else None
    dumped = 0
    while time.monotonic() - t0 < args.seconds:
        if args.reads and reads >= args.reads:
            break
        started = time.monotonic()
        try:
            buf = bytes(dev.read(EP_IN, args.chunk, timeout=args.timeout))
            n = len(buf)
        except usb.core.USBTimeoutError:
            timeouts += 1
            dt = (time.monotonic() - started) * 1000
            if timeouts <= 3 or timeouts % 20 == 0:
                print("  [%5.1fs] 超时 #%d（%.0f ms）" % (time.monotonic() - t0, timeouts, dt))
                sys.stdout.flush()
            continue
        except Exception as exc:
            errors += 1
            if errors <= 3:
                print("  读错误: %s" % exc)
            time.sleep(0.05)
            continue
        reads += 1
        if dump_fh is not None and dumped < args.dump_count:
            dump_fh.write(struct.pack("<I", n))
            dump_fh.write(bytes(buf[:n]))
            dumped += 1
        if first or reads <= 3:
            print("  [%5.1fs] chunk #%d n=%d first16=%s" % (
                time.monotonic() - t0, reads, n, bytes(buf[:16]).hex()))
            sys.stdout.flush()
            first = False
        rx.extend(buf)
        pos = 0
        while True:
            if not synced:
                found = find_header(rx[pos:] if pos else rx)
                if found is None:
                    # 保留末尾 11 B（header 可能跨块）
                    keep = 11
                    if len(rx) - pos > keep:
                        dropped += len(rx) - pos - keep
                        pos = len(rx) - keep
                    break
                dropped += found
                pos += found
                synced = True
            if len(rx) - pos < 2:
                break
            hdr_len = rx[pos]
            if len(rx) - pos < hdr_len:
                break
            if not header_ok(rx, pos, hdr_len):
                invalid += 1
                if invalid <= 5:
                    print("    无效 header: bHeaderLength=%d info=0x%02x" % (
                        hdr_len, rx[pos + 1]))
                synced = False
                dropped += 1
                pos += 1
                continue
            need = hdr_len + args.max_frame
            if len(rx) - pos < need:
                break
            frames += 1
            if frames <= 3:
                print("    帧 #%d hdr=%s len=%d" % (
                    frames, bytes(rx[pos:pos + 12]).hex(), need))
            pos += need
        del rx[:pos]
    elapsed = max(1e-6, time.monotonic() - t0)
    print("结果：%.1f s  读块=%d 帧=%d 超时=%d 错误=%d 无效header=%d 丢弃=%dB  fps=%.1f" % (
        elapsed, reads, frames, timeouts, errors, invalid, dropped, frames / elapsed))
    stop.set()
    for t in threads:
        t.join(timeout=2.0)
    if dump_fh is not None:
        dump_fh.close()
        print("原始数据 -> %s (%d URBs)" % (args.dump, dumped))
    try:
        dev.set_interface(IF_VS, 0)
    except Exception:
        pass
    try:
        usb.util.dispose_resources(dev)
    except Exception:
        pass
    return 0 if frames else 1


if __name__ == "__main__":
    raise SystemExit(main())
