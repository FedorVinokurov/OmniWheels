package com.example.omniwheels

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.omniwheels.databinding.FragmentFirstBinding
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.DataStreamConfig
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

private const val STREAM_PORT = 8080
private const val STREAM_PATH = "/stream.mjpg"
private const val COMMAND_PORT = 4210
private const val DISCOVERY_PORT = 4211
private const val AGORA_CHANNEL = "robot-room"
private const val AGORA_APP_ID = "41f7f4e1a4bd4cda9efe3fc3696e86ae"
private const val AGORA_TOKEN = ""
private const val LOG_TAG = "OmniAgora"

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
    private var imageReader: ImageReader? = null
    private val latestFrame = AtomicReference<ByteArray>()
    private var mjpegServer: MjpegServer? = null
    private var commandServer: UdpCommandServer? = null
    private var cameraBeacon: UdpCameraBeacon? = null
    private var cameraDiscovery: UdpCameraDiscovery? = null
    private var cameraScanner: CameraSubnetScanner? = null
    private var commandSender: UdpCommandSender? = null
    private var mjpegView: MjpegView? = null
    private var cameraHost: String? = null
    private var rtcEngine: RtcEngine? = null
    private var commandStreamId: Int? = null
    private var agoraSurfaceView: SurfaceView? = null
    private var permissionReceiverRegistered = false

    @Volatile
    private var controllerMode = false
    private var joyX = 0f
    private var joyY = 0f
    private var rotation = 0f
    private var speedLimit = 255
    private var lastCommand = ""
    private var commandRxStatus = "CMD RX: нет"
    private var commandTxStatus = "TX: нет"
    private var connectionStatus = "BT: нет"
    private val commandWriteExecutor = Executors.newSingleThreadExecutor()
    private val commandWriteSequence = AtomicLong(0)
    private val pendingCommandWrite = AtomicReference<PendingCommand?>(null)
    private val commandWriterActive = AtomicBoolean(false)

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

        mjpegView = MjpegView(requireContext()).also { view ->
            binding.root.addView(
                view,
                0,
                ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    ConstraintLayout.LayoutParams.MATCH_PARENT
                )
            )
            view.visibility = View.GONE
        }

        binding.cameraPreview.surfaceTextureListener = cameraSurfaceListener
        binding.cameraPreview.visibility = View.GONE
        binding.driveJoystick.visibility = View.GONE
        binding.turnJoystick.visibility = View.GONE
        binding.commandStatus.visibility = View.GONE
        updateCommandStatus()
        binding.modeCamera.setOnClickListener {
            configureRole(controller = false)
        }
        binding.modeScreen.setOnClickListener {
            configureRole(controller = true)
        }
    }

    private val rtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            Log.d(LOG_TAG, "joined channel=$channel uid=$uid controllerMode=$controllerMode")
            commandStreamId = rtcEngine?.createDataStream(
                DataStreamConfig().apply {
                    ordered = true
                    syncWithAudio = false
                }
            )?.takeIf { it >= 0 }
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            Log.d(LOG_TAG, "peer joined uid=$uid controllerMode=$controllerMode")
            if (controllerMode) {
                mainHandler.post { setupAgoraRemoteVideo(uid) }
            }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            Log.d(LOG_TAG, "peer offline uid=$uid reason=$reason")
            if (controllerMode) {
                mainHandler.post { clearAgoraVideo() }
            }
        }

        override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
            Log.d(LOG_TAG, "remote video uid=$uid state=$state reason=$reason controllerMode=$controllerMode")
            if (controllerMode) {
                mainHandler.post { setupAgoraRemoteVideo(uid) }
            }
        }

        override fun onStreamMessage(uid: Int, streamId: Int, data: ByteArray?) {
            val command = data?.decodeToString().orEmpty()
            if (!controllerMode && command.isNotBlank()) {
                commandRxStatus = "CMD RX: ${command.trim()}"
                updateCommandStatus()
                writeCommand(command, force = true)
            }
        }

        override fun onError(err: Int) {
            Log.e(LOG_TAG, "agora error=$err controllerMode=$controllerMode")
        }
    }

    override fun onDestroyView() {
        stopNetworking()
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
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            if (!controllerMode) {
                joinAgora()
                connectBluetoothDevice()
            }
        }
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST &&
            BluetoothRobotConnection.hasPermissions(requireContext())
        ) {
            connectBluetoothDevice()
        }
    }

    private val cameraSurfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            // Agora owns camera preview now; keep the legacy TextureView idle.
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

    private fun configureRole(controller: Boolean) {
        stopNetworking()
        closeCamera()
        disconnect()
        lastCommand = ""
        commandRxStatus = "CMD RX: нет"
        commandTxStatus = "TX: нет"
        connectionStatus = "BT: нет"
        updateCommandStatus()
        cameraHost = null
        controllerMode = controller
        binding.modeOverlay.visibility = View.GONE
        if (controllerMode) {
            binding.cameraPreview.visibility = View.GONE
            binding.agoraVideoContainer.visibility = View.VISIBLE
            mjpegView?.visibility = View.GONE
            binding.driveJoystick.visibility = View.VISIBLE
            binding.turnJoystick.visibility = View.VISIBLE
            binding.commandStatus.visibility = View.GONE
            joinAgora()
        } else {
            binding.cameraPreview.visibility = View.GONE
            binding.agoraVideoContainer.visibility = View.VISIBLE
            mjpegView?.visibility = View.GONE
            binding.driveJoystick.visibility = View.GONE
            binding.turnJoystick.visibility = View.GONE
            binding.commandStatus.visibility = View.VISIBLE
            updateCommandStatus()
            val missingPermissions = listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).filter {
                ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
            }
            if (missingPermissions.isNotEmpty()) {
                requestPermissions(missingPermissions.toTypedArray(), CAMERA_PERMISSION_REQUEST)
                return
            }
            joinAgora()
            connectBluetoothDevice()
        }
    }

    private fun joinAgora() {
        leaveAgora()
        try {
            rtcEngine = RtcEngine.create(requireContext().applicationContext, AGORA_APP_ID, rtcEventHandler).apply {
                enableVideo()
                enableAudio()
                setVideoEncoderConfiguration(
                    VideoEncoderConfiguration(
                        VideoEncoderConfiguration.VideoDimensions(640, 360),
                        VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                        600,
                        VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
                    )
                )
                setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
                setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
            }

            if (controllerMode) {
                clearAgoraVideo()
            } else {
                setupAgoraLocalVideo()
                rtcEngine?.startPreview()
            }

            val options = ChannelMediaOptions().apply {
                clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                publishCameraTrack = !controllerMode
                publishMicrophoneTrack = !controllerMode
                autoSubscribeVideo = true
                autoSubscribeAudio = true
            }
            rtcEngine?.joinChannel(AGORA_TOKEN.ifBlank { null }, AGORA_CHANNEL, 0, options)
        } catch (error: Exception) {
            Log.e(LOG_TAG, "failed to join agora", error)
            leaveAgora()
        }
    }

    private fun setupAgoraLocalVideo() {
        clearAgoraVideo()
        agoraSurfaceView = SurfaceView(requireContext())
        binding.agoraVideoContainer.addView(agoraSurfaceView)
        rtcEngine?.setupLocalVideo(VideoCanvas(agoraSurfaceView, VideoCanvas.RENDER_MODE_HIDDEN, 0))
    }

    private fun setupAgoraRemoteVideo(uid: Int) {
        clearAgoraVideo()
        agoraSurfaceView = SurfaceView(requireContext())
        binding.agoraVideoContainer.addView(agoraSurfaceView)
        rtcEngine?.setupRemoteVideo(VideoCanvas(agoraSurfaceView, VideoCanvas.RENDER_MODE_HIDDEN, uid))
    }

    private fun clearAgoraVideo() {
        binding.agoraVideoContainer.removeAllViews()
        agoraSurfaceView = null
    }

    private fun leaveAgora() {
        rtcEngine?.stopPreview()
        rtcEngine?.leaveChannel()
        rtcEngine?.let { RtcEngine.destroy() }
        rtcEngine = null
        commandStreamId = null
        if (_binding != null) clearAgoraVideo()
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
        imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).also { reader ->
            reader.setOnImageAvailableListener({ readyReader ->
                val image = readyReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    image.use {
                        latestFrame.set(imageToJpeg(it, 55))
                    }
                } catch (_: Exception) {
                    try {
                        image.close()
                    } catch (_: Exception) {
                    }
                }
            }, mainHandler)
        }
        val frameSurface = imageReader?.surface
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            frameSurface?.let { addTarget(it) }
        }

        camera.createCaptureSession(
            listOfNotNull(surface, frameSurface),
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
            imageReader?.close()
        } finally {
            cameraSession = null
            cameraDevice = null
            imageReader = null
            latestFrame.set(null)
        }
    }

    private fun stopNetworking() {
        leaveAgora()
        mjpegServer?.stop()
        mjpegServer = null
        commandServer?.stop()
        commandServer = null
        cameraBeacon?.stop()
        cameraBeacon = null
        cameraDiscovery?.stop()
        cameraDiscovery = null
        cameraScanner?.stop()
        cameraScanner = null
        commandSender?.stop()
        commandSender = null
        mjpegView?.stop()
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
            .firstOrNull()
        if (selected == null) {
            connectionStatus = "Arduino: не найден"
            updateCommandStatus()
            return
        }

        if (usbManager.hasPermission(selected)) {
            openDevice(selected)
        } else {
            try {
                connectionStatus = "Arduino: запрос разрешения"
                updateCommandStatus()
                usbManager.requestPermission(selected, usbPermissionIntent())
            } catch (_: RuntimeException) {
                connectionStatus = "Arduino: ошибка разрешения"
                updateCommandStatus()
            }
        }
    }

    private fun connectBluetoothDevice() {
        if (serialConnection != null) {
            connectionStatus = "BT: подключен"
            updateCommandStatus()
            return
        }
        if (!BluetoothRobotConnection.hasPermissions(requireContext())) {
            connectionStatus = "BT: нужно разрешение"
            updateCommandStatus()
            requestPermissions(
                BluetoothRobotConnection.requiredPermissions(),
                BLUETOOTH_PERMISSION_REQUEST
            )
            return
        }

        connectionStatus = "BT: подключение"
        updateCommandStatus()
        thread(name = "ArduinoBluetoothConnect") {
            try {
                val connection = BluetoothRobotConnection.openFirstPaired(requireContext().applicationContext)
                serialConnection = connection
                mainHandler.post {
                    connectionStatus = "BT: подключен"
                    lastCommand = ""
                    commandTxStatus = "TX: готов"
                    updateCommandStatus()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    connectionStatus = "BT: ошибка ${error.message.orEmpty()}"
                    updateCommandStatus()
                    closeSerialConnection()
                }
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
                    connectionStatus = "Arduino: подключен"
                    updateCommandStatus()
                    lastCommand = ""
                    sendDriveCommand()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    connectionStatus = "Arduino: ошибка подключения"
                    updateCommandStatus()
                }
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
        if (controllerMode) {
            commandStreamId?.let { streamId ->
                rtcEngine?.sendStreamMessage(streamId, command.toByteArray(Charsets.US_ASCII))
            }
        } else {
            writeCommand(command)
        }
    }

    private fun writeCommand(command: String, force: Boolean = false) {
        val outgoing = command.trim()
        if (outgoing.isBlank()) return
        val commandLine = "$outgoing\n"
        if (!force && commandLine == lastCommand) return
        lastCommand = commandLine
        val sequence = commandWriteSequence.incrementAndGet()
        commandTxStatus = "TX: $outgoing"
        updateCommandStatus()

        pendingCommandWrite.set(PendingCommand(commandLine, outgoing, sequence))
        startCommandWriterIfNeeded()
    }

    private fun startCommandWriterIfNeeded() {
        if (!commandWriterActive.compareAndSet(false, true)) return
        commandWriteExecutor.execute {
            try {
                while (true) {
                    val pending = pendingCommandWrite.getAndSet(null) ?: break
                    val connection = serialConnection
                    if (connection == null) {
                        mainHandler.post {
                            if (pending.sequence == commandWriteSequence.get()) {
                                commandTxStatus = "TX: нет соединения"
                                updateCommandStatus()
                            }
                        }
                        break
                    }
                    try {
                        connection.write(pending.line.toByteArray(Charsets.US_ASCII))
                        mainHandler.post {
                            if (pending.sequence == commandWriteSequence.get()) {
                                commandTxStatus = "TX OK: ${pending.label}"
                                updateCommandStatus()
                            }
                        }
                    } catch (error: IOException) {
                        mainHandler.post {
                            if (pending.sequence == commandWriteSequence.get()) {
                                commandTxStatus = "TX: ошибка записи"
                                updateCommandStatus()
                            }
                            closeSerialConnection()
                        }
                        break
                    }
                }
            } finally {
                commandWriterActive.set(false)
                if (pendingCommandWrite.get() != null) {
                    startCommandWriterIfNeeded()
                }
            }
        }
    }

    private fun disconnect() {
        closeSerialConnection()
    }

    private fun closeSerialConnection() {
        commandWriteSequence.incrementAndGet()
        pendingCommandWrite.set(null)
        try {
            serialConnection?.close()
        } catch (_: IOException) {
        } finally {
            serialConnection = null
            connectionStatus = "BT: отключен"
            updateCommandStatus()
        }
    }

    private fun updateCommandStatus() {
        if (_binding == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateCommandStatus() }
            return
        }
        binding.commandStatus.text = "$connectionStatus\n$commandRxStatus\n$commandTxStatus"
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.omniwheels.USB_PERMISSION"
        private const val CAMERA_PERMISSION_REQUEST = 7
        private const val BLUETOOTH_PERMISSION_REQUEST = 8
        private const val BAUD_RATE = 115200
        private const val FULL_PWM = 255
    }

    private data class PendingCommand(
        val line: String,
        val label: String,
        val sequence: Long
    )
}

private class UdpCommandSender(private val port: Int) {
    private val running = AtomicBoolean(true)

    fun send(host: String, command: String) {
        if (!running.get()) return
        thread(name = "OmniUdpScreen") {
            runCatching {
                val bytes = command.toByteArray(Charsets.US_ASCII)
                DatagramSocket().use { socket ->
                    socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port))
                }
            }
        }
    }

    fun stop() {
        running.set(false)
    }
}

private class UdpCommandServer(
    private val port: Int,
    private val onCommand: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "OmniUdpRobot") {
            try {
                DatagramSocket(port).use { server ->
                    socket = server
                    val buffer = ByteArray(96)
                    while (running.get()) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        server.receive(packet)
                        val command = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII).trim()
                        if (command == "STOP" || command.startsWith("M ")) {
                            onCommand("$command\n")
                        }
                    }
                }
            } catch (_: SocketException) {
            } catch (_: IOException) {
            } finally {
                socket = null
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        worker?.interrupt()
        worker = null
    }
}

private class UdpCameraBeacon(private val port: Int) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "OmniCameraBeacon") {
            while (running.get()) {
                runCatching {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        val bytes = "ROBOT_CAMERA $STREAM_PORT".toByteArray(Charsets.US_ASCII)
                        broadcastTargets().forEach { target ->
                            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(target), port))
                        }
                    }
                }
                Thread.sleep(1000)
            }
        }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }
}

private class UdpCameraDiscovery(
    private val port: Int,
    private val onCameraFound: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "OmniCameraDiscovery") {
            try {
                DatagramSocket(port).use { receiver ->
                    socket = receiver
                    val buffer = ByteArray(64)
                    while (running.get()) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        receiver.receive(packet)
                        val message = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII).trim()
                        if (message.startsWith("ROBOT_CAMERA")) {
                            val host = packet.address.hostAddress ?: continue
                            mainHandler.post { onCameraFound(host) }
                        }
                    }
                }
            } catch (_: SocketException) {
            } catch (_: IOException) {
            } finally {
                socket = null
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        worker?.interrupt()
        worker = null
    }
}

private class CameraSubnetScanner(
    private val onCameraFound: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var executor = Executors.newFixedThreadPool(24)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ownIp = localIpAddresses().firstOrNull() ?: return
        val prefix = ownIp.substringBeforeLast('.', missingDelimiterValue = "")
        if (prefix.isBlank()) return
        for (hostSuffix in 1..254) {
            val host = "$prefix.$hostSuffix"
            if (host == ownIp) continue
            executor.execute {
                if (!running.get()) return@execute
                if (probe(host)) {
                    mainHandler.post { onCameraFound(host) }
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        executor.shutdownNow()
        executor = Executors.newFixedThreadPool(24)
    }

    private fun probe(host: String): Boolean {
        return try {
            val connection = URL("http://$host:$STREAM_PORT$STREAM_PATH").openConnection() as HttpURLConnection
            connection.connectTimeout = 250
            connection.readTimeout = 250
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.connect()
            val isCamera =
                connection.responseCode == HttpURLConnection.HTTP_OK &&
                    (connection.contentType?.contains("multipart/x-mixed-replace") == true)
            connection.disconnect()
            isCamera
        } catch (_: Exception) {
            false
        }
    }
}

private class MjpegServer(
    private val port: Int,
    private val latestFrame: AtomicReference<ByteArray>
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArraySet<Socket>()

    fun start() {
        running = true
        thread(name = "OmniMjpegServer") {
            try {
                serverSocket = ServerSocket(port)
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    clients += socket
                    thread(name = "OmniMjpegClient") {
                        serveClient(socket)
                    }
                }
            } catch (_: IOException) {
                running = false
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        clients.forEach { socket ->
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
        clients.clear()
    }

    private fun serveClient(socket: Socket) {
        try {
            socket.getInputStream().bufferedReader().readLine()
            val output = socket.getOutputStream()
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Connection: close\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Pragma: no-cache\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n"
                    ).toByteArray()
            )

            while (running && !socket.isClosed) {
                val frame = latestFrame.get()
                if (frame == null) {
                    Thread.sleep(50)
                    continue
                }
                output.write("--frame\r\n".toByteArray())
                output.write("Content-Type: image/jpeg\r\n".toByteArray())
                output.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                output.write(frame)
                output.write("\r\n".toByteArray())
                output.flush()
                Thread.sleep(90)
            }
        } catch (_: Exception) {
        } finally {
            clients -= socket
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }
}

private class MjpegView(context: Context) : View(context) {
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var currentUrl: String? = null
    private var bitmap: Bitmap? = null

    fun play(url: String) {
        if (url == currentUrl && running) return
        stop()
        currentUrl = url
        running = true
        worker = thread(name = "OmniMjpegViewer") {
            readStream(url)
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun readStream(url: String) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 8000
            connection.connect()
            DataInputStream(connection.inputStream).use { input ->
                while (running) {
                    val frame = readJpeg(input) ?: break
                    val decoded = BitmapFactory.decodeByteArray(frame, 0, frame.size)
                    if (decoded != null) {
                        bitmap = decoded
                        postInvalidate()
                    }
                }
            }
            connection.disconnect()
        } catch (_: Exception) {
            running = false
        }
    }

    private fun readJpeg(input: DataInputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        var previous = -1
        var started = false
        while (running) {
            val current = try {
                input.readUnsignedByte()
            } catch (_: IOException) {
                return null
            }
            if (!started && previous == 0xFF && current == 0xD8) {
                started = true
                buffer.write(0xFF)
            }
            if (started) {
                buffer.write(current)
                if (previous == 0xFF && current == 0xD9) {
                    return buffer.toByteArray()
                }
            }
            previous = current
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(android.graphics.Color.BLACK)
        val frame = bitmap ?: return
        val scale = maxOf(width.toFloat() / frame.width, height.toFloat() / frame.height)
        val drawnWidth = (frame.width * scale).toInt()
        val drawnHeight = (frame.height * scale).toInt()
        val left = (width - drawnWidth) / 2
        val top = (height - drawnHeight) / 2
        canvas.drawBitmap(frame, null, Rect(left, top, left + drawnWidth, top + drawnHeight), null)
    }
}

private fun imageToJpeg(image: Image, quality: Int): ByteArray {
    val nv21 = yuv420ToNv21(image)
    val output = ByteArrayOutputStream()
    YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        .compressToJpeg(Rect(0, 0, image.width, image.height), quality, output)
    return output.toByteArray()
}

private fun yuv420ToNv21(image: Image): ByteArray {
    val width = image.width
    val height = image.height
    val ySize = width * height
    val uvSize = width * height / 4
    val output = ByteArray(ySize + uvSize * 2)
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, width, height, output, 0, 1)
    copyPlane(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, width / 2, height / 2, output, ySize, 2)
    copyPlane(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, width / 2, height / 2, output, ySize + 1, 2)
    return output
}

private fun copyPlane(
    buffer: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
    output: ByteArray,
    offset: Int,
    outputPixelStride: Int
) {
    val row = ByteArray(rowStride)
    var outputOffset = offset
    buffer.rewind()
    for (rowIndex in 0 until height) {
        val bytesPerRow = if (rowIndex == height - 1) {
            minOf(buffer.remaining(), rowStride)
        } else {
            rowStride
        }
        buffer.get(row, 0, bytesPerRow)
        for (column in 0 until width) {
            output[outputOffset] = row[column * pixelStride]
            outputOffset += outputPixelStride
        }
    }
}

private fun localIpAddresses(): List<String> {
    return try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { networkInterface ->
                Collections.list(networkInterface.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { address ->
                        address.hostAddress
                            ?.takeIf { !address.isLoopbackAddress && !it.startsWith("169.254.") }
                    }
            }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }
}

private fun broadcastTargets(): List<String> {
    return (localIpAddresses().mapNotNull { address ->
        val prefix = address.substringBeforeLast('.', missingDelimiterValue = "")
        prefix.takeIf { it.isNotBlank() }?.let { "$it.255" }
    } + "255.255.255.255").distinct()
}
