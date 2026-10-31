package io.driftlab.dualsense

import kotlin.math.hypot
import kotlin.math.sqrt

data class StickSample(
    val timestamp: Long,
    val lx: Int,
    val ly: Int,
    val rx: Int,
    val ry: Int
)

data class DriftResult(
    val samples: Int,
    val leftOffsetPercent: Double,
    val rightOffsetPercent: Double,
    val leftJitterPercent: Double,
    val rightJitterPercent: Double
) {
    val maximumOffsetPercent: Double get() = maxOf(leftOffsetPercent, rightOffsetPercent)
    val maximumJitterPercent: Double get() = maxOf(leftJitterPercent, rightJitterPercent)
    val stable: Boolean get() = maximumJitterPercent <= 1.5
}

object DriftAnalyzer {
    // DualSense USB report has 8-bit axes; the exact neutral value is between 127 and 128.
    private fun centered(value: Double) = (value - 127.5) / 127.5 * 100.0

    fun analyze(samples: List<StickSample>): DriftResult {
        require(samples.size >= 30) { "Insufficient real USB input reports (${samples.size}). Check the data cable." }
        fun measure(x: (StickSample) -> Int, y: (StickSample) -> Int): Pair<Double, Double> {
            val mx = samples.map { x(it).toDouble() }.average()
            val my = samples.map { y(it).toDouble() }.average()
            val offset = hypot(centered(mx), centered(my))
            val noise = sqrt(samples.map {
                val dx = centered(x(it).toDouble()) - centered(mx)
                val dy = centered(y(it).toDouble()) - centered(my)
                dx * dx + dy * dy
            }.average())
            return offset to noise
        }
        val (lo, lj) = measure({ it.lx }, { it.ly })
        val (ro, rj) = measure({ it.rx }, { it.ry })
        return DriftResult(samples.size, lo, ro, lj, rj)
    }
}
