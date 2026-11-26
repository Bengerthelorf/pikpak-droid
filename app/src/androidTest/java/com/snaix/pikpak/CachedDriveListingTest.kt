package com.snaix.pikpak

import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.descendants
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import dev.oneuiproject.oneui.layout.NavDrawerLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CachedDriveListingTest {
    @Test
    fun reopeningDrawerRefreshesRemoteChangesAfterTwoMinutesAndPullRefreshBypassesThatWindow() = isolated { app, cloud ->
        val elapsed = AtomicLong(1_000L)
        cloud.listings[""] = emptyList()
        cloud.favorites = listOf("first.txt")
        workspace(app, cloud, elapsed::get) { screen, model ->
            expect(screen, "signed in") { model.signedIn && !model.loading }
            screen.onActivity { model.loadDrawerFavorites() }
            expect(screen, "original favorite") { model.drawerFavoritesLoaded && !model.drawerFavoritesLoading }
            cloud.favorites = listOf("other-device.txt")
            elapsed.addAndGet(119_999L)
            screen.onActivity { model.loadDrawerFavorites() }
            assertEquals(1, cloud.favoriteReads.get())
            elapsed.incrementAndGet()
            screen.onActivity { model.loadDrawerFavorites() }
            expect(screen, "remote changes after cache window") {
                !model.drawerFavoritesLoading && model.drawerFavorites.singleOrNull()?.id == "other-device.txt"
            }
            screen.onActivity { model.open(Page.Favorites) }
            expect(screen, "favorites page reuses fresh drawer data") { !model.loading && model.entries == model.drawerFavorites }
            assertEquals(2, cloud.favoriteReads.get())
            cloud.favorites = listOf("refresh.txt")
            screen.onActivity { model.refreshFromUi() }
            expect(screen, "pull refresh picks up latest changes immediately") {
                !model.loading && model.entries.singleOrNull()?.id == "refresh.txt" && model.entries == model.drawerFavorites
            }
            assertEquals(3, cloud.favoriteReads.get())
        }
    }

    @Test
    fun drawerAndFavoritesPageShareOneLazyReadAndKeepOfflineDiskResults() = isolated { app, cloud ->
        cloud.listings[""] = listOf("home.txt")
        cloud.favorites = listOf("saved.txt")
        workspace(app, cloud) { screen, model ->
            expect(screen, "drive root before opening the drawer") { !model.loading && model.signedIn }
            assertEquals(0, cloud.favoriteReads.get())
            val read = cloud.hold("starred")
            screen.onActivity {
                model.loadDrawerFavorites()
                model.loadDrawerFavorites()
                model.open(Page.Favorites)
            }
            read.awaitStart()
            assertEquals(1, cloud.favoriteReads.get())
            read.release.countDown()
            expect(screen, "drawer and page show the completed favorites request") {
                !model.loading && model.drawerFavoritesLoaded && model.drawerFavorites.singleOrNull()?.name == "saved.txt" &&
                    model.entries == model.drawerFavorites
            }
            screen.onActivity { model.loadDrawerFavorites() }
            assertEquals(1, cloud.favoriteReads.get())
        }
        val offline = cloud.hold("starred", error = "request")
        workspace(app, cloud) { screen, model ->
            expect(screen, "new signed-in model") { model.signedIn && !model.loading }
            screen.onActivity { model.loadDrawerFavorites() }
            offline.awaitStart()
            expect(screen, "snapshot visible while the drawer refreshes") {
                model.drawerFavoritesLoading && model.drawerFavoritesLoaded && model.drawerFavorites.singleOrNull()?.name == "saved.txt"
            }
            offline.release.countDown()
            expect(screen, "offline favorites and retry state") {
                !model.drawerFavoritesLoading && model.drawerFavoritesError != null && model.drawerFavorites.singleOrNull()?.name == "saved.txt"
            }
            cloud.favorites = emptyList()
            screen.onActivity { model.loadDrawerFavorites() }
            expect(screen, "server-confirmed empty favorites") {
                model.drawerFavoritesLoaded && !model.drawerFavoritesLoading && model.drawerFavoritesError == null && model.drawerFavorites.isEmpty()
            }
        }
    }

    @Test
    fun drawerFavoritesReflectMutationsAndRejectOldAccountResponses() = isolated { app, cloud ->
        cloud.listings[""] = listOf("new.txt", "old.txt")
        cloud.favorites = listOf("old.txt")
        workspace(app, cloud) { screen, model ->
            expect(screen, "signed in at root") { model.signedIn && !model.loading }
            screen.onActivity { model.loadDrawerFavorites() }
            expect(screen, "initial favorites") { model.drawerFavoritesLoaded && !model.drawerFavoritesLoading }
            screen.onActivity { model.command("star", "star", "ids" to listOf("new.txt")) }
            expect(screen, "star visible without reopening drawer") {
                !model.loading && model.drawerFavorites.map { it.id }.toSet() == setOf("old.txt", "new.txt")
            }
            screen.onActivity { model.command("unstar", "unstar", "ids" to listOf("old.txt")) }
            expect(screen, "unstar visible without reopening drawer") { model.drawerFavorites.map { it.id } == listOf("new.txt") }

            val late = cloud.hold("starred", error = "auth")
            screen.onActivity { model.loadDrawerFavorites() }
            late.awaitStart()
            screen.onActivity { model.logout() }
            expect(screen, "logout clears previous account favorites") {
                !model.signedIn && !model.drawerFavoritesLoaded && model.drawerFavorites.isEmpty()
            }
            screen.onActivity { model.login("second@example.invalid", "fixture") {} }
            expect(screen, "different account signed in") { model.signedIn && !model.loading }
            val cold = cloud.hold("starred", error = "request")
            screen.onActivity { model.loadDrawerFavorites() }
            cold.awaitStart()
            late.release.countDown()
            cold.release.countDown()
            expect(screen, "no cached rows from the other account on first-read failure") {
                model.signedIn && !model.drawerFavoritesLoading && !model.drawerFavoritesLoaded &&
                    model.drawerFavorites.isEmpty() && model.drawerFavoritesError != null
            }
        }
    }

    @Test
    fun mutatingFavoritesBeforeOpeningDrawerUpdatesItsExistingDiskSnapshot() = isolated { app, cloud ->
        cloud.listings[""] = listOf("old.txt", "new.txt")
        cloud.favorites = listOf("old.txt", "keep.txt")
        workspace(app, cloud) { screen, model ->
            expect(screen, "signed in before seeding") { model.signedIn && !model.loading }
            screen.onActivity { model.loadDrawerFavorites() }
            expect(screen, "original snapshot on disk") { model.drawerFavoritesLoaded && !model.drawerFavoritesLoading }
        }
        workspace(app, cloud) { screen, model ->
            expect(screen, "root with favorites drawer still unopened") { model.signedIn && !model.loading && !model.drawerFavoritesLoaded }
            screen.onActivity { model.command("unstar", "unstar", "ids" to listOf("old.txt")) }
            expect(screen, "confirmed first change") { model.busy == null && !model.loading }
            screen.onActivity { model.command("star", "star", "ids" to listOf("new.txt")) }
            expect(screen, "confirmed second change") { model.busy == null && !model.loading }
            assertEquals(1, cloud.favoriteReads.get())
            val remote = cloud.hold("starred", error = "request")
            screen.onActivity { model.loadDrawerFavorites() }
            remote.awaitStart()
            screen.onActivity {
                assertTrue(model.drawerFavoritesLoading)
                assertEquals(setOf("keep.txt", "new.txt"), model.drawerFavorites.map(CloudFile::id).toSet())
            }
            remote.release.countDown()
            expect(screen, "updated snapshot retained after failed refresh") { model.drawerFavoritesError != null }
        }
    }

    @Test
    fun leavingGlobalSearchDiscardsALateResponse() = isolated { app, cloud ->
        cloud.listings[""] = listOf("home.txt")
        cloud.listings["*"] = listOf("elsewhere.txt")
        val delayed = cloud.hold("*", error = "auth")
        workspace(app, cloud) { screen, model ->
            expect(screen, "root listing") { !model.loading && row(it, "home.txt") != null }
            screen.onActivity { model.searchDrive("elsewhere") }
            delayed.awaitStart()
            val jobs = screen.onActivity { model.viewModelScope.coroutineContext.job.children.toList() }
            screen.onActivity {
                model.editQuery("new query")
                check(model.back())
            }
            expect(screen, "original directory after cancelling") { model.location.search == null && row(it, "home.txt") != null }
            delayed.release.countDown()
            runBlocking { withTimeout(10_000) { jobs.filter(Job::isCancelled).joinAll() } }
            screen.onActivity {
                assertTrue(model.signedIn)
                assertEquals(listOf("home.txt"), model.entries.map { it.name })
                assertEquals(null, model.loadError)
            }
        }
    }

    @Test
    fun canOpenCachedFolderWhileRefreshingAndLateParentCannotReplaceItsChildren() = isolated { app, cloud ->
        cloud.listings[""] = listOf("folder")
        cloud.listings["folder"] = listOf("original.txt")
        workspace(app, cloud) { screen, model ->
            expect(screen, "loaded drive root") { !model.loading && row(it, "folder") != null }
            screen.onActivity { clickRow(it, "folder") }
            expect(screen, "loaded initial folder") { !model.loading && row(it, "original.txt") != null }

            cloud.listings[""] = listOf("late-parent.txt")
            val parentRead = cloud.hold("")
            screen.onActivity { check(model.back()) }
            parentRead.awaitStart()
            expect(screen, "cached parent row under the active refresh bar") {
                model.loading && progress(it).isShown && row(it, "folder") != null && row(it, "original.txt") == null
            }
            val parentJobs = screen.onActivity { model.viewModelScope.coroutineContext.job.children.toList() }

            cloud.listings["folder"] = listOf("updated.txt")
            val childRead = cloud.hold("folder")
            screen.onActivity { clickRow(it, "folder") }
            childRead.awaitStart()
            expect(screen, "cached child listing while the child request is in flight") {
                model.folder.id == "folder" && model.loading && progress(it).isShown && row(it, "original.txt") != null
            }
            parentRead.release.countDown()
            runBlocking { withTimeout(10_000) { parentJobs.filter(Job::isCancelled).joinAll() } }
            screen.onActivity {
                assertTrue(model.loading)
                assertEquals(listOf("original.txt"), model.entries.map(CloudFile::name))
            }
            childRead.release.countDown()
            expect(screen, "fresh child replaces cached rows and finishes the progress bar") {
                !model.loading && !progress(it).isShown && row(it, "updated.txt") != null &&
                    row(it, "original.txt") == null && row(it, "late-parent.txt") == null
            }
        }
    }

    @Test
    fun reopenedWorkspaceShowsItsDiskSnapshotThroughAnOfflineRefreshThenAcceptsFreshData() = isolated { app, cloud ->
        cloud.listings[""] = listOf("previous.txt")
        workspace(app, cloud) { screen, model ->
            expect(screen, "seeded directory listing") { !model.loading && row(it, "previous.txt") != null }
        }
        val offline = cloud.hold("", error = "request")
        workspace(app, cloud) { screen, model ->
            offline.awaitStart()
            expect(screen, "new model restores cached file with refresh still running") {
                model.loading && progress(it).isShown && row(it, "previous.txt") != null
            }
            offline.release.countDown()
            expect(screen, "network error retains the cached file and ends progress") {
                !model.loading && model.loadError != null && !progress(it).isShown && row(it, "previous.txt") != null
            }
            cloud.listings[""] = listOf("latest.txt")
            screen.onActivity { model.refreshFromUi() }
            expect(screen, "successful refresh replaces the offline listing") {
                !model.loading && model.loadError == null && row(it, "latest.txt") != null && row(it, "previous.txt") == null
            }
        }
        val recheck = cloud.hold("", error = "auth")
        workspace(app, cloud) { screen, model ->
            recheck.awaitStart()
            expect(screen, "successful refresh replaced the on-disk snapshot") {
                model.loading && row(it, "latest.txt") != null && row(it, "previous.txt") == null
            }
            recheck.release.countDown()
            expect(screen, "session expiration hides cached contents") {
                !model.signedIn && !model.loading && model.entries.isEmpty() && row(it, "latest.txt") == null
            }
            val otherRequest = cloud.hold("", error = "request")
            screen.onActivity { model.login("other@example.invalid", "fixture") {} }
            otherRequest.awaitStart()
            otherRequest.release.countDown()
            expect(screen, "another account cannot see the previous owner's listing even offline") {
                model.signedIn && !model.loading && model.loadError != null && model.entries.isEmpty() &&
                    row(it, "latest.txt") == null
            }
        }
    }

    @Test
    fun slowerCachedStatisticsCannotPutAnOldFolderBackAfterTheCloudRefresh() = isolated { app, cloud ->
        val owner = checkNotNull(TransferHistory(app).verifiedAccountScope())
        val disk = DriveListingCache(app)
        disk.write(owner, "", listOf(CloudFile("folder", "folder", folder = true)), disk.beginRead())
        app.appearancePreferences().edit().putBoolean(DRIVE_FOLDER_STATS, true).commit()
        cloud.listings[""] = listOf("replacement.txt")
        val remote = cloud.hold("")
        val oldStats = cloud.hold("folder_stats_cached")
        cloud.supportsFolderIndex = true
        workspace(app, cloud) { screen, model ->
            remote.awaitStart()
            oldStats.awaitStart()
            expect(screen, "old folder is already visible during statistics lookup") {
                row(it, "folder") != null && model.loading
            }
            remote.release.countDown()
            expect(screen, "fast cloud result is shown while old statistics are pending") {
                row(it, "replacement.txt") != null && row(it, "folder") == null
            }
            oldStats.release.countDown()
            expect(screen, "old statistics cannot replace the server response") {
                !model.loading && row(it, "replacement.txt") != null && row(it, "folder") == null
            }
        }
    }

    @Test fun backRestoresScrolledListDuringAndAfterParentRefresh() = checkScrollOnReturn(DriveView.List)

    @Test fun toolbarUpRestoresScrolledGridDuringAndAfterParentRefresh() = checkScrollOnReturn(DriveView.Icons)

    @Test
    fun folderSlidesInBothDirectionsAndRefreshStaysStill() = isolated { app, cloud ->
        cloud.listings[""] = listOf("folder")
        cloud.listings["folder"] = listOf("child.txt")
        workspace(app, cloud) { screen, model ->
            expect(screen, "parent folder row") { !model.loading && row(it, "folder") != null }
            val pages = screen.onActivity { it.findViewById<FolderPageLayout>(R.id.workspace_folder_pages) }
            // Only the incoming page has workspace_list. The departing RecyclerView remains visible
            // alongside it until the animation completes.
            val frames = mutableListOf<Pair<Int, Int?>>()
            val trace = ViewTreeObserver.OnPreDrawListener {
                val live = pages.findViewById<RecyclerView>(R.id.workspace_list)
                val departing = (0 until pages.childCount).map(pages::getChildAt).firstOrNull { it !== live }
                frames.add(pages.horizontalOffset(live) to departing?.let { pages.horizontalOffset(it) })
                true
            }
            fun arrived(from: Int): Boolean = frames.lastOrNull() == (0 to null) &&
                if (ValueAnimator.areAnimatorsEnabled()) frames.any { (live, departing) ->
                    live * from > 1 && departing != null && departing * from < -1
                } else frames.all { it == (0 to null) }
            try {
                screen.onActivity {
                    pages.viewTreeObserver.addOnPreDrawListener(trace)
                    clickRow(it, "folder")
                }
                expect(screen, "child slides in from the trailing edge then settles") {
                    !model.loading && row(it, "child.txt") != null && arrived(1)
                }

                cloud.listings["folder"] = listOf("updated.txt")
                screen.onActivity {
                    frames.clear()
                    model.refreshFromUi()
                }
                expect(screen, "refreshed child has drawn") {
                    !model.loading && row(it, "updated.txt") != null && frames.isNotEmpty()
                }
                screen.onActivity {
                    assertTrue("refresh moved the child horizontally: $frames", frames.all { it == (0 to null) })
                    frames.clear()
                    it.onBackPressedDispatcher.onBackPressed()
                }
                expect(screen, "parent slides in from the leading edge then settles") {
                    !model.loading && row(it, "folder") != null && arrived(-1)
                }
            } finally {
                screen.onActivity { pages.viewTreeObserver.removeOnPreDrawListener(trace) }
            }
        }
    }

    @Test
    fun backDuringFolderEntryKeepsParentAfterLateChildResponse() = isolated { app, cloud ->
        cloud.listings[""] = listOf("folder", "parent.txt")
        cloud.listings["folder"] = listOf("late-child.txt")
        val blocked = cloud.hold("folder")
        workspace(app, cloud) { screen, model ->
            expect(screen, "parent row ready for entry") { !model.loading && row(it, "folder") != null }
            val pages = screen.onActivity { it.findViewById<FolderPageLayout>(R.id.workspace_folder_pages) }
            val back = screen.onActivity { it.onBackPressedDispatcher }
            var reversed = false
            var childJobs = emptyList<Job>()
            val trace = ViewTreeObserver.OnPreDrawListener {
                val live = pages.findViewById<RecyclerView>(R.id.workspace_list)
                val distance = kotlin.math.abs(pages.horizontalOffset(live))
                if (!reversed && model.folder.id == "folder") {
                    if (blocked.started.count == 0L && (distance in 1 until pages.width || !ValueAnimator.areAnimatorsEnabled())) {
                        reversed = true
                        childJobs = model.viewModelScope.coroutineContext.job.children.toList()
                        pages.post { back.onBackPressed() }
                    } else {
                        pages.postInvalidateOnAnimation()
                    }
                }
                true
            }
            try {
                screen.onActivity {
                    pages.viewTreeObserver.addOnPreDrawListener(trace)
                    clickRow(it, "folder")
                }
                blocked.awaitStart()
                expect(screen, "Back during entry restores the parent while the child request is still held") {
                    reversed && model.location.parent == null && row(it, "parent.txt") != null
                }
            } finally {
                blocked.release.countDown()
                screen.onActivity { pages.viewTreeObserver.removeOnPreDrawListener(trace) }
            }
            runBlocking { withTimeout(10_000) { childJobs.filter(Job::isCancelled).joinAll() } }
            expect(screen, "live parent returns to its resting position") {
                val live = pages.findViewById<RecyclerView>(R.id.workspace_list)
                !model.loading && pages.childCount == 1 && pages.horizontalOffset(live) == 0 && row(it, "parent.txt") != null && row(it, "late-child.txt") == null
            }
        }
    }

    private fun FolderPageLayout.horizontalOffset(page: View): Int {
        val point = IntArray(2)
        getLocationInWindow(point)
        val windowX = point[0]
        page.getLocationInWindow(point)
        return (point[0] - windowX) * if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
    }

    private fun checkScrollOnReturn(view: DriveView) = isolated { app, cloud ->
        DrivePresentation(view = view, grouping = DriveGrouping.Kind).save(app.appearancePreferences())
        val folders = (100..199).map { "Folder $it" }
        val parent = if (view == DriveView.List) "" else "folder"
        if (parent.isNotEmpty()) cloud.listings[""] = listOf(parent)
        cloud.listings[parent] = folders
        cloud.folders.addAll(folders)
        for (folder in folders) cloud.listings[folder] = listOf("child.txt")
        workspace(app, cloud) { screen, model ->
            if (parent.isNotEmpty()) {
                expect(screen, "top-level folder") { row(it, parent) != null }
                screen.onActivity { clickRow(it, parent) }
            }
            fun list(activity: MainActivity) = activity.findViewById<RecyclerView>(R.id.workspace_list)
            fun toolbar(activity: MainActivity) = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
            expect(screen, "long parent listing") {
                !model.loading && list(it).let { recycler ->
                    (recycler.adapter?.itemCount ?: 0) >= folders.size && !recycler.hasPendingAdapterUpdates()
                }
            }
            screen.onActivity {
                toolbar(it).setExpanded(false, false)
                (list(it).layoutManager as LinearLayoutManager).scrollToPositionWithOffset(50, -it.dp(11))
            }
            fun firstVisible(activity: MainActivity): Pair<String, Int>? {
                val recycler = list(activity)
                val layout = recycler.layoutManager as LinearLayoutManager
                val child = layout.findViewByPosition(layout.findFirstVisibleItemPosition()) as? ViewGroup ?: return null
                val name = child.descendants.filterIsInstance<TextView>().map { it.text.toString() }
                    .firstOrNull { it in folders } ?: return null
                return name to (layout.getDecoratedTop(child) - recycler.paddingTop)
            }
            expect(screen, "scrolled parent and collapsed app bar") {
                !toolbar(it).isExpanded && (firstVisible(it)?.first?.let(folders::indexOf) ?: -1) in 40..55
            }
            val initialRowCount = screen.onActivity { list(it).adapter!!.itemCount }
            val before = screen.onActivity { activity ->
                val anchor = checkNotNull(firstVisible(activity))
                // Activate a fully visible row below the partially clipped first row.
                val columns = (list(activity).layoutManager as? GridLayoutManager)?.spanCount ?: 1
                clickRow(activity, folders[folders.indexOf(anchor.first) + columns])
                anchor
            }
            expect(screen, "child directory at its start") {
                !model.loading && row(it, "child.txt") != null && !list(it).canScrollVertically(-1)
            }
            // Insert a whole grid row ahead of the anchor so an adapter index alone cannot restore it.
            val columns = screen.onActivity { (list(it).layoutManager as? GridLayoutManager)?.spanCount ?: 1 }
            val inserted = (0 until columns).map { "Added $it" }
            cloud.folders.addAll(inserted)
            cloud.listings[parent] = inserted + folders
            val refreshed = cloud.hold(parent)
            screen.onActivity {
                if (view == DriveView.List) it.onBackPressedDispatcher.onBackPressed() else {
                    val bar = toolbar(it).toolbar
                    val up = bar.descendants.filterIsInstance<ImageButton>().first { button -> button.drawable == bar.navigationIcon }
                    check(up.performClick())
                }
            }
            refreshed.awaitStart()
            fun restored(activity: MainActivity) = !toolbar(activity).isExpanded &&
                !list(activity).hasPendingAdapterUpdates() && firstVisible(activity) == before
            try {
                expect(screen, "cached parent restored to $before before the network returns") {
                    model.loading && restored(it)
                }
            } finally {
                refreshed.release.countDown()
            }
            expect(screen, "refreshed parent retains $before after prepending files") {
                !model.loading && list(it).adapter?.itemCount == initialRowCount + columns && restored(it)
            }
        }
    }

    private fun progress(activity: MainActivity): View = activity.findViewById(R.id.workspace_drive_progress)

    private fun row(activity: MainActivity, name: String): TextView? =
        activity.findViewById<ViewGroup>(R.id.workspace_list).descendants.filterIsInstance<TextView>()
            .firstOrNull { it.isShown && it.text.toString() == name }

    private fun clickRow(activity: MainActivity, name: String) {
        val label = checkNotNull(row(activity, name))
        val target = generateSequence<View>(label) { it.parent as? View }.first(View::isClickable)
        check(target.performClick())
    }

    private fun expect(screen: FixtureActivity<MainActivity>, label: String, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            if (screen.onActivity(predicate)) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Expected $label")
    }

    private fun workspace(app: Application, cloud: Listings, elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
        run: (FixtureActivity<MainActivity>, CloudModel) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        launchFixtureActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).use { screen ->
            val model = screen.onActivity { activity ->
                ViewModelProvider(activity, viewModelFactory {
                    initializer { CloudModel(app, cloud, transferHistory = TransferHistory(app), elapsedMillis = elapsedMillis) }
                })["cached-list-fixture", CloudModel::class.java].also { activity.showFixture(it) }
            }
            run(screen, model)
        }
    }

    private fun isolated(run: (Application, Listings) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "cached-listing-").toFile()
        val prefix = "cached-listing-${UUID.randomUUID()}-"
        val preferences = mutableSetOf<String>()
        val app = object : Application() {
            init { attachBaseContext(context.applicationContext) }
            override fun getApplicationContext(): Context = this
            override fun getCacheDir() = dir
            override fun getNoBackupFilesDir() = File(dir, "private").apply { mkdirs() }
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val scoped = prefix + name
                synchronized(preferences) { preferences.add(scoped) }
                return context.getSharedPreferences(scoped, Context.MODE_PRIVATE)
            }
        }
        app.appearancePreferences().edit().putBoolean(DRIVE_FOLDER_STATS, false)
            .putBoolean(APPEARANCE_PREVIEW_CACHE, false).putBoolean(APPEARANCE_THUMBNAILS, false).commit()
        TransferHistory(app).authenticate("owner@example.invalid") {}
        val cloud = Listings()
        try {
            run(app, cloud)
        } finally {
            cloud.releaseAll()
            synchronized(preferences) { preferences.forEach { context.deleteSharedPreferences(it) } }
            dir.deleteRecursively()
        }
    }

    private class Response(val error: String?) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun awaitStart() = check(started.await(10, TimeUnit.SECONDS)) { "Expected a cloud listing request" }
    }

    private class Listings : Gateway {
        override var supportsFolderIndex = false
        val listings = ConcurrentHashMap<String, List<String>>()
        val folders = ConcurrentHashMap.newKeySet<String>().apply { add("folder") }
        @Volatile var favorites: List<String> = emptyList()
        val favoriteReads = AtomicInteger()
        private val next = ConcurrentHashMap<String, Response>()
        private val gates = mutableListOf<Response>()
        fun hold(folder: String, error: String? = null) = Response(error).also {
            gates.add(it)
            next[folder] = it
        }
        fun releaseAll() = gates.forEach { it.release.countDown() }
        private fun respond(key: String) {
            next.remove(key)?.let { response ->
                response.started.countDown()
                check(response.release.await(30, TimeUnit.SECONDS)) { "Fixture listing remained blocked" }
                response.error?.let { throw CloudError(it, "Fixture listing rejected") }
            }
        }
        override fun call(operation: String, vararg args: Pair<String, Any?>): Any? {
            if (operation == "init") return true
            if (operation == "login" || operation == "logout") return null
            if (operation == "star" || operation == "unstar") {
                val ids = (args.toMap()["ids"] as List<*>).filterIsInstance<String>()
                favorites = if (operation == "star") (favorites + ids).distinct() else favorites - ids.toSet()
                return null
            }
            if (operation == "starred") {
                favoriteReads.incrementAndGet()
                val result = favorites
                respond(operation)
                return JSONArray().apply {
                    for (id in result) put(JSONObject().put("id", id).put("name", id).put("kind", "file"))
                }
            }
            if (operation == "folder_stats_cached") {
                respond(operation)
                return JSONObject().put("folder", JSONObject().put("bytes", "123").put("is_empty", false))
            }
            check(operation == "files") { "Unexpected fixture operation: $operation" }
            val parent = args.toMap().getValue("parent") as String
            val files = listings.getValue(parent)
            respond(parent)
            return JSONArray().apply {
                for (name in files) put(JSONObject().put("id", name).put("name", name)
                    .put("kind", if (name in folders) "folder" else "file"))
            }
        }
    }
}
