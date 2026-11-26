package com.snaix.pikpak

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ImageButton
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.descendants
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import dev.oneuiproject.oneui.layout.NavDrawerLayout
import dev.oneuiproject.oneui.navigation.widget.DrawerNavigationView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class AppSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun nativeLibraryStartsAndHashesAFile() {
        val file = File.createTempFile("gcid-", ".txt", instrumentation.targetContext.cacheDir)
        try {
            val bytes = "PikPak Android\n".encodeToByteArray()
            file.writeBytes(bytes)
            val digest = MessageDigest.getInstance("SHA-1")
            val expected = digest.digest(digest.digest(bytes)).joinToString("") { "%02X".format(it) }
            val request = JSONObject().put("op", "hash").put("path", file.path)
            val reply = JSONObject(NativeClient.request(request.toString()))
            assertEquals(expected, reply.getString("value").uppercase())
        } finally {
            file.delete()
        }
    }

    @Test
    fun nativeLoginPersistsSessionInAppStorage() {
        val directory = Files.createTempDirectory(
            instrumentation.targetContext.noBackupFilesDir.toPath(), "auth-fixture-",
        ).toFile()
        val session = File(directory, "session.json")
        val authBase = "PIKPAK_AUTH_BASE_URL"
        val previousAuthBase = Os.getenv(authBase)
        try {
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5_000
                val responses = mapOf(
                    "/v1/shield/captcha/init" to """{"captcha_token":"fixture-challenge","expires_in":3600}""",
                    "/v1/auth/signin" to """{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600}""",
                )
                val fixture = FutureTask {
                    for ((path, body) in responses) {
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            assertEquals("POST $path HTTP/1.1", input.readLine())
                            val headers = generateSequence(input::readLine).takeWhile(String::isNotEmpty).toList()
                            val contentLength = headers.first { it.startsWith("content-length:", ignoreCase = true) }
                                .substringAfter(':').trim().toInt()
                            repeat(contentLength) { check(input.read() != -1) }
                            socket.getOutputStream().write((
                                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                                ).toByteArray(Charsets.US_ASCII))
                        }
                    }
                }
                Thread(fixture, "pikpak-auth-fixture").start()
                Os.setenv(authBase, "http://127.0.0.1:${server.localPort}", true)
                val request = JSONObject().put("op", "login").put("path", session.path)
                    .put("email", "fixture@example.invalid").put("password", "fixture-password")
                val reply = JSONObject(NativeClient.request(request.toString()))
                fixture.get(6, TimeUnit.SECONDS)
                assertTrue("Native login returned ${reply.optString("code")}", reply.has("value"))
                assertEquals("fixture-access", JSONObject(session.readText()).getString("access_token"))
                assertEquals(0x180, Os.stat(session.path).st_mode and 0x1ff)
                val init = JSONObject().put("op", "init").put("path", session.path)
                assertTrue(JSONObject(NativeClient.request(init.toString())).getBoolean("value"))
            }
        } finally {
            if (previousAuthBase == null) Os.unsetenv(authBase) else Os.setenv(authBase, previousAuthBase, true)
            directory.deleteRecursively()
        }
    }

    @Test
    fun drawerNavigationAndSelectionRenderThroughSesl(): Unit = WorkspaceFixtureApplication(instrumentation.targetContext).use { fixtureApp ->
        fixtureApp.appearancePreferences().edit().putBoolean(DRIVE_FOLDER_STATS, false).commit()
        val gateway = FixtureGateway()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
        launchFixtureActivity(MainActivity::class.java, intent).use { scenario ->
            lateinit var model: CloudModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity, viewModelFactory {
                    initializer { CloudModel(fixtureApp, gateway) }
                })["test-fixture", CloudModel::class.java]
                activity.showFixture(model)
            }
            try {
                expect(scenario, "fixture listing") { findText(it.window.decorView, "旅行归档") != null }
            } catch (error: AssertionError) {
                scenario.onActivity { activity ->
                    val root = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                    val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.workspace_list)
                    val report = "stage=${model.starting} signed=${model.signedIn} page=${model.page} " +
                        "entries=${model.visibleEntries.map(CloudFile::name)} adapter=${list?.adapter?.itemCount} " +
                        "list=${list?.width}x${list?.height} shown=${list?.isShown} children=${list?.childCount}"
                    android.util.Log.e("PikPakSmoke", report)
                    if (root != null && root.width > 0 && root.height > 0) {
                        capture(root, activity.cacheDir, "sesl-fixture-failure.png")
                    }
                }
                throw error
            }
            expect(scenario, "native toolbar actions") {
                val menu = it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout).toolbar.menu
                (0 until menu.size()).map { index -> menu.getItem(index).title.toString() }
                    .containsAll(listOf("搜索", "添加", "检视", "刷新"))
            }
            scenario.onActivity { activity ->
                val root = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                checkNotNull(root.toolbar.navigationIcon)
                root.setDrawerOpen(true, animate = false)
            }
            expect(scenario, "profile name and initial") { activity ->
                val root = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                root.drawerOffset > 0f && root.findViewById<TextView>(R.id.drawer_profile_name).text == "Avery Chen" &&
                    root.findViewById<TextView>(R.id.drawer_initial).text == "A"
            }
            scenario.onActivity { activity ->
                val root = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                capture(root, activity.cacheDir, "sesl-drawer.png")
            }
            if (scenario.onActivity { DrawerQuotaKind.Storage in model.drawerQuota.visible }) {
                revealDrawerItem(scenario, "Capacity footer") { it.findViewById(R.id.drawer_quota_panel) }
                scenario.onActivity { activity ->
                    val panel = activity.findViewById<ViewGroup>(R.id.drawer_quota_panel)
                    val progress = panel.findViewById<androidx.appcompat.widget.SeslProgressBar>(R.id.drawer_quota_storage_progress)
                    assertEquals(10, progress.progress)
                    checkNotNull(findText(panel, "10 GB / 100 GB"))
                    if (model.drawerQuota.density == DrawerQuotaDensity.Detailed) {
                        check(checkNotNull(findText(panel, activity.getString(R.string.drawer_collapse_usage))).performClick())
                    }
                    check(checkNotNull(findText(panel, activity.getString(R.string.drawer_expand_usage))).performClick())
                    checkNotNull(findText(panel, activity.getString(R.string.ui_quota_remaining, "90 GB")))
                    check(checkNotNull(findText(panel, activity.getString(R.string.drawer_collapse_usage))).performClick())
                    check(findText(panel, activity.getString(R.string.ui_quota_remaining, "90 GB")) == null)
                }
            }
            scenario.onActivity { check(it.findViewById<View>(R.id.drawer_profile).performClick()) }
            expect(scenario) {
                model.page == Page.Account && it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout).drawerOffset == 0f
            }

            expect(scenario, "account does not contain preferences") {
                findText(it.findViewById(R.id.workspace_list), "云盘容量") != null && findText(it.window.decorView, "外观") == null
            }
            scenario.onActivity {
                it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout).setDrawerOpen(true, animate = false)
            }
            expect(scenario, "One UI drawer header gear") {
                val root = it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                val gear = root.findViewById<ImageButton>(dev.oneuiproject.oneui.design.R.id.oui_des_drawer_header_button)
                root.drawerOffset > 0f && gear?.isShown == true && gear.contentDescription == "设置"
            }
            awaitFixtureActivity(SettingsActivity::class.java) {
                scenario.onActivity { activity ->
                    val root = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                    capture(root, activity.cacheDir, "oneui-drawer-settings.png")
                    val gear = root.findViewById<ImageButton>(dev.oneuiproject.oneui.design.R.id.oui_des_drawer_header_button)
                    check(gear.performClick())
                }
            }.use { settings ->
                expect(settings, "One UI settings screen") {
                    val toolbar = it.findViewById<dev.oneuiproject.oneui.layout.ToolbarLayout>(R.id.workspace_settings_toolbar)
                    toolbar.showNavigationButtonAsBack && findText(it.window.decorView, "外观") != null &&
                        findText(it.window.decorView, "文件缩略图") != null &&
                        findText(it.window.decorView, it.getString(R.string.app_language_title)) != null
                }
                settings.onActivity { activity ->
                    val root = activity.findViewById<dev.oneuiproject.oneui.layout.ToolbarLayout>(R.id.workspace_settings_toolbar)
                    capture(root, activity.cacheDir, "oneui-settings.png")
                }
            }
            expect(scenario, "settings closes to its originating workspace") {
                val root = it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                model.page == Page.Account && root.drawerOffset == 0f && findText(root.findViewById(R.id.workspace_list), "云盘容量") != null
            }
            scenario.onActivity { model.open(Page.Files) }
            expect(scenario) { findText(it.window.decorView, "旅行归档") != null }
            scenario.onActivity { activity ->
                val root = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                capture(root, activity.cacheDir, "sesl-drive.png")
                val text = checkNotNull(findText(root, "旅行归档"))
                check(checkNotNull(clickableParent(text)).performClick())
            }
            expect(scenario, "single One UI up button") {
                val root = it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                findText(root, "行程笔记.txt") != null && root.showNavigationButtonAsBack &&
                    root.expandedSubtitle == "云盘" && findText(root, "返回上一级") == null
            }
            scenario.onActivity { activity ->
                val root = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                val search = checkNotNull(findView(root.toolbar) {
                    it.contentDescription == "搜索" || (it is TextView && it.text.toString() == "搜索")
                })
                check(search.performClick())
            }
            expect(scenario, "search opens with entire drive scope") {
                searchField(it) != null && model.location.search?.scope == DriveSearchScope.EntireDrive &&
                    findText(it.window.decorView, it.getString(R.string.search_scope_all)) != null
            }
            assertEquals(0, gateway.driveSearchReads.get())
            scenario.onActivity { clickRow(it, it.getString(R.string.search_scope)) }
            scenario.onActivity { activity ->
                val sheet = activity.supportFragmentManager.findFragmentByTag(CloudActionSheet.TAG) as CloudActionSheet
                check(sheet.heading == activity.getString(R.string.search_scope))
            }
            clickSheetChoice(scenario, "搜索范围", "当前文件夹（旅行归档）")
            scenario.onActivity { checkNotNull(searchField(it)).setQuery("行程笔记", false) }
            expect(scenario, "folder-local search") {
                findText(it.window.decorView, "行程笔记.txt") != null && findText(it.window.decorView, "交通") == null
            }
            scenario.onActivity { activity ->
                val text = checkNotNull(findText(activity.window.decorView, "行程笔记.txt"))
                check(checkNotNull(clickableParent(text)).performLongClick())
            }
            expect(scenario, "select filtered file") {
                val root = it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                root.isActionMode && model.selected == setOf("notes")
            }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            expect(scenario, "first back clears selection") {
                val root = it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                !root.isActionMode && root.isSearchMode && model.selected.isEmpty() && model.query == "行程笔记"
            }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            expect(scenario, "second back closes search within the same folder") {
                model.folder.id == "trips" && model.query.isBlank() && findText(it.window.decorView, "交通") != null
            }
            scenario.onActivity { activity ->
                val root = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                check(checkNotNull(findView(root.toolbar) { it.contentDescription == "搜索" || (it is TextView && it.text == "搜索") }).performClick())
            }
            expect(scenario, "subsequent search again defaults to the drive") {
                searchField(it) != null && model.location.search?.scope == DriveSearchScope.EntireDrive
            }
            scenario.onActivity { checkNotNull(searchField(it)).setQuery("全盘匹配", true) }
            expect(scenario, "matches outside this folder") {
                model.location.search != null && findText(it.window.decorView, "全盘匹配.txt") != null
            }
            scenario.onActivity { activity ->
                checkNotNull(searchField(activity)).setQuery("全盘", false)
            }
            expect(scenario, "editing the drive search") { findText(it.window.decorView, "全盘目录") != null }
            scenario.onActivity { clickRow(it, "全盘目录") }
            expect(scenario, "open folder from results") {
                model.folder.id == "elsewhere" && findText(it.window.decorView, "全盘匹配.txt") != null
            }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            try {
                expect(scenario, "back restores search and query") {
                    model.location.search != null && searchField(it)?.query?.toString() == "全盘" &&
                        findText(it.window.decorView, "全盘目录") != null
                }
            } catch (failure: AssertionError) {
                val status = scenario.onActivity { activity ->
                    val root = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                    "folder=${model.folder.id}, search=${model.location.search?.term}, model=${model.query}, " +
                        "mode=${root.isSearchMode}, field=${searchField(activity)?.query}, results=${model.entries.size}"
                }
                throw AssertionError("Search restoration failed ($status)", failure)
            }
            assertEquals(1, gateway.driveSearchReads.get())
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            expect(scenario, "leaving results restores original folder") {
                model.folder.id == "trips" && model.query.isBlank() && findText(it.window.decorView, "交通") != null
            }
            scenario.onActivity { clickRow(it, "交通") }
            expect(scenario, "nested folder path") {
                val root = it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                findText(root, "火车票.txt") != null && root.expandedSubtitle == "云盘 / 旅行归档" &&
                    findText(root, "返回上一级") == null && findText(root, "全部") == null &&
                    searchField(it) == null && root.toolbar.isShown && root.toolbar.alpha > 0.99f
            }
            scenario.onActivity { activity ->
                val root = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                capture(root, activity.cacheDir, "sesl-folder.png")
            }
            scenario.onActivity(::clickUp)
            expect(scenario, "toolbar up returns one folder") { findText(it.window.decorView, "行程笔记.txt") != null }

            scenario.onActivity { model.openShare("demo_share", "") }
            expect(scenario) { findText(it.window.decorView, "分享文件夹") != null }
            scenario.onActivity { clickRow(it, "分享文件夹") }
            expect(scenario, "nested shared folder") {
                val root = it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                findText(root, "分享说明.txt") != null && root.showNavigationButtonAsBack &&
                    findText(root, "返回上一级") == null
            }
            scenario.onActivity(::clickUp)
            expect(scenario, "share root after up") { findText(it.window.decorView, "精选文件.txt") != null }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            expect(scenario, "share returns to its opener") { findText(it.window.decorView, "行程笔记.txt") != null }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            expect(scenario) { findText(it.window.decorView, "旅行归档") != null && model.folder.id.isEmpty() }
            scenario.onActivity { activity ->
                check(findText(activity.findViewById(R.id.workspace_list), activity.getString(R.string.drawer_favorites)) == null)
                activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout).setDrawerOpen(true, animate = false)
            }
            expect(scenario, "six menu destinations and profile") { activity ->
                val drawer = activity.findViewById<DrawerNavigationView>(R.id.workspace_drawer)
                val ids = listOf(R.id.nav_files, R.id.nav_favorites, R.id.nav_recent, R.id.nav_trash,
                    R.id.nav_tasks, R.id.nav_shares)
                activity.findViewById<View>(R.id.drawer_profile).isClickable && ids.all { drawer.findMenuItem(it)?.isCheckable == true } &&
                    drawer.findMenuItem(R.id.nav_favorites)?.isVisible == true
            }
            val allFavorites = scenario.onActivity { it.getString(R.string.drawer_all_favorites) }
            revealDrawerItem(scenario, allFavorites)
            scenario.onActivity { activity ->
                check(checkNotNull(activity.fixtureDrawer().fixtureItem(allFavorites)).performClick())
            }
            expect(scenario, "favorites is a drawer destination") { activity ->
                val toolbar = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                val drawer = activity.findViewById<DrawerNavigationView>(R.id.workspace_drawer)
                model.page == Page.Favorites && drawerClosed(toolbar) && !toolbar.showNavigationButtonAsBack &&
                    findText(activity.findViewById(R.id.workspace_list), "收藏的旅行归档") != null &&
                    drawer.findMenuItem(R.id.nav_favorites)?.isChecked == true && drawer.findMenuItem(R.id.nav_files)?.isChecked == false
            }
            scenario.onActivity { clickRow(it, "收藏的旅行归档") }
            expect(scenario, "folder opened from favorites") {
                val root = it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                model.page == Page.Files && model.folder.id == "trips" && drawerClosed(root) && root.showNavigationButtonAsBack &&
                    findText(it.findViewById(R.id.workspace_list), "行程笔记.txt") != null
            }
            scenario.onActivity(::clickUp)
            expectFavoritesParent(scenario, model)
            val beforeSystemBack = scenario.onActivity { navState(it, model) }
            sendSystemBack(scenario)
            val immediatelyAfterBack = scenario.onActivity { navState(it, model) }
            try {
                expect(scenario, "favorites returns to cloud root") {
                    val root = it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                    model.page == Page.Files && model.location.parent == null && !root.showNavigationButtonAsBack &&
                        model.entries.any { entry -> entry.id == "trips" }
                }
            } catch (failure: AssertionError) {
                throw AssertionError("Cloud root return: before=[$beforeSystemBack], immediate=[$immediatelyAfterBack], settled=[" +
                    scenario.onActivity { navState(it, model) } + "]", failure)
            }

            scenario.onActivity { model.open(Page.Tasks) }
            expect(scenario, "cloud download page") { findText(it.window.decorView, "示例云下载") != null }
            if (Build.VERSION.SDK_INT >= 29) {
                scenario.onActivity { clickRow(it, "示例云下载") }
                expect(scenario, "completed task result action") {
                    WindowInspector.getGlobalWindowViews().any { findText(it, "打开下载结果") != null }
                }
                scenario.onActivity {
                    val action = WindowInspector.getGlobalWindowViews().firstNotNullOf { findText(it, "打开下载结果") }
                    val choices = generateSequence(action as View) { it.parent as? View }.filterIsInstance<ListView>().first()
                    val position = choices.getPositionForView(action)
                    check(choices.performItemClick(action, position, choices.adapter.getItemId(position)))
                }
            } else scenario.onActivity { model.openFolder(CloudFile("trips", "示例下载结果", folder = true)) }
            expect(scenario, "cloud download result folder") { findText(it.window.decorView, "行程笔记.txt") != null }
            scenario.onActivity(::clickUp)
            expect(scenario, "download result returns to tasks") {
                model.page == Page.Tasks && findText(it.window.decorView, "示例云下载") != null
            }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            expect(scenario, "tasks back to cloud root") { model.page == Page.Files && model.folder.id.isEmpty() }

            scenario.onActivity { model.openShare("demo_share", "") }
            expect(scenario) { findText(it.window.decorView, "精选文件.txt") != null }
            scenario.onActivity { activity ->
                val text = checkNotNull(findText(activity.window.decorView, "精选文件.txt"))
                check(checkNotNull(clickableParent(text)).performLongClick())
            }
            expect(scenario) {
                val root = it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                root.isActionMode && model.selected == setOf("selected-id")
            }
            clickSelectionAction(scenario, R.id.selection_save_share)
            expect(scenario, "folder selector after long press") { 
                model.selected == setOf("selected-id") && (Build.VERSION.SDK_INT < 29 ||
                    dialogText(it, "转存到这里")?.isEnabled == true)
            }
            if (Build.VERSION.SDK_INT >= 29) {
                scenario.onActivity { check(checkNotNull(dialogText(it, "转存到这里")).performClick()) }
                expect(scenario, "selected shared file sent to fixture gateway") {
                    gateway.restore?.get("ids") == listOf("selected-id") && gateway.restore?.get("parent") == ""
                }
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun fileSelectionButtonDispatchesSingleAndMultipleFileActions(): Unit = WorkspaceFixtureApplication(instrumentation.targetContext).use { fixtureApp ->
        val gateway = FixtureGateway()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
        launchFixtureActivity(MainActivity::class.java, intent).use { scenario ->
            val model = scenario.onActivity { activity ->
                ViewModelProvider(activity, viewModelFactory {
                    initializer { CloudModel(fixtureApp, gateway) }
                })["selection-fixture", CloudModel::class.java].also { activity.showFixture(it) }
            }
            expect(scenario, "fixture root") { model.entries.any { it.id == "trips" } }
            scenario.onActivity { model.openFolder(model.entries.first { it.id == "trips" }) }
            expect(scenario, "fixture folder") { findText(it.window.decorView, "行程笔记.txt") != null }
            fun longPress(title: String) = scenario.onActivity { activity ->
                val row = checkNotNull(clickableParent(checkNotNull(findText(activity.window.decorView, title))))
                check(row.performLongClick())
            }

            longPress("交通")
            expect(scenario, "folder long press opens its own sheet") {
                val sheet = it.supportFragmentManager.findFragmentByTag(CloudActionSheet.TAG) as? CloudActionSheet
                model.selected.isEmpty() && sheet?.heading == "交通" && sheet.actions.any { action -> action.title == "选择文件夹封面" }
            }
            scenario.onActivity {
                (it.supportFragmentManager.findFragmentByTag(CloudActionSheet.TAG) as CloudActionSheet).dismissNow()
            }

            longPress("行程笔记.txt")
            clickSelectionAction(scenario, R.id.selection_file_actions)
            clickSheetChoice(scenario, "行程笔记.txt", "重命名")
            expect(scenario, "rename field for the selected file") {
                val dialog = dialogText(it, "重命名")?.rootView ?: return@expect false
                findView(dialog) { view -> view is EditText && view.text.toString() == "行程笔记.txt" } != null
            }
            scenario.onActivity { check(checkNotNull(dialogText(it, "取消")).performClick()) }
            expect(scenario, "rename dismissed") { it.hasWindowFocus() && model.selected == setOf("notes") }
            scenario.onActivity { clickRow(it, "住宿清单.txt") }
            expect(scenario, "second file joins selection") { model.selected == setOf("notes", "hotels") }
            clickSelectionAction(scenario, R.id.selection_file_actions)
            clickSheetChoice(scenario, "已选 2 项", "复制到…")
            expect(scenario, "copy destination picker") { dialogText(it, "复制到这里")?.isEnabled == true }
            scenario.onActivity { check(checkNotNull(dialogText(it, "复制到这里")).performClick()) }
            expect(scenario, "both file IDs sent to fixture gateway") {
                gateway.copied?.get("parent") == "" &&
                    (gateway.copied?.get("ids") as? List<*>)?.toSet() == setOf("notes", "hotels")
            }
        }
    }

    private fun clickSelectionAction(scenario: FixtureActivity<MainActivity>, id: Int) {
        fun button(activity: MainActivity) = findView(activity.window.decorView) {
            it.id == id && it.isShown && it.isClickable
        }
        expect(scenario, "direct One UI selection action") { button(it) != null }
        scenario.onActivity { check(checkNotNull(button(it)).performClick()) }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun opensStarredItemsDirectlyFromDrawer(): Unit = WorkspaceFixtureApplication(instrumentation.targetContext).use { app ->
        app.appearancePreferences().edit().putBoolean(DRIVE_FOLDER_STATS, false).commit()
        launchFixtureActivity(MainActivity::class.java, Intent(instrumentation.targetContext, MainActivity::class.java)).use { screen ->
            val model = screen.onActivity { activity ->
                ViewModelProvider(activity, viewModelFactory {
                    initializer { CloudModel(app, FixtureGateway(extraFavorites = 5)) }
                })["starred-shortcuts", CloudModel::class.java].also { activity.showFixture(it) }
            }
            expect(screen) { model.signedIn && model.hasDriveListing }
            fun setDrawerOpen(open: Boolean) {
                screen.onActivity { it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout).setDrawerOpen(open, false) }
                expect(screen, if (open) "open drawer" else "closed drawer") { activity ->
                    val root = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                    if (open) root.drawerOffset == 1f else drawerClosed(root)
                }
            }
            fun state(expanded: Boolean) {
                val id = if (expanded) R.string.drawer_stars_expanded else R.string.drawer_stars_collapsed
                revealDrawerItem(screen, "Favorites group") { it.findViewById(R.id.nav_stars_header) }
                expect(screen, if (expanded) "expanded Favorites group" else "collapsed Favorites group") { activity ->
                    val drawer = activity.fixtureDrawer()
                    val header = drawer.findViewById<View>(R.id.nav_stars_header)
                    header?.isClickable == true &&
                        ViewCompat.getStateDescription(header)?.toString() == activity.getString(id) &&
                        drawer.findMenuItem(R.id.nav_favorites)?.isVisible == expanded
                }
            }
            fun toggleFavorites() = screen.onActivity { activity ->
                check(activity.fixtureDrawer().findViewById<View>(R.id.nav_stars_header).performClick())
            }
            fun clickDrawer(label: String) {
                revealDrawerItem(screen, label)
                screen.onActivity { check(checkNotNull(it.fixtureDrawer().fixtureItem(label)).performClick()) }
            }

            screen.onActivity { model.open(Page.Trash) }
            expect(screen) { model.page == Page.Trash && !model.loading }
            setDrawerOpen(true)
            expect(screen, "seven favorites loaded") { model.drawerFavoritesLoaded && model.drawerFavorites.size == 7 }
            state(expanded = true)
            val destinations = listOf(R.id.nav_tasks, R.id.nav_shares, R.id.nav_files, R.id.nav_recent, R.id.nav_trash)
            val positions = destinations.map { id ->
                val title = screen.onActivity { checkNotNull(it.fixtureDrawer().findMenuItem(id)?.title) }
                revealDrawerItem(screen, title)
            }
            val header = revealDrawerItem(screen, "Favorites group") { it.findViewById(R.id.nav_stars_header) }
            val allFavorites = screen.onActivity { it.getString(R.string.drawer_all_favorites) }
            val allFavoritesPosition = revealDrawerItem(screen, allFavorites)
            val ordered = positions + header + allFavoritesPosition
            assertTrue("Drawer destinations or Favorites group out of order: $ordered", ordered.zipWithNext().all { (a, b) -> a < b })
            val labels = listOf("收藏的旅行归档", "常用清单.txt") + (1..5).map { "收藏文件 $it.txt" }
            val shortcutPositions = labels.map { label -> revealDrawerItem(screen, label) }
            assertTrue("Seven favorites must appear under the group", shortcutPositions.toSet().size == 7 &&
                shortcutPositions.all { it > header && it < allFavoritesPosition })
            expect(screen, "account data ready for the capacity footer") { model.drawerAccount != null }
            val capacityPosition = revealDrawerItem(screen, "Capacity footer") { it.findViewById(R.id.drawer_quota_panel) }
            assertTrue("Capacity footer must follow the entire favorite group", capacityPosition > allFavoritesPosition)
            expect(screen, "independent capacity footer") { activity ->
                val drawer = activity.fixtureDrawer()
                val footer = activity.findViewById<View>(R.id.drawer_quota_panel)
                val language = activity.getString(R.string.app_language_title)
                findText(footer, "10 GB / 100 GB") != null &&
                    drawer.descendants.filterIsInstance<TextView>().none { it.text.toString() == language }
            }
            screen.onActivity { activity ->
                val footer = activity.findViewById<View>(R.id.drawer_quota_panel)
                footer.requestRectangleOnScreen(Rect(0, 0, footer.width, footer.height), true)
            }
            expect(screen, "capacity footer scrolls into view") { activity ->
                activity.findViewById<View>(R.id.drawer_quota_storage_progress).getGlobalVisibleRect(Rect())
            }
            state(expanded = true)
            toggleFavorites()
            state(expanded = false)
            expect(screen, "favorites children hidden while collapsed") { activity ->
                labels.none { activity.fixtureDrawer().fixtureItem(it) != null }
            }
            setDrawerOpen(false)
            setDrawerOpen(true)
            state(expanded = false)
            val firstFavorite = screen.onActivity { model.drawerFavorites.first().name }
            val originalHeight = screen.onActivity { activity ->
                val drawer = activity.fixtureDrawer()
                val list = drawer.getChildAt(0) as RecyclerView
                val row = drawer.findViewById<View>(R.id.nav_stars_header)
                val position = list.getChildAdapterPosition(checkNotNull(list.findContainingItemView(row)))
                val height = 3 * row.height + list.paddingTop + list.paddingBottom
                list.layoutParams.height.also {
                    list.layoutParams = list.layoutParams.apply { this.height = height }
                    (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 2 * row.height - activity.dp(6))
                }
            }
            try {
                expect(screen, "collapsed Favorites header at the bottom of the shortened drawer") { activity ->
                    val drawer = activity.fixtureDrawer()
                    val list = drawer.getChildAt(0) as RecyclerView
                    val row = drawer.findViewById<View>(R.id.nav_stars_header) ?: return@expect false
                    val listBounds = Rect()
                    val headerBounds = Rect()
                    list.getGlobalVisibleRect(listBounds) && row.getGlobalVisibleRect(headerBounds) &&
                        headerBounds.height() == row.height &&
                        listBounds.bottom - list.paddingBottom - headerBounds.bottom in 0..activity.dp(12)
                }
                toggleFavorites()
                expect(screen, "the first favorite is fully visible immediately after expanding") { activity ->
                    val drawer = activity.fixtureDrawer()
                    val list = drawer.getChildAt(0) as RecyclerView
                    val first = drawer.fixtureItem(firstFavorite) ?: return@expect false
                    val listBounds = Rect()
                    val rowBounds = Rect()
                    list.getGlobalVisibleRect(listBounds) && first.getGlobalVisibleRect(rowBounds) &&
                        rowBounds.height() == first.height && rowBounds.bottom <= listBounds.bottom - list.paddingBottom
                }
            } finally {
                screen.onActivity { activity ->
                    val list = activity.fixtureDrawer().getChildAt(0) as RecyclerView
                    list.layoutParams = list.layoutParams.apply { height = originalHeight }
                }
            }
            state(expanded = true)
            setDrawerOpen(false)
            setDrawerOpen(true)
            state(expanded = true)
            clickDrawer("常用清单.txt")
            expect(screen, "text preview opens from Trash using the favorite ID") {
                dialogText(it, "Drawer favorite preview") != null && model.page == Page.Trash
            }
            screen.onActivity { activity ->
                val done = checkNotNull(dialogText(activity, activity.getString(R.string.ui_done)))
                check(done.performClick())
            }
            expect(screen, "preview dismissed and workspace ready") { activity ->
                activity.hasWindowFocus() && dialogText(activity, "Drawer favorite preview") == null &&
                    drawerClosed(activity.findViewById(R.id.workspace_toolbar_layout))
            }
            setDrawerOpen(true)
            clickDrawer("收藏的旅行归档")
            expect(screen, "direct folder opens below Favorites") {
                val root = it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                drawerClosed(root) && root.showNavigationButtonAsBack && model.folder.id == "trips" &&
                    model.location.parent?.page == Page.Favorites && findText(it.findViewById(R.id.workspace_list), "行程笔记.txt") != null
            }
            screen.onActivity(::clickUp)
            expectFavoritesParent(screen, model)
        }
    }

    private fun drawerClosed(root: NavDrawerLayout): Boolean {
        if (root.drawerOffset != 0f) return false
        if (root.isLargeScreenMode) return true // The collapsed rail remains visible.
        // SemDrawerLayout announces zero before the closing animation moves its pane off screen.
        val pane = root.findViewById<View>(dev.oneuiproject.oneui.design.R.id.drawer_panel)
        return !pane.isShown || !pane.getGlobalVisibleRect(android.graphics.Rect())
    }

    private fun sendSystemBack(screen: FixtureActivity<MainActivity>) {
        if (Build.VERSION.SDK_INT < 29) {
            screen.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            return
        }
        val display = screen.onActivity { activity ->
            check(activity.hasWindowFocus())
            checkNotNull(activity.window.decorView.display).displayId
        }
        val command = instrumentation.uiAutomation.executeShellCommand("input -d $display keyevent KEYCODE_BACK")
        val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(command).bufferedReader().use { it.readText() }
        check(output.isBlank()) { "Back to fixture display $display failed: $output" }
    }

    private fun expectFavoritesParent(screen: FixtureActivity<MainActivity>, model: CloudModel) {
        try {
            expect(screen, "favorite folder returns to favorites") {
                val root = it.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
                model.page == Page.Favorites && model.location.folder == null && drawerClosed(root) && !root.showNavigationButtonAsBack &&
                    findText(it.findViewById(R.id.workspace_list), "收藏的旅行归档") != null
            }
        } catch (failure: AssertionError) {
            val state = screen.onActivity { navState(it, model) }
            throw AssertionError("Favorite folder navigation failed ($state)", failure)
        }
    }

    private fun navState(activity: MainActivity, model: CloudModel): String {
        val root = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout)
        val list = root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.workspace_list)
        val visible = model.entries.filter { findText(list, it.name) != null }.map(CloudFile::id)
        return "page=${model.page}, folder=${model.location.folder?.id}, parent=${model.location.parent?.page}, " +
            "loading=${model.loading}, error=${model.loadError}, entries=${model.entries.map(CloudFile::id)}, " +
            "drawer=${root.drawerOffset}, up=${root.showNavigationButtonAsBack}, search=${root.isSearchMode}, " +
            "selection=${root.isActionMode}, selected=${model.selected.size}, query=${model.query.isNotEmpty()}, " +
            "adapter=${list.adapter?.itemCount}, visible=$visible, " +
            "lifecycle=${activity.lifecycle.currentState}, focus=${activity.hasWindowFocus()}, " +
            "transition=${activity.navigationEventDispatcher.transitionState.value}, " +
            "history=${activity.navigationEventDispatcher.history.value}"
    }

    private fun clickSheetChoice(scenario: FixtureActivity<MainActivity>, title: String, choice: String) {
        fun list(activity: MainActivity): ListView? {
            val sheet = activity.supportFragmentManager.findFragmentByTag(CloudActionSheet.TAG) as? CloudActionSheet
            return sheet?.takeIf { it.heading == title }?.view?.findViewById(R.id.cloud_sheet_actions)
        }
        expect(scenario, "action sheet for $title") { list(it)?.childCount?.let { count -> count > 0 } == true }
        val position = scenario.onActivity {
            val choices = checkNotNull(list(it))
            (0 until choices.count).first { index -> (choices.getItemAtPosition(index) as Choice).title == choice }
                .also(choices::setSelection)
        }
        expect(scenario, "$choice visible") {
            val choices = list(it) ?: return@expect false
            position in choices.firstVisiblePosition..choices.lastVisiblePosition
        }
        scenario.onActivity {
            val choices = checkNotNull(list(it))
            val row = checkNotNull(choices.getChildAt(position - choices.firstVisiblePosition))
            check(choices.performItemClick(row, position, choices.getItemIdAtPosition(position)))
        }
    }

    @android.annotation.TargetApi(29)
    private fun dialogText(activity: MainActivity, value: String): TextView? =
        WindowInspector.getGlobalWindowViews().asSequence()
            .filter { it !== activity.window.decorView && it.display?.displayId == activity.window.decorView.display?.displayId }
            .firstNotNullOfOrNull { findText(it, value) }

    private fun findText(view: View, value: String): TextView? =
        findView(view) { it is TextView && it.text.toString() == value && it.isShown } as? TextView

    private fun searchField(activity: MainActivity): SearchView? =
        findView(activity.findViewById(R.id.workspace_toolbar_layout)) { it is SearchView && it.isShown } as? SearchView

    private fun findView(view: View, matches: (View) -> Boolean): View? {
        if (matches(view)) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findView(view.getChildAt(index), matches)?.let { return it }
        }
        return null
    }

    private fun clickRow(activity: MainActivity, title: String) {
        val list = activity.findViewById<View>(R.id.workspace_list)
        val content = if (list.isShown) list else activity.findViewById<View>(R.id.workspace_task_pager)
        val row = checkNotNull(findText(content, title))
        check(checkNotNull(clickableParent(row)).performClick())
    }

    private fun clickUp(activity: MainActivity) {
        val toolbar = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout).toolbar
        val icon = checkNotNull(toolbar.navigationIcon)
        val button = checkNotNull(findView(toolbar) { it is ImageButton && it.drawable == icon })
        check(button.performClick())
    }

    private fun clickableParent(view: View): View? = generateSequence(view) { it.parent as? View }
        .firstOrNull { it.isClickable }

    private fun capture(view: View, directory: File, fileName: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(directory, fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun <T : android.app.Activity> expect(
        scenario: FixtureActivity<T>,
        what: String = "native UI",
        condition: (T) -> Boolean,
    ) {
        val timeout = SystemClock.elapsedRealtime() + 10_000
        do {
            if (scenario.onActivity(condition)) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < timeout)
        assertTrue("Expected $what was not reached", false)
    }
}

internal class FixtureGateway(private val extraFavorites: Int = 0) : Gateway {
    val driveSearchReads = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var restore: Map<String, Any?>? = null
    @Volatile var copied: Map<String, Any?>? = null

    override fun call(operation: String, vararg args: Pair<String, Any?>): Any? {
        val input = args.toMap()
        return when (operation) {
            "init" -> true
            "files" -> when (input["parent"]) {
                "*" -> {
                    driveSearchReads.incrementAndGet()
                    JSONArray().put(file("elsewhere", "全盘目录", true)).put(file("outside", "全盘匹配.txt", false))
                }
                "elsewhere" -> JSONArray().put(file("outside", "全盘匹配.txt", false))
                "trips" -> JSONArray().put(file("notes", "行程笔记.txt", false, 2864))
                    .put(file("hotels", "住宿清单.txt", false, 128)).put(file("transport", "交通", true))
                "transport" -> JSONArray().put(file("ticket", "火车票.txt", false, 180))
                else -> JSONArray()
                    .put(file("trips", "旅行归档", true))
                    .put(file("my-pack", "My Pack", true))
                    .put(file("films", "影片收藏", true))
                    .put(file("video", "海岸线 · 片段.mp4", false, 514_627_120))
                    .put(file("image", "冬日山脊.jpg", false, 2_461_330))
                    .put(file("text", "素材清单.txt", false, 808))
            }
            "tasks" -> JSONArray().put(JSONObject().put("id", "task").put("name", "示例云下载")
                .put("phase", "PHASE_TYPE_COMPLETE").put("file_id", "trips"))
            "info" -> {
                check(input["id"] == "trips")
                file("trips", "示例下载结果", true)
            }
            "account" -> JSONObject().put("user", JSONObject().put("name", "Avery Chen"))
                .put("quota", JSONObject().put("usage", "10737418240").put("limit", "107374182400"))
            "shares", "events", "trash" -> JSONArray()
            "starred" -> JSONArray().put(file("trips", "收藏的旅行归档", true))
                .put(file("starred-note", "常用清单.txt", false)).apply {
                    for (index in 1..extraFavorites) put(file("star-file-$index", "收藏文件 $index.txt", false))
                }
            "text" -> {
                check(input["id"] == "starred-note")
                JSONObject().put("text", "Drawer favorite preview")
            }
            "share_open" -> JSONObject().put("share", "demo_share").put("token", "fixture")
                .put("files", sharedRoot())
            "share_folder" -> if (input["parent"] == "shared-folder") {
                JSONArray().put(file("readme", "分享说明.txt", false, 35))
            } else sharedRoot()
            "share_save" -> { restore = input; null }
            "copy" -> { copied = input; null }
            else -> error("Unexpected fixture operation: $operation")
        }
    }

    private fun sharedRoot() = JSONArray()
        .put(file("shared-folder", "分享文件夹", true))
        .put(file("selected-id", "精选文件.txt", false, 30))
        .put(file("other-id", "其他文件.txt", false, 33))

    private fun file(id: String, name: String, folder: Boolean, bytes: Long = 0) = JSONObject()
        .put("id", id).put("name", name).put("kind", if (folder) "Folder" else "File")
        .put("size", bytes).put("modified_time", "2026-11-24T10:00:00Z")
}
