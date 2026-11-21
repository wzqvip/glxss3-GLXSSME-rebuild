# 第 6 章 · 固件与引导

> 本章讲清楚：固件为什么"不在眼镜里"、`G25_MainFW_1.9.1.img` 是什么格式、
> 主机到底发了什么、AES 尾部怎么解、以及**画面全无时怎么救回来**。
>
> 对应代码：[`usb/MyriadBootLoader.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/usb/MyriadBootLoader.kt)
> （纯 Kotlin，默认路径）、[`usb/BootLoader.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/usb/BootLoader.kt)
> （厂商 `libboot.so` JNI，可选路径）
>
> 深度逆向：[firmware-boot-protocol.md](firmware-boot-protocol.md)（反汇编地址、TLV、ELF）；
> 固件能否回读：[firmware-extraction.md](firmware-extraction.md)；
> 原厂刷机抓包与 -206：[vendor-flash-capture.md](vendor-flash-capture.md)；
> 故障诊断：[device-recovery.md](device-recovery.md)

---

## 1. 一句话：固件是"每次开机从主机加载"的

```
上电/复位
  │
  ├─ Myriad 2 芯片内 mask ROM 启动（不可改、不可读）
  │     └─ 枚举 03E7:2150 "Movidius MA2X5X"
  │           └─ 主机把 .img 从 EP 0x01 灌进来（裸 bulk，无协议）
  │
  ├─ 设备收满 → 自行重启
  │     └─ 重枚举 2E09:0030（UVC + HID + LCD + UAC）
  │
  └─ RTEMS 固件在 DDR 里跑起来
```

推论：

- **设备端没有"当前固件"可读**。想备份，备份主机侧那份文件；想确认版本，读 `0xA1/0x17`。
- **拔插 = 固件丢失**。因为掉电后 DDR 清空，必须重新灌。
- **"刷坏了"通常还能救**。ROM 在芯片内，只要设备还能枚举 `03E7:2150`，就能重灌。
- **固件卡死无法软复位**。没有私有 reset 命令，只能重灌（见 §7）。

---

## 2. 容器格式：MA2x / MVCMD

文件 `G25_MainFW_1.9.1.img`：**1,525,136 B = `0x174590`**。

| 区间 | 长度 | 内容 | 加密 |
|---|---|---|---|
| `0x000000`–`0x000048` | 72 B | `MA2x` 容器头（TLV：名字/段/寄存器初始化） | 明文 |
| `0x000048`–`0x0F4590` | ~1 MB | LEON/SPARC 一级引导（DDR init）+ `0xFF000000` 填充 + 资源 | 明文 |
| `0x0F4590`–`0x174590` | **`0x80000` = 512 KiB** | 二级引导描述符 + 主固件（含 ELF32 SPARC） | **AES-128-ECB** |

### 2.1 头部字节

| 偏移 | 字节 | 解析 | 置信 |
|---|---|---|---|
| `0x00` | `4D 41 32 78` | magic **`MA2x`** | 【验】 |
| `0x04` | `A8 1B 00 00` | TLV：`tag=0xA8`、`len=0x1B`（27） | 【验】 |
| `0x08` | `ddrInitHeaderForMvcmd.elf\0` | 一级引导名（26 B） | 【验】 |
| `0x22` | `9A 28 00 FC 20 11 11 11 11` | `tag=0x9A` + 两个 u32（疑似寄存器地址/值） | 【推】 |
| `0x34` | `A8 07 00 00` | 第二个名字记录 | 【验】 |
| `0x38` | `.text\0\0` | 段名 | 【验】 |
| `0x48` | `A0 BF E3 9D` | SPARC `save %sp,-96,%sp` → 一级引导代码起点 | 【验】 |

TLV 模型（由多个样本长度自洽推出）：

```
record := u8 tag | u24 len_le | payload
  tag 0xA8 : 名字记录，payload = ASCII + '\0'，len = strlen + 2
  tag 0x9A : 定长 9 B（tag + 2×u32），疑似 (寄存器地址, 值)
```

> **注意**：`len` 恒比真实 payload 多 1，按 `len` 前进会错位；读名字必须靠 `\0` 结束符。
> 完整样本与置信度见 [firmware-boot-protocol.md §5](firmware-boot-protocol.md#5-g25_mainfw_191img-容器解析ma2x--mvcmd)。

### 2.2 解密后的关键位置

| 偏移 | 内容 |
|---|---|
| `0x49C4` | `GIT VERSION: CLEAN 2016-06-09 ... refs/heads/temp_proposed_golden_driver_ma2450` |
| `0x101474` | **`G25_MainFW_1.9.1\n`**（镜像内真实版本串） |
| `0x101485` | `ELF32 / LSB / ET_EXEC / EM_SPARC`（`main.elf` 描述符） |
| `0x174558` | `OsMV0212.c`（板级名 MV0212） |
| 全文 | `RTEMS 4.11.99.0 (MYRIAD2/w/FPU/ma2x5x)`、`Configuring imx214M10MO camera and datapath`、`LcdGeneric/leon/Lcd.c`、`MCCI` USB 栈 |

---

## 3. AES 尾部与密钥链

### 3.1 规则

- 只加密**最后 `0x80000`（512 KiB）**，前面的明文与解密后内容**逐字节连续**；
- 算法 **AES-128-ECB**，key 来自 `llvision.enkey`；
- 解密后尾部有 PKCS#7 填充，原厂按"最后一个字节 = 填充长度"截断：
  `outLen = size - 16 - plain[last]`（1.9.1 → `0x174574` = 1,525,108）。

密钥链（已完整复算）：

```
llvision.enkey (128 B RSA 密文)
   --RSA-1024 / PKCS#1 v1.5（libboot.so 内置私钥 PEM）-->
"b5Q8JBFRYYIRawl3b5Q8JBFRYYIRawl3\n"  (33 B ASCII)
   --取前 16 B-->
AES-128-ECB key = "b5Q8JBFRYYIRawl3"
```

厂商从未轮换密钥：1.7.4 / 1.9.1 / 社区版三份 APK 的 `enkey` sha256 完全相同。

### 3.2 三个长度，别搞混

| 长度 | 值 | 用途 |
|---|---|---|
| 原始文件 | `0x174590` = 1,525,136 | APK asset / 原厂 App 读取 |
| **规范明文** | `0x174574` = 1,525,108 | 原厂实际发送的长度（去掉 28 B 尾部填充） |
| 本仓库发送 | **1,525,136（完整）** | 设备容忍尾部多 28 B，已实测成功 |

> 自己写 flasher：直接发**已解密的完整字节**即可（当前 `MyriadBootLoader` 就是这样）；
> 若追求与原厂逐字节一致，可在解密后截到 `size - 16 - plain[last]`。
> **用原厂 App 重刷时必须保留原始 1,525,136 B 的文件**（它自己算长度）。

---

## 4. 灌写协议（03E7:2150）

### 4.1 规格

| 项 | 值 | 证据 |
|---|---|---|
| 目标 | `03E7:2150`，interface 0 | 枚举 |
| 端点 | **EP `0x01`（OUT，bulk）** | 抓包 + 反汇编 |
| 分块 | **≤1024 B**（硬编码 `0x400`） | `libboot.so` 立即数 |
| 单块超时 | **10,000 ms** | `0x2710` |
| 帧头 / 命令 / ACK / ZLP | **全部没有** | 全库唯一 bulk 调用，无 control、无 IN |
| 结束标记 | 没有；设备按容器内长度判断收完 | 原厂同样不发 |
| 成功判定 | **`2E09:0030` 重新枚举**（不是 bulk 返回 0） | 抓包 |

### 4.2 时序

```
主机                                              眼镜（ROM）
  │  openDevice + claimInterface(0)                  │
  │  ── bulk 1024 B ──────────────────────────────►  │
  │  ── bulk 1024 B ──────────────────────────────►  │
  │  ... 共约 1490 包 ...                            │
  │  ── bulk 最后一块（372 / 400 B）───────────────► │
  │  releaseInterface + close                        │
  │                                                  ├─ 收满 → 自行重启
  │  ◄────────── 03E7:2150 消失 ────────────────────┤
  │  ◄────────── 2E09:0030 出现（约 1.5 s）─────────┤
```

具体步骤（与 §5 代码一一对应）：

```
1) 等 03E7:2150 出现（attach 广播 / 轮询 deviceList），申请并确认 USB 权限
2) 读出 .img 全部字节（1,525,136 B），校验 magic == "MA2x"
3) 把最后 min(size, 0x80000) 字节做 AES-128-ECB 解密（1.9.1 → 从 0x0F4590 开始）
4) openDevice，claimInterface(0, force = true)，找 bulk OUT（EP 0x01）
5) 从 offset 0 开始，每块 1024 B 调 bulkTransfer(timeout = 10 s)，按返回值前进
6) 写完不发结束命令、不发 ZLP；release + close
7) 等 2E09:0030 重新枚举（约 1.5 s）；不要轮询旧 fd
8) 用 0xA1/0x17 读版本确认；失败则先查设备是否还在 03E7:2150，再重刷
```

### 4.3 为什么原厂 App 会 -206（历史坑，已修）

`libboot` 的 `bootDownload` 是**异步**的：`pthread_create` 后 **1 ms 内返回 0**，
worker 才开始读文件、解密、bulk 写。旧版 `BootLoader.kt` 在 `bootDownload` 返回后立刻
`conn.close()`，worker 第一次 bulk 就失败 → `-206 "trasfer data fail"`。

正确做法：**连接必须活到 `BootCallback.onSuccess/onFail`**。
当前 [`BootLoader.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/usb/BootLoader.kt#L51-L66) 已经改成只在回调里关闭。
不过它依赖厂商 `libboot.so / libcommon.so / libusb100.so`（`.gitignore` 排除了 `*.so`，
仓库不分发），所以**默认走纯 Kotlin 的 `MyriadBootLoader`**。

---

## 5. 可运行代码（默认路径）

### 5.1 解密尾部

```kotlin
// usb/MyriadBootLoader.kt
object MyriadBootLoader {
    const val BOOT_VID = 0x03E7
    const val BOOT_PID = 0x2150
    private const val CHUNK = 1024
    private const val TIMEOUT = 10000
    private const val ENC_START = 0x0F4590
    private const val ENC_LEN = 0x80000
    private val AES_KEY = "b5Q8JBFRYYIRawl3".toByteArray(Charsets.US_ASCII)

    fun prepareImage(img: ByteArray): ByteArray {
        val out = img.copyOf()
        val start = minOf(ENC_START, out.size)
        val len = minOf(ENC_LEN, out.size - start)
        if (len <= 0) return out
        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
            val plain = cipher.doFinal(out, start, len)
            System.arraycopy(plain, 0, out, start, plain.size)
            // 保留完整 0x80000 字节（含 PKCS#7 填充字节），总长 = 0x174590
            Log.i(TAG, "decrypted tail: cipher=$len plain=${plain.size}")
            out
        } catch (e: Exception) {
            Log.e(TAG, "decrypt failed", e)
            out
        }
    }
}
```

> 用 `min(ENC_START, size)` / `min(ENC_LEN, ...)` 而不是裸下标，是为了不让短文件越界。
> **但要注意：`ENC_START = 0x0F4590` 是 1.9.1 专用的写死值**（等于 `1.9.1.size - 0x80000`）。
> 若要刷 1.7.4（1,522,416 B），正确起点是 `size - 0x80000 = 0x0F3AF0`；
> 当前代码会从 `0x0F4590` 开始解，**对 1.7.4 是错的**。通用写法应是
> `start = size - min(size, 0x80000)`，本项目暂时只保证 1.9.1（见 [open-questions.md](open-questions.md) F4）。

### 5.2 发送

```kotlin
// usb/MyriadBootLoader.kt —— 与 libboot.so 的 send_file 等价
var off = 0
var retry = 0
while (off < img.size) {
    val n = minOf(CHUNK, img.size - off)
    val sent = conn.bulkTransfer(ep, img, off, n, TIMEOUT)
    if (sent < 0) {
        retry++
        Log.w(TAG, "bulk fail off=$off retry=$retry")
        if (retry > 3) { done(false, "传输失败 off=$off ret=$sent"); return@Thread }
        Thread.sleep(50)
        continue
    }
    off += sent
    if (off % (CHUNK * 64) == 0) progress(off * 100 / img.size, "写入 $off/${img.size}")
}
```

调用（MainActivity 自动恢复）：

```kotlin
// MainActivity.autoFlashBoot()
val base = getExternalFilesDir(null)?.absolutePath ?: "/sdcard/Android/data/com.wzqvip.glxsscast/files"
val img = base + "/G25_MainFW_1.9.1.img"
if (!File(img).exists()) { status = "检测到引导模式，但缺少固件文件"; return }
MyriadBootLoader.flash(this, dev, img,
    { p, m -> runOnUiThread { status = "$p% $m" } },
    { ok, msg -> runOnUiThread { status = msg } })
```

> 固件文件放在 **app 专属外部目录**：`/sdcard/Android/data/com.wzqvip.glxsscast/files/G25_MainFW_1.9.1.img`。
> 不需要 `llvision.enkey`（纯 Kotlin 路径直接内置 AES key）。

---

## 6. 实测数据

| 项 | 值 |
|---|---|
| 镜像 | 1,525,136 B；解密后 `0x101474` 处 = `G25_MainFW_1.9.1\n` |
| 规范明文 sha256 | `beaed6c7…b9d60`（长度 `0x174574`） |
| 传输 | 1490 次 bulk（1489×1024 + 372/400），总耗时 **约 460 ms**（≈3.3 MB/s） |
| 重枚举 | `03E7:2150` 消失 → `2E09:0030` 出现，**约 1.5 s（1.3–1.8 s）** |
| 原厂抓包 | 1490 包、全部 EP 0x01、timeout 10000 ms、**0 条 control** |
| 恢复效果 | 重灌后 UVC 25 fps、投屏 25 fps、原厂 App 也恢复出图 |
| 1.7.4 | 1,522,416 B，规范明文 1,522,396 B；加密尾起点 `0x0F3AF0`；与 1.9.1 同构建日，差 2,712 B（新增 CPR 驱动）。**当前 loader 的 `ENC_START` 需改成 `size - 0x80000` 才能刷它** |

镜像自检（可复现）：

```
原始 asset md5        9cea02e400fcf6c126aac12e1fb4765c  (1,525,136 B)
解密后 md5            8e878a410d67187099f82e9a6d9ec275
0x101485 处          7f 45 4c 46  （ELF）
0x101474 处          "G25_MainFW_1.9.1\n"
```

---

## 7. 半死现象与恢复 SOP

### 7.1 什么是"半死"

固件的**媒体流水线**（相机采集 / 面板刷新）停摆，但 USB 栈和控制面还活着：

| 层 | 卡死时状态 | 证据 |
|---|---|---|
| USB 枚举 | 正常 `2E09:0030`，7 接口全在 | `lsusb` / `dumpsys usb` |
| LCD 控制面 | **正常**：`0xA1/0x04` 返回 8 B，`0x05/0x03/0x01` 全 ACK | Frida 直连 |
| 投屏 bulk | **正常**：1,024,016 B / 30 ms 收完 | 同上 |
| UVC 数据面 | **0 帧**：3 个 460822 B URB 全部 `TIMED_OUT` | 原厂 App 同样如此 |
| 面板 | 状态仍报 `bopen=1`，但画面不刷新 | `0xA1/0x04` |
| 固件日志线索 | `Could not initialize Leon IPC Rx`、`list full`（发往相机任务的队列满） | 固件 strings |

**关键反证**：卡死时原厂 App（proven 的 libuvc）也收不到帧 → 不是我们的代码问题。
**没有私有复位命令**：`liblcd.so` / `libhid.so` 的全部 control 请求已枚举，无 reset / power-on / camera-enable。
（见 [device-recovery.md §3](device-recovery.md#3-固件侧行为fw-191decbin-strings验)）

### 7.2 三态判定（30 秒）

| # | 现象 | 判定 | 处理 |
|---|---|---|---|
| A | 没有 `2E09:0030` | 掉线 / 在引导模式 | 等重枚举；若是 `03E7:2150` → 灌固件 |
| B | 有 `2E09`，状态正常，但 UVC 0 帧且色条/投屏不出图 | **媒体流水线卡死** | §7.3 |
| C | 有 `2E09`，UVC 有数据但 `n == urbSize`、首字节不是 `0x0C` | 在灌无边界垃圾流（同一故障的较轻态） | §7.3 |
| D | UVC `n=460812`、首字节 `0C`、20–25 fps | 健康 | — |

### 7.3 恢复步骤（实测有效）

```text
1) 拔掉眼镜 USB，等 2 秒，再插上
   → 设备以 03E7:2150 出现（dmesg: Product: Movidius MA2X5X）
2) App 收到 attach 广播，自动读
   /sdcard/Android/data/com.wzqvip.glxsscast/files/G25_MainFW_1.9.1.img
   → AES 解尾部 512 KiB → 1490 次 1024 B bulk（约 1.5 秒）
   若没自动灌：点「加载固件（引导模式 03E7:2150）」
3) 设备自行重启，重枚举为 2E09:0030
4) 点「连接眼镜」→「开屏（switch=1 + 显示模式 2）」→ 发色条 / 开摄像头 / 开始投屏
```

**成功判据**：不是"bulk 写完"，而是 `2E09:0030` 重新出现 **且** UVC 能收到 `n=460812`、首字节 `0C`。

### 7.4 哪些事没用（别浪费时间）

| 尝试 | 结果 |
|---|---|
| 软复位 USB role / 重新枚举 | ❌ 不能恢复，只有固件重载能恢复 |
| 补发某条私有复位命令 | ❌ 不存在这样的命令 |
| 改 URB 尺寸 / header 偏移 / U/V 顺序 | ❌ 健康固件下同一份代码 25 fps |
| 停掉内核 `uvcvideo` / `hid-generic` | ❌ 两边都在 force claim，且原厂也拿不到帧 |
| 等一会儿 | ❌ 实测不会自愈 |

---

## 8. 坑与注意事项

1. **别对已解密的镜像再解密**。先探测 `0x101474` 是否是 `G25_MainFW_` 或 `0x101485` 是否是 `7F 45 4C 46`。
2. **刷写期间单 owner**。不要同时开 UVC/投屏/原厂 App；否则会出现 `bulkTransfer` 失败或 -206。
3. **不要中途拔线**。一级引导（DDR init）写一半是最危险的情形；失败后先确认还能枚举 `03E7:2150`。
4. **重试不能"从中断处续传"**。`MyriadBootLoader` 在同一 offset 重试 3 次；更稳妥的是失败即从头重来，
   因为设备是按流解析容器的，中间缺一段无法恢复。
5. **校验镜像**：`MA2x` 魔数 + `G25_MainFW_` 版本串 + `OsMV0212` 板级串，防止刷错型号。
6. **剪裁长度可选**：完整 1,525,136 B 已验证可用；要与原厂一致就截到 `size - 16 - plain[last]`。
7. **不要发 `SET_CONFIGURATION`**：原厂也没发，Android 下反而可能失败。
8. **`bulkTransfer` 可能短写**：必须按返回值前进，不能假设每次都等于请求长度。
9. **`libboot` 路径需要三个 `.so` + `llvision.enkey`**，且 `bootDownload` 是异步的；普通使用不需要它。
10. **固件文件路径**：`getExternalFilesDir(null)` 对应 `/sdcard/Android/data/<pkg>/files/`；
    原厂用的是 `/sdcard/llvision/`（需要存储权限），不要混。
11. **刷完不要立刻抢 UVC**：等 1–2 秒让相机的 IMX214 任务起来（未证实的风险项）。
12. **SELinux**：如果用 root 把文件塞进 `/data/data/<pkg>/files/`，要 `chcon` 到 app 的 context，
    否则 native `fopen` 会 -203（见 [vendor-flash-capture.md §7.2](vendor-flash-capture.md#72-selinux-小坑)）。

---

## 9. 待验证

- [ ] 半死状态的触发条件（长期 force-stop？LCD+UVC 并发？固件 watchdog？）——需要 UART 才能看固件日志。
- [ ] `list full`（LEON IPC 队列满）是否就是卡死点。
- [ ] `perform_power_saving_on` 是否会停掉 camera/LCD 任务而 USB 任务照跑。
- [ ] 1.7.4 的规范长度在真机上是否同样可用（当前只用 1.9.1 实测过）。
- [ ] 引导 ROM 是否存在未公开的回读命令（有风险，未探测）。
- [ ] `0x9A` TLV 记录的第二字段是否真是写入值。
