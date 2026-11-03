package com.example.camera

import android.graphics.RectF

data class MotionTarget(
    val bounds: RectF,
    val centerX: Float,
    val centerY: Float,
    val intensity: Float,
    val isNear: Boolean = false,
    val timestamp: Long
)
