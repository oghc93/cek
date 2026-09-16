package com.lite.xraylite.config

import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.lite.xraylite.model.ConnectionType
import com.lite.xraylite.model.ServerConfig
import com.lite.xraylite.settings.Prefs
import java.util.UUID

/**
 * Parser link share standar komunitas Xray:
 *  - vless://uuid@host:port?type=ws&path=/&host=xxx&security=tls&sni=xxx&fp=chrome#nama
 *  - vless://uuid@host:port?type=tcp&security=reality&sni=xxx&pbk=xxx&sid=xxx&fp=chrome&flow=xtls-rprx-vision#nama
 *  - trojan://password@host:port?type=ws&path=/&security=tls&sni=xxx#nama
 *  - vmess://<base64 JSON>
 *  - ssh://username:password@host:port#nama   (SSH murni, bukan lewat Xray;
 *    untuk private-key auth pakai dialog "Tambah SSH manual" di layar utama,
 *    bukan format link ini)
 */
object ConfigParser {

    fun parse(link: String): ServerConfig? = when {
        link.startsWith("vless://") -> parseVlessOrTrojan(link, ConnectionType.VLESS)
        link.startsWith("trojan://") -> parseVlessOrTrojan(link, ConnectionType.TROJAN)
        link.startsWith("vmess://") -> parseVmess(link)
        link.startsWith("ssh://") -> parseSsh(link)
        else -> null
    }

    private fun parseVlessOrTrojan(link: String, type: ConnectionType): ServerConfig? {
        return try {
            val uri = Uri.parse(link)
            val userInfo = uri.userInfo ?: return null // uuid (vless) atau password (trojan)
            val host = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else 443
            val name = uri.fragment?.let { Uri.decode(it) } ?: host

            ServerConfig(
                id = UUID.randomUUID().toString(),
                name = name,
                type = type,
                address = host,
                port = port,
                uuidOrPassword = userInfo,
                network = uri.getQueryParameter("type") ?: "ws",
                wsPath = uri.getQueryParameter("path") ?: "/",
                wsHost = uri.getQueryParameter("host") ?: host,
                security = uri.getQueryParameter("security") ?: "tls",
                sni = uri.getQueryParameter("sni") ?: host,
                alpn = uri.getQueryParameter("alpn") ?: "",
                fingerprint = uri.getQueryParameter("fp") ?: "",
                allowInsecure = uri.getQueryParameter("allowInsecure") == "1",
                flow = uri.getQueryParameter("flow") ?: "",
                realityPublicKey = uri.getQueryParameter("pbk") ?: "",
                realityShortId = uri.getQueryParameter("sid") ?: "",
                realitySpiderX = uri.getQueryParameter("spx") ?: ""
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVmess(link: String): ServerConfig? {
        return try {
            val payload = link.removePrefix("vmess://")
            val decoded = String(Base64.decode(payload, Base64.DEFAULT))
            val json = Gson().fromJson(decoded, JsonObject::class.java)

            ServerConfig(
                id = UUID.randomUUID().toString(),
                name = json.get("ps")?.asString ?: "vmess",
                type = ConnectionType.VMESS,
                address = json.get("add")?.asString ?: return null,
                port = json.get("port")?.asString?.toIntOrNull() ?: 443,
                uuidOrPassword = json.get("id")?.asString ?: return null,
                network = json.get("net")?.asString ?: "ws",
                wsPath = json.get("path")?.asString ?: "/",
                wsHost = json.get("host")?.asString ?: "",
                security = if ((json.get("tls")?.asString ?: "") == "tls") "tls" else "none",
                sni = json.get("sni")?.asString ?: (json.get("host")?.asString ?: ""),
                alpn = json.get("alpn")?.asString ?: "",
                fingerprint = json.get("fp")?.asString ?: "",
                allowInsecure = false,
                flow = ""
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSsh(link: String): ServerConfig? {
        return try {
            val uri = Uri.parse(link)
            val host = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else 22
            val userInfo = uri.userInfo ?: return null
            val (user, pass) = userInfo.split(":", limit = 2).let {
                it.getOrElse(0) { "" } to it.getOrElse(1) { "" }
            }
            if (user.isBlank()) return null
            val name = uri.fragment?.let { Uri.decode(it) } ?: host

            ServerConfig(
                id = UUID.randomUUID().toString(),
                name = name,
                type = ConnectionType.SSH,
                address = host,
                port = port,
                sshUsername = user,
                sshPassword = pass,
                localSocksPort = uri.getQueryParameter("localPort")?.toIntOrNull() ?: 1080
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Bangun blok "settings" outbound Xray-core, KHUSUS sesuai protokol (vless/vmess
     * pakai "vnext", trojan pakai "servers" — tidak digabung asal-asalan seperti versi
     * sebelumnya). Semua string lewat JsonObject/Gson jadi otomatis di-escape dengan benar,
     * tidak ada lagi risiko JSON rusak kalau uuid/password/path mengandung karakter `"`.
     */
    private fun buildOutboundSettings(cfg: ServerConfig): JsonObject {
        val settings = JsonObject()
        when (cfg.type) {
            ConnectionType.VLESS -> {
                val user = JsonObject().apply {
                    addProperty("id", cfg.uuidOrPassword)
                    addProperty("encryption", "none")
                    if (cfg.flow.isNotBlank()) addProperty("flow", cfg.flow)
                }
                val vnext = JsonObject().apply {
                    addProperty("address", cfg.address)
                    addProperty("port", cfg.port)
                    add("users", JsonArray().apply { add(user) })
                }
                settings.add("vnext", JsonArray().apply { add(vnext) })
            }
            ConnectionType.VMESS -> {
                val user = JsonObject().apply {
                    addProperty("id", cfg.uuidOrPassword)
                    addProperty("alterId", 0)
                }
                val vnext = JsonObject().apply {
                    addProperty("address", cfg.address)
                    addProperty("port", cfg.port)
                    add("users", JsonArray().apply { add(user) })
                }
                settings.add("vnext", JsonArray().apply { add(vnext) })
            }
            ConnectionType.TROJAN -> {
                val server = JsonObject().apply {
                    addProperty("address", cfg.address)
                    addProperty("port", cfg.port)
                    addProperty("password", cfg.uuidOrPassword)
                }
                settings.add("servers", JsonArray().apply { add(server) })
            }
            ConnectionType.SSH -> throw IllegalArgumentException("SSH bukan outbound Xray")
        }
        return settings
    }

    private fun buildStreamSettings(cfg: ServerConfig): JsonObject {
        val streamSettings = JsonObject()
        streamSettings.addProperty("network", cfg.network)
        streamSettings.addProperty("security", cfg.security)

        when (cfg.network) {
            "ws" -> {
                val wsSettings = JsonObject().apply {
                    addProperty("path", cfg.wsPath)
                    add("headers", JsonObject().apply { addProperty("Host", cfg.wsHost) })
                }
                streamSettings.add("wsSettings", wsSettings)
            }
            "grpc" -> {
                val grpcSettings = JsonObject().apply {
                    addProperty("serviceName", cfg.wsPath.trim('/'))
                }
                streamSettings.add("grpcSettings", grpcSettings)
            }
            // "tcp" tidak butuh blok settings tambahan
        }

        when (cfg.security) {
            "tls" -> {
                val tlsSettings = JsonObject().apply {
                    addProperty("serverName", cfg.sni)
                    // Settings > Xray options > "Allow insecure (TLS)" berlaku GLOBAL: kalau
                    // dinyalakan di sana, override nilai per-server (OR, bukan AND) supaya
                    // konsisten dengan perilaku toggle-nya di NetMod (satu switch utk semua server).
                    addProperty("allowInsecure", cfg.allowInsecure || Prefs.allowInsecureTls)
                    if (cfg.fingerprint.isNotBlank()) addProperty("fingerprint", cfg.fingerprint)
                    if (cfg.alpn.isNotBlank()) {
                        add("alpn", JsonArray().apply { cfg.alpn.split(",").forEach { add(it.trim()) } })
                    }
                    // Settings > SSH options > "TLS version" (dipakai juga di sini walau
                    // kategorinya "SSH options" di UI, karena SSH murni project ini tidak
                    // dibungkus TLS sama sekali — jadi satu-satunya tempat setting ini
                    // benar-benar berefek adalah koneksi Xray yang pakai security=tls).
                    when (Prefs.tlsVersion) {
                        "1.2" -> { addProperty("minVersion", "1.2"); addProperty("maxVersion", "1.2") }
                        "1.3" -> { addProperty("minVersion", "1.3"); addProperty("maxVersion", "1.3") }
                        // "Auto" -> tidak diisi, biarkan Xray-core pakai default-nya
                    }
                }
                streamSettings.add("tlsSettings", tlsSettings)
            }
            "reality" -> {
                // Skema field ini (serverName/fingerprint/publicKey/shortId/spiderX) adalah
                // format outbound realitySettings resmi Xray-core, bukan tebakan.
                val realitySettings = JsonObject().apply {
                    addProperty("serverName", cfg.sni)
                    // "chrome" dipakai sebagai default kalau link tidak menyertakan fp= — Reality
                    // WAJIB fingerprint yang valid (tidak boleh kosong) beda dari TLS biasa.
                    addProperty("fingerprint", cfg.fingerprint.ifBlank { "chrome" })
                    addProperty("publicKey", cfg.realityPublicKey)
                    if (cfg.realityShortId.isNotBlank()) addProperty("shortId", cfg.realityShortId)
                    if (cfg.realitySpiderX.isNotBlank()) addProperty("spiderX", cfg.realitySpiderX)
                }
                streamSettings.add("realitySettings", realitySettings)
            }
            // "none" -> tidak perlu blok tambahan
        }

        // Settings > Xray options > "Enable Fragment": cara resminya di Xray-core BUKAN
        // field di streamSettings, tapi lewat outbound "freedom" terpisah bertag "fragment"
        // yang di-chain lewat sockopt.dialerProxy (dicek ke PR resmi XTLS/Xray-core#2021 +
        // dipakai luas di v2rayNG). Outbound "fragment"-nya sendiri dibuat di
        // toXrayFullConfigJson().
        if (Prefs.enableFragment) {
            streamSettings.add("sockopt", JsonObject().apply {
                addProperty("dialerProxy", "fragment")
            })
        }
        return streamSettings
    }

    /** Satu blok "outbound" Xray-core untuk [cfg], dengan tag "proxy". */
    private fun buildProxyOutbound(cfg: ServerConfig): JsonObject {
        val protocol = when (cfg.type) {
            ConnectionType.VLESS -> "vless"
            ConnectionType.VMESS -> "vmess"
            ConnectionType.TROJAN -> "trojan"
            ConnectionType.SSH -> throw IllegalArgumentException("SSH bukan outbound Xray")
        }
        return JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", protocol)
            add("settings", buildOutboundSettings(cfg))
            add("streamSettings", buildStreamSettings(cfg))
            // Settings > Xray options > "Enable Mux" + "TCP/XUDP Concurrency" + "XUDP QUIC
            // traffic" — field mux ini SIBLING dari streamSettings di level outbound (bukan
            // di dalamnya), formatnya dicek ke docs resmi Xray-core (xtls.github.io/config).
            if (Prefs.enableMux) {
                add("mux", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("concurrency", Prefs.tcpXudpConcurrency)
                    addProperty("xudpConcurrency", Prefs.tcpXudpConcurrency)
                    addProperty("xudpProxyUDP443", Prefs.xudpQuicTraffic)
                })
            }
        }
    }

    /**
     * Config Xray-core lengkap siap dikirim ke `CoreController.startLoop(configContent, tunFd)`
     * dari AndroidLibXrayLite. Tidak ada "inbounds" karena trafik masuk lewat TUN fd yang
     * dikasih langsung ke StartLoop (AndroidLibXrayLite versi sekarang sudah handle TUN
     * secara internal, tidak perlu inbound SOCKS + tun2socks terpisah lagi).
     *
     * "stats" + "policy.system.statsOutbound*" diaktifkan supaya
     * `CoreController.queryAllOutboundTrafficStats()` benar-benar mengembalikan angka
     * (dipakai XrayVpnService buat isi counter upload/download di layar utama).
     */
    fun toXrayFullConfigJson(cfg: ServerConfig): String {
        val root = JsonObject()

        root.add("log", JsonObject().apply { addProperty("loglevel", "warning") })
        root.add("stats", JsonObject())
        root.add("policy", JsonObject().apply {
            add("system", JsonObject().apply {
                addProperty("statsOutboundUplink", true)
                addProperty("statsOutboundDownlink", true)
            })
        })
        root.add("inbounds", JsonArray())

        // Settings > Xray options > "FakeDNS": daftar server DNS berisi entri khusus
        // "fakedns" di posisi pertama (dokumentasi resmi Xray-core), plus blok top-level
        // "fakedns" berisi ip pool-nya. DNS primer/sekunder dari Settings > DNS dipakai
        // sebagai server DNS asli setelahnya.
        val dnsServers = JsonArray().apply {
            if (Prefs.fakeDnsEnabled) add("fakedns")
            if (Prefs.dnsPrimary.isNotBlank()) add(Prefs.dnsPrimary)
            if (Prefs.dnsSecondary.isNotBlank()) add(Prefs.dnsSecondary)
        }
        root.add("dns", JsonObject().apply { add("servers", dnsServers) })
        if (Prefs.fakeDnsEnabled) {
            root.add("fakedns", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("ipPool", "198.18.0.0/16")
                    addProperty("poolSize", 65535)
                })
            })
        }

        val outbounds = JsonArray().apply {
            add(buildProxyOutbound(cfg))
            // Settings > Xray options > "Enable Fragment": outbound "freedom" terpisah
            // bertag "fragment", dirujuk lewat sockopt.dialerProxy di outbound "proxy" di
            // atas (lihat buildStreamSettings). Kalau fitur ini tidak aktif, outbound ini
            // tidak dibuat sama sekali supaya config tetap bersih.
            if (Prefs.enableFragment) {
                add(JsonObject().apply {
                    addProperty("tag", "fragment")
                    addProperty("protocol", "freedom")
                    add("settings", JsonObject().apply {
                        add("fragment", JsonObject().apply {
                            addProperty("packets", Prefs.fragmentPackets)
                            addProperty("length", Prefs.fragmentLength)
                            addProperty("interval", Prefs.fragmentInterval)
                        })
                    })
                })
            }
            add(JsonObject().apply { addProperty("protocol", "freedom"); addProperty("tag", "direct") })
            add(JsonObject().apply { addProperty("protocol", "blackhole"); addProperty("tag", "block") })
        }
        root.add("outbounds", outbounds)

        // Tidak ada "rules" eksplisit -> semua trafik default ke outbound pertama ("proxy").
        root.add("routing", JsonObject().apply {
            addProperty("domainStrategy", "AsIs")
            add("rules", JsonArray())
        })

        return Gson().toJson(root)
    }
}
