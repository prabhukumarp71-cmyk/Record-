package com.example.ui.screens

import android.view.TextureView
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.camera.RecordingManager

@Composable
fun DualFrameViewfinder(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val recordingState by RecordingManager.recordingState.collectAsState()
    val isTorchOn by RecordingManager.isTorchOn.collectAsState()
    val zoomRatio by RecordingManager.zoomRatio.collectAsState()
    val isFarFocusOnly by RecordingManager.isFarFocusOnly.collectAsState()
    val isNightMode by RecordingManager.nightModeEnabled.collectAsState()
    val isFocusLocked by RecordingManager.isFocusLocked.collectAsState()
    val exposureCompensation by RecordingManager.exposureCompensation.collectAsState()
    val exposureMode by RecordingManager.exposureMode.collectAsState()
    val tapMeterPoint by RecordingManager.tapMeterPoint.collectAsState()
    val activeQualityDesc by RecordingManager.activeQualityDescription.collectAsState()
    val targetFps by RecordingManager.targetFps.collectAsState()
    val cropPosition by RecordingManager.cropPosition.collectAsState()
    val dualFormatEnabled by RecordingManager.dualFormatEnabled.collectAsState()

    val trackedPersons by RecordingManager.humanTracker.trackedPersons.collectAsState()
    val humanAfEnabled by RecordingManager.humanTracker.humanAfEnabled.collectAsState()
    val afMode by RecordingManager.humanTracker.afMode.collectAsState()

    var previewTextureView by remember { mutableStateOf<TextureView?>(null) }
    var showPip by remember { mutableStateOf(true) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight

        // Main camera preview
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
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    RecordingManager.setSurfaceProvider(this.surfaceProvider)
                    // Retrieve child TextureView for mirror companion
                    post {
                        for (i in 0 until childCount) {
                            val child = getChildAt(i)
                            if (child is TextureView) {
                                previewTextureView = child
                                break
                            }
                        }
                    }
                }
            },
            update = { view ->
                RecordingManager.setSurfaceProvider(view.surfaceProvider)
                if (previewTextureView == null) {
                    for (i in 0 until view.childCount) {
                        val child = view.getChildAt(i)
                        if (child is TextureView) {
                            previewTextureView = child
                            break
                        }
                    }
                }
            }
        )

        // Overlay: Human tracking bounding boxes and metering points
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height

            // Human AF bounding boxes
            if (humanAfEnabled) {
                for (person in trackedPersons) {
                    val rect = person.boundingBox
                    val left = rect.left * canvasW
                    val top = rect.top * canvasH
                    val width = rect.width() * canvasW
                    val height = rect.height() * canvasH

                    val boxColor = if (person.isSelected) Color(0xFF00E676) else Color(0xFFFFEB3B)
                    drawRect(
                        color = boxColor,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }

            // Tap metering point
            tapMeterPoint?.let { (x, y) ->
                val px = x * canvasW
                val py = y * canvasH
                drawCircle(
                    color = Color.Yellow,
                    radius = 24.dp.toPx(),
                    center = Offset(px, py),
                    style = Stroke(width = 2.dp.toPx())
                )
                drawLine(
                    color = Color.Yellow,
                    start = Offset(px - 16.dp.toPx(), py),
                    end = Offset(px + 16.dp.toPx(), py),
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color = Color.Yellow,
                    start = Offset(px, py - 16.dp.toPx()),
                    end = Offset(px, py + 16.dp.toPx()),
                    strokeWidth = 2.dp.toPx()
                )
            }

            // Dual format guide lines (Horizontal 16:9 box guide on portrait preview)
            if (dualFormatEnabled) {
                val boxH = (canvasW * 9f) / 16f
                val maxTop = (canvasH - boxH).coerceAtLeast(0f)
                val boxTop = maxTop * cropPosition

                // Semi-transparent dimming outside the 16:9 crop guide
                drawRect(
                    color = Color(0x66000000),
                    topLeft = Offset(0f, 0f),
                    size = Size(canvasW, boxTop)
                )
                drawRect(
                    color = Color(0x66000000),
                    topLeft = Offset(0f, boxTop + boxH),
                    size = Size(canvasW, canvasH - (boxTop + boxH))
                )

                // 16:9 Guide Frame
                drawRect(
                    color = Color(0xFF00E5FF).copy(alpha = 0.85f),
                    topLeft = Offset(0f, boxTop),
                    size = Size(canvasW, boxH),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // Top Quality Banner
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.65f),
                contentColor = Color.White
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFF00E676), CircleShape)
                    )
                    Text(
                        text = activeQualityDesc,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "• ${targetFps} FPS",
                        fontSize = 12.sp,
                        color = Color(0xFF00E5FF)
                    )
                }
            }

            if (dualFormatEnabled) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF00E5FF).copy(alpha = 0.25f),
                    contentColor = Color(0xFF00E5FF),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF))
                ) {
                    Text(
                        text = "📱 9:16 + 🖥️ 16:9",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Picture-In-Picture Companion Preview (Synchronized 16:9 Horizontal Crop Preview)
        if (dualFormatEnabled && showPip && previewTextureView != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 16.dp)
                    .width(130.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(8.dp)),
                colors = CardDefaults.cardColors(containerColor = Color.Black)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MirrorFrameView(ctx).apply {
                            this.cropPosition = cropPosition
                            previewTextureView?.let { startMirroring(it) }
                        }
                    },
                    update = { view ->
                        view.cropPosition = cropPosition
                        previewTextureView?.let { view.startMirroring(it) }
                    }
                )
            }
        }

        // Quick Controls Vertical Rail (Right Side)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Torch Button
            IconButton(
                onClick = { RecordingManager.toggleTorch() },
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Torch",
                    tint = if (isTorchOn) Color.Yellow else Color.White
                )
            }

            // Zoom 1x / 2x Button
            IconButton(
                onClick = { RecordingManager.toggleZoom() },
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            ) {
                Text(
                    text = if (zoomRatio > 1.5f) "2.0x" else "1.0x",
                    color = if (zoomRatio > 1.5f) Color.Yellow else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            // Flip Camera
            IconButton(
                onClick = { RecordingManager.toggleCamera() },
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Switch Camera",
                    tint = Color.White
                )
            }

            // Human AF Toggle
            IconButton(
                onClick = { RecordingManager.humanTracker.toggleHumanAf() },
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (humanAfEnabled) Color(0xFF00E676).copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.55f),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (humanAfEnabled) Icons.Default.CenterFocusStrong else Icons.Default.CenterFocusWeak,
                    contentDescription = "Human AF",
                    tint = if (humanAfEnabled) Color(0xFF00E676) else Color.White
                )
            }

            // Night Mode Toggle
            IconButton(
                onClick = { RecordingManager.toggleNightMode() },
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (isNightMode) Color(0xFF7C4DFF).copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.55f),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (isNightMode) Icons.Default.Bedtime else Icons.Outlined.Bedtime,
                    contentDescription = "Night Mode",
                    tint = if (isNightMode) Color(0xFFB388FF) else Color.White
                )
            }

            // Far Focus Only Toggle
            IconButton(
                onClick = { RecordingManager.toggleFarFocusOnly() },
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (isFarFocusOnly) Color(0xFF00E5FF).copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.55f),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Landscape,
                    contentDescription = "Far Focus",
                    tint = if (isFarFocusOnly) Color(0xFF00E5FF) else Color.White
                )
            }

            // Lock Focus Toggle
            IconButton(
                onClick = { RecordingManager.toggleFocusLock() },
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (isFocusLocked) Color.Red.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.55f),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (isFocusLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = "Lock Focus",
                    tint = if (isFocusLocked) Color.Red else Color.White
                )
            }
        }

        // Exposure Slider (When Manual Exposure is Active)
        if (exposureMode == 1) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 140.dp, start = 24.dp, end = 24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Exposure EV", color = Color.White, fontSize = 12.sp)
                    Text(
                        String.format("%.1f EV", exposureCompensation * 2.0f),
                        color = Color.Yellow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
                Slider(
                    value = exposureCompensation,
                    onValueChange = { RecordingManager.setExposureCompensation(it) },
                    valueRange = -1f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.Yellow,
                        activeTrackColor = Color.Yellow
                    )
                )
            }
        }
    }
}
