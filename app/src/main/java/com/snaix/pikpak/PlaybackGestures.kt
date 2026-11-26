package com.snaix.pikpak

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.PlayerView
import dev.oneuiproject.oneui.R as OneUIIcons
import kotlin.math.abs

/** Media3 draws its transport above this overlay, so its buttons and seek bar keep native touch handling. */
@OptIn(UnstableApi::class)
internal class PlaybackGestures(
    private val surface: PlayerView,
    private val onPull: (Float) -> Unit = {},
    private val onCancelPull: () -> Unit = {},
    private val minimize: () -> Unit,
) : FrameLayout(surface.context) {
    private val config = ViewConfiguration.get(context)
    private val tapInterval = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val swipeDistance = context.dp(64)
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var pulling = false
    private var moved = false
    private var multiTouch = false
    private var continuesTap = false
    private var lastUp = -1L
    private var lastX = 0f
    private var lastY = 0f
    private var seekCount = 0
    private var listening: Player? = null
    private val confirmTap = Runnable {
        lastUp = -1L
        performClick()
    }

    private val buttons = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }
    private val back = button(R.id.playback_center_back, androidx.media3.ui.R.drawable.exo_icon_rewind,
        R.string.gesture_seek_back, 48) { seek(-1) }
    private val playPause = button(R.id.playback_center_play_pause, OneUIIcons.drawable.ic_oui_control_play,
        androidx.media3.ui.R.string.exo_controls_play_description, 64) {
        surface.player?.let { Util.handlePlayPauseButtonAction(it) }
    }
    private val forward = button(R.id.playback_center_forward, androidx.media3.ui.R.drawable.exo_icon_fastforward,
        R.string.gesture_seek_forward, 48) { seek(1) }
    private val edgeHighlight = SeekEdgeHighlight(context)
    private val feedback = TextView(context).apply {
        id = R.id.playback_seek_feedback
        gravity = Gravity.CENTER
        textSize = 15f
        setTextColor(Color.WHITE)
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
        background = GradientDrawable().apply {
            cornerRadius = context.dp(24).toFloat()
            setColor(0xC0202020.toInt())
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }
    private val feedbackArea = FrameLayout(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(feedback, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
    }
    private val hideFeedback = Runnable { feedback.visibility = View.GONE }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_AVAILABLE_COMMANDS_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED)) updateButtons(player)
        }
    }

    init {
        id = R.id.playback_gestures
        isClickable = true
        isFocusable = true
        addView(edgeHighlight, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(feedbackArea, LayoutParams(0, WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.LEFT))
        addView(buttons, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.gesture_minimize)) { _, _ ->
            minimize()
            true
        }
        checkNotNull(surface.overlayFrameLayout).addView(this, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        update(true, surface.isControllerFullyVisible)
    }

    private fun button(id: Int, image: Int, description: Int, size: Int, action: () -> Unit) =
        AppCompatImageButton(context).apply {
            this.id = id
            setImageResource(image)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = context.getString(description)
            val inset = context.dp(if (size == 64) 17 else 11)
            setPadding(inset, inset, inset, inset)
            background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xB0202020.toInt())
            }, null)
            isFocusable = true
            setOnClickListener {
                action()
                surface.showController()
            }
            buttons.addView(this, LinearLayout.LayoutParams(context.dp(size), context.dp(size)).apply {
                marginStart = context.dp(4)
                marginEnd = context.dp(4)
            })
        }

    fun update(enabled: Boolean, controlsVisible: Boolean) {
        visibility = if (enabled) View.VISIBLE else View.GONE
        buttons.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        feedbackArea.translationY = if (controlsVisible) -context.dp(48).toFloat() else 0f
        val description = if (controlsVisible) R.string.gesture_hide_controls else R.string.gesture_show_controls
        contentDescription = context.getString(description)
        val player = surface.player.takeIf { enabled }
        if (player !== listening) {
            listening?.removeListener(listener)
            listening = player
            player?.addListener(listener)
            player?.let(::updateButtons)
        }
        if (!enabled) cancelGestures()
    }

    private fun updateButtons(player: Player) {
        val showPlay = Util.shouldShowPlayButton(player)
        playPause.setImageResource(if (showPlay) OneUIIcons.drawable.ic_oui_control_play else OneUIIcons.drawable.ic_oui_control_pause)
        val description = if (showPlay) androidx.media3.ui.R.string.exo_controls_play_description
            else androidx.media3.ui.R.string.exo_controls_pause_description
        playPause.contentDescription = context.getString(description)
        playPause.isEnabled = Util.shouldEnablePlayPauseButton(player)
        back.isEnabled = player.isCommandAvailable(Player.COMMAND_SEEK_BACK)
        forward.isEnabled = player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)
        for (button in listOf(back, playPause, forward)) button.alpha = if (button.isEnabled) 1f else 0.4f
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downRawX = event.rawX
                downRawY = event.rawY
                moved = false
                multiTouch = false
                continuesTap = lastUp >= 0 && event.eventTime - lastUp <= tapInterval &&
                    side(downX) == side(lastX) && abs(downX - lastX) <= config.scaledDoubleTapSlop &&
                    abs(downY - lastY) <= config.scaledDoubleTapSlop
                if (continuesTap) removeCallbacks(confirmTap)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                cancelGestures()
            }
            MotionEvent.ACTION_MOVE -> {
                // Allow a deliberate downward swipe to start on a centered playback button too.
                if (trackPull(event)) return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val dx = abs(event.rawX - downRawX)
        val dy = event.rawY - downRawY
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                cancelGestures()
            }
            MotionEvent.ACTION_MOVE -> {
                if (maxOf(dx, abs(dy)) > config.scaledTouchSlop) {
                    moved = true
                    removeCallbacks(confirmTap)
                }
                trackPull(event)
            }
            MotionEvent.ACTION_CANCEL -> cancelGestures()
            MotionEvent.ACTION_UP -> {
                if (!multiTouch && dy >= swipeDistance && dy > 1.5f * dx) {
                    pulling = false
                    cancelGestures()
                    minimize()
                } else if (!multiTouch && !moved && maxOf(dx, abs(dy)) <= config.scaledTouchSlop &&
                    event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()) {
                    onTap(event)
                } else {
                    cancelGestures()
                }
            }
        }
        return true
    }

    private fun trackPull(event: MotionEvent): Boolean {
        if (multiTouch) return false
        val dy = event.rawY - downRawY
        val dx = abs(event.rawX - downRawX)
        if (!pulling && (dy <= config.scaledTouchSlop || dy <= 1.5f * dx)) return false
        moved = true
        pulling = true
        removeCallbacks(confirmTap)
        onPull(dy.coerceAtLeast(0f))
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (surface.isControllerFullyVisible) surface.hideController() else surface.showController()
        return true
    }

    private fun side(x: Float): Int = when {
        x < width / 3f -> -1
        x > width * 2f / 3f -> 1
        else -> 0
    }

    private fun onTap(event: MotionEvent) {
        removeCallbacks(confirmTap)
        val direction = side(downX)
        if (direction == 0) {
            lastUp = -1L
            performClick()
            return
        }
        lastUp = event.eventTime
        lastX = downX
        lastY = downY
        if (!continuesTap) {
            seekCount = 0
            postDelayed(confirmTap, tapInterval)
            return
        }
        if (!seek(direction)) return
        seekCount++
        val label = if (direction < 0) R.string.gesture_seconds_back else R.string.gesture_seconds_forward
        val player = surface.player ?: return
        val step = if (direction < 0) player.seekBackIncrement else player.seekForwardIncrement
        feedback.text = context.getString(label, seekCount * step / 1000)
        feedbackArea.layoutParams = LayoutParams(width / 2, WRAP_CONTENT, Gravity.CENTER_VERTICAL or
            if (direction < 0) Gravity.LEFT else Gravity.RIGHT)
        edgeHighlight.flash(direction)
        feedback.visibility = View.VISIBLE
        removeCallbacks(hideFeedback)
        postDelayed(hideFeedback, 650)
    }

    private fun seek(direction: Int): Boolean {
        val player = surface.player ?: return false
        val command = if (direction < 0) Player.COMMAND_SEEK_BACK else Player.COMMAND_SEEK_FORWARD
        if (!player.isCommandAvailable(command)) return false
        if (direction < 0) player.seekBack() else player.seekForward()
        return true
    }

    private fun cancelGestures() {
        if (pulling) {
            pulling = false
            onCancelPull()
        }
        removeCallbacks(confirmTap)
        removeCallbacks(hideFeedback)
        feedback.visibility = View.GONE
        edgeHighlight.clear()
        lastUp = -1L
        seekCount = 0
    }

    fun dispose() {
        cancelGestures()
        listening?.removeListener(listener)
        listening = null
        surface.overlayFrameLayout?.removeView(this)
    }
}

/** One fixed arc per physical edge. Repeated seeks brighten the same area. */
private class SeekEdgeHighlight(context: Context) : View(context) {
    private val arc = Path()
    private val area = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(115, 115, 115) }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(195, 195, 195)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }
    private var right = false
    private val hide = Runnable { visibility = INVISIBLE }
    private val fade = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 300L
        interpolator = AccelerateInterpolator()
        addUpdateListener { setIntensity(it.animatedValue as Float) }
        doOnEnd { visibility = INVISIBLE }
    }

    init {
        id = R.id.playback_seek_edge
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = INVISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        arc.rewind()
        arc.moveTo(w * .34f, 0f)
        arc.quadTo(w * .56f, h * .5f, w * .34f, h.toFloat())
        area.set(arc)
        area.lineTo(0f, h.toFloat())
        area.lineTo(0f, 0f)
        area.close()
    }

    fun flash(direction: Int) {
        fade.cancel()
        removeCallbacks(hide)
        right = direction > 0
        setIntensity(1f)
        visibility = VISIBLE
        if (ValueAnimator.areAnimatorsEnabled()) fade.start() else postDelayed(hide, 220L)
    }

    private fun setIntensity(value: Float) {
        fillPaint.alpha = (56 * value).toInt()
        arcPaint.alpha = (105 * value).toInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        if (right) canvas.scale(-1f, 1f, width / 2f, 0f)
        canvas.drawPath(area, fillPaint)
        canvas.drawPath(arc, arcPaint)
        canvas.restoreToCount(checkpoint)
    }

    fun clear() {
        fade.cancel()
        removeCallbacks(hide)
        visibility = INVISIBLE
    }
}
