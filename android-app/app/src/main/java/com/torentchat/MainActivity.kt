package com.torentchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.torentchat.identity.IdentityManager
import com.torentchat.ui.navigation.TorentChatNavHost
import com.torentchat.ui.theme.TorentChatTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var identityManager: IdentityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Determine start destination: if identity exists, skip onboarding
        val hasIdentity = identityManager.loadIdentity() != null

        setContent {
            TorentChatTheme {
                TorentChatNavHost(startOnboarding = !hasIdentity)
            }
        }
    }
}
