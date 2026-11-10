package com.yunga.ultra3connect

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Bundle
import android.os.Parcelable
import android.util.Log

/**
 * Relays ONLY explicitly enabled app categories. Does not store raw messages or
 * titles in diagnostics. The system grants access to all notifications, but this
 * listener filters early and only forwards selected sources.
 */
class WatchNotificationListener : NotificationListenerService() {
    private val recent = LinkedHashMap<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        WatchNotificationBridge.report("Servicio de Android conectado y escuchando")
    }

    override fun onListenerDisconnected() {
        WatchNotificationBridge.report("Servicio de Android desconectado")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val source = WatchNotificationSources.fromPackage(sbn.packageName) ?: return
        val preferences = WatchNotificationSettings(this)

        // These messages are non-sensitive debug markers: no sender names or body.
        WatchNotificationBridge.report("Android detectó un aviso de " + source.name)
        if (!preferences.enabled()) {
            WatchNotificationBridge.report("Aviso omitido: reenvío general desactivado")
            return
        }
        if (!preferences.allowed(source.key)) {
            WatchNotificationBridge.report("Aviso omitido: " + source.name + " no está habilitado")
            return
        }

        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            WatchNotificationBridge.report("Aviso omitido: resumen de conversación")
            return
        }
        if (notification.visibility == Notification.VISIBILITY_SECRET) {
            WatchNotificationBridge.report("Aviso omitido: contenido marcado privado por Android")
            return
        }
        if (notification.category == Notification.CATEGORY_SERVICE ||
            notification.category == Notification.CATEGORY_PROGRESS) return

        val extras = notification.extras ?: return
        // WhatsApp may use MessagingStyle with an empty EXTRA_TEXT; try newest message.
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()?.trim().orEmpty()
            .ifBlank { extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty() }
            .ifBlank { latestConversationText(extras) }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim().orEmpty()
        if (body.isBlank()) {
            WatchNotificationBridge.report("Aviso omitido: Android no proporcionó texto")
            return
        }

        val combined = if (title.isNotBlank() && title != body) "$title: $body" else body
        if (!NotificationPrivacyFilter.canForward(combined)) {
            WatchNotificationBridge.report("Aviso omitido: filtro de privacidad")
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val signature = sbn.key + ":" + combined.hashCode()
        val last = recent[signature]
        if (last != null && now - last in 0..60000L) return
        recent[signature] = now
        while (recent.size > 100) recent.remove(recent.keys.first())

        if (WatchNotificationBridge.receiver == null) {
            WatchNotificationBridge.report("Aviso descartado: conexión de la app no está activa")
            return
        }
        WatchNotificationBridge.report("Aviso de " + source.name + " listo para enviar por BLE")
        WatchNotificationBridge.receiver?.invoke(
            IncomingWatchNotice(source.type, source.name, combined)
        )
    }

    private fun latestConversationText(extras: Bundle): String {
        return try {
            val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return ""
            Notification.MessagingStyle.Message
                .getMessagesFromBundleArray(raw)
                .lastOrNull()?.text?.toString()?.trim().orEmpty()
        } catch (_: Exception) { "" }
    }
}
