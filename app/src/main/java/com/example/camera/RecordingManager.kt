package com.example.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
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

    private var currentSurfaceProvider: Preview.SurfaceProvider? = null
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

    fun setVideoQuality(quality: Quality) {
        currentQuality = quality
        val ctx = currentContext
        val lifecycleOwner = currentLifecycleOwner
        if (ctx != null && lifecycleOwner != null && _recordingState.value == RecordingState.IDLE) {
            bindCamera(ctx, lifecycleOwner, currentLensFacing, quality)
        }
    }

    fun setDualFormatEnabled(enabled: Boolean) {
        _dualFormatEnabled.value = enabled
    }

    fun setSurfaceProvider(provider: Preview.SurfaceProvider?) {
        currentSurfaceProvider = provider
        previewUseCase?.setSurfaceProvider(provider)
    }

    @SuppressLint("UnsafeOptInUsageError")
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun bindCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        lensFacing: Int = currentLensFacing,
        quality: Quality = currentQuality,
        surfaceProvider: Preview.SurfaceProvider? = currentSurfaceProvider,
        onBound: (() -> Unit)? = null
    ) {
        currentContext = context.applicationContext
        currentLifecycleOwner = lifecycleOwner
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

                val qualitySelector = when (quality) {
                    Quality.UHD -> QualitySelector.from(
                        Quality.UHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
                    )
                    Quality.FHD -> QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                    )
                    Quality.HD -> QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                    Quality.SD -> QualitySelector.from(
                        Quality.SD,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                    )
                    else -> QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                    )
                }

                _activeQualityDescription.value = when (quality) {
                    Quality.UHD -> "4K UHD • 3840×2160 (High Profile)"
                    Quality.FHD -> "1080p FHD • 1920×1080 (High Profile)"
                    Quality.HD -> "720p HD • 1280×720 (High Profile)"
                    Quality.SD -> "480p SD • 720×480"
                    else -> "1080p FHD • 1920×1080"
                }

                val fps = _targetFps.value
                val targetBitRate = when (quality) {
                    Quality.UHD -> if (fps >= 60) 40_000_000 else 30_000_000
                    Quality.FHD -> if (fps >= 60) 24_000_000 else 18_000_000
                    Quality.HD -> 10_000_000
                    else -> 6_000_000
                }

                val recorderBuilder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)

                try {
                    recorderBuilder.setTargetVideoEncodingBitRate(targetBitRate)
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot set target bitrate: ${e.message}")
                }

                val recorder = recorderBuilder.build()
                val videoCaptureBuilder = VideoCapture.Builder(recorder)

                val camera2Extender = Camera2Interop.Extender(videoCaptureBuilder)

                // Master quality & sharpness enhancements directly on camera pipeline
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
                if (!_isFarFocusOnly.value) {
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    )
                }
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.EDGE_MODE,
                    CaptureRequest.EDGE_MODE_HIGH_QUALITY
                )
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                )
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.TONEMAP_MODE,
                    CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
                )
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
                )
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.HOT_PIXEL_MODE,
                    CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
                )
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.SHADING_MODE,
                    CaptureRequest.SHADING_MODE_HIGH_QUALITY
                )
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                )

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

                if (currentSurfaceProvider != null) {
                    previewUseCase = Preview.Builder()
                        .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                        .build()
                    previewUseCase?.setSurfaceProvider(currentSurfaceProvider)
                    previewUseCase?.let { useCases.add(it) }
                } else {
                    previewUseCase = null
                }

                val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetResolution(Size(640, 480))
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
                    Log.w(TAG, "Binding all use cases failed, fallback without ImageAnalysis: ${e.message}")
                    useCases.remove(imageAnalysis)
                    camera = cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        *useCases.toTypedArray()
                    )
                    humanTracker.attachCamera(camera, null)
                }

                if (_isFarFocusOnly.value) {
                    applyFarFocus()
                }

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
        val step = if (exposureState.exposureCompensationStep.denominator != 0) {
            exposureState.exposureCompensationStep.numerator.toFloat() / exposureState.exposureCompensationStep.denominator.toFloat()
        } else 0.166667f
        _exposureCompensationStep.value = step
        _exposureIndex.value = exposureState.exposureCompensationIndex
        _exposureEvValue.value = exposureState.exposureCompensationIndex * step
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyFarFocus() {
        val currentCam = camera ?: return
        val cameraControl = currentCam.cameraControl
        val camera2Control = Camera2CameraControl.from(cameraControl)
        val captureOptions = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
            .build()
        camera2Control.setCaptureRequestOptions(captureOptions)
    }

    fun setExposureMode(mode: Int) {
        _exposureMode.value = mode
        applyExposure()
    }

    fun setExposureCompensation(value: Float) {
        _exposureCompensation.value = value.coerceIn(-1.0f, 1.0f)
        applyExposure()
    }

    fun setExposureIndex(index: Int) {
        _exposureIndex.value = index
        camera?.cameraControl?.setExposureCompensationIndex(index)
        _exposureEvValue.value = index * _exposureCompensationStep.value
    }

    private fun applyExposure() {
        val currentCam = camera ?: return
        val cameraInfo = currentCam.cameraInfo
        val range = cameraInfo.exposureState.exposureCompensationRange
        val targetIndex = if (_nightModeEnabled.value) {
            (range.upper * 0.75f).toInt()
        } else if (_exposureMode.value == 1) {
            val fraction = (_exposureCompensation.value + 1.0f) / 2.0f
            (range.lower + fraction * (range.upper - range.lower)).toInt()
        } else {
            0
        }
        val clampedIndex = targetIndex.coerceIn(range.lower, range.upper)
        currentCam.cameraControl.setExposureCompensationIndex(clampedIndex)
        _exposureIndex.value = clampedIndex
        _exposureEvValue.value = clampedIndex * _exposureCompensationStep.value
    }

    fun clearTapMeterPoint() {
        _tapMeterPoint.value = null
    }

    fun toggleFarFocusOnly() {
        setFarFocusOnly(!_isFarFocusOnly.value)
    }

    fun setFarFocusOnly(enabled: Boolean) {
        _isFarFocusOnly.value = enabled
        humanTracker.setFarFocusOnly(enabled)
        if (enabled) {
            _isFocusLocked.value = false
            camera?.cameraControl?.cancelFocusAndMetering()
            _exposureMode.value = 1
            applyFarFocus()
        }
        applyCameraSettings()
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
            0.5f -> 0.2f
            0.2f -> 0.8f
            else -> 0.5f
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(context: Context, audioEnabled: Boolean) {
        if (_recordingState.value == RecordingState.RECORDING || _recordingState.value == RecordingState.STARTING) return

        val capture = videoCapture ?: run {
            Log.e(TAG, "VideoCapture is null when starting recording")
            Toast.makeText(context, "Camera is still initializing, please try again in a second", Toast.LENGTH_SHORT).show()
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
