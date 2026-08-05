package com.taka0.callrecorder

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File

class FakeAudioRecorder : AudioRecorder {
    var startedFile: File? = null
    var stopped = false

    override fun start(outputFile: File) {
        startedFile = outputFile
    }

    override fun stop() {
        stopped = true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingServiceTest {

    // Robolectric's ShadowStatFs reports 0 available bytes for any path until stats are
    // explicitly registered; without this, RecordingService's low-storage guard would always
    // trip and short-circuit startRecording() before it ever reaches the AudioRecorder.
    @Before
    fun stubPlentyOfStorage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val recordingsDir = File(context.getExternalFilesDir(null), "recordings")
        ShadowStatFs.registerStats(recordingsDir.path, 1_000_000, 1_000_000, 1_000_000)
    }

    @Test
    fun `ACTION_START begins recording to a file named by FileNaming`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val fake = FakeAudioRecorder()
        service.setAudioRecorderForTest(fake)

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START), 0, 1)

        assertNotNull(fake.startedFile)
        assertTrue(fake.startedFile!!.name.endsWith(".m4a"))
    }

    @Test
    fun `ACTION_STOP stops the audio recorder`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val fake = FakeAudioRecorder()
        service.setAudioRecorderForTest(fake)
        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START), 0, 1)

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 2)

        assertTrue(fake.stopped)
    }

    @Test
    fun `enables speakerphone when recording starts`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.setAudioRecorderForTest(FakeAudioRecorder())

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START), 0, 1)

        val audioManager = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        assertTrue(audioManager.isSpeakerphoneOn)
    }
}
