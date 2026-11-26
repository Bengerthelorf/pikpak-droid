package com.snaix.pikpak

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class LiveFolderStatsTest {
    @Test(timeout = 125_000)
    fun savedSessionMatchesIndependentFolderListing() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveFolderStats") == "true")
        val api = CloudApi(InstrumentationRegistry.getInstrumentation().targetContext)
        val token = UUID.randomUUID().toString()
        val worker = Executors.newSingleThreadExecutor { action ->
            Thread(action, "Live folder statistics check").apply { isDaemon = true }
        }
        try {
            val check = worker.submit {
                check(api.call("init") == true) { "No saved sign-in on the device" }
                api.call("folder_stats_begin", "scan" to token)
                inspect(api, token)
            }
            try {
                check.get(110, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                throw AssertionError("Folder statistics check exceeded 110 seconds")
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            }
        } finally {
            worker.shutdownNow()
            api.call("folder_stats_cancel", "scan" to token)
        }
    }

    private data class Candidate(
        val id: String, val depth: Int, val metadata: JSONObject?,
        val singleChildParent: Candidate? = null, val parentFiles: List<JSONObject> = emptyList(),
    )

    private fun inspect(api: Gateway, token: String) {
        val pending = ArrayDeque<Candidate>()
        pending.add(Candidate("", 0, null))
        val seen = mutableSetOf<String>()
        var samples = 0
        var checkedParent = false
        while (pending.isNotEmpty() && seen.size < 18 && samples < 3 && !Thread.currentThread().isInterrupted) {
            val folder = pending.removeFirst()
            if (!seen.add(folder.id)) continue
            val raw = api.call("files", "parent" to folder.id)
            check(raw is JSONArray) { "Drive listing did not return an array" }
            val children = raw.objects()
            val subfolders = children.filter { it.optString("kind").endsWith("folder", ignoreCase = true) }
            if (seen.size == 1) {
                Log.i("PikPakLive", "root rows=${children.size} folders=${subfolders.size} " +
                    "with_modified=${children.count { it.stringOrNull("modified_time")?.cloudInstant() != null }}")
            }
            if (children.size in 1..2048 && subfolders.isEmpty()) {
                checkSample(api, token, folder, children, seen.size)
                samples++
                if (!checkedParent && folder.singleChildParent != null) {
                    checkSample(api, token, folder.singleChildParent, children + folder.parentFiles, seen.size)
                    checkedParent = true
                    Log.i("PikPakLive", "parent and nested file totals independently matched")
                }
            }
            if (folder.depth < 4) {
                subfolders.take(5).asReversed().forEach { child ->
                    val id = child.stringOrNull("id") ?: return@forEach
                    val parent = folder.takeIf { subfolders.size == 1 && children.size <= 2048 }
                    val siblings = if (parent == null) emptyList() else children.filter { it !== child }
                    pending.addFirst(Candidate(id, folder.depth + 1, child, parent, siblings))
                }
            }
        }
        check(samples > 0) { "No nonempty all-file folder with at most 2048 files in ${seen.size} inspected listings" }
    }

    private fun checkSample(api: Gateway, token: String, folder: Candidate, rawFiles: List<JSONObject>, inspected: Int) {
        fun stamp(row: JSONObject, key: String): Instant? = row.stringOrNull(key)?.let { value ->
            runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        }
        val modified = rawFiles.map { stamp(it, "modified_time") }
        val latest = modified.filterNotNull().maxOrNull()
        val expectedBytes = rawFiles.fold(BigInteger.ZERO) { total, row ->
            val bytes = row.stringOrNull("size")?.toBigIntegerOrNull()
            check(bytes != null && bytes.signum() >= 0) { "A listed file lacks a lossless byte count" }
            total + bytes
        }
        val response = api.call("folder_stats", "id" to folder.id, "scan" to token)
        check(response is JSONObject) { "Folder statistics did not return an object" }
        val stats = FolderStats.Complete.from(response)
        check(!stats.isEmpty) { "Nonempty folder was marked empty" }
        check(expectedBytes.toString() == stats.bytes.toString()) { "Recursive and listed byte totals differ" }
        check(latest == stats.modifiedAt) { "Recursive and listed latest timestamps differ" }

        val rows = rawFiles.toMutableList()
        folder.metadata?.let(rows::add)
        val entries = rows.map { raw ->
            val file = CloudFile.from(raw)
            if (file.folder) file.copy(folderStats = stats) else file
        }
        for (sort in listOf(FileSort.Created, FileSort.Recent, FileSort.ContentModified)) {
            val stamps = rows.associate { row ->
                val id = row.getString("id")
                val time = when (sort) {
                    FileSort.Created -> stamp(row, "created_time")
                    FileSort.ContentModified -> if (id == folder.id) latest else stamp(row, "modified_time")
                    else -> stamp(row, "modified_time")
                }
                id to time
            }
            for (descending in listOf(false, true)) {
                val ordered = DrivePresentation(sort = sort, descending = descending).sorted(entries, "")
                val values = stamps.values.filterNotNull().sorted()
                val expected = (if (descending) values.asReversed() else values) +
                    List(stamps.values.count { it == null }) { null }
                check(ordered.map { stamps[it.id] } == expected) { "Date order mismatch for ${sort.name}" }
            }
        }
        val validTimes = modified.count { it != null }
        val created = rawFiles.mapNotNull { stamp(it, "created_time") }
        val status = if (validTimes == 0) "no valid modified_time in listing; native returned null" else "matched"
        Log.i("PikPakLive", "folder_stats inspected=$inspected files=${rawFiles.size} " +
            "valid_created=${created.size} distinct_created=${created.distinct().size} " +
            "valid_modified=$validTimes distinct_modified=${modified.filterNotNull().distinct().size} " +
            "bytes=matched latest=$status date_orders=6_matched")
    }
}
