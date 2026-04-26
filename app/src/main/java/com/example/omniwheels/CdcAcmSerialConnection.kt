package com.example.omniwheels

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException

interface UsbSerialConnection : Closeable {
    fun write(bytes: ByteArray)
}

object UsbSerialConnectionFactory {
    fun open(usbManager: UsbManager, device: UsbDevice, baudRate: Int): UsbSerialConnection {
        return if (device.vendorId == FtdiSerialConnection.FTDI_VENDOR_ID) {
            FtdiSerialConnection.open(usbManager, device, baudRate)
        } else {
            CdcAcmSerialConnection.open(usbManager, device, baudRate)
        }
    }
}

class CdcAcmSerialConnection private constructor(
    private val connection: UsbDeviceConnection,
    private val claimedInterfaces: List<UsbInterface>,
    private val outEndpoint: UsbEndpoint
) : UsbSerialConnection {

    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val chunkSize = minOf(outEndpoint.maxPacketSize.coerceAtLeast(1), bytes.size - offset)
            val sent = connection.bulkTransfer(outEndpoint, bytes, offset, chunkSize, WRITE_TIMEOUT_MS)
            if (sent <= 0) {
                throw IOException("USB write timeout")
            }
            offset += sent
        }
    }

    override fun close() {
        claimedInterfaces.forEach { connection.releaseInterface(it) }
        connection.close()
    }

    companion object {
        private const val USB_RT_ACM = 0x21
        private const val SET_CONTROL_LINE_STATE = 0x22
        private const val SET_LINE_CODING = 0x20
        private const val WRITE_TIMEOUT_MS = 250
        private const val CONTROL_TIMEOUT_MS = 1000

        fun open(usbManager: UsbManager, device: UsbDevice, baudRate: Int): CdcAcmSerialConnection {
            val dataInterface = findDataInterface(device)
                ?: throw IOException("CDC data interface not found")
            val controlInterface = findControlInterface(device) ?: dataInterface
            val outEndpoint = findOutEndpoint(dataInterface)
                ?: throw IOException("USB bulk OUT endpoint not found")
            val connection = usbManager.openDevice(device)
                ?: throw IOException("Cannot open USB device")
            val claimed = mutableListOf<UsbInterface>()

            try {
                if (!connection.claimInterface(controlInterface, true)) {
                    throw IOException("Cannot claim control interface")
                }
                claimed += controlInterface

                if (dataInterface.id != controlInterface.id) {
                    if (!connection.claimInterface(dataInterface, true)) {
                        throw IOException("Cannot claim data interface")
                    }
                    claimed += dataInterface
                }

                configureLine(connection, controlInterface, baudRate)
                return CdcAcmSerialConnection(connection, claimed, outEndpoint)
            } catch (error: Exception) {
                claimed.forEach { connection.releaseInterface(it) }
                connection.close()
                throw error
            }
        }

        private fun findControlInterface(device: UsbDevice): UsbInterface? {
            return allInterfaces(device).firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_COMM
            }
        }

        private fun findDataInterface(device: UsbDevice): UsbInterface? {
            return allInterfaces(device).firstOrNull { iface ->
                iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA && findOutEndpoint(iface) != null
            } ?: allInterfaces(device).firstOrNull { findOutEndpoint(it) != null }
        }

        private fun findOutEndpoint(usbInterface: UsbInterface): UsbEndpoint? {
            for (index in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(index)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT
                ) {
                    return endpoint
                }
            }
            return null
        }

        private fun allInterfaces(device: UsbDevice): List<UsbInterface> {
            return (0 until device.interfaceCount).map { device.getInterface(it) }
        }

        private fun configureLine(
            connection: UsbDeviceConnection,
            controlInterface: UsbInterface,
            baudRate: Int
        ) {
            val lineCoding = byteArrayOf(
                (baudRate and 0xff).toByte(),
                (baudRate shr 8 and 0xff).toByte(),
                (baudRate shr 16 and 0xff).toByte(),
                (baudRate shr 24 and 0xff).toByte(),
                0,
                0,
                8
            )
            val lineResult = connection.controlTransfer(
                USB_RT_ACM,
                SET_LINE_CODING,
                0,
                controlInterface.id,
                lineCoding,
                lineCoding.size,
                CONTROL_TIMEOUT_MS
            )
            if (lineResult < 0) {
                throw IOException("Cannot set serial baud rate")
            }

            val stateResult = connection.controlTransfer(
                USB_RT_ACM,
                SET_CONTROL_LINE_STATE,
                0x03,
                controlInterface.id,
                null,
                0,
                CONTROL_TIMEOUT_MS
            )
            if (stateResult < 0) {
                throw IOException("Cannot enable serial control lines")
            }
        }
    }
}

class FtdiSerialConnection private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val outEndpoint: UsbEndpoint
) : UsbSerialConnection {

    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val chunkSize = minOf(outEndpoint.maxPacketSize.coerceAtLeast(1), bytes.size - offset)
            val sent = connection.bulkTransfer(outEndpoint, bytes, offset, chunkSize, WRITE_TIMEOUT_MS)
            if (sent <= 0) {
                throw IOException("USB write timeout")
            }
            offset += sent
        }
    }

    override fun close() {
        connection.releaseInterface(usbInterface)
        connection.close()
    }

    companion object {
        const val FTDI_VENDOR_ID = 0x0403

        private const val REQUEST_TYPE_OUT = 0x40
        private const val SIO_RESET = 0
        private const val SIO_MODEM_CTRL = 1
        private const val SIO_SET_FLOW_CTRL = 2
        private const val SIO_SET_BAUD_RATE = 3
        private const val SIO_SET_DATA = 4
        private const val RESET_SIO = 0
        private const val PURGE_RX = 1
        private const val PURGE_TX = 2
        private const val DTR_RTS_ENABLED = 0x0303
        private const val DATA_8N1 = 0x0008
        private const val WRITE_TIMEOUT_MS = 250
        private const val CONTROL_TIMEOUT_MS = 1000

        fun open(usbManager: UsbManager, device: UsbDevice, baudRate: Int): FtdiSerialConnection {
            val usbInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { findOutEndpoint(it) != null }
                ?: throw IOException("FTDI interface not found")
            val outEndpoint = findOutEndpoint(usbInterface)
                ?: throw IOException("FTDI bulk OUT endpoint not found")
            val connection = usbManager.openDevice(device)
                ?: throw IOException("Cannot open FTDI device")

            try {
                if (!connection.claimInterface(usbInterface, true)) {
                    throw IOException("Cannot claim FTDI interface")
                }
                configure(connection, usbInterface, baudRate)
                return FtdiSerialConnection(connection, usbInterface, outEndpoint)
            } catch (error: Exception) {
                connection.releaseInterface(usbInterface)
                connection.close()
                throw error
            }
        }

        private fun configure(
            connection: UsbDeviceConnection,
            usbInterface: UsbInterface,
            baudRate: Int
        ) {
            vendorCommand(connection, SIO_RESET, RESET_SIO, usbInterface.id, "Cannot reset FTDI")
            vendorCommand(connection, SIO_RESET, PURGE_RX, usbInterface.id, "Cannot purge FTDI RX")
            vendorCommand(connection, SIO_RESET, PURGE_TX, usbInterface.id, "Cannot purge FTDI TX")

            val divisor = when (baudRate) {
                9600 -> 0x4138 to 0x0000
                19200 -> 0x809C to 0x0000
                38400 -> 0xC04E to 0x0000
                57600 -> 0x0034 to 0x0000
                115200 -> 0x001A to 0x0000
                else -> throw IOException("Unsupported FTDI baud rate: $baudRate")
            }
            vendorCommand(
                connection,
                SIO_SET_BAUD_RATE,
                divisor.first,
                divisor.second,
                "Cannot set FTDI baud rate"
            )
            vendorCommand(connection, SIO_SET_DATA, DATA_8N1, usbInterface.id, "Cannot set FTDI data format")
            vendorCommand(connection, SIO_SET_FLOW_CTRL, 0, usbInterface.id, "Cannot disable FTDI flow control")
            vendorCommand(connection, SIO_MODEM_CTRL, DTR_RTS_ENABLED, usbInterface.id, "Cannot enable FTDI DTR/RTS")
        }

        private fun vendorCommand(
            connection: UsbDeviceConnection,
            request: Int,
            value: Int,
            index: Int,
            errorMessage: String
        ) {
            val result = connection.controlTransfer(
                REQUEST_TYPE_OUT,
                request,
                value,
                index,
                null,
                0,
                CONTROL_TIMEOUT_MS
            )
            if (result < 0) {
                throw IOException(errorMessage)
            }
        }

        private fun findOutEndpoint(usbInterface: UsbInterface): UsbEndpoint? {
            for (index in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(index)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT
                ) {
                    return endpoint
                }
            }
            return null
        }
    }
}
