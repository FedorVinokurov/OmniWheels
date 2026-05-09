package com.example.omniwheels

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import com.example.omniwheels.databinding.FragmentFirstBinding
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.DataStreamConfig
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.video.VideoCanvas
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

// Настройки подключения
private const val BAUD_RATE = 115200
private const val ACTION_USB_PERMISSION = "com.example.omniwheels.USB_PERMISSION"
private const val CAMERA_PERMISSION_REQUEST = 101
private const val BLUETOOTH_PERMISSION_REQUEST = 102

// Лимиты для защиты сервопривода (чтобы не клинило)
private const val SERVO_SAFE_MIN = 10
private const val SERVO_SAFE_MAX = 170

// Настройки фильтрации данных
private const val SERVO_THROTTLE_MS = 65L
private const val SERVO_STEP = 2
private const val MOTOR_THROTTLE_MS = 100L
private const val MOTOR_DIRECTION_DEAD_ZONE = 0.25f
private const val FULL_PWM = 255

private const val AGORA_CHANNEL = "robot-room"
private const val AGORA_APP_ID = "41f7f4e1a4bd4cda9efe3fc3696e86ae"
private const val LOG_TAG = "OmniAgora"

data class PendingCommand(val line: String, val outgoing: String, val sequence: Long)

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val usbManager: UsbManager by lazy { requireContext().getSystemService(Context.USB_SERVICE) as UsbManager }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var serialConnection: UsbSerialConnection? = null
    private var rtcEngine: RtcEngine? = null
    private var commandStreamId: Int? = null

    @Volatile private var controllerMode = false
    private var localJoystickMode = false
    private var debugPanelVisible = false
    private var joyX = 0f
    private var joyY = 0f
    private var rotation = 0f
    private var turnY = 0f

    private var servo1Angle = 90
    private var lastSentServoAngle = -1
    private var lastServoSliderSentAt = 0L
    private var lastServo1Command = ""

    private var lastMotorCommand = ""
    private var lastMotorSentAt = 0L
    private var lastCommand = ""
    private var desiredMotorSpeeds = intArrayOf(0, 0, 0, 0)
    private var motorSendScheduled = false
    private var forwardSpeedLimit = 120
    private var sideSpeedLimit = 136
    private var turnSpeedLimit = 180

    private var connectionStatus = "BT: нет"
    private var commandTxStatus = "TX: нет"
    private var arduinoRxStatus = "ARD RX: нет"
    private var commandRxStatus = "CMD RX: нет"

    private var joystickDebugStatus = "JOY L: 0,0 R: 0"
    private var desiredMotorStatus = "DES M: 0 0 0 0"
    private var releaseDebugStatus = "RELEASE: none"
    private var lastReleaseAt = 0L

    private val commandWriteExecutor = Executors.newSingleThreadExecutor()
    private val commandWriteSequence = AtomicLong(0)
    private val pendingCommandWrite = AtomicReference<PendingCommand?>(null)
    private val commandWriterActive = AtomicBoolean(false)
    private val motorSendRunnable = Runnable {
        motorSendScheduled = false
        val keepAlive = desiredMotorSpeeds.any { it != 0 }
        sendMotorCommand(desiredMotorSpeeds.copyOf(), force = keepAlive)
        if (keepAlive) {
            queueMotorSend()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.driveJoystick.snapToCardinal = true
        binding.driveJoystick.releaseListener = {
            joyX = 0f
            joyY = 0f
            sendStopBurst()
        }
        binding.driveJoystick.listener = { x, y ->
            joyX = x; joyY = y
            updateJoystickDebug()
            sendDriveCommand()
        }

        binding.turnJoystick.limitToSquare = true
        binding.turnJoystick.snapYOnly = true
        binding.turnJoystick.resetXOnRelease = true
        binding.turnJoystick.resetYOnRelease = false
        binding.turnJoystick.releaseListener = {
            rotation = 0f
            sendStopBurst()
            mainHandler.postDelayed({
                sendServo1Angle(joystickYToServoAngle(turnY), force = true)
            }, 260L)
        }
        binding.turnJoystick.listener = { x, y ->
            rotation = x
            turnY = y
            updateJoystickDebug()
            sendServo1Angle(joystickYToServoAngle(y))
            sendDriveCommand()
        }
        binding.cameraUpButton.direction = TriangleButtonView.Direction.UP
        binding.cameraDownButton.direction = TriangleButtonView.Direction.DOWN
        binding.sideLeftButton.direction = TriangleButtonView.Direction.LEFT
        binding.sideRightButton.direction = TriangleButtonView.Direction.RIGHT
        setSideButtonListener(binding.sideLeftButton, -1f)
        setSideButtonListener(binding.sideRightButton, 1f)
        setCameraButtonListener(binding.cameraUpButton, 1f)
        setCameraButtonListener(binding.cameraDownButton, -1f)

        // Настройка слайдера с безопасными границами
        binding.servoAngleSlider.max = 180
        binding.servoAngleSlider.progress = servo1Angle
        binding.servoAngleSlider.setOnSeekBarChangeListener(servoAngleSliderListener)
        binding.forwardSpeedSlider.max = FULL_PWM
        binding.forwardSpeedSlider.progress = forwardSpeedLimit
        binding.forwardSpeedSlider.setOnSeekBarChangeListener(axisSpeedSliderListener)
        binding.sideSpeedSlider.max = FULL_PWM
        binding.sideSpeedSlider.progress = sideSpeedLimit
        binding.sideSpeedSlider.setOnSeekBarChangeListener(axisSpeedSliderListener)
        binding.turnSpeedSlider.max = FULL_PWM
        binding.turnSpeedSlider.progress = turnSpeedLimit
        binding.turnSpeedSlider.setOnSeekBarChangeListener(axisSpeedSliderListener)

        binding.modeCamera.setOnClickListener { configureRole(controller = false) }
        binding.modeScreen.setOnClickListener { configureRole(controller = true) }
        binding.modeJoystick.setOnClickListener { configureJoystickMode() }
        binding.debugToggle.setOnClickListener {
            debugPanelVisible = !debugPanelVisible
            updateDebugPanelVisibility()
        }
        binding.closeApp.setOnClickListener { requireActivity().finishAndRemoveTask() }

        updateServoAngleLabel()
        updateSpeedLabels()
        updateDebugPanelVisibility()
        updateCommandStatus()
        binding.root.post { applyMockupControlPositions() }
    }

    private fun setSideButtonListener(view: View, value: Float) {
        view.setOnTouchListener { pressedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedView.alpha = 0.55f
                    rotation = value
                    updateJoystickDebug()
                    sendDriveCommand()
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE -> {
                    pressedView.alpha = 1f
                    rotation = 0f
                    updateJoystickDebug()
                    sendStopBurst()
                    true
                }

                else -> true
            }
        }
    }

    private fun setCameraButtonListener(view: View, y: Float) {
        view.setOnTouchListener { pressedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedView.alpha = 0.55f
                    turnY = y
                    updateJoystickDebug()
                    sendServo1Angle(joystickYToServoAngle(y), force = true)
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE -> {
                    pressedView.alpha = 1f
                    true
                }

                else -> true
            }
        }
    }

    private val servoAngleSliderListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (!fromUser) return

            val now = SystemClock.uptimeMillis()
            // Ограничиваем угол безопасным диапазоном 10-170
            val safeAngle = progress.coerceIn(SERVO_SAFE_MIN, SERVO_SAFE_MAX)
            val steppedAngle = (safeAngle / SERVO_STEP) * SERVO_STEP

            if (now - lastServoSliderSentAt >= SERVO_THROTTLE_MS && steppedAngle != lastSentServoAngle) {
                lastSentServoAngle = steppedAngle
                lastServoSliderSentAt = now
                servo1Angle = steppedAngle
                updateServoAngleLabel()
                sendServo1Angle(steppedAngle)
            }
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            // При отпускании шлем финальную точку (тоже в безопасном диапазоне)
            val finalAngle = (seekBar?.progress ?: servo1Angle).coerceIn(SERVO_SAFE_MIN, SERVO_SAFE_MAX)
            sendServo1Angle(finalAngle, force = true)
            lastSentServoAngle = finalAngle
            updateServoAngleLabel()
        }
    }

    private fun sendServo1Angle(angle: Int, force: Boolean = false) {
        val command = "S1 $angle\n"
        if (!force && command == lastServo1Command) return
        lastServo1Command = command

        if (controllerMode) {
            sendCommandOverAgora(command)
        } else {
            writeCommand(command, force = force)
        }
    }

    private fun joystickYToServoAngle(y: Float): Int {
        val normalized = ((1f - y.coerceIn(-1f, 1f)) / 2f)
        return (normalized * 180f).roundToInt().coerceIn(SERVO_SAFE_MIN, SERVO_SAFE_MAX)
    }

    private fun sendDriveCommand() {
        val leftJoystickActive = abs(joyX) >= MOTOR_DIRECTION_DEAD_ZONE ||
            abs(joyY) >= MOTOR_DIRECTION_DEAD_ZONE
        val speeds = if (leftJoystickActive && abs(joyY) >= abs(joyX)) {
            val speed = signedAxisSpeed(joyY, forwardSpeedLimit)
            intArrayOf(speed, speed, speed, speed)
        } else if (leftJoystickActive) {
            val speed = signedAxisSpeed(joyX, turnSpeedLimit)
            intArrayOf(speed, -speed, -speed, speed)
        } else {
            val speed = analogAxisSpeed(rotation, sideSpeedLimit)
            intArrayOf(speed, -speed, speed, -speed)
        }

        desiredMotorSpeeds = speeds
        updateDesiredMotorDebug()
        queueMotorSend()
    }

    private fun signedAxisSpeed(value: Float, speedLimit: Int): Int {
        if (speedLimit <= 0 || abs(value) < MOTOR_DIRECTION_DEAD_ZONE) return 0
        return if (value > 0f) speedLimit else -speedLimit
    }

    private fun analogAxisSpeed(value: Float, speedLimit: Int): Int {
        if (speedLimit <= 0 || abs(value) < MOTOR_DIRECTION_DEAD_ZONE) return 0
        return (value.coerceIn(-1f, 1f) * speedLimit).roundToInt()
    }

    private fun sendStopBurst() {
        joyX = 0f
        joyY = 0f
        rotation = 0f
        lastReleaseAt = SystemClock.uptimeMillis()
        updateJoystickDebug()
        updateReleaseDebug()
        desiredMotorSpeeds = intArrayOf(0, 0, 0, 0)
        updateDesiredMotorDebug()
        mainHandler.removeCallbacks(motorSendRunnable)
        motorSendScheduled = false
        pendingCommandWrite.set(null)
        lastCommand = ""
        lastMotorCommand = ""
        sendStopCommand()

        val delays = longArrayOf(0L, 40L, 100L, 220L)
        delays.forEach { delay ->
            mainHandler.postDelayed({
                pendingCommandWrite.set(null)
                lastCommand = ""
                lastMotorCommand = ""
                sendStopCommand()
            }, delay)
        }
    }

    private fun sendStopCommand() {
        if (controllerMode) {
            sendCommandOverAgora("STOP\n")
        } else {
            writeCommand("STOP\n", force = true)
        }
    }

    private fun queueMotorSend() {
        if (motorSendScheduled) return
        val elapsed = SystemClock.uptimeMillis() - lastMotorSentAt
        val delay = (MOTOR_THROTTLE_MS - elapsed).coerceAtLeast(0L)
        motorSendScheduled = true
        mainHandler.postDelayed(motorSendRunnable, delay)
    }

    private fun sendMotorCommand(speeds: IntArray, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        val shieldSpeeds = intArrayOf(
            speeds[3],
            speeds[1],
            speeds[0],
            speeds[2]
        )
        val command = "M ${shieldSpeeds[0]} ${shieldSpeeds[1]} ${shieldSpeeds[2]} ${shieldSpeeds[3]}\n"
        if (!force && command == lastMotorCommand) return
        lastMotorCommand = command
        lastMotorSentAt = now

        if (controllerMode) sendCommandOverAgora(command) else writeCommand(command, force = force)
    }

    private fun writeCommand(command: String, force: Boolean = false) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        val line = "$cmd\n"

        if (!controllerMode && serialConnection == null) {
            commandTxStatus = "TX: wait BT"
            updateCommandStatus()
            return
        }

        if (!force && line == lastCommand) return
        lastCommand = line

        val seq = commandWriteSequence.incrementAndGet()
        mainHandler.post {
            commandTxStatus = "TX: $cmd"
            if (cmd.startsWith("M ") || cmd == "STOP") {
                arduinoRxStatus = "ARD RX: no motor ack"
            }
            updateCommandStatus()
        }

        pendingCommandWrite.set(PendingCommand(line, cmd, seq))
        startCommandWriterIfNeeded()
    }

    private fun startCommandWriterIfNeeded() {
        if (!commandWriterActive.compareAndSet(false, true)) return
        commandWriteExecutor.execute {
            try {
                while (true) {
                    val p = pendingCommandWrite.getAndSet(null) ?: break
                    val conn = serialConnection ?: break
                    try {
                        conn.write(p.line.toByteArray(Charsets.US_ASCII))
                    } catch (e: IOException) {
                        mainHandler.post {
                            commandTxStatus = "TX: error"
                            updateCommandStatus()
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

    private fun updateServoAngleLabel() {
        binding.servoAngleValue.text = "Servo: $servo1Angle°"
    }

    private fun updateSpeedLabels() {
        binding.forwardSpeedValue.text = "Forward: $forwardSpeedLimit"
        binding.sideSpeedValue.text = "Turn: $sideSpeedLimit"
        binding.turnSpeedValue.text = "Side: $turnSpeedLimit"
    }

    private fun updateDebugPanelVisibility() {
        val visible = if (debugPanelVisible) View.VISIBLE else View.GONE
        binding.commandStatus.visibility = visible
        binding.speedControls.visibility =
            if (debugPanelVisible && binding.driveJoystick.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun updateJoystickDebug() {
        joystickDebugStatus = "JOY L: ${joyX.toDebug()} ${joyY.toDebug()} R: ${rotation.toDebug()}"
        updateCommandStatus()
    }

    private fun updateDesiredMotorDebug() {
        desiredMotorStatus = "DES M: ${desiredMotorSpeeds.joinToString(" ")}"
        updateCommandStatus()
    }

    private fun updateReleaseDebug() {
        releaseDebugStatus = if (lastReleaseAt == 0L) {
            "RELEASE: none"
        } else {
            "RELEASE: ${SystemClock.uptimeMillis() - lastReleaseAt}ms"
        }
        updateCommandStatus()
    }

    private fun updateCommandStatus() {
        if (lastReleaseAt != 0L) {
            releaseDebugStatus = "RELEASE: ${SystemClock.uptimeMillis() - lastReleaseAt}ms"
        }
        binding.commandStatus.text =
            "$connectionStatus\n$joystickDebugStatus\n$desiredMotorStatus\n$releaseDebugStatus\n$commandTxStatus\n$arduinoRxStatus\n$commandRxStatus"
    }

    private fun Float.toDebug(): String = String.format(java.util.Locale.US, "%.2f", this)

    private val axisSpeedSliderListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            updateAxisSpeed(seekBar, progress)
            if (controllerMode || localJoystickMode) {
                lastMotorCommand = ""
                sendDriveCommand()
            }
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            updateAxisSpeed(seekBar, seekBar?.progress ?: 0)
            if (controllerMode || localJoystickMode) {
                lastMotorCommand = ""
                sendDriveCommand()
            }
        }
    }

    private fun updateAxisSpeed(seekBar: SeekBar?, progress: Int) {
        val value = progress.coerceIn(0, FULL_PWM)
        when (seekBar?.id) {
            R.id.forward_speed_slider -> forwardSpeedLimit = value
            R.id.side_speed_slider -> sideSpeedLimit = value
            R.id.turn_speed_slider -> turnSpeedLimit = value
        }
        updateSpeedLabels()
    }

    // --- Логика Agora ---

    private val rtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            commandStreamId = rtcEngine?.createDataStream(DataStreamConfig())
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            if (controllerMode) mainHandler.post { setupRemoteVideo(uid) }
        }

        override fun onStreamMessage(uid: Int, streamId: Int, data: ByteArray?) {
            val cmd = data?.decodeToString().orEmpty()
            if (!controllerMode && cmd.isNotBlank()) {
                mainHandler.post {
                    commandRxStatus = "CMD RX: ${cmd.trim()}"
                    updateCommandStatus()
                    writeCommand(cmd, force = true)
                }
            }
        }
    }

    private fun configureRole(controller: Boolean) {
        controllerMode = controller
        localJoystickMode = false
        binding.modeOverlay.visibility = View.GONE

        if (controllerMode) {
            showControlUi()
        } else {
            showControlUi()
            connectBluetooth()
        }
        updateDebugPanelVisibility()
        initAgora()
    }

    private fun configureJoystickMode() {
        controllerMode = false
        localJoystickMode = true
        binding.modeOverlay.visibility = View.GONE
        showControlUi()
        binding.agoraVideoContainer.removeAllViews()
        updateDebugPanelVisibility()
        connectBluetooth()
    }

    private fun showControlUi() {
        binding.driveJoystick.visibility = View.VISIBLE
        binding.turnJoystick.visibility = View.GONE
        binding.rightControls.visibility = View.VISIBLE
        binding.servoControls.visibility = View.GONE
        binding.servoAngleValue.visibility = View.GONE
        binding.root.post { applyMockupControlPositions() }
        updateDebugPanelVisibility()
    }

    private fun applyMockupControlPositions() {
        val rootWidth = binding.root.width
        val rootHeight = binding.root.height
        if (rootWidth <= 0 || rootHeight <= 0) return

        val xScale = rootWidth / 1280f
        val yScale = rootHeight / 576f
        val joystickScale = minOf(xScale, yScale)

        placeView(
            binding.driveJoystick,
            left = 105.5f * xScale,
            top = 378.5f * yScale,
            width = 138f * joystickScale,
            height = 138f * joystickScale
        )
        placeView(
            binding.cameraUpButton,
            left = 1091.5f * xScale,
            top = 113.5f * yScale,
            width = 90f * xScale,
            height = 81f * yScale
        )
        placeView(
            binding.cameraDownButton,
            left = 1091.5f * xScale,
            top = 250.5f * yScale,
            width = 90f * xScale,
            height = 80f * yScale
        )
        placeView(
            binding.sideLeftButton,
            left = 938.5f * xScale,
            top = 387.5f * yScale,
            width = 110f * xScale,
            height = 124f * yScale
        )
        placeView(
            binding.sideRightButton,
            left = 1060.5f * xScale,
            top = 387.5f * yScale,
            width = 110f * xScale,
            height = 124f * yScale
        )
        placeWrapView(binding.debugToggle, left = 88f * xScale, top = 47f * yScale)
        placeWrapView(
            binding.closeApp,
            left = (640.5f * xScale) - (binding.closeApp.width / 2f),
            top = 41f * yScale
        )
    }

    private fun placeView(view: View, left: Float, top: Float, width: Float, height: Float) {
        val params = (view.layoutParams as? ConstraintLayout.LayoutParams)
            ?: ConstraintLayout.LayoutParams(width.roundToInt(), height.roundToInt())
        params.width = width.roundToInt().coerceAtLeast(1)
        params.height = height.roundToInt().coerceAtLeast(1)
        params.leftMargin = left.roundToInt()
        params.topMargin = top.roundToInt()
        params.rightMargin = 0
        params.bottomMargin = 0
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.UNSET
        params.endToStart = ConstraintLayout.LayoutParams.UNSET
        params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        params.bottomToTop = ConstraintLayout.LayoutParams.UNSET
        params.topToBottom = ConstraintLayout.LayoutParams.UNSET
        view.layoutParams = params
    }

    private fun placeWrapView(view: View, left: Float, top: Float) {
        val params = (view.layoutParams as? ConstraintLayout.LayoutParams) ?: return
        params.leftMargin = left.roundToInt()
        params.topMargin = top.roundToInt()
        params.rightMargin = 0
        params.bottomMargin = 0
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.UNSET
        params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        params.topToBottom = ConstraintLayout.LayoutParams.UNSET
        view.layoutParams = params
    }

    private fun initAgora() {
        try {
            rtcEngine = RtcEngine.create(requireContext(), AGORA_APP_ID, rtcEventHandler).apply {
                enableVideo()
                setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
                setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
            }
            if (!controllerMode) {
                val surface = SurfaceView(requireContext())
                binding.agoraVideoContainer.addView(surface)
                rtcEngine?.setupLocalVideo(VideoCanvas(surface, VideoCanvas.RENDER_MODE_HIDDEN, 0))
            }
            rtcEngine?.joinChannel(null, AGORA_CHANNEL, 0, ChannelMediaOptions().apply {
                publishCameraTrack = !controllerMode
                publishMicrophoneTrack = !controllerMode
            })
        } catch (e: Exception) { Log.e(LOG_TAG, "Agora Error", e) }
    }

    private fun sendCommandOverAgora(command: String) {
        val id = commandStreamId ?: return
        rtcEngine?.sendStreamMessage(id, command.toByteArray())
        commandTxStatus = "CMD TX: ${command.trim()}"
        updateCommandStatus()
    }

    private fun setupRemoteVideo(uid: Int) {
        binding.agoraVideoContainer.removeAllViews()
        val surface = SurfaceView(requireContext())
        binding.agoraVideoContainer.addView(surface)
        rtcEngine?.setupRemoteVideo(VideoCanvas(surface, VideoCanvas.RENDER_MODE_HIDDEN, uid))
    }

    private fun connectBluetooth() {
        connectionStatus = "BT: Поиск..."
        updateCommandStatus()
        thread {
            try {
                serialConnection = BluetoothRobotConnection.openFirstPaired(requireContext()) { line ->
                    mainHandler.post { arduinoRxStatus = line; updateCommandStatus() }
                }
                mainHandler.post {
                    connectionStatus = "BT: OK"
                    lastCommand = ""
                    lastMotorCommand = ""
                    lastServo1Command = ""
                    updateCommandStatus()
                    sendDriveCommand()
                    sendServo1Angle(servo1Angle, force = true)
                }
            } catch (e: Exception) { mainHandler.post { connectionStatus = "BT: Ошибка" } }
        }
    }

    override fun onDestroyView() {
        serialConnection?.close()
        rtcEngine?.leaveChannel()
        RtcEngine.destroy()
        super.onDestroyView()
        _binding = null
    }
}
