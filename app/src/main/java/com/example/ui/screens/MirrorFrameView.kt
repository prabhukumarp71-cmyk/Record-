package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Choreographer
import android.view.TextureView
import android.view.View

class MirrorFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var sourceTextureView: TextureView? = null
    var cropPosition: Float = 0.5f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()
    private var sharedBitmap: Bitmap? = null
    private var isLoopRunning = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isLoopRunning || !isAttachedToWindow) return

            val texture = sourceTextureView
            if (texture != null && texture.isAvailable) {
                val targetW = 480
                val targetH = (targetW * 16) / 9
                if (sharedBitmap == null || sharedBitmap?.width != targetW || sharedBitmap?.height != targetH) {
                    try {
                        sharedBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    } catch (_: Exception) {}
                }
                sharedBitmap?.let { bmp ->
                    try {
                        texture.getBitmap(bmp)
                        invalidate()
                    } catch (_: Exception) {}
                }
            }

            if (isLoopRunning && isAttachedToWindow) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun startMirroring(textureView: TextureView) {
        sourceTextureView = textureView
        if (!isLoopRunning) {
            isLoopRunning = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    fun stopMirroring() {
        isLoopRunning = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopMirroring()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = sharedBitmap
        if (bmp == null || width == 0 || height == 0) {
            canvas.drawColor(Color.BLACK)
            return
        }

        val cropH = (bmp.width * 9) / 16
        val maxTop = (bmp.height - cropH).coerceAtLeast(0)
        val top = (maxTop * cropPosition).toInt().coerceIn(0, maxTop)

        srcRect.set(0, top, bmp.width, top + cropH)
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
    }
}
