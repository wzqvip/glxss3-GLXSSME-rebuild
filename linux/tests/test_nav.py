# -*- coding: utf-8 -*-
"""导航 HUD 的无硬件自检：UDP 协议、共享单槽、HUD 渲染（无罗盘）。

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
                        "heading": 40, "eta": 120, "remain": 2400,
                        "source": "maps",
                        "maneuver": "right"}).encode("utf-8"),
            now=100.0, ttl=5.0, source="192.168.2.9:1234")
        self.assertAlmostEqual(frame.bearing, 45.0)
        self.assertAlmostEqual(frame.distance, 120.0)
        self.assertEqual(frame.label, "右转")
        self.assertAlmostEqual(frame.heading, 40.0)
        self.assertAlmostEqual(frame.eta, 120.0)
        self.assertAlmostEqual(frame.remain, 2400.0)
        self.assertEqual(frame.maneuver, "right")
        # 包内 source 优先于传输层地址（HUD 底部显示 MAPS 而不是 IP）。
        self.assertEqual(frame.source, "maps")
        self.assertAlmostEqual(frame.expires_at, 105.0)
        self.assertTrue(frame.is_fresh(now=104.9))
        self.assertFalse(frame.is_fresh(now=105.1))

    def test_parse_android_maps_packet(self):
        """Android 端真实格式：bearing/heading 为 null（眼镜没有磁力计）。"""
        frame = nav.parse_packet(json.dumps({
            "bearing": None, "distance": 500, "label": "右转进入 人民路",
            "heading": None, "eta": 120, "source": "maps",
            "maneuver": "right",
        }).encode("utf-8"), now=10.0, ttl=5.0)
        self.assertIsNone(frame.bearing)
        self.assertIsNone(frame.heading)
        self.assertIsNone(frame.relative_bearing())
        self.assertEqual(frame.maneuver, "right")
        self.assertAlmostEqual(frame.distance, 500.0)
        self.assertAlmostEqual(frame.eta, 120.0)
        self.assertEqual(frame.source, "maps")
        self.assertTrue(frame.is_fresh(now=14.0))

    def test_maneuver_falls_back_to_label_keywords(self):
        cases = {
            "右转进入 解放路": "right",
            "左转进入 Main St": "left",
            "直行 300 米": "straight",
            "稍向右前方": "slight-right",
            "靠左": "slight-left",
            "进入环岛": "roundabout",
            "前方掉头": "uturn",
            "到达目的地": "arrive",
            "Turn right onto Main St": "right",
            "Turn left onto 5th Ave": "left",
            "At the roundabout, take the 2nd exit": "roundabout",
            "Make a U-turn": "uturn",
            "Continue straight": "straight",
            "You have arrived": "arrive",
            "Main St": "unknown",
        }
        for label, expected in cases.items():
            with self.subTest(label=label):
                self.assertEqual(nav.classify_maneuver(label), expected)
                frame = nav.parse_packet(json.dumps(
                    {"distance": 10, "label": label}).encode("utf-8"))
                self.assertEqual(frame.maneuver, expected)

    def test_explicit_maneuver_wins_and_aliases(self):
        frame = nav.parse_packet(json.dumps(
            {"label": "环岛", "maneuver": "arrive"}).encode("utf-8"))
        self.assertEqual(frame.maneuver, "arrive")
        for alias, expected in (("Slight Right", "slight-right"),
                                ("u-turn", "uturn"),
                                ("keep-left", "slight-left"),
                                ("", "roundabout")):
            self.assertEqual(nav.maneuver_of(alias, "进入环岛"), expected)

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
            b'{"foo": 1}',
            b'{"bearing": "north", "distance": 1}',
            b'{"bearing": 1, "distance": "far"}',
            b'{"bearing": NaN, "distance": 1}',
        ]
        for raw in cases:
            with self.subTest(raw=raw):
                with self.assertRaises(ValueError):
                    nav.parse_packet(raw)

    def test_tolerates_garbage_eta_and_remain(self):
        """ETA/剩余距离是可选字段，坏值只忽略，不该丢整包。"""
        frame = nav.parse_packet(
            b'{"distance": 1, "eta": "soon", "remain": null}')
        self.assertIsNone(frame.eta)
        self.assertIsNone(frame.remain)

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

    def test_update_without_bearing(self):
        state = nav.LatestNav()
        state.update(None, 500, "右转进入 人民路", None, eta=120,
                     remain=2400, ttl=0, source="ctl")
        frame = state.snapshot()
        self.assertIsNotNone(frame)
        self.assertIsNone(frame.bearing)
        self.assertEqual(frame.maneuver, "right")
        self.assertAlmostEqual(frame.eta, 120)
        self.assertEqual(frame.source, "ctl")

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
                payload = json.dumps({"bearing": None, "distance": 12.5,
                                      "label": "右转", "heading": None,
                                      "eta": 60, "source": "maps"})
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
            self.assertIsNone(frame.bearing)
            self.assertAlmostEqual(frame.distance, 12.5)
            self.assertEqual(frame.label, "右转")
            self.assertAlmostEqual(frame.eta, 60.0)
            self.assertEqual(frame.maneuver, "right")
            self.assertEqual(frame.source, "maps")
            self.assertEqual(listener.received, 1)
        finally:
            listener.stop()


class HudNavRenderTests(unittest.TestCase):
    READY = hud.Image is not None and hud.np is not None

    def test_compass_removed(self):
        """眼镜没有磁力计：HUD 不再有罗盘绘制/常量。"""
        self.assertFalse(hasattr(hud, "_draw_compass"))
        self.assertFalse(hasattr(hud, "_needle"))
        self.assertFalse(hasattr(hud, "COMPASS_R"))
        self.assertFalse(hasattr(hud, "COMPASS_CX"))

    def test_maneuver_mapping_and_formatting(self):
        self.assertEqual(hud.NavInfo(label="左转").maneuver_name(), "left")
        self.assertEqual(
            hud.NavInfo(label="x", maneuver="roundabout").maneuver_name(),
            "roundabout")
        self.assertEqual(hud._format_distance(120), "120 m")
        self.assertEqual(hud._format_distance(1234), "1.2 km")
        self.assertEqual(hud._format_eta(120), "2 分")
        self.assertEqual(hud._format_eta(120, cjk=False), "2 min")
        self.assertEqual(hud._format_eta(3900), "1 小时 5 分")
        self.assertEqual(hud._format_eta(3900, cjk=False), "1 h 5 min")
        self.assertEqual(hud._maneuver_word("right"), "右转")
        self.assertEqual(hud._maneuver_word("right", cjk=False), "RIGHT")

    @unittest.skipUnless(READY, "需要 Pillow + numpy")
    def test_nav_source_drives_maneuver_and_waiting(self):
        state = nav.LatestNav()
        source = hud.HudSource(nav_source=state)
        source.set_connected(True)
        source.set_fps(2.0)
        waiting = source.render().tobytes()

        state.update(None, 1234, "右转进入 Main St", None, eta=120,
                     remain=4200, ttl=0, source="maps")
        right = source.render()
        self.assertEqual(right.size, (640, 400))
        self.assertNotEqual(waiting, right.tobytes())

        # 同一个 bearing/heading，只换机动类型 → 画面必须变（不再按角度旋转）。
        state.update(None, 1234, "左转进入 Main St", None, ttl=0,
                     source="maps")
        left = source.render()
        self.assertNotEqual(right.tobytes(), left.tobytes())

        state.clear()
        self.assertIsNone(source.snapshot().nav)

    @unittest.skipUnless(READY, "需要 Pillow + numpy")
    def test_all_maneuvers_render(self):
        for maneuver in nav.MANEUVERS:
            with self.subTest(maneuver=maneuver):
                source = hud.HudSource(demo=True)
                st = source.snapshot()
                st.nav = hud.NavInfo(distance=300.0, label="指令",
                                     maneuver=maneuver, eta=180.0,
                                     remain=2400.0, source="maps")
                image = source.render(st)
                self.assertEqual(image.size, (640, 400))
                self.assertGreater(len(image.convert("RGB").getcolors(65536)),
                                   8)

    @unittest.skipUnless(READY, "需要 Pillow + numpy")
    def test_set_nav_with_source_is_persistent(self):
        state = nav.LatestNav(stale=0.1)
        source = hud.HudSource(nav_source=state)
        source.set_nav(45, 120, "右转", heading=40)
        self.assertIsNotNone(state.snapshot())
        time.sleep(0.2)  # 不应过期：set_nav 走 ttl=0
        self.assertIsNotNone(state.snapshot())
        self.assertIsNotNone(source.snapshot().nav)

    @unittest.skipUnless(READY, "需要 Pillow + numpy")
    def test_set_nav_without_bearing(self):
        state = nav.LatestNav()
        source = hud.HudSource(nav_source=state)
        source.set_nav(None, 500, "右转", eta=60, remain=3000)
        info = source.snapshot().nav
        self.assertIsNotNone(info)
        self.assertIsNone(info.bearing)
        self.assertEqual(info.maneuver_name(), "right")
        self.assertAlmostEqual(info.eta, 60)


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

    def test_ctl_nav_without_bearing(self):
        args = build_parser().parse_args(
            ["ctl", "nav", "--label", "右转", "--distance", "500",
             "--eta", "120", "--remain", "2400"])
        self.assertEqual(args.action, "nav")
        self.assertIsNone(args.value)
        self.assertAlmostEqual(args.distance, 500.0)
        self.assertAlmostEqual(args.eta, 120.0)
        self.assertAlmostEqual(args.remain, 2400.0)


if __name__ == "__main__":
    unittest.main()
