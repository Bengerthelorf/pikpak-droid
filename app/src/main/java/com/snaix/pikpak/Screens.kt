package com.snaix.pikpak

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.SeslProgressBar
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.oneuiproject.oneui.layout.ToolbarLayout
import dev.oneuiproject.oneui.layout.NavDrawerLayout
import dev.oneuiproject.oneui.navigation.widget.DrawerNavigationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val drawerPages = mapOf(
    R.id.nav_files to Page.Files, R.id.nav_favorites to Page.Favorites,
    R.id.nav_recent to Page.Recent, R.id.nav_trash to Page.Trash, R.id.nav_tasks to Page.Tasks,
    R.id.nav_shares to Page.Shares,
)

internal class WorkspaceView(
    private val activity: AppCompatActivity,
    private val model: CloudModel,
    private val actions: CloudActions,
) {
    val root: NavDrawerLayout = activity.layoutInflater.inflate(R.layout.workspace, null) as NavDrawerLayout
    private val pages: FolderPageLayout = root.findViewById(R.id.workspace_folder_pages)
    private var listing = Listing(root.findViewById(R.id.workspace_list))
    private val list get() = listing.view
    private val rows get() = listing.rows
    private val gridLayout get() = listing.grid
    private val layout get() = list.layoutManager as LinearLayoutManager
    private val refresh: SwipeRefreshLayout = root.findViewById(R.id.workspace_refresh)
    private val drawer: DrawerNavigationView = root.findViewById(R.id.workspace_drawer)
    private val drawerFavorites = DrawerFavorites(activity, root, drawer, model)
    private val drawerAccount = DrawerAccountPanel(activity, root, model)
    private val progress: TextView = root.findViewById(R.id.workspace_busy)
    // The One UI content column follows the app bar. Its first row sits above the rounded list mask.
    private val driveProgressRow = root.findViewById<LinearLayout>(dev.oneuiproject.oneui.design.R.id.tbl_main_content_parent)
        .let { column ->
            activity.layoutInflater.inflate(R.layout.workspace_drive_progress, column, false).also { column.addView(it, 0) }
        }
    private val driveProgress: SeslProgressBar = driveProgressRow.findViewById(R.id.workspace_drive_progress)
    private val scrollPositions = LinkedHashMap<WorkspaceLocation, WorkspaceScroll>(16, 0.75f, true)
    private var activeLocation: WorkspaceLocation? = null
    private var pendingScroll: RowAnchor? = null
    private var requestedAnchor: RowAnchor? = null
    private var activePresentation: DrivePresentation? = null
    private var tasks: WorkspaceTasks? = null
    private var taskMenuState: Pair<TaskTab, Boolean>? = null
    private val observe: Job
    private val routeBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { handleBack() }
    }
    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            populateToolbar(menu, model.page)
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
    }

    private inner class Listing(val view: RecyclerView) {
        val rows = WorkspaceAdapter(activity.lifecycleScope, model.previews, model.folderCovers)
        val linear = LinearLayoutManager(activity)
        val grid = GridLayoutManager(activity, 2).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (rows.isTile(position)) 1 else spanCount
            }
        }
        init {
            view.layoutManager = linear
            view.adapter = rows
            view.itemAnimator = null
            view.isVerticalScrollBarEnabled = false
            view.seslSetFastScrollerEnabled(true)
            view.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                if (view === list) {
                    requestedAnchor = null
                    if (view.layoutManager === grid) updateGridColumns(right - left)
                }
            }
            view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (view === list && newState == RecyclerView.SCROLL_STATE_DRAGGING) pendingScroll = null
                }
            })
        }
    }

    init {
        refresh.setOnChildScrollUpCallback { _, _ -> list.canScrollVertically(-1) }
        refresh.setOnRefreshListener {
            if (model.page == Page.Files || model.page == Page.Favorites) refresh.isRefreshing = false
            actions.refresh()
        }
        activity.addMenuProvider(menuProvider)
        root.lockNavRailOnActionMode = true
        root.lockNavRailOnSearchMode = true
        root.setDrawerLockListener(drawer::updateLock)
        drawerPages.forEach { (id, page) -> drawer.findMenuItem(id)?.icon = activity.icon(if (page == Page.Favorites) Symbol.ListView else page.icon) }
        val settingsLabel = activity.getString(R.string.workspace_settings_title)
        activity.icon(Symbol.Settings)?.let { icon ->
            root.setupHeaderButton(icon, tooltipText = settingsLabel) {
                root.setDrawerOpen(false)
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
            root.findViewById<View>(dev.oneuiproject.oneui.design.R.id.oui_des_drawer_header_button)
                ?.contentDescription = settingsLabel
        }
        drawer.setNavigationItemSelectedListener { menu ->
            if (drawerFavorites.handle(menu) { file ->
                // A document opens a dialog or a separate playback window immediately.
                root.setDrawerOpen(false, animate = file.folder)
                actions.openDrawerFavorite(file)
            }) return@setNavigationItemSelectedListener true
            drawerPages[menu.itemId]?.let(model::open)
            root.setDrawerOpen(false)
            true
        }
        observe = activity.lifecycleScope.launch {
            model.observe { currentData() }.collect { data -> render(data) }
        }
    }

    private fun currentData() = ScreenData(
        model.location, model.visibleEntries,
        model.records, model.drawerAccount.takeIf { model.page == Page.Account }, model.selected, model.query,
        model.loading, model.loadError, model.busy, model.showThumbnails, model.listingPresentation, model.coverRevision,
        scanTotal = model.entries.count { it.folderStats != null },
        scanPending = model.entries.count { it.folderStats == FolderStats.Pending },
        syncingFolderIndex = model.syncingFolderIndex, hasDriveListing = model.hasDriveListing,
        taskTab = model.taskTab.takeIf { model.page == Page.Tasks },
        searchScope = model.location.search?.scope,
        searchStatus = model.searchStatus,
        localTransfers = if (model.page == Page.Tasks) model.localTransfers else emptyList(),
    )

    private fun render(data: ScreenData) {
        val location = data.location
        val wantsGrid = location.page == Page.Files && data.presentation.view == DriveView.Icons
        val locationChanged = location != activeLocation
        val presentationChanged = activePresentation != null && activePresentation != model.presentation
        val keepVisible = if (!locationChanged && !presentationChanged && location.page == Page.Files &&
            location.search == null && data.query.isBlank() && pendingScroll == null
        ) visibleAnchor() else null
        if (locationChanged) {
            val previous = activeLocation
            if (previous != null && previous.page != Page.Tasks && pendingScroll == null &&
                rows.itemCount > 0 && (!root.isSearchMode || previous.search != null) && !root.isActionMode
            ) {
                visibleAnchor()?.let {
                    scrollPositions[previous] = WorkspaceScroll(it, root.isExpanded, layout === gridLayout)
                }
                if (scrollPositions.size > 24) scrollPositions.remove(scrollPositions.keys.first())
            }
            if (root.drawerOffset == 0f) {
                pages.navigate(previous, location) {
                    val next = activity.layoutInflater.inflate(R.layout.workspace_list, pages, false) as RecyclerView
                    listing = Listing(next)
                    next
                }
            } else pages.settle()
            if (root.isActionMode) root.endActionMode()
            if (root.isSearchMode) root.endSearchMode()
            configureToolbar(location)
            val saved = scrollPositions[location]
            pendingScroll = saved?.let {
                if (it.grid == wantsGrid) it.anchor
                else it.anchor.takeIf { anchor -> anchor.fileId != null }?.copy(offset = 0)
            }
            root.setExpanded(saved?.toolbarExpanded ?: true, false)
            activeLocation = location
            if (location.search != null) beginSearch(location.search.term)
            else if (previous == null && data.query.isNotBlank()) beginSearch(data.query)

            // The One UI navigation layout also registers with the Activity's back dispatcher.
            // Install the current route after its toolbar state is set so one Back reaches its parent.
            routeBack.remove()
            if (location.parent != null) activity.onBackPressedDispatcher.addCallback(activity, routeBack)
        }
        val wantedLayout = if (wantsGrid) gridLayout else listing.linear
        if (layout !== wantedLayout) {
            val side = if (wantsGrid) activity.dp(8) else 0
            list.setPaddingRelative(side, list.paddingTop, side, list.paddingBottom)
            if (wantsGrid) updateGridColumns(if (list.width > 0) list.width else pages.width)
            list.layoutManager = wantedLayout
        }
        if (locationChanged || presentationChanged) {
            requestedAnchor = null
            if (!locationChanged) pendingScroll = null
            if (pendingScroll == null && location.page != Page.Tasks) scrollTo(RowAnchor(0, 0))
        }
        activePresentation = model.presentation
        val page = location.page
        val section = location.drawerDestination
        drawerPages.forEach { (id, destination) ->
            drawer.findMenuItem(id)?.let { item ->
                val checked = destination == section
                if (item.isChecked != checked) item.isChecked = checked
            }
        }
        val taskTab = data.taskTab
        refresh.showWhen(taskTab == null)
        val drivePage = page == Page.Files || page == Page.Favorites
        refresh.isRefreshing = !drivePage && taskTab == null && data.loading
        if (drivePage && data.error == null && (data.loading || data.scanPending > 0 || data.syncingFolderIndex)) {
            val completed = data.scanTotal - data.scanPending
            driveProgress.isIndeterminate = data.loading || data.syncingFolderIndex || completed == 0
            if (!driveProgress.isIndeterminate) {
                driveProgress.max = data.scanTotal
                driveProgress.progress = completed
            }
            driveProgress.contentDescription = if (data.searchStatus?.updating == true) activity.getString(R.string.search_index_updating)
                else if (data.loading) appString(R.string.ui_loading_file_list)
                else if (data.syncingFolderIndex) appString(R.string.ui_syncing_folder_stats)
                else appString(R.string.ui_folder_scan_progress, completed, data.scanTotal)
            driveProgressRow.showWhen(true)
        } else driveProgressRow.showWhen(false)
        progress.text = data.busy.orEmpty()
        progress.showWhen(data.busy != null && data.localTransfers.none { it.status == TransferStatus.Running })
        val inSelection = data.selected.isNotEmpty()
        if (inSelection && !root.isActionMode) beginSelection()
        if (!inSelection && root.isActionMode) root.endActionMode()
        if (inSelection) root.updateAllSelector(
            count = data.selected.size,
            enabled = data.entries.isNotEmpty(),
            checked = data.entries.isNotEmpty() && data.entries.all { it.id in data.selected },
        )
        val currentTaskMenu = taskTab?.let { it to (data.busy == null) }
        if (currentTaskMenu != taskMenuState) {
            taskMenuState = currentTaskMenu
            activity.invalidateOptionsMenu()
        }
        if (taskTab != null) {
            if (rows.itemCount > 0) rows.submit(emptyList())
            val panel = tasks ?: WorkspaceTasks(
                root.findViewById<ViewStub>(R.id.workspace_tasks_stub).inflate(),
                activity.lifecycleScope, model, actions,
            ).also { tasks = it }
            panel.root.showWhen(true)
            panel.render(taskTab, data.records, data.localTransfers, data.loading, data.error, data.showThumbnails)
            return
        }
        tasks?.root?.showWhen(false)
        val items = items(data)
        rows.submit(items)
        val anchor = pendingScroll ?: keepVisible ?: return
        if (pendingScroll != null && !data.hasDriveListing && (data.loading || data.error != null)) return
        val matchingPosition = when {
            anchor.fileId == null -> -1
            items.getOrNull(anchor.position)?.fileId == anchor.fileId -> anchor.position
            else -> items.indexOfFirst { it.fileId == anchor.fileId }
        }
        if (pendingScroll != null) {
            if (matchingPosition < 0 && anchor.fileId != null && data.loading) return
            pendingScroll = null
            if (items.isNotEmpty()) {
                val position = if (matchingPosition >= 0) matchingPosition else anchor.position.coerceAtMost(items.lastIndex)
                scrollTo(anchor.copy(position = position, fileId = items[position].fileId))
            }
        } else if (matchingPosition >= 0 && matchingPosition != anchor.position) {
            scrollTo(anchor.copy(position = matchingPosition))
        }
    }

    private fun visibleAnchor(): RowAnchor? {
        // A cache render and a network render can both arrive before RecyclerView lays out its children.
        requestedAnchor?.let { return it }
        val first = layout.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return null
        var fallback: RowAnchor? = null
        for (position in first..layout.findLastVisibleItemPosition()) {
            val view = layout.findViewByPosition(position) ?: continue
            val anchor = RowAnchor(position, layout.getDecoratedTop(view) - list.paddingTop,
                view.getTag(R.id.workspace_list) as? String)
            if (first == 0 || anchor.fileId != null) return anchor
            if (fallback == null) fallback = anchor
        }
        return fallback
    }

    private fun scrollTo(anchor: RowAnchor) {
        requestedAnchor = anchor
        layout.scrollToPositionWithOffset(anchor.position, anchor.offset)
    }

    private fun updateGridColumns(width: Int) {
        if (width <= 0) return
        val columns = ((width - activity.dp(16)) / activity.dp(135)).coerceIn(1, 6)
        if (gridLayout.spanCount != columns) gridLayout.spanCount = columns
    }

    private fun configureToolbar(location: WorkspaceLocation) {
        root.setTitle(location.title)
        root.setSubtitle(location.folder?.let {
            generateSequence(location.parent) { it.parent }
                .takeWhile { it.page == location.page && it.search == null }.toList().asReversed()
                .joinToString(" / ") { it.title }.ifEmpty { location.parent?.title.orEmpty() }
        })
        root.showNavigationButtonAsBack = location.showsUp
        root.showNavigationButton = true
        root.setNavigationButtonTooltip(activity.getString(if (location.showsUp) R.string.drawer_back else R.string.drawer_open))
        // The compact One UI drawer ignores setNavigationButtonOnClickListener on NavDrawerLayout.
        // Route its actual toolbar Up button directly to this workspace's parent.
        root.toolbar.setNavigationOnClickListener {
            if (location.showsUp) model.back() else root.setDrawerOpen(root.drawerOffset == 0f)
        }
        activity.invalidateOptionsMenu()
    }

    private fun populateToolbar(menu: Menu, page: Page) {
        val filePage = page in setOf(Page.Files, Page.Favorites, Page.Trash, Page.SharedFiles)
        val fullWidthDrive = page == Page.Files && activity.resources.configuration.screenWidthDp >= 360
        val fileActionPlacement = if (fullWidthDrive) MenuItem.SHOW_AS_ACTION_ALWAYS else MenuItem.SHOW_AS_ACTION_IF_ROOM
        if (filePage) menu.entry(appString(R.string.ui_search), Symbol.Search, showAsAction = fileActionPlacement) {
            if (page == Page.Files && model.location.search == null) model.searchDrive() else beginSearch(model.query)
        }
        when (page) {
            Page.Files -> menu.entry(appString(R.string.ui_add), Symbol.Plus, showAsAction = MenuItem.SHOW_AS_ACTION_ALWAYS, action = actions::create)
            Page.Tasks -> when (model.taskTab) {
                TaskTab.Cloud -> menu.entry(appString(R.string.ui_new_cloud_download), Symbol.Plus, icon = true, enabled = model.busy == null) { actions.addTask() }
                TaskTab.Upload -> menu.entry(appString(R.string.ui_upload_to_cloud), Symbol.Plus, icon = true, enabled = model.busy == null, action = actions::newUpload)
                TaskTab.Download -> Unit
            }
            Page.Shares -> menu.entry(appString(R.string.ui_open_share), Symbol.Plus, icon = true) { actions.openShare() }
            Page.SharedFiles -> menu.entry(appString(R.string.ui_save_all_shared), Symbol.Download, icon = true) { actions.saveShare(model.entries) }
            else -> Unit
        }
        if (filePage) menu.entry(appString(R.string.ui_view_options), Symbol.ViewOptions, showAsAction = fileActionPlacement, action = actions::viewOptions)
        if (page != Page.Tasks || model.taskTab == TaskTab.Cloud) {
            menu.entry(appString(R.string.ui_refresh), Symbol.Refresh, enabled = page != Page.Tasks || model.busy == null, action = actions::refresh)
        }
        if (page == Page.Trash) menu.entry(appString(R.string.ui_empty_trash), Symbol.Trash, action = actions::emptyTrash)
    }

    private fun beginSearch(initialQuery: String = "") {
        val origin = model.location
        if (origin.search == null && initialQuery.isEmpty()) {
            visibleAnchor()?.let {
                scrollPositions[origin] = WorkspaceScroll(it, root.isExpanded, layout === gridLayout)
            }
        }
        var acceptingEdits = false
        root.startSearchMode(object : ToolbarLayout.SearchModeListener {
            override fun onSearchModeToggle(searchView: SearchView, isActive: Boolean) {
                searchView.queryHint = activity.getString(if (origin.search != null) R.string.search_input_hint else R.string.search_list_hint)
                if (isActive) {
                    if (searchView.query.toString() != initialQuery) searchView.setQuery(initialQuery, false)
                    acceptingEdits = true
                } else {
                    acceptingEdits = false
                    // One UI fades search back in when selection ends. Cancel that animation
                    // when search closes before its end action can show the toolbar again.
                    (searchView.parent as? Toolbar)?.let { searchToolbar ->
                        searchToolbar.animate().cancel()
                        searchToolbar.visibility = View.GONE
                    }
                    if (model.location == origin) {
                        if (origin.search != null) model.back() else if (model.query.isNotEmpty()) {
                            pendingScroll = scrollPositions[origin]?.anchor
                            model.editQuery("")
                        }
                    }
                }
            }

            override fun onQueryTextChange(newText: String): Boolean {
                if (acceptingEdits && model.location == origin) model.editQuery(newText)
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean {
                WindowCompat.getInsetsController(activity.window, root).hide(WindowInsetsCompat.Type.ime())
                if (model.location == origin && origin.search != null && query.isNotBlank()) model.refresh()
                return true
            }
        }, ToolbarLayout.SearchModeOnBackBehavior.DISMISS, initialQuery.isEmpty())
    }

    private fun beginSelection() {
        root.startActionMode(object : ToolbarLayout.ActionModeListener {
            override fun onInflateActionMenu(menu: Menu, menuInflater: MenuInflater) {
                val item = if (model.page == Page.SharedFiles) {
                    menu.add(Menu.NONE, R.id.selection_save_share, Menu.NONE, appString(R.string.ui_save_selected)).setIcon(activity.icon(Symbol.Download))
                } else {
                    menu.add(Menu.NONE, R.id.selection_file_actions, Menu.NONE, appString(R.string.ui_actions)).setIcon(activity.icon(Symbol.More))
                }
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            override fun onEndActionMode() { model.clearSelection() }
            // One UI copies these items between its action bars and forwards clicks by ID.
            override fun onMenuItemClicked(item: MenuItem): Boolean {
                when (item.itemId) {
                    R.id.selection_file_actions -> actions.selectionMenu()
                    R.id.selection_save_share -> actions.saveShare(model.chosen)
                    else -> return false
                }
                return true
            }
            override fun onSelectAll(isChecked: Boolean) { model.selectVisible() }
        }, searchOnActionMode = ToolbarLayout.SearchOnActionMode.NoDismiss)
    }

    private fun Menu.entry(
        title: String,
        glyph: Symbol,
        icon: Boolean = false,
        enabled: Boolean = true,
        showAsAction: Int = if (icon) MenuItem.SHOW_AS_ACTION_IF_ROOM else MenuItem.SHOW_AS_ACTION_NEVER,
        action: () -> Unit,
    ) {
        val drawable = activity.icon(glyph)
        add(title).apply {
            this.icon = drawable
            isEnabled = enabled
            setShowAsAction(if (drawable != null) showAsAction else MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener { action(); true }
        }
    }

    fun handleBack(): Boolean = when {
        root.drawerOffset > 0f -> { root.setDrawerOpen(false); true }
        root.isActionMode -> { root.endActionMode(); true }
        root.isSearchMode -> { root.endSearchMode(); true }
        else -> model.back()
    }

    fun dispose() {
        routeBack.remove()
        observe.cancel()
        drawerFavorites.dispose()
        drawerAccount.dispose()
        tasks?.dispose()
        pages.settle()
        list.adapter = null
        activity.removeMenuProvider(menuProvider)
    }

    private fun items(data: ScreenData): List<WorkspaceRow> = buildList {
        val page = data.location.page
        data.error?.let {
            add(WorkspaceRow(if (data.hasDriveListing) appString(R.string.ui_refresh_error_retry) else appString(R.string.ui_load_failed), it, Symbol.Info, action = actions::refresh))
        }
        when (page) {
            Page.Files, Page.Favorites, Page.Trash, Page.SharedFiles -> {
                val search = data.location.search
                if (search != null && data.selected.isEmpty()) {
                    val heading = activity.getString(R.string.search_scope)
                    val entire = activity.getString(R.string.search_scope_all)
                    val current = activity.getString(R.string.search_scope_folder, search.origin.name)
                    val global = data.searchScope == DriveSearchScope.EntireDrive
                    add(WorkspaceRow(heading, if (global) entire else current, Symbol.Search) {
                        actions.choices(heading, listOf(
                            Choice(entire, Symbol.Search, selected = global) { model.setSearchScope(DriveSearchScope.EntireDrive) },
                            Choice(current, Symbol.Folder, selected = !global) { model.setSearchScope(DriveSearchScope.CurrentFolder) },
                        ))
                    })
                }
                if (data.location.search != null && data.query.isBlank()) {
                    add(WorkspaceRow(activity.getString(R.string.search_drive_empty_query), icon = Symbol.Search))
                    return@buildList
                }
                val status = data.searchStatus
                if (status != null && data.searchScope == DriveSearchScope.EntireDrive && data.selected.isEmpty()) {
                    val title = when {
                        status.failure != null -> R.string.search_update_failed
                        status.updating -> R.string.search_index_updating
                        status.cached -> R.string.search_index_cached
                        status.sessionOnly -> R.string.search_index_session_only
                        !status.complete -> R.string.search_index_partial
                        else -> null
                    }
                    title?.let {
                        val detail = listOfNotNull(
                            if (status.cached && (status.updating || status.failure != null)) activity.getString(R.string.search_index_cached)
                                else if (status.sessionOnly && (status.updating || status.failure != null)) activity.getString(R.string.search_index_session_only)
                                else if (!status.complete && it != R.string.search_index_partial) activity.getString(R.string.search_index_partial) else null,
                            status.failure?.takeUnless { message -> message == activity.getString(it) },
                        ).joinToString(" · ").ifEmpty { null }
                        add(WorkspaceRow(activity.getString(it), detail, Symbol.Info,
                            action = if (status.failure != null) actions::refresh else null))
                    }
                }
                if (data.location.search != null && data.entries.isNotEmpty() && data.selected.isEmpty()) {
                    val count = status?.count ?: data.entries.size
                    add(WorkspaceRow(activity.getString(R.string.search_results_heading),
                        if (count > data.entries.size) activity.getString(R.string.search_shown_match_count, data.entries.size, count)
                        else activity.getString(R.string.search_drive_match_count, count), section = true))
                }
                if (status != null && !data.loading && data.selected.isEmpty()) {
                    if (status.page > 0) add(WorkspaceRow(activity.getString(R.string.search_previous_page), icon = Symbol.Back) {
                        model.changeSearchPage(forward = false)
                        list.scrollToPosition(0)
                    })
                    if (status.hasNextPage) add(WorkspaceRow(activity.getString(R.string.search_next_page), icon = Symbol.ChevronEnd) {
                        model.changeSearchPage(forward = true)
                        list.scrollToPosition(0)
                    })
                }
                if (data.query.isBlank() && data.selected.isEmpty()) {
                    if (data.entries.isNotEmpty() && (page != Page.Files || data.presentation.grouping == DriveGrouping.None)) add(WorkspaceRow(
                        if (page == Page.SharedFiles) appString(R.string.ui_files_in_share) else if (page == Page.Trash) appString(R.string.ui_deleted_items) else appString(R.string.ui_files),
                        appString(R.string.ui_items_count, data.entries.size), section = true,
                    ))
                }
                val useTiles = page == Page.Files && data.presentation.view == DriveView.Icons
                val sections = if (page == Page.Files) data.presentation.sections(data.entries)
                    else listOf(DriveSection(null, data.entries))
                for (section in sections) {
                    section.label?.let { add(WorkspaceRow(it, appString(R.string.ui_items_count, section.entries.size), section = true)) }
                    for (file in section.entries) {
                        val thumbnail = if (data.showThumbnails && !file.folder && file.thumbnail != null) {
                            PreviewFile(file, canRefreshLink = page == Page.Files || page == Page.Favorites)
                        } else null
                        val previewThumbnail = if (thumbnail != null && data.selected.isEmpty() && page != Page.Trash) {
                            { actions.quickPreview(file) }
                        } else null
                        val detail = data.presentation.rowDetail(file)
                        add(WorkspaceRow(
                            file.name, detail, file.category.symbol,
                            fileId = file.id,
                            selected = if (data.selected.isEmpty()) null else file.id in data.selected,
                            onLongClick = {
                                if (page == Page.Files && file.folder && model.selected.isEmpty()) actions.fileMenu(file)
                                else model.select(file)
                            },
                            overflow = if (data.selected.isEmpty()) ({ actions.fileMenu(file) }) else null,
                            thumbnail = thumbnail,
                            previewThumbnail = previewThumbnail,
                            tile = if (useTiles) DriveTile(file.folder,
                                if (file.folder && data.showThumbnails) model.folderCover(file) else null,
                                data.coverRevision,
                            ) else null,
                        ) { actions.openFile(file) })
                    }
                }
                if (data.entries.isEmpty() && (data.hasDriveListing || (data.error == null && !data.loading))) {
                    val title = when {
                        data.searchScope == DriveSearchScope.EntireDrive && status?.complete == false ->
                            activity.getString(R.string.search_partial_empty)
                        data.query.isNotBlank() -> appString(R.string.ui_no_matching_files)
                        page == Page.Trash -> appString(R.string.ui_trash_empty)
                        page == Page.Favorites -> appString(R.string.ui_no_favorites)
                        else -> appString(R.string.ui_no_files)
                    }
                    add(WorkspaceRow(title, icon = Symbol.Empty))
                    if (page == Page.Files && data.query.isBlank()) add(WorkspaceRow(appString(R.string.ui_add), icon = Symbol.Plus, action = actions::create))
                }
            }
            Page.Tasks -> Unit
            Page.Shares -> {
                if (data.records.isEmpty() && !data.loading && data.error == null) {
                    add(WorkspaceRow(appString(R.string.ui_no_shares), appString(R.string.ui_create_share_hint), Symbol.Share))
                }
                data.records.map(ListedShare::from).forEach { share ->
                    add(WorkspaceRow(share.title, share.rowSummary, if (share.protected) Symbol.Lock else Symbol.Share) {
                        actions.shareMenu(share)
                    })
                }
                add(WorkspaceRow(appString(R.string.ui_open_share), appString(R.string.ui_open_share_hint), Symbol.Link) { actions.openShare() })
            }
            Page.Account -> {
                val account = data.account
                if (account != null) {
                    add(WorkspaceRow(account.displayName ?: appString(R.string.ui_pikpak_account), icon = Symbol.Person))
                }
                account?.membership?.let { membership ->
                    val status = membership.status?.let {
                        when (it.lowercase()) {
                            "ok", "active" -> appString(R.string.ui_membership_active)
                            "expired" -> appString(R.string.ui_membership_expired)
                            else -> it
                        }
                    }
                    val expiry = membership.expiry?.take(10)?.let { appString(R.string.ui_expires_on, it) }
                    val detail = listOfNotNull(membership.tier, status, expiry).joinToString(" · ")
                    if (detail.isNotEmpty()) add(WorkspaceRow(appString(R.string.ui_membership), detail, Symbol.Star))
                }
                account?.storage?.let {
                    add(WorkspaceRow(appString(R.string.ui_cloud_capacity), it.detail(), Symbol.Folder))
                }
                account?.trashUsage?.let {
                    add(WorkspaceRow(appString(R.string.ui_trash_size), readableBytes(it), Symbol.Trash))
                }
                val bandwidthExpiry = account?.bandwidthExpiry?.take(10)?.let { appString(R.string.ui_expires_on, it) }
                if (!account?.bandwidth.isNullOrEmpty() || bandwidthExpiry != null) {
                    add(WorkspaceRow(appString(R.string.ui_transfer_quota), bandwidthExpiry, section = true))
                    account?.bandwidth?.forEach { (kind, quota) ->
                        val remaining = quota.remaining
                        val detail = if (remaining != null && quota.limit != null) {
                            appString(R.string.ui_quota_remaining_of_total, readableBytes(remaining), readableBytes(quota.limit))
                        } else quota.detail()
                        add(WorkspaceRow(kind.label, detail))
                    }
                }
                add(WorkspaceRow(appString(R.string.ui_cloud_tools), section = true))
                add(WorkspaceRow(appString(R.string.ui_play_local_video), icon = Symbol.Play, action = actions.chooseLocalVideo))
                add(WorkspaceRow(appString(R.string.ui_calculate_local_gcid), icon = Symbol.Hash, action = actions.chooseHash))
                add(WorkspaceRow(appString(R.string.ui_sign_out), icon = Symbol.Exit) {
                    actions.confirm(appString(R.string.ui_sign_out), appString(R.string.ui_sign_out_confirm), appString(R.string.ui_confirm_sign_out)) { model.logout() }
                })
            }
            Page.Recent -> {
                data.records.forEach { event ->
                    val type = event.stringOrNull("type_name") ?: event.stringOrNull("type")
                    val name = event.stringOrNull("file_name") ?: event.optJSONObject("reference_resource")?.stringOrNull("name")
                    val time = event.stringOrNull("created_time")?.displayTime()
                    add(WorkspaceRow(name ?: type ?: appString(R.string.ui_file_activity), listOfNotNull(type, time).distinct().joinToString(" · "), Symbol.Clock))
                }
                if (data.records.isEmpty() && !data.loading && data.error == null) add(WorkspaceRow(appString(R.string.ui_no_recent_activity), icon = Symbol.Clock))
            }
        }
    }
}

private data class RowAnchor(val position: Int, val offset: Int, val fileId: String? = null)
private data class WorkspaceScroll(val anchor: RowAnchor, val toolbarExpanded: Boolean, val grid: Boolean)

private data class ScreenData(
    val location: WorkspaceLocation,
    val entries: List<CloudFile>,
    val records: List<org.json.JSONObject>,
    val account: DrawerAccountData?,
    val selected: Set<String>,
    val query: String,
    val loading: Boolean,
    val error: String?,
    val busy: String?,
    val showThumbnails: Boolean,
    val presentation: DrivePresentation,
    val coverRevision: Int,
    val scanTotal: Int,
    val scanPending: Int,
    val syncingFolderIndex: Boolean,
    val hasDriveListing: Boolean,
    val taskTab: TaskTab?,
    val searchScope: DriveSearchScope?,
    val searchStatus: DriveSearchStatus?,
    val localTransfers: List<LocalTransfer>,
)

internal data class WorkspaceRow(
    val title: String,
    val detail: String? = null,
    val icon: Symbol? = null,
    val section: Boolean = false,
    val selected: Boolean? = null,
    val onLongClick: (() -> Unit)? = null,
    val overflow: (() -> Unit)? = null,
    val thumbnail: PreviewFile? = null,
    val previewThumbnail: (() -> Unit)? = null,
    val tile: DriveTile? = null,
    val thumbnailBitmap: Bitmap? = null,
    val fileId: String? = null,
    val action: (() -> Unit)? = null,
)

internal class WorkspaceAdapter(
    private val scope: CoroutineScope,
    private val previews: CloudPreviews,
    private val covers: FolderCovers? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rows: List<WorkspaceRow> = emptyList()

    fun submit(value: List<WorkspaceRow>) {
        rows = value
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    fun isTile(position: Int) = rows.getOrNull(position)?.tile != null

    override fun getItemViewType(position: Int) = if (isTile(position)) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == 1) DriveTileHolder(parent.context, scope, previews, checkNotNull(covers)) else Holder(parent.context)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        holder.itemView.setTag(R.id.workspace_list, row.fileId)
        when (holder) {
            is DriveTileHolder -> holder.bind(row)
            is Holder -> {
                val divider = !row.section && position < rows.lastIndex && !rows[position + 1].section && !isTile(position + 1)
                holder.bind(row, divider, scope, previews)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is DriveTileHolder -> holder.recycle()
            is Holder -> holder.recycle()
        }
    }

    class Holder(context: Context) : RecyclerView.ViewHolder(context.column().apply {
        layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }) {
        private val container = itemView as LinearLayout
        private val item = context.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(62)
            setPaddingRelative(context.dp(19), context.dp(10), context.dp(9), context.dp(10))
        }
        private val iconFrame = FileThumbnailView(context)
        private val title = context.label()
        private val detail = context.label(size = 13f, secondary = true)
        private val check = AppCompatCheckBox(context).apply { isClickable = false; isFocusable = false }
        private val more = AppCompatImageButton(context).apply {
            setImageDrawable(context.icon(Symbol.More))
            selectableBackground(true)
        }
        private val divider = View(context).apply {
            setBackgroundColor(context.themeColor(com.google.android.material.R.attr.colorOutlineVariant, 0x14555555))
        }
        init {
            val text = context.column()
            text.addFullWidth(title)
            text.addFullWidth(detail)
            item.addView(iconFrame, LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply {
                marginEnd = context.dp(11)
            })
            item.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            item.addView(check, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            item.addView(more, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
            container.addFullWidth(item)
            container.addView(divider, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(1)).apply {
                marginStart = context.dp(18)
                marginEnd = context.dp(18)
            })
        }

        fun recycle() = iconFrame.recycle()

        fun bind(row: WorkspaceRow, hasDivider: Boolean, scope: CoroutineScope, previews: CloudPreviews) {
            val context = item.context
            val emphasis = context.themeColor(androidx.appcompat.R.attr.colorPrimary, title.currentTextColor)
            title.text = row.title
            title.textSize = if (row.section) 14f else 17f
            title.maxLines = if (row.section) 1 else 2
            title.ellipsize = TextUtils.TruncateAt.END
            title.setTypeface(null, if (row.section) Typeface.BOLD else Typeface.NORMAL)
            detail.text = row.detail.orEmpty()
            detail.showWhen(!row.detail.isNullOrBlank())
            detail.maxLines = if (row.section) 1 else 2
            detail.ellipsize = TextUtils.TruncateAt.END
            val drawable = row.icon?.let { context.icon(it, emphasis) }
            iconFrame.bind(row.thumbnail, previews, scope, drawable, readyBitmap = row.thumbnailBitmap)
            iconFrame.showWhen(drawable != null || row.thumbnail != null || row.thumbnailBitmap != null)
            val preview = row.previewThumbnail
            iconFrame.contentDescription = preview?.let { appString(R.string.ui_preview_item, row.title) }
            iconFrame.setOnClickListener(preview?.let { View.OnClickListener { preview() } })
            iconFrame.setOnLongClickListener(if (preview != null && row.onLongClick != null) {
                View.OnLongClickListener { row.onLongClick.invoke(); true }
            } else null)
            iconFrame.isClickable = preview != null
            iconFrame.isFocusable = preview != null
            if (preview != null) iconFrame.selectableBackground(true) else iconFrame.background = null
            check.isChecked = row.selected == true
            check.showWhen(row.selected != null)
            more.contentDescription = appString(R.string.ui_item_actions, row.title)
            more.showWhen(row.overflow != null)
            more.setOnClickListener(if (row.overflow != null) View.OnClickListener { row.overflow.invoke() } else null)
            divider.showWhen(hasDivider)
            item.minimumHeight = context.dp(if (row.section) 43 else 62)
            item.isSelected = row.selected == true
            item.isClickable = row.action != null
            item.setOnClickListener(if (row.action != null) View.OnClickListener { row.action.invoke() } else null)
            item.setOnLongClickListener(if (row.onLongClick != null) View.OnLongClickListener { row.onLongClick.invoke(); true } else null)
            if (row.action != null) item.selectableBackground() else item.background = null
        }
    }
}
