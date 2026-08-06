package com.taka0.callrecorder

interface CallLogLookup {
    /** 指定した時刻（エポックミリ秒）より新しい、直近の発着信の電話番号。取得できない場合はnull。 */
    fun mostRecentNumber(afterEpochMillis: Long): String?
}
