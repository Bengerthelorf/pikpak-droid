package com.snaix.pikpak

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.AtomicFile
import androidx.annotation.StringRes
import org.json.JSONObject
import java.io.File

internal enum class DrawerQuotaKind(val wireKey: String?, @StringRes val labelRes: Int) {
    Storage(null, R.string.ui_cloud_capacity),
    Upload("upload", R.string.ui_upload),
    Download("download", R.string.ui_download),
    Offline("offline", R.string.ui_cloud_download),
    DownloadDaily("download_daily", R.string.ui_daily_download);

    val label: String get() = appString(labelRes)
}

internal enum class DrawerQuotaDensity(@StringRes val labelRes: Int) {
    Simple(R.string.drawer_quota_simple), Detailed(R.string.drawer_quota_detailed);

    val label: String get() = appString(labelRes)
}

internal data class DrawerQuotaPreferences(
    val visible: Set<DrawerQuotaKind>,
    val density: DrawerQuotaDensity,
) {
    companion object {
        const val VISIBLE_KEY = "drawer_quotas"
        const val DENSITY_KEY = "drawer_quota_density"
        val defaultVisible = setOf(DrawerQuotaKind.Storage.name)

        fun read(prefs: SharedPreferences): DrawerQuotaPreferences {
            val visible = prefs.getStringSet(VISIBLE_KEY, defaultVisible).orEmpty()
            val density = prefs.getString(DENSITY_KEY, null)
            return DrawerQuotaPreferences(
                DrawerQuotaKind.entries.filterTo(linkedSetOf()) { it.name in visible },
                DrawerQuotaDensity.entries.firstOrNull { it.name == density } ?: DrawerQuotaDensity.Simple,
            )
        }
    }
}

internal data class DrawerQuota(val used: Long?, val limit: Long?, val unlimited: Boolean = false) {
    val remaining: Long? get() = if (!unlimited && limit != null && used != null) (limit - used).coerceAtLeast(0) else null

    fun detail(): String = when {
        unlimited -> appString(R.string.drawer_quota_unlimited)
        used != null && limit != null -> "${readableBytes(used)} / ${readableBytes(limit)}"
        used != null -> appString(R.string.ui_usage, readableBytes(used))
        limit != null -> appString(R.string.ui_quota_total, readableBytes(limit))
        else -> ""
    }
}

internal data class DrawerMembership(val tier: String?, val status: String?, val expiry: String?)

internal data class DrawerAccountData(
    val displayName: String?,
    val avatar: Uri?,
    val storage: DrawerQuota?,
    val bandwidth: Map<DrawerQuotaKind, DrawerQuota>,
    val trashUsage: Long?,
    val bandwidthExpiry: String?,
    val membership: DrawerMembership?,
) {
    fun quota(kind: DrawerQuotaKind): DrawerQuota? = if (kind == DrawerQuotaKind.Storage) storage else bandwidth[kind]

    companion object {
        fun from(response: JSONObject): DrawerAccountData {
            val user = response.optJSONObject("user")
            val quota = response.optJSONObject("quota")
            val bandwidth = response.optJSONObject("bandwidth")
            return DrawerAccountData(
                displayName = user?.stringOrNull("name") ?: user?.stringOrNull("email"),
                avatar = httpsUri(user?.stringOrNull("picture")) ?: httpsUri(user?.stringOrNull("avatar")),
                storage = quota?.readQuota("usage", "limit", quota.optBoolean("is_unlimited")),
                bandwidth = buildMap {
                    for (kind in DrawerQuotaKind.entries) {
                        val key = kind.wireKey ?: continue
                        val band = bandwidth?.optJSONObject(key) ?: continue
                        // The API reports used bytes in assets. Only the daily band defines zero total as unlimited.
                        val unlimited = kind == DrawerQuotaKind.DownloadDaily && band.nonnegativeCount("total_assets") == 0L
                        band.readQuota("assets", "total_assets", unlimited)?.let { put(kind, it) }
                    }
                },
                trashUsage = quota?.nonnegativeCount("usage_in_trash"),
                bandwidthExpiry = bandwidth?.stringOrNull("expire_time"),
                membership = response.optJSONObject("vip")?.let {
                    DrawerMembership(it.stringOrNull("type"), it.stringOrNull("status"), it.stringOrNull("expire"))
                },
            )
        }

        private fun JSONObject.readQuota(usageKey: String, limitKey: String, unlimited: Boolean): DrawerQuota? {
            val used = nonnegativeCount(usageKey)
            val limit = nonnegativeCount(limitKey).takeUnless { unlimited }
            return DrawerQuota(used, limit, unlimited).takeIf { used != null || limit != null || unlimited }
        }
    }
}

/** Stores only fields displayed by the app, keyed to the verified account. */
internal class DrawerAccountCache(file: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "drawer-account.json"))
    private val stored = AtomicFile(file)

    fun load(owner: String): DrawerAccountData? = synchronized(lock) {
        read(owner)?.let(DrawerAccountData::from)
    }

    fun save(owner: String, profile: DrawerAccountData) {
        val storage = profile.storage
        val bands = JSONObject().put("expire_time", profile.bandwidthExpiry)
        profile.bandwidth.forEach { (kind, quota) ->
            val key = kind.wireKey ?: return@forEach
            bands.put(key, JSONObject().put("assets", quota.used).put("total_assets", if (quota.unlimited) 0L else quota.limit))
        }
        val encoded = JSONObject()
            .put("owner", owner)
            .put("user", JSONObject().put("name", profile.displayName).put("picture", profile.avatar?.toString()))
            .put("quota", JSONObject().put("usage", storage?.used).put("limit", storage?.limit)
                .put("is_unlimited", storage?.unlimited).put("usage_in_trash", profile.trashUsage))
            .put("bandwidth", bands)
            .put("vip", profile.membership?.let { JSONObject().put("type", it.tier).put("status", it.status).put("expire", it.expiry) })
        synchronized(lock) {
            val stream = stored.startWrite()
            try {
                stream.write(encoded.toString().toByteArray(Charsets.UTF_8))
                stored.finishWrite(stream)
            } catch (error: Exception) {
                stored.failWrite(stream)
                throw error
            }
        }
    }

    fun clear(owner: String) = synchronized(lock) {
        if (read(owner) != null) stored.delete()
    }

    private fun read(owner: String): JSONObject? = runCatching { JSONObject(stored.readFully().toString(Charsets.UTF_8)) }
        .getOrNull()?.takeIf { it.stringOrNull("owner") == owner }

    private companion object {
        val lock = Any()
    }
}
