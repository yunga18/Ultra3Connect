package com.yunga.ultra3connect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchControlProtocolTest {
    private fun hex(input: String) = input.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun startHeartRateMatchesCapturedHryFinePacket() {
        assertArrayEquals(
            hex("DF 00 06 06 02 10 0D 00 01 01"),
            WatchControlProtocol.startMeasurementCommand(ExperimentalVitalDecoder.Kind.HEART_RATE)
        )
    }

    @Test fun startPressureMatchesCapturedHryFinePacket() {
        assertArrayEquals(
            hex("DF 00 06 07 02 10 0E 00 01 01"),
            WatchControlProtocol.startMeasurementCommand(ExperimentalVitalDecoder.Kind.BLOOD_PRESSURE)
        )
    }

    @Test fun recognizesObservedWatchAcknowledgments() {
        assertTrue(WatchControlProtocol.isMeasurementAcknowledgment(
            hex("FD 00 05 1C 02 0D 00 0A 01"), ExperimentalVitalDecoder.Kind.HEART_RATE
        ))
        assertTrue(WatchControlProtocol.isMeasurementAcknowledgment(
            hex("FD 00 05 1D 02 0E 00 0A 01"), ExperimentalVitalDecoder.Kind.BLOOD_PRESSURE
        ))
        assertFalse(WatchControlProtocol.isMeasurementAcknowledgment(
            hex("FD 00 05 1D 02 0E 00 0A 01"), ExperimentalVitalDecoder.Kind.HEART_RATE
        ))
    }

    @Test fun rejectsCorruptAcknowledgmentChecksum() {
        assertFalse(WatchControlProtocol.isMeasurementAcknowledgment(
            hex("FD 00 05 1C 02 0D 00 0A 02"), ExperimentalVitalDecoder.Kind.HEART_RATE
        ))
    }
}
