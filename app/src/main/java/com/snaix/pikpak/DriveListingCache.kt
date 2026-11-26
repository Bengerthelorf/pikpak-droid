package com.snaix.pikpak

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal data class CachedSearchDirectory(val folders: List<String>, val matches: List<CloudFile>)

/** Complete child listings only. A missing snapshot differs from a known empty folder. */
internal class DriveListingCache(context: Context) {
    private val directory = File(context.cacheDir, "drive-listings-v1")

    fun beginRead(): Long = sequence.incrementAndGet()

    fun read(owner: String, parent: String, favorites: Boolean = false): List<CloudFile>? {
        if (owner.isBlank()) return null
        val files = mutableListOf<CloudFile>()
        val valid = readRows(snapshot(owner, parent, favorites), touch = true) { record, bytes ->
            files += CloudFile.from(record).copy(bytes = bytes)
        }
        return if (valid) files else null
    }

    fun searchDirectory(owner: String, parent: String, term: String, checkpoint: () -> Unit): CachedSearchDirectory? {
        if (owner.isBlank()) return null
        checkpoint()
        val folders = mutableListOf<String>()
        val matches = mutableListOf<CloudFile>()
        val valid = readRows(snapshot(owner, parent, favorites = false), touch = false) { record, bytes ->
            checkpoint()
            val id = record.getString("id")
            if (record.optString("kind").endsWith("folder", ignoreCase = true)) folders += id
            if (record.getString("name").contains(term, ignoreCase = true)) {
                matches += CloudFile.from(record).copy(bytes = bytes)
            }
        }
        return if (valid) CachedSearchDirectory(folders, matches) else null
    }

    private inline fun readRows(file: File, touch: Boolean, visit: (JSONObject, Long) -> Unit): Boolean =
        synchronized(lockFor(file)) {
            try {
                AtomicFile(file).openRead().bufferedReader().use { reader ->
                    val count = JSONArray(reader.readLine() ?: throw EOFException()).getInt(2)
                    if (count < 0) throw IOException("Invalid cached listing length")
                    repeat(count) {
                        val record = JSONObject(reader.readLine() ?: throw EOFException())
                        visit(record, record.getLong("bytes"))
                    }
                    if (reader.readLine() != null) throw IOException("Invalid cached listing length")
                    if (touch) file.setLastModified(System.currentTimeMillis())
                    true
                }
            } catch (_: IOException) {
                false
            } catch (_: JSONException) {
                false
            }
        }

    /** ticket is taken when the remote request starts, so a slow warmup cannot roll back a newer listing. */
    fun write(owner: String, parent: String, files: List<CloudFile>?, ticket: Long, favorites: Boolean = false) {
        if (owner.isBlank()) return
        val file = snapshot(owner, parent, favorites)
        synchronized(lockFor(file)) {
            val atomic = AtomicFile(file)
            try {
                if (hasNewerSnapshot(atomic, ticket)) return
                val out = atomic.startWrite()
                try {
                    // One JSON record per line avoids constructing a second in-memory copy of a large directory.
                    val writer = out.bufferedWriter()
                    // A tombstone also prevents an older in-flight fetch from restoring invalidated rows.
                    writer.appendLine(JSONArray().put(process).put(ticket).put(files?.size ?: -1).toString())
                    for (entry in files.orEmpty()) {
                        writer.appendLine(entry.listingJson().toString())
                    }
                    writer.flush()
                    atomic.finishWrite(out)
                } catch (failure: Exception) {
                    atomic.failWrite(out)
                    throw failure
                }
            } catch (_: IOException) {
                return
            }
        }
    }

    private fun hasNewerSnapshot(atomic: AtomicFile, ticket: Long): Boolean = try {
        atomic.openRead().bufferedReader().use { reader ->
            val header = JSONArray(reader.readLine() ?: throw EOFException())
            header.getString(0) == process && header.getLong(1) > ticket
        }
    } catch (_: IOException) {
        false
    } catch (_: JSONException) {
        false
    }

    private fun snapshot(owner: String, parent: String, favorites: Boolean): File =
        File(directory, digestKey(owner, if (favorites) "favorites" else "files", parent) + ".jsonl")

    companion object {
        private val sequence = AtomicLong()
        private val process = UUID.randomUUID().toString()
        private val locks = Array(16) { Any() }
        private fun lockFor(file: File) = locks[(file.hashCode() and Int.MAX_VALUE) % locks.size]
    }
}

internal fun CloudFile.listingJson(): JSONObject = JSONObject().put("id", id).put("name", name)
    .put("kind", if (folder) "folder" else "file").put("bytes", bytes).put("size", fileSize?.toString())
    .put("created_time", created).put("modified_time", modified).put("starred", starred)
    .put("thumbnail_link", thumbnail?.toString())
