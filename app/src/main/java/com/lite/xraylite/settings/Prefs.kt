package com.lite.xraylite.settings

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings as AndroidSettings
import java.security.MessageDigest

/**
 * Satu tempat baca/tulis semua pengaturan aplikasi (dulunya cuma hardcode di kode).
 * Dipakai oleh SettingsActivity (lewat PreferenceFragmentCompat, key-key di sini HARUS
 * sama persis dengan `android:key` di res/xml/root_preferences.xml) dan oleh
 * ConfigParser/XrayVpnService/SshTunnelManager/MainActivity untuk baca nilai aktifnya.
 *
 * Beberapa key di sini cuma "placeholder" (disimpan tapi belum benar-benar mengubah
 * perilaku tunnel) karena butuh implementasi native yang lebih dalam (root, dsb) —
 * ditandai jelas di komentar masing-masing supaya tidak mengklaim fitur yang sebenarnya
 * belum jalan.
 */
object Prefs {
    private const val FILE = "og_tunnel_prefs"
    lateinit var sp: SharedPreferences
        private set

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    // ---- General ----
    var enableIpv6: Boolean
        get() = sp.getBoolean("enable_ipv6", false)
        set(v) = sp.edit().putBoolean("enable_ipv6", v).apply()

    var socksLocalPort: Int
        get() = sp.getInt("socks_local_port", 1080)
        set(v) = sp.edit().putInt("socks_local_port", v).apply()

    /** Placeholder: belum dihubungkan ke UDP forwarding manapun di SshTunnelManager. */
    var udpgwPort: Int
        get() = sp.getInt("udpgw_port", 0)
        set(v) = sp.edit().putInt("udpgw_port", v).apply()

    var splitTunnelEnabled: Boolean
        get() = sp.getBoolean("split_tunnel_enabled", false)
        set(v) = sp.edit().putBoolean("split_tunnel_enabled", v).apply()

    /** Paket aplikasi yang DIKECUALIKAN dari VPN saat split tunnel aktif. */
    var splitTunnelApps: MutableSet<String>
        get() = HashSet(sp.getStringSet("split_tunnel_apps", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("split_tunnel_apps", v).apply()

    /** Placeholder: butuh iptables/root, belum diimplementasikan. */
    var proxyTetheringEnabled: Boolean
        get() = sp.getBoolean("proxy_tethering_enabled", false)
        set(v) = sp.edit().putBoolean("proxy_tethering_enabled", v).apply()

    /** Placeholder: butuh root, belum diimplementasikan (sengaja di-disable di UI). */
    var vpnTetheringRootEnabled: Boolean
        get() = sp.getBoolean("vpn_tethering_root_enabled", false)
        set(v) = sp.edit().putBoolean("vpn_tethering_root_enabled", v).apply()

    var networkLogEnabled: Boolean
        get() = sp.getBoolean("network_log_enabled", false)
        set(v) = sp.edit().putBoolean("network_log_enabled", v).apply()

    /** Perlu Android 13+ (VpnService.Builder.excludeRoute). No-op di bawah itu. */
    var bypassLanEnabled: Boolean
        get() = sp.getBoolean("bypass_lan_enabled", false)
        set(v) = sp.edit().putBoolean("bypass_lan_enabled", v).apply()

    // ---- DNS ----
    var useDefaultNetworkDns: Boolean
        get() = sp.getBoolean("use_default_network_dns", false)
        set(v) = sp.edit().putBoolean("use_default_network_dns", v).apply()

    var dnsPrimary: String
        get() = sp.getString("dns_primary", "1.1.1.1") ?: "1.1.1.1"
        set(v) = sp.edit().putString("dns_primary", v).apply()

    var dnsSecondary: String
        get() = sp.getString("dns_secondary", "1.0.0.1") ?: "1.0.0.1"
        set(v) = sp.edit().putString("dns_secondary", v).apply()

    /** Placeholder: builder DNS resolution custom belum dipisah dari DNS sistem. */
    var resolveOutboundExternally: Boolean
        get() = sp.getBoolean("resolve_outbound_externally", false)
        set(v) = sp.edit().putBoolean("resolve_outbound_externally", v).apply()

    // ---- Xray options ----
    var fakeDnsEnabled: Boolean
        get() = sp.getBoolean("fakedns_enabled", false)
        set(v) = sp.edit().putBoolean("fakedns_enabled", v).apply()

    /** Placeholder: butuh inbound eksplisit dengan sniffing.enabled=true; mode TUN app ini
     * tidak mendefinisikan inbound sendiri (trafik masuk lewat startLoop(config, tunFd)
     * yang inbound-nya ditangani internal oleh AndroidLibXrayLite), jadi toggle ini belum
     * bisa dipastikan berefek nyata. Disimpan untuk paritas UI. */
    var contentSniffingEnabled: Boolean
        get() = sp.getBoolean("content_sniffing_enabled", false)
        set(v) = sp.edit().putBoolean("content_sniffing_enabled", v).apply()

    var allowInsecureTls: Boolean
        get() = sp.getBoolean("allow_insecure_tls", true)
        set(v) = sp.edit().putBoolean("allow_insecure_tls", v).apply()

    var enableMux: Boolean
        get() = sp.getBoolean("enable_mux", false)
        set(v) = sp.edit().putBoolean("enable_mux", v).apply()

    var tcpXudpConcurrency: Int
        get() = sp.getInt("tcp_xudp_concurrency", 8)
        set(v) = sp.edit().putInt("tcp_xudp_concurrency", v.coerceIn(-1, 1024)).apply()

    /** "reject" | "allow" | "skip" — persis field xudpProxyUDP443 di Xray-core. */
    var xudpQuicTraffic: String
        get() = sp.getString("xudp_quic_traffic", "reject") ?: "reject"
        set(v) = sp.edit().putString("xudp_quic_traffic", v).apply()

    var enableFragment: Boolean
        get() = sp.getBoolean("enable_fragment", false)
        set(v) = sp.edit().putBoolean("enable_fragment", v).apply()

    var fragmentPackets: String
        get() = sp.getString("fragment_packets", "tlshello") ?: "tlshello"
        set(v) = sp.edit().putString("fragment_packets", v).apply()

    var fragmentLength: String
        get() = sp.getString("fragment_length", "50-100") ?: "50-100"
        set(v) = sp.edit().putString("fragment_length", v).apply()

    var fragmentInterval: String
        get() = sp.getString("fragment_interval", "10-20") ?: "10-20"
        set(v) = sp.edit().putString("fragment_interval", v).apply()

    // ---- SSH options ----
    var sshWakeLockEnabled: Boolean
        get() = sp.getBoolean("ssh_wakelock_enabled", true)
        set(v) = sp.edit().putBoolean("ssh_wakelock_enabled", v).apply()

    var sshReconnectMaxAttempt: Int
        get() = sp.getInt("ssh_reconnect_max_attempt", 100)
        set(v) = sp.edit().putInt("ssh_reconnect_max_attempt", v).apply()

    var sshReconnectIntervalMs: Int
        get() = sp.getInt("ssh_reconnect_interval_ms", 3000)
        set(v) = sp.edit().putInt("ssh_reconnect_interval_ms", v).apply()

    /** "Auto" | "1.2" | "1.3" — dipakai sebagai tlsSettings.minVersion/maxVersion Xray
     * untuk koneksi VLESS/VMess/Trojan. Tidak berlaku untuk SSH murni (SshTunnelManager
     * tidak membungkus koneksinya dengan TLS). */
    var tlsVersion: String
        get() = sp.getString("tls_version", "Auto") ?: "Auto"
        set(v) = sp.edit().putString("tls_version", v).apply()

    /** Placeholder: sshj tidak mengekspos socket send/receive buffer size secara langsung
     * lewat API publik yang sudah dicek project ini, jadi disimpan tapi belum dipakai. */
    var sshSendBuffer: Int
        get() = sp.getInt("ssh_send_buffer", 16384)
        set(v) = sp.edit().putInt("ssh_send_buffer", v).apply()

    var sshReceiveBuffer: Int
        get() = sp.getInt("ssh_receive_buffer", 32768)
        set(v) = sp.edit().putInt("ssh_receive_buffer", v).apply()

    // ---- HTTP ping ----
    var httpPingAutoStart: Boolean
        get() = sp.getBoolean("http_ping_autostart", true)
        set(v) = sp.edit().putBoolean("http_ping_autostart", v).apply()

    var httpPingAddress: String
        get() = sp.getString("http_ping_address", "") ?: ""
        set(v) = sp.edit().putString("http_ping_address", v).apply()

    fun httpPingUrlOrDefault(): String =
        httpPingAddress.trim().ifBlank { "https://cp.cloudflare.com/generate_204" }

    // ---- Appearance ----
    /** "dark" | "light" | "system" */
    var theme: String
        get() = sp.getString("theme", "dark") ?: "dark"
        set(v) = sp.edit().putString("theme", v).apply()

    // ---- List UI (bukan bagian dari layar Settings, tapi disimpan di sini juga) ----
    /** "list" | "grid" — dipakai MainActivity utk toggle "View as". */
    var listViewMode: String
        get() = sp.getString("list_view_mode", "list") ?: "list"
        set(v) = sp.edit().putString("list_view_mode", v).apply()

    /** "name" | "ping" | "type" — dipakai MainActivity utk "Sort by". */
    var sortMode: String
        get() = sp.getString("sort_mode", "name") ?: "name"
        set(v) = sp.edit().putString("sort_mode", v).apply()

    /**
     * ID perangkat yang stabil, diturunkan dari ANDROID_ID (di-hash SHA-256 lalu
     * diambil 20 karakter hex pertama) supaya tidak menampilkan ANDROID_ID mentah.
     * Ditampilkan read-only di Settings > Hardware ID, mirip fitur sejenis di app VPN lain.
     */
    fun hardwareId(context: Context): String {
        val androidId = AndroidSettings.Secure.getString(
            context.contentResolver, AndroidSettings.Secure.ANDROID_ID
        ) ?: "unknown"
        val digest = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray())
        return digest.joinToString("") { "%02X".format(it) }.take(20)
    }
}
