package com.snaix.pikpak

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.allViews
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ShareFilePreviewTest {
    @Test fun sharedFileUsesShareMetadataAndListingThumbnail() = openPreview(supported = true)
    @Test fun legacyCoreShowsListingDetailsAndCanSave() = openPreview(supported = false)
    @Test fun previewFailureKeepsListingDetailsAndCanSave() = openPreview(supported = true, fail = true)

    private fun openPreview(supported: Boolean, fail: Boolean = false) {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileName = "Shared clip.mp4"
        val shareHandle = "share-handle/folder"
        val thumbnail = "https://fixture.invalid/shared-preview"
        val file = JSONObject().put("id", "clip").put("name", fileName).put("size", 31)
            .put("kind", "drive#file").apply { if (supported) put("thumbnail_link", thumbnail) }
        val delegate = FixtureGateway()
        val operations = CopyOnWriteArrayList<String>()
        val images = CopyOnWriteArrayList<String>()
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        val gateway = object : Gateway {
            override val supportsShareBrowsing = supported
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? {
                operations.add(operation)
                return when (operation) {
                    "share_open" -> JSONObject().put("share", shareHandle).put("token", "fixture-token")
                        .put("files", JSONArray().put(file))
                    "share_preview" -> {
                        check(args.toMap() == mapOf("share" to shareHandle, "token" to "fixture-token", "id" to "clip"))
                        if (fail) throw CloudError("share", "Share unavailable")
                        JSONObject().put("id", "clip").put("name", fileName).put("mime_type", "video/mp4")
                            .put("size", 75).put("duration_seconds", 65.5)
                            .put("streams", JSONArray().put(JSONObject().put("label", "Original · 1080p").put("available", true))
                                .put(JSONObject().put("label", "720p").put("available", false)
                                    .put("reason", "additional playback quota required")))
                    }
                    else -> delegate.call(operation, *args)
                }
            }
        }
        val previews = CloudPreviews(fetchImage = { uri, _, _ ->
            images.add(uri.toString())
            if (uri.toString() == thumbnail) bitmap else null
        }) { error("Requested a private drive link for a shared file: $it") }
        WorkspaceFixtureApplication(context).use { app ->
            launchFixtureActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).use { screen ->
                val model = screen.onActivity { activity ->
                    ViewModelProvider(activity, viewModelFactory {
                        initializer { CloudModel(app, gateway, previews) }
                    })["shared-file-fixture", CloudModel::class.java].also { activity.showFixture(it) }
                }
                await(screen) { model.signedIn && !model.starting && !model.loading }
                screen.onActivity { model.openShare("fixture", "") }
                await(screen) { it.window.decorView.text(fileName) != null }
                screen.onActivity { activity ->
                    val title = checkNotNull(activity.window.decorView.text(fileName))
                    check(generateSequence<View>(title) { it.parent as? View }.first { it.isClickable }.performClick())
                }
                await(screen) { activity ->
                    val root = dialog(activity) ?: return@await false
                    val details = root.allViews.filterIsInstance<TextView>().map { it.text.toString() }.joinToString("\n")
                    val hasDetails = when {
                        fail -> details.contains(activity.getString(R.string.share_preview_details_unavailable)) && "31 B" in details
                        supported -> "video/mp4" in details && "75 B" in details && DateUtils.formatElapsedTime(65) in details &&
                            "1080p" in details && activity.getString(R.string.share_preview_quality_quota, "720p") in details
                        else -> "31 B" in details && "1080p" !in details
                    }
                    val showsThumbnail = root.allViews.filterIsInstance<ImageView>().any {
                        it.isShown && (it.drawable as? BitmapDrawable)?.bitmap === bitmap
                    }
                    hasDetails && showsThumbnail == supported && root.text(activity.getString(R.string.ui_play)) == null
                }
                screen.onActivity { activity ->
                    check(checkNotNull(dialog(activity)?.text(activity.getString(R.string.ui_restore_to_drive))).performClick())
                }
                val saveHere = screen.onActivity { it.getString(R.string.ui_use_current_folder, it.getString(R.string.ui_save_shared)) }
                await(screen) { dialog(it)?.text(saveHere)?.isEnabled == true }
                screen.onActivity { check(checkNotNull(dialog(it)?.text(saveHere)).performClick()) }
                await(screen) { delegate.restore != null }
                assertEquals(shareHandle, delegate.restore?.get("share"))
                assertEquals("fixture-token", delegate.restore?.get("token"))
                assertEquals(listOf("clip"), delegate.restore?.get("ids"))
            }
        }
        assertEquals(if (supported) 1 else 0, operations.count { it == "share_preview" })
        assertTrue(operations.none { it in setOf("info", "links", "text") })
        assertEquals(if (supported) setOf(thumbnail) else emptySet(), images.toSet())
    }

    @android.annotation.TargetApi(29)
    private fun dialog(activity: MainActivity) = WindowInspector.getGlobalWindowViews().lastOrNull {
        it.isShown && it !== activity.window.decorView && it.display?.displayId == activity.window.decorView.display?.displayId
    }

    private fun View.text(value: String): TextView? =
        allViews.filterIsInstance<TextView>().firstOrNull { it.isShown && it.text.toString() == value }

    private fun await(screen: FixtureActivity<MainActivity>, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!screen.onActivity(condition)) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for shared file UI" }
            SystemClock.sleep(40)
        }
    }
}
