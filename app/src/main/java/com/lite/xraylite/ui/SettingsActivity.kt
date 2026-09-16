package com.lite.xraylite.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.lite.xraylite.R
import com.lite.xraylite.settings.Prefs

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<Toolbar>(R.id.toolbarSettings).setNavigationOnClickListener { finish() }
        findViewById<Toolbar>(R.id.toolbarSettings).navigationIcon =
            ContextCompat.getDrawable(this, android.R.drawable.ic_menu_close_clear_cancel)
        supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        @SuppressLint("ApplySharedPref")
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // WAJIB disamakan dengan nama file di Prefs.kt (og_tunnel_prefs), kalau tidak
            // PreferenceFragmentCompat akan baca/tulis ke SharedPreferences default yang
            // beda file-nya dari yang dipakai Prefs.kt di seluruh app.
            preferenceManager.sharedPreferencesName = "og_tunnel_prefs"
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            setupHardwareId()
            setupBatteryOptimization()
            setupSplitTunnelPicker()
            setupThemeListener()
        }

        private fun setupHardwareId() {
            val pref = findPreference<Preference>("hardware_id") ?: return
            val id = Prefs.hardwareId(requireContext())
            pref.summary = id
            pref.setOnPreferenceClickListener {
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Hardware ID", id))
                Toast.makeText(requireContext(), "Hardware ID disalin", Toast.LENGTH_SHORT).show()
                true
            }
        }

        private fun setupBatteryOptimization() {
            val pref = findPreference<Preference>("battery_optimization") ?: return
            refreshBatteryOptimizationSummary(pref)
            pref.setOnPreferenceClickListener {
                val intent = Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                runCatching { startActivity(intent) }
                    .onFailure { Toast.makeText(requireContext(), "Tidak didukung di perangkat ini", Toast.LENGTH_SHORT).show() }
                true
            }
        }

        override fun onResume() {
            super.onResume()
            findPreference<Preference>("battery_optimization")?.let { refreshBatteryOptimizationSummary(it) }
        }

        private fun refreshBatteryOptimizationSummary(pref: Preference) {
            val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
            val ignoring = pm.isIgnoringBatteryOptimizations(requireContext().packageName)
            pref.summary = if (ignoring) {
                "Sudah dikecualikan dari optimasi baterai"
            } else {
                "Nonaktifkan optimasi baterai untuk app ini supaya VPN Service tidak dimatikan sistem"
            }
        }

        /** Dialog pilih aplikasi yang dikecualikan dari VPN saat split tunnel aktif. */
        private fun setupSplitTunnelPicker() {
            val pref = findPreference<Preference>("split_tunnel_apps") ?: return
            pref.setOnPreferenceClickListener {
                val pm = requireContext().packageManager
                val launchableApps = pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .sortedBy { it.loadLabel(pm).toString().lowercase() }

                val labels = launchableApps.map { it.loadLabel(pm).toString() }.toTypedArray()
                val packages = launchableApps.map { it.packageName }
                val selected = Prefs.splitTunnelApps
                val checked = packages.map { it in selected }.toBooleanArray()

                AlertDialog.Builder(requireContext())
                    .setTitle("Kecualikan dari VPN")
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton("Simpan") { _, _ ->
                        val newSelection = packages.filterIndexed { i, _ -> checked[i] }.toMutableSet()
                        Prefs.splitTunnelApps = newSelection
                        pref.summary = "${newSelection.size} aplikasi dikecualikan"
                    }
                    .setNegativeButton("Batal", null)
                    .show()
                true
            }
            pref.summary = "${Prefs.splitTunnelApps.size} aplikasi dikecualikan"
        }

        /** Ganti tema langsung begitu dipilih, tanpa perlu restart activity manual. */
        private fun setupThemeListener() {
            findPreference<ListPreference>("theme")?.setOnPreferenceChangeListener { _, newValue ->
                Prefs.theme = newValue as String
                requireActivity().recreate()
                true
            }
        }
    }
}
