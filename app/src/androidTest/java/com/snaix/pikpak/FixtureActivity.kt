package com.snaix.pikpak

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Give a fixture model its own preferences, disk caches and private account data. */
internal class WorkspaceFixtureApplication(private val target: Context) : Application(), AutoCloseable {
    private val directory = Files.createTempDirectory(target.cacheDir.toPath(), "workspace-fixture-").toFile()
    private val preferenceNames = mutableSetOf<String>()

    init { attachBaseContext(target.applicationContext) }

    override fun getApplicationContext(): Context = this
    override fun getCacheDir(): File = directory
    override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
    override fun getNoBackupFilesDir(): File = File(directory, "private").apply { mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val scoped = "${directory.name}-$name"
        synchronized(preferenceNames) { preferenceNames.add(scoped) }
        return target.getSharedPreferences(scoped, MODE_PRIVATE)
    }

    override fun close() {
        synchronized(preferenceNames) { preferenceNames.forEach { target.deleteSharedPreferences(it) } }
        directory.deleteRecursively()
    }
}

internal class FixtureActivity<T : Activity>(private val screen: T) : AutoCloseable {
    fun <R> onActivity(action: (T) -> R): R = onMain("check ${screen.localClassName}") { action(screen) }

    override fun close() {
        onMain("close fixture") { screen.finish() }
    }
}

internal fun <T : Activity> launchFixtureActivity(type: Class<T>, intent: Intent): FixtureActivity<T> {
    val instrument = InstrumentationRegistry.getInstrumentation()
    val application = instrument.targetContext.applicationContext as Application
    val display = InstrumentationRegistry.getArguments().getString("displayId")?.toIntOrNull()
    val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
    return awaitFixtureActivity(type) {
        onMain("start ${type.simpleName} on display $display") {
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            application.startActivity(intent.addFlags(flags), options)
        }
    }
}

internal fun <T : Activity> awaitFixtureActivity(type: Class<T>, launch: () -> Unit): FixtureActivity<T> {
    val instrument = InstrumentationRegistry.getInstrumentation()
    val application = instrument.targetContext.applicationContext as Application
    val display = InstrumentationRegistry.getArguments().getString("displayId")?.toIntOrNull()
    val ready = CountDownLatch(1)
    val active = AtomicReference<T>()

    val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (!type.isInstance(activity)) return
            if (display != null && display != 0 && activity.display?.displayId == display) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            active.set(type.cast(activity))
            ready.countDown()
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    onMain("attach fixture listener") {
        application.registerActivityLifecycleCallbacks(callbacks)
    }
    try {
        launch()
        check(ready.await(15, TimeUnit.SECONDS)) { "${type.simpleName} did not initialize on display $display" }
        // Samsung can relaunch an Activity when it first attaches to a secondary display.
        // Bind the fixture to the instance that actually receives the window focus.
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            val visible = onMain("wait for ${type.simpleName} window") {
                active.get()?.takeIf { !it.isDestroyed && !it.isFinishing && it.hasWindowFocus() }
            }
            if (visible != null) return FixtureActivity(visible)
            SystemClock.sleep(40)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("${type.simpleName} did not receive window focus on display $display")
    } finally {
        onMain("detach fixture listener") { application.unregisterActivityLifecycleCallbacks(callbacks) }
    }
}

private fun <R> onMain(label: String, work: () -> R): R {
    val done = CountDownLatch(1)
    val result = AtomicReference<Result<R>>()
    Handler(Looper.getMainLooper()).post {
        result.set(runCatching(work))
        done.countDown()
    }
    check(done.await(10, TimeUnit.SECONDS)) { "Android UI thread did not finish: $label" }
    return result.get().getOrThrow()
}
