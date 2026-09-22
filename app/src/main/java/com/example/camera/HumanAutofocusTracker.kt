package com.example.camera

import android.graphics.RectF
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot

enum class AutoFocusMode {
    MOTION_TRACKING, // Automatically tracks and focuses on ANY movement in the frame immediately
    HUMAN_PRIORITY,  // Prioritizes moving humans, then stationary humans, then center
    STANDARD_AUTO    // Native center continuous autofocus
}

data class TrackedPerson(
    val id: Int,
    val bounds: RectF, // Normalized [0, 1] relative to preview frame
    val centerX: Float,
    val centerY: Float,
    val velocity: Float, // Normalized screen distance / second
    val isMoving: Boolean,
    val lastSeenTimestamp: Long
)

data class MotionTarget(
    val bounds: RectF,
    val centerX: Float,
    val centerY: Float,
    val intensity: Float,
    val timestamp: Long
)

class HumanAutofocusTracker {

    companion object {
        private const val TAG = "HumanAutofocusTracker"
        private const val MOTION_SPEED_THRESHOLD = 0.020f // 2.0% screen distance / sec
        private const val MOTION_PERSISTENCE_MS = 1600L   // Keep marked as moving for 1.6s
        private const val HUMAN_LOST_TIMEOUT_MS = 1200L
        private const val MIN_AF_TRIGGER_INTERVAL_MS = 380L // Faster re-focus on movement (380ms)
        private const val PERIODIC_AF_INTERVAL_MS = 1400L
        private const val MOVEMENT_DISPLACEMENT_THRESHOLD = 0.028f // 2.8% screen displacement
        private const val GRID_SIZE = 16
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
    private var activeImageAnalysis: ImageAnalysis? = null
    private var customMeteringPointFactory: MeteringPointFactory? = null

    // Tracking mode: default to MOTION_TRACKING so movement automatically gets focused
    private val _afMode = MutableStateFlow(AutoFocusMode.MOTION_TRACKING)
    val afMode: StateFlow<AutoFocusMode> = _afMode

    // Legacy boolean for compatibility
    private val _isHumanAfEnabled = MutableStateFlow(true)
    val isHumanAfEnabled: StateFlow<Boolean> = _isHumanAfEnabled

    private val _trackedPerson = MutableStateFlow<TrackedPerson?>(null)
    val trackedPerson: StateFlow<TrackedPerson?> = _trackedPerson

    private val _allDetectedPersons = MutableStateFlow<List<TrackedPerson>>(emptyList())
    val allDetectedPersons: StateFlow<List<TrackedPerson>> = _allDetectedPersons

    private val _trackedMotionTarget = MutableStateFlow<MotionTarget?>(null)
    val trackedMotionTarget: StateFlow<MotionTarget?> = _trackedMotionTarget

    private val _isPersonMoving = MutableStateFlow(false)
    val isPersonMoving: StateFlow<Boolean> = _isPersonMoving

    private val _isMotionActive = MutableStateFlow(false)
    val isMotionActive: StateFlow<Boolean> = _isMotionActive

    private val _afStatusText = MutableStateFlow("AF: MOTION TRACKING 🏃")
    val afStatusText: StateFlow<String> = _afStatusText

    // Explicit user selected person ID
    private var selectedPersonId: Int? = null

    // Internal track persistence for continuous tracking across frames
    private data class InternalTrack(
        val id: Int,
        var lastBounds: RectF,
        var lastX: Float,
        var lastY: Float,
        var smoothedVelocity: Float,
        var lastSeenTimestamp: Long,
        var lastMotionTimestamp: Long
    )
    private val activeTracks = mutableListOf<InternalTrack>()
    private var nextTrackId = 1

    // Luminance frame-difference grid for real-time motion detection
    private var previousLumaGrid: ByteArray? = null
    private var lastMotionTimestamp = 0L

    // Coordinate smoothing
    private var smoothedTargetX = 0.5f
    private var smoothedTargetY = 0.5f
    private var lastTriggeredX = 0.5f
    private var lastTriggeredY = 0.5f
    private var lastAfTriggerTime = 0L
    private var lastPersonSeenTime = 0L
    private var isFocusOperationActive = false

    fun setMeteringPointFactory(factory: MeteringPointFactory?) {
        this.customMeteringPointFactory = factory
    }

    fun setAfMode(mode: AutoFocusMode) {
        _afMode.value = mode
        _isHumanAfEnabled.value = (mode != AutoFocusMode.STANDARD_AUTO)
        when (mode) {
            AutoFocusMode.HUMAN_PRIORITY -> {
                _afStatusText.value = "AF: HUMAN PRIORITY"
            }
            AutoFocusMode.MOTION_TRACKING -> {
                _afStatusText.value = "AF: MOTION TRACKING"
            }
            AutoFocusMode.STANDARD_AUTO -> {
                _trackedPerson.value = null
                _trackedMotionTarget.value = null
                _allDetectedPersons.value = emptyList()
                _isPersonMoving.value = false
                _isMotionActive.value = false
                _afStatusText.value = "AF: STANDARD AUTO"
                resetToCenterAf()
            }
        }
    }

    fun cycleAfMode() {
        val next = when (_afMode.value) {
            AutoFocusMode.HUMAN_PRIORITY -> AutoFocusMode.MOTION_TRACKING
            AutoFocusMode.MOTION_TRACKING -> AutoFocusMode.STANDARD_AUTO
            AutoFocusMode.STANDARD_AUTO -> AutoFocusMode.HUMAN_PRIORITY
        }
        setAfMode(next)
    }

    fun setHumanAfEnabled(enabled: Boolean) {
        if (enabled) {
            setAfMode(AutoFocusMode.HUMAN_PRIORITY)
        } else {
            setAfMode(AutoFocusMode.STANDARD_AUTO)
        }
    }

    fun toggleHumanAf() {
        cycleAfMode()
    }

    fun attachCamera(camera: Camera?, imageAnalysis: ImageAnalysis? = null) {
        this.activeCamera = camera
        this.activeImageAnalysis = imageAnalysis
        activeTracks.clear()
        previousLumaGrid = null
        selectedPersonId = null
        lastPersonSeenTime = 0L
        lastAfTriggerTime = 0L
        isFocusOperationActive = false
    }

    fun detachCamera() {
        this.activeCamera = null
        this.activeImageAnalysis = null
        _trackedPerson.value = null
        _trackedMotionTarget.value = null
        _allDetectedPersons.value = emptyList()
        _isPersonMoving.value = false
        _isMotionActive.value = false
        activeTracks.clear()
        previousLumaGrid = null
    }

    fun selectPersonAt(tapX: Float, tapY: Float): Boolean {
        if (_afMode.value == AutoFocusMode.STANDARD_AUTO) return false
        val currentList = _allDetectedPersons.value
        for (person in currentList) {
            val margin = 0.08f
            val hit = tapX >= (person.bounds.left - margin) &&
                    tapX <= (person.bounds.right + margin) &&
                    tapY >= (person.bounds.top - margin) &&
                    tapY <= (person.bounds.bottom + margin)
            if (hit) {
                selectedPersonId = person.id
                _trackedPerson.value = person
                triggerAf(person.centerX, person.centerY, force = true)
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
        if (mediaImage == null || _afMode.value == AutoFocusMode.STANDARD_AUTO) {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val effectiveWidth = if (isRotated) imageProxy.height.toFloat() else imageProxy.width.toFloat()
        val effectiveHeight = if (isRotated) imageProxy.width.toFloat() else imageProxy.height.toFloat()

        // 1. Detect Real-time Optical/Luminance Motion from ImageProxy Y-plane
        val motionTarget = detectFrameMotion(imageProxy, rotationDegrees, isFrontCamera)

        // 2. Detect Faces via ML Kit
        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                handleDetectedFaces(faces, effectiveWidth, effectiveHeight, isFrontCamera, motionTarget)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Face detection failure", e)
                // If face detection fails or no faces, still process motion target
                handleMotionFallback(motionTarget)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /**
     * Samples a 16x16 grid of luminance values from the Y-plane to compute
     * frame-to-frame motion vector, centroid, and magnitude in < 0.2ms.
     */
    private fun detectFrameMotion(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        isFrontCamera: Boolean
    ): MotionTarget? {
        val planes = imageProxy.planes
        if (planes.isEmpty()) return null

        val yPlane = planes[0]
        val buffer: ByteBuffer = yPlane.buffer
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride

        val currentGrid = ByteArray(GRID_SIZE * GRID_SIZE)
        val stepX = width / GRID_SIZE
        val stepY = height / GRID_SIZE

        for (r in 0 until GRID_SIZE) {
            val y = (r * stepY).coerceIn(0, height - 1)
            for (c in 0 until GRID_SIZE) {
                val x = (c * stepX).coerceIn(0, width - 1)
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    currentGrid[r * GRID_SIZE + c] = buffer.get(index)
                }
            }
        }

        val prevGrid = previousLumaGrid
        previousLumaGrid = currentGrid
        if (prevGrid == null) return null

        var motionCellsCount = 0
        var sumGridX = 0f
        var sumGridY = 0f
        var minGridX = 1f
        var maxGridX = 0f
        var minGridY = 1f
        var maxGridY = 0f

        for (r in 0 until GRID_SIZE) {
            for (c in 0 until GRID_SIZE) {
                val currVal = currentGrid[r * GRID_SIZE + c].toInt() and 0xFF
                val prevVal = prevGrid[r * GRID_SIZE + c].toInt() and 0xFF
                val diff = abs(currVal - prevVal)

                // Responsive luminance difference threshold (22 for rapid detection)
                if (diff > 22) {
                    motionCellsCount++
                    val normX = (c + 0.5f) / GRID_SIZE
                    val normY = (r + 0.5f) / GRID_SIZE

                    sumGridX += normX
                    sumGridY += normY
                    minGridX = minOf(minGridX, normX)
                    maxGridX = maxOf(maxGridX, normX)
                    minGridY = minOf(minGridY, normY)
                    maxGridY = maxOf(maxGridY, normY)
                }
            }
        }

        // Require at least 2 cells with motion to reject isolated sensor noise
        if (motionCellsCount < 2) {
            return null
        }

        val rawCenterX = sumGridX / motionCellsCount
        val rawCenterY = sumGridY / motionCellsCount

        // Map sensor coordinates to preview orientation
        var transformedX: Float
        var transformedY: Float

        when (rotationDegrees) {
            90 -> {
                transformedX = 1f - rawCenterY
                transformedY = rawCenterX
            }
            180 -> {
                transformedX = 1f - rawCenterX
                transformedY = 1f - rawCenterY
            }
            270 -> {
                transformedX = rawCenterY
                transformedY = 1f - rawCenterX
            }
            else -> {
                transformedX = rawCenterX
                transformedY = rawCenterY
            }
        }

        if (isFrontCamera) {
            transformedX = 1f - transformedX
        }

        val now = System.currentTimeMillis()
        val intensity = (motionCellsCount / (GRID_SIZE * GRID_SIZE.toFloat())).coerceIn(0.1f, 1f)
        val boxRadius = (0.12f + (intensity * 0.15f)).coerceIn(0.1f, 0.4f)

        val bounds = RectF(
            (transformedX - boxRadius).coerceIn(0f, 1f),
            (transformedY - boxRadius).coerceIn(0f, 1f),
            (transformedX + boxRadius).coerceIn(0f, 1f),
            (transformedY + boxRadius).coerceIn(0f, 1f)
        )

        return MotionTarget(
            bounds = bounds,
            centerX = transformedX.coerceIn(0f, 1f),
            centerY = transformedY.coerceIn(0f, 1f),
            intensity = intensity,
            timestamp = now
        )
    }

    private fun handleDetectedFaces(
        faces: List<Face>,
        effectiveWidth: Float,
        effectiveHeight: Float,
        isFrontCamera: Boolean,
        motionTarget: MotionTarget?
    ) {
        val now = System.currentTimeMillis()

        if (motionTarget != null) {
            lastMotionTimestamp = now
            _isMotionActive.value = true
            _trackedMotionTarget.value = motionTarget
        } else if (now - lastMotionTimestamp > MOTION_PERSISTENCE_MS) {
            _isMotionActive.value = false
            _trackedMotionTarget.value = null
        }

        if (faces.isEmpty()) {
            _allDetectedPersons.value = emptyList()
            handleMotionFallback(motionTarget)
            return
        }

        lastPersonSeenTime = now

        // Multi-frame centroid-based spatial tracking
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

            // Match to existing active track by proximity or ML Kit trackingId
            val existingTrack = matchOrCreateTrack(face.trackingId, centerX, centerY, clampedBounds, now)

            val dt = ((now - existingTrack.lastSeenTimestamp).coerceAtLeast(16L)) / 1000f
            val dist = hypot(centerX - existingTrack.lastX, centerY - existingTrack.lastY)
            val instantSpeed = dist / dt
            val smoothedSpeed = (existingTrack.smoothedVelocity * 0.6f) + (instantSpeed * 0.4f)

            existingTrack.lastX = centerX
            existingTrack.lastY = centerY
            existingTrack.lastBounds = clampedBounds
            existingTrack.smoothedVelocity = smoothedSpeed
            existingTrack.lastSeenTimestamp = now

            // Check if this person moved by displacement OR overlaps with active frame motion
            val overlapsWithMotion = motionTarget != null && RectF.intersects(
                RectF(
                    clampedBounds.left - 0.08f,
                    clampedBounds.top - 0.08f,
                    clampedBounds.right + 0.08f,
                    clampedBounds.bottom + 0.08f
                ),
                motionTarget.bounds
            )

            val isMovingNow = (smoothedSpeed > MOTION_SPEED_THRESHOLD) || overlapsWithMotion
            if (isMovingNow) {
                existingTrack.lastMotionTimestamp = now
            }

            // Maintain motion flag for hysteresis persistence window
            val isMoving = isMovingNow || (now - existingTrack.lastMotionTimestamp < MOTION_PERSISTENCE_MS)

            detectedList.add(
                TrackedPerson(
                    id = existingTrack.id,
                    bounds = clampedBounds,
                    centerX = centerX,
                    centerY = centerY,
                    velocity = smoothedSpeed,
                    isMoving = isMoving,
                    lastSeenTimestamp = now
                )
            )
        }

        // Remove stale tracks (not seen for 2.5 seconds)
        activeTracks.removeAll { now - it.lastSeenTimestamp > 2500L }

        _allDetectedPersons.value = detectedList

        // Mode: MOTION_TRACKING: If motion target exists, give it focus
        if (_afMode.value == AutoFocusMode.MOTION_TRACKING && motionTarget != null) {
            val movingPerson = detectedList.firstOrNull { it.isMoving }
            if (movingPerson != null) {
                _trackedPerson.value = movingPerson
                _isPersonMoving.value = true
                _afStatusText.value = "AF: MOVING HUMAN 🏃"
                triggerAf(movingPerson.centerX, movingPerson.centerY, force = false)
            } else {
                _trackedPerson.value = null
                _isPersonMoving.value = false
                _afStatusText.value = "AF: MOTION TRACKING 🏃"
                triggerAf(motionTarget.centerX, motionTarget.centerY, force = false)
            }
            return
        }

        // Mode: HUMAN_PRIORITY: Select the best human target
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

            triggerAf(primaryTarget.centerX, primaryTarget.centerY, force = false)
        } else {
            handleMotionFallback(motionTarget)
        }
    }

    private fun handleMotionFallback(motionTarget: MotionTarget?) {
        val now = System.currentTimeMillis()

        // If motion target is active and either in MOTION_TRACKING mode or HUMAN_PRIORITY without a face
        if (motionTarget != null && (_afMode.value == AutoFocusMode.MOTION_TRACKING || _afMode.value == AutoFocusMode.HUMAN_PRIORITY)) {
            _trackedMotionTarget.value = motionTarget
            _isMotionActive.value = true
            _isPersonMoving.value = false
            _afStatusText.value = "AF: MOTION DETECTED ⚡"
            triggerAf(motionTarget.centerX, motionTarget.centerY, force = false)
            return
        }

        // Fallback to center autofocus when nothing is detected
        if (_trackedPerson.value != null && (now - lastPersonSeenTime) > HUMAN_LOST_TIMEOUT_MS) {
            Log.d(TAG, "No subject detected for ${HUMAN_LOST_TIMEOUT_MS}ms. Returning to continuous center AF.")
            _trackedPerson.value = null
            _trackedMotionTarget.value = null
            _isPersonMoving.value = false
            _isMotionActive.value = false
            selectedPersonId = null
            _afStatusText.value = "AF: AUTO (CENTER)"
            resetToCenterAf()
        }
    }

    private fun matchOrCreateTrack(
        mlKitId: Int?,
        x: Float,
        y: Float,
        bounds: RectF,
        now: Long
    ): InternalTrack {
        // First try to match by trackingId if present and valid
        if (mlKitId != null && mlKitId >= 0) {
            val byId = activeTracks.firstOrNull { it.id == mlKitId }
            if (byId != null) return byId
        }

        // Otherwise match to closest active track within 0.32 screen distance
        val closest = activeTracks.minByOrNull { hypot(it.lastX - x, it.lastY - y) }
        if (closest != null && hypot(closest.lastX - x, closest.lastY - y) < 0.32f) {
            return closest
        }

        // Create new track
        val assignedId = mlKitId ?: (nextTrackId++)
        val newTrack = InternalTrack(
            id = assignedId,
            lastBounds = bounds,
            lastX = x,
            lastY = y,
            smoothedVelocity = 0f,
            lastSeenTimestamp = now,
            lastMotionTimestamp = 0L
        )
        activeTracks.add(newTrack)
        return newTrack
    }

    private fun selectBestTarget(persons: List<TrackedPerson>): TrackedPerson? {
        if (persons.isEmpty()) return null

        // 1. Explicit user selection
        if (selectedPersonId != null) {
            val userChoice = persons.firstOrNull { it.id == selectedPersonId }
            if (userChoice != null) return userChoice
        }

        // 2. Moving humans get top priority over stationary humans
        val movingPersons = persons.filter { it.isMoving }
        if (movingPersons.isNotEmpty()) {
            return movingPersons.minByOrNull { person ->
                val distToCenter = hypot(person.centerX - 0.5f, person.centerY - 0.5f)
                distToCenter - (person.velocity * 0.4f)
            }
        }

        // 3. Proximity to center, weighted slightly by face size
        return persons.minByOrNull { person ->
            val distToCenter = hypot(person.centerX - 0.5f, person.centerY - 0.5f)
            val area = person.bounds.width() * person.bounds.height()
            distToCenter - (area * 0.3f)
        }
    }

    private fun triggerAf(targetX: Float, targetY: Float, force: Boolean) {
        val camera = activeCamera ?: return
        val now = System.currentTimeMillis()

        val isMoving = _isPersonMoving.value || _isMotionActive.value
        val alpha = if (isMoving) 0.55f else 0.30f

        smoothedTargetX += alpha * (targetX - smoothedTargetX)
        smoothedTargetY += alpha * (targetY - smoothedTargetY)

        val displacement = hypot(smoothedTargetX - lastTriggeredX, smoothedTargetY - lastTriggeredY)
        val elapsed = now - lastAfTriggerTime

        // Do not interrupt an active AF sweep if it started very recently (< 450ms when moving, 650ms otherwise)
        val minSweepDuration = if (isMoving) 450L else 650L
        if (isFocusOperationActive && elapsed < minSweepDuration && !force) {
            return
        }

        val shouldTrigger = force ||
                (displacement > MOVEMENT_DISPLACEMENT_THRESHOLD && elapsed > MIN_AF_TRIGGER_INTERVAL_MS) ||
                (isMoving && elapsed > MIN_AF_TRIGGER_INTERVAL_MS) ||
                (elapsed > PERIODIC_AF_INTERVAL_MS)

        if (!shouldTrigger) return

        lastTriggeredX = smoothedTargetX
        lastTriggeredY = smoothedTargetY
        lastAfTriggerTime = now
        isFocusOperationActive = true

        try {
            val clampedX = smoothedTargetX.coerceIn(0.05f, 0.95f)
            val clampedY = smoothedTargetY.coerceIn(0.05f, 0.95f)

            val point: MeteringPoint = customMeteringPointFactory?.createPoint(clampedX, clampedY)
                ?: run {
                    val fallbackFactory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    fallbackFactory.createPoint(clampedX, clampedY)
                }

            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            )
                .setAutoCancelDuration(2200, TimeUnit.MILLISECONDS)
                .build()

            val future = camera.cameraControl.startFocusAndMetering(action)
            future.addListener({
                isFocusOperationActive = false
            }, executor)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start focus and metering", e)
            isFocusOperationActive = false
        }
    }

    private fun resetToCenterAf() {
        val camera = activeCamera ?: return
        try {
            isFocusOperationActive = false
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
