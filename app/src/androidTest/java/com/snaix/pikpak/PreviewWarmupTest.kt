package com.snaix.pikpak

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileFilter
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PreviewWarmupTest {
    private fun isolated(block: (Context, SharedPreferences) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "warmup-${UUID.randomUUID()}"
        val prefs = target.getSharedPreferences(name, Context.MODE_PRIVATE)
        val dir = Files.createTempDirectory(target.cacheDir.toPath(), name).toFile()
        val context = object : ContextWrapper(target) {
            override fun getCacheDir(): File = dir
            override fun getSharedPreferences(name: String?, mode: Int) = prefs
        }
        try { block(context, prefs) } finally {
            target.deleteSharedPreferences(name)
            dir.deleteRecursively()
        }
    }

    @Test
    fun previewOnlyWarmsTheSameThumbnailAndCoverThatBrowsingReadsAfterRestart() = isolated { context, prefs ->
        prefs.edit().putBoolean(DRIVE_FOLDER_STATS, false).putBoolean(APPEARANCE_PREVIEW_CACHE, true).apply()
        val folder = JSONObject().put("id", "an").put("name", "AN").put("kind", "folder")
        val image = JSONObject().put("id", "poster").put("name", "poster.jpg").put("kind", "file")
            .put("size", 400L).put("modified_time", "2026-11-24T06:00:00Z")
            .put("thumbnail_link", "https://preview.invalid/poster.jpg")
        val listed = mutableListOf<String>()
        val api = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> true
                "files" -> {
                    val parent = args.toMap()["parent"] as String
                    listed += parent
                    if (parent.isEmpty()) JSONArray().put(folder) else JSONArray().put(image)
                        .put(JSONObject().put("id", "notes").put("kind", "file").put("name", "notes.txt"))
                }
                else -> error("Unexpected operation: $operation")
            }
        }
        var owner = "one"
        val fill = PreviewDiskCache.forWarmup(context) { owner }
        var fetched = 0
        val warmImages = CloudPreviews(fetchImage = { _, _, _ ->
            fetched++
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        }, disk = fill) { error("Unexpected metadata load") }
        val phases = mutableListOf<CacheWarmupState>()
        runBlocking {
            PreviewWarmup(context, api, owner, { owner }, fill, warmImages).run { phases += it }
            assertTrue(phases.any { it.total == 2 })
            assertEquals(listOf("", "an"), listed)
            assertEquals(1, fetched)
            assertEquals(listOf("poster.jpg", "notes.txt"), DriveListingCache(context).read(owner, "an")?.map(CloudFile::name))
            val disk = PreviewDiskCache(context) { owner }
            val coldImages = CloudPreviews(fetchImage = { _, _, _ -> error("Unexpected redownload") },
                disk = disk) { error("Unexpected metadata refresh") }
            val source = PreviewFile(CloudFile.from(image), true)
            assertNotNull(coldImages.thumbnail(source))
            val covers = FolderCovers(context, coldImages, disk, { owner }) { error("Unexpected second listing") }
            val an = CloudFile.from(folder)
            assertEquals(192, covers.load(FolderCoverRequest(an, emptyList(), covers.key(an, emptyList())))?.width)
            listed.clear()
            val second = PreviewWarmup(context, api, owner, { owner }, fill, coldImages).run { }
            assertTrue(second, second.contains("可用预览已缓存"))
            assertEquals(listOf("", "an"), listed)
            owner = "two"
            assertNull(disk.read(source.file.previewDiskKey("thumbnail"), owner))
        }
    }

    @Test
    fun bulkThumbnailsRefreshExpiredLinksWithoutDownloadingAnOriginalPhoto() {
        val file = JSONObject().put("id", "photo").put("kind", "file").put("name", "photo.jpg")
            .put("size", "800").put("thumbnail_link", "https://preview.invalid/expired")
        val urls = mutableListOf<String>()
        val loader = CloudPreviews(fetchImage = { uri, _, _ -> urls += uri.toString(); null }, allowOriginalImage = false) {
            JSONObject().put("size", 800).put("download", "https://preview.invalid/original")
                .put("thumbnail", "https://preview.invalid/refreshed")
        }
        runBlocking { assertNull(loader.thumbnail(PreviewFile(CloudFile.from(file), true))) }
        assertEquals(listOf("https://preview.invalid/expired", "https://preview.invalid/refreshed"), urls)
    }

    @Test
    fun concurrentBrowsingAndWarmupShareCapacityWithoutRescanningEachWrite() = isolated { context, prefs ->
        prefs.edit().putBoolean(APPEARANCE_PREVIEW_CACHE, true).apply()
        var listings = 0
        val directory = object : File(context.cacheDir, "drive-previews") {
            override fun listFiles(filter: FileFilter?): Array<File>? {
                listings++
                return super.listFiles(filter)
            }
        }
        assertTrue(directory.mkdirs())
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val pngBytes = ByteArrayOutputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.size()
        }
        val oldest = File(directory, "oldest.png")
        RandomAccessFile(oldest, "rw").use { it.setLength(64L * 1024 * 1024 - 3 * pngBytes) }
        assertTrue(oldest.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)))

        val budget = PreviewCacheBudget.forDirectory(directory)
        val warmup = PreviewDiskCache.forWarmup(context) { "owner" }
        val browse = PreviewDiskCache(context) { "owner" }
        assertEquals(479, warmup.warmupSlots())
        assertTrue(oldest.exists())
        warmup.write("A", bitmap, "owner")
        browse.write("B", bitmap, "owner")
        browse.invalidate("B")
        warmup.write("B", bitmap, "owner")
        warmup.write("C", bitmap, "owner")
        assertEquals(1, listings)
        assertEquals(0, synchronized(budget) { budget.remaining() })

        warmup.write("D", bitmap, "owner")
        assertTrue(warmup.reachedCapacity)
        assertTrue(oldest.exists())
        assertTrue(!browse.contains("D", "owner"))
        browse.write("D", bitmap, "owner")
        assertTrue(!oldest.exists())
        assertTrue(browse.contains("D", "owner"))
        assertEquals(476, warmup.warmupSlots())
    }

    @Test
    fun existingThirtyDayOldImageAndIndexSurviveUpgradeAndOnlyThePreviewIsCleared() = isolated { context, prefs ->
        prefs.edit().putBoolean(APPEARANCE_PREVIEW_CACHE, true).apply()
        val disk = PreviewDiskCache(context) { "owner" }
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        disk.write("persistent", bitmap, "owner")
        val png = File(context.cacheDir, "drive-previews").listFiles()!!.single()
        assertTrue(png.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)))
        val unrelated = File(context.cacheDir, "index-fixture.sqlite3").apply { writeText("preserve") }
        val other = PreviewDiskCache(context) { "owner" }
        assertTrue(other.contains("persistent", "owner"))
        assertEquals(8, other.read("persistent", "owner")?.width)
        val cleared = runBlocking { PreviewWarmupService.clearPreviewCache(context) }
        assertEquals(PreviewCacheUsage(0, 0), cleared)
        assertEquals("preserve", unrelated.readText())
        assertNull(disk.read("persistent", "owner"))
    }

    @Test
    fun shrinkingEachCustomLimitEvictsOldestThenWarmupHonorsTheNewBudget() = isolated { context, prefs ->
        prefs.edit().putBoolean(APPEARANCE_PREVIEW_CACHE, true)
            .putInt(PreviewCacheLimits.SIZE_KEY, 2).putInt(PreviewCacheLimits.COUNT_KEY, 6).apply()
        val dir = File(context.cacheDir, "drive-previews").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val images = (0..5).map { number ->
            File(dir, "$number.png").also {
                RandomAccessFile(it, "rw").use { img -> img.setLength(256L * 1024) }
                assertTrue(it.setLastModified(now - (6 - number) * 10000))
            }
        }
        val disk = PreviewDiskCache(context) { "owner" }
        prefs.edit().putInt(PreviewCacheLimits.SIZE_KEY, 1).apply()
        assertEquals(PreviewCacheUsage(1024L * 1024, 4), disk.applyLimits())
        assertEquals(images.drop(2), images.filter(File::exists))
        prefs.edit().putInt(PreviewCacheLimits.COUNT_KEY, 2).apply()
        assertEquals(PreviewCacheUsage(512L * 1024, 2), disk.applyLimits())
        assertEquals(images.takeLast(2), images.filter(File::exists))
        val fill = PreviewDiskCache.forWarmup(context) { "owner" }
        assertEquals(0, fill.warmupSlots())
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        fill.write("third", image, "owner")
        assertTrue(fill.reachedCapacity)
        assertTrue(!disk.contains("third", "owner"))
        prefs.edit().putInt(PreviewCacheLimits.COUNT_KEY, 3).apply()
        assertEquals(1, PreviewDiskCache.forWarmup(context) { "owner" }.warmupSlots())
        disk.write("third", image, "owner")
        assertEquals(3, disk.usage().entries)
    }

    @Test
    fun independentSwitchesControlBothBrowsingAndWarmupWithoutLosingTheirValues() = isolated { context, prefs ->
        prefs.edit().putBoolean(APPEARANCE_PREVIEW_CACHE, true)
            .putInt(PreviewCacheLimits.SIZE_KEY, 1).putInt(PreviewCacheLimits.COUNT_KEY, 1).apply()
        val disk = PreviewDiskCache(context) { "owner" }
        fun fill() = PreviewDiskCache.forWarmup(context) { "owner" }
        fun toggle(size: Boolean, count: Boolean) {
            prefs.edit().putBoolean(PreviewCacheLimits.SIZE_ENABLED_KEY, size)
                .putBoolean(PreviewCacheLimits.COUNT_ENABLED_KEY, count).apply()
            disk.applyLimits()
            val stored = PreviewCacheLimits.read(prefs)
            assertEquals(1, stored.maxMiB)
            assertEquals(1, stored.maxEntries)
        }
        val noise = java.util.Random(203)
        val pixels = IntArray(640 * 640) { noise.nextInt() or 0xff000000.toInt() }
        val large = Bitmap.createBitmap(pixels, 640, 640, Bitmap.Config.ARGB_8888)
        val small = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val migrated = PreviewCacheLimits.read(prefs)
        assertTrue(migrated.sizeEnabled && migrated.countEnabled)
        disk.write("large", large, "owner")
        assertEquals(0, disk.usage().entries)

        toggle(size = false, count = true)
        fill().write("large", large, "owner")
        assertTrue(disk.usage().bytes > 1024L * 1024)
        // The filesystem clock can tie or go backwards between these two writes.
        val previous = File(context.cacheDir, "drive-previews").listFiles()!!.single()
        assertTrue(previous.setLastModified(System.currentTimeMillis() + 5_000))
        assertEquals(0, fill().warmupSlots())
        disk.write("one", small, "owner")
        assertEquals(1, disk.usage().entries)
        assertTrue(disk.contains("one", "owner"))

        toggle(size = true, count = false)
        fill().write("two", small, "owner")
        disk.write("large", large, "owner")
        assertEquals(2, disk.usage().entries)
        assertTrue(!disk.contains("large", "owner"))

        toggle(size = false, count = false)
        fill().write("large", large, "owner")
        assertEquals(3, disk.usage().entries)
        assertTrue(disk.usage().bytes > 1024L * 1024)
        toggle(size = true, count = true)
        assertTrue(disk.usage().entries <= 1)
        assertTrue(disk.usage().bytes <= 1024L * 1024)
    }

    @Test
    fun customCountAboveTheOldLimitCanFillMultipleBatches() = isolated { context, prefs ->
        prefs.edit().putBoolean(DRIVE_FOLDER_STATS, false).putBoolean(APPEARANCE_PREVIEW_CACHE, true)
            .putInt(PreviewCacheLimits.SIZE_KEY, 1).putInt(PreviewCacheLimits.COUNT_KEY, 520).apply()
        val files = JSONArray()
        repeat(520) { index ->
            files.put(JSONObject().put("id", index.toString()).put("name", "$index.jpg").put("kind", "file")
                .put("thumbnail_link", "https://preview.invalid/$index"))
        }
        val api = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> true
                "files" -> files
                else -> error("Unexpected operation: $operation")
            }
        }
        val disk = PreviewDiskCache.forWarmup(context) { "owner" }
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val loader = CloudPreviews(fetchImage = { _, _, _ -> bitmap }, disk = disk) { null }
        var lastTotal: Int? = null
        val summary = runBlocking {
            PreviewWarmup(context, api, "owner", { "owner" }, disk, loader).run { state -> lastTotal = state.total }
        }
        assertEquals(520, lastTotal)
        assertTrue(summary, summary.contains("已缓存 520 个预览"))
        assertEquals(520, disk.usage().entries)
        assertEquals(0, disk.warmupSlots())
    }

    @Test
    fun disablingCountDuringDiscoveryUsesTheNewBudgetInTheSameRun() = isolated { context, prefs ->
        prefs.edit().putBoolean(DRIVE_FOLDER_STATS, false).putBoolean(APPEARANCE_PREVIEW_CACHE, true)
            .putInt(PreviewCacheLimits.COUNT_KEY, 1).apply()
        val files = JSONArray()
        repeat(3) { index ->
            files.put(JSONObject().put("id", index.toString()).put("name", "$index.jpg").put("kind", "file")
                .put("thumbnail_link", "https://preview.invalid/$index"))
        }
        val api = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> true
                "files" -> {
                    prefs.edit().putBoolean(PreviewCacheLimits.COUNT_ENABLED_KEY, false).apply()
                    files
                }
                else -> error("Unexpected operation: $operation")
            }
        }
        val disk = PreviewDiskCache.forWarmup(context) { "owner" }
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val loader = CloudPreviews(fetchImage = { _, _, _ -> bitmap }, disk = disk) { null }
        runBlocking { PreviewWarmup(context, api, "owner", { "owner" }, disk, loader).run {} }
        assertEquals(3, disk.usage().entries)
    }

    @Test
    fun clearingPreviewsDuringDirectorySyncLeavesTheIndexJobRunningAndSkipsImageRequests() = isolated { context, prefs ->
        prefs.edit().putBoolean(DRIVE_FOLDER_STATS, true).putBoolean(APPEARANCE_PREVIEW_CACHE, true).apply()
        val disk = PreviewDiskCache.forWarmup(context) { "owner" }
        val api = object : Gateway {
            override val supportsFolderIndex = true
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> true
                "folder_index_sync" -> disk.clear().let { null }
                "folder_stats_begin", "folder_stats_cancel" -> null
                else -> error("Unexpected operation after clear: $operation")
            }
        }
        val summary = runBlocking { PreviewWarmup(context, api, "owner", { "owner" }, disk).run { } }
        assertTrue(summary, summary.contains("目录统计已更新"))
        assertTrue(summary, summary.contains("已清理预览缓存"))
    }

    @Test
    fun accountChangeDuringDiscoveryDropsTheOldListingBeforeAnyFileFetch() = isolated { context, prefs ->
        prefs.edit().putBoolean(DRIVE_FOLDER_STATS, false).putBoolean(APPEARANCE_PREVIEW_CACHE, true).apply()
        var owner = "initial"
        val api = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> true
                "files" -> {
                    owner = "next"
                    JSONArray().put(JSONObject().put("id", "file").put("name", "image.jpg").put("kind", "file")
                        .put("thumbnail_link", "https://preview.invalid/file"))
                }
                else -> error("Unexpected operation after account switch: $operation")
            }
        }
        val disk = PreviewDiskCache.forWarmup(context) { owner }
        val images = CloudPreviews(fetchImage = { _, _, _ -> error("Stale preview fetch") }, disk = disk) { null }
        try {
            runBlocking { PreviewWarmup(context, api, owner, { owner }, disk, images).run { } }
            error("A previous account's warmup continued")
        } catch (_: CancellationException) { }
        assertTrue(context.cacheDir.walk().none { it.extension == "png" })
    }

    @Test
    fun rootPreviewFailurePreservesTheStatisticsOutcomeWithoutRetrying() = isolated { context, prefs ->
        prefs.edit().putBoolean(DRIVE_FOLDER_STATS, true).putBoolean(APPEARANCE_PREVIEW_CACHE, true).apply()
        for (statsSuccessful in listOf(false, true)) {
            var syncs = 0
            var listings = 0
            val api = object : Gateway {
                override val supportsFolderIndex = true
                override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                    "init" -> true
                    "folder_stats_begin", "folder_stats_cancel" -> null
                    "folder_index_sync" -> {
                        syncs++
                        if (!statsSuccessful) throw CloudError("timeout", "等待 PikPak 响应超时。")
                        true
                    }
                    "files" -> {
                        listings++
                        assertEquals("", args.toMap()["parent"])
                        throw CloudError("timeout", "等待 PikPak 响应超时。")
                    }
                    else -> error("Unexpected operation: $operation")
                }
            }
            val outcome = runBlocking { PreviewWarmup(context, api, "owner", { "owner" }).run { } }
            assertTrue(outcome, outcome.contains(if (statsSuccessful) "目录统计已更新" else "目录统计未更新：等待 PikPak 响应超时"))
            assertTrue(outcome, outcome.contains("预览缓存未完成：无法读取云盘根目录"))
            assertTrue(outcome, outcome.contains("点「立即缓存」重试"))
            assertEquals(1, syncs)
            assertEquals(1, listings)
        }
    }

    @Test
    fun statisticsOnlySyncUsesAnIndependentCancellableNonFreshToken() = isolated { context, prefs ->
        prefs.edit().putBoolean(DRIVE_FOLDER_STATS, true).putBoolean(APPEARANCE_PREVIEW_CACHE, false).apply()
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var scan = ""
        val api = object : Gateway {
            override val supportsFolderIndex = true
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? {
                val values = args.toMap()
                return when (operation) {
                    "init" -> true
                    "folder_stats_begin" -> {
                        assertEquals(false, values["fresh"])
                        scan = values["scan"] as String
                        null
                    }
                    "folder_index_sync" -> {
                        assertEquals(scan, values["scan"])
                        assertEquals(false, values["force_full"])
                        entered.countDown()
                        assertTrue(stopped.await(5, TimeUnit.SECONDS))
                        null
                    }
                    "folder_stats_cancel" -> {
                        assertEquals(scan, values["scan"])
                        stopped.countDown()
                        null
                    }
                    else -> error("Unexpected operation: $operation")
                }
            }
        }
        val work = PreviewWarmup(context, api, "one", { "one" })
        runBlocking {
            val job = async(Dispatchers.IO) { work.run { } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            work.cancel()
            try {
                job.await()
                error("Cancelled sync reported success")
            } catch (_: CancellationException) { }
        }
    }
}
