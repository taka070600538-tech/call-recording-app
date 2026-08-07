package com.taka0.callrecorder

interface CallLogLookup {
    /**
     * (afterEpochMillis, beforeEpochMillis) の範囲内で最も新しい発着信の電話番号。
     * 取得できない場合はnull。
     */
    fun mostRecentNumber(afterEpochMillis: Long, beforeEpochMillis: Long): String?
}
