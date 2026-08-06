package com.taka0.callrecorder

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Does not process accessibility events. Its only purpose is to give the app an active
 * accessibility service, which is what exempts it from Android 12+'s restriction that denies
 * microphone access to a foreground service started from a background broadcast receiver
 * (RecordingService, started by CallStateReceiver when a call begins). Without this, the
 * service starts successfully but MediaRecorder cannot acquire the microphone and silently
 * produces no recording.
 */
class CallRecorderAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
