package com.biandan.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biandan.app.data.AppStore
import com.biandan.app.data.OccurrenceView
import com.biandan.app.data.RepeatRule
import com.biandan.app.data.TaskView
import kotlinx.coroutines.launch
import java.time.LocalDate

// ---------- 实例/任务详情 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    store: AppStore,
    occurrenceId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pair by remember { mutableStateOf<Pair<TaskView, OccurrenceView>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val lists by store.lists.collectAsState()

    fun load() {
        scope.launch {
            try {
                pair = store.run { store.repo.getOccurrence(occurrenceId) }
            } catch (e: Exception) {
                error = e.message
            }
        }
    }
    LaunchedEffect(occurrenceId) { load() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("任务详情") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
        )
        val p = pair
        when {
            error != null -> EmptyHint("加载失败：$error")
            p == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> {
                val (task, occ) = p
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(task.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        if (task.remark.isNotEmpty()) {
                            Text(task.remark, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    item {
                        Card {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                MetaRow("清单", lists.firstOrNull { it.listId == task.listId }?.name ?: task.listId)
                                MetaRow("状态", statusLabel(occ.status) + if (task.completed) "（任务已全部完成）" else "")
                                if (task.undated) MetaRow("排程", "无日期")
                                else MetaRow("日期", "${occ.startDate} → ${occ.endDate}")
                                if (occ.startTime != "00:00:00" || occ.endTime != "23:59:00") {
                                    MetaRow("时段", "${occ.startTime?.take(5)} – ${occ.endTime?.take(5)}")
                                }
                                if (task.recurrence != null) MetaRow("重复", describeRule(task.recurrence) + if (task.isHabit) " · 习惯" else "")
                                if (task.timeReminder != null) {
                                    MetaRow("提醒", "${task.timeReminder.date ?: ""} ${task.timeReminder.time?.take(5) ?: ""}")
                                }
                                task.location?.let { loc ->
                                    MetaRow("地点", loc.name + if (task.hasLocationReminder) "（到达提醒）" else "")
                                }
                                MetaRow("标记", buildList {
                                    if (task.important) add("重要")
                                    if (task.urgent) add("紧急")
                                    if (task.collected) add("已收藏")
                                }.joinToString("、").ifEmpty { "无" })
                            }
                        }
                    }
                    // 子任务 / 目标
                    if (task.subtasks.isNotEmpty()) {
                        item { Text("子任务", fontWeight = FontWeight.Bold) }
                        itemsIndexed(task.subtasks) { i, title ->
                            val checked = i in occ.completedSubtaskIndices
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = occ.status == "pending") {
                                    scope.launch {
                                        store.run {
                                            if (checked) store.repo.changeProgress(occ.occurrenceId, "uncheck_subtask", subtaskIndex = i)
                                            else store.repo.completeSubtask(occ.occurrenceId, i)
                                        }; load()
                                    }
                                }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked, onCheckedChange = null, enabled = occ.status == "pending")
                                Text(title, color = if (checked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    } else if (task.targetCount > 1 && occ.status == "pending") {
                        item {
                            Card {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("进度", Modifier.weight(1f))
                                    TextButton(onClick = {
                                        scope.launch {
                                            store.run {
                                                store.repo.changeProgress(occ.occurrenceId, "set_count",
                                                    count = (occ.completedCount - 1).coerceAtLeast(0))
                                            }; load()
                                        }
                                    }) { Text("−") }
                                    Text("${occ.completedCount} / ${occ.targetCount}", style = MaterialTheme.typography.titleMedium)
                                    TextButton(onClick = {
                                        scope.launch {
                                            store.run {
                                                store.repo.changeProgress(occ.occurrenceId, "set_count",
                                                    count = (occ.completedCount + 1).coerceAtMost(occ.targetCount))
                                            }; load()
                                        }
                                    }) { Text("+") }
                                }
                            }
                        }
                    }
                    // 操作
                    item {
                        Text("操作", fontWeight = FontWeight.Bold)
                        ActionButtons(store, task, occ,
                            reload = { load() }, onDeleted = onDeleted, scope = scope)
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row {
        Text(label, Modifier.width(56.dp), color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionButtons(
    store: AppStore, task: TaskView, occ: OccurrenceView,
    reload: () -> Unit, onDeleted: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var showReschedule by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var showLocation by remember { mutableStateOf(false) }
    val lists by store.lists.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (occ.status) {
                "pending" -> {
                    Button(onClick = {
                        scope.launch { store.run { store.repo.completeOccurrence(occ.occurrenceId) }; reload() }
                    }) { Text("完成") }
                    OutlinedButton(onClick = {
                        scope.launch { store.run { store.repo.changeProgress(occ.occurrenceId, "abandon") }; reload() }
                    }) { Text("放弃") }
                }
                "completed" -> OutlinedButton(onClick = {
                    scope.launch { store.run { store.repo.changeProgress(occ.occurrenceId, "reopen") }; reload() }
                }) { Text("重开") }
                "abandoned" -> OutlinedButton(onClick = {
                    scope.launch { store.run { store.repo.changeProgress(occ.occurrenceId, "restore") }; reload() }
                }) { Text("恢复") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showEdit = true }) { Text("编辑") }
            if (occ.status == "pending") {
                OutlinedButton(onClick = { showReschedule = true }) { Text("改期") }
            }
            OutlinedButton(onClick = { showMove = true }) { Text("移动清单") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showReminder = true }) { Text("提醒") }
            OutlinedButton(onClick = { showLocation = true }) { Text("地点") }
            OutlinedButton(onClick = { showDelete = true }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showReschedule) {
        var sd by remember { mutableStateOf(occ.startDate) }
        var ed by remember { mutableStateOf(occ.endDate) }
        var st by remember { mutableStateOf(occ.startTime?.take(5)) }
        var et by remember { mutableStateOf(occ.endTime?.take(5)) }
        AlertDialog(
            onDismissRequest = { showReschedule = false },
            title = { Text("改期" + if (task.recurrence != null) "（仅此次，将脱离系列）" else "") },
            text = {
                Column {
                    Row { DateField("开始日", sd, { sd = it }); DateField("结束日", ed, { ed = it }) }
                    Row { TimeField("开始时间", st, { st = it?.take(5) }); TimeField("结束时间", et, { et = it?.take(5) }) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (sd != null) scope.launch {
                        store.run { store.repo.rescheduleOccurrence(occ.occurrenceId, sd!!, ed, st, et) }
                        showReschedule = false; reload()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showReschedule = false }) { Text("取消") } },
        )
    }

    if (showMove) {
        AlertDialog(
            onDismissRequest = { showMove = false },
            title = { Text("移动到其他清单") },
            text = {
                Column {
                    for (l in lists) {
                        Text(
                            "${l.name}",
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    store.run { store.repo.moveTask(task.taskId, l.listId) }
                                    showMove = false; reload()
                                }
                            }.padding(vertical = 12.dp),
                            color = ListPalette.color(l.color),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMove = false }) { Text("取消") } },
        )
    }

    if (showEdit) {
        EditTaskDialog(store, task, onDone = { showEdit = false; reload() }, onCancel = { showEdit = false })
    }

    if (showReminder) {
        var enabled by remember { mutableStateOf(task.timeReminder != null) }
        var rd by remember { mutableStateOf(task.timeReminder?.date ?: occ.startDate) }
        var rt by remember { mutableStateOf(task.timeReminder?.time?.take(5) ?: "09:00") }
        AlertDialog(
            onDismissRequest = { showReminder = false },
            title = { Text("时间提醒") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(enabled, { enabled = it }); Spacer(Modifier.width(8.dp)); Text("启用提醒")
                    }
                    if (enabled) Row { DateField("日期", rd, { rd = it }); TimeField("时间", rt, { rt = it?.take(5) ?: "09:00" }) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        store.run {
                            if (enabled) store.repo.setTimeReminder(task.taskId, rd, rt)
                            else store.repo.setTimeReminder(task.taskId, enabled = false)
                        }
                        showReminder = false; reload()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showReminder = false }) { Text("取消") } },
        )
    }

    if (showLocation) {
        var name by remember { mutableStateOf(task.location?.name ?: "") }
        var lat by remember { mutableStateOf(task.location?.latitude?.takeIf { it != -1.0 }?.toString() ?: "") }
        var lon by remember { mutableStateOf(task.location?.longitude?.takeIf { it != -1.0 }?.toString() ?: "") }
        var remind by remember { mutableStateOf(task.hasLocationReminder) }
        AlertDialog(
            onDismissRequest = { showLocation = false },
            title = { Text("地点") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("地点名") }, singleLine = true)
                    Row {
                        OutlinedTextField(lat, { lat = it }, label = { Text("纬度") }, modifier = Modifier.weight(1f), singleLine = true)
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(lon, { lon = it }, label = { Text("经度") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(remind, { remind = it }); Spacer(Modifier.width(8.dp)); Text("到达提醒")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        store.run {
                            if (name.isBlank() && lat.isBlank() && lon.isBlank() && !remind) {
                                store.repo.setLocation(task.taskId, clear = true)
                            } else {
                                store.repo.setLocation(
                                    task.taskId,
                                    name = name.ifBlank { null },
                                    latitude = lat.toDoubleOrNull(), longitude = lon.toDoubleOrNull(),
                                    remind = remind,
                                )
                            }
                        }
                        showLocation = false; reload()
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            store.run { store.repo.setLocation(task.taskId, clear = true) }
                            showLocation = false; reload()
                        }
                    }) { Text("清除地点") }
                    TextButton(onClick = { showLocation = false }) { Text("取消") }
                }
            },
        )
    }

    if (showDelete) {
        var scopeChoice by remember { mutableStateOf("series") }
        val repeating = task.recurrence != null || task.repeatType != 0
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除任务") },
            text = {
                Column {
                    if (repeating) {
                        listOf(
                            "occurrence" to "仅删除这一次",
                            "future_pending" to "删除今天起未完成的",
                            "series" to "删除整个系列",
                        ).forEach { (v, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(
                                    selected = scopeChoice == v, onClick = { scopeChoice = v })
                                Text(label)
                            }
                        }
                    } else Text("将删除任务及其唯一实例")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        store.run {
                            store.repo.deleteTask(
                                task.taskId,
                                scope = if (repeating) scopeChoice else null,
                                occurrenceId = if (repeating && scopeChoice == "occurrence") occ.occurrenceId else null,
                            )
                        }
                        store.run { store.refresh() }
                        showDelete = false; onDeleted()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun EditTaskDialog(store: AppStore, task: TaskView, onDone: () -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(task.title) }
    var remark by remember { mutableStateOf(task.remark) }
    var important by remember { mutableStateOf(task.important) }
    var urgent by remember { mutableStateOf(task.urgent) }
    var collected by remember { mutableStateOf(task.collected) }
    val subtasks = remember { mutableStateListOf<String>().apply { addAll(task.subtasks) } }
    var target by remember { mutableStateOf(if (task.subtasks.isEmpty()) task.targetCount.toString() else "") }
    var newSub by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("编辑任务") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(remark, { remark = it }, label = { Text("备注") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(important, { important = !important }, label = { Text("重要") })
                    FilterChip(urgent, { urgent = !urgent }, label = { Text("紧急") })
                    FilterChip(collected, { collected = !collected }, label = { Text("收藏") })
                }
                Spacer(Modifier.height(8.dp))
                Text("子任务", fontWeight = FontWeight.Bold)
                subtasks.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. $s", Modifier.weight(1f))
                        IconButton(onClick = { subtasks.removeAt(i) }) { Icon(Icons.Default.Delete, null) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newSub, { newSub = it }, Modifier.weight(1f),
                        placeholder = { Text("添加子任务") }, singleLine = true)
                    IconButton(onClick = {
                        if (newSub.isNotBlank() && subtasks.size < 20) { subtasks += newSub.trim(); newSub = "" }
                    }) { Icon(Icons.Default.Add, null) }
                }
                if (subtasks.isEmpty()) {
                    OutlinedTextField(target, { target = it.filter(Char::isDigit) },
                        label = { Text("目标次数（留空=1）") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    store.run {
                        store.repo.updateTask(task.taskId, title = title, remark = remark,
                            important = important, urgent = urgent, collected = collected)
                        if (subtasks.isNotEmpty()) {
                            if (subtasks != task.subtasks) {
                                store.repo.editTaskGoal(task.taskId, subtasks = subtasks.toList())
                            }
                        } else {
                            val t = target.toIntOrNull() ?: 1
                            if (t != task.targetCount) store.repo.editTaskGoal(task.taskId, targetCount = t)
                        }
                    }
                    onDone()
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

// ---------- 新建任务 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTaskScreen(store: AppStore, onDone: () -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lists by store.lists.collectAsState()
    var title by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var listId by remember { mutableStateOf("1") }
    var dated by remember { mutableStateOf(true) }
    var startDate by remember { mutableStateOf<String?>(LocalDate.now().toString()) }
    var endDate by remember { mutableStateOf<String?>(LocalDate.now().toString()) }
    var startTime by remember { mutableStateOf<String?>(null) }
    var endTime by remember { mutableStateOf<String?>(null) }
    var important by remember { mutableStateOf(false) }
    var urgent by remember { mutableStateOf(false) }
    var habit by remember { mutableStateOf(false) }
    var remindOn by remember { mutableStateOf(false) }
    var remindDate by remember { mutableStateOf<String?>(null) }
    var remindTime by remember { mutableStateOf<String?>("09:00") }
    var repeatMode by remember { mutableStateOf("none") }
    var customUnit by remember { mutableStateOf("day") }
    var customInterval by remember { mutableStateOf("1") }
    var weekDays by remember { mutableStateOf(setOf(1)) } // 0=日..6=六
    var monthDays by remember { mutableStateOf("1") }
    var yearDay by remember { mutableStateOf("01-01") }
    var dayFilter by remember { mutableStateOf("none") }
    var durationDays by remember { mutableStateOf("1") }
    var target by remember { mutableStateOf("") }
    var locName by remember { mutableStateOf("") }
    var locLat by remember { mutableStateOf("") }
    var locLon by remember { mutableStateOf("") }
    var locRemind by remember { mutableStateOf(false) }
    val subtasks = remember { mutableStateListOf<String>() }
    var newSub by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val repeatOptions = listOf(
        "none" to "不重复", "daily" to "每天", "workdays" to "工作日", "days_off" to "休息日",
        "ebbinghaus" to "艾宾浩斯", "weekly_period" to "每周", "monthly_period" to "每月",
        "yearly_period" to "每年", "custom" to "自定义",
    )

    fun buildRule(): RepeatRule? = when (repeatMode) {
        "none" -> null
        "custom" -> RepeatRule(
            mode = "custom", unit = customUnit,
            interval = customInterval.toIntOrNull()?.coerceIn(1, 99) ?: 1,
            dates = when (customUnit) {
                "week" -> weekDays.sorted().map { it.toString() }
                "month" -> monthDays.split(Regex("[,，、\\s]+")).filter { it.isNotBlank() }
                "year" -> listOf(yearDay)
                else -> emptyList()
            },
            dayFilter = dayFilter.takeIf { it != "none" },
            durationDays = durationDays.toIntOrNull()?.coerceIn(1, 30) ?: 1,
        )
        else -> RepeatRule(mode = repeatMode)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("新建任务") },
            navigationIcon = {
                IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            },
            actions = {
                TextButton(enabled = !saving, onClick = {
                    saving = true; error = null
                    scope.launch {
                        val loc = if (locName.isNotBlank() || locLat.isNotBlank()) mapOf(
                            "name" to locName.ifBlank { null },
                            "latitude" to locLat.toDoubleOrNull(),
                            "longitude" to locLon.toDoubleOrNull(),
                            "remind" to locRemind,
                        ) else null
                        val result = store.run {
                            store.repo.createTask(
                                title = title,
                                startDate = if (dated) startDate else null,
                                endDate = if (dated) endDate else null,
                                startTime = if (dated) startTime else null,
                                endTime = if (dated) endTime else null,
                                listId = listId,
                                remark = remark,
                                subtasks = subtasks.toList().ifEmpty { null },
                                important = important, urgent = urgent,
                                reminderDate = if (remindOn) remindDate else null,
                                reminderTime = if (remindOn) remindTime else null,
                                repeatRule = if (dated) buildRule() else null,
                                habit = habit,
                                targetCount = if (subtasks.isEmpty()) target.toIntOrNull() else null,
                                location = loc,
                                weekStart = store.weekStart,
                                chinaMakeup = store.chinaMakeup,
                            )
                        }
                        saving = false
                        if (result != null) {
                            store.run { store.refresh() }
                            onDone()
                        }
                    }
                }) { Text(if (saving) "保存中…" else "保存") }
            },
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(),
                label = { Text("标题（必填）") }, singleLine = true)
            OutlinedTextField(remark, { remark = it }, Modifier.fillMaxWidth(), label = { Text("备注") })

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(important, { important = !important }, label = { Text("重要") })
                FilterChip(urgent, { urgent = !urgent }, label = { Text("紧急") })
                FilterChip(habit, { habit = !habit }, label = { Text("习惯") })
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("清单", Modifier.weight(1f))
                    }
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(lists.size) { i ->
                            val l = lists[i]
                            FilterChip(listId == l.listId, { listId = l.listId }, label = { Text(l.name) })
                        }
                    }
                }
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("排定日期", Modifier.weight(1f))
                        Switch(dated, {
                            dated = it
                            if (!it) { repeatMode = "none"; remindOn = false }
                        })
                    }
                    if (dated) {
                        Row { DateField("开始日", startDate, { startDate = it }); DateField("结束日", endDate, { endDate = it }) }
                        Row { TimeField("开始时间", startTime, { startTime = it?.take(5) }); TimeField("结束时间", endTime, { endTime = it?.take(5) }) }
                    }
                }
            }

            if (dated) {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("重复", fontWeight = FontWeight.Bold)
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(repeatOptions.size) { i ->
                                val (v, label) = repeatOptions[i]
                                FilterChip(repeatMode == v, {
                                    repeatMode = v
                                    if (v == "none") habit = false
                                }, label = { Text(label) })
                            }
                        }
                        if (repeatMode == "custom") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("每")
                                OutlinedTextField(customInterval, { customInterval = it.filter(Char::isDigit) },
                                    Modifier.width(72.dp), singleLine = true)
                                listOf("day" to "天", "week" to "周", "month" to "月", "year" to "年").forEach { (v, l) ->
                                    FilterChip(customUnit == v, { customUnit = v }, label = { Text(l) })
                                }
                            }
                            when (customUnit) {
                                "week" -> {
                                    Text("星期", style = MaterialTheme.typography.labelLarge)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf("日", "一", "二", "三", "四", "五", "六").forEachIndexed { i, d ->
                                            FilterChip(i in weekDays, {
                                                weekDays = if (i in weekDays) weekDays - i else weekDays + i
                                            }, label = { Text(d) })
                                        }
                                    }
                                }
                                "month" -> OutlinedTextField(monthDays, { monthDays = it },
                                    label = { Text("日期（如 1,15 或 32=月末）") }, singleLine = true)
                                "year" -> OutlinedTextField(yearDay, { yearDay = it },
                                    label = { Text("月-日（如 01-01）") }, singleLine = true)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("仅")
                                listOf("none" to "不限", "workdays" to "工作日", "days_off" to "休息日").forEach { (v, l) ->
                                    FilterChip(dayFilter == v, { dayFilter = v }, label = { Text(l) })
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("每次持续")
                                OutlinedTextField(durationDays, { durationDays = it.filter(Char::isDigit) },
                                    Modifier.width(72.dp), singleLine = true)
                                Text("天")
                            }
                        }
                    }
                }
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("时间提醒", Modifier.weight(1f))
                            Switch(remindOn, { remindOn = it; if (it && remindDate == null) remindDate = startDate })
                        }
                        if (remindOn) Row { DateField("日期", remindDate, { remindDate = it }); TimeField("时间", remindTime, { remindTime = it?.take(5) }) }
                    }
                }
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("地点", fontWeight = FontWeight.Bold)
                        OutlinedTextField(locName, { locName = it }, Modifier.fillMaxWidth(),
                            label = { Text("地点名") }, singleLine = true)
                        Row {
                            OutlinedTextField(locLat, { locLat = it }, Modifier.weight(1f),
                                label = { Text("纬度") }, singleLine = true)
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(locLon, { locLon = it }, Modifier.weight(1f),
                                label = { Text("经度") }, singleLine = true)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(locRemind, { locRemind = it }); Spacer(Modifier.width(8.dp)); Text("到达提醒")
                        }
                    }
                }
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("目标", fontWeight = FontWeight.Bold)
                    subtasks.forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}. $s", Modifier.weight(1f))
                            IconButton(onClick = { subtasks.removeAt(i) }) { Icon(Icons.Default.Delete, null) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newSub, { newSub = it }, Modifier.weight(1f),
                            placeholder = { Text("添加子任务（最多20）") }, singleLine = true)
                        IconButton(onClick = {
                            if (newSub.isNotBlank() && subtasks.size < 20) { subtasks += newSub.trim(); newSub = "" }
                        }) { Icon(Icons.Default.Add, null) }
                    }
                    if (subtasks.isEmpty()) {
                        OutlinedTextField(target, { target = it.filter(Char::isDigit) },
                            label = { Text("或数值目标（如每天 8 杯水填 8）") }, singleLine = true)
                    } else {
                        Text("已用子任务，数值目标不可用", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}
