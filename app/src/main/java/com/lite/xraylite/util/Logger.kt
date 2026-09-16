package com.lite.xraylite.util

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Locale

enum class LogLevel { INFO, SUCCESS, ERROR }

data class LogEntry(val timestampMs: Long, val level: LogLevel, val message: String) {
    fun formatted(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(timestampMs)
        return "[$time] $message"
    }
}

/**
 * Buffer log in-memory (ring buffer, maksimal [MAX_ENTRIES]) yang dibaca layar "Show logs"
 * (LogActivity). Dipakai dari XrayVpnService, SshTunnelManager, MainActivity, dan
 * XrayLiteApp supaya urutan kejadian koneksi kelihatan jelas — meniru gaya log NetMod
 * di screenshot (versi app, versi core, event start/stop service, dsb).
 *
 * Sengaja in-memory saja (hilang kalau app di-kill), konsisten dengan filosofi
 * ServerRepository yang juga belum ada persistence penuh — cukup buat kebutuhan
 * debugging sesi berjalan, bukan audit log jangka panjang.
 */
object Logger {
    private const val MAX_ENTRIES = 500
    private val entries = ArrayDeque<LogEntry>()
    private val listeners = mutableListOf<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun log(message: String, level: LogLevel = LogLevel.INFO) {
        entries.addLast(LogEntry(System.currentTimeMillis(), level, message))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        notifyListeners()
    }

    fun success(message: String) = log(message, LogLevel.SUCCESS)
    fun error(message: String) = log(message, LogLevel.ERROR)

    @Synchronized
    fun all(): List<LogEntry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        notifyListeners()
    }

    @Synchronized
    fun allFormatted(): String = entries.joinToString("\n") { it.formatted() }

    /** [listener] dipanggil di main thread tiap kali ada log baru / log dibersihkan. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        mainHandler.post { listeners.toList().forEach { it() } }
    }
}
