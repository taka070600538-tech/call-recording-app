package com.taka0.callrecorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.time.format.DateTimeFormatter

class RecordingsAdapter(
    private var recordings: List<Recording>,
    private var savedFileNames: Set<String>,
    private val onSelectionChanged: (Recording?) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

    private val labelFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private var selectedRecording: Recording? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.recording_label)
        val savedBadge: TextView = view.findViewById(R.id.saved_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recording = recordings[position]
        holder.label.text = recording.recordedAt.format(labelFormatter)
        holder.savedBadge.visibility = if (recording.file.name in savedFileNames) View.VISIBLE else View.GONE
        holder.itemView.isActivated = recording == selectedRecording
        holder.itemView.setOnClickListener {
            selectedRecording = recording
            notifyDataSetChanged()
            onSelectionChanged(recording)
        }
    }

    override fun getItemCount(): Int = recordings.size

    fun getSelected(): Recording? = selectedRecording

    fun updateRecordings(newRecordings: List<Recording>, newSavedFileNames: Set<String>) {
        recordings = newRecordings
        savedFileNames = newSavedFileNames
        if (selectedRecording != null && selectedRecording !in newRecordings) {
            selectedRecording = null
            onSelectionChanged(null)
        }
        notifyDataSetChanged()
    }
}
