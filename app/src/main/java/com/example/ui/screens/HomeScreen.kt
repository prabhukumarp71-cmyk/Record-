package com.example.ui.screens

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.RecordingManager
import com.example.model.RecordingState
import com.example.service.RecordingService
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToRecordings: () -> Unit
) {
    val context = LocalContext.current
    val recordingState by RecordingManager.recordingState.collectAsState()
    val recordingDurationMs by RecordingManager.recordingDurationMs.collectAsState()

    val currentQuality by com.example.settings.AppSettings.getVideoQuality(context).collectAsState(initial = "HIGH")
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    val isRecording = recordingState == RecordingState.RECORDING
    val isPaused = recordingState == RecordingState.PAUSED
    val isBusy = isRecording || isPaused || recordingState == RecordingState.STARTING || recordingState == RecordingState.STOPPING

    val durationText = formatDuration(recordingDurationMs)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Main Viewfinder
            DualFrameViewfinder(modifier = Modifier.fillMaxSize())

            // Top Bar Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Settings button
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .testTag("settings_button")
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White
                    )
                }

                // Quick Resolution Switcher Chip (1-tap toggle between 4K UHD & 1080p FHD)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (currentQuality == "ULTRA") Color(0xFFFF9100).copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.65f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (currentQuality == "ULTRA") Color(0xFFFF9100) else Color(0xFF00E5FF)
                    ),
                    modifier = Modifier
                        .testTag("quick_resolution_toggle")
                        .clickable(enabled = !isBusy) {
                            coroutineScope.launch {
                                val next = if (currentQuality == "ULTRA") "HIGH" else "ULTRA"
                                com.example.settings.AppSettings.setVideoQuality(context, next)
                                val label = if (next == "ULTRA") "4K UHD (3840×2160)" else "1080p FHD (1920×1080)"
                                Toast.makeText(context, "🎬 Quality: $label", Toast.LENGTH_SHORT).show()
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (currentQuality == "ULTRA") Color.White else Color(0xFF00E5FF),
                                    CircleShape
                                )
                        )
                        Text(
                            text = if (currentQuality == "ULTRA") "4K UHD" else "1080p FHD",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    }
                }

                // Recordings Library Button
                IconButton(
                    onClick = onNavigateToRecordings,
                    modifier = Modifier
                        .testTag("recordings_button")
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = "Recordings",
                        tint = Color.White
                    )
                }
            }

            // Bottom Recording Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Video Mode & Live Status Badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isBusy) Color.Red else Color.White.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (isPaused) Color.Yellow else if (isBusy) Color.Red else Color(0xFFFF5252),
                                    CircleShape
                                )
                        )
                        Text(
                            text = if (isBusy) {
                                if (isPaused) "PAUSED • $durationText" else "🔴 RECORDING ($durationText)"
                            } else {
                                "📹 VIDEO MODE • READY"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isBusy) Color.White else Color(0xFFFFCDD2),
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Controls Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pause / Resume Button
                    if (isBusy) {
                        FilledTonalButton(
                            onClick = {
                                val action = if (isPaused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE
                                val intent = Intent(context, RecordingService::class.java).apply {
                                    this.action = action
                                }
                                context.startService(intent)
                            },
                            modifier = Modifier
                                .testTag("pause_resume_button")
                                .size(48.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color.Black.copy(alpha = 0.65f)
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (isPaused) "Resume" else "Pause",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(28.dp))
                    }

                    // Primary Video Record / Stop Button
                    Box(
                        modifier = Modifier
                            .testTag("record_button")
                            .size(80.dp)
                            .background(Color.Transparent, CircleShape)
                            .clickable {
                                if (recordingState == RecordingState.STARTING || recordingState == RecordingState.STOPPING) {
                                    return@clickable
                                }
                                if (isRecording || isPaused) {
                                    val stopIntent = Intent(context, RecordingService::class.java).apply {
                                        action = RecordingService.ACTION_STOP
                                    }
                                    context.startService(stopIntent)
                                } else {
                                    val hasCamera = androidx.core.content.ContextCompat.checkSelfPermission(
                                        context,
                                        android.Manifest.permission.CAMERA
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    if (!hasCamera) {
                                        android.widget.Toast.makeText(context, "Camera permission is required to record", android.widget.Toast.LENGTH_SHORT).show()
                                        return@clickable
                                    }
                                    RecordingManager.setStarting()
                                    Toast.makeText(context, "🔴 Starting Background Video Recording...", Toast.LENGTH_SHORT).show()
                                    val startIntent = Intent(context, RecordingService::class.java).apply {
                                        action = RecordingService.ACTION_START
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(startIntent)
                                    } else {
                                        context.startService(startIntent)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Outer ring
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .background(
                                    Color.Transparent,
                                    CircleShape
                                )
                                .then(
                                    Modifier.background(
                                        Color.White.copy(alpha = 0.2f),
                                        CircleShape
                                    )
                                )
                        )
                        // Inner Red Video Button / Stop Square
                        if (isBusy) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color.Red, RoundedCornerShape(6.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(62.dp)
                                    .background(Color(0xFFE53935), CircleShape)
                            )
                        }
                    }
                }

                // Helpful background recording indicator text
                Text(
                    text = if (isBusy) {
                        "🔒 Safe to lock screen or close app — video keeps recording"
                    } else {
                        "Tap RED circle to record • Continues when app is closed"
                    },
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
