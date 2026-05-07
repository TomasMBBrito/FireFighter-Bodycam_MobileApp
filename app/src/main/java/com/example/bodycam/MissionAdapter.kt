package com.example.bodycam

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MissionAdapter(
    private val items: List<MissionItem>,
    private val onSelect: (MissionItem) -> Unit
) : RecyclerView.Adapter<MissionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvMissionTitle)
        val tvLocation: TextView = view.findViewById(R.id.tvMissionLocation)
        val btnSelect: Button = view.findViewById(R.id.btnSelectMission)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mission, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvLocation.text = item.location
        holder.btnSelect.setOnClickListener { onSelect(item) }
    }

    override fun getItemCount() = items.size
}