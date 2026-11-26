package com.snaix.pikpak

import android.content.SharedPreferences
import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.TreeSet
import java.util.WeakHashMap

internal data class PreviewCacheLimits(
    val maxMiB: Int = 64,
    val maxEntries: Int = 480,
    val sizeEnabled: Boolean = true,
    val countEnabled: Boolean = true,
) {
    val maxBytes: Long get() = maxMiB.toLong() * 1024 * 1024

    companion object {
        const val SIZE_KEY = "preview_cache_max_mib"
        const val COUNT_KEY = "preview_cache_max_entries"
        const val SIZE_ENABLED_KEY = "preview_cache_max_mib_enabled"
        const val COUNT_ENABLED_KEY = "preview_cache_max_entries_enabled"

        fun read(prefs: SharedPreferences): PreviewCacheLimits {
            val defaults = PreviewCacheLimits()
            return PreviewCacheLimits(
                prefs.getInt(SIZE_KEY, defaults.maxMiB).takeIf { it > 0 } ?: defaults.maxMiB,
                prefs.getInt(COUNT_KEY, defaults.maxEntries).takeIf { it > 0 } ?: defaults.maxEntries,
                prefs.getBoolean(SIZE_ENABLED_KEY, defaults.sizeEnabled),
                prefs.getBoolean(COUNT_ENABLED_KEY, defaults.countEnabled),
            )
        }
    }
}

internal data class PreviewCacheUsage(val bytes: Long, val entries: Int)

/** Shared by browsing and warmup. Callers hold this object's monitor while changing files and usage. */
internal class PreviewCacheBudget private constructor(private val directory: File) {
    private data class Entry(val file: File, val modified: Long, val bytes: Long)

    private val files = mutableMapOf<File, Entry>()
    private val oldestFirst = TreeSet(compareBy<Entry> { it.modified }.thenBy { it.file.name })
    private var modified: Long? = null
    private var bytes = 0L

    @Volatile var generation = 0L
        private set

    fun usage() = PreviewCacheUsage(bytes, files.size)

    fun clear(): PreviewCacheUsage {
        generation++
        val cleared = !directory.exists() || directory.deleteRecursively()
        refresh(force = true)
        if (!cleared) throw java.io.IOException(appString(R.string.model_preview_clear_failed))
        return usage()
    }

    fun refresh(force: Boolean = false) {
        val stamp = directory.lastModified()
        if (force || stamp != modified) {
            files.clear()
            oldestFirst.clear()
            bytes = 0
            for (file in directory.listFiles { entry -> entry.extension == "png" }.orEmpty()) record(file)
            modified = stamp
        }
    }

    fun remaining(limits: PreviewCacheLimits = PreviewCacheLimits()): Int = when {
        limits.sizeEnabled && bytes >= limits.maxBytes -> 0
        limits.countEnabled -> (limits.maxEntries - files.size).coerceAtLeast(0)
        else -> Int.MAX_VALUE
    }

    fun contains(file: File) = file in files

    fun written(file: File): Long {
        record(file)
        modified = directory.lastModified()
        return files[file]?.bytes ?: 0L
    }

    fun exceedsLimits(limits: PreviewCacheLimits) =
        (limits.sizeEnabled && bytes > limits.maxBytes) ||
            (limits.countEnabled && files.size > limits.maxEntries)

    fun remove(file: File) {
        if (file.delete() || !file.exists()) {
            forget(file)
            modified = directory.lastModified()
        }
    }

    fun trim(limits: PreviewCacheLimits, keep: File? = null) {
        val entries = oldestFirst.iterator()
        while (exceedsLimits(limits) && entries.hasNext()) {
            val entry = entries.next()
            if (entry.file == keep) continue
            if (entry.file.delete() || !entry.file.exists()) {
                entries.remove()
                files.remove(entry.file)
                bytes -= entry.bytes
                modified = directory.lastModified()
            }
        }
    }

    private fun record(file: File) {
        forget(file)
        val attrs = try { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java) }
            catch (_: java.nio.file.NoSuchFileException) { return }
        if (!attrs.isRegularFile) return
        val entry = Entry(file, attrs.lastModifiedTime().toMillis(), attrs.size())
        files[file] = entry
        oldestFirst += entry
        bytes += entry.bytes
    }

    private fun forget(file: File) {
        files.remove(file)?.let { entry ->
            oldestFirst.remove(entry)
            bytes -= entry.bytes
        }
    }

    companion object {
        private val directories = WeakHashMap<File, WeakReference<PreviewCacheBudget>>()

        fun forDirectory(directory: File): PreviewCacheBudget = synchronized(directories) {
            directories[directory]?.get() ?: PreviewCacheBudget(directory).also { directories[directory] = WeakReference(it) }
        }
    }
}
