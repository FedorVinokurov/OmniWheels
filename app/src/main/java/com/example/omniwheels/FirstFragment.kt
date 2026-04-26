package com.example.omniwheels

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.omniwheels.databinding.FragmentFirstBinding
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val usbManager: UsbManager by lazy {
        requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val devices = mutableListOf<UsbDevice>()
    private var serialConnection: UsbSerialConnection? = null
    private var permissionReceiverRegistered = false

    private var joyX = 0f
    private var joyY = 0f
    private var rotation = 0f
    private var speedLimit = 180
    private var lastCommand = ""

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (device != null && granted) {
                openDevice(device)
            } else {
                setBusy(false)
                setStatus(getString(R.string.status_usb_permission_denied))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        registerUsbPermissionReceiver()

        binding.joystick.listener = { x, y ->
            joyX = x
            joyY = y
            sendDriveCommand()
        }

        binding.rotationSlider.addOnChangeListener { _, value, fromUser ->
            rotation = value / 100f
            binding.rotationValue.text = getString(R.string.rotation_value, value.roundToInt())
            if (fromUser) sendDriveCommand()
        }
        binding.rotationSlider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) = Unit

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                rotation = 0f
                slider.value = 0f
                sendDriveCommand()
            }
        })

        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            speedLimit = value.roundToInt()
            binding.speedValue.text = getString(R.string.speed_value, speedLimit)
            if (fromUser) sendDriveCommand()
        }

        binding.refreshDevicesButton.setOnClickListener { loadUsbDevices() }
        binding.connectButton.setOnClickListener { connectSelectedDevice() }
        binding.stopButton.setOnClickListener { stopRobot() }
        binding.disconnectButton.setOnClickListener { disconnect() }

        loadUsbDevices()
        updateTelemetry(0, 0, 0, 0)
    }

    override fun onDestroyView() {
        disconnect()
        unregisterUsbPermissionReceiver()
        super.onDestroyView()
        _binding = null
    }

    private fun registerUsbPermissionReceiver() {
        if (permissionReceiverRegistered) return
        ContextCompat.registerReceiver(
            requireContext(),
            usbPermissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        permissionReceiverRegistered = true
    }

    private fun unregisterUsbPermissionReceiver() {
        if (!permissionReceiverRegistered) return
        requireContext().unregisterReceiver(usbPermissionReceiver)
        permissionReceiverRegistered = false
    }

    private fun loadUsbDevices() {
        devices.clear()
        devices.addAll(usbManager.deviceList.values.sortedWith(compareBy({ it.vendorId }, { it.productId })))

        val labels = if (devices.isEmpty()) {
            listOf(getString(R.string.no_usb_devices))
        } else {
            devices.map { device ->
                val name = device.productName ?: getString(R.string.usb_device)
                "$name (${device.vendorId.toString(16)}:${device.productId.toString(16)})"
            }
        }
        binding.deviceSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )

        setStatus(
            if (devices.isEmpty()) getString(R.string.status_connect_usb)
            else getString(R.string.status_usb_ready)
        )
    }

    private fun connectSelectedDevice() {
        if (devices.isEmpty()) {
            setStatus(getString(R.string.status_connect_usb))
            return
        }

        val selected = devices[binding.deviceSpinner.selectedItemPosition.coerceAtLeast(0)]
        setBusy(true)
        setStatus(getString(R.string.status_usb_permission))

        if (usbManager.hasPermission(selected)) {
            openDevice(selected)
        } else {
            try {
                usbManager.requestPermission(selected, usbPermissionIntent())
            } catch (error: RuntimeException) {
                setBusy(false)
                setStatus(getString(R.string.status_usb_failed, error.localizedMessage ?: "permission request"))
            }
        }
    }

    private fun usbPermissionIntent(): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(
            requireContext(),
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(requireContext().packageName),
            flags
        )
    }

    private fun openDevice(device: UsbDevice) {
        setBusy(true)
        setStatus(getString(R.string.status_usb_opening, device.productName ?: getString(R.string.usb_device)))

        thread(name = "ArduinoUsbConnect") {
            try {
                val connection = UsbSerialConnectionFactory.open(usbManager, device, BAUD_RATE)
                serialConnection = connection
                requireActivity().runOnUiThread {
                    setBusy(false)
                    setConnected(true)
                    setStatus(getString(R.string.status_usb_connected, device.productName ?: getString(R.string.usb_device)))
                    sendDriveCommand()
                }
            } catch (error: Exception) {
                closeSerialConnection()
                requireActivity().runOnUiThread {
                    setBusy(false)
                    setConnected(false)
                    setStatus(getString(R.string.status_usb_failed, error.localizedMessage ?: "USB"))
                }
            }
        }
    }

    private fun sendDriveCommand() {
        val rawFrontLeft = joyY + joyX + rotation
        val rawFrontRight = joyY - joyX - rotation
        val rawRearLeft = joyY - joyX + rotation
        val rawRearRight = joyY + joyX - rotation
        val maxMagnitude = maxOf(
            1f,
            kotlin.math.abs(rawFrontLeft),
            kotlin.math.abs(rawFrontRight),
            kotlin.math.abs(rawRearLeft),
            kotlin.math.abs(rawRearRight)
        )

        val frontLeft = (rawFrontLeft / maxMagnitude * speedLimit).roundToInt()
        val frontRight = (rawFrontRight / maxMagnitude * speedLimit).roundToInt()
        val rearLeft = (rawRearLeft / maxMagnitude * speedLimit).roundToInt()
        val rearRight = (rawRearRight / maxMagnitude * speedLimit).roundToInt()
        val command = "M $frontLeft $frontRight $rearLeft $rearRight\n"

        updateTelemetry(frontLeft, frontRight, rearLeft, rearRight)
        writeCommand(command)
    }

    private fun stopRobot() {
        joyX = 0f
        joyY = 0f
        rotation = 0f
        binding.rotationSlider.value = 0f
        updateTelemetry(0, 0, 0, 0)
        writeCommand("STOP\n", force = true)
    }

    private fun writeCommand(command: String, force: Boolean = false) {
        if (!force && command == lastCommand) return
        lastCommand = command
        binding.commandPreview.text = command.trim()

        val connection = serialConnection ?: return
        thread(name = "ArduinoUsbWrite") {
            try {
                connection.write(command.toByteArray(Charsets.US_ASCII))
            } catch (error: IOException) {
                mainHandler.post {
                    closeSerialConnection()
                    setConnected(false)
                    setStatus(getString(R.string.status_write_failed))
                }
            }
        }
    }

    private fun updateTelemetry(frontLeft: Int, frontRight: Int, rearLeft: Int, rearRight: Int) {
        binding.vectorValue.text = getString(
            R.string.vector_value,
            (joyX * 100).roundToInt(),
            (joyY * 100).roundToInt()
        )
        binding.motorFrontLeft.text = getString(R.string.motor_front_left, frontLeft)
        binding.motorFrontRight.text = getString(R.string.motor_front_right, frontRight)
        binding.motorRearLeft.text = getString(R.string.motor_rear_left, rearLeft)
        binding.motorRearRight.text = getString(R.string.motor_rear_right, rearRight)
    }

    private fun disconnect() {
        closeSerialConnection()
        if (_binding != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                setConnected(false)
            } else {
                mainHandler.post {
                    if (_binding != null) setConnected(false)
                }
            }
        }
    }

    private fun closeSerialConnection() {
        try {
            serialConnection?.close()
        } catch (_: IOException) {
        } finally {
            serialConnection = null
        }
    }

    private fun setConnected(connected: Boolean) {
        binding.connectButton.isEnabled = !connected
        binding.disconnectButton.isEnabled = connected
        binding.connectionIndicator.text = getString(
            if (connected) R.string.connected else R.string.disconnected
        )
        binding.connectionIndicator.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (connected) R.color.status_connected else R.color.status_disconnected
            )
        )
    }

    private fun setBusy(busy: Boolean) {
        binding.connectButton.isEnabled = !busy
        binding.refreshDevicesButton.isEnabled = !busy
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun setStatus(message: String) {
        binding.statusText.text = message
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.omniwheels.USB_PERMISSION"
        private const val BAUD_RATE = 115200
    }
}
