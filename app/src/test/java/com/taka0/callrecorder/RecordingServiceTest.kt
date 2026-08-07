package com.taka0.callrecorder

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.time.LocalDateTime
import java.util.Base64

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
    var lastBeforeEpochMillis: Long? = null

    override fun mostRecentNumber(afterEpochMillis: Long, beforeEpochMillis: Long): String? {
        callCount++
        lastAfterEpochMillis = afterEpochMillis
        lastBeforeEpochMillis = beforeEpochMillis
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
    fun `ACTION_STOP passes a bounded window around the recording start time to CallLogLookup`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.setAudioRecorderForTest(FakeAudioRecorder())
        service.setBackgroundExecutorForTest { it.run() }
        val lookup = FakeCallLogLookup(mutableListOf("08088004673"))
        service.setCallLogLookupForTest(lookup)
        val beforeStart = System.currentTimeMillis()
        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START), 0, 1)
        val afterStart = System.currentTimeMillis()

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 2)

        // 下限は録音開始より前(呼び出し時間の余裕分)、上限は録音開始より後(クロックスキュー許容分)に
        // なるはずで、かつ下限 < 上限であることを確認する。
        val after = lookup.lastAfterEpochMillis
        val before = lookup.lastBeforeEpochMillis
        assertNotNull(after)
        assertNotNull(before)
        assertTrue(after!! < beforeStart)
        assertTrue(before!! > afterStart)
        assertTrue(after < before)
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

    // --- I-3: patchDiaryIfAlreadySaved must not depend on SavedRecordingsStore --------------

    private fun newTestSettingsStore(server: MockWebServer): SecureSettingsStore {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("test_secure_settings_i3_${System.nanoTime()}", android.content.Context.MODE_PRIVATE)
        return SecureSettingsStore(prefs).apply {
            gitHubToken = "ghp-test"
            gitHubRepo = "me/diary-repo"
            gitHubBranch = "main"
            gitHubFolder = "diary"
        }
    }

    @Test
    fun `ACTION_STOP patches the diary on GitHub even when the file is not recorded in SavedRecordingsStore`() {
        // This is exactly the race I-3 fixes: TranscribeActivity PUTs the diary with "不明" and
        // only calls SavedRecordingsStore#markSaved() afterwards, so a background phone-number
        // resolution that lands in between must still be able to patch the diary even though
        // SavedRecordingsStore has no record of the file yet. We deliberately never call
        // markSaved() anywhere in this test.
        val server = MockWebServer()
        server.start()
        try {
            val settingsStore = newTestSettingsStore(server)
            val gitHubClient = GitHubClient(apiBaseUrl = server.url("/").toString().trimEnd('/'))

            val service = Robolectric.buildService(RecordingService::class.java).create().get()
            service.setAudioRecorderForTest(FakeAudioRecorder())
            service.setBackgroundExecutorForTest { it.run() }
            service.setCallLogLookupForTest(FakeCallLogLookup(mutableListOf("08088004673")))
            service.setSecureSettingsStoreForTest(settingsStore)
            service.setGitHubClientForTest(gitHubClient)

            service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START), 0, 1)
            val fileName = service.getCurrentFileNameForTest()!!
            val recordedAt = LocalDateTime.parse(fileName.removeSuffix(".m4a"), FileNaming.FORMATTER)

            val existingDiary = "---\ndate: ${recordedAt.toLocalDate()}\n---\n\n" +
                "${DiaryMarkdownFormatter.timeHeadingPrefix(recordedAt.toLocalTime())} — 不明\n\n本文\n"
            val getBody = JSONObject()
                .put("sha", "sha-existing")
                .put("content", Base64.getEncoder().encodeToString(existingDiary.toByteArray(Charsets.UTF_8)))
            server.enqueue(MockResponse().setResponseCode(200).setBody(getBody.toString()))
            server.enqueue(MockResponse().setResponseCode(200))

            service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 2)

            val getRequest = server.takeRequest()
            assertEquals("GET", getRequest.method)
            val putRequest = server.takeRequest()
            assertEquals("PUT", putRequest.method)
            val putBody = JSONObject(putRequest.body.readUtf8())
            val decodedContent = String(Base64.getMimeDecoder().decode(putBody.getString("content")), Charsets.UTF_8)
            assertTrue(decodedContent.contains("${DiaryMarkdownFormatter.timeHeadingPrefix(recordedAt.toLocalTime())} — 08088004673"))
            assertFalse(decodedContent.contains("不明"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `ACTION_STOP does not PUT to GitHub when no matching unknown heading exists`() {
        val server = MockWebServer()
        server.start()
        try {
            val settingsStore = newTestSettingsStore(server)
            val gitHubClient = GitHubClient(apiBaseUrl = server.url("/").toString().trimEnd('/'))

            val service = Robolectric.buildService(RecordingService::class.java).create().get()
            service.setAudioRecorderForTest(FakeAudioRecorder())
            service.setBackgroundExecutorForTest { it.run() }
            service.setCallLogLookupForTest(FakeCallLogLookup(mutableListOf("08088004673")))
            service.setSecureSettingsStoreForTest(settingsStore)
            service.setGitHubClientForTest(gitHubClient)

            service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START), 0, 1)

            // Diary exists on GitHub but has no "不明" heading matching this recording's time,
            // e.g. because it was never transcribed/saved at all.
            val existingDiary = "---\ndate: 2026-08-06\n---\n\n## 09:00 — 08000000000\n\n別の通話\n"
            val getBody = JSONObject()
                .put("sha", "sha-existing")
                .put("content", Base64.getEncoder().encodeToString(existingDiary.toByteArray(Charsets.UTF_8)))
            server.enqueue(MockResponse().setResponseCode(200).setBody(getBody.toString()))

            service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 2)

            val getRequest = server.takeRequest()
            assertEquals("GET", getRequest.method)
            // No further request should have been made (no PUT); a second immediate takeRequest
            // with a short timeout confirms nothing else was enqueued/sent.
            assertNull(server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {
            server.shutdown()
        }
    }
}
