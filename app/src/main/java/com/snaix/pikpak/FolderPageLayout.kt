package com.snaix.pikpak

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToLong

/** The child moves across the full width, revealing its parent a short distance behind it. */
internal class FolderPageLayout(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {
    private var outgoing: RecyclerView? = null
    private var outgoingRoute: WorkspaceLocation? = null
    private var incoming: RecyclerView? = null
    private var entering = false
    private var childOffset = 0f
    private var animator: ValueAnimator? = null
    private val easing = PathInterpolator(0.2f, 0f, 0.2f, 1f)

    fun navigate(from: WorkspaceLocation?, to: WorkspaceLocation, createList: () -> RecyclerView) {
        val forward = when {
            from == null -> null
            to.parent == from && (to.folder != null || to.page == Page.SharedFiles) -> true
            from.parent == to && (from.folder != null || from.page == Page.SharedFiles) -> false
            else -> null
        }
        // Back during a push (or tapping the same child during a pop) starts at the visible edge.
        val start = if (outgoingRoute == to && forward != null && forward != entering) childOffset
            else if (forward == true) 1f else 0f
        settle()
        if (forward == null || !isShown || !isLaidOut || width == 0 || !ValueAnimator.areAnimatorsEnabled()) return
        val target = if (forward) 0f else 1f
        if (start == target) return

        val old = getChildAt(0) as RecyclerView
        old.stopScroll()
        old.clearFocus()
        old.id = View.NO_ID
        old.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        old.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        val next = createList()
        addView(next, if (forward) 1 else 0)
        outgoing = old
        outgoingRoute = from
        incoming = next
        entering = forward
        position(start)
        next.doOnPreDraw {
            if (outgoing === old) {
                animator = ValueAnimator.ofFloat(start, target).apply {
                    duration = (300 * abs(target - start)).roundToLong()
                    interpolator = easing
                    addUpdateListener { position(it.animatedValue as Float) }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) = settle()
                    })
                }.also { it.start() }
            }
        }
    }

    private val endSign get() = if (layoutDirection == LAYOUT_DIRECTION_RTL) -1f else 1f

    private fun position(offset: Float) {
        childOffset = offset
        val distance = width * endSign
        val child = if (entering) incoming else outgoing
        val parent = if (entering) outgoing else incoming
        child?.translationX = distance * offset
        parent?.translationX = -distance * 0.28f * (1f - offset)
        invalidate()
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val parentPage = if (entering) outgoing else incoming
        if (child !== parentPage) return super.drawChild(canvas, child, drawingTime)
        // The One UI lists are transparent. Clip the parent to the exposed strip so rows
        // and thumbnails from the two directories can never show through each other.
        val save = canvas.save()
        val exposed = width * childOffset
        if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.clipRect(width - exposed, 0f, width.toFloat(), height.toFloat())
        else canvas.clipRect(0f, 0f, exposed, height.toFloat())
        return try { super.drawChild(canvas, child, drawingTime) } finally { canvas.restoreToCount(save) }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (outgoing != null && event.actionMasked == MotionEvent.ACTION_DOWN) {
            val fromStart = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - event.x else event.x
            val touchesChild = fromStart >= width * childOffset
            if (touchesChild != entering) return true
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (outgoing != null) position(childOffset)
    }

    fun settle() {
        animator?.apply { removeAllListeners(); cancel() }
        animator = null
        incoming?.translationX = 0f
        outgoing?.let { old ->
            removeView(old)
            old.adapter = null
        }
        outgoing = null
        incoming = null
        outgoingRoute = null
    }

    override fun onDetachedFromWindow() {
        settle()
        super.onDetachedFromWindow()
    }
}
