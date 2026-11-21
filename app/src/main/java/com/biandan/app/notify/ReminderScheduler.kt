package com.biandan.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.biandan.app.data.Repository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 时间提醒：为未来 N 天内 isRemind 位 0 开启的待办排 AlarmManager。
 * 触发时由 ReminderReceiver 发通知。实际调度受设备电量策略影响。
 */
class ReminderScheduler(private val context: Context) {
    private val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs = context.getSharedPreferences("biandan-reminders", Context.MODE_PRIVATE)

    suspend fun reschedule(repo: Repository, days: Int = 7) {
        val today = LocalDate.now()
        val horizon = today.plusDays(days.toLong())
        val tasks = repo.searchTasks(limit = 100).items
        val wanted = mutableMapOf<String, Pair<String, Long>>()
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        for (task in tasks) {
            val reminder = task.timeReminder ?: continue
            if (task.completed) continue
            val at = runCatching {
                LocalDateTime.parse("${reminder.date} ${reminder.time}", fmt)
                    .atZone(ZoneId.systemDefault()).toEpochSecond() * 1000
            }.getOrNull() ?: continue
            val day = runCatching { LocalDate.parse(reminder.date) }.getOrNull() ?: continue
            if (day < today || day > horizon) continue
            if (at <= System.currentTimeMillis()) continue
            wanted[task.taskId] = task.title to at
        }
        // 撤销不再需要的旧闹铃
        val old = prefs.all.keys.filter { it.startsWith("t") }.toSet()
        for (key in old) {
            val id = key.removePrefix("t")
            if (id !in wanted) {
                alarm.cancel(pending(id))
                prefs.edit().remove(key).apply()
            }
        }
        for ((id, pair) in wanted) {
            if (prefs.getLong("t$id", 0L) == pair.second) continue
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, pair.second, pending(id, pair.first))
            prefs.edit().putLong("t$id", pair.second).apply()
        }
    }

    private fun pending(taskId: String, title: String = ""): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("task_id", taskId)
            putExtra("title", title)
        }
        return PendingIntent.getBroadcast(
            context, taskId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
