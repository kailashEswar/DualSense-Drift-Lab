package io.driftlab.dualsense

import kotlin.math.hypot

/** Pure helpers; does not send USB commands or simulate controller input. */
object PrecisionCalibration {
    const val FIELD_COUNT = 12
    const val MAX_SESSION_DELTA = 500
    val names = arrayOf("LL", "LT", "RL", "RT", "LR", "LB", "RR", "RB", "LX", "LY", "RX", "RY")

    /** Original DualShock Tools' radial 10x center magnification. */
    fun zoom(x: Float, y: Float): Pair<Float, Float> {
        val distance = hypot(x.toDouble(), y.toDouble()).toFloat()
        if (distance == 0f) return 0f to 0f
        val newDistance = if (distance <= 0.05f) {
            (distance / 0.05f) * 0.5f
        } else {
            0.5f + ((distance - 0.05f) / 0.95f) * 0.5f
        }
        return (x / distance * newDistance) to (y / distance * newDistance)
    }

    fun normalized(raw: Int): Float = (raw - 127.5f) / 127.5f

    fun fields(stick: Int, circularity: Boolean): IntArray = when {
        stick == 0 && !circularity -> intArrayOf(8, 9)
        stick == 1 && !circularity -> intArrayOf(10, 11)
        stick == 0 -> intArrayOf(0, 1, 4, 5)
        else -> intArrayOf(2, 3, 6, 7)
    }

    fun adjust(original: IntArray, working: IntArray, index: Int, delta: Int): IntArray {
        require(original.size == FIELD_COUNT && working.size == FIELD_COUNT)
        require(index in 0 until FIELD_COUNT)
        val result = working.copyOf()
        val next = (working[index].toLong() + delta).coerceIn(0L, 65535L).toInt()
        require(kotlin.math.abs(next - original[index]) <= MAX_SESSION_DELTA) {
            "Adjustment limit reached (+/-$MAX_SESSION_DELTA per field per session)."
        }
        result[index] = next
        return result
    }
}
