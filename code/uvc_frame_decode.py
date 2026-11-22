#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
兼容入口：把 raw UVC dump 解成 PNG。

实际逻辑在 glxss.py 的 decode-uvc 子命令里（纯标准库，YV12/I420、单帧/连续 URB/
4 B 长度前缀/裸 YUV 均支持）。保留本文件只是为了兼容
`re/liangliang/uvc_frame_decode.py` 的调用习惯。

用法：
    python code/uvc_frame_decode.py dump.bin
    python code/uvc_frame_decode.py dump.bin --order i420 -o out.png
    python code/glxss.py decode-uvc dump.bin      # 等价（推荐）
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from glxss import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main(["decode-uvc"] + sys.argv[1:]))
