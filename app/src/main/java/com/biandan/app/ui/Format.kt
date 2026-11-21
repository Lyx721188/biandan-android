package com.biandan.app.ui

import androidx.compose.ui.graphics.Color
import com.biandan.app.data.RepeatRule

/** 便单待办单色表 0–24：默认 + 鲜亮/柔和/莫兰迪 × 红橙黄绿湖蓝浅蓝深紫 */
object ListPalette {
    private const val DEFAULT = 0xFF9AA3AE

    private val vivid = longArrayOf(
        0xFFE5484D, 0xFFF76B15, 0xFFF5B800, 0xFF30A46C,
        0xFF00A2C7, 0xFF0090FF, 0xFF3E63DD, 0xFF8E4EC6,
    )
    private val muted = longArrayOf(
        0xFFFF8F8F, 0xFFFFB27A, 0xFFFFDD66, 0xFF86D9AE,
        0xFF7ADCE8, 0xFF7CC7FF, 0xFF9AACFF, 0xFFCD9BEE,
    )
    private val morandi = longArrayOf(
        0xFFC98B8B, 0xFFD3A583, 0xFFD8C77E, 0xFF9ABF9A,
        0xFF8FBFC5, 0xFF8FAFD0, 0xFF9AA5C9, 0xFFB29AC0,
    )

    fun color(index: Int): Color = when {
        index <= 0 -> Color(DEFAULT)
        index in 1..8 -> Color(vivid[index - 1])
        index in 9..16 -> Color(muted[index - 9])
        index in 17..24 -> Color(morandi[index - 17])
        else -> Color(DEFAULT)
    }

    val groups = listOf("鲜亮" to (1..8), "柔和" to (9..16), "莫兰迪" to (17..24))
}

/** 循环规则 → 中文描述 */
fun describeRule(rule: RepeatRule?): String {
    if (rule == null) return "不重复"
    val dayNames = listOf("日", "一", "二", "三", "四", "五", "六")
    return when (rule.mode) {
        "daily" -> "每天"
        "workdays" -> "工作日"
        "days_off" -> "休息日"
        "ebbinghaus" -> "艾宾浩斯"
        "weekly_period" -> "每周"
        "monthly_period" -> "每月"
        "yearly_period" -> "每年"
        "custom" -> buildString {
            val interval = rule.interval
            when (rule.unit) {
                "day" -> append(if (interval == 1) "每天" else "每${interval}天")
                "week" -> {
                    append(if (interval == 1) "每周" else "每${interval}周")
                    if (rule.dates.isNotEmpty()) {
                        append(rule.dates.mapNotNull { it.toIntOrNull() }
                            .sorted().joinToString("、") { dayNames[it] })
                    }
                }
                "month" -> {
                    append(if (interval == 1) "每月" else "每${interval}月")
                    append(rule.dates.joinToString("、") { if (it == "32") "最后一天" else "${it}日" })
                }
                "year" -> {
                    append(if (interval == 1) "每年" else "每${interval}年")
                    append(rule.dates.joinToString("、"))
                }
                "lunar_month" -> {
                    append(if (interval == 1) "农历每月" else "农历每${interval}月")
                    append(rule.dates.joinToString("、") { "${it}日" })
                }
                "lunar_year" -> {
                    append(if (interval == 1) "农历每年" else "农历每${interval}年")
                    append(rule.dates.joinToString("、"))
                }
                else -> append("自定义")
            }
            when (rule.dayFilter) {
                "workdays" -> append("（仅工作日）")
                "days_off" -> append("（仅休息日）")
            }
            if (rule.durationDays > 1) append("，每次${rule.durationDays}天")
        }
        else -> "重复"
    }
}

fun statusLabel(status: String) = when (status) {
    "pending" -> "待办"
    "completed" -> "已完成"
    "abandoned" -> "已放弃"
    else -> status
}
