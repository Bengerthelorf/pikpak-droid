package com.snaix.pikpak

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Size
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.coroutines.resume

internal object NativeClient {
    external fun request(json: String): String
}

internal class CloudError(
    val code: String,
    message: String,
    val challenge: String? = null,
) : Exception(message)

internal interface Gateway {
    val supportsFolderIndex: Boolean get() = false
    val supportsDriveSearch: Boolean get() = false
    val supportsShareBrowsing: Boolean get() = false
    fun call(operation: String, vararg args: Pair<String, Any?>): Any?

    fun transfer(
        operation: String, control: TransferControl, progress: TransferProgress, vararg args: Pair<String, Any?>,
    ) {
        control.checkpoint()
        call(operation, *args)
    }
}

private fun Gateway.coreCapabilities(): Set<String> {
    val capabilities = try {
        (call("core_info") as? JSONObject)?.optJSONArray("capabilities")
    } catch (_: CloudError) {
        // API 1 cores predating capability discovery reject core_info.
        null
    } ?: return emptySet()
    return (0 until capabilities.length()).mapTo(mutableSetOf()) { capabilities.optString(it) }
}

internal fun Gateway.advertisesFolderIndex() = "folder_index" in coreCapabilities()

internal class CloudApi(private val context: Context) : Gateway {
    private val session = File(context.noBackupFilesDir, "pikpak-session.json")
    private val capabilities by lazy { coreCapabilities() }
    override val supportsFolderIndex: Boolean get() = "folder_index" in capabilities
    override val supportsDriveSearch: Boolean get() = "drive_search_cached" in capabilities
    override val supportsShareBrowsing: Boolean get() = CoreRuntime.supportsShareBrowsing

    override fun call(operation: String, vararg args: Pair<String, Any?>): Any? {
        val input = JSONObject().put("op", operation)
        if (operation in setOf("init", "login", "logout")) input.put("path", session.path)
        args.forEach { (key, value) ->
            input.put(key, if (value is Collection<*>) JSONArray(value) else value ?: JSONObject.NULL)
        }
        val reply = JSONObject(NativeClient.request(input.toString()))
        if (operation == "init") CoreRuntime.recordHealthy(context)
        if (reply.has("error")) {
            val code = reply.optString("code")
            throw CloudError(code, errorText(code), reply.stringOrNull("challenge"))
        }
        return reply.opt("value").takeUnless { it == JSONObject.NULL }
    }

    override fun transfer(
        operation: String, control: TransferControl, progress: TransferProgress, vararg args: Pair<String, Any?>,
    ) {
        val token = "transfer" to UUID.randomUUID().toString()
        call("transfer_begin", token, "kind" to operation)
        try {
            control.attach(stop = { runCatching { call("transfer_stop", token) } }, sample = {
                val latest = runCatching { call("transfer_progress", token) as JSONObject }.getOrNull()
                latest?.takeIf { it.optLong("total") > 0 || it.optLong("bytes") > 0 }?.let {
                    val stage = when (it.optString("stage")) {
                        "Hashing" -> TransferStage.Hashing
                        "Finalizing on server" -> TransferStage.Finishing
                        else -> progress.stage
                    }
                    progress.copy(stage = stage, bytes = it.optLong("bytes"), byteTotal = it.optLong("total").takeIf { bytes -> bytes > 0 })
                }
            })
            control.checkpoint()
            call(operation, token, *args)
        } finally {
            control.detach()
            runCatching { call("transfer_end", token) }
        }
    }
}

private fun errorText(code: String) = when (code) {
    "pending" -> appString(R.string.model_cloud_pending)
    "auth" -> appString(R.string.model_cloud_auth)
    "login" -> appString(R.string.model_cloud_login)
    "session_save" -> appString(R.string.model_cloud_session_save)
    "own_share" -> appString(R.string.model_cloud_own_share)
    "share" -> appString(R.string.model_cloud_share)
    "quota" -> appString(R.string.model_cloud_quota)
    "timeout" -> appString(R.string.model_cloud_timeout)
    "connect", "network" -> appString(R.string.model_cloud_network)
    "unavailable" -> appString(R.string.model_cloud_unavailable)
    "restricted" -> appString(R.string.model_cloud_restricted)
    "challenge" -> appString(R.string.model_cloud_challenge)
    else -> appString(R.string.model_cloud_failed)
}

internal data class CloudFile(
    val id: String,
    val name: String,
    val folder: Boolean,
    val bytes: Long = 0,
    val modified: String = "",
    val starred: Boolean = false,
    val thumbnail: Uri? = null,
    val created: String = "",
    val fileSize: ULong? = bytes.takeIf { it >= 0 }?.toULong(),
    val folderStats: FolderStats? = null,
) {
    val modifiedAt = modified.cloudInstant()
    val createdAt = created.cloudInstant()
    val contentModifiedAt: Instant? get() =
        if (folder) (folderStats as? FolderStats.Complete)?.modifiedAt else modifiedAt
    val displayBytes: ULong? get() =
        if (folder) (folderStats as? FolderStats.Complete)?.bytes else fileSize

    val category: FileCategory
        get() = if (folder) FileCategory.Folder else when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "mkv", "avi", "mov", "webm", "wmv", "m4v", "ts", "rmvb", "flv" -> FileCategory.Video
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif" -> FileCategory.Image
            "mp3", "flac", "wav", "ogg", "m4a", "aac", "opus" -> FileCategory.Audio
            "txt", "md", "csv", "json", "xml", "yaml", "yml", "log", "srt", "ass", "ini", "toml" -> FileCategory.Text
            else -> FileCategory.Document
        }

    companion object {
        fun from(value: JSONObject) = CloudFile(
            id = value.getString("id"),
            name = value.getString("name"),
            folder = value.optString("kind").endsWith("folder", ignoreCase = true),
            bytes = value.optLong("size"),
            modified = value.optString("modified_time"),
            starred = value.optBoolean("starred"),
            thumbnail = httpsUri(value.stringOrNull("thumbnail_link")),
            created = value.optString("created_time"),
            fileSize = value.stringOrNull("size")?.toULongOrNull(),
        )
    }
}

internal fun String.cloudInstant(): Instant? =
    if (isBlank()) null else runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()

internal sealed interface FolderStats {
    data object Pending : FolderStats
    data object Failed : FolderStats
    data class Complete(val bytes: ULong, val modifiedAt: Instant?, val isEmpty: Boolean) : FolderStats {
        companion object {
            fun from(value: JSONObject) = Complete(
                value.getString("bytes").toULong(),
                value.stringOrNull("modified_at")?.cloudInstant(),
                value.getBoolean("is_empty"),
            )
        }
    }
}

internal enum class FileCategory(private val titleResource: Int) {
    Folder(R.string.model_kind_folder), Video(R.string.model_kind_video), Image(R.string.model_kind_image), Audio(R.string.model_kind_audio), Text(R.string.model_kind_text), Document(R.string.model_kind_other);

    val label: String get() = appString(titleResource)
}

internal data class Folder(val id: String, val name: String)

internal fun Any?.objects(): List<JSONObject> {
    val array = this as? JSONArray ?: return emptyList()
    return (0 until array.length()).mapNotNull(array::optJSONObject)
}

internal fun Any?.files(): List<CloudFile> = objects().map(CloudFile::from)

internal fun JSONObject.stringOrNull(key: String): String? =
    optString(key, "").takeIf { !isNull(key) && it.isNotBlank() }

internal fun readableBytes(value: Long): String = readableBytes(value.coerceAtLeast(0).toULong())

internal fun readableBytes(value: ULong): String {
    if (value < 1024uL) return "$value B"
    var quantity = value.toDouble()
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
    var unit = 0
    while (quantity >= 1024 && unit < units.lastIndex) {
        quantity /= 1024
        unit++
    }
    val format = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    return "${format.format(quantity)} ${units[unit]}"
}

private val sharedLinkPattern = Regex("""(?i)(?:https?://|magnet:\?)[^\s<>"“”]+""")

internal fun embeddedLinks(text: String): Sequence<String> = sharedLinkPattern.findAll(text)
    .map { match ->
        var link = match.value.trimEnd('。', '，', '；', '）')
        for ((open, close) in listOf('(' to ')', '[' to ']')) {
            val unmatched = link.count { it == close } - link.count { it == open }
            if (unmatched > 0) link = link.dropLast(minOf(unmatched, link.takeLastWhile { it == close }.length))
        }
        link
    }

internal fun linkFromSharedText(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return null
    return embeddedLinks(trimmed).firstOrNull() ?: trimmed.takeIf { !it.any(Char::isWhitespace) }
}

internal fun isOfflineLink(link: String): Boolean {
    val uri = Uri.parse(link)
    return when (uri.scheme?.lowercase()) {
        "http", "https" -> !uri.host.isNullOrBlank()
        "magnet" -> link.substringAfter('?', "").substringBefore('#').split('&').any { parameter ->
            val topic = Uri.decode(parameter.substringAfter('=', ""))
            parameter.substringBefore('=').equals("xt", ignoreCase = true) &&
                topic.startsWith("urn:btih:", ignoreCase = true) && topic.length > "urn:btih:".length
        }
        else -> false
    }
}

private val shareHosts = setOf("mypikpak.com", "www.mypikpak.com", "share.mypikpak.com")

internal fun isPikPakShare(value: String): Boolean {
    val uri = Uri.parse(value)
    return uri.host?.lowercase() in shareHosts && uri.pathSegments.firstOrNull() == "s"
}

internal fun containsPikPakShare(text: String): Boolean = embeddedLinks(text).any(::isPikPakShare)

internal data class ShareInput(val link: String, val accessCode: String?) {
    val isNested: Boolean get() = Uri.parse(link).pathSegments.size > 2
}

private val shareIdPattern = Regex("[A-Za-z0-9_-]+")
private val shareAccessCodePattern = Regex(
    """(?:提取码|访问码|提取碼|存取碼|pass\s*code|access\s+code)\s*[:：=]\s*([A-Za-z0-9_-]+)""",
    RegexOption.IGNORE_CASE,
)

internal fun isValidShareUrl(link: String): Boolean {
    if (link.any { it == '\\' || it.isISOControl() }) return false
    val uri = Uri.parse(link)
    if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return false
    if (uri.userInfo != null || uri.host?.lowercase() !in shareHosts) return false
    val path = uri.encodedPath ?: return false
    return path.startsWith("/s/") && path.substring(3).removeSuffix("/").split('/').all(shareIdPattern::matches)
}

internal fun parseShareInput(text: String): ShareInput? {
    val trimmed = text.trim()
    if (shareIdPattern.matches(trimmed)) return ShareInput(trimmed, null)
    // Keep the original route. The core decodes web folder IDs and verifies their ancestry.
    val link = embeddedLinks(trimmed).firstOrNull(::isValidShareUrl) ?: return null
    val uri = Uri.parse(link)
    val code = uri.getQueryParameter("pass_code")?.takeIf(String::isNotBlank)
        ?: shareAccessCodePattern.find(text)?.groupValues?.get(1)
    return ShareInput(link, code)
}

internal fun httpsUri(value: String?): Uri? {
    if (value.isNullOrBlank()) return null
    return Uri.parse(value).takeIf { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null }
}

internal class LocalTransfers(private val context: Context, private val gateway: Gateway) {
    private val resolver: ContentResolver get() = context.contentResolver
    val durableDownloads = DurableDownloads(context)

    suspend fun sourceThumbnail(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                val bitmap = runCatching {
                    val type = resolver.getType(uri).orEmpty()
                    if (type.startsWith("image/") || type.startsWith("video/") || type == "application/pdf") {
                        resolver.loadThumbnail(uri, Size(192, 192), signal)
                    } else null
                }.getOrNull()
                continuation.resume(bitmap)
            }
        }
    }

    fun upload(uris: List<Uri>, folder: Folder, control: TransferControl, report: (TransferProgress) -> Unit): Int {
        for ((index, uri) in uris.withIndex()) {
            val progress = TransferProgress(completed = index, total = uris.size)
            val name = sendUpload(uri, folder, control, progress, report)
            report(progress.copy(stage = TransferStage.Uploading, currentFile = name, completed = index + 1))
        }
        return uris.size
    }

    fun uploadTree(uri: Uri, target: Folder, control: TransferControl, report: (TransferProgress) -> Unit): Int {
        control.checkpoint()
        val root = DocumentFile.fromTreeUri(context, uri)
            ?.takeIf(DocumentFile::isDirectory) ?: error(appString(R.string.model_open_folder_failed))
        val queue = ArrayDeque(listOf(root to target))
        val seen = mutableSetOf<Uri>()
        var uploaded = 0
        while (queue.isNotEmpty()) {
            control.checkpoint()
            val (source, parent) = queue.removeFirst()
            if (source.isDirectory) {
                check(seen.add(source.uri)) { appString(R.string.model_local_folder_loop) }
                val name = safeFileName(source.name ?: appString(R.string.model_upload_folder))
                val children = listChildren(source)
                report(TransferProgress(TransferStage.Preparing, name, uploaded))
                control.checkpoint()
                val created = control.inSession {
                    gateway.call("mkdir", "parent" to parent.id, "name" to name) as JSONObject
                }
                val destination = Folder(created.getString("id"), name)
                children.asReversed().forEach { queue.addFirst(it to destination) }
            } else if (source.isFile) {
                val name = sendUpload(source.uri, parent, control, TransferProgress(completed = uploaded), report)
                uploaded++
                report(TransferProgress(TransferStage.Uploading, name, uploaded))
            } else {
                error(appString(R.string.model_read_folder_items_failed))
            }
        }
        return uploaded
    }

    private fun listChildren(source: DocumentFile): List<DocumentFile> {
        // DocumentFile.listFiles silently swallows provider query failures.
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            source.uri, DocumentsContract.getDocumentId(source.uri),
        )
        val column = DocumentsContract.Document.COLUMN_DOCUMENT_ID
        val cursor = resolver.query(children, arrayOf(column), null, null, null)
            ?: error(appString(R.string.model_read_folder_failed))
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(source.uri, it.getString(0))
                    val child = DocumentFile.fromSingleUri(context, childUri) ?: error(appString(R.string.model_read_entry_failed))
                    add(child)
                }
            }
        }
    }

    fun hash(uri: Uri): String = withLocalCopy(uri) { staged ->
        gateway.call("hash", "path" to staged.path) as String
    }

    /** The app keeps its own checkpoint and only reopens documents created for this transfer. */
    inner class Download(
        private val files: List<CloudFile>, private val destination: Uri, private val tree: Boolean,
        private val durable: DurableDownload? = null,
    ) {
        val total = files.size.takeIf { files.none(CloudFile::folder) }
        private var queue: ArrayDeque<Pair<CloudFile, DocumentFile?>>? = null
        private val seenFolders = mutableSetOf<String>()
        private var currentTarget = destination.takeUnless { tree }
        private var staging: File? = null
        private var exported = 0

        fun run(control: TransferControl, report: (TransferProgress) -> Unit): Int {
            control.checkpoint()
            if (durable != null) staging = durableDownloads.staging(durable)
            val pending = queue ?: initialize().also { queue = it }
            while (pending.isNotEmpty()) {
                control.checkpoint()
                val (file, parent) = pending.first()
                if (file.folder) {
                    report(TransferProgress(TransferStage.Preparing, file.name, exported, total))
                    check(file.id !in seenFolders) { appString(R.string.model_remote_folder_loop) }
                    val children = control.inSession { gateway.call("files", "parent" to file.id).files() }
                    control.checkpoint()
                    val directory = parent?.createDirectory(safeFileName(file.name))
                        ?: error(appString(R.string.model_create_folder_failed, file.name))
                    seenFolders.add(file.id)
                    pending.removeFirst()
                    children.asReversed().forEach { pending.addFirst(it to directory) }
                } else {
                    val progress = TransferProgress(TransferStage.Downloading, file.name, exported, total,
                        byteTotal = file.bytes.takeIf { it > 0 })
                    report(progress)
                    val directory = staging ?: temporaryDirectory().also { staging = it }
                    directory.mkdirs()
                    val downloaded = File(directory, "data")
                    if (!downloaded.isFile) control.inSession {
                        gateway.transfer("download", control, progress, "id" to file.id, "path" to downloaded.path)
                    }
                    control.checkpoint()
                    val target = currentTarget ?: parent?.createFile("application/octet-stream", safeFileName(file.name))?.uri
                        ?.also { currentTarget = it } ?: error(appString(R.string.model_create_file_failed, file.name))
                    val saving = progress.copy(stage = TransferStage.Saving, byteTotal = downloaded.length())
                    report(saving)
                    control.inSession {
                        if (durable != null) durableDownloads.beginWriting(durable)
                        val output = resolver.openOutputStream(target, "wt") ?: error(appString(R.string.model_write_target_failed))
                        output.use { sink -> downloaded.inputStream().use { copy(it, sink, control, saving, report) } }
                        durable?.let { checkpoint -> runCatching { durableDownloads.finished(checkpoint) } }
                    }
                    currentTarget = null
                    pending.removeFirst()
                    if (durable == null) clearStaging()
                    exported++
                    report(saving.copy(completed = exported, bytes = saving.byteTotal ?: 0))
                }
            }
            return exported
        }

        private fun initialize(): ArrayDeque<Pair<CloudFile, DocumentFile?>> {
            val root = if (tree) DocumentFile.fromTreeUri(context, destination)
                ?.takeIf(DocumentFile::isDirectory) ?: error(appString(R.string.model_open_folder_failed)) else null
            return ArrayDeque(files.map { it to root })
        }

        fun abandon(keep: Boolean) {
            if (durable != null) {
                durableDownloads.release(durable, keep)
                return
            }
            currentTarget?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            currentTarget = null
            clearStaging()
        }

        private fun clearStaging() {
            staging?.deleteRecursively()
            staging = null
        }
    }

    private fun sendUpload(
        uri: Uri, folder: Folder, control: TransferControl, progress: TransferProgress, report: (TransferProgress) -> Unit,
    ): String = withLocalCopy(uri, control, progress, report) { staged ->
        val uploading = progress.copy(stage = TransferStage.Uploading, currentFile = staged.name, byteTotal = staged.length())
        report(uploading)
        control.inSession {
            gateway.transfer("upload", control, uploading, "path" to staged.path, "parent" to folder.id)
        }
        staged.name
    }

    private fun <T> withLocalCopy(
        uri: Uri,
        control: TransferControl = TransferControl(),
        stage: TransferProgress = TransferProgress(),
        report: (TransferProgress) -> Unit = {},
        work: (File) -> T,
    ): T = withTempDir { directory ->
        control.checkpoint()
        val metadata = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    (if (name >= 0) cursor.getString(name) else null) to
                        (if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size).takeIf { it >= 0 } else null)
                }
            }
        val name = metadata?.first ?: uri.lastPathSegment ?: "upload"
        val local = File(directory, safeFileName(name))
        val progress = stage.copy(currentFile = local.name, byteTotal = metadata?.second)
        report(progress)
        val input = resolver.openInputStream(uri) ?: error(appString(R.string.model_read_file_failed))
        input.use { source -> local.outputStream().use { copy(source, it, control, progress, report) } }
        control.checkpoint()
        work(local)
    }

    private fun copy(
        source: InputStream, sink: OutputStream, control: TransferControl, progress: TransferProgress,
        report: (TransferProgress) -> Unit,
    ) {
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            control.checkpoint()
            val count = source.read(buffer)
            if (count < 0) return
            sink.write(buffer, 0, count)
            bytes += count
            report(progress.copy(bytes = bytes))
        }
    }

    private fun temporaryDirectory(): File {
        val root = File(context.cacheDir, "active-transfers")
        synchronized(initializedCaches) {
            // A previous process cannot resume a transfer without its live document grant.
            if (initializedCaches.add(root.path)) root.deleteRecursively()
            check(root.isDirectory || root.mkdirs()) { appString(R.string.model_transfer_cache_failed) }
        }
        return Files.createTempDirectory(root.toPath(), "transfer-").toFile()
    }

    private fun <T> withTempDir(work: (File) -> T): T {
        val directory = temporaryDirectory()
        return try { work(directory) } finally { directory.deleteRecursively() }
    }

    private companion object {
        val initializedCaches = mutableSetOf<String>()
    }
}

internal fun safeFileName(name: String): String {
    val cleaned = name.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
        .joinToString("").trim()
    return cleaned.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: appString(R.string.model_fallback_filename)
}
