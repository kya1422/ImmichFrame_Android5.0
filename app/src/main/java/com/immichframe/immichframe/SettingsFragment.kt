package com.immichframe.immichframe

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormatSymbols
import java.util.Locale

class SettingsFragment : PreferenceFragmentCompat() {
    /**
     * Lets the user pick the client certificate for Cloudflare mTLS: either a single PKCS#12
     * file (.p12/.pfx) or the PEM certificate/private key files. Multiple selection is allowed
     * so that `cert.pem` and `key.pem` can be imported in one go; the order does not matter.
     */
    private val clientCertPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            handleClientCertSelection(uris)
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_view, rootKey)
        val chkUseWebView = findPreference<SwitchPreferenceCompat>("useWebView")
        val chkBlurredBackground = findPreference<SwitchPreferenceCompat>("blurredBackground")
        val chkShowCurrentDate = findPreference<SwitchPreferenceCompat>("showCurrentDate")
        val chkActiveTimes = findPreference<SwitchPreferenceCompat>("activeTimes")
        val editActiveSchedule = findPreference<Preference>("active_schedule_edit")
        val adminActiveSchedule = findPreference<Preference>("active_schedule_admin")


        //obfuscate the authSecret
        val authPref = findPreference<EditTextPreference>("authSecret")
        authPref?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        // Update visibility based on switches
        val useWebView = chkUseWebView?.isChecked ?: false
        chkBlurredBackground?.isVisible = !useWebView
        chkShowCurrentDate?.isVisible = !useWebView
        val activeTimes = chkActiveTimes?.isChecked ?: false
        editActiveSchedule?.isVisible = activeTimes
        adminActiveSchedule?.isVisible = activeTimes
        updateAdminSummary(adminActiveSchedule)
        updateScheduleSummary(editActiveSchedule)

        // React to changes
        chkUseWebView?.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as Boolean
            chkBlurredBackground?.isVisible = !value
            chkShowCurrentDate?.isVisible = !value
            //add android settings button
            true
        }
        chkActiveTimes?.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as Boolean
            editActiveSchedule?.isVisible = value
            adminActiveSchedule?.isVisible = value
            true
        }
        editActiveSchedule?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), ActiveScheduleActivity::class.java))
            true
        }
        adminActiveSchedule?.setOnPreferenceClickListener {
            val dpm = requireContext()
                .getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = FrameDeviceAdminReceiver.componentName(requireContext())
            if (dpm.isAdminActive(component)) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Screen-Off Permission")
                    .setMessage("ImmichFrame can already turn the screen off. Disable this permission?")
                    .setPositiveButton("Disable") { _, _ ->
                        dpm.removeActiveAdmin(component)
                        // removeActiveAdmin applies asynchronously; refresh once it takes effect.
                        Handler(Looper.getMainLooper()).postDelayed({
                            updateAdminSummary(adminActiveSchedule)
                        }, 500)
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.device_admin_description),
                    )
                }
                startActivity(intent)
            }
            true
        }
        val btnDiagnostics = findPreference<Preference>("diagnostics")
        btnDiagnostics?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), DiagnosticsActivity::class.java))
            true
        }

        val chkKeepAlive = findPreference<SwitchPreferenceCompat>("keepAlive")
        chkKeepAlive?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled) {
                val activeTimes = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getBoolean("activeTimes", false)
                if (!activeTimes) {
                    // Without a schedule there is nothing to keep alive and the service stops
                    // itself again, which would look like a bug without this hint.
                    Toast.makeText(
                        requireContext(),
                        R.string.keepalive_needs_active_times,
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    FrameKeepAliveService.start(requireContext())
                    Toast.makeText(requireContext(), R.string.keepalive_started_toast, Toast.LENGTH_LONG)
                        .show()
                }
            } else {
                FrameKeepAliveService.stop(requireContext())
            }
            true
        }

        val chkClientCert = findPreference<SwitchPreferenceCompat>("clientCertEnabled")
        val importClientCert = findPreference<Preference>("client_cert_import")
        val clearClientCert = findPreference<Preference>("client_cert_clear")

        chkClientCert?.setOnPreferenceChangeListener { _, newValue ->
            val enabling = newValue as Boolean
            if (enabling && !ClientCertStore.hasCertificate(requireContext())) {
                Toast.makeText(requireContext(), R.string.client_cert_needs_import, Toast.LENGTH_LONG)
                    .show()
                false
            } else {
                true
            }
        }
        importClientCert?.setOnPreferenceClickListener {
            clientCertPicker.launch(arrayOf("*/*"))
            true
        }
        clearClientCert?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.client_cert_clear_title)
                .setMessage(R.string.client_cert_clear_message)
                .setPositiveButton(R.string.client_cert_clear_confirm) { _, _ ->
                    ClientCertStore.clear(requireContext())
                    updateClientCertPreferences()
                    Toast.makeText(requireContext(), R.string.client_cert_removed, Toast.LENGTH_SHORT)
                        .show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        updateClientCertPreferences()

        val chkSettingsLock = findPreference<SwitchPreferenceCompat>("settingsLock")
        chkSettingsLock?.setOnPreferenceChangeListener { _, newValue ->
            val enabling = newValue as Boolean
            if (enabling) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Confirm Action")
                    .setMessage(
                        "This will disable access to the settings screen, the only way back is via RPC commands (or uninstall/reinstall).\n" +
                                "Are you absolutely sure?"
                    )
                    .setPositiveButton("Yes", null) // Proceed
                    .setNegativeButton("No") { dialog, _ ->
                        chkSettingsLock.isChecked = false // revert
                        dialog.dismiss()
                    }
                    .show()
            }
            true
        }


        val btnClose = findPreference<Preference>("closeSettings")
        btnClose?.setOnPreferenceClickListener {
            val url = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString("webview_url", "")?.trim()
            val urlPattern = Regex("^https?://.+")
            return@setOnPreferenceClickListener if (url.isNullOrEmpty() || !url.matches(urlPattern)) {
                Toast.makeText(requireContext(), "Please enter a valid server URL.", Toast.LENGTH_LONG).show()
                false
            } else {
                activity?.setResult(Activity.RESULT_OK)
                activity?.finish()
                true
            }
        }

        val btnAndroidSettings = findPreference<Preference>("androidSettings")
        btnAndroidSettings?.setOnPreferenceClickListener {
            val context = requireContext()

            // Only show Toast + auto-return on Android 9 and below
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                Toast.makeText(context, "Returning to app in 2 minutes…", Toast.LENGTH_LONG).show()

                // Schedule return after 2 minutes
                Handler(Looper.getMainLooper()).postDelayed({
                    val returnIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    returnIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(returnIntent)
                }, 2 * 60 * 1000)
            }

            // Launch Android settings
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)

            true
        }
    }

    override fun onResume() {
        super.onResume()
        updateAdminSummary(findPreference("active_schedule_admin"))
        updateScheduleSummary(findPreference("active_schedule_edit"))
        updateClientCertPreferences()
    }

    // --- Client certificate (mTLS) -----------------------------------------

    private fun updateClientCertPreferences() {
        val context = requireContext()
        val switch = findPreference<SwitchPreferenceCompat>("clientCertEnabled") ?: return
        val importPref = findPreference<Preference>("client_cert_import")
        val clearPref = findPreference<Preference>("client_cert_clear")
        val imported = ClientCertStore.hasCertificate(context)
        switch.isChecked = ClientCertStore.isEnabled(context)
        importPref?.summary = if (imported) {
            ClientCertStore.describe(context) ?: getString(R.string.client_cert_read_error)
        } else {
            getString(R.string.client_cert_not_imported)
        }
        clearPref?.isVisible = imported
    }

    private fun handleClientCertSelection(uris: List<Uri>) {
        val context = requireContext()
        val selected = mutableListOf<ByteArray>()
        for (uri in uris) {
            val bytes = readUri(uri) ?: continue
            if (bytes.isEmpty()) continue
            selected.add(bytes)
        }
        if (selected.isEmpty()) {
            Toast.makeText(context, R.string.client_cert_unreadable, Toast.LENGTH_LONG).show()
            return
        }

        // A PKCS#12 container is a binary DER structure (starts with 0x30); anything that is not
        // one is treated as PEM text.
        val pkcs12 = selected.firstOrNull { isPkcs12Container(it) }
        if (pkcs12 != null) {
            askForPkcs12Password(pkcs12)
            return
        }

        val pems = selected.map { String(it, Charsets.UTF_8) }
        try {
            ClientCertStore.importPem(context, pems)
        } catch (e: Exception) {
            showClientCertError(e.message ?: e.toString())
            return
        }
        updateClientCertPreferences()
        showClientCertImported()
    }

    private fun askForPkcs12Password(bytes: ByteArray) {
        val context = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_cert_password, null)
        val input = view.findViewById<EditText>(R.id.certPassword)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.client_cert_password_title)
            .setMessage(R.string.client_cert_password_message)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                try {
                    ClientCertStore.importPkcs12(context, bytes, input.text.toString().toCharArray())
                } catch (e: Exception) {
                    showClientCertError(e.message ?: e.toString())
                    return@setPositiveButton
                }
                updateClientCertPreferences()
                showClientCertImported()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showClientCertImported() {
        val description = ClientCertStore.describe(requireContext()).orEmpty()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.client_cert_imported_title)
            .setMessage(getString(R.string.client_cert_imported_message, description))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showClientCertError(message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.client_cert_import_failed_title)
            .setMessage(getString(R.string.client_cert_import_failed_message, message))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun readUri(uri: Uri): ByteArray? = try {
        requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    /** PKCS#12 containers are BER/DER encoded, i.e. a SEQUENCE with a long form length. */
    private fun isPkcs12Container(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0x30.toByte() && (bytes[1].toInt() and 0xFF) > 0x7F

    private fun updateScheduleSummary(preference: Preference?) {
        val pref = preference ?: return
        val context = requireContext()
        val json = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("activeSchedule", null)
        val rules = summarizeSchedule(json)
        val activeTimes = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("activeTimes", false)
        val next = if (activeTimes) ActiveScheduleManager.nextTransition(context) else null
        pref.summary = if (next == null) {
            rules
        } else {
            val what = if (next.becomesActive) {
                getString(R.string.schedule_next_wake)
            } else {
                getString(R.string.schedule_next_sleep)
            }
            "$rules\n$what: ${ActiveScheduleManager.formatShort(next.at.timeInMillis)}"
        }
    }

    private fun summarizeSchedule(json: String?): String {
        val schedule = Helpers.parseActiveSchedule(json)
        if (schedule.rules.isEmpty()) return getString(R.string.active_schedule_always)
        return schedule.rules.joinToString("\n") { rule ->
            val days = summarizeDays(rule.days)
            val times = rule.ranges.joinToString(", ") { "${it.start}–${it.end}" }
            if (days.isEmpty()) times else "$days: $times"
        }
    }

    // Collapse a set of Calendar weekday constants into a compact label, e.g. "Mon–Fri, Sun".
    private fun summarizeDays(days: Set<Int>): String {
        val order = intArrayOf(2, 3, 4, 5, 6, 7, 1) // Mon..Sun
        val indices = order.indices.filter { days.contains(order[it]) }
        if (indices.isEmpty()) return ""
        val names = DateFormatSymbols(Locale.getDefault()).shortWeekdays
        fun name(dayInt: Int) = names.getOrNull(dayInt)?.takeIf { it.isNotBlank() } ?: dayInt.toString()
        val parts = mutableListOf<String>()
        var start = 0
        while (start < indices.size) {
            var end = start
            while (end + 1 < indices.size && indices[end + 1] == indices[end] + 1) end++
            if (end - start >= 2) {
                parts.add("${name(order[indices[start]])}–${name(order[indices[end]])}")
            } else {
                for (k in start..end) parts.add(name(order[indices[k]]))
            }
            start = end + 1
        }
        return parts.joinToString(", ")
    }

    private fun updateAdminSummary(preference: Preference?) {
        val pref = preference ?: return
        val dpm = requireContext()
            .getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val enabled = dpm.isAdminActive(FrameDeviceAdminReceiver.componentName(requireContext()))
        pref.summary = if (enabled) {
            "Enabled — the frame can turn off the screen and sleep the device. Tap to disable."
        } else {
            "Allow the frame to turn off the screen and sleep the device during inactive hours"
        }
    }
}