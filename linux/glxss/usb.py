# -*- coding: utf-8 -*-
"""USB 设备发现、打开、claim、control / bulk 传输。

这一层只做“搬运”，不解释 LCD/HID/UVC 协议；三套协议分别在 lcd.py / uvc.py /
hid.py 里。pyusb 是可选导入：只跑 `--help`、`frame --dry-run` 时不需要它。

平台要点：
  * Linux  ：libusb-1.0 + udev 规则；内核 uvcvideo/hid-generic 可能占接口，
              可用 detach=True 临时摘掉（退出时尽量 attach 回去）。
  * Windows：需要 libusb-1.0.dll，并用 Zadig 把目标接口绑到 WinUSB。
  * macOS  ：brew install libusb；系统 IOHID/UVC 可能占接口，优先只操作厂商接口。
"""

from __future__ import annotations

import sys
import threading
import time
from typing import Iterator, List, Optional, Sequence

from .errors import DeviceNotFound, GlxssError

try:  # pragma: no cover - 取决于运行环境
    import usb.core  # type: ignore
    import usb.util  # type: ignore

    PYUSB_AVAILABLE = True
    _PYUSB_IMPORT_ERROR: Optional[BaseException] = None
except Exception as _exc:  # ImportError / libusb 后端加载失败
    usb = None  # type: ignore
    PYUSB_AVAILABLE = False
    _PYUSB_IMPORT_ERROR = _exc

# 正常模式 / Myriad ROM 引导模式（docs/firmware-boot-protocol.md §2）
VID_NORMAL, PID_NORMAL = 0x2E09, 0x0030
VID_BOOT, PID_BOOT = 0x03E7, 0x2150

# 接口号（docs/ch-display.md §3、docs/ch-hid.md §2）
IFACE_LCD = 3
IFACE_HID = 2
IFACE_BOOT = 0

UVC_IFACE_CLASS = 0x0E
UVC_VC_SUBCLASS = 0x01
UVC_VS_SUBCLASS = 0x02

KNOWN_DEVICES = {
    (VID_NORMAL, PID_NORMAL): "GLXSS G25 正常模式（LCD/HID/UVC）",
    (VID_BOOT, PID_BOOT): "GLXSS G25 引导模式（Myriad ROM boot）",
}


# ---------------------------------------------------------------------------
# 依赖与错误提示
# ---------------------------------------------------------------------------
def require_pyusb() -> None:
    """没有 pyusb 时抛出带安装说明的异常。"""
    if PYUSB_AVAILABLE:
        return
    raise GlxssError(
        "没有可用的 pyusb（%s）。\n"
        "  python -m pip install pyusb\n"
        "  Linux  : sudo apt install libusb-1.0-0\n"
        "  Windows: 安装 libusb-1.0.dll，并用 Zadig 把眼镜接口绑定到 WinUSB\n"
        "  macOS  : brew install libusb" % (_PYUSB_IMPORT_ERROR or "未知原因",)
    )


def _error_text(exc: BaseException) -> str:
    return str(exc)


def error_errno(exc: BaseException) -> Optional[int]:
    for attr in ("errno", "backend_error_code"):
        value = getattr(exc, attr, None)
        if value is None:
            continue
        try:
            return int(value)
        except (TypeError, ValueError):
            pass
    return None


def is_timeout(exc: BaseException) -> bool:
    text = _error_text(exc).lower()
    return error_errno(exc) in (-7, 110) or "timeout" in text or "timed out" in text


def is_no_device(exc: BaseException) -> bool:
    text = _error_text(exc).lower()
    return (
        error_errno(exc) in (-4, 19)
        or "no such device" in text
        or "device disconnected" in text
        or ("no device" in text and "backend" not in text)
    )


def is_permission_error(exc: BaseException) -> bool:
    text = _error_text(exc).lower()
    return error_errno(exc) in (-13, 13) or "access" in text or "permission" in text


def usb_error_hint(exc: BaseException, doing: str = "") -> str:
    """把 libusb 的英文 errno 转成中文排障提示。"""
    hints: List[str] = []
    if is_timeout(exc):
        hints.append(
            "超时：设备没在时限内响应。关闭厂商 App/其他占用 USB 的程序；"
            "Linux 可加 --detach，投屏时可调大 --timeout。"
        )
    elif is_no_device(exc):
        hints.append("设备已断开/重枚举：拔插后重试；刷写时这通常意味着设备开始重启。")
    elif is_permission_error(exc):
        hints.append(
            "权限不足：Linux 装 udev 规则（linux/udev/99-glxss.rules）并把用户加入相应组；"
            "Windows 用 Zadig 绑定 WinUSB；macOS 检查 libusb 是否安装。"
        )
    elif error_errno(exc) in (-16, 16) or "busy" in _error_text(exc).lower() or "claimed" in _error_text(exc).lower():
        hints.append(
            "接口忙：退出厂商 App；Linux 可加 --detach 摘掉 uvcvideo/hid-generic；"
            "同一进程里不要用两个 pyusb 句柄重复 claim 同一接口。"
        )
    elif "not supported" in _error_text(exc).lower() or "not implemented" in _error_text(exc).lower():
        hints.append(
            "当前后端不支持该操作：Windows 需要 WinUSB 驱动；"
            "Linux 可能需要 detach 内核驱动。"
        )
    elif "no backend" in _error_text(exc).lower() or "libusb" in _error_text(exc).lower():
        hints.append(
            "找不到 libusb 后端：Linux 装 libusb-1.0-0，Windows 把 libusb-1.0.dll 放到 PATH/System32，"
            "macOS 执行 brew install libusb。"
        )
    base = ("%s失败: %s" % (doing, exc)) if doing else str(exc)
    return base + (("\n提示：" + " ".join(hints)) if hints else "")


def platform_note() -> str:
    """返回当前平台的准备提示，供 list/status 使用。"""
    if sys.platform.startswith("linux"):
        return "Linux：libusb-1.0 + udev 权限；如接口被占用可加 --detach。"
    if sys.platform == "win32":
        return "Windows：需要 libusb-1.0.dll，并用 Zadig 将目标接口绑定到 WinUSB。"
    if sys.platform == "darwin":
        return "macOS：brew install libusb；若接口被系统驱动占用可尝试 --detach。"
    return "未知平台：需要 libusb 后端和 pyusb。"


# ---------------------------------------------------------------------------
# 枚举与描述符
# ---------------------------------------------------------------------------
def _safe_attr(dev, name: str, default=None):
    try:
        return getattr(dev, name)
    except Exception:
        return default


def safe_string(dev, attr: str) -> str:
    try:
        value = getattr(dev, attr)
        return value if isinstance(value, str) else ""
    except Exception:
        return ""


def device_label(dev) -> str:
    vid = int(_safe_attr(dev, "idVendor", 0) or 0)
    pid = int(_safe_attr(dev, "idProduct", 0) or 0)
    tag = KNOWN_DEVICES.get((vid, pid), "")
    return "%04x:%04x bus=%s addr=%s%s" % (
        vid,
        pid,
        _safe_attr(dev, "bus", "?"),
        _safe_attr(dev, "address", "?"),
        ("  [" + tag + "]") if tag else "",
    )


def is_glxss(dev) -> bool:
    return (int(_safe_attr(dev, "idVendor", 0) or 0),
            int(_safe_attr(dev, "idProduct", 0) or 0)) in KNOWN_DEVICES


def find_device(vid: int, pid: int, required: bool = True):
    require_pyusb()
    try:
        dev = usb.core.find(idVendor=vid, idProduct=pid)  # type: ignore[union-attr]
    except Exception as exc:
        raise GlxssError(usb_error_hint(exc, "查找 USB 设备")) from exc
    if dev is None and required:
        raise DeviceNotFound(
            "未找到 %04x:%04x。\n"
            "  * 正常模式应为 2e09:0030；引导模式应为 03e7:2150。\n"
            "  * 检查线材/供电/设备模式，先运行 `glxss list` 确认。\n"
            "  * %s" % (vid, pid, platform_note())
        )
    return dev


def find_normal(required: bool = True):
    return find_device(VID_NORMAL, PID_NORMAL, required=required)


def find_boot(required: bool = True):
    return find_device(VID_BOOT, PID_BOOT, required=required)


def iter_devices() -> List[object]:
    """枚举本机所有 USB 设备，按 bus/addr 排序。"""
    require_pyusb()
    try:
        devices = list(usb.core.find(find_all=True))  # type: ignore[union-attr]
    except Exception as exc:
        raise GlxssError(usb_error_hint(exc, "枚举 USB")) from exc
    devices.sort(key=lambda d: (int(_safe_attr(d, "bus", 0) or 0),
                                int(_safe_attr(d, "address", 0) or 0)))
    return devices


def iter_configs(dev) -> List[object]:
    try:
        return list(dev)
    except Exception as exc:
        raise GlxssError(usb_error_hint(exc, "读取配置描述符")) from exc


def iter_interfaces(dev) -> Iterator[object]:
    for cfg in iter_configs(dev):
        for intf in cfg:
            yield intf


def find_interface(dev, number: int, alt: Optional[int] = None):
    for intf in iter_interfaces(dev):
        if intf.bInterfaceNumber == number and (alt is None or intf.bAlternateSetting == alt):
            return intf
    return None


def interface_label(intf) -> str:
    # pyusb 的 Interface 对象没有 bConfigurationValue（只有 Configuration 有），
    # 老代码在 -v 下会抛 AttributeError 并误报 claim 失败。
    try:
        config = getattr(intf, "configuration", None)
        config_value = getattr(config, "bConfigurationValue", None)
    except Exception:
        config_value = None
    config_text = ("cfg=%d " % config_value) if config_value is not None else ""
    return "%sif=%d alt=%d class=0x%02x sub=0x%02x proto=0x%02x" % (
        config_text,
        intf.bInterfaceNumber,
        intf.bAlternateSetting,
        intf.bInterfaceClass,
        intf.bInterfaceSubClass,
        intf.bInterfaceProtocol,
    )


def endpoint_label(ep) -> str:
    direction = "IN" if ep.bEndpointAddress & 0x80 else "OUT"
    etype = {0: "control", 1: "iso", 2: "bulk", 3: "interrupt"}.get(
        ep.bmAttributes & 0x03, "?")
    return "ep=0x%02x %-3s %-9s mps=%d" % (
        ep.bEndpointAddress, direction, etype, ep.wMaxPacketSize)


def describe_device(dev) -> str:
    """返回多行设备/接口/端点描述。"""
    lines = [device_label(dev)]
    manufacturer = safe_string(dev, "manufacturer")
    product = safe_string(dev, "product")
    serial = safe_string(dev, "serial_number")
    for name, value in (("厂商", manufacturer), ("产品", product), ("序列号", serial)):
        if value:
            lines.append("  %s: %s" % (name, value))
    try:
        for cfg in iter_configs(dev):
            lines.append("  配置 %d:" % cfg.bConfigurationValue)
            for intf in cfg:
                lines.append("    " + interface_label(intf))
                for ep in intf:
                    lines.append("      " + endpoint_label(ep))
    except GlxssError as exc:
        lines.append("  [读取描述符失败] " + str(exc))
    return "\n".join(lines)


def ensure_configuration(dev, verbose: bool = False) -> None:
    """确保设备已有 active configuration；已配置时绝不重置。"""
    try:
        dev.get_active_configuration()
        return
    except Exception as exc:
        if verbose:
            print("  get_active_configuration: %s" % exc)
    try:
        dev.set_configuration()
        if verbose:
            print("  已 set_configuration")
    except Exception as exc:
        if verbose:
            print("  set_configuration 失败（继续）: %s" % exc)


def pick_bulk_out(dev):
    """选 LCD bulk OUT；评分与 Android 版一致：厂商接口 > EP1 > if3。"""
    candidates = []
    for cfg in iter_configs(dev):
        for intf in cfg:
            for ep in intf:
                if (ep.bmAttributes & 0x03) != 0x02 or (ep.bEndpointAddress & 0x80):
                    continue
                score = 0
                if intf.bInterfaceClass == 0xFF:
                    score += 100
                if ep.bEndpointAddress == 0x01:
                    score += 50
                if intf.bInterfaceNumber == IFACE_LCD:
                    score += 20
                candidates.append((score, intf, ep))
    if not candidates:
        raise GlxssError("设备没有 bulk OUT 端点（描述符不符合 GLXSS 预期）。")
    candidates.sort(key=lambda item: -item[0])
    _, intf, ep = candidates[0]
    return intf, ep


def pick_uvc(dev):
    """返回 (VC 接口或 None, VS 接口, bulk IN 端点)。"""
    vcs = []
    vss = []
    for intf in iter_interfaces(dev):
        if intf.bInterfaceClass != UVC_IFACE_CLASS:
            continue
        if intf.bInterfaceSubClass == UVC_VC_SUBCLASS:
            vcs.append(intf)
        elif intf.bInterfaceSubClass == UVC_VS_SUBCLASS:
            for ep in intf:
                if (ep.bmAttributes & 0x03) == 0x02 and (ep.bEndpointAddress & 0x80):
                    vss.append((intf, ep))
                    break
    if not vss:
        raise GlxssError("未找到 UVC VideoStreaming 接口（class 0x0e/subclass 0x02 + bulk IN）。")
    vss.sort(key=lambda item: (item[0].bAlternateSetting != 0, item[0].bInterfaceNumber))
    vs, ep = vss[0]
    vc = vcs[0] if vcs else None
    return vc, vs, ep


# ---------------------------------------------------------------------------
# 全进程 USB 串行闸门
# ---------------------------------------------------------------------------
class UsbGate:
    """控制面 + LCD bulk OUT 的串行闸门（对应 Android 版 UsbGate）。

    背景：投屏每帧是一次 1,024,016 B 的 bulk OUT，而 HID 轮询（300/100 ms）、
    亮度/模式控制、UVC probe/commit 会同时在其它线程发出。这些请求插进
    1 MB 帧中间最容易触发固件异常，所以：

      * 所有 control transfer、claim/set_interface、LCD bulk OUT 都走闸门；
      * UVC 的 bulk IN 读流**不走**闸门（长驻读，原厂也是 LCD+UVC 并发）。

    使用 RLock 允许同一线程嵌套（例如 monitor 打开时 claim + negotiate）。
    """

    def __init__(self, verbose: bool = False):
        self._lock = threading.RLock()
        self.verbose = verbose
        self.last_owner = ""

    def __enter__(self):
        self._lock.acquire()
        return self

    def __exit__(self, exc_type, exc, tb):
        self._lock.release()
        return False

    def acquire(self, owner: str = "") -> None:
        self._lock.acquire()
        self.last_owner = owner or self.last_owner

    def release(self) -> None:
        self._lock.release()


# ---------------------------------------------------------------------------
# 打开 / claim / 释放
# ---------------------------------------------------------------------------
class DeviceSession:
    """context manager：claim 接口，退出时 release，必要时 attach 内核驱动。"""

    def __init__(self, dev, detach: bool = False, verbose: bool = False):
        self.dev = dev
        self.detach = detach
        self.verbose = verbose
        self.claimed = []
        self.detached = []
        self._configured = False
        self._closed = False

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()
        return False

    def _config(self) -> None:
        if not self._configured:
            ensure_configuration(self.dev, self.verbose)
            self._configured = True

    def claim(self, number: int, alt: Optional[int] = None, label: str = "") -> bool:
        self._config()
        intf = find_interface(self.dev, number, alt)
        if intf is None:
            if self.verbose:
                print("  找不到 interface %d%s，跳过 claim" %
                      (number, (" alt=%d" % alt) if alt is not None else ""))
            return False
        return self.claim_interface(intf, label=label)

    def claim_interface(self, intf, label: str = "") -> bool:
        self._config()
        number = intf.bInterfaceNumber
        name = label or ("interface %d" % number)
        if self.detach and number not in self.detached:
            try:
                self.dev.detach_kernel_driver(number)
                self.detached.append(number)
                if self.verbose:
                    print("  已 detach 内核驱动: %s" % name)
            except NotImplementedError:
                if self.verbose:
                    print("  当前平台不支持 detach_kernel_driver（Windows 请用 Zadig 绑定 WinUSB）")
            except Exception as exc:
                if self.verbose:
                    print("  detach 内核驱动 %s: %s（可能本来就没有驱动）" % (name, exc))
        try:
            usb.util.claim_interface(self.dev, intf)  # type: ignore[union-attr]
            self.claimed.append(intf)
            if self.verbose:
                print("  claim %s (%s) 成功" % (name, interface_label(intf)))
            return True
        except Exception as exc:
            if self.verbose:
                print("  claim %s 失败: %s\n"
                      "  控制请求仍会尝试；若失败请关闭厂商 App、Linux 加 --detach、"
                      "Windows 用 Zadig。" % (name, exc))
            return False

    def release_interface(self, intf) -> None:
        """单独释放一个已 claim 的接口（monitor 退出时停 UVC 流用）。"""
        try:
            usb.util.release_interface(self.dev, intf)  # type: ignore[union-attr]
        except Exception:
            pass
        try:
            self.claimed.remove(intf)
        except ValueError:
            pass

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        for intf in reversed(self.claimed):
            try:
                usb.util.release_interface(self.dev, intf)  # type: ignore[union-attr]
            except Exception:
                pass
        self.claimed = []
        for number in self.detached:
            try:
                self.dev.attach_kernel_driver(number)
            except Exception:
                pass
        self.detached = []
        # find 出来的设备句柄由 pyusb 管理；显式 dispose 让下一条命令能干净重开。
        try:
            usb.util.dispose_resources(self.dev)  # type: ignore[union-attr]
        except Exception:
            pass


# ---------------------------------------------------------------------------
# 传输封装
# ---------------------------------------------------------------------------
def ctrl_in(dev, request: int, value: int, index: int, length: int,
            timeout: int = 2000, request_type: int = 0xA1,
            quiet: bool = True) -> bytes:
    """class IN control transfer。默认 bmRequestType=0xA1（Device-to-Host|Class|Interface）。"""
    if not quiet:
        print("  CTRL IN  bm=0x%02x bReq=0x%02x wValue=0x%04x wIndex=%d len=%d" % (
            request_type, request, value, index, length))
    try:
        data = dev.ctrl_transfer(request_type, request, value, index, length, timeout=timeout)
    except Exception as exc:
        raise GlxssError(usb_error_hint(exc, "控制读 0x%02x/0x%02x" % (request_type, request))) from exc
    return bytes(data)


def ctrl_out(dev, request: int, value: int, index: int, data: bytes,
             timeout: int = 2000, request_type: int = 0x21,
             quiet: bool = True) -> int:
    """class OUT control transfer。默认 bmRequestType=0x21（Host-to-Device|Class|Interface）。"""
    if not quiet:
        print("  CTRL OUT bm=0x%02x bReq=0x%02x wValue=0x%04x wIndex=%d len=%d" % (
            request_type, request, value, index, len(data)))
    try:
        return int(dev.ctrl_transfer(request_type, request, value, index, data, timeout=timeout))
    except Exception as exc:
        raise GlxssError(usb_error_hint(exc, "控制写 0x%02x/0x%02x" % (request_type, request))) from exc


def bulk_write_all(dev, ep_addr: int, data: bytes, timeout: int = 5000,
                   attempts: int = 3) -> int:
    """写完整块；短写只补剩余字节，保持设备端帧边界。"""
    offset = 0
    total = len(data)
    failures = 0
    while offset < total:
        try:
            written = dev.write(ep_addr, data[offset:], timeout)
        except Exception as exc:
            if is_no_device(exc):
                raise GlxssError("设备已断开（bulk OUT 0x%02x，已写 %d/%d B）" % (
                    ep_addr, offset, total)) from exc
            failures += 1
            if failures >= attempts:
                raise GlxssError(usb_error_hint(
                    exc, "bulk OUT 0x%02x（已写 %d/%d B）" % (ep_addr, offset, total))) from exc
            time.sleep(0.1)
            continue
        if not written:
            failures += 1
            if failures >= attempts:
                raise GlxssError("bulk OUT 0x%02x 连续返回 0 字节（offset %d/%d）" % (
                    ep_addr, offset, total))
            time.sleep(0.1)
            continue
        offset += int(written)
        failures = 0
    return offset


def bulk_read(dev, ep_addr: int, size: int, timeout: int = 5000) -> bytes:
    try:
        return bytes(dev.read(ep_addr, size, timeout=timeout))
    except Exception as exc:
        raise GlxssError(usb_error_hint(exc, "bulk IN 0x%02x" % ep_addr)) from exc


# ---------------------------------------------------------------------------
# alt setting / 端点复位
# ---------------------------------------------------------------------------
USB_REQ_SET_INTERFACE = 0x0B
USB_REQ_CLEAR_FEATURE = 0x01
USB_FEATURE_ENDPOINT_HALT = 0x00


def set_alt(dev, intf, alt: Optional[int] = None, timeout: int = 2000,
            verbose: bool = False) -> bool:
    """切换接口 alt setting，等价于 Android 的 `UsbDeviceConnection.setInterface`。

    本设备 VS 只有一个 alt=0，但**仍然要发**：SET_INTERFACE 会让设备复位端点
    数据翻转/HALT 状态。Android 每次打开都发（alt=0 也发、返回 false 也继续），
    Pi 侧漏掉它就会出现「commit 成功但 bulk IN 一个字节都不来」。

    注意 pyusb 1.2 **没有** `Device.set_interface`（老代码调它只会抛
    AttributeError 被吞掉）；正确入口是
    `Device.set_interface_altsetting(interface, alternate_setting)`
    → libusb_set_interface_alt_setting → usbfs SETINTERFACE。
    再不行退回标准控制请求 bmRequestType=0x01 / bRequest=0x0B。
    """
    number = int(getattr(intf, "bInterfaceNumber", intf))
    if alt is None:
        alt = int(getattr(intf, "bAlternateSetting", 0) or 0)
    try:
        dev.set_interface_altsetting(number, int(alt))
        if verbose:
            print("  SET_INTERFACE if%d alt=%d ok" % (number, int(alt)))
        return True
    except AttributeError:
        pass
    except Exception as exc:
        if verbose:
            print("  SET_INTERFACE if%d alt=%d 失败: %s（改试控制请求）" % (
                number, int(alt), exc))
    try:
        dev.ctrl_transfer(0x01, USB_REQ_SET_INTERFACE, int(alt), number, 0,
                          timeout=timeout)
        if verbose:
            print("  SET_INTERFACE(ctrl) if%d alt=%d ok" % (number, int(alt)))
        return True
    except Exception as exc:
        if verbose:
            print("  SET_INTERFACE(ctrl) if%d alt=%d 失败: %s（继续）" % (
                number, int(alt), exc))
        return False


def clear_halt(dev, ep_addr: int, timeout: int = 1000, verbose: bool = False) -> bool:
    """CLEAR_FEATURE(ENDPOINT_HALT)，wValue=0 / wIndex=端点地址。

    上一次被取消的 bulk IN 结束时设备可能把 0x81 留在 HALT 状态，下一次
    commit 之后一个字节都不来（Android 在 stop() 里同样清一次）。
    """
    try:
        dev.clear_halt(ep_addr)
        if verbose:
            print("  CLEAR_FEATURE(HALT) ep=0x%02x ok" % ep_addr)
        return True
    except AttributeError:
        pass
    except Exception as exc:
        if verbose:
            print("  CLEAR_FEATURE(HALT) ep=0x%02x: %s（改试控制请求）" % (
                ep_addr, exc))
    try:
        dev.ctrl_transfer(0x02, USB_REQ_CLEAR_FEATURE, USB_FEATURE_ENDPOINT_HALT,
                          ep_addr, 0, timeout=timeout)
        if verbose:
            print("  CLEAR_FEATURE(HALT,ctrl) ep=0x%02x ok" % ep_addr)
        return True
    except Exception as exc:
        if verbose:
            print("  CLEAR_FEATURE(HALT,ctrl) ep=0x%02x 失败: %s" % (ep_addr, exc))
        return False


def open_normal(detach: bool = False, verbose: bool = False,
                claim: Sequence[int] = (IFACE_LCD,)):
    """便捷函数：找正常模式设备并 claim 指定接口，返回 (dev, session)。"""
    dev = find_normal()
    session = DeviceSession(dev, detach=detach, verbose=verbose)
    for number in claim:
        session.claim(number, label="if%d" % number)
    return dev, session
