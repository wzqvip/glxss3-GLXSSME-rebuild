# -*- mode: python ; coding: utf-8 -*-
"""glxss CLI 的 PyInstaller spec。

用法（在 linux/ 目录）：
    packaging/build.sh              # Linux/macOS，默认 onefile
    packaging/build.sh --onedir     # 目录版，启动更快
    powershell -File packaging/build.ps1            # Windows
    powershell -File packaging/build.ps1 -OneDir   # Windows 目录版

环境变量：
    GLXSS_ONEFILE=0         → 目录版（EXE + COLLECT）
    GLXSS_LIBUSB_DLL=path   → 把 libusb 动态库一起打进包（Windows/macOS 常用）

注意：PyInstaller 不能跨平台打包；Linux 包必须在 Linux 上打，Windows 包在
Windows 上打，macOS 包在 macOS 上打。
"""

import os

# SPECPATH 是 PyInstaller 注入的当前 spec 所在目录。
ROOT = os.path.abspath(os.path.join(SPECPATH, ".."))
ONEFILE = os.environ.get("GLXSS_ONEFILE", "1").strip() != "0"

hiddenimports = [
    "usb",
    "usb.backend.libusb1",
    "usb.backend.openusb",
]

binaries = []
libusb = os.environ.get("GLXSS_LIBUSB_DLL", "").strip()
if libusb and os.path.exists(libusb):
    binaries.append((libusb, "."))

# mss/spidev/树莓派 GPIO 都不是 CLI 的启动依赖；排除它们可减小体积。
excludes = [
    "mss",
    "spidev",
    "RPi",
    "lgpio",
    "gpiod",
    "tkinter",
]

a = Analysis(
    [os.path.join(SPECPATH, "entry.py")],
    pathex=[ROOT],
    binaries=binaries,
    datas=[],
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[os.path.join(SPECPATH, "rthook_libusb.py")],
    excludes=excludes,
    noarchive=False,
)
pyz = PYZ(a.pure)

if ONEFILE:
    exe = EXE(
        pyz,
        a.scripts,
        a.binaries,
        a.datas,
        [],
        name="glxss",
        debug=False,
        bootloader_ignore_signals=False,
        strip=False,
        upx=False,
        console=True,
        runtime_tmpdir=None,
    )
else:
    exe = EXE(
        pyz,
        a.scripts,
        [],
        exclude_binaries=True,
        name="glxss",
        debug=False,
        bootloader_ignore_signals=False,
        strip=False,
        upx=False,
        console=True,
    )
    coll = COLLECT(
        exe,
        a.binaries,
        a.datas,
        strip=False,
        upx=False,
        name="glxss",
    )
