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

    @Test fun hypotheticalCounterMatchesObservedSeventyNineSecondDelta() {
        val assembler = DfFrameAssembler()
        val earlier = hex("DF 00 11 C5 05 01 04 00 0C 35 6A 00 01 00 00 A2 27 00 00 00 56")
        val later = hex("DF 00 11 85 05 01 05 00 0C 35 6A 00 01 00 00 A2 76 00 00 81 45")
        val frame1 = assembler.append(earlier).single()
        val frame2 = assembler.append(later).single()
        assertTrue(frame1.isValid)
        assertTrue(frame2.isValid)
        assertEquals(79, frame2.counterCandidate16Be!! - frame1.counterCandidate16Be!!)
        assertEquals(0x04, frame1.subcommand)
        assertEquals(0x05, frame2.subcommand)
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
