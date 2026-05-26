package com.example.omniwheels

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val BLE_SERVICE_UUID: UUID =
    UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
private val BLE_WRITE_UUID_PRIMARY: UUID =
    UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
private val BLE_WRITE_UUID_FALLBACK: UUID =
    UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB")
private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
    UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
private const val ROBOT_BLUETOOTH_ADDRESS = "E4:A7:3B:3F:1F:22"

class BluetoothRobotConnection private constructor(
    private val gatt: BluetoothGatt,
    private val writeCharacteristics: List<BluetoothGattCharacteristic>,
    private val onLineReceived: (String) -> Unit,
    private val onDisconnected: () -> Unit,
) : UsbSerialConnection {
    private val writeLock = Object()
    private val readBuffer = StringBuilder()
    @Volatile private var pendingWriteLatch: CountDownLatch? = null
    @Volatile private var pendingWriteError: IOException? = null
    @Volatile private var disconnectedNotified = false

    override fun write(bytes: ByteArray) {
        synchronized(writeLock) {
            val errors = mutableListOf<String>()
            for (characteristic in writeCharacteristics) {
                for (writeType in characteristic.supportedWriteTypes()) {
                    pendingWriteError = null
                    pendingWriteLatch = CountDownLatch(1)
                    val startError = startWrite(characteristic, bytes, writeType)
                    if (startError == null) {
                        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                            pendingWriteLatch = null
                            return
                        }
                        val completed = pendingWriteLatch?.await(2500, TimeUnit.MILLISECONDS) == true
                        val error = pendingWriteError
                        pendingWriteLatch = null
                        if (completed && error == null) return
                        errors += error?.message ?: "write timeout"
                    } else {
                        pendingWriteLatch = null
                        errors += startError
                    }
                }
            }
            throw IOException(errors.joinToString(" | "))
        }
    }

    override fun close() {
        try {
            gatt.disconnect()
        } catch (_: SecurityException) {
        }
        gatt.close()
    }

    fun onCharacteristicWrite(status: Int) {
        pendingWriteError = if (status == BluetoothGatt.GATT_SUCCESS) {
            null
        } else {
            IOException("write status $status")
        }
        pendingWriteLatch?.countDown()
    }

    fun onCharacteristicChanged(bytes: ByteArray) {
        val text = bytes.toString(Charsets.UTF_8)
        synchronized(readBuffer) {
            for (c in text) {
                if (c == '\n') {
                    val line = readBuffer.toString().trim()
                    readBuffer.clear()
                    if (line.isNotBlank()) {
                        onLineReceived(line)
                    }
                } else if (c != '\r') {
                    readBuffer.append(c)
                    if (readBuffer.length > 160) {
                        readBuffer.clear()
                    }
                }
            }
        }
    }

    fun onGattDisconnected() {
        if (disconnectedNotified) return
        disconnectedNotified = true
        pendingWriteError = IOException("Bluetooth disconnected")
        pendingWriteLatch?.countDown()
        onDisconnected()
    }

    @SuppressLint("MissingPermission")
    private fun startWrite(
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
        writeType: Int,
    ): String? {
        characteristic.writeType = writeType
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(characteristic, bytes, writeType)
            if (status == BluetoothStatusCodes.SUCCESS) null else "write returned $status"
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            if (gatt.writeCharacteristic(characteristic)) null else "write returned false"
        }
    }

    companion object {
        fun requiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                emptyArray()
            }
        }

        fun hasPermissions(context: Context): Boolean {
            return requiredPermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }

        @SuppressLint("MissingPermission")
        fun openFirstPaired(
            context: Context,
            onLineReceived: (String) -> Unit = {},
            onDisconnected: () -> Unit = {},
        ): BluetoothRobotConnection {
            val adapter = bluetoothAdapter(context)
                ?: throw IOException("Bluetooth not supported")
            if (!adapter.isEnabled) {
                throw IOException("Bluetooth is disabled")
            }
            if (BluetoothAdapter.checkBluetoothAddress(ROBOT_BLUETOOTH_ADDRESS)) {
                val device = adapter.getRemoteDevice(ROBOT_BLUETOOTH_ADDRESS)
                return open(context, device, onLineReceived, onDisconnected)
            }
            val device = adapter.bondedDevices
                .sortedWith(
                    compareByDescending<BluetoothDevice> { it.name?.isLikelyRobotModule() == true }
                        .thenBy { it.name ?: "" }
                )
                .firstOrNull()
                ?: throw IOException("No paired Bluetooth devices")
            return open(context, device, onLineReceived, onDisconnected)
        }

        @SuppressLint("MissingPermission")
        private fun open(
            context: Context,
            device: BluetoothDevice,
            onLineReceived: (String) -> Unit,
            onDisconnected: () -> Unit,
        ): BluetoothRobotConnection {
            val latch = CountDownLatch(1)
            var result: Result<BluetoothRobotConnection>? = null
            var connection: BluetoothRobotConnection? = null

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        result = Result.failure(IOException("GATT status $status"))
                        latch.countDown()
                        gatt.close()
                        return
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        connection?.onGattDisconnected()
                        if (connection == null) {
                            result = Result.failure(IOException("Bluetooth disconnected"))
                            latch.countDown()
                        }
                        gatt.close()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        result = Result.failure(IOException("Services status $status"))
                        latch.countDown()
                        gatt.close()
                        return
                    }
                    val service = gatt.getService(BLE_SERVICE_UUID)
                    if (service == null) {
                        result = Result.failure(IOException("Service FFE0 not found"))
                        latch.countDown()
                        gatt.close()
                        return
                    }
                    val writeCharacteristics = listOfNotNull(
                        service.getCharacteristic(BLE_WRITE_UUID_PRIMARY),
                        service.getCharacteristic(BLE_WRITE_UUID_FALLBACK),
                    ).filter { it.canWrite() }
                    if (writeCharacteristics.isEmpty()) {
                        result = Result.failure(IOException("FFE1/FFE2 write not found"))
                        latch.countDown()
                        gatt.close()
                        return
                    }
                    val opened = BluetoothRobotConnection(gatt, writeCharacteristics, onLineReceived, onDisconnected)
                    connection = opened
                    enableNotifications(gatt, service.characteristics.filter { it.canNotify() || it.canIndicate() })
                    result = Result.success(opened)
                    latch.countDown()
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    connection?.onCharacteristicWrite(status)
                }

                @Deprecated("Deprecated in Android API")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    @Suppress("DEPRECATION")
                    connection?.onCharacteristicChanged(characteristic.value ?: return)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    connection?.onCharacteristicChanged(value)
                }
            }

            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context.applicationContext, false, callback)
            } ?: throw IOException("connectGatt returned null")

            if (!latch.await(15000, TimeUnit.MILLISECONDS)) {
                gatt.close()
                throw IOException("Bluetooth connect timeout")
            }
            return result?.getOrThrow() ?: throw IOException("Bluetooth connect failed")
        }

        private fun bluetoothAdapter(context: Context): BluetoothAdapter? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } else {
                @Suppress("DEPRECATION")
                BluetoothAdapter.getDefaultAdapter()
            }
        }

        private fun String.isLikelyRobotModule(): Boolean {
            val upper = uppercase()
            return upper.contains("HM") ||
                upper.contains("BT") ||
                upper.contains("BLE") ||
                upper.contains("HC")
        }

        private fun BluetoothGattCharacteristic.canWrite(): Boolean {
            return properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        }

        private fun BluetoothGattCharacteristic.canNotify(): Boolean {
            return properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        }

        private fun BluetoothGattCharacteristic.canIndicate(): Boolean {
            return properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(
            gatt: BluetoothGatt,
            characteristics: List<BluetoothGattCharacteristic>,
        ) {
            characteristics.forEach { characteristic ->
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                    ?: return@forEach
                val value = if (characteristic.canNotify()) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, value)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                Thread.sleep(120)
            }
        }

        private fun BluetoothGattCharacteristic.supportedWriteTypes(): List<Int> {
            val result = mutableListOf<Int>()
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                result += BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                result += BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            return result.ifEmpty { listOf(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }.distinct()
        }
    }
}
