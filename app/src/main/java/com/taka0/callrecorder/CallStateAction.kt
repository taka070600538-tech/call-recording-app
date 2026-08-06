package com.taka0.callrecorder

import android.telephony.TelephonyManager

object CallStateAction {
    fun forCallState(state: Int): String? {
        return when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> RecordingService.ACTION_START
            TelephonyManager.CALL_STATE_IDLE -> RecordingService.ACTION_STOP
            else -> null
        }
    }
}
