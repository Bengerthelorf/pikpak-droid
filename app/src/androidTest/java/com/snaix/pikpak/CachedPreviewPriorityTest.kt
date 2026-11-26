package com.snaix.pikpak

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CachedPreviewPriorityTest {
    @Test fun coldDiskImagesAppearWhileEveryNetworkSlotIsBusy() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "preview-order-${UUID.randomUUID()}"
        val directory = Files.createTempDirectory(target.cacheDir.toPath(), name).toFile()
        val prefs = target.getSharedPreferences(name, Context.MODE_PRIVATE)
        val context = object : ContextWrapper(target) {
            override fun getCacheDir(): File = directory
            override fun getSharedPreferences(name: String?, mode: Int) = prefs
        }
        val release = CountDownLatch(1)
        val thumbnailRequests = CountDownLatch(3)
        val coverRequests = CountDownLatch(2)
        try {
            prefs.edit().putBoolean(APPEARANCE_PREVIEW_CACHE, true).apply()
            val disk = PreviewDiskCache(context) { "owner" }
            val bitmap = Bitmap.createBitmap(20, 18, Bitmap.Config.ARGB_8888)
            val images = CloudPreviews(fetchImage = { uri, _, _ ->
                check(uri.lastPathSegment != "saved")
                thumbnailRequests.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                bitmap
            }, disk = disk) { null }
            val covers = FolderCovers(context, images, disk, { "owner" }) { id ->
                check(id != "saved")
                coverRequests.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                emptyList()
            }
            fun photo(id: String) = PreviewFile(
                CloudFile(id, "$id.jpg", false, thumbnail = Uri.parse("https://preview.invalid/$id")), false,
            )
            fun folder(id: String): FolderCoverRequest {
                val file = CloudFile(id, id, true)
                return FolderCoverRequest(file, emptyList(), covers.key(file, emptyList()))
            }
            val savedPhoto = photo("saved")
            val savedCover = folder("saved")
            disk.write(savedPhoto.file.previewDiskKey("thumbnail"), bitmap, "owner")
            disk.write(savedCover.key, bitmap, "owner")
            runBlocking {
                repeat(3) { index -> async(Dispatchers.IO) { images.thumbnail(photo("slow-$index")) } }
                repeat(2) { index -> async(Dispatchers.IO) { covers.load(folder("slow-$index")) } }
                try {
                    assertTrue(thumbnailRequests.await(5, TimeUnit.SECONDS))
                    assertTrue(coverRequests.await(5, TimeUnit.SECONDS))
                    withTimeout(3_000) {
                        assertEquals(20, images.thumbnail(savedPhoto)?.width)
                        assertEquals(18, covers.load(savedCover)?.height)
                    }
                } finally {
                    release.countDown()
                }
            }
        } finally {
            release.countDown()
            target.deleteSharedPreferences(name)
            directory.deleteRecursively()
        }
    }
}
