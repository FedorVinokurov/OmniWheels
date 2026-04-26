package com.example.omniwheels

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.omniwheels.databinding.FragmentFirstBinding
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val devices = mutableListOf<BluetoothDevice>()
    private var socket: BluetoothSocket? = null
    private var joyX = 0f
    private var joyY = 0f
    private var rotation = 0f
    private var speedLimit = 180
    private var lastCommand = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

        binding.refreshDevicesButton.setOnClickListener { loadBondedDevices() }
        binding.connectButton.setOnClickListener { connectSelectedDevice() }
        binding.stopButton.setOnClickListener { stopRobot() }
        binding.disconnectButton.setOnClickListener { disconnect() }

        ensureBluetoothPermission()
        loadBondedDevices()
        updateTelemetry(0, 0, 0, 0)
    }

    override fun onDestroyView() {
        disconnect()
        super.onDestroyView()
        _binding = null
    }

    private fun ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_PERMISSION_REQUEST)
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadBondedDevices() {
        if (!hasBluetoothPermission()) {
            setStatus(getString(R.string.status_permission_required))
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            setStatus(getString(R.string.status_no_bluetooth))
            return
        }

        devices.clear()
        devices.addAll(adapter.bondedDevices.sortedBy { it.name ?: it.address })

        val labels = if (devices.isEmpty()) {
            listOf(getString(R.string.no_devices))
        } else {
            devices.map { device -> "${device.name ?: getString(R.string.unknown_device)} (${device.address})" }
        }
        binding.deviceSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )

        setStatus(
            if (devices.isEmpty()) getString(R.string.status_pair_device)
            else getString(R.string.status_ready)
        )
    }

    private fun connectSelectedDevice() {
        if (!hasBluetoothPermission()) {
            ensureBluetoothPermission()
            return
        }
        if (devices.isEmpty()) {
            setStatus(getString(R.string.status_pair_device))
            return
        }

        val selected = devices[binding.deviceSpinner.selectedItemPosition.coerceAtLeast(0)]
        setBusy(true)
        setStatus(getString(R.string.status_connecting, selected.name ?: selected.address))

        thread(name = "ArduinoBluetoothConnect") {
            try {
                bluetoothAdapter?.cancelDiscovery()
                val newSocket = selected.createRfcommSocketToServiceRecord(sppUuid)
                newSocket.connect()
                socket = newSocket
                requireActivity().runOnUiThread {
                    setBusy(false)
                    setConnected(true)
                    setStatus(getString(R.string.status_connected, selected.name ?: selected.address))
                    sendDriveCommand()
                }
            } catch (error: IOException) {
                disconnect()
                requireActivity().runOnUiThread {
                    setBusy(false)
                    setConnected(false)
                    setStatus(getString(R.string.status_connection_failed, error.localizedMessage ?: "I/O"))
                }
            } catch (error: SecurityException) {
                requireActivity().runOnUiThread {
                    setBusy(false)
                    setStatus(getString(R.string.status_permission_required))
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

        val activeSocket = socket ?: return
        thread(name = "ArduinoBluetoothWrite") {
            try {
                activeSocket.outputStream.write(command.toByteArray(Charsets.US_ASCII))
                activeSocket.outputStream.flush()
            } catch (error: IOException) {
                requireActivity().runOnUiThread {
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
        try {
            socket?.close()
        } catch (_: IOException) {
        } finally {
            socket = null
        }
        if (_binding != null) {
            setConnected(false)
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
        private const val BLUETOOTH_PERMISSION_REQUEST = 42
    }
}
