package com.taka0.callrecorder

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallStateActionTest {

    @Test
    fun `OFFHOOK maps to ACTION_START`() {
        assertEquals(RecordingService.ACTION_START, CallStateAction.forCallState(TelephonyManager.CALL_STATE_OFFHOOK))
    }

    @Test
    fun `IDLE maps to ACTION_STOP`() {
        assertEquals(RecordingService.ACTION_STOP, CallStateAction.forCallState(TelephonyManager.CALL_STATE_IDLE))
    }

    @Test
    fun `RINGING maps to no action`() {
        assertNull(CallStateAction.forCallState(TelephonyManager.CALL_STATE_RINGING))
    }
}
