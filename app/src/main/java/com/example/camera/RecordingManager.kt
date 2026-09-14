package com.example.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.model.RecordingState
import com.example.media.DualFormatVideoHelper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

object RecordingManager {
    private const val TAG = "RecordingManager"

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs

    private val _nightModeEnabled = MutableStateFlow(false)
    val nightModeEnabled: StateFlow<Boolean> = _nightModeEnabled

    private val _exposureMode = MutableStateFlow(0) // 0 = Auto, 1 = Manual
    val exposureMode: StateFlow<Int> = _exposureMode

    private val _exposureCompensation = MutableStateFlow(0f) // -1.0 to 1.0
    val exposureCompensation: StateFlow<Float> = _exposureCompensation

    private val _isFocusLocked = MutableStateFlow(false)
    val isFocusLocked: StateFlow<Boolean> = _isFocusLocked

    private val _dualFormatEnabled = MutableStateFlow(true)
    val dualFormatEnabled: StateFlow<Boolean> = _dualFormatEnabled

    private val _isProcessingDualFormat = MutableStateFlow(false)
    val isProcessingDualFormat: StateFlow<Boolean> = _isProcessingDualFormat

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio

    private val _lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val lensFacing: StateFlow<Int> = _lensFacing

    private val _cropPosition = MutableStateFlow(0.5f) // 0.2 = top, 0.5 = center, 0.8 = bottom
    val cropPosition: StateFlow<Float> = _cropPosition

    private val _targetFps = MutableStateFlow(30)
    val targetFps: StateFlow<Int> = _targetFps

    private val _supportedFpsRanges = MutableStateFlow<List<Int>>(listOf(30))
    val supportedFpsRanges: StateFlow<List<Int>> = _supportedFpsRanges

    var previewUseCase: Preview? = null
        private set

    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null

    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentContext: Context? = null
    private var currentQuality: Quality = Quality.HIGHEST
    private var onFinalizeCallback: ((VideoRecordEvent.Finalize?) -> Unit)? = null
    private var currentRecordingDisplayName: String = ""

    fun toggleDualFormat() {
        _dualFormatEnabled.value = !_dualFormatEnabled.value
    }

    fun setDualFormatEnabled(enabled: Boolean) {
        _dualFormatEnabled.value = enabled
    }

    fun setSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        currentSurfaceProvider = surfaceProvider
        surfaceProvider?.let { previewUseCase?.setSurfaceProvider(it) }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @SuppressLint("MissingPermission")
    fun bindCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        lensFacing: Int = currentLensFacing,
        quality: Quality = currentQuality,
        onBound: (() -> Unit)? = null
    ) {
        currentContext = context.applicationContext
        currentLifecycleOwner = lifecycleOwner
        currentLensFacing = lensFacing
        currentQuality = quality

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                cameraProvider?.unbindAll()

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                val cameraInfo = cameraProvider?.getCameraInfo(cameraSelector)
                cameraInfo?.let { info ->
                    try {
                        val camera2Info = Camera2CameraInfo.from(info)
                        val fpsRanges = camera2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        val upperBounds = fpsRanges?.map { it.upper }?.distinct()?.sorted() ?: emptyList()
                        val validOptions = upperBounds.filter { it in listOf(24, 30, 60, 120) }.toMutableList()
                        
                        // Force add 60 and 30 if they are missing so UI always has them, letting CameraX attempt best-effort
                        if (!validOptions.contains(30)) validOptions.add(30)
                        if (!validOptions.contains(60)) validOptions.add(60)
                        
                        _supportedFpsRanges.value = validOptions.sorted()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error querying FPS ranges", e)
                        _supportedFpsRanges.value = listOf(30, 60)
                    }
                }

                @Suppress("DEPRECATION")
                val supportedQualities = cameraInfo?.let { QualitySelector.getSupportedQualities(it) } ?: emptyList()

                val qualitySelector = if (supportedQualities.isNotEmpty()) {
                    val targetQuality = if (supportedQualities.contains(quality)) quality else supportedQualities.first()
                    QualitySelector.from(targetQuality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                } else {
                    QualitySelector.from(Quality.LOWEST)
                }

                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()

                val videoCaptureBuilder = VideoCapture.Builder(recorder)
                val camera2Extender = Camera2Interop.Extender(videoCaptureBuilder)

                // Configure low-light optimizations tailored to hardware limitations (Moto Edge 50 Fusion Sony LYT-700C / Snapdragon 7s Gen 2 sensor)
                if (_nightModeEnabled.value) {
                    // Enable Night Mode scene if supported or low-light boost
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_SCENE_MODE,
                        CaptureRequest.CONTROL_SCENE_MODE_NIGHT
                    )
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_MODE,
                        CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
                    )
                    // Add High Quality Noise Reduction (matches photo processing)
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.NOISE_REDUCTION_MODE,
                        CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                    )
                    // Add High Quality Tone Mapping
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.TONEMAP_MODE,
                        CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
                    )
                    // Extend exposure frame rate range for higher light accumulation without hardware stalling
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(15, 30)
                    )
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_LOCK,
                        false
                    )
                } else {
                    val fps = _targetFps.value
                    cameraInfo?.let { info ->
                        try {
                            val camera2Info = Camera2CameraInfo.from(info)
                            val ranges = camera2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                            val targetRange = ranges?.filter { it.upper == fps }?.sortedByDescending { it.lower }?.firstOrNull()
                            
                            if (targetRange != null) {
                                camera2Extender.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    targetRange
                                )
                            } else {
                                Log.w(TAG, "Requested FPS $fps not officially supported by hardware, letting CameraX decide optimal framerate.")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting FPS range", e)
                        }
                    }
                }

                videoCapture = videoCaptureBuilder.build()

                val useCases = mutableListOf<androidx.camera.core.UseCase>()
                videoCapture?.let { useCases.add(it) }

                if (currentSurfaceProvider != null) {
                    previewUseCase = Preview.Builder().build()
                    previewUseCase?.setSurfaceProvider(currentSurfaceProvider)
                    previewUseCase?.let { useCases.add(it) }
                } else {
                    previewUseCase = null
                }

                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    *useCases.toTypedArray()
                )

                // Apply exposure compensation boost if night mode is enabled
                applyExposure()

                onBound?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera", e)
                _recordingState.value = RecordingState.ERROR
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun applyExposure() {
        val currentCam = camera ?: return
        val exposureState = currentCam.cameraInfo.exposureState
        if (exposureState.isExposureCompensationSupported) {
            val targetCompensation = if (_exposureMode.value == 1) { // Manual Mode
                val range = exposureState.exposureCompensationRange
                val ev = _exposureCompensation.value
                if (ev < 0) {
                    (ev * -range.lower).toInt().coerceAtLeast(range.lower)
                } else {
                    (ev * range.upper).toInt().coerceAtMost(range.upper)
                }
            } else if (_nightModeEnabled.value) { // Auto Mode + Night Mode
                val range = exposureState.exposureCompensationRange
                (range.upper * 0.75f).toInt().coerceIn(range.lower, range.upper)
            } else {
                0
            }
            try {
                currentCam.cameraControl.setExposureCompensationIndex(targetCompensation)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set exposure compensation", e)
            }
        }
    }

    fun setExposureMode(mode: Int) {
        _exposureMode.value = mode
        if (_nightModeEnabled.value) {
            _nightModeEnabled.value = false
            val ctx = currentContext
            val lifecycleOwner = currentLifecycleOwner
            if (ctx != null && lifecycleOwner != null && _recordingState.value == RecordingState.IDLE) {
                bindCamera(ctx, lifecycleOwner, currentLensFacing, currentQuality)
                return
            }
        }
        applyExposure()
    }

    fun setExposureCompensation(normalizedValue: Float) {
        _exposureCompensation.value = normalizedValue.coerceIn(-1f, 1f)
        if (_exposureMode.value == 1) {
            applyExposure()
        }
    }

    fun toggleFocusLock() {
        val currentCam = camera ?: return
        _isFocusLocked.value = !_isFocusLocked.value
        
        if (_isFocusLocked.value) {
            val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(1f, 1f)
            val center = factory.createPoint(0.5f, 0.5f)
            val action = androidx.camera.core.FocusMeteringAction.Builder(center, androidx.camera.core.FocusMeteringAction.FLAG_AF)
                .disableAutoCancel()
                .build()
            currentCam.cameraControl.startFocusAndMetering(action)
        } else {
            currentCam.cameraControl.cancelFocusAndMetering()
        }
    }

    fun focusAtPoint(x: Float, y: Float) {
        val currentCam = camera ?: return
        val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(x, y)
        val action = androidx.camera.core.FocusMeteringAction.Builder(point, androidx.camera.core.FocusMeteringAction.FLAG_AF or androidx.camera.core.FocusMeteringAction.FLAG_AE)
            .disableAutoCancel()
            .build()
        currentCam.cameraControl.startFocusAndMetering(action)
        _isFocusLocked.value = true
    }

    fun toggleNightMode() {
        _nightModeEnabled.value = !_nightModeEnabled.value
        if (_nightModeEnabled.value) {
            _exposureMode.value = 0
        }
        val ctx = currentContext
        val lifecycleOwner = currentLifecycleOwner
        if (ctx != null && lifecycleOwner != null && _recordingState.value == RecordingState.IDLE) {
            bindCamera(ctx, lifecycleOwner, currentLensFacing, currentQuality)
        } else {
            applyExposure()
        }
    }

    fun toggleCamera() {
        if (_recordingState.value == RecordingState.RECORDING || _recordingState.value == RecordingState.STARTING) return
        
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        _lensFacing.value = currentLensFacing
        
        currentContext?.let { ctx ->
            currentLifecycleOwner?.let { owner ->
                bindCamera(ctx, owner, currentLensFacing, currentQuality)
            }
        }
    }

    fun toggleTorch() {
        val currentCamera = camera ?: return
        if (!currentCamera.cameraInfo.hasFlashUnit()) return
        val next = !_isTorchOn.value
        currentCamera.cameraControl.enableTorch(next)
        _isTorchOn.value = next
    }

    fun toggleZoom() {
        val currentCamera = camera ?: return
        val current = _zoomRatio.value
        val next = if (current < 1.5f) 2.0f else 1.0f
        currentCamera.cameraControl.setZoomRatio(next)
        _zoomRatio.value = next
    }

    fun setTargetFps(fps: Int) {
        _targetFps.value = fps
    }

    fun cycleCropPosition() {
        _cropPosition.value = when (_cropPosition.value) {
            0.5f -> 0.2f // Top
            0.2f -> 0.8f // Bottom
            else -> 0.5f // Center
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(context: Context, audioEnabled: Boolean) {
        if (_recordingState.value == RecordingState.RECORDING || _recordingState.value == RecordingState.STARTING) return
        
        val capture = videoCapture ?: run {
            Log.e(TAG, "VideoCapture is null when starting recording")
            return
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val isDual = _dualFormatEnabled.value
        val baseName = "VID_$timestamp"
        val name = if (isDual) "${baseName}_Vertical" else baseName
        currentRecordingDisplayName = name
        
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BackgroundRecorder")
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .build()

        _recordingState.value = RecordingState.STARTING
        
        var pendingRecording = capture.output.prepareRecording(context, mediaStoreOutputOptions)
        val hasAudioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioEnabled && hasAudioPermission) {
            pendingRecording = pendingRecording.withAudioEnabled()
        }

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    _recordingState.value = RecordingState.RECORDING
                }
                is VideoRecordEvent.Pause -> {
                    _recordingState.value = RecordingState.PAUSED
                }
                is VideoRecordEvent.Resume -> {
                    _recordingState.value = RecordingState.RECORDING
                }
                is VideoRecordEvent.Status -> {
                    _recordingDurationMs.value = recordEvent.recordingStats.recordedDurationNanos / 1000000L
                }
                is VideoRecordEvent.Finalize -> {
                    if (!recordEvent.hasError() && recordEvent.outputResults.outputUri != android.net.Uri.EMPTY && isDual) {
                        _recordingState.value = RecordingState.STOPPING
                        _isProcessingDualFormat.value = true
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val companionUri = DualFormatVideoHelper.generateCompanionFormat(
                                    context = context.applicationContext,
                                    sourceUri = recordEvent.outputResults.outputUri,
                                    originalDisplayName = "$name.mp4",
                                    cropPosition = _cropPosition.value
                                )
                                withContext(Dispatchers.Main) {
                                    _isProcessingDualFormat.value = false
                                    _recordingState.value = RecordingState.IDLE
                                    _recordingDurationMs.value = 0L
                                    if (companionUri != null) {
                                        Toast.makeText(context, "✅ Recorded in both Vertical & Horizontal formats!", Toast.LENGTH_SHORT).show()
                                    }
                                    onFinalizeCallback?.invoke(recordEvent)
                                    onFinalizeCallback = null
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed dual format generation", e)
                                withContext(Dispatchers.Main) {
                                    _isProcessingDualFormat.value = false
                                    _recordingState.value = RecordingState.IDLE
                                    _recordingDurationMs.value = 0L
                                    onFinalizeCallback?.invoke(recordEvent)
                                    onFinalizeCallback = null
                                }
                            }
                        }
                    } else {
                        _recordingState.value = RecordingState.IDLE
                        _recordingDurationMs.value = 0L
                        if (recordEvent.hasError()) {
                            Log.w(TAG, "Video capture finalized with code: ${recordEvent.error}")
                        }
                        onFinalizeCallback?.invoke(recordEvent)
                        onFinalizeCallback = null
                    }
                }
            }
        }
    }

    fun resetStateIfError() {
        if (_recordingState.value == RecordingState.ERROR || _recordingState.value == RecordingState.STOPPING) {
            _recordingState.value = RecordingState.IDLE
        }
    }

    fun stopRecording(onFinalize: ((VideoRecordEvent.Finalize?) -> Unit)? = null) {
        onFinalizeCallback = onFinalize
        if (activeRecording != null) {
            _recordingState.value = RecordingState.STOPPING
            activeRecording?.stop()
            activeRecording = null
        } else {
            _recordingState.value = RecordingState.IDLE
            onFinalize?.invoke(null)
            onFinalizeCallback = null
        }
    }

    fun pauseRecording() {
        if (_recordingState.value == RecordingState.RECORDING) {
            activeRecording?.pause()
        }
    }

    fun resumeRecording() {
        if (_recordingState.value == RecordingState.PAUSED) {
            activeRecording?.resume()
        }
    }

    fun unbind() {
        cameraProvider?.unbindAll()
    }
}
