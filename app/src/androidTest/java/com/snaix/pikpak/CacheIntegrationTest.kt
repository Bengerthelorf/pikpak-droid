package com.snaix.pikpak

import android.app.Notification
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class CacheIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun packagedCoreListsAllDrivePagesAcrossFolders() {
        val directory = Files.createTempDirectory(context.noBackupFilesDir.toPath(), "search-fixture-").toFile()
        val session = File(directory, "session.json")
        val base = "PIKPAK_DRIVE_BASE_URL"
        val previous = Os.getenv(base)
        val api = CloudApi(context)
        try {
            session.writeText(JSONObject().put("access_token", "fixture-token").put("refresh_token", "fixture-refresh")
                .put("expires_at_unix", 4102444800L).put("user_id", "search-fixture").toString())
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 8_000
                Os.setenv(base, "http://127.0.0.1:${server.localPort}", true)
                val setup = JSONObject().put("op", "init").put("path", session.absolutePath)
                assertTrue(JSONObject(NativeClient.request(setup.toString())).getBoolean("value"))
                val fixture = FutureTask {
                    listOf("first", "second").forEachIndexed { index, id ->
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val reader = socket.getInputStream().bufferedReader()
                            val endpoint = Uri.parse("http://localhost" + reader.readLine().split(" ")[1])
                            assertEquals("/drive/v1/files", endpoint.path)
                            assertEquals("*", endpoint.getQueryParameter("parent_id"))
                            assertEquals(if (index == 0) null else "next-page", endpoint.getQueryParameter("page_token"))
                            val filters = JSONObject(checkNotNull(endpoint.getQueryParameter("filters")))
                            assertFalse(filters.getJSONObject("trashed").getBoolean("eq"))
                            assertEquals("PHASE_TYPE_COMPLETE", filters.getJSONObject("phase").getString("eq"))
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            val row = JSONObject().put("id", id).put("kind", "drive#file")
                                .put("name", "$id.txt").put("parent_id", "folder-$id")
                            val body = JSONObject().put("files", JSONArray().put(row))
                                .put("next_page_token", if (index == 0) "next-page" else "").toString()
                            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray(Charsets.US_ASCII))
                        }
                    }
                }
                Thread(fixture, "drive-search-http").apply { isDaemon = true; start() }
                val files = api.call("files", "parent" to "*").files()
                fixture.get(9, TimeUnit.SECONDS)
                assertEquals(listOf("first.txt", "second.txt"), files.map { it.name })
            }
        } finally {
            if (previous == null) Os.unsetenv(base) else Os.setenv(base, previous, true)
            try { api.call("init") } finally { directory.deleteRecursively() }
        }
    }

    @Test fun liveUpdateCarriesProgressAndStopAction() {
        val working = CacheWarmupState(true, "正在保存预览", 3, 8)
        val notification = CacheWarmupNotification.build(context, working)
        assertNotNull(notification.contentIntent)
        assertNotNull(notification.actions.single().actionIntent)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        if (Build.VERSION.SDK_INT >= 36) {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            assertTrue("android.permission.POST_PROMOTED_NOTIFICATIONS" in info.requestedPermissions.orEmpty())
            assertTrue(notification.hasPromotableCharacteristics())
            assertTrue(notification.extras.getBoolean("android.requestPromotedOngoing"))
            val style = Notification.Builder.recoverBuilder(context, notification).style as Notification.ProgressStyle
            assertEquals(3, style.progress)
            assertEquals(8, style.progressMax)
            val discovery = CacheWarmupNotification.build(context, CacheWarmupState(true, "正在更新目录统计"))
            assertTrue((Notification.Builder.recoverBuilder(context, discovery).style as Notification.ProgressStyle).isProgressIndeterminate)
        }
    }

    /** Test the packaged ARM64 JNI, including a read after closing the only HTTP endpoint. */
    @Test fun nativeIndexPersistsFolderSizeAndContentTimeWithoutNetwork() {
        val folder = Files.createTempDirectory(context.noBackupFilesDir.toPath(), "index-fixture-").toFile()
        val session = File(folder, "session.json")
        val base = "PIKPAK_DRIVE_BASE_URL"
        val previous = Os.getenv(base)
        val api = CloudApi(context)
        val timestamp = "2026-11-24T12:34:56Z"
        fun init() = JSONObject(NativeClient.request(JSONObject().put("op", "init")
            .put("path", session.absolutePath).toString())).getBoolean("value")
        fun cached() = api.call("folder_stats_cached", "ids" to listOf("AN", "child", "empty", "unknown")) as JSONObject
        try {
            val info = api.call("core_info") as JSONObject
            assertTrue(info.getJSONArray("capabilities").let { caps -> (0 until caps.length()).any { caps.getString(it) == "folder_index" } })
            session.writeText(JSONObject().put("access_token", "fixture-token").put("refresh_token", "fixture-refresh")
                .put("expires_at_unix", 4102444800L).put("user_id", "fixture-owner").toString())
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                Os.setenv(base, "http://127.0.0.1:${server.localPort}", true)
                assertTrue(init())
                assertTrue(cached().isNull("AN"))
                val rows = JSONArray()
                fun add(id: String, parent: String, file: Boolean = false) {
                    val row = JSONObject().put("id", id).put("name", id).put("parent_id", parent)
                        .put("kind", if (file) "drive#file" else "drive#folder")
                    if (file) row.put("size", "321").put("modified_time", timestamp)
                    rows.put(row)
                }
                add("AN", "")
                add("child", "AN")
                add("empty", "AN")
                add("clip", "child", file = true)
                server.soTimeout = 10000
                val response = FutureTask {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        assertTrue(reader.readLine().startsWith("GET /drive/v1/files?"))
                        while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                        val bytes = JSONObject().put("files", rows).put("sync_time", timestamp).toString().encodeToByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                            write(bytes)
                        }
                    }
                }
                Thread(response, "index-fixture-http").apply { isDaemon = true; start() }
                val token = "test-folder-index"
                api.call("folder_stats_begin", "scan" to token)
                try { api.call("folder_index_sync", "scan" to token) }
                finally { api.call("folder_stats_cancel", "scan" to token) }
                response.get(10, TimeUnit.SECONDS)
            }
            assertTrue(init())
            val results = cached()
            for (id in listOf("AN", "child")) {
                assertEquals("321", results.getJSONObject(id).getString("bytes"))
                assertEquals(java.time.Instant.parse(timestamp), java.time.Instant.parse(results.getJSONObject(id).getString("modified_at")))
                assertFalse(results.getJSONObject(id).getBoolean("is_empty"))
            }
            assertTrue(results.getJSONObject("empty").getBoolean("is_empty"))
            assertTrue(results.isNull("unknown"))
            assertTrue(folder.walk().any { it.extension == "sqlite3" && it.length() > 0 })
        } finally {
            if (previous == null) Os.unsetenv(base) else Os.setenv(base, previous, true)
            try { api.call("init") } finally { folder.deleteRecursively() }
        }
    }
}
