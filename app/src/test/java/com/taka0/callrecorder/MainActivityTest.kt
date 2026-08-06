package com.taka0.callrecorder

import android.view.View
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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

    @Test
    fun `action buttons start disabled and become enabled after selecting a recording`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val recordingsDir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        File(recordingsDir, "2026-08-06-135135.m4a").createNewFile()

        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().get()

        val playButton = activity.findViewById<Button>(R.id.action_play)
        val transcribeButton = activity.findViewById<Button>(R.id.action_transcribe)
        val deleteButton = activity.findViewById<Button>(R.id.action_delete)
        assertFalse(playButton.isEnabled)
        assertFalse(transcribeButton.isEnabled)
        assertFalse(deleteButton.isEnabled)

        val recyclerView = activity.findViewById<RecyclerView>(R.id.recordings_list)
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(0, 0, 1080, 2000)
        recyclerView.findViewHolderForAdapterPosition(0)!!.itemView.performClick()

        assertTrue(playButton.isEnabled)
        assertTrue(transcribeButton.isEnabled)
        assertTrue(deleteButton.isEnabled)
    }
}
