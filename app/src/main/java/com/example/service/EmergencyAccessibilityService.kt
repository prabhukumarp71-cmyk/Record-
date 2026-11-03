package com.example.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.camera.EmergencyKeyManager

class EmergencyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "EmergencyAccessService"
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val handled = EmergencyKeyManager.onKeyEvent(this, event)
            if (handled) {
                Log.d(TAG, "Emergency recording handled by AccessibilityService")
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op
    }

    override fun onInterrupt() {
        Log.d(TAG, "EmergencyAccessibilityService interrupted")
    }
}
