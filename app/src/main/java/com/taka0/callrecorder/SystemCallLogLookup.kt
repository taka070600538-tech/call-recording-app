package com.taka0.callrecorder

import android.content.Context
import android.provider.CallLog
import android.util.Log

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
                // Non-notified (番号非表示) calls can produce an empty-but-non-null NUMBER column;
                // treat that the same as "unresolved" (null) so callers don't persist "" as if it
                // were a real, known number.
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (e: Exception) {
            Log.w("CallLogLookup", "CallLog query failed", e)
            null
        }
    }
}
