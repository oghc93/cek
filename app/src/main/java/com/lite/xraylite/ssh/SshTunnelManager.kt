package com.lite.xraylite.ssh

import android.content.Context
import android.os.PowerManager
import com.lite.xraylite.model.ServerConfig
import com.lite.xraylite.settings.Prefs
import com.lite.xraylite.util.Logger
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * Tunnel SSH murni: bikin local SOCKS/port-forward via SSH direct-tcpip channel,
 * pakai `LocalPortForwarder` bawaan sshj (bukan bikin sendiri accept-loop) supaya
 * start/stop-nya benar: accept loop otomatis berhenti begitu forwarder di-close(),
 * bukannya nyangkut selamanya di accept() seperti versi sebelumnya.
 *
 * Sekarang juga baca Settings > SSH options: WakeLock (jaga CPU nyala selama sesi SSH,
 * dimatikan lagi begitu putus) + auto-reconnect (attempt max & interval), plus log tiap
 * event penting ke [Logger] biar kelihatan di layar "Show logs" (meniru urutan event
 * WakeLock acquired/released di NetMod).
 *
 * Catatan keamanan: PromiscuousVerifier() menerima host key apa saja (mirip
 * StrictHostKeyChecking=no). Untuk versi lebih matang, simpan & verifikasi
 * fingerprint host key VPS kamu sendiri agar tidak rentan MITM.
 */
class SshTunnelManager(private val appContext: Context) {

    private var client: SSHClient? = null
    private var forwarder: LocalPortForwarder? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var stopRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var reconnectAttempt = 0

    /**
     * Konek & buka local port-forward. Blocking di thread background sendiri;
     * [onConnected] dipanggil sekali begitu forward mulai listen, [onError] kalau
     * gagal konek/auth dan sudah kehabisan jatah reconnect ATAU forward terputus sendiri
     * bukan karena [disconnect] dipanggil user.
     */
    fun connect(cfg: ServerConfig, onConnected: () -> Unit, onError: (Throwable) -> Unit) {
        stopRequested = false
        reconnectAttempt = 0
        acquireWakeLock()
        attemptConnect(cfg, onConnected, onError)
    }

    private fun attemptConnect(cfg: ServerConfig, onConnected: () -> Unit, onError: (Throwable) -> Unit) {
        executor.execute {
            var ssh: SSHClient? = null
            var serverSocket: ServerSocket? = null
            try {
                Logger.log("Starting service")
                Logger.log("Using config remarks: ${cfg.name}, address: ${cfg.address}:${cfg.port}, protocol: ssh")

                ssh = SSHClient()
                ssh.addHostKeyVerifier(PromiscuousVerifier()) // TODO: ganti verifier fingerprint asli
                ssh.connect(cfg.address, cfg.port)

                if (cfg.sshPrivateKeyPem.isNotBlank()) {
                    // PENTING: cfg.sshPrivateKeyPem berisi ISI PEM (bukan path file), jadi
                    // harus lewat overload loadKeys(privateKey, publicKey, passwordFinder) —
                    // overload loadKeys(String location) memperlakukan argumennya sebagai
                    // PATH FILE DI DISK, bukan konten key, dan bakal selalu gagal (FileNotFound)
                    // kalau dikasih isi PEM langsung.
                    val keyProvider = ssh.loadKeys(cfg.sshPrivateKeyPem, null, null)
                    ssh.authPublickey(cfg.sshUsername, keyProvider)
                } else {
                    ssh.authPassword(cfg.sshUsername, cfg.sshPassword)
                }

                // remoteHost/remotePort = layanan di sisi VPS yang mau ditembus lewat
                // channel SSH (default asumsi VPS sudah jalankan SOCKS lokal di
                // 127.0.0.1:1080 — sesuaikan kalau beda, lihat README bagian SSH murni).
                val params = Parameters("127.0.0.1", cfg.localSocksPort, "127.0.0.1", 1080)

                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", cfg.localSocksPort))
                }

                client = ssh
                val fwd = ssh.newLocalPortForwarder(params, serverSocket)
                forwarder = fwd

                reconnectAttempt = 0
                Logger.success("Connecting to server")
                onConnected()
                fwd.listen() // blocking; balik normal begitu forwarder.close() dipanggil dari disconnect()
            } catch (t: Throwable) {
                if (!stopRequested) {
                    handleDisconnected(cfg, t, onConnected, onError)
                }
            } finally {
                runCatching { serverSocket?.close() }
                runCatching { ssh?.disconnect() }
                client = null
                forwarder = null
            }
        }
    }

    /** Putus tak terduga (bukan user disconnect) -> coba reconnect sesuai Settings, kalau habis jatah baru lapor error. */
    private fun handleDisconnected(cfg: ServerConfig, t: Throwable, onConnected: () -> Unit, onError: (Throwable) -> Unit) {
        val maxAttempt = Prefs.sshReconnectMaxAttempt
        if (reconnectAttempt >= maxAttempt) {
            releaseWakeLock()
            Logger.error("Gagal konek setelah $reconnectAttempt percobaan: ${t.message}")
            onError(t)
            return
        }
        reconnectAttempt++
        Logger.error("Koneksi SSH putus (${t.message}), reconnect percobaan $reconnectAttempt/$maxAttempt...")
        Thread.sleep(Prefs.sshReconnectIntervalMs.toLong().coerceAtLeast(0))
        if (!stopRequested) attemptConnect(cfg, onConnected, onError)
    }

    private fun acquireWakeLock() {
        if (!Prefs.sshWakeLockEnabled) return
        runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OGTunnel:SshWakeLock")
            wl.acquire(6 * 60 * 60 * 1000L) // batas aman 6 jam, dilepas manual saat disconnect
            wakeLock = wl
            Logger.success("WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        if (wakeLock != null) Logger.success("WakeLock released")
        wakeLock = null
    }

    fun disconnect() {
        stopRequested = true
        // forwarder.close() meng-interrupt thread listen() DAN menutup ServerSocket-nya,
        // jadi accept() yang lagi ngeblok langsung keluar dan loop-nya benar-benar berhenti
        // (beda dari implementasi manual sebelumnya yang tidak pernah menutup socket-nya).
        runCatching { forwarder?.close() }
        runCatching { client?.disconnect() }
        releaseWakeLock()
        Logger.log("Service stopped")
    }
}
