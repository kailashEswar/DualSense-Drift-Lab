package io.driftlab.dualsense

/**
 * Pure mapping of the standard DualSense USB 0x01 input-report button bytes.
 * Byte 8: d-pad hat (low nibble) + face buttons (high nibble).
 * Byte 9: L1 and R1 (bits 0 and 1).
 * Based on the Linux hid-playstation driver and dualshock-tools finetune controls.
 */
object FineTuneControls {
    enum class Direction { LEFT, RIGHT, UP, DOWN }
    data class Change(val field: Int, val delta: Int)

    fun l1(buttons1: Int): Boolean = buttons1 and 0x01 != 0
    fun r1(buttons1: Int): Boolean = buttons1 and 0x02 != 0

    fun direction(buttons0: Int): Direction? {
        // Prefer one unambiguous face button if held; square/circle/triangle/cross.
        val face = buttons0 and 0xf0
        if (Integer.bitCount(face) == 1) {
            return when (face) {
                0x10 -> Direction.LEFT   // Square
                0x20 -> Direction.DOWN   // Cross
                0x40 -> Direction.RIGHT  // Circle
                0x80 -> Direction.UP     // Triangle
                else -> null
            }
        }
        if (face != 0) return null // Don't combine multiple face keys.
        return when (buttons0 and 0x0f) {
            0 -> Direction.UP
            2 -> Direction.RIGHT
            4 -> Direction.DOWN
            6 -> Direction.LEFT
            else -> null // Neutral (8), diagonals and reserved codes.
        }
    }

    fun stickToSelect(s: StickSample): Int? {
        val l = maxOf(kotlin.math.abs(PrecisionCalibration.normalized(s.lx)),
            kotlin.math.abs(PrecisionCalibration.normalized(s.ly)))
        val r = maxOf(kotlin.math.abs(PrecisionCalibration.normalized(s.rx)),
            kotlin.math.abs(PrecisionCalibration.normalized(s.ry)))
        return when {
            l >= 0.65f && r < 0.45f -> 0
            r >= 0.65f && l < 0.45f -> 1
            else -> null
        }
    }

    /**
     * Match original center and circularity D-pad mapping.
     * Center: left/up increase corresponding raw axis, right/down decrease.
     * Circularity: the selected stick must be at a cardinal extreme; left/up
     * increase the corresponding directional field, right/down decrease it.
     */
    fun change(s: StickSample, stick: Int, circularity: Boolean,
               direction: Direction, step: Int): Change? {
        require(stick == 0 || stick == 1)
        require(step in 1..15)
        val x = PrecisionCalibration.normalized(if (stick == 0) s.lx else s.rx)
        val y = PrecisionCalibration.normalized(if (stick == 0) s.ly else s.ry)
        if (!circularity) {
            if (kotlin.math.abs(x) > 0.50f || kotlin.math.abs(y) > 0.50f) return null
            val targetX = if (stick == 0) 8 else 10
            val targetY = targetX + 1
            return when (direction) {
                Direction.LEFT -> Change(targetX, step)
                Direction.RIGHT -> Change(targetX, -step)
                Direction.UP -> Change(targetY, step)
                Direction.DOWN -> Change(targetY, -step)
            }
        }
        val ax = kotlin.math.abs(x)
        val ay = kotlin.math.abs(y)
        // Circularity can be tuned only while holding a cardinal extreme.
        if (maxOf(ax, ay) < 0.50f || minOf(ax, ay) >= 0.20f) return null
        val horizontal = ax > ay
        val field = if (stick == 0) {
            if (horizontal) { if (x < 0) 0 else 4 }
            else { if (y < 0) 1 else 5 }
        } else {
            if (horizontal) { if (x < 0) 2 else 6 }
            else { if (y < 0) 3 else 7 }
        }
        if (horizontal && direction != Direction.LEFT && direction != Direction.RIGHT) return null
        if (!horizontal && direction != Direction.UP && direction != Direction.DOWN) return null
        val delta = if (direction == Direction.LEFT || direction == Direction.UP) step else -step
        return Change(field, delta)
    }
}
