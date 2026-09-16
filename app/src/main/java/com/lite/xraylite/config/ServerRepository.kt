package com.lite.xraylite.config

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lite.xraylite.model.ServerConfig

/**
 * Penyimpanan daftar semua profil server (multi-profile), plus profil aktif
 * dan statistik sesi berjalan (dipakai panel monitor di UI).
 *
 * Sekarang PERSISTEN lewat SharedPreferences (Gson serialize List<ServerConfig>) —
 * sebelumnya ini murni in-memory, jadi seluruh server hilang tiap app di-kill/restart.
 * Ini diperbaiki karena fitur "Import from file" tidak banyak gunanya kalau hasil
 * import-nya hilang begitu app ditutup. Panggil [init] sekali di Application.onCreate()
 * sebelum method lain di object ini dipakai.
 */
object ServerRepository {

    private const val FILE = "og_tunnel_servers"
    private const val KEY_SERVERS = "servers_json"
    private const val KEY_ACTIVE = "active_id"

    private lateinit var sp: SharedPreferences
    private val gson = Gson()
    private val servers = LinkedHashMap<String, ServerConfig>()

    var activeId: String? = null
        private set

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val json = sp.getString(KEY_SERVERS, null)
        if (!json.isNullOrBlank()) {
            runCatching {
                val type = object : TypeToken<List<ServerConfig>>() {}.type
                val list: List<ServerConfig> = gson.fromJson(json, type)
                list.forEach { servers[it.id] = it }
            }
        }
        activeId = sp.getString(KEY_ACTIVE, null)
    }

    private fun persist() {
        sp.edit()
            .putString(KEY_SERVERS, gson.toJson(servers.values.toList()))
            .putString(KEY_ACTIVE, activeId)
            .apply()
    }

    // Statistik sesi berjalan (byte). Diupdate dari XrayVpnService / SshTunnelManager
    // lewat SessionStats saat data mengalir. Placeholder sampai dihubungkan ke
    // API stats asli Xray-core (StatsManager) atau counter socket SSH.
    object SessionStats {
        var uploadBytes: Long = 0L
        var downloadBytes: Long = 0L
        var connectedSinceMs: Long = 0L

        fun reset() {
            uploadBytes = 0L
            downloadBytes = 0L
            connectedSinceMs = System.currentTimeMillis()
        }
    }

    // Hasil ping terakhir tiap server (ms), null = belum pernah dites, -1 = timeout
    private val pingResults = HashMap<String, Int?>()

    fun setPing(id: String, ms: Int?) { pingResults[id] = ms }
    fun getPing(id: String): Int? = pingResults[id]

    fun add(cfg: ServerConfig) {
        servers[cfg.id] = cfg
        persist()
    }

    fun remove(id: String) {
        servers.remove(id)
        pingResults.remove(id)
        if (activeId == id) activeId = null
        persist()
    }

    fun all(): List<ServerConfig> = servers.values.toList()

    fun get(id: String?): ServerConfig? = id?.let { servers[it] }

    fun setActive(id: String) {
        activeId = id
        persist()
    }

    fun activeConfig(): ServerConfig? = get(activeId)
}
