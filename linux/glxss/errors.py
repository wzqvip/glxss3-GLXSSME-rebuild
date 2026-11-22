# -*- coding: utf-8 -*-
"""异常类型：CLI 只捕获这些异常，避免把 pyusb 的 traceback 直接甩给用户。"""


class GlxssError(RuntimeError):
    """可读的 GLXSS 错误。"""


class DeviceNotFound(GlxssError):
    """没有找到目标 USB 设备。"""


class PermissionDenied(GlxssError):
    """没有权限打开/claim 设备（Windows 未绑 WinUSB，Linux udev 未配置）。"""


class ProtocolError(GlxssError):
    """设备返回的数据不符合已逆向的协议。"""
