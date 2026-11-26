package com.snaix.pikpak

import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CachedNameSearchTest {
    private val app = WorkspaceFixtureApplication(InstrumentationRegistry.getInstrumentation().targetContext)
    private val history = TransferHistory(app).apply { authenticate("search-owner@example.invalid") {} }
    private val owner = checkNotNull(history.verifiedAccountScope())
    private val listings = DriveListingCache(app)

    @After fun closeFixture() = app.close()

    @Test fun coldSearchShowsDeepCachedChineseNamesBeforeTheWildcardFinishes() = runBlocking {
        val branches = (1..32).map { CloudFile("branch-$it", "目录 $it", folder = true) }
        val album = CloudFile("album", "三上相册", folder = true)
        val movie = CloudFile("movie", "精选·三上.mp4", folder = false)
        val hidden = CloudFile("foreign", movie.name, folder = false)
        val cloudOnly = CloudFile("new", "三上新片.mp4", folder = false)
        val remote = CountDownLatch(1)
        val wildcardReads = AtomicInteger()
        listings.write(owner, branches.last().id, listOf(album), listings.beginRead())
        listings.write(owner, album.id, listOf(movie), listings.beginRead())
        listings.write("a-different-account", album.id, listOf(hidden), listings.beginRead())
        app.appearancePreferences().edit().putBoolean(DRIVE_FOLDER_STATS, false).commit()

        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? {
                if (operation == "init") return true
                check(operation == "files")
                return when (args.toMap()["parent"]) {
                    "" -> arrayOfFiles(branches)
                    album.id -> arrayOfFiles(listOf(movie))
                    "*" -> {
                        wildcardReads.incrementAndGet()
                        check(remote.await(30, TimeUnit.SECONDS))
                        arrayOfFiles(listOf(album, cloudOnly))
                    }
                    else -> error("Local search requested an unrelated remote directory")
                }
            }
        }
        withContext(Dispatchers.Main) {
            val model = CloudModel(app, api = gateway, transferHistory = history)
            try {
                awaitState(model) { model.signedIn && !model.loading }
                model.searchDrive("三上")
                val localIds = setOf(album.id, movie.id)
                awaitState(model) { model.entries.map(CloudFile::id).toSet() == localIds }
                assertFalse(checkNotNull(model.searchStatus).complete)
                assertTrue(model.loading)
                model.openFolder(model.entries.single(CloudFile::folder))
                awaitState(model) { !model.loading && model.folder.id == album.id }
                assertEquals(listOf(movie.id), model.entries.map(CloudFile::id))
                assertTrue(model.back())
                awaitState(model) { model.location.search != null && model.entries.map(CloudFile::id).toSet() == localIds }
                remote.countDown()
                awaitState(model) { model.searchStatus?.complete == true && !model.loading }
                assertEquals(setOf(album.id, cloudOnly.id), model.entries.map(CloudFile::id).toSet())
                assertEquals(1, wildcardReads.get())
            } finally {
                remote.countDown()
                model.viewModelScope.cancel()
            }
        }
    }

    @Test fun freshLegacyWholeDriveSnapshotAnswersAColdChineseQueryWithoutNetwork() = runBlocking {
        val child = CloudFile("nested", "子目录", folder = true)
        val stale = CloudFile("stale", "三上（旧名称）", folder = false)
        val indexed = CloudFile("indexed", "收藏·三上.mp4", folder = false)
        listings.write(owner, child.id, listOf(stale), listings.beginRead())
        // Write the legacy on-disk format independently of the current writer.
        File(app.cacheDir, "drive-search-v1.jsonl").writeText(
            JSONArray().put(owner).put(System.currentTimeMillis()).put(1).toString() + "\n" +
                """{"id":"indexed","name":"收藏·三上.mp4","kind":"file","size":"123","bytes":123}""" + "\n",
        )
        app.appearancePreferences().edit().putBoolean(DRIVE_FOLDER_STATS, false).commit()
        val wildcardReads = AtomicInteger()
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? {
                if (operation == "init") return true
                check(operation == "files")
                if (args.toMap()["parent"] == "") return arrayOfFiles(listOf(child))
                wildcardReads.incrementAndGet()
                error("A fresh whole-drive index should answer this query locally")
            }
        }
        withContext(Dispatchers.Main) {
            val model = CloudModel(app, api = gateway, transferHistory = history)
            try {
                awaitState(model) { model.signedIn && !model.loading }
                model.searchDrive("三上")
                awaitState(model) { model.entries.isNotEmpty() }
                assertEquals(listOf(indexed.id), model.entries.map(CloudFile::id))
                awaitState(model) { model.searchStatus?.complete == true && !model.loading }
                assertEquals(indexed.name, model.entries.single().name)
                model.editQuery("三上.mp4")
                awaitState(model) { model.searchStatus?.complete == true && !model.loading }
                assertEquals(listOf(indexed.id), model.entries.map(CloudFile::id))
                assertEquals(0, wildcardReads.get())
            } finally {
                model.viewModelScope.cancel()
            }
        }
    }

    private class NativeGateway(
        val readPage: (Map<String, Any?>) -> JSONObject?,
        val sync: () -> Unit = {},
        val list: (String) -> JSONArray = { JSONArray() },
    ) : Gateway {
        val syncs = AtomicInteger()
        val wildcards = AtomicInteger()
        val clears = AtomicInteger()
        override val supportsDriveSearch = true
        override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
            "init" -> true
            "drive_search_cached" -> readPage(args.toMap())
            "drive_search_clear" -> { clears.incrementAndGet(); null }
            "folder_stats_begin", "folder_stats_cancel" -> null
            "folder_index_sync" -> {
                assertEquals(true, args.toMap()["report_unavailable"])
                syncs.incrementAndGet()
                sync()
                null
            }
            "files" -> {
                val parent = args.toMap().getValue("parent") as String
                if (parent == "*") wildcards.incrementAndGet()
                list(parent)
            }
            else -> error("Unexpected operation $operation")
        }
    }

    private fun nativePage(files: List<CloudFile>, count: Int = files.size, next: Boolean = false,
        seconds: Long = System.currentTimeMillis() / 1_000): JSONObject = JSONObject()
        .put("files", arrayOfFiles(files)).put("count", count).put("has_next", next).put("checked_at", seconds)

    @Test fun freshNativeSearchPaginatesWithoutDirectoryOrRefreshRequests() = runBlocking {
        val files = (0..301).map { CloudFile("id$it", "三上%03d".format(it), false) }
        val reads = AtomicInteger()
        val snapshot = System.currentTimeMillis() / 1_000
        val gateway = NativeGateway(readPage = { args ->
            reads.incrementAndGet()
            assertEquals("三上", args["query"])
            assertEquals("Name", args["sort"])
            assertEquals(false, args["descending"])
            assertEquals(DRIVE_SEARCH_VISIBLE_LIMIT, args["limit"])
            val cursor = args["after"] as? JSONObject
            if (cursor == null) nativePage(files.take(300), files.size, next = true, seconds = snapshot)
            else {
                assertEquals("id299", cursor.getString("id"))
                assertEquals("File", cursor.getString("kind"))
                nativePage(files.drop(300), files.size, seconds = snapshot)
            }
        })
        app.appearancePreferences().edit().putBoolean(DRIVE_FOLDER_STATS, false).commit()
        withContext(Dispatchers.Main) {
            val model = CloudModel(app, api = gateway, transferHistory = history)
            try {
                awaitState(model) { model.signedIn && !model.loading }
                model.searchDrive("三上")
                awaitState(model) { model.searchStatus?.complete == true && !model.loading }
                assertEquals(302, model.searchStatus?.count)
                assertTrue(checkNotNull(model.searchStatus).hasNextPage)
                assertEquals(300, model.entries.size)
                model.changeSearchPage(forward = true)
                awaitState(model) { model.searchStatus?.page == 1 && !model.loading }
                assertEquals(listOf("id300", "id301"), model.entries.map(CloudFile::id))
                model.changeSearchPage(forward = false)
                awaitState(model) { model.searchStatus?.page == 0 && !model.loading }
                assertEquals(3, reads.get())
                assertEquals(0, gateway.syncs.get())
                assertEquals(0, gateway.wildcards.get())
            } finally { model.viewModelScope.cancel() }
        }
    }

    @Test fun changedIndexRestartsPaginationAndKeepsNativeFolderStats() = runBlocking {
        val folder = CloudFile("folder", "Folder", true)
        val latest = CloudFile("latest", "Folder two", false)
        var snapshot = System.currentTimeMillis() / 1_000
        val gateway = NativeGateway(readPage = { args ->
            assertEquals(true, args["folder_stats"])
            val page = nativePage(if (args["after"] == null) listOf(folder) else listOf(latest), 2,
                next = args["after"] == null, seconds = snapshot)
            if (args["after"] == null) page.getJSONArray("files").getJSONObject(0).put("folder_stats",
                JSONObject().put("bytes", "9001").put("modified_at", "2026-04-05T01:02:03Z").put("is_empty", false))
            else {
                val cursor = args["after"] as JSONObject
                assertEquals("9001", cursor.getJSONObject("folder_stats").getString("bytes"))
            }
            page
        })
        app.appearancePreferences().edit().putBoolean(DRIVE_FOLDER_STATS, true).commit()
        withContext(Dispatchers.Main) {
            val model = CloudModel(app, api = gateway, transferHistory = history)
            try {
                awaitState(model) { model.signedIn && !model.loading }
                model.searchDrive("Folder")
                awaitState(model) { model.searchStatus?.complete == true && !model.loading }
                assertEquals(9001uL, model.entries.single().displayBytes)
                assertTrue(model.hasListingFolderStats)
                assertFalse(model.canScanFolders)
                snapshot -= 2
                model.changeSearchPage(forward = true)
                awaitState(model) { model.searchStatus?.page == 0 && !model.loading }
                assertEquals("folder", model.entries.single().id)
                assertEquals(0, gateway.syncs.get())
                assertEquals(0, gateway.wildcards.get())
            } finally { model.viewModelScope.cancel() }
        }
    }

    @Test fun coldNativeIndexUsesAvailableFoldersAndReplacesThemAfterSharedSync() = runBlocking {
        val local = CloudFile("old", "三上旧名字", false)
        val updated = CloudFile("new", "三上新名字", false)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateway = NativeGateway(
            readPage = { if (done.get()) nativePage(listOf(updated)) else null },
            sync = { started.countDown(); check(release.await(30, TimeUnit.SECONDS)); done.set(true) },
            list = { parent -> check(parent.isEmpty()); arrayOfFiles(listOf(local)) },
        )
        app.appearancePreferences().edit().putBoolean(DRIVE_FOLDER_STATS, false).commit()
        withContext(Dispatchers.Main) {
            val model = CloudModel(app, api = gateway, transferHistory = history)
            try {
                awaitState(model) { model.signedIn && !model.loading }
                model.searchDrive("三上")
                awaitState(model) { model.entries.singleOrNull()?.id == "old" && model.searchStatus?.complete == false }
                assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
                model.editQuery("三上新")
                release.countDown()
                awaitState(model) { model.searchStatus?.complete == true && !model.loading }
                assertEquals(listOf("new"), model.entries.map(CloudFile::id))
                assertEquals(1, gateway.syncs.get())
                assertEquals(0, gateway.wildcards.get())
            } finally { release.countDown(); model.viewModelScope.cancel() }
        }
    }

    @Test fun staleNativePageSurvivesFailedSyncAndUsesExplicitClear() = runBlocking {
        val file = CloudFile("file", "收藏电影", false)
        val gateway = NativeGateway(
            readPage = { nativePage(listOf(file), seconds = 1) },
            sync = { throw CloudError("connect", "offline") },
        )
        app.appearancePreferences().edit().putBoolean(DRIVE_FOLDER_STATS, false).commit()
        withContext(Dispatchers.Main) {
            val model = CloudModel(app, api = gateway, transferHistory = history)
            try {
                awaitState(model) { model.signedIn && !model.loading }
                model.searchDrive("电影")
                awaitState(model) { model.searchStatus?.failure != null && !model.loading }
                assertEquals(listOf("file"), model.entries.map(CloudFile::id))
                assertTrue(checkNotNull(model.searchStatus).complete)
                assertEquals(0, gateway.wildcards.get())
                val legacy = File(app.cacheDir, "drive-search-v1.jsonl")
                legacy.writeText("old snapshot")
                withContext(Dispatchers.IO) { DriveSearchCache(app).clearAccountIndex(gateway, hasOwner = true) }
                awaitState(model) { model.searchStatus?.complete == false && model.entries.isEmpty() }
                assertEquals(1, gateway.clears.get())
                assertFalse(legacy.exists())
            } finally { model.viewModelScope.cancel() }
        }
    }

    @Test fun unsupportedAccountUsesWildcardCompatibilityPath() = runBlocking {
        val legacy = CloudFile("legacy", "旧课件", false)
        val gateway = NativeGateway(
            readPage = { null },
            sync = { throw CloudError("index_unavailable", "no index watermark") },
            list = { parent -> if (parent == "*") arrayOfFiles(listOf(legacy)) else JSONArray() },
        )
        app.appearancePreferences().edit().putBoolean(DRIVE_FOLDER_STATS, false).commit()
        withContext(Dispatchers.Main) {
            val model = CloudModel(app, api = gateway, transferHistory = history)
            try {
                awaitState(model) { model.signedIn && !model.loading }
                model.searchDrive("课件")
                awaitState(model) { model.searchStatus?.complete == true && !model.loading }
                assertEquals(listOf("legacy"), model.entries.map(CloudFile::id))
                assertEquals(1, gateway.syncs.get())
                assertEquals(1, gateway.wildcards.get())
            } finally { model.viewModelScope.cancel() }
        }
    }

    private suspend fun awaitState(model: CloudModel, condition: () -> Boolean) {
        withTimeout(10_000) { model.observe(condition).first { it } }
    }

    private fun arrayOfFiles(files: List<CloudFile>): JSONArray = JSONArray().apply {
        files.forEach { put(it.listingJson()) }
    }
}
