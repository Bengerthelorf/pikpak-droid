package com.snaix.pikpak

import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

internal class WorkspaceTasks(
    val root: View,
    scope: CoroutineScope,
    private val model: CloudModel,
    private val actions: CloudActions,
) {
    private val pager = root.findViewById<ViewPager2>(R.id.workspace_task_pager)
    private val rowAdapters = TaskTab.entries.map { WorkspaceAdapter(scope, model.previews) }
    private var cloudRefreshing = false
    private var cloudRefresh: SwipeRefreshLayout? = null
    private val pageChange = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = model.selectTaskTab(TaskTab.entries[position])
    }
    private val tabs = root.findViewById<TabLayout>(R.id.workspace_task_tabs).apply { seslSetSubTabStyle() }
    private val mediator: TabLayoutMediator

    init {
        pager.offscreenPageLimit = TaskTab.entries.lastIndex
        pager.adapter = Pages()
        pager.setCurrentItem(model.taskTab.ordinal, false)
        mediator = TabLayoutMediator(tabs, pager) { tab, index -> tab.text = TaskTab.entries[index].title }
        mediator.attach()
        pager.registerOnPageChangeCallback(pageChange)
    }

    fun render(
        tab: TaskTab,
        tasks: List<JSONObject>,
        transfers: List<LocalTransfer>,
        loading: Boolean,
        error: String?,
        showThumbnails: Boolean,
    ) {
        if (pager.currentItem != tab.ordinal) pager.setCurrentItem(tab.ordinal, false)
        cloudRefreshing = loading
        cloudRefresh?.isRefreshing = loading
        rowAdapters[TaskTab.Cloud.ordinal].submit(cloudRows(tasks, loading, error, showThumbnails))
        rowAdapters[TaskTab.Download.ordinal].submit(localRows(TaskTab.Download, transfers, showThumbnails))
        rowAdapters[TaskTab.Upload.ordinal].submit(localRows(TaskTab.Upload, transfers, showThumbnails))
    }

    fun dispose() {
        pager.unregisterOnPageChangeCallback(pageChange)
        mediator.detach()
        pager.adapter = null
    }

    private fun cloudRows(
        tasks: List<JSONObject>, loading: Boolean, error: String?, showThumbnails: Boolean,
    ) = buildList {
        if (error != null) add(WorkspaceRow(appString(R.string.ui_load_failed), error, Symbol.Info, action = actions::refresh))
        for (task in tasks) {
            val (status, icon) = when (task.optString("phase")) {
                "PHASE_TYPE_COMPLETE" -> appString(R.string.ui_completed) to Symbol.Check
                "PHASE_TYPE_RUNNING" -> appString(R.string.ui_cloud_download_progress, task.optInt("progress").coerceIn(0, 100)) to Symbol.Download
                "PHASE_TYPE_PENDING" -> appString(R.string.ui_waiting) to Symbol.Clock
                "PHASE_TYPE_ERROR" -> (task.stringOrNull("message") ?: appString(R.string.ui_failed)) to Symbol.Info
                "PHASE_TYPE_PAUSED" -> appString(R.string.ui_paused) to Symbol.Pause
                else -> appString(R.string.ui_waiting_for_status) to Symbol.Clock
            }
            val preview = if (showThumbnails) cloudTaskPreview(task) else null
            add(WorkspaceRow(task.optString("name").ifBlank { appString(R.string.ui_cloud_download_task) }, status, icon, thumbnail = preview) {
                actions.taskMenu(task)
            })
        }
        if (tasks.isEmpty() && !loading && error == null) {
            add(WorkspaceRow(appString(R.string.ui_no_cloud_downloads), appString(R.string.ui_add_magnet_or_url), Symbol.Download) { actions.addTask() })
        }
    }

    private fun localRows(tab: TaskTab, transfers: List<LocalTransfer>, showThumbnails: Boolean): List<WorkspaceRow> {
        val direction = if (tab == TaskTab.Upload) Symbol.Upload else Symbol.Download
        val records = transfers.filter { it.tab == tab }
        if (records.isEmpty()) return listOf(
            if (tab == TaskTab.Upload) WorkspaceRow(appString(R.string.ui_no_uploads), appString(R.string.ui_upload_hint), direction, action = actions::newUpload)
            else WorkspaceRow(appString(R.string.ui_no_device_downloads), appString(R.string.ui_device_download_hint), direction) { model.open(Page.Files) },
        )
        return records.map { record ->
            val progress = record.progress
            val status = if (record.status == TransferStatus.Running) progress.stage.label else record.status.label
            val count = when {
                record.status == TransferStatus.Completed -> appString(R.string.ui_completed_file_count, progress.completed)
                progress.total != null -> appString(R.string.ui_transfer_file_count, progress.completed, progress.total)
                else -> appString(R.string.ui_completed_files_unknown_total, progress.completed)
            }
            val detail = "${status} · ${count}"
            val currentFile = progress.currentFile?.takeIf { it != record.title && record.status == TransferStatus.Running }
            val bytes = when {
                record.status == TransferStatus.Completed -> null
                progress.byteTotal != null && progress.byteTotal > 0 ->
                    "${readableBytes(progress.bytes)} / ${readableBytes(progress.byteTotal)}"
                progress.bytes > 0 -> readableBytes(progress.bytes)
                else -> null
            }
            val secondary = listOfNotNull(currentFile, bytes, record.failure).joinToString(" · ").takeIf(String::isNotEmpty)
            val icon = when (record.status) {
                TransferStatus.Queued -> Symbol.Clock
                TransferStatus.Running -> direction
                TransferStatus.Pausing, TransferStatus.Paused -> Symbol.Pause
                TransferStatus.Completed -> Symbol.Check
                TransferStatus.Cancelling, TransferStatus.Cancelled, TransferStatus.Failed, TransferStatus.Interrupted -> Symbol.Info
            }
            WorkspaceRow(record.title, listOfNotNull(detail, secondary).joinToString("\n"), icon,
                thumbnail = record.thumbnail.takeIf { showThumbnails },
                thumbnailBitmap = record.thumbnailBitmap.takeIf { showThumbnails }) {
                val startedAt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
                    root.resources.configuration.locales[0]).format(Date(record.startedAt))
                val details = { actions.result(record.title, listOfNotNull(detail, secondary, startedAt).joinToString("\n\n")) }
                if (!record.canControl) details() else actions.choices(record.title, buildList {
                    if (tab == TaskTab.Download) {
                        when (record.status) {
                            TransferStatus.Queued, TransferStatus.Running -> add(Choice(appString(R.string.ui_pause_download), Symbol.Clock) {
                                model.pauseTransfer(record.id)
                            })
                            TransferStatus.Paused -> add(Choice(appString(R.string.ui_resume_download), Symbol.Play) { model.resumeTransfer(record.id) })
                            TransferStatus.Failed -> add(Choice(appString(R.string.ui_retry_download), Symbol.Refresh) { model.resumeTransfer(record.id) })
                            else -> Unit
                        }
                    }
                    if (record.status != TransferStatus.Cancelling) {
                        add(Choice(if (tab == TaskTab.Upload) appString(R.string.ui_cancel_upload) else appString(R.string.ui_cancel_download), Symbol.Trash,
                            appString(R.string.ui_completed_files_kept), dangerous = true) { model.cancelTransfer(record.id) })
                    }
                    add(Choice(appString(R.string.ui_view_details), Symbol.Info, action = details))
                }, allowWhileBusy = true)
            }
        }
    }

    private inner class Pages : RecyclerView.Adapter<TaskPage>() {
        override fun getItemCount() = TaskTab.entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskPage {
            val refresh = SwipeRefreshLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }
            val list = RecyclerView(parent.context).apply {
                layoutManager = LinearLayoutManager(context)
                itemAnimator = null
                clipToPadding = false
                setPadding(0, 0, 0, context.dp(8))
            }
            refresh.addView(list, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            return TaskPage(refresh, list)
        }

        override fun onBindViewHolder(page: TaskPage, position: Int) {
            page.list.adapter = rowAdapters[position]
            val cloud = position == TaskTab.Cloud.ordinal
            page.refresh.isEnabled = cloud
            page.refresh.setOnRefreshListener(if (cloud) SwipeRefreshLayout.OnRefreshListener(actions::refresh) else null)
            page.refresh.isRefreshing = cloud && cloudRefreshing
            if (cloud) cloudRefresh = page.refresh
        }

        override fun onViewRecycled(page: TaskPage) {
            if (cloudRefresh === page.refresh) cloudRefresh = null
            page.list.adapter = null
        }
    }

    private class TaskPage(val refresh: SwipeRefreshLayout, val list: RecyclerView) : RecyclerView.ViewHolder(refresh)
}

internal fun cloudTaskPreview(task: JSONObject): PreviewFile? {
    val file = task.optJSONObject("reference_resource")
    val thumbnail = httpsUri(file?.stringOrNull("thumbnail_link"))
        ?: httpsUri(task.stringOrNull("thumbnail_link")) ?: return null
    val id = file?.stringOrNull("id") ?: task.stringOrNull("file_id")
        ?: task.stringOrNull("id")?.let { "offline-task:$it" } ?: return null
    return PreviewFile(CloudFile(
        id = id,
        name = file?.stringOrNull("name") ?: task.optString("name"),
        folder = false,
        bytes = task.optLong("file_size"),
        modified = file?.stringOrNull("modified_time").orEmpty(),
        thumbnail = thumbnail,
        created = file?.stringOrNull("created_time").orEmpty(),
    ), canRefreshLink = false)
}
