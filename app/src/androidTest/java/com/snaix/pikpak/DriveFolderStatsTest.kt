package com.snaix.pikpak

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.View
import androidx.appcompat.widget.SeslProgressBar
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DriveFolderStatsTest {
    @Test
    fun optionalIndexProbeAcceptsCoresPredatingCapabilityDiscovery() {
        fun probe(reply: () -> JSONObject): Boolean = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? {
                assertEquals("core_info", operation)
                return reply()
            }
        }.advertisesFolderIndex()
        assertFalse(probe { throw CloudError("request", "Unknown operation") })
        assertFalse(probe { JSONObject().put("api", 1) })
        assertTrue(probe { JSONObject().put("capabilities", JSONArray().put("folder_index")) })
    }

    @Test
    fun toolbarProgressTracksScanAndDisappearsWhenLeavingTheDrive() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val name = "scan-toolbar-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val app = object : Application() {
            init { attachBaseContext(context.applicationContext) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        prefs.edit().putBoolean(APPEARANCE_THUMBNAILS, false).apply()
        val gateway = ScanGateway()
        val first = gateway.holdNext()
        try {
            launchFixtureActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).use { screen ->
                instrument.waitForIdleSync()
                val model = screen.onActivity { activity ->
                    ViewModelProvider(activity, viewModelFactory {
                        initializer { CloudModel(app, gateway) }
                    })["toolbar-scan", CloudModel::class.java].also {
                        it.applyPresentation(DrivePresentation(view = DriveView.List, grouping = DriveGrouping.ContentModified))
                        activity.showFixture(it)
                    }
                }
                fun bar(activity: MainActivity) = activity.findViewById<SeslProgressBar>(R.id.workspace_drive_progress)
                fun await(ready: (SeslProgressBar) -> Boolean) {
                    val end = SystemClock.elapsedRealtime() + 12_000
                    do {
                        if (screen.onActivity { ready(bar(it)) }) return
                        SystemClock.sleep(30)
                    } while (SystemClock.elapsedRealtime() < end)
                    error(screen.onActivity { activity ->
                        val view = bar(activity)
                        "Toolbar progress shown=${view.isShown} width=${view.width} " +
                            "indeterminate=${view.isIndeterminate} value=${view.progress}/${view.max}"
                    })
                }
                fun checkPlacementAndCapture(state: String) {
                    instrument.waitForIdleSync()
                    screen.onActivity { activity ->
                        val root = activity.findViewById<dev.oneuiproject.oneui.layout.NavDrawerLayout>(R.id.workspace_toolbar_layout)
                        val row = bar(activity).parent as View
                        val top = IntArray(2)
                        val header = IntArray(2)
                        row.getLocationInWindow(top)
                        root.appBarLayout.getLocationInWindow(header)
                        assertEquals(activity.dp(4), top[1] - header[1] - root.appBarLayout.height)
                        assertEquals(activity.dp(5), row.height)
                    }
                    if (InstrumentationRegistry.getArguments().getString("captureDriveProgress") != "true" ||
                        android.os.Build.VERSION.SDK_INT < 34) return
                    // Give the native drawable a few frames after it becomes visible or changes mode.
                    SystemClock.sleep(450)
                    val window = screen.onActivity { it.window }
                    val bitmap = checkNotNull(instrument.getUiAutomation(
                        android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot(window))
                    try {
                        java.io.File(context.cacheDir, "qa-drive-$state.png").outputStream().use {
                            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { bitmap.recycle() }
                }
                first.awaitStart()
                await { it.isShown && it.width > 0 && it.isIndeterminate }
                assertEquals("正在统计", screen.onActivity { model.listingPresentation.sections(model.entries).single().label })
                checkPlacementAndCapture("expanded-loading")
                if (InstrumentationRegistry.getArguments().getString("captureDriveProgress") == "true") {
                    checkPlacementAndCapture("expanded-loading-next")
                }
                val second = gateway.holdNext()
                first.release.countDown()
                second.awaitStart()
                await { it.isShown && !it.isIndeterminate && it.max == 2 && it.progress == 1 }
                checkPlacementAndCapture("expanded-half")
                screen.onActivity { activity ->
                    activity.findViewById<dev.oneuiproject.oneui.layout.NavDrawerLayout>(R.id.workspace_toolbar_layout)
                        .setExpanded(false, false)
                }
                checkPlacementAndCapture("collapsed-half")
                second.release.countDown()
                await { !it.isShown }
                val next = gateway.holdNext()
                screen.onActivity { model.refreshFromUi() }
                next.awaitStart()
                await { it.isShown }
                screen.onActivity { model.open(Page.Trash) }
                await { !it.isShown }
                next.release.countDown()
            }
        } finally {
            gateway.releaseAll()
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun sizeAndContentTimeUseCompletedStatsAndLocalDatesInRowsSectionsAndDetails() {
        val zone = ZoneId.of("Asia/Shanghai")
        val folder = CloudFile(
            "big", "big", true, bytes = 0, modified = "2025-06-30T16:15:00Z", created = "2025-01-31T17:00:00Z",
            folderStats = FolderStats.Complete.from(reply(ULong.MAX_VALUE, "2025-04-30T12:00:00.400-04:00")),
        )
        val file = CloudFile("file", "file", false, bytes = 100, modified = "2025-06-01T04:30:00+08:00")
        val empty = CloudFile("empty", "empty", true, folderStats = FolderStats.Complete.from(reply(0uL, empty = true)))
        val zero = CloudFile("zero", "zero", true, folderStats = FolderStats.Complete.from(reply(0uL)))
        val failed = CloudFile("failed", "a failed subtree", true, bytes = 8192, folderStats = FolderStats.Failed)
        val pending = CloudFile("pending", "b pending", true, folderStats = FolderStats.Pending)
        val entries = listOf(zero, failed, file, empty, folder, pending)
        val bySize = DrivePresentation(sort = FileSort.Size, grouping = DriveGrouping.Size)
        val byContent = DrivePresentation(sort = FileSort.ContentModified, grouping = DriveGrouping.ContentModified)
        val byMetadata = byContent.available(false)

        assertEquals(ULong.MAX_VALUE, folder.displayBytes)
        assertNull(failed.displayBytes)
        assertEquals(listOf(folder, file, empty, zero, failed, pending), bySize.sorted(entries, ""))
        assertEquals(listOf(empty, zero, file, folder, failed, pending), bySize.copy(descending = false).sorted(entries, ""))
        assertEquals(listOf(file, folder, empty, zero, failed, pending), byContent.sorted(entries, ""))
        assertEquals(listOf(folder, file, empty, zero, failed, pending), byContent.copy(descending = false).sorted(entries, ""))
        assertEquals(folder, byMetadata.sorted(entries, "").first())
        assertEquals(listOf("1 GiB 及以上", "小于 1 MiB", "正在统计", "统计失败"), bySize.sections(entries, zone).map { it.label })
        assertEquals(listOf("2025年6月", "2025年5月", "正在统计", "空文件夹", "统计失败", "日期未知"),
            byContent.sections(entries, zone).map { it.label })
        assertEquals(listOf("2025年7月", "2025年6月", "日期未知"), byMetadata.sections(entries, zone).map { it.label })
        assertEquals("July 2025", byMetadata.sections(entries, zone, java.util.Locale.ENGLISH).first().label)
        assertEquals("2025年7月", byMetadata.sections(entries, zone, java.util.Locale.TRADITIONAL_CHINESE).first().label)
        assertTrue(byContent.rowDetail(folder, zone).endsWith("2025-05-01"))
        assertTrue(byMetadata.rowDetail(folder, zone).endsWith("2025-07-01"))
        assertTrue(DrivePresentation(sort = FileSort.Created).rowDetail(folder, zone).endsWith("2025-02-01"))
        assertEquals("0 B（空文件夹）", byContent.rowDetail(empty, zone))
        assertEquals("0 B · 无有效时间", byContent.rowDetail(zero, zone))
        assertEquals("统计失败", byContent.rowDetail(failed, zone))
        assertEquals("正在统计", byContent.rowDetail(pending, zone))
        val details = folder.detailText(JSONObject(), zone)
        assertTrue(details.contains("文件夹修改时间：2025-07-01 00:15"))
        assertTrue(details.contains("内容修改时间：2025-05-01 00:00"))
        assertTrue(details.contains("创建：2025-02-01 01:00"))
        assertFalse(failed.detailText(JSONObject(), zone).contains("0 B"))
    }

    @Test
    fun returningToANUsesPersistedTotalsAndSharesTheFirstIndexBuildAcrossNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "index-return-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(APPEARANCE_THUMBNAILS, false).apply()
        val app = object : Application() {
            init { attachBaseContext(context.applicationContext) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        val gateway = IndexedGateway()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val fixtureWorker = Dispatchers.IO.limitedParallelism(1)
        val factory = viewModelFactory { initializer { CloudModel(app, gateway, folderStatsDispatcher = fixtureWorker) } }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        lateinit var model: CloudModel
        fun awaitState(label: String, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 8000
            do {
                var reached = false
                main { reached = condition() }
                if (reached) return
                SystemClock.sleep(15)
            } while (SystemClock.elapsedRealtime() < until)
            error("Missing index state: $label")
        }
        try {
            main {
                model = ViewModelProvider(owner, factory)[CloudModel::class.java]
            }
            // AN's size is already on disk when this model starts, even before the first sync.
            awaitState("saved size displayed while syncing") {
                model.entries.singleOrNull()?.displayBytes == 12uL && model.syncingFolderIndex
            }
            // The UI can show synchronization while the worker is still queued.
            // Begin navigating only once the simulated core is actually inside its first sync.
            assertTrue("First core sync never entered", gateway.startedSync.await(12, TimeUnit.SECONDS))
            main { model.openFolder(model.entries.single()) }
            awaitState("partial snapshot inside AN") {
                model.folder.id == "AN" && model.entries.firstOrNull()?.displayBytes == 0uL &&
                    model.entries.lastOrNull()?.folderStats == FolderStats.Pending
            }
            main { model.openFolder(model.entries.first()) }
            awaitState("empty child") { model.folder.id == "empty" && !model.loading && model.entries.isEmpty() }
            main { assertTrue(model.back()) }
            awaitState("same sync still running") { model.folder.id == "AN" && model.syncingFolderIndex }
            assertEquals(1, gateway.syncCalls.get())
            assertTrue(gateway.cancelled.isEmpty())
            gateway.allowSync.countDown()
            awaitState("committed batch reread") {
                !model.syncingFolderIndex && model.entries.map { it.displayBytes } == listOf(0uL, 21uL)
            }
            main {
                val stats = model.entries.last().folderStats as FolderStats.Complete
                assertEquals("2026-11-24T10:00:00Z", stats.modifiedAt.toString())
            }
            main { model.openFolder(model.entries.first()) }
            awaitState("second child visit") { model.folder.id == "empty" && !model.loading }
            main { assertTrue(model.back()) }
            awaitState("immediate return") {
                model.folder.id == "AN" && !model.loading && !model.syncingFolderIndex && model.entries.lastOrNull()?.displayBytes == 21uL
            }
            assertEquals(1, gateway.syncCalls.get())
            gateway.failSync = true
            main { model.refreshFromUi() }
            awaitState("failed explicit sync retains snapshot") {
                gateway.syncCalls.get() == 2 && !model.syncingFolderIndex &&
                    model.entries.map { it.displayBytes } == listOf(0uL, 21uL) && model.notice != null
            }
            // A new model has no in-memory freshness timestamp. It must still render the
            // committed values supplied by the core when the remote sync is unavailable.
            main {
                owner.viewModelStore.clear()
                model = ViewModelProvider(owner, factory)[CloudModel::class.java]
            }
            awaitState("cached root survives reopening while offline") {
                gateway.syncCalls.get() == 3 && !model.syncingFolderIndex && model.entries.singleOrNull()?.displayBytes == 21uL
            }
            assertEquals(0, gateway.scopedCalls.get())
            assertTrue(gateway.cachedBatches.any { it == listOf("empty", "work") })
        } finally {
            gateway.allowSync.countDown()
            main { owner.viewModelStore.clear() }
            context.deleteSharedPreferences(name)
        }
    }

    private class IndexedGateway : Gateway {
        override val supportsFolderIndex = true
        val startedSync = CountDownLatch(1)
        val allowSync = CountDownLatch(1)
        val syncCalls = AtomicInteger()
        val scopedCalls = AtomicInteger()
        val cancelled = CopyOnWriteArrayList<String>()
        val cachedBatches = CopyOnWriteArrayList<List<String>>()
        @Volatile var failSync = false
        @Volatile private var committed = false

        override fun call(operation: String, vararg args: Pair<String, Any?>): Any? {
            val input = args.toMap()
            return when (operation) {
                "init" -> true
                "files" -> JSONArray().apply {
                    val children = when (input["parent"]) {
                        "" -> listOf("AN")
                        "AN" -> listOf("empty", "work")
                        else -> emptyList()
                    }
                    children.forEach { put(JSONObject().put("id", it).put("name", it).put("kind", "drive#folder")) }
                }
                "folder_stats_cached" -> JSONObject().apply {
                    val ids = (input["ids"] as List<*>).filterIsInstance<String>()
                    cachedBatches.add(ids)
                    ids.forEach { id ->
                        put(id, when (id) {
                            "AN" -> reply(if (committed) 21uL else 12uL)
                            "empty" -> reply(0uL, empty = true)
                            else -> if (committed) reply(21uL, "2026-11-24T10:00:00Z") else JSONObject.NULL
                        })
                    }
                }
                "folder_stats_begin" -> { check(input["fresh"] != true); null }
                "folder_index_sync" -> {
                    check(input["force_full"] != true)
                    syncCalls.incrementAndGet()
                    startedSync.countDown()
                    if (failSync) throw CloudError("connect", "offline")
                    check(allowSync.await(15, TimeUnit.SECONDS))
                    committed = true
                    null
                }
                "folder_stats_cancel" -> cancelled.add(input["scan"] as String)
                "folder_stats" -> { scopedCalls.incrementAndGet(); error("Unexpected scoped traversal") }
                else -> error("Unexpected fixture operation $operation")
            }
        }
    }

    @Test
    fun returningToANReusesScopedFallbackAndRefreshKeepsItsLastKnownSize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "scoped-return-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val app = object : Application() {
            init { attachBaseContext(context.applicationContext) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun awaitState(label: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 8000
            do {
                var reached = false
                main { reached = condition() }
                if (reached) return
                SystemClock.sleep(15)
            } while (SystemClock.elapsedRealtime() < end)
            error("Missing scoped statistics: $label")
        }
        try {
            for (indexed in listOf(true, false)) {
                val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
                val calls = AtomicInteger()
                val listings = AtomicInteger()
                val syncs = AtomicInteger()
                val gate = Gate()
                val indexError = AtomicReference("request")
                val persisted = AtomicReference<JSONObject?>()
                val gateway = object : Gateway {
                    override val supportsFolderIndex = indexed
                    override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                        "init" -> true
                        "files" -> JSONArray().apply {
                            listings.incrementAndGet()
                            if (args.toMap()["parent"] == "")
                                put(JSONObject().put("id", "AN").put("name", "AN").put("kind", "drive#folder"))
                        }
                        "folder_stats_begin", "folder_stats_cancel" -> null
                        "folder_stats_cached" -> JSONObject().put("AN", persisted.get() ?: JSONObject.NULL)
                        "folder_index_sync" -> { syncs.incrementAndGet(); throw CloudError(indexError.get(), "unavailable") }
                        "folder_stats" -> {
                            val call = calls.incrementAndGet()
                            if (call == 2) {
                                gate.started.countDown()
                                check(gate.release.await(15, TimeUnit.SECONDS))
                            }
                            reply(16uL * call.toULong())
                        }
                        else -> error("Unexpected operation $operation")
                    }
                }
                lateinit var model: CloudModel
                try {
                    main {
                        model = ViewModelProvider(owner, viewModelFactory {
                            initializer { CloudModel(app, gateway, folderStatsDispatcher = Dispatchers.IO.limitedParallelism(1)) }
                        })[CloudModel::class.java]
                    }
                    awaitState("scoped size") { model.entries.singleOrNull()?.displayBytes == 16uL }
                    main { model.openFolder(model.entries.single()) }
                    awaitState("inside AN") { model.folder.id == "AN" && !model.loading }
                    main { assertTrue(model.back()) }
                    awaitState("retained after back") {
                        model.folder.id.isEmpty() && !model.loading && model.entries.singleOrNull()?.displayBytes == 16uL
                    }
                    assertEquals(1, calls.get())
                    assertEquals(if (indexed) 1 else 0, syncs.get())
                    main { model.refreshFromUi() }
                    gate.awaitStart()
                    main { assertEquals(16uL, model.entries.single().displayBytes) }
                    gate.release.countDown()
                    awaitState("updated scoped size") { model.entries.singleOrNull()?.displayBytes == 32uL }
                    if (indexed) {
                        indexError.set("timeout")
                        main { model.refreshFromUi() }
                        awaitState("timeout retains stale value without a scoped storm") {
                            syncs.get() == 3 && !model.syncingFolderIndex && model.entries.singleOrNull()?.displayBytes == 32uL
                        }
                        assertEquals(2, calls.get())
                        persisted.set(reply(99uL))
                        val listsBefore = listings.get()
                        main { model.reloadCachedFolderStats() }
                        awaitState("committed SQLite wins") { model.entries.singleOrNull()?.displayBytes == 99uL }
                        assertEquals(listsBefore, listings.get())
                        assertEquals(3, syncs.get())
                    }
                } finally {
                    gate.release.countDown()
                    main { owner.viewModelStore.clear() }
                }
            }
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test
    fun navigationPreferenceRefreshAndLogoutCancelNativeScansAndKeepTheWorkerBounded() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefsName = "folder-stats-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val application = object : Application() {
            init { attachBaseContext(context.applicationContext) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        val gateway = ScanGateway()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        lateinit var model: CloudModel
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun expect(check: () -> Boolean) {
            val limit = SystemClock.elapsedRealtime() + 5_000
            do {
                var ready = false
                onMain { ready = check() }
                if (ready) return
                SystemClock.sleep(20)
            } while (SystemClock.elapsedRealtime() < limit)
            error("Folder statistics did not reach the expected state")
        }
        val oldRoot = gateway.holdNext()
        try {
            onMain {
                model = ViewModelProvider(owner, viewModelFactory {
                    initializer { CloudModel(application, gateway) }
                })[CloudModel::class.java]
            }
            oldRoot.awaitStart()
            onMain { model.open(Page.Favorites) }
            expect { model.entries.size == 3 && model.entries.all { it.folderStats == FolderStats.Pending } }
            assertTrue(oldRoot.token in gateway.cancelled)
            oldRoot.release.countDown()
            expect { model.entries.all { it.folderStats != FolderStats.Pending } }
            onMain {
                assertEquals(42uL, model.entries.first { it.id == "same" }.displayBytes)
                assertNull(model.entries.first { it.id == "bad" }.displayBytes)
                assertTrue((model.entries.first { it.id == "empty" }.folderStats as FolderStats.Complete).isEmpty)
            }
            assertFalse("abandoned" in gateway.folders)
            val refresh = gateway.holdNext()
            onMain {
                model.applyPresentation(DrivePresentation(sort = FileSort.ContentModified, grouping = DriveGrouping.ContentModified))
                model.refresh()
            }
            refresh.awaitStart()
            onMain { prefs.edit().putBoolean(DRIVE_FOLDER_STATS, false).apply() }
            assertTrue(refresh.token in gateway.cancelled)
            refresh.release.countDown()
            expect { model.entries.all { it.folderStats == null } && model.presentation.sort == FileSort.Recent }
            onMain {
                assertEquals(DriveGrouping.Modified, model.presentation.grouping)
                model.open(Page.Trash)
            }
            expect { model.entries.singleOrNull()?.id == "trashed" }
            onMain { prefs.edit().putBoolean(DRIVE_FOLDER_STATS, true).apply() }
            expect { !model.canScanFolders && model.entries.single().folderStats == null }
            val logout = gateway.holdNext()
            onMain { model.open(Page.Files) }
            logout.awaitStart()
            onMain { model.logout() }
            expect { !model.signedIn && model.entries.isEmpty() }
            assertTrue(logout.token in gateway.cancelled)
            logout.release.countDown()
            expect { gateway.inFlight.get() == 0 }
            assertEquals(1, gateway.maxInFlight.get())
            assertFalse("trashed" in gateway.folders)
        } finally {
            gateway.releaseAll()
            onMain { owner.viewModelStore.clear() }
            context.deleteSharedPreferences(prefsName)
        }
    }

    private class Gate {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        lateinit var token: String
        fun awaitStart() = assertTrue("Expected a scan to start", started.await(5, TimeUnit.SECONDS))
    }

    private class ScanGateway : Gateway {
        val cancelled = ConcurrentHashMap.newKeySet<String>()
        val folders = CopyOnWriteArrayList<String>()
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        private val next = AtomicReference<Gate?>()
        private val gates = CopyOnWriteArrayList<Gate>()
        fun holdNext() = Gate().also {
            check(next.compareAndSet(null, it))
            gates.add(it)
        }

        fun releaseAll() = gates.forEach { it.release.countDown() }

        override fun call(operation: String, vararg args: Pair<String, Any?>): Any? {
            val input = args.toMap()
            return when (operation) {
                "init" -> true
                "files" -> folderList("same", "abandoned")
                "starred" -> folderList("same", "bad", "empty")
                "trash" -> folderList("trashed")
                "folder_stats_begin", "logout" -> null
                "folder_stats_cancel" -> cancelled.add(input["scan"] as String)
                "folder_stats" -> {
                    val id = input["id"] as String
                    folders.add(id)
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    try {
                        val gate = next.getAndSet(null)
                        if (gate != null) {
                            gate.token = input["scan"] as String
                            gate.started.countDown()
                            // Simulate an in-flight page which completes after navigation.
                            check(gate.release.await(30, TimeUnit.SECONDS))
                            reply(999uL, "2026-10-18T08:00:00Z")
                        } else when (id) {
                            "bad" -> throw CloudError("network", "Unreadable descendant page")
                            "empty" -> reply(0uL, empty = true)
                            else -> reply(42uL)
                        }
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
                else -> error("Unexpected operation $operation")
            }
        }

        private fun folderList(vararg ids: String) = JSONArray().apply {
            ids.forEach {
                val title = when (it) { "same" -> "项目资料"; "abandoned" -> "影像归档"; else -> it }
                put(JSONObject().put("name", title).put("id", it).put("kind", "drive#folder"))
            }
        }
    }

    companion object {
        private fun reply(bytes: ULong, modified: String? = null, empty: Boolean = false): JSONObject =
            JSONObject().put("bytes", bytes.toString()).put("modified_at", modified ?: JSONObject.NULL).put("is_empty", empty)
    }
}
