package com.wzqvip.glxsscast.settings

import android.content.Context

/**
 * 全部持久化设置（SharedPreferences 文件 `prefs`）。
 *
 * 收录：
 *  - 画面模式 / 画面调整（zoom、offsetX、offsetY）—— 原有
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
        private const val KEY_UVC_FORMAT = "uvc_format"
        private const val KEY_UVC_W = "uvc_w"
        private const val KEY_UVC_H = "uvc_h"
        private const val KEY_AUTO_BRIGHT = "auto_brightness"
        private const val KEY_HID = "hid_enabled"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"

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

    var autoReconnect: Boolean
        get() = sp.getBoolean(KEY_AUTO_RECONNECT, true)
        set(v) { sp.edit().putBoolean(KEY_AUTO_RECONNECT, v).apply() }
}
