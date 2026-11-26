package com.snaix.pikpak

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.descendants
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TransferTasksTest {
    @Test
    fun staleTransferOwnerInterruptsDownloadWithoutSigningOut() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = "transfer-owner-fixture-${UUID.randomUUID()}"
        val store = context.getSharedPreferences(prefs, Context.MODE_PRIVATE)
        val history = TransferHistory(store)
        val target = File(context.cacheDir, "transfer-owner-${UUID.randomUUID()}.txt")
        val downloads = AtomicInteger()
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any = when (operation) {
                "init" -> true
                "files" -> JSONArray()
                "download" -> downloads.incrementAndGet()
                else -> error("Unexpected fixture operation: $operation")
            }
        }
        try {
            history.authenticate("first@example.test") {}
            launchFixtureActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).use { scenario ->
                val model = scenario.onActivity { activity ->
                    ViewModelProvider(activity, viewModelFactory {
                        initializer { CloudModel(activity.application, gateway, transferHistory = history) }
                    })["transfer-owner-fixture", CloudModel::class.java].also { activity.showFixture(it) }
                }
                expect(scenario, "saved session restored") { model.signedIn && !model.loading }
                val newOwner = TransferHistory(store).apply { authenticate("second@example.test") {} }
                scenario.onActivity {
                    model.export(CloudFile("old", "old.txt", false), Uri.fromFile(target))
                }
                expect(scenario, "old transfer interrupted") {
                    model.localTransfers.singleOrNull()?.status == TransferStatus.Interrupted
                }
                scenario.onActivity {
                    assertTrue(model.signedIn)
                    assertEquals(null, model.authError)
                    assertTrue(!model.localTransfers.single().canControl)
                }
                assertEquals(0, downloads.get())
                assertTrue(newOwner.load().isEmpty())
                assertTrue(!target.exists())
            }
        } finally {
            context.deleteSharedPreferences(prefs)
            target.delete()
        }
    }

    @Test
    fun pickerResultSurvivesRecreatedStateAndWaitsForSessionRestore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "picker-fixture-").toFile()
        val folder = Folder("", "云盘")
        val file = CloudFile("picker-file", "样本.txt", folder = false, bytes = 7)
        val target = File(directory, file.name)
        val authStarted = CountDownLatch(1)
        val finishAuth = CountDownLatch(1)
        val downloads = AtomicInteger()
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any = when (operation) {
                "init" -> {
                    authStarted.countDown()
                    check(finishAuth.await(10, TimeUnit.SECONDS))
                    true
                }
                "files" -> JSONArray()
                "download" -> {
                    downloads.incrementAndGet()
                    assertEquals(file.id, args.toMap()["id"])
                    File(args.toMap().getValue("path") as String).writeText("content")
                }
                else -> error("Unexpected picker fixture operation: $operation")
            }
        }
        try {
            val original = TransferPickerState(directory, null)
            val uploadState = Bundle()
            original.saveUpload(folder)
            original.saveInstanceState(uploadState)
            assertEquals(folder, TransferPickerState(directory, uploadState).takeUpload())
            assertEquals(null, TransferPickerState(directory, uploadState).takeUpload())

            val downloadState = Bundle()
            original.saveDownload(listOf(file), "fixture-account")
            original.saveInstanceState(downloadState)
            launchFixtureActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).use { scenario ->
                val model = scenario.onActivity { activity ->
                    ViewModelProvider(activity, viewModelFactory {
                        initializer { CloudModel(activity.application, gateway) }
                    })["picker-restart-fixture", CloudModel::class.java].also { activity.showFixture(it) }
                }
                assertTrue(authStarted.await(6, TimeUnit.SECONDS))
                scenario.onActivity {
                    val restored = TransferPickerState(directory, downloadState)
                    val selection = restored.takeDownloadSelection()
                    assertEquals("fixture-account", selection?.accountScope)
                    model.finishTransferPicker(selection?.files?.singleOrNull()) {
                        model.export(it, Uri.fromFile(target))
                    }
                    assertEquals(null, restored.takeDownloadSelection())
                    assertEquals(0, downloads.get())
                }
                finishAuth.countDown()
                expect(scenario, "restored picker result exports after sign-in") {
                    model.localTransfers.singleOrNull()?.status == TransferStatus.Completed
                }
                assertEquals(1, downloads.get())
                assertEquals("content", target.readText())
            }
        } finally {
            finishAuth.countDown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun englishTaskTabTitlesFitNarrowWindowsAtLargeFontScale() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val base = instrument.targetContext
        instrument.runOnMainSync {
            val configuration = Configuration(base.resources.configuration).apply {
                setLocale(Locale.ENGLISH)
                fontScale = 1.5f
            }
            val context = ContextThemeWrapper(base.createConfigurationContext(configuration), R.style.AppTheme)
            val panel = LayoutInflater.from(context).inflate(R.layout.workspace_tasks, null)
            val tabs = panel.findViewById<TabLayout>(R.id.workspace_task_tabs)
            tabs.seslSetSubTabStyle()
            val titles = listOf(R.string.model_tab_cloud, R.string.model_tab_device, R.string.model_tab_upload)
                .map { context.getString(it) }
            titles.forEach { tabs.addTab(tabs.newTab().setText(it)) }
            val width = context.dp(320)
            val height = context.dp(480)
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, width, height)
            titles.forEachIndexed { index, title ->
                val tab = checkNotNull(tabs.getTabAt(index))
                val label = tab.view.descendants.filterIsInstance<TextView>().first { it.text.toString() == title }
                val textLayout = checkNotNull(label.layout) { "No layout for $title" }
                assertTrue("Truncated tab title: $title", (0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 })
                assertEquals(title.length, textLayout.getLineEnd(textLayout.lineCount - 1))
            }
        }
    }

    @Test
    @SuppressLint("PrivateResource") // Check the native SESL selection indicator as well as its TabView.
    fun everyTaskTabIndicatorStaysInsideTheOneUiContentEdges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any = when (operation) {
                "init" -> true
                "files", "tasks" -> JSONArray()
                else -> error("Unexpected task fixture operation")
            }
        }
        launchFixtureActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).use { scenario ->
            val model = scenario.onActivity { activity ->
                ViewModelProvider(activity, viewModelFactory {
                    initializer { CloudModel(activity.application, gateway) }
                })["task-tab-layout-fixture", CloudModel::class.java].also { activity.showFixture(it) }
            }
            expect(scenario, "task tab fixture signs in") { model.signedIn }
            scenario.onActivity { model.open(Page.Tasks) }
            expect(scenario, "all three task tabs are laid out") { activity ->
                val tabs = activity.findViewById<TabLayout>(R.id.workspace_task_tabs)
                tabs != null && tabs.isShown && tabs.width > 0 && tabs.tabCount == TaskTab.entries.size
            }

            val clipped = mutableListOf<String>()
            for (tab in TaskTab.entries) {
                scenario.onActivity { activity ->
                    val tabs = activity.findViewById<TabLayout>(R.id.workspace_task_tabs)
                    checkNotNull(tabs.getTabAt(tab.ordinal)).view.performClick()
                }
                expect(scenario, "${tab.title} activates its page") { activity ->
                    val tabs = activity.findViewById<TabLayout>(R.id.workspace_task_tabs)
                    val pager = activity.findViewById<ViewPager2>(R.id.workspace_task_pager)
                    tabs.selectedTabPosition == tab.ordinal && pager.currentItem == tab.ordinal &&
                        pager.scrollState == ViewPager2.SCROLL_STATE_IDLE
                }
                scenario.onActivity { activity ->
                    val panel = activity.findViewById<View>(R.id.workspace_tasks)
                    val tabs = activity.findViewById<TabLayout>(R.id.workspace_task_tabs)
                    val indicator = checkNotNull(tabs.getTabAt(tab.ordinal)).view
                        .findViewById<View>(com.google.android.material.R.id.indicator)
                    val panelBounds = Rect()
                    val indicatorBounds = Rect()
                    check(panel.getGlobalVisibleRect(panelBounds) && indicator.getGlobalVisibleRect(indicatorBounds))
                    val left = indicatorBounds.left - panelBounds.left
                    val right = panelBounds.right - indicatorBounds.right
                    // The host draws a 10 dp rounded edge over its content.
                    val safeInset = activity.dp(12)
                    if (left < safeInset || right < safeInset || indicatorBounds.width() != indicator.width) {
                        clipped.add("${tab.title} left=$left right=$right min=$safeInset")
                    }
                    capture(activity, "task-tab-${tab.name.lowercase()}.png")
                }
            }
            assertTrue(clipped.joinToString(" | "), clipped.isEmpty())
        }
    }

    @Test
    fun tabsShowLocalTransferProgressAndPartialFailure() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "transfer-fixture-").toFile()
        val prefs = "transfer-fixture-${UUID.randomUUID()}"
        val history = TransferHistory(context.getSharedPreferences(prefs, Context.MODE_PRIVATE))
        val written = File(directory, "本机样本.txt")
        val sources = listOf("first.txt", "second.txt").map { name ->
            File(directory, name).apply { writeText(name) }.let(Uri::fromFile)
        }
        val uploadStarted = CountDownLatch(1)
        val finishUpload = CountDownLatch(1)
        val uploads = AtomicInteger()
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> true
                "files" -> JSONArray()
                "tasks" -> JSONArray().put(JSONObject().put("id", "cloud-fixture").put("name", "云端样本").put("phase", "PHASE_TYPE_COMPLETE"))
                "download" -> File(args.toMap().getValue("path") as String).writeText("exported")
                "upload" -> {
                    val staged = File(args.toMap().getValue("path") as String)
                    if (uploads.incrementAndGet() == 1) {
                        assertEquals("first.txt", staged.readText())
                        uploadStarted.countDown()
                        check(finishUpload.await(30, TimeUnit.SECONDS)) { "Fixture did not release the first upload" }
                    } else {
                        assertEquals("second.txt", staged.readText())
                        throw CloudError("network", "测试网络中断")
                    }
                }
                else -> error("Unexpected transfer operation")
            }
        }
        try {
            launchFixtureActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).use { scenario ->
                val model = scenario.onActivity { activity ->
                    ViewModelProvider(activity, viewModelFactory {
                        initializer { CloudModel(activity.application, gateway, transferHistory = history) }
                    })["transfer-fixture", CloudModel::class.java].also { activity.showFixture(it) }
                }
                expect(scenario, "fixture is signed in") { model.signedIn }
                scenario.onActivity { model.export(CloudFile("download-fixture", written.name, folder = false), Uri.fromFile(written)) }
                expect(scenario, "local export completes") {
                    model.busy == null && model.localTransfers.singleOrNull()?.status == TransferStatus.Completed
                }
                assertEquals("exported", written.readText())
                scenario.onActivity { model.upload(sources, Folder("", "云盘")) }
                assertTrue(uploadStarted.await(6, TimeUnit.SECONDS))
                scenario.onActivity { model.open(Page.Tasks) }
                expect(scenario, "upload tab shows the in-flight transfer") { activity ->
                    val pager = activity.findViewById<ViewPager2>(R.id.workspace_task_pager)
                    pager != null && pager.currentItem == TaskTab.Upload.ordinal &&
                        visibleText(pager, "正在上传 · 0/2 个文件")
                }
                assertEquals(TransferStatus.Interrupted, history.load().first().status)
                scenario.onActivity { capture(it, "oneui-upload-tasks.png") }
                finishUpload.countDown()
                try {
                    expect(scenario, "partial upload keeps the completed file count") { activity ->
                        model.busy == null && model.localTransfers.first().progress.completed == 1 &&
                            visibleText(activity.window.decorView, "未完成 · 1/2 个文件")
                    }
                } catch (error: IllegalStateException) {
                    val record = scenario.onActivity { model.localTransfers.first() }
                    error("${error.message}. Workers=${uploads.get()} state=${record.status} " +
                        "completed=${record.progress.completed} failure=${record.failure}")
                }

                scenario.onActivity { activity ->
                    val tabs = activity.findViewById<TabLayout>(R.id.workspace_task_tabs)
                    assertEquals(TaskTab.entries.map(TaskTab::title), List(tabs.tabCount) { tabs.getTabAt(it)?.text.toString() })
                    checkNotNull(tabs.getTabAt(TaskTab.Download.ordinal)).select()
                }
                expect(scenario, "tab selection shows the local download") { activity ->
                    val pager = activity.findViewById<ViewPager2>(R.id.workspace_task_pager)
                    pager.currentItem == TaskTab.Download.ordinal && pager.scrollState == ViewPager2.SCROLL_STATE_IDLE &&
                        visibleText(pager, written.name)
                }
                scenario.onActivity { activity ->
                    capture(activity, "oneui-device-download-tasks.png")
                    val pager = activity.findViewById<ViewPager2>(R.id.workspace_task_pager)
                    assertTrue(pager.beginFakeDrag())
                    assertTrue(pager.fakeDragBy(pager.width * 0.9f))
                    assertTrue(pager.endFakeDrag())
                }
                expect(scenario, "horizontal swipe returns to cloud downloads") { activity ->
                    val pager = activity.findViewById<ViewPager2>(R.id.workspace_task_pager)
                    val tabs = activity.findViewById<TabLayout>(R.id.workspace_task_tabs)
                    model.taskTab == TaskTab.Cloud && tabs.selectedTabPosition == TaskTab.Cloud.ordinal &&
                        visibleText(pager, "云端样本")
                }
                assertEquals(listOf(TransferStatus.Failed, TransferStatus.Completed), history.load().map(LocalTransfer::status))
            }
        } finally {
            finishUpload.countDown()
            context.deleteSharedPreferences(prefs)
            directory.deleteRecursively()
        }
    }

    @Test
    fun transferControlsStopWorkersAndReuseTheDownloadCheckpoint() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "transfer-controls-").toFile()
        val first = CloudFile("first", "first.txt", false, bytes = 7)
        val second = CloudFile("second", "second.txt", false, bytes = 7)
        val firstTarget = File(directory, first.name)
        val secondTarget = File(directory, second.name)
        val upload = File(directory, "upload.txt").apply { writeText("upload") }
        val downloadStarted = CountDownLatch(1)
        val downloadStopped = CountDownLatch(1)
        val uploadStarted = CountDownLatch(1)
        val uploadStopped = CountDownLatch(1)
        val firstAttempts = AtomicInteger()
        val secondAttempts = AtomicInteger()
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any = when (operation) {
                "init" -> true
                "files", "tasks" -> JSONArray()
                else -> error("Unexpected transfer fixture operation: $operation")
            }

            override fun transfer(
                operation: String, control: TransferControl, progress: TransferProgress, vararg args: Pair<String, Any?>,
            ) {
                val values = args.toMap()
                val staged = File(values.getValue("path") as String)
                if (operation == "upload") {
                    control.attach(uploadStopped::countDown) { progress.copy(bytes = 3, byteTotal = 6) }
                    try {
                        uploadStarted.countDown()
                        check(uploadStopped.await(8, TimeUnit.SECONDS))
                        control.checkpoint()
                    } finally {
                        control.detach()
                    }
                } else if (values["id"] == first.id) {
                    val partial = File(staged.parentFile, "${staged.name}.part")
                    if (firstAttempts.incrementAndGet() == 1) {
                        partial.writeText("part")
                        control.attach(downloadStopped::countDown) { progress.copy(bytes = 4, byteTotal = 7) }
                        try {
                            downloadStarted.countDown()
                            check(downloadStopped.await(8, TimeUnit.SECONDS))
                            control.checkpoint()
                        } finally {
                            control.detach()
                        }
                    } else {
                        assertEquals("part", partial.readText())
                        staged.writeText("content")
                    }
                } else if (secondAttempts.incrementAndGet() == 1) {
                    throw CloudError("network", "Fixture network failure")
                } else staged.writeText("content")
            }
        }
        try {
            launchFixtureActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).use { scenario ->
                val model = scenario.onActivity { activity ->
                    ViewModelProvider(activity, viewModelFactory {
                        initializer { CloudModel(activity.application, gateway) }
                    })["transfer-controls-fixture", CloudModel::class.java].also { activity.showFixture(it) }
                }
                expect(scenario, "fixture signs in") { model.signedIn }
                scenario.onActivity { model.export(first, Uri.fromFile(firstTarget)) }
                assertTrue(downloadStarted.await(6, TimeUnit.SECONDS))
                scenario.onActivity {
                    model.export(second, Uri.fromFile(secondTarget))
                    assertEquals(TransferStatus.Queued, model.localTransfers.first().status)
                    model.open(Page.Tasks)
                }
                expect(scenario, "download shows byte progress") { activity ->
                    visibleText(activity.window.decorView, "正在下载 · 0/1 个文件\n4 B / 7 B")
                }
                scenario.onActivity { model.pauseTransfer(model.localTransfers.last().id) }
                expect(scenario, "pause stops current worker and advances the queue") {
                    model.localTransfers.map(LocalTransfer::status) == listOf(TransferStatus.Failed, TransferStatus.Paused)
                }
                scenario.onActivity {
                    model.resumeTransfer(model.localTransfers.last().id)
                    model.resumeTransfer(model.localTransfers.first().id)
                }
                expect(scenario, "resumed and retried downloads complete") {
                    model.localTransfers.all { it.status == TransferStatus.Completed }
                }
                assertEquals("content", firstTarget.readText())
                assertEquals("content", secondTarget.readText())

                scenario.onActivity { model.upload(listOf(Uri.fromFile(upload)), Folder("", "云盘")) }
                assertTrue(uploadStarted.await(6, TimeUnit.SECONDS))
                scenario.onActivity { model.cancelTransfer(model.localTransfers.first().id) }
                expect(scenario, "upload cancellation stops its worker") {
                    model.localTransfers.first().status == TransferStatus.Cancelled
                }
            }
        } finally {
            downloadStopped.countDown()
            uploadStopped.countDown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun durableSingleFileRestoresOnlyARecordedAccountAndWriteGrant() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "durable-download-fixture-").toFile()
        val granted = mutableSetOf<Uri>()
        var allowPersist = true
        val mock = object : DownloadWriteGrants {
            override fun isDocument(uri: Uri) = uri.authority == "fixture.documents"
            override fun hasWrite(uri: Uri) = uri in granted
            override fun takeWrite(uri: Uri) {
                if (!allowPersist) throw SecurityException("Fixture refused a persistent grant")
                granted.add(uri)
            }
            override fun releaseWrite(uri: Uri) { granted.remove(uri) }
        }
        fun destination(id: String) = Uri.parse("content://fixture.documents/document/$id")
        val file = CloudFile("cloud", "transfer.txt", false, bytes = 7, modified = "fixture-metadata")
        val firstProcess = DurableDownloads(root, mock)
        try {
            val record = checkNotNull(firstProcess.create("account-a", file, destination("first")))
            val stage = firstProcess.staging(record)
            File(stage, "data.part").writeText("part")
            firstProcess.release(record, keep = true)

            val restarted = DurableDownloads(root, mock)
            val paused = restarted.recover("account-a").single()
            assertEquals(TransferStatus.Paused, paused.status)
            assertEquals(4L, paused.bytes)
            assertEquals(record.destination, paused.transfer.destination)
            assertEquals(stage, restarted.staging(paused.transfer))
            assertTrue(firstProcess.recover("account-a").isEmpty())
            restarted.beginWriting(paused.transfer)
            restarted.release(paused.transfer, keep = true)
            assertEquals(TransferStatus.Interrupted, firstProcess.recover("account-a").single().status)
            assertTrue(granted.isEmpty() && !stage.exists())

            val revoked = checkNotNull(firstProcess.create("account-a", file, destination("revoked")))
            firstProcess.release(revoked, keep = true)
            granted.remove(revoked.destination)
            assertEquals(TransferStatus.Interrupted, restarted.recover("account-a").single().status)

            val cancelled = checkNotNull(firstProcess.create("account-a", file, destination("cancelled")))
            firstProcess.cancel(cancelled)
            firstProcess.release(cancelled, keep = true)
            assertEquals(TransferStatus.Cancelled, restarted.recover("account-a").single().status)
            assertTrue(granted.isEmpty())

            val other = checkNotNull(firstProcess.create("account-a", file, destination("other-account")))
            firstProcess.release(other, keep = true)
            assertTrue(restarted.recover("account-b").isEmpty())
            assertTrue(granted.isEmpty() && root.listFiles().orEmpty().isEmpty())

            allowPersist = false
            assertEquals(null, restarted.create("account-b", file, destination("unsupported")))
            assertTrue(granted.isEmpty() && root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cloudAndLocalTaskRowsUseAvailableCoversWithoutRefreshingLinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "task-cover-fixture-").toFile()
        val savedFile = File(directory, "export.jpg")
        val cloudCover = "https://preview.invalid/cloud-task.jpg"
        val driveCover = "https://preview.invalid/drive-file.jpg"
        val cloud = JSONObject().put("id", "cloud-task").put("name", "Cloud cover fixture")
            .put("phase", "PHASE_TYPE_COMPLETE").put("reference_resource", JSONObject()
                .put("id", "cloud-result").put("thumbnail_link", cloudCover))
        val drive = JSONObject().put("id", "drive-file").put("name", savedFile.name)
            .put("kind", "drive#file").put("thumbnail_link", driveCover)
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> true
                "files" -> JSONArray().put(drive)
                "tasks" -> JSONArray().put(cloud)
                "download" -> File(args.toMap().getValue("path") as String).writeText("fixture")
                else -> error("Unexpected task preview operation: $operation")
            }
        }
        val linkReads = AtomicInteger()
        val preview = CloudPreviews(fetchImage = { uri, _, _ ->
            val color = when (uri.toString()) {
                cloudCover -> Color.CYAN
                driveCover -> Color.MAGENTA
                else -> Color.TRANSPARENT
            }
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        }) {
            linkReads.incrementAndGet()
            null
        }
        assertTrue(cloudTaskPreview(cloud)?.canRefreshLink == false)
        assertEquals(null, cloudTaskPreview(JSONObject().put("id", "no-thumb")
            .put("web_content_link", "https://preview.invalid/original.jpg")))
        try {
            launchFixtureActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).use { scenario ->
                val model = scenario.onActivity { activity ->
                    ViewModelProvider(activity, viewModelFactory {
                        initializer { CloudModel(activity.application, gateway, previewProvider = preview) }
                    })["task-cover-fixture", CloudModel::class.java].also { activity.showFixture(it) }
                }
                expect(scenario, "drive thumbnail source is loaded") { model.entries.singleOrNull()?.id == "drive-file" }
                scenario.onActivity { model.open(Page.Tasks) }
                expect(scenario, "cloud-task row renders supplied cover") { visibleBitmap(it.window.decorView, Color.CYAN) }

                scenario.onActivity { model.open(Page.Files) }
                expect(scenario, "drive thumbnail source is restored") { model.entries.singleOrNull()?.id == "drive-file" }
                scenario.onActivity {
                    // Picker drafts carry the file identity without storing the signed thumbnail URL.
                    model.export(CloudFile("drive-file", savedFile.name, false), Uri.fromFile(savedFile))
                    model.open(Page.Tasks)
                }
                expect(scenario, "local download row renders the drive cover") { activity ->
                    model.taskTab == TaskTab.Download && visibleBitmap(activity.window.decorView, Color.MAGENTA)
                }
                assertEquals(0, linkReads.get())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun visibleBitmap(view: View, color: Int): Boolean {
        val bitmap = ((view as? ImageView)?.drawable as? BitmapDrawable)?.bitmap
        if (bitmap != null && bitmap.config != Bitmap.Config.HARDWARE && bitmap.getPixel(0, 0) == color &&
            view.isShown && view.getGlobalVisibleRect(Rect())) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) if (visibleBitmap(view.getChildAt(index), color)) return true
        }
        return false
    }

    private fun visibleText(view: View, value: String): Boolean {
        if (view is TextView && view.text.startsWith(value) && view.isShown && view.getGlobalVisibleRect(Rect())) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) if (visibleText(view.getChildAt(index), value)) return true
        }
        return false
    }

    private fun expect(scenario: FixtureActivity<MainActivity>, note: String, ready: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            if (scenario.onActivity(ready)) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Transfer fixture did not reach: $note")
    }

    private fun capture(activity: MainActivity, filename: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            File(activity.cacheDir, filename).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
