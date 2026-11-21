package com.biandan.app.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biandan.app.data.AppStore
import com.biandan.app.data.FocusView
import com.biandan.app.data.ListView
import com.biandan.app.data.OccurrenceView
import com.biandan.app.data.Repository
import com.biandan.app.data.TaskView
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------- 登录 ----------

@Composable
fun LoginScreen(store: AppStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("便单", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("轻计划，重执行", color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(48.dp))
        if (busy) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("请在浏览器中完成华为授权…", color = MaterialTheme.colorScheme.outline)
        } else {
            Button(onClick = {
                busy = true
                scope.launch {
                    store.run { store.auth.signIn(context as Activity) }
                    busy = false
                    if (store.auth.isSignedIn) {
                        store.signedIn.value = true
                        store.run { store.refresh() }
                    }
                }
            }) { Text("使用华为账号授权登录") }
            Spacer(Modifier.height(12.dp))
            Text(
                "登录态走便单官方 OAuth，仅保存在本机",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// ---------- 今日（周条 + 当日实例） ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(store: AppStore, onOpenOccurrence: (String) -> Unit, onAdd: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var items by remember { mutableStateOf<List<OccurrenceView>>(emptyList()) }
    var overdue by remember { mutableStateOf<List<OccurrenceView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val syncing by store.syncing.collectAsState()
    val lists by store.lists.collectAsState()
    val listMeta = lists.associate { it.listId to it }

    fun load() {
        scope.launch {
            loading = true
            val day = selected.toString()
            val page = store.run {
                store.repo.listOccurrences(
                    startDate = day, endDate = day, status = "all",
                    limit = 100, includeSummary = false,
                )
            }
            items = page?.items.orEmpty()
            if (selected == LocalDate.now()) {
                val ov = store.run {
                    store.repo.listOccurrences(
                        startDate = "1900-01-31", endDate = LocalDate.now().minusDays(1).toString(),
                        status = "pending", limit = 20,
                    )
                }
                overdue = ov?.items.orEmpty()
            } else overdue = emptyList()
            loading = false
        }
    }
    LaunchedEffect(selected, syncing) { if (!syncing) load() }

    val pending = items.filter { it.status == "pending" }
    val finished = items.filter { it.status != "pending" }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = syncing, onRefresh = {
            scope.launch { store.run { store.refresh() } }
        }) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            if (selected == LocalDate.now()) "今天" else
                                selected.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            selected.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    WeekStrip(selected) { selected = it }
                }
                if (overdue.isNotEmpty()) {
                    item { SectionHeader("已逾期", overdue.size, MaterialTheme.colorScheme.error) }
                    items(overdue, key = { "o" + it.occurrenceId }) { occ ->
                        OccurrenceRow(occ,
                            onClick = { onOpenOccurrence(occ.occurrenceId) },
                            onToggle = {
                                scope.launch {
                                    store.run {
                                        if (occ.status == "pending") store.repo.completeOccurrence(occ.occurrenceId)
                                        else store.repo.changeProgress(occ.occurrenceId, "reopen")
                                    }; load()
                                }
                            },
                            listName = listMeta[occ.listId]?.name ?: "",
                            listColor = listMeta[occ.listId]?.color ?: 0)
                    }
                }
                item { SectionHeader("待办", pending.size) }
                if (loading) {
                    item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                } else if (items.isEmpty()) {
                    item { EmptyHint("这一天没有安排") }
                } else {
                    items(pending, key = { it.occurrenceId }) { occ ->
                        OccurrenceRow(occ,
                            onClick = { onOpenOccurrence(occ.occurrenceId) },
                            onToggle = {
                                scope.launch {
                                    store.run {
                                        if (occ.status == "pending") store.repo.completeOccurrence(occ.occurrenceId)
                                        else store.repo.changeProgress(occ.occurrenceId, "reopen")
                                    }; load()
                                }
                            },
                            listName = listMeta[occ.listId]?.name ?: "",
                            listColor = listMeta[occ.listId]?.color ?: 0)
                    }
                    if (finished.isNotEmpty()) {
                        item { SectionHeader("已完成", finished.size, MaterialTheme.colorScheme.outline) }
                        items(finished, key = { "d" + it.occurrenceId }) { occ ->
                            OccurrenceRow(occ,
                                onClick = { onOpenOccurrence(occ.occurrenceId) },
                                onToggle = {
                                    scope.launch {
                                        store.run { store.repo.changeProgress(occ.occurrenceId, "reopen") }
                                        load()
                                    }
                                },
                                listName = listMeta[occ.listId]?.name ?: "",
                                listColor = listMeta[occ.listId]?.color ?: 0)
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, contentDescription = "新建") }
    }
}

@Composable
fun WeekStrip(selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val days = (-30L..30L).map { today.plusDays(it) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(days) { day ->
            val sel = day == selected
            val isToday = day == today
            Column(
                modifier = Modifier
                    .width(44.dp)
                    .clickable { onSelect(day) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    day.format(DateTimeFormatter.ofPattern("E", Locale.CHINA)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                sel -> MaterialTheme.colorScheme.primary
                                isToday -> MaterialTheme.colorScheme.primaryContainer
                                else -> Color.Transparent
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        day.dayOfMonth.toString(),
                        fontWeight = if (sel || isToday) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            sel -> MaterialTheme.colorScheme.onPrimary
                            isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

// ---------- 任务搜索/浏览 ----------

@Composable
fun TasksScreen(store: AppStore, onOpenTask: (String) -> Unit, onAdd: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filterIndex by remember { mutableIntStateOf(0) }
    var listId by remember { mutableStateOf<String?>(null) }
    var showDone by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<Repository.Page<TaskView>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val lists by store.lists.collectAsState()
    val syncing by store.syncing.collectAsState()

    val filters = listOf("全部", "重要", "紧急", "循环", "习惯", "收藏")
    fun load() {
        scope.launch {
            val result = store.run {
                store.repo.searchTasks(
                    query = query, listId = listId,
                    important = if (filterIndex == 1) true else null,
                    urgent = if (filterIndex == 2) true else null,
                    repeating = if (filterIndex == 3) true else null,
                    habit = if (filterIndex == 4) true else null,
                    collected = if (filterIndex == 5) true else null,
                    completed = if (showDone) null else false,
                    limit = 100,
                )
            }
            if (result != null) { page = result; failed = false }
            else failed = true
        }
    }
    LaunchedEffect(query, filterIndex, listId, showDone, syncing) { if (!syncing) load() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索任务、备注、子任务") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filters.size) { i ->
                    androidx.compose.material3.FilterChip(
                        selected = filterIndex == i,
                        onClick = { filterIndex = i },
                        label = { Text(filters[i]) },
                    )
                }
                item {
                    androidx.compose.material3.FilterChip(
                        selected = showDone, onClick = { showDone = !showDone },
                        label = { Text("含已完成") },
                    )
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    androidx.compose.material3.FilterChip(
                        selected = listId == null, onClick = { listId = null },
                        label = { Text("全部清单") },
                    )
                }
                items(lists) { l ->
                    androidx.compose.material3.FilterChip(
                        selected = listId == l.listId, onClick = { listId = l.listId },
                        label = { Text(l.name) },
                    )
                }
            }
            val p = page
            LazyColumn(Modifier.fillMaxSize()) {
                if (p == null && !failed) {
                    item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                } else if (failed) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            EmptyHint("加载失败，请检查网络后重试")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { failed = false; page = null; load() }) { Text("重试") }
                        }
                    }
                } else if (p == null || p.items.isEmpty()) {
                    item { EmptyHint("没有匹配的任务") }
                } else {
                    items(p.items, key = { it.taskId }) { task ->
                        TaskRow(task, onClick = { onOpenTask(task.taskId) })
                    }
                }
            }
        }
        FloatingActionButton(onClick = onAdd, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = "新建")
        }
    }
}

// ---------- 清单管理 ----------

@Composable
fun ListsScreen(store: AppStore, onOpenList: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val lists by store.lists.collectAsState()
    val syncing by store.syncing.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ListView?>(null) }
    var deleting by remember { mutableStateOf<ListView?>(null) }

    LaunchedEffect(syncing) { if (!syncing) store.run { store.lists.value = store.repo.listLists() } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(lists, key = { it.listId }) { list ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenList(list.listId) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(14.dp).padding(1.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = ListPalette.color(list.color))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(list.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (list.listId != "1") {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, null)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("重命名/配色") }, onClick = { editing = list; menu = false },
                                leadingIcon = { Icon(Icons.Default.Edit, null) })
                            DropdownMenuItem(text = { Text("删除") }, onClick = { deleting = list; menu = false },
                                leadingIcon = { Icon(Icons.Default.Delete, null) })
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        FloatingActionButton(onClick = { showCreate = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = "新建清单")
        }
    }

    if (showCreate || editing != null) {
        var name by remember(showCreate, editing) { mutableStateOf(editing?.name ?: "") }
        var color by remember(showCreate, editing) { mutableIntStateOf(editing?.color ?: 0) }
        AlertDialog(
            onDismissRequest = { showCreate = false; editing = null },
            title = { Text(if (editing == null) "新建清单" else "编辑清单") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("名称") }, singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    ColorPalettePicker(color) { color = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = editing
                    scope.launch {
                        store.run {
                            if (target == null) store.repo.createList(name, color)
                            else store.repo.updateList(target.listId, name.ifBlank { null }, color)
                        }
                        store.run { store.lists.value = store.repo.listLists() }
                    }
                    showCreate = false; editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false; editing = null }) { Text("取消") } },
        )
    }

    deleting?.let { list ->
        var deleteTasks by remember { mutableStateOf(false) }
        var preview by remember { mutableStateOf<Repository.DeleteListResult?>(null) }
        LaunchedEffect(list) {
            preview = store.run { store.repo.deleteList(list.listId, preview = true) }
        }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除清单「${list.name}」") },
            text = {
                Column {
                    Text("包含 ${preview?.affectedTasks?.size ?: "…"} 个任务")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = deleteTasks, onCheckedChange = { deleteTasks = it })
                        Spacer(Modifier.width(8.dp))
                        Text(if (deleteTasks) "同时删除其中的任务" else "任务移入「未分类」")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        store.run {
                            store.repo.deleteList(list.listId,
                                tasks = if (deleteTasks) "delete_tasks" else "move_to_default")
                        }
                        store.run { store.refresh() }
                    }
                    deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

// ---------- 清单详情（按清单过滤的实例） ----------

@Composable
fun ListDetailScreen(store: AppStore, listId: String, onOpenOccurrence: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<OccurrenceView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val lists by store.lists.collectAsState()
    val list = lists.firstOrNull { it.listId == listId }
    val syncing by store.syncing.collectAsState()

    LaunchedEffect(listId, syncing) {
        if (syncing) return@LaunchedEffect
        loading = true
        val page = store.run {
            store.repo.listOccurrences(
                startDate = "1900-01-31", endDate = "2100-12-31", listId = listId,
                status = "all", includeUndated = true, limit = 100,
            )
        }
        items = page?.items.orEmpty()
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            list?.name ?: "清单 $listId",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = ListPalette.color(list?.color ?: 0),
        )
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else LazyColumn {
            if (items.isEmpty()) item { EmptyHint("清单是空的") }
            items(items, key = { it.occurrenceId }) { occ ->
                OccurrenceRow(occ, onClick = { onOpenOccurrence(occ.occurrenceId) },
                    onToggle = {
                        scope.launch {
                            store.run {
                                if (occ.status == "pending") store.repo.completeOccurrence(occ.occurrenceId)
                                else store.repo.changeProgress(occ.occurrenceId, "reopen")
                            }
                        }
                    })
            }
        }
    }
}

// ---------- 习惯 ----------

@Composable
fun HabitsScreen(store: AppStore, onOpenOccurrence: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var todayItems by remember { mutableStateOf<List<OccurrenceView>>(emptyList()) }
    var habits by remember { mutableStateOf<List<TaskView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val syncing by store.syncing.collectAsState()

    fun load() {
        scope.launch {
            loading = true
            val today = LocalDate.now().toString()
            val occ = store.run {
                store.repo.listOccurrences(startDate = today, endDate = today, status = "all", habit = true, limit = 100)
            }
            todayItems = occ?.items.orEmpty()
            val t = store.run { store.repo.searchTasks(habit = true, completed = false, limit = 100) }
            habits = t?.items.orEmpty()
            loading = false
        }
    }
    LaunchedEffect(syncing) { if (!syncing) load() }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("今日打卡") }
        if (loading) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else if (todayItems.isEmpty()) {
            item { EmptyHint("今天没有需要打卡的习惯") }
        } else {
            items(todayItems, key = { it.occurrenceId }) { occ ->
                OccurrenceRow(occ,
                    onClick = { onOpenOccurrence(occ.occurrenceId) },
                    onToggle = {
                        scope.launch {
                            store.run {
                                if (occ.status == "pending") store.repo.completeOccurrence(occ.occurrenceId)
                                else store.repo.changeProgress(occ.occurrenceId, "reopen")
                            }; load()
                        }
                    },
                    onCheck = if (occ.status == "pending" && occ.targetCount > 1) {
                        {
                            scope.launch {
                                store.run {
                                    store.repo.changeProgress(occ.occurrenceId, "set_count",
                                        count = occ.completedCount + 1)
                                }; load()
                            }
                        }
                    } else null,
                )
            }
        }
        item { SectionHeader("全部习惯 ${habits.size}") }
        items(habits, key = { it.taskId }) { task -> TaskRow(task, onClick = { onOpenOccurrence(task.taskId) }) }
    }
}

// ---------- 专注 ----------

@Composable
fun FocusScreen(store: AppStore) {
    val scope = rememberCoroutineScope()
    var range by remember { mutableIntStateOf(0) } // 0 今日 1 近7天 2 近30天
    var page by remember { mutableStateOf<Repository.FocusPage?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var timerOn by remember { mutableStateOf(false) }
    var timerTitle by remember { mutableStateOf("") }
    var startAt by remember { mutableStateOf<java.time.LocalDateTime?>(null) }
    val syncing by store.syncing.collectAsState()

    fun load() {
        scope.launch {
            val (s, e) = when (range) {
                0 -> LocalDate.now().toString() to LocalDate.now().toString()
                1 -> LocalDate.now().minusDays(6).toString() to LocalDate.now().toString()
                else -> LocalDate.now().minusDays(29).toString() to LocalDate.now().toString()
            }
            page = store.run {
                store.repo.listFocus(startDate = s, endDate = e, limit = 100, includeSummary = true)
            }
        }
    }
    LaunchedEffect(range, syncing) { if (!syncing) load() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("今日", "近7天", "近30天").forEachIndexed { i, label ->
                        androidx.compose.material3.FilterChip(selected = range == i, onClick = { range = i },
                            label = { Text(label) })
                    }
                }
            }
            item {
                val s = page?.summary
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column { Text("${(s?.get("minutes")?.toInt() ?: 0)}", style = MaterialTheme.typography.headlineMedium); Text("分钟", style = MaterialTheme.typography.labelSmall) }
                        Column { Text("${s?.get("good_count") ?: 0}", style = MaterialTheme.typography.headlineMedium); Text("专注", style = MaterialTheme.typography.labelSmall) }
                        Column { Text("${s?.get("bad_count") ?: 0}", style = MaterialTheme.typography.headlineMedium); Text("中断", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            if (timerOn) {
                item {
                    Card(Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("专注中…", fontWeight = FontWeight.Bold)
                            Text("开始于 ${startAt?.toLocalTime()?.withNano(0) ?: ""}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    val st = startAt!!
                                    val mins = java.time.Duration.between(st, java.time.LocalDateTime.now()).toMinutes()
                                    scope.launch {
                                        store.run {
                                            store.repo.createFocus(st.toString().take(16),
                                                mins.coerceIn(1, 1440).toDouble(), timerTitle, goodCount = 1)
                                        }; load()
                                    }
                                    timerOn = false
                                }) { Text("完成") }
                                TextButton(onClick = {
                                    val st = startAt!!
                                    val mins = java.time.Duration.between(st, java.time.LocalDateTime.now()).toMinutes()
                                    scope.launch {
                                        store.run {
                                            store.repo.createFocus(st.toString().take(16),
                                                mins.coerceIn(1, 1440).toDouble(), timerTitle, badCount = 1)
                                        }; load()
                                    }
                                    timerOn = false
                                }) { Text("中断") }
                                TextButton(onClick = { timerOn = false }) { Text("丢弃") }
                            }
                        }
                    }
                }
            }
            val items = page?.items.orEmpty()
            if (items.isEmpty()) {
                item { EmptyHint("还没有专注记录") }
            } else {
                items(items, key = { it.focusId }) { f ->
                    FocusRow(f, onDelete = {
                        scope.launch { store.run { store.repo.deleteFocus(f.focusId) }; load() }
                    })
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End) {
            FloatingActionButton(onClick = {
                timerTitle = ""; startAt = java.time.LocalDateTime.now(); timerOn = true
            }, modifier = Modifier.padding(bottom = 8.dp)) { Text("▶") }
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "补记") }
        }
    }

    if (showAdd) {
        var title by remember { mutableStateOf("") }
        var mins by remember { mutableStateOf("25") }
        var good by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("补记专注") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(mins, { mins = it.filter(Char::isDigit) }, label = { Text("时长（分钟 1-1440）") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(good, { good = it }); Spacer(Modifier.width(8.dp))
                        Text(if (good) "完成" else "中断")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        store.run {
                            store.repo.createFocus(
                                java.time.LocalDateTime.now().toString().take(16),
                                mins.toDoubleOrNull() ?: 0.0, title,
                                goodCount = if (good) 1 else 0, badCount = if (good) 0 else 1,
                            )
                        }; load()
                    }
                    showAdd = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun FocusRow(f: FocusView, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(f.title.ifEmpty { "专注" }, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${f.date} · ${f.minutes.toInt()} 分钟 · 好${f.goodCount}/坏${f.badCount}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("删除") }, onClick = { onDelete(); menu = false },
                leadingIcon = { Icon(Icons.Default.Delete, null) })
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

// ---------- 更多（二级入口） ----------

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    data class Entry(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val desc: String)
    val entries = listOf(
        Entry("lists", "清单", Icons.Default.List, "管理任务清单与配色"),
        Entry("habits", "习惯", Icons.Default.CheckCircle, "每日打卡与坚持记录"),
        Entry("focus", "专注", Icons.Default.PlayArrow, "番茄钟与专注日志"),
        Entry("settings", "设置", Icons.Default.Settings, "账号、排程与同步"),
    )
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("更多", Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        items(entries) { e ->
            Row(
                Modifier.fillMaxWidth().clickable { onNavigate(e.route) }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(e.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(e.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

// ---------- 设置 ----------

@Composable
fun SettingsScreen(store: AppStore) {
    val scope = rememberCoroutineScope()
    var weekStart by remember { mutableIntStateOf(store.weekStart) }
    var makeup by remember { mutableStateOf(store.chinaMakeup) }
    var connected by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) { connected = store.run { store.repo.checkConnection() } ?: false }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("账号", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("登录状态：已授权", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "云端连接：" + when (connected) {
                            null -> "检测中…"
                            true -> "正常"
                            false -> "失败"
                        },
                        color = if (connected == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        scope.launch {
                            store.auth.signOut()
                            store.signedIn.value = false
                        }
                    }) { Text("退出授权", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            Text("排程", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("一周从周一开始", Modifier.weight(1f))
                        Switch(weekStart == 1, {
                            weekStart = if (it) 1 else 0; store.weekStart = weekStart
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("按中国调休计算工作日/休息日", Modifier.weight(1f))
                        Switch(makeup, { makeup = it; store.chinaMakeup = it })
                    }
                    Text("改动在下次创建/更新循环任务时生效", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        item {
            Text("同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card {
                Column(Modifier.padding(16.dp)) {
                    TextButton(onClick = { scope.launch { store.run { store.refresh() } } }) {
                        Text("立即同步云端数据")
                    }
                    Text("便单 v1.0 · 数据基于便单官方云接口", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
