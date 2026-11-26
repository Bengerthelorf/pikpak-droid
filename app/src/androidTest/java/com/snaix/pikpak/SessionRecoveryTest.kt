package com.snaix.pikpak

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SessionRecoveryTest {
    @Test
    fun retriesSavedSessionAndRecoversAnInterruptedReadWithoutSigningIn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val restores = AtomicInteger()
        val listings = AtomicInteger()
        val fixture = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> {
                    if (restores.incrementAndGet() == 1) throw CloudError("request", "Temporary local read failure")
                    true
                }
                "files" -> {
                    if (listings.incrementAndGet() == 1) throw CloudError("connect", "Connection interrupted")
                    JSONArray().put(JSONObject().put("id", "session-fixture").put("name", "Recovered file"))
                }
                else -> error("Unexpected fixture operation: $operation")
            }
        }
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        lateinit var model: CloudModel
        instrumentation.runOnMainSync {
            model = ViewModelProvider(owner, viewModelFactory {
                initializer { CloudModel(application, fixture) }
            })[CloudModel::class.java]
        }
        try {
            await { !model.starting && model.canRetrySession }
            instrumentation.runOnMainSync { model.restoreSession() }
            await { model.signedIn && !model.loading && model.entries.singleOrNull()?.id == "session-fixture" }
            instrumentation.runOnMainSync {
                assertTrue(model.signedIn && !model.canRetrySession)
                assertEquals(null, model.authError)
                assertEquals(null, model.loadError)
            }
            assertEquals(2, restores.get())
            assertEquals(2, listings.get())
        } finally {
            instrumentation.runOnMainSync { owner.viewModelStore.clear() }
        }
    }

    @Test
    fun signInKeepsTransferHistoryOnlyForTheSameAccount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val prefs = "session-history-fixture-${UUID.randomUUID()}"
        val history = TransferHistory(application.getSharedPreferences(prefs, Application.MODE_PRIVATE))
        val receipt = LocalTransfer(
            id = "session-history-fixture",
            tab = TaskTab.Download,
            title = "Account-specific file.txt",
            startedAt = 1,
            progress = TransferProgress(completed = 1, total = 1),
            status = TransferStatus.Completed,
        )
        history.save(listOf(receipt))
        val expireNextRead = AtomicBoolean(false)
        val gateway = object : Gateway {
            override fun call(operation: String, vararg args: Pair<String, Any?>): Any? = when (operation) {
                "init" -> true
                "login" -> null
                "files" -> {
                    if (expireNextRead.getAndSet(false)) throw CloudError("auth", "Fixture sign-in expired")
                    JSONArray()
                }
                else -> error("Unexpected fixture operation: $operation")
            }
        }
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        lateinit var model: CloudModel
        instrumentation.runOnMainSync {
            model = ViewModelProvider(owner, viewModelFactory {
                initializer { CloudModel(application, gateway, transferHistory = history) }
            })[CloudModel::class.java]
        }

        fun expire() {
            expireNextRead.set(true)
            instrumentation.runOnMainSync { model.refresh() }
            await { !model.signedIn && !model.loading }
        }

        fun signIn(email: String) {
            instrumentation.runOnMainSync { model.login(email, "fixture-password") {} }
            await { model.signedIn && !model.loading && model.busy == null }
        }

        try {
            await { model.signedIn && !model.loading }
            instrumentation.runOnMainSync { assertEquals(listOf(receipt), model.localTransfers) }

            expire()
            signIn("first@example.test")
            instrumentation.runOnMainSync { assertTrue(model.localTransfers.isEmpty()) }
            assertTrue(history.load().isEmpty())
            history.save(listOf(receipt))

            expire()
            signIn(" first@example.test ")
            instrumentation.runOnMainSync { assertEquals(listOf(receipt), model.localTransfers) }

            expire()
            signIn("second@example.test")
            instrumentation.runOnMainSync { assertTrue(model.localTransfers.isEmpty()) }
            assertTrue(history.load().isEmpty())
        } finally {
            instrumentation.runOnMainSync { owner.viewModelStore.clear() }
            application.deleteSharedPreferences(prefs)
        }
    }

    @Test
    fun anUncertainSignInKeepsPreviousHistoryHiddenUntilOwnershipIsProven() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = "session-owner-fixture-${UUID.randomUUID()}"
        val store = context.getSharedPreferences(prefs, Application.MODE_PRIVATE)
        val history = TransferHistory(store)
        val receipt = LocalTransfer(
            "first", TaskTab.Download, "First account.txt", 1, TransferProgress(), TransferStatus.Completed,
        )
        try {
            history.authenticate("first@example.test") {}
            history.save(listOf(receipt))
            val stale = TransferHistory(store)
            val rejected = CloudError("login", "Fixture password rejected")
            assertEquals(rejected, runCatching {
                history.authenticate("second@example.test") { throw rejected }
            }.exceptionOrNull())
            assertEquals(listOf(receipt), TransferHistory(store).load())

            val uncertain = CloudError("session_save", "Fixture save interrupted")
            assertEquals(uncertain, runCatching {
                history.authenticate("second@example.test") { throw uncertain }
            }.exceptionOrNull())
            val recovered = TransferHistory(store)
            assertTrue(recovered.load().isEmpty())
            recovered.save(listOf(receipt.copy(title = "Unverified account.txt")))
            recovered.authenticate("first@example.test") {}
            assertEquals(listOf(receipt), recovered.load())

            recovered.authenticate("second@example.test") {}
            stale.save(listOf(receipt))
            assertTrue(recovered.load().isEmpty())
            val secondReceipt = receipt.copy(id = "second", title = "Second account.txt")
            recovered.save(listOf(secondReceipt))
            stale.clear()
            assertEquals(listOf(secondReceipt), recovered.load())

            val thirdSignIn = runCatching { recovered.authenticate("third@example.test") { throw uncertain } }
            assertEquals(uncertain, thirdSignIn.exceptionOrNull())
            // Explicit logout clears the record even when its visibility is suspended.
            recovered.clear()
            recovered.authenticate("second@example.test") {}
            assertTrue(recovered.load().isEmpty())
        } finally {
            context.deleteSharedPreferences(prefs)
        }
    }

    private fun await(condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            var matched = false
            instrumentation.runOnMainSync { matched = condition() }
            if (matched) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Session recovery did not settle")
    }
}
