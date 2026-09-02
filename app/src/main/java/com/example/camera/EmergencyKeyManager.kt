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
import android.widget.Toast
import com.example.model.RecordingState
import com.example.service.EmergencyAccessibilityService
import com.example.service.RecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object EmergencyKeyManager {
    private const val TAG = "EmergencyKeyManager"
    private const val DOUBLE_PRESS_WINDOW_MS = 800L
    private const val MIN_PRESS_INTERVAL_MS = 60L

    private var lastVolumeDownTimestamp = 0L

    private val _emergencyTriggerCount = MutableStateFlow(0)
    val emergencyTriggerCount: StateFlow<Int> = _emergencyTriggerCount

    /**
     * Call when a Volume Down key press is detected.
     * Returns true if emergency double press was handled and consumed.
     */
    fun onVolumeDownPressed(context: Context): Boolean {
        val currentTime = System.currentTimeMillis()
        val interval = currentTime - lastVolumeDownTimestamp

        if (interval in MIN_PRESS_INTERVAL_MS..DOUBLE_PRESS_WINDOW_MS) {
            // Double press detected!
            lastVolumeDownTimestamp = 0L // Reset to prevent consecutive triple triggers
            Log.d(TAG, "Emergency double-press detected (interval: ${interval}ms)")
            handleEmergencyTrigger(context.applicationContext)
            return true
        } else {
            lastVolumeDownTimestamp = currentTime
            return false
        }
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
