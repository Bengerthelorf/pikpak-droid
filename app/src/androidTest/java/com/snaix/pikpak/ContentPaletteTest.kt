package com.snaix.pikpak

import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentPaletteTest {
    @Test
    fun workspaceLabelsKeepTheirPrimaryAndSupportingRolesInBothThemes() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        for (nightMode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val configuration = Configuration(target.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
            }
            val context = ContextThemeWrapper(target.createConfigurationContext(configuration), R.style.AppTheme)
            val fallback = if (nightMode == Configuration.UI_MODE_NIGHT_YES) Color.BLACK else Color.WHITE
            val background = context.themeColor(android.R.attr.windowBackground, fallback)
                .takeIf { Color.alpha(it) == 255 } ?: fallback
            val title = context.label("文件名").currentTextColor
            val detail = context.label("文件大小", 13f, secondary = true).currentTextColor
            assertEquals(context.themeColor(android.R.attr.textColorPrimary), title)
            val primaryContrast = ColorUtils.calculateContrast(title, background)
            val secondaryContrast = ColorUtils.calculateContrast(detail, background)
            assertTrue("nightMode=$nightMode title=$primaryContrast detail=$secondaryContrast",
                secondaryContrast >= 4.5 && primaryContrast > secondaryContrast)
        }
    }
}
