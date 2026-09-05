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
  }

  @Test
  fun testVideoFormatTypeBadge() {
    assertEquals("9:16", VideoFormatType.VERTICAL.ratio)
    assertEquals("16:9", VideoFormatType.HORIZONTAL.ratio)
  }
}
