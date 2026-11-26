package com.snaix.pikpak

import org.json.JSONObject

internal fun JSONObject.nonnegativeCount(key: String): Long? =
    stringOrNull(key)?.toLongOrNull()?.takeIf { it >= 0 }

internal data class ListedShare(
    val id: String?,
    val title: String,
    val url: String?,
    val accessCode: String?,
    val protected: Boolean,
    val created: String?,
    val expiry: String?,
    val views: Long?,
    val restores: Long?,
    val files: Long?,
) {
    private val visibility: String get() = if (protected) appString(R.string.model_share_requires_code) else appString(R.string.model_share_public)

    val rowSummary: String get() = listOfNotNull(visibility, expiry, created?.take(10)).joinToString(" · ")

    fun details(): String = listOfNotNull(
        visibility,
        expiry?.let { appString(R.string.model_share_expiry, it) },
        created?.let { appString(R.string.model_share_created, it.displayTime()) },
        views?.let { appString(R.string.model_share_views, it) },
        restores?.let { appString(R.string.model_share_restores, it) },
        files?.let { appString(R.string.model_share_items, it) },
        id?.let { appString(R.string.model_share_id, it) },
        url,
        accessCode?.let { appString(R.string.model_share_code, it) },
    ).joinToString("\n")

    companion object {
        fun from(value: JSONObject): ListedShare {
            val code = value.stringOrNull("pass_code")
            val expiry = when (val days = value.stringOrNull("expiration_days")) {
                null, "-1", "0" -> appString(R.string.model_share_forever)
                else -> days.toLongOrNull()?.takeIf { it > 0 }?.let { appString(R.string.model_share_days, it) }
            }
            return ListedShare(
                id = value.stringOrNull("share_id"),
                title = value.stringOrNull("title") ?: appString(R.string.model_share_link),
                url = value.stringOrNull("share_url"),
                accessCode = code,
                protected = code != null || value.optString("share_to").contains("encrypted", ignoreCase = true),
                created = value.stringOrNull("create_time"),
                expiry = expiry,
                views = value.nonnegativeCount("view_count"),
                restores = value.nonnegativeCount("restore_count"),
                files = value.nonnegativeCount("file_num"),
            )
        }
    }
}
