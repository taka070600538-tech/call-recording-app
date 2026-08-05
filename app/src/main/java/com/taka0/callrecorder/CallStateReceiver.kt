package com.taka0.callrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val action = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> RecordingService.ACTION_START
            TelephonyManager.EXTRA_STATE_IDLE -> RecordingService.ACTION_STOP
            else -> return
        }

        val serviceIntent = Intent(context, RecordingService::class.java).setAction(action)
        context.startForegroundService(serviceIntent)
    }
}
