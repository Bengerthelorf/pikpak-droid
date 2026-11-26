package com.snaix.pikpak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.SeslProgressBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import javax.net.ssl.HttpsURLConnection

internal data class PreviewFile(val file: CloudFile, val canRefreshLink: Boolean)

internal fun CloudFile.previewDiskKey(kind: String) = "$kind\u0000$id\u0000$modified\u0000$created\u0000$bytes"

internal class CloudPreviews(
    private val fetchImage: (Uri, Int, Int) -> Bitmap? = ::loadPreviewImage,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
    private val disk: PreviewDiskCache? = null,
    private val allowOriginalImage: Boolean = true,
    private val readLinks: suspend (String) -> JSONObject?,
) {
    private val thumbnailSlots = Semaphore(3)
    private val thumbnails = object : LruCache<PreviewFile, CachedThumbnail>(8 * 1024 * 1024) {
        override fun sizeOf(key: PreviewFile, value: CachedThumbnail) = value.bitmap?.allocationByteCount ?: 1024
    }

    private var memoryRevision = 0L
    private var memoryGeneration = disk?.generation ?: 0L

    fun cachedThumbnail(source: PreviewFile): CachedThumbnail? = synchronized(thumbnails) {
        if (disk != null && memoryGeneration != disk.generation) clearMemory()
        thumbnails[source]?.takeIf { it.bitmap != null || elapsedMillis() - it.at < 30_000 }
    }

    suspend fun thumbnail(source: PreviewFile, generation: Long = disk?.generation ?: 0L): Bitmap? {
        if (source.file.folder || source.file.thumbnail == null || (disk != null && generation != disk.generation)) return null
        val revision = synchronized(thumbnails) {
            cachedThumbnail(source)?.let { return it.bitmap }
            memoryRevision
        }
        val owner = disk?.account()
        val key = source.file.previewDiskKey("thumbnail")
        fun remember(bitmap: Bitmap?): Bitmap? = synchronized(thumbnails) {
            if (memoryRevision != revision || (disk != null && disk.generation != generation) ||
                (owner != null && disk.account() != owner)) return@synchronized null
            thumbnails.put(source, CachedThumbnail(bitmap, elapsedMillis()))
            bitmap
        }
        if (disk != null && owner != null) {
            val cached = withContext(Dispatchers.IO) { disk.read(key, owner) }
            if (cached != null) return remember(cached)
        }
        return thumbnailSlots.withPermit {
            if ((disk != null && generation != disk.generation) ||
                synchronized(thumbnails) { memoryRevision != revision }) return@withPermit null
            cachedThumbnail(source)?.let { return@withPermit it.bitmap }
            val bitmap = findImage(source, maxSide = 192, preferOriginal = false)
            if (bitmap != null && disk != null && owner != null) {
                withContext(Dispatchers.IO) { disk.write(key, bitmap, owner, generation) }
            }
            remember(bitmap)
        }
    }

    suspend fun preview(source: PreviewFile, preferOriginal: Boolean = false): Bitmap? {
        if (source.file.folder) return null
        val kind = if (preferOriginal) "image" else "preview"
        val generation = disk?.generation ?: 0L
        return withDisk(source, kind, generation) { findImage(source, maxSide = 1440, preferOriginal = preferOriginal) }
            ?: cachedThumbnail(source)?.bitmap
    }

    fun clearMemory() = synchronized(thumbnails) {
        memoryGeneration = disk?.generation ?: 0L
        memoryRevision++
        thumbnails.evictAll()
    }

    private suspend fun withDisk(
        source: PreviewFile, kind: String, generation: Long, load: suspend () -> Bitmap?,
    ): Bitmap? {
        val cache = disk ?: return load()
        val key = source.file.previewDiskKey(kind)
        val owner = cache.account()
        val cached = owner?.let { withContext(Dispatchers.IO) { cache.read(key, it) } }
        val bitmap = cached ?: load()?.also { image ->
            if (owner != null) withContext(Dispatchers.IO) { cache.write(key, image, owner, generation) }
        }
        return bitmap?.takeIf { cache.generation == generation }
    }

    private suspend fun findImage(source: PreviewFile, maxSide: Int, preferOriginal: Boolean): Bitmap? {
        val file = source.file
        val tried = mutableSetOf<Uri>()
        suspend fun load(uri: Uri?, limit: Int): Bitmap? =
            if (uri == null || !tried.add(uri)) null
            else previewOrNull { runInterruptible(Dispatchers.IO) { fetchImage(uri, limit, maxSide) } }

        // PikPak lists a signed preview URL. Refresh the file metadata once if it expired.
        if (!preferOriginal) load(file.thumbnail, MAX_THUMBNAIL_BYTES)?.let { return it }
        val links = if (source.canRefreshLink) previewOrNull { readLinks(file.id) } else null
        val originalLimit = if (preferOriginal) MAX_ORIGINAL_BYTES else MAX_THUMBNAIL_BYTES
        val reportedSize = links?.optLong("size") ?: 0L
        val original = if (allowOriginalImage && file.category == FileCategory.Image &&
            file.bytes <= originalLimit && reportedSize <= originalLimit &&
            (preferOriginal || file.bytes > 0 || reportedSize > 0)
        ) httpsUri(links?.stringOrNull("download")) else null
        if (preferOriginal) load(original, originalLimit)?.let { return it }
        load(file.thumbnail, MAX_THUMBNAIL_BYTES)?.let { return it }
        load(httpsUri(links?.stringOrNull("thumbnail")), MAX_THUMBNAIL_BYTES)?.let { return it }
        return load(original, originalLimit)
    }

    data class CachedThumbnail(val bitmap: Bitmap?, val at: Long)
}

private const val MAX_THUMBNAIL_BYTES = 16 * 1024 * 1024
private const val MAX_ORIGINAL_BYTES = 24 * 1024 * 1024

private suspend fun <T> previewOrNull(block: suspend () -> T): T? = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}

internal fun loadPreviewImage(uri: Uri, bytesLimit: Int, maxSide: Int): Bitmap? {
    val bytes = readPreviewBytes(uri, bytesLimit) ?: return null
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val longest = maxOf(width, height)
            require(width > 0 && height > 0 && longest <= 8192)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (longest > maxSide) {
                decoder.setTargetSize(maxOf(1, width * maxSide / longest), maxOf(1, height * maxSide / longest))
            }
        }
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    val longest = maxOf(options.outWidth, options.outHeight)
    require(options.outWidth > 0 && options.outHeight > 0 && longest <= 8192)
    var sampleSize = 1
    while (longest / sampleSize > maxSide) sampleSize *= 2
    options.inSampleSize = sampleSize
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun readPreviewBytes(source: Uri, limit: Int): ByteArray? {
    val deadline = SystemClock.elapsedRealtime() + 8_000L
    var uri = URI.create(source.toString())
    repeat(5) {
        if (httpsUri(uri.toString()) == null) return null
        val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtMost(4_000).toInt()
        if (remaining <= 0) return null
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = remaining
            connection.readTimeout = remaining
            val status = connection.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location") ?: return null
                uri = uri.resolve(location)
            } else {
                if (status !in 200..299 || connection.contentLengthLong > limit) return null
                return connection.inputStream.use { stream ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (SystemClock.elapsedRealtime() < deadline && !Thread.currentThread().isInterrupted) {
                        val count = stream.read(buffer, 0, minOf(buffer.size, limit + 1 - bytes.size()))
                        if (count < 0) return@use bytes.toByteArray()
                        if (count + bytes.size() > limit) return@use null
                        bytes.write(buffer, 0, count)
                    }
                    null
                }
            }
        } finally {
            connection.disconnect()
        }
    }
    return null
}

internal class FileThumbnailView(
    context: Context, private val previewSize: Int = 42, private val symbolSize: Int = 25,
) : FrameLayout(context) {
    private val image = AppCompatImageView(context)
    private val indicator = SeslProgressBar(
        context, null, 0, androidx.appcompat.R.style.Widget_AppCompat_ProgressBar_Small,
    ).apply {
        isIndeterminate = true
        contentDescription = appString(R.string.model_thumbnail_loading)
        visibility = GONE
    }
    private var source: PreviewFile? = null
    private var loader: CloudPreviews? = null
    private var scope: CoroutineScope? = null
    private var placeholder: Drawable? = null
    private var readyBitmap: Bitmap? = null
    private var bitmap: Bitmap? = null
    private var job: Job? = null
    private val thumbnailOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, context.dp(8).toFloat())
        }
    }

    init {
        addView(image, LayoutParams(context.dp(symbolSize), context.dp(symbolSize), Gravity.CENTER))
        addView(indicator, LayoutParams(context.dp(22), context.dp(22), Gravity.CENTER))
    }

    fun bind(
        source: PreviewFile?,
        loader: CloudPreviews,
        scope: CoroutineScope,
        placeholder: Drawable?,
        readyBitmap: Bitmap? = null,
    ) {
        if (this.source != source || this.loader !== loader || this.readyBitmap !== readyBitmap) {
            job?.cancel()
            job = null
            bitmap = readyBitmap
            this.source = source
            this.readyBitmap = readyBitmap
        }
        this.loader = loader
        this.scope = scope
        this.placeholder = placeholder
        drawImage(loading = job?.isActive == true)
        loadIfVisible()
    }

    fun recycle() {
        job?.cancel()
        job = null
        readyBitmap = null
        bitmap = null
        source = null
        scope = null
        loader = null
        placeholder = null
        drawImage()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadIfVisible()
    }

    override fun onDetachedFromWindow() {
        job?.cancel()
        job = null
        drawImage()
        super.onDetachedFromWindow()
    }

    private fun loadIfVisible() {
        val request = source ?: return
        val previews = loader ?: return
        val thumbnailScope = scope ?: return
        if (!isAttachedToWindow || bitmap != null || job?.isActive == true) return
        previews.cachedThumbnail(request)?.let { cached ->
            bitmap = cached.bitmap
            drawImage()
            return
        }
        drawImage(loading = true)
        job = thumbnailScope.launch {
            val picture = previews.thumbnail(request)
            if (source == request && loader === previews) {
                bitmap = picture
                drawImage()
            }
        }
    }

    private fun drawImage(loading: Boolean = false) {
        val picture = bitmap
        indicator.showWhen(loading)
        image.showWhen(!loading && (picture != null || placeholder != null))
        if (picture == null) image.setImageDrawable(placeholder) else image.setImageBitmap(picture)
        val size = context.dp(if (picture == null) symbolSize else previewSize)
        val params = image.layoutParams
        if (params.width != size) {
            params.width = size
            params.height = size
            image.layoutParams = params
        }
        image.scaleType = if (picture == null) ImageView.ScaleType.CENTER_INSIDE else ImageView.ScaleType.CENTER_CROP
        image.outlineProvider = if (picture == null) ViewOutlineProvider.BACKGROUND else thumbnailOutline
        image.clipToOutline = picture != null
    }
}
