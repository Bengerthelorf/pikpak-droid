package com.snaix.pikpak

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.SeslProgressBar
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class Choice(
    val title: String,
    val icon: Symbol,
    val detail: String? = null,
    val dangerous: Boolean = false,
    val opensSubmenu: Boolean = false,
    val selected: Boolean? = null,
    val separated: Boolean = false,
    val action: () -> Unit,
)

internal class CloudActions(private val context: AppCompatActivity, private val model: CloudModel) {
    private var dialog: android.app.Dialog? = null
    var chooseUpload: (Folder) -> Unit = {}
    var chooseUploadTree: (Folder) -> Unit = {}
    var chooseSave: (CloudFile) -> Unit = {}
    var chooseTree: (List<CloudFile>) -> Unit = {}
    var chooseHash: () -> Unit = {}
    var chooseLocalVideo: () -> Unit = {}

    private fun show(next: android.app.Dialog) {
        dismissDialogs()
        dialog = next
        next.show()
    }

    fun dismissDialogs() {
        dismissActionSheet()
        dialog?.dismiss()
        dialog = null
    }

    fun choices(title: String, items: List<Choice>, allowWhileBusy: Boolean = false) {
        val manager = context.supportFragmentManager
        if ((model.busy != null && !allowWhileBusy) || items.isEmpty() || manager.isStateSaved) return
        dismissDialogs()
        CloudActionSheet().apply {
            heading = title
            actions = items
        }.showNow(manager, CloudActionSheet.TAG)
    }

    private fun dismissActionSheet() {
        val manager = context.supportFragmentManager
        val sheet = manager.findFragmentByTag(CloudActionSheet.TAG) as? CloudActionSheet ?: return
        if (manager.isStateSaved) sheet.dismissAllowingStateLoss() else sheet.dismissNow()
    }

    fun result(
        title: String, body: String, copyLabel: String? = null,
        updates: Flow<String>? = null, onCopy: () -> Unit = {},
    ) {
        val text = context.label(body, 15f).apply {
            setTextIsSelectable(true)
            setPadding(context.dp(24), context.dp(9), context.dp(24), context.dp(12))
        }
        val scroll = ScrollView(context).apply { addView(text) }
        val builder = AlertDialog.Builder(context).setTitle(title).setView(scroll).setPositiveButton(context.getString(R.string.ui_done), null)
        if (copyLabel != null) builder.setNeutralButton(copyLabel) { _, _ -> onCopy() }
        val box = builder.create()
        show(box)
        if (updates != null) {
            val job = context.lifecycleScope.launch { updates.collect { text.text = it } }
            box.setOnDismissListener { job.cancel() }
        }
    }

    fun confirm(title: String, body: String, button: String, dangerous: Boolean = false, perform: () -> Unit) {
        if (model.busy != null) return
        val box = AlertDialog.Builder(context).setTitle(title).setMessage(body)
            .setPositiveButton(button) { _, _ -> perform() }
            .setNegativeButton(context.getString(R.string.ui_cancel), null).create()
        show(box)
        if (dangerous) box.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(context.themeColor(com.google.android.material.R.attr.colorError))
    }

    fun refresh() = model.refreshFromUi()

    fun create() {
        val folder = model.folder
        choices(context.getString(R.string.ui_add_to, folder.name), listOf(Choice(context.getString(R.string.ui_new_folder), Symbol.Folder) { mkdir() }) +
            uploadChoices(folder) + listOf(
                Choice(context.getString(R.string.ui_new_cloud_download), Symbol.Download, context.getString(R.string.ui_cloud_download_description)) { addTask(destination = folder) },
                Choice(context.getString(R.string.ui_open_share), Symbol.Link) { openShare() },
            ))
    }

    fun newUpload() {
        if (model.busy != null) return
        pickFolder(context.getString(R.string.ui_upload_to_picker), context.getString(R.string.ui_select)) { folder ->
            choices(context.getString(R.string.ui_upload_to, folder.name), uploadChoices(folder))
        }
    }

    private fun uploadChoices(folder: Folder) = listOf(
        Choice(context.getString(R.string.ui_upload_files), Symbol.Upload, context.getString(R.string.ui_select_device_files)) {
            chooseUpload(folder)
        },
        Choice(context.getString(R.string.ui_upload_folder), Symbol.Folder, context.getString(R.string.ui_preserve_folder_structure)) {
            chooseUploadTree(folder)
        },
    )

    fun viewOptions() {
        val current = model.listingPresentation
        choices(context.getString(R.string.ui_view_options), buildList {
            if (model.page == Page.Files) {
                add(Choice(context.getString(R.string.ui_view), Symbol.ViewOptions, current.view.label, opensSubmenu = true) {
                    choices(context.getString(R.string.ui_view), DriveView.entries.map { mode ->
                        val icon = if (mode == DriveView.Icons) Symbol.GridView else Symbol.ListView
                        Choice(mode.label, icon, selected = mode == current.view) {
                            model.applyPresentation(current.copy(view = mode))
                        }
                    })
                })
                add(Choice(context.getString(R.string.ui_group_by), Symbol.GroupBy, current.grouping.label, opensSubmenu = true) {
                    val available = DriveGrouping.entries.filter { model.hasListingFolderStats || it != DriveGrouping.ContentModified }
                    choices(context.getString(R.string.ui_group_by), available.map { group ->
                        val icon = when (group) {
                            DriveGrouping.None -> Symbol.ListView
                            DriveGrouping.Kind -> Symbol.FileKind
                            DriveGrouping.Created -> Symbol.DateCreated
                            DriveGrouping.Modified -> Symbol.DateModified
                            DriveGrouping.ContentModified -> Symbol.Clock
                            DriveGrouping.Size -> Symbol.FileSize
                        }
                        Choice(group.label, icon, selected = group == current.grouping) {
                            model.applyPresentation(current.copy(grouping = group))
                        }
                    })
                })
            }
            add(Choice(context.getString(R.string.ui_sort_by), Symbol.Sort, current.sort.label, opensSubmenu = true) {
                val available = FileSort.entries.filter { model.hasListingFolderStats || it != FileSort.ContentModified }
                choices(context.getString(R.string.ui_sort_by), available.map { sort ->
                    val icon = when (sort) {
                        FileSort.Name -> Symbol.FileName
                        FileSort.Created -> Symbol.DateCreated
                        FileSort.Recent -> Symbol.DateModified
                        FileSort.ContentModified -> Symbol.Clock
                        FileSort.Kind -> Symbol.FileKind
                        FileSort.Size -> Symbol.FileSize
                    }
                    Choice(sort.label, icon, selected = sort == current.sort) {
                        model.applyPresentation(current.copy(sort = sort,
                            descending = if (sort == current.sort) current.descending else sort.defaultDescending))
                    }
                })
            })
            val directionIcon = if (current.descending) Symbol.SortDown else Symbol.SortUp
            val direction = if (current.descending) context.getString(R.string.ui_descending) else context.getString(R.string.ui_ascending)
            add(Choice(context.getString(R.string.ui_sort_order), directionIcon, direction, separated = true) {
                model.applyPresentation(current.copy(descending = !current.descending))
            })
        })
    }

    fun theme() {
        if (model.busy != null) return
        val modes = ThemeMode.entries
        show(AlertDialog.Builder(context).setTitle(context.getString(R.string.ui_appearance))
            .setSingleChoiceItems(modes.map(ThemeMode::label).toTypedArray(), model.theme.ordinal) { dialog, index ->
                dialog.dismiss()
                model.applyTheme(modes[index])
            }
            .setNegativeButton(context.getString(R.string.ui_cancel), null)
            .create())
    }

    fun fileMenu(file: CloudFile) = fileMenu(listOf(file))

    fun selectionMenu() = fileMenu(model.chosen)

    private fun fileMenu(files: List<CloudFile>) {
        if (files.isEmpty()) return
        val title = files.singleOrNull()?.name ?: context.getString(R.string.ui_selected_count, files.size)
        val ids = files.map(CloudFile::id)
        val options = when (model.page) {
            Page.Trash -> listOf(
                Choice(context.getString(R.string.ui_restore), Symbol.Refresh) {
                    model.command(context.getString(R.string.ui_restoring), "restore", "ids" to ids, done = context.getString(R.string.ui_restored))
                },
                Choice(context.getString(R.string.ui_permanent_delete), Symbol.Trash, dangerous = true) {
                    confirm(context.getString(R.string.ui_permanent_delete), context.getString(R.string.ui_permanent_delete_confirm, files.size), context.getString(R.string.ui_permanent_delete), true) {
                        model.command(context.getString(R.string.ui_permanent_deleting), "destroy", "ids" to ids, done = context.getString(R.string.ui_deleted))
                    }
                },
            )
            Page.SharedFiles -> buildList {
                files.singleOrNull()?.takeUnless { it.folder }?.let { file ->
                    add(Choice(context.getString(R.string.ui_preview), Symbol.Image) { sharedPreview(file) })
                }
                add(Choice(context.getString(R.string.ui_restore_to_drive), Symbol.Download) { saveShare(files) })
                add(Choice(context.getString(R.string.ui_select), Symbol.Check) { files.forEach(model::select) })
            }
            else -> buildList {
                val single = files.singleOrNull()
                if (single != null) {
                    if (!single.folder) add(Choice(context.getString(R.string.ui_preview), Symbol.Image) { quickPreview(single) })
                    if (single.folder && model.page == Page.Files) add(Choice(context.getString(R.string.ui_choose_folder_cover), Symbol.Image) {
                        chooseFolderCover(single)
                    })
                    add(Choice(context.getString(R.string.ui_details), Symbol.Info) { detail(single) })
                    if (single.category in setOf(FileCategory.Video, FileCategory.Audio)) {
                        add(Choice(context.getString(R.string.ui_external_player), Symbol.External) { externalPlay(single) })
                    }
                    if (!single.folder) add(Choice(context.getString(R.string.ui_copy_download_link), Symbol.Link) {
                        withLinks(single) { response ->
                            val uri = httpsUri(response.stringOrNull("download"))
                            if (uri == null) model.notice = context.getString(R.string.ui_no_download_link)
                            else copy(uri.toString(), context.getString(R.string.ui_download_link), sensitive = true)
                        }
                    })
                    add(Choice(context.getString(R.string.ui_rename), Symbol.Rename) { rename(single) })
                }
                add(Choice(context.getString(R.string.ui_download_to_device), Symbol.Download) { download(files) })
                add(Choice(context.getString(R.string.ui_move_to), Symbol.Move) { relocate(files, "move", context.getString(R.string.ui_move)) })
                add(Choice(context.getString(R.string.ui_copy_to), Symbol.Copy) { relocate(files, "copy", context.getString(R.string.ui_copy)) })
                if (files.any { !it.starred }) add(Choice(context.getString(R.string.ui_add_favorite), Symbol.Star) {
                    model.command(context.getString(R.string.ui_favoriting), "star", "ids" to files.filterNot(CloudFile::starred).map(CloudFile::id), done = context.getString(R.string.ui_favorited))
                })
                if (files.any(CloudFile::starred)) add(Choice(context.getString(R.string.ui_unfavorite), Symbol.Star) {
                    model.command(context.getString(R.string.ui_unfavoriting), "unstar", "ids" to files.filter(CloudFile::starred).map(CloudFile::id), done = context.getString(R.string.ui_unfavorited))
                })
                add(Choice(context.getString(R.string.ui_create_share), Symbol.Share) { createShare(files) })
                if (model.selected.isEmpty()) add(Choice(context.getString(R.string.ui_select), Symbol.Check) { files.forEach(model::select) })
                add(Choice(context.getString(R.string.ui_move_to_trash), Symbol.Trash, dangerous = true) {
                    confirm(context.getString(R.string.ui_move_to_trash), context.getString(R.string.ui_move_to_trash_confirm, files.size), context.getString(R.string.ui_move_to_trash)) {
                        model.command(context.getString(R.string.ui_moving_to_trash), "remove", "ids" to ids, done = context.getString(R.string.ui_moved_to_trash))
                    }
                })
            }
        }
        choices(title, options)
    }

    private fun chooseFolderCover(folder: CloudFile) {
        val selected = model.folderCovers.selection(folder).toMutableList()
        val status = context.label(context.getString(R.string.ui_loading_folder), 14f)
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
        }
        val adapter = WorkspaceAdapter(context.lifecycleScope, model.previews)
        list.adapter = adapter
        val panel = formPanel(listOf(status))
        panel.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(272), 1f))
        val box = AlertDialog.Builder(context).setTitle(folder.name)
            .setView(panel)
            .setPositiveButton(context.getString(R.string.ui_save_cover)) { _, _ -> model.applyFolderCover(folder, selected.toList()) }
            .setNeutralButton(context.getString(R.string.ui_choose_automatically)) { _, _ -> model.applyFolderCover(folder, emptyList()) }
            .setNegativeButton(context.getString(R.string.ui_cancel), null).create()
        show(box)
        box.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        val job = context.lifecycleScope.launch {
            try {
                val candidates = model.folderCovers.candidates(folder)
                selected.retainAll(candidates.map(CloudFile::id).toSet())
                fun display() {
                    status.text = if (candidates.isEmpty()) context.getString(R.string.ui_no_cover_candidates)
                        else context.getString(R.string.ui_cover_selection_count, FolderCovers.LIMIT, selected.size)
                    adapter.submit(candidates.map { file ->
                        WorkspaceRow(
                            file.name,
                            detail = if (file.folder) context.getString(R.string.ui_folder) else null,
                            icon = if (file.folder) Symbol.Folder else Symbol.Image,
                            selected = file.id in selected,
                            thumbnail = if (file.folder) null else PreviewFile(file, canRefreshLink = true),
                        ) {
                            if (file.id in selected) {
                                selected.remove(file.id)
                                display()
                            } else if (selected.size < FolderCovers.LIMIT) {
                                selected.add(file.id)
                                display()
                            }
                        }
                    })
                    box.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = selected.isNotEmpty()
                }
                display()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status.text = error.message ?: context.getString(R.string.ui_cover_load_failed)
            }
        }
        box.setOnDismissListener {
            job.cancel()
            list.adapter = null
        }
    }

    fun openFile(file: CloudFile) {
        if (model.selected.isNotEmpty()) { model.select(file); return }
        if (model.page == Page.Trash) { fileMenu(file); return }
        if (file.folder) { model.openFolder(file); return }
        if (model.page == Page.SharedFiles) { sharedPreview(file); return }
        openDriveMedia(file)
    }

    fun openDrawerFavorite(file: CloudFile) {
        if (file.folder) model.openDrawerFavoriteFolder(file)
        else openDriveMedia(file, useFolderCaptions = false)
    }

    private fun openDriveMedia(file: CloudFile, useFolderCaptions: Boolean = true) {
        when (file.category) {
            FileCategory.Video, FileCategory.Audio -> play(file, useFolderCaptions)
            FileCategory.Image -> imagePreview(PreviewFile(file, canRefreshLink = true), preferOriginal = true)
            FileCategory.Text -> textPreview(file)
            else -> detail(file)
        }
    }

    fun quickPreview(file: CloudFile) {
        if (file.folder || model.busy != null) return
        if (model.page == Page.SharedFiles) { sharedPreview(file); return }
        val canRefreshLink = model.page != Page.Trash
        when {
            canRefreshLink && file.category == FileCategory.Text -> textPreview(file)
            file.thumbnail != null || file.category == FileCategory.Image || file.category == FileCategory.Video ->
                imagePreview(PreviewFile(file, canRefreshLink))
            canRefreshLink -> detail(file)
        }
    }

    private fun sharedPreview(file: CloudFile) {
        val share = model.shareBrowser ?: return
        show(sharedFilePreviewDialog(context, model, share, file) {
            if (model.shareBrowser == share && model.signedIn) saveShare(listOf(file))
        })
    }

    private fun textPreview(file: CloudFile) {
        model.command(context.getString(R.string.ui_loading_preview), "text", "id" to file.id, refresh = false) {
            val value = it as JSONObject
            val text = value.optString("text")
            result(file.name, if (value.optBoolean("truncated")) context.getString(R.string.ui_text_truncation, text)
                else text.ifEmpty { context.getString(R.string.ui_empty_file) })
        }
    }

    private fun detail(file: CloudFile) {
        val origin = model.location
        model.command(context.getString(R.string.ui_loading_details), "info", "id" to file.id, refresh = false) { response ->
            if (model.location != origin || !model.signedIn) return@command
            val value = response as JSONObject
            val latest = { model.entries.firstOrNull { it.id == file.id } ?: file }
            val updates = if (file.folder) model.observe { latest().detailText(value) } else null
            result(file.name, latest().detailText(value), updates = updates)
        }
    }

    private fun withLinks(file: CloudFile, complete: (JSONObject) -> Unit) =
        model.command(context.getString(R.string.ui_loading_link), "links", "id" to file.id, refresh = false) {
            complete(it as JSONObject)
        }

    private fun play(file: CloudFile, useFolderCaptions: Boolean = true) {
        val origin = model.location
        val directory = when {
            !useFolderCaptions || origin.page != Page.Files -> null
            origin.search == null -> origin
            origin.search.scope == DriveSearchScope.CurrentFolder -> origin.parent
            else -> null
        }
        val folderPath = generateSequence(directory) { it.parent }
            .takeWhile { it.page == Page.Files && it.search == null }
            .mapNotNull { it.folder }
            .toList().asReversed()
        val captionStem = file.name.substringBeforeLast('.')
        val captions = if (directory != null && file.category == FileCategory.Video) {
            model.entries.filter { candidate ->
                !candidate.folder && subtitleMime(candidate.name) != null &&
                    candidate.name.startsWith("$captionStem.", ignoreCase = true)
            }
        } else emptyList()
        withLinks(file) { response ->
            if (PlaybackSource.from(response).isNotEmpty()) {
                context.startActivity(PlaybackActivity.intent(context, file, response, captions, folderPath))
            } else {
                val media = response.optJSONArray("play").objects()
                model.notice = if (media.any { it.optString("reason").contains("quota") }) {
                    context.getString(R.string.ui_play_quota_required)
                } else context.getString(R.string.ui_no_play_link)
            }
        }
    }

    private fun externalPlay(file: CloudFile) = withLinks(file) { response ->
        val best = PlaybackSource.from(response).firstOrNull()
        if (best != null) openExternal(best.uri, mediaMime(file.category == FileCategory.Audio, response))
        else model.notice = context.getString(R.string.ui_no_play_link)
    }

    private fun download(files: List<CloudFile>) {
        val single = files.singleOrNull()
        if (single != null && !single.folder) chooseSave(single) else chooseTree(files)
    }

    private fun mkdir() {
        val parent = model.folder
        input(context.getString(R.string.ui_new_folder), context.getString(R.string.ui_folder_name), "", context.getString(R.string.ui_create_folder)) { name ->
            model.command(context.getString(R.string.ui_creating_folder), "mkdir", "parent" to parent.id, "name" to name, done = context.getString(R.string.ui_folder_created))
        }
    }

    private fun rename(file: CloudFile) = input(context.getString(R.string.ui_rename), context.getString(R.string.ui_name), file.name, context.getString(R.string.ui_save)) { name ->
        if (name != file.name) model.command(context.getString(R.string.ui_renaming), "rename", "id" to file.id, "name" to name, done = context.getString(R.string.ui_name_updated))
    }

    private fun input(title: String, label: String, initial: String, confirm: String, perform: (String) -> Unit) {
        val field = textField(label, initial)
        editDialog(title, listOf(field.layout), confirm, listOf(field),
            valid = { field.value.isNotBlank() && field.value.trim() !in listOf(".", "..") &&
                field.value.none { it == '/' || it == '\\' || it.isISOControl() } },
        ) { perform(field.value.trim()) }
    }

    private fun relocate(files: List<CloudFile>, operation: String, verb: String) {
        pickFolder(context.getString(R.string.ui_relocate_to, verb), verb, files.filter(CloudFile::folder).map(CloudFile::id).toSet()) { target ->
            model.command(context.getString(R.string.ui_relocating, verb), operation, "ids" to files.map(CloudFile::id), "parent" to target.id,
                done = context.getString(R.string.ui_relocated_to, verb, target.name))
        }
    }

    private fun pickFolder(title: String, verb: String, excluded: Set<String> = emptySet(), perform: (Folder) -> Unit) {
        val path = mutableListOf(Folder("", context.getString(R.string.ui_cloud_drive)))
        val breadcrumb = context.label().apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.START
        }
        val up = context.button(context.getString(R.string.ui_up_one_level)) {}
        val status = context.label("", 14f)
        val list = ListView(context)
        val panel = formPanel(listOf(breadcrumb, up, status))
        panel.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(245), 1f))
        val box = AlertDialog.Builder(context).setTitle(title).setView(panel)
            .setPositiveButton(context.getString(R.string.ui_use_current_folder, verb)) { _, _ -> perform(path.last()) }
            .setNegativeButton(context.getString(R.string.ui_cancel), null).create()
        var job: Job? = null
        fun load() {
            job?.cancel()
            val folder = path.last()
            breadcrumb.text = path.joinToString(" / ") { it.name }
            up.showWhen(path.size > 1)
            status.text = context.getString(R.string.ui_loading)
            status.showWhen(true)
            list.adapter = null
            box.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            job = model.folders(folder.id) { files, failure ->
                val dirs = files.filterNot { it.id in excluded }
                status.text = failure ?: if (dirs.isEmpty()) context.getString(R.string.ui_no_child_folders) else ""
                status.showWhen(status.text.isNotEmpty())
                list.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, dirs.map(CloudFile::name))
                list.setOnItemClickListener { _, _, index, _ ->
                    val target = dirs[index]
                    path.add(Folder(target.id, target.name))
                    load()
                }
                box.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = failure == null
            }
        }
        up.setOnClickListener { path.removeAt(path.lastIndex); load() }
        box.setOnDismissListener { job?.cancel() }
        show(box)
        load()
    }

    fun addTask(initial: String = "", destination: Folder? = null): AlertDialog? {
        if (model.busy != null) return null
        val link = textField(context.getString(R.string.ui_magnet_or_url), initial, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val name = textField(context.getString(R.string.ui_optional_name))
        val target = context.label(context.getString(R.string.ui_save_to, destination?.name ?: context.getString(R.string.ui_default_download_folder)), 14f)
        link.edit.id = R.id.incoming_link
        name.edit.id = R.id.incoming_description
        return editDialog(context.getString(R.string.ui_new_cloud_download), listOf(link.layout, name.layout, target), context.getString(R.string.ui_start_download), listOf(link),
            valid = { linkFromSharedText(link.value)?.let(::isOfflineLink) == true }) {
            val url = linkFromSharedText(link.value) ?: return@editDialog
            model.command(
                context.getString(R.string.ui_creating_cloud_download), "offline", "url" to url, "name" to name.value.trim(),
                "parent" to destination?.id, done = context.getString(R.string.ui_cloud_download_created), refresh = false,
            ) { model.openCloudTasks() }
        }
    }

    fun taskMenu(task: JSONObject) {
        val id = task.optString("id")
        if (id.isBlank()) return
        choices(task.optString("name").ifBlank { context.getString(R.string.ui_cloud_download_task) }, buildList {
            val fileId = task.stringOrNull("file_id")
            if (task.optString("phase") == "PHASE_TYPE_COMPLETE" && fileId != null) {
                add(Choice(context.getString(R.string.ui_open_cloud_result), Symbol.Folder) {
                    model.command(context.getString(R.string.ui_opening_cloud_result), "info", "id" to fileId, refresh = false) { response ->
                        val file = CloudFile.from((response as JSONObject).put("id", fileId))
                        // Preview and playback can start a second cloud operation after this one finishes.
                        context.window.decorView.post {
                            if (!context.isFinishing && !context.isDestroyed && model.page == Page.Tasks) openFile(file)
                        }
                    }
                })
            }
            if (task.optString("phase") in setOf("PHASE_TYPE_ERROR", "PHASE_TYPE_PAUSED")) {
                add(Choice(context.getString(R.string.ui_retry), Symbol.Refresh) { model.command(context.getString(R.string.ui_retrying), "retry_task", "id" to id, done = context.getString(R.string.ui_retry_requested)) })
            }
            add(Choice(context.getString(R.string.ui_delete_task_only), Symbol.Trash) {
                confirm(context.getString(R.string.ui_delete_task), context.getString(R.string.ui_delete_task_confirm), context.getString(R.string.ui_delete)) {
                    model.command(context.getString(R.string.ui_deleting_task), "delete_tasks", "ids" to listOf(id), "delete_files" to false, done = context.getString(R.string.ui_task_deleted))
                }
            })
            add(Choice(context.getString(R.string.ui_delete_task_and_files), Symbol.Trash, dangerous = true) {
                confirm(context.getString(R.string.ui_delete_task_and_files), context.getString(R.string.ui_delete_task_and_files_confirm), context.getString(R.string.ui_delete), true) {
                    model.command(context.getString(R.string.ui_deleting_task), "delete_tasks", "ids" to listOf(id), "delete_files" to true, done = context.getString(R.string.ui_task_and_files_deleted))
                }
            })
        })
    }

    fun emptyTrash() = confirm(context.getString(R.string.ui_empty_trash), context.getString(R.string.ui_empty_trash_confirm), context.getString(R.string.ui_clear), true) {
        model.command(context.getString(R.string.ui_emptying_trash), "empty_trash", done = context.getString(R.string.ui_trash_emptied))
    }

    fun openShare(initial: String = ""): AlertDialog? {
        if (model.busy != null) return null
        val parsed = parseShareInput(initial)
        val share = textField(context.getString(R.string.ui_share_link_or_id), parsed?.link ?: initial)
        val access = textField(context.getString(R.string.ui_optional_access_code), parsed?.accessCode.orEmpty())
        share.edit.id = R.id.incoming_link
        access.edit.id = R.id.incoming_description
        return editDialog(context.getString(R.string.ui_open_share), listOf(share.layout, access.layout), context.getString(R.string.ui_browse_shared_files), listOf(share),
            valid = {
                val input = parseShareInput(share.value)
                share.layout.error = when {
                    input == null && share.value.isNotBlank() -> context.getString(R.string.ui_share_link_invalid)
                    input?.isNested == true && !model.supportsShareBrowsing -> context.getString(R.string.ui_share_folder_needs_core)
                    else -> null
                }
                input != null && share.layout.error == null
            }) {
            val input = parseShareInput(share.value) ?: return@editDialog
            model.openShare(input.link, access.value.trim().ifEmpty { input.accessCode.orEmpty() })
        }
    }

    fun saveShare(files: List<CloudFile>) {
        val share = model.shareBrowser ?: return
        if (files.isEmpty()) return
        pickFolder(context.getString(R.string.ui_save_shared_count_to, files.size), context.getString(R.string.ui_save_shared)) { destination ->
            model.command(
                context.getString(R.string.ui_saving_share), "share_save", "share" to share.id, "token" to share.token,
                "ids" to files.map(CloudFile::id), "parent" to destination.id,
                done = context.getString(R.string.ui_share_saved_to, destination.name), refresh = false,
            ) { model.clearSelection() }
        }
    }

    private fun createShare(files: List<CloudFile>) {
        val targets = files.distinctBy(CloudFile::id)
        if (targets.isEmpty()) return
        val durations = listOf(1L, 7L, 30L, -1L)
        var selected = 1
        val code = AppCompatCheckBox(context).apply { text = context.getString(R.string.ui_share_requires_code) }
        val separate = AppCompatCheckBox(context).apply { text = context.getString(R.string.ui_separate_share_links) }
        val panel = formPanel(if (targets.size > 1) listOf(code, separate) else listOf(code))
        val box = AlertDialog.Builder(context).setTitle(context.getString(R.string.ui_create_share))
            .setSingleChoiceItems(arrayOf(context.getString(R.string.ui_one_day), context.getString(R.string.ui_seven_days), context.getString(R.string.ui_thirty_days), context.getString(R.string.ui_forever)), selected) { _, index -> selected = index }
            .setView(panel)
            .setNegativeButton(context.getString(R.string.ui_cancel), null)
            .setPositiveButton(context.getString(R.string.ui_create)) { _, _ ->
                val separateLinks = separate.isChecked
                val withCode = code.isChecked
                model.command(
                    context.getString(R.string.ui_creating_share), "share_create", "ids" to targets.map(CloudFile::id),
                    "days" to durations[selected], "protected" to withCode, "separate" to separateLinks,
                ) { response ->
                    val value = response as JSONObject
                    if (separateLinks) showSeparateShares(targets, value, withCode)
                    else {
                        val passCode = value.stringOrNull("pass_code")
                        val url = value.stringOrNull("share_url") ?: error(context.getString(R.string.ui_missing_created_share_link))
                        val text = shareText(url, passCode)
                        result(context.getString(R.string.ui_share_created), text, context.getString(R.string.ui_copy_share)) {
                            copy(text, context.getString(R.string.ui_share), sensitive = withCode || passCode != null)
                        }
                    }
                }
            }.create()
        show(box)
    }

    private fun showSeparateShares(targets: List<CloudFile>, response: JSONObject, protected: Boolean) {
        val names = targets.associate { it.id to it.name }
        var sensitive = protected
        val links = response.optJSONArray("shares").objects().mapNotNull { entry ->
            val id = entry.stringOrNull("file_id") ?: return@mapNotNull null
            val name = names[id] ?: return@mapNotNull null
            val share = entry.optJSONObject("share") ?: return@mapNotNull null
            val url = share.stringOrNull("share_url") ?: return@mapNotNull null
            val code = share.stringOrNull("pass_code")
            sensitive = sensitive || code != null
            "$name\n${shareText(url, code)}"
        }
        val partial = links.size != targets.size
        val text = links.joinToString("\n\n")
        val body = if (partial) {
            context.getString(R.string.ui_share_partial_count, links.size, targets.size) +
                if (text.isEmpty()) "" else "\n\n$text"
        } else text
        result(
            if (partial) context.getString(R.string.ui_share_partially_created) else context.getString(R.string.ui_share_created),
            body,
            if (text.isEmpty()) null else if (partial) context.getString(R.string.ui_copy_confirmed_links) else context.getString(R.string.ui_copy_all_links),
        ) { copy(text, context.getString(R.string.ui_share_link), sensitive) }
    }

    fun shareMenu(share: ListedShare) {
        choices(share.title, buildList {
            add(Choice(context.getString(R.string.ui_share_details), Symbol.Info) { result(share.title, share.details()) })
            share.url?.let { url ->
                val text = shareText(url, share.accessCode)
                add(Choice(context.getString(R.string.ui_copy_share), Symbol.Copy) { copy(text, context.getString(R.string.ui_share), sensitive = share.protected) })
                add(Choice(context.getString(R.string.ui_send_share), Symbol.Share) {
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.ui_send_share)))
                })
            }
            share.id?.let { id ->
                add(Choice(context.getString(R.string.ui_revoke_share), Symbol.Trash, dangerous = true) {
                    confirm(context.getString(R.string.ui_revoke_share), context.getString(R.string.ui_revoke_share_confirm), context.getString(R.string.ui_revoke_share), true) {
                        model.command(context.getString(R.string.ui_revoking_share), "share_delete", "ids" to listOf(id), done = context.getString(R.string.ui_share_revoked))
                    }
                })
            }
        })
    }

    private fun shareText(url: String, code: String?) = if (code == null) url else context.getString(R.string.ui_share_text_with_code, url, code)

    fun copy(value: String, label: String, sensitive: Boolean = false) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= 33 && sensitive) {
            clip.description.extras = PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        manager.setPrimaryClip(clip)
        model.notice = context.getString(R.string.ui_copied_to_clipboard, label)
    }

    fun openVerification(value: String) {
        val uri = httpsUri(value)
        val host = uri?.host?.lowercase().orEmpty()
        if (uri != null && (host == "mypikpak.com" || host.endsWith(".mypikpak.com"))) openExternal(uri, null)
        else model.notice = context.getString(R.string.ui_verification_link_invalid)
    }

    private fun openExternal(uri: Uri, mime: String?) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            if (mime == null) intent.data = uri else intent.setDataAndType(uri, mime)
            context.startActivity(Intent.createChooser(intent, if (mime == null) context.getString(R.string.ui_open_verification_link) else context.getString(R.string.ui_choose_player)))
        } catch (_: android.content.ActivityNotFoundException) {
            model.notice = context.getString(R.string.ui_no_link_handler)
        }
    }

    private fun formPanel(views: List<View>) = context.column().apply {
        setPadding(context.dp(22), context.dp(12), context.dp(22), context.dp(8))
        views.forEach { child ->
            addView(child, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = context.dp(10) })
        }
    }

    private fun textField(label: String, initial: String = "", type: Int = InputType.TYPE_CLASS_TEXT): DialogField {
        val layout = TextInputLayout(context).apply { hint = label }
        val edit = TextInputEditText(context).apply { inputType = type; isSingleLine = true; setText(initial) }
        layout.addView(edit)
        return DialogField(layout, edit)
    }

    private fun editDialog(
        title: String,
        views: List<View>,
        confirm: String,
        fields: List<DialogField>,
        valid: () -> Boolean,
        action: () -> Unit,
    ): AlertDialog {
        val scroll = ScrollView(context).apply { addView(formPanel(views)) }
        val box = AlertDialog.Builder(context).setTitle(title).setView(scroll)
            .setNegativeButton(context.getString(R.string.ui_cancel), null)
            .setPositiveButton(confirm) { _, _ -> action() }.create()
        show(box)
        fun update() { box.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = valid() }
        fields.forEach { it.edit.doAfterTextChanged { update() } }
        update()
        return box
    }

    private fun imagePreview(source: PreviewFile, preferOriginal: Boolean = false) {
        val file = source.file
        val image = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxHeight = context.dp(420)
        }
        val spinner = SeslProgressBar(context, null, 0, androidx.appcompat.R.style.Widget_AppCompat_ProgressBar).apply {
            isIndeterminate = true
        }
        val status = context.label(context.getString(R.string.ui_preview_unavailable)).apply {
            gravity = Gravity.CENTER
            showWhen(false)
        }
        val preview = FrameLayout(context).apply {
            minimumHeight = context.dp(80)
            addView(image, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
            addView(spinner, FrameLayout.LayoutParams(context.dp(40), context.dp(40), Gravity.CENTER))
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        }
        val builder = AlertDialog.Builder(context).setTitle(file.name)
            .setView(formPanel(listOf(preview)))
            .setNegativeButton(context.getString(R.string.ui_close), null)
        if (source.canRefreshLink && file.category == FileCategory.Video) {
            builder.setPositiveButton(context.getString(R.string.ui_play)) { _, _ -> play(file) }
        }
        val box = builder.create()
        show(box)
        val job = context.lifecycleScope.launch {
            val bitmap = model.previews.preview(source, preferOriginal)
            spinner.showWhen(false)
            status.showWhen(bitmap == null)
            image.setImageBitmap(bitmap)
        }
        box.setOnDismissListener { job.cancel() }
    }

    private data class DialogField(val layout: TextInputLayout, val edit: TextInputEditText) {
        val value: String get() = edit.text?.toString().orEmpty()
    }
}

