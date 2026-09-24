package org.nocturne.emulator

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.ZipFile

/**
 * Imports a Nocturne-compatible "firmware" payload into the emulator: a zip
 * carrying a webapp bundle (index.html, ideally under a `ui/` directory) plus
 * optional `fsroot/` device-identity files. The daemon restarts and serves the
 * imported UI in place of the bundled one.
 *
 * This is deliberately NOT the flasher: no USB, no partition writes, nothing
 * touches real hardware. Foreign firmware that ships raw partition images
 * (stock dumps, Mira) cannot be imported — extracting its userspace would
 * require running a different daemon, which this emulator does not emulate.
 */
class ImportActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var packageSummary: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var pickButton: Button
    private lateinit var importButton: Button
    private lateinit var resetButton: Button

    private var picked: File? = null
    private var plan: EmulatorFirmware.Plan? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
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

        content.addView(text("Load firmware into emulator", 22f))
        content.addView(text(
            "Replace the emulated Car Thing's webapp (and optionally its device\n" +
                "identity) from a zip archive, then restart the daemon.\n\n" +
                "Accepted payloads:\n" +
                "  - a built webapp bundle (index.html + assets) at the archive\n" +
                "    root or under a ui/ directory, e.g. webapps/ui/\n" +
                "  - optional fsroot/etc/superbird and fsroot/proc/cmdline to\n" +
                "    override the emulated serial / MAC / build metadata\n\n" +
                "This only rewrites files in the emulator's private storage. It\n" +
                "cannot flash or harm a real Car Thing, and firmware images whose\n" +
                "payload lives inside raw partitions (stock dumps, Mira) are\n" +
                "rejected — the emulator has no system emulator to boot them.",
        ))

        status = text("webapp: " + if (importedUiDir().let { File(it, "index.html").isFile }) {
            "imported (custom bundle active)"
        } else {
            "bundled"
        }, 16f)
        content.addView(status)

        pickButton = Button(this).apply {
            text = "Choose firmware / webapp archive (.zip)"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    },
                    REQ_PICK_ARCHIVE,
                )
            }
        }
        content.addView(pickButton)

        packageSummary = text("no archive selected")
        content.addView(packageSummary)

        importButton = Button(this).apply {
            text = "Import and restart daemon"
            isEnabled = false
            setOnClickListener { runImport() }
        }
        content.addView(importButton)

        resetButton = Button(this).apply {
            text = "Restore bundled UI"
            isEnabled = File(importedUiDir(), "index.html").isFile
            setOnClickListener { runReset() }
        }
        content.addView(resetButton)

        logView = TextView(this).apply {
            setTextColor(0xFF9E9E9E.toInt())
            textSize = 12f
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        content.addView(logScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (LOG_HEIGHT_DP * resources.displayMetrics.density).toInt(),
        ))

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun importedUiDir(): File = File(filesDir, "webapps-imported/ui")
    private fun importedDir(): File = File(filesDir, "webapps-imported")
    private fun fsroot(): File = File(filesDir, "fsroot")

    private fun updateButtons() {
        importButton.isEnabled = !busy && plan != null
        resetButton.isEnabled = !busy && File(importedUiDir(), "index.html").isFile
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_ARCHIVE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        analyze(uri)
    }

    private fun analyze(uri: Uri) {
        packageSummary.text = "reading archive..."
        plan = null
        updateButtons()
        Thread {
            try {
                val dest = File(cacheDir, "picked-import.zip")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: throw IOException("cannot open selected file")

                val found = ZipFile(dest).use { EmulatorFirmware.plan(it) }
                    ?: throw IOException(
                        "no webapp bundle in archive (no index.html) — partition-image " +
                            "firmware like stock dumps or Mira cannot be imported",
                    )
                runOnUiThread {
                    picked = dest
                    plan = found
                    packageSummary.text = buildString {
                        append("webapp bundle at '${found.bundleRoot.ifEmpty { "." }}' ")
                        append("(${found.uiFiles} files)\n")
                        if (found.identityFiles.isNotEmpty()) {
                            append("identity overrides: ${found.identityFiles.joinToString(", ")}\n")
                        }
                        append("\nNote: the imported UI must speak the nocturned WebSocket\n")
                        append("protocol (port 5000). Foreign UIs will render but stay dead.")
                    }
                    updateButtons()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    picked = null
                    plan = null
                    packageSummary.text = "failed to read archive: ${e.message}"
                    updateButtons()
                }
            }
        }.start()
    }

    private fun runImport() {
        val file = picked ?: return
        busy = true
        updateButtons()
        appendLog("importing ${file.name}...")
        Thread {
            try {
                val result = EmulatorFirmware.importPackage(file, importedDir(), fsroot())
                appendLog(
                    "imported ${result.plan.uiFiles} files to ${result.importedTo.name}" +
                        if (result.plan.identityFiles.isNotEmpty()) {
                            "; staged ${result.plan.identityFiles.joinToString(", ")}"
                        } else "",
                )
                restartDaemon()
            } catch (e: Throwable) {
                appendLog("FAILED: ${e.message}")
            } finally {
                busy = false
                runOnUiThread { updateButtons(); refreshStatus() }
            }
        }.start()
    }

    private fun runReset() {
        busy = true
        updateButtons()
        appendLog("restoring bundled UI...")
        Thread {
            try {
                EmulatorFirmware.reset(importedDir())
                appendLog("imported bundle removed")
                restartDaemon()
            } catch (e: Throwable) {
                appendLog("FAILED: ${e.message}")
            } finally {
                busy = false
                runOnUiThread { updateButtons(); refreshStatus() }
            }
        }.start()
    }

    private fun restartDaemon() {
        appendLog("restarting daemon...")
        DaemonService.restart(this)
        // Wait for the webapp port to come back so the user returns to a
        // daemon already serving the new bundle.
        repeat(300) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", 8080), 200) }
                appendLog("daemon is back on :8080")
                return
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        appendLog("daemon did not answer on :8080 in time — check logcat")
    }

    private fun refreshStatus() {
        status.text = "webapp: " + if (File(importedUiDir(), "index.html").isFile) {
            "imported (custom bundle active)"
        } else {
            "bundled"
        }
    }

    private fun appendLog(line: String) = runOnUiThread {
        logView.append(line + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val LOG_HEIGHT_DP = 180
        private const val REQ_PICK_ARCHIVE = 43
    }
}
