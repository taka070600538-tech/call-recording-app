package com.taka0.callrecorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureSettingsStoreTest {

    private fun newStore(): SecureSettingsStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_secure_settings_${System.nanoTime()}", Context.MODE_PRIVATE)
        return SecureSettingsStore(prefs)
    }

    @Test
    fun `defaults branch to main and folder to diary`() {
        val store = newStore()
        assertEquals("main", store.gitHubBranch)
        assertEquals("diary", store.gitHubFolder)
    }

    @Test
    fun `persists values across instances backed by the same preferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_secure_settings_shared", Context.MODE_PRIVATE)
        SecureSettingsStore(prefs).apply {
            openAiApiKey = "sk-test"
            gitHubToken = "ghp-test"
            gitHubRepo = "me/call-recording-app"
        }

        val reloaded = SecureSettingsStore(prefs)
        assertEquals("sk-test", reloaded.openAiApiKey)
        assertEquals("ghp-test", reloaded.gitHubToken)
        assertEquals("me/call-recording-app", reloaded.gitHubRepo)
    }

    @Test
    fun `is not configured until token and repo are set`() {
        val store = newStore()
        assertFalse(store.isConfigured())

        store.gitHubToken = "ghp-test"
        store.gitHubRepo = "me/call-recording-app"
        assertTrue(store.isConfigured())
    }
}
