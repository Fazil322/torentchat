import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.compose") version "1.6.11"
    kotlin("plugin.compose") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
}

group = "com.torentchat"
version = "0.1.0"

repositories { mavenCentral(); google() }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.signal:libsignal-client:0.86.5")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

compose.desktop {
    application {
        mainClass = "com.torentchat.linux.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "TorentChat"
            packageVersion = "0.1.0"
            description = "P2P encrypted chat — Signal Protocol + WebRTC"
            vendor = "TorentChat"
            copyright = "© 2026 TorentChat. MIT License."
            linux {
                packageName = "torentchat"
                menuGroup = "Network"
            }
        }
    }
}

kotlin { jvmToolchain(17) }
