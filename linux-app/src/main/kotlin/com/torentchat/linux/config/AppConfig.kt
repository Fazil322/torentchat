package com.torentchat.linux.config

import java.nio.file.Paths

object AppConfig {
    const val RELAY_URL = "https://torentchat-worker.ztik-user.workers.dev"
    val DATA_DIR = Paths.get(System.getProperty("user.home"), ".torentchat")
}
