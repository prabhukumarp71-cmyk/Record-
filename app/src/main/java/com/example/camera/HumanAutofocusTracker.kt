package com.example.camera

import android.annotation.SuppressLint
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HumanAutofocusTracker {
    companion object {
        private const val TAG = "HumanAfTracker"
    }

    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        FaceDetection.getClient(options)
    }

    private var activeCamera: Camera? = null
    private var activeImageAnalysis: ImageAnalysis? = null

    private val _afMode = MutableStateFlow(AutoFocusMode.HUMAN_PRIORITY)
    val afMode: StateFlow<AutoFocusMode> = _afMode

    private val _isHumanAfEnabled = MutableStateFlow(true)
    val humanAfEnabled: StateFlow<Boolean> = _isHumanAfEnabled
    val isHumanAfEnabled: StateFlow<Boolean> = _isHumanAfEnabled

    private val _isFarFocusOnly = MutableStateFlow(false)
    val isFarFocusOnly: StateFlow<Boolean> = _isFarFocusOnly

    private val _allDetectedPersons = MutableStateFlow<List<TrackedPerson>>(emptyList())
    val trackedPersons: StateFlow<List<TrackedPerson>> = _allDetectedPersons

    private var selectedPersonId: Int? = null
    private var lastAfTriggerTime: Long = 0L

    fun attachCamera(camera: Camera?, imageAnalysis: ImageAnalysis?) {
        activeCamera = camera
        activeImageAnalysis = imageAnalysis
    }

    fun detachCamera() {
        activeCamera = null
        activeImageAnalysis = null
        _allDetectedPersons.value = emptyList()
    }

    fun setFarFocusOnly(enabled: Boolean) {
        _isFarFocusOnly.value = enabled
        if (enabled) {
            _isHumanAfEnabled.value = false
            selectedPersonId = null
            _allDetectedPersons.value = emptyList()
        }
    }

    fun toggleHumanAf() {
        if (_isFarFocusOnly.value) return
        _isHumanAfEnabled.value = !_isHumanAfEnabled.value
        if (!_isHumanAfEnabled.value) {
            selectedPersonId = null
            _allDetectedPersons.value = emptyList()
            activeCamera?.cameraControl?.cancelFocusAndMetering()
        }
    }

    fun cycleAfMode() {
        _afMode.value = when (_afMode.value) {
            AutoFocusMode.OFF -> AutoFocusMode.CONTINUOUS
            AutoFocusMode.CONTINUOUS -> AutoFocusMode.HUMAN_PRIORITY
            AutoFocusMode.HUMAN_PRIORITY -> AutoFocusMode.MOTION_TRACKING
            AutoFocusMode.MOTION_TRACKING -> AutoFocusMode.OFF
        }
    }

    fun selectPersonAt(tapX: Float, tapY: Float): Boolean {
        if (!_isHumanAfEnabled.value) return false
        val current = _allDetectedPersons.value
        val hit = current.firstOrNull { it.boundingBox.contains(tapX, tapY) }
        return if (hit != null) {
            selectedPersonId = hit.id
            triggerAf(hit.centerX, hit.centerY, force = true)
            _allDetectedPersons.value = current.map {
                it.copy(isSelected = it.id == hit.id)
            }
            true
        } else {
            false
        }
    }

    fun clearSelectedPerson() {
        selectedPersonId = null
        _allDetectedPersons.value = _allDetectedPersons.value.map { it.copy(isSelected = false) }
    }

    fun createAnalyzer(isFrontCamera: Boolean): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            if (!_isHumanAfEnabled.value || _isFarFocusOnly.value) {
                imageProxy.close()
                return@Analyzer
            }

            processFrame(imageProxy, isFrontCamera)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrame(imageProxy: ImageProxy, isFront: Boolean) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        val frameWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val frameHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                handleDetectedFaces(faces, frameWidth.toFloat(), frameHeight.toFloat(), isFront)
            }
            .addOnFailureListener {
                // Ignore transient frame errors
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleDetectedFaces(faces: List<Face>, frameW: Float, frameH: Float, isFront: Boolean) {
        if (faces.isEmpty()) {
            if (_allDetectedPersons.value.isNotEmpty()) {
                _allDetectedPersons.value = emptyList()
            }
            return
        }

        val mappedPersons = faces.mapIndexed { idx, face ->
            val b = face.boundingBox
            val normLeft = (b.left.toFloat() / frameW).coerceIn(0f, 1f)
            val normTop = (b.top.toFloat() / frameH).coerceIn(0f, 1f)
            val normRight = (b.right.toFloat() / frameW).coerceIn(0f, 1f)
            val normBottom = (b.bottom.toFloat() / frameH).coerceIn(0f, 1f)

            val adjustedLeft = if (isFront) (1f - normRight) else normLeft
            val adjustedRight = if (isFront) (1f - normLeft) else normRight

            val normRect = RectF(adjustedLeft, normTop, adjustedRight, normBottom)
            val faceId = face.trackingId ?: idx
            TrackedPerson(
                id = faceId,
                boundingBox = normRect,
                isSelected = selectedPersonId == faceId,
                centerX = normRect.centerX(),
                centerY = normRect.centerY()
            )
        }

        _allDetectedPersons.value = mappedPersons

        val target = if (selectedPersonId != null) {
            mappedPersons.firstOrNull { it.id == selectedPersonId } ?: mappedPersons.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        } else {
            mappedPersons.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        }

        target?.let {
            val now = System.currentTimeMillis()
            if (now - lastAfTriggerTime > 1200L) {
                lastAfTriggerTime = now
                triggerAf(it.centerX, it.centerY, force = false)
            }
        }
    }

    private fun triggerAf(x: Float, y: Float, force: Boolean) {
        val cam = activeCamera ?: return
        try {
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            val point = factory.createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to meter point: ${e.message}")
        }
    }
}
