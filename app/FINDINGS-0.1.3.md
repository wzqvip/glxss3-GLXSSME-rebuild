# 0.1.3 现场记录：崩溃/半死触发实验与修复

> 本文件属于 `app/`（交付物的一部分），记录 2026-11-21 22:04–23:xx 在
> LG G8（`adb -s 192.168.3.88:37003`，root）+ GLXSS G25（`2E09:0030`）上的实测。
>
> 相关代码：`diag/Oplog.kt`（环形日志）、`usb/UsbGate.kt`（USB 串行闸门）、
> `usb/HidSensor.kt`（HID 解析）、`diag/DebugReceiver.kt`（adb 实验入口）。

## 1. 这次修了什么（对应任务 1/3/4）

| 项 | 实现 |
|---|---|
| 崩溃现场 | `Oplog` 内存环 600 条 + 每 2 s 追加 `files/oplog.txt`（>2 MB 轮转 `oplog.1.txt`）；掉线/掉回 `03E7:2150`、LCD 连续 3 帧发送失败、UVC 连续无效流/断流、HID 连续读失败、进程未捕获异常都会把最后 200 条写 `files/crash_last.txt`（带时间/原因/版本/pid） |
| 统一 USB 连接 | UVC 不再 `openDevice(dev, null)`，改为复用 `UsbLink.connection`；UVC 停止时只 release VS/VC，不 close、不 release HID（原厂也是单 fd） |
| UVC 优雅停止 | 停读线程 → `setInterface(alt0)` → `releaseInterface(VS)` → `CLEAR_FEATURE(ENDPOINT_HALT)`（`0x02/0x01, wValue=0, wIndex=0x81`）→ 共享连接不 close |
| LCD 帧节奏 | 最高 30 fps（`MIN_FRAME_INTERVAL_MS`），`sending` 保证整帧 bulk OUT 返回后才发下一帧；连续 3 帧失败停流 + crash 快照，不再无限重试 |
| 控制请求串行化 | `UsbGate`（ReentrantLock）覆盖 LCD/HID/UVC 的 control transfer、claim/release/setInterface 和 1 MB bulk OUT；UVC bulk IN 不占闸门（保留原厂式并发） |
| HID | 28 B 报告 `0xA1/0x16` wIndex=2 每 300 ms 轮询（0x83 中断读保留为备选）；单击=松开后的第一个包、长按=连续第 4 个按下包；UI 显示 lux/温度/acc/gyro，单击/长按动作可配（默认拍照 / 投屏开关） |

## 2. 触发实验（全部在 0.1.3 修复版上跑）

环境说明：

- 测试期间眼镜的 **UVC 相机一直处于「半死」**：probe/commit 都 rc=34，但 0 帧；
  偶发一个 460812 B 的 URB，前 16 B 是 `ea08b31aaa8aeb3f4aa0a80a2aa98aad`（不是 `0C` 开头的 UVC header），
  之后全是 bulk-in timeout。**原厂「亮亮拍」App 同时/Hook 也能复现**（`libuvc: retrying transfer, status = 2`），
  说明不是本 App 代码造成；详见 §3。
- 因此实验 1/2/4/6 的 LCD/投屏通路正常（1 MB 帧 24–30 ms 写完），实验 3/5 的 UVC 只能验证
  **控制面**（claim/probe/commit/setInterface/clear-halt）在长时间/高频循环下的稳定性。
- 投屏内容：Android 14 的 MediaProjection 单选 App 授权，选「设置」App，用周期性滑动让其持续产生帧
  （17–27 fps）；这比原事故的「静止 ~2 fps」负载更高。

| # | 实验 | 时长/次数 | 结果 | crash_last |
|---|---|---|---|---|
| 1 | 只投屏 + 中途亮度/显示模式/开屏切换 | 3 min | 投屏累计 3611 帧、20.9–27.2 fps，LCD 发送 0 失败；每 30 s 切亮度 100–240 / 显示模式 1↔2 / 开屏 1 | 未崩溃 |
| 2 | 投屏 + 录像（原事故组合） | 8 min | 投屏 2625 帧 / 24–27 fps，录像 2498 帧 / 477.9 s / 0 丢帧 / 32 MB，USB 未断 | 未崩溃 |
| 3 | 投屏 + UVC 并发 | 3 min | 投屏 20–21 fps / 4526 帧；UVC 共享同一 fd 下 claim(VC/VS)+probe/commit 全部 rc>0，但相机 0 帧（半死）；LCD 0 失败 | 未崩溃 |
| 4 | 快速 start/stop 投屏 ×20 | 301 s（20/20 成功，约 15 s/轮，含系统授权 UI） | 每轮 `running=True`，VID 始终 `2e09`；无掉线/半死 | 未崩溃 |
| 5 | 快速 start/stop UVC ×50（投屏同时） | 67 s（50/50 成功） | 每轮 claim(VC/VS)+probe/commit+alt0+clear-halt 全成功；50/50 `ok`；投屏继续出帧（5–24 fps）；无失败 | 未崩溃 |
| 6 | 每 100 ms 状态查询 + 投屏 | 3 min（约 1800 次 0x04） | 0x04 请求 0 失败；投屏 22–24 fps / 5959 帧 / LCD 0 失败；`UsbGate.maxHoldMs=708 ms`（控制请求最多等一帧） | 未崩溃 |

**结论**：上述 6 组操作（含原事故的投屏+录像组合、以及更重的三路并发/高频控制/快速启停）在修复版上
**均未复现**「掉回 `03E7:2150`」或「LCD/USB 半死」。能 100% 复现的只有 `0xA1/0x18`（显式固件复位，
见 §3），它不是原事故发生路径，但可作为**软件恢复入口**。

对照旧版（0.1.2）在同一台机器上：22:00 的三重并发压力测试记录为
`cast 29.7 fps / uvc 0.0 fps / rec 899 帧 / CPU 14%`，UVC 当时已半死；23:xx 把 0.1.2 装回去做 UVC
验证同样 0 帧（`libuvc TIMED_OUT`），所以「UVC 0 帧」不是 0.1.3 引入的，设备相机当时已处于半死态。

## 3. 眼镜当前状态与「半死」判定（实测）

- `2E09:0030` 枚举正常、`0xA1/0x04` 返回 8 B、LCD 1 MB bulk OUT 25 ms 完成 → USB/LCD 通路健康；
- 但 UVC：commit rc=34，读循环 0 帧；偶发一帧非 header 数据（上面 `ea08...`）；
- 原厂 App：`libuvc` 提交 3 个 transfer，5 s 后 `retrying transfer, status = 2`（TIMED_OUT），预览黑屏；
- **重灌固件不能恢复本状态**（本次实测）：
  - `0xA1/0x18 wValue=1` 是一条约 2 s 后超时（rc=-1）但**确实会复位固件**的命令：
    约 2.3–2.9 s 后设备以 `03E7:2150` 重新枚举；
  - App 自动/手动灌 `G25_MainFW_1.9.1.img`（1,525,136 B，sha256 `5b4246ba...`）全部 1490 包写完，
    设备 1.3 s 后重枚举回 `2E09:0030`，但 UVC 依旧 0 帧。
  - 结论：**0x18 复位的是 SoC/固件，不掉眼镜的 VBUS；相机 sensor 的电源域没有被真正断电。**
    文档里「拔插后重灌能恢复」的关键是拔插（VBUS 断电），不是灌固件本身。
    本机眼镜是远程测试、无法物理拔插，所以本轮无法把相机恢复到健康态。
  - 附带：`/sys/bus/usb/devices/1-1/authorized`、dwc3 `mode`（host/peripheral 切换）只会重枚举数据线，
    不会给眼镜断电；`smb5-vbus` 是只读 sysfs（0444），无法软件关 VBUS。

## 4. HID 实测（0.1.3）

`0xA1/0x16` 28 B，连续轮询 300 ms，`sensors`（0x10/0x11/0x12/0x14）使能后：

```
hid: running=true mode=POLL polls=22 fails=0
report=lux=10 · 温度=47.5°C · acc=(-0.03, -0.50, -0.06)g · gyro=(0, 0, -0)°/s · af=248 caf=247
原始: 00710a000000000000000000...00f8f7<f32 temp>
```

- lux=10、温度 45.1–47.5 °C 稳定；`keyStatus=0 keyCode=0x71`（未按）；
- **使能前** acc/gyro 全 0；使能后 acc 三个轴出现非零值（本机静置时约 -0.03/-0.50/-0.06 g），
  gyro 近 0 —— 证明 28 B 报告的 10/12/14 与 16/18/20 偏移解析正确；
- `af=248 / caf=247` 在相机半死时保持不变（相机侧状态，未细究）；
- 物理按键无法远程按下：单击/长按状态机按原厂语义实现（松开后第一个包 / 连续第 4 个按下包），
  **未做真人按键实测**；按键动作绑定（默认 单击=拍照、长按=投屏开关）已持久化在 `Prefs`。

## 5. 未验证 / 风险

- 原事故（静止 ~2 fps + 录像 → 掉 `03E7`）未复现：本轮 8 min 高帧率 + 录音 + LCD 控制无异常；
  可能是「长时间静止低帧率」触发，或与当时相机固件状态相关，需在能物理拔插/健康相机的机器上继续。
- UVC 半死状态下实验 3/5 只覆盖控制面。
- HID 中断端点 0x83 备选路径未做长跑（控制请求路径已验证）。
- 0x18 复位是**破坏性**命令，正常 UI 不暴露；只放在实验入口。
