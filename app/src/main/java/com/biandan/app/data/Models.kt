package com.biandan.app.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 云记录：外层 id/version 属于传输层，业务主键在 attributes 里。
 */
data class CloudRecord(
    val id: String,
    val version: Int,
    val attributes: JsonObject,
)

typealias Attrs = JsonObject

fun Attrs.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.let { if (it.isString) it.content else it.content }

fun Attrs.int(key: String): Int? = (this[key] as? JsonPrimitive)?.longOrNull?.toInt()

fun Attrs.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

fun Attrs.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

fun Attrs.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

// ---------- 视图模型（对应 task_view / occurrence_view / focus_view） ----------

data class TaskView(
    val taskId: String,
    val title: String,
    val remark: String,
    val subtasks: List<String>,
    val targetCount: Int,
    val listId: String,
    val important: Boolean,
    val urgent: Boolean,
    val collected: Boolean,
    val repeatType: Int,
    val recurrence: RepeatRule?,
    val isHabit: Boolean,
    val undated: Boolean,
    val startDate: String?,
    val endDate: String?,
    val startTime: String?,
    val endTime: String?,
    val completed: Boolean,
    val timeReminder: TimeReminder?,
    val hasLocationReminder: Boolean,
    val location: LocationInfo?,
)

data class TimeReminder(val date: String?, val time: String?)

data class LocationInfo(val name: String, val latitude: Double?, val longitude: Double?)

data class RepeatRule(
    val mode: String,
    val durationDays: Int = 1,
    val unit: String? = null,
    val dayFilter: String? = null,
    val interval: Int = 1,
    val dates: List<String> = emptyList(),
)

data class OccurrenceView(
    val occurrenceId: String,
    val taskId: String,
    val startDate: String?,
    val endDate: String?,
    val startTime: String?,
    val endTime: String?,
    val status: String,          // pending | completed | abandoned
    val completedAt: String?,
    val completedCount: Int,
    val targetCount: Int,
    val completedSubtaskIndices: List<Int>,
    // 列表展示附加
    val title: String = "",
    val listId: String = "",
    val important: Boolean = false,
    val urgent: Boolean = false,
    val isHabit: Boolean = false,
    val subtaskTitles: List<String> = emptyList(),
    val hasTimeReminder: Boolean = false,
    val hasLocationReminder: Boolean = false,
)

data class ListView(
    val listId: String,
    val name: String,
    val color: Int,
    val sortIndex: Int,
    val pendingCount: Int = 0,
)

data class FocusView(
    val focusId: String,
    val date: String,
    val minutes: Double,
    val goodCount: Int,
    val badCount: Int,
    val title: String,
    val occurrenceId: String?,
    val startedAt: String,
)

fun Int.bitCount(): Int = Integer.bitCount(this)

/** 按写入类型放值：整数/长整型/浮点/布尔→数字，其余→字符串（对应 Python 写入约定）。 */
fun JsonObjectBuilder.putAny(key: String, value: Any?) {
    when (value) {
        null -> put(key, JsonPrimitive(""))
        is Int -> put(key, value)
        is Long -> put(key, value)
        is Double -> put(key, value)
        is Float -> put(key, value.toDouble())
        is Boolean -> put(key, if (value) 1 else 0)
        is JsonElement -> put(key, value)
        else -> put(key, value.toString())
    }
}
