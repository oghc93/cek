package com.lite.xraylite.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.IpPrefix
import android.net.InetAddresses
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.lite.xraylite.config.ConfigParser
import com.lite.xraylite.config.ServerRepository
import com.lite.xraylite.model.ServerConfig
import com.lite.xraylite.settings.Prefs
import com.lite.xraylite.ui.MainActivity
import com.lite.xraylite.util.Logger
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * VpnService ringan: bikin TUN interface, terus kasih file descriptor-nya LANGSUNG ke
 * `CoreController.startLoop(configJson, tunFd)` dari AndroidLibXrayLite. Versi
 * AndroidLibXrayLite yang sekarang sudah bisa baca trafik dari TUN fd secara internal,
 * jadi TIDAK perlu lagi proses tun2socks terpisah (hev-socks5-tunnel dkk) seperti
 * rencana awal di README — satu native dependency lebih sedikit yang harus di-build.
 *
 * CATATAN PENTING:
 * Kelas ini SUDAH memanggil API Libv2ray yang nyata (bukan TODO kosong lagi), dicek
 * langsung ke source github.com/2dust/AndroidLibXrayLite + cara pakainya di app v2rayNG.
 * Tapi karena `libs/libv2ray.aar` di-build otomatis oleh workflow CI dari source Go
 * ter-update di GitHub (lihat .github/workflows/build.yml), ADA kemungkinan kecil tanda
 * tangan method interface `CoreCallbackHandler` berubah kalau upstream mengubah API-nya
 * setelah file ini ditulis. Kalau build gagal persis di `object : CoreCallbackHandler`
 * karena "method tidak match", cek versi AAR yang ke-build lalu sesuaikan signature di
 * sini (biasanya cuma beda tipe Int/Long).
 */
class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.lite.xraylite.CONNECT"
        const val ACTION_DISCONNECT = "com.lite.xraylite.DISCONNECT"
        const val EXTRA_CONFIG_ID = "config_id"
        private const val CHANNEL_ID = "xraylite_vpn"
        private const val NOTIF_ID = 1
        private const val STATS_INTERVAL_MS = 1000L

        // Range yang dianggap "LAN" untuk fitur Settings > "Bypass LAN route (VPN)".
        // Standar RFC1918 (private) + link-local, dipakai sebagai excludeRoute() supaya
        // trafik ke jaringan lokal (router, printer, webcam) tidak ikut masuk tunnel.
        private val LAN_RANGES = listOf(
            "10.0.0.0" to 8,
            "172.16.0.0" to 12,
            "192.168.0.0" to 16,
            "169.254.0.0" to 16
        )
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var isRunning = false
    private var coreController: CoreController? = null
    private val statsHandler = Handler(Looper.getMainLooper())

    private val statsTick = object : Runnable {
        override fun run() {
            pollStats()
            if (isRunning) statsHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val cfg = ServerRepository.get(intent.getStringExtra(EXTRA_CONFIG_ID))
                if (cfg != null) startVpn(cfg) else stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startVpn(cfg: ServerConfig) {
        if (isRunning) return
        Logger.log("Starting service")
        Logger.log(
            "Using config remarks: ${cfg.name}, address: ${cfg.address}:${cfg.port}, protocol: " +
                cfg.type.name.lowercase() + ", transfer protocol: " + cfg.network +
                (if (cfg.security == "tls") ", tls type: tls, sni: ${cfg.sni}" else "")
        )
        startForeground(NOTIF_ID, buildNotification("Menghubungkan ke ${cfg.name}..."))

        val fd = buildVpnInterface(cfg) ?: run {
            Logger.error("Gagal membuat TUN interface")
            updateNotification("Gagal membuat TUN interface")
            stopVpn()
            return
        }
        tunFd = fd

        val controller = Libv2ray.newCoreController(buildCallbackHandler(cfg))
        coreController = controller

        try {
            Logger.log("Preparing VPN routes")
            controller.startLoop(ConfigParser.toXrayFullConfigJson(cfg), fd.fd)
            Logger.success("VPN service started")
        } catch (e: Exception) {
            Logger.error("Gagal start core: ${e.message}")
            updateNotification("Gagal start core: ${e.message}")
            stopVpn()
            return
        }

        ServerRepository.SessionStats.reset()
        isRunning = true
        statsHandler.post(statsTick)
        updateNotification("Terhubung ke ${cfg.name}")
    }

    /**
     * Susun VpnService.Builder sesuai beberapa toggle di Settings:
     *  - Enable IPV6 address -> tambah address+route IPv6 (kalau server juga support)
     *  - Bypass LAN route (VPN) -> excludeRoute() utk range privat (Android 13+/API 33 saja,
     *    API di bawah itu belum ada method-nya sama sekali jadi di-skip dengan log info)
     *  - Split tunnel -> addDisallowedApplication() utk tiap paket yang dipilih user
     */
    private fun buildVpnInterface(cfg: ServerConfig): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("OG Tunnel")
            .addAddress("10.10.10.2", 30)
            .setMtu(1500)

        if (Prefs.useDefaultNetworkDns) {
            // Sengaja TIDAK addDnsServer() sama sekali -> Android boleh pakai resolver
            // bawaan sistem. Ini best-effort; VpnService tetap bisa memaksa semua trafik UDP:53
            // masuk tunnel tergantung addRoute di bawah, jadi tidak selalu 100% "asli" default DNS.
        } else {
            runCatching { builder.addDnsServer(Prefs.dnsPrimary) }
            runCatching { builder.addDnsServer(Prefs.dnsSecondary) }
        }

        if (Prefs.enableIpv6) {
            runCatching {
                builder.addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 126)
                builder.addRoute("::", 0)
            }.onFailure { Logger.error("Gagal setup IPv6: ${it.message}") }
        }

        if (Prefs.bypassLanEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.addRoute("0.0.0.0", 0)
            LAN_RANGES.forEach { (addr, prefix) ->
                runCatching {
                    builder.excludeRoute(IpPrefix(InetAddresses.parseNumericAddress(addr), prefix))
                }
            }
            Logger.log("Bypass LAN route aktif (${LAN_RANGES.size} range dikecualikan)")
        } else {
            if (Prefs.bypassLanEnabled) {
                Logger.log("Bypass LAN route butuh Android 13+, dilewati di perangkat ini")
            }
            builder.addRoute("0.0.0.0", 0)
        }

        if (Prefs.splitTunnelEnabled) {
            Prefs.splitTunnelApps.forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
            }
            Logger.log("Split tunnel aktif, ${Prefs.splitTunnelApps.size} aplikasi dikecualikan dari VPN")
        }

        return builder.establish()
    }

    private fun stopVpn() {
        statsHandler.removeCallbacks(statsTick)
        if (isRunning) {
            runCatching { coreController?.stopLoop() }
        }
        coreController = null
        isRunning = false
        runCatching { tunFd?.close() }
        tunFd = null
        Logger.log("Service stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Sistem manggil ini kalau izin VPN dicabut (mis. user matiin dari Settings, atau app VPN lain ambil alih). */
    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun buildCallbackHandler(cfg: ServerConfig): CoreCallbackHandler = object : CoreCallbackHandler {
        override fun startup(): Long = 0

        override fun shutdown(): Long = 0

        override fun onEmitStatus(status: Long, msg: String?): Long {
            updateNotification(msg?.takeIf { it.isNotBlank() } ?: "Terhubung ke ${cfg.name}")
            return 0
        }
    }

    private fun pollStats() {
        val controller = coreController ?: return
        runCatching {
            val raw = controller.queryAllOutboundTrafficStats()
            applyStats(raw)
        }
    }

    /** Format dari Xray-core: "tag,direction,value;tag,direction,value;..." (bisa kosong). */
    private fun applyStats(raw: String) {
        if (raw.isBlank()) return
        var up = 0L
        var down = 0L
        raw.split(";").forEach { entry ->
            if (entry.isBlank()) return@forEach
            val parts = entry.split(",")
            if (parts.size != 3) return@forEach
            val value = parts[2].toLongOrNull() ?: return@forEach
            when (parts[1]) {
                "uplink" -> up += value
                "downlink" -> down += value
            }
        }
        ServerRepository.SessionStats.uploadBytes += up
        ServerRepository.SessionStats.downloadBytes += down
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        // NotificationCompat.Builder aman di semua API level yang didukung app ini (minSdk 24) —
        // dia sendiri yang menyesuaikan ke Notification.Builder asli sesuai API level di belakang
        // layar. Beda dari manggil android.app.Notification.Builder(ctx, channelId) langsung:
        // constructor 2-argumen itu baru ada dari API 26 dan bakal NoSuchMethodError di API 24-25.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OG Tunnel")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
