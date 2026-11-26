package com.snaix.pikpak

import android.app.Activity
import android.app.ActivityOptions
import android.app.PictureInPictureParams
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** The playback activity owns the decoder throughout. Hosts only lend it a visible video surface. */
@OptIn(UnstableApi::class)
internal object InAppPlayback {
    private val parked = MutableStateFlow<PlaybackActivity?>(null)
    val mini = parked.asStateFlow()
    private var current = WeakReference<PlaybackActivity>(null)
    private var host = WeakReference<MiniPlayerHostActivity>(null)
    private var target = WeakReference<PlayerView>(null)
    private var expanding = false
    private var sessionStarted = false
    private var restoring: Intent? = null

    fun register(owner: PlaybackActivity) {
        val previous = current.get()
        if (previous !== owner && previous != null && !previous.isDestroyed) previous.closePlayback()
        sessionStarted = true
        current = WeakReference(owner)
    }

    fun park(owner: PlaybackActivity) {
        expanding = false
        parked.value = owner
    }

    fun unpark(owner: PlaybackActivity) {
        if (parked.value !== owner) return
        target.get()?.player = null
        target.clear()
        host.clear()
        expanding = false
        parked.value = null
    }

    fun takeExpansion(owner: PlaybackActivity): Boolean {
        if (!expanding || parked.value !== owner) return false
        val full = owner.fullVideoSurface ?: return false
        owner.frameHandoff.expectFirstFrame(target.get(), full)
        PlayerView.switchTargetView(owner.miniPlayer, target.get(), full)
        unpark(owner)
        return true
    }

    fun ownerForHost(from: MiniPlayerHostActivity): PlaybackActivity? {
        val owner = parked.value ?: return null
        if (owner.taskId != from.playbackTaskId) return null
        val active = host.get()
        // Opening the app in its original task must not take video out of a still-visible PiP.
        if (active !== from && active?.isInPictureInPictureMode == true && !from.isInPictureInPictureMode) return null
        return owner
    }

    fun pinnedHost(owner: PlaybackActivity): MiniPlayerHostActivity? =
        host.get()?.takeIf { parked.value === owner && it.isInPictureInPictureMode }

    fun hasSeparatePip(from: MiniPlayerHostActivity): Boolean {
        val owner = parked.value ?: return false
        val pinned = pinnedHost(owner) ?: return false
        return owner.taskId == from.playbackTaskId && pinned !== from
    }

    fun expand(from: MiniPlayerHostActivity) {
        if (expanding || from.isInPictureInPictureMode || ownerForHost(from) == null) return
        expanding = true
        try {
            from.startMiniTransition(Intent(from, PlaybackActivity::class.java))
        } catch (e: RuntimeException) {
            expanding = false
            throw e
        }
    }

    fun closeCloudPlayback() {
        current.get()?.takeIf { it.isCloudPlayback }?.closePlayback()
    }

    fun hostStarted(from: MiniPlayerHostActivity) {
        val owner = ownerForHost(from) ?: return
        if (expanding) return
        host = WeakReference(from)
        owner.resumeVisiblePlayback()
    }

    fun hostStopped(from: Activity) {
        if (host.get() !== from) return
        host.clear()
        if (!expanding) parked.value?.pauseHiddenPlayback()
    }

    fun hostIsVisible(owner: PlaybackActivity) = parked.value === owner && host.get() != null

    fun attachMiniSurface(from: MiniPlayerHostActivity, surface: PlayerView) {
        val owner = ownerForHost(from) ?: return
        if (expanding) return
        host = WeakReference(from)
        owner.resumeVisiblePlayback()
        val original = target.get() ?: owner.fullVideoSurface?.takeIf { it.player === owner.miniPlayer }
        if (surface.player !== owner.miniPlayer) {
            if (!from.isInPictureInPictureMode) owner.frameHandoff.expectFirstFrame(original, surface)
            PlayerView.switchTargetView(owner.miniPlayer, original, surface)
        }
        target = WeakReference(surface)
        owner.rememberMiniHost(from)
    }

    fun detachMiniSurface(surface: PlayerView) {
        if (target.get() !== surface) return
        surface.player = null
        target.clear()
    }

    fun saveParkedState(from: MiniPlayerHostActivity): Bundle? {
        val owner = ownerForHost(from) ?: return null
        return Bundle().apply {
            putParcelable("launch", Intent(owner.intent))
            putBundle("playback", owner.playbackSnapshot())
        }
    }

    @Suppress("DEPRECATION")
    fun restoreParkedState(from: Activity, state: Bundle) {
        // Only the first restored host in a new process may replay its snapshot.
        if (sessionStarted || from.isFinishing || from.isDestroyed) return
        val launch = state.getParcelable<Intent>("launch") ?: return
        val snapshot = state.getBundle("playback") ?: return
        sessionStarted = true
        val recovery = Intent(launch).setClass(from, PlaybackActivity::class.java)
            .setFlags((launch.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_RECOVER, snapshot).putExtra(EXTRA_HOST, from.componentName)
        // Android may first recreate the parked activity with its older pre-minimize saved state
        // and only afterwards deliver the reorder intent. Prefer the foreground host's snapshot.
        restoring = recovery
        from.startActivity(recovery)
    }

    fun takeRecovery(): Intent? = restoring.also { restoring = null }

    const val EXTRA_RECOVER = "recoveredMiniPlayback"
    const val EXTRA_HOST = "returnToMiniHost"
}

internal fun playbackPipParams(player: Player, sourceView: View, autoEnter: Boolean): PictureInPictureParams {
    val size = player.videoSize
    val pixelRatio = size.pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0 } ?: 1f
    val ratio = if (size.width > 0 && size.height > 0) {
        (size.width.toDouble() * pixelRatio / size.height).coerceIn(1.0 / 2.39, 2.39)
    } else 16.0 / 9
    // Rounding inwards keeps the rational strictly inside platform PiP limits at either extreme.
    val aspect = Rational((ratio * 10000).roundToInt().coerceIn(4185, 23900), 10000)
    val rect = Rect()
    return PictureInPictureParams.Builder().apply {
        setAspectRatio(aspect)
        if (sourceView.getGlobalVisibleRect(rect) && !rect.isEmpty) setSourceRectHint(rect)
        if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(autoEnter)
    }.build()
}

/** The live video morph owns the transition, including when One UI reorders an existing task activity. */
internal fun Activity.startMiniTransition(intent: Intent) {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
}
