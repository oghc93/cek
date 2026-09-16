package com.lite.xraylite.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lite.xraylite.R
import com.lite.xraylite.util.LogEntry
import com.lite.xraylite.util.LogLevel

class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

    private val items = mutableListOf<LogEntry>()

    fun submit(list: List<LogEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false) as TextView
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.tv.text = entry.formatted()
        holder.tv.setTextColor(
            when (entry.level) {
                LogLevel.SUCCESS -> Color.parseColor("#4CAF50")
                LogLevel.ERROR -> Color.parseColor("#FF5252")
                LogLevel.INFO -> Color.parseColor("#DDDDDD")
            }
        )
    }

    override fun getItemCount() = items.size
}
