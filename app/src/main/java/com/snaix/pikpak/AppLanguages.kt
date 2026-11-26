package com.snaix.pikpak

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.app.LocaleManagerCompat
import java.util.Locale

internal object AppLanguages {
    @Volatile private lateinit var localizedResources: Resources

    fun initialize(context: Context) {
        update(context, LocaleManagerCompat.getApplicationLocales(context))
    }

    private fun update(context: Context, selected: LocaleListCompat) {
        val system = LocaleManagerCompat.getSystemLocales(context)
        val tags = listOf(selected, system).flatMap { list ->
            (0 until list.size()).mapNotNull { list[it]?.toLanguageTag() }
        }.distinct()
        val locales = LocaleList.forLanguageTags(tags.joinToString(","))
        // Services, workers and cold starts can have system-only application resources.
        localizedResources = context.applicationContext.createConfigurationContext(Configuration().apply { setLocales(locales) }).resources
    }

    val locale: Locale get() = localizedResources.configuration.locales[0]

    fun text(@StringRes id: Int, args: Array<out Any>): String {
        val resources = localizedResources
        return if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
    }

    fun open(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                activity.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
                return
            } catch (_: ActivityNotFoundException) {
                // Devices without a system picker use the same AppCompat locale setting below.
            }
        }
        val tags = listOf("", "zh-Hans", "zh-Hant", "en")
        val selected = AppCompatDelegate.getApplicationLocales()[0]
        val names = arrayOf(activity.getString(R.string.app_language_system), "简体中文", "繁體中文", "English")
        val index = tags.indexOfFirst { it.isNotEmpty() && Locale.forLanguageTag(it) == selected }.coerceAtLeast(0)
        AlertDialog.Builder(activity).setTitle(R.string.app_language_title)
            .setSingleChoiceItems(names, index) { dialog, position ->
                dialog.dismiss()
                val chosen = LocaleListCompat.forLanguageTags(tags[position])
                update(activity, chosen)
                AppCompatDelegate.setApplicationLocales(chosen)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}

internal fun appString(@StringRes id: Int, vararg args: Any): String = AppLanguages.text(id, args)
