package com.taka0.callrecorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.time.format.DateTimeFormatter

class RecordingsAdapter(
    private var recordings: List<Recording>,
    private val onPlay: (Recording) -> Unit,
    private val onTranscribe: (Recording) -> Unit,
    private val onDelete: (Recording) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

    private val labelFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.recording_label)
        val playButton: Button = view.findViewById(R.id.play_button)
        val transcribeButton: Button = view.findViewById(R.id.transcribe_button)
        val deleteButton: Button = view.findViewById(R.id.delete_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recording = recordings[position]
        holder.label.text = recording.recordedAt.format(labelFormatter)
        holder.playButton.setOnClickListener { onPlay(recording) }
        holder.transcribeButton.setOnClickListener { onTranscribe(recording) }
        holder.deleteButton.setOnClickListener { onDelete(recording) }
    }

    override fun getItemCount(): Int = recordings.size

    fun updateRecordings(newRecordings: List<Recording>) {
        recordings = newRecordings
        notifyDataSetChanged()
    }
}
