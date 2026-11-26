package com.snaix.pikpak

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.properties.Delegates

// A cancelled scan may still be unwinding a network request. Keep that worker slot occupied.
private val sharedFolderStatsDispatcher = Dispatchers.IO.limitedParallelism(1)
private const val INDEX_CHECK_INTERVAL_MS = 60_000L
private const val MAX_SCOPED_STATS = 512
private const val DRAWER_REFRESH_INTERVAL_MS = 120_000L

internal class ViewState {
    private val revision = MutableStateFlow(0L)

    fun <T> property(initial: T) = Delegates.observable(initial) { _, previous, next ->
        if (previous != next) revision.update { it + 1L }
    }

    fun <T> observe(read: () -> T) = revision.map { read() }.distinctUntilChanged()
}

internal enum class Page(val titleRes: Int, val icon: Symbol) {
    Files(R.string.drawer_files, Symbol.Folder), Tasks(R.string.drawer_tasks, Symbol.Download),
    Shares(R.string.drawer_shares, Symbol.Share), Account(R.string.drawer_account, Symbol.Person),
    Favorites(R.string.drawer_favorites, Symbol.Star), Trash(R.string.drawer_trash, Symbol.Trash),
    Recent(R.string.drawer_recent, Symbol.Clock), SharedFiles(R.string.shared_contents, Symbol.Folder);

    val isDrawerDestination: Boolean get() = this != SharedFiles
}

internal enum class ThemeMode(private val titleResource: Int, val nightMode: Int) {
    System(R.string.model_theme_system, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    Light(R.string.model_theme_light, AppCompatDelegate.MODE_NIGHT_NO),
    Dark(R.string.model_theme_dark, AppCompatDelegate.MODE_NIGHT_YES);

    val label: String get() = appString(titleResource)
}

internal data class ShareBrowser(val id: String, val token: String)

internal enum class DriveSearchScope { EntireDrive, CurrentFolder }
internal class DriveSearch(val origin: Folder, var term: String, var scope: DriveSearchScope = DriveSearchScope.EntireDrive) {
    val cursors = mutableListOf<CloudFile>()
    var indexedAt: Long? = null
}

internal data class DriveSearchStatus(
    val count: Int,
    val complete: Boolean,
    val cached: Boolean = false,
    val updating: Boolean = false,
    val failure: String? = null,
    val page: Int = 0,
    val hasNextPage: Boolean = false,
    val sessionOnly: Boolean = false,
)

internal data class WorkspaceLocation(
    val page: Page,
    val folder: Folder? = null,
    val share: ShareBrowser? = null,
    val parent: WorkspaceLocation? = null,
    val search: DriveSearch? = null,
) {
    val title: String get() = folder?.name ?: appString(when {
        search != null -> R.string.search_drive_title
        page == Page.Account -> R.string.my_account
        else -> page.titleRes
    })
    val showsUp: Boolean get() = folder != null || search != null || !page.isDrawerDestination
    val drawerDestination: Page get() = when {
        page == Page.SharedFiles -> Page.Shares
        folder != null -> parent?.drawerDestination ?: Page.Files
        else -> page
    }
}

internal class CloudModel(
    application: Application,
    private val api: Gateway = CloudApi(application),
    previewProvider: CloudPreviews? = null,
    private val transferHistory: TransferHistory = TransferHistory(),
    private val folderStatsDispatcher: CoroutineDispatcher = sharedFolderStatsDispatcher,
    private val rememberedSignIn: RememberedSignIn? = if (api is CloudApi) DeviceSignInStore(application) else null,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) : AndroidViewModel(application) {
    private val state = ViewState()
    fun <T> observe(read: () -> T) = state.observe(read)

    private val transfer = LocalTransfers(application, api)
    private class TransferWork(
        val nameFromFirstFile: Boolean,
        val abandon: ((Boolean) -> Unit)?,
        val run: (TransferControl, (TransferProgress) -> Unit) -> Int,
        val durable: DurableDownload? = null,
    ) {
        var keepCheckpoint = false
        var cancelPending = false
    }
    private val transferQueue = linkedMapOf<String, TransferWork>()
    private var activeTransfer: Pair<String, TransferControl>? = null
    private val prefs = application.appearancePreferences()
    private val listingCache = DriveListingCache(application)
    private data class Directory(val owner: String?, val parent: String, val favorites: Boolean)
    private var displayedDirectory by state.property<Directory?>(null)
    val hasDriveListing: Boolean get() = displayedDirectory != null
    private val recentDirectories = object : LruCache<Directory, List<CloudFile>>(4096) {
        override fun sizeOf(key: Directory, value: List<CloudFile>) = 1 + value.size
    }
    private val previewDisk = PreviewDiskCache(application, transferHistory::currentAccountScope)
    val previews = previewProvider ?: CloudPreviews(disk = previewDisk) { fileId -> io("links", "id" to fileId) as? JSONObject }
    val folderCovers = FolderCovers(application, previews, previewDisk, transferHistory::currentAccountScope) { id ->
        val owner = transferHistory.verifiedAccountScope()
        val ticket = listingCache.beginRead()
        val files = readWithRetry("files", "parent" to id).files()
        if (owner != null && owner == transferHistory.verifiedAccountScope()) {
            withContext(Dispatchers.IO) { listingCache.write(owner, id, files, ticket) }
        }
        files
    }
    private var restoreJob: Job? = null
    private var loadJob: Job? = null
    private val searchCatalogs = mutableMapOf<String, Deferred<List<CloudFile>>>()
    private val driveSearchCache = DriveSearchCache(application)
    private data class DriveSearchRead(val owner: String?, val time: Long, val rows: JSONArray?)
    private var driveSearchRead: Deferred<DriveSearchRead>? = null
    private var driveSearchMemory: DriveSearchRead? = null
    private var driveSearchNeedsRefresh = false
    private var driveSearchUnsupportedAt: Long? = null
    private var indexedSearchMetadata by state.property(false)
    private var driveSearchFailure: Pair<Long, String>? = null
    private var folderStatsJob: Job? = null
    private var scanToken: String? = null
    private var indexSync: Deferred<Unit>? = null
    private var indexToken: String? = null
    private var lastIndexSync: Long? = null
    private var visibleIndexStats = emptyMap<String, FolderStats.Complete>()
    private data class IndexFailure(val at: Long, val allowsScopedReads: Boolean)
    private var lastIndexFailure: IndexFailure? = null
    private data class ScopedStats(val value: FolderStats.Complete, var freshUntil: Long)
    private val scopedStats = object : LinkedHashMap<String, ScopedStats>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScopedStats>?) = size > MAX_SCOPED_STATS
    }
    var syncingFolderIndex by state.property(false)
        private set
    private var loadGeneration = 0
    private var sessionGeneration = 0
    private val accountCache = DrawerAccountCache(application)
    private var accountRequest: Deferred<Unit>? = null
    private var accountReadAt: Long? = null
    private var favoritesRequest: Deferred<List<CloudFile>>? = null
    private var favoritesReadAt: Long? = null
    private var favoritesOwner: String? = null
    private var favoritesRevision = 0L
    private var favoritesCacheWrite: Job? = null
    private var signingOut = false
    private val driveRoot get() = Folder("", appString(R.string.drawer_files))
    private val home = WorkspaceLocation(Page.Files)

    var signedIn by state.property(false)
        private set
    var starting by state.property(true)
        private set
    var location by state.property(home)
        private set
    var entries by state.property<List<CloudFile>>(emptyList())
        private set
    var records by state.property<List<JSONObject>>(emptyList())
        private set
    var taskTab by state.property(TaskTab.Cloud)
        private set
    var localTransfers by state.property(transferHistory.load())
        private set
    var drawerAccount by state.property<DrawerAccountData?>(null)
        private set
    var drawerFavorites by state.property<List<CloudFile>>(emptyList())
        private set
    var drawerFavoritesLoaded by state.property(false)
        private set
    var drawerFavoritesLoading by state.property(false)
        private set
    var drawerFavoritesError by state.property<String?>(null)
        private set
    var drawerQuota by state.property(DrawerQuotaPreferences.read(prefs))
        private set
    var selected by state.property<Set<String>>(emptySet())
        private set
    var query by state.property("")
        private set
    var searchStatus by state.property<DriveSearchStatus?>(null)
        private set
    private var folderStatsEnabled by state.property(prefs.getBoolean(DRIVE_FOLDER_STATS, true))
    var presentation by state.property(DrivePresentation.load(prefs).available(folderStatsEnabled))
        private set
    val canScanFolders: Boolean get() = folderStatsEnabled && location.search == null && (page == Page.Files || page == Page.Favorites)
    val hasListingFolderStats: Boolean
        get() = canScanFolders || (folderStatsEnabled && indexedSearchMetadata && location.search?.scope == DriveSearchScope.EntireDrive)
    val listingPresentation: DrivePresentation get() = presentation.available(hasListingFolderStats)
    var loading by state.property(false)
        private set
    var loadError by state.property<String?>(null)
        private set
    var busy by state.property<String?>(null)
        private set
    var notice by state.property<String?>(null)
    var authError by state.property<String?>(null)
        private set
    var canRetrySession by state.property(false)
        private set
    var challenge by state.property<String?>(null)
        private set
    var theme by state.property(prefs.themeMode())
        private set
    var showThumbnails by state.property(prefs.getBoolean(APPEARANCE_THUMBNAILS, true))
        private set
    var coverRevision by state.property(0)
        private set
    private val appearanceChanges = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            APPEARANCE_THEME -> theme = prefs.themeMode()
            APPEARANCE_THUMBNAILS -> showThumbnails = prefs.getBoolean(APPEARANCE_THUMBNAILS, true)
            DrawerQuotaPreferences.VISIBLE_KEY, DrawerQuotaPreferences.DENSITY_KEY -> drawerQuota = DrawerQuotaPreferences.read(prefs)
            DRIVE_FOLDER_STATS -> {
                folderStatsEnabled = prefs.getBoolean(DRIVE_FOLDER_STATS, true)
                if (!folderStatsEnabled) {
                    stopFolderStats()
                    invalidateIndexSync()
                    entries = entries.map { if (it.folderStats != null) it.copy(folderStats = null) else it }
                    applyPresentation(presentation)
                } else if (signedIn && !loading && loadError == null && canScanFolders) refresh()
                if (signedIn && indexedSearchMetadata && location.search?.scope == DriveSearchScope.EntireDrive) {
                    // Folder totals and content timestamps affect both ordering and the page cursor.
                    location.search?.cursors?.clear()
                    refresh()
                }
            }
        }
    }

    val page: Page get() = location.page
    val folder: Folder get() = location.folder?.takeIf { page == Page.Files } ?: driveRoot
    val shareBrowser: ShareBrowser? get() = location.share
    val supportsShareBrowsing: Boolean get() = api.supportsShareBrowsing
    val chosen: List<CloudFile> get() = entries.filter { it.id in selected }
    private var visibleCache: VisibleEntries? = null
    val visibleEntries: List<CloudFile> get() {
        if (location.search != null) return entries
        val options = listingPresentation
        val cached = visibleCache
        if (cached != null && cached.source === entries && cached.query == query &&
            cached.sort == options.sort && cached.descending == options.descending
        ) return cached.result
        return options.sorted(entries, query).also { sorted ->
            visibleCache = VisibleEntries(entries, query, options.sort, options.descending, sorted)
        }
    }

    private data class VisibleEntries(
        val source: List<CloudFile>, val query: String, val sort: FileSort, val descending: Boolean,
        val result: List<CloudFile>,
    )

    fun applyPresentation(options: DrivePresentation) {
        presentation = options.available(folderStatsEnabled)
        presentation.save(prefs)
        location.search?.let {
            it.cursors.clear()
            refresh()
        }
    }

    fun folderCover(file: CloudFile): FolderCoverRequest {
        val selected = folderCovers.selection(file)
        return FolderCoverRequest(file, selected, folderCovers.key(file, selected))
    }

    fun applyFolderCover(folder: CloudFile, selection: List<String>) {
        folderCovers.setSelection(folder, selection)
        coverRevision++
    }

    fun refreshFromUi() {
        if (busy != null) return
        if (location.search != null) {
            discardSearchCatalog()
            location.search?.cursors?.clear()
            refresh(forceSearch = true)
            return
        }
        if (page != Page.Files || presentation.view != DriveView.Icons) {
            refresh(freshStats = true)
            return
        }
        val original = location
        val keys = entries.asSequence().filter(CloudFile::folder).map { folderCover(it).key }.toList()
        viewModelScope.launch {
            folderCovers.invalidate(keys)
            if (location == original) {
                coverRevision++
                refresh(freshStats = true)
            }
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(appearanceChanges)
        viewModelScope.launch {
            DriveSearchCache.cleared.drop(1).collect {
                resetDriveSearch()
                if (location.search?.scope == DriveSearchScope.EntireDrive && query.isNotBlank()) {
                    loadJob?.cancel()
                    loadGeneration++
                    loading = false
                    entries = emptyList()
                    location.search?.cursors?.clear()
                    clearSelection()
                    searchStatus = DriveSearchStatus(0, complete = false)
                }
            }
        }
        restoreSession()
    }

    fun restoreSession() {
        if (restoreJob?.isActive == true || signedIn || busy != null) return
        starting = true
        canRetrySession = false
        authError = null
        restoreJob = viewModelScope.launch {
            try {
                val restored = readWithRetry("init") == true
                if (restored) {
                    restoreDrawerAccount()
                    restoreDurableDownloads()
                }
                signedIn = restored
                if (signedIn) refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                authError = error.message
                canRetrySession = true
            } finally {
                starting = false
            }
        }
    }

    override fun onCleared() {
        stopFolderStats()
        invalidateIndexSync()
        discardTransfers()
        prefs.unregisterOnSharedPreferenceChangeListener(appearanceChanges)
        super.onCleared()
    }

    fun clearAuthError() {
        authError = null
        challenge = null
    }

    fun useLoginForm() {
        canRetrySession = false
        clearAuthError()
    }

    fun login(email: String, password: String, remember: Boolean = false, onSuccess: () -> Unit) {
        if (busy != null || starting) return
        authError = null
        challenge = null
        busy = appString(R.string.model_signing_in)
        viewModelScope.launch {
            try {
                val loginEmail = email.trim()
                var saved = true
                withContext(Dispatchers.IO) {
                    transferHistory.authenticate(loginEmail) {
                        rememberedSignIn?.clear()
                        api.call("login", "email" to loginEmail, "password" to password)
                        if (remember) saved = rememberPassword(loginEmail, password)
                    }
                }
                localTransfers = transferHistory.load()
                recentDirectories.evictAll()
                invalidateIndexSync()
                resetDrawerAccount()
                resetDrawerFavorites()
                resetDriveSearch()
                restoreDrawerAccount()
                restoreDurableDownloads()
                previews.clearMemory()
                folderCovers.clearMemory()
                onSuccess()
                if (!saved) notice = appString(R.string.model_password_save_failed)
                signedIn = true
                canRetrySession = false
                navigate(home)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                authError = error.message
                challenge = (error as? CloudError)?.challenge
            } finally {
                busy = null
            }
        }
    }

    private fun rememberPassword(email: String, password: String): Boolean =
        try {
            rememberedSignIn?.save(email, password)
            true
        } catch (_: Exception) {
            false
        }

    fun logout() = work(appString(R.string.model_signing_out), action = {
        val owner = transferHistory.verifiedAccountScope()
        signingOut = true
        accountRequest?.cancel()
        resetDrawerFavorites()
        resetDriveSearch()
        loadJob?.cancel()
        loadGeneration++
        loading = false
        stopFolderStats()
        invalidateIndexSync()
        PreviewWarmupService.cancelIfRunning(getApplication())
        discardTransfers(keepDurable = false)
        try {
            withContext(Dispatchers.IO) {
                transferHistory.withOwner {
                    rememberedSignIn?.clear()
                    api.call("logout")
                    if (owner != null) {
                        accountCache.clear(owner)
                        driveSearchCache.clear(owner)
                    }
                }
                if (owner != null) runCatching { transfer.durableDownloads.discardOwner(owner) }
            }
        } catch (error: Exception) {
            signingOut = false
            if (error !is CancellationException && (error as? CloudError)?.code != "auth") refresh()
            throw error
        } finally {
            signingOut = false
        }
    }) {
        resetDrawerAccount()
        clearContent()
        recentDirectories.evictAll()
        previews.clearMemory()
        folderCovers.clearMemory()
        localTransfers = emptyList()
        transferHistory.clear()
        taskTab = TaskTab.Cloud
        loadError = null
        location = home
        signedIn = false
        canRetrySession = false
        authError = null
        challenge = null
    }

    fun applyTheme(value: ThemeMode) {
        prefs.edit().putString(APPEARANCE_THEME, value.name).apply()
    }

    fun open(destination: Page) {
        val target = when {
            destination == Page.Files -> home
            destination.isDrawerDestination -> WorkspaceLocation(destination, parent = home)
            else -> WorkspaceLocation(destination, parent = location)
        }
        if (target != location) navigate(target)
    }

    fun selectTaskTab(tab: TaskTab) {
        if (taskTab == tab) return
        taskTab = tab
        if (page == Page.Tasks) refresh()
    }

    fun openCloudTasks() {
        taskTab = TaskTab.Cloud
        if (page == Page.Tasks) refresh() else open(Page.Tasks)
    }

    fun openDrawerFavoriteFolder(file: CloudFile) {
        if (!signedIn || signingOut || favoritesOwner != transferHistory.verifiedAccountScope()) return
        val folder = drawerFavorites.firstOrNull { it.folder && it.id == file.id } ?: return
        val favorites = WorkspaceLocation(Page.Favorites, parent = home)
        navigate(WorkspaceLocation(Page.Files, folder = Folder(folder.id, folder.name), parent = favorites))
    }

    fun openFolder(file: CloudFile) {
        val folder = Folder(file.id, file.name)
        val target = if (page == Page.SharedFiles) {
            location.copy(folder = folder, parent = location)
        } else {
            WorkspaceLocation(Page.Files, folder = folder, parent = location)
        }
        navigate(target)
    }

    fun searchDrive(text: String = "") {
        if (page != Page.Files || location.search != null) return
        if (hasDriveListing && !loading && loadError == null) searchCatalogs[folder.id] = CompletableDeferred(entries)
        navigate(WorkspaceLocation(Page.Files, parent = location, search = DriveSearch(folder, text.trim())))
    }

    fun setSearchScope(scope: DriveSearchScope) {
        val search = location.search ?: return
        if (search.scope == scope) return
        search.scope = scope
        search.cursors.clear()
        entries = emptyList()
        searchStatus = null
        clearSelection()
        refresh()
    }

    fun editQuery(text: String) {
        if (query == text) return
        query = text
        location.search?.let {
            it.term = text
            it.cursors.clear()
            entries = emptyList()
            searchStatus = null
            clearSelection()
            refresh(debounceSearch = text.isNotBlank())
        }
    }

    fun changeSearchPage(forward: Boolean) {
        val search = location.search ?: return
        if (loading || selected.isNotEmpty()) return
        if (forward) {
            if (searchStatus?.hasNextPage != true) return
            search.cursors += entries.lastOrNull() ?: return
        } else if (search.cursors.removeLastOrNull() == null) return
        entries = emptyList()
        searchStatus = null
        refresh()
    }

    private fun discardSearchCatalog() {
        searchCatalogs.values.forEach { it.cancel() }
        searchCatalogs.clear()
    }

    private fun resetDriveSearch() {
        driveSearchRead?.cancel()
        driveSearchRead = null
        driveSearchMemory = null
        driveSearchFailure = null
        driveSearchUnsupportedAt = null
        indexedSearchMetadata = false
        driveSearchNeedsRefresh = false
    }

    private fun navigate(target: WorkspaceLocation) {
        displayedDirectory?.let { recentDirectories.put(it, entries) }
        if (generateSequence(target) { it.parent }.none { it.search != null }) {
            discardSearchCatalog()
            if ((driveSearchMemory?.rows?.length() ?: 0) > 4_000) driveSearchMemory = null
        }
        if (signedIn) loading = true
        location = target
        clearContent()
        query = target.search?.term.orEmpty()
        refresh()
    }

    fun back(): Boolean {
        if (selected.isNotEmpty()) {
            clearSelection()
            return true
        }
        if (location.search == null && query.isNotBlank()) {
            query = ""
            return true
        }
        val parent = location.parent ?: return false
        navigate(parent)
        return true
    }

    fun select(file: CloudFile) {
        selected = if (file.id in selected) selected - file.id else selected + file.id
    }

    fun selectVisible() {
        val ids = visibleEntries.map(CloudFile::id).toSet()
        selected = if (ids.all(selected::contains)) selected - ids else selected + ids
    }

    fun clearSelection() { selected = emptySet() }

    private fun clearContent() {
        displayedDirectory = null
        stopFolderStats()
        entries = emptyList()
        records = emptyList()
        searchStatus = null
        selected = emptySet()
        query = ""
    }

    private fun expireSession(message: String?) {
        if (!signedIn) return
        sessionGeneration++
        resetDrawerAccount()
        resetDrawerFavorites()
        resetDriveSearch()
        loadJob?.cancel()
        loadGeneration++
        loading = false
        recentDirectories.evictAll()
        invalidateIndexSync()
        signedIn = false
        authError = message
        clearContent()
        discardTransfers()
        if (rememberedSignIn == null) return
        starting = true
        viewModelScope.launch {
            try {
                val passwordStored = withContext(Dispatchers.IO) {
                    transferHistory.withOwner {
                        val saved = rememberedSignIn.takeForRetry() ?: return@withOwner null
                        if (!transferHistory.matchesAccount(saved.email)) return@withOwner null
                        api.call("login", "email" to saved.email, "password" to saved.password)
                        rememberPassword(saved.email, saved.password)
                    }
                }
                if (passwordStored != null) {
                    if (!passwordStored) notice = appString(R.string.model_password_resave_failed)
                    authError = null
                    restoreDrawerAccount()
                    signedIn = true
                    query = location.search?.term.orEmpty()
                    restoreDurableDownloads()
                    refresh()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                authError = (error as? CloudError)?.message ?: message
                challenge = (error as? CloudError)?.challenge
            } finally {
                starting = false
            }
        }
    }

    fun loadDrawerAccount() {
        if (accountRequest?.isActive == true) return
        val request = requestAccount() ?: return
        viewModelScope.launch {
            try {
                request.await()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request === accountRequest && error is CloudError && error.code == "auth") expireSession(error.message)
            }
        }
    }

    private fun requestAccount(force: Boolean = false): Deferred<Unit>? {
        if (!signedIn || signingOut) return null
        accountRequest?.takeIf { it.isActive }?.let { return it }
        if (!force && accountReadAt?.let { elapsedMillis() - it < DRAWER_REFRESH_INTERVAL_MS } == true) return null
        val owner = transferHistory.verifiedAccountScope()
        val generation = sessionGeneration
        return viewModelScope.async<Unit> {
            val response = readWithRetry("account") as? JSONObject ?: error(appString(R.string.ui_load_failed))
            if (!signedIn || signingOut || generation != sessionGeneration || owner != transferHistory.verifiedAccountScope()) return@async
            val profile = DrawerAccountData.from(response)
            drawerAccount = profile
            accountReadAt = elapsedMillis()
            if (owner != null) withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                runCatching {
                    transferHistory.withOwner {
                        context.ensureActive()
                        if (owner == transferHistory.verifiedAccountScope()) accountCache.save(owner, profile)
                    }
                }
            }
        }.also { accountRequest = it }
    }

    private suspend fun restoreDrawerAccount() {
        val owner = transferHistory.verifiedAccountScope() ?: return
        val profile = withContext(Dispatchers.IO) { accountCache.load(owner) }
        if (owner == transferHistory.verifiedAccountScope()) drawerAccount = profile
    }

    private fun resetDrawerAccount() {
        accountRequest?.cancel()
        accountRequest = null
        accountReadAt = null
        drawerAccount = null
    }

    fun loadDrawerFavorites() {
        if (signedIn && !signingOut) requestFavorites(force = false)
    }

    private fun resetDrawerFavorites() {
        favoritesRevision++
        favoritesRequest?.cancel()
        favoritesRequest = null
        favoritesReadAt = null
        favoritesOwner = null
        favoritesCacheWrite = null
        drawerFavorites = emptyList()
        drawerFavoritesLoading = false
        drawerFavoritesLoaded = false
        drawerFavoritesError = null
    }

    private fun publishFavorites(files: List<CloudFile>) {
        drawerFavorites = files
        drawerFavoritesLoaded = true
        if (page == Page.Favorites) {
            displayedDirectory = Directory(transferHistory.verifiedAccountScope(), "", true)
            entries = if (canScanFolders) withKnownStats(files) else files
        }
    }

    private fun requestFavorites(force: Boolean): Deferred<List<CloudFile>>? {
        val owner = transferHistory.verifiedAccountScope()
        if (owner != favoritesOwner) {
            if (page == Page.Favorites && drawerFavoritesLoaded) {
                displayedDirectory = null
                entries = emptyList()
            }
            resetDrawerFavorites()
            favoritesOwner = owner
        }
        favoritesRequest?.takeIf { it.isActive }?.let { return it }
        if (!force && favoritesReadAt?.let { elapsedMillis() - it < DRAWER_REFRESH_INTERVAL_MS } == true) return null
        val revision = favoritesRevision
        val ticket = listingCache.beginRead()
        fun current() = revision == favoritesRevision && signedIn && !signingOut && owner == transferHistory.verifiedAccountScope()
        drawerFavoritesLoading = true
        drawerFavoritesError = null
        return viewModelScope.async {
            try {
                if (!drawerFavoritesLoaded && owner != null) {
                    favoritesCacheWrite?.join()
                    val saved = withContext(Dispatchers.IO) { listingCache.read(owner, "", favorites = true) }
                    if (saved != null && current()) publishFavorites(saved)
                }
                val result = readWithRetry("starred") as? JSONArray ?: error(appString(R.string.ui_load_failed))
                val files = withContext(Dispatchers.Default) {
                    (0 until result.length()).map { index ->
                        ensureActive()
                        CloudFile.from(result.getJSONObject(index)).copy(starred = true)
                    }
                }
                if (!current()) throw CancellationException()
                publishFavorites(files)
                favoritesReadAt = elapsedMillis()
                if (owner != null) withContext(Dispatchers.IO) {
                    listingCache.write(owner, "", files, ticket, favorites = true)
                }
                files
            } catch (error: Exception) {
                if (error !is CancellationException && current()) {
                    favoritesReadAt = null
                    drawerFavoritesError = error.message ?: appString(R.string.ui_load_failed)
                    if (error is CloudError && error.code == "auth") expireSession(error.message)
                }
                throw error
            } finally {
                if (revision == favoritesRevision) drawerFavoritesLoading = false
            }
        }.also { favoritesRequest = it }
    }

    private suspend fun refreshFavorites(fresh: Boolean) {
        val request = requestFavorites(force = fresh)
        if (drawerFavoritesLoaded) publishFavorites(drawerFavorites)
        val files = request?.await() ?: drawerFavorites
        displayDriveListing(files, fresh)
    }

    fun refresh(freshStats: Boolean = false, debounceSearch: Boolean = false, forceSearch: Boolean = false): Job? {
        if (!signedIn) return null
        loadJob?.cancel()
        stopFolderStats()
        if (freshStats && canScanFolders) scopedStats.values.forEach { it.freshUntil = 0 }
        val generation = ++loadGeneration
        val currentPage = page
        val parent = folder.id
        val share = shareBrowser
        val shareParent = location.folder?.id.orEmpty()
        loadError = null
        if (currentPage == Page.Tasks && taskTab != TaskTab.Cloud) {
            loading = false
            return null
        }
        loading = true
        loadJob = viewModelScope.launch {
            try {
                when (currentPage) {
                    Page.Favorites -> refreshFavorites(freshStats)
                    Page.Files -> if (location.search != null) {
                        refreshDriveSearch(debounceSearch, forceSearch)
                    } else refreshDriveDirectory(parent, freshStats)
                    Page.Trash -> entries = readWithRetry("trash").files()
                    Page.Tasks -> records = readWithRetry("tasks").objects()
                    Page.Shares -> records = readWithRetry("shares").objects()
                    Page.Account -> requestAccount(force = freshStats)?.await()
                    Page.Recent -> records = readWithRetry("events").objects()
                    Page.SharedFiles -> if (share != null) {
                        entries = readWithRetry(
                            "share_folder", "share" to share.id, "token" to share.token,
                            "parent" to shareParent,
                        ).files()
                    }
                }
                selected = selected.intersect(entries.map(CloudFile::id).toSet())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation != loadGeneration) return@launch
                if (error is CloudError && error.code == "auth") {
                    expireSession(error.message)
                } else {
                    loadError = error.message
                    failPendingStats()
                }
            } finally {
                if (generation == loadGeneration) loading = false
            }
        }
        return loadJob
    }

    private suspend fun refreshDriveSearch(debounce: Boolean, force: Boolean) {
        val search = location.search ?: return
        val text = query.trim()
        if (text.isEmpty()) {
            entries = emptyList()
            searchStatus = null
            return
        }
        val owner = transferHistory.verifiedAccountScope()
        if (search.scope == DriveSearchScope.CurrentFolder) {
            val parent = search.origin.id
            val catalog = searchCatalogs[parent]?.takeUnless { it.isCancelled || force } ?: viewModelScope.async(Dispatchers.IO) {
                if (!force && owner != null) listingCache.read(owner, parent)?.let { return@async it }
                readWithRetry("files", "parent" to parent).files()
            }.also { searchCatalogs[parent] = it }
            publishSearch(catalog.await().searchMatches(text, listingPresentation, search.cursors.lastOrNull()), complete = true)
            return
        }

        val indexUnsupported = driveSearchUnsupportedAt?.let { SystemClock.elapsedRealtime() - it < DRIVE_SEARCH_FRESH_MS } == true
        indexedSearchMetadata = (!indexUnsupported || force) && withContext(Dispatchers.IO) { api.supportsDriveSearch }
        if (indexedSearchMetadata) {
            try {
                indexedDriveSearch(search, owner, text, listingPresentation, debounce, force)
                driveSearchUnsupportedAt = null
                return
            } catch (error: CloudError) {
                // Some accounts/servers cannot supply the stable identity or watermark required by the index.
                if (error.code != "index_unavailable") throw error
                driveSearchUnsupportedAt = SystemClock.elapsedRealtime()
                if (search.indexedAt != null) search.cursors.clear()
                search.indexedAt = null
                indexedSearchMetadata = false
            }
        }

        val options = listingPresentation
        val after = search.cursors.lastOrNull()
        val memory = driveSearchMemory?.takeIf { it.owner == owner }
        val memoryMatches = memory?.rows?.searchMatches(text, options, after)
        val saved = if (memoryMatches == null) readDriveSearchCache(owner, text, options, after) else null
        val fullMatches = memoryMatches ?: saved?.matches
        val time = memory?.time ?: saved?.time ?: 0
        val fresh = fullMatches != null && System.currentTimeMillis() - time in 0 until DRIVE_SEARCH_FRESH_MS
        if (!force && !driveSearchNeedsRefresh && fresh) {
            publishSearch(checkNotNull(fullMatches), complete = true, cached = saved != null, sessionOnly = memoryMatches != null)
            return
        }
        val recentFailure = driveSearchFailure?.takeIf {
            !force && SystemClock.elapsedRealtime() - it.first < 15_000L
        }?.second
        coroutineScope {
            var available = fullMatches ?: DriveSearchMatches(emptyList(), 0, false)
            fun showAvailable(failure: String?) {
                publishSearch(available, complete = fullMatches != null, cached = saved != null,
                    updating = failure == null, failure = failure, sessionOnly = memoryMatches != null)
            }

            // Local discovery and cloud refresh run independently. A complete snapshot always wins.
            val local = if (fullMatches == null) launch {
                availableDriveMatches(search, owner, text, options, after) { found ->
                    available = found
                    showAvailable(recentFailure)
                }
            } else null
            if (fullMatches != null) showAvailable(recentFailure)
            if (recentFailure != null) return@coroutineScope

            if (debounce) delay(350)
            try {
                val update = requestDriveSearch(owner).await()
                val matches = update.rows?.searchMatches(text, options, after)
                    ?: readDriveSearchCache(owner, text, options, after)?.matches ?: error(appString(R.string.search_update_failed))
                local?.cancel()
                publishSearch(matches, complete = true, sessionOnly = update.rows != null)
            } catch (error: Exception) {
                if (error is CancellationException || (error as? CloudError)?.code == "auth") throw error
                val message = error.message ?: appString(R.string.search_update_failed)
                driveSearchFailure = SystemClock.elapsedRealtime() to message
                local?.join()
                showAvailable(message)
            }
        }
    }

    private suspend fun indexedDriveSearch(search: DriveSearch, owner: String?, text: String, options: DrivePresentation,
        debounce: Boolean, force: Boolean) = coroutineScope {
        var after = search.cursors.lastOrNull()
        val generation = sessionGeneration
        val revision = DriveSearchCache.cleared.value
        val withFolderStats = hasListingFolderStats
        fun checkOwner() {
            if (generation != sessionGeneration || !signedIn || signingOut || owner != transferHistory.verifiedAccountScope() ||
                revision != DriveSearchCache.cleared.value) throw CancellationException()
        }
        suspend fun readPage(cursor: CloudFile?): DriveSearchCachedMatches? =
            withContext(Dispatchers.IO) { api.readIndexedMatches(text, options, cursor, withFolderStats) }.also { checkOwner() }
        suspend fun cachedPage(refreshed: Boolean = false): DriveSearchCachedMatches? {
            var page = readPage(if (refreshed) null else after) ?: return null
            if (after != null && (refreshed || search.indexedAt != page.time)) {
                // A cursor belongs to its sorted snapshot. Restart at page one if it has changed.
                if (!refreshed) page = readPage(null) ?: return null
                after = null
                search.cursors.clear()
            }
            search.indexedAt = page.time
            return page
        }

        var available = DriveSearchMatches(emptyList(), 0, false)
        var complete = false
        var sessionOnly = false
        var failure = driveSearchFailure?.takeIf { !force && SystemClock.elapsedRealtime() - it.first < 15_000L }?.second
        fun show(updating: Boolean) {
            checkOwner()
            publishSearch(available, complete, cached = complete && !sessionOnly, updating = updating, failure = failure,
                sessionOnly = sessionOnly)
        }
        var local: Job? = null
        suspend fun showFallback() {
            if (complete || local != null) return
            val memory = driveSearchMemory?.takeIf { it.owner == owner }?.rows?.searchMatches(text, options, after)
            val matches = memory ?: readDriveSearchCache(owner, text, options, after)?.matches
            checkOwner()
            if (matches != null) {
                available = matches
                complete = true
                sessionOnly = memory != null
            } else {
                local = launch {
                    availableDriveMatches(search, owner, text, options, after) {
                        if (!complete) {
                            available = it
                            show(updating = failure == null)
                        }
                    }
                }
            }
        }
        try {
            val cached = cachedPage()
            if (cached == null) {
                // Existing installs may have a complete JSONL snapshot while Rust backfills filenames.
                showFallback()
            } else {
                available = cached.matches
                complete = true
                if (!force && !driveSearchNeedsRefresh && System.currentTimeMillis() - cached.time in 0 until DRIVE_SEARCH_FRESH_MS) {
                    failure = null
                    show(updating = false)
                    return@coroutineScope
                }
            }
            show(updating = failure == null)
            if (failure != null) return@coroutineScope
            if (debounce) delay(350)
            synchronizeIndex().await()
            val updated = cachedPage(refreshed = true) ?: error(appString(R.string.search_update_failed))
            local?.cancel()
            driveSearchNeedsRefresh = false
            driveSearchFailure = null
            publishSearch(updated.matches, complete = true)
        } catch (error: Exception) {
            if (error is CancellationException || (error as? CloudError)?.code in setOf("auth", "index_unavailable")) throw error
            showFallback()
            failure = error.message ?: appString(R.string.search_update_failed)
            driveSearchFailure = SystemClock.elapsedRealtime() to checkNotNull(failure)
            local?.join()
            show(updating = false)
        }
    }

    private suspend fun List<CloudFile>.searchMatches(text: String, options: DrivePresentation, after: CloudFile?): DriveSearchMatches =
        withContext(Dispatchers.Default) {
            val matching = DriveSearchMatcher(text, options, after)
            for (file in this@searchMatches) {
                ensureActive()
                matching.accept(file)
            }
            matching.matches()
        }

    private suspend fun JSONArray.searchMatches(text: String, options: DrivePresentation, after: CloudFile?): DriveSearchMatches =
        withContext(Dispatchers.Default) {
            val matching = DriveSearchMatcher(text, options, after)
            for (index in 0 until length()) {
                ensureActive()
                val row = getJSONObject(index)
                if (row.getString("name").contains(text, ignoreCase = true)) matching.accept(CloudFile.from(row))
            }
            matching.matches()
        }

    private suspend fun availableDriveMatches(search: DriveSearch, owner: String?, text: String, options: DrivePresentation,
        after: CloudFile?, publish: (DriveSearchMatches) -> Unit) {
        val memory = recentDirectories.snapshot().filterKeys { it.owner == owner && !it.favorites }
        val origin = searchCatalogs[search.origin.id]?.takeIf { it.isCompleted && !it.isCancelled }?.await()
        withContext(Dispatchers.IO) {
            val visited = hashSetOf<String>()
            val pending = ArrayDeque<String>()
            fun enqueue(id: String) {
                if (visited.add(id)) pending.addLast(id)
            }
            enqueue(search.origin.id)
            enqueue("")
            memory.keys.toList().asReversed().forEach { enqueue(it.parent) }

            val matching = DriveSearchMatcher(text, options, after)
            val matchedIds = hashSetOf<String>()
            var reportedCount = 0
            var lastReport = 0L
            while (pending.isNotEmpty()) {
                ensureActive()
                val parent = pending.removeFirst()
                val rows = origin.takeIf { parent == search.origin.id } ?: memory[Directory(owner, parent, false)]
                if (rows != null) {
                    for (file in rows) {
                        ensureActive()
                        if (file.folder) enqueue(file.id)
                        if (file.name.contains(text, ignoreCase = true) && matchedIds.add(file.id)) matching.accept(file)
                    }
                } else if (owner != null) {
                    val cached = listingCache.searchDirectory(owner, parent, text) { ensureActive() } ?: continue
                    cached.folders.forEach(::enqueue)
                    for (file in cached.matches) if (matchedIds.add(file.id)) matching.accept(file)
                }
                val now = SystemClock.elapsedRealtime()
                if (matchedIds.size != reportedCount && (reportedCount == 0 || now - lastReport >= 100L)) {
                    val snapshot = matching.matches()
                    withContext(Dispatchers.Main) { publish(snapshot) }
                    reportedCount = matchedIds.size
                    lastReport = now
                }
            }
            if (reportedCount == 0 || reportedCount != matchedIds.size) {
                val snapshot = matching.matches()
                withContext(Dispatchers.Main) { publish(snapshot) }
            }
        }
    }

    private suspend fun readDriveSearchCache(owner: String?, text: String, options: DrivePresentation,
        after: CloudFile?): DriveSearchCachedMatches? =
        if (owner == null) null else withContext(Dispatchers.IO) {
            driveSearchCache.read(owner, text, options, after) { ensureActive() }
        }

    private fun requestDriveSearch(owner: String?): Deferred<DriveSearchRead> {
        driveSearchRead?.takeIf { it.isActive }?.let { return it }
        val generation = sessionGeneration
        val revision = DriveSearchCache.cleared.value
        return viewModelScope.async(Dispatchers.IO) {
            // API 1 exposes the native wildcard only as one blocking request. Moving away
            // cancels the screen's wait. A finished read may still warm the same account's cache.
            val response = api.call("files", "parent" to "*") as? JSONArray ?: error(appString(R.string.search_update_failed))
            ensureActive()
            val time = System.currentTimeMillis()
            val saved = owner != null && transferHistory.withOwner {
                owner == transferHistory.verifiedAccountScope() && driveSearchCache.write(owner, response, time, revision) { ensureActive() }
            }
            val rows = if (saved) null else response
            val update = DriveSearchRead(owner, time, rows)
            withContext(Dispatchers.Main) {
                if (!signedIn || signingOut || generation != sessionGeneration || revision != DriveSearchCache.cleared.value ||
                    owner != transferHistory.verifiedAccountScope()) throw CancellationException()
                driveSearchNeedsRefresh = false
                driveSearchFailure = null
                val inSearch = generateSequence(location) { it.parent }.any { it.search != null }
                driveSearchMemory = update.takeIf { rows != null && (inSearch || rows.length() <= 4_000) }
            }
            update
        }.also { request ->
            driveSearchRead = request
            request.invokeOnCompletion {
                viewModelScope.launch { if (driveSearchRead === request) driveSearchRead = null }
            }
        }
    }

    private fun publishSearch(matches: DriveSearchMatches, complete: Boolean, cached: Boolean = false,
        updating: Boolean = false, failure: String? = null, sessionOnly: Boolean = false) {
        entries = matches.files
        searchStatus = DriveSearchStatus(matches.count, complete, cached, updating, failure,
            location.search?.cursors?.size ?: 0, matches.hasNext, sessionOnly)
    }

    private suspend fun refreshDriveDirectory(parent: String, freshStats: Boolean) = coroutineScope {
        val owner = transferHistory.verifiedAccountScope()
        val directory = Directory(owner, parent, false)
        val ticket = listingCache.beginRead()
        if (displayedDirectory != directory) {
            recentDirectories.get(directory)?.let { snapshot ->
                entries = if (canScanFolders) snapshot else snapshot.map { it.copy(folderStats = null) }
                displayedDirectory = directory
            }
        }
        // Disk and network race. Once the server replies, a slower local read cannot replace it.
        val diskRead = if (displayedDirectory == null && owner != null) launch {
            val saved = withContext(Dispatchers.IO) { listingCache.read(owner, parent) } ?: return@launch
            if (owner != transferHistory.verifiedAccountScope()) return@launch
            entries = if (canScanFolders) withKnownStats(saved) else saved
            displayedDirectory = directory
            val folders = saved.filter(CloudFile::folder).map(CloudFile::id)
            if (canScanFolders && folders.isNotEmpty() && api.supportsFolderIndex) {
                val totals = try { cachedStats(folders) } catch (_: CloudError) { null }
                if (totals != null && canScanFolders && owner == transferHistory.verifiedAccountScope()) {
                    visibleIndexStats = totals
                    entries = withKnownStats(saved)
                }
            }
        } else null
        val files = try {
            readWithRetry("files", "parent" to parent).files()
        } catch (error: CloudError) {
            // Offline refresh can fail before the local read completes. Keep the last usable listing.
            if (error.code != "auth") diskRead?.join()
            throw error
        }
        diskRead?.cancel()
        currentCoroutineContext().ensureActive()
        if (owner != transferHistory.verifiedAccountScope()) return@coroutineScope
        val persist = owner?.let {
            // Keep the snapshot even if the user immediately opens an item and cancels this refresh.
            viewModelScope.launch(Dispatchers.IO) { listingCache.write(owner, parent, files, ticket) }
        }
        displayedDirectory = directory
        displayDriveListing(files, freshStats)
        persist?.join()
        Unit
    }

    private suspend fun displayDriveListing(listing: List<CloudFile>, fresh: Boolean) {
        val ids = listing.filter(CloudFile::folder).map(CloudFile::id)
        if (!canScanFolders || ids.isEmpty()) {
            entries = listing
            return
        }
        // File names are usable immediately, including when the local statistics index is busy.
        visibleIndexStats = entries.mapNotNull { file ->
            (file.folderStats as? FolderStats.Complete)?.let { file.id to it }
        }.toMap()
        entries = withKnownStats(listing)
        val indexed = api.supportsFolderIndex
        val syncBeforeRead = lastIndexSync
        val snapshot = if (indexed) cachedStats(ids) else null
        val cached = if (indexed && lastIndexSync != syncBeforeRead) cachedStats(ids) else snapshot
        if (!canScanFolders) {
            entries = listing
            return
        }
        // Replace provisional totals with the latest committed statistics, including zero-byte folders.
        visibleIndexStats = cached.orEmpty()
        entries = withKnownStats(listing)
        if (cached == null) {
            folderStatsJob = viewModelScope.launch { scanMissingFolders(fresh && !indexed) }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!fresh && indexSync?.isActive != true) {
            val failure = lastIndexFailure
            if (failure != null && now - failure.at < INDEX_CHECK_INTERVAL_MS) {
                if (failure.allowsScopedReads) folderStatsJob = viewModelScope.launch { scanMissingFolders(false) }
                else failPendingStats()
                return
            }
            if (lastIndexSync?.let { now - it < INDEX_CHECK_INTERVAL_MS } == true) {
                folderStatsJob = viewModelScope.launch { scanMissingFolders(false) }
                return
            }
        }
        val generation = loadGeneration
        syncingFolderIndex = true
        folderStatsJob = viewModelScope.launch {
            try {
                synchronizeIndex().await()
                val updated = cachedStats(ids)
                if (updated != null) {
                    visibleIndexStats = updated
                    entries = withKnownStats(entries)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (loadGeneration != generation) return@launch
                if (error is CloudError && error.code == "auth") {
                    expireSession(error.message)
                    return@launch
                }
                if (fresh) notice = appString(R.string.model_stats_sync_failed)
                // A failed network sync cannot be repaired by sending one more request per child.
                if (!error.allowsScopedReads()) {
                    failPendingStats()
                    return@launch
                }
            } finally {
                if (generation == loadGeneration) syncingFolderIndex = false
            }
            // Some shared folders, or servers without an index watermark, require scoped reads.
            scanMissingFolders(fresh = false)
        }
    }

    private fun withKnownStats(listing: List<CloudFile>) = listing.map {
        if (it.folder) it.copy(folderStats = visibleIndexStats[it.id] ?: scopedStats[it.id]?.value ?: FolderStats.Pending) else it
    }

    fun reloadCachedFolderStats() {
        if (!signedIn || !canScanFolders || !api.supportsFolderIndex) return
        val generation = loadGeneration
        viewModelScope.launch {
            if (loading) observe { loading }.first { !it }
            if (loadGeneration != generation || !signedIn || !canScanFolders) return@launch
            val ids = entries.filter(CloudFile::folder).map(CloudFile::id)
            if (ids.isEmpty()) return@launch
            val cached = try { cachedStats(ids) } catch (error: CloudError) {
                if (loadGeneration == generation && signedIn && error.code == "auth") expireSession(error.message)
                return@launch
            } ?: return@launch
            if (loadGeneration != generation || !signedIn || !canScanFolders) return@launch
            visibleIndexStats = cached
            entries = entries.map { file -> cached[file.id]?.let { file.copy(folderStats = it) } ?: file }
        }
    }

    private suspend fun cachedStats(ids: List<String>): Map<String, FolderStats.Complete>? = try {
        withContext(Dispatchers.IO) {
            val values = api.call("folder_stats_cached", "ids" to ids) as JSONObject
            buildMap {
                for (id in ids) values.optJSONObject(id)?.let { put(id, FolderStats.Complete.from(it)) }
            }
        }
    } catch (error: Exception) {
        if (error is CancellationException || (error is CloudError && error.code == "auth")) throw error
        null // An older saved session may have no stable account ID for a persistent index.
    }

    private fun Exception.allowsScopedReads(): Boolean =
        this is CloudError && (code == "request" || code == "index_unavailable")

    private fun synchronizeIndex(): Deferred<Unit> {
        indexSync?.takeIf { it.isActive }?.let { return it }
        val token = UUID.randomUUID().toString()
        api.call("folder_stats_begin", "scan" to token, "root" to (page == Page.Files && folder.id.isEmpty()))
        indexToken = token
        // Owned by the account, not the view. Navigating cancels only the awaiting coroutine.
        return viewModelScope.async {
            try {
                withContext(folderStatsDispatcher) { api.call("folder_index_sync", "scan" to token, "report_unavailable" to api.supportsDriveSearch) }
                lastIndexSync = SystemClock.elapsedRealtime()
                lastIndexFailure = null
            } catch (error: Exception) {
                if (error !is CancellationException && indexToken == token) {
                    lastIndexFailure = IndexFailure(SystemClock.elapsedRealtime(), error.allowsScopedReads())
                }
                throw error
            } finally {
                if (indexToken == token) {
                    indexToken = null
                    runCatching { api.call("folder_stats_cancel", "scan" to token) }
                }
            }
        }.also { indexSync = it }
    }

    private fun invalidateIndexSync() {
        discardSearchCatalog()
        driveSearchRead?.cancel()
        driveSearchNeedsRefresh = true
        indexToken?.let { token -> runCatching { api.call("folder_stats_cancel", "scan" to token) } }
        indexToken = null
        indexSync?.cancel()
        indexSync = null
        lastIndexSync = null
        lastIndexFailure = null
        scopedStats.clear()
    }

    private fun failPendingStats() {
        entries = entries.map { if (it.folderStats == FolderStats.Pending) it.copy(folderStats = FolderStats.Failed) else it }
    }

    private suspend fun scanMissingFolders(fresh: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val folders = entries.filter { it.folder && it.id !in visibleIndexStats && (scopedStats[it.id]?.freshUntil ?: 0) <= now }
        if (folders.isEmpty()) return
        val token = UUID.randomUUID().toString()
        val generation = loadGeneration
        // Registration and cancellation only touch the native token table, never the network.
        try {
            api.call("folder_stats_begin", "scan" to token, "root" to (page == Page.Files && folder.id.isEmpty()),
                "fresh" to fresh)
        } catch (_: Exception) {
            failPendingStats()
            return
        }
        scanToken = token
        if (folders.any { it.folderStats is FolderStats.Complete }) syncingFolderIndex = true
        try {
            for (file in folders) {
                if (file.id in visibleIndexStats) continue
                val stats = try {
                    withContext(folderStatsDispatcher) {
                        val value = api.call("folder_stats", "id" to file.id, "scan" to token) as JSONObject
                        FolderStats.Complete.from(value)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (loadGeneration != generation || scanToken != token) return
                    if (error is CloudError && error.code == "auth") {
                        expireSession(error.message)
                        return
                    }
                    FolderStats.Failed
                }
                if (scanToken != token) return
                if (file.id in visibleIndexStats) continue
                if (stats is FolderStats.Complete) {
                    scopedStats[file.id] = ScopedStats(stats, SystemClock.elapsedRealtime() + INDEX_CHECK_INTERVAL_MS)
                }
                entries = entries.map {
                    if (it.id == file.id && (stats is FolderStats.Complete || it.folderStats !is FolderStats.Complete))
                        it.copy(folderStats = stats) else it
                }
            }
        } finally {
            if (scanToken == token) releaseScan(token)
            if (generation == loadGeneration) syncingFolderIndex = false
        }
    }

    private fun releaseScan(token: String) {
        scanToken = null
        runCatching { api.call("folder_stats_cancel", "scan" to token) }
    }

    private fun stopFolderStats() {
        visibleIndexStats = emptyMap()
        syncingFolderIndex = false
        scanToken?.let(::releaseScan)
        folderStatsJob?.cancel()
        folderStatsJob = null
    }

    suspend fun readSharedPreview(share: ShareBrowser, fileId: String): JSONObject? =
        (readWithRetry("share_preview", "share" to share.id, "token" to share.token, "id" to fileId) as? JSONObject)
            ?.takeIf { it.stringOrNull("id") == fileId }

    fun openShare(url: String, code: String) {
        val origin = location
        command(
            appString(R.string.model_opening_share), "share_open", "url" to url, "code" to code, refresh = false,
        ) { response ->
            val value = response as JSONObject
            val share = ShareBrowser(value.getString("share"), value.getString("token"))
            loadJob?.cancel()
            loadGeneration++
            displayedDirectory?.let { recentDirectories.put(it, entries) }
            clearContent()
            location = WorkspaceLocation(Page.SharedFiles, share = share, parent = origin)
            loading = false
            loadError = null
            entries = value.optJSONArray("files").files()
        }
    }

    private fun favoritesAfterCommand(operation: String, arguments: Map<String, Any?>, confirmed: Boolean) {
        if (operation !in setOf("star", "unstar", "rename", "remove", "restore", "destroy", "empty_trash") &&
            !(operation == "delete_tasks" && arguments["delete_files"] == true)) return
        val ids = (arguments["ids"] as? List<*>)?.toSet().orEmpty()
        val name = arguments["name"] as? String
        val owner = transferHistory.verifiedAccountScope()
        if (owner != favoritesOwner) {
            resetDrawerFavorites()
            favoritesOwner = owner
        }
        val added = if (operation == "star") entries.filter { it.id in ids }.map { it.copy(starred = true) } else emptyList()
        fun update(rows: List<CloudFile>): List<CloudFile>? = if (!confirmed) null else when (operation) {
            "star" -> (rows + added).distinctBy { it.id }
            "unstar", "remove", "destroy" -> rows.filterNot { it.id in ids }
            "rename" -> rows.map {
                if (it.id == arguments["id"] && name != null) it.copy(name = name) else it
            }
            else -> null
        }
        val snapshot = if (drawerFavoritesLoaded) update(drawerFavorites) else null
        favoritesRevision++
        favoritesRequest?.cancel()
        favoritesRequest = null
        favoritesReadAt = null
        drawerFavoritesLoading = false
        drawerFavoritesError = null
        if (snapshot != null) publishFavorites(snapshot)
        if (owner == null) return
        val ticket = listingCache.beginRead()
        val pending = favoritesCacheWrite
        val needsPrevious = !drawerFavoritesLoaded && confirmed
        favoritesCacheWrite = viewModelScope.launch(Dispatchers.IO) {
            pending?.join()
            val updated = if (needsPrevious) listingCache.read(owner, "", favorites = true)?.let(::update) else snapshot
            listingCache.write(owner, "", updated, ticket, favorites = true)
        }
    }

    fun command(
        label: String,
        operation: String,
        vararg args: Pair<String, Any?>,
        done: String? = null,
        refresh: Boolean = true,
        complete: (Any?) -> Unit = {},
    ) = work(label, {
        try {
            io(operation, *args)
        } catch (error: CloudError) {
            if (error.code == "pending") favoritesAfterCommand(operation, args.toMap(), confirmed = false)
            throw error
        }
    }, complete = { value ->
        favoritesAfterCommand(operation, args.toMap(), confirmed = true)
        complete(value)
        if (refresh) {
            invalidateIndexSync()
            clearSelection()
            refresh()
        }
        if (done != null) notice = done
    })

    fun upload(uris: List<Uri>, destination: Folder) = runTransfer(
        TaskTab.Upload, if (uris.size == 1) appString(R.string.model_upload_files) else appString(R.string.model_file_count, uris.size),
        total = uris.size, useFirstFilename = uris.size == 1,
        previewUri = uris.singleOrNull(),
    ) { control, report -> transfer.upload(uris, destination, control, report) }

    fun <T : Any> finishTransferPicker(selection: T?, complete: (T) -> Unit) {
        viewModelScope.launch {
            observe { starting }.first { !it }
            when {
                selection == null -> notice = appString(R.string.model_picker_lost)
                !signedIn -> notice = appString(R.string.model_transfer_not_started)
                busy != null -> notice = appString(R.string.model_transfer_busy)
                else -> complete(selection)
            }
        }
    }

    fun uploadTree(uri: Uri, target: Folder) = runTransfer(
        TaskTab.Upload, appString(R.string.model_upload_folder), useFirstFilename = true,
    ) { control, report -> transfer.uploadTree(uri, target, control, report) }

    fun export(file: CloudFile, uri: Uri) = queueDownload(listOf(file), uri, tree = false)

    fun transferAccountScope(): String? = transferHistory.currentAccountScope()

    fun exportPicked(file: CloudFile, uri: Uri, account: String?) {
        if (account != transferAccountScope()) {
            notice = appString(R.string.model_account_changed)
            return
        }
        val owner = transferHistory.verifiedAccountScope()
        if (owner == null || owner != account) {
            export(file, uri)
            return
        }
        viewModelScope.launch {
            var acquired: DurableDownload? = null
            try {
                withContext(Dispatchers.IO) {
                    acquired = runCatching {
                        transferHistory.withOwner { transfer.durableDownloads.create(owner, file, uri) }
                    }.getOrNull()
                }
                if (!signedIn || owner != transferHistory.verifiedAccountScope()) {
                    notice = appString(R.string.model_account_changed)
                    return@launch
                }
                queueDownload(listOf(file), uri, tree = false, durable = acquired)
                acquired = null
            } finally {
                acquired?.let { record ->
                    val keep = signedIn && owner == transferHistory.verifiedAccountScope()
                    releaseTransfer({ retain -> transfer.durableDownloads.release(record, retain) }, keep)
                }
            }
        }
    }

    fun exportTree(files: List<CloudFile>, uri: Uri) = queueDownload(files, uri, tree = true)

    private fun queueDownload(files: List<CloudFile>, uri: Uri, tree: Boolean, durable: DurableDownload? = null) {
        val download = transfer.Download(files, uri, tree, durable)
        val preview = files.singleOrNull()?.takeUnless(CloudFile::folder)?.let { file ->
            val source = file.takeIf { it.thumbnail != null }
                ?: entries.firstOrNull { it.id == file.id && !it.folder && it.thumbnail != null }
            source?.let { PreviewFile(it, canRefreshLink = false) }
        }
        runTransfer(TaskTab.Download, files.singleOrNull()?.name ?: appString(R.string.model_item_count, files.size),
            total = download.total, abandon = download::abandon, durable = durable,
            thumbnail = preview, action = download::run)
    }

    private suspend fun restoreDurableDownloads() {
        val owner = transferHistory.verifiedAccountScope() ?: return
        var recovered = emptyList<DurableDownloads.Recovered>()
        try {
            withContext(Dispatchers.IO) {
                recovered = runCatching { transferHistory.withOwner { transfer.durableDownloads.recover(owner) } }
                    .getOrDefault(emptyList())
            }
            if (recovered.isEmpty() || owner != transferHistory.verifiedAccountScope()) return
            val restored = recovered.map { item ->
                val download = item.transfer
                val resume = item.status == TransferStatus.Paused
                if (resume) {
                    val work = transfer.Download(listOf(download.file), download.destination, tree = false, durable = download)
                    transferQueue[download.id] = TransferWork(false, work::abandon, work::run, download)
                }
                LocalTransfer(download.id, TaskTab.Download, download.file.name, download.startedAt,
                    TransferProgress(TransferStage.Downloading, download.file.name,
                        completed = if (item.status == TransferStatus.Completed) 1 else 0, total = 1,
                        bytes = item.bytes, byteTotal = download.file.bytes.takeIf { it > 0 }),
                    item.status, canControl = resume)
            }
            recovered = emptyList()
            localTransfers = (restored + localTransfers).distinctBy { it.id }.sortedByDescending { it.startedAt }
            transferHistory.save(localTransfers)
        } finally {
            recovered.forEach { item ->
                releaseTransfer({ keep -> transfer.durableDownloads.release(item.transfer, keep) }, keep = true)
            }
        }
    }

    private fun runTransfer(
        tab: TaskTab,
        title: String,
        total: Int? = null,
        useFirstFilename: Boolean = false,
        abandon: ((Boolean) -> Unit)? = null,
        durable: DurableDownload? = null,
        thumbnail: PreviewFile? = null,
        previewUri: Uri? = null,
        action: (TransferControl, (TransferProgress) -> Unit) -> Int,
    ) {
        if (!signedIn || transferQueue.size >= TransferHistory.LIMIT) {
            releaseTransfer(abandon)
            notice = if (signedIn) appString(R.string.model_transfer_queue_full) else appString(R.string.model_transfer_requires_signin)
            return
        }
        val record = LocalTransfer(durable?.id ?: UUID.randomUUID().toString(), tab, title,
            durable?.startedAt ?: System.currentTimeMillis(),
            TransferProgress(total = total), TransferStatus.Queued, canControl = true, thumbnail = thumbnail)
        transferQueue[record.id] = TransferWork(useFirstFilename, abandon, action, durable)
        localTransfers = (listOf(record) + localTransfers).filterIndexed { index, item ->
            index < TransferHistory.LIMIT || item.id in transferQueue
        }
        transferHistory.save(localTransfers)
        selectTaskTab(tab)
        if (tab == TaskTab.Download) clearSelection()
        startNextTransfer()
        if (previewUri != null && showThumbnails) {
            val owner = transferHistory.currentAccountScope()
            viewModelScope.launch {
                val picture = transfer.sourceThumbnail(previewUri) ?: return@launch
                if (signedIn && owner == transferHistory.currentAccountScope()) {
                    updateTransfer(record.id) { recent ->
                        if (recent.status in setOf(TransferStatus.Cancelling, TransferStatus.Cancelled, TransferStatus.Interrupted)) {
                            recent
                        } else recent.copy(thumbnailBitmap = picture)
                    }
                }
            }
        }
    }

    fun pauseTransfer(id: String) {
        val record = localTransfers.firstOrNull { it.id == id && it.canControl && it.tab == TaskTab.Download } ?: return
        when (record.status) {
            TransferStatus.Queued -> updateTransfer(id) { it.copy(status = TransferStatus.Paused) }
            TransferStatus.Running -> {
                updateTransfer(id) { it.copy(status = TransferStatus.Pausing) }
                activeTransfer?.takeIf { it.first == id }?.second?.stop()
            }
            else -> Unit
        }
    }

    fun resumeTransfer(id: String) {
        val record = localTransfers.firstOrNull { it.id == id && it.canControl && it.tab == TaskTab.Download } ?: return
        if (record.status !in setOf(TransferStatus.Paused, TransferStatus.Failed)) return
        updateTransfer(id) { it.copy(status = TransferStatus.Queued, failure = null) }
        startNextTransfer()
    }

    fun cancelTransfer(id: String) {
        val task = transferQueue[id] ?: return
        val durable = task.durable
        if (durable == null) {
            stopTransfer(id, task)
            return
        }
        if (task.cancelPending) return
        task.cancelPending = true
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { transfer.durableDownloads.cancel(durable) } }
            if (transferQueue[id] === task) {
                task.cancelPending = false
                if (saved.isSuccess) stopTransfer(id, task) else notice = appString(R.string.model_cancel_save_failed)
            }
        }
    }

    private fun stopTransfer(id: String, task: TransferWork) {
        val active = activeTransfer
        if (active?.first == id) {
            updateTransfer(id) { it.copy(status = TransferStatus.Cancelling) }
            active.second.stop()
        } else {
            transferQueue.remove(id)
            updateTransfer(id) { it.copy(status = TransferStatus.Cancelled, canControl = false) }
            releaseTransfer(task.abandon)
        }
    }

    private fun startNextTransfer() {
        if (activeTransfer != null || !signedIn) return
        val next = localTransfers.lastOrNull { it.status == TransferStatus.Queued && it.id in transferQueue } ?: return
        val id = next.id
        val work = checkNotNull(transferQueue[id])
        val control = TransferControl(transferHistory)
        activeTransfer = id to control
        updateTransfer(id) { it.copy(status = TransferStatus.Running) }
        viewModelScope.launch {
            val updates = MutableStateFlow(next.progress)
            var waitingForName = work.nameFromFirstFile && next.progress.currentFile == null
            fun display(progress: TransferProgress, transform: (LocalTransfer) -> LocalTransfer = { it }) {
                if (transferQueue[id] !== work) return
                updateTransfer(id) { record ->
                    val name = progress.currentFile?.takeIf { waitingForName }
                    if (name != null) waitingForName = false
                    transform(record.copy(title = name ?: record.title, progress = progress))
                }
            }
            val observer = launch {
                while (isActive) {
                    display(control.sample() ?: updates.value)
                    delay(180)
                }
            }
            var abandon = true
            val upload = next.tab == TaskTab.Upload
            try {
                val completed = withContext(Dispatchers.IO) {
                    val transferContext = currentCoroutineContext()
                    work.run(control) { progress ->
                        transferContext.ensureActive()
                        updates.value = progress
                    }
                }
                display(updates.value.copy(completed = completed, total = completed)) {
                    it.copy(status = TransferStatus.Completed, canControl = false)
                }
                if (transferQueue[id] === work) {
                    notice = if (upload) appString(R.string.model_uploaded_count, completed) else appString(R.string.model_downloaded_count, completed)
                }
            } catch (error: Exception) {
                val current = localTransfers.firstOrNull { it.id == id }
                val authRejected = error is CloudError && error.code == "auth"
                val interrupted = error is CancellationException || error is TransferOwnerChanged || authRejected
                if (interrupted && work.durable != null) work.keepCheckpoint = true
                val status = when {
                    interrupted -> TransferStatus.Interrupted
                    current?.status == TransferStatus.Pausing -> TransferStatus.Paused
                    current?.status == TransferStatus.Cancelling -> TransferStatus.Cancelled
                    else -> TransferStatus.Failed
                }
                abandon = upload || status !in setOf(TransferStatus.Paused, TransferStatus.Failed)
                val progress = updates.value
                val observed = current?.progress?.takeIf {
                    it.currentFile == progress.currentFile && it.completed == progress.completed && it.bytes >= progress.bytes
                }
                display(observed ?: progress) {
                    it.copy(status = status, canControl = !abandon,
                        failure = error.message.takeIf { status == TransferStatus.Failed })
                }
                if (authRejected && transferQueue[id] === work) {
                    expireSession(error.message)
                } else if (error is TransferOwnerChanged && transferQueue[id] === work) {
                    notice = error.message
                } else if (status == TransferStatus.Failed && transferQueue[id] === work) {
                    notice = error.message ?: appString(R.string.model_transfer_incomplete)
                }
                if (error is CancellationException) throw error
            } finally {
                observer.cancel()
                if (abandon || transferQueue[id] !== work) {
                    transferQueue.remove(id)
                    releaseTransfer(work.abandon, work.keepCheckpoint)
                }
                activeTransfer = null
                if (upload) {
                    invalidateIndexSync()
                    if (signedIn && (page == Page.Files || page == Page.Favorites)) refresh()
                }
                startNextTransfer()
            }
        }
    }

    private fun updateTransfer(id: String, transform: (LocalTransfer) -> LocalTransfer) {
        val before = localTransfers.firstOrNull { it.id == id } ?: return
        val after = transform(before)
        if (before == after) return
        localTransfers = localTransfers.map { if (it.id == id) after else it }
        if (before.status != after.status || before.title != after.title || before.progress.completed != after.progress.completed) {
            transferHistory.save(localTransfers)
        }
    }

    private fun discardTransfers(keepDurable: Boolean = true) {
        val active = activeTransfer
        val ids = transferQueue.keys.toList()
        ids.forEach { id ->
            val task = transferQueue.remove(id) ?: return@forEach
            task.keepCheckpoint = keepDurable && task.durable != null
            if (id != active?.first) releaseTransfer(task.abandon, task.keepCheckpoint)
            updateTransfer(id) { it.copy(status = TransferStatus.Interrupted, canControl = false) }
        }
        active?.second?.stop()
    }

    private fun releaseTransfer(abandon: ((Boolean) -> Unit)?, keep: Boolean = false) {
        if (abandon != null) CoroutineScope(Dispatchers.IO).launch { runCatching { abandon(keep) } }
    }

    fun calculateHash(uri: Uri, complete: (String) -> Unit) = work(appString(R.string.model_calculating_gcid), {
        withContext(Dispatchers.IO) { transfer.hash(uri) }
    }, complete)

    fun folders(parent: String, complete: (List<CloudFile>, String?) -> Unit): Job =
        viewModelScope.launch {
            try {
                val folders = io("files", "parent" to parent).files().filter(CloudFile::folder)
                complete(folders.sortedBy { it.name.lowercase() }, null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                complete(emptyList(), error.message)
            }
        }

    suspend fun pollTasks() {
        while (true) {
            delay(5_000)
            if (signedIn && page == Page.Tasks && taskTab == TaskTab.Cloud && busy == null && !loading &&
                records.any { it.optString("phase") in setOf("PHASE_TYPE_PENDING", "PHASE_TYPE_RUNNING") }
            ) {
                val request = refresh() ?: return
                try {
                    request.join()
                } finally {
                    if (!currentCoroutineContext().isActive) request.cancel()
                }
            }
        }
    }

    private suspend fun io(operation: String, vararg args: Pair<String, Any?>): Any? =
        withContext(Dispatchers.IO) { api.call(operation, *args) }

    private suspend fun readWithRetry(operation: String, vararg args: Pair<String, Any?>): Any? =
        try {
            io(operation, *args)
        } catch (error: CloudError) {
            if (error.code != "connect" && error.code != "unavailable" &&
                !(operation == "init" && error.code == "timeout")
            ) throw error
            delay(700)
            io(operation, *args)
        }

    private fun <T> work(label: String, action: suspend () -> T, complete: (T) -> Unit) {
        if (busy != null) return
        busy = label
        val generation = sessionGeneration
        viewModelScope.launch {
            try {
                val result = action()
                if (generation == sessionGeneration) complete(result)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation != sessionGeneration) return@launch
                notice = error.message ?: appString(R.string.model_action_failed)
                if (error is CloudError && error.code == "auth") {
                    expireSession(error.message)
                } else if (error is CloudError && error.code == "pending") {
                    // The server may already have applied a change whose acknowledgement timed out.
                    invalidateIndexSync()
                    if (signedIn && (canScanFolders || location.search != null)) refresh()
                }
            } finally {
                busy = null
            }
        }
    }
}
