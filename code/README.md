# code/ —— GLXSS G25 PC 端调试工具

`glxss.py` 是一个**单文件命令行工具**，把仓库已经逆向清楚的协议集中到 PC 上：
LCD 投屏帧、亮度/开关/模式/roll、状态、EEPROM 产品信息、HID 传感器、版本、
UVC probe/commit 只读探测、Myriad 引导刷写，以及 App raw UVC dump → PNG。

> 协议出处：`docs/ch-display.md`、`docs/ch-hid.md`、`docs/ch-uvc.md`、
> `docs/firmware-boot-protocol.md`、`docs/ch-firmware.md`。
> 本工具只实现**已经验证/有文档依据**的请求；不实现写 EEPROM（`0x21/0x06`，危险）。

## 1. 文件

| 文件 | 说明 |
|---|---|
| [`glxss.py`](glxss.py) | 本工具（唯一入口） |
| [`uvc_frame_decode.py`](uvc_frame_decode.py) | 兼容入口，等价于 `glxss.py decode-uvc` |
| [`poc_send_frame.py`](poc_send_frame.py) | 早期只发帧的 PoC，保留作对照；新用法请用 `glxss.py frame` |
| [`tools/elf_disasm.py`](tools/elf_disasm.py) | 固件 ELF 反汇编辅助脚本，与本工具无关 |

## 2. 安装与权限

### 2.1 Python 依赖

```bash
python -m pip install pyusb
```

- 只有**硬件命令**需要 pyusb；`--help`、`decode-uvc`、`flash` 的 dry-run 不需要。
- Windows 还需要 `libusb-1.0.dll`（可放到 `C:\Windows\System32` 或 PATH），
  并让眼镜接口绑定到 **WinUSB**（Zadig）——否则 pyusb 打不开设备。
- Linux 需要 `libusb-1.0-0`；如果 `claim_interface` 被 `uvcvideo`/`hid-generic` 占住，
  加 `--detach`（工具会临时 detach，退出时尽量 attach 回去）。

### 2.2 Linux udev（推荐）

```udev
# /etc/udev/rules.d/99-glxss.rules
SUBSYSTEM=="usb", ATTR{idVendor}=="2e09", ATTR{idProduct}=="0030", MODE="0666"
SUBSYSTEM=="usb", ATTR{idVendor}=="03e7", ATTR{idProduct}=="2150", MODE="0666"
```

```bash
sudo udevadm control --reload-rules && sudo udevadm trigger
```

Windows 上如果同一副眼镜被厂商 App 打开着，先退出厂商 App；必要时用 Zadig 重新绑定接口。

## 3. 快速开始

```bash
python code/glxss.py list
python code/glxss.py status
python code/glxss.py bright 180
python code/glxss.py screen on
python code/glxss.py mode 2
python code/glxss.py frame bars --fps 10 --seconds 5
python code/glxss.py version
python code/glxss.py hid --enable-sensors
```

每个子命令都有中文 `--help`，例如：

```bash
python code/glxss.py frame --help
python code/glxss.py flash --help
```

## 4. 子命令

### 4.1 `list`

列出本机 USB 设备，用 `<<< >>>` 标出：

- `2e09:0030`：正常模式（LCD/HID/UVC）
- `03e7:2150`：引导模式（Myriad ROM boot）

并打印每个配置的接口号、alt、class/subclass/protocol、端点地址/方向/类型/包长。
`--brief` 只打摘要（GLXSS 设备仍打接口/端点）。

```bash
python code/glxss.py list
python code/glxss.py list --brief
```

### 4.2 `status`

```bash
python code/glxss.py status
```

发 `0xA1/0x04 wValue=0 wIndex=3 wLength=8`，解析：

| 字节 | 字段 |
|---|---|
| 0–1 | width（LE u16） |
| 2–3 | height（LE u16） |
| 4 | bopen（1=屏开） |
| 5 | luminance（当前亮度） |
| 6–7 | roll（LE u16） |

同时打印原始 hex 与 `byte7`（UNKNOWN / roll 高字节）。
**注意：状态里没有固件版本**，版本走 `version`。

### 4.3 `bright` / `screen` / `mode` / `roll`

四条控制命令，参数都放在 `wValue`，`wIndex=3`，`wLength=1`（IN 方向 1 B 哑读回 ack）：

| 命令 | 请求 | 取值 | 说明 |
|---|---|---|---|
| `bright <0-255>` | `0xA1/0x01` | 0–255 | 实测有效 100–254，越界会警告但照发 |
| `screen on\|off` | `0xA1/0x03` | 1/0 | 屏被关后只发帧不会亮 |
| `mode <n>` | `0xA1/0x05` | 0–2 | 1=关/logo/待机，2=投屏开；0 未验证 |
| `roll <n>` | `0xA1/0x02` | 0–65535 | 与状态里的 roll 字段对应 |

```bash
python code/glxss.py bright 180
python code/glxss.py screen on
python code/glxss.py mode 2
python code/glxss.py roll 10
python code/glxss.py screen off --no-verify
```

默认写完后回读一次状态（`--no-verify` 关闭）。`frame` 默认会自动先发
`0x03=1` + `0x05=2`，避免“帧写进去但屏不亮”的常见坑。

### 4.4 `eeprom`

```bash
python code/glxss.py eeprom
python code/glxss.py eeprom --raw
```

发 `0xA1/0x07 wValue=0x0100 wIndex=3 wLength=264`，按
[`lcd/ProductInfo.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/lcd/ProductInfo.kt)
解析 8 B 设备头 + 256 B 产品信息：

| 偏移（256 B 内） | 字段 |
|---|---|
| 0 | platformID（hex） |
| 1–7 | productID（ASCII） |
| 8–9 | firmID（hex） |
| 10–25 | bsnID |
| 26–41 | psnID |
| 42–43 | bomID |
| 44–51 | ispID |
| 52–59 | firmwareID（工厂 ID，不是 1.9.1 语义版本） |
| 60–63 | resolution（原始 hex + 推测宽高） |
| 64–127 | UNKNOWN（hex） |
| 128–255 | customFields（ASCII） |

若 256 B 全 `0xFF`/全 `0x00`，会提示“EEPROM 未写入工厂信息”。**只读，安全。**

### 4.5 `version`

```bash
python code/glxss.py version
```

发 `0xA1/0x17 wValue=0 wIndex=2 wLength=24`：

- 0–15：ASCII 版本串（实跑 `G25_MainFW_1.9.1`）
- 16–23：设备 ID（u64 LE，十进制 + hex）

HID 接口被内核驱动占用时读取会失败；Linux 可试 `--detach`。

### 4.6 `hid`

```bash
python code/glxss.py hid
python code/glxss.py hid --enable-sensors --raw
python code/glxss.py hid --count 10 --interval 0.3
```

每 300 ms 发一次 `0xA1/0x16 wValue=0 wIndex=2 wLength=28`，按
`struct.unpack("<BBH9hBBf")` 解析：

| 字段 | 含义 |
|---|---|
| `keyStatus` / `keyCode` | 0=松开、1=按下；Fn 键码固定 `0x71` |
| `lux` | 环境光原始值 |
| `compass_x/y/z` | 磁罗盘原始值 |
| `acc_x/y/z` | 加速度原始值 |
| `gyro_x/y/z` | 陀螺原始值 |
| `af_result` / `caf_flag` | 相机 AF/CAF 状态 |
| `temperature` | 温度（float，摄氏度） |

IMU/罗盘全 0 时通常是传感器没使能，加 `--enable-sensors`
（发 `0x10/0x11/0x12/0x14 wValue=1`）。Ctrl-C 退出。

### 4.7 `frame`

```bash
python code/glxss.py frame bars --fps 10 --seconds 5
python code/glxss.py frame black --fps 25 --loop
python code/glxss.py frame solid --color ff8000 --fps 5
python code/glxss.py frame solid ff8000 --fps 5          # 颜色也可作为第二个位置参数
python code/glxss.py frame png screenshot.png --fit fit
python code/glxss.py frame png screenshot.png --fit cover --fps 10
python code/glxss.py frame bars --dry-run
```

帧格式（见 [docs/ch-display.md](../docs/ch-display.md) §2）：

```
16 B 头：01 00 00 00 | totalLen(u32 LE, 含头) | 8 B 0
payload：640×400×4 = 1,024,000 B RGBA8888
整帧 1,024,016 B，一次性 bulk OUT EP 0x01
```

- 端点自动选：优先 vendor-specific（class 0xFF）+ bulk OUT，通常 if3 / EP `0x01`。
- 默认先发开屏 + 显示模式；`--no-init` 只发帧。
- `--fps 0` 表示不限速；`--loop` 一直发到 Ctrl-C。
- `--fit`：`fit`（默认，留黑边）/ `cover`（裁剪）/ `stretch`（拉伸）。
- PNG 解码用标准库 zlib，支持常见 8/16 位、灰度/RGB/RGBA/调色板、非隔行 PNG；
  透明像素合成到黑底，输出 alpha 统一写 255。
- `--dry-run` 只生成帧、打印头与 SHA256，不碰设备，可离线验证 PNG 映射。

### 4.8 `uvc`

```bash
python code/glxss.py uvc --frames 3
python code/glxss.py uvc --probe-only
python code/glxss.py uvc --detach --frames 5
```

流程（只读测试，见 [docs/ch-uvc.md](../docs/ch-uvc.md)）：

1. 找 VC（`0x0e/0x01`）与 VS（`0x0e/0x02`）接口、bulk IN 端点（通常 `0x81`）。
2. `GET_CUR(probe)`（34 B，失败回退 26 B）读当前参数。
3. 填 `bmHint=1`、format/frame/interval、`dwMaxVideoFrameSize`、
   `dwMaxPayloadTransferSize`，发 `SET_CUR(probe)`，再回读。
4. `SET_CUR(commit)` 生效。
5. `bulk IN` 读若干 URB，打印 12 B UVC payload header
   （`bHeaderLength`、FID/EOF/PTS/SCR/ERR）和首 32 B / 像素首 16 B。

可选参数：`--format-index`、`--frame-index`、`--interval`（100 ns 单位）、
`--fps`、`--max-frame`、`--max-payload`、`--probe-only`。
设备没插/被 `uvcvideo` 占用/没绑定 WinUSB 时会给出明确提示，不会抛 traceback。

### 4.9 `flash`

```bash
# 1) 默认 dry-run：只分析镜像，不打开设备、不写入
python code/glxss.py flash G25_MainFW_1.9.1.img

# 2) 确认打印出的 SHA256 后，真写
python code/glxss.py flash G25_MainFW_1.9.1.img --yes

# 3) 可选：非交互校验期望哈希
python code/glxss.py flash G25_MainFW_1.9.1.img --yes --sha256 <hex>
```

- 目标：引导模式 `03e7:2150`，interface 0，EP `0x01` bulk OUT。
- 每包 **1024 B**，单包超时 **10000 ms**，无 header/ACK/ZLP。
- 自动识别并解密原厂 `.img` 末尾 `0x80000` 字节（AES-128-ECB，
  key `b5Q8JBFRYYIRawl3`，见 [docs/ch-firmware.md](../docs/ch-firmware.md) §3）：
  - 若明文区已有 `G25_MainFW_`，视为已解密，跳过；
  - 否则解密尾部，并检查 PKCS#7 填充；找不到版本串则拒绝发送；
  - `--no-decrypt` 强制发送原文件（用于已解密镜像/特殊场景）。
- **默认 dry-run**。只有 `--yes` 才会打开引导设备写数据。
- 写入过程中设备可能直接断开重枚举；工具会把“设备断开”当作正常结束候选，
  并等待 `2e09:0030` 重新出现（最多 8 s）。
- 风险：传输中拔线/断电可能损坏一级引导；确认供电稳定后再操作。
  若设备仍能枚举 `03e7:2150`，协议是幂等的，可重刷。

成功判据不是 bulk 返回 0，而是**重新枚举为 `2e09:0030`**，之后用：

```bash
python code/glxss.py version
```

### 4.10 `decode-uvc`

```bash
python code/glxss.py decode-uvc dump.bin
python code/glxss.py decode-uvc dump.bin --order i420
python code/glxss.py decode-uvc dump.bin -o first.png --first
```

把 App/Frida 抓到的 raw UVC dump 解成 PNG，默认 `YV12`（Y|V|U），
可用 `--order i420` 对照（Y|U|V）。自动识别：

- 单个 UVC 帧（文件开头就是 `bHeaderLength`）
- 连续 URB（无长度前缀）
- **4 B 小端长度前缀 + URB** 的 dump 格式（`re/liangliang` 抓包）
- 裸 YUV420 frame（长度 = w×h×3/2）

输出：单帧为 `<输入名>.png`；多帧为 `<输入名>_f0001.png`、`_f0002.png`…
`--width/--height` 默认 640×480，`--format auto|frame|stream|prefixed|raw`
可强制指定解析方式。

## 5. 协议速查

| 功能 | bmRequestType | bRequest | wValue | wIndex | wLength |
|---|---|---|---|---|---|
| 设亮度 | `0xA1` | `0x01` | 亮度 | 3 | 1 |
| 设 roll | `0xA1` | `0x02` | roll | 3 | 1 |
| 屏开关 | `0xA1` | `0x03` | 0/1 | 3 | 1 |
| 读状态 | `0xA1` | `0x04` | 0 | 3 | 8 |
| 显示模式 | `0xA1` | `0x05` | 1/2 | 3 | 1 |
| 读 EEPROM | `0xA1` | `0x07` | `0x0100` | 3 | 264 |
| HID 报告 | `0xA1` | `0x16` | 0 | 2 | 28 |
| 版本 + ID | `0xA1` | `0x17` | 0 | 2 | 24 |
| UVC GET_CUR probe | `0xA1` | `0x81` | `0x0100` | VS 接口号 | 34/26 |
| UVC SET_CUR probe | `0x21` | `0x01` | `0x0100` | VS 接口号 | 34/26 |
| UVC SET_CUR commit | `0x21` | `0x01` | `0x0200` | VS 接口号 | 34/26 |

## 6. 排障

| 现象 | 处理 |
|---|---|
| `未找到 2e09:0030` | 确认不是引导模式；检查线材/供电；先跑 `list` |
| `Access denied` / `-13` | Linux 加 udev 规则或 sudo；Windows 用 Zadig 绑 WinUSB |
| `claim ... 失败` / `Resource busy` | 关闭厂商 App/其他占用程序；Linux 加 `--detach` |
| `0x17`/`0x16` 返回 -1 或超时 | HID 被 `hid-generic` 占用：`--detach`；或先关掉 UVC 程序 |
| UVC probe/commit 成功但 0 帧 | Linux `uvcvideo` 占着 VS：`--detach` 或 `modprobe -r uvcvideo`；也查固件是否半死 |
| `frame` 发了但屏不亮 | 先 `screen on`、`mode 2`；这是原厂也会踩的坑 |
| `flash` 找不到 `03e7:2150` | 让眼镜进入引导模式（掉电重启/等待重枚举），不要对正常模式刷写 |
| `flash` 解密后找不到版本串 | 确认是 G25 固件；已解密镜像加 `--no-decrypt`；必要时 `--force`（危险） |

## 7. 已验证 / 未验证

本仓库开发机**没有眼镜**，所以：

**已离线验证**

- `python -m py_compile code/glxss.py`
- 全部子命令 `--help`、`--version`
- `list`：能枚举本机 19 个 USB 设备并打印接口/端点
- `frame --dry-run`：bars/solid/black/png 均生成 1,024,016 B 帧，头字段正确
- PNG 解码：RGB/RGBA/调色板/16 位灰度 + fit/cover/stretch 映射
- `decode-uvc`：合成单帧、连续 URB、4 B 长度前缀、裸 YUV 四种 dump 均解出 640×480 PNG
- AES-128-ECB：NIST 向量 + 与 pycryptodome 对拍；纯 Python 解 512 KiB 约 1 s
- `flash` dry-run：加密/明文 `.img` 自动识别、SHA256、PKCS#7 检查

**未在真机验证**

- 各控制请求的实际 ack 字节（文档只给了返回长度/抓包样本）
- Windows WinUSB 下的 claim/bulk 行为（取决于驱动绑定）
- `uvc` 的 probe/commit 与读帧（需要摄像头出流）
- `flash --yes` 的真实写入与重枚举（需要引导模式设备）

## 8. 参考

- [docs/ch-display.md](../docs/ch-display.md) —— 帧格式、控制面、状态、EEPROM
- [docs/ch-hid.md](../docs/ch-hid.md) —— 28 B 报告、24 B 版本
- [docs/ch-uvc.md](../docs/ch-uvc.md) —— UVC probe/commit、payload header、YV12
- [docs/firmware-boot-protocol.md](../docs/firmware-boot-protocol.md) —— 引导协议
- [docs/ch-firmware.md](../docs/ch-firmware.md) —— 固件解密与刷写 SOP
- [docs/README.md](../docs/README.md) —— 文档索引
