package com.snaix.pikpak

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.nio.file.Files

class CoreReleasesTest {
    private val binary = "pikpaktui-android-arm64-v8a-api1.so"
    private val root = "https://github.com/Bengerthelorf/pikpaktui/releases/download/v1.2.3"
    private val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun asset(name: String = binary) = JSONObject()
        .put("name", name).put("state", "uploaded").put("size", 3)
        .put("digest", "sha256:$sha").put("browser_download_url", "$root/$name")

    private fun release(vararg assets: JSONObject) = JSONObject()
        .put("tag_name", "v1.2.3").put("prerelease", false).put("draft", false)
        .put("assets", JSONArray(assets.toList()))

    private fun parse(json: JSONObject) =
        CoreReleases.parseRelease(json) { _, _ -> error("Unexpected checksum fetch") }

    private fun rejected(action: () -> Unit) {
        assertTrue(runCatching(action).exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun parsesOnlyStableExactOfficialAssetsAndNumericVersions() {
        assertEquals(CoreRelease("1.2.3", sha, 3, "$root/$binary"), parse(release(asset())))
        assertTrue(CoreReleases.newer("v1.10.0", "1.9.99"))
        assertFalse(CoreReleases.newer("v1.2.3", "1.2.3"))
        assertTrue(CoreReleases.newer("123456789123456789123456789.0.0", "9223372036854775807.99.0"))
        listOf("1.2", "1.02.3", "1.2.3-rc1", "1.2.3+build", "../1.2.3", "1.2.3 ").forEach {
            rejected { CoreReleases.newer(it, "0.0.1") }
        }
        val missing = runCatching { parse(release(asset("pikpaktui-aarch64-linux.tar.gz"))) }.exceptionOrNull()
        assertTrue(missing?.message.orEmpty().contains("v1.2.3 尚未发布 Android ARM64 核心"))
        rejected { parse(release(asset(), asset())) }
        rejected { parse(release(asset()).put("draft", true)) }
        rejected { parse(release(asset()).put("prerelease", true)) }
        rejected { parse(release(asset().put("state", "new"))) }
        rejected { parse(release(asset().put("size", Long.MAX_VALUE))) }
        rejected { parse(release(asset().put("digest", "sha1:$sha"))) }
        for (url in listOf(
            "http://github.com/Bengerthelorf/pikpaktui/releases/download/v1.2.3/$binary",
            "$root/other.so",
            "$root/$binary?other=1",
            root.replace("github.com", "github.com.example.org") + "/$binary",
            root.replace("Bengerthelorf/", "OtherUser/") + "/$binary",
            root.replace("v1.2.3", "v1.2.2") + "/$binary",
        )) {
            rejected { parse(release(asset().put("browser_download_url", url))) }
        }
    }

    @Test
    fun usesOneExactChecksumFromTheSameReleaseOnlyWhenDigestIsAbsent() {
        val core = asset().put("digest", JSONObject.NULL)
        val text = "$sha  other.zip\n$sha *$binary\n"
        fun withManifest(contents: String, url: String = "$root/sha256sums.txt", digest: String? = null): CoreRelease {
            val bytes = contents.toByteArray()
            val sums = asset("sha256sums.txt").put("size", bytes.size)
                .put("digest", digest ?: JSONObject.NULL).put("browser_download_url", url)
            return CoreReleases.parseRelease(release(core, sums)) { address, limit ->
                assertEquals("$root/sha256sums.txt", address)
                assertEquals(bytes.size.toLong(), limit)
                bytes
            }
        }
        assertEquals(sha, withManifest(text).sha256)
        rejected { withManifest("$text$sha  $binary\n") }
        rejected { withManifest("$sha  nested/$binary\n") }
        rejected { withManifest(text, url = "$root/../../outside/sha256sums.txt") }
        rejected { withManifest(text, digest = "sha256:$sha") }
    }

    @Test
    fun followsOnlyHttpsGithubReleaseCdnRedirects() {
        val origin = URI("$root/$binary")
        val good = "https://release-assets.githubusercontent.com/github-production-release-asset/1154991631/blob?sig=abc"
        assertEquals(URI(good), CoreReleases.assetRedirect(origin, good))
        listOf(
            "http://release-assets.githubusercontent.com/file",
            "https://release-assets.githubusercontent.com.evil.example/file",
            "https://release-assets.githubusercontent.com@evil.example/file",
            "https://user@release-assets.githubusercontent.com/file",
            "https://release-assets.githubusercontent.com:444/file",
            "https://github.com/other/project/releases/download/v2/core.so",
            "file:///local/file",
        ).forEach { rejected { CoreReleases.assetRedirect(origin, it) } }
    }

    @Test
    fun replacesOutputOnlyAfterExactSizeAndDigestMatch() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val directory = Files.createTempDirectory(cache.toPath(), "core-release-test-").toFile()
        val destination = File(directory, "verified.so")
        val metadata = parse(release(asset()))
        try {
            destination.writeText("prior")
            for (invalid in listOf("axc", "ab", "abcd")) {
                rejected { CoreReleases.storeVerified(metadata, destination, invalid.byteInputStream()) }
                assertEquals("prior", destination.readText())
                assertEquals(listOf("verified.so"), directory.list()?.toList())
            }
            val wrongUrl = metadata.copy(downloadUrl = "https://example.org/$binary")
            rejected { CoreReleases.download(wrongUrl, destination) }
            CoreReleases.storeVerified(metadata, destination, "abc".byteInputStream())
            assertArrayEquals("abc".toByteArray(), destination.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }
}
