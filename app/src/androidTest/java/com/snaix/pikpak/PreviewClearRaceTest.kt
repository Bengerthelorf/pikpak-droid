package com.snaix.pikpak

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PreviewClearRaceTest {
    @Test
    fun clearingRevokesAnInFlightThumbnailAndAnExistingWarmupWriter() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "preview-clear-${UUID.randomUUID()}"
        val directory = Files.createTempDirectory(target.cacheDir.toPath(), name).toFile()
        val prefs = target.getSharedPreferences(name, Context.MODE_PRIVATE)
        val context = object : ContextWrapper(target) {
            override fun getCacheDir(): File = directory
            override fun getSharedPreferences(name: String?, mode: Int) = prefs
        }
        fun source(id: String) = PreviewFile(
            CloudFile(id, "$id.jpg", folder = false, thumbnail = Uri.parse("https://preview.invalid/$id")),
            canRefreshLink = false,
        )
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val owner = "fixture-owner"
        try {
            prefs.edit().putBoolean(APPEARANCE_PREVIEW_CACHE, true).apply()
            val browsing = PreviewDiskCache(context) { owner }
            val warmup = PreviewDiskCache.forWarmup(context) { owner }
            val settings = PreviewDiskCache(context) { null }
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val ready = source("ready")
            val waiting = source("waiting")
            val loader = CloudPreviews(fetchImage = { uri, _, _ ->
                if (uri == waiting.file.thumbnail) {
                    fetchStarted.countDown()
                    check(releaseFetch.await(10, TimeUnit.SECONDS))
                }
                bitmap
            }, disk = browsing) { error("Unexpected metadata request") }

            runBlocking {
                assertNotNull(loader.thumbnail(ready))
                assertNotNull(loader.cachedThumbnail(ready))
                assertEquals(1, settings.usage().entries)

                val request = async(Dispatchers.IO) { loader.thumbnail(waiting) }
                try {
                    assertTrue(fetchStarted.await(5, TimeUnit.SECONDS))
                    assertEquals(PreviewCacheUsage(0, 0), settings.clear())
                    assertNull(loader.cachedThumbnail(ready))
                } finally {
                    releaseFetch.countDown()
                }
                assertNull(request.await())
                assertNull(loader.cachedThumbnail(waiting))
                warmup.write("old-fill", bitmap, owner)
                assertEquals(PreviewCacheUsage(0, 0), settings.usage())

                assertNotNull(loader.thumbnail(waiting))
                assertNotNull(browsing.read(waiting.file.previewDiskKey("thumbnail"), owner))
                assertEquals(1, settings.usage().entries)
            }
        } finally {
            releaseFetch.countDown()
            target.deleteSharedPreferences(name)
            directory.deleteRecursively()
        }
    }
}
