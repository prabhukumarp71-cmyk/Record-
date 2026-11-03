package com.example.camera

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import com.example.model.RecordingState
import com.example.service.EmergencyAccessibilityService
import com.example.service.RecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object EmergencyKeyManager {
    private const val TAG = "EmergencyKeyManager"
    const val HOLD_START_DURATION_MS: Long = 4000L
    const val HOLD_STOP_DURATION_MS: Long = 2000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isVolUpPressed = false
    private var isVolDownPressed = false
    private var bothHeldStartTime: Long = 0L
    private var singleHeldKeyCode: Int? = null
    private var singleHeldStartTime: Long = 0L
    private var isTriggerExecuted = false
    private var pendingHoldRunnable: Runnable? = null
    private var lastToggleTime: Long = 0L

    private val _emergencyTriggerCount = MutableStateFlow(0)
    val emergencyTriggerCount: StateFlow<Int> = _emergencyTriggerCount

    private fun isCurrentlyRecording(): Boolean {
        val state = RecordingManager.recordingState.value
        return state == RecordingState.RECORDING || state == RecordingState.STARTING || state == RecordingState.PAUSED
    }

    @Synchronized
    fun onKeyEvent(context: Context, event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            return false
        }

        val currentTime = System.currentTimeMillis()
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val isFirstDown = event.repeatCount == 0
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) isVolUpPressed = true
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) isVolDownPressed = true

                var requiredDuration = if (isCurrentlyRecording()) HOLD_STOP_DURATION_MS else HOLD_START_DURATION_MS

                if (!isVolUpPressed || !isVolDownPressed) {
                    if (isFirstDown) {
                        singleHeldKeyCode = keyCode
                        singleHeldStartTime = currentTime
                        isTriggerExecuted = false
                        pendingHoldRunnable?.let { mainHandler.removeCallbacks(it) }

                        val holdTask = Runnable {
                            synchronized(this) {
                                val isPressed = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) isVolUpPressed else isVolDownPressed
                                if (isPressed && !isTriggerExecuted) {
                                    isTriggerExecuted = true
                                    lastToggleTime = System.currentTimeMillis()
                                    handleEmergencyToggle(context.applicationContext, false)
                                }
                            }
                        }
                        pendingHoldRunnable = holdTask
                        mainHandler.postDelayed(holdTask, requiredDuration)
                    } else if (!isTriggerExecuted && currentTime - singleHeldStartTime >= requiredDuration) {
                        isTriggerExecuted = true
                        pendingHoldRunnable?.let { mainHandler.removeCallbacks(it) }
                        pendingHoldRunnable = null
                        lastToggleTime = currentTime
                        handleEmergencyToggle(context.applicationContext, false)
                        return true
                    }
                    return isTriggerExecuted
                }

                if (bothHeldStartTime == 0L) {
                    bothHeldStartTime = currentTime
                    isTriggerExecuted = false
                    pendingHoldRunnable?.let { mainHandler.removeCallbacks(it) }

                    val holdTask = Runnable {
                        synchronized(this) {
                            if (isVolUpPressed && isVolDownPressed && !isTriggerExecuted) {
                                isTriggerExecuted = true
                                lastToggleTime = System.currentTimeMillis()
                                handleEmergencyToggle(context.applicationContext, true)
                            }
                        }
                    }
                    pendingHoldRunnable = holdTask
                    mainHandler.postDelayed(holdTask, requiredDuration)
                } else if (!isTriggerExecuted && currentTime - bothHeldStartTime >= requiredDuration) {
                    isTriggerExecuted = true
                    pendingHoldRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingHoldRunnable = null
                    lastToggleTime = currentTime
                    handleEmergencyToggle(context.applicationContext, true)
                    return true
                }
                return isTriggerExecuted
            }

            KeyEvent.ACTION_UP -> {
                val wasTriggered = isTriggerExecuted
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) isVolUpPressed = false
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) isVolDownPressed = false

                if (!isVolUpPressed || !isVolDownPressed) {
                    bothHeldStartTime = 0L
                }
                if (!isVolUpPressed && !isVolDownPressed) {
                    pendingHoldRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingHoldRunnable = null
                    singleHeldKeyCode = null
                    singleHeldStartTime = 0L
                    isTriggerExecuted = false
                }
                return wasTriggered
            }

            else -> return false
        }
    }

    private fun handleEmergencyToggle(context: Context, isBothKeys: Boolean) {
        _emergencyTriggerCount.value = _emergencyTriggerCount.value + 1
        mainHandler.post {
            val isRecording = isCurrentlyRecording()
            Log.d(TAG, "Executing emergency toggle. isRecording=$isRecording, isBothKeys=$isBothKeys")
            if (isRecording) {
                vibrateStop(context)
                val msg = if (isBothKeys) "⏹️ Emergency Recording Stopped (Both keys held 2s)" else "⏹️ Emergency Recording Stopped (Held 2s)"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                val stopIntent = Intent(context, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_STOP
                }
                context.startService(stopIntent)
            } else {
                RecordingManager.resetStateIfError()
                vibrateStart(context)
                val msg = if (isBothKeys) "🚨 Emergency Recording Started (Both keys held 4s)" else "🚨 Emergency Recording Started (Held 4s)"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                val startIntent = Intent(context, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }
            }
        }
    }

    fun vibrateStart(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 250), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 250), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(longArrayOf(0, 200, 100, 250), -1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration feedback unavailable", e)
        }
    }

    fun vibrateStop(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(350L, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(350L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(350L)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration feedback unavailable", e)
        }
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, EmergencyAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentStr = splitter.next()
            val component = ComponentName.unflattenFromString(componentStr)
            if (component == expected) return true
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings", e)
        }
    }
}
