#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成一张 640×400 RGBA 测试 PNG，并验证 RGBA 打包正确。

用法：
  cd linux
  python tools/make_test_png.py --out test-pattern.png
  python -m glxss frame image test-pattern.png --dry-run

这个脚本用 Pillow/numpy 构造 1280×800 源图，再走 capture.map_to_rgba()，
最后把 1,024,000 B 的 RGBA payload 重新读回比较，确保没有通道/行序错位。
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from glxss import capture  # noqa: E402


def make_source(width: int = 1280, height: int = 800) -> Image.Image:
    image = Image.new("RGB", (width, height), (18, 20, 24))
    draw = ImageDraw.Draw(image)
    colors = [
        (255, 255, 255), (255, 255, 0), (0, 255, 255), (0, 255, 0),
        (255, 0, 255), (255, 0, 0), (0, 0, 255), (0, 0, 0),
    ]
    band = width // len(colors)
    for index, color in enumerate(colors):
        x0 = index * band
        draw.rectangle((x0, 0, x0 + band - 1, height // 2), fill=color)
    draw.rectangle((0, height // 2, width, height), fill=(35, 40, 50))
    draw.ellipse((width // 2 - 180, height // 2 + 30, width // 2 + 180, height // 2 + 330),
                 outline=(255, 200, 0), width=12)
    draw.line((40, height - 60, width - 40, height - 60), fill=(0, 220, 160), width=8)
    draw.text((40, height // 2 + 16), "GLXSS 640x400 RGBA TEST", fill=(255, 255, 255))
    return image


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="生成/验证 GLXSS 640×400 RGBA 测试 PNG")
    parser.add_argument("--out", default="test-pattern.png", help="输出 PNG")
    parser.add_argument("--fit", choices=("fit", "cover", "stretch"), default="stretch")
    args = parser.parse_args(argv)

    source = make_source()
    rgba = capture.map_to_rgba(np.asarray(source), mode=args.fit)
    payload = capture.rgba_bytes(rgba)
    if len(payload) != 640 * 400 * 4:
        print("RGBA payload 长度错误: %d" % len(payload), file=sys.stderr)
        return 1
    alpha = rgba[:, :, 3]
    if not bool((alpha == 255).all()):
        print("alpha 不全是 255", file=sys.stderr)
        return 1

    capture.save_rgba_png(args.out, rgba)
    with Image.open(args.out) as reloaded:
        if reloaded.mode != "RGBA" or reloaded.size != (640, 400):
            print("PNG 回读属性错误: mode=%s size=%s" % (reloaded.mode, reloaded.size),
                  file=sys.stderr)
            return 1
        roundtrip = reloaded.tobytes()
    if roundtrip != rgba.tobytes():
        print("PNG 回读像素与 RGBA 数组不一致", file=sys.stderr)
        return 1

    print("源图       : %dx%d RGB" % source.size)
    print("映射       : %s → 640×400 RGBA" % args.fit)
    print("payload    : %d B (640*400*4)" % len(payload))
    print("alpha      : 全部 255")
    print("PNG 回读   : mode=RGBA size=640x400，逐字节一致")
    print("PNG 文件   : %s" % os.path.abspath(args.out))
    with open(args.out, "rb") as handle:
        png_sha = hashlib.sha256(handle.read()).hexdigest()
    print("PNG SHA256 : %s" % png_sha)
    print("payload    : %s" % hashlib.sha256(payload).hexdigest())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
