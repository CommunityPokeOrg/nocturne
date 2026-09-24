package org.nocturne.emulator.flasher

import java.io.IOException
import java.io.InputStream

/**
 * Progress metrics for a partition/disk write step.
 */
data class FlashProgress(
    val percent: Double,
    val elapsedMs: Double,
    val etaMs: Double,
    val rateKiBs: Double,
    val avgRateKiBs: Double,
)

/**
 * Amlogic SoC burn-mode protocol for the Spotify Car Thing.
 *
 * Faithful Kotlin port of the protocol used by flashthing
 * (github.com/JoeyEamigh/flashthing, MIT): vendor control requests drive
 * memory access and commands, bulk endpoints carry image payloads, and the
 * AMLC/AMLS handshake streams a u-boot blob through BL2 when the device is in
 * button-held USB mode rather than maskrom burn mode.
 */
class AmlogicDevice(
    private val usb: CarThingUsb.Session,
    private val sessionOpener: (() -> CarThingUsb.Session)?,
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val REQ_WRITE_MEM = 0x01
        const val REQ_READ_MEM = 0x02
        const val REQ_RUN_IN_ADDR = 0x05
        const val REQ_WR_LARGE_MEM = 0x11
        const val REQ_IDENTIFY_HOST = 0x20
        const val REQ_BULKCMD = 0x34
        const val REQ_GET_AMLC = 0x50
        const val REQ_WRITE_AMLC = 0x60

        const val ADDR_BL2 = 0xfffa0000L
        const val ADDR_TMP = 0x01080000L

        const val PART_SECTOR_SIZE = 512
        const val TRANSFER_BLOCK_SIZE = 8 * PART_SECTOR_SIZE // 4 KiB
        const val TRANSFER_SIZE_THRESHOLD = 8 * 1024 * 1024
        const val ERASE_GROUP_SECTORS = 8 * 1024 // 4 MiB
        const val FLAG_KEEP_POWER_ON = 0x10L

        const val AMLC_AMLS_BLOCK_LENGTH = 0x200
        const val AMLC_MAX_BLOCK_LENGTH = 0x4000
        const val AMLC_MAX_TRANSFER_LENGTH = 65536

        const val COMMAND_TIMEOUT = CarThingUsb.COMMAND_TIMEOUT_MS
        const val RESET_SETTLE_MS = 5000L
    }

    /** Release the USB session (e.g. before a bl2 re-enumeration). */
    fun close() = usb.close()

    private fun hi16(addr: Long) = ((addr shr 16) and 0xffff).toInt()
    private fun lo16(addr: Long) = (addr and 0xffff).toInt()

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(),
        ((v shr 24) and 0xff).toByte(),
    )

    private fun u32le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)

    /** Write <= 64 bytes to device memory. */
    fun writeSimpleMemory(address: Long, data: ByteArray, length: Int = data.size) {
        require(length <= 64) { "writeSimpleMemory max 64 bytes" }
        usb.controlOut(REQ_WRITE_MEM, hi16(address), lo16(address), data.copyOf(length), COMMAND_TIMEOUT)
    }

    /** Write arbitrary data to memory in 64-byte control chunks. */
    fun writeMemory(address: Long, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val chunk = minOf(64, data.size - offset)
            writeSimpleMemory(address + offset, data.copyOfRange(offset, offset + chunk))
            offset += chunk
        }
    }

    fun readSimpleMemory(address: Long, length: Int): ByteArray {
        require(length in 1..64) { "readSimpleMemory max 64 bytes" }
        val buf = ByteArray(length)
        val read = usb.controlIn(REQ_READ_MEM, hi16(address), lo16(address), buf, COMMAND_TIMEOUT)
        if (read != length) throw IOException("incomplete memory read: $read/$length")
        return buf
    }

    fun readMemory(address: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val chunk = readSimpleMemory(address + offset, minOf(64, length - offset))
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    /** Execute code at [address]. keepPower ORs FLAG_KEEP_POWER_ON into the payload. */
    fun run(address: Long, keepPower: Boolean = true) {
        val data = if (keepPower) address or FLAG_KEEP_POWER_ON else address
        usb.controlOut(REQ_RUN_IN_ADDR, hi16(address), lo16(address), le32(data), COMMAND_TIMEOUT)
    }

    /** Read the 8-byte identify string. */
    fun identify(): String {
        val buf = ByteArray(8)
        val read = usb.controlIn(REQ_IDENTIFY_HOST, 0, 0, buf, COMMAND_TIMEOUT)
        if (read != 8) throw IOException("failed to read identify data ($read/8)")
        return String(buf)
    }

    /**
     * Stage [data] in device RAM at [memoryAddress]. Pads to a [blockLength]
     * multiple when [appendZeros]; bulk-writes one block at a time.
     */
    fun writeLargeMemory(memoryAddress: Long, data: ByteArray, length: Int = data.size, blockLength: Int, appendZeros: Boolean) {
        var payload = data.copyOf(length)
        if (appendZeros) {
            val rem = payload.size % blockLength
            if (rem != 0) payload = payload.copyOf(payload.size + (blockLength - rem))
        } else {
            require(payload.size % blockLength == 0) { "large data must be a multiple of block length" }
        }

        val blockCount = payload.size / blockLength
        val control = ByteArray(16)
        le32(memoryAddress).copyInto(control, 0)
        le32(payload.size.toLong()).copyInto(control, 4)
        usb.controlOut(REQ_WR_LARGE_MEM, blockLength, blockCount, control, COMMAND_TIMEOUT)

        var offset = 0
        while (offset < payload.size) {
            usb.bulkOut(payload, offset, blockLength, 2000)
            offset += blockLength
        }
    }

    private fun writeAmlcData(amlcOffset: Long, data: ByteArray, length: Int = data.size, dataOffset: Int = 0) {
        usb.controlOut(
            REQ_WRITE_AMLC,
            (amlcOffset / AMLC_AMLS_BLOCK_LENGTH).toInt(),
            length - 1,
            ByteArray(0),
            COMMAND_TIMEOUT,
        )

        var remaining = length
        var offset = dataOffset
        while (remaining > 0) {
            val blockLength = minOf(remaining, AMLC_MAX_BLOCK_LENGTH)
            var retries = 0
            var done = false
            while (!done && retries < 3) {
                val written = usb.bulkOut(data, offset, blockLength, 1000)
                if (written == blockLength) {
                    done = true
                } else {
                    retries += 1
                    Thread.sleep(100)
                }
            }
            if (!done) throw IOException("incomplete AMLC bulk write")
            offset += blockLength
            remaining -= blockLength
            Thread.sleep(10)
        }

        val ackBuf = ByteArray(16)
        var read = 0
        var retries = 0
        while (retries < 3) {
            read = usb.bulkIn(ackBuf, 1000)
            if (read >= 4) break
            retries += 1
            Thread.sleep(100)
        }
        if (read < 4) throw IOException("no AMLC acknowledgment")
        val ack = String(ackBuf, 0, 4)
        if (ack != "OKAY") throw IOException("invalid AMLC data write ack: $ack")
    }

    private fun amlcChecksum(data: ByteArray, length: Int = data.size, dataOffset: Int = 0): Long {
        var checksum = 0L
        var offset = dataOffset
        val end = dataOffset + length
        while (offset < end) {
            val remaining = end - offset
            val value: Long = when {
                remaining >= 4 -> u32le(data, offset).also { offset += 4 }
                remaining >= 3 -> {
                    val v = u32le(byteArrayOf(data[offset], data[offset + 1], data[offset + 2], 0), 0) and 0xffffff
                    offset += 3
                    v
                }
                remaining >= 2 -> {
                    val v = (data[offset].toLong() and 0xff) or ((data[offset + 1].toLong() and 0xff) shl 8)
                    offset += 2
                    v
                }
                else -> {
                    val v = data[offset].toLong() and 0xff
                    offset += 1
                    v
                }
            }
            checksum = (checksum + value) % 0x1_0000_0000L
        }
        return checksum
    }

    /**
     * Send one AMLC packet: [data] split into 64 KiB transfers, then a 0x200
     * "AMLS" block (seq + checksum header + trailing payload bytes) at
     * [amlcOffset].
     */
    fun writeAmlcDataPacket(seq: Int, amlcOffset: Long, data: ByteArray, length: Int = data.size, dataOffset: Int = 0) {
        if (length > 0) {
            var offset = 0
            while (offset < length) {
                val writeLength = minOf(AMLC_MAX_TRANSFER_LENGTH, length - offset)
                writeAmlcData(offset.toLong(), data, writeLength, dataOffset + offset)
                Thread.sleep(50)
                offset += writeLength
            }
        }

        val checksum = amlcChecksum(data, length, dataOffset)

        val amls = ByteArray(AMLC_AMLS_BLOCK_LENGTH)
        "AMLS".toByteArray().copyInto(amls, 0) // AMLS (not AMLC) for the final block
        amls[4] = seq.toByte()
        le32(checksum).copyInto(amls, 8)
        if (length > 16) {
            val copyLen = minOf(AMLC_AMLS_BLOCK_LENGTH - 16, length - 16)
            data.copyInto(amls, 16, dataOffset + 16, dataOffset + 16 + copyLen)
        }

        writeAmlcData(amlcOffset, amls)
    }

    /** Poll the BL2 stage for the next (length, offset) window it wants. */
    private fun getBootAmlc(): Pair<Long, Long> {
        usb.controlOut(REQ_GET_AMLC, AMLC_AMLS_BLOCK_LENGTH, 0, ByteArray(0), COMMAND_TIMEOUT)
        val buf = ByteArray(AMLC_AMLS_BLOCK_LENGTH)
        val read = usb.bulkIn(buf, 2000)
        if (read < AMLC_AMLS_BLOCK_LENGTH) throw IOException("no AMLC data received")
        val tag = String(buf, 0, 4)
        if (tag != "AMLC") throw IOException("invalid amlc request: $tag")
        val length = u32le(buf, 8)
        val offset = u32le(buf, 12)
        val ack = ByteArray(16)
        "OKAY".toByteArray().copyInto(ack, 0)
        usb.bulkOut(ack, 0, ack.size, 2000)
        return length to offset
    }

    /**
     * Stream [bootloader] through the BL2 AMLC handshake (device must already
     * be opened in GX-CHIP "USB mode"). Does not re-acquire afterwards.
     */
    fun bl2Stream(bl2: ByteArray, bootloader: ByteArray) {
        log("sending bl2 to 0x${ADDR_BL2.toString(16)}")
        writeLargeMemory(ADDR_BL2, bl2, blockLength = 4096, appendZeros = true)
        run(ADDR_BL2, keepPower = true)
        Thread.sleep(2000)

        var prevLength = 0L
        var prevOffset = 0L
        var seq = 0
        var iterations = 0
        while (true) {
            if (iterations >= 50) throw IOException("maximum iterations reached in bl2 boot")
            iterations += 1

            var retryCount = 0
            val (length, offset) = run {
                var result: Pair<Long, Long>? = null
                while (result == null) {
                    try {
                        result = getBootAmlc()
                    } catch (e: IOException) {
                        retryCount += 1
                        if (retryCount >= 3) throw e
                        Thread.sleep(500)
                    }
                }
                result
            }

            if (length == prevLength && offset == prevOffset) break
            prevLength = length
            prevOffset = offset

            if (offset >= bootloader.size) {
                writeAmlcDataPacket(seq, offset, ByteArray(0))
            } else {
                val actualLength = minOf(length.toInt(), bootloader.size - offset.toInt())
                writeAmlcDataPacket(seq, offset, bootloader, actualLength, offset.toInt())
            }
            seq = (seq + 1) and 0xff
            Thread.sleep(100)
        }
        log("bl2 boot sequence completed")
    }

    /**
     * Boot into burn mode and re-acquire the re-enumerated device. Returns a
     * new [AmlogicDevice] bound to the post-reset session, or this instance if
     * the device was already in burn mode.
     */
    fun bl2Boot(bl2: ByteArray, bootloader: ByteArray): AmlogicDevice {
        bl2Stream(bl2, bootloader)
        // The SoC resets and re-enumerates; the old connection is dead.
        runCatching { usb.close() }
        Thread.sleep(RESET_SETTLE_MS)
        val opener = sessionOpener ?: throw IOException("no session re-acquire path configured")
        return AmlogicDevice(opener(), sessionOpener, log)
    }

    /**
     * Send a bulk command string (NUL-terminated) and read the response.
     * Fails unless the response contains "success" (case-insensitive).
     */
    fun bulkcmd(command: String): String {
        val cmd = command.toByteArray(Charsets.US_ASCII) + 0.toByte()
        usb.controlOut(REQ_BULKCMD, 0, 0, cmd, COMMAND_TIMEOUT)
        val buf = ByteArray(512)
        val read = usb.bulkIn(buf, COMMAND_TIMEOUT)
        if (read == 0) throw IOException("no response for bulk command")
        val start = buf.indexOfFirst { it != 0.toByte() }.let { if (it < 0) 0 else it }
        var end = read
        while (end > start && buf[end - 1] == 0.toByte()) end--
        val response = String(buf, start, end - start)
        if (!response.lowercase().contains("success")) {
            throw IOException("bulkcmd failed: $response")
        }
        return response
    }

    /** Probe that a partition reports the expected size (superbird layout). */
    fun validatePartitionSize(partName: String, sizeSectors: Long, altSizeSectors: Long? = null): Long {
        if (partName == "cache") throw IOException("cache partition is zero-length on superbird")
        if (partName == "reserved") throw IOException("reserved partition cannot be accessed")

        fun probe(sizeSectorsProbe: Long): Boolean = try {
            val partSize = sizeSectorsProbe * PART_SECTOR_SIZE
            bulkcmd("amlmmc read $partName 0x${ADDR_TMP.toString(16)} 0x${(partSize - PART_SECTOR_SIZE).toString(16)} 0x${PART_SECTOR_SIZE.toString(16)}")
            true
        } catch (_: IOException) {
            false
        }

        val primary = sizeSectors * PART_SECTOR_SIZE
        if (probe(sizeSectors)) return primary

        if (partName == "data" && altSizeSectors != null && probe(altSizeSectors)) {
            return altSizeSectors * PART_SECTOR_SIZE
        }
        throw IOException("partition $partName failed size validation")
    }

    /** Write a boot hwpartition (1 = boot0, 2 = boot1) wholesale. */
    fun writeBootPartition(hwpart: Int, data: ByteArray) {
        require(hwpart in 1..2) { "boot hwpart must be 1 or 2" }
        require(data.size <= TRANSFER_SIZE_THRESHOLD) { "boot partition payload exceeds single-transfer cap" }
        bulkcmd("mmc dev 1 $hwpart")
        bulkcmd("amlmmc key")
        writeLargeMemory(ADDR_TMP, data, blockLength = TRANSFER_BLOCK_SIZE, appendZeros = true)
        val sectorCount = (data.size + PART_SECTOR_SIZE - 1) / PART_SECTOR_SIZE
        bulkcmd("mmc write 0x${ADDR_TMP.toString(16)} 0 0x${sectorCount.toString(16)}")
        bulkcmd("mmc dev 1 0")
    }

    /** Stream [size] bytes from [source] into the eMMC user area at [lbaOffset]. */
    fun writeUserArea(
        lbaOffset: Long,
        source: InputStream,
        dataSize: Long,
        sparse: Boolean,
        progress: (FlashProgress) -> Unit,
    ) {
        bulkcmd("mmc dev 1 0")
        bulkcmd("amlmmc key")

        var erasedFrom = 0L
        var erasedTo = 0L
        if (sparse) {
            val spanSectors = (dataSize + PART_SECTOR_SIZE - 1) / PART_SECTOR_SIZE
            val first = lbaOffset
            val start = ((first + ERASE_GROUP_SECTORS - 1) / ERASE_GROUP_SECTORS) * ERASE_GROUP_SECTORS
            val end = (first + spanSectors) / ERASE_GROUP_SECTORS * ERASE_GROUP_SECTORS
            if (end > start) {
                val count = end - start
                log("erasing $count sectors at LBA $start before sparse write")
                bulkcmd("mmc erase 0x${start.toString(16)} 0x${count.toString(16)}")
                erasedFrom = (start - first) * PART_SECTOR_SIZE
                erasedTo = (end - first) * PART_SECTOR_SIZE
            }
        }

        val buffer = ByteArray(TRANSFER_SIZE_THRESHOLD)
        var offset = 0L
        val startTime = System.nanoTime()

        while (offset < dataSize) {
            val chunkStart = System.nanoTime()
            val writeLength = minOf(TRANSFER_SIZE_THRESHOLD.toLong(), dataSize - offset).toInt()
            readFully(source, buffer, writeLength)

            val erased = offset >= erasedFrom && offset + writeLength <= erasedTo
            var allZero = true
            for (i in 0 until writeLength) {
                if (buffer[i] != 0.toByte()) {
                    allZero = false
                    break
                }
            }
            val skip = sparse && erased && allZero

            if (!skip) {
                writeLargeMemory(ADDR_TMP, buffer, writeLength, TRANSFER_BLOCK_SIZE, appendZeros = true)
                val chunkLba = lbaOffset + offset / PART_SECTOR_SIZE
                val chunkSectors = writeLength / PART_SECTOR_SIZE

                var retries = 0
                while (true) {
                    try {
                        val cmdStart = System.nanoTime()
                        bulkcmd("mmc write 0x${ADDR_TMP.toString(16)} 0x${chunkLba.toString(16)} 0x${chunkSectors.toString(16)}")
                        if ((System.nanoTime() - cmdStart) / 1_000_000 > 3000) Thread.sleep(5000)
                        break
                    } catch (e: IOException) {
                        retries += 1
                        if (retries >= 3) throw e
                        log("mmc write failed at LBA 0x${chunkLba.toString(16)}, retry $retries/3")
                        Thread.sleep(5000)
                    }
                }
            }

            offset += writeLength
            progress(reportProgress(chunkStart, startTime, offset, dataSize, writeLength))
        }
    }

    /** Restore a named superbird partition from [source] ([fileSize] bytes). */
    fun restorePartition(
        partName: String,
        partSize: Long,
        source: InputStream,
        fileSize: Long,
        progress: (FlashProgress) -> Unit,
    ) {
        val adjustedPartSize = if (partName == "bootloader") 2L * 1024 * 1024 else partSize
        if (fileSize > adjustedPartSize && partName != "bootloader") {
            throw IOException("file is larger than target partition: $fileSize vs $adjustedPartSize")
        }

        bulkcmd("amlmmc key")

        val buffer = ByteArray(TRANSFER_SIZE_THRESHOLD)
        var offset = 0L
        val startTime = System.nanoTime()

        while (offset < fileSize) {
            val chunkStart = System.nanoTime()
            val writeLength = minOf(TRANSFER_SIZE_THRESHOLD.toLong(), fileSize - offset).toInt()
            readFully(source, buffer, writeLength)
            writeLargeMemory(ADDR_TMP, buffer, writeLength, TRANSFER_BLOCK_SIZE, appendZeros = true)

            if (partName == "bootloader") {
                try {
                    bulkcmd("amlmmc write $partName 0x${ADDR_TMP.toString(16)} 0x${offset.toString(16)} 0x${writeLength.toString(16)}")
                } catch (_: IOException) {
                    // bootloader writes time out on superbird by design
                }
                Thread.sleep(2000)
            } else {
                var retries = 0
                while (true) {
                    try {
                        val cmdStart = System.nanoTime()
                        bulkcmd("amlmmc write $partName 0x${ADDR_TMP.toString(16)} 0x${offset.toString(16)} 0x${writeLength.toString(16)}")
                        if ((System.nanoTime() - cmdStart) / 1_000_000 > 3000) Thread.sleep(5000)
                        break
                    } catch (e: IOException) {
                        retries += 1
                        if (retries >= 3) throw e
                        log("write command failed, retry $retries/3")
                        Thread.sleep(5000)
                    }
                }
            }

            offset += writeLength
            progress(reportProgress(chunkStart, startTime, offset, fileSize, writeLength))
        }
    }

    /** Write [size] bytes from [source] to raw eMMC at [diskAddress]. */
    fun writeLargeMemoryToDisk(
        diskAddress: Long,
        source: InputStream,
        dataSize: Long,
        blockLength: Int,
        appendZeros: Boolean,
        progress: (FlashProgress) -> Unit,
    ) {
        bulkcmd("mmc dev 1")
        bulkcmd("amlmmc key")

        val buffer = ByteArray(TRANSFER_SIZE_THRESHOLD)
        var offset = 0L
        val startTime = System.nanoTime()

        while (offset < dataSize) {
            val chunkStart = System.nanoTime()
            val writeLength = minOf(TRANSFER_SIZE_THRESHOLD.toLong(), dataSize - offset).toInt()
            readFully(source, buffer, writeLength)

            writeLargeMemory(ADDR_TMP, buffer, writeLength, blockLength, appendZeros)

            var retries = 0
            while (true) {
                try {
                    val cmdStart = System.nanoTime()
                    bulkcmd(
                        "mmc write 0x${ADDR_TMP.toString(16)} " +
                            "0x${((diskAddress + offset) / PART_SECTOR_SIZE).toString(16)} " +
                            "0x${(writeLength / PART_SECTOR_SIZE).toString(16)}",
                    )
                    if ((System.nanoTime() - cmdStart) / 1_000_000 > 3000) Thread.sleep(5000)
                    break
                } catch (e: IOException) {
                    retries += 1
                    if (retries >= 3) throw e
                    Thread.sleep(5000)
                }
            }

            offset += writeLength
            progress(reportProgress(chunkStart, startTime, offset, dataSize, writeLength))
        }
    }

    /** Write a u-boot environment text blob via `env import`. */
    fun writeEnv(envData: ByteArray) {
        if (!envData.all { it >= 0 }) throw IOException("env data must be ascii")
        bulkcmd("amlmmc env")
        writeLargeMemory(ADDR_TMP, envData, blockLength = TRANSFER_BLOCK_SIZE, appendZeros = true)
        bulkcmd("env import -t 0x${ADDR_TMP.toString(16)} 0x${envData.size.toString(16)}")
    }

    private fun reportProgress(chunkStart: Long, startTime: Long, offset: Long, total: Long, chunkLen: Int): FlashProgress {
        val elapsed = (System.nanoTime() - startTime) / 1_000_000.0
        val chunkSecs = (System.nanoTime() - chunkStart) / 1_000_000_000.0
        val bytesPerSec = if (elapsed > 0) offset / (elapsed / 1000.0) else offset.toDouble()
        val eta = if (bytesPerSec > 0) (total - offset) / bytesPerSec * 1000.0 else 0.0
        return FlashProgress(
            percent = offset.toDouble() / total * 100.0,
            elapsedMs = elapsed,
            etaMs = eta,
            rateKiBs = chunkLen / chunkSecs / 1024.0,
            avgRateKiBs = bytesPerSec / 1024.0,
        )
    }

    private fun readFully(source: InputStream, buf: ByteArray, length: Int) {
        var read = 0
        while (read < length) {
            val n = source.read(buf, read, length - read)
            if (n < 0) throw IOException("unexpected end of payload stream")
            read += n
        }
    }
}
