package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Choreographer
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.camera.RecordingManager
import com.example.model.RecordingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Custom View that mirrors and crops the camera TextureView to a 16:9 widescreen format.
 */
class MirrorFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var sourceTextureView: TextureView? = null
    var cropPosition: Float = 0.5f // 0.2f = top, 0.5f = center, 0.8f = bottom
    private var sharedBitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()
    private var isLoopRunning = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isLoopRunning || !isAttachedToWindow) return
            val tv = sourceTextureView
            if (tv != null && tv.isAvailable) {
                val targetW = 480
                val targetH = (targetW * 16) / 9 // 853
                if (sharedBitmap == null || sharedBitmap?.width != targetW || sharedBitmap?.height != targetH) {
                    try {
                        sharedBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    } catch (_: Exception) {}
                }
                sharedBitmap?.let { bmp ->
                    try {
                        tv.getBitmap(bmp)
                        invalidate()
                    } catch (_: Exception) {}
                }
            }
            if (isLoopRunning && isAttachedToWindow) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun startMirroring(textureView: TextureView) {
        sourceTextureView = textureView
        if (!isLoopRunning) {
            isLoopRunning = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    fun stopMirroring() {
        isLoopRunning = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopMirroring()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = sharedBitmap
        if (bmp == null || width == 0 || height == 0) {
            canvas.drawColor(android.graphics.Color.BLACK)
            return
        }

        val cropH = (bmp.width * 9) / 16
        val maxTop = (bmp.height - cropH).coerceAtLeast(0)
        val top = (maxTop * cropPosition).toInt().coerceIn(0, maxTop)

        srcRect.set(0, top, bmp.width, top + cropH)
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
    }
}

/**
 * Full dual-frame camera interface matching the user's reference:
 * - Top Viewport: 9:16 aspect ratio vertical live camera preview
 * - Bottom Viewport: 16:9 aspect ratio horizontal live camera preview
 * - Bottom camera control bar: Flash, Zoom (1x/2x), Record shutter button, Settings, Flip camera
 */
@Composable
fun DualFrameViewfinder(
    modifier: Modifier = Modifier,
    onRecordToggle: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToRecordings: () -> Unit,
    onToggleDashboardMode: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val recordingState by RecordingManager.recordingState.collectAsState()
    val duration by RecordingManager.recordingDurationMs.collectAsState()
    val isTorchOn by RecordingManager.isTorchOn.collectAsState()
    val zoomRatio by RecordingManager.zoomRatio.collectAsState()
    val cropPos by RecordingManager.cropPosition.collectAsState()
    val isDualFormat by RecordingManager.dualFormatEnabled.collectAsState()
    val isProcessingDual by RecordingManager.isProcessingDualFormat.collectAsState()
    val isFocusLocked by RecordingManager.isFocusLocked.collectAsState()
    val isFarFocusOnly by RecordingManager.isFarFocusOnly.collectAsState()
    val isAeLocked by RecordingManager.isAeLocked.collectAsState()
    val exposureMode by RecordingManager.exposureMode.collectAsState()
    val exposureCompensation by RecordingManager.exposureCompensation.collectAsState()
    val exposureEvValue by RecordingManager.exposureEvValue.collectAsState()
    val tapMeterPoint by RecordingManager.tapMeterPoint.collectAsState()
    val isNightModeEnabled by RecordingManager.nightModeEnabled.collectAsState()
    val trackedPerson by RecordingManager.humanTracker.trackedPerson.collectAsState()
    val allDetectedPersons by RecordingManager.humanTracker.allDetectedPersons.collectAsState()
    val isHumanAfEnabled by RecordingManager.humanTracker.isHumanAfEnabled.collectAsState()
    val isPersonMoving by RecordingManager.humanTracker.isPersonMoving.collectAsState()
    val afStatusText by RecordingManager.humanTracker.afStatusText.collectAsState()
    val afMode by RecordingManager.humanTracker.afMode.collectAsState()
    val trackedMotionTarget by RecordingManager.humanTracker.trackedMotionTarget.collectAsState()
    val isMotionActive by RecordingManager.humanTracker.isMotionActive.collectAsState()

    val isRecording = recordingState == RecordingState.RECORDING
    val isPaused = recordingState == RecordingState.PAUSED
    val isStarting = recordingState == RecordingState.STARTING
    val isStopping = recordingState == RecordingState.STOPPING || isProcessingDual

    var mirrorViewRef by remember { mutableStateOf<MirrorFrameView?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    val coroutineScope = rememberCoroutineScope()
    var showFloatingExposureIndicator by remember { mutableStateOf(false) }
    var floatingDismissJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val onDragExposure: (Float) -> Unit = { deltaY ->
        val deltaNorm = -deltaY * 0.005f
        RecordingManager.adjustExposureBy(deltaNorm)
        showFloatingExposureIndicator = true
        floatingDismissJob?.cancel()
        floatingDismissJob = coroutineScope.launch {
            delay(1800)
            showFloatingExposureIndicator = false
        }
    }

    val targetFps by com.example.settings.AppSettings.getTargetFps(context).collectAsState(initial = 30)

    LaunchedEffect(recordingState, lifecycleOwner, targetFps) {
        if (recordingState == RecordingState.IDLE) {
            RecordingManager.setTargetFps(targetFps)
            RecordingManager.bindCamera(
                context = context,
                lifecycleOwner = lifecycleOwner
            )
        }
    }

    LaunchedEffect(tapMeterPoint) {
        if (tapMeterPoint != null) {
            delay(2500)
            RecordingManager.clearTapMeterPoint()
        }
    }

    // Recording pulsing effect
    val infiniteTransition = rememberInfiniteTransition(label = "recPulse")
    val recPulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recPulse"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Info & Quick Actions Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Recordings shortcut
                FilledTonalButton(
                    onClick = onNavigateToRecordings,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF222222),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = "Recordings", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Library", style = MaterialTheme.typography.labelSmall)
                }

                // Stealth / Dashboard Mode toggle
                IconButton(
                    onClick = onToggleDashboardMode,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF222222))
                ) {
                    Icon(
                        Icons.Outlined.VisibilityOff,
                        contentDescription = "Switch to Stealth Dashboard",
                        tint = Color(0xFFBBBBBB),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Recording indicator / Timer
            if (isRecording || isPaused || isStopping) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Red.copy(alpha = 0.85f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .scale(recPulse)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val seconds = duration / 1000
                        val min = seconds / 60
                        val sec = seconds % 60
                        Text(
                            text = if (isStopping) "SAVING DUAL..." else String.format("REC %02d:%02d", min, sec),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            } else {
                // Emergency info pill
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E1E1E)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Vol keys: 4s start • 2s stop",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = Color(0xFFDDDDDD)
                        )
                    }
                }
            }

            // Settings button
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
            }
        }

        // Main Viewport (Clean Single Default Viewfinder - 16:9 format)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(16.dp))
                .background(Color(0xFF121212))
                .pointerInput(isFarFocusOnly) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        val startY = down.position.y
                        var isDrag = false
                        var currentY = startY

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!isDrag) {
                                    val normX = (startX / size.width.toFloat()).coerceIn(0f, 1f)
                                    val normY = (startY / size.height.toFloat()).coerceIn(0f, 1f)
                                    RecordingManager.focusAtPoint(normX, normY)
                                } else {
                                    floatingDismissJob?.cancel()
                                    floatingDismissJob = coroutineScope.launch {
                                        delay(1800)
                                        showFloatingExposureIndicator = false
                                    }
                                }
                                break
                            }
                            val dy = change.position.y - currentY
                            if (!isDrag && (kotlin.math.abs(change.position.y - startY) > 12f || kotlin.math.abs(change.position.x - startX) > 12f)) {
                                if (isFarFocusOnly && kotlin.math.abs(change.position.y - startY) > kotlin.math.abs(change.position.x - startX)) {
                                    isDrag = true
                                }
                            }
                            if (isDrag) {
                                change.consume()
                                onDragExposure(dy)
                            }
                            currentY = change.position.y
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // CameraX PreviewView (Default Single Viewfinder)
            AndroidView(
                factory = { ctx ->
                    val frameLayout = FrameLayout(ctx)
                    val pv = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    previewViewRef = pv
                    frameLayout.addView(pv)

                    RecordingManager.setSurfaceProvider(pv.surfaceProvider)

                    pv.post {
                        RecordingManager.humanTracker.setMeteringPointFactory(pv.meteringPointFactory)
                    }

                    frameLayout
                },
                modifier = Modifier.fillMaxSize()
            )

            // Real-time Human / Motion Autofocus Target Reticles & Far AE Metering Reticle
            if ((afMode != com.example.camera.AutoFocusMode.STANDARD_AUTO || isFarFocusOnly || tapMeterPoint != null) && !isFocusLocked) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    for (person in allDetectedPersons) {
                        val isPrimary = person.id == trackedPerson?.id
                        val left = person.bounds.left * size.width
                        val top = person.bounds.top * size.height
                        val right = person.bounds.right * size.width
                        val bottom = person.bounds.bottom * size.height
                        val boxWidth = (right - left).coerceAtLeast(10f)
                        val boxHeight = (bottom - top).coerceAtLeast(10f)

                        val boxColor = if (isFarFocusOnly) {
                            if (person.isNear) {
                                Color.White.copy(alpha = 0.20f) // Near person ignored
                            } else if (isPrimary) {
                                Color(0xFF00E5FF) // Distant target focused
                            } else {
                                Color(0xFFFFD54F).copy(alpha = 0.6f)
                            }
                        } else if (isPrimary) {
                            if (person.isMoving) Color(0xFF00E5FF) else Color(0xFFFFD54F)
                        } else {
                            Color.White.copy(alpha = 0.35f)
                        }

                        val strokeWidth = if (isFarFocusOnly && person.isNear) 1.dp.toPx()
                        else if (isPrimary) 2.5.dp.toPx()
                        else 1.2.dp.toPx()

                        val cornerLength = (minOf(boxWidth, boxHeight) * 0.28f).coerceIn(10.dp.toPx(), 26.dp.toPx())

                        // Draw high-precision corner brackets
                        // Top-Left
                        drawLine(boxColor, Offset(left, top), Offset(left + cornerLength, top), strokeWidth)
                        drawLine(boxColor, Offset(left, top), Offset(left, top + cornerLength), strokeWidth)
                        // Top-Right
                        drawLine(boxColor, Offset(right, top), Offset(right - cornerLength, top), strokeWidth)
                        drawLine(boxColor, Offset(right, top), Offset(right, top + cornerLength), strokeWidth)
                        // Bottom-Left
                        drawLine(boxColor, Offset(left, bottom), Offset(left + cornerLength, bottom), strokeWidth)
                        drawLine(boxColor, Offset(left, bottom), Offset(left, bottom - cornerLength), strokeWidth)
                        // Bottom-Right
                        drawLine(boxColor, Offset(right, bottom), Offset(right - cornerLength, bottom), strokeWidth)
                        drawLine(boxColor, Offset(right, bottom), Offset(right, bottom - cornerLength), strokeWidth)

                        if (isPrimary && (!isFarFocusOnly || !person.isNear)) {
                            val cx = (left + right) / 2f
                            val cy = (top + bottom) / 2f
                            drawCircle(boxColor.copy(alpha = 0.85f), radius = 3.5.dp.toPx(), center = Offset(cx, cy))
                        }
                    }

                    // Motion target reticle
                    val motion = trackedMotionTarget
                    if (motion != null && (!isFarFocusOnly || !motion.isNear) && (trackedPerson == null || !isPersonMoving)) {
                        val left = motion.bounds.left * size.width
                        val top = motion.bounds.top * size.height
                        val right = motion.bounds.right * size.width
                        val bottom = motion.bounds.bottom * size.height
                        val boxWidth = (right - left).coerceAtLeast(10f)
                        val boxHeight = (bottom - top).coerceAtLeast(10f)
                        val mColor = Color(0xFF00E5FF)
                        val strokeWidth = 2.dp.toPx()
                        val cornerLength = (minOf(boxWidth, boxHeight) * 0.25f).coerceIn(8.dp.toPx(), 22.dp.toPx())

                        drawLine(mColor, Offset(left, top), Offset(left + cornerLength, top), strokeWidth)
                        drawLine(mColor, Offset(left, top), Offset(left, top + cornerLength), strokeWidth)
                        drawLine(mColor, Offset(right, top), Offset(right - cornerLength, top), strokeWidth)
                        drawLine(mColor, Offset(right, top), Offset(right, top + cornerLength), strokeWidth)
                        drawLine(mColor, Offset(left, bottom), Offset(left + cornerLength, bottom), strokeWidth)
                        drawLine(mColor, Offset(left, bottom), Offset(left, bottom - cornerLength), strokeWidth)
                        drawLine(mColor, Offset(right, bottom), Offset(right - cornerLength, bottom), strokeWidth)
                        drawLine(mColor, Offset(right, bottom), Offset(right, bottom - cornerLength), strokeWidth)

                        val cx = motion.centerX * size.width
                        val cy = motion.centerY * size.height
                        drawCircle(mColor.copy(alpha = 0.9f), radius = 4.dp.toPx(), center = Offset(cx, cy))
                    }

                    // Draw Tap-to-Meter AE Reticle in Far Only Mode
                    if (isFarFocusOnly && tapMeterPoint != null) {
                        val tx = tapMeterPoint!!.first * size.width
                        val ty = tapMeterPoint!!.second * size.height
                        val ringRadius = 24.dp.toPx()
                        val sunColor = Color(0xFFFFD54F)

                        drawCircle(
                            color = sunColor,
                            radius = ringRadius,
                            center = Offset(tx, ty),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                        )
                        drawCircle(
                            color = sunColor,
                            radius = 4.dp.toPx(),
                            center = Offset(tx, ty)
                        )
                        val rayLen = 7.dp.toPx()
                        val gap = ringRadius + 3.dp.toPx()
                        drawLine(sunColor, Offset(tx, ty - gap), Offset(tx, ty - gap - rayLen), strokeWidth = 2.dp.toPx())
                        drawLine(sunColor, Offset(tx, ty + gap), Offset(tx, ty + gap - rayLen), strokeWidth = 2.dp.toPx())
                        drawLine(sunColor, Offset(tx - gap, ty), Offset(tx - gap - rayLen, ty), strokeWidth = 2.dp.toPx())
                        drawLine(sunColor, Offset(tx + gap, ty), Offset(tx + gap + rayLen, ty), strokeWidth = 2.dp.toPx())
                    }
                }
            }

            // Top-Left Badges: Format 16:9 + Resolution / AF status
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.65f)
                ) {
                    Text(
                        text = "16:9",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                val currentQualityName by com.example.settings.AppSettings.getVideoQuality(context).collectAsState(initial = "HIGH")
                val qualityBadge = when (currentQualityName) {
                    "ULTRA" -> "4K UHD • 3840×2160"
                    "HIGH" -> "1080p FHD • 1920×1080"
                    "MEDIUM" -> "720p HD"
                    else -> "480p SD"
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E88E5).copy(alpha = 0.85f)
                ) {
                    Text(
                        text = qualityBadge,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (isFarFocusOnly) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0277BD).copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = "🏔️ FAR ONLY",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else if (afMode != com.example.camera.AutoFocusMode.STANDARD_AUTO) {
                    val hasMotion = isPersonMoving || isMotionActive
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (hasMotion) Color(0xFF006064).copy(alpha = 0.85f)
                        else if (trackedPerson != null) Color(0xFFE65100).copy(alpha = 0.85f)
                        else Color.Black.copy(alpha = 0.65f)
                    ) {
                        Text(
                            text = if (hasMotion) "🏃 MOTION AF" else if (trackedPerson != null) "👤 HUMAN AF" else "👁 SCANNING",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                            color = if (hasMotion) Color(0xFF80DEEA) else if (trackedPerson != null) Color(0xFFFFE082) else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Top-Right Badge: Active FPS & Mode
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF263238).copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
            ) {
                Text(
                    text = "$targetFps FPS",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                    color = Color(0xFFFFD54F),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Floating Exposure Indicator Overlay
            androidx.compose.animation.AnimatedVisibility(
                visible = showFloatingExposureIndicator && isFarFocusOnly,
                enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.85f),
                exit = fadeOut(tween(250)) + scaleOut(targetScale = 0.85f),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.82f),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFFD54F)),
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.WbSunny,
                                contentDescription = null,
                                tint = Color(0xFFFFD54F),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = String.format("%+.1f EV", exposureEvValue),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = Color(0xFFFFD54F)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(84.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.25f))
                        ) {
                            val fillFrac = ((exposureCompensation + 1f) / 2f).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction = fillFrac)
                                    .background(Color(0xFFFFD54F))
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Swipe ↕ to adjust EV",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
        
        // EXPOSURE & FOCUS CONTROLS
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            if (isFarFocusOnly) {
                // DEDICATED FAR ONLY EXPOSURE PANEL
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF191D22),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF0288D1).copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        // Header Row: Status & EV readout
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Landscape,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "FAR ONLY EXPOSURE",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp),
                                    color = Color(0xFF00E5FF)
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = when {
                                        isAeLocked -> Color(0xFFFF6F00)
                                        exposureMode == 1 -> Color(0xFFFFD54F)
                                        else -> Color(0xFF2C3238)
                                    }
                                ) {
                                    Text(
                                        text = when {
                                            isAeLocked -> "AE LOCKED 🔒"
                                            exposureMode == 1 -> String.format(if (exposureEvValue > 0) "+%.1f EV" else "%.1f EV", exposureEvValue)
                                            else -> "AUTO AE"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                        color = if (exposureMode == 1 && !isAeLocked) Color.Black else Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                if (exposureMode == 1 || isAeLocked || exposureCompensation != 0f) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Reset Exposure",
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable { RecordingManager.resetExposure() }
                                            .testTag("far_exposure_reset_button")
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Control Mode Selector Row: [ AUTO AE ] [ MANUAL EV ] [ AE LOCK 🔒 ] [ EXIT FAR ]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF23282E)),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // AUTO AE
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { RecordingManager.setExposureMode(0) }
                                    .background(if (exposureMode == 0 && !isAeLocked) Color(0xFF0288D1) else Color.Transparent)
                                    .padding(vertical = 6.dp)
                                    .testTag("far_exposure_auto_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "AUTO AE",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                    color = if (exposureMode == 0 && !isAeLocked) Color.White else Color.Gray
                                )
                            }

                            // MANUAL EV
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { RecordingManager.setExposureMode(1) }
                                    .background(if (exposureMode == 1) Color(0xFFFFD54F) else Color.Transparent)
                                    .padding(vertical = 6.dp)
                                    .testTag("far_exposure_manual_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "MANUAL EV",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                    color = if (exposureMode == 1) Color.Black else Color.White
                                )
                            }

                            // AE LOCK
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { RecordingManager.toggleAeLock() }
                                    .background(if (isAeLocked) Color(0xFFFF8F00) else Color.Transparent)
                                    .padding(vertical = 6.dp)
                                    .testTag("far_ae_lock_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isAeLocked) "LOCKED 🔒" else "AE LOCK",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                    color = if (isAeLocked) Color.White else Color.Gray
                                )
                            }

                            // EXIT FAR ONLY
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { RecordingManager.setFarFocusOnly(false) }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "EXIT FAR",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }

                        // FINE-TUNING STEPPERS ROW: [-0.5 EV] [-0.1 EV] [EV Badge] [+0.1 EV] [+0.5 EV]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF2C3238),
                                modifier = Modifier
                                    .clickable { RecordingManager.stepExposureEv(-0.5f) }
                                    .testTag("far_step_minus_large")
                            ) {
                                Text(
                                    text = "-0.5 EV",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                    color = Color(0xFFFFD54F),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF2C3238),
                                modifier = Modifier
                                    .clickable { RecordingManager.stepExposureEv(-0.1f) }
                                    .testTag("far_step_minus_small")
                            ) {
                                Text(
                                    text = "-0.1 EV",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                    color = Color(0xFFFFD54F),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }

                            // Center EV Readout
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = when {
                                    isAeLocked -> Color(0xFFFF6F00)
                                    exposureMode == 1 -> Color(0xFFFFD54F)
                                    else -> Color(0xFF0288D1)
                                }
                            ) {
                                Text(
                                    text = when {
                                        isAeLocked -> "LOCKED 🔒"
                                        exposureMode == 1 -> String.format(if (exposureEvValue > 0) "+%.1f EV" else "%.1f EV", exposureEvValue)
                                        else -> "AUTO AE"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 10.sp),
                                    color = if (exposureMode == 1 && !isAeLocked) Color.Black else Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF2C3238),
                                modifier = Modifier
                                    .clickable { RecordingManager.stepExposureEv(0.1f) }
                                    .testTag("far_step_plus_small")
                            ) {
                                Text(
                                    text = "+0.1 EV",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                    color = Color(0xFFFFD54F),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF2C3238),
                                modifier = Modifier
                                    .clickable { RecordingManager.stepExposureEv(0.5f) }
                                    .testTag("far_step_plus_large")
                            ) {
                                Text(
                                    text = "+0.5 EV",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                    color = Color(0xFFFFD54F),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // CONTINUOUS MANUAL EV SLIDER
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                        ) {
                            Text(
                                text = "-2 EV",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                color = Color(0xFFFFD54F),
                                modifier = Modifier.clickable {
                                    RecordingManager.stepExposureEv(-0.5f)
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Slider(
                                value = exposureCompensation,
                                onValueChange = { RecordingManager.setExposureCompensation(it) },
                                valueRange = -1f..1f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFFFD54F),
                                    activeTrackColor = Color(0xFFFFD54F),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("far_exposure_slider")
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "+2 EV",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                color = Color(0xFFFFD54F),
                                modifier = Modifier.clickable {
                                    RecordingManager.stepExposureEv(0.5f)
                                }
                            )
                        }

                        // QUICK DISTANT SCENE PRESETS
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val presets = listOf(
                                Triple("☁️ Sky (-1 EV)", -0.5f, "far_preset_sky"),
                                Triple("🌄 Sunset (-0.5)", -0.25f, "far_preset_sunset"),
                                Triple("⚖️ Normal (0 EV)", 0.0f, "far_preset_normal"),
                                Triple("🌲 Shadows (+0.5)", 0.25f, "far_preset_shadows"),
                                Triple("🌃 Night (+1 EV)", 0.5f, "far_preset_night")
                            )
                            for ((label, value, tag) in presets) {
                                val isSelected = exposureMode == 1 && kotlin.math.abs(exposureCompensation - value) < 0.13f
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSelected) Color(0xFFFFD54F) else Color(0xFF2B3037),
                                    modifier = Modifier
                                        .clickable { RecordingManager.setExposurePreset(value) }
                                        .testTag(tag)
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 8.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        ),
                                        color = if (isSelected) Color.Black else Color(0xFFDDDDDD),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        // Tap-to-expose hint
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "💡 Swipe ↕ on preview to adjust EV • Tap to spot-meter • Focus locked to infinity",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = Color(0xFF81D4FA).copy(alpha = 0.9f)
                        )
                    }
                }
            } else {
                // NORMAL FOCUS & EXPOSURE CONTROLS
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isFocusLocked) "FOCUS: LOCKED (Tap to Auto)" else afStatusText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isFocusLocked) Color(0xFFFFD54F)
                        else if (isPersonMoving) Color(0xFF00E5FF)
                        else if (trackedPerson != null) Color(0xFFFFD54F)
                        else Color.White,
                        modifier = Modifier.clickable {
                            if (isFocusLocked) {
                                RecordingManager.toggleFocusLock()
                            } else {
                                RecordingManager.humanTracker.toggleHumanAf()
                            }
                        }
                    )
                    
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF222222)),
                    ) {
                        val afButtonText = when (afMode) {
                            com.example.camera.AutoFocusMode.HUMAN_PRIORITY -> "HUMAN AF"
                            com.example.camera.AutoFocusMode.MOTION_TRACKING -> "MOTION AF"
                            com.example.camera.AutoFocusMode.STANDARD_AUTO -> "AF OFF"
                        }
                        val afButtonBg = when (afMode) {
                            com.example.camera.AutoFocusMode.HUMAN_PRIORITY -> Color(0xFF2E7D32)
                            com.example.camera.AutoFocusMode.MOTION_TRACKING -> Color(0xFF00838F)
                            com.example.camera.AutoFocusMode.STANDARD_AUTO -> Color.Transparent
                        }
                        Text(
                            text = afButtonText,
                            modifier = Modifier
                                .clickable { RecordingManager.humanTracker.cycleAfMode() }
                                .background(afButtonBg)
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                            color = if (afMode != com.example.camera.AutoFocusMode.STANDARD_AUTO) Color.White else Color.Gray
                        )
                        Text(
                            text = "FAR ONLY",
                            modifier = Modifier
                                .clickable { RecordingManager.toggleFarFocusOnly() }
                                .background(Color.Transparent)
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                                .testTag("far_focus_toggle_button"),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                            color = Color(0xFF81D4FA)
                        )
                        Text(
                            text = "NIGHT",
                            modifier = Modifier
                                .clickable { RecordingManager.toggleNightMode() }
                                .background(if (isNightModeEnabled) Color(0xFFFFD54F) else Color.Transparent)
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                            color = if (isNightModeEnabled) Color.Black else Color.White
                        )
                        Text(
                            text = "AUTO EV",
                            modifier = Modifier
                                .clickable { RecordingManager.setExposureMode(0) }
                                .background(if (exposureMode == 0 && !isNightModeEnabled) Color(0xFFFFD54F) else Color.Transparent)
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                            color = if (exposureMode == 0 && !isNightModeEnabled) Color.Black else Color.White
                        )
                        Text(
                            text = "MANUAL",
                            modifier = Modifier
                                .clickable { RecordingManager.setExposureMode(1) }
                                .background(if (exposureMode == 1 && !isNightModeEnabled) Color(0xFFFFD54F) else Color.Transparent)
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                            color = if (exposureMode == 1 && !isNightModeEnabled) Color.Black else Color.White
                        )
                    }
                }

                AnimatedVisibility(visible = exposureMode == 1) {
                    Slider(
                        value = exposureCompensation,
                        onValueChange = { RecordingManager.setExposureCompensation(it) },
                        valueRange = -1f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFFD54F),
                            activeTrackColor = Color(0xFFFFD54F),
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
        }

        // BOTTOM CAMERA CONTROLS BAR (Matching reference screenshot)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Flash / Torch Toggle Button [ ⚡\ ]
            IconButton(
                onClick = { RecordingManager.toggleTorch() },
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF222222))
                    .testTag("flash_toggle_button")
            ) {
                Icon(
                    imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Torch Toggle",
                    tint = if (isTorchOn) Color(0xFFFFD54F) else Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Zoom Toggle Button [ 1x / 2x ]
            Surface(
                shape = CircleShape,
                color = Color(0xFF222222),
                modifier = Modifier
                    .size(46.dp)
                    .clickable { RecordingManager.toggleZoom() }
                    .testTag("zoom_toggle_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (zoomRatio >= 1.5f) "2x" else "1x",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (zoomRatio >= 1.5f) Color(0xFFFFD54F) else Color.White
                    )
                }
            }

            // Central Shutter / Record Button (White outer ring with red center circle)
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clickable(
                        enabled = !isStarting && !isStopping,
                        onClick = onRecordToggle
                    )
                    .testTag("camera_shutter_button"),
                contentAlignment = Alignment.Center
            ) {
                // Outer White Ring
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                )

                // Inner Red Shutter Button (turns square when recording)
                Box(
                    modifier = Modifier
                        .size(if (isRecording || isPaused) 32.dp else 60.dp)
                        .clip(if (isRecording || isPaused) RoundedCornerShape(8.dp) else CircleShape)
                        .background(Color(0xFFFF3B30))
                )
            }

            // Settings Button [ ⚙ ]
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF222222))
                    .testTag("bar_settings_button")
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Camera Flip Button [ 🔄 ]
            IconButton(
                onClick = { RecordingManager.toggleCamera() },
                enabled = !isRecording && !isPaused && !isStarting && !isStopping,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF222222))
                    .testTag("flip_camera_button")
            ) {
                Icon(
                    Icons.Default.Cameraswitch,
                    contentDescription = "Flip Camera",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
