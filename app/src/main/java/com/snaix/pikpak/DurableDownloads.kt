package com.snaix.pikpak

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class DurableDownload(
    val id: String,
    val owner: String,
    val file: CloudFile,
    val destination: Uri,
    val startedAt: Long,
)

internal interface DownloadWriteGrants {
    fun isDocument(uri: Uri): Boolean
    fun hasWrite(uri: Uri): Boolean
    fun takeWrite(uri: Uri)
    fun releaseWrite(uri: Uri)
}

/** Private journal for single-document transfers with an exact, persisted write grant. */
internal class DurableDownloads(private val root: File, private val grants: DownloadWriteGrants) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "resumable-downloads"), object : DownloadWriteGrants {
        private val resolver = context.contentResolver
        private val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        override fun isDocument(uri: Uri) = DocumentsContract.isDocumentUri(context, uri) && !DocumentsContract.isTreeUri(uri)
        override fun hasWrite(uri: Uri) = resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        override fun takeWrite(uri: Uri) = resolver.takePersistableUriPermission(uri, write)
        override fun releaseWrite(uri: Uri) = resolver.releasePersistableUriPermission(uri, write)
    })

    private enum class Phase { Downloading, Writing, Completed, Cancelled }
    private data class Journal(val transfer: DurableDownload, val phase: Phase)
    data class Recovered(val transfer: DurableDownload, val status: TransferStatus, val bytes: Long)

    fun create(owner: String, file: CloudFile, uri: Uri): DurableDownload? = synchronized(lock) {
        val source = CloudFile(file.id, file.name, file.folder, bytes = file.bytes)
        val record = DurableDownload(UUID.randomUUID().toString(), owner, source, uri, System.currentTimeMillis())
        if (!valid(record) || runCatching { grants.hasWrite(uri) }.getOrDefault(true)) return null
        val journal = Journal(record, Phase.Downloading)
        try {
            // Record intent first so a crash after taking the grant still leaves a cleanup record.
            write(journal)
            grants.takeWrite(uri)
            check(grants.hasWrite(uri))
            claimed.add(directory(record).path)
            record
        } catch (_: Exception) {
            remove(record)
            null
        }
    }

    fun recover(owner: String): List<Recovered> = synchronized(lock) {
        root.listFiles().orEmpty().mapNotNull { directory ->
            if (!validId(directory.name) || directory.path in claimed || directory.canonicalFile.parentFile != root.canonicalFile) {
                return@mapNotNull null
            }
            val journal = read(directory)
            if (journal == null) {
                directory.deleteRecursively()
                return@mapNotNull null
            }
            val record = journal.transfer
            if (record.owner != owner) {
                remove(record)
                return@mapNotNull null
            }
            val status = when (journal.phase) {
                Phase.Completed -> TransferStatus.Completed
                Phase.Cancelled -> TransferStatus.Cancelled
                Phase.Writing -> TransferStatus.Interrupted
                Phase.Downloading -> if (hasGrant(record)) TransferStatus.Paused else TransferStatus.Interrupted
            }
            val bytes = maxOf(File(directory, "data").length(), File(directory, "data.part").length())
            if (status == TransferStatus.Paused) claimed.add(directory.path) else remove(record)
            Recovered(record, status, bytes)
        }.sortedByDescending { it.transfer.startedAt }
    }

    fun staging(record: DurableDownload): File = synchronized(lock) {
        val current = requireClaim(record)
        if (current.phase == Phase.Cancelled) throw TransferStopped()
        check(current.phase == Phase.Downloading || current.phase == Phase.Writing)
        check(hasGrant(record)) { appString(R.string.model_location_access_lost) }
        directory(record)
    }

    fun beginWriting(record: DurableDownload) = synchronized(lock) {
        val current = requireClaim(record)
        if (current.phase == Phase.Cancelled) throw TransferStopped()
        check(current.phase == Phase.Downloading || current.phase == Phase.Writing)
        check(hasGrant(record)) { appString(R.string.model_location_access_lost) }
        write(current.copy(phase = Phase.Writing))
    }

    fun finished(record: DurableDownload) = synchronized(lock) {
        val current = requireClaim(record)
        write(current.copy(phase = Phase.Completed))
    }

    fun cancel(record: DurableDownload) = synchronized(lock) {
        val current = requireClaim(record)
        if (current.phase != Phase.Completed) write(current.copy(phase = Phase.Cancelled))
    }

    fun release(record: DurableDownload, keep: Boolean) = synchronized(lock) {
        if (claimed.remove(directory(record).path) && !keep) remove(record)
    }

    fun discardOwner(owner: String) = synchronized(lock) {
        root.listFiles().orEmpty().forEach { directory ->
            if (!validId(directory.name) || directory.canonicalFile.parentFile != root.canonicalFile) return@forEach
            val journal = read(directory)?.takeIf { it.transfer.owner == owner } ?: return@forEach
            if (directory.path !in claimed) remove(journal.transfer)
            else if (journal.phase != Phase.Completed) write(journal.copy(phase = Phase.Cancelled))
        }
    }

    private fun requireClaim(record: DurableDownload): Journal {
        val directory = directory(record)
        check(directory.path in claimed) { appString(R.string.model_download_unavailable) }
        val current = read(directory)
        check(current != null && current.transfer == record) { appString(R.string.model_download_record_expired) }
        return current
    }

    private fun directory(record: DurableDownload): File {
        require(validId(record.id))
        return File(root, record.id).also { check(it.canonicalFile.parentFile == root.canonicalFile) }
    }

    private fun hasGrant(record: DurableDownload): Boolean = runCatching {
        grants.hasWrite(record.destination)
    }.getOrDefault(false)

    private fun remove(record: DurableDownload) {
        if (hasGrant(record)) runCatching { grants.releaseWrite(record.destination) }
        directory(record).deleteRecursively()
    }

    private fun write(journal: Journal) {
        val record = journal.transfer
        val file = AtomicFile(File(directory(record), "transfer.json"))
        val json = JSONObject().put("id", record.id).put("owner", record.owner)
            .put("file", record.file.id).put("title", record.file.name).put("bytes", record.file.bytes)
            .put("uri", record.destination.toString()).put("started", record.startedAt).put("phase", journal.phase.name)
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        check(read(directory(record)) == journal) { appString(R.string.model_download_record_failed) }
    }

    private fun read(directory: File): Journal? = runCatching {
        val json = JSONObject(AtomicFile(File(directory, "transfer.json")).readFully().toString(Charsets.UTF_8))
        val record = DurableDownload(json.getString("id"), json.getString("owner"),
            CloudFile(json.getString("file"), json.getString("title"), false, bytes = json.getLong("bytes")),
            Uri.parse(json.getString("uri")), json.getLong("started"))
        check(record.id == directory.name && valid(record))
        Journal(record, Phase.valueOf(json.getString("phase")))
    }.getOrNull()

    private fun valid(record: DurableDownload): Boolean {
        val uri = record.destination
        return validId(record.id) && record.owner.isNotBlank() && record.owner.length <= 128 &&
            !record.file.folder && record.file.id.matches(FILE_ID) && record.file.name.length in 1..4096 &&
            record.file.bytes >= 0 && uri.scheme == ContentResolver.SCHEME_CONTENT && !uri.authority.isNullOrBlank() &&
            uri.fragment == null && uri.encodedQuery == null && uri.toString().length <= 8192 &&
            runCatching { grants.isDocument(uri) }.getOrDefault(false)
    }

    private companion object {
        val FILE_ID = Regex("[A-Za-z0-9_-]{1,512}")
        val lock = Any()
        val claimed = mutableSetOf<String>()
        fun validId(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
