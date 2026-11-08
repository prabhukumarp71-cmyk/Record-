package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.MainActivity
import com.example.camera.RecordingManager
import com.example.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RecordingService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        private const val TAG = "RecordingService"
    }

    private var audioManager: android.media.AudioManager? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                // Must be called synchronously to satisfy Android foreground service requirements
                promoteToForeground()
                startRecording()
            }
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "BackgroundRecorder::WakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(3 * 60 * 60 * 1000L) // 3 hours timeout safety
                }
                Log.d(TAG, "Acquired partial wake lock for background recording")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Released wake lock")
            }
            wakeLock = null
        } catch (_: Exception) {}
    }

    private fun promoteToForeground() {
        val notification = createNotification("🔴 Background Video Recording Active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                androidx.core.app.ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    serviceType
                )
            } catch (e: Exception) {
                Log.e(TAG, "ServiceCompat.startForeground failed", e)
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun requestCameraAudioFocus() {
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                audioFocusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                audioFocusRequest?.let { audioManager?.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    null,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        } catch (_: Exception) {}
    }

    private fun abandonCameraAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    private fun startRecording() {
        acquireWakeLock()
        lifecycleScope.launch {
            try {
                val audioEnabled = AppSettings.isAudioEnabled(this@RecordingService).first()
                if (audioEnabled) {
                    requestCameraAudioFocus()
                }

                val qualityStr = AppSettings.getVideoQuality(this@RecordingService).first()
                val lensFacingStr = AppSettings.getCameraLens(this@RecordingService).first()
                val quality = RecordingManager.parseQuality(qualityStr)
                val lensFacing = if (lensFacingStr == "FRONT") {
                    androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                } else {
                    androidx.camera.core.CameraSelector.LENS_FACING_BACK
                }

                // Crucial: Bind camera directly to this LifecycleService instance so recording
                // continues uninterrupted when MainActivity goes to background or screen turns off!
                RecordingManager.bindCamera(
                    context = this@RecordingService,
                    lifecycleOwner = this@RecordingService,
                    lensFacing = lensFacing,
                    quality = quality,
                    surfaceProvider = RecordingManager.currentSurfaceProvider
                ) {
                    RecordingManager.startRecording(this@RecordingService, audioEnabled)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed starting recording in background service", e)
            }
        }
    }

    private fun stopRecording() {
        releaseWakeLock()
        abandonCameraAudioFocus()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification("💾 Saving Video..."))
        RecordingManager.stopRecording {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {}
            stopSelf()
        }
    }

    private fun pauseRecording() {
        RecordingManager.pauseRecording()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification("⏸️ Video recording paused"))
    }

    private fun resumeRecording() {
        RecordingManager.resumeRecording()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification("🔴 Background Video Recording Active"))
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Background Video Recorder")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used to show ongoing background video recording"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        abandonCameraAudioFocus()
        RecordingManager.unbind()
    }
}
