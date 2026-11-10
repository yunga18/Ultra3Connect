package com.yunga.ultra3connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DfFrameAssemblerTest {
    private fun hex(input: String) = input.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun reconstructsBothObservedFramesFromTwentyPlusOneBytes() {
        val assembler = DfFrameAssembler()
        val first = hex("DF 00 11 B7 05 01 0E 00 0C 35 6A 00 01 00 00 9F 07 00 00 00")
        assertTrue(assembler.append(first).isEmpty())
        val frame1 = assembler.append(hex("61")).single()
        assertEquals(21, frame1.bytes.size)
        assertEquals(17, frame1.declaredLength)
        assertEquals(12, frame1.actualPayloadLength)
        assertEquals(0x05, frame1.command)
        assertEquals(0x0E, frame1.subcommand)
        assertTrue(frame1.isValid)

        val second = hex("DF 00 11 B9 05 01 04 00 0C 35 6A 00 01 00 00 9F 1A 00 00 00")
        assertTrue(assembler.append(second).isEmpty())
        val frame2 = assembler.append(hex("5A")).single()
        assertEquals(21, frame2.bytes.size)
        assertEquals(0x04, frame2.subcommand)
        assertTrue(frame2.isValid)
    }

    @Test fun invalidChecksumIsReportedRatherThanSilentlyAccepted() {
        val assembler = DfFrameAssembler()
        val corrupted = hex("DF 00 11 B7 05 01 0E 00 0C 35 6A 00 01 00 00 9F 07 00 00 00 60")
        val frame = assembler.append(corrupted).single()
        assertFalse(frame.checksumValid)
        assertFalse(frame.isValid)
    }

    @Test fun resetDropsIncompleteFramesAndNoise() {
        val assembler = DfFrameAssembler()
        assertTrue(assembler.append(hex("61 5A DF 00 11 B7")).isEmpty())
        assembler.reset()
        assertTrue(assembler.append(hex("5A")).isEmpty())
    }
}
