package com.example.ui.screens

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.camera.RecordingManager
import com.example.settings.AppSettings

@Composable
fun CameraPreviewView(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val qualityName by AppSettings.getVideoQuality(context).collectAsState(initial = "HIGH")
    var previewViewInstance by remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(qualityName, lifecycleOwner, previewViewInstance) {
        val pv = previewViewInstance ?: return@LaunchedEffect
        val targetQuality = RecordingManager.parseQuality(qualityName)
        RecordingManager.bindCamera(
            context = context,
            lifecycleOwner = lifecycleOwner,
            quality = targetQuality,
            surfaceProvider = pv.surfaceProvider
        )
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                previewViewInstance = this
                val targetQuality = RecordingManager.parseQuality(qualityName)
                RecordingManager.bindCamera(
                    context = ctx,
                    lifecycleOwner = lifecycleOwner,
                    quality = targetQuality,
                    surfaceProvider = this.surfaceProvider
                )
            }
        },
        update = { view ->
            if (previewViewInstance != view) {
                previewViewInstance = view
            }
        }
    )
}
