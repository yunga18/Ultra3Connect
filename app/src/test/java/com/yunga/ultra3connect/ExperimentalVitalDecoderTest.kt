package com.yunga.ultra3connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExperimentalVitalDecoderTest {
    private fun hex(input: String) = input.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun heartRateCandidateFromObservedFrame() {
        val frame = DfFrameAssembler().append(
            hex("DF 00 11 54 05 01 04 00 0C 35 6A 00 01 00 00 A4 B0 00 00 00 5A")
        ).single()
        assertEquals(ExperimentalVitalDecoder.Kind.HEART_RATE, ExperimentalVitalDecoder.decode(frame)?.kind)
        assertEquals("90 lpm", ExperimentalVitalDecoder.decode(frame)?.valueLabel)
    }

    @Test fun bloodPressureCandidateFromObservedFrame() {
        val frame = DfFrameAssembler().append(
            hex("DF 00 11 E4 05 01 05 00 0C 35 6A 00 01 00 00 A4 D8 00 00 6F 52")
        ).single()
        assertEquals(ExperimentalVitalDecoder.Kind.BLOOD_PRESSURE, ExperimentalVitalDecoder.decode(frame)?.kind)
        assertEquals("111/82 mmHg", ExperimentalVitalDecoder.decode(frame)?.valueLabel)
    }

    @Test fun doesNotDecodeUnknownOrInvalidFrames() {
        val frame = DfFrameAssembler().append(
            hex("DF 00 11 54 05 01 04 00 0C 35 6A 00 01 00 00 A4 B0 00 00 00 5B")
        ).single()
        assertNull(ExperimentalVitalDecoder.decode(frame))
    }
}
