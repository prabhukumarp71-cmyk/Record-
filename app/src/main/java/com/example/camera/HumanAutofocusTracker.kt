package com.example.camera

import android.graphics.RectF
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.ExperimentalGetImage
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
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

data class TrackedPerson(
    val id: Int,
    val bounds: RectF, // Normalized [0, 1] relative to preview frame
    val centerX: Float,
    val centerY: Float,
    val velocity: Float, // Normalized screen distance / second
    val isMoving: Boolean,
    val lastSeenTimestamp: Long
)

class HumanAutofocusTracker {

    companion object {
        private const val TAG = "HumanAutofocusTracker"
        private const val MOTION_SPEED_THRESHOLD = 0.045f // 4.5% screen distance / sec
        private const val HUMAN_LOST_TIMEOUT_MS = 1000L
        private const val MIN_AF_TRIGGER_INTERVAL_MS = 400L
        private const val PERIODIC_AF_INTERVAL_MS = 1400L
        private const val MOVEMENT_DISPLACEMENT_THRESHOLD = 0.035f // 3.5% screen displacement
    }

    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var activeCamera: Camera? = null

    private val _isHumanAfEnabled = MutableStateFlow(true)
    val isHumanAfEnabled: StateFlow<Boolean> = _isHumanAfEnabled

    private val _trackedPerson = MutableStateFlow<TrackedPerson?>(null)
    val trackedPerson: StateFlow<TrackedPerson?> = _trackedPerson

    private val _allDetectedPersons = MutableStateFlow<List<TrackedPerson>>(emptyList())
    val allDetectedPersons: StateFlow<List<TrackedPerson>> = _allDetectedPersons

    private val _isPersonMoving = MutableStateFlow(false)
    val isPersonMoving: StateFlow<Boolean> = _isPersonMoving

    private val _afStatusText = MutableStateFlow("AF: HUMAN AUTO")
    val afStatusText: StateFlow<String> = _afStatusText

    // Explicit user selected person ID
    private var selectedPersonId: Int? = null

    // Motion tracking history per face ID
    private data class MotionData(
        var x: Float,
        var y: Float,
        var timestamp: Long,
        var smoothedSpeed: Float
    )
    private val motionHistory = HashMap<Int, MotionData>()

    // Coordinate smoothing
    private var smoothedTargetX = 0.5f
    private var smoothedTargetY = 0.5f
    private var lastTriggeredX = 0.5f
    private var lastTriggeredY = 0.5f
    private var lastAfTriggerTime = 0L
    private var lastPersonSeenTime = 0L

    fun setHumanAfEnabled(enabled: Boolean) {
        _isHumanAfEnabled.value = enabled
        if (!enabled) {
            _trackedPerson.value = null
            _allDetectedPersons.value = emptyList()
            _isPersonMoving.value = false
            _afStatusText.value = "AF: STANDARD AUTO"
            resetToCenterAf()
        } else {
            _afStatusText.value = "AF: SCANNING HUMANS"
        }
    }

    fun toggleHumanAf() {
        setHumanAfEnabled(!_isHumanAfEnabled.value)
    }

    fun attachCamera(camera: Camera?) {
        this.activeCamera = camera
        motionHistory.clear()
        selectedPersonId = null
        lastPersonSeenTime = 0L
        lastAfTriggerTime = 0L
    }

    fun detachCamera() {
        this.activeCamera = null
        _trackedPerson.value = null
        _allDetectedPersons.value = emptyList()
        _isPersonMoving.value = false
        motionHistory.clear()
    }

    fun selectPersonAt(tapX: Float, tapY: Float): Boolean {
        if (!_isHumanAfEnabled.value) return false
        val currentList = _allDetectedPersons.value
        // Check if tap intersects any person bounds with slight expansion
        for (person in currentList) {
            val margin = 0.08f
            val hit = tapX >= (person.bounds.left - margin) &&
                    tapX <= (person.bounds.right + margin) &&
                    tapY >= (person.bounds.top - margin) &&
                    tapY <= (person.bounds.bottom + margin)
            if (hit) {
                selectedPersonId = person.id
                _trackedPerson.value = person
                triggerAfOnPerson(person, force = true)
                Log.d(TAG, "Selected person ID ${person.id} via user tap")
                return true
            }
        }
        return false
    }

    fun clearSelectedPerson() {
        selectedPersonId = null
    }

    fun createAnalyzer(isFrontCamera: Boolean): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            processFrame(imageProxy, isFrontCamera)
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || !_isHumanAfEnabled.value) {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        // After rotation, effective dimensions
        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val effectiveWidth = if (isRotated) imageProxy.height.toFloat() else imageProxy.width.toFloat()
        val effectiveHeight = if (isRotated) imageProxy.width.toFloat() else imageProxy.height.toFloat()

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                handleDetectedFaces(faces, effectiveWidth, effectiveHeight, isFrontCamera)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Face detection failure", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleDetectedFaces(
        faces: List<Face>,
        effectiveWidth: Float,
        effectiveHeight: Float,
        isFrontCamera: Boolean
    ) {
        val now = System.currentTimeMillis()

        if (faces.isEmpty()) {
            _allDetectedPersons.value = emptyList()
            if (_trackedPerson.value != null && (now - lastPersonSeenTime) > HUMAN_LOST_TIMEOUT_MS) {
                Log.d(TAG, "No humans detected for ${HUMAN_LOST_TIMEOUT_MS}ms. Falling back to center/object AF.")
                _trackedPerson.value = null
                _isPersonMoving.value = false
                selectedPersonId = null
                _afStatusText.value = "AF: AUTO (NO PERSON)"
                resetToCenterAf()
            }
            return
        }

        lastPersonSeenTime = now

        // Convert detected faces into TrackedPerson models with motion analysis
        val detectedList = mutableListOf<TrackedPerson>()

        for (face in faces) {
            val rawBox = face.boundingBox
            var normLeft = rawBox.left / effectiveWidth
            var normRight = rawBox.right / effectiveWidth
            val normTop = rawBox.top / effectiveHeight
            val normBottom = rawBox.bottom / effectiveHeight

            if (isFrontCamera) {
                val tempLeft = normLeft
                normLeft = 1f - normRight
                normRight = 1f - tempLeft
            }

            val clampedBounds = RectF(
                normLeft.coerceIn(0f, 1f),
                normTop.coerceIn(0f, 1f),
                normRight.coerceIn(0f, 1f),
                normBottom.coerceIn(0f, 1f)
            )

            val centerX = (clampedBounds.left + clampedBounds.right) / 2f
            val centerY = (clampedBounds.top + clampedBounds.bottom) / 2f
            val id = face.trackingId ?: face.hashCode()

            // Compute inter-frame velocity
            val motion = motionHistory[id]
            val velocity: Float
            val isMoving: Boolean

            if (motion != null) {
                val dt = ((now - motion.timestamp).coerceAtLeast(16L)) / 1000f
                val dist = hypot(centerX - motion.x, centerY - motion.y)
                val instantSpeed = dist / dt
                val smoothedSpeed = (motion.smoothedSpeed * 0.65f) + (instantSpeed * 0.35f)

                motion.x = centerX
                motion.y = centerY
                motion.timestamp = now
                motion.smoothedSpeed = smoothedSpeed

                velocity = smoothedSpeed
                isMoving = smoothedSpeed > MOTION_SPEED_THRESHOLD
            } else {
                motionHistory[id] = MotionData(centerX, centerY, now, 0f)
                velocity = 0f
                isMoving = false
            }

            detectedList.add(
                TrackedPerson(
                    id = id,
                    bounds = clampedBounds,
                    centerX = centerX,
                    centerY = centerY,
                    velocity = velocity,
                    isMoving = isMoving,
                    lastSeenTimestamp = now
                )
            )
        }

        // Clean up stale motion entries
        motionHistory.entries.removeIf { now - it.value.timestamp > 3000L }

        _allDetectedPersons.value = detectedList

        // Select the primary target
        val primaryTarget = selectBestTarget(detectedList)
        if (primaryTarget != null) {
            _trackedPerson.value = primaryTarget
            _isPersonMoving.value = primaryTarget.isMoving

            val status = if (primaryTarget.id == selectedPersonId) {
                "AF: SELECTED HUMAN 🎯"
            } else if (primaryTarget.isMoving) {
                "AF: MOVING HUMAN 🏃"
            } else {
                "AF: HUMAN TRACKING 👤"
            }
            _afStatusText.value = status

            triggerAfOnPerson(primaryTarget, force = false)
        }
    }

    private fun selectBestTarget(persons: List<TrackedPerson>): TrackedPerson? {
        if (persons.isEmpty()) return null

        // 1. If user explicitly selected a person, prioritize them
        if (selectedPersonId != null) {
            val userChoice = persons.firstOrNull { it.id == selectedPersonId }
            if (userChoice != null) return userChoice
        }

        // 2. Prioritize MOVING humans over stationary humans
        val movingPersons = persons.filter { it.isMoving }
        if (movingPersons.isNotEmpty()) {
            // Pick moving person with highest motion priority (speed + proximity to center)
            return movingPersons.minByOrNull { person ->
                val distToCenter = hypot(person.centerX - 0.5f, person.centerY - 0.5f)
                // Balance distance to center with velocity (higher velocity gives edge)
                distToCenter - (person.velocity * 0.4f)
            }
        }

        // 3. If no moving person, prioritize person closest to the center of the frame
        return persons.minByOrNull { person ->
            val distToCenter = hypot(person.centerX - 0.5f, person.centerY - 0.5f)
            // Also factor in size (larger/closer face has slight advantage over tiny background face)
            val area = person.bounds.width() * person.bounds.height()
            distToCenter - (area * 0.3f)
        }
    }

    private fun triggerAfOnPerson(person: TrackedPerson, force: Boolean) {
        val camera = activeCamera ?: return
        val now = System.currentTimeMillis()

        // Smooth target coordinates (Exponential Moving Average)
        val alpha = if (person.isMoving) 0.55f else 0.30f
        smoothedTargetX += alpha * (person.centerX - smoothedTargetX)
        smoothedTargetY += alpha * (person.centerY - smoothedTargetY)

        val displacement = hypot(smoothedTargetX - lastTriggeredX, smoothedTargetY - lastTriggeredY)
        val elapsed = now - lastAfTriggerTime

        val shouldTrigger = force ||
                (displacement > MOVEMENT_DISPLACEMENT_THRESHOLD && elapsed > MIN_AF_TRIGGER_INTERVAL_MS) ||
                (person.isMoving && elapsed > MIN_AF_TRIGGER_INTERVAL_MS) ||
                (elapsed > PERIODIC_AF_INTERVAL_MS)

        if (!shouldTrigger) return

        lastTriggeredX = smoothedTargetX
        lastTriggeredY = smoothedTargetY
        lastAfTriggerTime = now

        try {
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            // Center metering on face / upper body
            val point = factory.createPoint(
                smoothedTargetX.coerceIn(0.05f, 0.95f),
                smoothedTargetY.coerceIn(0.05f, 0.95f)
            )

            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            )
                .setAutoCancelDuration(2000, TimeUnit.MILLISECONDS)
                .build()

            camera.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start focus and metering on human", e)
        }
    }

    private fun resetToCenterAf() {
        val camera = activeCamera ?: return
        try {
            // Cancel locked AF/AE to return to native continuous center autofocus
            camera.cameraControl.cancelFocusAndMetering()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel focus metering", e)
        }
    }

    fun destroy() {
        detachCamera()
        try {
            detector.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing face detector", e)
        }
        executor.shutdown()
    }
}
