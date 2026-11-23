# -*- coding: utf-8 -*-
"""导航 HUD 的无硬件自检：UDP 协议、共享单槽、HUD 渲染。

运行：
  cd linux && python -m unittest tests.test_nav -v
"""

from __future__ import annotations

import json
import socket
import time
import unittest

from glxss import hud, nav
from glxss.cli import build_parser


class NavParseTests(unittest.TestCase):
    def test_parse_full_packet(self):
        frame = nav.parse_packet(
            json.dumps({"bearing": 405, "distance": 120, "label": "  右转  ",
                        "heading": 40}).encode("utf-8"),
            now=100.0, ttl=5.0, source="192.168.2.9:1234")
        self.assertAlmostEqual(frame.bearing, 45.0)
        self.assertAlmostEqual(frame.distance, 120.0)
        self.assertEqual(frame.label, "右转")
        self.assertAlmostEqual(frame.heading, 40.0)
        self.assertAlmostEqual(frame.expires_at, 105.0)
        self.assertEqual(frame.source, "192.168.2.9:1234")
        self.assertTrue(frame.is_fresh(now=104.9))
        self.assertFalse(frame.is_fresh(now=105.1))

    def test_parse_optional_heading_and_ttl(self):
        frame = nav.parse_packet(b'{"bearing": 0, "distance": 5}')
        self.assertIsNone(frame.heading)
        self.assertEqual(frame.expires_at, 0.0)
        self.assertTrue(frame.persistent)
        self.assertTrue(frame.is_fresh(now=time.monotonic() + 10_000))

    def test_relative_bearing(self):
        frame = nav.parse_packet(
            b'{"bearing": 350, "distance": 1, "heading": 10}')
        self.assertAlmostEqual(frame.relative_bearing(), 340.0)
        frame = nav.parse_packet(b'{"bearing": 45, "distance": 1}')
        self.assertAlmostEqual(frame.relative_bearing(), 45.0)

    def test_bad_packets(self):
        cases = [
            b"not json",
            b"[1, 2, 3]",
            b'{"distance": 1}',
            b'{"bearing": "north", "distance": 1}',
            b'{"bearing": 1, "distance": "far"}',
            b'{"bearing": NaN, "distance": 1}',
        ]
        for raw in cases:
            with self.subTest(raw=raw):
                with self.assertRaises(ValueError):
                    nav.parse_packet(raw)

    def test_label_truncation_and_newlines(self):
        frame = nav.parse_packet(json.dumps(
            {"bearing": 1, "distance": 2, "label": "a\nb" + "x" * 80}
        ).encode("utf-8"))
        self.assertNotIn("\n", frame.label)
        self.assertLessEqual(len(frame.label), nav.MAX_LABEL)


class LatestNavTests(unittest.TestCase):
    def test_update_and_stale(self):
        state = nav.LatestNav(stale=0.25)
        self.assertIsNone(state.snapshot())
        state.update(45, 120, "右转", 40, ttl=0.25, source="udp")
        fresh = state.snapshot()
        self.assertIsNotNone(fresh)
        self.assertAlmostEqual(fresh.bearing, 45)
        time.sleep(0.35)
        self.assertIsNone(state.snapshot())
        # peek 仍能看到最后一帧，status 用来说明“过期了”。
        self.assertIsNotNone(state.peek())
        self.assertFalse(state.status()["fresh"])

    def test_clear_and_persistent(self):
        state = nav.LatestNav()
        state.update(90, 10, "到达", ttl=0, source="ctl")
        time.sleep(0.05)
        self.assertIsNotNone(state.snapshot())
        state.clear()
        self.assertIsNone(state.snapshot())

    def test_bad_packet_does_not_drop_last_frame(self):
        state = nav.LatestNav()
        state.update(1, 2, "直行", ttl=0)
        state.mark_bad("boom")
        self.assertIsNotNone(state.snapshot())
        self.assertEqual(state.status()["bad_packets"], 1)


class NavListenerTests(unittest.TestCase):
    def test_udp_roundtrip(self):
        state = nav.LatestNav(stale=1.0)
        listener = nav.NavListener(state, host="127.0.0.1", port=0)
        listener.start()
        try:
            self.assertTrue(listener.wait_ready(2.0))
            self.assertGreater(listener.bound_port, 0)
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                payload = json.dumps({"bearing": 90, "distance": 12.5,
                                      "label": "右转", "heading": 80})
                sock.sendto(payload.encode("utf-8"),
                            ("127.0.0.1", listener.bound_port))
            finally:
                sock.close()
            deadline = time.monotonic() + 2.0
            frame = None
            while time.monotonic() < deadline:
                frame = state.snapshot()
                if frame is not None:
                    break
                time.sleep(0.02)
            self.assertIsNotNone(frame)
            self.assertAlmostEqual(frame.bearing, 90.0)
            self.assertAlmostEqual(frame.distance, 12.5)
            self.assertEqual(frame.label, "右转")
            self.assertAlmostEqual(frame.heading, 80.0)
            self.assertEqual(listener.received, 1)
        finally:
            listener.stop()


class HudNavRenderTests(unittest.TestCase):
    READY = hud.Image is not None and hud.np is not None

    @unittest.skipUnless(READY, "需要 Pillow + numpy")
    def test_nav_source_drives_arrow_and_waiting(self):
        state = nav.LatestNav()
        source = hud.HudSource(nav_source=state)
        source.set_connected(True)
        source.set_fps(2.0)
        waiting = source.render().tobytes()

        state.update(90, 1234, "到达", heading=0, ttl=0)
        right = source.render()
        self.assertEqual(right.size, (640, 400))
        self.assertNotEqual(waiting, right.tobytes())

        state.update(90, 1234, "到达", heading=180, ttl=0)
        left = source.render()
        self.assertNotEqual(right.tobytes(), left.tobytes())

        state.clear()
        self.assertIsNone(source.snapshot().nav)
        self.assertEqual(hud._format_distance(120), "120 m")
        self.assertEqual(hud._format_distance(1234), "1.2 km")

    @unittest.skipUnless(READY, "需要 Pillow + numpy")
    def test_set_nav_with_source_is_persistent(self):
        state = nav.LatestNav(stale=0.1)
        source = hud.HudSource(nav_source=state)
        source.set_nav(45, 120, "右转", heading=40)
        self.assertIsNotNone(state.snapshot())
        time.sleep(0.2)  # 不应过期：set_nav 走 ttl=0
        self.assertIsNotNone(state.snapshot())
        self.assertIsNotNone(source.snapshot().nav)


class NavCliTests(unittest.TestCase):
    def test_ctl_nav_accepts_heading(self):
        args = build_parser().parse_args(
            ["ctl", "nav", "45", "--distance", "120",
             "--label", "右转", "--heading", "40"])
        self.assertEqual(args.action, "nav")
        self.assertEqual(args.value, "45")
        self.assertAlmostEqual(args.distance, 120.0)
        self.assertEqual(args.label, "右转")
        self.assertAlmostEqual(args.heading, 40.0)


if __name__ == "__main__":
    unittest.main()
