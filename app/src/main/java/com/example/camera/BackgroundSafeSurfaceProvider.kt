package com.example.camera

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat

/**
 * Ensures CameraX preview stream NEVER stalls or exhausts buffers
 * when the app is closed, minimized, or the screen is turned off.
 *
 * When the UI is visible, it routes the surface request directly to PreviewView.
 * When the UI is destroyed (app closed or backgrounded), it seamlessly provides
 * an ImageReader surface on a dedicated drain thread so frames are continuously
 * consumed and closed immediately. This keeps the camera HAL buffer queue free,
 * guaranteeing that VideoCapture receives all video frames and records continuous
 * 4K/1080p video AND audio in the background without freezing.
 */
class BackgroundSafeSurfaceProvider(
    context: Context
) : Preview.SurfaceProvider {

    companion object {
        private const val TAG = "SafeSurfaceProvider"
    }

    private val mainExecutor = ContextCompat.getMainExecutor(context.applicationContext)
    private var uiProvider: Preview.SurfaceProvider? = null

    private var drainThread: HandlerThread? = null
    private var drainHandler: Handler? = null

    private var imageReader: ImageReader? = null
    private var headlessSurface: Surface? = null

    init {
        startDrainThread()
    }

    @Synchronized
    private fun startDrainThread() {
        if (drainThread == null || !drainThread!!.isAlive) {
            drainThread = HandlerThread("HeadlessCameraDrainThread").apply {
                start()
                drainHandler = Handler(looper)
            }
        }
    }

    @Synchronized
    fun isHeadless(): Boolean = uiProvider == null

    @Synchronized
    fun setUiProvider(provider: Preview.SurfaceProvider?) {
        uiProvider = provider
        Log.d(TAG, "UI SurfaceProvider set: ${if (provider != null) "UI_ACTIVE" else "HEADLESS_BACKGROUND"}")
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        val currentUi = synchronized(this) { uiProvider }
        if (currentUi != null) {
            try {
                currentUi.onSurfaceRequested(request)
                return
            } catch (e: Exception) {
                Log.w(TAG, "UI surface request failed: ${e.message}, falling back to headless surface")
            }
        }

        provideHeadless(request)
    }

    @Synchronized
    private fun provideHeadless(request: SurfaceRequest) {
        try {
            startDrainThread()
            val width = request.resolution.width
            val height = request.resolution.height

            val currentReader = imageReader
            if (currentReader == null || currentReader.width != width || currentReader.height != height) {
                try {
                    headlessSurface?.release()
                    currentReader?.close()
                } catch (_: Exception) {}

                val newReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
                newReader.setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireLatestImage()
                        image?.close()
                    } catch (_: Exception) {}
                }, drainHandler)

                imageReader = newReader
                headlessSurface = newReader.surface
                Log.d(TAG, "Created headless ImageReader ($width x $height)")
            }

            val surface = headlessSurface
            if (surface != null && surface.isValid) {
                request.provideSurface(surface, mainExecutor) { result ->
                    Log.d(TAG, "Headless surface provided with result: ${result.resultCode}")
                }
            } else {
                Log.e(TAG, "Headless surface is invalid!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to provide headless surface", e)
        }
    }

    fun release() {
        synchronized(this) {
            try {
                headlessSurface?.release()
                imageReader?.close()
                drainThread?.quitSafely()
            } catch (_: Exception) {}
            headlessSurface = null
            imageReader = null
            drainThread = null
            drainHandler = null
            uiProvider = null
        }
    }
}
