package com.ravy.assistant.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Fires when a scheduled reminder/report time arrives. Posts a real
 * full-screen-intent notification — this is the legitimate Android
 * mechanism alarm/caller apps use to show a prominent screen over the lock
 * screen; it is NOT a cellular phone call, and nothing here claims it is.
 * Re-schedules the next occurrence for repeating reminders.
 */
class RavyAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "ravy_alerts"
        const val ACTION_DISMISS = "com.ravy.assistant.ACTION_DISMISS_ALERT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra("reminder_id") ?: return
        val label = intent.getStringExtra("label") ?: "Reminder"

        if (intent.action == ACTION_DISMISS) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(reminderId.hashCode())
            return
        }

        ensureChannel(context)

        val fullScreenIntent = Intent(context, IncomingRavyCallActivity::class.java).apply {
            putExtra("reminder_id", reminderId)
            putExtra("label", label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, reminderId.hashCode(), fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, RavyAlarmReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra("reminder_id", reminderId)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, reminderId.hashCode() + 1, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Ravy")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(android.R.drawable.ic_menu_call, "Answer", fullScreenPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(reminderId.hashCode(), notification)

        // Reschedule the next occurrence for repeating reminders.
        val reminder = RavyReminderStore.getAll(context).firstOrNull { it.id == reminderId }
        if (reminder != null && reminder.repeat != RepeatType.NONE && reminder.enabled) {
            val next = reminder.copy(
                nextTriggerMillis = RavyReminderScheduler.computeNextTrigger(reminder.hour, reminder.minute, reminder.repeat)
            )
            RavyReminderStore.update(context, next)
            RavyReminderScheduler.schedule(context, next)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, "Ravy alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Scheduled reminders and reports from Ravy"
                    setBypassDnd(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}
