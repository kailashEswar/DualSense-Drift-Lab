package io.driftlab.dualsense

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.res.ColorStateList
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
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    companion object { private const val ACTION_USB_PERMISSION = "io.driftlab.dualsense.USB_PERMISSION" }
    private enum class Session { NONE, CENTER, RANGE, FAULT }
    private val background = Color.rgb(245, 248, 253)
    private val surface = Color.rgb(255, 255, 255)
    private val accent = Color.rgb(19, 101, 232)
    private val ink = Color.rgb(30, 47, 72)
    private val muted = Color.rgb(105, 121, 145)
    private val good = Color.rgb(18, 137, 99)
    private val warning = Color.rgb(171, 100, 26)

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
    // USB reports arrive on a worker thread. Keep only the most recent report for
    // the next display frame; never queue a runnable for every HID packet.
    @Volatile private var latestSample: StickSample? = null
    private val uiFramePending = AtomicBoolean(false)
    private var lastTelemetryAt = 0L
    private var lastRangeLabelAt = 0L
    private val displayFrame = Choreographer.FrameCallback {
        uiFramePending.set(false)
        val sample = latestSample
        if (usb != null && sample != null && !isFinishing && !isDestroyed) {
            leftPlot.setPosition(sample.lx, sample.ly)
            rightPlot.setPosition(sample.rx, sample.ry)
            finePlot?.setPosition(if (fineStick == 0) sample.lx else sample.rx,
                if (fineStick == 0) sample.ly else sample.ry)
            val now = SystemClock.elapsedRealtime()
            // Updating text layout every USB report is costly; plots stay frame-synced.
            if (now - lastTelemetryAt >= 80L) {
                rawText.text = "LX ${sample.lx}    LY ${sample.ly}    RX ${sample.rx}    RY ${sample.ry}"
                fineLiveText?.text = if (fineStick == 0)
                    "Live X ${sample.lx} · Y ${sample.ly} (8-bit USB reports)"
                else "Live X ${sample.rx} · Y ${sample.ry} (8-bit USB reports)"
                lastTelemetryAt = now
            }
            if (session == Session.RANGE && now - lastRangeLabelAt >= 400L) {
                showInstruction()
                lastRangeLabelAt = now
            }
        }
    }
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
    private lateinit var finetuneButton: Button
    private var finePlot: StickPlotView? = null
    private var fineLiveText: TextView? = null
    private var fineStick = 0
    private var zoomEnabled = false
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
    private fun shape(color: Int, radius: Int = 16, bordered: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (bordered) setStroke(dp(1), Color.rgb(225, 232, 243))
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
        box.background = shape(surface, 17, bordered = true)
        box.setPadding(dp(16), dp(16), dp(16), dp(16))
        box.addView(label(title, 17f, ink, true))
        if (subtitle.isNotEmpty()) {
            val l = label(subtitle, 12f, muted)
            l.setPadding(0, dp(6), 0, dp(13))
            box.addView(l)
        }
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return box
    }
    private fun action(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = shape(Color.WHITE, 10)
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.rgb(174, 187, 205), accent)
        )
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
        val scroll = ScrollView(this).apply {
            setBackgroundColor(this@MainActivity.background)
            setFillViewport(true)
            isVerticalScrollBarEnabled = false
        }
        val root = vertical()
        root.setPadding(dp(16), dp(21), dp(16), dp(32))
        scroll.addView(root)
        setContentView(scroll)
        root.addView(label("DUALSENSE  /  ANDROID", 11f, accent, true))
        root.addView(label("Drift Calibration", 29f, ink, true).apply {
            setPadding(0, dp(4), 0, dp(4))
        })
        root.addView(label("Native USB calibration & live joystick diagnostics", 13f).apply {
            setPadding(0, 0, 0, dp(18))
        })

        val device = section(root, "Controller Info", "DualSense (054C:0CE6)  •  Wired USB-C connection")
        statusText = textInfo(device, "Not connected")
        memoryText = label("Memory status: —", 12f, muted).also {
            it.setPadding(0, dp(8), 0, 0); device.addView(it)
        }
        connectButton = action("Connect DualSense") { connectOrDisconnect() }.also { device.addView(it) }

        val live = section(root, "Joystick Info", "Responsive live input — synchronized with your display")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val lcol = vertical().apply { gravity = Gravity.CENTER }
        val rcol = vertical().apply { gravity = Gravity.CENTER }
        leftPlot = StickPlotView(this); rightPlot = StickPlotView(this)
        lcol.addView(label("LEFT STICK", 11f, muted, true).apply { gravity = Gravity.CENTER })
        lcol.addView(leftPlot)
        rcol.addView(label("RIGHT STICK", 11f, muted, true).apply { gravity = Gravity.CENTER })
        rcol.addView(rightPlot)
        row.addView(lcol, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(rcol, LinearLayout.LayoutParams(0, -2, 1f))
        live.addView(row)
        rawText = label("LX —    LY —    RX —    RY —", 12f, muted, true)
        rawText.gravity = Gravity.CENTER
        rawText.setPadding(0, dp(10), 0, dp(4))
        live.addView(rawText)
        live.addView(label("Plot mode (coordinates remain unchanged)", 12f, muted).apply {
            setPadding(0, dp(8), 0, dp(3))
        })
        val zoomOptions = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val normalRadio = RadioButton(this).apply { text = "Normal"; id = View.generateViewId(); isChecked = true }
        val zoomRadio = RadioButton(this).apply { text = "10x zoom"; id = View.generateViewId() }
        zoomOptions.addView(normalRadio)
        zoomOptions.addView(zoomRadio)
        zoomOptions.setOnCheckedChangeListener { _, checkedId ->
            zoomEnabled = checkedId == zoomRadio.id
            leftPlot.setZoomEnabled(zoomEnabled)
            rightPlot.setZoomEnabled(zoomEnabled)
        }
        live.addView(zoomOptions)
        live.addView(label("10x enlarges the nearest 5% of travel to half the dial. It does not amplify actual input precision.", 11f, muted))

        val drift = section(root, "Quick Test / Drift Diagnosis", "Release both sticks for 3.5 seconds to measure resting offset")
        resultText = textInfo(drift, "Run a drift test to establish a baseline before calibrating.")
        testButton = action("Run drift test") { driftTest() }.also { drift.addView(it) }

        val cal = section(root, "Stick Calibration", "Guided center and range calibration · Do not unplug during a step")
        instructionText = textInfo(cal, "Start with a stable baseline test. Calibrate only if the sticks are mechanically sound.")
        centerButton = action("Guided center calibration") { centerClick() }.also { cal.addView(it) }
        rangeButton = action("Range calibration") { rangeClick() }.also { cal.addView(it) }
        finetuneButton = action("Precise adjustment / Fine-tune") { openFinetune() }.also { cal.addView(it) }
        cal.addView(label("Adjust raw center / circularity parameters in small steps. Changes are staged, then applied to controller RAM only with confirmation.", 12f, muted).apply {
            setPadding(0, dp(8), 0, 0)
        })

        val save = section(root, "Save Changes Permanently", "Requires valid calibration and a stable post-calibration test")
        save.addView(label("Saving changes to NVS is potentially irreversible and can damage calibration. Test the result first.", 13f, warning))
        saveButton = action("Save permanently") { confirmSave() }.also { save.addView(it) }

        val events = section(root, "Diagnostic Log", "Local-only · No account or network required")
        logText = label("Connect a controller to begin.", 12f, muted)
        events.addView(logText)
        root.addView(label("UNOFFICIAL · NOT AFFILIATED WITH SONY. Recalibration cannot repair physically worn sticks.", 11f).apply {
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
            latestSample = null
            leftPlot.reset(); rightPlot.reset()
            rawText.text = "LX —    LY —    RX —    RY —"
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
        latestSample = null
        leftPlot.reset(); rightPlot.reset()
        finePlot?.reset()
        rawText.text = "LX —    LY —    RX —    RY —"
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
        // The USB reader never waits for a UI redraw and never queues more than
        // one display callback. Frames use the newest available real sample.
        latestSample = sample
        if (uiFramePending.compareAndSet(false, true)) {
            runOnUiThread {
                if (usb != null && !isFinishing && !isDestroyed) {
                    Choreographer.getInstance().postFrameCallback(displayFrame)
                } else {
                    uiFramePending.set(false)
                }
            }
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

    /**
     * A guarded version of the original project's fine-tune dialog. The native app
     * offers explicit, small adjustments and only sends the complete 12-field block
     * after user confirmation. The persistent NVS save stays a separate action.
     */
    private fun openFinetune() {
        val bridge = usb ?: return
        if (!readyForCalibration()) return
        val epoch = connectionEpoch
        runCommand("Read raw fine-tune parameters") {
            val original = bridge.readFinetuneData()
            runOnUiThread {
                if (epoch == connectionEpoch && usb === bridge && !isFinishing) {
                    showFinetuneDialog(bridge, original)
                }
            }
        }
    }

    private fun showFinetuneDialog(bridge: DualSenseUsb, original: IntArray) {
        var working = original.copyOf()
        var stepSize = 1
        var circularity = false
        fineStick = 0

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(layout) }
        layout.addView(label("Changes are staged locally until Apply. Release the sticks before changing center values.", 13f, warning))

        val stickChoice = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val left = RadioButton(this).apply { text = "Left stick"; id = View.generateViewId(); isChecked = true }
        val right = RadioButton(this).apply { text = "Right stick"; id = View.generateViewId() }
        stickChoice.addView(left); stickChoice.addView(right)
        layout.addView(stickChoice)

        val modeChoice = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val centerMode = RadioButton(this).apply { text = "Center"; id = View.generateViewId(); isChecked = true }
        val rangeMode = RadioButton(this).apply { text = "Circularity"; id = View.generateViewId() }
        modeChoice.addView(centerMode); modeChoice.addView(rangeMode)
        layout.addView(modeChoice)

        val zoomSwitch = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val normal = RadioButton(this).apply { text = "Normal"; id = View.generateViewId() }
        val zoom = RadioButton(this).apply { text = "10x zoom"; id = View.generateViewId(); isChecked = true }
        zoomSwitch.addView(normal); zoomSwitch.addView(zoom)
        layout.addView(zoomSwitch)
        val plotContainer = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val plot = StickPlotView(this).apply { setZoomEnabled(true) }
        finePlot = plot
        latestSample?.let { plot.setPosition(it.lx, it.ly) }
        plotContainer.addView(plot)
        layout.addView(plotContainer)
        fineLiveText = label("Raw joystick coordinates (USB input)", 12f, muted)
        fineLiveText?.gravity = Gravity.CENTER
        layout.addView(fineLiveText)
        layout.addView(label("Step size (raw 16-bit calibration units)", 12f, muted).apply {
            setPadding(0, dp(12), 0, 0)
        })
        val stepChoice = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (number in intArrayOf(1, 5, 10, 15)) {
            val button = RadioButton(this).apply {
                text = number.toString()
                id = View.generateViewId()
                isChecked = number == 1
                setOnClickListener { stepSize = number }
            }
            stepChoice.addView(button)
        }
        layout.addView(stepChoice)

        val fieldsBox = vertical()
        layout.addView(fieldsBox)
        val stageStatus = label("No changes staged.", 12f, muted)
        stageStatus.setPadding(0, dp(10), 0, dp(8))
        layout.addView(stageStatus)
        val reset = Button(this).apply { text = "Reset staged adjustments"; isAllCaps = false }
        layout.addView(reset)

        fun renderFields() {
            fieldsBox.removeAllViews()
            val fields = PrecisionCalibration.fields(fineStick, circularity)
            val changed = working.indices.count { working[it] != original[it] }
            stageStatus.text = if (changed == 0) "No changes staged — nothing written to controller."
                else "$changed calibration field(s) modified. Not yet sent to controller."
            fields.forEach { index ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(label(PrecisionCalibration.names[index], 14f, ink, true),
                    LinearLayout.LayoutParams(0, -2, 0.75f))
                val decrease = Button(this).apply { text = "−"; isAllCaps = false }
                row.addView(decrease, LinearLayout.LayoutParams(0, dp(46), 0.75f))
                val number = label(working[index].toString(), 13f, ink, true).apply { gravity = Gravity.CENTER }
                row.addView(number, LinearLayout.LayoutParams(0, -2, 1.2f))
                val increase = Button(this).apply { text = "+"; isAllCaps = false }
                row.addView(increase, LinearLayout.LayoutParams(0, dp(46), 0.75f))
                fieldsBox.addView(row)
                fun change(delta: Int) {
                    // Center tuning must be done with the stick released.
                    if (!circularity) {
                        val latest = latestSample
                        val x = if (fineStick == 0) latest?.lx else latest?.rx
                        val y = if (fineStick == 0) latest?.ly else latest?.ry
                        if (x == null || y == null || kotlin.math.abs(x - 128) > 35 || kotlin.math.abs(y - 128) > 35) {
                            stageStatus.text = "Release the selected stick near center before changing center values."
                            return
                        }
                    }
                    try {
                        working = PrecisionCalibration.adjust(original, working, index, delta)
                        renderFields()
                    } catch (e: IllegalArgumentException) { stageStatus.text = e.message ?: "Adjustment rejected" }
                }
                decrease.setOnClickListener { change(-stepSize) }
                increase.setOnClickListener { change(stepSize) }
            }
        }
        stickChoice.setOnCheckedChangeListener { _, checkedId ->
            fineStick = if (checkedId == left.id) 0 else 1
            latestSample?.let {
                plot.setPosition(if (fineStick == 0) it.lx else it.rx, if (fineStick == 0) it.ly else it.ry)
            }
            renderFields()
        }
        modeChoice.setOnCheckedChangeListener { _, checkedId ->
            circularity = checkedId == rangeMode.id
            renderFields()
        }
        zoomSwitch.setOnCheckedChangeListener { _, checkedId -> plot.setZoomEnabled(checkedId == zoom.id) }
        reset.setOnClickListener { working = original.copyOf(); renderFields() }
        renderFields()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Precise stick calibration")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply to RAM", null)
            .create()
        dialog.setOnDismissListener {
            if (finePlot === plot) finePlot = null
            fineLiveText = null
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (working.contentEquals(original)) {
                    stageStatus.text = "No changes to apply."
                    return@setOnClickListener
                }
                if (usb !== bridge || session != Session.NONE || operationBusy) {
                    stageStatus.text = "Connection or calibration state changed; reconnect to retry."
                    return@setOnClickListener
                }
                if (!readyForCalibration()) return@setOnClickListener
                // Bound each value to a small change from device-provided original.
                if (working.indices.any { kotlin.math.abs(working[it] - original[it]) > PrecisionCalibration.MAX_SESSION_DELTA }) {
                    stageStatus.text = "Session safety limit exceeded."
                    return@setOnClickListener
                }
                val payload = working.copyOf()
                AlertDialog.Builder(this)
                    .setTitle("Apply fine-tune changes to controller RAM?")
                    .setMessage("This will send actual calibration values to the DualSense. The change is NOT a permanent save yet. Keep USB connected and charged. A failed write may require a controller power-cycle. Continue?")
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Apply to RAM") { _, _ ->
                        dialog.dismiss()
                        // If any write fails, lock the app's write path until power-cycle.
                        session = Session.FAULT
                        runCommand("Fine-tune RAM calibration") {
                            bridge.writeFinetuneData(payload)
                            dirty = true
                            afterResult = null
                            session = Session.NONE
                        }
                    }.show()
            }
        }
        dialog.show()
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
        finetuneButton.isEnabled = connected && !operationBusy && locked && session == Session.NONE
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
