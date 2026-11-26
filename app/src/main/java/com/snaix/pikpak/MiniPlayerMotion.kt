package com.snaix.pikpak

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

internal enum class MiniCorner(val right: Boolean, val bottom: Boolean) {
    TOP_LEFT(false, false), TOP_RIGHT(true, false), BOTTOM_LEFT(false, true), BOTTOM_RIGHT(true, true)
}

/** The same video image rectangle expressed in two activity windows, possibly in different orientations. */
internal data class MiniOrigin(val cx: Float, val cy: Float, val widthFraction: Float, val dim: Float)

private fun videoAspect(player: Player): Float {
    val video = player.videoSize
    if (video.width == 0 || video.height == 0) return 16f / 9f
    return (video.width.toFloat() / video.height * video.pixelWidthHeightRatio)
        .takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
}

private fun fit(width: Float, height: Float, aspect: Float): RectF {
    val w = minOf(width, height * aspect).coerceAtLeast(1f)
    val h = w / aspect
    val x = (width - w) / 2
    val y = (height - h) / 2
    return RectF(x, y, x + w, y + h)
}

private data class DockSpace(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val w: Int, val h: Int) {
    fun left(corner: MiniCorner) = if (corner.right) x1 else x0
    fun top(corner: MiniCorner) = if (corner.bottom) y1 else y0
    fun closest(x: Float, y: Float) = MiniCorner.entries.first {
        it.right == (x > (x0 + x1) / 2) && it.bottom == (y > (y0 + y1) / 2)
    }
}

private fun dockSpace(parent: View, aspect: Float): DockSpace {
    val edge = parent.context.dp(12)
    val pos = IntArray(2).also(parent::getLocationInWindow)
    val safe = ViewCompat.getRootWindowInsets(parent)?.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
    val root = parent.rootView
    val left = edge + ((safe?.left ?: 0) - pos[0]).coerceAtLeast(0)
    val top = edge + ((safe?.top ?: 0) - pos[1]).coerceAtLeast(0)
    val right = parent.width - edge - (pos[0] + parent.width + (safe?.right ?: 0) - root.width).coerceAtLeast(0)
    val bottom = parent.height - edge - (pos[1] + parent.height + (safe?.bottom ?: 0) - root.height).coerceAtLeast(0)
    val maxWidth = minOf(parent.context.dp(216), (right - left).coerceAtLeast(2) / 2)
    val maxHeight = minOf(parent.context.dp(176), (bottom - top).coerceAtLeast(2) / 2)
    val fitted = fit(maxWidth.toFloat(), maxHeight.toFloat(), aspect)
    val w = fitted.width().roundToInt().coerceIn(1, maxWidth)
    val h = fitted.height().roundToInt().coerceIn(1, maxHeight)
    return DockSpace(left.toFloat(), top.toFloat(), (right - w).coerceAtLeast(left).toFloat(),
        (bottom - h).coerceAtLeast(top).toFloat(), w, h)
}

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

/** A cancellable clock. Geometry is set synchronously at both ends, including with system animations disabled. */
private class MotionClock {
    private var running: ValueAnimator? = null

    fun stop() {
        running?.removeAllListeners()
        running?.cancel()
        running = null
    }

    fun run(ms: Long, draw: (Float) -> Unit, finish: () -> Unit = {}) {
        stop()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            draw(1f)
            finish()
            return
        }
        draw(0f)
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = LinearInterpolator()
            addUpdateListener { draw(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    running = null
                    finish()
                }
            })
            start()
        }
    }
}

private fun easeOut(t: Float): Float {
    val left = 1f - t
    return 1f - left * left * left
}

/** Scales the actual rendered image (ignoring letterboxes) under the finger. */
@OptIn(UnstableApi::class)
internal class FullVideoPull(
    private val root: FrameLayout,
    private val surface: PlayerView,
    private val player: Player,
    private val corner: () -> MiniCorner,
    private val captureFrame: () -> Unit,
) {
    private val clock = MotionClock()
    private var image = RectF()
    private var visible = RectF()
    private var destination = RectF()
    private var fraction = 0f
    private var active = false
    private var restoreControls = false

    fun pull(dy: Float) {
        if (root.width <= 0 || root.height <= 0 || !ValueAnimator.areAnimatorsEnabled()) return
        if (!active) {
            active = true
            captureFrame()
            image = fit(root.width.toFloat(), root.height.toFloat(), videoAspect(player))
            val dock = dockSpace(root, videoAspect(player))
            val x = dock.left(corner())
            val y = dock.top(corner())
            destination = RectF(x, y, x + dock.w, y + dock.h)
            restoreControls = surface.isControllerFullyVisible
            surface.hideController()
        }
        clock.stop()
        draw((dy / (root.height * .70f)).coerceIn(0f, .93f))
    }

    private fun draw(t: Float) {
        fraction = t
        val w = mix(image.width(), destination.width(), t)
        val scale = w / image.width()
        val x = mix(image.left, destination.left, t)
        val y = mix(image.top, destination.top, t)
        visible.set(x, y, x + w, y + image.height() * scale)
        surface.pivotX = 0f
        surface.pivotY = 0f
        surface.scaleX = scale
        surface.scaleY = scale
        surface.translationX = x - image.left * scale
        surface.translationY = y - image.top * scale
    }

    fun origin(): MiniOrigin? {
        if (root.width <= 0 || root.height <= 0 || !ValueAnimator.areAnimatorsEnabled()) return null
        val r = if (active) visible else fit(root.width.toFloat(), root.height.toFloat(), videoAspect(player))
        return MiniOrigin(r.centerX() / root.width, r.centerY() / root.height, r.width() / root.width,
            1f - fraction * .55f)
    }

    fun cancel() {
        if (!active) return
        val from = fraction
        clock.run(180, { draw(from * (1f - easeOut(it))) }) { reset(true) }
    }

    fun reset(restore: Boolean = false) {
        clock.stop()
        if (!active) return
        surface.scaleX = 1f
        surface.scaleY = 1f
        surface.translationX = 0f
        surface.translationY = 0f
        active = false
        fraction = 0f
        if (restore && restoreControls) surface.showController()
    }
}

/** Moves only compositor properties during touch, snap and video-to-card morphs. */
@OptIn(UnstableApi::class)
internal class MiniDockMotion(
    private val container: FrameLayout,
    private val card: FrameLayout,
    private val owner: PlaybackActivity,
    private val onExpanded: () -> Unit,
    private val onSettled: () -> Unit,
) {
    private val clock = MotionClock()
    private val close = card.findViewById<View>(R.id.playback_mini_close)
    private val surface = card.findViewById<PlayerView>(R.id.playback_mini_video)
    private val shape = card.background as GradientDrawable
    private val radius = card.context.dp(14).toFloat()
    private val slop = ViewConfiguration.get(card.context).scaledTouchSlop
    private var space: DockSpace? = null
    private var pending = owner.miniOrigin.also { owner.miniOrigin = null }
    private var shade: View? = null
    private var pinned = false
    private var opening = false
    private var tracking = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var originX = 0f
    private var originY = 0f
    private var velocity: VelocityTracker? = null

    init {
        card.pivotX = 0f
        card.pivotY = 0f
        card.setOnClickListener { expand() }
        surface.setOnClickListener { card.performClick() }
        val touch = View.OnTouchListener { view, event -> touch(view, event) }
        card.setOnTouchListener(touch)
        surface.setOnTouchListener(touch)
    }

    fun layout(inPip: Boolean) {
        if (container.width <= 0 || container.height <= 0) return
        val updated = dockSpace(container, videoAspect(owner.miniPlayer))
        if (space == updated && inPip == pinned) return
        clock.stop()
        clearTouch()
        closingShade()
        val resumeExpand = opening && !inPip
        opening = false
        pinned = inPip
        space = updated
        val lp = card.layoutParams as FrameLayout.LayoutParams
        lp.width = if (pinned) FrameLayout.LayoutParams.MATCH_PARENT else updated.w
        lp.height = if (pinned) FrameLayout.LayoutParams.MATCH_PARENT else updated.h
        lp.gravity = Gravity.LEFT or Gravity.TOP
        lp.setMargins(0, 0, 0, 0)
        card.layoutParams = lp
        card.scaleX = 1f
        card.scaleY = 1f
        card.translationX = if (pinned) 0f else updated.left(owner.miniCorner)
        card.translationY = if (pinned) 0f else updated.top(owner.miniCorner)
        shape.cornerRadius = if (pinned) 0f else radius
        close.alpha = 1f
        close.showWhen(!pinned)
        val from = pending
        pending = null
        if (!pinned && from != null) {
            val width = (from.widthFraction * container.width).coerceIn(1f, container.width.toFloat())
            val fromX = from.cx * container.width - width / 2
            val fromY = from.cy * container.height - (width * updated.h / updated.w) / 2
            val s = width / updated.w
            card.translationX = fromX
            card.translationY = fromY
            card.scaleX = s
            card.scaleY = s
            morph(updated.left(owner.miniCorner), updated.top(owner.miniCorner), 1f, from.dim, 0f) { onSettled() }
        } else if (resumeExpand) expand()
    }

    private fun expand() {
        if (pinned || opening) return
        val dock = space ?: return
        opening = true
        owner.frameHandoff.capture(surface)
        val image = fit(container.width.toFloat(), container.height.toFloat(), videoAspect(owner.miniPlayer))
        morph(image.left, image.top, image.width() / dock.w, shade?.alpha ?: 0f, 1f) {
            if (opening && card.isAttachedToWindow) {
                owner.frameHandoff.capture(surface)
                onExpanded()
            }
        }
    }

    private fun morph(endX: Float, endY: Float, endScale: Float, fromDim: Float, toDim: Float, done: () -> Unit) {
        val x = card.translationX
        val y = card.translationY
        val scale = card.scaleX
        val startClose = if (opening) close.alpha else 0f
        val overlay = shade ?: View(container.context).apply {
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            container.addView(this, container.indexOfChild(card))
            shade = this
        }
        clock.run(if (opening) 260 else 250, { time ->
            val t = easeOut(time)
            val s = mix(scale, endScale, t)
            card.translationX = mix(x, endX, t)
            card.translationY = mix(y, endY, t)
            card.scaleX = s
            card.scaleY = s
            val darkness = mix(fromDim, toDim, t)
            overlay.alpha = darkness
            close.alpha = mix(startClose, if (opening) 0f else 1f, t)
            shape.cornerRadius = radius * (1f - darkness) / s
        }) {
            if (!opening) closingShade()
            done()
        }
    }

    private fun closingShade() {
        shade?.let(container::removeView)
        shade = null
    }

    private fun touch(target: View, e: MotionEvent): Boolean {
        val dock = space ?: return false
        if (pinned || opening) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                dragging = false
                downX = e.rawX
                downY = e.rawY
                originX = card.translationX
                originY = card.translationY
                // A spring can be caught at its current position with no jump.
                if (shade == null) clock.stop()
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && maxOf(abs(dx), abs(dy)) > slop) {
                    dragging = true
                    if (shade != null) {
                        // Interrupt a settling transition and keep the card centered beneath the finger.
                        clock.stop()
                        closingShade()
                        val xy = IntArray(2).also(container::getLocationOnScreen)
                        originX = downX - xy[0] - dock.w / 2f
                        originY = downY - xy[1] - dock.h / 2f
                        card.scaleX = 1f
                        card.scaleY = 1f
                        shape.cornerRadius = radius
                        close.alpha = 1f
                    }
                }
                if (dragging) {
                    card.translationX = (originX + dx).coerceIn(dock.x0, dock.x1)
                    card.translationY = (originY + dy).coerceIn(dock.y0, dock.y1)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return true
                recordVelocity(e)
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    settle(dock, velocity?.xVelocity ?: 0f, velocity?.yVelocity ?: 0f)
                } else target.performClick()
                clearTouch()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                if (tracking && shade == null) settle(dock, 0f, 0f)
                clearTouch()
                return true
            }
        }
        if (tracking) recordVelocity(e)
        return true
    }

    private fun recordVelocity(e: MotionEvent) {
        // VelocityTracker normally sees view-local positions, which barely move during a drag.
        val event = MotionEvent.obtain(e)
        event.offsetLocation(e.rawX - e.x, e.rawY - e.y)
        velocity?.addMovement(event)
        event.recycle()
    }

    private fun clearTouch() {
        velocity?.recycle()
        velocity = null
        tracking = false
        dragging = false
    }

    private fun settle(dock: DockSpace, vx: Float, vy: Float) {
        val x = card.translationX
        val y = card.translationY
        val corner = dock.closest(x + vx * .08f, y + vy * .08f)
        owner.miniCorner = corner
        val targetX = dock.left(corner)
        val targetY = dock.top(corner)
        // Critically damped spring with the measured release velocity, in px/second.
        fun at(start: Float, end: Float, speed: Float, t: Float): Float {
            val distance = start - end
            return end + (distance + (speed + 24f * distance) * t) * exp(-24f * t)
        }
        clock.run(345, { fraction ->
            val t = .345f * fraction
            card.translationX = at(x, targetX, vx, t).coerceIn(dock.x0, dock.x1)
            card.translationY = at(y, targetY, vy, t).coerceIn(dock.y0, dock.y1)
        }) {
            card.translationX = targetX
            card.translationY = targetY
            onSettled()
        }
    }

    fun detach() {
        clock.stop()
        clearTouch()
        closingShade()
        card.setOnTouchListener(null)
        surface.setOnTouchListener(null)
    }
}
