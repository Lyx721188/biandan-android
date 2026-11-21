package com.biandan.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 应用级状态容器：授权、云端、业务仓库、刷新与错误提示。 */
class AppStore(context: Context) {
    val auth = AuthManager(context.applicationContext)
    val cloud = BiandanCloud(auth)
    val repo = Repository(cloud)

    val signedIn = MutableStateFlow(auth.isSignedIn)
    val syncing = MutableStateFlow(false)
    val snackbar = MutableStateFlow<String?>(null)
    val lists = MutableStateFlow<List<ListView>>(emptyList())

    // 应用设置（对应原生端的一周起点与调休选项）
    private val prefs = context.applicationContext
        .getSharedPreferences("biandan-settings", Context.MODE_PRIVATE)

    var weekStart: Int
        get() = prefs.getInt("week_start", 0)
        set(v) = prefs.edit().putInt("week_start", v).apply()

    var chinaMakeup: Boolean
        get() = prefs.getBoolean("china_makeup", true)
        set(v) = prefs.edit().putBoolean("china_makeup", v).apply()

    private val refreshLock = Mutex()

    suspend fun refresh() {
        if (syncing.value) return
        refreshLock.withLock {
            if (syncing.value) return
            syncing.value = true
            try {
                repo.refreshAll()
                lists.value = repo.listLists()
            } finally {
                syncing.value = false
            }
        }
    }

    suspend fun <T> run(action: suspend () -> T): T? = try {
        action()
    } catch (e: ReauthRequired) {
        signedIn.value = false
        snackbar.value = e.message ?: "需要重新授权"
        null
    } catch (e: CloudError) {
        snackbar.value = e.message ?: "云端请求失败"
        null
    } catch (e: ToolError) {
        snackbar.value = e.message ?: "操作失败"
        null
    } catch (e: Exception) {
        snackbar.value = "操作失败：${e.message}"
        null
    }
}
