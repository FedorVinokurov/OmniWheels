package com.example.omniwheels

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
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
import android.provider.Settings
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
private const val PREFS_NAME = "omniwheels_control_settings"
private const val PREF_FORWARD_SPEED = "forward_speed"
private const val PREF_TURN_BUTTON_SPEED = "turn_button_speed"
private const val PREF_TURN_BUTTON_DURATION = "turn_button_duration"
private const val PREF_DRIVE_TURN_SLOWDOWN = "drive_turn_slowdown"

// Лимиты для защиты сервопривода (чтобы не клинило)
private const val SERVO_SAFE_MIN = 10
private const val SERVO_SAFE_MAX = 170

// Настройки фильтрации данных
private const val SERVO_THROTTLE_MS = 65L
private const val SERVO_STEP = 2
private const val MOTOR_THROTTLE_MS = 100L
private const val MOTOR_DIRECTION_DEAD_ZONE = 0.25f
private const val FULL_PWM = 255
private const val DEFAULT_TURN_BUTTON_SPEED = 223
private const val DEFAULT_TURN_BUTTON_DURATION_MS = 100
private const val MAX_TURN_BUTTON_DURATION_MS = 3000
private const val MOCKUP_WIDTH = 1280f
private const val MOCKUP_HEIGHT = 576f

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
    private var pendingAgoraStart = false
    private var pendingBluetoothConnect = false
    private var closingApp = false

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
    private var sideSpeedLimit = 255
    private var turnSpeedLimit = 180
    private var turnButtonSpeed = DEFAULT_TURN_BUTTON_SPEED
    private var turnButtonDurationMs = DEFAULT_TURN_BUTTON_DURATION_MS
    private var driveTurnSlowdownPercent = 50
    private var driveTurnDirection = 0
    private var turnButtonRunnable: Runnable? = null

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
        closingApp = false
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.driveJoystick.snapToCardinal = false
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
        setDriveButtonListener(binding.driveForwardButton, 1f)
        setDriveButtonListener(binding.driveBackwardButton, -1f)

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
        setTurnButtonListener(binding.sideLeftButton, -1)
        setTurnButtonListener(binding.sideRightButton, 1)
        setCameraButtonListener(binding.cameraUpButton, 1f)
        setCameraButtonListener(binding.cameraDownButton, -1f)

        // Настройка слайдера с безопасными границами
        loadControlSettings()

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
        binding.turnButtonSpeedSlider.max = FULL_PWM
        binding.turnButtonSpeedSlider.progress = turnButtonSpeed
        binding.turnButtonSpeedSlider.setOnSeekBarChangeListener(axisSpeedSliderListener)
        binding.driveTurnSlowdownSlider.max = 100
        binding.driveTurnSlowdownSlider.progress = driveTurnSlowdownPercent
        binding.driveTurnSlowdownSlider.setOnSeekBarChangeListener(axisSpeedSliderListener)
        binding.turnButtonDurationSlider.max = MAX_TURN_BUTTON_DURATION_MS
        binding.turnButtonDurationSlider.progress = turnButtonDurationMs
        binding.turnButtonDurationSlider.setOnSeekBarChangeListener(axisSpeedSliderListener)

        binding.modeCamera.setOnClickListener { configureCameraMode() }
        binding.startScreenLogo.setOnClickListener { configureRole(controller = true) }
        binding.modeJoystick.setOnClickListener { configureJoystickMode() }
        binding.debugToggle.setOnClickListener {
            debugPanelVisible = !debugPanelVisible
            updateDebugPanelVisibility()
        }
        binding.closeApp.setOnClickListener { closeAppSafely() }

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

    private fun setTurnButtonListener(view: View, direction: Int) {
        view.setOnTouchListener { pressedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedView.alpha = 0.55f
                    driveTurnDirection = direction
                    val driveActive = abs(joyX) >= MOTOR_DIRECTION_DEAD_ZONE ||
                        abs(joyY) >= MOTOR_DIRECTION_DEAD_ZONE
                    if (driveActive) {
                        sendDriveCommand()
                    } else {
                        sendTurnCommand(direction * turnButtonSpeed, turnButtonDurationMs.toLong())
                    }
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE -> {
                    pressedView.alpha = 1f
                    if (driveTurnDirection == direction) {
                        driveTurnDirection = 0
                        sendDriveCommand()
                    }
                    true
                }

                else -> true
            }
        }
    }

    private fun setDriveButtonListener(view: View, y: Float) {
        view.setOnTouchListener { pressedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedView.alpha = 0.55f
                    joyX = 0f
                    joyY = y
                    updateJoystickDebug()
                    sendDriveCommand()
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE -> {
                    pressedView.alpha = 1f
                    joyX = 0f
                    joyY = 0f
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
        val forward = analogAxisSpeed(joyY, forwardSpeedLimit)
        val rotate = analogAxisSpeed(joyX, turnSpeedLimit)
        val strafe = analogAxisSpeed(rotation, sideSpeedLimit)
        val speeds = if (
            driveTurnDirection != 0 &&
            strafe == 0 &&
            abs(forward) > 0
        ) {
            driveTurnMotorSpeeds(forward, -driveTurnDirection)
        } else {
            mixedMotorSpeeds(forward, rotate, strafe)
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

    private fun mixedMotorSpeeds(forward: Int, rotate: Int, strafe: Int): IntArray {
        return intArrayOf(
            forward + rotate + strafe,
            forward - rotate - strafe,
            forward + rotate - strafe,
            forward - rotate + strafe
        ).map { it.coerceIn(-FULL_PWM, FULL_PWM) }.toIntArray()
    }

    private fun driveTurnMotorSpeeds(forward: Int, direction: Int): IntArray {
        val safeForward = forward.coerceIn(-FULL_PWM, FULL_PWM)
        val slowdown = (driveTurnSlowdownPercent / 100f).coerceIn(0f, 1f)
        val innerSpeed = (safeForward * (1f - slowdown)).roundToInt()

        val motor1: Int
        val motor2: Int
        val motor3: Int
        val motor4: Int
        if (direction > 0) {
            motor1 = safeForward
            motor2 = safeForward
            motor3 = innerSpeed
            motor4 = innerSpeed
        } else {
            motor1 = innerSpeed
            motor2 = innerSpeed
            motor3 = safeForward
            motor4 = safeForward
        }

        return intArrayOf(
            motor3,
            motor2,
            motor4,
            motor1
        )
    }

    private fun sendStopBurst() {
        joyX = 0f
        joyY = 0f
        rotation = 0f
        driveTurnDirection = 0
        turnButtonRunnable?.let { mainHandler.removeCallbacks(it) }
        turnButtonRunnable = null
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

    private fun sendTurnCommand(speed: Int, durationMs: Long) {
        val driveActive = abs(joyX) >= MOTOR_DIRECTION_DEAD_ZONE ||
            abs(joyY) >= MOTOR_DIRECTION_DEAD_ZONE
        if (driveActive) {
            sendTimedDriveTurn(speed, durationMs)
            return
        }

        val command = "TURN ${speed.coerceIn(-FULL_PWM, FULL_PWM)} ${durationMs.coerceAtLeast(0L)}\n"
        if (controllerMode) {
            sendCommandOverAgora(command)
        } else {
            writeCommand(command, force = true)
        }
    }

    private fun sendTimedDriveTurn(speed: Int, durationMs: Long) {
        turnButtonRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacks(motorSendRunnable)
        motorSendScheduled = false

        val forward = analogAxisSpeed(joyY, forwardSpeedLimit)
        val direction = if (speed >= 0) 1 else -1
        val speeds = driveTurnMotorSpeeds(forward, direction)

        desiredMotorSpeeds = speeds
        updateDesiredMotorDebug()
        sendMotorCommand(speeds, force = true)

        val restore = Runnable {
            turnButtonRunnable = null
            lastMotorCommand = ""
            sendDriveCommand()
        }
        turnButtonRunnable = restore
        mainHandler.postDelayed(restore, durationMs.coerceAtLeast(0L))
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
        if (_binding == null) return
        binding.servoAngleValue.text = "Servo: $servo1Angle°"
    }

    private fun updateSpeedLabels() {
        if (_binding == null) return
        binding.forwardSpeedValue.text = "Forward: $forwardSpeedLimit"
        binding.sideSpeedValue.text = "Left/right: $sideSpeedLimit"
        binding.turnSpeedValue.text = "Rotate: $turnSpeedLimit"
        binding.turnButtonSpeedValue.text = "Button turn speed: $turnButtonSpeed"
        binding.driveTurnSlowdownValue.text =
            "Drive turn slowdown: ${String.format(java.util.Locale.US, "%.2f", driveTurnSlowdownPercent / 100f)}"
        binding.turnButtonDurationValue.text = "Button turn time: $turnButtonDurationMs ms"
    }

    private fun updateDebugPanelVisibility() {
        if (_binding == null) return
        val visible = if (debugPanelVisible) View.VISIBLE else View.GONE
        binding.commandStatus.visibility = visible
        binding.speedControls.visibility =
            if (
                binding.driveForwardButton.visibility == View.VISIBLE &&
                (localJoystickMode || debugPanelVisible)
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
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
        if (_binding == null) return
        if (lastReleaseAt != 0L) {
            releaseDebugStatus = "RELEASE: ${SystemClock.uptimeMillis() - lastReleaseAt}ms"
        }
        binding.commandStatus.text =
            "$connectionStatus\n$joystickDebugStatus\n$desiredMotorStatus\n$releaseDebugStatus\n$commandTxStatus\n$arduinoRxStatus\n$commandRxStatus"
    }

    private fun loadControlSettings() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        forwardSpeedLimit = prefs.getInt(PREF_FORWARD_SPEED, forwardSpeedLimit).coerceIn(0, FULL_PWM)
        turnButtonSpeed = prefs.getInt(PREF_TURN_BUTTON_SPEED, turnButtonSpeed).coerceIn(0, FULL_PWM)
        turnButtonDurationMs = prefs.getInt(
            PREF_TURN_BUTTON_DURATION,
            turnButtonDurationMs
        ).coerceIn(0, MAX_TURN_BUTTON_DURATION_MS)
        driveTurnSlowdownPercent = prefs.getInt(
            PREF_DRIVE_TURN_SLOWDOWN,
            driveTurnSlowdownPercent
        ).coerceIn(0, 100)
    }

    private fun saveControlSettings() {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_FORWARD_SPEED, forwardSpeedLimit)
            .putInt(PREF_TURN_BUTTON_SPEED, turnButtonSpeed)
            .putInt(PREF_TURN_BUTTON_DURATION, turnButtonDurationMs)
            .putInt(PREF_DRIVE_TURN_SLOWDOWN, driveTurnSlowdownPercent)
            .apply()
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
            R.id.turn_button_speed_slider -> turnButtonSpeed = value
            R.id.drive_turn_slowdown_slider -> driveTurnSlowdownPercent = progress.coerceIn(0, 100)
            R.id.turn_button_duration_slider -> turnButtonDurationMs =
                progress.coerceIn(0, MAX_TURN_BUTTON_DURATION_MS)
        }
        updateSpeedLabels()
        saveControlSettings()
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
        startAgoraWhenReady()
    }

    private fun configureCameraMode() {
        controllerMode = false
        localJoystickMode = false
        binding.modeOverlay.visibility = View.GONE
        showControlUi()
        binding.agoraVideoContainer.removeAllViews()
        updateDebugPanelVisibility()
        connectBluetooth()
        startAgoraWhenReady()
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
        binding.driveJoystick.visibility = View.GONE
        binding.driveForwardButton.visibility = View.VISIBLE
        binding.driveBackwardButton.visibility = View.VISIBLE
        binding.turnJoystick.visibility = View.GONE
        binding.rightControls.visibility = View.VISIBLE
        binding.debugToggle.visibility = View.GONE
        binding.closeApp.visibility = View.VISIBLE
        binding.servoControls.visibility = View.GONE
        binding.servoAngleValue.visibility = View.GONE
        binding.speedControls.visibility = if (localJoystickMode) View.VISIBLE else View.GONE
        binding.root.post { applyMockupControlPositions() }
        updateDebugPanelVisibility()
    }

    private fun hideControlUi() {
        binding.driveJoystick.visibility = View.GONE
        binding.driveForwardButton.visibility = View.GONE
        binding.driveBackwardButton.visibility = View.GONE
        binding.turnJoystick.visibility = View.GONE
        binding.rightControls.visibility = View.GONE
        binding.speedControls.visibility = View.GONE
        binding.servoControls.visibility = View.GONE
        binding.servoAngleValue.visibility = View.GONE
        binding.debugToggle.visibility = View.GONE
        binding.commandStatus.visibility = View.GONE
        binding.closeApp.visibility = View.GONE
    }

    private fun startAgoraWhenReady() {
        if (!controllerMode && !hasMediaPermissions()) {
            pendingAgoraStart = true
            requestPermissions(mediaPermissions(), CAMERA_PERMISSION_REQUEST)
            return
        }
        pendingAgoraStart = false
        initAgora()
    }

    private fun hasMediaPermissions(): Boolean {
        return mediaPermissions().all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun mediaPermissions(): Array<String> {
        return arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    private fun applyMockupControlPositions() {
        if (_binding == null) return
        val rootWidth = binding.root.width
        val rootHeight = binding.root.height
        if (rootWidth <= 0 || rootHeight <= 0) return

        val scale = rootHeight / MOCKUP_HEIGHT

        placeAnchoredView(
            binding.driveJoystick,
            x = 114f,
            y = 384f,
            width = 122f,
            height = 128f,
            anchor = MockupAnchor.LEFT_BOTTOM,
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            scale = scale
        )
        placeAnchoredView(
            binding.driveForwardButton,
            x = 100f,
            y = 297f,
            width = 142f,
            height = 123f,
            anchor = MockupAnchor.LEFT_BOTTOM,
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            scale = scale
        )
        placeAnchoredView(
            binding.driveBackwardButton,
            x = 101f,
            y = 426f,
            width = 138f,
            height = 114f,
            anchor = MockupAnchor.LEFT_BOTTOM,
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            scale = scale
        )
        placeAnchoredView(
            binding.cameraUpButton,
            x = 1082f,
            y = 105f,
            width = 110f,
            height = 100f,
            anchor = MockupAnchor.RIGHT_BOTTOM,
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            scale = scale
        )
        placeAnchoredView(
            binding.cameraDownButton,
            x = 1081f,
            y = 245f,
            width = 106f,
            height = 93f,
            anchor = MockupAnchor.RIGHT_BOTTOM,
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            scale = scale
        )
        placeAnchoredView(
            binding.sideLeftButton,
            x = 926f,
            y = 384f,
            width = 126f,
            height = 133f,
            anchor = MockupAnchor.RIGHT_BOTTOM,
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            scale = scale
        )
        placeAnchoredView(
            binding.sideRightButton,
            x = 1061f,
            y = 383f,
            width = 113f,
            height = 136f,
            anchor = MockupAnchor.RIGHT_BOTTOM,
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            scale = scale
        )
        placeAnchoredView(
            binding.debugToggle,
            x = 71f,
            y = 26f,
            width = 72f,
            height = 56f,
            anchor = MockupAnchor.LEFT_TOP,
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            scale = scale
        )
        placeAnchoredView(
            binding.closeApp,
            x = 34f,
            y = 20f,
            width = 72f,
            height = 72f,
            anchor = MockupAnchor.LEFT_BOTTOM,
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            scale = scale
        )
        placeAnchoredView(
            binding.modeCamera,
            x = 29f,
            y = 27f,
            width = 161f,
            height = 74f,
            anchor = MockupAnchor.LEFT_TOP,
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            scale = scale
        )
        placeAnchoredView(
            binding.modeJoystick,
            x = 193f,
            y = 28f,
            width = 181f,
            height = 72f,
            anchor = MockupAnchor.LEFT_TOP,
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            scale = scale
        )
        placeAnchoredView(
            binding.startScreenLogo,
            x = 507f,
            y = 195f,
            width = 270f,
            height = 186f,
            anchor = MockupAnchor.CENTER,
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            scale = scale
        )
    }

    private enum class MockupAnchor {
        LEFT_TOP,
        LEFT_BOTTOM,
        RIGHT_BOTTOM,
        CENTER_TOP,
        CENTER
    }

    private fun placeAnchoredView(
        view: View,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        anchor: MockupAnchor,
        rootWidth: Int,
        rootHeight: Int,
        scale: Float
    ) {
        val scaledWidth = width * scale
        val scaledHeight = height * scale
        val left: Float
        val top: Float

        when (anchor) {
            MockupAnchor.LEFT_TOP -> {
                left = x * scale
                top = y * scale
            }

            MockupAnchor.LEFT_BOTTOM -> {
                left = x * scale
                top = rootHeight - (MOCKUP_HEIGHT - y) * scale
            }

            MockupAnchor.RIGHT_BOTTOM -> {
                left = rootWidth - (MOCKUP_WIDTH - x) * scale
                top = rootHeight - (MOCKUP_HEIGHT - y) * scale
            }

            MockupAnchor.CENTER_TOP -> {
                val centerOffset = (x + width / 2f - MOCKUP_WIDTH / 2f) * scale
                left = rootWidth / 2f + centerOffset - scaledWidth / 2f
                top = y * scale
            }

            MockupAnchor.CENTER -> {
                val centerOffsetX = (x + width / 2f - MOCKUP_WIDTH / 2f) * scale
                val centerOffsetY = (y + height / 2f - MOCKUP_HEIGHT / 2f) * scale
                left = rootWidth / 2f + centerOffsetX - scaledWidth / 2f
                top = rootHeight / 2f + centerOffsetY - scaledHeight / 2f
            }
        }

        placeView(view, left, top, scaledWidth, scaledHeight)
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
        if (_binding == null) return
        binding.agoraVideoContainer.removeAllViews()
        val surface = SurfaceView(requireContext())
        binding.agoraVideoContainer.addView(surface)
        rtcEngine?.setupRemoteVideo(VideoCanvas(surface, VideoCanvas.RENDER_MODE_HIDDEN, uid))
    }

    private fun connectBluetooth() {
        if (closingApp || _binding == null) return
        if (!BluetoothRobotConnection.hasPermissions(requireContext())) {
            pendingBluetoothConnect = true
            requestPermissions(BluetoothRobotConnection.requiredPermissions(), BLUETOOTH_PERMISSION_REQUEST)
            return
        }

        connectionStatus = "BT: Поиск..."
        updateCommandStatus()
        thread {
            try {
                serialConnection = BluetoothRobotConnection.openFirstPaired(requireContext()) { line ->
                    mainHandler.post {
                        if (closingApp || _binding == null) return@post
                        arduinoRxStatus = line
                        updateCommandStatus()
                    }
                }
                mainHandler.post {
                    if (closingApp || _binding == null) return@post
                    connectionStatus = "BT: OK"
                    lastCommand = ""
                    lastMotorCommand = ""
                    lastServo1Command = ""
                    updateCommandStatus()
                    sendDriveCommand()
                    sendServo1Angle(servo1Angle, force = true)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (closingApp || _binding == null) return@post
                    connectionStatus = "BT: ${e.message ?: "error"}"
                    updateCommandStatus()
                    when (e.message) {
                        "Bluetooth is disabled" -> {
                            pendingBluetoothConnect = true
                            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        }

                        "No paired Bluetooth devices" -> {
                            pendingBluetoothConnect = true
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }

                        "Bluetooth pairing required" -> {
                            pendingBluetoothConnect = true
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (closingApp || _binding == null) return
        if (pendingBluetoothConnect && BluetoothRobotConnection.hasPermissions(requireContext())) {
            pendingBluetoothConnect = false
            connectBluetooth()
        }
    }

    private fun closeAppSafely() {
        closingApp = true
        pendingAgoraStart = false
        pendingBluetoothConnect = false
        mainHandler.removeCallbacksAndMessages(null)
        closeConnections()
        if (isAdded) {
            requireActivity().finish()
        }
    }

    private fun closeConnections() {
        try {
            serialConnection?.close()
        } catch (_: Exception) {
        }
        serialConnection = null

        try {
            rtcEngine?.leaveChannel()
        } catch (_: Exception) {
        }
        rtcEngine = null

        try {
            RtcEngine.destroy()
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                if (granted && pendingAgoraStart) {
                    startAgoraWhenReady()
                } else {
                    pendingAgoraStart = false
                    commandTxStatus = "CAM: permission denied"
                    updateCommandStatus()
                }
            }

            BLUETOOTH_PERMISSION_REQUEST -> {
                if (granted && pendingBluetoothConnect) {
                    pendingBluetoothConnect = false
                    connectBluetooth()
                } else {
                    pendingBluetoothConnect = false
                    connectionStatus = "BT: permission denied"
                    updateCommandStatus()
                }
            }
        }
    }

    override fun onDestroyView() {
        closingApp = true
        mainHandler.removeCallbacksAndMessages(null)
        closeConnections()
        super.onDestroyView()
        _binding = null
    }
}
