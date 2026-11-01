package io.driftlab.dualsense

import io.driftlab.dualsense.FineTuneControls.Direction as D
import org.junit.Assert.*
import org.junit.Test

class FineTuneControlsTest {
    private fun s(lx: Int = 128, ly: Int = 128, rx: Int = 128, ry: Int = 128,
                  b0: Int = 8, b1: Int = 0) =
        StickSample(0L, lx, ly, rx, ry, b0, b1)

    @Test fun dpadFaceButtonsAndShoulders() {
        assertNull(FineTuneControls.direction(8))
        assertEquals(D.UP, FineTuneControls.direction(0))
        assertEquals(D.RIGHT, FineTuneControls.direction(2))
        assertEquals(D.DOWN, FineTuneControls.direction(4))
        assertEquals(D.LEFT, FineTuneControls.direction(6))
        assertNull(FineTuneControls.direction(1))
        assertEquals(D.LEFT, FineTuneControls.direction(0x18)) // Square
        assertEquals(D.DOWN, FineTuneControls.direction(0x28)) // Cross
        assertEquals(D.RIGHT, FineTuneControls.direction(0x48)) // Circle
        assertEquals(D.UP, FineTuneControls.direction(0x88)) // Triangle
        assertNull(FineTuneControls.direction(0x38)) // Ambiguous face buttons
        assertTrue(FineTuneControls.l1(0x01))
        assertTrue(FineTuneControls.r1(0x02))
        assertFalse(FineTuneControls.r1(0x01))
    }

    @Test fun autoSelectMovedStick() {
        assertEquals(0, FineTuneControls.stickToSelect(s(lx = 240)))
        assertEquals(1, FineTuneControls.stickToSelect(s(rx = 8)))
        assertNull(FineTuneControls.stickToSelect(s()))
        assertNull(FineTuneControls.stickToSelect(s(lx = 240, rx = 8)))
    }

    @Test fun centerMatchesOriginalDirectionalMapping() {
        assertEquals(FineTuneControls.Change(8, 5), FineTuneControls.change(s(), 0, false, D.LEFT, 5))
        assertEquals(FineTuneControls.Change(8, -5), FineTuneControls.change(s(), 0, false, D.RIGHT, 5))
        assertEquals(FineTuneControls.Change(9, 5), FineTuneControls.change(s(), 0, false, D.UP, 5))
        assertEquals(FineTuneControls.Change(9, -5), FineTuneControls.change(s(), 0, false, D.DOWN, 5))
        assertEquals(FineTuneControls.Change(10, 1), FineTuneControls.change(s(), 1, false, D.LEFT, 1))
        assertNull(FineTuneControls.change(s(lx = 250), 0, false, D.LEFT, 5))
    }

    @Test fun circularityRequiresCardinalExtremeAndMapsField() {
        assertEquals(FineTuneControls.Change(0, 5), FineTuneControls.change(s(lx = 0), 0, true, D.LEFT, 5))
        assertEquals(FineTuneControls.Change(4, -5), FineTuneControls.change(s(lx = 255), 0, true, D.RIGHT, 5))
        assertEquals(FineTuneControls.Change(1, 5), FineTuneControls.change(s(ly = 0), 0, true, D.UP, 5))
        assertEquals(FineTuneControls.Change(5, -5), FineTuneControls.change(s(ly = 255), 0, true, D.DOWN, 5))
        assertEquals(FineTuneControls.Change(2, 5), FineTuneControls.change(s(rx = 0), 1, true, D.LEFT, 5))
        assertEquals(FineTuneControls.Change(7, -5), FineTuneControls.change(s(ry = 255), 1, true, D.DOWN, 5))
        assertNull(FineTuneControls.change(s(), 0, true, D.LEFT, 5))
        assertNull(FineTuneControls.change(s(lx = 245, ly = 247), 0, true, D.LEFT, 5))
        assertNull(FineTuneControls.change(s(lx = 0), 0, true, D.UP, 5))
    }
}
