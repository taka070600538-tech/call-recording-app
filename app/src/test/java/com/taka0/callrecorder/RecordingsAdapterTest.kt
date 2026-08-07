package com.taka0.callrecorder

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingsAdapterTest {

    private fun themedContext(): Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_CallRecorder)

    private fun recording(nameSeed: String): Recording {
        val file = File.createTempFile("rec_$nameSeed", ".m4a").apply { deleteOnExit() }
        return Recording(file, LocalDateTime.of(2026, 8, 6, 13, 51))
    }

    private fun buildRecyclerView(adapter: RecordingsAdapter): RecyclerView {
        val context = themedContext()
        val recyclerView = RecyclerView(context)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(0, 0, 1080, 2000)
        return recyclerView
    }

    @Test
    fun `tapping a row selects it and notifies the callback`() {
        val a = recording("a")
        val b = recording("b")
        var selected: Recording? = null
        val adapter = RecordingsAdapter(listOf(a, b), emptySet(), emptyMap()) { selected = it }
        val recyclerView = buildRecyclerView(adapter)

        recyclerView.findViewHolderForAdapterPosition(1)!!.itemView.performClick()
        val relaidOutRecyclerView = buildRecyclerView(adapter)

        assertEquals(b, selected)
        assertEquals(b, adapter.getSelected())
        assertTrue(relaidOutRecyclerView.findViewHolderForAdapterPosition(1)!!.itemView.isActivated)
        assertFalse(relaidOutRecyclerView.findViewHolderForAdapterPosition(0)!!.itemView.isActivated)
    }

    @Test
    fun `updateRecordings clears selection when the selected recording is removed`() {
        val a = recording("a")
        val b = recording("b")
        var selected: Recording? = null
        val adapter = RecordingsAdapter(listOf(a, b), emptySet(), emptyMap()) { selected = it }
        buildRecyclerView(adapter).findViewHolderForAdapterPosition(0)!!.itemView.performClick()
        assertEquals(a, adapter.getSelected())

        adapter.updateRecordings(listOf(b), emptySet(), emptyMap())

        assertNull(adapter.getSelected())
        assertNull(selected)
    }

    @Test
    fun `updateRecordings keeps selection when the selected recording is still present`() {
        val a = recording("a")
        val b = recording("b")
        val adapter = RecordingsAdapter(listOf(a, b), emptySet(), emptyMap()) { }
        buildRecyclerView(adapter).findViewHolderForAdapterPosition(0)!!.itemView.performClick()

        adapter.updateRecordings(listOf(a, b), emptySet(), emptyMap())

        assertEquals(a, adapter.getSelected())
    }

    @Test
    fun `saved badge is visible only for file names in savedFileNames`() {
        val a = recording("a")
        val b = recording("b")
        val adapter = RecordingsAdapter(listOf(a, b), setOf(a.file.name), emptyMap()) { }
        val recyclerView = buildRecyclerView(adapter)

        val badgeA = recyclerView.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(R.id.saved_badge)
        val badgeB = recyclerView.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<View>(R.id.saved_badge)

        assertEquals(View.VISIBLE, badgeA.visibility)
        assertEquals(View.GONE, badgeB.visibility)
    }

    @Test
    fun `label includes the phone number when one is known for that file`() {
        val a = recording("a")
        val b = recording("b")
        val adapter = RecordingsAdapter(listOf(a, b), emptySet(), mapOf(a.file.name to "08089004673")) { }
        val recyclerView = buildRecyclerView(adapter)

        val labelA = recyclerView.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<android.widget.TextView>(R.id.recording_label)
        val labelB = recyclerView.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<android.widget.TextView>(R.id.recording_label)

        assertTrue(labelA.text.toString().endsWith("08089004673"))
        assertFalse(labelB.text.toString().contains("08089004673"))
    }

    @Test
    fun `label is date-only when no phone number is known for that file`() {
        val a = recording("a")
        val adapter = RecordingsAdapter(listOf(a), emptySet(), emptyMap()) { }
        val recyclerView = buildRecyclerView(adapter)

        val labelA = recyclerView.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<android.widget.TextView>(R.id.recording_label)

        assertEquals("2026-08-06 13:51", labelA.text.toString())
    }
}
