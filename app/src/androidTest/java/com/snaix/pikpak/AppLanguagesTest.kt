package com.snaix.pikpak

import android.app.LocaleConfig
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.preference.Preference
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@SdkSuppress(minSdkVersion = 33)
class AppLanguagesTest {
    @Test fun systemLanguagePickerAndWorkspaceUseTheSameLocale() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext
        val config = LocaleConfig(app)
        assertEquals(LocaleConfig.STATUS_SUCCESS, config.status)
        assertEquals(setOf("en", "zh-Hans", "zh-Hant"), config.supportedLocales!!.toLanguageTags().split(",").toSet())
        val picker = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", app.packageName, null))
        assertNotNull(app.packageManager.resolveActivity(picker, PackageManager.MATCH_DEFAULT_ONLY))
        val settings = Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(app.packageName)
        assertEquals(SettingsActivity::class.java.name, app.packageManager.resolveActivity(settings, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.name)
        launchFixtureActivity(SettingsActivity::class.java, settings).use { scenario ->
            scenario.onActivity { activity ->
                val language = (activity.supportFragmentManager.fragments.single() as WorkspacePreferencesFragment)
                    .findPreference<Preference>("app_language")
                val actual = listOf(language?.title, activity.getString(Page.Trash.titleRes), TaskTab.Cloud.title, activity.getString(R.string.search_drive_title))
                val locale = activity.resources.configuration.locales[0]
                val expected = when (locale.language) {
                    "zh" -> if (locale.script == "Hant" || locale.country in listOf("TW", "HK", "MO")) {
                        listOf("應用程式語言", "垃圾桶", "雲端下載", "搜尋檔案")
                    } else {
                        listOf("应用语言", "回收站", "云下载", "搜索文件")
                    }
                    else -> listOf("App language", "Trash", "Cloud downloads", "Search files")
                }
                assertEquals(expected, actual)
                InstrumentationRegistry.getArguments().getString("expectedLanguage")?.let { tag ->
                    assertEquals(tag, android.icu.util.ULocale.addLikelySubtags(android.icu.util.ULocale.forLocale(locale))
                        .let { if (it.language == "zh") "zh-" + it.script else it.language })
                }
            }
        }
    }
}
