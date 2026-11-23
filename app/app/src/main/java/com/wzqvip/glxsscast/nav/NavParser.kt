package com.wzqvip.glxsscast.nav

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONObject

/**
 * Google 地图导航通知 → [NavFrame]。
 *
 * 解析字段（Notification.extras）：
 * | 字段 | Maps 内容 | 用途 |
 * |---|---|---|
 * | EXTRA_TITLE | 转向指令（`右转进入 人民路` / `Turn right onto Main St`） | label |
 * | EXTRA_TEXT | 距离（`500 米` / `500 m`） | distance |
 * | EXTRA_SUB_TEXT / EXTRA_INFO_TEXT | `12 分钟 · 3.2 公里` | eta / remain |
 * | EXTRA_BIG_TEXT | 展开后的整段文字（部分版本） | 兜底解析 |
 * | category == CATEGORY_NAVIGATION | 导航类 | 判定“这是导航通知” |
 * | getLargeIcon() | 机动图标（左/右/直行/环岛/到达） | 图标相似度兜底 |
 *
 * **不依赖图标资源 id**：图标只是可选证据。文本关键词（中/英）优先；文本给不出
 * 机动类型时，才把大图标缩成 24×24 二值剪影，与本地用 Canvas 画的模板比 IoU
 * （相似度），置信度不够就保持 [Maneuver.UNKNOWN]。
 */
object NavParser {
    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    /** 文本关键词规则：从具体到笼统（环岛里的“右转”不能当普通右转）。 */
    private val KEYWORDS: List<Pair<Maneuver, List<String>>> = listOf(
        Maneuver.ARRIVE to listOf(
            "到达", "抵达", "目的地", "终点", "已到",
            "arrive", "arrived", "destination"
        ),
        Maneuver.ROUNDABOUT to listOf(
            "环岛", "环状交叉", "回旋", "roundabout", "rotary", "traffic circle"
        ),
        Maneuver.UTURN to listOf(
            "掉头", "调头", "掉頭", "u-turn", "uturn", "u turn"
        ),
        Maneuver.SLIGHT_RIGHT to listOf(
            "稍向右", "稍微向右", "向右前方", "右前方", "靠右",
            "slight right", "bear right", "keep right", "stay right"
        ),
        Maneuver.SLIGHT_LEFT to listOf(
            "稍向左", "稍微向左", "向左前方", "左前方", "靠左",
            "slight left", "bear left", "keep left", "stay left"
        ),
        Maneuver.RIGHT to listOf(
            "右转", "向右", "右拐", "往右",
            "turn right", "right turn", "right onto", "onto the right"
        ),
        Maneuver.LEFT to listOf(
            "左转", "向左", "左拐", "往左",
            "turn left", "left turn", "left onto", "onto the left"
        ),
        Maneuver.STRAIGHT to listOf(
            "直行", "直走", "继续直行", "沿路直行", "前方直行",
            // 车机模式（Maps 在 Android Auto/驾驶模式下的 navigation_status 通知）用
            // “驶向 X 路 / 前往 X”表示继续沿路走，不是转弯。
            "驶向", "前往", "开往",
            "straight", "continue", "go straight", "keep straight",
            "heading to", "head toward", "toward"
        ),
    )

    private val DISTANCE_RE = Regex(
        """(\d+(?:[.,]\d+)?)\s*(公里|千米|km|米|mi|英里|ft|英尺|m)(?![A-Za-z])""",
        RegexOption.IGNORE_CASE
    )
    private val DURATION_RE = Regex(
        """(\d+)\s*(小时|钟头|hours?|hrs?|h|分钟|分|minutes?|mins?|min)(?![A-Za-z])""",
        RegexOption.IGNORE_CASE
    )
    private val LESS_THAN_MIN_RE = Regex("""[<＜]\s*1\s*(分|min)""", RegexOption.IGNORE_CASE)

    // ---- 路名（从指令里单独抽出来给 HUD 第二行放大显示） ----
    /** 中文：“进入/驶入/转入/并入/驶上/沿(着)” 后面的路名；允许路名里带空格（`G6 京藏高速`）。 */
    private val ZH_ROAD_RE = Regex("""(?:进入|驶入|转入|并入|驶上|沿着|沿)\s*([^，。；,;、!?]{1,24})""")

    /** 中文道路通名（长的在前），用它把 `人民路后靠右` 截成 `人民路`。 */
    private val ZH_ROAD_SUFFIX = Regex("""(?:高速公路|高速|公路|大道|大街|胡同|路|街|道|巷|弄|桥|环岛)""")

    /** 没有通名时，再按动作词截断（`进入隧道随后直行` → `隧道`）。 */
    private val ZH_ROAD_TAIL_RE = Regex(
        """(?:直行|行驶|前行|前进|继续|方向|靠左|靠右|掉头|右转|左转|向东|向西|向南|向北|往东|往西|往南|往北).*"""
    )

    /** 这些是动作/车道/环岛本身，不是“要去的路名”。 */
    private val ZH_ROAD_REJECT = setOf(
        "环岛", "环状交叉", "回旋", "目的地", "终点", "出口", "匝道", "收费站",
        "左侧车道", "右侧车道", "中间车道", "公交车道", "应急车道",
    )

    /** 英文：onto / along / on 后面的路名。 */
    private val EN_ROAD_RE = Regex(
        """\b(onto|along|on)\s+([^,.;!?]{2,60})""",
        RegexOption.IGNORE_CASE
    )

    /** 英文截止词：`on Main St for 500 m`、`onto US-101 and then...`。 */
    private val EN_ROAD_TAIL_RE = Regex(
        """\s+(?:for|and|then|to|in|at|near|towards?|via)\b.*""",
        RegexOption.IGNORE_CASE
    )

    private val EN_ROAD_REJECT = setOf(
        "right", "left", "the right", "the left", "roundabout", "the roundabout",
        "lane", "the lane", "exit", "the exit", "road", "the road",
    )

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------
    /** 解析一条 StatusBarNotification 的 Notification；不是导航返回 null。 */
    fun parseNotification(
        notification: Notification?,
        packageName: String = MAPS_PACKAGE,
        context: android.content.Context? = null,
    ): NavFrame? {
        if (notification == null) return null
        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()
        // 大图标是机动类型（左/右/直行/环岛/到达），只当“兜底证据”用。
        // 公开 API 只能 loadDrawable（Icon.getBitmap 是 @hide），再画成位图。
        val icon: Bitmap? = if (context != null) {
            try {
                drawableToBitmap(notification.getLargeIcon()?.loadDrawable(context))
            } catch (t: Throwable) {
                null
            }
        } else {
            null
        }
        return parseFields(
            packageName = packageName,
            title = title,
            text = text,
            bigText = bigText,
            subText = subText,
            infoText = infoText,
            category = notification.category,
            ongoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            icon = icon,
        )
    }

    /**
     * 核心解析（纯函数，可在“模拟一条通知”里直接复用）。
     *
     * @return 不是导航通知（没有指令也没有距离/机动线索）时返回 null。
     */
    fun parseFields(
        packageName: String,
        title: String,
        text: String,
        bigText: String = "",
        subText: String = "",
        infoText: String = "",
        category: String? = null,
        ongoing: Boolean = false,
        icon: Bitmap? = null,
        simulated: Boolean = false,
    ): NavFrame? {
        val titleClean = clean(title)
        val textClean = clean(text)
        val bigClean = clean(bigText)
        val subClean = clean(subText)
        val infoClean = clean(infoText)
        if (titleClean.isEmpty() && textClean.isEmpty() && bigClean.isEmpty()) return null

        // 1) 距离：正文 > 标题 > 大文本
        val distance = parseDistance(textClean)
            ?: parseDistance(titleClean)
            ?: parseDistance(bigClean)

        // 2) 剩余时间 / 剩余距离：副标题 > 信息栏 > 大文本；ETA 再兜底正文
        //    （部分 Maps 版本把 "500 米 · 12 分钟" 都塞进 EXTRA_TEXT）。
        val extras = listOf(subClean, infoClean, bigClean).filter { it.isNotEmpty() }
        val eta = extras.firstNotNullOfOrNull { parseDuration(it) }
            ?: parseDuration(textClean)
        val remain = extras.firstNotNullOfOrNull { parseDistance(it) }

        // 3) 指令 / 机动类型
        //    **字段顺序不固定**：普通手机通知是 title=指令、text=距离；而 Maps 的
        //    navigation_status（车机/驾驶模式）反过来：title=距离（"0 英尺"）、
        //    text=“驶向 XX 路”。所以按“像不像纯数值”来挑指令文字。
        val label = pickLabel(titleClean, textClean, bigClean)
        // 路名只在指令文字里找；label 保持完整（HUD 第一行仍显示整句）。
        val road = extractRoad(label.ifEmpty { textClean }.ifEmpty { bigClean })
        val haystack = (titleClean + " " + textClean + " " + bigClean).lowercase()
        var maneuver = keywordManeuver(haystack)
        var iconGuess = Maneuver.UNKNOWN
        var iconScore = 0.0
        if (maneuver == Maneuver.UNKNOWN && icon != null) {
            val (guess, score) = IconMatcher.detect(icon)
            iconGuess = guess
            iconScore = score
            if (guess != Maneuver.UNKNOWN) maneuver = guess
        }

        val isNavigation = category == Notification.CATEGORY_NAVIGATION ||
            maneuver != Maneuver.UNKNOWN || distance != null
        if (!isNavigation) return null
        if (label.isEmpty() && distance == null) return null

        return NavFrame(
            label = label.ifEmpty { textClean },
            maneuver = maneuver,
            road = road,
            distanceMeters = distance,
            etaSeconds = eta,
            remainMeters = remain,
            source = "maps",
            title = titleClean,
            text = textClean,
            subText = listOf(subClean, infoClean).filter { it.isNotEmpty() }
                .joinToString(" · "),
            bigText = bigClean,
            packageName = packageName,
            iconGuess = iconGuess,
            iconScore = iconScore,
            simulated = simulated,
        )
    }

    /** 调试用：造一条“假通知”走同一套解析（不经过系统通知栏）。 */
    fun simulate(
        maneuver: Maneuver,
        distanceMeters: Double? = 500.0,
        etaSeconds: Long? = 120,
        remainMeters: Double? = 2400.0,
        label: String? = null,
        withIcon: Boolean = true,
    ): NavFrame {
        val zh = label ?: when (maneuver) {
            Maneuver.RIGHT -> "右转进入 人民路"
            Maneuver.LEFT -> "左转进入 解放路"
            Maneuver.STRAIGHT -> "沿 中山东路 直行"
            Maneuver.SLIGHT_RIGHT -> "稍向右前方 靠右行驶"
            Maneuver.SLIGHT_LEFT -> "稍向左前方 靠左行驶"
            Maneuver.ROUNDABOUT -> "进入环岛，第 2 个出口驶出"
            Maneuver.UTURN -> "前方掉头"
            Maneuver.ARRIVE -> "到达 目的地"
            Maneuver.UNKNOWN -> "继续行驶"
        }
        val text = if (distanceMeters != null) NavFrame.distanceText(distanceMeters) else ""
        val sub = listOfNotNull(
            etaSeconds?.let { NavFrame.etaText(it) },
            remainMeters?.let { NavFrame.distanceText(it) }
        ).joinToString(" · ")
        val icon = if (withIcon) IconMatcher.templateBitmap(maneuver) else null
        return parseFields(
            packageName = MAPS_PACKAGE,
            title = zh,
            text = text,
            subText = sub,
            category = Notification.CATEGORY_NAVIGATION,
            ongoing = true,
            icon = icon,
            simulated = true,
        ) ?: NavFrame(
            label = zh, maneuver = maneuver, road = extractRoad(zh),
            distanceMeters = distanceMeters,
            etaSeconds = etaSeconds, remainMeters = remainMeters,
            source = "maps", title = zh, text = text, subText = sub,
            simulated = true,
        )
    }

    // ------------------------------------------------------------------
    // 文本解析
    // ------------------------------------------------------------------
    /** 任意 Drawable（BitmapDrawable / AdaptiveIconDrawable…）→ 位图，供图标比对。 */
    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable?): Bitmap? {
        if (drawable == null) return null
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            if (bitmap.width == 0 || bitmap.height == 0) null else bitmap
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 只含数值 + 单位的字符串（`0 英尺`、`500 m`、`12 分钟`）不算“指令”，
     * 用来在 title/text 顺序不固定时挑出真正的那一行。
     */
    fun isBareMeasurement(text: String): Boolean {
        if (text.isBlank()) return false
        var stripped = DISTANCE_RE.replace(text, " ")
        stripped = DURATION_RE.replace(stripped, " ")
        stripped = LESS_THAN_MIN_RE.replace(stripped, " ")
        stripped = stripped.replace(Regex("""[\s·,，.、:：/<>＜()（）\-—]"""), "")
        return stripped.isEmpty()
    }

    /** 指令文字：title > text > bigText，跳过纯数值项（见 [isBareMeasurement]）。 */
    fun pickLabel(title: String, text: String, bigText: String): String {
        for (candidate in listOf(title, text, bigText)) {
            if (candidate.isNotBlank() && !isBareMeasurement(candidate)) return candidate
        }
        return title.ifBlank { bigText }
    }

    fun clean(value: String?): String =
        (value ?: "").replace('\n', ' ').replace('\r', ' ').trim()
            .replace(Regex("""\s+"""), " ")

    /**
     * 从转向指令里抽出**路名**；抽不出来返回空串（[label] 本身保持完整指令）。
     *
     * 中文：`右转进入 人民路` / `左转驶入解放路` / `沿 中山东路 直行` /
     * `驶入 G6 京藏高速`；英文：`Turn right onto Main St` /
     * `Continue on Elm Street for 2 km` / `Merge onto US-101`。
     *
     * 环岛、车道、目的地这类不是路名，返回空串；`进入环岛` 后面的出口路名由
     * 下一条指令（`驶出到 XX 路`）给出。
     */
    fun extractRoad(text: String): String {
        val src = clean(text)
        if (src.isEmpty()) return ""
        extractRoadZh(src)?.let { return it }
        return extractRoadEn(src)
    }

    private fun extractRoadZh(text: String): String? {
        for (match in ZH_ROAD_RE.findAll(text)) {
            var name = match.groupValues[1].trim()
            // 先截到第一个道路通名（`人民路后靠右` → `人民路`），没有通名再按动作词截。
            val suffix = ZH_ROAD_SUFFIX.find(name)
            name = if (suffix != null) {
                name.substring(0, suffix.range.last + 1)
            } else {
                name.replace(ZH_ROAD_TAIL_RE, "")
            }
            name = name.trim().trimEnd('的', '。', '，', ',', '.')
            if (name.length >= 2 && name !in ZH_ROAD_REJECT) return name
        }
        return null
    }

    private fun extractRoadEn(text: String): String {
        for (match in EN_ROAD_RE.findAll(text)) {
            val prep = match.groupValues[1].lowercase()
            var name = match.groupValues[2].replace(EN_ROAD_TAIL_RE, "").trim()
            name = name.trimEnd(',', '.', ';', ':', '，', '。', '；')
            if (name.startsWith("the ", ignoreCase = true)) name = name.substring(4).trim()
            val lower = name.lowercase()
            if (name.length < 2 || !name.any { it.isLetter() }) continue
            if (lower in EN_ROAD_REJECT) continue
            // "on the right" 是方向提示，不是路名（此时 "the" 已去掉）。
            if (prep == "on" && (lower == "right" || lower == "left")) continue
            return name
        }
        return ""
    }

    fun keywordManeuver(text: String): Maneuver {
        if (text.isBlank()) return Maneuver.UNKNOWN
        val lower = text.lowercase()
        for ((maneuver, words) in KEYWORDS) {
            for (word in words) {
                if (lower.contains(word)) return maneuver
            }
        }
        return Maneuver.UNKNOWN
    }

    /** `500 米` / `1.2 km` / `1,2 公里` / `800 ft` → 米。 */
    fun parseDistance(text: String): Double? {
        if (text.isBlank()) return null
        val match = DISTANCE_RE.find(text) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val factor = when (unit) {
            "公里", "千米", "km" -> 1000.0
            "米", "m" -> 1.0
            "mi", "英里" -> 1609.344
            "ft", "英尺" -> 0.3048
            else -> 1.0
        }
        return value * factor
    }

    /** `12 分钟` / `1 小时 5 分` / `12 min` → 秒。 */
    fun parseDuration(text: String): Long? {
        if (text.isBlank()) return null
        var seconds = 0L
        var found = false
        for (match in DURATION_RE.findAll(text)) {
            val value = match.groupValues[1].toLongOrNull() ?: continue
            val unit = match.groupValues[2].lowercase()
            seconds += when (unit) {
                "小时", "钟头", "h", "hr", "hrs", "hour", "hours" -> value * 3600
                else -> value * 60
            }
            found = true
        }
        if (!found && LESS_THAN_MIN_RE.containsMatchIn(text)) return 30L
        return if (found) seconds else null
    }

    /**
     * 图标相似度：把大图标缩成 24×24 二值剪影，和本地 Canvas 模板比 IoU。
     * 只作为兜底；阈值保守，宁可不猜（UNKNOWN）也不乱猜。
     */
    object IconMatcher {
        private const val GRID = 24
        private const val TEMPLATE_SIZE = 96
        private const val MIN_SCORE = 0.55
        private const val MIN_MARGIN = 0.05

        private val templateCache: Map<Maneuver, BooleanArray> by lazy {
            listOf(
                Maneuver.LEFT, Maneuver.RIGHT, Maneuver.STRAIGHT,
                Maneuver.UTURN, Maneuver.ROUNDABOUT, Maneuver.ARRIVE,
                Maneuver.SLIGHT_LEFT, Maneuver.SLIGHT_RIGHT
            ).associateWith { toMask(templateBitmap(it)) ?: BooleanArray(GRID * GRID) }
        }

        fun detect(bitmap: Bitmap?): Pair<Maneuver, Double> {
            val mask = bitmap?.let { toMask(it) } ?: return Maneuver.UNKNOWN to 0.0
            var best = Maneuver.UNKNOWN
            var bestScore = 0.0
            var second = 0.0
            for ((maneuver, template) in templateCache) {
                val score = iou(mask, template)
                if (score > bestScore) {
                    second = bestScore
                    bestScore = score
                    best = maneuver
                } else if (score > second) {
                    second = score
                }
            }
            return if (bestScore >= MIN_SCORE && bestScore - second >= MIN_MARGIN) {
                best to bestScore
            } else {
                Maneuver.UNKNOWN to bestScore
            }
        }

        /** 自检：用模板当输入跑一遍识别，返回命中数/总数。 */
        fun selfTest(): String {
            var passed = 0
            val detail = StringBuilder()
            for ((expected, _) in templateCache) {
                val (guess, score) = detect(templateBitmap(expected))
                if (guess == expected) passed++
                detail.append(expected.key).append("→").append(guess.key)
                    .append(String.format(java.util.Locale.US, "(%.2f) ", score))
            }
            return "$passed/${templateCache.size}  $detail"
        }

        fun templateBitmap(maneuver: Maneuver): Bitmap {
            val bitmap = Bitmap.createBitmap(
                TEMPLATE_SIZE, TEMPLATE_SIZE, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            val unit = TEMPLATE_SIZE / 100.0f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 14f * unit
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            fun x(v: Float) = TEMPLATE_SIZE / 2f + v * unit
            fun y(v: Float) = TEMPLATE_SIZE / 2f + v * unit
            fun stroke(points: List<Pair<Float, Float>>) {
                if (points.size < 2) return
                val path = Path()
                path.moveTo(x(points[0].first), y(points[0].second))
                for (point in points.drop(1)) path.lineTo(x(point.first), y(point.second))
                canvas.drawPath(path, paint)
            }
            fun fill(points: List<Pair<Float, Float>>) {
                if (points.size < 3) return
                val path = Path()
                path.moveTo(x(points[0].first), y(points[0].second))
                for (point in points.drop(1)) path.lineTo(x(point.first), y(point.second))
                path.close()
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                })
            }
            fun head(tip: Pair<Float, Float>, dx: Float, dy: Float,
                     length: Float = 20f, half: Float = 16f): List<Pair<Float, Float>> {
                val norm = kotlin.math.hypot(dx, dy).takeIf { it > 0f } ?: 1f
                val ux = dx / norm
                val uy = dy / norm
                val bx = tip.first - ux * length
                val by = tip.second - uy * length
                return listOf(
                    tip,
                    bx - uy * half to by + ux * half,
                    bx + uy * half to by - ux * half
                )
            }
            when (maneuver) {
                Maneuver.LEFT, Maneuver.RIGHT -> {
                    val sign = if (maneuver == Maneuver.RIGHT) 1f else -1f
                    stroke(listOf(0f to 34f, 0f to -10f, 20f * sign to -10f))
                    fill(head(40f * sign to -10f, sign, 0f))
                }
                Maneuver.SLIGHT_LEFT, Maneuver.SLIGHT_RIGHT -> {
                    val sign = if (maneuver == Maneuver.SLIGHT_RIGHT) 1f else -1f
                    stroke(listOf(0f to 34f, 0f to 10f, 24f * sign to -14f))
                    fill(head(30f * sign to -20f, sign, -1f))
                }
                Maneuver.UTURN -> {
                    stroke(listOf(14f to 34f, 14f to -4f))
                    val arc = (0..12).map { step ->
                        val angle = Math.toRadians(-180.0 * step / 12.0)
                        (14f * kotlin.math.cos(angle).toFloat()) to
                            (-4f + 14f * kotlin.math.sin(angle).toFloat())
                    }
                    stroke(arc)
                    stroke(listOf(-14f to -4f, -14f to 14f))
                    fill(head(-14f to 32f, 0f, 1f))
                }
                Maneuver.ROUNDABOUT -> {
                    val ring = (0..15).map { step ->
                        val angle = Math.toRadians(240.0 - 300.0 * step / 15.0)
                        (20f * kotlin.math.cos(angle).toFloat()) to
                            (2f + 20f * kotlin.math.sin(angle).toFloat())
                    }
                    stroke(ring)
                    stroke(listOf(0f to 46f, 0f to 22f))
                    fill(head(-2f to -23f, -1f, -0.6f))
                }
                Maneuver.ARRIVE -> {
                    stroke(listOf(-14f to 36f, -14f to -36f))
                    fill(listOf(-14f to -36f, 30f to -24f, -14f to -12f))
                    stroke(listOf(-30f to 36f, -2f to 36f))
                }
                else -> fill(
                    listOf(
                        -9f to 34f, 9f to 34f, 9f to -6f, 22f to -6f,
                        0f to -36f, -22f to -6f, -9f to -6f
                    )
                )
            }
            return bitmap
        }

        private fun toMask(bitmap: Bitmap): BooleanArray? {
            val scaled = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
            val pixels = IntArray(64 * 64)
            scaled.getPixels(pixels, 0, 64, 0, 0, 64, 64)
            if (scaled !== bitmap) scaled.recycle()

            // 先找墨迹：透明图标看 alpha；不透明图标看“与角落背景色的距离”。
            var transparent = false
            for (pixel in pixels) {
                if (Color.alpha(pixel) < 250) {
                    transparent = true
                    break
                }
            }
            val background = pixels[0]
            val ink = BooleanArray(64 * 64)
            var count = 0
            for (index in pixels.indices) {
                val pixel = pixels[index]
                val on = if (transparent) {
                    Color.alpha(pixel) > 40
                } else {
                    val dr = Color.red(pixel) - Color.red(background)
                    val dg = Color.green(pixel) - Color.green(background)
                    val db = Color.blue(pixel) - Color.blue(background)
                    dr * dr + dg * dg + db * db > 3600
                }
                ink[index] = on
                if (on) count++
            }
            if (count < 16) return null

            var minX = 63
            var minY = 63
            var maxX = 0
            var maxY = 0
            for (index in ink.indices) {
                if (!ink[index]) continue
                val px = index % 64
                val py = index / 64
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
            }
            val width = maxX - minX + 1
            val height = maxY - minY + 1
            val mask = BooleanArray(GRID * GRID)
            for (gy in 0 until GRID) {
                for (gx in 0 until GRID) {
                    val sx = minX + ((gx + 0.5) * width / GRID).toInt()
                        .coerceIn(minX, maxX)
                    val sy = minY + ((gy + 0.5) * height / GRID).toInt()
                        .coerceIn(minY, maxY)
                    mask[gy * GRID + gx] = ink[sy * 64 + sx]
                }
            }
            return mask
        }

        private fun iou(a: BooleanArray, b: BooleanArray): Double {
            var intersection = 0
            var union = 0
            for (index in a.indices) {
                if (a[index] || b[index]) union++
                if (a[index] && b[index]) intersection++
            }
            return if (union == 0) 0.0 else intersection.toDouble() / union
        }
    }

    /** 调试页展示用：一包 JSON 的字段摘要。 */
    fun summary(frame: NavFrame?, fallback: String = ""): String {
        if (frame == null) return fallback.ifEmpty { "（尚未收到导航通知）" }
        val obj = try {
            JSONObject(frame.toJson())
        } catch (t: Throwable) {
            null
        }
        return buildString {
            append("label=").append(frame.label).append('\n')
            if (frame.road.isNotEmpty()) {
                append("road=").append(frame.road).append('\n')
            }
            append("maneuver=").append(frame.maneuver.key)
            append("  distance=").append(NavFrame.distanceText(frame.distanceMeters))
            append("  eta=").append(NavFrame.etaText(frame.etaSeconds))
            append("  remain=").append(NavFrame.distanceText(frame.remainMeters))
            append('\n')
            if (frame.iconGuess != Maneuver.UNKNOWN || frame.iconScore > 0.0) {
                append("icon-guess=").append(frame.iconGuess.key)
                append(String.format(java.util.Locale.US, "(%.2f)", frame.iconScore))
                append('\n')
            }
            append("json=").append(obj?.toString() ?: frame.toJson())
        }
    }
}
