package com.yunga.ultra3connect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNotificationProtocolTest {
    private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun packetEncodingAndChecksum() {
        val packet = WatchNotificationProtocol.pushNotification(
            WatchNotificationProtocol.Type.WHATSAPP, "Ultra3: mensaje de prueba")
        assertArrayEquals(hex(
            "DF 00 21 48 02 10 12 00 1C 08 00 00 55 6C 74 72 61 33 3A 20 " +
            "6D 65 6E 73 61 6A 65 20 64 65 20 70 72 75 65 62 61"
        ), packet)
    }

    @Test fun applicationCodesMatchHryFineApk() {
        assertEquals(8, WatchNotificationProtocol.Type.WHATSAPP.code)
        assertEquals(5, WatchNotificationProtocol.Type.TWITTER.code)
        assertEquals(21, WatchNotificationProtocol.Type.TELEGRAM.code)
        val twitter = WatchNotificationProtocol.pushNotification(
            WatchNotificationProtocol.Type.TWITTER, "Mensaje de prueba")
        assertEquals(5, twitter[9].toInt() and 255)
    }

    @Test fun fragmentRoundtrip() {
        val packet = WatchNotificationProtocol.pushNotification(
            WatchNotificationProtocol.Type.WHATSAPP, "Ultra3: mensaje de prueba")
        val parts = WatchNotificationProtocol.fragments(packet)
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.size <= 20 })
        assertArrayEquals(packet, parts.fold(byteArrayOf()) { a, b -> a + b })
    }

    @Test fun safeUtf8Truncation() {
        val frame = WatchNotificationProtocol.pushNotification(
            WatchNotificationProtocol.Type.SMS, "ñ".repeat(110))
        assertEquals(199, ((frame[7].toInt() and 255) shl 8) or (frame[8].toInt() and 255))
        assertEquals("ñ".repeat(98), String(frame.copyOfRange(12, frame.size), Charsets.UTF_8))
    }
}
