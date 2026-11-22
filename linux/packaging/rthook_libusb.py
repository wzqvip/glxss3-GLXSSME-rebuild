# -*- coding: utf-8 -*-
"""PyInstaller 运行时钩子：让 pyusb/ctypes 找到随包携带的 libusb。

Windows onefile 会把打包进去的 libusb-1.0.dll 解压到 sys._MEIPASS；Python 3.8+
默认不会把该目录加入 DLL 搜索路径，所以这里显式 add_dll_directory。
Linux/macOS 上通常使用系统库，本钩子为 no-op。
"""

import os
import sys


def _add_dll_dir() -> None:
    if os.name != "nt" or not hasattr(os, "add_dll_directory"):
        return
    base = getattr(sys, "_MEIPASS", None) or os.path.dirname(sys.executable)
    try:
        os.add_dll_directory(base)
    except OSError:
        pass


_add_dll_dir()
