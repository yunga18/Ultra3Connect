package com.yunga.ultra3connect

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * System-granted read access is broad. We strictly forward only explicitly
 * enabled app categories, and never persist or log original notification text.
 */
class WatchNotificationListener : NotificationListenerService() {
    private val recent = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val preferences = WatchNotificationSettings(this)
        if (!preferences.enabled()) return

        val source = WatchNotificationSources.fromPackage(sbn.packageName) ?: return
        if (!preferences.allowed(source.key)) return

        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.visibility == Notification.VISIBILITY_SECRET) return
        if (notification.category == Notification.CATEGORY_SERVICE ||
            notification.category == Notification.CATEGORY_PROGRESS) return

        val extras = notification.extras ?: return
        val body = (
            extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            )?.toString()?.trim() ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim().orEmpty()

        val text = if (title.isNotBlank() && title != body) "$title: $body" else body
        if (!NotificationPrivacyFilter.canForward(text)) return

        // Suppress duplicate posts of identical message content for one system key.
        val now = android.os.SystemClock.elapsedRealtime()
        val signature = sbn.key + ":" + text.hashCode()
        val last = recent[signature]
        if (last != null && now - last in 0..60000L) return
        recent[signature] = now
        while (recent.size > 100) recent.remove(recent.keys.first())

        WatchNotificationBridge.receiver?.invoke(
            IncomingWatchNotice(source.type, source.name, text)
        )
    }
}
