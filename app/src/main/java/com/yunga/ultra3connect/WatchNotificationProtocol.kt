package com.yunga.ultra3connect

/**
 * HryFine CMD=02/SUB=12. The user verified that the watch displays a test message.
 * Icon mappings come from the uploaded HryFine APK, MsgPushHelper.getType/getNewType.
 * On this Ultra 3, code 5 displays X/Twitter; WhatsApp must use code 8.
 */
object WatchNotificationProtocol {
    enum class Type(val code: Int) { SMS(0), TWITTER(5), WHATSAPP(8), TELEGRAM(21), OTHER(13) }

    fun pushNotification(type: Type, message: String): ByteArray {
        val body = validUtf8Prefix(message, 196)
        val payload = byteArrayOf(type.code.toByte(), 0, 0) + body
        val frame = ByteArray(9 + payload.size)
        frame[0] = 0xDF.toByte()
        val length = payload.size + 5
        frame[1] = (length ushr 8).toByte()
        frame[2] = length.toByte()
        frame[4] = 0x02
        frame[5] = 0x10
        frame[6] = 0x12
        frame[7] = (payload.size ushr 8).toByte()
        frame[8] = payload.size.toByte()
        payload.copyInto(frame, 9)
        frame[3] = (frame.indices.filter { it != 3 }
            .sumOf { frame[it].toInt() and 0xFF } and 0xFF).toByte()
        return frame
    }

    fun fragments(frame: ByteArray, size: Int = 20): List<ByteArray> {
        require(size in 1..512)
        return frame.toList().chunked(size) { it.toByteArray() }
    }

    private fun validUtf8Prefix(value: String, maxBytes: Int): ByteArray {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < value.length) {
            val point = Character.codePointAt(value, i)
            val chunk = String(Character.toChars(point)).toByteArray(Charsets.UTF_8)
            if (bytes.size + chunk.size > maxBytes) break
            chunk.forEach { bytes.add(it) }
            i += Character.charCount(point)
        }
        return bytes.toByteArray()
    }
}
