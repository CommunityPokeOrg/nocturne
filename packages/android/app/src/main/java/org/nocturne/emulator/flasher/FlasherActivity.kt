package org.nocturne.emulator.flasher

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spotify Car Thing firmware flasher.
 *
 * Lets the user pick any flashthing-format firmware package (a zip carrying a
 * Terbium-style meta.json — e.g. Nocturne release zips — or a stock dump
 * without one) and flashes it to a Car Thing connected over USB OTG in
 * Amlogic burn mode.
 *
 * Safety: the write path is only armed after an explicit checkbox + a
 * confirmation dialog spelling out the bricking risk, and the step plan is
 * shown before anything touches the device.
 */
class FlasherActivity : Activity() {

    private lateinit var usbManager: UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var deviceStatus: TextView
    private lateinit var packageSummary: TextView
    private lateinit var logView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var confirmCheck: CheckBox
    private lateinit var pickButton: Button
    private lateinit var flashButton: Button

    private var packageFile: File? = null
    private var packageConfig: FlashConfig? = null
    private var flashing = false

    private var permissionLatch: CountDownLatch? = null
    private var permissionGranted = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            permissionLatch?.countDown()
        }
    }

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshDeviceStatus()
    }

    private val pollStatus = object : Runnable {
        override fun run() {
            refreshDeviceStatus()
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        registerCompat(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerCompat(attachReceiver, attachFilter)

        buildUi()
        mainHandler.post(pollStatus)
        handleAttachIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAttachIntent(intent)
    }

    private fun handleAttachIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) refreshDeviceStatus()
    }

    private fun registerCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pollStatus)
        unregisterReceiver(permissionReceiver)
        unregisterReceiver(attachReceiver)
        super.onDestroy()
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.BLACK)
        }

        fun text(value: String, size: Float = 14f): TextView = TextView(this).apply {
            text = value
            setTextColor(0xFFE0E0E0.toInt())
            textSize = size
        }

        content.addView(text("Car Thing Firmware Flasher", 22f))
        content.addView(text(
            "Flash a Spotify Car Thing firmware package over USB.\n\n" +
                "1. Power on the Car Thing while holding buttons 1 and 4.\n" +
                "2. Connect it to this device with a USB-C cable (OTG adapter if needed).\n" +
                "3. Pick a flashthing-format firmware zip (meta.json inside) or a stock dump zip.\n" +
                "4. Confirm you understand the risk, then flash.",
        ))

        deviceStatus = text("device: checking...", 16f)
        content.addView(deviceStatus)

        pickButton = Button(this).apply {
            text = "Choose firmware package (.zip)"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    },
                    REQ_PICK_FIRMWARE,
                )
            }
        }
        content.addView(pickButton)

        packageSummary = text("no firmware package selected")
        content.addView(packageSummary)

        confirmCheck = CheckBox(this).apply {
            text = "I understand flashing writes to the Car Thing's eMMC and a bad image or interrupted write can require a maskrom recovery."
            setTextColor(0xFFFFB74D.toInt())
            setOnCheckedChangeListener { _, _ -> updateFlashEnabled() }
        }
        content.addView(confirmCheck)

        flashButton = Button(this).apply {
            text = "Flash firmware"
            isEnabled = false
            setOnClickListener { confirmAndFlash() }
        }
        content.addView(flashButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        content.addView(progressBar)

        progressText = text("")
        content.addView(progressText)

        logView = TextView(this).apply {
            setTextColor(0xFF9E9E9E.toInt())
            textSize = 12f
        }
        // Bounded height keeps the log independently scrollable inside the
        // outer ScrollView without it growing to push the buttons offscreen.
        logScroll = ScrollView(this).apply {
            addView(logView)
        }
        content.addView(logScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (LOG_HEIGHT_DP * resources.displayMetrics.density).toInt(),
        ))

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun updateFlashEnabled() {
        flashButton.isEnabled = !flashing && confirmCheck.isChecked && packageConfig != null
    }

    private fun refreshDeviceStatus() {
        val detected = CarThingUsb.detect(usbManager)
        val line = when (detected?.mode) {
            null, DeviceMode.NotFound ->
                "device: not found — hold buttons 1 and 4 while powering on the Car Thing"
            DeviceMode.Normal ->
                "device: booted normally — unplug, then power on while holding buttons 1 and 4"
            DeviceMode.Fastboot ->
                "device: in fastboot mode — power on while holding buttons 1 and 4 for burn mode"
            DeviceMode.Usb ->
                "device: USB mode (buttons held) — will be BL2-booted into burn mode on flash"
            DeviceMode.UsbBurn ->
                "device: burn mode ready (1b8e:c003)"
        }
        deviceStatus.text = line
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_FIRMWARE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        loadPackage(uri)
    }

    private fun loadPackage(uri: Uri) {
        packageSummary.text = "reading package..."
        Thread {
            try {
                // ZipFile needs a real file: copy the picked document into cache.
                val dest = File(cacheDir, "picked-firmware.zip")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: throw IOException("cannot open selected file")

                val stockMeta = assets.open(STOCK_META_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
                val pkg = FirmwarePackage.ZipPackage(dest, stockMeta)
                val stock = java.util.zip.ZipFile(dest).use { it.getEntry("meta.json") == null }
                pkg.close()

                runOnUiThread {
                    packageFile = dest
                    packageConfig = pkg.config
                    val kind = if (stock) "stock dump" else "flashthing package"
                    packageSummary.text = buildString {
                        append("${pkg.config.name} ${pkg.config.version} ($kind)\n")
                        if (pkg.config.description.isNotBlank()) append("${pkg.config.description}\n")
                        append("${pkg.config.steps.size} flash steps:")
                        pkg.config.steps.forEach { append("\n  - ${it.describe()}") }
                    }
                    updateFlashEnabled()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    packageConfig = null
                    packageFile = null
                    packageSummary.text = "failed to read package: ${e.message}"
                    updateFlashEnabled()
                }
            }
        }.start()
    }

    private fun confirmAndFlash() {
        val config = packageConfig ?: return
        AlertDialog.Builder(this)
            .setTitle("Flash ${config.name}?")
            .setMessage(
                "This writes firmware to the Spotify Car Thing's eMMC. " +
                    "Keep the device connected for the whole flash — an interrupted write can leave " +
                    "it needing a maskrom recovery. Only continue if this package is meant for the Car Thing.",
            )
            .setPositiveButton("Flash") { _, _ -> startFlash() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startFlash() {
        val file = packageFile ?: return
        flashing = true
        updateFlashEnabled()
        pickButton.isEnabled = false
        progressBar.progress = 0
        appendLog("starting flash of ${packageConfig?.name}")

        Thread {
            try {
                val stockMeta = assets.open(STOCK_META_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
                val bl2 = assets.open(BL2_ASSET).use { it.readBytes() }
                val bootloader = assets.open(BOOTLOADER_ASSET).use { it.readBytes() }

                FirmwarePackage.ZipPackage(file, stockMeta).use { pkg ->
                    FlashEngine(
                        usbManager = usbManager,
                        pkg = pkg,
                        bl2 = bl2,
                        bootloader = bootloader,
                        requestPermission = ::requestUsbPermission,
                        emit = ::onFlashEvent,
                    ).run()
                }
            } catch (e: Throwable) {
                onFlashEvent(FlashEvent.Failed(e.message ?: e.toString()))
            }
        }.start()
    }

    /** Blocking permission request called from the flash worker thread. */
    private fun requestUsbPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        val latch = CountDownLatch(1)
        permissionGranted = false
        permissionLatch = latch
        val pi = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, pi)
        val granted = latch.await(120, TimeUnit.SECONDS) && permissionGranted
        permissionLatch = null
        return granted
    }

    private fun onFlashEvent(event: FlashEvent) = runOnUiThread {
        when (event) {
            is FlashEvent.ModeDetected -> appendLog("device mode: ${event.mode}")
            is FlashEvent.Status -> {
                progressText.text = event.text
                appendLog(event.text)
            }
            is FlashEvent.StepStarted -> {
                progressText.text = "step ${event.index}/${event.total}: ${event.description}"
                appendLog("[${event.index}/${event.total}] ${event.description}")
            }
            is FlashEvent.StepProgress -> {
                val p = event.progress
                progressBar.progress = (p.percent * 10).toInt().coerceIn(0, 1000)
                progressText.text = "%.1f%% — %.0f KiB/s, ETA %.0fs".format(p.percent, p.rateKiBs, p.etaMs / 1000.0)
            }
            is FlashEvent.LogLine -> appendLog(event.text)
            is FlashEvent.Done -> {
                progressText.text = "flash complete — unplug and power-cycle the Car Thing"
                appendLog("flash complete")
                finishFlash()
            }
            is FlashEvent.Failed -> {
                progressText.text = "flash failed: ${event.error}"
                appendLog("FAILED: ${event.error}")
                finishFlash()
            }
        }
    }

    private fun finishFlash() {
        flashing = false
        pickButton.isEnabled = true
        updateFlashEnabled()
    }

    private fun appendLog(line: String) {
        logView.append(line + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val LOG_HEIGHT_DP = 180
        private const val REQ_PICK_FIRMWARE = 42
        private const val ACTION_USB_PERMISSION = "org.nocturne.emulator.USB_PERMISSION"
        private const val BL2_ASSET = "flasher/superbird.bl2.encrypted.bin"
        private const val BOOTLOADER_ASSET = "flasher/superbird.bootloader.img"
        private const val STOCK_META_ASSET = "flasher/stock-meta.json"
    }
}
