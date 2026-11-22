# -*- coding: utf-8 -*-
"""Myriad 2 引导模式固件灌写（默认 dry-run）。

协议出处 docs/ch-firmware.md、docs/firmware-boot-protocol.md：
  * 设备以 03e7:2150 出现，interface 0，EP 0x01 bulk OUT；
  * 每包 1024 B，无头/无 ACK/无 ZLP，单包超时 10 s；
  * 固件最后 min(size, 512 KiB) 是 AES-128-ECB，key=b"b5Q8JBFRYYIRawl3"；
  * 写完后设备自行重启并重枚举为 2e09:0030。

安全：不提供写 EEPROM；flash 默认只分析，必须显式 --yes 才会写。
"""

from __future__ import annotations

import hashlib
import os
import time
from dataclasses import dataclass
from typing import Callable, Optional

from . import usb
from .errors import GlxssError
from .util import hexs

BOOT_CHUNK = 1024
BOOT_TIMEOUT_MS = 10000
FW_TAIL_MAX = 0x80000
FW_AES_KEY = b"b5Q8JBFRYYIRawl3"
FW_MAGIC = b"MA2x"
FW_VERSION_MARK = b"G25_MainFW_"

try:  # 有 pycryptodome 就用它加速
    from Crypto.Cipher import AES as _CRYPTO_AES  # type: ignore
except Exception:  # pragma: no cover - 环境相关
    _CRYPTO_AES = None


# ---------------------------------------------------------------------------
# 纯 Python AES-128 解密（无第三方依赖时的兜底）
# ---------------------------------------------------------------------------
_AES_SBOX = bytes([
    0x63, 0x7C, 0x77, 0x7B, 0xF2, 0x6B, 0x6F, 0xC5, 0x30, 0x01, 0x67, 0x2B, 0xFE, 0xD7, 0xAB, 0x76,
    0xCA, 0x82, 0xC9, 0x7D, 0xFA, 0x59, 0x47, 0xF0, 0xAD, 0xD4, 0xA2, 0xAF, 0x9C, 0xA4, 0x72, 0xC0,
    0xB7, 0xFD, 0x93, 0x26, 0x36, 0x3F, 0xF7, 0xCC, 0x34, 0xA5, 0xE5, 0xF1, 0x71, 0xD8, 0x31, 0x15,
    0x04, 0xC7, 0x23, 0xC3, 0x18, 0x96, 0x05, 0x9A, 0x07, 0x12, 0x80, 0xE2, 0xEB, 0x27, 0xB2, 0x75,
    0x09, 0x83, 0x2C, 0x1A, 0x1B, 0x6E, 0x5A, 0xA0, 0x52, 0x3B, 0xD6, 0xB3, 0x29, 0xE3, 0x2F, 0x84,
    0x53, 0xD1, 0x00, 0xED, 0x20, 0xFC, 0xB1, 0x5B, 0x6A, 0xCB, 0xBE, 0x39, 0x4A, 0x4C, 0x58, 0xCF,
    0xD0, 0xEF, 0xAA, 0xFB, 0x43, 0x4D, 0x33, 0x85, 0x45, 0xF9, 0x02, 0x7F, 0x50, 0x3C, 0x9F, 0xA8,
    0x51, 0xA3, 0x40, 0x8F, 0x92, 0x9D, 0x38, 0xF5, 0xBC, 0xB6, 0xDA, 0x21, 0x10, 0xFF, 0xF3, 0xD2,
    0xCD, 0x0C, 0x13, 0xEC, 0x5F, 0x97, 0x44, 0x17, 0xC4, 0xA7, 0x7E, 0x3D, 0x64, 0x5D, 0x19, 0x73,
    0x60, 0x81, 0x4F, 0xDC, 0x22, 0x2A, 0x90, 0x88, 0x46, 0xEE, 0xB8, 0x14, 0xDE, 0x5E, 0x0B, 0xDB,
    0xE0, 0x32, 0x3A, 0x0A, 0x49, 0x06, 0x24, 0x5C, 0xC2, 0xD3, 0xAC, 0x62, 0x91, 0x95, 0xE4, 0x79,
    0xE7, 0xC8, 0x37, 0x6D, 0x8D, 0xD5, 0x4E, 0xA9, 0x6C, 0x56, 0xF4, 0xEA, 0x65, 0x7A, 0xAE, 0x08,
    0xBA, 0x78, 0x25, 0x2E, 0x1C, 0xA6, 0xB4, 0xC6, 0xE8, 0xDD, 0x74, 0x1F, 0x4B, 0xBD, 0x8B, 0x8A,
    0x70, 0x3E, 0xB5, 0x66, 0x48, 0x03, 0xF6, 0x0E, 0x61, 0x35, 0x57, 0xB9, 0x86, 0xC1, 0x1D, 0x9E,
    0xE1, 0xF8, 0x98, 0x11, 0x69, 0xD9, 0x8E, 0x94, 0x9B, 0x1E, 0x87, 0xE9, 0xCE, 0x55, 0x28, 0xDF,
    0x8C, 0xA1, 0x89, 0x0D, 0xBF, 0xE6, 0x42, 0x68, 0x41, 0x99, 0x2D, 0x0F, 0xB0, 0x54, 0xBB, 0x16,
])
_AES_INV_SBOX = bytes([0] * 256)
_inv = bytearray(256)
for _i, _v in enumerate(_AES_SBOX):
    _inv[_v] = _i
_AES_INV_SBOX = bytes(_inv)


def _gmul(a: int, b: int) -> int:
    product = 0
    for _ in range(8):
        if b & 1:
            product ^= a
        high = a & 0x80
        a = (a << 1) & 0xFF
        if high:
            a ^= 0x1B
        b >>= 1
    return product


_MUL9 = bytes(_gmul(i, 9) for i in range(256))
_MUL11 = bytes(_gmul(i, 11) for i in range(256))
_MUL13 = bytes(_gmul(i, 13) for i in range(256))
_MUL14 = bytes(_gmul(i, 14) for i in range(256))


def _expand_key(key: bytes):
    if len(key) != 16:
        raise GlxssError("AES-128 密钥必须是 16 字节")
    words = list(key)
    rcon = 1
    for i in range(4, 44):
        temp = words[(i - 1) * 4:i * 4]
        if i % 4 == 0:
            temp = temp[1:] + temp[:1]
            temp = [_AES_SBOX[b] for b in temp]
            temp[0] ^= rcon
            rcon = ((rcon << 1) ^ 0x1B) & 0xFF if rcon & 0x80 else (rcon << 1) & 0xFF
        words.extend(words[(i - 4) * 4 + j] ^ temp[j] for j in range(4))
    rounds = [words[i * 4:(i + 1) * 4] for i in range(44)]
    return [bytes(rounds[4 * r] + rounds[4 * r + 1] +
                  rounds[4 * r + 2] + rounds[4 * r + 3]) for r in range(11)]


def _add_round_key(state, round_key):
    return [state[i] ^ round_key[i] for i in range(16)]


def _inv_shift_rows(state):
    out = state[:]
    for row in range(1, 4):
        for col in range(4):
            out[row + 4 * col] = state[row + 4 * ((col - row) % 4)]
    return out


def _inv_mix_columns(state):
    out = state[:]
    for col in range(4):
        a0, a1, a2, a3 = state[4 * col:4 * col + 4]
        out[4 * col] = _MUL14[a0] ^ _MUL11[a1] ^ _MUL13[a2] ^ _MUL9[a3]
        out[4 * col + 1] = _MUL9[a0] ^ _MUL14[a1] ^ _MUL11[a2] ^ _MUL13[a3]
        out[4 * col + 2] = _MUL13[a0] ^ _MUL9[a1] ^ _MUL14[a2] ^ _MUL11[a3]
        out[4 * col + 3] = _MUL11[a0] ^ _MUL13[a1] ^ _MUL9[a2] ^ _MUL14[a3]
    return out


def _decrypt_block(block: bytes, round_keys) -> bytes:
    state = _add_round_key(list(block), round_keys[10])
    for round_index in range(9, 0, -1):
        state = _inv_shift_rows(state)
        state = [_AES_INV_SBOX[b] for b in state]
        state = _add_round_key(state, round_keys[round_index])
        state = _inv_mix_columns(state)
    state = _inv_shift_rows(state)
    state = [_AES_INV_SBOX[b] for b in state]
    state = _add_round_key(state, round_keys[0])
    return bytes(state)


def aes128_ecb_decrypt(data: bytes, key: bytes = FW_AES_KEY) -> bytes:
    """AES-128-ECB 解密；长度必须是 16 的整数倍。"""
    if len(data) % 16:
        raise GlxssError("AES 解密长度必须是 16 的整数倍（当前 %d 字节）" % len(data))
    if _CRYPTO_AES is not None and not os.environ.get("GLXSS_PURE_AES"):
        try:
            return _CRYPTO_AES.new(key, _CRYPTO_AES.MODE_ECB).decrypt(data)
        except Exception:
            pass
    round_keys = _expand_key(key)
    return b"".join(_decrypt_block(data[i:i + 16], round_keys)
                    for i in range(0, len(data), 16))


# ---------------------------------------------------------------------------
# 镜像分析
# ---------------------------------------------------------------------------
def find_fw_version(data: bytes):
    index = data.find(FW_VERSION_MARK)
    if index < 0:
        return None, -1
    end = index
    while end < len(data) and end < index + 48 and 0x20 <= data[end] <= 0x7E:
        end += 1
    return data[index:end].decode("ascii", "replace").strip(), index


@dataclass
class FirmwareImage:
    path: str
    raw: bytes
    payload: bytes
    raw_sha256: str
    payload_sha256: str
    decrypted: bool
    magic: bytes
    version: Optional[str]
    version_offset: int
    tail_start: int
    tail_len: int
    warning: Optional[str] = None

    @property
    def size(self) -> int:
        return len(self.payload)

    @property
    def valid_magic(self) -> bool:
        return self.magic == FW_MAGIC

    def format(self) -> str:
        lines = [
            "  文件      : %s" % os.path.abspath(self.path),
            "  原始大小  : %d B (0x%x)" % (len(self.raw), len(self.raw)),
            "  SHA256    : %s" % self.raw_sha256,
            "  magic     : %s (%s)%s" % (
                self.magic.decode("latin1", "replace"), hexs(self.magic),
                "" if self.valid_magic else "  [警告] 不是 MA2x"),
            "  解密      : %s" % (
                "AES-128-ECB 解密尾部 0x%x..0x%x（%d B）" % (
                    self.tail_start, self.tail_start + self.tail_len, self.tail_len)
                if self.decrypted else "跳过/镜像已是明文"),
            "  版本串    : %s" % (
                "%r @0x%x" % (self.version, self.version_offset)
                if self.version else "（未找到 G25_MainFW_）"),
            "  将发送    : %d B (0x%x)" % (self.size, self.size),
            "  SHA256(发送): %s" % self.payload_sha256,
            "  分块      : %d B/包，共 %d 包，单包超时由 --timeout 指定" % (
                BOOT_CHUNK, (self.size + BOOT_CHUNK - 1) // BOOT_CHUNK),
        ]
        if self.warning:
            lines.append("  [警告]    : " + self.warning)
        return "\n".join(lines)


def prepare_image(path: str, no_decrypt: bool = False, force: bool = False) -> FirmwareImage:
    """读取并（必要时）解密镜像；不访问 USB。"""
    try:
        with open(path, "rb") as handle:
            raw = handle.read()
    except OSError as exc:
        raise GlxssError("无法读取固件 %s: %s" % (path, exc)) from exc
    if not raw:
        raise GlxssError("固件文件为空: %s" % path)

    magic = raw[:4]
    version, version_offset = find_fw_version(raw)
    payload = raw
    decrypted = False
    warning = None
    tail_start = len(raw) - min(len(raw), FW_TAIL_MAX)
    tail_len = len(raw) - tail_start

    if no_decrypt:
        warning = "--no-decrypt：直接发送原文件"
    elif version is not None:
        # 明文区已有版本串，按现有 CLI 的处理视为已解密，避免二次解密。
        warning = "明文区已含版本串，按已解密镜像处理"
    else:
        tail = raw[tail_start:]
        if len(tail) % 16:
            raise GlxssError(
                "尾部长度 %d B 不是 16 的整数倍，无法按 AES-ECB 解密。\n"
                "  若这是已解密镜像，请加 --no-decrypt。" % len(tail))
        plain = aes128_ecb_decrypt(tail, FW_AES_KEY)
        candidate = raw[:tail_start] + plain
        candidate_version, candidate_offset = find_fw_version(candidate)
        payload = candidate
        decrypted = True
        version = candidate_version
        version_offset = candidate_offset
        if version is None:
            warning = "AES 解密后仍未找到 G25_MainFW_；请确认镜像是 G25 固件"
            if not force:
                warning += "（真写需要 --force）"

    if magic != FW_MAGIC and not force:
        warning = ((warning + "；") if warning else "") + "magic 不是 MA2x，真写需要 --force"

    return FirmwareImage(
        path=path,
        raw=raw,
        payload=payload,
        raw_sha256=hashlib.sha256(raw).hexdigest(),
        payload_sha256=hashlib.sha256(payload).hexdigest(),
        decrypted=decrypted,
        magic=magic,
        version=version,
        version_offset=version_offset,
        tail_start=tail_start,
        tail_len=tail_len,
        warning=warning,
    )


# ---------------------------------------------------------------------------
# 灌写
# ---------------------------------------------------------------------------
@dataclass
class FlashResult:
    dry_run: bool
    sent: int
    total: int
    elapsed: float
    gone: bool = False

    @property
    def rate_mbps(self) -> float:
        return (self.sent / max(1e-6, self.elapsed)) / 1e6

    @property
    def complete(self) -> bool:
        return self.sent >= self.total


Progress = Optional[Callable[[int, int, float], None]]


def _default_progress(sent: int, total: int, rate: float) -> None:
    pct = sent * 100 // max(1, total)
    print("  进度: %3d%%  %d/%d B  %.2f MB/s" % (pct, sent, total, rate))


def flash(dev, image: FirmwareImage, dry_run: bool = True,
          timeout: int = BOOT_TIMEOUT_MS, force: bool = False,
          progress: Progress = _default_progress) -> FlashResult:
    """把镜像写到 03e7:2150。dry_run=True 时只返回计划，不碰设备。"""
    if dry_run:
        return FlashResult(dry_run=True, sent=0, total=image.size, elapsed=0.0)
    if not image.valid_magic and not force:
        raise GlxssError("镜像 magic 不是 MA2x，拒绝写入；确认文件后加 --force。")
    if image.version is None and not force:
        raise GlxssError("镜像里没有找到 G25_MainFW_ 版本串，拒绝写入；确认后加 --force。")

    intf, ep = usb.pick_bulk_out(dev)
    if progress:
        progress(0, image.size, 0.0)
    payload = image.payload
    total = len(payload)
    sent = 0
    started = time.monotonic()
    gone = False
    last_report = started
    next_pct = 0

    while sent < total:
        chunk = payload[sent:sent + BOOT_CHUNK]
        offset = 0
        while offset < len(chunk):
            try:
                written = dev.write(ep.bEndpointAddress, chunk[offset:], timeout)
            except Exception as exc:
                if usb.is_no_device(exc):
                    gone = True
                    break
                raise GlxssError(usb.usb_error_hint(
                    exc, "刷写 bulk OUT（已写 %d/%d B）" % (sent + offset, total))) from exc
            if not written:
                raise GlxssError("bulk OUT 返回 0 字节（offset %d/%d）" % (sent + offset, total))
            offset += int(written)
        if gone:
            break
        sent += len(chunk)
        now = time.monotonic()
        pct = sent * 100 // total
        if progress and (pct >= next_pct or now - last_report >= 1.0 or sent >= total):
            progress(sent, total, (sent / max(1e-6, now - started)) / 1e6)
            next_pct = (pct // 10 + 1) * 10
            last_report = now

    return FlashResult(
        dry_run=False, sent=sent, total=total,
        elapsed=max(1e-6, time.monotonic() - started), gone=gone)


def wait_for_normal(timeout: float = 8.0, interval: float = 0.5) -> bool:
    """等待设备重枚举为 2e09:0030；仅用于刷写后提示。"""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if usb.find_normal(required=False) is not None:
                return True
        except GlxssError:
            pass
        time.sleep(interval)
    return False
