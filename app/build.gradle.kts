import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// google-services.json carries the Firebase project identity and is not in
// this public repo. The plugin is applied only when the file is present, the
// same way the release signing config exists only with ARKPHONE_STORE_FILE —
// a checkout without it still builds, just without push wake-up.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val appVersionName = "1.27"

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// APKs build as ARK-phone-<version>-<variant>.apk so a downloaded file
// always tells which version it is.
base {
    archivesName.set("ARK-phone-$appVersionName")
}

android {
    namespace = "org.jarsi.arkphone"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.jarsi.arkphone"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = appVersionName
        // The ARK signaling worker. The address sits inside every public APK,
        // so it is no secret; local.properties overrides it for a self-hosted
        // worker (arkphone.voip.workerUrl).
        buildConfigField(
            "String",
            "VOIP_WORKER_URL",
            "\"${localProps.getProperty("arkphone.voip.workerUrl") ?: "https://arkphone-voip.jarsi.workers.dev"}\"",
        )
    }

    // Credentials live in the user's ~/.gradle/gradle.properties, never in the
    // repo. Without them the release build stays unsigned instead of failing.
    val releaseStoreFile = providers.gradleProperty("ARKPHONE_STORE_FILE").orNull
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("ARKPHONE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("ARKPHONE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("ARKPHONE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        // Every variant names itself in versionName so the settings page
        // always tells which build is installed (field confusion 2026-08-20:
        // debug and release both read as a bare "1.26").
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            versionNameSuffix = "-release"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Family field-test build: release signature, unminified. It installs
        // OVER an installed release build and keeps its data and the dialer
        // role, and it is the fallback should R8 ever misbehave in the field.
        // Never published as a release.
        create("beta") {
            isDebuggable = false
            isMinifyEnabled = false
            versionNameSuffix = "-beta"
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            matchingFallbacks += listOf("debug", "release")
        }
    }
    lint {
        // Version-currency checks flip on upstream releases, not on code
        // changes — they would break the warning-free gate on their own
        // schedule. Everything else must stay clean: a new warning fails.
        disable += listOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
            "OldTargetApi",
        )
        warningsAsErrors = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    androidResources {
        localeFilters += listOf("en", "fi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(libs.androidx.core.telecom)
    implementation(libs.firebase.messaging)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.stream.webrtc)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
