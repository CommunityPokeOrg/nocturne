package org.nocturne.emulator.flasher

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * A Car Thing firmware package: a flashthing-format zip (meta.json plus image
 * files), a plain directory, or a stock dump directory/zip that pairs with the
 * bundled stock meta.json. Resolves the filePath references that flash steps
 * point at.
 */
sealed class FirmwarePackage : AutoCloseable {
    abstract fun readAll(path: String): ByteArray
    abstract fun open(path: String): Pair<Long, InputStream>
    abstract val config: FlashConfig

    protected fun entryName(path: String): String = path.removePrefix("./")

    /** Zip archive containing meta.json + payloads. */
    class ZipPackage(file: File, stockMeta: String? = null) : FirmwarePackage() {
        private val zip = ZipFile(file)

        override val config: FlashConfig = run {
            val metaEntry = zip.getEntry("meta.json")
            val metaJson = when {
                metaEntry != null -> zip.getInputStream(metaEntry).use { it.readBytes().toString(Charsets.UTF_8) }
                stockMeta != null -> stockMeta
                else -> throw IOException("no meta.json in archive (not a flashthing or stock package)")
            }
            FlashConfig.parse(metaJson)
        }

        override fun readAll(path: String): ByteArray {
            val entry = zip.getEntry(entryName(path))
                ?: throw IOException("missing archive member: $path")
            return zip.getInputStream(entry).use { it.readBytes() }
        }

        override fun open(path: String): Pair<Long, InputStream> {
            val entry = zip.getEntry(entryName(path))
                ?: throw IOException("missing archive member: $path")
            return entry.size to zip.getInputStream(entry)
        }

        override fun close() = zip.close()
    }

    /** Directory containing meta.json + payloads. */
    class DirPackage(private val base: File, stockMeta: String? = null) : FirmwarePackage() {
        override val config: FlashConfig = run {
            val meta = File(base, "meta.json")
            val metaJson = when {
                meta.isFile -> meta.readText()
                stockMeta != null -> stockMeta
                else -> throw IOException("no meta.json in $base (not a flashthing or stock package)")
            }
            FlashConfig.parse(metaJson)
        }

        private fun resolve(path: String): File {
            val f = File(base, entryName(path))
            if (!f.canonicalPath.startsWith(base.canonicalPath)) {
                throw IOException("file path escapes package root: $path")
            }
            if (!f.isFile) throw IOException("missing file: $path")
            return f
        }

        override fun readAll(path: String): ByteArray = resolve(path).readBytes()

        override fun open(path: String): Pair<Long, InputStream> {
            val f = resolve(path)
            return f.length() to f.inputStream().buffered()
        }

        override fun close() {}
    }

    /** Inline JSON plan with files resolved relative to a directory. */
    class StandalonePackage(private val base: File, metaJson: String) : FirmwarePackage() {
        override val config: FlashConfig = FlashConfig.parse(metaJson)

        override fun readAll(path: String): ByteArray = File(base, entryName(path)).readBytes()
        override fun open(path: String): Pair<Long, InputStream> {
            val f = File(base, entryName(path))
            return f.length() to f.inputStream().buffered()
        }

        override fun close() {}
    }
}
