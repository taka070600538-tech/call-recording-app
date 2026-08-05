package com.taka0.callrecorder

import android.content.Intent
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallStateReceiverTest {

    @Test
    fun `OFFHOOK starts the recording service with ACTION_START`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowApp = shadowOf(context as android.app.Application)
        val receiver = CallStateReceiver()

        val intent = Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            .putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK)
        receiver.onReceive(context, intent)

        val started = shadowApp.nextStartedService
        assertEquals(RecordingService.ACTION_START, started.action)
        assertEquals(RecordingService::class.java.name, started.component!!.className)
    }

    @Test
    fun `IDLE starts the recording service with ACTION_STOP`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowApp = shadowOf(context as android.app.Application)
        val receiver = CallStateReceiver()

        val intent = Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            .putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_IDLE)
        receiver.onReceive(context, intent)

        assertEquals(RecordingService.ACTION_STOP, shadowApp.nextStartedService.action)
    }

    @Test
    fun `RINGING does not start any service`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowApp = shadowOf(context as android.app.Application)
        val receiver = CallStateReceiver()

        val intent = Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            .putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_RINGING)
        receiver.onReceive(context, intent)

        assertNull(shadowApp.nextStartedService)
    }
}
