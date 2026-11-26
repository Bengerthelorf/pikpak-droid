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
import java.util.concurrent.atomic.AtomicInteger

class RememberedSessionRecoveryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun expiredReadResumesInTheSameFolderAfterOneSignIn() = withModel { model, server ->
        enterFolder(model)
        server.listError = "auth"
        main { model.refresh() }
        await { model.entries.singleOrNull()?.name == "after sign-in" && !model.loading }
        assertEquals(1, server.logins.get())
        assertEquals(1, server.secretReads.get())
        main { assertEquals("subfolder", model.folder.id) }
    }

    @Test
    fun expiredWriteRestoresTheListingWithoutSubmittingTheWriteAgain() = withModel { model, server ->
        enterFolder(model)
        main { model.command("Fixture write", "rename", "id" to "entry", "name" to "edited") }
        await { model.entries.singleOrNull()?.name == "after sign-in" && !model.loading }
        assertEquals(1, server.writes.get())
        assertEquals(1, server.logins.get())
        main { assertEquals("subfolder", model.folder.id) }
    }

    @Test
    fun networkFailureKeepsSessionWithoutReadingTheSavedPassword() = withModel { model, server ->
        server.listError = "connect"
        main { model.refresh() }
        await { !model.loading && model.loadError != null }
        main { assertTrue(model.signedIn) }
        assertEquals(0, server.logins.get())
        assertEquals(0, server.secretReads.get())
    }

    @Test
    fun rememberedCredentialsForADifferentOwnerCannotSignIn() = withModel { model, server ->
        server.credentialOwner = "different@example.invalid"
        server.listError = "auth"
        main { model.refresh() }
        await { !model.signedIn && !model.starting && server.secretReads.get() == 1 }
        assertEquals(0, server.logins.get())
    }

    private fun enterFolder(model: CloudModel) {
        main { model.openFolder(CloudFile.from(JSONObject().put("id", "subfolder").put("name", "Folder").put("kind", "drive#folder"))) }
        await { !model.loading && model.entries.singleOrNull()?.name == "subfolder listing" }
    }

    private class Server(val email: String) : Gateway, RememberedSignIn {
        val secretReads = AtomicInteger()
        val logins = AtomicInteger()
        val writes = AtomicInteger()
        @Volatile var listError: String? = null
        @Volatile var credentialOwner = email

        override fun takeForRetry(): SignInCredentials {
            secretReads.incrementAndGet()
            return SignInCredentials(credentialOwner, "fixture-secret")
        }
        override fun save(email: String, password: String) = Unit
        override fun clear() = Unit

        override fun call(operation: String, vararg args: Pair<String, Any?>): Any? {
            val parameters = args.toMap()
            return when (operation) {
                "init" -> true
                "files" -> {
                    listError?.let { throw CloudError(it, "Fixture listing failure") }
                    val name = when {
                        logins.get() > 0 -> "after sign-in"
                        parameters["parent"] == "subfolder" -> "subfolder listing"
                        else -> "root listing"
                    }
                    JSONArray().put(JSONObject().put("id", "entry").put("name", name))
                }
                "rename" -> {
                    writes.incrementAndGet()
                    throw CloudError("auth", "Fixture write expired")
                }
                "login" -> {
                    assertEquals(email, parameters["email"])
                    assertEquals("fixture-secret", parameters["password"])
                    logins.incrementAndGet()
                    listError = null
                    null
                }
                else -> error("Unexpected fixture operation: $operation")
            }
        }
    }

    private fun withModel(test: (CloudModel, Server) -> Unit) {
        val app = instrumentation.targetContext.applicationContext as Application
        val id = UUID.randomUUID()
        val preferences = "remembered-recovery-$id"
        val server = Server("$id@example.invalid")
        val history = TransferHistory(app.getSharedPreferences(preferences, Application.MODE_PRIVATE))
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        history.authenticate(server.email) {}
        lateinit var model: CloudModel
        try {
            main {
                model = ViewModelProvider(owner, viewModelFactory {
                    initializer { CloudModel(app, server, transferHistory = history, rememberedSignIn = server) }
                })[CloudModel::class.java]
            }
            await { model.signedIn && !model.starting && !model.loading && model.entries.singleOrNull()?.name == "root listing" }
            test(model, server)
        } finally {
            main { owner.viewModelStore.clear() }
            app.deleteSharedPreferences(preferences)
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    private fun await(ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        do {
            var done = false
            main { done = ready() }
            if (done) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Timed out waiting for session recovery")
    }
}
