package com.example.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat

/**
 * Ensures CameraX preview stream NEVER dies or triggers ERROR_SOURCE_INACTIVE
 * when the app is closed, minimized, or the screen is turned off.
 *
 * When the UI is visible, it routes the surface request directly to PreviewView.
 * When the UI is destroyed (app closed or backgrounded), it seamlessly provides
 * a persistent headless SurfaceTexture in memory so VideoCapture continues
 * recording uninterrupted in the background.
 */
class BackgroundSafeSurfaceProvider(
    context: Context
) : Preview.SurfaceProvider {

    companion object {
        private const val TAG = "SafeSurfaceProvider"
    }

    private val mainExecutor = ContextCompat.getMainExecutor(context.applicationContext)
    private var uiProvider: Preview.SurfaceProvider? = null
    private var headlessTexture: SurfaceTexture? = null
    private var headlessSurface: Surface? = null

    @Synchronized
    fun setUiProvider(provider: Preview.SurfaceProvider?) {
        uiProvider = provider
        Log.d(TAG, "UI SurfaceProvider set: ${if (provider != null) "UI_ACTIVE" else "HEADLESS_BACKGROUND"}")
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        val currentUi = uiProvider
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
            val width = request.resolution.width
            val height = request.resolution.height

            if (headlessTexture == null) {
                headlessTexture = SurfaceTexture(0).apply {
                    setDefaultBufferSize(width, height)
                    setOnFrameAvailableListener {
                        // Consumed in background headless mode
                    }
                }
                headlessSurface = Surface(headlessTexture)
            }

            val surface = headlessSurface ?: run {
                val tex = SurfaceTexture(0).apply { setDefaultBufferSize(width, height) }
                headlessTexture = tex
                val surf = Surface(tex)
                headlessSurface = surf
                surf
            }

            request.provideSurface(surface, mainExecutor) { result ->
                Log.d(TAG, "Headless surface provided with result: ${result.resultCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to provide headless surface", e)
        }
    }

    fun release() {
        try {
            headlessSurface?.release()
            headlessTexture?.release()
        } catch (_: Exception) {}
        headlessSurface = null
        headlessTexture = null
        uiProvider = null
    }
}
