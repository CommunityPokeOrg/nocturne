package org.nocturne.emulator.flasher

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.IOException
import java.io.InputStream

/** Events the engine reports to the UI thread via [emit]. */
sealed class FlashEvent {
    data class ModeDetected(val mode: DeviceMode) : FlashEvent()
    data class Status(val text: String) : FlashEvent()
    data class StepStarted(val index: Int, val total: Int, val description: String) : FlashEvent()
    data class StepProgress(val progress: FlashProgress) : FlashEvent()
    data class LogLine(val text: String) : FlashEvent()
    object Done : FlashEvent()
    data class Failed(val error: String) : FlashEvent()
}

/**
 * Drives a full firmware flash: detect the Car Thing, move it to burn mode if
 * it arrived in button-held "USB mode", then run every step of the package's
 * meta.json plan against the Amlogic SoC.
 *
 * Runs entirely on the calling thread — invoke from a worker thread.
 *
 * [requestPermission] must synchronously ask the user for USB access and
 * return whether it was granted (the activity supplies the latch/dialog).
 */
class FlashEngine(
    private val usbManager: UsbManager,
    private val pkg: FirmwarePackage,
    private val bl2: ByteArray,
    private val bootloader: ByteArray,
    private val requestPermission: (UsbDevice) -> Boolean,
    private val emit: (FlashEvent) -> Unit,
) {
    private fun log(text: String) = emit(FlashEvent.LogLine(text))

    /** Poll for a burn-mode Car Thing, request permission, and open it. */
    private fun acquireSession(waitMs: Long = 15000): CarThingUsb.Session {
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            val detected = CarThingUsb.detect(usbManager)
            if (detected != null && detected.mode == DeviceMode.UsbBurn) {
                if (!requestPermission(detected.device)) {
                    throw IOException("USB permission denied")
                }
                return CarThingUsb.claim(usbManager, detected.device)
            }
            Thread.sleep(250)
        }
        throw IOException("Car Thing not found in burn mode — power it on while holding buttons 1 and 4")
    }

    private fun openPayload(data: DataOrFile): Pair<Long, InputStream> = when (data) {
        is DataOrFile.Inline -> data.data.size.toLong() to data.data.inputStream()
        is DataOrFile.File -> pkg.open(data.filePath)
    }

    private fun readPayload(data: DataOrFile): ByteArray = when (data) {
        is DataOrFile.Inline -> data.data
        is DataOrFile.File -> pkg.readAll(data.filePath)
    }

    fun run() {
        try {
            emit(FlashEvent.Status("looking for a Car Thing..."))
            val detected = CarThingUsb.detect(usbManager)
                ?: throw IOException("no Car Thing found — power it on while holding buttons 1 and 4, then connect it by USB")
            emit(FlashEvent.ModeDetected(detected.mode))

            var device: AmlogicDevice
            when (detected.mode) {
                DeviceMode.NotFound -> throw IOException("no Car Thing found")
                DeviceMode.Normal -> throw IOException(
                    "Car Thing booted normally — unplug it, then power it back on while holding buttons 1 and 4",
                )
                DeviceMode.Fastboot -> throw IOException(
                    "Car Thing is in fastboot mode, which this flasher does not use — " +
                        "power it on while holding buttons 1 and 4 to reach burn mode",
                )
                DeviceMode.UsbBurn -> {
                    emit(FlashEvent.Status("device is in burn mode"))
                    device = AmlogicDevice(acquireSession(), { acquireSession() }, ::log)
                }
                DeviceMode.Usb -> {
                    if (!requestPermission(detected.device)) throw IOException("USB permission denied")
                    val session = CarThingUsb.claim(usbManager, detected.device)
                    device = AmlogicDevice(session, { acquireSession() }, ::log)
                    emit(FlashEvent.Status("booting device into burn mode (BL2)..."))
                    device = device.bl2Boot(bl2, bootloader)
                    emit(FlashEvent.Status("device re-enumerated in burn mode"))
                }
            }

            val steps = pkg.config.steps
            steps.forEachIndexed { index, step ->
                emit(FlashEvent.StepStarted(index + 1, steps.size, step.describe()))
                device = runStep(device, step)
            }

            emit(FlashEvent.Done)
        } catch (e: Throwable) {
            emit(FlashEvent.Failed(e.message ?: e.toString()))
        }
    }

    private fun runStep(device: AmlogicDevice, step: FlashStep): AmlogicDevice = when (step) {
        is FlashStep.Bulkcmd -> {
            log("bulkcmd: ${step.command} -> ${device.bulkcmd(step.command).trim()}")
            device
        }

        is FlashStep.Run -> {
            device.run(step.address, step.keepPower ?: true)
            device
        }

        is FlashStep.WriteSimpleMemory -> {
            device.writeSimpleMemory(step.address, readPayload(step.data))
            device
        }

        is FlashStep.WriteLargeMemory -> {
            val (size, stream) = openPayload(step.data)
            device.writeLargeMemoryToDisk(
                step.address, stream, size, step.blockLength, step.appendZeros ?: true,
            ) { emit(FlashEvent.StepProgress(it)) }
            device
        }

        is FlashStep.WriteAMLCData -> {
            device.writeAmlcDataPacket(step.seq, step.amlcOffset, readPayload(step.data))
            device
        }

        is FlashStep.Bl2Boot -> {
            val bl2Data = readPayload(step.bl2)
            val bootData = readPayload(step.bootloader)
            emit(FlashEvent.Status("BL2 boot from package — device will re-enumerate"))
            device.bl2Boot(bl2Data, bootData)
        }

        is FlashStep.RestorePartition -> {
            val info = SuperbirdPartitions[step.name]
                ?: throw IOException("unknown superbird partition: ${step.name}")
            val partSize = device.validatePartitionSize(step.name, info.size, info.sizeAlt)
            val (size, stream) = openPayload(step.data)
            device.restorePartition(step.name, partSize, stream, size) {
                emit(FlashEvent.StepProgress(it))
            }
            device
        }

        is FlashStep.WriteBootPartition -> {
            device.writeBootPartition(step.hwpart, readPayload(step.data))
            device
        }

        is FlashStep.WriteUserArea -> {
            val (size, stream) = openPayload(step.data)
            device.writeUserArea(step.lba, stream, size, step.sparse) {
                emit(FlashEvent.StepProgress(it))
            }
            device
        }

        is FlashStep.WriteEnv -> {
            device.writeEnv(readPayload(step.env))
            device
        }

        is FlashStep.Log -> {
            log(step.message)
            device
        }

        is FlashStep.Wait -> {
            Thread.sleep(step.timeMs)
            device
        }
    }
}
