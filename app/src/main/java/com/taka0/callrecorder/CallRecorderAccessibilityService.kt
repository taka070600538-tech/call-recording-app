package com.taka0.callrecorder

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityEvent

/**
 * Does not process accessibility events. Its purpose is twofold:
 *
 * 1. Holding an active accessibility service is what exempts the app from Android 12+'s
 *    restriction that denies microphone access to a foreground service started from a background
 *    broadcast receiver.
 * 2. Call-state detection and RecordingService start/stop now happen from *inside* this bound
 *    service rather than from a one-shot BroadcastReceiver (the previous CallStateReceiver).
 *    Empirically, on this device, a RecordingService started from a plain BroadcastReceiver
 *    (uidState RCVR, a transient process state) still receives silent microphone audio during a
 *    call even with the accessibility exemption from (1) in place. Starting it from this
 *    service's already-bound, continuously-running process (uidState BFGS) is what other working
 *    call-recording apps on this device do, and is required for microphone audio to actually be
 *    captured.
 */
class CallRecorderAccessibilityService : AccessibilityService() {

    private var phoneStateListener: PhoneStateListener? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        startListeningForCallState()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopListeningForCallState()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun startListeningForCallState() {
        val telephonyManager = getSystemService(TelephonyManager::class.java) ?: return

        @Suppress("DEPRECATION")
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java, but still functional across minSdk..targetSdk")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                val action = CallStateAction.forCallState(state) ?: return
                startForegroundService(
                    Intent(this@CallRecorderAccessibilityService, RecordingService::class.java).setAction(action)
                )
            }
        }

        @Suppress("DEPRECATION")
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        phoneStateListener = listener
    }

    private fun stopListeningForCallState() {
        val telephonyManager = getSystemService(TelephonyManager::class.java) ?: return
        @Suppress("DEPRECATION")
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        phoneStateListener = null
    }
}
