package com.example.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.RecordingsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VideoPlayerScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNavigateToRecordings = { navController.navigate("recordings") },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable("recordings") {
            RecordingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPlayer = { uri, companion ->
                    val encodedUri = Uri.encode(uri)
                    val encodedCompanion = if (companion != null) Uri.encode(companion) else "none"
                    navController.navigate("player/$encodedUri/$encodedCompanion")
                }
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            "player/{uri}/{companion}",
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("companion") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val uri = Uri.decode(backStackEntry.arguments?.getString("uri"))
            val companionArg = backStackEntry.arguments?.getString("companion")
            val companion = if (companionArg != null && companionArg != "none") Uri.decode(companionArg) else null

            VideoPlayerScreen(
                videoUriStr = uri,
                companionUriStr = companion,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
