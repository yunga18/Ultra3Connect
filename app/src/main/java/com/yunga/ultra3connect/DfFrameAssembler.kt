package com.yunga.ultra3connect

/**
 * Interprets BLE UART notification fragments as proprietary 0xDF frames.
 * Based on observed length, payload-length and additive checksum fields.
 * Does not send Bluetooth commands, mutate GATT, or decode proprietary meanings.
 */
class DfFrameAssembler {
    data class Frame(
        val bytes: ByteArray,
        val declaredLength: Int,
        val declaredPayloadLength: Int,
        val actualPayloadLength: Int,
        val command: Int,
        val subcommand: Int,
        val checksumValid: Boolean
    ) {
        val lengthValid: Boolean get() =
            declaredLength == bytes.size - 4 && declaredPayloadLength == actualPayloadLength
        val isValid: Boolean get() = lengthValid && checksumValid
    }

    private val buffer = ArrayList<Byte>()
    private val maximumFrameBytes = 4096

    fun reset() = buffer.clear()

    fun append(fragment: ByteArray): List<Frame> {
        if (fragment.isEmpty()) return emptyList()
        fragment.forEach { buffer.add(it) }
        val result = ArrayList<Frame>()
        while (buffer.isNotEmpty()) {
            // Keep non-DF notifications in the raw packet log; they are not DF frames.
            if ((buffer[0].toInt() and 0xFF) != 0xDF) {
                buffer.removeAt(0)
                continue
            }
            if (buffer.size < 4) break
            val declared = ((buffer[1].toInt() and 0xFF) shl 8) or
                (buffer[2].toInt() and 0xFF)
            val totalBytes = declared + 4
            if (declared < 5 || totalBytes > maximumFrameBytes) {
                buffer.removeAt(0)
                continue
            }
            if (buffer.size < totalBytes) break

            val packet = ByteArray(totalBytes) { buffer[it] }
            buffer.subList(0, totalBytes).clear()
            val declaredPayload = ((packet[7].toInt() and 0xFF) shl 8) or
                (packet[8].toInt() and 0xFF)
            val computedChecksum = packet.indices
                .filter { it != 3 }
                .sumOf { packet[it].toInt() and 0xFF } and 0xFF
            val checksumValid = computedChecksum == (packet[3].toInt() and 0xFF)
            result.add(
                Frame(
                    bytes = packet,
                    declaredLength = declared,
                    declaredPayloadLength = declaredPayload,
                    actualPayloadLength = totalBytes - 9,
                    command = packet[4].toInt() and 0xFF,
                    subcommand = packet[6].toInt() and 0xFF,
                    checksumValid = checksumValid
                )
            )
        }
        return result
    }
}
