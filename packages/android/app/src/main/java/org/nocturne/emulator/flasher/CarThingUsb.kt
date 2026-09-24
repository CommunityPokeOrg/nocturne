package org.nocturne.emulator.flasher

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/** How the Car Thing's Amlogic SoC is currently reachable over USB. */
enum class DeviceMode {
    /** Running firmware normally (CDC-NCM/adb gadget: 18d1:4e40). */
    Normal,

    /** Held-buttons-1&4 "GX-CHIP" mode; needs a BL2 boot to reach burn mode. */
    Usb,

    /** Maskrom USB burn mode (1b8e:c003, no "GX-CHIP" product string). */
    UsbBurn,

    /** Mainline u-boot fastboot gadget (18d1:fada); not handled by this flasher. */
    Fastboot,

    /** Nothing matching is attached. */
    NotFound,
}

data class DetectedDevice(val device: UsbDevice, val mode: DeviceMode)

/**
 * USB transport for the Car Thing's Amlogic burn-mode gadget. Speaks vendor
 * control transfers plus raw bulk endpoints, matching the wire protocol used
 * by flashthing (github.com/JoeyEamigh/flashthing) and pyamlboot.
 */
object CarThingUsb {
    const val VENDOR_ID_AMLOGIC = 0x1b8e
    const val PRODUCT_ID_BURN = 0xc003

    const val VENDOR_ID_GOOGLE = 0x18d1
    const val PRODUCT_ID_NORMAL = 0x4e40
    const val PRODUCT_ID_FASTBOOT = 0xfada

    private const val MASKROM_INTERFACE = 0
    const val COMMAND_TIMEOUT_MS = 1000

    /** Scan the USB device list and report the Car Thing's mode. */
    fun detect(usbManager: UsbManager): DetectedDevice? {
        for (device in usbManager.deviceList.values) {
            when {
                device.vendorId == VENDOR_ID_GOOGLE && device.productId == PRODUCT_ID_NORMAL ->
                    return DetectedDevice(device, DeviceMode.Normal)
                device.vendorId == VENDOR_ID_GOOGLE && device.productId == PRODUCT_ID_FASTBOOT ->
                    return DetectedDevice(device, DeviceMode.Fastboot)
                device.vendorId == VENDOR_ID_AMLOGIC && device.productId == PRODUCT_ID_BURN ->
                    return DetectedDevice(
                        device,
                        // flashthing distinguishes "USB mode" (needs BL2 boot)
                        // from burn mode by the GX-CHIP product string.
                        if (device.productName == "GX-CHIP") DeviceMode.Usb else DeviceMode.UsbBurn,
                    )
            }
        }
        return null
    }

    /**
     * Open [device], claim interface 0, and locate the bulk IN/OUT endpoints.
     * Caller must hold UsbManager permission for the device.
     */
    fun claim(usbManager: UsbManager, device: UsbDevice): Session {
        val connection = usbManager.openDevice(device)
            ?: throw IOException("failed to open USB device")
        try {
            val intf: UsbInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.id == MASKROM_INTERFACE }
                ?: throw IOException("no maskrom interface on this device")

            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (ep.direction) {
                    UsbConstants.USB_DIR_IN -> if (epIn == null) epIn = ep
                    UsbConstants.USB_DIR_OUT -> if (epOut == null) epOut = ep
                }
            }
            val endpointIn = epIn ?: throw IOException("no bulk IN endpoint")
            val endpointOut = epOut ?: throw IOException("no bulk OUT endpoint")

            if (!connection.claimInterface(intf, true)) {
                throw IOException("failed to claim interface $MASKROM_INTERFACE")
            }
            return Session(connection, intf, endpointIn, endpointOut)
        } catch (e: Throwable) {
            connection.close()
            throw e
        }
    }

    class Session(
        private val connection: UsbDeviceConnection,
        private val intf: UsbInterface,
        private val endpointIn: UsbEndpoint,
        private val endpointOut: UsbEndpoint,
    ) : AutoCloseable {

        /** Vendor-specific control OUT transfer (bmRequestType 0x40). */
        fun controlOut(request: Int, value: Int, index: Int, data: ByteArray, timeout: Int): Int {
            val n = connection.controlTransfer(0x40, request, value, index, data, data.size, timeout)
            if (n < 0) throw IOException("control OUT 0x${request.toString(16)} failed")
            return n
        }

        /** Vendor-specific control IN transfer (bmRequestType 0xC0). */
        fun controlIn(request: Int, value: Int, index: Int, buf: ByteArray, timeout: Int): Int {
            val n = connection.controlTransfer(0xC0, request, value, index, buf, buf.size, timeout)
            if (n < 0) throw IOException("control IN 0x${request.toString(16)} failed")
            return n
        }

        fun bulkOut(data: ByteArray, offset: Int, length: Int, timeout: Int): Int {
            // bulkTransfer has no offset variant until API 28 (minSdk is 24),
            // so slice manually when the caller passes a window.
            val chunk = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)
            val n = connection.bulkTransfer(endpointOut, chunk, length, timeout)
            if (n < 0) throw IOException("bulk OUT failed")
            return n
        }

        fun bulkIn(buf: ByteArray, timeout: Int): Int {
            val n = connection.bulkTransfer(endpointIn, buf, buf.size, timeout)
            if (n < 0) throw IOException("bulk IN failed")
            return n
        }

        override fun close() {
            connection.releaseInterface(intf)
            connection.close()
        }
    }
}
