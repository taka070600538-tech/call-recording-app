package com.taka0.callrecorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedRecordingsStoreTest {

    private fun newStore(): SavedRecordingsStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_saved_recordings_${System.nanoTime()}", Context.MODE_PRIVATE)
        return SavedRecordingsStore(prefs)
    }

    @Test
    fun `all is empty by default`() {
        val store = newStore()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `markSaved adds the file name to all`() {
        val store = newStore()

        store.markSaved("2026-08-06-135135.m4a")

        assertEquals(setOf("2026-08-06-135135.m4a"), store.all())
    }

    @Test
    fun `markSaved accumulates multiple file names`() {
        val store = newStore()

        store.markSaved("a.m4a")
        store.markSaved("b.m4a")

        assertEquals(setOf("a.m4a", "b.m4a"), store.all())
    }

    @Test
    fun `persists across instances backed by the same preferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_saved_recordings_shared", Context.MODE_PRIVATE)
        SavedRecordingsStore(prefs).markSaved("2026-08-06-135135.m4a")

        val reloaded = SavedRecordingsStore(prefs)

        assertEquals(setOf("2026-08-06-135135.m4a"), reloaded.all())
    }
}
