package com.wzqvip.glxsscast.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 音频增强：强制 USB 麦克风输入 / 音量控制 / 采样率+RMS+峰值 dBFS 实测 / 双向通话测试。
 *
 * 眼镜在 USB 上就是一个 UAC 声卡（扬声器 + 麦克风），系统默认路由到它；
 * 但插着手机自带麦克风或多路 USB 设备时，默认输入不一定是眼镜麦克风，
 * 所以录音前用 [AudioRecord.setPreferredDevice] 显式指定 `TYPE_USB_HEADSET` /
 * `TYPE_USB_DEVICE` / `TYPE_USB_ACCESSORY`，再用 [AudioRecord.getRoutedDevice]
 * 回报**实际**路由到的设备（系统不接受偏好时能看出来）。
 */
object AudioEnh {

    private const val TAG = "AudioEnh"

    private val USB_TYPES = intArrayOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY
    )

    /** 一个输入设备（麦克风） */
    data class Mic(
        val info: AudioDeviceInfo,
        val name: String,
        val type: Int,
        val isUsb: Boolean
    ) {
        val typeName: String
            get() = when (type) {
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机/麦克风"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 声卡"
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 配件"
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙 SCO"
                else -> "type=$type"
            }

        fun line(): String = "$typeName · $name (id=${info.id})"
    }

    /** 采样率/RMS/峰值实测结果 */
    data class Probe(
        val ok: Boolean,
        val deviceName: String,
        val usbFound: Boolean,
        val preferredOk: Boolean,
        val sampleRate: Int,
        val rms: Int,
        val peakDbfs: Double,
        val samples: Int,
        val tonePlayedMs: Long = 0,
        val error: String = ""
    ) {
        fun line(): String = if (!ok) {
            "音频实测失败: $error"
        } else {
            String.format(
                Locale.US,
                "输入: %s%s · 采样率 %d Hz · RMS %d · 峰值 %.1f dBFS · %d 样本",
                deviceName,
                if (usbFound && !preferredOk) "（USB 麦克风被系统拒绝，已回落）" else if (usbFound) "（已强制 USB）" else "（无 USB 麦克风）",
                sampleRate, rms, peakDbfs, samples
            )
        }
    }

    /** 音量档位 */
    data class Volume(val cur: Int, val max: Int, val min: Int = 0) {
        val ratio: Float get() = if (max > 0) cur.toFloat() / max else 0f
        fun line(): String = "音量 $cur/$max"
    }

    // ==================================================================
    // 设备枚举
    // ==================================================================

    fun inputDevices(ctx: Context): List<Mic> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { info ->
                Mic(
                    info = info,
                    name = info.productName?.toString()?.ifEmpty { "未知设备" } ?: "未知设备",
                    type = info.type,
                    isUsb = USB_TYPES.contains(info.type)
                )
            }.sortedBy { if (it.isUsb) 0 else 1 }
        } catch (t: Throwable) {
            Log.w(TAG, "getDevices(INPUTS) failed", t)
            emptyList()
        }
    }

    fun findUsbMic(ctx: Context): Mic? = inputDevices(ctx).firstOrNull { it.isUsb }

    // ==================================================================
    // 音量（STREAM_MUSIC，走眼镜扬声器那一路）
    // ==================================================================

    fun volume(ctx: Context): Volume {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            Volume(am.getStreamVolume(AudioManager.STREAM_MUSIC), am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        } catch (t: Throwable) {
            Log.w(TAG, "read volume failed", t)
            Volume(0, 0)
        }
    }

    fun stepVolume(ctx: Context, delta: Int): Volume {
        val cur = volume(ctx)
        return setVolume(ctx, cur.cur + delta)
    }

    fun setVolume(ctx: Context, index: Int): Volume {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxV = try {
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        } catch (t: Throwable) {
            0
        }
        val want = index.coerceIn(0, maxV)
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, want, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "setStreamVolume($want) failed", t)
        }
        return volume(ctx)
    }

    // ==================================================================
    // 实测：采样率 / RMS / 峰值 dBFS
    // ==================================================================

    /**
     * 录 [seconds] 秒：把输入设备强制到 USB 麦克风，回报实际采样率、RMS、峰值 dBFS。
     * 必须放在 IO 线程调用（会阻塞 seconds 秒）。
     */
    fun probe(ctx: Context, seconds: Double = 1.0, forceUsb: Boolean = true): Probe =
        recordOnce(ctx, seconds, forceUsb, toneFreq = null)

    /**
     * 双向通话测试：一边用 STREAM_MUSIC 播 440Hz（应走眼镜扬声器），
     * 一边用强制 USB 麦克风的 AudioRecord 录 [seconds] 秒，验证不冲突。
     */
    fun duplex(ctx: Context, seconds: Double = 2.0): Probe {
        val toneMs = (seconds * 1000).toLong()
        val p = recordOnce(ctx, seconds, forceUsb = true, toneFreq = 440.0)
        return if (!p.ok) p else p.copy(tonePlayedMs = toneMs)
    }

    private fun recordOnce(
        ctx: Context,
        seconds: Double,
        forceUsb: Boolean,
        toneFreq: Double?
    ): Probe {
        val usb = if (forceUsb) findUsbMic(ctx) else null
        val rates = intArrayOf(48_000, 44_100, 16_000)
        var rec: AudioRecord? = null
        var rate = 0
        for (r in rates) {
            val minBuf = try {
                AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            } catch (t: Throwable) {
                -1
            }
            if (minBuf <= 0) continue
            val candidate = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, r,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf * 2, r / 2)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord($r) failed", t)
                null
            }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                rec = candidate
                rate = r
                break
            }
            candidate?.release()
        }
        if (rec == null) {
            return Probe(false, "", usb != null, false, 0, 0, 0.0, 0, error = "AudioRecord 初始化失败（48k/44.1k/16k 都不行）")
        }

        var prefOk = false
        if (forceUsb && usb != null) {
            prefOk = try {
                rec.setPreferredDevice(usb.info)
            } catch (t: Throwable) {
                Log.w(TAG, "setPreferredDevice failed", t)
                false
            }
        }

        // 实际采样率（系统可能改过）
        val actualRate = try {
            rec.sampleRate
        } catch (t: Throwable) {
            rate
        }

        // 双向测试：先起播放线程，再开录音
        var toneThread: Thread? = null
        var tonePlayedMs = 0L
        if (toneFreq != null) {
            toneThread = startTone(toneFreq, seconds)
        }

        var sum = 0.0
        var peak = 0
        var n = 0
        val buf = ShortArray(max(1024, actualRate / 10).coerceAtMost(8192))
        var routedName = ""
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("startRecording 后状态=${rec.recordingState}")
            }
            routedName = try {
                rec.routedDevice?.productName?.toString().orEmpty()
            } catch (t: Throwable) {
                ""
            }
            val t0 = System.currentTimeMillis()
            val durationMs = (seconds * 1000).toLong()
            while (System.currentTimeMillis() - t0 < durationMs) {
                val r = rec.read(buf, 0, buf.size)
                if (r > 0) {
                    for (i in 0 until r) {
                        val v = buf[i].toInt()
                        sum += v.toDouble() * v
                        val a = abs(v)
                        if (a > peak) peak = a
                    }
                    n += r
                } else if (r == AudioRecord.ERROR_DEAD_OBJECT) {
                    throw IllegalStateException("AudioRecord 死掉（ERROR_DEAD_OBJECT）")
                }
            }
            routedName = routedName.ifEmpty {
                try {
                    rec.routedDevice?.productName?.toString().orEmpty()
                } catch (t: Throwable) {
                    ""
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "record failed", t)
            // 释放交给 finally（这里不再重复 release，避免二次释放）
            return Probe(
                false, routedName, usb != null, prefOk, actualRate, 0, 0.0, n,
                0, t.message ?: "录音异常"
            )
        } finally {
            try {
                rec.stop()
            } catch (_: Throwable) {
            }
            try {
                rec.release()
            } catch (_: Throwable) {
            }
        }
        toneThread?.let {
            try {
                it.join(2000)
            } catch (_: InterruptedException) {
            }
            tonePlayedMs = (seconds * 1000).toLong()
        }

        val rms = if (n > 0) sqrt(sum / n).toInt() else 0
        val peakDbfs = if (peak <= 0) -96.0 else 20.0 * log10(peak / 32768.0)
        val name = routedName.ifEmpty { usb?.name ?: "默认输入" }
        Log.i(TAG, "probe: dev=$name usb=${usb != null} pref=$prefOk rate=$actualRate rms=$rms peak=${"%.1f".format(peakDbfs)} dBFS samples=$n")
        return Probe(
            ok = n > 0,
            deviceName = name,
            usbFound = usb != null,
            preferredOk = prefOk,
            sampleRate = actualRate,
            rms = rms,
            peakDbfs = peakDbfs,
            samples = n,
            tonePlayedMs = tonePlayedMs
        )
    }

    /** 播放 [seconds] 秒 440Hz 测试音（STREAM_MUSIC，系统会路由到眼镜扬声器） */
    private fun startTone(freq: Double, seconds: Double): Thread {
        val t = Thread({
            val sr = 44_100
            val total = (sr * seconds).toInt()
            val pcm = ShortArray(total) {
                (6000.0 * sin(2.0 * PI * freq * it / sr)).toInt().toShort()
            }
            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                track = AudioTrack(
                    AudioManager.STREAM_MUSIC, sr,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, sr / 2), AudioTrack.MODE_STREAM
                )
                track.play()
                var off = 0
                while (off < pcm.size) {
                    val w = track.write(pcm, off, min(pcm.size - off, 4096))
                    if (w <= 0) break
                    off += w
                }
                Thread.sleep(120)
            } catch (t: Throwable) {
                Log.w(TAG, "tone failed", t)
            } finally {
                try {
                    track?.stop()
                } catch (_: Throwable) {
                }
                try {
                    track?.release()
                } catch (_: Throwable) {
                }
            }
        }, "tone")
        t.isDaemon = true
        t.start()
        return t
    }
}
