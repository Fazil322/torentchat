import org.gradle.api.tasks.application.CreateStartScripts

plugins {
    kotlin("jvm") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
    application
}

group = "com.torentchat"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.signal:libsignal-client:0.86.5")
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

application {
    mainClass.set("com.torentchat.cli.MainKt")
    applicationName = "torentchat"
}

// Build fat JAR (all deps bundled)
tasks.jar {
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "com.torentchat.cli.MainKt") }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

kotlin { jvmToolchain(17) }
