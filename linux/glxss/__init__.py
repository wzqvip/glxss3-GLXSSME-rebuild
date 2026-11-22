# -*- coding: utf-8 -*-
"""GLXSS3/G25 跨平台 Python 核心库。

平台：Linux / Windows / macOS，Python 3.9+。
硬件访问统一走 pyusb；没有安装 pyusb 时，帮助、测试图案和 dry-run 仍然可用。
"""

__version__ = "0.1.0"

from .errors import DeviceNotFound, GlxssError, PermissionDenied, ProtocolError

__all__ = [
    "__version__",
    "GlxssError",
    "DeviceNotFound",
    "PermissionDenied",
    "ProtocolError",
]
