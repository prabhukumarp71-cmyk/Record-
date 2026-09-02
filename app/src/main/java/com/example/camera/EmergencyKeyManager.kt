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
    private const val MULTI_PRESS_WINDOW_MS = 2500L
    private const val DOUBLE_DOWN_WINDOW_MS = 800L
    private const val MIN_DEBOUNCE_INTERVAL_MS = 40L
    private const val REQUIRED_PRESS_COUNT = 4

    private data class KeyPressRecord(val keyCode: Int, val timestamp: Long)

    private val pressHistory = mutableListOf<KeyPressRecord>()

    private val _emergencyTriggerCount = MutableStateFlow(0)
    val emergencyTriggerCount: StateFlow<Int> = _emergencyTriggerCount

    /**
     * Call when either Volume Up or Volume Down key press is detected.
     * Triggers recording if 4 volume key presses (Up/Down) or 2 rapid Volume-Down presses are registered.
     * Returns true if emergency trigger condition is met and handled.
     */
    @Synchronized
    fun onVolumeKeyPressed(context: Context, keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        val currentTime = System.currentTimeMillis()

        // Remove expired presses outside the multi-press window
        pressHistory.removeAll { currentTime - it.timestamp > MULTI_PRESS_WINDOW_MS }

        // Debounce hardware contact bounce for the exact same key
        val lastPress = pressHistory.lastOrNull()
        if (lastPress != null && lastPress.keyCode == keyCode && (currentTime - lastPress.timestamp) < MIN_DEBOUNCE_INTERVAL_MS) {
            return false
        }

        pressHistory.add(KeyPressRecord(keyCode, currentTime))

        val totalPressesInWindow = pressHistory.size
        val hasBothKeys = pressHistory.any { it.keyCode == KeyEvent.KEYCODE_VOLUME_UP } &&
                pressHistory.any { it.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN }

        // Condition 1: 4 volume key presses (Volume Up and Down 4 times)
        val is4PressTrigger = totalPressesInWindow >= REQUIRED_PRESS_COUNT

        // Condition 2: 2 rapid Volume Down presses within 800ms
        val recentDownPresses = pressHistory.filter { it.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN }
        val isDoubleDownTrigger = recentDownPresses.size >= 2 &&
                (currentTime - recentDownPresses[recentDownPresses.size - 2].timestamp) <= DOUBLE_DOWN_WINDOW_MS

        if (is4PressTrigger || isDoubleDownTrigger) {
            pressHistory.clear()
            Log.d(TAG, "Emergency key trigger activated! totalPresses=$totalPressesInWindow, hasBothKeys=$hasBothKeys, is4Press=$is4PressTrigger, isDoubleDown=$isDoubleDownTrigger")
            handleEmergencyTrigger(context.applicationContext)
            return true
        }

        return false
    }

    /**
     * Legacy helper for Volume Down only
     */
    fun onVolumeDownPressed(context: Context): Boolean {
        return onVolumeKeyPressed(context, KeyEvent.KEYCODE_VOLUME_DOWN)
    }

    private fun handleEmergencyTrigger(context: Context) {
        _emergencyTriggerCount.value += 1
        vibrateEmergency(context)

        val currentState = RecordingManager.recordingState.value
        Handler(Looper.getMainLooper()).post {
            if (currentState == RecordingState.IDLE) {
                Toast.makeText(context, "🚨 Emergency Recording Started", Toast.LENGTH_SHORT).show()
                val intent = Intent(context, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                Toast.makeText(context, "🚨 Emergency Recording Active", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Tactile confirmation vibration pattern (two distinct pulses).
     */
    fun vibrateEmergency(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 180, 80, 220), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 180, 80, 220), -1)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(longArrayOf(0, 180, 80, 220), -1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration feedback unavailable", e)
        }
    }

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
