package com.snaix.pikpak

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit, read-only Galaxy smoke check. Never prints account or file data. */
@OptIn(UnstableApi::class)
class LiveCloudSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 90_000)
    fun cloudFolderSuppliesReadableSubtitle() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSubtitle") == "true")
        launchFixtureActivity(SettingsActivity::class.java, Intent(context, SettingsActivity::class.java)).use { screen ->
            screen.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            checkSubtitleFromSmallFolder()
        }
    }

    private fun checkSubtitleFromSmallFolder() {
        val api = CloudApi(context)
        check(api.call("init") == true) { "No saved sign-in" }
        val owner = checkNotNull(TransferHistory(context).verifiedAccountScope())
        val cache = DriveListingCache(context)
        val folders = ArrayDeque(listOf(""))
        val seen = mutableSetOf<String>()
        while (folders.isNotEmpty()) {
            val parent = folders.removeFirst()
            if (!seen.add(parent)) continue
            val directory = cache.searchDirectory(owner, parent, ".") {} ?: continue
            folders.addAll(directory.folders)
            if (directory.folders.size + directory.matches.size > 50) continue
            if (directory.matches.none { !it.folder && subtitleMime(it.name) != null }) continue
            val file = api.call("files", "parent" to parent).files()
                .firstOrNull { !it.folder && subtitleMime(it.name) != null && it.bytes > 0 } ?: continue
            val links = api.call("links", "id" to file.id) as JSONObject
            val uri = checkNotNull(httpsUri(links.stringOrNull("download"))) { "No subtitle download URL" }
            val data = DefaultHttpDataSource.Factory().createDataSource()
            try {
                data.open(DataSpec(uri))
                assertTrue("Cloud subtitle is empty", data.read(ByteArray(128), 0, 128) > 0)
            } finally {
                data.close()
            }
            return
        }
        assumeTrue("No supported subtitles in this account's sampled small folders", false)
    }

    @Test(timeout = 45_000)
    fun savedSessionOpensCloudAfterRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCloud") == "true")
        val api = CloudApi(context)
        check(api.call("init") == true) { "The app has no saved sign-in" }
        try {
            checkNotNull(api.call("files", "parent" to ""))
        } catch (error: CloudError) {
            throw AssertionError("Cloud root returned ${error.code}")
        }
    }

    /** Opt in separately because the wildcard reads every normal listing page in this account. */
    @Test(timeout = 150_000)
    fun wildcardSearchIncludesFilesOutsideTheRoot() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveDriveSearch") == "true")
        val api = CloudApi(context)
        fun read(operation: String, parent: String? = null): Any? = try {
            if (parent == null) api.call(operation) else api.call(operation, "parent" to parent)
        } catch (error: Exception) {
            throw AssertionError("Drive search ($operation): ${(error as? CloudError)?.code ?: error.javaClass.simpleName}")
        }
        check(read("init") == true) { "No saved sign-in for the live drive search" }
        val root = read("files", "").files()
        val rootIds = root.mapTo(HashSet()) { it.id }
        val folders = root.filter(CloudFile::folder).sortedByDescending { it.modifiedAt }
        assumeTrue("No root folders available for a cross-folder comparison", folders.isNotEmpty())
        val all = read("files", "*").files().mapTo(HashSet()) { it.id }
        val deadline = SystemClock.elapsedRealtime() + 70_000
        var sampled = 0
        for (folder in folders) {
            if (SystemClock.elapsedRealtime() >= deadline) break
            sampled++
            val children = read("files", folder.id).files()
            val outside = children.filter { it.id !in rootIds }
            if (outside.isEmpty()) continue
            val matched = outside.count { it.id in all }
            val counts = "root=${rootIds.size}, wildcard=${all.size}, sampledFolders=$sampled, nested=${outside.size}, matched=$matched"
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", counts) })
            assertTrue("Wildcard omitted the sampled folder's children ($counts)", matched > 0)
            return
        }
        assumeTrue("No nonempty folder in the sampled roots (sampled=$sampled, roots=${folders.size})", false)
    }

    @Test(timeout = 180_000)
    fun realSessionCloudViewsAndPlayback() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveCloud") == "true")
        val mode = args.getString("liveMode")
        val display = args.getString("displayId")?.toIntOrNull()
        check(display != null && display != 0) { "Live playback requires a separate display" }
        val screen = Intent(context, SettingsActivity::class.java)
        launchFixtureActivity(SettingsActivity::class.java, screen).use { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            checkLiveCloud(mode)
        }
    }

    private fun checkLiveCloud(mode: String?) {
        val api = CloudApi(context)
        check(api.call("init") == true) { "The app has no saved sign-in" }
        val failures = mutableListOf<String>()

        fun report(message: String) = Log.i("PikPakLive", message)

        fun <T> checkOperation(operation: String, run: () -> T): T? {
            val started = SystemClock.elapsedRealtime()
            report("$operation started")
            return try {
                run().also { report("$operation passed in ${SystemClock.elapsedRealtime() - started} ms") }
            } catch (error: Exception) {
                val code = (error as? CloudError)?.code ?: error::class.simpleName
                failures.add("$operation ($code)")
                report("$operation failed ($code) in ${SystemClock.elapsedRealtime() - started} ms")
                null
            }
        }

        val root = checkOperation("cloud root") { api.call("files", "parent" to "").files() }.orEmpty()
        val candidates = root.filter { it.category == FileCategory.Video }.toMutableList()
        val thumbnails = root.filter { !it.folder && it.thumbnail != null }.toMutableList()
        val parents = ArrayDeque(root.filter(CloudFile::folder).take(10))
        var foldersChecked = 0
        while (parents.isNotEmpty() && foldersChecked < 4 &&
            (foldersChecked == 0 || mode != "views" && (candidates.isEmpty() || thumbnails.isEmpty()))
        ) {
            val parent = parents.removeFirst()
            val contents = checkOperation("cloud folder ${foldersChecked + 1}") {
                api.call("files", "parent" to parent.id).files()
            } ?: break
            foldersChecked++
            candidates += contents.filter { it.category == FileCategory.Video }
            thumbnails += contents.filter { !it.folder && it.thumbnail != null }
            parents.addAll(contents.filter(CloudFile::folder).take(5))
        }

        val starred = if (mode != "media" || candidates.isEmpty()) {
            checkOperation("starred") { api.call("starred").files() }.orEmpty()
        } else emptyList()
        candidates += starred.filter { it.category == FileCategory.Video }
        val tasks = if (mode != "media" || candidates.isEmpty()) {
            checkOperation("tasks") { api.call("tasks").objects() }.orEmpty()
        } else emptyList()
        if (candidates.isEmpty()) candidates += tasks.mapNotNull { task ->
            val id = task.stringOrNull("file_id") ?: return@mapNotNull null
            val name = task.stringOrNull("name") ?: return@mapNotNull null
            CloudFile(id, name, folder = false, bytes = task.optLong("file_size"))
                .takeIf { it.category == FileCategory.Video && task.optString("phase") == "PHASE_TYPE_COMPLETE" }
        }
        if (mode != "media") {
            for (operation in listOf("trash", "shares", "account", "events")) {
                checkOperation(operation) { checkNotNull(api.call(operation)) }
            }
        }
        if (mode == "views") {
            assertTrue(failures.joinToString(), failures.isEmpty())
            return
        }

        val previewLoader = CloudPreviews { id -> api.call("links", "id" to id) as? JSONObject }
        val thumbnailSamples = (thumbnails + starred.filter { !it.folder && it.thumbnail != null })
            .distinctBy(CloudFile::category).take(2)
        if (thumbnailSamples.isEmpty()) report("No cloud thumbnail found within the bounded sample")
        for ((index, file) in thumbnailSamples.withIndex()) {
            checkOperation("cloud thumbnail ${index + 1}") {
                runBlocking {
                    val source = PreviewFile(file, canRefreshLink = true)
                    val thumbnail = checkNotNull(previewLoader.thumbnail(source))
                    check(maxOf(thumbnail.width, thumbnail.height) in 1..192)
                    checkNotNull(previewLoader.preview(source))
                }
            }
        }

        val samples = candidates.sortedBy { it.bytes.takeIf { bytes -> bytes > 0 } ?: Long.MAX_VALUE }
            .distinctBy { it.name.substringAfterLast('.').lowercase() }.take(2)
        if (samples.isEmpty()) report("No video found within the bounded sample")
        for ((index, file) in samples.withIndex()) {
            val label = "cloud video ${index + 1}"
            val links = checkOperation("$label links") { api.call("links", "id" to file.id) as JSONObject } ?: continue
            val choices = PlaybackSource.from(links)
            report("$label ${choices.size} usable source(s)")
            if (choices.isEmpty()) {
                failures.add("$label (no source)")
                continue
            }
            val intent = Intent(context, PlaybackActivity::class.java)
                .putExtra("name", "云端播放检测")
                .putExtra("sources", links.toString())
            launchFixtureActivity(PlaybackActivity::class.java, intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    (findPlayer(activity.window.decorView)?.player as? ExoPlayer)?.apply {
                        addListener(object : Player.Listener {
                            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                                report("$label play=$playWhenReady reason=$reason lifecycle=${activity.lifecycle.currentState}")
                            }
                        })
                        volume = 0f
                        // This muted test runs on a separate display and does not use the system audio route.
                        setAudioAttributes(AudioAttributes.DEFAULT, false)
                        setHandleAudioBecomingNoisy(false)
                        play()
                    }
                }
                val cutoff = SystemClock.elapsedRealtime() + 30_000
                var progress = false
                var lastState = "no player"
                do {
                    scenario.onActivity { activity ->
                        findPlayer(activity.window.decorView)?.player?.let { player ->
                            val formats = player.currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
                            progress = formats && player.currentPosition > 300
                            lastState = player.playerError?.errorCodeName ?: "state ${player.playbackState}, " +
                                "video supported $formats, play ${player.playWhenReady}, " +
                                "suppression ${player.playbackSuppressionReason}"
                        }
                    }
                    if (!progress) SystemClock.sleep(100)
                } while (!progress && SystemClock.elapsedRealtime() < cutoff)
                if (progress) report("$label decoded and advanced")
                else {
                    failures.add("$label ($lastState)")
                    report("$label failed ($lastState)")
                }
            }
        }
        assertTrue(failures.joinToString(), failures.isEmpty())
    }

    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findPlayer(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
