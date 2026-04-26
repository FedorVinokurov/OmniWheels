package com.example.omniwheels

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
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
    private val cameraManager: CameraManager by lazy {
        requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serialConnection: UsbSerialConnection? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
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

        binding.cameraPreview.surfaceTextureListener = cameraSurfaceListener
        startCameraIfReady()
        connectFirstUsbDevice()
    }

    override fun onDestroyView() {
        closeCamera()
        disconnect()
        unregisterUsbPermissionReceiver()
        super.onDestroyView()
        _binding = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraIfReady()
        }
    }

    private val cameraSurfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            startCameraIfReady()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            closeCamera()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private fun startCameraIfReady() {
        if (_binding == null || cameraDevice != null || !binding.cameraPreview.isAvailable) return

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }

        try {
            val cameraId = findBackCameraId() ?: return
            cameraManager.openCamera(cameraId, cameraStateCallback, mainHandler)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun findBackCameraId(): String? {
        val cameraIds = cameraManager.cameraIdList
        return cameraIds.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraIds.firstOrNull()
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPreview(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            closeCamera()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            closeCamera()
        }
    }

    private fun startPreview(camera: CameraDevice) {
        val texture = binding.cameraPreview.surfaceTexture ?: return
        texture.setDefaultBufferSize(binding.cameraPreview.width, binding.cameraPreview.height)
        val surface = Surface(texture)
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
        }

        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraSession = session
                    session.setRepeatingRequest(request.build(), null, mainHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) = Unit
            },
            mainHandler
        )
    }

    private fun closeCamera() {
        try {
            cameraSession?.close()
            cameraDevice?.close()
        } finally {
            cameraSession = null
            cameraDevice = null
        }
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
        private const val CAMERA_PERMISSION_REQUEST = 7
        private const val BAUD_RATE = 115200
        private const val FULL_PWM = 255
    }
}
