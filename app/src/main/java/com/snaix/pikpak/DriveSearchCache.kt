package com.snaix.pikpak

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.PriorityQueue

internal const val DRIVE_SEARCH_VISIBLE_LIMIT = 300
internal const val DRIVE_SEARCH_FRESH_MS = 15 * 60_000L

internal data class DriveSearchMatches(val files: List<CloudFile>, val count: Int, val hasNext: Boolean)

internal class DriveSearchMatcher(private val term: String, options: DrivePresentation, private val after: CloudFile? = null) {
    private val order = options.fileOrder()
    private val top = PriorityQueue(DRIVE_SEARCH_VISIBLE_LIMIT, order.reversed())
    private var count = 0
    private var afterCount = 0

    fun accept(file: CloudFile) {
        if (!file.name.contains(term, ignoreCase = true)) return
        count++
        if (after != null && order.compare(file, after) <= 0) return
        afterCount++
        if (top.size < DRIVE_SEARCH_VISIBLE_LIMIT) top.add(file)
        else if (order.compare(file, top.peek()) < 0) {
            top.remove()
            top.add(file)
        }
    }

    fun matches() = DriveSearchMatches(top.sortedWith(order), count, hasNext = afterCount > top.size)
}

internal data class DriveSearchCachedMatches(val time: Long, val matches: DriveSearchMatches)

/** A native search page uses the same row and sort cursor for forwards and backwards navigation. */
internal fun Gateway.readIndexedMatches(term: String, options: DrivePresentation, after: CloudFile?,
    folderStats: Boolean): DriveSearchCachedMatches? {
    val cursor = after?.listingJson()?.apply {
        put("kind", if (after.folder) "Folder" else "File")
        (after.folderStats as? FolderStats.Complete)?.let { stats ->
            put("folder_stats", JSONObject().put("bytes", stats.bytes.toString())
                .put("modified_at", stats.modifiedAt?.toString()).put("is_empty", stats.isEmpty))
        }
    }
    val response = call("drive_search_cached", "query" to term, "sort" to options.sort.name,
        "descending" to options.descending, "folder_stats" to folderStats,
        "limit" to DRIVE_SEARCH_VISIBLE_LIMIT, "after" to cursor) ?: return null
    val page = response as? JSONObject ?: throw JSONException("Invalid indexed search page")
    val rows = page.getJSONArray("files")
    val count = page.getInt("count")
    val more = page.getBoolean("has_next")
    check(rows.length() <= DRIVE_SEARCH_VISIBLE_LIMIT && count >= rows.length() && (!more || rows.length() > 0))
    val files = (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        CloudFile.from(row).copy(folderStats = row.optJSONObject("folder_stats")?.let(FolderStats.Complete::from))
    }
    val checked = Math.multiplyExact(page.getLong("checked_at"), 1000L)
    return DriveSearchCachedMatches(checked, DriveSearchMatches(files, count, more))
}

/** A single complete whole-drive snapshot. The previous one survives errors and partial writes. */
internal class DriveSearchCache(private val file: File) {
    constructor(context: Context) : this(File(context.cacheDir, "drive-search-v1.jsonl"))

    private val stored = AtomicFile(file)

    fun read(owner: String, term: String, options: DrivePresentation, after: CloudFile?, checkpoint: () -> Unit): DriveSearchCachedMatches? {
        val stream = synchronized(lock) {
            try {
                stored.openRead()
            } catch (_: IOException) {
                return null
            }
        }
        return try {
            stream.bufferedReader().use { reader ->
                val header = JSONArray(reader.readLine() ?: return null)
                if (header.getString(0) != owner) return null
                val time = header.getLong(1)
                val count = header.getInt(2)
                if (count < 0) return null
                val matching = DriveSearchMatcher(term, options, after)
                repeat(count) {
                    checkpoint()
                    val record = JSONObject(reader.readLine() ?: return null)
                    if (record.getString("name").contains(term, ignoreCase = true)) {
                        matching.accept(CloudFile.from(record).copy(bytes = record.getLong("bytes")))
                    }
                }
                if (reader.readLine() != null) return null
                DriveSearchCachedMatches(time, matching.matches())
            }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    fun write(owner: String, rows: JSONArray, time: Long, revision: Long, checkpoint: () -> Unit): Boolean = synchronized(lock) {
        if (changes.value != revision) return@synchronized false
        val stream = try { stored.startWrite() } catch (_: IOException) { return@synchronized false }
        val header = JSONArray().put(owner).put(time).put(rows.length()).toString()
        try {
            val writer = stream.bufferedWriter()
            writer.appendLine(header)
            for (index in 0 until rows.length()) {
                checkpoint()
                val record = CloudFile.from(rows.getJSONObject(index)).listingJson().toString()
                writer.appendLine(record)
            }
            checkpoint()
            writer.flush()
            stored.finishWrite(stream)
        } catch (_: IOException) {
            stored.failWrite(stream)
            return@synchronized false
        } catch (error: Exception) {
            stored.failWrite(stream)
            throw error
        }
        // AtomicFile may log a failed rename without throwing.
        try {
            stored.openRead().bufferedReader().use { it.readLine() == header }
        } catch (_: IOException) {
            false
        }
    }

    fun clearAccountIndex(api: Gateway, hasOwner: Boolean) {
        try {
            if (api.supportsDriveSearch) api.call("drive_search_clear")
        } catch (error: CloudError) {
            if (error.code != "index_unavailable" && (hasOwner || error.code != "auth")) throw error
        }
        clear()
    }

    fun clear(owner: String? = null) = synchronized(lock) {
        val matchesOwner = owner == null || try {
            stored.openRead().bufferedReader().use { JSONArray(it.readLine()).getString(0) == owner }
        } catch (_: Exception) {
            false
        }
        if (matchesOwner) stored.delete()
        changes.value++
    }

    companion object {
        private val lock = Any()
        private val changes = MutableStateFlow(0L)
        val cleared = changes.asStateFlow()
    }
}
