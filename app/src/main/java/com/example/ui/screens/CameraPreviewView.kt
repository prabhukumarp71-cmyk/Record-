package com.example.ui.screens

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.camera.RecordingManager
import com.example.model.RecordingState

@Composable
fun CameraPreviewView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val recordingState by RecordingManager.recordingState.collectAsState()
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(recordingState, lifecycleOwner) {
        if (recordingState == RecordingState.IDLE) {
            RecordingManager.bindCamera(
                context = context,
                lifecycleOwner = lifecycleOwner
            )
        }
    }

    LaunchedEffect(previewView) {
        previewView?.let {
            RecordingManager.setSurfaceProvider(it.surfaceProvider)
        }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                this.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                previewView = this
                RecordingManager.setSurfaceProvider(this.surfaceProvider)
            }
        },
        modifier = modifier
    )
}
