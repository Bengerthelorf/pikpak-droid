package com.snaix.pikpak

import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LauncherIconTest {
    @Test
    fun launcherEntryUsesTheAppName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = checkNotNull(context.getSystemService(LauncherApps::class.java))
        val entry = launcher.getActivityList(context.packageName, Process.myUserHandle()).single()
        assertEquals("PikPak-Droid", entry.label.toString())
    }

    @Test
    fun manifestIconSuppliesAdaptiveAndMonochromeLayers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.getDrawable(context.applicationInfo.icon) as AdaptiveIconDrawable
        assertCompactGlyph(checkNotNull(icon.foreground))
        if (Build.VERSION.SDK_INT >= 33) {
            val monochrome = checkNotNull(icon.monochrome)
            assertCompactGlyph(monochrome)
            val previewColor = InstrumentationRegistry.getArguments().getString("iconPreviewColor")
            if (previewColor != null) {
                val themed = AdaptiveIconDrawable(ColorDrawable(Color.parseColor(previewColor)), monochrome)
                for ((name, variant) in listOf("color" to icon, "themed" to themed)) {
                    val bitmap = Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888)
                    try {
                        variant.setBounds(0, 0, bitmap.width, bitmap.height)
                        variant.draw(Canvas(bitmap))
                        File(context.cacheDir, "launcher-$name.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun assertCompactGlyph(drawable: Drawable) {
        val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            fun alpha(x: Int, y: Int) = Color.alpha(bitmap.getPixel(x, y))
            val columns = (0 until bitmap.width).filter { x -> (0 until bitmap.height).any { y -> alpha(x, y) > 128 } }
            val rows = (0 until bitmap.height).filter { y -> (0 until bitmap.width).any { x -> alpha(x, y) > 128 } }
            assertTrue(columns.size in 40..44 && rows.size in 29..36)
            assertTrue(columns.first() >= 30 && columns.last() <= 77)
            assertTrue(rows.first() >= 35 && rows.last() <= 72)
            // Both layers must preserve the open front and all three parts of the face.
            assertEquals(0, alpha(54, 55))
            assertTrue(alpha(46, 57) > 128 && alpha(61, 57) > 128 && alpha(54, 61) > 128)
        } finally {
            bitmap.recycle()
        }
    }
}
