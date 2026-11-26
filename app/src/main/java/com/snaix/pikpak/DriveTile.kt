package com.snaix.pikpak

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.SeslProgressBar
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class DriveTile(val folder: Boolean, val cover: FolderCoverRequest?, val revision: Int)

internal class DriveTileHolder(
    context: Context,
    private val scope: CoroutineScope,
    private val previews: CloudPreviews,
    covers: FolderCovers,
) : RecyclerView.ViewHolder(context.column().apply {
    layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    setPaddingRelative(context.dp(6), context.dp(4), context.dp(6), context.dp(10))
    gravity = Gravity.CENTER_HORIZONTAL
    selectableBackground()
}) {
    private val container = itemView as LinearLayout
    private val background = context.themeColor(com.google.android.material.R.attr.colorSurfaceContainer, 0x0a888888)
    private val accent = context.themeColor(androidx.appcompat.R.attr.colorPrimary)
    private val art = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            setColor(this@DriveTileHolder.background)
            cornerRadius = context.dp(16).toFloat()
        }
    }
    private val thumbnail = FileThumbnailView(context, previewSize = 72, symbolSize = 47)
    private val folderCover = FolderTileArtwork(context, scope, covers)
    private val more = AppCompatImageButton(context).apply {
        setImageDrawable(context.icon(Symbol.More))
        selectableBackground(true)
    }
    private val check = AppCompatCheckBox(context).apply { isClickable = false; isFocusable = false }
    private val name = context.label(size = 14f).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_HORIZONTAL
        setPaddingRelative(context.dp(2), context.dp(6), context.dp(2), 0)
    }
    private val detail = context.label(size = 12f, secondary = true).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_HORIZONTAL
    }

    init {
        art.addView(thumbnail, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        art.addView(folderCover, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        art.addView(more, FrameLayout.LayoutParams(context.dp(48), context.dp(48), Gravity.TOP or Gravity.END))
        art.addView(check, FrameLayout.LayoutParams(context.dp(44), context.dp(44), Gravity.TOP or Gravity.END))
        container.addView(art, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(100)))
        container.addFullWidth(name)
        container.addFullWidth(detail)
    }

    fun bind(row: WorkspaceRow) {
        val tile = checkNotNull(row.tile)
        val context = itemView.context
        itemView.isSelected = row.selected == true
        itemView.setOnClickListener { row.action?.invoke() }
        val longClick = row.onLongClick?.let { action -> View.OnLongClickListener { action(); true } }
        itemView.setOnLongClickListener(longClick)
        name.text = row.title
        detail.text = row.detail.orEmpty()
        detail.showWhen(!row.detail.isNullOrBlank())
        check.isChecked = row.selected == true
        check.showWhen(row.selected != null)
        more.contentDescription = context.getString(R.string.ui_item_actions, row.title)
        more.setOnClickListener(row.overflow?.let { action -> View.OnClickListener { action() } })
        more.showWhen(row.overflow != null)
        folderCover.showWhen(tile.folder)
        folderCover.bind(tile.cover, tile.revision)
        thumbnail.showWhen(!tile.folder)
        thumbnail.bind(row.thumbnail, previews, scope, row.icon?.let { context.icon(it, accent) })
        val preview = row.previewThumbnail
        thumbnail.contentDescription = preview?.let { context.getString(R.string.ui_preview_item, row.title) }
        thumbnail.setOnClickListener(preview?.let { action -> View.OnClickListener { action() } })
        thumbnail.setOnLongClickListener(if (preview != null) longClick else null)
        thumbnail.isClickable = preview != null
        thumbnail.isFocusable = preview != null
    }

    fun recycle() {
        thumbnail.recycle()
        folderCover.bind(null, 0)
    }
}

private class FolderTileArtwork(
    context: Context,
    private val scope: CoroutineScope,
    private val covers: FolderCovers,
) : FrameLayout(context) {
    private val accent = context.themeColor(androidx.appcompat.R.attr.colorPrimary)
    private val folder = AppCompatImageView(context).apply {
        setImageDrawable(context.icon(Symbol.FolderFilled, accent))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val cover = AppCompatImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        tag = "drive-folder-cover"
    }
    private val flap = View(context).apply {
        background = GradientDrawable().apply {
            setColor(accent)
            cornerRadius = context.dp(8).toFloat()
        }
        visibility = GONE
    }
    private val loading = SeslProgressBar(
        context, null, 0, androidx.appcompat.R.style.Widget_AppCompat_ProgressBar_Small,
    ).apply {
        isIndeterminate = true
        contentDescription = context.getString(R.string.ui_loading_folder_cover)
        visibility = GONE
    }
    private var request: FolderCoverRequest? = null
    private var revision = 0
    private var job: Job? = null
    private var resolved = false

    init {
        addView(folder, LayoutParams(context.dp(95), context.dp(90), Gravity.CENTER))
        addView(cover, LayoutParams(context.dp(110), context.dp(70), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = context.dp(5)
        })
        addView(flap, LayoutParams(context.dp(74), context.dp(31), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = context.dp(13)
        })
        addView(loading, LayoutParams(context.dp(20), context.dp(20), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = context.dp(7)
        })
    }

    fun bind(value: FolderCoverRequest?, version: Int) {
        if (value?.key != request?.key || version != revision) {
            job?.cancel()
            job = null
            request = value
            revision = version
            resolved = false
            loading.showWhen(false)
            showCover(null)
        }
        loadIfVisible()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadIfVisible()
    }

    override fun onDetachedFromWindow() {
        job?.cancel()
        job = null
        resolved = cover.drawable != null
        loading.showWhen(false)
        super.onDetachedFromWindow()
    }

    private fun loadIfVisible() {
        val target = request ?: return
        if (!isAttachedToWindow || job?.isActive == true || resolved) return
        val version = revision
        loading.showWhen(true)
        job = scope.launch {
            val image = covers.load(target)
            if (request == target && revision == version) {
                resolved = true
                loading.showWhen(false)
                showCover(image)
            }
        }
    }

    private fun showCover(image: android.graphics.Bitmap?) {
        if (image == null) cover.setImageDrawable(null) else cover.setImageBitmap(image)
        cover.showWhen(image != null)
        flap.showWhen(image != null)
        folder.alpha = if (image == null) 1f else 0.72f
    }
}
