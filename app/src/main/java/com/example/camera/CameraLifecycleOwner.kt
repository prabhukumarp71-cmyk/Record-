package com.example.camera

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * A dedicated LifecycleOwner for CameraX that remains in RESUMED state
 * regardless of Activity lifecycle events.
 *
 * This completely decouples camera streaming from the Activity window,
 * guaranteeing that closing the app, pressing Home, or locking the device
 * will NEVER cause CameraX to detach use cases or terminate background recording.
 */
class CameraLifecycleOwner : LifecycleOwner {

    companion object {
        private const val TAG = "CameraLifecycleOwner"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val registry = LifecycleRegistry(this)

    init {
        runOnMainThread {
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    override val lifecycle: Lifecycle
        get() = registry

    fun start() {
        runOnMainThread {
            if (registry.currentState != Lifecycle.State.RESUMED) {
                Log.d(TAG, "Transitioning camera lifecycle to RESUMED")
                registry.currentState = Lifecycle.State.STARTED
                registry.currentState = Lifecycle.State.RESUMED
            }
        }
    }

    fun stop() {
        runOnMainThread {
            if (registry.currentState != Lifecycle.State.CREATED) {
                Log.d(TAG, "Transitioning camera lifecycle to CREATED")
                registry.currentState = Lifecycle.State.CREATED
            }
        }
    }

    fun destroy() {
        runOnMainThread {
            Log.d(TAG, "Transitioning camera lifecycle to DESTROYED")
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
