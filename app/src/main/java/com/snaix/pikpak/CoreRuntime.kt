package com.snaix.pikpak

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Process
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class DroidApplication : Application() {
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguages.initialize(this)
    }

    override fun onCreate() {
        super.onCreate()
        AppLanguages.initialize(this)
        val name = if (Build.VERSION.SDK_INT >= 28) getProcessName()
            else File("/proc/self/cmdline").inputStream().bufferedReader().use { it.readLine()?.substringBefore('\u0000') }
        if (name != "$packageName:core_probe") CoreRuntime.start(this)
    }
}

internal data class CoreVersion(val version: String, val revision: String, val sha256: String = "") {
    val description get() = "$version · ${revision.take(8)}"
}

internal object CoreRuntime {
    private val digestPattern = Regex("[0-9a-f]{64}")
    private const val LIMIT = 128L * 1024 * 1024
    lateinit var active: CoreVersion
        private set
    // API 1 gained nested shares and share_preview before it advertised a capability for them.
    val supportsShareBrowsing: Boolean get() = ::active.isInitialized &&
        runCatching { !CoreReleases.newer("0.0.67", active.version) }.getOrDefault(false)
    var recovery: String? = null
        private set
    private fun prefs(context: Context) = context.getSharedPreferences("core_runtime", Context.MODE_PRIVATE)
    private fun dir(context: Context) = File(context.noBackupFilesDir, "cores").apply { mkdirs() }
    fun bundled(context: Context) = CoreVersion(context.getString(R.string.builtin_core_version),
        context.getString(R.string.builtin_core_revision))
    fun pending(context: Context): CoreVersion = prefs(context).let {
        val sha = it.getString("sha", "").orEmpty()
        if (digestPattern.matches(sha)) CoreVersion(it.getString("version", "").orEmpty(),
            it.getString("revision", "").orEmpty(), sha) else bundled(context)
    }
    fun select(context: Context, version: CoreVersion) {
        check(prefs(context).edit().putString("sha", version.sha256).putString("version", version.version)
            .putString("revision", version.revision).remove("starting").commit()) { appString(R.string.model_core_settings_failed) }
    }
    private fun library(context: Context, sha: String): File {
        require(digestPattern.matches(sha)) { appString(R.string.model_core_need_sha) }
        return File(dir(context), "$sha.so")
    }
    private fun hash(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun stage(context: Context, expected: String, write: (File) -> Unit): String {
        val sha = expected.trim().lowercase()
        val target = library(context, sha)
        val temp = File.createTempFile("staging-", ".tmp", dir(context))
        try {
            write(temp)
            require(temp.length() in 64L..LIMIT && hash(temp) == sha) { appString(R.string.model_core_mismatch) }
            val header = ByteArray(20)
            temp.inputStream().use { java.io.DataInputStream(it).readFully(header) }
            require(header.copyOfRange(0, 6).contentEquals(byteArrayOf(127, 69, 76, 70, 2, 1))
                && header[16] == 3.toByte() && header[17] == 0.toByte()
                && header[18] == 183.toByte() && header[19] == 0.toByte()) { appString(R.string.model_core_choose_arm) }
            check(temp.setReadOnly()) { appString(R.string.model_core_lock_failed) }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return sha
        } finally { temp.delete() }
    }
    fun importDevelopment(context: Context, expected: String, source: InputStream): String = source.use { input ->
        require((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        stage(context, expected) { temp ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var bytes = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes += n
                    require(bytes <= LIMIT) { appString(R.string.model_core_too_large) }
                    output.write(buffer, 0, n)
                }
            }
        }
    }
    fun probe(context: Context, sha: String): CoreVersion {
        val file = library(context, sha)
        require(file.isFile && hash(file) == sha)
        System.load(file.absolutePath)
        val reply = JSONObject(NativeClient.request("""{"op":"core_info"}""")).getJSONObject("value")
        require(reply.getInt("api") == 1) { appString(R.string.model_core_incompatible) }
        val revision = reply.getString("upstream")
        require(revision.matches(Regex("[a-f0-9]{40}"))) { appString(R.string.model_core_no_revision) }
        return CoreVersion(reply.optString("version").removePrefix("v").ifBlank { appString(R.string.model_core_development) }, revision, sha)
    }
    @Volatile private var healthRecorded = false
    fun recordHealthy(context: Context) {
        if (!healthRecorded && ::active.isInitialized && active.sha256.isNotEmpty()) {
            healthRecorded = prefs(context).edit().putString("healthy", active.sha256).remove("starting").commit()
        }
    }
    fun start(context: Context) {
        val builtin = bundled(context)
        val choice = pending(context)
        val prefs = prefs(context)
        val superseded = choice.sha256.isNotEmpty() && runCatching {
            CoreReleases.newer(builtin.version, choice.version)
        }.getOrDefault(false)
        var loaded = false
        if (choice.sha256.isNotEmpty() && !superseded) {
            loaded = runCatching {
                require(prefs.getString("starting", null) != choice.sha256) { appString(R.string.model_core_start_incomplete) }
                val file = library(context, choice.sha256)
                require(file.isFile && hash(file) == choice.sha256) { appString(R.string.model_core_bad_binary) }
                check(prefs.edit().putString("starting", choice.sha256).commit())
                System.load(file.absolutePath)
            }.isSuccess
        }
        if (loaded) {
            active = choice
            // Keep the first-activation crash marker until a JNI request actually returns.
            if (prefs.getString("healthy", null) == choice.sha256) prefs.edit().remove("starting").commit()
        } else {
            System.loadLibrary("pikpak_android")
            active = builtin
            if (choice.sha256.isNotEmpty()) {
                recovery = if (superseded) appString(R.string.model_core_using_new_builtin) else appString(R.string.model_core_recovered)
                prefs.edit().remove("sha").remove("version").remove("revision").remove("starting").commit()
            }
        }
        // A second probe can be running while Android recreates the main process.
        dir(context).listFiles()?.filter { it.name != "${active.sha256}.so" &&
            it.lastModified() < System.currentTimeMillis() - 86_400_000L }?.forEach(File::delete)
    }
}

/** Validate JNI in a short-lived process before selecting the binary for the main process. */
class CoreProbeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CoreRuntime.probe(this@CoreProbeActivity, intent.getStringExtra("sha").orEmpty()) }
            }
            val data = Intent()
            result.onSuccess { data.putExtra("version", it.version).putExtra("revision", it.revision).putExtra("sha", it.sha256) }
                .onFailure { data.putExtra("error", appString(R.string.model_core_probe_failed)) }
            setResult(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, data)
            finish()
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) Process.killProcess(Process.myPid())
    }
}
