# 第 4 章 · 音频

> 本章讲清楚：眼镜自带的**扬声器和麦克风**是怎么工作的、为什么不需要写一行"音频协议"、
> 以及怎么验证声音到底从哪进哪出。
>
> 对应代码：[`usb/Peripherals.kt`](../app/app/src/main/java/com/wzqvip/glxsscast/usb/Peripherals.kt)
>
> 描述符：[usb-capture.md §1](usb-capture.md#1-设备描述符实测)；
> 版本信息：[apk-versions.md](apk-versions.md)（两代 App 的音频接口未变）

---

## 1. 原理：一个标准 UAC1 声卡

眼镜在 USB 复合设备里带了 **USB Audio Class 1.0** 功能：

```
if4  AudioControl   (0x01/0x01)  无端点        ← 终端/Feature Unit/音量控制
if5  AudioStreaming (0x01/0x02)  alt1: 0x02 ISOC OUT 256  ← 扬声器（手机 → 眼镜）
if6  AudioStreaming (0x01/0x02)  alt1: 0x82 ISOC IN  220  ← 麦克风（眼镜 → 手机）
```

关键点：

1. **它不需要私有驱动**。Android 的 `UsbAlsaManager` 会把 UAC 设备注册成普通音频设备
   （`AudioDeviceInfo.TYPE_USB_DEVICE` / `TYPE_USB_HEADSET`），之后 `AudioTrack` / `AudioRecord`
   走系统音频框架，由内核的 `snd-usb-audio`（Linux/Android）或 Android USB 音频栈处理等时传输。
2. **App 不应该 claim 音频接口**。一旦 claim if4/if5/if6，系统音频栈会失去端点，
   结果是"声音没了"——本项目所有 USB 代码都只 claim LCD(3) / HID(2) / UVC(0,1)。
3. **采样率/位深是协商出来的**。UAC1 描述符里会列支持格式；`AudioTrack`/`AudioRecord`
   创建时由系统选一个支持值。本项目没有抓 UAC 的 class 描述符细节，标【未】。

---

## 2. 数据结构与接口

### 2.1 接口/端点

| 接口 | Class/Subclass | alt | 端点 | 方向 | 最大包 | 用途 |
|---|---|---|---|---|---|---|
| if4 | `0x01/0x01` | 0 | — | — | — | AudioControl：终端、Feature Unit、音量/静音 |
| if5 | `0x01/0x02` | 0 | — | — | — | 零带宽 alt（空闲） |
| if5 | `0x01/0x02` | **1** | `0x02` ISOC | OUT | 256 B | 扬声器数据 |
| if6 | `0x01/0x02` | 0 | — | — | — | 零带宽 alt（空闲） |
| if6 | `0x01/0x02` | **1** | `0x82` ISOC | IN | 220 B | 麦克风数据 |

> `wMaxPacketSize` 是**上限**，不是实际码率。真实采样率取决于 UAC 描述符里的
> `bSamFreqType`/`tSamFreq` 与主机协商结果；本次未解析，标【未】。
>
> 等时端点由系统 USB 音频驱动独占，**应用层看不到也不该碰**。

### 2.2 UAC1 描述符字段（标准结构，本设备未解析）

UAC1 的能力都写在这些 class 描述符里。本项目只用了端点，**没有解析它们**，所以采样率等标【未】。

| 描述符 | 关键字段 | 作用 |
|---|---|---|
| Header（`0x24/0x01`） | `bcdADC`、`wTotalLength`、`bInCollection`、`baInterfaceNr` | 声明这组音频接口 |
| Input Terminal（`0x24/0x02`） | `bTerminalID`、`wTerminalType`、`bNrChannels`、`wChannelConfig` | 麦克风/线路输入 |
| Output Terminal（`0x24/0x03`） | `bTerminalID`、`wTerminalType`、`bSourceID` | 扬声器/耳机输出 |
| Feature Unit（`0x24/0x06`） | `bUnitID`、`bSourceID`、`bControlSize`、`bmaControls` | 音量/静音/增益控制 |
| AS Interface（`0x24/0x01`） | `bTerminalLink`、`bDelay`、`wFormatTag` | 数据接口绑定到哪个终端 |
| Type I Format（`0x24/0x02`） | `bNrChannels`、`bSubframeSize`、`bBitResolution`、`bSamFreqType`、`tSamFreq` | 采样率/位深/通道 |

> 想补齐，读 `rawDescriptors` 里 `bDescriptorType=0x24` 的记录即可（和 [ch-uvc.md](ch-uvc.md) 解析 VS 描述符同一套做法）。
> 目前 Android 会自动协商，所以"不解析也能用"。

### 2.3 音频通路

```
手机侧                                             眼镜侧
──────                                             ──────
AudioTrack (STREAM_MUSIC)                           UAC OUT 终端
   └─ AudioFlinger → USB audio HAL → if5 ep 0x02 ──► DAC → 扬声器

AudioRecord (MIC/DEFAULT)                           UAC IN 终端
   ◄─ if6 ep 0x82 ── ADC ◄────────────────────────── 麦克风
```

### 2.4 与视频/投屏的关系

| 组合 | 实测 |
|---|---|
| 投屏 + 音频 | 音频走 iso 端点，投屏走 bulk，互不冲突（未单独压测） |
| UVC + 投屏 + 音频 | UVC 25 fps + 投屏 25 fps 已实测并发；音频带宽很小，理论无压力【推】 |
| 音频 + 大量 bulk | USB 2.0 HS 总带宽 ~40 MB/s，投屏 25.6 MB/s + UVC 9–12 MB/s 已接近上限；音频 iso 有保留带宽，实测未掉 |

---

## 3. 驱动步骤（其实是"不驱动"）

```
1. 插上眼镜，等它枚举成 2E09:0030
2. 确认系统识别到 USB 音频设备：
     adb shell dumpsys audio | grep -i usb
     adb shell dumpsys media.audio_flinger | grep -i usb
3. 播放：new AudioTrack(...).write(...).play()   ← 默认路由即可
4. 录音：new AudioRecord(...).startRecording()   ← 默认路由即可
5. 若默认路由不对（手机有多个麦克风/输出），用 setPreferredDevice() 指定 USB 设备
```

**不要**做的事：

- 不要 `claimInterface` if4/5/6；
- 不要自己 `bulkTransfer` 到 `0x02`（iso 端点不是 bulk）；
- 不要 `setInterface` 切音频 alt（会把系统正在用的流打断）。

---

## 4. 可运行代码

### 4.1 播放测试音（仓库真实代码）

```kotlin
// usb/Peripherals.kt
fun playTone(freq: Double, seconds: Double): Boolean {
    return try {
        val sr = 44100
        val count = (sr * seconds).toInt()
        val buf = ShortArray(count)
        for (i in 0 until count) {
            buf[i] = (8000.0 * sin(2.0 * Math.PI * freq * i / sr)).toInt().toShort()
        }
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC, sr,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            buf.size * 2, AudioTrack.MODE_STATIC
        )
        track.write(buf, 0, buf.size)
        track.play()
        Thread.sleep((seconds * 1000).toLong() + 100)
        track.stop()
        track.release()
        true
    } catch (e: Exception) { Log.e(TAG, "tone failed", e); false }
}
```

要点：

- `STREAM_MUSIC`：走媒体音量；
- 44.1 kHz / 单声道 / 16 bit：最保守的配置，UAC1 基本都支持；
- 幅度 8000（约 -12 dBFS）：避免削顶和啸叫；
- `MODE_STATIC`：一次性写入、播放，适合短测试音。

### 4.2 麦克风电平（仓库真实代码）

```kotlin
// usb/Peripherals.kt
fun micRms(): Int {
    return try {
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.DEFAULT, sr,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            if (minBuf > 0) minBuf else 4096
        )
        val buf = ShortArray(4096)
        rec.startRecording()
        var sum = 0.0
        var n = 0
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 500) {
            val r = rec.read(buf, 0, buf.size)
            if (r > 0) for (i in 0 until r) { sum += buf[i].toDouble() * buf[i]; n++ }
        }
        rec.stop()
        rec.release()
        if (n == 0) 0 else sqrt(sum / n).toInt()
    } catch (e: Exception) { Log.e(TAG, "mic failed", e); -1 }
}
```

返回 500 ms 的 RMS。说话时应该明显高于环境底噪；返回 `-1` 表示没有可用输入设备
或 `RECORD_AUDIO` 权限没给。

### 4.3 更稳的路由方式（说明，非仓库代码）

如果手机同时有内置麦克风/扬声器，`DEFAULT` 源不一定选到眼镜。更确定的做法是：

```kotlin
// 伪代码示意，未在仓库实现
val usbOut = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                   it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
track.setPreferredDevice(usbOut)
```

`AudioTrack.setPreferredDevice()` / `AudioRecord.setPreferredDevice()`（API 23+）可以在
不改变系统全局路由的前提下把这一路流钉到 USB 设备。本项目目前依赖默认路由，标【待验证】。

---

## 5. 测试方法

| 目的 | 方法 | 期望 |
|---|---|---|
| 系统是否识别 | `adb shell dumpsys audio \| grep -i usb` | 出现 `USB device` / `USB headset` |
| 扬声器 | App「播放测试音（眼镜扬声器）」 | 眼镜里听到 440 Hz，约 1 s |
| 麦克风 | App「麦克风电平测试」 | 安静时 RMS 低，说话时明显升高 |
| 音量 | 手机媒体音量键 | 音量变化作用在 USB 输出上 |
| 路由 | 插拔眼镜时看 `dumpsys audio` 的设备列表变化 | 设备增删与插拔同步 |
| Linux 主机 | `aplay -l` / `arecord -l` | 出现 `LLVISION G25` 之类 card |

---

## 6. 实测数据

| 项 | 结果 | 置信 |
|---|---|---|
| UAC1 接口枚举 | if4/5/6 全在，端点 `0x02` OUT / `0x82` IN | 【验】 |
| 系统注册为音频设备 | 插上后 Android 音频框架可见 USB 设备 | 【验】 |
| 播放测试音 | App 内置入口可用；详细响度/延迟未记录 | 【推】 |
| 麦克风 RMS | App 内置入口可用；数值未归档 | 【推】 |
| 采样率/位深 | 未抓 UAC class 描述符 | 【未】 |
| 双向同时（扬声器+麦克风） | 未专项测试；注意啸叫 | 【未】 |

> 本章的"✅"指的是**接口与系统路由可用**（有枚举与代码路径），
> 不是"声学指标已验收"。需要量化时按 §5 补测并回填。

---

## 7. 坑与注意事项

1. **不要 claim 音频接口**。系统音频栈一旦失去接口，声音直接消失，而且不会报错。
2. **优先用系统路由，不要自己写 iso 传输**。Android 没有给应用的 isochronous API，
   自己拼 `UsbRequest` 也拿不到稳定的时序。
3. **`AudioRecord` 需要 `RECORD_AUDIO` 权限**（本项目 Manifest 已声明并在启动时申请）。
4. **不要用 `STREAM_VOICE_CALL` 测扬声器**：会走通话路由，很多机型不出声。
5. **啸叫**：眼镜扬声器和麦克风物理距离近，外放 + 录音同时开容易正反馈；
   测试时降低音量或错开时间。
6. **音量键控制的是"媒体音量"**：确认手机不在静音/勿扰，且媒体音量不为 0。
7. **部分 ROM 不自动路由**：插上后仍从手机听筒/扬声器出声，需要 `setPreferredDevice`
   或在开发者选项里关掉"USB 音频自动路由"之类的开关（因 ROM 而异）。
8. **音频与投屏抢 USB role**：某些手机切 USB role 会重枚举，正在播的声音会中断；
   本项目不主动切 role。
9. **采样率别乱假设**：测试音用 44.1 kHz 最稳；UAC1 支持哪些采样率由描述符决定（本次未解析），
   不要假设 48 kHz / 96 kHz 一定行。

---

## 8. 待验证

- [ ] UAC class 描述符：支持的采样率、位深、通道数、Feature Unit 的音量/静音控制。
- [ ] 扬声器实际响度/频响、麦克风灵敏度与底噪（需要声学环境）。
- [ ] 双向同时（扬声器 + 麦克风）是否稳定，是否需要回声消除。
- [ ] `setPreferredDevice()` 在目标机型上的效果。
- [ ] 音频是否会影响投屏/UVC 的带宽与延迟（iso 有保留带宽，理论上不会）。
