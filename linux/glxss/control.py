# -*- coding: utf-8 -*-
"""主进程（`glxss hud --listen`）与 LCD HAT 之间的 JSON/Unix socket 控制通道。

为什么用 Unix socket：
  * 只在本机通信，不需要 TCP 端口/防火墙；
  * 一行一个 JSON，调试时可以直接 `socat`/`nc -U`；
  * 服务端只做“把请求转给主循环里的回调”，不引入 Web 框架。

协议：
    请求：{"cmd": "status"} / {"cmd": "bright", "value": 180} / ...
    响应：{"ok": true, "status": {...}}；失败 {"ok": false, "error": "..."}

默认路径：
    1. 环境变量 `GLXSS_SOCK`；
    2. /run/glxssctl.sock（对 /run 有写权限时，例如 systemd 的 RuntimeDirectory）；
    3. $XDG_RUNTIME_DIR/glxssctl.sock；
    4. /tmp/glxssctl-<uid>.sock（普通用户直接运行时）。
"""

from __future__ import annotations

import json
import os
import socket
import stat
import tempfile
import threading
from typing import Callable, Dict, Optional

from .errors import GlxssError

DEFAULT_SOCKET = "/run/glxssctl.sock"
MAX_REQUEST = 64 * 1024
Handler = Callable[[Dict[str, object]], Dict[str, object]]


def _fallback_socket() -> str:
    if os.name != "nt" and hasattr(os, "getuid"):
        return os.path.join(tempfile.gettempdir(), "glxssctl-%d.sock" % os.getuid())
    return os.path.join(tempfile.gettempdir(), "glxssctl.sock")


def default_socket_path() -> str:
    """选一个当前用户大概率可写的 socket 路径。"""
    env = os.environ.get("GLXSS_SOCK", "").strip()
    if env:
        return env
    if os.name != "nt":
        for candidate in (DEFAULT_SOCKET,
                          os.path.join(os.environ.get("XDG_RUNTIME_DIR", ""),
                                       "glxssctl.sock")):
            parent = os.path.dirname(candidate)
            if parent and os.path.isdir(parent) and os.access(parent, os.W_OK):
                return candidate
    return _fallback_socket()


def _encode(payload: Dict[str, object]) -> bytes:
    return (json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8")


class ControlServer:
    """在后台线程里 accept Unix socket；每个请求交给 handler 同步处理。"""

    def __init__(self, path: str, handler: Handler):
        if not hasattr(socket, "AF_UNIX"):
            raise GlxssError("当前平台不支持 Unix socket，HAT 请用 --no-spawn 本机模式")
        if not path:
            raise GlxssError("控制 socket 路径为空")
        self.path = os.path.abspath(path)
        self.handler = handler
        self._sock: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()

    def start(self) -> None:
        parent = os.path.dirname(self.path)
        if parent:
            try:
                os.makedirs(parent, exist_ok=True)
            except OSError as exc:
                raise GlxssError("无法创建 socket 目录 %s: %s" % (parent, exc)) from exc
        self._prepare_path()
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            sock.bind(self.path)
            os.chmod(self.path, 0o600)
            sock.listen(8)
            sock.settimeout(0.5)
        except OSError as exc:
            sock.close()
            raise GlxssError("无法监听控制 socket %s: %s" % (self.path, exc)) from exc
        self._sock = sock
        self._thread = threading.Thread(target=self._accept_loop,
                                        name="glxss-control", daemon=True)
        self._thread.start()

    def _prepare_path(self) -> None:
        if not os.path.exists(self.path):
            return
        try:
            mode = os.stat(self.path).st_mode
        except OSError:
            return
        if not stat.S_ISSOCK(mode):
            raise GlxssError("%s 已存在且不是 socket；换一个 --socket 路径" % self.path)
        # 已有服务在监听：不要误删；连接失败才当作残留 socket 清理。
        probe = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            probe.settimeout(0.25)
            probe.connect(self.path)
        except OSError:
            try:
                os.unlink(self.path)
            except OSError:
                pass
        else:
            raise GlxssError("已有进程在监听 %s" % self.path)
        finally:
            probe.close()

    def _accept_loop(self) -> None:
        while not self._stop.is_set():
            try:
                conn, _ = self._sock.accept()  # type: ignore[union-attr]
            except socket.timeout:
                continue
            except OSError:
                break
            threading.Thread(target=self._serve, args=(conn,),
                             name="glxss-control-conn", daemon=True).start()

    def _serve(self, conn: socket.socket) -> None:
        with conn:
            conn.settimeout(2.0)
            data = b""
            try:
                while b"\n" not in data and len(data) < MAX_REQUEST:
                    chunk = conn.recv(4096)
                    if not chunk:
                        break
                    data += chunk
            except OSError:
                return
            line = data.split(b"\n", 1)[0].strip()
            if not line:
                return
            try:
                request = json.loads(line.decode("utf-8"))
                if not isinstance(request, dict):
                    raise ValueError("请求必须是 JSON 对象")
                response = self.handler(request)
                if not isinstance(response, dict):
                    raise ValueError("handler 必须返回 dict")
            except Exception as exc:  # 不把异常抛进 accept 线程
                response = {"ok": False, "error": str(exc)}
            try:
                conn.sendall(_encode(response))
            except OSError:
                pass

    def stop(self) -> None:
        self._stop.set()
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None
        thread = self._thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=1.5)
        self._thread = None
        try:
            if os.path.exists(self.path) and stat.S_ISSOCK(os.stat(self.path).st_mode):
                os.unlink(self.path)
        except OSError:
            pass

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, exc_type, exc, tb):
        self.stop()
        return False


class ControlClient:
    """HAT/CLI 侧的短连接客户端：一次请求一个连接，简单、无状态。"""

    def __init__(self, path: Optional[str] = None, timeout: float = 1.5):
        self.path = os.path.abspath(path or default_socket_path())
        self.timeout = max(0.1, float(timeout))

    def request(self, cmd: Optional[str] = None,
                **payload: object) -> Dict[str, object]:
        if not hasattr(socket, "AF_UNIX"):
            raise GlxssError("当前平台不支持 Unix socket 控制通道")
        body = dict(payload)
        if cmd is not None:
            body["cmd"] = cmd
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            sock.settimeout(self.timeout)
            sock.connect(self.path)
            sock.sendall(_encode(body))
            data = b""
            while b"\n" not in data and len(data) < MAX_REQUEST:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                data += chunk
        except FileNotFoundError as exc:
            raise GlxssError(
                "控制服务未启动：%s\n"
                "  先在另一个终端运行 `glxss hud --listen`，"
                "或让 HAT 自动拉起（默认行为）。" % self.path) from exc
        except OSError as exc:
            raise GlxssError("控制 socket 通信失败（%s）: %s" % (self.path, exc)) from exc
        finally:
            sock.close()
        line = data.split(b"\n", 1)[0].strip()
        if not line:
            raise GlxssError("控制服务没有返回数据（%s）" % self.path)
        try:
            response = json.loads(line.decode("utf-8"))
        except ValueError as exc:
            raise GlxssError("控制服务返回了非法 JSON: %r" % line[:80]) from exc
        if not isinstance(response, dict):
            raise GlxssError("控制服务返回的不是 JSON 对象")
        return response

    def status(self) -> Dict[str, object]:
        response = self.request("status")
        status = response.get("status")
        return status if isinstance(status, dict) else response

    def available(self) -> bool:
        try:
            self.request("ping")
            return True
        except GlxssError:
            return False
