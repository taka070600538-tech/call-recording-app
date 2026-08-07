package com.taka0.callrecorder

import android.content.Context
import android.provider.CallLog

class SystemCallLogLookup(private val context: Context) : CallLogLookup {
    override fun mostRecentNumber(afterEpochMillis: Long, beforeEpochMillis: Long): String? {
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                "${CallLog.Calls.DATE} > ? AND ${CallLog.Calls.DATE} < ?",
                arrayOf(afterEpochMillis.toString(), beforeEpochMillis.toString()),
                // No "LIMIT 1" here: some OEM/OS versions of the CallLog provider reject a LIMIT
                // token in sortOrder with IllegalArgumentException (confirmed on a real Pixel 9a).
                // moveToFirst() below already gives the most-recent row under this DESC order.
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
