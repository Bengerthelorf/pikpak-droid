package com.snaix.pikpak

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.DragEvent
import android.view.InputDevice
import android.view.View
import android.view.Gravity
import android.view.MotionEvent
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.filters.SdkSuppress
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@SdkSuppress(minSdkVersion = 29)
class IncomingLinkActivityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun sharedFolderLinkKeepsItsAnchorOnRefreshAndBack() = verifySharedFolderLink(supported = true)

    @Test
    fun sharedFolderLinkRequiresANewerCoreWithoutOpeningTheRoot() = verifySharedFolderLink(supported = false)

    private fun verifySharedFolderLink(supported: Boolean) {
        val app = instrumentation.targetContext.applicationContext as Application
        val id = UUID.randomUUID().toString()
        val active = AtomicReference<MainActivity>()
        val handle = "https://mypikpak.com/s/VOIQ0QFrIMiPE8EFPZYJKKxpo1/AAAeZ9plmSBi6NG9KffhrhCAo1_VOI"
        val url = handle.replace("mypikpak.com", "www.mypikpak.com") + "?pass_code=a%2Bb#fragment"
        val openCalls = AtomicInteger()
        val anchoredReads = AtomicInteger()
        val activityFocused = CountDownLatch(1)
        val child = JSONObject().put("id", "child").put("name", "Child").put("kind", "Folder")
        val fallback = FixtureGateway()
        val gateway = object : Gateway {
            override val supportsShareBrowsing = supported
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? {
                val input = args.toMap()
                return when (operation) {
                    "init" -> {
                        // The incoming dialog takes focus immediately after session initialization.
                        check(activityFocused.await(15, TimeUnit.SECONDS))
                        fallback.call(operation, *args)
                    }
                    "share_open" -> {
                        check(input["url"] == url && input["code"] == "a+b")
                        openCalls.incrementAndGet()
                        JSONObject().put("share", handle).put("token", "fixture").put("files", JSONArray().put(child))
                    }
                    "share_folder" -> {
                        check(input["share"] == handle && input["token"] == "fixture")
                        when (input["parent"]) {
                            "" -> {
                                anchoredReads.incrementAndGet()
                                JSONArray().put(child)
                            }
                            "child" -> JSONArray().put(JSONObject().put("id", "leaf").put("name", "Leaf").put("kind", "File"))
                            else -> error("Unexpected share parent")
                        }
                    }
                    else -> fallback.call(operation, *args)
                }
            }
        }
        WorkspaceFixtureApplication(app).use { fixture ->
            fixture.appearancePreferences().edit().putBoolean(DRIVE_FOLDER_STATS, false).commit()
            val callbacks = fixtureCallbacks(id, active) { CloudModel(fixture, gateway) }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url), app, MainActivity::class.java).putExtra("fixtureId", id)
            main { app.registerActivityLifecycleCallbacks(callbacks) }
            try {
                launchFixtureActivity(MainActivity::class.java, intent).use {
                    activityFocused.countDown()
                    lateinit var model: CloudModel
                    fun confirmation() = WindowInspector.getGlobalWindowViews().firstOrNull { view ->
                        view.display?.displayId == active.get()?.window?.decorView?.display?.displayId &&
                            view.findViewById<EditText>(R.id.incoming_link)?.text?.toString() == url
                    }
                    await { confirmation() != null }
                    main {
                        model = active.get().model
                        val dialog = checkNotNull(confirmation())
                        assertEquals("a+b", dialog.findViewById<EditText>(R.id.incoming_description).text.toString())
                        val confirm = dialog.findViewById<View>(android.R.id.button1)
                        assertEquals(supported, confirm.isEnabled)
                        val target = if (supported) confirm else dialog.findViewById<View>(android.R.id.button2)
                        check(target.performClick())
                    }
                    await { confirmation() == null }
                    if (supported) {
                        await { model.entries.singleOrNull()?.id == "child" }
                        main { model.refresh() }
                        await { anchoredReads.get() == 1 && !model.loading }
                        main { model.openFolder(model.entries.single()) }
                        await { model.entries.singleOrNull()?.id == "leaf" }
                        main { check(model.back()) }
                        await { anchoredReads.get() == 2 && model.entries.singleOrNull()?.id == "child" }
                    }
                    assertEquals(if (supported) 1 else 0, openCalls.get())
                }
            } finally {
                activityFocused.countDown()
                main { app.unregisterActivityLifecycleCallbacks(callbacks) }
            }
        }
    }

    @Test
    fun coldShareAndWarmMagnetOpenOneEditableConfirmationAndCancelDoesNotSubmit() {
        val application = instrumentation.targetContext.applicationContext as Application
        val id = UUID.randomUUID().toString()
        val prefs = "incoming-fixture-$id"
        val history = TransferHistory(application.getSharedPreferences(prefs, Application.MODE_PRIVATE))
        val active = AtomicReference<MainActivity>()
        val release = CountDownLatch(1)
        val submissions = AtomicInteger()
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> false
                "login" -> null
                "files" -> JSONArray()
                "fixture_busy" -> { check(release.await(15, TimeUnit.SECONDS)); null }
                "offline" -> { submissions.incrementAndGet(); null }
                else -> error("Unexpected operation $operation")
            }
        }
        val callbacks = fixtureCallbacks(id, active) {
            CloudModel(application, gateway, transferHistory = history)
        }
        val cold = "magnet:?xt=urn:btih:" + "a".repeat(40)
        val warm = "magnet:?xt=urn:btih:" + "b".repeat(40)
        val intent = Intent(application, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Found a link\n$cold").putExtra("fixtureId", id)
        fun dialog(): View? = WindowInspector.getGlobalWindowViews().firstOrNull {
            it.display?.displayId == active.get().window.decorView.display?.displayId &&
                it.findViewById<EditText>(R.id.incoming_link)?.isShown == true
        }
        fun field(target: Int = R.id.incoming_link): EditText? = dialog()?.findViewById(target)
        fun cancel() = main { check(checkNotNull(dialog()).findViewById<View>(android.R.id.button2).performClick()) }
        fun deliverWarm(tag: Int) = main {
            val activity = active.get()
            val view = Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(warm)).putExtra("fixtureId", id).putExtra("delivery", tag)
            activity.startActivity(view)
        }
        main { application.registerActivityLifecycleCallbacks(callbacks) }
        try {
            launchFixtureActivity(MainActivity::class.java, intent).use {
                await { active.get()?.findViewById<EditText>(R.id.login_email)?.isShown == true }
                main {
                    assertNull(dialog())
                    val email = active.get().findViewById<EditText>(R.id.login_email)
                    val password = active.get().findViewById<EditText>(R.id.login_password)
                    val hints = email.autofillHints.orEmpty()
                    assertTrue(View.AUTOFILL_HINT_USERNAME in hints && View.AUTOFILL_HINT_EMAIL_ADDRESS in hints)
                    assertTrue(View.AUTOFILL_HINT_PASSWORD in password.autofillHints.orEmpty())
                    active.get().model.login("$id@example.invalid", "fixture") {}
                }
                await { field()?.text?.toString() == cold }
                val first = active.get()
                main {
                    checkNotNull(field(R.id.incoming_description)).setText("Edited name")
                    first.recreate()
                }
                await {
                    active.get() !== first && field()?.text?.toString() == cold &&
                        field(R.id.incoming_description)?.text?.toString() == "Edited name"
                }
                cancel()
                await { dialog() == null }
                main { active.get().model.command("Holding", "fixture_busy", refresh = false) }
                deliverWarm(1)
                await { active.get().intent.getIntExtra("delivery", 0) == 1 }
                main { assertNull(dialog()) }
                release.countDown()
                await { field()?.text?.toString() == warm }
                deliverWarm(2)
                await { active.get().intent.getIntExtra("delivery", 0) == 2 }
                cancel()
                await { dialog() == null }
                val second = active.get()
                main { second.recreate() }
                await { active.get() !== second && active.get().hasWindowFocus() && active.get().model.signedIn }
                main { assertNull(dialog()) }
                assertEquals(0, submissions.get())
            }
        } finally {
            release.countDown()
            main {
                active.get()?.finish()
                application.unregisterActivityLifecycleCallbacks(callbacks)
            }
            application.deleteSharedPreferences(prefs)
        }
    }

    @Test
    @LargeTest
    @SdkSuppress(minSdkVersion = 34)
    fun systemDragAcrossWindowsOntoLoginFieldQueuesTheMagnet() {
        val arguments = InstrumentationRegistry.getArguments()
        val displayId = arguments.getString("displayId")?.toIntOrNull() ?: 0
        assumeTrue("Requires an explicit secondary fixture display", displayId > 0)
        val driver = arguments.getString("dragDriver")
        assumeTrue("Enable system gesture injection with -e dragDriver host or -e dragDriver instrumentation", driver == "host" || driver == "instrumentation")
        val app = instrumentation.targetContext.applicationContext as Application
        val id = UUID.randomUUID().toString()
        val prefs = "drag-fixture-$id"
        val history = TransferHistory(app.getSharedPreferences(prefs, Application.MODE_PRIVATE))
        val submissions = AtomicInteger()
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> false
                "login" -> null
                "files" -> JSONArray()
                "offline" -> { submissions.incrementAndGet(); null }
                else -> error("Unexpected operation $operation")
            }
        }
        val active = AtomicReference<MainActivity>()
        val callbacks = fixtureCallbacks(id, active) { CloudModel(app, gateway, transferHistory = history) }
        val magnet = "magnet:?xt=urn:btih:" + "c".repeat(40)
        val started = AtomicBoolean()
        val touches = AtomicReference("none")
        val attempted = AtomicBoolean()
        val ended = CountDownLatch(1)
        val accepted = AtomicBoolean()
        lateinit var sourceView: TextView
        var sourceWindow: PopupWindow? = null
        val sourcePoint = IntArray(2)
        val targetPoint = IntArray(2)
        var geometry = ""
        val intent = Intent(app, MainActivity::class.java).putExtra("fixtureId", id)
        main { app.registerActivityLifecycleCallbacks(callbacks) }
        try {
            launchFixtureActivity(MainActivity::class.java, intent).use {
                await { active.get()?.findViewById<EditText>(R.id.login_email)?.isShown == true }
                main {
                    val activity = active.get()
                    val root = activity.window.decorView
                    check(root.display.displayId == displayId)
                    val email = activity.findViewById<EditText>(R.id.login_email)
                    val bounds = Rect()
                    check(email.getLocalVisibleRect(bounds))
                    email.getLocationOnScreen(targetPoint)
                    targetPoint[0] += bounds.centerX()
                    targetPoint[1] += bounds.centerY()
                    sourceView = TextView(activity).apply {
                        text = "Fixture magnet"
                        setOnDragListener { _, event ->
                            if (event.action == DragEvent.ACTION_DRAG_ENDED) {
                                accepted.set(event.result)
                                ended.countDown()
                            }
                            event.action != DragEvent.ACTION_DROP
                        }
                        setOnTouchListener { _, event ->
                            val action = event.actionMasked
                            if (action != MotionEvent.ACTION_MOVE) touches.updateAndGet { "$it/${MotionEvent.actionToString(action)}" }
                            if (action == MotionEvent.ACTION_DOWN) {
                                // Initiate while the pointer is still held, independently of Samsung's long-press preference.
                                postDelayed({
                                    attempted.set(true)
                                    started.set(startDragAndDrop(ClipData.newRawUri("Magnet", Uri.parse(magnet)),
                                        View.DragShadowBuilder(this), null, View.DRAG_FLAG_GLOBAL))
                                }, 80L)
                            }
                            true
                        }
                    }
                    val edge = activity.dp(72)
                    val visible = Rect().also(root::getWindowVisibleDisplayFrame)
                    val origin = IntArray(2).also(root::getLocationOnScreen)
                    geometry = "root=${origin.contentToString()}+${root.width}x${root.height} visible=$visible"
                    val y = if (targetPoint[1] < visible.centerY()) visible.bottom - 2 * edge else visible.top + edge
                    sourceWindow = PopupWindow(sourceView, edge, edge, false).apply {
                        isTouchModal = false
                        showAtLocation(root, Gravity.TOP or Gravity.LEFT, edge / 2, y)
                    }
                }
                await { sourceView.isShown && sourceView.width > 0 }
                main {
                    sourceView.getLocationOnScreen(sourcePoint)
                    geometry += " popup=${sourcePoint.contentToString()}+${sourceView.width}x${sourceView.height}"
                    sourcePoint[0] += sourceView.width / 2
                    sourcePoint[1] += sourceView.height / 2
                }
                val points = "$displayId ${sourcePoint[0]} ${sourcePoint[1]} ${targetPoint[0]} ${targetPoint[1]}"
                instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\nDRAG_READY $points\n$geometry\n") })
                if (driver == "instrumentation") injectDrag(displayId, sourcePoint, targetPoint)
                val finished = ended.await(if (driver == "host") 45 else 4, TimeUnit.SECONDS)
                assertTrue("Drag failed: $points $geometry touches=${touches.get()} attempted=${attempted.get()} started=${started.get()} ended=$finished accepted=${accepted.get()}",
                    started.get() && finished && accepted.get())
                main {
                    sourceWindow?.dismiss()
                    val activity = active.get()
                    assertEquals("", activity.findViewById<EditText>(R.id.login_email).text.toString())
                    activity.model.login("$id@example.invalid", "fixture") {}
                }
                fun confirmation() = WindowInspector.getGlobalWindowViews().firstOrNull { view ->
                    view.display?.displayId == displayId && view.findViewById<EditText>(R.id.incoming_link)?.text?.toString() == magnet
                }
                await { confirmation() != null }
                main { check(checkNotNull(confirmation()).findViewById<View>(android.R.id.button2).performClick()) }
                await { confirmation() == null }
                assertEquals(0, submissions.get())
            }
        } finally {
            main {
                sourceWindow?.dismiss()
                app.unregisterActivityLifecycleCallbacks(callbacks)
            }
            app.deleteSharedPreferences(prefs)
        }
    }

    @android.annotation.TargetApi(34)
    private fun injectDrag(display: Int, start: IntArray, end: IntArray) {
        val down = SystemClock.uptimeMillis()
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        fun send(action: Int, position: Float) {
            val coords = MotionEvent.PointerCoords().apply {
                x = start[0] + (end[0] - start[0]) * position
                y = start[1] + (end[1] - start[1]) * position
                pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                size = 1f
            }
            val event = checkNotNull(MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, display, 0, MotionEvent.CLASSIFICATION_NONE))
            try {
                check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "Could not inject gesture on display $display: action=$action" }
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        try {
            SystemClock.sleep(300)
            for (step in 1..12) {
                send(MotionEvent.ACTION_MOVE, step / 12f)
                SystemClock.sleep(30)
            }
        } finally {
            send(MotionEvent.ACTION_UP, 1f)
        }
    }

    private fun fixtureCallbacks(id: String, active: AtomicReference<MainActivity>, makeModel: () -> CloudModel) =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, state: Bundle?) {
                if (activity !is MainActivity || activity.intent.getStringExtra("fixtureId") != id) return
                // Preload this activity's default model key before the production by-viewModels delegate reads it.
                ViewModelProvider(activity, viewModelFactory { initializer { makeModel() } })[CloudModel::class.java]
                active.set(activity)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        do {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("External link confirmation did not settle")
    }
}
