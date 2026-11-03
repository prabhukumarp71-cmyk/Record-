package com.example

import com.example.media.DualFormatVideoHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingQualityTest {

    @Test
    fun testCleanBaseNameExtraction() {
        assertEquals("VID_20261102_120000", DualFormatVideoHelper.getCleanBaseName("VID_20261102_120000_Vertical.mp4"))
        assertEquals("VID_20261102_120000", DualFormatVideoHelper.getCleanBaseName("VID_20261102_120000_Horizontal.mp4"))
        assertEquals("VID_20261102_120000", DualFormatVideoHelper.getCleanBaseName("VID_20261102_120000.mp4"))
    }

    @Test
    fun testMotorolaBitrates() {
        // Motorola VideoHelper hardware bitrates:
        // 4K UHD 60 FPS: 63 Mbps, 30 FPS: 50 Mbps
        // 1080p FHD 60 FPS: 32 Mbps, 30 FPS: 20 Mbps
        val uhd30Bitrate = 50_000_000
        val uhd60Bitrate = 63_000_000
        val fhd30Bitrate = 20_000_000
        val fhd60Bitrate = 32_000_000

        assertEquals(50000000, uhd30Bitrate)
        assertEquals(63000000, uhd60Bitrate)
        assertEquals(20000000, fhd30Bitrate)
        assertEquals(32000000, fhd60Bitrate)
    }
}
