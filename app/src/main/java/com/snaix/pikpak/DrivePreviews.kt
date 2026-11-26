package com.snaix.pikpak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AtomicFile
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

internal class PreviewDiskCache private constructor(
    context: Context, private val fillOnly: Boolean, private val accountScope: () -> String?,
) {
    constructor(context: Context, accountScope: () -> String?) : this(context, false, accountScope)
    private val directory = File(context.cacheDir, "drive-previews")
    private val preferences = context.appearancePreferences()
    private val budget = PreviewCacheBudget.forDirectory(directory)

    val generation: Long get() = budget.generation
    private val initialGeneration = generation

    fun usage(): PreviewCacheUsage = synchronized(budget) {
        budget.refresh()
        budget.usage()
    }

    fun applyLimits(): PreviewCacheUsage = synchronized(budget) {
        budget.refresh(force = true)
        val limits = PreviewCacheLimits.read(preferences)
        budget.trim(limits)
        if (budget.exceedsLimits(limits)) throw java.io.IOException(appString(R.string.model_preview_trim_failed))
        budget.usage()
    }

    fun clear(): PreviewCacheUsage = synchronized(budget) { budget.clear() }

    fun account(): String? = if (preferences.getBoolean(APPEARANCE_PREVIEW_CACHE, false)) accountScope() else null

    @Volatile var reachedCapacity = false
        private set

    fun contains(key: String, owner: String): Boolean = synchronized(budget) {
        val file = cacheFile(key, owner) ?: return@synchronized false
        file.isFile
    }

    fun warmupSlots(): Int = synchronized(budget) {
        // Account for Android reclaiming cache files while the app was idle.
        budget.refresh(force = true)
        budget.remaining(PreviewCacheLimits.read(preferences))
    }

    fun read(key: String, account: String): Bitmap? = synchronized(budget) {
        val file = cacheFile(key, account) ?: return@synchronized null
        if (!file.isFile) return@synchronized null
        BitmapFactory.decodeFile(file.path)
    }

    fun write(key: String, bitmap: Bitmap, account: String, requestGeneration: Long = generation) = synchronized(budget) {
        if (requestGeneration != generation || (fillOnly && initialGeneration != generation)) return@synchronized
        val file = cacheFile(key, account) ?: return@synchronized
        runCatching {
            directory.mkdirs()
            budget.refresh()
            val limits = PreviewCacheLimits.read(preferences)
            if (fillOnly) {
                if (budget.contains(file) && file.exists()) return@synchronized
                if (budget.remaining(limits) == 0) {
                    // Reconcile space Android may have reclaimed while this batch was running.
                    budget.refresh(force = true)
                    if (budget.remaining(limits) == 0) {
                        reachedCapacity = true
                        return@synchronized
                    }
                }
            }
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
            val imageBytes = budget.written(file)
            if (limits.sizeEnabled && imageBytes > limits.maxBytes) {
                budget.remove(file)
            } else if (fillOnly && budget.exceedsLimits(limits)) {
                // A manual fill must not displace a preview the user already viewed.
                budget.remove(file)
                reachedCapacity = true
            } else if (!fillOnly) {
                // Multiple writes can have the same filesystem timestamp. Keep the just-viewed image.
                budget.trim(limits, keep = file)
            }
        }
        Unit
    }

    fun invalidate(key: String) = synchronized(budget) {
        val owner = account() ?: return@synchronized
        val file = cacheFile(key, owner) ?: return@synchronized
        budget.refresh()
        budget.remove(file)
    }

    private fun cacheFile(key: String, scope: String): File? {
        if (!preferences.getBoolean(APPEARANCE_PREVIEW_CACHE, false)) return null
        if (accountScope() != scope) return null
        return File(directory, "${digestKey(scope, key)}.png")
    }

    companion object {
        fun forWarmup(context: Context, accountScope: () -> String?) = PreviewDiskCache(context, true, accountScope)
    }
}

internal class FolderCovers(
    private val context: Context,
    private val previews: CloudPreviews,
    private val disk: PreviewDiskCache,
    private val accountScope: () -> String?,
    private val listChildren: suspend (String) -> List<CloudFile>,
) {
    private val prefs = context.getSharedPreferences("folder_covers", Context.MODE_PRIVATE)
    private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val coverSlots = Semaphore(2)
    private val diskMutations = Mutex()
    private var cacheRevision = 0L
    private var memoryGeneration = disk.generation

    fun selection(folder: CloudFile): List<String> {
        val key = preferenceKey(folder) ?: return emptyList()
        val value = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val ids = JSONArray(value)
            (0 until ids.length()).map(ids::getString).take(LIMIT)
        }.getOrDefault(emptyList())
    }

    fun setSelection(folder: CloudFile, selected: List<String>) {
        val key = preferenceKey(folder) ?: return
        val edit = prefs.edit()
        if (selected.isEmpty()) edit.remove(key) else edit.putString(key, JSONArray(selected.take(LIMIT)).toString())
        edit.apply()
    }

    fun clearMemory() = synchronized(memory) {
        memoryGeneration = disk.generation
        cacheRevision++
        memory.evictAll()
    }

    private fun cachedCover(key: String): Bitmap? = synchronized(memory) {
        if (memoryGeneration != disk.generation) clearMemory()
        memory.get(key)
    }

    fun key(folder: CloudFile, selection: List<String>): String = digestKey(
        "folder-cover-v2", accountScope().orEmpty(), folder.id, folder.modified, folder.created,
        selection.joinToString("\u0000"),
    )

    suspend fun invalidate(keys: List<String>) {
        if (keys.isEmpty()) return
        withContext(Dispatchers.IO) {
            diskMutations.withLock {
                synchronized(memory) {
                    cacheRevision++
                    for (key in keys) memory.remove(key)
                }
                for (key in keys) disk.invalidate(key)
            }
        }
    }

    suspend fun candidates(folder: CloudFile): List<CloudFile> = listChildren(folder.id)
        .filter { it.id != folder.id && (it.folder || it.thumbnail != null) }
        .sortedWith(coverOrder)

    suspend fun load(request: FolderCoverRequest): Bitmap? {
        if (request.key != key(request.folder, request.selection)) return null
        val generation = disk.generation
        val revision = synchronized(memory) {
            cachedCover(request.key)?.let { return it.takeIf { image -> image.width > 1 && generation == disk.generation } }
            cacheRevision
        }
        val owner = disk.account()
        if (owner != null) {
            val cached = withContext(Dispatchers.IO) {
                diskMutations.withLock { disk.read(request.key, owner) }
            }
            if (cached != null) return cached.takeIf { it.width > 1 && remember(request, revision, it) }
        }
        val rendered = coverSlots.withPermit {
            if (generation != disk.generation || synchronized(memory) { cacheRevision != revision } ||
                request.key != key(request.folder, request.selection)) return@withPermit null
            cachedCover(request.key)?.let { return@withPermit it }
            try {
                val files = candidates(request.folder)
                val available = files.associateBy(CloudFile::id)
                val manual = request.selection.mapNotNull(available::get)
                val picked = manual.ifEmpty { files.sortedBy(CloudFile::folder).take(LIMIT) }
                val layers = picked.map { candidate ->
                    if (candidate.folder) folderLayer(candidate, request.folder.id, generation)
                    else previews.thumbnail(PreviewFile(candidate, canRefreshLink = true), generation)
                        ?.let { CoverLayer(it, folder = false) }
                }
                val visible = layers.filterNotNull()
                if (visible.isEmpty() && picked.isNotEmpty()) return@withPermit null
                val cover = withContext(Dispatchers.Default) { coverBitmap(context, visible) }
                // Partial results stay visible, but a future load may recover the missing preview.
                if (visible.size == picked.size && visible.none(CoverLayer::retryable)) {
                    withContext(Dispatchers.IO) {
                        diskMutations.withLock<Unit> {
                            if (remember(request, revision, cover) && owner != null && visible.isNotEmpty()) {
                                disk.write(request.key, cover, owner, generation)
                            }
                        }
                    }
                }
                cover.takeIf { synchronized(memory) { cacheRevision == revision } && request.key == key(request.folder, request.selection) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        return rendered?.takeIf { it.width > 1 && generation == disk.generation }
    }

    private fun remember(request: FolderCoverRequest, revision: Long, bitmap: Bitmap): Boolean =
        synchronized(memory) {
            if (memoryGeneration != disk.generation || cacheRevision != revision || request.key != key(request.folder, request.selection)) return@synchronized false
            memory.put(request.key, bitmap)
            true
        }

    private suspend fun folderLayer(child: CloudFile, parentId: String, generation: Long): CoverLayer {
        val visited = mutableSetOf(parentId)
        var folder = child
        var retryable = false
        // One child listing and at most one further level, even in a folder-only hierarchy.
        for (level in 0 until CHILD_LOOKUPS) {
            if (generation != disk.generation || !visited.add(folder.id)) break
            val entries = try {
                candidates(folder)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                retryable = true
                break
            }
            val files = entries.asSequence().filterNot(CloudFile::folder).take(CHILD_MEDIA_ATTEMPTS).toList()
            for (file in files) {
                val image = previews.thumbnail(PreviewFile(file, canRefreshLink = true), generation)
                if (image != null) return CoverLayer(image, folder = true)
                retryable = true
            }
            if (level + 1 == CHILD_LOOKUPS) break
            folder = entries.firstOrNull { it.folder && it.id !in visited } ?: break
        }
        return CoverLayer(null, folder = true, retryable = retryable)
    }

    private fun preferenceKey(folder: CloudFile): String? = accountScope()?.let { digestKey(it, folder.id) }

    companion object {
        const val LIMIT = 3
        private const val CHILD_LOOKUPS = 2
        private const val CHILD_MEDIA_ATTEMPTS = 2
        private val coverOrder = compareByDescending<CloudFile> { it.modifiedAt }
            .thenBy(CloudFile::name).thenBy(CloudFile::id)
    }
}

internal data class FolderCoverRequest(val folder: CloudFile, val selection: List<String>, val key: String)

private data class CoverLayer(val image: Bitmap?, val folder: Boolean, val retryable: Boolean = false)

internal fun digestKey(vararg parts: String): String = Base64.encodeToString(
    MessageDigest.getInstance("SHA-256").digest(JSONArray(parts.toList()).toString().toByteArray()),
    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
)

private fun coverBitmap(context: Context, layers: List<CoverLayer>): Bitmap {
    if (layers.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    val bitmap = Bitmap.createBitmap(192, 124, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val path = Path()
    val folderIcon = if (layers.any(CoverLayer::folder)) context.icon(Symbol.Folder, 0xff456179.toInt()) else null
    for (index in layers.indices.reversed()) {
        val layer = layers[index]
        val image = layer.image
        val offset = when (index) { 1 -> -38f; 2 -> 38f; else -> 0f }
        val y = if (index == 0) 20f else 11f
        val card = RectF(48f + offset, y, 144f + offset, y + 92f)
        canvas.save()
        canvas.rotate(if (index == 0) 0f else offset / 3f, card.centerX(), card.centerY())
        paint.color = 0x50000000
        canvas.drawRoundRect(RectF(card).apply { offset(0f, 3f) }, 9f, 9f, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(card, 9f, 9f, paint)
        val inner = RectF(card).apply { inset(3f, 3f) }
        path.reset()
        path.addRoundRect(inner, 6f, 6f, Path.Direction.CW)
        canvas.clipPath(path)
        if (image == null) {
            paint.color = 0xffdfebf3.toInt()
            canvas.drawRoundRect(inner, 6f, 6f, paint)
        } else {
            val side = minOf(image.width, image.height)
            val x = (image.width - side) / 2
            val top = (image.height - side) / 2
            canvas.drawBitmap(image, Rect(x, top, x + side, top + side), inner, paint)
        }
        if (layer.folder && folderIcon != null) {
            val bounds = if (image == null) {
                val x = inner.centerX().toInt()
                val y = inner.centerY().toInt()
                Rect(x - 23, y - 23, x + 23, y + 23)
            } else {
                val x = inner.right.toInt() - 6
                val y = inner.bottom.toInt() - 6
                paint.color = Color.WHITE
                canvas.drawRoundRect(RectF(x - 24f, y - 24f, x + 2f, y + 2f), 5f, 5f, paint)
                Rect(x - 22, y - 22, x, y)
            }
            folderIcon.bounds = bounds
            folderIcon.draw(canvas)
        }
        canvas.restore()
    }
    return bitmap
}
