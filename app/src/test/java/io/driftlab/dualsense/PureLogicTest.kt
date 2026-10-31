package io.driftlab.dualsense

import org.junit.Assert.*
import org.junit.Test

class PureLogicTest {
    @Test fun descriptorFeatureLengths() {
        val descriptor = byteArrayOf(
            0x75, 0x08, 0x85.toByte(), 0x80.toByte(), 0x95.toByte(), 0x3f,
            0xB1.toByte(), 0x02, 0x85.toByte(), 0x82.toByte(), 0x95.toByte(), 0x09,
            0xB1.toByte(), 0x02
        )
        val lengths = HidReportSizes.fromDescriptor(descriptor)
        assertEquals(63, lengths[0x80])
        assertEquals(9, lengths[0x82])
    }

    @Test fun centeredSticksHaveSubOnePercentFloor() {
        val samples = (0 until 130).map {
            StickSample(it.toLong(), 127, 128, 128, 127)
        }
        val result = DriftAnalyzer.analyze(samples)
        assertTrue(result.maximumOffsetPercent < 1.0)
        assertTrue(result.stable)
    }

    @Test fun stableOffsetIsDetected() {
        val samples = (0 until 130).map {
            StickSample(it.toLong(), 145, 128, 128, 128)
        }
        val result = DriftAnalyzer.analyze(samples)
        assertTrue(result.leftOffsetPercent > 10)
        assertTrue(result.rightOffsetPercent < 1)
        assertTrue(result.stable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesTooFewSamples() {
        DriftAnalyzer.analyze(listOf(StickSample(1, 127, 127, 127, 127)))
    }
}
