package com.torentchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.torentchat.ui.navigation.TorentChatNavHost
import com.torentchat.ui.theme.TorentChatTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host for the entire Compose UI.
 *
 * Navigation between screens (onboarding → conversations → chat → scan → profile)
 * is handled by [TorentChatNavHost].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TorentChatTheme {
                TorentChatNavHost()
            }
        }
    }
}
