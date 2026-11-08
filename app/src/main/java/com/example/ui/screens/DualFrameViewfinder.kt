package com.example.ui.screens

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.camera.RecordingManager
import com.example.model.RecordingState
import com.example.settings.AppSettings
import java.util.Locale

@Composable
fun DualFrameViewfinder(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val qualityName by AppSettings.getVideoQuality(context).collectAsState(initial = "HIGH")
    val cameraLensName by AppSettings.getCameraLens(context).collectAsState(initial = "BACK")

    val recordingState by RecordingManager.recordingState.collectAsState()
    val isTorchOn by RecordingManager.isTorchOn.collectAsState()
    val zoomRatio by RecordingManager.zoomRatio.collectAsState()
    val isFarFocusOnly by RecordingManager.isFarFocusOnly.collectAsState()
    val exposureIndex by RecordingManager.exposureIndex.collectAsState()
    val exposureEvValue by RecordingManager.exposureEvValue.collectAsState()
    val exposureRange by RecordingManager.exposureRange.collectAsState()
    val tapMeterPoint by RecordingManager.tapMeterPoint.collectAsState()
    val activeQualityDesc by RecordingManager.activeQualityDescription.collectAsState()

    var showExposureSlider by remember { mutableStateOf(false) }
    var currentPreviewViewInstance by remember { mutableStateOf<PreviewView?>(null) }

    val targetQuality = RecordingManager.parseQuality(qualityName)
    val lensFacing = if (cameraLensName == "FRONT") {
        androidx.camera.core.CameraSelector.LENS_FACING_FRONT
    } else {
        androidx.camera.core.CameraSelector.LENS_FACING_BACK
    }

    LaunchedEffect(qualityName, cameraLensName, lifecycleOwner, currentPreviewViewInstance, recordingState) {
        val pv = currentPreviewViewInstance ?: return@LaunchedEffect
        if (recordingState == RecordingState.IDLE) {
            RecordingManager.bindCamera(
                context = context,
                lifecycleOwner = lifecycleOwner,
                lensFacing = lensFacing,
                quality = targetQuality,
                surfaceProvider = pv.surfaceProvider
            )
        } else {
            // Camera is already bound to RecordingService in background; attach surface provider for live preview
            RecordingManager.setSurfaceProvider(pv.surfaceProvider)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // High-performance camera preview
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val normX = (offset.x / size.width).coerceIn(0f, 1f)
                        val normY = (offset.y / size.height).coerceIn(0f, 1f)
                        RecordingManager.focusAtPoint(normX, normY)
                    }
                },
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    currentPreviewViewInstance = this
                    RecordingManager.setSurfaceProvider(this.surfaceProvider)
                }
            },
            update = { view ->
                if (currentPreviewViewInstance != view) {
                    currentPreviewViewInstance = view
                }
                RecordingManager.setSurfaceProvider(view.surfaceProvider)
            }
        )

        // Tap-to-meter focus indicator
        tapMeterPoint?.let { (x, y) ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasW = size.width
                val canvasH = size.height
                val px = x * canvasW
                val py = y * canvasH
                drawCircle(
                    color = Color.Yellow,
                    radius = 28.dp.toPx(),
                    center = Offset(px, py),
                    style = Stroke(width = 2.dp.toPx())
                )
                drawLine(
                    color = Color.Yellow,
                    start = Offset(px - 14.dp.toPx(), py),
                    end = Offset(px + 14.dp.toPx(), py),
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color = Color.Yellow,
                    start = Offset(px, py - 14.dp.toPx()),
                    end = Offset(px, py + 14.dp.toPx()),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        // Top Status Banners (Far Focus Lock + EV Info)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Far Focus Lock Status Pill (Tap to toggle)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isFarFocusOnly) Color(0xFF00E5FF).copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.6f),
                border = if (isFarFocusOnly) null else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                modifier = Modifier
                    .testTag("far_focus_status_pill")
                    .clickable {
                        RecordingManager.toggleFarFocusOnly()
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isFarFocusOnly) Icons.Default.Lock else Icons.Default.Landscape,
                        contentDescription = null,
                        tint = if (isFarFocusOnly) Color.Black else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isFarFocusOnly) "FAR OBJECTS ONLY (FOCUS LOCKED)" else "FOCUS: AUTO",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isFarFocusOnly) Color.Black else Color.White
                    )
                }
            }

            // Exposure Value Readout Badge (Tap to open/close EV slider)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (exposureIndex != 0) Color(0xFFFFB300).copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .testTag("exposure_value_badge")
                    .clickable {
                        showExposureSlider = !showExposureSlider
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WbSunny,
                        contentDescription = "Exposure",
                        tint = if (exposureIndex != 0) Color.Black else Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = String.format(Locale.US, "%+.1f EV", exposureEvValue),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (exposureIndex != 0) Color.Black else Color.White
                    )
                }
            }
        }

        // Exposure Control Slider Overlay (Smooth, clean slide-in)
        AnimatedVisibility(
            visible = showExposureSlider,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp)
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Exposure Compensation",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = String.format(Locale.US, "%+.1f EV", exposureEvValue),
                            color = Color(0xFFFFB300),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    // Slider row with [-] and [+] buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                RecordingManager.setExposureIndex(exposureIndex - 1)
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease EV", tint = Color.White)
                        }

                        val minVal = exposureRange.first.toFloat()
                        val maxVal = exposureRange.second.toFloat()
                        val currentVal = exposureIndex.toFloat().coerceIn(minVal, maxVal)

                        Slider(
                            value = currentVal,
                            onValueChange = { newVal ->
                                RecordingManager.setExposureIndex(newVal.toInt())
                            },
                            valueRange = minVal..maxVal,
                            steps = (maxVal - minVal).toInt().coerceAtLeast(0),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFFFB300),
                                activeTrackColor = Color(0xFFFFB300),
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("exposure_slider")
                        )

                        IconButton(
                            onClick = {
                                RecordingManager.setExposureIndex(exposureIndex + 1)
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase EV", tint = Color.White)
                        }
                    }

                    // Reset and Close buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.15f),
                            modifier = Modifier.clickable {
                                RecordingManager.resetExposure()
                            }
                        ) {
                            Text(
                                text = "Reset to 0 EV",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFFB300).copy(alpha = 0.25f),
                            modifier = Modifier.clickable {
                                showExposureSlider = false
                            }
                        ) {
                            Text(
                                text = "Done",
                                color = Color(0xFFFFB300),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // Clean Quick Controls Rail (Right Side)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Dedicated Far Focus Lock Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = { RecordingManager.toggleFarFocusOnly() },
                    modifier = Modifier
                        .testTag("far_focus_lock_button")
                        .size(46.dp)
                        .background(
                            if (isFarFocusOnly) Color(0xFF00E5FF).copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f),
                            CircleShape
                        )
                        .border(
                            1.dp,
                            if (isFarFocusOnly) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.3f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isFarFocusOnly) Icons.Default.Lock else Icons.Default.Landscape,
                        contentDescription = "Far Focus Lock",
                        tint = if (isFarFocusOnly) Color.Black else Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = if (isFarFocusOnly) "LOCKED" else "FAR AF",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isFarFocusOnly) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.8f)
                )
            }

            // Exposure Compensation Toggle Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = { showExposureSlider = !showExposureSlider },
                    modifier = Modifier
                        .testTag("exposure_toggle_button")
                        .size(46.dp)
                        .background(
                            if (showExposureSlider || exposureIndex != 0) Color(0xFFFFB300).copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f),
                            CircleShape
                        )
                        .border(
                            1.dp,
                            if (exposureIndex != 0) Color(0xFFFFB300) else Color.White.copy(alpha = 0.3f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.WbSunny,
                        contentDescription = "Exposure",
                        tint = if (showExposureSlider || exposureIndex != 0) Color.Black else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = "EXP",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (exposureIndex != 0) Color(0xFFFFB300) else Color.White.copy(alpha = 0.8f)
                )
            }

            // Torch Button
            IconButton(
                onClick = { RecordingManager.toggleTorch() },
                modifier = Modifier
                    .testTag("torch_button")
                    .size(46.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Torch",
                    tint = if (isTorchOn) Color.Yellow else Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Zoom 1x / 2x Button
            IconButton(
                onClick = { RecordingManager.toggleZoom() },
                modifier = Modifier
                    .testTag("zoom_button")
                    .size(46.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Text(
                    text = if (zoomRatio > 1.5f) "2.0x" else "1.0x",
                    color = if (zoomRatio > 1.5f) Color(0xFF00E5FF) else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            // Flip Camera (Front / Back)
            IconButton(
                onClick = { RecordingManager.toggleCamera() },
                modifier = Modifier
                    .testTag("flip_camera_button")
                    .size(46.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Switch Camera",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
