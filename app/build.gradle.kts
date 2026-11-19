import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val runtimeAppId: String = providers.gradleProperty("yurlungr.applicationId").getOrElse("com.termux")

// Release signing (see DEVELOPMENT.md, "Release signing"). Each value comes from a Gradle property
// (-P or ~/.gradle/gradle.properties), an environment variable, or local.properties, in that order.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(name: String): String? =
    (providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull ?: localProperties.getProperty(name))
        ?.takeIf { it.isNotBlank() }
val releaseKeystore: File? = signingValue("YURLUNGR_KEYSTORE")?.let { rootProject.file(it) }
// CI release builds set this so a missing key fails the build instead of shipping debug-signed APKs.
val requireReleaseKey = signingValue("YURLUNGR_REQUIRE_RELEASE_KEY").toBoolean()
if (releaseKeystore != null && !releaseKeystore.isFile) {
    throw GradleException("YURLUNGR_KEYSTORE points to $releaseKeystore, which doesn't exist.")
}
if (releaseKeystore == null && requireReleaseKey) {
    throw GradleException("YURLUNGR_REQUIRE_RELEASE_KEY is set but no release key is configured (YURLUNGR_KEYSTORE).")
}

// Keep the matching upstream Java classes, but replace JitPack's 4 KB JNI binaries
// with libtermux built below. A generated AAR avoids duplicate-library pickFirst rules.
val termuxEmulatorArchive by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val prepareTermuxEmulator by tasks.registering(Zip::class) {
    from(termuxEmulatorArchive.elements.map { zipTree(it.single().asFile) }) { exclude("jni/**") }
    destinationDirectory.set(layout.buildDirectory.dir("termux-emulator"))
    archiveFileName.set("terminal-emulator-java.aar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

android {
    namespace = "dev.yurlungr.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = runtimeAppId
        minSdk = 24
        testInstrumentationRunner = "dev.yurlungr.app.web.WebRunSmokeInstrumentation"
        // Must stay at 28: from targetSdk 29 Android forbids exec() of files in the app's
        // data directory, which is where the embedded runtime's binaries live (same as Termux).
        // Sideload-only as a result; Play Store would reject it.
        targetSdk = 28
        versionCode = 5
        versionName = "0.0.5"
    }

    // One APK per CPU architecture (smaller downloads; each carries only its own native libraries),
    // plus a universal APK for anyone unsure which one their phone needs.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("YURLUNGR_KEYSTORE_PASSWORD")
                    ?: throw GradleException("YURLUNGR_KEYSTORE is set but YURLUNGR_KEYSTORE_PASSWORD isn't.")
                keyAlias = signingValue("YURLUNGR_KEY_ALIAS")
                    ?: throw GradleException("YURLUNGR_KEYSTORE is set but YURLUNGR_KEY_ALIAS isn't.")
                // PKCS12 keystores (keytool's default) use the store password for the key too.
                keyPassword = signingValue("YURLUNGR_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            // Off for now; turn on (with keep rules for reflection/JNI) before a wide release.
            isMinifyEnabled = false
            // Without a release key (alpha workflow), release falls back to this machine's debug key so
            // it can be sideloaded over a debug install (same signature keeps the runtime and chats).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // targetSdk 28 is deliberate, see above.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        checkReleaseBuilds = true
    }
}

if (releaseKeystore == null) {
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.project == project && it.name.contains("Release") }) {
            logger.warn("Warning: no release key configured (YURLUNGR_KEYSTORE); release APKs are signed with this machine's debug key.")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.tasklist)
    implementation(libs.commonmark.autolink)
    implementation(libs.commonmark.footnotes)
    implementation(libs.commonmark.frontmatter)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.graphics.path)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.termux.terminal.view) {
        exclude(group = "com.github.termux.termux-app", module = "terminal-emulator")
    }
    termuxEmulatorArchive(libs.termux.terminal.emulator)
    implementation(files(prepareTermuxEmulator.flatMap { it.archiveFile }))
    implementation(libs.java.websocket)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
