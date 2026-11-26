package com.snaix.pikpak

import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class DrawerAccountDataTest {
    private val app = WorkspaceFixtureApplication(InstrumentationRegistry.getInstrumentation().targetContext)

    @After fun cleanUpFixture() = app.close()

    @Test fun decodesUsageAndDistinguishesMissingZeroAndUnlimited() {
        val profile = DrawerAccountData.from(JSONObject("""
            {
                "user": {"name":"Sample account", "picture":"https://example.invalid/avatar.png"},
                "quota": {"usage":"0", "limit":"250", "usage_in_trash":"0"},
                "vip": {"type":"premium", "status":"active"},
                "bandwidth": {
                    "download":{"total_assets":100,"assets":40},
                    "upload":{"total_assets":0,"assets":0},
                    "offline":{"total_assets":100},
                    "download_daily":{"total_assets":0}
                }
            }
        """.trimIndent()))
        assertEquals("Sample account", profile.displayName)
        assertEquals("https://example.invalid/avatar.png", profile.avatar.toString())
        assertEquals(DrawerQuota(0L, 250L), profile.storage)
        assertEquals(0L, profile.trashUsage)
        assertEquals(60L, profile.quota(DrawerQuotaKind.Download)?.remaining)
        assertEquals(DrawerQuota(0L, 0L), profile.quota(DrawerQuotaKind.Upload))
        assertEquals(DrawerQuota(null, 100L), profile.quota(DrawerQuotaKind.Offline))
        assertNull(profile.quota(DrawerQuotaKind.Offline)?.remaining)
        assertEquals(DrawerQuota(null, null, unlimited = true), profile.quota(DrawerQuotaKind.DownloadDaily))
        val unknown = DrawerAccountData.from(JSONObject("""{"bandwidth":{"download_daily":{}}}"""))
        assertNull(unknown.quota(DrawerQuotaKind.DownloadDaily))
        val unlimited = DrawerAccountData.from(JSONObject("""{"quota":{"is_unlimited":true,"usage":"10"}}"""))
        assertEquals(DrawerQuota(10L, null, unlimited = true), unlimited.storage)
    }

    @Test fun cacheOnlyRestoresTheMatchingOwner() {
        val path = File(app.cacheDir, "drawer-account-test-${UUID.randomUUID()}.json")
        val cache = DrawerAccountCache(path)
        val profileA = DrawerAccountData.from(JSONObject("""{"user":{"name":"Account A"},"quota":{"usage":"15"}}"""))
        val profileB = DrawerAccountData.from(JSONObject("""{"user":{"name":"Account B"},"quota":{"usage":"35"}}"""))
        try {
            cache.save("owner-a", profileA)
            assertEquals(profileA, DrawerAccountCache(path).load("owner-a"))
            assertNull(cache.load("owner-b"))
            cache.clear("owner-b")
            assertEquals(profileA, cache.load("owner-a"))
            cache.save("owner-b", profileB)
            assertNull(cache.load("owner-a"))
            assertEquals(profileB, cache.load("owner-b"))
        } finally {
            cache.clear("owner-a")
            cache.clear("owner-b")
        }
        assertNull(cache.load("owner-b"))
    }

    @Test fun drawerAndAccountPageShareOneOnDemandRead() = runBlocking {
        val requests = AtomicInteger()
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> true
                "files" -> JSONArray()
                "account" -> JSONObject().put("user", JSONObject().put("name", "Fixture ${requests.incrementAndGet()}"))
                else -> error("Unexpected fixture request: $operation")
            }
        }
        withContext(Dispatchers.Main) {
            val model = CloudModel(app, api = gateway)
            try {
                withTimeout(10_000) { model.observe { model.signedIn && !model.loading }.first { it } }
                assertEquals(0, requests.get())
                assertNull(model.drawerAccount)
                model.loadDrawerAccount()
                model.loadDrawerAccount()
                model.open(Page.Account)
                withTimeout(10_000) { model.observe { model.drawerAccount != null && !model.loading }.first { it } }
                assertEquals("Fixture 1", model.drawerAccount?.displayName)
                model.open(Page.Files)
                model.open(Page.Account)
                model.loadDrawerAccount()
                withTimeout(10_000) { model.observe { !model.loading }.first { it } }
                assertEquals(1, requests.get())
                model.refreshFromUi()
                withTimeout(10_000) { model.observe { model.drawerAccount?.displayName }.first { it == "Fixture 2" } }
                assertEquals(2, requests.get())
            } finally {
                model.viewModelScope.cancel()
            }
        }
    }
}
