package com.snaix.pikpak

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.allViews
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

@OptIn(UnstableApi::class)
class CloudSubtitlePlaybackTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val folder = "Translations"
    private val caption = "Traditional Chinese.SRT"
    private val cue = "雲端字幕測試"

    @Test fun browseAnotherFolderAndRenderCaptionOnVideoWithoutTextTracks() {
        exercisePlayback("subtitle_video", embedded = false)
    }

    @Test fun chosenCloudCaptionReplacesEmbeddedCaption() {
        exercisePlayback("native_playback", embedded = true)
    }

    @Test fun cachedSubtitleCanBeSelectedWhileFolderRefreshIsPending() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val local = Uri.parse("android.resource://${instrument.context.packageName}/raw/subtitle_video")
        val intent = PlaybackActivity.localIntent(instrument.targetContext, local, "Movie")
        val cached = CloudFile("cached-caption", caption, false)
        val remote = CompletableDeferred<List<CloudFile>>()
        val started = CountDownLatch(1)
        var selected: CloudFile? = null
        launchFixtureActivity(PlaybackActivity::class.java, intent).use { screen ->
            val dialog = screen.onActivity { activity ->
                showCloudSubtitlePicker(activity, listOf(Folder("movies", "Movies")),
                    readCachedFolder = { parent ->
                        check(parent == "movies")
                        listOf(cached, CloudFile("text", "notes.txt", false))
                    },
                    readFolder = {
                        started.countDown()
                        remote.await()
                    },
                    onSelected = { selected = it })
            }
            try {
                assertTrue("Folder refresh started", started.await(5, TimeUnit.SECONDS))
                await(screen, "cached subtitle during refresh") { label(it, caption) }
                screen.onActivity { assertTrue(label(it, "notes.txt") == null) }
                click(screen, caption)
                screen.onActivity {
                    assertEquals(cached.id, selected?.id)
                    assertTrue("Selected before refresh finished", !remote.isCompleted && !dialog.isShowing)
                }
            } finally {
                remote.complete(emptyList())
            }
        }
    }

    private fun exercisePlayback(video: String, embedded: Boolean) {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        withFixtureDrive {
            val uri = Uri.parse("android.resource://${instrument.context.packageName}/raw/$video")
            val suggestions = if (embedded) listOf(CloudFile("caption", caption, false)) else emptyList()
            val intent = PlaybackActivity.intent(instrument.targetContext, CloudFile("", "Movie.mp4", false),
                JSONObject(), suggestions, listOf(Folder("movies", "Movies"))).setData(uri)
            launchFixtureActivity(PlaybackActivity::class.java, intent).use { screen ->
                val surface = await(screen, "video ready") { activity ->
                    activity.window.decorView.allViews.filterIsInstance<PlayerView>().firstOrNull {
                        it.player?.playbackState == Player.STATE_READY
                    }
                }
                val player = screen.onActivity {
                    (checkNotNull(surface.player) as ExoPlayer).apply {
                        setAudioAttributes(AudioAttributes.DEFAULT, false)
                        setHandleAudioBecomingNoisy(false)
                        volume = 0f
                        pause()
                        seekTo(2_000)
                        assertEquals(embedded, currentTracks.containsType(C.TRACK_TYPE_TEXT))
                    }
                }
                screen.onActivity {
                    surface.showController()
                    val button = surface.findViewById<View>(R.id.playback_subtitles)
                    assertTrue(button.isShown && button.isEnabled && button.performClick())
                }
                val local = screen.onActivity { it.getString(R.string.ui_import_local_subtitles) }
                await(screen, "local import option") { label(it, local) }
                click(screen, screen.onActivity { it.getString(R.string.ui_choose_cloud_subtitles) })
                if (!embedded) {
                    val up = screen.onActivity { it.getString(R.string.ui_up_one_level) }
                    await(screen, "no subtitles in the movie's own folder") {
                        label(it, it.getString(R.string.subtitle_picker_empty))
                    }
                    click(screen, up)
                    click(screen, folder)
                    await(screen, "subtitles in chosen folder") { label(it, caption) }
                    screen.onActivity { assertTrue(label(it, "notes.txt") == null) }
                    click(screen, up)
                    click(screen, folder)
                }
                if (embedded) {
                    screen.onActivity { player.play() }
                    await(screen, "movie playing with embedded tracks") { player.takeIf { it.isPlaying } }
                }
                val previousPosition = screen.onActivity { player.currentPosition }
                click(screen, caption)
                await(screen, "selected cloud subtitle on prepared video") {
                    val config = player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.singleOrNull()
                    config?.takeIf { it.label == caption && player.playbackState == Player.STATE_READY &&
                        player.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT) }
                }
                screen.onActivity {
                    assertEquals("Preserve playback state", embedded, player.playWhenReady)
                    if (embedded) assertTrue("Video restarted", player.currentPosition >= previousPosition - 100)
                    else {
                        assertTrue("Unexpected position ${player.currentPosition}", player.currentPosition in 1_900..2_100)
                        player.play()
                    }
                }
                val rendered = await(screen, "cloud caption rendered by Media3") {
                    player.currentCues.cues.firstOrNull { it.text.toString() == cue }
                }
                assertEquals(cue, rendered.text.toString())
            }
        }
    }

    private fun label(activity: PlaybackActivity, title: String): TextView? =
        WindowInspector.getGlobalWindowViews().asReversed().asSequence()
            .filter { it !== activity.window.decorView && it.display?.displayId == activity.window.decorView.display?.displayId }
            .flatMap { it.allViews }.filterIsInstance<TextView>()
            .firstOrNull { it.isShown && it.text.toString() == title }

    private fun click(screen: FixtureActivity<PlaybackActivity>, title: String) {
        val item = await(screen, title) { label(it, title) }
        screen.onActivity {
            val list = item.rootView.allViews.filterIsInstance<ListView>().firstOrNull { it.getPositionForView(item) >= 0 }
            val clicked = if (list == null) item.performClick() else {
                val position = list.getPositionForView(item)
                list.performItemClick(list.getChildAt(position - list.firstVisiblePosition), position, list.adapter.getItemId(position))
            }
            assertTrue("Click $title", clicked)
        }
    }

    private fun <T : Any> await(screen: FixtureActivity<PlaybackActivity>, description: String,
                                read: (PlaybackActivity) -> T?): T {
        val end = SystemClock.elapsedRealtime() + 12_000
        do {
            screen.onActivity(read)?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < end)
        error("Timed out waiting for $description")
    }

    private fun withFixtureDrive(action: () -> Unit) {
        val test = instrument.context
        val target = instrument.targetContext
        assumeTrue("Fixture folders require an installation without a personal directory cache",
            TransferHistory(target).verifiedAccountScope() == null)
        val tls = loopbackTls(test)
        val previousTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val bases = listOf("PIKPAK_DRIVE_BASE_URL", "PIKPAK_AUTH_BASE_URL").associateWith(Os::getenv)
        val dir = Files.createTempDirectory(target.cacheDir.toPath(), "subtitle-fixture-").toFile()
        try {
            val session = File(dir, "session.json").apply {
                writeText("""{"access_token":"fixture","refresh_token":"fixture","expires_at_unix":4102444800}""")
            }
            val loopback = InetAddress.getByName("127.0.0.1")
            ServerSocket(0, 8, loopback).use { api ->
                tls.serverSocketFactory.createServerSocket(0, 8, loopback).use { cdn ->
                    val captionUrl = "https://127.0.0.1:${cdn.localPort}/caption.srt"
                    serve(cdn, "application/x-subrip") { "1\n00:00:00,000 --> 00:00:09,500\n$cue\n\n" }
                    serve(api, "application/json") { request ->
                        when (request.path) {
                            "/drive/v1/files" -> when (val parent = request.getQueryParameter("parent_id").orEmpty()) {
                                "" -> """{"files":[{"name":"$folder","id":"translations","kind":"drive#folder"}]}"""
                                "movies" -> """{"files":[{"name":"Movie.mp4","id":"movie","kind":"drive#file"}]}"""
                                "translations" -> """{"files":[{"name":"$caption","id":"caption","kind":"drive#file"},
                                    {"name":"notes.txt","id":"text","kind":"drive#file"}]}"""
                                else -> error("Unexpected folder $parent")
                            }
                            "/drive/v1/files/caption" -> """{"name":"$caption","web_content_link":"$captionUrl"}"""
                            else -> error("Unexpected API path ${request.path}")
                        }
                    }
                    bases.keys.forEach { Os.setenv(it, "http://127.0.0.1:${api.localPort}", true) }
                    val init = JSONObject().put("op", "init").put("path", session.path)
                    assertTrue(JSONObject(NativeClient.request(init.toString())).getBoolean("value"))
                    HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
                    action()
                }
            }
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previousTls)
            bases.forEach { (key, value) -> if (value == null) Os.unsetenv(key) else Os.setenv(key, value, true) }
            try { CloudApi(target).call("init") } finally { dir.deleteRecursively() }
        }
    }

    private fun serve(server: ServerSocket, mime: String, body: (Uri) -> String) {
        thread(name = "subtitle-http-fixture", isDaemon = true) {
            while (!server.isClosed) {
                val connection = try { server.accept() } catch (error: SocketException) {
                    if (server.isClosed) break else throw error
                }
                connection.use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                    while (!input.readLine().isNullOrEmpty()) { /* consume HTTP headers */ }
                    val bytes = body(Uri.parse("http://localhost$path")).encodeToByteArray()
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n").encodeToByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
    }
}
