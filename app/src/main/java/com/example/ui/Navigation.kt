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
                onNavigateToPlayer = { uri ->
                    val encoded = Uri.encode(uri)
                    navController.navigate("player/$encoded")
                }
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            "player/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val uri = Uri.decode(backStackEntry.arguments?.getString("uri"))
            VideoPlayerScreen(
                videoUriStr = uri,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
