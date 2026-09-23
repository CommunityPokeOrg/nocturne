package org.nocturne.emulator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Hosts the bundled `nocturned` daemon binary as a child process of the app.
 *
 * The daemon is packaged as `libnocturned.so` under `jniLibs/<abi>` so the
 * package manager installs it into `nativeLibraryDir` with execute
 * permission. In emulator mode the daemon reroots every device path under
 * `NOCTURNE_FS_ROOT` (app-private storage) and listens for the scripted
 * companion on a loopback SPP socket, so no real Bluetooth, ALSA, or sysfs
 * hardware is needed.
 */
class DaemonService : Service() {

    private var daemonProcess: java.lang.Process? = null
    private var logThread: Thread? = null
    private var supervisorThread: Thread? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            supervisorThread = Thread({ supervise() }, "nocturned-supervisor").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        daemonProcess?.destroy()
        daemonProcess = null
        supervisorThread?.interrupt()
        logThread?.interrupt()
        super.onDestroy()
    }

    private fun supervise() {
        var restartCount = 0
        while (running) {
            try {
                val process = spawnDaemon()
                daemonProcess = process
                logThread = Thread({ streamLogs(process) }, "nocturned-logs").also { it.start() }
                val exit = process.waitFor()
                daemonProcess = null
                if (!running) return
                // The daemon exits 0 on emulated reboot/factory-reset; restart
                // it to emulate the device power cycle. Crashes also restart,
                // with capped backoff.
                restartCount += 1
                Log.i(TAG, "nocturned exited with code $exit; restarting (cycle $restartCount)")
                Thread.sleep(RESTART_DELAY_MS)
            } catch (e: InterruptedException) {
                return
            } catch (e: IOException) {
                Log.e(TAG, "failed to spawn nocturned: ${e.message}")
                Thread.sleep(RESTART_DELAY_MS)
            }
        }
    }

    private fun spawnDaemon(): java.lang.Process {
        val files = filesDir
        val webapps = File(files, "webapps").apply { mkdirs() }
        val fsroot = File(files, "fsroot").apply { mkdirs() }
        extractAssets(webapps)

        val daemon = File(applicationInfo.nativeLibraryDir, "libnocturned.so")
        check(daemon.exists()) { "bundled daemon missing at ${daemon.absolutePath}" }
        daemon.setExecutable(true)

        val builder = ProcessBuilder(daemon.absolutePath)
            .directory(files)
            .redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", files.absolutePath)
            put("NOCTURNE_EMULATOR", "1")
            put("NOCTURNE_FS_ROOT", fsroot.absolutePath)
            put("NOCTURNE_WEBAPPS_DIR", File(webapps, "ui").absolutePath)
            put("NOCTURNE_SLOTS_STUB", "1")
            put("RUST_BACKTRACE", "1")
        }
        Log.i(TAG, "spawning ${daemon.absolutePath} (fsroot=$fsroot)")
        return builder.start()
    }

    private fun streamLogs(process: java.lang.Process) {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> Log.i("nocturned", line) }
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "daemon log stream ended: ${e.message}")
        }
    }

    private fun extractAssets(webappsDir: File) {
        val marker = File(webappsDir, ".extracted-${packageManager.getPackageInfo(packageName, 0).lastUpdateTime}")
        if (marker.exists()) return
        webappsDir.listFiles { f -> f.name.startsWith(".extracted-") }?.forEach { it.delete() }
        val queue = ArrayDeque(listOf("webapps"))
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val children = assets.list(path) ?: continue
            if (children.isEmpty()) {
                val out = File(filesDir, path)
                out.parentFile?.mkdirs()
                assets.open(path).use { input -> out.outputStream().use { input.copyTo(it) } }
            } else {
                File(filesDir, path).mkdirs()
                children.forEach { queue.addLast("$path/$it") }
            }
        }
        marker.createNewFile()
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "Nocturne daemon", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Nocturne emulator")
            .setContentText("nocturned is running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "NocturneDaemon"
        private const val CHANNEL_ID = "nocturned"
        private const val NOTIFICATION_ID = 1
        private const val RESTART_DELAY_MS = 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DaemonService::class.java))
        }
    }
}
