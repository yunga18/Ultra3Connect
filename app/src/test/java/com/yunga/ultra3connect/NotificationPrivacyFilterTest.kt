package com.yunga.ultra3connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPrivacyFilterTest {
    @Test fun blocksCodesAndSensitiveNotifications() {
        assertFalse(NotificationPrivacyFilter.canForward("Tu código es 123456"))
        assertFalse(NotificationPrivacyFilter.canForward("Security OTP 874219"))
        assertFalse(NotificationPrivacyFilter.canForward("PIN de acceso"))
        assertFalse(NotificationPrivacyFilter.canForward("1234"))
    }

    @Test fun ordinaryTextCanBeForwarded() {
        assertTrue(NotificationPrivacyFilter.canForward("¿Nos vemos a las tres?"))
        assertTrue(NotificationPrivacyFilter.canForward("Hola, llegué a casa"))
    }

    @Test fun packagesAreExplicitlyMappedToSources() {
        assertEquals("WhatsApp", WatchNotificationSources.fromPackage("com.whatsapp")?.name)
        assertEquals("Telegram", WatchNotificationSources.fromPackage("org.telegram.messenger")?.name)
        assertEquals(null, WatchNotificationSources.fromPackage("com.other.app"))
    }
}
