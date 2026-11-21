package com.biandan.app

import android.app.Application
import com.biandan.app.data.AppStore
import com.biandan.app.notify.ReminderScheduler

class BiandanApp : Application() {
    lateinit var store: AppStore
        private set
    lateinit var reminders: ReminderScheduler
        private set

    override fun onCreate() {
        super.onCreate()
        store = AppStore(this)
        reminders = ReminderScheduler(this)
    }
}
