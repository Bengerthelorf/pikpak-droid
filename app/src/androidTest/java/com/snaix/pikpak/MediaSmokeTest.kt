package com.snaix.pikpak

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class MediaSmokeTest {
    @Test
    fun nativePlayerDecodesSeeksAndDetectsEmbeddedSubtitles() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val testContext = instrument.context
        val fixture = Uri.parse("android.resource://${testContext.packageName}/raw/native_playback")
        val intent = PlaybackActivity.localIntent(instrument.targetContext, fixture, "播放测试")
        launchFixtureActivity(PlaybackActivity::class.java, intent).use { scenario ->
            scenario.onActivity { activity ->
                val player = checkNotNull(findPlayer(activity.window.decorView)?.player)
                player.addListener(object : Player.Listener {
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        Log.i("PikPakMediaTest", "play=$playWhenReady reason=$reason lifecycle=${activity.lifecycle.currentState}")
                    }
                })
                if (InstrumentationRegistry.getArguments().getString("playbackIsolation") == "true") {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    (player as ExoPlayer).apply {
                        volume = 0f
                        setAudioAttributes(AudioAttributes.DEFAULT, false)
                        setHandleAudioBecomingNoisy(false)
                        play()
                    }
                }
            }
            expect(scenario) { player ->
                player.playbackState == Player.STATE_READY && player.currentPosition > 250 &&
                    player.currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO) &&
                    player.currentTracks.isTypeSupported(C.TRACK_TYPE_AUDIO) &&
                    player.currentTracks.isTypeSupported(C.TRACK_TYPE_TEXT)
            }
            scenario.onActivity {
                val surface = checkNotNull(findPlayer(it.window.decorView))
                val player = checkNotNull(surface.player)
                surface.showController()
                assertTrue(surface.findViewById<View>(R.id.playback_transport).isShown)
                assertTrue(surface.findViewById<PlaybackSeekBar>(androidx.media3.ui.R.id.exo_progress).isShown)
                assertTrue(surface.findViewById<View>(androidx.media3.ui.R.id.exo_settings).isShown)
                assertTrue(surface.findViewById<View>(R.id.playback_subtitles).isEnabled)
                assertTrue(surface.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).performClick())
                assertFalse(player.playWhenReady)
                captureControls(it)
                assertTrue(surface.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd_with_amount).performClick())
            }
            expect(scenario) { it.currentPosition >= 9_900 }
            scenario.onActivity {
                val surface = checkNotNull(findPlayer(it.window.decorView))
                assertTrue(surface.findViewById<View>(androidx.media3.ui.R.id.exo_rew_with_amount).performClick())
            }
            expect(scenario) { it.currentPosition in 0..50 }
            scenario.onActivity {
                val surface = checkNotNull(findPlayer(it.window.decorView))
                val player = checkNotNull(surface.player)
                player.setPlaybackSpeed(1.5f)
                assertEquals(1.5f, player.playbackParameters.speed)
                val slider = surface.findViewById<PlaybackSeekBar>(androidx.media3.ui.R.id.exo_progress)
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now, action, slider.width / 2f, slider.height / 2f, 0)
                    try {
                        assertTrue(slider.dispatchTouchEvent(event))
                    } finally {
                        event.recycle()
                    }
                }
            }
            expect(scenario) { it.currentPosition in 4_750..5_250 }
            scenario.onActivity { activity ->
                val player = checkNotNull(findPlayer(activity.window.decorView)?.player)
                player.seekTo(0)
                player.play()
            }
            expect(scenario) { it.isPlaying }
            awaitFixtureActivity(SettingsActivity::class.java) {
                scenario.onActivity { activity ->
                    activity.startActivity(Intent(activity, SettingsActivity::class.java))
                }
            }.use {
                expect(scenario) { !it.playWhenReady }
            }
            expect(scenario) { it.isPlaying }
        }
    }

    @Test
    fun narrowPlaybackKeepsEveryControlVisibleAndBackExitsFullscreen() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val fixture = Uri.parse("android.resource://${instrument.context.packageName}/raw/native_playback")
        val intent = PlaybackActivity.localIntent(instrument.targetContext, fixture, "播放测试")
        launchFixtureActivity(PlaybackActivity::class.java, intent).use { scenario ->
            expect(scenario) { player ->
                player.playbackState == Player.STATE_READY && player.currentTracks.isTypeSupported(C.TRACK_TYPE_TEXT)
            }
            val narrowLayout = CountDownLatch(1)
            scenario.onActivity { activity ->
                val surface = checkNotNull(findPlayer(activity.window.decorView))
                surface.controllerShowTimeoutMs = 0
                surface.showController()
                val transport = surface.findViewById<View>(R.id.playback_transport)
                // Preserve the 12dp side margins when simulating a 305dp-wide window.
                val width = activity.dp(281)
                if (transport.width in 1..width) {
                    narrowLayout.countDown()
                } else {
                    transport.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                        if (view.width == width) narrowLayout.countDown()
                    }
                    transport.layoutParams = (transport.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
                }
            }
            assertTrue("Player transport did not lay out at the compact width", narrowLayout.await(5, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val surface = checkNotNull(findPlayer(decor))
                val transport = surface.findViewById<View>(R.id.playback_transport)
                val transportBounds = Rect()
                assertTrue(transport.getGlobalVisibleRect(transportBounds))
                val controlIds = intArrayOf(
                    androidx.media3.ui.R.id.exo_rew_with_amount, androidx.media3.ui.R.id.exo_play_pause,
                    androidx.media3.ui.R.id.exo_ffwd_with_amount, R.id.playback_subtitles,
                    androidx.media3.ui.R.id.exo_settings, androidx.media3.ui.R.id.exo_fullscreen,
                )
                val controlBounds = controlIds.map { id ->
                    val button = surface.findViewById<View>(id)
                    Rect().apply {
                        val name = activity.resources.getResourceEntryName(id)
                        assertTrue("$name has no visible hit target", button.getGlobalVisibleRect(this))
                        assertTrue("$name extends outside the playback transport", transportBounds.contains(this))
                        assertEquals("$name has a clipped width", activity.dp(48), width())
                        assertEquals("$name has a clipped height", activity.dp(48), height())
                    }
                }
                assertTrue(controlBounds.last().top >= controlBounds.first().bottom)

                val fullscreen = surface.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)
                assertTrue(fullscreen.performClick())
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
                val navigation = ArrayList<View>()
                decor.findViewsWithText(navigation, "返回文件", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                assertTrue(navigation.single().performClick())
                assertFalse(activity.isFinishing)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)

                assertTrue(fullscreen.performClick())
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
                activity.onBackPressedDispatcher.onBackPressed()
                assertFalse(activity.isFinishing)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
            }
        }
    }

    private fun captureControls(activity: PlaybackActivity) {
        val window = activity.window.decorView
        val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        try {
            window.draw(Canvas(bitmap))
            File(activity.cacheDir, "oneui-player.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun expect(scenario: FixtureActivity<PlaybackActivity>, condition: (Player) -> Boolean) {
        val timeout = SystemClock.elapsedRealtime() + 10_000
        do {
            if (scenario.onActivity { findPlayer(it.window.decorView)?.player?.let(condition) == true }) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < timeout)
        val state = scenario.onActivity { activity ->
            findPlayer(activity.window.decorView)?.player?.let { player ->
                "state=${player.playbackState} play=${player.playWhenReady} position=${player.currentPosition} " +
                    "suppression=${player.playbackSuppressionReason} lifecycle=${activity.lifecycle.currentState} " +
                    "video=${player.currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO)} " +
                    "audio=${player.currentTracks.isTypeSupported(C.TRACK_TYPE_AUDIO)} " +
                    "text=${player.currentTracks.isTypeSupported(C.TRACK_TYPE_TEXT)}"
            }
        }
        assertTrue("Expected Media3 playback state was not reached ($state)", false)
    }

    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findPlayer(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
