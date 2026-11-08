package com.example.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
    val isProcessingDual by RecordingManager.isProcessingDualFormat.collectAsState()
    val dualFormatEnabled by RecordingManager.dualFormatEnabled.collectAsState()
    val cropPosition by RecordingManager.cropPosition.collectAsState()

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

                // Dual Format Toggle
                IconButton(
                    onClick = {
                        RecordingManager.setDualFormatEnabled(!dualFormatEnabled)
                    },
                    modifier = Modifier
                        .testTag("switch_to_dual_camera_button")
                        .size(44.dp)
                        .background(
                            if (dualFormatEnabled) Color(0xFF00E5FF).copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.55f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.ViewCarousel,
                        contentDescription = "Toggle Dual Format",
                        tint = if (dualFormatEnabled) Color(0xFF00E5FF) else Color.White
                    )
                }

                // Recordings Library Button
                IconButton(
                    onClick = onNavigateToRecordings,
                    modifier = Modifier
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
                    .padding(bottom = 24.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Duration & Status Timer
                if (isBusy) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.7f),
                        contentColor = Color.White
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(if (isPaused) Color.Yellow else Color.Red, CircleShape)
                            )
                            Text(
                                text = durationText,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (isPaused) {
                                Text(
                                    text = "PAUSED",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Yellow
                                )
                            }
                        }
                    }
                }

                // Controls Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Framing Crop Position Toggle
                    if (dualFormatEnabled) {
                        IconButton(
                            onClick = { RecordingManager.cycleCropPosition() },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Crop,
                                contentDescription = "Crop Position",
                                tint = Color.White
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }

                    // Primary Record / Stop Button
                    Button(
                        onClick = {
                            if (recordingState == RecordingState.STARTING || recordingState == RecordingState.STOPPING) {
                                return@Button
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
                                    return@Button
                                }
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
                        modifier = Modifier
                            .testTag("record_button")
                            .size(76.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isBusy) Color.Red else Color.White
                        )
                    ) {
                        Icon(
                            imageVector = if (isBusy) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = if (isBusy) "Stop Recording" else "Start Recording",
                            modifier = Modifier.size(36.dp),
                            tint = if (isBusy) Color.White else Color.Red
                        )
                    }

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
                                containerColor = Color.Black.copy(alpha = 0.55f)
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
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }
                }
            }

            // Post-processing indicator
            AnimatedVisibility(
                visible = isProcessingDual,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.85f),
                    contentColor = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF00E5FF),
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = "Saving High Quality Videos...",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                        Text(
                            text = "Encoding in Master Bitrate",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
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
