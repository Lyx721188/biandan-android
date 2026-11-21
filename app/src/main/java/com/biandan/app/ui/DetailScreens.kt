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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextDecoration
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
                val done = occ.status == "completed"
                var showEdit by remember { mutableStateOf(false) }
                var showReschedule by remember { mutableStateOf(false) }
                var showMove by remember { mutableStateOf(false) }
                var showReminder by remember { mutableStateOf(false) }
                var showLocation by remember { mutableStateOf(false) }
                var showDelete by remember { mutableStateOf(false) }
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 头部：圆勾 + 标题 + 备注（Todoist 式）
                    item {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            CircleCheck(done, occ.status == "abandoned") {
                                scope.launch {
                                    store.run {
                                        if (done) store.repo.changeProgress(occ.occurrenceId, "reopen")
                                        else if (occ.status == "pending") store.repo.completeOccurrence(occ.occurrenceId)
                                        else store.repo.changeProgress(occ.occurrenceId, "restore")
                                    }; load()
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    task.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    textDecoration = if (done) TextDecoration.LineThrough else null,
                                    color = if (done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                                )
                                if (task.remark.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(task.remark, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                    // 元数据卡：图标行
                    item {
                        Card(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val listColor = ListPalette.color(lists.firstOrNull { it.listId == task.listId }?.color ?: 0)
                                MetaLine(Icons.Default.List, listColor,
                                    lists.firstOrNull { it.listId == task.listId }?.name ?: "未分类")
                                MetaLine(Icons.Default.Info, MaterialTheme.colorScheme.outline,
                                    statusLabel(occ.status) + if (task.completed) "（系列已全部完成）" else "")
                                MetaLine(Icons.Default.DateRange, MaterialTheme.colorScheme.primary,
                                    if (task.undated) "无日期" else "${occ.startDate} → ${occ.endDate}")
                                if (occ.startTime != "00:00:00" || occ.endTime != "23:59:00") {
                                    MetaLine(Icons.Default.Refresh, MaterialTheme.colorScheme.primary,
                                        "${occ.startTime?.take(5)} – ${occ.endTime?.take(5)}")
                                }
                                if (task.recurrence != null) {
                                    MetaLine(Icons.Default.Refresh, MaterialTheme.colorScheme.primary,
                                        describeRule(task.recurrence) + if (task.isHabit) " · 习惯" else "")
                                }
                                if (task.timeReminder != null) {
                                    MetaLine(Icons.Default.Notifications, MaterialTheme.colorScheme.tertiary,
                                        "${task.timeReminder.date ?: ""} ${task.timeReminder.time?.take(5) ?: ""}")
                                }
                                task.location?.let { loc ->
                                    MetaLine(Icons.Default.Place, MaterialTheme.colorScheme.tertiary,
                                        loc.name + if (task.hasLocationReminder) "（到达提醒）" else "")
                                }
                                val flags = buildList {
                                    if (task.important) add("重要")
                                    if (task.urgent) add("紧急")
                                    if (task.collected) add("已收藏")
                                }
                                if (flags.isNotEmpty()) {
                                    MetaLine(Icons.Default.Star, MaterialTheme.colorScheme.error, flags.joinToString("、"))
                                }
                            }
                        }
                    }
                    // 动作 chips
                    item {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AssistChip(onClick = { showEdit = true }, label = { Text("编辑") },
                                leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) })
                            if (occ.status == "pending") {
                                AssistChip(onClick = { showReschedule = true }, label = { Text("改期") },
                                    leadingIcon = { Icon(Icons.Default.DateRange, null, Modifier.size(16.dp)) })
                            }
                            AssistChip(onClick = { showMove = true }, label = { Text("移动") })
                            AssistChip(onClick = { showReminder = true }, label = { Text("提醒") },
                                leadingIcon = { Icon(Icons.Default.Notifications, null, Modifier.size(16.dp)) })
                            AssistChip(onClick = { showLocation = true }, label = { Text("地点") },
                                leadingIcon = { Icon(Icons.Default.Place, null, Modifier.size(16.dp)) })
                        }
                    }
                    // 子任务区
                    if (task.subtasks.isNotEmpty()) {
                        item {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text("子任务", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${occ.completedSubtaskIndices.size}/${task.subtasks.size}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        itemsIndexed(task.subtasks) { i, title ->
                            val checked = i in occ.completedSubtaskIndices
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = occ.status == "pending") {
                                        scope.launch {
                                            store.run {
                                                if (checked) store.repo.changeProgress(occ.occurrenceId, "uncheck_subtask", subtaskIndex = i)
                                                else store.repo.completeSubtask(occ.occurrenceId, i)
                                            }; load()
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircleCheck(checked) {}
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    title,
                                    textDecoration = if (checked) TextDecoration.LineThrough else null,
                                    color = if (checked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    } else if (task.targetCount > 1) {
                        item {
                            Card(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("进度", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        Text("${occ.completedCount} / ${occ.targetCount}",
                                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { (occ.completedCount.toFloat() / occ.targetCount).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    )
                                    if (occ.status == "pending") {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = {
                                                scope.launch {
                                                    store.run {
                                                        store.repo.changeProgress(occ.occurrenceId, "set_count",
                                                            count = (occ.completedCount - 1).coerceAtLeast(0))
                                                    }; load()
                                                }
                                            }) { Text("−1") }
                                            OutlinedButton(onClick = {
                                                scope.launch {
                                                    store.run {
                                                        store.repo.changeProgress(occ.occurrenceId, "set_count",
                                                            count = (occ.completedCount + 1).coerceAtMost(occ.targetCount))
                                                    }; load()
                                                }
                                            }) { Text("+1") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 底部主操作 + 删除
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (occ.status) {
                                "pending" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        scope.launch { store.run { store.repo.completeOccurrence(occ.occurrenceId) }; load() }
                                    }, modifier = Modifier.weight(1f)) { Text("完成") }
                                    OutlinedButton(onClick = {
                                        scope.launch { store.run { store.repo.changeProgress(occ.occurrenceId, "abandon") }; load() }
                                    }) { Text("放弃") }
                                }
                                "completed" -> OutlinedButton(onClick = {
                                    scope.launch { store.run { store.repo.changeProgress(occ.occurrenceId, "reopen") }; load() }
                                }, modifier = Modifier.fillMaxWidth()) { Text("重新打开") }
                                "abandoned" -> OutlinedButton(onClick = {
                                    scope.launch { store.run { store.repo.changeProgress(occ.occurrenceId, "restore") }; load() }
                                }, modifier = Modifier.fillMaxWidth()) { Text("恢复待办") }
                            }
                            TextButton(
                                onClick = { showDelete = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("删除任务", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    item { Spacer(Modifier.height(40.dp)) }
                }
                DetailDialogs(
                    store, task, occ, scope,
                    reload = { load() }, onDeleted = onDeleted,
                    showEdit = showEdit, onEditChange = { showEdit = it },
                    showReschedule = showReschedule, onRescheduleChange = { showReschedule = it },
                    showMove = showMove, onMoveChange = { showMove = it },
                    showReminder = showReminder, onReminderChange = { showReminder = it },
                    showLocation = showLocation, onLocationChange = { showLocation = it },
                    showDelete = showDelete, onDeleteChange = { showDelete = it },
                )
            }
        }
    }
}

@Composable
private fun MetaLine(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DetailDialogs(
    store: AppStore, task: TaskView, occ: OccurrenceView,
    scope: kotlinx.coroutines.CoroutineScope,
    reload: () -> Unit, onDeleted: () -> Unit,
    showEdit: Boolean, onEditChange: (Boolean) -> Unit,
    showReschedule: Boolean, onRescheduleChange: (Boolean) -> Unit,
    showMove: Boolean, onMoveChange: (Boolean) -> Unit,
    showReminder: Boolean, onReminderChange: (Boolean) -> Unit,
    showLocation: Boolean, onLocationChange: (Boolean) -> Unit,
    showDelete: Boolean, onDeleteChange: (Boolean) -> Unit,
) {
    val lists by store.lists.collectAsState()

    if (showReschedule) {
        var sd by remember { mutableStateOf(occ.startDate) }
        var ed by remember { mutableStateOf(occ.endDate) }
        var st by remember { mutableStateOf(occ.startTime?.take(5)) }
        var et by remember { mutableStateOf(occ.endTime?.take(5)) }
        AlertDialog(
            onDismissRequest = { onRescheduleChange(false) },
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
                        onRescheduleChange(false); reload()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { onRescheduleChange(false) }) { Text("取消") } },
        )
    }

    if (showMove) {
        AlertDialog(
            onDismissRequest = { onMoveChange(false) },
            title = { Text("移动到其他清单") },
            text = {
                Column {
                    for (l in lists) {
                        Text(
                            "${l.name}",
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    store.run { store.repo.moveTask(task.taskId, l.listId) }
                                    onMoveChange(false); reload()
                                }
                            }.padding(vertical = 12.dp),
                            color = ListPalette.color(l.color),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { onMoveChange(false) }) { Text("取消") } },
        )
    }

    if (showEdit) {
        EditTaskDialog(store, task, onDone = { onEditChange(false); reload() }, onCancel = { onEditChange(false) })
    }

    if (showReminder) {
        var enabled by remember { mutableStateOf(task.timeReminder != null) }
        var rd by remember { mutableStateOf(task.timeReminder?.date ?: occ.startDate) }
        var rt by remember { mutableStateOf(task.timeReminder?.time?.take(5) ?: "09:00") }
        AlertDialog(
            onDismissRequest = { onReminderChange(false) },
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
                        onReminderChange(false); reload()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { onReminderChange(false) }) { Text("取消") } },
        )
    }

    if (showLocation) {
        var name by remember { mutableStateOf(task.location?.name ?: "") }
        var lat by remember { mutableStateOf(task.location?.latitude?.takeIf { it != -1.0 }?.toString() ?: "") }
        var lon by remember { mutableStateOf(task.location?.longitude?.takeIf { it != -1.0 }?.toString() ?: "") }
        var remind by remember { mutableStateOf(task.hasLocationReminder) }
        AlertDialog(
            onDismissRequest = { onLocationChange(false) },
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
                        onLocationChange(false); reload()
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            store.run { store.repo.setLocation(task.taskId, clear = true) }
                            onLocationChange(false); reload()
                        }
                    }) { Text("清除地点") }
                    TextButton(onClick = { onLocationChange(false) }) { Text("取消") }
                }
            },
        )
    }

    if (showDelete) {
        var scopeChoice by remember { mutableStateOf("series") }
        val repeating = task.recurrence != null || task.repeatType != 0
        AlertDialog(
            onDismissRequest = { onDeleteChange(false) },
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
                        onDeleteChange(false); onDeleted()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { onDeleteChange(false) }) { Text("取消") } },
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
