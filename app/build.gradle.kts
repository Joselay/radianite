import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Stamps each build with the commit it came from, so the installed app identifies itself.
fun git(vararg args: String) = providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim()
val commitCount = git("rev-list", "--count", "HEAD").toInt()
val commit = git("rev-parse", "--short", "HEAD") + if (git("status", "--porcelain").isNotEmpty()) "-dirty" else ""

// A keystore.properties at the project root (kept out of git) signs release builds for distributing;
// without one they fall back to the debug key, which is fine for sideloading onto your own phone.
val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

android {
    namespace = "app.radianite"
    compileSdk = 37

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "app.radianite"
        minSdk = 28
        targetSdk = 36
        versionCode = commitCount
        // Release tags are "v" and the version code, so the installed label and the update offered read the same.
        versionName = "$commitCount ($commit)"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The app is in English only; this drops the translations its libraries bring.
    androidResources {
        localeFilters += "en"
    }

    // Metadata only Kotlin reflection, debuggers and build tools read; the libraries' licenses stay.
    packaging {
        resources.excludes += listOf("kotlin/**.kotlin_builtins", "META-INF/*.version", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Data modules log best-effort failures with android.util.Log; in JVM tests that's a no-op.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.work.runtime)
    implementation(libs.glance.appwidget)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
