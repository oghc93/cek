package com.lite.xraylite.util

import com.lite.xraylite.settings.Prefs
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Timer
import java.util.TimerTask

/**
 * HTTP ping periodik lewat proxy/tunnel yang sedang aktif — dipakai supaya sesi SSH
 * murni tidak idle & disconnect sendiri (sama seperti opsi "HTTP ping > Auto start" di
 * NetMod), dan sekalian ngasih sinyal hidup/matinya jalur di layar "Show logs".
 *
 * Ping ini jalan di proses APP (bukan lewat TUN Xray), jadi untuk mode Xray dia menguji
 * konektivitas internet perangkat secara umum, bukan spesifik lewat tunnel — beda dengan
 * NetMod yang HTTP ping-nya jalan di proses core setelah tunnel established. Cukup untuk
 * indikasi "tunnel masih hidup" karena kalau permintaan ini sukses berarti setidaknya
 * request keluar normal; kalau butuh jaminan lewat proxy tertentu, arahkan HttpURLConnection
 * ini ke java.net.Proxy yang menunjuk ke SOCKS lokal (misal localSocksPort SSH).
 */
object HttpPingTester {
    private var timer: Timer? = null

    fun start(intervalMs: Long = 30_000L) {
        stop()
        if (!Prefs.httpPingAutoStart) return
        val url = Prefs.httpPingUrlOrDefault()
        val t = Timer("http-ping", true)
        t.scheduleAtFixedRate(object : TimerTask() {
            override fun run() = pingOnce(url)
        }, 1000L, intervalMs)
        timer = t
    }

    fun stop() {
        timer?.cancel()
        timer = null
    }

    private fun pingOnce(urlStr: String) {
        Logger.log("Requesting $urlStr with (HEAD)")
        val start = System.currentTimeMillis()
        try {
            val url = URL(urlStr)
            val host = InetAddress.getByName(url.host).hostAddress
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 5000
                readTimeout = 5000
            }
            val status = conn.responseCode
            val elapsed = System.currentTimeMillis() - start
            conn.disconnect()
            Logger.success("[ping] Reply from: $host: status=$status: time=${elapsed}ms")
        } catch (e: Exception) {
            Logger.error("[ping] Gagal: ${e.message}")
        }
    }
}
