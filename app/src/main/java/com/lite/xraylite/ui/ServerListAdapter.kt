package com.lite.xraylite.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lite.xraylite.R
import com.lite.xraylite.model.ServerConfig
import com.lite.xraylite.util.PingTester

class ServerListAdapter(
    private val onSelect: (ServerConfig) -> Unit
) : RecyclerView.Adapter<ServerListAdapter.VH>() {

    private val items = mutableListOf<ServerConfig>()
    private var activeId: String? = null
    private val pingCache = HashMap<String, Long>()

    fun submit(list: List<ServerConfig>, activeId: String?) {
        items.clear()
        items.addAll(list)
        this.activeId = activeId
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val dot: View = view.findViewById(R.id.dotActive)
        val name: TextView = view.findViewById(R.id.tvName)
        val detail: TextView = view.findViewById(R.id.tvDetail)
        val ping: TextView = view.findViewById(R.id.tvPing)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cfg = items[position]
        holder.name.text = cfg.name
        holder.detail.text = "${cfg.type} · ${cfg.address}:${cfg.port}"
        holder.dot.setBackgroundResource(
            if (cfg.id == activeId) R.drawable.dot_active else R.drawable.dot_inactive
        )
        val cachedPing = pingCache[cfg.id]
        holder.ping.text = if (cachedPing != null) "${cachedPing} ms" else "-- ms"

        holder.itemView.setOnClickListener { onSelect(cfg) }

        // Tes ping otomatis saat baris muncul (throttle sederhana via cache)
        if (cachedPing == null) {
            PingTester.test(cfg) { success, ms ->
                holder.itemView.post {
                    pingCache[cfg.id] = if (success) ms else -1
                    // RecyclerView bisa saja sudah mendaur ulang `holder` ini untuk item lain
                    // di posisi berbeda sebelum ping (timeout-nya sampai 3 detik) selesai.
                    // Cek dulu holder ini masih benar-benar menampilkan `cfg` yang sama sebelum
                    // nulis ke view-nya, supaya hasil ping tidak nempel di baris server yang salah.
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && items.getOrNull(pos)?.id == cfg.id) {
                        holder.ping.text = if (success) "${ms} ms" else "timeout"
                    }
                }
            }
        }
    }

    override fun getItemCount() = items.size
}
