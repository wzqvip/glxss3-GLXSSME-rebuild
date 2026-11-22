# -*- coding: utf-8 -*-
"""无硬件自检：帧头、RGBA 打包、YV12、AES、UVC header、CLI 解析。

运行：
  cd linux && python -m unittest discover -s tests -v
"""

from __future__ import annotations

import io
import os
import socket
import struct
import tempfile
import threading
import time
import unittest
from contextlib import redirect_stdout
from types import SimpleNamespace

from glxss import capture, control, firmware, hid, hud, lcd, monitor, usb, uvc
from glxss.errors import GlxssError
from glxss.cli import build_parser
from glxss.cli import main as cli_main


class FrameTests(unittest.TestCase):
    def test_frame_header_and_size(self):
        payload = bytes([0x11, 0x22, 0x33, 0xFF]) * (lcd.FRAME_W * lcd.FRAME_H)
        packet = lcd.build_frame(payload)
        self.assertEqual(len(packet), lcd.FRAME_TOTAL)
        self.assertEqual(packet[:4], b"\x01\x00\x00\x00")
        self.assertEqual(struct.unpack_from("<I", packet, 4)[0], lcd.FRAME_TOTAL)
        self.assertEqual(packet[8:16], b"\x00" * 8)
        self.assertEqual(packet[16:20], b"\x11\x22\x33\xff")

    def test_bad_payload_rejected(self):
        with self.assertRaises(Exception):
            lcd.build_frame(b"\x00" * 10)


class StatusAndHidTests(unittest.TestCase):
    def test_lcd_status_sample(self):
        status = lcd.LcdStatus.parse(bytes.fromhex("80 02 90 01 01 b9 0a 00"))
        self.assertEqual((status.width, status.height), (640, 400))
        self.assertTrue(status.screen_open)
        self.assertEqual(status.luminance, 0xB9)
        self.assertEqual(status.roll, 10)

    def test_hid_report_sample(self):
        sample = bytes.fromhex(
            "00 71 50 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 "
            "00 00 00 00 41 1b 72 42"
        )
        report = hid.parse_report(sample)
        self.assertEqual(report.key_code, 0x71)
        self.assertEqual(report.lux, 80)
        self.assertFalse(report.pressed)
        self.assertAlmostEqual(report.temperature, 60.527, places=2)


class CaptureTests(unittest.TestCase):
    def test_test_pattern_and_rgba(self):
        source = capture.TestPatternSource(animate=False)
        rgb = source.grab()
        self.assertEqual(rgb.shape, (400, 640, 3))
        self.assertEqual(rgb.dtype.name, "uint8")
        rgba = capture.map_to_rgba(rgb, mode="fit")
        self.assertEqual(rgba.shape, (400, 640, 4))
        self.assertTrue((rgba[:, :, 3] == 255).all())
        payload = capture.rgba_bytes(rgba)
        self.assertEqual(len(payload), lcd.FRAME_PAYLOAD)

    def test_mapping_modes(self):
        import numpy as np
        rgb = np.zeros((200, 100, 3), dtype=np.uint8)
        for mode in ("fit", "cover", "crop", "stretch"):
            rgba = capture.map_to_rgba(rgb, mode=mode)
            self.assertEqual(rgba.shape, (400, 640, 4))
            self.assertTrue((rgba[..., 3] == 255).all())

    def test_png_roundtrip(self):
        """Pillow 保存后回读，证明 RGBA 打包没有丢通道/错位。"""
        try:
            from PIL import Image
        except Exception:
            self.skipTest("Pillow 未安装")
        source = capture.TestPatternSource(animate=False)
        rgba = capture.map_to_rgba(source.grab(), mode="cover")
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "test-pattern.png")
            capture.save_rgba_png(path, rgba)
            with Image.open(path) as image:
                self.assertEqual(image.mode, "RGBA")
                self.assertEqual(image.size, (640, 400))
                roundtrip = image.copy()
            data = roundtrip.tobytes()
            self.assertEqual(data, rgba.tobytes())


class HudTests(unittest.TestCase):
    """HUD 渲染是纯软件路径；有 Pillow+numpy 就能在无硬件环境验证。"""

    READY = hud.Image is not None and hud.np is not None

    def test_heading_from_compass(self):
        self.assertAlmostEqual(hud.heading_from_compass(1, 0), 0.0)
        self.assertAlmostEqual(hud.heading_from_compass(0, 1), 90.0)
        self.assertAlmostEqual(hud.heading_from_compass(-1, 0), 180.0)
        self.assertAlmostEqual(hud.heading_from_compass(0, -1), 270.0)
        self.assertAlmostEqual(hud.heading_from_compass(0, 0), 0.0)
        self.assertAlmostEqual(hud.heading_from_compass(1, 0, 10), 10.0)
        demo = hud.demo_state()
        self.assertTrue(35 <= demo.heading <= 38)

    def test_pitch_roll(self):
        self.assertEqual(hud.pitch_roll_from_acc(0, 0, 0), (0.0, 0.0))
        pitch, roll = hud.pitch_roll_from_acc(-350, 9200, 120)
        self.assertAlmostEqual(pitch, -2.18, delta=0.1)
        self.assertAlmostEqual(roll, 0.75, delta=0.1)

    @unittest.skipUnless(READY, "需要 Pillow + numpy")
    def test_render_size_layers_and_png(self):
        source = hud.HudSource(demo=True)
        image = source.render()
        self.assertEqual(image.mode, "RGBA")
        self.assertEqual(image.size, (640, 400))
        self.assertEqual(len(image.tobytes()), 640 * 400 * 4)
        colors = image.convert("RGB").getcolors(maxcolors=100000)
        self.assertIsNotNone(colors)
        self.assertGreater(len(colors), 16)  # 不是纯黑/纯色帧
        self.assertIsNotNone(image.getbbox())

        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "hud.png")
            source.save_png(path)
            self.assertGreater(os.path.getsize(path), 5000)
            from PIL import Image
            with Image.open(path) as saved:
                self.assertEqual(saved.size, (640, 400))
                self.assertEqual(saved.mode, "RGBA")

    @unittest.skipUnless(READY, "需要 Pillow + numpy")
    def test_grab_shape_and_cache(self):
        source = hud.HudSource(demo=True, render_interval=60.0)
        rgb = source.grab()
        self.assertEqual(rgb.shape, (400, 640, 3))
        self.assertEqual(rgb.dtype.name, "uint8")
        # 60 s 内应直接命中缓存（同一个对象）
        self.assertIs(source.grab(), rgb)

    @unittest.skipUnless(READY, "需要 Pillow + numpy")
    def test_hud_paused_and_nav(self):
        source = hud.HudSource(demo=True)
        source.set_hud_enabled(False)
        paused = source.render()
        self.assertEqual(paused.size, (640, 400))
        self.assertNotEqual(paused.tobytes(), source._background().tobytes())
        source.set_hud_enabled(True)
        source.set_nav(90, 1234, "Gate")
        self.assertIsNotNone(source.snapshot().nav)
        source.clear_nav()
        self.assertIsNone(source.snapshot().nav)

    @unittest.skipUnless(READY, "需要 Pillow + numpy")
    def test_update_report(self):
        sample = struct.pack(
            "<BBH9hBBf", 0, 0x71, 80,
            100, 200, 300, 10, 20, 30, 0, 0, 0, 0, 0, 36.5)
        source = hud.HudSource()
        source.update_report(hid.parse_report(sample))
        state = source.snapshot()
        self.assertEqual(state.lux, 80)
        self.assertAlmostEqual(state.temperature, 36.5, places=2)
        self.assertTrue(state.sensors_valid)
        self.assertTrue(state.compass_valid)
        self.assertTrue(state.acc_valid)
        self.assertAlmostEqual(state.heading, 63.43, delta=0.1)

    @unittest.skipUnless(READY, "需要 Pillow + numpy")
    def test_hid_fn_key_callback_only_on_fn(self):
        source = hud.HudSource()
        released = struct.pack(
            "<BBH9hBBf", 0, 0x71, 80,
            100, 200, 300, 10, 20, 30, 0, 0, 0, 0, 0, 36.5)
        pressed = struct.pack(
            "<BBH9hBBf", 1, 0x71, 80,
            100, 200, 300, 10, 20, 30, 0, 0, 0, 0, 0, 36.5)
        other = struct.pack(
            "<BBH9hBBf", 1, 0x01, 80,
            100, 200, 300, 10, 20, 30, 0, 0, 0, 0, 0, 36.5)

        class FakeDev:
            data = released

            def ctrl_transfer(self, request_type, request, value, index,
                              length, timeout=None):
                if request == 0x16:
                    return bytearray(self.data)
                return bytearray(length)

        dev = FakeDev()
        hits = []
        fired = threading.Event()

        def on_key(report):
            hits.append(report.key_code)
            fired.set()

        source.attach_hid(dev, interval=0.01, timeout=100, on_key=on_key)
        try:
            time.sleep(0.05)
            dev.data = pressed
            self.assertTrue(fired.wait(2.0), "Fn 按下没有触发回调")
            time.sleep(0.05)
            dev.data = released
            time.sleep(0.05)
            dev.data = other
            time.sleep(0.15)
            self.assertEqual(hits, [0x71])
        finally:
            source.close()

    @unittest.skipUnless(READY, "需要 Pillow + numpy")
    def test_cli_hud_dry_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "hud.png")
            with redirect_stdout(io.StringIO()):
                code = cli_main(["hud", "--dry-run", "--save-png", path])
            self.assertEqual(code, 0)
            self.assertTrue(os.path.exists(path))
            self.assertGreater(os.path.getsize(path), 5000)


class ControlTests(unittest.TestCase):
    @unittest.skipUnless(hasattr(socket, "AF_UNIX"),
                         "当前平台没有 AF_UNIX")
    def test_server_client_roundtrip(self):
        def handler(request):
            if request.get("cmd") == "ping":
                return {"ok": True, "pong": True}
            return {"ok": True, "status": {"connected": True}, "echo": request}

        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "glxss.sock")
            server = control.ControlServer(path, handler)
            server.start()
            try:
                client = control.ControlClient(path, timeout=2)
                self.assertTrue(client.available())
                response = client.request("status")
                self.assertTrue(response["ok"])
                self.assertTrue(response["status"]["connected"])
            finally:
                server.stop()
            self.assertFalse(os.path.exists(path))


class YuvTests(unittest.TestCase):
    def test_black_and_white(self):
        try:
            __import__("numpy")
        except Exception:
            self.skipTest("numpy 未安装")
        w, h = 8, 8
        y_size = w * h
        c_size = y_size // 4
        black = bytes([16]) * y_size + bytes([128]) * (2 * c_size)
        rgb = uvc.yv12_to_rgb(black, w, h)
        self.assertEqual(rgb.shape, (h, w, 3))
        self.assertTrue((rgb == 0).all())
        white = bytes([235]) * y_size + bytes([128]) * (2 * c_size)
        rgb = uvc.yv12_to_rgb(white, w, h)
        self.assertTrue((rgb >= 254).all())

    def test_size_check(self):
        with self.assertRaises(Exception):
            uvc.yv12_to_rgb(b"\x00" * 10, 8, 8)


class UsbHelpersTests(unittest.TestCase):
    def test_interface_label_does_not_require_configuration(self):
        class FakeInterface:
            bInterfaceNumber = 1
            bAlternateSetting = 0
            bInterfaceClass = 0x0E
            bInterfaceSubClass = 0x02
            bInterfaceProtocol = 0

        text = usb.interface_label(FakeInterface())
        self.assertIn("if=1", text)
        self.assertIn("class=0x0e", text)

    def test_usb_gate_is_reentrant(self):
        gate = usb.UsbGate()
        with gate:
            with gate:
                pass


class FastRgbaTests(unittest.TestCase):
    """monitor 的 YV12→RGBA 快路径；无硬件也能验证颜色/尺寸/旋转。"""

    @staticmethod
    def _frame(w=640, h=480):
        rng = __import__("numpy").random.default_rng(1)
        y = rng.integers(0, 256, w * h, dtype=__import__("numpy").uint8)
        u = rng.integers(0, 256, w * h // 4, dtype=__import__("numpy").uint8)
        v = rng.integers(0, 256, w * h // 4, dtype=__import__("numpy").uint8)
        return y.tobytes() + v.tobytes() + u.tobytes()

    def test_cover_matches_reference(self):
        try:
            import numpy as np
        except Exception:
            self.skipTest("numpy 未安装")
        w, h = 640, 480
        data = self._frame(w, h)
        reference = uvc.yv12_to_rgb(data, w, h)
        rgba = uvc.decode_yv12_rgba(data, w, h, 640, 400, fit="cover")
        self.assertEqual(rgba.shape, (400, 640, 4))
        self.assertEqual(rgba.dtype.name, "uint8")
        self.assertTrue((rgba[..., 3] == 255).all())
        diff = np.abs(rgba[:, :, :3].astype(np.int16) -
                      reference[40:440].astype(np.int16))
        # 分开取整/截断与参考实现最多差 1。
        self.assertLessEqual(int(diff.max()), 2)
        self.assertLess(float(diff.mean()), 2.0)

    def test_modes_and_half(self):
        try:
            __import__("numpy")
        except Exception:
            self.skipTest("numpy 未安装")
        data = self._frame()
        for fit in ("cover", "stretch", "fit"):
            with self.subTest(fit=fit):
                rgba = uvc.decode_yv12_rgba(data, 640, 480, 640, 400, fit=fit)
                self.assertEqual(rgba.shape, (400, 640, 4))
        half = uvc.decode_yv12_rgba(data, 640, 480, 640, 400,
                                    fit="cover", half=True)
        self.assertEqual(half.shape, (400, 640, 4))

    def test_rotate180_matches_reference(self):
        try:
            import numpy as np
        except Exception:
            self.skipTest("numpy 未安装")
        rgba = np.arange(400 * 640 * 4, dtype=np.uint8).reshape(400, 640, 4)
        rotated = capture.rotate180_rgba(rgba)
        expected = np.ascontiguousarray(rgba[::-1, ::-1])
        self.assertTrue((rotated == expected).all())


class MonitorTests(unittest.TestCase):
    READY = hud.Image is not None and hud.np is not None

    class _TimeoutStream:
        def __init__(self):
            self.closed = False

        def read_frame(self, timeout=None):
            import time
            time.sleep(0.01)
            raise GlxssError("UVC 读帧超时（测试）")

        def close(self):
            self.closed = True

    def test_overlay_grid_level_and_rec(self):
        if not self.READY:
            self.skipTest("需要 Pillow + numpy")
        import numpy as np
        source = monitor.MonitorSource(
            self._TimeoutStream(), timeout=100,
            state_provider=hud.demo_state, rec=True)
        try:
            rgba = np.zeros((400, 640, 4), dtype=np.uint8)
            rgba[..., 3] = 255
            source._compose(rgba)
            # 三分线：x=213 与 y=133 不应再是全黑。
            self.assertGreater(int(rgba[:, 213, :3].sum()), 0)
            self.assertGreater(int(rgba[133, :, :3].sum()), 0)
            # REC 红点/文字叠加在右上角，红色通道明显。
            corner = rgba[4:34, 486:640, 0]
            self.assertGreater(int(corner.max()), 200)
            self.assertTrue((rgba[..., 3] == 255).all())
        finally:
            source.close()

    def test_mode_source_switch_and_fallback(self):
        if not self.READY:
            self.skipTest("需要 Pillow + numpy")
        import numpy as np

        class FakeHud:
            name = "hud"

            def grab(self):
                return np.zeros((400, 640, 3), dtype=np.uint8)

            def grab_rgba(self):
                return np.zeros((400, 640, 4), dtype=np.uint8)

            def close(self):
                pass

        opened = []

        class FakeMonitor:
            name = "uvc"

            def __init__(self):
                self.closed = False

            def grab_rgba(self):
                return np.full((400, 640, 4), 7, dtype=np.uint8)

            def grab(self):
                return np.zeros((400, 640, 3), dtype=np.uint8)

            def set_rec(self, value):
                pass

            def stats_dict(self):
                return {"read_fps": 1.0}

            def close(self):
                self.closed = True

        def opener():
            item = FakeMonitor()
            opened.append(item)
            return item

        source = monitor.ModeSource(FakeHud(), opener)
        self.assertEqual(source.mode, "hud")
        self.assertEqual(int(source.grab_rgba().max()), 0)
        source.request_view("monitor")
        self.assertEqual(int(source.grab_rgba().max()), 7)
        self.assertEqual(source.mode, "monitor")
        self.assertEqual(len(opened), 1)
        source.request_view("hud")
        source.grab_rgba()
        self.assertEqual(source.mode, "hud")
        self.assertTrue(opened[0].closed)

        def broken():
            raise GlxssError("UVC 不可用（测试）")

        fallback = monitor.ModeSource(FakeHud(), broken)
        fallback.request_view("monitor")
        self.assertEqual(int(fallback.grab_rgba().max()), 0)
        self.assertEqual(fallback.mode, "hud")
        self.assertIn("UVC", fallback.error)


class MonitorCliTests(unittest.TestCase):
    def test_cast_uvc_args(self):
        args = build_parser().parse_args(
            ["cast", "--source", "uvc", "--fps", "10", "--rec",
             "--camera-fps", "15", "--uvc-half", "--rotate", "180"])
        self.assertEqual(args.source, "uvc")
        self.assertEqual(args.fps, 10)
        self.assertTrue(args.rec)
        self.assertTrue(args.uvc_half)
        self.assertEqual(args.rotate, 180)
        self.assertIsNone(args.fit)  # uvc 默认在 cmd_cast 里解析为 cover

    def test_hud_view_args(self):
        args = build_parser().parse_args(
            ["hud", "--view", "monitor", "--listen", "--detach",
             "--monitor-fit", "cover", "--rotate", "180"])
        self.assertEqual(args.view, "monitor")
        self.assertEqual(args.monitor_fit, "cover")
        self.assertEqual(args.rotate, 180)

    def test_ctl_view_and_rec(self):
        parser = build_parser()
        self.assertEqual(parser.parse_args(["ctl", "view", "monitor"]).value,
                         "monitor")
        self.assertEqual(parser.parse_args(["ctl", "rec", "on"]).value, "on")
        self.assertEqual(parser.parse_args(["ctl", "mode", "monitor"]).value,
                         "monitor")


class UvcChunkFramingTests(unittest.TestCase):
    """16 KiB 分块 + 字节流组帧（Android cc310be 的移植）。

    背景：usbfs 单次 bulk 读上限 16384 B（= libusb MAX_BULK_BUFFER_LENGTH），
    一次读 460822 在 Android 上立刻 -1；设备侧一帧仍是 12 B header + 460800 B。
    """

    @staticmethod
    def _frame(w, h, pts=0x2B4):
        y_size = w * h
        c_size = y_size // 4
        payload = bytes([16]) * y_size + bytes([128]) * (2 * c_size)
        header = bytes([12, 0x0F]) + struct.pack("<I", pts) + bytes([pts & 0xFF,
                                                                    (pts >> 8) & 0xFF,
                                                                    0, 0, 0, 0])
        return header + payload, payload

    class ChunkDev:
        """按请求尺寸返回连续字节流的假设备（模拟 16 KiB bulk 读）。"""

        def __init__(self, blob, max_chunk=uvc.BULK_CHUNK):
            self.blob = bytes(blob)
            self.pos = 0
            self.max_chunk = max_chunk
            self.sizes = []

        def read(self, endpoint, size, timeout=0):
            self.sizes.append(size)
            take = min(int(size), self.max_chunk, len(self.blob) - self.pos)
            chunk = self.blob[self.pos:self.pos + take]
            self.pos += len(chunk)
            return chunk

    def test_production_frame_uses_16k_chunks(self):
        header, payload = self._frame(640, 480)
        # 从帧中间开始（前面 3 B 是上一帧的尾字节）+ 两帧首尾相接
        dev = self.ChunkDev(b"\x7f\x80\x81" + header + payload + header + payload)
        stream = uvc.UvcStream(dev, SimpleNamespace(bInterfaceNumber=1),
                               SimpleNamespace(bEndpointAddress=0x81), timeout=1000)
        stream.set_dimensions(640, 480)
        self.assertEqual(stream.chunk_size, uvc.BULK_CHUNK)
        frame = stream.read_frame()
        self.assertEqual(frame.data, payload)
        self.assertEqual(len(frame.data), 460800)
        self.assertEqual(frame.header.header_len + len(frame.data), 460812)
        # 每个 bulk 读都不超过 16384，且第一帧由多块拼成
        self.assertTrue(dev.sizes)
        self.assertLessEqual(max(dev.sizes), 16384)
        self.assertEqual(dev.sizes[0], 16384)
        self.assertGreater(len(dev.sizes), 20)
        self.assertGreaterEqual(stream.dropped_bytes, 3)
        # 第二帧的首块已在缓冲里：继续读完即可，仍然按 ≤16 KiB 块拼
        second = stream.read_frame()
        self.assertEqual(second.data, payload)
        self.assertEqual(stream.synced_frames, 2)
        self.assertLessEqual(max(dev.sizes), 16384)

    def test_header_split_across_chunks(self):
        header, payload = self._frame(8, 8)
        dev = self.ChunkDev(header + payload, max_chunk=7)
        stream = uvc.UvcStream(dev, SimpleNamespace(bInterfaceNumber=1),
                               SimpleNamespace(bEndpointAddress=0x81), timeout=1000)
        stream.set_dimensions(8, 8)
        stream.chunk_size = 7  # 人为把 header 切碎（真实 16 KiB 块也可能跨 header）
        frame = stream.read_frame()
        self.assertEqual(frame.data, payload)
        self.assertEqual(frame.header.pts, 0x2B4)

    def test_resync_skips_fake_headers(self):
        header, payload = self._frame(8, 8)
        # 假 header：0x0C 开头但 EOF=0 / SCR 与 PTS 不一致，都不能被当成帧起点
        fake1 = bytes([12, 0x0D, 1, 2, 0, 0, 9, 9, 0, 0, 0, 0])
        fake2 = bytes([12, 0x0E, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0])
        dev = self.ChunkDev(b"\x00" * 5 + fake1 + b"abc" + fake2 + header + payload)
        stream = uvc.UvcStream(dev, SimpleNamespace(bInterfaceNumber=1),
                               SimpleNamespace(bEndpointAddress=0x81), timeout=1000)
        stream.set_dimensions(8, 8)
        frame = stream.read_frame()
        self.assertEqual(frame.data, payload)
        self.assertGreaterEqual(stream.dropped_bytes, len(fake1) + len(fake2))

    def test_header_checks(self):
        good = bytes.fromhex("0c 0f b4 02 00 00 b4 02 00 00 b4 02")
        self.assertTrue(uvc.UvcStream._header_ok(good, 0, 12))
        no_eof = bytes.fromhex("0c 0d b4 02 00 00 b4 02 00 00 b4 02")
        self.assertFalse(uvc.UvcStream._header_ok(no_eof, 0, 12))
        err = bytes.fromhex("0c 4f b4 02 00 00 b4 02 00 00 b4 02")
        self.assertFalse(uvc.UvcStream._header_ok(err, 0, 12))
        bad_scr = bytes.fromhex("0c 0f b4 02 00 00 b5 02 00 00 b4 02")
        self.assertFalse(uvc.UvcStream._header_ok(bad_scr, 0, 12))
        self.assertFalse(uvc.UvcStream._header_ok(good, 0, 1))
        self.assertFalse(uvc.UvcStream._header_ok(good[:8], 0, 12))

    def test_timeout_reports_framing_state(self):
        class EmptyDev:
            def read(self, endpoint, size, timeout=0):
                return b""

        stream = uvc.UvcStream(EmptyDev(), SimpleNamespace(bInterfaceNumber=1),
                               SimpleNamespace(bEndpointAddress=0x81), timeout=60)
        stream.set_dimensions(640, 480)
        with self.assertRaises(GlxssError) as ctx:
            stream.read_frame()
        self.assertIn("超时", str(ctx.exception))


class UvcHeaderTests(unittest.TestCase):
    def test_parse_12_byte_header(self):
        header = bytes.fromhex("0c 0f b4 02 00 00 b4 02 00 00 b4 02")
        parsed = uvc.parse_header(header)
        self.assertTrue(parsed.valid)
        self.assertEqual(parsed.header_len, 12)
        self.assertTrue(parsed.eof)
        self.assertEqual(parsed.pts, 0x2B4)
        self.assertEqual(parsed.scr, bytes.fromhex("b4 02 00 00 b4 02"))

    def test_read_frame_assembles_payload(self):
        class FakeDev:
            def __init__(self, urbs):
                self.urbs = list(urbs)

            def read(self, endpoint, size, timeout=0):
                return self.urbs.pop(0)

        w, h = 8, 8
        y_size = w * h
        c_size = y_size // 4
        yuv = bytes([16]) * y_size + bytes([128]) * (2 * c_size)
        header = bytes.fromhex("0c 0f 00 00 00 00 00 00 00 00 00 00")
        stream = uvc.UvcStream(
            FakeDev([header + yuv]), SimpleNamespace(bInterfaceNumber=1),
            SimpleNamespace(bEndpointAddress=0x81), timeout=1000)
        stream.set_dimensions(w, h)
        frame = stream.read_frame()
        self.assertEqual(frame.data, yuv)
        self.assertTrue(frame.header.eof)
        self.assertEqual(frame.rgb().shape, (h, w, 3))


class UvcNegotiateTests(unittest.TestCase):
    """Pi 侧回归：probe 不能沿用 GET_CUR 的残留档位，URB 必须 frameSize+22。"""

    @staticmethod
    def _poisoned_probe():
        # 半坏状态：bFrameIndex=1（1080p）、maxPayload=GET_MAX 全局上限。
        cur = bytearray(34)
        struct.pack_into("<H", cur, 0, 1)
        cur[2], cur[3] = 1, 1
        struct.pack_into("<I", cur, 4, 333333)
        struct.pack_into("<I", cur, 18, 460800)
        struct.pack_into("<I", cur, 22, 19693452)
        return cur

    def test_negotiate_overrides_residual_frame_and_urb(self):
        state = {"cur": self._poisoned_probe()}
        writes = []

        class FakeDev:
            def ctrl_transfer(self, request_type, request, value, index,
                              data_or_length, timeout=0):
                if request == uvc.UVC_GET_CUR:
                    return bytes(state["cur"])
                if request == uvc.UVC_GET_DEF:
                    raise RuntimeError("no GET_DEF")
                payload = bytes(data_or_length)
                writes.append((value, payload))
                if value == uvc.UVC_VS_PROBE:
                    state["cur"] = bytearray(payload.ljust(34, b"\x00"))
                return len(payload)

        stream = uvc.UvcStream(
            FakeDev(), SimpleNamespace(bInterfaceNumber=1),
            SimpleNamespace(bEndpointAddress=0x81), timeout=1000)
        probe = stream.negotiate(fps=30)
        self.assertEqual(probe.format_index, 1)
        self.assertEqual(probe.frame_index, 4)
        self.assertEqual(probe.interval, 333333)
        self.assertEqual(stream.frame_size, 460800)
        self.assertEqual(stream.urb_size, 460822)       # 逻辑帧缓冲（日志用）
        self.assertEqual(stream.chunk_size, 16384)      # 单次 bulk 读 ≤16 KiB
        self.assertEqual(stream.frame_size + uvc.MAX_HEADER, 460812)
        probe_out = [p for (value, p) in writes if value == uvc.UVC_VS_PROBE][0]
        self.assertEqual(probe_out[3], 4)                      # bFrameIndex
        self.assertEqual(struct.unpack_from("<I", probe_out, 22)[0], 16384)

    def test_negotiate_keeps_working_when_get_cur_dies(self):
        class FakeDev:
            def ctrl_transfer(self, request_type, request, value, index,
                              data_or_length, timeout=0):
                if request in (uvc.UVC_GET_CUR, uvc.UVC_GET_DEF):
                    raise RuntimeError("stalled")
                return len(bytes(data_or_length))

        stream = uvc.UvcStream(
            FakeDev(), SimpleNamespace(bInterfaceNumber=1),
            SimpleNamespace(bEndpointAddress=0x81), timeout=1000)
        stream.negotiate(fps=30)  # 不该抛：基底用全 0 也能 commit
        self.assertEqual(stream.urb_size, 460822)


class AltSettingTests(unittest.TestCase):
    def test_set_alt_uses_set_interface_altsetting(self):
        class Dev:
            def __init__(self):
                self.calls = []

            def set_interface_altsetting(self, number, alt):
                self.calls.append((number, alt))

        dev = Dev()
        self.assertTrue(usb.set_alt(
            dev, SimpleNamespace(bInterfaceNumber=1, bAlternateSetting=0)))
        self.assertEqual(dev.calls, [(1, 0)])

    def test_set_alt_falls_back_to_control_request(self):
        class Dev:
            def __init__(self):
                self.calls = []

            def set_interface_altsetting(self, number, alt):
                raise NotImplementedError

            def ctrl_transfer(self, request_type, request, value, index,
                              data_or_length, timeout=0):
                self.calls.append((request_type, request, value, index))
                return 0

        dev = Dev()
        self.assertTrue(usb.set_alt(
            dev, SimpleNamespace(bInterfaceNumber=1, bAlternateSetting=0)))
        self.assertEqual(dev.calls, [(0x01, 0x0B, 0, 1)])

    def test_clear_halt_falls_back_to_control_request(self):
        class Dev:
            def __init__(self):
                self.calls = []

            def clear_halt(self, ep):
                raise AttributeError("no clear_halt")

            def ctrl_transfer(self, request_type, request, value, index,
                              data_or_length, timeout=0):
                self.calls.append((request_type, request, value, index))
                return 0

        dev = Dev()
        self.assertTrue(usb.clear_halt(dev, 0x81))
        self.assertEqual(dev.calls, [(0x02, 0x01, 0, 0x81)])


class AesTests(unittest.TestCase):
    def test_nist_aes128_ecb_vector(self):
        key = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
        ciphertext = bytes.fromhex("69c4e0d86a7b0430d8cdb78070b4c55a")
        plaintext = bytes.fromhex("00112233445566778899aabbccddeeff")
        self.assertEqual(firmware.aes128_ecb_decrypt(ciphertext, key), plaintext)

    def test_prepare_plaintext_image(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "small.img")
            with open(path, "wb") as handle:
                handle.write(b"MA2x" + b"\x00" * 12 + b"G25_MainFW_1.9.1\n" + b"\x00" * 16)
            image = firmware.prepare_image(path)
            self.assertTrue(image.valid_magic)
            self.assertFalse(image.decrypted)
            self.assertEqual(image.version, "G25_MainFW_1.9.1")

    def test_encrypted_tail_roundtrip(self):
        try:
            from Crypto.Cipher import AES
        except Exception:
            self.skipTest("pycryptodome 未安装")
        prefix = b"MA2x" + b"\x00" * 60
        marker = b"G25_MainFW_1.9.1\n"
        plain_tail = marker + b"\x00" * (64 - len(marker))
        cipher = AES.new(firmware.FW_AES_KEY, AES.MODE_ECB).encrypt(plain_tail)
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "encrypted.img")
            with open(path, "wb") as handle:
                handle.write(prefix + cipher)
            image = firmware.prepare_image(path)
            self.assertTrue(image.decrypted)
            self.assertEqual(image.version, "G25_MainFW_1.9.1")


class CliParserTests(unittest.TestCase):
    def test_all_subcommands_help_exit_zero(self):
        for command in ("list", "status", "bright", "screen", "mode", "roll",
                        "eeprom", "version", "hid", "uvc", "frame", "flash",
                        "cast", "hud", "ctl"):
            with self.subTest(command=command):
                parser = build_parser()
                with redirect_stdout(io.StringIO()):
                    with self.assertRaises(SystemExit) as raised:
                        parser.parse_args([command, "--help"])
                self.assertEqual(raised.exception.code, 0)

    def test_top_level_help_exit_zero(self):
        parser = build_parser()
        with redirect_stdout(io.StringIO()):
            with self.assertRaises(SystemExit) as raised:
                parser.parse_args(["--help"])
        self.assertEqual(raised.exception.code, 0)


if __name__ == "__main__":
    unittest.main()
