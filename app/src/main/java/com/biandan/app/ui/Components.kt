package com.biandan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biandan.app.data.OccurrenceView
import com.biandan.app.data.TaskView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String?, onChange: (String?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Text(
        text = value ?: label,
        modifier = modifier.clickable { open = true }.padding(8.dp),
        color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
    )
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = runCatching { LocalDate.parse(value ?: "").toEpochDay() * 86400000L }.getOrNull()
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(LocalDate.ofEpochDay(it / 86400000L).format(FMT)) }
                    open = false
                }) { Text("确定") }
            },
            dismissButton = {
                Row {
                    if (value != null) TextButton(onClick = { onChange(null); open = false }) { Text("清除") }
                    TextButton(onClick = { open = false }) { Text("取消") }
                }
            },
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(label: String, value: String?, onChange: (String?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Text(
        text = value ?: label,
        modifier = modifier.clickable { open = true }.padding(8.dp),
        color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
    )
    if (open) {
        val parts = value?.split(":")
        val state = rememberTimePickerState(
            initialHour = parts?.getOrNull(0)?.toIntOrNull() ?: 9,
            initialMinute = parts?.getOrNull(1)?.toIntOrNull() ?: 0,
        )
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(String.format(Locale.US, "%02d:%02d:00", state.hour, state.minute))
                    open = false
                }) { Text("确定") }
            },
            dismissButton = {
                Row {
                    if (value != null) TextButton(onClick = { onChange(null); open = false }) { Text("清除") }
                    TextButton(onClick = { open = false }) { Text("取消") }
                }
            },
            text = { TimePicker(state) },
        )
    }
}

/** Todoist 式圆形勾选框：待办=空心圆，完成=实心+对勾，放弃=灰色对勾。 */
@Composable
fun CircleCheck(done: Boolean, abandoned: Boolean = false, onClick: () -> Unit) {
    val border = when {
        done -> MaterialTheme.colorScheme.primary
        abandoned -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (done || abandoned) border else Color.Transparent)
            .border(1.5.dp, border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (done || abandoned) {
            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
fun MetaIcon(icon: ImageVector, text: String, color: Color = MaterialTheme.colorScheme.outline) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** 实例行：圆卡 + 圆勾 + 标题 + 元数据行（Todoist/ClickUp 式）。 */
@Composable
fun OccurrenceRow(
    occ: OccurrenceView,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onCheck: (() -> Unit)? = null,
    listName: String = "",
    listColor: Int = 0,
) {
    val done = occ.status == "completed"
    val abandoned = occ.status == "abandoned"
    val timed = occ.startTime != "00:00:00" || occ.endTime != "23:59:00"
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        onClick = onClick,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleCheck(done, abandoned, onClick = onToggle)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    occ.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = when {
                        done -> MaterialTheme.colorScheme.outline
                        abandoned -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                val meta = buildList<@Composable () -> Unit> {
                    if (timed && occ.startTime != null && occ.endTime != null) {
                        add { MetaIcon(Icons.Default.DateRange, "${occ.startTime.take(5)}–${occ.endTime.take(5)}") }
                    }
                    if (listName.isNotEmpty()) {
                        add {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(ListPalette.color(listColor)))
                                Spacer(Modifier.width(4.dp))
                                Text(listName, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    if (occ.hasTimeReminder) {
                        add { MetaIcon(Icons.Default.Notifications, "提醒") }
                    }
                    if (occ.hasLocationReminder) {
                        add { MetaIcon(Icons.Default.Place, "地点") }
                    }
                }
                if (meta.isNotEmpty() || occ.isHabit || occ.targetCount > 1 || occ.completedSubtaskIndices.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        meta.forEach { it() }
                        if (occ.important) Text("重要", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        if (occ.urgent) Text("紧急", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        if (occ.isHabit) Text("习惯", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        if (occ.targetCount > 1 || occ.completedSubtaskIndices.isNotEmpty()) {
                            Text(
                                "${occ.completedCount}/${maxOf(occ.targetCount, occ.subtaskTitles.size)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            if (onCheck != null && !done && !abandoned) {
                TextButton(onClick = onCheck) { Text("打卡") }
            }
        }
    }
}

/** 分区标题（带计数）。 */
@Composable
fun SectionHeader(text: String, count: Int? = null, color: Color = MaterialTheme.colorScheme.primary) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.outline)
    }
}

/** 调色板：默认 + 三组色系 */
@Composable
fun ColorPalettePicker(selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("默认")
            ColorDot(ListPalette.color(0), selected == 0) { onPick(0) }
        }
        for ((name, range) in ListPalette.groups) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, modifier = Modifier.width(48.dp))
                for (i in range) ColorDot(ListPalette.color(i), selected == i) { onPick(i) }
            }
        }
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (selected) 28.dp else 22.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
    }
}

/** 任务行（搜索/列表页）：卡式 + 标题 + 元数据行。 */
@Composable
fun TaskRow(task: TaskView, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        onClick = onClick,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                color = if (task.completed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val window = buildString {
                    if (task.undated) append("无日期")
                    else {
                        task.startDate?.let { append(it) }
                        if (task.endDate != null) append(" → ${task.endDate}")
                    }
                }
                MetaIcon(Icons.Default.DateRange, window)
                if (task.recurrence != null) {
                    MetaIcon(Icons.Default.Refresh, describeRule(task.recurrence), MaterialTheme.colorScheme.primary)
                }
                if (task.isHabit) Text("习惯", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                if (task.important) Text("重要", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                if (task.completed) Text("已完成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun FilterChipsRow(options: List<Pair<String, Boolean>>, onToggle: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, (label, sel) ->
            FilterChip(selected = sel, onClick = { onToggle(i) }, label = { Text(label) })
        }
    }
}
