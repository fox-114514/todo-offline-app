package com.example.todooffline.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.todooffline.R
import com.example.todooffline.data.repo.TodoRepository

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val repository = TodoRepository(context)
        val settings = repository.reminderSettings()
        if (!settings.enabled) return

        val task = repository.localStore.randomIncompleteTask()
        if (task != null && canPostNotifications(context)) {
            ensureChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("可以做这个任务")
                .setContentText(task.title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(task.content.ifBlank { task.title }))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(task.id.hashCode(), notification)
        }
        ReminderScheduler.scheduleNext(context)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}

object ReminderScheduler {
    fun scheduleNext(context: Context) {
        val repository = TodoRepository(context)
        val settings = repository.reminderSettings()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = reminderIntent(context)
        if (!settings.enabled) {
            alarmManager.cancel(pendingIntent)
            return
        }

        val triggerAt = System.currentTimeMillis() + settings.frequencySeconds * 1000L
        runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }.onFailure {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun reminderIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(CHANNEL_ID, "任务提醒", NotificationManager.IMPORTANCE_DEFAULT)
    manager.createNotificationChannel(channel)
}

private const val CHANNEL_ID = "todo_reminders"
