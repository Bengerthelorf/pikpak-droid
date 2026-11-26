package com.snaix.pikpak

import android.app.PictureInPictureUiState
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.AttributeSet
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID

internal data class PlaybackSource(val label: String, val uri: Uri, val sourceLabel: String = label) {
    companion object {
        fun from(data: JSONObject): List<PlaybackSource> = data.optJSONArray("play").objects().mapNotNull { source ->
            val uri = httpsUri(source.stringOrNull("url"))
            if (!source.optBoolean("available") || uri == null) null
            else {
                val sourceLabel = source.optString("label")
                val label = sourceLabel.replace("Original", appString(R.string.ui_original_quality))
                    .replace("Unknown", appString(R.string.ui_other_quality))
                PlaybackSource(label, uri, sourceLabel)
            }
        }.distinctBy(PlaybackSource::uri)
    }
}

internal fun mediaMime(audio: Boolean, data: JSONObject) = data.stringOrNull("mime")
    ?.takeIf { it.startsWith(if (audio) "audio/" else "video/") }
    ?: if (audio) "audio/*" else "video/*"

internal fun subtitleMime(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "srt" -> MimeTypes.APPLICATION_SUBRIP
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "vtt" -> MimeTypes.TEXT_VTT
    "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
    else -> null
}

private data class CloudSubtitle(val id: String, val name: String)

@OptIn(UnstableApi::class)
class PlaybackActivity : AppCompatActivity() {
    private val state = ViewState()
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var view: PlayerView? = null
    private var gestures: PlaybackGestures? = null
    private var pullMotion: FullVideoPull? = null
    internal val frameHandoff = VideoFrameHandoff(this)
    internal var miniCorner = MiniCorner.BOTTOM_LEFT
    internal var miniOrigin: MiniOrigin? = null
    internal val fullVideoSurface: PlayerView? get() = view
    private var fullscreenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    private var launchingAnotherScreen = false
    private var exitingPip = false
    private var recoverMiniHost: ComponentName? = null
    private val miniHosts = ArrayList<ComponentName>()
    private val miniHostInstances = mutableMapOf<ComponentName, WeakReference<MiniPlayerHostActivity>>()
    private var openingMiniHost = false
    internal val isCloudPlayback get() = mediaId.isNotEmpty()
    internal val miniPlayer: ExoPlayer get() = player
    internal val miniTitle: String get() = mediaName
    private lateinit var playerRoot: FrameLayout
    private lateinit var playerToolbar: Toolbar
    private lateinit var playerErrorPanel: LinearLayout
    private lateinit var playerErrorText: TextView
    private lateinit var retryButton: Button
    private var controllerVisible = true
    private var transportPopupOpen = false
    private lateinit var qualityAction: MenuItem
    private lateinit var cloudSubtitleAction: MenuItem
    private lateinit var removeSubtitleAction: MenuItem
    private lateinit var pipAction: MenuItem
    private var sources by state.property<List<PlaybackSource>>(emptyList())
    private var source by state.property<PlaybackSource?>(null)
    private var subtitle by state.property<MediaItem.SubtitleConfiguration?>(null)
    private var fullScreenMode by state.property(false)
    private var inPip by state.property(false)
    private var enteringPip = false
    private val systemVideoOnly get() = enteringPip || inPip || isInPictureInPictureMode
    private var playerError by state.property<String?>(null)
    private var refreshing by state.property(false)
    private var fetchingSubtitle by state.property(false)
    private var cloudSubtitles: List<CloudSubtitle> = emptyList()
    private var subtitleFolderPath: List<Folder> = emptyList()
    private var subtitleJob: Job? = null
    private var cloudSubtitle: CloudSubtitle? = null
    private var preferSidecar = false
    private val subtitleApi by lazy { CloudApi(application) }
    private var resumeOnStart = false
    private val wantsPlayback: Boolean get() = player.playWhenReady || resumeOnStart
    private var audio = false
    private var mediaName = ""
    private var mediaId = ""
    private var mime = "video/*"
    private val attemptedSources = mutableSetOf<Uri>()
    private var refreshedLinks = false
    private var sourceRevision = 0
    private var preparedPosition = 0L
    private var sourceReady = false
    private var refreshJob: Job? = null

    private val positions by lazy { getSharedPreferences("playback_positions", MODE_PRIVATE) }
    private val pipSupported get() = !audio && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private val chooseSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && ::player.isInitialized) importSubtitle(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = appearancePreferences().themeMode().nightMode
        super.onCreate(savedInstanceState)
        inPip = isInPictureInPictureMode
        InAppPlayback.takeRecovery()?.let(::setIntent)
        mediaId = intent.getStringExtra("id").orEmpty()
        mediaName = intent.getStringExtra("name").orEmpty()
        audio = intent.getBooleanExtra("audio", false)
        cloudSubtitles = runCatching { JSONArray(intent.getStringExtra("subtitles") ?: "[]") }
            .getOrNull().objects().map { CloudSubtitle(it.getString("id"), it.getString("name")) }
        subtitleFolderPath = runCatching { JSONArray(intent.getStringExtra("subtitleFolderPath") ?: "[]") }
            .getOrNull().objects().map { Folder(it.getString("id"), it.getString("name")) }
        val data = runCatching { JSONObject(intent.getStringExtra("sources").orEmpty()) }.getOrNull()
        val local = intent.data?.takeIf { it.scheme in setOf("content", "android.resource") }
        val playable = local?.let { listOf(PlaybackSource(getString(R.string.ui_local_file), it, "local-file")) }
            ?: data?.let(PlaybackSource::from).orEmpty()
        if (playable.isEmpty()) {
            finish()
            return
        }
        InAppPlayback.register(this)
        val restored = intent.getBundleExtra(InAppPlayback.EXTRA_RECOVER) ?: savedInstanceState
        miniCorner = MiniCorner.entries.firstOrNull { it.name == restored?.getString("miniCorner") }
            ?: MiniCorner.BOTTOM_LEFT
        @Suppress("DEPRECATION")
        val returningTo = intent.getParcelableExtra<ComponentName>(InAppPlayback.EXTRA_HOST)
        recoverMiniHost = returningTo?.takeIf { it.packageName == packageName }
        @Suppress("DEPRECATION")
        val priorHosts = restored?.getParcelableArrayList<ComponentName>("miniHosts")
            ?: listOfNotNull(restored?.getParcelable<ComponentName>("miniHost"))
        miniHosts.addAll(priorHosts.filter { it.packageName == packageName })
        intent.removeExtra(InAppPlayback.EXTRA_RECOVER)
        intent.removeExtra(InAppPlayback.EXTRA_HOST)
        sources = playable
        mime = mediaMime(audio, data ?: JSONObject())
        val barStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(barStyle, barStyle)

        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        player.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    updateDurationVisibility()
                }
                if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_VIDEO_SIZE_CHANGED, Player.EVENT_PLAYER_ERROR)) updatePipParams()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateMenu()
                if (playbackState == Player.STATE_READY && !sourceReady) {
                    sourceReady = true
                    val tracks = player.currentTracks
                    if (!audio && tracks.containsType(C.TRACK_TYPE_VIDEO) &&
                        !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
                    ) {
                        recoverPlayback(getString(R.string.ui_decode_quality_failed))
                    }
                }
                if (playbackState == Player.STATE_ENDED) savePosition()
            }

            override fun onPlayerError(error: PlaybackException) {
                val httpError = error.cause as? HttpDataSource.HttpDataSourceException
                val sidecar = subtitle
                if (sidecar != null && httpError?.dataSpec?.uri == sidecar.uri) {
                    recoverPlayback(getString(R.string.ui_external_subtitle_failed), withoutSubtitle = true)
                    return
                }
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    PlaybackException.ERROR_CODE_DECODING_FAILED -> recoverPlayback(getString(R.string.ui_decode_quality_failed))
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                        recoverPlayback(getString(R.string.ui_media_format_failed))
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                        val status = (httpError as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                        recoverPlayback(getString(R.string.ui_play_link_unavailable), refreshLinks = status in setOf(401, 403, 410))
                    }
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> playerError = getString(R.string.ui_play_connection_failed)
                    else -> playerError = getString(R.string.ui_play_failed)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !audio) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (preferSidecar) selectSidecarTrack(tracks)
                val captions = view?.findViewById<ImageView>(R.id.playback_subtitles) ?: return
                val attribute = if (tracks.isTypeSelected(C.TRACK_TYPE_TEXT)) androidx.appcompat.R.attr.colorAccent
                    else android.R.attr.textColorPrimary
                captions.setColorFilter(captions.context.themeColor(attribute, Color.WHITE))
            }
        })
        session = MediaSession.Builder(this, player).setId("pikpak-${UUID.randomUUID()}").build()

        val selected = restored?.getString("quality")?.let { label ->
            playable.firstOrNull { it.sourceLabel == label || it.label == label }
        }
            ?: playable.first()
        val position = restored?.getLong("position") ?: positions.getLong(mediaId, 0L)
        val savedCaptionName = restored?.getString("subtitleName")
        val savedCaptionId = restored?.getString("subtitleFileId")
        if (restored != null) {
            restored.getBundle("trackParameters")?.let {
                player.trackSelectionParameters = TrackSelectionParameters.fromBundle(it)
            }
            preferSidecar = restored.getBoolean("preferSidecar")
            player.setPlaybackSpeed(restored.getFloat("speed", 1f))
            val localCaption = restored.getString("subtitleUri")?.let(Uri::parse)
            if (localCaption?.scheme == "content" && savedCaptionName != null) {
                subtitleMime(savedCaptionName)?.let { subtitle = sidecarConfig(localCaption, savedCaptionName, it) }
            }
        }
        showPlayer()
        if (restored?.getBoolean("fullscreen") == true) {
            toggleFullscreen(true, restored.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE))
        }
        load(selected, position, restored?.getBoolean("playing", true) ?: true)
        if (savedCaptionId != null && savedCaptionName != null) {
            attachCloudSubtitle(CloudSubtitle(savedCaptionId, savedCaptionName), preferSidecar)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullScreenMode) toggleFullscreen(false) else finish()
            }
        })
    }

    internal fun playbackSnapshot() = Bundle().apply {
        if (::player.isInitialized) {
            putLong("position", playbackPosition())
            putBoolean("playing", wantsPlayback)
            putString("quality", source?.sourceLabel)
            putFloat("speed", player.playbackParameters.speed)
            putBoolean("fullscreen", fullScreenMode)
            putInt("orientation", fullscreenOrientation)
            putString("miniCorner", miniCorner.name)
            putParcelableArrayList("miniHosts", ArrayList(miniHosts))
            putBundle("trackParameters", player.trackSelectionParameters.toBundle())
            putBoolean("preferSidecar", preferSidecar)
            putString("subtitleName", cloudSubtitle?.name ?: subtitle?.label)
            putString("subtitleFileId", cloudSubtitle?.id)
            if (cloudSubtitle == null) putString("subtitleUri", subtitle?.uri?.toString())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putAll(playbackSnapshot())
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        returnFromMini()
        if (InAppPlayback.mini.value !== this) resumeVisiblePlayback()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep the original launch intent and its URI grant for future instance restoration.
        if (!intent.hasExtra(InAppPlayback.EXTRA_RECOVER)) returnFromMini()
    }

    private fun returnFromMini() {
        if (!InAppPlayback.takeExpansion(this)) return
        pullMotion?.reset()
        toggleFullscreen(fullScreenMode, fullscreenOrientation)
    }

    override fun onResume() {
        super.onResume()
        exitingPip = false
        enteringPip = false
        inPip = isInPictureInPictureMode
        updateChrome()
        launchingAnotherScreen = false
        updatePipParams()
        val host = recoverMiniHost
        if (host != null) {
            recoverMiniHost = null
            minimize(host)
        } else if (InAppPlayback.mini.value === this && !isFinishing) {
            // Reordering a player above Settings, then minimizing there, can leave the parked
            // activity directly underneath Settings. Back should continue to the preceding host.
            val previous = miniHosts.asReversed().drop(1).firstOrNull { name ->
                miniHostInstances[name]?.get()?.isFinishing != true
            }
            openMiniHost(previous ?: ComponentName(this, MainActivity::class.java))
        }
    }

    internal fun resumeVisiblePlayback() {
        if (::player.isInitialized && resumeOnStart) {
            resumeOnStart = false
            player.play()
        }
    }

    override fun onPause() {
        if (InAppPlayback.mini.value !== this) pullMotion?.reset(true)
        // A quick Back from the mini host can resume this activity before it ever stops.
        openingMiniHost = false
        super.onPause()
    }

    override fun onStop() {
        if (::player.isInitialized) {
            savePosition()
            if ((inPip || exitingPip) && !isChangingConfigurations &&
                getSystemService(PowerManager::class.java).isInteractive) {
                // A visible native PiP remains STARTED. Dismissing it must retire the owning
                // activity, otherwise returning to the workspace can resume the dismissed film.
                closePlayback()
            } else if (InAppPlayback.mini.value !== this) pauseHiddenPlayback()
        }
        super.onStop()
    }

    internal fun pauseHiddenPlayback() {
        if (!::player.isInitialized) return
        resumeOnStart = wantsPlayback
        player.pause()
    }

    internal fun closePlayback() {
        if (isFinishing || isDestroyed) return
        InAppPlayback.unpark(this)
        if (::player.isInitialized) {
            savePosition()
            resumeOnStart = false
            player.pause()
        }
        finish()
    }

    internal fun rememberMiniHost(host: MiniPlayerHostActivity) {
        val name = host.componentName
        val index = miniHosts.indexOf(name)
        if (index < 0) miniHosts.add(name)
        else miniHosts.subList(index + 1, miniHosts.size).clear()
        miniHostInstances[name] = WeakReference(host)
        openingMiniHost = false
    }

    private fun minimizeFromGesture() {
        view?.let(frameHandoff::capture)
        miniOrigin = pullMotion?.origin()
        minimize()
    }

    private fun minimize(host: ComponentName = miniHosts.lastOrNull() ?: ComponentName(this, MainActivity::class.java)) {
        if (audio || inPip || isFinishing || InAppPlayback.mini.value === this) return
        launchingAnotherScreen = true
        updatePipParams()
        InAppPlayback.park(this)
        updateChrome()
        openMiniHost(host)
    }

    private fun openMiniHost(host: ComponentName) {
        if (openingMiniHost) return
        openingMiniHost = true
        startMiniTransition(Intent().setComponent(host))
    }

    override fun onDestroy() {
        InAppPlayback.unpark(this)
        frameHandoff.clear()
        pullMotion?.reset()
        gestures?.dispose()
        view?.player = null
        session?.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // One UI overflow menus and selection dialogs take window focus from their anchor.
        updateControllerTimeout(hasFocus)
    }

    private fun updateControllerTimeout(hasFocus: Boolean = hasWindowFocus()) {
        val surface = view ?: return
        val timeout = if (audio || transportPopupOpen || !hasFocus) 0 else VIDEO_CONTROLS_TIMEOUT_MS
        if (surface.controllerShowTimeoutMs != timeout) surface.controllerShowTimeoutMs = timeout
    }

    @RequiresApi(31)
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= 35 && pipState.isTransitioningToPip) {
            enteringPip = true
            updateChrome()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        enteringPip = false
        exitingPip = !isInPictureInPictureMode && inPip && !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        inPip = isInPictureInPictureMode
        if (!inPip && !isFinishing && ::playerRoot.isInitialized) {
            toggleFullscreen(fullScreenMode, fullscreenOrientation)
        }
        updateChrome()
        if (!systemVideoOnly && ::player.isInitialized && !player.playWhenReady && playerError == null) {
            view?.showController()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && canAutoPip()) enterPip()
    }

    override fun finish() {
        launchingAnotherScreen = true
        updatePipParams()
        super.finish()
    }

    private fun load(next: PlaybackSource, position: Long = playbackPosition(), autoPlay: Boolean = wantsPlayback) {
        val oldRefresh = refreshJob
        refreshJob = null
        refreshing = false
        oldRefresh?.cancel()
        attemptedSources.clear()
        refreshedLinks = false
        prepare(next, position, autoPlay)
    }

    private fun prepare(next: PlaybackSource, position: Long, autoPlay: Boolean) {
        sourceRevision++
        sourceReady = false
        preparedPosition = position.coerceAtLeast(0L)
        attemptedSources.add(next.uri)
        source = next
        playerError = null
        val item = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(next.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(mediaName).build())
            .setSubtitleConfigurations(listOfNotNull(subtitle))
            .build()
        player.setMediaItem(item, preparedPosition)
        val canPlay = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || isInPictureInPictureMode ||
            InAppPlayback.hostIsVisible(this)
        resumeOnStart = autoPlay && !canPlay
        player.playWhenReady = autoPlay && canPlay
        player.prepare()
    }

    private fun recoverPlayback(message: String, refreshLinks: Boolean = false, withoutSubtitle: Boolean = false) {
        val revision = sourceRevision
        lifecycleScope.launch {
            // Let Media3 finish dispatching its current source callbacks before replacing it.
            yield()
            if (revision != sourceRevision || refreshing || isFinishing) return@launch
            when {
                withoutSubtitle -> {
                    val current = source ?: return@launch
                    subtitle = null
                    cloudSubtitle = null
                    preferSidecar = false
                    Toast.makeText(this@PlaybackActivity, message, Toast.LENGTH_SHORT).show()
                    prepare(current, playbackPosition(), wantsPlayback)
                }
                refreshLinks && mediaId.isNotBlank() && !refreshedLinks -> refreshPlayback(message)
                else -> tryAnotherSource(message)
            }
        }
    }

    private fun tryAnotherSource(message: String) {
        val next = sources.firstOrNull { it.uri !in attemptedSources }
        if (next == null) {
            playerError = message
            return
        }
        prepare(next, playbackPosition(), wantsPlayback)
    }

    private fun playbackPosition() = if (sourceReady) player.currentPosition else preparedPosition

    private fun savePosition() {
        if (mediaId.isBlank()) return
        val duration = player.duration
        val position = player.currentPosition
        if (duration <= 0 || duration == C.TIME_UNSET) return
        val saved = if (position < 3_000 || duration - position < 5_000) 0L else position
        positions.edit().putLong(mediaId, saved).apply()
    }

    private fun importSubtitle(uri: Uri) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
        val mime = subtitleMime(name)
        if (mime == null) {
            Toast.makeText(this, getString(R.string.ui_subtitle_file_types), Toast.LENGTH_SHORT).show()
            return
        }
        subtitleJob?.cancel()
        setSubtitle(uri, name, mime, null, activate = true)
    }

    private fun attachCloudSubtitle(caption: CloudSubtitle, activate: Boolean = true) {
        val mime = subtitleMime(caption.name) ?: return
        subtitleJob?.cancel()
        fetchingSubtitle = true
        subtitleJob = lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val data = subtitleRequest("links", "id" to caption.id) as JSONObject
                    httpsUri(data.stringOrNull("download"))
                }
                if (uri == null) Toast.makeText(this@PlaybackActivity, getString(R.string.ui_subtitle_unavailable), Toast.LENGTH_SHORT).show()
                else setSubtitle(uri, caption.name, mime, caption, activate)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(this@PlaybackActivity, error.message ?: getString(R.string.ui_load_subtitle_failed), Toast.LENGTH_SHORT).show()
            } finally {
                if (subtitleJob === coroutineContext[Job]) fetchingSubtitle = false
            }
        }
    }

    private fun sidecarConfig(uri: Uri, name: String, mime: String) =
        MediaItem.SubtitleConfiguration.Builder(uri)
            .setId(SIDECAR_ID).setMimeType(mime).setLabel(name).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()

    private fun setSubtitle(uri: Uri, name: String, mime: String, cloud: CloudSubtitle?, activate: Boolean) {
        cloudSubtitle = cloud
        subtitle = sidecarConfig(uri, name, mime)
        preferSidecar = activate
        if (activate) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        }
        source?.let { load(it) }
    }

    private fun selectSidecarTrack(tracks: Tracks) {
        // Media3 prefixes format IDs with the source index when merging sidecar and embedded tracks.
        for (group in tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }) {
            val index = (0 until group.length).firstOrNull {
                group.getTrackFormat(it).id?.substringAfterLast(':') == SIDECAR_ID && group.isTrackSupported(it)
            } ?: continue
            val override = TrackSelectionOverride(group.mediaTrackGroup, index)
            if (player.trackSelectionParameters.overrides[group.mediaTrackGroup] != override) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(override).build()
            }
            return
        }
    }

    private fun refreshPlayback(recoveryMessage: String? = null) {
        if (refreshing) return
        val previous = source ?: return
        val position = playbackPosition()
        if (mediaId.isBlank()) {
            load(previous, position, true)
            return
        }
        val revision = sourceRevision
        val autoPlay = recoveryMessage == null || wantsPlayback
        refreshedLinks = true
        refreshing = true
        refreshJob = lifecycleScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    val response = CloudApi(application).call("links", "id" to mediaId) as JSONObject
                    PlaybackSource.from(response)
                }
                if (revision != sourceRevision) return@launch
                if (updated.isNotEmpty()) {
                    sources = updated
                    attemptedSources.clear()
                    val next = updated.firstOrNull { it.sourceLabel == previous.sourceLabel } ?: updated.first()
                    prepare(next, position, autoPlay)
                } else if (recoveryMessage != null) {
                    tryAnotherSource(recoveryMessage)
                } else {
                    playerError = getString(R.string.ui_play_link_missing)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (revision != sourceRevision) return@launch
                if (recoveryMessage != null) tryAnotherSource(recoveryMessage)
                else playerError = error.message ?: getString(R.string.ui_refresh_play_link_failed)
            } finally {
                if (refreshJob == coroutineContext[Job]) {
                    refreshing = false
                    refreshJob = null
                }
            }
        }
    }

    private fun canAutoPip() = pipSupported && ::player.isInitialized && player.playWhenReady &&
        (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) &&
        !launchingAnotherScreen && !isFinishing && playerError == null && InAppPlayback.mini.value !== this

    private fun updatePipParams() {
        val surface = view ?: return
        if (!pipSupported || isDestroyed) return
        val sourceView = surface.videoSurfaceView ?: surface
        try {
            setPictureInPictureParams(playbackPipParams(player, sourceView, canAutoPip()))
        } catch (_: IllegalStateException) {
            // Multi-window or device policy can temporarily disallow PiP.
        }
    }

    private fun enterPip() {
        val surface = view ?: return
        if (!pipSupported || playerError != null ||
            player.playbackState !in listOf(Player.STATE_READY, Player.STATE_BUFFERING)) return
        view?.hideController()
        try {
            val params = playbackPipParams(player, surface.videoSurfaceView ?: surface, canAutoPip())
            if (!enterPictureInPictureMode(params)) view?.showController()
        } catch (_: IllegalStateException) {
            view?.showController()
            Toast.makeText(this, getString(R.string.ui_picture_in_picture_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFullscreen(fullscreen: Boolean, orientation: Int = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
        fullScreenMode = fullscreen
        fullscreenOrientation = orientation
        val wanted = if (fullscreen) orientation else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (requestedOrientation != wanted) requestedOrientation = wanted
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
        view?.setFullscreenButtonState(fullscreen)
        updateChrome()
    }

    private fun externalPlayer() {
        val uri = source?.uri ?: return
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        if (uri.scheme == "content") intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            launchingAnotherScreen = true
            updatePipParams()
            startActivity(Intent.createChooser(intent, getString(R.string.ui_choose_player)))
        } catch (_: ActivityNotFoundException) {
            launchingAnotherScreen = false
            updatePipParams()
            Toast.makeText(this, getString(R.string.ui_external_player_missing), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLocalSubtitles() {
        launchingAnotherScreen = true
        updatePipParams()
        try {
            chooseSubtitle.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            launchingAnotherScreen = false
            updatePipParams()
            Toast.makeText(this, R.string.ui_load_subtitle_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPlayer() {
        // Controls drawn over the video use night resources even on a light workspace.
        // Keep the Activity as the base so the display, density and window services stay intact.
        val mediaContext = ContextThemeWrapper(this, R.style.AppTheme).apply {
            applyOverrideConfiguration(Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_YES })
        }
        playerRoot = FrameLayout(mediaContext).apply { setBackgroundColor(Color.BLACK) }
        // Toolbar owns the overflow popup, which uses the same appearance as the other menus.
        playerToolbar = Toolbar(this).apply {
            title = mediaName
            setTitleTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.playback_toolbar_background)
            navigationIcon = context.icon(Symbol.Back, Color.WHITE)
            navigationContentDescription = getString(R.string.ui_back_to_files)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        val playerSurface = (layoutInflater.cloneInContext(mediaContext)
            .inflate(R.layout.playback_surface, playerRoot, false) as PlayerView).apply {
            if (systemVideoOnly) {
                controllerAutoShow = false
                useController = false
            }
            player = this@PlaybackActivity.player
            controllerShowTimeoutMs = if (audio) 0 else VIDEO_CONTROLS_TIMEOUT_MS
            if (!audio) setFullscreenButtonClickListener { enabled ->
                // Media3 also notifies this listener when we update the button from the mode menu.
                if (enabled != fullScreenMode) toggleFullscreen(enabled)
            }
            findViewById<View>(androidx.media3.ui.R.id.exo_settings).setOnClickListener(::showTransportSettings)
            findViewById<View>(R.id.playback_subtitles).apply {
                showWhen(!audio)
                setOnClickListener {
                    chooseTrack(C.TRACK_TYPE_TEXT, getString(R.string.ui_subtitles), getString(R.string.ui_subtitles_off))
                }
            }
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                controllerVisible = visibility == View.VISIBLE
                updateChrome()
                // A hide deadline can run just before the overflow window acquires focus.
                if (!controllerVisible && !systemVideoOnly && useController &&
                    (playerToolbar.isOverflowMenuShowing || transportPopupOpen)) showController()
            })
        }
        view = playerSurface
        if (!audio) {
            val motion = FullVideoPull(playerRoot, playerSurface, player, { miniCorner }) { frameHandoff.capture(playerSurface) }
            pullMotion = motion
            gestures = PlaybackGestures(playerSurface, motion::pull, {
                motion.cancel()
                frameHandoff.clear()
            }, ::minimizeFromGesture)
        }
        (playerSurface.videoSurfaceView ?: playerSurface).addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (l != oldL || t != oldT || r != oldR || b != oldB) updatePipParams()
        }
        updateDurationVisibility()
        playerRoot.addView(playerSurface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        playerRoot.addView(playerToolbar, FrameLayout.LayoutParams(MATCH_PARENT, dp(56), Gravity.TOP))
        playerErrorPanel = mediaContext.column().apply {
            setBackgroundResource(R.drawable.playback_transport_background)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minimumWidth = dp(290)
        }
        playerErrorText = mediaContext.label()
        retryButton = mediaContext.button(getString(R.string.ui_retry_playback)) { refreshPlayback() }
        playerErrorPanel.addFullWidth(playerErrorText)
        playerErrorPanel.addFullWidth(retryButton)
        playerErrorPanel.addFullWidth(mediaContext.button(getString(R.string.ui_external_player), ::externalPlayer))
        playerRoot.addView(playerErrorPanel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        val transport = playerSurface.findViewById<View>(R.id.playback_transport)
        ViewCompat.setOnApplyWindowInsetsListener(playerRoot) { _, insets ->
            val size = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            playerToolbar.setPadding(size.left, size.top, size.right, 0)
            (playerToolbar.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                val height = dp(56) + size.top
                if (params.height != height) {
                    params.height = height
                    playerToolbar.layoutParams = params
                }
            }
            val safeControls = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val margin = dp(12)
            val errorMargin = maxOf(safeControls.left, safeControls.right) + margin
            val errorLayout = playerErrorPanel.layoutParams as FrameLayout.LayoutParams
            if (errorLayout.leftMargin != errorMargin || errorLayout.rightMargin != errorMargin) {
                errorLayout.setMargins(errorMargin, 0, errorMargin, 0)
                playerErrorPanel.layoutParams = errorLayout
            }
            val bottom = safeControls.bottom + dp(8)
            val controls = transport.layoutParams as FrameLayout.LayoutParams
            if (controls.leftMargin != safeControls.left + margin ||
                controls.rightMargin != safeControls.right + margin || controls.bottomMargin != bottom
            ) {
                controls.setMargins(safeControls.left + margin, 0, safeControls.right + margin, bottom)
                transport.layoutParams = controls
            }
            insets
        }
        createMenu()
        setContentView(playerRoot)
        updateChrome()
        lifecycleScope.launch {
            state.observe { listOf(sources, source, subtitle, inPip, playerError, refreshing, fetchingSubtitle) }
                .collect { updateChrome(); updateMenu() }
        }
    }

    private fun updateDurationVisibility() {
        val surface = view ?: return
        val known = player.duration != C.TIME_UNSET && player.duration >= 0L
        surface.findViewById<TextView>(androidx.media3.ui.R.id.exo_duration).showWhen(known)
        surface.findViewById<TextView>(R.id.playback_unknown_duration).showWhen(!known)
    }

    private fun updateChrome() {
        if (!::playerToolbar.isInitialized || !::playerErrorPanel.isInitialized) return
        val videoOnly = systemVideoOnly
        gestures?.update(enabled = !videoOnly && playerError == null && InAppPlayback.mini.value !== this,
            controlsVisible = controllerVisible)
        playerToolbar.showWhen(!videoOnly && (audio || controllerVisible || playerError != null || playerToolbar.isOverflowMenuShowing))
        view?.let { surface ->
            surface.controllerAutoShow = !videoOnly
            val showControls = !videoOnly && (playerError == null || transportPopupOpen)
            if (surface.useController != showControls) surface.useController = showControls
            if (videoOnly) surface.hideController()
            surface.setCustomErrorMessage(if (refreshing && playerError == null && !videoOnly) getString(R.string.ui_getting_playback_link_ellipsis) else null)
        }
        playerErrorPanel.showWhen(!videoOnly && playerError != null)
        playerErrorText.text = playerError
        retryButton.text = if (refreshing) getString(R.string.ui_getting_playback_link) else getString(R.string.ui_retry_playback)
        retryButton.isEnabled = !refreshing
    }

    private fun createMenu() {
        val toolbar = playerToolbar
        val menu = toolbar.menu
        qualityAction = menu.add(getString(R.string.ui_quality)).apply {
            setIcon(R.drawable.playback_quality_icon)
            iconTintList = ColorStateList.valueOf(Color.WHITE)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { chooseQuality(); true }
        }
        cloudSubtitleAction = menu.add(getString(R.string.ui_choose_cloud_subtitles)).apply {
            isVisible = !audio
            setOnMenuItemClickListener { chooseCloudSubtitle(); true }
        }
        if (!audio) {
            menu.add(getString(R.string.ui_import_local_subtitles)).setOnMenuItemClickListener {
                openLocalSubtitles(); true
            }
        }
        removeSubtitleAction = menu.add(getString(R.string.ui_remove_external_subtitles)).setOnMenuItemClickListener {
            subtitleJob?.cancel()
            cloudSubtitle = null
            preferSidecar = false
            subtitle = null
            source?.let { load(it) }
            true
        }
        pipAction = menu.add(getString(R.string.ui_picture_in_picture)).apply {
            isVisible = pipSupported
            setOnMenuItemClickListener { enterPip(); true }
        }
        menu.add(getString(R.string.ui_external_player)).setOnMenuItemClickListener { externalPlayer(); true }
        toolbar.overflowIcon = toolbar.overflowIcon?.mutate()?.apply { setTint(Color.WHITE) }
        updateMenu()
    }

    private fun updateMenu() {
        if (!::pipAction.isInitialized) return
        qualityAction.isVisible = !audio && sources.size > 1
        val description = source?.label?.let { getString(R.string.ui_current_quality, it) } ?: getString(R.string.ui_quality)
        if (qualityAction.contentDescription != description) {
            qualityAction.contentDescription = description
            qualityAction.tooltipText = description
        }
        removeSubtitleAction.isVisible = !audio && subtitle != null
        if (cloudSubtitleAction.isEnabled == fetchingSubtitle) cloudSubtitleAction.isEnabled = !fetchingSubtitle
        val canEnterPip = playerError == null && player.playbackState == Player.STATE_READY
        if (pipAction.isEnabled != canEnterPip) pipAction.isEnabled = canEnterPip
    }

    private fun chooseQuality() {
        val options = sources
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_quality))
            .setSingleChoiceItems(options.map(PlaybackSource::label).toTypedArray(), options.indexOf(source)) { dialog, index ->
                dialog.dismiss()
                val option = options[index]
                if (option != source) load(option)
            }
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .show()
    }

    private fun chooseCloudSubtitle() {
        if (cloudSubtitles.isEmpty()) {
            browseSubtitleFolder()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_cloud_subtitles))
            .setItems(cloudSubtitles.map(CloudSubtitle::name).toTypedArray()) { _, index ->
                attachCloudSubtitle(cloudSubtitles[index])
            }
            .setPositiveButton(R.string.subtitle_browse_drive) { _, _ -> browseSubtitleFolder() }
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .show()
    }

    private fun browseSubtitleFolder() {
        val history = TransferHistory(this)
        val sessionOwner = history.currentAccountScope()
        val cacheOwner = history.verifiedAccountScope()
        val cache = DriveListingCache(this)
        showCloudSubtitlePicker(this, subtitleFolderPath,
            readFolder = { parent ->
                val ticket = cache.beginRead()
                val response = subtitleRequest("files", "parent" to parent) as? JSONArray
                    ?: error(getString(R.string.ui_load_failed))
                val files = response.files()
                if (cacheOwner != null) lifecycleScope.launch(Dispatchers.IO) {
                    if (cacheOwner == history.verifiedAccountScope()) cache.write(cacheOwner, parent, files, ticket)
                }
                files
            },
            readCachedFolder = { parent -> cacheOwner?.let { cache.read(it, parent) } },
            isCurrentAccount = { sessionOwner != null && sessionOwner == history.currentAccountScope() },
            onSelected = { file -> attachCloudSubtitle(CloudSubtitle(file.id, file.name)) },
        )
    }

    private fun subtitleRequest(operation: String, argument: Pair<String, String>): Any? {
        return try {
            subtitleApi.call(operation, argument)
        } catch (error: CloudError) {
            // The OS can restore PlaybackActivity before MainActivity initializes the signed-in session.
            if (error.code != "auth" || subtitleApi.call("init") != true) throw error
            subtitleApi.call(operation, argument)
        }
    }

    private fun showTransportSettings(anchor: View) {
        PopupMenu(this, anchor, Gravity.END).apply {
            transportPopupOpen = true
            updateControllerTimeout()
            setOnDismissListener {
                transportPopupOpen = false
                updateControllerTimeout()
                updateChrome()
            }
            menu.add(getString(R.string.ui_playback_speed)).setOnMenuItemClickListener {
                val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
                val labels = speeds.map { if (it == 1f) getString(R.string.ui_normal_speed) else "${it}×" }.toTypedArray()
                AlertDialog.Builder(this@PlaybackActivity).setTitle(getString(R.string.ui_playback_speed))
                    .setSingleChoiceItems(labels, speeds.indexOf(player.playbackParameters.speed)) { dialog, index ->
                        player.setPlaybackSpeed(speeds[index])
                        dialog.dismiss()
                    }
                    .setNegativeButton(getString(R.string.ui_cancel), null).show()
                true
            }
            if (player.currentTracks.isTypeSupported(C.TRACK_TYPE_AUDIO)) {
                menu.add(getString(R.string.ui_audio_track)).setOnMenuItemClickListener {
                    chooseTrack(C.TRACK_TYPE_AUDIO, getString(R.string.ui_audio_track), getString(R.string.ui_automatic))
                    true
                }
            }
            if (!audio) {
                menu.add(R.string.playback_fullscreen_portrait).setOnMenuItemClickListener {
                    toggleFullscreen(true, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
                    true
                }
                menu.add(R.string.playback_fullscreen_landscape).setOnMenuItemClickListener {
                    toggleFullscreen(true)
                    true
                }
            }
            show()
        }
    }

    private fun chooseTrack(type: Int, title: String, defaultLabel: String) {
        val options = player.currentTracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter(group::isTrackSupported).map { group to it }
        }
        if (options.isEmpty() && type != C.TRACK_TYPE_TEXT) return
        val names = DefaultTrackNameProvider(resources)
        val trackNames = options.map { (track, index) -> names.getTrackName(track.getTrackFormat(index)) }
        val counts = trackNames.groupingBy { it }.eachCount()
        val labels = listOf(defaultLabel) + trackNames.mapIndexed { index, name ->
            if (counts.getValue(name) > 1) "$name（${index + 1}）" else name
        }
        val current = player.trackSelectionParameters
        val selected = options.indexOfFirst { (track, index) ->
            if (type == C.TRACK_TYPE_AUDIO) current.overrides[track.mediaTrackGroup]?.trackIndices?.contains(index) == true
            else type !in current.disabledTrackTypes && track.isTrackSelected(index)
        } + 1
        val dialog = AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, choice ->
                val parameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                    .setTrackTypeDisabled(type, choice == 0 && type == C.TRACK_TYPE_TEXT)
                options.getOrNull(choice - 1)?.let { (track, index) ->
                    parameters.setOverrideForType(TrackSelectionOverride(track.mediaTrackGroup, index))
                }
                if (type == C.TRACK_TYPE_TEXT) {
                    subtitleJob?.cancel()
                    preferSidecar = options.getOrNull(choice - 1)?.let { (track, index) ->
                        track.getTrackFormat(index).id?.substringAfterLast(':') == SIDECAR_ID
                    } == true
                }
                player.trackSelectionParameters = parameters.build()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.ui_cancel), null)
        if (type == C.TRACK_TYPE_TEXT) {
            dialog.setPositiveButton(R.string.ui_choose_cloud_subtitles) { _, _ -> chooseCloudSubtitle() }
            dialog.setNeutralButton(R.string.ui_import_local_subtitles) { _, _ -> openLocalSubtitles() }
        }
        dialog.show()
    }

    companion object {
        private const val VIDEO_CONTROLS_TIMEOUT_MS = 3_500
        private const val SIDECAR_ID = "pikpak-sidecar"

        internal fun localIntent(context: Context, uri: Uri, title: String) =
            Intent(context, PlaybackActivity::class.java)
                .setData(uri).putExtra("name", title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        internal fun intent(
            context: Context, file: CloudFile, response: JSONObject,
            subtitles: List<CloudFile>, folderPath: List<Folder> = emptyList(),
        ) =
            Intent(context, PlaybackActivity::class.java)
                .putExtra("id", file.id)
                .putExtra("name", file.name)
                .putExtra("audio", file.category == FileCategory.Audio)
                .putExtra("sources", response.toString())
                .putExtra("subtitles", JSONArray(subtitles.map {
                    JSONObject().put("id", it.id).put("name", it.name)
                }).toString())
                .putExtra("subtitleFolderPath", JSONArray(folderPath.map {
                    JSONObject().put("id", it.id).put("name", it.name)
                }).toString())
    }
}

internal class PlaybackControlRows(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private lateinit var primaryControls: LinearLayout
    private lateinit var secondaryControls: LinearLayout
    private lateinit var spacer: View

    override fun onFinishInflate() {
        super.onFinishInflate()
        primaryControls = findViewById(R.id.playback_primary_controls)
        secondaryControls = findViewById(R.id.playback_secondary_controls)
        spacer = findViewById(R.id.playback_controls_spacer)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val buttons = primaryControls.children + secondaryControls.children
        val buttonsWidth = buttons.filter { it.visibility != GONE }.sumOf { it.layoutParams.width }
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val stacked = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && availableWidth < buttonsWidth
        val nextOrientation = if (stacked) VERTICAL else HORIZONTAL
        if (orientation != nextOrientation) {
            orientation = nextOrientation
            gravity = if (stacked) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            spacer.showWhen(!stacked)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
