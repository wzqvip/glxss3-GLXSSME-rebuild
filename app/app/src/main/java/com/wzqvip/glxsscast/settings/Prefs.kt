package com.wzqvip.glxsscast.settings

import android.content.Context

/**
 * 全部持久化设置（SharedPreferences 文件 `prefs`）。
 *
 * 收录：
 *  - 画面模式 / 画面调整（zoom、offsetX、offsetY）—— 原有
 *  - 画面 180° 软件翻转（投屏 + 测试图）与 UVC 预览独立翻转
 *  - UVC 分辨率与像素格式（按 `格式名 + 宽 + 高` 记住，换档位时不依赖描述符 index）
 *  - 自动亮度开关
 *  - HID 监听开关
 *  - 自动重连开关
 *
 * 所有 setter 立即写入（apply），重启 App 后由 [MainActivity] / [CastService] 恢复。
 */
class Prefs private constructor(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    companion object {
        const val FILE = "prefs"

        // ---- SharedPreferences key ----
        private const val KEY_MODE = "mode"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_OX = "ox"
        private const val KEY_OY = "oy"
        private const val KEY_FLIP180 = "flip180"
        private const val KEY_UVC_FLIP180 = "uvc_flip180"
        private const val KEY_UVC_FORMAT = "uvc_format"
        private const val KEY_UVC_W = "uvc_w"
        private const val KEY_UVC_H = "uvc_h"
        private const val KEY_AUTO_BRIGHT = "auto_brightness"
        private const val KEY_HID = "hid_enabled"
        private const val KEY_HID_MODE = "hid_mode"
        private const val KEY_HID_CLICK = "hid_click_action"
        private const val KEY_HID_LONG = "hid_long_action"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_ACC_FSR = "acc_fsr"
        private const val KEY_GYRO_FSR = "gyro_fsr"
        private const val KEY_FSR_INIT = "fsr_initialized"
        // 导航 HUD（Google 地图通知 → Pi Wi-Fi UDP）
        private const val KEY_NAV_IP = "nav_host"
        private const val KEY_NAV_PORT = "nav_udp_port"
        private const val KEY_NAV_SEND = "nav_send"

        /** 导航 HUD 默认目标：Pi Zero 2 W 的 Wi-Fi 地址。 */
        const val DEFAULT_NAV_HOST = "192.168.2.128"
        const val DEFAULT_NAV_PORT = 9999

        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context): Prefs =
            inst ?: synchronized(this) {
                inst ?: Prefs(ctx).also { inst = it }
            }
    }

    // ---- 画面模式 / 调整 ----
    var fillMode: Int
        get() = sp.getInt(KEY_MODE, 1)
        set(v) { sp.edit().putInt(KEY_MODE, v).apply() }

    var zoom: Float
        get() = sp.getFloat(KEY_ZOOM, 1f).coerceIn(1f, 2f)
        set(v) { sp.edit().putFloat(KEY_ZOOM, v.coerceIn(1f, 2f)).apply() }

    var offsetX: Float
        get() = sp.getFloat(KEY_OX, 0f).coerceIn(-1f, 1f)
        set(v) { sp.edit().putFloat(KEY_OX, v.coerceIn(-1f, 1f)).apply() }

    var offsetY: Float
        get() = sp.getFloat(KEY_OY, 0f).coerceIn(-1f, 1f)
        set(v) { sp.edit().putFloat(KEY_OY, v.coerceIn(-1f, 1f)).apply() }

    /**
     * 画面 180° 软件翻转（投屏 + 测试色条共用）。
     *
     * 不用设备侧 roll：原厂 SDK 的 `setRoll` 实际是 0xA1/0x03（见 LcdControl），
     * 我们之前发 0x02 只是屏开关，实测无方向效果。
     */
    var flip180: Boolean
        get() = sp.getBoolean(KEY_FLIP180, false)
        set(v) { sp.edit().putBoolean(KEY_FLIP180, v).apply() }

    /** UVC 预览独立翻转（默认关，不影响投屏/测试图） */
    var uvcFlip180: Boolean
        get() = sp.getBoolean(KEY_UVC_FLIP180, false)
        set(v) { sp.edit().putBoolean(KEY_UVC_FLIP180, v).apply() }

    // ---- UVC 分辨率/格式选择（按名字+尺寸匹配） ----
    var uvcFormat: String
        get() = sp.getString(KEY_UVC_FORMAT, "") ?: ""
        set(v) { sp.edit().putString(KEY_UVC_FORMAT, v).apply() }

    var uvcWidth: Int
        get() = sp.getInt(KEY_UVC_W, 0)
        set(v) { sp.edit().putInt(KEY_UVC_W, v).apply() }

    var uvcHeight: Int
        get() = sp.getInt(KEY_UVC_H, 0)
        set(v) { sp.edit().putInt(KEY_UVC_H, v).apply() }

    /** 是否已经保存过 UVC 档位 */
    val hasUvcSelection: Boolean get() = uvcWidth > 0 && uvcHeight > 0 && uvcFormat.isNotEmpty()

    fun saveUvc(formatName: String, w: Int, h: Int) {
        sp.edit()
            .putString(KEY_UVC_FORMAT, formatName)
            .putInt(KEY_UVC_W, w)
            .putInt(KEY_UVC_H, h)
            .apply()
    }

    // ---- 开关 ----
    var autoBrightness: Boolean
        get() = sp.getBoolean(KEY_AUTO_BRIGHT, false)
        set(v) { sp.edit().putBoolean(KEY_AUTO_BRIGHT, v).apply() }

    var hidEnabled: Boolean
        get() = sp.getBoolean(KEY_HID, false)
        set(v) { sp.edit().putBoolean(KEY_HID, v).apply() }

    /** HID 读法：poll（0xA1/0x16，默认）/ interrupt（0x83 备选） */
    var hidMode: String
        get() = sp.getString(KEY_HID_MODE, "poll") ?: "poll"
        set(v) { sp.edit().putString(KEY_HID_MODE, v).apply() }

    /** 单击绑定（默认拍照：UVC snapshot） */
    var hidClickAction: String
        get() = sp.getString(KEY_HID_CLICK, "snapshot") ?: "snapshot"
        set(v) { sp.edit().putString(KEY_HID_CLICK, v).apply() }

    /** 长按绑定（默认投屏开关） */
    var hidLongAction: String
        get() = sp.getString(KEY_HID_LONG, "cast") ?: "cast"
        set(v) { sp.edit().putString(KEY_HID_LONG, v).apply() }

    var autoReconnect: Boolean
        get() = sp.getBoolean(KEY_AUTO_RECONNECT, true)
        set(v) { sp.edit().putBoolean(KEY_AUTO_RECONNECT, v).apply() }

    // ---- 传感器量程（索引 0..3，与固件 0x06 的 acc_fsr / gyro_fsr 一致） ----
    /** 默认 ±4g：实测 1g 对应 raw≈8192（32768/8192 = 4） */
    var accFsr: Int
        get() = sp.getInt(KEY_ACC_FSR, 1).coerceIn(0, 3)
        set(v) { sp.edit().putInt(KEY_ACC_FSR, v.coerceIn(0, 3)).apply() }

    /** 默认 ±2000dps；连上眼镜后由 0x06 报的硬件值覆盖（首次） */
    var gyroFsr: Int
        get() = sp.getInt(KEY_GYRO_FSR, 3).coerceIn(0, 3)
        set(v) { sp.edit().putInt(KEY_GYRO_FSR, v.coerceIn(0, 3)).apply() }

    /** 是否已经按硬件 0x06 / 用户选择初始化过量程（初始化后不再被 0x06 覆盖） */
    var fsrInitialized: Boolean
        get() = sp.getBoolean(KEY_FSR_INIT, false)
        set(v) { sp.edit().putBoolean(KEY_FSR_INIT, v).apply() }

    // ---- 导航 HUD（Google 地图通知 → Pi Wi-Fi UDP，默认 192.168.2.128:9999） ----
    /** Pi 的地址。 */
    var navHost: String
        get() = sp.getString(KEY_NAV_IP, DEFAULT_NAV_HOST)?.trim()
            ?.ifEmpty { DEFAULT_NAV_HOST } ?: DEFAULT_NAV_HOST
        set(v) { sp.edit().putString(KEY_NAV_IP, v.trim()).apply() }

    /** UDP 端口。 */
    var navPort: Int
        get() = sp.getInt(KEY_NAV_PORT, DEFAULT_NAV_PORT).coerceIn(1, 65535)
        set(v) { sp.edit().putInt(KEY_NAV_PORT, v.coerceIn(1, 65535)).apply() }

    /** 是否把导航包发到 Pi（默认开）。 */
    var navSend: Boolean
        get() = sp.getBoolean(KEY_NAV_SEND, true)
        set(v) { sp.edit().putBoolean(KEY_NAV_SEND, v).apply() }
}
