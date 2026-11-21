# GLXSS G25 固件提取可行性报告

> **这是"为什么没有固件可 dump"的完整论证与复算记录。** 教程版见
> [第 6 章 · 固件](ch-firmware.md)；引导协议见 [firmware-boot-protocol.md](firmware-boot-protocol.md)。
>
> 对象：亮亮视野 GLXSS G25（Intel Movidius Myriad 2 / MA2450，NCS1 同芯片）
> 样本：`com.llvision.glxss3.camera` v2.3.7（`re/liangliang/app.apk`）
> 设备：Pixel 10 Pro Fold `192.168.3.50:37353`（原厂 App 正常连过眼镜）
> 范围：只做静态分析与只读抓取；**未刷写、未改设备、未操作 LG G8**
> 结论分级：**【验】已复算/有原始输出**、**【推】由代码与数据强推断**、**【未】未确认**

---

## 0. 结论摘要

| 问题 | 结论 | 一句话证据 |
|---|---|---|
| APK 里的 `G25_MainFW_1.9.1.img` 是不是完整固件？ | **是**，它就是主机每次上电写给眼镜的完整镜像 | App 把 asset 原样复制到 `/sdcard/llvision/`，`libboot` 读全文件、解密最后 512 KiB、从 EP1 整包写出；解密后含 `G25_MainFW_1.9.1`、一级引导、`main.elf`、RTEMS 主固件 |
| APK 里还有别的固件/云端 OTA 吗？ | **没有**。只有这一份 `.img`；云端只更新 APK | `assets/` 仅 3 个文件；`res/raw` 仅 2 个 ogg；dex 只有两个 `update.llvision.com/check/*`，返回 `human_version/url` 并下载 APK |
| 能不能从工作中的眼镜读回整片固件？ | **不能（USB 层面）** | 正常模式厂商接口只有 EP 0x01 OUT，没有 IN；全部 vendor control 请求里没有读 flash/内存命令。引导模式主机协议是单向 bulk 写，无读命令、无 ACK |
| 那“当前固件”是什么？ | 就是主机最近一次写进去的那份文件；每次上电都要重新加载 | `dumpsys usb` 历史反复出现 `03e7:2150`（约 1.7 s 后）→ `2e09:0030`，不是从设备 flash 启动 |
| 怎么确认设备上的版本？ | 用 `0xA1/0x17`（HID，24 B）或 App 的 “About GLXSS”；主界面的 `Firmware: G25_MainFW_1.9.1` 是**硬编码**，不能当证据 | `libhid.so` 的 `sensor_get_product_info` 读 16 B 版本串 + 8 B ID；`MainActivity.java:191-196` 直接写死 asset 文件名 |
| 最实用路径 | **APK 提取 + 解密校验 + 保留原始/解密两份**；版本用只读命令确认 | 见 §5；本机手机上的实际文件 hash 与 APK asset 完全一致 |

**一句话**：这不是“从眼镜里 dump 固件”的问题，而是“眼镜每次启动都从主机加载固件”。只要拿到主机侧那份文件（APK asset），就已经拿到了当前固件；设备端没有可用的整片回读通道。

---

## 1. 结论一：APK 里的 img 就是写入设备的完整镜像

### 1.1 主机侧证据链【验】

1. **asset 被原样复制到手机**

   `com/llvision/glass3/appstore/a/a.java:13-15,54-64`：

   ```java
   public static final String a = Environment.getExternalStorageDirectory() + "/llvision";
   public static final String b = a + File.separator + FileUtils.SE_KEY_NAME;
   public static final String c = a + File.separator + "G25_MainFW_1.9.1.img";

   public static void a(Context context) {
       if (!new File(c).exists()) {
           a(context, "G25_MainFW_1.9.1.img", c);   // 逐字节复制 asset
       }
   }
   public static void b(Context context) {
       if (!new File(b).exists()) {
           a(context, FileUtils.SE_KEY_NAME, b);    // llvision.enkey
       }
   }
   ```

2. **路径被传给平台服务，再传给 libboot**

   `ConnectGlassDialog.java:223-233`：

   ```java
   a.a(a.a);  a.b(this);  a.a(this);
   Intent intent = new Intent(this, LLVisionPlatformService.class);
   intent.putExtra(LLVisionPlatformService.KEY_FILM_IMG_PATH, a.c);  // /sdcard/llvision/G25_MainFW_1.9.1.img
   intent.putExtra(LLVisionPlatformService.KEY_KEY_PATH,      a.b);  // /sdcard/llvision/llvision.enkey
   startService(intent);
   ```

   `LLVisionPlatformService.java:404-424` 把这两个路径存进 SharedPreferences；`183-191` 在检测到 `03e7:2150` 时：

   ```java
   int iBootInit = Boot.getInstance().bootInit(c0017b);
   if (iBootInit == 0) {
       Boot.getInstance().bootDownload(mFilmImgPath, mKeyPath, callback);
   }
   ```

3. **手机上的实际文件与 APK asset 完全一致**

   在 Pixel 上只读执行：

   ```
   # ls -l /sdcard/llvision
   -rw-rw---- 1 u0_a301 media_rw 1525136 2026-11-20 20:57 G25_MainFW_1.9.1.img
   -rw-rw---- 1 u0_a301 media_rw     128 2026-11-20 20:57 llvision.enkey

   # sha256sum /sdcard/llvision/G25_MainFW_1.9.1.img /sdcard/llvision/llvision.enkey
   5b4246ba8a738f74be8938e0d174107e058a6303aa69f5538734f081dde68bca  G25_MainFW_1.9.1.img
   04063d14d1bdcbf34df093ae21d0ee12570145369b3ea2e71edbc32ba973676c  llvision.enkey
   ```

   APK 内同名文件：

   ```
   5B4246BA8A738F74BE8938E0D174107E058A6303AA69F5538734F081DDE68BCA  assets/G25_MainFW_1.9.1.img
   04063D14D1BDCBF34DF093AE21D0EE12570145369B3EA2E71EDBC32BA973676C  assets/llvision.enkey
   ```

   `LLVisionPlatformService.xml` 也只指向这两个路径：

   ```xml
   <string name="film_img_path">/storage/emulated/0/llvision/G25_MainFW_1.9.1.img</string>
   <string name="key_path">/storage/emulated/0/llvision/llvision.enkey</string>
   ```

4. **设备确实反复走“引导模式 → 正常模式”**

   `adb -s 192.168.3.50:37353 shell dumpsys usb` 的 `host_manager.connections` 末尾（节选）：

   ```
   device_address=/dev/bus/usb/001/008
   mode=0
   timestamp=1795228239285
   manufacturer=999          # 0x03E7
   product=8528              # 0x2150

   device_address=/dev/bus/usb/001/008
   mode=-1
   timestamp=1795228239857

   device_address=/dev/bus/usb/001/009
   mode=0
   timestamp=1795228241018
   manufacturer=11785        # 0x2E09
   product=48                # 0x0030
   ```

   即：`03e7:2150` 出现约 0.57 s 后断开，再过约 1.16 s 以 `2e09:0030` 重新出现。这与“主机写入固件 → 设备重启 → 正常模式”的流程一致，也说明**每次上电都是主机重新加载**，不是从设备 flash 启动。

> 注意：`a.a(context)` **只在文件不存在时复制**，之后不再校验 hash。所以“App 实际用哪份文件”要以手机上的 `/sdcard/llvision/` 为准；本机这份与 APK 一致。若该文件被替换，App 会直接写替换后的内容（这是一个可被利用/需防范的点）。

### 1.2 libboot 实际做了什么【验】

`libboot.so`（x86_64 版，`apk/lib/x86_64/libboot.so`）对 `03e7:2150` 的完整路径：

```
fopen(path, "rb")                    0x38ca
fseek(END) / ftell / rewind          0x38e5..0x38f8
malloc(size) / fread(whole file)     0x3900..0x391f
decrypt_glxss_file(buf, size, &outLen, keyPath)   0x3947
                                       ↑ 内部：
                                       BIO_new_mem_buf(内置 PEM, 886 B)   0x330b
                                       PEM_read_bio_RSAPrivateKey         0x3331
                                       fopen(keyPath,"rb")                0x3358
                                       RSA_private_decrypt(128B enkey)    0x3409
                                       AES-128-ECB(buf+size-0x80000, 0x80000)  0x3447..0x345b
                                       outLen = size - 16 - buf[size-1]   0x377f
loop: bulk_transfer(EP 0x01, min(remaining,1024), timeout=10000)
                                      0x3b60..0x3bc1（0x400 / 0x2710 立即数）
```

要点：

* **读的是整文件**，没有“只读某段/按需下载”。
* **解密只作用于最后 0x80000 字节**（`lea -0x80000(%rbp,%r12,1),%rsi`，0x3447）。
* 解密后发送长度由 `outLen` 决定，**不是**文件原始大小（见 §1.3）。
* 发送循环只有 `libusb_bulk_transfer`，无 header、无命令、无 ACK、无 ZLP。
* `libboot.so` 中没有 `libusb_control_transfer`，也没有任何 IN 传输。

所以：**主机在 wire 上发出的 = 解密后的 asset（去掉尾部填充），不是原始 APK asset**。

### 1.3 镜像长度与尾部 28 字节【验】

原始 asset：

```
size = 1,525,136 = 0x174590
```

解密最后 `0x80000` 后，尾部是：

```
0x174550  00 00 00 00 a8 0c 00 00 4f 73 4d 56 30 32 31 32
0x174560  2e 63 00 9a 00 0c 18 70 01 00 00 00 a4 00 00 00
0x174570  80 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
0x174580  00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 0c
```

`libboot` 的 `outLen = size - 16 - last_byte`：

```
last_byte = 0x0c = 12
outLen = 0x174590 - 16 - 12 = 0x174574 = 1,525,108
```

`0x174574` 之后的 28 字节是：

```
00 × 27, 0c
```

这是**填充/尾部标记，不是固件内容**。因此：

* 实际发送的镜像长度 = **1,525,108 B（0x174574）**；
* APK asset 多出的最后 28 B 不发送；
* 规范哈希（前 0x174574 字节）：

  ```
  sha256 = beaed6c7a49569544dc816ac97d0a2bb82b213593f45beb2ee8629c2992b9d60
  ```

> 这也解决了原文档 §8.3 的未决项：“应发 0x174584 还是 0x174574”。反汇编与数据都指向 **0x174574**。
> 但如果你要**用原厂 App 重刷**，必须保留原始带 28 B 尾巴的 asset（App 会用 `size-16-last_byte` 自己算）；如果你写自己的 flasher，直接发 0x174574 的明文即可。

### 1.4 版本串确实在解密后的镜像里【验】

原始 asset 里搜不到 `G25_MainFW`（那段是密文）：

```
# strings -a G25_MainFW_1.9.1.img | grep -E 'G25|MainFW|1\.9\.1'
（无输出）
```

解密后：

```
0x101474  "G25_MainFW_1.9.1\n"
0x101485  ELF32 LSB / EM_SPARC / ET_EXEC / e_entry=0
0x4990    "BUILD DATE: Thu Jun  9 06:59:57 EEST 2016"
0x49c0    "GIT VERSION: CLEAN 2016-06-09 06:56:58 +0300 ref: refs/heads/temp_proposed_golden_driver_ma2450"
0x174558  "OsMV0212.c"
```

其它可确认的主固件特征：

```
RTEMS 4.11.99.0 (MYRIAD2/w/FPU/ma2x5x)
"Starting mv0212 board initialize"
"mv0212 board initialized successfully"
"Board Mv0212 initialized, revision = %lu "
"Configuring imx214M10MO camera and datapath"
"GET_EEPROM_CHIP_INFO" / "SET_EEPROM_CHIP_INFO"
"UsbPump_Rtems_DataPump_Startup()!"
```

所以它不是一个空壳/测试数据，而是 **MA2x 容器 + DDR 初始化一级引导 + SPARC `main.elf` + RTEMS 主固件 + 摄像头/USB/显示/传感器组件** 的完整主机加载镜像。它不包含：

* Myriad 2 的 **mask ROM**（芯片内，不可读/不可改）；
* EEPROM 里的**工厂产品信息**（平台 ID、SN、BOM 等，见 §4.3）。

### 1.5 复算脚本与结果【验】

```python
# 依赖：pycryptodome、pyelftools
from Crypto.PublicKey import RSA
from Crypto.Cipher import AES, PKCS1_v1_5
import hashlib

apk = r'C:\Users\WANGZ\Desktop\sshtest\re\liangliang\apk'
raw = open(apk + r'\assets\G25_MainFW_1.9.1.img', 'rb').read()
enc = open(apk + r'\assets\llvision.enkey', 'rb').read()
so  = open(apk + r'\lib\x86_64\libboot.so', 'rb').read()

i = so.find(b'-----BEGIN RSA PRIVATE KEY-----')
j = so.find(b'-----END RSA PRIVATE KEY-----', i)
rsa = RSA.import_key(so[i:j+len(b'-----END RSA PRIVATE KEY-----')])
key = PKCS1_v1_5.new(rsa).decrypt(enc, b'')[:16]
print('AES key =', key)

tail = 0x80000
start = len(raw) - tail
plain = raw[:start] + AES.new(key, AES.MODE_ECB).decrypt(raw[start:])
print('continuity =', raw[start-16:start] == plain[start:start+16])  # True
print('version @0x101474 =', plain[0x101474:0x101485])
print('canonical len =', hex(0x174574))
print('canonical sha256 =', hashlib.sha256(plain[:0x174574]).hexdigest())
open('G25_MainFW_1.9.1.dec.bin','wb').write(plain[:0x174574])
```

实测输出：

```
AES key = b'b5Q8JBFRYYIRawl3'
continuity = True
version @0x101474 = b'G25_MainFW_1.9.1\n'
canonical sha256 = beaed6c7a49569544dc816ac97d0a2bb82b213593f45beb2ee8629c2992b9d60
```

RSA 中间值也可复算：

```
llvision.enkey (128 B)
  --RSA-1024/PKCS#1 v1.5（libboot 内置私钥，PEM 在 .rodata 0x4C35，886 B）-->
"b5Q8JBFRYYIRawl3b5Q8JBFRYYIRawl3\n"  (33 B)
  --取前 16 B-->
AES-128-ECB key = "b5Q8JBFRYYIRawl3"
```

---

## 2. 结论二：APK 没有第二份固件，也没有固件云 OTA

### 2.1 资产清单【验】

APK zip 条目（完整）：

```
res/raw/camera_click.ogg                 4,851
res/raw/llvision_lib_camera_click.ogg    4,851
assets/G25_MainFW_1.9.1.img          1,525,136
assets/llvision.enkey                      128
assets/logo.bin                      1,024,000
```

* `logo.bin` 是 **640×400×4 = 1,024,000 B 的 RGBA 开机画面**，不是固件：

  `LCDService.showLogo()` → `getAssets().open("logo.bin")` → `transferScreen(640,400,...)`（`LCDService.java:344,354`）。

* `llvision.enkey` 是 **RSA-1024 密文**（128 B），解开后是 AES key，不是固件。

* 所有 `lib/*/` 里都没有第二份 `MA2x` / `MainFW` 字符串。

### 2.2 代码/字符串搜索【验】

对整个 `classes.dex` 做字节级搜索：

```
amazonaws     : 0
/firmware     : 0
mainfw        : 0
MainFW        : 2        (只在 G25_MainFW_1.9.1.img / 1.7.4 两处)
\.img         : 2
update.llvision : 2
```

jadx 全文里也没有 `firmware` 下载逻辑；只有：

* `FileUtils.IMG_NAME = "G25_MainFW_1.7.4.img"`（`FileUtils.java:19-20`）——**死代码**，`downloadFirmware()` 从未被调用，assets 里也没有 1.7.4。
* `a/a.java` 固定 1.9.1 文件名。
* `MainActivity.java:191-196` 固定 1.9.1 文件名。

### 2.3 两个云端 URL 都只更新 APK【验】

`com/llvision/util/UpdateManager.java:109,224`：

```
GET https://update.llvision.com/check/G25_camera?tested=1
Header X-API-Key: A72E805D24DDCDE9C2A0663D98F841A4
```

`com/llvision/glass3/appstore/util/UpdateManager.java:69,228`：

```
GET https://update.llvision.com/check/app_storeG25?tested=1
```

响应只取：

```java
String version_get = obj.getString("human_version");
String url         = obj.getString("url");
...
mHashMap.put("name", "GlxssCameraApp" + version_get + ".apk");   // 或 AppStore<ver>.apk
downloadApkThread -> 下载 .apk -> installApk()
```

**没有**“按版本下载 `G25_MainFW_x.y.z.img`”的接口。云端能间接带来新固件的唯一方式是：**发一个新 APK，里面带新 asset**。

---

## 3. 结论三：不能从工作中的眼镜 USB 读回整片固件

### 3.1 正常模式（2e09:0030）的接口/端点【验】

`re/liangliang/usb-descriptors.bin`（752 B，正常模式实测描述符）解析：

```
DEV 2e09:0030  bcdUSB=0210  class=ef/02/01 (IAD)  ep0=64
CONFIG 1 total=734, 7 interfaces
  INTF 0  UVC VideoControl      无端点
  INTF 1  UVC VideoStream       EP 0x81 IN  BULK 512
  INTF 2  HID                   EP 0x83 IN  INT  64 (interval 8)
  INTF 3  厂商 0xff/0x01        EP 0x01 OUT BULK 512   ★ 只有 OUT，没有 IN
  INTF 4  UAC Control           无端点
  INTF 5  UAC Streaming OUT     EP 0x02 OUT ISOC 256
  INTF 6  UAC Streaming IN      EP 0x82 IN  ISOC 220
```

**厂商接口只有 EP 0x01 OUT**，没有 bulk IN 端点。也就是说：即使固件想回传整片镜像，也没有 bulk 通道；只能走 control IN 小包（见 §3.2）。

### 3.2 全部 vendor control 请求枚举：没有“读 flash/内存”

对 `liblcd.so` / `libhid.so` 里所有 `libusb_control_transfer` 调用点做了静态枚举（含参数常量），并与真机 Frida 抓包（`re/liangliang/frida3.log`）交叉核对：

**liblcd.so（厂商 LCD 接口 3）**

| 调用点 | bmReq | bReq | wValue | wIndex | wLen | 方向/用途 |
|---|---|---|---|---|---|---|
| 0x18fa | 0xA1 | 0x01 | 亮度 | 3 | 1 | IN，设亮度（实际是“带返回的写”） |
| 0x1a4e | 0xA1 | 0x02 | roll | 3 | 1 | IN，旋转 |
| 0x1b9a | 0xA1 | 0x03 | 开关 | 3 | 1 | IN，屏开关 |
| 0x1cea | 0xA1 | 0x05 | 模式 | 3 | 1 | IN，显示模式 |
| 0x1e2a | 0xA1 | **0x04** | 0 | 3 | **8** | IN，读屏状态 |
| 0x20c4 | 0xA1 | **0x07** | **0x0100** | 3 | **264** | IN，读 EEPROM 产品信息（见 §4.3） |
| 0x2142 | **0x21** | **0x06** | 0 | 3 | 264 | **OUT，写 EEPROM（不要调用）** |

**libhid.so（HID 接口 2）**

| 调用点 | bmReq | bReq | wValue | wIndex | wLen | 用途 |
|---|---|---|---|---|---|---|
| 0x1d28 | 0xA1 | 0x16 | 0 | 2 | 28 | IN，HID 传感器报告 |
| 0x1f28 | 0xA1 | 0x1A | 0 | 2 | 2 | IN，状态 |
| 0x203e | 0xA1 | 0x04 | 0 | 2 | 12 | IN，传感器数据 |
| 0x211a | 0xA1 | 0x05 | 0 | 2 | 12 | IN，传感器数据 |
| 0x21f6 | 0xA1 | 0x06 | 0 | 2 | 16 | IN，传感器数据 |
| 0x22ea | 0xA1 | **0x17** | 0 | 2 | **24** | IN，**版本串 + 设备 ID**（见 §4.2） |
| 0x240e | 0xA1 | 0x19 | 0/1 | 2 | 1 | LED |
| 0x24da | 0xA1 | 0x18 | 0 | 2 | 1 | 配置 |
| 0x25ae | 0xA1 | 0x10 | 1 | 2 | 1 | 自动亮度 |
| 0x2686 | 0xA1 | 0x11 | 0 | 2 | 1 | 传感器开关 |
| 0x275e | 0xA1 | 0x12 | 0 | 2 | 1 | 传感器开关 |
| 0x2836 | 0xA1 | 0x13 | 0 | 2 | 1 | 传感器量程 |
| 0x290e | 0xA1 | 0x14 | 0 | 2 | 1 | 传感器量程 |
| 0x29e6 | 0xA1 | 0x15 | 0 | 2 | 1 | 传感器配置 |

**结论**：所有 IN 请求的最大长度是 **264 B（EEPROM 产品信息）**，其余是 8/24/28 B 的状态/版本/传感器数据。**没有任何一个请求带“地址 + 长度”参数去读 flash/RAM**；厂商接口也没有 bulk IN。正常模式下不存在整片固件回读接口。

补充：UVC 接口是标准摄像头，App 用的是 `libuvc` 通用实现；抓包里的 `0x21/0x01 wValue=0x303/0x808 wIndex=0x400` 是标准 UVC 类请求，不是固件读取。

### 3.3 引导模式（03e7:2150）：主机协议是“写-only”【验/推】

Intel NCSDK 的 `usb_boot.c`（Myriad2 ROM 引导的官方主机实现）只有：

```c
static int send_file(...) {
    while (twb < file_size) {
        wb = file_size - twb;
        if (wb > bulk_chunk_len) wb = bulk_chunk_len;
        rc = libusb_bulk_transfer(h, endpoint, p, wb, &wbr, write_timeout);
        ...
        twb += wbr; p += wbr;
    }
}

int usb_boot(...) {
    wait_findopen(...);          // 找 03e7:2150，第一个 BULK OUT
    send_file(...);              // 纯写
    libusb_release_interface(h, 0);
    libusb_close(h);
}
```

来源：[NCSDK api/src/usb_boot.c](https://raw.githubusercontent.com/666DZY666/Design-and-Implementation-of-Face-Recognition-based-on-PYNQ/f3e69d39558f1018db0afeeb1aacbdf09628e7ee/ncsdk/api/src/usb_boot.c)。

* 没有 control transfer；
* 没有 IN transfer；
* 没有命令包/ACK；
* 主机甚至不解析 mvcmd，只把文件原样切片写出。

亮亮版 `libboot.so` 与它同源，且**更彻底**：全库唯一的 bulk 调用是 `libusb_bulk_transfer(EP 1, OUT)`，没有 `libusb_control_transfer`，没有任何 IN 调用。

**能否用 ROM 的隐藏命令回读？【未】**：ROM 代码在芯片内，无法静态确认；公开工具/NCSDK 只实现写。若存在未文档化的读命令，需要向 `03e7:2150` 主动发未知 control/bulk 请求去试探——这已经超出“只读”范围，且有被 ROM 误解为写操作的风险。

### 3.4 UART / 调试口【推/未】

解密后的固件里能找到：

```
/dev/console
console_open
.../shared/console/console.c
minor <= uarts
"The Movidius MDK default implementation does not support user input"
```

说明固件**带 RTEMS 串口 console 驱动**，默认可能只输出日志、不接受输入；但：

* 正常模式 USB 描述符里**没有 CDC/ACM**，console 不在 USB 上；
* G25 是成品眼镜，从现有资料看外壳没有对外 UART 口（未拆机确认）；
* 是否在 PCB 测试点/排线上引出 UART，需要拆机确认【未】。

即使有 UART，通常也只能看启动日志，不能 dump 整片 DDR。

### 3.5 SPI / EEPROM【验/推】

固件字符串：

```
SPI_MASTER_0 / SPI_SLAVE_0
SPI_ECX_336
/dev/spi.SPI_ECX_336
eeprom init ,minor is %d
eeprom info rtems_io_read fail
GET_EEPROM_CHIP_INFO,need len is %d
SET_EEPROM_CHIP_INFO
```

说明固件里有 **SPI 主控驱动**、一个名为 `SPI_ECX_336` 的 SPI 设备，以及 **EEPROM 读写路径**；`GET_EEPROM_CHIP_INFO` / `SET_EEPROM_CHIP_INFO` 与 §4.3 的 `0xA1/0x07` / `0x21/0x06` 对应。EEPROM 具体挂在 SPI 还是 I2C、是否为独立芯片，静态字符串不能 100% 确定【未】。

但：

* 这颗 EEPROM 只用于 **256 B 级产品信息**，不可能存 1.5 MB 固件；
* 是否有另一颗 SPI NOR flash 存“golden image/logo”，静态字符串不能确定【未】；
* 即使有，读出来的是 flash 里的旧镜像，不是当前 DDR 里运行的镜像（当前镜像是主机每次上电写入的）。

### 3.6 回读结论

| 通道 | 能否读整片固件 | 说明 |
|---|---|---|
| 正常模式 bulk | 否 | 厂商接口只有 EP 0x01 OUT |
| 正常模式 control | 否 | 最大 264 B EEPROM 产品信息；无地址/长度读命令 |
| 正常模式 UVC/HID | 否 | UVC 是摄像头；HID 是 28 B 报告 + 24 B 版本 |
| 引导模式 03e7:2150 | 否（公开协议） | 主机只写；无 ACK/读命令；隐藏命令未知 |
| UART | 未知 | 固件有 console 驱动；无对外口；需拆机找测试点 |
| SPI/JTAG | 仅硬件 | 可读 flash/内存，但需要拆机、接线、可能改板；读 SPI 也不等于读当前 DDR |

**所以：要“备份当前固件”，不要走设备回读；直接用主机侧那份文件。**

---

## 4. 结论四：如何确认设备上运行的固件版本

### 4.1 两个同名但不同的 ProductInfo 路径【验】

App 里有两个 `ProductInfo` 来源，名字很像，必须区分：

| 路径 | native 方法 | 实际请求 | 返回内容 | Java 侧 |
|---|---|---|---|---|
| **HID / 版本** | `libhid.so` `nativeGetProductInfo` → `sensor_get_product_info` | `0xA1/0x17`，wIndex=2，24 B | 16 B 版本串 + 8 B 设备 ID | `ProductInfo(String,long)` → `softwareVersion` / `glxssId` |
| **LCD / EEPROM** | `liblcd.so` `nativeProductGetInfo` → `product_info_get` | `0xA1/0x07`，wIndex=3，264 B | 8 B 头 + 256 B 产品信息 | `ProductInfo(byte[])` → platformID/productID/firmID/bsnID/psnID/bomID/ispID/firmwareID/resolution/customFields |

对应 Java：

* `HID.java:243-249` → `nativeGetProductInfo()`
* `LCD.java:166-176` → `nativeProductGetInfo()`
* `LCD.java:56-70`：`lcdInit()` 成功后**自动**调用一次 `getProductInfo()`（所以每次连接都会读 264 B EEPROM）
* `LLVisionPlatformService.java:793-795`：AIDL `getProductInfo()` 返回 **HID** 的 `this.g.getProductInfo()`
* `DeviceManager.java:112-121`：Framework 的 `getProductInfo()` 走 AIDL → 平台服务 → **HID**
* `AboutActivity.java:34-40`：用 `DeviceManager.getProductInfo().getGlxssId()/getSoftwareVersion()`，即 **HID 路径**

> 关键区别：`LCD` 路径（`nativeProductGetInfo`）用 `ProductInfo(byte[])` 构造，**`softwareVersion` 永远为 null**，只有 `firmwareID` 等 EEPROM 字段；`HID` 路径（`nativeGetProductInfo`）才用 `ProductInfo(String,long)` 填入 16 B 版本串。不要把 LCD 的 `firmwareID` 当成 `1.9.1` 这种版本号。

### 4.2 `0xA1/0x17`：真正的版本查询【验】

`libhid.so` 的 `sensor_get_product_info`（Thumb 0x22b1）反汇编：

```
r0 = dev->handle
libusb_control_transfer(
    handle,
    request_type = 0xA1,
    bRequest     = 0x17,
    wValue       = 0,
    wIndex       = 2,        // HID 接口
    data         = sp+0x10,  // 24 B 临时缓冲
    wLength      = 0x18,     // 24
    timeout      = 0x1388);  // 5000 ms

if (rc == 24) {
    memcpy(out, tmp, 16);    // 16 B 版本串
    out[16] = 0;             // 补 NUL
    *(u32*)(out+17) = tmp[16];   // 8 B 设备 ID（低/高 32 位）
    *(u32*)(out+21) = tmp[20];
}
```

`nativeGetProductInfo` 随后：

* 用 `NewStringUTF(out)` 得到 16 字符版本串；
* 用 `out+17/out+21` 组成 64 位 `glxssId`；
* 调 `ProductInfo(String,long)`（构造函数签名 `(Ljava/lang/String;J)V` 在 `libhid.so` 0x6229）。

**版本串长度正好 16 字节**，而镜像里唯一的版本串 `G25_MainFW_1.9.1` 也正好 16 字符（第 17 字节是 `\n`）。因此：

> 设备运行 1.9.1 时，`0xA1/0x17` 的 16 B 版本字段应为 ASCII `G25_MainFW_1.9.1`。
> 这是**静态推断**；要 100% 确认，运行一次 §5.3 的只读查询，或打开 App 的 “About GLXSS”。

### 4.3 `0xA1/0x07`：EEPROM 产品信息（含 `firmwareID`）【验】

`liblcd.so` 的 `product_info_get`（Thumb 0x20a1）反汇编：

```
libusb_control_transfer(
    handle,
    request_type = 0xA1,
    bRequest     = 0x07,
    wValue       = 0x0100,   // 调用点把缓冲区首个 u16 设为 0x100；候选语义是“请求 256 B”
    wIndex       = 3,        // 厂商 LCD 接口
    data         = buf,      // 264 B
    wLength      = 0x108,    // 264
    timeout      = 0x1388);  // 5000 ms
```

返回布局（由 `nativeProductGetInfo` 的 `SetByteArrayRegion(buf+8, 256)` 反推）：

```
[0..7]    8 B 设备返回头（不要当产品信息）
[8..263]  256 B 产品信息，即 Java ProductInfo(byte[]) 的 bytes[0..255]
```

`ProductInfo.productInforAnalysis()`（`ProductInfo.java:39-87`）字段偏移：

| 偏移 | 长度 | 字段 | 解析 |
|---|---|---|---|
| 0 | 1 | platformID | `%02x` |
| 1..7 | 7 | productID | ASCII |
| 8..9 | 2 | firmID | 两字节 hex |
| 10..25 | 16 | bsnID | ASCII |
| 26..41 | 16 | psnID | ASCII |
| 42..43 | 2 | bomID | 两字节 ASCII |
| 44..51 | 8 | ispID | ASCII |
| **52..59** | **8** | **firmwareID** | **ASCII（8 字符）** |
| 60..63 | 4 | resolution | 宽/高各 1 B hex |
| 128..255 | 128 | customFields | ASCII |

注意：

* 这是 **EEPROM 里的工厂信息**，`firmwareID` 是 8 字符的 ID，**不一定等于** `1.9.1` 这种语义版本；它也不随主机每次加载的镜像变化（除非量产时写入）。
* 读它用 `0xA1/0x07`（只读，安全）。
* **不要**调用 `0x21/0x06`（`nativeWriteCustomFields`，写 EEPROM）。

### 4.4 `0xA1/0x04` 和 `0xA1/0x16` 不含版本【验】

`0xA1/0x04` 8 B 状态（`liblcd.so` `lcd_get_info` 0x1e00 反汇编 + 抓包）：

```
byte[0:2]  u16 width       = 640
byte[2:4]  u16 height      = 400
byte[4]    bopen (0/1)
byte[5]    luminance (100..254)
byte[6]    rollover
byte[7]    unused/0
```

实测 `80 02 90 01 01 b9 0a 00` → 640×400、开、亮度 0xB9=185、rollover=0x0A=10。
**没有版本字段。**

`0xA1/0x16` 28 B 是 HID 传感器报告（计数 + 加速度/陀螺/时间戳），也不是版本。

### 4.5 主界面的 “Firmware: G25_MainFW_1.9.1” 是硬编码【验】

`com/llvision/glass3/appstore/activity/MainActivity.java:191-196`：

```java
String strSubstring = "G25_MainFW_1.9.1.img";
int iLastIndexOf = "G25_MainFW_1.9.1.img".lastIndexOf(".");
if (iLastIndexOf != -1) {
    strSubstring = "G25_MainFW_1.9.1.img".substring(0, iLastIndexOf);
}
textView.setText(getString(R.string.llvision_sdk_device_versoin, new Object[]{strSubstring}));
```

它取的是**代码里写死的 asset 文件名**，不是 `getProductInfo()`。所以：

* `ui2.xml` 里的 “Firmware: G25_MainFW_1.9.1” **不能证明**设备在跑 1.9.1；
* 但它至少说明原厂把 1.9.1 当作当前发布版本。

### 4.6 `ChipInfoActivity` 是死代码/半成品【验】

`ChipInfoActivity.java:73-190` 的 `TestChipInfoThread`：

* 只构造 `byte[] codeB = new byte[256]` 并填 `loopB`；
* `byte[] readB = Base64.decode("", 1);` —— 空数组；
* `if (0 != 0)` —— 永远 false；
* 从未调用 `mLcdClient` 的读写方法；
* 只打印空日志。

所以它**不能**用来读 SN/版本；不要被类名误导。

---

## 5. 可操作步骤

> 以下命令均可在 `C:\Users\WANGZ\Desktop\sshtest` 下执行。只读步骤不会改设备；刷写步骤仅作说明，**本次不执行**。

### 5.1 从 APK 提取并校验

```powershell
$apk = "re\liangliang\app.apk"
$out = "re\liangliang\fw-out"
New-Item -ItemType Directory -Force $out | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $apk))
foreach ($n in @("assets/G25_MainFW_1.9.1.img","assets/llvision.enkey","assets/logo.bin")) {
    $e = $z.Entries | Where-Object FullName -eq $n
    [IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $out (Split-Path $n -Leaf)), $true)
}
$z.Dispose()
Get-FileHash "$out\G25_MainFW_1.9.1.img","$out\llvision.enkey" -Algorithm SHA256
```

期望：

```
G25_MainFW_1.9.1.img  5B4246BA8A738F74BE8938E0D174107E058A6303AA69F5538734F081DDE68BCA
llvision.enkey        04063D14D1BDCBF34DF093AE21D0EE12570145369B3EA2E71EDBC32BA973676C
```

### 5.2 解密并生成“可发送镜像”

用 §1.5 的 Python 脚本，输出 `G25_MainFW_1.9.1.dec.bin`（长度 0x174574）。校验：

```python
assert len(open('G25_MainFW_1.9.1.dec.bin','rb').read()) == 0x174574
assert open('G25_MainFW_1.9.1.dec.bin','rb').read()[0x101474:0x101485] == b'G25_MainFW_1.9.1\n'
```

建议同时保留：

* `G25_MainFW_1.9.1.img`（原始加密 asset，**重刷用**）；
* `llvision.enkey`；
* `G25_MainFW_1.9.1.dec.bin`（规范明文，**自定义 flasher 用**）；
* 三个 SHA256。

### 5.3 只读查询运行版本（`0xA1/0x17`）

Linux + pyusb，眼镜在正常模式 `2e09:0030`，**不要在 App 正在使用设备时跑**：

```python
import usb.core, usb.util

dev = usb.core.find(idVendor=0x2e09, idProduct=0x0030)
assert dev, "glasses not found (normal mode 2e09:0030)"

# 只做 control IN，不需要 bulk 端点。
# 注意：Linux 的 uvcvideo/hid 内核驱动可能占用接口；若 ctrl_transfer 返回 EBUSY，
# 先停掉占用该设备的进程，或用 usb.util.detach_kernel_driver(dev, 2)（仅主机侧操作）。
try:
    dev.set_configuration()
except usb.core.USBError:
    pass

data = dev.ctrl_transfer(0xA1, 0x17, 0, 2, 24, timeout=5000)
print("raw24 =", bytes(data).hex())
print("version =", bytes(data[:16]).decode("ascii", "replace").rstrip("\x00"))
print("deviceId =", bytes(data[16:24]).hex())
```

期望 `version = G25_MainFW_1.9.1`。

> 如果 Linux 的 `uvcvideo`/`hid` 驱动占用接口导致失败：先 `sudo modprobe -r uvcvideo` 或用 `usb.util.detach_kernel_driver(dev, 0/2)`；不要修改设备。
> 更简单的办法：原厂 App → 设备页 → **About GLXSS**，第二行读的是 HID `softwareVersion`（`AboutActivity.java:34-40`）。

### 5.4 只读读取 EEPROM 产品信息（`0xA1/0x07`）

```python
data = dev.ctrl_transfer(0xA1, 0x07, 0x0100, 3, 264, timeout=5000)
info = bytes(data[8:8+256])
print("platformID  =", info[0:1].hex())
print("productID   =", info[1:8].decode("ascii","replace").strip("\x00"))
print("firmID      =", info[8:10].hex())
print("bsnID       =", info[10:26].decode("ascii","replace").strip("\x00"))
print("psnID       =", info[26:42].decode("ascii","replace").strip("\x00"))
print("bomID       =", info[42:44].decode("ascii","replace").strip("\x00"))
print("ispID       =", info[44:52].decode("ascii","replace").strip("\x00"))
print("firmwareID  =", info[52:60].decode("ascii","replace").strip("\x00"))
print("resolution  =", int(info[60:62].hex(),16), "x", int(info[62:64].hex(),16))
```

这是只读的；**不要**发 `0x21/0x06`。

### 5.5 在手机上确认 App 实际使用哪个文件（只读）

```powershell
adb -s 192.168.3.50:37353 shell su -c "ls -l /sdcard/llvision"
adb -s 192.168.3.50:37353 shell su -c "sha256sum /sdcard/llvision/G25_MainFW_1.9.1.img"
adb -s 192.168.3.50:37353 shell su -c "cat /data/data/com.llvision.glxss3.camera/shared_prefs/LLVisionPlatformService.xml"
```

本机结果已确认：文件 hash 与 APK asset 一致；prefs 指向该路径。

### 5.6 备份/恢复建议

* **备份**：保留 `G25_MainFW_1.9.1.img`（原始 1,525,136 B）+ `llvision.enkey` + 规范明文 + hash。
* **用原厂 App 恢复**：把原始 asset 放回 `/sdcard/llvision/G25_MainFW_1.9.1.img`，保持文件名不变；App 会自己复制/解密/写入。
* **自己写 flasher**：直接发规范明文 `0x174574` 字节到 `03e7:2150` 的 EP 0x01，1024 B/包，10 s 超时；**不要**再 AES 解密。
* **恢复前**确认设备仍能枚举 `03e7:2150`；若卡在 `2e09:0030`，先让 App/设备重新进入引导模式（本次不涉及）。
* 如果设备上跑的是**比 APK 更新**的版本：APK 里的 1.9.1 不是它的备份。此时只能找对应版本的 APK/厂商镜像，或走硬件 dump（不推荐）。

---

## 6. 未知项与风险

### 6.1 未知项

1. **引导 ROM 是否有未公开的回读命令**：公开 NCSDK/`usb_boot.c` 只有 bulk 写；亮亮 `libboot.so` 也只有写。ROM 内部实现不可见，未做主动探测（有风险）。【未】
2. **引导模式描述符**：未抓到 `03e7:2150` 的 `lsusb -v`，不确定是否有 EP 0x81 IN；即便有，也没有已知读协议。【未】
3. **UART/JTAG/SPI 测试点**：固件有 console/SPI 驱动，但 G25 外壳/PCB 是否引出、引脚定义未知；需要拆机。【未】
4. **`firmwareID` 语义**：8 字符 EEPROM 字段，可能是工厂追踪码，不等同于 `1.9.1`。【未】
5. **是否有 SPI NOR 里的 golden image**：固件有 SPI 驱动，但是否存整片镜像未确认；即使有，也不是当前运行镜像。【未】
6. **0xA1/0x17 的真实返回值**：本次未在连接状态下实测（Pixel 当前 `port_manager` 为 `ufp`，眼镜未接在 Pixel 上）；返回值是按代码与镜像版本串推断。【推】

### 6.2 风险

| 风险 | 后果 | 规避 |
|---|---|---|
| 误发 `0x21/0x06` | 改写 EEPROM 产品信息（SN/BOM 等），可能不可逆 | 只发 `0xA1` IN 请求；不要调用 `nativeWriteCustomFields` |
| 误发 `0xA1/0x01/0x02/0x03/0x05/0x10..0x15/0x18/0x19` | 改亮度/显示/传感器配置，通常可恢复但会干扰设备 | 只读查询只用 `0xA1/0x04/0x07/0x17` |
| 对已解密镜像再次解密 | 尾部被破坏，设备启动失败 | 检查 `0x101474` 是否为 `G25_MainFW_`；自定义 flasher 直接发明文 |
| 只保留规范明文、却用原厂 App 重刷 | App 会把最后一个代码字节当 pad，算错长度 | 重刷保留原始 1,525,136 B asset |
| 传输中断电/拔线 | 可能停留在引导模式，极端情况需硬件恢复 | 保证供电；失败后先确认仍能枚举 `03e7:2150` |
| 用错固件（非 G25/MA2450） | DDR/板级参数不匹配，设备不启动 | 校验 `MA2x` magic + `G25_MainFW_` + `OsMV0212` |
| 修改 `/sdcard/llvision/...img` | App 不校验 hash，会直接写修改后的文件 | 刷前对比 SHA256 |

---

## 7. 证据索引（文件/行号/命令）

| 证据 | 位置 |
|---|---|
| asset 复制到 `/sdcard/llvision` | `jadx-out/sources/com/llvision/glass3/appstore/a/a.java:13-15,54-64` |
| 传 img/key 路径给服务 | `.../appstore/activity/ConnectGlassDialog.java:223-233` |
| 服务保存路径并在 03e7 时 bootDownload | `.../sdk/service/LLVisionPlatformService.java:183-191,404-424` |
| `Boot.download` JNI | `.../sdk/boot/Boot.java:60-66` |
| libboot 读文件/解密/发送 | `apk/lib/x86_64/libboot.so` 0x32d0-0x377f、0x3890-0x3c60 |
| 版本串/构建信息 | 解密镜像 0x101474、0x4990、0x49c0、0x174558 |
| 主界面硬编码版本 | `.../appstore/activity/MainActivity.java:191-196` |
| About 页读 HID 版本 | `.../appstore/activity/AboutActivity.java:34-40` |
| HID `getProductInfo` | `.../sdk/hid/HID.java:243-249` |
| LCD `getProductInfo` + 自动调用 | `.../sdk/lcd/LCD.java:56-70,166-176` |
| ProductInfo 字段解析 | `.../sdk/hid/ProductInfo.java:39-87` |
| 平台服务返回 HID 产品信息 | `.../sdk/service/LLVisionPlatformService.java:793-795` |
| DeviceManager AIDL | `.../framework/DeviceManager.java:112-121` |
| ChipInfoActivity 死代码 | `.../appstore/activity/ChipInfoActivity.java:73-190` |
| APK 更新 URL/JSON/APK 下载 | `.../util/UpdateManager.java:89-97,109,224`；`.../appstore/util/UpdateManager.java:69,96-103,228` |
| 1.7.4 死代码 | `.../appstore/util/FileUtils.java:19-20,168-173` |
| 正常模式描述符 | `re/liangliang/usb-descriptors.bin`；解析见 §3.1 |
| 控制请求抓包 | `re/liangliang/frida3.log`（0xA1/0x04/0x05/0x16/0x19 等） |
| 手机实际文件/hash/prefs | Pixel `192.168.3.50:37353` 只读命令（§1.1、§5.5） |
| USB 反复 03e7→2e09 | Pixel `dumpsys usb` 的 `host_manager.connections`（§1.1） |

### 7.1 关于“当前运行 1.9.1”的置信度

* **高置信【推】**：手机上的 `/sdcard/llvision/G25_MainFW_1.9.1.img` 与 APK asset 同 hash；App 的 boot 路径只使用该文件；`dumpsys usb` 里最后一次 `03e7:2150` 在约 1.7 s 后变成 `2e09:0030`，说明写入后设备成功启动。
* **最终确认**：运行 §5.3 的 `0xA1/0x17`，或看 App 的 About GLXSS；预期 `G25_MainFW_1.9.1`。
* **不要**用主界面 “Firmware: G25_MainFW_1.9.1” 作为设备读取证据——它是硬编码。

---

## 8. 附：正常模式/引导模式状态机（便于理解“为什么没有当前固件可读”）

```
上电/复位
  │
  ├─ Myriad2 mask ROM 枚举 03e7:2150（Movidius MA2X5X）
  │     │
  │     └─ 主机（原厂 App / libboot）：
  │          读 asset → 解密尾 512 KiB → 去掉 28 B 尾
  │          → bulk OUT EP1，1024 B/包，10 s 超时
  │          → 设备收完 → 自行重启
  │
  └─ 固件启动 → 重枚举 2e09:0030（UVC + HID + 厂商 LCD + UAC）
        │
        └─ 此时“固件”在 DDR 里；设备没有暴露整片回读接口
```

因此：

* **“当前固件”= 主机最近一次成功写入的镜像**；
* 设备端没有“固件分区”可读（每次上电都重新加载）；
* 想备份，备份主机侧文件；想确认版本，查 `0xA1/0x17`；
* 想拿到“设备上正在跑的、但主机没有的版本”，只能走硬件调试/内存 dump，成本和风险都远高于直接找对应 APK。
