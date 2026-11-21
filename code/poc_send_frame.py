#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
LLVision GLXSS3 (G25) USB 投屏发送端 PoC

协议（逆向自 App，详见 docs/protocol.md）：
    帧 = 16 字节头 + 640*400*4 字节原始 RGBA8888
    头: [0]=format(1) [1..3]=0 [4..7]=总长(小端) [8..15]=扩展(0)

用法（Linux，需 root 或 udev 权限）：
    pip install pyusb
    python3 poc_send_frame.py --list
    python3 poc_send_frame.py --pattern bars --fps 5
    python3 poc_send_frame.py --solid ff8000 --fps 1

注意：
  * 眼镜须处于正常模式（VID 0x2e09 / PID 0x0030）；boot 模式为 0x03e7/0x2150。
  * 本脚本只发帧；开屏/亮度/显示模式属于 control transfer，参数待真机抓包（docs/open-questions.md）。
  * 复合设备上请选 vendor-specific(class=0xFF) 且带 bulk OUT 的接口，避免 claim 到 UVC。
"""
import argparse
import struct
import sys
import time

try:
    import usb.core
    import usb.util
except ImportError:
    sys.exit("请先: pip install pyusb")

VID, PID = 0x2E09, 0x0030
W, H = 640, 400
FRAME_BYTES = W * H * 4  # 1,024,000


def find_device():
    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        sys.exit("未找到 0x2e09:0x0030 设备（检查线材/模式/USB 权限）")
    return dev


def dump(dev):
    print("device: %04x:%04x bus=%s addr=%s" % (dev.idVendor, dev.idProduct, dev.bus, dev.address))
    try:
        dev.set_configuration()
    except usb.core.USBError as e:
        print("set_configuration:", e)
    for cfg in dev:
        print(" config %d" % cfg.bConfigurationValue)
        for intf in cfg:
            print("  intf %d alt %d class=0x%02x sub=0x%02x proto=0x%02x" % (
                intf.bInterfaceNumber, intf.bAlternateSetting,
                intf.bInterfaceClass, intf.bInterfaceSubClass, intf.bInterfaceProtocol))
            for ep in intf:
                print("    ep 0x%02x attr=%d maxpkt=%d" % (
                    ep.bEndpointAddress, ep.bmAttributes, ep.wMaxPacketSize))


def pick_endpoint(dev):
    cands = []
    for cfg in dev:
        for intf in cfg:
            for ep in intf:
                is_bulk = (ep.bmAttributes & 0x03) == 0x02
                is_out = (ep.bEndpointAddress & 0x80) == 0
                if is_bulk and is_out:
                    cands.append((intf.bInterfaceNumber, ep.bEndpointAddress, intf.bInterfaceClass))
    if not cands:
        sys.exit("没有找到 bulk OUT 端点")
    prio = [c for c in cands if c[2] == 0xFF]
    return (prio or cands)[0]


def pattern_bars():
    buf = bytearray(FRAME_BYTES)
    colors = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 255),
              (0, 0, 0), (255, 255, 0), (0, 255, 255), (255, 0, 255)]
    n = len(colors)
    for y in range(H):
        row = y * W * 4
        shade = 255 - int(200 * y / max(1, H - 1))
        for x in range(W):
            r, g, b = colors[(x * n // W) % n]
            o = row + x * 4
            buf[o] = r * shade // 255
            buf[o + 1] = g * shade // 255
            buf[o + 2] = b * shade // 255
            buf[o + 3] = 255
    return bytes(buf)


def pattern_solid(hexcolor):
    r = int(hexcolor[0:2], 16)
    g = int(hexcolor[2:4], 16)
    b = int(hexcolor[4:6], 16)
    return bytes((r, g, b, 255)) * (W * H)


def frame(payload):
    return bytes((1, 0, 0, 0)) + struct.pack("<I", len(payload) + 16) + bytes(8) + payload


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", action="store_true", help="只列出设备接口/端点")
    ap.add_argument("--pattern", default="bars", choices=("bars",))
    ap.add_argument("--solid", help="纯色 RRGGBB")
    ap.add_argument("--fps", type=float, default=1.0)
    ap.add_argument("--seconds", type=float, default=5.0)
    ap.add_argument("--timeout", type=int, default=2000, help="bulk 超时 ms")
    args = ap.parse_args()

    dev = find_device()
    if args.list:
        dump(dev)
        return

    dump(dev)
    intf_no, ep, cls = pick_endpoint(dev)
    print("-> interface %d (class 0x%02x), endpoint 0x%02x" % (intf_no, cls, ep))
    try:
        usb.util.claim_interface(dev, intf_no)
    except usb.core.USBError as e:
        print("claim_interface:", e, "（可能被内核驱动占用）")

    payload = pattern_solid(args.solid) if args.solid else pattern_bars()
    pkt = frame(payload)
    print("frame: %d bytes (16 + %d)" % (len(pkt), len(payload)))

    sent = 0
    t0 = time.time()
    period = 1.0 / max(args.fps, 0.01)
    try:
        while time.time() - t0 < args.seconds:
            t = time.time()
            dev.write(ep, pkt, args.timeout)
            sent += 1
            dt = time.time() - t
            if dt < period:
                time.sleep(period - dt)
    except KeyboardInterrupt:
        pass
    finally:
        dur = time.time() - t0
        print("发送 %d 帧, %.1f 秒, 平均 %.1f fps" % (sent, dur, sent / max(1e-6, dur)))
        try:
            usb.util.release_interface(dev, intf_no)
        except Exception:
            pass


if __name__ == "__main__":
    main()
