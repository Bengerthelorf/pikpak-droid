package com.snaix.pikpak

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class CacheWarmupState(
    val running: Boolean = false,
    val summary: String = "",
    val completed: Int = 0,
    val total: Int? = null,
)

/** Fills the same account-scoped entries consumed by the file list and icon view. */
internal class PreviewWarmup(
    private val context: Context,
    private val api: Gateway,
    private val owner: String,
    private val currentOwner: () -> String?,
    private val disk: PreviewDiskCache = PreviewDiskCache.forWarmup(context, currentOwner),
    private val previews: CloudPreviews = CloudPreviews(disk = disk, allowOriginalImage = false) { api.call("links", "id" to it) as? JSONObject },
    private val previewsStillRequested: () -> Boolean = { true },
    private val listingOwner: () -> String? = currentOwner,
    private val onIndexSynced: () -> Unit = {},
) {
    private class PreviewsCleared : CancellationException()
    private val previewGeneration = disk.generation

    private val scan = UUID.randomUUID().toString()
    @Volatile private var cancelled = false
    @Volatile private var scanning = false
    private val scanLock = Any()

    // Discovery and cover composition read the same listings, including the cover's child lookahead.
    private val listings = object : LruCache<String, List<CloudFile>>(4096) {
        override fun sizeOf(key: String, value: List<CloudFile>) = (value.size + 1).coerceAtMost(4096)
    }
    private val covers = FolderCovers(context, previews, disk, currentOwner, ::children)
    private val prefs = context.appearancePreferences()
    private val listingCache = DriveListingCache(context)

    /** This is callable from a second IO thread while JNI is waiting on the server. */
    fun cancel() {
        cancelled = true
        synchronized(scanLock) {
            if (scanning) runCatching { api.call("folder_stats_cancel", "scan" to scan) }
        }
    }

    private suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
        if (cancelled || owner != currentOwner()) throw CancellationException(appString(R.string.model_cache_stopped))
    }

    private suspend fun previewCheckpoint() {
        checkpoint()
        if (disk.generation != previewGeneration || !previewsStillRequested()) throw PreviewsCleared()
    }

    private suspend fun children(id: String): List<CloudFile> {
        previewCheckpoint()
        listings.get(id)?.let { return it }
        val ticket = listingCache.beginRead()
        val response = api.call("files", "parent" to id) as? JSONArray ?: error(appString(R.string.model_cache_cannot_read))
        previewCheckpoint()
        val files = response.files()
        if (owner == listingOwner()) listingCache.write(owner, id, files, ticket)
        return files.filter { it.id.isNotBlank() && (it.folder || it.thumbnail != null) }
            .also { listings.put(id, it) }
    }

    suspend fun run(progress: suspend (CacheWarmupState) -> Unit): String = withContext(Dispatchers.IO) {
        checkpoint()
        check(api.call("init") == true) { appString(R.string.model_sign_in_first) }
        val results = mutableListOf<String>()
        var retry = false
        suspend fun stage(label: String, work: suspend () -> String) {
            try {
                results += work()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                checkpoint()
                val reason = error.message?.trimEnd('。')?.takeIf { it.isNotBlank() }
                results += if (reason == null) label else "$label：$reason"
                retry = true
            }
        }
        if (prefs.getBoolean(DRIVE_FOLDER_STATS, true)) {
            stage(appString(R.string.model_cache_stats_unchanged)) {
                if (api.supportsFolderIndex) {
                    progress(CacheWarmupState(true, appString(R.string.model_cache_syncing_stats)))
                    syncIndex()
                    onIndexSynced()
                    appString(R.string.model_cache_stats_done)
                } else appString(R.string.model_cache_update_core)
            }
        }
        checkpoint()
        if (prefs.getBoolean(APPEARANCE_PREVIEW_CACHE, false)) {
            try {
                stage(appString(R.string.model_cache_previews_incomplete)) { fillPreviews(progress) }
            } catch (_: PreviewsCleared) {
                results += appString(R.string.model_cache_previews_cleared)
            }
        }
        val summary = results.joinToString(appString(R.string.model_stage_separator)).ifEmpty { appString(R.string.model_cache_enable_first) }
        if (retry) appString(R.string.model_cache_retry_summary, summary) else summary
    }

    private suspend fun syncIndex() {
        checkpoint()
        synchronized(scanLock) {
            if (cancelled) throw CancellationException()
            api.call("folder_stats_begin", "scan" to scan, "root" to false, "fresh" to false)
            scanning = true
        }
        try {
            checkpoint()
            // The native index coalesces concurrent syncs for the same account's SQLite path.
            api.call("folder_index_sync", "scan" to scan, "force_full" to false)
            checkpoint()
        } finally {
            synchronized(scanLock) {
                scanning = false
                runCatching { api.call("folder_stats_cancel", "scan" to scan) }
            }
        }
    }

    private suspend fun fillPreviews(progress: suspend (CacheWarmupState) -> Unit): String {
        previewCheckpoint()
        var slots = disk.warmupSlots()
        if (slots == 0) return appString(R.string.model_cache_limit_reached)
        val pending = ArrayDeque<CloudFile>()
        val seenFolders = mutableSetOf("")
        val planned = linkedMapOf<String, CloudFile>()
        var current: CloudFile? = null
        var scanned = 0
        var skipped = 0
        var capped = false
        var saved = 0
        var completed = 0

        suspend fun saveBatch(finalBatch: Boolean) {
            val total = if (finalBatch) completed + planned.size else null
            for ((key, file) in planned) {
                previewCheckpoint()
                if (disk.reachedCapacity) break
                val label = if (total == null) appString(R.string.model_cache_processed, completed) else "$completed/$total"
                progress(CacheWarmupState(true, appString(R.string.model_cache_previews_progress, label), completed, total))
                if (!disk.contains(key, owner)) {
                    if (file.folder) {
                        val selection = covers.selection(file)
                        if (key == covers.key(file, selection)) covers.load(FolderCoverRequest(file, selection, key))
                    } else {
                        previews.thumbnail(PreviewFile(file, canRefreshLink = true))
                    }
                }
                previewCheckpoint()
                completed++
                if (disk.contains(key, owner)) saved++ else if (!disk.reachedCapacity) skipped++
            }
            planned.clear()
        }

        suspend fun enqueue(key: String, file: CloudFile) {
            if (disk.contains(key, owner)) return
            planned[key] = file
            // Large user-selected limits need bounded discovery memory, including for one very large folder.
            if (planned.size >= 512 && planned.size < slots) {
                saveBatch(finalBatch = false)
                slots = disk.warmupSlots()
            }
        }
        fun planningLimitReached(): Boolean {
            if (disk.reachedCapacity) return true
            if (planned.size < slots) return false
            // The user can raise or turn off a limit while the service is discovering previews.
            slots = disk.warmupSlots()
            return planned.size >= slots
        }

        do {
            previewCheckpoint()
            progress(CacheWarmupState(true, appString(R.string.model_cache_scan_progress, scanned)))
            val folder = current
            val entries = try {
                children(folder?.id.orEmpty())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (folder == null) {
                    val reason = error.message?.takeIf { it.isNotBlank() }
                    throw IllegalStateException(if (reason == null) appString(R.string.model_cache_root_unreadable) else appString(R.string.model_cache_root_error, reason), error)
                }
                if (error is CloudError && error.code == "auth") throw error
                skipped++
                emptyList()
            }
            scanned++
            if (folder != null && entries.isNotEmpty()) {
                val key = covers.key(folder, covers.selection(folder))
                enqueue(key, folder)
            }
            for (file in entries) {
                if (planningLimitReached()) {
                    capped = true
                    break
                }
                if (file.folder) {
                    if (seenFolders.add(file.id)) pending.addLast(file)
                } else {
                    val key = file.previewDiskKey("thumbnail")
                    enqueue(key, file)
                }
            }
            if (!capped && pending.isNotEmpty() && planningLimitReached()) capped = true
            current = pending.removeFirstOrNull()
        } while (current != null && !capped)

        saveBatch(finalBatch = true)
        return buildList {
            add(if (completed == 0 && skipped == 0 && !disk.reachedCapacity) appString(R.string.model_cache_already_saved) else appString(R.string.model_cache_saved_count, saved))
            if (skipped > 0) add(appString(R.string.model_cache_skipped_count, skipped))
            if (capped || disk.reachedCapacity) add(appString(R.string.model_cache_limit_reached))
        }.joinToString(appString(R.string.model_item_separator))
    }
}
