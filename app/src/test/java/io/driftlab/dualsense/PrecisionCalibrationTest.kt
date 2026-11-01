package io.driftlab.dualsense

import org.junit.Assert.*
import org.junit.Test

class PrecisionCalibrationTest {
    @Test fun centerZoomMatchesOriginal() {
        val near = PrecisionCalibration.zoom(0.05f, 0f)
        assertEquals(0.5f, near.first, 0.0001f)
        assertEquals(0f, near.second, 0.0001f)
        val far = PrecisionCalibration.zoom(1f, 0f)
        assertEquals(1f, far.first, 0.0001f)
        val origin = PrecisionCalibration.zoom(0f, 0f)
        assertEquals(0f, origin.first, 0.0001f)
    }

    @Test fun fieldSelectionAndStepGuards() {
        assertArrayEquals(intArrayOf(8, 9), PrecisionCalibration.fields(0, false))
        assertArrayEquals(intArrayOf(10, 11), PrecisionCalibration.fields(1, false))
        assertArrayEquals(intArrayOf(0, 1, 4, 5), PrecisionCalibration.fields(0, true))
        assertArrayEquals(intArrayOf(2, 3, 6, 7), PrecisionCalibration.fields(1, true))
        val base = IntArray(12) { 32000 }
        val changed = PrecisionCalibration.adjust(base, base, 8, 1)
        assertEquals(32001, changed[8])
        assertEquals(32000, base[8])
        try {
            PrecisionCalibration.adjust(base, changed, 8, 501)
            fail("Must reject a large adjustment")
        } catch (_: IllegalArgumentException) { }
    }
}
