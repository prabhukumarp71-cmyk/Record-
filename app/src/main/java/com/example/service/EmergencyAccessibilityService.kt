package com.example.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.camera.EmergencyKeyManager

class EmergencyAccessibilityService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                val handled = EmergencyKeyManager.onVolumeKeyPressed(this, event.keyCode)
                if (handled) {
                    Log.d(TAG, "Emergency recording triggered from background AccessibilityService via volume hardware key combination")
                    return true
                }
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No UI accessibility events needed; dedicated solely to hardware key event filtering
    }

    override fun onInterrupt() {
        Log.d(TAG, "EmergencyAccessibilityService interrupted")
    }

    companion object {
        private const val TAG = "EmergencyAccessService"
    }
}
