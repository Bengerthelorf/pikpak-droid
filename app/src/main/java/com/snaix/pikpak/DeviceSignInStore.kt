package com.snaix.pikpak

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SignInCredentials(val email: String, val password: String)

internal interface RememberedSignIn {
    fun save(email: String, password: String)
    fun takeForRetry(): SignInCredentials?
    fun clear()
}

internal class DeviceSignInStore(
    context: Context,
    name: String = "remembered-sign-in-v1",
    private val now: () -> Long = System::currentTimeMillis,
) : RememberedSignIn {
    private val file = File(context.noBackupFilesDir, name)
    private val staging = File(context.noBackupFilesDir, "$name.tmp")
    private val lockFile = File(context.noBackupFilesDir, "$name.lock")
    private val alias = "${context.packageName}.$name"

    override fun save(email: String, password: String) = locked {
        require(email.isNotBlank() && password.isNotEmpty())
        try {
            val previous = read()
            val lastAttempt = previous?.takeIf { it.optString("email") == email }?.optLong("lastAttempt") ?: 0L
            write(JSONObject().put("email", email).put("password", password).put("lastAttempt", lastAttempt))
        } catch (error: Exception) {
            // After a password or account change, an older password must never become eligible again.
            erase()
            throw IOException(appString(R.string.model_device_login_failed), error)
        }
    }

    override fun takeForRetry(): SignInCredentials? = locked {
        val record = read() ?: return@locked null
        val password = record.optString("password")
        val attempted = record.optLong("lastAttempt")
        val timestamp = now()
        if (password.isEmpty() || (attempted > 0 && timestamp - attempted in 0L until COOLDOWN_MS)) {
            return@locked null
        }
        val email = record.getString("email")
        // Commit consumption before returning the password, including across concurrent callers and restarts.
        write(JSONObject().put("email", email).put("lastAttempt", timestamp))
        SignInCredentials(email, password)
    }

    override fun clear() = locked { erase() }

    fun hasSavedPassword(): Boolean = locked { read()?.optString("password").orEmpty().isNotEmpty() }

    private fun <T> locked(block: () -> T): T = synchronized(processLock) {
        FileOutputStream(lockFile, true).channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun read(): JSONObject? {
        if (!file.exists()) return null
        val plain = try {
            val key = keyStore().getKey(alias, null) as? SecretKey ?: return null
            val encrypted = file.readBytes()
            if (encrypted.size < IV_BYTES + 16) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted, 0, IV_BYTES))
            cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES)
        } catch (_: AEADBadTagException) {
            erase()
            return null
        } catch (_: KeyPermanentlyInvalidatedException) {
            erase()
            return null
        }
        return try {
            JSONObject(plain.toString(Charsets.UTF_8))
        } finally {
            plain.fill(0)
        }
    }

    private fun write(record: JSONObject) {
        val key = keyStore().getKey(alias, null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = cipher.iv
        check(iv.size == IV_BYTES)
        val plain = record.toString().toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(staging).use { out ->
                out.write(iv)
                out.write(cipher.doFinal(plain))
                out.fd.sync()
            }
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            plain.fill(0)
            Files.deleteIfExists(staging.toPath())
        }
    }

    private fun erase() {
        // A first login with password storage disabled must also work on a device with no available keystore.
        if (!file.exists() && !staging.exists()) return
        try {
            Files.deleteIfExists(file.toPath())
            Files.deleteIfExists(staging.toPath())
        } finally {
            val keys = keyStore()
            if (keys.containsAlias(alias)) keys.deleteEntry(alias)
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val COOLDOWN_MS = 10 * 60_000L
        val processLock = Any()
    }
}
