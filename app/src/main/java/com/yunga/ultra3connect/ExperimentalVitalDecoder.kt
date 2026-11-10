package com.yunga.ultra3connect

/**
 * Hypothesis based on timestamped user observations and external descriptions of
 * related HryFine/LaxasFit protocols. Not a clinically validated measurement.
 */
object ExperimentalVitalDecoder {
    enum class Kind { HEART_RATE, BLOOD_PRESSURE }

    data class Candidate(val kind: Kind, val valueLabel: String)

    fun decode(frame: DfFrameAssembler.Frame): Candidate? {
        if (!frame.isValid || frame.command != 0x05 ||
            frame.declaredPayloadLength != 12 || frame.bytes.size != 21) return null

        val data = frame.bytes
        return when (frame.subcommand) {
            0x04 -> {
                val bpm = data[20].toInt() and 0xFF
                if (bpm in 30..220) Candidate(Kind.HEART_RATE, "$bpm lpm") else null
            }
            0x05 -> {
                val systolic = data[19].toInt() and 0xFF
                val diastolic = data[20].toInt() and 0xFF
                if (systolic in 70..220 && diastolic in 40..140 && systolic > diastolic)
                    Candidate(Kind.BLOOD_PRESSURE, "$systolic/$diastolic mmHg") else null
            }
            else -> null
        }
    }
}
