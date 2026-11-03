package com.example.camera

import android.graphics.RectF

data class TrackedPerson(
    val id: Int,
    val boundingBox: RectF,
    val isSelected: Boolean = false,
    val centerX: Float = boundingBox.centerX(),
    val centerY: Float = boundingBox.centerY()
)
