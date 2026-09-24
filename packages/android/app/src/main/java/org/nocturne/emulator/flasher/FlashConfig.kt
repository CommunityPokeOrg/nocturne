package org.nocturne.emulator.flasher

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Superbird partition geometry in 512-byte sectors, extracted from the
 * device's `amlmmc part 1` output (same table flashthing ships).
 */
object SuperbirdPartitions {
    data class Info(val offset: Long, val size: Long, val sizeAlt: Long?)

    private val table = mapOf(
        "bootloader" to Info(0, 4096, null),
        "reserved" to Info(73728, 131072, null),
        "cache" to Info(221184, 0, null),
        "env" to Info(237568, 16384, null),
        "fip_a" to Info(270336, 8192, null),
        "fip_b" to Info(294912, 8192, null),
        "logo" to Info(319488, 16384, null),
        "dtbo_a" to Info(352256, 8192, null),
        "dtbo_b" to Info(376832, 8192, null),
        "vbmeta_a" to Info(401408, 2048, null),
        "vbmeta_b" to Info(419840, 2048, null),
        "boot_a" to Info(438272, 32768, null),
        "boot_b" to Info(487424, 32768, null),
        "system_a" to Info(536576, 1056856, null),
        "system_b" to Info(1609816, 1056856, null),
        "misc" to Info(2683056, 16384, null),
        "settings" to Info(2715824, 524288, null),
        // some devices report a smaller data partition
        "data" to Info(3256496, 4476752, 4378448),
    )

    operator fun get(name: String): Info? = table[name]
}

/** A payload reference: either an inline byte array or a file inside the package. */
sealed class DataOrFile {
    data class Inline(val data: ByteArray) : DataOrFile()
    data class File(val filePath: String) : DataOrFile()
}

/** One step of a flashthing-compatible meta.json flash plan. */
sealed class FlashStep {
    abstract fun describe(): String

    data class Bulkcmd(val command: String) : FlashStep() {
        override fun describe() = "command: $command"
    }

    data class Run(val address: Long, val keepPower: Boolean?) : FlashStep() {
        override fun describe() = "run at 0x${address.toString(16)}"
    }

    data class WriteSimpleMemory(val address: Long, val data: DataOrFile) : FlashStep() {
        override fun describe() = "write memory at 0x${address.toString(16)}"
    }

    data class WriteLargeMemory(
        val address: Long,
        val data: DataOrFile,
        val blockLength: Int,
        val appendZeros: Boolean?,
    ) : FlashStep() {
        override fun describe() = "write disk at 0x${address.toString(16)}"
    }

    data class WriteAMLCData(val seq: Int, val amlcOffset: Long, val data: DataOrFile) : FlashStep() {
        override fun describe() = "write AMLC packet at 0x${amlcOffset.toString(16)}"
    }

    data class Bl2Boot(val bl2: DataOrFile, val bootloader: DataOrFile) : FlashStep() {
        override fun describe() = "boot through BL2"
    }

    data class RestorePartition(val name: String, val data: DataOrFile) : FlashStep() {
        override fun describe() = "restore partition $name"
    }

    data class WriteBootPartition(val hwpart: Int, val data: DataOrFile) : FlashStep() {
        override fun describe() = "write boot$hwpart partition"
    }

    data class WriteUserArea(val lba: Long, val data: DataOrFile, val sparse: Boolean) : FlashStep() {
        override fun describe() = "write user area at LBA $lba"
    }

    data class WriteEnv(val env: DataOrFile) : FlashStep() {
        override fun describe() = "write u-boot environment"
    }

    data class Log(val message: String) : FlashStep() {
        override fun describe() = "log: $message"
    }

    data class Wait(val timeMs: Long) : FlashStep() {
        override fun describe() = "wait ${timeMs}ms"
    }
}

class UnsupportedStepException(message: String) : IOException(message)

/**
 * Parsed flashthing `meta.json` (Terbium meta subset). Versions 1-2 accepted;
 * the same step types flashthing refuses (identify, reads, getBootAMLC,
 * bulkcmdStat, validatePartitionSize, wait for user input) are rejected.
 */
data class FlashConfig(
    val name: String,
    val version: String,
    val description: String,
    val steps: List<FlashStep>,
) {
    companion object {
        private const val META_MIN = 1
        private const val META_MAX = 2

        fun parse(json: String): FlashConfig {
            val root = JSONObject(json)
            val metaVersion = root.getInt("metadataVersion")
            if (metaVersion < META_MIN || metaVersion > META_MAX) {
                throw IOException("unsupported meta.json version: $metaVersion")
            }
            val steps = root.getJSONArray("steps")
            val parsed = (0 until steps.length()).map { parseStep(steps.getJSONObject(it)) }
            return FlashConfig(
                name = root.optString("name", "unnamed firmware"),
                version = root.optString("version", "?"),
                description = root.optString("description", ""),
                steps = parsed,
            )
        }

        private fun dataOrFile(node: Any?): DataOrFile = when (node) {
            is JSONObject -> DataOrFile.File(node.getString("filePath"))
            is JSONArray -> {
                val bytes = ByteArray(node.length())
                for (i in 0 until node.length()) bytes[i] = (node.getInt(i) and 0xff).toByte()
                DataOrFile.Inline(bytes)
            }
            else -> throw IOException("invalid data/file reference: $node")
        }

        private fun parseStep(obj: JSONObject): FlashStep {
            return when (val type = obj.getString("type")) {
                "identify", "bulkcmdStat", "readSimpleMemory", "readLargeMemory",
                "getBootAMLC", "validatePartitionSize",
                -> throw UnsupportedStepException("unsupported meta.json step: $type")

                "bulkcmd" -> FlashStep.Bulkcmd(obj.getString("value"))

                "run" -> {
                    val v = obj.getJSONObject("value")
                    FlashStep.Run(v.getLong("address"), if (v.has("keepPower")) v.getBoolean("keepPower") else null)
                }

                "writeSimpleMemory" -> {
                    val v = obj.getJSONObject("value")
                    FlashStep.WriteSimpleMemory(v.getLong("address"), dataOrFile(v.get("data")))
                }

                "writeLargeMemory" -> {
                    val v = obj.getJSONObject("value")
                    FlashStep.WriteLargeMemory(
                        v.getLong("address"),
                        dataOrFile(v.get("data")),
                        v.getInt("blockLength"),
                        if (v.has("appendZeros")) v.getBoolean("appendZeros") else null,
                    )
                }

                "writeAMLCData" -> {
                    val v = obj.getJSONObject("value")
                    FlashStep.WriteAMLCData(v.getInt("seq"), v.getLong("amlcOffset"), dataOrFile(v.get("data")))
                }

                "bl2Boot" -> {
                    val v = obj.getJSONObject("value")
                    FlashStep.Bl2Boot(dataOrFile(v.get("bl2")), dataOrFile(v.get("bootloader")))
                }

                "restorePartition" -> {
                    val v = obj.getJSONObject("value")
                    FlashStep.RestorePartition(v.getString("name"), dataOrFile(v.get("data")))
                }

                "writeBootPartition" -> {
                    val v = obj.getJSONObject("value")
                    FlashStep.WriteBootPartition(v.getInt("hwpart"), dataOrFile(v.get("data")))
                }

                "writeUserArea" -> {
                    val v = obj.getJSONObject("value")
                    FlashStep.WriteUserArea(
                        v.getLong("lba"),
                        dataOrFile(v.get("data")),
                        v.optBoolean("sparse", false),
                    )
                }

                "writeEnv" -> {
                    when (val v = obj.get("value")) {
                        is String -> FlashStep.WriteEnv(DataOrFile.Inline(v.toByteArray(Charsets.US_ASCII)))
                        else -> FlashStep.WriteEnv(dataOrFile(v))
                    }
                }

                "log" -> FlashStep.Log(obj.getString("value"))

                "wait" -> {
                    val v = obj.getJSONObject("value")
                    when (v.getString("type")) {
                        "time" -> FlashStep.Wait(v.getLong("time"))
                        else -> throw UnsupportedStepException("unsupported wait type: ${v.getString("type")}")
                    }
                }

                else -> throw UnsupportedStepException("unknown meta.json step: $type")
            }
        }
    }
}
