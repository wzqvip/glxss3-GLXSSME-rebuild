# 亮亮视野 GLXSS G25 固件引导协议（LibBoot / MA2x MVCMD）

> 目标设备：亮亮视野 G25 AR 眼镜（Intel Movidius Myriad 2 / MA2450，与 Intel NCS1 同芯片）
> 逆向对象：`com.llvision.glxss3.camera` v2.3.7 中的 `libboot.so` / `libcommon.so`、`Boot.java`、`LLVisionPlatformService.java`、`assets/G25_MainFW_1.9.1.img`
> 本文所有结论都带证据（虚拟地址 / 指令 / 字符串）。标注说明：
> **【验】= 已用二进制或实测数据验证**；**【推】= 由代码结构推断（未在真机抓包确认）**；**【未】= 仍未确定**

---

## 0. 一句话结论

`libboot.so` 是 **Intel NCSDK `usb_boot.c` 的亮亮视野改写版**：对 `03e7:2150` 只做**纯 bulk 单向写**——
把（解密后的）`.img` 文件原始字节，**每次 ≤1024 字节、超时 10000 ms**，写到 **端点 0x01（EP1 OUT）**，
**没有任何命令包、没有 header、没有 ACK、没有 control 传输、没有零长度包（ZLP）**。
写完后直接 close 句柄；眼镜由设备侧固件自行重启并重枚举为 `2e09:0030`。
除了"写数据"本身，真正的协议全部藏在 `.img` 文件内部（MA2x 容器），由设备 ROM 解析，主机不参与。

---

## 1. 证据来源与定位方法

`libboot.so` 已被 strip，只有 `.dynsym`。定位方式：

* `JNI_OnLoad`（x86_64 `0x11d0`）→ `RegisterNatives` 表 `.data`，类名 `com/llvision/glass3/sdk/boot/Boot`，3 个方法
  `nativeBootInit` / `nativeBootDestory` / `nativeBootDownload`（字符串 `0x50b0`、`0x50d2`、`0x50fc`、`0x5112`）。
* 日志格式串 `[%d*%s:%d:%s]:Performing bulk write of %lu byte%s from %s...`（x86_64 `.rodata:0x5475`）
  与 NCSDK `usb_boot.c` 的 `"Performing bulk write of %u bytes...\n"` 完全同源。
* 全 `.text` 中**只有一处** `libusb_bulk_transfer` 调用（x86_64 `0x3bb1`，arm64 `0x37a8`）。

> 说明：本文件中的地址以 **x86_64 版 `lib/lib/x86_64/libboot.so`** 为主（代码最易读），
> arm64 版（`lib/arm64-v8a/libboot.so`）用于交叉验证常量，两者逻辑一致。
>
> ⚠️ 这两个 `.so` 的 `.plt` 桩与 `.rela.plt` 顺序存在**整体错位 1 项**（疑似壳/加固产物，x86_64 与 arm64 均如此），
> 因此**不能按 `.dynsym` 名字直接标注 import**；本文的 import 归属全部由**调用点参数形态**（参数个数/常量）反推，
> 并已与开源 `usb_boot.c` 源码逐行对齐确认。

---

## 2. USB 层规格【验】

| 项 | 值 | 证据 |
|---|---|---|
| 引导模式 VID:PID | `0x03e7:0x2150` | `LLVisionPlatformService.java:159,183`：`getVendorId()==999 && getProductId()==8528`（999=0x03E7，8528=0x2150） |
| 正常模式 VID:PID | `0x2e09:0x0030` | `LLVisionPlatformService.java:159`：`==11785 && ==48`（11785=0x2E09，48=0x30） |
| 接口 | **interface 0, alt 0** | `libcommon.so` 字符串 `claiming interface %d failed: %s`；`libusb_claim_interface` 导入 |
| 配置 | 1 | NCSDK 原版 `libusb_set_configuration(h,1)`；`libcommon.so` **没有** `libusb_set_configuration` 导入 → Android 下依赖系统已配置，**Kotlin 只需 claimInterface** |
| 数据端点 | **0x01（EP1 OUT，bulk）** | 传输循环前 `mov esi,1`（x86_64 `0x3b9b`）/ `mov w1,#1`（arm64 `0x3794`），方向位 bit7=0 → OUT |
| 反向端点 0x81 IN | 存在但**从不使用** | `libboot.so` 无 `libusb_control_transfer`、无 inbound bulk 调用 |
| 每次写长度 | **≤1024 B**（`0x400`） | x86_64 `0x3b68 cmp r14d,0x400` + `cmova`；arm64 `0x377c cmp w9,#0x400` + `csel` |
| 单次超时 | **10000 ms**（`0x2710`） | x86_64 `0x3ba0 mov r9d,0x2710`；arm64 `0x379c mov w5,#0x2710` |
| 打开方式 | `libusb_get_device_with_fd` + `libusb_open` | `libcommon.so` 导入表；对应 Android 的 `UsbManager.openDevice(device)` |

> **【推】关于 1024**：NCSDK 原版是 `bulk_chunk_len = endpoint->wMaxPacketSize`，
> 亮亮版把上限硬编码成 `0x400`。因此**无论 OUT 端点的 `wMaxPacketSize` 是 512 还是 1024，都按 1024 切块**。
> （若你抓到的描述符里只有 0x81 IN，那说明 OUT 端点未被记录；固件写入必须存在 0x01 OUT，否则原厂 App 无法工作。）

---

## 3. 传输协议规格

### 3.1 数据帧格式

**没有帧格式**——就是"把文件字节流切片后原样写下去"。

| 层 | 内容 | 证据 |
|---|---|---|
| 传输层 | 裸 `bulk OUT`，无 USB 命令/类请求 | `.text` 内唯一 bulk 调用；无 `libusb_control_transfer` 导入 |
| 应用层 | `image[offset .. offset+len)` 原样 → EP1 | `send_file` 循环 `p += wbr; twb += wbr`（x86_64 `0x3c37..0x3c43`） |
| 帧头 | **无**（无 magic/size/address/flags 前缀） | 发送缓冲区即文件 `malloc` 后的原内容（`0x390f malloc` → `0x3920 fread` → `0x3937 decrypt` → 循环写） |
| 结束标记 | **无**（不发 0 长包，不发"启动"命令） | 循环条件 `twb < file_size`，退出后直接走收尾 |

字段级"协议"全部在 **`.img` 容器内部**（第 5 节），由设备 ROM 解析。

### 3.2 时序 / 状态机

```
[App]  检测到 03e7:2150  ──► 申请权限 ──► openDevice
                                          │
                                          ├─ claimInterface(0)
                                          ├─ nativeBootInit(vid,pid,fd,bus,addr,usbfs)  ← 只做 libusb init/open，不碰数据
                                          ├─ pthread → 读文件 → 解密尾 512KiB
                                          ├─ loop: bulkTransfer(EP 0x01, 1024B, timeout=10s)
                                          ├─ usb_destory()  （release + close，句柄释放）
                                          └─ 回调 onSuccess()/onFail(code,msg)
                                                │
[设备] 收满镜像 → 自行重启 → 重枚举 2e09:0030
                                                │
[App]  USBMonitor onAttach → onConnect(2e09:0030) → HID/LCD init
```

* **没有 ACK 读取**：`Send` 方向只有写，代码里没有任何 in-transfer 或 control-in。
* **没有"启动"命令**：设备给多少收多少，靠容器头里的长度字段判断完整性。
* **重试**：native 层**零重试**。`libusb_bulk_transfer` 返回负值（除 `-4 LIBUSB_ERROR_NO_DEVICE` 会 break）立刻返回错误码。
* **重枚举等待**：由 Java 层 `USBMonitor` 的 attach 广播驱动，native 不等待。

### 3.3 返回值 / 错误码（`ResultCode.java` + native 常量对齐）【验】

| 常量 | 值 | native 立即数 | 触发点 |
|---|---|---|---|
| `BOOT_ERROR_NOT_INIT` | -201 | `0xffffff37`（x86_64 `0x457b`） | `nativeBootDownload` 时 `g_dev==NULL` |
| `BOOT_ERROR_ALREADY_INIT` | -202 | `0xffffff36`（`0x4146`） | 重复 `nativeBootInit` |
| `BOOT_ERROR_OPEN_FILE` | -203 | `0xffffff35`（`0x39e6`） | `fopen` 失败 |
| `BOOT_ERROR_MALLOC_BUFFER` | -204 | `0xffffff34`（`0x3a40`） | `malloc(size)` 失败 |
| `BOOT_ERROR_READ_FILE_INCOMPLETE` | -205 | `0xffffff33`（`0x3aae`） | `fread` 长度不足 |
| `BOOT_ERROR_TRASFER_DATA_FAIL` | -206 | `0xffffff32`（`0x3ca4`） | bulk 写失败（非 NO_DEVICE） |
| （私有）解密失败 | -207 | `0xffffff31`（`0x398a`） | `decrypt_glxss_file() != 0` |

失败时走 `onFail(int code, String msg)`，`msg` 取自 `.rodata`：
`"success" 0x52c1`、`"open file fail" 0x52c9`、`"malloc buffer fail" 0x52d8"`、
`"read file incomplete" 0x52eb`、`"trasfer data fail" 0x52f0`（原文拼写如此）、`"no msg" 0x5302`。
（`boot_jni.cpp:0x5309 "[%d*%s:%d:%s]:result code = %d , msg = %s"`）

---

## 4. 完整流程（从 03e7:2150 到 2e09:0030）

### 4.1 native 侧函数

| 函数 | x86_64 | arm64 | 说明 |
|---|---|---|---|
| `nativeBootInit(IIIIILjava/lang/String;)I` | `0x40d0` | — | `JNI GetStringUTFChars(usbfs)` → `malloc(0x20)` → `usb_init(&dev,vid,pid,fd,bus,addr,usbfs)` → `g_dev` |
| `nativeBootDownload(String,String,BootCallback)I` | `0x4430` | — | 取 `path`/`keyPath` UTF，`pthread_create(boot_worker, NULL)`，**立即返回 0**（异步） |
| `boot_worker`（内含 `send_file`） | `0x3890` | — | 读文件 → 解密 → 发送 → `usb_destory` → JNI 回调 |
| `nativeBootDestory()` | `0x4360` | — | `usb_close`/`usb_destory`，`g_dev=NULL` |

`nativeBootInit` 日志串（`0x5195`）：
`"native boot init vid=%d, pid=%d, fd=%d, busNum=%d, devAddr=%d, usbfs_str=%s"` → 参数与 `Boot.bootInit()` 的 6 参一一对应。

### 4.2 发送循环（逐步）

```
fopen(path,"rb") → fseek/ftell 得 size → rewind
malloc(size) → fread(buf,1,size)
decrypt_glxss_file(buf,size,&outLen,keyPath)      // 0x32c0，失败返回 -207
twb = 0
while (twb < outLen) {
    wb = min(outLen - twb, 1024)
    wbr = 0
    rc = libusb_bulk_transfer(g_dev->handle, 0x01, buf+twb, wb, &wbr, 10000)
    if (rc || wbr != wb) {
        if (rc == LIBUSB_ERROR_NO_DEVICE) break;   // 设备已重启
        return -206;                                // 其它错误立即失败
    }
    twb += wbr
}
usb_destory();  // release_interface + close
```

对应反汇编：x86_64 `0x3b50`～`0x3c4c`（循环体），常量 `0x400`/`0x2710`/`ep=1`。

### 4.3 各阶段超时与耗时（实测估算）

| 阶段 | 时间 | 来源 |
|---|---|---|
| 单块 bulk 超时 | 10 000 ms | 立即数 `0x2710` |
| 1.5 MB 传输（约 1490 块） | 依 USB 速率，通常 3–15 s | 块数 = ⌈1525136/1024⌉ = **1490** |
| 解密 | 仅 AES-128-ECB 512 KiB，毫秒级 | 见 5.3 |
| 设备重启 + 重枚举 | 数百 ms ~ 数秒 | 由 Java `USBMonitor` 捕获 attach |

---

## 5. `G25_MainFW_1.9.1.img` 容器解析（MA2x / MVCMD）

文件大小 **1,525,136 B = `0x174590`**。整体分三段：

| 区间 | 长度 | 内容 | 加密 |
|---|---|---|---|
| `0x000000`–`0x000048` | 72 B | `MA2x` 容器头（TLV） | 明文 |
| `0x000048`–`0x0F4590` | ~1 MB | 头记录 + LEON/SPARC 一级引导代码 + 大块 `0xFF000000` 填充/图像数据 | 明文 |
| `0x0F4590`–`0x174590` | **`0x80000` = 512 KiB** | 二级引导描述符 + 主固件（含 `ELF32 SPARC`） | **AES-128-ECB** |

> 512 KiB 边界不是猜的：`decrypt_glxss_file` 里就是 `buf + size - 0x80000`（x86_64 `0x3437`），
> 且 `0x174590 - 0x80000 = 0x0F4590`，与实测数据熵边界**完全重合**。

### 5.1 容器头（已验证字节）

| 偏移 | 字节 | 解析 | 置信 |
|---|---|---|---|
| `0x00` | `4D 41 32 78` | magic `"MA2x"`（ASCII；按 LE u32 = `0x7832414D`，按 BE 读 = `0x4D413278`，与 NCSDK `MVCMD_MAGIC` 一致） | 【验】 |
| `0x04` | `A8 1B 00 00` | TLV：`tag=0xA8`，`len=0x1B=27` | 【验】 |
| `0x08` | `ddrInitHeaderForMvcmd.elf\0\0` | 一级引导名（26 B，len 多算 1） | 【验】 |
| `0x22` | `9A 28 00 FC 20 11 11 11 11` | `tag=0x9A` + 8 B 负载：`0x20FC0028` / `0x11111111` | 【推】寄存器(地址,值)对 |
| `0x2B` | `9A 30 00 FC 20 11 11 11 11` | `0x20FC0030` / `0x11111111` | 【推】同上（地址 +8） |
| `0x34` | `A8 07 00 00` | `tag=0xA8`，`len=7` | 【验】 |
| `0x38` | `.text\0\0` | 段名 | 【验】 |
| `0x40` | `00 8A 00 00` | `0x8A00` = 35328（候选：一级 `.text` 长度） | 【推】 |
| `0x44` | `18 70 04 11` | `0x11047018`（候选：加载地址 / 入口，未能与 Myriad2 存储图对齐） | 【未】 |
| `0x48` | `A0 BF E3 9D` | **SPARC `save %sp,-96,%sp`**（LE 存放，BE 值 `0x9DE3BFA0`）→ 一级引导代码起点 | 【验】 |

**TLV 记录模型【推】**（由 4 处独立样本长度自洽推出）：

```
record := u8 tag | u24 len_le | payload
   tag 0xA8 : 名字记录，payload = ASCII 名字 + '\0'（实际占 strlen+1），len 字段 = strlen+2（比 payload 多 1）
   tag 0x9A : 定长 9 B 记录（tag + 2×u32），疑似 (寄存器地址, 值)
```

> 即 `len` 恒比真实 payload 多 1（4 处样本 + 2 个符号名样本全部吻合），
> 解析器若按 `len` 前进会错位——这也是"必须靠 NUL 结束符读名字"的原因。

样本自洽性（`len == strlen+2`）：`".text"`(5)→7 ✓、`"main.elf"`(8)→10 ✓、`"nlockCriticalSection"`(20)→22 ✓、
`"ddrInitHeaderForMvcmd.elf"`(25)→27 ✓、`"OsMV0212.c"`(10)→12 ✓、`"ors_aux"`(7)→9 ✓。

> 名字记录里出现了 `nlockCriticalSection`、`ors_aux` 这类**半截名字**（`_global_dtors_aux` 的后缀），
> 说明该表使用了**后缀共享压缩**，同一前缀只存一次。【推】

### 5.2 文件内已定位的关键结构

| 偏移 | 内容 | 置信 |
|---|---|---|
| `0x0048` | SPARC/LEON 一级引导代码（DDR init header） | 【验】 |
| `0x49C4` | `"GIT VERSION: CLEAN 2016-06-09 06:56:58 +0300 ref..."`（Movidius 构建戳） | 【验】 |
| `0x49FD` | `"temp_proposed_golden_driver_ma2450"` | 【验】 |
| `0x7420` | TLV `0xA8 len=10` → `"main.elf\0"` | 【验】 |
| `0x742D` | TLV `0xA8 len=22` → `"nlockCriticalSection\0"` | 【验】 |
| `0x7450`–`0x101474` | 大块 `00 00 00 FF` 字（254,221 个，≈993 KiB），疑似 ARGB 黑（`0xFF000000`）资源/显存镜像 | 【验】字节；用途【推】 |
| `0x101474` | `"G25_MainFW_1.9.1\n"` —— **镜像内真实版本串**（可由它做版本校验） | 【验】 |
| `0x101485` | **ELF32 little-endian, `EM_SPARC`(2), ET_EXEC, e_entry=0** | 【验】 |
| `0x174558` | `"OsMV0212.c\0"`（板级名 MV0212，与固件串 `"Board Mv0212 initialized"` 呼应） | 【验】 |

### 5.3 内嵌 ELF（`0x101485`，即容器中的 `main.elf` 描述符）

```
ELF32 / LSB / ET_EXEC / EM_SPARC
e_entry = 0x0     e_phoff=0x34  e_phnum=2   e_shoff=0xC3C  e_shnum=4  e_shstrndx=3
```

程序头（**段表 = 加载地址 + 长度**）：

| # | 类型 | 文件偏移 | **加载地址 (p_vaddr/p_paddr)** | 文件长度 | 内存长度 | 权限 |
|---|---|---|---|---|---|---|
| 0 | PT_LOAD | `0x80` | **`0x1C000000`** | `0x4` | `0x4` | RW (6) |
| 1 | PT_LOAD | `0x90` | **`0x1D000000`** | `0xB8D` | `0xB8D` | RX (5) |

节表：

| # | 名称 | 类型 | 地址 | 偏移 | 长度 |
|---|---|---|---|---|---|
| 1 | `.dyn.text` | PROGBITS | `0x1D000000` | `0x90` | `0xB8D` |
| 2 | `.dyn.data` | PROGBITS | `0x1C000000` | `0x80` | `0x4` |
| 3 | `.shstrtab` | STRTAB | 0 | `0xC1D` | `0x1F` |

该 ELF 只是**描述符 + 3 KB 级桩代码**；真正的固件主体在它**之后**的 `0x102161`–`0x174590`（约 470 KiB），
其中可读到（偏移为解密后的绝对文件偏移，均【验】）：

| 偏移 | 字符串 |
|---|---|
| `0x103AB8` | `"The Movidius MDK default implementation does not support use…"` |
| `0x104030` | `"Camera initialized in SuperSpeed mode"` |
| `0x104190` | `"Board Mv0212 initialized, revision = %lu "` |
| `0x1041C0` | `"Configuring imx214M10MO camera and datapath"` |
| `0x103Axx` | `"../common/components/CamGeneric/arch/ma2x5x/leon/common/src/"` |

→ 确认是 **Myriad2 MA2x5x / MV0212 板级主固件（LEON + CamGeneric/imx214 组件）**，不是测试数据。

> 注意：`e_entry=0` 且段地址是 `0x1C000000/0x1D000000` 这种"外设/内存窗口"式地址，
> 说明它不是给 OS 用的常规可执行 ELF，而是给 **ROM / 一级引导**读的"加载描述符 + 代码块"。
> 具体谁解析它（ROM 或 `ddrInit` 代码）尚未确认。【未】

### 5.4 加密（已完全解出）

`decrypt_glxss_file()`（x86_64 `0x32c0`～`0x376e`）逻辑：

1. 把**内置在 `.so` 里的 RSA-1024 私钥 PEM** 拷到栈上（`.rodata:0x4C35`，886 B，
   `-----BEGIN RSA PRIVATE KEY-----`）；
2. `fopen(keyPath,"rb")` 读 `llvision.enkey`（128 B = 1024 bit），
   `RSA_private_decrypt(128, enc, out, rsa, RSA_PKCS1_PADDING)`（`0x33e5 mov r8d,1`）；
3. 用解出的 **AES 密钥**解 `buf` 尾部 `0x80000` 字节；
4. PKCS#7 去填充（`0x376f: movzx eax,[r12+rbp-1]; sub ebp,eax`），写回 `*outLen`。

**实测复算**（本机 Python 复现，非猜测）：

```
llvision.enkey (128 B RSA 密文)
  --RSA-1024/PKCS#1 v1.5 (内置私钥)-->
"b5Q8JBFRYYIRawl3b5Q8JBFRYYIRawl3\n"   (33 B ASCII)

AES-128-ECB, key = "b5Q8JBFRYYIRawl3"  (前 16 字节)
  作用于 img[0x0F4590 : 0x174590]
  ==> 明文与 0x0F4590 之前的 0xFF000000 填充**逐字节连续**
  ==> 明文尾部出现 "G25_MainFW_1.9.1"、".shstrtab"、".dyn.text"、真实 ELF 头
```

验证强度：错误 key / 错误模式（CBC/CFB/OFB/CTR/AES-256）都无法产生这种连续性；
ECB 解出后 `0xF4590` 前 16 B 与前一 16 B 完全相同（`00 00 00 FF ×4`），
这是**不可伪造的**正确性证据。

> **【验】结论：`G25_MainFW_1.9.1.img` 是"明文头 + 加密尾（最后 512 KiB，AES-128-ECB）"的混合体。**
> 主机必须像原厂 App 一样：读出整个文件 → 就地解密最后 `min(size, 0x80000)` 字节 → 再整包发送。

---

## 6. Android / Kotlin 实现（可直接照抄）

### 6.1 设备发现与权限

```kotlin
object GlxssIds {
    const val BOOT_VID = 0x03e7      // 999
    const val BOOT_PID = 0x2150      // 8528
    const val NORMAL_VID = 0x2e09    // 11785
    const val NORMAL_PID = 0x0030    // 48
}

fun findBootDevice(usbManager: UsbManager): UsbDevice? =
    usbManager.deviceList.values.firstOrNull {
        it.vendorId == GlxssIds.BOOT_VID && it.productId == GlxssIds.BOOT_PID
    }
```

> 必须在 `res/xml/usb_device_filter.xml` 里加上 `03e7:2150`，否则 `openDevice()` 返回 `null`
> （权限靠 `UsbManager.requestPermission()`）。

### 6.2 传输核心（对应 `send_file`）

```kotlin
class GlxssBootLoader(private val usbManager: UsbManager) {

    companion object {
        private const val CHUNK_SIZE   = 1024          // 0x400，硬编码上限
        private const val TIMEOUT_MS   = 10_000        // 0x2710
        private const val EP_OUT_ADDR  = 0x01
        private const val IFACE_INDEX  = 0
    }

    /** 阻塞执行；调用方放到后台线程。返回 true 表示"数据已全部写出"。 */
    fun download(device: UsbDevice, image: ByteArray, onProgress: (Int) -> Unit): Result<Unit> {
        require(device.vendorId == GlxssIds.BOOT_VID && device.productId == GlxssIds.BOOT_PID)

        val conn = usbManager.openDevice(device)
            ?: return Result.failure(IOException("openDevice failed: permission?"))

        return try {
            val iface: UsbInterface = device.getInterface(IFACE_INDEX)

            // 找 bulk OUT（原厂代码固定用地址 1）
            var epOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_OUT) { epOut = ep; break }
            }
            val out = epOut ?: return Result.failure(IOException("no bulk OUT endpoint"))

            // 原厂：libusb_set_auto_detach_kernel_driver(1) + claim_interface(0)
            if (!conn.claimInterface(iface, /* force = */ true))
                return Result.failure(IOException("claimInterface(0) failed"))

            var offset = 0
            val total  = image.size
            while (offset < total) {
                val len  = minOf(CHUNK_SIZE, total - offset)
                // 注意：用带 offset 的重载，避免每块都复制
                val sent = conn.bulkTransfer(out, image, offset, len, TIMEOUT_MS)
                if (sent < 0) {
                    // 原厂语义：
                    //   设备已断开（NO_DEVICE）→ break，当作"写完了"
                    //   其它错误               → 立即失败（-206）
                    if (isDeviceGone(sent)) break
                    return Result.failure(IOException("bulkTransfer failed at $offset/$total, rc=$sent"))
                }
                if (sent == 0) return Result.failure(IOException("bulkTransfer returned 0 at $offset"))
                offset += sent
                onProgress((offset * 100L / total).toInt())
            }
            // 原厂不发 ZLP、不发"启动"命令，直接收尾
            conn.releaseInterface(iface)   // 等价于 libusb_release_interface
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            conn.close()                   // 等价 usb_destory + close
        }
    }

    private fun isDeviceGone(rc: Int) = rc == -1 /*或 errno ENODEV；Android 只给 -1*/
}
```

要点（与反汇编一一对应）：

| 代码 | 依据 |
|---|---|
| `CHUNK_SIZE = 1024` | `0x400` 立即数 |
| `TIMEOUT_MS = 10_000` | `0x2710` |
| `bulkTransfer` 只发不读 | 全库仅 1 处 bulk 调用，方向 OUT |
| 不发 ZLP / 不发启动命令 | 循环结束即返回 |
| `releaseInterface` + `close` | `libusb_release_interface` / `libusb_close` |
| 失败不重试 | native 无重试分支 |

### 6.3 加密镜像处理（若要直接吃原厂 `.img`）

```kotlin
object FirmwareCrypto {
    private const val TAIL_MAX = 0x80000          // 512 KiB

    /** 原厂：AES-128-ECB 解最后 min(size,512KiB)，PKCS#7 去填充。 */
    fun decryptInPlace(image: ByteArray): ByteArray {
        val tail = minOf(image.size, TAIL_MAX)
        val start = image.size - tail

        val aesKey = "b5Q8JBFRYYIRawl3".toByteArray(Charsets.US_ASCII) // 16B
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")        // = PKCS#7
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"))

        val plain = cipher.doFinal(image, start, tail)                 // 自动去填充
        return image.copyOfRange(0, start) + plain
    }

    /**
     * 若要从 llvision.enkey 现场推导 key：
     *   "RSA/ECB/PKCS1Padding" + 内置 RSA-1024 私钥 PEM（libboot.so .rodata+0x4C35）
     *   解出的 33 字节 ASCII 取前 16 字节即 AES key。
     */
}
```

> 指纹式判定（可选）：明文尾一定含 `"G25_MainFW_"`；若 `image` 已经是明文（`0x101485` 处就是 `7F 45 4C 46`），
> 则应跳过解密，否则会把明文再"解"坏。

### 6.4 完整调用时序（App 侧）

```kotlin
// 1. 找到 03e7:2150
val dev = findBootDevice(usbManager) ?: return           // 等 USBMonitor 的 attach 回调更稳

// 2. 读镜像（assets 或 /sdcard/llvision/G25_MainFW_1.9.1.img）
val raw = assets.open("G25_MainFW_1.9.1.img").readBytes()   // 1,525,136 B

// 3. 解密最后 512 KiB（原厂必做）
val payload = FirmwareCrypto.decryptInPlace(raw)

// 4. 后台线程发送（约 1490 次 bulkTransfer）
thread {
    val r = GlxssBootLoader(usbManager).download(dev, payload) { p -> Log.i("BOOT", "$p%") }
    if (r.isSuccess) {
        // 5. 不轮询设备，等 USB attach 广播：2e09:0030 出现即成功
    }
}
```

> 判定成功：**不是** `bulkTransfer` 返回 0，而是 `2e09:0030` 重新枚举（`USBMonitor.onAttach`）。
> 原厂 App 也是这个策略（`LLVisionPlatformService.onConnect` 里按 VID/PID 分派）。

---

## 7. 与开源 `myriad-usbboot` / NCSDK 的对照

`movidius/myriad-usbboot` 仓库目前 404（已被 Intel 归档删除），但其代码在
[NCSDK `api/src/usb_boot.c`](https://raw.githubusercontent.com/movidius/ncsdk/master/api/src/usb_boot.c) 中完整保留。
`libboot.so` 与它**同源到可以逐行对齐**：

| NCSDK 原版 | 亮亮版 `libboot.so` | 异同 |
|---|---|---|
| `Performing bulk write of %u bytes...\n` | `Performing bulk write of %lu byte%s from %s...` | 加了单复数与文件名，**同一句** |
| `Successfully sent %u bytes of data in %lf ms (%lf MB/s)\n` | `Successfully sent %ld bytes of data in %lf ms (%lf MB/s)` | 完全一致 |
| `bulk_chunk_len = endpoint->wMaxPacketSize` | **硬编码 `0x400`** | 改为固定 1024 |
| `write_timeout = DEFAULT_WRITE_TIMEOUT (2000)` | **`0x2710` = 10000** | 放宽 5 倍 |
| `libusb_bulk_transfer(h, endpoint, p, wb, &wbr, timeout)` | 同 | 一致 |
| `usb_open_device()`：`set_configuration(1)` + `claim_interface(0)`，在描述符里找 **第一个 bulk OUT** | `libcommon.so usb_open()`：`get_device_with_fd`+`open`+`auto_detach_kernel_driver`+`claim_interface(0)`，**未调 set_configuration** | Android 适配（走 fd，免配置） |
| `wait_findopen()` 轮询 100 ms × timeout | 移到 Java `USBMonitor` | 分层变化 |
| 发送前**不解析 mvcmd**，只 `fread` 整个文件 | 同，额外做了 `decrypt_glxss_file` | **协议本身完全相同** |
| 设备重启后由调用者重新枚举 | 由 `USBMonitor` 捕获 attach | 一致 |

结论：**协议层（裸 bulk、无 header、无 ACK、1024 B 分块）是 Intel 标准的 Myriad2 USB boot**，
亮亮视野只加了"固件加密"和"Android fd 打开"两件事。因此 NCS1 的 `usbboot`/`ncsdk` 工具若把
`03e7:2150` 作为目标，理论上可以直接喂**解密后的** `.img`。

---

## 8. 已验证 / 推断 / 未知

### 8.1 已验证【验】

1. 端点 `0x01`(EP1 OUT)、每块 ≤1024 B、超时 10000 ms、无 header、无 ACK、无 control 传输、无 ZLP。
2. `libboot.so` = NCSDK `usb_boot.c` 改写版（日志串、结构、常量三重对齐）。
3. 错误码 -201…-206（+ 私有 -207）与 `ResultCode.java` 常量一一对应。
4. `.img` 结构：`MA2x` 头 + TLV 名字记录（`0xA8`）+ 定长记录（`0x9A`）+ 明文一级引导（SPARC，`0x48` 起）+ 明文大块 `0xFF000000` + **最后 512 KiB AES-128-ECB**。
5. 解密链：`llvision.enkey` --RSA-1024/PKCS#1(内置私钥)--> `"b5Q8JBFRYYIRawl3b5Q8JBFRYYIRawl3\n"` --AES-128-ECB(key=`b5Q8JBFRYYIRawl3`)--> 与明文段逐字节连续。
6. 内嵌 ELF32 `EM_SPARC`：`.dyn.data @0x1C000000 (4 B)`、`.dyn.text @0x1D000000 (0xB8D B)`，`e_entry=0`。
7. 镜像内版本串 `G25_MainFW_1.9.1\n` 位于 `0x101474`。

### 8.2 推断【推】

1. TLV 语义：`0xA8` = 名字（`len=strlen+2`）；`0x9A` = `(addr,u32 value)` 形式的 DDR/寄存器初始化对（样本 `0x20FC0028/0x20FC0030` + `0x11111111`）。
2. `0x1C000000/0x1D000000` 是 Myriad2 的内存/外设窗口，由 ROM 或 `ddrInit` 代码消费该 ELF。
3. 大块 `0xFF000000` 是 ARGB 黑（启动画面/显存初始化），非代码。
4. 名字表使用**后缀共享**（`nlockCriticalSection` ← `*lockCriticalSection`、`ors_aux` ← `_global_dtors_aux`）。
5. 设备靠容器内长度字段判断"收完即启动"，因此不需要主机发结束命令。

### 8.3 未知【未】

1. `0x44: 0x11047018` 与 `0x40: 0x8A00` 的确切含义（长度/入口/校验）。
2. `0x9A` 记录第二字段（`0x11111111`）是否真是写入值，还是掩码/延时。
3. 发送完成后设备"自我重启"的触发条件（收满？校验通过？超时？）；无 ACK 可观测。
4. PKCS#7 去填充后应发送 `0x174584` 还是 `0x174574`（两种截断算法都能解释反汇编）；**建议按完整 `0x174590` 发送**，容器自带长度字段，多出的尾字节应被忽略。
5. 真机上 `wMaxPacketSize` 是否为 1024（影响是否需要 ZLP；原厂不发，故照抄即可）。
6. 引导模式进入方式（按键/命令/掉电）——不在本次范围。

---

## 9. 风险提示与恢复

### 9.1 变砖风险

| 风险 | 后果 | 缓解 |
|---|---|---|
| 写错 VID/PID（把 `2e09:0030` 当引导设备） | 数据写进正常模式接口，无意义、可能干扰 UVC | 严格校验 `03e7:2150` |
| 忘记解密尾 512 KiB | 设备收到 `0x0F4590` 之后全是密文 → **校验/启动失败**，多半停留在引导模式（可重刷），不会物理损坏 | 实现 6.3，或直接发"已解密"的镜像 |
| 对已明文镜像重复解密 | 尾部被破坏，同上 | 先探测 `0x101485` 处是否 `7F 45 4C 46` |
| 中途拔线 / 断电 | 一级引导（DDR init）可能只写了一半 → **可能无法再进入 `03e7:2150`**（这是最危险的情形） | 保证供电、不要在传输中拔线；失败后重试前先确认仍能枚举 `03e7:2150` |
| 用错固件（非 G25 / 非 MA2450） | DDR/时钟参数不匹配，设备可能彻底不启动 | 校验 magic `MA2x` + 版本串 `G25_MainFW_` |
| 超时设太小 | 大块写超时 → -206，可重试 | 保持 10 s；不要设 < 2 s |

> Myriad2 的 USB boot ROM 在 **ROM 里**（不可擦写），所以"写坏"通常只会坏在
> `ddrInit`/`main` 的 flash/DDR 内容上，**一般仍可用 USB boot 重新刷回**；
> 但如果一级引导把 flash 引导区写坏且 ROM 的恢复路径依赖它，就可能需要拆机/SPI 编程器。
> 这属于**【推】**，未在真机验证。

### 9.2 恢复步骤（建议写进 App 的"救砖"入口）

1. 断电重启眼镜，观察是否仍出现 `03e7:2150`。
2. 若出现 → 直接重刷（本协议幂等：重复发送完整镜像即可）。
3. 若不出现、但出现 `2e09:0030` → 说明应用区还在，可走正常升级入口再触发一次引导。
4. 若两者都不出现 → 只能硬件级恢复（USB boot 引脚/SPI），超出本文范围。

### 9.3 实现注意事项

* 传输必须在**后台线程**（1490 次调用，主线程会 ANR）。
* 传输期间**不要**重新枚举/热插拔监听去 `openDevice` 同一个设备（会抢 fd）。
* `claimInterface(force=true)` 在部分 ROM 上会 detach 内核驱动，之后必须 `releaseInterface`。
* 发送前后都不要发 `SET_CONFIGURATION`：`libcommon.so` 没做，Android 下反而可能失败。
* 进度回调按字节累计；`bulkTransfer` 可能返回**小于请求**的长度，要按 `sent` 前进（原厂同样处理 `twb += wbr`）。
