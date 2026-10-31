package io.driftlab.dualsense

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object { private const val ACTION_USB_PERMISSION = "io.driftlab.dualsense.USB_PERMISSION" }
    private enum class Session { NONE, CENTER, RANGE, FAULT }
    private val background = Color.rgb(10, 16, 31)
    private val surface = Color.rgb(23, 33, 55)
    private val accent = Color.rgb(57, 169, 247)
    private val ink = Color.rgb(238, 243, 252)
    private val muted = Color.rgb(158, 171, 192)
    private val good = Color.rgb(104, 235, 186)
    private val warning = Color.rgb(255, 191, 105)

    private lateinit var usbManager: UsbManager
    private var usb: DualSenseUsb? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val samplesLock = Any()
    private val samples = ArrayDeque<StickSample>()
    @Volatile private var session = Session.NONE
    @Volatile private var operationBusy = false
    @Volatile private var dirty = false
    @Volatile private var memory: DualSenseUsb.MemoryState? = null
    private var baseline: DriftResult? = null
    private var afterResult: DriftResult? = null
    private var centerStep = 0
    private var rangeStarted = 0L
    private var rangeCount = 0
    private val rangeMin = IntArray(4) { 255 }
    private val rangeMax = IntArray(4)
    private var lastFrame = 0L
    @Volatile private var connectionEpoch = 0

    private lateinit var statusText: TextView
    private lateinit var memoryText: TextView
    private lateinit var rawText: TextView
    private lateinit var resultText: TextView
    private lateinit var instructionText: TextView
    private lateinit var logText: TextView
    private lateinit var connectButton: Button
    private lateinit var testButton: Button
    private lateinit var centerButton: Button
    private lateinit var rangeButton: Button
    private lateinit var saveButton: Button
    private lateinit var leftPlot: StickPlotView
    private lateinit var rightPlot: StickPlotView

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != ACTION_USB_PERMISSION) return
        @Suppress("DEPRECATION")
        val device = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            open(device)
        else note("USB access was denied.")
    }
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                @Suppress("DEPRECATION")
                val device = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                if (device != null && device.deviceId == usb?.device?.deviceId) {
                    disconnect("USB disconnected. If a calibration was open, power-cycle before trying again.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        // Keep display awake while the app is in the foreground during USB work.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        }
        buildUi()
        updateUi()
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value + 0.5f).toInt()
    private fun shape(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun label(text: String, size: Float = 14f, color: Int = muted, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
    private fun vertical(gap: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (gap > 0) setPadding(0, dp(gap), 0, dp(gap))
    }
    private fun section(parent: LinearLayout, title: String, subtitle: String = ""): LinearLayout {
        val box = vertical()
        box.background = shape(surface)
        box.setPadding(dp(18), dp(18), dp(18), dp(18))
        box.addView(label(title, 18f, ink, true))
        if (subtitle.isNotEmpty()) {
            val l = label(subtitle, 12f, muted)
            l.setPadding(0, dp(5), 0, dp(12))
            box.addView(l)
        } else box.setPadding(dp(18), dp(18), dp(18), dp(18))
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        return box
    }
    private fun action(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        background = shape(accent, 12)
        minimumHeight = dp(48)
        setOnClickListener { onClick() }
        val p = LinearLayout.LayoutParams(-1, dp(50))
        p.topMargin = dp(10)
        layoutParams = p
    }
    private fun textInfo(box: LinearLayout, text: String): TextView {
        val v = label(text, 14f, ink)
        box.addView(v)
        return v
    }
    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(background); fillViewport = true }
        val root = vertical()
        root.setPadding(dp(18), dp(28), dp(18), dp(32))
        scroll.addView(root)
        setContentView(scroll)
        root.addView(label("DRIFT LAB  /  ANDROID", 12f, accent, true))
        root.addView(label("DualSense Calibration", 27f, ink, true).apply {
            setPadding(0, dp(5), 0, dp(6))
        })
        root.addView(label("Native USB connection · No browser required", 13f).apply {
            setPadding(0, 0, 0, dp(20))
        })

        val device = section(root, "01  Controller", "Standard DualSense • USB-C data cable / OTG")
        statusText = textInfo(device, "Not connected")
        memoryText = label("Memory status: —", 12f, muted).also {
            it.setPadding(0, dp(8), 0, 0); device.addView(it)
        }
        connectButton = action("Connect DualSense") { connectOrDisconnect() }.also { device.addView(it) }

        val live = section(root, "02  Live stick monitor", "Real controller input, no simulated values")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val lcol = vertical().apply { gravity = Gravity.CENTER }
        val rcol = vertical().apply { gravity = Gravity.CENTER }
        leftPlot = StickPlotView(this); rightPlot = StickPlotView(this)
        lcol.addView(label("LEFT STICK", 11f, ink, true))
        lcol.addView(leftPlot)
        rcol.addView(label("RIGHT STICK", 11f, ink, true))
        rcol.addView(rightPlot)
        row.addView(lcol, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(rcol, LinearLayout.LayoutParams(0, -2, 1f))
        live.addView(row)
        rawText = label("LX: —   LY: —    RX: —   RY: —", 12f, muted)
        rawText.gravity = Gravity.CENTER
        live.addView(rawText)

        val drift = section(root, "03  Drift diagnosis", "Leave both sticks untouched on a flat surface for 3.5 seconds")
        resultText = textInfo(drift, "Run a drift test to establish a baseline before calibrating.")
        testButton = action("Run drift test") { driftTest() }.also { drift.addView(it) }

        val cal = section(root, "04  Calibration", "Experimental hardware writes. Never unplug during a calibration step.")
        instructionText = textInfo(cal, "Start with a stable baseline test. Calibrate only if the sticks are mechanically sound.")
        centerButton = action("Guided center calibration") { centerClick() }.also { cal.addView(it) }
        rangeButton = action("Range calibration") { rangeClick() }.also { cal.addView(it) }

        val save = section(root, "05  Permanent memory save", "Disabled until calibration and a verified after-test are complete")
        save.addView(label("Saving changes to NVS is potentially irreversible and can damage calibration. Test the result first.", 13f, warning))
        saveButton = action("Save permanently") { confirmSave() }.also { save.addView(it) }

        val events = section(root, "Diagnostics", "Local-only. No accounts, network access or analytics")
        logText = label("Connect a controller to begin.", 12f, muted)
        events.addView(logText)
        root.addView(label("UNOFFICIAL • Not affiliated with Sony. Calibration cannot repair worn hardware.", 11f).apply {
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun note(message: String) {
        logText.text = "${SystemClock.elapsedRealtime() / 1000}s  $message\n\n" + logText.text.take(1800)
    }
    private fun connectOrDisconnect() {
        if (usb != null) { disconnect("Controller disconnected by user."); return }
        val device = usbManager.deviceList.values.firstOrNull { DualSenseUsb.supports(it) }
        if (device == null) {
            note("No standard DualSense USB device found (054C:0CE6). Use a data-capable USB-C / OTG cable.")
            return
        }
        if (usbManager.hasPermission(device)) { open(device); return }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        // Explicit activity intent avoids mutable implicit PendingIntent restrictions on Android 14+.
        val intent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_USB_PERMISSION
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }, flags)
        usbManager.requestPermission(device, intent)
        note("Requested Android USB permission…")
    }

    private fun open(device: UsbDevice) {
        if (usb != null) disconnect("Changing controller")
        try {
            val bridge = DualSenseUsb(usbManager, device,
                onSample = { sample -> handleInput(sample) },
                onTransportError = { error -> runOnUiThread {
                    note("USB transport error: $error"); session = Session.FAULT; updateUi()
                } }
            )
            connectionEpoch++
            usb = bridge
            synchronized(samplesLock) { samples.clear() }
            session = Session.NONE; dirty = false; memory = null
            baseline = null; afterResult = null; centerStep = 0
            bridge.startInput()
            note("Connected to standard DualSense. Reading calibration memory lock state…")
            runCommand("Check NVS") { memory = bridge.memoryStatus() }
        } catch (error: Exception) {
            note("Connection failed: ${error.message}")
        }
        updateUi()
    }
    private fun disconnect(message: String) {
        connectionEpoch++
        usb?.close(); usb = null
        if (session != Session.NONE) note("WARNING: Calibration was active or failed. Power-cycle before reconnecting.")
        session = Session.NONE; memory = null; dirty = false
        baseline = null; afterResult = null
        synchronized(samplesLock) { samples.clear() }
        note(message); updateUi()
    }

    private fun handleInput(sample: StickSample) {
        synchronized(samplesLock) {
            samples.addLast(sample)
            while (samples.size > 1200) samples.removeFirst()
            if (session == Session.RANGE) {
                val axes = intArrayOf(sample.lx, sample.ly, sample.rx, sample.ry)
                for (i in 0..3) {
                    rangeMin[i] = minOf(rangeMin[i], axes[i])
                    rangeMax[i] = maxOf(rangeMax[i], axes[i])
                }
                rangeCount++
            }
        }
        if (sample.timestamp - lastFrame < 100) return
        lastFrame = sample.timestamp
        runOnUiThread {
            if (usb == null) return@runOnUiThread
            leftPlot.setPosition(sample.lx, sample.ly)
            rightPlot.setPosition(sample.rx, sample.ry)
            rawText.text = "LX ${sample.lx}  LY ${sample.ly}     RX ${sample.rx}  RY ${sample.ry}"
            if (session == Session.RANGE) showInstruction()
        }
    }

    private fun runCommand(label: String, block: () -> Unit) {
        if (operationBusy) return
        operationBusy = true
        val epoch = connectionEpoch
        updateUi()
        executor.execute {
            var error: String? = null
            try { block() } catch (t: Exception) { error = t.message ?: t.javaClass.simpleName }
            runOnUiThread {
                if (epoch != connectionEpoch) {
                    operationBusy = false; updateUi(); return@runOnUiThread
                }
                if (error == null) note("$label: completed")
                else {
                    note("$label: $error")
                    if (label.contains("calibration", true) || label.contains("sample", true) ||
                        label.contains("range", true) || label.contains("Save", true)) {
                        session = Session.FAULT
                    }
                }
                operationBusy = false; updateUi()
            }
        }
    }

    private fun driftTest() {
        val bridge = usb ?: return
        if (session == Session.CENTER || session == Session.RANGE || operationBusy) return
        if (SystemClock.elapsedRealtime() - bridge.lastInputAt > 1500) {
            note("No recent controller input. Check the USB connection."); return
        }
        note("Collecting 3.5s of resting-stick samples. Do not touch the sticks.")
        runCommand("Drift test") {
            val start = SystemClock.elapsedRealtime()
            Thread.sleep(3500)
            val batch = synchronized(samplesLock) { samples.filter { it.timestamp >= start } }
            val result = DriftAnalyzer.analyze(batch)
            if (dirty) afterResult = result else baseline = result
        }
    }

    private fun readyForCalibration(): Boolean {
        val bridge = usb ?: return false
        if (operationBusy || session != Session.NONE) return false
        if (memory != DualSenseUsb.MemoryState.LOCKED) {
            note("Writes blocked: calibration memory lock must be confirmed LOCKED.")
            return false
        }
        val base = baseline
        if (base == null) { note("First run the drift test with sticks untouched."); return false }
        if (!base.stable || base.maximumOffsetPercent > 15.0) {
            note("Unstable or severe stick offset; avoid recalibration before checking hardware.")
            return false
        }
        if (SystemClock.elapsedRealtime() - bridge.lastInputAt > 1500) {
            note("Controller stopped reporting input. Not starting calibration."); return false
        }
        return true
    }
    private fun centerClick() {
        val bridge = usb ?: return
        if (operationBusy || session == Session.FAULT || session == Session.RANGE) return
        if (session == Session.NONE) {
            if (!readyForCalibration()) return
            AlertDialog.Builder(this)
                .setTitle("Begin guided center calibration?")
                .setMessage("This sends undocumented hardware commands. Do not disconnect or close the app during the four steps. A failed session requires a controller power-cycle. Proceed only with a charged controller.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Begin") { _, _ ->
                    // Mark uncertain/possibly open before issuing command: never auto-commit partial state.
                    session = Session.FAULT
                    runCommand("Start center calibration") {
                        bridge.beginCenter()
                        centerStep = 0
                        session = Session.CENTER
                    }
                }.show()
        } else if (session == Session.CENTER) {
            if (centerStep < 4) {
                runCommand("Center sample ${centerStep + 1}/4") {
                    bridge.sampleCenter()
                    centerStep++
                }
            } else {
                runCommand("Finish center calibration") {
                    bridge.endCenter() // Changes current working calibration (RAM)
                    session = Session.NONE
                    dirty = true
                    afterResult = null
                }
            }
        }
    }

    private fun rangeCoverage() = synchronized(samplesLock) {
        rangeCount >= 100 && (0..3).all { rangeMin[it] <= 12 && rangeMax[it] >= 243 }
    }
    private fun rangeClick() {
        val bridge = usb ?: return
        if (operationBusy || session == Session.FAULT || session == Session.CENTER) return
        if (session == Session.NONE) {
            if (!readyForCalibration()) return
            AlertDialog.Builder(this)
                .setTitle("Begin range calibration?")
                .setMessage("Rotate BOTH sticks slowly around the edges twice clockwise and twice anticlockwise. A partial range calibration can make the controller unusable until recalibrated. Continue only if hardware is sound.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Begin") { _, _ ->
                    synchronized(samplesLock) {
                        rangeMin.fill(255); rangeMax.fill(0); rangeCount = 0
                    }
                    session = Session.FAULT
                    runCommand("Start range calibration") {
                        bridge.beginRange()
                        rangeStarted = SystemClock.elapsedRealtime()
                        session = Session.RANGE
                    }
                }.show()
        } else {
            if (SystemClock.elapsedRealtime() - rangeStarted < 15_000 || !rangeCoverage()) {
                note("Cannot finish: rotate BOTH sticks fully through their travel for at least 15 seconds.")
                return
            }
            AlertDialog.Builder(this).setTitle("Finish range calibration?")
                .setMessage("All four axes reached near both ends. Apply this range to the working calibration (not permanent memory yet)?")
                .setNegativeButton("Keep rotating", null)
                .setPositiveButton("Apply") { _, _ ->
                    runCommand("Finish range calibration") {
                        bridge.endRange()
                        session = Session.NONE
                        dirty = true
                        afterResult = null
                    }
                }.show()
        }
    }

    private fun allowedSave(): Boolean {
        val base = baseline ?: return false
        val after = afterResult ?: return false
        return usb != null && !operationBusy && session == Session.NONE && dirty &&
            memory == DualSenseUsb.MemoryState.LOCKED && after.stable &&
            after.maximumOffsetPercent <= 15.0 &&
            after.maximumOffsetPercent <= base.maximumOffsetPercent + 0.5
    }
    private fun confirmSave() {
        val bridge = usb ?: return
        if (!allowedSave()) { note("Save blocked: connect, calibrate, then obtain a stable drift test that is not worse than baseline."); return }
        val field = EditText(this).apply {
            hint = "Type SAVE"; setSingleLine(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Permanent write — irreversible risk")
            .setMessage("This writes calibration to controller non-volatile memory. Keep USB connected, battery charged, and do not exit until finished. Type SAVE to authorize.")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Write to memory") { _, _ ->
                if (field.text.toString() != "SAVE") { note("Save cancelled: confirmation did not match SAVE."); return@setPositiveButton }
                runCommand("Save permanently") {
                    bridge.savePermanently()
                    dirty = false
                    memory = DualSenseUsb.MemoryState.LOCKED
                    baseline = afterResult
                    afterResult = null
                }
            }.show()
    }

    private fun pct(number: Double) = String.format(Locale.US, "%.2f%%", number)
    private fun renderResult(result: DriftResult, prefix: String): String {
        val quality = when {
            !result.stable -> "NOISY / UNSTABLE — check the hardware"
            result.maximumOffsetPercent < 1.25 -> "WITHIN 1 INPUT STEP"
            result.maximumOffsetPercent < 3.5 -> "MILD RESTING OFFSET"
            result.maximumOffsetPercent < 15 -> "MARKED OFFSET"
            else -> "SEVERE OFFSET — hardware inspection advised"
        }
        return "$prefix • $quality\nLeft offset ${pct(result.leftOffsetPercent)} · jitter ${pct(result.leftJitterPercent)}\nRight offset ${pct(result.rightOffsetPercent)} · jitter ${pct(result.rightJitterPercent)}\nSamples: ${result.samples}"
    }
    private fun showInstruction() {
        instructionText.text = when (session) {
            Session.NONE -> if (dirty) "Working calibration changed. Run another drift test, compare the result, then decide whether to save." else "Start with a stable baseline test. Calibrate only if the sticks are mechanically sound."
            Session.CENTER -> {
                val directions = listOf("TOP-LEFT", "TOP-RIGHT", "BOTTOM-LEFT", "BOTTOM-RIGHT")
                if (centerStep < 4) "Step ${centerStep + 1}/4: Move BOTH sticks to ${directions[centerStep]}, RELEASE them, then tap Sample."
                else "All 4 steps sampled. Tap Finish to apply working calibration; do NOT unplug yet."
            }
            Session.RANGE -> {
                val elapsed = (SystemClock.elapsedRealtime() - rangeStarted).coerceAtLeast(0) / 1000
                val coverage = synchronized(samplesLock) { (0..3).count { rangeMin[it] <= 12 && rangeMax[it] >= 243 } }
                "Rotate both sticks fully clockwise and anticlockwise. $elapsed / 15 seconds · $coverage / 4 axes covered. Finish only when all axes are covered."
            }
            Session.FAULT -> "Write path locked for safety. Power-cycle the controller before retrying. Do NOT send more calibration commands."
        }
    }
    private fun updateUi() {
        val connected = usb != null
        val locked = memory == DualSenseUsb.MemoryState.LOCKED
        statusText.text = if (connected) "●  Connected · USB Host" else "○  Controller not connected"
        statusText.setTextColor(if (connected) good else muted)
        memoryText.text = "Calibration memory: ${memory?.name ?: "not verified"}"
        connectButton.text = if (connected) "Disconnect" else "Connect DualSense"
        connectButton.isEnabled = !operationBusy
        testButton.isEnabled = connected && !operationBusy && session != Session.CENTER && session != Session.RANGE
        centerButton.isEnabled = connected && !operationBusy && locked && session != Session.FAULT && session != Session.RANGE
        centerButton.text = if (session == Session.CENTER) {
            if (centerStep < 4) "Sample position ${centerStep + 1}/4" else "Finish center calibration"
        } else "Guided center calibration"
        rangeButton.isEnabled = connected && !operationBusy && locked && session != Session.FAULT && session != Session.CENTER
        rangeButton.text = if (session == Session.RANGE) "Finish range calibration" else "Range calibration"
        saveButton.isEnabled = allowedSave()
        val before = baseline?.let { renderResult(it, "BEFORE") } ?: "No baseline."
        val after = afterResult?.let { "\n\n" + renderResult(it, "AFTER") } ?: ""
        resultText.text = before + after + if (dirty && afterResult == null) "\n\nChanges applied. Run after-test before saving." else ""
        showInstruction()
    }

    @Deprecated("Use platform back dispatcher in a future UI revision")
    override fun onBackPressed() {
        if (session == Session.CENTER || session == Session.RANGE || operationBusy) {
            AlertDialog.Builder(this)
                .setTitle("Calibration or USB operation in progress")
                .setMessage("Leaving now may interrupt a controller command or an open calibration session. Complete all steps without unplugging the controller.")
                .setPositiveButton("Stay in app", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(detachReceiver) } catch (_: Exception) { }
        usb?.close(); usb = null
        executor.shutdownNow()
        super.onDestroy()
    }
}
