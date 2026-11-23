package com.wzqvip.glxsscast.nav

import org.json.JSONObject

/**
 * 手机 → Pi 的导航协议（对齐 `linux/glxss/nav.py`）。
 *
 * 每秒一包 UDP JSON（默认 192.168.2.128:9999）：
 * ```
 * {"bearing": null, "distance": 500, "label": "右转进入 人民路",
 *  "heading": null, "maneuver": "right", "eta": 120,
 *  "remain": 2400, "road": "人民路", "source": "maps"}
 * ```
 * * `bearing` / `heading` 固定发 `null`：眼镜没有磁力计，Pi 的 HUD 不再画罗盘，
 *   箭头只按 `maneuver`（或 `label` 关键词）画机动类型。
 * * `distance` 是到下一个动作的距离（米），`eta` 剩余秒数，`remain` 剩余路线米数。
 * * `road` 是从指令里单独抽出的路名（`进入 人民路` → `人民路`），没有时为空串；
 *   `label` 仍是完整指令（不裁掉路名）。
 * * `maneuver` 取值见 [Maneuver.key]；拿不到时发 `unknown`，Pi 会按 `label` 兜底。
 */
enum class Maneuver(val key: String, val zh: String, val en: String) {
    ARRIVE("arrive", "到达", "ARRIVE"),
    ROUNDABOUT("roundabout", "环岛", "ROUNDABOUT"),
    UTURN("uturn", "掉头", "U TURN"),
    SLIGHT_RIGHT("slight-right", "稍向右", "SLIGHT RIGHT"),
    SLIGHT_LEFT("slight-left", "稍向左", "SLIGHT LEFT"),
    RIGHT("right", "右转", "RIGHT"),
    LEFT("left", "左转", "LEFT"),
    STRAIGHT("straight", "直行", "STRAIGHT"),
    UNKNOWN("unknown", "", "");

    companion object {
        fun fromKey(key: String?): Maneuver {
            if (key.isNullOrBlank()) return UNKNOWN
            val normalized = key.trim().lowercase()
                .replace('_', '-').replace(' ', '-')
            return entries.firstOrNull { it.key == normalized } ?: UNKNOWN
        }
    }
}

/**
 * 一条解析好的导航通知。
 *
 * [label] 是通知标题（Maps 的转向指令，如“右转进入 人民路”），会原样发到 Pi，
 * 同时用于 HUD 底部指令条；[road] 是从 [label] 里单独抽出的路名（“人民路”），
 * HUD 第二行放大高亮；[maneuver] 决定 HUD 中心画什么箭头。
 */
data class NavFrame(
    val label: String,
    val maneuver: Maneuver,
    val road: String = "",
    val distanceMeters: Double? = null,
    val etaSeconds: Long? = null,
    val remainMeters: Double? = null,
    val source: String = "maps",
    val title: String = "",
    val text: String = "",
    val subText: String = "",
    val bigText: String = "",
    val packageName: String = "",
    val iconGuess: Maneuver = Maneuver.UNKNOWN,
    val iconScore: Double = 0.0,
    val receivedAt: Long = System.currentTimeMillis(),
    val simulated: Boolean = false,
) {
    val ageMs: Long get() = System.currentTimeMillis() - receivedAt

    fun isFresh(maxAgeMs: Long = 30_000L): Boolean = ageMs <= maxAgeMs

    /** 发给 Pi 的 UDP JSON（字段与 `linux/glxss/nav.py` 一致）。 */
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("bearing", JSONObject.NULL)
        obj.put("heading", JSONObject.NULL)
        obj.put("label", label.take(48))
        obj.put("road", road.take(32))
        obj.put("distance", distanceMeters ?: 0.0)
        obj.put("maneuver", maneuver.key)
        etaSeconds?.let { obj.put("eta", it) }
        remainMeters?.let { obj.put("remain", it) }
        obj.put("source", source)
        return obj.toString()
    }

    companion object {
        fun distanceText(meters: Double?): String {
            if (meters == null || meters < 0) return "--"
            return if (meters >= 1000.0) {
                String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)
            } else {
                String.format(java.util.Locale.US, "%.0f m", meters)
            }
        }

        fun etaText(seconds: Long?): String {
            if (seconds == null) return "--"
            if (seconds < 60) return "<1 分"
            val minutes = ((seconds + 30) / 60).toInt()
            if (minutes < 60) return "$minutes 分"
            val hours = minutes / 60
            val rest = minutes % 60
            return if (rest == 0) "$hours 小时" else "$hours 小时 $rest 分"
        }

        /** 调试页的“剩 2 分 · 2.4 km”一行。 */
        fun remainingText(frame: NavFrame): String {
            val parts = mutableListOf<String>()
            frame.etaSeconds?.let { parts.add(etaText(it)) }
            frame.remainMeters?.let { parts.add(distanceText(it)) }
            return if (parts.isEmpty()) "--" else parts.joinToString(" · ")
        }
    }
}
