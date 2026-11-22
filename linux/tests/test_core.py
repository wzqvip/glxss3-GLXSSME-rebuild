# -*- coding: utf-8 -*-
"""无硬件自检：帧头、RGBA 打包、YV12、AES、UVC header、CLI 解析。

运行：
  cd linux && python -m unittest discover -s tests -v
"""

from __future__ import annotations

import io
import os
import struct
import tempfile
import unittest
from contextlib import redirect_stdout
from types import SimpleNamespace

from glxss import capture, firmware, hid, lcd, uvc
from glxss.cli import build_parser


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
                        "eeprom", "version", "hid", "uvc", "frame", "flash", "cast"):
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
