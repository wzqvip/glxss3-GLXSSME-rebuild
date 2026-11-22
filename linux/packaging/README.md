# `glxss` 打包（PyInstaller）

把 `glxss` 命令行打成一个**不依赖目标机器 Python 环境**的可执行文件：
Linux / macOS 用 `build.sh`，Windows 用 `build.ps1`。PyInstaller **不能跨平台**，
Windows 包必须在 Windows 上打，Linux 包在 Linux 上打，macOS 包在 macOS 上打。

产物默认写到 `linux/dist/`，不要提交到 git（`linux/.gitignore` 已忽略）。

---

## 1. 先决条件

| 平台 | 依赖 |
|---|---|
| 全部 | Python 3.9+（建议 3.10–3.13；过新的 Python 可能还没被 PyInstaller 支持） |
| 全部 | `python -m pip install pyinstaller` |
| Linux | `libusb-1.0-0`（运行时系统库；一般不需要打进包） |
| Windows | `libusb-1.0.dll`（见 §3）；Zadig 绑定 WinUSB |
| macOS | `brew install libusb`；USB 接口被系统驱动占用时需测试 |

`build.sh --install-deps` / `build.ps1 -InstallDeps` 会执行：

```bash
python -m pip install -e ".[dev]" pyinstaller
```

这一步会装 Pillow（HUD/PNG）、mss（屏幕采集，可选）等。只投 HUD 的话，
运行机器必须有 Pillow + numpy + pyusb；mss 只在 `--source screen` 时需要。

---

## 2. 构建

### Linux / macOS

```bash
cd linux

# onefile：单个 dist/glxss，分发最省事，但每次启动要解压
packaging/build.sh

# onedir：dist/glxss/ 目录，启动更快，适合 Pi/常驻
packaging/build.sh --onedir

# 指定解释器 / 先装依赖
PYTHON=python3.12 packaging/build.sh --install-deps
```

### Windows

```powershell
cd linux

# onefile：dist\glxss.exe
powershell -ExecutionPolicy Bypass -File packaging\build.ps1

# onedir：dist\glxss\glxss.exe
powershell -ExecutionPolicy Bypass -File packaging\build.ps1 -OneDir

# 把 libusb DLL 一起打进 onefile（推荐）
powershell -ExecutionPolicy Bypass -File packaging\build.ps1 `
  -LibusbDll C:\tools\libusb-1.0.dll

# 先装依赖
powershell -ExecutionPolicy Bypass -File packaging\build.ps1 -InstallDeps

# PATH 里的 python 不是装 PyInstaller 的那个时，显式指定
powershell -ExecutionPolicy Bypass -File packaging\build.ps1 `
  -Python C:\Python312\python.exe
```

构建脚本最后会自动跑一次 `glxss --help` 作为冒烟测试。

---

## 3. Windows：libusb-1.0.dll

pyusb 只是 ctypes 包装，**不带 libusb 本体**。没有 DLL 时会报：

```text
No backend available
```

两种处理方式：

1. **随包携带（推荐）**：从 [libusb releases](https://github.com/libusb/libusb/releases)
   下载 Windows 二进制，解压出 `libusb-1.0.dll`，构建时传
   `-LibusbDll C:\path\libusb-1.0.dll`。`glxss.spec` 会把它放进包内，
   `rthook_libusb.py` 会把 `sys._MEIPASS` 加进 DLL 搜索路径。
2. **系统安装**：把 `libusb-1.0.dll` 放到 `C:\Windows\System32`（管理员）
   或 exe 同目录 / PATH。注意 onefile 的 exe 同目录不一定在 DLL 搜索路径里，
   所以更推荐第 1 种。

驱动：用 [Zadig](https://zadig.akeo.ie/) 把眼镜的**目标接口**绑到 WinUSB；
不要把整个复合设备绑成 WinUSB，否则摄像头/HID/音频会一起失效。

---

## 4. macOS：brew libusb

```bash
brew install libusb
```

pyusb 通过 `ctypes.util.find_library("usb-1.0")` 查找；Homebrew 的
`/opt/homebrew/lib`（Apple Silicon）不一定在默认搜索路径里。运行前可以：

```bash
export DYLD_LIBRARY_PATH="/opt/homebrew/lib:$DYLD_LIBRARY_PATH"
```

打包时也可以让 spec 把 dylib 收进去：

```bash
GLXSS_LIBUSB_DLL=/opt/homebrew/lib/libusb-1.0.dylib packaging/build.sh
```

onefile 在 macOS 上仍可能受 Gatekeeper/签名影响；没有做签名/公证。
如果要分发给别人，建议 onedir + 简单启动脚本，或自行 codesign。

---

## 5. onefile vs onedir

| 项 | `--onefile`（默认） | `--onedir`（`--onedir` / `-OneDir`） |
|---|---|---|
| 产物 | 单个 `glxss` / `glxss.exe` | 一个目录（exe + `_internal/`） |
| 体积 | 约 40–90 MB（numpy + Pillow 占大头） | 总体相近，文件数多 |
| 启动 | 每次解压到临时目录，慢 **1–3 秒**（Pi Zero 上更明显） | 直接启动，快 |
| 升级 | 替换一个文件 | 替换整个目录 |
| 临时目录 | 需要可写 `%TEMP%` / `/tmp` | 不需要解压 |
| 杀软 | 单文件更容易被误报 | 相对好一点 |

树莓派/长期运行建议 `build.sh --onedir`；临时拷贝到 Windows 机器验证用
onefile 更方便。

> 某些 Windows 安全策略/沙箱/杀软会禁止 onefile 在 `%TEMP%` 下创建
> `_MEIxxxxx` 解压目录，运行时报：
>
> ```text
> [PYI-xxxxx:ERROR] Could not create temporary directory!
> ```
>
> 这不是程序本身的错误，改用 `build.ps1 -OneDir` 即可（目录版不解压）。
> 本仓库在受限 Windows 环境下用 `-OneDir` 验证过；onefile 构建能成功，
> 但运行取决于临时目录权限。

---

## 6. 验证

```bash
# Linux/macOS onefile
./dist/glxss --help
./dist/glxss list
./dist/glxss hud --dry-run --save-png /tmp/hud.png

# Windows
.\dist\glxss.exe --help
.\dist\glxss.exe list
.\dist\glxss.exe hud --dry-run --save-png $env:TEMP\hud.png
```

打包版和源码版功能一致；`glxss hud --listen` 仍需要能访问眼镜 USB
（Linux udev / Windows WinUSB / macOS libusb）。

---

## 7. 已知限制

* PyInstaller 不会把 `spidev`/`RPi.GPIO` 打进 CLI 包；LCD HAT 控制器
  `pi/lcdhat.py` 仍建议在树莓派上直接用 venv 运行（它依赖系统 GPIO/SPI）。
* `--source screen` 依赖 `mss`；某些 Linux 桌面/安全策略下 PyInstaller
  包可能拿不到 X11 权限，优先用测试图案或 HUD。
* 固件灌写使用 pyusb bulk 传输；Windows 上必须正确绑定 WinUSB。
* 没有做代码签名/公证/安装器；只产出可执行文件。
