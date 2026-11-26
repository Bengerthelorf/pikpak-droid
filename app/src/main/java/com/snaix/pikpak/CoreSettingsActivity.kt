package com.snaix.pikpak

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Process
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import dev.oneuiproject.oneui.layout.ToolbarLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CoreSettingsActivity : MiniPlayerHostActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = appearancePreferences().themeMode().nightMode
        super.onCreate(savedInstanceState)
        setContentView(R.layout.workspace_settings)
        findViewById<ToolbarLayout>(R.id.workspace_settings_toolbar).apply {
            setTitle("PikPak Core")
            showNavigationButtonAsBack = true
            showNavigationButton = true
            setNavigationButtonTooltip(getString(R.string.workspace_settings_back))
            setNavigationButtonOnClickListener { finish() }
        }
        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(R.id.workspace_settings_preferences, CorePreferences()).commitNow()
    }
}

class CorePreferences : PreferenceFragmentCompat() {
    private lateinit var current: Preference
    private lateinit var checkUpdates: Preference
    private lateinit var importCore: Preference
    private lateinit var restore: Preference
    private lateinit var close: Preference
    private var busy = false
    private var expectedVersion: String? = null
    private var expectedHash: String? = null
    private var pickerHash: String? = null

    private val probe = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val version = data?.getStringExtra("version")
        val revision = data?.getStringExtra("revision")
        val hash = data?.getStringExtra("sha")
        if (result.resultCode == Activity.RESULT_OK && version != null && revision != null && hash != null &&
            hash == expectedHash && (expectedVersion == null || expectedVersion == version)) {
            CoreRuntime.select(requireContext(), CoreVersion(version, revision, hash))
            message(getString(R.string.ui_core_verified))
        } else message(data?.getStringExtra("error") ?: getString(R.string.ui_core_verification_failed))
        expectedHash = null
        expectedVersion = null
        refresh()
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val hash = pickerHash
        pickerHash = null
        if (uri != null && hash != null) importDevelopmentFile(uri, hash)
    }

    internal fun importDevelopmentFile(uri: android.net.Uri, hash: String) {
        val context = requireContext().applicationContext
        runWork({
            val input = context.contentResolver.openInputStream(uri) ?: error(appString(R.string.ui_file_read_failed))
            CoreRuntime.importDevelopment(context, hash, input)
        }) { testCore(it, null) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        expectedVersion = savedInstanceState?.getString("version")
        expectedHash = savedInstanceState?.getString("sha")
        pickerHash = savedInstanceState?.getString("picker")
        preferenceScreen = preferenceManager.createPreferenceScreen(context)
        fun row(label: String, action: (() -> Unit)? = null) = Preference(context).apply {
            title = label
            isIconSpaceReserved = false
            isSelectable = action != null
            if (action != null) setOnPreferenceClickListener { action(); true }
            preferenceScreen.addPreference(this)
        }
        current = row(getString(R.string.ui_core_in_use))
        checkUpdates = row(getString(R.string.ui_check_core_updates)) {
            runWork(CoreReleases::latest) { release ->
                val selected = CoreRuntime.pending(context)
                val sameOrOlder = !CoreReleases.newer(release.version, CoreRuntime.bundled(context).version) ||
                    (selected.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) && !CoreReleases.newer(release.version, selected.version))
                if (sameOrOlder) message(getString(R.string.ui_core_is_current))
                else AlertDialog.Builder(context).setTitle(getString(R.string.ui_update_core_title, release.version))
                    .setMessage(getString(R.string.ui_update_core_body, readableBytes(release.size)))
                    .setNegativeButton(getString(R.string.ui_cancel), null)
                    .setPositiveButton(getString(R.string.ui_download_and_verify)) { _, _ ->
                        runWork({ CoreRuntime.stage(context, release.sha256) { CoreReleases.download(release, it) } }) {
                            testCore(it, release.version)
                        }
                    }.show()
            }
        }
        checkUpdates.summary = getString(R.string.ui_core_updates_description)
        importCore = row(getString(R.string.ui_import_development_core)) {
            val field = AppCompatEditText(context).apply {
                isSingleLine = true
                hint = "SHA-256"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            val input = FrameLayout(context).apply {
                setPaddingRelative(context.dp(24), 0, context.dp(24), 0)
                addView(field, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            }
            val box = AlertDialog.Builder(context).setTitle(getString(R.string.ui_import_local_core))
                .setMessage(getString(R.string.ui_import_local_core_description))
                .setView(input).setNegativeButton(getString(R.string.ui_cancel), null)
                .setPositiveButton(getString(R.string.ui_choose_file)) { _, _ ->
                    pickerHash = field.text.toString().trim().lowercase()
                    picker.launch(arrayOf("*/*"))
                }.show()
            val choose = box.getButton(AlertDialog.BUTTON_POSITIVE)
            choose.isEnabled = false
            val hexDigest = Regex("[a-fA-F0-9]{64}")
            field.doAfterTextChanged { choose.isEnabled = hexDigest.matches(it.toString().trim()) }
        }
        importCore.isVisible = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        importCore.summary = getString(R.string.ui_import_core_description)
        restore = row(getString(R.string.ui_restore_bundled_core)) {
            CoreRuntime.select(context, CoreRuntime.bundled(context))
            refresh()
        }
        close = row(getString(R.string.ui_close_to_switch_core)) {
            AlertDialog.Builder(context).setTitle(getString(R.string.ui_close_app_title))
                .setMessage(getString(R.string.ui_close_app_core_description))
                .setNegativeButton(getString(R.string.ui_cancel), null)
                .setPositiveButton(getString(R.string.ui_close_app)) { _, _ ->
                    requireActivity().finishAndRemoveTask()
                    Process.killProcess(Process.myPid())
                }.show()
        }
        preferenceScreen.applySummaryColors()
        refresh()
    }

    private fun testCore(hash: String, version: String?) {
        expectedHash = hash
        expectedVersion = version
        refresh()
        probe.launch(Intent(requireContext(), CoreProbeActivity::class.java).putExtra("sha", hash))
    }

    private fun <T> runWork(work: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true
        refresh()
        lifecycleScope.launch {
            try {
                done(withContext(Dispatchers.IO) { work() })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (isAdded) AlertDialog.Builder(requireContext()).setTitle("PikPak Core").setMessage(e.message ?: getString(R.string.ui_update_failed))
                    .setPositiveButton(getString(R.string.ui_ok), null).show()
            } finally {
                busy = false
                if (isAdded) refresh()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("version", expectedVersion)
        outState.putString("sha", expectedHash)
        outState.putString("picker", pickerHash)
    }

    private fun refresh() {
        val selected = CoreRuntime.pending(requireContext())
        val active = CoreRuntime.active
        current.summary = listOfNotNull(
            getString(if (active.sha256.isEmpty()) R.string.ui_bundled_core_version else R.string.ui_standalone_core_version,
                active.description),
            CoreRuntime.recovery,
            if (selected != active) getString(R.string.ui_core_next_start, selected.description) else null
        ).joinToString("\n")
        val ready = !busy && expectedHash == null
        checkUpdates.isEnabled = ready
        importCore.isEnabled = ready
        restore.isVisible = selected.sha256.isNotEmpty()
        restore.isEnabled = ready
        close.isVisible = selected != active
        close.isEnabled = ready
    }

    private fun message(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
}
