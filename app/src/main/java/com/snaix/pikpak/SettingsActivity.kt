package com.snaix.pikpak

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeslSwitchPreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import dev.oneuiproject.oneui.layout.ToolbarLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val APPEARANCE_PREFERENCES = "appearance"
internal const val APPEARANCE_THEME = "theme"
internal const val APPEARANCE_THUMBNAILS = "thumbnails"
internal const val DRIVE_FOLDER_STATS = "folder_stats"
internal const val APPEARANCE_PREVIEW_CACHE = "preview_cache"

internal fun Context.appearancePreferences(): SharedPreferences =
    getSharedPreferences(APPEARANCE_PREFERENCES, Context.MODE_PRIVATE)

internal fun SharedPreferences.themeMode(): ThemeMode {
    val stored = getString(APPEARANCE_THEME, null)
    return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.System
}

internal fun PreferenceGroup.applySummaryColors() {
    val supporting = context.contentTextColors(secondary = true) ?: return
    for (index in 0 until preferenceCount) {
        val child = getPreference(index)
        if (child is PreferenceGroup) child.applySummaryColors() else child.seslSetSummaryColor(supporting)
    }
}

class SettingsActivity : MiniPlayerHostActivity() {
    private val preferences by lazy { appearancePreferences() }
    private val appearanceChanges = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == APPEARANCE_THEME) applyAppearance()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAppearance()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.workspace_settings)
        findViewById<ToolbarLayout>(R.id.workspace_settings_toolbar).apply {
            setTitle(getString(R.string.workspace_settings_title))
            showNavigationButtonAsBack = true
            showNavigationButton = true
            setNavigationButtonTooltip(getString(R.string.workspace_settings_back))
            setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.workspace_settings_preferences, WorkspacePreferencesFragment())
                .commitNow()
        }
    }

    override fun onStart() {
        super.onStart()
        preferences.registerOnSharedPreferenceChangeListener(appearanceChanges)
        applyAppearance()
    }

    override fun onStop() {
        preferences.unregisterOnSharedPreferenceChangeListener(appearanceChanges)
        super.onStop()
    }

    private fun applyAppearance() {
        val mode = preferences.themeMode().nightMode
        if (delegate.localNightMode != mode) delegate.localNightMode = mode
    }
}

private class CacheLimitPreference(context: Context) : SeslSwitchPreferenceScreen(context) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.findViewById(android.R.id.title)?.isEnabled = isChecked
        holder.findViewById(android.R.id.summary)?.isEnabled = isChecked
        // SESL has separate switch widgets for its regular and large-font layouts.
        holder.findViewById(android.R.id.switch_widget)?.isFocusable = true
        holder.findViewById(androidx.preference.R.id.switch_widget)?.isFocusable = true
    }
}

class WorkspacePreferencesFragment : PreferenceFragmentCompat() {
    private lateinit var forgetPassword: Preference
    private lateinit var warmup: Preference
    private lateinit var notifications: Preference
    private lateinit var sizeLimit: CacheLimitPreference
    private lateinit var countLimit: CacheLimitPreference
    private lateinit var clearPreviews: Preference
    private val previewDisk by lazy { PreviewDiskCache(requireContext().applicationContext) { null } }
    private var usageRevision = 0L
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (isAdded) {
            renderNotificationSettings()
            beginWarmup()
        }
    }
    private val cacheChanges = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            DRIVE_FOLDER_STATS, APPEARANCE_PREVIEW_CACHE -> renderCache()
            PreviewCacheLimits.SIZE_KEY, PreviewCacheLimits.COUNT_KEY -> renderLimits()
            PreviewCacheLimits.SIZE_ENABLED_KEY, PreviewCacheLimits.COUNT_ENABLED_KEY -> {
                renderLimits()
                PreviewWarmupService.resetFinishedState()
                if (prefs.getBoolean(key, true)) refreshPreviewUsage(clearing = false)
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = APPEARANCE_PREFERENCES
        val context = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(context).apply {
            addPreference(Preference(context).apply {
                key = "app_language"
                setTitle(R.string.app_language_title)
                isPersistent = false
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    AppLanguages.open(requireActivity() as AppCompatActivity)
                    true
                }
            })
            addPreference(ListPreference(context).apply {
                key = APPEARANCE_THEME
                setTitle(R.string.workspace_settings_appearance)
                setDialogTitle(R.string.workspace_settings_appearance)
                entries = ThemeMode.entries.map(ThemeMode::label).toTypedArray()
                entryValues = ThemeMode.entries.map(ThemeMode::name).toTypedArray()
                setDefaultValue(ThemeMode.System.name)
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                isIconSpaceReserved = false
            })
            addPreference(SwitchPreferenceCompat(context).apply {
                key = APPEARANCE_THUMBNAILS
                setTitle(R.string.workspace_settings_thumbnails)
                setSummary(R.string.workspace_settings_thumbnails_description)
                setDefaultValue(true)
                isIconSpaceReserved = false
            })
            val drawer = PreferenceCategory(context).apply { setTitle(R.string.drawer_quota_category) }
            addPreference(drawer)
            drawer.addPreference(MultiSelectListPreference(context).apply {
                key = DrawerQuotaPreferences.VISIBLE_KEY
                setTitle(R.string.drawer_quota_selection)
                setDialogTitle(R.string.drawer_quota_selection)
                entries = DrawerQuotaKind.entries.map { getString(it.labelRes) }.toTypedArray()
                entryValues = DrawerQuotaKind.entries.map { it.name }.toTypedArray()
                setDefaultValue(DrawerQuotaPreferences.defaultVisible)
                summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> { preference ->
                    DrawerQuotaKind.entries.filter { it.name in preference.values }.joinToString(" · ") { getString(it.labelRes) }
                        .ifEmpty { getString(R.string.drawer_quota_none) }
                }
                isIconSpaceReserved = false
            })
            drawer.addPreference(ListPreference(context).apply {
                key = DrawerQuotaPreferences.DENSITY_KEY
                setTitle(R.string.drawer_quota_density)
                setDialogTitle(R.string.drawer_quota_density)
                entries = DrawerQuotaDensity.entries.map { getString(it.labelRes) }.toTypedArray()
                entryValues = DrawerQuotaDensity.entries.map { it.name }.toTypedArray()
                setDefaultValue(DrawerQuotaDensity.Simple.name)
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                isIconSpaceReserved = false
            })
            val cache = PreferenceCategory(context).apply { title = getString(R.string.ui_cache) }
            addPreference(cache)
            cache.addPreference(SwitchPreferenceCompat(context).apply {
                key = DRIVE_FOLDER_STATS
                setTitle(R.string.workspace_settings_folder_stats)
                setSummary(R.string.workspace_settings_folder_stats_description)
                setDefaultValue(true)
                isIconSpaceReserved = false
            })
            cache.addPreference(SwitchPreferenceCompat(context).apply {
                key = APPEARANCE_PREVIEW_CACHE
                setTitle(R.string.workspace_settings_preview_cache)
                setSummary(R.string.workspace_settings_preview_cache_description)
                setDefaultValue(false)
                isIconSpaceReserved = false
            })
            warmup = Preference(context).apply {
                key = "cache_now"
                title = getString(R.string.ui_cache_now)
                isPersistent = false
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    if (PreviewWarmupService.state.value.running) PreviewWarmupService.cancel(context)
                    else if (Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else beginWarmup()
                    true
                }
            }
            cache.addPreference(warmup)
            sizeLimit = cacheLimit(PreviewCacheLimits.SIZE_ENABLED_KEY, PreviewCacheLimits.SIZE_KEY, R.string.preview_cache_size_limit)
            countLimit = cacheLimit(PreviewCacheLimits.COUNT_ENABLED_KEY, PreviewCacheLimits.COUNT_KEY, R.string.preview_cache_count_limit)
            clearPreviews = Preference(context).apply {
                key = CLEAR_PREVIEW_CACHE
                setTitle(R.string.preview_cache_clear)
                isPersistent = false
                isIconSpaceReserved = false
                setOnPreferenceClickListener { openCacheDialog(CLEAR_PREVIEW_CACHE) }
            }
            cache.addPreference(sizeLimit)
            cache.addPreference(countLimit)
            cache.addPreference(clearPreviews)
            cache.addPreference(Preference(context).apply {
                key = "clear_drive_search_index"
                setTitle(R.string.search_clear_index)
                setSummary(R.string.search_clear_index_summary)
                isPersistent = false
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    val app = context.applicationContext
                    viewLifecycleOwner.lifecycleScope.launch {
                        val message = try {
                            withContext(Dispatchers.IO) {
                                val history = TransferHistory(app)
                                history.withOwner {
                                    DriveSearchCache(app).clearAccountIndex(CloudApi(app), history.verifiedAccountScope() != null)
                                }
                            }
                            appString(R.string.search_index_cleared)
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            appString(R.string.search_clear_failed)
                        }
                        Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            })
            notifications = Preference(context).apply {
                title = getString(R.string.ui_cache_progress_notification)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    val manager = context.getSystemService(NotificationManager::class.java)
                    val intent = if (Build.VERSION.SDK_INT >= 36 && manager.areNotificationsEnabled())
                        Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    try {
                        startActivity(intent)
                    } catch (_: android.content.ActivityNotFoundException) {
                        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                    true
                }
            }
            cache.addPreference(notifications)
            forgetPassword = Preference(context).apply {
                title = getString(R.string.ui_forget_password)
                summary = getString(R.string.ui_forget_password_description)
                isPersistent = false
                isIconSpaceReserved = false
                isVisible = false
                setOnPreferenceClickListener {
                    val app = context.applicationContext
                    viewLifecycleOwner.lifecycleScope.launch {
                        val cleared = withContext(Dispatchers.IO) {
                            runCatching { TransferHistory(app).withOwner { DeviceSignInStore(app).clear() } }.isSuccess
                        }
                        if (cleared) forgetPassword.isVisible = false
                        Toast.makeText(app, if (cleared) getString(R.string.ui_password_forgotten) else getString(R.string.ui_forget_password_failed), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            }
            addPreference(forgetPassword)
            val about = PreferenceCategory(context).apply { title = getString(R.string.ui_about) }
            addPreference(about)
            about.addPreference(Preference(context).apply {
                title = "PikPak Core"
                val core = CoreRuntime.active
                summary = getString(if (core.sha256.isEmpty()) R.string.ui_bundled_core_version else R.string.ui_standalone_core_version,
                    core.description)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    startActivity(android.content.Intent(context, CoreSettingsActivity::class.java))
                    true
                }
            })
            about.addPreference(Preference(context).apply {
                setTitle(R.string.workspace_settings_version)
                @Suppress("DEPRECATION")
                val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                summary = appVersion
                isSelectable = false
                isIconSpaceReserved = false
            })
            applySummaryColors()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val disk = previewDisk
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            forgetPassword.isVisible = withContext(Dispatchers.IO) {
                runCatching { TransferHistory(app).withOwner { DeviceSignInStore(app).hasSavedPassword() } }.getOrDefault(false)
            }
        }
        childFragmentManager.setFragmentResultListener(CACHE_ACTION_RESULT, viewLifecycleOwner) { _, result ->
            applyCacheAction(result.getString(CACHE_ACTION_KEY) ?: return@setFragmentResultListener, result.getInt(CACHE_ACTION_VALUE))
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PreviewWarmupService.state.collect { renderCache() } }
                PreviewWarmupService.state.map { it.running }.distinctUntilChanged().collectLatest { running ->
                    do {
                        val revision = usageRevision
                        val usage = try { withContext(Dispatchers.IO) { disk.usage() } } catch (_: IOException) { null }
                        if (revision == usageRevision) {
                            if (usage != null) showUsage(usage) else clearPreviews.setSummary(R.string.preview_cache_usage_unavailable)
                        }
                        if (running) delay(3000)
                    } while (running)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requireContext().appearancePreferences().registerOnSharedPreferenceChangeListener(cacheChanges)
        renderCache()
        renderLimits()
        renderNotificationSettings()
    }

    override fun onPause() {
        requireContext().appearancePreferences().unregisterOnSharedPreferenceChangeListener(cacheChanges)
        super.onPause()
    }

    private fun beginWarmup() {
        runCatching { PreviewWarmupService.start(requireContext()) }
            .onFailure { Toast.makeText(requireContext(), getString(R.string.ui_cache_start_failed), Toast.LENGTH_LONG).show() }
    }

    private fun cacheLimit(enabledKey: String, valueKey: String, titleRes: Int) = CacheLimitPreference(requireContext()).apply {
        key = enabledKey
        setTitle(titleRes)
        setDefaultValue(true)
        isIconSpaceReserved = false
        setOnPreferenceClickListener { isChecked && openCacheDialog(valueKey) }
    }

    private fun openCacheDialog(key: String): Boolean {
        PreviewCacheSettingsDialog.newInstance(key).show(childFragmentManager, CACHE_ACTION_RESULT)
        return true
    }

    private fun renderLimits() {
        val limits = PreviewCacheLimits.read(requireContext().appearancePreferences())
        sizeLimit.render(limits.sizeEnabled, getString(R.string.preview_cache_mib, limits.maxMiB))
        countLimit.render(limits.countEnabled, getString(R.string.preview_cache_count, limits.maxEntries))
    }

    private fun CacheLimitPreference.render(enabled: Boolean, value: String) {
        isChecked = enabled
        isSelectable = enabled
        summary = if (enabled) value else getString(R.string.preview_cache_limit_unlimited)
    }

    private fun showUsage(usage: PreviewCacheUsage) {
        val amount = when {
            usage.bytes < 1024L -> getString(R.string.preview_cache_used_bytes, usage.bytes)
            usage.bytes < 1024L * 1024 -> getString(R.string.preview_cache_used_kib, usage.bytes / 1024.0)
            else -> getString(R.string.preview_cache_used_mib, usage.bytes / (1024.0 * 1024))
        }
        clearPreviews.summary = getString(R.string.preview_cache_usage, amount, usage.entries)
    }

    private fun applyCacheAction(key: String, value: Int) {
        val clearing = key == CLEAR_PREVIEW_CACHE
        if (!clearing) {
            val prefs = requireContext().appearancePreferences()
            val limits = PreviewCacheLimits.read(prefs)
            val enabled = when (key) {
                PreviewCacheLimits.SIZE_KEY -> limits.sizeEnabled
                PreviewCacheLimits.COUNT_KEY -> limits.countEnabled
                else -> false
            }
            if (!enabled || value <= 0) return
            prefs.edit().putInt(key, value).apply()
            PreviewWarmupService.resetFinishedState()
        }
        refreshPreviewUsage(clearing)
    }

    private fun refreshPreviewUsage(clearing: Boolean) {
        val app = requireContext().applicationContext
        val disk = previewDisk
        val revision = ++usageRevision
        viewLifecycleOwner.lifecycleScope.launch {
            val usage = try {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (clearing) PreviewWarmupService.clearPreviewCache(app) else disk.applyLimits()
                }
            } catch (_: IOException) {
                Toast.makeText(app, R.string.preview_cache_cleanup_failed, Toast.LENGTH_LONG).show()
                clearPreviews.setSummary(R.string.preview_cache_usage_unavailable)
                return@launch
            }
            if (revision == usageRevision) {
                usageRevision++
                showUsage(usage)
                if (clearing && usage.entries == 0) Toast.makeText(app, R.string.preview_cache_cleared, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderCache() {
        val context = requireContext()
        val prefs = context.appearancePreferences()
        val stats = prefs.getBoolean(DRIVE_FOLDER_STATS, true)
        val images = prefs.getBoolean(APPEARANCE_PREVIEW_CACHE, false)
        val state = PreviewWarmupService.state.value
        warmup.title = if (state.running) getString(R.string.ui_stop_cache) else getString(R.string.ui_cache_now)
        warmup.isEnabled = state.running || stats || images
        warmup.summary = when {
            !warmup.isEnabled -> getString(R.string.ui_cache_disabled_hint)
            state.summary.isNotBlank() -> state.summary
            stats && images -> getString(R.string.ui_cache_stats_and_previews)
            stats -> getString(R.string.ui_cache_stats)
            else -> getString(R.string.ui_cache_previews)
        }
    }

    private fun renderNotificationSettings() {
        val manager = requireContext().getSystemService(NotificationManager::class.java)
        notifications.summary = when {
            !manager.areNotificationsEnabled() -> getString(R.string.ui_notification_disabled_hint)
            Build.VERSION.SDK_INT >= 36 && !manager.canPostPromotedNotifications() -> getString(R.string.ui_notification_promoted_hint)
            Build.VERSION.SDK_INT >= 36 && Build.MANUFACTURER.equals("samsung", true) -> getString(R.string.ui_notification_nowbar_hint)
            else -> getString(R.string.ui_notification_action_hint)
        }
    }
}
