package com.ravy.assistant.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class RavyNotification(
    val appPackage: String,
    val title: String,
    val text: String,
    val postedAt: Long
)

/**
 * Real NotificationListenerService. Keeps only the most recent notifications
 * in memory (not persisted, not sent anywhere) so Ravy can summarize counts
 * and senders on request — matching the "report counts/senders first, only
 * read full content when explicitly asked" rule from the spec.
 *
 * The user must enable this manually — Settings > Apps > Special app access
 * > Notification access > Ravy > Allow. MainActivity's button opens that
 * screen directly; Ravy cannot grant this to itself.
 */
class RavyNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        RavyNotificationStore.isConnected = true
    }

    override fun onListenerDisconnected() {
        RavyNotificationStore.isConnected = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        RavyNotificationStore.add(
            RavyNotification(
                appPackage = sbn.packageName,
                title = title,
                text = text,
                postedAt = sbn.postTime
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No action needed for this scaffold.
    }
}

/** In-memory only. Cleared on process death — nothing is persisted or transmitted. */
object RavyNotificationStore {
    var isConnected: Boolean = false
    private val recent = mutableListOf<RavyNotification>()

    @Synchronized
    fun add(n: RavyNotification) {
        recent.add(0, n)
        while (recent.size > 50) recent.removeAt(recent.size - 1)
    }

    @Synchronized
    fun snapshot(): List<RavyNotification> = recent.toList()
}
