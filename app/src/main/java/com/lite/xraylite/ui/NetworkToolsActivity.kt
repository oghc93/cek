package com.lite.xraylite.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lite.xraylite.R
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

/**
 * Satu Activity buat 3 tool jaringan ringan yang ada di drawer menu ("What's my IP?",
 * "Host to IP", "Host checker") — dibuat satu kelas supaya tidak menduplikasi
 * boilerplate input+button+result 3x. Mode ditentukan lewat EXTRA_MODE.
 */
class NetworkToolsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_WHATS_MY_IP = "whats_my_ip"
        const val MODE_HOST_TO_IP = "host_to_ip"
        const val MODE_HOST_CHECKER = "host_checker"
    }

    private val executor = Executors.newCachedThreadPool()
    private lateinit var etInput: EditText
    private lateinit var btnRun: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvResult: TextView
    private lateinit var mode: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_tools)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_WHATS_MY_IP

        val toolbar = findViewById<Toolbar>(R.id.toolbarTools)
        toolbar.title = titleFor(mode)
        toolbar.setNavigationOnClickListener { finish() }

        etInput = findViewById(R.id.etToolInput)
        btnRun = findViewById(R.id.btnToolRun)
        progress = findViewById(R.id.progressTool)
        tvResult = findViewById(R.id.tvToolResult)

        when (mode) {
            MODE_WHATS_MY_IP -> {
                etInput.visibility = View.GONE
                btnRun.text = "Check my IP"
            }
            MODE_HOST_TO_IP -> {
                etInput.hint = "Hostname, mis. google.com"
                btnRun.text = "Resolve"
            }
            MODE_HOST_CHECKER -> {
                etInput.hint = "host:port, mis. idn.dontol.ccwu.cc:443"
                btnRun.text = "Check host"
            }
        }

        btnRun.setOnClickListener { run() }
        if (mode == MODE_WHATS_MY_IP) run()
    }

    private fun titleFor(mode: String) = when (mode) {
        MODE_WHATS_MY_IP -> "What's my IP?"
        MODE_HOST_TO_IP -> "Host to IP"
        MODE_HOST_CHECKER -> "Host checker"
        else -> "Network tools"
    }

    private fun run() {
        when (mode) {
            MODE_WHATS_MY_IP -> runWhatsMyIp()
            MODE_HOST_TO_IP -> runHostToIp(etInput.text.toString().trim())
            MODE_HOST_CHECKER -> runHostChecker(etInput.text.toString().trim())
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnRun.isEnabled = !busy
    }

    private fun runWhatsMyIp() {
        setBusy(true)
        tvResult.text = ""
        executor.execute {
            val result = try {
                val conn = URL("https://ipapi.co/json/").openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json: JsonObject = JsonParser.parseString(body).asJsonObject
                buildString {
                    appendLine("IP       : ${json.get("ip")?.asString ?: "-"}")
                    appendLine("Kota     : ${json.get("city")?.asString ?: "-"}")
                    appendLine("Wilayah  : ${json.get("region")?.asString ?: "-"}")
                    appendLine("Negara   : ${json.get("country_name")?.asString ?: "-"}")
                    append("ISP      : ${json.get("org")?.asString ?: "-"}")
                }
            } catch (e: Exception) {
                "Gagal ambil info IP: ${e.message}"
            }
            runOnUiThread {
                setBusy(false)
                tvResult.text = result
            }
        }
    }

    private fun runHostToIp(host: String) {
        if (host.isBlank()) {
            tvResult.text = "Isi hostname dulu"
            return
        }
        setBusy(true)
        tvResult.text = ""
        executor.execute {
            val result = try {
                val addrs = java.net.InetAddress.getAllByName(host)
                addrs.joinToString("\n") { it.hostAddress ?: it.toString() }
            } catch (e: Exception) {
                "Gagal resolve $host: ${e.message}"
            }
            runOnUiThread {
                setBusy(false)
                tvResult.text = result
            }
        }
    }

    private fun runHostChecker(input: String) {
        if (input.isBlank()) {
            tvResult.text = "Isi host:port dulu"
            return
        }
        val (host, port) = if (input.contains(":")) {
            val parts = input.split(":", limit = 2)
            parts[0] to (parts[1].toIntOrNull() ?: 443)
        } else {
            input to 443
        }
        setBusy(true)
        tvResult.text = ""
        executor.execute {
            val start = System.currentTimeMillis()
            val result = try {
                Socket().use { it.connect(InetSocketAddress(host, port), 5000) }
                val elapsed = System.currentTimeMillis() - start
                "OK — $host:$port terjangkau (${elapsed}ms)"
            } catch (e: Exception) {
                "GAGAL — $host:$port tidak terjangkau: ${e.message}"
            }
            runOnUiThread {
                setBusy(false)
                tvResult.text = result
            }
        }
    }
}
