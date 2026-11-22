package com.wzqvip.glxsscast.stress

import android.os.Process
import android.util.Log
import java.io.File

/**
 * 进程 CPU 采样（压力测试用）。
 *
 * - 主路径：读 `/proc/self/stat` 的第 14/15 字段（utime+stime，单位 jiffies，
 *   Android 上 CLK_TCK = 100 → ×10 ms），拿不到时退回
 *   [android.os.Process.getElapsedCpuTime]（同源数据，单位 ms）；
 * - 归一化：`CPU% = ΔCPU / (Δ墙钟 × 核数) × 100`（每核 100%，全核满载 = 100%）。
 */
object CpuSampler {

    private const val TAG = "CpuSampler"

    fun cores(): Int = Runtime.getRuntime().availableProcessors()

    fun cpuMs(): Long {
        val fromProc = procStatCpuMs()
        if (fromProc >= 0) return fromProc
        return try {
            Process.getElapsedCpuTime()
        } catch (t: Throwable) {
            Log.w(TAG, "getElapsedCpuTime failed", t)
            -1
        }
    }

    /** `/proc/self/stat`：跳过 comm（可能带空格/括号），utime/stime 是剩余字段的第 14/15 个 */
    private fun procStatCpuMs(): Long {
        return try {
            val line = File("/proc/self/stat").readText()
            val end = line.lastIndexOf(')')
            if (end < 0 || end + 2 >= line.length) return -1
            // 从 ") " 之后开始：state 是第 1 个字段，utime 是第 12 个（14 - 2）
            val rest = line.substring(end + 2).trim().split(Regex("\\s+"))
            if (rest.size < 13) return -1
            val utime = rest[11].toLong()
            val stime = rest[12].toLong()
            (utime + stime) * 10L
        } catch (t: Throwable) {
            -1
        }
    }
}
