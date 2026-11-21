# 待验证项与验证方法 / Open questions

> **状态：§1–§3 已用真机抓包解决**（见 [usb-capture.md](usb-capture.md)）。
> 剩余：固件/enkey/刷机风险（§4）、颜色字节序等显示细节（§5）。

以下内容来自**静态分析**，需要在真机上确认。欢迎提交 PR 补充实测结果。

## 1. 端点与接口

```bash
lsusb -v -d 2e09:0030        # 或 usb-devices
```
需要记录：

- LCD 接口的 **bInterfaceNumber**、**bInterfaceClass**（预期 `0xFF` 厂商自定义）
- **bulk OUT 端点地址**、`wMaxPacketSize`、是否支持 bulk 连续传输
- 设备是 USB 2.0 还是 3.0

## 2. 控制命令字节

用 `usbmon` + Wireshark 抓一次 App 的投屏过程：

```bash
sudo modprobe usbmon
# 找到总线号，抓 /sys/kernel/debug/usb/usbmon/<bus>u
```
需要提取：

- `nativeLCDSetLuminance` / `SetSwitch` / `SetRoll` / `SetDisplayMode` 对应的
  `bmRequestType`、`bRequest`、`wValue`、`wIndex`、数据长度
- 是否每次送帧前有控制握手

## 3. 流控与 ACK

- 每帧是否需要等待设备回 ACK？
- 发送过快时 `libusb_bulk_transfer` 是否返回 `LIBUSB_ERROR_TIMEOUT` / `PIPE`（`liblcd` 有 `libusb_clear_halt`）
- 实际稳定帧率是多少（抓包统计）

## 4. 固件与 enkey

- `llvision.enkey`（128 B）用途：固件解密？校验？量产密钥？
- 固件是否校验签名（决定能否修改 `main.elf` 后重新刷入）
- boot 模式（`0x03E7:0x2150`）下载协议细节（libboot 已 strip，需动态抓包）

## 5. 显示细节

- RGB 字节序（RGBA 还是 BGRA / ARGB）——用纯色测试帧可肉眼确认
- 是否存在行对齐/padding（`rowStride != width*4` 时）
- 显示模式 0/1/2 的确切差异
- 亮度的实际生效范围

## 如何提交结果

1. 抓包或 `lsusb -v` 输出（去掉序列号等隐私信息）
2. 实测结论 + 复现命令
3. 提 PR 修改本文件，或开 Issue 附上数据
