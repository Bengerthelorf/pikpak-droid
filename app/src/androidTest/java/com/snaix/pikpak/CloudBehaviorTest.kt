package com.snaix.pikpak

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBehaviorTest {
    @Test
    fun protectedShareKeepsTheCodeWhenPastedOrOpenedFromAndroid() {
        val link = "https://mypikpak.com/s/fixture_1"
        assertEquals(ShareInput(link, "Ab12"), parseShareInput("分享给你\n$link\n提取码：Ab12"))
        assertEquals(ShareInput(link, "Ab12"), parseShareInput("参考 https://example.invalid\n$link\n提取码：Ab12"))
        val query = "$link?pass_code=a%2Bb"
        assertEquals(ShareInput(query, "a+b"), parseShareInput(query))
        assertEquals(ShareInput("fixture_1", null), parseShareInput("fixture_1"))
        assertNull(parseShareInput("https://example.invalid/s/fixture_1"))
        val deep = "https://www.mypikpak.com/s/VOIQ0QFrIMiPE8EFPZYJKKxpo1/" +
            "AAAeZ9plmSBi6NG9KffhrhCAo1_VOI/VOI12345678901234567890po1/?pass_code=a%2Bb#fragment"
        assertEquals(ShareInput(deep, "a+b"), parseShareInput("Open $deep\nAccess code: ignored"))
        assertTrue(checkNotNull(parseShareInput(deep)).isNested)
        for (unsupported in listOf("$link/../folder", "$link/a%2Fb", "$link//", "$link/folder\\name",
            "https://mypikpak.com/s//fixture_1", "https://mypikpak.com/s/%66ixture_1")) {
            assertTrue(containsPikPakShare(unsupported))
            assertNull(parseShareInput(unsupported))
        }
        assertNull(parseShareInput("https://name@mypikpak.com/s/fixture_1/deep_path"))
    }

    @Test
    fun cloudEntriesKeepTimeOfDayForRecentSorting() {
        val early = CloudFile.from(JSONObject("""{"id":"z","name":"Earlier","modified_time":"2026-11-24T18:00:00Z"}"""))
        val late = CloudFile.from(JSONObject("""{"id":"a","name":"Later","modified_time":"2026-11-24T18:00:00.500Z"}"""))
        assertTrue(checkNotNull(late.modifiedAt) > checkNotNull(early.modifiedAt))
    }

    @Test
    fun listedSharesShowServerMetricsAndEncryptedVisibility() {
        val share = ListedShare.from(JSONObject("""
            {
                "share_id":"fixture", "share_url":"https://mypikpak.com/s/fixture",
                "share_to":"encryptedlink", "expiration_days":"7",
                "create_time":"2026-11-24T08:00:00Z",
                "view_count":"0", "restore_count":"9", "file_num":"3"
            }
        """.trimIndent()))
        assertEquals("需要提取码 · 7 天 · 2026-11-24", share.rowSummary)
        assertTrue(share.details().contains("浏览 · 0 次"))
        assertTrue(share.details().contains("转存 · 9 次"))
        assertTrue(share.details().contains("分享项目 · 3 项"))
        assertTrue(share.details().contains("分享 ID · fixture"))
        assertEquals("永久", ListedShare.from(JSONObject().put("expiration_days", "-1")).expiry)
        assertNull(ListedShare.from(JSONObject().put("view_count", "")).views)
    }
}
