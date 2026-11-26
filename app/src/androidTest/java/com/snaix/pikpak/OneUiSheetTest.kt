package com.snaix.pikpak

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.textfield.TextInputLayout
import dev.oneuiproject.oneui.layout.NavDrawerLayout
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OneUiSheetTest {
    @Test
    fun cloudAddOpensTheOneUiActionSheet() {
        val testContext = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(testContext, MainActivity::class.java)
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> true
                "files" -> JSONArray()
                else -> error("Unexpected fixture operation: $operation")
            }
        }
        launchFixtureActivity(MainActivity::class.java, intent).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity, viewModelFactory {
                    initializer { CloudModel(activity.application, gateway) }
                })["sheet-fixture", CloudModel::class.java]
                activity.showFixture(model)
            }
            expect(scenario) { addButton(it) != null }
            scenario.onActivity { assertTrue(checkNotNull(addButton(it)).performClick()) }

            expect(scenario) { activity ->
                val sheet = activity.supportFragmentManager.findFragmentByTag(CloudActionSheet.TAG) as? CloudActionSheet
                    ?: return@expect false
                val content = sheet.view ?: return@expect false
                val window = sheet.dialog?.window?.decorView ?: return@expect false
                content.findViewById<BottomSheetDragHandleView>(R.id.cloud_sheet_drag_handle).isShown &&
                    content.findViewById<ListView>(R.id.cloud_sheet_actions).childCount > 0 &&
                    window.width > 0 && window.height > 0
            }
            val window = scenario.onActivity { activity ->
                val sheet = activity.supportFragmentManager.findFragmentByTag(CloudActionSheet.TAG) as CloudActionSheet
                val content = checkNotNull(sheet.view)
                val title = content.findViewById<TextView>(R.id.cloud_sheet_title)
                assertEquals("添加到 云盘", title.text.toString())
                val frame = content.rootView
                val panel = frame.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                val minimumInset = activity.resources.getDimensionPixelSize(R.dimen.cloud_sheet_edge_inset)
                val leftInset = positionInWindow(panel) - positionInWindow(frame)
                val rightInset = frame.width - leftInset - panel.width
                assertTrue("Sheet left inset was $leftInset px", leftInset >= minimumInset)
                assertTrue("Sheet right inset was $rightInset px", rightInset >= minimumInset)
                val choices = content.findViewById<ListView>(R.id.cloud_sheet_actions)
                assertEquals(
                    listOf("新建文件夹", "上传文件", "上传文件夹", "新建云下载", "打开分享链接"),
                    List(choices.count) { (choices.adapter.getItem(it) as Choice).title },
                )
                for (index in 0 until choices.count) {
                    val row = choices.adapter.getView(index, null, choices) as TextView
                    assertTrue(row.compoundDrawablesRelative[0] != null)
                    val listMargins = choices.layoutParams as ViewGroup.MarginLayoutParams
                    assertEquals(title.paddingStart, listMargins.marginStart + row.paddingStart)
                    assertEquals(title.paddingEnd, listMargins.marginEnd + row.paddingEnd)
                }
                checkNotNull(sheet.dialog?.window)
            }
            SystemClock.sleep(300)
            capture(scenario, window, File(testContext.cacheDir, "oneui-add-sheet.png"))
            scenario.onActivity { activity ->
                val sheet = activity.supportFragmentManager.findFragmentByTag(CloudActionSheet.TAG) as CloudActionSheet
                val choices = checkNotNull(sheet.view).findViewById<ListView>(R.id.cloud_sheet_actions)
                assertTrue(choices.performItemClick(choices.getChildAt(0), 0, choices.adapter.getItemId(0)))
            }
            expect(scenario) { activity ->
                activity.supportFragmentManager.findFragmentByTag(CloudActionSheet.TAG) == null &&
                    (Build.VERSION.SDK_INT < 29 || WindowInspector.getGlobalWindowViews().any { window ->
                        findView(window) { it is TextInputLayout && it.hint == "文件夹名称" } != null
                    })
            }
        }
    }

    private fun positionInWindow(view: View): Int = IntArray(2).also(view::getLocationInWindow)[0]

    private fun addButton(activity: MainActivity): View? {
        val workspace = activity.findViewById<NavDrawerLayout>(R.id.workspace_toolbar_layout) ?: return null
        return findView(workspace.toolbar) { it.isShown && it.isClickable && it.contentDescription == "添加" }
    }

    private fun findView(view: View, matches: (View) -> Boolean): View? {
        if (matches(view)) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findView(view.getChildAt(index), matches)?.let { return it }
        }
        return null
    }

    private fun expect(scenario: FixtureActivity<MainActivity>, ready: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            if (scenario.onActivity(ready)) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("One UI add sheet state did not render")
    }

    private fun capture(scenario: FixtureActivity<MainActivity>, window: Window, target: File) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val physicalId = InstrumentationRegistry.getArguments().getString("captureDisplayId")?.toULongOrNull()
        val automation = instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val screen = physicalId?.let { id ->
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p -d $id"))
                .use(BitmapFactory::decodeStream)
        }
        val screenshot = screen ?: if (Build.VERSION.SDK_INT >= 34) automation.takeScreenshot(window) else null
        val bitmap = screenshot ?: scenario.onActivity {
            val view = window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        try {
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
