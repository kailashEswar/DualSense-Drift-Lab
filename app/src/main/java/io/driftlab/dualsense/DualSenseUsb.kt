package io.driftlab.dualsense

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * Native Android USB HID transport (no WebView, no WebHID dependency).
 * Standard DualSense ONLY (VID 054c / PID 0ce6); USB only.
 *
 * Feature report command sequences are based on publicly documented
 * community reverse engineering by dualshock-tools (MIT). Hardware/firmware
 * behavior differs; all write workflows are guarded by a checked response.
 */
class DualSenseUsb(
    manager: UsbManager,
    val device: UsbDevice,
    private val onSample: (StickSample) -> Unit,
    private val onTransportError: (String) -> Unit
) {
    companion object {
        const val VENDOR_ID = 0x054c
        const val PRODUCT_ID = 0x0ce6
        fun supports(device: UsbDevice) =
            device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID
    }

    private val connection: UsbDeviceConnection = manager.openDevice(device)
        ?: error("Android could not open the DualSense. Check USB permission.")
    private val hid: UsbInterface
    private val interruptIn: UsbEndpoint
    private val featureLengths: Map<Int, Int>
    private var request: UsbRequest? = null
    @Volatile private var running = false
    @Volatile private var closing = false
    @Volatile var lastInputAt = 0L
        private set
    private val reportLock = Any()

    init {
        try {
            require(supports(device)) { "Only the standard PS5 DualSense (054C:0CE6) is supported." }
            val match = (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { intf -> intf.interfaceClass == UsbConstants.USB_CLASS_HID &&
                    (0 until intf.endpointCount).any { i ->
                        val ep = intf.getEndpoint(i)
                        ep.direction == UsbConstants.USB_DIR_IN &&
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
                    }
                } ?: error("The controller HID USB interface was not found.")
            hid = match
            interruptIn = (0 until hid.endpointCount).map { hid.getEndpoint(it) }.first {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
            check(connection.claimInterface(hid, true)) {
                "Android cannot claim the controller HID interface. Another driver/app may own it."
            }
            val descriptor = ByteArray(1024)
            // Standard GET_DESCRIPTOR, HID report descriptor (0x22), interface recipient.
            val got = connection.controlTransfer(
                0x81, 0x06, 0x2200, hid.id, descriptor, descriptor.size, 1500
            )
            check(got > 0) { "Cannot read USB HID descriptor. Calibration writes are not allowed." }
            featureLengths = HidReportSizes.fromDescriptor(descriptor.copyOf(got))
            // Require the known standard DualSense structure; do not guess command packet sizes.
            check(featureLengths[0x80] == 63 && featureLengths[0x81] == 63 &&
                featureLengths[0x82] == 9 && featureLengths[0x83] == 63) {
                "Unsupported HID report descriptor. Found sizes: $featureLengths"
            }
        } catch (error: Exception) {
            connection.close()
            throw error
        }
    }

    fun startInput() {
        check(!closing)
        running = true
        Thread({
            val req = UsbRequest()
            if (!req.initialize(connection, interruptIn)) {
                if (!closing) onTransportError("Cannot initialize USB interrupt read.")
                return@Thread
            }
            request = req
            val buffer = ByteBuffer.allocateDirect(maxOf(interruptIn.maxPacketSize, 64))
            var queued = false
            try {
                while (running) {
                    if (!queued) {
                        buffer.clear()
                        check(req.queue(buffer)) { "USB interrupt input could not be queued." }
                        queued = true
                    }
                    // Android 8+ provides timed requestWait; do not re-queue after timeout.
                    val completed = try {
                        connection.requestWait(1000)
                    } catch (_: TimeoutException) {
                        // The request remains queued. A timeout is not a device failure.
                        continue
                    }
                    if (completed == null) error("USB interrupt request failed.")
                    if (completed !== req) continue
                    queued = false
                    val size = buffer.position()
                    buffer.flip()
                    if (size >= 11 && (buffer.get(0).toInt() and 0xff) == 0x01) {
                        val sample = StickSample(
                            timestamp = SystemClock.elapsedRealtime(),
                            lx = buffer.get(1).toInt() and 0xff,
                            ly = buffer.get(2).toInt() and 0xff,
                            rx = buffer.get(3).toInt() and 0xff,
                            ry = buffer.get(4).toInt() and 0xff,
                            buttons0 = buffer.get(8).toInt() and 0xff,
                            buttons1 = buffer.get(9).toInt() and 0xff
                        )
                        lastInputAt = sample.timestamp
                        onSample(sample)
                    }
                }
            } catch (error: Exception) {
                if (!closing) onTransportError(error.message ?: "USB input failed")
            } finally {
                try { req.cancel() } catch (_: Exception) { }
                try { req.close() } catch (_: Exception) { }
                if (request === req) request = null
            }
        }, "DualSense-USB-Input").apply { isDaemon = true; start() }
    }

    fun close() {
        closing = true
        running = false
        try { request?.cancel() } catch (_: Exception) { }
        try { connection.releaseInterface(hid) } catch (_: Exception) { }
        connection.close()
    }

    private fun setFeature(id: Int, payload: IntArray) {
        val length = featureLengths[id] ?: error("Feature 0x${id.toString(16)} is unavailable.")
        require(payload.size <= length)
        val bytes = ByteArray(length + 1)
        bytes[0] = id.toByte()
        for (i in payload.indices) bytes[i + 1] = payload[i].toByte()
        val count = connection.controlTransfer(0x21, 0x09, 0x0300 or id,
            hid.id, bytes, bytes.size, 1500)
        check(count == bytes.size) {
            "SET_REPORT 0x${id.toString(16)} failed ($count of ${bytes.size} bytes). Device state may be unknown."
        }
    }

    private fun getFeature(id: Int): ByteArray {
        val length = featureLengths[id] ?: error("Feature reply 0x${id.toString(16)} unavailable.")
        val bytes = ByteArray(length + 1)
        var count = connection.controlTransfer(0xa1, 0x01, 0x0300 or id,
            hid.id, bytes, bytes.size, 1500)
        // Older pyUSB calibration clients use 0x00xx for GET_REPORT; read-only fallback.
        if (count <= 0) {
            count = connection.controlTransfer(0xa1, 0x01, id,
                hid.id, bytes, bytes.size, 1500)
        }
        check(count >= 5) { "GET_REPORT 0x${id.toString(16)} returned only $count bytes." }
        check((bytes[0].toInt() and 0xff) == id) {
            "Unexpected 0x${id.toString(16)} report ID: 0x${(bytes[0].toInt() and 255).toString(16)}"
        }
        return bytes.copyOf(count)
    }

    /**
     * Read the 12 live calibration parameters, following the original DualShock Tools
     * getInMemoryModuleData() feature report sequence (0x80: 0C 02 -> 0x81).
     * This does not change calibration values or non-volatile storage.
     */
    fun readFinetuneData(): IntArray = synchronized(reportLock) {
        check(!closing) { "Controller disconnected." }
        check(memoryStatus() == MemoryState.LOCKED) { "NVS must be confirmed locked before finetuning." }
        setFeature(0x80, intArrayOf(12, 2))
        Thread.sleep(100)
        val reply = getFeature(0x81)
        check(reply.size >= 28) { "Incomplete finetune data (got ${reply.size} bytes)." }
        val code = reply[1].toInt() and 255
        val operation = reply[2].toInt() and 255
        val status = reply[3].toInt() and 255
        check(code == 12 && (operation == 2 || operation == 4) && status == 2) {
            "Unexpected finetune response: $code/$operation/$status. No values will be written."
        }
        IntArray(PrecisionCalibration.FIELD_COUNT) { i ->
            val lo = reply[4 + 2 * i].toInt() and 255
            val hi = reply[5 + 2 * i].toInt() and 255
            lo or (hi shl 8)
        }
    }

    /**
     * Write working RAM calibration only (NOT a persistent NVS save), then verify
     * by re-reading all 12 values. Never auto-retry a failed write.
     */
    fun writeFinetuneData(data: IntArray) = synchronized(reportLock) {
        check(!closing) { "Controller disconnected." }
        require(data.size == PrecisionCalibration.FIELD_COUNT && data.all { it in 0..65535 }) {
            "Invalid 12-field calibration payload."
        }
        check(memoryStatus() == MemoryState.LOCKED) { "Calibration memory not locked." }
        val payload = IntArray(2 + data.size * 2)
        payload[0] = 12
        payload[1] = 1
        data.forEachIndexed { i, v ->
            payload[2 + i * 2] = v and 255
            payload[3 + i * 2] = (v ushr 8) and 255
        }
        setFeature(0x80, payload)
        Thread.sleep(100)
        check(readFinetuneData().contentEquals(data)) {
            "Controller did not confirm new RAM calibration. Power-cycle before any further writes."
        }
    }

    enum class MemoryState { LOCKED, UNLOCKED, PENDING_REBOOT, UNKNOWN }

    /** Read-only in the sense of calibration memory, but sends a USB status request. */
    fun memoryStatus(): MemoryState = synchronized(reportLock) {
        setFeature(0x80, intArrayOf(3, 3))
        val bytes = getFeature(0x81)
        val code = (1..4).fold(0L) { value, i ->
            (value shl 8) or (bytes[i].toLong() and 255L)
        }
        when (code) {
            0x03030201L -> MemoryState.LOCKED
            0x03030200L -> MemoryState.UNLOCKED
            0x15010100L -> MemoryState.PENDING_REBOOT
            else -> MemoryState.UNKNOWN
        }
    }

    private fun calibrationCommand(operation: Int, target: Int, success: Long) =
        synchronized(reportLock) {
            setFeature(0x82, intArrayOf(operation, 1, target))
            val response = getFeature(0x83)
            val reply = (0..3).fold(0L) { acc, i ->
                (acc shl 8) or (response[i].toLong() and 255L)
            }
            check(reply == success) {
                "Unexpected calibration response 0x${reply.toString(16)} (expected 0x${success.toString(16)}). Stop; power-cycle controller."
            }
        }

    fun beginCenter() = calibrationCommand(1, 1, 0x83010101L)
    fun sampleCenter() = calibrationCommand(3, 1, 0x83010101L)
    fun endCenter() = calibrationCommand(2, 1, 0x83010102L)
    fun beginRange() = calibrationCommand(1, 2, 0x83010201L)
    fun endRange() = calibrationCommand(2, 2, 0x83010202L)

    /**
     * Highly sensitive: writes the current working calibration to non-volatile memory.
     * Call ONLY after a user explicitly confirms and the caller checked LOCKED state.
     * Never auto-retry unlock; repeat lock once only if the first lock fails.
     */
    fun savePermanently() = synchronized(reportLock) {
        check(memoryStatus() == MemoryState.LOCKED) { "Memory must be confirmed locked before saving." }
        fun nvsCommand(payload: IntArray) {
            setFeature(0x80, payload)
            val response = getFeature(0x81)
            check(response[0].toInt() and 255 == 0x81) { "Invalid NVS acknowledgment." }
        }
        nvsCommand(intArrayOf(3, 2, 101, 50, 64, 12)) // unlock: potentially commits
        try {
            nvsCommand(intArrayOf(3, 1)) // relock
        } catch (first: Exception) {
            // Lock is the only operation eligible for one retry.
            try { nvsCommand(intArrayOf(3, 1)) }
            catch (second: Exception) {
                throw IllegalStateException("Memory may remain unlocked. Do not calibrate again; restart the controller.", second)
            }
        }
        check(memoryStatus() == MemoryState.LOCKED) {
            "Memory state after save is unverified. Do not run any more calibration commands."
        }
    }
}
