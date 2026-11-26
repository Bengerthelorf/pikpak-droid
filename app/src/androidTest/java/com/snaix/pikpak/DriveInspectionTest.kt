package com.snaix.pikpak

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import android.widget.ImageView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import dev.oneuiproject.oneui.layout.NavDrawerLayout
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class DriveInspectionTest {
    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun iconViewGroupsFilesAndOpensTheFolderCoverPicker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val historyName = "drive-inspection-${UUID.randomUUID()}"
        val history = TransferHistory(context.getSharedPreferences(historyName, Context.MODE_PRIVATE))
        val initial = DrivePresentation.load(context.appearancePreferences())
        val coverFolder = "cover-fixture-${UUID.randomUUID()}"
        val coverChild = "cover-child-fixture-${UUID.randomUUID()}"
        val emptyFolder = "empty-fixture-${UUID.randomUUID()}"
        val older = "2025-01-10T11:00:00Z"
        val newer = "2025-04-20T11:00:00Z"
        fun entry(id: String, name: String, kind: String = "drive#file", date: String = older) =
            JSONObject().put("id", id).put("name", name).put("kind", kind).put("created_time", date)
                .put("modified_time", date).put("thumbnail_link", "https://fixture.example/$id.png")
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any = when (operation) {
                "init" -> true
                "files" -> when (args.toMap()["parent"]) {
                    coverFolder -> JSONArray().put(entry("old", "原始封面.jpg")).put(entry("new", "新封面.jpg", date = newer))
                        .put(entry(coverChild, "子文件夹封面", "drive#folder"))
                    emptyFolder, coverChild -> JSONArray()
                    else -> JSONArray().put(entry(coverFolder, "封面测试文件夹", "drive#folder"))
                        .put(entry(emptyFolder, "空目录", "drive#folder"))
                        .put(entry("media", "视频.mp4", date = newer)).put(entry("audio", "音频.mp3"))
                }
                else -> error("Unexpected drive fixture operation: $operation")
            }
        }
        val preview = CloudPreviews(fetchImage = { uri, _, _ ->
            val color = if (uri.lastPathSegment == "old.png") 0xffc27362 else 0xff397eac
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color.toInt()) }
        }) { null }
        try {
            launchFixtureActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).use { scenario ->
                val model = scenario.onActivity { activity ->
                    ViewModelProvider(activity, viewModelFactory {
                        initializer { CloudModel(activity.application, gateway, preview, history) }
                    })["drive-inspection", CloudModel::class.java].also {
                        it.applyPresentation(DrivePresentation())
                        activity.showFixture(it)
                    }
                }
                expect(scenario) { model.entries.size == 4 && !model.loading }
                scenario.onActivity {
                    val byCreated = DrivePresentation(sort = FileSort.Created, grouping = DriveGrouping.Created)
                    assertEquals("media", byCreated.sorted(model.entries, "").first().id)
                    assertEquals(listOf("2025年4月", "2025年1月"), byCreated.sections(model.entries).map(DriveSection::label))
                }
                openViewOptions(scenario)
                expect(scenario) { choiceList(it)?.let { list -> list.childCount > 0 } == true }
                scenario.onActivity { activity ->
                    val list = checkNotNull(choiceList(activity))
                    assertEquals(4, list.count)
                    for (index in 0..2) {
                        val choice = list.adapter.getItem(index) as Choice
                        val indicators = choiceText(list, index).compoundDrawablesRelative
                        assertTrue(choice.opensSubmenu)
                        assertNotNull(indicators[0])
                        assertNotNull(indicators[2])
                    }
                    val order = list.adapter.getItem(3) as Choice
                    assertEquals("排序顺序", order.title)
                    assertEquals(Symbol.SortUp, order.icon)
                    assertTrue(order.separated)
                    assertNull(choiceText(list, 3).compoundDrawablesRelative[2])
                }
                tapChoice(scenario, "排序顺序")
                expect(scenario) { model.presentation.descending && choiceList(it) == null }
                openViewOptions(scenario)
                expect(scenario) { choiceList(it)?.let { list -> list.childCount > 0 } == true }
                scenario.onActivity { activity ->
                    val order = checkNotNull(choiceList(activity)).adapter.getItem(3) as Choice
                    assertEquals(Symbol.SortDown, order.icon)
                    assertEquals("降序", order.detail)
                }
                tapChoice(scenario, "视图")
                expect(scenario) { choiceList(it)?.let { list -> list.count == 2 && list.childCount > 0 } == true }
                scenario.onActivity { activity ->
                    val options = checkNotNull(choiceList(activity))
                    assertEquals(Symbol.ListView, (options.adapter.getItem(0) as Choice).icon)
                    assertEquals(Symbol.GridView, (options.adapter.getItem(1) as Choice).icon)
                    assertNotNull(choiceText(options, 0).compoundDrawablesRelative[0])
                    assertNotNull(choiceText(options, 0).compoundDrawablesRelative[2])
                    assertNotNull(choiceText(options, 1).compoundDrawablesRelative[0])
                    assertNull(choiceText(options, 1).compoundDrawablesRelative[2])
                }
                tapChoice(scenario, "图标")
                expect(scenario) { it.findViewById<RecyclerView>(R.id.workspace_list).layoutManager is GridLayoutManager }
                openViewOptions(scenario)
                tapChoice(scenario, "分组方式")
                expect(scenario) { choiceList(it)?.count == DriveGrouping.entries.size }
                scenario.onActivity { activity ->
                    val options = checkNotNull(choiceList(activity))
                    val icons = (0 until options.count).map { (options.adapter.getItem(it) as Choice).icon }
                    assertEquals(DriveGrouping.entries.size, icons.distinct().size)
                }
                tapChoice(scenario, "文件类型")
                expect(scenario) {
                    model.presentation.grouping == DriveGrouping.Kind && text(it.window.decorView, "文件夹") != null
                }
                expect(scenario) {
                    val tile = text(it.window.decorView, "封面测试文件夹")?.parent as? ViewGroup
                    val cover = tile?.findViewWithTag<ImageView>("drive-folder-cover")
                    cover?.isShown == true && cover.drawable != null
                }
                scenario.onActivity {
                    it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout).setExpanded(false, false)
                }
                scenario.onActivity {
                    val root = it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                    val heading = checkNotNull(text(root, "文件夹"))
                    assertEquals(heading.context.themeColor(android.R.attr.textColorPrimary), heading.currentTextColor)
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    try {
                        root.draw(Canvas(bitmap))
                        File(context.cacheDir, "drive-inspection-grid.png").outputStream().use { output ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                val selectedFolder = scenario.onActivity { activity ->
                    val folder = checkNotNull(text(activity.window.decorView, "封面测试文件夹"))
                    assertTrue(generateSequence(folder as View) { it.parent as? View }.first(View::isLongClickable).performLongClick())
                    model.entries.first { it.id == coverFolder }
                }
                tapChoice(scenario, "选择文件夹封面")
                expect(scenario) { windowText("原始封面.jpg") != null && windowText("子文件夹封面") != null }
                scenario.onActivity {
                    val row = checkNotNull(windowText("原始封面.jpg"))
                    assertTrue(generateSequence(row as View) { it.parent as? View }.first(View::isClickable).performClick())
                    val child = checkNotNull(windowText("子文件夹封面"))
                    assertTrue(generateSequence(child as View) { it.parent as? View }.first(View::isClickable).performClick())
                    assertTrue(checkNotNull(windowText("保存封面")).performClick())
                    assertEquals(DriveView.Icons, DrivePresentation.load(context.appearancePreferences()).view)
                }
                expect(scenario) { model.folderCovers.selection(selectedFolder) == listOf("old", coverChild) }
                val request = scenario.onActivity { model.folderCover(selectedFolder) }
                assertNotNull(runBlocking { model.folderCovers.load(request) })
                scenario.onActivity { model.applyFolderCover(selectedFolder, emptyList()) }
            }
        } finally {
            initial.save(context.appearancePreferences())
            context.deleteSharedPreferences(historyName)
        }
    }

    @Test
    fun folderOnlyCoversUseBoundedLookAheadAndKeepManualChildOrder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "child-cover-fixture-").toFile()
        val prefsName = "drive-child-covers-${UUID.randomUUID()}"
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getCacheDir(): File = directory
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        }
        val requests = mutableListOf<String>()
        val lookedUpImages = mutableListOf<String>()
        var account = "test-account"
        var failEmpty = false
        val parent = CloudFile("parent", "父文件夹", folder = true)
        val deep = CloudFile("deep", "A", folder = true)
        val media = CloudFile("media", "B", folder = true)
        val empty = CloudFile("empty", "C", folder = true)
        val grandchild = CloudFile("deep-2", "D", folder = true)
        val descendant = CloudFile("deep-3", "E", folder = true)
        val image = CloudFile("cover", "封面.jpg", folder = false, thumbnail = Uri.parse("https://fixture.example/cover.jpg"))
        val previews = CloudPreviews(fetchImage = { url, _, _ ->
            lookedUpImages += checkNotNull(url.lastPathSegment)
            Bitmap.createBitmap(30, 24, Bitmap.Config.ARGB_8888)
        }) { null }
        fun covers() = FolderCovers(isolated, previews, PreviewDiskCache(isolated) { account }, { account }) { id ->
            requests += id
            when (id) {
                parent.id -> listOf(deep, media, empty)
                deep.id -> listOf(grandchild)
                grandchild.id -> listOf(parent, descendant)
                media.id -> listOf(image)
                empty.id -> if (failEmpty) error("Temporary fixture error") else emptyList()
                else -> error("Unexpected folder cover lookup: $id")
            }
        }
        try {
            val first = covers()
            val automatic = FolderCoverRequest(parent, emptyList(), first.key(parent, emptyList()))
            assertNotNull(first.load(automatic))
            assertEquals(listOf("parent", "deep", "deep-2", "media", "empty"), requests)
            assertEquals(listOf("cover.jpg"), lookedUpImages)

            val selection = listOf(empty.id, media.id)
            first.setSelection(parent, selection)
            val recreated = covers()
            assertEquals(selection, recreated.selection(parent))
            val manual = FolderCoverRequest(parent, selection, recreated.key(parent, selection))
            requests.clear()
            failEmpty = true
            assertNotNull(recreated.load(manual))
            failEmpty = false
            assertNotNull(recreated.load(manual))
            assertEquals(listOf("parent", "empty", "media", "parent", "empty", "media"), requests)
            recreated.invalidate(listOf(manual.key))
            requests.clear()
            assertNotNull(recreated.load(manual))
            assertEquals(listOf("parent", "empty", "media"), requests)

            account = "other-account"
            assertTrue(recreated.selection(parent).isEmpty())
            assertNull(recreated.load(manual))
        } finally {
            context.deleteSharedPreferences(prefsName)
            directory.deleteRecursively()
        }
    }

    @Test
    fun previewDiskCacheSurvivesRecreationAndScopesAccounts() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val location = Files.createTempDirectory(target.cacheDir.toPath(), "drive-cache-fixture-").toFile()
        val prefsName = "drive-cache-${UUID.randomUUID()}"
        val prefs = target.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val isolated = object : android.content.ContextWrapper(target) {
            override fun getCacheDir(): File = location
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
        try {
            prefs.edit().putBoolean(APPEARANCE_PREVIEW_CACHE, true).commit()
            var account = "first"
            val key = "fixture-image"
            val bitmap = Bitmap.createBitmap(5, 4, Bitmap.Config.ARGB_8888)
            PreviewDiskCache(isolated) { account }.write(key, bitmap, account)
            val recreated = PreviewDiskCache(isolated) { account }
            assertEquals(5, recreated.read(key, account)?.width)
            account = "second"
            assertNull(recreated.read(key, account))
            recreated.write(key, bitmap, "first")
            assertNull(recreated.read(key, "first"))
            account = "first"
            assertNotNull(recreated.read(key, account))
            prefs.edit().putBoolean(APPEARANCE_PREVIEW_CACHE, false).commit()
            assertNull(recreated.read(key, account))
            assertEquals(1, recreated.usage().entries)
            prefs.edit().putBoolean(APPEARANCE_PREVIEW_CACHE, true).commit()
            assertNotNull(recreated.read(key, account))
            assertEquals(0, recreated.clear().entries)
            assertNull(recreated.read(key, account))
        } finally {
            target.deleteSharedPreferences(prefsName)
            location.deleteRecursively()
        }
    }

    private fun tapChoice(scenario: FixtureActivity<MainActivity>, name: String) {
        expect(scenario) { activity ->
            val sheet = activity.supportFragmentManager.findFragmentByTag(CloudActionSheet.TAG) as? CloudActionSheet
            val list = sheet?.view?.findViewById<ListView>(R.id.cloud_sheet_actions) ?: return@expect false
            list.childCount > 0 && (0 until list.count).any { (list.adapter.getItem(it) as Choice).title == name }
        }
        scenario.onActivity { activity ->
            val sheet = activity.supportFragmentManager.findFragmentByTag(CloudActionSheet.TAG) as CloudActionSheet
            val list = checkNotNull(sheet.view).findViewById<ListView>(R.id.cloud_sheet_actions)
            val index = (0 until list.count).first { (list.adapter.getItem(it) as Choice).title == name }
            assertTrue(list.performItemClick(list.getChildAt(index - list.firstVisiblePosition), index, list.adapter.getItemId(index)))
        }
    }

    private fun openViewOptions(scenario: FixtureActivity<MainActivity>) {
        expect(scenario) { activity ->
            val toolbar = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)?.toolbar
                ?: return@expect false
            listOf("搜索", "添加", "检视").all { toolbarAction(toolbar, it) != null }
        }
        scenario.onActivity { activity ->
            val toolbar = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout).toolbar
            assertTrue(checkNotNull(toolbarAction(toolbar, "检视")).performClick())
        }
    }

    private fun toolbarAction(root: View, title: String): View? {
        if (root.isShown && root.isClickable && root.contentDescription == title) return root
        val group = root as? ViewGroup ?: return null
        return (0 until group.childCount).firstNotNullOfOrNull { toolbarAction(group.getChildAt(it), title) }
    }

    private fun choiceList(activity: MainActivity): ListView? {
        val sheet = activity.supportFragmentManager.findFragmentByTag(CloudActionSheet.TAG) as? CloudActionSheet
        return sheet?.view?.findViewById(R.id.cloud_sheet_actions)
    }

    private fun choiceText(list: ListView, index: Int): TextView {
        val row = list.getChildAt(index - list.firstVisiblePosition) ?: list.adapter.getView(index, null, list)
        return checkNotNull(row.findViewById(android.R.id.text1))
    }

    private fun windowText(value: String): TextView? = if (Build.VERSION.SDK_INT >= 29) {
        WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { text(it, value) }
    } else null

    private fun text(root: View, value: String): TextView? {
        if (root is TextView && root.isShown && root.text.toString() == value) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            text(root.getChildAt(index), value)?.let { return it }
        }
        return null
    }

    private fun expect(scenario: FixtureActivity<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val limit = SystemClock.elapsedRealtime() + 10_000
        do {
            if (scenario.onActivity(predicate)) return
            SystemClock.sleep(80)
        } while (SystemClock.elapsedRealtime() < limit)
        error("Drive inspection did not render the expected state")
    }
}
