package com.snaix.pikpak

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keep large cloud selections outside the activity state transaction. */
internal class TransferPickerState(private val directory: File, restoredState: Bundle?) {
    data class DownloadSelection(val files: List<CloudFile>, val accountScope: String?)
    private var draft = restoredState?.getString(STATE_KEY)?.takeIf { it.matches(DRAFT_NAME) }
        ?.let { File(directory, it) }

    fun saveUpload(folder: Folder) = save(JSONObject().put("id", folder.id).put("name", folder.name))

    fun saveDownload(files: List<CloudFile>, accountScope: String? = null) = save(JSONObject().put("account", accountScope).put("files", JSONArray().apply {
        files.forEach { file ->
            put(JSONObject().put("id", file.id).put("name", file.name)
                .put("kind", if (file.folder) "folder" else "file").put("size", file.bytes))
        }
    }))

    fun takeUpload(): Folder? = take { Folder(it.getString("id"), it.getString("name")) }

    fun takeDownloadSelection(): DownloadSelection? = take {
        DownloadSelection(it.optJSONArray("files").files(), it.stringOrNull("account"))
    }

    fun saveInstanceState(state: Bundle) {
        draft?.let { state.putString(STATE_KEY, it.name) }
    }

    fun clear() {
        draft?.delete()
        draft = null
    }

    private fun save(value: JSONObject) {
        val file = File.createTempFile("transfer-picker-", ".json", directory)
        try {
            file.writeText(value.toString())
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        clear()
        draft = file
    }

    private fun <T> take(decode: (JSONObject) -> T): T? = try {
        draft?.let { runCatching { decode(JSONObject(it.readText())) }.getOrNull() }
    } finally {
        clear()
    }

    companion object {
        private const val STATE_KEY = "transfer_picker_draft"
        private val DRAFT_NAME = Regex("transfer-picker-[-0-9]+\\.json")
    }
}
