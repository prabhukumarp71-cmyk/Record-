package com.example.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.RecordingManager
import com.example.model.RecordingState
import com.example.service.RecordingService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToRecordings: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val recordingState by RecordingManager.recordingState.collectAsState()
    val duration by RecordingManager.recordingDurationMs.collectAsState()
    val isNightMode by RecordingManager.nightModeEnabled.collectAsState()
    val isRecording = recordingState == RecordingState.RECORDING
    val isPaused = recordingState == RecordingState.PAUSED
    val isStarting = recordingState == RecordingState.STARTING
    val isStopping = recordingState == RecordingState.STOPPING

    // Pulse animation for recording state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "Background Recorder",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Status Header Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isRecording -> MaterialTheme.colorScheme.errorContainer
                        isPaused -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isRecording -> MaterialTheme.colorScheme.error
                                        isPaused -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.outline
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = when {
                                isStarting -> "Starting..."
                                isStopping -> "Saving recording..."
                                isRecording -> "Recording in background"
                                isPaused -> "Recording paused"
                                else -> "Ready to record"
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = when {
                                isRecording -> MaterialTheme.colorScheme.onErrorContainer
                                isPaused -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    if (isRecording || isPaused) {
                        val seconds = duration / 1000
                        val min = seconds / 60
                        val sec = seconds % 60
                        Text(
                            text = String.format("%02d:%02d", min, sec),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            // Emergency Trigger Info Banner
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Bolt,
                        contentDescription = "Emergency trigger",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Emergency Key: Hold both Vol keys (4s to start, 2s to stop)",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Central Recording Controller (In the middle)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Pulsing glow rings when active
                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                    )
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.25f))
                    )
                }

                // Big Center Start/Stop Button
                Surface(
                    shape = CircleShape,
                    color = when {
                        isRecording || isPaused -> MaterialTheme.colorScheme.error
                        isStarting || isStopping -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .clickable(
                            enabled = !isStarting && !isStopping,
                            onClick = {
                                if (recordingState == RecordingState.IDLE) {
                                    val intent = Intent(context, RecordingService::class.java).apply {
                                        action = RecordingService.ACTION_START
                                    }
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                } else {
                                    val intent = Intent(context, RecordingService::class.java).apply {
                                        action = RecordingService.ACTION_STOP
                                    }
                                    context.startService(intent)
                                }
                            }
                        )
                        .testTag("start_stop_button")
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isStarting || isStopping -> Icons.Default.HourglassEmpty
                                isRecording || isPaused -> Icons.Default.Stop
                                else -> Icons.Default.PlayArrow
                            },
                            contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
                            modifier = Modifier.size(56.dp),
                            tint = when {
                                isRecording || isPaused -> MaterialTheme.colorScheme.onError
                                isStarting || isStopping -> MaterialTheme.colorScheme.onSecondary
                                else -> MaterialTheme.colorScheme.onPrimary
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when {
                                isStarting -> "Starting..."
                                isStopping -> "Saving..."
                                isRecording -> "STOP"
                                isPaused -> "STOP"
                                else -> "START"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.2.sp
                            ),
                            color = when {
                                isRecording || isPaused -> MaterialTheme.colorScheme.onError
                                isStarting || isStopping -> MaterialTheme.colorScheme.onSecondary
                                else -> MaterialTheme.colorScheme.onPrimary
                            }
                        )
                    }
                }
            }

            // Quick Actions & Controls Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Pause / Resume option when recording
                AnimatedVisibility(visible = isRecording || isPaused) {
                    FilledTonalButton(
                        onClick = {
                            val intent = Intent(context, RecordingService::class.java).apply {
                                action = if (isRecording) RecordingService.ACTION_PAUSE else RecordingService.ACTION_RESUME
                            }
                            context.startService(intent)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .testTag("pause_resume_button")
                    ) {
                        Icon(
                            if (isRecording) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isRecording) "Pause Recording" else "Resume Recording")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Switch Camera Lens
                    OutlinedButton(
                        onClick = { RecordingManager.toggleCamera() },
                        enabled = recordingState == RecordingState.IDLE,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp)
                            .testTag("switch_camera_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Camera", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Flip", maxLines = 1, fontSize = 12.sp)
                    }

                    // Night Mode Toggle (Optimized for Moto Edge 50 Fusion sensor)
                    OutlinedButton(
                        onClick = { RecordingManager.toggleNightMode() },
                        modifier = Modifier
                            .weight(1.1f)
                            .padding(horizontal = 4.dp)
                            .testTag("night_mode_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = if (isNightMode) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Icon(
                            if (isNightMode) Icons.Default.Bedtime else Icons.Outlined.Bedtime,
                            contentDescription = "Night Mode",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isNightMode) "Night ON" else "Night", maxLines = 1, fontSize = 12.sp)
                    }

                    // Flash / Torch Toggle
                    OutlinedButton(
                        onClick = { RecordingManager.toggleTorch() },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .testTag("flash_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.FlashlightOn, contentDescription = "Flash", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Flash", maxLines = 1, fontSize = 12.sp)
                    }

                    // Recordings Gallery
                    Button(
                        onClick = onNavigateToRecordings,
                        modifier = Modifier
                            .weight(1.1f)
                            .padding(start = 4.dp)
                            .testTag("recordings_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Videos", maxLines = 1, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
