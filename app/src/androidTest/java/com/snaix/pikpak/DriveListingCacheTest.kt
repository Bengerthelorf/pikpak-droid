package com.snaix.pikpak

import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DriveListingCacheTest {
    @Test fun snapshotsRetainCompleteMetadataAndDoNotCrossAccountsOrListings() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = Files.createTempDirectory(app.cacheDir.toPath(), "listing-fixture-").toFile()
        val context = object : ContextWrapper(app) { override fun getCacheDir(): File = dir }
        try {
            val first = DriveListingCache(context)
            val second = DriveListingCache(context)
            val stale = first.beginRead()
            val files = listOf(
                CloudFile("folder", "a\nb", true, created = "2026-11-19T14:33:51Z"),
                CloudFile("huge", "影片.mp4", false, bytes = Long.MAX_VALUE, fileSize = ULong.MAX_VALUE,
                    modified = "2026-11-20T01:33:51Z", thumbnail = Uri.parse("https://example.invalid/a.jpg"), starred = true),
                CloudFile("unknown", "未知大小", false, fileSize = null),
            )
            assertNull(first.read("alice", ""))
            second.write("alice", "", files, second.beginRead())
            first.write("alice", "", emptyList(), stale)
            assertEquals(files, first.read("alice", ""))
            assertNull(first.read("bob", ""))
            assertNull(first.read("alice", "folder"))
            assertNull(first.read("alice", "", favorites = true))

            second.write("alice", "", emptyList(), second.beginRead(), favorites = true)
            assertEquals(emptyList<CloudFile>(), first.read("alice", "", favorites = true))
            assertEquals(files, first.read("alice", ""))
            first.write("alice", "", emptyList(), first.beginRead())
            assertEquals(emptyList<CloudFile>(), second.read("alice", ""))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun truncatedSnapshotNeverShowsAPartialListing() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = Files.createTempDirectory(app.cacheDir.toPath(), "listing-fixture-").toFile()
        val context = object : ContextWrapper(app) { override fun getCacheDir(): File = dir }
        try {
            val cache = DriveListingCache(context)
            val files = listOf(CloudFile("one", "一", false), CloudFile("two", "二", false))
            cache.write("alice", "folder", files, cache.beginRead())
            val snapshot = dir.walkTopDown().single { it.extension == "jsonl" }
            snapshot.writeText(snapshot.readLines().take(2).joinToString("\n", postfix = "\n"))
            assertNull(cache.read("alice", "folder"))
            cache.write("alice", "folder", files, cache.beginRead())
            assertEquals(files, cache.read("alice", "folder"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
