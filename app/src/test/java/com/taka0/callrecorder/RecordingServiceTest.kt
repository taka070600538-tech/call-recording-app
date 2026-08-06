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
    var startCount = 0
    var stopCount = 0

    override fun start(outputFile: File) {
        startedFile = outputFile
        startCount++
    }

    override fun stop() {
        stopped = true
        stopCount++
    }
}

class FakeCallLogLookup(private val numbers: MutableList<String?>) : CallLogLookup {
    var callCount = 0
    var lastAfterEpochMillis: Long? = null

    override fun mostRecentNumber(afterEpochMillis: Long): String? {
        callCount++
        lastAfterEpochMillis = afterEpochMillis
        return if (numbers.isNotEmpty()) numbers.removeAt(0) else null
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
    fun `a second ACTION_START while recording is ignored`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val fake = FakeAudioRecorder()
        service.setAudioRecorderForTest(fake)
        val startIntent = Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START)

        service.onStartCommand(startIntent, 0, 1)
        service.onStartCommand(startIntent, 0, 2)

        assertEquals(1, fake.startCount)
    }

    @Test
    fun `ACTION_STOP without a prior start does not touch the recorder`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val fake = FakeAudioRecorder()
        service.setAudioRecorderForTest(fake)

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 1)

        assertEquals(0, fake.stopCount)
    }

    @Test
    fun `a duplicate ACTION_STOP stops the recorder only once`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val fake = FakeAudioRecorder()
        service.setAudioRecorderForTest(fake)
        val stopIntent = Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP)
        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START), 0, 1)

        service.onStartCommand(stopIntent, 0, 2)
        service.onStartCommand(stopIntent, 0, 3)

        assertEquals(1, fake.stopCount)
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

    @Test
    fun `ACTION_STOP saves the phone number from CallLogLookup to CallMetadataStore`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.setAudioRecorderForTest(FakeAudioRecorder())
        service.setBackgroundExecutorForTest { it.run() }
        service.setCallLogLookupForTest(FakeCallLogLookup(mutableListOf("08088004673")))
        val startIntent = Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START)
        service.onStartCommand(startIntent, 0, 1)
        val fileName = service.getCurrentFileNameForTest()

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 2)

        val store = CallMetadataStore(ApplicationProvider.getApplicationContext<android.content.Context>())
        assertEquals("08088004673", store.get(fileName!!))
    }

    @Test
    fun `ACTION_STOP passes a baseline near the recording start time to CallLogLookup`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.setAudioRecorderForTest(FakeAudioRecorder())
        service.setBackgroundExecutorForTest { it.run() }
        val lookup = FakeCallLogLookup(mutableListOf("08088004673"))
        service.setCallLogLookupForTest(lookup)
        val beforeStart = System.currentTimeMillis()
        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START), 0, 1)
        val afterStart = System.currentTimeMillis()

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 2)

        // ベースラインは録音開始時刻から安全マージン(数秒)を引いた値になるはずなので、
        // 「録音開始直前〜直後」のごく短い範囲に収まっているはずで、0や無関係な値ではないことを確認する。
        val baseline = lookup.lastAfterEpochMillis
        assertNotNull(baseline)
        assertTrue(baseline!! <= afterStart)
        assertTrue(baseline >= beforeStart - 10_000L)
    }

    @Test
    fun `ACTION_STOP saves the phone number as soon as CallLogLookup returns one during retries`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.setAudioRecorderForTest(FakeAudioRecorder())
        service.setBackgroundExecutorForTest { it.run() }
        val lookup = FakeCallLogLookup(mutableListOf(null, null, "08088004673"))
        service.setCallLogLookupForTest(lookup)
        val startIntent = Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START)
        service.onStartCommand(startIntent, 0, 1)
        val fileName = service.getCurrentFileNameForTest()

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 2)

        assertEquals(3, lookup.callCount)
        val store = CallMetadataStore(ApplicationProvider.getApplicationContext<android.content.Context>())
        assertEquals("08088004673", store.get(fileName!!))
    }

    @Test
    fun `ACTION_STOP defers the CallLog lookup so teardown does not block on it`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.setAudioRecorderForTest(FakeAudioRecorder())
        var captured: Runnable? = null
        service.setBackgroundExecutorForTest { runnable -> captured = runnable }
        service.setCallLogLookupForTest(FakeCallLogLookup(mutableListOf("08088004673")))
        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START), 0, 1)
        val fileName = service.getCurrentFileNameForTest()

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 2)

        // onStartCommand(ACTION_STOP) has already returned, but the captured Runnable has not been
        // run yet, so the lookup+save must not have happened synchronously as part of stopRecording().
        val store = CallMetadataStore(ApplicationProvider.getApplicationContext<android.content.Context>())
        assertNull(store.get(fileName!!))

        captured?.run()

        // Once the deferred work actually runs, it produces the correct result.
        assertEquals("08088004673", store.get(fileName))
    }

    @Test
    fun `a stray ACTION_STOP without a prior start does not touch CallMetadataStore`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.setAudioRecorderForTest(FakeAudioRecorder())
        service.setCallLogLookupForTest(FakeCallLogLookup(mutableListOf("08088004673")))

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 1)

        val store = CallMetadataStore(ApplicationProvider.getApplicationContext<android.content.Context>())
        assertNull(store.get("nonexistent.m4a"))
    }
}
