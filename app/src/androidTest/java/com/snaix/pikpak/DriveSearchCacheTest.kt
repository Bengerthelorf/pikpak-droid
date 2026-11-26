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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DriveSearchCacheTest {
    private val app = WorkspaceFixtureApplication(InstrumentationRegistry.getInstrumentation().targetContext)

    @After fun cleanUpFixture() = app.close()

    @Test fun completeSnapshotIsOwnerScopedAndReturnsAllPagesAfterReopening() {
        val path = File(app.cacheDir, "search-test-${UUID.randomUUID()}.jsonl")
        val snapshot = DriveSearchCache(path)
        val count = DRIVE_SEARCH_VISIBLE_LIMIT * 2 + 7
        val files = JSONArray().apply { for (index in count downTo 1) put(file(index.toString().padStart(4, '0'))) }
        val revision = DriveSearchCache.cleared.value
        try {
            assertTrue(snapshot.write("owner-a", files, 42, revision) {})
            val reopened = DriveSearchCache(path)
            assertNull(reopened.read("owner-b", "match", DrivePresentation(), null) {})
            val seen = mutableListOf<String>()
            var after: CloudFile? = null
            do {
                val page = checkNotNull(reopened.read("owner-a", "match", DrivePresentation(), after) {})
                assertEquals(42L, page.time)
                assertEquals(count, page.matches.count)
                assertTrue(page.matches.files.size <= DRIVE_SEARCH_VISIBLE_LIMIT)
                seen += page.matches.files.map(CloudFile::id)
                after = page.matches.files.lastOrNull()
            } while (page.matches.hasNext)
            assertEquals((1..count).map { it.toString().padStart(4, '0') }, seen)

            reopened.clear("owner-a")
            assertFalse(snapshot.write("owner-a", files, 43, revision) {})
            assertNull(reopened.read("owner-a", "match", DrivePresentation(), null) {})
        } finally {
            snapshot.clear()
        }
    }

    @Test fun localResultsStayUsableWhileSearchWarmsAndAfterRefreshFails() = runBlocking {
        val requests = AtomicInteger()
        val ready = CountDownLatch(1)
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> true
                "files" -> if (args.toMap()["parent"] == "*") {
                    if (requests.incrementAndGet() > 1) throw CloudError("timeout", "Fixture timeout")
                    check(ready.await(8, TimeUnit.SECONDS))
                    JSONArray().put(file("cloud")).put(file("local"))
                } else JSONArray().put(file("local"))
                else -> error("Unexpected fixture request: $operation")
            }
        }
        withContext(Dispatchers.Main) {
            val model = CloudModel(app, api = gateway)
            try {
                withTimeout(10_000) { model.observe { model.signedIn && !model.loading }.first { it } }
                model.searchDrive("match")
                val initial = withTimeout(10_000) { model.observe { model.searchStatus }.first { it?.updating == true } }
                assertFalse(checkNotNull(initial).complete)
                assertEquals(listOf("local"), model.entries.map(CloudFile::id))
                model.back()
                model.searchDrive("match")
                ready.countDown()
                withTimeout(10_000) { model.observe { model.searchStatus?.complete == true && !model.loading }.first { it } }
                assertEquals(listOf("cloud", "local"), model.entries.map(CloudFile::id))
                assertEquals(1, requests.get())
                model.back()
                model.searchDrive("match")
                withTimeout(10_000) { model.observe { model.searchStatus?.complete == true && !model.loading }.first { it } }
                assertEquals(1, requests.get())
                model.refreshFromUi()
                val failure = withTimeout(10_000) { model.observe { model.searchStatus }.first { it?.failure != null } }
                assertTrue(checkNotNull(failure).complete)
                assertTrue(failure.sessionOnly)
                assertEquals("Fixture timeout", failure.failure)
                assertEquals(listOf("cloud", "local"), model.entries.map(CloudFile::id))
                assertEquals(2, requests.get())
            } finally {
                ready.countDown()
                model.viewModelScope.cancel()
            }
        }
    }

    @Test fun completedSearchWithoutDiskCacheFindsThe4001stItem() = runBlocking {
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> true
                "files" -> if (args.toMap()["parent"] == "*") JSONArray().apply {
                    for (index in 0 until 4_000) put(file(index.toString()))
                    put(file("last").put("name", "unique needle"))
                } else JSONArray()
                else -> error("Unexpected fixture request: $operation")
            }
        }
        withContext(Dispatchers.Main) {
            val model = CloudModel(app, api = gateway)
            try {
                withTimeout(10_000) { model.observe { model.signedIn && !model.loading }.first { it } }
                model.searchDrive("needle")
                val finished = withTimeout(10_000) {
                    model.observe { model.searchStatus }.first { it?.complete == true && !it.updating }
                }
                assertTrue(checkNotNull(finished).sessionOnly)
                assertEquals(1, finished.count)
                assertEquals(listOf("last"), model.entries.map(CloudFile::id))
            } finally {
                model.viewModelScope.cancel()
            }
        }
    }

    private fun file(id: String) = JSONObject().put("id", id).put("name", "match-$id").put("kind", "drive#file").put("size", "42")
}
