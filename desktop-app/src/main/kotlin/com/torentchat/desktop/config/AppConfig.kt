package com.torentchat.desktop.config

object AppConfig {
    const val RELAY_URL = "https://torentchat-worker.ztik-user.workers.dev"
    val DATA_DIR: java.nio.file.Path = java.nio.file.Paths.get(System.getProperty("user.home"), ".torentchat")
}
