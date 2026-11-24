package com.sci.calc.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.sci.calc.browser.AdBlocker
import com.sci.calc.calc.SetupActivity
import com.sci.calc.data.Db
import com.sci.calc.data.Prefs
import com.sci.calc.security.AppLock
import com.sci.calc.security.SecureActivity
import com.sci.calc.vault.Storage

class SettingsActivity : SecureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, Fragment()).commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    fun external() = goingExternal()

    class Fragment : PreferenceFragmentCompat() {
        private val prefs by lazy { Prefs(requireContext()) }

        private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.downloadTree = uri.toString()
                refreshSummaries()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(com.sci.calc.R.xml.preferences, rootKey)

            click("change_code") {
                startActivity(Intent(requireContext(), SetupActivity::class.java)
                    .putExtra(SetupActivity.EXTRA_RESET, true).putExtra(SetupActivity.EXTRA_FROM_SETTINGS, true))
            }
            findPreference<SwitchPreferenceCompat>("bio_switch")?.apply {
                val can = BiometricManager.from(requireContext()).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
                ) == BiometricManager.BIOMETRIC_SUCCESS
                isPersistent = false
                isEnabled = can
                isChecked = AppLock.biometricEnabled && can
                if (!can) summary = "No fingerprint set up on this phone"
                setOnPreferenceChangeListener { _, v -> AppLock.biometricEnabled = v as Boolean; true }
            }
            click("new_backup") {
                AlertDialog.Builder(requireContext())
                    .setMessage("Create a new backup code? The old one will stop working.")
                    .setPositiveButton("Create") { _, _ ->
                        val code = AppLock.newBackupCode().chunked(4).joinToString(" ")
                        AlertDialog.Builder(requireContext()).setTitle("New backup code")
                            .setMessage("$code\n\nWrite it down. It's shown only once.")
                            .setPositiveButton("Done", null).show()
                    }.setNegativeButton("Cancel", null).show()
            }
            click("update_blocklist") {
                toast("Updating…")
                AdBlocker.refresh(requireContext().applicationContext) { ok ->
                    activity?.runOnUiThread {
                        toast(if (ok) "Block list updated (${AdBlocker.size} domains)" else "Update failed. Check your connection.")
                        refreshSummaries()
                    }
                }
            }
            click("clear_whitelist") {
                val sites = prefs.whitelist.sorted()
                if (sites.isEmpty()) { toast("No sites allowed"); return@click }
                val checked = BooleanArray(sites.size) { true }
                AlertDialog.Builder(requireContext()).setTitle("Allowed to show ads")
                    .setMultiChoiceItems(sites.toTypedArray(), checked) { _, i, c -> checked[i] = c }
                    .setPositiveButton("Save") { _, _ ->
                        prefs.whitelist = sites.filterIndexed { i, _ -> checked[i] }.toSet()
                    }.setNegativeButton("Cancel", null).show()
            }
            click("clear_data") {
                val items = arrayOf("Cookies & site data", "Cache", "History", "Site permissions")
                val checked = booleanArrayOf(true, true, true, false)
                AlertDialog.Builder(requireContext()).setTitle("Clear browsing data")
                    .setMultiChoiceItems(items, checked) { _, i, c -> checked[i] = c }
                    .setPositiveButton("Clear") { _, _ ->
                        val ctx = requireContext()
                        if (checked[0]) { CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData() }
                        if (checked[1]) WebView(ctx).apply { clearCache(true); destroy() }
                        if (checked[2]) Db.get(ctx).clearHistory()
                        if (checked[3]) ctx.getSharedPreferences("site_perms", 0).edit().clear().apply()
                        toast("Cleared")
                    }.setNegativeButton("Cancel", null).show()
            }
            click("download_folder") {
                AlertDialog.Builder(requireContext())
                    .setMessage("Choose a folder on your memory card. In the next screen, open the menu (≡), pick your SD card, create or select a folder, then tap \"Use this folder\".")
                    .setPositiveButton("Choose") { _, _ ->
                        (activity as? SettingsActivity)?.external()
                        pickFolder.launch(null)
                    }
                    .setNegativeButton("Cancel", null).show()
            }
            click("download_folder_reset") {
                prefs.downloadTree = null
                refreshSummaries()
                toast(if (Storage.hasMemoryCard(requireContext())) "Using memory card" else "No memory card found — using phone storage")
            }
            refreshSummaries()
        }

        private fun refreshSummaries() {
            findPreference<Preference>("download_folder")?.summary = Storage.describeTarget(requireContext())
            findPreference<Preference>("update_blocklist")?.summary = "${AdBlocker.size} domains blocked"
        }

        private fun click(key: String, action: () -> Unit) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }
        }

        private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
    }
}
