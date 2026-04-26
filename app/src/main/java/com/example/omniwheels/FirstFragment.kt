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
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
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
    private var serialConnection: UsbSerialConnection? = null
    private var permissionReceiverRegistered = false

    private var joyX = 0f
    private var joyY = 0f
    private var rotation = 0f
    private var speedLimit = 255
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

        binding.driveJoystick.listener = { x, y ->
            joyX = x
            joyY = y
            sendDriveCommand()
        }

        binding.turnJoystick.listener = { x, _ ->
            rotation = x
            sendDriveCommand()
        }

        connectFirstUsbDevice()
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

    private fun connectFirstUsbDevice() {
        val selected = usbManager.deviceList.values
            .sortedWith(compareBy({ it.vendorId }, { it.productId }))
            .firstOrNull() ?: return

        if (usbManager.hasPermission(selected)) {
            openDevice(selected)
        } else {
            try {
                usbManager.requestPermission(selected, usbPermissionIntent())
            } catch (_: RuntimeException) {
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
        thread(name = "ArduinoUsbConnect") {
            try {
                val connection = UsbSerialConnectionFactory.open(usbManager, device, BAUD_RATE)
                serialConnection = connection
                requireActivity().runOnUiThread {
                    lastCommand = ""
                    sendDriveCommand()
                }
            } catch (error: Exception) {
                closeSerialConnection()
            }
        }
    }

    private fun sendDriveCommand() {
        val strafe = rotation
        val turn = joyX
        val rawFrontLeft = joyY + strafe + turn
        val rawFrontRight = joyY - strafe - turn
        val rawRearLeft = joyY + strafe - turn
        val rawRearRight = joyY - strafe + turn
        val maxMagnitude = maxOf(
            1f,
            kotlin.math.abs(rawFrontLeft),
            kotlin.math.abs(rawFrontRight),
            kotlin.math.abs(rawRearLeft),
            kotlin.math.abs(rawRearRight)
        )

        val targetSpeeds = intArrayOf(
            applyFullPwm((rawFrontLeft / maxMagnitude * speedLimit).roundToInt()),
            applyFullPwm((rawFrontRight / maxMagnitude * speedLimit).roundToInt()),
            applyFullPwm((rawRearLeft / maxMagnitude * speedLimit).roundToInt()),
            applyFullPwm((rawRearRight / maxMagnitude * speedLimit).roundToInt())
        )

        writeMotorCommand(targetSpeeds)
    }

    private fun applyFullPwm(speed: Int): Int {
        if (speed == 0) return 0
        return if (speed > 0) FULL_PWM else -FULL_PWM
    }

    private fun stopRobot() {
        joyX = 0f
        joyY = 0f
        rotation = 0f
        writeCommand("STOP\n", force = true)
    }

    private fun writeMotorCommand(speeds: IntArray) {
        val shieldSpeeds = intArrayOf(
            speeds[3],
            speeds[1],
            speeds[0],
            speeds[2]
        )
        val command = "M ${shieldSpeeds[0]} ${shieldSpeeds[1]} ${shieldSpeeds[2]} ${shieldSpeeds[3]}\n"
        writeCommand(command)
    }

    private fun writeCommand(command: String, force: Boolean = false) {
        if (!force && command == lastCommand) return
        lastCommand = command

        val connection = serialConnection ?: return
        thread(name = "ArduinoUsbWrite") {
            try {
                connection.write(command.toByteArray(Charsets.US_ASCII))
            } catch (error: IOException) {
                mainHandler.post {
                    closeSerialConnection()
                }
            }
        }
    }

    private fun disconnect() {
        closeSerialConnection()
    }

    private fun closeSerialConnection() {
        try {
            serialConnection?.close()
        } catch (_: IOException) {
        } finally {
            serialConnection = null
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.omniwheels.USB_PERMISSION"
        private const val BAUD_RATE = 115200
        private const val FULL_PWM = 255
    }
}
