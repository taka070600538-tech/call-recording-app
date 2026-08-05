package com.taka0.callrecorder

import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {

    @Test
    fun `launches and shows an empty recordings list`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().get()

        val recyclerView = activity.findViewById<RecyclerView>(R.id.recordings_list)

        assertNotNull(recyclerView)
        assertNotNull(recyclerView.adapter)
        assertTrue(recyclerView.adapter is RecordingsAdapter)
    }
}
