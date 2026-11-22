package com.wzqvip.glxsscast.settings

/** Fn 按键绑定（单击/长按各自可配；默认：单击=拍照，长按=投屏开关） */
enum class HidAction(val id: String, val label: String) {
    NONE("none", "无"),
    SNAPSHOT("snapshot", "拍照(UVC)"),
    CAST_TOGGLE("cast", "投屏开关"),
    RECORD_TOGGLE("record", "录像开关");

    fun next(): HidAction {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        fun from(id: String?): HidAction =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}
