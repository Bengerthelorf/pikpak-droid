import groovy.json.JsonSlurper
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest

plugins {
    id("com.android.application")
}

providers.environmentVariable("PIKPAK_APP_BUILD_DIR").orNull?.let {
    layout.buildDirectory.set(file(it))
}

val bundle = rootProject.layout.projectDirectory.dir("core/bundled")
val overridePath = providers.gradleProperty("pikpakCoreLibrary").orNull
val coreFile = overridePath?.let { file(it) } ?: bundle.file("pikpaktui-android-arm64-v8a-api1.so").asFile
val pin = if (overridePath == null) {
    val manifest = bundle.file("manifest.json")
    val fields = JsonSlurper().parseText(providers.fileContents(manifest).asText.get()) as Map<*, *>
    require(fields["abi"] == "arm64-v8a" && (fields["source"] as? String).orEmpty().isNotBlank()) {
        "Bundled core manifest must identify the upstream source and arm64-v8a ABI"
    }
    require(!fields.containsKey("api") || fields["api"] == 1) { "Expected Android JNI API 1" }
    fields
} else emptyMap<String, String>()

fun coreField(key: String, property: String): String {
    val value = if (overridePath == null) pin[key] as? String else providers.gradleProperty(property).orNull
    return requireNotNull(value?.takeIf { it.isNotBlank() }) {
        if (overridePath == null) "Bundled core manifest is missing $key" else "Set -P$property when overriding pikpakCoreLibrary"
    }
}

val packagedCoreVersion = coreField("version", "pikpakCoreVersion").removePrefix("v")
val packagedCoreRevision = coreField("revision", "pikpakCoreRevision")
val expectedCoreSha = coreField("sha256", "pikpakCoreSha256").lowercase()
require(Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""").matches(packagedCoreVersion)) {
    "Expected a three-part upstream core version"
}
require(Regex("[0-9a-f]{40}").matches(packagedCoreRevision)) { "Expected the full upstream Git revision" }
require(Regex("[0-9a-f]{64}").matches(expectedCoreSha)) { "Expected the full SHA-256 of the core binary" }
require(coreFile.isFile) { "Missing Android core binary: $coreFile" }

android {
    namespace = "com.snaix.pikpak"
    compileSdk = 37
    compileSdkMinor = 1
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.snaix.pikpak"
        minSdk = 26
        targetSdk = 36
        versionCode = 33
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk.abiFilters += "arm64-v8a"
        resValue("string", "builtin_core_version", packagedCoreVersion)
        resValue("string", "builtin_core_revision", packagedCoreRevision)
    }

    buildFeatures.resValues = true
    // Preserve the upstream release bytes in the APK, including its published digest.
    packaging.jniLibs.keepDebugSymbols.add("**/libpikpak_android.so")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

val jniOutput = layout.buildDirectory.dir("prebuilt-jni")
val prepareCore = tasks.register<Sync>("stagePrebuiltCore") {
    from(coreFile) {
        into("arm64-v8a")
        rename { "libpikpak_android.so" }
    }
    into(jniOutput)
    inputs.file(coreFile)
    inputs.property("expectedSha256", expectedCoreSha)
    // Verify the actual packaging input on every build, including incremental ones.
    outputs.upToDateWhen { false }
    doLast {
        val staged = jniOutput.get().file("arm64-v8a/libpikpak_android.so").asFile
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(staged.inputStream().buffered(), digest).use { input ->
            val elf = input.readNBytes(20)
            require(elf.size == 20 && elf.copyOfRange(0, 6).contentEquals(byteArrayOf(127, 69, 76, 70, 2, 1)) &&
                elf[16] == 3.toByte() && elf[17] == 0.toByte() && elf[18] == 183.toByte() && elf[19] == 0.toByte()) {
                "Expected an ARM64 ELF shared library"
            }
            input.transferTo(OutputStream.nullOutputStream())
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == expectedCoreSha) { "Core SHA-256 mismatch: expected $expectedCoreSha, got $actual" }
    }
}

android.sourceSets.getByName("main").jniLibs.directories.add(jniOutput.get().asFile.absolutePath)
tasks.named("preBuild") { dependsOn(prepareCore) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// SESL artifacts replace Jetpack classes under the same AndroidX namespaces.
configurations.configureEach {
    listOf(
        "androidx.core" to "core", "androidx.core" to "core-ktx",
        "androidx.appcompat" to "appcompat",
        "androidx.fragment" to "fragment", "androidx.fragment" to "fragment-ktx",
        "androidx.customview" to "customview",
        "androidx.coordinatorlayout" to "coordinatorlayout",
        "androidx.drawerlayout" to "drawerlayout",
        "androidx.viewpager" to "viewpager", "androidx.viewpager2" to "viewpager2",
        "androidx.preference" to "preference", "androidx.recyclerview" to "recyclerview",
        "androidx.slidingpanelayout" to "slidingpanelayout",
        "androidx.swiperefreshlayout" to "swiperefreshlayout",
        "com.google.android.material" to "material",
    ).forEach { (group, module) -> exclude(group = group, module = module) }
}

dependencies {
    implementation("io.github.tribalfs:oneui-design:0.9.20+oneui8") {
        // Optional picker modules share an Android namespace. This app uses the system picker.
        exclude(group = "sesl.androidx.picker", module = "picker-app")
        exclude(group = "sesl.androidx.picker", module = "picker-color")
    }
    implementation("io.github.oneuiproject:icons:1.1.0")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
