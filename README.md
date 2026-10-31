# DualSense Drift Lab — Native Android prototype

**Status: 0.1.0-alpha — source code, not a tested APK.**

A standalone native Android app to connect a standard PS5 DualSense via
USB-C/OTG, display live analog stick readings, measure resting-stick offset and
jitter, and send guarded calibration commands. It does **not** use WebHID,
Chrome, WebView, or a hosted backend.

## Compatibility

- Android 8.0+ with USB Host/OTG support and a USB-C **data** cable.
- **Standard Sony DualSense VID 054C / PID 0CE6 only**.
- No Bluetooth calibration. No DualSense Edge, DualShock 4, third-party clones.
- Device-specific Android USB policies/other HID drivers may stop interface
  claiming or HID feature reports. Not tested on physical hardware.

## Build an installable APK

### Option A — Android Studio

1. Extract the ZIP and open the `DualSenseDriftLabAndroid` folder as a project.
2. Install Android SDK Platform 35 and JDK 17 from Android Studio's tools.
3. Let Android Studio sync the Gradle project (AGP 8.7.3 / Kotlin 2.0.21).
4. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Find `app/build/outputs/apk/debug/app-debug.apk` and install on the phone.

The project deliberately excludes a Gradle wrapper JAR. Android Studio should
use a configured Gradle 8.9 installation, or generate a wrapper using `gradle
wrapper --gradle-version 8.9` on a computer with Gradle installed.

### Option B — GitHub Actions, builds APK in the cloud

1. Create a GitHub repository and upload **the contents of this folder**.
2. Go to **Actions → Build Android debug APK → Run workflow**.
3. Download the artifact called `DualSenseDriftLab-debug-APK` after a successful
   build. The archive contains `app-debug.apk`.

The workflow runs Kotlin/JUnit tests before building the APK. It uses a debug
signature and is **not** suitable for Play Store release without further QA.

## How to use, only after confirming USB input works

1. Connect a charged DualSense to the Android phone via a data cable or OTG
   adapter. Do not touch either stick.
2. Tap **Connect DualSense**; approve the USB device permission.
3. Check for live LX/LY/RX/RY values and *Calibration memory: LOCKED*.
4. Tap **Run drift test** (3.5 s). This is read-only and establishes a baseline.
5. If the signal is stable and the offset is correctable, you can carefully
   choose **Guided center calibration**. For each corner, move both sticks to the
   indicated direction, release fully, and tap Sample. Finish the 4-step wizard.
6. Alternatively **Range calibration** requires 15+ seconds of rotating BOTH
   sticks fully clockwise and anticlockwise; all four axes must reach the
   specified extremes before Finish is allowed.
7. Run the drift test again. Permanent saving is disabled if the latest after
   test is noisier/unstable, severely offset or >0.5 percentage points worse
   than baseline.
8. **Save permanently** requires explicit text `SAVE`, previously confirmed
   locked NVS, and a passing after-test. It can still be risky.

If calibration fails or is interrupted, **do not immediately restart it**.
Power-cycle the controller and reconnect. The app refuses auto-repair/commit
of an incomplete calibration. `Disconnect` is not a reliable substitute for
power-cycling.

## Safety and important limitations

- This is an **unverified prototype**. I had no physical DualSense or Android
  phone connected to this environment and cannot guarantee that a specific
  firmware accepts the USB control transfers or that any calibration succeeds.
- An interrupted calibration can leave a hardware calibration session open.
  Automatically "canceling" it by sending End might *commit a partial pass*;
  this app deliberately does not do that.
- The code requires the USB HID feature-report lengths to match the standard
  DualSense report descriptor before enabling commands. Unexpected devices
  fail closed instead of guessing packet sizes.
- The NVS unlock→lock operation may persist the working calibration. A failed
  lock operation could leave memory in an unknown state; the app stops writes.
- Calibration **cannot repair stick drift caused by worn/dirty/damaged
  potentiometers or worn springs**. A badly worn stick may become worse.
- Before trusting calibration functionality, verify connecting/read-only tests
  on a specific Android phone + standard DualSense firmware, then test writes
  only with hardware you can afford to repair/replace.
- No Internet permission, telemetry, analytics, or account storage.

## Technical structure

```
app/src/main/java/io/driftlab/dualsense/
  MainActivity.kt    Android activity, safety state machine, permission & workflow
  DualSenseUsb.kt    Native USB connection, interrupt IN, HID GET/SET reports
  HidReportSizes.kt Descriptor parser to determine exact feature-report lengths
  DriftAnalyzer.kt  Read-only offset / noise computations
  StickPlotView.kt  Custom live circular plots
app/src/test/       Pure-logic tests
.github/workflows/  CI debug APK build on GitHub
```

The app uses Android `UsbManager` and `UsbDeviceConnection.controlTransfer` for
feature reports, and asynchronous `UsbRequest` reads for the interrupt IN HID
endpoint. The tested protocol baseline uses report IDs `0x80`/`0x81` for memory
status and `0x82`/`0x83` for stick calibration, guarded by expected replies.

See `THIRD_PARTY_NOTICES.md` for original research and attribution. Unofficial;
not affiliated with or endorsed by Sony/PlayStation.
