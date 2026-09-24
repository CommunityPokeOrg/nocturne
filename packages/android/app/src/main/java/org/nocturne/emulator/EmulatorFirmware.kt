package org.nocturne.emulator

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Safe "firmware" import for the emulator — the counterpart of the USB
 * flasher's destructive write path.
 *
 * The emulator only ever runs the bundled `nocturned` + a webapp bundle, so
 * "loading firmware" here means replacing the served webapp under
 * `filesDir/webapps-imported/ui` (picked up by [DaemonService] on the next
 * daemon spawn) and optionally staging a handful of device-identity files
 * under the virtual rootfs (`fsroot/` entries in the archive). Raw partition
 * images inside flashthing/stock dumps are NOT touched: nothing here writes
 * partitions, spawns foreign binaries, or pretends USB hardware exists.
 *
 * Archive layout accepted:
 *   - a webapp bundle anywhere in the zip, located by its `index.html`;
 *     a directory literally named `ui/` is preferred (e.g. `webapps/ui/`,
 *     `dist/ui/`, `ui/`), otherwise the shallowest `index.html` wins
 *   - `fsroot/etc/superbird` and `fsroot/proc/cmdline` override the emulated
 *     device identity; every other `fsroot/` entry is ignored
 */
object EmulatorFirmware {

    /** fsroot-relative paths an archive may stage; device identity only. */
    private val FSROOT_ALLOWLIST = setOf("etc/superbird", "proc/cmdline")
    private const val FSROOT_PREFIX = "fsroot/"

    data class Plan(
        /** Entry prefix of the webapp bundle ("" or ".../" form). */
        val bundleRoot: String,
        /** Number of files under the bundle root. */
        val uiFiles: Int,
        /** fsroot-relative identity files present in the archive. */
        val identityFiles: List<String>,
    )

    data class ImportResult(
        val plan: Plan,
        val importedTo: File,
    )

    /** Analyze an archive without extracting; null when it carries no webapp. */
    fun plan(zip: ZipFile): Plan? {
        val bundleRoot = findBundleRoot(zip) ?: return null
        val uiFiles = zip.entries().asSequence()
            .count { !it.isDirectory && it.name.startsWith(bundleRoot) }
        val identityFiles = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith(FSROOT_PREFIX) }
            .map { it.name.removePrefix(FSROOT_PREFIX) }
            .filter { it in FSROOT_ALLOWLIST }
            .toList()
        return Plan(bundleRoot, uiFiles, identityFiles)
    }

    /**
     * Extract the archive's webapp bundle into `importedDir/ui` (atomically
     * swapped via a staging dir) and stage any allowlisted identity files into
     * `fsroot`. Throws IOException on anything malformed.
     */
    fun importPackage(zipFile: File, importedDir: File, fsroot: File): ImportResult {
        val staging = File(importedDir, "ui.next")
        val current = File(importedDir, "ui")

        ZipFile(zipFile).use { zip ->
            val plan = plan(zip)
                ?: throw IOException(
                    "no webapp bundle in archive (no index.html) — " +
                        "flashthing partition images like stock dumps or Mira " +
                        "cannot be imported; they need real hardware flashing",
                )
            staging.deleteRecursively()
            if (!staging.mkdirs()) throw IOException("cannot create $staging")
            try {
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(plan.bundleRoot) }
                    .forEach { entry ->
                        val dest = File(staging, entry.name.removePrefix(plan.bundleRoot))
                        checkContained(staging, dest)
                        dest.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        }
                    }

                if (!File(staging, "index.html").isFile) {
                    throw IOException("extracted bundle is missing index.html")
                }

                plan.identityFiles.forEach { relative ->
                    val dest = File(fsroot, relative)
                    checkContained(fsroot, dest)
                    dest.parentFile?.mkdirs()
                    zip.getInputStream(zip.getEntry(FSROOT_PREFIX + relative)).use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                }
            } catch (e: Throwable) {
                staging.deleteRecursively()
                throw e
            }

            // Swap in only after every file landed cleanly.
            current.deleteRecursively()
            if (!staging.renameTo(current)) {
                staging.deleteRecursively()
                throw IOException("cannot move staged webapp into place")
            }
            return ImportResult(plan, current)
        }
    }

    /** Remove an imported bundle so the next daemon spawn serves the bundled UI. */
    fun reset(importedDir: File) {
        importedDir.deleteRecursively()
    }

    /** Directory prefix of the archive's `index.html`, or null when absent. */
    fun findBundleRoot(zip: ZipFile): String? {
        val roots = zip.entries().asSequence()
            .filter { !it.isDirectory && (it.name.endsWith("/index.html") || it.name == "index.html") }
            .map { it.name.removeSuffix("index.html") }
            .toList()
        if (roots.isEmpty()) return null
        val named = roots.filter { it.removeSuffix("/").substringAfterLast('/') == "ui" }
        return (named.ifEmpty { roots }).minByOrNull { it.length }
    }

    private fun checkContained(root: File, dest: File) {
        if (!dest.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            throw IOException("archive entry escapes destination: ${dest.name}")
        }
    }
}
