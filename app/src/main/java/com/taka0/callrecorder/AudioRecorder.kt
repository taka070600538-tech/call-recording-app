package com.taka0.callrecorder

import java.io.File

interface AudioRecorder {
    fun start(outputFile: File)
    fun stop()
}
