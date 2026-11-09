package com.example

import com.example.media.DualFormatVideoHelper
import com.example.media.VideoFormatType
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testCleanBaseNameExtraction() {
    val clean1 = DualFormatVideoHelper.getCleanBaseName("VID_20260905_120000_Vertical.mp4")
    assertEquals("VID_20260905_120000", clean1)

    val clean2 = DualFormatVideoHelper.getCleanBaseName("VID_20260905_120000_Horizontal.mp4")
    assertEquals("VID_20260905_120000", clean2)

    val clean3 = DualFormatVideoHelper.getCleanBaseName("VID_20260905_120000_9x16.mp4")
    assertEquals("VID_20260905_120000", clean3)

    val clean4 = DualFormatVideoHelper.getCleanBaseName("VID_20260905_120000_1x1.mp4")
    assertEquals("VID_20260905_120000", clean4)
  }

  @Test
  fun testVideoFormatTypeBadge() {
    assertEquals("9:16", VideoFormatType.VERTICAL.ratio)
    assertEquals("16:9", VideoFormatType.HORIZONTAL.ratio)
    assertEquals("1:1", VideoFormatType.SQUARE.ratio)
    assertEquals("4:3", VideoFormatType.RATIO_4_3.ratio)
  }

  @Test
  fun testFarFocusPresets() {
    val presets = com.example.camera.FarFocusPreset.values()
    assertTrue(presets.isNotEmpty())
    val near15 = presets.first { it == com.example.camera.FarFocusPreset.NEAR_15CM }
    assertEquals(0.15f, near15.distanceMeters ?: 0f, 0.001f)
    val auto = presets.first { it == com.example.camera.FarFocusPreset.AUTO_FAR }
    assertNull(auto.distanceMeters)
  }
}
