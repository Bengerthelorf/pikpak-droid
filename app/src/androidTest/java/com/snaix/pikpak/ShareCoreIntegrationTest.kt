package com.snaix.pikpak

import android.net.Uri
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class ShareCoreIntegrationTest {
    @Test fun nestedShareRefreshAndRestrictedPreviewUseTheNativeContract() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = CloudApi(context)
        val info = api.call("core_info") as JSONObject
        val version = info.getString("version")
        val supported = !CoreReleases.newer("0.0.67", version)
        if (InstrumentationRegistry.getArguments().getString("requireShareCore") == "true") {
            assertTrue("Active core is $version, expected at least 0.0.67", supported)
        }
        assumeTrue("Nested shares require core 0.0.67 or later", supported)
        assertTrue(api.supportsShareBrowsing)

        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "jni-share-").toFile()
        val session = File(directory, "session.json")
        val bases = listOf("PIKPAK_DRIVE_BASE_URL", "PIKPAK_AUTH_BASE_URL").associateWith { Os.getenv(it) }
        try {
            session.writeText("""{"access_token":"fixture","refresh_token":"fixture","expires_at_unix":4102444800}""")
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 10_000
                bases.keys.forEach { Os.setenv(it, "http://127.0.0.1:${server.localPort}", true) }
                val init = JSONObject().put("op", "init").put("path", session.path)
                assertTrue(JSONObject(NativeClient.request(init.toString())).getBoolean("value"))
                val http = FutureTask {
                    List(5) {
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val reader = socket.getInputStream().bufferedReader()
                            val line = reader.readLine().split(" ")
                            assertEquals("GET", line[0])
                            while (!reader.readLine().isNullOrEmpty()) {
                                // Consume the headers before sending the fixture response.
                            }
                            val uri = Uri.parse("http://localhost" + line[1])
                            val bytes = reply(uri).encodeToByteArray()
                            socket.getOutputStream().apply {
                                write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").encodeToByteArray())
                                write(bytes)
                            }
                            uri
                        }
                    }
                }
                Thread(http, "share-fixture-http").apply { isDaemon = true; start() }
                // The web link encodes folder AQIDbb relative to share AAAAAA.
                val input = checkNotNull(parseShareInput("https://mypikpak.com/s/AAAAAA/AQIDbb_AQI?pass_code=a%2Bb"))
                val opened = api.call("share_open", "url" to input.link, "code" to input.accessCode) as JSONObject
                val handle = opened.getString("share")
                val token = opened.getString("token")
                assertEquals("https://mypikpak.com/s/AAAAAA/AQIDbb_AQI", handle)
                assertEquals("clip", opened.getJSONArray("files").files().single().id)
                val refreshed = api.call("share_folder", "share" to handle, "token" to token, "parent" to "") as JSONArray
                assertEquals("clip", refreshed.files().single().id)
                val preview = api.call("share_preview", "share" to handle, "token" to token, "id" to "clip") as JSONObject
                assertEquals("clip", preview.getString("id"))
                assertTrue(preview.isNull("size"))
                assertTrue(preview.isNull("duration_seconds"))
                val quality = preview.getJSONArray("streams").objects().single()
                assertEquals("720p", quality.getString("label"))
                assertFalse(quality.getBoolean("available"))
                assertTrue(quality.getString("reason").contains("quota"))
                assertFalse(preview.toString().contains("example.invalid"))
                val requests = http.get(10, TimeUnit.SECONDS)
                assertEquals("a+b", requests.first().getQueryParameter("pass_code"))
                assertEquals(listOf("", "AQIDbb", "AQIDbb"), requests.filter { it.path?.endsWith("/detail") == true }
                    .map { it.getQueryParameter("parent_id") })
                assertTrue(requests.all { it.getQueryParameter("share_id") == "AAAAAA" })
                assertTrue(requests.drop(1).all { it.getQueryParameter("pass_code_token") == "fixture-token" })
                assertEquals("clip", requests.last().getQueryParameter("file_id"))
            }
        } finally {
            bases.forEach { (key, value) -> if (value == null) Os.unsetenv(key) else Os.setenv(key, value, true) }
            try { api.call("init") } finally { directory.deleteRecursively() }
        }
    }

    private fun reply(uri: Uri): String = when (uri.path) {
        "/drive/v1/share" -> """{"share_status":"OK","pass_code_token":"fixture-token"}"""
        "/drive/v1/share/detail" -> if (uri.getQueryParameter("parent_id").isNullOrEmpty()) {
            """{"files":[{"id":"AQIDbb","name":"Folder","kind":"drive#folder"}]}"""
        } else {
            """{"files":[{"id":"clip","name":"Sample.mp4","kind":"drive#file"}]}"""
        }
        "/drive/v1/share/file_info" -> """{"share_status":"OK","file_info":{"id":"clip","name":"Sample.mp4",
            "mime_type":"video/mp4","thumbnail_link":"https://example.invalid/thumb","medias":[
            {"media_name":"720p","is_visible":true,"need_more_quota":true,"link":{"url":"https://example.invalid/media"}},
            {"media_name":"hidden","is_visible":false}]}}"""
        else -> error("Unexpected fixture endpoint ${uri.path}")
    }
}
