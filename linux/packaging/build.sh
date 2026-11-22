#!/usr/bin/env bash
# glxss CLI 的 Linux/macOS PyInstaller 构建脚本。
#
#   packaging/build.sh                # onefile（默认，单文件但启动慢一点）
#   packaging/build.sh --onedir       # 目录版（启动快，分发多个文件）
#   packaging/build.sh --install-deps # 先 pip install -e ".[dev]" pyinstaller
#   PYTHON=python3.12 packaging/build.sh
#
# 输出：linux/dist/glxss（onefile）或 linux/dist/glxss/（onedir）

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
PYTHON="${PYTHON:-python3}"
ONEFILE=1
CLEAN=1
INSTALL_DEPS=0

usage() {
  sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --onedir) ONEFILE=0 ;;
    --no-clean) CLEAN=0 ;;
    --install-deps) INSTALL_DEPS=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

cd "$ROOT"

if ! "$PYTHON" -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 9) else 1)"; then
  echo "[错误] 需要 Python 3.9+（当前: $("$PYTHON" --version 2>&1)）" >&2
  exit 2
fi

if [ "$INSTALL_DEPS" = "1" ]; then
  echo "== 安装构建依赖 =="
  "$PYTHON" -m pip install -U pip
  "$PYTHON" -m pip install -e ".[dev]" pyinstaller
fi

if ! "$PYTHON" -c "import PyInstaller" >/dev/null 2>&1; then
  echo "[错误] 未安装 PyInstaller。先运行：" >&2
  echo "  $PYTHON -m pip install pyinstaller" >&2
  echo "或：packaging/build.sh --install-deps" >&2
  exit 2
fi

export GLXSS_ONEFILE="$ONEFILE"
if [ -n "${GLXSS_LIBUSB_DLL:-}" ] && [ ! -f "$GLXSS_LIBUSB_DLL" ]; then
  echo "[警告] GLXSS_LIBUSB_DLL 不存在: $GLXSS_LIBUSB_DLL" >&2
fi

echo "== PyInstaller =="
echo "  python : $("$PYTHON" --version 2>&1)"
echo "  mode   : $([ "$ONEFILE" = "1" ] && echo onefile || echo onedir)"
echo "  spec   : $HERE/glxss.spec"
echo "  output : $ROOT/dist"

ARGS=(--noconfirm --distpath "$ROOT/dist" --workpath "$ROOT/build/pyinstaller")
if [ "$CLEAN" = "1" ]; then
  ARGS+=(--clean)
fi
ARGS+=("$HERE/glxss.spec")
"$PYTHON" -m PyInstaller "${ARGS[@]}"

if [ "$ONEFILE" = "1" ]; then
  BIN="$ROOT/dist/glxss"
else
  BIN="$ROOT/dist/glxss/glxss"
fi

if [ ! -e "$BIN" ]; then
  echo "[错误] 构建结束但找不到 $BIN" >&2
  exit 1
fi

echo
echo "== 产物 =="
if [ "$ONEFILE" = "1" ]; then
  du -h "$BIN"
else
  du -sh "$ROOT/dist/glxss"
fi
echo "冒烟测试: $BIN --help"
"$BIN" --help >/dev/null

if [ "$(uname -s)" = "Darwin" ]; then
  echo
  echo "[提示] macOS 上 pyusb 通过 ctypes 查找 libusb；如果运行时报找不到库："
  echo "  brew install libusb"
  echo "  export DYLD_LIBRARY_PATH=\"$(brew --prefix 2>/dev/null || echo /opt/homebrew)/lib:\$DYLD_LIBRARY_PATH\""
  echo "  或构建时传 GLXSS_LIBUSB_DLL=/opt/homebrew/lib/libusb-1.0.dylib"
fi

echo
echo "完成：$BIN"
