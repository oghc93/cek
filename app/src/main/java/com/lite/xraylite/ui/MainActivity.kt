package com.lite.xraylite.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.navigation.NavigationView
import com.lite.xraylite.BuildConfig
import com.lite.xraylite.R
import com.lite.xraylite.config.ConfigParser
import com.lite.xraylite.config.ServerRepository
import com.lite.xraylite.databinding.ActivityMainBinding
import com.lite.xraylite.model.ConnectionType
import com.lite.xraylite.model.ServerConfig
import com.lite.xraylite.settings.Prefs
import com.lite.xraylite.ssh.SshTunnelManager
import com.lite.xraylite.util.HttpPingTester
import com.lite.xraylite.util.Logger
import com.lite.xraylite.vpn.XrayVpnService
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ServerListAdapter
    private lateinit var drawerLayout: DrawerLayout
    private var connected = false
    private lateinit var sshManager: SshTunnelManager
    private val uiHandler = Handler(Looper.getMainLooper())

    // Tick tiap detik untuk update durasi + counter upload/download dari
    // ServerRepository.SessionStats (diisi XrayVpnService lewat stats API asli
    // Xray-core, atau nanti bisa disambung ke counter socket SSH juga).
    private val monitorTick = object : Runnable {
        override fun run() {
            if (connected) {
                val elapsedMs = System.currentTimeMillis() - ServerRepository.SessionStats.connectedSinceMs
                binding.tvDuration.text = formatDuration(elapsedMs)
                binding.tvUpload.text = formatBytes(ServerRepository.SessionStats.uploadBytes)
                binding.tvDownload.text = formatBytes(ServerRepository.SessionStats.downloadBytes)
            }
            uiHandler.postDelayed(this, 1000)
        }
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) startTunnel()
        }

    /** "+ Import from file": ambil file teks berisi link (satu link per baris). */
    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importFromFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sshManager = SshTunnelManager(applicationContext)

        drawerLayout = findViewById(R.id.drawerLayout)
        setupToolbarAndDrawer()

        adapter = ServerListAdapter { cfg -> onServerSelected(cfg) }
        applyListViewMode()
        binding.rvServers.adapter = adapter

        intent?.data?.toString()?.let { handleIncomingLink(it) }

        binding.btnImportLink.setOnClickListener { showImportLinkDialog() }
        binding.btnAddSsh.setOnClickListener { showAddSshDialog() }
        binding.btnConnect.setOnClickListener { toggleConnection() }

        refreshList()
        uiHandler.post(monitorTick)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(monitorTick)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Toolbar + drawer + menu overflow
    // ---------------------------------------------------------------------

    private fun setupToolbarAndDrawer() {
        val toolbar = findViewById<Toolbar>(R.id.toolbarMain)
        toolbar.setNavigationOnClickListener { drawerLayout.openDrawer(Gravity.START) }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.actionImportFile -> { importFileLauncher.launch("text/plain"); true }
                R.id.actionSortBy -> { showSortByDialog(); true }
                R.id.actionViewAs -> { showViewAsDialog(); true }
                R.id.actionPingTool -> { pingAllServers(); true }
                R.id.actionFindSelected -> { scrollToActiveServer(); true }
                else -> false
            }
        }

        val navView = findViewById<NavigationView>(R.id.navView)
        navView.getHeaderView(0)?.findViewById<android.widget.TextView>(R.id.tvNavVersion)?.text =
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        navView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawers()
            when (item.itemId) {
                R.id.navWhatsMyIp -> launchTool(NetworkToolsActivity.MODE_WHATS_MY_IP)
                R.id.navHostToIp -> launchTool(NetworkToolsActivity.MODE_HOST_TO_IP)
                R.id.navHostChecker -> launchTool(NetworkToolsActivity.MODE_HOST_CHECKER)
                R.id.navShowLogs -> startActivity(Intent(this, LogActivity::class.java))
                R.id.navSettings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.navAbout -> startActivity(Intent(this, AboutActivity::class.java))
                R.id.navShare -> shareApp()
            }
            true
        }
    }

    private fun launchTool(mode: String) {
        startActivity(Intent(this, NetworkToolsActivity::class.java).putExtra(NetworkToolsActivity.EXTRA_MODE, mode))
    }

    private fun shareApp() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Saya pakai OG Tunnel buat konek ke VPS pribadi saya.")
        }
        startActivity(Intent.createChooser(intent, "Share OG Tunnel"))
    }

    private fun showSortByDialog() {
        val entries = resources.getStringArray(R.array.sort_mode_entries)
        val values = resources.getStringArray(R.array.sort_mode_values)
        val current = values.indexOf(Prefs.sortMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(entries, current) { dialog, which ->
                Prefs.sortMode = values[which]
                refreshList()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showViewAsDialog() {
        val options = arrayOf("List", "Grid")
        val current = if (Prefs.listViewMode == "grid") 1 else 0
        AlertDialog.Builder(this)
            .setTitle("View as")
            .setSingleChoiceItems(options, current) { dialog, which ->
                Prefs.listViewMode = if (which == 1) "grid" else "list"
                applyListViewMode()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun applyListViewMode() {
        binding.rvServers.layoutManager = if (Prefs.listViewMode == "grid") {
            GridLayoutManager(this, 2)
        } else {
            LinearLayoutManager(this)
        }
    }

    /** Ping ulang SEMUA server (bukan cuma yang lagi kelihatan di layar) lalu refresh badge-nya. */
    private fun pingAllServers() {
        val all = ServerRepository.all()
        if (all.isEmpty()) {
            Toast.makeText(this, "Belum ada server", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Nge-ping ${all.size} server...", Toast.LENGTH_SHORT).show()
        all.forEach { cfg ->
            com.lite.xraylite.util.PingTester.test(cfg) { success, ms ->
                ServerRepository.setPing(cfg.id, if (success) ms.toInt() else -1)
                runOnUiThread { refreshList() }
            }
        }
    }

    /** "Find selected": scroll ke server yang lagi aktif/dipilih. */
    private fun scrollToActiveServer() {
        val activeId = ServerRepository.activeId
        if (activeId == null) {
            Toast.makeText(this, "Belum ada server yang dipilih", Toast.LENGTH_SHORT).show()
            return
        }
        val pos = ServerRepository.all().indexOfFirst { it.id == activeId }
        if (pos >= 0) binding.rvServers.scrollToPosition(pos)
    }

    private fun importFromFile(uri: android.net.Uri) {
        val lines = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.readLines() ?: emptyList()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal baca file: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        var imported = 0
        lines.map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            ConfigParser.parse(line)?.let {
                ServerRepository.add(it)
                imported++
            }
        }
        Toast.makeText(this, "$imported dari ${lines.size} baris berhasil diimport", Toast.LENGTH_LONG).show()
        Logger.log("Import from file: $imported/${lines.size} config berhasil ditambahkan")
        if (ServerRepository.activeId == null) ServerRepository.all().firstOrNull()?.let { ServerRepository.setActive(it.id) }
        refreshList()
    }

    // ---------------------------------------------------------------------
    // Daftar server + koneksi (logika lama, ditambah sort + logging)
    // ---------------------------------------------------------------------

    private fun refreshList() {
        adapter.submit(sortedServers(), ServerRepository.activeId)
        val active = ServerRepository.activeConfig()
        binding.tvServerName.text = active?.let { "${it.name} (${it.type})" } ?: "Belum ada server dipilih"
    }

    private fun sortedServers(): List<ServerConfig> {
        val all = ServerRepository.all()
        return when (Prefs.sortMode) {
            "ping" -> all.sortedBy { ServerRepository.getPing(it.id) ?: Int.MAX_VALUE }
            "type" -> all.sortedBy { it.type.name }
            else -> all.sortedBy { it.name.lowercase() }
        }
    }

    private fun onServerSelected(cfg: ServerConfig) {
        if (connected) return // jangan ganti server saat masih terhubung
        ServerRepository.setActive(cfg.id)
        refreshList()
    }

    private fun showImportLinkDialog() {
        val input = EditText(this).apply {
            hint = "Tempel link vless:// vmess:// trojan://"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Import link")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ -> handleIncomingLink(input.text.toString().trim()) }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Form manual buat SSH murni (host/port/user + password ATAU private key PEM). */
    private fun showAddSshDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val etHost = EditText(this).apply { hint = "Host / IP VPS" }
        val etPort = EditText(this).apply {
            hint = "Port SSH (default 22)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val etUser = EditText(this).apply { hint = "Username" }
        val etPass = EditText(this).apply {
            hint = "Password (kosongkan kalau pakai private key)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etKey = EditText(this).apply {
            hint = "Private key PEM (opsional, kosongkan kalau pakai password)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            isSingleLine = false
        }
        val etLocalPort = EditText(this).apply {
            hint = "Local SOCKS port (default ${Prefs.socksLocalPort})"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            listOf(etHost, etPort, etUser, etPass, etKey, etLocalPort).forEach {
                addView(it)
                (it.layoutParams as LinearLayout.LayoutParams).topMargin = pad / 2
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Tambah server SSH murni")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Simpan") { _, _ ->
                val host = etHost.text.toString().trim()
                val user = etUser.text.toString().trim()
                if (host.isEmpty() || user.isEmpty()) {
                    binding.tvServerName.text = "Host & username wajib diisi"
                    return@setPositiveButton
                }
                val cfg = ServerConfig(
                    id = UUID.randomUUID().toString(),
                    name = host,
                    type = ConnectionType.SSH,
                    address = host,
                    port = etPort.text.toString().toIntOrNull() ?: 22,
                    sshUsername = user,
                    sshPassword = etPass.text.toString(),
                    sshPrivateKeyPem = etKey.text.toString().trim(),
                    localSocksPort = etLocalPort.text.toString().toIntOrNull() ?: Prefs.socksLocalPort
                )
                ServerRepository.add(cfg)
                if (ServerRepository.activeId == null) ServerRepository.setActive(cfg.id)
                refreshList()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun handleIncomingLink(link: String) {
        val cfg = ConfigParser.parse(link)
        if (cfg == null) {
            binding.tvServerName.text = "Link tidak valid"
            return
        }
        ServerRepository.add(cfg)
        if (ServerRepository.activeId == null) ServerRepository.setActive(cfg.id)
        refreshList()
    }

    private fun toggleConnection() {
        val cfg = ServerRepository.activeConfig()
        if (cfg == null) {
            binding.tvServerName.text = "Pilih atau import server dulu"
            return
        }
        if (connected) {
            stopTunnel(cfg)
        } else {
            Logger.log("Menghubungkan ke ${cfg.name} (${cfg.type})...")
            when (cfg.type) {
                ConnectionType.SSH -> startSshTunnel(cfg)
                else -> requestVpnPermissionThenStart()
            }
        }
    }

    private fun requestVpnPermissionThenStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startTunnel()
    }

    private fun startTunnel() {
        val cfg = ServerRepository.activeConfig() ?: return
        val intent = Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_CONNECT
            putExtra(XrayVpnService.EXTRA_CONFIG_ID, cfg.id)
        }
        startForegroundService(intent)
        ServerRepository.SessionStats.reset()
        setConnectedUi(true)
        HttpPingTester.start()
    }

    private fun startSshTunnel(cfg: ServerConfig) {
        sshManager.connect(
            cfg,
            onConnected = {
                runOnUiThread {
                    ServerRepository.SessionStats.reset()
                    setConnectedUi(true)
                    HttpPingTester.start()
                    Logger.success("SSH tersambung ke ${cfg.address}:${cfg.port}, SOCKS lokal di 127.0.0.1:${cfg.localSocksPort}")
                }
            },
            onError = { err ->
                runOnUiThread {
                    binding.tvStatus.text = "Gagal: ${err.message}"
                    setConnectedUi(false)
                    Logger.error("SSH gagal konek: ${err.message}")
                }
            }
        )
    }

    private fun stopTunnel(cfg: ServerConfig) {
        HttpPingTester.stop()
        if (cfg.type == ConnectionType.SSH) {
            sshManager.disconnect()
        } else {
            startService(Intent(this, XrayVpnService::class.java).apply {
                action = XrayVpnService.ACTION_DISCONNECT
            })
        }
        Logger.log("Terputus dari ${cfg.name}")
        setConnectedUi(false)
    }

    private fun setConnectedUi(isConnected: Boolean) {
        connected = isConnected
        binding.tvStatus.text = if (isConnected) "Terhubung" else "Terputus"
        binding.btnConnect.text = if (isConnected) "DISCONNECT" else "CONNECT"
        if (!isConnected) {
            binding.tvDuration.text = "00:00:00"
            binding.tvUpload.text = "0 KB"
            binding.tvDownload.text = "0 KB"
        }
        refreshList()
    }

    private fun formatDuration(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
    }
}
