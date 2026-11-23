package com.wzqvip.glxsscast.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路名提取（[NavParser.extractRoad]）：中文 / 英文 / 无路名 / 环岛 / 车道。
 *
 * 只测纯文本逻辑，不碰 Android 通知和图标（[NavParser.parseFields] 传 icon=null
 * 也不会走 IconMatcher）。
 */
class NavParserRoadTest {

    private fun road(text: String): String = NavParser.extractRoad(text)

    // ---- 中文 ----
    @Test
    fun chineseEnterWithSpace() {
        assertEquals("人民路", road("右转进入 人民路"))
    }

    @Test
    fun chineseEnterWithoutSpace() {
        assertEquals("解放路", road("左转驶入解放路"))
    }

    @Test
    fun chineseAlongWithMotionTail() {
        assertEquals("中山东路", road("沿 中山东路 直行"))
        assertEquals("中山东路", road("沿中山东路直行"))
    }

    @Test
    fun chineseHighwayKeepsRouteNumber() {
        assertEquals("G6 京藏高速", road("驶入 G6 京藏高速"))
    }

    @Test
    fun chineseLaneIsNotRoad() {
        assertEquals("", road("进入左侧车道"))
    }

    // ---- 英文 ----
    @Test
    fun englishOnto() {
        assertEquals("Main St", road("Turn right onto Main St"))
    }

    @Test
    fun englishOnWithDistanceTail() {
        assertEquals("Elm Street", road("Continue on Elm Street for 2 km"))
    }

    @Test
    fun englishHighway() {
        assertEquals("US-101", road("Merge onto US-101"))
    }

    // ---- 无路名 ----
    @Test
    fun noRoad() {
        assertEquals("", road("到达 目的地"))
        assertEquals("", road("前方掉头"))
        assertEquals("", road("继续行驶"))
    }

    // ---- 环岛 ----
    @Test
    fun roundaboutChineseHasNoRoad() {
        assertEquals("", road("进入环岛，第 2 个出口驶出"))
    }

    @Test
    fun roundaboutEnglishKeepsExitRoad() {
        assertEquals("Oak Ave", road("At the roundabout, take the 2nd exit onto Oak Ave"))
    }

    // ---- 链路：label 保持完整，road 单独给 ----
    @Test
    fun parseFieldsKeepsFullLabelAndSeparateRoad() {
        val frame = NavParser.parseFields(
            packageName = NavParser.MAPS_PACKAGE,
            title = "右转进入 人民路",
            text = "500 米",
            subText = "12 分钟 · 3.2 公里",
            category = "navigation",
            icon = null,
        )
        assertTrue(frame != null)
        assertEquals("右转进入 人民路", frame!!.label)
        assertEquals("人民路", frame.road)
        assertEquals(Maneuver.RIGHT, frame.maneuver)
    }
}
