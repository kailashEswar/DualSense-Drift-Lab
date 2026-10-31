package io.driftlab.dualsense

/** Extracts feature-report lengths from a USB HID report descriptor. */
object HidReportSizes {
    fun fromDescriptor(bytes: ByteArray): Map<Int, Int> {
        var pos = 0
        var reportId = 0
        var reportSize = 0
        var reportCount = 0
        val stack = java.util.ArrayDeque<Triple<Int, Int, Int>>()
        val bits = mutableMapOf<Int, Int>()
        while (pos < bytes.size) {
            val prefix = bytes[pos++].toInt() and 0xff
            if (prefix == 0xfe) { // Long item
                if (pos + 2 > bytes.size) break
                val n = bytes[pos].toInt() and 0xff
                pos += 2 + n
                continue
            }
            val count = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            if (pos + count > bytes.size) break
            var value = 0
            for (j in 0 until count) value = value or ((bytes[pos + j].toInt() and 0xff) shl (j * 8))
            pos += count
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0f
            if (type == 1) { // Global
                when (tag) {
                    7 -> reportSize = value
                    8 -> reportId = value
                    9 -> reportCount = value
                    10 -> stack.addLast(Triple(reportId, reportSize, reportCount))
                    11 -> if (stack.isNotEmpty()) {
                        val prev = stack.removeLast()
                        reportId = prev.first; reportSize = prev.second; reportCount = prev.third
                    }
                }
            } else if (type == 0 && tag == 11) { // Main: Feature
                if (reportId != 0 && reportSize > 0 && reportCount > 0) {
                    bits[reportId] = (bits[reportId] ?: 0) + reportSize * reportCount
                }
            }
        }
        return bits.mapValues { (_, bitCount) -> (bitCount + 7) / 8 }
    }
}
