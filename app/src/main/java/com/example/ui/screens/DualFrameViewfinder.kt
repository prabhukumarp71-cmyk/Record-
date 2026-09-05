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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.camera.RecordingManager
import com.example.model.RecordingState

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

    val isRecording = recordingState == RecordingState.RECORDING
    val isPaused = recordingState == RecordingState.PAUSED
    val isStarting = recordingState == RecordingState.STARTING
    val isStopping = recordingState == RecordingState.STOPPING || isProcessingDual

    var mirrorViewRef by remember { mutableStateOf<MirrorFrameView?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(recordingState, lifecycleOwner) {
        if (recordingState == RecordingState.IDLE) {
            RecordingManager.bindCamera(
                context = context,
                lifecycleOwner = lifecycleOwner
            )
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

        // Viewport Stack: 9:16 (Top) + 16:9 (Bottom)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // TOP FRAME: 9:16 Vertical Portrait Frame
            Box(
                modifier = Modifier
                    .weight(1.35f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))
                    .background(Color(0xFF121212)),
                contentAlignment = Alignment.Center
            ) {
                // CameraX PreviewView (9:16 native portrait)
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

                        // Attach mirror once texture view is available
                        pv.post {
                            val textureView = (0 until pv.childCount)
                                .mapNotNull { pv.getChildAt(it) as? TextureView }
                                .firstOrNull()
                            textureView?.let { tv ->
                                mirrorViewRef?.startMirroring(tv)
                            }
                        }

                        frameLayout
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Top-Left Badge: "9:16" (matching reference screenshot)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "9:16",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Top-Right Badge: "★ PRO" / "★ DUAL" (matching reference screenshot)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFE5A000),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "★ DUAL",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 11.sp),
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Left Floating Crop Tool Button [ ⯐ ] (cycles crop position for 16:9)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 10.dp)
                        .clickable {
                            RecordingManager.cycleCropPosition()
                            mirrorViewRef?.cropPosition = RecordingManager.cropPosition.value
                        }
                        .testTag("crop_reframe_button")
                ) {
                    Icon(
                        Icons.Default.Crop,
                        contentDescription = "Reframe Crop Position",
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier
                            .padding(8.dp)
                            .size(20.dp)
                    )
                }

                // Bottom Watermark: "Reframe" (matching reference screenshot)
                Text(
                    text = "Reframe",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                )
            }

            // BOTTOM FRAME: 16:9 Horizontal Widescreen Frame
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))
                    .background(Color(0xFF121212)),
                contentAlignment = Alignment.Center
            ) {
                // Live 16:9 Mirror View
                AndroidView(
                    factory = { ctx ->
                        MirrorFrameView(ctx).apply {
                            mirrorViewRef = this
                            this.cropPosition = cropPos
                            // If previewViewRef already has textureView, bind now
                            previewViewRef?.let { pv ->
                                val textureView = (0 until pv.childCount)
                                    .mapNotNull { pv.getChildAt(it) as? TextureView }
                                    .firstOrNull()
                                textureView?.let { tv ->
                                    startMirroring(tv)
                                }
                            }
                        }
                    },
                    update = { view ->
                        view.cropPosition = cropPos
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Bottom-Left Badge: "16:9" (matching reference screenshot)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "16:9",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Bottom-Right Watermark: "Reframe" (matching reference screenshot)
                Text(
                    text = "Reframe",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 10.dp, end = 12.dp)
                )
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
