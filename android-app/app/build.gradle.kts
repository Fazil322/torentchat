plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.torentchat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.torentchat"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Signaling relay URL — injected from CI (GitHub Actions) or local env.
        // Falls back to placeholder if not set so local dev still works.
        buildConfigField(
            "String",
            "SIGNALING_RELAY_URL",
            "\"${System.getenv("SIGNALING_RELAY_URL") ?: "https://torentchat-worker.example.workers.dev"}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ── AndroidX / Compose ────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ── Hilt ───────────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ── Coroutines ─────────────────────────────────────────────────────────
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // ── Ktor (signaling HTTP/WebSocket client) ─────────────────────────────
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // ── WebRTC (P2P data channels) ─────────────────────────────────────────
    implementation(libs.stream.webrtc.android)

    // ── Signal Protocol (E2EE) ─────────────────────────────────────────────
    // libsignal-android: native .so files; libsignal-client: protocol classes
    implementation(libs.libsignal)
    implementation(libs.libsignal.client)

    // ── SQLCipher + Room (encrypted local storage) ─────────────────────────
    implementation(libs.sqlcipher)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── QR codes ───────────────────────────────────────────────────────────
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    // ── Image loading ──────────────────────────────────────────────────────
    implementation(libs.coil.compose)

    // ── Serialization ──────────────────────────────────────────────────────
    implementation(libs.kotlinx.serialization.json)

    // ── Testing ────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
