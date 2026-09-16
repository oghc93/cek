package com.lite.xraylite.util

import com.lite.xraylite.model.ServerConfig
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Ping ringan: ukur waktu TCP handshake ke address:port server (bukan ICMP,
 * karena ICMP butuh root di Android). Cukup akurat untuk indikasi latency
 * dan status server hidup/mati.
 */
object PingTester {
    private val executor = Executors.newCachedThreadPool()

    fun test(cfg: ServerConfig, timeoutMs: Int = 3000, callback: (success: Boolean, ms: Long) -> Unit) {
        executor.execute {
            val start = System.currentTimeMillis()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(cfg.address, cfg.port), timeoutMs)
                }
                val elapsed = System.currentTimeMillis() - start
                callback(true, elapsed)
            } catch (e: Exception) {
                callback(false, -1)
            }
        }
    }
}
