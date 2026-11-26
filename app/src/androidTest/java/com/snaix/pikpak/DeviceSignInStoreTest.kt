package com.snaix.pikpak

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class DeviceSignInStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "remembered-sign-in-test-${UUID.randomUUID()}"
    private val clock = AtomicLong(1_000_000)
    private val file = File(context.noBackupFilesDir, name)
    private val alias = "${context.packageName}.$name"
    private fun reopen() = DeviceSignInStore(context, name, clock::get)
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After fun cleanup() {
        reopen().clear()
        File(context.noBackupFilesDir, "$name.lock").delete()
    }

    @Test fun encryptedPasswordCanBeClaimedOnceAndSuccessfulRetryKeepsCooldownAfterRestart() {
        val store = reopen()
        val email = "fixture@example.test"
        val password = "a-long-fixture-password-本地"
        assertNull(store.takeForRetry())
        assertFalse(store.hasSavedPassword())
        assertFalse(keys().containsAlias(alias))

        store.save(email, password)
        assertTrue(reopen().hasSavedPassword())
        assertNull(keys().getKey(alias, null).encoded)
        val payload = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(payload.contains(email))
        assertFalse(payload.contains("a-long-fixture-password"))

        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = List(2) {
                executor.submit(Callable {
                    check(start.await(5, TimeUnit.SECONDS))
                    reopen().takeForRetry()
                })
            }
            start.countDown()
            val claim = attempts.mapNotNull { it.get(10, TimeUnit.SECONDS) }.single()
            assertEquals(email, claim.email)
            assertTrue(claim.password == password)
            assertFalse(claim.toString().contains(password))
        } finally {
            executor.shutdownNow()
        }
        assertFalse(reopen().hasSavedPassword())
        clock.addAndGet(60_000L)
        store.save(email, password)
        assertTrue(reopen().hasSavedPassword())
        assertNull(reopen().takeForRetry())
        clock.addAndGet(9 * 60_000L)
        assertEquals(email, reopen().takeForRetry()?.email)
        assertFalse(reopen().hasSavedPassword())
    }

    @Test fun switchingAccountReplacesPreviousCredentialsAndForgetRemovesKeyAndCiphertext() {
        val store = reopen()
        store.save("first@example.test", "fixture-first")
        assertEquals("first@example.test", store.takeForRetry()?.email)
        store.save("first@example.test", "fixture-first")
        store.save("second@example.test", "fixture-second")
        val second = checkNotNull(reopen().takeForRetry())
        assertEquals("second@example.test", second.email)
        assertTrue(second.password == "fixture-second")
        store.save(second.email, second.password)
        store.clear()
        assertFalse(file.exists())
        assertFalse(keys().containsAlias(alias))
        assertFalse(reopen().hasSavedPassword())
        assertNull(reopen().takeForRetry())
    }

    @Test fun damagedCiphertextCannotBeUsedForSignIn() {
        val store = reopen()
        store.save("fixture@example.test", "before-damage")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(reopen().takeForRetry())
        assertFalse(reopen().hasSavedPassword())
        store.save("fixture@example.test", "restored")
        assertTrue(reopen().takeForRetry()?.password == "restored")
    }
}
