# 原厂 App 固件刷写 USB 序列抓取与 -206 根因分析

- 日期：2026-11-20 23:05（America/New_York）
- 手机：LG LM-G850N（`lineage_mh2lm`，Android 14 / LineageOS，Magisk root，`192.168.3.88`，adb 端口本次运行中从 `38279` 变为 `37003`）
- 眼镜：亮亮视野 GLXSS G25（Intel Movidius Myriad 2 / MA2450）
- 原厂 App：`com.llvision.glxss3.camera` v2.3.7（versionCode 15，targetSdk 25）
- 我们的 App：`com.wzqvip.glxsscast`（源码 `glxss3-touying`）
- 工具：frida-server 17.27.4（arm64）、Python frida 17.27.4、frida-compile + frida-java-bridge、adb

> 先说明本次最重要的限制：**原厂 App 没有在真机上真正执行到“写硬件”那一步**。抓到 `03e7:2150` 后，原厂 App 因缺少 USB 权限无法 `openDevice`；等我们补上权限时，手机重启导致眼镜断电重枚举，我们自己的 App（默认 USB handler）抢先自动刷写成功，眼镜变成 `2e09:0030`，boot 模式消失。
> 但原厂 `libboot.so` 的完整 bulk 序列仍通过“在进程内拦截 `libusb_bulk_transfer`、伪造成功、不写硬件”的方式完整跑通并抓到了，见第 3.3 节。序列由原厂 `libboot.so` 产生，与厂商 APK 内是同一份二进制。

---

## 0. 结论先行

1. **原厂 libboot 的 bulk 序列**：`ep=0x01`（EP1 OUT），`timeout=10000 ms`，每次 **1024 B**，共 **1490 次**（1489×1024 + 最后 372 B），总长 **1,525,108 B**；**没有任何 control transfer、没有 header/ACK/ZLP**。
2. **我们 -206 的主因（P0）**：`BootLoader.kt` 在 `Boot.bootDownload()` 返回后立刻 `conn.close()`。但 `nativeBootDownload` 是**异步**的（`pthread_create` 后立即返回 0），worker 还没开始 bulk，fd/连接就被关掉，后续 `libusb_bulk_transfer` 失败（非 `NO_DEVICE`）→ `-206 "trasfer data fail"`。原厂 App 在整个刷写期间一直持有 `UsbDeviceConnection`，直到回调/设备 detach 才释放。
3. **原厂 payload 与我们不同**：原厂发送的是“前 1,000,848 B 明文 + 尾部 524,288 B AES-128-ECB 解密并去掉末尾 28 B”，总长 **1,525,108 B**；我们（自定义 loader）发送完整文件 **1,525,136 B**。设备可容忍多出的 28 B，自定义 loader 已在 22:57:57 成功刷入并重枚举。
4. **次要差异**：原厂 `usbfs` 参数传 `/dev/bus/usb`（基目录），我们 `BootLoader.kt` 传完整设备路径 `/dev/bus/usb/001/003`。`bootInit` 对两者都返回 0（实测），所以它不是 -206 的首因，但建议对齐。
5. 我们自己的 **`MyriadBootLoader`**（1024 B 分块、EP1、10 s 超时、AES 解密）已在真机上成功刷完；`BootLoader`（厂商 `libboot.so` 的 JNI 路径）失败，问题在 JNI 外层生命周期，不在 native 的包大小/端点/超时。

---

## 1. 目标

1. 在原厂 App 刷写 `03e7:2150` 时抓 `libusb_bulk_transfer` / `libusb_control_transfer` 的完整序列。
2. 与我们的实现对比，解释 `nativeBootDownload` 侧 `-206 "trasfer data fail"`。
3. 给出可落地的修复建议（不改 App 源码，只写文档与建议）。

---

## 2. 原厂刷写链路（代码 + 二进制证据）

### 2.1 Java 侧入口

`LLVisionPlatformService.java` 的 `onConnect` 回调里：

```java
if (usbDevice.getVendorId() == 999 && usbDevice.getProductId() == 8528) {
    if (this.c == null || this.d == null) { ... return; }
    int iBootInit = this.e.bootInit(c0017b);
    if (iBootInit == 0) {
        this.e.bootDownload(this.c, this.d, this.x);   // 异步
        this.i();
        return;
    }
}
```

- `999 = 0x03E7`，`8528 = 0x2150`。
- 固件/密钥路径来自 `Intent` 的 `KEY_FILM_IMG_PATH`（`film_img_path`）和 `KEY_KEY_PATH`（`key_path`），也被写入 `SharedPreferences`。
- `ConnectGlassDialog` 启动时会把 APK `assets/` 下的 `G25_MainFW_1.9.1.img` 和 `llvision.enkey` 复制到 `/sdcard/llvision/`，再 `startService` 带 extras。

### 2.2 `Boot.java` 到 JNI

`Boot.bootInit(c0017b)` 调用：

```java
nativeBootInit(b.g(), b.h(), b.f(), b.i(), b.j(), a.a(b))
// vid, pid, fd, busNum, devAddr, usbfs
```

`com.llvision.glass3.sdk.util.a.a(c0017b)` 对 `/dev/bus/usb/001/003` 去掉最后两段，返回 **`/dev/bus/usb`**。

`boot_jni.cpp` 的 native 日志（真机实测）：

```text
I libUVCCamera: [6473*boot_jni.cpp:41:nativeBootInit]:native boot init vid=9, pid=48, fd=119, busNum=1, devAddr=3, usbfs_str=/dev/bus/usb
```

> `vid=9` 是 native 侧把 `0x2E09` 截成低 8 位后的打印值（`0x2E09 & 0xFF = 0x09`）。原厂 App 同样会得到 9，属于既有实现细节，不是本次差异；bus/addr/fd 都正确。

### 2.3 `libboot.so` 的发送循环（静态还原，已有文档）

`glxss3-touying/docs/firmware-boot-protocol.md` 已给出：

```c
size = file_size;
buf = malloc(size);
fread(buf, 1, size, fp);
decrypt_glxss_file(buf, size, &outLen, keyPath);   // 失败 -207
twb = 0;
while (twb < outLen) {
    wb = min(outLen - twb, 0x400);                 // 1024
    wbr = 0;
    rc = libusb_bulk_transfer(g_dev->handle, 0x01, buf + twb, wb, &wbr, 10000);
    if (rc || wbr != wb) {
        if (rc == LIBUSB_ERROR_NO_DEVICE) break;
        return -206;                               // "trasfer data fail"
    }
    twb += wbr;
}
```

关键常量（x86_64 反汇编 `0x3b68/0x3ba0/0x3b9b`，arm64 对应）：

| 项 | 值 |
|---|---|
| 端点 | `0x01`（EP1 OUT，bulk） |
| 分块 | `min(remaining, 0x400)` = 1024 B |
| 超时 | `0x2710` = 10000 ms |
| control transfer | `.text` 内无 `libusb_control_transfer` 调用 |
| 重试 | 无（除 `NO_DEVICE` 直接 break 当成功） |
| 结束标记 | 无 ZLP、无“启动”命令 |

---

## 3. 本次实测过程与原始证据

### 3.1 设备状态与 USB 权限

开始时眼镜仍是 boot 模式：

```text
$ su -c 'grep -H . /sys/bus/usb/devices/*/idVendor ...'
/sys/bus/usb/devices/1-1/idVendor:03e7
/sys/bus/usb/devices/1-1/idProduct:2150
/sys/bus/usb/devices/1-1/product:Movidius MA2X5X
```

原厂 App 进程（`u0_a224`）启动后反复尝试 `openDevice`，全部失败：

```text
E UsbManager: exception in UsbManager.openDevice
E UsbManager: java.lang.SecurityException: User has not given 10224/com.llvision.glxss3.camera permission to access device /dev/bus/usb/001/003
    at android.hardware.usb.UsbManager.openDevice(UsbManager.java:870)
    at com.llvision.glass3.sdk.usb.b$b.a(USBMonitor.java:1051)
```

根因：原厂 App 从未作为该设备的默认 USB handler 被 attach 拉起，`/data/system/users/0/usb_device_manager.xml` 里只有我们 App 的 preference：

```xml
<preference package="com.wzqvip.glxsscast" user="0">
  <usb-device vendor-id="999" product-id="8528" ... serial-number="03e72150" />
</preference>
```

为了继续，我们用 root 直接调用 `IUsbManager.grantDevicePermission(device, 10224)`（工具：`re/liangliang/tools/grant/GrantUsb.java`，app_process 以 uid 0 运行），成功：

```text
permissions_manager={
  user_id=0
  device_permissions={
    device_name=/dev/bus/usb/001/003
    uids=[10223, 10224]
  }
}
```

同时给原厂 App 补了 `READ/WRITE_EXTERNAL_STORAGE` 和 `RECORD_AUDIO`（targetSdk 25，`pm grant` 有效）。

### 3.2 原厂 App 的 `onStartCommand`（Java hook 证据）

我们成功把原厂 service 拉起，并用 frida-java-bridge hook 了 `LLVisionPlatformService.onStartCommand`：

```text
TRACE onStartCommand id=4 action=null img=/sdcard/llvision/G25_MainFW_1.9.1.img key=/sdcard/llvision/llvision.enkey
TRACE onStartCommand ret=1
```

说明路径和 service 都正确。之后 `USBMonitor.e()` 能枚举到 1 个设备，但 `onStartCommand` 里的 `i.b(device)` 没有把设备送到 `bootInit`（我们后续的 hook 未捕获到 `usb_init`；当时还在排查 `USBMonitor` 的 device filter 行为时，设备就被重启/刷掉了）。

### 3.3 原厂 `libboot.so` 的 bulk 序列（核心抓包）

由于 boot 模式已丢失，无法再让原厂 App 真正写硬件；我们改用**同一份原厂 `libboot.so`** 做进程内抓包：

- 进程：`com.wzqvip.glxsscast`（其 APK 内打包了原厂 `libusb100.so/libcommon.so/libboot.so`，与厂商 APK 同一套）。
- 调用：`com.llvision.glass3.sdk.boot.Boot.bootInit(0x2e09, 0x30, fd, 1, 3, "/dev/bus/usb")`（与厂商 `Boot.java` 的 usbfs 参数一致），随后 `bootDownload("/data/data/.../G25_MainFW_1.9.1.img", ".../llvision.enkey", callback)`。
- 关键：在 `bootDownload` 之前用 Frida `Interceptor.replace` 把 `libusb_bulk_transfer` 整个替换为“记录参数 + `*transferred = length` + 返回 0”，**不调用原函数、不写 USB**。因此序列是原厂 native 代码真实产生的，但数据没有落到眼镜。
- 脚本：`re/liangliang/frida_build/capture_vendor_full.js`；日志：`re/liangliang/vendor_full_capture.log`。

原始关键行：

```text
CAP dev=/dev/bus/usb/001/003 fd=117 bus=1 addr=3
OPEN dev=0xb400007bab617a90
OPEN ret=0
CLAIM iface=0
CLAIM ret=0
CAP bootInit ret=0
CAP bulk addr=0x7ac295ddf4
CAP bulk replaced (no hardware write)
CAP bootDownload ret=0
BULK #1 ep=0x1 len=1024 timeout=10000 ret=0 xfer=1024 t=363 fnv=146e0ffa head=4d413278a81b0000646472496e697448
BULK #2 ep=0x1 len=1024 timeout=10000 ret=0 xfer=1024 t=363 fnv=892c481f head=1c2006d6446217900c20109204201094
BULK #3 ep=0x1 len=1024 timeout=10000 ret=0 xfer=1024 t=364 fnv=c855212d head=08e0c38101000a90003c040314611082
BULK #4 ep=0x1 len=1024 timeout=10000 ret=0 xfer=1024 t=364 fnv=fdbf439e head=58bfe39de23f0682ff6008820560a080
...
BULK #1488 ep=0x1 len=1024 timeout=10000 ret=0 xfer=1024 t=1108
BULK #1489 ep=0x1 len=1024 timeout=10000 ret=0 xfer=1024 t=1108
BULK #1490 ep=0x1 len=372  timeout=10000 ret=0 xfer=372  t=1109 fnv=4d306acd head=00000000000000000000000000000000
CAP onSuccess calls=1490 total=1525108 gh=4d306acd
CAP restore bulk
CAP done
```

**结论：**

- 调用次数：**1490**。
- 包长序列：`#1..#1489 = 1024 B`，`#1490 = 372 B`。
- 端点：全部 `0x01`。
- 超时：全部 `10000 ms`。
- control transfer：**0 条**（脚本同时 hook 了 `libusb_control_transfer`，日志中没有任何 `CTRL` 行；`libusb_open` / `libusb_claim_interface(0)` 各一次，`ret=0`）。
- 返回码：替换函数固定返回 0、`xfer=len`；这是为了不写硬件。真实设备上原厂代码要求 `rc==0 && wbr==wb`，否则 -206。
- 数据：第 1 包头部 `4d 41 32 78 ...` 即固件文件开头的 `MA2x`，说明从文件偏移 0 原样发送；整个 payload 的 FNV-1a 为 `0x4d306acd`。
- 时间间隔：本次是拦截模拟，`gap` 为 JS 日志开销（0–1 ms），**不代表真实 USB 间隔**。真实间隔参考 3.5 的成功刷写：整个 1.5 MB 约 **460 ms**。

> 抓包日志里没有 `CTRL`，但 `libusb_open`/`claim_interface` 仍会经过 libusb 内部的 descriptor 读取（不是 `libusb_control_transfer` 导出函数）。对“应用层协议”而言，原厂在 bulk 之前/之后**没有额外的 control 请求、复位或延时**。

### 3.4 原厂 payload 的精确组成（已用 FNV 对齐）

把抓到的 1,525,108 B 与我们本地文件比对，唯一匹配的构造是：

```text
payload = raw[0x000000 : 0x0F4590]                    // 1,000,848 B 明文
        + AES128_ECB_decrypt(raw[0x0F4590 : 0x174590], key)[0 : -28]   // 524,288 - 28 = 524,260 B
total  = 1,000,848 + 524,260 = 1,525,108 B
key    = "b5Q8JBFRYYIRawl3"
```

验证脚本 `re/liangliang/tools/check_vendor_payload.py` 输出：

```text
file 1525136
raw[:-28]                 1525108  fnv 0xf6bda8f6
raw[:start]+dec           1525136  fnv 0xc8325799
raw[:start]+dec[:-28]     1525108  fnv 0x4d306acd   <-- 与抓包 gh 完全一致
```

说明：

- 原厂确实对尾部 `0x80000` 做了 AES-128-ECB 解密，然后**丢掉解密结果末尾 28 B**（不是简单 PKCS#5 去 padding；解密尾块以 `...000c` 结束，原厂按固定 28 B 截断）。
- 我们 `MyriadBootLoader` 发的是完整 1,525,136 B（多 28 B），设备接受并成功启动；说明设备按容器内长度解析，末尾多出的 28 B 无影响。
- `llvision.enkey` 是 128 B 的密钥材料，不是明文 AES key；native 内部有固定 key/解包逻辑。复现时直接用 `b5Q8JBFRYYIRawl3` 即可。

### 3.5 我们 App 的真实刷写结果（boot 模式消失的原因）

22:57 手机重启后，眼镜重新上电，仍是 `03e7:2150`。由于我们 App 是该设备的默认 USB handler，开机 attach 时系统自动拉起 `com.wzqvip.glxsscast` 并授予 USB 权限，App 的 **`MyriadBootLoader`** 自动开始刷写：

```text
11-20 22:57:57.323  5914  6185 I MyriadBoot: decrypted tail: cipher=524288 plain=524288
11-20 22:57:57.783     0     0 I usb 1-1 : USB disconnect, device number 2
11-20 22:57:58.643     0     0 I usb 1-1 : new high-speed USB device number 3 using xhci-hcd
11-20 22:57:58.795     0     0 I usb 1-1 : New USB device found, idVendor=2e09, idProduct=0030
11-20 22:57:58.795     0     0 I usb 1-1 : Product: LLVISION G25 Firmware
```

- 从解密完成到 USB disconnect 仅 **460 ms**，即 1490 次 1024 B bulk 在 460 ms 内发完（平均 ~0.31 ms/包）。
- 之后正常重枚举为 `2e09:0030`，设备可用（App 显示“已连接: LLVISION G25 Firmware”）。
- **这证明“1024 B 分块 + EP1 + 10 s 超时 + 完整镜像”本身是可以工作的**；`-206` 不是包大小/端点/超时问题。

### 3.6 之前那次失败（`off=1058816 ret=-1`）

在 22:45–22:46，`MyriadBootLoader`（不是 `BootLoader`）曾在 offset `1,058,816` 处失败，并重试 4 次：

```text
11-20 22:46:04.752  26137 26642 W MyriadBoot: bulk fail off=1058816 retry=1
11-20 22:46:14.992  26137 26642 W MyriadBoot: bulk fail off=1058816 retry=2
11-20 22:46:25.232  26137 26642 W MyriadBoot: bulk fail off=1058816 retry=3
11-20 22:46:35.471  26137 26642 W MyriadBoot: bulk fail off=1058816 retry=4
```

`1,058,816 = 0x102800`，已经进入尾部加密区（`0x0F4590` 之后 57,968 B）。当时代码/设备状态有几个异常因素：

- 同一时间段原厂 service 被拉起，`USBMonitor` 在重复 `openDevice`（虽然因权限失败，但会对同一 USB 设备产生打开/关闭竞争）。
- 之前很可能已经有一次 `BootLoader`（厂商 libboot）的 `-206` 尝试，设备侧 USB 状态未复位；手机重启（USB 断电）后同样的 `MyriadBootLoader` 一次成功。
- 这更像是“设备/接口状态 + 多进程抢设备”导致的传输失败，而不是分块协议错误。

---

## 4. `-206` 根因分析

### P0（主因）：异步 `bootDownload` 返回后立刻 `close()` 连接

`BootLoader.kt` 的代码（`glxss3-touying/app/app/src/main/java/com/wzqvip/glxsscast/usb/BootLoader.kt`）：

```kotlin
val conn = usb.openDevice(dev)          // 35
...
Thread {
    try {
        val r = Boot.bootInit(...)      // 45
        ...
        val r2 = Boot.bootDownload(...) // 51, 异步：立即返回 0
        Log.i(TAG, "bootDownload ret=$r2")
        if (r2 != 0) onDone(false, ...)
    } finally {
        conn.close()                    // 67-72, 在 bootDownload 返回后立刻执行
    }
}.start()
```

我们在真机上验证了 `nativeBootDownload` 的异步行为（用不存在的文件，避免任何 bulk 写）：

```text
DLG bootInit ret=0
DLG bootDownload ret=0 after 1ms
DLG conn.close() called immediately
DLG onFail code=-203 msg=open file fail
```

即：

1. `bootDownload` 在 **1 ms 内返回 0**，只是 `pthread_create(boot_worker)`。
2. worker 随后才 `fopen` / `fread` / `decrypt` / 第一次 `libusb_bulk_transfer`。
3. 我们的 `finally` 在 worker 开始前就把 `UsbDeviceConnection` 关了（fd 关闭；即使 libusb 内部有 dup，Android 侧 connection 生命周期也已被提前结束）。
4. worker 的 `libusb_bulk_transfer` 返回负值（通常是 `-1 LIBUSB_ERROR_IO` 或 `-4` 之外的错误；`NO_DEVICE` 会 break，不会 -206），`rc || wbr != wb` → **-206**。

原厂 App 的生命周期完全不同：`C0017b`（持有 `UsbDeviceConnection`）由 `USBMonitor` 的 map 持有，直到设备 detach/`usb_destory`；`bootDownload` 返回后**不会**关闭连接。它只依赖 `BootCallback.onSuccess/onFail` 来结束。

> 这同时解释了为什么“`nativeBootInit` 返回 0、`nativeBootDownload` 打开文件成功，但随后 -206”：init 和文件打开都发生在 close 之前/独立于 close，真正失败的是 worker 的第一次（或后续）bulk。

### P1：多进程/多入口抢同一个 USB 设备

- 原厂 `LLVisionPlatformService` 的 `USBMonitor` 每 2 s 轮询并 `openDevice`；
- 我们的 App 也会在 USB attach/开机时自动 `openDevice` 并刷写；
- 两个 App 同时 `claimInterface(0)` 时，后到者可能 `LIBUSB_ERROR_BUSY`/`IO`；同一个 usbfs 设备的 open/close 也会干扰正在进行的 transfer。
- 实测 22:45–22:46 的 `off=1058816 ret=-1` 就发生在原厂 service 被拉起、反复 open 设备的窗口内。

**修复方向**：刷写期间必须“单 owner”：

- 暂停/停止原厂 `LLVisionPlatformService`（或至少不要让它轮询 03e7:2150）；
- 我们 App 关闭“开机自动刷写/USB attach 自动刷写”，改为用户显式点击；
- 刷写线程内 `synchronized` 锁，禁止并发 flash；
- 刷写期间不要做 UVC/投屏的 `openDevice`。

### P2：`usbfs` 参数不一致（次要）

| | 原厂 | 我们 `BootLoader.kt` |
|---|---|---|
| `usbfs` | `/dev/bus/usb`（`a.a()` 去掉最后两段） | `/dev/bus/usb/001/003`（完整设备路径） |

实测在正常模式设备上，两种参数 `bootInit` 都返回 0，`open`/`claim` 都成功：

```text
INIT usb_init ... usbfs="/dev/bus/usb"        -> ret=0, claim ret=0
INIT usb_init ... usbfs="/dev/bus/usb/001/003" -> ret=0, claim ret=0
```

因此它**不是** -206 的首因；但为保持和原厂一致、避免不同 libusb 版本/路径解析差异，建议改为 `/dev/bus/usb`。

### P3：失败后的设备状态 / 重试策略

- `libboot` 失败后不会自动 `usb_destory` 复位；我们的 `BootLoader` 也没有在 `onFail` 里 `bootDestroy()` 再重开。
- 22:45 的失败在 offset `1,058,816` 反复重试 4 次都失败，手机重启（USB 断电）后同样代码一次成功 → 设备侧状态/接口 claim 未复位是现实存在的。
- 建议：失败后 `Boot.bootDestroy()` + `conn.close()`，重新 `openDevice`/`bootInit` 再试；连续失败则提示用户给眼镜断电重插（或手机重启 USB）。

### 不是原因的部分（已排除）

- **包大小**：原厂就是 1024 B；我们的 `MyriadBootLoader` 也是 1024 B，且成功。
- **端点**：原厂 `0x01`；我们也是 `0x01`。
- **超时**：原厂 10000 ms；我们也是 10000 ms。
- **缺少 control/复位/延时**：原厂抓包 0 条 control，传输前只有 `open`+`claim(0)`，没有 `set_configuration`/`reset`。
- **固件内容**：抓包 payload FNV 与“原厂解密算法”完全一致，不是发错文件。

---

## 5. 与我们实现的对比

| 项 | 原厂 App（`libboot.so`） | 我们 `BootLoader`（厂商 libboot JNI） | 我们 `MyriadBootLoader`（自研） |
|---|---|---|---|
| 分块 | 1024 B | 由 libboot 内部 1024 B | 1024 B |
| 端点/超时 | EP1 OUT / 10000 ms | 同左 | 同左 |
| payload 长度 | 1,525,108 B | 同左（native 解密后） | 1,525,136 B（完整文件） |
| usbfs | `/dev/bus/usb` | `/dev/bus/usb/001/003` ❌ | N/A（Java bulkTransfer） |
| 连接生命周期 | `C0017b` 一直持有到回调/detach | `bootDownload` 返回后立即 `close()` ❌ | 循环结束/回调后才 `close()` ✅ |
| 异步处理 | 等 `BootCallback` | 把 `bootDownload` 当同步调用 ❌ | 同步循环，天然正确 |
| 失败重试 | 无（NO_DEVICE break） | 无；且 close 后无法重试 ❌ | 3 次重试（同一连接）⚠️ |
| 单 owner | 由 service 统一管理 | 与原厂 service/自动刷写竞争 ❌ | 同样有竞争风险 ⚠️ |
| 实测结果 | 未落硬件（权限/时序原因） | 用户报 -206 | **22:57:57 成功，2e09:0030** ✅ |

---

## 6. 修复建议（按优先级）

### 6.1 最小修复：不要在 `bootDownload` 返回后关闭连接

把 `conn.close()` 从 `finally` 移到 `BootCallback.onSuccess/onFail`，并保证 `conn` 被强引用到回调：

```kotlin
fun flash(...) {
    val conn = usb.openDevice(dev) ?: return onDone(false, "open fail")
    val fd = conn.fileDescriptor
    val (bus, addr) = parseBusAddr(dev.deviceName)
    val usbfs = "/dev/bus/usb"                 // 对齐原厂
    Thread {
        val r = Boot.bootInit(dev.vendorId, dev.productId, fd, bus, addr, usbfs)
        if (r != 0) { conn.close(); onDone(false, "bootInit $r"); return@Thread }
        Boot.bootDownload(imgPath, keyPath, object : BootCallback {
            override fun onSuccess() {
                Boot.bootDestroy()
                conn.close()                   // 只在回调里关
                onDone(true, "ok")
            }
            override fun onFail(code: Int, msg: String?) {
                Boot.bootDestroy()
                conn.close()
                onDone(false, "fail $code $msg")
            }
        })
        // 这里绝对不要 conn.close()
    }.start()
}
```

注意：`Boot.bootDownload` 返回 0 只代表“线程已创建”，不代表成功；成功/失败只由 callback 决定。现有代码 `if (r2 != 0)` 的判断几乎没有意义（只有 `-201` 之类同步错误才会非 0）。

### 6.2 对齐 `usbfs`

```kotlin
private fun usbfsBase(deviceName: String): String {
    val parts = deviceName.trim('/').split('/')
    return if (parts.size > 2) parts.dropLast(2).joinToString("/", prefix = "/") else "/dev/bus/usb"
}
val usbfs = usbfsBase(dev.deviceName)   // /dev/bus/usb
```

### 6.3 刷写单 owner / 互斥

- 刷写入口加 `AtomicBoolean`/`synchronized`，禁止并发。
- 刷写前：
  - `am force-stop com.llvision.glxss3.camera`（或至少停止其 `LLVisionPlatformService`）；
  - 关闭我们 App 的 USB attach 自动刷写；
  - 避免 UVC/投屏同时持有设备。
- 刷写后再恢复。

### 6.4 直接使用已验证的 `MyriadBootLoader`

- 22:57:57 的实测已经证明它的“1024 B × 1490 + AES 解密 + 完整 1,525,136 B”可以成功。
- 如果暂时不想改 `BootLoader` 的 JNI 生命周期，可以把 `MyriadBootLoader` 作为默认路径，`BootLoader` 仅作 fallback。
- `MyriadBootLoader` 唯一的小差异是末尾多 28 B（设备容忍）；若想完全对齐原厂，可在解密后截掉末尾 28 B（`dec.size - 28`），但非必需。

### 6.5 失败恢复

- `onFail` 后：`Boot.bootDestroy()` → `conn.close()` → 重新 `openDevice`/`bootInit` 再试一次；不要在同一 fd 上继续 bulk。
- 连续失败：提示用户给眼镜断电重插 / 重启手机 USB；本次实测手机重启后同代码即成功。
- 刷写前校验 `MA2x` magic 和 `G25_MainFW_` 版本串，避免发错镜像。

---

## 7. 复现与验证

### 7.1 抓原厂 libboot 序列（不写硬件）

1. 眼镜在正常模式或 boot 模式均可（本方法不会真正 bulk 写）。
2. 把固件/密钥放到 App 可读目录（注意 SELinux context，见 7.2）。
3. `frida-server` 启动，attach 到进程。
4. 运行 `re/liangliang/frida_build/capture_vendor_full.js`（编译产物 `capture_vendor_full.bundle.js`）。
5. 日志中应看到 1490 条 `BULK #... ep=0x1 len=1024/372`，无 `CTRL`，最后 `onSuccess calls=1490 total=1525108 gh=4d306acd`。

### 7.2 SELinux 小坑

把文件 `su cp` 到 `/data/data/<pkg>/files/` 后，必须把 SELinux context 改成该 App 的 categories，否则 native `fopen` 返回 `-203`：

```sh
chcon u:object_r:app_data_file:s0:c223,c256,c512,c768 \
  /data/data/com.wzqvip.glxsscast/files/G25_MainFW_1.9.1.img \
  /data/data/com.wzqvip.glxsscast/files/llvision.enkey
```

### 7.3 给原厂 App 授予 USB 权限（root）

```sh
# 编译 GrantUsb（已生成 dex）
adb push re/liangliang/tools/grant/dex/classes.dex /data/local/tmp/grant.dex
adb shell su -c 'CLASSPATH=/data/local/tmp/grant.dex app_process /system/bin com.tool.GrantUsb 10224'
```

### 7.4 证据文件

- `re/liangliang/vendor_full_capture.log`：1490 条完整 bulk 序列 + FNV 汇总（本次核心证据）。
- `re/liangliang/vendor_bulk_capture.log`：早期同序列（路径不同）。
- `re/liangliang/boot_flash_success.log`：`MyriadBoot` 成功刷写、`03e7:2150 → 2e09:0030` 的内核日志。
- `re/liangliang/bootinit_probe_native.log`：`usb_init`/`libusb_open`/`claim_interface` 参数。
- `re/liangliang/trace_java.log`：原厂 `onStartCommand` 带路径的 Java trace。
- `re/liangliang/frida_build/capture_vendor_full.js`：抓包脚本（替换 `libusb_bulk_transfer`）。
- `re/liangliang/tools/grant/GrantUsb.java`：root 授予 USB 权限工具。
- `re/liangliang/tools/check_vendor_payload.py`：payload/FNV 对齐脚本。

---

## 8. 限制与未决

1. **原厂 App 未在真机上写硬件**：boot 模式被我们 App 的自动刷写抢先消费；原厂序列是通过同一份 `libboot.so` + `libusb_bulk_transfer` 拦截得到的，数据未落 USB。若必须“原厂 App 亲自写成功”的证据，需要让眼镜重新进入 `03e7:2150`（断电/重启 USB，或按硬件恢复流程），并在原厂 App 启动前先 attach Frida、授予权限，同时关闭我们 App 的自动刷写。
2. **真实 USB 调用间隔**未在本次拦截中测量；460 ms/1490 次来自我们自定义 loader 的真实成功刷写，可作为量级参考。
3. `usbfs` 完整路径是否会在真实 bulk 阶段造成差异，尚未在 boot 模式验证；`bootInit` 两种都成功，故列为次要。
4. native 日志把 `vid` 打印成 9（`0x2E09 & 0xFF`），原厂同样如此；不影响协议，但说明 native 参数存在 8 位截断的既有实现细节。
5. 本次未修改任何 App 源码；只新增/修改了抓包脚本、root 工具和本文档。眼镜目前为 `2e09:0030` 正常模式，可正常使用。

---

## 附录 A：原厂 bulk 包长序列表

| 包序号 | 长度 (B) | 端点 | timeout | 累计 (B) |
|---|---|---|---|---|
| 1 | 1024 | 0x01 | 10000 | 1024 |
| 2 | 1024 | 0x01 | 10000 | 2048 |
| ... | ... | ... | ... | ... |
| 1489 | 1024 | 0x01 | 10000 | 1,524,736 |
| 1490 | 372 | 0x01 | 10000 | 1,525,108 |

- 总调用：1490
- 总字节：1,525,108
- 平均每包：1023.56 B
- 端点：全部 `0x01`
- 控制传输：0
- 返回码：拦截运行时全部 `ret=0/xfer=len`；真实设备要求 `rc=0 && xfer=len`，否则 `-206`
