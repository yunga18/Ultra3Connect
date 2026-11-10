package com.yunga.ultra3connect

import android.content.Context

/** Explicit opt-in, all app categories disabled until the owner enables them. */
class WatchNotificationSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ultra3_notification_consent_v1", Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean("enabled", false)
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply() }

    fun allowed(key: String): Boolean = prefs.getBoolean("allow_$key", false)
    fun setAllowed(key: String, allowed: Boolean) {
        prefs.edit().putBoolean("allow_$key", allowed).apply()
    }
}

data class WatchNotificationSource(
    val key: String,
    val name: String,
    val packages: Set<String>,
    val type: WatchNotificationProtocol.Type
)

object WatchNotificationSources {
    val supported = listOf(
        WatchNotificationSource("whatsapp", "WhatsApp",
            setOf("com.whatsapp", "com.whatsapp.w4b"),
            WatchNotificationProtocol.Type.WHATSAPP),
        WatchNotificationSource("telegram", "Telegram",
            setOf("org.telegram.messenger", "org.telegram.messenger.web"),
            WatchNotificationProtocol.Type.TELEGRAM),
        WatchNotificationSource("sms", "SMS / Mensajes",
            setOf("com.google.android.apps.messaging", "com.android.mms"),
            WatchNotificationProtocol.Type.SMS),
        WatchNotificationSource("twitter", "X / Twitter",
            setOf("com.twitter.android"),
            WatchNotificationProtocol.Type.TWITTER)
    )

    fun fromPackage(name: String): WatchNotificationSource? =
        supported.firstOrNull { name in it.packages }
}

data class IncomingWatchNotice(
    val type: WatchNotificationProtocol.Type,
    val source: String,
    val text: String
)

/**
 * In-memory only: no notification bodies are persisted.
 * The listener posts diagnostic milestones without sensitive content.
 */
object WatchNotificationBridge {
    @Volatile var receiver: ((IncomingWatchNotice) -> Unit)? = null
    @Volatile var diagnostic: ((String) -> Unit)? = null

    fun report(message: String) {
        diagnostic?.invoke(message)
    }
}

object NotificationPrivacyFilter {
    // Conservative default: skip likely verification codes or sensitive alerts.
    private val codePhrases = Regex(
        "(?i)(\\botp\\b|\\bpin\\b|\\bpassword\\b|\\bpasscode\\b|" +
            "\\bverification\\b|\\bverificaci[oó]n\\b|\\bc[oó]digo\\b|" +
            "\\bcontrase[nñ]a\\b|\\b2fa\\b|\\btoken\\b)"
    )
    private val shortNumericCode = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    fun canForward(text: String): Boolean =
        text.isNotBlank() && !codePhrases.containsMatchIn(text) &&
            !shortNumericCode.containsMatchIn(text)
}
