# -*- coding: utf-8 -*-
"""手机 → Pi 的导航数据：Wi-Fi UDP JSON → 线程安全单槽。

协议（手机每秒一包，UTF-8 JSON，默认 UDP 9999）::

    {"bearing": 45, "distance": 120, "label": "右转", "heading": 40}

* ``bearing``  目标绝对方位（deg，0=北，顺时针）
* ``heading``  手机当前朝向（deg，可选；缺省时 HUD 用眼镜 HID 罗盘）
* ``distance`` 距离（米）
* ``label``    下一动作文字（UTF-8，最长 48 字符，例如 右转 / 直行 / 到达）

设计对齐 :class:`glxss.pipeline.LatestFrame`：接收线程只写**最新单槽**，HUD
渲染线程只读最新值；晚到的旧包直接覆盖，不排队、不阻塞。UDP 包带
``expires_at``，超时后 HUD 显示“等待手机…”，但 ``ctl nav`` 手动注入的帧
默认持久（直到 ``ctl nav-clear`` 或收到新包）。

没有任何第三方依赖；Pi Zero 2 W + Python 3.11 实测 CPU 可忽略。
"""

from __future__ import annotations

import json
import math
import socket
import threading
import time
from dataclasses import dataclass
from typing import Callable, Optional

# 默认监听所有网卡：手机在 Wi-Fi 上直接发给 Pi 的 192.168.x.x。
DEFAULT_BIND = "0.0.0.0"
DEFAULT_PORT = 9999
# 手机 1 Hz 发包；5 s 没收到就认为手机断/App 关了。
DEFAULT_STALE = 5.0
MAX_LABEL = 48
MAX_PACKET = 64 * 1024


# ---------------------------------------------------------------------------
# 数据模型 / 解析
# ---------------------------------------------------------------------------
@dataclass(frozen=True)
class NavFrame:
    """一帧导航数据（不可变；UDP 线程整体替换，读线程拿到的永远自洽）。"""

    bearing: float
    distance: float
    label: str = ""
    heading: Optional[float] = None
    received_at: float = 0.0
    # 0 = 永久有效（ctl 手动测试）；UDP 帧 = received_at + stale。
    expires_at: float = 0.0
    source: str = ""
    raw: str = ""

    @property
    def persistent(self) -> bool:
        return self.expires_at <= 0.0

    def is_fresh(self, now: Optional[float] = None) -> bool:
        if self.persistent:
            return True
        return (time.monotonic() if now is None else now) < self.expires_at

    def relative_bearing(self) -> float:
        """相对当前朝向的箭头角度：目标方位 - 当前 heading，归一化 0..360。

        手机只给了 bearing（没给 heading）时，按“bearing 已经是相对角”处理；
        这与手动测试模式（手机把滑块角度同时当 bearing 发送）一致。
        """
        if self.heading is None:
            return self.bearing % 360.0
        return (self.bearing - self.heading) % 360.0

    def as_dict(self) -> dict:
        return {
            "bearing": round(self.bearing, 1),
            "distance": round(self.distance, 1),
            "label": self.label,
            "heading": None if self.heading is None else round(self.heading, 1),
            "age": round(max(0.0, time.monotonic() - self.received_at), 2),
            "fresh": self.is_fresh(),
            "persistent": self.persistent,
        }


def normalize_angle(value: float) -> float:
    """角度归一化到 [0, 360)。"""
    return float(value) % 360.0


def _number(value, name: str) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError("%s 必须是数字，收到 %r" % (name, value)) from exc
    if not math.isfinite(number):
        raise ValueError("%s 必须是有限数字，收到 %r" % (name, value))
    return number


def parse_packet(data: bytes, *, now: Optional[float] = None,
                 ttl: float = 0.0, source: str = "") -> NavFrame:
    """把一包 UDP 字节解析成 :class:`NavFrame`；坏包抛 ``ValueError``。

    ``ttl<=0`` 表示永久有效（ctl 注入）；UDP 监听会传 ``ttl=stale``。
    ``label`` 只做长度截断，不校验语言（手机可以发中文）。
    """
    if not isinstance(data, (bytes, bytearray)):
        raise ValueError("UDP 数据必须是 bytes")
    if len(data) > MAX_PACKET:
        raise ValueError("UDP 包过大（%d B）" % len(data))
    try:
        text = bytes(data).decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError("UDP 包不是 UTF-8: %s" % exc) from exc
    try:
        obj = json.loads(text)
    except ValueError as exc:
        raise ValueError("UDP 包不是合法 JSON: %s" % exc) from exc
    if not isinstance(obj, dict):
        raise ValueError("UDP JSON 必须是对象，收到 %s" % type(obj).__name__)

    if "bearing" not in obj:
        raise ValueError("缺少 bearing 字段")
    bearing = normalize_angle(_number(obj.get("bearing"), "bearing"))
    distance = max(0.0, _number(obj.get("distance", 0.0), "distance"))

    label = obj.get("label", "")
    if label is None:
        label = ""
    elif not isinstance(label, str):
        label = str(label)
    label = " ".join(label.replace("\r", " ").replace("\n", " ").split())
    label = label[:MAX_LABEL]

    heading_raw = obj.get("heading")
    heading: Optional[float] = None
    if heading_raw is not None and heading_raw != "":
        heading = normalize_angle(_number(heading_raw, "heading"))

    stamp = time.monotonic() if now is None else float(now)
    expires = (stamp + float(ttl)) if ttl and ttl > 0 else 0.0
    return NavFrame(
        bearing=bearing,
        distance=distance,
        label=label,
        heading=heading,
        received_at=stamp,
        expires_at=expires,
        source=str(source or ""),
        raw=text[:200],
    )


# ---------------------------------------------------------------------------
# 共享单槽
# ---------------------------------------------------------------------------
class LatestNav:
    """线程安全的最新导航单槽（UDP 线程写，HUD / ctl / status 读）。"""

    def __init__(self, stale: float = DEFAULT_STALE):
        self.stale = max(0.2, float(stale))
        self._lock = threading.Lock()
        self._frame: Optional[NavFrame] = None
        self._count = 0
        self._bad = 0
        self._last_error = ""
        self._last_source = ""

    # -- 写 ---------------------------------------------------------------
    def update(self, bearing: float, distance: float, label: str = "",
               heading: Optional[float] = None, *, ttl: Optional[float] = None,
               source: str = "ctl") -> NavFrame:
        """构造并写入一帧；``ttl=None`` 用构造时的 stale，``ttl<=0`` 永久。"""
        now = time.monotonic()
        life = self.stale if ttl is None else float(ttl)
        label = " ".join(str(label or "").replace("\r", " ").split())[:MAX_LABEL]
        frame = NavFrame(
            bearing=normalize_angle(bearing),
            distance=max(0.0, float(distance)),
            label=label,
            heading=None if heading is None else normalize_angle(heading),
            received_at=now,
            expires_at=(now + life) if life > 0 else 0.0,
            source=str(source or "ctl"),
        )
        self.set(frame)
        return frame

    def set(self, frame: NavFrame) -> None:
        if not isinstance(frame, NavFrame):
            raise TypeError("LatestNav.set 需要 NavFrame")
        with self._lock:
            self._frame = frame
            self._count += 1
            self._last_source = frame.source

    def clear(self) -> None:
        with self._lock:
            self._frame = None
            self._last_source = ""

    def mark_bad(self, error: str) -> None:
        """记录一次坏包（不动当前有效帧，手机偶尔丢一个包不该清屏）。"""
        with self._lock:
            self._bad += 1
            self._last_error = str(error or "")[:200]

    # -- 读 ---------------------------------------------------------------
    def snapshot(self) -> Optional[NavFrame]:
        """最新**有效**帧；过期/从未收到返回 None（HUD 显示等待手机）。"""
        with self._lock:
            frame = self._frame
        if frame is None or not frame.is_fresh():
            return None
        return frame

    def peek(self) -> Optional[NavFrame]:
        """最新帧（可能已过期）；status / 调试用。"""
        with self._lock:
            return self._frame

    def age(self) -> float:
        with self._lock:
            frame = self._frame
        if frame is None:
            return float("inf")
        return max(0.0, time.monotonic() - frame.received_at)

    def status(self) -> dict:
        frame = self.peek()
        data = {
            "fresh": bool(frame is not None and frame.is_fresh()),
            "age": None if frame is None else round(self.age(), 2),
            "packets": self._count,
            "bad_packets": self._bad,
            "last_error": self._last_error,
            "source": self._last_source,
        }
        if frame is not None:
            data.update(frame.as_dict())
        return data


# ---------------------------------------------------------------------------
# UDP 监听线程
# ---------------------------------------------------------------------------
class NavListener(threading.Thread):
    """后台收 UDP JSON；绑定失败只记录 error，不杀主进程（HUD 仍可 ctl nav）。"""

    def __init__(self, state: Optional[LatestNav] = None,
                 host: str = DEFAULT_BIND, port: int = DEFAULT_PORT,
                 stale: float = DEFAULT_STALE,
                 on_frame: Optional[Callable[[NavFrame], None]] = None,
                 verbose: bool = False):
        super().__init__(name="glxss-nav-udp", daemon=True)
        self.state = state if state is not None else LatestNav(stale=stale)
        self.host = str(host or DEFAULT_BIND)
        self.port = int(port)
        self.on_frame = on_frame
        self.verbose = bool(verbose)
        self.error = ""
        self.received = 0
        self.dropped = 0
        self.last_error = ""
        self._stop_event = threading.Event()
        self._sock: Optional[socket.socket] = None
        self._bound_port = 0
        self._ready = threading.Event()
        self._last_bad_log = 0.0

    # -- 状态 -------------------------------------------------------------
    @property
    def bound_port(self) -> int:
        return self._bound_port

    @property
    def alive(self) -> bool:
        return self.is_alive() and not self._stop_event.is_set()

    def wait_ready(self, timeout: float = 2.0) -> bool:
        """等 bind 完成（测试/启动日志用）；失败也返回 False。"""
        return self._ready.wait(max(0.0, float(timeout))) and not self.error

    def status(self) -> dict:
        return {
            "alive": self.alive,
            "bind": self.host,
            "port": self._bound_port or self.port,
            "received": self.received,
            "dropped": self.dropped,
            "error": self.error,
            "last_error": self.last_error,
        }

    # -- 线程 -------------------------------------------------------------
    def run(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind((self.host, self.port))
            sock.settimeout(0.5)
        except OSError as exc:
            self.error = "绑定 %s:%d 失败：%s" % (self.host, self.port, exc)
            print("[nav] " + self.error, flush=True)
            try:
                sock.close()
            except OSError:
                pass
            self._ready.set()
            return
        self._sock = sock
        self._bound_port = int(sock.getsockname()[1])
        print("[nav] UDP 导航监听 %s:%d（JSON 1 Hz，手机 → Pi）"
              % (self.host, self._bound_port), flush=True)
        self._ready.set()
        try:
            while not self._stop_event.is_set():
                try:
                    data, addr = sock.recvfrom(MAX_PACKET)
                except socket.timeout:
                    continue
                except OSError as exc:
                    if self._stop_event.is_set():
                        break
                    self.last_error = "recvfrom 失败：%s" % exc
                    break
                now = time.monotonic()
                try:
                    frame = parse_packet(
                        data, now=now, ttl=self.state.stale,
                        source="%s:%d" % (addr[0], addr[1]))
                except ValueError as exc:
                    self.dropped += 1
                    self.last_error = str(exc)
                    self.state.mark_bad(str(exc))
                    # 坏包可能每秒都有，5 s 打一次，避免 journal 刷屏。
                    if now - self._last_bad_log >= 5.0:
                        self._last_bad_log = now
                        print("[nav] 丢弃坏包（%d）：%s"
                              % (self.dropped, self.last_error), flush=True)
                    continue
                self.state.set(frame)
                self.received += 1
                self.last_error = ""
                if self.on_frame is not None:
                    try:
                        self.on_frame(frame)
                    except Exception as exc:  # 回调不能弄死接收线程
                        print("[nav] on_frame 回调失败：%r" % (exc,), flush=True)
                if self.received <= 3 or self.verbose:
                    print("[nav] #%d bearing=%.0f distance=%.0f heading=%s "
                          "label=%r ← %s" % (
                              self.received, frame.bearing, frame.distance,
                              "--" if frame.heading is None
                              else "%.0f" % frame.heading,
                              frame.label, frame.source), flush=True)
        finally:
            try:
                sock.close()
            except OSError:
                pass
            self._sock = None

    def stop(self) -> None:
        self._stop_event.set()
        sock = self._sock
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
        if self.is_alive():
            self.join(timeout=1.0)

