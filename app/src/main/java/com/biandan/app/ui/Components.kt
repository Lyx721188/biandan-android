package com.biandan.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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

@Composable
fun OccurrenceRow(
    occ: OccurrenceView,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onCheck: (() -> Unit)? = null,
    listName: String = "",
    listColor: Int = 0,
) {
    val done = occ.status != "pending"
    val timed = occ.startTime != "00:00:00" || occ.endTime != "23:59:00"
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = done, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(
                occ.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (timed && occ.startTime != null && occ.endTime != null) {
                    Text(
                        "${occ.startTime.take(5)}–${occ.endTime.take(5)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (listName.isNotEmpty()) {
                    Text(listName, style = MaterialTheme.typography.bodySmall, color = ListPalette.color(listColor))
                }
                if (occ.important) {
                    Text("重要", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (occ.urgent) {
                    Text("紧急", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (occ.isHabit) {
                    Text("习惯", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                if (occ.targetCount > 1 || occ.completedSubtaskIndices.isNotEmpty()) {
                    Text(
                        "${occ.completedCount}/${maxOf(occ.targetCount, occ.subtaskTitles.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (onCheck != null && !done) {
            TextButton(onClick = onCheck) { Text("打卡") }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
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

/** 任务一行（列表/搜索结果页用） */
@Composable
fun TaskRow(task: TaskView, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val window = buildString {
                    if (task.startDate != null) append(task.startDate)
                    if (task.endDate != null) {
                        if (isNotEmpty()) append(" → ") else append("至 ")
                        append(task.endDate)
                    }
                }
                if (window.isNotEmpty()) {
                    Text(window, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                if (task.recurrence != null) {
                    Text(describeRule(task.recurrence), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (task.isHabit) {
                    Text("习惯", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (task.completed) {
                    Text("已完成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
fun FilterChipsRow(options: List<Pair<String, Boolean>>, onToggle: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, (label, sel) ->
            FilterChip(selected = sel, onClick = { onToggle(i) }, label = { Text(label) })
        }
    }
}
