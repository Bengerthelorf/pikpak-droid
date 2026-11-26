package com.snaix.pikpak

import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SeslProgressBar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun sharedFilePreviewDialog(
    context: AppCompatActivity,
    model: CloudModel,
    share: ShareBrowser,
    file: CloudFile,
    save: () -> Unit,
): AlertDialog {
    val image = ImageView(context).apply {
        adjustViewBounds = true
        maxHeight = context.dp(300)
        scaleType = ImageView.ScaleType.FIT_CENTER
        showWhen(false)
    }
    val progress = SeslProgressBar(context, null, 0, androidx.appcompat.R.style.Widget_AppCompat_ProgressBar_Small).apply {
        isIndeterminate = true
        contentDescription = context.getString(R.string.ui_loading_preview)
        showWhen(file.thumbnail != null || model.supportsShareBrowsing)
    }
    val details = context.label(file.detailText(JSONObject()), 15f).apply { setTextIsSelectable(true) }
    val panel = context.column().apply {
        setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(12))
        addView(image, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = context.dp(12) })
        addView(progress, LinearLayout.LayoutParams(context.dp(28), context.dp(28)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = context.dp(8)
        })
        addView(details)
    }
    val box = AlertDialog.Builder(context)
        .setTitle(file.name)
        .setView(ScrollView(context).apply { addView(panel) })
        .setPositiveButton(R.string.ui_restore_to_drive) { _, _ -> save() }
        .setNegativeButton(R.string.ui_close, null)
        .create()
    var loading: Job? = null
    box.setOnShowListener {
        loading = context.lifecycleScope.launch {
            coroutineScope {
                if (file.thumbnail != null) launch {
                    val bitmap = model.previews.preview(PreviewFile(file, canRefreshLink = false))
                    if (bitmap != null) {
                        image.setImageBitmap(bitmap)
                        image.showWhen(true)
                    }
                }
                if (model.supportsShareBrowsing) launch {
                    val metadata = try {
                        model.readSharedPreview(share, file.id)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                    if (metadata != null) {
                        metadata.stringOrNull("name")?.let(box::setTitle)
                        details.text = file.sharePreviewDetails(metadata)
                    } else {
                        details.append("\n\n" + context.getString(R.string.share_preview_details_unavailable))
                    }
                }
            }
            progress.showWhen(false)
        }
    }
    box.setOnDismissListener { loading?.cancel() }
    return box
}

private fun CloudFile.sharePreviewDetails(metadata: JSONObject): String = buildList {
    add(detailText(metadata))
    metadata.optDouble("duration_seconds").takeIf { it.isFinite() && it >= 0 }?.let {
        add(appString(R.string.share_preview_duration, DateUtils.formatElapsedTime(it.toLong())))
    }
    val qualities = metadata.optJSONArray("streams").objects().mapNotNull { stream ->
        val label = stream.stringOrNull("label")?.replace("Original", appString(R.string.ui_original_quality))
            ?.replace("Unknown", appString(R.string.ui_other_quality)) ?: return@mapNotNull null
        when {
            stream.optBoolean("available") -> label
            stream.stringOrNull("reason")?.contains("quota") == true -> appString(R.string.share_preview_quality_quota, label)
            else -> appString(R.string.share_preview_quality_unavailable, label)
        }
    }.distinct()
    if (qualities.isNotEmpty()) add(appString(R.string.share_preview_qualities, qualities.joinToString("\n")))
}.joinToString("\n\n")
