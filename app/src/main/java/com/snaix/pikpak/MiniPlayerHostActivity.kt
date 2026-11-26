package com.snaix.pikpak

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
abstract class MiniPlayerHostActivity : AppCompatActivity() {
    internal var playbackTaskId = -1
        private set
    private var owner: PlaybackActivity? = null
    private var card: FrameLayout? = null
    private var dockMotion: MiniDockMotion? = null
    private var observation: Job? = null
    private var savedMini: Bundle? = null
    private var launching = false
    private var pinned = false
    private var leavingPip = false
    private var hiddenWorkspace: View? = null
    private val container get() = findViewById<FrameLayout>(android.R.id.content)
    private val video get() = card?.findViewById<PlayerView>(R.id.playback_mini_video)
    private val supportsPip by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    private val onHostLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sizeMini() }
    private val onVideoLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateMiniPip() }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_VIDEO_SIZE_CHANGED)) sizeMini()
            if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_VIDEO_SIZE_CHANGED, Player.EVENT_PLAYER_ERROR)) updateMiniPip()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android may move this activity alone into a new task for PiP, leaving the decoder
        // in the original task. Keep the association across that move and activity recreation.
        playbackTaskId = savedInstanceState?.getInt("playbackTask", taskId) ?: taskId
        savedMini = savedInstanceState?.getBundle("inAppPlayback")
    }

    override fun onStart() {
        super.onStart()
        InAppPlayback.hostStarted(this)
        observation = lifecycleScope.launch {
            InAppPlayback.mini.collect { parked ->
                if (isInPictureInPictureMode && owner != null && parked !== owner) {
                    // A replacement video or logout ended this session. Remove its system window.
                    finish()
                } else {
                    showMini(InAppPlayback.ownerForHost(this@MiniPlayerHostActivity))
                }
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        leavingPip = false
        launching = false
        val saved = savedMini
        savedMini = null
        if (saved != null && InAppPlayback.mini.value == null) InAppPlayback.restoreParkedState(this, saved)
        showMini(InAppPlayback.ownerForHost(this))
        updateMiniPip()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("playbackTask", playbackTaskId)
        (InAppPlayback.saveParkedState(this) ?: savedMini)?.let { outState.putBundle("inAppPlayback", it) }
    }

    override fun onStop() {
        observation?.cancel()
        observation = null
        if ((isInPictureInPictureMode || leavingPip) && !isChangingConfigurations &&
            getSystemService(PowerManager::class.java).isInteractive) closeHostedMini()
        showMini(null)
        InAppPlayback.hostStopped(this)
        super.onStop()
    }

    protected fun closeHostedMini() {
        owner?.takeIf { it === InAppPlayback.ownerForHost(this) }?.closePlayback()
    }

    private fun showMini(next: PlaybackActivity?) {
        if (next === owner) return
        owner?.miniPlayer?.removeListener(playerListener)
        video?.let {
            it.removeOnLayoutChangeListener(onVideoLayout)
            InAppPlayback.detachMiniSurface(it)
        }
        container.removeOnLayoutChangeListener(onHostLayout)
        dockMotion?.detach()
        dockMotion = null
        card?.let(container::removeView)
        card = null
        owner = next
        if (next == null) {
            disableMiniAutoPip()
            return
        }
        val mini = layoutInflater.inflate(R.layout.playback_mini, container, false) as FrameLayout
        card = mini
        mini.background = GradientDrawable().apply { setColor(Color.BLACK) }
        mini.contentDescription = getString(R.string.mini_expand_video, next.miniTitle)
        val surface = mini.findViewById<PlayerView>(R.id.playback_mini_video)
        mini.findViewById<View>(R.id.playback_mini_close).apply {
            val disc = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()) }
            background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(0x55ffffff), disc, null), dp(7))
            setOnClickListener { closeHostedMini() }
        }
        container.addView(mini)
        dockMotion = MiniDockMotion(container, mini, next, { InAppPlayback.expand(this) }, ::updateMiniPip)
        container.addOnLayoutChangeListener(onHostLayout)
        surface.addOnLayoutChangeListener(onVideoLayout)
        ViewCompat.setOnApplyWindowInsetsListener(mini) { _, insets -> sizeMini(); insets }
        InAppPlayback.attachMiniSurface(this, surface)
        next.miniPlayer.addListener(playerListener)
        sizeMini()
        updateMiniPip()
    }

    private fun sizeMini() {
        val inPip = isInPictureInPictureMode
        dockMotion?.layout(inPip)
        // All three hosts keep their workspace as the first child, beneath the video.
        // Hide it even from accessibility while the system owns playback controls.
        if (inPip && card != null) {
            val workspace = container.getChildAt(0)
            if (workspace != null && workspace !== card && workspace.visibility == View.VISIBLE) {
                hiddenWorkspace = workspace
                workspace.visibility = View.INVISIBLE
            }
        } else {
            hiddenWorkspace?.visibility = View.VISIBLE
            hiddenWorkspace = null
        }
        card?.keepScreenOn = owner?.miniPlayer?.isPlaying == true && !isInPictureInPictureMode
    }

    private fun canAutoPip(): Boolean {
        val player = owner?.miniPlayer ?: return false
        return !launching && !isFinishing && player.playWhenReady && player.playerError == null &&
            player.playbackState in Player.STATE_BUFFERING..Player.STATE_READY
    }

    private fun disableMiniAutoPip() {
        if (Build.VERSION.SDK_INT >= 31 && supportsPip && !isInPictureInPictureMode && !isFinishing) {
            applyMiniPipParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
    }

    private fun applyMiniPipParams(params: PictureInPictureParams) {
        try {
            setPictureInPictureParams(params)
        } catch (_: IllegalStateException) {
            // The window can temporarily lose PiP eligibility while Android moves it between tasks.
        }
    }

    private fun updateMiniPip() {
        val surface = video ?: return
        val player = owner?.miniPlayer ?: return
        card?.keepScreenOn = player.isPlaying && !isInPictureInPictureMode
        if (supportsPip && !isInPictureInPictureMode && !isFinishing) {
            applyMiniPipParams(playbackPipParams(player, surface, canAutoPip()))
        }
    }

    @Deprecated("Android activity launch hook")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (owner != null) {
            launching = true
            disableMiniAutoPip()
        }
        try {
            super.startActivityForResult(intent, requestCode, options)
        } catch (failure: RuntimeException) {
            launching = false
            updateMiniPip()
            throw failure
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 31 || !supportsPip || !canAutoPip()) return
        val surface = video ?: return
        val player = owner?.miniPlayer ?: return
        try {
            enterPictureInPictureMode(playbackPipParams(player, surface, false))
        } catch (_: IllegalStateException) {
            // Leave the app normally if this window cannot enter PiP.
        }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        leavingPip = !inPip && pinned && !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        pinned = inPip
        if (inPip) {
            showMini(InAppPlayback.ownerForHost(this))
            video?.let { InAppPlayback.attachMiniSurface(this, it) }
        }
        sizeMini()
        if (!inPip) updateMiniPip()
    }
}
