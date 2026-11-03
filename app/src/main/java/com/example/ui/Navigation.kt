package com.example.ui

import android.net.Uri
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.RecordingsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VideoPlayerScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                onNavigateToSettings = {
                    navController.navigate("settings")
                },
                onNavigateToRecordings = {
                    navController.navigate("recordings")
                }
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("recordings") {
            RecordingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToPlayer = { uri, companion ->
                    val encodedUri = Uri.encode(uri)
                    val encodedCompanion = if (companion != null) Uri.encode(companion) else "none"
                    navController.navigate("player/$encodedUri/$encodedCompanion")
                }
            )
        }

        composable(
            route = "player/{uri}/{companion}",
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("companion") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val uriArg = backStackEntry.arguments?.getString("uri") ?: ""
            val companionArg = backStackEntry.arguments?.getString("companion")
            val decodedUri = Uri.decode(uriArg)
            val decodedCompanion = if (companionArg != null && companionArg != "none") {
                Uri.decode(companionArg)
            } else {
                null
            }

            VideoPlayerScreen(
                videoUriStr = decodedUri,
                companionUriStr = decodedCompanion,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
