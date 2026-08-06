package com.taka0.callrecorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallMetadataStoreTest {

    private fun newStore(): CallMetadataStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_call_metadata_${System.nanoTime()}", Context.MODE_PRIVATE)
        return CallMetadataStore(prefs)
    }

    @Test
    fun `get returns null when nothing was saved`() {
        val store = newStore()
        assertNull(store.get("2026-08-06-135135.m4a"))
    }

    @Test
    fun `save then get returns the phone number`() {
        val store = newStore()

        store.save("2026-08-06-135135.m4a", "08088004673")

        assertEquals("08088004673", store.get("2026-08-06-135135.m4a"))
    }

    @Test
    fun `save with a null phone number can be read back as null`() {
        val store = newStore()

        store.save("2026-08-06-135135.m4a", null)

        assertNull(store.get("2026-08-06-135135.m4a"))
    }

    @Test
    fun `persists across instances backed by the same preferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_call_metadata_shared", Context.MODE_PRIVATE)
        CallMetadataStore(prefs).save("2026-08-06-135135.m4a", "08088004673")

        val reloaded = CallMetadataStore(prefs)

        assertEquals("08088004673", reloaded.get("2026-08-06-135135.m4a"))
    }
}
