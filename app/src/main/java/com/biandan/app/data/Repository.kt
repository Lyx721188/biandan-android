package com.biandan.app.data

import com.biandan.app.data.Recurrence.occurrenceId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ToolError(message: String, val details: Map<String, Any?> = emptyMap()) : Exception(message)

/**
 * 便单业务层，对应 biandan_tools.py 的 BiandanTools。
 * 读路径带本机缓存，写路径始终先读回云端最新版本再修改。
 */
class Repository(
    private val cloud: BiandanCloud,
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now() },
) {
    // ---------- 校验工具 ----------

    private fun calendarDate(value: String): String {
        try {
            if (LocalDate.parse(value).toString() == value) return value
        } catch (_: Exception) {}
        throw ToolError("Use a date in YYYY-MM-DD format.")
    }

    private fun businessId(value: String): String {
        if (!Regex("[1-9][0-9]{0,15}").matches(value) || value.toLong() > (1L shl 53) - 1) {
            throw ToolError("Use the positive numeric business ID returned by a Biandan tool.")
        }
        return value
    }

    private fun clockTime(value: String): String {
        if (!Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(value)) {
            throw ToolError("Use a local time in HH:MM format.")
        }
        return "$value:00"
    }

    private fun newBusinessId(): String = (System.nanoTime() / 1000).toString()

    // ---------- 视图解码 ----------

    fun taskView(record: CloudRecord): TaskView {
        val row = record.attributes
        val decoded = TaskContent.decode(row.str("taskContent") ?: "")
        val flags = row.int("isRemind") ?: 0
        val target = row.int("unitTimeNumber") ?: 1
        val hasLocation = row.str("location") != null && row.str("location") != ""
            || (row.double("latitude") != null && row.double("longitude") != null
                && row.double("latitude") != -1.0 && row.double("longitude") != -1.0)
            || flags and 2 != 0
        return TaskView(
            taskId = row["id1"].toString().trim('"'),
            title = row.str("taskTitle") ?: "",
            remark = decoded.remark,
            subtasks = decoded.subtasks,
            targetCount = if (target >= 100) (target / 100).bitCount() else target,
            listId = (row.int("id3") ?: 1).toString(),
            important = (row.int("isImportant") ?: 0) != 0,
            urgent = (row.int("isUrgency") ?: 0) != 0,
            collected = (row.int("isCollectible") ?: 0) != 0,
            repeatType = row.int("loopSet") ?: 0,
            recurrence = Recurrence.viewRule(row),
            isHabit = (row.int("isLoop") ?: 0) >= 2,
            undated = (row.int("haveNoTime") ?: 0) != 0,
            startDate = row.str("startTime"),
            endDate = row.str("endTime"),
            startTime = row.str("validStartTime"),
            endTime = row.str("validEndTime"),
            completed = (row.int("isFinish") ?: 0) != 0,
            timeReminder = if (flags and 1 != 0) TimeReminder(row.str("remindDate"), row.str("remindTime")) else null,
            hasLocationReminder = flags and 2 != 0,
            location = if (hasLocation) LocationInfo(
                row.str("location") ?: "", row.double("latitude"), row.double("longitude")
            ) else null,
        )
    }

    fun occurrenceView(record: CloudRecord, task: CloudRecord): OccurrenceView {
        val row = record.attributes
        val progress = row.int("completedCount") ?: 0
        val target = task.attributes.int("unitTimeNumber") ?: 1
        val tv = taskView(task)
        return OccurrenceView(
            occurrenceId = row["id2"].toString().trim('"'),
            taskId = row["id1"].toString().trim('"'),
            startDate = row.str("startTime"),
            endDate = row.str("endTime"),
            startTime = row.str("validStartTime"),
            endTime = row.str("validEndTime"),
            status = if ((row.int("isFinish") ?: 0) != 0) "completed" else if (progress < 0) "abandoned" else "pending",
            completedAt = if ((row.int("isFinish") ?: 0) != 0) row.str("recentFinishedTime") else null,
            completedCount = if (target >= 100) (maxOf(progress, 0) / 100).bitCount() else maxOf(progress, 0),
            targetCount = if (target >= 100) (target / 100).bitCount() else target,
            completedSubtaskIndices = if (target >= 100)
                (0 until 20).filter { (maxOf(progress, 0) / 100) and (1 shl it) != 0 } else emptyList(),
            title = tv.title,
            listId = tv.listId,
            important = tv.important,
            urgent = tv.urgent,
            isHabit = tv.isHabit,
            subtaskTitles = tv.subtasks,
            hasTimeReminder = tv.timeReminder != null,
            hasLocationReminder = tv.hasLocationReminder,
        )
    }

    fun focusView(record: CloudRecord): FocusView {
        val row = record.attributes
        val log = parseFocusLog(row.str("log"))
        val linked = log["eventId"]
        val id = row["time"].toString().trim('"')
        return FocusView(
            focusId = id,
            date = row.str("date") ?: "",
            minutes = row.double("minutes") ?: 0.0,
            goodCount = row.int("goodCount") ?: 0,
            badCount = row.int("badCount") ?: 0,
            title = log["eventTitle"] ?: "",
            occurrenceId = linked?.takeIf { it != "-1" },
            startedAt = try {
                LocalDateTime.ofEpochSecond(id.toLong() / 1000, 0, java.time.ZoneOffset.systemDefault().rules.getOffset(java.time.Instant.now())).toString()
            } catch (_: Exception) { "" },
        )
    }

    private fun parseFocusLog(value: String?): Map<String, String> {
        if (value.isNullOrEmpty()) return emptyMap()
        return runCatching {
            val obj = BiandanCloud.json.parseToJsonElement(value)
            if (obj is kotlinx.serialization.json.JsonObject) {
                obj.entries.associate { (k, v) -> k to v.toString().trim('"') }
            } else emptyMap()
        }.getOrDefault(emptyMap())
    }

    // ---------- 缓存与记录访问 ----------

    private val cacheLock = Mutex()
    private val cache = mutableMapOf<String, List<CloudRecord>>()

    private fun cachePut(kind: String, rows: List<CloudRecord>) {
        cache[kind] = rows
    }

    private fun cacheUpsert(kind: String, record: CloudRecord) {
        cache[kind]?.let { rows ->
            cache[kind] = rows.filter { it.id != record.id } + record
        }
    }

    private fun cacheRemove(kind: String, recordId: String) {
        cache[kind]?.let { rows -> cache[kind] = rows.filter { it.id != recordId } }
    }

    suspend fun invalidate(kind: String? = null) = cacheLock.withLock {
        if (kind == null) cache.clear() else cache.remove(kind)
    }

    suspend fun refreshAll() = cacheLock.withLock {
        cache.clear()
        for (kind in BiandanCloud.KINDS) cache[kind] = fetchAll(kind)
    }

    private suspend fun fetchAll(kind: String): List<CloudRecord> {
        val out = mutableListOf<CloudRecord>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        repeat(200) {
            val (records, next) = cloud.listPage(kind, cursor, 100)
            out += records
            if (next == null) return out
            if (next in seen) throw ToolError("Invalid or repeated cursor for $kind.")
            seen += next
            cursor = next
        }
        throw ToolError("Too many $kind pages; the operation was stopped before using partial data.")
    }

    /** records(kind)：默认读缓存（App 内手动刷新更新），force 强制走云端。 */
    private suspend fun records(kind: String, force: Boolean = false): List<CloudRecord> =
        cacheLock.withLock {
            if (force || kind !in cache) cache[kind] = fetchAll(kind)
            cache[kind]!!
        }

    private suspend fun find(kind: String, key: String, identifier: String, rows: List<CloudRecord>? = null, required: Boolean = true, force: Boolean = false): CloudRecord? {
        businessId(identifier)
        val all = rows ?: records(kind, force)
        val matches = all.filter { it.attributes[key].toString().trim('"') == identifier }
        if (matches.size > 1) throw ToolError("Multiple $kind records share ID $identifier.")
        if (matches.isNotEmpty()) return matches[0]
        if (required) throw ToolError("No $kind record found with ID $identifier.")
        return null
    }

    private suspend fun fresh(kind: String, record: CloudRecord): CloudRecord {
        val updated = cloud.get(kind, record.id)
        cacheLock.withLock { cacheUpsert(kind, updated) }
        return updated
    }

    private suspend fun patch(kind: String, record: CloudRecord, changes: Map<String, Any?>, reparent: Boolean = false): CloudRecord {
        val effective = changes.filter { (k, v) ->
            val current = record.attributes[k]
            current?.toString()?.trim('"') != v?.toString()
        }
        if (effective.isEmpty()) return record
        val attrs = buildJsonObject {
            for ((k, v) in effective) putAny(k, v)
        }
        cloud.update(kind, record.id, record.version, attrs, reparent = reparent)
        val updated = fresh(kind, record)
        for ((k, v) in effective) {
            val actual = updated.attributes[k]?.toString()?.trim('"')
            if (actual != v?.toString()) {
                throw ToolError("The requested $kind update was not confirmed by readback.")
            }
        }
        return updated
    }

    suspend fun checkConnection(): Boolean = runCatching { cloud.check(); true }.getOrDefault(false)

    fun localToday(): String = nowProvider().toLocalDate().toString()

    // ---------- 待办单 favorites ----------

    suspend fun listLists(): List<ListView> {
        val rows = records("favorites")
        val lists = rows.map { row ->
            val color = row.attributes.int("favoritesColor") ?: 0
            ListView(
                listId = row.attributes["id3"].toString().trim('"'),
                name = row.attributes.str("favoritesName") ?: "",
                color = color % 100,
                sortIndex = color / 100,
            )
        }.toMutableList()
        if (lists.none { it.listId == "1" }) {
            lists += ListView("1", "未分类", 0, 0)
        }
        return lists.sortedWith(compareBy({ it.sortIndex }, { it.listId.toLongOrNull() ?: 0 }))
    }

    private suspend fun validateList(listId: String): Int {
        if (listId != "1") find("favorites", "id3", listId)
        return listId.toInt()
    }

    suspend fun getList(listId: String, includePending: Boolean = false, limit: Int = 30, offset: Int = 0): ListView {
        businessId(listId)
        val item = listLists().firstOrNull { it.listId == listId }
            ?: throw ToolError("No list found with ID $listId.")
        if (includePending) {
            val pending = listOccurrences(
                startDate = "1900-01-31", endDate = "2100-12-31", listId = listId,
                status = "pending", includeUndated = true, limit = limit, offset = offset,
            )
            return item.copy(pendingCount = pending.total)
        }
        return item
    }

    suspend fun createList(name: String, color: Int = 0, sortIndex: Int? = null, operationId: String? = null): Pair<ListView, String> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw ToolError("A list name is required.")
        val identifier = if (operationId != null) businessId(operationId) else newBusinessId()
        if (identifier == "1") throw ToolError("List ID 1 is reserved.")
        val existing = find("favorites", "id3", identifier, required = false)
        val sort = sortIndex ?: (existing?.attributes?.int("favoritesColor")?.div(100)
            ?: ((listLists().maxOfOrNull { it.sortIndex } ?: 0) + 1))
        try {
            ensureRecord(
                "favorites", "id3", identifier,
                buildJsonObject {
                    put("id3", identifier.toInt())
                    put("favoritesName", trimmed)
                    put("favoritesColor", sort * 100 + color)
                },
                existing,
            )
        } catch (e: Exception) {
            if (e is ToolError || e is CloudError) {
                throw ToolError(e.message ?: "create failed", mapOf("operation_id" to identifier))
            }
            throw e
        }
        invalidate("favorites")
        return getList(identifier) to identifier
    }

    suspend fun updateList(listId: String, name: String? = null, color: Int? = null, sortIndex: Int? = null): ListView {
        if (listId == "1") throw ToolError("The default list cannot be edited.")
        if (name == null && color == null && sortIndex == null) throw ToolError("Provide a list field to change.")
        val row = fresh("favorites", find("favorites", "id3", listId)!!)
        val changes = mutableMapOf<String, Any?>()
        if (name != null) {
            if (name.trim().isEmpty()) throw ToolError("A list name is required.")
            changes["favoritesName"] = name.trim()
        }
        if (color != null || sortIndex != null) {
            val old = row.attributes.int("favoritesColor") ?: 0
            changes["favoritesColor"] = (sortIndex ?: old / 100) * 100 + (color ?: old % 100)
        }
        patch("favorites", row, changes)
        return getList(listId)
    }

    private suspend fun deleteRecord(kind: String, record: CloudRecord) {
        try {
            cloud.delete(kind, record.id, record.version)
        } catch (e: CloudError) {
            if (e.status != 404) throw e
        }
        cacheLock.withLock { cacheRemove(kind, record.id) }
    }

    data class DeleteListResult(
        val listId: String,
        val taskAction: String,
        val affectedTasks: List<Pair<String, String>>,
        val preview: Boolean = false,
        val deleted: Boolean = false,
    )

    suspend fun deleteList(listId: String, tasks: String = "move_to_default", preview: Boolean = false): DeleteListResult {
        if (listId == "1") throw ToolError("The default list cannot be deleted.")
        val folder = find("favorites", "id3", listId, required = false)
        val matches = records("Task").filter { it.attributes["id3"].toString().trim('"') == listId }
        val result = DeleteListResult(
            listId = listId,
            taskAction = tasks,
            affectedTasks = matches.map {
                it.attributes["id1"].toString().trim('"') to (it.attributes.str("taskTitle") ?: "")
            },
        )
        if (preview) return result.copy(preview = true)
        if (folder == null && matches.isNotEmpty()) {
            throw ToolError("The list no longer exists but tasks still reference it. Move the tasks before retrying.")
        }
        try {
            for (row in matches) {
                if (tasks == "delete_tasks") {
                    deleteTask(row.attributes["id1"].toString().trim('"'), scope = "series", expectedList = listId)
                } else {
                    patch("Task", row, mapOf("id3" to 1))
                }
            }
            if (folder != null) {
                if (records("Task", force = true).any { it.attributes["id3"].toString().trim('"') == listId }) {
                    throw ToolError("Some tasks still reference this list.")
                }
                deleteRecord("favorites", folder)
            }
        } catch (e: Exception) {
            when (e) {
                is CloudError, is ToolError ->
                    throw ToolError(e.message ?: "delete failed", mapOf("list_id" to listId, "may_have_written" to true))
                else -> throw e
            }
        }
        return result.copy(deleted = true)
    }

    // ---------- 任务删除 ----------

    data class DeleteTaskResult(
        val taskId: String,
        val title: String?,
        val scope: String,
        val occurrenceIds: List<String>,
        val deletesTaskDefinition: Boolean,
        val preview: Boolean = false,
        val deleted: Boolean = false,
    )

    suspend fun deleteTask(
        taskId: String,
        scope: String? = null,
        occurrenceId: String? = null,
        fromDate: String? = null,
        preview: Boolean = false,
        expectedList: String? = null,
    ): DeleteTaskResult {
        val task = find("Task", "id1", taskId, required = false)
        val instances = taskOccurrences(taskId)
        val repeating = task != null && ((task.attributes.int("isLoop") ?: 0) != 0 || (task.attributes.int("loopSet") ?: 0) != 0)
        if (expectedList != null && (task == null || task.attributes["id3"].toString().trim('"') != expectedList)) {
            throw ToolError("This task no longer belongs to the list being deleted.")
        }
        if (repeating && scope == null) {
            throw ToolError("Choose a repeat deletion scope: occurrence, future_pending or series.")
        }
        if ((occurrenceId != null) != (scope == "occurrence") || (fromDate != null && scope != "future_pending")) {
            throw ToolError("occurrence requires occurrence_id; from_date only applies to future_pending.")
        }
        var deleteParent = scope in setOf(null, "series") || (!repeating && scope == "occurrence")
        var chosen = instances
        if (scope == "occurrence") {
            businessId(occurrenceId!!)
            chosen = instances.filter { it.attributes["id2"].toString().trim('"') == occurrenceId }
            if (chosen.isEmpty() && task != null && !repeating && instances.isNotEmpty()) {
                throw ToolError("The specified occurrence does not belong to this task.")
            }
        }
        if (scope == "future_pending") {
            val start = if (fromDate != null) {
                calendarDate(fromDate) to "00:00:00"
            } else {
                val now = nowProvider()
                now.toLocalDate().toString() to now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            }
            chosen = instances.filter { row ->
                (row.attributes.int("isFinish") ?: 0) == 0 && run {
                    val d = row.attributes.str("startTime") ?: ""
                    val t = row.attributes.str("validStartTime") ?: ""
                    d > start.first || (d == start.first && t >= start.second)
                }
            }
            deleteParent = chosen.isNotEmpty() && chosen.size == instances.size
        }
        if (deleteParent && scope == "occurrence" && chosen.size != instances.size) {
            throw ToolError("Additional occurrences exist. Use series deletion to remove the task.")
        }
        val result = DeleteTaskResult(
            taskId = taskId,
            title = task?.attributes?.str("taskTitle"),
            scope = scope ?: "series",
            occurrenceIds = chosen.map { it.attributes["id2"].toString().trim('"') },
            deletesTaskDefinition = task != null && deleteParent,
        )
        if (preview) return result.copy(preview = true)
        try {
            for (row in chosen) deleteRecord("showTask", row)
            if (task != null) {
                val remaining = taskOccurrences(taskId, force = true)
                if (deleteParent) {
                    if (remaining.isNotEmpty()) throw ToolError("Other occurrences appeared before deletion finished.")
                    deleteRecord("Task", task)
                } else if (remaining.isNotEmpty()) {
                    reconcileTask(task, remaining)
                }
            }
        } catch (e: Exception) {
            when (e) {
                is CloudError, is ToolError ->
                    throw ToolError(e.message ?: "delete failed", mapOf("task_id" to taskId, "may_have_written" to true))
                else -> throw e
            }
        }
        return result.copy(deleted = true)
    }

    // ---------- 任务查询 ----------

    data class Page<T>(val items: List<T>, val total: Int, val nextOffset: Int?)

    private fun <T> pageResult(items: List<T>, limit: Int, offset: Int): Page<T> {
        if (limit !in 1..100 || offset < 0) throw ToolError("limit must be 1-100 and offset cannot be negative.")
        val end = offset + limit
        return Page(items.subList(offset, minOf(end, items.size)), items.size, if (end < items.size) end else null)
    }

    suspend fun searchTasks(
        query: String = "", listId: String? = null, important: Boolean? = null,
        urgent: Boolean? = null, limit: Int = 30, offset: Int = 0,
        habit: Boolean? = null, repeating: Boolean? = null,
        completed: Boolean? = null, collected: Boolean? = null,
    ): Page<TaskView> {
        listId?.let { businessId(it) }
        val needle = query.trim().lowercase()
        val items = mutableListOf<TaskView>()
        for (record in records("Task")) {
            val item = taskView(record)
            if (listId != null && item.listId != listId) continue
            if (important != null && item.important != important) continue
            if (urgent != null && item.urgent != urgent) continue
            if (habit != null && item.isHabit != habit) continue
            if (repeating != null && (item.repeatType != 0) != repeating) continue
            if (completed != null && item.completed != completed) continue
            if (collected != null && item.collected != collected) continue
            val searchable = listOf(item.title, item.remark) + item.subtasks
            if (needle.isEmpty() || searchable.any { it.lowercase().contains(needle) }) items += item
        }
        items.sortWith(compareBy({ it.completed }, { -(it.taskId.toLongOrNull() ?: 0) }))
        return pageResult(items, limit, offset)
    }

    /** 按实例业务 id 取实例视图（详情页按实例进入时用）。 */
    suspend fun getOccurrenceView(occurrenceId: String, today: LocalDate = LocalDate.now()): OccurrenceView {
        val rows = records("showTask")
        val rec = find("showTask", "id2", occurrenceId, rows)
            ?: throw ToolError("找不到记录：$occurrenceId")
        val parent = find("Task", "id1", rec.attributes["id1"].toString().trim('"'))!!
        return occurrenceView(rec, parent)
    }

    suspend fun getTask(taskId: String): TaskView =
        taskView(fresh("Task", find("Task", "id1", taskId)!!))

    data class OccurrencePage(
        val items: List<OccurrenceView>,
        val total: Int,
        val nextOffset: Int?,
        val summary: Map<String, Int>? = null,
    )

    suspend fun listOccurrences(
        startDate: String? = null, endDate: String? = null, status: String = "pending",
        taskId: String? = null, listId: String? = null, includeUndated: Boolean = false,
        important: Boolean? = null, urgent: Boolean? = null, limit: Int = 30, offset: Int = 0,
        query: String = "", habit: Boolean? = null, repeating: Boolean? = null,
        collected: Boolean? = null, timeQuery: String? = null, includeSummary: Boolean = false,
    ): OccurrencePage {
        if (status !in setOf("pending", "completed", "abandoned", "all")) {
            throw ToolError("Unsupported occurrence status.")
        }
        val current = nowProvider()
        var start = startDate
        var end = endDate
        var timeWindow: Pair<LocalDateTime, LocalDateTime>? = null
        if (timeQuery != null) {
            if (start != null || end != null) throw ToolError("Use either time_query or explicit dates.")
            when (timeQuery) {
                "today" -> { start = current.toLocalDate().toString(); end = start }
                "tomorrow" -> { start = current.toLocalDate().plusDays(1).toString(); end = start }
                "last7day" -> { start = current.toLocalDate().minusDays(6).toString(); end = current.toLocalDate().toString() }
                "next7day" -> { start = current.toLocalDate().toString(); end = current.toLocalDate().plusDays(6).toString() }
                "last24hour" -> {
                    timeWindow = current.minusDays(1) to current
                    start = timeWindow.first.toLocalDate().toString(); end = timeWindow.second.toLocalDate().toString()
                }
                "next24hour" -> {
                    timeWindow = current to current.plusDays(1)
                    start = timeWindow.first.toLocalDate().toString(); end = timeWindow.second.toLocalDate().toString()
                }
            }
        }
        val from = if (start != null) calendarDate(start) else current.toLocalDate().toString()
        val to = if (end != null) calendarDate(end) else from
        if (to < from) throw ToolError("end_date cannot be earlier than start_date.")
        taskId?.let { businessId(it) }
        listId?.let { businessId(it) }

        val tasks = mutableMapOf<String, Pair<CloudRecord, TaskView>>()
        for (task in records("Task")) {
            val item = taskView(task)
            if (tasks.putIfAbsent(item.taskId, task to item) != null) {
                throw ToolError("Multiple tasks share ID ${item.taskId}.")
            }
        }
        val needle = query.trim().lowercase()
        val items = mutableListOf<OccurrenceView>()
        for (occurrence in records("showTask")) {
            val identifier = occurrence.attributes["id1"].toString().trim('"')
            val (task, summary) = tasks[identifier] ?: continue
            if (taskId != null && identifier != taskId) continue
            if (listId != null && summary.listId != listId) continue
            if (important != null && summary.important != important) continue
            if (urgent != null && summary.urgent != urgent) continue
            if (habit != null && summary.isHabit != habit) continue
            if (repeating != null && (summary.repeatType != 0) != repeating) continue
            if (collected != null && summary.collected != collected) continue
            if (needle.isNotEmpty() && !listOf(summary.title, summary.remark).plus(summary.subtasks)
                    .any { it.lowercase().contains(needle) }) continue
            val item = occurrenceView(occurrence, task)
            if (status != "all" && item.status != status) continue
            var matches = when {
                status == "completed" -> (item.completedAt ?: "").take(10).let { it >= from && it <= to }
                summary.undated -> includeUndated
                else -> (item.startDate ?: "") <= to && (item.endDate ?: "") >= from
            }
            if (matches && timeWindow != null && (status == "completed" || !summary.undated)) {
                val first: String
                val last: String
                if (status == "completed") {
                    first = item.completedAt ?: ""; last = first
                } else {
                    first = "${item.startDate} ${item.startTime ?: "00:00:00"}"
                    last = "${item.endDate} ${item.endTime ?: "23:59:59"}"
                }
                val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                matches = first <= timeWindow.second.format(fmt) && last >= timeWindow.first.format(fmt)
            }
            if (matches) items += item
        }
        val sortField: (OccurrenceView) -> String = if (status == "completed")
            ({ it.completedAt ?: "" }) else ({ it.startDate ?: "" })
        items.sortWith(compareBy({ sortField(it) }, { it.startTime ?: "" }, { it.occurrenceId }))
        val page = pageResult(items, limit, offset)
        val summaryMap = if (includeSummary) mapOf(
            "pending" to items.count { it.status == "pending" },
            "completed" to items.count { it.status == "completed" },
            "abandoned" to items.count { it.status == "abandoned" },
        ) else null
        return OccurrencePage(page.items, page.total, page.nextOffset, summaryMap)
    }

    // ---------- 任务写入 ----------

    private fun dateWindow(startDate: String, endDate: String?, startTime: String?, endTime: String?): Map<String, Any?> {
        val start = calendarDate(startDate)
        val end = if (endDate != null) calendarDate(endDate) else start
        val first = if (startTime != null) clockTime(startTime) else "00:00:00"
        val last = if (endTime != null) clockTime(endTime) else "23:59:00"
        if (start > end || (start == end && first > last)) {
            throw ToolError("The end of the task cannot be earlier than its start.")
        }
        return mapOf("startTime" to start, "endTime" to end, "validStartTime" to first, "validEndTime" to last)
    }

    data class CreateTaskResult(
        val taskId: String,
        val occurrenceId: String,
        val operationId: String,
        val occurrencesCreated: Int,
    )

    suspend fun createTask(
        title: String,
        startDate: String? = null,
        endDate: String? = null,
        startTime: String? = null,
        endTime: String? = null,
        listId: String = "1",
        remark: String = "",
        subtasks: List<String>? = null,
        important: Boolean = false,
        urgent: Boolean = false,
        reminderDate: String? = null,
        reminderTime: String? = null,
        operationId: String? = null,
        repeatRule: RepeatRule? = null,
        habit: Boolean = false,
        targetCount: Int? = null,
        location: Map<String, Any?>? = null,
        weekStart: Int = 0,
        chinaMakeup: Boolean = true,
    ): CreateTaskResult {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) throw ToolError("A task title is required.")
        val subs = subtasks ?: emptyList()
        if (subs.size > 20 || subs.any { it.trim().isEmpty() }) {
            throw ToolError("Provide no more than 20 non-empty subtask titles.")
        }
        val undated = startDate == null
        if (undated && listOf(endDate, startTime, endTime, reminderTime).any { it != null } || (undated && repeatRule != null)) {
            throw ToolError("Provide start_date for a scheduled task or a time reminder.")
        }
        if ((repeatRule != null && endDate == null) || (habit && repeatRule == null)) {
            throw ToolError("Repeating tasks require end_date; habits require a repeat rule.")
        }
        val window = dateWindow(startDate ?: "1970-01-01", if (undated) "2100-12-31" else endDate, startTime, endTime)
        if ((reminderDate == null) != (reminderTime == null)) {
            throw ToolError("Provide both reminder_date and reminder_time, or neither.")
        }
        val taskId = if (operationId != null) businessId(operationId) else newBusinessId()
        if (subs.isNotEmpty() && targetCount != null) {
            throw ToolError("Choose either subtasks or a numeric target.")
        }
        val taskAttrs = buildJsonObject {
            put("id1", taskId)
            put("taskTitle", trimmedTitle)
            put("taskContent", TaskContent.encode(remark, subs))
            put("id3", validateList(listId))
            put("isCollectible", 0)
            put("isImportant", if (important) 1 else 0)
            put("isUrgency", if (urgent) 1 else 0)
            put("isLoop", 0)
            put("haveNoTime", if (undated) 1 else 0)
            put("unitTimeNumber", if (subs.isNotEmpty()) ((1 shl subs.size) - 1) * 100 else (targetCount ?: 1))
            put("loopSet", 0)
            put("loopBaseNumber", 1)
            put("loopUnit", 0)
            put("loopDate", "")
            put("duration", 0)
            put("isRemind", if (reminderTime != null) 1 else 0)
            put("remindDate", if (reminderDate != null) calendarDate(reminderDate) else window["startTime"] as String)
            put("remindTime", if (reminderTime != null) clockTime(reminderTime) else "00:00:00")
            put("isFinish", 0)
            put("latitude", -1)
            put("longitude", -1)
            put("location", "")
            // 日期窗口
            put("startTime", window["startTime"] as String)
            put("endTime", window["endTime"] as String)
            put("validStartTime", window["validStartTime"] as String)
            put("validEndTime", window["validEndTime"] as String)
        }
        var taskJson = taskAttrs
        if (location != null) {
            taskJson = buildJsonObject {
                for ((k, v) in taskAttrs) put(k, v)
                for ((k, v) in locationChanges(taskAttrs,
                    name = location["name"] as String?,
                    latitude = (location["latitude"] as? Number)?.toDouble(),
                    longitude = (location["longitude"] as? Number)?.toDouble(),
                    remind = location["remind"] as? Boolean,
                )) putAny(k, v)
            }
        }
        var windows = listOf(
            Recurrence.Window(
                window["startTime"] as String, window["endTime"] as String,
                window["validStartTime"] as String, window["validEndTime"] as String,
            )
        )
        if (repeatRule != null) {
            val encoded = Recurrence.encodeRule(repeatRule)
            taskJson = buildJsonObject {
                for ((k, v) in taskJson) put(k, v)
                for ((k, v) in encoded) putAny(k, v)
                put("isLoop", if (habit) 2 else 1)
            }
            try {
                windows = Recurrence.recurrenceWindows(taskJson, weekStart = weekStart, chinaMakeup = chinaMakeup)
            } catch (e: Recurrence.RuleError) {
                throw ToolError(e.message ?: "rule error")
            }
        }
        val occurrences = windows.map { w ->
            newOccurrence(taskId, w, repeating = repeatRule != null)
        }
        var stage = "task"
        try {
            val stored = find("Task", "id1", taskId, required = false)
            val indexed = occurrenceIndex(occurrences.map { it["id2"].toString().trim('"') }.toSet())
            if (indexed.isNotEmpty() && stored == null) {
                throw ToolError("A generated occurrence ID is already in use.")
            }
            if (indexed.values.any { it.attributes["id1"].toString().trim('"') != taskId }) {
                throw ToolError("A generated occurrence ID belongs to a different task.")
            }
            ensureRecord("Task", "id1", taskId, taskJson, stored)
            stage = "occurrence"
            for (entry in occurrences) {
                ensureRecord("showTask", "id2", entry["id2"].toString().trim('"'), entry, indexed[entry["id2"].toString().trim('"')])
            }
        } catch (e: Exception) {
            when (e) {
                is ToolError, is CloudError -> throw ToolError(
                    e.message ?: "create failed",
                    mapOf("operation_id" to taskId, "stage" to stage, "may_have_written" to true),
                )
                else -> throw e
            }
        }
        invalidate()
        return CreateTaskResult(taskId, occurrences[0]["id2"].toString().trim('"'), taskId, occurrences.size)
    }

    private fun newOccurrence(taskId: String, window: Recurrence.Window, repeating: Boolean = true): Attrs =
        buildJsonObject {
            put("startTime", window.startTime)
            put("endTime", window.endTime)
            put("validStartTime", window.validStartTime)
            put("validEndTime", window.validEndTime)
            put("id1", taskId)
            put("id2", if (repeating) occurrenceId(taskId, window.startTime) else taskId)
            put("completedCount", 0)
            put("recentFinishedTime", "")
            put("isFinish", 0)
        }

    private suspend fun occurrenceIndex(identifiers: Set<String>): Map<String, CloudRecord> {
        val matches = mutableMapOf<String, CloudRecord>()
        for (row in records("showTask")) {
            val identifier = row.attributes["id2"].toString().trim('"')
            if (identifier in identifiers) {
                if (identifier in matches) throw ToolError("Multiple occurrences share ID $identifier.")
                matches[identifier] = row
            }
        }
        return matches
    }

    private suspend fun ensureRecord(kind: String, key: String, identifier: String, expected: Attrs, existing: CloudRecord?) {
        var stored = existing
        if (stored == null) {
            val created = cloud.create(kind, expected)
            val saved = created["saveResults"] as? kotlinx.serialization.json.JsonArray
            if (saved != null && saved.size == 1) {
                val receipt = cloud.normalizeRecord(saved[0] as JsonObject)
                if (receipt.id.isNotEmpty()) stored = receipt
            }
            if (stored == null) stored = find(kind, key, identifier, force = true)
            if (stored == null) {
                // find 用缓存，创建后强制刷新一次再查
                invalidate(kind)
                stored = find(kind, key, identifier, required = false)
            }
        }
        if (stored == null) throw ToolError("Created $kind record not found on readback.")
        val actual = fresh(kind, stored).attributes
        for ((k, v) in expected) {
            val matches = if (k in setOf("id1", "id2", "id3", "time")) {
                actual[k]?.toString()?.trim('"') == v.toString()
            } else actual[k]?.toString()?.trim('"') == v.toString()
            if (!matches) throw ToolError("Stored $kind does not match the requested creation fields.")
        }
        cacheLock.withLock { cacheUpsert(kind, stored) }
    }

    // ---------- 地点与提醒 ----------

    private fun locationChanges(
        row: Attrs,
        name: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        remind: Boolean? = null,
        clear: Boolean = false,
    ): Map<String, Any?> {
        val flags = row.int("isRemind") ?: 0
        if (clear) {
            if (listOf(name, latitude, longitude, remind).any { it != null }) {
                throw ToolError("clear cannot be combined with location or reminder values.")
            }
            return mapOf("location" to "", "latitude" to -1, "longitude" to -1, "isRemind" to (flags and 2.inv()))
        }
        if (listOf(name, latitude, longitude, remind).all { it == null }) {
            throw ToolError("Provide a location, coordinates or reminder setting.")
        }
        if ((latitude == null) != (longitude == null)) {
            throw ToolError("Provide latitude and longitude together.")
        }
        val changes = mutableMapOf<String, Any?>()
        if (name != null) changes["location"] = name.trim()
        if (latitude != null) {
            if (latitude !in -90.0..90.0 || longitude!! !in -180.0..180.0) {
                throw ToolError("Coordinates are outside the valid range.")
            }
            changes["latitude"] = latitude
            changes["longitude"] = longitude
        }
        if (remind != null) {
            val lat = (changes["latitude"] as? Double) ?: row.double("latitude")
            val lon = (changes["longitude"] as? Double) ?: row.double("longitude")
            if (remind && (lat == null || lon == null || (lat == -1.0 && lon == -1.0))) {
                throw ToolError("A location reminder needs an actual map coordinate.")
            }
            changes["isRemind"] = if (remind) flags or 2 else flags and 2.inv()
        } else if (latitude == -1.0 && longitude == -1.0) {
            changes["isRemind"] = flags and 2.inv()
        }
        return changes
    }

    suspend fun setLocation(
        taskId: String, name: String? = null, latitude: Double? = null,
        longitude: Double? = null, remind: Boolean? = null, clear: Boolean = false,
    ): TaskView {
        val current = fresh("Task", find("Task", "id1", taskId)!!)
        val changes = locationChanges(current.attributes, name, latitude, longitude, remind, clear)
        return taskView(patch("Task", current, changes))
    }

    suspend fun setTimeReminder(
        taskId: String, reminderDate: String? = null, reminderTime: String? = null, enabled: Boolean = true,
    ): TaskView {
        val task = fresh("Task", find("Task", "id1", taskId)!!)
        val flags = task.attributes.int("isRemind") ?: 0
        val changes = if (enabled) {
            if (reminderDate == null || reminderTime == null) {
                throw ToolError("Provide both reminder_date and reminder_time to enable a reminder.")
            }
            mapOf(
                "remindDate" to calendarDate(reminderDate),
                "remindTime" to clockTime(reminderTime),
                "isRemind" to (flags or 1),
            )
        } else {
            if (reminderDate != null || reminderTime != null) {
                throw ToolError("Omit date and time when disabling a reminder.")
            }
            mapOf("isRemind" to (flags and 1.inv()))
        }
        return taskView(patch("Task", task, changes))
    }

    suspend fun updateTask(
        taskId: String, title: String? = null, remark: String? = null,
        important: Boolean? = null, urgent: Boolean? = null, collected: Boolean? = null,
    ): TaskView {
        val current = fresh("Task", find("Task", "id1", taskId)!!)
        val changes = mutableMapOf<String, Any?>()
        if (title != null) {
            if (title.trim().isEmpty()) throw ToolError("A task title is required.")
            changes["taskTitle"] = title.trim()
        }
        if (remark != null) {
            val decoded = TaskContent.decode(current.attributes.str("taskContent") ?: "")
            changes["taskContent"] = TaskContent.encode(remark, decoded.subtasks, decoded.attachments)
        }
        important?.let { changes["isImportant"] = it }
        urgent?.let { changes["isUrgency"] = it }
        collected?.let { changes["isCollectible"] = it }
        if (changes.isEmpty()) throw ToolError("Provide at least one field to change.")
        return taskView(patch("Task", current, changes))
    }

    suspend fun editTaskGoal(taskId: String, subtasks: List<String>? = null, targetCount: Int? = null): TaskView {
        if ((subtasks == null) == (targetCount == null)) {
            throw ToolError("Choose either subtasks or target_count.")
        }
        val task = fresh("Task", find("Task", "id1", taskId)!!)
        val row = task.attributes
        val decoded = TaskContent.decode(row.str("taskContent") ?: "")
        val previousTarget = row.int("unitTimeNumber") ?: 1
        val nextTitles = subtasks?.map { it.trim() } ?: emptyList()
        if (nextTitles.size > 20 || nextTitles.any { it.isEmpty() }) {
            throw ToolError("Provide no more than 20 non-empty subtask titles.")
        }
        val target = if (nextTitles.isNotEmpty()) ((1 shl nextTitles.size) - 1) * 100 else (targetCount ?: 1)
        val instances = taskOccurrences(taskId)
        val hasProgress = instances.any {
            (it.attributes.int("completedCount") ?: 0) > 0 || (it.attributes.int("isFinish") ?: 0) != 0
        }
        if (hasProgress && (previousTarget >= 100) != (target >= 100)) {
            throw ToolError("Changing between count and checklist would reinterpret saved progress.")
        }
        if (hasProgress && nextTitles.isNotEmpty()) {
            nextTitles.forEachIndexed { index, title ->
                if (title in decoded.subtasks && (index >= decoded.subtasks.size || decoded.subtasks[index] != title)) {
                    throw ToolError("Do not reorder subtasks with existing history; edit titles in place.")
                }
            }
        }
        try {
            for (item in instances) {
                val data = item.attributes
                var progress = data.int("completedCount") ?: 0
                if ((data.int("isFinish") ?: 0) != 0 || progress < 0) continue
                progress = if (target >= 100) ((progress / 100) and (target / 100)) * 100
                else minOf(progress, target)
                val flags = mutableMapOf<String, Any?>(
                    "completedCount" to progress,
                    "isFinish" to if (progress >= target) 1 else 0,
                )
                if (progress >= target) {
                    flags["recentFinishedTime"] = nowProvider().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:00"))
                }
                patch("showTask", item, flags)
            }
            val updated = patch("Task", task, mapOf(
                "taskContent" to TaskContent.encode(decoded.remark, nextTitles, decoded.attachments),
                "unitTimeNumber" to target,
            ))
            reconcileTask(updated, taskOccurrences(taskId, force = true))
            return taskView(updated)
        } catch (e: Exception) {
            when (e) {
                is CloudError, is ToolError ->
                    throw ToolError(e.message ?: "goal update failed", mapOf("task_id" to taskId, "may_have_written" to true))
                else -> throw e
            }
        }
    }

    // ---------- 专注记录 ----------

    data class FocusPage(
        val items: List<FocusView>,
        val total: Int,
        val nextOffset: Int?,
        val summary: Map<String, Number>? = null,
    )

    suspend fun listFocus(
        startDate: String? = null, endDate: String? = null, occurrenceId: String? = null,
        limit: Int = 30, offset: Int = 0, includeSummary: Boolean = false,
    ): FocusPage {
        occurrenceId?.let { businessId(it) }
        val start = startDate?.let { calendarDate(it) }
        val end = endDate?.let { calendarDate(it) }
        if (start != null && end != null && end < start) throw ToolError("end_date cannot precede start_date.")
        val results = records("tomato").map { focusView(it) }
            .filter { item ->
                (occurrenceId == null || item.occurrenceId == occurrenceId) &&
                    (start == null || item.date >= start) &&
                    (end == null || item.date <= end)
            }
            .sortedByDescending { it.focusId.toLongOrNull() ?: 0L }
        val page = pageResult(results, limit, offset)
        val summary = if (includeSummary) mapOf(
            "minutes" to results.sumOf { it.minutes },
            "good_count" to results.sumOf { it.goodCount },
            "bad_count" to results.sumOf { it.badCount },
        ) else null
        return FocusPage(page.items, page.total, page.nextOffset, summary)
    }

    suspend fun getFocus(focusId: String): FocusView =
        focusView(fresh("tomato", find("tomato", "time", focusId)!!))

    suspend fun createFocus(
        startedAt: String, minutes: Double, title: String = "",
        goodCount: Int = 0, badCount: Int = 0, occurrenceId: String? = null,
    ): FocusView {
        if (!Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?").matches(startedAt)) {
            throw ToolError("Use a local start time in YYYY-MM-DDTHH:MM format.")
        }
        val started = try { LocalDateTime.parse(startedAt) } catch (e: Exception) {
            throw ToolError("Invalid local focus start time.")
        }
        val identifier = businessId(
            (started.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()).toString()
        )
        if (!java.lang.Double.isFinite(minutes) || minutes < 1 || minutes > 1440) {
            throw ToolError("Focus duration must be between 1 and 1440 minutes.")
        }
        val mins = kotlin.math.floor(minutes).toInt()
        val logData = mutableMapOf<String, Any>(
            "eventId" to -1, "eventType" to -1, "eventTitle" to title.trim(),
        )
        if (occurrenceId != null) {
            val linked = getOccurrence(occurrenceId).first
            logData["eventId"] = occurrenceId.toLong()
            logData["eventType"] = linked.listId.toLong()
            logData["eventTitle"] = title.trim().ifEmpty { linked.title }
        }
        val ended = started.plusMinutes(mins.toLong())
        logData["endTime"] = ended.toString()
        val logJson = buildJsonObject {
            for ((k, v) in logData) putAny(k, v)
        }.toString()
        ensureRecord("tomato", "time", identifier, buildJsonObject {
            put("time", identifier.toLong())
            put("date", started.toLocalDate().toString())
            put("minutes", mins)
            put("goodCount", goodCount)
            put("badCount", badCount)
            put("log", logJson)
        }, find("tomato", "time", identifier, required = false))
        invalidate("tomato")
        return getFocus(identifier)
    }

    suspend fun updateFocus(
        focusId: String, title: String? = null, goodCount: Int? = null, badCount: Int? = null,
    ): FocusView {
        if (title == null && goodCount == null && badCount == null) {
            throw ToolError("Provide a field to change.")
        }
        val record = fresh("tomato", find("tomato", "time", focusId)!!)
        val changes = mutableMapOf<String, Any?>()
        goodCount?.let { changes["goodCount"] = it }
        badCount?.let { changes["badCount"] = it }
        if (title != null) {
            val original = record.attributes.str("log")
            val data = parseFocusLog(original).toMutableMap()
            if (data.isEmpty()) {
                data["eventId"] = "-1"; data["eventType"] = "-1"; data["endTime"] = original ?: ""
            }
            data["eventTitle"] = title.trim()
            changes["log"] = buildJsonObject {
                for ((k, v) in data) {
                    v.toLongOrNull()?.let { put(k, it) } ?: put(k, v)
                }
            }.toString()
        }
        return focusView(patch("tomato", record, changes))
    }

    suspend fun deleteFocus(focusId: String) {
        find("tomato", "time", focusId, required = false)?.let { deleteRecord("tomato", it) }
    }

    // ---------- 移动 / 改期 / 循环 ----------

    suspend fun moveTask(taskId: String, listId: String): TaskView {
        val destination = validateList(listId)
        val task = fresh("Task", find("Task", "id1", taskId)!!)
        return taskView(patch("Task", task, mapOf("id3" to destination)))
    }

    data class RecurrenceUpdateResult(
        val taskId: String,
        val fromDate: String,
        val created: Int,
        val rescheduled: Int,
        val removed: Int,
        val protectedDates: Int,
        val preview: Boolean = false,
        val task: TaskView? = null,
    )

    suspend fun updateRecurrence(
        taskId: String, repeatRule: RepeatRule, fromDate: String? = null,
        startDate: String? = null, endDate: String? = null,
        startTime: String? = null, endTime: String? = null,
        habit: Boolean? = null, weekStart: Int = 0, chinaMakeup: Boolean = true,
        preview: Boolean = false,
    ): RecurrenceUpdateResult {
        var task = fresh("Task", find("Task", "id1", taskId)!!)
        val row = task.attributes
        val cutoff = fromDate?.let { calendarDate(it) } ?: nowProvider().toLocalDate().toString()
        if ((row.int("haveNoTime") ?: 0) != 0 && (startDate == null || endDate == null)) {
            throw ToolError("Provide start_date and end_date when converting an undated task.")
        }
        val start = startDate ?: row.str("startTime")!!
        val end = endDate ?: row.str("endTime")!!
        val window = dateWindow(
            start, end,
            startTime ?: (row.str("validStartTime") ?: "00:00:00").take(5),
            endTime ?: (row.str("validEndTime") ?: "23:59:00").take(5),
        )
        val isHabit = habit ?: ((row.int("isLoop") ?: 0) >= 2)
        val changes: Map<String, Any?>
        val target: Map<String, Recurrence.Window>
        try {
            val encoded = Recurrence.encodeRule(repeatRule)
            changes = window + encoded + mapOf("isLoop" to if (isHabit) 2 else 1, "haveNoTime" to 0)
            val probe = buildJsonObject {
                for ((k, v) in row) put(k, v)
                for ((k, v) in changes) {
                    if (v != null) putAny(k, v)
                }
            }
            target = Recurrence.recurrenceWindows(
                probe, fromDate = LocalDate.parse(cutoff), allowEmpty = true,
                weekStart = weekStart, chinaMakeup = chinaMakeup,
            ).associateBy { it.startTime }
        } catch (e: Recurrence.RuleError) {
            throw ToolError(e.message ?: "rule error")
        }
        val planned = mutableMapOf<String, CloudRecord>()
        val protectedDays = mutableSetOf<String>()
        val existing = taskOccurrences(taskId)
        for (entry in existing) {
            val fields = entry.attributes
            val day = fields.str("startTime") ?: ""
            if ((fields.int("isFinish") ?: 0) != 0 || (fields.int("completedCount") ?: 0) != 0
                || (day < cutoff && (row.int("haveNoTime") ?: 0) == 0)) {
                protectedDays += day
            } else {
                if (day in planned) {
                    throw ToolError("Multiple pending occurrences share a date; reconcile them before editing the series.")
                }
                planned[day] = entry
            }
        }
        val targetMutable = target.toMutableMap()
        for (day in protectedDays) targetMutable.remove(day)
        val insert = targetMutable.filterKeys { it !in planned }.values.map { newOccurrence(taskId, it) }
        val prune = planned.filterKeys { it !in targetMutable }.values
        val patchList = targetMutable.filterKeys { it in planned }
            .filter { (day, slot) ->
                val attrs = planned[day]!!.attributes
                slot.let { s ->
                    attrs.str("startTime") != s.startTime || attrs.str("endTime") != s.endTime ||
                        attrs.str("validStartTime") != s.validStartTime || attrs.str("validEndTime") != s.validEndTime
                }
            }
            .map { (day, slot) -> planned[day]!! to slot }
        if (targetMutable.isEmpty() && protectedDays.isEmpty()) {
            throw ToolError("This change would leave the task without any occurrences; adjust dates or delete the task.")
        }
        val result = RecurrenceUpdateResult(
            taskId = taskId, fromDate = cutoff,
            created = insert.size, rescheduled = patchList.size,
            removed = prune.size, protectedDates = protectedDays.size,
        )
        if (preview) return result.copy(preview = true)
        try {
            val indexed = if (insert.isNotEmpty())
                occurrenceIndex(insert.map { it["id2"].toString().trim('"') }.toSet()) else emptyMap()
            if (indexed.values.any { it.attributes["id1"].toString().trim('"') != taskId }) {
                throw ToolError("A generated occurrence ID is already in use by a different task.")
            }
            for (entry in insert) {
                ensureRecord("showTask", "id2", entry["id2"].toString().trim('"'), entry, indexed[entry["id2"].toString().trim('"')])
            }
            for ((record, slot) in patchList) {
                patch("showTask", record, mapOf(
                    "startTime" to slot.startTime, "endTime" to slot.endTime,
                    "validStartTime" to slot.validStartTime, "validEndTime" to slot.validEndTime,
                ))
            }
            task = patch("Task", task, changes)
            for (record in prune) deleteRecord("showTask", record)
            task = reconcileTask(task, taskOccurrences(taskId, force = true)).first
        } catch (e: Exception) {
            when (e) {
                is CloudError, is ToolError -> throw ToolError(
                    e.message ?: "recurrence update failed",
                    mapOf("task_id" to taskId, "from_date" to cutoff, "may_have_written" to true),
                )
                else -> throw e
            }
        }
        return result.copy(task = taskView(task))
    }

    suspend fun rescheduleTask(
        taskId: String, startDate: String, endDate: String? = null,
        startTime: String? = null, endTime: String? = null,
    ): Pair<TaskView, OccurrenceView> {
        var task = fresh("Task", find("Task", "id1", taskId)!!)
        val row = task.attributes
        if ((row.int("isLoop") ?: 0) != 0 || (row.int("loopSet") ?: 0) != 0) {
            throw ToolError("Rescheduling a repeating series is not supported by this operation.")
        }
        val instances = taskOccurrences(taskId)
        if (instances.size != 1) {
            throw ToolError("Rescheduling requires exactly one existing occurrence.")
        }
        var instance = fresh("showTask", instances[0])
        if ((instance.attributes.int("isFinish") ?: 0) != 0 || (instance.attributes.int("completedCount") ?: 0) < 0) {
            throw ToolError("Only pending tasks can be rescheduled.")
        }
        val currentStart = (instance.attributes.str("validStartTime") ?: "00:00:00").take(5)
        val currentEnd = (instance.attributes.str("validEndTime") ?: "23:59:00").take(5)
        val window = dateWindow(
            startDate, endDate,
            startTime ?: currentStart, endTime ?: currentEnd,
        )
        try {
            task = patch("Task", task, window + ("haveNoTime" to 0))
            instance = patch("showTask", instance, window)
        } catch (e: Exception) {
            when (e) {
                is CloudError, is ToolError ->
                    throw ToolError(e.message ?: "reschedule failed", mapOf("task_id" to taskId, "task_confirmed" to true))
                else -> throw e
            }
        }
        return taskView(task) to occurrenceView(instance, task)
    }

    private suspend fun taskOccurrences(taskId: String, force: Boolean = false): List<CloudRecord> =
        records("showTask", force).filter { it.attributes["id1"].toString().trim('"') == taskId }

    suspend fun getOccurrence(occurrenceId: String): Pair<TaskView, OccurrenceView> {
        val instance = fresh("showTask", find("showTask", "id2", occurrenceId)!!)
        val tid = instance.attributes["id1"].toString().trim('"')
        val task = fresh("Task", find("Task", "id1", tid)!!)
        return taskView(task) to occurrenceView(instance, task)
    }

    suspend fun rescheduleOccurrence(
        occurrenceId: String, startDate: String, endDate: String? = null,
        startTime: String? = null, endTime: String? = null,
    ): Triple<TaskView, OccurrenceView, String> {
        var instance = fresh("showTask", find("showTask", "id2", occurrenceId)!!)
        val row = instance.attributes
        val taskId = row["id1"].toString().trim('"')
        var task = fresh("Task", find("Task", "id1", taskId)!!)
        if ((task.attributes.int("isLoop") ?: 0) == 0 && (task.attributes.int("loopSet") ?: 0) == 0) {
            val (tv, ov) = rescheduleTask(taskId, startDate, endDate, startTime, endTime)
            return Triple(tv, ov, taskId)
        }
        if ((row.int("isFinish") ?: 0) != 0 || (row.int("completedCount") ?: 0) < 0) {
            throw ToolError("Only pending occurrences can be rescheduled.")
        }
        val span = LocalDate.parse(row.str("endTime")).toEpochDay() - LocalDate.parse(row.str("startTime")).toEpochDay()
        val actualEnd = endDate ?: LocalDate.parse(calendarDate(startDate)).plusDays(span).toString()
        val changes = dateWindow(
            startDate, actualEnd,
            startTime ?: (row.str("validStartTime") ?: "00:00:00").take(5),
            endTime ?: (row.str("validEndTime") ?: "23:59:00").take(5),
        )
        val detachedId = occurrenceId(occurrenceId, "detached")
        val clone = buildJsonObject {
            for ((k, v) in task.attributes) put(k, v)
            for ((k, v) in changes) put(k, v.toString())
            put("id1", detachedId)
            put("isLoop", 0); put("haveNoTime", 0)
            put("loopSet", 0); put("loopUnit", 0); put("loopBaseNumber", 1)
            put("loopDate", ""); put("duration", 0); put("isFinish", 0)
        }
        try {
            ensureRecord("Task", "id1", detachedId, clone, find("Task", "id1", detachedId, required = false))
            val detached = fresh("Task", find("Task", "id1", detachedId)!!)
            val others = taskOccurrences(taskId).filter { it.id != instance.id }
            reconcileTask(task, others)
            instance = patch("showTask", instance, changes + ("id1" to detachedId), reparent = true)
            return Triple(taskView(detached), occurrenceView(instance, detached), taskId)
        } catch (e: Exception) {
            when (e) {
                is CloudError, is ToolError -> throw ToolError(
                    e.message ?: "reschedule failed",
                    mapOf("occurrence_id" to occurrenceId, "task_id" to taskId, "detached_task_id" to detachedId),
                )
                else -> throw e
            }
        }
    }

    suspend fun clearSchedule(taskId: String): Pair<TaskView, OccurrenceView> {
        var task = fresh("Task", find("Task", "id1", taskId)!!)
        val row = task.attributes
        if ((row.int("isLoop") ?: 0) != 0 || (row.int("loopSet") ?: 0) != 0) {
            throw ToolError("A repeat series needs scheduled dates.")
        }
        val instances = taskOccurrences(taskId)
        if (instances.size != 1) throw ToolError("Clearing a schedule requires exactly one occurrence.")
        val window = dateWindow("1970-01-01", "2100-12-31", null, null)
        try {
            val item = patch("showTask", instances[0], window)
            task = patch("Task", task, window + mapOf(
                "haveNoTime" to 1,
                "isRemind" to ((row.int("isRemind") ?: 0) and 1.inv()),
            ))
            return taskView(task) to occurrenceView(item, task)
        } catch (e: Exception) {
            when (e) {
                is CloudError, is ToolError ->
                    throw ToolError(e.message ?: "clear failed", mapOf("task_id" to taskId, "may_have_written" to true))
                else -> throw e
            }
        }
    }

    private suspend fun reconcileTask(task: CloudRecord, instances: List<CloudRecord>): Pair<CloudRecord, Boolean> {
        val summary = instances.isNotEmpty() && instances.none {
            (it.attributes.int("isFinish") ?: 0) == 0 && (it.attributes.int("completedCount") ?: 0) >= 0
        }
        return patch("Task", task, mapOf("isFinish" to if (summary) 1 else 0)) to summary
    }

    // ---------- 进度 ----------

    suspend fun completeOccurrence(occurrenceId: String, completedAt: String? = null): Pair<OccurrenceView, Boolean> =
        updateProgress(occurrenceId, action = "complete", completedAt = completedAt)

    suspend fun completeSubtask(occurrenceId: String, subtaskIndex: Int): Pair<OccurrenceView, Boolean> =
        updateProgress(occurrenceId, subtaskIndex = subtaskIndex, action = "complete")

    suspend fun changeProgress(
        occurrenceId: String, action: String, subtaskIndex: Int? = null,
        count: Int? = null, completedAt: String? = null,
    ): Pair<OccurrenceView, Boolean> {
        if ((subtaskIndex != null) != (action == "uncheck_subtask") || (count != null) != (action == "set_count")) {
            throw ToolError("Supply subtask_index for uncheck_subtask or count for set_count.")
        }
        if (completedAt != null && action != "set_count") {
            throw ToolError("completed_at applies to set_count or complete_occurrence.")
        }
        return updateProgress(occurrenceId, subtaskIndex, action = action, count = count, completedAt = completedAt)
    }

    private suspend fun updateProgress(
        occurrenceId: String, subtaskIndex: Int? = null, action: String = "complete",
        count: Int? = null, completedAt: String? = null,
    ): Pair<OccurrenceView, Boolean> {
        val composed = if (completedAt != null) {
            if (!Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}").matches(completedAt)) {
                throw ToolError("Use a local completion time in YYYY-MM-DDTHH:MM format.")
            }
            calendarDate(completedAt.take(10)) + " " + clockTime(completedAt.substring(11))
        } else {
            nowProvider().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:00"))
        }
        var occurrence = fresh("showTask", find("showTask", "id2", occurrenceId)!!)
        val taskId = occurrence.attributes["id1"].toString().trim('"')
        var task = fresh("Task", find("Task", "id1", taskId)!!)
        val target = task.attributes.int("unitTimeNumber") ?: 1
        val previous = occurrence.attributes.int("completedCount") ?: 0
        var progress = target
        if (subtaskIndex != null) {
            val decoded = TaskContent.decode(task.attributes.str("taskContent") ?: "")
            if (target < 100 || subtaskIndex !in 0 until minOf(decoded.subtasks.size, 20)) {
                throw ToolError("subtask_index is a zero-based index for a subtask on this occurrence.")
            }
            val mask = 1 shl subtaskIndex
            if ((target / 100) and mask == 0) {
                throw ToolError("The requested subtask is not present in the stored goal mask.")
            }
            val bits = maxOf(previous, 0) / 100
            progress = (if (action == "uncheck_subtask") bits and mask.inv() else bits or mask) * 100
        }
        when (action) {
            "reopen" -> progress = 0
            "abandon" -> progress = -1
            "restore" -> progress = if (previous < 0) 0 else previous
            "set_count" -> {
                if (target >= 100 || count == null || count !in 0..target) {
                    throw ToolError("Provide a count between 0 and the numeric target on a count-based task.")
                }
                progress = count
            }
        }
        val changes = mutableMapOf<String, Any?>("isFinish" to if (progress >= target) 1 else 0)
        if (previous != progress || completedAt != null) {
            changes["completedCount"] = progress
            changes["recentFinishedTime"] = composed
        }
        try {
            occurrence = patch("showTask", occurrence, changes)
            var refreshed = taskOccurrences(taskId, force = true)
            if (refreshed.none { it.attributes["id2"].toString().trim('"') == occurrenceId }) {
                throw ToolError("Updated occurrence was missing from the refreshed cloud list.")
            }
            refreshed = refreshed.map { if (it.id == occurrence.id) occurrence else it }
            val (newTask, summary) = reconcileTask(task, refreshed)
            task = newTask
            invalidate("Task")
            return occurrenceView(occurrence, task) to summary
        } catch (e: Exception) {
            when (e) {
                is CloudError, is ToolError -> throw ToolError(
                    e.message ?: "progress update failed",
                    mapOf("occurrence_id" to occurrenceId, "task_id" to taskId),
                )
                else -> throw e
            }
        }
    }

    // ---------- 批量 ----------

    data class BatchItemResult(val ok: Boolean, val result: Any? = null, val error: String? = null)

    suspend fun batchAddTasks(tasks: List<Map<String, Any?>>): List<BatchItemResult> =
        tasks.map { item ->
            try {
                @Suppress("UNCHECKED_CAST")
                val result = createTask(
                    title = item["title"] as String,
                    startDate = item["start_date"] as String?,
                    endDate = item["end_date"] as String?,
                    startTime = item["start_time"] as String?,
                    endTime = item["end_time"] as String?,
                    listId = item["list_id"] as? String ?: "1",
                    remark = item["remark"] as? String ?: "",
                    subtasks = item["subtasks"] as? List<String>,
                    important = item["important"] as? Boolean ?: false,
                    urgent = item["urgent"] as? Boolean ?: false,
                    reminderDate = item["reminder_date"] as? String,
                    reminderTime = item["reminder_time"] as? String,
                    repeatRule = item["repeat_rule"] as? RepeatRule,
                    habit = item["habit"] as? Boolean ?: false,
                    targetCount = item["target_count"] as? Int,
                )
                BatchItemResult(true, result)
            } catch (e: Exception) {
                BatchItemResult(false, error = e.message)
            }
        }
}
