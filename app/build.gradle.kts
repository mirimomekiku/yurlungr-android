import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val runtimeAppId: String = providers.gradleProperty("yurlungr.applicationId").getOrElse("com.termux")

android {
    namespace = "dev.yurlungr.app"
    compileSdk = 36

    defaultConfig {
        applicationId = runtimeAppId
        minSdk = 24
        // Must stay at 28: from targetSdk 29 Android forbids exec() of files in the app's
        // data directory, which is where the embedded runtime's binaries live (same as Termux).
        // Sideload-only as a result; Play Store would reject it.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0-spike"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Spike: sign release with the debug key so it can be sideloaded.
            signingConfig = signingConfigs.getByName("debug")
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
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.termux.terminal.view)
    implementation(libs.java.websocket)

    testImplementation(libs.junit)
}
