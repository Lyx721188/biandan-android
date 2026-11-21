package com.biandan.app.data

import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth

/**
 * 循环规则编解码与实例日期展开，逐行对应 biandan_recurrence.py。
 */
object Recurrence {

    val MODES = mapOf(
        "custom" to 1, "workdays" to 2, "days_off" to 3, "daily" to 4,
        "ebbinghaus" to 5, "weekly_period" to 6, "monthly_period" to 7, "yearly_period" to 8,
    )
    val UNITS = mapOf(
        "day" to 0, "week" to 1, "month" to 2, "year" to 3,
        "lunar_month" to 4, "lunar_year" to 5,
    )
    val FILTERS = mapOf("all" to 0, "days_off" to 1, "workdays" to 2)
    const val MAX_OCCURRENCES = 1000

    class RuleError(message: String) : Exception(message)

    /** encode_rule：把 UI 规则对象编码回 Task 列。 */
    fun encodeRule(rule: RepeatRule): Map<String, Any> {
        val mode = rule.mode
        if (mode !in MODES) throw RuleError("Unsupported repeat mode.")
        val span = rule.durationDays
        if (mode != "custom" && (rule.unit != null || rule.dates.isNotEmpty() || rule.dayFilter != null)) {
            throw RuleError("Unit, interval, dates and day_filter apply to custom repeat rules.")
        }
        val unit = rule.unit ?: "day"
        val interval = rule.interval
        val selector = rule.dayFilter ?: "all"
        val dates = rule.dates.toSet()
        if (unit !in UNITS || selector !in FILTERS || interval !in 1..366 || span !in 1..366) {
            throw RuleError("Invalid repeat unit, interval, day filter or duration.")
        }
        val unitId = UNITS.getValue(unit)
        if ((unitId != 0) != dates.isNotEmpty()) {
            throw RuleError("Selected dates are required for weekly, monthly and yearly custom rules.")
        }
        if (unit == "year" || unit == "lunar_year") {
            for (selected in dates) {
                if (!Regex("[0-9]{2}-[0-9]{2}").matches(selected)) {
                    throw RuleError("Yearly dates use MM-DD.")
                }
                val parts = selected.split("-").map { it.toInt() }
                if (unit == "year") {
                    try { LocalDate.of(2000, parts[0], parts[1]) } catch (e: Exception) {
                        throw RuleError("Invalid yearly date.")
                    }
                } else if (parts[0] !in 1..12 || parts[1] !in 1..30) {
                    throw RuleError("Invalid lunar month or day.")
                }
            }
        } else if (dates.isNotEmpty()) {
            val range = when (unit) {
                "week" -> 0..6
                "month" -> 1..32
                "lunar_month" -> 1..30
                else -> 0..0
            }
            if (dates.any { it.toIntOrNull() !in range }) {
                throw RuleError("Selected dates are out of range for this repeat unit.")
            }
        }
        return mapOf(
            "loopSet" to MODES.getValue(mode),
            "loopUnit" to unitId + FILTERS.getValue(selector) * 100,
            "loopBaseNumber" to interval,
            "loopDate" to dates.sorted().joinToString(","),
            "duration" to span,
        )
    }

    /** view_rule：从 Task 行解码规则。 */
    fun viewRule(row: Attrs): RepeatRule? {
        val mode = row.int("loopSet") ?: 0
        if (mode == 0) return null
        val modeName = MODES.entries.firstOrNull { it.value == mode }?.key ?: "unknown"
        val rule = RepeatRule(mode = modeName, durationDays = row.int("duration") ?: 1)
        if (modeName == "custom") {
            val raw = row.int("loopUnit") ?: 0
            val selector = raw / 100
            val unit = raw % 100
            return rule.copy(
                unit = UNITS.entries.firstOrNull { it.value == unit }?.key ?: "unknown",
                dayFilter = FILTERS.entries.firstOrNull { it.value == selector }?.key ?: "unknown",
                interval = row.int("loopBaseNumber") ?: 1,
                dates = (row.str("loopDate") ?: "").split(",").filter { it.isNotEmpty() },
            )
        }
        return rule
    }

    /** 重复实例的确定性 id2：2^51 + sha256(taskId:startDate) 前 8 字节 mod 2^51。 */
    fun occurrenceId(taskId: String, startDate: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$taskId:$startDate".toByteArray(Charsets.US_ASCII))
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (digest[i].toLong() and 0xFF)
        val modulus = 1L shl 51
        return ((modulus) + (value % modulus)).toString()
    }

    private fun months(start: LocalDate, end: LocalDate, interval: Int): Sequence<LocalDate> = sequence {
        val first = start.year * 12 + start.monthValue - 1
        val last = end.year * 12 + end.monthValue - 1
        var index = first
        while (index <= last) {
            yield(LocalDate.of(index / 12, index % 12 + 1, 1))
            index += interval
        }
    }

    private data class LunarMonth(val year: Int, val month: Int, val first: LocalDate, val days: Int)

    private val lunarCalendar: List<LunarMonth> by lazy {
        val out = mutableListOf<LunarMonth>()
        var first = LocalDate.of(1900, 1, 31)
        LUNAR_YEARS.forEachIndexed { index, yearBits ->
            val year = 1900 + index
            val leap = yearBits and 15
            for (month in 1..12) {
                var days = 29 + if (yearBits and (0x10000 shr month) != 0) 1 else 0
                out += LunarMonth(year, month, first, days)
                first = first.plusDays(days.toLong())
                if (month == leap) {
                    days = 29 + if (yearBits and 0x10000 != 0) 1 else 0
                    out += LunarMonth(year, month, first, days)
                    first = first.plusDays(days.toLong())
                }
            }
        }
        out
    }

    private fun lunarDates(
        start: LocalDate, end: LocalDate, unit: String, interval: Int, selected: Set<String>,
    ): Sequence<LocalDate> = sequence {
        val calendar = lunarCalendar
        val anchor = calendar.last { it.first <= start }
        val anchorIndex = anchor.year * 12 + anchor.month
        for ((year, month, first, days) in calendar) {
            if (first > end) break
            val offset = year * 12 + month - anchorIndex
            if (offset < 0) continue
            if (unit == "lunar_month" && offset % interval == 0) {
                for (day in selected.map { it.toInt() }) {
                    if (day <= days) yield(first.plusDays((day - 1).toLong()))
                }
            }
            if (unit == "lunar_year" && (year - anchor.year) % interval == 0) {
                for (day in 1..days) {
                    val key = "%02d-%02d".format(month, day)
                    if (key in selected) yield(first.plusDays((day - 1).toLong()))
                }
            }
        }
    }

    private fun generateDates(
        start: LocalDate, end: LocalDate, mode: String, unit: String?,
        interval: Int, selected: Set<String>, weekStart: Int,
    ): Sequence<LocalDate> = sequence {
        when {
            mode == "ebbinghaus" -> {
                val offsets = listOf(0, 1, 2, 4, 6, 14) +
                    generateSequence(29) { it + 15 }.takeWhile { it <= (end.toEpochDay() - start.toEpochDay()).toInt() }.toList()
                for (offset in offsets) {
                    if (offset <= end.toEpochDay() - start.toEpochDay()) yield(start.plusDays(offset.toLong()))
                }
            }
            mode == "weekly_period" || unit == "week" -> {
                // 周日编码 0，周一 1 … 周六 6（对齐 python 的 (weekday+1)%7）
                val sunday = start.dayOfWeek.value % 7
                val offset = if (mode == "weekly_period") {
                    ((sunday - weekStart) % 7 + 7) % 7
                } else {
                    sunday - weekStart
                }
                val first = start.minusDays(offset.toLong())
                var delta = 0L
                while (first.plusDays(delta) <= end) {
                    val week = first.plusDays(delta)
                    if (mode == "weekly_period") {
                        yield(week)
                    } else {
                        for (day in 0..6) {
                            if (((day + weekStart) % 7).toString() in selected) {
                                yield(week.plusDays(day.toLong()))
                            }
                        }
                    }
                    delta += 7L * interval
                }
            }
            mode == "monthly_period" || unit == "month" -> {
                for (first in months(start, end, interval)) {
                    if (mode == "monthly_period") {
                        yield(first)
                    } else {
                        for (day in selected.map { it.toInt() }) {
                            // 32 = 当月最后一天
                            val actual = if (day == 32) YearMonth.of(first.year, first.monthValue).lengthOfMonth() else day
                            yield(first.plusDays((actual - 1).toLong()))
                        }
                    }
                }
            }
            mode == "yearly_period" || unit == "year" -> {
                var year = start.year
                while (year <= end.year) {
                    if (mode == "yearly_period") {
                        yield(LocalDate.of(year, 1, 1))
                    } else {
                        for (value in selected) {
                            val (m, d) = value.split("-").map { it.toInt() }
                            if (d <= YearMonth.of(year, m).lengthOfMonth()) yield(LocalDate.of(year, m, d))
                        }
                    }
                    year += interval
                }
            }
            unit == "lunar_month" || unit == "lunar_year" -> {
                yieldAll(lunarDates(start, end, unit, interval, selected))
            }
            else -> {
                var offset = 0L
                while (start.plusDays(offset) <= end) {
                    yield(start.plusDays(offset))
                    offset += interval
                }
            }
        }
    }

    data class Window(
        val startTime: String,
        val endTime: String,
        val validStartTime: String,
        val validEndTime: String,
    )

    /**
     * recurrence_windows：按重复规则展开实例窗口。
     * from_date 只取 cutoff 及之后；period 模式允许实例早于 start。
     */
    fun recurrenceWindows(
        row: Attrs,
        weekStart: Int = 0,
        chinaMakeup: Boolean = true,
        fromDate: LocalDate? = null,
        allowEmpty: Boolean = false,
    ): List<Window> {
        val start = LocalDate.parse(row.str("startTime"))
        val end = LocalDate.parse(row.str("endTime"))
        if (start < LocalDate.of(1900, 1, 31) || end > LocalDate.of(2100, 12, 31) || start > end) {
            throw RuleError("Repeat dates must be between 1900-01-31 and 2100-12-31.")
        }
        val rule = viewRule(row) ?: throw RuleError("Not a repeating task.")
        val mode = rule.mode
        val unit = rule.unit
        val interval = rule.interval
        val selected = rule.dates.toSet()
        val selector = if (mode in setOf("workdays", "days_off")) mode else (rule.dayFilter ?: "all")
        if (chinaMakeup && selector != "all"
            && !(start >= LocalDate.of(2024, 1, 1) && end <= LocalDate.of(2026, 12, 31))) {
            throw RuleError("Bundled Chinese make-up day data covers 2024-2026. Use regular weekends for other dates.")
        }
        val period = mode.endsWith("_period")
        val dates = sortedSetOf<LocalDate>()
        for (day in generateDates(start, end, mode, unit, interval, selected, weekStart)) {
            if (day > end || (!period && day < start) || (fromDate != null && day < fromDate)) continue
            var holiday = day.dayOfWeek.value >= 6
            if (chinaMakeup && CHINA_MAKEUP.contains(day.year * 10000 + day.monthValue * 100 + day.dayOfMonth)) {
                holiday = !holiday
            }
            if ((selector == "days_off" && !holiday) || (selector == "workdays" && holiday)) continue
            dates += day
            if (dates.size > MAX_OCCURRENCES) {
                throw RuleError("A single operation can generate at most $MAX_OCCURRENCES occurrences. Shorten the date range.")
            }
        }
        if (dates.isEmpty() && !allowEmpty) {
            throw RuleError("The selected rule produces no occurrences in this date range.")
        }
        val validStart = row.str("validStartTime") ?: "00:00:00"
        val validEnd = row.str("validEndTime") ?: "23:59:59"
        return dates.map { day ->
            val last = when (mode) {
                "weekly_period" -> day.plusDays(6)
                "monthly_period" -> day.withDayOfMonth(YearMonth.of(day.year, day.monthValue).lengthOfMonth())
                "yearly_period" -> day.withMonth(12).withDayOfMonth(31)
                else -> day.plusDays((rule.durationDays - 1).toLong())
            }
            if (last > LocalDate.of(2100, 12, 31)) {
                throw RuleError("An occurrence would end beyond 2100-12-31.")
            }
            if (day == last && validStart > validEnd || day > last) {
                throw RuleError("At least one occurrence ends before it starts.")
            }
            Window(day.toString(), last.toString(), validStart, validEnd)
        }
    }

    // 与 chineseCalendar.ets 相同的农历年份位数据
    private val LUNAR_YEARS = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x05ac0, 0x0ab60, 0x096d5, 0x092e0,
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0,
        0x0a2e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4,
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0,
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160,
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252,
        0x0d520,
    )

    // 调休数据（holidayChecker.ets）：列入的日期节假日属性取反
    private val CHINA_MAKEUP: Set<Int> = setOf(
        20240101, 20240204, 20240212, 20240213, 20240214,
        20240215, 20240216, 20240218, 20240404, 20240405,
        20240407, 20240428, 20240501, 20240502, 20240503,
        20240511, 20240610, 20240914, 20240916, 20240917,
        20240929, 20241001, 20241002, 20241003, 20241004,
        20241007, 20241012, 20250101, 20250126, 20250128,
        20250129, 20250130, 20250131, 20250203, 20250204,
        20250208, 20250404, 20250427, 20250501, 20250502,
        20250505, 20250602, 20250928, 20251001, 20251002,
        20251003, 20251006, 20251007, 20251008, 20251011,
        20260101, 20260102, 20260104, 20260214, 20260216,
        20260217, 20260218, 20260219, 20260220, 20260223,
        20260228, 20260406, 20260501, 20260504, 20260505,
        20260509, 20260619, 20260920, 20260925, 20261001,
        20261002, 20261005, 20261006, 20261007, 20261010,
    )
}
