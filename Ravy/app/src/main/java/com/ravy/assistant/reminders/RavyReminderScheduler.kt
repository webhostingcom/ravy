package com.ravy.assistant.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Schedules real Android alarms via AlarmManager — this is what actually
 * fires at the requested time, independent of whether the app is open.
 * Uses setExactAndAllowWhileIdle so it still fires in Doze; on Android 12+
 * this requires the user to have granted "Alarms & reminders" special
 * access (SCHEDULE_EXACT_ALARM) — if not granted, scheduling honestly fails
 * rather than silently not firing later.
 */
object RavyReminderScheduler {

    private fun pendingIntentFor(context: Context, reminder: RavyReminder): PendingIntent {
        val intent = Intent(context, RavyAlarmReceiver::class.java).apply {
            putExtra("reminder_id", reminder.id)
            putExtra("label", reminder.label)
        }
        return PendingIntent.getBroadcast(
            context, reminder.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun computeNextTrigger(hour: Int, minute: Int, repeat: RepeatType, from: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 1)
        if (repeat == RepeatType.WEEKDAYS) {
            while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    /** Returns true if actually scheduled, false if the OS refused (e.g. exact-alarm permission not granted). */
    fun schedule(context: Context, reminder: RavyReminder): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.nextTriggerMillis, pendingIntentFor(context, reminder))
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun cancel(context: Context, reminder: RavyReminder) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntentFor(context, reminder))
    }

    /** Android 12+ only: whether this app currently has permission to schedule exact alarms. */
    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else true
    }
}
