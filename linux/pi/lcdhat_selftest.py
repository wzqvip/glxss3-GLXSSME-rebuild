#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Waveshare 1.3inch LCD HAT 硬件自检（用真实的 lcdhat.ST7789 驱动）。

不开图形界面、不连眼镜，只验证：SPI 能写、偏移/旋转/反相是否正确、
按键与摇杆能否读到事件。参数与 `pi/lcdhat.py` 完全一致。

    python3 pi/lcdhat_selftest.py colors   # 全屏 红/绿/蓝/白/黑
    python3 pi/lcdhat_selftest.py text     # 识别页：角标 + 色条 + 文字
    python3 pi/lcdhat_selftest.py keys     # 只打印按键事件
    python3 pi/lcdhat_selftest.py all      # colors -> text -> 停留

常用调参：
    --rotation 0..3   --x-offset 0   --y-offset 80   --spi-hz 40000000
    --invert / --no-invert
"""

from __future__ import annotations

import argparse
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
for path in (HERE, ROOT):
    if path not in sys.path:
        sys.path.insert(0, path)

from lcdhat import (  # noqa: E402
    LCD_H, LCD_W, ST7789, Buttons, Gpio, GlxssError,
)

try:
    from PIL import Image, ImageDraw, ImageFont
except Exception:  # pragma: no cover
    Image = None


def _font(size: int):
    for path in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf",
    ):
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def identification(args) -> "Image.Image":
    """角标 + 色条 + 参数文字，用来肉眼确认颜色/方向/偏移。"""
    img = Image.new("RGB", (LCD_W, LCD_H), (8, 12, 20))
    d = ImageDraw.Draw(img)
    # 左上红、右上绿、左下蓝、右下白：一眼看出方向和镜像
    d.rectangle((0, 0, 24, 24), fill=(255, 0, 0))
    d.rectangle((LCD_W - 25, 0, LCD_W - 1, 24), fill=(0, 255, 0))
    d.rectangle((0, LCD_H - 25, 24, LCD_H - 1), fill=(0, 0, 255))
    d.rectangle((LCD_W - 25, LCD_H - 25, LCD_W - 1, LCD_H - 1),
                fill=(255, 255, 255))
    # 标准色条：白 黄 青 绿 品红 红 蓝 黑
    for i, color in enumerate(((255, 255, 255), (255, 255, 0), (0, 255, 255),
                               (0, 255, 0), (255, 0, 255), (255, 0, 0),
                               (0, 0, 255), (0, 0, 0))):
        y = 34 + i * 11
        d.rectangle((60, y, 178, y + 9), fill=color)

    d.text((62, 8), 'GLXSS 1.3"', font=_font(26), fill=(255, 255, 255))
    info = "rot=%d off=%d,%d inv=%s" % (
        args.rotation, args.x_offset, args.y_offset,
        "on" if args.invert else "off")
    d.text((8, 122), info, font=_font(13), fill=(120, 220, 255))
    d.text((8, 140), "SPI %.0f MHz" % (args.spi_hz / 1e6), font=_font(13),
           fill=(120, 220, 255))
    d.text((8, 164), "red=TL green=TR", font=_font(11), fill=(200, 200, 200))
    d.text((8, 178), "blue=BL white=BR", font=_font(11), fill=(200, 200, 200))
    d.text((8, 196), "TEXT UP SHOULD READ", font=_font(11), fill=(255, 210, 90))
    d.text((8, 212), "GLXSS 1.3", font=_font(20), fill=(255, 255, 255))
    return img


def run_keys(gpio: Gpio, seconds: float) -> None:
    buttons = Buttons(gpio)
    print("按键自检 %ds：按 UP/DOWN/LEFT/RIGHT/PRESS/KEY1/KEY2/KEY3" % seconds)
    idle = {name: gpio.input(pin) for name, pin in
            (("up", 6), ("down", 19), ("left", 5), ("right", 26),
             ("press", 13), ("key1", 21), ("key2", 20), ("key3", 16))}
    print("idle: " + " ".join("%s=%d" % kv for kv in idle.items()))
    start = time.monotonic()
    while time.monotonic() - start < seconds:
        for name in buttons.poll():
            print("  [%6.2fs] %s" % (time.monotonic() - start, name))
        time.sleep(0.02)
    print("按键自检结束")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="LCD HAT 硬件自检")
    ap.add_argument("mode", nargs="?", default="all",
                    choices=("colors", "text", "keys", "all"))
    ap.add_argument("--spi-bus", type=int, default=0)
    ap.add_argument("--spi-device", type=int, default=0)
    ap.add_argument("--spi-hz", type=int, default=40_000_000)
    ap.add_argument("--rotation", type=int, choices=(0, 1, 2, 3), default=0)
    ap.add_argument("--x-offset", type=int, default=0)
    ap.add_argument("--y-offset", type=int, default=80)
    ap.add_argument("--invert", dest="invert", action="store_true", default=True)
    ap.add_argument("--no-invert", dest="invert", action="store_false")
    ap.add_argument("--hold", type=float, default=25.0,
                    help="识别页停留秒数（默认 25）")
    ap.add_argument("--keys-seconds", type=float, default=20.0)
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)

    if Image is None:
        print("[错误] 自检需要 Pillow：sudo apt install python3-pil",
              file=sys.stderr)
        return 1

    gpio = None
    panel = None
    try:
        gpio = Gpio()
        if args.mode != "keys":
            panel = ST7789(
                gpio, bus=args.spi_bus, device=args.spi_device,
                speed_hz=args.spi_hz, x_offset=args.x_offset,
                y_offset=args.y_offset, rotation=args.rotation,
                invert=args.invert, verbose=args.verbose)
            print("panel: rotation=%d offsets=(%d,%d) invert=%s spi=%.1fMHz" % (
                args.rotation, panel.x_offset, panel.y_offset,
                args.invert, args.spi_hz / 1e6))

        if args.mode in ("colors", "all"):
            for name, color in (("RED", (255, 0, 0)), ("GREEN", (0, 255, 0)),
                                ("BLUE", (0, 0, 255)), ("WHITE", (255, 255, 255)),
                                ("BLACK", (0, 0, 0))):
                print("fill " + name)
                panel.fill(color)
                time.sleep(1.5)

        if args.mode in ("text", "all"):
            print("identification page")
            panel.show(identification(args))

        if args.mode == "keys":
            run_keys(gpio, args.keys_seconds)
        elif args.mode in ("text", "all"):
            print("hold %.0fs" % args.hold)
            time.sleep(args.hold)
    except GlxssError as exc:
        print("[错误] " + str(exc), file=sys.stderr)
        return 1
    finally:
        if panel is not None:
            panel.close()
        if gpio is not None:
            gpio.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
