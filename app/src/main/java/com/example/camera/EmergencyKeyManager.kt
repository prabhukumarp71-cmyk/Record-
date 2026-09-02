package com.example.camera

import android.accessibilityservice.AccessibilityService
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
    const val HOLD_START_DURATION_MS = 4000L // Press and hold both keys for 4 seconds to start
    const val HOLD_STOP_DURATION_MS = 2000L  // Press and hold both keys for 2 seconds to stop

    private val mainHandler = Handler(Looper.getMainLooper())

    private var isVolUpPressed = false
    private var isVolDownPressed = false
    private var bothHeldStartTime = 0L
    private var singleHeldKeyCode: Int? = null
    private var singleHeldStartTime = 0L
    private var isTriggerExecuted = false
    private var pendingHoldRunnable: Runnable? = null
    private var lastToggleTime = 0L

    private val _emergencyTriggerCount = MutableStateFlow(0)
    val emergencyTriggerCount: StateFlow<Int> = _emergencyTriggerCount

    /**
     * Primary entry point for hardware key events (Volume Up / Volume Down).
     * Handles: Hold both keys (4s to start, 2s to stop).
     */
    @Synchronized
    fun onKeyEvent(context: Context, event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            return false
        }

        val currentTime = System.currentTimeMillis()

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val isFirstDown = (event.repeatCount == 0)

                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    isVolUpPressed = true
                } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    isVolDownPressed = true
                }

                // Check if BOTH keys are currently pressed
                if (isVolUpPressed && isVolDownPressed) {
                    if (bothHeldStartTime == 0L) {
                        bothHeldStartTime = currentTime
                        isTriggerExecuted = false

                        // Cancel single key pending hold
                        pendingHoldRunnable?.let { mainHandler.removeCallbacks(it) }

                        val isRecording = isCurrentlyRecording()
                        val requiredDuration = if (isRecording) HOLD_STOP_DURATION_MS else HOLD_START_DURATION_MS

                        Log.d(TAG, "Both volume keys pressed down. Required hold time: ${requiredDuration}ms (isRecording=$isRecording)")

                        val holdTask = Runnable {
                            synchronized(EmergencyKeyManager) {
                                if (isVolUpPressed && isVolDownPressed && !isTriggerExecuted) {
                                    isTriggerExecuted = true
                                    lastToggleTime = System.currentTimeMillis()
                                    Log.d(TAG, "Both keys held for ${requiredDuration}ms! Triggering emergency recording toggle.")
                                    handleEmergencyToggle(context.applicationContext, isBothKeys = true)
                                }
                            }
                        }
                        pendingHoldRunnable = holdTask
                        mainHandler.postDelayed(holdTask, requiredDuration)
                    } else {
                        // Check if held duration elapsed via repeat events
                        val isRecording = isCurrentlyRecording()
                        val requiredDuration = if (isRecording) HOLD_STOP_DURATION_MS else HOLD_START_DURATION_MS
                        if (!isTriggerExecuted && (currentTime - bothHeldStartTime >= requiredDuration)) {
                            isTriggerExecuted = true
                            pendingHoldRunnable?.let { mainHandler.removeCallbacks(it) }
                            pendingHoldRunnable = null
                            lastToggleTime = currentTime
                            Log.d(TAG, "Both keys hold completed via repeat event (${requiredDuration}ms)!")
                            handleEmergencyToggle(context.applicationContext, isBothKeys = true)
                            return true
                        }
                    }
                    return isTriggerExecuted
                } else {
                    // Only ONE key is currently pressed
                    if (isFirstDown) {
                        singleHeldKeyCode = keyCode
                        singleHeldStartTime = currentTime
                        isTriggerExecuted = false

                        // Cancel previous task
                        pendingHoldRunnable?.let { mainHandler.removeCallbacks(it) }

                        val isRecording = isCurrentlyRecording()
                        val requiredDuration = if (isRecording) HOLD_STOP_DURATION_MS else HOLD_START_DURATION_MS

                        val singleHoldTask = Runnable {
                            synchronized(EmergencyKeyManager) {
                                val isStillHeld = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) isVolUpPressed else isVolDownPressed
                                if (isStillHeld && !isTriggerExecuted) {
                                    isTriggerExecuted = true
                                    lastToggleTime = System.currentTimeMillis()
                                    Log.d(TAG, "Single key held for ${requiredDuration}ms! Triggering.")
                                    handleEmergencyToggle(context.applicationContext, isBothKeys = false)
                                }
                            }
                        }
                        pendingHoldRunnable = singleHoldTask
                        mainHandler.postDelayed(singleHoldTask, requiredDuration)
                    } else {
                        // Repeat event for single key
                        val isRecording = isCurrentlyRecording()
                        val requiredDuration = if (isRecording) HOLD_STOP_DURATION_MS else HOLD_START_DURATION_MS
                        if (!isTriggerExecuted && (currentTime - singleHeldStartTime >= requiredDuration)) {
                            isTriggerExecuted = true
                            pendingHoldRunnable?.let { mainHandler.removeCallbacks(it) }
                            pendingHoldRunnable = null
                            lastToggleTime = currentTime
                            Log.d(TAG, "Single key hold completed via repeat event (${requiredDuration}ms)!")
                            handleEmergencyToggle(context.applicationContext, isBothKeys = false)
                            return true
                        }
                    }
                }
                return isTriggerExecuted
            }

            KeyEvent.ACTION_UP -> {
                val wasTriggered = isTriggerExecuted

                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    isVolUpPressed = false
                } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    isVolDownPressed = false
                }

                // If either key is released, cancel both-keys hold timer
                if (!isVolUpPressed || !isVolDownPressed) {
                    bothHeldStartTime = 0L
                }

                // If all keys released, clear pending tasks
                if (!isVolUpPressed && !isVolDownPressed) {
                    pendingHoldRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingHoldRunnable = null
                    singleHeldKeyCode = null
                    singleHeldStartTime = 0L
                    isTriggerExecuted = false
                }

                return wasTriggered
            }
        }
        return false
    }

    private fun isCurrentlyRecording(): Boolean {
        val state = RecordingManager.recordingState.value
        return state == RecordingState.RECORDING || state == RecordingState.STARTING || state == RecordingState.PAUSED
    }

    /**
     * Helper for volume key press
     */
    fun onVolumeKeyPressed(context: Context, keyCode: Int): Boolean {
        val simulatedDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        return onKeyEvent(context, simulatedDown)
    }

    /**
     * Helper for volume down
     */
    fun onVolumeDownPressed(context: Context): Boolean {
        return onVolumeKeyPressed(context, KeyEvent.KEYCODE_VOLUME_DOWN)
    }

    private fun handleEmergencyToggle(context: Context, isBothKeys: Boolean) {
        _emergencyTriggerCount.value += 1

        mainHandler.post {
            val isRecording = isCurrentlyRecording()
            Log.d(TAG, "Executing emergency toggle. isRecording=$isRecording, isBothKeys=$isBothKeys")

            if (isRecording) {
                // Stop and save after 2s hold
                vibrateStop(context)
                val msg = if (isBothKeys) "⏹️ Emergency Recording Stopped (Both keys held 2s)" else "⏹️ Emergency Recording Stopped (Held 2s)"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                val stopIntent = Intent(context, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_STOP
                }
                context.startService(stopIntent)
            } else {
                // Start new recording after 4s hold
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

    /**
     * Tactile confirmation vibration pattern on Start (two distinct strong pulses).
     */
    fun vibrateStart(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 250), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 250), -1)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(longArrayOf(0, 200, 100, 250), -1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration feedback unavailable", e)
        }
    }

    /**
     * Tactile confirmation vibration pattern on Stop (one long pulse).
     */
    fun vibrateStop(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(350)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration feedback unavailable", e)
        }
    }

    /**
     * Alias for UI testing
     */
    fun vibrateEmergency(context: Context) = vibrateStart(context)

    /**
     * Checks if the Background Emergency Accessibility Service is enabled in Android System Settings.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = ComponentName(context, EmergencyAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    /**
     * Opens Android System Accessibility Settings to let the user enable global hardware key detection.
     */
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
