package com.example

import com.example.camera.RecordingManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FarExposureUnitTest {

    @Before
    fun setUp() {
        RecordingManager.resetExposure()
        RecordingManager.setFarFocusOnly(false)
    }

    @Test
    fun testFarFocusOnlyToggle() {
        assertFalse(RecordingManager.isFarFocusOnly.value)
        RecordingManager.setFarFocusOnly(true)
        assertTrue(RecordingManager.isFarFocusOnly.value)
        RecordingManager.toggleFarFocusOnly()
        assertFalse(RecordingManager.isFarFocusOnly.value)
    }

    @Test
    fun testManualExposureCompensationInFarMode() {
        RecordingManager.setFarFocusOnly(true)
        assertTrue(RecordingManager.isFarFocusOnly.value)

        // Switch to manual exposure
        RecordingManager.setExposureMode(1)
        assertEquals(1, RecordingManager.exposureMode.value)

        // Set exposure compensation to +0.6f
        RecordingManager.setExposureCompensation(0.6f)
        assertEquals(0.6f, RecordingManager.exposureCompensation.value, 0.001f)
        assertEquals(1, RecordingManager.exposureMode.value)

        // Set exposure compensation to -0.8f
        RecordingManager.setExposureCompensation(-0.8f)
        assertEquals(-0.8f, RecordingManager.exposureCompensation.value, 0.001f)

        // Coerce checks
        RecordingManager.setExposureCompensation(1.5f)
        assertEquals(1.0f, RecordingManager.exposureCompensation.value, 0.001f)

        RecordingManager.setExposureCompensation(-1.5f)
        assertEquals(-1.0f, RecordingManager.exposureCompensation.value, 0.001f)
    }

    @Test
    fun testAeLockInFarMode() {
        RecordingManager.setFarFocusOnly(true)
        assertFalse(RecordingManager.isAeLocked.value)

        RecordingManager.toggleAeLock()
        assertTrue(RecordingManager.isAeLocked.value)

        RecordingManager.toggleAeLock()
        assertFalse(RecordingManager.isAeLocked.value)

        RecordingManager.setAeLock(true)
        assertTrue(RecordingManager.isAeLocked.value)

        // Reset clears AE lock and EV compensation
        RecordingManager.resetExposure()
        assertFalse(RecordingManager.isAeLocked.value)
        assertEquals(0f, RecordingManager.exposureCompensation.value, 0.001f)
        assertEquals(0, RecordingManager.exposureMode.value)
    }

    @Test
    fun testFarExposurePresets() {
        RecordingManager.setFarFocusOnly(true)

        // Sky preset (-0.5f)
        RecordingManager.setExposurePreset(-0.5f)
        assertEquals(-0.5f, RecordingManager.exposureCompensation.value, 0.001f)
        assertEquals(1, RecordingManager.exposureMode.value)

        // Shadows preset (+0.5f)
        RecordingManager.setExposurePreset(0.5f)
        assertEquals(0.5f, RecordingManager.exposureCompensation.value, 0.001f)

        // Night boost preset (+1.0f)
        RecordingManager.setExposurePreset(1.0f)
        assertEquals(1.0f, RecordingManager.exposureCompensation.value, 0.001f)

        // Reset preset (0.0f)
        RecordingManager.setExposurePreset(0.0f)
        assertEquals(0.0f, RecordingManager.exposureCompensation.value, 0.001f)
    }

    @Test
    fun testTapToMeterInFarMode() {
        RecordingManager.setFarFocusOnly(true)
        assertNull(RecordingManager.tapMeterPoint.value)

        // Tap on distant sky/subject at (0.35f, 0.25f)
        RecordingManager.focusAtPoint(0.35f, 0.25f)

        // Verifies coordinates registered for spot metering visual feedback
        val tapPoint = RecordingManager.tapMeterPoint.value
        assertNotNull(tapPoint)
        assertEquals(0.35f, tapPoint!!.first, 0.001f)
        assertEquals(0.25f, tapPoint.second, 0.001f)

        // Verify far focus lock was NOT cancelled by tapping
        assertTrue(RecordingManager.isFarFocusOnly.value)

        // Clear tap meter point
        RecordingManager.clearTapMeterPoint()
        assertNull(RecordingManager.tapMeterPoint.value)
    }
}
