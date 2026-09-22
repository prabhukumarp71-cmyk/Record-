package com.example

import android.graphics.RectF
import com.example.camera.HumanAutofocusTracker
import com.example.camera.TrackedPerson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanTrackerUnitTest {

    @Test
    fun testTrackedPersonCreationAndMotion() {
        val personStationary = TrackedPerson(
            id = 1,
            bounds = RectF(0.4f, 0.4f, 0.6f, 0.6f),
            centerX = 0.5f,
            centerY = 0.5f,
            velocity = 0.01f,
            isMoving = false,
            lastSeenTimestamp = System.currentTimeMillis()
        )

        val personMoving = TrackedPerson(
            id = 2,
            bounds = RectF(0.1f, 0.1f, 0.3f, 0.3f),
            centerX = 0.2f,
            centerY = 0.2f,
            velocity = 0.09f,
            isMoving = true,
            lastSeenTimestamp = System.currentTimeMillis()
        )

        assertEquals(1, personStationary.id)
        assertEquals(false, personStationary.isMoving)
        assertEquals(true, personMoving.isMoving)
        assertTrue(personMoving.velocity > personStationary.velocity)
    }

    @Test
    fun testHumanAfToggle() {
        val tracker = HumanAutofocusTracker()
        assertTrue(tracker.isHumanAfEnabled.value)
        assertEquals(com.example.camera.AutoFocusMode.MOTION_TRACKING, tracker.afMode.value)

        tracker.setHumanAfEnabled(false)
        assertEquals(false, tracker.isHumanAfEnabled.value)
        assertEquals(com.example.camera.AutoFocusMode.STANDARD_AUTO, tracker.afMode.value)
        assertNull(tracker.trackedPerson.value)

        tracker.setHumanAfEnabled(true)
        assertTrue(tracker.isHumanAfEnabled.value)
        assertEquals(com.example.camera.AutoFocusMode.HUMAN_PRIORITY, tracker.afMode.value)

        // Test cycle
        tracker.cycleAfMode()
        assertEquals(com.example.camera.AutoFocusMode.MOTION_TRACKING, tracker.afMode.value)

        tracker.cycleAfMode()
        assertEquals(com.example.camera.AutoFocusMode.STANDARD_AUTO, tracker.afMode.value)

        tracker.cycleAfMode()
        assertEquals(com.example.camera.AutoFocusMode.HUMAN_PRIORITY, tracker.afMode.value)
    }

    @Test
    fun testMotionTarget() {
        val motion = com.example.camera.MotionTarget(
            bounds = RectF(0.2f, 0.2f, 0.4f, 0.4f),
            centerX = 0.3f,
            centerY = 0.3f,
            intensity = 0.8f,
            timestamp = System.currentTimeMillis()
        )
        assertEquals(0.3f, motion.centerX, 0.001f)
        assertEquals(0.8f, motion.intensity, 0.001f)
    }
}
