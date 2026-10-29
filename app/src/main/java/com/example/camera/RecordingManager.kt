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
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
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

    private val _isFarFocusOnly = MutableStateFlow(false)
    val isFarFocusOnly: StateFlow<Boolean> = _isFarFocusOnly

    private val _isAeLocked = MutableStateFlow(false)
    val isAeLocked: StateFlow<Boolean> = _isAeLocked

    private val _exposureIndex = MutableStateFlow(0)
    val exposureIndex: StateFlow<Int> = _exposureIndex

    private val _exposureCompensationStep = MutableStateFlow(0.166667f)
    val exposureCompensationStep: StateFlow<Float> = _exposureCompensationStep

    private val _exposureEvValue = MutableStateFlow(0.0f)
    val exposureEvValue: StateFlow<Float> = _exposureEvValue

    private val _tapMeterPoint = MutableStateFlow<Pair<Float, Float>?>(null)
    val tapMeterPoint: StateFlow<Pair<Float, Float>?> = _tapMeterPoint

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

    private val _activeQualityDescription = MutableStateFlow("1080p FHD • 1920×1080")
    val activeQualityDescription: StateFlow<String> = _activeQualityDescription

    val humanTracker = HumanAutofocusTracker()

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
    private var currentQuality: Quality = Quality.FHD
    private var onFinalizeCallback: ((VideoRecordEvent.Finalize?) -> Unit)? = null
    private var currentRecordingDisplayName: String = ""
    private var currentRawRecordingFile: java.io.File? = null
    private var currentRecordingBaseName: String = ""

    fun parseQuality(name: String): Quality {
        return when (name.uppercase()) {
            "ULTRA", "4K" -> Quality.UHD
            "HIGH", "1080P", "FHD" -> Quality.FHD
            "MEDIUM", "720P", "HD" -> Quality.HD
            "LOW", "480P", "SD" -> Quality.SD
            else -> Quality.FHD
        }
    }

    fun setVideoQuality(quality: Quality) {
        currentQuality = quality
        val ctx = currentContext
        val lifecycleOwner = currentLifecycleOwner
        if (ctx != null && lifecycleOwner != null && _recordingState.value == RecordingState.IDLE) {
            bindCamera(ctx, lifecycleOwner, currentLensFacing, quality)
        }
    }

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

                val videoCapabilities = cameraInfo?.let {
                    try {
                        Recorder.getVideoCapabilities(it)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error querying VideoCapabilities: ${e.message}")
                        null
                    }
                }
                val supportedQualities: List<Quality> = videoCapabilities?.getSupportedQualities(DynamicRange.SDR) ?: emptyList()
                Log.d(TAG, "Camera lens $lensFacing supported qualities: $supportedQualities, requested quality: $quality")

                // Build robust quality selector ensuring 1080p FHD is given absolute top priority
                val qualitySelector = when (quality) {
                    Quality.UHD -> QualitySelector.fromOrderedList(
                        listOf(Quality.UHD, Quality.FHD, Quality.HD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                    )
                    Quality.FHD -> QualitySelector.fromOrderedList(
                        listOf(Quality.FHD, Quality.HD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                    )
                    Quality.HD -> QualitySelector.fromOrderedList(
                        listOf(Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                    Quality.SD -> QualitySelector.from(
                        Quality.SD,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                    )
                    else -> QualitySelector.fromOrderedList(
                        listOf(Quality.FHD, Quality.HD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                    )
                }

                _activeQualityDescription.value = when (quality) {
                    Quality.UHD -> "4K UHD • 3840×2160"
                    Quality.FHD -> "1080p FHD • 1920×1080"
                    Quality.HD -> "720p HD • 1280×720"
                    Quality.SD -> "480p SD • 720×480"
                    else -> "1080p FHD • 1920×1080"
                }
                Log.d(TAG, "Configured quality selector for recording: requested=$quality, target=${_activeQualityDescription.value}")

                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()

                val videoCaptureBuilder = VideoCapture.Builder(recorder)
                try {
                    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                    val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        context.display?.rotation ?: android.view.Surface.ROTATION_0
                    } else {
                        @Suppress("DEPRECATION")
                        windowManager?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
                    }
                    videoCaptureBuilder.setTargetRotation(rotation)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set targetRotation: ${e.message}")
                }
                val camera2Extender = Camera2Interop.Extender(videoCaptureBuilder)

                // If Far Focus Only is enabled, lock lens to far distance / infinity (0.0 diopters) and turn off near AF sweeps
                if (_isFarFocusOnly.value) {
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF
                    )
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        0.0f
                    )
                }

                // Configure low-light optimizations tailored to hardware limitations (Moto Edge 50 Fusion Sony LYT-700C / Snapdragon 7s Gen 2 sensor)
                if (_nightModeEnabled.value) {
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
                    previewUseCase = Preview.Builder()
                        .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                        .build()
                    previewUseCase?.setSurfaceProvider(currentSurfaceProvider)
                    previewUseCase?.let { useCases.add(it) }
                } else {
                    previewUseCase = null
                }

                // ImageAnalysis for Real-time Human / Motion Detection and Autofocus Tracking
                val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
                val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                imageAnalysis.setAnalyzer(humanTracker.executor, humanTracker.createAnalyzer(isFront))
                useCases.add(imageAnalysis)

                try {
                    camera = cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        *useCases.toTypedArray()
                    )
                    humanTracker.attachCamera(camera, imageAnalysis)
                } catch (e: Exception) {
                    Log.w(TAG, "Binding all use cases failed, attempting fallback without ImageAnalysis: ${e.message}")
                    useCases.remove(imageAnalysis)
                    camera = cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        *useCases.toTypedArray()
                    )
                    humanTracker.attachCamera(camera, null)
                }

                // If Far Focus Only is enabled, enforce Camera2 infinity focus distance
                if (_isFarFocusOnly.value) {
                    applyFarFocus()
                }

                // Apply exposure compensation boost if night mode is enabled
                applyExposure()

                onBound?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera", e)
                _recordingState.value = RecordingState.ERROR
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun applyCameraSettings() {
        val currentCam = camera ?: return
        val exposureState = currentCam.cameraInfo.exposureState
        val range = exposureState.exposureCompensationRange
        val step = if (exposureState.exposureCompensationStep.denominator != 0) {
            exposureState.exposureCompensationStep.numerator.toFloat() / exposureState.exposureCompensationStep.denominator.toFloat()
        } else {
            1f / 6f
        }
        _exposureCompensationStep.value = step

        val targetIndex: Int = if (_exposureMode.value == 1) { // Manual Mode
            val ev = _exposureCompensation.value
            if (range.upper != range.lower) {
                if (ev < 0) {
                    (ev * -range.lower).toInt().coerceIn(range.lower, range.upper)
                } else {
                    (ev * range.upper).toInt().coerceIn(range.lower, range.upper)
                }
            } else {
                0
            }
        } else if (_nightModeEnabled.value) { // Auto Mode + Night Mode boost
            (range.upper * 0.75f).toInt().coerceIn(range.lower, range.upper)
        } else {
            0
        }
        _exposureIndex.value = targetIndex
        if (range.upper != range.lower && step > 0) {
            _exposureEvValue.value = targetIndex * step
        } else {
            _exposureEvValue.value = _exposureCompensation.value * 2.0f
        }

        if (exposureState.isExposureCompensationSupported) {
            try {
                currentCam.cameraControl.setExposureCompensationIndex(targetIndex)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set exposure compensation index: ${e.message}")
            }
        }

        try {
            val camera2Control = Camera2CameraControl.from(currentCam.cameraControl)
            val builder = CaptureRequestOptions.Builder()

            if (_isFarFocusOnly.value) {
                // Focus ONLY far: turn off continuous AF sweeps that lock to near foreground,
                // and lock lens focus distance strictly to infinity (0.0f diopters = infinity/far)
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }

            // AE Lock support
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, _isAeLocked.value)

            // Pass AE exposure compensation to Camera2 capture requests
            if (range.upper != range.lower) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, targetIndex)
            }

            camera2Control.setCaptureRequestOptions(builder.build())
            Log.d(TAG, "Applied camera settings: farOnly=${_isFarFocusOnly.value}, expIndex=$targetIndex, ev=${_exposureEvValue.value}, aeLocked=${_isAeLocked.value}")
        } catch (e: Exception) {
            Log.w(TAG, "Error applying Camera2 options: ${e.message}")
        }
    }

    private fun applyExposure() {
        applyCameraSettings()
    }

    private fun applyFarFocus() {
        applyCameraSettings()
    }

    fun setExposureMode(mode: Int) {
        _exposureMode.value = mode
        if (mode == 0) {
            _isAeLocked.value = false
        }
        if (_nightModeEnabled.value) {
            _nightModeEnabled.value = false
            val ctx = currentContext
            val lifecycleOwner = currentLifecycleOwner
            if (ctx != null && lifecycleOwner != null && _recordingState.value == RecordingState.IDLE) {
                bindCamera(ctx, lifecycleOwner, currentLensFacing, currentQuality)
                return
            }
        }
        applyCameraSettings()
    }

    fun setExposureCompensation(normalizedValue: Float) {
        _exposureCompensation.value = normalizedValue.coerceIn(-1f, 1f)
        if (_exposureMode.value == 0) {
            _exposureMode.value = 1
        }
        applyCameraSettings()
    }

    fun setExposurePreset(normalizedValue: Float) {
        _exposureCompensation.value = normalizedValue.coerceIn(-1f, 1f)
        _exposureMode.value = 1
        applyCameraSettings()
    }

    fun adjustExposureBy(deltaNormalized: Float) {
        _exposureCompensation.value = (_exposureCompensation.value + deltaNormalized).coerceIn(-1f, 1f)
        _exposureMode.value = 1
        applyCameraSettings()
    }

    fun stepExposureEv(deltaEv: Float) {
        val currentCam = camera
        val range = currentCam?.cameraInfo?.exposureState?.exposureCompensationRange
        val step = _exposureCompensationStep.value
        val normDelta = if (range != null && range.upper > range.lower && step > 0) {
            val maxEv = range.upper * step
            if (maxEv > 0) deltaEv / maxEv else deltaEv / 2f
        } else {
            deltaEv / 2f
        }
        adjustExposureBy(normDelta)
    }

    fun toggleAeLock() {
        _isAeLocked.value = !_isAeLocked.value
        applyCameraSettings()
    }

    fun setAeLock(locked: Boolean) {
        _isAeLocked.value = locked
        applyCameraSettings()
    }

    fun resetExposure() {
        _exposureCompensation.value = 0f
        _exposureMode.value = 0
        _isAeLocked.value = false
        _tapMeterPoint.value = null
        applyCameraSettings()
    }

    fun clearTapMeterPoint() {
        _tapMeterPoint.value = null
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun toggleFarFocusOnly() {
        setFarFocusOnly(!_isFarFocusOnly.value)
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun setFarFocusOnly(enabled: Boolean) {
        _isFarFocusOnly.value = enabled
        humanTracker.setFarFocusOnly(enabled)
        if (enabled) {
            _isFocusLocked.value = false
            camera?.cameraControl?.cancelFocusAndMetering()
            // In Far Only mode, automatically activate manual exposure control so exposure is immediately adjustable
            _exposureMode.value = 1
        }
        applyCameraSettings()
    }

    fun toggleFocusLock() {
        val currentCam = camera ?: return
        if (_isFarFocusOnly.value) {
            // Turning on focus lock cancels Far Only mode
            setFarFocusOnly(false)
        }
        _isFocusLocked.value = !_isFocusLocked.value
        
        if (_isFocusLocked.value) {
            val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(1f, 1f)
            val center = factory.createPoint(0.5f, 0.5f)
            val action = androidx.camera.core.FocusMeteringAction.Builder(center, androidx.camera.core.FocusMeteringAction.FLAG_AF)
                .disableAutoCancel()
                .build()
            currentCam.cameraControl.startFocusAndMetering(action)
        } else {
            humanTracker.clearSelectedPerson()
            currentCam.cameraControl.cancelFocusAndMetering()
        }
    }

    fun focusAtPoint(x: Float, y: Float) {
        if (_isFarFocusOnly.value) {
            // Far focus is strictly locked to infinity; meter exposure (AE) at this tapped point
            _tapMeterPoint.value = Pair(x, y)
            val currentCam = camera ?: return
            val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(1f, 1f)
            val point = factory.createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
            val action = androidx.camera.core.FocusMeteringAction.Builder(
                point,
                androidx.camera.core.FocusMeteringAction.FLAG_AE
            ).disableAutoCancel().build()
            try {
                currentCam.cameraControl.startFocusAndMetering(action)
            } catch (e: Exception) {
                Log.w(TAG, "Error metering AE at point: ${e.message}")
            }
            applyCameraSettings()
            return
        }

        val currentCam = camera ?: return
        val hitPerson = humanTracker.selectPersonAt(x, y)
        if (hitPerson) {
            _isFocusLocked.value = false
            return
        }

        // Otherwise, tap locks manual focus and exposure to this specific point
        humanTracker.clearSelectedPerson()
        val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        val action = androidx.camera.core.FocusMeteringAction.Builder(point, androidx.camera.core.FocusMeteringAction.FLAG_AF or androidx.camera.core.FocusMeteringAction.FLAG_AE)
            .disableAutoCancel()
            .build()
        try {
            currentCam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.w(TAG, "Error startFocusAndMetering: ${e.message}")
        }
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
        currentRecordingBaseName = baseName
        currentRecordingDisplayName = if (isDual) "${baseName}_Vertical" else baseName

        val rawRecordingFile = java.io.File(context.cacheDir, "camera_raw_${System.currentTimeMillis()}.mp4")
        currentRawRecordingFile = rawRecordingFile

        val fileOutputOptions = FileOutputOptions.Builder(rawRecordingFile).build()

        _recordingState.value = RecordingState.STARTING
        
        var pendingRecording = capture.output.prepareRecording(context, fileOutputOptions)
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
                    val rawFile = currentRawRecordingFile
                    val base = currentRecordingBaseName
                    if (!recordEvent.hasError() && rawFile != null && rawFile.exists() && rawFile.length() > 0L) {
                        _recordingState.value = RecordingState.STOPPING
                        _isProcessingDualFormat.value = true
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val result = DualFormatVideoHelper.processAndSaveRecording(
                                    context = context.applicationContext,
                                    rawFile = rawFile,
                                    baseName = base,
                                    isDual = isDual,
                                    cropPosition = _cropPosition.value,
                                    targetQuality = currentQuality
                                )

                                withContext(Dispatchers.Main) {
                                    _isProcessingDualFormat.value = false
                                    _recordingState.value = RecordingState.IDLE
                                    _recordingDurationMs.value = 0L
                                    val msg = if (result.companionUri != null) {
                                        "✅ Saved in 1920×1080p (Vertical & Horizontal)!"
                                    } else {
                                        "✅ Saved in 1920×1080p FHD!"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    onFinalizeCallback?.invoke(recordEvent)
                                    onFinalizeCallback = null
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed video post-processing", e)
                                withContext(Dispatchers.Main) {
                                    _isProcessingDualFormat.value = false
                                    _recordingState.value = RecordingState.IDLE
                                    _recordingDurationMs.value = 0L
                                    onFinalizeCallback?.invoke(recordEvent)
                                    onFinalizeCallback = null
                                }
                            } finally {
                                if (rawFile.exists()) {
                                    rawFile.delete()
                                }
                                currentRawRecordingFile = null
                            }
                        }
                    } else {
                        _recordingState.value = RecordingState.IDLE
                        _recordingDurationMs.value = 0L
                        if (recordEvent.hasError()) {
                            Log.w(TAG, "Video capture finalized with code: ${recordEvent.error}")
                        }
                        if (rawFile != null && rawFile.exists()) {
                            rawFile.delete()
                        }
                        currentRawRecordingFile = null
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
        humanTracker.detachCamera()
        cameraProvider?.unbindAll()
    }
}
