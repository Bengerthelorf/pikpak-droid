package com.snaix.pikpak

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.R as Sesl
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SeslProgressBar
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun showCloudSubtitlePicker(
    activity: AppCompatActivity,
    initialPath: List<Folder>,
    readFolder: suspend (String) -> List<CloudFile>,
    readCachedFolder: suspend (String) -> List<CloudFile>? = { null },
    isCurrentAccount: () -> Boolean = { true },
    onSelected: (CloudFile) -> Unit,
): AlertDialog {
    val builder = AlertDialog.Builder(activity)
    val context = builder.context
    val path = mutableListOf(Folder("", context.getString(R.string.ui_cloud_drive)))
    path.addAll(initialPath.filter { it.id.isNotEmpty() }.distinctBy(Folder::id))
    val breadcrumb = context.label(size = 14f, secondary = true).apply {
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.START
    }
    val spinner = SeslProgressBar(context, null, 0, Sesl.style.Widget_AppCompat_ProgressBar_Small).apply {
        isIndeterminate = true
    }
    val message = context.label(size = 14f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    val status = context.row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(12), 0, 0)
        addView(spinner, LinearLayout.LayoutParams(context.dp(22), context.dp(22)).apply { marginEnd = context.dp(8) })
        addView(message)
    }
    val header = context.column().apply {
        setPadding(context.dp(24), context.dp(4), context.dp(24), context.dp(10))
        addFullWidth(breadcrumb)
        addFullWidth(status)
    }
    val rows = SubtitleRows(context)
    val box = builder.setTitle(R.string.ui_cloud_subtitles)
        .setAdapter(rows, null)
        .setNegativeButton(R.string.ui_cancel, null)
        .setNeutralButton(R.string.ui_up_one_level, null)
        .setPositiveButton(R.string.ui_retry, null)
        .create()
    var load: Job? = null
    var visibleFolder: String? = null
    fun accountStillActive(): Boolean {
        if (isCurrentAccount()) return true
        box.dismiss()
        return false
    }
    fun openFolder() {
        load?.cancel()
        if (!accountStillActive()) return
        val id = path.last().id
        breadcrumb.text = path.joinToString(" / ") { it.name }
        if (id != visibleFolder) {
            rows.replace(emptyList())
            box.listView.setSelection(0)
            visibleFolder = id
        }
        box.getButton(AlertDialog.BUTTON_NEUTRAL).showWhen(path.size > 1)
        box.getButton(AlertDialog.BUTTON_POSITIVE).showWhen(false)
        message.setText(R.string.ui_loading_folder)
        spinner.showWhen(true)
        status.showWhen(true)
        load = activity.lifecycleScope.launch {
            val diskRead = launch {
                val saved = try {
                    withContext(Dispatchers.IO) { readCachedFolder(id)?.subtitleEntries() }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                }
                if (saved != null && accountStillActive()) rows.replace(saved)
            }
            try {
                val entries = withContext(Dispatchers.IO) { readFolder(id).subtitleEntries() }
                diskRead.cancel()
                if (!accountStillActive()) return@launch
                rows.replace(entries)
                message.setText(R.string.subtitle_picker_empty)
                status.showWhen(entries.isEmpty())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (!accountStillActive()) return@launch
                message.text = error.localizedMessage ?: context.getString(R.string.ui_load_failed)
                box.getButton(AlertDialog.BUTTON_POSITIVE).showWhen(true)
            } finally {
                if (isActive) spinner.showWhen(false)
            }
        }
    }
    fun up() {
        if (path.size == 1) box.cancel() else {
            path.removeAt(path.lastIndex)
            openFolder()
        }
    }
    val destruction = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) { accountStillActive() }
        override fun onDestroy(owner: LifecycleOwner) = box.dismiss()
    }
    box.setOnDismissListener {
        load?.cancel()
        activity.lifecycle.removeObserver(destruction)
    }
    box.show()
    activity.lifecycle.addObserver(destruction)
    box.onBackPressedDispatcher.addCallback(box, object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = up()
    })
    box.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { up() }
    box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { openFolder() }
    box.listView.apply {
        addHeaderView(header, null, false)
        setOnItemClickListener { parent, _, position, _ ->
            if (!accountStillActive()) return@setOnItemClickListener
            val file = parent.getItemAtPosition(position) as? CloudFile ?: return@setOnItemClickListener
            if (file.folder) {
                path.add(Folder(file.id, file.name))
                openFolder()
            } else {
                box.dismiss()
                onSelected(file)
            }
        }
    }
    openFolder()
    return box
}

private fun List<CloudFile>.subtitleEntries() = filter { it.folder || subtitleMime(it.name) != null }
    .sortedWith(compareByDescending<CloudFile> { it.folder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

private class SubtitleRows(private val context: Context) : BaseAdapter() {
    private var files: List<CloudFile> = emptyList()

    fun replace(items: List<CloudFile>) {
        files = items
        notifyDataSetChanged()
    }

    override fun getCount() = files.size
    override fun getItem(position: Int) = files[position]
    override fun getItemId(position: Int) = position.toLong()

    @SuppressLint("PrivateResource") // Share the SESL dialog row used by the other file actions.
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView as? TextView ?: LayoutInflater.from(context)
            .inflate(Sesl.layout.sesl_select_dialog_item, parent, false) as TextView
        val file = files[position]
        row.text = file.name
        row.setSingleLine(false)
        row.maxLines = 2
        row.ellipsize = TextUtils.TruncateAt.END
        val icon = context.icon(if (file.folder) Symbol.Folder else Symbol.Text, row.currentTextColor)
        icon?.setBounds(0, 0, context.dp(22), context.dp(22))
        row.compoundDrawablePadding = context.dp(12)
        row.setCompoundDrawablesRelative(icon, null, null, null)
        return row
    }
}
