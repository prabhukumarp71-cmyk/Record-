package com.example.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.model.RecordingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    fun setSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        currentSurfaceProvider = surfaceProvider
        surfaceProvider?.let { previewUseCase?.setSurfaceProvider(it) }
    }

    @OptIn(ExperimentalCamera2Interop::class)
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

                // Configure low-light optimizations tailored to hardware limitations (Moto Edge 50 Fusion Sony LYT-700C / Snapdragon 7s Gen 2 sensor)
                if (_nightModeEnabled.value) {
                    val camera2Extender = Camera2Interop.Extender(videoCaptureBuilder)
                    // Enable Night Mode scene if supported or low-light boost
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_SCENE_MODE,
                        CaptureRequest.CONTROL_SCENE_MODE_NIGHT
                    )
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_MODE,
                        CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
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
                applyNightModeExposure()

                onBound?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera", e)
                _recordingState.value = RecordingState.ERROR
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun applyNightModeExposure() {
        val currentCam = camera ?: return
        val exposureState = currentCam.cameraInfo.exposureState
        if (exposureState.isExposureCompensationSupported) {
            val targetCompensation = if (_nightModeEnabled.value) {
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

    fun toggleNightMode() {
        _nightModeEnabled.value = !_nightModeEnabled.value
        val ctx = currentContext
        val lifecycleOwner = currentLifecycleOwner
        if (ctx != null && lifecycleOwner != null && _recordingState.value == RecordingState.IDLE) {
            bindCamera(ctx, lifecycleOwner, currentLensFacing, currentQuality)
        } else {
            applyNightModeExposure()
        }
    }

    fun toggleCamera() {
        if (_recordingState.value == RecordingState.RECORDING || _recordingState.value == RecordingState.STARTING) return
        
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        
        currentContext?.let { ctx ->
            currentLifecycleOwner?.let { owner ->
                bindCamera(ctx, owner, currentLensFacing, currentQuality)
            }
        }
    }

    fun toggleTorch() {
        val currentCamera = camera ?: return
        val isTorchOn = currentCamera.cameraInfo.torchState.value == androidx.camera.core.TorchState.ON
        currentCamera.cameraControl.enableTorch(!isTorchOn)
    }

    @SuppressLint("MissingPermission")
    fun startRecording(context: Context, audioEnabled: Boolean) {
        if (_recordingState.value == RecordingState.RECORDING || _recordingState.value == RecordingState.STARTING) return
        
        val capture = videoCapture ?: run {
            Log.e(TAG, "VideoCapture is null when starting recording")
            return
        }
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
        
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
                    _recordingState.value = RecordingState.IDLE
                    _recordingDurationMs.value = 0L
                    if (recordEvent.hasError()) {
                        Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                        if (recordEvent.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
                            _recordingState.value = RecordingState.ERROR
                        }
                    }
                    onFinalizeCallback?.invoke(recordEvent)
                    onFinalizeCallback = null
                }
            }
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
