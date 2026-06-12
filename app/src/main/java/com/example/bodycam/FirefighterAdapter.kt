package com.example.bodycam

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FirefighterAdapter(
    private val items: List<FirefighterItem>,
    private val onSelect: (FirefighterItem) -> Unit
) : RecyclerView.Adapter<FirefighterAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvFirefighterName)
        val tvStation: TextView = view.findViewById(R.id.tvFirefighterStation)
        val btnSelect: Button = view.findViewById(R.id.btnSelectFirefighter)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_firefighter, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvStation.text = item.station
        holder.btnSelect.setOnClickListener { onSelect(item) }
    }

    override fun getItemCount() = items.size
}