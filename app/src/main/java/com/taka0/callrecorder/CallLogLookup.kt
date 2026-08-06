package com.taka0.callrecorder

interface CallLogLookup {
    /** 直近の発着信の電話番号。取得できない場合はnull。 */
    fun mostRecentNumber(): String?
}
