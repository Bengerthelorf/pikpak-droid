package com.snaix.pikpak

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.EmptyCoroutineContext

class PreviewWarmupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var warmup: PreviewWarmup? = null
    private var latestStart = 0
    private var stopping: String? = null
    private var destroyed = false
    private var lastPosted = 0L
    private val prefs by lazy { appearancePreferences() }
    private val preferenceChange = SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
        if ((key == APPEARANCE_PREVIEW_CACHE || key == DRIVE_FOLDER_STATS) && !store.getBoolean(key, false)) {
            requestStop(appString(R.string.model_cache_settings_changed))
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs.registerOnSharedPreferenceChangeListener(preferenceChange)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        if (intent?.action == CANCEL || intent == null) {
            if (work == null) {
                mutableState.value = CacheWarmupState(summary = appString(R.string.model_cache_stopped))
                stopSelfResult(startId)
            } else requestStop(appString(R.string.model_cache_stopped))
            return START_NOT_STICKY
        }
        if (work != null) return START_NOT_STICKY
        val initial = CacheWarmupState(true, appString(R.string.model_cache_preparing))
        mutableState.value = initial
        startForeground(CacheWarmupNotification.ID, CacheWarmupNotification.build(this, initial))
        val history = TransferHistory(this)
        val owner = intent.getStringExtra(OWNER)
        stopping = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val summary = try {
                check(owner != null && history.currentAccountScope() == owner) { appString(R.string.model_cache_account_changed) }
                val requestedGeneration = intent.getLongExtra(PREVIEW_GENERATION, 0L)
                val runner = PreviewWarmup(this@PreviewWarmupService, CloudApi(this@PreviewWarmupService),
                    owner, history::currentAccountScope,
                    listingOwner = history::verifiedAccountScope,
                    previewsStillRequested = { requestedGeneration == previewGeneration.get() },
                    onIndexSynced = { mutableIndexCommits.tryEmit(Unit) })
                warmup = runner
                runner.run { next ->
                    withContext(Dispatchers.Main.immediate) {
                        if (stopping == null) {
                            val previous = mutableState.value
                            mutableState.value = next
                            val now = SystemClock.elapsedRealtime()
                            if (previous.total != next.total || now - lastPosted >= 1000) {
                                getSystemService(NotificationManager::class.java)
                                    .notify(CacheWarmupNotification.ID, CacheWarmupNotification.build(this@PreviewWarmupService, next))
                                lastPosted = now
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                stopping ?: appString(R.string.model_cache_stopped)
            } catch (error: Exception) {
                error.message?.let { appString(R.string.model_cache_partial, it) } ?: appString(R.string.model_cache_retry)
            }
            if (!destroyed) {
                mutableState.value = CacheWarmupState(summary = summary)
                warmup = null
                work = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(latestStart)
            }
        }
        work = job
        job.start()
        return START_NOT_STICKY
    }

    private fun requestStop(message: String) {
        if (work == null || stopping != null) return
        stopping = message
        mutableState.value = CacheWarmupState(true, appString(R.string.model_cache_stopping))
        // The native index call blocks its IO thread. Signal its token on a different thread.
        warmup?.let { runner -> Dispatchers.IO.dispatch(EmptyCoroutineContext) { runner.cancel() } }
        work?.cancel()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        requestStop(appString(R.string.model_cache_system_stop))
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceChange)
        requestStop(appString(R.string.model_cache_stopped))
        scope.cancel()
        if (mutableState.value.running) mutableState.value = CacheWarmupState(summary = stopping ?: appString(R.string.model_cache_stopped))
        super.onDestroy()
    }

    companion object {
        private const val CANCEL = "com.snaix.pikpak.CANCEL_PREVIEW_WARMUP"
        private const val OWNER = "owner"
        private const val PREVIEW_GENERATION = "preview_generation"
        private val previewGeneration = AtomicLong()
        private val mutableIndexCommits = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        internal val indexCommits = mutableIndexCommits.asSharedFlow()
        private val mutableState = MutableStateFlow(CacheWarmupState())
        internal val state = mutableState.asStateFlow()

        internal fun start(context: Context) {
            if (state.value.running) return
            val intent = Intent(context, PreviewWarmupService::class.java)
                .putExtra(OWNER, TransferHistory(context).currentAccountScope())
                .putExtra(PREVIEW_GENERATION, previewGeneration.get())
            mutableState.value = CacheWarmupState(true, appString(R.string.model_cache_preparing))
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: RuntimeException) {
                mutableState.value = CacheWarmupState(summary = appString(R.string.model_cache_start_failed))
                throw error
            }
        }

        internal suspend fun clearPreviewCache(context: Context): PreviewCacheUsage = withContext(Dispatchers.IO) {
            previewGeneration.incrementAndGet()
            val usage = PreviewDiskCache(context) { null }.clear()
            resetFinishedState()
            usage
        }

        internal fun resetFinishedState() {
            val previous = mutableState.value
            if (!previous.running) mutableState.compareAndSet(previous, CacheWarmupState())
        }

        internal fun cancelIntent(context: Context): Intent =
            Intent(context, PreviewWarmupService::class.java).setAction(CANCEL)

        internal fun cancel(context: Context) {
            context.startService(cancelIntent(context))
        }

        internal fun cancelIfRunning(context: Context) {
            if (state.value.running) cancel(context)
        }
    }
}
