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

        tracker.setHumanAfEnabled(false)
        assertEquals(false, tracker.isHumanAfEnabled.value)
        assertNull(tracker.trackedPerson.value)

        tracker.setHumanAfEnabled(true)
        assertTrue(tracker.isHumanAfEnabled.value)
    }
}
