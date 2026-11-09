package com.example.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.CamcorderProfile
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.media.DualFormatVideoHelper
import com.example.model.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

    private val _exposureCompensation = MutableStateFlow(0f)
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

    private val _dualFormatEnabled = MutableStateFlow(false)
    val dualFormatEnabled: StateFlow<Boolean> = _dualFormatEnabled

    private val _exposureRange = MutableStateFlow(Pair(-6, 6))
    val exposureRange: StateFlow<Pair<Int, Int>> = _exposureRange

    private val _isProcessingDualFormat = MutableStateFlow(false)
    val isProcessingDualFormat: StateFlow<Boolean> = _isProcessingDualFormat

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio

    private val _lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val lensFacing: StateFlow<Int> = _lensFacing

    private val _cropPosition = MutableStateFlow(0.5f)
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

    val isCameraReady: Boolean
        get() = videoCapture != null

    var currentSurfaceProvider: Preview.SurfaceProvider? = null
        private set
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentContext: Context? = null
    private var currentQuality: Quality = Quality.FHD
    private var onFinalizeCallback: ((VideoRecordEvent.Finalize?) -> Unit)? = null
    private var currentRecordingDisplayName: String = ""
    private var currentRawRecordingFile: File? = null
    private var currentRecordingBaseName: String = ""

    fun parseQuality(name: String): Quality {
        return when (name.uppercase()) {
            "ULTRA", "4K", "UHD" -> Quality.UHD
            "HIGH", "1080P", "FHD" -> Quality.FHD
            "MEDIUM", "720P", "HD" -> Quality.HD
            "LOW", "480P", "SD" -> Quality.SD
            else -> Quality.FHD
        }
    }

    val cameraLifecycleOwner = CameraLifecycleOwner()

    fun setVideoQuality(quality: Quality) {
        currentQuality = quality
        val ctx = currentContext
        if (ctx != null && _recordingState.value == RecordingState.IDLE) {
            bindCamera(ctx, cameraLifecycleOwner, currentLensFacing, quality)
        }
    }

    var persistentSurfaceTexture: android.graphics.SurfaceTexture? = null
        private set
    var persistentSurface: android.view.Surface? = null
        private set
    private var pendingSurfaceRequest: androidx.camera.core.SurfaceRequest? = null

    fun provideTextureViewSurface(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
        if (persistentSurfaceTexture != st) {
            persistentSurfaceTexture = st
            st.setDefaultBufferSize(width, height)
            persistentSurface = android.view.Surface(st)
        }
        val surf = persistentSurface
        if (surf != null && surf.isValid) {
            pendingSurfaceRequest?.let { req ->
                try {
                    req.provideSurface(surf, ContextCompat.getMainExecutor(currentContext ?: return)) { res ->
                        Log.d(TAG, "Provided surface completed: ${res.resultCode}")
                    }
                    pendingSurfaceRequest = null
                } catch (e: Exception) {
                    Log.w(TAG, "Error providing surface to pending request: ${e.message}")
                }
            }
        }
    }

    private var safeSurfaceProvider: BackgroundSafeSurfaceProvider? = null

    fun setUiSurfaceProvider(provider: Preview.SurfaceProvider?) {
        currentSurfaceProvider = provider
        safeSurfaceProvider?.setUiProvider(provider)
    }

    fun setSurfaceProvider(provider: Preview.SurfaceProvider?) {
        setUiSurfaceProvider(provider)
    }

    @SuppressLint("UnsafeOptInUsageError")
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun bindCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner = cameraLifecycleOwner,
        lensFacing: Int = currentLensFacing,
        quality: Quality = currentQuality,
        surfaceProvider: Preview.SurfaceProvider? = currentSurfaceProvider,
        onBound: (() -> Unit)? = null
    ) {
        currentContext = context.applicationContext
        currentLifecycleOwner = cameraLifecycleOwner
        cameraLifecycleOwner.start()
        currentLensFacing = lensFacing
        _lensFacing.value = lensFacing
        currentQuality = quality
        if (surfaceProvider != null) {
            currentSurfaceProvider = surfaceProvider
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                cameraProvider?.unbindAll()

                val cameraInfo = try {
                    cameraProvider?.getCameraInfo(cameraSelector)
                } catch (e: Exception) {
                    null
                }

                val camIdInt = cameraInfo?.let { info ->
                    try {
                        Camera2CameraInfo.from(info).cameraId.toIntOrNull()
                    } catch (e: Exception) {
                        null
                    }
                } ?: if (lensFacing == CameraSelector.LENS_FACING_FRONT) 1 else 0

                val supportedQualities = mutableListOf<Quality>()
                if (cameraInfo != null) {
                    try {
                        val videoCaps = Recorder.getVideoCapabilities(cameraInfo)
                        val caps = videoCaps.getSupportedQualities(DynamicRange.SDR)
                        supportedQualities.addAll(caps)
                        Log.d(TAG, "Supported qualities from VideoCapabilities: $caps")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error checking VideoCapabilities: ${e.message}")
                    }
                }

                if (supportedQualities.isEmpty()) {
                    supportedQualities.addAll(listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD))
                }

                val preferredQualities = mutableListOf<Quality>()
                if (quality in supportedQualities) {
                    preferredQualities.add(quality)
                }
                listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD).forEach { q ->
                    if (q in supportedQualities && !preferredQualities.contains(q)) {
                        preferredQualities.add(q)
                    }
                }
                if (preferredQualities.isEmpty()) {
                    preferredQualities.addAll(listOf(Quality.FHD, Quality.HD, Quality.SD))
                }

                val qualitySelector = QualitySelector.fromOrderedList(
                    preferredQualities,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )

                val resolvedQuality = preferredQualities.firstOrNull() ?: Quality.FHD

                _activeQualityDescription.value = when (resolvedQuality) {
                    Quality.UHD -> "4K UHD • 3840×2160 (High Quality)"
                    Quality.FHD -> "1080p FHD • 1920×1080 (High Quality)"
                    Quality.HD -> "720p HD • 1280×720 (High Quality)"
                    Quality.SD -> "480p SD • 720×480"
                    Quality.HIGHEST -> "4K UHD (High Quality)"
                    else -> "1080p FHD (High Quality)"
                }

                val recorderBuilder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)

                val targetBitRate = when (resolvedQuality) {
                    Quality.UHD -> 36_000_000
                    Quality.FHD -> 20_000_000
                    Quality.HD -> 12_000_000
                    else -> 8_000_000
                }
                try {
                    recorderBuilder.setTargetVideoEncodingBitRate(targetBitRate)
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot set target bitrate: ${e.message}")
                }

                val recorder = recorderBuilder.build()
                val videoCaptureBuilder = VideoCapture.Builder(recorder)

                val camera2Extender = Camera2Interop.Extender(videoCaptureBuilder)

                // Master quality & continuous sharp auto-focus
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_MODE,
                    CaptureRequest.CONTROL_MODE_AUTO
                )
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON
                )
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_AUTO
                )
                if (_isFarFocusOnly.value) {
                    val camera2Info = cameraInfo?.let {
                        try { Camera2CameraInfo.from(it) } catch (_: Exception) { null }
                    }
                    val afModes = camera2Info?.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                    val minFocusDistance = camera2Info?.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f
                    val hyperfocal = camera2Info?.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)

                    if (minFocusDistance > 0.0f && afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) {
                        val targetDist = if (hyperfocal != null && hyperfocal > 0.0f) hyperfocal else 0.0f
                        camera2Extender.setCaptureRequestOption(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_OFF
                        )
                        camera2Extender.setCaptureRequestOption(
                            CaptureRequest.LENS_FOCUS_DISTANCE,
                            targetDist
                        )
                    } else {
                        camera2Extender.setCaptureRequestOption(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        )
                    }
                } else {
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    )
                }
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.EDGE_MODE,
                    CaptureRequest.EDGE_MODE_HIGH_QUALITY
                )

                if (_nightModeEnabled.value) {
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(15, 30)
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
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting FPS range", e)
                        }
                    }
                }

                videoCapture = videoCaptureBuilder.build()

                val useCases = mutableListOf<UseCase>()
                videoCapture?.let { useCases.add(it) }

                val safeProvider = safeSurfaceProvider ?: BackgroundSafeSurfaceProvider(context.applicationContext).also {
                    safeSurfaceProvider = it
                }
                if (surfaceProvider != null) {
                    safeProvider.setUiProvider(surfaceProvider)
                }

                previewUseCase = Preview.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                    .build()

                previewUseCase?.setSurfaceProvider { request ->
                    val surf = persistentSurface
                    if (surf != null && surf.isValid) {
                        try {
                            request.provideSurface(surf, ContextCompat.getMainExecutor(context)) { result ->
                                Log.d(TAG, "Persistent preview surface result: ${result.resultCode}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Fallback to safeProvider on surface exception: ${e.message}")
                            safeProvider.onSurfaceRequested(request)
                        }
                    } else {
                        pendingSurfaceRequest = request
                        safeProvider.onSurfaceRequested(request)
                    }
                }
                previewUseCase?.let { useCases.add(it) }

                try {
                    camera = cameraProvider?.bindToLifecycle(
                        cameraLifecycleOwner,
                        cameraSelector,
                        *useCases.toTypedArray()
                    )
                    humanTracker.attachCamera(camera, null)
                    if (_recordingState.value == RecordingState.ERROR) {
                        _recordingState.value = RecordingState.IDLE
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Binding with videoCapture failed: ${e.message}, attempting lowest quality fallback...")
                    try {
                            videoCapture?.let { useCases.remove(it) }
                            val fallbackRecorder = Recorder.Builder()
                                .setQualitySelector(
                                    QualitySelector.from(
                                        Quality.LOWEST,
                                        FallbackStrategy.higherQualityOrLowerThan(Quality.LOWEST)
                                    )
                                ).build()
                            val fallbackVideoCapture = VideoCapture.Builder(fallbackRecorder).build()
                            videoCapture = fallbackVideoCapture
                            useCases.add(fallbackVideoCapture)

                            camera = cameraProvider?.bindToLifecycle(
                                cameraLifecycleOwner,
                                cameraSelector,
                                *useCases.toTypedArray()
                            )
                            humanTracker.attachCamera(camera, null)
                            if (_recordingState.value == RecordingState.ERROR) {
                                _recordingState.value = RecordingState.IDLE
                            }
                            Log.i(TAG, "Successfully bound camera with lowest quality VideoCapture")
                    } catch (e3: Exception) {
                        Log.e(TAG, "VideoCapture not supported on this device/emulator: ${e3.message}. Falling back to preview-only mode.")
                        videoCapture?.let { useCases.remove(it) }
                        videoCapture = null
                        try {
                            camera = cameraProvider?.bindToLifecycle(
                                cameraLifecycleOwner,
                                cameraSelector,
                                *useCases.toTypedArray()
                            )
                            humanTracker.attachCamera(camera, null)
                            if (_recordingState.value == RecordingState.ERROR) {
                                _recordingState.value = RecordingState.IDLE
                            }
                            Log.i(TAG, "Successfully bound preview-only mode")
                        } catch (e4: Exception) {
                            Log.e(TAG, "Fatal: unable to bind camera preview", e4)
                            _recordingState.value = RecordingState.ERROR
                        }
                    }
                }

                if (_isFarFocusOnly.value) {
                    setFarFocusOnly(true)
                }

                applyCameraSettings()
                onBound?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed in camera provider listener", e)
                _recordingState.value = RecordingState.ERROR
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun applyCameraSettings() {
        val currentCam = camera ?: return
        val exposureState = currentCam.cameraInfo.exposureState
        val step = if (exposureState.exposureCompensationStep.denominator != 0) {
            exposureState.exposureCompensationStep.numerator.toFloat() / exposureState.exposureCompensationStep.denominator.toFloat()
        } else 0.166667f
        _exposureCompensationStep.value = step
        _exposureIndex.value = exposureState.exposureCompensationIndex
        _exposureEvValue.value = exposureState.exposureCompensationIndex * step
        _exposureRange.value = Pair(exposureState.exposureCompensationRange.lower, exposureState.exposureCompensationRange.upper)
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun setFarFocusOnly(enabled: Boolean) {
        _isFarFocusOnly.value = enabled
        _isFocusLocked.value = enabled
        val currentCam = camera ?: return
        val cameraControl = currentCam.cameraControl
        val camera2Control = Camera2CameraControl.from(cameraControl)
        val camera2Info = try {
            Camera2CameraInfo.from(currentCam.cameraInfo)
        } catch (_: Exception) {
            null
        }

        if (enabled) {
            cameraControl.cancelFocusAndMetering()
            val afModes = camera2Info?.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val minFocusDistance = camera2Info?.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f
            val hyperfocalDistance = camera2Info?.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)

            val builder = CaptureRequestOptions.Builder()

            if (minFocusDistance == 0.0f) {
                // Fixed-focus camera lens (e.g. many front cameras or ultra-wide lenses)
                // Manual focus distance is not supported; keeping auto mode is optimal
                if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                } else {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                }
                Log.d(TAG, "Far Focus: Fixed-focus lens detected (minFocusDistance=0)")
            } else if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) {
                // Manual focus is supported: use hyperfocal distance if calibrated, or 0.0f (infinity)
                // If hyperfocal distance is available and > 0, everything from (1 / hyperfocal) to infinity is in sharp focus!
                val targetFocusDistance = if (hyperfocalDistance != null && hyperfocalDistance > 0.0f) {
                    hyperfocalDistance
                } else {
                    0.0f
                }
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, targetFocusDistance)
                Log.d(TAG, "Far Focus Locked with AF_MODE_OFF at focus distance: $targetFocusDistance (minFocus: $minFocusDistance, hyperfocal: $hyperfocalDistance)")
            } else {
                // If AF_MODE_OFF is not supported by driver, use continuous video or auto AF locked
                val fallbackAf = if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                } else {
                    CaptureRequest.CONTROL_AF_MODE_AUTO
                }
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, fallbackAf)
                Log.d(TAG, "Far Focus: AF_MODE_OFF not in available modes, using AF mode: $fallbackAf")
            }

            camera2Control.setCaptureRequestOptions(builder.build())
        } else {
            cameraControl.cancelFocusAndMetering()
            val afModes = camera2Info?.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val restoreAfMode = if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            } else if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                CaptureRequest.CONTROL_AF_MODE_AUTO
            } else {
                CaptureRequest.CONTROL_AF_MODE_OFF
            }

            val captureOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, restoreAfMode)
                .build()
            camera2Control.setCaptureRequestOptions(captureOptions)
            Log.d(TAG, "Restored Continuous Video Auto-Focus mode: $restoreAfMode")
        }
    }

    fun setExposureIndex(index: Int) {
        val currentCam = camera ?: return
        val range = currentCam.cameraInfo.exposureState.exposureCompensationRange
        val clamped = index.coerceIn(range.lower, range.upper)
        _exposureIndex.value = clamped
        try {
            currentCam.cameraControl.setExposureCompensationIndex(clamped)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set exposure index: ${e.message}")
        }
        _exposureEvValue.value = clamped * _exposureCompensationStep.value
    }

    fun resetExposure() {
        setExposureIndex(0)
    }

    fun clearTapMeterPoint() {
        _tapMeterPoint.value = null
    }

    fun toggleFarFocusOnly() {
        setFarFocusOnly(!_isFarFocusOnly.value)
    }

    fun toggleFocusLock() {
        val currentCam = camera ?: return
        if (_isFarFocusOnly.value) {
            setFarFocusOnly(false)
        }
        _isFocusLocked.value = !_isFocusLocked.value

        if (_isFocusLocked.value) {
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            val center = factory.createPoint(0.5f, 0.5f)
            val action = FocusMeteringAction.Builder(center, FocusMeteringAction.FLAG_AF)
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
            _tapMeterPoint.value = Pair(x, y)
            val currentCam = camera ?: return
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            val point = factory.createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE)
                .disableAutoCancel()
                .build()
            try {
                currentCam.cameraControl.startFocusAndMetering(action)
            } catch (e: Exception) {
                Log.w(TAG, "Error metering AE: ${e.message}")
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

        humanTracker.clearSelectedPerson()
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
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
        if (ctx != null && _recordingState.value == RecordingState.IDLE) {
            bindCamera(ctx, cameraLifecycleOwner, currentLensFacing, currentQuality)
        } else {
            applyCameraSettings()
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
            bindCamera(ctx, cameraLifecycleOwner, currentLensFacing, currentQuality)
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
            0.5f -> 0.2f
            0.2f -> 0.8f
            else -> 0.5f
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(context: Context, audioEnabled: Boolean) {
        if (activeRecording != null || _recordingState.value == RecordingState.RECORDING) {
            Log.w(TAG, "Recording already active, ignoring duplicate start call")
            return
        }

        val capture = videoCapture ?: run {
            Log.e(TAG, "VideoCapture is null when starting recording")
            _recordingState.value = RecordingState.ERROR
            Toast.makeText(context, "Camera is still initializing, please try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val isDual = _dualFormatEnabled.value
        val baseName = "VID_$timestamp"
        currentRecordingBaseName = baseName
        currentRecordingDisplayName = if (isDual) "${baseName}_Vertical" else baseName

        val rawRecordingFile = File(context.cacheDir, "camera_raw_${System.currentTimeMillis()}.mp4")
        currentRawRecordingFile = rawRecordingFile

        val fileOutputOptions = FileOutputOptions.Builder(rawRecordingFile).build()

        _recordingState.value = RecordingState.STARTING

        try {
            var pendingRecording = capture.output.prepareRecording(context, fileOutputOptions)
            val hasAudioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (audioEnabled && hasAudioPermission) {
                pendingRecording = pendingRecording.withAudioEnabled()
            }

            activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        Log.i(TAG, "VideoRecordEvent.Start received: RECORDING IS ACTIVE")
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
                                    val qualityTag = if (currentQuality == Quality.UHD) "4K UHD" else "1080p FHD"
                                    val msg = if (result.companionUri != null) {
                                        "✅ Saved in $qualityTag High Quality (Vertical & Horizontal)!"
                                    } else {
                                        "✅ Saved in $qualityTag High Quality!"
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
                            val errorDesc = when (recordEvent.error) {
                                VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "Encoder failed for chosen resolution/bitrate"
                                VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "File size limit reached"
                                VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "Insufficient storage space"
                                VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "Invalid output options"
                                VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "No video data recorded"
                                VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "Camera recorder error"
                                VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> "Recorder was disposed"
                                VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "Camera stream inactive"
                                else -> "Recording error (code: ${recordEvent.error})"
                            }
                            Log.e(TAG, "Video capture finalized with code: ${recordEvent.error} - $errorDesc", recordEvent.cause)
                            Toast.makeText(context, "⚠️ $errorDesc", Toast.LENGTH_LONG).show()
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
    } catch (e: Exception) {
        Log.e(TAG, "Failed to start recording: ${e.message}", e)
        _recordingState.value = RecordingState.ERROR
        activeRecording = null
        Toast.makeText(context, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

    fun setStarting() {
        if (_recordingState.value == RecordingState.IDLE) {
            _recordingState.value = RecordingState.STARTING
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
