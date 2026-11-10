package com.yunga.ultra3connect

/**
 * Two manually-triggered control commands captured from HryFine talking to this Ultra 3.
 * No pairing, settings, OTA, or factory-reset commands are exposed here.
 *
 * 2026-11-10 HCI observations:
 * CMD 02 / SUB 0D / 01 -> watch CMD 05 / SUB 04 (heart rate)
 * CMD 02 / SUB 0E / 01 -> watch CMD 05 / SUB 05 (blood pressure)
 */
object WatchControlProtocol {
    fun startMeasurementCommand(kind: ExperimentalVitalDecoder.Kind): ByteArray {
        val subcommand = when (kind) {
            ExperimentalVitalDecoder.Kind.HEART_RATE -> 0x0D
            ExperimentalVitalDecoder.Kind.BLOOD_PRESSURE -> 0x0E
        }
        val packet = byteArrayOf(
            0xDF.toByte(), 0x00, 0x06, 0x00,
            0x02, 0x10, subcommand.toByte(), 0x00, 0x01, 0x01
        )
        packet[3] = (packet.indices.filter { it != 3 }
            .sumOf { packet[it].toInt() and 0xFF } and 0xFF).toByte()
        return packet
    }

    /**
     * FD transport acknowledgment for command 02 and subcommand 0D/0E.
     * The final byte and checksum are verified; this only confirms a protocol response.
     */
    fun isMeasurementAcknowledgment(
        packet: ByteArray, kind: ExperimentalVitalDecoder.Kind
    ): Boolean {
        if (packet.size != 9 || (packet[0].toInt() and 0xFF) != 0xFD ||
            packet[1] != 0.toByte() || packet[2] != 5.toByte() ||
            packet[4] != 2.toByte() || packet[6] != 0.toByte() ||
            packet[7] != 10.toByte() || packet[8] != 1.toByte()) return false
        val expectedSub = when (kind) {
            ExperimentalVitalDecoder.Kind.HEART_RATE -> 0x0D
            ExperimentalVitalDecoder.Kind.BLOOD_PRESSURE -> 0x0E
        }
        val observedChecksum = packet[3].toInt() and 0xFF
        val computedChecksum = (packet.indices.filter { it != 3 }
            .sumOf { packet[it].toInt() and 0xFF } and 0xFF)
        return (packet[5].toInt() and 0xFF) == expectedSub &&
            observedChecksum == computedChecksum
    }
}
