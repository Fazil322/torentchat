package com.torentchat.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.torentchat.ui.screens.chat.ChatScreen
import com.torentchat.ui.screens.conversations.ConversationsScreen
import com.torentchat.ui.screens.onboarding.OnboardingScreen
import com.torentchat.ui.screens.profile.ProfileScreen
import com.torentchat.ui.screens.scan.ScanScreen

/** Central navigation graph. */
sealed class Destinations(val route: String) {
    data object Onboarding : Destinations("onboarding")
    data object Conversations : Destinations("conversations")
    data object Scan : Destinations("scan")
    data object Profile : Destinations("profile")
    data object Chat : Destinations("chat/{conversationId}") {
        const val ARG_CONVERSATION_ID = "conversationId"
        fun createRoute(conversationId: String) = "chat/$conversationId"
    }
}

@Composable
fun TorentChatNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Destinations.Onboarding.route,
    ) {
        composable(Destinations.Onboarding.route) {
            OnboardingScreen(
                onCompleted = {
                    navController.navigate(Destinations.Conversations.route) {
                        popUpTo(Destinations.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Destinations.Conversations.route) {
            ConversationsScreen(
                onConversationClick = { conversationId ->
                    navController.navigate(Destinations.Chat.createRoute(conversationId))
                },
                onScanClick = { navController.navigate(Destinations.Scan.route) },
                onProfileClick = { navController.navigate(Destinations.Profile.route) },
            )
        }

        composable(Destinations.Scan.route) {
            ScanScreen(
                onPeerConnected = {
                    navController.popBackStack(Destinations.Conversations.route, inclusive = false)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Destinations.Profile.route) {
            ProfileScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Destinations.Chat.route,
            arguments = listOf(
                navArgument(Destinations.Chat.ARG_CONVERSATION_ID) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val conversationId =
                backStackEntry.arguments?.getString(Destinations.Chat.ARG_CONVERSATION_ID).orEmpty()
            ChatScreen(
                conversationId = conversationId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
