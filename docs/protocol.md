# 投屏协议（已并入第 1 章）

> 完整的帧格式、控制命令、行映射、实测数据和坑，见 **[第 1 章 · 显示子系统](ch-display.md)**。
>
> 这里只留一个**正确的**最小示例，替换早期笔记里的笔误。

## 一帧 = 16 B 头 + 640×400 RGBA8888

| 偏移 | 长度 | 字段 | 值 |
|---|---|---|---|
| 0 | 1 | `format` | `0x01`（RGBA8888） |
| 1–3 | 3 | 保留 | 0 |
| 4–7 | 4 | `totalLen`（LE u32） | `1,024,016` = 16 + 1,024,000 |
| 8–15 | 8 | 扩展 | 0 |
| 16 | 1,024,000 | 像素 | RGBA8888，无行填充 |

640×400 时的前 16 字节：

```
01 00 00 00  10 A0 0F 00  00 00 00 00  00 00 00 00
```

> 早期版本写的 `00 01 ... 18 88 ff 0f` 是错的（格式字节位置和长度都不对）。
> 以代码为准：[`LcdProtocol.frame()`](../app/app/src/main/java/com/wzqvip/glxsscast/lcd/LcdProtocol.kt)。

其余内容：

- 控制命令（亮度/开关/roll/模式/状态）→ [ch-display.md §3](ch-display.md#3-控制面全部实测)
- MediaProjection 管线与三种映射 → [ch-display.md §4](ch-display.md#4-手机端管线从-mediaprojection-到-640400)
- 原始抓包 → [usb-capture.md](usb-capture.md)
