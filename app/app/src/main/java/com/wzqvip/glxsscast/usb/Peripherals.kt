package com.wzqvip.glxsscast.usb

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.wzqvip.glxsscast.diag.Oplog
import kotlin.math.sin
import kotlin.math.sqrt

/** 外设：HID 报文读取、音频（标准 UAC，系统已路由到眼镜）。 */
object Peripherals {
    private const val TAG = "Peripherals"

    @Volatile var hidRunning = false; private set
    @Volatile var hidPackets = 0L; private set
    @Volatile var lastHidHex = ""; private set

    private var hidThread: Thread? = null

    fun startHid(link: UsbLink) {
        if (hidRunning) return
        val ep = link.openHidEndpoint()
        if (ep == null) { lastHidHex = "no HID endpoint"; Oplog.log("HID", "legacy interrupt: no endpoint"); return }
        hidRunning = true
        Oplog.log("HID", "legacy interrupt reader start (0x83)")
        val t = Thread {
            val buf = ByteArray(64)
            while (hidRunning) {
                val n = try { link.readInterrupt(ep, buf, 500) } catch (e: Exception) { -1 }
                if (n > 0) {
                    hidPackets++
                    val sb = StringBuilder()
                    for (i in 0 until n) sb.append(String.format("%02x", buf[i]))
                    lastHidHex = sb.toString()
                } else {
                    try { Thread.sleep(50) } catch (e: InterruptedException) { break }
                }
            }
        }
        t.isDaemon = true
        hidThread = t
        t.start()
        Log.i(TAG, "HID reader started")
    }

    fun stopHid() { hidRunning = false; hidThread = null; Oplog.logf("HID", "legacy interrupt reader stop (%d pkts)", hidPackets) }

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
                if (r > 0) {
                    for (i in 0 until r) { sum += buf[i].toDouble() * buf[i]; n++ }
                }
            }
            rec.stop()
            rec.release()
            if (n == 0) 0 else sqrt(sum / n).toInt()
        } catch (e: Exception) { Log.e(TAG, "mic failed", e); -1 }
    }
}
