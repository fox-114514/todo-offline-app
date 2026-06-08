package com.example.todooffline

import android.app.Application
import com.example.todooffline.reminder.ReminderScheduler
import com.example.todooffline.sync.SyncScheduler

class TodoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncScheduler.schedulePeriodic(this)
        ReminderScheduler.scheduleNext(this)
    }
}
