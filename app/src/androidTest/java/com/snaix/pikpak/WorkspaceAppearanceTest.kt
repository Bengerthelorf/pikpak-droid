package com.snaix.pikpak

import android.animation.ValueAnimator
import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.descendants
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.oneuiproject.oneui.layout.NavDrawerLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class WorkspaceAppearanceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext

    @Test fun lightWorkspace() = checkWorkspace(ThemeMode.Light)
    @Test fun darkWorkspace() = checkWorkspace(ThemeMode.Dark)

    private fun checkWorkspace(mode: ThemeMode) {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val appearance = context.appearancePreferences()
        val previous = appearance.getString(APPEARANCE_THEME, null)
        val fixtureApp = WorkspaceFixtureApplication(context)
        fixtureApp.appearancePreferences().edit()
            .putBoolean(DRIVE_FOLDER_STATS, false).putBoolean(APPEARANCE_THUMBNAILS, false).commit()
        try {
            appearance.edit().putString(APPEARANCE_THEME, mode.name).commit()
            launch(MainActivity::class.java).use { screen ->
                val model = screen.onActivity { activity ->
                    ViewModelProvider(activity, viewModelFactory {
                        initializer { CloudModel(fixtureApp, FixtureGateway()) }
                    })["appearance", CloudModel::class.java].also { activity.showFixture(it, signedIn = false) }
                }
                fun window() = screen.onActivity { it.window }
                fun capture(name: String, text: String) = checkWindow(window(), mode, name, text)
                capture("login", context.getString(R.string.ui_sign_in_cloud_intro))
                screen.onActivity { it.showFixture(model) }
                capture("drive", "旅行归档")
                val root = screen.onActivity { it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout) }
                screen.onActivity { root.setDrawerOpen(true, animate = false) }
                checkWindow(window(), mode, "drawer", "Avery Chen") { root.drawerOffset >= 0.999f }
                val favoritesTitle = screen.onActivity { it.getString(R.string.drawer_favorites) }
                revealDrawerItem(screen, favoritesTitle) { it.findViewById(R.id.nav_stars_header) }
                val drawer = screen.onActivity { it.fixtureDrawer() }
                fun favoritesExpanded(expanded: Boolean): Boolean {
                    val header = drawer.findViewById<View>(R.id.nav_stars_header) ?: return false
                    val label = if (expanded) R.string.drawer_stars_expanded else R.string.drawer_stars_collapsed
                    return ViewCompat.getStateDescription(header)?.toString() == drawer.context.getString(label) &&
                        drawer.findMenuItem(R.id.nav_favorites)?.isVisible == expanded
                }
                checkDisclosureMotion(screen, expanded = false)
                revealDrawerItem(screen, "Capacity footer") { it.findViewById(R.id.drawer_quota_panel) }
                checkWindow(window(), mode, "drawer-collapsed", favoritesTitle) {
                    favoritesExpanded(false) && drawer.fixtureItem("收藏的旅行归档") == null &&
                        drawer.fixtureItem("常用清单.txt") == null
                }
                // A second tap during opening must bring the disclosure back to its collapsed state.
                checkDisclosureMotion(screen, expanded = false, reverse = true)
                checkDisclosureMotion(screen, expanded = true)
                checkWindow(window(), mode, "drawer-favorites", favoritesTitle) { favoritesExpanded(true) }
                revealDrawerItem(screen, "Capacity footer") { it.findViewById(R.id.drawer_quota_panel) }
                screen.onActivity { activity ->
                    val progress = activity.findViewById<View>(R.id.drawer_quota_storage_progress)
                    progress.requestRectangleOnScreen(Rect(0, 0, progress.width, progress.height), true)
                }
                checkWindow(window(), mode, "drawer-quota", "10 GB / 100 GB") {
                    val progress = root.findViewById<androidx.appcompat.widget.SeslProgressBar>(R.id.drawer_quota_storage_progress)
                    val quotaBounds = Rect()
                    progress?.progress == 10 && progress.getGlobalVisibleRect(quotaBounds) &&
                        quotaBounds.width() >= root.context.dp(16) && quotaBounds.height() > 0
                }
                screen.onActivity { root.setDrawerOpen(false, animate = false) }
                for ((name, label) in listOf("add" to context.getString(R.string.ui_new_folder), "inspect" to context.getString(R.string.ui_view_options))) {
                    screen.onActivity { activity ->
                        val actions = CloudActions(activity, model)
                        if (name == "add") actions.create() else actions.viewOptions()
                        activity.supportFragmentManager.executePendingTransactions()
                    }
                    val sheet = screen.onActivity { it.supportFragmentManager.findFragmentByTag(CloudActionSheet.TAG) as CloudActionSheet }
                    checkWindow(screen.onActivity { sheet.requireDialog().window!! }, mode, name, label)
                    screen.onActivity { sheet.dismissNow() }
                }
                screen.onActivity { model.applyPresentation(DrivePresentation(view = DriveView.Icons)) }
                capture("grid", "旅行归档")
                screen.onActivity { model.openFolder(model.entries.first { it.id == "trips" }) }
                capture("folder", "行程笔记.txt")
                for ((page, label) in listOf(Page.Favorites to "收藏的旅行归档", Page.Trash to context.getString(R.string.ui_trash_empty),
                    Page.Tasks to "示例云下载", Page.Shares to context.getString(R.string.ui_no_shares), Page.Account to context.getString(R.string.ui_cloud_capacity),
                    Page.Recent to context.getString(R.string.ui_no_recent_activity))) {
                    screen.onActivity { model.open(page) }
                    capture(page.name.lowercase(), label)
                    if (page == Page.Tasks) {
                        screen.onActivity { model.selectTaskTab(TaskTab.Download) }
                        capture("downloads", context.getString(R.string.ui_no_device_downloads))
                        screen.onActivity { model.selectTaskTab(TaskTab.Upload) }
                        capture("uploads", context.getString(R.string.ui_no_uploads))
                    }
                }
                screen.onActivity { model.openShare("https://mypikpak.com/s/demo_share", "") }
                capture("shared-files", "分享文件夹")
            }
            launch(SettingsActivity::class.java).use {
                val window = it.onActivity { activity -> activity.window }
                checkWindow(window, mode, "settings", context.getString(R.string.workspace_settings_appearance))
                checkWindow(window, mode, "settings-summary", context.getString(R.string.workspace_settings_thumbnails_description))
            }
            launch(CoreSettingsActivity::class.java).use {
                val window = it.onActivity { activity -> activity.window }
                checkWindow(window, mode, "core", context.getString(R.string.ui_core_in_use))
                checkWindow(window, mode, "core-summary", context.getString(R.string.ui_core_updates_description))
            }
        } finally {
            appearance.edit().putString(APPEARANCE_THEME, previous).commit()
            fixtureApp.close()
        }
    }

    private fun <T : Activity> launch(type: Class<T>) = launchFixtureActivity(type, Intent(context, type))

    private fun checkDisclosureMotion(screen: FixtureActivity<MainActivity>, expanded: Boolean, reverse: Boolean = false) {
        val drawer = screen.onActivity { it.fixtureDrawer() }
        val animated = ValueAnimator.areAnimatorsEnabled()
        InstrumentationRegistry.getArguments().getString("expectedDrawerAnimations")?.let {
            assertEquals("System animator setting", it.toBooleanStrict(), animated)
        }
        val endAngle = if (expanded) 180f else 0f
        val state = drawer.context.getString(if (expanded) R.string.drawer_stars_expanded else R.string.drawer_stars_collapsed)
        val finished = CountDownLatch(1)
        val angles = mutableListOf<Float>()
        val footerOffsets = mutableListOf<Int>()
        val point = IntArray(2)
        fun footerOffset(header: View): Int? {
            val footer = drawer.findViewById<View>(R.id.drawer_quota_panel) ?: return null
            if (!footer.isShown || footer.height == 0) return null
            header.getLocationOnScreen(point)
            val headerY = point[1]
            footer.getLocationOnScreen(point)
            return point[1] - headerY
        }
        val startOffset = screen.onActivity {
            checkNotNull(footerOffset(drawer.findViewById(R.id.nav_stars_header))) { "Capacity footer is missing before the tap" }
        }
        var secondTapSent = false
        var footerLastMoved = SystemClock.uptimeMillis()
        fun clickHeader() = check(drawer.findViewById<View>(R.id.nav_stars_header).performClick())
        val listener = ViewTreeObserver.OnPreDrawListener {
            val header = drawer.findViewById<View>(R.id.nav_stars_header)
            val rotation = header?.findViewById<View>(R.id.drawer_stars_chevron)?.rotation?.let { abs(it) }
            val offset = header?.let(::footerOffset)
            if (header != null && rotation != null && offset != null) {
                if (footerOffsets.lastOrNull() != offset) footerLastMoved = SystemClock.uptimeMillis()
                angles.add(rotation)
                footerOffsets.add(offset)
                if (reverse && !secondTapSent && (!animated || (rotation in 2f..178f && abs(offset - startOffset) > 1))) {
                    secondTapSent = true
                    drawer.post { clickHeader() }
                } else if ((!reverse || secondTapSent) && abs(rotation - endAngle) < 0.01f &&
                    SystemClock.uptimeMillis() - footerLastMoved >= 80 &&
                    (if (reverse) abs(offset - startOffset) <= 1 else offset != startOffset) &&
                    ViewCompat.getStateDescription(header)?.toString() == state &&
                    drawer.findMenuItem(R.id.nav_favorites)?.isVisible == expanded
                ) {
                    finished.countDown()
                }
            }
            if (finished.count > 0) drawer.postInvalidateOnAnimation()
            true
        }
        try {
            screen.onActivity {
                drawer.viewTreeObserver.addOnPreDrawListener(listener)
                clickHeader()
            }
            val settled = finished.await(8, TimeUnit.SECONDS)
            val (frames, offsets) = screen.onActivity { angles.toList() to footerOffsets.toList() }
            assertTrue("Favorites did not settle at expanded=$expanded, reverse=$reverse. Angles=$frames footer=$offsets", settled)
            assertEquals("Favorites arrow intermediate frames=$frames", animated, frames.any { it in 1f..179f })
            if (!reverse) {
                val endOffset = offsets.last()
                assertEquals("Capacity moved in the wrong direction: $startOffset → $endOffset", expanded, endOffset > startOffset)
                var furthest = startOffset
                for (offset in offsets) {
                    furthest = if (expanded) maxOf(furthest, offset) else minOf(furthest, offset)
                    assertTrue("Capacity jumped backwards: start=$startOffset frames=$offsets", abs(offset - furthest) <= 1)
                }
                val between = (minOf(startOffset, endOffset) + 1) until maxOf(startOffset, endOffset)
                assertEquals("Capacity intermediate frames=$offsets", animated, offsets.any { it in between })
            }
        } finally {
            screen.onActivity { drawer.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
    }

    /** Compare a real label against the dominant surface behind it in the composited window. */
    private fun checkWindow(window: Window, mode: ThemeMode, name: String, label: String, ready: () -> Boolean = { true }) {
        val bounds = Rect()
        var foreground = 0
        val end = SystemClock.elapsedRealtime() + 8000
        do {
            instrument.runOnMainSync {
                val decor = window.decorView as ViewGroup
                val night = decor.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                assertEquals(name, if (mode == ThemeMode.Dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO, night)
                if (!ready()) {
                    bounds.setEmpty()
                    return@runOnMainSync
                }
                val row = decor.descendants.filterIsInstance<TextView>().firstOrNull {
                    it.text.toString() == label && it.isShown && it.getGlobalVisibleRect(bounds) && bounds.width() > 10
                }
                if (row != null) foreground = row.currentTextColor else bounds.setEmpty()
            }
            if (!bounds.isEmpty) break
            SystemClock.sleep(75)
        } while (SystemClock.elapsedRealtime() < end)
        assertTrue("${mode.name}/$name missing visible $label", !bounds.isEmpty)
        instrument.waitForIdleSync()
        SystemClock.sleep(330)
        val automation = instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val display = InstrumentationRegistry.getArguments().getString("captureDisplayId")?.toULongOrNull()
        val composed = display?.let {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p -d $it"))
                .use(BitmapFactory::decodeStream)
        }
        val shot = checkNotNull(composed ?: if (window.decorView.display?.displayId == 0) automation.takeScreenshot()
            else automation.takeScreenshot(window))
        val pixels = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false) else shot
        try {
            val save = InstrumentationRegistry.getArguments().getString("captureAppearance") == "true"
            if (save) File(context.cacheDir, "qa-workspace-${mode.name.lowercase()}-$name.png").outputStream().use {
                check(pixels.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            assertTrue("$name label outside captured window: $bounds", bounds.intersect(0, 0, pixels.width, pixels.height))
            val counts = HashMap<Int, Int>()
            for (y in bounds.top until bounds.bottom step 4) for (x in bounds.left until bounds.right step 4) {
                // A single-window capture omits the system's blur layer behind a One UI sheet.
                val rgb = ColorUtils.compositeColors(pixels.getPixel(x, y),
                    if (mode == ThemeMode.Dark) Color.BLACK else Color.WHITE)
                counts[rgb] = (counts[rgb] ?: 0) + 1
            }
            val background = counts.maxBy { it.value }.key
            val contrast = ColorUtils.calculateContrast(foreground, background)
            assertTrue("${mode.name}/$name $label contrast=$contrast bgcolor=${background.toUInt().toString(16)}",
                contrast >= 4.5)
            assertEquals("${mode.name}/$name surface", mode == ThemeMode.Dark, ColorUtils.calculateLuminance(background) < 0.32)
        } finally {
            if (pixels !== shot) pixels.recycle()
            shot.recycle()
        }
    }
}
