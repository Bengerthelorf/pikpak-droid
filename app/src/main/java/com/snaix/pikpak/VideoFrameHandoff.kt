package com.snaix.pikpak

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

/** Bridges the compositor gap between two in-app video outputs; never reads the rest of the window. */
@OptIn(UnstableApi::class)
internal class VideoFrameHandoff(private val owner: PlaybackActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { clear() }
    private var generation = 0
    private var source: PlayerView? = null
    private var item: MediaItem? = null
    private var destination: PlayerView? = null
    private var frame: Bitmap? = null
    private var image: ImageView? = null
    private var copying = false
    private var listener: Player.Listener? = null

    fun capture(from: PlayerView) {
        val player = from.player ?: return
        if (source !== from || destination != null || item != player.currentMediaItem) clear()
        val secure = owner.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 ||
            player.currentMediaItem?.localConfiguration?.drmConfiguration != null || owner.miniPlayer.videoFormat?.drmInitData != null
        if (secure) {
            clear()
            return
        }
        if (copying) return
        val video = from.videoSurfaceView ?: return
        val size = player.videoSize
        if (size.width <= 0 || size.height <= 0 || !video.isAttachedToWindow) return
        val aspect = size.width * size.pixelWidthHeightRatio / size.height
        if (!aspect.isFinite() || aspect <= 0) return
        // A bounded copy on RenderThread avoids a synchronous TextureView.getBitmap readback on touch.
        val texture = if (video is TextureView && video.isAvailable) video.surfaceTexture?.let(::Surface) else null
        val surface = (video as? SurfaceView)?.holder?.surface ?: texture ?: return
        if (!surface.isValid) {
            texture?.release()
            return
        }
        val w = minOf(400f, 400f * aspect).roundToInt().coerceAtLeast(1)
        val h = (w / aspect).roundToInt().coerceIn(1, 400)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val request = generation
        source = from
        item = player.currentMediaItem
        copying = true
        armExpiry()
        try {
            PixelCopy.request(surface, bitmap, { status ->
                texture?.release()
                if (request == generation) {
                    copying = false
                    if (status == PixelCopy.SUCCESS) {
                        frame = bitmap
                        displayFrame()
                    }
                }
            }, handler)
        } catch (_: IllegalArgumentException) {
            texture?.release()
            copying = false
        }
    }

    /** Install before switching PlayerViews so even the receiver's first draw has the old frame. */
    fun expectFirstFrame(from: PlayerView?, to: PlayerView) {
        if (from == null || source !== from || (!copying && frame == null)) return
        if (item != owner.miniPlayer.currentMediaItem) {
            clear()
            return
        }
        destination = to
        displayFrame()
        armExpiry()
        val request = generation
        val player = owner.miniPlayer
        val events = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                // Media3 has queued the frame to the current video output. Leave one draw for
                // SurfaceFlinger / TextureView to latch it before revealing that output.
                to.postOnAnimation {
                    to.postOnAnimation {
                        if (generation == request && destination === to) clear()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) = clear()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = clear()
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int,
            ) = clear()
        }
        listener = events
        player.addListener(events)
    }

    private fun displayFrame() {
        val to = destination ?: return
        val bitmap = frame ?: return
        val content = to.findViewById<FrameLayout>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        val still = image ?: ImageView(to.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val shutter = content.findViewById<View>(androidx.media3.ui.R.id.exo_shutter)
            content.addView(this, content.indexOfChild(shutter) + 1, FrameLayout.LayoutParams(-1, -1))
            image = this
        }
        still.setImageBitmap(bitmap)
    }

    private fun armExpiry() {
        handler.removeCallbacks(expire)
        // Failed or stalled output must not leave an old image indefinitely.
        handler.postDelayed(expire, 1600)
    }

    fun clear() {
        generation++
        handler.removeCallbacks(expire)
        listener?.let(owner.miniPlayer::removeListener)
        listener = null
        image?.let { still ->
            still.setImageDrawable(null)
            (still.parent as? FrameLayout)?.removeView(still)
        }
        image = null
        destination = null
        source = null
        item = null
        frame = null
        copying = false
    }
}
