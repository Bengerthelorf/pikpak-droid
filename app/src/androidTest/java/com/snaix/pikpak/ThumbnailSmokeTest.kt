package com.snaix.pikpak

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SeslProgressBar
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ThumbnailSmokeTest {
    @Test
    fun loadsInlineThumbnailsAndOpensQuickPreviewWithoutFetchingVideo() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clip = landscape(Color.rgb(80, 161, 185), Color.rgb(27, 84, 101))
        val photo = landscape(Color.rgb(220, 171, 125), Color.rgb(89, 72, 105))
        val retriedPhoto = landscape(Color.rgb(134, 173, 158), Color.rgb(61, 100, 74))
        val releasePhoto = CountDownLatch(1)
        val retryAttempts = AtomicInteger()
        val clock = AtomicLong()
        val visited = CopyOnWriteArrayList<String>()
        val originalVideo = "https://preview.invalid/original.mkv"
        val previews = CloudPreviews(fetchImage = { source, _, _ ->
            visited.add(source.toString())
            when (source.lastPathSegment) {
                "clip-fresh" -> clip
                "photo" -> if (releasePhoto.await(15, TimeUnit.SECONDS)) photo else null
                "retry" -> if (retryAttempts.incrementAndGet() == 1) null else retriedPhoto
                else -> null
            }
        }, elapsedMillis = clock::get) { fileId ->
            check(fileId == "clip")
            JSONObject().put("thumbnail", "https://preview.invalid/clip-fresh")
                .put("download", originalVideo).put("size", 10_000)
        }
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> true
                "files" -> if (args.toMap()["parent"] == "folder") {
                    JSONArray().put(entry("text", "说明.txt"))
                } else JSONArray()
                    .put(entry("folder", "归档", folder = true))
                    .put(entry("clip", "海岸线.mp4", "https://preview.invalid/clip-expired"))
                    .put(entry("photo", "黄昏山脊.jpg", "https://preview.invalid/photo"))
                    .put(entry("retry", "晨雾.jpg", "https://preview.invalid/retry"))
                else -> error("Unexpected fixture operation: $operation")
            }
        }
        launchFixtureActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).use { scenario ->
            lateinit var model: CloudModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity, viewModelFactory {
                    initializer { CloudModel(activity.application, gateway, previews) }
                })["thumbnail-fixture", CloudModel::class.java]
                activity.showFixture(model)
            }
            assumeTrue(scenario.onActivity { model.showThumbnails })
            try {
                expect(scenario) { activity ->
                    val root = activity.window.decorView
                    val photoRow = find(root) { it is FileThumbnailView && it.contentDescription == "预览 黄昏山脊.jpg" }
                    photoRow != null && find(photoRow) { it is SeslProgressBar && it.isShown && it.isIndeterminate } != null &&
                        inlineThumbnail(photoRow) == null
                }
                scenario.onActivity { activity ->
                    capture(activity.window.decorView, File(activity.cacheDir, "oneui-thumbnail-loading.png"))
                }
            } finally {
                releasePhoto.countDown()
            }
            expect(scenario) { activity ->
                val root = activity.window.decorView
                inlineThumbnail(root, clip) != null && inlineThumbnail(root, photo) != null &&
                    find(root) { it is SeslProgressBar && it.contentDescription == "正在加载缩略图" && it.isShown } == null
            }
            assertTrue(retryAttempts.get() == 1)
            clock.set(31_000)
            scenario.onActivity { model.refresh() }
            expect(scenario) { inlineThumbnail(it.window.decorView, retriedPhoto) != null }
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                capture(root, File(activity.cacheDir, "oneui-thumbnails.png"))
                check(checkNotNull(find(root) { it.contentDescription == "预览 海岸线.mp4" }).performClick())
            }
            expect(scenario) { dialogWithBitmap(clip) != null }
            scenario.onActivity { activity ->
                val preview = checkNotNull(dialogWithBitmap(clip))
                capture(preview, File(activity.cacheDir, "oneui-file-preview.png"))
                check(checkNotNull(find(preview) { it is TextView && it.text.toString() == "关闭" }).performClick())
                model.openFolder(CloudFile("folder", "归档", folder = true))
            }
            expect(scenario) { activity ->
                val root = activity.window.decorView
                find(root) { it is TextView && it.text.toString() == "说明.txt" } != null &&
                    inlineThumbnail(root) == null &&
                    find(root) { it is SeslProgressBar && it.contentDescription == "正在加载缩略图" && it.isShown } == null
            }
        }
        assertTrue(visited.contains("https://preview.invalid/clip-expired"))
        assertTrue(visited.contains("https://preview.invalid/clip-fresh"))
        assertTrue(originalVideo !in visited)
    }

    private fun inlineThumbnail(root: View, expected: Bitmap? = null): View? = find(root) { view ->
        val bitmap = (view as? ImageView)?.takeIf { it.parent is FileThumbnailView && it.isShown }
            ?.drawable as? BitmapDrawable
        bitmap != null && (expected == null || bitmap.bitmap === expected)
    }

    @androidx.annotation.RequiresApi(29)
    private fun dialogWithBitmap(bitmap: Bitmap): View? = WindowInspector.getGlobalWindowViews().firstOrNull { root ->
        find(root) { it is ImageView && it.parent !is FileThumbnailView && (it.drawable as? BitmapDrawable)?.bitmap === bitmap } != null
    }

    private fun entry(id: String, name: String, thumbnail: String? = null, folder: Boolean = false) = JSONObject()
        .put("id", id).put("name", name).put("kind", if (folder) "drive#folder" else "drive#file")
        .put("size", 10_000).put("thumbnail_link", thumbnail)

    private fun find(view: View, predicate: (View) -> Boolean): View? {
        if (view.isShown && predicate(view)) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            find(view.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }

    private fun expect(scenario: FixtureActivity<MainActivity>, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (scenario.onActivity(condition)) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected file preview fixture state was not reached")
    }

    private fun landscape(sky: Int, mountain: Int): Bitmap =
        Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(sky)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 231, 177) }
            canvas.drawCircle(364f, 76f, 31f, paint)
            paint.color = mountain
            val ridgeline = Path().apply {
                moveTo(0f, 220f)
                lineTo(129f, 98f)
                lineTo(205f, 184f)
                lineTo(301f, 123f)
                lineTo(480f, 219f)
                lineTo(480f, 270f)
                lineTo(0f, 270f)
                close()
            }
            canvas.drawPath(ridgeline, paint)
        }

    private fun capture(view: View, destination: File) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
