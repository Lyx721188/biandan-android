package com.biandan.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.biandan.app.data.AppStore
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(
    Tab("today", "今日", Icons.Default.DateRange),
    Tab("tasks", "任务", Icons.Default.Star),
    Tab("more", "更多", Icons.Default.Menu),
)

// 二级页面：从「更多」进入，底栏保持可见且高亮「更多」
private val SECONDARY_ROUTES = setOf("lists", "habits", "focus", "settings")

@Composable
fun AppRoot(store: AppStore) {
    val signedIn by store.signedIn.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val snackMsg by store.snackbar.collectAsState()
    LaunchedEffect(snackMsg) {
        snackMsg?.let {
            snackbar.showSnackbar(it)
            store.snackbar.value = null
        }
    }

    if (!signedIn) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(pad)) {
                LoginScreen(store)
            }
        }
        return
    }

    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    LaunchedEffect(Unit) { scope.launch { store.run { store.refresh() } } }

    fun openOccurrence(id: String) = nav.navigate("occ/$id")
    fun openTask(taskId: String) {
        scope.launch {
            // 任务 → 实例：优先取最近的待办实例，否则任意一个
            val page = store.run {
                store.repo.listOccurrences(
                    startDate = "1900-01-31", endDate = "2100-12-31",
                    taskId = taskId, status = "all", includeUndated = true, limit = 100,
                )
            }
            val target = page?.items?.firstOrNull { it.status == "pending" } ?: page?.items?.firstOrNull()
            if (target != null) nav.navigate("occ/${target.occurrenceId}")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (currentRoute in TABS.map { it.route } + SECONDARY_ROUTES) {
                NavigationBar {
                    for (tab in TABS) {
                        NavigationBarItem(
                            selected = currentRoute == tab.route ||
                                (tab.route == "more" && currentRoute in SECONDARY_ROUTES),
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(
            navController = nav,
            startDestination = "today",
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            composable("today") { TodayScreen(store, ::openOccurrence) { nav.navigate("new") } }
            composable("tasks") { TasksScreen(store, ::openTask) { nav.navigate("new") } }
            composable("more") { MoreScreen { route -> nav.navigate(route) } }
            composable("lists") { ListsScreen(store) { nav.navigate("list/$it") } }
            composable("habits") { HabitsScreen(store, ::openTask) }
            composable("focus") { FocusScreen(store) }
            composable("settings") { SettingsScreen(store) }
            composable("new") {
                EditTaskScreen(store,
                    onDone = { nav.popBackStack() },
                    onCancel = { nav.popBackStack() })
            }
            composable(
                "occ/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                TaskDetailScreen(
                    store,
                    occurrenceId = entry.arguments?.getString("id") ?: "",
                    onBack = { nav.popBackStack() },
                    onDeleted = { nav.popBackStack() },
                )
            }
            composable(
                "list/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                ListDetailScreen(
                    store,
                    listId = entry.arguments?.getString("id") ?: "1",
                    onOpenOccurrence = ::openOccurrence,
                )
            }
        }
    }
}
