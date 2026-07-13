import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.compose") version "1.6.11"
    kotlin("plugin.compose") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
}

group = "com.torentchat"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    // Compose Desktop UI
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Ktor (HTTP client for signaling — JVM/OkHttp)
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Signal Protocol (E2EE) — JVM compatible
    implementation("org.signal:libsignal-client:0.86.5")

    // QR code generation
    implementation("com.google.zxing:core:3.5.3")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

compose.desktop {
    application {
        mainClass = "com.torentchat.desktop.MainKt"

        // Enable ProGuard for release builds
        buildTypes.release.proguard {
            isEnabled = true
            obfuscate = true
            optimize = true
            configFiles.from("proguard-rules.pro")
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "TorentChat"
            packageVersion = "0.1.0"
            description = "P2P encrypted chat — Signal Protocol + WebRTC"
            vendor = "TorentChat"
            copyright = "© 2026 TorentChat. MIT License."

            windows {
                menuGroup = "TorentChat"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }

            // Include native libs
            appResourcesRootDir.set(project.layout.projectDirectory.dir("src/main/resources"))
        }
    }
}

// Java 17 for both compile & runtime
kotlin {
    jvmToolchain(17)
}
