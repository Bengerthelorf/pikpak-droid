package com.snaix.pikpak

import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.allViews
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.oneuiproject.oneui.layout.NavDrawerLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class PlaybackGesturesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun sideTapsSeekAndBothControlRowsRemainOperable() = withVideo { workspace, full, player ->
        full.onActivity {
            player.pause()
            player.seekTo(25_000)
            surface(it).hideController()
        }
        waitUntil("video surface ready for taps") {
            full.onActivity { !surface(it).isControllerFullyVisible && player.currentPosition == 25_000L }
        }
        tapVideo(full, .85f, .38f)
        full.onActivity { activity ->
            tap(activity, surface(activity), .85f, .38f)
            assertFixedSeekHighlight(activity, .85f, .38f)
        }
        waitUntil("right double tap seeks ten seconds") { full.onActivity { player.currentPosition == 35_000L } }
        tapVideo(full, .85f, .38f)
        waitUntil("third tap seeks ten more seconds") { full.onActivity { player.currentPosition == 45_000L } }
        full.onActivity {
            assertEquals(it.getString(R.string.gesture_seconds_forward, 20),
                it.findViewById<TextView>(R.id.playback_seek_feedback).text.toString())
        }
        finishTapSequence()

        tapVideo(full, .15f, .62f)
        full.onActivity { activity ->
            tap(activity, surface(activity), .15f, .62f)
            assertFixedSeekHighlight(activity, .15f, .62f)
        }
        waitUntil("left double tap seeks ten seconds") { full.onActivity { player.currentPosition == 35_000L } }
        finishTapSequence()

        full.onActivity { surface(it).hideController() }
        tapVideo(full, .5f, .37f)
        waitUntil("center tap reveals both sets of controls") {
            full.onActivity { it.findViewById<View>(R.id.playback_center_play_pause).isShown &&
                it.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).isShown }
        }
        fun tapSeek(id: Int, target: Long) {
            full.onActivity { tapButton(it, id) }
            waitUntil("seek control reaches $target ms") { full.onActivity { player.currentPosition == target } }
        }
        tapSeek(R.id.playback_center_back, 25_000L)
        tapSeek(R.id.playback_center_forward, 35_000L)
        tapSeek(androidx.media3.ui.R.id.exo_ffwd_with_amount, 45_000L)
        tapSeek(androidx.media3.ui.R.id.exo_rew_with_amount, 35_000L)
        full.onActivity { tapButton(it, R.id.playback_center_play_pause) }
        waitUntil("center play") { full.onActivity { player.playWhenReady } }
        full.onActivity { tapButton(it, androidx.media3.ui.R.id.exo_play_pause) }
        waitUntil("bottom pause") { full.onActivity { !player.playWhenReady } }
        assertFalse(workspace.onActivity { it.hasWindowFocus() })
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun playingPortraitVideoContinuesInTheLowerLeftMiniPlayer() = withVideo { workspace, full, player ->
        full.onActivity {
            surface(it).showController()
            tapButton(it, androidx.media3.ui.R.id.exo_settings)
        }
        waitUntil("portrait fullscreen menu row accepts the click") {
            full.onActivity { activity ->
                val name = activity.getString(R.string.playback_fullscreen_portrait)
                val list = WindowInspector.getGlobalWindowViews().asReversed().asSequence()
                    .filter { it.display == activity.window.decorView.display && it !== activity.window.decorView && it.hasWindowFocus() }
                    .flatMap { it.allViews }.filterIsInstance<ListView>()
                    .firstOrNull { it.isShown && it.isLaidOut } ?: return@onActivity false
                val position = (list.firstVisiblePosition..list.lastVisiblePosition).firstOrNull {
                    (list.getItemAtPosition(it) as? MenuItem)?.title?.toString() == name
                } ?: return@onActivity false
                val row = list.getChildAt(position - list.firstVisiblePosition)
                if (!row.allViews.filterIsInstance<TextView>().any { it.isShown && it.text.toString() == name })
                    return@onActivity false
                // Click the rendered row matched to its actual menu item, even while the popup is animating.
                row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) ||
                    list.performItemClick(row, position, list.getItemIdAtPosition(position))
            }
        }
        val status = { full.onActivity { activity ->
            val decor = activity.window.decorView
            "request=${activity.requestedOrientation}, window=${decor.width}x${decor.height}, " +
                "focus=${activity.hasWindowFocus()}, PiP=${activity.isInPictureInPictureMode}, " +
                "play=${player.playWhenReady}, state=${player.playbackState}, " +
                "suppression=${player.playbackSuppressionReason}, at=${player.currentPosition}"
        } }
        waitUntil("portrait fullscreen orientation after menu tap", status) {
            full.onActivity { it.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT &&
                it.window.decorView.height > it.window.decorView.width }
        }
        full.onActivity {
            player.setPlaybackSpeed(1.25f)
            player.seekTo(12_000)
            player.play()
        }
        waitUntil("portrait fullscreen video advances before dragging", status) {
            full.onActivity { player.isPlaying && player.currentPosition > 12_000 }
        }
        swipeDown(full)
        val mini = awaitMini(workspace, player)
        val first = mini.onActivity { player.currentPosition }
        waitUntil("mini player keeps advancing") { mini.onActivity { player.isPlaying && player.currentPosition >= first + 180 } }
        mini.onActivity { tapButton(it, R.id.playback_mini_video) }
        val restored = awaitFull(mini, player)
        restored.onActivity {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, it.requestedOrientation)
            assertEquals(1.25f, player.playbackParameters.speed, .001f)
            assertTrue(player.isPlaying && player.currentPosition >= first)
        }
    }

    @Test fun pausedLandscapeVideoRetainsItsPositionAndSubtitleSelection() = withVideo("native_playback") { workspace, full, player ->
        full.onActivity {
            player.pause()
            player.seekTo(3_200)
            player.setPlaybackSpeed(1.5f)
            val texts = player.currentTracks.groups.first { group -> group.type == C.TRACK_TYPE_TEXT }
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(texts.mediaTrackGroup, 0)).build()
            surface(it).showController()
            tapButton(it, androidx.media3.ui.R.id.exo_fullscreen)
        }
        waitUntil("landscape playback mode requested") {
            full.onActivity { it.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE &&
                it.window.decorView.width > it.window.decorView.height && player.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT) }
        }
        val selection = full.onActivity { player.trackSelectionParameters }
        swipeDown(full)
        val mini = awaitMini(workspace, player)
        mini.onActivity {
            assertEquals(3_200L, player.currentPosition)
            assertFalse(player.playWhenReady)
            tapButton(it, R.id.playback_mini_video)
        }
        val restored = awaitFull(mini, player)
        waitUntil("landscape window restored") {
            restored.onActivity { it.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE &&
                it.window.decorView.width > it.window.decorView.height }
        }
        restored.onActivity {
            assertEquals(selection, player.trackSelectionParameters)
            assertEquals(1.5f, player.playbackParameters.speed, .001f)
            assertEquals(3_200L, player.currentPosition)
            assertFalse(player.playWhenReady)
        }
    }

    @Test fun systemHomeKeepsFullVideoPlayingInPictureInPicture() = homePip(fromMini = false)

    @Test fun miniPlayerFollowsSettingsBackToDrive() =
        WorkspaceFixtureApplication(instrumentation.targetContext).use { app ->
            withVideo { workspace, full, player ->
                full.onActivity { player.seekTo(7_000); player.play() }
                waitUntil("video advances before navigating") { full.onActivity { player.isPlaying && player.currentPosition > 7_000 } }
                swipeDown(full)
                val drive = awaitMini(workspace, player)
                drive.onActivity { activity ->
                    val model = ViewModelProvider(activity, viewModelFactory {
                        initializer { CloudModel(app, FixtureGateway()) }
                    })["mini-drive", CloudModel::class.java]
                    activity.showFixture(model)
                }
                val gear = dev.oneuiproject.oneui.design.R.id.oui_des_drawer_header_button
                drive.onActivity {
                    it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout).setDrawerOpen(true, animate = false)
                }
                waitUntil("drawer settings button ready") { drive.onActivity {
                    it.findViewById<View>(gear)?.let { button -> button.isShown && button.width > 0 } == true
                } }
                awaitFixtureActivity(SettingsActivity::class.java) {
                    drive.onActivity { tapButton(it, gear) }
                }.use { settings ->
                    fun awaitSettingsMini() = waitUntil("original settings window hosts the same playing video") {
                        settings.onActivity { activity ->
                            val mini = activity.findViewById<PlayerView>(R.id.playback_mini_video)
                            activity.hasWindowFocus() && mini?.isShown == true && mini.player === player &&
                                mini.videoSurfaceView?.isShown == true && player.isPlaying
                        }
                    }
                    awaitSettingsMini()
                    settings.onActivity { tapButton(it, R.id.playback_mini_video) }
                    val expanded = awaitFull(drive, player)
                    swipeDown(expanded)
                    awaitSettingsMini()
                    val position = settings.onActivity { player.currentPosition }
                    settings.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    waitUntil("Back returns to the original drive with video still advancing") {
                        drive.onActivity { activity ->
                            val mini = activity.findViewById<PlayerView>(R.id.playback_mini_video)
                            activity.hasWindowFocus() && activity.findViewById<View>(R.id.workspace_list)?.isShown == true &&
                                mini?.isShown == true && mini.player === player && player.isPlaying &&
                                player.currentPosition > position
                        }
                    }
                }
            }
        }

    @Test fun systemHomeKeepsMiniVideoPlayingInPictureInPicture() = homePip(fromMini = true)

    @Test fun dismissingSystemPictureInPictureClosesTheMiniPlayer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realHomePip") == "true")
        val target = instrumentation.targetContext
        assumeTrue(target.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        withVideo { workspace, full, player ->
            full.onActivity { player.play() }
            waitUntil("video starts before minimizing") { full.onActivity { player.isPlaying } }
            swipeDown(full)
            val mini = awaitMini(workspace, player)
            val display = mini.onActivity { it.window.decorView.display.displayId }
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "input -d $display keyevent KEYCODE_HOME")).use { it.readBytes() }
            waitUntil("system pinned the mini window before dismissal") { mini.onActivity { it.isInPictureInPictureMode } }
            waitUntil("system PiP exposes and accepts its dismiss action") {
                performSystemPipAction(AccessibilityNodeInfo.ACTION_DISMISS)
            }
            waitUntil("dismissing system PiP destroys the parked player") {
                full.onActivity { InAppPlayback.mini.value == null && player.isReleased }
            }
            val launcher = checkNotNull(target.packageManager.getLaunchIntentForPackage(target.packageName)?.component)
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am start --display $display -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                    "-n ${launcher.flattenToShortString()}")).use { it.readBytes() }
            var resumed: MainActivity? = null
            waitUntil("reopened workspace has no mini player") {
                workspace.onActivity {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull { screen ->
                            screen.hasWindowFocus() && !screen.isInPictureInPictureMode &&
                                screen.findViewById<View>(R.id.playback_mini)?.isShown != true
                        }
                    resumed != null && InAppPlayback.mini.value == null && player.isReleased
                }
            }
            FixtureActivity(checkNotNull(resumed)).close()
        }
    }

    @Test fun miniDragFollowsTheFingerAndDocksAtAnotherCorner() = withVideo { workspace, full, player ->
        if (ValueAnimator.areAnimatorsEnabled()) {
            full.onActivity { activity ->
                val video = surface(activity)
                val xy = IntArray(2).also(video::getLocationInWindow)
                val x = xy[0] + video.width * .5f
                val y = xy[1] + video.height * .25f
                val down = SystemClock.uptimeMillis()
                dispatch(activity, down, MotionEvent.ACTION_DOWN, x, y)
                dispatch(activity, down, MotionEvent.ACTION_MOVE, x, y + video.height * .24f)
                assertTrue("video shrinks before the finger lifts", video.scaleX > 0f && video.scaleX < .99f)
                dispatch(activity, down, MotionEvent.ACTION_CANCEL, x, y + video.height * .24f)
            }
            waitUntil("cancelling the pull restores the video") { full.onActivity {
                surface(it).scaleX == 1f && it.hasWindowFocus() && surface(it).player === player
            } }
        }
        swipeDown(full)
        val mini = awaitMini(workspace, player)
        val (start, end) = mini.onActivity { activity ->
            val card = activity.findViewById<View>(R.id.playback_mini)
            val (x, y) = IntArray(2).also(card::getLocationInWindow)
            val root = activity.window.decorView
            (x + card.width / 2f to y + card.height / 2f) to
                (root.width - card.width * .7f to root.height * .23f)
        }
        val down = SystemClock.uptimeMillis()
        mini.onActivity { dispatch(it, down, MotionEvent.ACTION_DOWN, start.first, start.second) }
        for (i in 1..8) {
            val t = i / 8f
            mini.onActivity { dispatch(it, down, MotionEvent.ACTION_MOVE,
                start.first + (end.first - start.first) * t, start.second + (end.second - start.second) * t) }
            SystemClock.sleep(25)
        }
        mini.onActivity { activity ->
            val card = Rect().also { activity.findViewById<View>(R.id.playback_mini).getGlobalVisibleRect(it) }
            assertTrue("held card follows the finger", card.contains(end.first.toInt(), end.second.toInt()))
            dispatch(activity, down, MotionEvent.ACTION_UP, end.first, end.second)
        }
        waitUntil("card docks inside the top right corner") { mini.onActivity { activity ->
            val root = activity.window.decorView
            val card = Rect().also { activity.findViewById<View>(R.id.playback_mini).getGlobalVisibleRect(it) }
            card.centerX() > root.width * .65 && card.right <= root.width && card.top < root.height * .1
        } }
        val expandedFromCard = AtomicBoolean(false)
        mini.onActivity { activity ->
            val card = activity.findViewById<View>(R.id.playback_mini)
            val frames = card.viewTreeObserver
            frames.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (card.scaleX > 1.04f || !card.isAttachedToWindow) {
                        if (card.scaleX > 1.04f) expandedFromCard.set(true)
                        if (frames.isAlive) frames.removeOnPreDrawListener(this)
                    }
                    return true
                }
            })
            tapButton(activity, R.id.playback_mini_video)
        }
        val restored = awaitFull(mini, player)
        if (ValueAnimator.areAnimatorsEnabled()) assertTrue("the live card grows before activity handoff", expandedFromCard.get())
        swipeDown(restored)
        waitUntil("the same session returns to its selected corner") { mini.onActivity {
            val root = it.window.decorView
            val card = it.findViewById<View>(R.id.playback_mini) ?: return@onActivity false
            val bounds = Rect().also(card::getGlobalVisibleRect)
            card.isShown && card.scaleX == 1f && bounds.centerX() > root.width * .65 && bounds.top < root.height * .1
        } }
    }

    private fun homePip(fromMini: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realHomePip") == "true")
        assumeTrue(instrumentation.targetContext.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        withNetworkVideo(instrumentation.context) { url, requests ->
            checkHomeStream(fromMini, url, requests)
        }
    }

    private fun checkHomeStream(fromMini: Boolean, url: Uri, requests: AtomicInteger) {
        withVideo(remote = url) { workspace, full, player ->
            full.onActivity {
                player.seekTo(10_000)
                player.play()
            }
            waitUntil("video playing before Home") { full.onActivity { player.isPlaying && player.currentPosition > 10_000 } }
            val before = requests.get()
            assertTrue("the video must have been fetched over HTTPS", before > 0)
            val item = full.onActivity { player.currentMediaItem }
            val rebuffer = AtomicInteger()
            full.onActivity {
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_BUFFERING) rebuffer.incrementAndGet()
                    }
                })
            }
            val host: FixtureActivity<out Activity> = if (fromMini) {
                swipeDown(full)
                awaitMini(workspace, player)
            } else full
            val display = host.onActivity { it.window.decorView.display.displayId }
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "input -d $display keyevent KEYCODE_HOME")).use { it.readBytes() }
            waitUntil("Android placed the video in native PiP") {
                host.onActivity { it.isInPictureInPictureMode }
            }
            if (fromMini) {
                waitUntil("the mini video surface fills the pinned window") { host.onActivity { activity ->
                    val content = activity.findViewById<View>(android.R.id.content)
                    val mini = activity.findViewById<PlayerView>(R.id.playback_mini_video)
                    mini?.isShown == true && mini.isAttachedToWindow && mini.player === player &&
                        mini.videoSurfaceView?.isShown == true && content.width > 0 && content.height > 0 &&
                        mini.width >= content.width * .95 && mini.height >= content.height * .95
                } }
            }
            val start = workspace.onActivity { player.currentPosition }
            waitUntil("video continues in system PiP") { workspace.onActivity {
                player.isPlaying && player.currentPosition > start + 300
            } }
            if (Build.VERSION.SDK_INT >= 29) {
                val automation = instrumentation.uiAutomation
                automation.adoptShellPermissionIdentity(android.Manifest.permission.MEDIA_CONTENT_CONTROL)
                try {
                    val context = instrumentation.targetContext
                    val controller = context.getSystemService(MediaSessionManager::class.java)
                        .getActiveSessions(null).first { it.packageName == context.packageName }.transportControls
                    controller.pause()
                    waitUntil("system pause reaches the same player") { workspace.onActivity { !player.playWhenReady } }
                    host.onActivity { activity ->
                        assertTrue(activity.isInPictureInPictureMode)
                        val video = activity.window.decorView.allViews.filterIsInstance<PlayerView>().first { it.isShown }
                        assertTrue(video.player === player)
                        assertFalse(video.useController)
                        assertFalse(activity.findViewById<View>(R.id.playback_transport)?.isShown == true)
                        assertFalse(activity.findViewById<View>(R.id.playback_center_play_pause)?.isShown == true)
                        assertFalse(activity.window.decorView.allViews.filterIsInstance<androidx.appcompat.widget.Toolbar>().any { it.isShown })
                    }
                    controller.play()
                    waitUntil("system play resumes in PiP") { workspace.onActivity { player.isPlaying } }
                } finally { automation.dropShellPermissionIdentity() }
            }
            expandSystemPip(host)
            waitUntil("expanded PiP returns to the original window and playing video") { host.onActivity {
                val visiblePlayer = if (it is PlaybackActivity) surface(it).player
                    else it.findViewById<PlayerView>(R.id.playback_mini_video)?.player
                !it.isInPictureInPictureMode && it.hasWindowFocus() && visiblePlayer === player && player.isPlaying
            } }
            host.onActivity {
                assertTrue("return keeps the original media item", player.currentMediaItem === item)
                assertTrue("playback time does not reset", player.currentPosition >= start)
            }
            assertEquals("no extra HTTPS video GET at Home, pause or restore", before, requests.get())
            android.util.Log.i("PlaybackPiPCheck", "mini=$fromMini https=$before->${requests.get()} buffering=${rebuffer.get()}")
            assertEquals("a buffered fixture should not rebuffer on the way in or out", 0, rebuffer.get())
        }
    }

    private fun expandSystemPip(host: FixtureActivity<out Activity>) {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val previousFlags = info.flags
        try {
            info.flags = previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            automation.serviceInfo = info
            // The pinned Activity can be reparented into a system-owned task. Operate its system menu.
            val display = host.onActivity { it.window.decorView.display.displayId }
            var menuOpenedAt = 0L
            var lastActions = emptySet<String>()
            waitUntil("system PiP menu offers fullscreen", { "PiP controls=$lastActions" }) {
                val windows = automation.windows
                val pinned = windows.firstOrNull { it.isInPictureInPictureMode }
                val expand = windows.asSequence().mapNotNull { it.root }
                    .flatMap { accessibilityTree(it) }
                    .filter { it.packageName?.toString() == "com.android.systemui" && it.isVisibleToUser }
                    .toList()
                lastActions = expand.mapNotNull { it.viewIdResourceName?.substringAfterLast('/') }.toSet()
                val button = expand.firstOrNull {
                    it.viewIdResourceName?.substringAfterLast('/') in setOf("expand_button", "expand_container")
                }
                if (button != null) {
                    val rect = Rect().also(button::getBoundsInScreen)
                    if (!rect.isEmpty) {
                        shellTap(display, rect)
                        return@waitUntil true
                    }
                }
                val root = pinned?.root
                if (root?.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND } == true &&
                    root.performAction(AccessibilityNodeInfo.ACTION_EXPAND)) return@waitUntil true
                val now = SystemClock.elapsedRealtime()
                if (now - menuOpenedAt > 2_500) {
                    menuOpenedAt = now
                    val rect = Rect()
                    pinned?.getBoundsInScreen(rect)
                    if (rect.isEmpty) {
                        host.onActivity { activity ->
                            if (Build.VERSION.SDK_INT >= 30) rect.set(activity.windowManager.currentWindowMetrics.bounds)
                            else {
                                val decor = activity.window.decorView
                                val xy = IntArray(2).also(decor::getLocationOnScreen)
                                rect.set(xy[0], xy[1], xy[0] + decor.width, xy[1] + decor.height)
                            }
                        }
                    }
                    if (!rect.isEmpty) shellTap(display, rect)
                }
                false
            }
        } finally {
            info.flags = previousFlags
            automation.serviceInfo = info
        }
    }

    private fun accessibilityTree(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(root)
        for (i in 0 until root.childCount) root.getChild(i)?.let { yieldAll(accessibilityTree(it)) }
    }

    private fun shellTap(display: Int, bounds: Rect) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "input -d $display tap ${bounds.centerX()} ${bounds.centerY()}")).use { it.readBytes() }
    }

    private fun performSystemPipAction(action: Int): Boolean {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val originalFlags = info.flags
        try {
            info.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            return automation.windows.filter { it.isInPictureInPictureMode }
                .mapNotNull { it.root }
                .any { root -> root.actionList.any { it.id == action } && root.performAction(action) }
        } finally {
            info.flags = originalFlags
            automation.serviceInfo = info
        }
    }

    private fun surface(activity: PlaybackActivity) =
        activity.window.decorView.allViews.filterIsInstance<PlayerView>().first()

    private fun awaitMini(workspace: FixtureActivity<MainActivity>, player: ExoPlayer): FixtureActivity<MainActivity> {
        var result: MainActivity? = null
        waitUntil("same player appears in workspace mini player") {
            result = workspace.onActivity {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull { activity ->
                        activity.hasWindowFocus() && activity.findViewById<PlayerView>(R.id.playback_mini_video)?.let { mini ->
                            mini.isShown && mini.width > 0 && mini.player === player
                        } == true
                    }
            }
            result != null
        }
        val host = FixtureActivity(checkNotNull(result))
        waitUntil("minimize animation reaches the lower left") { host.onActivity {
            val root = Rect().also { bounds -> it.window.decorView.getGlobalVisibleRect(bounds) }
            val card = it.findViewById<View>(R.id.playback_mini)
            val mini = Rect().also(card::getGlobalVisibleRect)
            card.scaleX == 1f && mini.left < root.width() / 5 && mini.centerY() > root.height() * .75 &&
                mini.width() <= root.width() * .52
        } }
        return host
    }

    private fun awaitFull(workspace: FixtureActivity<MainActivity>, player: ExoPlayer): FixtureActivity<PlaybackActivity> {
        var result: PlaybackActivity? = null
        waitUntil("mini expands to the same player") {
            result = workspace.onActivity {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<PlaybackActivity>().firstOrNull { screen ->
                        screen.hasWindowFocus() && surface(screen).player === player
                    }
            }
            result != null
        }
        return FixtureActivity(checkNotNull(result))
    }

    private fun withVideo(raw: String = "gesture_playback", remote: Uri? = null,
                          run: (FixtureActivity<MainActivity>, FixtureActivity<PlaybackActivity>, ExoPlayer) -> Unit) {
        val context = instrumentation.targetContext
        val video = Uri.parse("android.resource://${instrumentation.context.packageName}/raw/$raw")
        launchFixtureActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).use { workspace ->
            awaitFixtureActivity(PlaybackActivity::class.java) {
                workspace.onActivity { activity ->
                    val launch = if (remote == null) PlaybackActivity.localIntent(activity, video, "Player QA")
                    else {
                        val source = JSONObject().put("label", "Original").put("url", remote.toString()).put("available", true)
                        PlaybackActivity.intent(activity, CloudFile("pip-test", "Player QA.mp4", false),
                            JSONObject().put("mime", "video/mp4").put("play", JSONArray().put(source)), emptyList())
                    }
                    activity.startActivity(launch)
                }
            }.use { full ->
                waitUntil("local test video ready") { full.onActivity { surface(it).player?.playbackState == Player.STATE_READY } }
                val player = full.onActivity {
                    (surface(it).player as ExoPlayer).apply {
                        volume = 0f
                        setAudioAttributes(AudioAttributes.DEFAULT, false)
                        setHandleAudioBecomingNoisy(false)
                    }
                }
                run(workspace, full, player)
            }
        }
    }

    private fun waitUntil(label: String, diagnostic: (() -> String)? = null, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        do {
            if (condition()) return
            SystemClock.sleep(35)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Timed out waiting for $label" + (diagnostic?.let { " (${it()})" } ?: ""))
    }

    private fun finishTapSequence() = SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout() + 130L)

    private fun tapVideo(screen: FixtureActivity<PlaybackActivity>, horizontal: Float, vertical: Float) {
        screen.onActivity { tap(it, surface(it), horizontal, vertical) }
    }

    private fun assertFixedSeekHighlight(activity: Activity, tapX: Float, tapY: Float) {
        val edge = activity.findViewById<View>(R.id.playback_seek_edge)
        assertTrue(edge.isShown)
        // Keep the native horizontal resolution to sample the thin arc, even on a large display.
        val bitmap = Bitmap.createBitmap(edge.width, 200, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(1f, bitmap.height.toFloat() / edge.height)
            edge.draw(canvas)
            val x = (bitmap.width * tapX).toInt()
            val tapped = bitmap.getPixel(x, (bitmap.height * tapY).toInt())
            assertTrue("the selected side lights up", Color.alpha(tapped) > 0)
            assertEquals("the touch origin has the same fill as the far corner", bitmap.getPixel(x, 3), tapped)
            assertEquals("the center stays clear", 0, bitmap.getPixel(bitmap.width / 2, 100))
            assertEquals("the other side stays clear", 0, bitmap.getPixel(bitmap.width - 1 - x, 100))
            val arcAlpha = (bitmap.width / 3 until bitmap.width / 2).maxOf { distance ->
                val column = if (tapX < .5f) distance else bitmap.width - 1 - distance
                Color.alpha(bitmap.getPixel(column, 100))
            }
            assertTrue("the fixed arc outlines the fill", arcAlpha > Color.alpha(tapped))
        } finally { bitmap.recycle() }
    }

    private fun tapButton(activity: Activity, id: Int) {
        val button = checkNotNull(activity.findViewById<View>(id))
        assertTrue("Button ${activity.resources.getResourceEntryName(id)} must be visible", button.isShown && button.width > 0)
        tap(activity, button, .5f, .5f)
    }

    private fun tap(activity: Activity, target: View, horizontal: Float, vertical: Float) {
        val origin = IntArray(2).also(target::getLocationInWindow)
        val x = origin[0] + target.width * horizontal
        val y = origin[1] + target.height * vertical
        val down = SystemClock.uptimeMillis()
        dispatch(activity, down, MotionEvent.ACTION_DOWN, x, y)
        dispatch(activity, down, MotionEvent.ACTION_UP, x, y)
    }

    private fun swipeDown(screen: FixtureActivity<PlaybackActivity>) {
        val down = SystemClock.uptimeMillis()
        for ((i, fraction) in listOf(.25f, .31f, .45f, .65f, .68f).withIndex()) {
            screen.onActivity { activity ->
                val video = surface(activity)
                val origin = IntArray(2).also(video::getLocationInWindow)
                val action = when (i) {
                    0 -> MotionEvent.ACTION_DOWN
                    4 -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                dispatch(activity, down, action, origin[0] + video.width * .5f, origin[1] + video.height * fraction)
            }
            if (i < 4) SystemClock.sleep(25)
        }
    }

    private fun dispatch(activity: Activity, down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            activity.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
