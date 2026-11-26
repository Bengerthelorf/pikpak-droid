package com.snaix.pikpak

import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.view.allViews
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

@OptIn(UnstableApi::class)
class PlaybackAppearanceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val sampleName = "示例影片 · 山间的云与风"

    @Test
    fun playingVideoKeepsOpenMenusVisiblePastControllerTimeout() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val uri = Uri.parse("android.resource://${instrument.context.packageName}/raw/native_playback")
        launchFixtureActivity(PlaybackActivity::class.java,
            PlaybackActivity.localIntent(instrument.targetContext, uri, sampleName)).use { screen ->
            val surface = awaitView(screen) { activity ->
                activity.window.decorView.allViews.filterIsInstance<PlayerView>().firstOrNull {
                    it.player?.playbackState == Player.STATE_READY
                }
            }
            val toolbar = screen.onActivity { activity ->
                surface.player!!.apply {
                    volume = 0f
                    repeatMode = Player.REPEAT_MODE_ONE
                    play()
                }
                activity.window.decorView.allViews.filterIsInstance<Toolbar>().first()
            }
            for (overflow in listOf(true, false)) {
                val label = if (overflow) "导入本地字幕" else "播放速度"
                screen.onActivity {
                    surface.controllerShowTimeoutMs = 800
                    surface.showController()
                    if (overflow) assertTrue(toolbar.showOverflowMenu())
                    else assertTrue(surface.findViewById<View>(androidx.media3.ui.R.id.exo_settings).performClick())
                }
                awaitView(screen) { windowLabel(it, label) }
                SystemClock.sleep(1400)
                screen.onActivity {
                    assertTrue("Video should keep playing", surface.player!!.isPlaying)
                    assertTrue("Lost $label while choosing", windowLabel(it, label)?.isShown == true)
                    assertTrue(surface.isControllerFullyVisible)
                    if (overflow) toolbar.dismissPopupMenus()
                }
            }
        }
    }

    @Test
    fun bufferingToolbarAndQualityDialogRemainLegible() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        checkAppearances(::checkToolbarAndQuality)
    }

    private fun checkToolbarAndQuality(mode: ThemeMode) {
        // Hold a connection locally so this test can inspect the real pre-metadata state.
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            val labels = listOf("原画 · 1080p", "720P")
            val sources = JSONArray(labels.mapIndexed { i, label ->
                JSONObject().put("available", true).put("label", label)
                    .put("url", "https://127.0.0.1:${server.localPort}/$i")
            })
            val intent = Intent(instrument.targetContext, PlaybackActivity::class.java)
                .putExtra("name", sampleName).putExtra("sources", JSONObject().put("play", sources).toString())
            launchFixtureActivity(PlaybackActivity::class.java, intent).use { scenario ->
                val toolbar = awaitView(scenario) { activity ->
                    activity.window.decorView.allViews.filterIsInstance<Toolbar>().firstOrNull { bar ->
                        bar.width > 0 && bar.allViews.any { it.isShown && it.contentDescription?.startsWith("画质") == true }
                    }
                }
                scenario.onActivity { activity ->
                    assertEquals(if (mode == ThemeMode.Dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                        activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    val surface = activity.window.decorView.allViews.filterIsInstance<PlayerView>().first()
                    assertEquals(Configuration.UI_MODE_NIGHT_YES,
                        surface.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    val title = toolbar.allViews.filterIsInstance<TextView>().first { it.text == sampleName }
                    assertReadable(title, toolbar, Color.WHITE)
                    assertTrue(title.width > activity.dp(72))
                    assertWhiteIcon(checkNotNull(toolbar.navigationIcon))
                    assertWhiteIcon(checkNotNull(toolbar.menu.getItem(0).icon))
                    assertWhiteIcon(checkNotNull(toolbar.overflowIcon))
                    assertTrue(activity.findViewById<TextView>(R.id.playback_unknown_duration).isShown)
                }
                capture(scenario, toolbar.rootView, "toolbar", mode, sampleName, darkSurface = true)
                scenario.onActivity {
                    val quality = toolbar.allViews.first { it.isClickable && it.contentDescription?.startsWith("画质") == true }
                    assertTrue(quality.performClick())
                }
                val choice = awaitView(scenario) { activity -> windowLabel(activity, labels.first()) }
                scenario.onActivity {
                    val root = choice.rootView
                    for (name in listOf("画质") + labels + "取消") {
                        assertReadable(label(root, name)!!, root, backing(mode))
                    }
                    val list = root.allViews.filterIsInstance<ListView>().first()
                    assertTrue(list.isItemChecked(0))
                }
                capture(scenario, choice.rootView, "quality", mode, labels.first())
                scenario.onActivity { assertTrue(label(choice.rootView, "取消")!!.performClick()) }
                scenario.onActivity { assertTrue(toolbar.showOverflowMenu()) }
                val overflow = awaitView(scenario) { windowLabel(it, "导入本地字幕") }
                capture(scenario, overflow.rootView, "overflow", mode, "导入本地字幕")
                scenario.onActivity { toolbar.dismissPopupMenus() }
            }
        }
    }

    @Test
    fun speedPopupAndDialogUsePlayerPaletteAndUpdatePlayer() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        checkAppearances(::checkSpeedAndTracks)
    }

    private fun checkSpeedAndTracks(mode: ThemeMode) {
        val uri = Uri.parse("android.resource://${instrument.context.packageName}/raw/native_playback")
        launchFixtureActivity(PlaybackActivity::class.java,
            PlaybackActivity.localIntent(instrument.targetContext, uri, sampleName)).use { scenario ->
            val surface = awaitView(scenario) { activity ->
                activity.window.decorView.allViews.filterIsInstance<PlayerView>()
                    .firstOrNull { it.player?.playbackState == Player.STATE_READY }
            }
            val total = scenario.onActivity {
                surface.player!!.pause()
                surface.showController()
                val panel = surface.findViewById<View>(R.id.playback_transport)
                val position = surface.findViewById<TextView>(androidx.media3.ui.R.id.exo_position)
                val duration = surface.findViewById<TextView>(androidx.media3.ui.R.id.exo_duration)
                assertTrue(duration.isShown)
                assertReadable(position, panel, Color.BLACK)
                assertReadable(duration, panel, Color.BLACK)
                duration.text.toString()
            }
            capture(scenario, surface.rootView, "transport", mode, total, darkSurface = true)
            scenario.onActivity {
                assertTrue(surface.findViewById<View>(androidx.media3.ui.R.id.exo_settings).performClick())
            }
            val speed = awaitView(scenario) { windowLabel(it, "播放速度") }
            scenario.onActivity {
                assertReadable(speed, speed.rootView, backing(mode))
            }
            capture(scenario, speed.rootView, "settings", mode, "播放速度")
            scenario.onActivity {
                clickListRow(speed)
            }
            val normal = awaitView(scenario) { windowLabel(it, "正常") }
            scenario.onActivity {
                assertReadable(normal, normal.rootView, backing(mode))
                assertReadable(label(normal.rootView, "取消")!!, normal.rootView, backing(mode))
            }
            capture(scenario, normal.rootView, "speed", mode, "正常")
            scenario.onActivity {
                clickListRow(label(normal.rootView, "1.5×")!!)
                assertEquals(1.5f, surface.player!!.playbackParameters.speed, 0.001f)
            }
            fun audioChoices(): ListView {
                scenario.onActivity {
                    surface.showController()
                    assertTrue(surface.findViewById<View>(androidx.media3.ui.R.id.exo_settings).performClick())
                }
                val audio = awaitView(scenario) { windowLabel(it, "音轨") }
                scenario.onActivity { clickListRow(audio) }
                val automatic = awaitView(scenario) { windowLabel(it, "自动") }
                return scenario.onActivity { automatic.rootView.allViews.filterIsInstance<ListView>().first() }
            }
            val automatic = audioChoices()
            capture(scenario, automatic.rootView, "audio", mode, "自动")
            scenario.onActivity {
                assertTrue(automatic.isItemChecked(0))
                assertTrue(automatic.performItemClick(automatic.getChildAt(1), 1, automatic.adapter.getItemId(1)))
                assertTrue(surface.player!!.trackSelectionParameters.overrides.values.any { it.type == C.TRACK_TYPE_AUDIO })
            }
            val manual = audioChoices()
            scenario.onActivity {
                assertTrue(manual.isItemChecked(1))
                assertTrue(manual.performItemClick(manual.getChildAt(0), 0, manual.adapter.getItemId(0)))
                assertTrue(surface.player!!.trackSelectionParameters.overrides.values.none { it.type == C.TRACK_TYPE_AUDIO })
                surface.showController()
                assertTrue(surface.findViewById<View>(R.id.playback_subtitles).performClick())
            }
            val captions = awaitView(scenario) { windowLabel(it, "关闭") }
            capture(scenario, captions.rootView, "subtitles", mode, "关闭")
            scenario.onActivity { assertTrue(label(captions.rootView, "取消")!!.performClick()) }
        }
    }

    private fun checkAppearances(check: (ThemeMode) -> Unit) {
        val prefs = instrument.targetContext.appearancePreferences()
        val previous = prefs.getString(APPEARANCE_THEME, null)
        try {
            for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
                assertTrue(prefs.edit().putString(APPEARANCE_THEME, mode.name).commit())
                check(mode)
            }
        } finally {
            assertTrue(prefs.edit().putString(APPEARANCE_THEME, previous).commit())
        }
    }

    private fun backing(mode: ThemeMode) = if (mode == ThemeMode.Dark) Color.BLACK else Color.WHITE

    private fun label(root: View, name: String) = root.allViews.filterIsInstance<TextView>()
        .firstOrNull { it.isShown && it.width > 0 && it.text.toString() == name }

    private fun windowLabel(activity: PlaybackActivity, name: String) = WindowInspector.getGlobalWindowViews()
        .filter { it !== activity.window.decorView && it.display?.displayId == activity.display?.displayId }
        .firstNotNullOfOrNull { label(it, name) }

    private fun clickListRow(label: TextView) {
        val list = label.rootView.allViews.filterIsInstance<ListView>().first { it.getPositionForView(label) >= 0 }
        val position = list.getPositionForView(label)
        assertTrue(list.performItemClick(list.getChildAt(position - list.firstVisiblePosition),
            position, list.adapter.getItemId(position)))
    }

    private fun <V : View> awaitView(scenario: FixtureActivity<PlaybackActivity>, find: (PlaybackActivity) -> V?): V {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            scenario.onActivity(find)?.let { return it }
            SystemClock.sleep(75)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Playback control did not appear")
    }

    private fun assertReadable(text: TextView, container: View, behind: Int) {
        val fg = text.currentTextColor
        val original = text.textColors
        val position = IntArray(2).also(text::getLocationInWindow)
        val offset = IntArray(2).also(container::getLocationInWindow)
        val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            // Draw the actual surface at the center of the label, removing only the glyphs.
            text.setTextColor(Color.TRANSPARENT)
            val canvas = Canvas(pixel)
            canvas.drawColor(behind)
            canvas.translate((offset[0] - position[0] - text.width / 2).toFloat(),
                (offset[1] - position[1] - text.height / 2).toFloat())
            container.draw(canvas)
            val bg = ColorUtils.compositeColors(pixel.getPixel(0, 0), behind)
            val contrast = ColorUtils.calculateContrast(fg, bg)
            assertTrue("${text.text}: fg=${Integer.toHexString(fg)}, bg=${Integer.toHexString(bg)}, ratio=$contrast",
                contrast >= 4.5)
        } finally {
            text.setTextColor(original)
            pixel.recycle()
        }
    }

    private fun assertWhiteIcon(icon: Drawable) {
        val saved = Rect(icon.bounds)
        val rendered = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        try {
            icon.setBounds(0, 0, 48, 48)
            icon.draw(Canvas(rendered))
            val pixels = IntArray(48 * 48)
            rendered.getPixels(pixels, 0, 48, 0, 0, 48, 48)
            val solid = pixels.filter { Color.alpha(it) >= 200 }
            assertTrue("Player icon has no visible white glyph",
                solid.isNotEmpty() && solid.all { ColorUtils.calculateLuminance(it) > 0.85 })
        } finally {
            icon.bounds = saved
            rendered.recycle()
        }
    }

    private fun capture(scenario: FixtureActivity<PlaybackActivity>, root: View, name: String,
                        mode: ThemeMode, sample: String, darkSurface: Boolean = mode == ThemeMode.Dark) {
        instrument.waitForIdleSync()
        // Include the settled One UI window tint and blur after the dialog entrance animation.
        SystemClock.sleep(400)
        val physicalId = InstrumentationRegistry.getArguments().getString("captureDisplayId")?.toULongOrNull()
        val composed = physicalId?.let {
            val automation = instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p -d $it"))
                .use(BitmapFactory::decodeStream)
        }
        val screen = composed ?: scenario.onActivity {
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        try {
            File(instrument.targetContext.cacheDir, "qa-player-${mode.name.lowercase()}-$name.png").outputStream().use {
                screen.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val rect = Rect()
            val foreground = scenario.onActivity {
                val text = checkNotNull(label(root, sample)) { "Missing $sample on $name" }
                val xy = IntArray(2).also(text::getLocationOnScreen)
                if (composed == null) {
                    val origin = IntArray(2).also(root::getLocationOnScreen)
                    xy[0] -= origin[0]
                    xy[1] -= origin[1]
                }
                rect.set(xy[0], xy[1], xy[0] + text.width, xy[1] + text.height)
                text.currentTextColor
            }
            assertTrue("$sample outside captured window: $rect", rect.intersect(0, 0, screen.width, screen.height))
            val colors = ArrayList<Int>()
            for (y in rect.top until rect.bottom step 4) for (x in rect.left until rect.right step 4) {
                colors.add(ColorUtils.compositeColors(screen.getPixel(x, y), if (darkSurface) Color.BLACK else Color.WHITE))
            }
            // A blurred One UI popup has many background shades. The flat glyph fill can be
            // the single most common color even though most pixels belong to its surface.
            colors.sortBy(ColorUtils::calculateLuminance)
            val bg = colors[colors.size / 2]
            val contrast = ColorUtils.calculateContrast(foreground, bg)
            val description = "${mode.name}/$name $sample rect=$rect fg=${foreground.toUInt().toString(16)} bg=${bg.toUInt().toString(16)}"
            assertTrue("$description contrast=$contrast", contrast >= 4.5)
            assertEquals(description, darkSurface, ColorUtils.calculateLuminance(bg) < 0.32)
        } finally {
            screen.recycle()
        }
    }
}
