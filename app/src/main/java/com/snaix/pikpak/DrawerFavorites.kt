package com.snaix.pikpak

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Rect
import android.graphics.Picture
import android.graphics.drawable.PictureDrawable
import android.transition.Fade
import android.transition.Slide
import android.transition.Transition
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.transition.Visibility
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewStub
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.SubMenuBuilder
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.animation.doOnEnd
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import dev.oneuiproject.oneui.layout.NavDrawerLayout
import dev.oneuiproject.oneui.navigation.widget.DrawerNavigationView
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong

@SuppressLint("RestrictedApi", "PrivateResource")
internal class DrawerFavorites(
    private val activity: AppCompatActivity,
    private val root: NavDrawerLayout,
    private val drawer: DrawerNavigationView,
    private val model: CloudModel,
) {
    // DrawerNavigationView exposes findMenuItem but no public mutable Menu accessor.
    private val menu = (drawer.findMenuItem(R.id.nav_favorites_menu)!!.subMenu as SubMenuBuilder).parentMenu as MenuBuilder
    private val header = checkNotNull(drawer.findMenuItem(R.id.nav_stars_header))
    private val all = checkNotNull(drawer.findMenuItem(R.id.nav_favorites))
    private val prefs = model.getApplication<Application>().appearancePreferences()
    private val list = drawer.getChildAt(0) as RecyclerView
    private var expanded = prefs.getBoolean(EXPANDED_PREF, true)
    private var showAllEntry = true
    private val rows = linkedMapOf<Int, Pair<CloudFile, MenuItem>>()
    private val icons = FileCategory.entries.associateWith { activity.icon(it.symbol) }
    private var headerView: View? = null
    private var chevron: ImageView? = null
    private var angle = if (expanded) 180f else 0f
    private var arrowMotion: ValueAnimator? = null
    private var footerMotion: ValueAnimator? = null
    private var startFooterDistance = 0f
    private var footerProgress = 1f
    private var toggleNumber = 0
    private var clippingRows = false
    private var movingFooter: View? = null
    private var outgoing: ImageView? = null
    private val motionRows = mutableSetOf<View>()
    private val position = IntArray(2)
    private val clip = Rect()
    private val bindHeader = ViewTreeObserver.OnPreDrawListener {
        val current = drawer.findViewById<View>(R.id.nav_stars_header)
        if (current !== headerView) {
            // A normal SESL menu holder can later be recycled for a different destination.
            chevron?.visibility = View.GONE
            headerView?.let { ViewCompat.setStateDescription(it, null) }
            headerView = current
            chevron = current?.let { row ->
                row.findViewById<ImageView>(R.id.drawer_stars_chevron)
                    ?: (row.findViewById<ViewStub>(dev.oneuiproject.oneui.design.R.id.drawer_menu_expand_button_stub)
                        .inflate() as ImageView).apply {
                        id = R.id.drawer_stars_chevron
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
            }
            applyDisclosureState()
        }
        chevron?.showWhen(!root.isLargeScreenMode || root.drawerOffset > 0f)
        if (clippingRows) clipRowsAboveFooter()
        true
    }

    init {
        header.icon = activity.icon(Symbol.Star)
        drawer.viewTreeObserver.addOnPreDrawListener(bindHeader)
    }

    private val observer = activity.lifecycleScope.launch {
        model.observe {
            Triple(model.drawerFavorites, model.drawerFavoritesLoaded, model.drawerFavoritesError != null)
        }.collect { (files, loaded, failed) ->
            showAllEntry = files.isNotEmpty() || !loaded || failed
            menu.stopDispatchingItemsChanged()
            try {
                val shared = rows.values.asSequence().map { it.first }.zip(files.asSequence())
                    .takeWhile { (old, next) -> old == next }.count()
                rows.keys.drop(shared).forEach { id -> menu.removeItem(id); rows.remove(id) }
                files.drop(shared).forEach { file ->
                    val id = View.generateViewId()
                    val item = menu.add(R.id.nav_stars, id, 31, file.name).apply {
                        icon = icons[file.category]
                        contentDescription = activity.getString(R.string.drawer_starred_item, file.name, file.category.label)
                        tooltipText = file.name
                        isVisible = expanded
                        setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    }
                    rows[id] = file to item
                }
                all.isVisible = expanded && showAllEntry
            } finally {
                menu.startDispatchingItemsChanged()
            }
        }
    }

    private fun applyDisclosureState() {
        chevron?.rotation = angle
        headerView?.let { row ->
            ViewCompat.setStateDescription(row, activity.getString(
                if (expanded) R.string.drawer_stars_expanded else R.string.drawer_stars_collapsed
            ))
        }
    }

    private fun animateArrow() {
        arrowMotion?.cancel()
        val target = if (expanded) 180f else 0f
        if (!canAnimate() || angle == target) {
            angle = target
            applyDisclosureState()
            return
        }
        applyDisclosureState()
        arrowMotion = ValueAnimator.ofFloat(angle, target).apply {
            duration = (150L * abs(target - angle) / 180f).roundToLong()
            interpolator = ARROW_CURVE
            addUpdateListener {
                angle = it.animatedValue as Float
                chevron?.rotation = angle
            }
            start()
        }
    }

    private fun canAnimate() = ValueAnimator.areAnimatorsEnabled() && list.isLaidOut &&
        list.isShown && root.drawerOffset > 0f && headerView?.isAttachedToWindow == true

    private fun clipRowsAboveFooter() {
        // Disappearing rows can remain in Android's transition overlay after the menu rebind.
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index)
            if (row.id == all.itemId || row.id in rows) motionRows.add(row)
        }
        list.findViewById<View>(R.id.drawer_quota_host)?.let { host ->
            movingFooter = host
            // RecyclerView can rebind a frame after the menu notification. Apply the
            // visual position *after each layout* so the new endpoint never flashes.
            headerView?.let { anchor ->
                host.translationY = (startFooterDistance - (host.top - anchor.top)) * (1f - footerProgress)
            }
        }
        val footer = movingFooter?.takeIf { it.isAttachedToWindow && it.height > 0 }
        footer?.getLocationInWindow(position)
        val boundary = if (footer != null) position[1] else Int.MAX_VALUE
        motionRows.forEach { row ->
            if (footer == null || (row.id != all.itemId && row.id !in rows)) {
                row.clipBounds = null
            } else {
                row.getLocationInWindow(position)
                val height = (boundary - position[1]).coerceIn(0, row.height)
                if (height < row.height) {
                    clip.set(0, 0, row.width, height)
                    row.clipBounds = clip
                } else row.clipBounds = null
            }
        }
        outgoing?.let { row ->
            row.getLocationInWindow(position)
            clip.set(0, 0, row.width, if (footer == null) row.height else (boundary - position[1]).coerceIn(0, row.height))
            row.clipBounds = clip
        }
    }

    private fun clearRowClips() {
        clippingRows = false
        list.findViewById<View>(R.id.drawer_quota_host)?.translationY = 0f
        movingFooter = null
        motionRows.forEach { it.clipBounds = null }
        motionRows.clear()
    }

    private fun removeOutgoing() {
        outgoing?.let {
            it.animate().cancel()
            list.overlay.remove(it)
        }
        outgoing = null
    }

    private fun fadeOutVisibleRows() {
        // The SESL menu immediately reuses the removed holders for other destinations.
        // Record just the visible strip before rebinding, including its current opacity
        // if the user reverses direction. Never render a recycled holder into the overlay.
        val bounds = Rect()
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index)
            if (row.id == all.itemId || row.id in rows) bounds.union(row.left, row.top, row.right, row.bottom)
        }
        val footer = list.findViewById<View>(R.id.drawer_quota_host)
        val bottom = if (footer != null && footer.height > 0) minOf(list.height, footer.y.toInt()) else list.height
        if (!bounds.intersect(0, 0, list.width, bottom) || bounds.isEmpty) return
        val picture = Picture()
        val canvas = picture.beginRecording(bounds.width(), bounds.height())
        canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
        list.draw(canvas)
        picture.endRecording()
        val copy = ImageView(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageDrawable(PictureDrawable(picture))
            layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        outgoing = copy
        list.overlay.add(copy)
        copy.animate().alpha(0f).setDuration(120).setInterpolator(ROW_CURVE).withEndAction {
            list.overlay.remove(copy)
            if (outgoing === copy) outgoing = null
        }.start()
    }

    private val updateVisibility = Runnable {
        val menuRow = headerView?.takeIf { canAnimate() && (rows.isNotEmpty() || showAllEntry) }
        removeOutgoing()
        if (menuRow != null) {
            val host = list.findViewById<View>(R.id.drawer_quota_host)
            startFooterDistance = (host?.y?.coerceAtMost(list.height.toFloat()) ?: list.height.toFloat()) - menuRow.top
            footerMotion?.cancel()
            footerProgress = 0f
            clippingRows = true
            clipRowsAboveFooter()
            if (!expanded) fadeOutVisibleRows()
            val generation = toggleNumber
            // SESL refreshes the entire menu. Match the real rows by menu ID across rebinds.
            val transition = TransitionSet().apply {
                setMatchOrder(Transition.MATCH_ID)
                if (expanded) addTransition(Fade(Fade.IN).apply { addTarget(menuRow.javaClass); duration = 120 })
                // If the expanded list pushes the footer off screen, animate its departing copy.
                addTransition(Slide(Gravity.BOTTOM).apply {
                    mode = Visibility.MODE_OUT
                    addTarget(R.id.drawer_quota_host)
                    duration = 330
                })
                interpolator = ROW_CURVE
            }
            TransitionManager.beginDelayedTransition(list, transition)
            footerMotion = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 330
                interpolator = ROW_CURVE
                addUpdateListener {
                    footerProgress = it.animatedValue as Float
                    list.invalidate()
                }
                doOnEnd {
                    if (generation == toggleNumber) {
                        clearRowClips()
                        if (expanded) list.post { if (expanded && generation == toggleNumber) revealFirstFavorite() }
                    }
                }
                start()
            }
        } else {
            footerMotion?.cancel()
            clearRowClips()
        }
        menu.stopDispatchingItemsChanged()
        try {
            rows.values.forEach { (_, row) -> row.isVisible = expanded }
            all.isVisible = expanded && showAllEntry
        } finally {
            menu.startDispatchingItemsChanged()
        }
        if (expanded && menuRow == null) revealFirstFavorite()
    }

    private fun revealFirstFavorite() {
        val firstId = rows.keys.firstOrNull() ?: return
        list.doOnPreDraw {
            if (!expanded || list.scrollState != RecyclerView.SCROLL_STATE_IDLE || root.drawerOffset <= 0f) return@doOnPreDraw
            val headerRow = list.findViewById<View>(header.itemId) ?: return@doOnPreDraw
            val manager = list.layoutManager ?: return@doOnPreDraw
            val viewport = Rect()
            if (!list.getLocalVisibleRect(viewport)) return@doOnPreDraw
            val bottom = minOf(viewport.bottom, list.height - list.paddingBottom)
            val first = list.findViewById<View>(firstId)
            if (first != null) {
                val clipped = manager.getDecoratedBottom(first) - bottom
                if (clipped > 0) {
                    if (ValueAnimator.areAnimatorsEnabled()) list.smoothScrollBy(0, clipped) else list.scrollBy(0, clipped)
                }
            } else {
                // The next row is below the RecyclerView's bound range. Its layout manager
                // scrolls only until the target enters at the bottom of the viewport.
                val headerPosition = list.getChildAdapterPosition(headerRow)
                if (headerPosition != RecyclerView.NO_POSITION) {
                    if (ValueAnimator.areAnimatorsEnabled()) list.smoothScrollToPosition(headerPosition + 1)
                    else list.scrollToPosition(headerPosition + 1)
                }
            }
        }
        list.postInvalidateOnAnimation()
    }

    fun handle(item: MenuItem, open: (CloudFile) -> Unit): Boolean {
        if (item.itemId != header.itemId) {
            if (item.itemId == all.itemId) return !expanded || !all.isVisible
            val id = rows[item.itemId]?.first?.id ?: return false
            if (expanded && item.isVisible && model.signedIn) {
                model.drawerFavorites.firstOrNull { it.id == id }?.let(open)
            }
            return true
        }
        expanded = !expanded
        toggleNumber++
        prefs.edit().putBoolean(EXPANDED_PREF, expanded).apply()
        animateArrow()
        // The SESL presenter suppresses its own adapter updates during the click callback.
        drawer.removeCallbacks(updateVisibility)
        drawer.post(updateVisibility)
        return true
    }

    fun dispose() {
        toggleNumber++
        drawer.removeCallbacks(updateVisibility)
        arrowMotion?.cancel()
        footerMotion?.cancel()
        TransitionManager.endTransitions(list)
        removeOutgoing()
        clearRowClips()
        drawer.viewTreeObserver.removeOnPreDrawListener(bindHeader)
        observer.cancel()
    }

    private companion object {
        const val EXPANDED_PREF = "drawer_favorites_expanded"
        val ARROW_CURVE = PathInterpolator(0.33f, 0f, 0.4f, 1f)
        val ROW_CURVE = AccelerateDecelerateInterpolator()
    }
}
