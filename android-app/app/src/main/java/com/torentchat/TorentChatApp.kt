package com.torentchat

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * Hilt's [HiltAndroidApp] annotation bootstraps the dependency-injection graph.
 * All singleton-scoped bindings (crypto, signaling, WebRTC, database) are
 * constructed lazily from here.
 */
@HiltAndroidApp
class TorentChatApp : Application()
