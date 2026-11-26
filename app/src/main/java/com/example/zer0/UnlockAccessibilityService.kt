package com.example.zer0

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class UnlockAccessibilityService : AccessibilityService() {

    companion object {
        var instance: UnlockAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("VoiceUnlock", "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // අවශ්‍ය නම් window events handle කරන්න පුළුවන්
    }

    override fun onInterrupt() {
        Log.d("VoiceUnlock", "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun swipeUpToUnlock() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        val startX = width / 2f
        val startY = height * 0.80f
        val endX = width / 2f
        val endY = height * 0.25f

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        dispatchGesture(gesture, null, null)
    }
}