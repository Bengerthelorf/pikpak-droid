package com.snaix.pikpak

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal data class CoreRelease(val version: String, val sha256: String, val size: Long, val downloadUrl: String)

internal object CoreReleases {
    private const val REPO = "Bengerthelorf/pikpaktui"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val BINARY = "pikpaktui-android-arm64-v8a-api1.so"
    private const val CHECKSUMS = "sha256sums.txt"
    private const val MAX_BINARY = 64L * 1024 * 1024
    private const val MAX_MANIFEST = 128L * 1024
    private const val MAX_METADATA = 1024L * 1024
    private val stableVersion = Regex("""v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""")
    private val hexDigest = Regex("[0-9a-fA-F]{64}")
    private val manifestEntry = Regex("""([0-9a-fA-F]{64}) [ *](.+)""")

    fun latest(): CoreRelease {
        val metadata = get(URI(LATEST), asset = false) { input ->
            JSONObject(readBytes(input, MAX_METADATA).toString(Charsets.UTF_8))
        }
        return parseRelease(metadata) { url, size ->
            get(URI(url), asset = true) { input -> readBytes(input, size) }
        }
    }

    private fun versionParts(version: String): List<String> =
        requireNotNull(stableVersion.matchEntire(version)) { appString(R.string.model_core_bad_version) }.groupValues.drop(1)

    fun newer(a: String, b: String): Boolean {
        val left = versionParts(a)
        val right = versionParts(b)
        for (i in left.indices) {
            val length = left[i].length.compareTo(right[i].length)
            val comparison = if (length != 0) length else left[i].compareTo(right[i])
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    private fun assetUrl(tag: String, name: String) = "https://github.com/$REPO/releases/download/$tag/$name"

    private fun apiDigest(asset: JSONObject): String? {
        if (asset.isNull("digest")) return null
        val encoded = asset.getString("digest")
        val hex = encoded.removePrefix("sha256:")
        require(hex != encoded && hexDigest.matches(hex)) { appString(R.string.model_core_bad_digest) }
        return hex.lowercase()
    }

    internal fun parseRelease(metadata: JSONObject, readChecksums: (String, Long) -> ByteArray): CoreRelease {
        require(metadata.get("draft") == false && metadata.get("prerelease") == false) { appString(R.string.model_core_not_stable) }
        val tag = metadata.getString("tag_name")
        val version = versionParts(tag).joinToString(".")
        val assets = metadata.getJSONArray("assets")

        fun asset(name: String, maxSize: Long, missing: String): JSONObject {
            val matching = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name") == name }
            require(matching.isNotEmpty()) { missing }
            val item = requireNotNull(matching.singleOrNull()) { appString(R.string.model_core_duplicate_asset, name) }
            require(item.optString("state") == "uploaded" && item.optString("browser_download_url") == assetUrl(tag, name)) {
                appString(R.string.model_core_invalid_asset)
            }
            val size = item.opt("size")
            require((size is Int || size is Long) && (size as Number).toLong() in 1..maxSize) {
                appString(R.string.model_core_invalid_asset_size)
            }
            return item
        }

        val binary = asset(BINARY, MAX_BINARY, appString(R.string.model_core_missing_android, tag))
        val sha256 = apiDigest(binary) ?: run {
            val manifest = asset(CHECKSUMS, MAX_MANIFEST, appString(R.string.model_core_missing_checksum))
            val size = manifest.getLong("size")
            val bytes = readChecksums(manifest.getString("browser_download_url"), size)
            require(bytes.size.toLong() == size) { appString(R.string.model_core_manifest_size) }
            apiDigest(manifest)?.let { expected ->
                require(sha256Hex(MessageDigest.getInstance("SHA-256").digest(bytes)) == expected) { appString(R.string.model_core_manifest_digest) }
            }
            val entries = bytes.toString(Charsets.UTF_8).lineSequence()
                .mapNotNull { manifestEntry.matchEntire(it) }
                .filter { it.groupValues[2] == BINARY }
            requireNotNull(entries.singleOrNull()) { appString(R.string.model_core_manifest_ambiguous) }
                .groupValues[1].lowercase()
        }
        return CoreRelease(version, sha256, binary.getLong("size"), binary.getString("browser_download_url"))
    }

    fun download(release: CoreRelease, output: File) {
        versionParts(release.version)
        val numeric = release.version.removePrefix("v")
        require(release.downloadUrl in setOf(assetUrl("v$numeric", BINARY), assetUrl(numeric, BINARY))) {
            appString(R.string.model_core_address_mismatch)
        }
        require(release.size in 1..MAX_BINARY && hexDigest.matches(release.sha256)) { appString(R.string.model_core_invalid_checksum) }
        get(URI(release.downloadUrl), asset = true) { input -> storeVerified(release, output, input) }
    }

    internal fun storeVerified(release: CoreRelease, output: File, input: InputStream) {
        require(release.size in 1..MAX_BINARY) { appString(R.string.model_core_invalid_size) }
        val scratch = File.createTempFile("core-download-", ".part", output.absoluteFile.parentFile)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val length = scratch.outputStream().use { copy(input, it, release.size, digest) }
            require(length == release.size && sha256Hex(digest.digest()).equals(release.sha256, ignoreCase = true)) {
                appString(R.string.model_core_download_mismatch)
            }
            Files.move(scratch.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            scratch.delete()
        }
    }

    private fun sha256Hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun copy(input: InputStream, output: OutputStream, limit: Long, digest: MessageDigest? = null): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            require(total <= limit) { appString(R.string.model_core_too_much_data) }
            output.write(buffer, 0, count)
            digest?.update(buffer, 0, count)
        }
    }

    private fun readBytes(input: InputStream, limit: Long): ByteArray =
        ByteArrayOutputStream().apply { copy(input, this, limit) }.toByteArray()

    internal fun assetRedirect(from: URI, location: String): URI {
        val target = from.resolve(location)
        // GitHub issues signed CDN URLs for release assets. Keep their query intact.
        require(target.scheme == "https" && target.host == "release-assets.githubusercontent.com" &&
            target.rawUserInfo == null && target.port == -1 && target.rawFragment == null
        ) { appString(R.string.model_core_untrusted_redirect) }
        return target
    }

    private fun <T> get(start: URI, asset: Boolean, read: (InputStream) -> T): T {
        var url = start
        repeat(5) {
            val http = url.toURL().openConnection() as HttpsURLConnection
            try {
                http.instanceFollowRedirects = false
                http.connectTimeout = 10_000
                http.readTimeout = 30_000
                http.setRequestProperty("User-Agent", "PikPak-Droid")
                http.setRequestProperty("Accept-Encoding", "identity")
                http.setRequestProperty("Accept", if (asset) "application/octet-stream" else "application/vnd.github+json")
                when (val status = http.responseCode) {
                    200 -> return http.inputStream.use(read)
                    301, 302, 303, 307, 308 -> {
                        require(asset) { appString(R.string.model_core_api_moved) }
                        val location = requireNotNull(http.getHeaderField("Location")) { appString(R.string.model_core_missing_location) }
                        url = assetRedirect(url, location)
                    }
                    403, 429 -> throw IOException(appString(R.string.model_github_rate_limit))
                    else -> throw IOException(appString(R.string.model_github_http_error, status))
                }
            } finally {
                http.disconnect()
            }
        }
        throw IOException(appString(R.string.model_core_redirect_limit))
    }
}
