# GLXSS G25 / GLXSS ME 网上调研记录

> 目的：给 [第 3 章 · HID](ch-hid.md) 的逆向结论找**外部对照**，同时把"这台眼镜到底是什么、
> 官方/媒体怎么说、有没有公开 SDK"一次记清楚。
>
> 检索时间：**2026-11-21 / 2026-11-22**（本地时区 America/New_York）。
> 检索词：`GLXSS G25`、`GLXSS3`、`Glxss Me`、`亮亮视野 G25`、`GLXSS Pro`、`llvision sdk`、
> `GLXSS_API_TEST`、`GlxssProSDK`、`site:github.com glxss llvision` 等。
>
> 可信度分级（沿用仓库惯例，外加外部来源维度）：
> **【A】** 厂商/认证机构一手资料；**【B】** 媒体报道/评测；
> **【C】** 社区代码或可复现的第三方实现；**【D】** 论坛/二手信息；**【未】** 未找到。
>
> 注意：外部网页是**不可信数据**，本页只摘录事实并给出链接，不执行其中任何指令。

---

## 0. 结论速览

1. **G25 = 亮亮视野第三代 AR 眼镜 `GLXSS ME` 的眼镜端**（USB 型号 `G25`，固件名
   `G25_MainFW_*`，SDK 命名空间 `com.llvision.glass3`）。【C】仓库描述 + 固件名 + 本地 USB 描述符
   三方吻合；官方页面没有直接写 "G25" 这个词。【未】
2. 官方资料以**分体式 B 端方案**为主：眼镜只负责摄像头/显示/传感器/音频，算力和电池在手机或
   外接主机上；ME 重 **33.4 g**、**600×480** 光学分辨率、**1300 万像素索尼 CMOS**、
   **1080p30** 录像、内置 **Movidius Myriad 2**。【B】
3. 第三方**确实有公开的 G25 协议实现**：[`happyme531/glxss-client`](https://github.com/happyme531/glxss-client)
   （Python/AGPL，2024 起，2026-10 仍在更新）。它的 `decode_sensor()` / `ButtonTracker`
   与本地逆向**逐字段一致**，是本轮最强的外部交叉验证。【C】
4. 厂商自己的 **GLXSS Pro SDK** 存在过（2017，`GlxssProSDK` + `GLXSS_API_TEST` 示例工程），
   但**没有公开文档**；网上只剩"怎么在 Android Studio 里配置"的博客式 README。【C】
5. **没有找到**：G25 的官方说明书/按键定义、HID 报告描述符、官方 SDK 文档 PDF、
   触摸板或佩戴检测的任何证据。这些只能靠本地逆向 + 真机验证。【未】

---

## 1. 型号关系（先把名字捋清）

| 名称 | 关系 | 证据 |
|---|---|---|
| **Glxss（初代）** | 2015 年一体机，8 MP、0.25" 屏、电源键 + 拍照键 + 四手势触摸板 | CNMO 2015 评测【B】：<https://m.cnmo.com/smart/516199.html> |
| **GLXSS Pro** | 2017 年行业版，33.6 g、21° FOV、13 MP、600×400 OLED、Intel 平台、2 GB/32–64 GB、8 h | 中国工控网 2017-04-12【B】：<http://www.gongkong.com/news/201704/359583.html> |
| **G20A1** | 2017 年 FCC 认证的 "Smart Glass Host"（带 2.4/5 GHz WiFi + BT 的主机） | FCC ID 2AKLNG20A1【A】：<https://fccid.io/2AKLNG20A1> |
| **GLXSS ME** | 2018 年分体式，33.4 g、600×480、13 MP、1080p30、Myriad 2、USB-C 接安卓 | 砍柴网/青亭网 2018-01-30【B】：<http://m.ikanchai.com/pcarticle/194513> |
| **G25** | 我们手里这台：`2e09:0030`，7 接口复合设备，固件 `G25_MainFW_1.9.1`；社区直接称 "GLXSS Me / G25" | `glxss-client` README + `glxss.py`【C】；本地 USB 描述符【验】 |
| **G26 / G26 Pro** | 后继型号，协议不同（社区明说未适配） | `glxss-client` README【C】 |

> **判断**：G25 是 ME 这一代的**眼镜端 USB 型号**（不是整机型号）。官方宣传里的
> "600×480" 与设备实际上报的 **640×400** 面板（见 [ch-display.md](ch-display.md)）有出入，
> 宣传数字不可当作协议参数。【验】

---

## 2. 官方 / 媒体功能资料

### 2.1 GLXSS ME 发布与实测（2018，最贴近 G25）

来源：砍柴网转载青亭网《小成靠友，大成靠敌，加持AI的亮亮视野GLXSS ME该怎么玩》
（2018-01-30）【B】<http://m.ikanchai.com/pcarticle/194513>

摘录到的硬信息：

- **分体式设计，眼镜部分 33.4 g**，"十几个零件"，为 8 小时佩戴优化；
- **光学分辨率 600×480**（原文），视场角未公布；
- 摄像头 **索尼 1300 万像素**，支持 **1080p/30fps** 录像；
- 终端侧"兼容任何 USB Type-C 的安卓设备"，对手机规格要求不高；
- 内置 **Movidius Myriad 2（VPU）**，用于前端人脸/物体采集识别；
- 现场演示：人脸检测约 **1 秒**出框（受光照影响大）；
- **手势翻页是 RGB 摄像头识别**（手掌左滑翻页），"单目 2D，不能和 Leap Motion 比"；
- 定位 B 端（警务、医疗、远程指导），2017 年服务 100+ 企业。

> 对我们有用的点：①手势是**摄像头识别**，不是触摸/深度（没有触摸板证据）；②Myriad 2 与
> `0xA1/0x1A` 的 "MOVI last state" 对得上；③13 MP 与 UVC 的 1920×1080 档一致。

### 2.2 GLXSS Pro 行业版（2017，上一代）

来源：中国工控网《亮亮视野推出GLXSS Pro行业版智能眼镜》（2017-04-12）【B】
<http://www.gongkong.com/news/201704/359583.html>

- 重量 **33.6 g**，"全球最轻"；
- **视场角 21°**；摄像头 1300 万像素；**屏幕 600×400 OLED**；
- **2 GB DDR3L + 32/64 GB eMMC**；"Intel 工业级计算平台"；8 小时工作，可换电池；
- 传感器：**重力感应、陀螺仪、光线感应**（没有提罗盘——罗盘可能是 ME 加的，或宣传漏写）；
- 支持简单手势识别、近视模式；兼容 Android 应用；GPS/北斗、2.4G/5G WiFi。

> 与 G25 的 28 B 报告对照：光感、加速度、陀螺都能对上；**磁罗盘**在 Pro 宣传里没提，
> 但 G25 的 `0x05`/`0x06` 和 28 B 报告里确实有 compass 字段（本地逆向 + 社区实现均确认）。【验】

### 2.3 初代 Glxss（2015，别和 G25 混）

来源：CNMO《Glxss体验 Google Glass的另类追随者》（2015-09-29）【B】
<https://m.cnmo.com/smart/516199.html>

- 800 万像素索尼传感器；0.25 英寸屏（等效 2.5 m 看 35 英寸）；
- **一个电源键、一个拍照键、一个支持四种手势的触摸板**；
- 操作：长按电源开关机；按拍照键或**轻点两下触摸板**拍照；前后滑动触摸板看相册/设置。

> 这是**一体机**，按键/触摸板是它自己的；G25 是分体式、只有一个 Fn 键（见 [ch-hid.md §6](ch-hid.md#6-按键一个-fn-键码-0x71)）。
> 网上很多"GLXSS 有触摸板"的说法都源自这一代，**不能套到 G25 上**。

### 2.4 官方站点

| 页面 | 结果 | 说明 |
|---|---|---|
| <https://www.llvision.com/en/glxss-me.html> | 只返回站点标题（JS 渲染） | 抓不到正文；现在站点主推 LEION HEY2【未】 |
| <https://www.llvision.com/en/sdk-ai-engine.html> | 同上 | 无法确认是否还有 GLXSS SDK 下载【未】 |
| <https://file.llvision.com/download/> | **可用**：GLXSS Suite 下载页 | 原文："GLXSS 系列智能眼镜的必备应用。确认连接至手机的 GLXSS 智能眼镜型号，并为其提供固件/驱动；对眼镜进行设置（亮度、音量、指示灯等）"【A】 |
| <https://fccid.io/2AKLNG20A1> | FCC 认证页 | "Smart Glass Host"，2017-03-14，2.4/5 GHz + BT；手册 PDF 存在但**网页抓取不支持 PDF**，未读【A/未】 |

> GLXSS Suite 下载页的措辞（"确认型号 + 提供固件/驱动 + 亮度/音量/指示灯"）与我们逆向出的
> `0x17` 版本查询、`0x19` 指示灯、`0x01/0x05` 亮度/显示模式完全对应。【A + 验】

### 2.5 功能清单：宣传 vs 设备

把媒体/官方说法与本地实测对到一起（"设备侧"一列只看 USB 描述符、28 B 报告、SDK 类）：

| 功能 | 宣传/媒体 | 设备侧证据 | 级别 |
|---|---|---|---|
| 拍照 | 1300 万像素索尼 CMOS | UVC 最高 1920×1080；13 MP 静态照大概率走 Myriad/ISP 私有路径，未逆出 | 【B】宣传 /【未】实现 |
| 录像 | 1080p30 | UVC 模式 640×480、1280×720、1920×1080（本项目已实现） | 【验】 |
| 人脸识别 | Movidius Myriad 2 前端人脸/物体识别 | SDK 有 `com.llvision.glass3.sdk.facedtr.FaceDtr`，`CommandCode` 定义 `FACE_DTR_INTERFACE_INDEX=7` 与 9 条请求；G25 是否启用该接口**未验证** | 【B】宣传 /【推】SDK |
| 语音 | GLXSS Pro 宣传"语音控制" | 设备是标准 UAC1 麦克风+扬声器；没有发现"眼镜端 ASR"的证据 | 【验】音频 /【未】语音控制 |
| 按键 | 媒体未细说 | **1 个 Fn 键**，HID 码 `0x71`，单击/长按 | 【验】 |
| 触摸 | 初代 Glxss 有触摸板 | G25 的 28 B 报告无触摸字段，SDK 无 Touch 类 | 【推】无 |
| 佩戴检测 | 没有资料提到 | 无字段、无请求；`0x1A` 是否相关**未验证** | 【未】 |
| 传感器 | Pro：重力/陀螺/光感；ME：未列 | 光感、磁罗盘、加速度、陀螺、温度（28 B + `0x04/0x05/0x06`） | 【验】 |
| 显示 | 600×400 OLED（Pro）/ 600×480（ME） | 设备上报 **640×400**（LCD 接口 `0xA1/0x04`，wIndex=3） | 【验】实际值 |
| 续航 | Pro：8 小时，可换电池 | G25 是 USB 总线供电的分体式眼镜，本身不带电池 | 【B】+【推】 |
| 音频 | — | UAC1：麦克风 + 扬声器；社区实测 44.1 kHz/16 bit 双声道（左=后麦，右=前麦） | 【验】 |

> 这张表的作用是**划边界**：哪些是"宣传里有、但 USB 侧看不到"的（13 MP 静态照、语音控制、
> 佩戴检测），避免后来者把媒体话术当成协议能力。

---

## 3. SDK / 开发者资料线索

### 3.1 GLXSS Pro SDK（2017，存在但无文档）

仓库：[`goldsail/Custom_SDK_Configuration`](https://github.com/goldsail/Custom_SDK_Configuration)【C】

README 内容（原文要点）：

- 需要 `android-sdk_user.build_windows.zip`（解压为 `GlxssProSDK`，含 `platforms`、`platform-tools`）；
- 需要 `GLXSS_API_TEST_src.zip`（示例工程，Eclipse ADT 结构，导入 Android Studio）；
- 配置 SDK 路径、API level、Build Tools，安装 Android Support Repository，然后 Build APK；
- 截图里 API level/Build Tools 是 2017 年的老版本。

> 说明厂商当年发过 **GLXSS Pro SDK + 示例工程**，但仓库只有"怎么配置"，没有 API 文档；
> 两个 zip 本身**不在仓库里**，网上也没有找到可下载的镜像。【未】

### 3.2 社区开源客户端（本轮最重要的外部证据）

仓库：[`happyme531/glxss-client`](https://github.com/happyme531/glxss-client)【C】
（Python，AGPL-3.0，2024-08 创建，2026-10 仍在推送；8 star、4 fork）

README 明确写：

- "LLVision GLXSS Me / **G25** 智能眼镜的开源 Linux 客户端"；
- **机身按键**："识别 `0x71` 的按下、松开、短按和长按，可绑定拍照、录音等操作"；
- **传感器**："光照、温度、加速度、陀螺仪、姿态和磁罗盘"，支持罗盘校准与航向归零，
  "俯仰角为抬头正、低头负"；
- 显示："面板为 **640×400** 非透明显示屏"；
- 启动："眼镜每次上电先进入引导状态 `03e7:2150`，加载固件后重新枚举为 `2e09:0030`"；
- 摄像头：640×480 / 1280×720 / 1920×1080；音频 44.1 kHz/16 bit 双声道
  （左声道=后麦克风，右声道=前麦克风）；
- 明确"当前实现针对 G25 固件；G26 / G26PRO 协议不同，未适配"；
- 仓库里带 `G25_MainFW_1.7.4.decrypted.img`、`G25_MainFW_1.9.2.decrypted.img`、
  `G26_PRO_RELEASE_10000051.img` —— 与本地 `fw_1.9.1.dec.bin` 同一命名体系。

代码级对照（抓取自 raw.githubusercontent.com，master 分支）：

| 文件 | 关键实现 | 与本地逆向的关系 |
|---|---|---|
| `glxss.py: decode_sensor()` | `struct.unpack("<BBH9hBBf", data)`，字段名 `key_status/key_code/lux/compass_raw/acc_raw/gyro_raw/af_result/caf_flag/temperature_c` | **与 28 B 布局逐字段一致**【验】 |
| `glxss.py: sensor_attributes()` | `0x04`=12 B、`0x05`=12 B、`0x06`=16 B；`imu[2]=acc_fsr`、`imu[5]=gyro_fsr`、`chip_id` 在 `light[4:12]`/`imu[8:16]` | 与原生 `0x2046/0x21fe` 解析一致【验】 |
| `glxss.py: enable_sensors()` | `0x10` 光感、`0x11` 罗盘、`0x12` 加速度、`0x14` 陀螺，`wValue=1` | 与原生函数名 `sensor_set_*_open` 一致【验】 |
| `glxss.py: info()` | `0x17` 24 B，前 16 B 版本串 | 与 `sensor_get_product_info` 一致【验】 |
| `glxss.py: sensor()` | `0x16` 28 B | 与原厂 300 ms 轮询同一请求【验】 |
| `interaction.py: ButtonTracker` | `code==0x71`、`status 1=press/0=release`、`long_seconds=0.8`、输出 press/release + short/long | 与 `IKeyEventClient` 常量一致【验】 |
| `glxss.py: boot()` | `03e7:2150` → 写 `0x01` → 重新枚举 `2e09:0030` | 与 `firmware-boot-protocol.md` 一致【验】 |

> 该仓库没有 issue（discussions 未核），无法从作者那里拿到更多"按键长按阈值/触摸"细节；
> 但它的存在说明 **G25 的 28 B 布局不是我们一家之言**。

### 3.3 其他仓库（真假线索）

| 仓库 | 是什么 | 价值 |
|---|---|---|
| [`HowToLoveChina/glxss_GigaPixel_Android`](https://github.com/HowToLoveChina/glxss_GigaPixel_Android) | 实验室 GigaPixel 控制程序的 Android 客户端（Java，2019） | 与 SDK 无关，只能说明学界有人用 GLXSS【D】 |
| [`HowToLoveChina/LLvisionCompile`](https://github.com/HowToLoveChina/LLvisionCompile) | **不是 SDK**：一个 PyInstaller 打包的 NNC 模型编译工具（自带 IPython/PIL） | 误报，忽略【验】 |
| [`iOSMalin/videocoop`](https://github.com/iOSMalin/videocoop)、[`iOSMalin/CoopVideo`](https://github.com/iOSMalin/CoopVideo) | 2016 年的 GLXSS 视频协作 demo（空仓库/改过的 EaseMobSDK） | 历史资料，无协议价值【D】 |
| [`nexpriv/llvision-assets`](https://github.com/nexpriv/llvision-assets) | 133 MB 的 llvision 资源镜像（未逐项核对） | 可能含固件/App，**未验证**，谨慎对待【未】 |

GitHub 仓库搜索 `glxss` 共 10 条、`llvision` 共 4 条（2026-11-21 查询）；
**没有**官方账号发布的 SDK/协议仓库。

---

## 4. 民间资料

| 来源 | 内容 | 可信度 |
|---|---|---|
| [52pojie 2024 求 App 帖](https://www.52pojie.cn/thread-1935200-1-1.html) | 用户 2024 年入手 GLXSS 后"全网找不到相关软件"；有人用 `glxss_GigaPixel_Android` 源码打包了 APK；楼主回复"公众号上的官方软件已经找不到了" | 【D】但侧面证明**官方 App 已下架** |
| 微信公众号旧文（`mp.weixin.qq.com/s/PhgiAzVnreOBot0aplbg1A`，52pojie 帖中引用） | 已失效/需登录 | 未读【未】 |

> 结论：**官方渠道基本消失**，现在能用的资料就是 APK 逆向 + 社区客户端 + 本仓库。
> 这也是为什么"28 B 全解"这件事必须自己从 `libhid.so` 里做出来。

---

## 5. 交叉验证表（本地逆向 ↔ 外部来源）

| 本地结论（[ch-hid.md](ch-hid.md)） | 外部对照 | 级别 |
|---|---|---|
| 28 B：`B B H 9h B B f` | `glxss-client` `decode_sensor()` 完全相同 | 【验】 |
| 按键码 `0x71=113`，0=up/1=down | `ButtonTracker` 只认 `0x71`、`status in (0,1)` | 【验】 |
| 单击 / 长按由电平重复计数得出 | 社区用 0.8 s 时间阈值，语义一致 | 【验】 |
| 原厂 App 单击→录像中切 FOV | 无外部资料 | 【验】仅本地 |
| `0x04/0x05/0x06` 是属性不是数据 | `sensor_attributes()` 同布局 | 【验】 |
| `0x10/0x11/0x12/0x14` 使能传感器 | `enable_sensors()` 同请求、同 `wValue=1` | 【验】 |
| `0x17` 24 B = 16 B 版本 + 8 B ID | `info()` 取前 16 B 当版本串 | 【验】 |
| 面板 640×400 | README "面板为 640×400" | 【验】 |
| 引导 `03e7:2150` → `2e09:0030` | `boot()` 同流程 | 【验】 |
| 只有一个 Fn 键、无触摸 | ME 评测：手势靠摄像头；SDK 只定义 1 个键码 | 【推】 |
| 磁罗盘 | Pro 宣传未提；ME 评测未提；社区实现有 | 【验】来自代码 |

---

## 6. 没找到 / 仍缺的证据

1. **G25 官方说明书**（含按键图示、长按定义）—— 未找到。FCC 上只有 G20A1 的 "Smart Glass Host"，
   手册是 PDF 且抓取工具不支持；即使读到也未必是 G25。
2. **HID 报告描述符**（31 B，`GET_DESCRIPTOR(0x22)`）—— 未找到任何公开转储。
   因此"28 B 报告是不是标准 input report、有没有 report ID"仍未证实。
3. **官方 SDK 文档 / `GLXSS_API_TEST_src.zip`** —— 只在 2017 年的配置教程里被提到，文件本身找不到。
4. **`0x1A`（MOVI last state）的语义** —— 没有外部资料。
5. **`af_result` / `caf_flag` 的定义** —— 社区也只当状态位透传，没有解释。
6. **佩戴检测** —— 媒体、SDK、HID 报告里都没有证据；`0x1A` 是否与佩戴有关**未验证**。
7. **按键是否还有双击/组合键** —— SDK 只定义单击/长按两种手势，没有双击。
8. **G25 与 GLXSS Pro/ME 的官方型号对应** —— 只有社区 README 和固件命名，厂商页面没写 "G25"。

---

## 7. 检索记录（可复现）

- GitHub API：
  - `https://api.github.com/search/repositories?q=glxss` → 10 条
  - `https://api.github.com/search/repositories?q=llvision` → 4 条
  - `https://api.github.com/repos/happyme531/glxss-client/git/trees/master?recursive=1`
  - raw 文件：`glxss.py`、`interaction.py`、`README.md`
- 网页：
  - `https://www.llvision.com/en/glxss-me.html`、`.../en/sdk-ai-engine.html`（JS 渲染，无正文）
  - `https://file.llvision.com/download/`（GLXSS Suite）
  - `https://fccid.io/2AKLNG20A1`
  - `http://www.gongkong.com/news/201704/359583.html`（GLXSS Pro）
  - `http://m.ikanchai.com/pcarticle/194513`（GLXSS ME）
  - `https://m.cnmo.com/smart/516199.html`（初代 Glxss）
  - `https://www.52pojie.cn/thread-1935200-1-1.html`
- 未成功：FCC 手册 PDF（`application/pdf` 不支持）、天极网参数页（403）、微信公众号旧文。

---

## 8. 给后来者的建议

1. 先读 [ch-hid.md](ch-hid.md)：协议已经全解，**不需要再猜**。
2. 想验证按键行为，用 `0xA1/0x16` 每 100–300 ms 轮询即可，不必碰 `0x83`。
3. 想补"官方证据"，优先找：`GLXSS_API_TEST_src.zip`、`GlxssProSDK`、
   `GLXSS Suite` 旧版本 APK（`com.llvision.glass3.appstore`）——它们可能带 SDK 的 jar/文档。
4. 引用社区代码时注意其 **AGPL-3.0** 许可；本仓库只做协议对照，不复制其代码。
