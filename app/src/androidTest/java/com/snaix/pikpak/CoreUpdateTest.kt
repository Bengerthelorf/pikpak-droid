package com.snaix.pikpak

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CoreUpdateTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun corruptImportKeepsSelectedCore() {
        val prior = CoreRuntime.pending(context)
        val invalid = runCatching { CoreRuntime.stage(context, "0".repeat(64)) { it.writeBytes(ByteArray(256)) } }
        assertTrue(invalid.isFailure)
        assertEquals(prior, CoreRuntime.pending(context))
    }

    @Test fun importUpstreamBinaryThroughSettings() {
        val hash = InstrumentationRegistry.getArguments().getString("coreImportSha")
        assumeTrue(hash != null)
        val file = File(context.cacheDir, "qa-upstream-api1.so")
        assertTrue(file.isFile)
        launchFixtureActivity(CoreSettingsActivity::class.java, Intent(context, CoreSettingsActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.workspace_settings_preferences) as CorePreferences
                fragment.importDevelopmentFile(Uri.fromFile(file), checkNotNull(hash))
            }
            val deadline = SystemClock.elapsedRealtime() + 30000
            while (CoreRuntime.pending(context).sha256 != hash && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertEquals(hash, CoreRuntime.pending(context).sha256)
        }
    }

    @Test fun coldStartUsesSelectedUpstreamBinary() {
        val hash = InstrumentationRegistry.getArguments().getString("coreActiveSha")
        assumeTrue(hash != null)
        assertEquals(hash, CoreRuntime.active.sha256)
        val info = JSONObject(NativeClient.request("""{"op":"core_info"}""")).getJSONObject("value")
        assertEquals(1, info.getInt("api"))
        assertEquals(CoreRuntime.active.revision, info.getString("upstream"))
        assertTrue(File("/proc/self/maps").useLines { lines -> lines.any { it.contains("/cores/$hash.so") } })
        CloudApi(context).call("init")
    }
}
