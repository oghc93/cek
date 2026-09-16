package com.lite.xraylite.model

/** Jenis koneksi yang didukung. SSH di sini = SSH murni (port-forward), bukan Xray. */
enum class ConnectionType { VLESS, VMESS, TROJAN, SSH }

/**
 * Representasi satu profil server. Field yang tidak relevan untuk suatu
 * ConnectionType boleh dibiarkan default (misal password tidak dipakai VLESS).
 */
data class ServerConfig(
    val id: String,
    val name: String,
    val type: ConnectionType,
    val address: String,
    val port: Int,

    // Xray (VLESS/VMess/Trojan) fields
    val uuidOrPassword: String = "",
    val network: String = "ws",          // ws, tcp, grpc
    val wsPath: String = "/",
    val wsHost: String = "",
    val security: String = "tls",        // none, tls, reality
    val sni: String = "",
    val alpn: String = "",
    val fingerprint: String = "",        // uTLS fingerprint: chrome/firefox/safari/ios/android/random (param "fp")
    val allowInsecure: Boolean = false,
    val flow: String = "",               // dipakai VLESS (xtls-rprx-vision dll)

    // REALITY fields (dipakai kalau security == "reality")
    val realityPublicKey: String = "",   // param "pbk"
    val realityShortId: String = "",     // param "sid"
    val realitySpiderX: String = "",     // param "spx"

    // SSH murni fields
    val sshUsername: String = "",
    val sshPassword: String = "",
    val sshPrivateKeyPem: String = "",
    val localSocksPort: Int = 1080
)
