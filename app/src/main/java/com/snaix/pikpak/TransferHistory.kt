package com.snaix.pikpak

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal enum class TaskTab(private val titleResource: Int) {
    Cloud(R.string.model_tab_cloud), Download(R.string.model_tab_device), Upload(R.string.model_tab_upload);

    val title: String get() = appString(titleResource)
}

internal enum class TransferStage(private val titleResource: Int) {
    Preparing(R.string.model_stage_preparing), Hashing(R.string.model_stage_hashing), Downloading(R.string.model_stage_downloading), Saving(R.string.model_stage_saving),
    Uploading(R.string.model_stage_uploading), Finishing(R.string.model_stage_finalizing);

    val label: String get() = appString(titleResource)
}

internal data class TransferProgress(
    val stage: TransferStage = TransferStage.Preparing,
    val currentFile: String? = null,
    val completed: Int = 0,
    val total: Int? = null,
    val bytes: Long = 0,
    val byteTotal: Long? = null,
)

internal enum class TransferStatus(private val titleResource: Int) {
    Queued(R.string.model_status_queued), Running(R.string.model_status_running), Pausing(R.string.model_status_pausing), Paused(R.string.model_status_paused),
    Cancelling(R.string.model_status_cancelling), Cancelled(R.string.model_status_cancelled), Completed(R.string.model_status_done), Failed(R.string.model_status_failed), Interrupted(R.string.model_status_interrupted);

    val label: String get() = appString(titleResource)
}

internal data class LocalTransfer(
    val id: String,
    val tab: TaskTab,
    val title: String,
    val startedAt: Long,
    val progress: TransferProgress,
    val status: TransferStatus = TransferStatus.Running,
    val failure: String? = null,
    val canControl: Boolean = false,
    val thumbnail: PreviewFile? = null,
    val thumbnailBitmap: Bitmap? = null,
)

internal class TransferControl(private val history: TransferHistory? = null) {
    @Volatile private var stopped = false
    private var stopWorker: (() -> Unit)? = null
    private var sampleWorker: (() -> TransferProgress?)? = null

    fun checkpoint() {
        if (stopped) throw TransferStopped()
    }

    fun <T> inSession(work: () -> T): T {
        checkpoint()
        return if (history == null) work() else history.withOwner {
            checkpoint()
            work()
        }
    }

    @Synchronized fun stop() {
        stopped = true
        stopWorker?.invoke()
    }

    @Synchronized fun attach(stop: () -> Unit, sample: () -> TransferProgress?) {
        stopWorker = stop
        sampleWorker = sample
        if (stopped) stop()
    }

    @Synchronized fun detach() {
        stopWorker = null
        sampleWorker = null
    }

    @Synchronized fun sample(): TransferProgress? = sampleWorker?.invoke()
}

internal class TransferStopped : Exception()
internal class TransferOwnerChanged : Exception(appString(R.string.model_owner_interrupted))

/** Only the display record survives restart. The SAF permission and work belong to the live job. */
internal class TransferHistory(private val prefs: SharedPreferences? = null) {
    constructor(context: Context) : this(context.getSharedPreferences("local_transfers", Context.MODE_PRIVATE))

    private var loadedOwner = prefs?.getString(OWNER, null)

    fun load(): List<LocalTransfer> {
        val store = prefs ?: return emptyList()
        val saved = synchronized(store) {
            if (ownsHistory(store)) store.getString(HISTORY, null) else null
        } ?: return emptyList()
        val items = runCatching { JSONArray(saved) }.getOrNull() ?: return emptyList()
        return items.objects().take(LIMIT).mapNotNull { item ->
            runCatching {
                val status = TransferStatus.valueOf(item.getString("status"))
                LocalTransfer(
                    id = item.getString("id"),
                    tab = TaskTab.valueOf(item.getString("tab")),
                    title = item.getString("title"),
                    startedAt = item.getLong("startedAt"),
                    progress = TransferProgress(
                        completed = item.optInt("completed"),
                        total = item.optInt("total").takeIf { it > 0 },
                    ),
                    status = when (status) {
                        TransferStatus.Queued, TransferStatus.Running, TransferStatus.Pausing,
                        TransferStatus.Paused, TransferStatus.Cancelling -> TransferStatus.Interrupted
                        else -> status
                    },
                )
            }.getOrNull()
        }
    }

    fun authenticate(email: String, signIn: () -> Unit) {
        val store = prefs
        if (store == null) {
            signIn()
            return
        }
        val owner = accountScope(email)
        synchronized(loginLock) {
            val changingOwner = store.getString(OWNER, null) != owner
            val pendingChange = store.getBoolean(PENDING_CHANGE, false)
            val stageChange = changingOwner && !pendingChange
            if (stageChange) {
                // The marker must reach disk before native login can replace the session file.
                val recorded = synchronized(store) {
                    store.edit().putBoolean(PENDING_CHANGE, true).commit()
                }
                if (!recorded) throw CloudError("session_save", appString(R.string.model_cloud_session_save))
            }

            try {
                signIn()
            } catch (error: Exception) {
                // A session-save error can occur after the new session file was renamed into place.
                val rejectedBeforeSaving = error is CloudError &&
                    error.code != "session_save" && error.code != "internal"
                if (stageChange && rejectedBeforeSaving) synchronized(store) {
                    store.edit().remove(PENDING_CHANGE).apply()
                }
                throw error
            }

            synchronized(store) {
                if (changingOwner || pendingChange) {
                    val edit = store.edit().remove(PENDING_CHANGE)
                    if (changingOwner) edit.putString(OWNER, owner).remove(HISTORY)
                    edit.apply()
                }
                loadedOwner = owner
            }
        }
    }

    fun save(transfers: List<LocalTransfer>) {
        val store = prefs ?: return
        val items = JSONArray()
        transfers.take(LIMIT).forEach { transfer ->
            items.put(JSONObject()
                .put("id", transfer.id)
                .put("tab", transfer.tab.name)
                .put("title", transfer.title)
                .put("startedAt", transfer.startedAt)
                .put("status", transfer.status.name)
                .put("completed", transfer.progress.completed)
                .put("total", transfer.progress.total))
        }
        synchronized(store) {
            if (ownsHistory(store)) store.edit().putString(HISTORY, items.toString()).apply()
        }
    }

    fun <T> withOwner(work: () -> T): T = synchronized(loginLock) {
        val store = prefs
        if (store != null && !synchronized(store) { ownsHistory(store) }) {
            throw TransferOwnerChanged()
        }
        work()
    }

    fun clear() {
        val store = prefs ?: return
        synchronized(store) {
            if (store.getString(OWNER, null) == loadedOwner) store.edit().remove(HISTORY).apply()
        }
    }

    private fun ownsHistory(store: SharedPreferences): Boolean =
        !store.getBoolean(PENDING_CHANGE, false) && store.getString(OWNER, null) == loadedOwner

    fun currentAccountScope(): String? {
        val store = prefs ?: return null
        return synchronized(store) { if (ownsHistory(store)) loadedOwner ?: "local-session" else null }
    }

    fun verifiedAccountScope(): String? {
        val store = prefs ?: return null
        return synchronized(store) { loadedOwner.takeIf { ownsHistory(store) } }
    }

    fun matchesAccount(email: String): Boolean = verifiedAccountScope() == accountScope(email)

    private fun accountScope(email: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(email.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    companion object {
        const val LIMIT = 60
        private const val HISTORY = "history"
        private const val OWNER = "owner"
        private const val PENDING_CHANGE = "pending_owner_change"
        private val loginLock = Any()
    }
}
