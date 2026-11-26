package com.snaix.pikpak

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingLinksTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=Open%20Video&tr=https%3A%2F%2Fexample.invalid%2Fa%3Fx%3D1"

    @Test
    fun androidOffersTheAppForSharedTextUriListsAndDirectMagnets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intents = listOf(
            Intent(Intent.ACTION_SEND).setType("text/plain"),
            Intent(Intent.ACTION_SEND).setType("text/uri-list"),
            Intent(Intent.ACTION_VIEW, Uri.parse(magnet)).addCategory(Intent.CATEGORY_BROWSABLE),
        )
        intents.forEach { intent ->
            val match = context.packageManager.resolveActivity(intent.setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            assertEquals(MainActivity::class.java.name, match?.activityInfo?.name)
        }
        assertEquals(magnet, IncomingLinks.fromIntent(intents.last()))
    }

    @Test
    fun magnetWinsOverSourcePageInSharedTextAndDragData() {
        val url = "https://example.invalid/details/1"
        val clip = ClipData.newPlainText("Article", url).apply { addItem(ClipData.Item(Uri.parse(magnet))) }
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).apply { clipData = clip }
        assertEquals(magnet, IncomingLinks.fromIntent(intent))
        assertEquals(magnet, IncomingLinks.fromClip(clip))
        val text = "来源 $url\n下载（$magnet）"
        assertEquals(magnet, IncomingLinks.fromIntent(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)))
        val encoded = "magnet:?dn=Open&xt=urn%3Abtih%3A$hash"
        assertEquals(encoded, IncomingLinks.fromClip(ClipData.newRawUri("", Uri.parse(encoded))))
        val named = "magnet:?xt=urn:btih:$hash&dn=Film_(2026)"
        assertEquals(named, IncomingLinks.fromClip(ClipData.newPlainText("", "下载 ($named)")))
    }

    @Test
    fun protectedShareCodesAndHtmlAnchorTargetsArePreserved() {
        val share = "https://mypikpak.com/s/example"
        val clip = ClipData.newPlainText("Link", share).apply { addItem(ClipData.Item("提取码：A1b2")) }
        assertEquals(ShareInput(share, "A1b2"), parseShareInput(checkNotNull(IncomingLinks.fromClip(clip))))
        val html = "<a href=\"$magnet\">Open video</a>"
        assertEquals(magnet, IncomingLinks.fromClip(ClipData.newHtmlText("Link", "Open video", html)))
    }

    @Test
    fun deepSharesKeepTheirFolderAcrossAndroidEntryPoints() {
        val base = "https://share.mypikpak.com/s/VOIQ0QFrIMiPE8EFPZYJKKxpo1/AAAeZ9plmSBi6NG9KffhrhCAo1_VOI"
        val link = "$base?pass_code=a%2Bb#fragment"
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
        assertEquals(link, IncomingLinks.fromIntent(view))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val match = context.packageManager.resolveActivity(view.setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(MainActivity::class.java.name, match?.activityInfo?.name)

        val clip = ClipData.newRawUri("Shared folder", Uri.parse(base)).apply { addItem(ClipData.Item("提取碼：A1b2")) }
        val send = Intent(Intent.ACTION_SEND).setType("text/uri-list").apply { clipData = clip }
        val expected = ShareInput(base, "A1b2")
        assertEquals(expected, parseShareInput(checkNotNull(IncomingLinks.fromIntent(send))))
        assertEquals(expected, parseShareInput(checkNotNull(IncomingLinks.fromClip(clip))))
        for (invalid in listOf("$base/../", "$base/%2E", base.replace("share.mypikpak.com", "mypikpak.com.example.invalid"))) {
            assertNull(IncomingLinks.fromIntent(Intent(Intent.ACTION_VIEW, Uri.parse(invalid))))
        }
    }

    @Test
    fun unrelatedContentAndInvalidMagnetsDoNotOpenConfirmation() {
        assertNull(IncomingLinks.fromClip(ClipData.newPlainText("text", "A title with no link")))
        assertNull(IncomingLinks.fromClip(ClipData.newRawUri("file", Uri.parse("content://example.invalid/1"))))
        assertNull(IncomingLinks.fromIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/"))))
        assertNull(IncomingLinks.fromIntent(Intent(Intent.ACTION_VIEW, Uri.parse("magnet:?dn=xt=urn:btih:$hash"))))
    }
}
