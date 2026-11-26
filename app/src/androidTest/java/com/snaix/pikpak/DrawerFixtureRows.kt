package com.snaix.pikpak

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.core.view.descendants
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import dev.oneuiproject.oneui.navigation.widget.DrawerNavigationView

internal fun MainActivity.fixtureDrawer(): DrawerNavigationView = findViewById(R.id.workspace_drawer)

internal fun DrawerNavigationView.fixtureItem(label: CharSequence): View? = descendants
    .filterIsInstance<TextView>()
    .firstOrNull { it.text.toString() == label.toString() && it.isShown }
    ?.let { text -> generateSequence<View>(text) { it.parent as? View }.firstOrNull(View::isClickable) }

internal fun revealDrawerItem(
    screen: FixtureActivity<MainActivity>,
    label: CharSequence,
    locate: (DrawerNavigationView) -> View? = { it.fixtureItem(label) },
): Int {
    val instrument = InstrumentationRegistry.getInstrumentation()
    fun position() = screen.onActivity { activity ->
        val drawer = activity.fixtureDrawer()
        val row = locate(drawer) ?: return@onActivity null
        val list = drawer.getChildAt(0) as RecyclerView
        val holder = list.findContainingItemView(row) ?: return@onActivity null
        list.getChildAdapterPosition(holder).takeUnless { it == RecyclerView.NO_POSITION }
    }

    var target = position()
    if (target == null) {
        val count = screen.onActivity { (it.fixtureDrawer().getChildAt(0) as RecyclerView).adapter?.itemCount ?: 0 }
        for (index in 0 until count) {
            screen.onActivity { (it.fixtureDrawer().getChildAt(0) as RecyclerView).scrollToPosition(index) }
            SystemClock.sleep(35)
            instrument.waitForIdleSync()
            target = position()
            if (target != null) break
        }
    }
    return checkNotNull(target) { "Drawer item not attached after scrolling: $label" }.also {
        screen.onActivity { activity ->
            val row = checkNotNull(locate(activity.fixtureDrawer()))
            row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
        }
        val end = SystemClock.elapsedRealtime() + 2_000
        while (!screen.onActivity { activity -> locate(activity.fixtureDrawer())?.getGlobalVisibleRect(Rect()) == true }) {
            check(SystemClock.elapsedRealtime() < end) { "Drawer item outside the visible area: $label" }
            SystemClock.sleep(40)
        }
    }
}
